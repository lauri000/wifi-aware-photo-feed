use serde::{Deserialize, Serialize};

use crate::NearbyHashtreeError;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum WireMessage {
    SocketHello {
        instance: String,
    },
    RootAnnounce {
        feed_root_nhash: String,
        block_hashes: Vec<String>,
        entry_count: u32,
        sync_id: i64,
    },
    BlockWant {
        feed_root_nhash: String,
        missing_hashes: Vec<String>,
        sync_id: i64,
    },
    BlockPut {
        hash_hex: String,
        bytes: Vec<u8>,
        sync_id: i64,
    },
    SyncDone {
        feed_root_nhash: String,
        sync_id: i64,
    },
    SyncApplied {
        feed_root_nhash: String,
        added_entries: u32,
        sync_id: i64,
    },
    SyncError {
        feed_root_nhash: String,
        retryable: bool,
        reason: String,
        sync_id: i64,
    },
}

pub fn encode_frame(message: &WireMessage) -> Result<Vec<u8>, NearbyHashtreeError> {
    let payload = bincode::serialize(message)
        .map_err(|e| NearbyHashtreeError::Message(format!("failed to encode wire message: {e}")))?;
    let mut bytes = Vec::with_capacity(4 + payload.len());
    bytes.extend_from_slice(&(payload.len() as u32).to_be_bytes());
    bytes.extend_from_slice(&payload);
    Ok(bytes)
}

#[derive(Debug, Default, Clone)]
pub struct FrameDecoder {
    buffer: Vec<u8>,
}

impl FrameDecoder {
    pub fn push(
        &mut self,
        bytes: &[u8],
    ) -> Result<Vec<WireMessage>, NearbyHashtreeError> {
        self.buffer.extend_from_slice(bytes);
        let mut messages = Vec::new();

        loop {
            if self.buffer.len() < 4 {
                break;
            }

            let mut prefix = [0_u8; 4];
            prefix.copy_from_slice(&self.buffer[0..4]);
            let frame_len = u32::from_be_bytes(prefix) as usize;
            if self.buffer.len() < 4 + frame_len {
                break;
            }

            let payload = self.buffer[4..4 + frame_len].to_vec();
            self.buffer.drain(0..4 + frame_len);
            let message: WireMessage = bincode::deserialize(&payload).map_err(|e| {
                NearbyHashtreeError::Message(format!("failed to decode wire message: {e}"))
            })?;
            messages.push(message);
        }

        Ok(messages)
    }
}

#[cfg(test)]
mod tests {
    use super::{encode_frame, FrameDecoder, WireMessage};

    #[test]
    fn wire_round_trip() {
        let message = WireMessage::RootAnnounce {
            feed_root_nhash: "nhash1root".to_string(),
            block_hashes: vec!["aa".to_string(), "bb".to_string()],
            entry_count: 2,
            sync_id: 9,
        };

        let bytes = encode_frame(&message).expect("frame bytes");
        let mut decoder = FrameDecoder::default();
        let decoded = decoder.push(&bytes).expect("decode");
        assert_eq!(decoded, vec![message]);
    }

    #[test]
    fn wire_partial_decode() {
        let message = WireMessage::BlockPut {
            hash_hex: "ab".repeat(32),
            bytes: vec![1, 2, 3],
            sync_id: 7,
        };
        let bytes = encode_frame(&message).expect("frame bytes");
        let split = bytes.len() / 2;

        let mut decoder = FrameDecoder::default();
        let first = decoder.push(&bytes[..split]).expect("first decode");
        assert!(first.is_empty());

        let second = decoder.push(&bytes[split..]).expect("second decode");
        assert_eq!(second, vec![message]);
    }
}
