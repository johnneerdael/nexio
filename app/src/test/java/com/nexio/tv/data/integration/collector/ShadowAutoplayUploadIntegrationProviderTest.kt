package com.nexio.tv.data.integration.collector

import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.IntegrationCallSpec
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.data.integration.collector.transport.ShadowAutoplayUploadTransport
import com.nexio.tv.data.integration.collector.transport.ShadowAutoplayUploadTransportResult
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShadowAutoplayUploadIntegrationProviderTest {

    @Test
    fun `uploadEvent delegates through runtime call with shadow collector provider`() = runTest {
        val runtime = mockk<IntegrationRuntime>(relaxed = true)
        val transport = mockk<ShadowAutoplayUploadTransport>()
        coEvery {
            transport.uploadEvent(
                baseUrl = "https://collector.example",
                token = "write-token",
                envelopeJson = """{"sentAtMs":1234,"client":{},"payload":{}}"""
            )
        } returns ShadowAutoplayUploadTransportResult(
            statusCode = 200,
            isSuccessful = true
        )

        val provider = ShadowAutoplayUploadIntegrationProvider(
            runtime = runtime,
            transport = transport
        )
        val specSlot = slot<IntegrationCallSpec<Unit>>()
        coEvery { runtime.call(capture(specSlot)) } coAnswers {
            firstArg<IntegrationCallSpec<Unit>>().call()
        }
        val result = provider.uploadEvent(
            baseUrl = "https://collector.example",
            token = "write-token",
            envelopeJson = """{"sentAtMs":1234,"client":{},"payload":{}}"""
        )

        assertTrue(result is IntegrationCallResult.Success)
        assertEquals(IntegrationProvider.SHADOW_COLLECTOR, specSlot.captured.provider)
        assertEquals(IntegrationWorkClass.USER_VISIBLE, specSlot.captured.workClass)
    }

    @Test
    fun `uploadEvent maps HTTP failure to http call result`() = runTest {
        val runtime = mockk<IntegrationRuntime>(relaxed = true)
        val transport = mockk<ShadowAutoplayUploadTransport>()
        coEvery { transport.uploadEvent(any(), any(), any()) } returns ShadowAutoplayUploadTransportResult(
            statusCode = 503,
            isSuccessful = false
        )

        val provider = ShadowAutoplayUploadIntegrationProvider(
            runtime = runtime,
            transport = transport
        )
        val specSlot = slot<IntegrationCallSpec<Unit>>()
        coEvery { runtime.call(capture(specSlot)) } coAnswers {
            firstArg<IntegrationCallSpec<Unit>>().call()
        }
        val result = provider.uploadEvent(
            baseUrl = "https://collector.example",
            token = "write-token",
            envelopeJson = """{}"""
        )

        assertTrue(result is IntegrationCallResult.HttpError)
        assertEquals(503, (result as IntegrationCallResult.HttpError).statusCode)
    }

    @Test
    fun `uploadEvent maps network failure to network result`() = runTest {
        val runtime = mockk<IntegrationRuntime>(relaxed = true)
        val transport = mockk<ShadowAutoplayUploadTransport>()
        coEvery { transport.uploadEvent(any(), any(), any()) } throws IllegalStateException("boom")

        val provider = ShadowAutoplayUploadIntegrationProvider(
            runtime = runtime,
            transport = transport
        )
        val specSlot = slot<IntegrationCallSpec<Unit>>()
        coEvery { runtime.call(capture(specSlot)) } coAnswers {
            firstArg<IntegrationCallSpec<Unit>>().call()
        }

        val result = provider.uploadEvent(
            baseUrl = "https://collector.example",
            token = "write-token",
            envelopeJson = """{}"""
        )

        assertTrue(result is IntegrationCallResult.NetworkError)
        assertTrue((result as IntegrationCallResult.NetworkError).throwable is IllegalStateException)
    }
}
