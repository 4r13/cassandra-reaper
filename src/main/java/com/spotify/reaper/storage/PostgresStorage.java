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
package com.spotify.reaper.storage;

import com.spotify.reaper.ReaperApplicationConfiguration;
import com.spotify.reaper.ReaperException;
import com.spotify.reaper.core.Cluster;
import com.spotify.reaper.core.ColumnFamily;
import com.spotify.reaper.core.RepairRun;
import com.spotify.reaper.core.RepairSegment;
import com.spotify.reaper.storage.postgresql.IStoragePostgreSQL;

import org.skife.jdbi.v2.DBI;
import org.skife.jdbi.v2.Handle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import io.dropwizard.jdbi.DBIFactory;
import io.dropwizard.setup.Environment;

/**
 * Implements the StorageAPI using PostgreSQL database.
 */
public class PostgresStorage implements IStorage {

  private static final Logger LOG = LoggerFactory.getLogger(PostgresStorage.class);

  private final DBI jdbi;

  public PostgresStorage(ReaperApplicationConfiguration config, Environment environment)
      throws ReaperException {
    try {
      final DBIFactory factory = new DBIFactory();
      jdbi = factory.build(environment, config.getDataSourceFactory(), "postgresql");
    } catch (ClassNotFoundException ex) {
      LOG.error("failed creating database connection: {}", ex);
      throw new ReaperException(ex);
    }
  }

  @Override
  public Cluster getCluster(String clusterName) {
    return (Cluster) getGeneric(Cluster.class, clusterName);
  }

  @Override
  public boolean isStorageConnected() {
    if (null == jdbi) {
      return false;
    }
    Handle h = jdbi.open();
    IStoragePostgreSQL postgres = h.attach(IStoragePostgreSQL.class);
    String postgresVersion = postgres.getVersion();
    LOG.debug("connected PostgreSQL version: {}", postgresVersion);
    return null != postgresVersion && postgresVersion.trim().length() > 0;
  }

  @Override
  public Cluster addCluster(Cluster newCluster) {
    Handle h = jdbi.open();
    IStoragePostgreSQL postgres = h.attach(IStoragePostgreSQL.class);
    int rowsAdded = postgres.insertCluster(newCluster);
    h.close();
    if (rowsAdded < 1) {
      LOG.warn("failed inserting cluster with name: {}", newCluster.getName());
      return null;
    }
    return newCluster;
  }

  @Override
  public boolean updateCluster(Cluster cluster) {
    Handle h = jdbi.open();
    IStoragePostgreSQL postgres = h.attach(IStoragePostgreSQL.class);
    int rowsAdded = postgres.updateCluster(cluster);
    h.close();
    if (rowsAdded < 1) {
      LOG.warn("failed updating cluster with name: {}", cluster.getName());
      return false;
    }
    return true;
  }

  @Override
  public RepairRun getRepairRun(long id, Object repairRunLock) {
    assert null != repairRunLock : "Repair run lock must be given";
    RepairRun result = (RepairRun) getGeneric(RepairRun.class, id);
    result.setRepairRunLock(repairRunLock);
    return result;
  }

  @Override
  public RepairRun addRepairRun(RepairRun.Builder newRepairRun) {
    Handle h = jdbi.open();
    IStoragePostgreSQL postgres = h.attach(IStoragePostgreSQL.class);
    long insertedId = postgres.insertRepairRun(newRepairRun.build(-1));
    h.close();
    return newRepairRun.build(insertedId);
  }

  @Override
  public boolean updateRepairRun(RepairRun repairRun) {
    Handle h = jdbi.open();
    IStoragePostgreSQL postgres = h.attach(IStoragePostgreSQL.class);
    int rowsAdded = postgres.updateRepairRun(repairRun);
    h.close();
    if (rowsAdded < 1) {
      LOG.warn("failed updating repair run with id: {}", repairRun.getId());
      return false;
    }
    return true;
  }

  @Override
  public ColumnFamily addColumnFamily(ColumnFamily.Builder newColumnFamily) {
    Handle h = jdbi.open();
    IStoragePostgreSQL postgres = h.attach(IStoragePostgreSQL.class);
    long insertedId = postgres.insertColumnFamily(newColumnFamily.build(-1));
    h.close();
    return newColumnFamily.build(insertedId);
  }

  @Override
  public ColumnFamily getColumnFamily(long id) {
    return (ColumnFamily) getGeneric(ColumnFamily.class, id);
  }

  @Override
  public ColumnFamily getColumnFamily(String cluster, String keyspace, String table) {
    return null;
  }

  @Override
  public int addRepairSegments(Collection<RepairSegment.Builder> newSegments) {
    List<RepairSegment> insertableSegments = new ArrayList<>();
    for (RepairSegment.Builder segment : newSegments) {
      insertableSegments.add(segment.build(-1));
    }
    Handle h = jdbi.open();
    IStoragePostgreSQL postgres = h.attach(IStoragePostgreSQL.class);
    return postgres.insertRepairSegments(insertableSegments.iterator());
  }

  @Override
  public boolean updateRepairSegment(RepairSegment repairSegment) {
    Handle h = jdbi.open();
    IStoragePostgreSQL postgres = h.attach(IStoragePostgreSQL.class);
    int rowsAdded = postgres.updateRepairSegment(repairSegment);
    h.close();
    if (rowsAdded < 1) {
      LOG.warn("failed updating repair segment with id: {}", repairSegment.getId());
      return false;
    }
    return true;
  }

  @Override
  public RepairSegment getRepairSegment(long id) {
    return (RepairSegment) getGeneric(RepairSegment.class, id);
  }

  @Override
  public RepairSegment getNextFreeSegment(long runId) {
    Handle h = jdbi.open();
    IStoragePostgreSQL postgres = h.attach(IStoragePostgreSQL.class);
    RepairSegment result = postgres.getNextFreeRepairSegment(runId);
    h.close();
    return result;
  }

  @Override
  public RepairSegment getNextFreeSegmentInRange(long runId, BigInteger start, BigInteger end) {
    Handle h = jdbi.open();
    IStoragePostgreSQL postgres = h.attach(IStoragePostgreSQL.class);
    RepairSegment result = postgres.getNextFreeRepairSegmentOnRange(runId, start, end);
    h.close();
    return result;
  }

  /**
   * Generic database getter to decrease amount of code duplication.
   */
  private Object getGeneric(Class coreObjectType, Object value) {
    Handle h = jdbi.open();
    IStoragePostgreSQL postgres = h.attach(IStoragePostgreSQL.class);
    Object result = null;
    if (coreObjectType == Cluster.class) {
      result = postgres.getCluster((String) value);
    } else if (coreObjectType == RepairRun.class) {
      result = postgres.getRepairRun((Long) value);
    } else if (coreObjectType == ColumnFamily.class) {
      result = postgres.getColumnFamily((Long) value);
    } else if (coreObjectType == RepairSegment.class) {
      result = postgres.getRepairSegment((Long) value);
    }
    h.close();
    return result;
  }

}
