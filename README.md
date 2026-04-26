# NEXIO

NEXIO is an Android TV and Fire TV streaming app built for people who want a setup that feels premium in the living room, not just functional on paper. It combines a polished TV experience, serious playback intelligence, account-backed integrations, and a real companion portal so your setup can be faster, cleaner, and more reliable than the usual "pick a stream and hope" workflow.

## Why people choose NEXIO

NEXIO is designed for users who care about more than just opening a title and scrolling through a messy stream list.

It is built for:

- **lean-back playback** with less manual stream picking
- **high-quality debrid setups** that need real tuning instead of guesswork
- **home theater users** who care about HDR, passthrough, and device-specific behavior
- **Trakt-heavy workflows** with real Continue Watching, Up Next, and list depth
- **people who want one account-backed ecosystem** instead of scattered settings across devices and addons

## What makes NEXIO different

### Playback intelligence instead of blind stream selection
NEXIO can go beyond manual stream picking with **Deterministic Autoplay**, benchmark-aware scoring, and device-aware playback decisions.

That means playback can reflect:

- your measured network profile
- your device decode capabilities
- HDR compatibility
- audio support and passthrough likelihood
- transport stability and seek behavior
- stream realism and quality signals

This is how NEXIO gets closer to a true *pick-and-play* experience.

### Real debrid workflows, not just stored credentials
NEXIO supports meaningful debrid behavior with:

- **Real-Debrid**
- **Premiumize**
- **TorBox**
- **EasyDebrid**

These integrations power real product behavior such as:

- **Service Wrap**
- cached torrent validation
- direct playback link resolution
- debrid-aware library behavior
- benchmark-driven playback decisions

### Benchmarking and transport tuning built into the app
NEXIO includes one of its strongest differentiators for serious playback users:

- **Config Benchmark** for connection and chunk-size profiles
- **Benchmark** for Direct vs Optimized transport comparison
- **parallel connections** and **chunk-size tuning**
- **disk-backed VOD cache**
- **stream reuse / last-link cache**
- **buffering and transport diagnostics**

For large files, remux playback, and device-specific tuning, this matters a lot.

### Smarter stream cleanup before playback
NEXIO works hard to clean up messy addon output so the app feels usable instead of chaotic.

That includes:

- grouping streams across addons
- deduplicating grouped streams
- cleaner uniform stream formatting
- wrong-episode filtering
- wrong-movie-year filtering
- parser-backed stream metadata rendering
- optional WEB-DL Dolby Vision filtering when appropriate

### Dolby Vision fallback safety in autoplay
NEXIO includes **DV-aware autoplay fallback logic** for problematic WEB-DL Dolby Vision scenarios.

That means deterministic autoplay can keep a premium DV candidate ranked, probe it before final handoff, and fall back to a safer non-DV stream when the detected DV profile would create a bad playback experience on a non-DV display.

This is the kind of real-world protection that usually does **not** exist in typical Android TV media hubs.

### Advanced playback and audio-path work
NEXIO includes enthusiast-grade playback features that go far beyond basic Android TV defaults.

Highlights include:

- **DV7 to DV8.1 conversion**
- HDR-aware playback decisions
- frame-rate matching
- resolution matching
- tunneled playback support
- a custom **Kodi-inspired IEC packer / native audio sink path**
- AC3 / E-AC3 / DTS / DTS-HD / DTS:X family passthrough work
- DTS-HD core fallback
- audio delay supervision for affected firmware

Important caveat: **TrueHD should not be treated as fully reliable or production-stable today.**

ASS/SSA subtitles use a protected translation pipeline: NEXIO tokenizes ASS structure, translates only visible language text, validates placeholders, reconstructs ASS events, and renders through libass/assrender. Generic Media3 cue translation is disabled for ASS/SSA to preserve positioning, movement, drawing, and karaoke semantics.

### Deep Trakt integration
NEXIO treats Trakt as a real system layer, not a decorative add-on.

That includes:

- device authentication
- Continue Watching
- Up Next behavior
- watch progress sync
- scrobbling
- check-in
- watchlist and list workflows
- trending, popular, recommended, and calendar rails
- disk-backed startup behavior for heavy Trakt experiences

### Metadata, posters, and discovery that feel premium
NEXIO goes well beyond raw addon metadata with support for:

- **TMDB** enrichment
- **TheTVDB** enrichment
- **MDBList** ratings and lists
- **OMDb / IMDb episode ratings**
- **RPDB** and **TOP Posters**
- trailer-first browsing
- authenticated **YouTube Trailer Login**
- trailer screensaver and ambient browsing flows

### A real account portal and cross-device control plane
NEXIO is not just a local TV app.

It also includes an account system and companion portal for:

- QR-based TV sign-in
- linked-device flows
- synced integration settings
- addon management
- catalog ordering and visibility
- formatter selection
- secure secret handling
- migration and import workflows

## Best first steps

If you want the fastest way to understand what makes NEXIO work best in practice, start here:

- **Creator best-practices setup guide:** https://johnneerdael.github.io/nexio/start-here/
- **Full features overview:** https://johnneerdael.github.io/nexio/features/
- **Latest releases:** https://github.com/johnneerdael/nexio/releases
- **Stable APK (latest):** https://github.com/johnneerdael/nexio/releases/latest/download/nexio_stable.apk
- **Beta APK (latest prerelease):** https://github.com/johnneerdael/nexio/releases/download/beta/nexio_beta.apk

## Why the setup guide matters

NEXIO is at its best when you combine:

- one connected debrid provider
- built-in TMDB and TheTVDB enrichment
- Trakt sign-in
- benchmark-driven transport tuning
- Service Wrap
- Deterministic Autoplay

The creator guide walks beginners through that exact order so the app feels right much faster.

## Bottom line

NEXIO is built for users who want a modern TV experience with:

- less menu friction
- better autoplay decisions
- stronger debrid workflows
- better metadata and trailers
- deeper Trakt integration
- more serious playback tuning
- more control over how the app actually behaves in the living room

TMDB and TheTVDB are core metadata providers. Nexio includes built-in access for non-commercial app usage, and users can optionally save their own API keys to use their own provider quota.

If that is what you want, start with the setup guide above and then explore the full features overview.

## Legal

NEXIO is a client application. It does not host or distribute media content. Media access depends on user-installed addons, services, and sources the user is authorized to use.
