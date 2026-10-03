use std::collections::HashMap;

use bollard::Docker;
use bollard::auth::DockerCredentials;
use bollard::errors::Error as DockerError;
use bollard::models::ImageInspect;
use bollard::query_parameters::{
    CreateImageOptionsBuilder, ListContainersOptionsBuilder, ListImagesOptionsBuilder,
};
use futures_util::TryStreamExt;
use rootcause::prelude::*;
use tracing::{debug, info, warn};

use crate::images::auth::DockerAuth;
use crate::images::digest::ImageId;
use crate::images::reference::ImageRef;
use crate::updates::model::ContainerRef;

pub const LABEL_ENABLED: &str = "lighthouse.enabled";
pub const LABEL_BASE: &str = "lighthouse.base";
pub const LABEL_INSTANCE: &str = "lighthouse.instance";

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum EnrollmentMode {
    OptIn,
    OptOut,
}

#[derive(Clone, Debug)]
pub struct ContainerInfo {
    pub id: String,
    pub names: Vec<String>,
    pub image_id: ImageId,
    pub labels: HashMap<String, String>,
}

impl ContainerInfo {
    pub fn name(&self) -> &str {
        self.names.first().map(String::as_str).unwrap_or(&self.id)
    }

    pub fn is_lighthouse(&self) -> bool {
        self.labels.contains_key(LABEL_INSTANCE)
    }

    pub fn to_ref(&self) -> ContainerRef {
        ContainerRef {
            id: self.id.clone(),
            name: self.name().to_string(),
            is_lighthouse: self.is_lighthouse(),
        }
    }

    /// `lighthouse.enabled` always wins, otherwise the enrollment mode decides.
    pub fn is_participating(&self, mode: EnrollmentMode) -> bool {
        match self.labels.get(LABEL_ENABLED).map(String::as_str) {
            None => mode == EnrollmentMode::OptOut,
            Some(value) if value.eq_ignore_ascii_case("true") => true,
            Some(value) if value.eq_ignore_ascii_case("false") => false,
            Some(value) => {
                warn!(
                    container = self.name(),
                    value, "Invalid value for '{LABEL_ENABLED}', ignoring container"
                );
                false
            }
        }
    }
}

/// A thin wrapper around the docker daemon API.
pub struct DockerHost {
    pub client: Docker,
}

impl DockerHost {
    pub fn connect() -> Result<Self, Report> {
        let client = Docker::connect_with_defaults().context("Could not connect to docker")?;
        Ok(Self { client })
    }

    /// All containers, including stopped ones.
    pub async fn containers(&self) -> Result<Vec<ContainerInfo>, Report> {
        let summaries = self
            .client
            .list_containers(Some(ListContainersOptionsBuilder::new().all(true).build()))
            .await
            .context("Could not list containers")?;

        Ok(summaries
            .into_iter()
            .filter_map(|it| {
                Some(ContainerInfo {
                    id: it.id?,
                    names: it
                        .names
                        .unwrap_or_default()
                        .into_iter()
                        .map(|name| name.trim_start_matches('/').to_string())
                        .collect(),
                    image_id: it.image_id?.into(),
                    labels: it.labels.unwrap_or_default(),
                })
            })
            .collect())
    }

    /// Containers enrolled in update checks, including stopped ones.
    pub async fn participating_containers(
        &self,
        mode: EnrollmentMode,
    ) -> Result<Vec<ContainerInfo>, Report> {
        Ok(self
            .containers()
            .await?
            .into_iter()
            .filter(|container| {
                let participating = container.is_participating(mode);
                if !participating {
                    debug!(
                        container = container.name(),
                        "Container is not participating"
                    );
                }
                participating
            })
            .collect())
    }

    /// The image reference the container was created from (e.g. `nginx:stable`).
    ///
    /// Unlike the container list, this stays a name even after the tag moved to a newer image.
    pub async fn container_config_image(
        &self,
        container_id: &str,
    ) -> Result<Option<String>, Report> {
        let inspect = self
            .client
            .inspect_container(container_id, None)
            .await
            .context_with(|| format!("Could not inspect container '{container_id}'"))?;
        Ok(inspect.config.and_then(|it| it.image))
    }

    /// `Ok(None)` if the image does not exist locally.
    pub async fn inspect_image(&self, name: &str) -> Result<Option<ImageInspect>, Report> {
        match self.client.inspect_image(name).await {
            Ok(image) => Ok(Some(image)),
            Err(DockerError::DockerResponseServerError {
                status_code: 404, ..
            }) => Ok(None),
            Err(e) => Err(report!(e)
                .context(format!("Could not inspect image '{name}'"))
                .into()),
        }
    }

    /// Ids of all local images.
    pub async fn image_ids(&self) -> Result<Vec<ImageId>, Report> {
        let images = self
            .client
            .list_images(Some(ListImagesOptionsBuilder::new().all(true).build()))
            .await
            .context("Could not list images")?;
        Ok(images.into_iter().map(|it| it.id.into()).collect())
    }

    pub async fn pull(&self, image: &ImageRef, auth: &DockerAuth) -> Result<(), Report> {
        info!(%image, "Pulling image");
        let options = CreateImageOptionsBuilder::new()
            .from_image(&image.friendly_name())
            .tag(&image.tag)
            .build();
        let credentials = auth
            .credentials(&image.registry)
            .await
            .map(|it| DockerCredentials {
                username: Some(it.username),
                password: Some(it.password),
                serveraddress: Some(image.registry.clone()),
                ..Default::default()
            });

        self.client
            .create_image(Some(options), None, credentials)
            .try_for_each(|_| async { Ok(()) })
            .await
            .context_with(|| format!("Could not pull '{image}'"))?;
        Ok(())
    }
}
