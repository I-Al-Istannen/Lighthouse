package de.ialistannen.lighthouse;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientBuilder;
import de.ialistannen.lighthouse.auth.DockerRegistryAuth;
import de.ialistannen.lighthouse.cli.CliArguments;
import de.ialistannen.lighthouse.cli.CliArgumentsParser;
import de.ialistannen.lighthouse.metadata.DockerHubMetadataFetcher;
import de.ialistannen.lighthouse.model.EnrollmentMode;
import de.ialistannen.lighthouse.model.LighthouseContainerUpdate;
import de.ialistannen.lighthouse.notifier.DiscordNotifier;
import de.ialistannen.lighthouse.registry.DockerLibraryHelper;
import de.ialistannen.lighthouse.registry.DockerRegistry;
import de.ialistannen.lighthouse.updates.ContainerUpdateChecker;
import de.ialistannen.lighthouse.updates.ImageUpdateChecker;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {

  private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

  public static void main(String[] args) throws IOException, URISyntaxException, InterruptedException {
    CliArguments arguments = new CliArgumentsParser().parseOrExit(args);
    int checkFrequencySeconds = arguments.checkIntervalSeconds().orElse((int) TimeUnit.HOURS.toSeconds(12));
    EnrollmentMode enrollmentMode = arguments.optOut() ? EnrollmentMode.OPT_OUT : EnrollmentMode.OPT_IN;

    HttpClient httpClient = HttpClient.newBuilder().build();

    DefaultDockerClientConfig.Builder config = DefaultDockerClientConfig.createDefaultConfigBuilder();
    DockerClient dockerClient = DockerClientBuilder.getInstance(config.build()).build();

    DockerLibraryHelper libraryHelper = new DockerLibraryHelper(httpClient);
    DockerRegistry dockerRegistry = new DockerRegistry(libraryHelper, httpClient, authsFromArgs(arguments));
    DockerHubMetadataFetcher metadataFetcher = new DockerHubMetadataFetcher(libraryHelper, httpClient);

    ImageUpdateChecker imageUpdateChecker = new ImageUpdateChecker(
      dockerClient,
      dockerRegistry,
      metadataFetcher,
      enrollmentMode,
      libraryHelper
    );
    ContainerUpdateChecker containerUpdateChecker = new ContainerUpdateChecker(
      dockerClient,
      imageUpdateChecker,
      enrollmentMode
    );

    DiscordNotifier notifier = new DiscordNotifier(
      httpClient,
      new URI(arguments.webhookUrl()),
      arguments.mentionUserId(),
      arguments.mentionText()
    );

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

  private static List<DockerRegistryAuth> authsFromArgs(CliArguments arguments) throws IOException {
    Path pathToFile = Path.of(System.getProperty("user.home"), ".docker/config.json");

    if (arguments.dockerConfigPath().isEmpty()) {
      if (!Files.exists(pathToFile)) {
        return Collections.emptyList();
      }
      LOGGER.info("Using default docker.json path");
    } else {
      pathToFile = Path.of(arguments.dockerConfigPath().get());
    }

    LOGGER.info("Loading auth from '{}'", pathToFile);
    return DockerRegistryAuth.loadAuthentications(pathToFile);
  }
}
