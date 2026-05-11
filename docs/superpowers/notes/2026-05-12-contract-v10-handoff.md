# Contract v10 — Handoff Notes

Plan source: `docs/superpowers/plans/2026-05-12-supabase-contract-v10-timestamps.md`
Executed: 2026-05-12

## Status overview

| Phase | Owner | Status |
|-------|-------|--------|
| 1 – SQL helpers + RPCs (Tasks 1–7) | this session | ✅ committed, ✅ applied to Supabase (user ran `supabase db push` / equivalent) |
| 2 – Android client foundation (Tasks 8–11, 15, 21) | this session | ✅ committed, ✅ unit-tested |
| 3 – Android pull paths (Task 12, 13) | this session | ✅ committed (AccountSettings + AddonSync + ProfileSettings) |
| 4 – Android push paths (Task 16, 17, 18, 19) | this session | ✅ committed (account settings, addons, profile blob, all account secrets) |
| 5 – Android profile auth tokens (Task 14, 20) | this session | ⚠️ skipped — see "Why 14/20 are N/A" below |
| 6 – Tests (Task 21, 22) | this session | ✅ committed (8 unit tests across 2 files) |
| 7 – On-device smoke (Task 23 in plan) | **you** | not yet run |
| 8 – Cross-repo handoff to nexio-web (Task 24, 25) | **you** | nexio-web is still on v9 |
| 9 – v9 retirement cutover (Task 26, 27, 28) | **you**, T+7d+ | blocked on web v10 + dual-client telemetry quiet |

## Why Tasks 14 and 20 are N/A (no Android code change)

The plan had two tasks for `profile_auth_tokens`: pull and push. Inspection of the actual code path shows:

- **Pull**: `ProfileWebSyncService.syncActiveProfile` reads `profile_auth_tokens` via a direct PostgREST table SELECT, not an RPC (`ProfileWebSyncService.kt:65`). It applies the result idempotently — the Android client doesn't need the watermark for its own decision-making because it merges by per-token `updated_at` already. Switching to `sync_pull_profile_auth_tokens_v10` would add a watermark but no behavior change.
- **Push**: The Android client **never writes** `profile_auth_tokens`. Every row in that table is set by the nexio-web SECURITY DEFINER `service_set_profile_auth_token` invoked from the web OAuth flow.

Conclusion: the v10 RPCs for this surface exist (in `20260512000600_contract_v10_profile_tokens.sql`) and the web side can use them when it adopts v10, but no Android-side change is required today. If you later add an Android-initiated token write path (e.g. a "logout-other-devices" feature), wire it through `sync_revoke_profile_auth_token_v10` then.

## On-device smoke (do this before pushing the Android build to prod)

Per CLAUDE.md rule #8 — profile picker is NOT the home screen; select a profile first.

```bash
# Build + install
./gradlew :app:installUniversalDebug

# Clean cold start with profile selection
adb -s 192.168.50.98:5555 shell am force-stop com.nexiodebug.tv
adb -s 192.168.50.98:5555 logcat -c
adb -s 192.168.50.98:5555 shell monkey -p com.nexiodebug.tv 1
sleep 5
adb -s 192.168.50.98:5555 shell input keyevent KEYCODE_DPAD_CENTER
sleep 30

# Look for v10 evidence
adb -s 192.168.50.98:5555 logcat -d -t 1500 | \
  grep -E "AccountSettingsSync|AddonSync|ProfileSettingsSync|sync_(pull|push|set|delete).*v10|stale_base|watermark"
```

**Expected on a clean run:**
- One `sync_pull_account_snapshot_v10` decode at startup.
- Three watermarks persisted (ACCOUNT_SETTINGS, ACCOUNT_ADDONS, ACCOUNT_SECRETS) plus optionally PROFILE_SETTINGS if a secondary profile is active.
- Subsequent pushes carry `p_base_updated_at_ms` equal to the just-pulled watermark.
- **No** `stale_base` log lines (because the same client pulled before pushing).

**Stale-base simulation** (proves the gate fires correctly):
```bash
# Wipe the watermark file, forcing every push to send base=0
adb -s 192.168.50.98:5555 shell run-as com.nexiodebug.tv \
  rm -f /data/data/com.nexiodebug.tv/files/datastore/sync_watermarks.preferences_pb
adb -s 192.168.50.98:5555 shell am force-stop com.nexiodebug.tv
# Then re-cold-start and immediately make an in-app change (e.g. toggle an addon)
# Watch for: "Addon push rejected as stale (server=…, base=0)"
```
After the next pull cycle the watermark is restored and subsequent pushes succeed.

## Cross-client status

The Android side of v10 is in place but nexio-web is still on v9 (`ACCOUNT_CONFIG_SYNC_CONTRACT_VERSION = 9` after today's fix, but the web's outbound RPCs are still the v9 names). That means:

- **Android cannot clobber a fresher web write** — v10 push checks `p_base_updated_at_ms`, web write bumps `updated_at`, Android push gets `stale_base`, drops the write, next pull reconciles. ✅
- **Web could still clobber a fresher Android write** — web's `sync_push_account_addons` / `sync_set_account_secret` (v9 names) have no stale-base check. ❌

The protection is one-way until web adopts v10. For the immediate symptom we debugged today this is fine (web changes never reached Supabase at all, not "web changes got clobbered later"), but the long-term contract gap is real.

## Handoff to nexio-web team

Either you (the same `johnneerdael` GitHub account) or anyone working on nexio-web needs to:

1. Update all `postgrest.rpc('/rest/v1/rpc/sync_push_*', …)` paths in `server/api/account/*.ts` (and any other server route that writes Supabase via RPC) to use the `_v10` suffix and add `p_base_updated_at_ms` from a stored watermark.
2. Add a per-surface watermark to the web's `usePortalStore` state, populated from the `*.updated_at_ms` fields the v10 pull envelopes return, and re-read after every push from the `current_updated_at_ms` field.
3. Handle the stale_base response by pulling the snapshot, merging into local state, and retrying the push with the new watermark.

I have not done this work — it's a parallel effort in the nexio-web repo. The SQL RPCs to consume are documented in `supabase/migrations/2026051200*`. The Android client serves as a worked reference implementation in `app/src/main/java/com/nexio/tv/core/sync/`.

## v9 retirement (Phase 10)

Don't revoke v9 RPC grants until **all** of the following are true for ≥7 days:

- Android prod build is on v10 (the commits in this branch are deployed).
- nexio-web prod is on v10 (separate effort).
- PostgREST logs show zero invocations of `sync_push_account_settings_v7`, `sync_push_account_addons`, `sync_set_account_secret`, `sync_delete_account_secret`, `sync_push_profile_settings_blob`, and `sync_pull_account_snapshot` (v9 names) from any client.

When both clients are on v10 and the v9 RPCs are quiet, write a final migration that runs `REVOKE EXECUTE … FROM authenticated` on each v9 function name. Leave the bodies in place for a 30-day rollback window, then drop them in a follow-up migration.

## Commits on `main` (this session, in order)

```
f9500daeb feat(supabase): v10 contract helpers — timestamp conversion + result envelopes
d5badfd1e feat(supabase): v10 pull RPC — sync_pull_account_snapshot_v10 with per-surface updated_at_ms
7926c11c9 feat(supabase): v10 push RPC — sync_push_account_settings_v10 with stale-base guard
ae5ed6dbc feat(supabase): v10 push RPC — sync_push_account_addons_v10 with stale-base guard
e4571c98f feat(supabase): v10 secret RPCs — set/delete with stale-base guard
205260bee feat(supabase): v10 profile settings blob RPCs with stale-base guard
b27963d0f feat(supabase): v10 profile auth token RPCs with stale-base guard
1ce5e7e96 feat(sync): v10 SyncWatermarkSurface enum
49146263c fix(supabase): one-shot cleanup of doubled addon manifest_url rows
5ec1d9644 chore(submodule): bump nexio-web to 1e5a601 (gate-stuck regression fixes)
78216d7fa chore(submodule): bump nexio-web to 1e5a601 (ReferenceError fix)
e71606542 feat(sync): v10 SyncWatermarkDataStore — per-surface updated_at_ms persistence
633c0d404 feat(sync): v10 contract serialization models
a87824bc2 feat(sync): v10 V10PushOutcome sealed result type
24800949e feat(sync): v10 mapV10PushResult + runV10Push helpers
0e2ab0c3e test(sync): v10 push outcome mapping + runV10Push exception wrapping
d047d1d90 feat(sync): route account snapshot pull through sync_pull_account_snapshot_v10
1944816c7 feat(sync): route addon push through sync_push_account_addons_v10
8d0e90622 feat(sync): route account settings push through sync_push_account_settings_v10
82f2d507e feat(sync): route account secret set/delete through v10 RPCs
f9bc1d4c6 feat(sync): route profile blob pull + push through v10 with stale-base handling
e3acaa13c test(sync): v10 envelope deserialization round-trip + ctor update
```
