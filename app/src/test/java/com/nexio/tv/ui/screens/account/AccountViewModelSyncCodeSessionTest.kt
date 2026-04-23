package com.nexio.tv.ui.screens.account

import android.content.Context
import com.nexio.tv.core.auth.AuthManager
import com.nexio.tv.core.sync.AccountSettingsSyncService
import com.nexio.tv.core.sync.AccountSyncRefreshNotifier
import com.nexio.tv.core.sync.AddonSyncService
import com.nexio.tv.data.repository.AddonRepositoryImpl
import com.nexio.tv.domain.model.AuthState
import com.nexio.tv.domain.repository.SyncRepository
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
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountViewModelSyncCodeSessionTest {
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
    fun `generate sync code refuses stale full-account session`() = runTest(dispatcher) {
        val syncRepository = mockk<SyncRepository>(relaxed = true)
        val viewModel = createViewModel(
            authState = AuthState.FullAccount("user-1", "user@example.com"),
            currentSessionUserId = null,
            syncRepository = syncRepository
        )

        viewModel.generateSyncCode("1234")
        advanceUntilIdle()

        coVerify(exactly = 0) { syncRepository.generateSyncCode(any()) }
        assertEquals("Sign in with an account first.", viewModel.uiState.value.error)
    }

    @Test
    fun `get sync code refuses stale full-account session`() = runTest(dispatcher) {
        val syncRepository = mockk<SyncRepository>(relaxed = true)
        val viewModel = createViewModel(
            authState = AuthState.FullAccount("user-1", "user@example.com"),
            currentSessionUserId = null,
            syncRepository = syncRepository
        )

        viewModel.getSyncCode("1234")
        advanceUntilIdle()

        coVerify(exactly = 0) { syncRepository.getSyncCode(any()) }
        assertEquals("Sign in with an account first.", viewModel.uiState.value.error)
    }

    @Test
    fun `claim sync code refuses stale full-account session`() = runTest(dispatcher) {
        val syncRepository = mockk<SyncRepository>(relaxed = true)
        val viewModel = createViewModel(
            authState = AuthState.FullAccount("user-1", "user@example.com"),
            currentSessionUserId = null,
            syncRepository = syncRepository
        )

        viewModel.claimSyncCode(code = "ABCD", pin = "1234")
        advanceUntilIdle()

        coVerify(exactly = 0) { syncRepository.claimSyncCode(any(), any(), any()) }
        assertEquals("Sign in with an account first.", viewModel.uiState.value.error)
    }

    private fun createViewModel(
        authState: AuthState,
        currentSessionUserId: String?,
        syncRepository: SyncRepository
    ): AccountViewModel {
        val authManager = mockk<AuthManager>(relaxed = true) {
            every { this@mockk.authState } returns MutableStateFlow(authState)
            every { this@mockk.currentSessionUserId } returns currentSessionUserId
            every { this@mockk.isAuthenticated } returns (authState is AuthState.FullAccount)
        }

        return AccountViewModel(
            authManager = authManager,
            syncRepository = syncRepository,
            addonSyncService = mockk<AddonSyncService>(relaxed = true),
            accountSettingsSyncService = mockk<AccountSettingsSyncService>(relaxed = true),
            addonRepository = mockk<AddonRepositoryImpl>(relaxed = true),
            accountSyncRefreshNotifier = mockk<AccountSyncRefreshNotifier>(relaxed = true),
            context = mockk<Context>(relaxed = true)
        )
    }
}
