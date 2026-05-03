package com.nexio.tv.integrations.hyperhdr.capture

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.nexio.tv.integrations.hyperhdr.network.FrameSink

@UnstableApi
class HyperHdrCaptureEffect(private val sink: FrameSink) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        HyperHdrCaptureShaderProgram(context, useHdr, sink)
}
