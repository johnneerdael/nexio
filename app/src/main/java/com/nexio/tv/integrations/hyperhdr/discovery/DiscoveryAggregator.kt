package com.nexio.tv.integrations.hyperhdr.discovery

/**
 * Collects [DiscoveredServer] records keyed by Bonjour instance name. Pulled out
 * of [HyperHdrMdnsDiscovery] so the merge logic can be unit-tested without an
 * Android NSD stub.
 */
class DiscoveryAggregator {
    private val byName = mutableMapOf<String, DiscoveredServer>()

    fun onResolved(name: String, host: String, httpPort: Int, flatbufPort: Int) {
        byName[name] = DiscoveredServer(name, host, httpPort, flatbufPort)
    }

    fun onLost(name: String) {
        byName.remove(name)
    }

    fun servers(): List<DiscoveredServer> = byName.values.toList()
}
