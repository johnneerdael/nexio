package com.nexio.tv.ui.screens.player.ass

import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput

internal class AssSsaExtractorOutput(
    private val delegate: ExtractorOutput,
    private val sink: AssSsaSampleSink
) : ExtractorOutput {
    override fun track(id: Int, type: Int): TrackOutput {
        return AssSsaTrackOutput(delegate.track(id, type), sink, id)
    }

    override fun endTracks() {
        delegate.endTracks()
    }

    override fun seekMap(seekMap: SeekMap) {
        delegate.seekMap(seekMap)
    }
}
