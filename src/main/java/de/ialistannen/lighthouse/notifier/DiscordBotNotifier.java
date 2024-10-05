package de.ialistannen.lighthouse.notifier;

import com.google.common.base.Throwables;
import com.google.common.collect.Lists;
import de.ialistannen.lighthouse.model.LighthouseContainerUpdate;
import de.ialistannen.lighthouse.model.LighthouseImageUpdate;
import de.ialistannen.lighthouse.registry.RemoteImageMetadata;
import java.util.List;
import java.util.Optional;
import javax.security.auth.login.LoginException;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.MessageEmbed.Field;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.buttons.ButtonStyle;
import net.dv8tion.jda.api.interactions.components.selections.SelectOption;
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DiscordBotNotifier extends ListenerAdapter implements Notifier {

  private static final Logger LOGGER = LoggerFactory.getLogger(DiscordBotNotifier.class);

  private final TextChannel channel;
  private final Optional<String> mention;
  private final Optional<String> mentionText;
  private final Optional<String> hostname;

  public DiscordBotNotifier(
    TextChannel channel,
    Optional<String> mention,
    Optional<String> mentionText,
    Optional<String> hostname
  ) throws LoginException {
    this.mention = mention;
    this.mentionText = mentionText;
    this.hostname = hostname;
    this.channel = channel;
  }

  @Override
  public void notify(Throwable e) {
    LOGGER.info("Notifying for exception in discord");

    String stacktrace = Throwables.getStackTraceAsString(e);
    // Make some room for our other text
    if (stacktrace.length() > MessageEmbed.DESCRIPTION_MAX_LENGTH - 100) {
      stacktrace = stacktrace.substring(0, MessageEmbed.DESCRIPTION_MAX_LENGTH - 100);
    }

    EmbedBuilder embedBuilder = new EmbedBuilder();

    embedBuilder.setTitle("Error while checking for updates");
    embedBuilder.setDescription("```\n" + stacktrace + "\n```");
    embedBuilder.setColor(0xFF6347);
    embedBuilder.setFooter(getFooter());
    hostname.ifPresent(embedBuilder::setAuthor);

    sendEmbed(embedBuilder.build());
  }

  private void sendEmbed(MessageEmbed embed) {
    if (mention.isPresent()) {
      sendMessage(new MessageCreateBuilder().setEmbeds(embed), List.of());
      return;
    }

    channel.sendMessageEmbeds(embed).queue();
  }

  private void sendMessage(MessageCreateBuilder messageBuilder, List<LighthouseContainerUpdate> updates) {
    mention.ifPresent(mention -> messageBuilder.setContent(
      DiscordNotifierHelper.getExpandedMentionText(mention, mentionText, updates)
    ));

    channel.sendMessage(
        messageBuilder
          .build()
      )
      .queue();
  }

  private String getFooter() {
    return "Made with ❤️";
  }

  @Override
  public void notify(List<LighthouseContainerUpdate> updates) {
    if (updates.isEmpty()) {
      return;
    }

    for (List<LighthouseContainerUpdate> batch : Lists.partition(updates, Message.MAX_EMBED_COUNT)) {
      sendMessage(
        new MessageCreateBuilder()
          .setEmbeds(batch.stream().map(this::buildEmbed).map(EmbedBuilder::build).toList()),
        batch
      );
    }

    sendActionRows(updates);
  }

  private EmbedBuilder buildEmbed(LighthouseContainerUpdate update) {
    LighthouseImageUpdate imageUpdate = update.imageUpdate();
    EmbedBuilder embedBuilder = new EmbedBuilder();

    embedBuilder.setTitle(
      imageUpdate.imageIdentifier().nameWithTag(),
      "https://hub.docker.com/r/%s".formatted(imageUpdate.imageIdentifier().image())
    );
    embedBuilder.setDescription("Update found.");
    embedBuilder.setColor(0xFF6347);
    embedBuilder.setFooter(getFooter());

    embedBuilder.addField(buildContainerNamesField(update));
    embedBuilder.addField(buildImageNamesField(imageUpdate));

    imageUpdate.remoteImageMetadata().ifPresent(metadata -> {
      embedBuilder.addField(buildUpdaterField(metadata));
      embedBuilder.setTimestamp(metadata.updateTime());
    });

    embedBuilder.addField(buildRemoteImageIdField(imageUpdate));

    hostname.ifPresent(embedBuilder::setAuthor);

    return embedBuilder;
  }

  private Field buildContainerNamesField(LighthouseContainerUpdate update) {
    return new Field(
      "Container names",
      String.join(", ", update.names()),
      true
    );
  }

  private Field buildImageNamesField(LighthouseImageUpdate update) {
    return new Field(
      "Affected image names",
      String.join(", ", update.sourceImageNames()),
      true
    );
  }

  private Field buildUpdaterField(RemoteImageMetadata metadata) {
    return new Field(
      "Update information",
      "Updated <t:%s:R> by **%s**".formatted(
        metadata.updateTime().getEpochSecond(),
        metadata.updatedBy()
      ),
      false
    );
  }

  private Field buildRemoteImageIdField(LighthouseImageUpdate imageUpdate) {
    return new Field(
      "New digest",
      "`" + imageUpdate.remoteManifestDigest() + "`",
      false
    );
  }

  private void sendActionRows(List<LighthouseContainerUpdate> updates) {
    MessageCreateBuilder messageBuilder = new MessageCreateBuilder().setContent("\u00A0");

    messageBuilder.addActionRow(
      StringSelectMenu.create("image-select-" + updates.hashCode())
          .setMinValues(1)
          .setMaxValues(25)
          .addOptions(updates.stream()
            .map(update -> SelectOption.of(update.names().get(0), update.names().get(0)).withDescription(update.imageUpdate().imageIdentifier().nameWithTag()).withDefault(true))
            .toList()
          )
          .build()
    );
    messageBuilder.addActionRow(
      Button.of(ButtonStyle.PRIMARY, "update-" + updates.hashCode(), "Update selected!", Emoji.fromUnicode("🚀"))
    );

    channel.sendMessage(messageBuilder.build()).queue();
  }
}
