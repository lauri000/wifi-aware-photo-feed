use std::collections::{HashMap, VecDeque};
use std::path::PathBuf;
use std::sync::Mutex;

use crate::photo_store::PhotoStore;
use crate::protocol::{encode_frame, FrameDecoder, WireMessage, WirePhoto};
use crate::types::{
    AndroidCommand, AndroidEvent, ControlsEnabled, DiscoveryChannel, SocketSide, StoredPhoto,
    UiAction, UiPage, ViewState,
};
use crate::NearbyHashtreeError;

const SERVICE_NAME: &str = "_nostrwifiaware._tcp";
const SECURE_PASSPHRASE: &str = "awarebenchpass123";
const TCP_PROTOCOL: i32 = 6;
const ALBUM_LABEL: &str = "Local Instagram Feed";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Role {
    Idle,
    Peer,
}

#[derive(Debug, Clone)]
struct OutboundTransfer {
    label: String,
    photos: VecDeque<StoredPhoto>,
    total_count: usize,
    waiting_for_ack: bool,
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
    pending_broadcast: bool,
}

impl PeerState {
    fn new(_instance: String) -> Self {
        Self {
            publish_handle_id: None,
            subscribe_handle_id: None,
            connection_id: None,
            connected_side: None,
            initiator_requested: false,
            socket_connect_requested: false,
            hello_sent: false,
            pending_broadcast: false,
        }
    }
}

#[derive(Debug, Clone)]
struct ConnectionState {
    peer_instance: String,
    side: Option<SocketSide>,
    active_feed_label: String,
    outbound_transfer: Option<OutboundTransfer>,
}

#[derive(Debug)]
struct CoreState {
    app_instance: String,
    photo_store: PhotoStore,
    page: UiPage,
    role: Role,
    status_text: String,
    link_text: String,
    pending_start_nearby: bool,
    pending_commands: VecDeque<AndroidCommand>,
    log_lines: VecDeque<String>,
    next_message_id: i64,
    next_connection_id: i64,
    peers: HashMap<String, PeerState>,
    publish_handle_to_instance: HashMap<i64, String>,
    subscribe_handle_to_instance: HashMap<i64, String>,
    connections: HashMap<i64, ConnectionState>,
    inbound_decoders: HashMap<i64, FrameDecoder>,
    broadcast_mode: bool,
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
        app_instance: String,
    ) -> Result<Self, NearbyHashtreeError> {
        let photo_store = PhotoStore::new(PathBuf::from(app_files_dir))?;
        let mut state = CoreState {
            app_instance,
            photo_store,
            page: UiPage::Feed,
            role: Role::Idle,
            status_text: "Nearby off".to_string(),
            link_text: "Idle".to_string(),
            pending_start_nearby: false,
            pending_commands: VecDeque::new(),
            log_lines: VecDeque::new(),
            next_message_id: 1,
            next_connection_id: 1,
            peers: HashMap::new(),
            publish_handle_to_instance: HashMap::new(),
            subscribe_handle_to_instance: HashMap::new(),
            connections: HashMap::new(),
            inbound_decoders: HashMap::new(),
            broadcast_mode: false,
        };
        state.log("Local Instagram loaded. Take photos, keep them in app-private nhash-addressed storage, and broadcast them to nearby phones over Wi-Fi Aware.");
        Ok(Self {
            state: Mutex::new(state),
        })
    }

    pub fn on_ui_action(
        &self,
        action: UiAction,
    ) -> Result<(), NearbyHashtreeError> {
        let mut state = self.lock_state()?;
        state.on_ui_action(action)?;
        Ok(())
    }

    pub fn on_android_event(
        &self,
        event: AndroidEvent,
    ) -> Result<(), NearbyHashtreeError> {
        let mut state = self.lock_state()?;
        state.on_android_event(event)?;
        Ok(())
    }

    pub fn take_pending_commands(&self) -> Result<Vec<AndroidCommand>, NearbyHashtreeError> {
        let mut state = self.lock_state()?;
        Ok(state.pending_commands.drain(..).collect())
    }

    pub fn current_view_state(&self) -> Result<ViewState, NearbyHashtreeError> {
        let state = self.lock_state()?;
        Ok(state.view_state())
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
                if self.any_transfer_in_flight() {
                    self.log("Wait for the current nearby transfer to finish before taking another photo.");
                    return Ok(());
                }
                let output_path = self.photo_store.create_capture_temp_path()?;
                self.queue(AndroidCommand::LaunchCameraCapture { output_path });
            }
            UiAction::StartNearbyRequested => {
                if self.role != Role::Idle {
                    self.log("Nearby mode is already running.");
                    return Ok(());
                }
                self.pending_start_nearby = true;
                self.status_text = "Starting nearby".to_string();
                self.link_text = "Requesting permissions".to_string();
                self.log(&format!("Attaching as Nearby ({})", self.app_instance));
                self.queue(AndroidCommand::RequestPermissions);
            }
            UiAction::StopRequested => {
                self.stop("Stopped nearby mode");
            }
            UiAction::ShareAvailablePhotosRequested => {
                self.start_or_arm_broadcast("broadcast button")?;
            }
            UiAction::ClearDemoDataRequested => {
                if self.role != Role::Idle {
                    self.stop("Stopped nearby mode for demo data clear");
                }
                self.photo_store.clear_all()?;
                self.broadcast_mode = false;
                self.log("Cleared all local and nearby photos.");
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
            AndroidEvent::PermissionsGranted => {
                self.log("Permissions granted.");
                if self.pending_start_nearby {
                    self.queue(AndroidCommand::StartAwareAttach);
                }
            }
            AndroidEvent::PermissionsDenied => {
                self.pending_start_nearby = false;
                self.role = Role::Idle;
                self.status_text = "Permission denied".to_string();
                self.link_text = "Idle".to_string();
                self.log("Missing runtime permission. Wi-Fi Aware cannot start.");
            }
            AndroidEvent::CameraCaptureCompleted { temp_path } => {
                let photo = self.photo_store.finalize_captured_photo(&temp_path)?;
                self.page = UiPage::Feed;
                self.log(&format!(
                    "Captured photo {} nhash={} size={}",
                    photo.id,
                    photo.nhash,
                    format_byte_count(photo.size_bytes)
                ));
                if self.broadcast_mode && self.role == Role::Peer {
                    self.broadcast_to_all_known_peers("new photo captured")?;
                }
            }
            AndroidEvent::CameraCaptureCancelled => {
                self.log("Photo capture canceled.");
            }
            AndroidEvent::AwareAttachSucceeded => {
                self.pending_start_nearby = false;
                self.role = Role::Peer;
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
                self.role = Role::Idle;
                self.status_text = "Attach failed".to_string();
                self.link_text = "Attach failed".to_string();
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
                self.refresh_link_text();
            }
            AndroidEvent::SubscribeTerminated => {
                self.log("Nearby subscribe session terminated.");
                self.refresh_link_text();
            }
            AndroidEvent::PeerDiscovered { handle_id, instance } => {
                let Some(instance) = instance else {
                    self.log(&format!(
                        "Discovered {}, but it did not advertise an app instance.",
                        peer_label(handle_id)
                    ));
                    return Ok(());
                };
                let is_new = !self.peers.contains_key(&instance);
                self.remember_peer_handle(instance.clone(), handle_id, false);
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
                if let Some(remote_instance) = payload.strip_prefix("hello:") {
                    self.remember_peer_handle(remote_instance.to_string(), handle_id, channel == DiscoveryChannel::Publish);
                    self.handle_responder_hello(remote_instance.to_string(), handle_id)?;
                } else if let Some(remote_instance) = payload.strip_prefix("ready:") {
                    self.remember_peer_handle(remote_instance.to_string(), handle_id, channel == DiscoveryChannel::Subscribe);
                    self.handle_initiator_ready(remote_instance.to_string(), handle_id)?;
                }
            }
            AndroidEvent::DiscoveryMessageSent { message_id } => {
                self.log(&format!("Nearby discovery sent message #{message_id}"));
            }
            AndroidEvent::DiscoveryMessageFailed { message_id } => {
                self.log(&format!("Nearby discovery failed to send message #{message_id}"));
            }
            AndroidEvent::ResponderNetworkAvailable { connection_id } => {
                self.log(&format!(
                    "Responder Wi-Fi Aware data path available for connection #{connection_id}."
                ));
                self.refresh_link_text();
            }
            AndroidEvent::ResponderNetworkLost { connection_id } => {
                self.log(&format!(
                    "Responder Wi-Fi Aware data path lost for connection #{connection_id}."
                ));
                self.queue(AndroidCommand::CloseSocket { connection_id });
                self.refresh_link_text();
            }
            AndroidEvent::InitiatorNetworkAvailable { connection_id } => {
                self.log(&format!(
                    "Initiator Wi-Fi Aware data path available for connection #{connection_id}."
                ));
                self.refresh_link_text();
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
                self.refresh_link_text();
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
                }
                self.inbound_decoders.entry(connection_id).or_default();
                self.log(&format!(
                    "{} Wi-Fi Aware TCP socket connected to {}.",
                    socket_side_label(&side),
                    peer_instance
                ));
                self.refresh_link_text();
                self.maybe_start_broadcast_for_peer(&peer_instance, "peer socket connected")?;
            }
            AndroidEvent::SocketClosed { connection_id } => {
                self.inbound_decoders.remove(&connection_id);
                if let Some(connection) = self.connections.remove(&connection_id) {
                    if let Some(peer) = self.peers.get_mut(&connection.peer_instance) {
                        if peer.connection_id == Some(connection_id) {
                            peer.connection_id = None;
                            peer.connected_side = None;
                            peer.initiator_requested = false;
                            peer.socket_connect_requested = false;
                            peer.hello_sent = false;
                        }
                    }
                    self.log(&format!(
                        "Peer photo stream closed for {}.",
                        connection.peer_instance
                    ));
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
                self.log(&format!("Socket {connection_id} error: {message}"));
                self.queue(AndroidCommand::CloseSocket { connection_id });
                self.refresh_link_text();
            }
        }
        Ok(())
    }

    fn view_state(&self) -> ViewState {
        let local_count = self.photo_store.local_count();
        let nearby_count = self.photo_store.received_count();
        let storage_bytes = self.photo_store.total_storage_bytes();
        let connected_peers = self.connected_peer_count();
        let any_transfer = self.any_transfer_in_flight();
        let has_shareable = !self.photo_store.shareable_photos().is_empty();

        ViewState {
            page: self.page.clone(),
            status_text: format!("STATUS  {}", self.status_text),
            mode_text: format!(
                "MODE  {}",
                if self.broadcast_mode {
                    "Broadcasting"
                } else {
                    self.role_label()
                }
            ),
            link_text: format!(
                "LINK  {} nearby · {} linked",
                self.peers.len(),
                connected_peers
            ),
            storage_text: format!("STORAGE  {}", format_byte_count(storage_bytes)),
            local_summary_text: format!("Your photos: {}  ·  Taken on this phone", local_count),
            nearby_summary_text: format!(
                "Received nearby: {}  ·  {}",
                nearby_count,
                format_byte_count(storage_bytes)
            ),
            controls_enabled: ControlsEnabled {
                take_photo: !any_transfer,
                start_nearby: self.role == Role::Idle,
                stop: self.role != Role::Idle,
                share_available_photos: self.role == Role::Peer && has_shareable,
                clear_demo_data: !any_transfer,
                clear_log: true,
            },
            feed_items: self.photo_store.feed_items(),
            log_lines: self.log_lines.iter().cloned().collect(),
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

    fn any_transfer_in_flight(&self) -> bool {
        self.connections
            .values()
            .any(|connection| connection.outbound_transfer.is_some())
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
                if self.broadcast_mode {
                    format!("{discovered} nearby · {connected} linked · broadcast on")
                } else {
                    format!("{discovered} nearby · {connected} linked")
                }
            }
        };
    }

    fn remember_peer_handle(
        &mut self,
        instance: String,
        handle_id: i64,
        is_publish_handle: bool,
    ) {
        let peer = self
            .peers
            .entry(instance.clone())
            .or_insert_with(|| PeerState::new(instance.clone()));
        if is_publish_handle {
            peer.publish_handle_id = Some(handle_id);
            self.publish_handle_to_instance.insert(handle_id, instance);
        } else {
            peer.subscribe_handle_id = Some(handle_id);
            self.subscribe_handle_to_instance.insert(handle_id, instance);
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
        let (
            should_initiate,
            handle_id,
            already_connected,
            initiator_requested,
            hello_sent,
        ) = match self.peers.get(peer_instance) {
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

    fn handle_responder_hello(
        &mut self,
        peer_instance: String,
        handle_id: i64,
    ) -> Result<(), NearbyHashtreeError> {
        let already_connected = self
            .peers
            .get(&peer_instance)
            .map(|peer| peer.connection_id.is_some())
            .unwrap_or(false);
        if already_connected {
            self.log(&format!(
                "Already paired with {}. Ignoring duplicate hello.",
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
                active_feed_label: ALBUM_LABEL.to_string(),
                outbound_transfer: None,
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
                active_feed_label: ALBUM_LABEL.to_string(),
                outbound_transfer: None,
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

    fn start_or_arm_broadcast(
        &mut self,
        trigger: &str,
    ) -> Result<(), NearbyHashtreeError> {
        if self.role != Role::Peer {
            self.log("Start Nearby first.");
            return Ok(());
        }

        if self.photo_store.shareable_photos().is_empty() {
            self.log("Take a photo first, then broadcast it.");
            return Ok(());
        }

        self.broadcast_mode = true;
        self.refresh_link_text();
        self.log(&format!(
            "Broadcast mode armed from {}. Nearby phones will accept and deduplicate automatically.",
            trigger
        ));
        self.broadcast_to_all_known_peers(trigger)
    }

    fn broadcast_to_all_known_peers(
        &mut self,
        trigger: &str,
    ) -> Result<(), NearbyHashtreeError> {
        let peer_instances: Vec<String> = self.peers.keys().cloned().collect();
        if peer_instances.is_empty() {
            self.log("No nearby peers discovered yet. Broadcast will start as peers appear.");
            return Ok(());
        }

        let mut started_count = 0usize;
        for peer_instance in peer_instances {
            if let Some(peer) = self.peers.get_mut(&peer_instance) {
                peer.pending_broadcast = true;
            }
            started_count += self.maybe_start_broadcast_for_peer(&peer_instance, trigger)? as usize;
            self.ensure_data_path_for_peer(&peer_instance);
        }

        if started_count > 0 {
            self.log(&format!(
                "Started broadcast to {} nearby {}.",
                started_count,
                if started_count == 1 { "phone" } else { "phones" }
            ));
        } else {
            self.log("Broadcast is armed. Nearby peers will receive photos as each Wi-Fi Aware socket connects.");
        }
        Ok(())
    }

    fn maybe_start_broadcast_for_peer(
        &mut self,
        peer_instance: &str,
        trigger: &str,
    ) -> Result<bool, NearbyHashtreeError> {
        let should_broadcast = self
            .peers
            .get(peer_instance)
            .map(|peer| self.broadcast_mode || peer.pending_broadcast)
            .unwrap_or(false);
        if !should_broadcast {
            return Ok(false);
        }

        let connection_id = self
            .peers
            .get(peer_instance)
            .and_then(|peer| peer.connection_id)
            .filter(|connection_id| {
                self.connections
                    .get(connection_id)
                    .and_then(|connection| connection.side.clone())
                    .is_some()
            });
        let Some(connection_id) = connection_id else {
            return Ok(false);
        };

        if self
            .connections
            .get(&connection_id)
            .and_then(|connection| connection.outbound_transfer.as_ref())
            .is_some()
        {
            return Ok(false);
        }

        self.begin_outbound_transfer_for_connection(connection_id, trigger)?;
        Ok(true)
    }

    fn begin_outbound_transfer_for_connection(
        &mut self,
        connection_id: i64,
        trigger: &str,
    ) -> Result<(), NearbyHashtreeError> {
        let photos = self.photo_store.shareable_photos();
        if photos.is_empty() {
            self.log("Take a photo first, then broadcast it.");
            return Ok(());
        }

        let Some(connection) = self.connections.get_mut(&connection_id) else {
            return Ok(());
        };
        if connection.outbound_transfer.is_some() {
            return Ok(());
        }
        let peer_instance = connection.peer_instance.clone();
        connection.outbound_transfer = Some(OutboundTransfer {
            label: ALBUM_LABEL.to_string(),
            total_count: photos.len(),
            photos: VecDeque::from(photos),
            waiting_for_ack: false,
        });
        let total_count = connection
            .outbound_transfer
            .as_ref()
            .map(|transfer| transfer.total_count)
            .unwrap_or(0);
        let Some(peer) = self.peers.get_mut(&peer_instance) else {
            return Ok(());
        };
        peer.pending_broadcast = false;

        self.log(&format!(
            "Sending {} photos to {} over Wi-Fi Aware ({}).",
            total_count,
            peer_instance,
            trigger
        ));
        self.queue(AndroidCommand::WriteSocketBytes {
            connection_id,
            bytes: encode_frame(&WireMessage::Set {
                label: ALBUM_LABEL.to_string(),
                count: total_count as u32,
            })?,
        });
        self.send_next_outbound_photo(connection_id)?;
        Ok(())
    }

    fn send_next_outbound_photo(
        &mut self,
        connection_id: i64,
    ) -> Result<(), NearbyHashtreeError> {
        let (peer_instance, maybe_photo, total_count, label) = {
            let Some(connection) = self.connections.get_mut(&connection_id) else {
                return Ok(());
            };
            let Some(transfer) = connection.outbound_transfer.as_mut() else {
                return Ok(());
            };
            if transfer.waiting_for_ack {
                return Ok(());
            }
            let peer_instance = connection.peer_instance.clone();
            let total_count = transfer.total_count;
            let label = transfer.label.clone();
            let next_photo = transfer.photos.pop_front();
            if next_photo.is_some() {
                transfer.waiting_for_ack = true;
            }
            (peer_instance, next_photo, total_count, label)
        };

        let Some(photo) = maybe_photo else {
            self.queue(AndroidCommand::WriteSocketBytes {
                connection_id,
                bytes: encode_frame(&WireMessage::Done {
                    label: label.clone(),
                    count: total_count as u32,
                })?,
            });
            if let Some(connection) = self.connections.get_mut(&connection_id) {
                connection.outbound_transfer = None;
            }
            self.log(&format!(
                "Finished sending the current photo collection to {}.",
                peer_instance
            ));
            return Ok(());
        };

        let bytes = std::fs::read(self.photo_store.resolve_photo_path(&photo))
            .map_err(|e| NearbyHashtreeError::Message(format!("failed to read photo bytes: {e}")))?;
        let photo_id = photo.id.clone();
        let photo_nhash = photo.nhash.clone();
        let photo_size_bytes = photo.size_bytes;
        let wire_photo = WirePhoto {
            id: photo.id,
            source_label: photo.source_label,
            created_at_ms: photo.created_at_ms,
            announced_nhash: photo.nhash,
            mime_type: photo.mime_type,
            size_bytes: photo.size_bytes,
            bytes,
        };
        self.log(&format!(
            "Sending {} nhash={} size={} to {} over Wi-Fi Aware",
            photo_id,
            photo_nhash,
            format_byte_count(photo_size_bytes),
            peer_instance
        ));
        self.queue(AndroidCommand::WriteSocketBytes {
            connection_id,
            bytes: encode_frame(&WireMessage::Photo {
                photo: wire_photo,
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
            WireMessage::Set { label, count } => {
                if let Some(connection) = self.connections.get_mut(&connection_id) {
                    connection.active_feed_label = label.clone();
                }
                self.log(&format!(
                    "Receiving {} photos from {} inside {}.",
                    count, peer_instance, label
                ));
            }
            WireMessage::Photo { photo } => {
                let active_feed_label = self
                    .connections
                    .get(&connection_id)
                    .map(|connection| connection.active_feed_label.clone())
                    .unwrap_or_else(|| ALBUM_LABEL.to_string());
                self.log(&format!(
                    "Receiving photo {} from {} inside {} nhash={} ({}) over Wi-Fi Aware",
                    photo.id,
                    photo.source_label,
                    active_feed_label,
                    photo.announced_nhash,
                    format_byte_count(photo.size_bytes)
                ));
                let temp_path = self.photo_store.create_incoming_temp_path(&photo.id)?;
                std::fs::write(&temp_path, &photo.bytes).map_err(|e| {
                    NearbyHashtreeError::Message(format!("failed to write incoming temp photo: {e}"))
                })?;
                let result = self.photo_store.verify_and_store_received_photo(
                    &temp_path,
                    &photo.id,
                    photo.created_at_ms,
                    &photo.announced_nhash,
                    &photo.source_label,
                    &photo.mime_type,
                )?;
                self.log(&result.message);
                self.queue(AndroidCommand::WriteSocketBytes {
                    connection_id,
                    bytes: encode_frame(&WireMessage::Ack {
                        success: result.success,
                        actual_nhash: result.actual_nhash.clone(),
                        already_present: result.already_present,
                        message: result.message.clone(),
                    })?,
                });
            }
            WireMessage::Done { label, count } => {
                self.log(&format!(
                    "Finished receiving {} photos from {} inside {}.",
                    count, peer_instance, label
                ));
            }
            WireMessage::Ack {
                success,
                actual_nhash,
                already_present,
                message,
            } => {
                self.log(&format!(
                    "Peer {} replied: success={} actualNhash={} alreadyPresent={}",
                    peer_instance,
                    success,
                    actual_nhash.clone().unwrap_or_else(|| "n/a".to_string()),
                    already_present
                ));
                self.log(&message);
                if let Some(connection) = self.connections.get_mut(&connection_id) {
                    if let Some(transfer) = connection.outbound_transfer.as_mut() {
                        transfer.waiting_for_ack = false;
                    }
                }
                if !success {
                    self.log(&format!(
                        "Stopping photo transfer to {} because the peer rejected a photo.",
                        peer_instance
                    ));
                    if let Some(connection) = self.connections.get_mut(&connection_id) {
                        connection.outbound_transfer = None;
                    }
                    return Ok(());
                }
                self.send_next_outbound_photo(connection_id)?;
            }
        }
        Ok(())
    }

    fn stop(
        &mut self,
        reason: &str,
    ) {
        self.pending_start_nearby = false;
        self.broadcast_mode = false;
        self.peers.clear();
        self.publish_handle_to_instance.clear();
        self.subscribe_handle_to_instance.clear();
        self.connections.clear();
        self.inbound_decoders.clear();
        self.role = Role::Idle;
        self.status_text = "Nearby off".to_string();
        self.link_text = "Idle".to_string();
        self.queue(AndroidCommand::StopAware);
        self.log(reason);
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

#[cfg(test)]
mod tests {
    use super::{AppCore, SERVICE_NAME};
    use crate::types::{AndroidCommand, AndroidEvent, UiAction};

    #[test]
    fn start_nearby_requests_permissions() {
        let dir = tempfile::tempdir().expect("tempdir");
        let core = AppCore::new(dir.path().display().to_string(), "phone-a".to_string()).expect("core");
        core.on_ui_action(UiAction::StartNearbyRequested).expect("start nearby");
        let commands = core.take_pending_commands().expect("commands");
        assert!(matches!(commands.as_slice(), [AndroidCommand::RequestPermissions]));
    }

    #[test]
    fn permissions_granted_lead_to_attach_then_publish_subscribe() {
        let dir = tempfile::tempdir().expect("tempdir");
        let core = AppCore::new(dir.path().display().to_string(), "phone-a".to_string()).expect("core");
        core.on_ui_action(UiAction::StartNearbyRequested).expect("start nearby");
        let _ = core.take_pending_commands().expect("initial commands");
        core.on_android_event(AndroidEvent::PermissionsGranted)
            .expect("permissions granted");
        let commands = core.take_pending_commands().expect("attach commands");
        assert!(matches!(commands.as_slice(), [AndroidCommand::StartAwareAttach]));
        core.on_android_event(AndroidEvent::AwareAttachSucceeded)
            .expect("attach success");
        let commands = core.take_pending_commands().expect("publish subscribe");
        assert_eq!(commands.len(), 2);
        assert!(matches!(
            &commands[0],
            AndroidCommand::StartPublish { service_name, .. } if service_name == SERVICE_NAME
        ));
        assert!(matches!(
            &commands[1],
            AndroidCommand::StartSubscribe { service_name } if service_name == SERVICE_NAME
        ));
    }

    #[test]
    fn clear_data_resets_feed() {
        let dir = tempfile::tempdir().expect("tempdir");
        let core = AppCore::new(dir.path().display().to_string(), "phone-a".to_string()).expect("core");
        let temp = dir.path().join("capture.jpg");
        std::fs::write(&temp, b"nearby-photo").expect("write temp");
        core.on_android_event(AndroidEvent::CameraCaptureCompleted {
            temp_path: temp.display().to_string(),
        })
        .expect("capture completed");
        let view = core.current_view_state().expect("view");
        assert_eq!(view.feed_items.len(), 1);

        core.on_ui_action(UiAction::ClearDemoDataRequested)
            .expect("clear data");
        let view = core.current_view_state().expect("view after clear");
        assert!(view.feed_items.is_empty());
    }

    #[test]
    fn broadcast_to_connected_peer_queues_set_and_photo_frames() {
        let dir = tempfile::tempdir().expect("tempdir");
        let core = AppCore::new(dir.path().display().to_string(), "phone-a".to_string()).expect("core");
        let temp = dir.path().join("capture.jpg");
        std::fs::write(&temp, b"nearby-photo").expect("write temp");
        core.on_android_event(AndroidEvent::CameraCaptureCompleted {
            temp_path: temp.display().to_string(),
        })
        .expect("capture completed");
        core.on_android_event(AndroidEvent::AwareAttachSucceeded)
            .expect("attach success");
        let _ = core.take_pending_commands().expect("attach commands");

        core.on_android_event(AndroidEvent::PeerDiscovered {
            handle_id: 1,
            instance: Some("phone-b".to_string()),
        })
        .expect("peer discovered");
        let _ = core.take_pending_commands().expect("hello command");
        core.on_android_event(AndroidEvent::DiscoveryMessageReceived {
            channel: crate::types::DiscoveryChannel::Subscribe,
            handle_id: 1,
            payload: "ready:phone-b".to_string(),
        })
        .expect("ready");
        let commands = core.take_pending_commands().expect("open initiator");
        let connection_id =
            match commands.as_slice() {
                [AndroidCommand::OpenInitiator { connection_id, .. }] => *connection_id,
                other => panic!("unexpected commands: {other:?}"),
            };

        core.on_android_event(AndroidEvent::SocketConnected {
            connection_id,
            side: crate::types::SocketSide::Initiator,
        })
        .expect("socket connected");
        let _ = core.take_pending_commands().expect("post connect");

        core.on_ui_action(UiAction::ShareAvailablePhotosRequested)
            .expect("broadcast");
        let commands = core.take_pending_commands().expect("broadcast commands");
        assert_eq!(commands.len(), 2);
        assert!(matches!(commands[0], AndroidCommand::WriteSocketBytes { .. }));
        assert!(matches!(commands[1], AndroidCommand::WriteSocketBytes { .. }));
    }

    #[test]
    fn broadcast_arms_before_socket_connect_and_starts_on_connect() {
        let dir = tempfile::tempdir().expect("tempdir");
        let core = AppCore::new(dir.path().display().to_string(), "phone-a".to_string()).expect("core");
        let temp = dir.path().join("capture.jpg");
        std::fs::write(&temp, b"nearby-photo").expect("write temp");
        core.on_android_event(AndroidEvent::CameraCaptureCompleted {
            temp_path: temp.display().to_string(),
        })
        .expect("capture completed");
        core.on_android_event(AndroidEvent::AwareAttachSucceeded)
            .expect("attach success");
        let _ = core.take_pending_commands().expect("attach commands");

        core.on_android_event(AndroidEvent::PeerDiscovered {
            handle_id: 1,
            instance: Some("phone-b".to_string()),
        })
        .expect("peer discovered");
        let hello = core.take_pending_commands().expect("hello");
        assert!(matches!(
            hello.as_slice(),
            [AndroidCommand::SendDiscoveryMessage { .. }]
        ));

        core.on_ui_action(UiAction::ShareAvailablePhotosRequested)
            .expect("broadcast");
        let commands = core.take_pending_commands().expect("armed broadcast");
        assert!(commands.is_empty());

        core.on_android_event(AndroidEvent::DiscoveryMessageReceived {
            channel: crate::types::DiscoveryChannel::Subscribe,
            handle_id: 1,
            payload: "ready:phone-b".to_string(),
        })
        .expect("ready");
        let commands = core.take_pending_commands().expect("open initiator");
        let connection_id =
            match commands.as_slice() {
                [AndroidCommand::OpenInitiator { connection_id, .. }] => *connection_id,
                other => panic!("unexpected commands: {other:?}"),
            };

        core.on_android_event(AndroidEvent::SocketConnected {
            connection_id,
            side: crate::types::SocketSide::Initiator,
        })
        .expect("socket connected");
        let commands = core.take_pending_commands().expect("broadcast after connect");
        assert_eq!(commands.len(), 2);
        assert!(matches!(commands[0], AndroidCommand::WriteSocketBytes { .. }));
        assert!(matches!(commands[1], AndroidCommand::WriteSocketBytes { .. }));
    }

    #[test]
    fn initiator_capabilities_do_not_reconnect_after_socket_connect() {
        let dir = tempfile::tempdir().expect("tempdir");
        let core = AppCore::new(dir.path().display().to_string(), "phone-a".to_string()).expect("core");

        core.on_android_event(AndroidEvent::AwareAttachSucceeded)
            .expect("attach success");
        let _ = core.take_pending_commands().expect("attach commands");

        core.on_android_event(AndroidEvent::PeerDiscovered {
            handle_id: 1,
            instance: Some("phone-b".to_string()),
        })
        .expect("peer discovered");
        let _ = core.take_pending_commands().expect("hello");

        core.on_android_event(AndroidEvent::DiscoveryMessageReceived {
            channel: crate::types::DiscoveryChannel::Subscribe,
            handle_id: 1,
            payload: "ready:phone-b".to_string(),
        })
        .expect("ready");
        let commands = core.take_pending_commands().expect("open initiator");
        let connection_id = match commands.as_slice() {
            [AndroidCommand::OpenInitiator { connection_id, .. }] => *connection_id,
            other => panic!("unexpected commands: {other:?}"),
        };

        core.on_android_event(AndroidEvent::InitiatorCapabilities {
            connection_id,
            port: 42801,
            ipv6: Some("fe80::1".to_string()),
        })
        .expect("capabilities before connect");
        let commands = core.take_pending_commands().expect("connect command");
        assert!(matches!(
            commands.as_slice(),
            [AndroidCommand::ConnectInitiatorSocket { connection_id: id, .. }] if *id == connection_id
        ));

        core.on_android_event(AndroidEvent::SocketConnected {
            connection_id,
            side: crate::types::SocketSide::Initiator,
        })
        .expect("socket connected");
        let _ = core.take_pending_commands().expect("post-connect commands");

        core.on_android_event(AndroidEvent::InitiatorCapabilities {
            connection_id,
            port: 42801,
            ipv6: Some("fe80::1".to_string()),
        })
        .expect("capabilities after connect");
        let commands = core.take_pending_commands().expect("no reconnect command");
        assert!(commands.is_empty(), "expected no reconnect, got {commands:?}");
    }
}
