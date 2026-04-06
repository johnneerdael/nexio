package com.nexio.tv.ui.screens.player

internal data class RuntimeTransportObservation(
    val hostScope: String,
    val transportClass: String,
    val negotiatedProtocol: String? = null,
    val connectionHeader: String? = null
)
