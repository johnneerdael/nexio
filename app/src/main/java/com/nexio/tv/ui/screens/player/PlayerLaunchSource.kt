package com.nexio.tv.ui.screens.player

enum class PlayerLaunchSource(val routeValue: String) {
    STREAM("stream"),
    LIBRARY_DIRECT("library_direct"),
    TORBOX("torbox"),
    OTHER("other");

    companion object {
        fun from(value: String?): PlayerLaunchSource {
            return entries.firstOrNull { it.routeValue.equals(value, ignoreCase = true) } ?: OTHER
        }
    }
}
