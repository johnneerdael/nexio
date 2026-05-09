package com.nexio.tv.core.util

/**
 * Allocation-frugal hex encoder.
 *
 * Replaces the `bytes.joinToString("") { "%02x".format(it) }` idiom that
 * appears in cache-key / digest helpers throughout the codebase. Each
 * `String.format("%02x", byte)` allocates a Formatter + FormatSpecifier +
 * FormatSpecifierParser + Flags + FormatString[][] tower (~6 objects per
 * byte). For a 16-byte SHA-256 prefix that is 96 short-lived objects per
 * key — and these helpers are called per-card on Home, multiplying into
 * tens of thousands of Formatters per home pipeline tick (visible in
 * heap dumps as java.util.Formatter retention saturating short-lived
 * GC bands and contributing to GC death-spiral pauses on Modern Home).
 *
 * Encoding via a lookup table allocates exactly one char[] per call (the
 * String backing storage). No StringBuilder, no Formatter, no boxing.
 */
private val HEX_CHARS_LOWER = "0123456789abcdef".toCharArray()

fun ByteArray.toHexLowercase(): String {
    val out = CharArray(size * 2)
    for (i in indices) {
        val b = this[i].toInt() and 0xFF
        out[i * 2] = HEX_CHARS_LOWER[b ushr 4]
        out[i * 2 + 1] = HEX_CHARS_LOWER[b and 0x0F]
    }
    return String(out)
}
