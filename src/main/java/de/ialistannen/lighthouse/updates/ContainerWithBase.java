package de.ialistannen.lighthouse.updates;

import com.github.dockerjava.api.model.Container;
import de.ialistannen.lighthouse.registry.DockerLibraryHelper;

/**
 * A container with its base image.
 *
 * @param container the container itself
 * @param baseImage the base image's {@link DockerLibraryHelper#getFriendlyImageName(String)} friendly name}
 * @param baseTag the base image's tag
 */
record ContainerWithBase(
  Container container,
  String baseImage,
  String baseTag
) {

  public String baseRepoTag() {
    return baseImage + ":" + baseTag;
  }
}
