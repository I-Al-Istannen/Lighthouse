package de.ialistannen.shipit.notifier;

import de.ialistannen.shipit.docker.ShipItContainerUpdate;
import java.util.List;

public interface Notifier {

  void notify(List<ShipItContainerUpdate> updates);

  /**
   * Notifies the user that execution failed.
   *
   * @param e the error
   */
  void notify(Exception e);
}
