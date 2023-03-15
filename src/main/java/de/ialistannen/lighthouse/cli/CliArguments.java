package de.ialistannen.lighthouse.cli;

import de.ialistannen.lighthouse.model.BaseImageUpdateStrategy;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.jbock.Command;
import net.jbock.Option;
import net.jbock.Parameter;

@Command(name = "lighthouse", description = "Watches for docker base image updates", publicParser = true)
public interface CliArguments {

  @Option(
    names = "--check-times",
    description = "Check times in cron syntax (https://crontab.guru). Default: '23 08 * * *'",
    paramLabel = "CRONTAB"
  )
  Optional<String> checkTimes();

  @Option(names = "--mention", description = "Discord mention (e.g. '<@userid>')", paramLabel = "MENTION")
  Optional<String> mention();

  @Option(names = "--mention-text", description = "Text to send in Discord", paramLabel = "TEXT")
  Optional<String> mentionText();

  @Option(
    names = "--docker-config",
    description = "Path to docker config. Default: /root/.docker/config.json",
    paramLabel = "PATH"
  )
  Optional<String> dockerConfigPath();

  @Option(names = "--hostname", description = "The hostname to mention in notifications", paramLabel = "NAME")
  Optional<String> hostname();

  @Option(
    names = "--base-image-update",
    description = "Whether to 'only_pull_unknown' base images or 'pull_and_update' them",
    paramLabel = "STRATEGY"
  )
  Optional<BaseImageUpdateStrategy> baseImageUpdate();

  @Option(
    names = "--require-label",
    description = "Ignore containers without 'lighthouse.enabled' label. Default: false"
  )
  boolean requireLabel();

  @Option(names = "--notify-again", description = "Notify you more than once about an image update. Default: false")
  boolean alwaysNotify();

  @Option(
    names = "--bot-updater-docker-image",
    description = "The name of the image to use for updating containers. Default: 'library/docker'",
    paramLabel = "IMAGE"
  )
  Optional<String> updaterDockerImage();

  @Option(names = "--bot-updater-mount", description = "The mounts for created updater containers")
  List<String> updaterMounts();

  @Option(names = "--bot-updater-entrypoint", description = "The binary to call in the updater container")
  Optional<String> updaterEntrypoint();

  @Option(names = "--bot-channel-id", description = "The channel id the bot should send updates to")
  Optional<String> botChannelId();

  @Option(names = "--ntfy", description = "Use ntfy to send notifications")
  boolean ntfy();

  @Parameter(index = 0, description = "Webhook URL or discord bot token", paramLabel = "URL|TOKEN")
  String webhookUrlOrToken();

  default boolean useWebhookNotifier() {
    return webhookUrlOrToken().toLowerCase(Locale.ROOT).startsWith("https://");
  }
}
