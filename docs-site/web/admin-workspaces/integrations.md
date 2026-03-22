# Integrations

Integrations are the services that give Nexio account identity, better metadata, richer catalogs, and optional playback enhancements.

## What belongs here
- Trakt for sign-in, watch-state, and Trakt-backed catalogs.
- Debrid services for higher-quality stream access.
- TMDB for metadata enrichment and artwork behavior.
- MDBList for ratings and list-backed discovery.
- Anime Skip, Gemini, and poster ratings for optional enhancements.

## Recommended order
1. Connect Trakt first so the account has a clear identity and catalog baseline.
2. Add your debrid provider so stream availability improves.
3. Enable TMDB and MDBList if you want richer detail pages and better discovery signals.
4. Turn on optional extras only after the basics feel stable.

## What to expect from each service
- Trakt connects with a device-style authorization flow that avoids typing passwords on TV.
- Real-Debrid and Premiumize unlock account-specific debrid behavior.
- TMDB improves artwork and metadata richness.
- MDBList adds ratings and list-driven catalog options.
- Anime Skip, Gemini, and poster ratings are additive features you can keep off until you need them.

## Good setup habits
- Connect one service at a time.
- Save after each step so you know what changed.
- If a service looks connected but the app does not change, check the service that sits below it in the stack first.
- Keep your integration choices aligned with the account you actually want to use on Android.

## How to validate success
- Trakt should show the correct signed-in account and related catalog state.
- Debrid should improve the quality or availability of stream choices.
- TMDB and MDBList should deepen metadata and list context.
- Optional services should only add the effect you asked for.

## Troubleshooting
- If the wrong account is connected, disconnect the service and connect the right one.
- If a token or device flow expires, start the flow again from Integrations.
- If the app still looks unchanged, verify that the lower-level service is connected before assuming the feature is broken.

## Next page
Continue with [Formatter Getting Started](./formatter-getting-started.md) once the account has stable data to present.
