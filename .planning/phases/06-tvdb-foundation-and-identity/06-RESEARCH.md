# Phase 6: TVDB Foundation and Identity - Research

**Researched:** 2026-04-14 [VERIFIED: system date]
**Domain:** Android TV Kotlin settings, Retrofit/Moshi TVDB API integration, Supabase account-settings secret sync, TV identity cache [VERIFIED: CLAUDE.md; VERIFIED: 06-CONTEXT.md; VERIFIED: codebase grep]
**Confidence:** HIGH for local architecture and checked-in API contract; MEDIUM for source-name normalization because `tvdb.yml` exposes remote-id source names as strings, not an enum. [VERIFIED: tvdb.yml; VERIFIED: codebase grep]

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions
## Implementation Decisions

### Credential Model
- **D-01:** TVDB supports API key plus optional subscriber PIN in Phase 6. The optional PIN must be accepted by the settings flow and sent to TVDB `/login` only when present.
- **D-02:** TVDB credentials must be secret-backed. The public account sync payload may carry enabled/configured state, but API key and PIN must not be stored in public sync JSON or logs.
- **D-03:** Planning should extend the existing account secret allowlist with a TVDB secret type, likely `tvdb_api_key`, and choose whether the optional PIN is stored in the same secret payload or a separate secret ref. Keep secret material out of planning docs and commits.

### Settings Shape
- **D-04:** Phase 6 should create a simple foundation settings screen: enable TVDB, API key, optional PIN, validation state, and provider-precedence copy.
- **D-05:** Do not mirror TMDB per-feature toggles in Phase 6. Surface-level controls for artwork, trailers, cast, related content, or networks belong in Phase 7+ only if provider replacement work proves they are needed.
- **D-06:** Settings copy should state that TVDB becomes the TV metadata source when configured, TMDB remains movie metadata and TV fallback when TVDB is not configured, and poster-ratings integrations remain authoritative for supported poster imagery.

### Fallback Diagnostics
- **D-07:** Phase 6 fallback observability is settings status plus logs. The TVDB settings state should distinguish not configured, validating, valid, invalid, and last failure where practical.
- **D-08:** When TVDB is active but unusable, fallback to non-TVDB behavior must log the reason and must be observable from TVDB settings. Avoid browse-time toasts or snackbars.
- **D-09:** Full provider-choice diagnostics for every TV metadata path can wait until Phase 10, but Phase 6 should create enough state/logging for planner and tests to prove fallback is not silent.

### Identity Matching Scope
- **D-10:** Phase 6 should support broad TVDB identity matching: TVDB ID, IMDb ID, TMDB remote ID, TV Maze ID, Wikidata ID, and official-site IDs when TVDB exposes them.
- **D-11:** TVDB remote IDs should be normalized and cached so a TVDB-backed TV record does not require a TMDB API call merely to identify the series.
- **D-12:** The first implementation may consume only the IDs needed by current call sites, but the identity model should preserve the broader remote-ID set for downstream provider replacement.

### Token and Foundation Cache Policy
- **D-13:** Persist TVDB bearer token with expiry metadata. Reuse it across app restarts and refresh before or after expiry instead of calling `/login` repeatedly.
- **D-14:** Foundation caching in Phase 6 should cover auth token state and identity lookup results. Deeper metadata cache invalidation using TVDB `/updates` or record timestamps is Phase 10.
- **D-15:** Token and lookup caches should follow existing in-flight de-duping patterns where possible so parallel TVDB lookups join the same request instead of creating duplicate network calls.

### the agent's Discretion
- Exact local class names and package layout for TVDB settings, API client, token store, and identity service.
- Whether optional PIN is persisted in the same secret payload as the API key or a separate secret ref, as long as it remains secret-backed and sync-compatible.
- Exact wording for settings validation and precedence copy, as long as it communicates the decided provider hierarchy.
- Exact log tag names and diagnostic state representation.

### Claude's Discretion
See "the agent's Discretion" above, copied verbatim from CONTEXT.md. [VERIFIED: 06-CONTEXT.md]

### Deferred Ideas (OUT OF SCOPE)
- App-level TVDB key path is deferred. Phase 6 should not assume a negotiated app-level credential.
- TMDB-style per-TVDB metadata toggles are deferred unless Phase 7 provider replacement shows they are necessary.
- Full provider-choice diagnostics across every metadata path are deferred to Phase 10.
- TVDB `/updates`-driven metadata cache invalidation is deferred to Phase 10.
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| PREF-01 | User can configure TVDB with API key validation, local settings storage, and account sync support comparable to TMDB | Use `TmdbSettingsDataStore`, `TmdbSettingsViewModel`, `TmdbSettingsScreen`, `SettingsScreen`, and account secret sync as direct local patterns. [VERIFIED: REQUIREMENTS.md; VERIFIED: codebase grep] |
| PREF-04 | When TVDB is inactive, existing TMDB-backed TV behavior continues to work when TMDB is configured | Phase 6 should add TVDB foundation services without replacing existing TMDB TV call sites yet, because provider replacement starts in Phase 7. [VERIFIED: 06-CONTEXT.md; VERIFIED: ROADMAP.md] |
| PREF-05 | When active TVDB is invalid, unavailable, or lacks a required TV record, fallback is explicit, observable in diagnostics/logs, and does not silently double-fetch during normal success paths | Store validation status/last failure in TVDB settings state and log fallback reasons from TVDB foundation services. [VERIFIED: 06-CONTEXT.md; VERIFIED: LogSanitizer.kt] |
| PREF-06 | TVDB remote IDs are used for cross-provider identity matching so TVDB-backed TV records do not require TMDB lookups just to identify TV content | Use `/search/remoteid/{remoteId}`, `/search?remote_id=...`, `/series/{id}`, and `/series/{id}/extended` plus `remoteIds`. [VERIFIED: tvdb.yml] |
| CACHE-01 | TVDB authentication tokens and metadata responses are cached so normal browsing does not repeatedly authenticate or refetch stable TV metadata | Persist bearer token with expiry and cache identity lookup results using existing in-flight de-duping and disk-cache patterns. [VERIFIED: tvdb.yml; VERIFIED: TmdbService.kt; VERIFIED: MetadataDiskCacheStore.kt] |
</phase_requirements>

## Project Constraints (from CLAUDE.md)

- Nexio is an Android TV / Fire TV app built with Kotlin and Jetpack Compose. [VERIFIED: CLAUDE.md]
- Preserve existing architecture and naming patterns. [VERIFIED: CLAUDE.md]
- Keep domain code free of Android framework dependencies. [VERIFIED: CLAUDE.md]
- Prefer small, targeted changes and root-cause fixes over broad refactors or workarounds. [VERIFIED: CLAUDE.md]
- Do not introduce new libraries or patterns unless clearly justified by the existing codebase. [VERIFIED: CLAUDE.md]
- Use `./gradlew assembleArm64Debug`, `./gradlew testArm64DebugUnitTest`, and `./gradlew lintArm64Debug` for local build/test/lint verification. [VERIFIED: CLAUDE.md]

## Summary

Implement Phase 6 as a foundation layer, not a provider replacement layer: add TVDB settings, TVDB API/DTOs, token storage, identity lookup, cache, account sync, and diagnostic state, while leaving existing TMDB TV metadata call sites unchanged until Phase 7. [VERIFIED: 06-CONTEXT.md; VERIFIED: ROADMAP.md]

The strongest local pattern is "TMDB settings plus account secret sync": public sync stores enabled/configured/settings state, while credentials move through `sync_set_account_secret`, `sync_delete_account_secret`, and `sync_resolve_account_secret`. [VERIFIED: AccountSettingsSyncService.kt; VERIFIED: AccountSyncModels.kt; VERIFIED: supabase/account_settings_sync.sql]

TVDB API V4 in `tvdb.yml` supports `/login` with required `apikey` and optional `pin`, returns a bearer token, marks that token valid for one month, requires bearer auth for subsequent API calls, and exposes `https://api4.thetvdb.com/v4` as the server URL. [VERIFIED: tvdb.yml]

**Primary recommendation:** Build `TvdbSettingsDataStore` + `TvdbApi` + `TvdbTokenStore` + `TvdbIdentityService`; store API key/PIN in one TVDB account-secret payload for atomic sync, persist only non-secret status in public sync, and cache token/identity lookups with in-flight de-duping. [VERIFIED: 06-CONTEXT.md; VERIFIED: AccountSettingsSyncService.kt; VERIFIED: TmdbService.kt]

## Standard Stack

### Core

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Kotlin / Android Gradle Plugin | Kotlin plugin `2.3.0`, AGP `8.13.2` | Android app implementation | Already configured for the app. [VERIFIED: gradle/libs.versions.toml] |
| Jetpack Compose for TV | TV Material `1.0.1`, Compose BOM `2026.01.01` | TV settings UI | Existing settings screens use Compose for TV components and focus requesters. [VERIFIED: gradle/libs.versions.toml; VERIFIED: SettingsScreen.kt] |
| Hilt | `2.58` | Dependency injection | `NetworkModule` and settings ViewModels already use Hilt providers/injection. [VERIFIED: gradle/libs.versions.toml; VERIFIED: NetworkModule.kt; VERIFIED: TmdbSettingsViewModel.kt] |
| Retrofit | `2.9.0` | TVDB HTTP API interface | Existing remote APIs use Retrofit interfaces and named Retrofit providers. [VERIFIED: gradle/libs.versions.toml; VERIFIED: NetworkModule.kt; VERIFIED: TmdbApi.kt] |
| Moshi | `1.15.1` | TVDB JSON DTO parsing | `NetworkModule` provides a singleton Moshi with `KotlinJsonAdapterFactory`. [VERIFIED: gradle/libs.versions.toml; VERIFIED: NetworkModule.kt] |
| OkHttp | `4.12.0` | Shared HTTP transport/cache | Existing provider clients derive from a shared singleton OkHttp client. [VERIFIED: gradle/libs.versions.toml; VERIFIED: NetworkModule.kt] |
| DataStore Preferences | `1.1.1` | Local settings/token state | Existing integration settings use Preferences DataStore. [VERIFIED: gradle/libs.versions.toml; VERIFIED: TmdbSettingsDataStore.kt] |
| Kotlinx Serialization | `1.8.0` | Account sync payloads/secrets | Existing Supabase payloads use `@Serializable` models. [VERIFIED: gradle/libs.versions.toml; VERIFIED: AccountSyncModels.kt] |
| Supabase Kotlin | `3.1.4` | Account sync RPCs | Existing sync service uses Supabase Postgrest RPC calls. [VERIFIED: gradle/libs.versions.toml; VERIFIED: AccountSettingsSyncService.kt] |

### Supporting

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| Gson | `2.10.1` | Disk metadata cache serialization | Use only if extending `MetadataDiskCacheStore`; do not introduce a second disk-cache serializer there. [VERIFIED: gradle/libs.versions.toml; VERIFIED: MetadataDiskCacheStore.kt] |
| JUnit4 | `4.13.2` | Unit tests | Existing JVM tests use JUnit4. [VERIFIED: app/build.gradle.kts; VERIFIED: app/src/test] |
| kotlinx-coroutines-test | `1.8.1` | Coroutine tests | Existing ViewModel/service tests use `runTest`, `StandardTestDispatcher`, and `advanceUntilIdle`. [VERIFIED: app/build.gradle.kts; VERIFIED: TheIntroDbSettingsViewModelTest.kt] |
| MockK | `1.13.12` | Mocked API/service tests | Existing TMDB and sync tests use MockK call assertions. [VERIFIED: app/build.gradle.kts; VERIFIED: TmdbMetadataPerformanceTest.kt] |
| MockWebServer | `4.12.0` | HTTP behavior tests | Available for TVDB auth/header tests where Retrofit behavior matters. [VERIFIED: app/build.gradle.kts] |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Retrofit + Moshi | Ktor client DTO layer | Do not switch; the app already standardizes on Retrofit for provider APIs. [VERIFIED: NetworkModule.kt; VERIFIED: TmdbApi.kt] |
| Preferences DataStore token store | Android Keystore / EncryptedSharedPreferences | Local encrypted-at-rest credential storage is not an existing settings pattern in this codebase; adding it would be a separate security decision. [VERIFIED: codebase grep; ASSUMED] |
| Extend `MetadataDiskCacheStore` | New TVDB-only SharedPreferences cache | Prefer extending the existing shared metadata cache unless TVDB identity serialization becomes too different, because Phase 4 keeps `MetadataDiskCacheStore` shared. [VERIFIED: 04-CONTEXT.md; VERIFIED: MetadataDiskCacheStore.kt] |

**Installation:** No new dependency should be planned for Phase 6. [VERIFIED: CLAUDE.md; VERIFIED: gradle/libs.versions.toml]

**Version verification:** This is a Gradle Android project, not npm; versions above were verified from `gradle/libs.versions.toml` and `app/build.gradle.kts`, and the local wrapper reports Gradle `8.13`. [VERIFIED: ./gradlew --version; VERIFIED: gradle/libs.versions.toml; VERIFIED: app/build.gradle.kts]

## Architecture Patterns

### Recommended Project Structure

```text
app/src/main/java/com/nexio/tv/
├── data/local/              # TvdbSettingsDataStore and TvdbTokenStore using Preferences DataStore. [VERIFIED: TmdbSettingsDataStore.kt]
├── data/remote/api/         # TvdbApi Retrofit interface and Moshi DTOs based on tvdb.yml. [VERIFIED: TmdbApi.kt; VERIFIED: tvdb.yml]
├── core/tvdb/               # TvdbAuthService, TvdbIdentityService, source normalization, diagnostics. [VERIFIED: TmdbService.kt]
├── core/sync/               # Extend account config observers, payload builders, secret push/pull. [VERIFIED: AccountSettingsSyncService.kt]
├── data/remote/supabase/    # Add TvdbSyncSettings and Tvdb credential secret payload models. [VERIFIED: AccountSyncModels.kt]
└── ui/screens/settings/     # TvdbSettingsViewModel and TvdbSettingsScreen. [VERIFIED: TmdbSettingsViewModel.kt; VERIFIED: TmdbSettingsScreen.kt]
```

### Pattern 1: Settings And Validation

**What:** Mirror the TMDB settings flow structurally but remove TMDB's per-feature toggle matrix; TVDB Phase 6 needs enabled, API key, optional PIN, validation state, and provider-precedence copy. [VERIFIED: 06-CONTEXT.md; VERIFIED: TmdbSettingsScreen.kt]

**When to use:** Use this for PREF-01 settings and UX copy. [VERIFIED: REQUIREMENTS.md]

**Example:**

```kotlin
// Source pattern: TmdbSettingsViewModel.kt + tvdb.yml.
fun validateAndSaveCredentials(apiKey: String, pin: String?, onSuccess: () -> Unit) {
    // Call TvdbAuthService.login(apiKey, pin.takeIf { it.isNotBlank() }).
    // On success: save local credentials, cache token, status=Valid.
    // On 401 or exception: status=Invalid/Failure without logging secrets.
}
```

### Pattern 2: TVDB API Interface

**What:** Add a Retrofit `TvdbApi` using `@POST("login")`, `@GET("search")`, `@GET("search/remoteid/{remoteId}")`, `@GET("series/{id}")`, and `@GET("series/{id}/extended")`. [VERIFIED: tvdb.yml; VERIFIED: TmdbApi.kt]

**When to use:** Use this for credential validation, bearer-authenticated lookup, and Phase 7-ready series identity. [VERIFIED: tvdb.yml; VERIFIED: 06-CONTEXT.md]

**Example:**

```kotlin
// Source pattern: TmdbApi.kt; endpoint contract: tvdb.yml.
interface TvdbApi {
    @POST("login")
    suspend fun login(@Body body: TvdbLoginRequest): Response<TvdbLoginResponse>

    @GET("search/remoteid/{remoteId}")
    suspend fun searchByRemoteId(
        @Header("Authorization") authorization: String,
        @Path("remoteId") remoteId: String
    ): Response<TvdbRemoteIdSearchResponse>
}
```

### Pattern 3: Token Cache With Refresh Boundary

**What:** Store token, `expiresAtEpochMs`, and credential fingerprint metadata in a local store; refresh when missing, expired, or near expiry; clear token when credentials change. [VERIFIED: tvdb.yml; VERIFIED: TmdbService.kt; ASSUMED]

**When to use:** Use this for CACHE-01 and validation feedback. [VERIFIED: REQUIREMENTS.md]

**Example:**

```kotlin
// Source pattern: TmdbService.kt in-flight de-duping; token lifetime source: tvdb.yml.
private val tokenRefreshMutex = Mutex()

suspend fun bearerToken(): String? = tokenRefreshMutex.withLock {
    val cached = tokenStore.state.first()
    if (cached.isUsable(nowMs = clock.nowMs(), refreshSkewMs = TOKEN_REFRESH_SKEW_MS)) {
        return cached.token
    }
    loginAndPersistToken()
}
```

### Pattern 4: Identity Lookup

**What:** Normalize input IDs into `TvdbRemoteIdSource` keys, use TVDB search endpoints before any TMDB lookup, filter search results to series records, then hydrate/preserve `SeriesExtendedRecord.remoteIds`. [VERIFIED: tvdb.yml; VERIFIED: 06-CONTEXT.md]

**When to use:** Use this for PREF-06 and Phase 7 provider routing. [VERIFIED: REQUIREMENTS.md; VERIFIED: 07-CONTEXT.md]

**Example:**

```kotlin
// Source pattern: TmdbService.kt cache/in-flight shape; source fields: tvdb.yml RemoteID.
data class TvdbSeriesIdentity(
    val tvdbId: Int,
    val name: String?,
    val year: String?,
    val remoteIds: Map<TvdbRemoteIdSource, Set<String>>
)
```

### Anti-Patterns To Avoid

- **Logging request bodies or bearer tokens:** TVDB `/login` carries credentials and returns a token, so log only endpoint, status, and sanitized reason. [VERIFIED: tvdb.yml; VERIFIED: LogSanitizer.kt]
- **Calling TMDB just to identify a TV series when TVDB is active:** PREF-06 exists specifically to remove that dependency. [VERIFIED: REQUIREMENTS.md]
- **Treating `RemoteID.sourceName` as a closed enum:** `tvdb.yml` models `sourceName` as a string, so normalization must tolerate new names. [VERIFIED: tvdb.yml]
- **Using TMDB cache keys for TVDB data:** Phase 7 context requires TVDB cache entries to remain separate from TMDB cache entries. [VERIFIED: 07-CONTEXT.md]

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| HTTP client and JSON parsing | Custom URLConnection/manual JSON | Retrofit + Moshi | Existing provider integrations already use Retrofit/Moshi. [VERIFIED: NetworkModule.kt; VERIFIED: TmdbApi.kt] |
| Account secret storage RPCs | New Supabase secret tables/RPCs | Existing `account_secrets` + sync secret RPCs | Current SQL and Kotlin sync code already implement secret set/delete/resolve. [VERIFIED: supabase/account_settings_sync.sql; VERIFIED: AccountSettingsSyncService.kt] |
| In-flight duplicate suppression | Per-call booleans or ad hoc locks | `ConcurrentHashMap<String, CompletableDeferred<T?>>` pattern | `TmdbService` already joins duplicate concurrent lookups this way and has a test for it. [VERIFIED: TmdbService.kt; VERIFIED: TmdbMetadataPerformanceTest.kt] |
| Sensitive log redaction | One-off string replacements in TVDB code | Existing `sanitizeRequestTargetForLogs` plus no body logging | The sanitizer already redacts sensitive path segments and ignores query output. [VERIFIED: LogSanitizer.kt; VERIFIED: NetworkModule.kt] |
| Disk metadata cache mechanics | Separate unbatched SharedPreferences writes | Existing `MetadataDiskCacheStore` write batching/schema-version pattern | Existing store batches writes, namespaces keys, and schema-checks cached entries. [VERIFIED: MetadataDiskCacheStore.kt] |

**Key insight:** The hard part is not the TVDB HTTP calls; it is preserving the existing sync/security/cache boundaries while introducing a second TV identity authority. [VERIFIED: 06-CONTEXT.md; VERIFIED: AccountSettingsSyncService.kt; VERIFIED: TmdbService.kt]

## Common Pitfalls

### Pitfall 1: Public Sync Payload Leaks Credentials

**What goes wrong:** API key or PIN gets added to `TvdbSyncSettings` or schema JSON instead of the account secret channel. [VERIFIED: 06-CONTEXT.md; VERIFIED: AccountSyncModels.kt]

**Why it happens:** TMDB's local model contains `apiKey`, but its public sync model intentionally omits it. [VERIFIED: TmdbSettings.kt; VERIFIED: AccountSyncModels.kt]

**How to avoid:** Add public `TvdbSyncSettings(enabled, configured, validationStatus/lastFailure metadata if needed)` and add a separate TVDB secret payload. [VERIFIED: AccountSyncModels.kt; VERIFIED: AccountSettingsSyncService.kt]

**Warning signs:** `apiKey`, `pin`, `token`, or raw authorization values appear in `docs/settings/settings-sync.schema.json` or public Supabase payload defaults. [VERIFIED: docs/settings/settings-sync.schema.json; VERIFIED: supabase/account_settings_sync.sql]

### Pitfall 2: Secret Type Allowlist Missed In One SQL Definition

**What goes wrong:** Android adds `tvdb_api_key`, but Supabase rejects set/delete/resolve because one allowlist copy was missed. [VERIFIED: supabase/account_settings_sync.sql]

**Why it happens:** `supabase/account_settings_sync.sql` contains secret-type checks in the table constraint and in multiple RPC definitions. [VERIFIED: rg secret_type supabase/account_settings_sync.sql]

**How to avoid:** Update every `secret_type in (...)` allowlist occurrence and add a SQL grep verification step. [VERIFIED: supabase/account_settings_sync.sql]

**Warning signs:** TVDB validation succeeds locally but account sync logs `Unsupported secret type`. [VERIFIED: supabase/account_settings_sync.sql; VERIFIED: AccountSettingsSyncService.kt]

### Pitfall 3: Optional PIN Sent As Blank

**What goes wrong:** Login sends `"pin": ""` for non-subscriber keys and TVDB treats it differently from omitted PIN. [VERIFIED: tvdb.yml]

**Why it happens:** The TVDB contract says to completely remove `pin` unless a user-supported key has a subscriber PIN. [VERIFIED: tvdb.yml]

**How to avoid:** Model the login request with nullable/omitted PIN and omit empty values. [VERIFIED: tvdb.yml]

**Warning signs:** API-key-only users cannot validate even though the key is valid. [VERIFIED: tvdb.yml; ASSUMED]

### Pitfall 4: Repeated Login On Browse Paths

**What goes wrong:** Every identity lookup calls `/login`, causing latency and avoidable auth traffic. [VERIFIED: tvdb.yml; VERIFIED: REQUIREMENTS.md]

**Why it happens:** Token retrieval is implemented inside each API method instead of centralized behind a cached token service. [VERIFIED: TmdbService.kt; ASSUMED]

**How to avoid:** Centralize bearer retrieval in `TvdbAuthService`, persist expiry metadata, and join concurrent refreshes through a mutex/deferred. [VERIFIED: tvdb.yml; VERIFIED: TmdbService.kt]

**Warning signs:** Mocked tests show multiple `/login` calls for parallel identity lookups. [VERIFIED: TmdbMetadataPerformanceTest.kt]

### Pitfall 5: Remote-ID Result Type Ambiguity

**What goes wrong:** `/search/remoteid/{remoteId}` can return series, movie, people, episode, company, or season results, so accepting the first result can bind a TV path to the wrong entity kind. [VERIFIED: tvdb.yml]

**Why it happens:** The endpoint description is intentionally broad. [VERIFIED: tvdb.yml]

**How to avoid:** Filter to series records and hydrate via `/series/{id}` or `/series/{id}/extended` before returning `TvdbSeriesIdentity`. [VERIFIED: tvdb.yml; VERIFIED: 06-CONTEXT.md]

**Warning signs:** Tests pass for IMDb IDs but fail for official-site or Wikidata IDs with mixed result types. [VERIFIED: tvdb.yml; ASSUMED]

### Pitfall 6: Silent Fallback

**What goes wrong:** TVDB active-but-unusable paths quietly run old TMDB behavior with no settings status or log reason. [VERIFIED: 06-CONTEXT.md; VERIFIED: REQUIREMENTS.md]

**Why it happens:** Phase 6 does not replace all provider paths yet, so fallback can look like unchanged TMDB behavior. [VERIFIED: 06-CONTEXT.md; VERIFIED: ROADMAP.md]

**How to avoid:** Add a small diagnostic model with states `NotConfigured`, `Validating`, `Valid`, `Invalid`, and `LastFailure(reason, at)`, and log fallback reasons without browse-time toasts. [VERIFIED: 06-CONTEXT.md]

**Warning signs:** Tests can disable TVDB but cannot distinguish inactive from invalid credentials. [VERIFIED: REQUIREMENTS.md]

## Code Examples

### TVDB Secret Payload

```kotlin
// Source: AccountSecretApiKeyPayload pattern in AccountSyncModels.kt.
@Serializable
data class AccountTvdbCredentialSecretPayload(
    val apiKey: String = "",
    val pin: String? = null
)
```

### Account Sync Additions

```kotlin
// Source: AccountSettingsSyncService.kt secret constants and syncApiKeySecretToRemote pattern.
private const val TVDB_SECRET_TYPE = "tvdb_api_key"
private const val TVDB_SECRET_REF = "integration:tvdb"
```

### Remote-ID Normalization

```kotlin
// Source: tvdb.yml RemoteID.sourceName is string; 06-CONTEXT.md requires broad ID support.
enum class TvdbRemoteIdSource {
    TVDB, IMDB, TMDB, TV_MAZE, WIKIDATA, OFFICIAL_SITE, OTHER
}
```

### Cache Key Shape

```kotlin
// Source: MetadataDiskCacheStore.kt existing prefix/schema pattern; 07-CONTEXT.md separate TVDB cache namespace.
private const val TVDB_IDENTITY_PREFIX = "tvdb_identity::"
private const val TVDB_IDENTITY_SCHEMA_VERSION = 1
```

## State Of The Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| TMDB `find/{external_id}` for TV identity | TVDB remote-ID lookup and preserved `remoteIds` for TV identity | Phase 6 milestone decision, 2026-04-14 | TVDB-backed TV records no longer need TMDB just to identify a series. [VERIFIED: REQUIREMENTS.md; VERIFIED: 06-CONTEXT.md] |
| `/search?q=...` alias | `/search?query=...` | `tvdb.yml` says `q` will eventually be deprecated | Use `query` in new Retrofit methods. [VERIFIED: tvdb.yml] |
| Re-auth on demand | Persist bearer token for one-month validity | TVDB API V4 contract in `tvdb.yml` | Browsing should reuse token and refresh near expiry. [VERIFIED: tvdb.yml; VERIFIED: REQUIREMENTS.md] |
| TVDB `/updates` cache invalidation | Defer `/updates` invalidation to Phase 10 | Phase 6 context decision | Phase 6 should cache token and identity only, not build full update-aware metadata invalidation. [VERIFIED: 06-CONTEXT.md] |

**Deprecated/outdated:**
- Do not use `q` for new TVDB search calls because `tvdb.yml` recommends `query` and says `q` will eventually be deprecated. [VERIFIED: tvdb.yml]
- Do not build Phase 6 around TVDB `/updates`; that work is explicitly deferred. [VERIFIED: 06-CONTEXT.md]

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | Local TVDB API key/PIN can follow the existing local DataStore credential pattern while account sync uses the secret channel; the codebase does not currently show an encrypted local integration-settings store. | Standard Stack / Alternatives | If product/security requires local encrypted-at-rest credentials, Phase 6 needs an additional local secret-storage decision and more implementation scope. |
| A2 | A refresh skew before one-month token expiry is acceptable even though `tvdb.yml` does not specify an exact refresh-before-expiry window. | Architecture Patterns / Token Cache | If TVDB revokes tokens early or requires a specific refresh policy, token handling needs live validation or provider docs confirmation. |
| A3 | Official-site remote IDs can be normalized by source name plus URL canonicalization because `tvdb.yml` exposes `RemoteID.id` and `RemoteID.sourceName` without a fixed official-site enum. | Architecture Patterns / Identity Lookup | If source names differ in live API data, matching may miss some official-site aliases until telemetry/tests add synonyms. |

## Open Questions

1. **Should TVDB credentials be locally encrypted at rest?**
   - What we know: Existing local integration settings store API keys in Preferences DataStore, while remote account sync stores API keys through the secret channel. [VERIFIED: TmdbSettingsDataStore.kt; VERIFIED: AccountSettingsSyncService.kt]
   - What's unclear: D-02 may mean remote sync secret-backed only, or it may require a new local encrypted secret store. [VERIFIED: 06-CONTEXT.md; ASSUMED]
   - Recommendation: Plan using existing local pattern unless the user explicitly upgrades the security requirement before implementation. [ASSUMED]

2. **Should `tvdb.yml` be committed before implementation plans depend on it?**
   - What we know: `tvdb.yml` exists in the worktree, but `git status --short` shows it as untracked. [VERIFIED: git status]
   - What's unclear: The user called it checked-in, so the worktree may be ahead of git or the file may need adding intentionally. [VERIFIED: user prompt; VERIFIED: git status]
   - Recommendation: Treat `tvdb.yml` as the local contract for planning, and include a Wave 0 check to either commit it or document the authoritative source. [VERIFIED: user prompt; ASSUMED]

3. **Which exact `RemoteID.sourceName` strings appear in production for TV Maze, Wikidata, official site, and TMDB?**
   - What we know: `RemoteID.sourceName` is a string and context says live validation returned those categories. [VERIFIED: tvdb.yml; VERIFIED: 06-CONTEXT.md]
   - What's unclear: `tvdb.yml` does not enumerate source names. [VERIFIED: tvdb.yml]
   - Recommendation: Implement tolerant normalization with synonym tests and preserve unknown IDs as `OTHER`. [ASSUMED]

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|-------------|-----------|---------|----------|
| JDK | Gradle Android build/tests | ✓ | OpenJDK `17.0.18` | None needed. [VERIFIED: java -version] |
| Gradle wrapper | Build/test/lint commands | ✓ | Gradle `8.13` | None needed. [VERIFIED: ./gradlew --version] |
| Android SDK | Android build | ✓ | `ANDROID_HOME=/Users/jneerdael/Library/Android/sdk` | None needed. [VERIFIED: printenv ANDROID_HOME] |
| adb | Optional instrumentation/manual checks | ✓ | `/Users/jneerdael/Library/Android/sdk/platform-tools/adb` | JVM unit tests remain fallback for Phase 6 foundation. [VERIFIED: command -v adb] |
| TVDB live credential | Optional manual validation only | Not required for planning | Not inspected | Use mocked Retrofit/MockWebServer tests; do not read local secret files in research. [VERIFIED: user prompt; VERIFIED: app/build.gradle.kts] |

**Missing dependencies with no fallback:** None found for planning and JVM unit-test validation. [VERIFIED: environment probes]

**Missing dependencies with fallback:** TVDB live credential is not needed for automated tests because MockWebServer and MockK are available. [VERIFIED: app/build.gradle.kts]

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit4, MockK, kotlinx-coroutines-test, MockWebServer, Robolectric. [VERIFIED: app/build.gradle.kts] |
| Config file | Gradle build files; no separate JUnit config found in scanned test infrastructure. [VERIFIED: app/build.gradle.kts; VERIFIED: rg --files app/src/test] |
| Quick run command | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.*" --tests "com.nexio.tv.ui.screens.settings.TvdbSettingsViewModelTest" --tests "com.nexio.tv.core.sync.AccountConfigSyncContractTest"` [VERIFIED: CLAUDE.md; VERIFIED: app/src/test] |
| Full suite command | `./gradlew testArm64DebugUnitTest` [VERIFIED: CLAUDE.md] |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|--------------|
| PREF-01 | TVDB credentials validate, save, clear, and gate enabled state without logging secrets | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.ui.screens.settings.TvdbSettingsViewModelTest"` | ❌ Wave 0 [VERIFIED: app/src/test] |
| PREF-01 | Public sync payload includes TVDB non-secret state and omits API key/PIN | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.sync.AccountConfigSyncContractTest"` | ✅ Existing file needs TVDB cases [VERIFIED: AccountConfigSyncContractTest.kt] |
| PREF-04 | TVDB inactive leaves TMDB-backed behavior unchanged | unit/smoke | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.TvdbProviderFallbackTest"` | ❌ Wave 0 [VERIFIED: ROADMAP.md] |
| PREF-05 | Active invalid/unusable TVDB records diagnostic status and fallback reason | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.TvdbDiagnosticsTest"` | ❌ Wave 0 [VERIFIED: 06-CONTEXT.md] |
| PREF-06 | IMDb/TMDB/TV Maze/Wikidata/official-site/TVDB IDs resolve to one `TvdbSeriesIdentity` without TMDB API | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.TvdbIdentityServiceTest"` | ❌ Wave 0 [VERIFIED: 06-CONTEXT.md; VERIFIED: tvdb.yml] |
| CACHE-01 | Parallel identity requests share one TVDB remote call and cached token avoids repeated login | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.TvdbTokenStoreTest" --tests "com.nexio.tv.core.tvdb.TvdbIdentityServiceTest"` | ❌ Wave 0 [VERIFIED: TmdbMetadataPerformanceTest.kt] |

### Sampling Rate

- **Per task commit:** Run the most specific TVDB/sync/settings test command for the touched module. [VERIFIED: existing test layout]
- **Per wave merge:** Run `./gradlew testArm64DebugUnitTest`. [VERIFIED: CLAUDE.md]
- **Phase gate:** `./gradlew testArm64DebugUnitTest` and `./gradlew assembleArm64Debug` should pass before `/gsd-verify-work`. [VERIFIED: CLAUDE.md]

### Wave 0 Gaps

- [ ] `app/src/test/java/com/nexio/tv/core/tvdb/TvdbAuthServiceTest.kt` — covers login payload omission of blank PIN, token cache, and 401 handling. [VERIFIED: tvdb.yml]
- [ ] `app/src/test/java/com/nexio/tv/core/tvdb/TvdbIdentityServiceTest.kt` — covers broad remote-ID normalization, in-flight de-duping, cache hit, and no TMDB dependency. [VERIFIED: 06-CONTEXT.md; VERIFIED: TmdbService.kt]
- [ ] `app/src/test/java/com/nexio/tv/ui/screens/settings/TvdbSettingsViewModelTest.kt` — covers validation states and enablement gating. [VERIFIED: TheIntroDbSettingsViewModelTest.kt; VERIFIED: TmdbSettingsViewModel.kt]
- [ ] Extend `app/src/test/java/com/nexio/tv/core/sync/AccountConfigSyncContractTest.kt` — covers TVDB public sync fields and credential omission. [VERIFIED: AccountConfigSyncContractTest.kt]
- [ ] SQL/static grep verification for every `tvdb_api_key` allowlist occurrence in `supabase/account_settings_sync.sql`. [VERIFIED: supabase/account_settings_sync.sql]

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|------------------|
| V2 Authentication | Yes | TVDB `/login` uses API key plus optional PIN and returns bearer token; never log login body or token. [VERIFIED: tvdb.yml; VERIFIED: LogSanitizer.kt] |
| V3 Session Management | Yes | Persist token expiry metadata and clear token on credential change. [VERIFIED: tvdb.yml; ASSUMED] |
| V4 Access Control | Yes | Account sync secret RPCs are per authenticated sync owner through existing Supabase functions. [VERIFIED: supabase/account_settings_sync.sql] |
| V5 Input Validation | Yes | Trim API key/PIN, omit blank PIN, normalize remote IDs/source names, and reject unsupported identity types in series resolution. [VERIFIED: tvdb.yml; VERIFIED: TmdbSettingsViewModel.kt; ASSUMED] |
| V6 Cryptography | Yes | Do not hand-roll crypto; use existing Supabase Vault-backed account secret RPCs for synced credentials. [VERIFIED: supabase/account_settings_sync.sql] |

### Known Threat Patterns for This Stack

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| API key/PIN in public sync payload | Information Disclosure | Store only configured/enabled/status in public sync and store credentials through TVDB account secret payload. [VERIFIED: AccountSettingsSyncService.kt; VERIFIED: 06-CONTEXT.md] |
| Bearer token in logs | Information Disclosure | Do not log bodies/headers; use sanitized endpoint/status logs only. [VERIFIED: tvdb.yml; VERIFIED: LogSanitizer.kt] |
| Cross-account secret overwrite | Elevation of Privilege | Use existing sync secret RPCs keyed by authenticated sync owner and fixed `integration:tvdb` ref. [VERIFIED: supabase/account_settings_sync.sql; VERIFIED: AccountSettingsSyncService.kt] |
| Identity spoof through broad remote-id search | Tampering | Filter search results to series and hydrate/preserve TVDB series ID before accepting identity. [VERIFIED: tvdb.yml] |
| Re-auth storm under concurrency | Denial of Service | Use mutex/deferred in-flight de-duping around token refresh and identity lookup. [VERIFIED: TmdbService.kt; VERIFIED: TmdbMetadataPerformanceTest.kt] |

## Sources

### Primary (HIGH confidence)

- `.planning/phases/06-tvdb-foundation-and-identity/06-CONTEXT.md` — locked user decisions, scope, deferred work. [VERIFIED: file read]
- `.planning/REQUIREMENTS.md` — Phase 6 requirement IDs and descriptions. [VERIFIED: file read]
- `.planning/ROADMAP.md` — Phase 6 success criteria and Phase 7+ boundaries. [VERIFIED: file read]
- `.planning/STATE.md` and `.planning/PROJECT.md` — milestone decisions and provider precedence. [VERIFIED: file read]
- `CLAUDE.md` — repo constraints and build/test commands. [VERIFIED: file read]
- `tvdb.yml` — TVDB API V4 OpenAPI contract, version `4.7.10`, login/search/series/update schemas, server URL. [VERIFIED: file read]
- `app/src/main/java/com/nexio/tv/data/local/TmdbSettingsDataStore.kt` — settings DataStore pattern. [VERIFIED: file read]
- `app/src/main/java/com/nexio/tv/ui/screens/settings/TmdbSettingsViewModel.kt` and `TmdbSettingsScreen.kt` — validation/UI pattern. [VERIFIED: file read]
- `app/src/main/java/com/nexio/tv/core/di/NetworkModule.kt` and `app/src/main/java/com/nexio/tv/data/remote/api/TmdbApi.kt` — Retrofit/Moshi provider pattern. [VERIFIED: file read]
- `app/src/main/java/com/nexio/tv/core/tmdb/TmdbService.kt` — cache and in-flight lookup de-duping pattern. [VERIFIED: file read]
- `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt`, `AccountConfigSyncContract.kt`, `AccountSyncModels.kt`, `supabase/account_settings_sync.sql`, and `docs/settings/settings-sync.schema.json` — public sync and secret-channel architecture. [VERIFIED: file read]
- `app/src/main/java/com/nexio/tv/data/local/MetadataDiskCacheStore.kt` — metadata disk cache namespace/schema/batching pattern. [VERIFIED: file read]
- `app/build.gradle.kts` and `gradle/libs.versions.toml` — dependency versions and test stack. [VERIFIED: file read]

### Secondary (MEDIUM confidence)

- TheTVDB official GitHub repository and GitHub Pages Swagger UI were located as official API documentation surfaces. [CITED: https://github.com/thetvdb/v4-api; CITED: https://thetvdb.github.io/v4-api/]

### Tertiary (LOW confidence)

- None used as authoritative sources. [VERIFIED: research process]

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — derived from Gradle files and existing code. [VERIFIED: gradle/libs.versions.toml; VERIFIED: app/build.gradle.kts]
- Architecture: HIGH — direct local analogues exist for settings, Retrofit, sync secrets, and in-flight de-duping. [VERIFIED: TmdbSettingsDataStore.kt; VERIFIED: NetworkModule.kt; VERIFIED: AccountSettingsSyncService.kt; VERIFIED: TmdbService.kt]
- TVDB API contract: HIGH for fields present in `tvdb.yml`, MEDIUM for live remote-id source-name variants because the schema does not enumerate source names. [VERIFIED: tvdb.yml]
- Pitfalls: HIGH for sync/secret/cache pitfalls, MEDIUM for live TVDB source-name edge cases. [VERIFIED: AccountSettingsSyncService.kt; VERIFIED: supabase/account_settings_sync.sql; VERIFIED: tvdb.yml]

**Research date:** 2026-04-14 [VERIFIED: system date]
**Valid until:** 2026-05-14 for local architecture; re-check TVDB API docs and dependency catalog if implementation starts after that date. [ASSUMED]
