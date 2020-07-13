/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.engine.state.instance;

import io.zeebe.db.ColumnFamily;
import io.zeebe.db.DbContext;
import io.zeebe.db.ZeebeDb;
import io.zeebe.db.impl.DbCompositeKey;
import io.zeebe.db.impl.DbLong;
import io.zeebe.db.impl.DbNil;
import io.zeebe.db.impl.DbString;
import io.zeebe.engine.Loggers;
import io.zeebe.engine.metrics.JobMetrics;
import io.zeebe.engine.state.ZbColumnFamilies;
import io.zeebe.protocol.impl.record.value.job.JobRecord;
import io.zeebe.util.EnsureUtil;
import io.zeebe.util.buffer.BufferUtil;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import org.agrona.DirectBuffer;
import org.slf4j.Logger;

public final class JobState {

  private static final Logger LOG = Loggers.WORKFLOW_PROCESSOR_LOGGER;

  // key => job record value
  // we need two separate wrapper to not interfere with get and put
  // see https://github.com/zeebe-io/zeebe/issues/1914
  private final JobRecordValue jobRecordToRead = new JobRecordValue();
  private final JobRecordValue jobRecordToWrite = new JobRecordValue();

  private final DbLong jobKey;
  private final ColumnFamily<DbLong, JobRecordValue> jobsColumnFamily;

  // key => job state
  private final JobStateValue jobState = new JobStateValue();
  private final ColumnFamily<DbLong, JobStateValue> statesJobColumnFamily;

  // type => [key]
  private final DbString jobTypeKey;
  private final DbCompositeKey<DbString, DbLong> typeJobKey;
  private final ColumnFamily<DbCompositeKey<DbString, DbLong>, DbNil> activatableColumnFamily;

  // timeout => key
  private final DbLong deadlineKey;
  private final DbCompositeKey<DbLong, DbLong> deadlineJobKey;
  private final ColumnFamily<DbCompositeKey<DbLong, DbLong>, DbNil> deadlinesColumnFamily;

  private final JobMetrics metrics;

  private Consumer<String> onJobsAvailableCallback;

  public JobState(
      final ZeebeDb<ZbColumnFamilies> zeebeDb, final DbContext dbContext, final int partitionId) {
    jobKey = new DbLong();
    jobsColumnFamily =
        zeebeDb.createColumnFamily(ZbColumnFamilies.JOBS, dbContext, jobKey, jobRecordToRead);

    statesJobColumnFamily =
        zeebeDb.createColumnFamily(ZbColumnFamilies.JOB_STATES, dbContext, jobKey, jobState);

    jobTypeKey = new DbString();
    typeJobKey = new DbCompositeKey<>(jobTypeKey, jobKey);
    activatableColumnFamily =
        zeebeDb.createColumnFamily(
            ZbColumnFamilies.JOB_ACTIVATABLE, dbContext, typeJobKey, DbNil.INSTANCE);

    deadlineKey = new DbLong();
    deadlineJobKey = new DbCompositeKey<>(deadlineKey, jobKey);
    deadlinesColumnFamily =
        zeebeDb.createColumnFamily(
            ZbColumnFamilies.JOB_DEADLINES, dbContext, deadlineJobKey, DbNil.INSTANCE);

    metrics = new JobMetrics(partitionId);
  }

  public void create(final long key, final JobRecord record) {
    final DirectBuffer type = record.getTypeBuffer();
    createJob(key, record, type);
    metrics.jobCreated(record.getType());
  }

  private void createJob(final long key, final JobRecord record, final DirectBuffer type) {
    resetVariablesAndUpdateJobRecord(key, record);
    updateJobState(State.ACTIVATABLE);
    makeJobActivatable(type, key);
  }

  /**
   * <b>Note:</b> calling this method will reset the variables of the job record. Make sure to write
   * the job record to the log before updating it in the state.
   *
   * <p>related to https://github.com/zeebe-io/zeebe/issues/2182
   */
  public void activate(final long key, final JobRecord record) {
    final DirectBuffer type = record.getTypeBuffer();
    final long deadline = record.getDeadline();

    validateParameters(type);
    EnsureUtil.ensureGreaterThan("deadline", deadline, 0);

    resetVariablesAndUpdateJobRecord(key, record);

    updateJobState(State.ACTIVATED);

    makeJobNotActivatable(type);

    deadlineKey.wrapLong(deadline);
    deadlinesColumnFamily.put(deadlineJobKey, DbNil.INSTANCE);

    metrics.jobActivated(record.getType());
  }

  public void timeout(final long key, final JobRecord record) {
    final DirectBuffer type = record.getTypeBuffer();
    final long deadline = record.getDeadline();

    validateParameters(type);
    EnsureUtil.ensureGreaterThan("deadline", deadline, 0);

    createJob(key, record, type);
    removeJobDeadline(deadline);

    metrics.jobTimedOut(record.getType());
  }

  public void complete(final long key, final JobRecord record) {
    delete(key, record);
    metrics.jobCompleted(record.getType());
  }

  public void cancel(final long key, final JobRecord record) {
    delete(key, record);
    metrics.jobCanceled(record.getType());
  }

  public void disable(final long key, final JobRecord record) {
    updateJob(key, record, State.FAILED);
    makeJobNotActivatable(record.getTypeBuffer());
  }

  public void throwError(final long key, final JobRecord updatedValue) {
    updateJob(key, updatedValue, State.ERROR_THROWN);
    makeJobNotActivatable(updatedValue.getTypeBuffer());

    metrics.jobErrorThrown(updatedValue.getType());
  }

  public void delete(final long key, final JobRecord record) {
    final DirectBuffer type = record.getTypeBuffer();
    final long deadline = record.getDeadline();

    jobKey.wrapLong(key);
    jobsColumnFamily.delete(jobKey);

    statesJobColumnFamily.delete(jobKey);

    makeJobNotActivatable(type);

    removeJobDeadline(deadline);
  }

  public void fail(final long key, final JobRecord updatedValue) {
    final State newState = updatedValue.getRetries() > 0 ? State.ACTIVATABLE : State.FAILED;
    updateJob(key, updatedValue, newState);

    metrics.jobFailed(updatedValue.getType());
  }

  private void updateJob(final long key, final JobRecord updatedValue, final State newState) {
    final DirectBuffer type = updatedValue.getTypeBuffer();
    final long deadline = updatedValue.getDeadline();

    validateParameters(type);

    resetVariablesAndUpdateJobRecord(key, updatedValue);

    updateJobState(newState);

    if (newState == State.ACTIVATABLE) {
      makeJobActivatable(type, key);
    }

    if (deadline > 0) {
      removeJobDeadline(deadline);
    }
  }

  private void validateParameters(final DirectBuffer type) {
    EnsureUtil.ensureNotNullOrEmpty("type", type);
  }

  public void resolve(final long key, final JobRecord updatedValue) {
    updateJob(key, updatedValue, State.ACTIVATABLE);
  }

  public void forEachTimedOutEntry(
      final long upperBound, final BiFunction<Long, JobRecord, Boolean> callback) {

    deadlinesColumnFamily.whileTrue(
        (compositeKey, zbNil) -> {
          final long deadline = compositeKey.getFirst().getValue();
          final boolean isDue = deadline < upperBound;
          if (isDue) {
            final long jobKey = compositeKey.getSecond().getValue();
            return visitJob(jobKey, callback, () -> deadlinesColumnFamily.delete(compositeKey));
          }
          return false;
        });
  }

  public boolean exists(final long jobKey) {
    this.jobKey.wrapLong(jobKey);
    return jobsColumnFamily.exists(this.jobKey);
  }

  public State getState(final long key) {
    jobKey.wrapLong(key);

    final JobStateValue storedState = statesJobColumnFamily.get(jobKey);

    if (storedState == null) {
      return State.NOT_FOUND;
    }

    return storedState.getState();
  }

  public boolean isInState(final long key, final State state) {
    return getState(key) == state;
  }

  public void forEachActivatableJobs(
      final DirectBuffer type, final BiFunction<Long, JobRecord, Boolean> callback) {
    jobTypeKey.wrapBuffer(type);

    activatableColumnFamily.whileEqualPrefix(
        jobTypeKey,
        ((compositeKey, zbNil) -> {
          final long jobKey = compositeKey.getSecond().getValue();
          return visitJob(jobKey, callback, () -> activatableColumnFamily.delete(compositeKey));
        }));
  }

  boolean visitJob(
      final long jobKey,
      final BiFunction<Long, JobRecord, Boolean> callback,
      final Runnable cleanupRunnable) {
    final JobRecord job = getJob(jobKey);
    if (job == null) {
      LOG.error("Expected to find job with key {}, but no job found", jobKey);
      cleanupRunnable.run();
      return true; // we want to continue with the iteration
    }
    return callback.apply(jobKey, job);
  }

  public JobRecord updateJobRetries(final long jobKey, final int retries) {
    final JobRecord job = getJob(jobKey);
    if (job != null) {
      job.setRetries(retries);
      resetVariablesAndUpdateJobRecord(jobKey, job);
    }
    return job;
  }

  public JobRecord getJob(final long key) {
    jobKey.wrapLong(key);
    final JobRecordValue jobState = jobsColumnFamily.get(jobKey);
    return jobState == null ? null : jobState.getRecord();
  }

  public void setJobsAvailableCallback(final Consumer<String> onJobsAvailableCallback) {
    this.onJobsAvailableCallback = onJobsAvailableCallback;
  }

  private void notifyJobAvailable(final DirectBuffer jobType) {
    if (onJobsAvailableCallback != null) {
      onJobsAvailableCallback.accept(BufferUtil.bufferAsString(jobType));
    }
  }

  private void resetVariablesAndUpdateJobRecord(final long key, final JobRecord updatedValue) {
    jobKey.wrapLong(key);
    // do not persist variables in job state
    updatedValue.resetVariables();
    jobRecordToWrite.setRecord(updatedValue);
    jobsColumnFamily.put(jobKey, jobRecordToWrite);
  }

  private void updateJobState(final State newState) {
    jobState.setState(newState);
    statesJobColumnFamily.put(jobKey, jobState);
  }

  private void makeJobActivatable(final DirectBuffer type, final long key) {
    EnsureUtil.ensureNotNullOrEmpty("type", type);

    jobTypeKey.wrapBuffer(type);

    jobKey.wrapLong(key);
    activatableColumnFamily.put(typeJobKey, DbNil.INSTANCE);

    // always notify
    notifyJobAvailable(type);
  }

  private void makeJobNotActivatable(final DirectBuffer type) {
    EnsureUtil.ensureNotNullOrEmpty("type", type);

    jobTypeKey.wrapBuffer(type);
    activatableColumnFamily.delete(typeJobKey);
  }

  private void removeJobDeadline(final long deadline) {
    deadlineKey.wrapLong(deadline);
    deadlinesColumnFamily.delete(deadlineJobKey);
  }

  public boolean isEmpty() {
    return jobsColumnFamily.isEmpty()
        && statesJobColumnFamily.isEmpty()
        && activatableColumnFamily.isEmpty()
        && deadlinesColumnFamily.isEmpty();
  }

  public enum State {
    ACTIVATABLE((byte) 0),
    ACTIVATED((byte) 1),
    FAILED((byte) 2),
    NOT_FOUND((byte) 3),
    ERROR_THROWN((byte) 4);

    byte value;

    State(final byte value) {
      this.value = value;
    }
  }
}
