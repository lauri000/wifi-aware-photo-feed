# Nearby Hashtree Increments

Date: 2026-04-02

This is the split version of the nearby hashtree plan. Keep each increment shippable and verifiable.

## Increment 1

Wi-Fi Aware hashtree blob transfer only.

Scope:

- keep the current `Host` / `Client` Wi-Fi Aware transport split
- seed one deterministic demo blob on the client
- compute a real hashtree `nhash` for that blob
- send the blob over the Wi-Fi Aware data path to the host
- recompute `nhash` on the host
- only count success if the host-side `nhash` matches the advertised one and the verified blob is stored locally

Success criteria:

- both phones show the Wi-Fi Aware data path in logs
- the client logs the sent `nhash`
- the host logs the received `nhash`
- the host logs that the computed `nhash` matched and the blob was stored

Out of scope:

- trust / `npub` handshake
- indexes
- missing-set computation
- audio player
- search

## Increment 2

Two deterministic seeded audio sets transferred over Wi-Fi Aware.

Scope:

- keep the current `Host` / `Client` Wi-Fi Aware transport split
- add explicit `Seed Set A` and `Seed Set B` buttons
- generate a tiny deterministic local audio set on-device for each seed choice
- compute a real hashtree `nhash` for every seeded audio file
- send the currently seeded set over the Wi-Fi Aware data path to the host
- recompute and verify every received file by `nhash` on the host
- store verified received files locally and show them in the UI/log

Success criteria:

- `Seed Set A` creates the expected local audio files and logs their `nhash` values
- `Seed Set B` creates the expected local audio files and logs their `nhash` values
- the client logs that seeded tracks are being sent over the Wi-Fi Aware data path
- the host logs that each received track was verified by recomputing the same `nhash`
- the host stores the verified received files locally

## Increment 3

Add tiny manifests and dedupe.

Scope:

- exchange a tiny manifest for the currently seeded set
- explicit `Show Missing`
- explicit receiver-pull fetch of only missing hashes
- no-op on repeated fetch when everything is already present

Success criteria:

- each phone can see the other phone's manifest summary
- one phone can fetch only the missing files
- a second fetch stores nothing new and reports dedupe / already-present

## Increment 4

Add the trust gate.

Scope:

- fixed trusted `npub` list only
- signed challenge proof before manifest exchange or blob fetch
- refuse sync if the peer is not trusted

Future improvement note:

- replace fixed trusted `npub`s with social-graph-based trust later

## Increment 5

Turn the demo data into a crude audio demo.

Scope:

- deterministic generated audio files
- very small local catalog
- simple search
- simple local playback
- newly fetched tracks appear after refresh

## Increment 6

Move from the audio demo toward richer nearby content sync.

Candidates:

- better indexes
- content packs
- Nostr event segment indexes
- stronger sync protocol
