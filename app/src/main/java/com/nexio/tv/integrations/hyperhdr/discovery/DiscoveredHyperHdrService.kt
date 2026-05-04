package com.nexio.tv.integrations.hyperhdr.discovery

data class DiscoveredHyperHdrService(
    val hostname: String,        // e.g. "hyperhdr-server" or "192.168.1.10"
    val ipAddress: String,        // resolved IPv4 — what we put in HyperHdrConfig.host
    val flatbufPort: Int,         // 19400 by default
    val jsonPort: Int?,           // 19444 by default; null if not advertised
)
