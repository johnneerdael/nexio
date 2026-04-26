package com.nexio.tv.core.tvdb

import android.content.Context
import com.nexio.tv.data.local.MetadataDiskCacheStore
import com.nexio.tv.data.remote.api.TvdbArtworkStatusRecord
import com.nexio.tv.data.remote.api.TvdbArtworkTypeRecord
import com.nexio.tv.data.remote.api.TvdbCompanyTypeRecord
import com.nexio.tv.data.remote.api.TvdbContentRatingRecord
import com.nexio.tv.data.remote.api.TvdbEntityTypeRecord
import com.nexio.tv.data.remote.api.TvdbGenreReferenceRecord
import com.nexio.tv.data.remote.api.TvdbLanguageRecord
import com.nexio.tv.data.remote.api.TvdbSeasonTypeReferenceRecord
import com.nexio.tv.data.remote.api.TvdbSeriesStatusRecord
import com.nexio.tv.data.remote.api.TvdbSourceTypeRecord
import com.nexio.tv.data.integration.tvdb.TvdbIntegrationProvider
import com.nexio.tv.testutil.InMemorySharedPreferences
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CancellationException

class TvdbReferenceDataServiceTest {

    private val provider = mockk<TvdbIntegrationProvider>()
    private val diagnosticsRecorder = mockk<TvdbDiagnosticsRecorder>()
    private val prefs = InMemorySharedPreferences()
    private val context = mockk<Context> {
        every { getSharedPreferences("metadata_disk_cache_v1", Context.MODE_PRIVATE) } returns prefs
    }
    private val cacheStore = MetadataDiskCacheStore(context)

    private fun buildService() = TvdbReferenceDataService(
        provider = provider,
        cacheStore = cacheStore,
        diagnosticsRecorder = diagnosticsRecorder,
    )

    private fun stubAllReferenceEndpoints() {
        coEvery { diagnosticsRecorder.record(any()) } just Runs
        coEvery { provider.fetchReferenceRecords(TvdbReferenceKind.ARTWORK_TYPES) } returns listOf(
            TvdbArtworkTypeRecord(id = 1, name = "Banner")
        )
        coEvery { provider.fetchReferenceRecords(TvdbReferenceKind.ARTWORK_STATUSES) } returns listOf(
            TvdbArtworkStatusRecord(id = 1, name = "Active")
        )
        coEvery { provider.fetchReferenceRecords(TvdbReferenceKind.GENRES) } returns listOf(
            TvdbGenreReferenceRecord(id = 1, name = "Drama", slug = "drama")
        )
        coEvery { provider.fetchReferenceRecords(TvdbReferenceKind.LANGUAGES) } returns listOf(
            TvdbLanguageRecord(id = "eng", name = "English", nativeName = "English", shortCode = "en")
        )
        coEvery { provider.fetchReferenceRecords(TvdbReferenceKind.SERIES_STATUSES) } returns listOf(
            TvdbSeriesStatusRecord(id = 1, name = "Continuing", recordType = "series")
        )
        coEvery { provider.fetchReferenceRecords(TvdbReferenceKind.CONTENT_RATINGS) } returns listOf(
            TvdbContentRatingRecord(id = 1, name = "TV-14", country = "usa")
        )
        coEvery { provider.fetchReferenceRecords(TvdbReferenceKind.SEASON_TYPES) } returns listOf(
            TvdbSeasonTypeReferenceRecord(id = 1, name = "Aired Order", type = "default")
        )
        coEvery { provider.fetchReferenceRecords(TvdbReferenceKind.SOURCE_TYPES) } returns listOf(
            TvdbSourceTypeRecord(id = 1, name = "IMDB", slug = "imdb")
        )
        coEvery { provider.fetchReferenceRecords(TvdbReferenceKind.ENTITY_TYPES) } returns listOf(
            TvdbEntityTypeRecord(id = 1, name = "Series")
        )
        coEvery { provider.fetchReferenceRecords(TvdbReferenceKind.COMPANY_TYPES) } returns listOf(
            TvdbCompanyTypeRecord(companyTypeId = 1, companyTypeName = "Network")
        )
    }

    @Test
    fun `warmCoreReferences fetches every core reference kind`() = runTest {
        stubAllReferenceEndpoints()
        val service = buildService()

        val result = service.warmCoreReferences()

        assertTrue(result.success)
        assertEquals(TvdbReferenceKind.entries.size, result.succeededKinds.size)
        coVerify { provider.fetchReferenceRecords(TvdbReferenceKind.ARTWORK_TYPES) }
        coVerify { provider.fetchReferenceRecords(TvdbReferenceKind.ARTWORK_STATUSES) }
        coVerify { provider.fetchReferenceRecords(TvdbReferenceKind.GENRES) }
        coVerify { provider.fetchReferenceRecords(TvdbReferenceKind.LANGUAGES) }
        coVerify { provider.fetchReferenceRecords(TvdbReferenceKind.SERIES_STATUSES) }
        coVerify { provider.fetchReferenceRecords(TvdbReferenceKind.CONTENT_RATINGS) }
        coVerify { provider.fetchReferenceRecords(TvdbReferenceKind.SEASON_TYPES) }
        coVerify { provider.fetchReferenceRecords(TvdbReferenceKind.SOURCE_TYPES) }
        coVerify { provider.fetchReferenceRecords(TvdbReferenceKind.ENTITY_TYPES) }
        coVerify { provider.fetchReferenceRecords(TvdbReferenceKind.COMPANY_TYPES) }
    }

    @Test
    fun `entity types reference request uses entities endpoint`() = runTest {
        stubAllReferenceEndpoints()
        val service = buildService()

        service.refresh(TvdbReferenceKind.ENTITY_TYPES)

        coVerify { provider.fetchReferenceRecords(TvdbReferenceKind.ENTITY_TYPES) }
    }

    @Test
    fun `refresh failure serves stale cached labels`() = runTest {
        stubAllReferenceEndpoints()
        val service = buildService()

        // First warm successfully to populate cache
        service.warmCoreReferences()

        // Now make genres endpoint fail
        coEvery { provider.fetchReferenceRecords(TvdbReferenceKind.GENRES) } throws RuntimeException("Network error")

        val result = service.refresh(TvdbReferenceKind.GENRES)

        // Should return stale data, not null
        assertTrue(result.staleFallback)
        coVerify {
            diagnosticsRecorder.record(match {
                it.reason == TvdbReliabilityReason.REFERENCE_REFRESH_FAILED
            })
        }
    }

    @Test
    fun `reference update event refreshes only matching kind`() = runTest {
        stubAllReferenceEndpoints()
        val service = buildService()

        service.warmCoreReferences()

        // Refresh only genres via update entity type
        val result = service.refreshForUpdateEntityType("genres")

        assertNotNull(result)
        coVerify(exactly = 2) { provider.fetchReferenceRecords(TvdbReferenceKind.GENRES) } // once for warm, once for refresh
    }

    @Test
    fun `startup catch up warms core references after valid credentials`() = runTest {
        stubAllReferenceEndpoints()
        val service = buildService()

        val result = service.warmCoreReferences()

        assertTrue(result.success)
        // Verify REFERENCE_REFRESH_SUCCEEDED was recorded
        coVerify {
            diagnosticsRecorder.record(match {
                it.reason == TvdbReliabilityReason.REFERENCE_REFRESH_SUCCEEDED
            })
        }
    }

    @Test
    fun `cache invalidator refreshes reference kind from update entity type`() = runTest {
        stubAllReferenceEndpoints()
        val service = buildService()

        // Test all update entity type mappings
        assertNotNull(service.refreshForUpdateEntityType("artworktypes"))
        assertNotNull(service.refreshForUpdateEntityType("genres"))
        assertNotNull(service.refreshForUpdateEntityType("languages"))
        assertNotNull(service.refreshForUpdateEntityType("content_ratings"))
        assertNotNull(service.refreshForUpdateEntityType("seasontypes"))
        assertNotNull(service.refreshForUpdateEntityType("sourcetypes"))
        assertNotNull(service.refreshForUpdateEntityType("entity_types"))
        assertNotNull(service.refreshForUpdateEntityType("company_types"))

        // Unknown entity type should return null
        assertNull(service.refreshForUpdateEntityType("unknown_type"))
    }

    @Test
    fun `malformed reference payload is rejected before cache write`() = runTest {
        coEvery { diagnosticsRecorder.record(any()) } just Runs
        // Return records with blank/invalid names
        coEvery { provider.fetchReferenceRecords(TvdbReferenceKind.GENRES) } returns listOf(
            TvdbGenreReferenceRecord(id = 1, name = ""),
            TvdbGenreReferenceRecord(id = null, name = "Valid Name"),
            TvdbGenreReferenceRecord(id = 2, name = "   ")
        )

        val service = buildService()
        val result = service.refresh(TvdbReferenceKind.GENRES)

        // All records had invalid id or blank name, so validation should fail
        assertTrue(!result.success || result.staleFallback)
    }

    @Test
    fun `refresh preserves coroutine cancellation`() = runTest {
        coEvery { provider.fetchReferenceRecords(TvdbReferenceKind.GENRES) } throws CancellationException("cancelled")

        val service = buildService()
        val result = runCatching { service.refresh(TvdbReferenceKind.GENRES) }

        assertTrue(result.exceptionOrNull() is CancellationException)
    }

    @Test
    fun `unknown reference record type is rejected`() = runTest {
        coEvery { diagnosticsRecorder.record(any()) } just Runs
        coEvery { provider.fetchReferenceRecords(TvdbReferenceKind.GENRES) } returns listOf(UnknownReferenceRecord())

        val service = buildService()
        val result = service.refresh(TvdbReferenceKind.GENRES)

        assertTrue(!result.success && result.itemCount == 0 && !result.staleFallback)
    }

    @Test
    fun `mixed reference payload fails closed and does not write partial data`() = runTest {
        coEvery { diagnosticsRecorder.record(any()) } just Runs
        coEvery { provider.fetchReferenceRecords(TvdbReferenceKind.GENRES) } returns listOf(
            TvdbGenreReferenceRecord(id = 1, name = "Drama", slug = "drama"),
            TvdbGenreReferenceRecord(id = null, name = "Invalid Id"),
            UnknownReferenceRecord(),
            TvdbGenreReferenceRecord(id = 3, name = "Comedy", slug = "comedy")
        )

        val service = buildService()
        val result = service.refresh(TvdbReferenceKind.GENRES)

        assertTrue(!result.success && !result.staleFallback && result.itemCount == 0)
        assertTrue(cacheStore.readTvdbReference<Any>(TvdbReferenceKind.GENRES.cacheKey)?.isEmpty() ?: true)
    }
}

private data class UnknownReferenceRecord(val value: String = "unknown")
