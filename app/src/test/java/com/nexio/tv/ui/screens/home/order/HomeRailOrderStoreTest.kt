package com.nexio.tv.ui.screens.home.order

import com.google.gson.Gson
import com.nexio.tv.data.local.LayoutPreferenceDataStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class HomeRailOrderStoreTest {
    private val gson = Gson()
    private val codec = HomeRailOrderStateCodec(gson)
    private val fixedClock = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC)

    @Test
    fun `state defaults to Empty when persisted json is null`() = runTest {
        val layout = mockk<LayoutPreferenceDataStore>(relaxed = true)
        coEvery { layout.homeRailOrderStateJson } returns flowOf(null)
        coEvery { layout.homeCatalogOrderKeys } returns flowOf(emptyList())
        coEvery { layout.disabledHomeCatalogKeys } returns flowOf(emptyList())

        val store = HomeRailOrderStore(
            layoutPreferenceDataStore = layout,
            codec = codec,
            clock = fixedClock,
            scope = TestScope(StandardTestDispatcher(testScheduler)),
        )

        assertEquals(HomeRailOrderState.Empty, store.state.first())
    }

    @Test
    fun `updateOrder persists and bumps version`() = runTest {
        val layout = mockk<LayoutPreferenceDataStore>(relaxed = true)
        val persisted = MutableStateFlow<String?>(null)
        coEvery { layout.homeRailOrderStateJson } returns persisted
        coEvery { layout.homeCatalogOrderKeys } returns flowOf(emptyList())
        coEvery { layout.disabledHomeCatalogKeys } returns flowOf(emptyList())
        coEvery { layout.setHomeRailOrderStateJson(any()) } answers {
            persisted.value = firstArg()
        }

        val store = HomeRailOrderStore(
            layoutPreferenceDataStore = layout,
            codec = codec,
            clock = fixedClock,
            scope = TestScope(StandardTestDispatcher(testScheduler)),
        )

        store.updateOrder(
            orderedKeys = listOf(HomeRailKey("a"), HomeRailKey("b")),
            source = RailOrderMutationSource.ANDROID_ORDER_SCREEN,
            knownLiveKeys = setOf(HomeRailKey("a"), HomeRailKey("b")),
        )
        advanceUntilIdle()

        val state = store.state.first()
        assertEquals(listOf(HomeRailKey("a"), HomeRailKey("b")), state.orderedKeys)
        assertEquals(1L, state.version)
        assertEquals(RailOrderMutationSource.ANDROID_ORDER_SCREEN, state.lastMutationSource)
        coVerify { layout.setHomeRailOrderStateJson(any()) }
    }
}
