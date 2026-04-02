# Nearby Hashtree Demo Plan

Date: 2026-04-02

## Goal

Build a clear two-phone demo showing that nearby trusted devices can exchange content metadata and content blobs over Wi-Fi Aware, deduplicate by hash, and fill local storage with newly discovered files.

This is a demo plan, not the final city-scale architecture.

## Scope

In scope for the first nearby hashtree demo:

- two Android phones
- Wi-Fi Aware transport
- fixed trusted `npub` allowlist
- one local content index per device
- exchange of index summaries between the two devices
- transfer of missing blobs by hash
- local deduplication by content hash
- explicit UI buttons for each demo step

Out of scope for this phase:

- social graph sync or trust-distance logic
- passive syncing from arbitrary passersby
- background syncing
- relay integration
- multi-hop forwarding
- encrypted sharing policies beyond the current trusted-device gate

Future improvement:

- replace the fixed trusted `npub` list with social-graph-based trust and replication policy

## Demo Story

The demo should tell a simple story:

1. Phone A starts with one local index and some files.
2. Phone B starts with a different local index and some different files.
3. The phones connect over Wi-Fi Aware.
4. They exchange compact index summaries.
5. Phone A sees that Phone B has files it does not have.
6. Phone A requests the missing blobs by hash.
7. Phone A stores the received blobs locally.
8. If the same blob is requested again, it is deduplicated and not stored twice.
9. The UI clearly shows what was already present, what was missing, what was fetched, and what was deduplicated.

## Data Model For The Demo

The first version should keep the model intentionally small and explicit.

Each device has:

- a fixed device identity
- a fixed trusted-peer allowlist of `npub`s
- a local blob store keyed by hash
- a local demo index file describing known files

Suggested demo file record shape:

```json
{
  "id": "track-001",
  "name": "Track 001",
  "mimeType": "audio/mpeg",
  "size": 123456,
  "cid": {
    "hash": "..."
  }
}
```

Suggested per-device index shape:

```json
{
  "version": 1,
  "deviceLabel": "phone-a",
  "generatedAt": "2026-04-02T00:00:00Z",
  "files": [
    { "...": "..." }
  ]
}
```

For the first demo, this can be a plain JSON index instead of a full B-tree search index. The important part is that it references blobs by hash and can be compared across devices.

## Why Two Indexes First

Starting with one index per device gives a much cleaner demo than trying to sync raw Nostr events immediately.

Benefits:

- easy to understand on screen
- easy to seed with known differences
- easy to verify deduplication
- easy to expand later into richer hashtree roots or event segment indexes

This first demo should prove the core primitive:

- nearby peer announces content inventory
- receiver computes missing hashes
- receiver fetches only missing blobs

Once that works, the same primitive can sit under richer indexes later.

## Suggested Demo Seed Data

Prepare two small datasets with overlap:

- Phone A files:
  - `shared-001`
  - `shared-002`
  - `a-only-001`
  - `a-only-002`
- Phone B files:
  - `shared-001`
  - `shared-002`
  - `b-only-001`
  - `b-only-002`

This gives the demo all three cases:

- already present on both sides
- missing locally but present remotely
- local-only content

## Minimal Protocol

The first version does not need a complicated protocol.

Suggested flow:

1. Device handshake
   - verify the remote `npub` is on the trusted allowlist
2. Index summary exchange
   - send compact metadata:
   - index version
   - number of files
   - list of file hashes or file hash summary entries
3. Missing set computation
   - compare remote hashes against local hashes
4. Blob request
   - request missing blob hashes
5. Blob transfer
   - send raw bytes for requested blobs
6. Store and verify
   - hash the bytes
   - verify the hash matches the requested hash
   - store only if missing locally
7. Rebuild or refresh local index view

For the demo, the simplest protocol is enough:

- `HELLO`
- `INDEX_SUMMARY`
- `REQUEST_BLOBS`
- `BLOB`
- `SYNC_DONE`

## UI Plan

The demo should use explicit buttons so each step is visible and reproducible.

Suggested buttons:

- `Seed Phone A Demo Data`
- `Seed Phone B Demo Data`
- `Start Host`
- `Start Client`
- `Exchange Indexes`
- `Show Missing Files`
- `Fetch Missing Files`
- `Verify Dedup`
- `Refresh Local View`
- `Clear Demo Data`

If the current host/client split remains, the benchmark transport roles can stay underneath, but the UI should describe the sync roles in demo language rather than networking language.

Suggested readouts:

- trusted peer connected or not
- local file count
- remote file count
- missing file count
- fetched blob count
- deduplicated blob count
- local storage bytes before and after

## Demo Sequence

Recommended live demo sequence:

1. Clear demo data on both phones.
2. Seed Phone A demo data on Phone A.
3. Seed Phone B demo data on Phone B.
4. Show each phone’s local index on screen.
5. Start Wi-Fi Aware connection between the phones.
6. Tap `Exchange Indexes`.
7. Tap `Show Missing Files` on one phone.
8. Tap `Fetch Missing Files`.
9. Show that local storage now contains the newly received files.
10. Tap `Fetch Missing Files` again.
11. Show that no new storage is consumed and the app reports deduplication / no-op.

## Deduplication Requirement

The demo only counts as successful if the app can prove deduplication clearly.

Required observable behavior:

- files already present are not downloaded again
- blobs already in local storage are not written twice
- a second fetch attempt after sync reports zero new blobs stored

## Architecture Notes

For the first demo, do not over-generalize.

Prefer:

- one in-app local blob store
- one in-app local JSON index per device
- one nearby sync session at a time
- one trusted peer at a time

Avoid for now:

- full hashtree publish/resolve flow
- remote Blossom compatibility layer
- generic event sync engine
- multiple concurrent peers

## Relationship To The Long-Term Goal

This demo is a stepping stone toward the city-scale vision:

- today: one trusted nearby peer, explicit sync, fixed indexes
- later: many peers, passive discovery, social-graph gating, richer content indexes, and store-and-forward behavior

The important part is to get the nearby content-addressed transfer primitive right first.

## Likely Next Implementation Steps

1. Add a tiny local blob store abstraction in the app.
2. Add a tiny local demo index abstraction in the app.
3. Add seed buttons that generate deterministic demo data per phone.
4. Add message types for exchanging index summaries.
5. Add missing-hash computation.
6. Add blob request and blob transfer over the existing Wi-Fi Aware socket.
7. Add on-screen stats for fetched vs deduplicated blobs.
8. Run the demo across the two connected phones and document the result.
