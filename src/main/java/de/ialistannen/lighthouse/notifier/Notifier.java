package de.ialistannen.lighthouse.notifier;

import de.ialistannen.lighthouse.model.LighthouseContainerUpdate;
import de.ialistannen.lighthouse.model.LighthouseTagUpdate;
import java.util.List;

public interface Notifier {

  void notify(List<LighthouseContainerUpdate> updates);

  /**
   * Notifies the user about tag updates (version upgrades).
   *
   * @param tagUpdates the tag updates
   */
  void notifyTags(List<LighthouseTagUpdate> tagUpdates);

  /**
   * Notifies the user that execution failed.
   *
   * @param e the error
   */
  void notify(Throwable e);
}
