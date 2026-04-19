# Ratings and Metadata

Ratings and metadata shape how detail pages feel in Nexio. This page is the practical guide for the services that fill in title context, season and episode structure, external ratings, and list-backed discovery signals without turning setup into a developer task.

## Recommended setup

For most users, the simplest path is:

1. Use the built-in TMDB and TheTVDB metadata layer.
2. Add MDBList if you want extra ratings or list-backed inputs to appear where supported.
3. Use OMDb if you want a straightforward season and episode ratings path.
4. Ignore the self-hosted IMDb path unless you already know you want to operate it yourself.

## TMDB and TheTVDB

TMDB and TheTVDB enrichment are enabled by default. Nexio uses built-in metadata access so artwork, descriptions, cast, release details, episode data, trailers, and TV season ordering work without setup.

Optional custom API keys remain supported. When a custom key is saved, Nexio uses it before the built-in key. Removing the custom key returns the app to built-in access.

## Anime metadata IDs

Nexio can enrich anime metadata from Kitsu when a title has a Kitsu-backed ID in the bundled anime map. Direct `kitsu:{id}` IDs are resolved as Kitsu anime IDs. `mal:{id}`, `anilist:{id}`, `anidb:{id}`, `tmdb:{id}`, `tvdb:{id}`, `tt...`, and `imdb:{id}` IDs are resolved through `anime/anime-id-map.json` before Kitsu is called.

TMDB, TheTVDB, and IMDb IDs still fall back to the normal TMDB/TheTVDB metadata routes when the bundled anime map has no matching Kitsu ID. TMDB lookups use separate movie and series indexes to avoid cross-media collisions.

## Kitsu authentication

Kitsu login is optional for public metadata. When connected, Nexio uses Kitsu OAuth password grant to obtain access and refresh tokens. Username is stored only for display, tokens are stored through the account secret sync contract, and the password is never saved after the login exchange.

## MDBList ratings and list inputs

- MDBList helps Nexio surface extra ratings and list-oriented inputs where those signals are supported.
- It is useful when you want broader score coverage in hero areas or episode rows beyond the base metadata layer.
- MDBList is optional, but it is the right add-on when you want the detail flow to carry more rating context without self-hosting anything.
- If you care more about richer rankings and list signals than about basic metadata completion, MDBList is the next service to add after TMDB.

## OMDb season ratings

- OMDb is the straightforward option for IMDb season ratings and episode cards.
- It is the right choice if you want ratings to work without running your own service.
- The OMDb setting syncs through your account, so you can configure it in the TV app or the portal.

## What to expect in Nexio

- TMDB and TheTVDB handle the main enrichment layer for titles, seasons, episodes, cast, and related browsing surfaces.
- MDBList and OMDb add rating context where their inputs are enabled and supported.
- Ratings and metadata do not all come from one place, so one missing signal does not always mean the whole detail flow is broken.
- If detail pages are mostly complete but one rating source is missing, check that service first instead of resetting the full account.

## Advanced self-hosted IMDb path

- Nexio also supports a custom IMDb ratings provider for users who want to run the ratings service themselves.
- That path is documented by the [nexio-imdbratings](https://github.com/johnneerdael/nexio-imdbratings) project.
- When the custom IMDb path is active, it becomes the primary source for episode ratings and replaces the OMDb and TMDB fallback path for that view.

## Who actually needs self-hosting

- Most users do not need the self-hosted path.
- If you only want season ratings and episode ratings to show up in Nexio, the built-in metadata layer plus OMDb is usually enough.
- Self-hosting is mainly for people who want to control the ratings backend themselves, keep it behind their own infrastructure, or use the custom IMDb stack end to end.

## Where to go next

- See [Options and Self-Hosting](/advanced/options-and-self-hosting) for the long-form self-hosting walkthrough.

## If this is not working

- If season tabs, cast sections, or related-title surfaces are missing, check the metadata provider status first.
- If metadata is present but richer score signals are missing, check MDBList and OMDb before you assume the detail flow is broken.
- Start with OMDb if you only need ratings to show up and do not already run the self-hosted path.
- Check one known series or episode after saving the providers so you do not diagnose a title that lacks source data.
- If ratings or metadata are still missing, compare the detail flow in [Details, Seasons, and Watching Flow](/watch/details-seasons-and-watching-flow) before you assume playback is at fault.
- If the provider should be working but never appears, move to [Troubleshooting](/troubleshooting/).

## Related guides

- [Recommended Setup](/start-here/)
- [Details, Seasons, and Watching Flow](/watch/details-seasons-and-watching-flow)
- [Options and Self-Hosting](/advanced/options-and-self-hosting)
- [Troubleshooting](/troubleshooting/)
