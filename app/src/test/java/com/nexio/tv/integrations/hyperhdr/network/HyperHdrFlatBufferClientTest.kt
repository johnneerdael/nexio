package com.nexio.tv.integrations.hyperhdr.network

import com.google.common.truth.Truth.assertThat
import hyperhdrnet.Command
import hyperhdrnet.Image
import hyperhdrnet.ImageType
import hyperhdrnet.NV12Image
import hyperhdrnet.P010Image
import hyperhdrnet.Register
import hyperhdrnet.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.nio.ByteBuffer

class HyperHdrFlatBufferClientTest {

    @Test
    fun `connect sends a Register frame with origin and priority`() = runTest {
        FakeHyperHdrServer().use { server ->
            val client = HyperHdrFlatBufferClient(
                host = "127.0.0.1", port = server.port,
                priority = 100, origin = "Nexio-HyperHDR",
            )
            client.connect()
            val frame = withTimeout(2_000) { server.receiveFrame() }
            val req = Request.getRootAsRequest(ByteBuffer.wrap(frame))
            assertThat(req.commandType()).isEqualTo(Command.Register)
            val reg = Register().also { req.command(it) }
            assertThat(reg.origin()).isEqualTo("Nexio-HyperHDR")
            assertThat(reg.priority()).isEqualTo(100)
            client.close()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `sendNv12 emits an Image with NV12 payload, correct strides, and is isolated from caller mutation`() = runTest {
        FakeHyperHdrServer().use { server ->
            val client = HyperHdrFlatBufferClient(host = "127.0.0.1", port = server.port, priority = 100)
            client.connect()
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(2_000) { server.receiveFrame() }   // drain Register
            }
            val w = 4; val h = 2
            val y = ByteArray(w * h) { (it + 1).toByte() }
            val uv = ByteArray(w) { (it + 100).toByte() }
            client.sendNv12(y, uv, w, h, w, w)
            // Caller mutates immediately — client must have copied internally
            for (i in y.indices) y[i] = 0
            for (i in uv.indices) uv[i] = 0
            val frame = withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(2_000) { server.receiveFrame() }
            }
            val req = Request.getRootAsRequest(ByteBuffer.wrap(frame))
            val img = Image().also { req.command(it) }
            val nv12 = NV12Image().also { img.data(it) }
            for (i in 0 until nv12.dataYLength()) {
                assertThat(nv12.dataY(i).toInt() and 0xFF).isEqualTo(i + 1)
            }
            client.close()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `sendP010 emits an Image with P010 payload`() = runTest {
        FakeHyperHdrServer().use { server ->
            val client = HyperHdrFlatBufferClient(host = "127.0.0.1", port = server.port, priority = 100)
            client.connect()
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(2_000) { server.receiveFrame() }   // drain Register
            }
            val w = 4; val h = 2
            // P010 plane sizes: Y = w*h*2 bytes; UV = w*1*2 bytes (per spec).
            val y = ByteArray(w * h * 2) { (it + 1).toByte() }
            val uv = ByteArray(w * 2) { (it + 100).toByte() }
            client.sendP010(y, uv, w, h, strideY = w * 2, strideUv = w * 2)
            val frame = withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(2_000) { server.receiveFrame() }
            }
            val req = Request.getRootAsRequest(ByteBuffer.wrap(frame))
            val img = Image().also { req.command(it) }
            assertThat(img.dataType()).isEqualTo(ImageType.P010Image)
            val p010 = P010Image().also { img.data(it) }
            assertThat(p010.width()).isEqualTo(w)
            assertThat(p010.height()).isEqualTo(h)
            assertThat(p010.strideY()).isEqualTo(w * 2)
            assertThat(p010.strideUv()).isEqualTo(w * 2)
            client.close()
        }
    }
}
