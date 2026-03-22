# Home

![TV App home screen](/images/tv-app/home-overview.webp)
*Home combines a featured hero, rich poster badges, and synced catalog rails so you can resume quickly or browse deeper without leaving the main screen.*

## What Home does
Home is the main browsing surface. It is the fastest way to get back to what you were watching, jump into featured content, or open a catalog row from your configured addons and integrations.

## How it is organized
Home is built from three types of content:
- Hero items at the top
- Continue Watching items
- Catalog rows from active addons and synced integrations

The screen is ready as soon as any one of those sections has content. That means Home can feel responsive even when one source is still loading.

## What you can do here
- Resume an in-progress movie or episode from Continue Watching.
- Open a hero item to jump straight into a featured title.
- Browse catalog rows and long-press a poster for title actions.
- Open a row’s `See all` page when you want the full catalog instead of the row preview.

## How it behaves
- On first load, Home waits briefly for usable content instead of showing a permanent spinner.
- If nothing renderable arrives, it shows a retryable loading error rather than leaving you stuck.
- Different empty states are intentional:
  - No addons installed
  - Addons are installed, but no catalog addons are available
  - Content is still loading and has not become renderable yet
- When the app returns to the foreground, Home refreshes in the background so watched state and rows stay current.

## Where the important controls live
- Continue Watching is the quickest route back into active playback.
- Poster long-press actions are where you remove or manage items tied to watch progress.
- `See all` is the right choice when you want to browse a catalog more deeply than the Home row allows.

## Best use guidance
- Use Home for quick re-entry, not for deep catalog exploration.
- If you want a cleaner or denser browsing style, change the Home layout in [Settings](./settings.md).
- If Home feels empty, the problem is usually catalog availability rather than the Home screen itself.

## Troubleshooting
- If you only see a loading state, check that at least one catalog-enabled addon is configured.
- If you see a no-addon message, install or enable an addon first.
- If Home times out during startup, retry once before changing anything else.

## Related pages
- [Android Guides](../index.md)
- [Catalogs and Library](./catalog.md)
- [Media Detail](./detail.md)
- [Settings](./settings.md)
