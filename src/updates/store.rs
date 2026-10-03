use std::collections::{BTreeMap, BTreeSet, HashSet};
use std::io::ErrorKind;
use std::path::PathBuf;

use rootcause::prelude::*;
use serde::{Deserialize, Serialize};
use tracing::{debug, info};

use crate::images::digest::{ImageId, ManifestDigest};
use crate::updates::model::{ManifestUpdate, TagUpdate};

/// Remembers which updates were already announced, so every update is only notified once.
pub struct UpdateStore {
    path: PathBuf,
}

#[derive(Debug, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Database {
    /// Keyed by remote manifest digest
    #[serde(default)]
    known_updates: BTreeMap<ManifestDigest, KnownUpdate>,
    /// Keyed by `image:currentTag`
    #[serde(default)]
    known_tag_updates: BTreeMap<String, KnownTagUpdate>,
}

#[derive(Debug, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct KnownUpdate {
    remote_manifest: ManifestDigest,
    #[serde(default)]
    local_image_ids: BTreeSet<ImageId>,
    #[serde(default)]
    repo_tags: Vec<String>,
    #[serde(default)]
    original_containers: Vec<String>,
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct KnownTagUpdate {
    image: String,
    current_tag: String,
    new_tag: String,
}

impl UpdateStore {
    pub fn new(path: PathBuf) -> Self {
        Self { path }
    }

    pub async fn load(&self) -> Result<Database, Report> {
        let content = match tokio::fs::read_to_string(&self.path).await {
            Ok(it) => it,
            Err(e) if e.kind() == ErrorKind::NotFound => return Ok(Database::default()),
            Err(e) => {
                return Err(report!(e)
                    .context(format!("Could not read {}", self.path.display()))
                    .into());
            }
        };
        let database: Database = serde_json::from_str(&content)
            .context_with(|| format!("Invalid update database {}", self.path.display()))?;
        info!(
            digests = database.known_updates.len(),
            tags = database.known_tag_updates.len(),
            "Loaded known updates"
        );
        Ok(database)
    }

    /// Atomically replaces the database, so a crash can not leave a truncated file behind.
    pub async fn save(&self, database: &Database) -> Result<(), Report> {
        if let Some(parent) = self.path.parent() {
            tokio::fs::create_dir_all(parent)
                .await
                .context("Could not create the database directory")
                .attach_with(|| format!("Directory: {}", parent.display()))?;
        }
        let tmp = self.path.with_extension("json.tmp");
        let content = serde_json::to_vec_pretty(database)
            .context("Could not serialize the update database")?;
        tokio::fs::write(&tmp, content)
            .await
            .context("Could not write the temporary update database")
            .attach_with(|| format!("Temporary database: {}", tmp.display()))?;
        tokio::fs::rename(&tmp, &self.path)
            .await
            .context("Could not replace the update database")
            .attach_with(|| format!("Temporary database: {}", tmp.display()))
            .attach_with(|| format!("Database: {}", self.path.display()))?;
        debug!(path = %self.path.display(), "Saved update database");
        Ok(())
    }
}

impl Database {
    /// Removes already announced (digest, local image) pairs and records the remaining ones.
    ///
    /// A new local image built on a known outdated digest is still announced.
    pub fn filter_digest_updates(&mut self, updates: Vec<ManifestUpdate>) -> Vec<ManifestUpdate> {
        let mut result = Vec::new();

        for mut update in updates {
            let known = self
                .known_updates
                .entry(update.remote_digest.clone())
                .or_default();
            known.remote_manifest.clone_from(&update.remote_digest);

            update.affected.retain(|image| {
                let new = !known.local_image_ids.contains(&image.image_id);
                if !new {
                    info!(image = %update.base, local = ?image.repo_tags, "Already notified, skipping");
                }
                new
            });
            if update.affected.is_empty() {
                continue;
            }

            for image in &update.affected {
                known.local_image_ids.insert(image.image_id.clone());
                known.repo_tags.extend(image.repo_tags.iter().cloned());
                known
                    .original_containers
                    .extend(image.containers.iter().map(|it| it.name.clone()));
            }
            known.repo_tags.sort();
            known.repo_tags.dedup();
            known.original_containers.sort();
            known.original_containers.dedup();
            result.push(update);
        }

        self.known_updates
            .retain(|_, it| !it.local_image_ids.is_empty());
        result
    }

    /// Removes tag updates that were already announced with the same target tag. A newer target
    /// tag is announced again.
    pub fn filter_tag_updates(&mut self, updates: Vec<TagUpdate>) -> Vec<TagUpdate> {
        updates
            .into_iter()
            .filter(|update| {
                let key = update.image.friendly();
                if self
                    .known_tag_updates
                    .get(&key)
                    .is_some_and(|it| it.new_tag == update.new_tag)
                {
                    info!(
                        image = key,
                        new_tag = update.new_tag,
                        "Already notified, skipping"
                    );
                    return false;
                }
                self.known_tag_updates.insert(
                    key,
                    KnownTagUpdate {
                        image: update.image.friendly_name(),
                        current_tag: update.image.tag.clone(),
                        new_tag: update.new_tag.clone(),
                    },
                );
                true
            })
            .collect()
    }

    /// Forgets local images that no longer exist. They can never be reported again, and this keeps
    /// the database from growing forever.
    pub fn prune(&mut self, existing_image_ids: &HashSet<ImageId>) {
        for known in self.known_updates.values_mut() {
            known
                .local_image_ids
                .retain(|it| existing_image_ids.contains(it));
        }
        self.known_updates
            .retain(|_, it| !it.local_image_ids.is_empty());
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::images::digest::{ImageId, ManifestDigest};
    use crate::images::reference::ImageRef;
    use crate::updates::model::{AffectedImage, ContainerRef};

    fn digest_update(digest: &str, image_ids: &[&str]) -> ManifestUpdate {
        ManifestUpdate {
            base: ImageRef::parse("nginx:stable").unwrap(),
            remote_digest: digest.into(),
            affected: image_ids
                .iter()
                .map(|id| AffectedImage {
                    image_id: ImageId::from(*id),
                    repo_tags: vec![format!("{id}:latest")],
                    containers: vec![ContainerRef {
                        id: format!("c-{id}"),
                        name: format!("container-{id}"),
                        is_lighthouse: false,
                    }],
                })
                .collect(),
            metadata: None,
        }
    }

    fn tag_update(current: &str, new: &str) -> TagUpdate {
        TagUpdate {
            image: ImageRef::parse(&format!("nginx:{current}")).unwrap(),
            new_tag: new.into(),
            containers: Vec::new(),
            metadata: None,
        }
    }

    #[test]
    fn notifies_each_digest_and_image_once() {
        let mut database = Database::default();
        assert_eq!(
            database
                .filter_digest_updates(vec![digest_update("sha256:a", &["i1"])])
                .len(),
            1
        );
        assert!(
            database
                .filter_digest_updates(vec![digest_update("sha256:a", &["i1"])])
                .is_empty()
        );
        // Yet another update for the same image
        assert_eq!(
            database
                .filter_digest_updates(vec![digest_update("sha256:b", &["i1"])])
                .len(),
            1
        );
    }

    #[test]
    fn renotifies_when_a_newer_tag_appears() {
        let mut database = Database::default();
        assert_eq!(
            database
                .filter_tag_updates(vec![tag_update("1.25", "1.26")])
                .len(),
            1
        );
        assert!(
            database
                .filter_tag_updates(vec![tag_update("1.25", "1.26")])
                .is_empty()
        );
        assert_eq!(
            database
                .filter_tag_updates(vec![tag_update("1.25", "1.27")])
                .len(),
            1
        );
    }

    #[test]
    fn prunes_deleted_images() {
        let mut database = Database::default();
        database.filter_digest_updates(vec![digest_update("sha256:a", &["i1", "i2"])]);
        database.prune(&HashSet::from([ImageId::from("i2")]));
        assert!(
            database.known_updates[&ManifestDigest::from("sha256:a")]
                .local_image_ids
                .contains(&ImageId::from("i2"))
        );

        database.prune(&HashSet::new());
        assert!(database.known_updates.is_empty());
    }

    #[tokio::test]
    async fn round_trips_through_disk() {
        let dir = std::env::temp_dir().join(format!("lighthouse-store-{}", std::process::id()));
        let store = UpdateStore::new(dir.join("lighthouse-updates.json"));
        tokio::fs::create_dir_all(&dir).await.unwrap();
        let legacy = dir.join("known-images.json");
        tokio::fs::write(&legacy, "old database left untouched")
            .await
            .unwrap();

        let mut database = store.load().await.unwrap();
        database.filter_digest_updates(vec![digest_update("sha256:a", &["i1"])]);
        database.filter_tag_updates(vec![tag_update("1.25", "1.26")]);
        store.save(&database).await.unwrap();

        let mut reloaded = store.load().await.unwrap();
        assert!(
            reloaded
                .filter_digest_updates(vec![digest_update("sha256:a", &["i1"])])
                .is_empty()
        );
        assert!(
            reloaded
                .filter_tag_updates(vec![tag_update("1.25", "1.26")])
                .is_empty()
        );
        assert_eq!(
            tokio::fs::read_to_string(&legacy).await.unwrap(),
            "old database left untouched"
        );

        std::fs::remove_dir_all(dir).unwrap();
    }
}
