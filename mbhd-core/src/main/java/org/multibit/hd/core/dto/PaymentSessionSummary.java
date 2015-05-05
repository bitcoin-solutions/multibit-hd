package org.multibit.hd.core.dto;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.ListenableFuture;
import org.bitcoin.protocols.payments.Protos;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.protocols.payments.PaymentProtocol;
import org.bitcoinj.protocols.payments.PaymentProtocolException;
import org.bitcoinj.protocols.payments.PaymentSession;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.IOException;
import java.security.KeyStoreException;
import java.security.cert.CertPathValidatorException;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * <p>Value object to provide the following to Core API:</p>
 * <ul>
 * <li>Information about a payment session's status</li>
 * </ul>
 *
 * @since 0.0.7
 */
public class PaymentSessionSummary {

  private static final Logger log = LoggerFactory.getLogger(PaymentSessionSummary.class);

  private final PaymentSessionStatus status;
  private final Optional<PaymentSession> paymentSession;
  private final Optional<PaymentProtocol.PkiVerificationData> pkiVerificationData;

  private final RAGStatus severity;

  private final CoreMessageKey messageKey;
  private final Object[] messageData;

  /**
   * <p>The server has returned a well-formed payment request</p>
   *
   * @param paymentSession      The payment session containing meta data (cannot be null to be OK)
   * @param pkiVerificationData The PKI verification data containing identity information (cannot be null to be OK)
   *
   * @return A new "payment session OK" summary
   */
  public static PaymentSessionSummary newPaymentSessionOK(PaymentSession paymentSession, PaymentProtocol.PkiVerificationData pkiVerificationData) {

    Preconditions.checkNotNull(paymentSession, "'paymentSession' must be present");

    return new PaymentSessionSummary(
      Optional.of(paymentSession),
      Optional.fromNullable(pkiVerificationData),
      PaymentSessionStatus.TRUSTED,
      RAGStatus.GREEN,
      CoreMessageKey.PAYMENT_SESSION_OK,
      new String[]{paymentSession.getMemo()}
    );

  }

  /**
   * <p>The server has returned a well-formed payment request that has failed PKI validation</p>
   *
   * <p>The user may want to proceed under these circumstances so we cater for it.</p>
   *
   * @param paymentSession The payment session containing meta data
   *
   * @return A new "payment session" summary with appropriate confidence level
   */
  public static PaymentSessionSummary newPaymentSessionAlmostOK(PaymentSession paymentSession, Exception e) {

    if (e instanceof PaymentProtocolException.InvalidPkiData) {
      return new PaymentSessionSummary(
        Optional.of(paymentSession),
        Optional.<PaymentProtocol.PkiVerificationData>absent(),
        PaymentSessionStatus.UNTRUSTED,
        RAGStatus.AMBER,
        CoreMessageKey.PAYMENT_SESSION_PKI_INVALID,
        new String[]{paymentSession.getMemo(), e.getMessage()}
      );
    }
    if (e instanceof PaymentProtocolException.InvalidPkiType) {
      return new PaymentSessionSummary(
        Optional.of(paymentSession),
        Optional.<PaymentProtocol.PkiVerificationData>absent(),
        PaymentSessionStatus.UNTRUSTED,
        RAGStatus.AMBER,
        CoreMessageKey.PAYMENT_SESSION_PKI_INVALID_TYPE,
        new String[]{paymentSession.getMemo(), e.getMessage()}
      );
    }
    if (e instanceof PaymentProtocolException.PkiVerificationException) {
      return new PaymentSessionSummary(
        Optional.of(paymentSession),
        Optional.<PaymentProtocol.PkiVerificationData>absent(),
        PaymentSessionStatus.UNTRUSTED,
        RAGStatus.AMBER,
        CoreMessageKey.PAYMENT_SESSION_PKI_MISSING,
        new String[]{paymentSession.getMemo(), e.getMessage()}
      );
    }

    if (e instanceof KeyStoreException) {
      return new PaymentSessionSummary(
        Optional.of(paymentSession),
        Optional.<PaymentProtocol.PkiVerificationData>absent(),
        PaymentSessionStatus.UNTRUSTED,
        RAGStatus.AMBER,
        CoreMessageKey.PAYMENT_SESSION_PKI_UNTRUSTED_CA,
        new String[]{paymentSession.getMemo(), e.getMessage()}
      );
    }

    // Assume the worst
    return newPaymentSessionFromException(e, paymentSession.getMemo());

  }

  /**
   * @param e The payment protocol exception (either an ERROR or a DOWN)
   *
   * @return A suitable payment session summary
   */
  public static PaymentSessionSummary newPaymentSessionFromException(Exception e, String hostName) {

    log.warn("Failed payment server: Host={} Failure={}", hostName, e.getMessage());

    // Default handling is ERROR

    if (e instanceof InterruptedException) {
      return new PaymentSessionSummary(
        Optional.<PaymentSession>absent(),
        null, PaymentSessionStatus.DOWN,
        RAGStatus.AMBER,
        CoreMessageKey.PAYMENT_SESSION_DOWN,
        new String[]{hostName, e.getMessage()}
      );
    }
    if (e instanceof TimeoutException) {
      return new PaymentSessionSummary(
        Optional.<PaymentSession>absent(),
        null, PaymentSessionStatus.DOWN,
        RAGStatus.AMBER,
        CoreMessageKey.PAYMENT_SESSION_DOWN,
        new String[]{hostName, e.getMessage()}
      );
    }

    // Use default response
    return new PaymentSessionSummary(
      Optional.<PaymentSession>absent(),
      null, PaymentSessionStatus.ERROR,
      RAGStatus.AMBER,
      CoreMessageKey.PAYMENT_SESSION_ERROR,
      new String[]{hostName, e.getMessage()}
    );

  }

  /**
   * @param e        The payment protocol exception (specific problem)
   * @param hostName The host name
   *
   * @return A suitable payment session summary
   */
  public static PaymentSessionSummary newPaymentSessionFromException(PaymentProtocolException e, String hostName) {

    log.warn("Failed payment session: Host={} Failure={}", hostName, e.getMessage());

    // Default handling is ERROR

    if (e instanceof PaymentProtocolException.Expired) {
      return new PaymentSessionSummary(
        Optional.<PaymentSession>absent(),
        Optional.<PaymentProtocol.PkiVerificationData>absent(),
        PaymentSessionStatus.UNTRUSTED,
        RAGStatus.AMBER,
        CoreMessageKey.PAYMENT_SESSION_EXPIRED,
        new String[]{hostName, e.getMessage()}
      );
    }
    if (e instanceof PaymentProtocolException.InvalidNetwork) {
      return new PaymentSessionSummary(
        Optional.<PaymentSession>absent(),
        Optional.<PaymentProtocol.PkiVerificationData>absent(),
        PaymentSessionStatus.UNTRUSTED,
        RAGStatus.AMBER,
        CoreMessageKey.PAYMENT_SESSION_INVALID_NETWORK,
        new String[]{hostName, e.getMessage()}
      );
    }
    if (e instanceof PaymentProtocolException.InvalidOutputs) {
      return new PaymentSessionSummary(
        Optional.<PaymentSession>absent(),
        Optional.<PaymentProtocol.PkiVerificationData>absent(),
        PaymentSessionStatus.UNTRUSTED,
        RAGStatus.AMBER,
        CoreMessageKey.PAYMENT_SESSION_INVALID_OUTPUTS,
        new String[]{hostName, e.getMessage()}
      );
    }
    if (e instanceof PaymentProtocolException.InvalidPaymentRequestURL) {
      return new PaymentSessionSummary(
        Optional.<PaymentSession>absent(),
        Optional.<PaymentProtocol.PkiVerificationData>absent(),
        PaymentSessionStatus.UNTRUSTED,
        RAGStatus.AMBER,
        CoreMessageKey.PAYMENT_SESSION_INVALID_REQUEST_URL,
        new String[]{hostName, e.getMessage()}
      );
    }
    if (e instanceof PaymentProtocolException.InvalidPaymentURL) {
      return new PaymentSessionSummary(
        Optional.<PaymentSession>absent(),
        Optional.<PaymentProtocol.PkiVerificationData>absent(),
        PaymentSessionStatus.UNTRUSTED,
        RAGStatus.AMBER,
        CoreMessageKey.PAYMENT_SESSION_INVALID_PAYMENT_URL,
        new String[]{hostName, e.getMessage()}
      );
    }
    if (e instanceof PaymentProtocolException.InvalidVersion) {
      return new PaymentSessionSummary(
        Optional.<PaymentSession>absent(),
        Optional.<PaymentProtocol.PkiVerificationData>absent(),
        PaymentSessionStatus.UNTRUSTED,
        RAGStatus.AMBER,
        CoreMessageKey.PAYMENT_SESSION_INVALID_VERSION,
        new String[]{hostName, e.getMessage()}
      );
    }
    if (e instanceof PaymentProtocolException.InvalidPkiData) {
      return new PaymentSessionSummary(
        Optional.<PaymentSession>absent(),
        Optional.<PaymentProtocol.PkiVerificationData>absent(),
        PaymentSessionStatus.UNTRUSTED,
        RAGStatus.AMBER,
        CoreMessageKey.PAYMENT_SESSION_PKI_INVALID,
        new String[]{hostName, e.getMessage()}
      );
    }
    if (e instanceof PaymentProtocolException.InvalidPkiType) {
      return new PaymentSessionSummary(
        Optional.<PaymentSession>absent(),
        Optional.<PaymentProtocol.PkiVerificationData>absent(),
        PaymentSessionStatus.UNTRUSTED,
        RAGStatus.AMBER,
        CoreMessageKey.PAYMENT_SESSION_PKI_INVALID_TYPE,
        new String[]{hostName, e.getMessage()}
      );
    }
    if (e instanceof PaymentProtocolException.PkiVerificationException) {

      // This is a bit lame but the only way to differentiate PKI failures from untrusted
      if (e.getCause() != null && e.getCause() instanceof CertPathValidatorException) {
        // Untrusted CA (user might want to add it to the trust store)
        return new PaymentSessionSummary(
          Optional.<PaymentSession>absent(),
          Optional.<PaymentProtocol.PkiVerificationData>absent(),
          PaymentSessionStatus.UNTRUSTED,
          RAGStatus.AMBER,
          CoreMessageKey.PAYMENT_SESSION_PKI_UNTRUSTED_CA,
          new String[]{hostName, e.getMessage()}
        );
      } else {
        return new PaymentSessionSummary(
          Optional.<PaymentSession>absent(),
          Optional.<PaymentProtocol.PkiVerificationData>absent(),
          PaymentSessionStatus.UNTRUSTED,
          RAGStatus.AMBER,
          CoreMessageKey.PAYMENT_SESSION_PKI_MISSING,
          new String[]{hostName, e.getMessage()}
        );
      }
    }

    // Unknown
    return new PaymentSessionSummary(
      Optional.<PaymentSession>absent(),
      Optional.<PaymentProtocol.PkiVerificationData>absent(),
      PaymentSessionStatus.ERROR,
      RAGStatus.AMBER,
      CoreMessageKey.PAYMENT_SESSION_ERROR,
      new String[]{hostName, e.getMessage()}
    );

  }

  /**
   * <p>See the utility factory methods for standard situations</p>
   *
   * @param paymentSession      The optional payment session
   * @param pkiVerificationData The PKI verification data
   * @param status              The payment session status (e.g. OK)
   * @param severity            The severity (Red, Amber, Green)
   * @param messageKey          The error key to allow localisation
   * @param messageData         The error data for insertion into the error message
   */
  public PaymentSessionSummary(
    Optional<PaymentSession> paymentSession,
    Optional<PaymentProtocol.PkiVerificationData> pkiVerificationData,
    PaymentSessionStatus status,
    RAGStatus severity,
    CoreMessageKey messageKey,
    Object[] messageData) {

    this.paymentSession = paymentSession;
    this.pkiVerificationData = pkiVerificationData;

    this.status = status;
    this.severity = severity;

    this.messageKey = messageKey;
    this.messageData = Arrays.copyOf(messageData, messageData.length);

  }

  /**
   * @return true if there is a payment session
   */
  public boolean hasPaymentSession() {
    return paymentSession.isPresent();
  }

  /**
   * @return optional boolean, which holds true if the payment session has outputs
   */
  public Optional<Boolean> hasPaymentSessionOutputs() {
    if (hasPaymentSession()) {
      return Optional.of(!paymentSession.get().getOutputs().isEmpty());
    } else {
      return Optional.absent();
    }
  }

  /**
   * @return The memo from the payment session object
   */
  public Optional<String> getPaymentSessionMemo() {
    if (hasPaymentSession()) {
      return Optional.fromNullable(paymentSession.get().getMemo());
    } else {
      return Optional.absent();
    }
  }

  /**
   * @return The value from the payment session object
   */
  public Optional<Coin> getPaymentSessionValue() {
    if (hasPaymentSession()) {
      return Optional.fromNullable(paymentSession.get().getValue());
    } else {
      return Optional.absent();
    }
  }

  /**
   * @return The expires from the payment session object
   */
  public Optional<DateTime> getPaymentSessionExpires() {
    if (hasPaymentSession() && paymentSession.get().getExpires() != null) {
      return Optional.of(new DateTime(paymentSession.get().getExpires()));
    } else {
      return Optional.absent();
    }
  }

  /**
   * @return The date from the payment session object
   */
  public Optional<DateTime> getPaymentSessionDate() {
    if (hasPaymentSession() && paymentSession.get().getDate() != null) {
      return Optional.of(new DateTime(paymentSession.get().getDate()));
    } else {
      return Optional.absent();
    }
  }

  /**
   * @return The payment URL from the payment session object
   */
  public Optional<String> getPaymentSessionPaymentUrl() {
    if (hasPaymentSession()) {
      return Optional.fromNullable(paymentSession.get().getPaymentUrl());
    } else {
      return Optional.absent();
    }
  }

  /**
   * @return The payment request from the payment session object
   */
  public Optional<Protos.PaymentRequest> getPaymentSessionPaymentRequest() {
    if (hasPaymentSession()) {
      return Optional.fromNullable(paymentSession.get().getPaymentRequest());
    } else {
      return Optional.absent();
    }
  }

  public Optional<PaymentProtocolResponseDto> sendPaymentSessionPayment(List<Transaction> transactions, @Nullable Address refundAddr, @Nullable String memo)
      throws IOException, PaymentProtocolException {
    if (hasPaymentSession()) {
      log.debug("Sending payment details to requester at URL '{}'", paymentSession.get().getPaymentUrl());
      Protos.Payment payment = paymentSession.get().getPayment(transactions, refundAddr, memo);
      ListenableFuture<PaymentProtocol.Ack> future = paymentSession.get().sendPayment(transactions, refundAddr, memo);
      return Optional.of(new PaymentProtocolResponseDto(payment, future));
    } else {
      return Optional.absent();
    }
  }

  /**
   * Just a data holder for the result when sending the payment.
   */
  public static class PaymentProtocolResponseDto {
    private final Protos.Payment finalPayment;
    private final ListenableFuture<PaymentProtocol.Ack> future;

    public PaymentProtocolResponseDto(Protos.Payment finalPayment, ListenableFuture<PaymentProtocol.Ack> future) {
      this.finalPayment = finalPayment;
      this.future = future;
    }

    public Protos.Payment getFinalPayment() {
      return finalPayment;
    }

    public ListenableFuture<PaymentProtocol.Ack> getFuture() {
      return future;
    }
  }

  /**
   * @return The PKI verification data based on a second pass through the Payment Request (accurate)
   */
  public Optional<PaymentProtocol.PkiVerificationData> getPkiVerificationData() {
    return pkiVerificationData;
  }

  /**
   * @return The severity (e.g. AMBER)
   */
  public RAGStatus getSeverity() {
    return severity;
  }

  /**
   * @return The payment session status (e.g. "TRUSTED")
   */
  public PaymentSessionStatus getStatus() {
    return status;
  }

  /**
   * @return An optional array of arbitrary objects, often for insertion into a resource bundle string
   */
  public Object[] getMessageData() {
    return Arrays.copyOf(messageData, messageData.length);
  }

  public CoreMessageKey getMessageKey() {
    return messageKey;
  }

  @Override
  public String toString() {
    return "PaymentSessionSummary{" +
      "messageData=" + Arrays.toString(messageData) +
      ", status=" + status +
      ", paymentSession=" + paymentSession +
      ", pkiVerificationData=" + pkiVerificationData +
      ", severity=" + severity +
      ", messageKey=" + messageKey +
      '}';
  }
}
