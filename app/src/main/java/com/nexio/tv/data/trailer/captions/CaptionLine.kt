package com.nexio.tv.data.trailer.captions

internal data class CaptionLine(
    val offsetMs: Long,
    val durationMs: Long,
    val text: String
)
