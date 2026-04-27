package com.nexio.tv.ui.screens.settings

import com.nexio.tv.core.trace.TraceBuildInfo
import com.nexio.tv.core.trace.TraceMode
import com.nexio.tv.core.trace.TraceSessionManager
import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeTraceSettingsViewModelTest {
    @get:Rule val tmp = TemporaryFolder()

    @Before fun setMainDispatcher() { kotlinx.coroutines.Dispatchers.setMain(UnconfinedTestDispatcher()) }

    private fun manager() = TraceSessionManager(
        tracesRoot = tmp.newFolder("traces"),
        gson = Gson(),
        clock = { 1_700_000_000_000L },
        buildInfo = TraceBuildInfo(
            appVersion = "1.0",
            buildType = "debug",
            gitSha = null,
            deviceModel = "Pixel",
            androidVersion = "14"
        )
    )

    @Test
    fun `initial state is OFF and inactive`() = runTest {
        val store = mockk<com.nexio.tv.data.local.TraceSettingsDataStore>()
        coEvery { store.mode } returns MutableStateFlow(TraceMode.OFF)
        val vm = RuntimeTraceSettingsViewModel(store, manager())
        val state = vm.uiState.first()
        assertEquals(TraceMode.OFF, state.mode)
        assertFalse(state.isSessionActive)
    }

    @Test
    fun `selectMode persists to data store`() = runTest {
        val store = mockk<com.nexio.tv.data.local.TraceSettingsDataStore>(relaxed = true)
        coEvery { store.mode } returns MutableStateFlow(TraceMode.OFF)
        val vm = RuntimeTraceSettingsViewModel(store, manager())
        vm.selectMode(TraceMode.SAFE_METADATA_RUNTIME)
        coVerify { store.setMode(TraceMode.SAFE_METADATA_RUNTIME) }
    }

    @Test
    fun `startSession activates session manager`() = runTest {
        val store = mockk<com.nexio.tv.data.local.TraceSettingsDataStore>()
        coEvery { store.mode } returns MutableStateFlow(TraceMode.SAFE_METADATA_RUNTIME)
        val mgr = manager()
        val vm = RuntimeTraceSettingsViewModel(store, mgr)
        vm.startSession()
        // After start, manager should have an active session
        assertTrue(mgr.activeSession() != null)
        val state = vm.uiState.first { it.isSessionActive }
        assertTrue(state.sessionId != null)
    }

    @Test
    fun `stopSession deactivates session manager`() = runTest {
        val store = mockk<com.nexio.tv.data.local.TraceSettingsDataStore>()
        coEvery { store.mode } returns MutableStateFlow(TraceMode.SAFE_METADATA_RUNTIME)
        val mgr = manager()
        val vm = RuntimeTraceSettingsViewModel(store, mgr)
        vm.startSession()
        vm.stopSession()
        assertEquals(null, mgr.activeSession())
    }
}
