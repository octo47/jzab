/**
 * Licensed to the Apache Software Foundatlion (ASF) under one
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

package org.apache.zab;

import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import org.apache.zab.proto.ZabMessage.Message;
import org.apache.zab.transport.Transport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class manages the logic synchronizing the transactions to disk. It can
 * batch several transactions and sync to disk once.
 */
public class SyncProposalProcessor implements RequestProcessor,
                                              Callable<Void> {

  private final Log log;

  private final BlockingQueue<Request> proposalQueue =
      new LinkedBlockingQueue<Request>();

  Future<Void> ft;

  private final Transport transport;

  private static final Logger LOG =
      LoggerFactory.getLogger(SyncProposalProcessor.class);

  // The maximum count of batched transactions. Once batched transactions are
  // beyond this size, we force synchronizing them to disk and acknowledging
  // the leader.
  private static final int MAX_BATCH_SIZE = 1000;

  /**
   * Constructs a SyncProposalProcessor object.
   *
   * @param log the log which the transaction will be synchronized to.
   * @param transport used to send acknowledgment.
   */
  public SyncProposalProcessor(Log log, Transport transport) {
    this.log = log;
    this.transport = transport;
    ExecutorService es =
        Executors.newSingleThreadExecutor(DaemonThreadFactory.FACTORY);
    ft = es.submit(this);
    es.shutdown();
  }

  @Override
  public void processRequest(Request request) {
    proposalQueue.add(request);
  }

  void sendAck(String source, Zxid ackZxid) {
    Message ack = MessageBuilder.buildAck(ackZxid);
    ByteBuffer buffer = ByteBuffer.wrap(ack.toByteArray());
    this.transport.send(source, buffer);
  }

  @Override
  public Void call() throws Exception {
    try {
      LOG.debug("Batched SyncRequestProcessor gets started.");
      Request lastReq = null;
      // Number of transactions batched so far.
      int batchCount = 0;

      while (true) {
        Request request;
        if (lastReq == null) {
          request = this.proposalQueue.take();
          lastReq = request;
        } else {
          request = this.proposalQueue.poll();
          if (request == null || batchCount == MAX_BATCH_SIZE) {
            // Sync to disk and send ACK to leader.
            this.log.sync();
            Zxid zxid = MessageBuilder
                        .fromProtoZxid(lastReq.getMessage()
                                              .getProposal()
                                              .getZxid());
            sendAck(lastReq.getServerId(), zxid);
            batchCount = 0;
          }
          lastReq = request;
        }
        if (request != null) {
          if (request == Request.REQUEST_OF_DEATH) {
            break;
          }
          Transaction txn = MessageBuilder
                            .fromProposal(request.getMessage().getProposal());
          LOG.debug("Syncing transaction {} to disk.", txn.getZxid());
          this.log.append(txn);
          batchCount++;
        }
      }
    } catch (Exception e) {
      LOG.error("Caught exception in SyncProposalProcessor!");
      throw e;
    }
    LOG.debug("SyncProposalProcessor has been shut down.");
    return null;
  }

  @Override
  public void shutdown() throws InterruptedException, ExecutionException {
    this.proposalQueue.add(Request.REQUEST_OF_DEATH);
    this.ft.get();
  }
}