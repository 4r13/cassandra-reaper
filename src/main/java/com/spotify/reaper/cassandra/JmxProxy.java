package com.spotify.reaper.cassandra;

import com.google.common.base.Optional;
import com.google.common.collect.Lists;

import com.spotify.reaper.ReaperException;

import org.apache.cassandra.service.ActiveRepairService;
import org.apache.cassandra.service.StorageServiceMBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Serializable;
import java.math.BigInteger;
import java.net.MalformedURLException;
import java.util.List;

import javax.management.InstanceNotFoundException;
import javax.management.JMX;
import javax.management.ListenerNotFoundException;
import javax.management.MBeanServerConnection;
import javax.management.MalformedObjectNameException;
import javax.management.Notification;
import javax.management.NotificationListener;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

import static com.google.common.base.Preconditions.checkNotNull;

public class JmxProxy implements NotificationListener, Serializable {

  private static final Logger LOG = LoggerFactory.getLogger(JmxProxy.class);

  private static final int JMX_PORT = 7199;
  private static final String JMX_URL = "service:jmx:rmi:///jndi/rmi://%s:%d/jmxrmi";
  private static final String JMX_OBJECT_NAME = "org.apache.cassandra.db:type=StorageService";

  private final JMXConnector jmxConnector;
  private final ObjectName mbeanName;
  private final MBeanServerConnection mbeanServer;
  private final StorageServiceMBean ssProxy;
  private final Optional<RepairStatusHandler> repairStatusHandler;
  private final String host;

  private JmxProxy(Optional<RepairStatusHandler> handler, String host, JMXConnector jmxConnector,
                   StorageServiceMBean ssProxy, ObjectName mbeanName,
                   MBeanServerConnection mbeanServer) {
    this.host = host;
    this.jmxConnector = jmxConnector;
    this.mbeanName = mbeanName;
    this.mbeanServer = mbeanServer;
    this.ssProxy = ssProxy;
    this.repairStatusHandler = handler;
  }

  /**
   * Connect to JMX interface on the given host and default JMX port without RepairStatusHandler.
   */
  public static JmxProxy connect(String host)
      throws ReaperException {
    return connect(Optional.<RepairStatusHandler>absent(), host, JMX_PORT);
  }

  /**
   * Connect to JMX interface on the given host and default JMX port.
   *
   * @param handler Implementation of {@link RepairStatusHandler} to process incoming notifications
   *                of repair events.
   */
  public static JmxProxy connect(Optional<RepairStatusHandler> handler, String host)
      throws ReaperException {
    return connect(handler, host, JMX_PORT);
  }

  /**
   * Connect to JMX interface on the given host and port.
   *
   * @param handler Implementation of {@link RepairStatusHandler} to process incoming notifications
   *                of repair events.
   */
  public static JmxProxy connect(Optional<RepairStatusHandler> handler, String host, int port)
      throws ReaperException {
    JMXServiceURL jmxUrl;
    ObjectName mbeanName;
    try {
      jmxUrl = new JMXServiceURL(String.format(JMX_URL, host, port));
      mbeanName = new ObjectName(JMX_OBJECT_NAME);
    } catch (MalformedURLException | MalformedObjectNameException e) {
      LOG.error("Failed to prepare the JMX connection");
      throw new ReaperException("Failure during preparations for JMX connection", e);
    }
    try {
      JMXConnector jmxConn = JMXConnectorFactory.connect(jmxUrl);
      MBeanServerConnection mbeanServerConn = jmxConn.getMBeanServerConnection();
      StorageServiceMBean
          ssProxy = JMX.newMBeanProxy(mbeanServerConn, mbeanName, StorageServiceMBean.class);
      JmxProxy proxy = new JmxProxy(handler, host, jmxConn, ssProxy, mbeanName, mbeanServerConn);
      // registering a listener throws bunch of exceptions, so we do it here rather than in the
      // constructor
      mbeanServerConn.addNotificationListener(mbeanName, proxy, null, null);
      LOG.info(String.format("JMX connection to %s properly connected.", host));
      return proxy;
    } catch (IOException | InstanceNotFoundException e) {
      LOG.error("Failed to establish JMX connection");
      throw new ReaperException("Failure when establishing JMX connection", e);
    }
  }

  /**
   * @return list of tokens in the cluster
   */
  public List<String> getTokens() {
    checkNotNull(ssProxy, "Looks like the proxy is not connected");
    return Lists.newArrayList(ssProxy.getTokenToEndpointMap().keySet());
  }

  /**
   * @return full class name of Cassandra's partitioner.
   */
  public String getPartitioner() {
    checkNotNull(ssProxy, "Looks like the proxy is not connected");
    return ssProxy.getPartitionerName();
  }

  public static String toSymbolicName(String s) {
    return s.toLowerCase().replaceAll("[^a-z0-9_]", "");
  }

  /**
   * @return Cassandra cluster name in shortened form (symbolic name).
   */
  public String getClusterName() {
    checkNotNull(ssProxy, "Looks like the proxy is not connected");
    return toSymbolicName(ssProxy.getClusterName());
  }

  /**
   * Triggers a repair of range (beginToken, endToken] for given keyspace and column family.
   *
   * The repair is triggered by {@link org.apache.cassandra.service.StorageServiceMBean#forceRepairRangeAsync}
   * For time being, we don't allow local nor snapshot repairs.
   *
   * @return repair sequence number
   */
  public int triggerRepair(BigInteger beginToken, BigInteger endToken, String keyspace,
                           String columnFamily) {
    checkNotNull(ssProxy, "Looks like the proxy is not connected");
    LOG.info(String.format("Triggering repair of range (%s,%s] for %s.%s via host %s",
                           beginToken.toString(), endToken.toString(), keyspace, columnFamily,
                           this.host));
    return ssProxy.forceRepairRangeAsync(
        beginToken.toString(),
        endToken.toString(),
        keyspace,
        false,                      // isSequential - if true, do "snapshot repairs"
        false,                      // isLocal - if false, repair all DCs
        columnFamily);
  }

  /**
   * Invoked when the MBean this class listens to publishes an event.
   *
   * We're only interested in repair-related events. Their format is explained at {@link
   * org.apache.cassandra.service.StorageServiceMBean#forceRepairAsync} The format is: notification
   * type: "repair" notification userData: int array of length 2 where [0] = command number [1] =
   * ordinal of AntiEntropyService.Status
   */
  @Override
  public void handleNotification(Notification notification, Object handback) {
    // we're interested in "repair"
    String type = notification.getType();
    LOG.debug(String.format("Received notification: %s", notification.toString()));
    if (repairStatusHandler.isPresent() && type.equals("repair")) {
      int[] data = (int[]) notification.getUserData();
      // get the repair sequence number
      int repairNo = data[0];
      // get the repair status
      ActiveRepairService.Status status = ActiveRepairService.Status.values()[data[1]];
      // this is some text message like "Starting repair...", "Finished repair...", etc.
      String message = notification.getMessage();
      // let the handler process the event
      repairStatusHandler.get().handle(repairNo, status, message);
    }
  }

  /**
   * Cleanly shut down by un-registering the listener and closing the JMX connection.
   */
  public void close() throws ReaperException {
    try {
      mbeanServer.removeNotificationListener(mbeanName, this);
      jmxConnector.close();
    } catch (IOException | InstanceNotFoundException | ListenerNotFoundException e) {
      throw new ReaperException(e);
    }
  }
}
