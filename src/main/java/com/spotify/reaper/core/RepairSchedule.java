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
package com.spotify.reaper.core;

import com.google.common.collect.Lists;

import org.apache.cassandra.repair.RepairParallelism;
import org.joda.time.DateTime;

import java.util.List;

public class RepairSchedule {

  private final long id;

  private final long repairUnitId;
  private final State state;
  private final int daysBetween;
  private final DateTime nextActivation;
  private final List<Long> runHistory;
  private final int segmentCount;
  private final RepairParallelism repairParallelism;
  private final String owner;
  private final DateTime creationTime;
  private final DateTime pauseTime;
  private final String lastEvent;

  private RepairSchedule(Builder builder, long id) {
    this.id = id;
    this.repairUnitId = builder.repairUnitId;
    this.state = builder.state;
    this.daysBetween = builder.daysBetween;
    this.nextActivation = builder.nextActivation;
    this.runHistory = builder.runHistory;
    this.segmentCount = builder.segmentCount;
    this.repairParallelism = builder.repairParallelism;
    this.owner = builder.owner;
    this.creationTime = builder.creationTime;
    this.pauseTime = builder.pauseTime;
    this.lastEvent = builder.lastEvent;
  }

  public long getId() {
    return id;
  }

  public long getRepairUnitId() {
    return repairUnitId;
  }

  public State getState() {
    return state;
  }

  public int getDaysBetween() {
    return daysBetween;
  }

  public DateTime getFollowingActivation() {
    return getNextActivation().plusDays(getDaysBetween());
  }

  public DateTime getNextActivation() {
    return nextActivation;
  }

  public List<Long> getRunHistory() {
    return runHistory;
  }

  public int getSegmentCount() {
    return segmentCount;
  }

  public RepairParallelism getRepairParallelism() {
    return repairParallelism;
  }

  public String getOwner() {
    return owner;
  }

  public DateTime getCreationTime() {
    return creationTime;
  }

  public DateTime getPauseTime() {
    return pauseTime;
  }

  public String getLastEvent() {
    return lastEvent;
  }

  public Builder with() {
    return new Builder(this);
  }

  public enum State {
    RUNNING,
    PAUSED
  }

  public static class Builder {

    public final long repairUnitId;
    private State state;
    private int daysBetween;
    private DateTime nextActivation;
    private List<Long> runHistory;
    private int segmentCount;
    private RepairParallelism repairParallelism;
    private String owner;
    private DateTime creationTime;
    private DateTime pauseTime;
    private String lastEvent = "no events";

    public Builder(long repairUnitId, State initialState, DateTime creationTime,
                   int segmentCount, RepairParallelism repairParallelism) {
      this.repairUnitId = repairUnitId;
      this.state = initialState;
      this.runHistory = Lists.newArrayList();
      this.segmentCount = segmentCount;
      this.repairParallelism = repairParallelism;
      this.creationTime = creationTime;
    }

    private Builder(RepairSchedule original) {
      repairUnitId = original.repairUnitId;
      state = original.state;
      daysBetween = original.daysBetween;
      nextActivation = original.nextActivation;
      runHistory = original.runHistory;
      segmentCount = original.segmentCount;
      repairParallelism = original.repairParallelism;
      owner = original.owner;
      creationTime = original.creationTime;
      pauseTime = original.pauseTime;
      lastEvent = original.lastEvent;
    }

    public Builder runState(State state) {
      this.state = state;
      return this;
    }

    public Builder daysBetween(int daysBetween) {
      this.daysBetween = daysBetween;
      return this;
    }

    public Builder nextActivation(DateTime nextActivation) {
      this.nextActivation = nextActivation;
      return this;
    }

    public Builder addRun(Long newRunId) {
      runHistory.add(newRunId);
      return this;
    }

    public Builder segmentCount(int segmentCount) {
      this.segmentCount = segmentCount;
      return this;
    }

    public Builder repairParallelism(RepairParallelism repairParallelism) {
      this.repairParallelism = repairParallelism;
      return this;
    }

    public Builder owner(String owner) {
      this.owner = owner;
      return this;
    }

    public Builder creationTime(DateTime creationTime) {
      this.creationTime = creationTime;
      return this;
    }

    public Builder pauseTime(DateTime pauseTime) {
      this.pauseTime = pauseTime;
      return this;
    }

    public Builder lastEvent(String lastEvent) {
      this.lastEvent = lastEvent;
      return this;
    }

    public RepairSchedule build(long id) {
      return new RepairSchedule(this, id);
    }
  }
}
