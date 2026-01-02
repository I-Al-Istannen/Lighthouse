package de.ialistannen.lighthouse.model;

import de.ialistannen.lighthouse.registry.RemoteImageMetadata;
import java.util.List;
import java.util.Optional;

/**
 * Information about an available tag update (version upgrade).
 *
 * @param names the names of the containers using this image
 * @param currentTag the current tag the container is using
 * @param newTag the new tag that is available
 * @param imageIdentifier the image identifier with the new tag
 * @param remoteImageMetadata metadata about the new remote image, if available
 */
public record LighthouseTagUpdate(
  List<String> names,
  String currentTag,
  String newTag,
  ImageIdentifier imageIdentifier,
  Optional<RemoteImageMetadata> remoteImageMetadata
) {

}

