/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hama.graph;

import java.io.IOException;
import java.util.*;
import java.util.Map.Entry;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.ArrayWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.MapWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableComparable;
import org.apache.hama.Constants;
import org.apache.hama.HamaConfiguration;
import org.apache.hama.bsp.BSP;
import org.apache.hama.bsp.BSPPeer;
import org.apache.hama.bsp.HashPartitioner;
import org.apache.hama.bsp.Partitioner;
import org.apache.hama.bsp.PartitioningRunner.DefaultRecordConverter;
import org.apache.hama.bsp.PartitioningRunner.RecordConverter;
import org.apache.hama.bsp.sync.SyncException;
import org.apache.hama.commons.util.KeyValuePair;
import org.apache.hama.util.ReflectionUtils;

/**
 * Fully generic graph job runner.
 * 
 * @param <V> the id type of a vertex.
 * @param <E> the value type of an edge.
 * @param <M> the value type of a vertex.
 */
@SuppressWarnings("rawtypes")
public final class GraphJobRunner<V extends WritableComparable, E extends Writable, M extends Writable>
    extends BSP<Writable, Writable, Writable, Writable, GraphJobMessage> {

  public static enum GraphJobCounter {
    MULTISTEP_PARTITIONING, ITERATIONS, INPUT_VERTICES, AGGREGATE_VERTICES
  }

  private static final Log LOG = LogFactory.getLog(GraphJobRunner.class);

  // make sure that these values don't collide with the vertex names
  public static final String S_FLAG_MESSAGE_COUNTS = "hama.0";
  public static final String S_FLAG_AGGREGATOR_VALUE = "hama.1";
  public static final String S_FLAG_AGGREGATOR_INCREMENT = "hama.2";
  public static final String S_FLAG_VERTEX_INCREASE = "hama.3";
  public static final String S_FLAG_VERTEX_DECREASE = "hama.4";
  public static final String S_FLAG_VERTEX_ALTER_COUNTER = "hama.5";
  public static final String S_FLAG_VERTEX_TOTAL_VERTICES = "hama.6";
  public static final Text FLAG_MESSAGE_COUNTS = new Text(S_FLAG_MESSAGE_COUNTS);
  public static final Text FLAG_VERTEX_INCREASE = new Text(
      S_FLAG_VERTEX_INCREASE);
  public static final Text FLAG_VERTEX_DECREASE = new Text(
      S_FLAG_VERTEX_DECREASE);
  public static final Text FLAG_VERTEX_ALTER_COUNTER = new Text(
      S_FLAG_VERTEX_ALTER_COUNTER);
  public static final Text FLAG_VERTEX_TOTAL_VERTICES = new Text(
      S_FLAG_VERTEX_TOTAL_VERTICES);

  public static final String VERTEX_CLASS_KEY = "hama.graph.vertex.class";

  private HamaConfiguration conf;
  private Partitioner<V, M> partitioner;

  public static Class<?> VERTEX_CLASS;
  public static Class<? extends WritableComparable> VERTEX_ID_CLASS;
  public static Class<? extends Writable> VERTEX_VALUE_CLASS;
  public static Class<? extends Writable> EDGE_VALUE_CLASS;
  public static Class<Vertex<?, ?, ?>> vertexClass;

  private VerticesInfo<V, E, M> vertices;

  private boolean updated = true;
  private int globalUpdateCounts = 0;
  private int changedVertexCnt = 0;

  private long numberVertices = 0;
  // -1 is deactivated
  private int maxIteration = -1;
  private long iteration;
  private boolean recoveryTask = false;

  private AggregationRunner<V, E, M> aggregationRunner;
  private VertexOutputWriter<Writable, Writable, V, E, M> vertexOutputWriter;

  private BSPPeer<Writable, Writable, Writable, Writable, GraphJobMessage> peer;
  
  ArrayWritable log = new ArrayWritable(LongWritable.class);

  private void redoSuperstep() throws IOException, SyncException, InterruptedException {
    List<GraphJobMessage> stateHints = peer.retrieveStateHints();
    LOG.info("Retrieved StateHints. Size=" + stateHints.size());
    for (GraphJobMessage m : stateHints) {
        if (m.getNumOfValues() != 1)
            System.out.println("SET MESSAGE NUMVALUES:" + m.getNumOfValues());

        Vertex v = vertices.get((V) m.getSrcVertexId());
        Iterable<Writable> it = m.getIterableMessages();
        if (it.iterator().hasNext()) {
            v.setValue(it.iterator().next());
        }
    }

    // update the value of local variables
    iteration = peer.getSuperstepCount() - 1;

    // onPeerInitialized has put messages from prevSuperstep outgoingBundles on
    // alive peers into the localQ
    GraphJobMessage firstVertexMessage = parseMessages(peer);
    doSuperstep(firstVertexMessage, peer);
  }

  @Override
  public final void setup(
      BSPPeer<Writable, Writable, Writable, Writable, GraphJobMessage> peer)
      throws IOException, SyncException, InterruptedException {
 
    LOG.info("[GraphJobRunner] Entering setup.");  
    recoveryTask = peer.isRecoveryTask();

    if (recoveryTask == true) {
        Writable[] info = peer.getLog();

        // We are not calling countGlobalVertexCount for the recovering peer. Hence,
        // we ge this data from the log stored by master peer.
        numberVertices = ((LongWritable)info[0]).get();
        //LOG.info("Recovering peer reading from log: " + numberVertices + " vertices");
    }
      
    setupFields(peer);

    long start_time = System.nanoTime();
    loadVertices(peer);
    long end_time = System.nanoTime();
    double difference = (end_time - start_time)/1e9;
    //LOG.info("graph loading seconds " + difference);

    if(recoveryTask == false) {
      countGlobalVertexCount(peer);

      // master task writes to persistent storage at the start of the
      // computation. This information is used by recovering peers
      if (isMasterTask(peer)) {
        Writable[] writeArr = new Writable[1];
        writeArr[0] = new LongWritable(numberVertices);
        log.set(writeArr);
        peer.persistLog(log.get());
      }
    }

    doInitialSuperstep(peer);

    if(recoveryTask) {
      redoSuperstep();
      peer.doFirstSyncAfterRecovery();
    }
  }

  @Override
  public final void bsp(
      BSPPeer<Writable, Writable, Writable, Writable, GraphJobMessage> peer)
      throws IOException, SyncException, InterruptedException {

    LOG.info("Entered GraphRunner.bsp. Is recovery task? " + recoveryTask);
    int superstepsAsRecovery=0;
    // we do supersteps while we still have updates and have not reached our
    // maximum iterations yet
    while (updated && !((maxIteration > 0) && iteration > maxIteration)) {
      // reset the global update counter from our master in every
      // superstep
      globalUpdateCounts = 0;

      // for the recovery task, the first sync() is done as part of
      // peer.doFirstSyncAfterRecovery()
      if(recoveryTask) {
        recoveryTask = false;
      }
      else {
        peer.sync();
      }

      // note that the messages must be parsed here
      GraphJobMessage firstVertexMessage = parseMessages(peer);
      // master/slaves needs to update
      
      doAggregationUpdates(peer);
      
      // check if updated changed by our aggregators
      if (!updated) {
        break;
      }

      // loop over vertices and do their computation
      doSuperstep(firstVertexMessage, peer);

      if (isMasterTask(peer)) {
        peer.getCounter(GraphJobCounter.ITERATIONS).increment(1);
      }
    }
  }

  /**
   * Just write <ID as Writable, Value as Writable> pair as a result. Note that
   * this will also be executed when failure happened.
   * 
   * @param peer
   * @throws java.io.IOException
   */
  @Override
  public final void cleanup(
      BSPPeer<Writable, Writable, Writable, Writable, GraphJobMessage> peer)
      throws IOException {
    vertexOutputWriter.setup(conf);
    Iterator<Vertex<V, E, M>> iterator = vertices.iterator();
    while (iterator.hasNext()) {
      vertexOutputWriter.write(iterator.next(), peer);
    }
    vertices.clear();
  }

  /**
   * The master task is going to check the number of updated vertices and do
   * master aggregation. In case of no aggregators defined, we save a sync by
   * reading multiple typed messages.
   */
  private void doAggregationUpdates(
      BSPPeer<Writable, Writable, Writable, Writable, GraphJobMessage> peer)
      throws IOException, SyncException, InterruptedException {

    // this is only done in every second iteration
    if (isMasterTask(peer)) {
      MapWritable updatedCnt = new MapWritable();
      // send total number of vertices.
      updatedCnt.put(
          FLAG_VERTEX_TOTAL_VERTICES,
          new LongWritable((peer.getCounter(GraphJobCounter.INPUT_VERTICES)
              .getCounter())));
      // exit if there's no update made
      if (globalUpdateCounts == 0) {
        updatedCnt.put(FLAG_MESSAGE_COUNTS, new IntWritable(Integer.MIN_VALUE));
      } else {
        getAggregationRunner().doMasterAggregation(updatedCnt);
      }
      // send the updates from the master tasks back to the slaves
      for (String peerName : peer.getAllPeerNames()) {
        peer.send(peerName, new GraphJobMessage(updatedCnt));
      }
    }

    // Not syncing here may cause aggregate values from the master(tree root) to
    // reach the leaves one superstep late. But that should be ok.
    /*if (getAggregationRunner().isEnabled()) {
      peer.sync();
      // now the map message must be read that might be send from the master
      updated = getAggregationRunner().receiveAggregatedValues(
          peer.getCurrentMessage().getMap(), iteration);
    }*/
  }

  private Set<V> notComputedVertices;

  /**
   * Do the main logic of a superstep, namely checking if vertices are active,
   * feeding compute with messages and controlling combiners/aggregators. We
   * iterate over our messages and vertices in sorted order. That means that we
   * need to seek the first vertex that has the same ID as the iterated message.
   */
  @SuppressWarnings("unchecked")
  private void doSuperstep(GraphJobMessage currentMessage,
      BSPPeer<Writable, Writable, Writable, Writable, GraphJobMessage> peer)
      throws IOException {
    int activeVertices = 0;
    this.changedVertexCnt = 0;
    vertices.startSuperstep();

    notComputedVertices = new HashSet();
    notComputedVertices.addAll(vertices.keySet());

    // Simulating bspPeer failure!

    LOG.info(peer.getPeerName());
    
    //if(recoveryTask == false && peer.getPeerName().equals("slave1:61001") && peer.getSuperstepCount()%20 == 0) {
    if(recoveryTask == false && peer.getPeerName().contains("slave1") && peer.getSuperstepCount()%20 == 0) {
      LOG.info("simulated failure");
      System.exit(49);
    }

    Vertex<V, E, M> vertex = null;

    while (currentMessage != null) {
      vertex = vertices.get((V) currentMessage.getVertexId());

      // reactivation
      if (vertex.isHalted()) {
        vertex.setActive();
      }

      if (!vertex.isHalted()) {
        vertex.compute((Iterable<M>) currentMessage.getIterableMessages());
        vertices.finishVertexComputation(vertex);
        activeVertices++;

        notComputedVertices.remove(vertex.getVertexID());
      }

      currentMessage = peer.getCurrentMessage();
    }

    for (V v : notComputedVertices) {
      vertex = vertices.get(v);
      if (!vertex.isHalted()) {
        vertex.compute(Collections.<M> emptyList());
        vertices.finishVertexComputation(vertex);
        activeVertices++;
      }
    }

    vertices.finishSuperstep();
    getAggregationRunner().sendAggregatorValues(peer, activeVertices,
        this.changedVertexCnt);
    this.iteration++;
  }

  /**
   * Seed the vertices first with their own values in compute. This is the first
   * superstep after the vertices have been loaded.
   */
  private void doInitialSuperstep(
      BSPPeer<Writable, Writable, Writable, Writable, GraphJobMessage> peer)
      throws IOException {
    this.changedVertexCnt = 0;
    vertices.startSuperstep();

    Iterator<Vertex<V, E, M>> iterator = vertices.iterator();

    while (iterator.hasNext()) {
      Vertex<V, E, M> vertex = iterator.next();

      // Calls setup method.
      vertex.setup(conf);
      /*
      LOG.info("Prior to compute");
      LOG.info("Vertex Get Val" + vertex.getValue() + " NumVertices " + vertex.getNumVertices());
      if (recoveryTask)
          LOG.info("SuperStepCount : " + vertex.getSuperstepCount());
      */
      vertex.compute(Collections.singleton(vertex.getValue()));
      /*
      LOG.info("After Compute");
      */
      vertices.finishVertexComputation(vertex);
    }

    vertices.finishSuperstep();

    if(recoveryTask == false)
      getAggregationRunner().sendAggregatorValues(peer, 1, this.changedVertexCnt);

    iteration++;
  }

  @SuppressWarnings("unchecked")
  private void setupFields(
      BSPPeer<Writable, Writable, Writable, Writable, GraphJobMessage> peer)
      throws IOException {
    this.peer = peer;
    this.conf = peer.getConfiguration();
    maxIteration = peer.getConfiguration().getInt("hama.graph.max.iteration",
        -1);

    GraphJobRunner.<V, E, M> initClasses(conf);

    partitioner = (Partitioner<V, M>) org.apache.hadoop.util.ReflectionUtils
        .newInstance(
            conf.getClass("bsp.input.partitioner.class", HashPartitioner.class),
            conf);

    Class<?> outputWriter = conf.getClass(
        GraphJob.VERTEX_OUTPUT_WRITER_CLASS_ATTR, VertexOutputWriter.class);
    vertexOutputWriter = (VertexOutputWriter<Writable, Writable, V, E, M>) ReflectionUtils
        .newInstance(outputWriter);

    setAggregationRunner(new AggregationRunner<V, E, M>());
    getAggregationRunner().setupAggregators(peer);

    Class<? extends VerticesInfo<V, E, M>> verticesInfoClass = (Class<? extends VerticesInfo<V, E, M>>) conf
        .getClass("hama.graph.vertices.info", MapVerticesInfo.class,
            VerticesInfo.class);
    vertices = ReflectionUtils.newInstance(verticesInfoClass);
    vertices.init(this, conf, peer.getTaskId());
  }

  @SuppressWarnings("unchecked")
  public static <V extends WritableComparable<? super V>, E extends Writable, M extends Writable> void initClasses(
      Configuration conf) {
    Class<V> vertexIdClass = (Class<V>) conf.getClass(
        GraphJob.VERTEX_ID_CLASS_ATTR, Text.class, Writable.class);
    Class<M> vertexValueClass = (Class<M>) conf.getClass(
        GraphJob.VERTEX_VALUE_CLASS_ATTR, IntWritable.class, Writable.class);
    Class<E> edgeValueClass = (Class<E>) conf.getClass(
        GraphJob.VERTEX_EDGE_VALUE_CLASS_ATTR, IntWritable.class,
        Writable.class);
    vertexClass = (Class<Vertex<?, ?, ?>>) conf.getClass(
        "hama.graph.vertex.class", Vertex.class);

    // set the classes statically, so we can save memory per message
    VERTEX_ID_CLASS = vertexIdClass;
    VERTEX_VALUE_CLASS = vertexValueClass;
    VERTEX_CLASS = vertexClass;
    EDGE_VALUE_CLASS = edgeValueClass;
  }

  /**
   * Loads vertices into memory of each peer.
   */
  @SuppressWarnings("unchecked")
  private void loadVertices(
      BSPPeer<Writable, Writable, Writable, Writable, GraphJobMessage> peer)
      throws IOException, SyncException, InterruptedException {
    RecordConverter converter = org.apache.hadoop.util.ReflectionUtils
        .newInstance(conf.getClass(Constants.RUNTIME_PARTITION_RECORDCONVERTER,
            DefaultRecordConverter.class, RecordConverter.class), conf);

    // our VertexInputReader ensures incoming vertices are sorted by their ID
    Vertex<V, E, M> vertex = GraphJobRunner
        .<V, E, M> newVertexInstance(VERTEX_CLASS);
    Vertex<V, E, M> currentVertex = GraphJobRunner
        .<V, E, M> newVertexInstance(VERTEX_CLASS);

    KeyValuePair<Writable, Writable> record = null;
    KeyValuePair<Writable, Writable> converted = null;

    while ((record = peer.readNext()) != null) {
      converted = converter.convertRecord(record, conf);
      currentVertex = (Vertex<V, E, M>) converted.getValue();

      if (vertex.getVertexID() == null) {
        vertex = currentVertex;
      } else {
        if (vertex.getVertexID().equals(currentVertex.getVertexID())) {
          for (Edge<V, E> edge : currentVertex.getEdges()) {
            vertex.addEdge(edge);
          }
        } else {
          if (vertex.compareTo(currentVertex) > 0) {
            throw new IOException(
                "The records of split aren't in order by vertex ID.");
          }

          addVertex(vertex);
          vertex = currentVertex;
        }
      }
    }
    // add last vertex.
    addVertex(vertex);

    LOG.info(vertices.size() + " vertices are loaded into "
        + peer.getPeerName());
    LOG.debug("Starting Vertex processing!");
  }

  /**
   * Add new vertex into memory of each peer.
   * 
   * @throws IOException
   */
  private void addVertex(Vertex<V, E, M> vertex) throws IOException {
    if (conf.getBoolean("hama.graph.self.ref", false)) {
      vertex.addEdge(new Edge<V, E>(vertex.getVertexID(), null));
    }

    vertices.put(vertex);

    LOG.debug("Added VertexID: " + vertex.getVertexID() + " in peer "
        + peer.getPeerName());
  }

  /**
   * Remove vertex from this peer.
   * 
   * @throws IOException
   */
  private void removeVertex(V vertexID) {
    vertices.remove(vertexID);

    LOG.debug("Removed VertexID: " + vertexID + " in peer "
        + peer.getPeerName());
  }

  /**
   * Counts vertices globally by sending the count of vertices in the map to the
   * other peers.
   */
  private void countGlobalVertexCount(
      BSPPeer<Writable, Writable, Writable, Writable, GraphJobMessage> peer)
      throws IOException, SyncException, InterruptedException {
    for (String peerName : peer.getAllPeerNames()) {
      peer.send(peerName, new GraphJobMessage(new IntWritable(vertices.size())));
    }

    peer.sync();

    GraphJobMessage msg;
    while ((msg = peer.getCurrentMessage()) != null) {
      if (msg.isVerticesSizeMessage()) {
        numberVertices += msg.getVerticesSize().get();
      }
    }

    if (isMasterTask(peer)) {
      peer.getCounter(GraphJobCounter.INPUT_VERTICES).increment(numberVertices);
    }
  }

  /**
   * Parses the messages in every superstep and does actions according to flags
   * in the messages.
   * 
   * @return the first vertex message, null if none received.
   */
  @SuppressWarnings("unchecked")
  private GraphJobMessage parseMessages(
      BSPPeer<Writable, Writable, Writable, Writable, GraphJobMessage> peer)
      throws IOException, SyncException, InterruptedException {
    GraphJobMessage msg = null;
    boolean dynamicAdditions = false;
    boolean dynamicRemovals = false;

    while ((msg = peer.getCurrentMessage()) != null) {
      // either this is a vertex message or a directive that must be read
      // as map
      if (msg.isVertexMessage()) {
        // if we found a vertex message (ordering defines they come after map
        // messages, we return that as the first message so the outward process
        // can join them correctly with the VerticesInfo.
        break;
      } else if (msg.isMapMessage()) {
        for (Entry<Writable, Writable> e : msg.getMap().entrySet()) {
          Text vertexID = (Text) e.getKey();
          if (FLAG_MESSAGE_COUNTS.equals(vertexID)) {
            if (((IntWritable) e.getValue()).get() == Integer.MIN_VALUE) {
              updated = false;
            } else {
              globalUpdateCounts += ((IntWritable) e.getValue()).get();
            }
          } else if (getAggregationRunner().isEnabled() && isMasterTask(peer) 
              && vertexID.toString().startsWith(S_FLAG_AGGREGATOR_VALUE)) {
            getAggregationRunner().masterReadAggregatedValue(vertexID,
                (M) e.getValue());
          } else if (getAggregationRunner().isEnabled() && isMasterTask(peer)
              && vertexID.toString().startsWith(S_FLAG_AGGREGATOR_INCREMENT)) {
            getAggregationRunner().masterReadAggregatedIncrementalValue(
                vertexID, (M) e.getValue());
          } else if (FLAG_VERTEX_INCREASE.equals(vertexID)) {
            dynamicAdditions = true;
            addVertex((Vertex<V, E, M>) e.getValue());
          } else if (FLAG_VERTEX_DECREASE.equals(vertexID)) {
            dynamicRemovals = true;
            removeVertex((V) e.getValue());
          } else if (FLAG_VERTEX_TOTAL_VERTICES.equals(vertexID)) {
            this.numberVertices = ((LongWritable) e.getValue()).get();
          } else if (FLAG_VERTEX_ALTER_COUNTER.equals(vertexID)) {
            if (isMasterTask(peer)) {
              peer.getCounter(GraphJobCounter.INPUT_VERTICES).increment(
                  ((LongWritable) e.getValue()).get());
            } else {
              throw new UnsupportedOperationException(
                  "A message to increase vertex count is in a wrong place: "
                      + peer);
            }
          }
        }

      } else {
        throw new UnsupportedOperationException("Unknown message type: " + msg);
      }

    }

    // If we applied any changes to vertices, we need to call finishAdditions
    // and finishRemovals in the end.
    if (dynamicAdditions) {
      finishAdditions();
    }
    if (dynamicRemovals) {
      finishRemovals();
    }

    return msg;
  }

  private void finishRemovals() throws IOException {
    vertices.finishRemovals();
    // finish the "superstep" because we have written a new file here
    vertices.finishSuperstep();
  }

  private void finishAdditions() throws IOException {
    vertices.finishAdditions();
    // finish the "superstep" because we have written a new file here
    vertices.finishSuperstep();
  }

  /**
   * @return the number of vertices, globally accumulated.
   */
  public final long getNumberVertices() {
    return numberVertices;
  }

  /**
   * @return the current number of iterations.
   */
  public final long getNumberIterations() {
    return iteration;
  }

  /**
   * @return the defined number of maximum iterations, -1 if not defined.
   */
  public final int getMaxIteration() {
    return maxIteration;
  }

  /**
   * @return the defined partitioner instance.
   */
  public final Partitioner<V, M> getPartitioner() {
    return partitioner;
  }

  /**
   * Gets the last aggregated value at the given index. The index is dependend
   * on how the aggregators were configured during job setup phase.
   * 
   * @return the value of the aggregator, or null if none was defined.
   */
  public final Writable getLastAggregatedValue(int index) {
    return getAggregationRunner().getLastAggregatedValue(index);
  }

  /**
   * Gets the last aggregated number of vertices at the given index. The index
   * is dependend on how the aggregators were configured during job setup phase.
   * 
   * @return the value of the aggregator, or null if none was defined.
   */
  public final IntWritable getNumLastAggregatedVertices(int index) {
    return getAggregationRunner().getNumLastAggregatedVertices(index);
  }

  /**
   * @return the peer instance.
   */
  public final BSPPeer<Writable, Writable, Writable, Writable, GraphJobMessage> getPeer() {
    return peer;
  }

  /**
   * Checks if this is a master task. The master task is the first peer in the
   * peer array.
   */
  public static boolean isMasterTask(
      BSPPeer<Writable, Writable, Writable, Writable, GraphJobMessage> peer) {
    return peer.getPeerName().equals(getMasterTask(peer));
  }

  /**
   * @return the name of the master peer, the name at the first index of the
   *         peers.
   */
  public static String getMasterTask(
      BSPPeer<Writable, Writable, Writable, Writable, GraphJobMessage> peer) {
    return peer.getPeerName(0);
  }

  /**
   * @return a new vertex instance
   */
  @SuppressWarnings({ "unchecked" })
  public static <V extends WritableComparable, E extends Writable, M extends Writable> Vertex<V, E, M> newVertexInstance(
      Class<?> vertexClass) {
    return (Vertex<V, E, M>) ReflectionUtils.newInstance(vertexClass);
  }

  // following new instances don't need conf injects.

  /**
   * @return a new vertex id object.
   */
  @SuppressWarnings("unchecked")
  public static <X extends Writable> X createVertexIDObject() {
    return (X) ReflectionUtils.newInstance(VERTEX_ID_CLASS);
  }

  /**
   * @return a new vertex value object.
   */
  @SuppressWarnings("unchecked")
  public static <X extends Writable> X createVertexValue() {
    return (X) ReflectionUtils.newInstance(VERTEX_VALUE_CLASS);
  }

  /**
   * @return a new edge cost object.
   */
  @SuppressWarnings("unchecked")
  public static <X extends Writable> X createEdgeCostObject() {
    return (X) ReflectionUtils.newInstance(EDGE_VALUE_CLASS);
  }

  public int getChangedVertexCnt() {
    return changedVertexCnt;
  }

  public void setChangedVertexCnt(int changedVertexCnt) {
    this.changedVertexCnt = changedVertexCnt;
  }

  /**
   * @return the aggregationRunner
   */
  public AggregationRunner<V, E, M> getAggregationRunner() {
    return aggregationRunner;
  }

  /**
   * @param aggregationRunner the aggregationRunner to set
   */
  void setAggregationRunner(AggregationRunner<V, E, M> aggregationRunner) {
    this.aggregationRunner = aggregationRunner;
  }

}
