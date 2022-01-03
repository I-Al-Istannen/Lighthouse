package de.ialistannen.lighthouse.model;

import java.util.List;

/**
 * An update for a single container. This record stores the name(s) of the container and information about the update.
 *
 * @param names the names of the container
 * @param imageUpdate the image update
 */
public record LighthouseContainerUpdate(
  List<String> names,
  LighthouseImageUpdate imageUpdate
) {

}
