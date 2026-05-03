package com.nexio.tv.core.player.auth

object TransientFailureCodes {
    const val BAD_GATEWAY = 502
    const val SERVICE_UNAVAILABLE = 503
    const val GATEWAY_TIMEOUT = 504

    val ALL: Set<Int> = setOf(BAD_GATEWAY, SERVICE_UNAVAILABLE, GATEWAY_TIMEOUT)

    fun matches(status: Int): Boolean = status in ALL
}
