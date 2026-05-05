package com.nexio.animemap.model

object SeasonMarkerWire {
    private val KNOWN = setOf("a", "hentai", "unknown")

    fun normalize(raw: String?): String? {
        val trimmed = raw?.trim()?.lowercase() ?: return null
        if (trimmed.isEmpty()) return null
        if (trimmed in KNOWN) return trimmed
        return trimmed.toIntOrNull()?.toString()
    }
}
