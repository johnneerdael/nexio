package com.nexio.tv.ui.components

import android.graphics.Typeface
import android.util.TypedValue
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import com.nexio.tv.core.player.BurnInProtectionState
import com.nexio.tv.core.player.SUBTITLE_MAX_ALPHA
import com.nexio.tv.core.player.SUBTITLE_OFF_WHITE_ARGB
import com.nexio.tv.data.local.SubtitleStyleSettings

/**
 * Apply the user's subtitle style to a Media3 SubtitleView. Shared between
 * the stream player (PlayerScreen) and the trailer player (TrailerPlayer)
 * so captions render identically across both surfaces (transparent
 * background, outlined edge, configured font/size/color).
 *
 * Trailer callers pass [BurnInProtectionState.DISABLED]: trailers are
 * short (~30s–2min) so burn-in mitigation is irrelevant.
 */
internal fun applySubtitleViewStyle(
    subtitleView: SubtitleView,
    subtitleStyle: SubtitleStyleSettings,
    burnInProtection: BurnInProtectionState,
) {
    val baseFontSize = 24f
    val scaledFontSize = baseFontSize * (subtitleStyle.size / 100f)
    subtitleView.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, scaledFontSize)
    subtitleView.setApplyEmbeddedFontSizes(false)

    val typeface = if (subtitleStyle.bold) {
        Typeface.DEFAULT_BOLD
    } else {
        Typeface.DEFAULT
    }
    val edgeType = if (subtitleStyle.outlineEnabled) {
        CaptionStyleCompat.EDGE_TYPE_OUTLINE
    } else {
        CaptionStyleCompat.EDGE_TYPE_NONE
    }

    val foregroundColor = if (burnInProtection.enabled) {
        SUBTITLE_OFF_WHITE_ARGB
    } else {
        android.graphics.Color.WHITE
    }

    subtitleView.setStyle(
        CaptionStyleCompat(
            foregroundColor,
            subtitleStyle.backgroundColor,
            android.graphics.Color.TRANSPARENT,
            edgeType,
            subtitleStyle.outlineColor,
            typeface
        )
    )
    subtitleView.setApplyEmbeddedStyles(false)
    subtitleView.alpha = if (burnInProtection.enabled) SUBTITLE_MAX_ALPHA else 1.0f
    subtitleView.translationX = burnInProtection.horizontalOffsetPx

    val effectivePercent = subtitleStyle.verticalOffset + burnInProtection.verticalDeltaPercent
    val bottomPaddingFraction = (0.06f + (effectivePercent / 250f)).coerceIn(0f, 0.4f)
    subtitleView.setBottomPaddingFraction(bottomPaddingFraction)
    subtitleView.post {
        val extraPadding = (subtitleView.height * (effectivePercent / 400f))
            .toInt()
            .coerceAtLeast(0)
        subtitleView.setPadding(
            subtitleView.paddingLeft,
            subtitleView.paddingTop,
            subtitleView.paddingRight,
            extraPadding
        )
    }
}
