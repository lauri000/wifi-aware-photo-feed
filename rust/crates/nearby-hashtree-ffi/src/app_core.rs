use std::collections::{HashMap, VecDeque};
use std::path::PathBuf;
use std::sync::Mutex;

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
}

impl PeerState {
    fn new() -> Self {
        Self {
            publish_handle_id: None,
            subscribe_handle_id: None,
            connection_id: None,
            connected_side: None,
            initiator_requested: false,
            socket_connect_requested: false,
            hello_sent: false,
        }
    }
}

#[derive(Debug, Clone)]
struct ConnectionState {
    peer_instance: String,
    side: Option<SocketSide>,
    active_sync_root: Option<String>,
}

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
        };
        state.log(
            "Local Instagram loaded. Photos are persisted as hashtree blocks and synced by root + block hash over Wi-Fi Aware.",
        );
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
                let output_path = self.photo_store.create_capture_temp_path()?;
                self.queue(AndroidCommand::LaunchCameraCapture { output_path });
            }
            UiAction::ToggleNearbyRequested => {
                if self.role == Role::Idle {
                    self.pending_start_nearby = true;
                    self.status_text = "Starting nearby".to_string();
                    self.link_text = "Requesting permissions".to_string();
                    self.log(&format!("Attaching as Nearby ({})", self.app_instance));
                    self.queue(AndroidCommand::RequestPermissions);
                } else {
                    self.stop("Disconnected from nearby mode");
                }
            }
            UiAction::ClearDemoDataRequested => {
                if self.role != Role::Idle {
                    self.stop("Disconnected from nearby mode for data clear");
                }
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
                    "Captured photo {} cid={} size={}. Stored raw JPEG durably first, then ingested into the local hashtree feed.",
                    photo.id,
                    short_cid(&photo.photo_cid),
                    format_byte_count(photo.size_bytes)
                ));
                self.sync_current_root_to_all_known_peers("new photo captured")?;
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
                    self.remember_peer_handle(
                        remote_instance.to_string(),
                        handle_id,
                        channel == DiscoveryChannel::Publish,
                    );
                    self.handle_responder_hello(remote_instance.to_string(), handle_id)?;
                } else if let Some(remote_instance) = payload.strip_prefix("ready:") {
                    self.remember_peer_handle(
                        remote_instance.to_string(),
                        handle_id,
                        channel == DiscoveryChannel::Subscribe,
                    );
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
                }
                self.inbound_decoders.entry(connection_id).or_default();
                self.log(&format!(
                    "{} Wi-Fi Aware TCP socket connected to {}.",
                    socket_side_label(&side),
                    peer_instance
                ));
                self.refresh_link_text();
                self.sync_current_root_to_peer(&peer_instance, "peer socket connected")?;
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
                        }
                    }
                    self.log(&format!(
                        "Peer sync socket closed for {}.",
                        connection.peer_instance
                    ));
                    if self.role == Role::Peer
                        && !self.pending_start_nearby
                        && self.connections.is_empty()
                        && self.connected_peer_count() == 0
                    {
                        self.restart_nearby_stack(&format!(
                            "Nearby link to {} dropped. Re-establishing nearby automatically.",
                            peer_instance
                        ));
                    }
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

    fn view_state(&self) -> Result<ViewState, NearbyHashtreeError> {
        let local_count = self.photo_store.local_count()?;
        let nearby_count = self.photo_store.received_count()?;
        let storage_bytes = self.photo_store.total_storage_bytes()?;
        let connected_peers = self.connected_peer_count();
        let any_transfer = self.any_transfer_in_flight();

        Ok(ViewState {
            page: self.page.clone(),
            status_text: format!("STATUS  {}", self.status_text),
            mode_text: format!(
                "MODE  {}",
                self.role_label()
            ),
            link_text: format!(
                "LINK  {} nearby · {} linked",
                self.peers.len(),
                connected_peers
            ),
            storage_text: format!("STORAGE  {}", format_byte_count(storage_bytes)),
            local_summary_text: format!("Your photos: {}  ·  Stored as local feed blocks", local_count),
            nearby_summary_text: format!(
                "Received nearby: {}  ·  Content-addressed merge",
                nearby_count
            ),
            controls_enabled: ControlsEnabled {
                take_photo: !any_transfer,
                toggle_nearby: !any_transfer && !self.pending_start_nearby,
                clear_demo_data: !any_transfer,
                clear_log: true,
            },
            feed_items: self.photo_store.feed_items()?,
            log_lines: self.log_lines.iter().cloned().collect(),
        })
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
            .any(|connection| connection.active_sync_root.is_some())
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
    ) {
        let peer = self.peers.entry(instance.clone()).or_insert_with(PeerState::new);
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
                "Received duplicate hello from {}. Refreshing the responder data path.",
                peer_instance
            ));
            self.inbound_decoders.remove(&existing_connection_id);
            self.connections.remove(&existing_connection_id);
            self.queue(AndroidCommand::CloseSocket {
                connection_id: existing_connection_id,
            });
        }

        let connection_id = self.next_connection_id;
        self.next_connection_id += 1;
        self.connections.insert(
            connection_id,
            ConnectionState {
                peer_instance: peer_instance.clone(),
                side: None,
                active_sync_root: None,
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
                active_sync_root: None,
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

    fn sync_current_root_to_all_known_peers(
        &mut self,
        trigger: &str,
    ) -> Result<(), NearbyHashtreeError> {
        if self.role != Role::Peer {
            self.log("Nearby mode is off. Local photo was stored, but no peers are connected yet.");
            return Ok(());
        }

        if !self.photo_store.has_any_photos()? {
            self.log("No local photos to sync yet.");
            return Ok(());
        }

        let peer_instances: Vec<String> = self.peers.keys().cloned().collect();
        if peer_instances.is_empty() {
            self.log("No nearby peers discovered yet. The photo stays stored locally until a peer links.");
            return Ok(());
        }

        let mut started_count = 0usize;
        for peer_instance in peer_instances {
            started_count += self.sync_current_root_to_peer(&peer_instance, trigger)? as usize;
            self.ensure_data_path_for_peer(&peer_instance);
        }

        if started_count > 0 {
            self.log(&format!(
                "Started automatic sync to {} nearby {}.",
                started_count,
                if started_count == 1 { "phone" } else { "phones" }
            ));
        }
        Ok(())
    }

    fn sync_current_root_to_peer(
        &mut self,
        peer_instance: &str,
        trigger: &str,
    ) -> Result<bool, NearbyHashtreeError> {
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
            .and_then(|connection| connection.active_sync_root.as_ref())
            .is_some()
        {
            return Ok(false);
        }

        self.send_root_announce_for_connection(connection_id, trigger)?;
        Ok(true)
    }

    fn send_root_announce_for_connection(
        &mut self,
        connection_id: i64,
        trigger: &str,
    ) -> Result<(), NearbyHashtreeError> {
        let Some(root_nhash) = self.photo_store.current_root()? else {
            self.log("No local feed root yet. Take a photo first.");
            return Ok(());
        };
        let block_hashes = self.photo_store.collect_root_hashes(&root_nhash)?;
        let entry_count = self.photo_store.current_entry_count()? as u32;
        let Some(connection) = self.connections.get_mut(&connection_id) else {
            return Ok(());
        };
        connection.active_sync_root = Some(root_nhash.clone());
        let peer_instance = connection.peer_instance.clone();
        self.log(&format!(
            "Syncing feed root {} ({} photos, {} blocks) to {} over Wi-Fi Aware ({})",
            short_cid(&root_nhash),
            entry_count,
            block_hashes.len(),
            peer_instance,
            trigger
        ));
        self.queue(AndroidCommand::WriteSocketBytes {
            connection_id,
            bytes: encode_frame(&WireMessage::RootAnnounce {
                feed_root_nhash: root_nhash,
                block_hashes,
                entry_count,
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
            WireMessage::RootAnnounce {
                feed_root_nhash,
                block_hashes,
                entry_count,
            } => {
                if let Some(connection) = self.connections.get_mut(&connection_id) {
                    connection.active_sync_root = Some(feed_root_nhash.clone());
                }
                self.log(&format!(
                    "Peer {} announced feed root {} ({} photos, {} blocks).",
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
                    })?,
                });
            }
            WireMessage::BlockWant {
                feed_root_nhash,
                missing_hashes,
            } => {
                self.log(&format!(
                    "Peer {} requested {} missing blocks for root {}.",
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
                    })?,
                });
                if let Some(connection) = self.connections.get_mut(&connection_id) {
                    connection.active_sync_root = None;
                }
                self.log(&format!(
                    "Finished pushing root {} to {}.",
                    short_cid(&feed_root_nhash),
                    peer_instance
                ));
            }
            WireMessage::BlockPut { hash_hex, bytes } => {
                match self.photo_store.store_incoming_block(&hash_hex, &bytes) {
                    Ok(()) => {
                        self.log(&format!(
                            "Stored incoming block {} ({}).",
                            short_hash(&hash_hex),
                            format_byte_count(bytes.len() as u64)
                        ));
                    }
                    Err(err) => {
                        self.log(&format!(
                            "Rejected incoming block {} from {}: {}",
                            short_hash(&hash_hex),
                            peer_instance,
                            err
                        ));
                    }
                }
            }
            WireMessage::SyncDone { feed_root_nhash } => {
                match self.photo_store.finalize_remote_sync(&feed_root_nhash) {
                    Ok(merge) => {
                        self.log(&format!(
                            "Verified root {} from {} and merged {} new photos ({} already present).",
                            short_cid(&feed_root_nhash),
                            peer_instance,
                            merge.added_entries,
                            merge.already_present_entries
                        ));
                    }
                    Err(err) => {
                        self.log(&format!(
                            "Root {} from {} did not verify cleanly: {}",
                            short_cid(&feed_root_nhash),
                            peer_instance,
                            err
                        ));
                    }
                }
                if let Some(connection) = self.connections.get_mut(&connection_id) {
                    connection.active_sync_root = None;
                }
            }
        }
        Ok(())
    }

    fn stop(
        &mut self,
        reason: &str,
    ) {
        self.pending_start_nearby = false;
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

    fn restart_nearby_stack(
        &mut self,
        reason: &str,
    ) {
        self.pending_start_nearby = true;
        self.peers.clear();
        self.publish_handle_to_instance.clear();
        self.subscribe_handle_to_instance.clear();
        self.connections.clear();
        self.inbound_decoders.clear();
        self.status_text = "Restarting nearby".to_string();
        self.link_text = "Reconnecting".to_string();
        self.queue(AndroidCommand::StopAware);
        self.queue(AndroidCommand::StartAwareAttach);
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

#[cfg(test)]
mod tests {
    use super::{AppCore, SERVICE_NAME};
    use crate::protocol::{FrameDecoder, WireMessage};
    use crate::types::{AndroidCommand, AndroidEvent, UiAction};

    #[test]
    fn start_nearby_requests_permissions() {
        let dir = tempfile::tempdir().expect("tempdir");
        let files_dir = dir.path().join("files");
        let cache_dir = dir.path().join("cache");
        std::fs::create_dir_all(&files_dir).expect("files dir");
        std::fs::create_dir_all(&cache_dir).expect("cache dir");
        let core = AppCore::new(
            files_dir.display().to_string(),
            cache_dir.display().to_string(),
            "phone-a".to_string(),
        )
        .expect("core");
        core.on_ui_action(UiAction::ToggleNearbyRequested).expect("start nearby");
        let commands = core.take_pending_commands().expect("commands");
        assert!(matches!(commands.as_slice(), [AndroidCommand::RequestPermissions]));
    }

    #[test]
    fn permissions_granted_lead_to_attach_then_publish_subscribe() {
        let dir = tempfile::tempdir().expect("tempdir");
        let files_dir = dir.path().join("files");
        let cache_dir = dir.path().join("cache");
        std::fs::create_dir_all(&files_dir).expect("files dir");
        std::fs::create_dir_all(&cache_dir).expect("cache dir");
        let core = AppCore::new(
            files_dir.display().to_string(),
            cache_dir.display().to_string(),
            "phone-a".to_string(),
        )
        .expect("core");
        core.on_ui_action(UiAction::ToggleNearbyRequested).expect("start nearby");
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
        let files_dir = dir.path().join("files");
        let cache_dir = dir.path().join("cache");
        std::fs::create_dir_all(&files_dir).expect("files dir");
        std::fs::create_dir_all(&cache_dir).expect("cache dir");
        let core = AppCore::new(
            files_dir.display().to_string(),
            cache_dir.display().to_string(),
            "phone-a".to_string(),
        )
        .expect("core");
        let temp = cache_dir.join("capture.jpg");
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
    fn connected_peer_auto_syncs_current_root() {
        let dir = tempfile::tempdir().expect("tempdir");
        let files_dir = dir.path().join("files");
        let cache_dir = dir.path().join("cache");
        std::fs::create_dir_all(&files_dir).expect("files dir");
        std::fs::create_dir_all(&cache_dir).expect("cache dir");
        let core = AppCore::new(
            files_dir.display().to_string(),
            cache_dir.display().to_string(),
            "phone-a".to_string(),
        )
        .expect("core");
        let temp = cache_dir.join("capture.jpg");
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
        let connection_id = match commands.as_slice() {
            [AndroidCommand::OpenInitiator { connection_id, .. }] => *connection_id,
            other => panic!("unexpected commands: {other:?}"),
        };

        core.on_android_event(AndroidEvent::SocketConnected {
            connection_id,
            side: crate::types::SocketSide::Initiator,
        })
        .expect("socket connected");
        let commands = core.take_pending_commands().expect("post connect");
        assert_eq!(commands.len(), 1);
        let message = match &commands[0] {
            AndroidCommand::WriteSocketBytes { bytes, .. } => {
                let mut decoder = FrameDecoder::default();
                decoder.push(bytes).expect("decode").remove(0)
            }
            other => panic!("unexpected command: {other:?}"),
        };
        assert!(matches!(message, WireMessage::RootAnnounce { .. }));
    }

    #[test]
    fn new_capture_auto_syncs_to_connected_peer() {
        let dir = tempfile::tempdir().expect("tempdir");
        let files_dir = dir.path().join("files");
        let cache_dir = dir.path().join("cache");
        std::fs::create_dir_all(&files_dir).expect("files dir");
        std::fs::create_dir_all(&cache_dir).expect("cache dir");
        let core = AppCore::new(
            files_dir.display().to_string(),
            cache_dir.display().to_string(),
            "phone-a".to_string(),
        )
        .expect("core");

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
        let connection_id = match commands.as_slice() {
            [AndroidCommand::OpenInitiator { connection_id, .. }] => *connection_id,
            other => panic!("unexpected commands: {other:?}"),
        };

        core.on_android_event(AndroidEvent::SocketConnected {
            connection_id,
            side: crate::types::SocketSide::Initiator,
        })
        .expect("socket connected");
        let _ = core.take_pending_commands().expect("initial auto sync with empty store");

        let temp = cache_dir.join("capture.jpg");
        std::fs::write(&temp, b"nearby-photo").expect("write temp");
        core.on_android_event(AndroidEvent::CameraCaptureCompleted {
            temp_path: temp.display().to_string(),
        })
        .expect("capture completed");

        let commands = core.take_pending_commands().expect("capture sync commands");
        assert_eq!(commands.len(), 1);
        let message = match &commands[0] {
            AndroidCommand::WriteSocketBytes { bytes, .. } => {
                let mut decoder = FrameDecoder::default();
                decoder.push(bytes).expect("decode").remove(0)
            }
            other => panic!("unexpected command: {other:?}"),
        };
        assert!(matches!(message, WireMessage::RootAnnounce { .. }));
    }

    #[test]
    fn duplicate_hello_during_responder_setup_is_ignored() {
        let dir = tempfile::tempdir().expect("tempdir");
        let files_dir = dir.path().join("files");
        let cache_dir = dir.path().join("cache");
        std::fs::create_dir_all(&files_dir).expect("files dir");
        std::fs::create_dir_all(&cache_dir).expect("cache dir");
        let core = AppCore::new(
            files_dir.display().to_string(),
            cache_dir.display().to_string(),
            "phone-b".to_string(),
        )
        .expect("core");

        core.on_android_event(AndroidEvent::AwareAttachSucceeded)
            .expect("attach success");
        let _ = core.take_pending_commands().expect("attach commands");

        core.on_android_event(AndroidEvent::DiscoveryMessageReceived {
            channel: crate::types::DiscoveryChannel::Publish,
            handle_id: 1,
            payload: "hello:phone-a".to_string(),
        })
        .expect("first hello");
        let first_commands = core.take_pending_commands().expect("first responder commands");
        let first_connection_id = match first_commands.as_slice() {
            [
                AndroidCommand::OpenResponder { connection_id, .. },
                AndroidCommand::SendDiscoveryMessage { .. },
            ] => *connection_id,
            other => panic!("unexpected commands: {other:?}"),
        };

        core.on_android_event(AndroidEvent::DiscoveryMessageReceived {
            channel: crate::types::DiscoveryChannel::Publish,
            handle_id: 1,
            payload: "hello:phone-a".to_string(),
        })
        .expect("duplicate hello during setup");
        let duplicate_commands = core.take_pending_commands().expect("duplicate commands");
        assert!(matches!(
            duplicate_commands.as_slice(),
            [AndroidCommand::SendDiscoveryMessage { .. }]
        ));

        core.on_android_event(AndroidEvent::SocketConnected {
            connection_id: first_connection_id,
            side: crate::types::SocketSide::Responder,
        })
        .expect("socket connected");
        let _ = core.take_pending_commands().expect("post connect");

        core.on_android_event(AndroidEvent::DiscoveryMessageReceived {
            channel: crate::types::DiscoveryChannel::Publish,
            handle_id: 1,
            payload: "hello:phone-a".to_string(),
        })
        .expect("duplicate hello after connect");
        let refresh_commands = core.take_pending_commands().expect("refresh commands");
        assert!(matches!(
            refresh_commands.as_slice(),
            [
                AndroidCommand::CloseSocket { connection_id, .. },
                AndroidCommand::OpenResponder { .. },
                AndroidCommand::SendDiscoveryMessage { .. },
            ] if *connection_id == first_connection_id
        ));
    }

    #[test]
    fn closing_last_peer_socket_restarts_nearby_stack() {
        let dir = tempfile::tempdir().expect("tempdir");
        let files_dir = dir.path().join("files");
        let cache_dir = dir.path().join("cache");
        std::fs::create_dir_all(&files_dir).expect("files dir");
        std::fs::create_dir_all(&cache_dir).expect("cache dir");
        let core = AppCore::new(
            files_dir.display().to_string(),
            cache_dir.display().to_string(),
            "phone-a".to_string(),
        )
        .expect("core");

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
        let connection_id = match commands.as_slice() {
            [AndroidCommand::OpenInitiator { connection_id, .. }] => *connection_id,
            other => panic!("unexpected commands: {other:?}"),
        };

        core.on_android_event(AndroidEvent::SocketConnected {
            connection_id,
            side: crate::types::SocketSide::Initiator,
        })
        .expect("socket connected");
        let _ = core.take_pending_commands().expect("post connect");

        core.on_android_event(AndroidEvent::SocketClosed { connection_id })
            .expect("socket closed");
        let commands = core.take_pending_commands().expect("restart commands");
        assert!(matches!(
            commands.as_slice(),
            [
                AndroidCommand::StopAware,
                AndroidCommand::StartAwareAttach,
            ]
        ));
    }
}
