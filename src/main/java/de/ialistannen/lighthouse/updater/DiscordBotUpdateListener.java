package de.ialistannen.lighthouse.updater;

import de.ialistannen.lighthouse.model.LighthouseContainerUpdate;
import de.ialistannen.lighthouse.notifier.Notifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
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

  public DiscordBotUpdateListener(DockerUpdater updater, Notifier notifier) {
    this.updater = updater;
    this.notifier = notifier;
    this.lastUpdates = new ArrayList<>();
  }

  @Override
  public void onUpdatesFound(List<LighthouseContainerUpdate> updates) {
    this.lastUpdates = updates;
  }

  @Override
  public void onButtonInteraction(ButtonInteractionEvent event) {
    LOGGER.info("Received button interaction: {}", event.getComponentId());

    if (!"update-all".equalsIgnoreCase(event.getComponentId())) {
      event.reply("I don't know that action :/").queue();
      return;
    }
    InteractionHook hook = event.editComponents(ActionRow.of(
      event.getButton().asDisabled().withLabel("Executing...")
    )).complete();

    CompletableFuture.runAsync(() -> {
        try {
          updater.rebuildContainers(
            lastUpdates,
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
        hook.editOriginalComponents(ActionRow.of(event.getButton())).queue();

        return null;
      });
  }
}
