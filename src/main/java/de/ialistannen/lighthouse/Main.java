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
import de.ialistannen.lighthouse.model.BaseImageUpdateStrategy;
import de.ialistannen.lighthouse.model.EnrollmentMode;
import de.ialistannen.lighthouse.model.LighthouseContainerUpdate;
import de.ialistannen.lighthouse.notifier.DiscordBotNotifier;
import de.ialistannen.lighthouse.notifier.DiscordWebhookNotifier;
import de.ialistannen.lighthouse.notifier.Notifier;
import de.ialistannen.lighthouse.notifier.NtfyNotifier;
import de.ialistannen.lighthouse.registry.DockerLibraryHelper;
import de.ialistannen.lighthouse.registry.DockerRegistry;
import de.ialistannen.lighthouse.storage.FileUpdateFilter;
import de.ialistannen.lighthouse.timing.CronRunner;
import de.ialistannen.lighthouse.updater.DiscordBotUpdateListener;
import de.ialistannen.lighthouse.updater.DockerUpdater;
import de.ialistannen.lighthouse.updater.NtfyUpdateListener;
import de.ialistannen.lighthouse.updater.UpdateListener;
import de.ialistannen.lighthouse.updates.ContainerUpdateChecker;
import de.ialistannen.lighthouse.updates.ImageUpdateChecker;
import de.ialistannen.lighthouse.util.LighthouseDetector;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import javax.security.auth.login.LoginException;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.exceptions.InvalidTokenException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {

  private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

  public static void main(String[] args) throws IOException, URISyntaxException, InterruptedException {
    CliArguments arguments = CliArgumentsParser.parseOrExit(args);
    String cronTimesString = arguments.checkTimes().orElse("23 08 * * *");
    Cron cronTime = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX))
      .parse(cronTimesString)
      .validate();

    EnrollmentMode enrollmentMode = arguments.requireLabel() ? EnrollmentMode.OPT_IN : EnrollmentMode.OPT_OUT;

    HttpClient httpClient = HttpClient.newBuilder().build();

    DefaultDockerClientConfig.Builder config = DefaultDockerClientConfig.createDefaultConfigBuilder();
    DockerClient dockerClient = DockerClientBuilder.getInstance(config.build()).build();

    verifyLighthouseInstanceCount(dockerClient);

    DockerLibraryHelper libraryHelper = new DockerLibraryHelper(httpClient);
    DockerRegistry dockerRegistry = new DockerRegistry(libraryHelper, httpClient, authsFromArgs(arguments));
    DockerHubMetadataFetcher metadataFetcher = new DockerHubMetadataFetcher(libraryHelper, httpClient);

    JDA jda = buildJda(arguments);
    Notifier notifier = buildNotifier(arguments, httpClient, jda);

    ImageUpdateChecker imageUpdateChecker = new ImageUpdateChecker(
      dockerClient,
      dockerRegistry,
      metadataFetcher,
      enrollmentMode,
      libraryHelper,
      arguments.baseImageUpdate().orElse(BaseImageUpdateStrategy.ONLY_PULL_UNKNOWN),
      notifier
    );
    ContainerUpdateChecker containerUpdateChecker = new ContainerUpdateChecker(
      dockerClient,
      imageUpdateChecker,
      enrollmentMode
    );

    UpdateListener updateListener = buildUpdateListener(arguments, httpClient, notifier, dockerClient, jda);

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
        updateListener.onUpdatesFound(updates);

        // AFTER notify was successful!
        updateFilter.commit();
      }
    ).runUntilSingularity();
  }

  private static void verifyLighthouseInstanceCount(DockerClient dockerClient) {
    long foundLighthouseCount = dockerClient.listContainersCmd().exec().stream()
      .filter(LighthouseDetector::isLighthouse)
      .count();

    if (foundLighthouseCount == 0) {
      LOGGER.warn(
        "Label '{}' not set! Unable to identify own container. Can not update lighthouse last.",
        LighthouseDetector.LIGHTHOUSE_ID_LABEL
      );
    }
    if (foundLighthouseCount > 1) {
      LOGGER.warn(
        "Found {} containers with the '{}' label. "
        + "Running multiple instances should mostly work, but you are sailing *pretty close* to some nasty cliffs.",
        LighthouseDetector.LIGHTHOUSE_ID_LABEL,
        foundLighthouseCount
      );
    }
  }

  private static JDA buildJda(CliArguments arguments) {
    if (arguments.useWebhookNotifier()) {
      return null;
    }
    try {
      return JDABuilder.createDefault(arguments.webhookUrlOrToken()).build().awaitReady();
    } catch (InterruptedException | InvalidTokenException e) {
      LOGGER.error("Error logging in to discord", e);
      throw die("Error logging in to discord");
    }
  }

  private static Notifier buildNotifier(
    CliArguments arguments,
    HttpClient httpClient,
    JDA jda
  ) throws URISyntaxException {
    if (arguments.useWebhookNotifier()) {
      if (arguments.ntfy()) {
        return new NtfyNotifier(
          httpClient,
          new URI(arguments.webhookUrlOrToken()),
          arguments.hostname()
        );
      }
      return new DiscordWebhookNotifier(
        httpClient,
        new URI(arguments.webhookUrlOrToken()),
        arguments.mention(),
        arguments.mentionText(),
        arguments.hostname()
      );
    }

    if (jda == null) {
      throw die("JDA was null");
    }

    if (arguments.botChannelId().isEmpty()) {
      throw die("Channel id must be supplied when using the bot notifier");
    }

    TextChannel channel = jda.getTextChannelById(arguments.botChannelId().get());

    if (channel == null) {
      throw die("Textchannel does not exist");
    }

    try {
      return new DiscordBotNotifier(
        channel,
        arguments.mention(),
        arguments.mentionText(),
        arguments.hostname()
      );
    } catch (LoginException e) {
      LOGGER.error("Failed to login", e);
      throw die("Failed to login");
    }
  }

  private static UpdateListener buildUpdateListener(
    CliArguments arguments, HttpClient httpClient, Notifier notifier, DockerClient client, JDA jda
  ) throws URISyntaxException {
    if (arguments.useWebhookNotifier()) {
      if (arguments.ntfy()) {
        NtfyUpdateListener listener = new NtfyUpdateListener(
          httpClient,
          buildUpdater(arguments, client),
          notifier,
          new URI(arguments.webhookUrlOrToken()),
          arguments.hostname()
        );
        listener.start();
        return listener;
      }
      return ignored -> {
      };
    }
    DiscordBotUpdateListener listener = new DiscordBotUpdateListener(
      buildUpdater(arguments, client),
      notifier
    );
    jda.addEventListener(listener);
    return listener;
  }

  private static DockerUpdater buildUpdater(CliArguments arguments, DockerClient client) {
    if (arguments.updaterEntrypoint().isEmpty()) {
      throw die("Entrypoint must be given when using the updater");
    }

    String entrypoint = arguments.updaterEntrypoint().get();
    String updaterImage = arguments.updaterDockerImage().orElse("docker");

    return new DockerUpdater(client, arguments.updaterMounts(), entrypoint, updaterImage);
  }

  private static RuntimeException die(String msg) {
    LOGGER.error(msg);
    System.exit(1);

    return new RuntimeException();
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
