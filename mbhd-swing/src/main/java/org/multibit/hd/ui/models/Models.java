package org.multibit.hd.ui.models;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import org.bitcoinj.uri.BitcoinURI;
import org.multibit.hd.core.dto.PaymentRequestData;
import org.multibit.hd.core.dto.PaymentSessionSummary;
import org.multibit.hd.core.dto.RAGStatus;
import org.multibit.hd.core.events.TransactionSeenEvent;
import org.multibit.hd.core.managers.WalletManager;
import org.multibit.hd.core.services.CoreServices;
import org.multibit.hd.core.services.WalletService;
import org.multibit.hd.hardware.core.events.HardwareWalletEvent;
import org.multibit.hd.hardware.core.messages.Features;
import org.multibit.hd.ui.events.controller.ControllerEvents;
import org.multibit.hd.ui.events.view.ViewEvents;
import org.multibit.hd.ui.languages.Formats;
import org.multibit.hd.ui.languages.Languages;
import org.multibit.hd.ui.languages.MessageKey;
import org.multibit.hd.ui.views.components.Buttons;
import org.multibit.hd.ui.views.components.Panels;
import org.multibit.hd.ui.views.components.wallet_detail.WalletDetail;
import org.multibit.hd.ui.views.fonts.AwesomeIcon;
import org.multibit.hd.ui.views.wizards.Wizards;
import org.multibit.hd.ui.views.wizards.send_bitcoin.SendBitcoinParameter;

import javax.swing.*;
import java.awt.event.ActionEvent;

/**
 * <p>Factory to provide the following to UI:</p>
 * <ul>
 * <li>Provision of simple model wrappers</li>
 * </ul>
 *
 * @since 0.0.1
 *
 */
public class Models {

  /**
   * Utilities have no public constructor
   */
  private Models() {
  }

  /**
   * <p>A new alert model with no button</p>
   *
   * @param message The message
   * @param status  The RAG status
   *
   * @return A new alert model
   */
  public static AlertModel newAlertModel(String message, RAGStatus status) {
    return new AlertModel(message, status);
  }

  /**
   * <p>A new alert model with button</p>
   *
   * @param message The message
   * @param status  The RAG status
   * @param button  The button triggering an action
   *
   * @return A new alert model
   */
  public static AlertModel newAlertModel(String message, RAGStatus status, JButton button) {

    AlertModel model = newAlertModel(message, status);
    model.setButton(button);

    return model;
  }

  /**
   * @param bitcoinURI A Bitcoin URI
   *
   * @return An alert model suitable for use for displaying the information, absent if the Bitcoin URI does not contain sufficient information
   */
  public static Optional<AlertModel> newBitcoinURIAlertModel(final BitcoinURI bitcoinURI) {

    // Action to show the "send Bitcoin" wizard
    AbstractAction action = new AbstractAction() {
      @Override
      public void actionPerformed(ActionEvent e) {

        ControllerEvents.fireRemoveAlertEvent();

        SendBitcoinParameter parameter = new SendBitcoinParameter(bitcoinURI, null);

        Panels.showLightBox(Wizards.newSendBitcoinWizard(parameter).getWizardScreenHolder());

      }
    };
    JButton button = Buttons.newAlertPanelButton(action, MessageKey.YES, MessageKey.YES_TOOLTIP, AwesomeIcon.CHECK);

    // Attempt to decode the Bitcoin URI
    Optional<String> alertMessage = Formats.formatAlertMessage(bitcoinURI);

    // If there is sufficient information in the Bitcoin URI display it to the user as an alert
    if (alertMessage.isPresent()) {

      return Optional.of(Models.newAlertModel(
        alertMessage.get(),
        RAGStatus.PINK,
        button
      ));

    }

    return Optional.absent();
  }

  /**
   * @param paymentSessionSummary The payment session summary providing meta data
   *
   * @return An alert model suitable for use for displaying the payment request information, absent if the payment session summary does not contain sufficient information
   */
  public static Optional<AlertModel> newPaymentRequestAlertModel(final PaymentSessionSummary paymentSessionSummary) {

    // Action to show the "payment request details" wizard
    AbstractAction action = new AbstractAction() {
      @Override
      public void actionPerformed(ActionEvent e) {

        ControllerEvents.fireRemoveAlertEvent();

        // The user has indicated that the payment request is of interest so persist it
        Preconditions.checkState(WalletManager.INSTANCE.getCurrentWalletSummary().isPresent());
        Preconditions.checkNotNull(paymentSessionSummary);

        WalletService walletService = CoreServices.getOrCreateWalletService(WalletManager.INSTANCE.getCurrentWalletSummary().get().getWalletId());
        if (paymentSessionSummary.hasPaymentSession()) {

          // Build a PaymentRequestData for persistence
          PaymentRequestData paymentRequestData = new PaymentRequestData(paymentSessionSummary);

          // Add the localised trust status
          paymentRequestData.setTrustStatus(paymentSessionSummary.getStatus());
          paymentRequestData.setTrustErrorMessage(Languages.safeText(paymentSessionSummary.getMessageKey(), paymentSessionSummary.getMessageData()));

          // Store it (in memory) in the wallet service
          walletService.addPaymentRequestData(paymentRequestData);

          // The wallet has changed so UI will need updating
          ViewEvents.fireWalletDetailChangedEvent(new WalletDetail());

          // Show the wizard and provide the PaymentRequestData to the model
          Panels.showLightBox(Wizards.newPaymentsWizard(paymentRequestData).getWizardScreenHolder());
        }
      }
    };
    JButton button = Buttons.newAlertPanelButton(action, MessageKey.YES, MessageKey.YES_TOOLTIP, AwesomeIcon.CHECK);

    // Attempt to decode the Bitcoin URI
    Optional<String> alertMessage = Formats.formatAlertMessage(paymentSessionSummary);

    // If there is sufficient information in the payment request display it to the user as an alert
    if (alertMessage.isPresent()) {

      return Optional.of(Models.newAlertModel(
          alertMessage.get(),
          RAGStatus.PINK,
          button
        ));

    }

    return Optional.absent();
  }

  /**
   * @param transactionSeenEvent The transaction seen event
   *
   * @return An alert model suitable for use for displaying the information, absent if the Bitcoin URI does not contain sufficient information
   */
  public static AlertModel newPaymentReceivedAlertModel(TransactionSeenEvent transactionSeenEvent) {

    // Attempt to decode the "transaction seen" event
    String alertMessage = Formats.formatAlertMessage(transactionSeenEvent);

    return Models.newAlertModel(
      alertMessage,
      RAGStatus.GREEN
    );

  }

  /**
   * @param event The hardware wallet event (e.g. SHOW_DEVICE_FAILED etc)
   *
   * @return An alert model suitable for use for displaying the information
   */
  public static AlertModel newHardwareWalletAlertModel(HardwareWalletEvent event) {

    switch (event.getEventType()) {
      case SHOW_DEVICE_READY:

        String label = "";
        if (event.getMessage().isPresent()) {
          Features features = (Features) event.getMessage().get();
          label=features.getLabel();
        }

        // Provide action to allow user to see a wizard

        JButton button = Buttons.newAlertPanelButton(
          getAlertButtonAction(),
          // Considered using Shield + Trezor tools wizard message but screen
          // gets cluttered with shields everywhere and looks confused
          MessageKey.YES,
          MessageKey.YES_TOOLTIP,
          AwesomeIcon.CHECK
        );

        return Models.newAlertModel(
          Languages.safeText(MessageKey.TREZOR_ATTACHED_ALERT, label),
          RAGStatus.GREEN,
          button
        );
      case SHOW_DEVICE_FAILED:
        if (event.getMessage().isPresent()) {

        }
        return Models.newAlertModel(
          Languages.safeText(MessageKey.TREZOR_FAILURE_ALERT),
          RAGStatus.RED
        );
      default:
        throw new IllegalStateException("Unknown hardware wallet system event");
    }

  }

  /**
   *
   * @return A suitable action for the alert button
   */
  private static AbstractAction getAlertButtonAction() {

    return new AbstractAction() {
      @Override
      public void actionPerformed(ActionEvent e) {

        // Remove the alert since it's been dealt with
        ControllerEvents.fireRemoveAlertEvent();

        // Perform a switch wallet, which will close down the old wallet and then fire up a credentials wizard
        ViewEvents.fireSwitchWalletEvent();
      }
    };

  }
}
