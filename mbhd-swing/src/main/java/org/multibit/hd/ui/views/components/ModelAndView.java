package org.multibit.hd.ui.views.components;

import org.multibit.hd.core.services.CoreServices;
import org.multibit.hd.ui.models.Model;
import org.multibit.hd.ui.views.View;

/**
 * <p>Value object to provide the following to UI:</p>
 * <ul>
 * <li>Provision of a model and view for a component</li>
 * </ul>
 *
 * <p>All ModelAndView components are registered for UI events by default (both model and view)</p>
 *
 * @since 0.0.1
 *  
 */
public class ModelAndView<M extends Model, V extends View> {

  private final M model;
  private final V view;

  public ModelAndView(M model, V view) {

    this.model = model;
    this.view = view;

    // Convenience method to ensure UI events work out of the box
    CoreServices.uiEventBus.register(view);
    CoreServices.uiEventBus.register(model);

  }

  /**
   * @return The model (any changes will cause an immediate update event to the view)
   */
  public M getModel() {
    return model;
  }

  /**
   * @return The view (will reflect the model state)
   */
  public V getView() {
    return view;
  }
}
