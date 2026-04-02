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

Two tiny indexes, one per device.

Scope:

- one local index JSON on each phone
- overlap plus differences between the two indexes
- explicit `Exchange Indexes`
- explicit `Show Missing`
- explicit receiver-pull fetch of missing hashes
- dedupe by hash on repeated fetch

Success criteria:

- each phone can see the other phone's index summary
- one phone can fetch only the missing blobs
- a second fetch stores nothing new

## Increment 3

Add the trust gate.

Scope:

- fixed trusted `npub` list only
- signed challenge proof before index exchange or blob fetch
- refuse sync if the peer is not trusted

Future improvement note:

- replace fixed trusted `npub`s with social-graph-based trust later

## Increment 4

Turn the demo data into a crude audio demo.

Scope:

- deterministic generated audio files
- very small local catalog
- simple search
- simple local playback
- newly fetched tracks appear after refresh

## Increment 5

Move from the audio demo toward richer nearby content sync.

Candidates:

- better indexes
- content packs
- Nostr event segment indexes
- stronger sync protocol
