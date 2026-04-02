use std::sync::{Arc, OnceLock};

use futures::io::AllowStdIo;
use hashtree_core::{nhash_encode_full, HashTree, HashTreeConfig, MemoryStore, NHashData};

#[derive(Debug, Clone, thiserror::Error, uniffi::Error)]
pub enum NearbyHashtreeError {
    #[error("{0}")]
    Message(String),
}

fn runtime() -> &'static tokio::runtime::Runtime {
    static RUNTIME: OnceLock<tokio::runtime::Runtime> = OnceLock::new();
    RUNTIME.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build()
            .expect("failed to initialize nearby_hashtree_ffi runtime")
    })
}

#[uniffi::export]
pub fn compute_nhash_from_file(file_path: String) -> Result<String, NearbyHashtreeError> {
    runtime().block_on(compute_nhash_from_file_impl(&file_path))
}

async fn compute_nhash_from_file_impl(file_path: &str) -> Result<String, NearbyHashtreeError> {
    let file = std::fs::File::open(file_path)
        .map_err(|e| NearbyHashtreeError::Message(format!("failed to open {}: {}", file_path, e)))?;

    let store = MemoryStore::new();
    let tree = HashTree::new(HashTreeConfig::new(Arc::new(store)).public());
    let (cid, _size) = tree
        .put_stream(AllowStdIo::new(file))
        .await
        .map_err(|e| NearbyHashtreeError::Message(format!("failed to hash file: {}", e)))?;

    nhash_encode_full(&NHashData {
        hash: cid.hash,
        decrypt_key: cid.key,
    })
    .map_err(|e| NearbyHashtreeError::Message(format!("failed to encode nhash: {}", e)))
}

uniffi::setup_scaffolding!();

#[cfg(test)]
mod tests {
    use super::compute_nhash_from_file;

    #[test]
    fn compute_nhash_is_deterministic_for_same_file() {
        let dir = tempfile::tempdir().expect("tempdir");
        let path = dir.path().join("blob.bin");
        std::fs::write(&path, b"nearby-hashtree-demo").expect("write blob");

        let first = compute_nhash_from_file(path.display().to_string()).expect("first nhash");
        let second = compute_nhash_from_file(path.display().to_string()).expect("second nhash");

        assert_eq!(first, second);
    }
}
