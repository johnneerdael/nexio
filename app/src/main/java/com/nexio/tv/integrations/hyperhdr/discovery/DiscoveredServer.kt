package com.nexio.tv.integrations.hyperhdr.discovery

/**
 * A HyperHDR server seen on the local network via mDNS.
 *
 * @property name        the friendly Bonjour instance name (typically `hyperhdr:<port>`)
 * @property host        IPv4/IPv6 address of the resolved service
 * @property httpPort    the advertised port — HyperHDR's web server, which also
 *                       serves `/json-rpc` over HTTP. Same value Nexio stores in
 *                       [HyperHdrConfig.jsonPort].
 * @property flatbufPort default raw-TCP flatbuffers port (19400). HyperHDR does
 *                       not advertise this separately, so we assume the upstream
 *                       default and let the user override in manual settings.
 */
data class DiscoveredServer(
    val name: String,
    val host: String,
    val httpPort: Int,
    val flatbufPort: Int,
)
