package com.nexio.tv.core.server

import android.content.Context
import java.net.Inet4Address
import java.net.NetworkInterface

object DeviceIpAddress {

    fun get(@Suppress("UNUSED_PARAMETER") context: Context): String? {
        // Resolve non-loopback IPv4 address from active network interfaces
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList()
                ?.flatMap { it.inetAddresses.toList() }
                ?.firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                ?.hostAddress
        } catch (e: Exception) {
            null
        }
    }
}

