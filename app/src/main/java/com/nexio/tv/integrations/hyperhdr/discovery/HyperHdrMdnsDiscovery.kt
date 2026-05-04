package com.nexio.tv.integrations.hyperhdr.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Browses for HyperHDR servers on the local network via mDNS.
 *
 * HyperHDR's `WebServer` registers exactly one Bonjour service —
 * `_hyperhdr-http._tcp` — pointing at the HTTP/web port (default 8090). That
 * port also hosts `/json-rpc` over HTTP, which is what Nexio's
 * [HyperHdrJsonApiClient] talks to. The raw-TCP JSON server (19444) and
 * flatbuffers server (19400) are NOT advertised, so for those we fall back to
 * HyperHDR's documented defaults.
 *
 * See `HyperHDR/sources/bonjour/DiscoveryRecord.cpp` and
 * `HyperHDR/sources/webserver/WebServer.cpp` upstream.
 */
class HyperHdrMdnsDiscovery(
    private val nsd: NsdManager,
    private val wifiManager: WifiManager?,
) {
    companion object {
        private const val TYPE_HTTP = "_hyperhdr-http._tcp"
        private const val DEFAULT_FLATBUF_PORT = 19400
        private const val TAG = "HyperHdrMdns"
    }

    private val aggregator = DiscoveryAggregator()
    private val _servers = MutableStateFlow<List<DiscoveredServer>>(emptyList())
    val servers: StateFlow<List<DiscoveredServer>> = _servers.asStateFlow()

    private var listener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start() {
        if (listener != null) return
        multicastLock = wifiManager?.createMulticastLock(TAG)?.apply {
            setReferenceCounted(true)
            acquire()
        }
        listener = makeListener().also {
            nsd.discoverServices(TYPE_HTTP, NsdManager.PROTOCOL_DNS_SD, it)
        }
    }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
        multicastLock = null
    }

    private fun makeListener() = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(s: String?, errorCode: Int) {}
        override fun onStopDiscoveryFailed(s: String?, errorCode: Int) {}
        override fun onDiscoveryStarted(s: String?) {}
        override fun onDiscoveryStopped(s: String?) {}

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(s: NsdServiceInfo?, errorCode: Int) {}
                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    val host = resolved.host?.hostAddress ?: return
                    aggregator.onResolved(
                        name = resolved.serviceName ?: host,
                        host = host,
                        httpPort = resolved.port,
                        flatbufPort = DEFAULT_FLATBUF_PORT,
                    )
                    _servers.value = aggregator.servers()
                }
            })
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            aggregator.onLost(serviceInfo.serviceName ?: return)
            _servers.value = aggregator.servers()
        }
    }
}

/** Small factory so the ViewModel doesn't have to plumb system-service casts. */
fun HyperHdrMdnsDiscovery(context: Context): HyperHdrMdnsDiscovery =
    HyperHdrMdnsDiscovery(
        nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager,
        wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager,
    )
