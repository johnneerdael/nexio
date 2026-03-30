package com.nexio.tv.data.repository.servicewrap

import com.nexio.tv.core.stream.StreamTransportKind
import com.nexio.tv.domain.model.Stream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WrapCandidateExtractor @Inject constructor() {

    fun extractCandidate(
        addonName: String,
        addonLogo: String?,
        stream: Stream
    ): WrapCandidate? {
        val parsed = parseSourceStream(stream)
        if (parsed.transportKind != StreamTransportKind.P2P &&
            parsed.transportKind != StreamTransportKind.UNCACHED
        ) {
            return null
        }
        val infoHash = extractInfoHash(stream) ?: return null
        return WrapCandidate(
            normalizedInfoHash = infoHash,
            magnetUri = buildMagnetUri(infoHash, stream, parsed),
            sourceStream = stream,
            sourceAddonName = addonName,
            sourceAddonLogo = addonLogo,
            sourceStreamKey = stableWrapStreamKey(stream),
            sourceParsed = parsed
        )
    }
}
