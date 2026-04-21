package com.nexio.tv.core.integration

data class IntegrationProviderPolicy(
    val maxConcurrentNetworkStarts: Int = 1,
    val allowDuringPlayback: Boolean = false,
    val allowStaleWhilePaused: Boolean = true,
    val defaultBackoffOn429Ms: Long = 2_000L,
    val defaultBackoffOnTransientMs: Long = 5_000L
)
