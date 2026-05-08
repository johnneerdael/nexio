# Continue Watching P0 Identity Playback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix P0 Continue Watching playback and duplicate-row bugs by making canonical records authoritative for UI rows and routing clicks with a separate stream-fetch identity.

**Architecture:** Raw `WatchProgress` remains the legacy input and resume truth, but Home Continue Watching renders from canonical `ContinueWatchingRecord`s. Snapshot canonicalization is suspend-aware, preserves rows when identity resolution fails, and uses identity-only stable-id resolution instead of detail hydration. Packet 2 will handle localized display unification, series mirror cleanup, next-up fallback discovery, and trace expansion after this packet is green.

**Tech Stack:** Kotlin, Android/Hilt, coroutines/Flow, MetadataRouterFacade stable-id bundle, Gson snapshot persistence, JUnit4, MockK, Robolectric.

---

## Scope Check

This is Packet 1 only.

Included:

- Separate resume identity from stream-fetch identity.
- Resolve canonical CW keys for local and remote progress.
- Merge local TVDB Citadel and Trakt IMDb Citadel into one canonical record.
- Render UI rows from `snapshot.records`, not raw `resumeItems`.
- Preserve unresolved identity rows as low-confidence legacy records.
- Avoid `runBlocking` in snapshot construction.
- Use identity-only metadata depth for CW stable-id resolution.

Excluded:

- Localized display resolver and artwork projection. Packet 2 must use shared artwork legacy projection methods such as `toLegacyString()`, not raw `.url`.
- NextUpDiscoveryEngine.
- Series-level mirror cleanup.
- Expanded trace event surface beyond tests needed for P0.

Review-driven regression tests to include in Packet 1:

- `merged_record_ui_uses_progress_winner_position_not_newest_alias`
- `merged_record_preserves_local_resume_position_when_remote_zero_progress_is_newer`
- `merged_record_primary_resume_alias_is_used_for_resume_video_id`
- `continue_watching_snapshot_service_requires_identity_resolver_in_production`
- `legacy_low_confidence_record_preserves_remote_source`
- `cw_identity_depth_uses_cached_identity_aliases_without_detail_core_provider_steps`
- `cw_identity_depth_without_supported_stream_id_preserves_row_with_warning`

## File Structure

Create:

- `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingIdentityModels.kt`  
  Models canonical key, resume identity, stream-fetch identity, identity warnings, and raw resolver input.
- `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingItemKeys.kt`  
  Centralizes parent, episode, raw fallback, and resume alias keys. Unknown identities use a hash of the raw id and never `raw:unknown`.
- `app/src/main/java/com/nexio/tv/data/repository/StreamFetchIdentityResolver.kt`  
  Phase 0 policy-backed resolver for default Stremio IMDb stream id shape.
- `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingIdentityResolver.kt`  
  Resolves raw progress to canonical/display/stream identities with low-confidence fallback records on failure.
- `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingMerger.kt`  
  Merges canonical records and preserves all resume aliases.

Modify:

- `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataModels.kt`  
  Add `MetadataDepth.IDENTITY`.
- `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouter.kt`  
  Allow `IDENTITY` routing.
- `app/src/main/java/com/nexio/tv/core/metadata/router/ResolverOrchestrator.kt`  
  Identity depth schedules no field resolvers.
- `app/src/main/java/com/nexio/tv/core/metadata/router/ProviderPlanExecutor.kt`  
  Treat `IDENTITY` like `PREVIEW` for provider-plan execution.
- `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingRecord.kt`  
  Add canonical key, display identity, stream-fetch identity, all resume identities, lookup aliases, primary resume alias, confidence, and warnings.
- `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt`  
  Build canonical records in suspend flow transforms; `records` become authoritative UI source.
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeUiState.kt`  
  Carry canonical key and stream-fetch id on Continue Watching UI models.
- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt`  
  Build rows from `snapshot.records` when present and use canonical key for dedupe.
- `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeModels.kt`  
  Use canonical keys for stable UI item keys.
- `app/src/main/java/com/nexio/tv/ui/navigation/NexioNavHost.kt`  
  Pass `streamVideoId` from CW UI models.

Test:

- `app/src/test/java/com/nexio/tv/core/metadata/router/MetadataDepthIdentityTest.kt`
- `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingIdentityModelsTest.kt`
- `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingItemKeysTest.kt`
- `app/src/test/java/com/nexio/tv/data/repository/StreamFetchIdentityResolverTest.kt`
- `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingIdentityResolverTest.kt`
- `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingMergerTest.kt`
- `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotServiceMutationTest.kt`
- `app/src/test/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatchingTest.kt`
- `app/src/test/java/com/nexio/tv/ui/navigation/ScreenStreamRouteTest.kt`

## Task 1: Identity-Only Metadata Depth

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataModels.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouter.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/metadata/router/ResolverOrchestrator.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/metadata/router/ProviderPlanExecutor.kt`
- Create: `app/src/test/java/com/nexio/tv/core/metadata/router/MetadataDepthIdentityTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.nexio.tv.core.metadata.router

import com.nexio.tv.domain.model.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataDepthIdentityTest {
    @Test
    fun `identity depth is routable and schedules no field resolvers`() {
        val schedule = ResolverOrchestrator().schedule(MetadataDepth.IDENTITY)

        assertTrue(schedule.resolvers.isEmpty())
    }

    @Test
    fun `identity depth does not build provider execution steps`() {
        val route = MetadataRoute(
            provider = MetadataPrimaryProvider.TVDB,
            parentId = "tvdb:393268",
            mediaKind = MetadataMediaKind.SERIES,
            reason = MetadataDecisionReason.PROVIDER_NATIVE_DIRECT,
            sourceContext = MetadataSourceContext(itemType = "series"),
            targetIds = mapOf(MetadataPrimaryProvider.TVDB to "tvdb:393268"),
            trace = listOf(MetadataRouteTrace(MetadataDecisionReason.PROVIDER_NATIVE_DIRECT, "test"))
        )

        val plan = ProviderPlanExecutor().buildPlan(route, MetadataDepth.IDENTITY)

        assertEquals(MetadataDepth.IDENTITY, plan.depth)
        assertTrue(plan.steps.isEmpty())
    }

    @Test
    fun `identity request can be normalized by router`() {
        val router = MetadataRouter()
        val route = router.route(
            MetadataRequest(
                contentId = "tvdb:393268",
                contentType = ContentType.SERIES,
                sourceContext = MetadataSourceContext(itemType = "series"),
                depth = MetadataDepth.IDENTITY
            )
        )

        assertEquals("tvdb:393268", route.parentId)
        assertEquals(MetadataPrimaryProvider.TVDB, route.provider)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.core.metadata.router.MetadataDepthIdentityTest
```

Expected: FAIL because `MetadataDepth.IDENTITY` does not exist.

- [ ] **Step 3: Add identity depth**

In `MetadataModels.kt`, replace:

```kotlin
enum class MetadataDepth { PREVIEW, DETAIL_CORE, DETAIL_MEDIA, DETAIL_SECONDARY, DETAIL_FULL, SEASON, PLAYER }
```

with:

```kotlin
enum class MetadataDepth { PREVIEW, IDENTITY, DETAIL_CORE, DETAIL_MEDIA, DETAIL_SECONDARY, DETAIL_FULL, SEASON, PLAYER }
```

- [ ] **Step 4: Allow identity routing**

In `MetadataRouter.kt`, keep the existing PREVIEW guard and do not add `IDENTITY` to it:

```kotlin
require(request.depth != MetadataDepth.PREVIEW) {
    "MetadataRouter.route should not be called for PREVIEW requests"
}
```

- [ ] **Step 5: Schedule no resolvers for identity**

In `ResolverOrchestrator.schedule`, add the identity branch next to `PREVIEW`:

```kotlin
MetadataDepth.PREVIEW,
MetadataDepth.IDENTITY -> Unit
```

- [ ] **Step 6: Prevent provider execution for identity**

In `ProviderPlanExecutor`, update the unsupported depths:

```kotlin
val unsupportedDepths = setOf(MetadataDepth.PREVIEW, MetadataDepth.IDENTITY)
```

Ensure `buildPlan` returns an empty-step plan for unsupported depths:

```kotlin
if (depth in unsupportedDepths) {
    return ProviderExecutionPlan(route = route, depth = depth, steps = emptyList())
}
```

- [ ] **Step 7: Run tests**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.core.metadata.router.MetadataDepthIdentityTest
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/metadata/router/MetadataModels.kt app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouter.kt app/src/main/java/com/nexio/tv/core/metadata/router/ResolverOrchestrator.kt app/src/main/java/com/nexio/tv/core/metadata/router/ProviderPlanExecutor.kt app/src/test/java/com/nexio/tv/core/metadata/router/MetadataDepthIdentityTest.kt
git commit -m "feat: add identity-only metadata depth"
```

## Task 2: Identity Models And Non-Colliding Keys

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingIdentityModels.kt`
- Create: `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingItemKeys.kt`
- Create: `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingIdentityModelsTest.kt`
- Create: `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingItemKeysTest.kt`

- [ ] **Step 1: Write failing model tests**

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
            profileId = 1
        )

        assertEquals("profile:1:series:tvdb:393268:s2e1", key.stableKey())
    }

    @Test
    fun `resume lookup key preserves raw resume identity`() {
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

        assertEquals("tvdb:393268|tvdb:393268:2:1|2|1", resume.lookupKey())
        assertTrue(resume.isEpisode)
    }
}
```

- [ ] **Step 2: Write failing key tests**

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ContentIdentity
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ContinueWatchingItemKeysTest {
    @Test
    fun `episode key uses canonical provider and coordinate`() {
        val identity = ContentIdentity(
            canonicalProvider = ProviderId.TVDB,
            canonicalId = "393268",
            providerIds = ProviderIds(tvdb = "393268", imdb = "tt9794044")
        )

        assertEquals(
            "series:tvdb:393268:s2e1",
            ContinueWatchingItemKeys.episodeKey(MetadataMediaKind.SERIES, identity, 2, 1, "tvdb:393268")
        )
    }

    @Test
    fun `unknown identity parent keys do not collide`() {
        val identity = ContentIdentity(
            canonicalProvider = null,
            canonicalId = null,
            providerIds = ProviderIds()
        )

        val first = ContinueWatchingItemKeys.parentKey(MetadataMediaKind.SERIES, identity, "addon-a:show")
        val second = ContinueWatchingItemKeys.parentKey(MetadataMediaKind.SERIES, identity, "addon-b:show")

        assertNotEquals(first, second)
        assertEquals(true, first.startsWith("series:raw:"))
        assertEquals(true, second.startsWith("series:raw:"))
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.data.repository.ContinueWatchingIdentityModelsTest --tests com.nexio.tv.data.repository.ContinueWatchingItemKeysTest
```

Expected: FAIL with unresolved model and key classes.

- [ ] **Step 4: Add identity models**

Create `ContinueWatchingIdentityModels.kt`:

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ContentIdentity
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.WatchProgress

data class ContinueWatchingCanonicalKey(
    val mediaKind: MetadataMediaKind,
    val canonicalParent: ContentIdentity,
    val season: Int?,
    val episode: Int?,
    val profileId: Int
) {
    init {
        require(profileId > 0) { "profileId must be positive" }
        require((season == null) == (episode == null)) { "season and episode must both be present or absent" }
    }

    fun stableKey(): String =
        if (season != null && episode != null) {
            "${parentStableKey()}:s${season}e${episode}"
        } else {
            parentStableKey()
        }

    private fun parentStableKey(): String =
        "profile:$profileId:${ContinueWatchingItemKeys.parentKey(mediaKind, canonicalParent, canonicalParent.bestRawFallback())}"
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
    }

    val isEpisode: Boolean = season != null && episode != null

    fun lookupKey(): String =
        listOf(contentId, videoId.orEmpty(), season?.toString().orEmpty(), episode?.toString().orEmpty())
            .joinToString("|")
}

data class StreamFetchIdentity(
    val contentId: String,
    val videoId: String,
    val idScheme: StreamIdScheme,
    val confidence: IdentityConfidence,
    val trace: List<String>
)

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
)

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
    SYNTHETIC
}

enum class StreamIdScheme {
    IMDB_MOVIE,
    IMDB_EPISODE,
    UNRESOLVED
}

enum class IdentityConfidence {
    HIGH,
    MEDIUM,
    LOW
}

fun WatchProgress.toResumeIdentity(): ResumeIdentity =
    ResumeIdentity(
        source = when (source) {
            WatchProgress.SOURCE_TRAKT_PLAYBACK -> ContinueWatchingSource.TRAKT_PLAYBACK
            WatchProgress.SOURCE_TRAKT_HISTORY -> ContinueWatchingSource.TRAKT_HISTORY
            WatchProgress.SOURCE_TRAKT_SHOW_PROGRESS -> ContinueWatchingSource.TRAKT_SHOW_PROGRESS
            else -> ContinueWatchingSource.LOCAL
        },
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
    val hasTracking = traktShowId != null || traktEpisodeId != null || traktPlaybackId != null || traktMovieId != null
    if (!hasTracking) return null
    return TrackingIdentity(
        traktShowId = traktShowId,
        traktEpisodeId = traktEpisodeId,
        traktPlaybackId = traktPlaybackId,
        traktMovieId = traktMovieId,
        providerIds = ProviderIds(imdb = contentId.takeIf { it.matches(Regex("^tt\\d+$")) }, trakt = traktShowId?.toString())
    )
}

private fun ContentIdentity.bestRawFallback(): String =
    canonicalId ?: providerIds.tvdb ?: providerIds.tmdb ?: providerIds.kitsu ?: providerIds.imdb ?: providerIds.trakt ?: providerIds.simkl ?: "missing"
```

- [ ] **Step 5: Add key generator**

Create `ContinueWatchingItemKeys.kt`:

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ContentIdentity
import com.nexio.tv.domain.model.ProviderIds
import java.security.MessageDigest

object ContinueWatchingItemKeys {
    fun parentKey(
        mediaKind: MetadataMediaKind,
        identity: ContentIdentity,
        fallbackRawId: String
    ): String {
        val kind = mediaKind.name.lowercase()
        val providerAndId = providerAndId(identity.providerIds, identity.canonicalProvider?.name?.lowercase(), identity.canonicalId)
            ?: "raw:${stableHash(fallbackRawId)}"
        return "$kind:$providerAndId"
    }

    fun episodeKey(
        mediaKind: MetadataMediaKind,
        identity: ContentIdentity,
        season: Int,
        episode: Int,
        fallbackRawId: String
    ): String =
        "${parentKey(mediaKind, identity, fallbackRawId)}:s${season}e${episode}"

    fun legacyParentKey(contentType: String, contentId: String): String =
        "${contentType.lowercase()}:${contentId.trim()}"

    private fun providerAndId(ids: ProviderIds, canonicalProvider: String?, canonicalId: String?): String? {
        if (!canonicalProvider.isNullOrBlank() && !canonicalId.isNullOrBlank()) {
            return "$canonicalProvider:$canonicalId"
        }
        return when {
            ids.tvdb != null -> "tvdb:${ids.tvdb}"
            ids.tmdb != null -> "tmdb:${ids.tmdb}"
            ids.kitsu != null -> "kitsu:${ids.kitsu}"
            ids.imdb != null -> "imdb:${ids.imdb}"
            ids.trakt != null -> "trakt:${ids.trakt}"
            ids.simkl != null -> "simkl:${ids.simkl}"
            else -> null
        }
    }

    private fun stableHash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { byte -> "%02x".format(byte) }
    }
}
```

- [ ] **Step 6: Run tests**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.data.repository.ContinueWatchingIdentityModelsTest --tests com.nexio.tv.data.repository.ContinueWatchingItemKeysTest
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingIdentityModels.kt app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingItemKeys.kt app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingIdentityModelsTest.kt app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingItemKeysTest.kt
git commit -m "feat: add continue watching identity keys"
```

## Task 3: Phase 0 Stream Fetch Identity Resolver

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/repository/StreamFetchIdentityResolver.kt`
- Create: `app/src/test/java/com/nexio/tv/data/repository/StreamFetchIdentityResolverTest.kt`

- [ ] **Step 1: Write failing tests**

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
    fun `default stremio series uses imdb episode stream id`() = runTest {
        val identity = ContentIdentity(
            canonicalProvider = ProviderId.TVDB,
            canonicalId = "393268",
            providerIds = ProviderIds(tvdb = "393268", imdb = "tt9794044")
        )

        val result = resolver.resolveForEpisode(identity, identity.providerIds, 2, 1, StreamSourceContext(MetadataMediaKind.SERIES, "tvdb:393268:2:1"))

        assertEquals("tt9794044:2:1", result?.videoId)
        assertEquals(StreamIdScheme.IMDB_EPISODE, result?.idScheme)
    }

    @Test
    fun `stream identity is unresolved when no supported stream id exists`() = runTest {
        val identity = ContentIdentity(
            canonicalProvider = ProviderId.TVDB,
            canonicalId = "393268",
            providerIds = ProviderIds(tvdb = "393268")
        )

        val result = resolver.resolveForEpisode(identity, identity.providerIds, 2, 1, StreamSourceContext(MetadataMediaKind.SERIES, "tvdb:393268:2:1"))

        assertNull(result)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.data.repository.StreamFetchIdentityResolverTest
```

Expected: FAIL with unresolved resolver.

- [ ] **Step 3: Add resolver**

Create `StreamFetchIdentityResolver.kt`:

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ContentIdentity
import com.nexio.tv.domain.model.ProviderIds
import javax.inject.Inject
import javax.inject.Singleton

data class StreamSourceContext(
    val mediaKind: MetadataMediaKind,
    val resumeVideoId: String?
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
        val imdbId = knownIds.imdb?.takeIf(String::isStrictImdbId)
            ?: canonicalIdentity.providerIds.imdb?.takeIf(String::isStrictImdbId)
            ?: return null
        return StreamFetchIdentity(
            contentId = canonicalIdentity.providerIds.tvdb?.let { "tvdb:$it" } ?: canonicalIdentity.providerIds.imdb ?: imdbId,
            videoId = "$imdbId:$season:$episode",
            idScheme = StreamIdScheme.IMDB_EPISODE,
            confidence = IdentityConfidence.HIGH,
            trace = listOf("phase0 default stremio imdb episode stream id")
        )
    }

    suspend fun resolveForMovie(
        canonicalIdentity: ContentIdentity,
        knownIds: ProviderIds,
        sourceContext: StreamSourceContext
    ): StreamFetchIdentity? {
        val imdbId = knownIds.imdb?.takeIf(String::isStrictImdbId)
            ?: canonicalIdentity.providerIds.imdb?.takeIf(String::isStrictImdbId)
            ?: return null
        return StreamFetchIdentity(
            contentId = canonicalIdentity.providerIds.tmdb?.let { "tmdb:$it" } ?: imdbId,
            videoId = imdbId,
            idScheme = StreamIdScheme.IMDB_MOVIE,
            confidence = IdentityConfidence.HIGH,
            trace = listOf("phase0 default stremio imdb movie stream id")
        )
    }
}

private fun String.isStrictImdbId(): Boolean =
    matches(Regex("^tt\\d+$"))
```

- [ ] **Step 4: Add design note comment**

At the top of `StreamFetchIdentityResolver`, above `@Singleton`, include:

```kotlin
/**
 * Phase 0 stream policy: default Stremio-style addons fetch series streams by
 * IMDb episode id (`tt...:season:episode`). Addon-specific stream id support
 * belongs in a later AddonStreamIdPolicy, not in this P0 resolver.
 */
```

- [ ] **Step 5: Run tests**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.data.repository.StreamFetchIdentityResolverTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/StreamFetchIdentityResolver.kt app/src/test/java/com/nexio/tv/data/repository/StreamFetchIdentityResolverTest.kt
git commit -m "feat: resolve default continue watching stream ids"
```

## Task 4: Continue Watching Record With Aliases, Primary Resume, And Failure State

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingRecord.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingRecordTest.kt`

- [ ] **Step 1: Add failing record tests**

Append to `ContinueWatchingRecordTest`:

```kotlin
    @Test
    fun `record identity key prefers canonical key and exposes resume aliases`() {
        val resume = ResumeIdentity(
            source = ContinueWatchingSource.LOCAL,
            contentId = "tvdb:393268",
            videoId = "tvdb:393268:2:1",
            season = 2,
            episode = 1,
            positionMs = 65_066L,
            durationMs = 2_958_656L,
            progressPercent = null,
            lastWatchedMs = 200L
        )
        val record = ContinueWatchingRecord(
            profileId = 1,
            parentId = "series:tvdb:393268",
            contentId = "series:tvdb:393268:s2e1",
            provider = TrackingProvider.TRAKT,
            routingVersion = 1,
            positionMs = 65_066L,
            durationMs = 2_958_656L,
            episodeContext = ContinueWatchingRecord.EpisodeContext(2, 1),
            clickTimeDisplayMetadata = null,
            source = ContinueWatchingRecord.Source.LOCAL,
            updatedAt = 200L,
            canonicalKey = ContinueWatchingCanonicalKey(
                mediaKind = com.nexio.tv.core.metadata.router.MetadataMediaKind.SERIES,
                canonicalParent = com.nexio.tv.domain.model.ContentIdentity(
                    canonicalProvider = com.nexio.tv.domain.model.ProviderId.TVDB,
                    canonicalId = "393268",
                    providerIds = com.nexio.tv.domain.model.ProviderIds(tvdb = "393268", imdb = "tt9794044")
                ),
                season = 2,
                episode = 1,
                profileId = 1
            ),
            resumeIdentities = listOf(resume),
            streamFetchIdentity = StreamFetchIdentity("tvdb:393268", "tt9794044:2:1", StreamIdScheme.IMDB_EPISODE, IdentityConfidence.HIGH, listOf("test")),
            identityConfidence = IdentityConfidence.HIGH
        )

        assertEquals("profile:1:series:tvdb:393268:s2e1", record.identityKey())
        assertEquals(setOf("tvdb:393268|tvdb:393268:2:1|2|1"), record.resumeLookupKeys)
        assertEquals("tvdb:393268|tvdb:393268:2:1|2|1", record.primaryResumeLookupKey)
    }

    @Test
    fun `low confidence legacy record remains valid when identity is unresolved`() {
        val record = ContinueWatchingRecord(
            profileId = 1,
            parentId = "series:raw:abc",
            contentId = "series:raw:abc:s1e1",
            provider = TrackingProvider.TRAKT,
            routingVersion = 1,
            positionMs = 10L,
            durationMs = 100L,
            episodeContext = ContinueWatchingRecord.EpisodeContext(1, 1),
            clickTimeDisplayMetadata = null,
            source = ContinueWatchingRecord.Source.LOCAL,
            updatedAt = 1L,
            identityConfidence = IdentityConfidence.LOW,
            identityWarnings = listOf("identity resolution failed")
        )

        assertEquals("profile:1:continue-watching:series:raw:abc:s1e1", record.identityKey())
        assertEquals(listOf("identity resolution failed"), record.identityWarnings)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.data.repository.ContinueWatchingRecordTest
```

Expected: FAIL because record fields are missing.

- [ ] **Step 3: Extend record**

Add these defaulted fields to `ContinueWatchingRecord` after `updatedAt`:

```kotlin
    val canonicalKey: ContinueWatchingCanonicalKey? = null,
    val displayIdentity: com.nexio.tv.domain.model.ContentIdentity? = null,
    val streamFetchIdentity: StreamFetchIdentity? = null,
    val trackingIdentity: TrackingIdentity? = null,
    val resumeIdentities: List<ResumeIdentity> = emptyList(),
    val primaryResumeLookupKey: String? = resumeIdentities.firstOrNull()?.lookupKey(),
    val identityConfidence: IdentityConfidence = IdentityConfidence.LOW,
    val identityWarnings: List<String> = emptyList(),
    val languageTag: String? = null
```

Add derived aliases:

```kotlin
    val resumeLookupKeys: Set<String> = resumeIdentities.map { it.lookupKey() }.toSet()
```

Replace `identityKey()`:

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

Add init checks:

```kotlin
        require(canonicalKey == null || canonicalKey.profileId == profileId) { "canonicalKey.profileId must match profileId" }
        require(languageTag == null || languageTag.isNotBlank()) { "languageTag must not be blank when present" }
        require(primaryResumeLookupKey == null || primaryResumeLookupKey in resumeLookupKeys) {
            "primaryResumeLookupKey must reference one of resumeLookupKeys"
        }
```

Add this comment above `provider` in `ContinueWatchingRecord`:

```kotlin
// Legacy tracking-provider field. Continue Watching source ownership must use
// source, trackingIdentity, and resumeIdentities.source instead of this value.
```

- [ ] **Step 4: Run tests**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.data.repository.ContinueWatchingRecordTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingRecord.kt app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingRecordTest.kt
git commit -m "feat: add canonical resume aliases to cw records"
```

## Task 5: Identity Resolver With Low-Confidence Fallback

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingIdentityResolver.kt`
- Create: `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingIdentityResolverTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.core.metadata.router.CanonicalStableIds
import com.nexio.tv.core.metadata.router.MetadataRequest
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.core.metadata.router.SidecarStableIds
import com.nexio.tv.core.metadata.router.SourceStableIds
import com.nexio.tv.core.metadata.router.StableIdBundle
import com.nexio.tv.core.metadata.router.StableIdResolutionTrigger
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.WatchProgress
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinueWatchingIdentityResolverTest {
    @Test
    fun `identity depth uses cached sidecar aliases without detail core provider steps`() = runTest {
        val requestSlot = slot<MetadataRequest>()
        val facade = mockk<MetadataRouterFacade>()
        coEvery {
            facade.resolveStableIdBundle(capture(requestSlot), StableIdResolutionTrigger.CONTINUE_WATCHING, any())
        } returns bundle()
        val resolver = ContinueWatchingIdentityResolver(facade, StreamFetchIdentityResolver())

        val local = resolver.resolveOrFallback(RawContinueWatchingInput(1, citadel("tvdb:393268", "tvdb:393268:2:1"), "nl"))
        val trakt = resolver.resolveOrFallback(RawContinueWatchingInput(1, citadel("tt9794044", "tt9794044:2:1"), "nl"))

        assertEquals(local.canonicalKey?.stableKey(), trakt.canonicalKey?.stableKey())
        assertEquals("profile:1:series:tvdb:393268:s2e1", local.identityKey())
        assertEquals("tt9794044:2:1", local.streamFetchIdentity?.videoId)
        assertEquals(com.nexio.tv.core.metadata.router.MetadataDepth.IDENTITY, requestSlot.captured.depth)
    }

    @Test
    fun `identity resolution failure preserves low confidence legacy row`() = runTest {
        val facade = mockk<MetadataRouterFacade>()
        coEvery { facade.resolveStableIdBundle(any(), any(), any()) } throws IllegalStateException("no ids")
        val resolver = ContinueWatchingIdentityResolver(facade, StreamFetchIdentityResolver())

        val record = resolver.resolveOrFallback(RawContinueWatchingInput(1, citadel("provider:missing", "provider:missing:2:1"), "nl"))

        assertEquals(IdentityConfidence.LOW, record.identityConfidence)
        assertTrue(record.identityWarnings.single().contains("no ids"))
        assertEquals("provider:missing", record.resumeIdentities.single().contentId)
    }

    @Test
    fun `legacy low confidence record preserves remote source`() = runTest {
        val facade = mockk<MetadataRouterFacade>()
        coEvery { facade.resolveStableIdBundle(any(), any(), any()) } throws IllegalStateException("no ids")
        val resolver = ContinueWatchingIdentityResolver(facade, StreamFetchIdentityResolver())

        val record = resolver.resolveOrFallback(
            RawContinueWatchingInput(
                1,
                citadel("tt9794044", "tt9794044:2:1").copy(source = WatchProgress.SOURCE_TRAKT_PLAYBACK),
                "nl"
            )
        )

        assertEquals(ContinueWatchingRecord.Source.REMOTE, record.source)
        assertEquals(ContinueWatchingSource.TRAKT_PLAYBACK, record.resumeIdentities.single().source)
    }

    @Test
    fun `identity depth without supported stream id preserves row with warning`() = runTest {
        val facade = mockk<MetadataRouterFacade>()
        coEvery {
            facade.resolveStableIdBundle(any<MetadataRequest>(), StableIdResolutionTrigger.CONTINUE_WATCHING, any())
        } returns bundle().copy(sidecars = SidecarStableIds(imdbId = null))
        val resolver = ContinueWatchingIdentityResolver(facade, StreamFetchIdentityResolver())

        val record = resolver.resolveOrFallback(RawContinueWatchingInput(1, citadel("tvdb:393268", "tvdb:393268:2:1"), "nl"))

        assertEquals(IdentityConfidence.MEDIUM, record.identityConfidence)
        assertEquals(null, record.streamFetchIdentity)
        assertTrue(record.identityWarnings.contains("stream fetch identity unresolved"))
        assertEquals("tvdb:393268", record.resumeIdentities.single().contentId)
    }

    private fun bundle(): StableIdBundle =
        StableIdBundle(
            itemKey = "cw",
            itemType = ContentType.SERIES,
            canonical = CanonicalStableIds(tvdbSeriesId = "393268"),
            sidecars = SidecarStableIds(imdbId = "tt9794044"),
            source = SourceStableIds(ProviderId.TVDB, "tvdb:393268", null, ProviderIds(tvdb = "393268", imdb = "tt9794044")),
            evidence = emptyList(),
            resolvedAtMs = 1L
        )

    private fun citadel(contentId: String, videoId: String): WatchProgress =
        WatchProgress(
            contentId = contentId,
            contentType = "series",
            name = "Citadel",
            poster = null,
            backdrop = null,
            logo = null,
            videoId = videoId,
            season = 2,
            episode = 1,
            episodeTitle = "Baked Alaskas",
            position = 65_066L,
            duration = 2_958_656L,
            lastWatched = 200L,
            source = if (contentId.startsWith("tt")) WatchProgress.SOURCE_TRAKT_PLAYBACK else WatchProgress.SOURCE_LOCAL
        )
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.data.repository.ContinueWatchingIdentityResolverTest
```

Expected: FAIL with unresolved resolver.

- [ ] **Step 3: Add resolver**

Create `ContinueWatchingIdentityResolver.kt`:

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
import com.nexio.tv.domain.model.TrackingProvider
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContinueWatchingIdentityResolver @Inject constructor(
    private val metadataRouterFacade: MetadataRouterFacade,
    private val streamFetchIdentityResolver: StreamFetchIdentityResolver
) {
    suspend fun resolveOrFallback(input: RawContinueWatchingInput): ContinueWatchingRecord {
        return runCatching { resolve(input) }.getOrElse { error -> legacyRecord(input, error) }
    }

    private suspend fun resolve(input: RawContinueWatchingInput): ContinueWatchingRecord {
        val progress = input.progress
        val contentType = ContentType.fromString(progress.contentType)
        val mediaKind = if (contentType == ContentType.MOVIE) MetadataMediaKind.MOVIE else MetadataMediaKind.SERIES
        val observedIds = observedIds(progress)
        val request = MetadataRequest(
            contentId = progress.contentId,
            contentType = contentType,
            sourceContext = MetadataSourceContext(itemType = progress.contentType, previewStableIds = observedIds),
            language = input.languageTag,
            seasonNumber = progress.season,
            depth = MetadataDepth.IDENTITY
        )
        val bundle = metadataRouterFacade.resolveStableIdBundle(
            request = request,
            trigger = StableIdResolutionTrigger.CONTINUE_WATCHING,
            itemKey = ContinueWatchingItemKeys.legacyParentKey(progress.contentType, progress.contentId)
        )
        val displayIdentity = bundle.toContentIdentity(mediaKind, progress)
        val streamFetchIdentity = if (progress.season != null && progress.episode != null) {
            streamFetchIdentityResolver.resolveForEpisode(displayIdentity, displayIdentity.providerIds, progress.season, progress.episode, StreamSourceContext(mediaKind, progress.videoId))
        } else {
            streamFetchIdentityResolver.resolveForMovie(displayIdentity, displayIdentity.providerIds, StreamSourceContext(mediaKind, progress.videoId))
        }
        val canonicalKey = ContinueWatchingCanonicalKey(mediaKind, displayIdentity, progress.season, progress.episode, input.profileId)
        val contentKey = if (progress.season != null && progress.episode != null) {
            ContinueWatchingItemKeys.episodeKey(mediaKind, displayIdentity, progress.season, progress.episode, progress.contentId)
        } else {
            ContinueWatchingItemKeys.parentKey(mediaKind, displayIdentity, progress.contentId)
        }
        return ContinueWatchingRecord(
            profileId = input.profileId,
            parentId = ContinueWatchingItemKeys.parentKey(mediaKind, displayIdentity, progress.contentId),
            contentId = contentKey,
            provider = TrackingProvider.TRAKT,
            routingVersion = ContinueWatchingMetadataSnapshot.CURRENT_ROUTING_VERSION,
            positionMs = progress.position,
            durationMs = progress.duration,
            episodeContext = if (progress.season != null && progress.episode != null) ContinueWatchingRecord.EpisodeContext(progress.season, progress.episode) else null,
            clickTimeDisplayMetadata = null,
            source = if (progress.source == com.nexio.tv.domain.model.WatchProgress.SOURCE_LOCAL) ContinueWatchingRecord.Source.LOCAL else ContinueWatchingRecord.Source.REMOTE,
            updatedAt = progress.lastWatched.coerceAtLeast(1L),
            canonicalKey = canonicalKey,
            displayIdentity = displayIdentity,
            streamFetchIdentity = streamFetchIdentity,
            trackingIdentity = progress.toTrackingIdentity(),
            resumeIdentities = listOf(progress.toResumeIdentity()),
            primaryResumeLookupKey = progress.toResumeIdentity().lookupKey(),
            identityConfidence = if (streamFetchIdentity != null) IdentityConfidence.HIGH else IdentityConfidence.MEDIUM,
            identityWarnings = if (streamFetchIdentity == null && mediaKind == MetadataMediaKind.SERIES) listOf("stream fetch identity unresolved") else emptyList(),
            languageTag = input.languageTag
        )
    }

    private fun legacyRecord(input: RawContinueWatchingInput, error: Throwable): ContinueWatchingRecord {
        val progress = input.progress
        val mediaKind = if (ContentType.fromString(progress.contentType) == ContentType.MOVIE) MetadataMediaKind.MOVIE else MetadataMediaKind.SERIES
        val rawIdentity = ContentIdentity(null, null, ProviderIds())
        val parentKey = ContinueWatchingItemKeys.parentKey(mediaKind, rawIdentity, progress.contentId)
        val contentKey = if (progress.season != null && progress.episode != null) "$parentKey:s${progress.season}e${progress.episode}" else parentKey
        return ContinueWatchingRecord(
            profileId = input.profileId,
            parentId = parentKey,
            contentId = contentKey,
            provider = TrackingProvider.TRAKT,
            routingVersion = ContinueWatchingMetadataSnapshot.CURRENT_ROUTING_VERSION,
            positionMs = progress.position,
            durationMs = progress.duration,
            episodeContext = if (progress.season != null && progress.episode != null) ContinueWatchingRecord.EpisodeContext(progress.season, progress.episode) else null,
            clickTimeDisplayMetadata = null,
            source = if (progress.source == com.nexio.tv.domain.model.WatchProgress.SOURCE_LOCAL) ContinueWatchingRecord.Source.LOCAL else ContinueWatchingRecord.Source.REMOTE,
            updatedAt = progress.lastWatched.coerceAtLeast(1L),
            resumeIdentities = listOf(progress.toResumeIdentity()),
            primaryResumeLookupKey = progress.toResumeIdentity().lookupKey(),
            identityConfidence = IdentityConfidence.LOW,
            identityWarnings = listOf("identity resolution failed: ${error.message ?: error::class.java.simpleName}"),
            languageTag = input.languageTag
        )
    }

    private fun StableIdBundle.toContentIdentity(mediaKind: MetadataMediaKind, progress: com.nexio.tv.domain.model.WatchProgress): ContentIdentity {
        val observed = observedIds(progress)
        val ids = ProviderIds(
            imdb = sidecars.imdbId ?: observed.imdb,
            tmdb = canonical.tmdbMovieId ?: observed.tmdb,
            tvdb = canonical.tvdbSeriesId ?: observed.tvdb,
            kitsu = canonical.kitsuAnimeId ?: observed.kitsu,
            trakt = progress.traktShowId?.toString()
        )
        val provider = when {
            mediaKind == MetadataMediaKind.MOVIE && ids.tmdb != null -> ProviderId.TMDB
            ids.tvdb != null -> ProviderId.TVDB
            ids.kitsu != null -> ProviderId.KITSU
            ids.imdb != null -> ProviderId.IMDB
            else -> null
        }
        val id = when (provider) {
            ProviderId.TMDB -> ids.tmdb
            ProviderId.TVDB -> ids.tvdb
            ProviderId.KITSU -> ids.kitsu
            ProviderId.IMDB -> ids.imdb
            else -> progress.contentId
        }
        return ContentIdentity(provider, id, ids)
    }

    private fun observedIds(progress: com.nexio.tv.domain.model.WatchProgress): ProviderIds =
        ProviderIds(
            imdb = listOf(progress.contentId, progress.videoId).firstNotNullOfOrNull { it.substringBefore(":").takeIf { value -> value.matches(Regex("^tt\\d+$")) } },
            tvdb = listOf(progress.contentId, progress.videoId).firstNotNullOfOrNull { it.extractProviderNumber("tvdb") },
            tmdb = listOf(progress.contentId, progress.videoId).firstNotNullOfOrNull { it.extractProviderNumber("tmdb") },
            trakt = progress.traktShowId?.toString()
        )
}

private fun String.extractProviderNumber(prefix: String): String? =
    takeIf { it.startsWith("$prefix:", ignoreCase = true) }
        ?.substringAfter(":")
        ?.substringBefore(":")
        ?.takeIf { it.matches(Regex("^\\d+$")) }
```

- [ ] **Step 4: Run tests**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.data.repository.ContinueWatchingIdentityResolverTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingIdentityResolver.kt app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingIdentityResolverTest.kt
git commit -m "feat: resolve cw identity with legacy fallback"
```

## Task 6: Canonical Merger Preserves All Resume Aliases

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingMerger.kt`
- Create: `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingMergerTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ContentIdentity
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.TrackingProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinueWatchingMergerTest {
    @Test
    fun `merges tvdb local and imdb trakt rows into one record with both aliases`() {
        val merged = ContinueWatchingMerger.merge(
            listOf(
                record("tvdb:393268", "tvdb:393268:2:1", 200L, ContinueWatchingRecord.Source.LOCAL),
                record("tt9794044", "tt9794044:2:1", 100L, ContinueWatchingRecord.Source.REMOTE)
            )
        )

        assertEquals(1, merged.size)
        assertEquals("profile:1:series:tvdb:393268:s2e1", merged.single().identityKey())
        assertTrue("tvdb alias missing", "tvdb:393268|tvdb:393268:2:1|2|1" in merged.single().resumeLookupKeys)
        assertTrue("imdb alias missing", "tt9794044|tt9794044:2:1|2|1" in merged.single().resumeLookupKeys)
        assertEquals("tt9794044:2:1", merged.single().streamFetchIdentity?.videoId)
    }

    @Test
    fun `merged record keeps progress winner as primary resume alias when remote zero progress is newer`() {
        val local = record("tvdb:393268", "tvdb:393268:2:1", 100L, ContinueWatchingRecord.Source.LOCAL)
        val remote = record("tt9794044", "tt9794044:2:1", 200L, ContinueWatchingRecord.Source.REMOTE)

        val merged = ContinueWatchingMerger.merge(listOf(local, remote)).single()

        assertEquals(65_066L, merged.positionMs)
        assertEquals("tvdb:393268|tvdb:393268:2:1|2|1", merged.primaryResumeLookupKey)
    }

    private fun record(contentId: String, videoId: String, updatedAt: Long, source: ContinueWatchingRecord.Source): ContinueWatchingRecord {
        val identity = ContentIdentity(ProviderId.TVDB, "393268", ProviderIds(tvdb = "393268", imdb = "tt9794044"))
        val resume = ResumeIdentity(
            source = if (source == ContinueWatchingRecord.Source.LOCAL) ContinueWatchingSource.LOCAL else ContinueWatchingSource.TRAKT_PLAYBACK,
            contentId = contentId,
            videoId = videoId,
            season = 2,
            episode = 1,
            positionMs = if (source == ContinueWatchingRecord.Source.LOCAL) 65_066L else 0L,
            durationMs = 2_958_656L,
            progressPercent = null,
            lastWatchedMs = updatedAt
        )
        return ContinueWatchingRecord(
            profileId = 1,
            parentId = "series:tvdb:393268",
            contentId = "series:tvdb:393268:s2e1",
            provider = TrackingProvider.TRAKT,
            routingVersion = 1,
            positionMs = resume.positionMs,
            durationMs = 2_958_656L,
            episodeContext = ContinueWatchingRecord.EpisodeContext(2, 1),
            clickTimeDisplayMetadata = null,
            source = source,
            updatedAt = updatedAt,
            canonicalKey = ContinueWatchingCanonicalKey(MetadataMediaKind.SERIES, identity, 2, 1, 1),
            displayIdentity = identity,
            streamFetchIdentity = StreamFetchIdentity("tvdb:393268", "tt9794044:2:1", StreamIdScheme.IMDB_EPISODE, IdentityConfidence.HIGH, listOf("test")),
            resumeIdentities = listOf(resume),
            primaryResumeLookupKey = resume.lookupKey(),
            identityConfidence = IdentityConfidence.HIGH
        )
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.data.repository.ContinueWatchingMergerTest
```

Expected: FAIL with unresolved merger.

- [ ] **Step 3: Add merger**

Create `ContinueWatchingMerger.kt`:

```kotlin
package com.nexio.tv.data.repository

object ContinueWatchingMerger {
    fun merge(records: List<ContinueWatchingRecord>): List<ContinueWatchingRecord> {
        val byKey = linkedMapOf<String, ContinueWatchingRecord>()
        records.sortedByDescending { it.updatedAt }.forEach { record ->
            val key = record.identityKey()
            val existing = byKey[key]
            byKey[key] = if (existing == null) record else mergeRecords(existing, record)
        }
        return byKey.values.sortedByDescending { it.updatedAt }
    }

    private fun mergeRecords(existing: ContinueWatchingRecord, candidate: ContinueWatchingRecord): ContinueWatchingRecord {
        val progressWinner = chooseProgressWinner(existing, candidate)
        val aliases = (existing.resumeIdentities + candidate.resumeIdentities).distinctBy { it.lookupKey() }
        return progressWinner.copy(
            resumeIdentities = aliases,
            primaryResumeLookupKey = progressWinner.primaryResumeLookupKey
                ?: progressWinner.resumeIdentities.firstOrNull()?.lookupKey(),
            streamFetchIdentity = chooseStreamIdentity(existing.streamFetchIdentity, candidate.streamFetchIdentity),
            trackingIdentity = existing.trackingIdentity ?: candidate.trackingIdentity,
            displayIdentity = existing.displayIdentity ?: candidate.displayIdentity,
            identityConfidence = listOf(existing.identityConfidence, candidate.identityConfidence).minBy { it.ordinal },
            identityWarnings = (existing.identityWarnings + candidate.identityWarnings).distinct()
        )
    }

    private fun chooseProgressWinner(existing: ContinueWatchingRecord, candidate: ContinueWatchingRecord): ContinueWatchingRecord {
        if (existing.positionMs <= 0L && candidate.positionMs > 0L) return candidate
        if (candidate.updatedAt > existing.updatedAt && candidate.positionMs > 0L) return candidate
        return existing
    }

    private fun chooseStreamIdentity(existing: StreamFetchIdentity?, candidate: StreamFetchIdentity?): StreamFetchIdentity? {
        if (existing == null) return candidate
        if (candidate == null) return existing
        return if (candidate.confidence.ordinal < existing.confidence.ordinal) candidate else existing
    }
}
```

- [ ] **Step 4: Run tests**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.data.repository.ContinueWatchingMergerTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingMerger.kt app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingMergerTest.kt
git commit -m "feat: merge cw records with resume aliases"
```

## Task 7: Suspend Snapshot Canonicalization

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotServiceMutationTest.kt`

- [ ] **Step 1: Write failing tests**

Append to `ContinueWatchingSnapshotServiceMutationTest`:

```kotlin
    @Test
    fun `canonical snapshot records merge citadel rows and preserve unresolved rows`() = runTest {
        val resolver = mockk<ContinueWatchingIdentityResolver>()
        coEvery { resolver.resolveOrFallback(match { it.progress.contentId == "tvdb:393268" }) } returns citadelRecord("tvdb:393268", "tvdb:393268:2:1")
        coEvery { resolver.resolveOrFallback(match { it.progress.contentId == "tt9794044" }) } returns citadelRecord("tt9794044", "tt9794044:2:1")
        coEvery { resolver.resolveOrFallback(match { it.progress.contentId == "provider:missing" }) } returns unresolvedRecord()
        val service = buildServiceWithContinueWatchingIdentityResolver(resolver)

        val snapshot = service.buildRawSnapshotForTest(
            allProgress = listOf(
                resume("tvdb:393268", "tvdb:393268:2:1", season = 2, episode = 1),
                resume("tt9794044", "tt9794044:2:1", season = 2, episode = 1, source = WatchProgress.SOURCE_TRAKT_PLAYBACK),
                resume("provider:missing", "provider:missing:1:1", season = 1, episode = 1)
            ),
            nextUpEntries = emptyList(),
            traktUpNextEntries = emptyList()
        )

        assertEquals(2, snapshot.records.size)
        assertEquals(1, snapshot.records.count { it.identityKey() == "profile:1:series:tvdb:393268:s2e1" })
        assertEquals(1, snapshot.records.count { it.identityConfidence == IdentityConfidence.LOW })
    }

    @Test
    fun `production constructor requires continue watching identity resolver`() {
        val constructors = ContinueWatchingSnapshotService::class.java.declaredConstructors
        val injectConstructor = constructors.first { constructor ->
            constructor.annotations.any { it.annotationClass.qualifiedName == "javax.inject.Inject" }
        }

        assertTrue(
            injectConstructor.parameterTypes.any { it == ContinueWatchingIdentityResolver::class.java }
        )
    }
```

Add helper records in the test:

```kotlin
    private fun citadelRecord(contentId: String, videoId: String): ContinueWatchingRecord {
        val identity = com.nexio.tv.domain.model.ContentIdentity(
            canonicalProvider = com.nexio.tv.domain.model.ProviderId.TVDB,
            canonicalId = "393268",
            providerIds = com.nexio.tv.domain.model.ProviderIds(tvdb = "393268", imdb = "tt9794044")
        )
        val resume = ResumeIdentity(
            source = ContinueWatchingSource.LOCAL,
            contentId = contentId,
            videoId = videoId,
            season = 2,
            episode = 1,
            positionMs = if (contentId.startsWith("tvdb")) 65_066L else 0L,
            durationMs = 2_958_656L,
            progressPercent = null,
            lastWatchedMs = if (contentId.startsWith("tvdb")) 200L else 100L
        )
        return ContinueWatchingRecord(
            profileId = 1,
            parentId = "series:tvdb:393268",
            contentId = "series:tvdb:393268:s2e1",
            provider = com.nexio.tv.domain.model.TrackingProvider.TRAKT,
            routingVersion = 1,
            positionMs = resume.positionMs,
            durationMs = 2_958_656L,
            episodeContext = ContinueWatchingRecord.EpisodeContext(2, 1),
            clickTimeDisplayMetadata = null,
            source = ContinueWatchingRecord.Source.LOCAL,
            updatedAt = resume.lastWatchedMs,
            canonicalKey = ContinueWatchingCanonicalKey(com.nexio.tv.core.metadata.router.MetadataMediaKind.SERIES, identity, 2, 1, 1),
            displayIdentity = identity,
            streamFetchIdentity = StreamFetchIdentity("tvdb:393268", "tt9794044:2:1", StreamIdScheme.IMDB_EPISODE, IdentityConfidence.HIGH, listOf("test")),
            resumeIdentities = listOf(resume),
            primaryResumeLookupKey = resume.lookupKey(),
            identityConfidence = IdentityConfidence.HIGH
        )
    }

    private fun unresolvedRecord(): ContinueWatchingRecord =
        ContinueWatchingRecord(
            profileId = 1,
            parentId = "series:raw:missing",
            contentId = "series:raw:missing:s1e1",
            provider = com.nexio.tv.domain.model.TrackingProvider.TRAKT,
            routingVersion = 1,
            positionMs = 10L,
            durationMs = 100L,
            episodeContext = ContinueWatchingRecord.EpisodeContext(1, 1),
            clickTimeDisplayMetadata = null,
            source = ContinueWatchingRecord.Source.LOCAL,
            updatedAt = 1L,
            resumeIdentities = listOf(ResumeIdentity(ContinueWatchingSource.LOCAL, "provider:missing", "provider:missing:1:1", 1, 1, 10L, 100L, null, 1L)),
            primaryResumeLookupKey = "provider:missing|provider:missing:1:1|1|1",
            identityConfidence = IdentityConfidence.LOW,
            identityWarnings = listOf("identity resolution failed")
        )
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.data.repository.ContinueWatchingSnapshotServiceMutationTest
```

Expected: FAIL because snapshot has no authoritative records and no suspend test entry point.

- [ ] **Step 3: Add records field to snapshot**

In `ContinueWatchingSnapshot`, add:

```kotlin
    val records: List<ContinueWatchingRecord> = emptyList(),
```

- [ ] **Step 4: Add resolver dependency**

Add `ContinueWatchingIdentityResolver` to the `@Inject` production constructor as a required dependency:

```kotlin
    private val continueWatchingIdentityResolver: ContinueWatchingIdentityResolver,
    private val profileBoundary: com.nexio.tv.core.profile.ProfileBoundary
```

If existing tests need a legacy/no-resolver path, add an `@VisibleForTesting` secondary constructor that supplies a fake low-confidence resolver. Do not make the production `@Inject` dependency nullable, because that would silently keep the old raw-id behavior if DI wiring regresses.

- [ ] **Step 5: Make buildRawSnapshot suspend and pass captured profile context**

Change:

```kotlin
private fun buildRawSnapshot(
```

to:

```kotlin
private suspend fun buildRawSnapshot(
    profileId: Int,
    languageTag: String,
    allProgress: List<WatchProgress>,
    nextUpEntries: List<TrackingNextUpEntry>,
    traktUpNextEntries: List<TrackingNextUpEntry>
): ContinueWatchingSnapshot
```

Add test wrapper:

```kotlin
@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
internal suspend fun buildRawSnapshotForTest(
    profileId: Int = rawSnapshotState.value.profileId,
    languageTag: String = currentLanguageTagForSnapshot(),
    allProgress: List<WatchProgress>,
    nextUpEntries: List<TrackingNextUpEntry>,
    traktUpNextEntries: List<TrackingNextUpEntry>
): ContinueWatchingSnapshot =
    buildRawSnapshot(profileId, languageTag, allProgress, nextUpEntries, traktUpNextEntries)
```

- [ ] **Step 6: Update flow call sites without runBlocking**

The `map` and `combine` transforms are suspend functions. Keep the existing shape and call the suspend function directly:

```kotlin
val languageTag = currentLanguageTagForSnapshot()
snapshot = buildRawSnapshot(
    profileId = profileId,
    languageTag = languageTag,
    allProgress = allProgress,
    nextUpEntries = emptyList(),
    traktUpNextEntries = emptyList()
)
```

and:

```kotlin
val languageTag = currentLanguageTagForSnapshot()
snapshot = buildRawSnapshot(
    profileId = profileId,
    languageTag = languageTag,
    allProgress = allProgress,
    nextUpEntries = nextUpEntries,
    traktUpNextEntries = traktUpNextEntries
)
```

Do not introduce `runBlocking`.

- [ ] **Step 7: Resolve records before returning snapshot**

Inside `buildRawSnapshot`, after `val resumeItems = selectResumeItemsForContinueWatching(allProgress)`, add:

```kotlin
val records = resolveCanonicalResumeRecords(
    profileId = profileId,
    languageTag = languageTag,
    progressItems = resumeItems
)
```

Add methods:

```kotlin
private suspend fun resolveCanonicalResumeRecords(
    profileId: Int,
    languageTag: String,
    progressItems: List<WatchProgress>
): List<ContinueWatchingRecord> {
    val records = progressItems.map { progress ->
        continueWatchingIdentityResolver.resolveOrFallback(
            RawContinueWatchingInput(profileId, progress, languageTag)
        )
    }
    return ContinueWatchingMerger.merge(records)
}

private fun currentLanguageTagForSnapshot(): String =
    profileBoundary.currentLanguageTag().takeIf { it.isNotBlank() } ?: "en"
```

Call `currentLanguageTagForSnapshot()` once at the profile emission boundary and pass that captured value into `buildRawSnapshot`. Do not read active language state again from canonicalization or record mapping code.

Add `records = records` to the returned `ContinueWatchingSnapshot`.

- [ ] **Step 8: Run tests**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.data.repository.ContinueWatchingSnapshotServiceMutationTest
```

Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt app/src/test/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotServiceMutationTest.kt
git commit -m "feat: canonicalize cw snapshots without blocking"
```

## Task 8: UI Renders Canonical Records, Not Raw Resume Rows

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeUiState.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeModels.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatchingTest.kt`

- [ ] **Step 1: Write failing UI test**

Append to `HomeViewModelContinueWatchingTest`:

```kotlin
    @Test
    fun `canonical records render one ui item for local tvdb and trakt imdb aliases`() {
        val local = WatchProgress(
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
            lastWatched = 200L
        )
        val remote = local.copy(
            contentId = "tt9794044",
            videoId = "tt9794044:2:1",
            position = 0L,
            lastWatched = 300L,
            source = WatchProgress.SOURCE_TRAKT_PLAYBACK
        )
        val record = ContinueWatchingRecord(
            profileId = 1,
            parentId = "series:tvdb:393268",
            contentId = "series:tvdb:393268:s2e1",
            provider = com.nexio.tv.domain.model.TrackingProvider.TRAKT,
            routingVersion = 1,
            positionMs = 65_066L,
            durationMs = 2_958_656L,
            episodeContext = ContinueWatchingRecord.EpisodeContext(2, 1),
            clickTimeDisplayMetadata = null,
            source = ContinueWatchingRecord.Source.LOCAL,
            updatedAt = 200L,
            streamFetchIdentity = StreamFetchIdentity("tvdb:393268", "tt9794044:2:1", StreamIdScheme.IMDB_EPISODE, IdentityConfidence.HIGH, listOf("test")),
            resumeIdentities = listOf(local.toResumeIdentity(), remote.toResumeIdentity()),
            primaryResumeLookupKey = local.toResumeIdentity().lookupKey(),
            identityConfidence = IdentityConfidence.HIGH
        )

        val items = buildContinueWatchingItemsForSnapshot(
            snapshot = ContinueWatchingSnapshot(
                resumeItems = listOf(local, remote),
                records = listOf(record)
            ),
            nowMs = 1_000L
        )

        assertEquals(1, items.size)
        val item = items.single() as ContinueWatchingItem.InProgress
        assertEquals("tt9794044:2:1", item.streamFetchVideoId)
        assertEquals("tvdb:393268:2:1", item.progress.videoId)
        assertEquals(65_066L, item.progress.position)
        assertEquals(200L, item.progress.lastWatched)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.ui.screens.home.HomeViewModelContinueWatchingTest
```

Expected: FAIL because there is no record-based builder and UI still maps raw rows.

- [ ] **Step 3: Add UI fields**

In `HomeUiState.kt`, add to `ContinueWatchingItem.InProgress`:

```kotlin
val canonicalKey: String? = null,
val streamFetchVideoId: String? = null
```

Add to `NextUpInfo`:

```kotlin
val canonicalKey: String? = null,
val streamFetchVideoId: String? = null
```

- [ ] **Step 4: Add record-based builder**

In `HomeViewModelContinueWatching.kt`, add an internal function:

```kotlin
internal fun buildContinueWatchingItemsForSnapshot(
    snapshot: ContinueWatchingSnapshot,
    nowMs: Long
): List<ContinueWatchingItem> {
    if (snapshot.records.isNotEmpty()) {
        val mapped = snapshot.records
            .sortedByDescending { it.updatedAt }
            .mapNotNull { record -> record.toContinueWatchingItem(snapshot.displayMetadataByItemKey) }

        return dedupContinueWatchingByProjectedIdentity(mapped) { item ->
            item.canonicalOrContentKey()
        }
    }
    val timeline = buildMixedContinueWatchingTimeline(
        resumeItems = snapshot.resumeItems,
        nextUpItems = snapshot.nextUpItems,
        resumeRef = ::resumeRefForContinueWatching,
        nextUpRef = ::nextUpRefForContinueWatching
    )
    return timeline.map { row ->
        when (row) {
            is ContinueWatchingTimelineRow.Resume -> row.value.toContinueWatchingInProgress(snapshot.displayMetadataByItemKey)
            is ContinueWatchingTimelineRow.NextUp -> row.value.toContinueWatchingNextUp(snapshot.displayMetadataByItemKey, nowMs)
        }
    }.filter { item -> item !is ContinueWatchingItem.NextUp || item.info.hasAired }
}
```

Add helpers:

```kotlin
private fun ContinueWatchingRecord.toContinueWatchingItem(
    displayMetadataByItemKey: Map<String, HomeDisplayMetadata>
): ContinueWatchingItem? {
    val resume = resumeIdentities.firstOrNull { it.lookupKey() == primaryResumeLookupKey }
        ?: resumeIdentities.maxByOrNull { it.lastWatchedMs }
        ?: return null
    val progress = WatchProgress(
        contentId = resume.contentId,
        contentType = if (resume.isEpisode) "series" else "movie",
        name = displayMetadataByItemKey[parentId]?.title ?: resume.contentId,
        poster = displayMetadataByItemKey[parentId]?.poster,
        backdrop = displayMetadataByItemKey[parentId]?.backdrop,
        logo = displayMetadataByItemKey[parentId]?.logo,
        videoId = resume.videoId.orEmpty(),
        season = resume.season,
        episode = resume.episode,
        episodeTitle = null,
        position = positionMs,
        duration = durationMs,
        progressPercent = resume.progressPercent,
        lastWatched = updatedAt
    )
    // P0 uses existing display metadata maps. Packet 2 replaces this with ContinueWatchingDisplayResolver.
    val displayMetadata = displayMetadataByItemKey[contentId] ?: displayMetadataByItemKey[parentId]
    return ContinueWatchingItem.InProgress(
        progress = progress,
        displayMetadata = displayMetadata,
        episodeDescription = displayMetadata?.description,
        episodeImdbRating = displayMetadata?.imdbRating,
        genres = displayMetadata?.genres.orEmpty(),
        releaseInfo = displayMetadata?.releaseInfo,
        canonicalKey = identityKey(),
        streamFetchVideoId = streamFetchIdentity?.videoId
    )
}

private fun ContinueWatchingItem.canonicalOrContentKey(): String =
    when (this) {
        is ContinueWatchingItem.InProgress -> canonicalKey ?: contentId()
        is ContinueWatchingItem.NextUp -> info.canonicalKey ?: contentId()
    }
```

- [ ] **Step 5: Use builder in snapshot processing**

In `processContinueWatchingSnapshot`, replace the raw `timeline` to `items` construction with:

```kotlin
val rawItems = buildContinueWatchingItemsForSnapshot(snapshot, nowMs)
val projectedKeys = try {
    resolveProjectedContinueWatchingIdentityKeys(rawItems, animeSeasonProjectionResolver)
} catch (_: Exception) {
    emptyMap<Int, String>()
}
val items = dedupContinueWatchingByProjectedIdentity(rawItems) { item ->
    val idx = rawItems.indexOfFirst { it === item }
    projectedKeys[idx] ?: item.canonicalOrContentKey()
}
```

- [ ] **Step 6: Use canonical key for Compose item key**

In `ModernHomeModels.kt`, update `continueWatchingItemKey`:

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

- [ ] **Step 7: Run tests**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.ui.screens.home.HomeViewModelContinueWatchingTest
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeUiState.kt app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeModels.kt app/src/test/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatchingTest.kt
git commit -m "fix: render cw rows from canonical records"
```

## Task 9: Route CW Clicks With Stream Fetch Identity

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/navigation/NexioNavHost.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/navigation/ScreenStreamRouteTest.kt`

- [ ] **Step 1: Add route regression test**

Append to `ScreenStreamRouteTest`:

```kotlin
@Test
fun `continue watching route preserves resume id and passes stream fetch id`() {
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

- [ ] **Step 2: Run route test**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.ui.navigation.ScreenStreamRouteTest
```

Expected: PASS if route support already exists.

- [ ] **Step 3: Pass stream id from Continue Watching route builder**

In `NexioNavHost.kt`, inside `buildContinueWatchingStreamRoute`, add to the `InProgress` `Screen.Stream.createRoute(...)` call:

```kotlin
streamVideoId = item.streamFetchVideoId,
```

Add to the `NextUp` call:

```kotlin
streamVideoId = item.info.streamFetchVideoId,
```

- [ ] **Step 4: Run route and home tests**

Run:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.ui.navigation.ScreenStreamRouteTest --tests com.nexio.tv.ui.screens.home.HomeViewModelContinueWatchingTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/navigation/NexioNavHost.kt app/src/test/java/com/nexio/tv/ui/navigation/ScreenStreamRouteTest.kt
git commit -m "fix: use stream fetch id for cw playback"
```

## Task 10: P0 Contract Tests And Verification

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/sync/ProfileSettingsScopeContractTest.kt`
- Create: `review-dossier/2026-05-08-continue-watching-p0-validation.md`

- [ ] **Step 1: Add architecture contract test**

Append to `ProfileSettingsScopeContractTest`:

```kotlin
@Test
fun `continue watching p0 uses canonical records as ui authority`() {
    val snapshotSource = File("app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt").readText()
    val homeSource = File("app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt").readText()
    val navSource = File("app/src/main/java/com/nexio/tv/ui/navigation/NexioNavHost.kt").readText()

    assertTrue(snapshotSource.contains("private suspend fun buildRawSnapshot"))
    assertTrue(!snapshotSource.contains("runBlockingSafelyForSnapshot"))
    assertTrue(snapshotSource.contains("ContinueWatchingMerger.merge"))
    assertTrue(homeSource.contains("snapshot.records.isNotEmpty()"))
    assertTrue(homeSource.contains("buildContinueWatchingItemsForSnapshot"))
    assertTrue(navSource.contains("streamVideoId = item.streamFetchVideoId"))
}
```

- [ ] **Step 2: Run focused P0 tests**

Run:

```bash
./gradlew testDebugUnitTest \
  --tests com.nexio.tv.core.metadata.router.MetadataDepthIdentityTest \
  --tests com.nexio.tv.data.repository.ContinueWatchingIdentityModelsTest \
  --tests com.nexio.tv.data.repository.ContinueWatchingItemKeysTest \
  --tests com.nexio.tv.data.repository.StreamFetchIdentityResolverTest \
  --tests com.nexio.tv.data.repository.ContinueWatchingRecordTest \
  --tests com.nexio.tv.data.repository.ContinueWatchingIdentityResolverTest \
  --tests com.nexio.tv.data.repository.ContinueWatchingMergerTest \
  --tests com.nexio.tv.data.repository.ContinueWatchingSnapshotServiceMutationTest \
  --tests com.nexio.tv.ui.screens.home.HomeViewModelContinueWatchingTest \
  --tests com.nexio.tv.ui.navigation.ScreenStreamRouteTest \
  --tests com.nexio.tv.sync.ProfileSettingsScopeContractTest
```

Expected: PASS.

- [ ] **Step 3: Run impacted existing suites**

Run:

```bash
./gradlew testDebugUnitTest \
  --tests com.nexio.tv.data.repository.ContinueWatchingMetadataRouterTest \
  --tests com.nexio.tv.data.repository.ContinueWatchingTimelineTest \
  --tests com.nexio.tv.data.repository.ContinueWatchingTimelineAirDateTest \
  --tests com.nexio.tv.data.repository.ContinueWatchingSnapshotServiceProfileBoundaryTest \
  --tests com.nexio.tv.ui.screens.home.HomeViewModelContinueWatchingProfileScopedTest \
  --tests com.nexio.tv.ui.screens.home.HomeViewModelContinueWatchingProjectionTest \
  --tests com.nexio.tv.ui.screens.stream.StreamScreenViewModelDeterministicAutoplayTest
```

Expected: PASS.

- [ ] **Step 4: Save validation note**

Create `review-dossier/2026-05-08-continue-watching-p0-validation.md`:

```markdown
# Continue Watching P0 Validation

Date: 2026-05-08

## Acceptance

- Citadel local TVDB progress resolves canonical key `profile:1:series:tvdb:393268:s2e1`.
- Citadel local TVDB progress gets stream fetch id `tt9794044:2:1`.
- Citadel Trakt IMDb progress resolves to the same canonical key.
- Home Continue Watching renders from `snapshot.records`.
- Local TVDB and Trakt IMDb Citadel aliases render as one card.
- Click route preserves resume `videoId=tvdb:393268:2:1`.
- Click route passes `streamVideoId=tt9794044:2:1`.
- Identity resolution failure preserves a low-confidence legacy row.
- Snapshot canonicalization uses suspend transforms and no `runBlocking`.
- CW identity resolution uses `MetadataDepth.IDENTITY`, not `DETAIL_CORE`.

## Test Commands

Focused P0 command:

```bash
./gradlew testDebugUnitTest --tests com.nexio.tv.core.metadata.router.MetadataDepthIdentityTest --tests com.nexio.tv.data.repository.ContinueWatchingIdentityModelsTest --tests com.nexio.tv.data.repository.ContinueWatchingItemKeysTest --tests com.nexio.tv.data.repository.StreamFetchIdentityResolverTest --tests com.nexio.tv.data.repository.ContinueWatchingRecordTest --tests com.nexio.tv.data.repository.ContinueWatchingIdentityResolverTest --tests com.nexio.tv.data.repository.ContinueWatchingMergerTest --tests com.nexio.tv.data.repository.ContinueWatchingSnapshotServiceMutationTest --tests com.nexio.tv.ui.screens.home.HomeViewModelContinueWatchingTest --tests com.nexio.tv.ui.navigation.ScreenStreamRouteTest --tests com.nexio.tv.sync.ProfileSettingsScopeContractTest
```

Expected result: PASS.
```

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/com/nexio/tv/sync/ProfileSettingsScopeContractTest.kt review-dossier/2026-05-08-continue-watching-p0-validation.md
git commit -m "test: validate cw p0 shared identity playback"
```

## Final Verification

- [ ] **Step 1: Run all P0 and impacted tests**

Run:

```bash
./gradlew testDebugUnitTest \
  --tests com.nexio.tv.core.metadata.router.MetadataDepthIdentityTest \
  --tests com.nexio.tv.data.repository.ContinueWatchingIdentityModelsTest \
  --tests com.nexio.tv.data.repository.ContinueWatchingItemKeysTest \
  --tests com.nexio.tv.data.repository.StreamFetchIdentityResolverTest \
  --tests com.nexio.tv.data.repository.ContinueWatchingRecordTest \
  --tests com.nexio.tv.data.repository.ContinueWatchingIdentityResolverTest \
  --tests com.nexio.tv.data.repository.ContinueWatchingMergerTest \
  --tests com.nexio.tv.data.repository.ContinueWatchingSnapshotServiceMutationTest \
  --tests com.nexio.tv.data.repository.ContinueWatchingMetadataRouterTest \
  --tests com.nexio.tv.data.repository.ContinueWatchingTimelineTest \
  --tests com.nexio.tv.data.repository.ContinueWatchingTimelineAirDateTest \
  --tests com.nexio.tv.data.repository.ContinueWatchingSnapshotServiceProfileBoundaryTest \
  --tests com.nexio.tv.ui.screens.home.HomeViewModelContinueWatchingTest \
  --tests com.nexio.tv.ui.screens.home.HomeViewModelContinueWatchingProfileScopedTest \
  --tests com.nexio.tv.ui.screens.home.HomeViewModelContinueWatchingProjectionTest \
  --tests com.nexio.tv.ui.navigation.ScreenStreamRouteTest \
  --tests com.nexio.tv.ui.screens.stream.StreamScreenViewModelDeterministicAutoplayTest \
  --tests com.nexio.tv.sync.ProfileSettingsScopeContractTest
```

Expected: PASS.

- [ ] **Step 2: Check for prohibited P0 patterns**

Run:

```bash
rg -n "runBlockingSafelyForSnapshot|distinctBy \\{ it\\.contentId \\}|MetadataDepth\\.DETAIL_CORE" app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingIdentityResolver.kt app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt
```

Expected: no matches for `runBlockingSafelyForSnapshot`; no CW record UI path deduping by raw `contentId`; no `DETAIL_CORE` inside `ContinueWatchingIdentityResolver`.

- [ ] **Step 3: Full unit suite before merge**

Run:

```bash
./gradlew testDebugUnitTest
```

Expected: PASS.

## Self-Review

Spec coverage:

- Canonical records authoritative for UI rows: Task 8 uses `snapshot.records.isNotEmpty()` and renders one item from merged records.
- No `runBlocking`: Task 7 makes `buildRawSnapshot` suspend and calls it from suspend Flow transforms.
- Identity failure preserves rows: Task 5 returns low-confidence legacy records.
- Identity-only stable-id resolution: Task 1 adds `MetadataDepth.IDENTITY`; Task 5 uses it.
- Stream resolver scoped to Phase 0: Task 3 documents default Stremio IMDb behavior and unresolved fallback.
- Raw key collision prevention: Task 2 hashes raw fallback ids.
- P0 split: display localization, artwork projection, mirror cleanup, next-up discovery, and expanded traces are excluded from this packet.

Placeholder scan:

- This plan uses concrete file paths, commands, expected results, and code snippets.
- The plan contains no prohibited placeholder phrases.

Type consistency:

- `ContinueWatchingRecord.resumeIdentities` and `resumeLookupKeys` are defined before merger and UI tasks.
- `ContinueWatchingIdentityResolver.resolveOrFallback` returns a `ContinueWatchingRecord`, which snapshot canonicalization consumes directly.
- UI route uses `ContinueWatchingItem.InProgress.streamFetchVideoId`, populated only from canonical records.
