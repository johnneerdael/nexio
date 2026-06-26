package com.nexio.tv.data.repository

import com.nexio.tv.data.local.ContinueWatchingSnapshotStore
import com.nexio.tv.data.local.MetadataDiskCacheStore
import com.nexio.tv.data.local.TraktSettingsDataStore
import com.nexio.tv.domain.model.WatchProgress
import com.nexio.tv.domain.repository.WatchProgressRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinueWatchingSnapshotServiceLiveProgressPersistenceTest {

    @Test
    fun `transient local continue watching display is not persisted`() = runTest {
        val writes = mutableListOf<ContinueWatchingSnapshot>()
        val service = buildService(writes)
        service.reloadPersistedSnapshotForActiveProfile(clearWhenMissing = false)
        val local = progress("tmdb:101", WatchProgress.SOURCE_LOCAL)

        val published = service.publishSnapshotForTest(
            displaySnapshot = ContinueWatchingSnapshot(resumeItems = listOf(local), updatedAtMs = 10L),
            persistedSnapshot = null,
            profileId = PROFILE_ID
        )

        val displayed = withTimeout(2_000) {
            service.observeProfileSnapshot(PROFILE_ID)
                .first { snapshot -> snapshot.resumeItems.any { it.contentId == "tmdb:101" } }
        }
        assertTrue(published)
        assertEquals(listOf("tmdb:101"), displayed.resumeItems.map { it.contentId })
        assertTrue(writes.isEmpty())
    }

    @Test
    fun `session local display is excluded from persisted remote snapshot`() = runTest {
        val writes = mutableListOf<ContinueWatchingSnapshot>()
        val service = buildService(writes)
        service.reloadPersistedSnapshotForActiveProfile(clearWhenMissing = false)
        val local = progress("tmdb:101", WatchProgress.SOURCE_LOCAL)
        val remote = progress("tmdb:202", WatchProgress.SOURCE_SIMKL_PLAYBACK)

        val published = service.publishSnapshotForTest(
            displaySnapshot = ContinueWatchingSnapshot(resumeItems = listOf(local, remote), updatedAtMs = 20L),
            persistedSnapshot = ContinueWatchingSnapshot(resumeItems = listOf(remote), updatedAtMs = 20L),
            profileId = PROFILE_ID
        )

        val displayed = withTimeout(2_000) {
            service.observeProfileSnapshot(PROFILE_ID).first { snapshot ->
                snapshot.resumeItems.map { it.contentId }.toSet() == setOf("tmdb:101", "tmdb:202")
            }
        }
        assertTrue(published)
        assertEquals(setOf("tmdb:101", "tmdb:202"), displayed.resumeItems.map { it.contentId }.toSet())
        assertEquals(listOf("tmdb:202"), writes.single().resumeItems.map { it.contentId })
    }

    @Test
    fun `persisted local continue watching rows are removed on snapshot load`() = runTest {
        val writes = mutableListOf<ContinueWatchingSnapshot>()
        val persisted = ContinueWatchingSnapshot(
            resumeItems = listOf(progress("tmdb:101", WatchProgress.SOURCE_LOCAL)),
            updatedAtMs = 30L
        )
        val service = buildService(writes, persistedSnapshot = persisted)

        service.reloadPersistedSnapshotForActiveProfile(clearWhenMissing = false)

        val displayed = service.observeProfileSnapshot(PROFILE_ID).first()
        assertTrue(displayed.resumeItems.isEmpty())
        assertEquals(emptyList<WatchProgress>(), writes.last().resumeItems)
    }

    private fun buildService(
        writes: MutableList<ContinueWatchingSnapshot>,
        persistedSnapshot: ContinueWatchingSnapshot? = null
    ): ContinueWatchingSnapshotService {
        val snapshotStore = mockk<ContinueWatchingSnapshotStore>(relaxed = true) {
            every { read(any()) } returns persistedSnapshot
            every { write(any(), any()) } answers {
                writes += firstArg<ContinueWatchingSnapshot>()
                Unit
            }
        }
        return ContinueWatchingSnapshotService(
            watchProgressRepository = mockk<WatchProgressRepository>(relaxed = true) {
                every { observeSessionProgress(any()) } returns flowOf(emptyList())
            },
            trackingProgressService = mockk(relaxed = true),
            trackingProviderStateService = mockk(relaxed = true) {
                every { stateForProfile(any()) } returns emptyFlow()
            },
            traktSettingsDataStore = mockk<TraktSettingsDataStore>(relaxed = true) {
                every { dismissedNextUpKeys } returns flowOf(emptySet())
            },
            metadataDiskCacheStore = mockk<MetadataDiskCacheStore>(relaxed = true),
            snapshotStore = snapshotStore
        )
    }

    private fun progress(contentId: String, source: String): WatchProgress =
        WatchProgress(
            contentId = contentId,
            contentType = "movie",
            name = contentId,
            poster = null,
            backdrop = null,
            logo = null,
            videoId = contentId,
            season = null,
            episode = null,
            episodeTitle = null,
            position = 50L,
            duration = 100L,
            lastWatched = 1_000L + contentId.substringAfter(':').toLong(),
            progressPercent = 50f,
            source = source
        )

    private companion object {
        const val PROFILE_ID = 1
    }
}
