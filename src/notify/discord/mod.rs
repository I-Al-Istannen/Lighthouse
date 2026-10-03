pub mod bot;
pub mod embed;

use std::sync::Arc;

use rootcause::prelude::*;
use serenity::all::{
    ChannelId, CreateActionRow, CreateEmbed, CreateMessage, ExecuteWebhook, Http, Webhook,
};
use tracing::info;

use self::embed::Embed;
use super::text::truncate;
use crate::notify::discord::bot::Controls;
use crate::updates::model::{ManifestUpdate, TagUpdate};

pub const AVATAR_URL: &str =
    "https://github.com/I-Al-Istannen/Lighthouse/blob/master/media/lighthouse.png?raw=true";

#[derive(Clone)]
enum Target {
    Webhook(Arc<Webhook>),
    Channel(ChannelId),
}

/// Sends messages either through a webhook or as a bot into a channel.
#[derive(Clone)]
pub struct DiscordSender {
    http: Arc<Http>,
    target: Target,
    pub hostname: Option<String>,
}

impl DiscordSender {
    pub async fn webhook(url: &str, hostname: Option<String>) -> Result<Self, Report> {
        let http = Arc::new(Http::new(""));
        let webhook = Webhook::from_url(&http, url)
            .await
            .context("Could not resolve the Discord webhook, is the URL correct?")?;
        Ok(Self {
            http,
            target: Target::Webhook(Arc::new(webhook)),
            hostname,
        })
    }

    pub fn channel(http: Arc<Http>, channel: ChannelId, hostname: Option<String>) -> Self {
        Self {
            http,
            target: Target::Channel(channel),
            hostname,
        }
    }

    pub async fn send(
        &self,
        content: Option<String>,
        embeds: Vec<CreateEmbed>,
        components: Vec<CreateActionRow>,
    ) -> Result<(), Report> {
        match &self.target {
            Target::Webhook(webhook) => {
                let username = match &self.hostname {
                    Some(host) => format!("Lighthouse ({host})"),
                    None => "Lighthouse".to_string(),
                };
                let mut builder = ExecuteWebhook::new()
                    .username(truncate(&username, 80))
                    .avatar_url(AVATAR_URL)
                    .embeds(embeds);
                if let Some(content) = content {
                    builder = builder.content(content);
                }
                if !components.is_empty() {
                    builder = builder.components(components);
                }
                webhook
                    .execute(&self.http, true, builder)
                    .await
                    .context("Could not execute Discord webhook")?;
            }
            Target::Channel(channel) => {
                let mut builder = CreateMessage::new().embeds(embeds).components(components);
                if let Some(content) = content {
                    builder = builder.content(content);
                }
                channel
                    .send_message(&self.http, builder)
                    .await
                    .context("Could not send Discord message")?;
            }
        }
        Ok(())
    }

    pub async fn send_embeds(
        &self,
        embeds: Vec<Embed>,
        first_content: Option<String>,
    ) -> Result<(), Report> {
        let mut content = first_content;
        for message in embed::pack(embeds) {
            let embeds = message.iter().map(Embed::to_serenity).collect();
            // Only ping once, not for every batch
            self.send(content.take(), embeds, Vec::new()).await?;
        }
        Ok(())
    }

    pub async fn send_error(&self, error: &str) -> Result<(), Report> {
        let embed = embed::error_embed(error, self.hostname.as_deref());
        self.send_embeds(vec![embed], None).await
    }
}

pub struct Discord {
    pub sender: DiscordSender,
    pub mention: Option<String>,
    pub mention_text: Option<String>,
    /// Update buttons, only available in bot mode with an updater
    pub controls: Option<Arc<Controls>>,
}

impl Discord {
    pub async fn digest_updates(&self, updates: &[ManifestUpdate]) -> Result<(), Report> {
        info!(
            count = updates.len(),
            "Notifying Discord about image updates"
        );
        let embeds = updates
            .iter()
            .map(|it| embed::digest_embed(it, self.sender.hostname.as_deref()))
            .collect();

        let content = self.mention.as_ref().map(|mention| {
            let mut images: Vec<String> = updates.iter().map(|it| it.base.friendly()).collect();
            images.dedup();
            embed::mention_content(
                mention,
                self.mention_text.as_deref(),
                "I got some news!",
                &images.join(" "),
            )
        });

        self.sender.send_embeds(embeds, content).await?;
        if let Some(controls) = &self.controls {
            controls
                .post(updates)
                .await
                .context("Could not post Discord update controls")?;
        }
        Ok(())
    }

    pub async fn tag_updates(&self, updates: &[TagUpdate]) -> Result<(), Report> {
        info!(count = updates.len(), "Notifying Discord about tag updates");
        let embeds = updates
            .iter()
            .map(|it| embed::tag_embed(it, self.sender.hostname.as_deref()))
            .collect();

        let content = self.mention.as_ref().map(|mention| {
            let summary: Vec<String> = updates
                .iter()
                .map(|it| {
                    format!(
                        "{}: {} → {}",
                        it.image.friendly_name(),
                        it.image.tag,
                        it.new_tag
                    )
                })
                .collect();
            embed::mention_content(
                mention,
                self.mention_text.as_deref(),
                "Version upgrades available!",
                &summary.join(", "),
            )
        });

        self.sender.send_embeds(embeds, content).await
    }
}
