package de.ialistannen.lighthouse.notifier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.google.common.base.Throwables;
import com.google.common.collect.Lists;
import de.ialistannen.lighthouse.model.LighthouseContainerUpdate;
import de.ialistannen.lighthouse.model.LighthouseImageUpdate;
import de.ialistannen.lighthouse.registry.RemoteImageMetadata;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Triggers a discord webhook with notifications :^)
 */
public class DiscordNotifier implements Notifier {

  private static final Logger LOGGER = LoggerFactory.getLogger(DiscordNotifier.class);

  private final HttpClient httpClient;
  private final URI url;
  private final ObjectMapper objectMapper;
  private final Optional<String> mention;
  private final Optional<String> mentionText;
  private final Optional<String> hostname;

  public DiscordNotifier(
    HttpClient httpClient,
    URI url,
    Optional<String> mention,
    Optional<String> mentionText,
    Optional<String> hostname
  ) {
    this.httpClient = httpClient;
    this.url = url;
    this.mention = mention;
    this.mentionText = mentionText;
    this.hostname = hostname;
    this.objectMapper = new ObjectMapper();
  }

  @Override
  public void notify(Exception e) {
    LOGGER.info("Notifying for exception in discord");

    String stacktrace = Throwables.getStackTraceAsString(e);

    ObjectNode payload = objectMapper.createObjectNode();

    ObjectNode embed = objectMapper.createObjectNode();
    embed.set("title", new TextNode("Error while checking for updates"));
    embed.set("description", new TextNode("```\n" + stacktrace + "\n```"));
    embed.set("color", new IntNode(0xFF6347));
    embed.set("footer", buildFooter());

    ArrayNode embeds = objectMapper.createArrayNode();
    embeds.add(embed);
    payload.set("embeds", embeds);

    sendPayload(payload, List.of());
  }

  @Override
  public void notify(List<LighthouseContainerUpdate> updates) {
    if (updates.isEmpty()) {
      return;
    }
    LOGGER.info("Notifying in discord");

    for (List<LighthouseContainerUpdate> batch : Lists.partition(updates, 10)) {
      ObjectNode payload = buildPayload(batch);
      sendPayload(payload, batch);
    }
  }

  private void sendPayload(ObjectNode payload, List<LighthouseContainerUpdate> updates) {
    try {
      payload.set(
        "avatar_url",
        new TextNode("https://github.com/I-Al-Istannen/Lighthouse/blob/master/media/lighthouse.png?raw=true")
      );
      mention.ifPresent(mention -> payload.set(
        "content",
        new TextNode("Hey, " + mention + " " + getExpandedMentionText(updates))
      ));
      payload.set("username", new TextNode("Lighthouse" + hostname.map(it -> " (" + it + ")").orElse("")));

      HttpRequest request = HttpRequest.newBuilder(url)
        .POST(BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
        .header("Content-Type", "application/json")
        .build();

      HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());
      if (response.statusCode() != 200 && response.statusCode() != 204) {
        LOGGER.warn("Failed to notify (HTTP {}): {}", response.statusCode(), response.body());
      }
    } catch (IOException | InterruptedException e) {
      LOGGER.warn("Failed to notify!", e);
    }
  }

  private String getExpandedMentionText(List<LighthouseContainerUpdate> updates) {
    String text = mentionText.orElse("I got some news!");

    String images = updates.stream()
      .map(LighthouseContainerUpdate::imageUpdate)
      .map(imageUpdate -> imageUpdate.imageName() + ":" + imageUpdate.tag())
      .collect(Collectors.joining(" "));

    return text.replace("{IMAGES}", images);
  }

  private ObjectNode buildPayload(List<LighthouseContainerUpdate> updates) {
    ObjectNode payload = objectMapper.createObjectNode();
    ArrayNode embeds = objectMapper.createArrayNode();

    if (updates.size() > 10) {
      throw new IllegalArgumentException("Got more than 10 updates!");
    }

    updates.stream()
      .map(this::buildEmbed)
      .forEach(embeds::add);

    payload.set("embeds", embeds);
    return payload;
  }

  private ObjectNode buildEmbed(LighthouseContainerUpdate update) {
    LighthouseImageUpdate imageUpdate = update.imageUpdate();

    ObjectNode embed = objectMapper.createObjectNode();
    embed.set("title", new TextNode(imageUpdate.imageName() + ":" + imageUpdate.tag()));
    embed.set("description", new TextNode("Update found."));
    embed.set(
      "url",
      new TextNode("https://hub.docker.com/r/%s".formatted(imageUpdate.imageName()))
    );
    embed.set("color", new IntNode(0xFF6347));
    embed.set("footer", buildFooter());

    ArrayNode fields = objectMapper.createArrayNode();
    fields.add(buildContainerNamesField(update));
    fields.add(buildImageNamesField(imageUpdate));

    imageUpdate.remoteImageMetadata().ifPresent(metadata -> {
      fields.add(buildUpdaterField(metadata));
      embed.set("timestamp", new TextNode(metadata.updateTime().toString()));
    });

    fields.add(buildRemoteImageIdField(imageUpdate));

    embed.set("fields", fields);

    return embed;
  }

  private ObjectNode buildContainerNamesField(LighthouseContainerUpdate update) {
    ObjectNode containerNames = objectMapper.createObjectNode();
    containerNames.set("name", new TextNode("Container names"));
    containerNames.set("value", new TextNode(String.join(", ", update.names())));
    containerNames.set("inline", BooleanNode.TRUE);
    return containerNames;
  }

  private ObjectNode buildImageNamesField(LighthouseImageUpdate update) {
    ObjectNode imageNames = objectMapper.createObjectNode();
    imageNames.set("name", new TextNode("Affected image names"));
    imageNames.set("value", new TextNode(String.join(", ", update.sourceImageNames())));
    imageNames.set("inline", BooleanNode.TRUE);
    return imageNames;
  }

  private ObjectNode buildUpdaterField(RemoteImageMetadata metadata) {
    ObjectNode updaterInfo = objectMapper.createObjectNode();
    updaterInfo.set("name", new TextNode("Update information"));
    updaterInfo.set(
      "value",
      new TextNode(
        "Updated <t:%s:R> by **%s**".formatted(
          metadata.updateTime().getEpochSecond(),
          metadata.updatedBy()
        )
      )
    );

    return updaterInfo;
  }

  private ObjectNode buildRemoteImageIdField(LighthouseImageUpdate imageUpdate) {
    ObjectNode remoteImageId = objectMapper.createObjectNode();
    remoteImageId.set("name", new TextNode("New digest"));
    remoteImageId.set("value", new TextNode("`" + imageUpdate.remoteManifestDigest() + "`"));

    return remoteImageId;
  }

  private ObjectNode buildFooter() {
    ObjectNode footer = objectMapper.createObjectNode();
    footer.set("text", new TextNode("Made with \u2764\uFE0F"));
    return footer;
  }
}
