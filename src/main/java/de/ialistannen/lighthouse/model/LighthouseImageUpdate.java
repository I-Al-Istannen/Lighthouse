package de.ialistannen.lighthouse.model;

import de.ialistannen.lighthouse.registry.RemoteImageMetadata;
import java.util.List;
import java.util.Optional;

/**
 * Information about an available image update.
 *
 * @param sourceImageId the image id as used by docker commands
 * @param sourceImageNames all repo tags (name:tag) this image is known as
 * @param remoteManifestDigest the digest of the remote image's manifest
 * @param imageIdentifier the friendly name of the image (as used by {@code "docker inspect image"} etc) and tag
 * @param remoteImageMetadata metadata about the current remote image, if available
 */
public record LighthouseImageUpdate(
  String sourceImageId,
  List<String> sourceImageNames,
  String remoteManifestDigest,
  ImageIdentifier imageIdentifier,
  Optional<RemoteImageMetadata> remoteImageMetadata
) {

}
