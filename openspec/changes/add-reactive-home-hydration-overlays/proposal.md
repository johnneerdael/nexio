## Why

Modern Home proves first paint and canonical hydration independently, but visible/background hydration can write metadata cache entries without a guaranteed observed card repaint. Built-in API rails and addon rows need a durable reactive bridge:

```text
first paint preview -> stable ID bundle -> canonical hydration -> hydrated overlay -> item-level home repaint
```

## What Changes

### ADDED

- `HydratedHomeOverlayStore` persists language/policy-scoped display overlays by canonical identity and item-key aliases.
- `HomeHydrationCoordinator` runs visible/focused/adjacent/hero hydration through existing metadata router, stable ID, runtime, FieldResolver, and rating enrichment paths.
- Modern Home composes first-paint preview rows with hydrated overlays before publishing UI state.
- Home hydration trace events prove before/after repaint behavior.
- Metadata execution report scenarios prove first paint, hydration, cache hit, failure fallback, and profile-switch ignore behavior.

### MODIFIED

- Visible home hydration no longer stops at `MetadataDiskCacheStore.writeHomeDisplayMetadata`; it writes a hydrated overlay and publishes an in-memory patch.
- Focused and hero hydration use the same coordinator/store path as visible hydration.

## Impact

- Affected specs: `home-startup-refresh`.
- Affected code: home ViewModel pipelines, metadata trace events, metadata audit tests, local overlay persistence.
- No provider authority rule changes.
- No new provider-specific renderer, hydration scheduler, FieldResolver, or rating resolver.
