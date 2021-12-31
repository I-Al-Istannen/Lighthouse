package de.ialistannen.shipit.notifier;

import de.ialistannen.shipit.docker.ShipItContainerUpdate;
import java.util.List;

public interface Notifier {

  void notify(List<ShipItContainerUpdate> updates);
}
