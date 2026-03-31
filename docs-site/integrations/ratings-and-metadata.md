# Ratings and Metadata

Ratings and metadata shape how detail pages feel in Nexio. This page is the practical guide for the services that fill in title context, season and episode structure, external ratings, and list-backed discovery signals without turning setup into a developer task.

## Recommended setup

For most users, the simplest path is:

1. Keep TMDB enabled for the main metadata layer.
2. Add MDBList if you want extra ratings or list-backed inputs to appear where supported.
3. Use OMDb if you want a straightforward season and episode ratings path.
4. Ignore the self-hosted IMDb path unless you already know you want to operate it yourself.

## TMDB metadata enrichment

- TMDB is the main metadata enrichment path for title, season, cast, collection, and related-detail surfaces.
- It is the service that makes detail pages feel complete instead of bare stream entries.
- If season tabs, richer artwork, related titles, or cast context are missing, TMDB is the first thing to check.
- Most users should leave TMDB on because it is the default-friendly path for the detail experience.

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

- TMDB handles the main enrichment layer for titles, seasons, episodes, cast, and related browsing surfaces.
- MDBList and OMDb add rating context where their inputs are enabled and supported.
- Ratings and metadata do not all come from one place, so one missing signal does not always mean the whole detail flow is broken.
- If detail pages are mostly complete but one rating source is missing, check that service first instead of resetting the full account.

## Advanced self-hosted IMDb path

- Nexio also supports a custom IMDb ratings provider for users who want to run the ratings service themselves.
- That path is documented by the [nexio-imdbratings](https://github.com/johnneerdael/nexio-imdbratings) project.
- When the custom IMDb path is active, it becomes the primary source for episode ratings and replaces the OMDb and TMDB fallback path for that view.

## Who actually needs self-hosting

- Most users do not need the self-hosted path.
- If you only want season ratings and episode ratings to show up in Nexio, TMDB plus OMDb is usually enough.
- Self-hosting is mainly for people who want to control the ratings backend themselves, keep it behind their own infrastructure, or use the custom IMDb stack end to end.

## Where to go next

- See [Options and Self-Hosting](/advanced/options-and-self-hosting) for the long-form self-hosting walkthrough.

## If this is not working

- If season tabs, cast sections, or related-title surfaces are missing, check TMDB first.
- If metadata is present but richer score signals are missing, check MDBList and OMDb before you assume the detail flow is broken.
- Start with OMDb if you only need ratings to show up and do not already run the self-hosted path.
- Check one known series or episode after saving the providers so you do not diagnose a title that lacks source data.
- If ratings or metadata are still missing, compare the detail flow in [Details, Seasons, and Watching Flow](/watch/details-seasons-and-watching-flow) before you assume playback is at fault.
- If the provider should be working but never appears, move to [Troubleshooting](/troubleshooting/).

## Related guides

- [Recommended Setup](/start-here/recommended-setup)
- [Details, Seasons, and Watching Flow](/watch/details-seasons-and-watching-flow)
- [Options and Self-Hosting](/advanced/options-and-self-hosting)
- [Troubleshooting](/troubleshooting/)
