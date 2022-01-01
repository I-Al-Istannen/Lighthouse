package de.ialistannen.shipit.docker;

import static de.ialistannen.shipit.docker.ContainerUtils.getBaseRepoTag;
import static de.ialistannen.shipit.docker.ContainerUtils.getImage;
import static de.ialistannen.shipit.docker.ContainerUtils.getTag;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.model.Container;
import de.ialistannen.shipit.hub.DockerRegistryClient;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds local images that build on a (now out-of-date) base image.
 */
public class ImageUpdateChecker {

  private static final Logger LOGGER = LoggerFactory.getLogger(ImageUpdateChecker.class);

  private final DockerClient client;
  private final DockerRegistryClient dockerRegistryClient;

  public ImageUpdateChecker(DockerClient client, DockerRegistryClient dockerRegistryClient) {
    this.client = client;
    this.dockerRegistryClient = dockerRegistryClient;
  }

  /**
   * Checks for outdated images in any participating container. This will :
   * <ol>
   *   <li>loop over all containers<li>
   *   <li>check for the presence of the correct label</li>
   *   <li>check if their base image is up-to-date and updating it if necessary</li>
   *   <li>check if the container uses the up-to-date base image</li>
   * </ol>
   *
   * @return all found image updates
   * @throws IOException if an error happens looking up remote information
   * @throws URISyntaxException if the base image contains invalid characters
   * @throws InterruptedException ?
   * @throws de.ialistannen.shipit.hub.DigestFetchException if the remote denied serving the digest
   * @throws de.ialistannen.shipit.hub.TokenFetchException if the auth token could not be retrieved
   */
  public List<ShipItImageUpdate> check() throws IOException, URISyntaxException, InterruptedException {
    List<ShipItImageUpdate> updates = new ArrayList<>();

    Collection<Container> participatingContainers = getParticipatingContainers();
    pullUnknownBaseImages(participatingContainers);

    for (ContainerWithRemoteInfo info : getContainersWithRemoteInfo(participatingContainers)) {
      info = updateBaseImageIfNeeded(info);

      if (isContainerUpToDate(info)) {
        continue;
      }

      LOGGER.info(
        "Container '{}' is out of date for image '{}'",
        info.container().getNames(),
        getBaseRepoTag(info.container())
      );

      updates.add(
        new ShipItImageUpdate(
          info.container().getImageId(),
          info.containerImage().getRepoTags(),
          info.currentRemoteDigest(),
          dockerRegistryClient.fetchImageInformationForTag(getBaseRepoTag(info.container()))
        )
      );
    }

    return updates;
  }

  @SuppressWarnings("ConstantConditions")
  private boolean isContainerUpToDate(ContainerWithRemoteInfo info) {
    Set<String> containerLayers = new HashSet<>(info.containerImage().getRootFS().getLayers());

    for (String layer : info.localBaseImage().getRootFS().getLayers()) {
      if (!containerLayers.contains(layer)) {
        LOGGER.debug("Layer '{}' is missing in container, marking it as outdated", layer);
        return false;
      }
    }

    return true;
  }

  private Collection<ContainerWithRemoteInfo> getContainersWithRemoteInfo(Collection<Container> participatingContainers)
    throws IOException, URISyntaxException, InterruptedException {

    Collection<ContainerWithRemoteInfo> foo = new HashSet<>();
    for (Container participatingContainer : participatingContainers) {
      if (participatingContainer.getImageId() == null) {
        LOGGER.warn("Container '{}' has no image id", (Object) participatingContainer.getNames());
        continue;
      }
      InspectImageResponse inspect = client.inspectImageCmd(getBaseRepoTag(participatingContainer)).exec();
      if (inspect.getRepoDigests() == null || inspect.getRepoDigests().isEmpty()) {
        LOGGER.warn("Could not find repo digest for image '{}'", getBaseRepoTag(participatingContainer));
        continue;
      }

      foo.add(new ContainerWithRemoteInfo(
        participatingContainer,
        dockerRegistryClient.fetchImageDigestForTag(getBaseRepoTag(participatingContainer)),
        inspect,
        client.inspectImageCmd(participatingContainer.getImageId()).exec()
      ));
    }

    return foo;
  }

  private void pullUnknownBaseImages(Collection<Container> participatingContainers) throws InterruptedException {
    Set<String> knownImages = client.listImagesCmd()
      .exec()
      .stream()
      .filter(it -> it.getRepoTags() != null)
      .flatMap(it -> Arrays.stream(it.getRepoTags()))
      .collect(Collectors.toSet());

    for (Container container : participatingContainers) {
      String image = getImage(container);
      String tag = getTag(container);

      if (knownImages.contains(image + ":" + tag)) {
        LOGGER.debug("Found base image '{}':'{}' for {}", image, tag, container.getNames());
        continue;
      }

      pullBaseImage(image, tag);
    }
  }

  private void pullBaseImage(String image, String tag) throws InterruptedException {
    LOGGER.info("Pulling base image '{}':'{}'", image, tag);
    client.pullImageCmd(image)
      .withTag(tag)
      .exec(new PullImageResultCallback())
      .awaitCompletion(5, TimeUnit.MINUTES);
  }

  private ContainerWithRemoteInfo updateBaseImageIfNeeded(ContainerWithRemoteInfo info) throws InterruptedException {
    boolean isBaseImageUpToDate = Objects.requireNonNull(info.localBaseImage().getRepoDigests())
      .stream()
      .anyMatch(it -> it.endsWith(info.currentRemoteDigest()));

    if (!isBaseImageUpToDate) {
      LOGGER.info("Updating base image '{}'", getBaseRepoTag(info.container()));
      pullBaseImage(getImage(info.container()), getTag(info.container()));

      // re-fetch image
      return new ContainerWithRemoteInfo(
        info.container(),
        info.currentRemoteDigest(),
        client.inspectImageCmd(getBaseRepoTag(info.container())).exec(),
        info.containerImage()
      );
    } else {
      LOGGER.debug("Base image '{}' is up to date", getBaseRepoTag(info.container()));
    }
    return info;
  }

  private Collection<Container> getParticipatingContainers() {
    return client.listContainersCmd()
      .exec()
      .stream()
      .filter(ContainerUtils::isParticipating)
      .collect(Collectors.toMap(
        Container::getImageId,
        container -> container,
        (a, b) -> a
      )).values();
  }


  private record ContainerWithRemoteInfo(
    Container container,
    String currentRemoteDigest,
    InspectImageResponse localBaseImage,
    InspectImageResponse containerImage
  ) {

  }

}
