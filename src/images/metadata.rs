use std::sync::Arc;

use jiff::Timestamp;
use octocrab::Octocrab;
use reqwest::{StatusCode, Url};
use rootcause::prelude::*;
use serde::Deserialize;
use tracing::{debug, warn};

use crate::images::reference::ImageRef;
use crate::images::registry::Registry;

const LABEL_SOURCE: &str = "org.opencontainers.image.source";
const LABEL_SOURCE_LEGACY: &str = "org.label-schema.vcs-url";
const LABEL_REVISION: &str = "org.opencontainers.image.revision";
const LABEL_VERSION: &str = "org.opencontainers.image.version";
const LABEL_TITLE: &str = "org.opencontainers.image.title";
const LABEL_URL: &str = "org.opencontainers.image.url";

/// Everything we could find out about a remote image. All parts are optional, as images (and
/// registries) differ wildly in what they provide.
#[derive(Clone, Debug, Default)]
pub struct ImageMetadata {
    pub created: Option<Timestamp>,
    pub updated_by: Option<String>,
    pub title: Option<String>,
    pub version: Option<String>,
    pub homepage: Option<String>,
    pub source: Option<SourceRepo>,
    pub revision: Option<String>,
    pub release: Option<Release>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SourceRepo {
    pub url: String,
    pub forge: Forge,
    /// `owner/repo` for known forges
    pub path: Option<String>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Forge {
    GitHub,
    GitLab,
    Codeberg,
    Other,
}

#[derive(Clone, Debug)]
pub struct Release {
    pub name: String,
    pub url: String,
    pub body: Option<String>,
}

impl ImageMetadata {
    /// Fills all fields missing in `self` from `other`.
    fn fill_from(&mut self, other: ImageMetadata) {
        self.created = self.created.or(other.created);
        self.updated_by = self.updated_by.take().or(other.updated_by);
        self.title = self.title.take().or(other.title);
        self.version = self.version.take().or(other.version);
        self.homepage = self.homepage.take().or(other.homepage);
        self.source = self.source.take().or(other.source);
        self.revision = self.revision.take().or(other.revision);
        self.release = self.release.take().or(other.release);
    }

    /// A link to the commit the image was built from, if the forge is known.
    pub fn revision_url(&self) -> Option<String> {
        let source = self.source.as_ref()?;
        let revision = self.revision.as_ref()?;
        match source.forge {
            Forge::GitHub | Forge::Codeberg => Some(format!("{}/commit/{revision}", source.url)),
            Forge::GitLab => Some(format!("{}/-/commit/{revision}", source.url)),
            Forge::Other => None,
        }
    }
}

impl SourceRepo {
    /// Parses a source repository HTTP(S) URL, stripping a trailing `.git` for known forges.
    pub fn parse(raw: &str) -> Option<Self> {
        let mut url = Url::parse(raw.trim()).ok()?;
        if !matches!(url.scheme(), "http" | "https") {
            return None;
        }
        let forge = match url.host_str()? {
            "github.com" => Forge::GitHub,
            "gitlab.com" => Forge::GitLab,
            "codeberg.org" => Forge::Codeberg,
            _ => Forge::Other,
        };
        if forge == Forge::Other {
            return Some(Self {
                url: url.into(),
                forge,
                path: None,
            });
        }

        let path = url.path().trim_matches('/');
        let path = path.strip_suffix(".git").unwrap_or(path).to_string();
        let segments: Vec<&str> = path.split('/').collect();
        if segments.iter().any(|segment| segment.is_empty())
            || segments.len() < 2
            || (forge != Forge::GitLab && segments.len() != 2)
        {
            return None;
        }
        url.set_path(&path);
        url.set_query(None);
        url.set_fragment(None);
        Some(Self {
            url: url.into(),
            forge,
            path: Some(path),
        })
    }
}

/// Collects metadata from all available sources: the image itself, its source forge and the
/// registry's own API. Earlier sources win, later ones only fill gaps.
pub struct MetadataFetcher {
    registry: Arc<Registry>,
    http: reqwest::Client,
    github: Octocrab,
}

impl MetadataFetcher {
    pub fn new(
        registry: Arc<Registry>,
        http: reqwest::Client,
        github_token: Option<String>,
    ) -> Result<Self, Report> {
        let mut builder = Octocrab::builder();
        if let Some(token) = github_token {
            builder = builder.personal_token(token);
        }
        let github = builder
            .build_with_reqwest(http.clone())
            .context("Could not initialize the GitHub client")?;
        Ok(Self {
            registry,
            http,
            github,
        })
    }

    /// Never fails: missing metadata only makes notifications less detailed.
    pub async fn fetch(&self, image: &ImageRef) -> ImageMetadata {
        let mut metadata = match self.image_config_metadata(image).await {
            Ok(it) => it,
            Err(e) => {
                warn!(%image, "Could not read image config for metadata: {e}");
                ImageMetadata::default()
            }
        };

        if let Some(source) = metadata.source.clone() {
            match self
                .release(&source, image, metadata.version.as_deref())
                .await
            {
                Ok(release) => metadata.release = release,
                Err(e) => warn!(%image, repo = source.url, "Could not fetch release notes: {e}"),
            }
        }

        if image.is_docker_hub() {
            match self.docker_hub_metadata(image).await {
                Ok(hub) => metadata.fill_from(hub),
                Err(e) => warn!(%image, "Could not fetch Docker Hub metadata: {e}"),
            }
        }

        debug!(%image, ?metadata, "Collected metadata");
        metadata
    }

    async fn image_config_metadata(&self, image: &ImageRef) -> Result<ImageMetadata, Report> {
        let config = self.registry.config(image).await?;
        let label = |key: &str| {
            config
                .labels
                .get(key)
                .map(|it| it.trim().to_string())
                .filter(|it| !it.is_empty())
        };

        Ok(ImageMetadata {
            // Reproducible builds pin this to the epoch (or some other fixed date)
            created: config
                .created
                .filter(|it| it.to_zoned(jiff::tz::TimeZone::UTC).year() >= 2000),
            title: label(LABEL_TITLE),
            version: label(LABEL_VERSION),
            homepage: label(LABEL_URL),
            source: label(LABEL_SOURCE)
                .or_else(|| label(LABEL_SOURCE_LEGACY))
                .and_then(|it| SourceRepo::parse(&it)),
            revision: label(LABEL_REVISION),
            ..Default::default()
        })
    }

    async fn release(
        &self,
        source: &SourceRepo,
        image: &ImageRef,
        version_label: Option<&str>,
    ) -> Result<Option<Release>, Report> {
        let (Forge::GitHub, Some(path)) = (source.forge, &source.path) else {
            return Ok(None);
        };

        let Some((owner, repo)) = path.split_once('/') else {
            return Ok(None);
        };
        for tag in release_tag_candidates(&image.tag, version_label) {
            let result = self
                .github
                .repos(owner, repo)
                .releases()
                .get_by_tag(&tag)
                .await;
            if matches!(&result, Err(octocrab::Error::GitHub { source, .. }) if source.status_code == StatusCode::NOT_FOUND)
            {
                continue;
            }
            let release = result
                .context("Could not fetch the GitHub release")
                .attach_with(|| format!("Repository: {path}"))
                .attach_with(|| format!("Release tag: {tag}"))?;
            debug!(repo = path, tag, "Found GitHub release");
            return Ok(Some(Release {
                name: release
                    .name
                    .filter(|it| !it.trim().is_empty())
                    .unwrap_or(tag),
                url: release.html_url.into(),
                body: release.body.filter(|it| !it.trim().is_empty()),
            }));
        }
        Ok(None)
    }

    async fn docker_hub_metadata(&self, image: &ImageRef) -> Result<ImageMetadata, Report> {
        let url = format!(
            "https://hub.docker.com/v2/repositories/{}/tags/{}",
            image.repository, image.tag
        );
        let tag: HubTag = self
            .http
            .get(&url)
            .send()
            .await
            .context("Could not request Docker Hub metadata")
            .attach_with(|| format!("Image: {image}"))?
            .error_for_status()
            .context("Docker Hub rejected the metadata lookup")
            .attach_with(|| format!("Image: {image}"))?
            .json()
            .await
            .context("Could not decode Docker Hub metadata")
            .attach_with(|| format!("Image: {image}"))?;

        Ok(ImageMetadata {
            created: tag.tag_last_pushed.or(tag.last_updated),
            updated_by: tag.last_updater_username.filter(|it| !it.is_empty()),
            ..Default::default()
        })
    }
}

/// Uses an explicit version label when present, otherwise the image tag.
/// The only alternate spelling adds or removes one leading `v`.
fn release_tag_candidates(image_tag: &str, version_label: Option<&str>) -> Vec<String> {
    let tag = version_label.unwrap_or(image_tag);
    if !tag
        .strip_prefix('v')
        .unwrap_or(tag)
        .starts_with(|ch: char| ch.is_ascii_digit())
    {
        return vec![tag.to_string()];
    }
    let alternative = match tag.strip_prefix('v') {
        Some(tag) => tag.to_string(),
        None => format!("v{tag}"),
    };
    vec![tag.to_string(), alternative]
}

#[derive(Deserialize)]
struct HubTag {
    last_updated: Option<Timestamp>,
    tag_last_pushed: Option<Timestamp>,
    last_updater_username: Option<String>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_hub_timestamps_with_offsets_and_nanoseconds() {
        let tag: HubTag = serde_json::from_str(
            r#"{
                "last_updated": "2025-03-01T14:34:56.123456789+02:00",
                "tag_last_pushed": null,
                "last_updater_username": null
            }"#,
        )
        .unwrap();
        assert_eq!(
            tag.last_updated.unwrap(),
            "2025-03-01T12:34:56.123456789Z"
                .parse::<Timestamp>()
                .unwrap()
        );
        assert!(tag.tag_last_pushed.is_none());
    }

    #[test]
    fn parses_source_urls() {
        let github = SourceRepo::parse("https://github.com/home-assistant/core.git").unwrap();
        assert_eq!(github.forge, Forge::GitHub);
        assert_eq!(github.url, "https://github.com/home-assistant/core");
        assert_eq!(github.path.as_deref(), Some("home-assistant/core"));

        assert!(SourceRepo::parse("https://github.com/a/b/tree/main/docker/app").is_none());
        assert!(SourceRepo::parse("git@github.com:a/b.git").is_none());

        let gitlab = SourceRepo::parse("https://gitlab.com/group/subgroup/project.git").unwrap();
        assert_eq!(gitlab.forge, Forge::GitLab);
        assert_eq!(gitlab.path.as_deref(), Some("group/subgroup/project"));
        assert_eq!(gitlab.url, "https://gitlab.com/group/subgroup/project");

        let other = SourceRepo::parse("https://git.example.com/x/y/z").unwrap();
        assert_eq!(other.forge, Forge::Other);
        assert_eq!(other.url, "https://git.example.com/x/y/z");
        let other = SourceRepo::parse("http://git.example.com:8080/project.git?view=source#readme")
            .unwrap();
        assert_eq!(
            other.url,
            "http://git.example.com:8080/project.git?view=source#readme"
        );

        assert!(SourceRepo::parse("not a url").is_none());
        assert!(SourceRepo::parse("https://github.com/only-owner").is_none());
    }

    #[test]
    fn builds_revision_links_per_forge() {
        let metadata = |url: &str| ImageMetadata {
            source: SourceRepo::parse(url),
            revision: Some("abc123".into()),
            ..Default::default()
        };
        assert_eq!(
            metadata("https://github.com/a/b").revision_url().unwrap(),
            "https://github.com/a/b/commit/abc123"
        );
        assert_eq!(
            metadata("https://gitlab.com/a/b").revision_url().unwrap(),
            "https://gitlab.com/a/b/-/commit/abc123"
        );
        assert_eq!(
            metadata("https://gitlab.com/group/subgroup/repo.git")
                .revision_url()
                .unwrap(),
            "https://gitlab.com/group/subgroup/repo/-/commit/abc123"
        );
        assert_eq!(metadata("https://example.com/a/b").revision_url(), None);
    }

    #[test]
    fn release_tags_only_vary_the_leading_v() {
        assert_eq!(
            release_tag_candidates("1.27.3-alpine", None),
            vec!["1.27.3-alpine", "v1.27.3-alpine"]
        );
        assert_eq!(release_tag_candidates("v2.0", None), vec!["v2.0", "2.0"]);
        assert_eq!(
            release_tag_candidates("stable", Some("1.2.3")),
            vec!["1.2.3", "v1.2.3"]
        );
        assert_eq!(
            release_tag_candidates("1.2.3", Some("2.0.0")),
            vec!["2.0.0", "v2.0.0"]
        );
        assert_eq!(release_tag_candidates("latest", None), vec!["latest"]);
    }

    #[test]
    fn earlier_sources_win() {
        let mut metadata = ImageMetadata {
            version: Some("from-label".into()),
            ..Default::default()
        };
        metadata.fill_from(ImageMetadata {
            version: Some("from-hub".into()),
            updated_by: Some("someone".into()),
            ..Default::default()
        });
        assert_eq!(metadata.version.as_deref(), Some("from-label"));
        assert_eq!(metadata.updated_by.as_deref(), Some("someone"));
    }

    /// Hits the real registries and APIs. Run with `cargo test -- --ignored`.
    /// Uses `GITHUB_TOKEN` if set, as unauthenticated GitHub API calls are heavily rate limited.
    #[tokio::test]
    #[ignore = "needs network access"]
    async fn collects_metadata_from_real_sources() {
        crate::app::install_crypto_provider();
        let registry = Arc::new(Registry::new(
            crate::images::auth::DockerAuth::default(),
            Vec::new(),
        ));
        let fetcher = MetadataFetcher::new(
            registry,
            crate::app::http_client().unwrap(),
            std::env::var("GITHUB_TOKEN").ok(),
        )
        .unwrap();

        let ghcr = ImageRef::parse("ghcr.io/home-assistant/home-assistant:2025.1.0").unwrap();
        let metadata = fetcher.fetch(&ghcr).await;
        let source = metadata.source.as_ref().expect("source label");
        assert_eq!(source.url, "https://github.com/home-assistant/core");
        let release = metadata.release.as_ref().expect("GitHub release");
        assert!(
            release.url.contains("/releases/tag/2025.1.0"),
            "{}",
            release.url
        );

        let hub = ImageRef::parse("nginx:stable").unwrap();
        let metadata = fetcher.fetch(&hub).await;
        assert!(metadata.created.is_some());
        assert!(metadata.updated_by.is_some());
    }
}
