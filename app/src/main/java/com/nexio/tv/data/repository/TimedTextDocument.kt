package com.nexio.tv.data.repository

internal enum class TimedTextFormat(val extension: String) {
    SRT("srt"),
    VTT("vtt"),
    ASS("ass"),
    SSA("ssa")
}

internal sealed class TimedTextBlock {
    abstract fun render(translations: Map<Int, String>): String
}

internal data class PassthroughTimedTextBlock(
    private val lines: List<String>
) : TimedTextBlock() {
    override fun render(translations: Map<Int, String>): String = lines.joinToString("\n")
}

internal data class TranslatableTimedTextBlock(
    val blockId: Int,
    val prefixLines: List<String>,
    val text: String
) : TimedTextBlock() {
    override fun render(translations: Map<Int, String>): String {
        val translatedText = translations[blockId]?.trim().takeUnless { it.isNullOrBlank() } ?: text
        return (prefixLines + translatedText.split('\n')).joinToString("\n")
    }
}

internal data class TimedTextDocument(
    val format: TimedTextFormat,
    val blocks: List<TimedTextBlock>
) {
    val extension: String
        get() = format.extension

    val translatableBlocks: List<TranslatableTimedTextBlock>
        get() = blocks.flatMap { block ->
            when (block) {
                is TranslatableTimedTextBlock -> listOf(block)
                is AssSsaDialogueBlock -> block.translatableBlocks()
                else -> emptyList()
            }
        }

    fun render(translations: Map<Int, String>): String {
        val separator = when (format) {
            TimedTextFormat.ASS,
            TimedTextFormat.SSA -> "\n"
            TimedTextFormat.SRT,
            TimedTextFormat.VTT -> "\n\n"
        }
        return blocks.joinToString(separator) { it.render(translations) }.trim() + "\n"
    }

    fun assSsaSegmentSurfaces(): List<AssSsaTranslationSurface> {
        if (format != TimedTextFormat.ASS && format != TimedTextFormat.SSA) return emptyList()
        val dialogueBlocks = blocks.filterIsInstance<AssSsaDialogueBlock>()
        val plan = AssSsaTranslationPlanner.plan(dialogueBlocks.map { it.record })
        return dialogueBlocks.mapIndexedNotNull { index, block ->
            when (plan.actions[index]) {
                is AssSsaTranslationAction.Translate -> block.segmentSurface("ass_$index")
                is AssSsaTranslationAction.DuplicateOf -> null
                is AssSsaTranslationAction.Preserve -> null
            }
        }
    }

    fun renderAssSsaSegmentSurfaces(translations: Map<String, List<String>>): String {
        if (format != TimedTextFormat.ASS && format != TimedTextFormat.SSA) {
            return render(emptyMap())
        }
        val dialogueBlocks = blocks.filterIsInstance<AssSsaDialogueBlock>()
        val plan = AssSsaTranslationPlanner.plan(dialogueBlocks.map { it.record })
        var dialogueIndex = 0
        return blocks.joinToString("\n") { block ->
            if (block is AssSsaDialogueBlock) {
                val index = dialogueIndex++
                val canonicalIndex = when (val action = plan.actions[index]) {
                    is AssSsaTranslationAction.Translate -> action.canonicalIndex
                    is AssSsaTranslationAction.DuplicateOf -> action.canonicalIndex
                    is AssSsaTranslationAction.Preserve -> null
                }
                val id = canonicalIndex?.let { "ass_$it" }
                val surface = id?.let { block.segmentSurface(it) }
                block.renderSegmentSurface(surface, id?.let { translations[it] })
            } else {
                block.render(emptyMap())
            }
        }.trim() + "\n"
    }

    companion object {
        fun parse(raw: String, url: String): TimedTextDocument? {
            val normalized = raw
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim()
            if (normalized.isBlank()) return null

            val path = url.substringBefore('?').substringBefore('#').lowercase()
            return when {
                path.endsWith(".ass") -> parseAssSsaTimedTextDocument(normalized, TimedTextFormat.ASS)
                path.endsWith(".ssa") -> parseAssSsaTimedTextDocument(normalized, TimedTextFormat.SSA)
                normalized.startsWith("WEBVTT", ignoreCase = true) || path.endsWith(".vtt") -> parseVtt(normalized)
                path.endsWith(".srt") -> parseSrt(normalized)
                else -> {
                    val looksLikeSrt = normalized.lineSequence().any { it.contains("-->") }
                    if (looksLikeSrt) parseSrt(normalized) else null
                }
            }
        }

        private fun parseSrt(raw: String): TimedTextDocument {
            val blocks = raw.split(Regex("\n{2,}"))
            val parsed = mutableListOf<TimedTextBlock>()
            var nextBlockId = 0
            for (block in blocks) {
                val lines = block.lines().filterNot { it.isEmpty() }
                val timestampIndex = lines.indexOfFirst { it.contains("-->") }
                if (timestampIndex >= 0 && timestampIndex < lines.lastIndex) {
                    parsed += TranslatableTimedTextBlock(
                        blockId = nextBlockId++,
                        prefixLines = lines.take(timestampIndex + 1),
                        text = lines.drop(timestampIndex + 1).joinToString("\n")
                    )
                } else {
                    parsed += PassthroughTimedTextBlock(lines)
                }
            }
            return TimedTextDocument(TimedTextFormat.SRT, parsed)
        }

        private fun parseVtt(raw: String): TimedTextDocument {
            val blocks = raw.split(Regex("\n{2,}"))
            val parsed = mutableListOf<TimedTextBlock>()
            var nextBlockId = 0
            for (block in blocks) {
                val lines = block.lines().filterNot { it.isEmpty() }
                val timestampIndex = lines.indexOfFirst { it.contains("-->") }
                if (timestampIndex >= 0 && timestampIndex < lines.lastIndex) {
                    parsed += TranslatableTimedTextBlock(
                        blockId = nextBlockId++,
                        prefixLines = lines.take(timestampIndex + 1),
                        text = lines.drop(timestampIndex + 1).joinToString("\n")
                    )
                } else {
                    parsed += PassthroughTimedTextBlock(lines)
                }
            }
            return TimedTextDocument(TimedTextFormat.VTT, parsed)
        }
    }
}
