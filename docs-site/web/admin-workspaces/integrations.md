# Integrations

Integrations are the account-wide services that Nexio syncs between the Management Portal and the TV App. The portal stores the settings, secrets, and catalog choices; the TV app exposes the same stack under `Settings > Integration`.

![Integrations overview](/images/management-portal/integrations-overview.webp)
*The integrations workspace shows connection health, current status, and the services that are already active for the account.*

## TV App surface

On TV, integrations are grouped into these sections:

- Debrid
- Trakt
- TMDB
- MDBList
- Anime-Skip
- Google Gemini
- Poster Ratings

## Suggested setup order

1. Connect Trakt first so the account has a clear identity and a catalog baseline.
2. Add a debrid provider next so source selection can use the account-aware streams.
3. Turn on TMDB and MDBList when you want deeper metadata, ratings, and list-backed discovery.
4. Enable Anime Skip, Gemini, and poster ratings after the core identity and metadata pieces are stable.

## Trakt

### What it is

Trakt is the account identity layer for Nexio. It links the portal and TV app to a Trakt user so watch-state, progress, and catalog preferences can stay aligned.

### What it delivers

- Device-style sign-in without typing a password on TV.
- Signed-in account identity, username, and slug in both the portal and the TV app.
- Trakt-backed home rails such as Up Next, Trending, Popular, Recommended, and Calendar when they are enabled.
- Connected-state context on the TV app, including watch-progress and "watching now" details when available.

### What can be configured

- Connect or disconnect the Trakt account.
- Choose which Trakt catalog rows are enabled.
- Reorder the Trakt catalog rows.
- Select which popular lists should appear.
- Set the continue-watching window.
- Toggle whether unaired next-up items are shown.

### What happens automatically

- The portal uses a device-code flow instead of a password prompt.
- Trakt access and refresh tokens are stored as secrets and synced separately from the visible settings.
- The TV app keeps the connected username and pending-login state in sync with the account snapshot.
- Trakt catalog selections flow into the account sync payload, so the same account sees the same catalog layout on another TV.

### Where it appears on TV

- `Settings > Integration > Trakt`
- Home rails that come from the enabled Trakt catalogs
- Detail and progress surfaces that can show Trakt-backed watch context

## Real-Debrid

### What it is

Real-Debrid is the debrid login path for Nexio. It gives the account a device-authorized debrid connection instead of a manual username/password setup on TV.

### What it delivers

- Account-aware debrid state for the TV app.
- A device approval flow with a user code and verification URL.
- A connected username once authorization succeeds.
- Token-backed access that can support debrid-aware source selection.

### What can be configured

- Start the device authorization flow.
- Poll for approval while the flow is pending.
- Disconnect the account.
- Review the verification URL and code while approval is pending.

### What happens automatically

- The TV app tracks pending, connected, and disconnected states.
- Device code, user code, verification URL, and expiry are synced with the portal snapshot while approval is in flight.
- Access and refresh tokens are stored separately as secrets once the account is connected.
- When the account disconnects, the sync layer clears the remote secret state.

### Where it appears on TV

- `Settings > Integration > Debrid`
- Source selection and other debrid-aware playback flows

## Premiumize

### What it is

Premiumize is the second debrid provider Nexio supports. It is modeled as an API-key-backed account connection rather than a device-code flow.

### What it delivers

- Premiumize account status on the TV app.
- A customer ID once the TV app can read the connected account.
- Debrid-aware stream access for the account, where supported by the rest of the playback stack.

### What can be configured

- Save or clear the Premiumize API key.
- Review whether the account is connected.
- See the customer ID when the service returns it.

### What happens automatically

- The API key is validated before it is kept on the TV side.
- The key is stored as a secret in the portal sync layer, not as plain visible configuration.
- The account sync payload carries only the configured state and customer ID, so the TV app can render connection status without exposing the key.

### Where it appears on TV

- `Settings > Integration > Debrid`
- Any debrid-aware playback path that can benefit from Premiumize-backed sources

## TMDB

### What it is

TMDB is the metadata enrichment layer. It feeds Nexio with artwork and richer title, cast, crew, episode, and collection data.

### What it delivers

- Artwork enrichment for posters and related metadata surfaces.
- Basic title data and extended detail fields.
- Cast and crew data.
- Production company and network data.
- Episode metadata.
- More-like-this recommendations.
- Collection grouping for franchises.

### What can be configured

- Enable or disable TMDB account-wide.
- Save or clear the TMDB API key.
- Choose which metadata buckets should be used: artwork, basic info, details, credits, productions, networks, episodes, more like this, and collections.

### What happens automatically

- The TV app refuses to keep TMDB enabled if the API key is missing.
- The portal validates the API key before saving it.
- Once the account is enabled, the TV app applies the same TMDB settings from the synced account snapshot.
- TMDB enrichment is cached on the device, so repeated detail loads can reuse the same metadata.

### Where it appears on TV

- `Settings > Integration > TMDB`
- Detail pages, cast/person pages, episode views, and collection-style surfaces
- Artwork and related-title suggestions wherever TMDB enrichment is used

Note: the TV app also exposes a local Reviews toggle in its own TMDB screen, but the current portal sync contract does not carry that flag.

## MDBList

### What it is

MDBList adds ratings and list-backed discovery. It is the service Nexio uses when you want more rating sources and curated list rails beyond the built-in catalogs.

### What it delivers

- Rating badges from Trakt, IMDb, TMDB, Letterboxd, Rotten Tomatoes, audience scores, and Metacritic.
- Personal list discovery.
- Top-list discovery.
- Account-scoped list selections that can become TV catalogs.

### What can be configured

- Enable or disable MDBList.
- Save or clear the MDBList API key.
- Choose which rating sources should be shown.
- Enable or hide individual personal lists.
- Select which top lists should appear.
- Reorder the MDBList-derived catalogs.

### What happens automatically

- The API key is validated against the MDBList user endpoint before it is stored.
- Opening list management refreshes discovery data so the portal can show current list options.
- Toggling a personal list or top list triggers a fresh discovery pass.
- The selected lists and catalog order are synced back to the TV app through the account payload.

### Where it appears on TV

- `Settings > Integration > MDBList`
- Detail-page rating rows
- Home and catalog rails that are generated from the selected lists

## Anime Skip

### What it is

Anime Skip is Nexio’s intro-skip metadata source for supported anime.

### What it delivers

- Skip intervals when the service can identify them.
- Intro-skip support that plugs into playback rather than a separate browsing surface.

### What can be configured

- Enable or disable Anime Skip.
- Save the Anime Skip client ID.

### What happens automatically

- The client ID is validated before it is saved.
- The enable flag and client ID are synced to the TV app.
- The playback skip-intro path uses Anime Skip data automatically when the title is supported.

### Where it appears on TV

- `Settings > Integration > Anime-Skip`
- Playback skip-intro controls for supported anime

## Google Gemini

### What it is

Gemini powers Nexio’s AI subtitle translation flow.

### What it delivers

- AI-assisted subtitle translation during playback.
- A simple account-level switch instead of a per-title setup flow.

### What can be configured

- Enable or disable Gemini.
- Save or clear the Gemini API key.

### What happens automatically

- The API key is validated before it is stored.
- The TV app keeps the enabled state in sync with the account snapshot.
- If the API key is missing, the device-side settings will not stay enabled.

### Where it appears on TV

- `Settings > Integration > Google Gemini`
- Playback subtitle translation when the feature is enabled

## Poster Ratings

### What it is

Poster Ratings controls which poster provider Nexio uses when it rewrites artwork URLs.

### What it delivers

- RPDB-powered poster artwork.
- Top Posters-powered poster artwork.
- Automatic poster URL rewriting across the TV app wherever posters are resolved.

### What can be configured

- Enable RPDB or Top Posters.
- Save the API key for the chosen provider.
- Clear the provider key when you want to revert to normal posters.

### What happens automatically

- The portal validates each provider key before saving it.
- The sync layer stores the keys as secrets.
- Only one provider is effectively active at a time.
- RPDB is checked first; if it is enabled and has a key, it wins before Top Posters.
- RPDB is limited to IMDb IDs, while Top Posters supports IMDb, TMDB, TVDB, Trakt, MAL, Kitsu, AniList, and AniDB IDs.

### Where it appears on TV

- `Settings > Integration > Poster Ratings`
- Poster cards, detail artwork, and any other surface that uses the resolved poster URL

## Next step

Continue with [Formatter Getting Started](./formatter-getting-started.md) once the account has stable data to present.
