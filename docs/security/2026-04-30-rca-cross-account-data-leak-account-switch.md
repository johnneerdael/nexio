# Root Cause Analysis — Cross-Account Data Leak via Account Switch on Android

**Date opened:** 2026-04-30
**Status:** Root cause confirmed · containment guidance issued · fix not yet implemented
**Severity:** **HIGH** — silent data exfiltration across user accounts; integration credentials potentially exposed
**Reporter:** John Neerdael (`john@neerdael.nl`) — observed during normal device use
**Investigator:** This session, Claude Opus 4.7
**Codebase state at investigation:** branch `codex/integration-runtime-phase-a` @ `8a371476b`

---

## 1. Incident Summary

A user signed in on the Nexio Android app as `john@neerdael.nl`, then later signed in as `john.neerdael@gmail.com` on the same device. The local DataStore, per-profile SharedPreferences, provider auth stores, and snapshot stores from the **first** account were not cleared on sign-out. When the **second** account's sync cycle ran, `pushToRemote()` read the stale local state (still the first account's data) and upserted it into Supabase under the **second** account's `user_id` (`bf2214e3-f019-4b59-b194-2138fdab9c9e`).

Net effect: `john@neerdael.nl`'s profiles, integration tokens, addon configs, and per-profile settings are now stored on the Supabase backend under `john.neerdael@gmail.com`'s user record. Any other device that subsequently signs into `john.neerdael@gmail.com` will pull this leaked data down via `pullFromRemoteAndApply()`.

The user reported the issue under heading "this seems like a serious security issue???" after observing leaked profile records appear on a profileable build (`com.nexio.tv.profileable` on `192.168.50.98`) following a full uninstall + reinstall + sign-in to the gmail account.

---

## 2. Severity Justification

| Dimension | Assessment |
|---|---|
| **Confidentiality** | HIGH — Profile names, avatar URLs, addon URLs, and **integration API keys** (Trakt/SIMKL/Kitsu/MDBList/RealDebrid/Premiumize/TorBox/EasyDebrid) cross account boundaries. Some of these are paid-tier credentials with monetary impact if abused. |
| **Integrity** | MEDIUM — The receiving account's existing profile rows for the same `profile_index` get overwritten by `ON CONFLICT (user_id, profile_index) DO UPDATE`. |
| **Availability** | LOW — No service disruption. |
| **Scope** | Affects **every user who has ever switched accounts** on a single device install. The defect is in the default sign-out path, not behind any feature flag. |
| **Detection** | LOW signal-to-noise — the leak is silent. No telemetry alerts or audit logs surface the cross-account write. The user only noticed because they manually inspected which profiles appeared. |

---

## 3. Timeline (incident reproduction)

Reconstructed from the user's recollection + what we observed on `192.168.50.98`:

| Step | Action | Local state | Server state |
|---|---|---|---|
| T0 | User installs app, signs in as `john@neerdael.nl` (user_id = `<A>`) | `profile_settings.preferences_pb` populated with @neerdael.nl's profiles; integration auth stores populated | `public.profiles` rows for `<A>` |
| T1 | User signs out via the in-app sign-out action | `auth.signOut()` clears Supabase session + `auth_presence.preferences_pb` only. **All other DataStore + SharedPreferences files retain @neerdael.nl's data.** | Unchanged |
| T2 | User signs in as `john.neerdael@gmail.com` (user_id = `bf2214e3-f019-4b59-b194-2138fdab9c9e`) | `auth_presence.last_supabase_user_id` updated to `bf2214e3...`. `_sessionUserId.value` updated. **No purge of profile / integration data.** | Unchanged |
| T3 | App boots normally; sync coroutines fire | `profileManager.profiles.value` returns @neerdael.nl's profiles (stale state from T0) | Unchanged |
| T4 | `ProfileSyncService.pushToRemote()` runs | Reads stale profiles | `sync_push_profiles` RPC executes with `auth.uid() = bf2214e3...`. Inserts/upserts @neerdael.nl's profiles under `bf2214e3...`. |
| T5 | `AccountSettingsSyncService.pushToRemote()` runs | Reads stale integration secrets | `syncApiKeySecretToRemote` and friends push @neerdael.nl's API keys under `bf2214e3...` |
| T6 | User uninstalls app, reinstalls fresh, signs back in as `john.neerdael@gmail.com` | `pullFromRemoteAndApply()` pulls down the leaked rows from T4-T5 | Unchanged (data is still there) |
| T7 | User reports incident | Foreign profiles visible in UI; "force sync" doesn't help because pull-only re-pulls the leaked rows | Same |

---

## 4. Root Cause (confirmed in code)

**`AuthManager.signOut()` at `app/src/main/java/com/nexio/tv/core/auth/AuthManager.kt:355–368`:**

```kotlin
suspend fun signOut() {
    try {
        auth.signOut()                     // clears Supabase session
    } catch (e: Exception) {
        Log.e(TAG, "Sign out failed", e)
    }
    cachedEffectiveUserId = null            // clears in-memory cache
    cachedEffectiveUserSourceUserId = null  // clears in-memory cache
    try {
        authPresenceDataStore.clear()       // clears auth-presence marker only
    } catch (e: Exception) {
        Log.w(TAG, "Failed to clear auth presence marker on sign-out", e)
    }
}
```

This function clears **only** the Supabase session, two in-memory caches, and one DataStore file. It does NOT clear:

- `ProfileDataStore` (`profile_settings.preferences_pb`) — the local list of `UserProfile` records
- Per-profile SharedPreferences (`mdblist_discovery_snapshot.xml`, `trakt_discovery_snapshot.xml`, `simkl_discovery_snapshot_v2.xml`, etc.) created by `profilePrefsName(name, profileId)`
- Provider auth stores (each of which contains user-specific credentials):
  - `trakt_auth_store.preferences_pb`
  - `simkl_auth_store.preferences_pb`
  - `kitsu_auth_store.preferences_pb`
  - `real_debrid_auth_store.preferences_pb`
  - `premiumize_settings.preferences_pb`
  - (others — see Section 5 for the full inventory)
- Provider settings DataStores (`mdblist_settings`, `trakt_settings`, `simkl_settings`, `tmdb_settings`, `tvdb_settings`, `theintrodb_settings`, `gemini_settings`, `omdb_settings`, `poster_ratings_settings`, `animeskip_settings`, `addon_preferences`, `layout_settings`, `player_settings`, `theme`, etc.)
- Snapshot stores (`MDBListDiscoverySnapshotStore`, `SimklDiscoverySnapshotStore`, `TraktDiscoverySnapshotStore`, `ContinueWatchingSnapshotStore`, `HomeCatalogSnapshotStore`)
- `CatalogDiskCacheStore` (addon catalog HTTP responses) — `catalog_disk_cache_v1.xml`
- `MetadataDiskCache` — `metadata_disk_cache_v1.xml`
- Trace bundles in `/data/data/<pkg>/files/traces/<sessionId>/`

**Compounding defect — `AuthManager.publishAuthenticatedUser()` at `AuthManager.kt:230–256`:**

```kotlin
private suspend fun publishAuthenticatedUser(userId: String, email: String?) {
    _sessionUserId.value = userId
    if (cachedEffectiveUserSourceUserId != userId) {
        cachedEffectiveUserId = null
        cachedEffectiveUserSourceUserId = null
    }
    val computed = fullAccountStateForSupabaseUser(userId = userId, email = email)
    val newState = if (...) AuthState.SessionLost else computed
    _authState.value = newState
    if (newState is AuthState.FullAccount) {
        scope.launch {
            try {
                authPresenceDataStore.markAuthenticated(userId)
            } catch (e: Exception) { ... }
        }
    }
}
```

When a NEW user signs in (i.e. `userId != previously cached userId`), this function:
- Updates the in-memory session ID
- Resets the effective-user cache
- Writes the new `last_supabase_user_id` marker

It does **NOT** detect "this is a different user than last time" and trigger a purge. The new user's session begins with the previous user's local data fully intact.

**Synchronous push trigger — sync services don't guard against owner mismatch:**

- `ProfileSyncService.pushToRemote()` (`ProfileSyncService.kt:31`) reads `profileManager.profiles.value` and pushes whatever's there.
- `AccountSettingsSyncService.pushToRemote()` (`AccountSettingsSyncService.kt:356`) reads ~25 DataStore values and pushes them.
- Neither service compares "the owner of this local data" to "the currently authenticated user" before pushing.

**Server-side SQL is correctly scoped but cannot detect the leak:**

- `sync_push_profiles` (in `supabase/migrations/20260416020000_add_profile_pin_management.sql:363`) sets `v_user_id := auth.uid()` and inserts with the conflict key `(user_id, profile_index)`. From the database's perspective, the call is legitimate — an authenticated user wrote their own profile rows. The fact that the data semantically belonged to a different account is invisible to PostgreSQL.

---

## 5. Blast Radius — What Got Leaked

### 5.1 Confirmed leaked data types (from code-walk of `AccountSettingsSyncService` constructor at lines 172–194)

The following local DataStores all flow up through `pushToRemote()` and are at risk:

- **Theme + layout preferences** — low sensitivity
- **Provider settings**: TMDB, TVDB, MDBList, OMDB, TheIntroDB, AnimeSkip, Subtitle Translation, Poster Ratings — contains API keys
- **Debrid integrations**: Premiumize, TorBox, EasyDebrid, RealDebrid — **paid-tier API keys with monetary impact if abused**
- **Tracking integrations**: Trakt, SIMKL, Kitsu auth datastores — **OAuth refresh tokens that can act as the user across other services**
- **Profile records** (via `ProfileSyncService`) — names, avatar URLs, color, PIN-enabled flag

### 5.2 Per-profile blob (`profile_settings` table)

Each profile slot has a per-profile JSONB blob synced separately. This contains the profile's personal addon URLs, selected catalog preferences, etc.

### 5.3 What did NOT leak

- Supabase auth session itself (correctly cleared by `auth.signOut()`)
- The `auth_presence` marker (correctly cleared)
- Anything stored only in `/sdcard/...` outside `/data/data/<pkg>/` — verified the device only had screenshot files there, no Nexio app data

---

## 6. Evidence Captured

### 6.1 Code references (all line numbers verified)

- `AuthManager.kt:355` — `signOut()` defect
- `AuthManager.kt:230` — `publishAuthenticatedUser()` no-purge-on-user-change
- `ProfileSyncService.kt:31` — `pushToRemote()` no owner-guard
- `AccountSettingsSyncService.kt:356` — `pushToRemote()` no owner-guard
- `supabase/migrations/20260416020000_add_profile_pin_management.sql:363` — `sync_push_profiles` is correctly user-scoped via `auth.uid()` (server is not the bug)
- `ProfileManager.kt:355` — `signOut` does not invoke any wipe; `deleteSharedPreferencesForProfile()` exists but is only called from `deleteProfile()`, not on sign-out

### 6.2 Device evidence (`adb -s 192.168.50.98:5555` against rooted profileable build)

- `auth_presence.preferences_pb` on a 5-minute-old "fresh" install contained `last_supabase_user_id = bf2214e3-f019-4b59-b194-2138fdab9c9e` — confirming the user signed in as gmail.
- `profile_settings.preferences_pb` contained `[{"a":1,"b":"John","c":"#1E88E5",...}]` with an avatar URL pointing at the gmail user's Supabase storage path — confirming profile data was already populated on the gmail user's local state.
- 4 nexio packages installed (`com.nexio.tv`, `.profileable`, `.earlyaccess`, `com.nexioleanbackicons.tv`) with distinct UIDs (`10058`, `10064`, `10077`, `10079`) — confirming **NO** `sharedUserId` cross-package contamination.
- `bmgr enabled` reported "Backup Manager currently disabled" with only `LocalTransport` registered — confirming Auto Backup restore is **NOT** the vector on this device.
- `sdcard/Android/media/com.nexio.tv/` does not exist — confirming no external-storage persistence vector.

### 6.3 What we did NOT measure

- Server-side `created_at` / `updated_at` timestamps on the leaked profile rows (user has access to the SQL queries but hadn't run them by end of session).
- Whether other devices the user owns also pulled down the leaked rows (which would compound the surface area).
- The `auth.users.last_sign_in_at` history on Supabase to confirm the temporal ordering.

---

## 7. Containment Status

### 7.1 What was done in this session
- **None of the recommended containment actions have been confirmed performed.** The user was advised to:
  - Air-gap `192.168.50.98` from the network at the router
  - Revoke active Supabase sessions for `bf2214e3-f019-4b59-b194-2138fdab9c9e` from the Supabase Dashboard
  - Inspect leaked rows via the SQL queries provided in Section 8 below
  - Delete leaked rows via the SQL provided in Section 8 below
  - Rotate any debrid / Trakt / SIMKL / Kitsu / MDBList / Premiumize / TorBox / EasyDebrid API keys associated with `john@neerdael.nl`

### 7.2 Why "force sync" did not help

- "Force sync" in the UI calls `pullFromRemoteAndApply()` only.
- The leaked rows live server-side under `bf2214e3...`. Pull-only re-pulls them.
- Even if the user manually deletes a foreign profile locally and triggers push, `sync_push_profiles` is **additive** (`INSERT ... ON CONFLICT DO UPDATE`); it does not delete server rows whose `profile_index` is absent from the input. The leaked rows survive client-side cleanup.

---

## 8. Server-Side Cleanup (user must run; not yet performed)

### 8.1 Inspection queries (run in Supabase SQL Editor)

```sql
-- Inventory of everything stored under the gmail account
SELECT
  (SELECT count(*) FROM public.profiles
     WHERE user_id = 'bf2214e3-f019-4b59-b194-2138fdab9c9e') AS profiles_count,
  (SELECT count(*) FROM public.profile_settings
     WHERE user_id = 'bf2214e3-f019-4b59-b194-2138fdab9c9e') AS profile_settings_count,
  (SELECT count(*) FROM public.profile_auth_tokens
     WHERE user_id = 'bf2214e3-f019-4b59-b194-2138fdab9c9e') AS profile_auth_tokens_count;

-- Per-profile blob inventory
SELECT profile_index, length(blob_jsonb::text) AS bytes, created_at, updated_at
FROM public.profile_settings
WHERE user_id = 'bf2214e3-f019-4b59-b194-2138fdab9c9e'
ORDER BY profile_index;

-- Auth-token inventory (per-profile per-provider OAuth refresh tokens)
SELECT profile_index, provider, created_at, updated_at
FROM public.profile_auth_tokens
WHERE user_id = 'bf2214e3-f019-4b59-b194-2138fdab9c9e'
ORDER BY profile_index, provider;

-- Account-level secret inventory (debrid / metadata API keys)
-- (table name varies; check supabase/account_settings_sync.sql for the canonical name)
```

### 8.2 Deletion (only run after confirming which `profile_index` values are foreign)

```sql
-- Substitute (2,3,4) with whichever indices belong to the leaked @neerdael.nl data
DELETE FROM public.profile_auth_tokens
  WHERE user_id = 'bf2214e3-f019-4b59-b194-2138fdab9c9e'
    AND profile_index IN (2, 3, 4);
DELETE FROM public.profile_settings
  WHERE user_id = 'bf2214e3-f019-4b59-b194-2138fdab9c9e'
    AND profile_index IN (2, 3, 4);
DELETE FROM public.profiles
  WHERE user_id = 'bf2214e3-f019-4b59-b194-2138fdab9c9e'
    AND profile_index IN (2, 3, 4);
```

### 8.3 Credential rotation (must be done out-of-band)

- Rotate Trakt OAuth tokens (revoke + re-auth)
- Rotate SIMKL OAuth tokens
- Rotate Kitsu credentials
- Regenerate MDBList API key
- Regenerate Premiumize API key
- Regenerate TorBox API key
- Regenerate EasyDebrid API key
- Regenerate RealDebrid OAuth tokens
- Optionally rotate Supabase JWT secret if the breach scope warrants invalidating ALL existing sessions across both accounts

---

## 9. Proposed Fix Outline (for next session's brainstorm + plan)

### 9.1 Four-piece fix from initial design

1. **`LocalUserDataPurgeService.purgeAll()`** — a new service injected into `AuthManager.signOut()`. Synchronously wipes every user-bound DataStore, SharedPreferences file, snapshot store, and disk cache before `signOut()` returns. Fan-out plan: enumerate every DataStore listed in Section 5.1 + Section 4 and provide a single source-of-truth list.

2. **`AuthManager.publishAuthenticatedUser()` user-change purge** — detect `prev != null && prev != new` and refuse to publish the new user's session until purge completes. This is defense-in-depth: even if the user kills the app between sign-out and sign-in (skipping signOut entirely), the next sign-in still purges before the new identity becomes "live".

3. **Sync-service owner guards** — `AccountSettingsSyncService.pushToRemote()` and `ProfileSyncService.pushToRemote()` must record `lastSyncedOwnerUserId` per push, then refuse to push if `currentUserId != lastSyncedOwnerUserId`. On mismatch: log + abort + force a pull-only cycle to repopulate from server.

4. **`sync_push_profiles` replace-all semantics (SQL)** — extend the function to delete `(user_id, profile_index)` rows whose `profile_index` is NOT in the input array. This way even if a future regression re-introduces the local-state-not-cleared bug, push remains "client snapshot is authoritative" and won't accumulate foreign rows.

### 9.2 Edge cases to validate during brainstorm

- **TV-login QR pairing flow** (`AuthManager.startTvLoginSession`, lines around 392) — pairing a TV to a phone account creates an anonymous Supabase session that's later promoted. Does the purge fire correctly across this transition?
- **Sync-linked devices via `get_sync_owner` RPC** — the AuthManager has effective-user-ID indirection (lines 263–304). If a device's effective owner != session owner, what should the purge logic key on?
- **Anonymous Supabase sessions from QR pairing** — `signInAnonymously()` at line 347 creates real Supabase user IDs. Does signing OUT of an anonymous session properly purge?
- **JWT expired mid-flight** — `withJwtRefreshRetry` (`ProfileSyncService.kt:142`) auto-refreshes and retries. If the refresh succeeds with the SAME user, fine. But what if the refresh-token belongs to a different user (e.g. due to local state corruption)?
- **Migration of already-leaked data** — the user's `bf2214e3...` account has existing leaked rows. Should the fix include a one-time migration that, on first launch with the new code, queries `auth.users.identities` for ALL emails ever associated with the device and surfaces a "we detected potentially-leaked data, here's a cleanup wizard" UI?
- **Profileable / earlyaccess / stable variant interplay** — there are 4 packages on the user's device. Does signing out of one trigger any cross-package side effects? (Probably not given distinct UIDs, but worth confirming.)
- **Trace bundles** — should sign-out also delete `/data/data/<pkg>/files/traces/<sessionId>/`? Bundles can contain personal contentIds and search queries.

### 9.3 Security telemetry to add

Even after the fix, add observability so a future regression is caught fast:
- A boot-time check: if `last_supabase_user_id` in `auth_presence` differs from the current session's `auth.uid()` AND there's any user-bound local data, emit a `policy.cross_account_local_state` trace event (and optionally surface a banner to the user). This would have caught the original incident on first boot of the gmail account.

---

## 10. Open Questions for the User (to answer before plan-writing)

1. **Containment confirmation** — has the air-gap + Supabase session revoke + manual cleanup happened? Or is the device still online with the leaked state?
2. **Credential rotation status** — which integration credentials have already been rotated, and which are still active?
3. **Other devices** — has the user signed into `john.neerdael@gmail.com` on any other device (TV, phone, browser) since the leak? If yes, those devices may have pulled the leaked data down.
4. **Migration scope** — should the fix include a server-side audit query that scans for likely cross-account contamination across all Supabase users (e.g. `profiles` rows where `created_at` predates the corresponding `auth.users.created_at`, or where the email-domain pattern of the displayed name doesn't match the user's auth email)?
5. **Disclosure obligations** — does the project have a security disclosure / advisory policy? If shipped Nexio installs are affected by this defect (i.e. the same code is in the public release channel), are users entitled to notification?
6. **Variant matrix** — does the same defect affect `com.nexio.tv` (stable), `.earlyaccess`, `.profileable` equally? (Almost certainly yes, since `AuthManager` is shared, but worth confirming the codebase isn't variant-flavored differently.)

---

## 11. Resume-from-here Checklist (next session)

When picking this back up:

1. Read this RCA in full.
2. Get answers to Section 10 from the user.
3. Confirm containment status from Section 7.1 — re-run the diagnostic queries if not done.
4. Re-invoke `superpowers:brainstorming` with this RCA's path as the argument; the brainstorm skill will pick up from "Ask clarifying questions" and validate the Section 9 fix outline against the open questions.
5. After brainstorm approval, transition to `superpowers:writing-plans` for the implementation plan.
6. Implementation plan should produce a `docs/superpowers/plans/YYYY-MM-DD-cross-account-leak-fix.md` document. The plan should be executable via `superpowers:subagent-driven-development` like the catalog-rails series.

---

## 12. Related Work (already shipped this branch)

- The catalog-rails uniformity migration (Plans 1–8 of `docs/superpowers/plans/2026-04-30-catalog-rails-*.md`) introduced `LogcatRuntimeTraceSink` and the per-channel toggles (`Nexio.FirstPaint`, `Nexio.MetaRoute`, `Nexio.IntRuntime`). The Section 9.3 telemetry idea would fit naturally as a fourth toggle (`Nexio.AccountSecurity`) emitting `policy.cross_account_local_state` events.
- The architecture diagram at `docs/architecture/catalog-rails-end-to-end-flow.md` documents the integration runtime + cache fan-out which is also affected by this leak (snapshot stores carry user-bound data).
