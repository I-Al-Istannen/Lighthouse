package de.ialistannen.shipit.docker;

import java.util.List;

/**
 * An update for a single container. This record stores the name(s) of the container and information about the update.
 */
public record ShipItContainerUpdate(
  List<String> names,
  ShipItImageUpdate imageUpdate
) {

}
