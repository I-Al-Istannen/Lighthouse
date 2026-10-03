use std::sync::Arc;
use std::time::Duration;

use futures_util::StreamExt;
use rootcause::prelude::*;
use serde::Deserialize;
use tokio::sync::Mutex;
use tracing::{debug, info, warn};

use super::text::truncate;
use crate::images::metadata::ImageMetadata;
use crate::updates::model::{ManifestUpdate, TagUpdate};
use crate::updates::updater::{UpdateTarget, Updater, targets_of};

const ICON: &str =
    "https://raw.githubusercontent.com/I-Al-Istannen/Lighthouse/master/media/lighthouse.png";
/// Body of the message the "Update" action publishes. The listener only reacts to exactly this.
const UPDATE_REQUEST: &str = "Update all containers";
/// Longer messages are turned into attachments by ntfy
const MAX_MESSAGE: usize = 4000;

/// Notifications through ntfy, optionally with an "Update" action that the listener picks up.
pub struct Ntfy {
    http: reqwest::Client,
    url: String,
    hostname: Option<String>,
    /// What the update action applies. `None` if no updater is configured.
    pending: Option<Mutex<Vec<UpdateTarget>>>,
}

impl Ntfy {
    pub fn new(
        http: reqwest::Client,
        url: String,
        hostname: Option<String>,
        with_updater: bool,
    ) -> Self {
        Self {
            http,
            url: url.trim_end_matches('/').to_string(),
            hostname,
            pending: with_updater.then(|| Mutex::new(Vec::new())),
        }
    }

    fn title(&self, title: &str) -> String {
        match &self.hostname {
            Some(host) => format!("{title} ({host})"),
            None => title.to_string(),
        }
    }

    async fn publish(
        &self,
        title: &str,
        tags: &str,
        click: Option<String>,
        body: String,
    ) -> Result<(), Report> {
        let mut request = self
            .http
            .post(&self.url)
            .header("X-Title", self.title(title))
            .header("X-Tags", tags)
            .header("X-Icon", ICON)
            .body(truncate(&body, MAX_MESSAGE));
        if let Some(click) = click {
            request = request.header("X-Click", click);
        }
        request
            .send()
            .await
            .context("Could not publish the ntfy notification")?
            .error_for_status()
            .context("ntfy rejected the notification")?;
        Ok(())
    }

    pub async fn digest_updates(&self, updates: &[ManifestUpdate]) -> Result<(), Report> {
        info!(count = updates.len(), "Notifying ntfy about image updates");
        for update in updates {
            let empty_metadata = ImageMetadata::default();
            let metadata = update.metadata.as_ref().unwrap_or(&empty_metadata);
            let containers: Vec<&str> = update.containers().map(|it| it.name.as_str()).collect();
            let images: Vec<String> = update
                .affected
                .iter()
                .flat_map(|it| it.display_names())
                .collect();

            let mut body = format!(
                "Remote: {}\nContainers: {}\nImages: {}\n",
                update.base,
                containers.join(", "),
                images.join(", ")
            );
            push_metadata(&mut body, metadata);
            body.push_str(&format!("Digest: {}", update.remote_digest));

            let click = metadata
                .release
                .as_ref()
                .map(|it| it.url.clone())
                .or_else(|| update.base.web_url());
            self.publish("Lighthouse", "mailbox_with_mail", click, body)
                .await
                .context("Could not notify ntfy about the digest update")
                .attach_with(|| format!("Image: {}", update.base))?;
        }

        Ok(())
    }

    /// Refresh targets from the full check, independently of notification deduplication.
    pub async fn update_action(&self, updates: &[ManifestUpdate]) -> Result<(), Report> {
        if let Some(pending) = &self.pending {
            *pending.lock().await = targets_of(updates);
            if updates.is_empty() {
                return Ok(());
            }
            // Make sure the action arrives after the notifications
            tokio::time::sleep(Duration::from_secs(2)).await;
            self.publish_update_action(updates.len()).await?;
        }
        Ok(())
    }

    async fn publish_update_action(&self, count: usize) -> Result<(), Report> {
        // The action syntax is comma separated, keep user input from breaking it
        let title = self
            .title("Lighthouse Update")
            .replace([',', ';', '"'], " ");
        let action = format!(
            "http, Update, {}, body={UPDATE_REQUEST}, headers.X-Title={title}, headers.X-Tags=page_facing_up",
            self.url
        );
        self.http
            .post(&self.url)
            .header("X-Title", &title)
            .header("X-Tags", "envelope")
            .header("X-Icon", ICON)
            .header("X-Actions", action)
            .body(format!("Click to apply {count} update(s)"))
            .send()
            .await
            .context("Could not publish the ntfy update action")?
            .error_for_status()
            .context("ntfy rejected the update action")?;
        Ok(())
    }

    pub async fn tag_updates(&self, updates: &[TagUpdate]) -> Result<(), Report> {
        info!(count = updates.len(), "Notifying ntfy about tag updates");
        for update in updates {
            let empty_metadata = ImageMetadata::default();
            let metadata = update.metadata.as_ref().unwrap_or(&empty_metadata);
            let containers: Vec<&str> = update
                .containers
                .iter()
                .map(|it| it.name.as_str())
                .collect();

            let mut body = format!(
                "Manual update required - Tag/version change\n\nImage: {}\nCurrent: {} → New: {}\nContainers: {}\n",
                update.image.friendly_name(),
                update.image.tag,
                update.new_tag,
                containers.join(", ")
            );
            push_metadata(&mut body, metadata);

            let click = metadata
                .release
                .as_ref()
                .map(|it| it.url.clone())
                .or_else(|| update.image.web_url());
            self.publish(
                "Lighthouse Version Upgrade",
                "arrow_up,package",
                click,
                body,
            )
            .await
            .context("Could not notify ntfy about the tag update")
            .attach_with(|| format!("Image: {}", update.image))?;
        }
        Ok(())
    }

    pub async fn error(&self, error: &str) -> Result<(), Report> {
        self.publish("Lighthouse Error", "warning", None, error.to_string())
            .await
    }

    /// Listens for clicks on the "Update" action forever.
    pub async fn listen(self: Arc<Self>, updater: Arc<Updater>) {
        let url = format!("{}/json", self.url);
        loop {
            match self.listen_once(&url, &updater).await {
                Ok(()) => info!("ntfy subscription ended, reconnecting"),
                Err(e) => warn!("ntfy subscription failed, retrying in 30 seconds: {e}"),
            }
            tokio::time::sleep(Duration::from_secs(30)).await;
        }
    }

    async fn listen_once(
        self: &Arc<Self>,
        url: &str,
        updater: &Arc<Updater>,
    ) -> Result<(), Report> {
        let response = self
            .http
            .get(url)
            .send()
            .await
            .context("Could not connect to the ntfy subscription")?
            .error_for_status()
            .context("ntfy rejected the subscription")?;
        info!("Listening for update requests on ntfy");

        let mut stream = response.bytes_stream();
        let mut buffer = Vec::new();
        while let Some(chunk) = stream.next().await {
            buffer
                .extend_from_slice(&chunk.context("Could not read the ntfy subscription stream")?);
            while let Some(newline) = buffer.iter().position(|&it| it == b'\n') {
                let line: Vec<u8> = buffer.drain(..=newline).collect();
                if is_update_request(&line) {
                    info!("Received update request from ntfy");
                    tokio::spawn(self.clone().apply(updater.clone()));
                }
            }
        }
        Ok(())
    }

    async fn apply(self: Arc<Self>, updater: Arc<Updater>) {
        let Some(pending) = &self.pending else {
            return;
        };
        let targets = pending.lock().await.clone();
        let result = match updater.apply(&targets).await {
            Ok(summary) => {
                self.publish("Lighthouse Update", "rocket", None, summary)
                    .await
            }
            Err(e) => {
                warn!("Update failed: {e}");
                self.error(&format!("{e}")).await
            }
        };
        if let Err(e) = result {
            warn!("Could not report update result: {e}");
        }
    }
}

fn push_metadata(body: &mut String, metadata: &ImageMetadata) {
    if let Some(created) = metadata.created {
        body.push_str(&format!("Updated: {created}"));
        if let Some(user) = &metadata.updated_by {
            body.push_str(&format!(" by {user}"));
        }
        body.push('\n');
    }
    if let Some(version) = &metadata.version {
        body.push_str(&format!("Version: {version}\n"));
    }
    if let Some(release) = &metadata.release {
        body.push_str(&format!("Release: {} ({})\n", release.name, release.url));
    }
    if let Some(source) = &metadata.source {
        body.push_str(&format!("Source: {}\n", source.url));
    }
}

#[derive(Deserialize)]
struct NtfyEvent {
    event: String,
    message: Option<String>,
}

fn is_update_request(line: &[u8]) -> bool {
    let Ok(event) = serde_json::from_slice::<NtfyEvent>(line) else {
        debug!(line = %String::from_utf8_lossy(line), "Ignoring unparsable ntfy line");
        return false;
    };
    event.event == "message" && event.message.as_deref() == Some(UPDATE_REQUEST)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_reacts_to_update_requests() {
        assert!(is_update_request(
            br#"{"id":"x","event":"message","topic":"t","message":"Update all containers"}"#
        ));
        // Our own notifications on the same topic must not trigger updates
        assert!(!is_update_request(
            br#"{"id":"y","event":"message","topic":"t","message":"Remote: nginx:stable"}"#
        ));
        assert!(!is_update_request(
            br#"{"id":"z","event":"keepalive","topic":"t"}"#
        ));
        assert!(!is_update_request(b"garbage"));
    }
}
