use std::collections::{BTreeMap, HashMap, HashSet};
use std::sync::Arc;

use bollard::models::ImageInspect;
use clap::ValueEnum;
use rootcause::prelude::*;
use rootcause::report_collection::ReportCollection;
use tracing::{debug, info, warn};

use crate::docker::{ContainerInfo, DockerHost, EnrollmentMode, LABEL_BASE};
use crate::images::digest::{ImageId, ManifestDigest};
use crate::images::reference::ImageRef;
use crate::images::registry::Registry;
use crate::images::versioning::TagPolicy;
use crate::updates::model::{AffectedImage, ContainerRef, ManifestUpdate, TagUpdate};

#[derive(Clone, Copy, Debug, PartialEq, Eq, ValueEnum)]
#[value(rename_all = "snake_case")]
pub enum BaseImageUpdateStrategy {
    /// Pull base images if they are not present locally
    OnlyPullUnknown,
    /// Also pull base images if they are outdated
    PullAndUpdate,
}

/// Errors are collected instead of aborting the run: one broken container should not hide
/// updates for all others.
pub type Errors = ReportCollection;

pub struct CheckOutcome {
    pub digest_updates: Vec<ManifestUpdate>,
    pub tag_updates: Vec<TagUpdate>,
    pub errors: Errors,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum BaseKind {
    /// The `lighthouse.base` label names the image the container's image was built from
    Labeled,
    /// Use the container's own image as the update target
    Own,
}

struct Tracked {
    container: ContainerInfo,
    base: ImageRef,
    kind: BaseKind,
}

pub struct Checker {
    docker: Arc<DockerHost>,
    registry: Arc<Registry>,
    enrollment: EnrollmentMode,
    base_strategy: BaseImageUpdateStrategy,
}

impl Checker {
    pub fn new(
        docker: Arc<DockerHost>,
        registry: Arc<Registry>,
        enrollment: EnrollmentMode,
        base_strategy: BaseImageUpdateStrategy,
    ) -> Self {
        Self {
            docker,
            registry,
            enrollment,
            base_strategy,
        }
    }

    pub async fn check(&self, check_tags: bool) -> Result<CheckOutcome, Report> {
        let mut errors = Errors::new();
        let containers = self
            .docker
            .participating_containers(self.enrollment)
            .await?;
        let tracked = self.resolve_bases(containers, &mut errors).await;
        info!(count = tracked.len(), "Checking participating containers");

        let digest_updates = self.check_digests(&tracked, &mut errors).await;
        let tag_updates = if check_tags {
            self.check_tags(&tracked, &mut errors).await
        } else {
            Vec::new()
        };

        Ok(CheckOutcome {
            digest_updates,
            tag_updates,
            errors,
        })
    }

    async fn resolve_bases(
        &self,
        containers: Vec<ContainerInfo>,
        errors: &mut Errors,
    ) -> Vec<Tracked> {
        let mut tracked = Vec::new();

        for container in containers {
            if let Some(label) = container.labels.get(LABEL_BASE) {
                match ImageRef::parse(label) {
                    Ok(base) => tracked.push(Tracked {
                        container,
                        base,
                        kind: BaseKind::Labeled,
                    }),
                    Err(e) => push(errors, e.attach(format!("Container: {}", container.name()))),
                }
                continue;
            }

            let config_image = match self.docker.container_config_image(&container.id).await {
                Ok(it) => it,
                Err(e) => {
                    push(errors, e);
                    continue;
                }
            };
            let Some(config_image) = config_image else {
                warn!(
                    container = container.name(),
                    "Container has no image reference"
                );
                continue;
            };
            if config_image.starts_with("sha256:") || config_image.contains('@') {
                debug!(
                    container = container.name(),
                    image = config_image,
                    "Container image is pinned by id or digest, nothing to check"
                );
                continue;
            }

            match ImageRef::parse(&config_image) {
                Ok(base) => tracked.push(Tracked {
                    container,
                    base,
                    kind: BaseKind::Own,
                }),
                Err(e) => push(errors, e.attach(format!("Container: {}", container.name()))),
            }
        }

        tracked
    }

    async fn check_digests(&self, tracked: &[Tracked], errors: &mut Errors) -> Vec<ManifestUpdate> {
        let mut by_base: BTreeMap<&ImageRef, Vec<&Tracked>> = BTreeMap::new();
        for it in tracked {
            by_base.entry(&it.base).or_default().push(it);
        }

        let mut updates = Vec::new();

        for (base, users) in by_base {
            match self.check_base(base, &users).await {
                Ok(Some(update)) => updates.push(update),
                Ok(None) => info!(image = %base, "Up to date"),
                Err(e) => push(errors, e),
            }
        }

        updates
    }

    /// Filters out containers without a recorded remote image digest, unless a base label is set.
    /// Containers whose local image no longer exists are also skipped.
    async fn checkable_containers<'a>(
        &self,
        users: &[&'a Tracked],
    ) -> Result<Vec<(&'a Tracked, ImageInspect)>, Report> {
        let mut checkable = Vec::new();
        for user in users {
            let container = &user.container;
            let Some(image) = self
                .docker
                .inspect_image(container.image_id.as_str())
                .await?
            else {
                warn!(
                    container = container.name(),
                    "Image of container no longer exists"
                );
                continue;
            };
            if user.kind == BaseKind::Own
                && image.repo_digests.as_deref().unwrap_or_default().is_empty()
            {
                debug!(
                    container = container.name(),
                    "Image has no remote digest, add a '{LABEL_BASE}' label to check its base"
                );
                continue;
            }
            checkable.push((*user, image));
        }
        Ok(checkable)
    }

    async fn check_base(
        &self,
        base: &ImageRef,
        users: &[&Tracked],
    ) -> Result<Option<ManifestUpdate>, Report> {
        let checkable = self.checkable_containers(users).await?;
        if checkable.is_empty() {
            return Ok(None);
        }

        let remote_digest = self.registry.digest(base).await?;

        let base_layers = if checkable.iter().any(|(it, _)| it.kind == BaseKind::Labeled) {
            Some(self.local_base_layers(base, &remote_digest).await?)
        } else {
            None
        };

        let mut affected: BTreeMap<ImageId, AffectedImage> = BTreeMap::new();
        for (user, image) in checkable {
            let container = &user.container;
            let outdated = match user.kind {
                BaseKind::Own => !remote_digest.matches_image(&image),
                BaseKind::Labeled => match base_layers.as_ref().expect("labeled users have layers")
                {
                    BaseState::Outdated => true,
                    BaseState::Current(layers) => {
                        let own_layers: HashSet<&String> = image_layers(&image).iter().collect();
                        let missing = layers.iter().find(|it| !own_layers.contains(it));
                        if let Some(layer) = missing {
                            debug!(
                                container = container.name(),
                                layer, "Base layer missing in image"
                            );
                        }
                        missing.is_some()
                    }
                },
            };

            if !outdated {
                info!(container = container.name(), image = %base, "Container is up to date");
                continue;
            }
            info!(container = container.name(), image = %base, remote_digest = %remote_digest, "Container is outdated");

            affected
                .entry(container.image_id.clone())
                .or_insert_with(|| AffectedImage {
                    image_id: container.image_id.clone(),
                    repo_tags: image.repo_tags.clone().unwrap_or_default(),
                    containers: Vec::new(),
                })
                .containers
                .push(container.to_ref());
        }

        if affected.is_empty() {
            return Ok(None);
        }
        Ok(Some(ManifestUpdate {
            base: base.clone(),
            remote_digest,
            affected: affected.into_values().collect(),
            metadata: None,
        }))
    }

    /// Makes sure a reference copy of the base image exists locally (and is current, if allowed)
    /// and returns its layers.
    async fn local_base_layers(
        &self,
        base: &ImageRef,
        remote_digest: &ManifestDigest,
    ) -> Result<BaseState, Report> {
        let name = base.friendly();
        let mut local = self.docker.inspect_image(&name).await?;

        let needs_pull = match &local {
            None => true,
            Some(image) => {
                !remote_digest.matches_image(image)
                    && self.base_strategy == BaseImageUpdateStrategy::PullAndUpdate
            }
        };
        if needs_pull {
            self.docker.pull(base, self.registry.auth()).await?;
            local = self.docker.inspect_image(&name).await?;
        }

        let Some(local) = local else {
            bail!("Base image '{name}' is missing locally even after pulling it");
        };
        if !remote_digest.matches_image(&local) {
            info!(
                image = name,
                "Local copy of base image is outdated, treating users as outdated"
            );
            return Ok(BaseState::Outdated);
        }
        Ok(BaseState::Current(image_layers(&local).to_vec()))
    }

    async fn check_tags(&self, tracked: &[Tracked], errors: &mut Errors) -> Vec<TagUpdate> {
        info!("Checking for tag updates");
        // Keyed by repository, failed fetches are cached as None to report them only once
        let mut tags_by_repo: HashMap<(String, String), Option<Vec<String>>> = HashMap::new();
        let mut updates: BTreeMap<(ImageRef, String), Vec<ContainerRef>> = BTreeMap::new();

        for user in tracked {
            let container = &user.container;
            let policy = match TagPolicy::from_labels(&container.labels) {
                Ok(Some(policy)) => policy,
                Ok(None) => continue,
                Err(e) => {
                    push(errors, e.attach(format!("Container: {}", container.name())));
                    continue;
                }
            };

            let key = (user.base.registry.clone(), user.base.repository.clone());
            if !tags_by_repo.contains_key(&key) {
                let tags = match self.registry.tags(&user.base).await {
                    Ok(tags) => Some(tags),
                    Err(e) => {
                        push(errors, e);
                        None
                    }
                };
                tags_by_repo.insert(key.clone(), tags);
            }
            let Some(Some(tags)) = tags_by_repo.get(&key) else {
                continue;
            };

            match policy.newest(&user.base.tag, tags) {
                Ok(Some(new_tag)) => {
                    info!(container = container.name(), image = %user.base, new_tag, "Newer tag available");
                    updates
                        .entry((user.base.clone(), new_tag.to_string()))
                        .or_default()
                        .push(container.to_ref());
                }
                Ok(None) => debug!(container = container.name(), "No newer tag"),
                Err(e) => push(errors, e.attach(format!("Container: {}", container.name()))),
            }
        }

        updates
            .into_iter()
            .map(|((image, new_tag), containers)| TagUpdate {
                image,
                new_tag,
                containers,
                metadata: None,
            })
            .collect()
    }
}

enum BaseState {
    /// The local reference copy is outdated (and we may not update it)
    Outdated,
    Current(Vec<String>),
}

fn image_layers(image: &ImageInspect) -> &[String] {
    image
        .root_fs
        .as_ref()
        .and_then(|it| it.layers.as_deref())
        .unwrap_or_default()
}

pub fn push(errors: &mut Errors, report: impl Into<Report>) {
    errors.push(report.into().into_cloneable());
}
