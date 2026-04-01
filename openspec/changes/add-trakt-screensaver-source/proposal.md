# Change: Add Trakt Screensaver Source

## Why
When Trakt integration is configured and the user has enabled Trakt trending rails on home, the screensaver should use that same higher-signal discovery source instead of maintaining a separate Cinemeta-only rotation. Reusing the Trakt discovery snapshot also avoids redundant fetches once startup refresh has populated the disk-backed snapshot.

## What Changes
- Add a conditional screensaver source selection strategy.
- Keep the current Cinemeta-powered screensaver as the default fallback.
- Switch the screensaver source to Trakt only when Trakt is authenticated and both `Trending Movies` and `Trending Shows` are enabled on home.
- Use the persisted Trakt discovery snapshot as the source of screensaver items, taking the top 10 trending movies and top 10 trending shows for a 20-item rotation.
- Wait for the normal on-boot snapshot refresh path to populate the Trakt snapshot rather than introducing a second dedicated fetch for screensaver content.

## Impact
- Affected specs: `screensaver-content-source`
- Affected code: `app/src/main/java/com/nexio/tv/data/repository/IdleScreensaverRepository.kt`, `app/src/main/java/com/nexio/tv/data/repository/TraktDiscoveryService.kt`, `app/src/main/java/com/nexio/tv/data/local`, `app/src/main/java/com/nexio/tv/ui/screens/home`
