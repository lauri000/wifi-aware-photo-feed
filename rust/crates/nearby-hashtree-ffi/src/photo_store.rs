use std::cmp::Reverse;
use std::collections::HashMap;
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};

use futures::io::AllowStdIo;
use hashtree_core::{
    collect_hashes, from_hex, nhash_decode, nhash_encode_full, sha256, to_hex,
    verify_tree_integrity, Cid, DirEntry, HashTree, HashTreeConfig, LinkType, NHashData,
    Store, TreeEntry,
};
use hashtree_fs::FsBlobStore;
use serde_json::Value;

use crate::types::FeedItem;
use crate::{runtime, NearbyHashtreeError};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StoredPhoto {
    pub id: String,
    pub photo_cid: String,
    pub size_bytes: u64,
    pub created_at_ms: i64,
    pub source_device_id: String,
    pub mime_type: String,
    pub is_local: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MergeResult {
    pub added_entries: usize,
    pub total_entries: usize,
    pub already_present_entries: usize,
}

pub struct PhotoStore {
    store_root_dir: PathBuf,
    blocks_dir: PathBuf,
    roots_dir: PathBuf,
    captures_dir: PathBuf,
    capture_inbox_dir: PathBuf,
    capture_originals_dir: PathBuf,
    legacy_demo_dir: PathBuf,
    render_cache_dir: PathBuf,
    local_feed_root_file: PathBuf,
    device_id_file: PathBuf,
    device_id: String,
    store: Arc<FsBlobStore>,
    tree: HashTree<FsBlobStore>,
}

impl PhotoStore {
    pub fn new(
        files_dir: impl AsRef<Path>,
        cache_dir: impl AsRef<Path>,
    ) -> Result<Self, NearbyHashtreeError> {
        let files_dir = files_dir.as_ref().to_path_buf();
        let cache_dir = cache_dir.as_ref().to_path_buf();
        let store_root_dir = files_dir.join("hashtree");
        let blocks_dir = store_root_dir.join("blocks");
        let roots_dir = store_root_dir.join("roots");
        let captures_dir = store_root_dir.join("captures");
        let capture_inbox_dir = captures_dir.join("inbox");
        let capture_originals_dir = captures_dir.join("originals");
        let legacy_demo_dir = files_dir.join("demo");
        let render_cache_dir = cache_dir.join("render");
        cleanup_legacy_demo_storage(&legacy_demo_dir)?;
        fs::create_dir_all(&blocks_dir).map_err(io_err("create blocks dir"))?;
        fs::create_dir_all(&roots_dir).map_err(io_err("create roots dir"))?;
        fs::create_dir_all(&capture_inbox_dir).map_err(io_err("create capture inbox dir"))?;
        fs::create_dir_all(&capture_originals_dir).map_err(io_err("create capture originals dir"))?;
        fs::create_dir_all(&render_cache_dir).map_err(io_err("create render cache dir"))?;

        let store = Arc::new(FsBlobStore::new(&blocks_dir).map_err(store_err("open blocks store"))?);
        let tree = HashTree::new(HashTreeConfig::new(store.clone()).public());
        let device_id_file = roots_dir.join("device_id.txt");
        let device_id = read_or_create_device_id(&device_id_file)?;

        Ok(Self {
            store_root_dir,
            blocks_dir,
            roots_dir: roots_dir.clone(),
            captures_dir,
            capture_inbox_dir,
            capture_originals_dir,
            legacy_demo_dir,
            render_cache_dir,
            local_feed_root_file: roots_dir.join("local_feed_root.txt"),
            device_id_file,
            device_id,
            store,
            tree,
        })
    }

    pub fn create_capture_temp_path(&self) -> Result<String, NearbyHashtreeError> {
        self.ensure_dirs()?;
        Ok(self
            .capture_inbox_dir
            .join(format!("capture-{}.jpg", now_millis()))
            .display()
            .to_string())
    }

    pub fn recover_pending_captures(&self) -> Result<usize, NearbyHashtreeError> {
        self.ensure_dirs()?;
        let mut recovered = 0usize;
        let mut pending = fs::read_dir(&self.capture_inbox_dir)
            .map_err(io_err("read capture inbox"))?
            .collect::<Result<Vec<_>, _>>()
            .map_err(io_err("collect capture inbox"))?;
        pending.sort_by_key(|entry| entry.path());
        for entry in pending {
            let path = entry.path();
            if !path.is_file() {
                continue;
            }
            if path.extension().and_then(|ext| ext.to_str()) != Some("jpg") {
                continue;
            }
            if path.metadata().map_err(io_err("read pending capture metadata"))?.len() == 0 {
                continue;
            }
            if self.finalize_captured_photo(&path.display().to_string()).is_ok() {
                recovered += 1;
            }
        }
        Ok(recovered)
    }

    pub fn finalize_captured_photo(
        &self,
        temp_path: &str,
    ) -> Result<StoredPhoto, NearbyHashtreeError> {
        self.ensure_dirs()?;
        let capture_path = PathBuf::from(temp_path);
        let temp_file = std::fs::File::open(&capture_path).map_err(io_err("open captured photo"))?;
        let captured_at_ms = now_millis();
        let (cid, size_bytes) = runtime().block_on(async {
            self.tree
                .put_stream(AllowStdIo::new(temp_file))
                .await
                .map_err(tree_err("store captured photo"))
        })?;
        let archived_raw_path = self.archive_raw_capture(&capture_path, captured_at_ms, &cid.hash)?;

        let current_entries = self.current_entries()?;
        if let Some(existing) = current_entries.iter().find(|entry| entry.hash == cid.hash) {
            return self.entry_to_stored_photo(existing.clone());
        }

        let entry_name = entry_name_for(captured_at_ms, &cid.hash);
        let mut entries = self.entries_to_dir_entries(current_entries);
        entries.push(self.build_photo_entry(
            &entry_name,
            &cid,
            size_bytes,
            captured_at_ms,
            &self.device_id,
            "image/jpeg",
            archived_raw_path.file_name().and_then(|name| name.to_str()),
        ));
        let new_root = runtime().block_on(async {
            self.tree
                .put_directory(entries)
                .await
                .map_err(tree_err("update local feed root"))
        })?;
        self.persist_current_root(Some(&new_root))?;

        self.entry_to_stored_photo(TreeEntry {
            name: entry_name,
            hash: cid.hash,
            size: size_bytes,
            link_type: LinkType::File,
            key: cid.key,
            meta: Some(photo_meta(
                captured_at_ms,
                &self.device_id,
                "image/jpeg",
                archived_raw_path.file_name().and_then(|name| name.to_str()),
            )),
        })
    }

    pub fn clear_all(&self) -> Result<(), NearbyHashtreeError> {
        cleanup_legacy_demo_storage(&self.legacy_demo_dir)?;
        if self.store_root_dir.exists() {
            fs::remove_dir_all(&self.store_root_dir).map_err(io_err("clear hashtree store"))?;
        }
        if self.render_cache_dir.exists() {
            fs::remove_dir_all(&self.render_cache_dir).map_err(io_err("clear render cache"))?;
        }
        self.ensure_dirs()?;
        fs::write(&self.device_id_file, &self.device_id).map_err(io_err("persist device id"))?;
        Ok(())
    }

    pub fn current_root(&self) -> Result<Option<String>, NearbyHashtreeError> {
        self.current_root_cid()
            .and_then(|root| root.map(|cid| encode_cid(&cid)).transpose())
    }

    pub fn has_any_photos(&self) -> Result<bool, NearbyHashtreeError> {
        Ok(!self.list_stored_photos()?.is_empty())
    }

    pub fn feed_items(&self) -> Result<Vec<FeedItem>, NearbyHashtreeError> {
        let mut photos = self.list_stored_photos()?;
        photos.sort_by_key(|photo| Reverse(photo.created_at_ms));
        photos
            .into_iter()
            .map(|photo| {
                let render_cache_path =
                    self.ensure_render_cache(&photo.photo_cid, &photo.mime_type)?;
                Ok(FeedItem {
                    id: photo.id.clone(),
                    source_label: if photo.is_local {
                        "Taken Here".to_string()
                    } else {
                        format!(
                            "Nearby from {}",
                            short_device_id(&photo.source_device_id)
                        )
                    },
                    source_device_id: photo.source_device_id,
                    created_at_ms: photo.created_at_ms,
                    size_bytes: photo.size_bytes,
                    photo_cid: photo.photo_cid,
                    mime_type: photo.mime_type,
                    is_local: photo.is_local,
                    render_cache_path,
                })
            })
            .collect()
    }

    pub fn local_count(&self) -> Result<usize, NearbyHashtreeError> {
        Ok(self
            .list_stored_photos()?
            .into_iter()
            .filter(|photo| photo.is_local)
            .count())
    }

    pub fn received_count(&self) -> Result<usize, NearbyHashtreeError> {
        Ok(self
            .list_stored_photos()?
            .into_iter()
            .filter(|photo| !photo.is_local)
            .count())
    }

    pub fn total_storage_bytes(&self) -> Result<u64, NearbyHashtreeError> {
        self.store
            .stats()
            .map(|stats| stats.total_bytes)
            .map_err(store_err("read block store stats"))
    }

    pub fn current_entry_count(&self) -> Result<usize, NearbyHashtreeError> {
        Ok(self.current_entries()?.len())
    }

    pub fn collect_root_hashes(
        &self,
        root_nhash: &str,
    ) -> Result<Vec<String>, NearbyHashtreeError> {
        let cid = decode_cid(root_nhash)?;
        let mut hashes = runtime().block_on(async {
            collect_hashes(&self.tree, &cid, 8)
                .await
                .map_err(tree_err("collect root hashes"))
        })?
        .into_iter()
        .map(|hash| to_hex(&hash))
        .collect::<Vec<_>>();
        hashes.sort();
        Ok(hashes)
    }

    pub fn missing_hashes(
        &self,
        announced_hashes: &[String],
    ) -> Result<Vec<String>, NearbyHashtreeError> {
        let mut missing = Vec::new();
        for hash_hex in announced_hashes {
            let hash = from_hex(hash_hex)
                .map_err(|e| NearbyHashtreeError::Message(format!("invalid block hash {hash_hex}: {e}")))?;
            let has_block = runtime().block_on(async {
                self.store
                    .has(&hash)
                    .await
                    .map_err(store_err("check block existence"))
            })?;
            if !has_block {
                missing.push(hash_hex.clone());
            }
        }
        Ok(missing)
    }

    pub fn read_block(
        &self,
        hash_hex: &str,
    ) -> Result<Option<Vec<u8>>, NearbyHashtreeError> {
        let hash = from_hex(hash_hex)
            .map_err(|e| NearbyHashtreeError::Message(format!("invalid block hash {hash_hex}: {e}")))?;
        runtime().block_on(async {
            self.store
                .get(&hash)
                .await
                .map_err(store_err("read block"))
        })
    }

    pub fn store_incoming_block(
        &self,
        hash_hex: &str,
        bytes: &[u8],
    ) -> Result<(), NearbyHashtreeError> {
        let hash = from_hex(hash_hex)
            .map_err(|e| NearbyHashtreeError::Message(format!("invalid block hash {hash_hex}: {e}")))?;
        let actual_hash = sha256(bytes);
        if actual_hash != hash {
            return Err(NearbyHashtreeError::Message(format!(
                "incoming block {} failed integrity check; computed {}",
                hash_hex,
                to_hex(&actual_hash)
            )));
        }
        runtime().block_on(async {
            self.store
                .put(hash, bytes.to_vec())
                .await
                .map_err(store_err("store incoming block"))
        })?;
        Ok(())
    }

    pub fn finalize_remote_sync(
        &self,
        remote_root_nhash: &str,
    ) -> Result<MergeResult, NearbyHashtreeError> {
        let remote_root = decode_cid(remote_root_nhash)?;
        let verify = runtime().block_on(async {
            verify_tree_integrity(self.store.clone(), &remote_root.hash)
                .await
                .map_err(reader_err("verify remote root"))
        })?;
        if !verify.valid {
            return Err(NearbyHashtreeError::Message(format!(
                "remote root {} incomplete or corrupted; missing={} corrupted={}",
                short_nhash(remote_root_nhash),
                verify.missing.len(),
                verify.corrupted.len()
            )));
        }
        self.merge_remote_root(remote_root_nhash)
    }

    fn ensure_dirs(&self) -> Result<(), NearbyHashtreeError> {
        fs::create_dir_all(&self.blocks_dir).map_err(io_err("create blocks dir"))?;
        fs::create_dir_all(&self.roots_dir).map_err(io_err("create roots dir"))?;
        fs::create_dir_all(&self.captures_dir).map_err(io_err("create captures dir"))?;
        fs::create_dir_all(&self.capture_inbox_dir).map_err(io_err("create capture inbox dir"))?;
        fs::create_dir_all(&self.capture_originals_dir).map_err(io_err("create capture originals dir"))?;
        fs::create_dir_all(&self.render_cache_dir).map_err(io_err("create render cache dir"))?;
        Ok(())
    }

    fn ensure_render_cache(
        &self,
        photo_cid: &str,
        mime_type: &str,
    ) -> Result<String, NearbyHashtreeError> {
        self.ensure_dirs()?;
        let extension = extension_for_mime_type(mime_type);
        let cache_file = self
            .render_cache_dir
            .join(format!("{}.{}", safe_cache_key(photo_cid), extension));
        if !cache_file.exists() {
            let cid = decode_cid(photo_cid)?;
            let bytes = runtime().block_on(async {
                self.tree
                    .get(&cid, None)
                    .await
                    .map_err(tree_err("read photo for render cache"))
            })?
            .ok_or_else(|| {
                NearbyHashtreeError::Message(format!("missing photo bytes for {photo_cid}"))
            })?;
            fs::write(&cache_file, bytes).map_err(io_err("write render cache file"))?;
        }
        Ok(cache_file.display().to_string())
    }

    fn list_stored_photos(&self) -> Result<Vec<StoredPhoto>, NearbyHashtreeError> {
        self.current_entries()?
            .into_iter()
            .map(|entry| self.entry_to_stored_photo(entry))
            .collect()
    }

    fn entry_to_stored_photo(
        &self,
        entry: TreeEntry,
    ) -> Result<StoredPhoto, NearbyHashtreeError> {
        let photo_cid = encode_cid(&Cid {
            hash: entry.hash,
            key: entry.key,
        })?;
        let captured_at_ms = entry_meta_i64(&entry.meta, "captured_at_ms")
            .unwrap_or_else(|| parse_created_at_from_name(&entry.name));
        let source_device_id = entry_meta_string(&entry.meta, "source_device_id")
            .unwrap_or_else(|| "unknown".to_string());
        let mime_type = entry_meta_string(&entry.meta, "mime_type")
            .unwrap_or_else(|| "image/jpeg".to_string());
        Ok(StoredPhoto {
            id: entry.name,
            photo_cid,
            size_bytes: entry.size,
            created_at_ms: captured_at_ms,
            source_device_id: source_device_id.clone(),
            mime_type,
            is_local: source_device_id == self.device_id,
        })
    }

    fn current_entries(&self) -> Result<Vec<TreeEntry>, NearbyHashtreeError> {
        let Some(root) = self.current_root_cid()? else {
            return Ok(Vec::new());
        };
        runtime().block_on(async {
            self.tree
                .list_directory(&root)
                .await
                .map_err(tree_err("list feed directory"))
        })
    }

    fn merge_remote_root(
        &self,
        remote_root_nhash: &str,
    ) -> Result<MergeResult, NearbyHashtreeError> {
        let remote_root = decode_cid(remote_root_nhash)?;
        let remote_entries = runtime().block_on(async {
            self.tree
                .list_directory(&remote_root)
                .await
                .map_err(tree_err("list remote feed directory"))
        })?;

        if self.current_root_cid()?.is_none() {
            self.persist_current_root(Some(&remote_root))?;
            return Ok(MergeResult {
                added_entries: remote_entries.len(),
                total_entries: remote_entries.len(),
                already_present_entries: 0,
            });
        }

        let local_entries = self.current_entries()?;
        let mut entries_by_hash = HashMap::new();
        for entry in local_entries.iter().cloned() {
            entries_by_hash.insert(to_hex(&entry.hash), entry);
        }

        let mut added_entries = 0usize;
        let mut already_present_entries = 0usize;
        for entry in remote_entries {
            let key = to_hex(&entry.hash);
            if let std::collections::hash_map::Entry::Vacant(slot) = entries_by_hash.entry(key) {
                slot.insert(entry);
                added_entries += 1;
            } else {
                already_present_entries += 1;
            }
        }

        if added_entries == 0 {
            return Ok(MergeResult {
                added_entries: 0,
                total_entries: entries_by_hash.len(),
                already_present_entries,
            });
        }

        let merged_entries = self.entries_to_dir_entries(entries_by_hash.into_values().collect());
        let new_root = runtime().block_on(async {
            self.tree
                .put_directory(merged_entries)
                .await
                .map_err(tree_err("persist merged feed root"))
        })?;
        self.persist_current_root(Some(&new_root))?;

        Ok(MergeResult {
            added_entries,
            total_entries: self.current_entries()?.len(),
            already_present_entries,
        })
    }

    fn current_root_cid(&self) -> Result<Option<Cid>, NearbyHashtreeError> {
        if !self.local_feed_root_file.exists() {
            return Ok(None);
        }
        let nhash = fs::read_to_string(&self.local_feed_root_file)
            .map_err(io_err("read local feed root"))?;
        let nhash = nhash.trim();
        if nhash.is_empty() {
            return Ok(None);
        }
        decode_cid(nhash).map(Some)
    }

    fn persist_current_root(
        &self,
        root: Option<&Cid>,
    ) -> Result<(), NearbyHashtreeError> {
        self.ensure_dirs()?;
        match root {
            Some(root) => {
                let nhash = encode_cid(root)?;
                fs::write(&self.local_feed_root_file, nhash)
                    .map_err(io_err("write local feed root"))?;
            }
            None => {
                if self.local_feed_root_file.exists() {
                    fs::remove_file(&self.local_feed_root_file)
                        .map_err(io_err("remove local feed root"))?;
                }
            }
        }
        Ok(())
    }

    fn build_photo_entry(
        &self,
        name: &str,
        cid: &Cid,
        size_bytes: u64,
        captured_at_ms: i64,
        source_device_id: &str,
        mime_type: &str,
        raw_capture_name: Option<&str>,
    ) -> DirEntry {
        DirEntry::from_cid(name, cid)
            .with_size(size_bytes)
            .with_link_type(LinkType::File)
            .with_meta(photo_meta(
                captured_at_ms,
                source_device_id,
                mime_type,
                raw_capture_name,
            ))
    }

    fn entries_to_dir_entries(
        &self,
        entries: Vec<TreeEntry>,
    ) -> Vec<DirEntry> {
        entries
            .into_iter()
            .map(|entry| DirEntry {
                name: entry.name,
                hash: entry.hash,
                size: entry.size,
                key: entry.key,
                link_type: entry.link_type,
                meta: entry.meta,
            })
            .collect()
    }

    fn archive_raw_capture(
        &self,
        capture_path: &Path,
        captured_at_ms: i64,
        hash: &[u8; 32],
    ) -> Result<PathBuf, NearbyHashtreeError> {
        self.ensure_dirs()?;
        let archived_name = entry_name_for(captured_at_ms, hash);
        let archived_path = self.capture_originals_dir.join(archived_name);

        if capture_path == archived_path {
            return Ok(archived_path);
        }

        if archived_path.exists() {
            if capture_path.exists() {
                fs::remove_file(capture_path).map_err(io_err("remove duplicate raw capture"))?;
            }
            return Ok(archived_path);
        }

        fs::rename(capture_path, &archived_path).map_err(io_err("archive raw capture"))?;
        Ok(archived_path)
    }
}

fn encode_cid(cid: &Cid) -> Result<String, NearbyHashtreeError> {
    nhash_encode_full(&NHashData {
        hash: cid.hash,
        decrypt_key: cid.key,
    })
    .map_err(|e| NearbyHashtreeError::Message(format!("failed to encode nhash: {e}")))
}

fn decode_cid(nhash: &str) -> Result<Cid, NearbyHashtreeError> {
    let decoded = nhash_decode(nhash)
        .map_err(|e| NearbyHashtreeError::Message(format!("failed to decode nhash {nhash}: {e}")))?;
    Ok(Cid {
        hash: decoded.hash,
        key: decoded.decrypt_key,
    })
}

fn photo_meta(
    captured_at_ms: i64,
    source_device_id: &str,
    mime_type: &str,
    raw_capture_name: Option<&str>,
) -> HashMap<String, Value> {
    let mut meta = HashMap::from([
        (
            "captured_at_ms".to_string(),
            Value::Number(captured_at_ms.into()),
        ),
        (
            "source_device_id".to_string(),
            Value::String(source_device_id.to_string()),
        ),
        ("mime_type".to_string(), Value::String(mime_type.to_string())),
    ]);
    if let Some(raw_capture_name) = raw_capture_name {
        meta.insert(
            "raw_capture_name".to_string(),
            Value::String(raw_capture_name.to_string()),
        );
    }
    meta
}

fn entry_meta_string(
    meta: &Option<HashMap<String, Value>>,
    key: &str,
) -> Option<String> {
    meta.as_ref()
        .and_then(|meta| meta.get(key))
        .and_then(Value::as_str)
        .map(ToOwned::to_owned)
}

fn entry_meta_i64(
    meta: &Option<HashMap<String, Value>>,
    key: &str,
) -> Option<i64> {
    meta.as_ref()
        .and_then(|meta| meta.get(key))
        .and_then(Value::as_i64)
}

fn entry_name_for(
    captured_at_ms: i64,
    hash: &[u8; 32],
) -> String {
    format!("{captured_at_ms:015}-{}.jpg", &to_hex(hash)[..12])
}

fn parse_created_at_from_name(name: &str) -> i64 {
    name.split('-')
        .next()
        .and_then(|prefix| prefix.parse::<i64>().ok())
        .unwrap_or(0)
}

fn extension_for_mime_type(mime_type: &str) -> &'static str {
    match mime_type {
        "image/jpeg" => "jpg",
        "image/png" => "png",
        _ => "bin",
    }
}

fn safe_cache_key(photo_cid: &str) -> String {
    photo_cid.replace(':', "_")
}

fn short_device_id(device_id: &str) -> String {
    if device_id.len() > 10 {
        device_id[device_id.len() - 10..].to_string()
    } else {
        device_id.to_string()
    }
}

fn short_nhash(nhash: &str) -> String {
    if nhash.len() > 16 {
        nhash[nhash.len() - 16..].to_string()
    } else {
        nhash.to_string()
    }
}

fn read_or_create_device_id(path: &Path) -> Result<String, NearbyHashtreeError> {
    if path.exists() {
        let device_id = fs::read_to_string(path).map_err(io_err("read device id"))?;
        let trimmed = device_id.trim();
        if !trimmed.is_empty() {
            return Ok(trimmed.to_string());
        }
    }

    let device_id = format!("device-{}-{:x}", std::process::id(), now_nanos());
    fs::write(path, &device_id).map_err(io_err("write device id"))?;
    Ok(device_id)
}

fn cleanup_legacy_demo_storage(path: &Path) -> Result<(), NearbyHashtreeError> {
    if path.exists() {
        fs::remove_dir_all(path).map_err(io_err("remove legacy demo storage"))?;
    }
    Ok(())
}

fn now_millis() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as i64
}

fn now_nanos() -> u128 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos()
}

fn io_err(context: &'static str) -> impl Fn(std::io::Error) -> NearbyHashtreeError {
    move |e| NearbyHashtreeError::Message(format!("{context}: {e}"))
}

fn tree_err(context: &'static str) -> impl Fn(hashtree_core::HashTreeError) -> NearbyHashtreeError {
    move |e| NearbyHashtreeError::Message(format!("{context}: {e}"))
}

fn reader_err(context: &'static str) -> impl Fn(hashtree_core::ReaderError) -> NearbyHashtreeError {
    move |e| NearbyHashtreeError::Message(format!("{context}: {e}"))
}

fn store_err(
    context: &'static str,
) -> impl Fn(hashtree_core::StoreError) -> NearbyHashtreeError {
    move |e| NearbyHashtreeError::Message(format!("{context}: {e}"))
}

#[cfg(test)]
mod tests {
    use super::PhotoStore;

    fn make_store() -> (tempfile::TempDir, PhotoStore) {
        let dir = tempfile::tempdir().expect("tempdir");
        let files_dir = dir.path().join("files");
        let cache_dir = dir.path().join("cache");
        std::fs::create_dir_all(&files_dir).expect("files dir");
        std::fs::create_dir_all(&cache_dir).expect("cache dir");
        let store = PhotoStore::new(&files_dir, &cache_dir).expect("store");
        (dir, store)
    }

    #[test]
    fn ingesting_same_jpeg_reuses_existing_photo_and_blocks() {
        let (_dir, store) = make_store();
        let temp1 = store.create_capture_temp_path().expect("temp path 1");
        std::fs::write(&temp1, b"photo-bytes").expect("write photo 1");
        let first = store.finalize_captured_photo(&temp1).expect("first capture");
        let bytes_after_first = store.total_storage_bytes().expect("first bytes");

        let temp2 = store.create_capture_temp_path().expect("temp path 2");
        std::fs::write(&temp2, b"photo-bytes").expect("write photo 2");
        let second = store.finalize_captured_photo(&temp2).expect("second capture");
        let bytes_after_second = store.total_storage_bytes().expect("second bytes");

        assert_eq!(first.photo_cid, second.photo_cid);
        assert_eq!(store.current_entry_count().expect("entry count"), 1);
        assert_eq!(bytes_after_first, bytes_after_second);
    }

    #[test]
    fn captured_photo_is_archived_before_feed_sync() {
        let (_dir, store) = make_store();
        let temp = store.create_capture_temp_path().expect("temp path");
        assert!(temp.contains("/hashtree/captures/inbox/"));
        std::fs::write(&temp, b"durable-raw-photo").expect("write photo");

        let stored = store.finalize_captured_photo(&temp).expect("capture");

        assert!(!std::path::Path::new(&temp).exists());
        let originals = std::fs::read_dir(&store.capture_originals_dir)
            .expect("read originals dir")
            .collect::<Result<Vec<_>, _>>()
            .expect("collect originals");
        assert_eq!(originals.len(), 1);
        let raw_bytes = std::fs::read(originals[0].path()).expect("read archived raw photo");
        assert_eq!(raw_bytes, b"durable-raw-photo");
        assert_eq!(store.current_entry_count().expect("entry count"), 1);
        assert!(!stored.photo_cid.is_empty());
    }

    #[test]
    fn pending_inbox_capture_is_recovered_on_next_open() {
        let dir = tempfile::tempdir().expect("tempdir");
        let files_dir = dir.path().join("files");
        let cache_dir = dir.path().join("cache");
        std::fs::create_dir_all(&files_dir).expect("files dir");
        std::fs::create_dir_all(&cache_dir).expect("cache dir");
        let store = PhotoStore::new(&files_dir, &cache_dir).expect("store");
        let temp = store.create_capture_temp_path().expect("temp path");
        std::fs::write(&temp, b"recover-me").expect("write pending capture");
        drop(store);

        let reopened = PhotoStore::new(&files_dir, &cache_dir).expect("reopen store");
        let recovered = reopened.recover_pending_captures().expect("recover pending");

        assert_eq!(recovered, 1);
        assert_eq!(reopened.current_entry_count().expect("entry count"), 1);
        let inbox = std::fs::read_dir(files_dir.join("hashtree").join("captures").join("inbox"))
            .expect("read inbox")
            .collect::<Result<Vec<_>, _>>()
            .expect("collect inbox");
        assert!(inbox.is_empty());
    }

    #[test]
    fn render_cache_is_recreated_from_block_store() {
        let (_dir, store) = make_store();
        let temp = store.create_capture_temp_path().expect("temp path");
        std::fs::write(&temp, b"render-cache-photo").expect("write photo");
        store.finalize_captured_photo(&temp).expect("capture");

        let feed = store.feed_items().expect("feed");
        assert_eq!(feed.len(), 1);
        let render_cache_path = std::path::PathBuf::from(&feed[0].render_cache_path);
        assert!(render_cache_path.exists());

        std::fs::remove_dir_all(render_cache_path.parent().expect("render cache dir"))
            .expect("clear render cache");
        let feed_after_clear = store.feed_items().expect("feed after clear");
        assert!(std::path::Path::new(&feed_after_clear[0].render_cache_path).exists());
    }

    #[test]
    fn partial_block_sync_fails_until_all_blocks_arrive() {
        let (_dir_a, store_a) = make_store();
        let (_dir_b, store_b) = make_store();
        let temp = store_a.create_capture_temp_path().expect("temp path");
        std::fs::write(&temp, vec![42_u8; 512 * 1024]).expect("write large photo");
        store_a.finalize_captured_photo(&temp).expect("capture");
        let root = store_a
            .current_root()
            .expect("root")
            .expect("root nhash");
        let hashes = store_a.collect_root_hashes(&root).expect("hashes");
        assert!(hashes.len() > 1);

        for hash in hashes.iter().take(hashes.len() - 1) {
            let block = store_a
                .read_block(hash)
                .expect("read block")
                .expect("block bytes");
            store_b.store_incoming_block(hash, &block).expect("store partial block");
        }

        let err = store_b.finalize_remote_sync(&root).expect_err("partial sync should fail");
        assert!(err.to_string().contains("incomplete or corrupted"));

        let last_hash = hashes.last().expect("last hash");
        let block = store_a
            .read_block(last_hash)
            .expect("read last block")
            .expect("last block bytes");
        store_b
            .store_incoming_block(last_hash, &block)
            .expect("store final block");

        let merge = store_b.finalize_remote_sync(&root).expect("final sync");
        assert_eq!(merge.added_entries, 1);
        assert_eq!(store_b.feed_items().expect("feed").len(), 1);
    }

    #[test]
    fn repeat_remote_sync_deduplicates_by_photo_hash() {
        let (_dir_a, store_a) = make_store();
        let (_dir_b, store_b) = make_store();
        let temp = store_a.create_capture_temp_path().expect("temp path");
        std::fs::write(&temp, b"dedupe-remote-photo").expect("write photo");
        store_a.finalize_captured_photo(&temp).expect("capture");
        let root = store_a.current_root().expect("root").expect("root nhash");

        for hash in store_a.collect_root_hashes(&root).expect("hashes") {
            let block = store_a
                .read_block(&hash)
                .expect("read block")
                .expect("block bytes");
            store_b.store_incoming_block(&hash, &block).expect("store block");
        }

        let first = store_b.finalize_remote_sync(&root).expect("first sync");
        let second = store_b.finalize_remote_sync(&root).expect("second sync");

        assert_eq!(first.added_entries, 1);
        assert_eq!(second.added_entries, 0);
        assert_eq!(store_b.feed_items().expect("feed").len(), 1);
    }

    #[test]
    fn legacy_demo_storage_is_removed_on_open() {
        let dir = tempfile::tempdir().expect("tempdir");
        let files_dir = dir.path().join("files");
        let cache_dir = dir.path().join("cache");
        let legacy_dir = files_dir.join("demo").join("local");
        std::fs::create_dir_all(&legacy_dir).expect("legacy dir");
        std::fs::write(legacy_dir.join("manifest.json"), b"{}").expect("legacy manifest");

        let _store = PhotoStore::new(&files_dir, &cache_dir).expect("store");

        assert!(!files_dir.join("demo").exists());
    }
}
