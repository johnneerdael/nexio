# App-First Docs Overhaul Design

Date: 2026-03-31

## Goal

Replace the current split between `android` and `web` product documentation with an app-first,
feature-based documentation model that reflects how Nexio is actually used:

- the TV app is the primary product experience
- the website is a companion surface that makes setup, management, and customization easier
- users should find guidance by task and outcome, not by internal product boundary

The rewrite must stay end-user focused, explain the value of features in plain language, and help
both mainstream and advanced users succeed without turning the docs into developer notes or release
notes in paragraph form.

## Current Problems

- The docs site treats the TV app and website as peers, even though the website mostly exists to
  support the app.
- The Android section is written as a screen-by-screen reference instead of a user task guide.
- Important features introduced across recent releases are either undocumented or only recoverable
  from release notes.
- Users do not get enough guidance on recommended setup order, first-run sync expectations, or
  which settings materially improve the experience.
- Advanced capabilities such as service wrap, trailer auth, Streailer fallback, formatter
  customization, and self-hosted ratings support are not explained in a way that keeps mainstream
  users oriented.

## Desired Outcome

- The docs site reads like one product with one primary use case: watch in the TV app.
- The shortest successful onboarding path is clear:
  prepare addons, integrations, and catalog views on the website, then sign in on the TV app with
  QR and allow the first sync and cache warm-up to complete.
- Ongoing usage guidance is organized by feature and user job rather than by screen silo.
- Each guide explains:
  - what the feature helps with
  - where to find or configure it
  - the recommended setup
  - advanced options when relevant
  - what to expect
  - what to check if it is not working
- Both mainstream users and advanced users can use the same docs, with advanced content presented
  as optional callouts instead of separate expert-only pages by default.

## Recommended Approach

Adopt a hybrid documentation structure:

1. a short app-first onboarding flow
2. feature-based guides for daily use, setup, tuning, and customization
3. advanced pages only for truly optional or self-hosted capabilities

This is preferable to rewriting the current `android` and `web` sections in place because the old
information architecture would keep reinforcing a product split that no longer matches the intended
user experience.

## Information Architecture

### Primary sections

- Start Here
- Watching
- Playback
- Integrations
- Customize
- Advanced
- Troubleshooting
- Developer

The website should appear inside these guides as a companion tool, not as a primary parallel
product area.

### Start Here

- Overview
- Recommended Setup
- First Run, Sync, and Cache

This section should establish the main product story:

- configure the account on the website first
- use QR sign-in on the TV app
- expect initial sync work for Trakt, catalogs, and disk-backed cache before the app feels fully
  populated

### Watching

- Home and Continue Watching
- Details, Seasons, and Watching Flow
- Trailers and Recaps

This section should explain how users browse, resume, discover, and use trailer-related features in
the TV app.

### Playback

- Playback Tuning
- Subtitles and Auto-Translate
- Audio, Codec Support, and Device Advice

This section should cover practical recommendations rather than neutral settings lists.

### Integrations

- Debrid and Service Wrap
- Library Integration
- Ratings and Metadata
- Screensaver and Idle Experience

This section should explain why integrations matter, where they are configured, and what user value
they unlock inside the TV app.

### Customize

- Catalog Views and Personalization
- Universal Formatter

This section should explain how users shape the app experience, with the website framed as the best
place to manage these controls.

### Advanced

- Options and Self-Hosting

This section should hold advanced or optional setup such as:

- Streailer fallback
- YouTube trailer device auth details
- self-hosted IMDb ratings API
- other niche opt-in workflows that should not distract the default reader path

## Page Inventory

### Pages to replace or retire

The current screen-based Android docs and the web-first onboarding pages should be replaced or
substantially rewritten because they are built on the old split:

- `docs-site/android/index.md`
- `docs-site/android/getting-started.md`
- `docs-site/android/screens/home.md`
- `docs-site/android/screens/catalog.md`
- `docs-site/android/screens/detail.md`
- `docs-site/android/screens/player.md`
- `docs-site/android/screens/settings.md`
- `docs-site/android/screens/search-and-cast.md`
- `docs-site/web/index.md`
- `docs-site/web/get-started.md`

### Pages that can remain with lighter edits

- `docs-site/index.md`
- `docs-site/web/account.md`
- `docs-site/web/security-and-data.md`

These pages can stay if their framing is updated to support the new app-first model.

### New pages to add

- `docs-site/start-here/index.md`
- `docs-site/start-here/recommended-setup.md`
- `docs-site/start-here/first-run-sync-and-cache.md`
- `docs-site/watch/home-and-continue-watching.md`
- `docs-site/watch/details-seasons-and-watching-flow.md`
- `docs-site/watch/trailers-and-recaps.md`
- `docs-site/playback/playback-tuning.md`
- `docs-site/playback/subtitles-and-auto-translate.md`
- `docs-site/playback/audio-codecs-and-device-advice.md`
- `docs-site/integrations/debrid-and-service-wrap.md`
- `docs-site/integrations/library-integration.md`
- `docs-site/integrations/ratings-and-metadata.md`
- `docs-site/integrations/screensaver-and-idle-experience.md`
- `docs-site/customize/catalog-views-and-personalization.md`
- `docs-site/customize/universal-formatter.md`
- `docs-site/advanced/options-and-self-hosting.md`
- `docs-site/troubleshooting/index.md`

## Content Scope By Guide

### Recommended Setup

This is the shortest successful first-time path and should explicitly recommend:

1. configure addons, integrations, and catalog views on the website first
2. sign in on the TV app with QR
3. wait for Trakt and other synced content to populate on first run
4. allow the disk-backed cache to warm up before judging app completeness or performance

This page should not describe the app-first path as a fully app-driven setup.

### First Run, Sync, and Cache

This page should explain normal first-run behavior, including:

- why Home may take longer to feel complete on initial login
- how disk-backed cache improves later runs
- what content may appear in phases
- what symptoms are normal versus likely misconfiguration

### Home and Continue Watching

This page should explain:

- how Continue Watching is created
- how Trakt contributes to the mixed feed
- where freshness delays can come from
- what users can do when the feed looks incomplete or stale

### Trailers and Recaps

This page should cover:

- hero autoplay trailers on Modern Home
- the trailer playback button on detail pages
- season trailers from the season action
- season recaps by long press on seasons
- YouTube login benefits, especially for age-restricted trailers
- the Google device auth flow at a user level
- the custom YT-DLP fork for ad-free trailer playback
- optional Streailer fallback, including when to enable it

### Playback Tuning

This page should include clear practical guidance on:

- VOD cache
- parallel downloading
- when changing them helps
- recommended starting points
- the warning that users should tune for real symptoms instead of toggling every option

### Subtitles and Auto-Translate

This page should cover:

- baseline subtitle setup recommendations
- how Gemini-backed subtitle auto-translate works
- where users provide the API key
- what the feature is good for
- the likely delays, quality expectations, and any cost implications

### Audio, Codec Support, and Device Advice

This page should cover:

- AV1 software decoding support
- why 4K playback is not recommended when relying on software decode
- Dolby AC4 software decoding support
- practical guidance for users on lower-powered devices or devices without hardware support

### Debrid and Service Wrap

This page should explain in plain language:

- what service wrap does
- why users can install supported addons without entering provider secrets into each addon
- which providers are supported, including Real-Debrid, Premiumize, TorBox, and EasyDebrid
- where the relevant setup lives
- what configuration is recommended for most users

### Library Integration

This page should explain:

- Premiumize library integration
- Real-Debrid library integration
- TorBox library integration
- how library content surfaces in Nexio
- how library integration changes browsing and resume behavior

### Ratings and Metadata

This page should explain:

- OMDb integration for IMDb season ratings
- the Nexio custom IMDb ratings provider
- when self-hosting is required
- where the self-hosted API lives

The self-hosted repository should be referenced as an advanced path:

`https://github.com/johnneerdael/nexio-imdbratings`

### Screensaver and Idle Experience

This page should explain:

- the modern screensaver implementation
- the value of Trakt integration for screensaver content
- the Ken Burns motion effect
- how that motion helps reduce OLED burn-in risk
- what the app does to avoid static image retention during inactivity

### Universal Formatter

This page should explain:

- what the universal formatter template changes for end users
- why consistent stream formatting improves source selection
- the uniqueness and value of custom icon capabilities
- when advanced users may want to customize the template further on the website

## Navigation Changes

The main navigation and sidebars should be updated so the docs site no longer frames the website
and TV app as equal top-level product silos.

Recommended top navigation:

- Home
- Start Here
- Watching
- Playback
- Integrations
- Customize
- Advanced
- Developer

The sidebar should reinforce this same task-based structure.

## Writing Standards

### Voice and framing

- Write from the user goal first, not from the subsystem or implementation detail.
- Lead with value, then location, then setup steps.
- Treat the TV app as the primary product experience in page framing.
- Use the website as supporting context whenever configuration is easier there.

### Audience handling

- Default the main body to mainstream users.
- Add short advanced callouts for self-hosting, custom APIs, trailer auth, fallback providers, and
  formatter customization.
- Avoid creating separate power-user-only documentation when one page can serve both audiences
  cleanly.

### Content shape

Each feature page should generally include:

- what this helps with
- where to set it up
- recommended setup
- advanced options, when relevant
- what to expect
- if this is not working
- related guides

### Language rules

- Avoid internal product or team terminology such as MVP or engineering-layer terms that do not
  help end users.
- Use release notes as source material only; the docs should read as evergreen feature guidance.
- Prefer concrete recommendations over neutral option lists.

## Risks

- Migrating away from the current navigation may temporarily break old links unless redirects or
  replacement pages are handled carefully.
- Feature-based pages can become too broad if they try to absorb every screen-level detail from the
  older docs.
- Advanced topics can still overwhelm mainstream readers if the callout discipline is not enforced.

## Implementation Notes

- Start with navigation and section scaffolding, then draft the high-value onboarding and feature
  pages before migrating lower-signal screen reference content.
- Reuse existing screenshots only when they reinforce a user task; do not keep screenshots merely to
  preserve old structure.
- Cross-link pages by user job rather than by former `web` or `android` bucket.
- Preserve the developer section as a separate maintenance-oriented area.

## Testing

Verify the overhaul with the following checks:

- The homepage and nav clearly position the TV app as the primary product.
- A new user can follow Recommended Setup without needing the old `android` versus `web` mental
  model.
- The first-run sync and disk-backed cache explanations are easy to find before troubleshooting.
- The new docs cover service wrap, debrid and library integrations, trailers, subtitle translation,
  playback tuning, ratings, screensaver behavior, and universal formatter guidance.
- Mainstream users can read the default guidance without being forced through advanced paths.
- Advanced users can still find self-hosting and optional integrations from the relevant guides.
