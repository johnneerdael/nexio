package com.nexio.tv.core.network

import java.net.Inet4Address
import java.net.InetAddress
import okhttp3.Dns

/**
 * Prefer IPv4 addresses first to avoid broken IPv6 routes stalling trailer resolution.
 */
class IPv4FirstDns(
    private val delegate: Dns = Dns.SYSTEM
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        return delegate.lookup(hostname).sortedBy { address ->
            if (address is Inet4Address) 0 else 1
        }
    }
}
