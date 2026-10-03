pub mod discord;
pub mod ntfy;
mod text;

use std::sync::Arc;

use rootcause::prelude::*;
use tracing::warn;

use crate::updates::model::{ManifestUpdate, TagUpdate};

pub enum Notifier {
    Discord(discord::Discord),
    Ntfy(Arc<ntfy::Ntfy>),
}

impl Notifier {
    /// Update controls reflect all currently outdated images, including announced ones.
    pub async fn update_action(&self, updates: &[ManifestUpdate]) -> Result<(), Report> {
        match self {
            Notifier::Ntfy(it) => it.update_action(updates).await,
            Notifier::Discord(_) => Ok(()),
        }
    }

    pub async fn digest_updates(&self, updates: &[ManifestUpdate]) -> Result<(), Report> {
        if updates.is_empty() {
            return Ok(());
        }
        match self {
            Notifier::Discord(it) => it.digest_updates(updates).await,
            Notifier::Ntfy(it) => it.digest_updates(updates).await,
        }
    }

    pub async fn tag_updates(&self, updates: &[TagUpdate]) -> Result<(), Report> {
        if updates.is_empty() {
            return Ok(());
        }
        match self {
            Notifier::Discord(it) => it.tag_updates(updates).await,
            Notifier::Ntfy(it) => it.tag_updates(updates).await,
        }
    }

    /// Best effort: failures are only logged, there is nobody left to tell.
    pub async fn error(&self, error: &Report) {
        let text = format!("{error}");
        let result = match self {
            Notifier::Discord(it) => it.sender.send_error(&text).await,
            Notifier::Ntfy(it) => it.error(&text).await,
        };
        if let Err(e) = result {
            warn!("Could not send error notification: {e}");
        }
    }
}
