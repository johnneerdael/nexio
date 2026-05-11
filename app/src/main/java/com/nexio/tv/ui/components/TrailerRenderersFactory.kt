package com.nexio.tv.ui.components

import android.content.Context
import android.os.Looper
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.text.CueGroupSubtitleTranslator
import androidx.media3.exoplayer.text.SubtitleDecoderFactory
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer

/**
 * Minimal RenderersFactory for the trailer player. Its only override is
 * `buildTextRenderers` so the Media3 TextRenderer is constructed with a
 * [CueGroupSubtitleTranslator] plugin — that's the seam ExoPlayer uses
 * to call back into our `BuiltInSubtitleCueTranslator` for on-demand
 * cue translation.
 *
 * The stream player uses a much larger [SubtitleOffsetRenderersFactory]
 * with offset shifting, HDR sinks, ASS/SSA renderers, FireOS passthrough,
 * etc. Trailers don't need any of that — only the cue translator hook.
 */
internal class TrailerRenderersFactory(
    context: Context,
    private val cueGroupSubtitleTranslator: CueGroupSubtitleTranslator?
) : DefaultRenderersFactory(context) {

    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>
    ) {
        out.add(
            TextRenderer(
                output,
                outputLooper,
                SubtitleDecoderFactory.DEFAULT,
                cueGroupSubtitleTranslator
            )
        )
    }
}
