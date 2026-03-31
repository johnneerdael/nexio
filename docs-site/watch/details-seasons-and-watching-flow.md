# Details, Seasons, and Watching Flow

The detail flow is where you decide what to watch next. It brings together play and resume actions, season navigation, trailers, recaps, ratings, and the metadata that helps you choose with confidence.

## What this helps with

- Starting playback from the right entry point.
- Moving through seasons without losing context.
- Finding trailers, recaps, ratings, and other title metadata in one flow.
- Understanding what changes when you resume an episode versus starting from the hero.

## Where to set it up

- `Settings > Integration` controls the metadata and ratings services that feed detail pages.
- `Settings > Playback` controls the playback behavior that takes over after you press Play.
- [Ratings and Metadata](/integrations/ratings-and-metadata) covers TMDB, OMDb, MDBList, and the optional self-hosted ratings path.
- [Recommended Setup](/start-here/recommended-setup) is the right path if the whole account still looks unfinished instead of just one title.

## Recommended setup

1. Keep TMDB enabled if you want the richest title, cast, episode, and collection metadata.
2. Keep MDBList enabled if you want external ratings to show in the hero and episode rows.
3. Use Trakt if you want watch progress and next-up context to influence the detail CTA.
4. Start from Detail when you want to compare metadata before opening a stream.

## What to expect

- The top hero area gives you the main Play or Resume action, plus Trailer when a trailer is available.
- For series, the main action can change to a season-aware prompt such as playing the next episode or resuming the current one.
- Season tabs let you move between seasons, and episode cards show the episodes within the selected season.
- Long-press a season tab to open season actions. That is where season trailers and season recap actions live when the title supports them.
- Long-pressing an episode card opens episode actions.
- Season trailers are resolved from the season action you reach by long-pressing a season, using the selected season as the lookup context.
- Season recaps are also reached from that same long-press season action. Playback can still surface Skip Recap after you start a supported episode from Detail, but the season action is the main place to find it.
- Ratings can appear in the hero and in episode cards, depending on which metadata services are enabled.
- Cast, reviews, collections, and related titles live lower in the detail flow so you can keep browsing without leaving the page.

## If this is not working

- If Play or Resume points at the wrong episode, check your watch progress and Trakt sync state.
- If season tabs are missing, confirm that TMDB metadata is enabled and that the title has episode data.
- If ratings are missing, check MDBList and TMDB settings.
- If trailer playback fails, use [Trailers and Recaps](/watch/trailers-and-recaps) for the setup path.

## Related guides

- [Home and Continue Watching](/watch/home-and-continue-watching)
- [Trailers and Recaps](/watch/trailers-and-recaps)
- [Playback](/playback/)
- [Start Here](/start-here/)
- [Ratings and Metadata](/integrations/ratings-and-metadata)
- [Options and Self-Hosting](/advanced/options-and-self-hosting)
- [Troubleshooting](/troubleshooting/)
