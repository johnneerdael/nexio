package com.nexio.tv.data.repository.benchmark

import android.os.Build
import com.google.gson.JsonParser
import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.data.integration.collector.ShadowAutoplayUploadIntegrationProvider
import com.nexio.tv.data.local.PlayerSettings
import com.nexio.tv.data.local.PlayerSettingsDataStore
import com.nexio.tv.data.repository.device.DeviceCapabilityRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

@OptIn(ExperimentalCoroutinesApi::class)
class CollectorUploadersTest {

    @Test
    fun `shadow autoplay uploader delegates to integration provider with preserved envelope`() = runTest {
        val provider = mockk<ShadowAutoplayUploadIntegrationProvider>(relaxed = true)
        val uploader = ShadowAutoplayCollectionUploader(
            playerSettingsDataStore = playerSettingsDataStore(
                PlayerSettings(shadowAutoplayDataCollectionEnabled = true)
            ),
            uploadIntegrationProvider = provider,
            logger = mockk<ShadowAutoPlayDecisionLogger>().also {
                every { it.encode(any()) } returns """{"event_version":1,"event_type":"shadow_autoplay_decision"}"""
            },
            baseUrlProvider = { "https://example.com/api" },
            tokenProvider = { "write-token" },
            clientInfoProvider = { clientInfoJson("shadow-android-id") }
        )
        val envelopeSlot = slot<String>()
        coEvery {
            provider.uploadEvent(
                baseUrl = any(),
                token = "write-token",
                envelopeJson = capture(envelopeSlot)
            )
        } returns IntegrationCallResult.Success(Unit)

        uploader.submitIfEnabled(mockk(relaxed = true))

        val envelope = JsonParser.parseString(envelopeSlot.captured).asJsonObject
        val client = envelope.getAsJsonObject("client")
        val payload = envelope.getAsJsonObject("payload")
        assertTrue(envelope.has("sentAtMs"))
        assertEquals("0.38", client.get("appVersion").asString)
        assertEquals("debug", client.get("buildType").asString)
        assertEquals(Build.MODEL ?: "test-device", client.get("deviceModel").asString)
        assertEquals(Build.VERSION.SDK_INT, client.get("sdkInt").asInt)
        assertEquals("shadow-android-id", client.get("androidId").asString)
        assertEquals(1, payload.get("event_version").asInt)
        assertEquals("shadow_autoplay_decision", payload.get("event_type").asString)
        coVerify(exactly = 1) { provider.uploadEvent("https://example.com/api", "write-token", any()) }
    }

    @Test
    fun `shadow autoplay uploader rethrows cancellation exception from provider`() = runTest {
        val provider = mockk<ShadowAutoplayUploadIntegrationProvider>()
        coEvery {
            provider.uploadEvent(any(), any(), any())
        } throws CancellationException("cancelled")

        val uploader = ShadowAutoplayCollectionUploader(
            playerSettingsDataStore = playerSettingsDataStore(
                PlayerSettings(shadowAutoplayDataCollectionEnabled = true)
            ),
            uploadIntegrationProvider = provider,
            logger = mockk<ShadowAutoPlayDecisionLogger>().also {
                every { it.encode(any()) } returns "{}"
            },
            baseUrlProvider = { "https://example.com/api" },
            tokenProvider = { "write-token" },
            clientInfoProvider = { clientInfoJson("shadow-android-id") }
        )

        try {
            uploader.submitIfEnabled(mockk(relaxed = true))
            throw AssertionError("Expected CancellationException")
        } catch (error: CancellationException) {
            // expected
        }

        coVerify(exactly = 1) { provider.uploadEvent("https://example.com/api", "write-token", any()) }
    }

    @Test
    fun `device capability uploader delegates to integration provider when data collection is enabled`() = runTest {
        val provider = mockk<ShadowAutoplayUploadIntegrationProvider>()
        val envelopeSlot = slot<String>()
        coEvery {
            provider.uploadDeviceCapabilityReport(
                baseUrl = "https://example.com/api",
                token = "write-token",
                envelopeJson = capture(envelopeSlot)
            )
        } returns IntegrationCallResult.Success(Unit)

        val uploader = DeviceCapabilityReportUploader(
            playerSettingsDataStore = playerSettingsDataStore(
                PlayerSettings(shadowAutoplayDataCollectionEnabled = true)
            ),
            deviceCapabilityRepository = mockk<DeviceCapabilityRepository>().also {
                coEvery { it.snapshotForAutoplay() } returns sampleSnapshot()
            },
            uploadIntegrationProvider = provider,
            baseUrlProvider = { "https://example.com/api" },
            tokenProvider = { "write-token" },
            clientInfoProvider = { clientInfoJson("device-x") }
        )

        uploader.submitOnceIfEnabled()

        val envelope = JsonParser.parseString(envelopeSlot.captured).asJsonObject
        assertTrue(envelope.has("sentAtMs"))
        assertEquals("device-x", envelope.getAsJsonObject("client").get("androidId").asString)
        assertEquals(34, envelope.getAsJsonObject("report").get("sdkInt").asInt)
        coVerify(exactly = 1) {
            provider.uploadDeviceCapabilityReport("https://example.com/api", "write-token", any())
        }
    }

    @Test
    fun `device capability uploader does not post when data collection is disabled`() = runTest {
        val provider = mockk<ShadowAutoplayUploadIntegrationProvider>(relaxed = true)
        val uploader = DeviceCapabilityReportUploader(
            playerSettingsDataStore = playerSettingsDataStore(
                PlayerSettings(shadowAutoplayDataCollectionEnabled = false)
            ),
            deviceCapabilityRepository = mockk<DeviceCapabilityRepository>().also {
                coEvery { it.snapshotForAutoplay() } returns sampleSnapshot()
            },
            uploadIntegrationProvider = provider,
            baseUrlProvider = { "https://example.com/api" },
            tokenProvider = { "write-token" },
            clientInfoProvider = { clientInfoJson("device-x") }
        )

        uploader.submitOnceIfEnabled()

        coVerify(exactly = 0) { provider.uploadDeviceCapabilityReport(any(), any(), any()) }
    }

    @Test
    fun `device capability uploader posts at most once per instance`() = runTest {
        val provider = mockk<ShadowAutoplayUploadIntegrationProvider>()
        coEvery {
            provider.uploadDeviceCapabilityReport(any(), any(), any())
        } returns IntegrationCallResult.Success(Unit)

        val uploader = DeviceCapabilityReportUploader(
            playerSettingsDataStore = playerSettingsDataStore(
                PlayerSettings(shadowAutoplayDataCollectionEnabled = true)
            ),
            deviceCapabilityRepository = mockk<DeviceCapabilityRepository>().also {
                coEvery { it.snapshotForAutoplay() } returns sampleSnapshot()
            },
            uploadIntegrationProvider = provider,
            baseUrlProvider = { "https://example.com/api" },
            tokenProvider = { "write-token" },
            clientInfoProvider = { clientInfoJson("device-x") }
        )

        uploader.submitOnceIfEnabled()
        uploader.submitOnceIfEnabled()
        uploader.submitOnceIfEnabled()

        coVerify(exactly = 1) {
            provider.uploadDeviceCapabilityReport("https://example.com/api", "write-token", any())
        }
    }

    @Test
    fun `device capability uploader rethrows cancellation exception from provider`() = runTest {
        val provider = mockk<ShadowAutoplayUploadIntegrationProvider>()
        coEvery {
            provider.uploadDeviceCapabilityReport(any(), any(), any())
        } throws CancellationException("cancelled")

        val uploader = DeviceCapabilityReportUploader(
            playerSettingsDataStore = playerSettingsDataStore(
                PlayerSettings(shadowAutoplayDataCollectionEnabled = true)
            ),
            deviceCapabilityRepository = mockk<DeviceCapabilityRepository>().also {
                coEvery { it.snapshotForAutoplay() } returns sampleSnapshot()
            },
            uploadIntegrationProvider = provider,
            baseUrlProvider = { "https://example.com/api" },
            tokenProvider = { "write-token" },
            clientInfoProvider = { clientInfoJson("device-x") }
        )

        try {
            uploader.submitOnceIfEnabled()
            throw AssertionError("Expected CancellationException")
        } catch (error: CancellationException) {
            // expected
        }

        coVerify(exactly = 1) {
            provider.uploadDeviceCapabilityReport("https://example.com/api", "write-token", any())
        }
    }

    private fun sampleSnapshot() = DeviceCapabilitySnapshot(
        model = "Google TV Streamer",
        manufacturer = "Google",
        sdkInt = 34,
        displayHdrTypes = setOf(DeviceHdrType.HDR10),
        videoDecode = DeviceVideoDecodeCapabilities(),
        audioOutput = DeviceAudioOutputCapabilities(),
        evidence = null,
        capturedAtMs = 1775519900000L
    )

    private fun playerSettingsDataStore(settings: PlayerSettings): PlayerSettingsDataStore {
        return mockk<PlayerSettingsDataStore>(relaxed = true).also {
            every { it.playerSettings } returns flowOf(settings)
        }
    }

    private fun clientInfoJson(androidId: String) = com.google.gson.JsonObject().apply {
        addProperty("appVersion", "0.38")
        addProperty("buildType", "debug")
        addProperty("deviceModel", Build.MODEL ?: "test-device")
        addProperty("sdkInt", Build.VERSION.SDK_INT)
        addProperty("androidId", androidId)
    }
}
