package de.ialistannen.lighthouse.updates;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import de.ialistannen.lighthouse.model.EnrollmentMode;
import de.ialistannen.lighthouse.model.LighthouseContainerUpdate;
import de.ialistannen.lighthouse.model.LighthouseImageUpdate;
import de.ialistannen.lighthouse.registry.DigestFetchException;
import de.ialistannen.lighthouse.registry.TokenFetchException;
import de.ialistannen.lighthouse.util.LighthouseDetector;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
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
  private final EnrollmentMode enrollmentMode;

  public ContainerUpdateChecker(
    DockerClient client,
    ImageUpdateChecker imageUpdateChecker,
    EnrollmentMode enrollmentMode
  ) {
    this.client = client;
    this.imageUpdateChecker = imageUpdateChecker;
    this.enrollmentMode = enrollmentMode;
  }

  /**
   * Finds all out-of-date images and all containers using them.
   *
   * @return all container updates that should be applied
   * @throws IOException if an error happens looking up remote information
   * @throws URISyntaxException if the base image contains invalid characters
   * @throws InterruptedException ?
   * @throws DigestFetchException if the remote denied serving the digest
   * @throws TokenFetchException if the auth token could not be retrieved
   * @see ImageUpdateChecker#check()
   */
  public List<LighthouseContainerUpdate> check() throws IOException, URISyntaxException, InterruptedException {
    Collection<LighthouseImageUpdate> imageUpdates = imageUpdateChecker.check();
    List<LighthouseContainerUpdate> updates = new ArrayList<>();

    Map<String, LighthouseImageUpdate> imageMap = imageUpdates.stream().collect(Collectors.toMap(
      LighthouseImageUpdate::sourceImageId,
      it -> it,
      // Merge is necessary when there are multiple running instances. In those cases an image might be present
      // twice (potentially with differing tags). Just pick one of them for now.
      (a, b) -> a
    ));

    LOGGER.info("Found image updates {}", imageUpdates);
    LOGGER.info("Found image map {}", imageMap);

    for (Container container : client.listContainersCmd().withShowAll(true).exec()) {
      if (!imageMap.containsKey(container.getImageId())) {
        if (enrollmentMode.isParticipating(container)) {
          LOGGER.info("Container '{}' is up to date", getContainerNames(container));
        }
        continue;
      }
      LighthouseImageUpdate update = imageMap.get(container.getImageId());
      LOGGER.info("Container '{}' has an update ({})", getContainerNames(container), update.remoteManifestDigest());

      updates.add(
        new LighthouseContainerUpdate(
          getContainerNames(container),
          update,
          LighthouseDetector.isLighthouse(container)
        )
      );
    }

    return updates;
  }

  private List<String> getContainerNames(Container container) {
    return Arrays.stream(container.getNames()).map(it -> it.substring(1)).toList();
  }
}
