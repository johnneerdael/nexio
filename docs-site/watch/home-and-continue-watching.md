# Home and Continue Watching

Home is the main browsing surface in Nexio. This guide explains how it is assembled, why Continue Watching can contain mixed Trakt data, and where to look when the feed feels late or incomplete.

## What this helps with

- Resuming a movie or episode from the right place.
- Understanding why Continue Watching may combine local progress with Trakt next-up data.
- Finding the settings that shape the Home layout and its rails.
- Recognizing whether a delay comes from sync, metadata, or startup caching.

## Where to set it up

- `Settings > Layout` controls the Home style, hero section, and hero catalog selection.
- `Settings > Integration > Trakt` controls sign-in, the continue-watching window, and whether unaired next-up items are included.
- [Recommended Setup](/start-here/recommended-setup) is the right first stop when Home still looks like a first-login state.
- [First-run Sync and Cache](/start-here/first-run-sync-and-cache) explains why rows can appear in phases and why later launches usually look fuller.
- [Catalog Views and Personalization](/customize/catalog-views-and-personalization) covers catalog-backed rails, row order, and the account-level shape of Home.
- [Integrations](/integrations/) covers the account-backed services that feed Home once setup is finished.

## Recommended setup

1. Connect Trakt first so Nexio has a consistent account identity and watch-state source.
2. Choose the Home layout you actually want to use day to day.
3. Select only the hero catalogs and rails you want surfaced on the TV app.
4. Set the Trakt continue-watching window to match how much history you want to keep visible.
5. Give the first sync enough time to finish before judging the feed.

## What to expect

- Home is built from three inputs: hero items, Continue Watching, and catalog rows.
- Continue Watching is mixed. Local playback progress contributes resume items, while Trakt contributes next-up data and show-level watch context.
- Trakt next-up entries are folded into the same feed, but Nexio keeps paused in-progress episodes in the resume part of the list instead of turning them into next-up prompts.
- A feed item can appear before every source finishes refreshing, so Home may look partially complete and then fill in a moment later.
- Freshness can lag when Trakt discovery, catalog refreshes, metadata enrichment, or the startup cache are still catching up.
- Home is allowed to render as soon as it has usable content. If one source is still loading, another source can make the screen feel ready sooner.

## If this is not working

- If Home is empty, confirm that at least one catalog-enabled addon is installed and that Trakt is signed in.
- If Continue Watching is missing, check the Trakt continue-watching window and whether the episode or movie has any recent progress.
- If the feed looks stale, leave and return to Home after sync finishes, or retry after the next background refresh.
- If hero rows are missing, check the selected hero catalogs and the hero section toggle in Layout settings.

## Related guides

- [Details, Seasons, and Watching Flow](/watch/details-seasons-and-watching-flow)
- [Trailers and Recaps](/watch/trailers-and-recaps)
- [Start Here](/start-here/)
- [Playback](/playback/)
- [Catalog Views and Personalization](/customize/catalog-views-and-personalization)
- [Troubleshooting](/troubleshooting/)
