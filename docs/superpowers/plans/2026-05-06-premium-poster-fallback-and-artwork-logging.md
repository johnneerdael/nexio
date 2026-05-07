# Premium Poster Fallback And Artwork Logging Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix premium poster cards so failed RPDB/Top Posters materialization falls back to the original non-premium poster, including after app restart, and expose artwork materialization events through the existing integration runtime logcat toggle.

**Architecture:** Premium poster adapters must pass a safe non-premium poster URL from the route source context into `PosterRatingsUrlResolver`, so the artwork router persists that fallback as a rejected primary candidate when premium artwork wins. `ArtworkAssetRepository` already emits `artwork.*` trace events; route and format those events through `LogcatTraceChannel.INT_RUNTIME` so the existing `logcat_int_runtime_enabled` toggle controls them.

**Tech Stack:** Android Kotlin, Hilt-injected metadata adapters, artwork router/decision cache, Robolectric/JUnit, Android logcat trace toggles.

---

## Current Failure Shape

- Field routing works: `metadata.field_selected` shows `field=POSTER selectedProvider=RPDB sourceRole=ARTWORK rejectedCount=2`.
- Rendering fails later: UI shows `MonochromePosterPlaceholder`, meaning Coil/artwork loading reached an error state rather than using a real fallback image.
- The restart symptom is explained by durable state: persisted home metadata can carry `nexio-artwork://decision/...`, and `ArtworkAssetRepository` can only retry fallback candidates present in `ArtworkDecision.rejectedCandidates`.
- Root cause: `RpdbMetadataProviderAdapter` and `TopPostersMetadataProviderAdapter` call `PosterRatingsUrlResolver.resolvePosterArtworkString(...)` without `fallbackPosterUrl`, so the router cannot persist the non-premium poster as a fallback candidate for failed premium materialization.
- Diagnostic gap: `ArtworkAssetRepository` emits `artwork.decision_lookup`, `artwork.decision_missing`, `artwork.asset_materialized`, and `artwork.fallback_materialized`, but `LogcatTraceChannel.forEventType()` currently drops `artwork.*` events.

## File Structure

- Create `app/src/main/java/com/nexio/tv/data/integration/posters/PremiumPosterFallbackUrl.kt`
  - Owns safe fallback URL extraction from `MetadataRoute.sourceContext.addonMetadata`.
  - Rejects internal refs, blank values, non-http URLs, and premium provider URLs.
- Create `app/src/test/java/com/nexio/tv/data/integration/posters/PremiumPosterFallbackUrlTest.kt`
  - Unit tests the extraction and rejection rules directly.
- Modify `app/src/main/java/com/nexio/tv/data/integration/posters/RpdbMetadataProviderAdapter.kt`
  - Pass `fallbackPosterUrl = route.nonPremiumPosterFallbackUrl()` to `PosterRatingsUrlResolver`.
- Modify `app/src/main/java/com/nexio/tv/data/integration/posters/TopPostersMetadataProviderAdapter.kt`
  - Pass `fallbackPosterUrl = route.nonPremiumPosterFallbackUrl()` to `PosterRatingsUrlResolver`.
- Modify `app/src/test/java/com/nexio/tv/data/integration/posters/PremiumPosterMetadataProviderAdapterStableIdTest.kt`
  - Assert RPDB and Top Posters decisions persist non-premium primary fallback rejected candidates.
  - Assert raw premium provider URLs are not persisted as fallback candidates.
- Modify `app/src/main/java/com/nexio/tv/core/trace/LogcatTraceChannel.kt`
  - Route `artwork.*` events to `LogcatTraceChannel.INT_RUNTIME`.
- Modify `app/src/main/java/com/nexio/tv/core/trace/LogcatRuntimeTraceSink.kt`
  - Add curated fields for artwork decision lookup, missing decision, materialization, and fallback materialization events.
- Modify `app/src/test/java/com/nexio/tv/core/trace/LogcatTraceChannelTest.kt`
  - Prove `artwork.*` events use the existing integration runtime channel.
- Modify `app/src/test/java/com/nexio/tv/core/trace/LogcatRuntimeTraceSinkTest.kt`
  - Prove artwork logs are emitted to `Nexio.IntRuntime` only when `INT_RUNTIME` is enabled.

## Task 1: Add Failing Tests For Artwork Logcat Routing

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/core/trace/LogcatTraceChannelTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/trace/LogcatRuntimeTraceSinkTest.kt`

- [ ] **Step 1: Add channel mapping test**

Add this test to `LogcatTraceChannelTest` after the existing `trace body_sample maps to INT_RUNTIME` test:

```kotlin
    @Test
    fun `artwork materialization events map to INT_RUNTIME`() {
        assertEquals(LogcatTraceChannel.INT_RUNTIME, LogcatTraceChannel.forEventType("artwork.decision_lookup"))
        assertEquals(LogcatTraceChannel.INT_RUNTIME, LogcatTraceChannel.forEventType("artwork.decision_missing"))
        assertEquals(LogcatTraceChannel.INT_RUNTIME, LogcatTraceChannel.forEventType("artwork.asset_materialized"))
        assertEquals(LogcatTraceChannel.INT_RUNTIME, LogcatTraceChannel.forEventType("artwork.fallback_materialized"))
    }
```

- [ ] **Step 2: Add log formatting and gating tests**

Add these tests to `LogcatRuntimeTraceSinkTest` after the existing `cache_decision event writes to IntRuntime tag with cache proof fields for all decisions` test:

```kotlin
    @Test
    fun `artwork materialization event writes to IntRuntime tag with curated fields`() {
        val sink = LogcatRuntimeTraceSink(allEnabled)
        sink.emit(envelope("artwork.asset_materialized", mapOf(
            "decisionKey" to "decision-rpdb-550",
            "assetKey" to "asset-tmdb-fallback",
            "provider" to "TMDB",
            "imageType" to "POSTER",
            "cacheDecision" to "FALLBACK_MATERIALIZED",
            "networkExecuted" to true,
            "success" to true
        )))

        val logs = ShadowLog.getLogsForTag("Nexio.IntRuntime")
        assertEquals(1, logs.size)
        val msg = logs.first().msg
        assertTrue(msg.contains("t=artwork.asset_materialized"))
        assertTrue(msg.contains("decisionKey=decision-rpdb-550"))
        assertTrue(msg.contains("assetKey=asset-tmdb-fallback"))
        assertTrue(msg.contains("provider=TMDB"))
        assertTrue(msg.contains("imageType=POSTER"))
        assertTrue(msg.contains("cacheDecision=FALLBACK_MATERIALIZED"))
        assertTrue(msg.contains("networkExecuted=true"))
        assertTrue(msg.contains("success=true"))
    }

    @Test
    fun `artwork fallback materialized event writes to IntRuntime tag with fallback provider`() {
        val sink = LogcatRuntimeTraceSink(allEnabled)
        sink.emit(envelope("artwork.fallback_materialized", mapOf(
            "decisionKey" to "decision-rpdb-550",
            "fallbackProvider" to "TMDB",
            "assetKey" to "asset-tmdb-fallback"
        )))

        val logs = ShadowLog.getLogsForTag("Nexio.IntRuntime")
        assertEquals(1, logs.size)
        val msg = logs.first().msg
        assertTrue(msg.contains("t=artwork.fallback_materialized"))
        assertTrue(msg.contains("decisionKey=decision-rpdb-550"))
        assertTrue(msg.contains("fallbackProvider=TMDB"))
        assertTrue(msg.contains("assetKey=asset-tmdb-fallback"))
    }

    @Test
    fun `disabled integration runtime channel suppresses artwork logcat events`() {
        val onlyMeta = object : LogcatChannelGate {
            override fun isEnabled(channel: LogcatTraceChannel): Boolean =
                channel == LogcatTraceChannel.META_ROUTE
        }
        val sink = LogcatRuntimeTraceSink(onlyMeta)

        sink.emit(envelope("artwork.asset_materialized", mapOf(
            "decisionKey" to "decision-rpdb-550",
            "success" to false
        )))

        assertEquals(0, ShadowLog.getLogsForTag("Nexio.IntRuntime").size)
    }
```

- [ ] **Step 3: Run trace tests and verify they fail**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.core.trace.LogcatTraceChannelTest" --tests "com.nexio.tv.core.trace.LogcatRuntimeTraceSinkTest"
```

Expected: FAIL. The channel test should show `artwork.*` maps to `null`, and the sink tests should have no `Nexio.IntRuntime` entries for artwork events.

## Task 2: Route Artwork Events Through Existing Integration Runtime Toggle

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/trace/LogcatTraceChannel.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/trace/LogcatRuntimeTraceSink.kt`
- Test: `app/src/test/java/com/nexio/tv/core/trace/LogcatTraceChannelTest.kt`
- Test: `app/src/test/java/com/nexio/tv/core/trace/LogcatRuntimeTraceSinkTest.kt`

- [ ] **Step 1: Route artwork events to INT_RUNTIME**

In `LogcatTraceChannel.forEventType`, add the `artwork.*` branch with the existing runtime channels:

```kotlin
            eventType.startsWith("runtime.") -> INT_RUNTIME
            eventType.startsWith("http.") -> INT_RUNTIME
            eventType.startsWith("artwork.") -> INT_RUNTIME
            eventType == "trace.body_sample" -> INT_RUNTIME
```

- [ ] **Step 2: Add curated artwork log fields**

In `LogcatRuntimeTraceSink.curatedFields`, add these cases before the existing `runtime.trailer_playback_source` case:

```kotlin
        "artwork.decision_lookup" -> linkedMapOf(
            "decisionKey" to payload["decisionKey"],
            "found" to payload["found"]
        )
        "artwork.decision_missing" -> linkedMapOf(
            "decisionKey" to payload["decisionKey"]
        )
        "artwork.asset_materialized" -> linkedMapOf(
            "decisionKey" to payload["decisionKey"],
            "assetKey" to payload["assetKey"],
            "provider" to payload["provider"],
            "imageType" to payload["imageType"],
            "cacheDecision" to payload["cacheDecision"],
            "networkExecuted" to payload["networkExecuted"],
            "success" to payload["success"]
        )
        "artwork.fallback_materialized" -> linkedMapOf(
            "decisionKey" to payload["decisionKey"],
            "fallbackProvider" to payload["fallbackProvider"],
            "assetKey" to payload["assetKey"]
        )
```

- [ ] **Step 3: Run trace tests and verify they pass**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.core.trace.LogcatTraceChannelTest" --tests "com.nexio.tv.core.trace.LogcatRuntimeTraceSinkTest"
```

Expected: PASS. Artwork events write to `Nexio.IntRuntime` when the integration runtime toggle is enabled, and disabled `INT_RUNTIME` suppresses them.

- [ ] **Step 4: Commit logging instrumentation**

Run:

```bash
git add app/src/main/java/com/nexio/tv/core/trace/LogcatTraceChannel.kt app/src/main/java/com/nexio/tv/core/trace/LogcatRuntimeTraceSink.kt app/src/test/java/com/nexio/tv/core/trace/LogcatTraceChannelTest.kt app/src/test/java/com/nexio/tv/core/trace/LogcatRuntimeTraceSinkTest.kt
git commit -m "chore: route artwork materialization logs through runtime toggle"
```

## Task 3: Add Failing Tests For Safe Non-Premium Fallback URL Extraction

**Files:**
- Create: `app/src/test/java/com/nexio/tv/data/integration/posters/PremiumPosterFallbackUrlTest.kt`

- [ ] **Step 1: Create helper behavior tests**

Create `PremiumPosterFallbackUrlTest.kt` with this content:

```kotlin
package com.nexio.tv.data.integration.posters

import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.metadata.router.MetadataDecisionReason
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.core.metadata.router.MetadataRouteTrace
import com.nexio.tv.core.metadata.router.MetadataSourceContext
import com.nexio.tv.domain.model.HomeDisplayMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PremiumPosterFallbackUrlTest {

    @Test
    fun `route returns safe remote source poster as premium fallback`() {
        val fallback = "https://image.tmdb.org/t/p/w500/fallback.jpg"
        val route = route(HomeDisplayMetadata(poster = fallback))

        assertEquals(fallback, route.nonPremiumPosterFallbackUrl())
    }

    @Test
    fun `route uses raw poster when display poster is an internal artwork ref`() {
        val fallback = "https://image.tmdb.org/t/p/w500/raw-fallback.jpg"
        val route = route(
            HomeDisplayMetadata(
                poster = fallback,
                artwork = ArtworkBundle(
                    poster = ArtworkDisplayRef.LegacyString(
                        value = "nexio-artwork://decision/premium-decision",
                        imageType = ArtworkType.POSTER,
                        trace = ArtworkTrace.empty()
                    )
                )
            )
        )

        assertEquals(fallback, route.nonPremiumPosterFallbackUrl())
    }

    @Test
    fun `route rejects premium provider urls as fallback candidates`() {
        assertNull(route(HomeDisplayMetadata(
            poster = "https://api.ratingposterdb.com/rpdb-key/imdb/poster-default/tt0137523.jpg"
        )).nonPremiumPosterFallbackUrl())

        assertNull(route(HomeDisplayMetadata(
            poster = "https://api.top-posters.com/top-key/tmdb/poster/movie-550.jpg"
        )).nonPremiumPosterFallbackUrl())
    }

    @Test
    fun `route rejects blank internal and non remote fallback candidates`() {
        assertNull(route(HomeDisplayMetadata(poster = " ")).nonPremiumPosterFallbackUrl())
        assertNull(route(HomeDisplayMetadata(poster = "nexio-artwork://decision/already-internal")).nonPremiumPosterFallbackUrl())
        assertNull(route(HomeDisplayMetadata(poster = "file:///sdcard/poster.jpg")).nonPremiumPosterFallbackUrl())
    }

    private fun route(metadata: HomeDisplayMetadata?): MetadataRoute =
        MetadataRoute(
            provider = MetadataPrimaryProvider.TMDB,
            parentId = "tmdb:550",
            mediaKind = MetadataMediaKind.MOVIE,
            reason = MetadataDecisionReason.PROVIDER_NATIVE_DIRECT,
            sourceContext = MetadataSourceContext(addonMetadata = metadata),
            targetIds = mapOf(MetadataPrimaryProvider.TMDB to "tmdb:550"),
            trace = listOf(
                MetadataRouteTrace(
                    reason = MetadataDecisionReason.PROVIDER_NATIVE_DIRECT,
                    detail = "test route"
                )
            )
        )
}
```

- [ ] **Step 2: Run helper tests and verify they fail**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.data.integration.posters.PremiumPosterFallbackUrlTest"
```

Expected: FAIL with unresolved reference `nonPremiumPosterFallbackUrl`.

## Task 4: Implement Safe Non-Premium Fallback URL Extraction

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/integration/posters/PremiumPosterFallbackUrl.kt`
- Test: `app/src/test/java/com/nexio/tv/data/integration/posters/PremiumPosterFallbackUrlTest.kt`

- [ ] **Step 1: Create the extraction helper**

Create `PremiumPosterFallbackUrl.kt` with this content:

```kotlin
package com.nexio.tv.data.integration.posters

import com.nexio.tv.core.metadata.router.MetadataRoute
import java.net.URI
import java.util.Locale

internal fun MetadataRoute.nonPremiumPosterFallbackUrl(): String? {
    val metadata = sourceContext.addonMetadata ?: return null
    return listOf(metadata.displayPoster, metadata.poster)
        .asSequence()
        .mapNotNull { value -> value?.trim()?.takeIf { it.isNotBlank() } }
        .firstOrNull(::isNonPremiumRemoteArtworkUrl)
}

private fun isNonPremiumRemoteArtworkUrl(value: String): Boolean {
    val uri = runCatching { URI(value) }.getOrNull() ?: return false
    val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
    if (scheme != "http" && scheme != "https") return false

    val host = uri.host?.lowercase(Locale.ROOT) ?: return false
    if (host.endsWith("ratingposterdb.com")) return false
    if (host.endsWith("top-posters.com")) return false

    return true
}
```

- [ ] **Step 2: Run helper tests and verify they pass**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.data.integration.posters.PremiumPosterFallbackUrlTest"
```

Expected: PASS.

## Task 5: Add Failing Adapter Tests For Persisted Premium Fallback Candidates

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/data/integration/posters/PremiumPosterMetadataProviderAdapterStableIdTest.kt`

- [ ] **Step 1: Add imports**

Add these imports near the existing artwork and domain imports:

```kotlin
import com.nexio.tv.core.artwork.ArtworkProviderId
import com.nexio.tv.core.artwork.ArtworkSourceRole
import com.nexio.tv.domain.model.HomeDisplayMetadata
```

- [ ] **Step 2: Add RPDB fallback persistence test**

Add this test after `rpdb candidate ref uses stable tmdb target id instead of route parent id`:

```kotlin
    @Test
    fun `rpdb decision persists non premium source poster as primary fallback candidate`() = runTest {
        val cache = InMemoryArtworkDecisionCache()
        val adapter = RpdbMetadataProviderAdapter(
            posterResolver = resolver(
                PosterRatingsSettings(
                    rpdbEnabled = true,
                    rpdbApiKey = "rpdb-key"
                ),
                cache
            )
        )
        val route = route(
            provider = MetadataPrimaryProvider.TMDB,
            mediaKind = MetadataMediaKind.MOVIE,
            parentId = "catalog-row-item-99",
            targetIds = mapOf(MetadataPrimaryProvider.TMDB to "tmdb:550"),
            sourceMetadata = HomeDisplayMetadata(
                poster = "https://image.tmdb.org/t/p/w500/fallback.jpg"
            )
        )

        val result = adapter.execute(
            route = route,
            step = posterStep(MetadataPrimaryProvider.RPDB, PosterApiShapes.RPDB_POSTER_TEMPLATE)
        )

        val value = result.candidate?.fields?.get(ResolvedField.POSTER)?.value as? String
        assertInternalArtworkRef(value)
        val decision = cache.get(decisionKeyFromRef(value!!))
        val rejected = decision!!.rejectedCandidates.single { it.sourceRole == ArtworkSourceRole.PRIMARY }
        assertEquals(ArtworkProviderId.RuntimeProvider(IntegrationProvider.TMDB), rejected.provider)
        assertEquals("premium_artwork_provider_precedence", rejected.reason)
        assertNotNull(rejected.sourceHash)
        assertTrue(rejected.redactedSourceForTrace!!.contains("image.tmdb.org"))
        assertNull(rejected.providerTemplate)
    }
```

- [ ] **Step 3: Add Top Posters fallback persistence test**

Add this test after `top posters candidate ref uses provider native target id instead of route parent id`:

```kotlin
    @Test
    fun `top posters decision persists non premium source poster as primary fallback candidate`() = runTest {
        val cache = InMemoryArtworkDecisionCache()
        val adapter = TopPostersMetadataProviderAdapter(
            posterResolver = resolver(
                PosterRatingsSettings(
                    topPostersEnabled = true,
                    topPostersApiKey = "top-key"
                ),
                cache
            )
        )
        val route = route(
            provider = MetadataPrimaryProvider.TMDB,
            mediaKind = MetadataMediaKind.MOVIE,
            parentId = "catalog-row-item-42",
            targetIds = mapOf(
                MetadataPrimaryProvider.TMDB to "tmdb:550",
                MetadataPrimaryProvider.IMDB to "tt0137523"
            ),
            sourceMetadata = HomeDisplayMetadata(
                poster = "https://image.tmdb.org/t/p/w500/fallback.jpg"
            )
        )

        val result = adapter.execute(
            route = route,
            step = posterStep(MetadataPrimaryProvider.TOP_POSTERS, PosterApiShapes.TOP_POSTERS_POSTER_TEMPLATE)
        )

        val value = result.candidate?.fields?.get(ResolvedField.POSTER)?.value as? String
        assertInternalArtworkRef(value)
        val decision = cache.get(decisionKeyFromRef(value!!))
        val rejected = decision!!.rejectedCandidates.single { it.sourceRole == ArtworkSourceRole.PRIMARY }
        assertEquals(ArtworkProviderId.RuntimeProvider(IntegrationProvider.TMDB), rejected.provider)
        assertEquals("premium_artwork_provider_precedence", rejected.reason)
        assertNotNull(rejected.sourceHash)
        assertTrue(rejected.redactedSourceForTrace!!.contains("image.tmdb.org"))
        assertNull(rejected.providerTemplate)
    }
```

- [ ] **Step 4: Add premium URL rejection test**

Add this test near the other RPDB adapter tests:

```kotlin
    @Test
    fun `rpdb adapter does not persist raw premium provider url as fallback candidate`() = runTest {
        val cache = InMemoryArtworkDecisionCache()
        val adapter = RpdbMetadataProviderAdapter(
            posterResolver = resolver(
                PosterRatingsSettings(
                    rpdbEnabled = true,
                    rpdbApiKey = "rpdb-key"
                ),
                cache
            )
        )
        val route = route(
            provider = MetadataPrimaryProvider.TMDB,
            mediaKind = MetadataMediaKind.MOVIE,
            parentId = "catalog-row-item-99",
            targetIds = mapOf(
                MetadataPrimaryProvider.TMDB to "tmdb:550",
                MetadataPrimaryProvider.IMDB to "tt0137523"
            ),
            sourceMetadata = HomeDisplayMetadata(
                poster = "https://api.ratingposterdb.com/rpdb-key/imdb/poster-default/tt0137523.jpg"
            )
        )

        val result = adapter.execute(
            route = route,
            step = posterStep(MetadataPrimaryProvider.RPDB, PosterApiShapes.RPDB_POSTER_TEMPLATE)
        )

        val value = result.candidate?.fields?.get(ResolvedField.POSTER)?.value as? String
        assertInternalArtworkRef(value)
        val decision = cache.get(decisionKeyFromRef(value!!))
        assertTrue(decision!!.rejectedCandidates.none { it.sourceRole == ArtworkSourceRole.PRIMARY })
    }
```

- [ ] **Step 5: Extend the route test helper**

Replace the existing `route(...)` helper signature and `sourceContext` assignment with this version:

```kotlin
    private fun route(
        provider: MetadataPrimaryProvider,
        mediaKind: MetadataMediaKind,
        parentId: String,
        targetIds: Map<MetadataPrimaryProvider, String>,
        sourceMetadata: HomeDisplayMetadata? = null
    ): MetadataRoute = MetadataRoute(
        provider = provider,
        parentId = parentId,
        mediaKind = mediaKind,
        reason = MetadataDecisionReason.PROVIDER_NATIVE_DIRECT,
        sourceContext = MetadataSourceContext(addonMetadata = sourceMetadata),
        targetIds = targetIds,
        trace = listOf(
            MetadataRouteTrace(
                reason = MetadataDecisionReason.PROVIDER_NATIVE_DIRECT,
                detail = "test route"
            )
        )
    )
```

- [ ] **Step 6: Run adapter tests and verify they fail**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.data.integration.posters.PremiumPosterMetadataProviderAdapterStableIdTest"
```

Expected: FAIL. The new fallback persistence tests should fail because the adapters still do not pass `fallbackPosterUrl` to the resolver.

## Task 6: Wire Safe Fallback URLs Into Premium Poster Adapters

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/integration/posters/RpdbMetadataProviderAdapter.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/integration/posters/TopPostersMetadataProviderAdapter.kt`
- Test: `app/src/test/java/com/nexio/tv/data/integration/posters/PremiumPosterMetadataProviderAdapterStableIdTest.kt`

- [ ] **Step 1: Update RPDB adapter resolver call**

In `RpdbMetadataProviderAdapter.execute`, replace the resolver call with:

```kotlin
            posterResolver.resolvePosterArtworkString(
                settings = settings,
                providerIds = route.premiumPosterProviderIds(),
                mediaKind = route.mediaKind,
                ownerKey = ArtworkOwnerKey.CanonicalContent(stableContentId),
                fallbackPosterUrl = route.nonPremiumPosterFallbackUrl()
            )
```

- [ ] **Step 2: Update Top Posters adapter resolver call**

In `TopPostersMetadataProviderAdapter.execute`, replace the resolver call with:

```kotlin
            posterResolver.resolvePosterArtworkString(
                settings = settings,
                providerIds = route.premiumPosterProviderIds(),
                mediaKind = route.mediaKind,
                ownerKey = ArtworkOwnerKey.CanonicalContent(stableContentId),
                fallbackPosterUrl = route.nonPremiumPosterFallbackUrl()
            )
```

- [ ] **Step 3: Run helper and adapter tests**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.data.integration.posters.PremiumPosterFallbackUrlTest" --tests "com.nexio.tv.data.integration.posters.PremiumPosterMetadataProviderAdapterStableIdTest"
```

Expected: PASS. Decisions created by both premium adapters now include a persisted primary fallback candidate when the route has a safe source poster.

- [ ] **Step 4: Run restart fallback regression**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.core.artwork.ArtworkAssetRepositoryTest"
```

Expected: PASS. This keeps the existing proof that persisted rejected fallback candidates can materialize after restart when the selected premium provider fails.

- [ ] **Step 5: Commit fallback fix**

Run:

```bash
git add app/src/main/java/com/nexio/tv/data/integration/posters/PremiumPosterFallbackUrl.kt app/src/main/java/com/nexio/tv/data/integration/posters/RpdbMetadataProviderAdapter.kt app/src/main/java/com/nexio/tv/data/integration/posters/TopPostersMetadataProviderAdapter.kt app/src/test/java/com/nexio/tv/data/integration/posters/PremiumPosterFallbackUrlTest.kt app/src/test/java/com/nexio/tv/data/integration/posters/PremiumPosterMetadataProviderAdapterStableIdTest.kt
git commit -m "fix: preserve non-premium poster fallback for premium artwork"
```

## Task 7: Regression Suite And Device Verification

**Files:**
- Verify: `app/src/main/java/com/nexio/tv/core/poster/PosterRatingsUrlResolver.kt`
- Verify: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRepository.kt`
- Verify: `app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt`

- [ ] **Step 1: Run focused premium poster, artwork, and trace tests**

Run:

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.data.integration.posters.*" --tests "com.nexio.tv.core.poster.PosterRatingsUrlResolverTest" --tests "com.nexio.tv.core.artwork.ArtworkAssetRepositoryTest" --tests "com.nexio.tv.core.trace.*"
```

Expected: PASS. This covers resolver candidate construction, adapter decision persistence, restart fallback materialization, and logcat routing.

- [ ] **Step 2: Run the default debug unit test suite if focused tests pass**

Run:

```bash
./gradlew testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 3: Verify logcat behavior on device**

On the device, enable the existing troubleshooting toggle for integration runtime logcat output. Then run:

```bash
adb -s 192.168.50.98:5555 logcat -c
adb -s 192.168.50.98:5555 logcat -s Nexio.MetaRoute:I Nexio.IntRuntime:I '*:S'
```

Expected while home hydrates a premium poster:

```text
Nexio.MetaRoute: seq=... t=metadata.field_selected ... field=POSTER selectedProvider=RPDB sourceRole=ARTWORK ... rejectedCount=...
Nexio.IntRuntime: seq=... t=artwork.decision_lookup decisionKey=... found=true
Nexio.IntRuntime: seq=... t=artwork.asset_materialized decisionKey=... assetKey=... provider=... imageType=POSTER cacheDecision=... networkExecuted=... success=...
```

Expected when premium materialization fails and fallback succeeds:

```text
Nexio.IntRuntime: seq=... t=artwork.fallback_materialized decisionKey=... fallbackProvider=TMDB assetKey=...
Nexio.IntRuntime: seq=... t=artwork.asset_materialized decisionKey=... assetKey=... provider=TMDB imageType=POSTER cacheDecision=FALLBACK_MATERIALIZED networkExecuted=true success=true
```

- [ ] **Step 4: Verify restart behavior on device**

With premium poster provider enabled and integration runtime logcat enabled:

```bash
adb -s 192.168.50.98:5555 shell am force-stop com.nexio.tv
adb -s 192.168.50.98:5555 shell monkey -p com.nexio.tv 1
adb -s 192.168.50.98:5555 logcat -d -s Nexio.MetaRoute:I Nexio.IntRuntime:I '*:S'
```

Expected: premium poster cards do not remain in the monochrome play-button placeholder state when a non-premium source poster exists. If premium fetch fails after restart, logs show `artwork.fallback_materialized` and the rendered poster is the non-premium source image.

- [ ] **Step 5: Inspect git status**

Run:

```bash
git status --short
```

Expected: only files from this plan are modified, plus unrelated pre-existing user changes if any were already present before execution.

## Self-Review

- Spec coverage: The plan covers the root cause fix by passing a safe non-premium `fallbackPosterUrl` into both premium poster adapters, covers restart behavior through persisted rejected fallback candidates, and covers improved logcat observability behind the existing integration runtime toggle.
- Placeholder scan: The plan has concrete file paths, code snippets, test commands, expected failures, expected passes, device verification commands, and commit commands.
- Type consistency: `nonPremiumPosterFallbackUrl()` is defined as an internal extension in the same package used by both adapters and tests. Adapter tests use existing `ArtworkSourceRole`, `ArtworkProviderId`, `HomeDisplayMetadata`, `InMemoryArtworkDecisionCache`, and `decisionKeyFromRef` APIs.
