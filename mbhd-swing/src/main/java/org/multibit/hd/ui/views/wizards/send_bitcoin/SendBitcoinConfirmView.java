package org.multibit.hd.ui.views.wizards.send_bitcoin;

import net.miginfocom.swing.MigLayout;
import org.multibit.hd.core.api.MessageKey;
import org.multibit.hd.ui.views.components.*;
import org.multibit.hd.ui.views.wizards.AbstractWizard;
import org.multibit.hd.ui.views.wizards.AbstractWizardView;

import javax.swing.*;

/**
 * <p>View to provide the following to UI:</p>
 * <ul>
 * <li>Send bitcoin: Confirm</li>
 * </ul>
 *
 * @since 0.0.1
 *         
 */
public class SendBitcoinConfirmView extends AbstractWizardView<SendBitcoinWizardModel, String> {

  // Model
  private String model;

  /**
   * @param wizard The wizard managing the states
   */
  public SendBitcoinConfirmView(AbstractWizard<SendBitcoinWizardModel> wizard, String panelName) {

    super(wizard.getWizardModel(), panelName, MessageKey.CONFIRM_SEND_TITLE);

    PanelDecorator.addCancelPreviousSend(this, wizard);

  }

  @Override
  public JPanel newDataPanel() {

    model = "TODO replace with a proper model";
    setPanelModel(model);

    JPanel panel = Panels.newPanel(new MigLayout(
      "debug,fill,insets 0", // Layout constrains
      "[][]", // Column constraints
      "[]10[]10[]" // Row constraints
    ));

    panel.add(Labels.newConfirmSendAmount(),"span 2,wrap");
    //panel.add(Labels.newConfirmSendNote(getWizardModel().getTransactionModel()),"span 2,wrap");
    panel.add(Labels.newNotes());
    panel.add(TextBoxes.newEnterNotes(),"wrap");
    panel.add(Labels.newEnterPassword());
    panel.add(TextBoxes.newPassword(),"wrap");

    return panel;
  }

  @Override
  public void fireViewEvents() {
    // Do nothing
  }

  @Override
  public boolean updatePanelModel() {
    // Do nothing - panel model is updated via an action and wizard model is not applicable
    return true;
  }

}
