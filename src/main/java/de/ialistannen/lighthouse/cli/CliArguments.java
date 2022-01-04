package de.ialistannen.lighthouse.cli;

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
    names = "--require-label",
    description = "Ignore containers without 'lighthouse.enable' label. Default: false"
  )
  boolean requireLabel();

  @Option(names = "--notify-again", description = "Notify you more than once about an image update. Default: false")
  boolean alwaysNotify();

  @Parameter(index = 0, description = "Discord webhook URL", paramLabel = "URL")
  String webhookUrl();

}
