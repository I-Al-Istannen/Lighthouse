package de.ialistannen.lighthouse.updates;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.model.Container;
import de.ialistannen.lighthouse.registry.DockerLibraryHelper;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

record ContainerWithBase(
  Container container,
  String baseImage,
  String baseTag
) {

  private static final Logger LOGGER = LoggerFactory.getLogger(ContainerWithBase.class);

  public String baseRepoTag() {
    return baseImage + ":" + baseTag;
  }

  /**
   * @param libraryHelper the library helper to normalize names
   * @return the name of the image+tag in a way that can be passed to {@code "docker image inspect"}
   */
  public String inspectImageName(DockerLibraryHelper libraryHelper) {
    return libraryHelper.getFriendlyImageName(baseImage) + ":" + baseTag;
  }

  /**
   * Creates a {@link ContainerWithBase} for a docker container.
   *
   * @param client the docker client for requests
   * @param libraryHelper the library helper to normalize names
   * @param container the container to convert
   * @return the container with base image
   */
  public static Optional<ContainerWithBase> forContainer(
    DockerClient client,
    DockerLibraryHelper libraryHelper,
    Container container
  ) {
    if (ContainerWithBaseUtils.isTaggedWithBase(container)) {
      return Optional.of(
        new ContainerWithBase(
          container,
          libraryHelper.getFriendlyImageName(ContainerWithBaseUtils.getBaseImage(container)),
          ContainerWithBaseUtils.getBaseTag(container)
        )
      );
    }
    InspectImageResponse imageResponse = client.inspectImageCmd(container.getImage()).exec();
    if (imageResponse.getRepoTags() == null || imageResponse.getRepoTags().isEmpty()) {
      LOGGER.info(
        "Enrolled container '{}' has an unlabeled image and no 'lighthouse.base' tag",
        (Object) container.getNames()
      );
      return Optional.empty();
    }
    String repoTag = imageResponse.getRepoTags().get(0);
    String[] parts = repoTag.split(":");
    String image = libraryHelper.getFriendlyImageName(parts[0]);
    String tag = parts[1];

    return Optional.of(new ContainerWithBase(container, image, tag));
  }
}
