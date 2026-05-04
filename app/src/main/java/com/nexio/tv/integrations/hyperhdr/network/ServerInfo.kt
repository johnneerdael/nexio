package com.nexio.tv.integrations.hyperhdr.network

/**
 * Slim subset of HyperHDR's serverinfo response. The Test-connection UI uses
 * [hostname] and [instanceName]; the FormatDetector uses [supportsP010] to
 * decide whether HDR sources should be sent as P010 (fork) or NV12 (stock).
 *
 * Stock HyperHDR returns no `flatbuffer` block, so [flatbufferFormats] is
 * empty and [supportsP010] is false against stock — exactly the historical
 * compatibility envelope.
 */
data class ServerInfo(
    val hostname: String,
    val instanceId: Int,
    val instanceName: String?,
    val flatbufferFormats: Set<String> = emptySet(),
    val flatbufferWireVersion: Int? = null,
) {
    val supportsP010: Boolean get() = "P010Image" in flatbufferFormats
}
