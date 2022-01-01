package de.ialistannen.lighthouse.docker;

import de.ialistannen.lighthouse.hub.ImageInformation;
import java.util.List;

/**
 * Information about an available image update.
 */
public record LighthouseImageUpdate(
  String sourceImageId,
  List<String> sourceImageNames,
  String remoteImageId,
  ImageInformation remoteImageInfo
) {

}
