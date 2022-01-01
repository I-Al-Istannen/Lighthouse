package de.ialistannen.shipit;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientBuilder;
import de.ialistannen.shipit.docker.ContainerUpdateChecker;
import de.ialistannen.shipit.docker.ImageUpdateChecker;
import de.ialistannen.shipit.docker.ShipItContainerUpdate;
import de.ialistannen.shipit.hub.DockerLibraryHelper;
import de.ialistannen.shipit.hub.DockerRegistryClient;
import de.ialistannen.shipit.notifier.DiscordNotifier;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {

  private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

  public static void main(String[] args) throws IOException, URISyntaxException, InterruptedException {
    if (args.length != 1) {
      System.err.println("Usage: <this program> <discord webhook url>");
      System.exit(1);
    }
    HttpClient httpClient = HttpClient.newBuilder().build();

    DefaultDockerClientConfig.Builder config = DefaultDockerClientConfig.createDefaultConfigBuilder();
    DockerClient dockerClient = DockerClientBuilder.getInstance(config.build()).build();

    DockerRegistryClient registryClient = new DockerRegistryClient(httpClient, new DockerLibraryHelper());

    ImageUpdateChecker imageUpdateChecker = new ImageUpdateChecker(dockerClient, registryClient);
    ContainerUpdateChecker containerUpdateChecker = new ContainerUpdateChecker(dockerClient, imageUpdateChecker);

    DiscordNotifier notifier = new DiscordNotifier(httpClient, new URI(args[0])
    );

    try {
      List<ShipItContainerUpdate> updates = containerUpdateChecker.check();
      notifier.notify(updates);
    } catch (Exception e) {
      LOGGER.error("Failed to check for updates", e);
      notifier.notify(e);
    }
  }
}
