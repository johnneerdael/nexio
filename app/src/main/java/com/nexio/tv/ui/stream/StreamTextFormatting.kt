package com.nexio.tv.ui.stream

private val STREAM_NEWLINE_REGEX = Regex("""\s*[\r\n]+\s*""")
private val STREAM_WHITESPACE_REGEX = Regex("""\s+""")

internal fun inlineStreamText(value: String?): String? {
    return value
        ?.replace(STREAM_NEWLINE_REGEX, " • ")
        ?.replace(STREAM_WHITESPACE_REGEX, " ")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
}
