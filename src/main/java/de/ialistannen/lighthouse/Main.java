package de.ialistannen.lighthouse;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;
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
import de.ialistannen.lighthouse.storage.FileUpdateFilter;
import de.ialistannen.lighthouse.timing.CronRunner;
import de.ialistannen.lighthouse.updates.ContainerUpdateChecker;
import de.ialistannen.lighthouse.updates.ImageUpdateChecker;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {

  private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

  public static void main(String[] args) throws IOException, URISyntaxException, InterruptedException {
    CliArguments arguments = new CliArgumentsParser().parseOrExit(args);
    String cronTimesString = arguments.checkTimes().orElse("23 08 * * *");
    Cron cronTime = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX))
      .parse(cronTimesString)
      .validate();

    EnrollmentMode enrollmentMode = arguments.requireLabel() ? EnrollmentMode.OPT_IN : EnrollmentMode.OPT_OUT;

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
      arguments.mention(),
      arguments.mentionText(),
      arguments.hostname()
    );

    FileUpdateFilter updateFilter = new FileUpdateFilter(Path.of("data/known-images.json"));

    new CronRunner(
      cronTime,
      notifier,
      () -> {
        LOGGER.info("Checking for updates...");
        List<LighthouseContainerUpdate> updates = containerUpdateChecker.check();

        if (!arguments.alwaysNotify()) {
          updates = updateFilter.filter(updates);
        }

        notifier.notify(updates);

        // AFTER notify was successful!
        updateFilter.commit();
      }
    ).runUntilSingularity();
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
