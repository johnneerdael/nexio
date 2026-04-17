package com.nexio.tv.data.local

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.nexio.tv.data.repository.benchmark.AudioEncodingSupport
import com.nexio.tv.data.repository.benchmark.CodecSupport
import com.nexio.tv.data.repository.benchmark.DeviceAudioOutputCapabilities
import com.nexio.tv.data.repository.benchmark.DeviceCapabilitySnapshot
import com.nexio.tv.data.repository.benchmark.DeviceHdrType
import com.nexio.tv.data.repository.benchmark.DeviceVideoDecodeCapabilities
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceCapabilityStoreTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = TestScope(dispatcher + Job())
    private val tempFiles = mutableListOf<File>()

    @After
    fun tearDown() {
        tempFiles.forEach { it.deleteRecursively() }
        scope.cancel()
    }

    @Test
    fun `latest snapshot defaults to null`() = runTest(dispatcher) {
        val store = store()

        assertNull(store.latestSnapshot.first())
    }

    @Test
    fun `saved snapshot is restored`() = runTest(dispatcher) {
        val store = store()
        val snapshot = snapshot()

        store.save(snapshot)

        assertEquals(snapshot, store.latestSnapshot.first())
    }

    @Test
    fun `clear removes stored snapshot`() = runTest(dispatcher) {
        val store = store()
        store.save(snapshot())

        store.clear()

        assertNull(store.latestSnapshot.first())
    }

    private fun store(): DeviceCapabilityStore {
        val file = File.createTempFile("device-capability", ".preferences_pb").also {
            it.delete()
            tempFiles += it
        }
        return DeviceCapabilityStore(
            dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        )
    }

    private fun snapshot(): DeviceCapabilitySnapshot {
        return DeviceCapabilitySnapshot(
            model = "AM9 PRO",
            manufacturer = "UGOOS",
            sdkInt = 34,
            displayHdrTypes = setOf(DeviceHdrType.HDR10),
            videoDecode = DeviceVideoDecodeCapabilities(
                hevc = CodecSupport(true, false, true)
            ),
            audioOutput = DeviceAudioOutputCapabilities(
                eac3 = AudioEncodingSupport(true, true),
                truehd = AudioEncodingSupport(false, false)
            ),
            capturedAtMs = 42L
        )
    }
}
