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

#[derive(Debug)]
struct OutboundTransfer {
    connection_id: i64,
    label: String,
    photos: VecDeque<StoredPhoto>,
    total_count: usize,
    waiting_for_ack: bool,
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
    publish_handle_id: Option<i64>,
    subscribe_handle_id: Option<i64>,
    remote_peer_instance: Option<String>,
    hello_sent: bool,
    connected_socket_id: Option<i64>,
    connected_side: Option<SocketSide>,
    initiator_socket_connecting: bool,
    transfer_in_flight: bool,
    pending_remote_fetch_request: bool,
    inbound_decoders: HashMap<i64, FrameDecoder>,
    outbound_transfer: Option<OutboundTransfer>,
    active_feed_label: String,
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
            page: UiPage::Config,
            role: Role::Idle,
            status_text: "Idle".to_string(),
            link_text: "Idle".to_string(),
            pending_start_nearby: false,
            pending_commands: VecDeque::new(),
            log_lines: VecDeque::new(),
            next_message_id: 1,
            next_connection_id: 1,
            publish_handle_id: None,
            subscribe_handle_id: None,
            remote_peer_instance: None,
            hello_sent: false,
            connected_socket_id: None,
            connected_side: None,
            initiator_socket_connecting: false,
            transfer_in_flight: false,
            pending_remote_fetch_request: false,
            inbound_decoders: HashMap::new(),
            outbound_transfer: None,
            active_feed_label: ALBUM_LABEL.to_string(),
        };
        state.log("Local Instagram demo loaded. Take photos, keep them in hashtree-addressed storage, and share them with nearby peers over Wi-Fi Aware.");
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
                if self.transfer_in_flight {
                    self.log("Wait for the current transfer to finish before taking another photo.");
                    return Ok(());
                }
                let output_path = self.photo_store.create_capture_temp_path()?;
                self.queue(AndroidCommand::LaunchCameraCapture { output_path });
            }
            UiAction::StartNearbyRequested => {
                if self.role != Role::Idle {
                    self.log(&format!("Already running as {}.", self.role_label()));
                    return Ok(());
                }
                self.pending_start_nearby = true;
                self.status_text = "Attaching".to_string();
                self.link_text = "Requesting permissions".to_string();
                self.log(&format!("Attaching as Nearby ({})", self.app_instance));
                self.queue(AndroidCommand::RequestPermissions);
            }
            UiAction::StopRequested => {
                self.stop("Stopped manually");
            }
            UiAction::FetchFromPeerRequested => {
                if self.role != Role::Peer {
                    self.log("Start Nearby first.");
                    return Ok(());
                }
                if self.connected_socket_id.is_none() {
                    self.log("Wait for the Wi-Fi Aware peer socket before requesting a fetch.");
                    return Ok(());
                }
                if !self.send_message_to_active_peer(format!("fetch:{}", self.app_instance)) {
                    self.log("No nearby peer discovered yet.");
                    return Ok(());
                }
                self.log("Requested the nearby peer's available photo feed.");
            }
            UiAction::ShareAvailablePhotosRequested => {
                self.begin_outbound_transfer("manual share")?;
            }
            UiAction::ClearDemoDataRequested => {
                if self.role != Role::Idle {
                    self.stop("Stopped for demo data clear");
                }
                self.photo_store.clear_all()?;
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
            }
            AndroidEvent::CameraCaptureCancelled => {
                self.log("Photo capture canceled.");
            }
            AndroidEvent::AwareAttachSucceeded => {
                self.pending_start_nearby = false;
                self.role = Role::Peer;
                self.status_text = "Running".to_string();
                self.log("Attach succeeded.");
                self.queue(AndroidCommand::StartPublish {
                    service_name: SERVICE_NAME.to_string(),
                    service_info: format!("peer:{}", self.app_instance),
                });
                self.queue(AndroidCommand::StartSubscribe {
                    service_name: SERVICE_NAME.to_string(),
                });
            }
            AndroidEvent::AwareAttachFailed => {
                self.pending_start_nearby = false;
                self.role = Role::Idle;
                self.status_text = "Attach failed".to_string();
                self.link_text = "Attach failed".to_string();
                self.log("Attach failed.");
            }
            AndroidEvent::PublishStarted => {
                self.link_text = "Nearby publish started".to_string();
                self.log("Nearby publish started. Waiting for a peer hello if this phone becomes the responder.");
            }
            AndroidEvent::SubscribeStarted => {
                self.link_text = "Nearby subscribe started".to_string();
                self.log("Nearby subscribe started. Looking for a peer.");
            }
            AndroidEvent::PublishTerminated => {
                self.link_text = "Nearby publish terminated".to_string();
                self.log("Nearby publish session terminated.");
            }
            AndroidEvent::SubscribeTerminated => {
                self.link_text = "Nearby subscribe terminated".to_string();
                self.log("Nearby subscribe session terminated.");
            }
            AndroidEvent::PeerDiscovered { handle_id, instance } => {
                if !self.remember_peer(handle_id, instance.clone(), false) {
                    return Ok(());
                }
                let Some(discovered_instance) = instance else {
                    self.log(&format!(
                        "Discovered {}, but it did not advertise an app instance.",
                        peer_label(handle_id)
                    ));
                    return Ok(());
                };

                self.link_text = "Nearby peer discovered".to_string();
                self.log(&format!(
                    "Discovered nearby peer {} as {}",
                    discovered_instance,
                    peer_label(handle_id)
                ));
                if self.should_initiate_data_path(&discovered_instance) {
                    self.maybe_send_hello(handle_id);
                } else {
                    self.log("Tie-break selected the other phone to initiate the Wi-Fi Aware data path.");
                }
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
                    self.handle_responder_hello(handle_id, Some(remote_instance.to_string()))?;
                } else if let Some(remote_instance) = payload.strip_prefix("ready:") {
                    self.remember_peer(handle_id, Some(remote_instance.to_string()), false);
                    self.handle_initiator_ready(handle_id);
                } else if payload.starts_with("fetch:") {
                    self.remember_peer(handle_id, self.remote_peer_instance.clone(), channel == DiscoveryChannel::Publish);
                    self.pending_remote_fetch_request = true;
                    self.log("Nearby peer requested this phone's available feed.");
                    self.maybe_start_pending_fetch()?;
                }
            }
            AndroidEvent::DiscoveryMessageSent { message_id } => {
                self.log(&format!("Nearby discovery sent message #{message_id}"));
            }
            AndroidEvent::DiscoveryMessageFailed { message_id } => {
                self.log(&format!("Nearby discovery failed to send message #{message_id}"));
            }
            AndroidEvent::ResponderNetworkAvailable => {
                self.link_text = "Wi-Fi Aware data path available".to_string();
                self.log("Responder Wi-Fi Aware data path available.");
            }
            AndroidEvent::ResponderNetworkLost => {
                self.link_text = "Wi-Fi Aware data path lost".to_string();
                self.log("Responder data path lost.");
            }
            AndroidEvent::InitiatorNetworkAvailable => {
                self.link_text = "Wi-Fi Aware data path available".to_string();
                self.log("Initiator Wi-Fi Aware data path available.");
            }
            AndroidEvent::InitiatorNetworkLost => {
                self.link_text = "Wi-Fi Aware data path lost".to_string();
                self.initiator_socket_connecting = false;
                self.log("Initiator data path lost.");
            }
            AndroidEvent::InitiatorCapabilities { port, ipv6 } => {
                self.log(&format!(
                    "Initiator learned responder endpoint port={} ipv6={}",
                    port,
                    ipv6.clone().unwrap_or_else(|| "null".to_string())
                ));
                if let (Some(connection_id), Some(ipv6)) = (self.connected_socket_id.or(Some(self.next_connection_id - 1)), ipv6) {
                    if port > 0 && !self.initiator_socket_connecting {
                        self.initiator_socket_connecting = true;
                        self.queue(AndroidCommand::ConnectInitiatorSocket {
                            connection_id,
                            ipv6,
                            port,
                        });
                    }
                }
            }
            AndroidEvent::SocketConnected { connection_id, side } => {
                self.connected_socket_id = Some(connection_id);
                self.connected_side = Some(side.clone());
                self.initiator_socket_connecting = false;
                self.inbound_decoders.entry(connection_id).or_default();
                self.link_text = "Wi-Fi Aware peer socket connected".to_string();
                self.log(&format!("{} Wi-Fi Aware TCP socket connected.", socket_side_label(&side)));
                self.maybe_start_pending_fetch()?;
            }
            AndroidEvent::SocketClosed { connection_id } => {
                self.inbound_decoders.remove(&connection_id);
                if self.connected_socket_id == Some(connection_id) {
                    self.connected_socket_id = None;
                    self.connected_side = None;
                    self.transfer_in_flight = false;
                    self.outbound_transfer = None;
                }
                self.log("Peer photo stream closed.");
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
                self.link_text = "Wi-Fi Aware socket error".to_string();
            }
        }
        Ok(())
    }

    fn view_state(&self) -> ViewState {
        let local_count = self.photo_store.local_count();
        let nearby_count = self.photo_store.received_count();
        let storage_bytes = self.photo_store.total_storage_bytes();
        ViewState {
            page: self.page.clone(),
            status_text: format!("STATUS  {}", self.status_text),
            mode_text: format!("MODE  {}", self.role_label()),
            link_text: format!("LINK  {}", self.link_text),
            storage_text: format!("STORAGE  {}", format_byte_count(storage_bytes)),
            local_summary_text: format!("Local photos: {}  ·  Taken on this phone", local_count),
            nearby_summary_text: format!(
                "Nearby photos: {}  ·  {}",
                nearby_count,
                format_byte_count(storage_bytes)
            ),
            controls_enabled: ControlsEnabled {
                take_photo: !self.transfer_in_flight,
                start_nearby: self.role == Role::Idle,
                stop: self.role != Role::Idle,
                fetch_from_peer: self.role == Role::Peer
                    && self.connected_socket_id.is_some()
                    && (self.publish_handle_id.is_some() || self.subscribe_handle_id.is_some())
                    && !self.transfer_in_flight,
                share_available_photos: self.role == Role::Peer
                    && self.connected_socket_id.is_some()
                    && !self.initiator_socket_connecting
                    && !self.transfer_in_flight
                    && !self.photo_store.shareable_photos().is_empty(),
                clear_demo_data: !self.transfer_in_flight,
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

    fn remember_peer(
        &mut self,
        handle_id: i64,
        remote_instance: Option<String>,
        is_publish_handle: bool,
    ) -> bool {
        let normalized = remote_instance
            .clone()
            .or_else(|| self.remote_peer_instance.clone())
            .unwrap_or_else(|| peer_label(handle_id));
        if let Some(current_remote) = &self.remote_peer_instance {
            if current_remote != &normalized {
                self.log(&format!(
                    "Ignoring extra nearby peer {} because this demo currently stays paired with {}.",
                    normalized, current_remote
                ));
                return false;
            }
        }
        self.remote_peer_instance = Some(normalized);
        if is_publish_handle {
            self.publish_handle_id = Some(handle_id);
        } else {
            self.subscribe_handle_id = Some(handle_id);
        }
        true
    }

    fn should_initiate_data_path(
        &self,
        remote_instance: &str,
    ) -> bool {
        self.app_instance.as_str() < remote_instance
    }

    fn maybe_send_hello(
        &mut self,
        handle_id: i64,
    ) {
        if self.hello_sent
            || self.connected_socket_id.is_some()
            || self.initiator_socket_connecting
        {
            return;
        }
        self.hello_sent = true;
        self.link_text = "Initiating Wi-Fi Aware data path".to_string();
        if let Some(remote_instance) = &self.remote_peer_instance {
            self.log(&format!(
                "Tie-break selected this phone to initiate the Wi-Fi Aware data path with {}.",
                remote_instance
            ));
        }
        self.send_discovery_message(
            DiscoveryChannel::Subscribe,
            handle_id,
            format!("hello:{}", self.app_instance),
        );
    }

    fn handle_responder_hello(
        &mut self,
        handle_id: i64,
        remote_instance: Option<String>,
    ) -> Result<(), NearbyHashtreeError> {
        self.remember_peer(handle_id, remote_instance, true);
        if self.connected_side == Some(SocketSide::Responder) {
            self.log("Responder already requested a Wi-Fi Aware data path. Ignoring duplicate hello.");
            return Ok(());
        }
        self.link_text = "Preparing Wi-Fi Aware data path".to_string();
        self.log(&format!(
            "Preparing a Wi-Fi Aware data path for {}",
            self.remote_peer_instance
                .clone()
                .unwrap_or_else(|| peer_label(handle_id))
        ));
        let connection_id = self.next_connection_id;
        self.next_connection_id += 1;
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
        handle_id: i64,
    ) {
        if self.initiator_socket_connecting {
            self.log("Initiator already requested a data path.");
            return;
        }
        self.link_text = "Requesting Wi-Fi Aware data path".to_string();
        self.log(&format!(
            "Initiating a Wi-Fi Aware data path to {}",
            self.remote_peer_instance
                .clone()
                .unwrap_or_else(|| peer_label(handle_id))
        ));
        let connection_id = self.next_connection_id;
        self.next_connection_id += 1;
        self.queue(AndroidCommand::OpenInitiator {
            handle_id,
            passphrase: SECURE_PASSPHRASE.to_string(),
            connection_id,
        });
    }

    fn send_message_to_active_peer(
        &mut self,
        payload: String,
    ) -> bool {
        if let Some(handle_id) = self.subscribe_handle_id {
            self.send_discovery_message(DiscoveryChannel::Subscribe, handle_id, payload);
            return true;
        }
        if let Some(handle_id) = self.publish_handle_id {
            self.send_discovery_message(DiscoveryChannel::Publish, handle_id, payload);
            return true;
        }
        false
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

    fn maybe_start_pending_fetch(&mut self) -> Result<(), NearbyHashtreeError> {
        if self.role == Role::Peer
            && self.pending_remote_fetch_request
            && self.connected_socket_id.is_some()
            && !self.initiator_socket_connecting
            && !self.transfer_in_flight
        {
            self.pending_remote_fetch_request = false;
            self.begin_outbound_transfer("nearby fetch request")?;
        }
        Ok(())
    }

    fn begin_outbound_transfer(
        &mut self,
        trigger: &str,
    ) -> Result<(), NearbyHashtreeError> {
        if self.role != Role::Peer {
            self.log("Start Nearby first.");
            return Ok(());
        }
        if self.transfer_in_flight {
            self.log("A photo transfer is already in flight.");
            return Ok(());
        }
        let Some(connection_id) = self.connected_socket_id else {
            self.log("The Wi-Fi Aware peer socket is not connected yet.");
            return Ok(());
        };

        let photos = self.photo_store.shareable_photos();
        if photos.is_empty() {
            self.log("Take a photo first, or fetch photos from a nearby peer.");
            return Ok(());
        }

        self.transfer_in_flight = true;
        self.log(&format!(
            "Sending Wi-Fi Aware photo feed with {} photos ({}).",
            photos.len(),
            trigger
        ));
        self.queue(AndroidCommand::WriteSocketBytes {
            connection_id,
            bytes: encode_frame(&WireMessage::Set {
                label: ALBUM_LABEL.to_string(),
                count: photos.len() as u32,
            })?,
        });
        self.outbound_transfer = Some(OutboundTransfer {
            connection_id,
            label: ALBUM_LABEL.to_string(),
            photos: VecDeque::from(photos),
            total_count: self.photo_store.shareable_photos().len(),
            waiting_for_ack: false,
        });
        self.send_next_outbound_photo()?;
        Ok(())
    }

    fn send_next_outbound_photo(&mut self) -> Result<(), NearbyHashtreeError> {
        let Some(transfer) = self.outbound_transfer.as_mut() else {
            return Ok(());
        };
        if transfer.waiting_for_ack {
            return Ok(());
        }
        let Some(photo) = transfer.photos.pop_front() else {
            let connection_id = transfer.connection_id;
            let label = transfer.label.clone();
            let total_count = transfer.total_count;
            self.queue(AndroidCommand::WriteSocketBytes {
                connection_id,
                bytes: encode_frame(&WireMessage::Done {
                    label: label.clone(),
                    count: total_count as u32,
                })?,
            });
            self.log("Finished sending Wi-Fi Aware photo feed.");
            self.transfer_in_flight = false;
            self.outbound_transfer = None;
            self.maybe_start_pending_fetch()?;
            return Ok(());
        };

        let connection_id = transfer.connection_id;
        transfer.waiting_for_ack = true;
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
        let _ = transfer;
        self.log(&format!(
            "Sending {} nhash={} size={} over Wi-Fi Aware",
            photo_id,
            photo_nhash,
            format_byte_count(photo_size_bytes)
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
        match message {
            WireMessage::Set { label, count } => {
                self.active_feed_label = label.clone();
                self.log(&format!(
                    "Receiving Wi-Fi Aware photo feed {} with {} photos.",
                    label, count
                ));
            }
            WireMessage::Photo { photo } => {
                self.log(&format!(
                    "Receiving photo {} from {} inside {} nhash={} ({}) over Wi-Fi Aware",
                    photo.id,
                    photo.source_label,
                    self.active_feed_label,
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
                    "Finished receiving Wi-Fi Aware photo feed {} ({} photos).",
                    label, count
                ));
            }
            WireMessage::Ack {
                success,
                actual_nhash,
                already_present,
                message,
            } => {
                self.log(&format!(
                    "Peer reply: success={} actualNhash={} alreadyPresent={}",
                    success,
                    actual_nhash.clone().unwrap_or_else(|| "n/a".to_string()),
                    already_present
                ));
                self.log(&message);
                if let Some(transfer) = self.outbound_transfer.as_mut() {
                    transfer.waiting_for_ack = false;
                }
                if !success {
                    self.log("Stopping photo transfer because the peer rejected a photo.");
                    self.transfer_in_flight = false;
                    self.outbound_transfer = None;
                    return Ok(());
                }
                self.send_next_outbound_photo()?;
            }
        }
        Ok(())
    }

    fn stop(&mut self, reason: &str) {
        self.pending_start_nearby = false;
        self.transfer_in_flight = false;
        self.pending_remote_fetch_request = false;
        self.publish_handle_id = None;
        self.subscribe_handle_id = None;
        self.remote_peer_instance = None;
        self.hello_sent = false;
        self.connected_socket_id = None;
        self.connected_side = None;
        self.initiator_socket_connecting = false;
        self.inbound_decoders.clear();
        self.outbound_transfer = None;
        self.role = Role::Idle;
        self.status_text = "Stopped".to_string();
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
}
