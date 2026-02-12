package com.nuvio.tv.data.repository

import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.domain.model.WatchProgress
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WatchProgressRepositoryImplTest {

    private val localPrefs: WatchProgressPreferences = mockk(relaxed = true)
    private val authStore: TraktAuthDataStore = mockk(relaxed = true)
    private val traktProgressService: TraktProgressService = mockk(relaxed = true)

    private val authFlow = MutableStateFlow(false)
    private val localProgress = WatchProgress(
        contentId = "tt0111161",
        contentType = "movie",
        name = "The Shawshank Redemption",
        poster = null,
        backdrop = null,
        logo = null,
        videoId = "tt0111161",
        season = null,
        episode = null,
        episodeTitle = null,
        position = 120_000,
        duration = 720_000,
        lastWatched = 1_000
    )
    private val traktProgress = localProgress.copy(
        progressPercent = 41.5f,
        position = 0L,
        duration = 0L,
        source = WatchProgress.SOURCE_TRAKT_PLAYBACK
    )

    @Test
    fun `allProgress routes based on authentication state`() = runTest {
        every { authStore.isAuthenticated } returns authFlow
        every { localPrefs.allProgress } returns flowOf(listOf(localProgress))
        every { traktProgressService.observeAllProgress() } returns flowOf(listOf(traktProgress))

        val repository = WatchProgressRepositoryImpl(
            watchProgressPreferences = localPrefs,
            traktAuthDataStore = authStore,
            traktProgressService = traktProgressService
        )

        authFlow.value = false
        assertEquals(localProgress, repository.allProgress.first().first())

        authFlow.value = true
        assertEquals(traktProgress, repository.allProgress.first().first())
    }

    @Test
    fun `saveProgress is disabled when authenticated`() = runTest {
        every { authStore.isAuthenticated } returns authFlow
        every { localPrefs.allProgress } returns flowOf(emptyList())
        every { traktProgressService.observeAllProgress() } returns flowOf(emptyList())
        coEvery { localPrefs.saveProgress(any()) } returns Unit

        val repository = WatchProgressRepositoryImpl(
            watchProgressPreferences = localPrefs,
            traktAuthDataStore = authStore,
            traktProgressService = traktProgressService
        )

        authFlow.value = false
        repository.saveProgress(localProgress)
        coVerify(exactly = 1) { localPrefs.saveProgress(any()) }

        authFlow.value = true
        repository.saveProgress(localProgress)
        coVerify(exactly = 1) { localPrefs.saveProgress(any()) }
    }

    @Test
    fun `removeProgress uses trakt destructive remove when authenticated`() = runTest {
        every { authStore.isAuthenticated } returns authFlow
        every { localPrefs.allProgress } returns flowOf(emptyList())
        every { traktProgressService.observeAllProgress() } returns flowOf(emptyList())
        coEvery { localPrefs.removeProgress(any(), any(), any()) } returns Unit
        coEvery { traktProgressService.removeProgress(any(), any(), any()) } returns Unit

        val repository = WatchProgressRepositoryImpl(
            watchProgressPreferences = localPrefs,
            traktAuthDataStore = authStore,
            traktProgressService = traktProgressService
        )

        authFlow.value = true
        repository.removeProgress("tt0111161", null, null)

        coVerify(exactly = 1) { traktProgressService.removeProgress("tt0111161", null, null) }
        coVerify(exactly = 0) { localPrefs.removeProgress(any(), any(), any()) }
    }
}

