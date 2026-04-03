# FIPS-0001: LocalGram Architecture

Status: Informational  
Version: 2  
Updated: 2026-04-03

## Abstract

This document describes the shipped architecture of `nostr-wifi-aware` on the current `main` branch.

The application is a two-phone-and-up local photo-sharing demo with:

- camera-only capture
- Rust-owned application state and storage policy
- Kotlin-owned Android adapter code
- persistent hashtree block storage
- a persisted hashtree feed root
- Wi-Fi Aware peer discovery and block-level sync
- disposable image render cache for Android UI decoding

The key design rule is:

`persist blocks, persist roots, sync by hash/root`

The app no longer uses `manifest.json` or whole-photo transfer as the source of truth.

## 1. System Boundary

![System Boundary](/Users/l/Projects/iris/nostr-wifi-aware/docs/diagrams/fips-0001-system-boundary.svg)

The application is split into two layers:

- Kotlin adapter layer
- Rust core layer

Kotlin owns Android-only objects and side effects:

- `Activity` lifecycle
- camera `Intent` launch
- `FileProvider` URIs
- `WifiAwareSession`, `PeerHandle`, `ConnectivityManager`, `Network`
- socket IO
- `BitmapFactory` decoding
- view inflation and rendering

Rust owns application decisions and persistent state:

- feed root persistence
- block store access
- capture ingestion
- root/block sync protocol
- peer state machine
- dedupe and merge policy
- view-state shaping

## 2. Code Map

Primary Kotlin file:

- [MainActivity.kt](/Users/l/Projects/iris/nostr-wifi-aware/app/src/main/java/com/lauri000/nostrwifiaware/MainActivity.kt)

Primary Rust files:

- [app_core.rs](/Users/l/Projects/iris/nostr-wifi-aware/rust/crates/nearby-hashtree-ffi/src/app_core.rs)
- [photo_store.rs](/Users/l/Projects/iris/nostr-wifi-aware/rust/crates/nearby-hashtree-ffi/src/photo_store.rs)
- [protocol.rs](/Users/l/Projects/iris/nostr-wifi-aware/rust/crates/nearby-hashtree-ffi/src/protocol.rs)
- [types.rs](/Users/l/Projects/iris/nostr-wifi-aware/rust/crates/nearby-hashtree-ffi/src/types.rs)
- [lib.rs](/Users/l/Projects/iris/nostr-wifi-aware/rust/crates/nearby-hashtree-ffi/src/lib.rs)

## 3. Primary Interfaces

### 3.1 Kotlin adapter surface

Representative adapter functions in [MainActivity.kt](/Users/l/Projects/iris/nostr-wifi-aware/app/src/main/java/com/lauri000/nostrwifiaware/MainActivity.kt):

```kotlin
private fun dispatchUiAction(action: UiAction)
private fun dispatchAndroidEvent(event: AndroidEvent)
private fun drainCommands()
private fun executeCommand(command: AndroidCommand)
private fun executeWriteSocketBytes(command: AndroidCommand.WriteSocketBytes)
private fun createPhotoCard(item: FeedItem): View
private fun loadPreviewBitmap(file: File, targetWidth: Int, targetHeight: Int): Bitmap?
```

Kotlin does not decide what to store, what to merge, or what to broadcast.

### 3.2 Rust app core API

UniFFI-exposed API in [app_core.rs](/Users/l/Projects/iris/nostr-wifi-aware/rust/crates/nearby-hashtree-ffi/src/app_core.rs):

```rust
#[uniffi::constructor]
pub fn new(
    app_files_dir: String,
    app_cache_dir: String,
    app_instance: String,
) -> Result<Self, NearbyHashtreeError>

pub fn on_ui_action(&self, action: UiAction) -> Result<(), NearbyHashtreeError>
pub fn on_android_event(&self, event: AndroidEvent) -> Result<(), NearbyHashtreeError>
pub fn take_pending_commands(&self) -> Result<Vec<AndroidCommand>, NearbyHashtreeError>
pub fn current_view_state(&self) -> Result<ViewState, NearbyHashtreeError>
```

Rust app-core responsibilities:

- nearby start/stop
- peer discovery handshake
- initiator/responder tie-break
- framed socket protocol handling
- broadcast scheduling
- root/block merge decisions
- log generation
- UI control enablement

### 3.3 Rust feed store API

Representative storage methods in [photo_store.rs](/Users/l/Projects/iris/nostr-wifi-aware/rust/crates/nearby-hashtree-ffi/src/photo_store.rs):

```rust
pub fn create_capture_temp_path(&self) -> Result<String, NearbyHashtreeError>
pub fn finalize_captured_photo(&self, temp_path: &str) -> Result<StoredPhoto, NearbyHashtreeError>
pub fn current_root(&self) -> Result<Option<String>, NearbyHashtreeError>
pub fn feed_items(&self) -> Result<Vec<FeedItem>, NearbyHashtreeError>
pub fn collect_root_hashes(&self, root_nhash: &str) -> Result<Vec<String>, NearbyHashtreeError>
pub fn missing_hashes(&self, announced_hashes: &[String]) -> Result<Vec<String>, NearbyHashtreeError>
pub fn read_block(&self, hash_hex: &str) -> Result<Option<Vec<u8>>, NearbyHashtreeError>
pub fn store_incoming_block(&self, hash_hex: &str, bytes: &[u8]) -> Result<(), NearbyHashtreeError>
pub fn finalize_remote_sync(&self, remote_root_nhash: &str) -> Result<MergeResult, NearbyHashtreeError>
```

## 4. Hashtree Storage Model

### 4.1 Durable storage

The persistent store uses `hashtree-fs::FsBlobStore` under the app-private files directory.

On-device durable layout:

```text
files/hashtree/
  blocks/
    <sha-prefix>/<sha-rest>
  roots/
    device_id.txt
    local_feed_root.txt
```

Source of truth:

- photo bytes live in `files/hashtree/blocks`
- the current feed root lives in `files/hashtree/roots/local_feed_root.txt`

The app removes legacy `files/demo` storage automatically on startup and on data clear.

### 4.2 Feed as a hashtree directory

The feed is one flat hashtree directory root. Each photo is a directory entry whose content CID points at the stored JPEG block tree.

Entry naming:

- deterministic
- sortable by capture time
- format: `captured_at_ms` plus hash prefix, for example `001775188753808-9d9d8e21f6c0.jpg`

Entry metadata stored in `DirEntry.meta`:

- `captured_at_ms`
- `source_device_id`
- `mime_type`

Dedupe rule:

- directory merge deduplicates by content hash, not filename

## 5. Capture Ingest Flow

![Capture And Store Flow](/Users/l/Projects/iris/nostr-wifi-aware/docs/diagrams/fips-0001-capture-store.svg)

Capture path:

1. User taps `Take Photo`.
2. Kotlin sends `UiAction::TakePhotoRequested`.
3. Rust allocates a temp path under `cache/capture`.
4. Rust emits `AndroidCommand::LaunchCameraCapture { output_path }`.
5. Kotlin launches `ACTION_IMAGE_CAPTURE` using a `FileProvider` URI.
6. Android camera writes JPEG bytes to the temp file.
7. Kotlin sends `AndroidEvent::CameraCaptureCompleted { temp_path }`.
8. Rust opens the temp file and calls:

```rust
self.tree.put_stream(AllowStdIo::new(temp_file)).await
```

9. Rust creates or updates the feed directory root with a new entry for the photo CID.
10. Rust persists the new root to `local_feed_root.txt`.
11. The temp capture file is deleted.

The temp camera file is not durable application state.

## 6. Nearby Sync Protocol

![Fetch And Render Flow](/Users/l/Projects/iris/nostr-wifi-aware/docs/diagrams/fips-0001-fetch-render.svg)

The Wi-Fi Aware discovery service type is:

```rust
const SERVICE_NAME: &str = "_nostrwifiaware._tcp";
```

Discovery is followed by a single TCP socket over the Wi-Fi Aware data path. The socket protocol is block-oriented.

Wire protocol in [protocol.rs](/Users/l/Projects/iris/nostr-wifi-aware/rust/crates/nearby-hashtree-ffi/src/protocol.rs):

```rust
pub enum WireMessage {
    RootAnnounce {
        feed_root_nhash: String,
        block_hashes: Vec<String>,
        entry_count: u32,
    },
    BlockWant {
        feed_root_nhash: String,
        missing_hashes: Vec<String>,
    },
    BlockPut {
        hash_hex: String,
        bytes: Vec<u8>,
    },
    SyncDone {
        feed_root_nhash: String,
    },
}
```

Protocol semantics:

1. Broadcaster sends `RootAnnounce` with current feed root plus full block hash list.
2. Receiver compares hash list against its local block store.
3. Receiver sends `BlockWant` with only missing hashes.
4. Broadcaster sends one `BlockPut` per requested block.
5. Broadcaster sends `SyncDone`.
6. Receiver verifies the announced root with:

```rust
verify_tree_integrity(self.store.clone(), &remote_root.hash).await
```

7. If verification succeeds, receiver merges the remote directory entries into its local feed and persists a new root.

The app therefore syncs by announced root plus missing block hashes, not by whole-file transfer.

## 7. Render Path

The UI does not display bytes directly from the block store. Android still needs a filesystem path for `BitmapFactory`.

Render path:

1. Rust computes feed items in `feed_items()`.
2. For each item, Rust calls `ensure_render_cache(photo_cid, mime_type)`.
3. If needed, Rust reads the photo bytes back from hashtree:

```rust
self.tree.get(&cid, None).await
```

4. Rust writes a disposable cache file under `cache/render/<safe-cid>.jpg`.
5. Rust returns `FeedItem.render_cache_path`.
6. Kotlin decodes that path with `BitmapFactory.decodeFile(...)`.

Important distinction:

- block store is durable
- render cache is disposable

If `cache/render` is deleted, the UI regenerates it from the block store on next render.

## 8. Kotlin/Rust Command Boundary

Types declared in [types.rs](/Users/l/Projects/iris/nostr-wifi-aware/rust/crates/nearby-hashtree-ffi/src/types.rs):

- `UiAction`
- `AndroidEvent`
- `AndroidCommand`
- `ControlsEnabled`
- `FeedItem`
- `ViewState`

Boundary contract:

1. Kotlin sends a `UiAction`.
2. Rust mutates state and queues `AndroidCommand`s.
3. Kotlin executes those commands.
4. Kotlin converts Android callbacks into `AndroidEvent`s.
5. Rust updates state and produces a new `ViewState`.
6. Kotlin re-renders from `ViewState`.

This keeps Android APIs on the Kotlin side and application policy on the Rust side.

## 9. Verified Behavior

The current implementation has been verified on two USB-connected Pixel 9a devices:

- photo capture persists into `files/hashtree/blocks`
- feed survives app restart via `local_feed_root.txt`
- nearby broadcast announces a root and transfers only missing blocks
- receiver verifies remote root integrity before merge
- repeated broadcast produces no duplicate feed entries
- clearing only `cache/render` does not lose photos; the cache is recreated from the block store

## 10. Non-Goals of the Current Build

The current build does not yet include:

- trust policy above nearby mode
- private or encrypted content
- background sync
- multi-album or per-peer feed trees
- internet relay or Nostr event replication

It is intentionally a local-first, nearby-only photo feed built on persistent hashtree storage.
