---
phase: 06-tvdb-foundation-and-identity
verified: 2026-04-15T02:32:42Z
status: human_needed
score: 5/5 must-haves verified
overrides_applied: 0
gate_context:
  assembleArm64Debug: passed_after_phase_execution
  schema_drift: drift_detected_false
  code_review: "06-REVIEW.md status issues_found; 0 critical, 3 warnings"
  regression_gate: "attempted; not passed due out-of-scope dirty/profile/player/search/settings test compile errors and Kotlin daemon ZGenerational fallback noise"
  security_gate: "security enforcement enabled; no Phase 06 SECURITY.md exists yet, so no security pass is claimed"
verification_debt:
  - id: WR-01
    severity: warning
    summary: "Transient TVDB auth failures are persisted as INVALID rather than unavailable/fallback state"
  - id: WR-02
    severity: warning
    summary: "Settings observation disables enabled TVDB when status is FALLBACK_ACTIVE"
  - id: WR-03
    severity: warning
    summary: "Settings sync JSON schema is behind formatter badgeRowTemplate payload shape"
human_verification:
  - test: "Android TV settings route and D-pad flow"
    expected: "Settings > Integration > TVDB opens, focus lands predictably, credentials dialog can save/clear, and status text/masking are usable on device"
    why_human: "Compose TV focus behavior and visual/remote interaction need device or emulator confirmation"
  - test: "Live TVDB credential validation"
    expected: "A real valid TVDB API key, with optional PIN when required, validates successfully without exposing key/PIN/token in visible UI or logs"
    why_human: "Requires external TVDB service behavior and real secret material"
  - test: "Runtime fallback diagnostics"
    expected: "When TVDB is unavailable or a series is missing, browsing falls back explicitly and diagnostics show a sanitized reason without browse-time toasts"
    why_human: "The foundation code is present, but full browse-time provider behavior depends on runtime integration and later provider routing"
---

# Phase 6: TVDB Foundation and Identity Verification Report

**Phase Goal:** Users can configure TVDB, Nexio can authenticate and cache TVDB responses, and TVDB-backed TV identity matching works without TMDB lookup dependency
**Verified:** 2026-04-15T02:32:42Z
**Status:** human_needed
**Re-verification:** No - initial verification

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|---|---|---|
| 1 | User can enable TVDB, save an API key, and receive validation feedback without exposing the key in logs or synced public payloads | VERIFIED | `TvdbSettingsViewModel.saveCredentials()` trims API key/PIN and calls `TvdbAuthService.validateCredentials`; UI masks the API key and never stores PIN in `TvdbSettingsUiState`; account sync tests assert public JSON omits `apiKey`, `pin`, and `token`. |
| 2 | TVDB settings sync through account settings with the key handled by the existing secret-channel pattern | VERIFIED | `IntegrationSettings.tvdb` carries only enabled/configured/status/failure; `syncTvdbCredentialSecretToRemote()` writes `tvdb_api_key` with ref `integration:tvdb`; Supabase SQL has 8 `tvdb_api_key` allowlist entries; schema exposes non-secret TVDB fields. |
| 3 | TVDB auth tokens are cached and reused so normal browsing does not log in repeatedly | VERIFIED | `TvdbTokenStore` persists token, expiry, and credential fingerprint; `TvdbAuthService.bearerToken()` reads cache before `tvdbApi.login`, refreshes under a mutex, and clears token on credential changes. |
| 4 | TVDB search and remote IDs can resolve TV series through IMDb, TMDB, TV Maze, Wikidata, official-site, or TVDB IDs without using TMDB only for identification | VERIFIED | `TvdbRemoteIdNormalizer` supports all required sources; `TvdbIdentityService` uses `TvdbApi.searchByRemoteId`, `search`, `getSeriesBase`, and `getSeriesExtended`; `rg` found no `TmdbApi`/`TmdbService` dependency under `core/tvdb`. |
| 5 | If TVDB is inactive, TMDB-backed behavior remains unchanged; if TVDB is active but unusable, fallback is explicit and diagnostically visible | VERIFIED WITH DEBT | `TvdbProviderFallback.decide()` returns fallback for inactive/invalid/unavailable states and `recordFallback()` stores sanitized `FALLBACK_ACTIVE` reasons. Review warnings WR-01 and WR-02 are real debt for transient auth failures and fallback-active state retention, but the foundation diagnostic path exists. |

**Score:** 5/5 merged roadmap and plan-frontmatter truths verified

### Required Artifacts

| Artifact | Expected | Status | Details |
|---|---|---|---|
| `app/src/main/java/com/nexio/tv/domain/model/TvdbSettings.kt` | TVDB settings/status model | VERIFIED | Defines API key, optional subscriber PIN, validation status, failure text, configured and active helpers. |
| `app/src/main/java/com/nexio/tv/data/local/TvdbSettingsDataStore.kt` | Local settings persistence | VERIFIED | Preferences DataStore stores enabled, API key, PIN, validation status, failure, validation timestamp; credential changes clear tokens. |
| `app/src/main/java/com/nexio/tv/data/local/TvdbTokenStore.kt` | Token persistence | VERIFIED | Persists token, expiry, credential fingerprint and clears all fields. |
| `app/src/main/java/com/nexio/tv/data/remote/api/TvdbApi.kt` | TVDB Retrofit contract | VERIFIED | Defines login, search, remote-id search, series base, and extended series endpoints with Authorization headers. |
| `app/src/main/java/com/nexio/tv/core/tvdb/TvdbAuthService.kt` | Cached auth service | VERIFIED WITH DEBT | Implements blank PIN omission, cached bearer reuse, credential fingerprinting, and sanitized logging; WR-01 remains. |
| `app/src/main/java/com/nexio/tv/core/tvdb/TvdbIdentityService.kt` | Remote-ID identity matching | VERIFIED | Reads cache, joins in-flight lookups, gets bearer token, filters remote-id search to series records, and hydrates TVDB series identity. |
| `app/src/main/java/com/nexio/tv/core/tvdb/TvdbProviderFallback.kt` | Fallback decision/diagnostics | VERIFIED | Produces `UseTvdb`/`UseFallback` decisions and records sanitized fallback reasons. |
| `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt` | Account sync wiring | VERIFIED | Builds public TVDB sync fields, observes `integrations.tvdb`, pushes/resolves credential secret, and applies remote public/secret state. |
| `app/src/main/java/com/nexio/tv/ui/screens/settings/TvdbSettingsViewModel.kt` | Settings state and validation | VERIFIED WITH DEBT | Enforces valid credentials before enable, emits validation errors, masks credentials; WR-02 remains. |
| `app/src/main/java/com/nexio/tv/ui/screens/settings/TvdbSettingsScreen.kt` | Compose TV settings surface | VERIFIED | Adds enable toggle, credential dialog, masked value, status row, and provider precedence copy. |

### Key Link Verification

| From | To | Via | Status | Details |
|---|---|---|---|---|
| `TvdbSettingsScreen` | `TvdbSettingsViewModel` | `collectAsStateWithLifecycle`, events, `saveCredentials` | WIRED | Screen reads `uiState`, opens credential dialog, dispatches toggle/save/clear actions. |
| `TvdbSettingsViewModel` | `TvdbAuthService.validateCredentials` | Credentials save | WIRED | Save path calls validation with trimmed API key and PIN before marking valid. |
| `TvdbAuthService` | `TvdbTokenStore` | Cache read/write/clear | WIRED | `bearerToken()` reads cached authorization before login and persists fresh token after login. |
| `TvdbAuthService` | `TvdbApi.login` | TVDB `/login` request | WIRED | Request uses `TvdbLoginRequest(apikey, pin.takeIf { isNotBlank })`. |
| `NetworkModule` | `TvdbApi` | `@Named("tvdb")` Retrofit | WIRED | Provides TVDB base URL and `provideTvdbApi(@Named("tvdb"))`. |
| `TvdbIdentityService` | `TvdbAuthService.bearerToken` | Authenticated lookup | WIRED | Both TVDB-ID and remote-ID paths request bearer token before TVDB API calls. |
| `TvdbIdentityService` | `TvdbIdentityCacheStore` | Read before network, write after match | WIRED | Cache reads happen before API calls; successful identities are written by source/value. |
| `AccountSettingsSyncService` | Supabase secret RPCs | `tvdb_api_key` / `integration:tvdb` | WIRED | Push/delete/resolve paths use existing account secret channel. |

### Data-Flow Trace (Level 4)

| Artifact | Data Variable | Source | Produces Real Data | Status |
|---|---|---|---|---|
| `TvdbSettingsScreen` | `uiState` | `TvdbSettingsDataStore.settings` plus `TvdbAuthService.validateCredentials` | Yes | FLOWING |
| `TvdbAuthService` | bearer token | `TvdbApi.login` response then `TvdbTokenStore.tokenState` | Yes | FLOWING |
| `TvdbIdentityService` | `TvdbSeriesIdentity` | TVDB remote-id/search/series endpoints plus identity cache | Yes | FLOWING |
| `AccountSettingsSyncService` | `integrations.tvdb` and TVDB credential secret | Local TVDB DataStore plus Supabase secret RPCs | Yes | FLOWING |

### Behavioral Spot-Checks

| Behavior | Command / Source | Result | Status |
|---|---|---|---|
| Phase tree assembles | Provided gate context: `./gradlew assembleArm64Debug` | Passed after phase execution on current tree | PASS |
| Schema drift | Provided gate context | `drift_detected=false` | PASS |
| TVDB SQL allowlists include secret type | `rg -n "tvdb_api_key" supabase/account_settings_sync.sql \| wc -l` | 8 | PASS |
| TVDB identity core avoids TMDB API/service dependency | `rg -n "TmdbApi\|TmdbService\|tmdb" app/src/main/java/com/nexio/tv/core/tvdb ...` | Only `TvdbRemoteIdSource.TMDB` normalization matched | PASS |
| Regression unit gate | Provided gate context | Global unit-test compilation failed from out-of-scope dirty/profile/player/search/settings constructor/signature errors and Kotlin daemon fallback noise | NOT PASSED - not attributed to Phase 06 deliverables |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|---|---|---|---|---|
| PREF-01 | 06-01, 06-02, 06-04, 06-05 | User can configure TVDB with API key validation, local settings storage, and account sync support comparable to TMDB | SATISFIED | Settings UI/ViewModel, DataStores, auth validation, public sync fields, and secret-backed sync exist and are wired. |
| PREF-04 | 06-01, 06-03 | When TVDB is inactive, existing TMDB-backed TV behavior continues to work when TMDB is configured | SATISFIED | Phase 6 adds foundation services without replacing TMDB provider paths; fallback decision returns non-TVDB fallback when disabled/not configured. |
| PREF-05 | 06-01, 06-02, 06-03, 06-05 | Active TVDB invalid/unavailable/missing-record fallback is explicit, observable, and avoids silent normal-path double fetch | SATISFIED WITH DEBT | Fallback decisions and sanitized diagnostics exist; WR-01/WR-02 should be closed before relying on transient outage semantics. |
| PREF-06 | 06-01, 06-03 | TVDB remote IDs are used for cross-provider identity matching without TMDB lookup dependency | SATISFIED | Remote-ID normalizer and `TvdbIdentityService` cover TVDB/IMDb/TMDB/TV Maze/Wikidata/official-site/OTHER without TmdbApi/TmdbService. |
| CACHE-01 | 06-01, 06-02, 06-03 | TVDB auth tokens and metadata responses are cached so normal browsing does not repeatedly authenticate or refetch stable TV metadata | SATISFIED FOR PHASE 6 SCOPE | Auth token cache and first-pass identity metadata cache are implemented; broader update-aware metadata cache is explicitly Phase 10 scope. |

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|---|---:|---|---|---|
| `app/src/main/java/com/nexio/tv/core/tvdb/TvdbAuthService.kt` | 212 | Catch-all auth exception becomes `InvalidCredentials` | Warning | Transient outage can be persisted as invalid credentials; review WR-01. |
| `app/src/main/java/com/nexio/tv/ui/screens/settings/TvdbSettingsViewModel.kt` | 36 | Auto-disables any enabled state where `isActive` is false | Warning | `FALLBACK_ACTIVE` can be converted to disabled; review WR-02. |
| `docs/settings/settings-sync.schema.json` | 307 | Schema omission for `badgeRowTemplate` | Warning | Non-TVDB formatter payload emitted by app can be rejected; review WR-03. |

No blocker stub patterns were found in the Phase 6 TVDB artifacts. Null/default/empty values found by static scan are DTO defaults, nullable API fields, or guarded early returns rather than placeholders.

### Human Verification Required

#### 1. Android TV Settings Route and D-pad Flow

**Test:** On an Android TV device/emulator, open Settings > Integration > TVDB, move focus through enable, credentials, status, save, clear, close, and back.
**Expected:** Route opens reliably, focus remains navigable with D-pad, save/clear actions are usable, status copy and masked credential display are visible.
**Why human:** Compose TV focus and visual behavior are not fully verifiable by static code inspection.

#### 2. Live TVDB Credential Validation

**Test:** Enter a valid TVDB API key and optional PIN against the live service; repeat with invalid credentials.
**Expected:** Valid credentials produce `VALID` and allow enablement; invalid credentials produce visible validation feedback; no API key/PIN/token appears in logs or public sync payloads.
**Why human:** Requires real TVDB service behavior and secret material.

#### 3. Runtime Fallback Diagnostics

**Test:** Force TVDB outage/auth unavailability or missing-series lookup while TMDB fallback is configured.
**Expected:** User-facing browsing falls back cleanly, diagnostics/logs show sanitized fallback reason, and no browse-time toast is emitted.
**Why human:** Full browse-time fallback behavior depends on runtime state and later provider routing, although Phase 6 foundation APIs exist.

### Gaps Summary

No blocking implementation gaps were found against the Phase 6 foundation goal. The deliverables exist, are substantive, and are wired through settings, auth, identity, sync, and UI paths.

The phase is not marked `passed` because human verification remains and the regression unit gate did not pass. The regression failure is recorded as out-of-scope for Phase 06 deliverable verification, not as a Phase 06 gap. Code-review warnings WR-01, WR-02, and WR-03 remain verification debt and should be addressed before relying on TVDB fallback behavior in production.

---

_Verified: 2026-04-15T02:32:42Z_
_Verifier: Claude (gsd-verifier)_
