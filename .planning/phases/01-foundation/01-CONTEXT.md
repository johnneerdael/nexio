# Phase 1: Foundation - Context

**Gathered:** 2026-04-14
**Status:** Ready for planning

<domain>
## Phase Boundary

This phase creates the base infrastructure for multi-profile support: ProfileDataStoreFactory (per-profile DataStore isolation), ProfileDataStore (profile list persistence), ProfileManager (CRUD + active profile StateFlow), ProfileModule (Hilt DI), and UserProfile model extension. No user-facing UI changes. No DataStore migration of existing stores (that's Phase 2).

</domain>

<decisions>
## Implementation Decisions

### Serialization
- **D-01:** Use Gson (not Moshi) for ProfileDataStore JSON serialization of `List<UserProfile>`. Nexio already uses Gson throughout; this keeps consistency. Create a `ProfileJson` DTO class with `@SerializedName` annotations for the Gson adapter.

### UserProfile Model
- **D-02:** Add `avatarId: String? = null` — references entry in Supabase avatar catalog.
- **D-03:** Add `pinEnabled: Boolean = false` — local reflection of server-side PIN lock state.
- **D-04:** Do NOT add `usesPrimaryPlugins` — plugins are not a Nexio concept.
- **D-05:** Preserve existing fields: `id`, `name`, `avatarColorHex`, `usesPrimaryAddons`, `isPrimary` (computed).

### First-Launch Migration
- **D-06:** Silent migration — ProfileDataStore auto-creates Profile 1 with name "Default" on first read when no profiles exist. No UI prompt. Existing single-profile users never notice the change.
- **D-07:** Profile 1 always uses bare DataStore filenames (no `_p1` suffix) — ensures zero data migration for existing users. This is enforced in ProfileDataStoreFactory.

### Architecture
- **D-08:** Port NuvioTV's ProfileDataStoreFactory pattern directly — ConcurrentHashMap cache with lazy init, `deletedProfileIds` tracking for safe profile re-creation.
- **D-09:** ProfileManager max 4 profiles, IDs 1-4 with slot reuse on deletion. Profile 1 cannot be deleted.
- **D-10:** All new classes use `@Singleton` + `@Inject constructor` — Hilt auto-discovers them. ProfileModule is a marker `@Module @InstallIn(SingletonComponent)` with no explicit `@Provides`.

### Claude's Discretion
- Profile default avatar color assignment for new profiles (can cycle through ProfileAvatarColors)
- Internal ProfileJson DTO field naming conventions
- Error handling strategy for corrupted profile JSON

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### NuvioTV Reference Implementation (port source)
- `~/Scripts/NuvioTV/app/src/main/java/com/nuvio/tv/data/local/ProfileDataStoreFactory.kt` — ConcurrentHashMap factory, bare filename for profile 1, deletedProfileIds tracking
- `~/Scripts/NuvioTV/app/src/main/java/com/nuvio/tv/data/local/ProfileDataStore.kt` — Profile list persistence with Moshi JSON (adapt to Gson for Nexio)
- `~/Scripts/NuvioTV/app/src/main/java/com/nuvio/tv/core/profile/ProfileManager.kt` — CRUD, max 4, activeProfileId StateFlow, deletion cleanup
- `~/Scripts/NuvioTV/app/src/main/java/com/nuvio/tv/core/di/ProfileModule.kt` — Marker Hilt module
- `~/Scripts/NuvioTV/app/src/main/java/com/nuvio/tv/domain/model/UserProfile.kt` — Extended model with avatarId

### Nexio Existing Code (integration points)
- `app/src/main/java/com/nexio/tv/domain/model/UserProfile.kt` — Current model to extend
- `app/src/main/java/com/nexio/tv/domain/model/ProfileAvatarColors.kt` — 8 predefined avatar colors
- `app/src/main/java/com/nexio/tv/core/di/` — Existing Hilt DI modules (pattern reference)

### Research
- `.planning/research/ARCHITECTURE.md` — Component responsibilities, build order, anti-patterns
- `.planning/research/PITFALLS.md` — Pitfall 1 (delegate coexistence), Pitfall 7 (Profile 1 bare filenames)
- `.planning/research/STACK.md` — New files list, integration points

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `UserProfile.kt` — existing data class, extend with new fields
- `ProfileAvatarColors.kt` — 8 hex colors for default avatar assignment
- Existing Hilt `@Module` pattern in `core/di/` — follow same structure

### Established Patterns
- All DataStores use `@Singleton` + `@Inject constructor(@ApplicationContext context: Context)` + `preferencesDataStore` delegate
- Hilt modules use `@InstallIn(SingletonComponent::class)`
- Gson is the JSON library used across the codebase

### Integration Points
- `core/di/` — ProfileModule.kt will be added here
- `core/profile/` — New directory for ProfileManager
- `data/local/` — ProfileDataStore and ProfileDataStoreFactory alongside existing DataStores
- `domain/model/` — UserProfile.kt modification

</code_context>

<specifics>
## Specific Ideas

- NuvioTV ProfileDataStoreFactory is the canonical reference — port it, don't reinvent
- Gson adapter with `ProfileJson` DTO mirrors NuvioTV's Moshi `ProfileJson` pattern but with `@SerializedName` annotations
- ProfileManager should expose `profiles: StateFlow<List<UserProfile>>` and `activeProfileId: StateFlow<Int>` exactly as NuvioTV does

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 01-foundation*
*Context gathered: 2026-04-14*
