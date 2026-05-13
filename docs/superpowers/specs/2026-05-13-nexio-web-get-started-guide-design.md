# Nexio Web Get Started Guide Design

## Context

The Nexio web landing page currently sends users to the account portal or to the feature grid. It does not provide a direct installation path for users who only want to install the Android TV / Fire TV app, and it does not provide a guided setup path for the account portal configuration that powers the app.

The new guide should be public, user-facing, and written for non-technical users. It should explain installation first, then account setup and optional configuration. It should avoid internal architecture terms and focus on what users can do, why the step matters, and where to configure it.

## Goals

- Replace the landing hero actions with `Get Started` first and `Manage Account` second.
- Add a public `/get-started` route that requires no login.
- Make the first guide step useful for users who only want installation instructions.
- Present deeper setup as a polished clickthrough demo in the existing Nexio house style.
- Include screenshot templates so the page is usable before final screenshots are captured.
- Keep the guide easy to update as app screens and portal screens evolve.

## Non-Goals

- Do not redesign the account portal.
- Do not require sign-in to read installation or setup instructions.
- Do not implement real account mutations from the guide.
- Do not expose internal sync-layer or backend architecture details.
- Do not replace the landing page feature grid.

## Landing Page Changes

The landing hero should keep its current visual direction, but the call-to-action order changes:

- Primary action: `Get Started`, linking to `/get-started`.
- Secondary action: `Manage Account`, linking to `/account`.

The existing `Explore Features` hero action should be removed because users can scroll to the feature sections. The landing page remains the marketing overview, while `/get-started` becomes the practical installation and configuration path.

## Get Started Route

Add a public `/get-started` page using `PublicShell` so it shares the site navigation, signed-in awareness, and footer behavior with the existing public pages.

The page should be a single-page clickthrough wizard:

- Show one focused step at a time.
- Keep `Back` and `Next` controls fixed near the bottom center of the guide surface.
- Show a compact progress label such as `Step 1 of 10`.
- Provide a compact step index for direct jumps.
- Store the current step in local component state and reflect it in the route with a query or hash so direct links to specific steps can be shared.

Recommended route format: `/get-started?step=install`, using stable semantic step ids rather than numeric-only positions.

## Step Order

1. Install Nexio
2. Create account and sync devices
3. Create profiles
4. Configure addons
5. Connect Trakt and SIMKL
6. Configure integrations
7. Customize Modern Home rails
8. Set up Autoplay
9. Configure subtitles and auto-translate
10. Configure video caching

## Step Content

### 1. Install Nexio

Lead with installation because some users will not want the full setup guide.

Explain that Nexio can be installed with Downloader by AFTVNews. Include two linked store badges:

- Google Play badge links to `https://play.google.com/store/apps/details?id=com.esaba.downloader`.
- Amazon Appstore badge links to `https://www.amazon.com/dp/B01N0BP507/?tag=aftvn-20`.

Show the current Downloader codes prominently:

- Release: `3316080`
- Early Access: `7063421`

Keep a short note that account setup can be completed afterward and will sync configuration across devices.

### 2. Create Account and Sync Devices

Explain that a Nexio account connects the website and Android TV app. Users configure account-level features on the website, then linked devices receive the settings automatically. Mention that this is useful when Nexio is installed on multiple TVs because setup does not need to be repeated device by device.

### 3. Create Profiles

Present profiles as optional and recommended mainly for multi-person households that need separation.

Explain:

- Kids profiles can be configured around child-friendly content.
- Adult profiles can also be protected with a PIN.
- Profile pictures help identify each viewer.
- Each profile can link its own Trakt and SIMKL accounts for individual watched-state tracking.

### 4. Configure Addons

Explain that Nexio supports Stremio addons and link to `https://stremio-addons.net`. Also link to Nexio's tracked addon status page at `https://uptime.thepi.es/status/nexio`.

Cover the practical configuration concepts:

- Addons provide catalogs and stream sources.
- Dedicated anime addons can improve anime coverage.
- Parser selection changes how stream names are interpreted and grouped.

### 5. Connect Trakt and SIMKL

Explain what the integrations do:

- Scrobble watched progress.
- Fill Continue Watching.
- Power library and watched-state behavior.
- Run side by side for dual tracking.
- SIMKL is especially useful for anime tracking.

Keep setup copy focused on connecting the accounts from the portal and selecting the profile-specific or account-level behavior where applicable.

### 6. Configure Integrations

Group integrations by value rather than provider sprawl:

- Premium posters: TOP Posters and RPDB improve poster artwork with ratings and richer visual signals.
- Debrid: EasyDebrid, TorBox, Real-Debrid, and Premiumize power playback and library-oriented workflows. Keep the emphasis on what appears in Nexio, especially library view and stream availability, rather than service mechanics.
- MDBList: custom lists and ratings help shape discovery.
- AI subtitle translation: explain that users can configure translation providers for high-quality subtitle translation.

For affordable high-quality high-performance AI subtitle translation, recommend OpenRouter with:

- `google/gemini-2.5-flash-lite:nitro`
- `meta-llama/llama-4-scout:nitro`

### 7. Customize Modern Home Rails

Explain that Modern Home starts with sensible defaults and can be customized from the web portal.

Cover:

- Adding rails.
- Removing rails.
- Reordering rails.
- Choosing catalog sources that match the household's viewing habits.

### 8. Set Up Autoplay

Explain that Autoplay is enabled by default because it saves users from manually comparing streams and usually selects the best playable option automatically.

Describe the behavior in user-facing terms:

- Nexio compares available streams.
- It respects device capability, configured services, quality, and the user's autoplay budget.
- Users can tune the budget or disable Autoplay.
- Troubleshooting/data collection can be enabled when a selection looks wrong.
- The personalized device-level diagnostics link can be used to inspect why a stream was selected.

### 9. Configure Subtitles and Auto-Translate

Recommend configuring default subtitle languages early.

Explain:

- Nexio can prefer the user's chosen subtitle language.
- If auto-translate is enabled and a suitable subtitle is not available, Nexio can translate subtitles automatically.
- The feature is useful for international content and anime when exact subtitles are not available.

### 10. Configure Video Caching

Explain caching as an advanced performance tool.

Cover:

- Disk spool is recommended only with an external SD card.
- Parallel downloading can improve throughput where supported.
- VOD cache can help reduce buffering for compatible playback scenarios.

## Visual Design

The guide should follow the existing "Obsidian Lens" design language in `nexio-web/DESIGN.md`:

- Dark cinematic surfaces.
- Violet primary actions.
- Cyan as a restrained informational accent.
- Large editorial headlines.
- Glass-like panels and soft tonal layering.

The guide should avoid a plain documentation page. It should feel like an interactive product walkthrough while remaining practical on mobile and TV-sized screenshots.

Recommended layout:

- Desktop: two-column shell with a compact step index on the left and the active step on the right.
- Mobile: step index collapses into a horizontal step selector or compact menu above the active step.
- The active step contains text, action cards, and a screenshot/template frame.
- `Back` and `Next` stay centered at the bottom of the active step surface.

## Screenshot Templates

Each step should define an expected screenshot asset and a fallback template label. The template should look intentional: a dark TV or portal frame, dashed or subdued placeholder treatment, and a concise label.

Planned screenshot assets:

- `get-started-install-downloader.webp`: Downloader app install/code entry.
- `get-started-account-sync.webp`: account portal showing devices or sync.
- `get-started-profiles.webp`: profile dashboard/profile editor.
- `get-started-addons.webp`: addon manager with Stremio addon config and parser controls.
- `get-started-trakt-simkl.webp`: integration panel with Trakt/SIMKL.
- `get-started-integrations.webp`: grouped integrations overview.
- `get-started-home-rails.webp`: catalog rail reorder UI.
- `get-started-autoplay.webp`: autoplay settings and candidate diagnostics.
- `get-started-subtitles.webp`: subtitle language and translation settings.
- `get-started-cache.webp`: cache/playback performance settings.

Implementation should treat screenshots as optional data. If an asset is absent, render the template frame rather than a broken image.

## Implementation Shape

Use a data-driven page to keep the guide maintainable.

Initial implementation can keep the guide data inside `pages/get-started.vue`. If the file becomes unwieldy, move the content into `utils/get-started-guide.ts`.

Suggested data fields per step:

- `id`
- `eyebrow`
- `title`
- `summary`
- `body`
- `actions`
- `links`
- `screenshot`
- `templateLabel`

The page should include small internal rendering helpers for the step index, screenshot frame, action/link cards, and navigation controls. It should not create account-portal state or call protected account APIs.

## Accessibility and Interaction

- Use real buttons for step navigation.
- Disable or wrap `Back` and `Next` at the ends deliberately; prefer disabling `Back` on the first step and changing final `Next` to a clear terminal action such as `Open Account Portal`.
- Keep external links as anchors with `target="_blank"` and `rel="noopener noreferrer"`.
- Give store badge images useful alt text.
- Ensure the active step is announced through heading structure and visible progress text.
- Keep keyboard navigation usable through the step index and controls.

## Verification

After implementation:

- Run `npm run build` in `nexio-web`.
- Verify `/` shows `Get Started` first and `Manage Account` second.
- Verify `/get-started` loads without login.
- Verify direct step links such as `/get-started?step=addons`.
- Verify Back/Next and step-index navigation.
- Verify external links and store badge alt text.
- Check desktop and mobile widths in the browser.

