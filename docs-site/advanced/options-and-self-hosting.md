# Options and Self-Hosting

This page is the optional companion for the settings that need more explanation than the main guides provide. Use it when you already understand the default trailer and ratings flows and want the deeper setup path, not a second introduction.

## Streailer fallback

Streailer is the fallback to reach for when the built-in trailer path does not have enough coverage for a title, season, or recap prompt.

### When to enable it

- You already have the Streailer addon installed and want to use the extra trailer and recap coverage it can provide.
- The title you care about often misses a playable trailer from the primary sources, especially when the result depends on a provider-specific stream entry.
- You want a second chance at season trailers or recap prompts when the main lookup does not return a usable candidate.

### What it gives you

- Better coverage for titles that have trailer data in Streailer but not in the primary path.
- A fallback for edge cases where TMDB or the YouTube route comes up empty.
- A way to keep trailers and recaps inside the app instead of treating them as missing just because the first source did not resolve.

### When not to bother

- You do not use Streailer at all.
- The normal trailer path already covers the titles you watch.
- You want the smallest possible setup surface and are happy with the main guide’s default trailer behavior.

### How it behaves in practice

Streailer is not a separate browsing surface. It only matters when the app is already trying to resolve a trailer or recap and needs another source to try.

- For general trailer playback, Nexio tries the primary lookup first and only falls back when that does not produce a playable result.
- For season trailer and recap handling, Streailer can fill gaps when the season-specific primary source is missing or incomplete.
- If the addon has no matching stream for the title, season, or recap, the fallback does nothing.

That means Streailer is useful when you want broader coverage, but it is not worth turning into a configuration dependency unless you actually see missing trailers.

## Self-hosted IMDb ratings API

The custom IMDb ratings API is the optional path for people who want to run episode ratings from their own infrastructure instead of using the simpler default route.

### When this path is worth it

- You want the ratings backend to live on infrastructure you control.
- You need the same episode-rating behavior across multiple devices or environments.
- You are comfortable maintaining a small ratings service instead of relying on the default hosted path.
- You want the custom IMDb service to be the primary source for episode ratings in your account.

### When to leave it alone

- You only need the default ratings behavior.
- You do not want to operate another backend.
- You are happy with the simpler OMDb-based path for the cases that matter to you.

### Practical setup path

1. Deploy the `nexio-imdbratings` service on infrastructure you control.
2. Make sure the service is reachable at a stable base URL.
3. Open the IMDb ratings settings in Nexio.
4. Enter the base URL and API key.
5. Save and validate the connection before you depend on it.
6. Check a known series or episode view to confirm the ratings source is the one you expect.

### What to think about before you adopt it

- You are accepting the maintenance burden of another service.
- The service becomes the primary source for episode ratings once it is active.
- If the service is unreachable or misconfigured, the account will not benefit from the custom path until you fix it.
- This is useful when control matters more than simplicity.

### Common reasons to choose it anyway

- You already self-host other pieces of the stack and want ratings to match that model.
- You prefer one consistent ratings source across your library.
- You want to keep the data path in your own environment for operational or policy reasons.

## Keep this page optional

If you do not need fallback detail or self-hosting detail, stop with the main guides. This page is here for the cases where the normal setup is working but you want the next layer of control or coverage.

## Related guides

- [Trailers and Recaps](/watch/trailers-and-recaps)
- [Ratings and Metadata](/integrations/ratings-and-metadata)
- [Recommended Setup](/start-here/)
- [Troubleshooting](/troubleshooting/)
