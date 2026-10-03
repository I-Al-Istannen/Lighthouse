use crate::images::digest::{ImageId, ManifestDigest};
use crate::images::metadata::ImageMetadata;
use crate::images::reference::ImageRef;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ContainerRef {
    pub id: String,
    /// Primary name, without docker's leading slash
    pub name: String,
    pub is_lighthouse: bool,
}

/// A local image that is built on (or is) an outdated image.
#[derive(Clone, Debug)]
pub struct AffectedImage {
    pub image_id: ImageId,
    pub repo_tags: Vec<String>,
    pub containers: Vec<ContainerRef>,
}

impl AffectedImage {
    /// Repo tags, or the short image id for untagged images.
    pub fn display_names(&self) -> Vec<String> {
        if !self.repo_tags.is_empty() {
            return self.repo_tags.clone();
        }
        let id = self.image_id.as_str().trim_start_matches("sha256:");
        vec![id.chars().take(12).collect()]
    }
}

/// A new digest was pushed for a tag that local images are (based on).
///
/// There is exactly one of these per remote image, no matter how many local images and containers
/// use it.
#[derive(Clone, Debug)]
pub struct ManifestUpdate {
    pub base: ImageRef,
    pub remote_digest: ManifestDigest,
    pub affected: Vec<AffectedImage>,
    pub metadata: Option<ImageMetadata>,
}

impl ManifestUpdate {
    pub fn containers(&self) -> impl Iterator<Item = &ContainerRef> {
        self.affected.iter().flat_map(|it| &it.containers)
    }
}

/// A newer version tag is available for an image containers are pinned to.
#[derive(Clone, Debug)]
pub struct TagUpdate {
    /// The image with the *current* tag
    pub image: ImageRef,
    pub new_tag: String,
    pub containers: Vec<ContainerRef>,
    pub metadata: Option<ImageMetadata>,
}

impl TagUpdate {
    pub fn new_image(&self) -> ImageRef {
        self.image.with_tag(&self.new_tag)
    }
}
