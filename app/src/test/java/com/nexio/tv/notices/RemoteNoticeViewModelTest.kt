package com.nexio.tv.notices

import com.nexio.tv.notices.model.RemoteNoticeDisplay
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
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
        assertEquals("notice-1", viewModel.uiState.value.notice?.id)
        coVerify(exactly = 0) { preferences.markSeen(any()) }
    }

    @Test
    fun `suppress before fetch completes keeps dialog hidden and does not mark seen`() = runTest(dispatcher) {
        val repository = mockk<RemoteNoticeRepository>()
        val preferences = mockk<RemoteNoticePreferences>(relaxed = true)
        val pendingNotice = CompletableDeferred<RemoteNoticeDisplay?>()
        coEvery { repository.fetchStartupNotice() } coAnswers { pendingNotice.await() }

        val viewModel = RemoteNoticeViewModel(repository, preferences)
        advanceUntilIdle()

        viewModel.suppressForStartup()
        pendingNotice.complete(display())
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isChecking)
        assertFalse(viewModel.uiState.value.showDialog)
        assertNull(viewModel.uiState.value.notice)
        coVerify(exactly = 0) { preferences.markSeen(any()) }
    }

    @Test
    fun `dismissed notice is not reopened by stale in flight check`() = runTest(dispatcher) {
        val repository = mockk<RemoteNoticeRepository>()
        val preferences = mockk<RemoteNoticePreferences>(relaxed = true)
        val secondCheck = CompletableDeferred<RemoteNoticeDisplay?>()
        var fetchCount = 0
        coEvery { repository.fetchStartupNotice() } coAnswers {
            fetchCount += 1
            if (fetchCount == 1) display("notice-1") else secondCheck.await()
        }

        val viewModel = RemoteNoticeViewModel(repository, preferences)
        advanceUntilIdle()
        viewModel.checkForNotice()
        advanceUntilIdle()

        viewModel.dismissNotice()
        secondCheck.complete(display("notice-2"))
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isChecking)
        assertFalse(viewModel.uiState.value.showDialog)
        assertNull(viewModel.uiState.value.notice)
        coVerify(exactly = 1) { preferences.markSeen("notice-1") }
    }

    @Test
    fun `later check result wins over earlier stale result`() = runTest(dispatcher) {
        val repository = mockk<RemoteNoticeRepository>()
        val preferences = mockk<RemoteNoticePreferences>(relaxed = true)
        val firstCheck = CompletableDeferred<RemoteNoticeDisplay?>()
        val secondCheck = CompletableDeferred<RemoteNoticeDisplay?>()
        var fetchCount = 0
        coEvery { repository.fetchStartupNotice() } coAnswers {
            fetchCount += 1
            if (fetchCount == 1) firstCheck.await() else secondCheck.await()
        }

        val viewModel = RemoteNoticeViewModel(repository, preferences)
        advanceUntilIdle()
        viewModel.checkForNotice()
        advanceUntilIdle()

        secondCheck.complete(display("notice-2"))
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.showDialog)
        assertEquals("notice-2", viewModel.uiState.value.notice?.id)

        firstCheck.complete(display("notice-1"))
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.showDialog)
        assertEquals("notice-2", viewModel.uiState.value.notice?.id)
    }

    private fun display(id: String = "notice-1") = RemoteNoticeDisplay(
        id = id,
        title = "Important",
        markdown = "# Important",
        markdownUrl = "https://raw.githubusercontent.com/johnneerdael/nexio/main/notices/$id.md",
        publishedAt = "2026-05-12T12:01:00Z"
    )
}
