mod app_core;
mod photo_store;
mod protocol;
mod types;

use std::sync::OnceLock;

pub use app_core::AppCore;
pub use types::{
    AndroidCommand, AndroidEvent, ControlsEnabled, DiscoveryChannel, FeedItem, SocketSide,
    UiAction, UiPage, ViewState,
};

#[derive(Debug, Clone, thiserror::Error, uniffi::Error)]
pub enum NearbyHashtreeError {
    #[error("{0}")]
    Message(String),
}

pub(crate) fn runtime() -> &'static tokio::runtime::Runtime {
    static RUNTIME: OnceLock<tokio::runtime::Runtime> = OnceLock::new();
    RUNTIME.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build()
            .expect("failed to initialize nearby_hashtree_ffi runtime")
    })
}

uniffi::setup_scaffolding!();
