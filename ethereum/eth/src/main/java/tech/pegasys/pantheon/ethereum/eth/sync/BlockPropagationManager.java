/*
 * Copyright 2018 ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package tech.pegasys.pantheon.ethereum.eth.sync;

import tech.pegasys.pantheon.ethereum.ProtocolContext;
import tech.pegasys.pantheon.ethereum.chain.BlockAddedEvent;
import tech.pegasys.pantheon.ethereum.chain.BlockAddedEvent.EventType;
import tech.pegasys.pantheon.ethereum.chain.Blockchain;
import tech.pegasys.pantheon.ethereum.core.Block;
import tech.pegasys.pantheon.ethereum.core.Hash;
import tech.pegasys.pantheon.ethereum.eth.manager.AbstractPeerTask;
import tech.pegasys.pantheon.ethereum.eth.manager.EthContext;
import tech.pegasys.pantheon.ethereum.eth.manager.EthMessage;
import tech.pegasys.pantheon.ethereum.eth.manager.EthPeer;
import tech.pegasys.pantheon.ethereum.eth.messages.EthPV62;
import tech.pegasys.pantheon.ethereum.eth.messages.NewBlockHashesMessage;
import tech.pegasys.pantheon.ethereum.eth.messages.NewBlockHashesMessage.NewBlockHash;
import tech.pegasys.pantheon.ethereum.eth.messages.NewBlockMessage;
import tech.pegasys.pantheon.ethereum.eth.sync.state.PendingBlocks;
import tech.pegasys.pantheon.ethereum.eth.sync.state.SyncState;
import tech.pegasys.pantheon.ethereum.eth.sync.tasks.GetBlockFromPeerTask;
import tech.pegasys.pantheon.ethereum.eth.sync.tasks.PersistBlockTask;
import tech.pegasys.pantheon.ethereum.mainnet.HeaderValidationMode;
import tech.pegasys.pantheon.ethereum.mainnet.ProtocolSchedule;
import tech.pegasys.pantheon.ethereum.p2p.wire.messages.DisconnectMessage.DisconnectReason;
import tech.pegasys.pantheon.ethereum.rlp.RLPException;
import tech.pegasys.pantheon.metrics.MetricCategory;
import tech.pegasys.pantheon.metrics.MetricsSystem;
import tech.pegasys.pantheon.metrics.OperationTimer;
import tech.pegasys.pantheon.util.uint.UInt256;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.google.common.collect.Range;
import io.netty.util.internal.ConcurrentSet;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class BlockPropagationManager<C> {
  private static final Logger LOG = LogManager.getLogger();

  private final SynchronizerConfiguration config;
  private final ProtocolSchedule<C> protocolSchedule;
  private final ProtocolContext<C> protocolContext;
  private final EthContext ethContext;
  private final SyncState syncState;

  private final AtomicBoolean started = new AtomicBoolean(false);

  private final Set<Hash> requestedBlocks = new ConcurrentSet<>();
  private final PendingBlocks pendingBlocks;
  private final OperationTimer announcedBlockIngestTimer;

  BlockPropagationManager(
      final SynchronizerConfiguration config,
      final ProtocolSchedule<C> protocolSchedule,
      final ProtocolContext<C> protocolContext,
      final EthContext ethContext,
      final SyncState syncState,
      final PendingBlocks pendingBlocks,
      final MetricsSystem metricsSystem) {
    this.config = config;
    this.protocolSchedule = protocolSchedule;
    this.protocolContext = protocolContext;
    this.ethContext = ethContext;

    this.syncState = syncState;
    this.pendingBlocks = pendingBlocks;

    this.announcedBlockIngestTimer =
        metricsSystem.createTimer(
            MetricCategory.BLOCKCHAIN,
            "pantheon_blockchain_announcedBlock_ingest",
            "Time to ingest a single announced block");
  }

  public void start() {
    if (started.compareAndSet(false, true)) {
      setupListeners();
    } else {
      throw new IllegalStateException(
          "Attempt to start an already started " + this.getClass().getSimpleName() + ".");
    }
  }

  private void setupListeners() {
    protocolContext.getBlockchain().observeBlockAdded(this::onBlockAdded);
    ethContext.getEthMessages().subscribe(EthPV62.NEW_BLOCK, this::handleNewBlockFromNetwork);
    ethContext
        .getEthMessages()
        .subscribe(EthPV62.NEW_BLOCK_HASHES, this::handleNewBlockHashesFromNetwork);
  }

  private void onBlockAdded(final BlockAddedEvent blockAddedEvent, final Blockchain blockchain) {
    // Check to see if any of our pending blocks are now ready for import
    final Block newBlock = blockAddedEvent.getBlock();

    final List<Block> readyForImport;
    synchronized (pendingBlocks) {
      // Remove block from pendingBlocks list
      pendingBlocks.deregisterPendingBlock(newBlock);

      // Import any pending blocks that are children of the newly added block
      readyForImport = pendingBlocks.childrenOf(newBlock.getHash());
    }

    if (!readyForImport.isEmpty()) {
      final Supplier<CompletableFuture<List<Block>>> importBlocksTask =
          PersistBlockTask.forUnorderedBlocks(
              protocolSchedule, protocolContext, readyForImport, HeaderValidationMode.FULL);
      ethContext
          .getScheduler()
          .scheduleSyncWorkerTask(importBlocksTask)
          .whenComplete(
              (r, t) -> {
                if (r != null) {
                  LOG.info("Imported {} pending blocks", r.size());
                }
              });
    }

    if (blockAddedEvent.getEventType().equals(EventType.HEAD_ADVANCED)) {
      final long head = blockchain.getChainHeadBlockNumber();
      final long cutoff = head + config.blockPropagationRange().lowerEndpoint();
      pendingBlocks.purgeBlocksOlderThan(cutoff);
    }
  }

  private void handleNewBlockFromNetwork(final EthMessage message) {
    final Blockchain blockchain = protocolContext.getBlockchain();
    final NewBlockMessage newBlockMessage = NewBlockMessage.readFrom(message.getData());
    try {
      final Block block = newBlockMessage.block(protocolSchedule);
      final UInt256 totalDifficulty = newBlockMessage.totalDifficulty(protocolSchedule);

      message.getPeer().chainState().update(block.getHeader(), totalDifficulty);

      // Return early if we don't care about this block
      final long localChainHeight = protocolContext.getBlockchain().getChainHeadBlockNumber();
      final long bestChainHeight = syncState.bestChainHeight(localChainHeight);
      if (!shouldImportBlockAtHeight(
          block.getHeader().getNumber(), localChainHeight, bestChainHeight)) {
        return;
      }
      if (pendingBlocks.contains(block.getHash())) {
        return;
      }
      if (blockchain.contains(block.getHash())) {
        return;
      }

      importOrSavePendingBlock(block);
    } catch (final RLPException e) {
      message.getPeer().disconnect(DisconnectReason.BREACH_OF_PROTOCOL);
    }
  }

  private void handleNewBlockHashesFromNetwork(final EthMessage message) {
    final Blockchain blockchain = protocolContext.getBlockchain();
    final NewBlockHashesMessage newBlockHashesMessage =
        NewBlockHashesMessage.readFrom(message.getData());
    try {
      // Register announced blocks
      final List<NewBlockHash> announcedBlocks =
          Lists.newArrayList(newBlockHashesMessage.getNewHashes());
      for (final NewBlockHash announcedBlock : announcedBlocks) {
        message.getPeer().registerKnownBlock(announcedBlock.hash());
        message.getPeer().registerHeight(announcedBlock.hash(), announcedBlock.number());
      }

      // Filter announced blocks for blocks we care to import
      final long localChainHeight = protocolContext.getBlockchain().getChainHeadBlockNumber();
      final long bestChainHeight = syncState.bestChainHeight(localChainHeight);
      final List<NewBlockHash> relevantAnnouncements =
          announcedBlocks
              .stream()
              .filter(a -> shouldImportBlockAtHeight(a.number(), localChainHeight, bestChainHeight))
              .collect(Collectors.toList());

      // Filter for blocks we don't yet know about
      final List<NewBlockHash> newBlocks = new ArrayList<>();
      for (final NewBlockHash announcedBlock : relevantAnnouncements) {
        if (requestedBlocks.contains(announcedBlock.hash())) {
          continue;
        }
        if (pendingBlocks.contains(announcedBlock.hash())) {
          continue;
        }
        if (blockchain.contains(announcedBlock.hash())) {
          continue;
        }
        if (requestedBlocks.add(announcedBlock.hash())) {
          newBlocks.add(announcedBlock);
        }
      }

      // Process known blocks we care about
      for (final NewBlockHash newBlock : newBlocks) {
        processAnnouncedBlock(message.getPeer(), newBlock)
            .whenComplete((r, t) -> requestedBlocks.remove(newBlock.hash()));
      }
    } catch (final RLPException e) {
      message.getPeer().disconnect(DisconnectReason.BREACH_OF_PROTOCOL);
    }
  }

  private CompletableFuture<Block> processAnnouncedBlock(
      final EthPeer peer, final NewBlockHash newBlock) {
    final AbstractPeerTask<Block> getBlockTask =
        GetBlockFromPeerTask.create(protocolSchedule, ethContext, newBlock.hash()).assignPeer(peer);

    return getBlockTask.run().thenCompose((r) -> importOrSavePendingBlock(r.getResult()));
  }

  @VisibleForTesting
  CompletableFuture<Block> importOrSavePendingBlock(final Block block) {
    // Synchronize to avoid race condition where block import event fires after the
    // blockchain.contains() check and before the block is registered, causing onBlockAdded() to be
    // invoked for the parent of this block before we are able to register it.
    synchronized (pendingBlocks) {
      if (!protocolContext.getBlockchain().contains(block.getHeader().getParentHash())) {
        // Block isn't connected to local chain, save it to pending blocks collection
        if (pendingBlocks.registerPendingBlock(block)) {
          LOG.info(
              "Saving announced block {} ({}) for future import",
              block.getHeader().getNumber(),
              block.getHash());
        }
        return CompletableFuture.completedFuture(block);
      }
    }

    // Import block
    final PersistBlockTask<C> importTask =
        PersistBlockTask.create(
            protocolSchedule, protocolContext, block, HeaderValidationMode.FULL);
    final OperationTimer.TimingContext blockTimer = announcedBlockIngestTimer.startTimer();
    return ethContext
        .getScheduler()
        .scheduleSyncWorkerTask(importTask::run)
        .whenComplete(
            (r, t) -> {
              if (t != null) {
                // TODO do we time failures?  But we cannot drop a label in at this point.
                LOG.warn(
                    "Failed to import announced block {} ({}).",
                    block.getHeader().getNumber(),
                    block.getHash());
              } else {
                final double timeInMs = blockTimer.stopTimer() * 1000;
                LOG.info(
                    String.format(
                        "Successfully imported announced block %d (%s) in %01.3fms.",
                        block.getHeader().getNumber(), block.getHash(), timeInMs));
              }
            });
  }

  // Only import blocks within a certain range of our head and sync target
  private boolean shouldImportBlockAtHeight(
      final long blockNumber, final long localHeight, final long bestChainHeight) {
    final long distanceFromLocalHead = blockNumber - localHeight;
    final long distanceFromBestPeer = blockNumber - bestChainHeight;
    final Range<Long> importRange = config.blockPropagationRange();
    return importRange.contains(distanceFromLocalHead)
        && importRange.contains(distanceFromBestPeer);
  }
}
