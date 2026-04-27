package com.nexio.tv.data.repository.benchmark

import android.os.Build
import com.google.gson.JsonParser
import com.nexio.tv.data.local.PlayerSettings
import com.nexio.tv.data.local.PlayerSettingsDataStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CollectorUploadersTest {

    @Test
    fun `shadow autoplay uploader includes android id in client envelope`() = runTest {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(200))
        val uploader = ShadowAutoplayCollectionUploader(
            playerSettingsDataStore = playerSettingsDataStore(
                PlayerSettings(shadowAutoplayDataCollectionEnabled = true)
            ),
            okHttpClient = OkHttpClient(),
            logger = mockk<ShadowAutoPlayDecisionLogger>().also {
                every { it.encode(any()) } returns """{"event_version":1,"event_type":"shadow_autoplay_decision"}"""
            },
            baseUrlProvider = { server.url("/").toString().trimEnd('/') },
            tokenProvider = { "write-token" },
            clientInfoProvider = { clientInfoJson("shadow-android-id") }
        )

        uploader.submitIfEnabled(mockk(relaxed = true))

        val request = server.takeRequest()
        val envelope = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        assertEquals("shadow-android-id", envelope.getAsJsonObject("client").get("androidId").asString)
        server.shutdown()
    }

    @Test
    fun `device capability uploader posts envelope when data collection is enabled`() = runTest {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true,"id":"device-x"}"""))

        val uploader = DeviceCapabilityReportUploader(
            playerSettingsDataStore = playerSettingsDataStore(
                PlayerSettings(shadowAutoplayDataCollectionEnabled = true)
            ),
            deviceCapabilityRepository = mockk<com.nexio.tv.data.repository.device.DeviceCapabilityRepository>().also {
                coEvery { it.snapshotForAutoplay() } returns sampleSnapshot()
            },
            okHttpClient = OkHttpClient(),
            baseUrlProvider = { server.url("/").toString().trimEnd('/') },
            tokenProvider = { "write-token" },
            clientInfoProvider = { clientInfoJson("device-x") }
        )

        uploader.submitOnceIfEnabled()

        val request = server.takeRequest()
        assertEquals("/api/v1/device-capability-reports", request.path)
        assertEquals("Bearer write-token", request.getHeader("Authorization"))
        val envelope = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        assertEquals("device-x", envelope.getAsJsonObject("client").get("androidId").asString)
        assertEquals(34, envelope.getAsJsonObject("report").get("sdkInt").asInt)
        server.shutdown()
    }

    @Test
    fun `device capability uploader does not post when data collection is disabled`() = runTest {
        val server = MockWebServer()
        server.start()

        val uploader = DeviceCapabilityReportUploader(
            playerSettingsDataStore = playerSettingsDataStore(
                PlayerSettings(shadowAutoplayDataCollectionEnabled = false)
            ),
            deviceCapabilityRepository = mockk<com.nexio.tv.data.repository.device.DeviceCapabilityRepository>().also {
                coEvery { it.snapshotForAutoplay() } returns sampleSnapshot()
            },
            okHttpClient = OkHttpClient(),
            baseUrlProvider = { server.url("/").toString().trimEnd('/') },
            tokenProvider = { "write-token" },
            clientInfoProvider = { clientInfoJson("device-x") }
        )

        uploader.submitOnceIfEnabled()

        assertEquals(0, server.requestCount)
        server.shutdown()
    }

    @Test
    fun `device capability uploader posts at most once per instance`() = runTest {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200))

        val uploader = DeviceCapabilityReportUploader(
            playerSettingsDataStore = playerSettingsDataStore(
                PlayerSettings(shadowAutoplayDataCollectionEnabled = true)
            ),
            deviceCapabilityRepository = mockk<com.nexio.tv.data.repository.device.DeviceCapabilityRepository>().also {
                coEvery { it.snapshotForAutoplay() } returns sampleSnapshot()
            },
            okHttpClient = OkHttpClient(),
            baseUrlProvider = { server.url("/").toString().trimEnd('/') },
            tokenProvider = { "write-token" },
            clientInfoProvider = { clientInfoJson("device-x") }
        )

        uploader.submitOnceIfEnabled()
        uploader.submitOnceIfEnabled()
        uploader.submitOnceIfEnabled()

        assertEquals(1, server.requestCount)
        server.shutdown()
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
