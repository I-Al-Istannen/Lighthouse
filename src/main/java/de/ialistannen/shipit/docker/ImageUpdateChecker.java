package de.ialistannen.shipit.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.model.Container;
import de.ialistannen.shipit.hub.DockerRegistryClient;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
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
    List<String> localImageIds = client.listContainersCmd()
      .exec()
      .stream()
      .map(Container::getImageId)
      .distinct()
      .toList();

    List<ShipItImageUpdate> updates = new ArrayList<>();

    for (String imageId : localImageIds) {
      String baseRepoTag = findBaseRepoTag(imageId);
      String remoteDigest = dockerRegistryClient.fetchImageDigestForTag(baseRepoTag);

      InspectImageResponse inspect = client.inspectImageCmd(baseRepoTag).exec();
      if (inspect.getRepoDigests() == null || inspect.getRepoDigests().isEmpty()) {
        LOGGER.warn("Could not find repo digest for image '{}'", imageId);
        continue;
      }

      boolean isUpToDate = inspect.getRepoDigests()
        .stream()
        .anyMatch(it -> it.endsWith(remoteDigest));

      if (isUpToDate) {
        LOGGER.info("Image '{}' is up to date.", baseRepoTag);
        continue;
      }

      updates.add(
        new ShipItImageUpdate(
          imageId,
          client.inspectImageCmd(imageId).exec().getRepoTags(),
          remoteDigest,
          dockerRegistryClient.fetchImageInformationForTag(baseRepoTag)
        )
      );
    }

    return updates;
  }

  private String findBaseRepoTag(String imageId) {
    String baseRepoTag = imageId;

    String currentLayer = imageId;
    while (currentLayer != null && !currentLayer.isEmpty()) {
      currentLayer = currentLayer.replace("sha256:", "");

      InspectImageResponse currentImage = client.inspectImageCmd(currentLayer).exec();
      if (currentImage.getRepoTags() != null && !currentImage.getRepoTags().isEmpty()) {
        baseRepoTag = currentImage.getRepoTags().get(0);
      }

      currentLayer = currentImage.getParent();
    }

    return baseRepoTag;
  }

}
