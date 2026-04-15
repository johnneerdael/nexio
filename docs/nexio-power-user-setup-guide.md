# NEXIO Power-User Setup Guide

This guide is the fastest way to turn a fresh NEXIO install into a smooth, lean-back setup with better metadata, better posters, better trailer playback, and benchmark-aware autoplay.

If you only do **four** things, do these first:

1. Connect **one debrid provider**
2. Add your **TVDB** API key for TV metadata
3. Add your **TMDB** API key for movie metadata and TV fallback
4. Sign in to **Trakt**

Everything else builds on top of that.

## First launch expectation: give NEXIO a few minutes

On a fresh setup, do **not** judge NEXIO in the first minute.

When you first connect services like **Trakt** and **MDBList**, NEXIO needs time to hydrate catalogs, metadata-backed rails, and related cached startup data. During that initial population window, some rails may appear late, incomplete, or not yet fully populated.

That is expected behavior on first setup.

Why this matters:

- services like **Trakt** are rate-limited aggressively
- NEXIO is designed to restore these rails from disk cache on later startups
- after the initial hydration finishes, startup feels much faster because cached rails can appear immediately while background refresh updates the disk state

In short:

- **first setup:** allow a few minutes for initial hydration
- **later launches:** expect the app to restore much faster from disk-backed cache

If users are unaware of this first-run behavior, they can easily mistake normal initial population for a broken setup.

## What NEXIO is optimized for

NEXIO is built to shine when you combine:

- a connected debrid provider for high-quality cached playback
- **TVDB** for TV metadata when you want richer, TV-specific enrichment
- **TMDB** for movie metadata, trailers, and TV fallback when TVDB is not configured
- **Trakt** for Continue Watching, watch progress, scrobbles, personal lists, and Trakt-powered rails

Strongly recommended extras:

- **YouTube Trailer Login** for better trailer playback, including age-restricted trailers
- **MDBList** for richer external ratings
- **Poster Ratings** with **TOPPosters** or **RatingPostersDB (RPDB)** if you want rated posters
- **Service Wrap** + **Deterministic Autoplay** for the most console-like experience

---

## Before you start

Prepare these accounts or keys:

- one debrid provider: **Real-Debrid**, **Premiumize**, **TorBox**, or **EasyDebrid**
- a **TVDB** API key (recommended for TV metadata)
- a **TMDB** API key (required for movies, also serves as TV fallback)
- a **Trakt** account
- optional: **MDBList** API key
- optional: **TOPPosters** or **RPDB** API key
- optional: Google sign-in for **YouTube Trailer Login**

Helpful official links:

- **TVDB**: https://thetvdb.com/dashboard
- **TMDB**: https://developer.themoviedb.org/docs/getting-started
- **MDBList API**: https://docs.mdblist.com/docs/api
- **TOPPosters**: https://api.top-streaming.stream/
- **RPDB**: https://ratingposterdb.com/api-key/

## 1. Connect your debrid provider first

Menu path:

- **Settings → Integration → Debrid**

Supported providers in the current app:

- **Real-Debrid**
- **Premiumize**
- **TorBox**
- **EasyDebrid**

### Best practice

Connect **at least one** provider before you tune anything else.

Why:

- **Service Wrap** depends on a connected debrid provider
- **Deterministic Autoplay** depends on both **Service Wrap** and at least one completed debrid benchmark
- the best playback experience starts with cached, wrapped streams

If you use **Real-Debrid**, a conservative starting point is to prefer **3 connections** before you experiment with **4**, then let NEXIO's benchmarks make the final call for your own device and network.

For most **Real-Debrid** users, the safest recommendation is:

- **do not go above 3 parallel connections unless you have tested it yourself**

Reason:

- some Real-Debrid setups are more prone to unstable behavior at **4x**
- **3x** is the more reliable starting point for power users

---

## 2. Run the Config Benchmark before changing network settings

Menu path:

- **Settings → Integration → Debrid**
- choose your connected provider
- run **Config Benchmark**

What it does:

- compares NEXIO's optimized parallel connection and chunk-size profiles for that provider
- shows the best-performing profile on **this** device

Current benchmark matrix in NEXIO:

- **2x / 8 MB**
- **3x / 8 MB**
- **4x / 8 MB**
- **2x / 16 MB**
- **3x / 16 MB**
- **4x / 16 MB**
- **2x / 24 MB**
- **3x / 24 MB**
- **4x / 24 MB**
- **2x / 32 MB**
- **3x / 32 MB**
- **4x / 32 MB**

### Best practice

Do **not** guess your network profile if you care about large remux playback.

Run the **Config Benchmark** first, then copy the winning profile into your playback settings.

If your provider is **Real-Debrid**, treat **3x** as the preferred upper limit unless your own testing proves **4x** is stable on your setup.

---

## 3. Apply the winning profile in Network & Cache

Menu path:

- **Settings → Playback → Network & Cache**

Recommended changes:

- turn on **Parallel Connections**
- set **Connection Count** to your winning benchmark profile
- set **Chunk Size** to your winning benchmark profile
- turn on **Enable VOD Cache**
- raise **VOD Cache Size** if you have free storage

### Best practice

For higher-bitrate playback, especially remux-style movies and shows:

- start with at least **1024 MB** of **VOD Cache Size** if your device has the space
- use the exact **Connection Count** and **Chunk Size** that won your **Config Benchmark**

---

## 4. Run the full Benchmark after Network & Cache is configured

Menu path:

- **Settings → Integration → Debrid**
- run **Benchmark**

What it does:

- measures **Direct** vs **NEXIO Optimized** transport on a real provider item
- stores a benchmark result that **Deterministic Autoplay** can use later
- captures device capability snapshots used by playback scoring, including:
  - display HDR support
  - video decode capabilities
  - likely audio passthrough support

### Important

The benchmark needs a **large, playable provider item**.

If your provider library/cloud only has small files, the run may not produce a useful result. On fast connections, use a large item so NEXIO can measure the full sustained window.

### Timing expectation

A full benchmark usually takes about **4 minutes** because NEXIO measures both:

- **Direct** transport
- **NEXIO Optimized** transport

---

## 5. Enable Service Wrap and Deterministic Autoplay

Menu path:

- **Settings → Integration → Debrid**

Turn on these in order:

1. **Service Wrap**
2. **Deterministic Autoplay**

### What each setting does

**Service Wrap**

- lets NEXIO resolve eligible hash-based addon results through your connected debrid provider directly
- means you usually do **not** need to enter your debrid credentials into third-party addons for those supported wrapped flows
- keeps your debrid login inside **NEXIO**, instead of spreading it across untrusted or less-trusted addon providers
- only surfaces cached wrapped streams for supported results

**Deterministic Autoplay**

- skips manual stream picking
- auto-plays the best benchmark-aware match for your setup
- uses benchmark results plus device capability data to make the choice

This is the setting pair that gets NEXIO closest to a true lean-back, "pick and play" experience.

### Optional

You can also enable **Shadow Autoplay Data Collection** on the same screen.

### Recommended

If you use **Deterministic Autoplay**, it is worth opting in.

Why:

- it helps the NEXIO creator improve autoplay ranking and fallback behavior over time
- it gives real-world feedback from actual playback decisions instead of lab-only testing

### Privacy note

The current implementation uploads autoplay decision telemetry plus limited client metadata, including:

- app version
- build type
- device model
- Android SDK version

It does **not** upload your debrid secrets through this toggle.

---

## 6. Set up TVDB for TV metadata

Menu path:

- **Settings → Integration → TVDB**

Turn on:

- **Enable TVDB**

Then add your key in:

- **API Key**

### What TVDB does for NEXIO

When TVDB is configured, it becomes the authoritative TV metadata source. NEXIO uses TVDB for TV detail pages, episode metadata, artwork, trailers, related content, credits and cast, networks, genres, and content ratings.

TMDB remains the movie metadata source regardless of TVDB configuration. When TVDB is not configured, TMDB continues to serve TV metadata as an explicit fallback so existing setups keep working.

### Provider precedence

The provider order for metadata is:

1. **Poster-ratings** (TOPPosters or RPDB) override TVDB and TMDB poster imagery for supported titles
2. **TVDB** is the TV metadata authority when configured
3. **TMDB** remains movie metadata and explicit TV fallback when TVDB is not configured or cannot satisfy a request

This means poster-ratings integrations always win for poster artwork when they support the title, TVDB handles TV when it can, and TMDB remains available for movies and as a safety net for TV.

### Continue Watching air-time behavior

When TVDB provides both an episode aired date and a series `airsTime`, NEXIO computes an exact availability instant for Continue Watching next-up episodes. The instant is converted to your Android TV device's local timezone before visibility decisions are made.

This means new episodes appear in Continue Watching at their actual airing time, not just at the start of the aired date. Future episodes are withheld until the computed availability instant and automatically re-evaluated when that time arrives.

When `airsTime` is missing from TVDB metadata, NEXIO falls back to date-only gating. In that case, the episode becomes visible at the start of the aired date in your device timezone. Diagnostics explain when precise timing was unavailable so you know whether a date-only decision was made.

### How TVDB caching works

NEXIO keeps your TVDB metadata fresh without aggressive refetching:

- **Update-aware cache invalidation:** TVDB `/updates` signals drive cache freshness in the background. NEXIO polls for changed records periodically and at app startup, then invalidates only the affected metadata. Normal browsing and metadata reads are never blocked by update checks.
- **Stable reference-data caching:** Reference data such as artwork types, genres, languages, statuses, content ratings, season types, source types, entity types, and company types is cached heavily. This data rarely changes and is warmed when TVDB credentials first validate. Refresh failures serve last-known-good cached labels instead of showing blank metadata or raw IDs.
- **Stale-cache fallback during outages:** If TVDB is temporarily unavailable, TV detail and Continue Watching serve last-known-good TVDB data when present. NEXIO uses explicit fallback only when cached TVDB data cannot safely satisfy the surface, and records the reason. Your cached metadata stays safe even during TVDB outages.
- **Invalid credential handling:** If TVDB credentials become invalid after previously working, NEXIO keeps cached TVDB data as last-known-good, stops making new TVDB network calls until you fix the credentials, and surfaces the invalid status in settings. Do not clear your cache when you see a credential error. Fix the credentials and NEXIO will resume normal behavior.

### Where to find diagnostics

TVDB diagnostics are available in two places:

- **TVDB settings** shows a user-facing reliability status when there are issues such as invalid credentials, stale cache being served, or update refresh failures
- **Debug settings** shows detailed provider, cache, and fallback diagnostics including provider choice, fallback reasons, missing `airsTime`, date-only gating, poster-ratings overrides, skipped TMDB TV fetches, update refresh status, stale cache served, and credential status

If something looks wrong with your TV metadata, check TVDB settings first for a quick status, then Debug settings for the full picture.

---

## 7. Set up TMDB for movie metadata and TV fallback

Menu path:

- **Settings → Integration → TMDB**

Turn on:

- **Enable TMDB Enrichment**

Then add your key in:

- **API Key**

Recommended TMDB toggles to keep enabled:

- **Artwork**
- **Basic Info**
- **Details**
- **Credits**
- **Productions**
- **Networks**
- **Episodes**
- **More Like This**
- **Collections**

### Best practice

TMDB remains required for movie metadata and trailer sourcing. When TVDB is configured, TMDB also serves as the explicit TV fallback when TVDB cannot satisfy a request. If you only configure one metadata provider, make it TMDB. If you watch TV series regularly, add TVDB as well for richer TV-specific metadata and exact Continue Watching air-time behavior.

---

## 8. Sign in to YouTube Trailer Login for the best trailer experience

Menu path:

- **Settings → Integration → YouTube Trailer Login**

Use:

- **Sign In** to generate the TV code / QR flow

### Why this matters

TMDB helps NEXIO find trailers, but **YouTube Trailer Login** helps NEXIO play them more reliably inside the app, including age-restricted trailer content.

If trailers matter to you, this is worth doing.

---

## 9. Sign in to Trakt if you want NEXIO to feel "alive"

Menu path:

- **Settings → Integration → Trakt**

Why Trakt is strongly recommended:

- powers **Continue Watching** behavior
- syncs watch progress
- syncs scrobbles
- powers personal lists and Trakt rails
- improves the overall "my library follows me" feeling across devices

Recommended follow-up checks inside the Trakt screen:

- set **Continue Watching Window** to your preference
- review **Trakt Catalogs**
- optionally refresh discovery rails with the sync action

### Strong recommendation for movie and series fans

Inside **Trakt Catalogs**, make sure these built-in rails are enabled:

- **Trending Movies**
- **Trending Shows**

Then move them near the top of your Trakt catalog order.
In the current Trakt settings screen, the key step is to make sure they are **enabled** in **Trakt Catalogs**.

Why these two matter so much:

- they are some of the highest-quality discovery rails in the app for users who actively watch movies and series
- they help keep Home feeling fresh
- NEXIO also uses these stronger discovery feeds as inputs for trailer-driven experiences such as trailer and hero-style screensaver behavior

If your overall Home/catalog setup gives you ordering controls elsewhere, prioritize these rails there. In the current Android Trakt settings UI, treat **enabling them** as the must-do step.

If you skip Trakt, NEXIO still works, but the experience is noticeably less personal.

---

## 10. Add MDBList for richer ratings, not core metadata

Menu path:

- **Settings → Integration → MDBList**

Turn on:

- **Enable MDBList Ratings**
- add your **API Key**

Recommended rating sources to keep enabled:

- **Trakt**
- **IMDb**
- **TMDB**
- **Letterboxd**
- **Rotten Tomatoes**
- **Audience Score**
- **Metacritic**

### Important naming note

**MDBList is a ratings upgrade**, not the main metadata engine.

Use **TMDB** for metadata and trailer enrichment.
Use **MDBList** when you want richer external ratings and optional MDBList rails.

---

## 11. Choose your poster provider

Menu path:

- **Settings → Integration → Poster Ratings**

Current options:

- **TOPPosters**
- **RatingPostersDB (RPDB)**

Important:

- they are **mutually exclusive** in NEXIO
- you should enable **one**, not both

### Recommendation

If you want rated posters, choose whichever provider you already use elsewhere.

For new users, **TOPPosters** is the simpler first option to try. If you prefer **RPDB**, check RPDB's current key and tier rules on their official site before you commit to it.

If you do not care about rated posters, you can skip this step entirely.

---

## 12. Tidy up Player & Stream Selection

Menu path:

- **Settings → Playback → Player & Stream Selection**

Recommended toggles:

- turn on **Filter Wrong Episodes**
- turn on **Filter Wrong Movie Year**
- keep **Deduplicate Grouped Streams** enabled if you want a cleaner stream list

### Dolby Vision best practice

If you **do not** use **Deterministic Autoplay** and your display **does not** support Dolby Vision:

- turn on **Filter WEB-DL Dolby Vision**

If you **do** use **Deterministic Autoplay**:

- usually leave **Filter WEB-DL Dolby Vision** **off**
- NEXIO can keep Dolby Vision WEB-DL candidates in ranking, then probe before playback and fall back when needed on non-Dolby Vision displays

---

## 13. Leave DV7 conversion enabled unless you have a reason not to

Menu path:

- **Settings → Playback → Video & Audio**

Recommended setting:

- keep **DV7 - DV8.1 Conversion** enabled

Optional advanced toggle:

- **DV7 - Preserve Mapping**

### Best practice

For most people, the default **DV7 - DV8.1 Conversion** path is the right choice. It gives NEXIO the best chance of handling DV7 playback cleanly, with fallback behavior when needed.

Only treat **DV7 - Preserve Mapping** as an advanced experiment.

---

## 14. Enable Auto Frame Rate for serious movie playback

Menu path:

- **Settings → Playback → Video & Audio**

Recommended setting:

- set **Auto Frame Rate** to **On start/stop**

Optional:

- test **Resolution matching** too if your TV/device chain supports it cleanly

### Why this matters

If you care about high-bitrate movies, remux playback, or smooth cinematic motion, **Auto Frame Rate** is one of the most important playback settings in NEXIO.

Without AFR, some setups stay locked to **60 Hz**, which can make film content feel wrong or less smooth than it should. With AFR enabled, NEXIO can switch the display refresh rate when playback starts so movies are presented more correctly for your setup.

For power users, this is strongly recommended.

---

## 15. Enable Trailer Screensaver if you want a more premium idle experience

Menu path:

- **Settings → Layout → Trailer Screensaver** section

Turn on:

- **Trailer Screensaver**

Optional related settings live under:

- **Settings → Layout → Focused Poster**

Useful choices there:

- **Autoplay Trailer**
- **Trailer Playback Target**
- **Mute Trailer Previews**

This is not essential for playback quality, but it makes NEXIO feel much more polished.

---

## 16. Optional: keep the Universal formatter selected in the portal

If you use the NEXIO account portal, open:

- **Account Portal → Formatter**

Recommended choice:

- keep the built-in **Universal** formatter selected

Why:

- it keeps stream cards more consistent across addons
- it makes mixed addon setups easier to scan quickly

If you never use the portal, skip this.

---

## Recommended final checklist

Use this as your "done right" checklist:

- [ ] Connected one debrid provider in **Settings → Integration → Debrid**
- [ ] Ran **Config Benchmark**
- [ ] Copied the winning profile into **Settings → Playback → Network & Cache**
- [ ] Enabled **Enable VOD Cache** and increased **VOD Cache Size** if storage allows
- [ ] Ran the full **Benchmark**
- [ ] Enabled **Service Wrap**
- [ ] Enabled **Deterministic Autoplay**
- [ ] Added a **TVDB** API key for TV metadata
- [ ] Added a **TMDB** API key for movies and TV fallback
- [ ] Signed in to **YouTube Trailer Login**
- [ ] Signed in to **Trakt**
- [ ] Added **MDBList** if you want richer ratings
- [ ] Chose **TOPPosters** or **RPDB** if you want rated posters
- [ ] Enabled **Filter Wrong Episodes** and **Filter Wrong Movie Year**
- [ ] Left **DV7 - DV8.1 Conversion** enabled
- [ ] Set **Auto Frame Rate** to **On start/stop**
- [ ] Enabled **Trailer Screensaver** if you want the premium idle look

## Short version: the best NEXIO experience

For most power users, this is the sweet spot:

- **Debrid connected**
- **Config Benchmark** run
- **Network & Cache** matched to the benchmark
- **Benchmark** run
- **Service Wrap** on
- **Deterministic Autoplay** on
- **TVDB** on for TV metadata
- **TMDB** on for movies and TV fallback
- **YouTube Trailer Login** signed in
- **Trakt** signed in
- **Filter Wrong Episodes** on
- **Filter Wrong Movie Year** on
- **DV7 - DV8.1 Conversion** on
- **Auto Frame Rate** on

That combination gives you the best shot at fast startup, clean metadata, exact Continue Watching air-time behavior, reliable trailers, and the most hands-off playback experience NEXIO currently offers.
