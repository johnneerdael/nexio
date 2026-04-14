# Phase 5: nexio-web Integration - Research

**Researched:** 2026-04-14
**Domain:** Nuxt.js portal extension + Supabase Storage + Android sync layer
**Confidence:** HIGH

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

- **D-01:** Full-stack phase — covers nexio-web frontend (Nuxt.js), Supabase backend (RPCs, tables, Storage), and Android sync layer. All three surfaces are in scope.
- **D-02:** nexio-web already exists at `nexio-web/` inside the Android repo as a Nuxt.js app with Tailwind CSS. Extend the existing project, don't create a new one.
- **D-03:** Keep the existing "Obsidian Lens" design system (dark cinematic glassmorphism, Electric Violet primary, Cyber Cyan secondary). See `nexio-web/DESIGN.md` for full spec.
- **D-04:** Next-launch sync — TV app pulls latest profile data from Supabase on app launch and on profile switch. No realtime subscriptions or polling needed.
- **D-05:** Last-write-wins conflict resolution — most recent change (by timestamp) wins when web and TV both modify the same profile setting.
- **D-06:** Per-profile RPCs for sync — independent push/pull per profile ID. Profile 2 changes never touch Profile 1 data. Consistent with Phase 4 decision (SYNC-02).
- **D-07:** Master login + profile select — master account logs into nexio-web, then selects which profile to manage. Non-default profiles don't have their own web login.
- **D-08:** Direct apply — non-default profiles can self-manage their own auth, catalogs, and formatter settings without master approval. Master only controls profile CRUD (create/rename/delete).
- **D-09:** Dashboard layout — master sees all profiles in a grid/list overview. Click into a profile to manage its settings.
- **D-10:** Hybrid OAuth — both web and TV can independently link Trakt/Simkl accounts. Web handles OAuth redirect in the browser and stores tokens in Supabase scoped to profile_id. TV can also do its own OAuth. Both paths result in tokens in Supabase.
- **D-11:** Most-recent-wins for token conflicts — when tokens exist both on-device and in Supabase for the same profile, the most recently authenticated set wins (by timestamp).
- **D-12:** Full revoke on unlink — unlinking Trakt/Simkl from the web deletes tokens from Supabase AND calls the Trakt/Simkl revoke endpoint. Clean break.
- **D-13:** Extend existing AuthPanel.vue — add profile context (profile selector/indicator) to the existing AuthPanel component. Reuse existing Trakt/Simkl link/unlink UI.
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

### Deferred Ideas (OUT OF SCOPE)

None — discussion stayed within phase scope.

</user_constraints>

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| WEB-01 | Master account can create, edit, and delete profiles via nexio-web | ProfileDashboard + ProfileDetailShell + Supabase RPC for CRUD; `SupabaseProfile` model already has `profileIndex`, `name`, `avatarColorHex`; need `profile_upsert` / `profile_delete` RPCs |
| WEB-02 | Non-default profiles can manage Trakt/Simkl auth via nexio-web | Existing device-flow infra in `usePortalStore` + `server/api/integrations/trakt/` + `server/api/integrations/simkl/` extended with `profile_id` parameter; `disconnectTrakt`/`disconnectSimkl` already deletes secrets |
| WEB-03 | Non-default profiles can configure catalog ordering via nexio-web | `CatalogInventory.vue` already exists; need `profile_id`-scoped persist endpoint or pass `profile_id` to existing persist RPC |
| WEB-04 | Non-default profiles can configure formatter settings via nexio-web | `FormatterWorkspace.vue` already exists; same scoped persist approach as WEB-03 |
| WEB-05 | Profile photo upload via nexio-web stored in Supabase | New `server/api/account/profiles/photo.post.ts` route; sharp (Node.js) for resize; `supabase-storage-kt` plugin added to Android `SupabaseModule`; `SupabaseProfile.avatarUrl` new field |

</phase_requirements>

---

## Summary

Phase 5 is a full-stack extension across three codebases: the Nuxt.js portal (`nexio-web/`), the Supabase database layer (new RPCs and a Storage bucket), and the Android sync layer. The web side extends an already sophisticated portal; the Android side adds one new Supabase plugin and extends the sync service. Both sides are well-understood by reading the existing code.

The nexio-web app uses `@supabase/supabase-js` 2.57.4 with server-side Nuxt API routes (H3 event handlers) that proxy Supabase calls using bearer tokens extracted from the session. All profile management web UI follows the approved UI-SPEC (05-UI-SPEC.md) and must use the Obsidian Lens design system. The Android side uses `io.github.jan-tennert.supabase` BOM 3.1.4 with Auth and Postgrest already installed; Storage is available in the same BOM as `storage-kt` but not yet wired in. Image loading on Android is already done with Coil 2.7.0 (`coil-compose`, `coil-svg`) — no Glide needed.

The critical insight for planning: the existing `disconnectTrakt`/`disconnectSimkl` functions in `usePortalStore` delete secrets from the existing (account-scoped) secrets table but do NOT call the provider revoke endpoint. D-12 requires a real revoke call on unlink, so per-profile unlink needs a new server route that calls the Trakt/Simkl revoke API before deleting the Supabase secret row.

**Primary recommendation:** Implement in three parallel plan tracks — (A) Supabase schema + RPCs, (B) nexio-web profile management UI + API routes, (C) Android sync layer. Track A must land before B and C can be tested end-to-end, but B and C can be written independently.

---

## Project Constraints (from CLAUDE.md)

| Directive | Impact on Phase 5 |
|-----------|------------------|
| Prefer small, targeted changes over broad refactors | Add `profile_id` param to existing API routes rather than rewriting them |
| Preserve existing architecture and naming patterns | New composables and API routes follow existing file naming (`useProfileStore.ts` or extend `usePortalStore.ts`; routes under `server/api/account/profiles/`) |
| Keep domain code free of Android framework dependencies | Profile sync service must not import Activity/Context except for DataStore path |
| Do not introduce new libraries unless clearly justified | `sharp` for server-side image resize is justified (D-15); `supabase-storage-kt` is a first-party plugin already in the BOM (justified by D-14); no other new libraries needed |
| Keep changes scoped to the task | Phase 5 touches `account.vue`, `PortalShell.vue`, the portal composable, and the Android sync service only where needed |

---

## Standard Stack

### Core

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Nuxt.js | 4.3.1 | Web framework | Already in project [VERIFIED: nexio-web/package.json] |
| @supabase/supabase-js | 2.57.4 | Web Supabase client | Already in project [VERIFIED: nexio-web/package.json] |
| Tailwind CSS | 3.4.19 | Styling | Already in project [VERIFIED: nexio-web/package.json] |
| vuedraggable | 4.1.0 | Drag-and-drop catalog reorder | Already in project, used by CatalogInventory [VERIFIED: nexio-web/package.json] |
| io.github.jan-tennert.supabase BOM | 3.1.4 | Android Supabase client | Already in project [VERIFIED: gradle/libs.versions.toml] |
| supabase-storage-kt | (from BOM 3.1.4) | Android Storage plugin | First-party, same BOM, not yet installed [VERIFIED: libs.versions.toml has no storage entry] |
| Coil | 2.7.0 | Android image loading | Already in project (`coil-compose`, `coil-svg`) [VERIFIED: gradle/libs.versions.toml:19, build.gradle.kts:334-335] |

### Supporting

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| sharp (Node.js) | latest | Server-side image resize before Supabase Storage upload | Required by D-15; runs in Nuxt server route |
| H3 (built into Nuxt) | (Nuxt 4.3.1) | Server API event handlers | Already used by all existing `server/api/` routes |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| sharp (server resize) | Supabase Edge Function with Deno image transform | Edge function adds deployment surface; Nuxt server middleware keeps resize co-located with the upload route and avoids a separate deploy step. Only justified if the Nuxt server is not the upload path. |
| Coil (Android) | Glide | Coil is already installed and used; switching to Glide adds a dependency for no gain [ASSUMED] |
| Public Supabase Storage bucket | Signed URLs | Public bucket is simpler for profile avatars — no token refresh needed on TV, URL is stable. Appropriate for non-sensitive profile photos per D-14 [ASSUMED] |

**Installation (Android Storage plugin):**
```kotlin
// libs.versions.toml — add to [libraries]
supabase-storage = { group = "io.github.jan-tennert.supabase", name = "storage-kt" }

// build.gradle.kts — add to dependencies
implementation(libs.supabase.storage)

// SupabaseModule.kt — add inside createSupabaseClient { ... }
install(Storage)
```

**Installation (web server resize):**
```bash
cd nexio-web && npm install sharp
```

---

## Architecture Patterns

### Recommended Project Structure (new files only)

```
nexio-web/
├── server/api/account/
│   └── profiles/
│       ├── index.get.ts          # list all profiles for the session user
│       ├── upsert.post.ts        # create or update a profile (name, avatarColorHex)
│       ├── delete.post.ts        # delete a non-primary profile
│       └── photo.post.ts         # upload + resize avatar, store in Supabase Storage
├── server/api/integrations/
│   └── profiles/
│       ├── trakt/
│       │   ├── authorize.get.ts        # start Trakt browser OAuth redirect for a profile_id
│       │   ├── callback.get.ts         # exchange browser OAuth code and store profile tokens
│       │   └── disconnect.post.ts      # revoke + delete tokens for a profile_id
│       └── simkl/
│           ├── authorize.get.ts
│           ├── callback.get.ts
│           └── disconnect.post.ts
├── components/portal/
│   ├── ProfileDashboard.vue      # grid of ProfileCard components
│   ├── ProfileCard.vue           # single profile tile
│   ├── ProfileDetailShell.vue    # tab shell (Auth / Catalogs / Formatter)
│   ├── ProfileEditorSection.vue  # inline name edit + photo upload within detail header
│   ├── ProfilePhotoUpload.vue    # avatar click-to-upload control
│   ├── AuthPanel.vue             # existing component extended with profile mode for link/unlink
│   └── DeleteProfileModal.vue    # teleported delete confirmation modal
└── composables/
    └── useProfileStore.ts        # profile CRUD state + per-profile auth/catalog/formatter ops

app/src/main/java/com/nexio/tv/
├── core/di/
│   └── SupabaseModule.kt         # add Storage plugin (extend existing)
├── data/remote/supabase/
│   └── SupabaseModels.kt         # extend SupabaseProfile with avatarUrl field
└── core/sync/
    └── ProfileSyncService.kt     # new: per-profile pull on launch/switch
```

### Pattern 1: Nuxt Server Route with Bearer Token + profile_id

Every new profile API route follows the established H3 pattern in `persist.post.ts` and `bootstrap.get.ts`. The bearer token is extracted from the request and forwarded to Supabase. A `profile_id` body/query param scopes the operation.

```typescript
// Source: nexio-web/server/api/account/persist.post.ts (verified pattern)
import { bearerToken, okJson, readJsonBody, supabaseUser, supabaseFetch } from '~/server/utils/supabase'

export default defineEventHandler(async (event) => {
  const body = await readJsonBody<{ profile_id: number; /* ... */ }>(event)
  const token = bearerToken(event)
  await supabaseUser(event) // verifies session

  const result = await supabaseFetch<RpcMutationResult>(
    '/rest/v1/rpc/profile_settings_push',
    { method: 'POST', body: JSON.stringify({ p_profile_id: body.profile_id, /* ... */ }) },
    token
  )
  return okJson(result)
})
```

### Pattern 2: Per-Profile Trakt/Simkl Browser OAuth Redirect (Web)

Locked decision D-10 controls the web path: per-profile auth in nexio-web uses browser OAuth redirect, not a new web device-code flow. New routes under `server/api/integrations/profiles/{provider}/` carry `profile_id` in signed state, exchange the callback code, and store resulting tokens scoped to that profile in Supabase (`profile_auth_tokens`).

```typescript
// New: server/api/integrations/profiles/trakt/callback.get.ts
export default defineEventHandler(async (event) => {
  const token = bearerToken(event)
  await supabaseUser(event)
  // validate state -> recover profile_id -> exchange code
  // on success call supabaseFetch service_set_profile_auth_token
  // scoped to profile_id in new profile_auth_tokens table
})
```

### Pattern 3: Photo Upload with Server-Side Resize

```typescript
// Source: Nuxt H3 pattern + sharp [ASSUMED for sharp integration]
// nexio-web/server/api/account/profiles/photo.post.ts
import sharp from 'sharp'
export default defineEventHandler(async (event) => {
  const body = await readMultipartFormData(event)  // H3 built-in
  const token = bearerToken(event)
  await supabaseUser(event)
  // 1. Extract file from multipart
  // 2. sharp(buffer).resize(256, 256, { fit: 'cover' }).jpeg({ quality: 85 }).toBuffer()
  // 3. Upload to Supabase Storage via supabaseFetch POST /storage/v1/object/profile-avatars/{userId}/{profileId}.jpg
  // 4. Return public URL
})
```

### Pattern 4: Android Profile Photo Loading with Coil

```kotlin
// Source: nexio-web/app pattern — Coil AsyncImage already used in IdleScreensaverOverlay.kt [VERIFIED]
// In profile avatar composable:
AsyncImage(
    model = ImageRequest.Builder(LocalContext.current)
        .data(profile.avatarUrl)
        .crossfade(true)
        .build(),
    contentDescription = profile.name,
    contentScale = ContentScale.Crop,
    modifier = Modifier.size(48.dp).clip(CircleShape),
    fallback = rememberVectorPainter(Icons.Default.Person)
)
// URL-based invalidation: Coil uses the URL as cache key by default.
// When avatarUrl changes in sync payload, new URL triggers re-fetch automatically.
```

### Pattern 5: Android Supabase Storage Plugin Install

```kotlin
// Source: supabase-kt documentation [ASSUMED — verified BOM 3.1.4 includes storage-kt]
// SupabaseModule.kt extension:
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.storage

// Inside createSupabaseClient { ... }
install(Storage)

// Provide it:
@Provides @Singleton
fun provideSupabaseStorage(client: SupabaseClient): Storage = client.storage
```

### Anti-Patterns to Avoid

- **Account-level secrets table for per-profile tokens:** The existing `account_secrets` table stores Trakt/Simkl tokens at account scope. Per-profile tokens need a separate table (e.g., `profile_auth_tokens`) keyed by `(user_id, profile_id, token_type)`. Do not overload the existing table.
- **Extending usePortalStore for profile management:** `usePortalStore.ts` is already 2500+ lines and manages account-level state. Profile management belongs in a new `useProfileStore.ts` composable to keep separation of concerns. The profile store can call the same underlying `supabaseFetch` utilities.
- **Reusing the v7/v8 sync contract for per-profile settings pushed from web:** The web portal pushes per-profile settings via a dedicated server route that calls a new Supabase RPC. The Android side pulls via the same RPC on launch/switch. Do not route per-profile web pushes through the v7 `persist.post.ts` endpoint — that endpoint is account-scoped.
- **Using signed Supabase Storage URLs for avatars:** The project decided on a public bucket (D-14). Adding signed URL logic wastes complexity — public bucket URLs are permanent and Coil caches them naturally.
- **Calling Trakt/Simkl revoke in the existing `disconnectTrakt`/`disconnectSimkl` functions:** Those functions only delete local secrets. Per-profile unlink (D-12) requires a new server route that calls the provider revoke endpoint before deleting from Supabase.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Image resizing | Custom byte-manipulation | `sharp` (Node.js) | Handles EXIF rotation, WebP, JPEG quality, alpha channel — edge cases are brutal to get right |
| Drag-and-drop reorder | Custom mouse/touch handlers | `vuedraggable` (already installed) | Used by `CatalogInventory.vue`; handles keyboard accessibility, touch, Vue 3 list mutation |
| URL-based Coil cache invalidation | Manual disk cache eviction | Coil's default URL-as-key behavior | Coil automatically re-fetches when the URL string changes; no manual invalidation needed |
| Supabase Storage upload | Direct HTTP with fetch | supabase-storage-kt (Android) / `supabaseFetch` to Storage REST API (web) | Handles content-type headers, storage path encoding, error responses |
| Bearer token forwarding in Nuxt routes | Custom auth middleware | `bearerToken()` + `supabaseUser()` from `~/server/utils/supabase` | Already established pattern; skip the boilerplate |

**Key insight:** The existing portal codebase is mature. The job is wiring — pass `profile_id` into existing patterns, not building new ones.

---

## Common Pitfalls

### Pitfall 1: profile_id=1 (Primary Profile) Not Editable From Non-Primary Auth Paths
**What goes wrong:** Web UI mistakenly allows non-master users to modify profile 1 data. The delete button and certain edit paths need to guard against `profile_index === 1` on the server side (not just the UI).
**Why it happens:** UI-only guard is easy to bypass via direct API calls.
**How to avoid:** Supabase RPC for delete and rename must assert `profile_index != 1` server-side, not just hide the delete button in the UI.
**Warning signs:** A server route for delete/rename that doesn't check `profile_index` in the WHERE clause.

### Pitfall 2: Supabase Storage Public URL Path Collision
**What goes wrong:** Two users upload a profile photo with the same filename (e.g., `profile_2.jpg`). The public bucket has no per-user namespace.
**Why it happens:** Bucket path is not namespaced by `user_id`.
**How to avoid:** Always namespace by `{user_id}/{profile_id}.jpg` (or include a timestamp/hash suffix). Path: `profile-avatars/{userId}/{profileIndex}.jpg`.
**Warning signs:** Upload route that only uses `profile_id` as the path without the user scope.

### Pitfall 3: Trakt Token Scope Mismatch After Web Auth
**What goes wrong:** Web links Trakt for Profile 2; Android already has Profile 2 Trakt tokens from a prior TV OAuth. Both are valid but the TV-side tokens are stored in a different DataStore path than the new Supabase-synced tokens.
**Why it happens:** D-11 says most-recent-wins, but the pull logic needs to check timestamp and actually overwrite the local DataStore, not just skip if something exists locally.
**How to avoid:** Pull logic for per-profile auth tokens must always overwrite local DataStore values when the remote `updated_at` is newer than the local `last_linked_at`.
**Warning signs:** Sync logic with an early-return guard like `if (localTokenExists) return`.

### Pitfall 4: Sharp Not Available at Nuxt Build Time
**What goes wrong:** `sharp` is a native Node.js addon. In Nuxt server routes it works fine in Node mode, but Nuxt's Nitro server can be configured for edge/cloudflare runtimes where native addons fail.
**Why it happens:** Nitro preset selection.
**How to avoid:** Confirm Nitro is set to `node` preset (default for self-hosted). If `node_modules/sharp` fails, fall back to `@squoosh/lib` or accept the image without resize with a size limit guard.
**Warning signs:** Deploy errors mentioning `sharp` native bindings.

### Pitfall 5: PortalShell nav 'profiles' View Not in activeView Guard
**What goes wrong:** Adding `profiles` view to `account.vue` nav array without adding it to the `activeView` computed guard that validates known view IDs causes the computed to fall back to `'addons'`.
**Why it happens:** The `activeView` computed in `account.vue` validates against a `nav` array — must keep both in sync.
**How to avoid:** When adding `{ id: 'profiles', label: 'Profiles' }` to the nav array, the guard `nav.some((item) => item.id === view)` automatically covers it. But verify the PortalShell `set-view` emit and mobile bottom nav also include the new entry.
**Warning signs:** Profile view URL query (`?view=profiles`) redirects to `?view=addons`.

### Pitfall 6: CatalogInventory and FormatterWorkspace Require Account-Level Settings Blob
**What goes wrong:** `CatalogInventory.vue` and `FormatterWorkspace.vue` currently receive `state.settings` from `usePortalStore` which is the account-level settings blob. For per-profile use, they need a profile-scoped settings blob loaded by `useProfileStore`.
**Why it happens:** The components themselves are generic (they receive props) — the issue is the data source.
**How to avoid:** `useProfileStore` must load and hold a separate per-profile settings blob (from the Phase 4 per-profile sync RPC). When the profile detail shell shows Catalogs or Formatter tabs, it passes the profile-scoped blob, not the account blob.
**Warning signs:** Profile 2's catalog order appears identical to Profile 1 in the web UI.

---

## Code Examples

Verified patterns from official sources:

### Supabase Storage Public URL (Web)
```typescript
// Source: existing supabaseFetch pattern in nexio-web/server/utils/supabase [VERIFIED pattern]
// Public URL format for Supabase Storage:
// {SUPABASE_URL}/storage/v1/object/public/{bucket}/{path}
const avatarUrl = `${process.env.SUPABASE_URL}/storage/v1/object/public/profile-avatars/${userId}/${profileId}.jpg`
```

### SupabaseProfile Extended with avatarUrl
```kotlin
// Source: app/src/main/java/com/nexio/tv/data/remote/supabase/SupabaseModels.kt [VERIFIED — current model]
@Serializable
data class SupabaseProfile(
    val id: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("profile_index") val profileIndex: Int,
    val name: String = "",
    @SerialName("avatar_color_hex") val avatarColorHex: String = "#1E88E5",
    @SerialName("uses_primary_addons") val usesPrimaryAddons: Boolean = false,
    @SerialName("uses_primary_plugins") val usesPrimaryPlugins: Boolean = false,
    // NEW FIELD for Phase 5:
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)
```

### Coil AsyncImage with Fallback (Android — profile avatar)
```kotlin
// Source: app/src/main/java/com/nexio/tv/ui/screensaver/IdleScreensaverOverlay.kt [VERIFIED — existing pattern]
// Extended for profile avatar with color fallback per D-16:
if (profile.avatarUrl != null) {
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(profile.avatarUrl)
            .crossfade(true)
            .build(),
        contentDescription = profile.name,
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(48.dp).clip(CircleShape)
    )
} else {
    // Existing ProfileAvatarColors circle — no change needed
    ProfileAvatarCircle(color = profile.avatarColorHex, name = profile.name)
}
```

### H3 Multipart File Read (Nuxt server route)
```typescript
// Source: H3 built-in [ASSUMED — standard H3 API]
import { readMultipartFormData } from 'h3'
export default defineEventHandler(async (event) => {
  const parts = await readMultipartFormData(event)
  const file = parts?.find(p => p.name === 'photo')
  if (!file?.data) throw createError({ statusCode: 400, statusMessage: 'No file provided.' })
  // file.data is Buffer, file.type is MIME type
})
```

### PortalShell nav extension
```typescript
// Source: nexio-web/components/portal/PortalShell.vue [VERIFIED — existing pattern]
// Add to sidebar nav in PortalShell.vue — follows existing button pattern:
<button
  @click="$emit('set-view', 'profiles'); isMobileMenuOpen = false"
  class="w-full flex items-center gap-3 px-6 py-3 transition-colors font-headline text-sm font-medium tracking-wide group"
  :class="activeView === 'profiles' ? 'bg-gradient-to-r from-violet-500/10 to-transparent text-violet-300 border-l-4 border-violet-500' : 'text-zinc-500 hover:text-zinc-300'"
>
  <!-- Material Symbols: group icon -->
  <span class="material-symbols-outlined text-xl flex-shrink-0">group</span>
  <span>Profiles</span>
</button>
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Account-level Trakt/Simkl tokens (single token per account) | Per-profile tokens scoped by `profile_id` | Phase 5 | Requires new `profile_auth_tokens` table; existing account-level secrets stay for Profile 1 compatibility |
| `SupabaseProfile` without `avatar_url` | Extended with `avatar_url: String?` field | Phase 5 | Null-safe — existing sync code reads it as null, no migration needed |
| Android Supabase client: Auth + Postgrest only | Auth + Postgrest + Storage | Phase 5 | Storage plugin added to `SupabaseModule`; no other changes to the DI graph |
| nexio-web portal: 4 views (addons, catalogs, integrations, formatter) | 5 views — new `profiles` view | Phase 5 | nav array + PortalShell sidebar + mobile bottom nav each get one entry |

**Deprecated / outdated:**
- Account-level `disconnectTrakt` / `disconnectSimkl` in `usePortalStore`: These remain unchanged for account (Profile 1) use. New per-profile disconnect routes must call provider revoke APIs (the account-level functions do not), per D-12.

---

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | `sharp` integrates cleanly in Nuxt 4 server routes with the default Node Nitro preset | Standard Stack / Pitfall 4 | Image resize fails silently on deploy; fall back to accepting images without server resize, enforcing client-side size limit |
| A2 | Supabase BOM 3.1.4 includes `storage-kt` as a versioned artifact that installs cleanly | Standard Stack | Must verify against actual Maven artifact; alternative is to specify a version explicitly |
| A3 | Coil's default URL-as-cache-key behavior makes URL-based invalidation automatic (no manual cache clear needed) | Architecture Patterns Pattern 4 | If Coil uses a content-hash key instead of URL, URL changes won't trigger re-fetch; would need explicit cache invalidation |
| A4 | Public Supabase Storage bucket URLs are permanent and do not expire | Standard Stack Alternatives | If Supabase changes URL structure or requires signed URLs for security, TV app would need to refresh URLs |
| A5 | Per-profile settings blob structure from Phase 4 (v8 contract) will be defined before Phase 5 implementation begins | Pitfall 6 | If Phase 4 RPC schema is not finalized, `useProfileStore` cannot load per-profile catalog/formatter settings; Phase 5 plans should document the dependency on Phase 4 completion |

---

## Open Questions (RESOLVED)

1. **Phase 4 RPC availability**
   - What we know: Phase 4 defines per-profile settings blobs via new Supabase RPCs (v8 contract). Phase 5 depends on them to load per-profile catalog/formatter settings in the web portal.
   - Resolution: Phase 4 Plan 02 and Summary resolve the RPC names as `sync_push_profile_settings_blob` and `sync_pull_profile_settings_blob`. The Android service calls them with `p_profile_id`, `p_settings_json`, and `p_platform` for push, and `p_profile_id`, `p_platform` for pull. Phase 5 Plan 04 must use these exact names and must not fall back to empty settings.

2. **profile_auth_tokens table design**
   - What we know: Per-profile Trakt/Simkl tokens need a separate table from the existing `account_secrets` table. The new table needs `(user_id, profile_id, token_type, token_value, updated_at)`.
   - Resolution: Use the dedicated `profile_auth_tokens` table planned in 05-01. This honors the Phase 5 plan boundary and avoids overloading the existing account-scoped `account_secrets` table. Store `token_payload` as JSONB and have Android decode it as JSON (`JsonObject` / `Map<String, JsonElement>`), not `Map<String, String>`, because OAuth payloads include numeric fields such as `created_at` and `expires_in`.

3. **Trakt/Simkl per-profile device flow vs redirect flow**
   - What we know: The TV app uses device code flow (code + poll). The web portal also currently uses device code flow for account-level Trakt/Simkl.
   - Resolution: Locked decision D-10 controls: web handles OAuth redirect in the browser and stores tokens in Supabase scoped to profile ID. Phase 5 Plan 03 must implement redirect/callback routes for per-profile web linking, not new web device-code flows.

4. **Simkl revoke endpoint**
   - What we know: Local inspection shows `SimklAuthService.revokeAndLogout()` only clears local auth and `SimklApi.kt` has no revoke endpoint method. Trakt has `@POST("oauth/revoke") suspend fun revokeToken(...)`.
   - Resolution: D-12 still requires full revoke for Simkl. Phase 5 Plan 03 must include a blocking verification/decision task before implementing Simkl disconnect. If executor cannot confirm an official Simkl revoke endpoint from the provider API, execution must stop for a decision instead of silently deleting only Supabase tokens.

---

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Node.js | Nuxt server routes | ✓ | (darwin, project runs locally) | — |
| npm / package.json | sharp install | ✓ | (nexio-web/package.json exists) | — |
| sharp (Node native) | Photo resize (D-15) | ✗ (not in package.json) | — | Accept image with client-side size limit guard only |
| supabase-storage-kt | Android Storage plugin | ✗ (not in libs.versions.toml) | — | Cannot upload from Android without adding; no fallback for WEB-05 |
| Coil 2.7.0 | Android avatar display | ✓ | 2.7.0 | — |
| Supabase project (live) | All Supabase RPCs | [ASSUMED: ✓] | 2.x | — |

**Missing dependencies that must be added before implementation:**
- `sharp`: `cd nexio-web && npm install sharp`
- `supabase-storage-kt`: add to `libs.versions.toml` + `build.gradle.kts`

---

## Validation Architecture

> `workflow.nyquist_validation` not present in `.planning/config.json` — treating as enabled.

### Test Framework

| Property | Value |
|----------|-------|
| Framework | Gradle JUnit (Android) — `./gradlew testArm64DebugUnitTest` |
| Config file | `app/build.gradle.kts` |
| Quick run command | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.sync.*" -x lint` |
| Full suite command | `./gradlew testArm64DebugUnitTest` |

Web-side (Nuxt): no test framework detected in `package.json`. No automated tests for server routes exist in the project — manual testing via local dev server is the current approach.

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| WEB-01 | Profile CRUD RPCs — create/rename/delete | Unit (Android sync) | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.sync.ProfileSyncServiceTest"` | ❌ Wave 0 |
| WEB-02 | Per-profile Trakt/Simkl auth sync to device | Unit (Android sync) | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.sync.ProfileAuthSyncTest"` | ❌ Wave 0 |
| WEB-03 | Catalog ordering persists after sync pull | Unit (Android sync) | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.sync.ProfileCatalogSyncTest"` | ❌ Wave 0 |
| WEB-04 | Formatter settings persist after sync pull | Unit (Android sync) | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.sync.ProfileFormatterSyncTest"` | ❌ Wave 0 |
| WEB-05 | Avatar URL in sync payload triggers Coil re-fetch | Unit (Android) | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.profile.ProfileAvatarTest"` | ❌ Wave 0 |

### Sampling Rate

- **Per task commit:** `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.sync.*" -x lint`
- **Per wave merge:** `./gradlew testArm64DebugUnitTest`
- **Phase gate:** Full suite green before `/gsd-verify-work`

### Wave 0 Gaps

- [ ] `app/src/test/java/com/nexio/tv/sync/ProfileSyncServiceTest.kt` — covers WEB-01
- [ ] `app/src/test/java/com/nexio/tv/sync/ProfileAuthSyncTest.kt` — covers WEB-02
- [ ] `app/src/test/java/com/nexio/tv/sync/ProfileCatalogSyncTest.kt` — covers WEB-03
- [ ] `app/src/test/java/com/nexio/tv/sync/ProfileFormatterSyncTest.kt` — covers WEB-04
- [ ] `app/src/test/java/com/nexio/tv/profile/ProfileAvatarTest.kt` — covers WEB-05

---

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | yes | Supabase Auth JWT verified in every server route via `supabaseUser(event)` [VERIFIED: pattern in persist.post.ts, bootstrap.get.ts] |
| V3 Session Management | no | Session is managed by Supabase Auth — not custom |
| V4 Access Control | yes | Server routes must verify the requesting user owns the `profile_id` being modified (user_id check in RPC) |
| V5 Input Validation | yes | Profile name length limit server-side; image MIME type check before sharp; `profile_id` must be integer 1–4 |
| V6 Cryptography | no | No custom crypto; Supabase Storage handles encryption at rest |

### Known Threat Patterns for This Stack

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| IDOR — modify another user's profile by guessing profile_id | Tampering | All Supabase RPCs filter by `auth.uid()` — never trust client-supplied `user_id` |
| Malicious file upload (non-image uploaded as photo) | Tampering | Check `file.type` MIME type in server route; pass through `sharp` (fails on non-image input) before storing |
| OAuth token exfiltration via public bucket URL | Information Disclosure | Tokens are never stored in Supabase Storage; only resized avatar images go in the public bucket |
| Primary profile delete via API bypass | Tampering | Supabase RPC for delete must WHERE `profile_index != 1`; UI guard alone is insufficient |
| Stale avatar URL served from deleted profile | Information Disclosure | When a profile is deleted (Phase 4), the corresponding Storage object should be deleted too; Phase 5 plan should include a cleanup step |

---

## Sources

### Primary (HIGH confidence)
- `nexio-web/package.json` — confirmed all web dependency versions
- `nexio-web/nuxt.config.ts` — confirmed Nuxt 4 + runtimeConfig structure
- `gradle/libs.versions.toml` + `app/build.gradle.kts` — confirmed Supabase 3.1.4, Coil 2.7.0, no Storage plugin
- `nexio-web/composables/usePortalStore.ts` — confirmed device-flow pattern, disconnect functions, state shape
- `nexio-web/server/api/account/persist.post.ts` + `bootstrap.get.ts` — confirmed H3 server route pattern with bearerToken + supabaseUser
- `nexio-web/server/api/integrations/trakt/` + `simkl/` — confirmed existing device-code + device-token routes
- `app/src/main/java/com/nexio/tv/data/remote/supabase/SupabaseModels.kt` — confirmed current SupabaseProfile shape
- `app/src/main/java/com/nexio/tv/core/di/SupabaseModule.kt` — confirmed Auth + Postgrest only, no Storage
- `app/src/main/java/com/nexio/tv/ui/screensaver/IdleScreensaverOverlay.kt` — confirmed Coil AsyncImage usage pattern
- `nexio-web/DESIGN.md` + `05-UI-SPEC.md` — confirmed Obsidian Lens design system and Phase 5 component spec

### Secondary (MEDIUM confidence)
- Supabase Kotlin BOM 3.1.4 includes `storage-kt` — inferred from BOM structure and the fact that auth-kt and postgrest-kt both use `version.ref = "supabase"` without explicit versions. Storage is a first-party module in the same BOM [ASSUMED A2].

### Tertiary (LOW confidence)
- `sharp` Node.js library behavior in Nuxt 4 Nitro Node preset — standard community practice, not verified via live test [ASSUMED A1]

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all versions verified from project files
- Architecture: HIGH — patterns verified from existing codebase
- Pitfalls: MEDIUM — derived from code inspection; some are speculative until Phase 4 schema is finalized
- Security: MEDIUM — ASVS categories inferred from stack; specific RPC SQL not yet written

**Research date:** 2026-04-14
**Valid until:** 2026-05-14 (30 days — stable stack, no fast-moving dependencies)
