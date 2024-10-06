package de.ialistannen.lighthouse.updater;

import de.ialistannen.lighthouse.model.LighthouseContainerUpdate;
import de.ialistannen.lighthouse.notifier.Notifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.ButtonStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DiscordBotUpdateListener extends ListenerAdapter implements UpdateListener {

  private static final Logger LOGGER = LoggerFactory.getLogger(DiscordBotUpdateListener.class);

  private final DockerUpdater updater;
  private final Notifier notifier;
  private List<LighthouseContainerUpdate> lastUpdates;
  private List<String> selectedUpdates;

  public DiscordBotUpdateListener(DockerUpdater updater, Notifier notifier) {
    this.updater = updater;
    this.notifier = notifier;
    this.lastUpdates = new ArrayList<>();
    this.selectedUpdates = null;
  }

  @Override
  public void onUpdatesFound(List<LighthouseContainerUpdate> updates) {
    this.lastUpdates = updates;
    this.selectedUpdates = null;
  }

  @Override
  public void onButtonInteraction(ButtonInteractionEvent event) {
    String buttonId = event.getComponentId();
    LOGGER.info("Received button interaction: {}", buttonId);

    if (!buttonId.startsWith("update-")) {
      LOGGER.info("Unknown component id {}", buttonId);
      event.reply("I don't know that action :/").queue();
      return;
    } else if (!buttonId.substring("update-".length()).equals(String.valueOf(lastUpdates.hashCode()))) {
      LOGGER.info("Unknown updates for id {}", buttonId);
      event.reply("Sorry, updating is only supported for the latest notification").queue();
      return;
    }

    InteractionHook hook = event.editComponents(ActionRow.of(
      event.getButton().asDisabled().withLabel("Executing...")
    )).complete();

    List<LighthouseContainerUpdate> updates;

    if (selectedUpdates != null) {
      updates = lastUpdates.stream().filter(update -> selectedUpdates.contains(update.names().get(0))).toList();
    } else {
      updates = lastUpdates;
    }

    CompletableFuture.runAsync(() -> {
        try {
          updater.rebuildContainers(
            updates,
            label -> hook.editOriginalComponents(ActionRow.of(
              event.getButton().asDisabled()
                .withLabel(label)
                .withEmoji(Emoji.fromUnicode("✅"))
                .withStyle(ButtonStyle.SUCCESS)
            )).queue()
          );
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
      })
      .exceptionally(throwable -> {
        LOGGER.warn("Error while updating all", throwable);
        notifier.notify(throwable);
        // Restore button
        hook.editOriginalComponents(event.getMessage().getComponents()).queue();

        return null;
      });
  }

  @Override
  public void onStringSelectInteraction(StringSelectInteractionEvent event) {
    LOGGER.info("Received string select interaction: {}", event.getComponentId());

    if (!event.getComponentId().startsWith("image-select-")) {
      LOGGER.info("Unknown component id {}", event.getComponentId());
      event.reply("I don't know that action :/").queue();
      return;
    } else if (!event.getComponentId()
      .substring("image-select-".length())
      .equals(String.valueOf(lastUpdates.hashCode()))) {
      LOGGER.info("Unknown updates for id {}", event.getComponentId());
      event.reply("Sorry, selecting containers and updating is only supported for the latest notification").queue();
      return;
    }

    selectedUpdates = new ArrayList<>(event.getValues());
    event.deferEdit().queue();
  }
}
