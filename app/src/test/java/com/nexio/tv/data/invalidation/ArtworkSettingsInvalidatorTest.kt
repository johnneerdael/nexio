package com.nexio.tv.data.invalidation

import com.nexio.tv.core.artwork.ArtworkProviderSettingsSource
import com.nexio.tv.data.local.HydratedHomeOverlayStore
import com.nexio.tv.domain.model.ArtworkProviderChoiceKey
import com.nexio.tv.domain.model.ArtworkProviderSelectionSettings
import com.nexio.tv.domain.model.ArtworkProviderSettings
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ArtworkSettingsInvalidatorTest {

    private class FakeSource(
        override val settings: Flow<ArtworkProviderSettings>
    ) : ArtworkProviderSettingsSource

    @Test fun `first emission does NOT mark all stale`() = runTest {
        val flow = MutableStateFlow(ArtworkProviderSettings())
        val source = FakeSource(flow)
        val store: HydratedHomeOverlayStore = mockk(relaxed = true)
        val invalidator = ArtworkSettingsInvalidator(source, store)
        invalidator.start(this)
        advanceUntilIdle()
        coVerify(exactly = 0) { store.markStaleAll(any()) }
    }

    @Test fun `same-signature re-emission does NOT mark all stale`() = runTest {
        val flow = MutableStateFlow(ArtworkProviderSettings())
        val source = FakeSource(flow)
        val store: HydratedHomeOverlayStore = mockk(relaxed = true)
        val invalidator = ArtworkSettingsInvalidator(source, store)
        invalidator.start(this)
        advanceUntilIdle()
        // api key change does NOT affect signature
        flow.update { it.copy(rpdbApiKey = "new-key") }
        advanceUntilIdle()
        coVerify(exactly = 0) { store.markStaleAll(any()) }
    }

    @Test fun `signature change triggers markStaleAll`() = runTest {
        val flow = MutableStateFlow(ArtworkProviderSettings())
        val source = FakeSource(flow)
        val store: HydratedHomeOverlayStore = mockk(relaxed = true)
        val invalidator = ArtworkSettingsInvalidator(source, store)
        invalidator.start(this)
        advanceUntilIdle()
        flow.update {
            it.copy(
                selection = ArtworkProviderSelectionSettings(
                    posterProvider = ArtworkProviderChoiceKey.RPDB
                )
            )
        }
        advanceUntilIdle()
        coVerify(exactly = 1) { store.markStaleAll("settings_change") }
    }
}
