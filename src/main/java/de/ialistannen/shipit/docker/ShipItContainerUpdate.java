package de.ialistannen.shipit.docker;

import java.util.List;

public record ShipItContainerUpdate(
  List<String> names,
  ShipItImageUpdate imageUpdate
) {

}
