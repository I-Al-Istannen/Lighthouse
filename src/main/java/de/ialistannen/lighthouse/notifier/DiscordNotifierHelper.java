package de.ialistannen.lighthouse.notifier;

import de.ialistannen.lighthouse.model.LighthouseContainerUpdate;
import de.ialistannen.lighthouse.model.LighthouseImageUpdate;
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
      .map(LighthouseImageUpdate::getNameWithTag)
      .collect(Collectors.joining(" "));

    text = text.replace("{IMAGES}", images);

    return "Hey, " + mention + " " + text;
  }
}
