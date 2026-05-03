package com.nexio.tv.integrations.hyperhdr.network

/**
 * Slim subset of HyperHDR's serverinfo response. We only surface fields the Test-connection
 * UI shows; the full response has dozens of fields we don't need.
 */
data class ServerInfo(
    val hostname: String,
    val instanceId: Int,
    val instanceName: String?,
)
