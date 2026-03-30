package com.nexio.tv.ui.components

import com.nexio.tv.core.stream.ParsedStreamInfo
import com.nexio.tv.core.stream.StreamTransportKind
import com.nexio.tv.domain.model.Stream

internal enum class StreamBadgeKind {
    CACHED,
    TORRENT,
    YOUTUBE,
    EXTERNAL
}

internal fun streamBadgeKinds(
    stream: Stream,
    parsed: ParsedStreamInfo
): List<StreamBadgeKind> {
    return buildList {
        if (parsed.isCached == true) {
            add(StreamBadgeKind.CACHED)
        }

        when (parsed.transportKind) {
            StreamTransportKind.P2P -> if (parsed.isCached != true) add(StreamBadgeKind.TORRENT)
            StreamTransportKind.YOUTUBE -> add(StreamBadgeKind.YOUTUBE)
            StreamTransportKind.EXTERNAL -> add(StreamBadgeKind.EXTERNAL)
            else -> Unit
        }

        if (parsed.transportKind == StreamTransportKind.OTHER) {
            if (stream.isYouTube()) add(StreamBadgeKind.YOUTUBE)
            if (stream.isExternal()) add(StreamBadgeKind.EXTERNAL)
            if (stream.isTorrent() && parsed.isCached != true) add(StreamBadgeKind.TORRENT)
        }
    }
}
