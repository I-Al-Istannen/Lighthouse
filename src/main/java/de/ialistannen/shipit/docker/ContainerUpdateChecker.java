package de.ialistannen.shipit.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ContainerUpdateChecker {

  private static final Logger LOGGER = LoggerFactory.getLogger(ContainerUpdateChecker.class);

  private final DockerClient client;

  public ContainerUpdateChecker(DockerClient client) {
    this.client = client;
  }

  public List<ShipItContainerUpdate> check(List<ShipItImageUpdate> imageUpdates) {
    List<ShipItContainerUpdate> updates = new ArrayList<>();

    Map<String, ShipItImageUpdate> imageMap = imageUpdates.stream().collect(Collectors.toMap(
      ShipItImageUpdate::sourceImageId,
      it -> it
    ));

    for (Container container : client.listContainersCmd().exec()) {
      if (!imageMap.containsKey(container.getImageId())) {
        LOGGER.info("Container '{}' is up to date", getContainerNames(container));
        continue;
      }
      ShipItImageUpdate update = imageMap.get(container.getImageId());
      LOGGER.info("Container '{}' has an update ({})", getContainerNames(container), update.remoteImageId());

      updates.add(new ShipItContainerUpdate(
        getContainerNames(container),
        update
      ));
    }

    return updates;
  }

  private List<String> getContainerNames(Container container) {
    return Arrays.stream(container.getNames()).map(it -> it.substring(1)).toList();
  }
}
