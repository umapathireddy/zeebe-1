/*
 * Copyright © 2020  camunda services GmbH (info@camunda.com)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package io.atomix.raft.snapshot.impl;

import io.atomix.raft.snapshot.PersistedSnapshot;
import io.atomix.raft.snapshot.PersistedSnapshotListener;
import io.atomix.raft.snapshot.PersistedSnapshotStore;
import io.atomix.raft.snapshot.ReceivedSnapshot;
import io.atomix.raft.snapshot.TransientSnapshot;
import io.atomix.utils.time.WallClockTimestamp;
import io.zeebe.util.FileUtil;
import io.zeebe.util.ZbLogger;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import org.slf4j.Logger;

public final class FileBasedSnapshotStore implements PersistedSnapshotStore {

  private static final Logger LOGGER = new ZbLogger(FileBasedSnapshotStore.class);

  // the root snapshotsDirectory where all snapshots should be stored
  private final Path snapshotsDirectory;
  // the root snapshotsDirectory when pending snapshots should be stored
  private final Path pendingDirectory;
  // keeps track of all snapshot modification listeners
  private final Set<PersistedSnapshotListener> listeners;

  private final SnapshotMetrics snapshotMetrics;

  private PersistedSnapshot currentPersistedSnapshot;

  public FileBasedSnapshotStore(
      final SnapshotMetrics snapshotMetrics,
      final Path snapshotsDirectory,
      final Path pendingDirectory) {
    this.snapshotsDirectory = snapshotsDirectory;
    this.pendingDirectory = pendingDirectory;
    this.snapshotMetrics = snapshotMetrics;

    this.listeners = new CopyOnWriteArraySet<>();

    // load previous snapshots
    currentPersistedSnapshot = loadLatestSnapshot(snapshotsDirectory);
  }

  private PersistedSnapshot loadLatestSnapshot(final Path snapshotDirectory) {
    PersistedSnapshot latestPersistedSnapshot = null;
    try (final var stream = Files.newDirectoryStream(snapshotDirectory)) {
      for (final var path : stream) {
        latestPersistedSnapshot = collectSnapshot(path);
      }
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
    return latestPersistedSnapshot;
  }

  private PersistedSnapshot collectSnapshot(final Path path) {
    final var optionalMeta = FileBasedSnapshotMetadata.ofPath(path);
    if (optionalMeta.isPresent()) {
      final var metadata = optionalMeta.get();
      return new FileBasedSnapshot(path, metadata);
    } else {
      LOGGER.warn("Expected snapshot file format to be %d-%d-%d-%d, but was {}", path);
    }
    return null;
  }

  @Override
  public boolean exists(final String id) {
    final var optLatestSnapshot = getLatestSnapshot();

    if (optLatestSnapshot.isPresent()) {
      final var snapshot = optLatestSnapshot.get();
      return snapshot.getPath().getFileName().toString().equals(id);
    }
    return false;
  }

  @Override
  public TransientSnapshot newTransientSnapshot(
      final long index, final long term, final WallClockTimestamp timestamp) {
    final var directory = buildPendingSnapshotDirectory(index, term, timestamp);
    final var fileBasedSnapshotMetadata = new FileBasedSnapshotMetadata(index, term, timestamp);
    return new FileBasedTransientSnapshot(fileBasedSnapshotMetadata, directory, this);
  }

  @Override
  public ReceivedSnapshot newReceivedSnapshot(final String snapshotId) {
    final var optMetadata = FileBasedSnapshotMetadata.ofFileName(snapshotId);
    final var metadata = optMetadata.orElseThrow();

    final var pendingSnapshotDir = pendingDirectory.resolve(metadata.getSnapshotIdAsString());
    return new FileBasedReceivedSnapshot(metadata, pendingSnapshotDir, this);
  }

  @Override
  public Optional<PersistedSnapshot> getLatestSnapshot() {
    return Optional.ofNullable(currentPersistedSnapshot);
  }

  @Override
  public void purgePendingSnapshots() throws IOException {
    try (final var files = Files.list(pendingDirectory)) {
      files.filter(Files::isDirectory).forEach(this::purgePendingSnapshot);
    }
  }

  @Override
  public void addSnapshotListener(final PersistedSnapshotListener listener) {
    listeners.add(listener);
  }

  @Override
  public void removeSnapshotListener(final PersistedSnapshotListener listener) {
    listeners.remove(listener);
  }

  @Override
  public long getCurrentSnapshotIndex() {
    return getLatestSnapshot().map(PersistedSnapshot::index).orElse(0L);
  }

  @Override
  public void delete() {
    // currently only called by Atomix when permanently leaving a cluster - it should be safe here
    // to not update the metrics, as they will simply disappear as time moves on. Once we have a
    // single store/replication mechanism, we can consider updating the metrics here
    currentPersistedSnapshot = null;
    //    snapshotMetrics.decrementSnapshotCount();

    try {
      LOGGER.error("DELETE FOLDER {}", snapshotsDirectory);
      FileUtil.deleteFolder(snapshotsDirectory);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }

    try {
      LOGGER.error("DELETE FOLDER {}", snapshotsDirectory);
      FileUtil.deleteFolder(pendingDirectory);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void observeSnapshotSize(final PersistedSnapshot persistedSnapshot) {
    try (final var contents = Files.newDirectoryStream(persistedSnapshot.getPath())) {
      var totalSize = 0L;

      for (final var path : contents) {
        if (Files.isRegularFile(path)) {
          final var size = Files.size(path);
          snapshotMetrics.observeSnapshotFileSize(size);
          totalSize += size;
        }
      }

      snapshotMetrics.observeSnapshotSize(totalSize);
    } catch (final IOException e) {
      LOGGER.warn("Failed to observe size for snapshot {}", persistedSnapshot, e);
    }
  }

  private void purgePendingSnapshots(final long cutoffIndex) {
    LOGGER.debug(
        "Search for orphaned snapshots below oldest valid snapshot with index {} in {}",
        cutoffIndex,
        pendingDirectory);

    try (final var pendingSnapshots = Files.newDirectoryStream(pendingDirectory)) {
      for (final var pendingSnapshot : pendingSnapshots) {
        purgePendingSnapshot(cutoffIndex, pendingSnapshot);
      }
    } catch (final IOException e) {
      LOGGER.warn(
          "Failed to delete orphaned snapshots, could not list pending directory {}",
          pendingDirectory);
    }
  }

  private void purgePendingSnapshot(final long cutoffIndex, final Path pendingSnapshot) {
    final var optionalMetadata = FileBasedSnapshotMetadata.ofPath(pendingSnapshot);
    if (optionalMetadata.isPresent() && optionalMetadata.get().getIndex() < cutoffIndex) {
      try {
        LOGGER.error("Deleted orphaned snapshot {}", pendingSnapshot);
        FileUtil.deleteFolder(pendingSnapshot);
        LOGGER.debug("Deleted orphaned snapshot {}", pendingSnapshot);
      } catch (final IOException e) {
        LOGGER.warn(
            "Failed to delete orphaned snapshot {}, risk using unnecessary disk space",
            pendingSnapshot);
      }
    }
  }

  public Path getPath() {
    return snapshotsDirectory;
  }

  @Override
  public void close() {
    // nothing to be done
  }

  private boolean isCurrentSnapshotNewer(final FileBasedSnapshotMetadata metadata) {
    return (currentPersistedSnapshot != null
        && currentPersistedSnapshot.id().compareTo(metadata) >= 0);
  }

  PersistedSnapshot newSnapshot(final FileBasedSnapshotMetadata metadata, final Path directory) {

    if (isCurrentSnapshotNewer(metadata)) {
      LOGGER.debug("Snapshot is older then {} already exists", currentPersistedSnapshot);
      purgePendingSnapshots(metadata.getIndex() + 1);
      return currentPersistedSnapshot;
    }

    final var destination = buildSnapshotDirectory(metadata);
    try {
      tryAtomicDirectoryMove(directory, destination);
    } catch (final FileAlreadyExistsException e) {
      LOGGER.debug(
          "Expected to move snapshot from {} to {}, but it already exists",
          directory,
          destination,
          e);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }

    final var previousSnapshot = currentPersistedSnapshot;

    currentPersistedSnapshot = new FileBasedSnapshot(destination, metadata);
    snapshotMetrics.incrementSnapshotCount();
    observeSnapshotSize(currentPersistedSnapshot);

    LOGGER.debug("Purging snapshots older than {}", currentPersistedSnapshot);
    if (previousSnapshot != null) {
      LOGGER.error("Deleting snapshot {}", previousSnapshot);
      previousSnapshot.delete();
    }
    purgePendingSnapshots(currentPersistedSnapshot.index());

    listeners.forEach(listener -> listener.onNewSnapshot(currentPersistedSnapshot));

    LOGGER.debug("Created new snapshot {}", currentPersistedSnapshot);
    return currentPersistedSnapshot;
  }
  //
  //  private DirBasedSnapshot put(final DirBasedSnapshot snapshot) {
  //    // caveat: if the metadata is the same but the location is different, this will do nothing
  //    final var previous = snapshots.put(snapshot.getMetadata(), snapshot);
  //    if (previous == null) {
  //      listeners.forEach(listener -> listener.onNewSnapshot(snapshot));
  //    }
  //
  //    LOGGER.debug("Committed new snapshot {}", snapshot);
  //    return snapshot;
  //  }
  //
  //  private DirBasedSnapshot put(final Path directory, final DirBasedSnapshotMetadata metadata) {
  //    if (snapshots.containsKey(metadata)) {
  //      LOGGER.debug("Snapshot {} already exists", metadata);
  //      return snapshots.get(metadata);
  //    }
  //
  //    final var destination = buildSnapshotDirectory(metadata);
  //    try {
  //      tryAtomicDirectoryMove(directory, destination);
  //    } catch (final FileAlreadyExistsException e) {
  //      LOGGER.debug(
  //          "Expected to move snapshot from {} to {}, but it already exists",
  //          directory,
  //          destination,
  //          e);
  //    } catch (final IOException e) {
  //      throw new UncheckedIOException(e);
  //    }
  //
  //    return put(new DirBasedSnapshot(destination, metadata));
  //  }

  private void purgePendingSnapshot(final Path pendingSnapshot) {
    try {
      FileUtil.deleteFolder(pendingSnapshot);
      LOGGER.error("Delete not completed (orphaned) snapshot {}", pendingSnapshot);
    } catch (final IOException e) {
      LOGGER.error("Failed to delete not completed (orphaned) snapshot {}", pendingSnapshot);
    }
  }

  private void tryAtomicDirectoryMove(final Path directory, final Path destination)
      throws IOException {
    try {
      Files.move(directory, destination, StandardCopyOption.ATOMIC_MOVE);
    } catch (final AtomicMoveNotSupportedException e) {
      Files.move(directory, destination);
    }
  }

  private Path buildPendingSnapshotDirectory(
      final long index, final long term, final WallClockTimestamp timestamp) {
    final var metadata = new FileBasedSnapshotMetadata(index, term, timestamp);
    return pendingDirectory.resolve(metadata.getSnapshotIdAsString());
  }

  private Path buildSnapshotDirectory(final FileBasedSnapshotMetadata metadata) {
    return snapshotsDirectory.resolve(metadata.getSnapshotIdAsString());
  }
}
