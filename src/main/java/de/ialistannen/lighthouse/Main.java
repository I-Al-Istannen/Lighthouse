package de.ialistannen.lighthouse;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientBuilder;
import de.ialistannen.lighthouse.docker.ContainerUpdateChecker;
import de.ialistannen.lighthouse.docker.ImageUpdateChecker;
import de.ialistannen.lighthouse.docker.LighthouseContainerUpdate;
import de.ialistannen.lighthouse.hub.DockerLibraryHelper;
import de.ialistannen.lighthouse.hub.DockerRegistryClient;
import de.ialistannen.lighthouse.notifier.DiscordNotifier;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {

  private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

  public static void main(String[] args) throws IOException, URISyntaxException {
    if (args.length < 1 || args.length > 2) {
      System.err.println("Usage: <this program> <discord webhook url> [check frequency in seconds]");
      System.exit(1);
    }
    int checkFrequencySeconds = (int) TimeUnit.HOURS.toSeconds(6);
    if (args.length == 2) {
      checkFrequencySeconds = Integer.parseInt(args[1]);
    }

    HttpClient httpClient = HttpClient.newBuilder().build();

    DefaultDockerClientConfig.Builder config = DefaultDockerClientConfig.createDefaultConfigBuilder();
    DockerClient dockerClient = DockerClientBuilder.getInstance(config.build()).build();

    DockerRegistryClient registryClient = new DockerRegistryClient(httpClient, new DockerLibraryHelper());

    ImageUpdateChecker imageUpdateChecker = new ImageUpdateChecker(dockerClient, registryClient);
    ContainerUpdateChecker containerUpdateChecker = new ContainerUpdateChecker(dockerClient, imageUpdateChecker);

    DiscordNotifier notifier = new DiscordNotifier(httpClient, new URI(args[0]));

    //noinspection InfiniteLoopStatement
    while (true) {
      try {
        LOGGER.info("Checking for updates...");
        List<LighthouseContainerUpdate> updates = containerUpdateChecker.check();
        notifier.notify(updates);
      } catch (Exception e) {
        LOGGER.error("Failed to check for updates", e);
        notifier.notify(e);
      }
      Instant nextWakeup = Instant.now().plusSeconds(checkFrequencySeconds);
      while (Instant.now().isBefore(nextWakeup)) {
        LOGGER.debug(
          "Sleeping until {} ({} seconds)",
          nextWakeup,
          nextWakeup.getEpochSecond() - Instant.now().getEpochSecond()
        );
        try {
          //noinspection BusyWait
          Thread.sleep(checkFrequencySeconds / 4 * 1000L);
        } catch (InterruptedException e) {
          LOGGER.info("Interrupted while sleeping", e);
        }
      }
    }
  }
}
