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

Camera capture plus local hashtree-addressed photo storage.

Scope:

- replace seeding with real camera capture
- do not access the gallery
- write captured JPEGs into app-private storage
- compute a real hashtree `nhash` for every captured file
- show captured photos locally in the app

Success criteria:

- tapping `Take Photo` launches the camera app
- confirming a shot returns to the app
- the app logs the captured photo's `nhash`
- the captured file exists under app-private storage keyed by `nhash`
- the local feed shows the captured photo

## Increment 2.5

Peer-mode transport with no manual host/client UI.

Scope:

- both phones expose a single `Start Nearby` action
- under the hood, each phone publishes and subscribes at the same time
- a deterministic tie-break picks which phone initiates the Wi-Fi Aware data path
- once linked, either phone can push its current collection to the other
- nearby sync no longer depends on manual host/client selection

Success criteria:

- both phones can tap `Start Nearby` without manually choosing host or client
- logs still prove one side initiated and the other responded on the Wi-Fi Aware data path
- an empty phone can receive a nearby photo feed after both simply start nearby mode
- receiver-side `nhash` verification still happens on every transferred file

## Increment 3

Photo feed broadcast over Wi-Fi Aware.

Scope:

- two pages: `Feed` and `Settings`
- `Broadcast Photos` pushes whatever this phone currently has to every linked nearby peer
- receivers accept the photos automatically and store them after verification
- receiver stores verified photos separately from locally captured ones
- both local and nearby photos appear in one combined feed

Success criteria:

- one phone can capture a real photo
- the other phone can receive it over the Wi-Fi Aware data path
- sender and receiver log the same announced `nhash`
- receiver logs receiver-side verification and storage
- receiver feed shows the transferred photo

## Increment 4

Add tiny manifests and dedupe.

Scope:

- exchange a tiny manifest of nearby photos
- request only missing `nhash` values
- make repeat fetches a no-op when everything is already present
- make dedupe explicit in UI/logs

Success criteria:

- each phone can see the other phone's manifest summary
- one phone fetches only missing photos
- a second fetch stores nothing new and reports dedupe / already-present

## Increment 5

Add the trust gate.

Scope:

- fixed trusted `npub` list only
- signed challenge proof before manifest exchange or photo fetch
- refuse sync if the peer is not trusted

Future improvement note:

- replace fixed trusted `npub`s with social-graph-based trust later

## Increment 6

Move from the photo demo toward richer nearby content sync.

Candidates:

- better photo manifests
- richer feed metadata
- social filtering
- store-and-forward policies
- stronger sync protocol
