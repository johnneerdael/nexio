package com.nexio.tv.data.trailer.captions

internal object SrtSerializer {

    fun serialize(lines: List<CaptionLine>): String {
        if (lines.isEmpty()) return ""
        val sb = StringBuilder()
        for (i in lines.indices) {
            val line = lines[i]
            sb.append(i + 1).append('\n')
            sb.append(formatTimestamp(line.offsetMs))
                .append(" --> ")
                .append(formatTimestamp(line.offsetMs + line.durationMs))
                .append('\n')
            // Replace literal arrow sequences in the text with en-dashes
            // to avoid confusing SRT parsers; YoutubeExplode does the same
            // (ClosedCaptionClient.cs:170) — SRT has no escape mechanism.
            sb.append(line.text.replace("-->", "––>")).append('\n')
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun formatTimestamp(totalMs: Long): String {
        val hours = totalMs / 3_600_000
        val minutes = (totalMs / 60_000) % 60
        val seconds = (totalMs / 1_000) % 60
        val ms = totalMs % 1_000
        return "%02d:%02d:%02d,%03d".format(hours, minutes, seconds, ms)
    }
}
