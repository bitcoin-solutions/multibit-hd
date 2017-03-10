package org.multibit.hd.core.services;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.Uninterruptibles;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.bitcoinj.core.*;
import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.KeyCrypterException;
import org.bitcoinj.crypto.TransactionSignature;
import org.bitcoinj.net.discovery.DnsDiscovery;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.script.Script;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.wallet.DeterministicKeyChain;
import org.bitcoinj.wallet.KeyChain;
import org.joda.time.DateTime;
import org.multibit.commons.files.SecureFiles;
import org.multibit.hd.core.config.Configurations;
import org.multibit.hd.core.dto.*;
import org.multibit.hd.core.events.*;
import org.multibit.hd.core.managers.BlockStoreManager;
import org.multibit.hd.core.managers.InstallationManager;
import org.multibit.hd.core.managers.WalletManager;
import org.multibit.hd.core.network.MultiBitPeerEventListener;
import org.multibit.hd.core.utils.Coins;
import org.multibit.hd.hardware.core.HardwareWalletService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * <p>Service to provide access to the Bitcoin network, including:</p>
 * <ul>
 * <li>Initialisation of bitcoin network connection</li>
 * <li>Ability to send bitcoin</li>
 * </ul>
 * <p/>
 * <p>Emits the following events:</p>
 * <ul>
 * <li><code>BitcoinNetworkChangeEvent</code></li>
 * </ul>
 *
 * @since 0.0.1
 */
public class BitcoinNetworkService extends AbstractService {

  private static final Logger log = LoggerFactory.getLogger(BitcoinNetworkService.class);

  public static final int MAXIMUM_NUMBER_OF_PEERS = 10;

  private static final int SIZE_OF_SIGNATURE = 72; // bytes

  private static int CONNECTION_TIMEOUT = 4000; // milliseconds

  private static int NUMBER_OF_PEERS_TO_PING = 2;

  /**
   * The number of blocks to go back for a replay (based on the length of time coinbases need to mature for similar forking reasons)
   */
  private static int NUMBER_OF_BLOCKS_DELTA_FOR_REPLAY = 120;
  /**
   * The boundary for when more mining fee is due
   */
  private static final int MINING_FEE_BOUNDARY = 1000;  // bytes

  private BlockStore blockStore;
  private PeerGroup peerGroup;
  private BlockChain blockChain;
  private MultiBitPeerEventListener peerEventListener;

  private final NetworkParameters networkParameters;

  private boolean startedOk = false;

  private Optional<SendRequestSummary> lastSendRequestSummaryOptional = Optional.absent();
  private Optional<Wallet> lastWalletOptional = Optional.absent();

  /**
   * @param networkParameters The Bitcoin network parameters
   */
  public BitcoinNetworkService(NetworkParameters networkParameters) {

    super();

    Preconditions.checkNotNull(networkParameters, "'networkParameters' must be present");

    log.debug("Bitcoin network service using '{}'", networkParameters.getId());

    this.networkParameters = networkParameters;

    requireFixedThreadPoolExecutor(5, "bitcoin-network");

  }

  @Override
  public boolean startInternal() {
    // Note that the actual connection to the Bitcoin network is performed lazily,
    // only when a wallet needs syncing

    return true;
  }

  @Override
  protected boolean shutdownNowInternal(ShutdownEvent.ShutdownType shutdownType) {

    if (SwingUtilities.isEventDispatchThread()) {
      log.warn("BitcoinNetworkService should not be closed on EDT - the UI will freeze");
    }

    log.info("Bitcoin network service shutting down...");

    // Order is important here
    startedOk = false;

    // Provide additional exception handling to ensure we reach the saveWallet stage
    try {
      // Stop the peer group if it is running
      stopPeerGroup();

      // Close the block store
      closeBlockstore();

    } catch (Exception e) {
      log.error("Unexpected exception - continuing to save wallet", e);
    }

    // Save the current wallet
    WalletManager.INSTANCE.saveWallet();

    // Close the wallet
    WalletManager.INSTANCE.closeWallet();

    log.debug("Bitcoin network service specific code is shut down");

    // The Bitcoin network service is tied to a wallet so must always be fully shutdown
    return true;

  }

  /**
   * Open the blockstore, optionally checkpointing it to a date
   *
   * @param applicationDataDirectory The current application directory
   * @param replayConfig              ReplayHints giving the date or stored block to use for replay
   *                                 if not present then no checkpointing is done and te blockstore is simply opened
   */
  public BlockStore openBlockStore(File applicationDataDirectory, ReplayConfig replayConfig) {

    BlockStore blockStoreToReturn = null;
    try {
      // Check if there is a wallet - if there is no wallet the network will not start (there's nowhere to put the blockchain)
      if (!WalletManager.INSTANCE.getCurrentWalletSummary().isPresent()) {
        log.warn("Not opening blockstore as there is currently no wallet.");
        return null;
      }
      File walletParentDirectory = WalletManager.INSTANCE.getCurrentWalletFile(applicationDataDirectory).get().getParentFile();

      File blockStoreFile = SecureFiles.verifyOrCreateFile(walletParentDirectory, InstallationManager.MBHD_PREFIX + InstallationManager.SPV_BLOCKCHAIN_SUFFIX);
      File checkpointsFile = SecureFiles.verifyOrCreateFile(walletParentDirectory, InstallationManager.MBHD_PREFIX + InstallationManager.CHECKPOINTS_SUFFIX);

      if (replayConfig.getReplayDate().isPresent()) {
        // Create a block store and checkpoint it using the replay date
        log.debug("Create new block store - using replay date");
        blockStoreToReturn = new BlockStoreManager(networkParameters).createOrOpenBlockStore(blockStoreFile, checkpointsFile, replayConfig.getReplayDate().get(), true);
      } else {
        if (replayConfig.getReplayStoredBlockStack().isPresent()) {
          // Create a blockstore using the StoredBlockStack
          blockStoreToReturn = new BlockStoreManager(networkParameters).createOrOpenBlockStore(blockStoreFile, replayConfig.getReplayStoredBlockStack().get(), true);
        } else {
          // Load or create the blockStore - no checkpointing or storedBlock used
          log.debug("Create new block store - no replay date or storedBlock");
          blockStoreToReturn = new BlockStoreManager(networkParameters).createOrOpenBlockStore(blockStoreFile, checkpointsFile, null, false);
          log.debug(
            "Success. Blockstore is '{}', height is {}",
            blockStoreToReturn,
            blockStoreToReturn.getChainHead() == null ? "Unknown" : blockStoreToReturn.getChainHead().getHeight());
        }
      }

    } catch (IOException | BlockStoreException e) {
      log.error("Block store could not be opened", e.getMessage());

      CoreEvents.fireBitcoinNetworkChangedEvent(
        BitcoinNetworkSummary.newNetworkStartupFailed(
          CoreMessageKey.START_NETWORK_CONNECTION_ERROR,
          Optional.of(new Object[]{})
        )
      );
    }

    // Add the wallet to the block chain
    if (WalletManager.INSTANCE.getCurrentWalletSummary().isPresent() && blockChain != null) {
      addWalletToBlockChain(WalletManager.INSTANCE.getCurrentWalletSummary().get().getWallet());
    }
    return blockStoreToReturn;
  }

  /**
   * @return True if the network has started and a peer group is available
   */
  public boolean isStartedOk() {
    return startedOk;
  }

  public void recalculateFastCatchupAndFilter(boolean wait) {

    if (peerGroup != null) {
      ListenableFuture<BloomFilter> bloomFilterFuture = peerGroup.recalculateFastCatchupAndFilter(PeerGroup.FilterRecalculateMode.FORCE_SEND_FOR_REFRESH);

      if (wait) {
        try {
          bloomFilterFuture.get(1, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
          log.error("Bloom filter construction failed with error {}", e.getMessage());
        }

        // Wait one second for the remote peer to process the new BloomFilter
        Uninterruptibles.sleepUninterruptibly(1, TimeUnit.SECONDS);

      }

      log.debug("Status of BloomFilterFuture after 1 second:{}", bloomFilterFuture.isDone());
    }
  }

  /**
   * <p>Download the block chain in a new thread</p>
   */
  private void downloadBlockChainInBackground() {

    getExecutorService().submit(
      new Runnable() {
        @Override
        public void run() {
          Preconditions.checkNotNull(peerGroup, "'peerGroup' must be present");

          // Recalculate the bloom filter before every sync
          log.debug("Recalculating bloom filter ...");
          recalculateFastCatchupAndFilter(true);

          log.debug("Downloading block chain...");

          try {
            log.debug("Starting blockchain download . . .");
            // This method fires events along the way which are dealt with by MultiBitPeerEventListener
            peerGroup.downloadBlockChain();
            log.debug("After peerGroup.downloadBlockChain() called.");
            if (WalletManager.INSTANCE.getCurrentWalletSummary().isPresent()) {
              Wallet currentWallet = WalletManager.INSTANCE.getCurrentWalletSummary().get().getWallet();
              if (currentWallet != null) {
                // Do not reveal balance in logs
                log.trace("Wallet has {} transactions", currentWallet.getTransactions(true).size());
              } else {
                log.debug("There is no current wallet");
              }
            } else {
              log.debug("There is no wallet in the current WalletSummary");
            }
            // As long as the block chain is not being downloaded (perhaps on a Peer with a longer chain)
            // then fire that we are finished
            if (peerEventListener != null && !peerEventListener.isDownloading()) {
              CoreEvents.fireBitcoinNetworkChangedEvent(BitcoinNetworkSummary.newChainDownloadCompleted());
              CoreEvents.fireBitcoinNetworkChangedEvent(BitcoinNetworkSummary.newNetworkPeerCount(peerGroup.numConnectedPeers()));
            }
          } catch (RuntimeException re) {
            log.debug("Blockchain download was interrupted. Error was : '" + re.getMessage() + "'");
          }
        }
      });
  }

  /**
   * Sync the current wallet from the date specified. If Optional.absent() is specified no checkpointing is performed
   * The blockstore is deleted and created anew, checkpointed and then the blockchain is downloaded.
   *
   * @param applicationDataDirectory To enable location of supporting files
   * @param replayDateTime           Optional date time from which to begin using checkpoints (absent means no checkpoints)
   * @param useFastCatchup           True if only block headers from genesis block is required (fast catch up)
   * @param clearMemPool             True if the memory pool should be cleared (e.g. repair wallet scenario)
   */
  @SuppressFBWarnings({"RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE"})
  public void replayWallet(File applicationDataDirectory, Optional<DateTime> replayDateTime, boolean useFastCatchup, boolean clearMemPool) {

    Preconditions.checkNotNull(replayDateTime);
    Preconditions.checkState(WalletManager.INSTANCE.getCurrentWalletSummary().isPresent());
    Preconditions.checkState(!SwingUtilities.isEventDispatchThread(), "Replay should not take place on the EDT");

    try {
      log.debug("Stopping any existing downloads");

      // Stop the peer group if it is running
      stopPeerGroup();

      // Reset the mem pool - this will ensure transactions will be re-downloaded
      if (clearMemPool) {
        TxConfidenceTable memPool = Context.get().getConfidenceTable();
        memPool.reset();
      }

      // Open the block store if it has not been yet with no replay config - this is so that it can be interrogated
      if (blockStore == null) {
        blockStore = openBlockStore(applicationDataDirectory, new ReplayConfig());
      }

      // See if the replayDate is within the current block stores knowledge,
      // if so then we can do a replay from that and do not have to sync back from a checkpoint
      ReplayConfig replayConfig;

      Optional<Stack<StoredBlock>> replayStoredBlockStack = findStoredBlockBefore(replayDateTime);
      if (replayStoredBlockStack.isPresent()) {
        log.debug("Can replay wallet from storedBlock");
        replayConfig = new ReplayConfig(replayStoredBlockStack.get());
      } else {
         if (replayDateTime.isPresent()) {
          // Use the specified replay date
          log.debug("Using the specified replayDateTime {}", replayDateTime);
          replayConfig = new ReplayConfig(replayDateTime.get());
        } else {
          // No replay date nor a stored block
          log.debug("No replay date nor stored block available for replay");
          replayConfig = new ReplayConfig();
        }
      }

      // Close the block store if it is present
      closeBlockstore();

      log.debug(
              "Starting replay of wallet with id '{}' from date '{}'",
              WalletManager.INSTANCE.getCurrentWalletSummary().get().getWalletId(),
              replayDateTime.orNull()
      );

      blockStore = openBlockStore(applicationDataDirectory, replayConfig);
      log.debug("Blockstore is '{}'", blockStore);

      restartNetwork(blockStore, useFastCatchup);

      downloadBlockChainInBackground();

      log.debug("Blockchain download started.");
    } catch (BlockStoreException | IOException | TimeoutException | RuntimeException e) {
      log.debug("Wallet replay was interrupted. Error was : '" + e.getClass().getCanonicalName() + " " + e.getMessage() + e.getCause() == null ? " " : e.getCause().getMessage() + "'");
    }
  }

  /*
   * If a replayDateTime is specified, see if the replayDate is within the current block store's knowledge,
   * if so return the stored block from before the replay date
   * If not, return Optional.absent()
   *
   * We wind back NUMBER_OF_BLOCKS_DELTA_FOR_REPLAY blocks to cater for forks.
   * There has to be a difficulty transition in the stack returned (otherwise adding blocks fails) so if there isn't return Optional.absent()
   *
   */
  private Optional<Stack<StoredBlock>> findStoredBlockBefore(Optional<DateTime> replayDateTime) {
    // No replay datetime specified
    if (!replayDateTime.isPresent()) {
      log.debug("No replay date available");
      return Optional.absent();
    }

    // No open blockStore
    if (blockStore == null) {
      log.debug("No blockstore");
      return Optional.absent();
    }

    try {
      StoredBlock cursor = blockStore.getChainHead();
      if (cursor == null) {
        // No chainHead so cannot search back in time
        log.debug("No chainhead");
        return Optional.absent();
      } else {
        while (cursor != null) {
          if (cursor.getHeader().getTime().before(replayDateTime.get().toDate())) {
            // Found a stored block with time earlier than replay date
            // Copy to a stack the snippet of blockchain from earliest known to just before cursor
            // Check that there is a difficulty transition
            Stack<StoredBlock> storedBlockStack = pushStoredBlocksToCursorAndCheckDifficulty(cursor);
            return Optional.fromNullable(storedBlockStack);
          }

          // Find the previous header (will be null once blockstore is exhausted)
          cursor = cursor.getPrev(blockStore);
        }

        // Could not find stored block before replay date
        log.debug("No stored block available before replay date");

        return Optional.absent();
      }
    } catch (BlockStoreException bse) {
      // Something bad happened accessing the block store so give up search
      return Optional.absent();
    }
  }

  /**
   * Push to a stack all the headers from the earliest known in the block store to NUMBER_OF_BLOCKS_DELTA_FOR_REPLAY prior to the cursor
   * The stack returned is guaranteed to have a difficulty transition
   * @return Stack the snippet of chain from cursor backwards to whatever earliest the block store knows about, or null
   **/
  private Stack<StoredBlock> pushStoredBlocksToCursorAndCheckDifficulty(StoredBlock storedBlock) throws BlockStoreException {
    // Go back some more blocks to cater for possible forks
    for (int i = 0; i < NUMBER_OF_BLOCKS_DELTA_FOR_REPLAY; i++) {
      if (storedBlock == null) {
        // No header available
        log.debug("Not enough blocks in block store so returning null");
        return null;
      }
      storedBlock = storedBlock.getPrev(blockStore);
    }

    boolean foundDifficultyTransition = false;

    Stack<StoredBlock> stack = new Stack<>();

    while (storedBlock != null) {
      log.trace("Pushing block {} at height {}", storedBlock.getHeader().getHashAsString(), storedBlock.getHeight());
      stack.push(storedBlock);
      StoredBlock previousStoredBlock = null;
      try {
        previousStoredBlock = storedBlock.getPrev(blockStore);

        // Difficulty transition worked out as per AbstractBitcoinNetParams#isDifficultyTransitionPoint
        if (previousStoredBlock != null && ((previousStoredBlock.getHeight() + 1) % networkParameters.getInterval()) == 0) {
          foundDifficultyTransition = true;
          log.debug("Found difficulty transition");
        }
      } catch (BlockStoreException bse) {
        log.debug("Failed to get previous block, error was {}", bse.getMessage());
      }

      storedBlock = previousStoredBlock;
    }

    log.debug("Navigated back to beginning of blockstore");

    if (foundDifficultyTransition) {
      // return the chain snippet as it has a difficulty transition
      log.debug("Found a chain snippet for use in replay of length {}", stack.size());
      return stack;
    } else {
      log.debug("No difficulty transition in chain snippet so returning null");

      // No difficulty transition in chain snippet, or not enough stored blocks in the blockstore
      return null;
    }
  }


  /**
   * <p>Send bitcoin</p>
   * <p/>
   * <p>The result of the operation is sent to the CoreEventBus as a TransactionCreationEvent and, if the tx is sent ok, a BitcoinSentEvent</p>
   *
   * @param sendRequestSummary The information required to send bitcoin
   */
  public void send(final SendRequestSummary sendRequestSummary) {
    send(sendRequestSummary, Optional.<PaymentRequestData>absent());
  }

  /**
   * <p>Send bitcoin</p>
   * <p/>
   * <p>The result of the operation is sent to the CoreEventBus as a TransactionCreationEvent and, if the tx is sent ok, a BitcoinSentEvent</p>
   *
   * @param sendRequestSummary         The information required to send bitcoin
   * @param paymentRequestDataOptional Optional BIP70 payment request data
   */
  public void send(final SendRequestSummary sendRequestSummary, final Optional<PaymentRequestData> paymentRequestDataOptional) {
    lastSendRequestSummaryOptional = Optional.absent();
    lastWalletOptional = Optional.absent();

    getExecutorService().submit(
      new Runnable() {
        @Override
        public void run() {
          performSend(sendRequestSummary, paymentRequestDataOptional);
        }

      });
  }

  /**
   * @param sendRequestSummary The information required to send bitcoin
   *
   * @return The send request
   */
  private boolean performSend(SendRequestSummary sendRequestSummary, Optional<PaymentRequestData> paymentRequestDataOptional) {
    log.debug("Starting the send process");

    // Verify the wallet summary
    if (!checkWalletSummary(sendRequestSummary)) {
      log.debug("Wallet summary check fail");
      return false;
    }

    // Get the current wallet
    WalletSummary currentWalletSummary = WalletManager.INSTANCE.getCurrentWalletSummary().get();
    Wallet wallet = currentWalletSummary.getWallet();

    // Check for hard wallet signing
    switch (currentWalletSummary.getWalletType()) {

      case TREZOR_HARD_WALLET:
        // Attempt to sign the transaction using the Trezor / KeepKey wallet
        // This will fire HardwareWalletEvents as the signing progresses which are dealt by the onHardwareWalletEvent method in the SendBitcoinConfirmTrezorPanelView

        // Remember the last sendRequestSummary and Wallet for callback
        lastSendRequestSummaryOptional = Optional.of(sendRequestSummary);
        lastWalletOptional = Optional.of(wallet);

        if (!signUsingHardwareWallet(sendRequestSummary, wallet)) {
          return false;
        }
        break;
      default:
        // Regular soft wallet signing
        // Derive and append the key parameter to unlock the wallet
        if (!appendKeyParameter(sendRequestSummary, wallet)) {
          return false;
        }

        // Attempt to sign the transaction directly
        if (!signDirectly(sendRequestSummary, wallet)) {
          return false;
        }

        performCommitAndBroadcast(sendRequestSummary, wallet, paymentRequestDataOptional);
        break;
    }

    return true;

  }

  public void commitAndBroadcast(final SendRequestSummary sendRequestSummary, final Wallet wallet, final Optional<PaymentRequestData> paymentRequestDataOptional) {
    getExecutorService().submit(
      new Runnable() {
        @Override
        public void run() {
          performCommitAndBroadcast(sendRequestSummary, wallet, paymentRequestDataOptional);
        }

      });
  }

  private boolean performCommitAndBroadcast(SendRequestSummary sendRequestSummary, Wallet wallet, Optional<PaymentRequestData> paymentRequestDataOptional) {

    // Attempt to commit the signed transaction to the wallet
    if (!commit(sendRequestSummary, wallet)) {
      return false;
    }

    // Link the transaction to the payment request by UUID
    if (paymentRequestDataOptional.isPresent() && sendRequestSummary.getSendRequest().isPresent()) {
      WalletService walletService = CoreServices.getOrCreateWalletService(WalletManager.INSTANCE.getCurrentWalletSummary().get().getWalletId());

      // Set the Transaction hash and re-add to WalletService (replacing any pre-existing paymentRequestData with the same UUID)
      PaymentRequestData paymentRequestData = paymentRequestDataOptional.get();
      Sha256Hash txHash = sendRequestSummary.getSendRequest().get().tx.getHash();
      paymentRequestData.setTransactionHash(Optional.of(txHash));
      walletService.addPaymentRequestData(paymentRequestData);
      log.debug("Linking the payment request with UUID {} to the transaction with hash {}", paymentRequestData.getUuid(), txHash);
    }

    // Attempt to broadcast it
    if (!broadcast(sendRequestSummary)) {
      return false;
    }

    // Must be OK to be here
    log.debug("Forgetting last SendRequest and Wallet ");
    lastSendRequestSummaryOptional = Optional.absent();
    lastWalletOptional = Optional.absent();

    return true;
  }

  /**
   * @param sendRequestSummary The information required to send bitcoin
   * @param wallet             The wallet
   *
   * @return True if the derivation process was successful
   */
  private boolean appendKeyParameter(SendRequestSummary sendRequestSummary, Wallet wallet) {

    // Wrap it all up in a try-catch to ensure we fire a failure event
    try {

      log.debug("Deriving key parameter");

      Preconditions.checkNotNull(wallet, "'wallet' must be present");

      if (wallet.getKeyCrypter() == null) {
        throw new IllegalStateException("No key crypter in wallet when one is expected.");
      }
      sendRequestSummary.setKeyParameter(wallet.getKeyCrypter().deriveKey(sendRequestSummary.getPassword()));

      return true;

    } catch (IllegalStateException | KeyCrypterException e) {

      log.error("Could not convert password to AES key", e);

      // Declare the transaction creation a failure
      CoreEvents.fireTransactionCreationEvent(
        new TransactionCreationEvent(
          null,
          sendRequestSummary.getTotalAmount(),
          Optional.<FiatPayment>absent(),
          Optional.<Coin>absent(),
          sendRequestSummary.getDestinationAddress(),
          sendRequestSummary.getChangeAddress(),
          false,
          CoreMessageKey.THE_ERROR_WAS.getKey(),
          new String[]{e.getClass().getCanonicalName() + " " + e.getMessage()},
          sendRequestSummary.getNotes(),
          false));
    }

    // Must have failed to be here
    return false;
  }

  /**
   * Handle missing wallet summary
   *
   * @param sendRequestSummary The information required to send bitcoin
   *
   * @return True if the wallet summary is present
   */
  private boolean checkWalletSummary(SendRequestSummary sendRequestSummary) {
    if (!WalletManager.INSTANCE.getCurrentWalletSummary().isPresent()) {

      // Declare the transaction creation a failure - no wallet
      CoreEvents.fireTransactionCreationEvent(
        new TransactionCreationEvent(
          null,
          sendRequestSummary.getTotalAmount(),
          Optional.<FiatPayment>absent(),
          Optional.<Coin>absent(),
          sendRequestSummary.getDestinationAddress(),
          sendRequestSummary.getChangeAddress(),
          false,
          CoreMessageKey.NO_ACTIVE_WALLET.getKey(),
          new String[]{""},
          sendRequestSummary.getNotes(),
          false
        ));

      // Prevent fall-through to success
      return false;
    }

    // Must be OK to be here
    return true;
  }

  /**
   * <p>Build and append a Bitcoinj send request using the summary. This also calculates the final transaction fees and fiat equivalents</p>
   *
   * @param sendRequestSummary The information required to send bitcoin
   */
  private boolean appendSendRequest(SendRequestSummary sendRequestSummary) {
    log.debug("Appending send request based on: {}", sendRequestSummary);

    try {

      // Determine if a SendRequest is present
      final Wallet.SendRequest sendRequest;
      if (sendRequestSummary.getSendRequest().isPresent()) {
        // Use the existing SendRequest
        sendRequest = sendRequestSummary.getSendRequest().get();

      } else {
        // No SendRequest so build one from the information in the summary
        sendRequest = Wallet.SendRequest.to(
          sendRequestSummary.getDestinationAddress(),
          sendRequestSummary.getAmount()
        );

        // Ensure the transactionConfidence is in the Context transaction confidence table
        TransactionConfidence confidence = sendRequest.tx.getConfidence(CoreServices.getContext());
        log.debug("Confidence identity: {}", System.identityHashCode(confidence));
        log.debug("Transaction with hash {}", sendRequest.tx.getHashAsString());
        log.debug("CoreServices.getContext(): {}", CoreServices.getContext());
        log.debug("CoreServices.getContext().getConfidenceTable(): {}", CoreServices.getContext().getConfidenceTable());
        log.debug("The CoreServices Context confidence has identity: {}", System.identityHashCode(CoreServices.getContext().getConfidenceTable().get(sendRequest.tx.getHash())));

        if (sendRequestSummary.getKeyParameter().isPresent()) {
          sendRequest.aesKey = sendRequestSummary.getKeyParameter().get();
        }
        sendRequest.changeAddress = sendRequestSummary.getChangeAddress();

        // Require empty wallet to ensure that all funds are included
        sendRequest.emptyWallet = sendRequestSummary.isEmptyWallet();
      }
      sendRequest.fee = Coin.ZERO;
      sendRequest.feePerKb = sendRequestSummary.getFeePerKB();

      // Append the Bitcoinj send request to the summary
      sendRequestSummary.setSendRequest(sendRequest);

    } catch (IllegalStateException e) {

      log.error("Could not process the SendRequest", e);

      // Declare the transaction creation a failure
      CoreEvents.fireTransactionCreationEvent(
        new TransactionCreationEvent(
          null,
          sendRequestSummary.getTotalAmount(),
          Optional.<FiatPayment>absent(),
          Optional.<Coin>absent(),
          sendRequestSummary.getDestinationAddress(),
          sendRequestSummary.getChangeAddress(),
          false,
          CoreMessageKey.THE_ERROR_WAS.getKey(),
          new String[]{e.getClass().getCanonicalName() + " " + e.getMessage()},
          sendRequestSummary.getNotes(),
          false
        ));

      // We cannot proceed to broadcast
      return false;

    }

    // Must be OK to be here
    return true;

  }

  /**
   * @param sendRequestSummary The information required to prepare a transaction for sending (this is everything except the credentials)
   *                           This prepares the transaction but does not sign it
   *
   * @return True if the operation was successful
   */
  public boolean prepareTransaction(SendRequestSummary sendRequestSummary) {
    log.debug("Starting the prepare transaction process");

    // Verify the wallet summary
    if (!checkWalletSummary(sendRequestSummary)) {
      log.debug("Wallet summary check fail");
      return false;
    }

    // Get the current wallet
    Wallet wallet = WalletManager.INSTANCE.getCurrentWalletSummary().get().getWallet();

    if (!sendRequestSummary.isEmptyWallet()) {
      // This is a standard send so proceed as normal
      log.debug("Treating as standard send");

      // Attempt to build and append the send request as if it were standard
      if (!appendSendRequest(sendRequestSummary)) {
        return false;
      }

      // Attempt to completeWithoutSigning it (will fail if insufficient funds)
      if (!completeWithoutSigning(sendRequestSummary, wallet)) {
        return false;
      }

      // Set the fiat equivalent amount into the sendRequestSummary - this will take into account the transaction fee and client fee
      setFiatEquivalent(sendRequestSummary);
    } else {
      // This is an empty wallet so perform a dry run to get fees
      log.debug("Treating as an 'empty wallet' send");

      // Attempt to build and append the send request as if it were standard
      if (!appendSendRequest(sendRequestSummary)) {
        return false;
      }

      // Attempt to completeWithoutSigning it
      if (!completeWithoutSigning(sendRequestSummary, wallet)) {
        return false;
      }

      // Update the SendRequestSummary to ensure it is not an "empty wallet" and has the adjusted recipient amount
      sendRequestSummary.setEmptyWallet(false);

      // Attempt to build and append the send request as if it were standard
      // (This may now add on a client fee transaction output and also adjust the size of the output amounts)
      if (!appendSendRequest(sendRequestSummary)) {
        return false;
      }

      // Attempt to completeWithoutSigning it - we need to remove the inputs first as the complete will put all possible inputs back on the tx
      sendRequestSummary.getSendRequest().get().tx.clearInputs();
      if (!completeWithoutSigning(sendRequestSummary, wallet)) {
        return false;
      }

      // Set the fiat equivalent amount into the emptyWalletSendRequestSummary - this will take into account the transaction fee and client fee
      setFiatEquivalent(sendRequestSummary);
    }

    // Must be OK to be here
    log.debug("Prepare transaction has completed");

    return true;
  }

  /**
   * Work out the fiat equivalent of the total bitcoin amount being spent.
   * This includes the transaction fee and the client fee.
   * The exchange rate used is already set into the SendRequestSummary.fiatPayment by the UI models
   *
   * @param sendRequestSummary Send information, including transaction fee, client fee and exchange rate information
   *
   * @return boolean true if operation was successful, false otherwise
   */
  private boolean setFiatEquivalent(SendRequestSummary sendRequestSummary) {
    Preconditions.checkState(sendRequestSummary.getSendRequest().isPresent(), "No send request is present");
    Preconditions.checkNotNull(sendRequestSummary.getSendRequest().get().tx);

    log.debug("sendRequestSummary = " + sendRequestSummary.toString());

    try {
      Coin totalAmountIncludingTransactionAndClientFee = Coin.ZERO;

      // Loop over all the tx outputs, adding up everything but the change address
      log.debug("Calculating the fiat equivalent for transaction being sent");
      for (TransactionOutput transactionOutput : sendRequestSummary.getSendRequest().get().tx.getOutputs()) {
        try {
          log.debug("Examining tx output {} ", transactionOutput.toString());

          Script script = transactionOutput.getScriptPubKey();
          // Calculate a TO address if possible
          if (script.isSentToAddress() || script.isPayToScriptHash() || script.isSentToRawPubKey()) {
            Address toAddress = transactionOutput.getScriptPubKey().getToAddress(networkParameters);
            if (!toAddress.equals(sendRequestSummary.getChangeAddress())) {
              // Add to the running total
              totalAmountIncludingTransactionAndClientFee = totalAmountIncludingTransactionAndClientFee.add(transactionOutput.getValue());
              log.debug("Adding a transaction output amount, total bitcoin is now {}", totalAmountIncludingTransactionAndClientFee.toString());
            } else {
              log.debug("Skipping a transaction output as it is the change address");
            }
          } else {
            log.debug("Cannot generate a To address (because it is not defined) for  transactionOutput {}", transactionOutput.getHash().toString());
          }
        } catch (ScriptException se) {
          log.debug("Cannot cast script to Address for transactionOutput: {}", transactionOutput.getHash().toString());
        }
      }

      // Now add in the transaction fee
      totalAmountIncludingTransactionAndClientFee = totalAmountIncludingTransactionAndClientFee.add(sendRequestSummary.getSendRequest().get().fee);

      // Sends are negative
      totalAmountIncludingTransactionAndClientFee = totalAmountIncludingTransactionAndClientFee.negate();

      log.debug("Added the transaction fee, bitcoin total is now {}", totalAmountIncludingTransactionAndClientFee.toString());

      // Apply the exchange rate
      BigDecimal localAmount;
      if (sendRequestSummary.getFiatPayment().isPresent() && sendRequestSummary.getFiatPayment().get().getRate().isPresent()
        && sendRequestSummary.getFiatPayment().get().getRate() != null) {
        localAmount = Coins.toLocalAmount(totalAmountIncludingTransactionAndClientFee, new BigDecimal(sendRequestSummary.getFiatPayment().get().getRate().get()));
        sendRequestSummary.getFiatPayment().get().setAmount(Optional.of(localAmount));
      } else {
        localAmount = BigDecimal.ZERO;
      }
      log.debug("Total transaction bitcoin amount = {}, calculated fiat amount {}", totalAmountIncludingTransactionAndClientFee.toString(), localAmount.toString());
      return true;
    } catch (ScriptException e) {
      log.error("Could not process the fiat amount", e);
      return false;
    }
  }

  /**
   * Complete the transaction to work out the fees but DO NOT sign it yet
   *
   * @param sendRequestSummary The information required to send bitcoin
   * @param wallet             The wallet
   *
   * @return True if the completeWithoutSigning and signDirectly operations were successful
   */
  private boolean completeWithoutSigning(SendRequestSummary sendRequestSummary, Wallet wallet) {

    log.debug("Completing send request...");

    Wallet.SendRequest sendRequest = sendRequestSummary.getSendRequest().get();

    try {

      // Complete it (works out fee) but DO NOT sign it
      sendRequest.signInputs = false;
      wallet.completeTx(sendRequest);

    } catch (Exception e) {
      log.error("Could not complete the transaction without signing, error: {}", e.getClass().getCanonicalName() + " " + e.getMessage());

      String transactionId = sendRequest.tx != null ? sendRequest.tx.getHashAsString() : "?";

      String message = e.getMessage();
      if (message == null) {
        message = "Transaction cannot be completed at this time - unconfirmed?";
      }

      Coin totalAmount = sendRequestSummary.getTotalAmount();

      // Fire a failed transaction creation event
      CoreEvents.fireTransactionCreationEvent(
        new TransactionCreationEvent(
          transactionId,
          totalAmount,
          Optional.<FiatPayment>absent(),
          Optional.<Coin>absent(),
          sendRequestSummary.getDestinationAddress(),
          sendRequestSummary.getChangeAddress(),
          false,
          CoreMessageKey.THE_ERROR_WAS.getKey(),
          new String[]{message},
          sendRequestSummary.getNotes(),
          false));

      // We cannot proceed to signing
      return false;
    }

    // Must be OK to be here
    return true;
  }

  /**
   * <p>Sign the transaction using a hardware wallet service call</p>
   *
   * @param sendRequestSummary The information required to send bitcoin
   * @param wallet             The wallet
   *
   * @return True if the signDirectly operation was successful
   */
  private boolean signUsingHardwareWallet(SendRequestSummary sendRequestSummary, Wallet wallet) {

    log.debug("Signing the send request using a hardware wallet");

    Wallet.SendRequest sendRequest = sendRequestSummary.getSendRequest().get();

    Optional<HardwareWalletService> hardwareWalletService = CoreServices.getCurrentHardwareWalletService();

    // Check if there is a wallet present
    if (hardwareWalletService.isPresent()) {
      try {

        // Provide a map between the input indices and the HD path of receiving addresses used
        Map<Integer, ImmutableList<ChildNumber>> receivingAddressPathMap = buildReceivingAddressPathMap(
          sendRequest.tx,
          wallet
        );

        Map<Address, ImmutableList<ChildNumber>> changeAddressPathMap = buildChangeAddressPathMap(
          sendRequest.tx,
          wallet,
          sendRequestSummary.getDestinationAddress()
        );


        // Shuffle the outputs to obfuscate change and payment addresses
        sendRequest.tx.shuffleOutputs();

        // Sign the transaction using the Trezor device
        hardwareWalletService.get().signTx(sendRequest.tx, receivingAddressPathMap, changeAddressPathMap);

        // Must be ok to reach here
        return true;
      } catch (Exception e) {
        log.error("Failed to sign using Trezor", e);
        return false;
      }
    } else {
      log.warn("HardwareWalletService not present so cannot sign transaction");
      return false;
    }
  }

  /**
   * Sign the transaction directly
   *
   * @param sendRequestSummary The information required to send bitcoin
   * @param wallet             The wallet
   *
   * @return True if the signDirectly operation was successful
   */
  private boolean signDirectly(SendRequestSummary sendRequestSummary, Wallet wallet) {

    log.debug("Signing the send request directly...");

    Wallet.SendRequest sendRequest = sendRequestSummary.getSendRequest().get();

    try {
      // Ensure the AES key for decrypting the keys is present in the sendRequest
      if (wallet.getKeyCrypter() == null) {
        throw new IllegalStateException("Should not have an unencrypted wallet");
      }
      sendRequest.aesKey = wallet.getKeyCrypter().deriveKey(sendRequestSummary.getPassword());

      // Shuffle the outputs to obfuscate change and payment addresses
      sendRequest.tx.shuffleOutputs();

      // Sign the transaction
      sendRequest.signInputs = true;
      log.debug("sendRequest just before signing: {} ", sendRequest);
      wallet.signTransaction(sendRequest);

      // Check the signatures are canonical - non-canonical signatures are not relayed
      for (TransactionInput txInput : sendRequest.tx.getInputs()) {
        byte[] signature = txInput.getScriptSig().getChunks().get(0).data;
        if (signature != null) {
          log.debug(
            "Is signature canonical test result '{}' for txInput '{}', signature '{}'",
            TransactionSignature.isEncodingCanonical(signature),
            txInput.toString(),
            Utils.HEX.encode(signature));
        }
      }
    } catch (Exception e) {

      log.error("Could not sign the transaction", e.getMessage());

      String transactionId = sendRequest.tx != null ? sendRequest.tx.getHashAsString() : "?";

      // Fire a failed transaction creation event
      CoreEvents.fireTransactionCreationEvent(
        new TransactionCreationEvent(
          transactionId,
          sendRequestSummary.getTotalAmount(),
          Optional.<FiatPayment>absent(),
          Optional.<Coin>absent(),
          sendRequestSummary.getDestinationAddress(),
          sendRequestSummary.getChangeAddress(),
          false,
          CoreMessageKey.THE_ERROR_WAS.getKey(),
          new String[]{e.getMessage()},
          sendRequestSummary.getNotes(),
          false));

      // We cannot proceed to commit
      return false;
    }

    // Must be OK to be here
    return true;
  }

  /**
   * Commit the (signed) transaction to the wallet
   *
   * @param sendRequestSummary The information required to send bitcoin
   * @param wallet             The wallet
   *
   * @return True if the commit operation was successful
   */
  private boolean commit(SendRequestSummary sendRequestSummary, Wallet wallet) {

    log.debug("Committing send request...");

    Wallet.SendRequest sendRequest = sendRequestSummary.getSendRequest().get();

    try {
      // Ensure the tx source is set to SELF as this is a self generated tx
      // Note that the getConfidence automatically creates one if it is null
      sendRequest.tx.getConfidence().setSource(TransactionConfidence.Source.SELF);
      log.debug("Marking source as self for tx hash {}, identity {}", sendRequest.tx.getHashAsString(), System.identityHashCode(sendRequest.tx));

      // Commit to the wallet (informs the wallet of the transaction)
      if (wallet.getTransaction(sendRequest.tx.getHash()) == null) {
        log.debug("Committing transaction with hash {} to wallet", sendRequest.tx.getHashAsString());
        wallet.commitTx(sendRequest.tx);
      } else {
        log.debug("Not committing tx with hash '{}' because tx is already present in wallet", sendRequest.tx.getHashAsString());
      }

      // Fire a successful transaction creation event (not yet broadcast)
      CoreEvents.fireTransactionCreationEvent(
        new TransactionCreationEvent(
          sendRequest.tx.getHashAsString(),
          sendRequestSummary.getTotalAmount(),
          sendRequestSummary.getFiatPayment(),
          Optional.of(sendRequest.fee) /* the actual mining fee paid */,
          sendRequestSummary.getDestinationAddress(),
          sendRequestSummary.getChangeAddress(),
          true,
          null,
          null,
          sendRequestSummary.getNotes(),
          true
        ));
    } catch (Exception e) {
      log.error("Could not commit the transaction", e);

      String transactionId = sendRequest.tx != null ? sendRequest.tx.getHashAsString() : "?";

      // Fire a failed transaction creation event
      CoreEvents.fireTransactionCreationEvent(
        new TransactionCreationEvent(
          transactionId,
          sendRequestSummary.getTotalAmount(),
          Optional.<FiatPayment>absent(),
          Optional.<Coin>absent(),
          sendRequestSummary.getDestinationAddress(),
          sendRequestSummary.getChangeAddress(),
          false,
          CoreMessageKey.THE_ERROR_WAS.getKey(),
          new String[]{e.getMessage()},
          sendRequestSummary.getNotes(),
          false));

      // We cannot proceed to broadcast
      return false;
    }

    // Must be OK to be here
    return true;
  }

  /**
   * <p>Attempt to broadcast the transaction in the Bitcoinj send request</p>
   *
   * @param sendRequestSummary The information required to send bitcoin
   *
   * @return True if the broadcast operation was successful
   */
  private boolean broadcast(final SendRequestSummary sendRequestSummary) {

    log.debug("The CoreServices context has identity: {}", System.identityHashCode(CoreServices.getContext()));
    log.debug("The context has identity: {}", System.identityHashCode(Context.get()));

    final Wallet.SendRequest sendRequest = sendRequestSummary.getSendRequest().get();

    log.debug("Attempting to broadcast transaction with hash {} and identity {}", sendRequest.tx.getHashAsString(), System.identityHashCode(sendRequest.tx));

    try {

      // Ping the peers to check the Bitcoin network connection
      if (!pingPeers()) {
        // Declare the send a failure
        CoreEvents.fireBitcoinSentEvent(
          new BitcoinSentEvent(
            Optional.<Transaction>absent(),
            sendRequestSummary.getDestinationAddress(),
            sendRequestSummary.getTotalAmount(),
            sendRequestSummary.getChangeAddress(),
            Optional.<Coin>absent(),
            false,
            CoreMessageKey.COULD_NOT_CONNECT_TO_BITCOIN_NETWORK,
            new String[]{"Could not reach any Bitcoin nodes"}
          ));

        // Prevent a fall-through to success
        return false;
      }

      // Declare the send broadcast is in progress
      CoreEvents.fireBitcoinSendingEvent(
        new BitcoinSendingEvent(
          sendRequestSummary.getDestinationAddress(),
          sendRequestSummary.getTotalAmount(),
          sendRequestSummary.getChangeAddress(),
          Optional.of(sendRequest.fee)
        ));

      // Receive it in the wallet
      if (WalletManager.INSTANCE.getCurrentWalletSummary().isPresent()) {
        try {
          WalletManager.INSTANCE.getCurrentWalletSummary().get().getWallet().receivePending(sendRequest.tx, null);
        } catch (VerificationException e) {
          log.error("Verification exception for tx:\n{}\n", sendRequest.tx);
          throw new RuntimeException(e);
        }
      }

      // Work out some final values to keep them out of the broadcast loop
      final boolean fireTransactionSeen = WalletManager.INSTANCE.getCurrentWalletSummary().isPresent();
      final Optional<Coin> valueOptional;
      if (fireTransactionSeen) {
        valueOptional = Optional.of(sendRequest.tx.getValue(WalletManager.INSTANCE.getCurrentWalletSummary().get().getWallet()));
      } else {
        valueOptional = Optional.absent();
      }

      // Broadcast to network
      final TransactionBroadcast transactionBroadcast = peerGroup.broadcastTransaction(sendRequest.tx);
      ListenableFuture<Transaction> transactionFuture = transactionBroadcast.future();

      log.debug("Attaching progress callbacks to transactionBroadcast {} with tx {}", System.identityHashCode(transactionBroadcast), sendRequest.tx.getHashAsString());

      transactionBroadcast.setProgressCallback(
        new TransactionBroadcast.ProgressCallback() {
          @Override
          public void onBroadcastProgress(double progress) {
            log.debug("Tx {}, progress is now {}", sendRequest.tx, progress);
            if (fireTransactionSeen) {
              CoreEvents.fireBitcoinSendProgressEvent(new BitcoinSendProgressEvent(sendRequest.tx, progress));
              CoreEvents.fireTransactionSeenEvent(new TransactionSeenEvent(sendRequest.tx, valueOptional.get()));
            }
          }
        });

      Futures.addCallback(
        transactionFuture, new FutureCallback<Transaction>() {
          @Override
          public void onSuccess(Transaction transaction) {
            log.info("Future says transaction '{}' has broadcast successfully", transaction.getHashAsString());

            // Declare the send a success
            CoreEvents.fireBitcoinSentEvent(
              new BitcoinSentEvent(
                Optional.of(transaction),
                sendRequestSummary.getDestinationAddress(),
                sendRequestSummary.getTotalAmount(),
                sendRequestSummary.getChangeAddress(),
                Optional.of(sendRequest.fee),
                true,
                CoreMessageKey.BITCOIN_SENT_OK,
                null
              ));

            CoreEvents.fireBitcoinSendProgressEvent(new BitcoinSendProgressEvent(sendRequest.tx, 1.0));
          }

          @Override
          public void onFailure(Throwable throwable) {
            // This can't happen with the current code, but just in case one day that changes ...
            log.error("Future says transaction has NOT broadcast successfully. Error: '{}'", throwable);

            // Declare the send a failure
            // TODO Add i18n support for "No message" if required
            CoreEvents.fireBitcoinSentEvent(
              new BitcoinSentEvent(
                Optional.<Transaction>absent(), sendRequestSummary.getDestinationAddress(), sendRequestSummary.getTotalAmount(),
                sendRequestSummary.getChangeAddress(),
                Optional.<Coin>absent(),
                false,
                CoreMessageKey.THE_ERROR_WAS,
                new String[]{throwable == null ? "No message" : throwable.getMessage()}
              ));

          }
        });

      log.debug("Initiated broadcast of transaction: '{}'", Utils.HEX.encode(sendRequest.tx.bitcoinSerialize()));

      transactionFuture.get();

      log.debug("Get of future completed");

    } catch (RuntimeException | ExecutionException | InterruptedException e) {

      log.error("Could not broadcast the transaction", e);

      // Declare the send a failure
      CoreEvents.fireBitcoinSentEvent(
        new BitcoinSentEvent(
          Optional.<Transaction>absent(), sendRequestSummary.getDestinationAddress(), sendRequestSummary.getTotalAmount(),
          sendRequestSummary.getChangeAddress(),
          Optional.<Coin>absent(),
          false,
          CoreMessageKey.THE_ERROR_WAS,
          new String[]{e.getMessage()}
        ));

      // Prevent a fall-through to success
      return false;
    }

    // Must be OK to be here
    return true;

  }

  /**
   * @param unsignedTx The unsigned transaction (expect OP_0 in place of signatures)
   * @param wallet     The wallet
   *
   * @return The receiving address path map linking the tx input index to a deterministic path
   */
  private Map<Integer, ImmutableList<ChildNumber>> buildReceivingAddressPathMap(Transaction unsignedTx, Wallet wallet) {

    log.debug("Building Receiving address path map for transaction {}", unsignedTx);

    Map<Integer, ImmutableList<ChildNumber>> receivingAddressPathMap = Maps.newHashMap();

    // Examine the Tx inputs to determine receiving addresses in use
    for (int i = 0; i < unsignedTx.getInputs().size(); i++) {
      TransactionInput input = unsignedTx.getInput(i);

      // Get input script from the connected transaction output script
      Script script = input.getScriptSig();
      TransactionOutput connectedTransactionOutput = input.getConnectedOutput();
      log.debug("Connected transaction output {}", connectedTransactionOutput);
      if (connectedTransactionOutput != null) {
        byte[] pubKeyHash = connectedTransactionOutput.getScriptPubKey().getPubKeyHash();
        log.debug("Connected transaction pubKeyHash {}", Utils.HEX.encode(pubKeyHash));
        DeterministicKey keyFromPubKey = wallet.getActiveKeychain().findKeyFromPubHash(pubKeyHash);
        Preconditions.checkNotNull(keyFromPubKey, "Could not find deterministic key from given pubKeyHash. Input script index: " + i);

        receivingAddressPathMap.put(i, keyFromPubKey.getPath());
      } else {
        log.debug("Could not parse tx input script '{}'", script.toString());
      }

    }

    return receivingAddressPathMap;
  }

  /**
   * @param unsignedTx The unsigned transaction (expect OP_0 in place of signatures)
   * @param wallet     The wallet
   *
   * @return The receiving address path map linking the tx input index to a deterministic path
   */
  private Map<Address, ImmutableList<ChildNumber>> buildChangeAddressPathMap(Transaction unsignedTx, Wallet wallet, Address destinationAddress) {

    Map<Address, ImmutableList<ChildNumber>> changeAddressPathMap = Maps.newHashMap();

    DeterministicKeyChain activeKeyChain = wallet.getActiveKeychain();

    for (int i = 0; i < unsignedTx.getOutputs().size(); i++) {

      TransactionOutput output = unsignedTx.getOutput(i);

      Optional<DeterministicKey> key = Optional.absent();
      Optional<Address> address = Optional.absent();

      // Analyse the output script
      Script script = output.getScriptPubKey();
      if (script.isSentToRawPubKey()) {

        // Use the raw public key
        byte[] pubkey = script.getPubKey();
        if (wallet.isPubKeyMine(pubkey)) {
          key = Optional.fromNullable(activeKeyChain.findKeyFromPubKey(pubkey));
          ECKey ecKey = ECKey.fromPublicOnly(pubkey);
          address = Optional.fromNullable(ecKey.toAddress(MainNetParams.get()));
        }

      } else if (script.isPayToScriptHash() && wallet.isPayToScriptHashMine(script.getPubKeyHash())) {

        // Extract the public key hash from the script
        byte[] pubkeyHash = script.getPubKeyHash();
        key = Optional.fromNullable(activeKeyChain.findKeyFromPubHash(pubkeyHash));
        address = Optional.fromNullable(new Address(MainNetParams.get(), pubkeyHash));
      } else {

        // Use the public key hash
        byte[] pubkeyHash = script.getPubKeyHash();
        if (wallet.isPubKeyHashMine(pubkeyHash)) {
          key = Optional.fromNullable(activeKeyChain.findKeyFromPubHash(pubkeyHash));
          address = Optional.fromNullable(new Address(MainNetParams.get(), pubkeyHash));
        }
      }

      if (key.isPresent() && address.isPresent()) {

        // Found an address we own
        if (!address.get().equals(destinationAddress)) {
          changeAddressPathMap.put(address.get(), key.get().getPath());
        }
      }

    }
      return changeAddressPathMap;
    }



  /**
   * <p>Create a new peer group</p>
   *
   * @param wallet         the wallet to add to the peer group after construction
   * @param useFastCatchup True if only block headers from genesis block is required
   */
  private void createNewPeerGroup(Wallet wallet, boolean useFastCatchup) throws TimeoutException {
    String[] dnsSeeds = new String[]{
                     /* "seed.bitcoin.sipa.be",        // Pieter Wuille - not reachable */
      "dnsseed.internal",            // Internal Intranet DNS seed for environments without direct internet access.
      "dnsseed.bluematt.me",         // Matt Corallo
      "dnsseed.bitcoin.dashjr.org",  // Luke Dashjr
      "seed.bitcoinstats.com",       // Chris Decker
      "seed.bitnodes.io",            // Addy Yeow
    };
    log.info("Creating new DNS peer group for '{}'", networkParameters);
    peerGroup = new PeerGroup(networkParameters, blockChain);
    peerGroup.addPeerDiscovery(new DnsDiscovery(dnsSeeds, networkParameters));
    peerGroup.setConnectTimeoutMillis(CONNECTION_TIMEOUT);


    peerGroup.setUserAgent(
      InstallationManager.MBHD_APP_NAME,
      Configurations.currentConfiguration.getCurrentVersion());

    peerGroup.setMaxConnections(MAXIMUM_NUMBER_OF_PEERS);
    peerGroup.setUseLocalhostPeerWhenPossible(true);

    peerEventListener = new MultiBitPeerEventListener();
    peerGroup.addEventListener(peerEventListener);

    addWalletToPeerGroup(wallet, useFastCatchup);
  }

  public void addWalletToPeerGroup(Wallet wallet, boolean useFastCatchup) {
    if (peerGroup != null && wallet != null) {
      log.trace("Adding wallet {} to peerGroup {}", wallet, peerGroup);
      peerGroup.addWallet(wallet);
      if (useFastCatchup) {
        peerGroup.setFastCatchupTimeSecs(wallet.getEarliestKeyCreationTime());
      } else {
        peerGroup.setFastCatchupTimeSecs(0); // Download full blocks always
      }
      recalculateFastCatchupAndFilter(false);
    } else {
      log.debug("Could not add wallet to peerGroup - one or more is missing");
    }
  }

  public void addWalletToBlockChain(Wallet wallet) {
    if (blockChain != null && wallet != null) {
      log.trace("Adding wallet {} to blockChain {}", wallet, blockChain);
      blockChain.addWallet(wallet);
    } else {
      log.debug("Could not add wallet to blockChain - one or more is missing");
    }
  }

  /**
   * Get the next available change address
   *
   * @return changeAddress The next change address as an Address
   */
  public Address getNextChangeAddress() {

    Preconditions.checkState(WalletManager.INSTANCE.getCurrentWalletSummary().isPresent());
    Preconditions.checkNotNull(WalletManager.INSTANCE.getCurrentWalletSummary().get().getWallet());
    Preconditions.checkState(WalletManager.INSTANCE.getCurrentWalletSummary().get().getWallet().getKeychainSize() > 0);

    Wallet wallet = WalletManager.INSTANCE.getCurrentWalletSummary().get().getWallet();

    return wallet.freshKey(KeyChain.KeyPurpose.CHANGE).toAddress(networkParameters);
  }

  /**
   * Ping all connected peers to see if there is an active network connection
   *
   * @return true is two or more peers respond to the ping
   */
  public boolean pingPeers() {
    List<Peer> connectedPeers = peerGroup.getConnectedPeers();
    int numberOfSuccessfulPings = 0;
    if (connectedPeers != null) {
      for (Peer peer : connectedPeers) {

        log.debug("Ping: {}", peer.getAddress().toString());

        try {
          ListenableFuture<Long> result = peer.ping();
          result.get(4, TimeUnit.SECONDS);
          numberOfSuccessfulPings++;
          if (numberOfSuccessfulPings >= NUMBER_OF_PEERS_TO_PING) {
            break;
          }
        } catch (ProtocolException | InterruptedException | ExecutionException | TimeoutException e) {
          log.warn("Peer '" + peer.getAddress().toString() + "' failed ping test. Message was " + e.getMessage());
        }
      }
    }

    return numberOfSuccessfulPings >= 2;
  }

  /**
   * Removes the current wallet from the block chain and closes the block store
   */
  private void closeBlockstore() {
    // Remove the wallet from the block chain before closing the blockstore
    if (WalletManager.INSTANCE.getCurrentWalletSummary().isPresent() && blockChain != null) {
      log.debug("Removing wallet from blockChain...");
      blockChain.removeWallet(WalletManager.INSTANCE.getCurrentWalletSummary().get().getWallet());
      blockChain = null; // need to recreate it when a new blockstore is created
    }

    // Close the blockstore
    if (blockStore != null) {
      try {
        // The blockstore can throw an NPE internally
        log.debug("When the blockstore was closed the height was {}", blockStore.getChainHead() == null ? "unknown" : blockStore.getChainHead().getHeight());
        blockStore.close();
      } catch (BlockStoreException e) {
        log.warn("BlockStoreException: Blockstore was already closed or not closed cleanly: {}", e.getMessage());
      } catch (NullPointerException e) {
        // Internal bug in Bitcoinj
      }
    } else {
      log.debug("blockStore was not present");
    }
    blockStore = null;
  }

  /**
   * <p>Stops the current peer group blocking until completeWithoutSigning</p>
   */
  private void stopPeerGroup() {

    if (peerGroup != null) {
      log.debug("Stopping peerGroup service...");
      peerGroup.removeEventListener(peerEventListener);
      peerEventListener = null;

      // Remove the wallet from the peer group
      if (WalletManager.INSTANCE.getCurrentWalletSummary().isPresent()) {
        peerGroup.removeWallet(WalletManager.INSTANCE.getCurrentWalletSummary().get().getWallet());
      }

      peerGroup.stopAsync();
      log.debug("Service peerGroup stopped");

    } else {
      log.debug("Peer group was not present");
    }
    peerGroup = null;
  }

  /**
   * Restart the network, using the given blockstore
   * THe current wallet is hooked up to the blockchain and new peer group
   *
   * @param blockStore     The blockstore to use for the network connection
   * @param useFastCatchup True if fast catch up from the earliestKeDate is to used
   *
   * @throws BlockStoreException                   If the block store fails
   * @throws IOException                           If the network fails
   * @throws TimeoutException If the Tor connection fails
   */
  private void restartNetwork(BlockStore blockStore, boolean useFastCatchup) throws BlockStoreException, IOException, TimeoutException {

    Preconditions.checkNotNull(blockStore, "'blockStore' must be present");

    stopPeerGroup();

    CoreEvents.fireBitcoinNetworkChangedEvent(BitcoinNetworkSummary.newNetworkNotInitialised());

    log.debug("Creating block chain from blockStore {}...", blockStore);
    blockChain = new BlockChain(networkParameters, blockStore);

    Wallet wallet = null;
    if (WalletManager.INSTANCE.getCurrentWalletSummary().isPresent()) {
      wallet = WalletManager.INSTANCE.getCurrentWalletSummary().get().getWallet();
    }

    if (wallet == null) {
      log.error("No wallet is present to allow restart to occur");
      return;
    }

    blockChain.addWallet(wallet);
    log.debug("Created block chain '{}' with height '{}'", blockChain, blockChain.getBestChainHeight());

    log.debug("Creating peer group with useFastCatchup: {} ...", useFastCatchup);
    createNewPeerGroup(wallet, useFastCatchup);
    log.debug("Created peer group '{}'", peerGroup);

    log.debug("Starting peer group ...");
    peerGroup.startAsync();
    log.debug("Started peer group.");

    startedOk = true;
  }

  /**
   * Calculate the size of the transaction
   *
   * @param transaction The transaction to calculate the size of
   *
   * @return size of the transaction
   */
  private int calculateSizeWithSignatures(Transaction transaction) throws IOException {
    ByteArrayOutputStream byteOutputStream = new ByteArrayOutputStream();
    transaction.bitcoinSerialize(byteOutputStream);

    int unsignedSize = byteOutputStream.size();

    // Add on size of signatures
    return unsignedSize + SIZE_OF_SIGNATURE * transaction.getInputs().size();
  }

  public Optional<SendRequestSummary> getLastSendRequestSummaryOptional() {
    return lastSendRequestSummaryOptional;
  }

  public Optional<Wallet> getLastWalletOptional() {
    return lastWalletOptional;
  }

  public void setLastSendRequestSummaryOptional(Optional<SendRequestSummary> lastSendRequestSummaryOptional) {
    this.lastSendRequestSummaryOptional = lastSendRequestSummaryOptional;
  }

  public void setLastWalletOptional(Optional<Wallet> lastWalletOptional) {
    this.lastWalletOptional = lastWalletOptional;
  }

  public BlockStore getBlockStore() {
    return blockStore;
  }

  public int getNumberOfConnectedPeers() {
    if (peerGroup == null) {
      return 0;
    } else {
      return peerGroup.numConnectedPeers();
    }
  }
}
