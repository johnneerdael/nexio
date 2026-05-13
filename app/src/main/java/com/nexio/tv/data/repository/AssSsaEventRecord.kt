package com.nexio.tv.data.repository

import java.util.Locale
import kotlin.math.floor

internal data class AssSsaEventFormat(
    val fields: List<String>,
    val textIndex: Int,
    val startIndex: Int,
    val endIndex: Int
) {
    companion object {
        fun parse(line: String): AssSsaEventFormat? {
            if (!line.trimStart().startsWith("Format:", ignoreCase = true)) return null
            val fields = line.substringAfter(':').split(',').map { it.trim() }
            return AssSsaEventFormat(
                fields = fields,
                textIndex = fields.indexOfField("Text"),
                startIndex = fields.indexOfField("Start"),
                endIndex = fields.indexOfField("End")
            )
        }

        fun matroskaAss(): AssSsaEventFormat {
            val fields = listOf(
                "ReadOrder",
                "Layer",
                "Style",
                "Name",
                "MarginL",
                "MarginR",
                "MarginV",
                "Effect",
                "Text"
            )
            return AssSsaEventFormat(
                fields = fields,
                textIndex = fields.indexOfField("Text"),
                startIndex = -1,
                endIndex = -1
            )
        }

        fun prefixedMatroskaAss(): AssSsaEventFormat {
            val fields = listOf(
                "Start",
                "End",
                "ReadOrder",
                "Layer",
                "Style",
                "Name",
                "MarginL",
                "MarginR",
                "MarginV",
                "Effect",
                "Text"
            )
            return AssSsaEventFormat(
                fields = fields,
                textIndex = fields.indexOfField("Text"),
                startIndex = fields.indexOfField("Start"),
                endIndex = fields.indexOfField("End")
            )
        }

        fun standardDialogue(): AssSsaEventFormat {
            return checkNotNull(
                parse("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text")
            )
        }
    }
}

internal data class AssSsaEventRecord(
    val kind: String,
    val prefix: String,
    val format: AssSsaEventFormat,
    val values: List<String>
) {
    val text: String
        get() = values[format.textIndex]

    fun field(name: String): String? {
        val index = format.fields.indexOfField(name)
        return values.getOrNull(index)
    }

    fun withText(text: String): AssSsaEventRecord {
        val next = values.toMutableList()
        next[format.textIndex] = text
        return copy(values = next)
    }

    fun render(): String {
        return prefix + values.joinToString(",")
    }

    companion object {
        fun parseDialogueLine(line: String, format: AssSsaEventFormat): AssSsaEventRecord? {
            if (format.textIndex < 0) return null
            val colon = line.indexOf(':')
            if (colon < 0) return null
            val kind = line.take(colon).trim()
            if (!kind.equals("Dialogue", ignoreCase = true) &&
                !kind.equals("Comment", ignoreCase = true)
            ) {
                return null
            }
            var payloadStart = colon + 1
            while (payloadStart < line.length && line[payloadStart].isWhitespace()) {
                payloadStart += 1
            }
            val prefix = line.take(payloadStart)
            val values = line.drop(payloadStart).split(',', limit = format.fields.size)
            if (values.size <= format.textIndex) return null
            return AssSsaEventRecord(kind = kind, prefix = prefix, format = format, values = values)
        }

        fun parseMatroskaSample(
            sampleText: String,
            format: AssSsaEventFormat,
            timeUs: Long,
            durationUs: Long
        ): AssSsaEventRecord? {
            if (format.textIndex < 0) return null
            val values = sampleText.split(',', limit = format.fields.size)
            if (values.size <= format.textIndex) return null
            val renderedValues = mutableListOf<String>()
            renderedValues += values.getOrNull(format.fields.indexOfField("Layer"))?.trim().orEmpty()
            renderedValues += formatAssTimeUs(timeUs)
            renderedValues += formatAssTimeUs(timeUs + durationUs)
            renderedValues += values.getOrNull(format.fields.indexOfField("Style")).orEmpty()
            renderedValues += values.getOrNull(format.fields.indexOfField("Name")).orEmpty()
            renderedValues += values.getOrNull(format.fields.indexOfField("MarginL")).orEmpty()
            renderedValues += values.getOrNull(format.fields.indexOfField("MarginR")).orEmpty()
            renderedValues += values.getOrNull(format.fields.indexOfField("MarginV")).orEmpty()
            renderedValues += values.getOrNull(format.fields.indexOfField("Effect")).orEmpty()
            renderedValues += values[format.textIndex]
            return AssSsaEventRecord(
                kind = "Dialogue",
                prefix = "Dialogue: ",
                format = AssSsaEventFormat.standardDialogue(),
                values = renderedValues
            )
        }
    }
}

private fun List<String>.indexOfField(name: String): Int {
    return indexOfFirst { it.trim().equals(name, ignoreCase = true) }
}

internal fun formatAssTimeUs(timeUs: Long): String {
    val totalCentiseconds = floor(timeUs / 10_000.0).toLong().coerceAtLeast(0L)
    val centiseconds = totalCentiseconds % 100
    val totalSeconds = totalCentiseconds / 100
    val seconds = totalSeconds % 60
    val totalMinutes = totalSeconds / 60
    val minutes = totalMinutes % 60
    val hours = totalMinutes / 60
    return String.format(
        Locale.US,
        "%d:%02d:%02d.%02d",
        hours,
        minutes,
        seconds,
        centiseconds
    )
}
