package com.nexio.tv.core.image

import android.net.Uri
import coil.decode.DataSource
import coil.fetch.SourceResult
import com.nexio.tv.core.artwork.ArtworkAssetRecord
import com.nexio.tv.core.artwork.ArtworkAssetKey
import com.nexio.tv.core.artwork.ArtworkAssetResult
import com.nexio.tv.core.artwork.ArtworkAssetRepository
import com.nexio.tv.core.artwork.ArtworkDecisionKey
import com.nexio.tv.core.artwork.ArtworkProviderId
import com.nexio.tv.core.artwork.ArtworkType
import io.mockk.coVerify
import com.nexio.tv.core.integration.IntegrationFetchResult
import com.nexio.tv.core.integration.IntegrationProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NexioArtworkFetcherTest {
    private val temp = TemporaryFolder().also { it.create() }

    @Test
    fun `factory accepts asset uri model`() {
        val repository = mockk<ArtworkAssetRepository>(relaxed = true)
        val factory = NexioArtworkFetcher.Factory(repository)

        val fetcher = factory.create(
            data = Uri.parse("nexio-artwork://asset/artwork-asset:RPDB:poster:imdb:tt0137523"),
            options = mockk(relaxed = true),
            imageLoader = mockk(relaxed = true)
        )

        assertTrue(fetcher is NexioArtworkFetcher)
    }

    @Test
    fun `factory rejects remote provider uri model`() {
        val repository = mockk<ArtworkAssetRepository>(relaxed = true)
        val factory = NexioArtworkFetcher.Factory(repository)

        val fetcher = factory.create(
            data = Uri.parse("https://image.tmdb.org/t/p/w500/abc.jpg"),
            options = mockk(relaxed = true),
            imageLoader = mockk(relaxed = true)
        )

        assertNull(fetcher)
    }

    @Test
    fun `factory rejects insecure remote provider uri model`() {
        val repository = mockk<ArtworkAssetRepository>(relaxed = true)
        val factory = NexioArtworkFetcher.Factory(repository)

        val fetcher = factory.create(
            data = Uri.parse("http://image.tmdb.org/t/p/w500/abc.jpg"),
            options = mockk(relaxed = true),
            imageLoader = mockk(relaxed = true)
        )

        assertNull(fetcher)
    }

    @Test
    fun `fetcher reads existing asset file from repository`() = runTest {
        val assetKey = ArtworkAssetKey("artwork-asset:RPDB:poster:imdb:tt0137523")
        val file = temp.newFile("asset.bin")
        file.writeBytes("image-bytes".toByteArray())
        val repository = mockk<ArtworkAssetRepository>()
        every { repository.getExistingFile(assetKey) } returns file
        val fetcher = NexioArtworkFetcher(
            assetKey = assetKey,
            decisionKey = null,
            repository = repository
        )

        val result = fetcher.fetch()

        assertTrue(result is SourceResult)
        assertEquals(DataSource.DISK, (result as SourceResult).dataSource)
    }

    @Test
    fun `fetcher materializes decision uri through repository`() = runTest {
        val decisionKey = ArtworkDecisionKey("decision-key")
        val assetKey = ArtworkAssetKey("artwork-asset:RPDB:poster:imdb:tt0137523")
        val file = temp.newFile("decision-asset.bin")
        file.writeBytes("decision-image-bytes".toByteArray())
        val repository = mockk<ArtworkAssetRepository>()
        coEvery { repository.getOrFetchDecision(decisionKey) } returns ArtworkAssetResult(
            assetKey = assetKey,
            localFile = file,
            record = ArtworkAssetRecord(
                assetKey = assetKey,
                decisionKey = decisionKey,
                provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB),
                imageType = ArtworkType.POSTER,
                imageLanguage = "en",
                relativePath = "artwork-assets/RPDB/poster/${assetKey.value}.bin",
                mimeType = "image/jpeg",
                byteCount = file.length(),
                sourceHash = "source-hash",
                policyVersion = 1,
                fetchedAtMs = 1_000L,
                expiresAtMs = 2_000L,
                staleUntilMs = 3_000L
            ),
            runtimeResult = IntegrationFetchResult.Updated<ByteArray>("decision-image-bytes".toByteArray()),
            runtimeApiShapeId = "rpdb.poster_template",
            cacheDecision = "MISS_THEN_NETWORK",
            mimeType = "image/jpeg",
            networkExecuted = true
        )
        val fetcher = NexioArtworkFetcher(
            assetKey = null,
            decisionKey = decisionKey,
            repository = repository
        )

        val result = fetcher.fetch()

        assertTrue(result is SourceResult)
        result as SourceResult
        assertEquals(DataSource.DISK, result.dataSource)
        assertEquals("image/jpeg", result.mimeType)
    }

    @Test
    fun `fetcher returns null when decision is missing`() = runTest {
        val decisionKey = ArtworkDecisionKey("missing-decision")
        val repository = mockk<ArtworkAssetRepository>()
        coEvery { repository.getOrFetchDecision(decisionKey) } returns null
        val fetcher = NexioArtworkFetcher(
            assetKey = null,
            decisionKey = decisionKey,
            repository = repository
        )

        val result = fetcher.fetch()

        assertNull(result)
    }

    @Test
    fun `fetcher rehydrates evicted asset URI when on-disk file is missing`() = runTest {
        val assetKey = ArtworkAssetKey("artwork-asset:TVDB:logo:urlHash:abc:variant:none:imageLang:en:policy:1")
        val rehydratedFile = temp.newFile("rehydrated-logo.bin")
        rehydratedFile.writeBytes("logo-bytes".toByteArray())
        val repository = mockk<ArtworkAssetRepository>()
        every { repository.getExistingFile(assetKey) } returns null
        coEvery { repository.getOrRehydrateAsset(assetKey) } returns ArtworkAssetResult(
            assetKey = assetKey,
            localFile = rehydratedFile,
            record = ArtworkAssetRecord(
                assetKey = assetKey,
                decisionKey = ArtworkDecisionKey("decision-for-logo"),
                provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TVDB),
                imageType = ArtworkType.LOGO,
                imageLanguage = "en",
                relativePath = "artwork-assets/TVDB/logo/${assetKey.value}.bin",
                mimeType = "image/png",
                byteCount = rehydratedFile.length(),
                sourceHash = "source-hash",
                policyVersion = 1,
                fetchedAtMs = 1_000L,
                expiresAtMs = 2_000L,
                staleUntilMs = 3_000L
            ),
            runtimeResult = IntegrationFetchResult.Updated<ByteArray>("logo-bytes".toByteArray()),
            runtimeApiShapeId = "tvdb.artwork.fetch",
            cacheDecision = "MISS_THEN_NETWORK",
            mimeType = "image/png",
            networkExecuted = true
        )
        val fetcher = NexioArtworkFetcher(
            assetKey = assetKey,
            decisionKey = null,
            repository = repository
        )

        val result = fetcher.fetch()

        assertTrue(result is SourceResult)
        result as SourceResult
        assertEquals(DataSource.DISK, result.dataSource)
        assertEquals("image/png", result.mimeType)
        coVerify(exactly = 1) { repository.getOrRehydrateAsset(assetKey) }
    }

    @Test
    fun `fetcher returns null when asset URI cannot be rehydrated`() = runTest {
        val assetKey = ArtworkAssetKey("artwork-asset:TVDB:logo:orphan")
        val repository = mockk<ArtworkAssetRepository>()
        every { repository.getExistingFile(assetKey) } returns null
        coEvery { repository.getOrRehydrateAsset(assetKey) } returns null
        val fetcher = NexioArtworkFetcher(
            assetKey = assetKey,
            decisionKey = null,
            repository = repository
        )

        val result = fetcher.fetch()

        assertNull(result)
    }
}
