---
phase: 02
slug: per-profile-auth-and-settings
status: verified
threats_open: 0
asvs_level: 1
created: 2026-04-14
---

# Phase 02 — Security

> Per-phase security contract: threat register, accepted risks, and audit trail.

---

## Trust Boundaries

| Boundary | Description | Data Crossing |
|----------|-------------|---------------|
| Profile A data → Profile B read | Profile 2 must not read Profile 1's Trakt or Simkl access/refresh tokens | OAuth tokens, account IDs |
| Write path → active profile | All DataStore writes must target the currently active profile, not a stale captured reference | Preference writes |
| Profile switch → sync push | A profile switch must not trigger a sync push that overwrites the previous profile's remote data | Remote account config |
| Non-default profile → shared settings | Non-default profiles must not navigate to shared settings (debrid keys, API keys) | API credentials UI |
| Init block → active profile | DataStore init migrations run once on profile 1's DataStore at app startup before any UI interaction | Preference migrations |

---

## Threat Register

| Threat ID | Category | Component | Disposition | Mitigation | Status |
|-----------|----------|-----------|-------------|------------|--------|
| T-02-01 | Information Disclosure | TraktAuthDataStore | mitigate | `flatMapLatest(activeProfileId)` — state Flow only emits active profile's tokens; verified by TraktAuthDataStoreProfileTest | closed |
| T-02-02 | Tampering | TraktAuthDataStore.saveToken (all write methods) | mitigate | `store()` calls `profileManager.activeProfileId.value` at invocation time, not a captured reference; prevents writing Profile 1 token into Profile 2 file | closed |
| T-02-03 | Information Disclosure | TraktSettingsDataStore | mitigate | Each of 4 flow properties individually wrapped in `flatMapLatest(activeProfileId)`; verified by TraktSettingsDataStoreProfileTest | closed |
| T-02-04 | Information Disclosure | SimklAuthDataStore | mitigate | `flatMapLatest(activeProfileId)` — state Flow only emits active profile's Simkl tokens; verified by SimklAuthDataStoreProfileTest | closed |
| T-02-05 | Tampering | SimklAuthDataStore.saveAccessToken (all write methods) | mitigate | `store()` resolves `profileManager.activeProfileId.value` at call time; prevents cross-profile Simkl token writes | closed |
| T-02-06 | Information Disclosure | SimklSettingsDataStore | mitigate | `catalogPreferences` flow wrapped in `flatMapLatest(activeProfileId)`; verified by SimklSettingsDataStoreProfileTest | closed |
| T-02-07 | Information Disclosure | PlayerSettingsDataStore | mitigate | `profileFlow` helper wraps all 40+ flow properties via single `flatMapLatest(activeProfileId)` choke point; `store()` resolves at call time | closed |
| T-02-08 | Information Disclosure | LayoutPreferenceDataStore | mitigate | `profileFlow` helper wraps all 20+ flow properties via single `flatMapLatest(activeProfileId)` choke point | closed |
| T-02-09 | Tampering | LayoutPreferenceDataStore.init / PlayerSettingsDataStore.init | accept | see Accepted Risks Log — AR-02-01 | closed |
| T-02-10 | Information Disclosure | SearchHistoryDataStore | mitigate | `recentSearches` flow wrapped in `flatMapLatest(activeProfileId)`; per-profile isolation per D-03 | closed |
| T-02-11 | Tampering | AccountSettingsSyncService | mitigate | Generation-counter suppression (`suppressPushForSwitchGeneration`) set on `activeProfileId.drop(1)` observation; guard placed in both `collect` block and `schedulePush()`; superior to plan's 2-second boolean: eliminates TOCTOU gap and survives storage pressure delays | closed |
| T-02-12 | Tampering | Settings UI — Integration Hub (SettingsScreen.kt) | mitigate | `isPrimaryProfile` parameter gates shared integration entries (Debrid, TheIntroDb, Tmdb, Omdb, Imdb, MdbList, AnimeSkip, SubtitleTranslation, YouTubeTrailerLogin, PosterRatings); `LaunchedEffect(isPrimaryProfile, selectedSection)` safety redirect to Hub for non-primary profiles on shared sections | closed |
| T-02-13 | Elevation of Privilege | ProfileManager.setActiveProfile | accept | see Accepted Risks Log — AR-02-02 | closed |

*Status: open · closed*
*Disposition: mitigate (implementation required) · accept (documented risk) · transfer (third-party)*

---

## Accepted Risks Log

| Risk ID | Threat Ref | Rationale | Accepted By | Date |
|---------|------------|-----------|-------------|------|
| AR-02-01 | T-02-09 | `LayoutPreferenceDataStore.init` and `PlayerSettingsDataStore.init` call `store().edit` which resolves to profile 1 at Hilt singleton injection time. Risk: a profile switch occurring before init completes could migrate the wrong profile's DataStore. Accepted because: (1) Singleton init runs at Hilt injection time before any UI interaction is possible; (2) profile switching requires an explicit UI action; (3) the window is milliseconds at cold start. PIN gating (Phase 3 UI-04) further narrows the window. | gsd-security-auditor | 2026-04-14 |
| AR-02-02 | T-02-13 | `ProfileManager.setActiveProfile` does not validate caller identity — any in-process code path can switch profiles. Accepted because: (1) profile switching is an in-app action, not a network boundary; (2) no external actor can invoke this directly; (3) PIN/lock gating for profile switching is deferred to Phase 3 (UI-04), at which point the caller surface is gated by user authentication. | gsd-security-auditor | 2026-04-14 |

---

## Unregistered Threat Flags

No threat flags were raised in any SUMMARY.md that lack a mapping to a registered threat ID. All executor-noted deviations were environment issues (missing AAR, worktree build env) unrelated to security.

**Implementation deviation (T-02-11):** The executor upgraded the plan's fixed 2-second `@Volatile Boolean` flag to a generation-counter approach (`suppressPushForSwitchGeneration: Long` + `clearSuppression(gen)`). This is a strictly stronger control — it eliminates the TOCTOU gap between flag check and push-job launch, and it handles storage-pressure delays that could cause DataStore emissions to arrive after a fixed 2-second window expires. The threat remains CLOSED with higher confidence.

---

## Security Audit Trail

| Audit Date | Threats Total | Closed | Open | Run By |
|------------|---------------|--------|------|--------|
| 2026-04-14 | 13 | 13 | 0 | gsd-security-auditor (claude-sonnet-4-6) |

---

## Sign-Off

- [x] All threats have a disposition (mitigate / accept / transfer)
- [x] Accepted risks documented in Accepted Risks Log (AR-02-01, AR-02-02)
- [x] `threats_open: 0` confirmed
- [x] `status: verified` set in frontmatter

**Approval:** verified 2026-04-14
