package com.nexio.tv.core.integration

data class IntegrationFetchOptions(
    val cacheOnly: Boolean = false,
    val allowStaleOnFailure: Boolean = true
)
