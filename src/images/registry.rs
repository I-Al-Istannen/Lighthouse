use std::collections::{BTreeMap, HashSet};

use jiff::Timestamp;
use oci_client::Client;
use oci_client::client::{ClientConfig, ClientProtocol};
use rootcause::prelude::*;
use serde::Deserialize;
use tracing::debug;

use crate::images::auth::DockerAuth;
use crate::images::digest::ManifestDigest;
use crate::images::reference::ImageRef;

const TAG_PAGE_SIZE: usize = 1000;
/// Safety net against registries that ignore the `last` parameter in creative ways
const MAX_TAG_PAGES: usize = 200;

/// Talks to OCI registries using the distribution API.
pub struct Registry {
    client: Client,
    auth: DockerAuth,
}

/// Information from an image's config blob and manifest.
#[derive(Debug, Default)]
pub struct ImageConfigInfo {
    pub created: Option<Timestamp>,
    /// Config labels merged with manifest annotations (labels win)
    pub labels: BTreeMap<String, String>,
}

impl Registry {
    /// `insecure` registries (`host:port`) are accessed over plain HTTP.
    pub fn new(auth: DockerAuth, insecure: Vec<String>) -> Self {
        let protocol = if insecure.is_empty() {
            ClientProtocol::Https
        } else {
            ClientProtocol::HttpsExcept(insecure)
        };
        let client = Client::new(ClientConfig {
            protocol,
            user_agent: concat!("lighthouse/", env!("CARGO_PKG_VERSION")),
            ..Default::default()
        });
        Self { client, auth }
    }

    pub fn auth(&self) -> &DockerAuth {
        &self.auth
    }

    /// The manifest digest of a tag. This is what local images list as `RepoDigests`.
    ///
    /// Uses a HEAD request, which does not count against Docker Hub's pull rate limit.
    pub async fn digest(&self, image: &ImageRef) -> Result<ManifestDigest, Report> {
        let auth = self.auth.registry_auth(&image.registry).await;
        let digest = self
            .client
            .fetch_manifest_digest(&image.to_reference(), &auth)
            .await
            .context_with(|| format!("Could not fetch remote digest for '{image}'"))?;
        debug!(%image, digest, "Fetched remote digest");
        Ok(digest.into())
    }

    /// All tags of the image's repository, following pagination.
    pub async fn tags(&self, image: &ImageRef) -> Result<Vec<String>, Report> {
        let auth = self.auth.registry_auth(&image.registry).await;
        let reference = image.to_reference();

        let mut seen = HashSet::new();
        let mut tags = Vec::new();
        let mut last: Option<String> = None;

        for _ in 0..MAX_TAG_PAGES {
            let page = self
                .client
                .list_tags(&reference, &auth, Some(TAG_PAGE_SIZE), last.as_deref())
                .await
                .context_with(|| format!("Could not list tags for '{}'", image.friendly_name()))?;

            let new_tags: Vec<String> = page
                .tags
                .into_iter()
                .filter(|tag| seen.insert(tag.clone()))
                .collect();
            // Some registries cap the page size below what we asked for, so a short page does
            // not mean we are done. An empty (or repeated) page does.
            let Some(new_last) = new_tags.last().cloned() else {
                break;
            };
            tags.extend(new_tags);
            last = Some(new_last);
        }

        debug!(
            image = image.friendly_name(),
            count = tags.len(),
            "Fetched tags"
        );
        Ok(tags)
    }

    /// Fetches the config of the image (for the current platform, if it is a multi-arch index).
    ///
    /// This is a manifest GET and counts against Docker Hub's pull rate limit, so only call it for
    /// updates that will actually be reported.
    pub async fn config(&self, image: &ImageRef) -> Result<ImageConfigInfo, Report> {
        let auth = self.auth.registry_auth(&image.registry).await;
        let (manifest, _digest, config) = self
            .client
            .pull_manifest_and_config(&image.to_reference(), &auth)
            .await
            .context_with(|| format!("Could not fetch image config for '{image}'"))?;

        let mut info = parse_image_config(&config)
            .context_with(|| format!("Invalid image config for '{image}'"))?;
        for (key, value) in manifest.annotations.unwrap_or_default() {
            info.labels.entry(key).or_insert(value);
        }
        Ok(info)
    }
}

#[derive(Deserialize)]
struct RawImageConfig {
    created: Option<String>,
    config: Option<RawContainerConfig>,
}

#[derive(Deserialize)]
struct RawContainerConfig {
    #[serde(rename = "Labels")]
    labels: Option<BTreeMap<String, String>>,
}

fn parse_image_config(config: &str) -> Result<ImageConfigInfo, Report> {
    let raw: RawImageConfig = serde_json::from_str(config)?;
    let created = raw.created.and_then(|it| it.parse().ok());

    Ok(ImageConfigInfo {
        created,
        labels: raw.config.and_then(|it| it.labels).unwrap_or_default(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_config_blob() {
        let config = r#"{
            "architecture": "amd64",
            "created": "2025-03-01T12:34:56.789Z",
            "config": { "Labels": { "org.opencontainers.image.source": "https://github.com/a/b" } }
        }"#;
        let info = parse_image_config(config).unwrap();
        assert_eq!(
            info.created.unwrap().to_string(),
            "2025-03-01T12:34:56.789Z"
        );
        assert_eq!(
            info.labels["org.opencontainers.image.source"],
            "https://github.com/a/b"
        );
    }

    #[test]
    fn tolerates_missing_labels() {
        let info = parse_image_config(r#"{"config": {"Labels": null}}"#).unwrap();
        assert!(info.labels.is_empty());
        assert!(info.created.is_none());
    }

    /// Hits the real Docker Hub and GHCR. Run with `cargo test -- --ignored`.
    #[tokio::test]
    #[ignore = "needs network access"]
    async fn talks_to_real_registries() {
        crate::app::install_crypto_provider();
        let registry = Registry::new(DockerAuth::default(), Vec::new());

        let nginx = ImageRef::parse("nginx:stable").unwrap();
        assert!(
            registry
                .digest(&nginx)
                .await
                .unwrap()
                .as_str()
                .starts_with("sha256:")
        );
        let tags = registry.tags(&nginx).await.unwrap();
        assert!(
            tags.len() > TAG_PAGE_SIZE,
            "pagination should yield all tags"
        );
        assert!(tags.iter().any(|it| it == "stable"));

        let ghcr = ImageRef::parse("ghcr.io/home-assistant/home-assistant:stable").unwrap();
        let config = registry.config(&ghcr).await.unwrap();
        assert!(
            config
                .labels
                .contains_key("org.opencontainers.image.source")
        );
    }
}
