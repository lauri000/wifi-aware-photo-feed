use std::collections::{HashMap, VecDeque};
use std::path::PathBuf;
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

use crate::photo_store::PhotoStore;
use crate::protocol::{encode_frame, FrameDecoder, WireMessage};
use crate::types::{
    AndroidCommand, AndroidEvent, ControlsEnabled, DiscoveryChannel, SocketSide, UiAction, UiPage,
    ViewState,
};
use crate::NearbyHashtreeError;

const SERVICE_NAME: &str = "_nostrwifiaware._tcp";
const SECURE_PASSPHRASE: &str = "awarebenchpass123";
const TCP_PROTOCOL: i32 = 6;
const INITIAL_RECONNECT_BACKOFF_MS: i64 = 600;
const MAX_RECONNECT_BACKOFF_MS: i64 = 4_000;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Role {
    Idle,
    Peer,
}

#[derive(Debug, Clone)]
struct PeerState {
    publish_handle_id: Option<i64>,
    subscribe_handle_id: Option<i64>,
    connection_id: Option<i64>,
    connected_side: Option<SocketSide>,
    initiator_requested: bool,
    socket_connect_requested: bool,
    hello_sent: bool,
    desired_root: Option<String>,
    in_flight_root: Option<String>,
    in_flight_sync_id: Option<i64>,
    last_sent_root: Option<String>,
    last_applied_root: Option<String>,
    reconnect_backoff_ms: i64,
    reconnect_scheduled: bool,
    last_seen_ms: i64,
}

impl PeerState {
    fn new(now_ms: i64) -> Self {
        Self {
            publish_handle_id: None,
            subscribe_handle_id: None,
            connection_id: None,
            connected_side: None,
            initiator_requested: false,
            socket_connect_requested: false,
            hello_sent: false,
            desired_root: None,
            in_flight_root: None,
            in_flight_sync_id: None,
            last_sent_root: None,
            last_applied_root: None,
            reconnect_backoff_ms: INITIAL_RECONNECT_BACKOFF_MS,
            reconnect_scheduled: false,
            last_seen_ms: now_ms,
        }
    }
}

#[derive(Debug, Clone)]
struct ConnectionState {
    peer_instance: String,
    side: Option<SocketSide>,
    outbound_sync_id: Option<i64>,
    outbound_root: Option<String>,
    inbound_sync_id: Option<i64>,
    inbound_root: Option<String>,
}

struct CoreState {
    app_instance: String,
    photo_store: PhotoStore,
    page: UiPage,
    role: Role,
    nearby_available: bool,
    status_text: String,
    link_text: String,
    pending_start_nearby: bool,
    camera_preview_active: bool,
    capture_in_progress: bool,
    pending_capture_output_path: Option<String>,
    last_sync_error: Option<String>,
    pending_commands: VecDeque<AndroidCommand>,
    log_lines: VecDeque<String>,
    next_message_id: i64,
    next_connection_id: i64,
    next_sync_id: i64,
    peers: HashMap<String, PeerState>,
    publish_handle_to_instance: HashMap<i64, String>,
    subscribe_handle_to_instance: HashMap<i64, String>,
    connections: HashMap<i64, ConnectionState>,
    inbound_decoders: HashMap<i64, FrameDecoder>,
}

#[derive(uniffi::Object)]
pub struct AppCore {
    state: Mutex<CoreState>,
}

#[uniffi::export]
impl AppCore {
    #[uniffi::constructor]
    pub fn new(
        app_files_dir: String,
        app_cache_dir: String,
        app_instance: String,
    ) -> Result<Self, NearbyHashtreeError> {
        let photo_store = PhotoStore::new(PathBuf::from(app_files_dir), PathBuf::from(app_cache_dir))?;
        let recovered = photo_store.recover_pending_captures()?;
        let mut state = CoreState {
            app_instance,
            photo_store,
            page: UiPage::Feed,
            role: Role::Idle,
            nearby_available: true,
            status_text: "Nearby off".to_string(),
            link_text: "Idle".to_string(),
            pending_start_nearby: false,
            camera_preview_active: false,
            capture_in_progress: false,
            pending_capture_output_path: None,
            last_sync_error: None,
            pending_commands: VecDeque::new(),
            log_lines: VecDeque::new(),
            next_message_id: 1,
            next_connection_id: 1,
            next_sync_id: 1,
            peers: HashMap::new(),
            publish_handle_to_instance: HashMap::new(),
            subscribe_handle_to_instance: HashMap::new(),
            connections: HashMap::new(),
            inbound_decoders: HashMap::new(),
        };
        state.log(
            "Local Instagram loaded. Photos are persisted as hashtree blocks and synced by root + block hash over Wi-Fi Aware.",
        );
        if recovered > 0 {
            state.log(&format!(
                "Recovered {} pending raw {} from the durable inbox.",
                recovered,
                if recovered == 1 { "capture" } else { "captures" }
            ));
        }
        Ok(Self {
            state: Mutex::new(state),
        })
    }

    pub fn on_ui_action(
        &self,
        action: UiAction,
    ) -> Result<(), NearbyHashtreeError> {
        let mut state = self.lock_state()?;
        state.on_ui_action(action)
    }

    pub fn on_android_event(
        &self,
        event: AndroidEvent,
    ) -> Result<(), NearbyHashtreeError> {
        let mut state = self.lock_state()?;
        state.on_android_event(event)
    }

    pub fn take_pending_commands(&self) -> Result<Vec<AndroidCommand>, NearbyHashtreeError> {
        let mut state = self.lock_state()?;
        Ok(state.pending_commands.drain(..).collect())
    }

    pub fn current_view_state(&self) -> Result<ViewState, NearbyHashtreeError> {
        let state = self.lock_state()?;
        state.view_state()
    }
}

impl AppCore {
    fn lock_state(&self) -> Result<std::sync::MutexGuard<'_, CoreState>, NearbyHashtreeError> {
        self.state
            .lock()
            .map_err(|_| NearbyHashtreeError::Message("app core mutex poisoned".to_string()))
    }
}

impl CoreState {
    fn on_ui_action(
        &mut self,
        action: UiAction,
    ) -> Result<(), NearbyHashtreeError> {
        match action {
            UiAction::TakePhotoRequested => {
                if self.camera_preview_active || self.capture_in_progress {
                    return Ok(());
                }
                let output_path = self.photo_store.create_capture_temp_path()?;
                self.pending_capture_output_path = Some(output_path);
                self.last_sync_error = None;
                self.queue(AndroidCommand::RequestCameraPermission);
            }
            UiAction::CapturePhotoRequested => {
                let Some(output_path) = self.pending_capture_output_path.clone() else {
                    return Ok(());
                };
                if self.capture_in_progress {
                    return Ok(());
                }
                self.capture_in_progress = true;
                self.last_sync_error = None;
                self.queue(AndroidCommand::CapturePhoto { output_path });
            }
            UiAction::CancelCameraRequested => {
                self.camera_preview_active = false;
                self.capture_in_progress = false;
                self.pending_capture_output_path = None;
                self.queue(AndroidCommand::StopCameraPreview);
            }
            UiAction::ToggleNearbyRequested => {
                if self.role == Role::Idle {
                    self.role = Role::Peer;
                    self.pending_start_nearby = true;
                    self.status_text = "Starting nearby".to_string();
                    self.link_text = "Requesting permissions".to_string();
                    self.log(&format!("Attaching as Nearby ({})", self.app_instance));
                    self.queue(AndroidCommand::RequestNearbyPermission);
                } else {
                    self.stop("Disconnected from nearby mode");
                }
            }
            UiAction::ClearDemoDataRequested => {
                if self.role != Role::Idle {
                    self.stop("Disconnected from nearby mode for data clear");
                }
                self.camera_preview_active = false;
                self.capture_in_progress = false;
                self.pending_capture_output_path = None;
                self.last_sync_error = None;
                self.photo_store.clear_all()?;
                self.log("Cleared local hashtree roots, blocks, and render cache.");
            }
            UiAction::SwitchPage(page) => {
                self.page = page;
            }
            UiAction::ClearLogRequested => {
                self.log_lines.clear();
            }
        }
        Ok(())
    }

    fn on_android_event(
        &mut self,
        event: AndroidEvent,
    ) -> Result<(), NearbyHashtreeError> {
        match event {
            AndroidEvent::CameraPermissionGranted => {
                let Some(output_path) = self.pending_capture_output_path.clone() else {
                    return Ok(());
                };
                self.camera_preview_active = true;
                self.capture_in_progress = false;
                self.queue(AndroidCommand::StartCameraPreview { output_path });
            }
            AndroidEvent::CameraPermissionDenied => {
                self.camera_preview_active = false;
                self.capture_in_progress = false;
                self.pending_capture_output_path = None;
                self.last_sync_error = Some("Camera permission denied.".to_string());
                self.log("Camera permission denied.");
            }
            AndroidEvent::NearbyPermissionGranted => {
                self.log("Nearby permission granted.");
                if self.pending_start_nearby {
                    self.queue(AndroidCommand::StartAwareAttach);
                }
            }
            AndroidEvent::NearbyPermissionDenied => {
                self.pending_start_nearby = false;
                self.role = Role::Idle;
                self.status_text = "Permission denied".to_string();
                self.link_text = "Idle".to_string();
                self.log("Missing runtime permission. Wi-Fi Aware cannot start.");
            }
            AndroidEvent::CameraCaptureSaved { output_path } => {
                if self.pending_capture_output_path.as_deref() != Some(output_path.as_str()) {
                    self.log(&format!(
                        "Ignoring captured file {} because it no longer matches the active capture request.",
                        output_path
                    ));
                    return Ok(());
                }
                self.queue(AndroidCommand::StopCameraPreview);
                self.camera_preview_active = false;
                self.capture_in_progress = false;
                self.pending_capture_output_path = None;
                match self.photo_store.finalize_captured_photo(&output_path) {
                    Ok(photo) => {
                        self.page = UiPage::Feed;
                        self.last_sync_error = None;
                        self.log(&format!(
                            "Captured photo {} cid={} size={}. Stored raw JPEG durably first, then ingested into the local hashtree feed.",
                            photo.id,
                            short_cid(&photo.photo_cid),
                            format_byte_count(photo.size_bytes)
                        ));
                        self.on_local_root_updated("new photo captured")?;
                    }
                    Err(err) => {
                        self.last_sync_error = Some(err.to_string());
                        self.log(&format!(
                            "Camera save completed, but hashtree ingest failed. The raw JPEG is still durable in the inbox: {}",
                            err
                        ));
                    }
                }
            }
            AndroidEvent::CameraCaptureFailed { message } => {
                self.capture_in_progress = false;
                self.last_sync_error = Some(message.clone());
                self.log(&format!("Camera capture failed: {message}"));
            }
            AndroidEvent::AwareAvailabilityChanged { available } => {
                self.nearby_available = available;
                if !available {
                    self.pending_start_nearby = false;
                    self.clear_runtime_connections();
                    if self.role == Role::Peer {
                        self.status_text = "Wi-Fi Aware unavailable".to_string();
                        self.link_text = "Unavailable".to_string();
                        self.queue(AndroidCommand::StopAware);
                        self.log("Wi-Fi Aware became unavailable. Waiting for it to return.");
                    }
                } else if self.role == Role::Peer
                    && !self.pending_start_nearby
                    && self.connections.is_empty()
                {
                    self.pending_start_nearby = true;
                    self.status_text = "Restarting nearby".to_string();
                    self.link_text = "Reattaching".to_string();
                    self.queue(AndroidCommand::StartAwareAttach);
                    self.log("Wi-Fi Aware became available again. Reattaching.");
                }
            }
            AndroidEvent::AwareAttachSucceeded => {
                self.pending_start_nearby = false;
                self.status_text = "Nearby on".to_string();
                self.log("Attach succeeded.");
                self.queue(AndroidCommand::StartPublish {
                    service_name: SERVICE_NAME.to_string(),
                    service_info: format!("peer:{}", self.app_instance),
                });
                self.queue(AndroidCommand::StartSubscribe {
                    service_name: SERVICE_NAME.to_string(),
                });
                self.refresh_link_text();
            }
            AndroidEvent::AwareAttachFailed => {
                self.pending_start_nearby = false;
                if self.role == Role::Peer {
                    self.status_text = "Attach failed".to_string();
                    self.link_text = "Retry connect".to_string();
                } else {
                    self.status_text = "Nearby off".to_string();
                    self.link_text = "Idle".to_string();
                }
                self.log("Attach failed.");
            }
            AndroidEvent::PublishStarted => {
                self.log("Nearby publish started.");
                self.refresh_link_text();
            }
            AndroidEvent::SubscribeStarted => {
                self.log("Nearby subscribe started.");
                self.refresh_link_text();
            }
            AndroidEvent::PublishTerminated => {
                self.log("Nearby publish session terminated.");
                if self.role == Role::Peer && self.nearby_available {
                    self.restart_aware_session("Restarting nearby after publish session termination.");
                }
            }
            AndroidEvent::SubscribeTerminated => {
                self.log("Nearby subscribe session terminated.");
                if self.role == Role::Peer && self.nearby_available {
                    self.restart_aware_session("Restarting nearby after subscribe session termination.");
                }
            }
            AndroidEvent::PeerDiscovered { handle_id, instance } => {
                let Some(instance) = instance else {
                    self.log(&format!(
                        "Discovered {}, but it did not advertise an app instance.",
                        peer_label(handle_id)
                    ));
                    return Ok(());
                };
                let now_ms = now_millis();
                let is_new = !self.peers.contains_key(&instance);
                self.remember_peer_handle(instance.clone(), handle_id, false, now_ms);
                self.refresh_desired_root_for_peer(&instance)?;
                if is_new {
                    self.log(&format!(
                        "Discovered nearby peer {} as {}",
                        instance,
                        peer_label(handle_id)
                    ));
                }
                self.refresh_link_text();
                self.ensure_data_path_for_peer(&instance);
            }
            AndroidEvent::DiscoveryMessageReceived {
                channel,
                handle_id,
                payload,
            } => {
                self.log(&format!(
                    "Nearby {} received '{}' from {}",
                    channel_label(&channel),
                    payload,
                    peer_label(handle_id)
                ));
                let now_ms = now_millis();
                if let Some(remote_instance) = payload.strip_prefix("hello:") {
                    self.remember_peer_handle(
                        remote_instance.to_string(),
                        handle_id,
                        channel == DiscoveryChannel::Publish,
                        now_ms,
                    );
                    self.refresh_desired_root_for_peer(remote_instance)?;
                    self.handle_responder_hello(remote_instance.to_string(), handle_id)?;
                } else if let Some(remote_instance) = payload.strip_prefix("ready:") {
                    self.remember_peer_handle(
                        remote_instance.to_string(),
                        handle_id,
                        channel == DiscoveryChannel::Subscribe,
                        now_ms,
                    );
                    self.refresh_desired_root_for_peer(remote_instance)?;
                    self.handle_initiator_ready(remote_instance.to_string(), handle_id)?;
                }
            }
            AndroidEvent::DiscoveryMessageSent { message_id } => {
                self.log(&format!("Nearby discovery sent message #{message_id}"));
            }
            AndroidEvent::DiscoveryMessageFailed { message_id } => {
                self.log(&format!("Nearby discovery failed to send message #{message_id}"));
                let peer_instances: Vec<String> = self
                    .peers
                    .iter()
                    .filter_map(|(peer_instance, peer)| {
                        if peer.connected_side.is_none() {
                            Some(peer_instance.clone())
                        } else {
                            None
                        }
                    })
                    .collect();
                for peer_instance in peer_instances {
                    if let Some(peer) = self.peers.get_mut(&peer_instance) {
                        peer.hello_sent = false;
                    }
                    self.schedule_peer_reconnect(
                        &peer_instance,
                        &format!(
                            "Retrying nearby discovery with {} after a failed message send.",
                            peer_instance
                        ),
                    );
                }
            }
            AndroidEvent::ResponderNetworkAvailable { connection_id } => {
                self.log(&format!(
                    "Responder Wi-Fi Aware data path available for connection #{connection_id}."
                ));
            }
            AndroidEvent::ResponderNetworkLost { connection_id } => {
                self.log(&format!(
                    "Responder Wi-Fi Aware data path lost for connection #{connection_id}."
                ));
                self.queue(AndroidCommand::CloseSocket { connection_id });
            }
            AndroidEvent::InitiatorNetworkAvailable { connection_id } => {
                self.log(&format!(
                    "Initiator Wi-Fi Aware data path available for connection #{connection_id}."
                ));
            }
            AndroidEvent::InitiatorNetworkLost { connection_id } => {
                if let Some(peer_instance) = self.connection_peer_instance(connection_id) {
                    if let Some(peer) = self.peers.get_mut(&peer_instance) {
                        peer.initiator_requested = false;
                        peer.socket_connect_requested = false;
                    }
                }
                self.log(&format!(
                    "Initiator Wi-Fi Aware data path lost for connection #{connection_id}."
                ));
                self.queue(AndroidCommand::CloseSocket { connection_id });
            }
            AndroidEvent::InitiatorCapabilities {
                connection_id,
                port,
                ipv6,
            } => {
                self.log(&format!(
                    "Initiator learned responder endpoint for connection #{} port={} ipv6={}",
                    connection_id,
                    port,
                    ipv6.clone().unwrap_or_else(|| "null".to_string())
                ));
                if port <= 0 {
                    return Ok(());
                }
                let Some(ipv6) = ipv6 else {
                    return Ok(());
                };
                let Some(peer_instance) = self.connection_peer_instance(connection_id) else {
                    return Ok(());
                };
                let already_connected = self
                    .connections
                    .get(&connection_id)
                    .and_then(|connection| connection.side.as_ref())
                    .is_some();
                let should_connect = if already_connected {
                    false
                } else if let Some(peer) = self.peers.get_mut(&peer_instance) {
                    if peer.connected_side.is_some() || peer.socket_connect_requested {
                        false
                    } else {
                        peer.socket_connect_requested = true;
                        true
                    }
                } else {
                    false
                };
                if should_connect {
                    self.queue(AndroidCommand::ConnectInitiatorSocket {
                        connection_id,
                        ipv6,
                        port,
                    });
                }
            }
            AndroidEvent::SocketConnected { connection_id, side } => {
                let Some(peer_instance) = self.connection_peer_instance(connection_id) else {
                    self.log(&format!(
                        "Socket connected for unknown connection #{}.",
                        connection_id
                    ));
                    self.queue(AndroidCommand::CloseSocket { connection_id });
                    return Ok(());
                };
                if let Some(connection) = self.connections.get_mut(&connection_id) {
                    connection.side = Some(side.clone());
                }
                if let Some(peer) = self.peers.get_mut(&peer_instance) {
                    peer.connected_side = Some(side.clone());
                    peer.initiator_requested = false;
                    peer.socket_connect_requested = false;
                    peer.hello_sent = true;
                    peer.reconnect_scheduled = false;
                    peer.reconnect_backoff_ms = INITIAL_RECONNECT_BACKOFF_MS;
                }
                self.inbound_decoders.entry(connection_id).or_default();
                self.log(&format!(
                    "{} Wi-Fi Aware TCP socket connected to {}.",
                    socket_side_label(&side),
                    peer_instance
                ));
                self.refresh_link_text();
                self.queue(AndroidCommand::WriteSocketBytes {
                    connection_id,
                    bytes: encode_frame(&WireMessage::SocketHello {
                        instance: self.app_instance.clone(),
                    })?,
                });
                self.maybe_start_sync_to_peer(&peer_instance, "peer socket connected")?;
            }
            AndroidEvent::SocketClosed { connection_id } => {
                self.inbound_decoders.remove(&connection_id);
                if let Some(connection) = self.connections.remove(&connection_id) {
                    let peer_instance = connection.peer_instance.clone();
                    if let Some(peer) = self.peers.get_mut(&connection.peer_instance) {
                        if peer.connection_id == Some(connection_id) {
                            peer.connection_id = None;
                            peer.connected_side = None;
                            peer.initiator_requested = false;
                            peer.socket_connect_requested = false;
                            peer.hello_sent = false;
                            peer.in_flight_root = None;
                            peer.in_flight_sync_id = None;
                        }
                    }
                    self.log(&format!(
                        "Peer sync socket closed for {}.",
                        connection.peer_instance
                    ));
                    self.schedule_peer_reconnect(
                        &peer_instance,
                        &format!("Nearby link to {} dropped. Reconnecting.", peer_instance),
                    );
                }
                self.refresh_link_text();
            }
            AndroidEvent::SocketRead {
                connection_id,
                bytes,
            } => {
                let messages = self
                    .inbound_decoders
                    .entry(connection_id)
                    .or_default()
                    .push(&bytes)?;
                for message in messages {
                    self.handle_wire_message(connection_id, message)?;
                }
            }
            AndroidEvent::SocketError {
                connection_id,
                message,
            } => {
                self.last_sync_error = Some(message.clone());
                self.log(&format!("Socket {connection_id} error: {message}"));
                self.queue(AndroidCommand::CloseSocket { connection_id });
            }
            AndroidEvent::ReconnectPeer { peer_instance } => {
                if let Some(peer) = self.peers.get_mut(&peer_instance) {
                    peer.reconnect_scheduled = false;
                }
                self.clear_stale_connection_for_peer(&peer_instance, true);
                self.ensure_data_path_for_peer(&peer_instance);
                let should_retry_again = self
                    .peers
                    .get(&peer_instance)
                    .map(|peer| {
                        peer.connected_side.is_none()
                            && peer.connection_id.is_none()
                            && !peer.initiator_requested
                            && !peer.hello_sent
                    })
                    .unwrap_or(false);
                if should_retry_again {
                    self.schedule_peer_reconnect(
                        &peer_instance,
                        &format!("Still waiting to relink {}. Retrying nearby handshake.", peer_instance),
                    );
                }
            }
        }
        Ok(())
    }

    fn view_state(&self) -> Result<ViewState, NearbyHashtreeError> {
        let local_count = self.photo_store.local_count()?;
        let nearby_count = self.photo_store.received_count()?;
        let storage_bytes = self.photo_store.total_storage_bytes()?;
        let connected_peers = self.connected_peer_count();
        let in_flight_syncs = self.in_flight_sync_count();
        let queued_syncs = self.queued_sync_count();

        Ok(ViewState {
            page: self.page.clone(),
            status_text: format!("STATUS  {}", self.status_text),
            mode_text: format!("MODE  {}", self.role_label()),
            link_text: format!("LINK  {} nearby · {} linked", self.peers.len(), connected_peers),
            storage_text: format!("STORAGE  {}", format_byte_count(storage_bytes)),
            capture_queue_text: format!("CAMERA  {}", self.capture_status_text()),
            sync_status_text: format!("SYNC  {} active · {} queued", in_flight_syncs, queued_syncs),
            last_sync_error_text: format!(
                "LAST ERROR  {}",
                self.last_sync_error
                    .clone()
                    .unwrap_or_else(|| "None".to_string())
            ),
            local_summary_text: format!("Your photos: {}  ·  Stored as local feed blocks", local_count),
            nearby_summary_text: format!(
                "Received nearby: {}  ·  Content-addressed merge",
                nearby_count
            ),
            controls_enabled: ControlsEnabled {
                take_photo: true,
                toggle_nearby: !self.pending_start_nearby,
                clear_demo_data: !self.capture_in_progress,
                clear_log: true,
            },
            feed_items: self.photo_store.feed_items()?,
            log_lines: self.log_lines.iter().cloned().collect(),
        })
    }

    fn capture_status_text(&self) -> String {
        if self.capture_in_progress {
            "Saving photo".to_string()
        } else if self.camera_preview_active {
            "Camera ready".to_string()
        } else {
            "Idle".to_string()
        }
    }

    fn role_label(&self) -> &'static str {
        match self.role {
            Role::Idle => "Idle",
            Role::Peer => "Nearby",
        }
    }

    fn queue(&mut self, command: AndroidCommand) {
        self.pending_commands.push_back(command);
    }

    fn log(&mut self, message: &str) {
        self.log_lines.push_back(message.to_string());
        while self.log_lines.len() > 400 {
            self.log_lines.pop_front();
        }
    }

    fn in_flight_sync_count(&self) -> usize {
        self.peers
            .values()
            .filter(|peer| peer.in_flight_root.is_some())
            .count()
    }

    fn queued_sync_count(&self) -> usize {
        self.peers
            .values()
            .filter(|peer| {
                peer.desired_root.is_some()
                    && peer.desired_root != peer.last_sent_root
                    && peer.in_flight_root.is_none()
            })
            .count()
    }

    fn connected_peer_count(&self) -> usize {
        self.peers
            .values()
            .filter(|peer| peer.connection_id.is_some() && peer.connected_side.is_some())
            .count()
    }

    fn refresh_link_text(&mut self) {
        self.link_text = match self.role {
            Role::Idle => "Idle".to_string(),
            Role::Peer => {
                let discovered = self.peers.len();
                let connected = self.connected_peer_count();
                format!("{discovered} nearby · {connected} linked")
            }
        };
    }

    fn remember_peer_handle(
        &mut self,
        instance: String,
        handle_id: i64,
        is_publish_handle: bool,
        now_ms: i64,
    ) {
        let peer = self
            .peers
            .entry(instance.clone())
            .or_insert_with(|| PeerState::new(now_ms));
        let handle_changed = if is_publish_handle {
            peer.publish_handle_id != Some(handle_id)
        } else {
            peer.subscribe_handle_id != Some(handle_id)
        };
        peer.last_seen_ms = now_ms;
        if is_publish_handle {
            peer.publish_handle_id = Some(handle_id);
            self.publish_handle_to_instance.insert(handle_id, instance);
        } else {
            peer.subscribe_handle_id = Some(handle_id);
            self.subscribe_handle_to_instance.insert(handle_id, instance);
        }
        if handle_changed && peer.connected_side.is_none() {
            peer.hello_sent = false;
            peer.initiator_requested = false;
            peer.socket_connect_requested = false;
        }
    }

    fn connection_peer_instance(
        &self,
        connection_id: i64,
    ) -> Option<String> {
        self.connections
            .get(&connection_id)
            .map(|connection| connection.peer_instance.clone())
    }

    fn should_initiate_data_path(
        &self,
        remote_instance: &str,
    ) -> bool {
        self.app_instance.as_str() < remote_instance
    }

    fn ensure_data_path_for_peer(
        &mut self,
        peer_instance: &str,
    ) {
        if self.role != Role::Peer || !self.nearby_available {
            return;
        }

        self.clear_stale_connection_for_peer(peer_instance, false);

        let (should_initiate, handle_id, already_connected, initiator_requested, hello_sent) =
            match self.peers.get(peer_instance) {
                Some(peer) => (
                    self.should_initiate_data_path(peer_instance),
                    peer.subscribe_handle_id,
                    peer.connection_id.is_some(),
                    peer.initiator_requested,
                    peer.hello_sent,
                ),
                None => return,
            };

        if already_connected || initiator_requested {
            return;
        }

        if should_initiate {
            let Some(handle_id) = handle_id else {
                return;
            };
            if hello_sent {
                return;
            }
            if let Some(peer) = self.peers.get_mut(peer_instance) {
                peer.hello_sent = true;
            }
            self.log(&format!(
                "Tie-break selected this phone to initiate the Wi-Fi Aware data path with {}.",
                peer_instance
            ));
            self.send_discovery_message(
                DiscoveryChannel::Subscribe,
                handle_id,
                format!("hello:{}", self.app_instance),
            );
        }
    }

    fn clear_stale_connection_for_peer(
        &mut self,
        peer_instance: &str,
        force: bool,
    ) {
        let stale_connection_id = self.peers.get(peer_instance).and_then(|peer| {
            let connection_id = peer.connection_id?;
            let side = self
                .connections
                .get(&connection_id)
                .and_then(|connection| connection.side.as_ref());
            if side.is_none()
                && (force || (!peer.initiator_requested && !peer.socket_connect_requested))
            {
                Some(connection_id)
            } else {
                None
            }
        });

        let Some(connection_id) = stale_connection_id else {
            return;
        };

        self.connections.remove(&connection_id);
        self.inbound_decoders.remove(&connection_id);
        if let Some(peer) = self.peers.get_mut(peer_instance) {
            if peer.connection_id == Some(connection_id) {
                peer.connection_id = None;
                peer.connected_side = None;
                peer.initiator_requested = false;
                peer.socket_connect_requested = false;
                peer.hello_sent = false;
                peer.in_flight_root = None;
                peer.in_flight_sync_id = None;
            }
        }
    }

    fn handle_responder_hello(
        &mut self,
        peer_instance: String,
        handle_id: i64,
    ) -> Result<(), NearbyHashtreeError> {
        let existing_connection_id = self
            .peers
            .get(&peer_instance)
            .and_then(|peer| peer.connection_id);
        if let Some(existing_connection_id) = existing_connection_id {
            let existing_side = self
                .connections
                .get(&existing_connection_id)
                .and_then(|connection| connection.side.clone());
            if existing_side.is_none() {
                self.log(&format!(
                    "Already preparing a responder data path for {}.",
                    peer_instance
                ));
                self.send_discovery_message(
                    DiscoveryChannel::Publish,
                    handle_id,
                    format!("ready:{}", self.app_instance),
                );
                return Ok(());
            }
            self.log(&format!(
                "Received duplicate hello from {} while already connected. Reusing the existing responder data path.",
                peer_instance
            ));
            self.send_discovery_message(
                DiscoveryChannel::Publish,
                handle_id,
                format!("ready:{}", self.app_instance),
            );
            self.maybe_start_sync_to_peer(&peer_instance, "duplicate hello on live link")?;
            return Ok(());
        }

        let connection_id = self.next_connection_id;
        self.next_connection_id += 1;
        self.connections.insert(
            connection_id,
            ConnectionState {
                peer_instance: peer_instance.clone(),
                side: None,
                outbound_sync_id: None,
                outbound_root: None,
                inbound_sync_id: None,
                inbound_root: None,
            },
        );
        if let Some(peer) = self.peers.get_mut(&peer_instance) {
            peer.publish_handle_id = Some(handle_id);
            peer.connection_id = Some(connection_id);
            peer.connected_side = None;
            peer.initiator_requested = false;
            peer.socket_connect_requested = false;
            peer.hello_sent = true;
        }
        self.log(&format!(
            "Preparing a Wi-Fi Aware data path for {}",
            peer_instance
        ));
        self.queue(AndroidCommand::OpenResponder {
            handle_id,
            passphrase: SECURE_PASSPHRASE.to_string(),
            port: 0,
            protocol: TCP_PROTOCOL,
            connection_id,
        });
        self.send_discovery_message(
            DiscoveryChannel::Publish,
            handle_id,
            format!("ready:{}", self.app_instance),
        );
        Ok(())
    }

    fn handle_initiator_ready(
        &mut self,
        peer_instance: String,
        handle_id: i64,
    ) -> Result<(), NearbyHashtreeError> {
        let already_connected = self
            .peers
            .get(&peer_instance)
            .map(|peer| peer.connection_id.is_some() || peer.initiator_requested)
            .unwrap_or(false);
        if already_connected {
            self.log(&format!(
                "Already preparing or holding a Wi-Fi Aware data path to {}.",
                peer_instance
            ));
            return Ok(());
        }

        let connection_id = self.next_connection_id;
        self.next_connection_id += 1;
        self.connections.insert(
            connection_id,
            ConnectionState {
                peer_instance: peer_instance.clone(),
                side: None,
                outbound_sync_id: None,
                outbound_root: None,
                inbound_sync_id: None,
                inbound_root: None,
            },
        );
        if let Some(peer) = self.peers.get_mut(&peer_instance) {
            peer.subscribe_handle_id = Some(handle_id);
            peer.connection_id = Some(connection_id);
            peer.connected_side = None;
            peer.initiator_requested = true;
            peer.socket_connect_requested = false;
            peer.hello_sent = true;
        }
        self.log(&format!(
            "Initiating a Wi-Fi Aware data path to {}",
            peer_instance
        ));
        self.queue(AndroidCommand::OpenInitiator {
            handle_id,
            passphrase: SECURE_PASSPHRASE.to_string(),
            connection_id,
        });
        Ok(())
    }

    fn send_discovery_message(
        &mut self,
        channel: DiscoveryChannel,
        handle_id: i64,
        payload: String,
    ) {
        let message_id = self.next_message_id;
        self.next_message_id += 1;
        self.log(&format!(
            "Sending '{}' to {} ({})",
            payload,
            peer_label(handle_id),
            match channel {
                DiscoveryChannel::Publish => "publish channel",
                DiscoveryChannel::Subscribe => "subscribe channel",
            }
        ));
        self.queue(AndroidCommand::SendDiscoveryMessage {
            channel,
            handle_id,
            payload,
            message_id,
        });
    }

    fn refresh_desired_root_for_peer(
        &mut self,
        peer_instance: &str,
    ) -> Result<(), NearbyHashtreeError> {
        let current_root = self.photo_store.current_root()?;
        if let Some(peer) = self.peers.get_mut(peer_instance) {
            if peer.desired_root.is_none() {
                peer.desired_root = current_root;
            }
        }
        Ok(())
    }

    fn on_local_root_updated(
        &mut self,
        trigger: &str,
    ) -> Result<(), NearbyHashtreeError> {
        let Some(root) = self.photo_store.current_root()? else {
            return Ok(());
        };
        for peer in self.peers.values_mut() {
            peer.desired_root = Some(root.clone());
        }
        if self.role != Role::Peer {
            self.log("Nearby mode is off. Local photo was stored, but no peers are connected yet.");
            return Ok(());
        }
        if self.peers.is_empty() {
            self.log("No nearby peers discovered yet. The photo stays stored locally until a peer links.");
            return Ok(());
        }
        let peer_instances: Vec<String> = self.peers.keys().cloned().collect();
        for peer_instance in peer_instances {
            self.maybe_start_sync_to_peer(&peer_instance, trigger)?;
            self.ensure_data_path_for_peer(&peer_instance);
        }
        Ok(())
    }

    fn maybe_start_sync_to_peer(
        &mut self,
        peer_instance: &str,
        trigger: &str,
    ) -> Result<bool, NearbyHashtreeError> {
        let (connection_id, desired_root, already_sent) = match self.peers.get(peer_instance) {
            Some(peer) => (
                peer.connection_id,
                peer.desired_root.clone(),
                peer.last_sent_root.clone(),
            ),
            None => return Ok(false),
        };
        let Some(desired_root) = desired_root else {
            return Ok(false);
        };
        if already_sent.as_ref() == Some(&desired_root) {
            return Ok(false);
        }
        if self
            .peers
            .get(peer_instance)
            .and_then(|peer| peer.in_flight_root.as_ref())
            .is_some()
        {
            return Ok(false);
        }
        let Some(connection_id) = connection_id else {
            return Ok(false);
        };
        if self
            .connections
            .get(&connection_id)
            .and_then(|connection| connection.side.as_ref())
            .is_none()
        {
            return Ok(false);
        }
        self.send_root_announce_for_connection(connection_id, &desired_root, trigger)?;
        Ok(true)
    }

    fn send_root_announce_for_connection(
        &mut self,
        connection_id: i64,
        root_nhash: &str,
        trigger: &str,
    ) -> Result<(), NearbyHashtreeError> {
        let block_hashes = self.photo_store.collect_root_hashes(root_nhash)?;
        let entry_count = self.photo_store.current_entry_count()? as u32;
        let Some(connection) = self.connections.get_mut(&connection_id) else {
            return Ok(());
        };
        let sync_id = self.next_sync_id;
        self.next_sync_id += 1;
        connection.outbound_sync_id = Some(sync_id);
        connection.outbound_root = Some(root_nhash.to_string());
        let peer_instance = connection.peer_instance.clone();
        if let Some(peer) = self.peers.get_mut(&peer_instance) {
            peer.in_flight_root = Some(root_nhash.to_string());
            peer.in_flight_sync_id = Some(sync_id);
        }
        self.log(&format!(
            "Syncing feed root {} ({} photos, {} blocks) to {} over Wi-Fi Aware ({}, sync #{sync_id})",
            short_cid(root_nhash),
            entry_count,
            block_hashes.len(),
            peer_instance,
            trigger
        ));
        self.queue(AndroidCommand::WriteSocketBytes {
            connection_id,
            bytes: encode_frame(&WireMessage::RootAnnounce {
                feed_root_nhash: root_nhash.to_string(),
                block_hashes,
                entry_count,
                sync_id,
            })?,
        });
        Ok(())
    }

    fn handle_wire_message(
        &mut self,
        connection_id: i64,
        message: WireMessage,
    ) -> Result<(), NearbyHashtreeError> {
        let peer_instance = self
            .connection_peer_instance(connection_id)
            .unwrap_or_else(|| format!("connection-{connection_id}"));

        match message {
            WireMessage::SocketHello { instance } => {
                self.log(&format!(
                    "Peer {} confirmed socket identity as {}.",
                    peer_instance, instance
                ));
            }
            WireMessage::RootAnnounce {
                feed_root_nhash,
                block_hashes,
                entry_count,
                sync_id,
            } => {
                if let Some(connection) = self.connections.get_mut(&connection_id) {
                    connection.inbound_sync_id = Some(sync_id);
                    connection.inbound_root = Some(feed_root_nhash.clone());
                }
                self.log(&format!(
                    "Peer {} announced feed root {} ({} photos, {} blocks, sync #{sync_id}).",
                    peer_instance,
                    short_cid(&feed_root_nhash),
                    entry_count,
                    block_hashes.len()
                ));
                let missing_hashes = self.photo_store.missing_hashes(&block_hashes)?;
                self.log(&format!(
                    "Need {} of {} blocks for root {}.",
                    missing_hashes.len(),
                    block_hashes.len(),
                    short_cid(&feed_root_nhash)
                ));
                self.queue(AndroidCommand::WriteSocketBytes {
                    connection_id,
                    bytes: encode_frame(&WireMessage::BlockWant {
                        feed_root_nhash,
                        missing_hashes,
                        sync_id,
                    })?,
                });
            }
            WireMessage::BlockWant {
                feed_root_nhash,
                missing_hashes,
                sync_id,
            } => {
                let expected_sync_id = self
                    .connections
                    .get(&connection_id)
                    .and_then(|connection| connection.outbound_sync_id);
                if expected_sync_id != Some(sync_id) {
                    self.log(&format!(
                        "Ignoring block request for stale sync #{sync_id} from {}.",
                        peer_instance
                    ));
                    return Ok(());
                }
                self.log(&format!(
                    "Peer {} requested {} missing blocks for root {} (sync #{sync_id}).",
                    peer_instance,
                    missing_hashes.len(),
                    short_cid(&feed_root_nhash)
                ));
                for hash_hex in missing_hashes {
                    match self.photo_store.read_block(&hash_hex)? {
                        Some(bytes) => {
                            self.queue(AndroidCommand::WriteSocketBytes {
                                connection_id,
                                bytes: encode_frame(&WireMessage::BlockPut {
                                    hash_hex: hash_hex.clone(),
                                    bytes,
                                    sync_id,
                                })?,
                            });
                        }
                        None => {
                            self.log(&format!(
                                "Missing requested local block {} while serving {}.",
                                short_hash(&hash_hex),
                                peer_instance
                            ));
                        }
                    }
                }
                self.queue(AndroidCommand::WriteSocketBytes {
                    connection_id,
                    bytes: encode_frame(&WireMessage::SyncDone {
                        feed_root_nhash: feed_root_nhash.clone(),
                        sync_id,
                    })?,
                });
                self.log(&format!(
                    "Finished pushing root {} to {} (sync #{sync_id}).",
                    short_cid(&feed_root_nhash),
                    peer_instance
                ));
            }
            WireMessage::BlockPut {
                hash_hex,
                bytes,
                sync_id,
            } => {
                let expected_sync_id = self
                    .connections
                    .get(&connection_id)
                    .and_then(|connection| connection.inbound_sync_id);
                if expected_sync_id != Some(sync_id) {
                    self.log(&format!(
                        "Ignoring block {} for stale sync #{sync_id} from {}.",
                        short_hash(&hash_hex),
                        peer_instance
                    ));
                    return Ok(());
                }
                match self.photo_store.store_incoming_block(&hash_hex, &bytes) {
                    Ok(()) => {
                        self.log(&format!(
                            "Stored incoming block {} ({}).",
                            short_hash(&hash_hex),
                            format_byte_count(bytes.len() as u64)
                        ));
                    }
                    Err(err) => {
                        self.last_sync_error = Some(err.to_string());
                        self.log(&format!(
                            "Rejected incoming block {} from {}: {}",
                            short_hash(&hash_hex),
                            peer_instance,
                            err
                        ));
                    }
                }
            }
            WireMessage::SyncDone {
                feed_root_nhash,
                sync_id,
            } => {
                let expected_sync_id = self
                    .connections
                    .get(&connection_id)
                    .and_then(|connection| connection.inbound_sync_id);
                if expected_sync_id != Some(sync_id) {
                    self.log(&format!(
                        "Ignoring sync-done for stale sync #{sync_id} from {}.",
                        peer_instance
                    ));
                    return Ok(());
                }
                match self.photo_store.finalize_remote_sync(&feed_root_nhash) {
                    Ok(merge) => {
                        self.last_sync_error = None;
                        if let Some(peer) = self.peers.get_mut(&peer_instance) {
                            peer.last_applied_root = Some(feed_root_nhash.clone());
                        }
                        self.log(&format!(
                            "Verified root {} from {} and merged {} new photos ({} already present).",
                            short_cid(&feed_root_nhash),
                            peer_instance,
                            merge.added_entries,
                            merge.already_present_entries
                        ));
                        self.queue(AndroidCommand::WriteSocketBytes {
                            connection_id,
                            bytes: encode_frame(&WireMessage::SyncApplied {
                                feed_root_nhash: feed_root_nhash.clone(),
                                added_entries: merge.added_entries as u32,
                                sync_id,
                            })?,
                        });
                        if let Some(connection) = self.connections.get_mut(&connection_id) {
                            connection.inbound_sync_id = None;
                            connection.inbound_root = None;
                        }
                        if merge.added_entries > 0 {
                            self.on_local_root_updated("merged nearby photos")?;
                        }
                    }
                    Err(err) => {
                        self.last_sync_error = Some(err.to_string());
                        self.log(&format!(
                            "Root {} from {} did not verify cleanly: {}",
                            short_cid(&feed_root_nhash),
                            peer_instance,
                            err
                        ));
                        self.queue(AndroidCommand::WriteSocketBytes {
                            connection_id,
                            bytes: encode_frame(&WireMessage::SyncError {
                                feed_root_nhash: feed_root_nhash.clone(),
                                retryable: true,
                                reason: err.to_string(),
                                sync_id,
                            })?,
                        });
                        if let Some(connection) = self.connections.get_mut(&connection_id) {
                            connection.inbound_sync_id = None;
                            connection.inbound_root = None;
                        }
                    }
                }
            }
            WireMessage::SyncApplied {
                feed_root_nhash,
                added_entries,
                sync_id,
            } => {
                let expected_sync_id = self
                    .connections
                    .get(&connection_id)
                    .and_then(|connection| connection.outbound_sync_id);
                if expected_sync_id != Some(sync_id) {
                    self.log(&format!(
                        "Ignoring sync-applied for stale sync #{sync_id} from {}.",
                        peer_instance
                    ));
                    return Ok(());
                }
                if let Some(connection) = self.connections.get_mut(&connection_id) {
                    connection.outbound_sync_id = None;
                    connection.outbound_root = None;
                }
                if let Some(peer) = self.peers.get_mut(&peer_instance) {
                    peer.last_sent_root = Some(feed_root_nhash.clone());
                    peer.in_flight_root = None;
                    peer.in_flight_sync_id = None;
                }
                self.log(&format!(
                    "Peer {} applied root {} and reported {} new {} (sync #{sync_id}).",
                    peer_instance,
                    short_cid(&feed_root_nhash),
                    added_entries,
                    if added_entries == 1 { "photo" } else { "photos" }
                ));
                self.maybe_start_sync_to_peer(&peer_instance, "queued newer root")?;
            }
            WireMessage::SyncError {
                feed_root_nhash,
                retryable,
                reason,
                sync_id,
            } => {
                let expected_sync_id = self
                    .connections
                    .get(&connection_id)
                    .and_then(|connection| connection.outbound_sync_id);
                if expected_sync_id != Some(sync_id) {
                    self.log(&format!(
                        "Ignoring sync-error for stale sync #{sync_id} from {}.",
                        peer_instance
                    ));
                    return Ok(());
                }
                self.last_sync_error = Some(reason.clone());
                if let Some(connection) = self.connections.get_mut(&connection_id) {
                    connection.outbound_sync_id = None;
                    connection.outbound_root = None;
                }
                if let Some(peer) = self.peers.get_mut(&peer_instance) {
                    peer.in_flight_root = None;
                    peer.in_flight_sync_id = None;
                }
                self.log(&format!(
                    "Peer {} rejected root {} (sync #{sync_id}) retryable={} reason={}",
                    peer_instance,
                    short_cid(&feed_root_nhash),
                    retryable,
                    reason
                ));
                if retryable {
                    self.schedule_peer_reconnect(
                        &peer_instance,
                        &format!("Retrying sync to {} after remote verification failure.", peer_instance),
                    );
                }
            }
        }
        Ok(())
    }

    fn schedule_peer_reconnect(
        &mut self,
        peer_instance: &str,
        reason: &str,
    ) {
        if self.role != Role::Peer || !self.nearby_available {
            return;
        }
        let Some(peer) = self.peers.get_mut(peer_instance) else {
            return;
        };
        if peer.reconnect_scheduled {
            return;
        }
        let has_handle = peer.publish_handle_id.is_some() || peer.subscribe_handle_id.is_some();
        if !has_handle {
            return;
        }
        let delay_ms = peer.reconnect_backoff_ms;
        peer.reconnect_backoff_ms = (peer.reconnect_backoff_ms * 2).min(MAX_RECONNECT_BACKOFF_MS);
        peer.reconnect_scheduled = true;
        peer.hello_sent = false;
        self.log(reason);
        self.queue(AndroidCommand::ScheduleReconnect {
            peer_instance: peer_instance.to_string(),
            delay_ms,
        });
    }

    fn stop(
        &mut self,
        reason: &str,
    ) {
        self.pending_start_nearby = false;
        self.clear_runtime_connections();
        self.role = Role::Idle;
        self.status_text = "Nearby off".to_string();
        self.link_text = "Idle".to_string();
        self.queue(AndroidCommand::StopAware);
        self.log(reason);
    }

    fn restart_aware_session(
        &mut self,
        reason: &str,
    ) {
        self.pending_start_nearby = true;
        self.clear_runtime_connections();
        self.status_text = "Restarting nearby".to_string();
        self.link_text = "Reattaching".to_string();
        self.queue(AndroidCommand::StopAware);
        self.queue(AndroidCommand::StartAwareAttach);
        self.log(reason);
    }

    fn clear_runtime_connections(&mut self) {
        self.peers.clear();
        self.publish_handle_to_instance.clear();
        self.subscribe_handle_to_instance.clear();
        self.connections.clear();
        self.inbound_decoders.clear();
    }
}

fn peer_label(handle_id: i64) -> String {
    format!("peer-{:x}", handle_id)
}

fn channel_label(channel: &DiscoveryChannel) -> &'static str {
    match channel {
        DiscoveryChannel::Publish => "publish",
        DiscoveryChannel::Subscribe => "subscribe",
    }
}

fn socket_side_label(side: &SocketSide) -> &'static str {
    match side {
        SocketSide::Responder => "Responder",
        SocketSide::Initiator => "Initiator",
    }
}

fn format_byte_count(bytes: u64) -> String {
    if bytes >= 1_000_000 {
        format!("{:.2} MB", bytes as f64 / 1_000_000.0)
    } else if bytes >= 1_000 {
        format!("{:.2} KB", bytes as f64 / 1_000.0)
    } else {
        format!("{bytes} B")
    }
}

fn short_hash(hash_hex: &str) -> String {
    hash_hex.chars().take(12).collect()
}

fn short_cid(nhash: &str) -> String {
    if nhash.len() > 16 {
        nhash[nhash.len() - 16..].to_string()
    } else {
        nhash.to_string()
    }
}

fn now_millis() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as i64
}

#[cfg(test)]
mod tests {
    use super::{AppCore, SERVICE_NAME};
    use crate::protocol::{encode_frame, FrameDecoder, WireMessage};
    use crate::types::{
        AndroidCommand, AndroidEvent, DiscoveryChannel, SocketSide, UiAction,
    };

    fn make_core() -> AppCore {
        let dir = tempfile::tempdir().expect("tempdir");
        let files_dir = dir.path().join("files");
        let cache_dir = dir.path().join("cache");
        std::fs::create_dir_all(&files_dir).expect("files dir");
        std::fs::create_dir_all(&cache_dir).expect("cache dir");
        let core = AppCore::new(
            files_dir.display().to_string(),
            cache_dir.display().to_string(),
            "peer-a".to_string(),
        )
        .expect("core");
        std::mem::forget(dir);
        core
    }

    fn take_commands(core: &AppCore) -> Vec<AndroidCommand> {
        core.take_pending_commands().expect("pending commands")
    }

    fn decode_message(command: &AndroidCommand) -> WireMessage {
        match command {
            AndroidCommand::WriteSocketBytes { bytes, .. } => FrameDecoder::default()
                .push(bytes)
                .expect("decode frame")
                .into_iter()
                .next()
                .expect("wire message"),
            _ => panic!("not a write command"),
        }
    }

    fn seed_one_photo(core: &AppCore) {
        core.on_ui_action(UiAction::TakePhotoRequested).expect("take photo");
        assert!(matches!(
            take_commands(core).as_slice(),
            [AndroidCommand::RequestCameraPermission]
        ));
        core.on_android_event(AndroidEvent::CameraPermissionGranted)
            .expect("camera permission");
        let commands = take_commands(core);
        let output_path = match &commands[0] {
            AndroidCommand::StartCameraPreview { output_path } => output_path.clone(),
            other => panic!("unexpected command {other:?}"),
        };
        core.on_ui_action(UiAction::CapturePhotoRequested)
            .expect("capture photo");
        let commands = take_commands(core);
        assert!(matches!(
            commands.as_slice(),
            [AndroidCommand::CapturePhoto { .. }]
        ));
        std::fs::write(&output_path, b"seed-photo").expect("write capture");
        core.on_android_event(AndroidEvent::CameraCaptureSaved { output_path })
            .expect("capture saved");
        let _ = take_commands(core);
    }

    fn connect_peer(core: &AppCore, peer_instance: &str) -> i64 {
        core.on_ui_action(UiAction::ToggleNearbyRequested)
            .expect("toggle nearby");
        assert!(matches!(
            take_commands(core).as_slice(),
            [AndroidCommand::RequestNearbyPermission]
        ));
        core.on_android_event(AndroidEvent::NearbyPermissionGranted)
            .expect("nearby permission");
        assert!(matches!(
            take_commands(core).as_slice(),
            [AndroidCommand::StartAwareAttach]
        ));
        core.on_android_event(AndroidEvent::AwareAttachSucceeded)
            .expect("attach");
        let commands = take_commands(core);
        assert_eq!(commands.len(), 2);
        assert!(matches!(
            commands[0],
            AndroidCommand::StartPublish {
                ref service_name,
                ..
            } if service_name == SERVICE_NAME
        ));
        assert!(matches!(
            commands[1],
            AndroidCommand::StartSubscribe {
                ref service_name,
            } if service_name == SERVICE_NAME
        ));

        core.on_android_event(AndroidEvent::PeerDiscovered {
            handle_id: 41,
            instance: Some(peer_instance.to_string()),
        })
        .expect("peer discovered");
        let commands = take_commands(core);
        assert_eq!(commands.len(), 1);
        assert!(matches!(
            commands[0],
            AndroidCommand::SendDiscoveryMessage { .. }
        ));
        let connection_id = {
            core.on_android_event(AndroidEvent::DiscoveryMessageReceived {
                channel: DiscoveryChannel::Subscribe,
                handle_id: 41,
                payload: format!("ready:{peer_instance}"),
            })
            .expect("ready");
            let commands = take_commands(core);
            match &commands[0] {
                AndroidCommand::OpenInitiator { connection_id, .. } => *connection_id,
                other => panic!("unexpected command {other:?}"),
            }
        };
        core.on_android_event(AndroidEvent::SocketConnected {
            connection_id,
            side: SocketSide::Initiator,
        })
        .expect("socket connected");
        connection_id
    }

    #[test]
    fn start_nearby_requests_nearby_permission() {
        let core = make_core();
        core.on_ui_action(UiAction::ToggleNearbyRequested)
            .expect("toggle nearby");
        let commands = take_commands(&core);
        assert!(matches!(
            commands.as_slice(),
            [AndroidCommand::RequestNearbyPermission]
        ));
    }

    #[test]
    fn take_photo_requests_camera_permission_then_preview() {
        let core = make_core();
        core.on_ui_action(UiAction::TakePhotoRequested)
            .expect("take photo");
        assert!(matches!(
            take_commands(&core).as_slice(),
            [AndroidCommand::RequestCameraPermission]
        ));

        core.on_android_event(AndroidEvent::CameraPermissionGranted)
            .expect("camera permission");
        let commands = take_commands(&core);
        assert!(matches!(
            commands.as_slice(),
            [AndroidCommand::StartCameraPreview { .. }]
        ));
    }

    #[test]
    fn capture_stays_enabled_while_sync_is_active() {
        let core = make_core();
        seed_one_photo(&core);
        let connection_id = connect_peer(&core, "peer-b");
        let commands = take_commands(&core);
        assert_eq!(commands.len(), 2);
        assert!(matches!(decode_message(&commands[0]), WireMessage::SocketHello { .. }));
        assert!(matches!(decode_message(&commands[1]), WireMessage::RootAnnounce { .. }));

        let view = core.current_view_state().expect("view");
        assert!(view.controls_enabled.take_photo);

        core.on_android_event(AndroidEvent::SocketClosed { connection_id })
            .expect("socket closed");
        let _ = take_commands(&core);
    }

    #[test]
    fn duplicate_hello_on_live_link_does_not_close_socket() {
        let core = make_core();
        seed_one_photo(&core);
        let connection_id = connect_peer(&core, "peer-b");
        let _ = take_commands(&core);
        core.on_android_event(AndroidEvent::DiscoveryMessageReceived {
            channel: DiscoveryChannel::Publish,
            handle_id: 88,
            payload: "hello:peer-b".to_string(),
        })
        .expect("duplicate hello");
        let commands = take_commands(&core);
        assert!(!commands
            .iter()
            .any(|command| matches!(command, AndroidCommand::CloseSocket { connection_id: id } if *id == connection_id)));
    }

    #[test]
    fn new_capture_auto_syncs_to_connected_peer() {
        let core = make_core();
        seed_one_photo(&core);
        let connection_id = connect_peer(&core, "peer-b");
        let commands = take_commands(&core);
        let initial_sync_id = match decode_message(&commands[1]) {
            WireMessage::RootAnnounce { sync_id, .. } => sync_id,
            other => panic!("unexpected wire message {other:?}"),
        };

        core.on_ui_action(UiAction::TakePhotoRequested).expect("take photo");
        let _ = take_commands(&core);
        core.on_android_event(AndroidEvent::CameraPermissionGranted)
            .expect("camera permission");
        let commands = take_commands(&core);
        let output_path = match &commands[0] {
            AndroidCommand::StartCameraPreview { output_path } => output_path.clone(),
            other => panic!("unexpected command {other:?}"),
        };
        core.on_ui_action(UiAction::CapturePhotoRequested)
            .expect("capture photo");
        let _ = take_commands(&core);
        std::fs::write(&output_path, b"new-sync-photo").expect("write photo");
        core.on_android_event(AndroidEvent::CameraCaptureSaved { output_path })
            .expect("capture saved");
        let commands = take_commands(&core);
        assert!(matches!(commands[0], AndroidCommand::StopCameraPreview));
        assert_eq!(commands.len(), 1);

        core.on_android_event(AndroidEvent::SocketRead {
            connection_id,
            bytes: encode_frame(&WireMessage::SyncApplied {
                feed_root_nhash: "nhash1bogus".to_string(),
                added_entries: 0,
                sync_id: initial_sync_id,
            })
            .expect("frame"),
        })
        .expect("sync applied");
        let commands = take_commands(&core);
        assert!(matches!(decode_message(&commands[0]), WireMessage::RootAnnounce { .. }));
    }

    #[test]
    fn newer_root_is_queued_until_sync_applied() {
        let core = make_core();
        seed_one_photo(&core);
        let connection_id = connect_peer(&core, "peer-b");
        let commands = take_commands(&core);
        let initial_announce = decode_message(&commands[1]);
        let initial_sync_id = match initial_announce {
            WireMessage::RootAnnounce { sync_id, .. } => sync_id,
            other => panic!("unexpected wire message {other:?}"),
        };

        core.on_ui_action(UiAction::TakePhotoRequested).expect("take photo");
        let _ = take_commands(&core);
        core.on_android_event(AndroidEvent::CameraPermissionGranted)
            .expect("camera permission");
        let commands = take_commands(&core);
        let output_path = match &commands[0] {
            AndroidCommand::StartCameraPreview { output_path } => output_path.clone(),
            other => panic!("unexpected command {other:?}"),
        };
        core.on_ui_action(UiAction::CapturePhotoRequested)
            .expect("capture photo");
        let _ = take_commands(&core);
        std::fs::write(&output_path, b"queued-root").expect("write photo");
        core.on_android_event(AndroidEvent::CameraCaptureSaved { output_path })
            .expect("capture saved");
        let commands = take_commands(&core);
        assert_eq!(commands.len(), 1);
        assert!(matches!(commands[0], AndroidCommand::StopCameraPreview));

        core.on_android_event(AndroidEvent::SocketRead {
            connection_id,
            bytes: encode_frame(&WireMessage::SyncApplied {
                feed_root_nhash: "nhash1bogus".to_string(),
                added_entries: 0,
                sync_id: initial_sync_id,
            })
            .expect("frame"),
        })
        .expect("sync applied");
        let commands = take_commands(&core);
        assert!(commands
            .iter()
            .any(|command| matches!(command, AndroidCommand::WriteSocketBytes { .. })));
    }

    #[test]
    fn socket_close_schedules_peer_reconnect_instead_of_global_restart() {
        let core = make_core();
        seed_one_photo(&core);
        let connection_id = connect_peer(&core, "peer-b");
        let _ = take_commands(&core);
        core.on_android_event(AndroidEvent::SocketClosed { connection_id })
            .expect("socket closed");
        let commands = take_commands(&core);
        assert!(commands.iter().any(|command| matches!(
            command,
            AndroidCommand::ScheduleReconnect { peer_instance, .. } if peer_instance == "peer-b"
        )));
        assert!(!commands
            .iter()
            .any(|command| matches!(command, AndroidCommand::StopAware)));
    }

    #[test]
    fn reconnect_peer_clears_stale_unbound_connection_and_resends_hello() {
        let core = make_core();
        core.on_ui_action(UiAction::ToggleNearbyRequested)
            .expect("toggle nearby");
        let _ = take_commands(&core);
        core.on_android_event(AndroidEvent::NearbyPermissionGranted)
            .expect("nearby permission");
        let _ = take_commands(&core);
        core.on_android_event(AndroidEvent::AwareAttachSucceeded)
            .expect("attach");
        let _ = take_commands(&core);
        core.on_android_event(AndroidEvent::PeerDiscovered {
            handle_id: 41,
            instance: Some("peer-b".to_string()),
        })
        .expect("peer discovered");
        let _ = take_commands(&core);

        core.on_android_event(AndroidEvent::DiscoveryMessageReceived {
            channel: DiscoveryChannel::Subscribe,
            handle_id: 41,
            payload: "ready:peer-b".to_string(),
        })
        .expect("ready");
        let commands = take_commands(&core);
        let connection_id = match commands[0] {
            AndroidCommand::OpenInitiator { connection_id, .. } => connection_id,
            ref other => panic!("unexpected command {other:?}"),
        };

        core.on_android_event(AndroidEvent::ReconnectPeer {
            peer_instance: "peer-b".to_string(),
        })
        .expect("reconnect peer");
        let commands = take_commands(&core);
        assert!(commands.iter().any(|command| matches!(
            command,
            AndroidCommand::SendDiscoveryMessage { payload, .. } if payload == "hello:peer-a"
        )));

        let state = core.lock_state().expect("state");
        let peer = state.peers.get("peer-b").expect("peer");
        assert_ne!(peer.connection_id, Some(connection_id));
    }

    #[test]
    fn discovery_message_failure_schedules_retry_for_unlinked_peer() {
        let core = make_core();
        core.on_ui_action(UiAction::ToggleNearbyRequested)
            .expect("toggle nearby");
        let _ = take_commands(&core);
        core.on_android_event(AndroidEvent::NearbyPermissionGranted)
            .expect("nearby permission");
        let _ = take_commands(&core);
        core.on_android_event(AndroidEvent::AwareAttachSucceeded)
            .expect("attach");
        let _ = take_commands(&core);
        core.on_android_event(AndroidEvent::PeerDiscovered {
            handle_id: 41,
            instance: Some("peer-b".to_string()),
        })
        .expect("peer discovered");
        let _ = take_commands(&core);

        core.on_android_event(AndroidEvent::DiscoveryMessageFailed { message_id: 1 })
            .expect("discovery message failed");
        let commands = take_commands(&core);
        assert!(commands.iter().any(|command| matches!(
            command,
            AndroidCommand::ScheduleReconnect { peer_instance, .. } if peer_instance == "peer-b"
        )));
    }

    #[test]
    fn clear_data_resets_feed() {
        let core = make_core();
        seed_one_photo(&core);
        let view = core.current_view_state().expect("view");
        assert_eq!(view.feed_items.len(), 1);

        core.on_ui_action(UiAction::ClearDemoDataRequested)
            .expect("clear data");
        let _ = take_commands(&core);
        let view = core.current_view_state().expect("view after clear");
        assert!(view.feed_items.is_empty());
    }
}
