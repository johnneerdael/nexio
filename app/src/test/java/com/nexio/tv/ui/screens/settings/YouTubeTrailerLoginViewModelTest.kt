package com.nexio.tv.ui.screens.settings

import com.nexio.tv.data.trailer.helper.YouTubeDeviceCodeAuthService
import com.nexio.tv.data.trailer.helper.YouTubeDeviceCodeSession
import com.nexio.tv.data.trailer.helper.YouTubeTrailerAuthManager
import com.nexio.tv.data.trailer.helper.YouTubeTrailerAuthEvent
import com.nexio.tv.data.trailer.helper.YouTubeTrailerAuthUiState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class YouTubeTrailerLoginViewModelTest {

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
    fun `login state is signed out by default`() = runTest(dispatcher) {
        val authManager = mockk<YouTubeTrailerAuthManager>()
        every { authManager.uiState } returns MutableStateFlow(YouTubeTrailerAuthUiState())

        val viewModel = YouTubeTrailerLoginViewModel(authManager)

        assertFalse(viewModel.uiState.value.isSignedIn)
        assertNull(viewModel.uiState.value.userCode)
    }

    @Test
    fun `sign in event starts the device flow`() = runTest(dispatcher) {
        val authManager = mockk<YouTubeTrailerAuthManager>()
        every { authManager.uiState } returns MutableStateFlow(YouTubeTrailerAuthUiState())
        coEvery { authManager.startDeviceFlow() } returns Result.success(
            YouTubeDeviceCodeSession(
                deviceCode = "device-code",
                userCode = "ABCD-EFGH",
                verificationUrl = "https://example.com/verify",
                verificationUrlComplete = "https://example.com/verify?code=ABCD-EFGH",
                expiresInSeconds = 900,
                intervalSeconds = 5
            )
        )

        val viewModel = YouTubeTrailerLoginViewModel(authManager)

        viewModel.onEvent(YouTubeTrailerAuthEvent.SignIn)
        advanceUntilIdle()

        coVerify(exactly = 1) { authManager.startDeviceFlow() }
    }
}
