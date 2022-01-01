package de.ialistannen.lighthouse.hub;

import java.time.Instant;

/**
 * Basic information about a published image.
 */
public record ImageInformation(
  String lastUpdaterName,
  Instant lastUpdated,
  String imageName,
  String tag
) {

}
