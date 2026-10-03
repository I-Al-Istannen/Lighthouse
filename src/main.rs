mod app;
mod cli;
mod docker;
mod images;
mod notify;
mod updates;

use std::process::ExitCode;

use clap::Parser;
use tracing::error;

use crate::cli::Args;

#[tokio::main]
async fn main() -> ExitCode {
    app::init_logging();
    app::install_crypto_provider();
    let args = Args::parse();

    match app::run(args).await {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            error!("{e}");
            ExitCode::FAILURE
        }
    }
}
