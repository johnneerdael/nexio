package com.nexio.tv.ui.screens.player.ass

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.NoSampleRenderer

@OptIn(UnstableApi::class)
internal class AssSsaTimeRenderer(
    private val controller: AssSsaRenderController
) : NoSampleRenderer() {
    override fun getName(): String = "AssSsaTimeRenderer"

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        controller.currentTimeUs = positionUs
        controller.renderCurrentFrame()
    }

    override fun isReady(): Boolean = true

    override fun isEnded(): Boolean = true
}
