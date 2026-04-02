# nostr-wifi-aware

`nostr-wifi-aware` is currently an Android-first local photo-sharing demo built on Wi-Fi Aware plus persistent hashtree storage.

The Wi-Fi Aware service type is `_nostrwifiaware._tcp`, using DNS-SD service-type formatting so it is compatible with Apple's Wi-Fi Aware service declaration requirements as well.

## Current Prototype

The codebase currently contains a photo-first peer-mode demo: a small Android app with two pages, `Feed` and `Settings`. `Feed` is the main consumer surface: it shows local photos plus nearby photos in one timeline and gives you a `Take Photo` button. `Settings` holds the single `Connect` / `Disconnect` toggle, reset actions, and the proof log. The app does not browse the gallery.

Use it like this:

1. Open the app on both phones.
2. On one phone, tap `Take Photo` and capture one or more photos.
3. Open `Settings` on both phones and tap `Connect`.
4. Wait for the status pills to show `Nearby on` and `1 nearby · 1 linked`. The app decides internally which phone initiates the data path.
5. Verify that the receiver accepts the sender's current feed automatically, logs receiver-side verification, and stores it without prompting.
6. Take another photo on the sender and verify that it syncs automatically after capture.
7. Open `Feed` on the receiver to confirm the nearby photos are present.

The crude split-increment plan is documented in [docs/nearby-hashtree-increments.md](/Users/l/Projects/iris/nostr-wifi-aware/docs/nearby-hashtree-increments.md).
The earlier fuller nearby sync plan is still documented in [docs/nearby-hashtree-demo-plan.md](/Users/l/Projects/iris/nostr-wifi-aware/docs/nearby-hashtree-demo-plan.md) as historical context.
The older Wi-Fi Aware bandwidth measurements are preserved in [docs/bandwidth-results.md](/Users/l/Projects/iris/nostr-wifi-aware/docs/bandwidth-results.md).
The current architecture note is documented in [docs/fips-0001-local-instagram-architecture.md](/Users/l/Projects/iris/nostr-wifi-aware/docs/fips-0001-local-instagram-architecture.md).

### Build

If you change the Rust `hashtree` bridge, rebuild the Android native libraries first:

```bash
scripts/build-rust-android.sh
```

Then build the APK:

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
- The current increment persists captured photos as hashtree blocks under `files/hashtree/blocks`, persists the current feed root under `files/hashtree/roots/local_feed_root.txt`, and syncs nearby phones by announced root plus missing block hashes.
- The app uses camera capture via intent handoff and app-private file storage. It does not read from the gallery.
- For Wi-Fi Aware to work, keep the app open on both phones.
- Wi-Fi should be on.
- Location services should be on.
- Hotspot or tethering should be off.

### Current State On This Machine

As of April 3, 2026, the current increment built successfully and was verified end to end on two USB-connected Pixel 9a devices. One phone held a local photo collection, the other was cleared to zero photos, both phones tapped `Connect` in `Settings`, and the receiver then accepted the sender's full feed automatically. After that, a newly captured photo on the sender was stored immediately in the local hashtree feed and synced automatically to the linked peer. The receiver requested only missing blocks, verified the remote root, merged the new photo entries into its own feed root, and persisted the result under `files/hashtree/blocks` plus `files/hashtree/roots/local_feed_root.txt`.

## Current Shape

The current app is intentionally narrow:

- capture photos with the camera
- ingest them into app-private persistent hashtree storage
- keep the feed itself as a persisted hashtree directory root
- exchange roots and missing blocks directly between nearby phones over Wi-Fi Aware
- verify each received root before merging it into the local feed
- render feed images from disposable cache files rehydrated from the block store

## Next Likely Steps

- make multi-peer connected-peer status more explicit in the UI when several nearby phones are linked at once
- add clearer repeated-auto-sync dedupe/no-op reporting in the UI
- add trust rules above nearby sync
- move from this demo toward a richer local social photo app
