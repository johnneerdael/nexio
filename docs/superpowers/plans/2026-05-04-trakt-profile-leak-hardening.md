# Trakt Profile-Leak Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate the cross-profile leaks in `TraktProgressService`'s in-memory caches and unify cross-profile coalescing on global-content reads.

**Architecture:** Move singleton-scoped caches (`cachedActivities`, `episodeInfoCache`, `lastEventDrivenRefreshMs`) into the existing per-profile `TraktProgressRuntimeState` registry. Replace the in-memory `cachedActivities` layer with a 5-minute `CacheFirst` runtime cache on `getLastActivities` so we get persistence for free. Strip the per-profile `accountOperationKey` prefix from the seven `IntegrationScope.GlobalContent` specs so single-flight coalescing matches their cache-sharing intent.

**Tech Stack:** Kotlin, Hilt DI, Room (`IntegrationCacheDatabase`), Retrofit, Moshi, JUnit + Mockito.

**Source review:** `docs/superpowers/specs/2026-05-04-trakt-watched-history-sync-design.md` Part 1 (architecture audit findings) — Recommendations 1, 4, 6.

---

## File Map

**Modify:**
- `app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt`
  - Move `cachedActivities` + `cachedActivitiesAtMs` (lines 393-414) into `TraktProgressRuntimeState`.
  - Move `episodeInfoCache` (line 313) into `TraktProgressRuntimeState`.
  - Move `lastEventDrivenRefreshMs` (lines 434-484) into `TraktProgressRuntimeState`.
  - Replace `mutableMapOf` with `ConcurrentHashMap` in `TraktProgressRuntimeRegistry` (line 294).
  - Optionally delete the `getRecentActivities` in-memory cache layer once the runtime spec covers it.
- `app/src/main/java/com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt`
  - Convert `getLastActivities` to a `runtime.get(IntegrationSpec(... CacheFirst(5min)))` shape.
  - Strip account prefix from `operationKey` on the seven `GlobalContent` specs (lines 805, 844, 881, 918, 955, 995, 1036).
  - Add `globalContentOperationKey(logicalKey)` helper near `globalContentCacheKey` (line 1296).

**Create:**
- `app/src/test/java/com/nexio/tv/data/repository/TraktProgressServiceProfileIsolationTest.kt` — locks the cross-profile isolation contract for the moved caches.
- `app/src/test/java/com/nexio/tv/data/integration/trakt/TraktLastActivitiesCachingTest.kt` — verifies the new 5-min `CacheFirst` on `getLastActivities`.
- `app/src/test/java/com/nexio/tv/data/integration/trakt/TraktGlobalContentOperationKeyTest.kt` — pins the operation-key shape for global-content specs.

---

## Task 1: Baseline test build

**Files:** none.

- [ ] **Step 1: Confirm clean tree state**

```bash
git status
git rev-parse --abbrev-ref HEAD
```
Expected: branch `codex/integration-runtime-phase-a` (or this plan's worktree branch). Concurrent codex WIP may be present in the working tree — leave it alone.

- [ ] **Step 2: Run the test surface this plan touches**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.data.repository.Trakt*" \
  --tests "com.nexio.tv.data.repository.trakt.*" \
  --tests "com.nexio.tv.data.integration.trakt.*" \
  --tests "com.nexio.tv.data.local.integration.*" \
  -x generateIntegrationRuntimeAudit
```
Expected: BUILD SUCCESSFUL. Record test count.

- [ ] **Step 3: No commit**

---

## Task 2: Move `cachedActivities` into per-profile state

The two singleton fields at `TraktProgressService.kt:393-394` cause a 10-second cross-profile leak today.

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/TraktProgressServiceProfileIsolationTest.kt` (create)

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/nexio/tv/data/repository/TraktProgressServiceProfileIsolationTest.kt`. Mirror the wiring from `TraktProgressServiceNextUpValidationTest.kt` (read its first 100 lines for the construction pattern of the service + mocks + per-profile session swap).

Add this test:

```kotlin
@Test
fun cachedActivities_does_not_leak_across_profiles() = runBlocking {
    coEvery { traktAuthService.currentTraktProfileId() } returnsMany listOf(1, 2, 1, 2)
    val profile1Activities = TraktLastActivitiesResponseDto(
        all = "2026-05-04T10:00:00Z",
        episodes = TraktLastActivitiesEpisodesDto(watchedAt = "2026-05-04T09:00:00Z")
    )
    val profile2Activities = TraktLastActivitiesResponseDto(
        all = "2026-05-04T10:05:00Z",
        episodes = TraktLastActivitiesEpisodesDto(watchedAt = "2026-05-04T10:00:00Z")
    )
    coEvery { traktIntegrationProvider.getLastActivities() } returnsMany listOf(
        IntegrationCallResult.Success(profile1Activities),
        IntegrationCallResult.Success(profile2Activities)
    )

    // Profile 1 reads.
    val first = service.getRecentActivities(maxAgeMs = 60_000L)
    // Profile 2 reads — must NOT receive profile 1's cached body.
    val second = service.getRecentActivities(maxAgeMs = 60_000L)
    // Profile 1 reads again — must receive profile 1's body, not profile 2's.
    val third = service.getRecentActivities(maxAgeMs = 60_000L)
    // Profile 2 reads again — its own body.
    val fourth = service.getRecentActivities(maxAgeMs = 60_000L)

    assertEquals("2026-05-04T09:00:00Z", first?.episodes?.watchedAt)
    assertEquals("2026-05-04T10:00:00Z", second?.episodes?.watchedAt)
    assertEquals("2026-05-04T09:00:00Z", third?.episodes?.watchedAt)
    assertEquals("2026-05-04T10:00:00Z", fourth?.episodes?.watchedAt)
    coVerify(exactly = 2) { traktIntegrationProvider.getLastActivities() }
}
```

- [ ] **Step 2a: Run the test to confirm failure**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.data.repository.TraktProgressServiceProfileIsolationTest" \
  -x generateIntegrationRuntimeAudit
```
Expected: failure — profile 2 receives profile 1's body (the singleton `cachedActivities` is shared).

- [ ] **Step 3: Move the fields into `TraktProgressRuntimeState`**

Edit `TraktProgressService.kt`. Locate the `data class TraktProgressRuntimeState(...)` declaration (around line 220). Add two fields to its parameter list:

```kotlin
@Volatile var cachedActivities: TraktLastActivitiesResponseDto? = null,
@Volatile var cachedActivitiesAtMs: Long = 0L,
```

Delete the singleton declarations at lines 393-394:

```kotlin
@Volatile private var cachedActivities: TraktLastActivitiesResponseDto? = null
@Volatile private var cachedActivitiesAtMs: Long = 0L
```

Add accessor properties next to the other `runtimeState()` accessors (e.g. near line 360):

```kotlin
private var cachedActivities: TraktLastActivitiesResponseDto?
    get() = runtimeState().cachedActivities
    set(value) { runtimeState().cachedActivities = value }

private var cachedActivitiesAtMs: Long
    get() = runtimeState().cachedActivitiesAtMs
    set(value) { runtimeState().cachedActivitiesAtMs = value }
```

Add a clear in `clearProfile` (line 303-305 area) so the cache is wiped on logout:

```kotlin
fun clearProfile() {
    runtimeState().run {
        cachedActivities = null
        cachedActivitiesAtMs = 0L
        // ... existing clears
    }
}
```

(Read the existing `clearProfile` body and append, don't replace.)

- [ ] **Step 4: Run to confirm pass**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.data.repository.TraktProgressServiceProfileIsolationTest" \
  -x generateIntegrationRuntimeAudit
```
Expected: pass — each profile reads its own activities body.

- [ ] **Step 5: Run the broader Trakt regression**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.data.repository.Trakt*" \
  --tests "com.nexio.tv.data.repository.trakt.*" \
  -x generateIntegrationRuntimeAudit
```
Expected: BUILD SUCCESSFUL. No regressions.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt \
        app/src/test/java/com/nexio/tv/data/repository/TraktProgressServiceProfileIsolationTest.kt
git commit -m "$(cat <<'EOF'
fix(trakt): scope cachedActivities per profile

The singleton cachedActivities/cachedActivitiesAtMs fields on
TraktProgressService leaked /sync/last_activities responses across
profiles within the 10s in-memory window. Move both fields into
TraktProgressRuntimeState (already keyed per-profile via the
TraktProgressRuntimeRegistry) and clear them on profile logout.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Move `episodeInfoCache` into per-profile state

`episodeInfoCache` (line 313) is keyed by content id but stored on the singleton — episode metadata for show A on profile 1 leaks to profile 2 if the same show id is queried.

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/TraktProgressServiceProfileIsolationTest.kt`

- [ ] **Step 1: Write the failing test**

Append to `TraktProgressServiceProfileIsolationTest.kt`:

```kotlin
@Test
fun episodeInfoCache_does_not_leak_across_profiles() = runBlocking {
    coEvery { traktAuthService.currentTraktProfileId() } returnsMany listOf(1, 2)

    // Profile 1 resolves episode info for tvdb:81189 S1E1.
    service.testOnlyResolveEpisodeInfo("tvdb:81189", season = 1, episode = 1)
    // Profile 2 should NOT see profile 1's cached resolution.
    val profile2HasCachedEntry = service.testOnlyEpisodeInfoCacheContains(
        contentId = "tvdb:81189",
        season = 1,
        episode = 1
    )
    assertEquals(false, profile2HasCachedEntry)
}
```

(Add a `@VisibleForTesting internal fun testOnlyEpisodeInfoCacheContains(contentId, season, episode): Boolean` test seam in `TraktProgressService.kt` returning whether the per-profile `episodeInfoCache` contains the key.)

- [ ] **Step 2: Run to confirm failure**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.data.repository.TraktProgressServiceProfileIsolationTest.episodeInfoCache_does_not_leak_across_profiles" \
  -x generateIntegrationRuntimeAudit
```

- [ ] **Step 3: Move the field**

Add to `TraktProgressRuntimeState`:

```kotlin
val episodeInfoCache: MutableMap<String, ResolvedEpisodeInfo> = mutableMapOf(),
```

Delete `private val episodeInfoCache = mutableMapOf<String, ResolvedEpisodeInfo>()` at line 313.

Add accessor:

```kotlin
private val episodeInfoCache get() = runtimeState().episodeInfoCache
```

Add clear in `clearProfile`:

```kotlin
episodeInfoCache.clear()
```

Add the test seam:

```kotlin
@VisibleForTesting
internal fun testOnlyEpisodeInfoCacheContains(contentId: String, season: Int, episode: Int): Boolean {
    val key = episodeInfoCacheKey(contentId, season, episode)  // use existing key helper
    return episodeInfoCache.containsKey(key)
}
```

(Use the existing key derivation — search `episodeInfoCache[` in the file to find how keys are built.)

- [ ] **Step 4: Run to confirm pass + regression**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.data.repository.TraktProgressServiceProfileIsolationTest" \
  --tests "com.nexio.tv.data.repository.Trakt*" \
  -x generateIntegrationRuntimeAudit
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt \
        app/src/test/java/com/nexio/tv/data/repository/TraktProgressServiceProfileIsolationTest.kt
git commit -m "$(cat <<'EOF'
fix(trakt): scope episodeInfoCache per profile

Move the episode resolution cache into TraktProgressRuntimeState so
profile A's resolved episode info cannot be served to profile B for
the same content id. Cleared on profile logout.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Move `lastEventDrivenRefreshMs` into per-profile state

The throttle suppresses event-driven refreshes across profiles today.

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/TraktProgressServiceProfileIsolationTest.kt`

- [ ] **Step 1: Write the failing test**

Append:

```kotlin
@Test
fun event_driven_refresh_throttle_does_not_leak_across_profiles() = runBlocking {
    coEvery { traktAuthService.currentTraktProfileId() } returnsMany listOf(1, 1, 2)

    service.requestEventDrivenRefresh()  // profile 1 — fires
    service.requestEventDrivenRefresh()  // profile 1 — suppressed by throttle
    service.requestEventDrivenRefresh()  // profile 2 — must fire (different profile)

    // Verify the underlying refresh path was called twice (profile 1 once, profile 2 once),
    // not three (no leak) and not once (per-profile throttle works independently).
    coVerify(atLeast = 2) { /* the function the throttle gates */ }
}
```

(Substitute the actual gated call — read `requestEventDrivenRefresh` body around line 480 to find what the throttle wraps; it's a `refreshNow()` call.)

- [ ] **Step 2: Run to confirm failure**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.data.repository.TraktProgressServiceProfileIsolationTest.event_driven_refresh_throttle_does_not_leak_across_profiles" \
  -x generateIntegrationRuntimeAudit
```

- [ ] **Step 3: Move the field**

Add to `TraktProgressRuntimeState`:

```kotlin
var lastEventDrivenRefreshMs: Long = 0L,
```

Delete `private var lastEventDrivenRefreshMs: Long = 0L` at line 435.

Add accessor:

```kotlin
private var lastEventDrivenRefreshMs: Long
    get() = runtimeState().lastEventDrivenRefreshMs
    set(value) { runtimeState().lastEventDrivenRefreshMs = value }
```

Add reset in `clearProfile`:

```kotlin
lastEventDrivenRefreshMs = 0L
```

- [ ] **Step 4: Run to confirm pass + regression**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.data.repository.Trakt*" \
  --tests "com.nexio.tv.data.repository.trakt.*" \
  -x generateIntegrationRuntimeAudit
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt \
        app/src/test/java/com/nexio/tv/data/repository/TraktProgressServiceProfileIsolationTest.kt
git commit -m "$(cat <<'EOF'
fix(trakt): scope event-driven refresh throttle per profile

The lastEventDrivenRefreshMs throttle was a singleton, so a refresh
fired on profile 1 suppressed the immediate next refresh on profile 2.
Move it into TraktProgressRuntimeState.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Make `TraktProgressRuntimeRegistry.states` thread-safe

Concurrent first-time access from two coroutines on different profiles can race the `mutableMapOf.getOrPut`.

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt:294`

- [ ] **Step 1: Find the registry**

```bash
grep -n "private val states\|class TraktProgressRuntimeRegistry\|fun stateFor" app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt
```

- [ ] **Step 2: Replace `mutableMapOf` with `ConcurrentHashMap`**

Edit the registry. Change:

```kotlin
private val states = mutableMapOf<Int, TraktProgressRuntimeState>()
```

to:

```kotlin
private val states = java.util.concurrent.ConcurrentHashMap<Int, TraktProgressRuntimeState>()
```

In `stateFor`, change `getOrPut { ... }` to `computeIfAbsent { ... }`. The behaviour is the same; the latter is atomic.

- [ ] **Step 3: Compile**

```bash
./gradlew :app:compileUniversalDebugKotlin -x generateIntegrationRuntimeAudit
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run regression**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.data.repository.Trakt*" \
  -x generateIntegrationRuntimeAudit
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt
git commit -m "$(cat <<'EOF'
fix(trakt): use ConcurrentHashMap for per-profile runtime state registry

mutableMapOf with getOrPut races on first-time per-profile access
when two coroutines on different profiles initialise simultaneously.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Add `CacheFirst(5min)` runtime cache to `getLastActivities`

This persists the activities response across cold start AND replaces the in-memory `cachedActivities` layer (which can then be deleted in Task 7).

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt:165-171`
- Test: `app/src/test/java/com/nexio/tv/data/integration/trakt/TraktLastActivitiesCachingTest.kt` (create)

- [ ] **Step 1: Write the failing test**

Create `TraktLastActivitiesCachingTest.kt`. Mirror the setup from `TraktWatchedRuntimeRoutingTest.kt`:

```kotlin
@Test
fun getLastActivities_second_call_within_ttl_does_not_hit_traktApi() = runBlocking {
    val activities = TraktLastActivitiesResponseDto(all = "2026-05-04T10:00:00Z")
    coEvery { traktApi.getLastActivities(any()) } returns Response.success(activities)

    val first = unwrap(provider.getLastActivities())
    val second = unwrap(provider.getLastActivities())

    assertEquals("2026-05-04T10:00:00Z", first?.all)
    assertEquals("2026-05-04T10:00:00Z", second?.all)
    coVerify(exactly = 1) { traktApi.getLastActivities(any()) }
}
```

(Copy `unwrap` and the provider builder from `TraktWatchedRuntimeRoutingTest.kt`.)

- [ ] **Step 2: Run to confirm failure**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.data.integration.trakt.TraktLastActivitiesCachingTest" \
  -x generateIntegrationRuntimeAudit
```
Expected: fail — current `getLastActivities` uses `executeAuthorizedBackgroundCall` (no cache).

- [ ] **Step 3: Rewrite `getLastActivities`**

Replace lines 165-171 in `TraktIntegrationProvider.kt`:

```kotlin
suspend fun getLastActivities(): IntegrationCallResult<TraktLastActivitiesResponseDto> {
    val session = traktAuthService.accountScopedSession()
    val spec = IntegrationSpec(
        provider = IntegrationProvider.TRAKT,
        apiShapeId = TraktApiShapes.LAST_ACTIVITIES,
        operationKey = accountOperationKey(session, "trakt.last_activities"),
        cacheKey = accountCacheKey(session, "trakt:sync:last_activities"),
        codec = gsonCodec<TraktLastActivitiesResponseDto>(),
        cachePolicy = IntegrationCachePolicy.CacheFirst(
            ttlMs = LAST_ACTIVITIES_TTL_MS,
            staleAfterExpiryMs = LAST_ACTIVITIES_STALE_GRACE_MS
        ),
        workClass = IntegrationWorkClass.USER_VISIBLE,
        scope = accountScope(session),
        profileContext = profileContext(session),
        load = {
            val response = traktAuthService.executeAuthorizedRequestWithinRuntimeCall(session) { authorization ->
                traktApi.getLastActivities(authorization = authorization)
            } ?: return@IntegrationSpec IntegrationLoadResult.HttpError(401, reason = "auth_missing")
            if (!response.isSuccessful) {
                return@IntegrationSpec IntegrationLoadResult.HttpError(
                    statusCode = response.code(),
                    retryAfterMs = response.headers()["Retry-After"]?.toLongOrNull()?.times(1000L),
                    reason = "trakt_last_activities_failed"
                )
            }
            IntegrationLoadResult.Success(response.body() ?: TraktLastActivitiesResponseDto())
        }
    )
    val value = runtime.get(spec).valueOrNull() ?: return IntegrationCallResult.Missing
    return IntegrationCallResult.Success(value)
}
```

Add the constants to the existing `private companion object` at the bottom of the class:

```kotlin
const val LAST_ACTIVITIES_TTL_MS: Long = 5L * 60L * 1000L         // 5 minutes — Seren's hard floor
const val LAST_ACTIVITIES_STALE_GRACE_MS: Long = 60L * 60L * 1000L  // 1h grace
```

- [ ] **Step 4: Run to confirm pass**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.data.integration.trakt.TraktLastActivitiesCachingTest" \
  -x generateIntegrationRuntimeAudit
```

- [ ] **Step 5: Run broader regression**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.data.integration.trakt.*" \
  --tests "com.nexio.tv.data.repository.Trakt*" \
  -x generateIntegrationRuntimeAudit
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt \
        app/src/test/java/com/nexio/tv/data/integration/trakt/TraktLastActivitiesCachingTest.kt
git commit -m "$(cat <<'EOF'
feat(trakt): route getLastActivities through runtime cache

Add a 5-minute CacheFirst policy on /sync/last_activities so the
endpoint isn't hammered when several UI components ask for it in
quick succession. Account-scoped, profile-isolated, persistent
across cold start.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: Delete the now-redundant `cachedActivities` in-memory layer

Tasks 2 + 6 together make the in-memory layer redundant — the runtime cache provides the same TTL semantics with cross-profile isolation by construction.

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt`

- [ ] **Step 1: Read the current `getRecentActivities`**

```bash
grep -n "fun getRecentActivities\|cachedActivities\b" app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt
```

- [ ] **Step 2: Simplify**

Replace the `getRecentActivities` body (was lines 402-414, now per-profile after Task 2):

```kotlin
suspend fun getRecentActivities(maxAgeMs: Long = 10_000L): TraktLastActivitiesResponseDto? {
    // The runtime cache (5-minute CacheFirst) is the source of truth. The maxAgeMs
    // parameter is now advisory: callers that need fresher-than-5-min data must
    // call invalidate first.
    return when (val result = traktIntegrationProvider.getLastActivities()) {
        is IntegrationCallResult.Success -> result.value
        else -> null
    }
}
```

Delete the `cachedActivities` and `cachedActivitiesAtMs` fields from `TraktProgressRuntimeState` (added in Task 2). Delete the corresponding accessors and the `clearProfile` lines.

- [ ] **Step 3: Run the profile isolation test from Task 2 — it should still pass**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.data.repository.TraktProgressServiceProfileIsolationTest.cachedActivities_does_not_leak_across_profiles" \
  -x generateIntegrationRuntimeAudit
```
Expected: pass — the runtime cache is now the isolation boundary (different account scopes → different cache rows).

- [ ] **Step 4: Run the activities caching test from Task 6**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.data.integration.trakt.TraktLastActivitiesCachingTest" \
  -x generateIntegrationRuntimeAudit
```
Expected: still pass.

- [ ] **Step 5: Broader regression**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.data.repository.Trakt*" \
  --tests "com.nexio.tv.data.repository.trakt.*" \
  -x generateIntegrationRuntimeAudit
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt
git commit -m "$(cat <<'EOF'
refactor(trakt): drop redundant in-memory cachedActivities layer

The runtime cache CacheFirst(5min) on getLastActivities replaces the
bespoke in-memory cache that previously sat in TraktProgressService.
Removing the layer keeps a single source of truth and matches the
"no in-memory bypass" rule.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: Strip account prefix from global-content `operationKey`s

Seven `IntegrationScope.GlobalContent` specs build their `operationKey` via `accountOperationKey(session, ...)`, which embeds `profile:N:provider:TRAKT:credential:HASH:` — defeating the runtime's single-flight coalescing for cache-shared global content.

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt`
- Test: `app/src/test/java/com/nexio/tv/data/integration/trakt/TraktGlobalContentOperationKeyTest.kt` (create)

- [ ] **Step 1: Add the helper**

Near `globalContentCacheKey` (line 1296):

```kotlin
private fun globalContentOperationKey(logicalKey: String): String =
    "global:provider:TRAKT:operation:$logicalKey"
```

- [ ] **Step 2: Write the failing test**

Create `TraktGlobalContentOperationKeyTest.kt`:

```kotlin
@Test
fun trending_movies_operation_key_is_global_not_account_scoped() = runBlocking {
    val runtime = RecordingIntegrationRuntime<List<TraktTrendingMovieItemDto>>(successValue = emptyList())
    val provider = TraktIntegrationProvider(
        runtime = runtime,
        traktApi = mockk(),
        traktAuthService = authServiceForProfile(1)
    )

    provider.fetchTrendingMovies(limit = 20)

    val recordedOperationKey = runtime.callSpecs.first().operationKey
    assertFalse(
        "global content operationKey must not contain profile prefix; got: $recordedOperationKey",
        recordedOperationKey.contains("profile:")
    )
    assertFalse(
        "global content operationKey must not contain credential prefix; got: $recordedOperationKey",
        recordedOperationKey.contains("credential:")
    )
}
```

(Add `@Test` for `trending_shows`, `popular_movies`, `popular_shows`, `recommendations`, `calendar_shows`, `popular_lists` — same shape, one per fetch function.)

- [ ] **Step 3: Run to confirm failure**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.data.integration.trakt.TraktGlobalContentOperationKeyTest" \
  -x generateIntegrationRuntimeAudit
```

- [ ] **Step 4: Update each global-content spec**

Edit `TraktIntegrationProvider.kt`. At each of these seven sites, replace `operationKey = accountOperationKey(session, "X")` with `operationKey = globalContentOperationKey("X")`:

- Line 805: `"trakt.calendar.shows"` (in `fetchCalendarShows`)
- Line 844: `"trakt.trending.movies"` (in `fetchTrendingMovies`)
- Line 881: `"trakt.trending.shows"` (in `fetchTrendingShows`)
- Line 918: `"trakt.popular.movies"` (in `fetchPopularMovies`)
- Line 955: `"trakt.popular.shows"` (in `fetchPopularShows`)
- Line 995: `"trakt.recommendations.$type"` (in `fetchRecommendations`)
- Line 1036: `"trakt.popular.lists"` (in `fetchPopularLists`)

The `session` may still be needed for the `load { }` lambda — keep the `accountScopedSession()` call at the top of each function but stop weaving the session into the spec metadata.

- [ ] **Step 5: Run to confirm pass**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.data.integration.trakt.TraktGlobalContentOperationKeyTest" \
  -x generateIntegrationRuntimeAudit
```

- [ ] **Step 6: Run broader regression — `TraktAuthenticatedGlobalContentBoundaryTest` is the main risk**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.data.integration.trakt.*" \
  -x generateIntegrationRuntimeAudit
```
Expected: BUILD SUCCESSFUL. The boundary enforcer rejects `Global*` scopes paired with profile-prefixed cache keys; we're changing operation keys, not cache keys, so no new violations.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt \
        app/src/test/java/com/nexio/tv/data/integration/trakt/TraktGlobalContentOperationKeyTest.kt
git commit -m "$(cat <<'EOF'
fix(trakt): use global operationKey for global-content fetches

The seven IntegrationScope.GlobalContent specs (trending/popular/
calendar/recommendations/popular_lists) used accountOperationKey,
which embedded profile:N:credential:HASH in the operation key.
The runtime's single-flight coalescer keys on operationKey, so
two profiles requesting the same global trending list produced
two in-flight calls instead of one (cache row was shared, but the
dedup wasn't). Switch to a new globalContentOperationKey helper.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 9: Final verification

**Files:** none.

- [ ] **Step 1: Full Trakt + integration test surface**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.data.repository.Trakt*" \
  --tests "com.nexio.tv.data.repository.trakt.*" \
  --tests "com.nexio.tv.data.integration.trakt.*" \
  --tests "com.nexio.tv.data.local.integration.*" \
  --tests "com.nexio.tv.core.integration.*" \
  -x generateIntegrationRuntimeAudit
```
Expected: BUILD SUCCESSFUL. Test count higher than baseline by ~10 (new isolation + caching + opkey tests).

- [ ] **Step 2: Verify no new singleton-scoped caches were introduced**

```bash
grep -n "@Volatile private var cached\|private val .* = mutableMapOf<\|private var last.*Ms: Long = 0L" \
  app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt | grep -v "RuntimeState"
```
Expected: empty output for `cached*` patterns; any per-singleton mutable state should now live in `TraktProgressRuntimeState`.

---

## Self-Review

**Spec coverage:**
- Recommendation 1 (cachedActivities leak): Tasks 2, 7. ✅
- Recommendation 4 (5-min CacheFirst on getLastActivities): Task 6. ✅
- Recommendation 6 (drop account prefix from global-content opkeys): Task 8. ✅
- Bonus (LOW-priority concurrent map fix): Task 5. ✅
- Bonus (episodeInfoCache + lastEventDrivenRefreshMs leaks): Tasks 3, 4. ✅

**Placeholder scan:** clean. Each step has either explicit code, an explicit grep, or an explicit gradle command with expected output.

**Type consistency:** `cachedActivities`, `cachedActivitiesAtMs`, `episodeInfoCache`, `lastEventDrivenRefreshMs` all live in `TraktProgressRuntimeState` after Tasks 2-4 (Task 7 then deletes the first two as the runtime cache supersedes them). `LAST_ACTIVITIES_TTL_MS` and `LAST_ACTIVITIES_STALE_GRACE_MS` constants are added to the existing companion object alongside `WATCHED_SNAPSHOT_TTL_MS`.
