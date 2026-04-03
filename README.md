# LocalGram

LocalGram is an Android app for local photo sharing over Wi-Fi Aware.

It captures photos in-app with CameraX, stores them in a persistent hashtree block store, keeps the feed as a hashtree root, and syncs nearby phones by announcing roots and sending only missing blocks.

## What It Does

- takes photos without using the gallery
- stores captured photos durably in app-private hashtree storage
- rebuilds displayable images from render cache when needed
- syncs automatically with nearby peers when connected
- deduplicates by hash on the receiving side

## How To Try It

1. Install the app on two Android phones with Wi-Fi Aware support.
2. Open the app on both phones.
3. On one phone, tap `Take Photo`.
4. Tap `Connect` on both phones.
5. Wait for the header to show that the phones are linked.
6. New photos should appear on the other phone automatically after block sync completes.

## Build

If you changed the Rust core, rebuild the JNI libraries first:

```bash
scripts/build-rust-android.sh
```

Debug build:

```bash
./gradlew assembleDebug
```

Release build:

```bash
scripts/build-rust-android.sh --release
./gradlew assembleRelease
```

The Android package name is `com.lauri000.nostrwifiaware`.

## Release

Release instructions for Zapstore are in [docs/release-zapstore.md](/Users/l/Projects/iris/nostr-wifi-aware/docs/release-zapstore.md).

Draft release notes for the current version are in [docs/release-notes-v0.1.0.md](/Users/l/Projects/iris/nostr-wifi-aware/docs/release-notes-v0.1.0.md).

## Docs

- Architecture note: [docs/fips-0001-local-instagram-architecture.md](/Users/l/Projects/iris/nostr-wifi-aware/docs/fips-0001-local-instagram-architecture.md)
- Increment history: [docs/nearby-hashtree-increments.md](/Users/l/Projects/iris/nostr-wifi-aware/docs/nearby-hashtree-increments.md)
- Historical demo plan: [docs/nearby-hashtree-demo-plan.md](/Users/l/Projects/iris/nostr-wifi-aware/docs/nearby-hashtree-demo-plan.md)
