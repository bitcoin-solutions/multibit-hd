package org.multibit.hd.core.blockexplorer;

import java.text.MessageFormat;

/**
 *  <p>Blockchain URI BlockExplorer<br>
 *  </p>
 *  
 */
public class BlockchainURIBlockExplorer implements BlockExplorer {
  public static final String ID = "blockchainuri";

  @Override
  public String getId() {
    return ID;
  }

  @Override
  public String getName() {
    return "system default";
  }

  @Override
  public MessageFormat getTransactionLookupMessageFormat() {
    return new MessageFormat("blockchain:/tx/{0}");
  }
}
