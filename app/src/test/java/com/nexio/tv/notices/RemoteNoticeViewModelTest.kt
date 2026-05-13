package com.nexio.tv.notices

import com.nexio.tv.notices.model.RemoteNoticeDisplay
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteNoticeViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `init fetches notice and shows dialog`() = runTest(dispatcher) {
        val repository = mockk<RemoteNoticeRepository>()
        val preferences = mockk<RemoteNoticePreferences>(relaxed = true)
        coEvery { repository.fetchStartupNotice() } returns display()

        val viewModel = RemoteNoticeViewModel(repository, preferences)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isChecking)
        assertTrue(viewModel.uiState.value.showDialog)
        assertEquals("notice-1", viewModel.uiState.value.notice?.id)
    }

    @Test
    fun `init hides dialog and stops checking when repository has no notice`() = runTest(dispatcher) {
        val repository = mockk<RemoteNoticeRepository>()
        val preferences = mockk<RemoteNoticePreferences>(relaxed = true)
        coEvery { repository.fetchStartupNotice() } returns null

        val viewModel = RemoteNoticeViewModel(repository, preferences)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isChecking)
        assertFalse(viewModel.uiState.value.showDialog)
        assertNull(viewModel.uiState.value.notice)
    }

    @Test
    fun `dismiss marks current notice seen`() = runTest(dispatcher) {
        val repository = mockk<RemoteNoticeRepository>()
        val preferences = mockk<RemoteNoticePreferences>(relaxed = true)
        coEvery { repository.fetchStartupNotice() } returns display()

        val viewModel = RemoteNoticeViewModel(repository, preferences)
        advanceUntilIdle()

        viewModel.dismissNotice()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showDialog)
        assertNull(viewModel.uiState.value.notice)
        coVerify(exactly = 1) { preferences.markSeen("notice-1") }
    }

    @Test
    fun `suppress for startup hides without marking seen`() = runTest(dispatcher) {
        val repository = mockk<RemoteNoticeRepository>()
        val preferences = mockk<RemoteNoticePreferences>(relaxed = true)
        coEvery { repository.fetchStartupNotice() } returns display()

        val viewModel = RemoteNoticeViewModel(repository, preferences)
        advanceUntilIdle()

        viewModel.suppressForStartup()

        assertFalse(viewModel.uiState.value.showDialog)
        assertNull(viewModel.uiState.value.notice)
        coVerify(exactly = 0) { preferences.markSeen(any()) }
    }

    private fun display() = RemoteNoticeDisplay(
        id = "notice-1",
        title = "Important",
        markdown = "# Important",
        markdownUrl = "https://raw.githubusercontent.com/johnneerdael/nexio/main/notices/notice-1.md",
        publishedAt = "2026-05-12T12:01:00Z"
    )
}
