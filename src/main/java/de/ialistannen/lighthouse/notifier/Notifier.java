package de.ialistannen.lighthouse.notifier;

import de.ialistannen.lighthouse.docker.LighthouseContainerUpdate;
import java.util.List;

public interface Notifier {

  void notify(List<LighthouseContainerUpdate> updates);

  /**
   * Notifies the user that execution failed.
   *
   * @param e the error
   */
  void notify(Exception e);
}
