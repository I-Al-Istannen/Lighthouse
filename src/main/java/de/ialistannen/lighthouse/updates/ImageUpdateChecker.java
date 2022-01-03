package de.ialistannen.lighthouse.updates;

import static de.ialistannen.lighthouse.updates.ContainerWithBaseUtils.getBaseImage;
import static de.ialistannen.lighthouse.updates.ContainerWithBaseUtils.getBaseRepoTag;
import static de.ialistannen.lighthouse.updates.ContainerWithBaseUtils.getBaseTag;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.model.Container;
import de.ialistannen.lighthouse.metadata.MetadataFetcher;
import de.ialistannen.lighthouse.model.EnrollmentMode;
import de.ialistannen.lighthouse.model.LighthouseImageUpdate;
import de.ialistannen.lighthouse.registry.DigestFetchException;
import de.ialistannen.lighthouse.registry.DockerLibraryHelper;
import de.ialistannen.lighthouse.registry.DockerRegistry;
import de.ialistannen.lighthouse.registry.TokenFetchException;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds local images that build on a (now out-of-date) base image.
 */
public class ImageUpdateChecker {

  private static final Logger LOGGER = LoggerFactory.getLogger(ImageUpdateChecker.class);

  private final DockerClient client;
  private final DockerRegistry dockerRegistry;
  private final MetadataFetcher metadataFetcher;
  private final EnrollmentMode enrollmentMode;
  private final DockerLibraryHelper libraryHelper;

  public ImageUpdateChecker(
    DockerClient client,
    DockerRegistry dockerRegistry,
    MetadataFetcher metadataFetcher,
    EnrollmentMode enrollmentMode,
    DockerLibraryHelper libraryHelper
  ) {
    this.client = client;
    this.dockerRegistry = dockerRegistry;
    this.metadataFetcher = metadataFetcher;
    this.enrollmentMode = enrollmentMode;
    this.libraryHelper = libraryHelper;
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
   * @throws DigestFetchException if the remote denied serving the digest
   * @throws TokenFetchException if the auth token could not be retrieved
   */
  public List<LighthouseImageUpdate> check() throws IOException, URISyntaxException, InterruptedException {
    List<LighthouseImageUpdate> updates = new ArrayList<>(checkBaseTaggedContainers());
    updates.addAll(checkBasicContainers());
    return updates;
  }

  private List<LighthouseImageUpdate> checkBasicContainers()
    throws IOException, URISyntaxException, InterruptedException {
    List<LighthouseImageUpdate> updates = new ArrayList<>();

    for (ContainerWithRemoteInfo info : getContainersWithRemoteInfo(getParticipatingBasicContainers())) {
      if (info.baseImageOutdated()) {
        updates.add(info.toUpdate(metadataFetcher));
      }
    }

    return updates;
  }

  private List<LighthouseImageUpdate> checkBaseTaggedContainers()
    throws InterruptedException, IOException, URISyntaxException {

    List<LighthouseImageUpdate> updates = new ArrayList<>();

    Collection<ContainerWithBase> participatingContainers = getParticipatingBaseTaggedContainers();
    pullUnknownBaseImages(participatingContainers);

    for (ContainerWithRemoteInfo info : getContainersWithRemoteInfo(participatingContainers)) {
      info = updateBaseImageIfNeeded(info);

      if (isContainerUpToDate(info)) {
        continue;
      }

      LOGGER.info(
        "Container '{}' is out of date for image '{}'",
        info.container().container().getNames(),
        info.container().baseRepoTag()
      );

      updates.add(info.toUpdate(metadataFetcher));
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

  private Collection<ContainerWithRemoteInfo> getContainersWithRemoteInfo(
    Collection<ContainerWithBase> participatingContainers
  ) throws IOException, URISyntaxException, InterruptedException {

    Collection<ContainerWithRemoteInfo> foo = new HashSet<>();
    for (ContainerWithBase withBase : participatingContainers) {
      Container container = withBase.container();
      if (container.getImageId() == null) {
        LOGGER.warn("Container '{}' has no image id", (Object) container.getNames());
        continue;
      }
      InspectImageResponse inspect = client
        .inspectImageCmd(withBase.baseImage())
        .exec();
      if (inspect.getRepoDigests() == null || inspect.getRepoDigests().isEmpty()) {
        LOGGER.warn("Could not find repo digest for image '{}'", withBase.baseRepoTag());
        continue;
      }

      String remoteDigest = dockerRegistry.getDigest(withBase.baseImage(), withBase.baseTag());
      InspectImageResponse localBaseImage = client.inspectImageCmd(container.getImageId()).exec();
      foo.add(new ContainerWithRemoteInfo(
        withBase,
        remoteDigest,
        inspect,
        localBaseImage
      ));
    }

    return foo;
  }

  private void pullUnknownBaseImages(Collection<ContainerWithBase> participatingContainers)
    throws InterruptedException {
    Set<String> knownImages = client.listImagesCmd()
      .exec()
      .stream()
      .filter(it -> it.getRepoTags() != null)
      .flatMap(it -> Arrays.stream(it.getRepoTags()))
      .collect(Collectors.toSet());

    for (ContainerWithBase withBase : participatingContainers) {
      String image = withBase.baseImage();
      String tag = withBase.baseTag();

      if (knownImages.contains(image + ":" + tag)) {
        LOGGER.debug("Found base image '{}':'{}' for {}", image, tag, withBase.container().getNames());
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
    Container container = info.container().container();

    if (info.baseImageOutdated()) {
      LOGGER.info("Updating base image '{}'", getBaseRepoTag(container));
      pullBaseImage(getBaseImage(container), getBaseTag(container));

      // re-fetch image
      return new ContainerWithRemoteInfo(
        info.container(),
        info.currentRemoteDigest(),
        client.inspectImageCmd(getBaseRepoTag(container)).exec(),
        info.containerImage()
      );
    } else {
      LOGGER.debug("Base image '{}' is up to date", getBaseRepoTag(container));
    }
    return info;
  }

  private Collection<ContainerWithBase> getParticipatingBaseTaggedContainers() {
    return client.listContainersCmd()
      .exec()
      .stream()
      .filter(enrollmentMode::isParticipating)
      .filter(ContainerWithBaseUtils::isTaggedWithBase)
      .flatMap(container -> withBase(container).stream())
      .collect(Collectors.toMap(
        withBase -> withBase.container().getImageId(),
        withBase -> withBase,
        (a, b) -> a
      )).values();
  }

  private Collection<ContainerWithBase> getParticipatingBasicContainers() {
    return client.listContainersCmd()
      .exec()
      .stream()
      .filter(enrollmentMode::isParticipating)
      .filter(Predicate.not(ContainerWithBaseUtils::isTaggedWithBase))
      .flatMap(container -> withBase(container).stream())
      .collect(Collectors.toMap(
        withBase -> withBase.container().getImageId(),
        withBase -> withBase,
        (a, b) -> a
      )).values();
  }

  private Optional<ContainerWithBase> withBase(Container container) {
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
    String repoTag = imageResponse.getRepoTags().get(1);
    String[] parts = repoTag.split(":");
    String image = libraryHelper.getFriendlyImageName(parts[0]);
    String tag = parts[1];

    return Optional.of(new ContainerWithBase(container, image, tag));
  }

  private record ContainerWithRemoteInfo(
    ContainerWithBase container,
    String currentRemoteDigest,
    InspectImageResponse localBaseImage,
    InspectImageResponse containerImage
  ) {

    public boolean baseImageOutdated() {
      return Objects.requireNonNull(localBaseImage().getRepoDigests())
        .stream()
        .noneMatch(it -> it.endsWith(currentRemoteDigest()));
    }

    public LighthouseImageUpdate toUpdate(MetadataFetcher metadataFetcher)
      throws IOException, URISyntaxException, InterruptedException {

      return new LighthouseImageUpdate(
        container().container().getImageId(),
        containerImage().getRepoTags(),
        currentRemoteDigest(),
        container().baseImage(),
        container().baseTag(),
        metadataFetcher.fetch(container().baseImage(), container().baseTag())
      );
    }
  }

}
