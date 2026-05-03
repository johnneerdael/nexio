package com.nexio.tv.integrations.hyperhdr.network

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test

class HyperHdrJsonApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: HyperHdrJsonApiClient

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        client = HyperHdrJsonApiClient(host = server.hostName, port = server.port)
    }

    @After
    fun tearDown() { server.shutdown() }

    @Test
    fun `serverInfo parses hostname and instanceId from response`() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"success":true,"command":"serverinfo","tan":1,
             "info":{"hostname":"hyperhdr-server","instance":[{"instance":0,"friendly_name":"LED Strip","running":true}]}}
        """.trimIndent()))

        val info = client.serverInfo()

        assertThat(info.hostname).isEqualTo("hyperhdr-server")
        assertThat(info.instanceId).isEqualTo(0)
        assertThat(info.instanceName).isEqualTo("LED Strip")

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/json-rpc")
        val body = JSONObject(request.body.readUtf8())
        assertThat(body.getString("command")).isEqualTo("serverinfo")
    }

    @Test
    fun `serverInfo throws JsonApiError when success is false`() = runTest {
        server.enqueue(MockResponse().setBody("""{"success":false,"error":"bad request"}"""))

        try {
            client.serverInfo()
            error("expected JsonApiError")
        } catch (e: JsonApiError) {
            assertThat(e.message).contains("bad request")
        }
    }

    @Test
    fun `setHdrVideoMode sends videomode command with HDR field`() = runTest {
        server.enqueue(MockResponse().setBody("""{"success":true,"command":"videomode","tan":1}"""))

        client.setHdrVideoMode(hdr = true)

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        assertThat(body.getString("command")).isEqualTo("videomode")
        assertThat(body.getInt("HDR")).isEqualTo(1)
    }

    @Test
    fun `setHdrVideoMode sends HDR=0 when hdr is false`() = runTest {
        server.enqueue(MockResponse().setBody("""{"success":true,"command":"videomode","tan":1}"""))

        client.setHdrVideoMode(hdr = false)

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        assertThat(body.getInt("HDR")).isEqualTo(0)
    }
}
