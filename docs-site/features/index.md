# Features

Nexio is more than a TV app with a play button. It combines benchmark-aware playback, account-backed integrations, advanced media handling, and a real companion portal into one streaming stack.

This page adapts the longer source-backed feature inventory into a documentation-site overview that is easier to scan while still staying grounded in the actual product.

## What makes Nexio stand out

### Playback intelligence, not just stream lists
Nexio is built around playback decisions that can reflect real device and network conditions rather than blindly trusting stream labels.

That includes:

- **Deterministic Autoplay** for lean-back playback
- **Debrid benchmarking** and **config benchmarking**
- **Direct vs optimized transport comparison**
- **Parallel connection** and **chunk-size** tuning
- **Disk-backed VOD cache**
- **Startup, buffering, and transport diagnostics**

This is a major differentiator for users who care about high-bitrate files, remux playback, and minimizing manual stream picking.

### Debrid integrations that power real behavior
Nexio supports meaningful debrid workflows with:

- **Real-Debrid**
- **Premiumize**
- **TorBox**
- **EasyDebrid**

These are not just stored credentials. Nexio uses them for:

- **Service Wrap**
- cached torrent validation
- direct link resolution
- debrid-aware library behavior
- benchmark-driven playback tuning

## Stream handling that cleans up addon chaos

Nexio includes a lot of logic to make messy source data more usable.

### Cleaner stream presentation

- uniform stream formatting
- parsed metadata rendering
- synced formatter selection through the portal
- better consistency across different addon ecosystems

### Smarter stream cleanup

- grouping streams across addons
- deduplicating grouped results
- filtering wrong episodes
- filtering wrong movie years
- optional WEB-DL Dolby Vision filtering when appropriate

This helps Nexio feel more curated and less like a raw dump of addon responses.

## Advanced video and audio features

Nexio includes enthusiast-grade playback features that go well beyond basic Android TV defaults.

### Video and display features

- **DV7 to DV8.1 conversion**
- optional **DV7 preserve-mapping**
- HDR-aware playback decisions
- frame-rate matching
- resolution matching
- tunneled playback support

### Advanced audio path

Nexio includes a custom Kodi-inspired IEC packer / native audio sink path with support for:

- AC3 passthrough
- E-AC3 passthrough
- DTS passthrough
- DTS-HD / DTS:X family passthrough
- DTS-HD core fallback
- IEC packer PCM channel layout tuning
- audio delay supervision for affected firmware

Important caveat: **TrueHD is not fully implemented yet and should not be treated as fully reliable today.**

## Metadata, ratings, and detail enrichment

Nexio goes well beyond raw addon metadata.

### Metadata enrichment

- **TMDB** artwork, summaries, details, cast, networks, episodes, collections, and related content

### Ratings and poster systems

- **MDBList** ratings
- **OMDb-backed IMDb episode ratings**
- custom **IMDb API** support
- **RPDB**
- **TOP Posters**

### Discovery rails

- built-in **Trakt** rails
- Trakt popular/custom lists
- MDBList personal and top-list rails

## Trakt is deeply integrated

Nexio treats Trakt as a real product layer, not a bolt-on.

That includes:

- device authentication
- Continue Watching
- Up Next behavior
- watch progress sync
- scrobbling
- check-in
- watchlist and personal-list flows
- trending, popular, recommended, and calendar rails

Nexio also uses disk-backed startup and refresh behavior so Trakt-heavy home experiences can restore quickly from cache while refreshing in the background.

## Trailer-first and living-room-native browsing

Nexio puts real effort into ambient discovery and trailer playback.

That includes:

- detail-page trailer playback
- focused-poster trailer autoplay
- trailer delay controls
- season trailers and recaps
- trailer screensaver mode
- idle screensaver caching and preparation
- Trakt-powered idle trailer sources

## Subtitles and translation

Nexio includes a stronger subtitle workflow than most apps in this space, with support for:

- subtitle fetching from compatible addons in parallel
- multiple subtitle startup strategies
- subtitle organization modes
- libass support
- HDR-friendly subtitle rendering modes
- **Subtitle Translation** with OpenAI-compatible, Anthropic-compatible, Google Gemini, or Alibaba DashScope providers for SRT, VTT, ASS, and SSA text subtitles
- cached translated subtitle assets

## Addons, catalogs, and home control

Nexio includes a real control layer for how content is sourced and presented.

### Addon management

- install from manifest URLs
- enable/disable without removing
- reorder addons
- parser presets for different addon ecosystems
- migration/import flows from **Stremio** or **Nuvio**

### Catalog and rail control

- hero catalog selection
- home rail ordering
- hidden catalog controls
- account-wide catalog behavior through the portal

## Account ecosystem and portal

Nexio is not only a TV app. It also includes a companion account system and portal.

### Account and device workflows

- email/password portal access
- Google sign-in on the portal
- QR-based TV sign-in
- sync-code device linking
- linked-device workflows

### Portal control plane

The companion portal provides first-class workspaces for:

- addon management
- catalog inventory
- integrations management
- formatter selection and preview
- secure secret handling
- migration flows
- account security
- TV QR approval flows

## Bottom line

What makes Nexio stand out is not one checkbox.

It is the combination of:

- benchmark-aware playback intelligence
- real debrid workflows
- advanced Dolby Vision and audio-path work
- strong Trakt depth
- meaningful metadata enrichment
- trailer-first presentation
- synchronized portal control

If you want the full setup path first, start with the [Creator Best-Practices Setup Guide](/start-here/).
