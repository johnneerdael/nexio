# Phase 5: nexio-web Integration - Context

**Gathered:** 2026-04-14
**Status:** Ready for planning

<domain>
## Phase Boundary

This phase adds profile management capabilities to the existing nexio-web Nuxt.js app and builds the Android-side sync layer to consume those changes. The master account holder can CRUD profiles from the web dashboard. Non-default profiles can self-manage Trakt/Simkl auth, catalog ordering, and formatter config from nexio-web without touching the TV. Profile photos uploaded via the web are stored in Supabase Storage and displayed as avatars on the TV app. This is full-stack: Nuxt.js web frontend + Supabase RPCs/schema + Android sync code.

</domain>

<decisions>
## Implementation Decisions

### Scope & Architecture
- **D-01:** Full-stack phase — covers nexio-web frontend (Nuxt.js), Supabase backend (RPCs, tables, Storage), and Android sync layer. All three surfaces are in scope.
- **D-02:** nexio-web already exists at `nexio-web/` inside the Android repo as a Nuxt.js app with Tailwind CSS. Extend the existing project, don't create a new one.
- **D-03:** Keep the existing "Obsidian Lens" design system (dark cinematic glassmorphism, Electric Violet primary, Cyber Cyan secondary). See `nexio-web/DESIGN.md` for full spec.

### Sync Mechanism
- **D-04:** Next-launch sync — TV app pulls latest profile data from Supabase on app launch and on profile switch. No realtime subscriptions or polling needed.
- **D-05:** Last-write-wins conflict resolution — most recent change (by timestamp) wins when web and TV both modify the same profile setting.
- **D-06:** Per-profile RPCs for sync — independent push/pull per profile ID. Profile 2 changes never touch Profile 1 data. Consistent with Phase 4 decision (SYNC-02).

### Web Access & UX
- **D-07:** Master login + profile select — master account logs into nexio-web, then selects which profile to manage. Non-default profiles don't have their own web login.
- **D-08:** Direct apply — non-default profiles can self-manage their own auth, catalogs, and formatter settings without master approval. Master only controls profile CRUD (create/rename/delete).
- **D-09:** Dashboard layout — master sees all profiles in a grid/list overview. Click into a profile to manage its settings.

### Web Auth Flow (Trakt/Simkl)
- **D-10:** Hybrid OAuth — both web and TV can independently link Trakt/Simkl accounts. Web handles OAuth redirect in the browser and stores tokens in Supabase scoped to profile_id. TV can also do its own OAuth. Both paths result in tokens in Supabase.
- **D-11:** Most-recent-wins for token conflicts — when tokens exist both on-device and in Supabase for the same profile, the most recently authenticated set wins (by timestamp).
- **D-12:** Full revoke on unlink — unlinking Trakt/Simkl from the web deletes tokens from Supabase AND calls the Trakt/Simkl revoke endpoint. Clean break.
- **D-13:** Extend existing AuthPanel.vue — add profile context (profile selector/indicator) to the existing AuthPanel component. Reuse existing Trakt/Simkl link/unlink UI.

### Photo Upload & Avatar
- **D-14:** Supabase Storage with public bucket — upload profile photos to a public Supabase Storage bucket (e.g. `profile-avatars`). Serve via public URL. Requires adding Storage plugin to Android SupabaseModule.
- **D-15:** Server-side resize — accept any image, resize to a max dimension (e.g. 256x256) on the Supabase edge function or web server before storage. Saves bandwidth for TV app.
- **D-16:** Photo with color fallback — show photo avatar when available; fall back to existing ProfileAvatarColors circle when no photo is uploaded. Graceful degradation.
- **D-17:** Cache with URL-based invalidation — TV app caches profile photos locally using Coil/Glide. When the avatar URL changes in the profile sync payload, re-fetch.

### Claude's Discretion
- Dashboard grid vs list layout for profile overview
- Exact Supabase Storage bucket naming and path structure
- Server-side resize implementation (edge function vs web server middleware)
- Coil vs Glide for TV-side image loading (pick whichever Nexio already uses)
- Order of implementation plans (web-first vs Android-first vs interleaved)
- Per-profile RPC naming conventions
- Catalog ordering data model in Supabase (JSON blob vs separate rows)

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### nexio-web Existing Code (extension target)
- `nexio-web/nuxt.config.ts` — Nuxt config with Supabase, Trakt, Simkl runtime config
- `nexio-web/DESIGN.md` — "Obsidian Lens" design system spec (colors, typography, components, glassmorphism rules)
- `nexio-web/components/portal/AuthPanel.vue` — Existing auth panel to extend for per-profile Trakt/Simkl linking
- `nexio-web/components/portal/FormatterWorkspace.vue` — Existing formatter config UI
- `nexio-web/components/portal/CatalogInventory.vue` — Existing catalog ordering UI
- `nexio-web/components/portal/PortalShell.vue` — Portal layout shell
- `nexio-web/components/portal/OverviewPanel.vue` — Existing overview panel (extend for profile dashboard)
- `nexio-web/pages/account.vue` — Existing account page
- `nexio-web/composables/` — Existing composables for Supabase interaction

### Android Supabase Infrastructure (integration points)
- `app/src/main/java/com/nexio/tv/core/di/SupabaseModule.kt` — Current Supabase client (Auth + Postgrest only, needs Storage plugin)
- `app/src/main/java/com/nexio/tv/data/remote/supabase/SupabaseModels.kt` — Existing models including `SupabaseProfile` with `profileIndex`, `name`, `avatarColorHex`
- `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt` — Existing sync service (v7 contract, reference for per-profile RPCs)
- `app/src/main/java/com/nexio/tv/core/sync/AccountConfigSyncContract.kt` — Existing sync contract
- `app/src/main/java/com/nexio/tv/domain/repository/SyncRepository.kt` — Existing sync repository interface

### Phase Dependencies
- `.planning/phases/01-foundation/01-CONTEXT.md` — ProfileDataStoreFactory, ProfileManager, UserProfile model
- `.planning/phases/02-per-profile-auth-and-settings/02-CONTEXT.md` — DataStore classification, flatMapLatest migration pattern, auth switch behavior
- Phase 4 (Sync and Cleanup) — Supabase sync for profile metadata, per-profile settings blobs, cleanup on deletion (must be complete before Phase 5)

### NuvioTV Reference
- `~/Scripts/NuvioTV/app/src/main/java/com/nuvio/tv/core/profile/ProfileManager.kt` — Profile CRUD reference

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `nexio-web/components/portal/` — Full portal component library (AuthPanel, FormatterWorkspace, CatalogInventory, OverviewPanel, LinkedDevicesPanel, SettingsWorkspace)
- `nexio-web/components/settings/` — Reusable setting components (BaseSelect, BaseSlider, BaseToggle, SettingRow, SettingsSection)
- `SupabaseProfile` model — already has `profileIndex`, `name`, `avatarColorHex`, `usesPrimaryAddons`, `usesPrimaryPlugins`
- `TvLoginStartResult` / `TvLoginPollResult` / `TvLoginExchangeResult` — existing TV-code login flow models
- `AccountSettingsSyncService` — existing sync service pattern to replicate for per-profile sync
- `ProfileAvatarColors` — 8 predefined avatar colors for fallback display

### Established Patterns
- Nuxt.js with Tailwind CSS, runtime config for API keys
- Supabase client with Auth + Postgrest (Storage to be added)
- Portal components follow `PortalShell` layout with panels
- Android sync uses RPC calls via Postgrest
- `@kotlinx.serialization` for Supabase model serialization

### Integration Points
- `nexio-web/pages/account.vue` — add profile management dashboard
- `nexio-web/components/portal/AuthPanel.vue` — extend for per-profile auth
- `SupabaseModule.kt` — add Storage plugin installation
- `SupabaseModels.kt` — extend `SupabaseProfile` with `avatarUrl` field
- `AccountSettingsSyncService.kt` — add per-profile sync methods
- `SyncRepository.kt` — add profile sync interface methods

</code_context>

<specifics>
## Specific Ideas

- Extend the existing nexio-web portal (same Obsidian Lens theme) rather than building a new app
- Dashboard view shows all profiles as cards — click into one to manage auth, catalogs, formatter
- AuthPanel.vue gets a profile context indicator so the same link/unlink UI works per-profile
- Supabase Storage public bucket for avatars — simple URL-based access, no signed URLs needed
- Server-side image resize keeps TV download sizes reasonable
- TV app uses Coil/Glide with URL-based cache key — when avatar URL changes in sync payload, image is re-fetched

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 05-nexio-web-integration*
*Context gathered: 2026-04-14*
