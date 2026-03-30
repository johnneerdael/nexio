package com.nexio.tv.data.repository.servicewrap

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
        val infoHash = extractInfoHash(stream) ?: return null
        val parsed = parseSourceStream(stream)
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
