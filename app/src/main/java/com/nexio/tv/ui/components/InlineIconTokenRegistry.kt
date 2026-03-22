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
    val scaleClass: ScaleClass
)

sealed interface InlineIconSegment {
    data class TextSegment(val text: String) : InlineIconSegment
    data class IconSegment(val token: InlineIconToken) : InlineIconSegment
}

object InlineIconTokenRegistry {
    private val tokenPattern = Regex("""\[\[icon:([a-z0-9_]+)\]\]""", RegexOption.IGNORE_CASE)

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
        InlineIconToken("4k", R.drawable.formatter_icon_4k, "4K", ScaleClass.TITLE_PROMINENT),
        InlineIconToken("2k", R.drawable.formatter_icon_2k, "2K", ScaleClass.TITLE_PROMINENT),
        InlineIconToken("fullhd", R.drawable.formatter_icon_fullhd, "Full HD", ScaleClass.TITLE_PROMINENT),
        InlineIconToken("hd", R.drawable.formatter_icon_hd, "HD", ScaleClass.TITLE_PROMINENT),
        InlineIconToken("sd", R.drawable.formatter_icon_sd, "SD", ScaleClass.TITLE_PROMINENT)
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
            val token = resolve(rawTokenId)
            if (token != null) {
                segments += InlineIconSegment.IconSegment(token)
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
