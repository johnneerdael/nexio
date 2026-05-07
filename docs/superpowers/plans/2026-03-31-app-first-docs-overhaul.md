# App-First Docs Overhaul Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild the docs site around an app-first, feature-based structure that treats the TV app as the primary product, uses the website as a companion surface, and covers the approved end-user guides for setup, watching, playback, integrations, customization, advanced options, and troubleshooting.

**Architecture:** Keep the VitePress site in one repo and migrate it in place rather than standing up a second docs tree. Build the new journey-first navigation and page set first, then rewrite old `android` and `web` entry points into bridge pages so existing URLs still guide users into the new structure without preserving the old product split.

**Tech Stack:** VitePress, Markdown, existing docs-site image assets, npm `docs:build`

---

## File Structure

**Core site shell to modify**
- Modify: `docs-site/.vitepress/config.mts`
- Modify: `docs-site/index.md`

**New Start Here pages to create**
- Create: `docs-site/start-here/index.md`
- Create: `docs-site/start-here/recommended-setup.md`
- Create: `docs-site/start-here/first-run-sync-and-cache.md`
- Create: `docs-site/start-here/account-and-sign-in.md`
- Create: `docs-site/start-here/security-and-data.md`

**New Watching pages to create**
- Create: `docs-site/watch/index.md`
- Create: `docs-site/watch/home-and-continue-watching.md`
- Create: `docs-site/watch/details-seasons-and-watching-flow.md`
- Create: `docs-site/watch/trailers-and-recaps.md`

**New Playback pages to create**
- Create: `docs-site/playback/index.md`
- Create: `docs-site/playback/playback-tuning.md`
- Create: `docs-site/playback/subtitles-and-auto-translate.md`
- Create: `docs-site/playback/audio-codecs-and-device-advice.md`

**New Integrations pages to create**
- Create: `docs-site/integrations/index.md`
- Create: `docs-site/integrations/debrid-and-service-wrap.md`
- Create: `docs-site/integrations/library-integration.md`
- Create: `docs-site/integrations/ratings-and-metadata.md`
- Create: `docs-site/integrations/screensaver-and-idle-experience.md`

**New Customize, Advanced, and Troubleshooting pages to create**
- Create: `docs-site/customize/index.md`
- Create: `docs-site/customize/catalog-views-and-personalization.md`
- Create: `docs-site/customize/universal-formatter.md`
- Create: `docs-site/advanced/index.md`
- Create: `docs-site/advanced/options-and-self-hosting.md`
- Create: `docs-site/troubleshooting/index.md`

**Legacy entry points to rewrite as bridge pages**
- Modify: `docs-site/android/index.md`
- Modify: `docs-site/android/getting-started.md`
- Modify: `docs-site/android/screens/home.md`
- Modify: `docs-site/android/screens/catalog.md`
- Modify: `docs-site/android/screens/detail.md`
- Modify: `docs-site/android/screens/player.md`
- Modify: `docs-site/android/screens/search-and-cast.md`
- Modify: `docs-site/android/screens/settings.md`
- Modify: `docs-site/web/index.md`
- Modify: `docs-site/web/get-started.md`
- Modify: `docs-site/web/account.md`
- Modify: `docs-site/web/security-and-data.md`

**Legacy admin-workspace pages to rewrite or de-emphasize**
- Modify: `docs-site/web/admin-workspaces/addons.md`
- Modify: `docs-site/web/admin-workspaces/catalogs.md`
- Modify: `docs-site/web/admin-workspaces/integrations.md`
- Modify: `docs-site/web/admin-workspaces/formatter-getting-started.md`
- Modify: `docs-site/web/admin-workspaces/formatter.md`

**Developer pages to keep linked**
- Preserve in nav/sidebar planning: `docs-site/dev/architecture.md`
- Preserve in nav/sidebar planning: `docs-site/dev/deployment.md`
- Preserve in nav/sidebar planning: `docs-site/android/technical/media3.md`
- Preserve in nav/sidebar planning: `docs-site/android/technical/ffmpeg.md`
- Preserve in nav/sidebar planning: `docs-site/android/technical/libdovi.md`
- Preserve in nav/sidebar planning: `docs-site/android/technical/iec.md`

**Verification commands**
- Verify build: `cd docs-site && npm run docs:build`
- Verify changed links and content inventory: `rg -n "/android/|/web/" docs-site --glob '*.md' --glob '!.vitepress/dist/**'`

## Task 1: Rebuild The Top-Level IA, Nav, And Homepage

**Files:**
- Modify: `docs-site/.vitepress/config.mts`
- Modify: `docs-site/index.md`
- Create: `docs-site/start-here/index.md`
- Create: `docs-site/watch/index.md`
- Create: `docs-site/playback/index.md`
- Create: `docs-site/integrations/index.md`
- Create: `docs-site/customize/index.md`
- Create: `docs-site/advanced/index.md`

- [ ] **Step 1: Rewrite the top navigation in the VitePress config**

Set `themeConfig.nav` to the approved app-first structure with explicit link targets:
- `Home` -> `/`
- `Start Here` -> `/start-here/`
- `Watching` -> `/watch/`
- `Playback` -> `/playback/`
- `Integrations` -> `/integrations/`
- `Customize` -> `/customize/`
- `Advanced` -> `/advanced/`
- `Developer` -> `/dev/architecture`

Make `Troubleshooting` sidebar- and homepage-first, not a top-nav item.

- [ ] **Step 2: Replace the sidebar groups**

Update `themeConfig.sidebar` so the primary sections are:
- `/start-here/`
- `/watch/`
- `/playback/`
- `/integrations/`
- `/customize/`
- `/advanced/`
- `/troubleshooting/`
- `/dev/`

Do not leave `web` and `android` as primary sidebar silos. Keep Android technical docs reachable under the developer-oriented area.

- [ ] **Step 3: Add the section landing pages**

Create these lightweight landing pages:
- `docs-site/start-here/index.md`
- `docs-site/watch/index.md`
- `docs-site/playback/index.md`
- `docs-site/integrations/index.md`
- `docs-site/customize/index.md`
- `docs-site/advanced/index.md`

Each page should:
- briefly explain the section purpose in app-first language
- link to the concrete guides within that section
- avoid duplicating the long-form content that belongs in the actual guide pages

`docs-site/start-here/index.md` should additionally include:
- a short explanation that Nexio is watched in the TV app
- a note that the website is the easiest place to prepare setup
- direct links to `Recommended Setup`, `First Run, Sync, and Cache`, `Account and Sign-In`, and `Security and Data`

- [ ] **Step 4: Rewrite the homepage**

Update `docs-site/index.md` so the hero and featured links:
- position the TV app as the primary product
- frame the website as the companion control surface
- point users into the new task-based sections instead of the old split

Keep the homepage short and user-facing.

- [ ] **Step 5: Run the docs build**

Run: `cd docs-site && npm run docs:build`
Expected: PASS with a generated VitePress build and no config or missing-page errors.

- [ ] **Step 6: Commit**

```bash
git add docs-site/.vitepress/config.mts docs-site/index.md docs-site/start-here/index.md docs-site/watch/index.md docs-site/playback/index.md docs-site/integrations/index.md docs-site/customize/index.md docs-site/advanced/index.md
git commit -m "docs: add app-first docs navigation shell"
```

## Task 2: Build The Start Here Flow And Absorb Account/Security Content

**Files:**
- Create: `docs-site/start-here/recommended-setup.md`
- Create: `docs-site/start-here/first-run-sync-and-cache.md`
- Create: `docs-site/start-here/account-and-sign-in.md`
- Create: `docs-site/start-here/security-and-data.md`
- Modify: `docs-site/android/getting-started.md`
- Modify: `docs-site/web/get-started.md`
- Modify: `docs-site/web/account.md`
- Modify: `docs-site/web/security-and-data.md`

- [ ] **Step 1: Draft `Recommended Setup`**

Write the new page with these headings:
- `# Recommended Setup`
- `## What this helps with`
- `## Before you sign in on the TV`
- `## Recommended order`
- `## What to expect on first login`
- `## If setup looks incomplete`
- `## Related guides`

The body must explicitly recommend:
1. set up addons, integrations, and catalog views on the website first
2. sign in on the TV app with QR
3. wait for Trakt and catalog sync
4. allow disk-backed cache warm-up before judging completeness

- [ ] **Step 2: Draft `First Run, Sync, and Cache`**

Write the new page with explanations for:
- phased first-run loading
- why Home can fill in over time
- what the disk-backed cache improves on later runs
- symptoms that are normal versus symptoms that likely indicate misconfiguration

- [ ] **Step 3: Draft `Account and Sign-In` and `Security and Data`**

Move account and sign-in guidance into `Start Here`, and rewrite security/data content so it fits the new IA instead of a web silo.

Use these sections on both pages:
- `## What this helps with`
- `## Where you do this`
- `## Recommended setup`
- `## Advanced notes`, only if genuinely needed
- `## Related guides`

- [ ] **Step 4: Rewrite the old get-started and account pages as bridge pages**

Update these four legacy pages so they:
- explain that the guidance moved
- briefly describe the new page that replaces them
- link directly to the new `Start Here` destinations

Do not keep the old silo framing alive with long-form duplicate content.

- [ ] **Step 5: Run the docs build**

Run: `cd docs-site && npm run docs:build`
Expected: PASS with all new Start Here pages linked and renderable.

- [ ] **Step 6: Commit**

```bash
git add docs-site/start-here/recommended-setup.md docs-site/start-here/first-run-sync-and-cache.md docs-site/start-here/account-and-sign-in.md docs-site/start-here/security-and-data.md docs-site/android/getting-started.md docs-site/web/get-started.md docs-site/web/account.md docs-site/web/security-and-data.md
git commit -m "docs: add app-first start here guides"
```

## Task 3: Write The Watching Guides And Retire Screen-By-Screen App Entry Pages

**Files:**
- Create: `docs-site/watch/home-and-continue-watching.md`
- Create: `docs-site/watch/details-seasons-and-watching-flow.md`
- Create: `docs-site/watch/trailers-and-recaps.md`
- Modify: `docs-site/android/index.md`
- Modify: `docs-site/android/screens/home.md`
- Modify: `docs-site/android/screens/catalog.md`
- Modify: `docs-site/android/screens/detail.md`

- [ ] **Step 1: Draft `Home and Continue Watching`**

Cover:
- how the feed is created
- the role of mixed Trakt continue-watching data
- where delays can come from
- where users manage the inputs that shape Home

Use the standard guide structure:
- `## What this helps with`
- `## Where to set it up`
- `## Recommended setup`
- `## What to expect`
- `## If this is not working`
- `## Related guides`

- [ ] **Step 2: Draft `Details, Seasons, and Watching Flow`**

Cover:
- play and resume entry points
- season navigation
- where season trailers appear
- where season recaps appear
- how ratings and metadata show up in the detail flow

- [ ] **Step 3: Draft `Trailers and Recaps`**

Cover:
- hero autoplay trailers on Modern Home
- the trailer button on detail pages
- season trailers
- season recaps by long press
- YouTube login benefits, especially for age-restricted trailers
- the Google device auth flow at a user level
- the custom YT-DLP fork for ad-free trailer playback
- the optional Streailer fallback
- the app-first recommendation versus advanced fallback setup

Keep long-form optional auth or fallback instructions out of this page and link to `Advanced` for those.

- [ ] **Step 4: Rewrite the old Android overview and screen pages as bridge pages**

Update the old Android overview and screen pages so they:
- state that the docs have moved to feature-based guides
- link to the specific new pages that replace their old purpose
- avoid duplicating the new long-form content

Keep the bridge pages concise so old links still help users.

- [ ] **Step 5: Run the docs build**

Run: `cd docs-site && npm run docs:build`
Expected: PASS with the Watching section and bridge pages rendering cleanly.

- [ ] **Step 6: Commit**

```bash
git add docs-site/watch/home-and-continue-watching.md docs-site/watch/details-seasons-and-watching-flow.md docs-site/watch/trailers-and-recaps.md docs-site/android/index.md docs-site/android/screens/home.md docs-site/android/screens/catalog.md docs-site/android/screens/detail.md
git commit -m "docs: add watching guides and bridge old app pages"
```

## Task 4: Write The Playback Guides And Replace Old Player/Settings References

**Files:**
- Create: `docs-site/playback/playback-tuning.md`
- Create: `docs-site/playback/subtitles-and-auto-translate.md`
- Create: `docs-site/playback/audio-codecs-and-device-advice.md`
- Modify: `docs-site/android/screens/player.md`
- Modify: `docs-site/android/screens/settings.md`
- Modify: `docs-site/android/screens/search-and-cast.md`

- [ ] **Step 1: Draft `Playback Tuning`**

Cover:
- VOD cache
- parallel downloading
- what problems they solve
- recommended starting points
- the warning not to tune blindly

Include a clear recommendation section for mainstream users.

- [ ] **Step 2: Draft `Subtitles and Auto-Translate`**

Cover:
- baseline subtitle recommendations
- Gemini-backed auto-translate
- where users add the API key
- what delays, quality limits, or cost expectations they should understand

- [ ] **Step 3: Draft `Audio, Codec Support, and Device Advice`**

Cover:
- AV1 software decoding support
- why 4K is not recommended without hardware decoding
- Dolby AC4 software decoding support
- practical device-level guidance for weaker hardware

- [ ] **Step 4: Rewrite the old player/settings/search pages as bridge pages**

Update the old pages so they point users to the new playback guides and any remaining relevant guides, without preserving the old screen-by-screen structure as the main documentation model.

- [ ] **Step 5: Run the docs build**

Run: `cd docs-site && npm run docs:build`
Expected: PASS with the Playback section rendering and no missing links from the bridge pages.

- [ ] **Step 6: Commit**

```bash
git add docs-site/playback/playback-tuning.md docs-site/playback/subtitles-and-auto-translate.md docs-site/playback/audio-codecs-and-device-advice.md docs-site/android/screens/player.md docs-site/android/screens/settings.md docs-site/android/screens/search-and-cast.md
git commit -m "docs: add playback guidance and bridge legacy player pages"
```

## Task 5: Write The Integrations Guides

**Files:**
- Create: `docs-site/integrations/index.md`
- Create: `docs-site/integrations/debrid-and-service-wrap.md`
- Create: `docs-site/integrations/library-integration.md`
- Create: `docs-site/integrations/ratings-and-metadata.md`
- Create: `docs-site/integrations/screensaver-and-idle-experience.md`
- Modify: `docs-site/web/admin-workspaces/integrations.md`

- [ ] **Step 1: Draft the integrations pages**

Write the four integration guides with approved scope:
- `Debrid and Service Wrap`: explain supported providers including Real-Debrid, Premiumize, TorBox, and EasyDebrid, the secret-free addon benefit of service wrap, where setup happens, and recommended defaults
- `Library Integration`: explain Premiumize, Real-Debrid, and TorBox library behavior
- `Ratings and Metadata`: explain OMDb season ratings, reference `https://github.com/johnneerdael/nexio-imdbratings`, explain when most users do and do not need the self-hosted IMDb API path, and link to `Advanced` for the long-form self-hosting walkthrough
- `Screensaver and Idle Experience`: explain the modern screensaver, Trakt benefit, Ken Burns motion, and OLED burn-in mitigation

- [ ] **Step 2: Rewrite the old web integration page as a support reference**

Update the old web integration page so it:
- adopt app-first framing
- points readers to the new integrations guides first
- keeps only the narrow portal-specific integration reference content that still adds value
- avoids reading like a parallel product handbook

- [ ] **Step 3: Run the docs build**

Run: `cd docs-site && npm run docs:build`
Expected: PASS with the new Integrations section reachable from the nav.

- [ ] **Step 4: Commit**

```bash
git add docs-site/integrations/index.md docs-site/integrations/debrid-and-service-wrap.md docs-site/integrations/library-integration.md docs-site/integrations/ratings-and-metadata.md docs-site/integrations/screensaver-and-idle-experience.md docs-site/web/admin-workspaces/integrations.md
git commit -m "docs: add integration guides"
```

## Task 6: Write The Customize And Advanced Guides And Rewrite Web Portal References

**Files:**
- Create: `docs-site/customize/index.md`
- Create: `docs-site/advanced/index.md`
- Create: `docs-site/customize/catalog-views-and-personalization.md`
- Create: `docs-site/customize/universal-formatter.md`
- Create: `docs-site/advanced/options-and-self-hosting.md`
- Modify: `docs-site/web/admin-workspaces/addons.md`
- Modify: `docs-site/web/admin-workspaces/catalogs.md`
- Modify: `docs-site/web/admin-workspaces/formatter-getting-started.md`
- Modify: `docs-site/web/admin-workspaces/formatter.md`
- Modify: `docs-site/web/index.md`

- [ ] **Step 1: Draft the customize pages**

Write:
- `Catalog Views and Personalization` for catalog ordering, home shaping, and browsing behavior
- `Universal Formatter` for the new formatter template, stream-card value, and custom icon capabilities

Keep these pages end-user focused even when the website is the primary place to configure them.

- [ ] **Step 2: Draft the Advanced page**

Write `docs-site/advanced/options-and-self-hosting.md` as the long-form optional companion page for:
- Streailer fallback details
- YouTube trailer login walkthrough detail
- self-hosted IMDb ratings API detail

Do not repeat feature introductions already covered in the main guides.

- [ ] **Step 3: Rewrite the old web landing page and admin-workspace pages as support references**

Update the old web landing page and the add-on, catalog, and formatter admin-workspace pages so they:
- adopt app-first framing
- point readers to the new feature guides first
- keep only the narrow portal-specific reference content that still adds value
- avoid reading like a parallel product handbook

- [ ] **Step 4: Run the docs build**

Run: `cd docs-site && npm run docs:build`
Expected: PASS with the new Customize and Advanced sections reachable from the nav.

- [ ] **Step 5: Commit**

```bash
git add docs-site/customize/index.md docs-site/customize/catalog-views-and-personalization.md docs-site/customize/universal-formatter.md docs-site/advanced/index.md docs-site/advanced/options-and-self-hosting.md docs-site/web/admin-workspaces/addons.md docs-site/web/admin-workspaces/catalogs.md docs-site/web/admin-workspaces/formatter-getting-started.md docs-site/web/admin-workspaces/formatter.md docs-site/web/index.md
git commit -m "docs: add customization guides and portal references"
```

## Task 7: Add Troubleshooting, Clean Up Internal Links, And Run Final Verification

**Files:**
- Create: `docs-site/troubleshooting/index.md`
- Modify: `docs-site/.vitepress/config.mts`
- Modify: `docs-site/start-here/index.md`
- Modify: `docs-site/start-here/recommended-setup.md`
- Modify: `docs-site/start-here/first-run-sync-and-cache.md`
- Modify: `docs-site/start-here/account-and-sign-in.md`
- Modify: `docs-site/start-here/security-and-data.md`
- Modify: `docs-site/watch/home-and-continue-watching.md`
- Modify: `docs-site/watch/details-seasons-and-watching-flow.md`
- Modify: `docs-site/watch/trailers-and-recaps.md`
- Modify: `docs-site/playback/playback-tuning.md`
- Modify: `docs-site/playback/subtitles-and-auto-translate.md`
- Modify: `docs-site/playback/audio-codecs-and-device-advice.md`
- Modify: `docs-site/integrations/debrid-and-service-wrap.md`
- Modify: `docs-site/integrations/library-integration.md`
- Modify: `docs-site/integrations/ratings-and-metadata.md`
- Modify: `docs-site/integrations/screensaver-and-idle-experience.md`
- Modify: `docs-site/customize/catalog-views-and-personalization.md`
- Modify: `docs-site/customize/universal-formatter.md`
- Modify: `docs-site/advanced/options-and-self-hosting.md`

- [ ] **Step 1: Draft the troubleshooting page**

Write `docs-site/troubleshooting/index.md` around real user symptoms:
- empty or incomplete Home after first login
- missing Continue Watching content
- trailer playback issues
- subtitle translation not working
- ratings not showing
- poor playback on unsupported devices

Each symptom should point back to the relevant guide and give a short first action.

- [ ] **Step 2: Add cross-links from the feature guides**

Update the new pages so `Related guides` sections link across tasks instead of old product silos. At minimum:
- Start Here links into Watching and Troubleshooting
- Watching links into Playback, Integrations, and Advanced where relevant
- Playback links into Troubleshooting
- Integrations link back to Recommended Setup, Troubleshooting, and Advanced where self-hosting or optional setup is relevant
- Customize links back to Recommended Setup and Troubleshooting

- [ ] **Step 3: Check for stale internal references to the old siloed IA**

Run: `rg -n "/android/|/web/" docs-site --glob '*.md' --glob '!.vitepress/dist/**'`
Expected: only intentional bridge-page references and old legacy paths that still exist on purpose.

- [ ] **Step 4: Run the final docs build**

Run: `cd docs-site && npm run docs:build`
Expected: PASS with no missing-page, broken-config, or Markdown rendering failures.

- [ ] **Step 5: Spot-check the generated site**

Inspect the build output locally enough to verify:
- the top nav reflects the new IA
- the sidebar is task-based
- bridge pages lead users into the new structure
- Troubleshooting is present in the sidebar but not the top nav
- first-run and disk-backed cache guidance are easy to find from `Start Here` before a user has to enter Troubleshooting
- mainstream guidance remains readable without forcing users into `Advanced` for the default path

- [ ] **Step 6: Commit**

```bash
git add docs-site/start-here docs-site/watch docs-site/playback docs-site/integrations docs-site/customize docs-site/advanced docs-site/troubleshooting docs-site/index.md docs-site/.vitepress/config.mts
git commit -m "docs: finish app-first docs overhaul"
```

## Notes For Execution

- Prefer updating the nav and skeleton pages first so later content work always has valid destinations to link to.
- Reuse existing screenshots only when they strengthen a user task explanation. Do not preserve an old page just to preserve an image.
- Keep the copy user-facing. Avoid release-note phrasing such as `Added in v0.31` unless a version note is truly necessary.
- When rewriting legacy pages into bridge pages, keep them short and explicit so old bookmarks remain useful without preserving the old mental model.
- Use `superpowers:verification-before-completion` before claiming the rewrite is done.
