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
package org.apache.hama.bsp.message;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.Writable;
import org.apache.hama.Constants;
import org.apache.hama.HamaConfiguration;
import org.apache.hama.bsp.BSPMessageBundle;
import org.apache.hama.bsp.BSPPeer;
import org.apache.hama.bsp.BSPPeerImpl;
import org.apache.hama.bsp.TaskAttemptID;
import org.apache.hama.ipc.HamaRPCProtocolVersion;
import org.apache.hama.ipc.RPC;
import org.apache.hama.ipc.RPC.Server;
import org.apache.hama.util.LRUCache;
import org.apache.hama.util.BSPNetUtils;

/**
 * Implementation of the {@link HamaMessageManager}.
 * 
 */
public final class HamaMessageManagerImpl<M extends Writable> extends
    AbstractMessageManager<M> implements HamaMessageManager<M> {

  private static final Log LOG = LogFactory
      .getLog(HamaMessageManagerImpl.class);

  private Server server;

  private LRUCache<InetSocketAddress, HamaMessageManager<M>> peersLRUCache = null;

  @SuppressWarnings("serial")
  @Override
  public final void init(TaskAttemptID attemptId, BSPPeer<?, ?, ?, ?, M> peer,
      HamaConfiguration conf, InetSocketAddress peerAddress) {
    super.init(attemptId, peer, conf, peerAddress);
    startRPCServer(conf, peerAddress);
    peersLRUCache = new LRUCache<InetSocketAddress, HamaMessageManager<M>>(
        maxCachedConnections) {
      @Override
      protected final boolean removeEldestEntry(
          Map.Entry<InetSocketAddress, HamaMessageManager<M>> eldest) {
        if (size() > this.capacity) {
          HamaMessageManager<M> proxy = eldest.getValue();
          RPC.stopProxy(proxy);
          return true;
        }
        return false;
      }
    };
  }

  private final void startRPCServer(Configuration conf,
      InetSocketAddress peerAddress) {
    try {
      startServer(peerAddress.getHostName(), peerAddress.getPort());
    } catch (IOException ioe) {
      LOG.error("Fail to start RPC server!", ioe);
      throw new RuntimeException("RPC Server could not be launched!");
    }
  }

  private void startServer(String hostName, int port) throws IOException {
    int retry = 0;
    try {
      this.server = RPC.getServer(this, hostName, port,
          conf.getInt("hama.default.messenger.handler.threads.num", 5), false,
          conf);

      server.start();
      LOG.info("BSPPeer address:" + server.getListenerAddress().getHostName()
          + " port:" + server.getListenerAddress().getPort());
    } catch (BindException e) {
      LOG.warn("Address already in use. Retrying " + hostName + ":" + port + 1);
      startServer(hostName, port + 1);
      retry++;

      if (retry > 5) {
        throw new RuntimeException("RPC Server could not be launched!");
      }
    }
  }

  @Override
  public final void close() {
    super.close();
    if (server != null) {
      server.stop();
    }
  }

  @Override
  public void getRecoveryData(String peerName, boolean current) {
    InetSocketAddress targetPeerAddress = BSPNetUtils.getAddress(peerName);
    
    try {
      HamaMessageManager<M> bspPeerConnection = this.getBSPPeerConnection(targetPeerAddress);
      if(bspPeerConnection == null) {
        throw new IllegalArgumentException("Can not find " + targetPeerAddress.toString()
          + " to transfer messages to!");
      }
      else{
        // make an RPC to alive peer with arguments: [requesting peer]
        // [is data for previous/current superstep]
        bspPeerConnection.fetch(peer.getPeerName(), current);
      }
    } catch (Exception e) {
      LOG.info("Could not recover data from peer " + peerName);
      LOG.info(e.toString());
    }
  }

    @Override
    public final void transfer(InetSocketAddress addr, BSPMessageBundle<M> bundle)
            throws IOException {
        HamaMessageManager<M> bspPeerConnection = this.getBSPPeerConnection(addr);
        if (bspPeerConnection == null) {
            throw new IllegalArgumentException("Can not find " + addr.toString()
                    + " to transfer messages to!");
        } else {
            if (conf.getBoolean(Constants.MESSENGER_RUNTIME_COMPRESSION, false)) {
                ByteArrayOutputStream byteBuffer = new ByteArrayOutputStream();
                DataOutputStream bufferDos = new DataOutputStream(byteBuffer);
                bundle.write(bufferDos);

                byte[] compressed = compressor.compress(byteBuffer.toByteArray());
                peer.incrementCounter(BSPPeerImpl.PeerCounter.TOTAL_COMPRESSED_BYTES_TRANSFERED, compressed.length);
                peer.incrementCounter(BSPPeerImpl.PeerCounter.TOTAL_DECOMPRESSED_BYTES, byteBuffer.size());
                bspPeerConnection.put(compressed);
            } else {
                peer.incrementCounter(BSPPeerImpl.PeerCounter.TOTAL_MESSAGE_BYTES_TRANSFERED, bundle.getLength());
                bspPeerConnection.put(bundle);
            }
        }
    }


    public final void transfer_(InetSocketAddress addr, BSPMessageBundle<M> bundle)
            throws IOException {
        HamaMessageManager<M> bspPeerConnection = this.getBSPPeerConnection(addr);
        if (bspPeerConnection == null) {
            throw new IllegalArgumentException("Can not find " + addr.toString()
                    + " to transfer messages to!");
        } else {
            if (conf.getBoolean(Constants.MESSENGER_RUNTIME_COMPRESSION, false)) {
                ByteArrayOutputStream byteBuffer = new ByteArrayOutputStream();
                DataOutputStream bufferDos = new DataOutputStream(byteBuffer);
                bundle.write(bufferDos);

                byte[] compressed = compressor.compress(byteBuffer.toByteArray());
                peer.incrementCounter(BSPPeerImpl.PeerCounter.TOTAL_COMPRESSED_BYTES_TRANSFERED, compressed.length);
                peer.incrementCounter(BSPPeerImpl.PeerCounter.TOTAL_DECOMPRESSED_BYTES, byteBuffer.size());
                bspPeerConnection.put_(compressed);
            } else {
                peer.incrementCounter(BSPPeerImpl.PeerCounter.TOTAL_MESSAGE_BYTES_TRANSFERED, bundle.getLength());
                bspPeerConnection.put_(bundle);
            }
        }
    }

  /**
   * @param addr socket address to which BSP Peer Connection will be established
   * @return BSP Peer Connection, tried to return cached connection, else
   *         returns a new connection and caches it
   * @throws IOException
   */
  @SuppressWarnings("unchecked")
  protected final HamaMessageManager<M> getBSPPeerConnection(
      InetSocketAddress addr) throws IOException {
    HamaMessageManager<M> bspPeerConnection;
    if (!peersLRUCache.containsKey(addr)) {
      bspPeerConnection = (HamaMessageManager<M>) RPC.getProxy(
          HamaMessageManager.class, HamaRPCProtocolVersion.versionID, addr,
          this.conf);
      peersLRUCache.put(addr, bspPeerConnection);
    } else {
      bspPeerConnection = peersLRUCache.get(addr);
    }
    return bspPeerConnection;
  }

  @Override
  public final void put(M msg) throws IOException {
    loopBackMessage(msg);
  }

    @Override
    public final void put(BSPMessageBundle<M> bundle) throws IOException {
        loopBackBundle(bundle);
    }

    public final void put_(BSPMessageBundle<M> bundle) throws IOException {
        loopBackBundle_(bundle);
    }

  @Override
  public final void put(byte[] compressedBundle) throws IOException {
    byte[] decompressed = compressor.decompress(compressedBundle);

    BSPMessageBundle<M> bundle = new BSPMessageBundle<M>();
    ByteArrayInputStream bis = new ByteArrayInputStream(decompressed);
    DataInputStream dis = new DataInputStream(bis);
    bundle.readFields(dis);

    loopBackBundle(bundle);
  }

    @Override
    public void put_(byte[] compressedBundle) throws IOException {

    }

    @Override
  public final void fetch(String requestingPeerName, boolean current)
          throws IOException {

      InetSocketAddress requestingPeerAddress = BSPNetUtils.getAddress(requestingPeerName);
      BSPMessageBundle<M> bundle = null;
      
      // if current == true, return messages for current superstep, else
      // messages for previous superstep.
      if(current) {
        LOG.info("bspPeer " + peer.getPeerName() + " received request for this superstep data from bspPeer " + requestingPeerName);
        Iterator<Map.Entry<InetSocketAddress, BSPMessageBundle<M>>> it = outgoingMessageManager.getBundleIterator();
        while(it.hasNext()) {
          Map.Entry<InetSocketAddress, BSPMessageBundle<M>> entry = it.next();
          if (entry.getKey().equals(requestingPeerAddress)) {
            bundle = entry.getValue();
            break;
          }
        }
        transfer(requestingPeerAddress, bundle);
        return;
      }
      else {
        LOG.info("bspPeer " + peer.getPeerName() + " received request for previous superstep data from bspPeer " + requestingPeerName);
        bundle = outgoingMessageManager.getBundleFromPrevSuperstep(requestingPeerAddress);
      }

       // transfer those messages from the localQ of the peer which have
      // source-id of vertex belonging to the requestingPeerName. Partitioner is
      // used to determine such messages
       List<M> stateMsgs = localQueue.getRelevantMessages(requestingPeerName);
       LOG.info("Size of stateMsgs=" + stateMsgs.size());

       if (bundle == null && stateMsgs.size() > 0)
           bundle = new BSPMessageBundle<M>();

       for (M m : stateMsgs)
            bundle.addMessage(m);

       if(bundle == null)
            LOG.info("Found no bundle for requesting peer: " + requestingPeerName + " at peer: " + peer.getPeerName());
       else
            transfer_(requestingPeerAddress, bundle);
    }

  @Override
  public final long getProtocolVersion(String arg0, long arg1)
      throws IOException {
    return versionID;
  }

  @Override
  public InetSocketAddress getListenerAddress() {
    if (this.server != null) {
      return this.server.getListenerAddress();
    }
    return null;
  }

}
