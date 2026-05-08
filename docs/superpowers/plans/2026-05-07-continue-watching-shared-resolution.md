# Continue Watching Shared Resolution Implementation Plan

> **Superseded for execution:** Do not execute this broad plan directly. It has been split because P0 canonical identity/playback must land before display localization and next-up work. Execute `docs/superpowers/plans/2026-05-08-continue-watching-p0-identity-playback.md` first.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move Continue Watching onto shared canonical identity, stream-fetch identity, localized display, profile-scoped storage, and next-up discovery so Citadel-style playback, duplicate, localization, and newly aired regressions are fixed together.

**Architecture:** Continue Watching will stop treating `WatchProgress.videoId` as an all-purpose identity. Raw local/remote progress will resolve into canonical keys, resume identities, stream-fetch identities, and localized display metadata before merge and render. Existing snapshot fields stay readable for migration, while new canonical records become the authoritative path for click routing and dedupe.

**Tech Stack:** Kotlin, Android/Hilt, coroutines/Flow, Gson snapshot persistence, MetadataRouterFacade, ResolvedDisplaySurfaceRepository, JUnit4, MockK, Robolectric.

---

## Scope Check

The RCA covers four symptoms, but they share one architectural break: Continue Watching is a parallel feed system. This plan keeps the work in one branch because P0 playback and dedupe both depend on the same canonical identity resolver. The plan is staged so P0 can be reviewed and shipped before the next-up engine work:

- Phase 0 tasks produce a working playback/dedupe fix for Citadel.
- Phase 1 tasks make display metadata derive from the shared localized surface.
- Phase 2 tasks harden persistence/profile/migration.
- Phase 3 tasks add newly aired fallback discovery.

## File Structure

Create:

- `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingIdentityModels.kt`  
  Owns canonical CW key, resume identity, stream-fetch identity, confidence, and raw input models.
- `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingItemKeys.kt`  
  Owns all parent/episode/display key string generation for snapshot maps and UI dedupe.
- `app/src/main/java/com/nexio/tv/data/repository/StreamFetchIdentityResolver.kt`  
  Resolves addon-compatible stream ids, especially IMDb episode ids for series.
- `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingIdentityResolver.kt`  
  Resolves raw `WatchProgress` or `TrackingNextUpEntry` into canonical/display/stream/tracking identities.
- `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingMerger.kt`  
  Merges raw local/remote/synthetic inputs by canonical CW key.
- `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingDisplayResolver.kt`  
  Resolves localized `HomeDisplayMetadata` from resolved display surface, route snapshot, click-time metadata, persisted fallback.
- `app/src/main/java/com/nexio/tv/data/repository/NextUpDiscoveryEngine.kt`  
  Discovers next-up candidates from watched seeds when provider next-up streams are empty.
- `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingIdentityModelsTest.kt`
- `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingItemKeysTest.kt`
- `app/src/test/java/com/nexio/tv/data/repository/StreamFetchIdentityResolverTest.kt`
- `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingIdentityResolverTest.kt`
- `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingMergerTest.kt`
- `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingDisplayResolverTest.kt`
- `app/src/test/java/com/nexio/tv/data/repository/NextUpDiscoveryEngineTest.kt`

Modify:

- `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingRecord.kt`  
  Add canonical key, resume identity, stream-fetch identity, tracking identity, language tag, and source details while retaining existing constructor defaults for current tests.
- `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt`  
  Resolve and merge canonical records before snapshot publication; migrate legacy snapshots on read; hydrate display from `ContinueWatchingDisplayResolver`.
- `app/src/main/java/com/nexio/tv/data/local/ContinueWatchingSnapshotStore.kt`  
  Preserve schema compatibility and write migrated schema after successful canonicalization.
- `app/src/main/java/com/nexio/tv/data/local/WatchProgressPreferences.kt`  
  Stop writing episode progress into series-level mirror rows and quarantine old mirrors on read.
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeUiState.kt`  
  Add `canonicalKey` and `streamFetchVideoId` to Continue Watching UI models.
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt`  
  Use canonical keys for UI dedupe, route metadata keys, and localized display fallback.
- `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeModels.kt`  
  Use canonical keys in `continueWatchingItemKey` and expose `streamFetchVideoId`.
- `app/src/main/java/com/nexio/tv/ui/navigation/NexioNavHost.kt`  
  Pass `streamVideoId` from CW item to stream route.
- `app/src/main/java/com/nexio/tv/core/di/RepositoryModule.kt`  
  Add Hilt bindings or constructor availability for new repository classes if constructor injection is not enough.
- `app/src/main/java/com/nexio/tv/core/trace/TraceMetadataEvents.kt`  
  Add typed helper methods for CW identity/display/next-up trace events.

Update tests:

- `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingRecordTest.kt`
- `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingMetadataRouterTest.kt`
- `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotServiceMutationTest.kt`
- `app/src/test/java/com/nexio/tv/data/local/WatchProgressPreferencesProfileBoundaryTest.kt`
- `app/src/test/java/com/nexio/tv/ui/navigation/ScreenStreamRouteTest.kt`
- `app/src/test/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatchingTest.kt`
- `app/src/test/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatchingProjectionTest.kt`
- `app/src/test/java/com/nexio/tv/sync/ProfileSettingsScopeContractTest.kt`

## Task 1: Canonical Identity Models

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingIdentityModels.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingIdentityModelsTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ContentIdentity
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinueWatchingIdentityModelsTest {
    @Test
    fun `canonical key includes profile canonical parent and episode coordinate`() {
        val key = ContinueWatchingCanonicalKey(
            mediaKind = MetadataMediaKind.SERIES,
            canonicalParent = ContentIdentity(
                canonicalProvider = ProviderId.TVDB,
                canonicalId = "393268",
                providerIds = ProviderIds(tvdb = "393268", imdb = "tt9794044")
            ),
            season = 2,
            episode = 1,
            episodeCoordinateProvider = ProviderId.TVDB,
            profileId = 7
        )

        assertEquals("profile:7:series:tvdb:393268:s2e1", key.stableKey())
    }

    @Test
    fun `resume identity preserves raw local playback ids`() {
        val resume = ResumeIdentity(
            source = ContinueWatchingSource.LOCAL,
            contentId = "tvdb:393268",
            videoId = "tvdb:393268:2:1",
            season = 2,
            episode = 1,
            positionMs = 65_066L,
            durationMs = 2_958_656L,
            progressPercent = null,
            lastWatchedMs = 1_778_171_360_859L
        )

        assertEquals("tvdb:393268", resume.contentId)
        assertEquals("tvdb:393268:2:1", resume.videoId)
        assertTrue(resume.isEpisode)
    }

    @Test
    fun `stream fetch identity reports imdb episode scheme`() {
        val identity = StreamFetchIdentity(
            contentId = "tvdb:393268",
            videoId = "tt9794044:2:1",
            idScheme = StreamIdScheme.IMDB_EPISODE,
            confidence = IdentityConfidence.HIGH,
            trace = listOf("imdb sidecar tt9794044")
        )

        assertEquals("tt9794044:2:1", identity.videoId)
        assertEquals(StreamIdScheme.IMDB_EPISODE, identity.idScheme)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.data.repository.ContinueWatchingIdentityModelsTest
```

Expected: FAIL with unresolved references for `ContinueWatchingCanonicalKey`, `ResumeIdentity`, `StreamFetchIdentity`, `StreamIdScheme`, `ContinueWatchingSource`, and `IdentityConfidence`.

- [ ] **Step 3: Add identity model file**

Create `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingIdentityModels.kt`:

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ContentIdentity
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.WatchProgress

data class ContinueWatchingCanonicalKey(
    val mediaKind: MetadataMediaKind,
    val canonicalParent: ContentIdentity,
    val season: Int?,
    val episode: Int?,
    val episodeCoordinateProvider: ProviderId?,
    val profileId: Int
) {
    init {
        require(profileId > 0) { "profileId must be positive" }
        require(!canonicalProviderToken().isNullOrBlank()) { "canonical provider token must not be blank" }
        require(!canonicalIdToken().isNullOrBlank()) { "canonical id token must not be blank" }
        require((season == null) == (episode == null)) { "season and episode must both be present or both be absent" }
    }

    fun stableKey(): String {
        val base = "profile:$profileId:${mediaKind.name.lowercase()}:${canonicalProviderToken()}:${canonicalIdToken()}"
        return if (season != null && episode != null) "$base:s${season}e${episode}" else base
    }

    private fun canonicalProviderToken(): String? =
        canonicalParent.canonicalProvider?.name?.lowercase()
            ?: providerTokenFromIds(canonicalParent.providerIds)

    private fun canonicalIdToken(): String? =
        canonicalParent.canonicalId
            ?: canonicalParent.providerIds.tvdb
            ?: canonicalParent.providerIds.tmdb
            ?: canonicalParent.providerIds.kitsu
            ?: canonicalParent.providerIds.imdb
            ?: canonicalParent.providerIds.trakt
            ?: canonicalParent.providerIds.simkl
}

data class ResumeIdentity(
    val source: ContinueWatchingSource,
    val contentId: String,
    val videoId: String?,
    val season: Int?,
    val episode: Int?,
    val positionMs: Long,
    val durationMs: Long?,
    val progressPercent: Float?,
    val lastWatchedMs: Long
) {
    init {
        require(contentId.isNotBlank()) { "contentId must not be blank" }
        require(positionMs >= 0L) { "positionMs must be >= 0" }
        require(durationMs == null || durationMs >= 0L) { "durationMs must be >= 0 when present" }
        require(lastWatchedMs >= 0L) { "lastWatchedMs must be >= 0" }
    }

    val isEpisode: Boolean = season != null && episode != null
}

data class StreamFetchIdentity(
    val contentId: String,
    val videoId: String,
    val idScheme: StreamIdScheme,
    val confidence: IdentityConfidence,
    val trace: List<String>
) {
    init {
        require(contentId.isNotBlank()) { "contentId must not be blank" }
        require(videoId.isNotBlank()) { "videoId must not be blank" }
        require(trace.isNotEmpty()) { "trace must not be empty" }
    }
}

data class TrackingIdentity(
    val traktShowId: Int? = null,
    val traktEpisodeId: Int? = null,
    val traktPlaybackId: Long? = null,
    val traktMovieId: Int? = null,
    val providerIds: ProviderIds = ProviderIds()
)

data class RawContinueWatchingInput(
    val profileId: Int,
    val progress: WatchProgress,
    val languageTag: String
) {
    init {
        require(profileId > 0) { "profileId must be positive" }
        require(languageTag.isNotBlank()) { "languageTag must not be blank" }
    }
}

data class ContinueWatchingIdentityResolution(
    val canonicalKey: ContinueWatchingCanonicalKey,
    val displayIdentity: ContentIdentity,
    val streamFetchIdentity: StreamFetchIdentity?,
    val trackingIdentity: TrackingIdentity?,
    val confidence: IdentityConfidence,
    val warnings: List<String>
)

enum class ContinueWatchingSource {
    LOCAL,
    TRAKT_PLAYBACK,
    TRAKT_HISTORY,
    TRAKT_SHOW_PROGRESS,
    SIMKL,
    SYNTHETIC
}

enum class StreamIdScheme {
    IMDB_MOVIE,
    IMDB_EPISODE,
    PROVIDER_NATIVE,
    UNRESOLVED
}

enum class IdentityConfidence {
    HIGH,
    MEDIUM,
    LOW
}

fun WatchProgress.toResumeIdentity(): ResumeIdentity =
    ResumeIdentity(
        source = source.toContinueWatchingSource(),
        contentId = contentId,
        videoId = videoId.takeIf { it.isNotBlank() },
        season = season,
        episode = episode,
        positionMs = position,
        durationMs = duration,
        progressPercent = progressPercent,
        lastWatchedMs = lastWatched
    )

fun WatchProgress.toTrackingIdentity(): TrackingIdentity? {
    val hasTrakt = traktShowId != null ||
        traktEpisodeId != null ||
        traktPlaybackId != null ||
        traktMovieId != null
    if (!hasTrakt) return null
    return TrackingIdentity(
        traktShowId = traktShowId,
        traktEpisodeId = traktEpisodeId,
        traktPlaybackId = traktPlaybackId,
        traktMovieId = traktMovieId,
        providerIds = ProviderIds(
            imdb = contentId.takeIf { it.startsWith("tt") },
            trakt = traktShowId?.toString()
        )
    )
}

private fun String.toContinueWatchingSource(): ContinueWatchingSource =
    when (this) {
        WatchProgress.SOURCE_TRAKT_PLAYBACK -> ContinueWatchingSource.TRAKT_PLAYBACK
        WatchProgress.SOURCE_TRAKT_HISTORY -> ContinueWatchingSource.TRAKT_HISTORY
        WatchProgress.SOURCE_TRAKT_SHOW_PROGRESS -> ContinueWatchingSource.TRAKT_SHOW_PROGRESS
        else -> ContinueWatchingSource.LOCAL
    }

private fun providerTokenFromIds(ids: ProviderIds): String? =
    when {
        ids.tvdb != null -> "tvdb"
        ids.tmdb != null -> "tmdb"
        ids.kitsu != null -> "kitsu"
        ids.imdb != null -> "imdb"
        ids.trakt != null -> "trakt"
        ids.simkl != null -> "simkl"
        else -> null
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.data.repository.ContinueWatchingIdentityModelsTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingIdentityModels.kt app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingIdentityModelsTest.kt
git commit -m "feat: add continue watching identity models"
```

## Task 2: Shared Continue Watching Item Keys

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingItemKeys.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingItemKeysTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ContentIdentity
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import org.junit.Assert.assertEquals
import org.junit.Test

class ContinueWatchingItemKeysTest {
    @Test
    fun `parent key uses canonical provider and id`() {
        val identity = ContentIdentity(
            canonicalProvider = ProviderId.TVDB,
            canonicalId = "393268",
            providerIds = ProviderIds(tvdb = "393268", imdb = "tt9794044")
        )

        assertEquals("series:tvdb:393268", ContinueWatchingItemKeys.parentKey(MetadataMediaKind.SERIES, identity))
    }

    @Test
    fun `episode key adds season episode coordinate`() {
        val identity = ContentIdentity(
            canonicalProvider = ProviderId.TVDB,
            canonicalId = "393268",
            providerIds = ProviderIds(tvdb = "393268", imdb = "tt9794044")
        )

        assertEquals("series:tvdb:393268:s2e1", ContinueWatchingItemKeys.episodeKey(MetadataMediaKind.SERIES, identity, 2, 1))
    }

    @Test
    fun `display key delegates to canonical key stable key`() {
        val key = ContinueWatchingCanonicalKey(
            mediaKind = MetadataMediaKind.SERIES,
            canonicalParent = ContentIdentity(
                canonicalProvider = ProviderId.TVDB,
                canonicalId = "393268",
                providerIds = ProviderIds(tvdb = "393268", imdb = "tt9794044")
            ),
            season = 2,
            episode = 1,
            episodeCoordinateProvider = ProviderId.TVDB,
            profileId = 3
        )

        assertEquals("profile:3:series:tvdb:393268:s2e1", ContinueWatchingItemKeys.displayKey(key))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.data.repository.ContinueWatchingItemKeysTest
```

Expected: FAIL with unresolved reference `ContinueWatchingItemKeys`.

- [ ] **Step 3: Add key generator**

Create `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingItemKeys.kt`:

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ContentIdentity
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds

object ContinueWatchingItemKeys {
    fun parentKey(mediaKind: MetadataMediaKind, identity: ContentIdentity): String {
        val kind = mediaKind.name.lowercase()
        return "$kind:${providerToken(identity)}:${idToken(identity)}"
    }

    fun episodeKey(
        mediaKind: MetadataMediaKind,
        identity: ContentIdentity,
        season: Int,
        episode: Int
    ): String {
        return "${parentKey(mediaKind, identity)}:s${season}e${episode}"
    }

    fun displayKey(canonicalKey: ContinueWatchingCanonicalKey): String =
        canonicalKey.stableKey()

    fun legacyParentKey(contentType: String, contentId: String): String =
        "${contentType.lowercase()}:${contentId.trim()}"

    private fun providerToken(identity: ContentIdentity): String =
        identity.canonicalProvider?.name?.lowercase()
            ?: providerFromIds(identity.providerIds)
            ?: "raw"

    private fun idToken(identity: ContentIdentity): String =
        identity.canonicalId?.trim()?.takeIf { it.isNotBlank() }
            ?: idFromProviderIds(identity.providerIds)
            ?: "unknown"

    private fun providerFromIds(ids: ProviderIds): String? =
        when {
            ids.tvdb != null -> ProviderId.TVDB.name.lowercase()
            ids.tmdb != null -> ProviderId.TMDB.name.lowercase()
            ids.kitsu != null -> ProviderId.KITSU.name.lowercase()
            ids.imdb != null -> ProviderId.IMDB.name.lowercase()
            ids.trakt != null -> ProviderId.TRAKT.name.lowercase()
            ids.simkl != null -> ProviderId.SIMKL.name.lowercase()
            else -> null
        }

    private fun idFromProviderIds(ids: ProviderIds): String? =
        ids.tvdb ?: ids.tmdb ?: ids.kitsu ?: ids.imdb ?: ids.trakt ?: ids.simkl
}
```

- [ ] **Step 4: Replace local key-building in route context test expectation**

Modify `app/src/test/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatchingTest.kt` so the existing route context test expects the shared key helper:

```kotlin
import com.nexio.tv.data.repository.ContinueWatchingItemKeys
```

Replace:

```kotlin
coJustRun { snapshotService.recordMetadataSnapshot("series:tvdb:121361", capture(snapshotSlot)) }
```

with:

```kotlin
coJustRun {
    snapshotService.recordMetadataSnapshot(
        ContinueWatchingItemKeys.legacyParentKey("series", "tvdb:121361"),
        capture(snapshotSlot)
    )
}
```

Replace:

```kotlin
snapshotService.recordMetadataSnapshot("series:tvdb:121361", any())
```

with:

```kotlin
snapshotService.recordMetadataSnapshot(
    ContinueWatchingItemKeys.legacyParentKey("series", "tvdb:121361"),
    any()
)
```

- [ ] **Step 5: Run tests to verify they pass**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.data.repository.ContinueWatchingItemKeysTest --tests com.nexio.tv.ui.screens.home.HomeViewModelContinueWatchingTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingItemKeys.kt app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingItemKeysTest.kt app/src/test/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatchingTest.kt
git commit -m "feat: centralize continue watching item keys"
```

## Task 3: Stream Fetch Identity Resolver

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/repository/StreamFetchIdentityResolver.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/StreamFetchIdentityResolverTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ContentIdentity
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamFetchIdentityResolverTest {
    private val resolver = StreamFetchIdentityResolver()

    @Test
    fun `series episode uses imdb episode video id when imdb sidecar exists`() = runTest {
        val result = resolver.resolveForEpisode(
            canonicalIdentity = ContentIdentity(
                canonicalProvider = ProviderId.TVDB,
                canonicalId = "393268",
                providerIds = ProviderIds(tvdb = "393268", imdb = "tt9794044")
            ),
            knownIds = ProviderIds(tvdb = "393268", imdb = "tt9794044"),
            season = 2,
            episode = 1,
            sourceContext = StreamSourceContext(mediaKind = MetadataMediaKind.SERIES, resumeVideoId = "tvdb:393268:2:1")
        )

        assertEquals("tt9794044:2:1", result?.videoId)
        assertEquals(StreamIdScheme.IMDB_EPISODE, result?.idScheme)
        assertEquals(IdentityConfidence.HIGH, result?.confidence)
    }

    @Test
    fun `movie uses imdb movie id when imdb sidecar exists`() = runTest {
        val result = resolver.resolveForMovie(
            canonicalIdentity = ContentIdentity(
                canonicalProvider = ProviderId.TMDB,
                canonicalId = "687163",
                providerIds = ProviderIds(tmdb = "687163", imdb = "tt12042730")
            ),
            knownIds = ProviderIds(tmdb = "687163", imdb = "tt12042730"),
            sourceContext = StreamSourceContext(mediaKind = MetadataMediaKind.MOVIE, resumeVideoId = "tt12042730")
        )

        assertEquals("tt12042730", result?.videoId)
        assertEquals(StreamIdScheme.IMDB_MOVIE, result?.idScheme)
    }

    @Test
    fun `series episode returns null when imdb sidecar is missing`() = runTest {
        val result = resolver.resolveForEpisode(
            canonicalIdentity = ContentIdentity(
                canonicalProvider = ProviderId.TVDB,
                canonicalId = "393268",
                providerIds = ProviderIds(tvdb = "393268")
            ),
            knownIds = ProviderIds(tvdb = "393268"),
            season = 2,
            episode = 1,
            sourceContext = StreamSourceContext(mediaKind = MetadataMediaKind.SERIES, resumeVideoId = "tvdb:393268:2:1")
        )

        assertNull(result)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.data.repository.StreamFetchIdentityResolverTest
```

Expected: FAIL with unresolved references for `StreamFetchIdentityResolver` and `StreamSourceContext`.

- [ ] **Step 3: Add resolver**

Create `app/src/main/java/com/nexio/tv/data/repository/StreamFetchIdentityResolver.kt`:

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ContentIdentity
import com.nexio.tv.domain.model.ProviderIds
import javax.inject.Inject
import javax.inject.Singleton

data class StreamSourceContext(
    val mediaKind: MetadataMediaKind,
    val resumeVideoId: String?,
    val addonBaseUrlHost: String? = null
)

@Singleton
class StreamFetchIdentityResolver @Inject constructor() {
    suspend fun resolveForEpisode(
        canonicalIdentity: ContentIdentity,
        knownIds: ProviderIds,
        season: Int,
        episode: Int,
        sourceContext: StreamSourceContext
    ): StreamFetchIdentity? {
        val imdbId = knownIds.imdb?.takeIf { it.isStrictImdbId() }
            ?: canonicalIdentity.providerIds.imdb?.takeIf { it.isStrictImdbId() }
            ?: return null
        return StreamFetchIdentity(
            contentId = canonicalIdentity.providerIds.tvdb?.let { "tvdb:$it" }
                ?: canonicalIdentity.providerIds.tmdb?.let { "tmdb:$it" }
                ?: canonicalIdentity.providerIds.imdb
                ?: imdbId,
            videoId = "$imdbId:$season:$episode",
            idScheme = StreamIdScheme.IMDB_EPISODE,
            confidence = IdentityConfidence.HIGH,
            trace = listOf("resolved imdb episode stream id from sidecar $imdbId")
        )
    }

    suspend fun resolveForMovie(
        canonicalIdentity: ContentIdentity,
        knownIds: ProviderIds,
        sourceContext: StreamSourceContext
    ): StreamFetchIdentity? {
        val imdbId = knownIds.imdb?.takeIf { it.isStrictImdbId() }
            ?: canonicalIdentity.providerIds.imdb?.takeIf { it.isStrictImdbId() }
            ?: return null
        return StreamFetchIdentity(
            contentId = canonicalIdentity.providerIds.tmdb?.let { "tmdb:$it" }
                ?: canonicalIdentity.providerIds.imdb
                ?: imdbId,
            videoId = imdbId,
            idScheme = StreamIdScheme.IMDB_MOVIE,
            confidence = IdentityConfidence.HIGH,
            trace = listOf("resolved imdb movie stream id from sidecar $imdbId")
        )
    }
}

private fun String.isStrictImdbId(): Boolean =
    matches(Regex("^tt\\d+$"))
```

- [ ] **Step 4: Run tests to verify they pass**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.data.repository.StreamFetchIdentityResolverTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/StreamFetchIdentityResolver.kt app/src/test/java/com/nexio/tv/data/repository/StreamFetchIdentityResolverTest.kt
git commit -m "feat: resolve continue watching stream fetch identity"
```

## Task 4: Continue Watching Identity Resolver

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingIdentityResolver.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingIdentityResolverTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.core.metadata.router.CanonicalStableIds
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.MetadataRequest
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.core.metadata.router.SidecarStableIds
import com.nexio.tv.core.metadata.router.SourceStableIds
import com.nexio.tv.core.metadata.router.StableIdBundle
import com.nexio.tv.core.metadata.router.StableIdEvidence
import com.nexio.tv.core.metadata.router.StableIdResolutionTrigger
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.WatchProgress
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ContinueWatchingIdentityResolverTest {
    @Test
    fun `tvdb local and imdb trakt citadel resolve to same canonical key`() = runTest {
        val facade = mockk<MetadataRouterFacade>()
        coEvery {
            facade.resolveStableIdBundle(any<MetadataRequest>(), StableIdResolutionTrigger.CONTINUE_WATCHING, any())
        } returns StableIdBundle(
            itemKey = "cw-test",
            itemType = ContentType.SERIES,
            canonical = CanonicalStableIds(tvdbSeriesId = "393268"),
            sidecars = SidecarStableIds(imdbId = "tt9794044"),
            source = SourceStableIds(ProviderId.TVDB, "tvdb:393268", null, ProviderIds(tvdb = "393268", imdb = "tt9794044")),
            evidence = listOf(StableIdEvidence("test", "tvdb:393268", networkExecuted = false, resultId = "393268")),
            resolvedAtMs = 1L
        )
        val resolver = ContinueWatchingIdentityResolver(
            metadataRouterFacade = facade,
            streamFetchIdentityResolver = StreamFetchIdentityResolver()
        )

        val local = resolver.resolve(RawContinueWatchingInput(1, citadelLocal(), "nl"))
        val trakt = resolver.resolve(RawContinueWatchingInput(1, citadelTrakt(), "nl"))

        assertEquals(local.canonicalKey.stableKey(), trakt.canonicalKey.stableKey())
        assertEquals("profile:1:series:tvdb:393268:s2e1", local.canonicalKey.stableKey())
        assertEquals("tt9794044:2:1", local.streamFetchIdentity?.videoId)
        assertEquals("tt9794044:2:1", trakt.streamFetchIdentity?.videoId)
    }

    private fun citadelLocal(): WatchProgress = WatchProgress(
        contentId = "tvdb:393268",
        contentType = "series",
        name = "Citadel",
        poster = null,
        backdrop = null,
        logo = null,
        videoId = "tvdb:393268:2:1",
        season = 2,
        episode = 1,
        episodeTitle = "Baked Alaskas",
        position = 65_066L,
        duration = 2_958_656L,
        lastWatched = 1_778_171_360_859L
    )

    private fun citadelTrakt(): WatchProgress = citadelLocal().copy(
        contentId = "tt9794044",
        videoId = "tt9794044:2:1",
        position = 0L,
        duration = 0L,
        progressPercent = 2.19917f,
        source = WatchProgress.SOURCE_TRAKT_PLAYBACK,
        traktShowId = 171028,
        traktEpisodeId = 13018336,
        traktPlaybackId = 1748554661L,
        lastWatched = 1_778_166_295_000L
    )
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.data.repository.ContinueWatchingIdentityResolverTest
```

Expected: FAIL with unresolved reference `ContinueWatchingIdentityResolver`.

- [ ] **Step 3: Add resolver**

Create `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingIdentityResolver.kt`:

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.core.metadata.router.MetadataDepth
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.MetadataRequest
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.core.metadata.router.MetadataSourceContext
import com.nexio.tv.core.metadata.router.StableIdBundle
import com.nexio.tv.core.metadata.router.StableIdResolutionTrigger
import com.nexio.tv.domain.model.ContentIdentity
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContinueWatchingIdentityResolver @Inject constructor(
    private val metadataRouterFacade: MetadataRouterFacade,
    private val streamFetchIdentityResolver: StreamFetchIdentityResolver
) {
    suspend fun resolve(input: RawContinueWatchingInput): ContinueWatchingIdentityResolution {
        val progress = input.progress
        val contentType = ContentType.fromString(progress.contentType)
        val mediaKind = if (contentType == ContentType.MOVIE) MetadataMediaKind.MOVIE else MetadataMediaKind.SERIES
        val observedIds = observedIds(progress)
        val request = MetadataRequest(
            contentId = progress.contentId,
            contentType = contentType,
            sourceContext = MetadataSourceContext(
                itemType = progress.contentType,
                previewStableIds = observedIds
            ),
            language = input.languageTag,
            seasonNumber = progress.season,
            depth = MetadataDepth.DETAIL_CORE
        )
        val bundle = metadataRouterFacade.resolveStableIdBundle(
            request = request,
            trigger = StableIdResolutionTrigger.CONTINUE_WATCHING,
            itemKey = ContinueWatchingItemKeys.legacyParentKey(progress.contentType, progress.contentId)
        )
        val displayIdentity = bundle.toContentIdentity(mediaKind, progress)
        val streamFetchIdentity = if (progress.season != null && progress.episode != null) {
            streamFetchIdentityResolver.resolveForEpisode(
                canonicalIdentity = displayIdentity,
                knownIds = displayIdentity.providerIds,
                season = progress.season,
                episode = progress.episode,
                sourceContext = StreamSourceContext(mediaKind = mediaKind, resumeVideoId = progress.videoId)
            )
        } else {
            streamFetchIdentityResolver.resolveForMovie(
                canonicalIdentity = displayIdentity,
                knownIds = displayIdentity.providerIds,
                sourceContext = StreamSourceContext(mediaKind = mediaKind, resumeVideoId = progress.videoId)
            )
        }
        val canonicalKey = ContinueWatchingCanonicalKey(
            mediaKind = mediaKind,
            canonicalParent = displayIdentity,
            season = progress.season,
            episode = progress.episode,
            episodeCoordinateProvider = displayIdentity.canonicalProvider,
            profileId = input.profileId
        )
        return ContinueWatchingIdentityResolution(
            canonicalKey = canonicalKey,
            displayIdentity = displayIdentity,
            streamFetchIdentity = streamFetchIdentity,
            trackingIdentity = progress.toTrackingIdentity(),
            confidence = if (streamFetchIdentity != null) IdentityConfidence.HIGH else IdentityConfidence.MEDIUM,
            warnings = if (streamFetchIdentity == null && mediaKind == MetadataMediaKind.SERIES) {
                listOf("series stream fetch identity unresolved for ${progress.contentId}")
            } else {
                emptyList()
            }
        )
    }

    private fun StableIdBundle.toContentIdentity(
        mediaKind: MetadataMediaKind,
        progress: com.nexio.tv.domain.model.WatchProgress
    ): ContentIdentity {
        val providerIds = ProviderIds(
            imdb = sidecars.imdbId ?: observedIds(progress).imdb,
            tmdb = canonical.tmdbMovieId ?: observedIds(progress).tmdb,
            tvdb = canonical.tvdbSeriesId ?: observedIds(progress).tvdb,
            kitsu = canonical.kitsuAnimeId ?: observedIds(progress).kitsu,
            trakt = progress.traktShowId?.toString(),
            simkl = observedIds(progress).simkl
        )
        val canonicalProvider = when {
            mediaKind == MetadataMediaKind.MOVIE && providerIds.tmdb != null -> ProviderId.TMDB
            providerIds.tvdb != null -> ProviderId.TVDB
            providerIds.kitsu != null -> ProviderId.KITSU
            providerIds.imdb != null -> ProviderId.IMDB
            else -> null
        }
        val canonicalId = when (canonicalProvider) {
            ProviderId.TMDB -> providerIds.tmdb
            ProviderId.TVDB -> providerIds.tvdb
            ProviderId.KITSU -> providerIds.kitsu
            ProviderId.IMDB -> providerIds.imdb
            else -> providerIds.imdb ?: progress.contentId
        }
        return ContentIdentity(canonicalProvider, canonicalId, providerIds)
    }

    private fun observedIds(progress: com.nexio.tv.domain.model.WatchProgress): ProviderIds {
        val contentId = progress.contentId.trim()
        val videoId = progress.videoId.trim()
        return ProviderIds(
            imdb = listOf(contentId, videoId).firstNotNullOfOrNull { it.extractImdbId() },
            tmdb = listOf(contentId, videoId).firstNotNullOfOrNull { it.extractNumericProviderId("tmdb") },
            tvdb = listOf(contentId, videoId).firstNotNullOfOrNull { it.extractNumericProviderId("tvdb") },
            trakt = progress.traktShowId?.toString()
        )
    }
}

private fun String.extractImdbId(): String? {
    val raw = substringBefore(":").trim()
    return raw.takeIf { it.matches(Regex("^tt\\d+$")) }
}

private fun String.extractNumericProviderId(prefix: String): String? {
    val value = trim()
    if (!value.startsWith("$prefix:", ignoreCase = true)) return null
    return value.substringAfter(':').substringBefore(':').takeIf { it.matches(Regex("^\\d+$")) }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.data.repository.ContinueWatchingIdentityResolverTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingIdentityResolver.kt app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingIdentityResolverTest.kt
git commit -m "feat: resolve continue watching canonical identity"
```

## Task 5: Extend ContinueWatchingRecord Without Breaking Existing Tests

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingRecord.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingRecordTest.kt`

- [ ] **Step 1: Add failing tests for canonical identity and stream id**

Append to `ContinueWatchingRecordTest`:

```kotlin
    @Test
    fun `identity key prefers canonical key when present`() {
        val canonicalKey = ContinueWatchingCanonicalKey(
            mediaKind = com.nexio.tv.core.metadata.router.MetadataMediaKind.SERIES,
            canonicalParent = com.nexio.tv.domain.model.ContentIdentity(
                canonicalProvider = com.nexio.tv.domain.model.ProviderId.TVDB,
                canonicalId = "393268",
                providerIds = com.nexio.tv.domain.model.ProviderIds(tvdb = "393268", imdb = "tt9794044")
            ),
            season = 2,
            episode = 1,
            episodeCoordinateProvider = com.nexio.tv.domain.model.ProviderId.TVDB,
            profileId = 1
        )
        val record = ContinueWatchingRecord(
            profileId = 1,
            parentId = "tvdb:393268",
            contentId = "tvdb:393268:s2e1",
            provider = TrackingProvider.TRAKT,
            routingVersion = 4,
            positionMs = 65_066L,
            durationMs = 2_958_656L,
            episodeContext = ContinueWatchingRecord.EpisodeContext(2, 1),
            clickTimeDisplayMetadata = null,
            source = ContinueWatchingRecord.Source.LOCAL,
            updatedAt = 1_778_171_360_859L,
            canonicalKey = canonicalKey,
            resumeIdentity = ResumeIdentity(
                source = ContinueWatchingSource.LOCAL,
                contentId = "tvdb:393268",
                videoId = "tvdb:393268:2:1",
                season = 2,
                episode = 1,
                positionMs = 65_066L,
                durationMs = 2_958_656L,
                progressPercent = null,
                lastWatchedMs = 1_778_171_360_859L
            ),
            streamFetchIdentity = StreamFetchIdentity(
                contentId = "tvdb:393268",
                videoId = "tt9794044:2:1",
                idScheme = StreamIdScheme.IMDB_EPISODE,
                confidence = IdentityConfidence.HIGH,
                trace = listOf("resolved imdb episode stream id from sidecar tt9794044")
            ),
            languageTag = "nl"
        )

        assertEquals("profile:1:series:tvdb:393268:s2e1", record.identityKey())
        assertEquals("tt9794044:2:1", record.streamFetchIdentity?.videoId)
        assertEquals("tvdb:393268:2:1", record.resumeIdentity?.videoId)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.data.repository.ContinueWatchingRecordTest
```

Expected: FAIL because `ContinueWatchingRecord` has no `canonicalKey`, `resumeIdentity`, `streamFetchIdentity`, or `languageTag`.

- [ ] **Step 3: Extend record model with defaulted fields**

Modify `ContinueWatchingRecord` constructor by adding these fields after `updatedAt`:

```kotlin
    val canonicalKey: ContinueWatchingCanonicalKey? = null,
    val resumeIdentity: ResumeIdentity? = null,
    val displayIdentity: com.nexio.tv.domain.model.ContentIdentity? = null,
    val streamFetchIdentity: StreamFetchIdentity? = null,
    val trackingIdentity: TrackingIdentity? = null,
    val languageTag: String? = null
```

Replace `identityKey()` with:

```kotlin
    fun identityKey(): String {
        canonicalKey?.let { return it.stableKey() }
        val episodeKey = episodeContext?.let { "s${it.season}e${it.number}" }
        return if (episodeKey != null) {
            "profile:$profileId:continue-watching:$parentId:$episodeKey"
        } else {
            "profile:$profileId:continue-watching:$parentId"
        }
    }
```

Add these `init` checks below the existing checks:

```kotlin
        require(languageTag == null || languageTag.isNotBlank()) { "ContinueWatchingRecord.languageTag must not be blank when present" }
        require(canonicalKey == null || canonicalKey.profileId == profileId) { "canonicalKey.profileId must match record profileId" }
```

- [ ] **Step 4: Run tests to verify they pass**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.data.repository.ContinueWatchingRecordTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingRecord.kt app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingRecordTest.kt
git commit -m "feat: store canonical identities on continue watching records"
```

## Task 6: Canonical Merge

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingMerger.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingMergerTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ContentIdentity
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import org.junit.Assert.assertEquals
import org.junit.Test

class ContinueWatchingMergerTest {
    @Test
    fun `local tvdb and trakt imdb same episode merge into one record`() {
        val local = record(
            source = ContinueWatchingRecord.Source.LOCAL,
            positionMs = 65_066L,
            updatedAt = 200L,
            resumeContentId = "tvdb:393268",
            resumeVideoId = "tvdb:393268:2:1",
            traktEpisodeId = null
        )
        val trakt = record(
            source = ContinueWatchingRecord.Source.REMOTE,
            positionMs = 0L,
            updatedAt = 100L,
            resumeContentId = "tt9794044",
            resumeVideoId = "tt9794044:2:1",
            traktEpisodeId = 13018336
        )

        val merged = ContinueWatchingMerger.merge(listOf(trakt, local))

        assertEquals(1, merged.size)
        assertEquals(65_066L, merged.single().positionMs)
        assertEquals(13018336, merged.single().trackingIdentity?.traktEpisodeId)
        assertEquals("tt9794044:2:1", merged.single().streamFetchIdentity?.videoId)
    }

    private fun record(
        source: ContinueWatchingRecord.Source,
        positionMs: Long,
        updatedAt: Long,
        resumeContentId: String,
        resumeVideoId: String,
        traktEpisodeId: Int?
    ): ContinueWatchingRecord {
        val canonicalKey = ContinueWatchingCanonicalKey(
            mediaKind = MetadataMediaKind.SERIES,
            canonicalParent = ContentIdentity(
                canonicalProvider = ProviderId.TVDB,
                canonicalId = "393268",
                providerIds = ProviderIds(tvdb = "393268", imdb = "tt9794044")
            ),
            season = 2,
            episode = 1,
            episodeCoordinateProvider = ProviderId.TVDB,
            profileId = 1
        )
        return ContinueWatchingRecord(
            profileId = 1,
            parentId = "tvdb:393268",
            contentId = "tvdb:393268:s2e1",
            provider = com.nexio.tv.domain.model.TrackingProvider.TRAKT,
            routingVersion = ContinueWatchingMetadataSnapshot.CURRENT_ROUTING_VERSION,
            positionMs = positionMs,
            durationMs = 2_958_656L,
            episodeContext = ContinueWatchingRecord.EpisodeContext(2, 1),
            clickTimeDisplayMetadata = null,
            source = source,
            updatedAt = updatedAt,
            canonicalKey = canonicalKey,
            resumeIdentity = ResumeIdentity(
                source = if (source == ContinueWatchingRecord.Source.LOCAL) ContinueWatchingSource.LOCAL else ContinueWatchingSource.TRAKT_PLAYBACK,
                contentId = resumeContentId,
                videoId = resumeVideoId,
                season = 2,
                episode = 1,
                positionMs = positionMs,
                durationMs = 2_958_656L,
                progressPercent = null,
                lastWatchedMs = updatedAt
            ),
            streamFetchIdentity = StreamFetchIdentity(
                contentId = "tvdb:393268",
                videoId = "tt9794044:2:1",
                idScheme = StreamIdScheme.IMDB_EPISODE,
                confidence = IdentityConfidence.HIGH,
                trace = listOf("resolved imdb episode stream id from sidecar tt9794044")
            ),
            trackingIdentity = traktEpisodeId?.let { TrackingIdentity(traktEpisodeId = it) },
            languageTag = "nl"
        )
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.data.repository.ContinueWatchingMergerTest
```

Expected: FAIL with unresolved reference `ContinueWatchingMerger`.

- [ ] **Step 3: Add merger**

Create `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingMerger.kt`:

```kotlin
package com.nexio.tv.data.repository

object ContinueWatchingMerger {
    fun merge(records: List<ContinueWatchingRecord>): List<ContinueWatchingRecord> {
        val byKey = linkedMapOf<String, ContinueWatchingRecord>()
        records.sortedByDescending { it.updatedAt }.forEach { candidate ->
            val key = candidate.identityKey()
            val existing = byKey[key]
            byKey[key] = if (existing == null) {
                candidate
            } else {
                mergeRecords(existing, candidate)
            }
        }
        return byKey.values.sortedByDescending { it.updatedAt }
    }

    private fun mergeRecords(
        existing: ContinueWatchingRecord,
        candidate: ContinueWatchingRecord
    ): ContinueWatchingRecord {
        val progressWinner = chooseProgressWinner(existing, candidate)
        val trackingWinner = existing.trackingIdentity.mergeTracking(candidate.trackingIdentity)
        val streamWinner = chooseStreamIdentity(existing.streamFetchIdentity, candidate.streamFetchIdentity)
        val clickTimeWinner = existing.clickTimeDisplayMetadata ?: candidate.clickTimeDisplayMetadata
        return progressWinner.copy(
            trackingIdentity = trackingWinner,
            streamFetchIdentity = streamWinner,
            clickTimeDisplayMetadata = clickTimeWinner,
            displayIdentity = progressWinner.displayIdentity ?: existing.displayIdentity ?: candidate.displayIdentity,
            languageTag = progressWinner.languageTag ?: existing.languageTag ?: candidate.languageTag
        )
    }

    private fun chooseProgressWinner(
        existing: ContinueWatchingRecord,
        candidate: ContinueWatchingRecord
    ): ContinueWatchingRecord {
        val existingPosition = existing.positionMs
        val candidatePosition = candidate.positionMs
        if (candidate.updatedAt > existing.updatedAt && candidatePosition > 0L) return candidate
        if (existingPosition <= 0L && candidatePosition > 0L) return candidate
        if (candidatePosition > existingPosition && candidate.updatedAt >= existing.updatedAt - 1_000L) return candidate
        return existing
    }

    private fun chooseStreamIdentity(
        existing: StreamFetchIdentity?,
        candidate: StreamFetchIdentity?
    ): StreamFetchIdentity? {
        if (existing == null) return candidate
        if (candidate == null) return existing
        return if (candidate.confidence.ordinal < existing.confidence.ordinal) candidate else existing
    }
}

private fun TrackingIdentity?.mergeTracking(other: TrackingIdentity?): TrackingIdentity? {
    if (this == null) return other
    if (other == null) return this
    return TrackingIdentity(
        traktShowId = traktShowId ?: other.traktShowId,
        traktEpisodeId = traktEpisodeId ?: other.traktEpisodeId,
        traktPlaybackId = traktPlaybackId ?: other.traktPlaybackId,
        traktMovieId = traktMovieId ?: other.traktMovieId,
        providerIds = providerIds.copy(
            imdb = providerIds.imdb ?: other.providerIds.imdb,
            tmdb = providerIds.tmdb ?: other.providerIds.tmdb,
            tvdb = providerIds.tvdb ?: other.providerIds.tvdb,
            trakt = providerIds.trakt ?: other.providerIds.trakt,
            simkl = providerIds.simkl ?: other.providerIds.simkl,
            kitsu = providerIds.kitsu ?: other.providerIds.kitsu
        )
    )
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.data.repository.ContinueWatchingMergerTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingMerger.kt app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingMergerTest.kt
git commit -m "feat: merge continue watching records by canonical key"
```

## Task 7: Snapshot Canonicalization And Legacy Migration

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotServiceMutationTest.kt`

- [ ] **Step 1: Add failing snapshot migration test**

Append to `ContinueWatchingSnapshotServiceMutationTest`:

```kotlin
    @Test
    fun `legacy citadel tvdb and imdb rows migrate into one canonical record`() = runTest {
        val service = buildServiceWithIdentityResolver(
            identityResolver = fakeCitadelIdentityResolver()
        )
        val method = ContinueWatchingSnapshotService::class.java.getDeclaredMethod(
            "resolveCanonicalResumeRecords",
            Int::class.javaPrimitiveType,
            String::class.java,
            List::class.java
        )
        method.isAccessible = true

        val records = method.invoke(
            service,
            1,
            "nl",
            listOf(
                resume("tvdb:393268", "tvdb:393268:2:1", season = 2, episode = 1, lastWatched = 200L),
                resume("tt9794044", "tt9794044:2:1", season = 2, episode = 1, lastWatched = 100L, source = WatchProgress.SOURCE_TRAKT_PLAYBACK)
            )
        ) as List<*>

        assertEquals(1, records.size)
        val record = records.single() as ContinueWatchingRecord
        assertEquals("profile:1:series:tvdb:393268:s2e1", record.identityKey())
        assertEquals("tt9794044:2:1", record.streamFetchIdentity?.videoId)
    }
```

Add helpers inside the test class:

```kotlin
    private fun buildServiceWithIdentityResolver(
        identityResolver: ContinueWatchingIdentityResolver
    ): ContinueWatchingSnapshotService {
        val constructor = ContinueWatchingSnapshotService::class.java.declaredConstructors
            .first { candidate ->
                candidate.parameterTypes.any { it == ContinueWatchingIdentityResolver::class.java }
            }
        constructor.isAccessible = true
        val args = constructor.parameterTypes.map { type ->
            when (type) {
                WatchProgressRepository::class.java -> mockk<WatchProgressRepository>(relaxed = true) {
                    every { observeProgress(any()) } returns flowOf(emptyList())
                }
                TrackingProgressService::class.java -> mockk<TrackingProgressService>(relaxed = true) {
                    every { observeRemoteSnapshotLoaded() } returns flowOf(false)
                    every { observeContinueWatchingNextUp() } returns flowOf(emptyList())
                    every { observeSyntheticContinueWatchingNextUp() } returns flowOf(emptyList())
                }
                TrackingProviderStateService::class.java -> mockk<TrackingProviderStateService>(relaxed = true) {
                    every { state } returns flowOf(EffectiveTrackingProviderState(traktAuthenticated = true))
                }
                TraktSettingsDataStore::class.java -> mockk<TraktSettingsDataStore>(relaxed = true) {
                    every { dismissedNextUpKeys } returns flowOf(emptySet())
                }
                MetadataDiskCacheStore::class.java -> mockk<MetadataDiskCacheStore>(relaxed = true)
                ContinueWatchingSnapshotStore::class.java -> mockk<ContinueWatchingSnapshotStore>(relaxed = true) {
                    every { read(any()) } returns null
                }
                ContinueWatchingIdentityResolver::class.java -> identityResolver
                else -> null
            }
        }.toTypedArray()
        return constructor.newInstance(*args) as ContinueWatchingSnapshotService
    }

    private fun fakeCitadelIdentityResolver(): ContinueWatchingIdentityResolver =
        mockk {
            coEvery { resolve(any()) } answers {
                val input = firstArg<RawContinueWatchingInput>()
                val progress = input.progress
                val contentIdentity = com.nexio.tv.domain.model.ContentIdentity(
                    canonicalProvider = com.nexio.tv.domain.model.ProviderId.TVDB,
                    canonicalId = "393268",
                    providerIds = com.nexio.tv.domain.model.ProviderIds(tvdb = "393268", imdb = "tt9794044")
                )
                ContinueWatchingIdentityResolution(
                    canonicalKey = ContinueWatchingCanonicalKey(
                        mediaKind = com.nexio.tv.core.metadata.router.MetadataMediaKind.SERIES,
                        canonicalParent = contentIdentity,
                        season = progress.season,
                        episode = progress.episode,
                        episodeCoordinateProvider = com.nexio.tv.domain.model.ProviderId.TVDB,
                        profileId = input.profileId
                    ),
                    displayIdentity = contentIdentity,
                    streamFetchIdentity = StreamFetchIdentity(
                        contentId = "tvdb:393268",
                        videoId = "tt9794044:2:1",
                        idScheme = StreamIdScheme.IMDB_EPISODE,
                        confidence = IdentityConfidence.HIGH,
                        trace = listOf("test identity")
                    ),
                    trackingIdentity = progress.toTrackingIdentity(),
                    confidence = IdentityConfidence.HIGH,
                    warnings = emptyList()
                )
            }
        }
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.data.repository.ContinueWatchingSnapshotServiceMutationTest
```

Expected: FAIL because `ContinueWatchingSnapshotService` does not accept `ContinueWatchingIdentityResolver` and has no `resolveCanonicalResumeRecords`.

- [ ] **Step 3: Add resolver dependency**

Modify `ContinueWatchingSnapshotService` constructor by adding this parameter at the end so old reflective tests can still pass when they do not provide it:

```kotlin
    private val continueWatchingIdentityResolver: ContinueWatchingIdentityResolver? = null,
    private val profileBoundary: com.nexio.tv.core.profile.ProfileBoundary? = null
```

- [ ] **Step 4: Add canonical record resolver**

Add this internal method inside `ContinueWatchingSnapshotService`:

```kotlin
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal suspend fun resolveCanonicalResumeRecords(
        profileId: Int,
        languageTag: String,
        progressItems: List<WatchProgress>
    ): List<ContinueWatchingRecord> {
        val resolver = continueWatchingIdentityResolver ?: return legacyResumeRecords(profileId, progressItems)
        val records = progressItems.mapNotNull { progress ->
            runCatching {
                val resolution = resolver.resolve(
                    RawContinueWatchingInput(
                        profileId = profileId,
                        progress = progress,
                        languageTag = languageTag
                    )
                )
                val episodeContext = if (progress.season != null && progress.episode != null) {
                    ContinueWatchingRecord.EpisodeContext(progress.season, progress.episode)
                } else {
                    null
                }
                ContinueWatchingRecord(
                    profileId = profileId,
                    parentId = ContinueWatchingItemKeys.parentKey(resolution.canonicalKey.mediaKind, resolution.displayIdentity),
                    contentId = if (episodeContext != null) {
                        ContinueWatchingItemKeys.episodeKey(
                            resolution.canonicalKey.mediaKind,
                            resolution.displayIdentity,
                            episodeContext.season,
                            episodeContext.number
                        )
                    } else {
                        ContinueWatchingItemKeys.parentKey(resolution.canonicalKey.mediaKind, resolution.displayIdentity)
                    },
                    provider = TrackingProvider.TRAKT,
                    routingVersion = ContinueWatchingMetadataSnapshot.CURRENT_ROUTING_VERSION,
                    positionMs = progress.position,
                    durationMs = progress.duration,
                    episodeContext = episodeContext,
                    clickTimeDisplayMetadata = null,
                    source = if (progress.source == WatchProgress.SOURCE_LOCAL) ContinueWatchingRecord.Source.LOCAL else ContinueWatchingRecord.Source.REMOTE,
                    updatedAt = progress.lastWatched.coerceAtLeast(1L),
                    canonicalKey = resolution.canonicalKey,
                    resumeIdentity = progress.toResumeIdentity(),
                    displayIdentity = resolution.displayIdentity,
                    streamFetchIdentity = resolution.streamFetchIdentity,
                    trackingIdentity = resolution.trackingIdentity,
                    languageTag = languageTag
                )
            }.getOrNull()
        }
        return ContinueWatchingMerger.merge(records)
    }

    private fun legacyResumeRecords(
        profileId: Int,
        progressItems: List<WatchProgress>
    ): List<ContinueWatchingRecord> =
        progressItems.map { progress ->
            val episodeContext = if (progress.season != null && progress.episode != null) {
                ContinueWatchingRecord.EpisodeContext(progress.season, progress.episode)
            } else {
                null
            }
            val itemKey = if (episodeContext != null) {
                "${progress.contentId}:s${episodeContext.season}e${episodeContext.number}"
            } else {
                progress.contentId
            }
            ContinueWatchingRecord(
                profileId = profileId,
                parentId = progress.contentId,
                contentId = itemKey,
                provider = TrackingProvider.TRAKT,
                routingVersion = ContinueWatchingMetadataSnapshot.CURRENT_ROUTING_VERSION,
                positionMs = progress.position,
                durationMs = progress.duration,
                episodeContext = episodeContext,
                clickTimeDisplayMetadata = null,
                source = ContinueWatchingRecord.Source.LOCAL,
                updatedAt = progress.lastWatched.coerceAtLeast(1L),
                resumeIdentity = progress.toResumeIdentity()
            )
        }
```

- [ ] **Step 5: Thread records into snapshot**

Modify `ContinueWatchingSnapshot` to add a new defaulted field:

```kotlin
    val records: List<ContinueWatchingRecord> = emptyList()
```

Modify `buildRawSnapshot` after `val resumeItems = selectResumeItemsForContinueWatching(allProgress)`:

```kotlin
        val canonicalRecords = runBlockingSafelyForSnapshot {
            resolveCanonicalResumeRecords(
                profileId = rawSnapshotState.value.profileId,
                languageTag = currentLanguageTagForSnapshot(),
                progressItems = resumeItems
            )
        }
```

Add helper methods inside the service:

```kotlin
    private fun currentLanguageTagForSnapshot(): String =
        profileBoundary?.currentLanguageTag()?.takeIf { it.isNotBlank() } ?: "en"

    private fun <T> runBlockingSafelyForSnapshot(block: suspend () -> T): T? =
        runCatching { kotlinx.coroutines.runBlocking { block() } }.getOrNull()
```

In the returned `ContinueWatchingSnapshot(...)`, add:

```kotlin
            records = canonicalRecords.orEmpty()
```

- [ ] **Step 6: Run tests to verify they pass**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.data.repository.ContinueWatchingSnapshotServiceMutationTest --tests com.nexio.tv.data.repository.ContinueWatchingRecordTest
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotServiceMutationTest.kt
git commit -m "feat: canonicalize continue watching snapshot records"
```

## Task 8: UI Model And Click Route Use Stream Fetch Identity

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeUiState.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeModels.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/navigation/NexioNavHost.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/navigation/ScreenStreamRouteTest.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatchingTest.kt`

- [ ] **Step 1: Add failing route test**

Append to `ScreenStreamRouteTest`:

```kotlin
    @Test
    fun `continue watching route can preserve resume video id while using imdb stream id`() {
        val route = Screen.Stream.createRoute(
            videoId = "tvdb:393268:2:1",
            streamVideoId = "tt9794044:2:1",
            contentType = "series",
            title = "Citadel",
            contentId = "tvdb:393268",
            contentName = "Citadel",
            season = 2,
            episode = 1,
            resumePositionMs = 65_066L,
            resumeDurationMs = 2_958_656L,
            resumeSource = "local"
        )

        val args = decodedStreamRouteArgs(route)
        assertEquals("tvdb:393268:2:1", args["videoId"])
        assertEquals("tt9794044:2:1", args["streamVideoId"])
        assertEquals("tvdb:393268", args["contentId"])
    }
```

- [ ] **Step 2: Add failing UI mapping test**

Append to `HomeViewModelContinueWatchingTest`:

```kotlin
    @Test
    fun `continue watching in progress item carries stream fetch video id separately from resume video id`() {
        val item = ContinueWatchingItem.InProgress(
            progress = WatchProgress(
                contentId = "tvdb:393268",
                contentType = "series",
                name = "Citadel",
                poster = null,
                backdrop = null,
                logo = null,
                videoId = "tvdb:393268:2:1",
                season = 2,
                episode = 1,
                episodeTitle = "Baked Alaskas",
                position = 65_066L,
                duration = 2_958_656L,
                lastWatched = 42L
            ),
            canonicalKey = "profile:1:series:tvdb:393268:s2e1",
            streamFetchVideoId = "tt9794044:2:1"
        )

        assertEquals("tvdb:393268:2:1", item.progress.videoId)
        assertEquals("tt9794044:2:1", item.streamFetchVideoId)
        assertEquals("profile:1:series:tvdb:393268:s2e1", item.canonicalKey)
    }
```

- [ ] **Step 3: Run tests to verify they fail**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.ui.navigation.ScreenStreamRouteTest --tests com.nexio.tv.ui.screens.home.HomeViewModelContinueWatchingTest
```

Expected: FAIL because `ContinueWatchingItem.InProgress` has no `canonicalKey` or `streamFetchVideoId`.

- [ ] **Step 4: Add fields to UI models**

Modify `ContinueWatchingItem.InProgress` in `HomeUiState.kt`:

```kotlin
    data class InProgress(
        val progress: WatchProgress,
        val displayMetadata: HomeDisplayMetadata? = null,
        val episodeDescription: String? = null,
        val episodeThumbnail: String? = null,
        val episodeImdbRating: Float? = null,
        val genres: List<String> = emptyList(),
        val releaseInfo: String? = null,
        val canonicalKey: String? = null,
        val streamFetchVideoId: String? = null
    ) : ContinueWatchingItem()
```

Modify `NextUpInfo` by adding fields after `releaseInfo`:

```kotlin
    val canonicalKey: String? = null,
    val streamFetchVideoId: String? = null
```

- [ ] **Step 5: Use canonical key for UI item key**

Modify `continueWatchingItemKey` in `ModernHomeModels.kt`:

```kotlin
internal fun continueWatchingItemKey(item: ContinueWatchingItem): String {
    return when (item) {
        is ContinueWatchingItem.InProgress ->
            item.canonicalKey ?: "cw_inprogress_${item.progress.contentId}_${item.progress.videoId}_${item.progress.season ?: -1}_${item.progress.episode ?: -1}"
        is ContinueWatchingItem.NextUp ->
            item.info.canonicalKey ?: "cw_nextup_${item.info.contentId}_${item.info.videoId}_${item.info.season}_${item.info.episode}"
    }
}
```

- [ ] **Step 6: Pass streamVideoId from CW route**

Modify `buildContinueWatchingStreamRoute` in `NexioNavHost.kt` for `InProgress`:

```kotlin
            streamVideoId = item.streamFetchVideoId,
```

Place it inside the existing `Screen.Stream.createRoute(...)` call next to `videoId`.

Modify the `NextUp` route call in the same function:

```kotlin
            streamVideoId = item.info.streamFetchVideoId,
```

- [ ] **Step 7: Populate UI fields from canonical records**

Modify `WatchProgress.toContinueWatchingInProgress(...)` in `HomeViewModelContinueWatching.kt` to accept an optional record map:

```kotlin
private fun WatchProgress.toContinueWatchingInProgress(
    displayMetadataByItemKey: Map<String, HomeDisplayMetadata>,
    recordByResumeKey: Map<String, ContinueWatchingRecord> = emptyMap()
): ContinueWatchingItem.InProgress {
    val legacyKey = ContinueWatchingItemKeys.legacyParentKey(contentType, contentId)
    val displayMetadata = displayMetadataByItemKey[legacyKey]
    val record = recordByResumeKey[resumeLookupKey()]
    return ContinueWatchingItem.InProgress(
        progress = this,
        displayMetadata = displayMetadata,
        episodeDescription = displayMetadata?.description,
        episodeImdbRating = displayMetadata?.imdbRating,
        genres = displayMetadata?.genres.orEmpty(),
        releaseInfo = displayMetadata?.releaseInfo,
        canonicalKey = record?.identityKey(),
        streamFetchVideoId = record?.streamFetchIdentity?.videoId
    )
}

private fun WatchProgress.resumeLookupKey(): String =
    listOf(contentId, videoId, season?.toString().orEmpty(), episode?.toString().orEmpty()).joinToString("|")
```

In `processContinueWatchingSnapshot`, before `val rawItems = timeline.map`, add:

```kotlin
    val recordByResumeKey = snapshot.records
        .mapNotNull { record ->
            val resume = record.resumeIdentity ?: return@mapNotNull null
            val key = listOf(
                resume.contentId,
                resume.videoId.orEmpty(),
                resume.season?.toString().orEmpty(),
                resume.episode?.toString().orEmpty()
            ).joinToString("|")
            key to record
        }
        .toMap()
```

Replace:

```kotlin
is ContinueWatchingTimelineRow.Resume -> row.value.toContinueWatchingInProgress(snapshot.displayMetadataByItemKey)
```

with:

```kotlin
is ContinueWatchingTimelineRow.Resume -> row.value.toContinueWatchingInProgress(snapshot.displayMetadataByItemKey, recordByResumeKey)
```

- [ ] **Step 8: Run tests to verify they pass**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.ui.navigation.ScreenStreamRouteTest --tests com.nexio.tv.ui.screens.home.HomeViewModelContinueWatchingTest
```

Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeUiState.kt app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeModels.kt app/src/main/java/com/nexio/tv/ui/navigation/NexioNavHost.kt app/src/test/java/com/nexio/tv/ui/navigation/ScreenStreamRouteTest.kt app/src/test/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatchingTest.kt
git commit -m "fix: route continue watching playback with stream fetch identity"
```

## Task 9: Display Resolver Uses Shared Localized Surface

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingDisplayResolver.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingDisplayResolverTest.kt`

- [ ] **Step 1: Write failing display tests**

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.core.metadata.router.MetadataDecisionReason
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.domain.model.ContentIdentity
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.ResolvedDisplayFields
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.domain.model.TitleRating
import com.nexio.tv.domain.model.TrailerDisplayState
import com.nexio.tv.domain.model.HydrationState
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ContinueWatchingDisplayResolverTest {
    @Test
    fun `resolved display surface wins over stale english persisted fallback`() = runTest {
        val surfaceRepository = mockk<ResolvedDisplaySurfaceRepository>()
        coEvery { surfaceRepository.getSnapshot(ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY, 1) } returns listOf(
            displayItem("profile:1:series:tvdb:393268:s2e1", "Acht jaar geleden is Citadel vernietigd.")
        )
        val resolver = ContinueWatchingDisplayResolver(surfaceRepository)
        val record = record()

        val display = resolver.resolveDisplay(
            record = record,
            languageTag = "nl",
            profileId = 1,
            routeSnapshot = null,
            persistedFallback = HomeDisplayMetadata(description = "Eight years ago, Citadel was destroyed.")
        )

        assertEquals("Acht jaar geleden is Citadel vernietigd.", display.description)
    }

    @Test
    fun `localized route metadata wins when resolved surface has no item`() = runTest {
        val surfaceRepository = mockk<ResolvedDisplaySurfaceRepository>()
        coEvery { surfaceRepository.getSnapshot(ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY, 1) } returns emptyList()
        val resolver = ContinueWatchingDisplayResolver(surfaceRepository)

        val display = resolver.resolveDisplay(
            record = record(),
            languageTag = "nl",
            profileId = 1,
            routeSnapshot = ContinueWatchingMetadataSnapshot(
                routingVersion = ContinueWatchingMetadataSnapshot.CURRENT_ROUTING_VERSION,
                parentId = "tvdb:393268",
                primaryProvider = MetadataPrimaryProvider.TVDB,
                decisionReason = MetadataDecisionReason.ITEM_TYPE_SERIES,
                clickTimeDisplayMetadata = HomeDisplayMetadata(description = "Nederlandse routebeschrijving")
            ),
            persistedFallback = HomeDisplayMetadata(description = "English fallback")
        )

        assertEquals("Nederlandse routebeschrijving", display.description)
    }

    private fun record(): ContinueWatchingRecord {
        val identity = ContentIdentity(
            canonicalProvider = ProviderId.TVDB,
            canonicalId = "393268",
            providerIds = ProviderIds(tvdb = "393268", imdb = "tt9794044")
        )
        val key = ContinueWatchingCanonicalKey(
            mediaKind = MetadataMediaKind.SERIES,
            canonicalParent = identity,
            season = 2,
            episode = 1,
            episodeCoordinateProvider = ProviderId.TVDB,
            profileId = 1
        )
        return ContinueWatchingRecord(
            profileId = 1,
            parentId = "series:tvdb:393268",
            contentId = "series:tvdb:393268:s2e1",
            provider = com.nexio.tv.domain.model.TrackingProvider.TRAKT,
            routingVersion = ContinueWatchingMetadataSnapshot.CURRENT_ROUTING_VERSION,
            positionMs = 1L,
            durationMs = 2L,
            episodeContext = ContinueWatchingRecord.EpisodeContext(2, 1),
            clickTimeDisplayMetadata = null,
            source = ContinueWatchingRecord.Source.LOCAL,
            updatedAt = 1L,
            canonicalKey = key,
            displayIdentity = identity,
            languageTag = "nl"
        )
    }

    private fun displayItem(itemKey: String, overview: String): ResolvedDisplayItem =
        ResolvedDisplayItem(
            itemKey = itemKey,
            contentId = "tvdb:393268",
            parentId = "tvdb:393268",
            itemType = ContentType.SERIES,
            mediaKind = MetadataMediaKind.SERIES,
            canonicalProvider = "TVDB",
            canonicalId = "393268",
            imdbId = "tt9794044",
            stableIds = ProviderIds(tvdb = "393268", imdb = "tt9794044"),
            display = ResolvedDisplayFields(
                title = "Citadel",
                originalTitle = "Citadel",
                year = 2023,
                releaseDate = null,
                overview = overview,
                genres = listOf("Drama"),
                runtimeText = "42"
            ),
            artwork = com.nexio.tv.core.artwork.ArtworkBundle(),
            rating = null,
            trailer = TrailerDisplayState(),
            hydrationState = HydrationState.CANONICAL_READY,
            sourceTrace = emptyList(),
            updatedAtMs = 1L
        )
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.data.repository.ContinueWatchingDisplayResolverTest
```

Expected: FAIL with unresolved reference `ContinueWatchingDisplayResolver`.

- [ ] **Step 3: Add display resolver**

Create `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingDisplayResolver.kt`:

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.domain.model.mergeFallback
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContinueWatchingDisplayResolver @Inject constructor(
    private val resolvedDisplaySurfaceRepository: ResolvedDisplaySurfaceRepository
) {
    suspend fun resolveDisplay(
        record: ContinueWatchingRecord,
        languageTag: String,
        profileId: Int,
        routeSnapshot: ContinueWatchingMetadataSnapshot?,
        persistedFallback: HomeDisplayMetadata?
    ): HomeDisplayMetadata {
        val surfaceDisplay = resolvedDisplaySurfaceRepository
            .getSnapshot(ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY, profileId)
            .firstOrNull { item -> item.itemKey == record.identityKey() || item.matchesRecord(record) }
            ?.toHomeDisplayMetadata()
        return surfaceDisplay
            ?.mergeFallback(routeSnapshot?.clickTimeDisplayMetadata)
            ?.mergeFallback(persistedFallback)
            ?: routeSnapshot?.clickTimeDisplayMetadata?.mergeFallback(persistedFallback)
            ?: persistedFallback
            ?: HomeDisplayMetadata()
    }
}

private fun ResolvedDisplayItem.matchesRecord(record: ContinueWatchingRecord): Boolean {
    val ids = record.displayIdentity?.providerIds ?: return false
    return stableIds.tvdb != null && stableIds.tvdb == ids.tvdb ||
        stableIds.tmdb != null && stableIds.tmdb == ids.tmdb ||
        stableIds.imdb != null && stableIds.imdb == ids.imdb ||
        stableIds.kitsu != null && stableIds.kitsu == ids.kitsu
}

private fun ResolvedDisplayItem.toHomeDisplayMetadata(): HomeDisplayMetadata =
    HomeDisplayMetadata(
        title = display.title,
        description = display.overview,
        genres = display.genres,
        runtime = display.runtimeText,
        releaseInfo = display.releaseDate ?: display.year?.toString(),
        imdbRating = rating?.value?.toFloat(),
        ratingSource = rating?.source,
        poster = artwork.poster?.url,
        backdrop = artwork.backdrop?.url,
        logo = artwork.logo?.url,
        thumbnail = artwork.thumbnail?.url
    )
```

- [ ] **Step 4: Run tests to verify they pass**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.data.repository.ContinueWatchingDisplayResolverTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingDisplayResolver.kt app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingDisplayResolverTest.kt
git commit -m "feat: resolve continue watching display from shared surface"
```

## Task 10: Integrate Display Resolver Into Snapshot Hydration

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingMetadataRouterTest.kt`

- [ ] **Step 1: Add failing hydration test**

Append to `ContinueWatchingMetadataRouterTest`:

```kotlin
    @Test
    fun `display hydration uses localized route snapshot before stale persisted english fallback`() = runTest {
        val service = continueWatchingSnapshotService(metadataRouterFacade = mockk(relaxed = true))
        val method = ContinueWatchingSnapshotService::class.java.getDeclaredMethod(
            "hydrateSnapshotMetadata",
            ContinueWatchingSnapshot::class.java,
            Map::class.java
        )
        method.isAccessible = true
        val localized = ContinueWatchingMetadataSnapshot(
            routingVersion = ContinueWatchingMetadataSnapshot.CURRENT_ROUTING_VERSION,
            parentId = "tvdb:393268",
            primaryProvider = MetadataPrimaryProvider.TVDB,
            decisionReason = MetadataDecisionReason.ITEM_TYPE_SERIES,
            clickTimeDisplayMetadata = HomeDisplayMetadata(description = "Nederlandse Citadel beschrijving")
        )
        val snapshot = ContinueWatchingSnapshot(
            resumeItems = listOf(
                WatchProgress(
                    contentId = "tvdb:393268",
                    contentType = "series",
                    name = "Citadel",
                    poster = null,
                    backdrop = null,
                    logo = null,
                    videoId = "tvdb:393268:2:1",
                    season = 2,
                    episode = 1,
                    episodeTitle = "Baked Alaskas",
                    position = 1L,
                    duration = 2L,
                    lastWatched = 3L
                )
            ),
            metadataSnapshotsByItemKey = mapOf("series:tvdb:393268" to localized)
        )

        val hydrated = method.invoke(
            service,
            snapshot,
            mapOf("series:tvdb:393268" to HomeDisplayMetadata(description = "English fallback"))
        ) as ContinueWatchingSnapshot

        assertEquals(
            "Nederlandse Citadel beschrijving",
            hydrated.displayMetadataByItemKey.getValue("series:tvdb:393268").description
        )
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.data.repository.ContinueWatchingMetadataRouterTest
```

Expected: FAIL if `fetchHomeDisplayMetadata` returns canonical English or if route snapshot is not used for the map key.

- [ ] **Step 3: Add resolver dependency**

Modify `ContinueWatchingSnapshotService` constructor:

```kotlin
    private val continueWatchingDisplayResolver: ContinueWatchingDisplayResolver? = null
```

Place it after `continueWatchingIdentityResolver`.

- [ ] **Step 4: Prefer route snapshot when canonical fetch is missing or stale**

Modify `hydrateSnapshotMetadata` so the `merged` value is computed as:

```kotlin
            val routeSnapshot = routeUpgradedSnapshot.metadataSnapshotsByItemKey[itemKey]
            val record = routeUpgradedSnapshot.records.firstOrNull { it.parentId == itemKey || it.contentId == itemKey }
            val merged = if (continueWatchingDisplayResolver != null && record != null) {
                continueWatchingDisplayResolver.resolveDisplay(
                    record = record,
                    languageTag = routeUpgradedSnapshot.languageTagOrDefault(),
                    profileId = rawSnapshotState.value.profileId,
                    routeSnapshot = routeSnapshot,
                    persistedFallback = fallbackMetadata[itemKey]
                )
            } else {
                ContinueWatchingMetadataSnapshot.renderDisplayMetadata(
                    canonical = fetched,
                    clickTime = routeSnapshot?.clickTimeDisplayMetadata,
                    persistedFallback = fallbackMetadata[itemKey]
                )
            }
```

Add this extension near the bottom of the file:

```kotlin
private fun ContinueWatchingSnapshot.languageTagOrDefault(): String =
    records.firstNotNullOfOrNull { it.languageTag?.takeIf(String::isNotBlank) } ?: "en"
```

- [ ] **Step 5: Run tests to verify they pass**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.data.repository.ContinueWatchingMetadataRouterTest --tests com.nexio.tv.data.repository.ContinueWatchingDisplayResolverTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingMetadataRouterTest.kt
git commit -m "fix: derive continue watching display from localized metadata"
```

## Task 11: Stop Writing Series-Level Mirror Progress

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/local/WatchProgressPreferences.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/local/WatchProgressPreferencesProfileBoundaryTest.kt`

- [ ] **Step 1: Add failing tests for mirror removal**

Append to `WatchProgressPreferencesProfileBoundaryTest`:

```kotlin
    @Test
    fun `episode progress does not write series level mirror row`() = runTest {
        val preferences = WatchProgressPreferences(factory)
        val contentId = uniqueContentId("tvdb-series")

        preferences.saveProgress(
            1,
            sampleProgress(
                contentId = contentId,
                name = "episode only",
                season = 2,
                episode = 1
            )
        )

        assertEquals(null, preferences.getProgress(1, contentId).first())
        assertEquals("episode only", preferences.getEpisodeProgress(1, contentId, 2, 1).first()?.name)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.data.local.WatchProgressPreferencesProfileBoundaryTest
```

Expected: FAIL because `saveProgress` writes `map[seriesKey] = progress.copy(...)`.

- [ ] **Step 3: Remove mirror write**

In `WatchProgressPreferences.saveProgress`, delete this block:

```kotlin
            if (progress.season != null && progress.episode != null) {
                val seriesKey = progress.contentId
                val existingSeriesProgress = map[seriesKey]
                
                if (existingSeriesProgress == null || progress.lastWatched > existingSeriesProgress.lastWatched) {
                    map[seriesKey] = progress.copy(videoId = progress.videoId)
                }
            }
```

Replace it with:

```kotlin
            if (progress.season != null && progress.episode != null) {
                val seriesKey = progress.contentId
                if (seriesKey != key) {
                    map.remove(seriesKey)
                }
            }
```

- [ ] **Step 4: Run tests to verify they pass**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.data.local.WatchProgressPreferencesProfileBoundaryTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/local/WatchProgressPreferences.kt app/src/test/java/com/nexio/tv/data/local/WatchProgressPreferencesProfileBoundaryTest.kt
git commit -m "fix: stop writing series mirror progress rows"
```

## Task 12: NextUpDiscoveryEngine For Empty Provider Next-Up

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/repository/NextUpDiscoveryEngine.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/NextUpDiscoveryEngineTest.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt`

- [ ] **Step 1: Write failing next-up discovery tests**

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ContentIdentity
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.WatchProgress
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NextUpDiscoveryEngineTest {
    @Test
    fun `discovers next episode from local watched seed when provider next up is empty`() = runTest {
        val engine = NextUpDiscoveryEngine(
            episodeCatalog = fakeCatalog(
                NextUpEpisode(
                    season = 2,
                    episode = 2,
                    episodeTitle = "A New Spy",
                    firstAiredMs = 1_000L,
                    firstAired = "2026-05-01"
                )
            ),
            streamFetchIdentityResolver = StreamFetchIdentityResolver()
        )
        val seed = watchedSeed(lastWatched = 500L)

        val candidates = engine.discoverNextUp(
            profileId = 1,
            seeds = listOf(seed),
            policy = NextUpPolicy(nowMs = 2_000L, languageTag = "nl", includeUnaired = false)
        )

        assertEquals(1, candidates.size)
        assertEquals("profile:1:series:tvdb:393268:s2e2", candidates.single().canonicalKey.stableKey())
        assertEquals("tt9794044:2:2", candidates.single().streamFetchIdentity?.videoId)
        assertTrue(candidates.single().releaseAlert)
    }

    private fun watchedSeed(lastWatched: Long): WatchedSeed =
        WatchedSeed(
            progress = WatchProgress(
                contentId = "tvdb:393268",
                contentType = "series",
                name = "Citadel",
                poster = null,
                backdrop = null,
                logo = null,
                videoId = "tvdb:393268:2:1",
                season = 2,
                episode = 1,
                episodeTitle = "Baked Alaskas",
                position = 2_900_000L,
                duration = 2_958_656L,
                progressPercent = 90f,
                lastWatched = lastWatched
            ),
            displayIdentity = ContentIdentity(
                canonicalProvider = ProviderId.TVDB,
                canonicalId = "393268",
                providerIds = ProviderIds(tvdb = "393268", imdb = "tt9794044")
            )
        )

    private fun fakeCatalog(episode: NextUpEpisode): NextUpEpisodeCatalog =
        object : NextUpEpisodeCatalog {
            override suspend fun findEpisodeAfter(seed: WatchedSeed, languageTag: String): NextUpEpisode? = episode
        }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.data.repository.NextUpDiscoveryEngineTest
```

Expected: FAIL with unresolved references for `NextUpDiscoveryEngine`, `WatchedSeed`, `NextUpEpisode`, `NextUpPolicy`, and `NextUpEpisodeCatalog`.

- [ ] **Step 3: Add engine**

Create `app/src/main/java/com/nexio/tv/data/repository/NextUpDiscoveryEngine.kt`:

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ContentIdentity
import com.nexio.tv.domain.model.WatchProgress
import javax.inject.Inject
import javax.inject.Singleton

data class WatchedSeed(
    val progress: WatchProgress,
    val displayIdentity: ContentIdentity
)

data class NextUpPolicy(
    val nowMs: Long,
    val languageTag: String,
    val includeUnaired: Boolean,
    val releaseAlertWindowMs: Long = 60L * 24L * 60L * 60L * 1_000L
)

data class NextUpEpisode(
    val season: Int,
    val episode: Int,
    val episodeTitle: String?,
    val firstAiredMs: Long?,
    val firstAired: String?
)

data class NextUpCandidate(
    val canonicalKey: ContinueWatchingCanonicalKey,
    val displayIdentity: ContentIdentity,
    val streamFetchIdentity: StreamFetchIdentity?,
    val firstAiredMs: Long?,
    val firstAired: String?,
    val reason: NextUpReason,
    val releaseAlert: Boolean
)

enum class NextUpReason {
    PROVIDER_NEXT_UP,
    LOCAL_SEED_DISCOVERY,
    CACHED_SEED_DISCOVERY
}

interface NextUpEpisodeCatalog {
    suspend fun findEpisodeAfter(seed: WatchedSeed, languageTag: String): NextUpEpisode?
}

@Singleton
class NextUpDiscoveryEngine @Inject constructor(
    private val episodeCatalog: NextUpEpisodeCatalog,
    private val streamFetchIdentityResolver: StreamFetchIdentityResolver
) {
    suspend fun discoverNextUp(
        profileId: Int,
        seeds: List<WatchedSeed>,
        policy: NextUpPolicy
    ): List<NextUpCandidate> {
        return seeds.mapNotNull { seed ->
            val progress = seed.progress
            if (progress.season == null || progress.episode == null) return@mapNotNull null
            val next = episodeCatalog.findEpisodeAfter(seed, policy.languageTag) ?: return@mapNotNull null
            val firstAiredMs = next.firstAiredMs
            val hasAired = firstAiredMs == null || firstAiredMs <= policy.nowMs
            if (!hasAired && !policy.includeUnaired) return@mapNotNull null
            val canonicalKey = ContinueWatchingCanonicalKey(
                mediaKind = MetadataMediaKind.SERIES,
                canonicalParent = seed.displayIdentity,
                season = next.season,
                episode = next.episode,
                episodeCoordinateProvider = seed.displayIdentity.canonicalProvider,
                profileId = profileId
            )
            val streamFetchIdentity = streamFetchIdentityResolver.resolveForEpisode(
                canonicalIdentity = seed.displayIdentity,
                knownIds = seed.displayIdentity.providerIds,
                season = next.season,
                episode = next.episode,
                sourceContext = StreamSourceContext(MetadataMediaKind.SERIES, progress.videoId)
            )
            NextUpCandidate(
                canonicalKey = canonicalKey,
                displayIdentity = seed.displayIdentity,
                streamFetchIdentity = streamFetchIdentity,
                firstAiredMs = firstAiredMs,
                firstAired = next.firstAired,
                reason = NextUpReason.LOCAL_SEED_DISCOVERY,
                releaseAlert = firstAiredMs != null &&
                    firstAiredMs > progress.lastWatched &&
                    policy.nowMs - firstAiredMs in 0..policy.releaseAlertWindowMs
            )
        }.distinctBy { it.canonicalKey.stableKey() }
    }
}
```

- [ ] **Step 4: Add metadata-router episode catalog implementation**

Append to `NextUpDiscoveryEngine.kt`:

```kotlin
@Singleton
class MetadataRouterNextUpEpisodeCatalog @Inject constructor(
    private val metadataRouterFacade: com.nexio.tv.core.metadata.router.MetadataRouterFacade
) : NextUpEpisodeCatalog {
    override suspend fun findEpisodeAfter(seed: WatchedSeed, languageTag: String): NextUpEpisode? {
        val progress = seed.progress
        val season = progress.season ?: return null
        val episode = progress.episode ?: return null
        val decision = metadataRouterFacade.fetchTvEpisodeEnrichment(
            metadataRequest = com.nexio.tv.core.metadata.router.MetadataRequest(
                contentId = progress.contentId,
                contentType = com.nexio.tv.domain.model.ContentType.fromString(progress.contentType),
                sourceContext = com.nexio.tv.core.metadata.router.MetadataSourceContext(itemType = progress.contentType),
                language = languageTag,
                seasonNumber = season,
                depth = com.nexio.tv.core.metadata.router.MetadataDepth.SEASON
            ),
            tvRequest = com.nexio.tv.core.tvdb.TvMetadataRequest(
                contentId = progress.contentId,
                fallbackContentId = progress.videoId,
                contentType = com.nexio.tv.domain.model.ContentType.fromString(progress.contentType),
                language = languageTag,
                seasonNumbers = listOf(season, season + 1)
            )
        )
        return decision.value
            ?.keys
            ?.filter { (candidateSeason, candidateEpisode) ->
                candidateSeason > season || candidateSeason == season && candidateEpisode > episode
            }
            ?.sortedWith(compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second })
            ?.firstOrNull()
            ?.let { key ->
                val metadata = decision.value.getValue(key)
                NextUpEpisode(
                    season = key.first,
                    episode = key.second,
                    episodeTitle = metadata.title,
                    firstAiredMs = metadata.airDate?.let { runCatching { java.time.LocalDate.parse(it).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull() },
                    firstAired = metadata.airDate
                )
            }
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.data.repository.NextUpDiscoveryEngineTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/NextUpDiscoveryEngine.kt app/src/test/java/com/nexio/tv/data/repository/NextUpDiscoveryEngineTest.kt
git commit -m "feat: discover continue watching next up from watched seeds"
```

## Task 13: Wire NextUpDiscoveryEngine Into Snapshot Builder

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotServiceMutationTest.kt`

- [ ] **Step 1: Add failing fallback test**

Append to `ContinueWatchingSnapshotServiceMutationTest`:

```kotlin
    @Test
    fun `buildRawSnapshot includes discovered next up when provider next up is empty`() = runTest {
        val engine = mockk<NextUpDiscoveryEngine>()
        coEvery { engine.discoverNextUp(any(), any(), any()) } returns listOf(
            NextUpCandidate(
                canonicalKey = ContinueWatchingCanonicalKey(
                    mediaKind = com.nexio.tv.core.metadata.router.MetadataMediaKind.SERIES,
                    canonicalParent = com.nexio.tv.domain.model.ContentIdentity(
                        canonicalProvider = com.nexio.tv.domain.model.ProviderId.TVDB,
                        canonicalId = "393268",
                        providerIds = com.nexio.tv.domain.model.ProviderIds(tvdb = "393268", imdb = "tt9794044")
                    ),
                    season = 2,
                    episode = 2,
                    episodeCoordinateProvider = com.nexio.tv.domain.model.ProviderId.TVDB,
                    profileId = 1
                ),
                displayIdentity = com.nexio.tv.domain.model.ContentIdentity(
                    canonicalProvider = com.nexio.tv.domain.model.ProviderId.TVDB,
                    canonicalId = "393268",
                    providerIds = com.nexio.tv.domain.model.ProviderIds(tvdb = "393268", imdb = "tt9794044")
                ),
                streamFetchIdentity = StreamFetchIdentity(
                    contentId = "tvdb:393268",
                    videoId = "tt9794044:2:2",
                    idScheme = StreamIdScheme.IMDB_EPISODE,
                    confidence = IdentityConfidence.HIGH,
                    trace = listOf("test")
                ),
                firstAiredMs = 1L,
                firstAired = "2026-05-01",
                reason = NextUpReason.LOCAL_SEED_DISCOVERY,
                releaseAlert = true
            )
        )
        val service = buildServiceWithNextUpDiscovery(engine)

        val snapshot = invokeBuildRawSnapshot(
            service,
            allProgress = listOf(resume("tvdb:393268", "tvdb:393268:2:1", season = 2, episode = 1)),
            nextUpEntries = emptyList(),
            traktUpNextEntries = emptyList()
        )

        assertEquals(1, snapshot.traktUpNextItems.size)
        assertEquals("tt9794044:2:2", snapshot.traktUpNextItems.single().videoId)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.data.repository.ContinueWatchingSnapshotServiceMutationTest
```

Expected: FAIL because `ContinueWatchingSnapshotService` has no `NextUpDiscoveryEngine` dependency and does not convert discovered candidates into `TrackingNextUpEntry`.

- [ ] **Step 3: Add dependency and conversion**

Add constructor parameter:

```kotlin
    private val nextUpDiscoveryEngine: NextUpDiscoveryEngine? = null
```

Add helper:

```kotlin
    private suspend fun discoverFallbackNextUp(
        profileId: Int,
        languageTag: String,
        resumeRecords: List<ContinueWatchingRecord>,
        nowMs: Long
    ): List<TrackingNextUpEntry> {
        val engine = nextUpDiscoveryEngine ?: return emptyList()
        val seeds = resumeRecords.mapNotNull { record ->
            val resume = record.resumeIdentity ?: return@mapNotNull null
            val displayIdentity = record.displayIdentity ?: return@mapNotNull null
            val progress = WatchProgress(
                contentId = resume.contentId,
                contentType = if (resume.isEpisode) "series" else "movie",
                name = record.clickTimeDisplayMetadata?.clickTimeDisplayMetadata?.title ?: resume.contentId,
                poster = null,
                backdrop = null,
                logo = null,
                videoId = resume.videoId.orEmpty(),
                season = resume.season,
                episode = resume.episode,
                episodeTitle = null,
                position = resume.positionMs,
                duration = resume.durationMs ?: 0L,
                progressPercent = resume.progressPercent,
                lastWatched = resume.lastWatchedMs
            )
            WatchedSeed(progress = progress, displayIdentity = displayIdentity)
        }
        return engine.discoverNextUp(
            profileId = profileId,
            seeds = seeds,
            policy = NextUpPolicy(nowMs = nowMs, languageTag = languageTag, includeUnaired = false)
        ).map { candidate ->
            TrackingNextUpEntry(
                contentId = candidate.displayIdentity.providerIds.tvdb?.let { "tvdb:$it" }
                    ?: candidate.displayIdentity.providerIds.imdb
                    ?: candidate.canonicalKey.stableKey(),
                contentType = "series",
                name = candidate.displayIdentity.providerIds.imdb ?: candidate.canonicalKey.stableKey(),
                season = candidate.canonicalKey.season ?: 0,
                episode = candidate.canonicalKey.episode ?: 0,
                episodeTitle = null,
                videoId = candidate.streamFetchIdentity?.videoId ?: candidate.canonicalKey.stableKey(),
                firstAired = candidate.firstAired,
                firstAiredMs = candidate.firstAiredMs ?: 0L,
                activityAtMs = candidate.firstAiredMs ?: nowMs
            )
        }
    }
```

In `buildRawSnapshot`, after canonical records are computed, add:

```kotlin
        val fallbackNextUpItems = runBlockingSafelyForSnapshot {
            discoverFallbackNextUp(
                profileId = rawSnapshotState.value.profileId,
                languageTag = currentLanguageTagForSnapshot(),
                resumeRecords = canonicalRecords.orEmpty(),
                nowMs = nowMs
            )
        }.orEmpty()
```

Use `(nextUpEntries + fallbackNextUpItems)` in the `normalizedNextUpItems` source.

- [ ] **Step 4: Run tests to verify they pass**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.data.repository.ContinueWatchingSnapshotServiceMutationTest --tests com.nexio.tv.data.repository.NextUpDiscoveryEngineTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotServiceMutationTest.kt
git commit -m "feat: add fallback next up discovery to continue watching"
```

## Task 14: Trace Events

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/trace/TraceMetadataEvents.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingIdentityResolver.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingMerger.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingDisplayResolver.kt`
- Test: `app/src/test/java/com/nexio/tv/core/trace/TraceMetadataEventsTest.kt`

- [ ] **Step 1: Add failing trace tests**

Append to `TraceMetadataEventsTest`:

```kotlin
    @Test
    fun `continue watching merge trace includes canonical key and selected stream id`() {
        val sink = RecordingRuntimeTraceSink()
        val events = TraceMetadataEvents(sink = sink, sessionId = { "test-session" })

        events.continueWatchingMergeByCanonicalKey(
            canonicalKeyHash = "profile:1:series:tvdb:393268:s2e1",
            mergedSources = listOf("local", "trakt_playback"),
            inputIds = listOf("tvdb:393268", "tt9794044"),
            selectedStreamVideoId = "tt9794044:2:1"
        )

        val event = sink.events.single()
        assertEquals("cw.merge_by_canonical_key", event.eventType)
        assertEquals("tt9794044:2:1", event.payload["selectedStreamVideoId"])
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.core.trace.TraceMetadataEventsTest
```

Expected: FAIL because `continueWatchingMergeByCanonicalKey` does not exist.

- [ ] **Step 3: Add trace helpers**

Add to `TraceMetadataEvents`:

```kotlin
    fun continueWatchingMergeByCanonicalKey(
        canonicalKeyHash: String,
        mergedSources: List<String>,
        inputIds: List<String>,
        selectedStreamVideoId: String?
    ) {
        sink.emit(
            RuntimeTraceEvent(
                eventType = "cw.merge_by_canonical_key",
                sessionId = sessionId(),
                payload = mapOf(
                    "canonicalKeyHash" to canonicalKeyHash,
                    "mergedSources" to mergedSources,
                    "inputIds" to inputIds,
                    "selectedStreamVideoId" to selectedStreamVideoId
                )
            )
        )
    }

    fun continueWatchingStreamFetchIdentityResolved(
        canonicalKeyHash: String,
        streamVideoId: String?,
        scheme: String,
        confidence: String
    ) {
        sink.emit(
            RuntimeTraceEvent(
                eventType = "cw.stream_fetch_identity_resolved",
                sessionId = sessionId(),
                payload = mapOf(
                    "canonicalKeyHash" to canonicalKeyHash,
                    "streamVideoId" to streamVideoId,
                    "scheme" to scheme,
                    "confidence" to confidence
                )
            )
        )
    }

    fun continueWatchingDisplayMetadataSourceSelected(
        canonicalKeyHash: String,
        source: String,
        languageTag: String
    ) {
        sink.emit(
            RuntimeTraceEvent(
                eventType = "cw.display_metadata_source_selected",
                sessionId = sessionId(),
                payload = mapOf(
                    "canonicalKeyHash" to canonicalKeyHash,
                    "source" to source,
                    "languageTag" to languageTag
                )
            )
        )
    }
```

- [ ] **Step 4: Run trace tests**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.core.trace.TraceMetadataEventsTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/trace/TraceMetadataEvents.kt app/src/test/java/com/nexio/tv/core/trace/TraceMetadataEventsTest.kt
git commit -m "feat: trace continue watching identity decisions"
```

## Task 15: Regression Test Suite And Device Validation

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/sync/ProfileSettingsScopeContractTest.kt`
- Create: `review-dossier/2026-05-07-continue-watching-fix-validation.md`

- [ ] **Step 1: Add architecture contract assertions**

Append to `ProfileSettingsScopeContractTest`:

```kotlin
    @Test
    fun `continue watching uses canonical identity and stream fetch identity boundaries`() {
        val snapshotServiceSource = File("app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt").readText()
        val navHostSource = File("app/src/main/java/com/nexio/tv/ui/navigation/NexioNavHost.kt").readText()
        val homeSource = File("app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt").readText()

        assertTrue(snapshotServiceSource.contains("ContinueWatchingIdentityResolver"))
        assertTrue(snapshotServiceSource.contains("ContinueWatchingMerger.merge"))
        assertTrue(navHostSource.contains("streamVideoId = item.streamFetchVideoId"))
        assertTrue(homeSource.contains("canonicalKey"))
        assertTrue(!snapshotServiceSource.contains("distinctBy { it.contentId }"))
    }
```

- [ ] **Step 2: Run focused regression tests**

Run:

```bash
./gradlew testDebugUnitTest \
  --tests com.nexio.tv.data.repository.ContinueWatchingIdentityModelsTest \
  --tests com.nexio.tv.data.repository.ContinueWatchingItemKeysTest \
  --tests com.nexio.tv.data.repository.StreamFetchIdentityResolverTest \
  --tests com.nexio.tv.data.repository.ContinueWatchingIdentityResolverTest \
  --tests com.nexio.tv.data.repository.ContinueWatchingMergerTest \
  --tests com.nexio.tv.data.repository.ContinueWatchingDisplayResolverTest \
  --tests com.nexio.tv.data.repository.NextUpDiscoveryEngineTest \
  --tests com.nexio.tv.data.repository.ContinueWatchingSnapshotServiceMutationTest \
  --tests com.nexio.tv.ui.navigation.ScreenStreamRouteTest \
  --tests com.nexio.tv.ui.screens.home.HomeViewModelContinueWatchingTest \
  --tests com.nexio.tv.data.local.WatchProgressPreferencesProfileBoundaryTest \
  --tests com.nexio.tv.sync.ProfileSettingsScopeContractTest
```

Expected: PASS.

- [ ] **Step 3: Run broader impacted suites**

Run:

```bash
./gradlew testDebugUnitTest \
  --tests com.nexio.tv.data.repository.ContinueWatchingMetadataRouterTest \
  --tests com.nexio.tv.data.repository.ContinueWatchingRecordTest \
  --tests com.nexio.tv.data.repository.ContinueWatchingTimelineTest \
  --tests com.nexio.tv.data.repository.ContinueWatchingTimelineAirDateTest \
  --tests com.nexio.tv.data.repository.ContinueWatchingSnapshotServiceProfileBoundaryTest \
  --tests com.nexio.tv.ui.screens.home.HomeViewModelContinueWatchingProfileScopedTest \
  --tests com.nexio.tv.ui.screens.home.HomeViewModelContinueWatchingProjectionTest \
  --tests com.nexio.tv.ui.screens.stream.StreamScreenViewModelDeterministicAutoplayTest
```

Expected: PASS.

- [ ] **Step 4: Install and capture device validation logs**

Run:

```bash
./gradlew installDebug
adb -s 192.168.50.98:5555 logcat -c
adb -s 192.168.50.98:5555 shell monkey -p com.nexio.tv 1
adb -s 192.168.50.98:5555 logcat -d > tmp/continue-watching-rca/device/post-fix-logcat.txt
```

Expected:

- App launches.
- `post-fix-logcat.txt` contains `cw.merge_by_canonical_key` for Citadel after snapshot refresh.
- `post-fix-logcat.txt` contains `cw.stream_fetch_identity_resolved` with `tt9794044:2:1` when Citadel CW is clicked.

- [ ] **Step 5: Save validation note**

Create `review-dossier/2026-05-07-continue-watching-fix-validation.md`:

```markdown
# Continue Watching Fix Validation

Date: 2026-05-07

## Unit Tests

Focused regression command:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.data.repository.ContinueWatchingIdentityModelsTest --tests com.nexio.tv.data.repository.ContinueWatchingItemKeysTest --tests com.nexio.tv.data.repository.StreamFetchIdentityResolverTest --tests com.nexio.tv.data.repository.ContinueWatchingIdentityResolverTest --tests com.nexio.tv.data.repository.ContinueWatchingMergerTest --tests com.nexio.tv.data.repository.ContinueWatchingDisplayResolverTest --tests com.nexio.tv.data.repository.NextUpDiscoveryEngineTest --tests com.nexio.tv.data.repository.ContinueWatchingSnapshotServiceMutationTest --tests com.nexio.tv.ui.navigation.ScreenStreamRouteTest --tests com.nexio.tv.ui.screens.home.HomeViewModelContinueWatchingTest --tests com.nexio.tv.data.local.WatchProgressPreferencesProfileBoundaryTest --tests com.nexio.tv.sync.ProfileSettingsScopeContractTest
```

Expected Result: PASS after final execution.

## Device

Device: `192.168.50.98:5555`

Validation points:

- Citadel S02E01 appears once in Continue Watching.
- Citadel Continue Watching click routes with resume `videoId=tvdb:393268:2:1`.
- Citadel stream fetch uses `streamVideoId=tt9794044:2:1`.
- Dutch profile card text uses Dutch localized display metadata when route metadata has Dutch text.
- Newly aired candidates can be created from watched seeds when provider next-up is empty.
```

- [ ] **Step 6: Commit**

```bash
git add app/src/test/java/com/nexio/tv/sync/ProfileSettingsScopeContractTest.kt review-dossier/2026-05-07-continue-watching-fix-validation.md
git commit -m "test: lock continue watching shared resolution regressions"
```

## Final Verification

- [ ] **Step 1: Run all impacted unit tests**

Run:

```bash
./gradlew testDebugUnitTest \
  --tests com.nexio.tv.data.repository.ContinueWatchingIdentityModelsTest \
  --tests com.nexio.tv.data.repository.ContinueWatchingItemKeysTest \
  --tests com.nexio.tv.data.repository.StreamFetchIdentityResolverTest \
  --tests com.nexio.tv.data.repository.ContinueWatchingIdentityResolverTest \
  --tests com.nexio.tv.data.repository.ContinueWatchingMergerTest \
  --tests com.nexio.tv.data.repository.ContinueWatchingDisplayResolverTest \
  --tests com.nexio.tv.data.repository.NextUpDiscoveryEngineTest \
  --tests com.nexio.tv.data.repository.ContinueWatchingSnapshotServiceMutationTest \
  --tests com.nexio.tv.data.repository.ContinueWatchingMetadataRouterTest \
  --tests com.nexio.tv.data.repository.ContinueWatchingRecordTest \
  --tests com.nexio.tv.data.repository.ContinueWatchingTimelineTest \
  --tests com.nexio.tv.data.repository.ContinueWatchingTimelineAirDateTest \
  --tests com.nexio.tv.data.repository.ContinueWatchingSnapshotServiceProfileBoundaryTest \
  --tests com.nexio.tv.data.local.WatchProgressPreferencesProfileBoundaryTest \
  --tests com.nexio.tv.ui.navigation.ScreenStreamRouteTest \
  --tests com.nexio.tv.ui.screens.home.HomeViewModelContinueWatchingTest \
  --tests com.nexio.tv.ui.screens.home.HomeViewModelContinueWatchingProfileScopedTest \
  --tests com.nexio.tv.ui.screens.home.HomeViewModelContinueWatchingProjectionTest \
  --tests com.nexio.tv.ui.screens.stream.StreamScreenViewModelDeterministicAutoplayTest \
  --tests com.nexio.tv.sync.ProfileSettingsScopeContractTest
```

Expected: PASS.

- [ ] **Step 2: Run full debug unit test suite before merge**

Run:

```bash
./gradlew testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 3: Check plugin versioning rules**

Run:

```bash
git diff -- .claude-plugin/plugin.json .claude-plugin/marketplace.json CHANGELOG.md README.md
```

Expected: no plugin release version bump and no root changelog release section.

## Self-Review

Spec coverage:

- Playback failure: Task 3 resolves stream fetch ids; Task 8 routes `streamVideoId`; Task 15 validates route behavior.
- Duplicate Citadel: Task 4 resolves canonical identity; Task 6 merges by canonical key; Task 7 canonicalizes snapshots.
- Localized metadata divergence: Task 9 adds shared display resolver; Task 10 integrates it into snapshot hydration.
- Newly aired episodes missing: Task 12 adds seed discovery; Task 13 wires it into snapshots.
- Series mirror stale rows: Task 11 removes mirror writes and deletes existing mirror keys on episode save.
- Profile/session scope: Tasks 5, 7, 13 keep `profileId` on canonical keys and records; Task 15 locks profile contracts.

Placeholder scan:

- The plan contains none of the prohibited placeholder phrases.
- Every code-changing task includes concrete code blocks or exact replacements.

Type consistency:

- `ContinueWatchingCanonicalKey`, `ResumeIdentity`, `StreamFetchIdentity`, `TrackingIdentity`, and `ContinueWatchingIdentityResolution` are defined in Task 1 and reused consistently.
- `ContinueWatchingItemKeys` is defined in Task 2 and used consistently in snapshot/display/UI tasks.
- `StreamFetchIdentityResolver` is defined in Task 3 and injected into identity and next-up resolvers.
- `ContinueWatchingRecord.identityKey()` consistently prefers canonical key when present.
