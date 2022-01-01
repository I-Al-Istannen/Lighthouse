package de.ialistannen.lighthouse.cli;

import java.util.Optional;
import net.jbock.Command;
import net.jbock.Option;
import net.jbock.Parameter;

@Command(name = "lighthouse", description = "Watches for docker base image updates", publicParser = true)
public interface CliArguments {

  @Option(names = "--check-interval-seconds", description = "Check interval in seconds")
  Optional<Integer> checkIntervalSeconds();

  @Option(names = "--mention-user-id", description = "Discord user id to mention")
  Optional<String> mentionUserId();

  @Option(names = "--mention-text", description = "Text to send in Discord")
  Optional<String> mentionText();

  @Parameter(index = 0, description = "Discord webhook URL", paramLabel = "URL")
  String webhookUrl();

}
