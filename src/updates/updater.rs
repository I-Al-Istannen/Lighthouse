use std::collections::{BTreeSet, HashMap};
use std::sync::Arc;

use bollard::models::{ContainerCreateBody, HostConfig};
use bollard::query_parameters::{
    AttachContainerOptionsBuilder, ListContainersOptionsBuilder, RemoveContainerOptionsBuilder,
};
use bollard::{container::LogOutput, errors::Error as DockerError};
use futures_util::StreamExt;
use rootcause::prelude::*;
use tokio::sync::Mutex;
use tracing::{info, warn};

use crate::docker::DockerHost;
use crate::images::reference::ImageRef;
use crate::images::registry::Registry;
use crate::updates::model::{ContainerRef, ManifestUpdate};

const UPDATER_LABEL: &str = "lighthouse-builder-container";

/// A container to update, together with the image it needs pulled first.
#[derive(Clone, Debug)]
pub struct UpdateTarget {
    pub container: ContainerRef,
    pub base: ImageRef,
}

pub fn targets_of(updates: &[ManifestUpdate]) -> Vec<UpdateTarget> {
    updates
        .iter()
        .flat_map(|update| {
            update.containers().map(|container| UpdateTarget {
                container: container.clone(),
                base: update.base.clone(),
            })
        })
        .collect()
}

/// Applies updates by pulling base images and running a user-provided updater container that
/// rebuilds and restarts the affected containers.
pub struct Updater {
    docker: Arc<DockerHost>,
    registry: Arc<Registry>,
    image: ImageRef,
    entrypoint: String,
    binds: Vec<String>,
    running: Mutex<()>,
}

impl Updater {
    pub fn new(
        docker: Arc<DockerHost>,
        registry: Arc<Registry>,
        image: &str,
        entrypoint: String,
        mounts: Vec<String>,
    ) -> Result<Self, Report> {
        for mount in &mounts {
            let parts = mount.split(':').count();
            if !(2..=3).contains(&parts) {
                bail!("Mount '{mount}' does not have the form 'source:dest[:options]'");
            }
        }
        Ok(Self {
            docker,
            registry,
            image: ImageRef::parse(image).context("Invalid updater image")?,
            entrypoint,
            binds: mounts,
            running: Mutex::new(()),
        })
    }

    /// Removes updater containers left over from a crash.
    pub async fn clean_up_leftovers(&self) -> Result<(), Report> {
        let filters = HashMap::from([
            ("label", vec![UPDATER_LABEL]),
            ("status", vec!["created", "exited", "dead"]),
        ]);
        let leftovers = self
            .docker
            .client
            .list_containers(Some(
                ListContainersOptionsBuilder::new()
                    .all(true)
                    .filters(&filters)
                    .build(),
            ))
            .await
            .context("Could not list leftover updater containers")?;
        for id in leftovers.into_iter().filter_map(|it| it.id) {
            info!(id, "Removing leftover updater container");
            self.remove(&id).await;
        }
        Ok(())
    }

    /// Updates the given containers, Lighthouse itself last. Returns a short summary.
    pub async fn apply(&self, targets: &[UpdateTarget]) -> Result<String, Report> {
        let Ok(_guard) = self.running.try_lock() else {
            bail!("An update is already running");
        };
        if targets.is_empty() {
            bail!("Nothing selected to update");
        }

        let bases: BTreeSet<&ImageRef> = targets.iter().map(|it| &it.base).collect();
        for base in bases {
            self.docker.pull(base, self.registry.auth()).await?;
        }

        let (myself, others): (Vec<&UpdateTarget>, Vec<&UpdateTarget>) =
            targets.iter().partition(|it| it.container.is_lighthouse);
        let names = |targets: &[&UpdateTarget]| -> Vec<String> {
            let unique: BTreeSet<&str> = targets
                .iter()
                .map(|it| it.container.name.as_str())
                .collect();
            unique.into_iter().map(str::to_string).collect()
        };

        if !others.is_empty() {
            self.run_updater(names(&others)).await?;
        }
        if myself.is_empty() {
            return Ok(format!("Updated {} container(s)!", others.len()));
        }

        // We will most likely be killed while doing this, so there is no real progress to report
        info!("Updating Lighthouse itself");
        if myself.len() > 1 {
            warn!(
                "Multiple Lighthouse instances found, objects in mirror are closer than they appear"
            );
        }
        self.run_updater(names(&myself)).await?;
        Ok("Updated (including Lighthouse)!".to_string())
    }

    async fn run_updater(&self, container_names: Vec<String>) -> Result<(), Report> {
        info!(containers = ?container_names, "Running updater");
        if self
            .docker
            .inspect_image(&self.image.friendly())
            .await?
            .is_none()
        {
            self.docker.pull(&self.image, self.registry.auth()).await?;
        }

        let mut cmd = vec![self.entrypoint.clone()];
        cmd.extend(container_names.iter().cloned());
        let created = self
            .docker
            .client
            .create_container(
                None,
                ContainerCreateBody {
                    image: Some(self.image.friendly()),
                    cmd: Some(cmd),
                    labels: Some(HashMap::from([(
                        UPDATER_LABEL.to_string(),
                        "true".to_string(),
                    )])),
                    host_config: Some(HostConfig {
                        binds: Some(self.binds.clone()),
                        ..Default::default()
                    }),
                    ..Default::default()
                },
            )
            .await
            .context("Could not create updater container")
            .attach_with(|| format!("Updater image: {}", self.image))
            .attach_with(|| format!("Target containers: {}", container_names.join(", ")))?;
        let id = created.id;
        info!(id, "Created updater container");

        let result = self.run_to_completion(&id).await;
        self.remove(&id).await;
        result
    }

    async fn run_to_completion(&self, id: &str) -> Result<(), Report> {
        let attached = self
            .docker
            .client
            .attach_container(
                id,
                Some(
                    AttachContainerOptionsBuilder::new()
                        .stream(true)
                        .logs(true)
                        .stdout(true)
                        .stderr(true)
                        .build(),
                ),
            )
            .await
            .context("Could not attach to updater container")
            .attach_with(|| format!("Updater container: {id}"))?;
        let logs = tokio::spawn(async move {
            let mut output = attached.output;
            while let Some(Ok(line)) = output.next().await {
                match line {
                    LogOutput::StdErr { message } => {
                        warn!("[updater] {}", String::from_utf8_lossy(&message).trim_end());
                    }
                    other => info!("[updater] {}", other.to_string().trim_end()),
                }
            }
        });

        self.docker
            .client
            .start_container(id, None)
            .await
            .context("Could not start updater container")
            .attach_with(|| format!("Updater container: {id}"))?;
        let exit = self.docker.client.wait_container(id, None).next().await;
        // The attach stream ends with the container
        let _ = logs.await;

        match exit {
            Some(Ok(response)) if response.status_code == 0 => {
                info!("Updater finished successfully");
                Ok(())
            }
            Some(Ok(response)) => bail!("Updater failed with exit code {}", response.status_code),
            Some(Err(DockerError::DockerContainerWaitError { code, error })) => {
                bail!("Updater failed with exit code {code}: {error}")
            }
            Some(Err(e)) => Err(report!(e)
                .context("Waiting for the updater failed")
                .attach(format!("Updater container: {id}"))
                .into()),
            None => bail!("Waiting for the updater returned no result"),
        }
    }

    async fn remove(&self, id: &str) {
        let options = RemoveContainerOptionsBuilder::new().force(true).build();
        if let Err(e) = self.docker.client.remove_container(id, Some(options)).await {
            warn!(id, "Could not remove updater container: {e}");
        }
    }
}
