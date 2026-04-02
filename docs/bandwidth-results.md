# Wi-Fi Aware Bandwidth Results

Date: 2026-04-02

## Test Setup

- App: `nostr-wifi-aware`
- Test mode: Wi-Fi Aware data path plus TCP socket benchmark
- Devices: 2 x Pixel 9a
- Android feature check: `android.hardware.wifi.aware` present on both devices
- Traffic pattern: one-way bulk transfer from `Client` to `Host`, plus a small ack back from `Host`

## Role Meaning

The devices are peers at the Wi-Fi Aware layer.

For this benchmark run:

- `Host` means the peer that published discovery, opened the `ServerSocket`, and received the bulk transfer
- `Client` means the peer that subscribed, connected the socket, and sent the bulk transfer

This benchmark is not a full-duplex test. It measures one heavy direction at a time.

## Measured Results

### 1 Mbit test

- Client reported: `5.46 Mbit/s`
- Host reported: `12.78 Mbit/s`

### 10 Mbit test

- Client reported: `64.84 Mbit/s`
- Host reported: `106.60 Mbit/s`

### 100 Mbit test

- Client reported: `272.95 Mbit/s`
- Host reported: `387.80 Mbit/s`

## Interpretation

- The `1 Mbit` run is dominated by fixed setup and timing overhead, so it is not representative of sustained throughput.
- The `10 Mbit` and `100 Mbit` runs are much more useful for understanding actual link performance.
- The host-side number is higher because it mainly reflects raw receive time for the payload.
- The client-side number is more conservative because it includes sender-side write cost plus the benchmark ack round-trip.

## Practical Takeaway

On this pair of Pixel 9a devices, the Wi-Fi Aware link was clearly fast enough to move:

- `1 Mbit` easily
- `10 Mbit` comfortably
- `100 Mbit` in well under a second

The sustained one-way throughput from this simple benchmark appears to be in the few-hundred-Mbit/s range on this hardware.
