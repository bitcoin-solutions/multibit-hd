package org.multibit.hd.core.services;

import org.bitcoinj.core.*;

/**
 * Created by Keepkey on 7/19/16.
 */
public class FeeService {
    public static final Coin MINIMUM_FEE_PER_KB = Coin.valueOf(1000);   // Slightly higher than the minimum relay fee (1000 sat per KB)  as per Bitcoin Core 0.9
    public static final Coin DEFAULT_FEE_PER_KB = Coin.valueOf(15000);  // 0.15 mBTC per KB - a long used fee structure which works as of spam attacks of July 2015
    public static final Coin MAXIMUM_FEE_PER_KB = Coin.valueOf(200000);  // 2.0 mBTC per KB
    public static final String DONATION_ADDRESS = "Refactor this";
    public static final String DEFAULT_DONATION_AMOUNT = "0.01"; // in BTC as per BIP21

    public static Coin normaliseRawFeePerKB(long rawFeePerKB) {
        if (rawFeePerKB == 0) {
            return DEFAULT_FEE_PER_KB;
        }

        if (Coin.valueOf(rawFeePerKB).compareTo(MINIMUM_FEE_PER_KB) < 0) {
            return MINIMUM_FEE_PER_KB;
        }

        if (Coin.valueOf(rawFeePerKB).compareTo(MAXIMUM_FEE_PER_KB) > 0) {
            return MAXIMUM_FEE_PER_KB;
        }

        // Ok as is
        return Coin.valueOf(rawFeePerKB);
    }
}
