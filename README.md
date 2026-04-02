# nostr-wifi-aware

`nostr-wifi-aware` is currently a simple Android-first local photo-sharing demo built on Wi-Fi Aware plus hashtree-addressed storage.

The Wi-Fi Aware service type is `_nostrwifiaware._tcp`, using DNS-SD service-type formatting so it is compatible with Apple's Wi-Fi Aware service declaration requirements as well.

## Current Prototype

The codebase currently contains a photo-first peer-mode demo: a small Android app with two pages, `Config` and `Feed`. On `Config`, the app launches the camera only, keeps captured photos in app-owned hashtree-addressed storage, starts Wi-Fi Aware peer mode, fetches a nearby photo feed, and pushes its own available photos. It does not browse the gallery. On `Feed`, local photos and nearby photos are shown together in reverse chronological order.

Use it like this:

1. Open the app on both phones.
2. On one phone, tap `Take Photo` and capture one or more photos.
3. Tap `Start Nearby` on both phones.
4. Wait for the logs to show that the Wi-Fi Aware peer socket is connected. The app will decide internally which phone initiates the data path.
5. Tap `Fetch From Peer` on the other phone.
6. Verify that both phones log the same per-photo `nhash` values, and that the receiving phone logs receiver-side verification plus storage.
7. Open the `Feed` page to confirm the fetched photos are present.

The crude split-increment plan is documented in [docs/nearby-hashtree-increments.md](/Users/l/Projects/iris/nostr-wifi-aware/docs/nearby-hashtree-increments.md).
The earlier fuller nearby sync plan is still documented in [docs/nearby-hashtree-demo-plan.md](/Users/l/Projects/iris/nostr-wifi-aware/docs/nearby-hashtree-demo-plan.md) as historical context.
The older Wi-Fi Aware bandwidth measurements are preserved in [docs/bandwidth-results.md](/Users/l/Projects/iris/nostr-wifi-aware/docs/bandwidth-results.md).

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
- The current peer-mode increment proves one thing: captured photos with real hashtree `nhash` values can be fetched over a Wi-Fi Aware data path and verified on the receiving phone without manual host/client role selection.
- The app uses camera capture via intent handoff and app-private file storage. It does not read from the gallery.
- For Wi-Fi Aware to work, keep the app open on both phones.
- Wi-Fi should be on.
- Location services should be on.
- Hotspot or tethering should be off.

### Current State On This Machine

As of April 3, 2026, the current increment built successfully and was verified end to end on two USB-connected Pixel 9a devices. One phone launched the camera, captured a real JPEG, stored it under `files/demo/local/<nhash>.jpg`, and logged the computed hashtree `nhash`. Both phones then entered peer mode with `Start Nearby`, the receiver tapped `Fetch From Peer`, and the receiving phone logged both the Wi-Fi Aware transfer and receiver-side `nhash` verification before storing the photo under `files/demo/received/<nhash>.jpg`.

## Current Shape

The current app is intentionally narrow:

- capture photos with the camera
- store them in app-private hashtree-addressed storage
- exchange them directly between nearby phones over Wi-Fi Aware
- verify every received file by recomputing its `nhash`
- show both local and received photos in one simple feed

## Next Likely Steps

- add a tiny manifest so phones request only missing photos
- add explicit dedupe/no-op reporting on repeated fetches
- add trust rules above nearby sync
- move from this demo toward a richer local social photo app
