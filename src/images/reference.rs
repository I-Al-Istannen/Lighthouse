use std::fmt;

use oci_client::Reference;

use rootcause::prelude::*;

const DOCKER_HUB: &str = "docker.io";

/// A fully normalized, tagged image reference (e.g. `docker.io/library/nginx:stable`).
///
/// Docker Hub normalization ("library/" for single-segment names) is purely syntactic, so no
/// lookup of official images is needed.
#[derive(Clone, Debug, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct ImageRef {
    pub registry: String,
    pub repository: String,
    pub tag: String,
}

impl ImageRef {
    /// Parses a docker-style reference. A missing tag defaults to `latest`, references pinned by
    /// digest only are rejected as there is nothing to check for updates.
    pub fn parse(input: &str) -> Result<Self, Report> {
        let reference: Reference = input
            .trim()
            .parse()
            .context_with(|| format!("Invalid image reference '{input}'"))?;
        let Some(tag) = reference.tag() else {
            bail!("Image reference '{input}' is pinned by digest and has no tag to check");
        };

        Ok(Self {
            registry: reference.registry().to_string(),
            repository: reference.repository().to_string(),
            tag: tag.to_string(),
        })
    }

    pub fn with_tag(&self, tag: impl Into<String>) -> Self {
        Self {
            tag: tag.into(),
            ..self.clone()
        }
    }

    pub fn is_docker_hub(&self) -> bool {
        self.registry == DOCKER_HUB
    }

    /// The name as docker shows it in `RepoTags`, i.e. without `docker.io/` and `library/`.
    pub fn friendly_name(&self) -> String {
        if !self.is_docker_hub() {
            return format!("{}/{}", self.registry, self.repository);
        }
        self.repository
            .strip_prefix("library/")
            .unwrap_or(&self.repository)
            .to_string()
    }

    /// The `name:tag` form as docker shows it in `RepoTags`.
    pub fn friendly(&self) -> String {
        format!("{}:{}", self.friendly_name(), self.tag)
    }

    pub fn to_reference(&self) -> Reference {
        Reference::with_tag(
            self.registry.clone(),
            self.repository.clone(),
            self.tag.clone(),
        )
    }

    /// A human-facing web page for the repository, if the registry has a well-known one.
    pub fn web_url(&self) -> Option<String> {
        match self.registry.as_str() {
            DOCKER_HUB => Some(match self.repository.strip_prefix("library/") {
                Some(official) => format!("https://hub.docker.com/_/{official}"),
                None => format!("https://hub.docker.com/r/{}", self.repository),
            }),
            // GHCR redirects browsers to the GitHub package page
            "ghcr.io" => Some(format!("https://ghcr.io/{}", self.repository)),
            "quay.io" => Some(format!("https://quay.io/repository/{}", self.repository)),
            _ => None,
        }
    }
}

impl fmt::Display for ImageRef {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.friendly())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normalizes_official_images() {
        let image = ImageRef::parse("nginx").unwrap();
        assert_eq!(image.registry, "docker.io");
        assert_eq!(image.repository, "library/nginx");
        assert_eq!(image.tag, "latest");
        assert_eq!(image.friendly(), "nginx:latest");
        assert_eq!(image.web_url().unwrap(), "https://hub.docker.com/_/nginx");
    }

    #[test]
    fn single_segment_names_are_always_library_images() {
        // Not an official image, but docker still resolves it to library/
        let image = ImageRef::parse("docker.io/some-unofficial-thing:1").unwrap();
        assert_eq!(image.repository, "library/some-unofficial-thing");
        assert_eq!(image.friendly(), "some-unofficial-thing:1");
    }

    #[test]
    fn keeps_user_images_and_other_registries() {
        let user = ImageRef::parse("index.docker.io/crazymax/diun:4").unwrap();
        assert_eq!(user.registry, "docker.io");
        assert_eq!(user.friendly(), "crazymax/diun:4");
        assert_eq!(
            user.web_url().unwrap(),
            "https://hub.docker.com/r/crazymax/diun"
        );

        let ghcr = ImageRef::parse("ghcr.io/i-al-istannen/lighthouse:latest").unwrap();
        assert_eq!(ghcr.friendly(), "ghcr.io/i-al-istannen/lighthouse:latest");
        assert_eq!(
            ghcr.web_url().unwrap(),
            "https://ghcr.io/i-al-istannen/lighthouse"
        );

        let quay = ImageRef::parse("quay.io/prometheus/node-exporter:v1").unwrap();
        assert_eq!(
            quay.web_url().unwrap(),
            "https://quay.io/repository/prometheus/node-exporter"
        );

        let local = ImageRef::parse("registry.local:5000/team/app:1.2").unwrap();
        assert_eq!(local.registry, "registry.local:5000");
        assert_eq!(local.friendly(), "registry.local:5000/team/app:1.2");
        assert_eq!(local.web_url(), None);
    }

    #[test]
    fn rejects_digest_only_references() {
        assert!(ImageRef::parse(&format!("nginx@sha256:{}", "a".repeat(64))).is_err());
    }
}
