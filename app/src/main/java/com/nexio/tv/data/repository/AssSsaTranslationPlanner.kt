package com.nexio.tv.data.repository

import java.util.Locale

internal enum class AssSsaPreserveReason {
    Drawing,
    EmptyVisibleText,
    NonReadableEffect,
    WindowBudget
}

internal sealed interface AssSsaTranslationAction {
    data class Translate(val canonicalIndex: Int) : AssSsaTranslationAction
    data class DuplicateOf(val canonicalIndex: Int) : AssSsaTranslationAction
    data class Preserve(val reason: AssSsaPreserveReason) : AssSsaTranslationAction
}

internal data class AssSsaTranslationPlan(
    val actions: List<AssSsaTranslationAction>,
    val visibleTexts: List<String>
)

internal data class AssSsaTranslationPlanConfig(
    val liveWindowMs: Long? = null,
    val maxTranslatePerWindow: Int = Int.MAX_VALUE,
    val budgetPreferPreserveStyles: Set<String> = emptySet()
)

internal object AssSsaTranslationPlanner {
    fun plan(
        records: List<AssSsaEventRecord>,
        config: AssSsaTranslationPlanConfig = AssSsaTranslationPlanConfig()
    ): AssSsaTranslationPlan {
        val visibleTexts = records.map { record -> record.text.assSsaVisibleText().trim() }
        val actions = MutableList<AssSsaTranslationAction>(records.size) {
            AssSsaTranslationAction.Preserve(AssSsaPreserveReason.EmptyVisibleText)
        }
        val canonicalByVisible = linkedMapOf<String, Int>()

        for (i in records.indices) {
            val record = records[i]
            val visible = visibleTexts[i]
            actions[i] = classifyInitial(record, visible)?.let { reason ->
                AssSsaTranslationAction.Preserve(reason)
            } ?: run {
                val key = visible.normalizedAssSsaVisibleText()
                val canonical = canonicalByVisible[key]
                if (canonical != null) {
                    AssSsaTranslationAction.DuplicateOf(canonical)
                } else {
                    canonicalByVisible[key] = i
                    AssSsaTranslationAction.Translate(i)
                }
            }
        }

        applyWindowBudget(records, actions, config)
        return AssSsaTranslationPlan(actions = actions, visibleTexts = visibleTexts)
    }

    private fun classifyInitial(record: AssSsaEventRecord, visible: String): AssSsaPreserveReason? {
        if (visible.isBlank()) return AssSsaPreserveReason.EmptyVisibleText
        if (record.text.hasAssSsaDrawingPayload()) return AssSsaPreserveReason.Drawing

        val effect = record.field("Effect").orEmpty().trim()
        if (effect.equals("fx", ignoreCase = true) && !record.isReadableEnglishEffectLine(visible)) {
            return AssSsaPreserveReason.NonReadableEffect
        }
        return null
    }

    private fun AssSsaEventRecord.isReadableEnglishEffectLine(visible: String): Boolean {
        val style = field("Style").orEmpty().trim().lowercase(Locale.US)
        val englishStyle = style.contains("english") || style.endsWith("eng") || "-eng" in style
        if (!englishStyle) return false
        if (visible.length <= 3) return false
        val wordCount = visible.split(Regex("""\s+""")).count { it.isNotBlank() }
        return wordCount >= 2 || visible.any { it in ".!?," }
    }

    private fun applyWindowBudget(
        records: List<AssSsaEventRecord>,
        actions: MutableList<AssSsaTranslationAction>,
        config: AssSsaTranslationPlanConfig
    ) {
        val windowMs = config.liveWindowMs ?: return
        if (config.maxTranslatePerWindow == Int.MAX_VALUE) return

        val windows = linkedMapOf<Long, MutableList<Int>>()
        for (i in records.indices) {
            if (actions[i] is AssSsaTranslationAction.Translate) {
                val startMs = records[i].field("Start").orEmpty().parseAssSsaTimeMsOrNull() ?: continue
                windows.getOrPut(startMs / windowMs) { mutableListOf() } += i
            }
        }

        windows.values.forEach { indices ->
            if (indices.size <= config.maxTranslatePerWindow) return@forEach
            val sorted = indices.sortedWith(
                compareBy<Int> { index ->
                    val style = records[index].field("Style").orEmpty().trim().lowercase(Locale.US)
                    if (style in config.budgetPreferPreserveStyles) 1 else 0
                }.thenBy { index -> index }
            )
            sorted.drop(config.maxTranslatePerWindow).forEach { index ->
                actions[index] = AssSsaTranslationAction.Preserve(AssSsaPreserveReason.WindowBudget)
            }
        }
    }
}

internal fun String.assSsaVisibleText(): String {
    val out = StringBuilder()
    var drawingMode = 0
    var index = 0
    while (index < length) {
        val blockStart = indexOf('{', startIndex = index)
        if (blockStart < 0) {
            if (drawingMode == 0) out.append(substring(index))
            break
        }
        if (blockStart > index && drawingMode == 0) {
            out.append(substring(index, blockStart))
        }
        val blockEnd = indexOf('}', startIndex = blockStart + 1)
        if (blockEnd < 0) {
            if (drawingMode == 0) out.append(substring(blockStart))
            break
        }
        drawingMode = drawingModeAfterAssSsaOverrideBlock(substring(blockStart, blockEnd + 1), drawingMode)
        index = blockEnd + 1
    }
    return out.toString()
        .replace(Regex("""\\[Nn]"""), " ")
        .replace("""\h""", " ")
}

internal fun String.hasAssSsaDrawingPayload(): Boolean {
    var drawingMode = 0
    var index = 0
    while (index < length) {
        val blockStart = indexOf('{', startIndex = index)
        if (blockStart < 0) return false
        val blockEnd = indexOf('}', startIndex = blockStart + 1)
        if (blockEnd < 0) return false
        drawingMode = drawingModeAfterAssSsaOverrideBlock(substring(blockStart, blockEnd + 1), drawingMode)
        if (drawingMode > 0) return true
        index = blockEnd + 1
    }
    return false
}

private fun String.normalizedAssSsaVisibleText(): String {
    return lowercase(Locale.US)
        .replace(Regex("""\s+"""), " ")
        .trim()
}

private fun String.parseAssSsaTimeMsOrNull(): Long? {
    val parts = trim().split(":")
    if (parts.size != 3 && parts.size != 4) return null
    val hours = parts[0].toLongOrNull() ?: return null
    val minutes = parts[1].toLongOrNull() ?: return null
    val seconds: Long
    val millis: Long
    if (parts.size == 4) {
        seconds = parts[2].toLongOrNull() ?: return null
        millis = (parts[3].toLongOrNull() ?: return null) * 10L
    } else {
        val secondParts = parts[2].split(".", limit = 2)
        seconds = secondParts[0].toLongOrNull() ?: return null
        millis = secondParts.getOrNull(1)
            ?.padEnd(3, '0')
            ?.take(3)
            ?.toLongOrNull()
            ?: 0L
    }
    return hours * 3_600_000L + minutes * 60_000L + seconds * 1000L + millis
}

private fun drawingModeAfterAssSsaOverrideBlock(block: String, current: Int): Int {
    var drawingMode = current
    val inner = block.removePrefix("{").removeSuffix("}")
    var index = 0
    while (index < inner.length) {
        val slash = inner.indexOf('\\', startIndex = index)
        if (slash < 0 || slash == inner.lastIndex) break
        val nameStart = slash + 1
        if (inner[nameStart] == 'p') {
            val argStart = nameStart + 1
            if (argStart >= inner.length || !inner[argStart].isLetter()) {
                var argEnd = argStart
                while (argEnd < inner.length && inner[argEnd] != '\\') {
                    argEnd += 1
                }
                inner.substring(argStart, argEnd).trim().toIntOrNull()?.let { drawingMode = it }
                index = argEnd
                continue
            }
        }
        var nameEnd = nameStart
        while (nameEnd < inner.length && inner[nameEnd].isLetterOrDigit()) {
            nameEnd += 1
        }
        index = nameEnd
    }
    return drawingMode
}
