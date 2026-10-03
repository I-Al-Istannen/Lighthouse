use std::fmt;

use bollard::models::ImageInspect;
use serde::{Deserialize, Serialize};

/// An OCI manifest digest, shared by registry responses and local repo digests.
///
/// Stored as an opaque string to preserve Docker and database values unchanged.
#[derive(Clone, Debug, Default, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize)]
#[serde(transparent)]
pub struct ManifestDigest(String);

impl ManifestDigest {
    pub fn as_str(&self) -> &str {
        &self.0
    }

    /// Whether this digest matches any of the image's recorded repo digests.
    pub fn matches_image(&self, image: &ImageInspect) -> bool {
        image
            .repo_digests
            .iter()
            .flatten()
            .any(|it| self.matches_repo_digest(it))
    }

    /// Whether a Docker `RepoDigests` entry (`name@sha256:...`) matches this digest.
    fn matches_repo_digest(&self, repo_digest: &str) -> bool {
        repo_digest
            .rsplit_once('@')
            .is_some_and(|(_, digest)| digest == self.as_str())
    }
}

impl From<String> for ManifestDigest {
    fn from(value: String) -> Self {
        Self(value)
    }
}

impl From<&str> for ManifestDigest {
    fn from(value: &str) -> Self {
        Self(value.to_string())
    }
}

impl fmt::Display for ManifestDigest {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        self.0.fmt(f)
    }
}

/// Docker's local image identifier, used for inspection and deduplication.
///
/// Stored as an opaque string to preserve Docker and database values unchanged.
#[derive(Clone, Debug, Default, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize)]
#[serde(transparent)]
pub struct ImageId(String);

impl ImageId {
    pub fn as_str(&self) -> &str {
        &self.0
    }
}

impl From<String> for ImageId {
    fn from(value: String) -> Self {
        Self(value)
    }
}

impl From<&str> for ImageId {
    fn from(value: &str) -> Self {
        Self(value.to_string())
    }
}

impl fmt::Display for ImageId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        self.0.fmt(f)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn matches_repo_digests_exactly() {
        let digest = ManifestDigest::from(format!("sha256:{}", "b".repeat(64)));
        let image = ImageInspect {
            repo_digests: Some(vec!["nginx".into(), format!("nginx@{digest}")]),
            ..Default::default()
        };
        assert!(digest.matches_image(&image));
        assert!(!ManifestDigest::from("sha256:bbb").matches_image(&image));
        assert!(!digest.matches_image(&ImageInspect::default()));
    }
}
