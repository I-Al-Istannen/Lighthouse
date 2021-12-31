package de.ialistannen.shipit.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.model.Container;
import de.ialistannen.shipit.hub.DockerRegistryClient;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ImageUpdateChecker {

  private static final Logger LOGGER = LoggerFactory.getLogger(ImageUpdateChecker.class);

  private final DockerClient client;
  private final DockerRegistryClient dockerRegistryClient;

  public ImageUpdateChecker(DockerClient client, DockerRegistryClient dockerRegistryClient) {
    this.client = client;
    this.dockerRegistryClient = dockerRegistryClient;
  }

  public List<ShipItImageUpdate> check() throws IOException, URISyntaxException, InterruptedException {
    Map<String, Container> localImageIds = client.listContainersCmd()
      .exec()
      .stream()
      .filter(it -> it.getLabels().containsKey("SHIP_IT_IMAGE") && it.getLabels().containsKey("SHIP_IT_TAG"))
      .collect(Collectors.toMap(
        Container::getImageId,
        container -> container,
        (a, b) -> a
      ));

    Set<String> knownImages = client.listImagesCmd()
      .exec()
      .stream()
      .flatMap(it -> Arrays.stream(it.getRepoTags()))
      .collect(Collectors.toSet());

    for (Container container : localImageIds.values()) {
      String image = container.getLabels().get("SHIP_IT_IMAGE");
      String tag = container.getLabels().get("SHIP_IT_TAG");

      if (knownImages.contains(image + ":" + tag)) {
        LOGGER.debug("Found base image '{}':'{}' for {}", image, tag, container.getNames());
        continue;
      }

      LOGGER.info("Cloning base image '{}':'{}'", image, tag);
      client.pullImageCmd(image)
        .withTag(tag)
        .exec(new PullImageResultCallback())
        .awaitCompletion(5, TimeUnit.MINUTES);
    }

    List<ShipItImageUpdate> updates = new ArrayList<>();

    for (var entry : localImageIds.entrySet()) {
      String containerImageId = entry.getKey();
      Container container = entry.getValue();
      String image = container.getLabels().get("SHIP_IT_IMAGE");
      String tag = container.getLabels().get("SHIP_IT_TAG");
      String baseRepoTag = image + ":" + tag;

      String remoteDigest = dockerRegistryClient.fetchImageDigestForTag(baseRepoTag);

      InspectImageResponse inspect = client.inspectImageCmd(baseRepoTag).exec();
      if (inspect.getRepoDigests() == null || inspect.getRepoDigests().isEmpty()) {
        LOGGER.warn("Could not find repo digest for image '{}'", baseRepoTag);
        continue;
      }

      boolean isUpToDate = inspect.getRepoDigests()
        .stream()
        .anyMatch(it -> it.endsWith(remoteDigest));

      InspectImageResponse containerImageInspect = client.inspectImageCmd(containerImageId).exec();

      if (isUpToDate) {
        LOGGER.info("Base image '{}' is up to date", baseRepoTag);
        Set<String> containerLayers = new HashSet<>(containerImageInspect.getRootFS().getLayers());
        for (String layer : inspect.getRootFS().getLayers()) {
          if (!containerLayers.contains(layer)) {
            updates.add(
              new ShipItImageUpdate(
                containerImageId,
                client.inspectImageCmd(containerImageId).exec().getRepoTags(),
                remoteDigest,
                dockerRegistryClient.fetchImageInformationForTag(baseRepoTag),
                UpdateKind.CONTAINER_USES_OUTDATED_BASE_IMAGE
              )
            );
            break;
          }
        }
        continue;
      }
      LOGGER.info("Base image '{}' is outdated", baseRepoTag);

      updates.add(
        new ShipItImageUpdate(
          containerImageId,
          client.inspectImageCmd(containerImageId).exec().getRepoTags(),
          remoteDigest,
          dockerRegistryClient.fetchImageInformationForTag(baseRepoTag),
          UpdateKind.REFERENCE_IMAGE_IS_OUTDATED
        )
      );
    }

    return updates;
  }

}
