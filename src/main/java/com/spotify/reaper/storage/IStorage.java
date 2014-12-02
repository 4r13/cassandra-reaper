package com.spotify.reaper.storage;

import com.spotify.reaper.core.Cluster;
import com.spotify.reaper.core.RepairRun;

/**
 * API definition for cassandra-reaper.
 */
public interface IStorage {

  public Cluster addCluster(Cluster cluster);

  public Cluster getCluster(String clusterName);

  public RepairRun addRepairRun(RepairRun repairRun);

  public RepairRun getRepairRun(long id);

}
