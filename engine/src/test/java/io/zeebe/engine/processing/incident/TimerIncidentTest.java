/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.engine.processing.incident;

import static org.assertj.core.api.Assertions.assertThat;

import io.zeebe.engine.util.EngineRule;
import io.zeebe.model.bpmn.Bpmn;
import io.zeebe.model.bpmn.BpmnModelInstance;
import io.zeebe.protocol.record.Assertions;
import io.zeebe.protocol.record.Record;
import io.zeebe.protocol.record.intent.IncidentIntent;
import io.zeebe.protocol.record.intent.WorkflowInstanceIntent;
import io.zeebe.protocol.record.value.ErrorType;
import io.zeebe.protocol.record.value.IncidentRecordValue;
import io.zeebe.protocol.record.value.WorkflowInstanceRecordValue;
import io.zeebe.test.util.BrokerClassRuleHelper;
import io.zeebe.test.util.record.RecordingExporter;
import io.zeebe.test.util.record.RecordingExporterTestWatcher;
import java.time.Duration;
import java.util.Collections;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

public final class TimerIncidentTest {

  @ClassRule public static final EngineRule ENGINE = EngineRule.singlePartition();

  private static final String PROCESS_ID = "process";
  private static final String ELEMENT_ID = "timer-1";
  private static final String DURATION_VARIABLE = "timer_duration";
  private static final String DURATION_EXPRESSION = "duration(" + DURATION_VARIABLE + ")";
  private static final String CYCLE_EXPRESSION = "cycle(" + DURATION_EXPRESSION + ")";

  @Rule
  public final RecordingExporterTestWatcher recordingExporterTestWatcher =
      new RecordingExporterTestWatcher();

  @Rule public final BrokerClassRuleHelper helper = new BrokerClassRuleHelper();

  private static BpmnModelInstance createWorkflow(final String expression) {
    return Bpmn.createExecutableProcess(PROCESS_ID)
        .startEvent()
        .intermediateCatchEvent(ELEMENT_ID, b -> b.timerWithDurationExpression(expression))
        .endEvent()
        .done();
  }

  private static BpmnModelInstance createWorkflowWithCycle(final String expression) {
    return Bpmn.createExecutableProcess(PROCESS_ID)
        .startEvent()
        .serviceTask(
            ELEMENT_ID,
            serviceTaskBuilder ->
                serviceTaskBuilder
                    .zeebeJobTypeExpression("boundary_timer_test")
                    .boundaryEvent(
                        "boundary-event-1",
                        timerBoundaryEventBuilder ->
                            timerBoundaryEventBuilder
                                .cancelActivity(false)
                                .timerWithCycleExpression(expression)
                                .endEvent("boundary-timer-end-event")))
        .endEvent()
        .done();
  }

  @Test
  public void shouldCreateIncidentIfDurationVariableNotFound() {
    // when
    ENGINE.deployment().withXmlResource(createWorkflow(DURATION_EXPRESSION)).deploy();
    final long workflowInstanceKey = ENGINE.workflowInstance().ofBpmnProcessId(PROCESS_ID).create();

    // then
    final Record<IncidentRecordValue> incident =
        RecordingExporter.incidentRecords(IncidentIntent.CREATED)
            .withWorkflowInstanceKey(workflowInstanceKey)
            .getFirst();

    final Record<WorkflowInstanceRecordValue> elementInstance =
        RecordingExporter.workflowInstanceRecords(WorkflowInstanceIntent.ELEMENT_ACTIVATING)
            .withElementId(ELEMENT_ID)
            .getFirst();

    Assertions.assertThat(incident.getValue())
        .hasElementInstanceKey(elementInstance.getKey())
        .hasElementId(elementInstance.getValue().getElementId())
        .hasErrorType(ErrorType.EXTRACT_VALUE_ERROR)
        .hasErrorMessage(
            "failed to evaluate expression '"
                + DURATION_EXPRESSION
                + "': no variable found for name '"
                + DURATION_VARIABLE
                + "'");
  }

  @Test
  public void shouldCreateIncidentIfDurationVariableNotADuration() {
    // when
    ENGINE.deployment().withXmlResource(createWorkflow(DURATION_VARIABLE)).deploy();
    final long workflowInstanceKey =
        ENGINE
            .workflowInstance()
            .ofBpmnProcessId(PROCESS_ID)
            .withVariable(DURATION_VARIABLE, "not_a_duration_expression")
            .create();

    // then
    final Record<IncidentRecordValue> incident =
        RecordingExporter.incidentRecords(IncidentIntent.CREATED)
            .withWorkflowInstanceKey(workflowInstanceKey)
            .getFirst();

    final Record<WorkflowInstanceRecordValue> elementInstance =
        RecordingExporter.workflowInstanceRecords(WorkflowInstanceIntent.ELEMENT_ACTIVATING)
            .withElementId(ELEMENT_ID)
            .getFirst();

    Assertions.assertThat(incident.getValue())
        .hasElementInstanceKey(elementInstance.getKey())
        .hasElementId(elementInstance.getValue().getElementId())
        .hasErrorType(ErrorType.EXTRACT_VALUE_ERROR)
        .hasErrorMessage(
            "Expected result of the expression '"
                + DURATION_VARIABLE
                + "' to be parsed to a duration, but was 'not_a_duration_expression'"
                + " and encountered error: Text cannot be parsed to a Period");
  }

  @Test
  public void shouldCreateIncidentIfCycleExpressionCannotBeEvaluated() {
    // when
    ENGINE.deployment().withXmlResource(createWorkflowWithCycle(CYCLE_EXPRESSION)).deploy();
    final long workflowInstanceKey =
        ENGINE
            .workflowInstance()
            .ofBpmnProcessId(PROCESS_ID)
            .withVariable(DURATION_VARIABLE, "not_a_duration_expression")
            .create();

    // then
    final Record<IncidentRecordValue> incident =
        RecordingExporter.incidentRecords(IncidentIntent.CREATED)
            .withWorkflowInstanceKey(workflowInstanceKey)
            .getFirst();

    final Record<WorkflowInstanceRecordValue> elementInstance =
        RecordingExporter.workflowInstanceRecords(WorkflowInstanceIntent.ELEMENT_ACTIVATING)
            .withElementId(ELEMENT_ID)
            .getFirst();

    Assertions.assertThat(incident.getValue())
        .hasElementInstanceKey(elementInstance.getKey())
        .hasElementId(elementInstance.getValue().getElementId())
        .hasErrorType(ErrorType.EXTRACT_VALUE_ERROR)
        .hasErrorMessage(
            "failed to evaluate expression '"
                + CYCLE_EXPRESSION
                + "': cycle function expected an interval (duration) parameter, but found 'ValNull'");
  }

  @Test
  public void shouldResolveIncident() {
    // given
    ENGINE.deployment().withXmlResource(createWorkflow(DURATION_EXPRESSION)).deploy();
    final long workflowInstanceKey = ENGINE.workflowInstance().ofBpmnProcessId(PROCESS_ID).create();

    final Record<IncidentRecordValue> incident =
        RecordingExporter.incidentRecords(IncidentIntent.CREATED)
            .withWorkflowInstanceKey(workflowInstanceKey)
            .getFirst();

    // when
    ENGINE
        .variables()
        .ofScope(incident.getValue().getVariableScopeKey())
        .withDocument(Collections.singletonMap(DURATION_VARIABLE, Duration.ofSeconds(1).toString()))
        .update();

    ENGINE.incident().ofInstance(workflowInstanceKey).withKey(incident.getKey()).resolve();

    // then
    assertThat(
            RecordingExporter.workflowInstanceRecords()
                .withRecordKey(incident.getValue().getElementInstanceKey())
                .limit(2))
        .extracting(Record::getIntent)
        .contains(WorkflowInstanceIntent.ELEMENT_ACTIVATED);
  }
}
