package com.nexio.tv.core.tvdb

import android.content.Context
import com.nexio.tv.data.local.MetadataDiskCacheStore
import com.nexio.tv.data.remote.api.TvdbApi
import com.nexio.tv.data.remote.api.TvdbArtworkStatusRecord
import com.nexio.tv.data.remote.api.TvdbArtworkTypeRecord
import com.nexio.tv.data.remote.api.TvdbCompanyTypeRecord
import com.nexio.tv.data.remote.api.TvdbContentRatingRecord
import com.nexio.tv.data.remote.api.TvdbEntityTypeRecord
import com.nexio.tv.data.remote.api.TvdbGenreReferenceRecord
import com.nexio.tv.data.remote.api.TvdbLanguageRecord
import com.nexio.tv.data.remote.api.TvdbReferenceResponse
import com.nexio.tv.data.remote.api.TvdbSeasonTypeReferenceRecord
import com.nexio.tv.data.remote.api.TvdbSeriesStatusRecord
import com.nexio.tv.data.remote.api.TvdbSourceTypeRecord
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
import retrofit2.Response

class TvdbReferenceDataServiceTest {

    private val api = mockk<TvdbApi>()
    private val diagnosticsRecorder = mockk<TvdbDiagnosticsRecorder>()
    private val authService = mockk<TvdbAuthService>()
    private val prefs = InMemorySharedPreferences()
    private val context = mockk<Context> {
        every { getSharedPreferences("metadata_disk_cache_v1", Context.MODE_PRIVATE) } returns prefs
    }
    private val cacheStore = MetadataDiskCacheStore(context)

    private fun buildService() = TvdbReferenceDataService(
        api = api,
        cacheStore = cacheStore,
        diagnosticsRecorder = diagnosticsRecorder,
        authService = authService
    )

    private fun stubAllReferenceEndpoints() {
        coEvery { authService.bearerToken() } returns "Bearer test-token"
        coEvery { diagnosticsRecorder.record(any()) } just Runs
        coEvery { api.getArtworkTypes(any()) } returns Response.success(
            TvdbReferenceResponse(status = "success", data = listOf(
                TvdbArtworkTypeRecord(id = 1, name = "Banner")
            ))
        )
        coEvery { api.getArtworkStatuses(any()) } returns Response.success(
            TvdbReferenceResponse(status = "success", data = listOf(
                TvdbArtworkStatusRecord(id = 1, name = "Active")
            ))
        )
        coEvery { api.getGenres(any()) } returns Response.success(
            TvdbReferenceResponse(status = "success", data = listOf(
                TvdbGenreReferenceRecord(id = 1, name = "Drama", slug = "drama")
            ))
        )
        coEvery { api.getLanguages(any()) } returns Response.success(
            TvdbReferenceResponse(status = "success", data = listOf(
                TvdbLanguageRecord(id = "eng", name = "English", nativeName = "English", shortCode = "en")
            ))
        )
        coEvery { api.getSeriesStatuses(any()) } returns Response.success(
            TvdbReferenceResponse(status = "success", data = listOf(
                TvdbSeriesStatusRecord(id = 1, name = "Continuing", recordType = "series")
            ))
        )
        coEvery { api.getContentRatings(any()) } returns Response.success(
            TvdbReferenceResponse(status = "success", data = listOf(
                TvdbContentRatingRecord(id = 1, name = "TV-14", country = "usa")
            ))
        )
        coEvery { api.getSeasonTypes(any()) } returns Response.success(
            TvdbReferenceResponse(status = "success", data = listOf(
                TvdbSeasonTypeReferenceRecord(id = 1, name = "Aired Order", type = "default")
            ))
        )
        coEvery { api.getSourceTypes(any()) } returns Response.success(
            TvdbReferenceResponse(status = "success", data = listOf(
                TvdbSourceTypeRecord(id = 1, name = "IMDB", slug = "imdb")
            ))
        )
        coEvery { api.getEntityTypes(any()) } returns Response.success(
            TvdbReferenceResponse(status = "success", data = listOf(
                TvdbEntityTypeRecord(id = 1, name = "Series")
            ))
        )
        coEvery { api.getCompanyTypes(any()) } returns Response.success(
            TvdbReferenceResponse(status = "success", data = listOf(
                TvdbCompanyTypeRecord(companyTypeId = 1, companyTypeName = "Network")
            ))
        )
    }

    @Test
    fun `warmCoreReferences fetches every core reference kind`() = runTest {
        stubAllReferenceEndpoints()
        val service = buildService()

        val result = service.warmCoreReferences()

        assertTrue(result.success)
        assertEquals(TvdbReferenceKind.entries.size, result.succeededKinds.size)
        coVerify { api.getArtworkTypes(any()) }
        coVerify { api.getArtworkStatuses(any()) }
        coVerify { api.getGenres(any()) }
        coVerify { api.getLanguages(any()) }
        coVerify { api.getSeriesStatuses(any()) }
        coVerify { api.getContentRatings(any()) }
        coVerify { api.getSeasonTypes(any()) }
        coVerify { api.getSourceTypes(any()) }
        coVerify { api.getEntityTypes(any()) }
        coVerify { api.getCompanyTypes(any()) }
    }

    @Test
    fun `entity types reference request uses entities endpoint`() = runTest {
        stubAllReferenceEndpoints()
        val service = buildService()

        service.refresh(TvdbReferenceKind.ENTITY_TYPES)

        // Verify getEntityTypes was called (which maps to @GET("entities"))
        coVerify { api.getEntityTypes(any()) }
    }

    @Test
    fun `refresh failure serves stale cached labels`() = runTest {
        stubAllReferenceEndpoints()
        val service = buildService()

        // First warm successfully to populate cache
        service.warmCoreReferences()

        // Now make genres endpoint fail
        coEvery { api.getGenres(any()) } throws RuntimeException("Network error")

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
        coVerify(exactly = 2) { api.getGenres(any()) } // once for warm, once for refresh
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
        coEvery { authService.bearerToken() } returns "Bearer test-token"
        coEvery { diagnosticsRecorder.record(any()) } just Runs
        // Return records with blank/invalid names
        coEvery { api.getGenres(any()) } returns Response.success(
            TvdbReferenceResponse(status = "success", data = listOf(
                TvdbGenreReferenceRecord(id = 1, name = ""),
                TvdbGenreReferenceRecord(id = null, name = "Valid Name"),
                TvdbGenreReferenceRecord(id = 2, name = "   ")
            ))
        )

        val service = buildService()
        val result = service.refresh(TvdbReferenceKind.GENRES)

        // All records had invalid id or blank name, so validation should fail
        assertTrue(!result.success || result.staleFallback)
    }
}
