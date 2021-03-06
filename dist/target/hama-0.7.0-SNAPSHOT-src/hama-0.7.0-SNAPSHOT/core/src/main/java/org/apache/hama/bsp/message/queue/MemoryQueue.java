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
package org.apache.hama.bsp.message.queue;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.Writable;
import org.apache.hama.bsp.BSPMessageBundle;
import org.apache.hama.bsp.BSPPeer;
import org.apache.hama.bsp.TaskAttemptID;

/**
 * LinkedList backed queue structure for bookkeeping messages.
 */
public final class MemoryQueue<M extends Writable> implements
    SynchronizedQueue<M> {

  private final ConcurrentLinkedQueue<M> deque = new ConcurrentLinkedQueue<M>();
  private final ConcurrentLinkedQueue<BSPMessageBundle<M>> bundles = new ConcurrentLinkedQueue<BSPMessageBundle<M>>();
  private int numOfMsg = 0;
  private Iterator<M> currIterator;

  private Configuration conf;

  @Override
  public void addBundle(BSPMessageBundle<M> bundle) {
    numOfMsg += bundle.size();
    bundles.add(bundle);
  }
    @Override
    public List<M> getStateHints() {
        return null;
    }

    @Override
    public List<M> getRelevantMessages(String peerName) {
        return null;
    }

    @Override
    public void addBundleRecovery(BSPMessageBundle<M> bundle) {

    }

    @Override
  public final void addAll(Iterable<M> col) {
    for (M m : col)
      deque.add(m);
  }

    @Override
    public void addAllRecovery(Iterable<M> col) {

    }

    @Override
  public void addAll(MessageQueue<M> otherqueue) {
    M poll = null;
    while ((poll = otherqueue.poll()) != null) {
      deque.add(poll);
    }
  }

  @Override
  public final void add(M item) {
    deque.add(item);
  }

  @Override
  public final void clear() {
    deque.clear();
  }

  @Override
  public final M poll() {
    if (currIterator == null || !currIterator.hasNext()) {
      if (bundles.size() > 0)
        currIterator = bundles.poll().iterator();
      else
        return deque.poll();
    }

    numOfMsg--;
    return currIterator.next();
  }

  @Override
  public final int size() {
    return numOfMsg + deque.size();
  }

  @Override
  public final Iterator<M> iterator() {
    return deque.iterator();
  }

  @Override
  public void setConf(Configuration conf) {
    this.conf = conf;
  }

  @Override
  public Configuration getConf() {
    return conf;
  }

  @Override
  public void init(Configuration conf, TaskAttemptID id) {
    this.numOfMsg = 0;
    this.conf = conf;
  }

    @Override
    public void init(Configuration conf, TaskAttemptID id, BSPPeer peerRef) {

    }

    @Override
  public void close() {
    this.clear();
  }

  @Override
  public MessageQueue<M> getMessageQueue() {
    return this;
  }

}
