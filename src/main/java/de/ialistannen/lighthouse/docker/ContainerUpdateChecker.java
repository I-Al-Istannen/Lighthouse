package de.ialistannen.lighthouse.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Checks for updates in containers.
 */
public class ContainerUpdateChecker {

  private static final Logger LOGGER = LoggerFactory.getLogger(ContainerUpdateChecker.class);

  private final DockerClient client;
  private final ImageUpdateChecker imageUpdateChecker;

  public ContainerUpdateChecker(DockerClient client, ImageUpdateChecker imageUpdateChecker) {
    this.client = client;
    this.imageUpdateChecker = imageUpdateChecker;
  }

  /**
   * Finds all out-of-date images and all containers using them.
   *
   * @return all container updates that should be applied
   * @throws IOException if an error happens looking up remote information
   * @throws URISyntaxException if the base image contains invalid characters
   * @throws InterruptedException ?
   * @throws de.ialistannen.lighthouse.hub.DigestFetchException if the remote denied serving the digest
   * @throws de.ialistannen.lighthouse.hub.TokenFetchException if the auth token could not be retrieved
   * @see ImageUpdateChecker#check()
   */
  public List<LighthouseContainerUpdate> check() throws IOException, URISyntaxException, InterruptedException {
    List<LighthouseImageUpdate> imageUpdates = imageUpdateChecker.check();
    List<LighthouseContainerUpdate> updates = new ArrayList<>();

    Map<String, LighthouseImageUpdate> imageMap = imageUpdates.stream().collect(Collectors.toMap(
      LighthouseImageUpdate::sourceImageId,
      it -> it
    ));

    for (Container container : client.listContainersCmd().exec()) {
      if (!imageMap.containsKey(container.getImageId())) {
        if (ContainerUtils.isParticipating(container)) {
          LOGGER.info("Container '{}' is up to date", getContainerNames(container));
        }
        continue;
      }
      LighthouseImageUpdate update = imageMap.get(container.getImageId());
      LOGGER.info("Container '{}' has an update ({})", getContainerNames(container), update.remoteImageId());

      updates.add(
        new LighthouseContainerUpdate(
          getContainerNames(container),
          update
        )
      );
    }

    return updates;
  }

  private List<String> getContainerNames(Container container) {
    return Arrays.stream(container.getNames()).map(it -> it.substring(1)).toList();
  }
}
