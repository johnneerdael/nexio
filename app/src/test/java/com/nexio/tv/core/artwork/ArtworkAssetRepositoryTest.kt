package com.nexio.tv.core.artwork

import com.nexio.tv.core.integration.ArtworkApiShapes
import com.nexio.tv.core.integration.ByteArrayIntegrationCodec
import com.nexio.tv.core.integration.IntegrationCachePolicy
import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.IntegrationCallSpec
import com.nexio.tv.core.integration.IntegrationFetchOptions
import com.nexio.tv.core.integration.IntegrationFetchResult
import com.nexio.tv.core.integration.IntegrationLoadResult
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.core.integration.IntegrationSpec
import com.nexio.tv.core.integration.IntegrationStreamHandle
import com.nexio.tv.core.integration.IntegrationStreamSpec
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArtworkAssetRepositoryTest {
    private val temp = TemporaryFolder().also { it.create() }

    @Test
    fun `provider template fetch uses runtime and global English image scope`() = runTest {
        val runtime = RecordingIntegrationRuntime(successValue = "image-bytes".toByteArray())
        val repository = ArtworkAssetRepository(
            runtime = runtime,
            diskCache = ArtworkAssetDiskCache(temp.root),
            sourceMaterializer = ArtworkSourceMaterializer(emptyMap())
        )
        val decision = rpdbTemplateDecision()

        val result = repository.getOrFetch(decision)

        assertNotNull(result)
        result!!
        assertTrue(result.localFile.exists())
        assertArrayEquals("image-bytes".toByteArray(), result.localFile.readBytes())
        assertEquals(ArtworkCacheKeys.assetKeyForProviderTemplate(decision.selectedCandidate.providerTemplate!!).value, runtime.lastSpec!!.cacheKey)
        assertEquals(IntegrationScope.GlobalEnglishImage, runtime.lastSpec!!.scope)
        assertEquals(ArtworkApiShapes.RPDB_POSTER_TEMPLATE, runtime.lastSpec!!.apiShapeId)
        assertEquals(ByteArrayIntegrationCodec, runtime.lastSpec!!.codec)
        assertTrue(runtime.lastSpec!!.cachePolicy is IntegrationCachePolicy.CacheFirst)
        assertEquals(ArtworkApiShapes.RPDB_POSTER_TEMPLATE, result.runtimeApiShapeId)
        assertEquals("HIT", result.cacheDecision)
        assertEquals(ByteArrayIntegrationCodec.mimeType, result.mimeType)
        assertEquals(false, result.networkExecuted)
    }

    @Test
    fun `repository loader executes on runtime Updated path`() = runTest {
        val runtime = LoadingIntegrationRuntime()
        var loaderCalled = false
        val repository = ArtworkAssetRepository(
            runtime = runtime,
            diskCache = ArtworkAssetDiskCache(temp.root),
            sourceMaterializer = ArtworkSourceMaterializer(emptyMap()),
            byteLoader = ArtworkByteLoader { _, _ ->
                loaderCalled = true
                IntegrationLoadResult.Success("loaded".toByteArray())
            }
        )

        val result = repository.getOrFetch(rpdbTemplateDecision())

        assertNotNull(result)
        assertTrue(loaderCalled)
        assertArrayEquals("loaded".toByteArray(), result!!.localFile.readBytes())
        assertEquals("MISS_THEN_NETWORK", result.cacheDecision)
        assertEquals(true, result.networkExecuted)
    }

    @Test
    fun `repository marks stale result as network executed when loader was invoked`() = runTest {
        val runtime = StaleAfterLoadingIntegrationRuntime("stale".toByteArray())
        val repository = ArtworkAssetRepository(
            runtime = runtime,
            diskCache = ArtworkAssetDiskCache(temp.root),
            sourceMaterializer = ArtworkSourceMaterializer(emptyMap()),
            byteLoader = ArtworkByteLoader { _, _ ->
                IntegrationLoadResult.Success("network-attempt".toByteArray())
            }
        )

        val result = repository.getOrFetch(rpdbTemplateDecision())

        assertNotNull(result)
        assertEquals(true, result!!.networkExecuted)
        assertEquals("STALE_HIT", result.cacheDecision)
        assertArrayEquals("stale".toByteArray(), result.localFile.readBytes())
    }

    @Test
    fun `remote preview materialization recovers raw source by source hash without persisting raw url`() = runTest {
        val runtime = LoadingIntegrationRuntime()
        val loadedSources = mutableListOf<ArtworkSource>()
        val repository = ArtworkAssetRepository(
            runtime = runtime,
            diskCache = ArtworkAssetDiskCache(temp.root),
            sourceMaterializer = ArtworkSourceMaterializer(
                mapOf("hash" to SensitiveArtworkUrl.of("https://image.tmdb.org/t/p/w500/abc.jpg"))
            ),
            byteLoader = ArtworkByteLoader { source, _ ->
                loadedSources += source
                IntegrationLoadResult.Success("preview".toByteArray())
            }
        )
        val decision = remotePreviewDecision()

        val result = repository.getOrFetch(decision)

        assertNotNull(result)
        assertArrayEquals("preview".toByteArray(), result!!.localFile.readBytes())
        assertEquals(ArtworkApiShapes.RAIL_PREVIEW_IMAGE_FETCH, runtime.lastSpec!!.apiShapeId)
        assertEquals(ArtworkApiShapes.RAIL_PREVIEW_IMAGE_FETCH, result.runtimeApiShapeId)
        assertEquals("MISS_THEN_NETWORK", result.cacheDecision)
        assertEquals("artwork-asset:RAIL_PREVIEW:poster:urlHash:hash:variant:none:imageLang:en:policy:1", runtime.lastSpec!!.cacheKey)
        assertEquals("https://image.tmdb.org/t/p/w500/<redacted>", decision.selectedCandidate.redactedSourceForTrace)
        assertTrue(loadedSources.single() is ArtworkSource.RemoteUrl)
    }

    private class RecordingIntegrationRuntime(
        private val successValue: ByteArray
    ) : IntegrationRuntime {
        var lastSpec: IntegrationSpec<ByteArray>? = null

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T> get(
            spec: IntegrationSpec<T>,
            options: IntegrationFetchOptions
        ): IntegrationFetchResult<T> {
            lastSpec = spec as IntegrationSpec<ByteArray>
            return IntegrationFetchResult.Fresh(successValue as T)
        }

        override suspend fun <T> call(spec: IntegrationCallSpec<T>): IntegrationCallResult<T> =
            error("not used")

        override suspend fun <T> open(spec: IntegrationStreamSpec<T>): IntegrationStreamHandle<T>? =
            error("not used")
    }

    private class LoadingIntegrationRuntime : IntegrationRuntime {
        var lastSpec: IntegrationSpec<ByteArray>? = null

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T> get(
            spec: IntegrationSpec<T>,
            options: IntegrationFetchOptions
        ): IntegrationFetchResult<T> {
            lastSpec = spec as IntegrationSpec<ByteArray>
            return when (val loaded = spec.load()) {
                is IntegrationLoadResult.Success -> IntegrationFetchResult.Updated(loaded.value as T)
                else -> IntegrationFetchResult.Missing
            }
        }

        override suspend fun <T> call(spec: IntegrationCallSpec<T>): IntegrationCallResult<T> =
            error("not used")

        override suspend fun <T> open(spec: IntegrationStreamSpec<T>): IntegrationStreamHandle<T>? =
            error("not used")
    }

    private class StaleAfterLoadingIntegrationRuntime(
        private val staleValue: ByteArray
    ) : IntegrationRuntime {
        @Suppress("UNCHECKED_CAST")
        override suspend fun <T> get(
            spec: IntegrationSpec<T>,
            options: IntegrationFetchOptions
        ): IntegrationFetchResult<T> {
            spec.load()
            return IntegrationFetchResult.Stale(staleValue as T)
        }

        override suspend fun <T> call(spec: IntegrationCallSpec<T>): IntegrationCallResult<T> =
            error("not used")

        override suspend fun <T> open(spec: IntegrationStreamSpec<T>): IntegrationStreamHandle<T>? =
            error("not used")
    }

    private fun rpdbTemplateDecision(): ArtworkDecision =
        ArtworkDecision(
            decisionKey = ArtworkDecisionKey("rpdb-decision"),
            ownerKey = ArtworkOwnerKey.CanonicalContent("imdb:tt0137523"),
            canonicalContentId = "imdb:tt0137523",
            imageType = ArtworkType.POSTER,
            selectedCandidate = PersistedArtworkCandidate(
                provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB),
                sourceRole = ArtworkSourceRole.PREMIUM,
                sourceHash = "template-hash",
                redactedSourceForTrace = null,
                providerTemplate = PersistedProviderTemplate(
                    provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB),
                    imageType = ArtworkType.POSTER,
                    idType = "imdb",
                    mediaId = "tt0137523",
                    providerPathHash = "pathhash",
                    settingsHash = "settingshash",
                    credentialHash = "credentialhash",
                    policyVersion = 1
                ),
                priority = 0
            ),
            rejectedCandidates = emptyList(),
            policyVersion = 1,
            settingsHash = "settingshash",
            credentialHash = "credentialhash",
            createdAtMs = 100L,
            expiresAtMs = 200L,
            staleUntilMs = 300L
        )

    private fun remotePreviewDecision(): ArtworkDecision =
        ArtworkDecision(
            decisionKey = ArtworkDecisionKey("preview-decision"),
            ownerKey = ArtworkOwnerKey.PreviewItem("rail-item", "payload"),
            canonicalContentId = null,
            imageType = ArtworkType.POSTER,
            selectedCandidate = PersistedArtworkCandidate(
                provider = ArtworkProviderId.RailPreview,
                sourceRole = ArtworkSourceRole.RAIL_PREVIEW,
                sourceHash = "hash",
                redactedSourceForTrace = "https://image.tmdb.org/t/p/w500/<redacted>",
                providerTemplate = null,
                priority = 1
            ),
            rejectedCandidates = emptyList(),
            policyVersion = 1,
            settingsHash = null,
            credentialHash = null,
            createdAtMs = 100L,
            expiresAtMs = 200L,
            staleUntilMs = 300L
        )
}
