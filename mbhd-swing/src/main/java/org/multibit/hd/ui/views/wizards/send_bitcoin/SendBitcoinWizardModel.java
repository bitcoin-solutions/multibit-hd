package org.multibit.hd.ui.views.wizards.send_bitcoin;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.eventbus.Subscribe;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import org.bitcoin.protocols.payments.Protos;
import org.bitcoinj.core.*;
import org.bitcoinj.crypto.TransactionSignature;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.protocols.payments.PaymentProtocol;
import org.bitcoinj.protocols.payments.PaymentProtocolException;
import org.bitcoinj.protocols.payments.PaymentSession;
import org.bitcoinj.uri.BitcoinURI;
import org.multibit.hd.brit.dto.FeeState;
import org.multibit.hd.brit.services.FeeService;
import org.multibit.hd.core.config.BitcoinConfiguration;
import org.multibit.hd.core.config.Configurations;
import org.multibit.hd.core.config.LanguageConfiguration;
import org.multibit.hd.core.dto.*;
import org.multibit.hd.core.events.BitcoinSentEvent;
import org.multibit.hd.core.events.CoreEvents;
import org.multibit.hd.core.events.ExchangeRateChangedEvent;
import org.multibit.hd.core.events.PaymentSentToRequestorEvent;
import org.multibit.hd.core.exchanges.ExchangeKey;
import org.multibit.hd.core.managers.WalletManager;
import org.multibit.hd.core.services.*;
import org.multibit.hd.core.utils.BitcoinSymbol;
import org.multibit.hd.core.utils.Dates;
import org.multibit.hd.hardware.core.HardwareWalletService;
import org.multibit.hd.hardware.core.events.HardwareWalletEvent;
import org.multibit.hd.hardware.core.messages.ButtonRequest;
import org.multibit.hd.hardware.core.utils.TransactionUtils;
import org.multibit.hd.ui.events.view.ViewEvents;
import org.multibit.hd.ui.languages.Formats;
import org.multibit.hd.ui.languages.MessageKey;
import org.multibit.hd.ui.views.ViewKey;
import org.multibit.hd.ui.views.wizards.AbstractHardwareWalletWizardModel;
import org.multibit.hd.ui.views.wizards.WizardButton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;

import static org.multibit.hd.ui.views.wizards.send_bitcoin.SendBitcoinState.*;

/**
 * <p>Model object to provide the following to "send bitcoin wizard":</p>
 * <ul>
 * <li>Storage of panel data</li>
 * <li>State transition management</li>
 * </ul>
 *
 * @since 0.0.1
 */
public class SendBitcoinWizardModel extends AbstractHardwareWalletWizardModel<SendBitcoinState> {

  private static final Logger log = LoggerFactory.getLogger(SendBitcoinWizardModel.class);

  /**
   * The "enter amount" panel model
   */
  private SendBitcoinEnterAmountPanelModel enterAmountPanelModel;

  /**
   * The "confirm" panel model
   */
  private SendBitcoinConfirmPanelModel confirmPanelModel;

  /**
   * The "payment memo" panel model
   */
  private SendBitcoinEnterPaymentMemoPanelModel sendBitcoinEnterPaymentMemoPanelModel;

  /**
   * The "payment ack memo" panel model
   */
  private SendBitcoinShowPaymentACKMemoPanelModel sendBitcoinShowPaymentACKMemoPanelModel;

  /**
   * The current "enter PIN" panel view (might have one each for master public key and cipher key)
   */
  private SendBitcoinEnterPinPanelView enterPinPanelView;

  /**
   * Keep track of which transaction output is being signed
   * Start with -1 to allow for initial increment
   */
  private int txOutputIndex = -1;

  private final Optional<BitcoinURI> bitcoinURI;

  /**
   * The protobuf Payment Request Data containing persistent state for the original BIP 70 Payment Request
   */
  private Optional<PaymentRequestData> paymentRequestData = Optional.absent();

  /**
   * The SendRequestSummary that initially contains all the tx details, and then is signed prior to sending
   */
  private SendRequestSummary sendRequestSummary;
  private SendBitcoinConfirmTrezorPanelView sendBitcoinConfirmTrezorPanelView;

  /**
   * Boolean indicating whether this is processing a BIP70 payment request
   */
  private boolean isBIP70 = false;

  /**
   * The last bitcoin sent event
   */
  private BitcoinSentEvent lastBitcoinSentEvent;


  /**
   * @param state     The state object
   * @param parameter The "send bitcoin" parameter object
   */
  public SendBitcoinWizardModel(SendBitcoinState state, SendBitcoinParameter parameter) {
    super(state);

    this.bitcoinURI = parameter.getBitcoinURI();
    this.paymentRequestData = parameter.getPaymentRequestData();

    isBIP70 = paymentRequestData != null && paymentRequestData.isPresent();
  }

  @Override
  public void showNext() {

    switch (state) {
      case SEND_DISPLAY_PAYMENT_REQUEST:

        // The user has confirmed the payment request so the tx can be prepared
        // If the transaction was prepared OK this returns true, otherwise false
        // If there is insufficient money in the wallet a TransactionCreationEvent
        // with the details will be thrown

        if (prepareTransaction()) {
          state = SEND_CONFIRM_AMOUNT;
        } else {
          // Transaction did not prepare correctly
          state = SEND_REPORT;
        }
        break;
      case SEND_ENTER_AMOUNT:

        // The user has entered the send details so the tx can be prepared
        // If the transaction was prepared OK this returns true, otherwise false
        // If there is insufficient money in the wallet a TransactionCreationEvent
        // with the details will be thrown

        // Check a recipient has been set
        if (!enterAmountPanelModel
                .getEnterRecipientModel()
                .getRecipient().isPresent()) {
          state = SEND_ENTER_AMOUNT;
        } else {
          if (prepareTransaction()) {
            state = SEND_CONFIRM_AMOUNT;
          } else {
            // Transaction did not prepare correctly
            state = SEND_REPORT;
          }
        }

        break;
      case SEND_CONFIRM_AMOUNT:

        // The user has confirmed the send details and pressed the next button
        // For a non-Trezor wallet navigate directly to the send screen
        // Get the current wallet
        Optional<WalletSummary> currentWalletSummary = WalletManager.INSTANCE.getCurrentWalletSummary();
        if (currentWalletSummary.isPresent()) {
          if (WalletType.TREZOR_HARD_WALLET.equals(currentWalletSummary.get().getWalletType())) {
            log.debug("Sending using a Trezor hard wallet");
            state = SEND_CONFIRM_TREZOR;
            sendBitcoin();
          } else {
            log.debug("Not sending from a Trezor hard wallet - send directly");
            sendBitcoin();

            state = SEND_REPORT;
          }
        } else {
          log.debug("No wallet summary - cannot send");
        }

        break;

      case SEND_ENTER_PIN_FROM_CONFIRM_TREZOR:
        // Do nothing
        break;

      case SEND_CONFIRM_TREZOR:
        // Move to report
        state = SEND_REPORT;
        break;

      case SEND_REPORT:
        // BIP 70 payment requests have a BIP70_PAYMENT_MEMO page after the report
        if (isBIP70) {
          state = SEND_BIP70_PAYMENT_MEMO;
        }
        break;
      case SEND_BIP70_PAYMENT_MEMO:
        // BIP 70 payment requests have a BIP70_PAYMENT_ACK_MEMO page after the report
        if (isBIP70) {
          // Send the Payment (with the payment memo) to the merchant
          // Handle the rest of the send
          sendPaymentToMerchant();
          state = SEND_BIP70_PAYMENT_ACK_MEMO;
        }
        break;
      default:
        // Do nothing
    }
  }

  @Override
  public void showPrevious() {
    switch (state) {
      case SEND_ENTER_AMOUNT:
        state = SEND_ENTER_AMOUNT;
        break;
      case SEND_CONFIRM_AMOUNT:
        state = SEND_ENTER_AMOUNT;
        break;
      case SEND_CONFIRM_TREZOR:
        state = SEND_CONFIRM_AMOUNT;
        break;
      default:
        throw new IllegalStateException("Unexpected state:" + state);
    }
  }

  @Override
  public String getPanelName() {
    return state.name();
  }

  /**
   * @return The recipient the user identified
   */
  public Recipient getRecipient() {
    return enterAmountPanelModel
      .getEnterRecipientModel()
      .getRecipient().get();
  }

  /**
   * @return The Bitcoin amount without symbolic multiplier
   */
  public Optional<Coin> getCoinAmount() {
    return enterAmountPanelModel
      .getEnterAmountModel()
      .getCoinAmount();
  }

  /**
   * @return The local amount
   */
  public Optional<BigDecimal> getLocalAmount() {
    return enterAmountPanelModel
      .getEnterAmountModel()
      .getLocalAmount();
  }

  public void setLocalAmount(Optional<BigDecimal> fiatAmount) {
    enterAmountPanelModel.getEnterAmountModel().setLocalAmount(fiatAmount);
  }

  /**
   * @return The credentials the user entered
   */
  public String getPassword() {
    return confirmPanelModel.getPasswordModel().getValue();
  }

  /**
   * @return The notes the user entered
   */
  public String getNotes() {
    return confirmPanelModel.getNotes();
  }

  /**
   * <p>Reduced visibility for panel models only</p>
   *
   * @param enterAmountPanelModel The "enter amount" panel model
   */
  void setEnterAmountPanelModel(SendBitcoinEnterAmountPanelModel enterAmountPanelModel) {
    this.enterAmountPanelModel = enterAmountPanelModel;
  }

  /**
   * <p>Reduced visibility for panel models only</p>
   *
   * @param confirmPanelModel The "confirm" panel model
   */
  void setConfirmPanelModel(SendBitcoinConfirmPanelModel confirmPanelModel) {
    this.confirmPanelModel = confirmPanelModel;
  }

  /**
   * <p>Reduced visibility for panel models only</p>
   *
   * @param sendBitcoinConfirmTrezorPanelView The "confirm Trezor sign" panel view
   */
  void setSendBitcoinConfirmTrezorPanelView(SendBitcoinConfirmTrezorPanelView sendBitcoinConfirmTrezorPanelView) {
    this.sendBitcoinConfirmTrezorPanelView = sendBitcoinConfirmTrezorPanelView;
  }

  public SendBitcoinEnterPaymentMemoPanelModel getSendBitcoinEnterPaymentMemoPanelModel() {
    return sendBitcoinEnterPaymentMemoPanelModel;
  }

  public void setEnterPaymentMemoPanelModel(SendBitcoinEnterPaymentMemoPanelModel sendBitcoinEnterPaymentMemoPanelModel) {
    this.sendBitcoinEnterPaymentMemoPanelModel = sendBitcoinEnterPaymentMemoPanelModel;
  }

  public SendBitcoinShowPaymentACKMemoPanelModel getSendBitcoinShowPaymentACKMemoPanelModel() {
    return sendBitcoinShowPaymentACKMemoPanelModel;
  }

  public void setSendBitcoinShowPaymentACKMemoPanelModel(SendBitcoinShowPaymentACKMemoPanelModel sendBitcoinShowPaymentACKMemoPanelModel) {
    this.sendBitcoinShowPaymentACKMemoPanelModel = sendBitcoinShowPaymentACKMemoPanelModel;
  }

  /**
   * @return Any Bitcoin URI used to initiate this wizard
   */
  public Optional<BitcoinURI> getBitcoinURI() {
    return bitcoinURI;
  }

  /**
   * @return Any payment session summary used to initiate this wizard
   */
  public Optional<PaymentRequestData> getPaymentRequestData() {
    return paymentRequestData;
  }

  /**
   * @return the SendRequestSummary that includes all the tx details
   */
  public SendRequestSummary getSendRequestSummary() {
    return sendRequestSummary;
  }

  /**
   * Prepare the Bitcoin transaction that will be sent (after user confirmation for non BIP70 sends)
   *
   * @return True if the transaction was prepared OK
   */
  private boolean prepareTransaction() {

    // Ensure Bitcoin network service is started
    BitcoinNetworkService bitcoinNetworkService = CoreServices.getOrCreateBitcoinNetworkService();
    Preconditions.checkState(bitcoinNetworkService.isStartedOk(), "'bitcoinNetworkService' should be started");

    Address changeAddress = bitcoinNetworkService.getNextChangeAddress();

    // Determine if this came from a BIP70 payment request
    if (paymentRequestData.isPresent()) {
      Optional<FiatPayment>  fiatPayment = paymentRequestData.get().getFiatPayment();
      PaymentSession paymentSession;
      try {
        if (paymentRequestData.get().getPaymentRequest().isPresent()) {
          paymentSession = new PaymentSession(paymentRequestData.get().getPaymentRequest().get(), false);
        } else {
          log.error("No PaymentRequest in PaymentRequestData - cannot create a paymentSession");
          return false;
        }
      } catch (PaymentProtocolException e) {
        log.error("Could not create PaymentSession from payment request {}, error was {}", paymentRequestData.get().getPaymentRequest().get(), e);
        return false;
      }

      // Build the send request summary from the payment request
      Wallet.SendRequest sendRequest = paymentSession.getSendRequest();
      log.debug("SendRequest from BIP70 paymentSession: {}", sendRequest);

      Optional<FeeState> feeState = WalletManager.INSTANCE.calculateBRITFeeState(true);

      // Prepare the transaction i.e work out the fee sizes (not empty wallet)
      sendRequestSummary = new SendRequestSummary(
        sendRequest,
        fiatPayment,
        FeeService.normaliseRawFeePerKB(Configurations.currentConfiguration.getWallet().getFeePerKB()),
        null,
        feeState
      );

      // Ensure we keep track of the change address (used when calculating fiat equivalent)
      sendRequestSummary.setChangeAddress(changeAddress);
      sendRequest.changeAddress = changeAddress;
    } else {
      Preconditions.checkNotNull(enterAmountPanelModel);
      Preconditions.checkNotNull(confirmPanelModel);

      // Check a recipient has been set
      if (!enterAmountPanelModel
              .getEnterRecipientModel()
              .getRecipient().isPresent()) {
        return false;
      }

      // Build the send request summary from the user data
      Coin coin = enterAmountPanelModel.getEnterAmountModel().getCoinAmount().or(Coin.ZERO);
      Address bitcoinAddress = enterAmountPanelModel
        .getEnterRecipientModel()
        .getRecipient()
        .get()
        .getBitcoinAddress();

      Optional<FeeState> feeState = WalletManager.INSTANCE.calculateBRITFeeState(true);

      // Create the fiat payment - note that the fiat amount is not populated, only the exchange rate data.
      // This is because the client and transaction fee is only worked out at point of sending, and the fiat equivalent is computed from that
      Optional<FiatPayment> fiatPayment;
      Optional<ExchangeRateChangedEvent> exchangeRateChangedEvent = CoreServices.getApplicationEventService().getLatestExchangeRateChangedEvent();
      if (exchangeRateChangedEvent.isPresent()) {
        fiatPayment = Optional.of(new FiatPayment());
        fiatPayment.get().setRate(Optional.of(exchangeRateChangedEvent.get().getRate().toString()));
        // A send is denoted with a negative fiat amount
        fiatPayment.get().setAmount(Optional.<BigDecimal>absent());
        fiatPayment.get().setCurrency(Optional.of(exchangeRateChangedEvent.get().getCurrency()));
        fiatPayment.get().setExchangeName(Optional.of(ExchangeKey.current().getExchangeName()));
      } else {
        fiatPayment = Optional.absent();
      }

      // Prepare the transaction i.e work out the fee sizes (not empty wallet)
      sendRequestSummary = new SendRequestSummary(
        bitcoinAddress,
        coin,
        fiatPayment,
        changeAddress,
        FeeService.normaliseRawFeePerKB(Configurations.currentConfiguration.getWallet().getFeePerKB()),
        null,
        feeState,
        false);
    }

    log.debug("Just about to prepare transaction for sendRequestSummary: {}", sendRequestSummary);
    return bitcoinNetworkService.prepareTransaction(sendRequestSummary);
  }

  private void sendBitcoin() {

    // Actually send the bitcoin by signing using the credentials, committing to the wallet and broadcasting to the Bitcoin network
    Preconditions.checkNotNull(confirmPanelModel);

    // Copy the note into the sendRequestSummary
    if (confirmPanelModel.getNotes() != null) {
      sendRequestSummary.setNotes(Optional.of(confirmPanelModel.getNotes()));
    } else {
      sendRequestSummary.setNotes(Optional.<String>absent());
    }

    // Copy the credentials into the sendRequestSummary
    sendRequestSummary.setPassword(confirmPanelModel.getPasswordModel().getValue());

    BitcoinNetworkService bitcoinNetworkService = CoreServices.getOrCreateBitcoinNetworkService();
    Preconditions.checkState(bitcoinNetworkService.isStartedOk(), "'bitcoinNetworkService' should be started");

    log.debug("Just about to send bitcoin: {}", sendRequestSummary);
    lastBitcoinSentEvent = null;
    bitcoinNetworkService.send(sendRequestSummary, paymentRequestData);

    // The send throws TransactionCreationEvents and BitcoinSentEvents to which you subscribe to to work out success and failure.
  }

  /**
   * Populate the panel model with the Bitcoin URI details
   */
  void handleBitcoinURI() {

    if (!bitcoinURI.isPresent()) {
      return;
    }

    BitcoinURI uri = bitcoinURI.get();
    Optional<Address> address = Optional.fromNullable(uri.getAddress());
    Optional<Coin> amount = Optional.fromNullable(uri.getAmount());

    if (address.isPresent()) {

      final Optional<Recipient> recipient;

      // Get the current wallet
      Optional<WalletSummary> currentWalletSummary = WalletManager.INSTANCE.getCurrentWalletSummary();

      if (currentWalletSummary.isPresent()) {

        // Attempt to locate a contact with the address in the Bitcoin URI to reassure user
        List<Contact> contacts = CoreServices
          .getOrCreateContactService(currentWalletSummary.get().getWalletId())
          .filterContactsByBitcoinAddress(address.get());

        if (!contacts.isEmpty()) {
          // Offer the first contact with the matching address (already null checked)
          Address bitcoinAddress = contacts.get(0).getBitcoinAddress().get();
          recipient = Optional.of(new Recipient(bitcoinAddress));
          recipient.get().setContact(contacts.get(0));
        } else {
          // No matching contact so make an anonymous Recipient
          recipient = Optional.of(new Recipient(address.get()));
        }

      } else {
        // No current wallet so make an anonymous Recipient
        recipient = Optional.of(new Recipient(address.get()));
      }

      // Must have a valid address and therefore recipient to be here
      enterAmountPanelModel
        .getEnterRecipientModel()
        .setValue(recipient.get());

      // Add in any amount or treat as zero
      enterAmountPanelModel
        .getEnterAmountModel()
        .setCoinAmount(amount.or(Coin.ZERO));
    }
  }

  /**
   * @param pinPositions The PIN positions providing a level of obfuscation to protect the PIN
   */
  public void requestPinCheck(final String pinPositions) {

    ListenableFuture<Boolean> pinCheckFuture = hardwareWalletRequestService.submit(
      new Callable<Boolean>() {

        @Override
        public Boolean call() {

          log.debug("Performing a PIN check");

          // Talk to the Trezor and get it to check the PIN
          // This call to the Trezor will (sometime later) fire a
          // HardwareWalletEvent containing the encrypted text (or a PIN failure)
          // Expect a SHOW_OPERATION_SUCCEEDED or SHOW_OPERATION_FAILED
          Optional<HardwareWalletService> hardwareWalletService = CoreServices.getOrCreateHardwareWalletService();
          hardwareWalletService.get().providePIN(pinPositions);

          // Must have successfully send the message to be here
          return true;

        }
      });
    Futures.addCallback(
      pinCheckFuture, new FutureCallback<Boolean>() {

        @Override
        public void onSuccess(Boolean result) {

          // Do nothing message was sent to device correctly

        }

        @Override
        public void onFailure(Throwable t) {

          log.error(t.getMessage(), t);
          // Failed to send the message
          enterPinPanelView.failedPin();
        }
      }
    );

  }

  @Override
  public void showPINEntry(HardwareWalletEvent event) {

    switch (state) {
      case SEND_CONFIRM_TREZOR:
        log.debug("Transaction signing is PIN protected");
        state = SendBitcoinState.SEND_ENTER_PIN_FROM_CONFIRM_TREZOR;
        break;
      default:
        throw new IllegalStateException("Unknown state: " + state.name());
    }
  }

  @Override
  public void showButtonPress(HardwareWalletEvent event) {

    log.debug("Received hardware event: '{}'.{}", event.getEventType().name(), event.getMessage());

    // Successful PIN entry or not required so transition to Trezor signing display view
    state = SEND_CONFIRM_TREZOR;

    BitcoinNetworkService bitcoinNetworkService = CoreServices.getOrCreateBitcoinNetworkService();

    // Update label with descriptive text matching what the Trezor is showing
    ButtonRequest buttonRequest = (ButtonRequest) event.getMessage().get();

    // General message is nothing
    MessageKey key = null;
    Object[] values = null;

    if (bitcoinNetworkService.getLastSendRequestSummaryOptional().isPresent() && bitcoinNetworkService.getLastWalletOptional().isPresent()) {

      // We have a send request and a wallet

      Wallet wallet = bitcoinNetworkService.getLastWalletOptional().get();

      BitcoinConfiguration bitcoinConfiguration = Configurations.currentConfiguration.getBitcoin();
      LanguageConfiguration languageConfiguration = Configurations.currentConfiguration.getLanguage();

      Optional<Transaction> currentTransactionOptional = CoreServices.getOrCreateHardwareWalletService().get().getContext().getTransaction();
      if (currentTransactionOptional.isPresent()) {

        Transaction currentTransaction = currentTransactionOptional.get();
        // Substitute mBTC for MICON
        String bitcoinSymbolText = bitcoinConfiguration.getBitcoinSymbol();
        if (BitcoinSymbol.MICON.toString().equals(bitcoinSymbolText)) {
          bitcoinSymbolText = BitcoinSymbol.MBTC.getSymbol();
        }

        switch (buttonRequest.getButtonRequestType()) {
          case CONFIRM_OUTPUT:

            // Work out which output we're confirming (will be in same order as tx but wallet addresses will be ignored)

            Optional<TransactionOutput> confirmingOutput = Optional.absent();
            do {
              // Always increment from starting position (first button request is then 0 index)
              txOutputIndex++;

              if (!currentTransaction.getOutput(txOutputIndex).isMine(wallet)) {
                // Not owned by us so Trezor will show it on the display
                confirmingOutput = Optional.of(currentTransaction.getOutput(txOutputIndex));
                break;
              }
            } while (txOutputIndex < currentTransaction.getOutputs().size());

            if (confirmingOutput.isPresent()) {

              // Trezor will be displaying this output
              TransactionOutput output = confirmingOutput.get();

              String[] transactionOutputAmount = Formats.formatCoinAsSymbolic(output.getValue(), languageConfiguration, bitcoinConfiguration);

              Address transactionOutputAddress = output.getAddressFromP2PKHScript(MainNetParams.get());
              key = MessageKey.TREZOR_TRANSACTION_OUTPUT_CONFIRM_DISPLAY;
              values = new String[]{
                transactionOutputAmount[0] + transactionOutputAmount[1] + " " + bitcoinSymbolText,
                transactionOutputAddress == null ? "" : transactionOutputAddress.toString()
              };

            } else {
              throw new IllegalStateException("Trezor is confirming an output outside of the transaction. Have change addresses been ignored?");
            }
            break;
          case SIGN_TX:
            // Transaction#getValue() provides the net amount leaving the wallet which includes the fee
            // See #499: Trezor firmware below 1.3.3 displays the sum of all external outputs (including fee) and the fee separately
            // From 1.3.3+ the display is the net amount leaving the wallet with fees shown separately
            Coin transactionAmount = currentTransaction.getValue(wallet).negate();
            String[] transactionAmountFormatted = Formats.formatCoinAsSymbolic(transactionAmount, languageConfiguration, bitcoinConfiguration);
            String[] feeAmount = Formats.formatCoinAsSymbolic(currentTransaction.getFee(), languageConfiguration, bitcoinConfiguration);

            key = MessageKey.TREZOR_SIGN_CONFIRM_DISPLAY;
            values = new String[]{
              transactionAmountFormatted[0] + transactionAmountFormatted[1] + " " + bitcoinSymbolText,
              feeAmount[0] + feeAmount[1] + " " + bitcoinSymbolText
            };
            break;
          default:

        }
      }
    }
    sendBitcoinConfirmTrezorPanelView.setDisplayText(key, values);
  }

  @Override
  public void showOperationSucceeded(HardwareWalletEvent event) {

    if (state == SEND_ENTER_PIN_FROM_CONFIRM_TREZOR) {
      // Indicate a successful PIN
      getEnterPinPanelView().setPinStatus(true, true);
      return;
    }

    // Must be showing signing Trezor display

    // Enable next button
    ViewEvents.fireWizardButtonEnabledEvent(
      getPanelName(),
      WizardButton.NEXT,
      true
    );

    // TODO Refactor this off the EDT
    SwingUtilities.invokeLater(
      new Runnable() {
        @Override
        public void run() {

          // The tx is now complete so commit and broadcast it
          // Trezor will provide a signed serialized transaction
          byte[] deviceTxPayload = CoreServices.getOrCreateHardwareWalletService().get().getContext().getSerializedTx().toByteArray();

          log.info("DeviceTx payload:\n{}", Utils.HEX.encode(deviceTxPayload));

          // Load deviceTx
          Transaction deviceTx = new Transaction(MainNetParams.get(), deviceTxPayload);

          log.info("deviceTx:\n{}", deviceTx.toString());

          // Check the signatures are canonical
          for (TransactionInput txInput : deviceTx.getInputs()) {
            byte[] signature = txInput.getScriptSig().getChunks().get(0).data;
            if (signature != null) {
              log.debug(
                "Is signature canonical test result '{}' for txInput '{}', signature '{}'",
                TransactionSignature.isEncodingCanonical(signature),
                txInput.toString(),
                Utils.HEX.encode(signature));
            } else {
              log.warn("No signature data");
            }
          }

          log.debug("Committing and broadcasting the last tx");

          BitcoinNetworkService bitcoinNetworkService = CoreServices.getOrCreateBitcoinNetworkService();

          if (bitcoinNetworkService.getLastSendRequestSummaryOptional().isPresent()
            && bitcoinNetworkService.getLastWalletOptional().isPresent()) {

            SendRequestSummary sendRequestSummary = bitcoinNetworkService.getLastSendRequestSummaryOptional().get();

            // Check the unsigned and signed tx are essentially the same as a check against malware attacks on the Trezor
            if (TransactionUtils.checkEssentiallyEqual(sendRequestSummary.getSendRequest().get().tx, deviceTx)) {
              // Substitute the signed tx from the trezor
              log.debug(
                "Substituting the Trezor signed tx '{}' for the unsigned version {}",
                deviceTx.toString(),
                sendRequestSummary.getSendRequest().get().tx.toString()
              );
              sendRequestSummary.getSendRequest().get().tx = deviceTx;
              log.debug("The transaction fee was {}", sendRequestSummary.getSendRequest().get().fee);

              sendBitcoinConfirmTrezorPanelView.setOperationText(MessageKey.TREZOR_TRANSACTION_CREATED_OPERATION);
              sendBitcoinConfirmTrezorPanelView.setRecoveryText(MessageKey.CLICK_NEXT_TO_CONTINUE);
              sendBitcoinConfirmTrezorPanelView.setDisplayVisible(false);

              // Get the last wallet
              Wallet wallet = bitcoinNetworkService.getLastWalletOptional().get();

              // Commit and broadcast
              bitcoinNetworkService.commitAndBroadcast(sendRequestSummary, wallet, paymentRequestData);

              // Ensure the header is switched off whilst the send is in progress
              ViewEvents.fireViewChangedEvent(ViewKey.HEADER, false);
            } else {
              // The signed transaction is essentially different from what was sent to it - abort send
              sendBitcoinConfirmTrezorPanelView.setOperationText(MessageKey.TREZOR_FAILURE_OPERATION);
              sendBitcoinConfirmTrezorPanelView.setRecoveryText(MessageKey.CLICK_NEXT_TO_CONTINUE);
              sendBitcoinConfirmTrezorPanelView.setDisplayVisible(false);
            }
          } else {
            log.debug("Cannot commit and broadcast the last send as it is not present in bitcoinNetworkService");
          }
          // Clear the previous remembered tx
          bitcoinNetworkService.setLastSendRequestSummaryOptional(Optional.<SendRequestSummary>absent());
          bitcoinNetworkService.setLastWalletOptional(Optional.<Wallet>absent());
        }
      });

  }

  @Override
  public void showOperationFailed(HardwareWalletEvent event) {
    switch (state) {
      case SEND_ENTER_PIN_FROM_CONFIRM_TREZOR:
        state = SendBitcoinState.SEND_REPORT;
        setReportMessageKey(MessageKey.TREZOR_INCORRECT_PIN_FAILURE);
        setReportMessageStatus(false);
        requestCancel();
        break;
      default:
        state = SendBitcoinState.SEND_REPORT;
        setReportMessageKey(MessageKey.TREZOR_SIGN_FAILURE);
        setReportMessageStatus(false);
        requestCancel();
        break;
    }

    // Ignore device reset messages
    ApplicationEventService.setIgnoreHardwareWalletEventsThreshold(Dates.nowUtc().plusSeconds(1));

  }

  /**
   * Handles the process of sending a BIP70 Payment to the merchant and receiving a PaymentAck
   */
  public void sendPaymentToMerchant() {
    // Check for successful send and a BIP70 Payment requirement
    if (lastBitcoinSentEvent != null && lastBitcoinSentEvent.isSendWasSuccessful()) {
      Preconditions.checkNotNull(getPaymentRequestData());
      Preconditions.checkState(getPaymentRequestData().isPresent());
      Preconditions.checkNotNull(getPaymentRequestData().get().getPaymentSessionSummary());
      Preconditions.checkState(getPaymentRequestData().get().getPaymentSessionSummary().isPresent());
      Preconditions.checkState(getPaymentRequestData().get().getPaymentSessionSummary().get().hasPaymentSession());

      PaymentSessionSummary paymentSessionSummary = getPaymentRequestData().get().getPaymentSessionSummary().get();

      // Send the Payment message to the merchant
      try {
        final List<Transaction> transactionsSent = Lists.newArrayList(lastBitcoinSentEvent.getTransaction().get());
        final PaymentRequestData finalPaymentRequestData = getPaymentRequestData().get();

        final Optional<PaymentSessionSummary.PaymentProtocolResponseDto> dto = paymentSessionSummary.sendPaymentSessionPayment(
            transactionsSent,
            lastBitcoinSentEvent.getChangeAddress(),
            getSendBitcoinEnterPaymentMemoPanelModel().getPaymentMemo());

        final Protos.Payment finalPayment = dto.get().getFinalPayment();
        final ListenableFuture<PaymentProtocol.Ack> future = dto.get().getFuture();

        if (future != null) {
          Futures.addCallback(
            future, new FutureCallback<PaymentProtocol.Ack>() {
              @Override
              public void onSuccess(PaymentProtocol.Ack result) {

                // Have successfully received a PaymentAck from the merchant
                log.info("Received PaymentAck from merchant. Memo: {}", result.getMemo());
                getSendBitcoinShowPaymentACKMemoPanelModel().setPaymentACKMemo(result.getMemo());

                PaymentProtocolService paymentProtocolService = CoreServices.getPaymentProtocolService();

                if (finalPayment != null) {
                  Optional<Protos.PaymentACK> paymentACK = paymentProtocolService.newPaymentACK(finalPayment, result.getMemo());

                  finalPaymentRequestData.setPayment(Optional.of(finalPayment));
                  finalPaymentRequestData.setPaymentACK(paymentACK);
                  log.debug("Saving PaymentMemo of {} and PaymentACKMemo of {}", finalPayment.getMemo(), paymentACK.isPresent() ? paymentACK.get().getMemo() : "n/a");
                  WalletService walletService = CoreServices.getOrCreateWalletService(WalletManager.INSTANCE.getCurrentWalletSummary().get().getWalletId());
                  walletService.addPaymentRequestData(finalPaymentRequestData);

                  // Write payments
                  CharSequence password = WalletManager.INSTANCE.getCurrentWalletSummary().get().getWalletPassword().getPassword();
                  if (password != null) {
                    walletService.writePayments(password);
                  }

                  CoreEvents.firePaymentSentToRequestorEvent(new PaymentSentToRequestorEvent(true, CoreMessageKey.PAYMENT_SENT_TO_REQUESTER_OK, null));
                } else {
                  log.error("No payment and hence cannot save payment or paymentACK");
                }
              }

              @Override
              public void onFailure(Throwable t) {
                // Failed to communicate with the merchant
                log.error("Unexpected failure", t);
                CoreEvents.firePaymentSentToRequestorEvent(
                  new PaymentSentToRequestorEvent(
                    false,
                    CoreMessageKey.PAYMENT_SENT_TO_REQUESTER_FAILED,
                    new String[]{t.getClass().getCanonicalName() + " " + t.getMessage()}));
              }
            });
        } else {
          throw new PaymentProtocolException("Failed to create future from Ack");
        }
      } catch (IOException | PaymentProtocolException e) {
        log.error("Unexpected failure", e);
        CoreEvents.firePaymentSentToRequestorEvent(
          new PaymentSentToRequestorEvent(
            false,
            CoreMessageKey.PAYMENT_SENT_TO_REQUESTER_FAILED,
            new String[]{e.getClass().getCanonicalName() + " " + e.getMessage()}));
      }
    } else {
      String message = "Bitcoin not sent successfully so no payment sent to requestor";
      log.debug(message);
      CoreEvents.firePaymentSentToRequestorEvent(new PaymentSentToRequestorEvent(false, CoreMessageKey.PAYMENT_SENT_TO_REQUESTER_FAILED, new String[]{message}));
    }
  }

  public boolean isBIP70() {
    return isBIP70;
  }

  public void prepareWhenBIP70() {
    // if constructed using a paymentRequestData (BIP70) then prepare the tx immediately
    if (isBIP70) {
      if (prepareTransaction()) {
        log.debug("BIP70 prepareTransaction was successful, moving to SEND_CONFIRM_AMOUNT");
        this.state = SEND_CONFIRM_AMOUNT;
      } else {
        // Transaction did not prepare correctly
        log.debug("BIP70 prepareTransaction was NOT successful, moving to SEND_REPORT");
        this.state = SEND_REPORT;

        // TODO disable navigation to SendBIP70InfoViewPanel as transaction was not sent
      }
    } else {
      log.debug("No payment request available, moving to SEND_REPORT");
      this.state = SEND_REPORT;
    }
  }

  @Subscribe
  public void onBitcoinSentEvent(BitcoinSentEvent bitcoinSentEvent) {
    lastBitcoinSentEvent = bitcoinSentEvent;
  }

  public BitcoinSentEvent getLastBitcoinSentEvent() {
    return lastBitcoinSentEvent;
  }

  public void setLastBitcoinSentEvent(BitcoinSentEvent lastBitcoinSentEvent) {
    this.lastBitcoinSentEvent = lastBitcoinSentEvent;
  }

  public SendBitcoinEnterPinPanelView getEnterPinPanelView() {
    return enterPinPanelView;
  }

  public void setEnterPinPanelView(SendBitcoinEnterPinPanelView enterPinPanelView) {
    this.enterPinPanelView = enterPinPanelView;
  }

}
