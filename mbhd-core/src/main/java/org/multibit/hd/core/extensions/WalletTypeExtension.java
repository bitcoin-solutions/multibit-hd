package org.multibit.hd.core.extensions;

import com.google.common.base.Charsets;
import org.bitcoinj.core.Wallet;
import org.bitcoinj.core.WalletExtension;
import org.multibit.hd.core.dto.WalletType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Wallet Extension to provide the following to Wallet:</p>
 * <ul>
 * <li>Persistence of the wallet type</li>
 * </ul>
 *
 * @since 0.0.1
 */
public class WalletTypeExtension implements WalletExtension {

  private static final Logger log = LoggerFactory.getLogger(WalletTypeExtension.class);

  public static final String WALLET_TYPE_WALLET_EXTENSION_ID = "org.multibit.hd.core.WalletTypeExtension";

  private WalletType walletType;

  public WalletTypeExtension() {
    this.walletType = null;
  }

  public WalletTypeExtension(WalletType walletType) {
    this.walletType = walletType;
  }

  @Override
  public String getWalletExtensionID() {
    return WALLET_TYPE_WALLET_EXTENSION_ID;
  }

  @Override
  public boolean isWalletExtensionMandatory() {
    return false;
  }

  @Override
  public byte[] serializeWalletExtension() {
    if (walletType != null) {
      return walletType.name().getBytes(Charsets.UTF_8);
    } else {
      return WalletType.UNKNOWN.name().getBytes(Charsets.UTF_8);
    }
  }

  @Override
  public void deserializeWalletExtension(Wallet containingWallet, byte[] data) throws Exception {

    String serialisedString = new String(data, Charsets.UTF_8);
    log.debug("Parsing wallet type string '{}'", serialisedString);

    if (WalletType.MBHD_SOFT_WALLET.name().equals(serialisedString)) {
      walletType = WalletType.MBHD_SOFT_WALLET;
    } else if (WalletType.TREZOR_SOFT_WALLET.name().equals(serialisedString)) {
          walletType = WalletType.TREZOR_SOFT_WALLET;
    } else if (WalletType.MBHD_SOFT_WALLET_BIP32.name().equals(serialisedString)) {
              walletType = WalletType.MBHD_SOFT_WALLET_BIP32;
    } else if (WalletType.TREZOR_HARD_WALLET.name().equals(serialisedString)) {
      walletType = WalletType.TREZOR_HARD_WALLET;
    } else if (WalletType.UNKNOWN.name().equals(serialisedString)) {
      walletType = WalletType.UNKNOWN;
    } else {
      // Could not parse
      log.warn("The wallet type of '{}' could not be parsed. Wallet type set to UNKNOWN", serialisedString);
      walletType = WalletType.UNKNOWN;
    }
  }

  public WalletType getWalletType() {
    return walletType;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    WalletTypeExtension that = (WalletTypeExtension) o;

    return walletType == that.walletType;
  }

  @Override
  public int hashCode() {
    return walletType != null ? walletType.hashCode() : 0;
  }

  @Override
  public String toString() {
    return "WalletTypeExtension{" +
            "walletType=" + walletType +
            '}';
  }
}
