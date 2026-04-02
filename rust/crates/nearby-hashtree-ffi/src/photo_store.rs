use std::fs;
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

use crate::types::{FeedItem, StoredPhoto, VerifyStoredPhotoResult};
use crate::{compute_nhash_from_file_impl, NearbyHashtreeError};

#[derive(Debug)]
pub struct PhotoStore {
    base_dir: PathBuf,
    local_dir: PathBuf,
    received_dir: PathBuf,
    tmp_dir: PathBuf,
    capture_dir: PathBuf,
    local_manifest_file: PathBuf,
    received_manifest_file: PathBuf,
}

impl PhotoStore {
    pub fn new(files_dir: impl AsRef<Path>) -> Result<Self, NearbyHashtreeError> {
        let base_dir = files_dir.as_ref().join("demo");
        let local_dir = base_dir.join("local");
        let received_dir = base_dir.join("received");
        let tmp_dir = base_dir.join("tmp");
        let capture_dir = tmp_dir.join("capture");
        let store = Self {
            base_dir,
            local_dir: local_dir.clone(),
            received_dir: received_dir.clone(),
            tmp_dir: tmp_dir.clone(),
            capture_dir: capture_dir.clone(),
            local_manifest_file: local_dir.join("manifest.json"),
            received_manifest_file: received_dir.join("manifest.json"),
        };
        store.ensure_dirs()?;
        Ok(store)
    }

    pub fn create_capture_temp_path(&self) -> Result<String, NearbyHashtreeError> {
        self.ensure_dirs()?;
        Ok(self
            .capture_dir
            .join(format!("capture-{}.jpg", now_millis()))
            .display()
            .to_string())
    }

    pub fn finalize_captured_photo(
        &self,
        temp_path: &str,
    ) -> Result<StoredPhoto, NearbyHashtreeError> {
        self.ensure_dirs()?;
        let temp_file = PathBuf::from(temp_path);
        let nhash = compute_nhash_from_file_impl(temp_path)?;
        let created_at_ms = now_millis();
        let target = self.local_dir.join(format!("{nhash}.jpg"));
        fs::copy(&temp_file, &target).map_err(io_err("copy captured photo"))?;
        let _ = fs::remove_file(&temp_file);

        let photo = StoredPhoto {
            id: format!("photo-{created_at_ms}"),
            nhash,
            size_bytes: target.metadata().map_err(io_err("read captured photo metadata"))?.len(),
            created_at_ms,
            source_label: "Taken Here".to_string(),
            mime_type: "image/jpeg".to_string(),
            relative_path: target
                .file_name()
                .and_then(|name| name.to_str())
                .unwrap_or_default()
                .to_string(),
        };
        self.upsert_manifest(&self.local_manifest_file, &self.local_dir, &photo)?;
        Ok(photo)
    }

    pub fn create_incoming_temp_path(
        &self,
        photo_id: &str,
    ) -> Result<String, NearbyHashtreeError> {
        self.ensure_dirs()?;
        Ok(self
            .tmp_dir
            .join(format!("incoming-{photo_id}-{}.jpg", now_millis()))
            .display()
            .to_string())
    }

    pub fn verify_and_store_received_photo(
        &self,
        temp_path: &str,
        photo_id: &str,
        created_at_ms: i64,
        announced_nhash: &str,
        source_label: &str,
        mime_type: &str,
    ) -> Result<VerifyStoredPhotoResult, NearbyHashtreeError> {
        let actual_nhash = compute_nhash_from_file_impl(temp_path)?;
        if actual_nhash != announced_nhash {
            let _ = fs::remove_file(temp_path);
            return Ok(VerifyStoredPhotoResult {
                success: false,
                actual_nhash: Some(actual_nhash.clone()),
                already_present: false,
                message: format!(
                    "Rejected photo {photo_id}: announced {announced_nhash} but computed {actual_nhash}"
                ),
                stored_photo: None,
            });
        }

        if let Some(existing_photo) = self.find_existing_photo_by_nhash(&actual_nhash) {
            let _ = fs::remove_file(temp_path);
            return Ok(VerifyStoredPhotoResult {
                success: true,
                actual_nhash: Some(actual_nhash.clone()),
                already_present: true,
                message: format!(
                    "Verified Wi-Fi Aware photo {photo_id} ({actual_nhash}); already stored."
                ),
                stored_photo: Some(existing_photo),
            });
        }

        let target = self.received_dir.join(format!("{actual_nhash}.jpg"));
        fs::copy(temp_path, &target).map_err(io_err("copy received photo"))?;
        let _ = fs::remove_file(temp_path);

        let photo = StoredPhoto {
            id: photo_id.to_string(),
            nhash: actual_nhash.clone(),
            size_bytes: target.metadata().map_err(io_err("read received photo metadata"))?.len(),
            created_at_ms,
            source_label: source_label.to_string(),
            mime_type: mime_type.to_string(),
            relative_path: target
                .file_name()
                .and_then(|name| name.to_str())
                .unwrap_or_default()
                .to_string(),
        };
        self.upsert_manifest(&self.received_manifest_file, &self.received_dir, &photo)?;

        Ok(VerifyStoredPhotoResult {
            success: true,
            actual_nhash: Some(actual_nhash.clone()),
            already_present: false,
            message: format!("Verified Wi-Fi Aware photo {photo_id} ({actual_nhash}) and stored it."),
            stored_photo: Some(photo),
        })
    }

    pub fn clear_all(&self) -> Result<(), NearbyHashtreeError> {
        if self.base_dir.exists() {
            fs::remove_dir_all(&self.base_dir).map_err(io_err("clear demo data"))?;
        }
        self.ensure_dirs()?;
        Ok(())
    }

    pub fn current_local_photos(&self) -> Vec<StoredPhoto> {
        self.read_manifest(&self.local_manifest_file, &self.local_dir)
    }

    pub fn received_photos(&self) -> Vec<StoredPhoto> {
        self.read_manifest(&self.received_manifest_file, &self.received_dir)
    }

    pub fn total_storage_bytes(&self) -> u64 {
        [self.local_dir.as_path(), self.received_dir.as_path()]
            .iter()
            .flat_map(|dir| fs::read_dir(dir).ok().into_iter().flatten())
            .flatten()
            .filter_map(|entry| entry.metadata().ok())
            .filter(|metadata| metadata.is_file())
            .map(|metadata| metadata.len())
            .sum()
    }

    pub fn shareable_photos(&self) -> Vec<StoredPhoto> {
        let mut map = std::collections::BTreeMap::new();
        for photo in self
            .current_local_photos()
            .into_iter()
            .chain(self.received_photos().into_iter())
        {
            map.entry(photo.nhash.clone()).or_insert(photo);
        }
        let mut photos: Vec<_> = map.into_values().collect();
        photos.sort_by_key(|photo| std::cmp::Reverse(photo.created_at_ms));
        photos
    }

    pub fn feed_items(&self) -> Vec<FeedItem> {
        let mut photos: Vec<_> = self
            .current_local_photos()
            .into_iter()
            .chain(self.received_photos().into_iter())
            .collect();
        photos.sort_by_key(|photo| std::cmp::Reverse(photo.created_at_ms));
        photos
            .into_iter()
            .map(|photo| {
                let file_path = self.resolve_photo_path(&photo).display().to_string();
                let nhash_suffix = photo
                    .nhash
                    .chars()
                    .rev()
                    .take(14)
                    .collect::<String>()
                    .chars()
                    .rev()
                    .collect();
                FeedItem {
                    id: photo.id,
                    source_label: photo.source_label,
                    created_at_ms: photo.created_at_ms,
                    size_bytes: photo.size_bytes,
                    nhash_suffix,
                    file_path,
                }
            })
            .collect()
    }

    pub fn local_count(&self) -> usize {
        self.current_local_photos().len()
    }

    pub fn received_count(&self) -> usize {
        self.received_photos().len()
    }

    pub fn resolve_photo_path(&self, photo: &StoredPhoto) -> PathBuf {
        let local_path = self.local_dir.join(&photo.relative_path);
        if local_path.exists() {
            return local_path;
        }
        self.received_dir.join(&photo.relative_path)
    }

    fn ensure_dirs(&self) -> Result<(), NearbyHashtreeError> {
        fs::create_dir_all(&self.local_dir).map_err(io_err("create local dir"))?;
        fs::create_dir_all(&self.received_dir).map_err(io_err("create received dir"))?;
        fs::create_dir_all(&self.tmp_dir).map_err(io_err("create tmp dir"))?;
        fs::create_dir_all(&self.capture_dir).map_err(io_err("create capture dir"))?;
        Ok(())
    }

    fn read_manifest(
        &self,
        manifest_path: &Path,
        parent_dir: &Path,
    ) -> Vec<StoredPhoto> {
        let Ok(content) = fs::read_to_string(manifest_path) else {
            return Vec::new();
        };

        let Ok(mut photos) = serde_json::from_str::<Vec<StoredPhoto>>(&content) else {
            return Vec::new();
        };

        photos.retain(|photo| parent_dir.join(&photo.relative_path).exists());
        photos.sort_by_key(|photo| std::cmp::Reverse(photo.created_at_ms));
        photos
    }

    fn upsert_manifest(
        &self,
        manifest_path: &Path,
        parent_dir: &Path,
        photo: &StoredPhoto,
    ) -> Result<(), NearbyHashtreeError> {
        let mut photos = self.read_manifest(manifest_path, parent_dir);
        photos.retain(|existing| existing.nhash != photo.nhash);
        photos.push(photo.clone());
        photos.sort_by_key(|entry| std::cmp::Reverse(entry.created_at_ms));
        let json = serde_json::to_string_pretty(&photos)
            .map_err(|e| NearbyHashtreeError::Message(format!("failed to write manifest json: {e}")))?;
        fs::write(manifest_path, json).map_err(io_err("write manifest"))?;
        Ok(())
    }

    fn find_existing_photo_by_nhash(
        &self,
        nhash: &str,
    ) -> Option<StoredPhoto> {
        self.current_local_photos()
            .into_iter()
            .chain(self.received_photos())
            .find(|photo| photo.nhash == nhash)
    }
}

fn now_millis() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as i64
}

fn io_err(context: &'static str) -> impl Fn(std::io::Error) -> NearbyHashtreeError {
    move |e| NearbyHashtreeError::Message(format!("{context}: {e}"))
}

#[cfg(test)]
mod tests {
    use super::PhotoStore;

    #[test]
    fn duplicate_received_photo_is_detected() {
        let dir = tempfile::tempdir().expect("tempdir");
        let store = PhotoStore::new(dir.path()).expect("store");
        let file = dir.path().join("blob.jpg");
        std::fs::write(&file, b"photo-bytes").expect("write");
        let nhash = crate::compute_nhash_from_file(file.display().to_string()).expect("nhash");

        let temp1 = store
            .create_incoming_temp_path("photo-1")
            .expect("incoming path");
        std::fs::copy(&file, &temp1).expect("copy temp1");
        let first = store
            .verify_and_store_received_photo(&temp1, "photo-1", 1, &nhash, "Taken There", "image/jpeg")
            .expect("first store");
        assert!(first.success);
        assert!(!first.already_present);

        let temp2 = store
            .create_incoming_temp_path("photo-1")
            .expect("incoming path");
        std::fs::copy(&file, &temp2).expect("copy temp2");
        let second = store
            .verify_and_store_received_photo(&temp2, "photo-1", 1, &nhash, "Taken There", "image/jpeg")
            .expect("second store");
        assert!(second.success);
        assert!(second.already_present);
    }

    #[test]
    fn received_photo_is_deduped_against_existing_local_copy() {
        let dir = tempfile::tempdir().expect("tempdir");
        let store = PhotoStore::new(dir.path()).expect("store");
        let local_capture = dir.path().join("capture.jpg");
        std::fs::write(&local_capture, b"photo-bytes").expect("write");
        let local = store
            .finalize_captured_photo(local_capture.to_str().expect("capture path"))
            .expect("finalize local");

        let temp = store
            .create_incoming_temp_path("photo-remote")
            .expect("incoming path");
        std::fs::write(&temp, b"photo-bytes").expect("write incoming");
        let result = store
            .verify_and_store_received_photo(
                &temp,
                "photo-remote",
                2,
                &local.nhash,
                "Taken There",
                "image/jpeg",
            )
            .expect("verify");

        assert!(result.success);
        assert!(result.already_present);
        assert_eq!(store.received_photos().len(), 0);
        assert_eq!(store.feed_items().len(), 1);
    }
}
