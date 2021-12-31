package de.ialistannen.shipit.hub;

import java.time.Instant;

public record ImageInformation(
  String lastUpdaterName,
  Instant lastUpdated,
  String imageName,
  String tag
) {

}
