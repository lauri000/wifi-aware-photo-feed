# LocalGram Release To Zapstore

This project currently builds an Android APK from:

- application id: `com.lauri000.nostrwifiaware`
- version name: `0.1.0`
- version code: `1`
- repository: [github.com/lauri000/wifi-aware-photo-feed](https://github.com/lauri000/wifi-aware-photo-feed)

## 1. Build The Native Libraries

```bash
cd /Users/l/Projects/iris/nostr-wifi-aware
scripts/build-rust-android.sh --release
```

## 2. Build The Release APK

```bash
cd /Users/l/Projects/iris/nostr-wifi-aware
./gradlew assembleRelease
```

By default, Android release APK output goes under:

`app/build/outputs/apk/release/`

For this repo, the Gradle release build currently produces an unsigned APK unless you add a release signing config.

Current output from this repo:

- artifact: `app/build/outputs/apk/release/app-release-unsigned.apk`
- size: `8.4 MB`
- SHA-256: `2fd23220ca989912b2c43a856851e880deee92a84b0899cd7dc41495d6ffdbba`
- verification status: unsigned (`apksigner verify` fails with `Missing META-INF/MANIFEST.MF`)

## 3. Sign The APK

Zapstore publishing expects a signed APK and will also ask you to link the APK signing certificate to your Nostr identity on first publish.

If you already have a keystore, sign the unsigned artifact into a publishable APK, for example:

```bash
APKSIGNER="$ANDROID_HOME/build-tools/36.1.0/apksigner"

"$APKSIGNER" sign \
  --ks /absolute/path/to/your-release-key.jks \
  --out app/build/outputs/apk/release/app-release.apk \
  app/build/outputs/apk/release/app-release-unsigned.apk

"$APKSIGNER" verify --print-certs app/build/outputs/apk/release/app-release.apk
```

If you prefer, you can also generate the signed APK from Android Studio's signed build flow instead of the command line.

## 4. Create `zapstore.yaml`

Zapstore's fastest path is the publish wizard:

```bash
go install github.com/zapstore/zsp@latest
zsp publish --wizard
```

That writes a `zapstore.yaml` in the repo root. For this repo, the important fields should look like:

```yaml
repository: https://github.com/lauri000/wifi-aware-photo-feed
release_source: ./app/build/outputs/apk/release/app-release.apk
pubkey: npub1...
metadata_sources:
  - github
```

Commit `zapstore.yaml` after the wizard writes it.

## 5. Publish

After the signed APK exists and `zapstore.yaml` is committed:

```bash
cd /Users/l/Projects/iris/nostr-wifi-aware
zsp publish --wizard
```

For the first publish, Zapstore will:

- verify the repository and `zapstore.yaml`
- whitelist the repo/pubkey combination
- ask you to link the APK signing certificate to your Nostr identity

After that, later publishes are simpler as long as you keep using the same signing identity.

## Repo-Specific Release Checklist

1. Bump `versionCode` and `versionName` in [app/build.gradle.kts](/Users/l/Projects/iris/nostr-wifi-aware/app/build.gradle.kts).
2. Update [docs/release-notes-v0.1.0.md](/Users/l/Projects/iris/nostr-wifi-aware/docs/release-notes-v0.1.0.md) or create a new release-notes file for the new version.
3. Run `scripts/build-rust-android.sh --release`.
4. Run `./gradlew assembleRelease`.
5. Sign the APK.
6. Verify the signed APK.
7. Ensure `zapstore.yaml` points at the signed APK path.
8. Commit the version bump, release notes, and `zapstore.yaml`.
9. Run `zsp publish --wizard`.

## Sources

- [Android: Build your app for release to users](https://developer.android.com/build/build-for-release)
- [Android: Sign your app](https://developer.android.com/studio/publish/app-signing)
- [Zapstore: Publishing apps](https://zapstore.dev/docs/publish)
