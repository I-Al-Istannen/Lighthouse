package de.ialistannen.lighthouse.updates;

import com.github.dockerjava.api.model.Container;
import de.ialistannen.lighthouse.model.ImageIdentifier;
import de.ialistannen.lighthouse.registry.DockerLibraryHelper;

/**
 * A container with its base image.
 *
 * @param container the container itself
 * @param baseImage the base image's {@link DockerLibraryHelper#getFriendlyImageName(String)} friendly name} and tag
 */
record ContainerWithBase(
  Container container,
  ImageIdentifier baseImage
) {

  /**
   * Returns the "image:tag" name (as it appears in the RepoTag field) for an image. This is equivalent to
   * {@link ImageIdentifier#nameWithTag()}
   *
   * @return the RepoTag of the base image
   */
  public String baseImageRepoTag() {
    return baseImage.nameWithTag();
  }
}
