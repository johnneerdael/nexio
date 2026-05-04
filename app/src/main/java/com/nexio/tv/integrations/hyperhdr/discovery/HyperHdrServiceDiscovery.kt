package com.nexio.tv.integrations.hyperhdr.discovery

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Discovers HyperHDR servers on the LAN via mDNS (NsdManager). HyperHDR advertises two
 * service types: `_hyperhdr-flatbuf._tcp` and `_hyperhdr-json._tcp`. We start both
 * discoveries, correlate results by hostname, and emit a merged list to [services].
 *
 * Use [start] from a UI surface that wants live discovery results (e.g. the server-picker
 * dialog), and [stop] when leaving. Discovery is cheap but does broadcast traffic; don't
 * keep it running unless something is consuming the StateFlow.
 */
@Singleton
class HyperHdrServiceDiscovery @Inject constructor(
    private val nsdManager: NsdManager,
) {

    /** Internal raw service result before correlation. */
    data class RawService(val hostname: String, val ipAddress: String, val port: Int)

    private val _services = MutableStateFlow<List<DiscoveredHyperHdrService>>(emptyList())
    val services: StateFlow<List<DiscoveredHyperHdrService>> = _services.asStateFlow()

    private val flatbufResults = mutableSetOf<RawService>()
    private val jsonResults = mutableSetOf<RawService>()

    private var flatbufListener: NsdManager.DiscoveryListener? = null
    private var jsonListener: NsdManager.DiscoveryListener? = null

    fun start() {
        if (flatbufListener != null) return  // already running
        flatbufListener = makeListener(SERVICE_TYPE_FLATBUF) { added, raw ->
            if (added) flatbufResults.add(raw) else flatbufResults.removeAll { it.hostname == raw.hostname }
            recompute()
        }
        jsonListener = makeListener(SERVICE_TYPE_JSON) { added, raw ->
            if (added) jsonResults.add(raw) else jsonResults.removeAll { it.hostname == raw.hostname }
            recompute()
        }
        runCatching {
            nsdManager.discoverServices(SERVICE_TYPE_FLATBUF, NsdManager.PROTOCOL_DNS_SD, flatbufListener)
            nsdManager.discoverServices(SERVICE_TYPE_JSON, NsdManager.PROTOCOL_DNS_SD, jsonListener)
        }.onFailure {
            Log.w(TAG, "Failed to start mDNS discovery", it)
            stop()
        }
    }

    fun stop() {
        flatbufListener?.let { runCatching { nsdManager.stopServiceDiscovery(it) } }
        jsonListener?.let { runCatching { nsdManager.stopServiceDiscovery(it) } }
        flatbufListener = null
        jsonListener = null
        flatbufResults.clear()
        jsonResults.clear()
        _services.value = emptyList()
    }

    private fun recompute() {
        _services.value = mergeServices(flatbufResults.toList(), jsonResults.toList())
    }

    private fun makeListener(
        serviceType: String,
        onChange: (added: Boolean, raw: RawService) -> Unit,
    ): NsdManager.DiscoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(t: String?) {}
        override fun onDiscoveryStopped(t: String?) {}
        override fun onStartDiscoveryFailed(t: String?, errorCode: Int) {
            Log.w(TAG, "mDNS start failed for $t (code $errorCode)")
        }
        override fun onStopDiscoveryFailed(t: String?, errorCode: Int) {}
        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            val name = serviceInfo.serviceName ?: return
            onChange(false, RawService(name, "", serviceInfo.port))
        }
        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(s: NsdServiceInfo?, errorCode: Int) {
                    Log.w(TAG, "Resolve failed: ${s?.serviceName} ($errorCode)")
                }
                override fun onServiceResolved(s: NsdServiceInfo) {
                    val ip = s.host?.hostAddress ?: return
                    onChange(true, RawService(
                        hostname = s.serviceName ?: ip,
                        ipAddress = ip,
                        port = s.port,
                    ))
                }
            })
        }
    }

    companion object {
        private const val TAG = "HyperHdrDiscovery"
        private const val SERVICE_TYPE_FLATBUF = "_hyperhdr-flatbuf._tcp."
        private const val SERVICE_TYPE_JSON = "_hyperhdr-json._tcp."

        /**
         * Pure-logic merge of flatbuf + json results. Indexed by hostname (the mDNS
         * service-name field). Returns one [DiscoveredHyperHdrService] per unique hostname,
         * with [DiscoveredHyperHdrService.jsonPort] populated when the json result is present.
         */
        fun mergeServices(
            flatbuf: List<RawService>,
            json: List<RawService>,
        ): List<DiscoveredHyperHdrService> {
            val flatbufByHost = flatbuf.associateBy { it.hostname }
            val jsonByHost = json.associateBy { it.hostname }
            return flatbufByHost.values.distinctBy { it.hostname }.map { fb ->
                DiscoveredHyperHdrService(
                    hostname = fb.hostname,
                    ipAddress = fb.ipAddress,
                    flatbufPort = fb.port,
                    jsonPort = jsonByHost[fb.hostname]?.port,
                )
            }
        }
    }
}
