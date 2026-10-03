use std::ops::Range;
use std::sync::Arc;

use rootcause::prelude::*;
use serenity::all::{
    ButtonStyle, ComponentInteraction, ComponentInteractionDataKind, Context, CreateActionRow,
    CreateButton, CreateInteractionResponse, CreateInteractionResponseMessage, CreateSelectMenu,
    CreateSelectMenuKind, CreateSelectMenuOption, EditInteractionResponse, EventHandler,
    Interaction, Ready,
};
use tokio::sync::Mutex;
use tracing::{info, warn};

use crate::notify::discord::DiscordSender;
use crate::notify::text::truncate;
use crate::updates::model::ManifestUpdate;
use crate::updates::updater::{UpdateTarget, Updater, targets_of};

// https://discord.com/developers/docs/components/reference#string-select
const OPTIONS_PER_MENU: usize = 25;
const MAX_OPTION_TEXT: usize = 100;
/// A message has at most five action rows, one is needed for the button
const MENUS_PER_MESSAGE: usize = 4;
const TARGETS_PER_MESSAGE: usize = OPTIONS_PER_MENU * MENUS_PER_MESSAGE;

/// The select menus and "Update" buttons posted after update notifications in bot mode.
///
/// Only the latest set of controls is active: base images may have changed since older ones.
pub struct Controls {
    sender: DiscordSender,
    updater: Arc<Updater>,
    state: Mutex<Option<Run>>,
}

#[derive(Clone)]
struct Run {
    id: u64,
    targets: Vec<UpdateTarget>,
    /// Per target: whether it is selected. Everything starts out selected.
    selected: Vec<bool>,
}

#[derive(Debug, PartialEq, Eq)]
enum Action {
    Select {
        run: u64,
        message: usize,
        menu: usize,
    },
    Update {
        run: u64,
        message: usize,
    },
}

impl Action {
    fn parse(custom_id: &str) -> Option<Self> {
        let parts: Vec<&str> = custom_id.split(':').collect();
        match parts.as_slice() {
            ["lh", run, "sel", message, menu] => Some(Self::Select {
                run: run.parse().ok()?,
                message: message.parse().ok()?,
                menu: menu.parse().ok()?,
            }),
            ["lh", run, "go", message] => Some(Self::Update {
                run: run.parse().ok()?,
                message: message.parse().ok()?,
            }),
            _ => None,
        }
    }

    fn run(&self) -> u64 {
        match self {
            Action::Select { run, .. } | Action::Update { run, .. } => *run,
        }
    }
}

#[derive(Clone, Copy)]
enum ButtonState<'a> {
    Ready,
    Running,
    Done(&'a str),
}

impl Run {
    fn new(id: u64, targets: Vec<UpdateTarget>) -> Self {
        let selected = vec![true; targets.len()];
        Self {
            id,
            targets,
            selected,
        }
    }

    fn message_count(&self) -> usize {
        self.targets.len().div_ceil(TARGETS_PER_MESSAGE)
    }

    fn message_range(&self, message: usize) -> Range<usize> {
        let start = (message * TARGETS_PER_MESSAGE).min(self.targets.len());
        start..(start + TARGETS_PER_MESSAGE).min(self.targets.len())
    }

    fn menu_range(&self, message: usize, menu: usize) -> Range<usize> {
        let message_range = self.message_range(message);
        let start = (message_range.start + menu * OPTIONS_PER_MENU).min(message_range.end);
        start..(start + OPTIONS_PER_MENU).min(message_range.end)
    }

    /// Applies the values of one select menu. Values outside the menu are ignored.
    fn select(&mut self, message: usize, menu: usize, values: &[String]) {
        let range = self.menu_range(message, menu);
        for index in range.clone() {
            self.selected[index] = false;
        }
        for index in values.iter().filter_map(|it| it.parse::<usize>().ok()) {
            if range.contains(&index) {
                self.selected[index] = true;
            }
        }
    }

    fn selected_targets(&self, message: usize) -> Vec<UpdateTarget> {
        self.message_range(message)
            .filter(|&index| self.selected[index])
            .map(|index| self.targets[index].clone())
            .collect()
    }

    fn render(&self, message: usize, button: ButtonState) -> Vec<CreateActionRow> {
        let disabled = !matches!(button, ButtonState::Ready);
        let indices: Vec<usize> = self.message_range(message).collect();

        let mut rows: Vec<CreateActionRow> = indices
            .chunks(OPTIONS_PER_MENU)
            .enumerate()
            .map(|(menu, chunk)| {
                let options = chunk
                    .iter()
                    .map(|&index| {
                        let target = &self.targets[index];
                        CreateSelectMenuOption::new(
                            truncate(&target.container.name, MAX_OPTION_TEXT),
                            index.to_string(),
                        )
                        .description(truncate(&target.base.friendly(), MAX_OPTION_TEXT))
                        .default_selection(self.selected[index])
                    })
                    .collect();
                let select = CreateSelectMenu::new(
                    format!("lh:{}:sel:{message}:{menu}", self.id),
                    CreateSelectMenuKind::String { options },
                )
                .placeholder("Nothing selected")
                .min_values(0)
                // Discord rejects max_values larger than the number of options
                .max_values(chunk.len() as u8)
                .disabled(disabled);
                CreateActionRow::SelectMenu(select)
            })
            .collect();

        let id = format!("lh:{}:go:{message}", self.id);
        let button = match button {
            ButtonState::Ready => CreateButton::new(id)
                .label("Update selected")
                .emoji('🚀')
                .style(ButtonStyle::Primary),
            ButtonState::Running => CreateButton::new(id)
                .label("Updating…")
                .style(ButtonStyle::Secondary)
                .disabled(true),
            ButtonState::Done(summary) => CreateButton::new(id)
                .label(truncate(summary, 80))
                .emoji('✅')
                .style(ButtonStyle::Success)
                .disabled(true),
        };
        rows.push(CreateActionRow::Buttons(vec![button]));
        rows
    }
}

impl Controls {
    pub fn new(sender: DiscordSender, updater: Arc<Updater>) -> Self {
        Self {
            sender,
            updater,
            state: Mutex::new(None),
        }
    }

    /// Posts selection menus for the given updates, replacing all older controls.
    pub async fn post(&self, updates: &[ManifestUpdate]) -> Result<(), Report> {
        let targets = targets_of(updates);
        if targets.is_empty() {
            return Ok(());
        }

        let mut state = self.state.lock().await;
        let id = state.as_ref().map_or(0, |it| it.id) + 1;
        let run = Run::new(id, targets);

        for message in 0..run.message_count() {
            let content = if run.message_count() > 1 {
                format!(
                    "Select containers to update ({}/{})",
                    message + 1,
                    run.message_count()
                )
            } else {
                "Select containers to update".to_string()
            };
            self.sender
                .send(
                    Some(content),
                    Vec::new(),
                    run.render(message, ButtonState::Ready),
                )
                .await?;
        }
        *state = Some(run);
        Ok(())
    }

    async fn handle(&self, ctx: &Context, interaction: ComponentInteraction) {
        let custom_id = interaction.data.custom_id.clone();
        info!(custom_id, "Received component interaction");

        let result = match Action::parse(&custom_id) {
            None => reply_ephemeral(ctx, &interaction, "I don't know that action :/").await,
            Some(action) => self.handle_action(ctx, &interaction, action).await,
        };
        if let Err(e) = result {
            warn!(custom_id, "Could not handle interaction: {e}");
        }
    }

    async fn handle_action(
        &self,
        ctx: &Context,
        interaction: &ComponentInteraction,
        action: Action,
    ) -> Result<(), Report> {
        let mut state = self.state.lock().await;
        let Some(run) = state.as_mut().filter(|it| it.id == action.run()) else {
            return reply_ephemeral(
                ctx,
                interaction,
                "Sorry, updating is only supported for the latest notification",
            )
            .await;
        };

        match action {
            Action::Select { message, menu, .. } => {
                if let ComponentInteractionDataKind::StringSelect { values } =
                    &interaction.data.kind
                {
                    run.select(message, menu, values);
                }
                interaction
                    .create_response(&ctx.http, CreateInteractionResponse::Acknowledge)
                    .await
                    .context("Could not acknowledge the Discord container selection")?;
                Ok(())
            }
            Action::Update { message, .. } => {
                let targets = run.selected_targets(message);
                if targets.is_empty() {
                    return reply_ephemeral(ctx, interaction, "Nothing selected").await;
                }

                let running = run.render(message, ButtonState::Running);
                let snapshot = run.clone();
                drop(state);

                interaction
                    .create_response(
                        &ctx.http,
                        CreateInteractionResponse::UpdateMessage(
                            CreateInteractionResponseMessage::new().components(running),
                        ),
                    )
                    .await
                    .context("Could not mark Discord update controls as running")?;
                self.spawn_update(ctx, interaction.clone(), snapshot, message, targets);
                Ok(())
            }
        }
    }

    /// Runs the update in the background, as Discord expects a response within three seconds.
    fn spawn_update(
        &self,
        ctx: &Context,
        interaction: ComponentInteraction,
        run: Run,
        message: usize,
        targets: Vec<UpdateTarget>,
    ) {
        let http = ctx.http.clone();
        let updater = self.updater.clone();
        let sender = self.sender.clone();

        tokio::spawn(async move {
            let components = match updater.apply(&targets).await {
                Ok(summary) => {
                    info!(summary, "Update finished");
                    run.render(message, ButtonState::Done(&summary))
                }
                Err(e) => {
                    warn!("Update failed: {e}");
                    if let Err(e) = sender.send_error(&format!("{e}")).await {
                        warn!("Could not report update failure: {e}");
                    }
                    run.render(message, ButtonState::Ready)
                }
            };
            let edit = EditInteractionResponse::new().components(components);
            if let Err(e) = interaction.edit_response(&http, edit).await {
                warn!("Could not update controls message: {e}");
            }
        });
    }
}

/// Routes Discord gateway events to the controls.
pub struct Handler(pub Arc<Controls>);

#[serenity::async_trait]
impl EventHandler for Handler {
    async fn ready(&self, _ctx: Context, ready: Ready) {
        info!(user = ready.user.name, "Discord bot connected");
    }

    async fn interaction_create(&self, ctx: Context, interaction: Interaction) {
        if let Interaction::Component(component) = interaction {
            self.0.handle(&ctx, component).await;
        }
    }
}

async fn reply_ephemeral(
    ctx: &Context,
    interaction: &ComponentInteraction,
    text: &str,
) -> Result<(), Report> {
    interaction
        .create_response(
            &ctx.http,
            CreateInteractionResponse::Message(
                CreateInteractionResponseMessage::new()
                    .content(text)
                    .ephemeral(true),
            ),
        )
        .await
        .context("Could not send an ephemeral Discord interaction reply")?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::images::reference::ImageRef;
    use crate::updates::model::ContainerRef;

    fn run(count: usize) -> Run {
        let base = ImageRef::parse("nginx:stable").unwrap();
        let targets = (0..count)
            .map(|i| UpdateTarget {
                container: ContainerRef {
                    id: i.to_string(),
                    name: format!("container-{i}"),
                    is_lighthouse: false,
                },
                base: base.clone(),
            })
            .collect();
        Run::new(7, targets)
    }

    fn menus(rows: &[CreateActionRow]) -> Vec<serde_json::Value> {
        rows.iter()
            .map(|it| serde_json::to_value(it).unwrap()["components"][0].clone())
            .filter(|it| it["type"] == 3)
            .collect()
    }

    #[test]
    fn splits_many_containers_into_valid_menus() {
        let run = run(130);
        assert_eq!(run.message_count(), 2);

        let first = run.render(0, ButtonState::Ready);
        assert_eq!(first.len(), 5, "4 menus + 1 button row");
        for menu in menus(&first) {
            let options = menu["options"].as_array().unwrap().len();
            assert_eq!(options, OPTIONS_PER_MENU);
            assert_eq!(menu["max_values"], options);
        }

        let second = menus(&run.render(1, ButtonState::Ready));
        assert_eq!(second.len(), 2);
        assert_eq!(second[1]["options"].as_array().unwrap().len(), 5);
        assert_eq!(second[1]["max_values"], 5);
    }

    #[test]
    fn a_single_container_has_max_values_one() {
        let menus = menus(&run(1).render(0, ButtonState::Ready));
        assert_eq!(menus.len(), 1);
        assert_eq!(menus[0]["max_values"], 1);
    }

    #[test]
    fn selection_is_scoped_to_its_menu() {
        let mut run = run(60);
        // Only container 3 stays selected in the first menu, a forged out-of-menu value is ignored
        run.select(0, 0, &["3".into(), "40".into()]);
        let selected: Vec<String> = run
            .selected_targets(0)
            .into_iter()
            .map(|it| it.container.name)
            .collect();
        assert_eq!(selected.len(), 1 + 35);
        assert_eq!(selected[0], "container-3");
        assert!(selected.contains(&"container-40".to_string()));
    }

    #[test]
    fn parses_custom_ids() {
        assert_eq!(
            Action::parse("lh:3:sel:1:2"),
            Some(Action::Select {
                run: 3,
                message: 1,
                menu: 2
            })
        );
        assert_eq!(
            Action::parse("lh:3:go:0"),
            Some(Action::Update { run: 3, message: 0 })
        );
        assert_eq!(Action::parse("update-12345"), None);
    }
}
