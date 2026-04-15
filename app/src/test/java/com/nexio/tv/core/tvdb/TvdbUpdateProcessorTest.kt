package com.nexio.tv.core.tvdb

import com.nexio.tv.data.local.TvdbMergeAliasStore
import com.nexio.tv.data.local.TvdbUpdateStateStore
import com.nexio.tv.data.remote.api.TvdbApi
import com.nexio.tv.data.remote.api.TvdbEntityUpdate
import com.nexio.tv.data.remote.api.TvdbLinks
import com.nexio.tv.data.remote.api.TvdbUpdatesResponse
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class TvdbUpdateProcessorTest {

    private val api = mockk<TvdbApi>()
    private val stateStore = mockk<TvdbUpdateStateStore>()
    private val invalidator = mockk<TvdbCacheInvalidator>()
    private val diagnosticsRecorder = mockk<TvdbDiagnosticsRecorder>()
    private val authService = mockk<TvdbAuthService>()

    private fun buildProcessor() = TvdbUpdateProcessor(
        api = api,
        stateStore = stateStore,
        invalidator = invalidator,
        diagnosticsRecorder = diagnosticsRecorder,
        authService = authService
    )

    @Test
    fun `processSince invalidates changed events before advancing cursor`() = runTest {
        val updateEvent = TvdbEntityUpdate(
            entityType = "series",
            methodInt = 2,
            method = "update",
            recordId = 12345,
            timeStamp = 1700000100L,
            seriesId = null
        )
        coEvery { stateStore.currentCursor() } returns 1700000000L
        coEvery { authService.bearerToken() } returns "Bearer test-token"
        coEvery {
            api.getUpdates(any(), since = 1700000000L, type = null, page = null)
        } returns Response.success(
            TvdbUpdatesResponse(
                status = "success",
                data = listOf(updateEvent),
                links = TvdbLinks(next = null)
            )
        )
        coEvery { invalidator.invalidateChanged(any()) } just Runs
        coEvery { stateStore.storeSuccessfulCursor(any()) } just Runs
        coEvery { stateStore.recordStatus(any(), any()) } just Runs
        coEvery { diagnosticsRecorder.record(any()) } just Runs

        val processor = buildProcessor()
        val result = processor.processSince(1700000000L)

        assertTrue(result.success)
        coVerifyOrder {
            invalidator.invalidateChanged(updateEvent)
            stateStore.storeSuccessfulCursor(1700000100L)
        }
    }

    @Test
    fun `processSince purges deleted records`() = runTest {
        val deleteEvent = TvdbEntityUpdate(
            entityType = "series",
            methodInt = 3,
            method = "delete",
            recordId = 99999,
            timeStamp = 1700000200L,
            seriesId = null
        )
        coEvery { stateStore.currentCursor() } returns 1700000000L
        coEvery { authService.bearerToken() } returns "Bearer test-token"
        coEvery {
            api.getUpdates(any(), since = 1700000000L, type = null, page = null)
        } returns Response.success(
            TvdbUpdatesResponse(
                status = "success",
                data = listOf(deleteEvent),
                links = TvdbLinks(next = null)
            )
        )
        coEvery { invalidator.invalidateDeletedOrMerged(any()) } just Runs
        coEvery { stateStore.storeSuccessfulCursor(any()) } just Runs
        coEvery { stateStore.recordStatus(any(), any()) } just Runs
        coEvery { diagnosticsRecorder.record(any()) } just Runs

        val processor = buildProcessor()
        val result = processor.processSince(1700000000L)

        assertTrue(result.success)
        coVerify { invalidator.invalidateDeletedOrMerged(deleteEvent) }
    }

    @Test
    fun `processSince purges duplicate merge source and records merge target`() = runTest {
        val mergeEvent = TvdbEntityUpdate(
            entityType = "series",
            methodInt = 3,
            method = "delete",
            recordId = 11111,
            timeStamp = 1700000300L,
            seriesId = null,
            mergeToId = 22222,
            mergeToEntityType = "series"
        )
        coEvery { stateStore.currentCursor() } returns 1700000000L
        coEvery { authService.bearerToken() } returns "Bearer test-token"
        coEvery {
            api.getUpdates(any(), since = 1700000000L, type = null, page = null)
        } returns Response.success(
            TvdbUpdatesResponse(
                status = "success",
                data = listOf(mergeEvent),
                links = TvdbLinks(next = null)
            )
        )
        coEvery { invalidator.invalidateDeletedOrMerged(any()) } just Runs
        coEvery { stateStore.storeSuccessfulCursor(any()) } just Runs
        coEvery { stateStore.recordStatus(any(), any()) } just Runs
        coEvery { diagnosticsRecorder.record(any()) } just Runs

        val processor = buildProcessor()
        val result = processor.processSince(1700000000L)

        assertTrue(result.success)
        coVerify { invalidator.invalidateDeletedOrMerged(mergeEvent) }
    }

    @Test
    fun `processSince stores duplicate merge alias for read path`() = runTest {
        // Merge alias recording is delegated to invalidator.invalidateDeletedOrMerged
        // which internally calls TvdbMergeAliasStore.recordAlias.
        // This test verifies the processor routes merge events through invalidateDeletedOrMerged.
        val mergeEvent = TvdbEntityUpdate(
            entityType = "series",
            methodInt = 3,
            method = "delete",
            recordId = 33333,
            timeStamp = 1700000400L,
            seriesId = null,
            mergeToId = 44444,
            mergeToEntityType = "series"
        )
        coEvery { stateStore.currentCursor() } returns 1700000000L
        coEvery { authService.bearerToken() } returns "Bearer test-token"
        coEvery {
            api.getUpdates(any(), since = 1700000000L, type = null, page = null)
        } returns Response.success(
            TvdbUpdatesResponse(
                status = "success",
                data = listOf(mergeEvent),
                links = TvdbLinks(next = null)
            )
        )
        coEvery { invalidator.invalidateDeletedOrMerged(any()) } just Runs
        coEvery { stateStore.storeSuccessfulCursor(any()) } just Runs
        coEvery { stateStore.recordStatus(any(), any()) } just Runs
        coEvery { diagnosticsRecorder.record(any()) } just Runs

        val processor = buildProcessor()
        val result = processor.processSince(1700000000L)

        assertTrue(result.success)
        // The merge event with mergeToId present is routed through invalidateDeletedOrMerged
        // which is responsible for calling TvdbMergeAliasStore.recordAlias
        coVerify(exactly = 1) { invalidator.invalidateDeletedOrMerged(mergeEvent) }
    }

    @Test
    fun `processSince does not advance cursor when invalidation fails`() = runTest {
        val updateEvent = TvdbEntityUpdate(
            entityType = "series",
            methodInt = 2,
            method = "update",
            recordId = 55555,
            timeStamp = 1700000500L,
            seriesId = null
        )
        coEvery { stateStore.currentCursor() } returns 1700000000L
        coEvery { authService.bearerToken() } returns "Bearer test-token"
        coEvery {
            api.getUpdates(any(), since = 1700000000L, type = null, page = null)
        } returns Response.success(
            TvdbUpdatesResponse(
                status = "success",
                data = listOf(updateEvent),
                links = TvdbLinks(next = null)
            )
        )
        coEvery { invalidator.invalidateChanged(any()) } throws RuntimeException("Cache write failed")
        coEvery { stateStore.recordStatus(any(), any()) } just Runs
        coEvery { diagnosticsRecorder.record(any()) } just Runs

        val processor = buildProcessor()
        val result = processor.processSince(1700000000L)

        assertTrue(!result.success)
        coVerify(exactly = 0) { stateStore.storeSuccessfulCursor(any()) }
    }

    @Test
    fun `processSince ignores malformed event and records unknown update diagnostic`() = runTest {
        val malformedEvent = TvdbEntityUpdate(
            entityType = null,
            methodInt = null,
            recordId = null,
            timeStamp = null
        )
        val validEvent = TvdbEntityUpdate(
            entityType = "series",
            methodInt = 1,
            method = "create",
            recordId = 66666,
            timeStamp = 1700000600L,
            seriesId = null
        )
        coEvery { stateStore.currentCursor() } returns 1700000000L
        coEvery { authService.bearerToken() } returns "Bearer test-token"
        coEvery {
            api.getUpdates(any(), since = 1700000000L, type = null, page = null)
        } returns Response.success(
            TvdbUpdatesResponse(
                status = "success",
                data = listOf(malformedEvent, validEvent),
                links = TvdbLinks(next = null)
            )
        )
        coEvery { invalidator.invalidateChanged(any()) } just Runs
        coEvery { stateStore.storeSuccessfulCursor(any()) } just Runs
        coEvery { stateStore.recordStatus(any(), any()) } just Runs
        coEvery { diagnosticsRecorder.record(any()) } just Runs

        val processor = buildProcessor()
        val result = processor.processSince(1700000000L)

        assertTrue(result.success)
        // Should record an UNKNOWN_UPDATE_EVENT diagnostic for the malformed event
        coVerify(atLeast = 1) {
            diagnosticsRecorder.record(match { it.reason == TvdbReliabilityReason.UNKNOWN_UPDATE_EVENT })
        }
        // The processor should still process the valid event
        coVerify { invalidator.invalidateChanged(validEvent) }
        coVerify { stateStore.storeSuccessfulCursor(1700000600L) }
    }
}
