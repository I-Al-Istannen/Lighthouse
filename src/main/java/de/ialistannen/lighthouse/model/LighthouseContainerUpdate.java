package de.ialistannen.lighthouse.model;

import java.util.List;

/**
 * An update for a single container. This record stores the name(s) of the container and information about the update.
 */
public record LighthouseContainerUpdate(
  List<String> names,
  LighthouseImageUpdate imageUpdate
) {

}
