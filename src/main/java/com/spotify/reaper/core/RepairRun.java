package com.spotify.reaper.core;

import com.fasterxml.jackson.annotation.JsonIgnore;

import org.joda.time.DateTime;

public class RepairRun {

  private final long id;

  // IDEA: maybe we want to have start and stop token for parallel runners on same repair run?
  //private final long startToken;
  //private final long endToken;

  private final String cause;
  private final String owner;
  private final RunState runState;
  private final DateTime creationTime;
  private final DateTime startTime;
  private final DateTime endTime;
  private final double intensity;

  // repair run lock exist per single repair run, and will be passed on if instance updated
  @JsonIgnore
  private final Object repairRunLock;

  public long getId() {
    return id;
  }

  public Object getRepairRunLock() {
    return this.repairRunLock;
  }

  public String getCause() {
    return cause;
  }

  public String getOwner() {
    return owner;
  }

  public RunState getState() {
    return runState;
  }

  public DateTime getCreationTime() {
    return creationTime;
  }

  public DateTime getStartTime() {
    return startTime;
  }

  public DateTime getEndTime() {
    return endTime;
  }

  public double getIntensity() {
    return intensity;
  }

  public static RepairRun getCopy(RepairRun repairRun, RunState newState,
                                  DateTime startTime, DateTime endTime) {
    return new RepairRun.Builder(repairRun.getRepairRunLock(), newState,
                                 repairRun.getCreationTime(), repairRun.getIntensity())
        .cause(repairRun.getCause())
        .owner(repairRun.getOwner())
        .startTime(startTime)
        .endTime(endTime)
        .build(repairRun.getId());
  }

  public enum RunState {
    NOT_STARTED,
    RUNNING,
    ERROR,
    DONE,
    PAUSED
  }

  private RepairRun(Builder builder, long id) {
    this.id = id;
    this.repairRunLock = builder.repairRunLock;
    this.cause = builder.cause;
    this.owner = builder.owner;
    this.runState = builder.runState;
    this.creationTime = builder.creationTime;
    this.startTime = builder.startTime;
    this.endTime = builder.endTime;
    this.intensity = builder.intensity;
  }

  public static class Builder {

    public final RunState runState;
    public final DateTime creationTime;
    public final double intensity;
    private String cause;
    private String owner;
    private DateTime startTime;
    private DateTime endTime;
    private Object repairRunLock;

    public Builder(Object repairRunLock, RunState runState, DateTime creationTime,
                   double intensity) {
      this.repairRunLock = repairRunLock;
      this.runState = runState;
      this.creationTime = creationTime;
      this.intensity = intensity;
    }

    public Builder cause(String cause) {
      this.cause = cause;
      return this;
    }

    public Builder owner(String owner) {
      this.owner = owner;
      return this;
    }

    public Builder startTime(DateTime startTime) {
      this.startTime = startTime;
      return this;
    }

    public Builder endTime(DateTime endTime) {
      this.endTime = endTime;
      return this;
    }

    public Builder repairRunLock(Object lock) {
      this.repairRunLock = lock;
      return this;
    }

    public RepairRun build(long id) {
      return new RepairRun(this, id);
    }
  }
}
