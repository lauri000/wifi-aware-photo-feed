use serde::{Deserialize, Serialize};

use crate::NearbyHashtreeError;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct WirePhoto {
    pub id: String,
    pub source_label: String,
    pub created_at_ms: i64,
    pub announced_nhash: String,
    pub mime_type: String,
    pub size_bytes: u64,
    pub bytes: Vec<u8>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum WireMessage {
    Set { label: String, count: u32 },
    Photo { photo: WirePhoto },
    Done { label: String, count: u32 },
    Ack {
        success: bool,
        actual_nhash: Option<String>,
        already_present: bool,
        message: String,
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
    use super::{encode_frame, FrameDecoder, WireMessage, WirePhoto};

    #[test]
    fn wire_round_trip() {
        let message = WireMessage::Set {
            label: "Local Instagram Feed".to_string(),
            count: 2,
        };

        let bytes = encode_frame(&message).expect("frame bytes");
        let mut decoder = FrameDecoder::default();
        let decoded = decoder.push(&bytes).expect("decode");
        assert_eq!(decoded, vec![message]);
    }

    #[test]
    fn wire_partial_decode() {
        let message = WireMessage::Photo {
            photo: WirePhoto {
                id: "photo-1".to_string(),
                source_label: "Taken Here".to_string(),
                created_at_ms: 1,
                announced_nhash: "nhash1".to_string(),
                mime_type: "image/jpeg".to_string(),
                size_bytes: 3,
                bytes: vec![1, 2, 3],
            },
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

