use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::{env, fs};

use docker_credential::{CredentialRetrievalError, DockerCredential};
use oci_client::secrets::RegistryAuth;
use rootcause::prelude::*;
use tracing::{debug, info, warn};

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Credentials {
    pub username: String,
    pub password: String,
}

/// Registry credentials loaded from a docker config file.
#[derive(Default)]
pub struct DockerAuth {
    config: Option<Arc<str>>,
}

impl DockerAuth {
    /// Loads the given config, or the default `~/.docker/config.json` if it exists.
    pub fn load(explicit_path: Option<&Path>) -> Result<Self, Report> {
        let path = match explicit_path {
            Some(path) => path.to_path_buf(),
            None => {
                let Some(path) = default_config_path().filter(|it| it.exists()) else {
                    info!("No docker config found, using anonymous registry access");
                    return Ok(Self::default());
                };
                path
            }
        };
        info!(path = %path.display(), "Loading registry auth from docker config");

        let content = fs::read_to_string(&path)
            .context_with(|| format!("Could not read docker config at {}", path.display()))?;
        Ok(Self::parse(&content)
            .context_with(|| format!("Invalid docker config at {}", path.display()))?)
    }

    fn parse(content: &str) -> Result<Self, Report> {
        let config: serde_json::Value = serde_json::from_str(content)?;
        if !config.is_object() {
            bail!("Docker config must be a JSON object");
        }
        Ok(Self {
            config: Some(Arc::from(content)),
        })
    }

    /// Credentials for a registry host as it appears in an image reference (e.g. `docker.io`).
    pub async fn credentials(&self, registry: &str) -> Option<Credentials> {
        let config = self.config.clone()?;
        let server = credential_server(registry);
        let result = tokio::task::spawn_blocking(move || {
            docker_credential::get_credential_from_reader(config.as_bytes(), &server)
        })
        .await;

        match result {
            Ok(Ok(DockerCredential::UsernamePassword(username, password))) => {
                Some(Credentials { username, password })
            }
            Ok(Ok(DockerCredential::IdentityToken(_))) => {
                debug!(registry, "Ignoring unsupported registry identity token");
                None
            }
            Ok(Err(CredentialRetrievalError::NoCredentialConfigured)) => None,
            Ok(Err(CredentialRetrievalError::HelperFailure { helper, .. })) => {
                // Helper output can contain secrets; do not include it in logs.
                warn!(registry, helper, "Credential helper failed");
                None
            }
            Ok(Err(error)) => {
                warn!(registry, "Credential lookup failed: {error}");
                None
            }
            Err(error) => {
                warn!(registry, "Credential lookup task failed: {error}");
                None
            }
        }
    }

    pub async fn registry_auth(&self, registry: &str) -> RegistryAuth {
        match self.credentials(registry).await {
            Some(Credentials { username, password }) => RegistryAuth::Basic(username, password),
            None => RegistryAuth::Anonymous,
        }
    }
}

fn default_config_path() -> Option<PathBuf> {
    if let Some(dir) = env::var_os("DOCKER_CONFIG") {
        return Some(PathBuf::from(dir).join("config.json"));
    }
    env::var_os("HOME").map(|home| PathBuf::from(home).join(".docker/config.json"))
}

fn credential_server(registry: &str) -> String {
    // Docker still uses this key for Hub credentials, including helper requests.
    if registry == "docker.io" {
        "https://index.docker.io/v1/".to_string()
    } else {
        registry.to_string()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn reads_inline_auths() {
        let config = r#"{
            "auths": {
                "https://index.docker.io/v1/": { "auth": "dXNlcjpwYXNzOndpdGg6Y29sb25z" },
                "ghcr.io": { "username": "me", "password": "token" },
                "empty.example": {}
            }
        }"#;
        let auth = DockerAuth::parse(config).unwrap();

        assert_eq!(
            auth.credentials("docker.io").await,
            Some(Credentials {
                username: "user".into(),
                password: "pass:with:colons".into()
            })
        );
        assert_eq!(
            auth.credentials("ghcr.io").await.unwrap().username,
            "me".to_string()
        );
        assert_eq!(auth.credentials("empty.example").await, None);
        assert_eq!(auth.credentials("quay.io").await, None);
    }
}
