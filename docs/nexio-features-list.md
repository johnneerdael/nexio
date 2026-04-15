# NEXIO Features List

NEXIO is built for people who want a streaming setup that feels modern, fast, and deeply tuned to real-world playback instead of just looking good in screenshots.

This list is based on real features in the current NEXIO stack across the TV app and the account portal. It intentionally focuses on meaningful capabilities and differentiators, not simple cosmetic toggles.

## 1. Playback that is built for real-world streaming, not just manual stream picking

### Deterministic Autoplay
NEXIO can move beyond manual stream selection and choose a stream for you automatically using benchmark-aware playback intelligence.

That means it can evaluate candidates using:

- your measured network profile
- your device's decode and display capabilities
- HDR compatibility
- audio support and passthrough likelihood
- stream quality and realism signals
- transport stability and seek behavior

The goal is simple: less menu friction, better playback decisions, and a true lean-back experience.

### Service Wrap
NEXIO can resolve supported hash-based addon results through your connected debrid provider directly instead of forcing you to hand your debrid credentials to random third-party addons.

That gives users a cleaner and safer workflow:

- connect debrid once in NEXIO
- let NEXIO check cached availability directly
- surface only usable wrapped results for supported flows

### Debrid benchmarking and config benchmarking
NEXIO includes a serious debrid benchmarking system instead of treating all providers and all devices like they behave the same.

It supports:

- provider-specific connection benchmarking
- connection/chunk profile comparison
- direct vs optimized transport comparison
- stored local results per provider
- benchmark-aware playback decisions later on

This is one of NEXIO's biggest differentiators for power users who care about large files, remux playback, and device-specific tuning.

### Progressive transfer tuning
For users who want to squeeze the most out of their setup, NEXIO includes:

- parallel connections
- chunk-size tuning
- disk-backed VOD cache
- stream reuse / last-link cache
- buffering and transport diagnostics

This lets the app behave much closer to a tuned playback client than a generic stream browser.

---

## 2. Premium debrid and library integrations

NEXIO supports multiple meaningful debrid paths today, including:

- Real-Debrid
- Premiumize
- TorBox
- EasyDebrid

These are not just token fields on a settings page. NEXIO uses them for real product behavior such as:

- Service Wrap
- benchmark collection
- direct playback link resolution
- debrid-aware library behavior
- cached torrent validation

For users who build their setup around cached high-quality playback, this is core product functionality.

---

## 3. Smart stream handling instead of messy addon chaos

NEXIO puts a lot of work into making raw addon output more usable.

### Stream normalization and formatting
NEXIO supports:

- uniform stream formatting
- parsed stream metadata rendering
- cleaner stream card presentation across addons
- synced formatter selection through the portal

The result is a stream list that feels more readable and more consistent, even when results come from very different addon ecosystems.

### Stream grouping and cleanup
NEXIO also includes quality-of-life handling such as:

- grouping streams across addons into one merged view
- deduplicating grouped results
- filtering wrong episodes
- filtering wrong movie years
- filtering problematic WEB-DL Dolby Vision streams when desired

This matters because the difference between a usable setup and an annoying setup is often how well the app cleans up bad source data.

### Lean-back playback controls around autoplay
NEXIO also includes surrounding playback behaviors that make autoplay more usable in practice, such as:

- next-episode autoplay thresholds
- binge-group preferences
- stream reuse / last-link cache
- grouped and deduplicated stream handling before playback

---

## 4. Advanced video and audio features for serious playback setups

NEXIO is not limited to basic Android TV playback.

### Advanced Dolby Vision handling
NEXIO includes a substantial Dolby Vision feature set, including:

- DV7 to DV8.1 conversion
- optional preserve-mapping behavior for DV7
- automatic fallback behavior for problematic Dolby Vision scenarios

This is the kind of feature work that usually only appears in enthusiast-grade playback environments.

### HDR-aware playback behavior
NEXIO uses device capability data and HDR support awareness during scoring and playback decisions, which helps it avoid blindly preferring streams that look better on paper than they do on your actual screen.

### Kodi-style IEC packer / custom audio sink path
For advanced audio users, NEXIO includes a custom Kodi-inspired IEC packer path with support for power-user passthrough workflows, including:

- AC3 passthrough
- E-AC3 passthrough
- DTS passthrough
- TrueHD passthrough
- DTS-HD / DTS:X family passthrough
- DTS-HD core fallback
- IEC packer PCM channel layout controls
- passthrough audio delay supervision for affected firmware

This is a major differentiator for people with soundbars, AVRs, and demanding home theater setups.

Important caveat: **TrueHD is not fully implemented yet and should not be treated as fully working or production-stable today.** The IEC/Kodi-style audio path is still a real differentiator, but users should not read this as a promise of fully reliable TrueHD playback right now.

### Playback matching and transport tuning
NEXIO also includes:

- frame-rate matching
- resolution matching
- tunneled playback support
- startup frame telemetry and playback diagnostics

---

## 5. Metadata and detail enrichment that goes far beyond basic addon data

NEXIO does not stop at raw addon metadata.

### TVDB as the TV metadata authority
When TVDB is configured, it becomes the authoritative source for TV metadata. NEXIO uses TVDB for:

- TV detail pages and series information
- episode metadata including title, overview, image, runtime, aired date, and absolute numbering
- TV artwork, trailers, related content, and credits/cast
- networks, genres, and content ratings
- series air-time data for exact Continue Watching availability
- remote-ID matching for cross-provider identity without redundant TMDB lookups

TVDB replaces TMDB for TV metadata when configured. TMDB remains the movie metadata source and serves as an explicit TV fallback when TVDB is not configured or cannot satisfy a request. Normal success paths do not perform duplicate TMDB TV metadata fetches when TVDB is active.

### Provider precedence
NEXIO follows a clear metadata provider order:

1. **Poster-ratings** (TOPPosters or RPDB) override TVDB and TMDB poster imagery for supported titles
2. **TVDB** is the TV metadata authority when configured
3. **TMDB** remains movie metadata and explicit TV fallback

This means poster-ratings integrations always take priority for poster artwork, TVDB handles TV when available, and TMDB is always there for movies and as a safety net.

### TVDB reliability and caching
NEXIO keeps TVDB metadata fresh and reliable through several layers:

- **Update-aware cache invalidation:** TVDB `/updates` signals drive cache freshness. Changed records are detected in the background and only affected metadata is invalidated, without blocking normal browsing.
- **Stable reference data caching:** Reference data such as artwork types, genres, languages, statuses, content ratings, season types, source types, entity types, and company types is cached heavily and warmed when TVDB credentials first validate.
- **Stale-cache fallback:** During TVDB outages, TV detail and Continue Watching serve last-known-good cached TVDB data. Explicit fallback is used only when cached data cannot safely satisfy the surface.
- **Invalid credential handling:** If credentials become invalid, cached data remains safe, new TVDB network calls stop until credentials are fixed, and the invalid status is surfaced in settings.

### TMDB enrichment
TMDB remains the movie metadata source and provides TV fallback when TVDB is not configured. TMDB support enriches the experience with:

- artwork
- summaries and core info
- detailed metadata
- cast and crew
- production and network data
- episode metadata
- "More Like This"
- collections support
- review support

This helps NEXIO present content more like a polished media platform instead of a plain scraper shell.

### External ratings stack
NEXIO supports multiple ratings paths, including:

- MDBList ratings
- OMDB-backed IMDb episode ratings
- custom IMDb API support as a primary episode-ratings source
- RPDB posters
- TOP Posters rated artwork

That makes it possible to build a much richer detail experience than standard Stremio-style setups usually offer.

### Catalog enrichment through MDBList and Trakt
NEXIO can use both MDBList and Trakt not just for account data, but for real home-feed content, including:

- built-in Trakt rails
- Trakt popular/custom lists
- MDBList personal lists
- MDBList top-list rails

---

## 6. Trakt is deeply integrated, not bolted on

NEXIO treats Trakt as a real system component.

It supports:

- Trakt device authentication
- Continue Watching
- Up Next behavior
- watch progress sync
- scrobbling
- check-in
- watchlist integration
- personal list management
- built-in discovery rails like trending, popular, recommended, and calendar
- popular-list expansion into home rails
- watched / unwatched state flows

This makes NEXIO feel much more like a persistent personal media platform than a stateless stream launcher.

### Disk-backed startup behavior for Trakt and discovery
NEXIO also includes disk-first and startup-gated behavior for heavy Trakt-backed experiences so the app can restore useful rails from cache quickly and refresh them in the background instead of leaving users with an empty-feeling home screen at every launch.

That matters a lot because Trakt is heavily rate-limited in the real world.

---

## 7. Library and watch-state workflows that feel like a real media app

NEXIO includes a genuine library and watch-state layer instead of just a play button.

That includes:

- library navigation inside the TV app
- watchlist and personal-list integration
- library add/remove flows from detail pages
- watched / unwatched actions
- direct return-to-library playback behaviors
- debrid-aware library modes where applicable
- local and synced watch-progress persistence
- cached continue-watching startup behavior

This is how NEXIO closes the gap between enthusiast streaming and a more premium media-platform feel.

---

## 8. Trailer-first presentation and ambient browsing

NEXIO puts real effort into trailers and ambient discovery.

### Trailer playback stack
NEXIO supports:

- detail-page trailer playback
- focused-poster autoplay trailers
- trailer delay control
- hero trailer preview behavior
- season trailer and recap handling
- authenticated YouTube trailer playback through device login

### Trailer screensaver and hero-driven idle experience
NEXIO also includes a trailer-aware screensaver path and ambient browsing logic, including:

- trailer screensaver mode
- idle screensaver preparation and caching
- Trakt-powered screensaver source usage
- hero and focused-poster trailer presentation

This gives the app a much more premium, living-room-native feel.

---

## 9. Subtitle handling is much more ambitious than basic subtitle fetching

NEXIO supports a serious subtitle workflow, including:

- subtitle fetching from all compatible addons in parallel
- multiple subtitle startup strategies
- subtitle organization modes
- libass support for ASS/SSA rendering
- HDR-friendly subtitle rendering modes
- styling controls for readable TV playback
- AI subtitle translation through Google Gemini
- cached translated subtitle assets

For multilingual households and anime users especially, this is a real differentiator.

---

## 10. Anime and episode-specific quality-of-life features

NEXIO includes features that matter specifically for episodic content:

- Anime Skip integration for intro/outro skip timestamps
- Skip Intro support via intro detection services
- episode-level metadata enrichment through TMDB
- episode-level ratings paths through OMDB / IMDb integrations
- Continue Watching / Up Next workflows tuned for series playback

---

## 11. Addon ecosystem controls that go well beyond "paste a URL"

NEXIO includes a real addon-management workflow across app and portal.

### Addon management features
These include:

- install addons from manifest URLs
- enable/disable addons without removing them
- reorder addons
- parser presets for different addon ecosystems
- addon migration from Stremio or Nuvio through the portal
- server-side import that only pulls addon metadata and URLs for migration flows

### Home and rail management
NEXIO also supports meaningful catalog-layer control, including:

- home rail management
- catalog enable/disable behavior
- hero catalog selection
- account-wide catalog ordering through the portal
- account-wide hidden catalog controls

This gives users far more control over how the home experience actually feels.

---

## 12. A real account and cross-device ecosystem

NEXIO is not just a local TV app. It includes a broader account system and web portal.

### Account access and TV onboarding
Current account capabilities include:

- email/password portal access
- Google sign-in on the portal
- QR-based TV sign-in flow
- TV-first onboarding that keeps phone/browser approval in the loop
- linked-device workflows
- sync-code generation and claim flows for device linking

### Account-wide settings and integrations sync
NEXIO is designed so important integrations and behavioral settings belong to the account, not just one screen.

That includes account-scoped syncing for things like:

- debrid integrations
- TMDB / MDBList / OMDB / IMDb settings
- Anime Skip
- poster providers
- formatter selection
- catalog behavior
- stream-selection behavior

### Secure secret handling
The portal also includes secure handling for integration secrets and drafts so users can manage keys without hardwiring them into every device manually.

---

## 13. The portal is a real control plane, not just a web mirror

The NEXIO account portal is a product in its own right.

It currently provides major control surfaces for:

- addon management
- catalog and rail management
- integrations management
- secure API-key and token workflows
- Trakt and Real-Debrid device auth flows
- importer/migration workflows
- formatter selection and preview
- account security and password update flows
- TV QR approval workflows

That makes NEXIO much more usable as a multi-device ecosystem instead of a single-screen toy app.

---

## 14. Formatter sync is a real differentiator

NEXIO does not just parse streams — it gives users a way to shape how they are presented.

The current formatter system supports:

- a built-in Universal formatter
- additional built-in formatter templates
- custom formatter authoring in the portal
- formatter preview tooling
- formatter sync to playback surfaces
- richer icon/token rendering for services, codecs, HDR tags, and quality labels

For users who care about scanning streams quickly, this is much more powerful than default addon naming chaos.

---

## 15. Power-user diagnostics and validation exist throughout the stack

NEXIO includes deeper instrumentation than most apps in this category.

That includes:

- buffer and stream diagnostics
- startup frame telemetry
- native audio sink / IEC logging
- debrid benchmark result inspection
- autoplay shadow decision logging
- transport validation tooling in debug workflows
- TVDB provider, cache, and fallback diagnostics across three layers:
  - **TVDB settings** for user-facing reliability status such as invalid credentials, stale cache served, and update refresh failures
  - **Debug settings** for detailed diagnostics including provider choice, fallback reasons, missing `airsTime`, date-only gating, poster-ratings overrides, skipped TMDB TV fetches, update refresh status, stale cache served, and credential status
  - **Structured logs** for developer-level provider and cache event traces

Some of these are clearly enthusiast or debug-facing, but they reinforce the same point: NEXIO is engineered for people who actually care how playback and metadata behave.

---

## Bottom line

NEXIO is not just "an app that plays streams."

Its real feature set combines:

- benchmark-aware playback intelligence
- account-scoped integrations and sync
- advanced debrid workflows
- real Trakt depth
- TVDB-backed TV metadata with update-aware caching and stale-cache fallback
- clear provider precedence across TVDB, TMDB, and poster-ratings
- meaningful metadata enrichment for both TV and movies
- premium trailer and home-screen behavior
- enthusiast-grade audio and Dolby Vision handling
- a portal that acts as a real control plane

That combination is what makes NEXIO stand out.
