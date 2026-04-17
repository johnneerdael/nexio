package com.nexio.tv.data.repository.device

import com.nexio.tv.data.local.DeviceCapabilityStoreContract
import com.nexio.tv.data.repository.benchmark.DeviceCapabilitySnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceCapabilityRepositoryTest {

    @Test
    fun `ensure cached reuses existing snapshot`() = runTest(UnconfinedTestDispatcher()) {
        val existing = snapshot(1L)
        var captureCount = 0
        val repository = DeviceCapabilityRepository(
            store = FakeStore(existing),
            capture = {
                captureCount += 1
                snapshot(2L)
            }
        )

        assertEquals(existing, repository.ensureCached())
        assertEquals(0, captureCount)
    }

    @Test
    fun `ensure cached captures and stores snapshot when missing`() = runTest(UnconfinedTestDispatcher()) {
        val captured = snapshot(2L)
        val store = FakeStore(null)
        val repository = DeviceCapabilityRepository(
            store = store,
            capture = { captured }
        )

        assertEquals(captured, repository.ensureCached())
        assertEquals(captured, store.latestSnapshot.first())
    }

    @Test
    fun `snapshot for autoplay ensures a cached snapshot exists`() = runTest(UnconfinedTestDispatcher()) {
        val captured = snapshot(3L)
        val repository = DeviceCapabilityRepository(
            store = FakeStore(null),
            capture = { captured }
        )

        assertEquals(captured, repository.snapshotForAutoplay())
    }

    private fun snapshot(capturedAtMs: Long): DeviceCapabilitySnapshot {
        return DeviceCapabilitySnapshot(
            model = "AM9 PRO",
            manufacturer = "UGOOS",
            sdkInt = 34,
            capturedAtMs = capturedAtMs
        )
    }

    private class FakeStore(initial: DeviceCapabilitySnapshot?) : DeviceCapabilityStoreContract {
        private val state = MutableStateFlow(initial)
        override val latestSnapshot: Flow<DeviceCapabilitySnapshot?> = state
        override suspend fun save(snapshot: DeviceCapabilitySnapshot) {
            state.value = snapshot
        }
    }
}
