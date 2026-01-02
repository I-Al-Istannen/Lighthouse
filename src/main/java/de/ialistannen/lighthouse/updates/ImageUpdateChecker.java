package de.ialistannen.lighthouse.updates;

import static de.ialistannen.lighthouse.updates.ContainerWithBaseUtils.getBaseImageIdentifier;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.model.Container;
import de.ialistannen.lighthouse.metadata.MetadataFetcher;
import de.ialistannen.lighthouse.model.BaseImageUpdateStrategy;
import de.ialistannen.lighthouse.model.EnrollmentMode;
import de.ialistannen.lighthouse.model.ImageIdentifier;
import de.ialistannen.lighthouse.model.LighthouseImageUpdate;
import de.ialistannen.lighthouse.model.LighthouseTagUpdate;
import de.ialistannen.lighthouse.notifier.Notifier;
import de.ialistannen.lighthouse.registry.DigestFetchException;
import de.ialistannen.lighthouse.registry.DockerLibraryHelper;
import de.ialistannen.lighthouse.registry.DockerRegistry;
import de.ialistannen.lighthouse.registry.TokenFetchException;
import de.ialistannen.lighthouse.versioning.VersionParser;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.semver4j.Semver;
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
  private final BaseImageUpdateStrategy baseImageUpdateStrategy;
  private final Notifier notifier;

  public ImageUpdateChecker(
    DockerClient client,
    DockerRegistry dockerRegistry,
    MetadataFetcher metadataFetcher,
    EnrollmentMode enrollmentMode,
    DockerLibraryHelper libraryHelper,
    BaseImageUpdateStrategy baseImageUpdateStrategy,
    Notifier notifier
  ) {
    this.client = client;
    this.dockerRegistry = dockerRegistry;
    this.metadataFetcher = metadataFetcher;
    this.enrollmentMode = enrollmentMode;
    this.libraryHelper = libraryHelper;
    this.baseImageUpdateStrategy = baseImageUpdateStrategy;
    this.notifier = notifier;
  }

  /**
   * Checks for outdated images in any participating container. This will :
   * <ol>
   *   <li>loop over all containers<li>
   *   <li>check for the presence of the correct label</li>
   *   <li>check if their base image is up to date and updating it if necessary</li>
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
  public Collection<LighthouseImageUpdate> check() throws IOException, URISyntaxException, InterruptedException {
    Set<LighthouseImageUpdate> updates = new HashSet<>(checkBaseTaggedContainers());
    updates.addAll(checkBasicContainers());
    return updates;
  }

  public List<LighthouseTagUpdate> checkTags() throws IOException, URISyntaxException, InterruptedException {
    LOGGER.info("Checking for tag updates...");
    Set<ContainerWithBase> containers = new HashSet<>(getParticipatingBaseTaggedContainers());
    containers.addAll(getParticipatingBasicContainers());

    List<LighthouseTagUpdate> updates = new ArrayList<>();

    Map<String, List<String>> tagsPerImage = new HashMap<>();

    for (var info : containers) {
      String strategyString = info.container().getLabels().get("lighthouse.tag-check.strategy");
      if (strategyString == null) {
        LOGGER.debug("No tag check strategy for container '{}'", Arrays.toString(info.container().getNames()));
        continue;
      }
      VersionParser versionParser;
      try {
        versionParser = VersionParser.fromString(strategyString);
      } catch (IllegalArgumentException e) {
        LOGGER.warn(
          "Container '{}' has an invalid tag check strategy '{}'",
          Arrays.toString(info.container().getNames()),
          strategyString,
          e
        );
        continue;
      }

      LOGGER.debug("Checking tags for container '{}'", Arrays.toString(info.container().getNames()));
      String tag = info.baseImage().tag();
      Semver containerVersion;
      try {
        containerVersion = versionParser.parse(tag);
      } catch (IllegalArgumentException e) {
        LOGGER.warn(
          "Container '{}' has a tag '{}' that could not be parsed with strategy '{}'",
          Arrays.toString(info.container().getNames()),
          tag,
          strategyString,
          e
        );
        continue;
      }

      if (!tagsPerImage.containsKey(info.baseImage().image())) {
        String image = info.baseImage().image();
        try {
          List<String> tags = dockerRegistry.getTags(image);
          tagsPerImage.put(image, tags);
        } catch (Exception e) {
          LOGGER.warn("Failed to fetch tags for image '{}'", image, e);
          tagsPerImage.put(image, List.of());
        }
      }

      List<String> availableTags = new ArrayList<>(tagsPerImage.getOrDefault(info.baseImage().image(), List.of()));
      LOGGER.debug("Found tags {} for image '{}'", availableTags, info.baseImage().image());

      String keepRegex = info.container().getLabels().get("lighthouse.tag-check.keep");
      if (keepRegex != null) {
        availableTags.removeIf(it -> !it.matches(keepRegex));
      }
      String ignoreRegex = info.container().getLabels().get("lighthouse.tag-check.ignore");
      if (ignoreRegex != null) {
        availableTags.removeIf(it -> it.matches(ignoreRegex));
      }

      Optional<Map.Entry<Semver, String>> newestVersion = availableTags.stream()
        .distinct()
        .flatMap(it -> {
          try {
            return Stream.of(Map.entry(versionParser.parse(it), it));
          } catch (IllegalArgumentException e) {
            LOGGER.debug(
              "Skipping tag '{}' for container '{}' as it could not be parsed with strategy '{}'",
              it,
              Arrays.toString(info.container().getNames()),
              strategyString
            );
            return Stream.empty();
          }
        })
        .max(Entry.comparingByKey());
      if (newestVersion.isEmpty()) {
        continue;
      }
      Entry<Semver, String> newestEntry = newestVersion.orElseThrow();

      if (newestEntry.getKey().isLowerThanOrEqualTo(containerVersion)) {
        continue;
      }
      LOGGER.info(
        "Container '{}' has a newer tag '{}' (parsed as {}) available for image '{}' (current: '{}')",
        Arrays.toString(info.container().getNames()),
        newestEntry.getValue(),
        newestEntry.getKey(),
        info.baseImage().image(),
        tag
      );

      updates.add(
        new LighthouseTagUpdate(
          Arrays.asList(info.container().getNames()),
          info.baseImage().tag(),
          newestEntry.getValue(),
          info.baseImage(),
          metadataFetcher.fetch(new ImageIdentifier(info.baseImage().image(), newestEntry.getValue()))
        )
      );
    }

    return updates;
  }

  private Collection<LighthouseImageUpdate> checkBasicContainers()
    throws IOException, URISyntaxException, InterruptedException {
    List<LighthouseImageUpdate> updates = new ArrayList<>();

    for (ContainerWithRemoteInfo info : getContainersWithRemoteInfo(getParticipatingBasicContainers())) {
      if (info.baseImageOutdated()) {
        LOGGER.info(
          "Base image '{}' for {} is out of date",
          info.containerImage().getRepoTags(),
          info.container().container().getNames()
        );
        updates.add(info.toUpdate(metadataFetcher));
      } else {
        LOGGER.info(
          "Base image '{}' for {} is up to date",
          info.containerImage().getRepoTags(),
          info.container().container().getNames()
        );
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
      if (baseImageUpdateStrategy.updateOutdated()) {
        info = updateBaseImageIfNeeded(info);
      }

      if (info.baseImageOutdated()) {
        LOGGER.info(
          "Container '{}' has out of date base image '{}' and updating was forbidden. Treating as outdated",
          info.container().container().getNames(),
          info.container().baseImageRepoTag()
        );

        updates.add(info.toUpdate(metadataFetcher));
        continue;
      }

      if (isContainerUpToDate(info)) {
        continue;
      }

      LOGGER.info(
        "Container '{}' is out of date for image '{}'",
        info.container().container().getNames(),
        info.container().baseImageRepoTag()
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
    Collection<ContainerWithBase> containers
  ) {
    Collection<ContainerWithRemoteInfo> result = new HashSet<>();

    for (ContainerWithBase withBase : containers) {
      Container container = withBase.container();
      if (container.getImageId() == null) {
        LOGGER.warn("Container '{}' has no image id", Arrays.toString(container.getNames()));
        continue;
      }

      InspectImageResponse inspect = client
        .inspectImageCmd(withBase.baseImageRepoTag())
        .exec();
      if (inspect.getRepoDigests() == null || inspect.getRepoDigests().isEmpty()) {
        LOGGER.warn("Could not find repo digest for image '{}'", withBase.baseImageRepoTag());
        continue;
      }

      try {
        String remoteDigest = dockerRegistry.getDigest(withBase.baseImage().image(), withBase.baseImage().tag());
        InspectImageResponse localBaseImage = client.inspectImageCmd(container.getImageId()).exec();
        result.add(new ContainerWithRemoteInfo(
          withBase,
          remoteDigest,
          inspect,
          localBaseImage
        ));
      } catch (Exception e) {
        LOGGER.warn("Failed to fetch remote info for {}", Arrays.toString(container.getNames()), e);
        notifier.notify(e);
      }
    }

    return result;
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
      ImageIdentifier image = withBase.baseImage();
      if (knownImages.contains(image.nameWithTag())) {
        LOGGER.debug("Found base image '{}':'{}' for {}", image.image(), image.tag(), withBase.container().getNames());
        continue;
      }
      LOGGER.debug("Pulling image '{}':'{}' for {}", image.image(), image.tag(), withBase.container().getNames());

      pullBaseImage(image);
    }
  }

  private void pullBaseImage(ImageIdentifier identifier) throws InterruptedException {
    LOGGER.info("Pulling base image '{}':'{}'", identifier.image(), identifier.tag());
    client.pullImageCmd(identifier.image())
      .withTag(identifier.tag())
      .exec(new PullImageResultCallback())
      .awaitCompletion(5, TimeUnit.MINUTES);
  }

  private ContainerWithRemoteInfo updateBaseImageIfNeeded(ContainerWithRemoteInfo info) throws InterruptedException {
    Container container = info.container().container();

    if (info.baseImageOutdated()) {
      ImageIdentifier imageIdentifier = getBaseImageIdentifier(container);
      LOGGER.info("Updating base image '{}'", imageIdentifier);
      pullBaseImage(imageIdentifier);

      // re-fetch image
      return new ContainerWithRemoteInfo(
        info.container(),
        info.currentRemoteDigest(),
        client.inspectImageCmd(imageIdentifier.nameWithTag()).exec(),
        info.containerImage()
      );
    } else {
      LOGGER.debug("Base image '{}' is up to date", getBaseImageIdentifier(container));
    }
    return info;
  }

  private Collection<ContainerWithBase> getParticipatingBaseTaggedContainers() {
    return client.listContainersCmd()
      .withShowAll(true)
      .exec()
      .stream()
      .filter(enrollmentMode::isParticipating)
      .filter(ContainerWithBaseUtils::isTaggedWithBase)
      .flatMap(container -> withBase(container).stream())
      .collect(Collectors.toMap(
        withBase -> withBase.container().getImageId(),
        withBase -> withBase,
        (a, _) -> a
      )).values();
  }

  private Collection<ContainerWithBase> getParticipatingBasicContainers() {
    return client.listContainersCmd()
      .withShowAll(true)
      .exec()
      .stream()
      .filter(enrollmentMode::isParticipating)
      .filter(Predicate.not(ContainerWithBaseUtils::isTaggedWithBase))
      .flatMap(container -> withBase(container).stream())
      .collect(Collectors.toMap(
        withBase -> withBase.container().getImageId(),
        withBase -> withBase,
        (a, _) -> a
      )).values();
  }

  private Optional<ContainerWithBase> withBase(Container container) {
    if (ContainerWithBaseUtils.isTaggedWithBase(container)) {
      return Optional.of(
        new ContainerWithBase(
          container,
          getBaseImageIdentifier(container).friendly(libraryHelper)
        )
      );
    }
    LOGGER.debug("Looking at base image for '{}' ({})", container.getNames(), container.getImage());
    InspectImageResponse imageResponse = client.inspectImageCmd(container.getImage()).exec();
    LOGGER.debug("Found base image for '{}': {}", container.getNames(), imageResponse.getRepoTags());
    if (imageResponse.getRepoTags() == null || imageResponse.getRepoTags().isEmpty()) {
      LOGGER.warn(
        "Enrolled container '{}' has an unlabeled image and no 'lighthouse.base' tag",
        (Object) container.getNames()
      );
      return Optional.empty();
    }
    String repoTag = imageResponse.getRepoTags().getFirst();
    ImageIdentifier baseImage = ImageIdentifier.fromString(repoTag).friendly(libraryHelper);

    return Optional.of(new ContainerWithBase(container, baseImage));
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
        metadataFetcher.fetch(container().baseImage())
      );
    }
  }

}
