# Settings

## What Settings is for
Settings is where you shape Nexio around your device, your integrations, and the way you like to watch. It is split into TV-friendly categories so you can make one kind of change at a time.

## Main categories
- Account
- Appearance
- Layout
- Integration
- Playback
- Catalogs
- About
- Debug, on debug builds only

## How the screen behaves
- The left rail is the main navigation.
- The detail pane opens the selected category and tries to keep focus where you expect it.
- Some categories open directly inside the settings surface, while others open a dedicated screen.
- Catalog management is separate so ordering and enablement stay focused.

## What each area is good for
- Account: sign in and manage account-linked features.
- Appearance: tune the look and feel of the app.
- Layout: choose the Home style and related browsing behavior.
- Integration: configure services such as Trakt, TMDB, MDBList, Debrid, Anime Skip, Gemini, and poster ratings.
- Playback: tune player behavior, audio, subtitles, buffering, stream selection, and logging.
- Catalogs: manage catalog ordering and visibility.
- About: app information.

## Integration behavior from the TV
- Trakt shows login state, sync actions, catalog selection, continue-watching window controls, and whether unaired episodes are included.
- TMDB lets you enable enrichment and choose which metadata fields Nexio should use.
- MDBList controls external ratings and list-backed rails.
- Debrid surfaces account-backed source management.
- Anime Skip enables skip timestamps for supported anime titles.
- Gemini enables subtitle translation features when available.
- Poster ratings controls RPDB and TOPPosters.

## Playback settings that matter most
- Player preference decides whether Nexio uses the built-in player, an external app, or asks every time.
- Auto-play controls whether Nexio chooses the first source, matches a pattern, or leaves selection manual.
- VOD cache and parallel connections influence how resilient progressive streams feel during startup and buffering.
- Subtitle settings control language preference, startup behavior, style, and advanced rendering.
- Audio settings include decoder priority and passthrough options for premium TV devices.

## Best use guidance
- Change one section at a time and test it in [Home](./home.md), [Media Detail](./detail.md), or [Playback Interface](./player.md) before changing something else.
- If a setting is meant for advanced hardware behavior, treat it as a targeted fix rather than a daily toggle.
- Use playback tuning when the symptom is real and repeatable, not just because a stream had one bad start.

## Troubleshooting
- If a feature stops working after a settings change, revert the last category you touched first.
- If an integration disappears from Home or Detail, verify the account connection and the related toggle.
- If a playback change has no visible effect, make sure you tested it on a stream type that actually uses that code path.

## Related pages
- [Home](./home.md)
- [Catalogs and Library](./catalog.md)
- [Media Detail](./detail.md)
- [Playback Interface](./player.md)
- [Search and Cast](./search-and-cast.md)
