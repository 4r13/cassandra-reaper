/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.spotify.reaper.service;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;

import com.spotify.reaper.ReaperException;
import com.spotify.reaper.cassandra.JmxProxy;
import com.spotify.reaper.cassandra.RepairStatusHandler;
import com.spotify.reaper.core.ColumnFamily;
import com.spotify.reaper.core.RepairRun;
import com.spotify.reaper.core.RepairSegment;
import com.spotify.reaper.storage.IStorage;

import org.apache.cassandra.service.ActiveRepairService;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * A repair runner that only triggers one segment repair at a time.
 */
public class RepairRunner implements Runnable {
  // TODO: test
  // TODO: handle failed storage updates

  private static final Logger LOG = LoggerFactory.getLogger(RepairRunner.class);

  private static final int JMX_FAILURE_SLEEP_DELAY_SECONDS = 30;

  private static ScheduledExecutorService executor = null;
  private static int repairTimeoutSecs;

  public static void initializeThreadPool(int threadAmount, int repairTimeoutSecs) {
    executor = Executors.newScheduledThreadPool(threadAmount);
    RepairRunner.repairTimeoutSecs = repairTimeoutSecs;
  }

  public static void startNewRepairRun(IStorage storage, long repairRunID,
      JmxConnectionFactory jmxConnectionFactory) {
    // TODO: make sure that no more than one RepairRunner is created per RepairRun
    assert null != executor : "you need to initialize the thread pool first";
    LOG.info("scheduling repair for repair run #{}", repairRunID);
    try {
      executor.submit(new RepairRunner(storage, repairRunID, jmxConnectionFactory));
    } catch (ReaperException e) {
      e.printStackTrace();
      LOG.warn("Failed to schedule repair for repair run #{}", repairRunID);
    }
  }


  private final IStorage storage;
  private final long repairRunId;
  private final JmxConnectionFactory jmxConnectionFactory;
  private JmxProxy jmxConnection;

  // These fields are only set when a segment is being repaired.
  // TODO: bundle them into a class?
  private ScheduledFuture<?> repairTimeout = null;
  private int currentCommandId = -1;
  private long currentSegmentId = -1;

  @VisibleForTesting
  RepairRunner(IStorage storage, long repairRunId, JmxConnectionFactory jmxConnectionFactory)
      throws ReaperException {
    this.storage = storage;
    this.repairRunId = repairRunId;
    this.jmxConnectionFactory = jmxConnectionFactory;
    jmxConnection = this.jmxConnectionFactory.connectAny(Optional.<RepairStatusHandler>absent(),
        storage.getCluster(storage.getRepairRun(repairRunId).getClusterName()).getSeedHosts());
  }

  /**
   * Starts/resumes a repair run that is supposed to run.
   */
  @Override
  public void run() {
    RepairRun.RunState state = storage.getRepairRun(repairRunId).getRunState();
    LOG.debug("run() called for repair run #{} with run state {}", repairRunId, state);
    switch (state) {
      case NOT_STARTED:
        start();
        break;
      case RUNNING:
        startNextSegment();
        break;
      case PAUSED:
        // Do nothing
        break;
      case DONE:
        // Do nothing
        break;
    }
  }

  /**
   * Starts the repair run.
   */
  private void start() {
    LOG.info("Repairs for repair run #{} starting", repairRunId);
    RepairRun repairRun = storage.getRepairRun(repairRunId);
    storage.updateRepairRun(repairRun.with()
        .runState(RepairRun.RunState.RUNNING)
        .startTime(DateTime.now())
        .build(repairRun.getId()));
    startNextSegment();
  }

  /**
   * Concludes the repair run.
   */
  private void end() {
    LOG.info("Repairs for repair run #{} done", repairRunId);
    RepairRun repairRun = storage.getRepairRun(repairRunId);
    storage.updateRepairRun(repairRun.with()
        .runState(RepairRun.RunState.DONE)
        .endTime(DateTime.now())
        .build(repairRun.getId()));
  }

  /**
   * If no segment has the state RUNNING, start the next repair. Otherwise, mark the RUNNING segment
   * as NOT_STARTED to queue it up for a retry.
   */
  private void startNextSegment() {
    RepairSegment running = storage.getTheRunningSegment(repairRunId);
    if (running != null) {
      abortSegment(running);
      startNextSegment();
    } else {
      assert !repairIsTriggered();
      RepairSegment next = storage.getNextFreeSegment(repairRunId);
      if (next != null) {
        doRepairSegment(next);
      } else {
        end();
      }
    }
  }

  /**
   * Set the running repair segment back to NOT_STARTED.
   *
   * @param runningSegment the running repair segment.
   */
  public void abortSegment(RepairSegment runningSegment) {
    // TODO: actually abort the repair. (runningSegment.getRepairCommandId() should be set)
    assert runningSegment.getRepairCommandId() != null;
    LOG.debug("Aborting repair with and segmentId {} in repair run #{}",
        runningSegment.getId(), repairRunId);
    storage.updateRepairSegment(runningSegment.with()
        .startTime(null)
        .repairCommandId(null)
        .state(RepairSegment.State.NOT_STARTED)
        .build(runningSegment.getId()));
  }

  /**
   * Start the repair of a segment.
   *
   * @param next the segment to repair.
   */
  private synchronized void doRepairSegment(RepairSegment next) {
    // TODO: directly store the right host to contact per segment (or per run, if we guarantee that
    // TODO: one host can coordinate all repair segments).

    ColumnFamily columnFamily =
        storage.getColumnFamily(storage.getRepairRun(repairRunId).getColumnFamilyId());
    String keyspace = columnFamily.getKeyspaceName();

    if (!jmxConnection.isConnectionAlive()) {
      try {
        LOG.debug("reestablishing JMX proxy for repair runner on run id: {}", repairRunId);
        jmxConnection = jmxConnectionFactory.connectAny(Optional.<RepairStatusHandler>absent(),
            storage.getCluster(storage.getRepairRun(repairRunId).getClusterName()).getSeedHosts());
      } catch (ReaperException e) {
        e.printStackTrace();
        LOG.warn("Failed to reestablish JMX connection in runner #{}, reattempting in {} seconds",
            repairRunId, JMX_FAILURE_SLEEP_DELAY_SECONDS);
        executor.schedule(this, JMX_FAILURE_SLEEP_DELAY_SECONDS, TimeUnit.SECONDS);
        return;
      }
      LOG.info("successfully reestablished JMX proxy for repair runner on run id: {}", repairRunId);
    }


    List<String> potentialCoordinators = jmxConnection
        .tokenRangeToEndpoint(keyspace, storage.getNextFreeSegment(repairRunId).getTokenRange());
    if (potentialCoordinators == null) {
      // This segment has a faulty token range. Abort the entire repair run.
      RepairRun repairRun = storage.getRepairRun(repairRunId);
      storage.updateRepairRun(repairRun.with()
          .runState(RepairRun.RunState.ERROR)
          .build(repairRun.getId()));
      return;
    }

    // Connect to a node that can act as coordinator for the new repair.
    try {
      jmxConnection.close();
      jmxConnection = jmxConnectionFactory.connectAny(Optional.<RepairStatusHandler>of(
          new RepairStatusHandler() {
            @Override
            public void handle(int repairNumber, ActiveRepairService.Status status,
                String message) {
              RepairOutcome outcome;
              switch (status) {
                case STARTED:
                  outcome = RepairOutcome.STARTED;
                  break;
                case SESSION_FAILED:
                  outcome = RepairOutcome.FAILED;
                  break;
                case FINISHED:
                  outcome = RepairOutcome.FINISHED;
                  break;
                default:
                  // Do nothing, wait for FINISHED.
                  return;
              }
              handleRepairOutcome(repairNumber, outcome, message);
            }
          }), potentialCoordinators);
    } catch (ReaperException e) {
      e.printStackTrace();
      LOG.warn("Failed to connect to a coordinator node for next repair in runner #{}, "
          + "reattempting in {} seconds", repairRunId, JMX_FAILURE_SLEEP_DELAY_SECONDS);
      executor.schedule(this, JMX_FAILURE_SLEEP_DELAY_SECONDS, TimeUnit.SECONDS);
      return;
    }

    currentSegmentId = next.getId();
    // TODO: ensure that no repair is already running (abort all repairs)
    currentCommandId = jmxConnection
        .triggerRepair(next.getStartToken(), next.getEndToken(), keyspace, columnFamily.getName());
    repairTimeout = executor.schedule(new Runnable() {
      @Override
      public void run() {
        handleRepairOutcome(currentCommandId, RepairOutcome.TIMEOUT,
            "[Reaper] Repair command timed out");
      }
    }, repairTimeoutSecs, TimeUnit.SECONDS);
    LOG.debug("Triggered repair with command id {}", currentCommandId);
    LOG.info("Repair for segment {} started in repair run #{}", currentSegmentId, repairRunId);
    storage.updateRepairSegment(next.with()
        .state(RepairSegment.State.RUNNING)
        .repairCommandId(currentCommandId)
        .build(currentSegmentId));
  }


  public static enum RepairOutcome {
    STARTED, FINISHED, FAILED, TIMEOUT
  }

  /**
   * Called when there is an event coming either from JMX or this runner regarding on-going
   * repairs.
   *
   * @param repairCommandId repair sequence number, obtained when triggering a repair
   * @param outcome         new status of the repair (STARTED, FINISHED, FAILED, TIMEOUT)
   * @param message         additional information about the repair
   */
  public synchronized void handleRepairOutcome(int repairCommandId, RepairOutcome outcome,
      String message) {
    LOG.debug(
        "handleRepairOutcome called for repair run #{}, repairCommandId {}, outcome {} and message: {}",
        repairRunId, repairCommandId, outcome, message);
    if (repairCommandId != currentCommandId) {
      LOG.warn("Repair run id != current command id. {} != {}", repairCommandId, currentCommandId);
      // This can be reached if timeout happens while finished repair is being handled, or vice
      // versa. Since this method is synchronized, only one will get through.

      // Another cause for getting here is other repairs running on the node than what this runner
      // has initiated.
      return;
    }

    if (repairIsTriggered()) {
      RepairSegment currentSegment = storage.getRepairSegment(currentSegmentId);
      // See status explanations from: https://wiki.apache.org/cassandra/RepairAsyncAPI
      switch (outcome) {
        case STARTED:
          DateTime now = DateTime.now();
          storage.updateRepairSegment(currentSegment.with()
              .startTime(now)
              .build(currentSegmentId));
          // We already set the state of the segment to RUNNING.
          break;
        case FINISHED: {
          RepairSegment updatedSegment = currentSegment.with()
              .state(RepairSegment.State.DONE)
              .endTime(DateTime.now())
              .build(currentSegmentId);
          storage.updateRepairSegment(updatedSegment);
          closeRepairCommand();
          executor.schedule(this, intensityBasedDelayMillis(updatedSegment), TimeUnit.MILLISECONDS);
        }
        break;
        case FAILED: {
          // TODO: Bj0rn: How should we handle this? Here, it's almost treated like a success.
          RepairSegment updatedSegment = currentSegment.with()
              .state(RepairSegment.State.ERROR)
              .endTime(DateTime.now())
              .build(currentSegmentId);
          storage.updateRepairSegment(updatedSegment);
          closeRepairCommand();
          executor.schedule(this, intensityBasedDelayMillis(updatedSegment), TimeUnit.MILLISECONDS);
        }
        break;
        case TIMEOUT: {
          closeRepairCommand();
          abortSegment(currentSegment);
          executor.submit(this);
        }
        break;
      }
    }
  }

  /**
   * @return <code>true</code> if this RepairRunner has triggered a repair and is currently waiting
   * for a repair status notification from JMX.
   */
  boolean repairIsTriggered() {
    return repairTimeout != null;
  }

  /**
   * Stop countdown for repair, and stop listening for JMX notifications for the current repair.
   */
  void closeRepairCommand() {
    LOG.debug("Closing repair command with commandId {} and segmentId {} in repair run #{}",
        currentCommandId, currentSegmentId, repairRunId);
    assert repairTimeout != null;

    repairTimeout.cancel(false);
    repairTimeout = null;
    currentCommandId = -1;
    currentSegmentId = -1;
  }

  /**
   * Calculate the delay that should be used before starting the next repair segment.
   *
   * @param repairSegment the last finished repair segment.
   * @return the delay in milliseconds.
   */
  long intensityBasedDelayMillis(RepairSegment repairSegment) {
    RepairRun repairRun = storage.getRepairRun(repairRunId);
    assert repairSegment.getEndTime() != null && repairSegment.getStartTime() != null;
    long repairEnd = repairSegment.getEndTime().getMillis();
    long repairStart = repairSegment.getStartTime().getMillis();
    long repairDuration = repairEnd - repairStart;
    long delay = (long) (repairDuration / repairRun.getIntensity() - repairDuration);
    LOG.debug("Scheduling next runner run() with delay {} ms", delay);
    return delay;
  }
}
