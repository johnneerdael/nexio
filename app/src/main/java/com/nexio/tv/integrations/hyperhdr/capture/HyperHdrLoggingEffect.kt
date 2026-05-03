package com.nexio.tv.integrations.hyperhdr.capture

import android.content.Context
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * [GlEffect] wrapper that creates a [HyperHdrLoggingShaderProgram] for diagnostic logging of
 * the Media3 effect pipeline's GL texture properties for HDR sources. Throwaway — removed in
 * Task 8 once findings are recorded.
 */
@UnstableApi
class HyperHdrLoggingEffect : GlEffect {
    @Throws(VideoFrameProcessingException::class)
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        HyperHdrLoggingShaderProgram(context, useHdr)
}
