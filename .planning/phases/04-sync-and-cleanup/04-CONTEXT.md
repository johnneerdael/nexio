# Phase 4: Sync and Cleanup - Context

**Gathered:** 2026-04-14
**Status:** Ready for planning

<domain>
## Phase Boundary

Profile metadata and per-profile settings sync to Supabase via a new v8 contract, and deleting a profile removes all associated data on-device and in the cloud with no orphans remaining. SharedPreferences-based snapshot stores are classified and scoped per-profile where applicable.

</domain>

<decisions>
## Implementation Decisions

### Sync Trigger & Conflict Strategy
- **D-01:** Sync is event-driven with debounce — push on profile edit, settings change, and profile switch. Use NuvioTV 2s flatMapLatest debounce pattern. Pull on app start only.
- **D-02:** Conflict resolution is last-write-wins using server-side `updated_at` timestamp. No merge logic — latest push overwrites.
- **D-03:** Upgrade to a v8 sync contract that is profile-aware from the ground up. Per-profile settings get their own blob RPCs keyed by profileId. Shared settings remain in the contract. Clean break from v7 — no backwards-compat shims.

### Deletion Flow & Error Handling
- **D-04:** Profile deletion uses best-effort remote cleanup. Local data (DataStore files, SharedPreferences files) is deleted immediately. Remote cleanup (Supabase row deletion, remote blob deletion) retries on next app start if it fails. Profile disappears from UI right away.
- **D-05:** No Trakt revoke API call on deletion — just clear local tokens for both Trakt and Simkl. Simpler and Trakt tokens expire in 90 days anyway.
- **D-06:** Deletion requires a confirmation dialog showing the profile name: "Delete profile '{name}'? This removes all settings and sync data." Uses NexioDialog with "Keep Profile" (auto-focused) and "Delete Profile" buttons per UI-SPEC.

### Snapshot Store Classification
- **D-07:** 7 SharedPreferences stores become per-profile: TraktLibrarySnapshotStore, ContinueWatchingSnapshotStore, SimklLibrarySnapshotStore, SimklDiscoverySnapshotStore, SimklProgressSyncStateStore, TraktMutationOutboxStore, TraktDiscoverySnapshotStore.
- **D-08:** 5 SharedPreferences stores remain shared: HomeCatalogSnapshotStore, MetadataDiskCacheStore, CatalogDiskCacheStore, MDBListDiscoverySnapshotStore, SyntheticHomeCatalogStore.
- **D-09:** Per-profile SharedPreferences stores use the same `_p{id}` suffix convention as ProfileDataStoreFactory — bare name for Profile 1, `_p2`/`_p3`/`_p4` for others.

### Sync Status UI Feedback
- **D-10:** Background sync is completely silent — no toasts, no indicators during normal use. Status only visible when user taps "Sync Now" in Settings.
- **D-11:** A "Sync Now" button exists in the Profiles area of Settings. Shows brief feedback ("Synced" or "Failed") after completion. D-pad focusable.
- **D-12:** Startup pull is fully silent — no loading indication, no splash hold. If remote data arrives after UI renders, settings update silently in background.

### Claude's Discretion
- Supabase RPC naming and table schema design for profile metadata and settings blobs
- Debounce implementation details (coroutine scope, cancellation handling)
- Retry mechanism for failed remote cleanup on next app start
- v8 contract internal structure and migration path from v7

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Sync Architecture
- `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt` — Current shared settings sync service (v7 contract reference)
- `app/src/main/java/com/nexio/tv/core/sync/StartupSyncService.kt` — Startup sync orchestration
- `app/src/main/java/com/nexio/tv/data/remote/supabase/AccountSyncModels.kt` — Supabase sync data models

### Profile Infrastructure
- `app/src/main/java/com/nexio/tv/data/local/ProfileDataStoreFactory.kt` — Per-profile DataStore factory with `clearProfile()` deletion
- `app/src/main/java/com/nexio/tv/data/local/ProfileDataStore.kt` — Profile CRUD and `deleteProfile()` method

### Snapshot Stores (classification targets)
- `app/src/main/java/com/nexio/tv/data/local/TraktLibrarySnapshotStore.kt` — Per-profile
- `app/src/main/java/com/nexio/tv/data/local/ContinueWatchingSnapshotStore.kt` — Per-profile
- `app/src/main/java/com/nexio/tv/data/local/SimklLibrarySnapshotStore.kt` — Per-profile
- `app/src/main/java/com/nexio/tv/data/local/SimklDiscoverySnapshotStore.kt` — Per-profile
- `app/src/main/java/com/nexio/tv/data/local/SimklProgressSyncStateStore.kt` — Per-profile
- `app/src/main/java/com/nexio/tv/data/trakt/outbox/TraktMutationOutboxStore.kt` — Per-profile
- `app/src/main/java/com/nexio/tv/data/local/TraktDiscoverySnapshotStore.kt` — Per-profile
- `app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt` — Shared
- `app/src/main/java/com/nexio/tv/data/local/MetadataDiskCacheStore.kt` — Shared
- `app/src/main/java/com/nexio/tv/data/local/CatalogDiskCacheStore.kt` — Shared

### UI Components
- `app/src/main/java/com/nexio/tv/ui/components/NexioDialog.kt` — Dialog component for delete confirmation

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `ProfileDataStoreFactory.clearProfile()` — Already handles DataStore file cleanup for a profile
- `AccountSettingsSyncService` — Existing sync service to extend/replace for v8 contract
- `NexioDialog` — Reusable dialog component matching UI-SPEC delete confirmation design
- `StartupSyncService` — Existing startup hook for pull-on-launch behavior

### Established Patterns
- ConcurrentHashMap cache with lazy init (ProfileDataStoreFactory) — apply same pattern to SharedPreferences scoping
- flatMapLatest for reactive profile switching (Phase 2 pattern) — reuse for sync debounce
- `_p{id}` suffix convention for per-profile file naming — extend to SharedPreferences

### Integration Points
- Settings screen: "Sync Now" button and delete confirmation dialog integrate into existing settings UI
- Profile deletion: Extends `ProfileManager.deleteProfile()` to include SharedPreferences and remote cleanup
- Startup flow: Extends `StartupSyncService` with profile metadata pull

</code_context>

<specifics>
## Specific Ideas

No specific requirements — open to standard approaches based on NuvioTV reference implementation.

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope.

</deferred>

---

*Phase: 04-sync-and-cleanup*
*Context gathered: 2026-04-14*
