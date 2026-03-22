# Media Detail

![Media detail screen](/images/tv-app/detail-overview.webp)
*The Detail screen brings together playback actions, external ratings, seasons, and metadata so you can decide on a title before opening the stream picker.*

![Cast and production sections](/images/tv-app/detail-cast-production.webp)
*Cast, reviews, and production rows turn one detail page into a discovery hub, letting you move from a title into people, networks, and companies without starting a new search.*

## What this screen is for
The Detail screen is the decision point between browsing and playback. It gives you the metadata you need to choose a title confidently, then hands off to the player when you are ready.

## What you can do here
- Read the title’s main metadata, artwork, cast, ratings, and related content.
- Start playback, resume playback, or open a specific episode.
- Open the trailer when you want a quick preview before committing to a stream.
- Jump to cast members, collections, similar titles, and reviews when those sections are available.
- Open cast or crew people to see more titles tied to that person.
- Open networks and production companies to see the titles Nexio finds for that organization.
- Mark movies or episodes watched and manage library or watchlist state when your integrations support it.

## Where the controls live
- The main play and resume actions are near the top hero area.
- Episode controls appear in the episode section for series.
- Trailer, library, and list actions are grouped with the main title actions.
- Cast, Ratings, More Like This, Reviews, and Collection are section tabs or rows lower on the page.
- Cast cards can represent actors, creators, directors, or writers, and selecting one opens the related person view.
- Company logo rows are split by network and production company so you can move from a title into the right organization page directly.

## How it behaves
- Detail keeps focus stable when you move between sections or return from playback.
- If you played a trailer, Back exits the trailer first instead of immediately leaving the page.
- When a trailer is available, the hero adds a dedicated trailer button and the page swaps into trailer mode while it plays.
- Trailer playback keeps the rest of the hero content out of the way, and the on-page controls stay focused on trailer actions until you exit.
- For series, the screen can preserve the episode you came from so you land back on the right episode after playback.
- If the requested episode is already watched, Nexio can advance the return focus to the next logical episode.
- Reviews are merged from TMDB and Trakt when both are available, and more Trakt reviews can load as you move farther through the row.
- Pressing a review card toggles that review’s auto-scrolling text, which is handy for longer comments.
- Collection tiles open their own detail pages, and the section keeps focus stable when you come back.
- More Like This behaves the same way as collection tiles: it is a horizontal discovery row that opens the selected title.
- Person, network, and production-company pages reuse the same TV browsing model, so once you open them you can keep exploring titles from that person or organization without leaving the detail flow.

## Why it matters
- The screen saves clicks for binge watching by preserving episode context.
- It helps avoid opening the wrong stream by showing useful metadata before playback.
- It makes integrations visible on TV, so you can manage Trakt, MDBList, and library actions without leaving the couch.

## Best use guidance
- Use Detail before every new title if you care about release year, runtime, or episode selection.
- Use the trailer for a fast quality check when the title is unfamiliar.
- Use the cast and collection sections when you want to continue discovery from a known good title.
- Use reviews when you want a fast community read before committing to a long movie or a new series.

## Troubleshooting
- If Back behaves differently than expected, check whether trailer playback is still active.
- If a section is missing, the related integration or metadata source may not be enabled.
- If episode focus does not return where expected, the episode may already be marked watched and Nexio may have advanced to the next item.

## Related pages
- [Home](./home.md)
- [Catalogs and Library](./catalog.md)
- [Playback Interface](./player.md)
- [Settings](./settings.md)
