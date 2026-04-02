#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum UiPage {
    Feed,
    Settings,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum DiscoveryChannel {
    Publish,
    Subscribe,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum SocketSide {
    Responder,
    Initiator,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum UiAction {
    TakePhotoRequested,
    ToggleNearbyRequested,
    ClearDemoDataRequested,
    SwitchPage(UiPage),
    ClearLogRequested,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum AndroidEvent {
    PermissionsGranted,
    PermissionsDenied,
    CameraCaptureCompleted { temp_path: String },
    CameraCaptureCancelled,
    AwareAttachSucceeded,
    AwareAttachFailed,
    PublishStarted,
    SubscribeStarted,
    PublishTerminated,
    SubscribeTerminated,
    PeerDiscovered { handle_id: i64, instance: Option<String> },
    DiscoveryMessageReceived {
        channel: DiscoveryChannel,
        handle_id: i64,
        payload: String,
    },
    DiscoveryMessageSent { message_id: i64 },
    DiscoveryMessageFailed { message_id: i64 },
    ResponderNetworkAvailable { connection_id: i64 },
    ResponderNetworkLost { connection_id: i64 },
    InitiatorNetworkAvailable { connection_id: i64 },
    InitiatorNetworkLost { connection_id: i64 },
    InitiatorCapabilities {
        connection_id: i64,
        port: i32,
        ipv6: Option<String>,
    },
    SocketConnected {
        connection_id: i64,
        side: SocketSide,
    },
    SocketClosed {
        connection_id: i64,
    },
    SocketRead {
        connection_id: i64,
        bytes: Vec<u8>,
    },
    SocketError {
        connection_id: i64,
        message: String,
    },
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum AndroidCommand {
    RequestPermissions,
    LaunchCameraCapture { output_path: String },
    StartAwareAttach,
    StartPublish {
        service_name: String,
        service_info: String,
    },
    StartSubscribe {
        service_name: String,
    },
    SendDiscoveryMessage {
        channel: DiscoveryChannel,
        handle_id: i64,
        payload: String,
        message_id: i64,
    },
    OpenResponder {
        handle_id: i64,
        passphrase: String,
        port: i32,
        protocol: i32,
        connection_id: i64,
    },
    OpenInitiator {
        handle_id: i64,
        passphrase: String,
        connection_id: i64,
    },
    ConnectInitiatorSocket {
        connection_id: i64,
        ipv6: String,
        port: i32,
    },
    WriteSocketBytes {
        connection_id: i64,
        bytes: Vec<u8>,
    },
    CloseSocket {
        connection_id: i64,
    },
    StopAware,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ControlsEnabled {
    pub take_photo: bool,
    pub toggle_nearby: bool,
    pub clear_demo_data: bool,
    pub clear_log: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct FeedItem {
    pub id: String,
    pub source_label: String,
    pub source_device_id: String,
    pub created_at_ms: i64,
    pub size_bytes: u64,
    pub photo_cid: String,
    pub mime_type: String,
    pub is_local: bool,
    pub render_cache_path: String,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ViewState {
    pub page: UiPage,
    pub status_text: String,
    pub mode_text: String,
    pub link_text: String,
    pub storage_text: String,
    pub local_summary_text: String,
    pub nearby_summary_text: String,
    pub controls_enabled: ControlsEnabled,
    pub feed_items: Vec<FeedItem>,
    pub log_lines: Vec<String>,
}
