use std::path::PathBuf;

use clap::Parser;

use crate::updates::checker::BaseImageUpdateStrategy;

/// Watches for docker base image updates
#[derive(Debug, Parser)]
#[command(name = "lighthouse", version)]
pub struct Args {
    /// Discord webhook URL, Discord bot token or ntfy topic URL
    #[arg(value_name = "URL|TOKEN")]
    pub target: String,

    /// Check times in cron syntax (https://crontab.guru)
    #[arg(long, value_name = "CRONTAB", default_value = "23 08 * * *")]
    pub check_times: String,

    /// Also check once right after starting
    #[arg(long)]
    pub check_on_start: bool,

    /// Discord mention (e.g. '<@userid>')
    #[arg(long, value_name = "MENTION")]
    pub mention: Option<String>,

    /// Text to send in Discord. '{IMAGES}' is replaced by the updated images
    #[arg(long, value_name = "TEXT")]
    pub mention_text: Option<String>,

    /// Path to the docker config with registry credentials [default: ~/.docker/config.json]
    #[arg(long = "docker-config", value_name = "PATH")]
    pub docker_config: Option<PathBuf>,

    /// The hostname to mention in notifications
    #[arg(long, value_name = "NAME")]
    pub hostname: Option<String>,

    /// Whether to only pull unknown base images or also update outdated ones
    #[arg(
        long,
        value_enum,
        ignore_case = true,
        value_name = "STRATEGY",
        default_value = "only_pull_unknown"
    )]
    pub base_image_update: BaseImageUpdateStrategy,

    /// Ignore containers without the 'lighthouse.enabled=true' label
    #[arg(long)]
    pub require_label: bool,

    /// Notify about an update every time it is found, not just once
    #[arg(long = "notify-again")]
    pub notify_again: bool,

    /// Check for newer version tags of containers with a 'lighthouse.tag-check.strategy' label
    #[arg(long)]
    pub check_tag_updates: bool,

    /// Use ntfy to send notifications and receive update requests
    #[arg(long)]
    pub ntfy: bool,

    /// GitHub token for fetching release notes (raises the API rate limit)
    #[arg(
        long,
        env = "GITHUB_TOKEN",
        hide_env_values = true,
        value_name = "TOKEN"
    )]
    pub github_token: Option<String>,

    /// Registry (host:port) to talk to over plain HTTP, can be repeated
    #[arg(long = "insecure-registry", value_name = "HOST")]
    pub insecure_registries: Vec<String>,

    /// Directory for persistent state
    #[arg(long, value_name = "PATH", default_value = "data")]
    pub data_dir: PathBuf,

    /// The image to use for updating containers
    #[arg(
        long = "bot-updater-docker-image",
        value_name = "IMAGE",
        default_value = "docker"
    )]
    pub updater_image: String,

    /// Mounts for the updater container ('source:dest[:options]'), can be repeated
    #[arg(long = "bot-updater-mount", value_name = "MOUNT")]
    pub updater_mounts: Vec<String>,

    /// The binary to call in the updater container. Enables updating from notifications
    #[arg(long = "bot-updater-entrypoint", value_name = "PATH")]
    pub updater_entrypoint: Option<String>,

    /// The channel id the bot should send updates to
    #[arg(long = "bot-channel-id", value_name = "ID")]
    pub bot_channel_id: Option<u64>,
}

impl Args {
    pub fn is_url(&self) -> bool {
        self.target.to_ascii_lowercase().starts_with("https://")
            || self.target.to_ascii_lowercase().starts_with("http://")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn accepts_java_style_arguments() {
        let args = Args::try_parse_from([
            "lighthouse",
            "--check-times=13 06 * * *",
            "--base-image-update",
            "PULL_AND_UPDATE",
            "--require-label",
            "--bot-updater-mount=/a:/b",
            "--bot-updater-mount=/c:/d:ro",
            "--bot-channel-id=926553453583532043",
            "token",
        ])
        .unwrap();
        assert_eq!(
            args.base_image_update,
            BaseImageUpdateStrategy::PullAndUpdate
        );
        assert_eq!(args.updater_mounts.len(), 2);
        assert!(!args.is_url());

        let args = Args::try_parse_from([
            "lighthouse",
            "--base-image-update=only_pull_unknown",
            "https://discord.com/api/webhooks/1/x",
        ])
        .unwrap();
        assert_eq!(
            args.base_image_update,
            BaseImageUpdateStrategy::OnlyPullUnknown
        );
        assert!(args.is_url());
    }
}
