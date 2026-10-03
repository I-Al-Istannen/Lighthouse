use std::collections::HashSet;
use std::env;
use std::sync::Arc;
use std::time::Duration;

use croner::Cron;
use futures_util::{StreamExt, stream};
use jiff::Zoned;
use rootcause::prelude::*;
use serenity::all::{ChannelId, Client, GatewayIntents, Http};
use tracing::{error, info, warn};
use tracing_subscriber::EnvFilter;

use crate::cli::Args;
use crate::docker::{DockerHost, EnrollmentMode, LABEL_INSTANCE};
use crate::images::auth;
use crate::images::digest::ImageId;
use crate::images::metadata::MetadataFetcher;
use crate::images::registry::Registry;
use crate::notify::Notifier;
use crate::notify::discord::bot;
use crate::notify::discord::{Discord, DiscordSender};
use crate::notify::ntfy::Ntfy;
use crate::updates::checker::Checker;
use crate::updates::store::UpdateStore;
use crate::updates::updater::Updater;

/// How many metadata lookups run at once
const METADATA_CONCURRENCY: usize = 4;

pub(crate) fn init_logging() {
    // RUST_LOG takes precedence, LOG_LEVEL is kept for compatibility with the Java version
    let filter = EnvFilter::try_from_default_env().unwrap_or_else(|_| {
        let level = env::var("LOG_LEVEL").unwrap_or_else(|_| "info".into());
        EnvFilter::new(format!("warn,lighthouse={}", level.to_ascii_lowercase()))
    });
    tracing_subscriber::fmt().with_env_filter(filter).init();
}

/// GitHub's API rejects requests without a User-Agent.
pub(crate) fn http_client() -> Result<reqwest::Client, Report> {
    Ok(reqwest::Client::builder()
        .user_agent(concat!("lighthouse/", env!("CARGO_PKG_VERSION")))
        .timeout(Duration::from_secs(30))
        .build()?)
}

pub(crate) fn install_crypto_provider() {
    // Fails if already installed, which is fine
    let _ = rustls::crypto::ring::default_provider().install_default();
}

struct App {
    args: Args,
    checker: Checker,
    docker: Arc<DockerHost>,
    metadata: MetadataFetcher,
    notifier: Notifier,
    store: UpdateStore,
}

pub(crate) async fn run(args: Args) -> Result<(), Report> {
    let cron: Cron = args
        .check_times
        .parse()
        .context_with(|| format!("Invalid cron expression '{}'", args.check_times))?;

    let docker = Arc::new(DockerHost::connect()?);
    warn_about_instance_count(&docker).await?;

    let registry = Arc::new(Registry::new(
        auth::DockerAuth::load(args.docker_config.as_deref())?,
        args.insecure_registries.clone(),
    ));
    let http = http_client()?;

    let updater = match &args.updater_entrypoint {
        Some(entrypoint) => {
            let updater = Updater::new(
                docker.clone(),
                registry.clone(),
                &args.updater_image,
                entrypoint.clone(),
                args.updater_mounts.clone(),
            )?;
            if let Err(e) = updater.clean_up_leftovers().await {
                warn!("Could not clean up leftover updater containers: {e}");
            }
            Some(Arc::new(updater))
        }
        None => None,
    };

    let notifier = build_notifier(&args, http.clone(), updater).await?;
    let enrollment = if args.require_label {
        EnrollmentMode::OptIn
    } else {
        EnrollmentMode::OptOut
    };

    let app = App {
        checker: Checker::new(
            docker.clone(),
            registry.clone(),
            enrollment,
            args.base_image_update,
        ),
        metadata: MetadataFetcher::new(registry, http, args.github_token.clone())?,
        store: UpdateStore::new(args.data_dir.join("lighthouse-updates.json")),
        docker,
        notifier,
        args,
    };

    tokio::select! {
        () = app.run_forever(cron) => Ok(()),
        () = shutdown_signal() => {
            info!("Shutting down");
            Ok(())
        }
    }
}

async fn build_notifier(
    args: &Args,
    http: reqwest::Client,
    updater: Option<Arc<Updater>>,
) -> Result<Notifier, Report> {
    let hostname = args.hostname.clone();

    if args.ntfy {
        if !args.is_url() {
            bail!("ntfy mode needs a topic URL");
        }
        let ntfy = Arc::new(Ntfy::new(
            http,
            args.target.clone(),
            hostname,
            updater.is_some(),
        ));
        if let Some(updater) = updater {
            tokio::spawn(ntfy.clone().listen(updater));
        }
        return Ok(Notifier::Ntfy(ntfy));
    }

    let sender = if args.is_url() {
        if updater.is_some() {
            warn!("Webhooks can not have buttons, use a bot token to update from Discord");
        }
        DiscordSender::webhook(&args.target, hostname).await?
    } else {
        let Some(channel) = args.bot_channel_id else {
            bail!("--bot-channel-id is required when using a bot token");
        };
        let channel = ChannelId::new(channel);
        let http = Arc::new(Http::new(&args.target));
        http.get_channel(channel)
            .await
            .context("Could not access the bot channel, are the token and channel id correct?")?;
        DiscordSender::channel(http, channel, hostname)
    };

    // Buttons need a gateway connection to receive interactions
    let controls = match updater {
        Some(updater) if !args.is_url() => {
            let controls = Arc::new(bot::Controls::new(sender.clone(), updater));
            let mut client = Client::builder(&args.target, GatewayIntents::empty())
                .event_handler(bot::Handler(controls.clone()))
                .await
                .context("Could not create Discord client")?;
            tokio::spawn(async move {
                if let Err(e) = client.start().await {
                    error!("Discord gateway connection failed: {e}");
                }
            });
            Some(controls)
        }
        _ => None,
    };

    Ok(Notifier::Discord(Discord {
        sender,
        mention: args.mention.clone(),
        mention_text: args.mention_text.clone(),
        controls,
    }))
}

impl App {
    async fn run_forever(&self, cron: Cron) {
        if self.args.check_on_start {
            self.check_and_report().await;
        }
        loop {
            let now = Zoned::now();
            let next = match cron.find_next_occurrence(&now, false) {
                Ok(next) => next,
                Err(e) => {
                    error!("Cron expression has no next execution: {e}");
                    return;
                }
            };
            info!(next = %next.strftime("%Y-%m-%d %H:%M:%S %:z"), "Sleeping until next check");

            // Sleep in steps, so suspending the host does not delay checks by the suspended time
            while Zoned::now() < next {
                let remaining =
                    Duration::try_from(Zoned::now().duration_until(&next)).unwrap_or_default();
                tokio::time::sleep(remaining.min(Duration::from_secs(60))).await;
            }
            self.check_and_report().await;
        }
    }

    async fn check_and_report(&self) {
        info!("Checking for updates");
        if let Err(e) = self.check().await {
            error!("Check failed: {e}");
            self.notifier.error(&e).await;
        }
    }

    async fn check(&self) -> Result<(), Report> {
        let outcome = self
            .checker
            .check(self.args.check_tag_updates)
            .await
            .context("Could not check for image updates")?;
        let mut digest_updates = outcome.digest_updates.clone();
        let mut tag_updates = outcome.tag_updates;

        let mut database = if self.args.notify_again {
            None
        } else {
            let mut database = self
                .store
                .load()
                .await
                .context("Could not load previously announced updates")?;
            let existing: HashSet<ImageId> = self.docker.image_ids().await?.into_iter().collect();
            database.prune(&existing);
            digest_updates = database.filter_digest_updates(digest_updates);
            tag_updates = database.filter_tag_updates(tag_updates);
            Some(database)
        };
        info!(
            images = digest_updates.len(),
            tags = tag_updates.len(),
            "Found new updates"
        );

        // Only fetched for updates we actually report: this costs registry and API requests
        digest_updates = stream::iter(digest_updates)
            .map(|mut update| async {
                update.metadata = Some(self.metadata.fetch(&update.base).await);
                update
            })
            .buffered(METADATA_CONCURRENCY)
            .collect()
            .await;
        tag_updates = stream::iter(tag_updates)
            .map(|mut update| async {
                update.metadata = Some(self.metadata.fetch(&update.new_image()).await);
                update
            })
            .buffered(METADATA_CONCURRENCY)
            .collect()
            .await;

        self.notifier
            .digest_updates(&digest_updates)
            .await
            .context("Could not send digest update notifications")?;
        self.notifier
            .tag_updates(&tag_updates)
            .await
            .context("Could not send tag update notifications")?;
        self.notifier
            .update_action(&outcome.digest_updates)
            .await
            .context("Could not publish the update action")?;

        // Only remember updates once the notification went through
        if let Some(database) = &mut database {
            self.store
                .save(database)
                .await
                .context("Could not save successfully announced updates")?;
        }

        if !outcome.errors.is_empty() {
            let count = outcome.errors.len();
            return Err(outcome
                .errors
                .context(format!("{count} problem(s) while checking for updates"))
                .into());
        }
        Ok(())
    }
}

async fn warn_about_instance_count(docker: &DockerHost) -> Result<(), Report> {
    let count = docker
        .containers()
        .await?
        .iter()
        .filter(|it| it.is_lighthouse())
        .count();
    match count {
        0 => warn!(
            "Label '{LABEL_INSTANCE}' not set! Unable to identify own container, can not update Lighthouse last"
        ),
        1 => {}
        _ => warn!(
            count,
            "Found multiple containers with the '{LABEL_INSTANCE}' label. This should mostly work, but you are sailing *pretty close* to some nasty cliffs"
        ),
    }
    Ok(())
}

async fn shutdown_signal() {
    #[cfg(unix)]
    {
        use tokio::signal::unix::{SignalKind, signal};
        let mut terminate = signal(SignalKind::terminate()).expect("could not listen for SIGTERM");
        tokio::select! {
            _ = tokio::signal::ctrl_c() => {}
            _ = terminate.recv() => {}
        }
    }
    #[cfg(not(unix))]
    let _ = tokio::signal::ctrl_c().await;
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn schedules_in_local_time_across_daylight_saving() {
        let cron: Cron = "0 12 * * *".parse().unwrap();
        let now: Zoned = "2026-03-28T12:00:00+01:00[Europe/Berlin]".parse().unwrap();
        let next = cron.find_next_occurrence(&now, false).unwrap();
        assert_eq!(next.hour(), 12);
        assert_eq!(next.day(), 29);
        assert_eq!(
            Duration::try_from(now.duration_until(&next)).unwrap(),
            Duration::from_secs(23 * 60 * 60)
        );
    }
}
