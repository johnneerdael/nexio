# Phase 4: Sync and Cleanup - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-04-14
**Phase:** 04-sync-and-cleanup
**Areas discussed:** Sync trigger & conflict strategy, Deletion flow & error handling, Snapshot store classification, Sync status UI feedback

---

## Sync Trigger & Conflict Strategy

| Option | Description | Selected |
|--------|-------------|----------|
| Event-driven with debounce | Sync on profile edit, settings change, and profile switch. Debounce writes (NuvioTV uses 2s flatMapLatest). Pull on app start only. | ✓ |
| Startup + event-driven | Pull on every app startup, push on changes with debounce. No periodic background sync. | |
| Periodic background | WorkManager periodic sync (e.g. every 15 min) plus event-driven pushes. Heavier on battery. | |

**User's choice:** Event-driven with debounce
**Notes:** Matches NuvioTV reference pattern. No periodic background sync needed.

| Option | Description | Selected |
|--------|-------------|----------|
| Last-write-wins (timestamp) | Server stores updated_at per profile. Latest push overwrites. Simple, matches NuvioTV approach. | ✓ |
| Pull-before-push gate | Always pull remote state before pushing local changes. Merge non-conflicting fields. | |
| You decide | Claude picks based on NuvioTV reference. | |

**User's choice:** Last-write-wins (timestamp)
**Notes:** Simple approach. Risk of rare data loss if two devices edit simultaneously accepted.

| Option | Description | Selected |
|--------|-------------|----------|
| Upgrade to v8 contract | New v8 contract that's profile-aware. Per-profile settings get their own blob RPCs keyed by profileId. Clean break. | ✓ |
| Remove 4 keys from v7 | Keep v7 but strip per-profile keys. Minimal change. | |
| Keep v7, add v8 alongside | v7 stays for shared settings. New v8 handles per-profile blobs. Both coexist. | |
| You decide | Claude picks the cleanest approach. | |

**User's choice:** Upgrade to v8 contract
**Notes:** User asked about upgrading the contract rather than just removing keys. Clean break preferred over backwards-compat.

---

## Deletion Flow & Error Handling

| Option | Description | Selected |
|--------|-------------|----------|
| Best-effort, complete locally | Delete local data immediately. Remote cleanup retries on next app start. Profile disappears from UI right away. | ✓ |
| Block until success | Show error, keep profile until remote cleanup succeeds. | |
| Two-phase: soft then hard | Mark profile as 'pending deletion' immediately. Background job completes remote cleanup. | |

**User's choice:** Best-effort, complete locally
**Notes:** NuvioTV uses same approach per research findings.

| Option | Description | Selected |
|--------|-------------|----------|
| Yes, call Trakt revoke API | POST /oauth/revoke to invalidate the token server-side. Best security practice. | |
| No, just clear local tokens | Delete tokens locally for both Trakt and Simkl. Simpler. Trakt tokens expire in 90 days. | ✓ |
| You decide | Claude picks based on security vs simplicity tradeoff. | |

**User's choice:** No, just clear local tokens
**Notes:** Simpler approach. Trakt tokens expire eventually anyway.

| Option | Description | Selected |
|--------|-------------|----------|
| Confirm dialog with profile name | NexioDialog with "Keep Profile" / "Delete Profile" buttons. Matches UI-SPEC. | ✓ |
| Single button press, no dialog | Immediate deletion. Risky for accidental D-pad presses. | |
| You decide | Claude picks based on D-pad safety. | |

**User's choice:** Confirm dialog with profile name
**Notes:** Aligns with UI-SPEC delete dialog design and D-pad safety.

---

## Snapshot Store Classification

| Option | Description | Selected |
|--------|-------------|----------|
| Accept research recommendation | 7 per-profile, 5 shared. Split based on user-specific vs cache data. | ✓ |
| All tracking stores per-profile | Move ALL user-tracking stores to per-profile. Keep only pure caches shared. | |
| Let me review the list | Show each store individually for manual classification. | |

**User's choice:** Accept research recommendation
**Notes:** Research split accepted as-is.

| Option | Description | Selected |
|--------|-------------|----------|
| Yes, _p{id} suffix (consistent) | Same convention as ProfileDataStoreFactory. Bare name for Profile 1. | ✓ |
| Separate prefix pattern | Use profile_{id}_ prefix instead. Different but arguably more readable. | |
| You decide | Claude picks based on consistency. | |

**User's choice:** Yes, _p{id} suffix (consistent)
**Notes:** Consistency with existing ProfileDataStoreFactory naming convention.

---

## Sync Status UI Feedback

| Option | Description | Selected |
|--------|-------------|----------|
| Silent + on-demand only | Background sync completely silent. Status only visible via "Sync Now" in Settings. | ✓ |
| Inline Settings indicator | Subtitle text under Profiles section showing sync status. Matches UI-SPEC draft. | |
| Toasts on failure only | Silent during success. Toast/snackbar only on failure. | |

**User's choice:** Silent + on-demand only
**Notes:** Minimal UI footprint. No persistent indicators.

| Option | Description | Selected |
|--------|-------------|----------|
| Yes, in profile section | "Sync Now" button in Profiles area of Settings. Brief feedback after completion. D-pad focusable. | ✓ |
| No manual sync | Fully automatic. No user-facing sync button. | |
| You decide | Claude picks based on UX. | |

**User's choice:** Yes, in profile section
**Notes:** Provides user control despite event-driven model.

| Option | Description | Selected |
|--------|-------------|----------|
| No indication, fully silent | Startup pull in background. App loads normally. Settings update silently. | ✓ |
| Brief splash hold if slow | Hold splash >2s with "Syncing..." label. Prevents settings flicker. | |
| You decide | Claude picks based on UX tradeoff. | |

**User's choice:** No indication, fully silent
**Notes:** Speed prioritized over consistency. Settings may update after home screen loads.

---

## Claude's Discretion

- Supabase RPC naming and table schema design
- Debounce implementation details (coroutine scope, cancellation)
- Retry mechanism for failed remote cleanup
- v8 contract internal structure and migration path from v7

## Deferred Ideas

None — discussion stayed within phase scope.
