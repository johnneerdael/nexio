## Why

`codex/integration-runtime-phase-a` has become the architecture branch for the shared IntegrationRuntime, MetadataRouter, stable ID bundle resolver, rail-preview-first home lifecycle, and reactive hydration overlays. At the same time, `main` has advanced by 259 commits since the branch point and now contains critical product fixes for Continue Watching, deterministic autoplay, detail hydration, TVDB localization, proxy recovery, and playback diagnostics.

A direct merge is not feasible or desirable. This branch is 702 commits ahead of `main`, and `main` does not contain the shared runtime/router/rail-preview architecture. A mechanical merge would either delete new architecture files or place main behavior into old direct paths.

## What Changes

### ADDED

- A main-parity ledger that classifies each main-only commit as `PORT`, `ALREADY_COVERED`, `OBSOLETE`, or `REDESIGN_FOR_SHARED_ARCHITECTURE`.
- A semantic port workflow that brings main behavior into the shared architecture boundary where it belongs.
- Regression tests proving the ported behavior flows through existing shared components instead of provider-specific side paths.
- Device validation scenarios for profileable builds that prove stable IDs, route decisions, cache decisions, stream candidate filtering, and home/detail updates.

### MODIFIED

- Continue Watching playback launch must preserve addon context, stable IDs, episode context, runtime, and localized release-time gating before stream resolution.
- Deterministic autoplay must incorporate main’s candidate safety fixes and diagnostics while continuing to use the existing stream presentation/scoring path.
- Detail screens must hydrate canonical metadata through the shared router, stable ID bundle resolver, provider plan executor, IntegrationRuntime-backed provider adapters, and FieldResolver.
- Provider metadata completeness fixes from main must land inside shared provider adapters and runtime operations.
- Modern Home must remain preview-first and update/react to catalog mutations and hydration overlays without full row reloads.

## Impact

- Affected specs: `integration-runtime`, `metadata-router`, `library-playback`, `home-startup-refresh`.
- Affected areas: Continue Watching, stream/autoplay, detail screens, TVDB/TMDB/Kitsu provider adapters, Modern Home, runtime traces.
- Direct provider fetches, screen-specific metadata fallbacks, and parallel hydration/rendering paths remain disallowed.
- The first implementation milestone is branch inventory plus Continue Watching/playback parity, because bad playback route context invalidates downstream autoplay and resolver diagnostics.
