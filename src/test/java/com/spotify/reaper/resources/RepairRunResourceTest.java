package com.spotify.reaper.resources;

import com.google.common.base.Optional;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.spotify.reaper.ReaperApplicationConfiguration;
import com.spotify.reaper.ReaperException;
import com.spotify.reaper.cassandra.JmxConnectionFactory;
import com.spotify.reaper.cassandra.JmxProxy;
import com.spotify.reaper.cassandra.RepairStatusHandler;
import com.spotify.reaper.core.Cluster;
import com.spotify.reaper.core.RepairRun;
import com.spotify.reaper.core.RepairSegment;
import com.spotify.reaper.core.RepairUnit;
import com.spotify.reaper.resources.view.RepairRunStatus;
import com.spotify.reaper.service.RepairRunner;
import com.spotify.reaper.service.RingRange;
import com.spotify.reaper.storage.IStorage;
import com.spotify.reaper.storage.MemoryStorage;
import org.joda.time.DateTimeUtils;
import org.junit.Before;
import org.junit.Test;

import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import java.math.BigInteger;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class RepairRunResourceTest {

  int THREAD_CNT = 1;
  int REPAIR_TIMEOUT_S = 60;
  int RETRY_DELAY_S = 10;

  long TIME_CREATE = 42l;
  long TIME_START = 43l;

  URI SAMPLE_URI = URI.create("http://test");

  static final String CLUSTER_NAME = "TestCluster";
  static final String PARTITIONER = "org.apache.cassandra.dht.RandomPartitioner";
  static final String SEED_HOST = "TestHost";
  static final String KEYSPACE = "testKeyspace";
  static final Set<String> TABLES = Sets.newHashSet("testTable");
  static final String OWNER = "test";
  static final Integer SEGMENTS = 100;

  int SEGMENT_CNT = 6;
  double REPAIR_INTENSITY = 0.5f;
  boolean IS_SNAPSHOT_REPAIR = false;
  List<BigInteger> TOKENS = Lists.newArrayList(BigInteger.valueOf(0l), BigInteger.valueOf(100l),
      BigInteger.valueOf(200l));

  IStorage storage;
  ReaperApplicationConfiguration config;
  UriInfo uriInfo;
  JmxConnectionFactory factory;

  @Before
  public void setUp() throws Exception {
    storage = new MemoryStorage();
    Cluster cluster = new Cluster(CLUSTER_NAME, PARTITIONER, Sets.newHashSet(SEED_HOST));
    storage.addCluster(cluster);

    config = mock(ReaperApplicationConfiguration.class);
    when(config.getSegmentCount()).thenReturn(SEGMENT_CNT);
    when(config.getRepairIntensity()).thenReturn(REPAIR_INTENSITY);

    uriInfo = mock(UriInfo.class);
    when(uriInfo.getAbsolutePath()).thenReturn(SAMPLE_URI);
    when(uriInfo.getBaseUri()).thenReturn(SAMPLE_URI);

    final JmxProxy proxy = mock(JmxProxy.class);
    when(proxy.getClusterName()).thenReturn(CLUSTER_NAME);
    when(proxy.getPartitioner()).thenReturn(PARTITIONER);
    when(proxy.getTableNamesForKeyspace(KEYSPACE)).thenReturn(TABLES);
    when(proxy.getTokens()).thenReturn(TOKENS);
    when(proxy.tableExists(anyString(), anyString())).thenReturn(Boolean.TRUE);
    when(proxy.isConnectionAlive()).thenReturn(Boolean.TRUE);
    when(proxy.tokenRangeToEndpoint(anyString(), any(RingRange.class))).thenReturn(
        Collections.singletonList(""));
    factory = new JmxConnectionFactory() {
      @Override
      public JmxProxy connect(Optional<RepairStatusHandler> handler, String host)
          throws ReaperException {
        return proxy;
      }
    };

    RepairUnit.Builder repairUnitBuilder = new RepairUnit.Builder(CLUSTER_NAME,
        KEYSPACE, TABLES, SEGMENT_CNT, IS_SNAPSHOT_REPAIR);
    storage.addRepairUnit(repairUnitBuilder);
  }

  private Response addDefaultRepairRun(RepairRunResource resource) {
    return addRepairRun(resource, uriInfo, CLUSTER_NAME, KEYSPACE, TABLES,
        OWNER, null, SEGMENTS);
  }

  private Response addRepairRun(RepairRunResource resource, UriInfo uriInfo,
      String clusterName, String keyspace, Set<String> columnFamilies, String owner,
      String cause, Integer segments) {
    return resource.addRepairRun(uriInfo,
        clusterName == null ? Optional.<String>absent() : Optional.of(clusterName),
        keyspace == null ? Optional.<String>absent() : Optional.of(keyspace),
        columnFamilies == null ?
            Optional.<String>absent() :
            Optional.of(columnFamilies.iterator().next()),
        owner == null ? Optional.<String>absent() : Optional.of(owner),
        cause == null ? Optional.<String>absent() : Optional.of(cause),
        segments == null ? Optional.<Integer>absent() : Optional.of(segments));
  }

  @Test
  public void testAddRepairRun() throws Exception {

    DateTimeUtils.setCurrentMillisFixed(TIME_CREATE);
    RepairRunResource resource = new RepairRunResource(config, storage, factory);
    Response response = addDefaultRepairRun(resource);

    assertEquals(201, response.getStatus());
    assertTrue(response.getEntity() instanceof RepairRunStatus);

    assertEquals(1, storage.getClusters().size());
    assertEquals(1, storage.getRepairRunsForCluster(CLUSTER_NAME).size());
    assertEquals(1, storage.getRepairRunIdsForCluster(CLUSTER_NAME).size());
    Long runId = storage.getRepairRunIdsForCluster(CLUSTER_NAME).iterator().next();
    RepairRun run = storage.getRepairRun(runId).get();
    assertEquals(RepairRun.RunState.NOT_STARTED, run.getRunState());
    assertEquals(TIME_CREATE, run.getCreationTime().getMillis());
    assertEquals(REPAIR_INTENSITY, run.getIntensity(), 0.0f);
    assertNull(run.getStartTime());
    assertNull(run.getEndTime());

    // apparently, tokens [0, 100, 200] and 6 requested segments causes generating 8 RepairSegments
    assertEquals(8, storage.getSegmentAmountForRepairRun(run.getId(),
        RepairSegment.State.NOT_STARTED));

    // adding another repair run should work as well
    response = addDefaultRepairRun(resource);

    assertEquals(201, response.getStatus());
    assertTrue(response.getEntity() instanceof RepairRunStatus);

    assertEquals(1, storage.getClusters().size());
    assertEquals(2, storage.getRepairRunsForCluster(CLUSTER_NAME).size());
  }

  @Test
  public void testTriggerRepairRun() throws Exception {
    DateTimeUtils.setCurrentMillisFixed(TIME_CREATE);
    RepairRunner.initializeThreadPool(THREAD_CNT, REPAIR_TIMEOUT_S, TimeUnit.SECONDS, RETRY_DELAY_S,
        TimeUnit.SECONDS);
    RepairRunResource resource = new RepairRunResource(config, storage, factory);
    Response response = addDefaultRepairRun(resource);
    RepairRunStatus repairRunStatus = (RepairRunStatus) response.getEntity();
    long runId = repairRunStatus.getId();

    DateTimeUtils.setCurrentMillisFixed(TIME_START);
    Optional<String> newState = Optional.of(RepairRun.RunState.RUNNING.toString());
    response = resource.modifyRunState(uriInfo, runId, newState);

    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    assertTrue(response.getEntity() instanceof RepairRunStatus);
    // the thing we get as a reply from the endpoint is a not started run. This is because the
    // executor didn't have time to start the run
    assertEquals(RepairRun.RunState.NOT_STARTED.name(), repairRunStatus.getRunState());

    // give the executor some time to actually start the run
    Thread.sleep(200);

    RepairRun repairRun = storage.getRepairRun(runId).get();
    assertEquals(RepairRun.RunState.RUNNING, repairRun.getRunState());
    assertEquals(TIME_CREATE, repairRun.getCreationTime().getMillis());
    assertEquals(TIME_START, repairRun.getStartTime().getMillis());
    assertNull(repairRun.getEndTime());
    assertEquals(REPAIR_INTENSITY, repairRun.getIntensity(), 0.0f);
    assertEquals(1, storage.getSegmentAmountForRepairRun(runId, RepairSegment.State.RUNNING));
    assertEquals(7, storage.getSegmentAmountForRepairRun(runId, RepairSegment.State.NOT_STARTED));
  }

  @Test
  public void testTriggerNotExistingRun() {
    RepairRunResource resource = new RepairRunResource(config, storage, factory);
    Optional<String> newState = Optional.of(RepairRun.RunState.RUNNING.toString());
    Response response = resource.modifyRunState(uriInfo, 42l, newState);
    assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
    assertTrue(response.getEntity() instanceof String);
    assertTrue(response.getEntity().toString().contains("not found"));
  }

  @Test
  public void testTriggerAlreadyRunningRun() throws InterruptedException {
    DateTimeUtils.setCurrentMillisFixed(TIME_CREATE);
    RepairRunner.initializeThreadPool(THREAD_CNT, REPAIR_TIMEOUT_S, TimeUnit.SECONDS, RETRY_DELAY_S,
        TimeUnit.SECONDS);
    RepairRunResource resource = new RepairRunResource(config, storage, factory);
    Response response = addDefaultRepairRun(resource);
    RepairRunStatus repairRunStatus = (RepairRunStatus) response.getEntity();
    long runId = repairRunStatus.getId();

    DateTimeUtils.setCurrentMillisFixed(TIME_START);
    Optional<String> newState = Optional.of(RepairRun.RunState.RUNNING.toString());
    resource.modifyRunState(uriInfo, runId, newState);
    Thread.sleep(1000);
    response = resource.modifyRunState(uriInfo, runId, newState);
    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
  }

  @Test
  public void testAddRunClusterNotInStorage() {
    storage = new MemoryStorage();
    RepairRunResource resource = new RepairRunResource(config, storage, factory);
    Response response = addDefaultRepairRun(resource);
    assertEquals(404, response.getStatus());
    assertTrue(response.getEntity() instanceof String);
    assertTrue(response.getEntity().toString().contains("no cluster found"));
  }

  @Test
  public void testAddRunMissingArgument() {
    RepairRunResource resource = new RepairRunResource(config, storage, factory);
    Response response = addRepairRun(resource, uriInfo, CLUSTER_NAME, null,
        TABLES, OWNER, null, SEGMENTS);
    assertEquals(500, response.getStatus());
    assertTrue(response.getEntity() instanceof String);
    assertTrue(response.getEntity().toString().contains("argument missing"));
  }

  @Test
  public void testTriggerRunMissingArgument() {
    RepairRunner.initializeThreadPool(THREAD_CNT, REPAIR_TIMEOUT_S, TimeUnit.SECONDS, RETRY_DELAY_S,
        TimeUnit.SECONDS);
    RepairRunResource resource = new RepairRunResource(config, storage, factory);
    Response response = addRepairRun(resource, uriInfo, CLUSTER_NAME, null, TABLES, OWNER,
        null, SEGMENTS);
    assertEquals(500, response.getStatus());
    assertTrue(response.getEntity() instanceof String);
    assertTrue(response.getEntity().toString().contains("argument missing"));
  }

  @Test
  public void testPauseRunningRun() throws InterruptedException {
    // first trigger a run
    DateTimeUtils.setCurrentMillisFixed(TIME_CREATE);
    RepairRunner.initializeThreadPool(THREAD_CNT, REPAIR_TIMEOUT_S, TimeUnit.SECONDS, RETRY_DELAY_S,
        TimeUnit.SECONDS);
    RepairRunResource resource = new RepairRunResource(config, storage, factory);
    Response response = addDefaultRepairRun(resource);
    RepairRunStatus repairRunStatus = (RepairRunStatus) response.getEntity();
    long runId = repairRunStatus.getId();
    DateTimeUtils.setCurrentMillisFixed(TIME_START);
    Optional<String> newState = Optional.of(RepairRun.RunState.RUNNING.toString());
    resource.modifyRunState(uriInfo, runId, newState);

    Thread.sleep(200);

    // now pause it
    response = resource.modifyRunState(uriInfo, runId,
        Optional.of(RepairRun.RunState.PAUSED.toString()));
    Thread.sleep(200);

    assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    RepairRun repairRun = storage.getRepairRun(runId).get();
    // the run should be paused
    assertEquals(RepairRun.RunState.PAUSED, repairRun.getRunState());
    // but the running segment should be untouched
    assertEquals(1, storage.getSegmentAmountForRepairRun(runId, RepairSegment.State.RUNNING));
  }

  @Test
  public void testPauseNotRunningRun() throws InterruptedException {
    DateTimeUtils.setCurrentMillisFixed(TIME_CREATE);
    RepairRunner.initializeThreadPool(THREAD_CNT, REPAIR_TIMEOUT_S, TimeUnit.SECONDS, RETRY_DELAY_S,
        TimeUnit.SECONDS);
    RepairRunResource resource = new RepairRunResource(config, storage, factory);
    Response response = addDefaultRepairRun(resource);
    RepairRunStatus repairRunStatus = (RepairRunStatus) response.getEntity();
    long runId = repairRunStatus.getId();

    response = resource.modifyRunState(uriInfo, runId,
        Optional.of(RepairRun.RunState.PAUSED.toString()));
    Thread.sleep(200);

    assertEquals(400, response.getStatus());
    RepairRun repairRun = storage.getRepairRun(runId).get();
    // the run should be paused
    assertEquals(RepairRun.RunState.NOT_STARTED, repairRun.getRunState());
    // but the running segment should be untouched
    assertEquals(0, storage.getSegmentAmountForRepairRun(runId, RepairSegment.State.RUNNING));
  }

  @Test
  public void testPauseNotExistingRun() throws InterruptedException {
    RepairRunResource resource = new RepairRunResource(config, storage, factory);
    Response response = resource.modifyRunState(uriInfo, 42l,
        Optional.of(RepairRun.RunState.PAUSED.toString()));
    assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
    assertEquals(0, storage.getRepairRunsWithState(RepairRun.RunState.RUNNING).size());
  }

}
