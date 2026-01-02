package de.ialistannen.lighthouse.notifier;

import de.ialistannen.lighthouse.model.ImageIdentifier;
import de.ialistannen.lighthouse.model.LighthouseContainerUpdate;
import de.ialistannen.lighthouse.model.LighthouseImageUpdate;
import de.ialistannen.lighthouse.model.LighthouseTagUpdate;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

class DiscordNotifierHelper {

  static String getExpandedMentionText(
    String mention,
    Optional<String> mentionText,
    List<LighthouseContainerUpdate> updates
  ) {
    String text = mentionText.orElse("I got some news!");

    String images = updates.stream()
      .map(LighthouseContainerUpdate::imageUpdate)
      .map(LighthouseImageUpdate::imageIdentifier)
      .map(ImageIdentifier::nameWithTag)
      .distinct()
      .collect(Collectors.joining(" "));

    text = text.replace("{IMAGES}", images);

    return "Hey, " + mention + " " + text;
  }

  static String getExpandedMentionTextForTagUpdates(
    String mention,
    Optional<String> mentionText,
    List<LighthouseTagUpdate> tagUpdates
  ) {
    String text = mentionText.orElse("Version upgrades available!");

    String updates = tagUpdates.stream()
      .map(update -> update.imageIdentifier().image() + ": " + update.currentTag() + " → " + update.newTag())
      .distinct()
      .collect(Collectors.joining(", "));

    text = text.replace("{IMAGES}", updates);

    return "Hey, " + mention + " " + text;
  }
}
