package com.nexio.tv.ui.components

import androidx.annotation.DrawableRes
import com.nexio.tv.R
import java.util.Locale

enum class ScaleClass {
    INLINE,
    TITLE_PROMINENT
}

data class InlineIconToken(
    val id: String,
    @DrawableRes val drawableRes: Int,
    val fallbackLabel: String,
    val scaleClass: ScaleClass,
    val aspectRatio: Float = 1f
)

sealed interface InlineIconSegment {
    data class TextSegment(val text: String) : InlineIconSegment
    data class IconSegment(
        val token: InlineIconToken,
        val scaleOverride: Float? = null
    ) : InlineIconSegment
}

object InlineIconTokenRegistry {
    private val tokenPattern = Regex("""\[\[icon:([a-z0-9_]+)(?::([0-9]+(?:\.[0-9]+)?))?\]\]""", RegexOption.IGNORE_CASE)

    private val tokens = listOf(
        InlineIconToken("netflix", R.drawable.formatter_icon_netflix, "Netflix", ScaleClass.INLINE),
        InlineIconToken("disneyplus", R.drawable.formatter_icon_disneyplus, "Disney+", ScaleClass.INLINE),
        InlineIconToken("hbo", R.drawable.formatter_icon_hbo, "HBO Max", ScaleClass.INLINE),
        InlineIconToken("max", R.drawable.formatter_icon_max, "Max", ScaleClass.INLINE),
        InlineIconToken("prime", R.drawable.formatter_icon_prime, "Amazon", ScaleClass.INLINE),
        InlineIconToken("appletv", R.drawable.formatter_icon_appletv, "Apple TV+", ScaleClass.INLINE),
        InlineIconToken("paramount", R.drawable.formatter_icon_paramount, "Paramount+", ScaleClass.INLINE),
        InlineIconToken("peacock", R.drawable.formatter_icon_peacock, "Peacock", ScaleClass.INLINE),
        InlineIconToken("crunchyroll", R.drawable.formatter_icon_crunchyroll, "Crunchyroll", ScaleClass.INLINE),
        InlineIconToken("alldebrid", R.drawable.formatter_icon_alldebrid, "AllDebrid", ScaleClass.INLINE),
        InlineIconToken("debridlink", R.drawable.formatter_icon_debridlink, "Debrid-Link", ScaleClass.INLINE),
        InlineIconToken("torbox", R.drawable.formatter_icon_torbox, "TorBox", ScaleClass.INLINE),
        InlineIconToken("offcloud", R.drawable.formatter_icon_offcloud, "Offcloud", ScaleClass.INLINE),
        InlineIconToken("putio", R.drawable.formatter_icon_putio, "put.io", ScaleClass.INLINE),
        InlineIconToken("easydebrid", R.drawable.formatter_icon_easydebrid, "EasyDebrid", ScaleClass.INLINE),
        InlineIconToken("debrider", R.drawable.formatter_icon_debrider, "Debrider", ScaleClass.INLINE),
        InlineIconToken("pikpak", R.drawable.formatter_icon_pikpak, "PikPak", ScaleClass.INLINE),
        InlineIconToken("seedr", R.drawable.formatter_icon_seedr, "Seedr", ScaleClass.INLINE),
        InlineIconToken("easynews", R.drawable.formatter_icon_easynews, "Easynews", ScaleClass.INLINE),
        InlineIconToken("nzbdav", R.drawable.formatter_icon_nzbdav, "NzbDAV", ScaleClass.INLINE),
        InlineIconToken("altmount", R.drawable.formatter_icon_altmount, "AltMount", ScaleClass.INLINE),
        InlineIconToken("stremionntp", R.drawable.formatter_icon_stremionntp, "Stremio NNTP", ScaleClass.INLINE),
        InlineIconToken("stremthrunewz", R.drawable.formatter_icon_stremthrunewz, "StremThru Newz", ScaleClass.INLINE),
        InlineIconToken("premiumize", R.drawable.formatter_icon_premiumize, "Premiumize", ScaleClass.INLINE),
        InlineIconToken("realdebrid", R.drawable.formatter_icon_realdebrid, "Real-Debrid", ScaleClass.INLINE),
        InlineIconToken("atmos", R.drawable.formatter_icon_atmos, "Dolby Atmos", ScaleClass.INLINE, aspectRatio = 200f / 75f),
        InlineIconToken("truehd", R.drawable.formatter_icon_truehd, "Dolby TrueHD", ScaleClass.INLINE, aspectRatio = 200f / 49f),
        InlineIconToken("ddp", R.drawable.formatter_icon_ddp, "Dolby Digital+", ScaleClass.INLINE, aspectRatio = 200f / 47f),
        InlineIconToken("dd", R.drawable.formatter_icon_dd, "Dolby Digital", ScaleClass.INLINE, aspectRatio = 200f / 60f),
        InlineIconToken("dts", R.drawable.formatter_icon_dts, "DTS", ScaleClass.INLINE, aspectRatio = 200f / 83f),
        InlineIconToken("dtshd", R.drawable.formatter_icon_dtshd, "DTS-MA", ScaleClass.INLINE, aspectRatio = 200f / 69f),
        InlineIconToken("dtsx", R.drawable.formatter_icon_dtsx, "DTS:X", ScaleClass.INLINE, aspectRatio = 200f / 71f),
        InlineIconToken("stereo", R.drawable.formatter_icon_stereo, "Stereo", ScaleClass.INLINE, aspectRatio = 200f / 203f),
        InlineIconToken("dovi", R.drawable.formatter_icon_dovi, "Dolby Vision", ScaleClass.INLINE, aspectRatio = 200f / 74f),
        InlineIconToken("hdr10", R.drawable.formatter_icon_hdr10, "HDR10", ScaleClass.INLINE, aspectRatio = 200f / 43f),
        InlineIconToken("hdr10plus", R.drawable.formatter_icon_hdr10plus, "HDR10+", ScaleClass.INLINE, aspectRatio = 600f / 153f),
        InlineIconToken("hlg", R.drawable.formatter_icon_hlg, "HLG", ScaleClass.INLINE, aspectRatio = 600f / 253f),
        InlineIconToken("sdr", R.drawable.formatter_icon_sdr, "SDR", ScaleClass.INLINE, aspectRatio = 600f / 253f),
        InlineIconToken("ai", R.drawable.formatter_icon_ai, "AI", ScaleClass.INLINE, aspectRatio = 399f / 281f),
        InlineIconToken("4k", R.drawable.formatter_icon_4k, "4K", ScaleClass.TITLE_PROMINENT, aspectRatio = 109f / 72f),
        InlineIconToken("2k", R.drawable.formatter_icon_2k, "2K", ScaleClass.TITLE_PROMINENT, aspectRatio = 104f / 72f),
        InlineIconToken("fullhd", R.drawable.formatter_icon_fullhd, "Full HD", ScaleClass.TITLE_PROMINENT, aspectRatio = 93f / 72f),
        InlineIconToken("hd", R.drawable.formatter_icon_hd, "HD", ScaleClass.TITLE_PROMINENT, aspectRatio = 93f / 72f),
        InlineIconToken("sd", R.drawable.formatter_icon_sd, "SD", ScaleClass.TITLE_PROMINENT, aspectRatio = 90f / 72f)
    ).associateBy { it.id }

    fun resolve(id: String): InlineIconToken? = tokens[id.trim().lowercase(Locale.US)]

    fun tokenize(text: String): List<InlineIconSegment> {
        if (text.isEmpty()) return emptyList()

        val segments = mutableListOf<InlineIconSegment>()
        var cursor = 0

        tokenPattern.findAll(text).forEach { match ->
            val range = match.range
            if (range.first > cursor) {
                segments.appendText(text.substring(cursor, range.first))
            }

            val rawTokenId = match.groupValues[1]
            val rawScaleOverride = match.groups[2]?.value
            val token = resolve(rawTokenId)
            if (token != null) {
                segments += InlineIconSegment.IconSegment(
                    token = token,
                    scaleOverride = rawScaleOverride?.toFloatOrNull()
                )
            } else {
                segments.appendText(rawTokenId)
            }

            cursor = range.last + 1
        }

        if (cursor < text.length) {
            segments.appendText(text.substring(cursor))
        }

        return segments
    }

    private fun MutableList<InlineIconSegment>.appendText(value: String) {
        if (value.isEmpty()) return

        val previous = lastOrNull()
        if (previous is InlineIconSegment.TextSegment) {
            this[lastIndex] = previous.copy(text = previous.text + value)
        } else {
            add(InlineIconSegment.TextSegment(value))
        }
    }
}
