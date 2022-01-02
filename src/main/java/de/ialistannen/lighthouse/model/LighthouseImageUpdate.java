package de.ialistannen.lighthouse.model;

import de.ialistannen.lighthouse.registry.RemoteImageMetadata;
import java.util.List;
import java.util.Optional;

/**
 * Information about an available image update.
 */
public record LighthouseImageUpdate(
  String sourceImageId,
  List<String> sourceImageNames,
  String remoteImageId,
  String imageName,
  String tag,
  Optional<RemoteImageMetadata> remoteImageMetadata
) {

}
