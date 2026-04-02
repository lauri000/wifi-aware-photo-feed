# nostr-wifi-aware

`nostr-wifi-aware` is a simple Android-first mobile app that detects whether trusted Nostr friends are physically nearby using Wi-Fi Aware and, if so, unlocks a dataset.

## Current Prototype

The codebase currently contains a very dummy Android app for Wi-Fi Aware bandwidth testing between two Android phones.

Use it like this:

1. Open the app on both phones.
2. Tap `Start Host` on one phone.
3. Tap `Start Client` on the other phone.
4. Wait for the client log to say the TCP socket is connected.
5. Tap `1 Mbit`, `10 Mbit`, or `100 Mbit` on the client.
6. Read the measured throughput in Mbit/s from both logs.

Measured results from the first real-device run are documented in [docs/bandwidth-results.md](/Users/l/Projects/iris/nostr-wifi-aware/docs/bandwidth-results.md).
The next-step nearby content sync plan is documented in [docs/nearby-hashtree-demo-plan.md](/Users/l/Projects/iris/nostr-wifi-aware/docs/nearby-hashtree-demo-plan.md).

### Build

```bash
./gradlew assembleDebug
```

## Developing With A USB Phone

Right now this machine has the Android SDK installed at `/Users/l/Library/Android/sdk`, but `adb` is not on the shell `PATH`. The repo includes a helper script that uses that SDK path directly.

For fish shells on this machine, `adb` is now added via [`android-sdk.fish`](/Users/l/.config/fish/conf.d/android-sdk.fish) using `fish_add_path`, and `ANDROID_HOME` plus `ANDROID_SDK_ROOT` are exported from the same file.

### One-Time Phone Setup

1. On the phone, enable Developer options.
2. Enable USB debugging.
3. Plug the phone in over USB.
4. Accept the "Allow USB debugging" prompt on the phone.
5. Verify the device is visible:

```bash
/Users/l/Library/Android/sdk/platform-tools/adb devices -l
```

You want to see a line ending in `device`. If you see nothing, or `unauthorized`, fix that first before trying to install the app.

### Fast Dev Loop

Build, install, and launch:

```bash
scripts/dev-phone.sh all
```

Tail app logs:

```bash
scripts/dev-phone.sh logcat
```

You can also run the steps separately:

```bash
scripts/dev-phone.sh build
scripts/dev-phone.sh install
scripts/dev-phone.sh run
```

Keep all connected test phones awake while plugged in:

```bash
scripts/stay-awake.sh on
```

Check the current stay-awake setting on all connected phones:

```bash
scripts/stay-awake.sh status
```

### What To Expect

- The app package is `com.lauri000.nostrwifiaware`.
- The app writes its event log both to the screen and to logcat under the tag `NostrWifiAware`.
- For Wi-Fi Aware to work, keep the app open on both phones.
- Wi-Fi should be on.
- Location services should be on.
- Hotspot or tethering should be off.

### Current State On This Machine

As of April 1, 2026, `adb` was present in the SDK but your shell could not find it on `PATH`, and no USB device was visible from `adb devices -l` during my check. That means the next thing to fix is USB debugging connectivity, not the app build.

## MVP Summary

The app keeps a list of trusted friends identified by `npub`. Internally, those `npub` values are decoded to raw Nostr public keys. When the app is opened, it starts Wi-Fi Aware discovery, looks for peers running the same app, verifies whether any discovered peer controls one of the trusted Nostr keys, and loads a bundled JSON dataset if at least one trusted friend is nearby.

## Product Goal

Build the smallest useful version of a "friends nearby" app that:

- runs on Android devices with Wi-Fi Aware support
- identifies nearby friends by Nostr public key ownership
- avoids trusting unauthenticated discovery messages
- loads a dataset only after a trusted friend is verified nearby

## Non-Goals For MVP

- iOS support
- background scanning
- relay integration
- friend requests or social graph sync
- encrypted peer-to-peer dataset transfer
- precise distance measurement or geofencing
- production privacy hardening beyond basic identity verification

## Platform Choice

The first version targets Android only because Wi-Fi Aware is mature there and the API surface is straightforward enough for an MVP. iOS can be considered later as a separate phase.

## Core User Story

As a user, I can save one or more friend `npub` values in the app. When I open the app near a friend who is also running it, the app verifies that friend's Nostr key ownership and unlocks a dataset view.

## MVP Flow

1. User installs the app on Android.
2. User adds trusted friends by `npub`.
3. App decodes and stores the corresponding public keys.
4. User opens the app and taps scan.
5. App checks Wi-Fi Aware support, availability, and permissions.
6. App starts both publish and subscribe sessions on a shared service name.
7. When a peer is discovered, the app performs a lightweight handshake.
8. Peer proves control of a Nostr public key by signing a challenge.
9. App verifies the signature against the saved friend list.
10. If at least one trusted friend is verified nearby, the app loads the dataset.
11. If no trusted friend is verified, the dataset remains locked.

## Identity Model

- User input format: `npub`
- Internal storage format: 32-byte Nostr public key in hex or bytes
- Trust basis: local allowlist of saved friend public keys
- Verification method: Nostr-compatible signature over a short session challenge

The app should not trust a peer simply because it advertises a claimed `npub`. Discovery only finds a candidate peer. Trust is established only after the peer proves possession of the matching private key.

## Nearby Discovery Model

The app uses Wi-Fi Aware service discovery with a fixed service name such as `nostr-wifi-aware`. Each device can publish and subscribe at the same time so that two users running the app can discover each other without designating permanent client or server roles.

For the MVP, discovery uses the Wi-Fi Aware message channel only. A full Wi-Fi Aware data path is not required unless later versions need to transfer data between devices.

## Dataset Model

For the MVP, the dataset is a bundled JSON file shipped inside the app. This keeps the first version focused on nearby trust detection instead of server infrastructure.

Expected behavior:

- dataset stays locked by default
- dataset is loaded into memory only after friend verification succeeds
- dataset is hidden again if the session ends or the app is closed

Future versions may replace the bundled JSON with a remote fetch or peer-to-peer transfer.

## Security And Privacy Notes

- Do not broadcast private keys or raw secrets.
- Do not trust unauthenticated peer metadata.
- Use a fresh random challenge for each verification attempt.
- Keep the nearby handshake small and time-bounded.
- Treat Wi-Fi Aware discovery as proximity detection, not identity by itself.
- For MVP, it is acceptable that peers can infer that the app is running nearby.

## Android Requirements

- Android 8.0+ with Wi-Fi Aware hardware support
- runtime permission handling compatible with current Android Wi-Fi and nearby device requirements
- checks for Wi-Fi Aware feature presence and current availability
- graceful fallback UI for unsupported devices

## Technical Shape

Initial implementation should use:

- Kotlin
- Jetpack Compose
- Android Wi-Fi Aware APIs for discovery
- a small local persistence layer for saved friend keys
- a bundled JSON asset for the dataset

## First Milestones

1. Create Android app skeleton in Kotlin with Compose.
2. Add friend list storage and `npub` parsing.
3. Add Wi-Fi Aware capability and permission checks.
4. Implement publish and subscribe sessions.
5. Implement challenge-response identity verification.
6. Load and display bundled JSON after successful friend detection.
7. Add basic unsupported-device and permission-denied states.

## Open Design Decisions Deferred

- exact Nostr signing library choice
- whether to use a local app keypair or user-supplied signer for proof generation
- whether "nearby" should later mean simple discovery or measured distance
- whether future dataset loading should be local, remote, or peer-to-peer
