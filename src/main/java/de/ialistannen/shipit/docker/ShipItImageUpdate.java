package de.ialistannen.shipit.docker;

import de.ialistannen.shipit.hub.ImageInformation;
import java.util.List;

public record ShipItImageUpdate(
  String sourceImageId,
  List<String> sourceImageNames,
  String remoteImageId,
  ImageInformation remoteImageInfo
) {

}
