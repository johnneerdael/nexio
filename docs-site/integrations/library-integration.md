# Library Integration

Library integration turns supported debrid accounts into first-class browsing content inside Nexio. Instead of treating those accounts as playback-only services, Nexio can show their items as browsable library tabs with resolved playback links where available.

## Provider support

- Premiumize library integration imports cloud items that can be played directly from Nexio.
- Real-Debrid library integration imports resolved torrent items and their playback links.
- TorBox library integration imports cached torrent items from your TorBox account.

## How library content surfaces in Nexio

- Library items appear as service tabs in the Library screen.
- Each tab shows the content Nexio can currently resolve for that provider.
- The items keep their posters, metadata, and playback details, so they behave like normal library entries instead of loose addon results.
- If you also use Trakt, those list tabs still appear alongside the debrid service tabs.

## What changes in browsing

- Library is no longer just a Trakt list view when debrid accounts are connected.
- The refresh action switches to the source you are actually looking at, so a Real-Debrid tab refreshes Real-Debrid data and a TorBox tab refreshes TorBox data.
- Sort and filter behavior adjusts to the active source, which makes browsing feel closer to a real library and less like a raw service dump.

## What changes in resume behavior

- Resume is still driven by playback progress, not by library syncing alone.
- The difference is that debrid-backed titles are easier to come back to because Nexio stores them as first-class library entries with direct playback information when available.
- If you return to a title from Library, you do not have to rediscover the same item through the addon list first.

## When to use it

- Use Premiumize, Real-Debrid, or TorBox library integration if you want account-backed content to show up in Library.
- Skip it if you only want provider-backed playback and do not care about browsing those items later.

## If this is not working

- If provider tabs do not appear, confirm the matching debrid provider is connected first.
- If Library looks empty right after setup, give the first sync time to finish before you rebuild the account.
- If the account still does not surface library content, move to [Troubleshooting](/troubleshooting/).

## Related guides

- [Recommended Setup](/start-here/recommended-setup)
- [Debrid and Service Wrap](/integrations/debrid-and-service-wrap)
- [Home and Continue Watching](/watch/home-and-continue-watching)
- [Troubleshooting](/troubleshooting/)
