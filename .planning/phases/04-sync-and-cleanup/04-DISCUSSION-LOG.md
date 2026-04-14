# Phase 4: Sync and Cleanup - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-04-14
**Phase:** 04-sync-and-cleanup
**Areas discussed:** Snapshot store scoping, Settings sync transport, Sync timing and triggers, Deletion cleanup scope

---

## Snapshot Store Scoping

| Option | Description | Selected |
|--------|-------------|----------|
| Per-profile (Recommended) | Each profile gets its own snapshot file. Switching profiles loads correct library from cache. | ✓ |
| Clear on switch | Keep shared singleton but clear() on profile switch. No instant cache. | |
| You decide | Claude picks based on NuvioTV patterns. | |

**User's choice:** Per-profile for TraktLibrarySnapshotStore and ContinueWatchingSnapshotStore
**Notes:** Matches Phase 2 per-profile Trakt auth — library data is profile-scoped.

| Option | Description | Selected |
|--------|-------------|----------|
| Both per-profile (Recommended) | SimklLibrary per-profile (auth-tied). SimklDiscovery per-profile (catalog visibility). | ✓ |
| Library per-profile, Discovery shared | SimklLibrary per-profile. SimklDiscovery stays shared. | |
| You decide | Claude picks. | |

**User's choice:** Both per-profile for SimklLibrarySnapshotStore and SimklDiscoverySnapshotStore

| Option | Description | Selected |
|--------|-------------|----------|
| All per-profile (Recommended) | Catalog order and visibility are per-profile settings. Cached home screen should match. | ✓ |
| HomeCatalog per-profile, rest shared | HomeCatalog switches per-profile. TraktDiscovery and MDBListDiscovery stay shared. | |
| All shared, clear on switch | Simplest migration but loses cache benefits. | |
| You decide | Claude picks. | |

**User's choice:** All per-profile for TraktDiscoverySnapshotStore, MDBListDiscoverySnapshotStore, HomeCatalogSnapshotStore

| Option | Description | Selected |
|--------|-------------|----------|
| Per-profile where tied to auth (Recommended) | SimklProgressSyncState, TraktMutationOutbox, SyntheticHomeCatalog per-profile. MetadataDiskCache and CatalogDiskCache shared. | ✓ |
| All per-profile | Everything per-profile. Simpler mental model but more files. | |
| You decide | Claude picks. | |

**User's choice:** Per-profile where tied to auth for non-snapshot SharedPreferences stores

---

## Settings Sync Transport

| Option | Description | Selected |
|--------|-------------|----------|
| Port NuvioTV blob pattern (Recommended) | New ProfileSettingsSyncService serializes per-profile DataStores into JSON blob. Dedicated RPCs. Coexists with v7. | ✓ |
| Extend v7 contract | Add per-profile sections to existing payload. Risks overwrite. | |
| You decide | Claude picks. | |

**User's choice:** Port NuvioTV blob pattern

| Option | Description | Selected |
|--------|-------------|----------|
| Keep v7 untouched (Recommended) | v7 stays for shared settings. Per-profile settings removed from v7, moved to blob only. | ✓ |
| v7 stays, duplicate to per-profile | Redundant sync but avoids breaking v7 for single-profile users. | |
| You decide | Claude picks. | |

**User's choice:** Keep v7 untouched, remove per-profile settings from v7

| Option | Description | Selected |
|--------|-------------|----------|
| Dedicated RPCs (Recommended) | Port NuvioTV ProfileSyncService with sync_push_profiles / sync_pull_profiles. | ✓ |
| Combine with settings blob | Bundle metadata into settings blob. Fewer RPCs but mixes concerns. | |
| You decide | Claude picks. | |

**User's choice:** Dedicated RPCs for profile metadata sync

| Option | Description | Selected |
|--------|-------------|----------|
| Already exist | RPCs already defined in Supabase. | |
| Need to be created | RPCs don't exist yet — this phase includes backend SQL. | ✓ |
| Will handle separately | Backend work out of scope. | |

**User's choice:** Supabase RPCs need to be created as part of this phase

---

## Sync Timing and Triggers

| Option | Description | Selected |
|--------|-------------|----------|
| Debounced on change (Recommended) | Port NuvioTV ~1.5s debounce, observe changes via flatMapLatest, auto-push. | ✓ |
| On app background | Push only on onStop. Fewer RPCs but risk of data loss. | |
| You decide | Claude picks. | |

**User's choice:** Debounced on change for per-profile settings push

| Option | Description | Selected |
|--------|-------------|----------|
| On startup + profile switch (Recommended) | Pull on app startup and profile switch. Ensures fresh cross-device data. | ✓ |
| On startup only | Pull once on startup. Misses in-session cross-device changes. | |
| You decide | Claude picks. | |

**User's choice:** On startup + profile switch for per-profile settings pull

| Option | Description | Selected |
|--------|-------------|----------|
| Immediately on edit (Recommended) | Push after any create/edit/delete. Infrequent and small. | ✓ |
| Debounced like settings | Same debounce pattern. Consistent but unnecessary. | |
| You decide | Claude picks. | |

**User's choice:** Immediately on edit for profile metadata push

---

## Deletion Cleanup Scope

| Option | Description | Selected |
|--------|-------------|----------|
| Per-profile SP names (Recommended) | Profile-suffixed SharedPreferences names. Delete by suffix on deletion. Profile 1 keeps bare names. | ✓ |
| Clear via store API | Call each store's clear() method. Requires injecting all stores into cleanup coordinator. | |
| You decide | Claude picks. | |

**User's choice:** Per-profile SharedPreferences names with suffix-based cleanup

| Option | Description | Selected |
|--------|-------------|----------|
| Yes, via Supabase RPC (Recommended) | sync_delete_profile RPC removes profile row and settings blob server-side. | ✓ |
| Local only, remote eventually | Next sync_push_profiles omits deleted profile. Implicit cleanup. | |
| You decide | Claude picks. | |

**User's choice:** Supabase RPC for remote profile data deletion

| Option | Description | Selected |
|--------|-------------|----------|
| Revoke remotely (Recommended) | Call Trakt/Simkl token revocation API before discarding local tokens. | ✓ |
| Discard locally only | Delete local auth DataStore. Tokens expire eventually. | |
| Best-effort revoke | Attempt revocation, don't block deletion on failure. | |
| You decide | Claude picks. | |

**User's choice:** Revoke Trakt/Simkl tokens remotely on profile deletion

| Option | Description | Selected |
|--------|-------------|----------|
| Proceed anyway (Recommended) | Best-effort remote cleanup. Always complete local deletion regardless. Log failures. | ✓ |
| Block until success | Require successful remote cleanup. Deletion fails offline. | |
| You decide | Claude picks. | |

**User's choice:** Best-effort remote cleanup — always proceed with local deletion

---

## Claude's Discretion

- Order of snapshot store migrations
- JSON blob schema for per-profile settings
- Supabase SQL function signatures and table schema
- Pull-before-push gate logic for per-profile settings
- Error retry strategy for failed remote cleanup

## Deferred Ideas

None — discussion stayed within phase scope
