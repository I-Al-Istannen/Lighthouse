package de.ialistannen.lighthouse.registry;

import java.time.Instant;

public record RemoteImageMetadata(
  String updatedBy,
  Instant updateTime
) {

}
