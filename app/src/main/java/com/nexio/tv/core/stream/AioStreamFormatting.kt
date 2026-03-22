package com.nexio.tv.core.stream

import com.nexio.tv.domain.model.Stream
import java.util.Base64
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.random.Random

data class AioTemplateDefinition(
    val id: String,
    val nameTemplate: String,
    val descriptionTemplate: String
)

data class AioCustomTemplateSelection(
    val label: String? = null,
    val nameTemplate: String? = null,
    val descriptionTemplate: String? = null
)

data class AioFormatterSelection(
    val selectedTemplateId: String = "universal",
    val customTemplate: AioCustomTemplateSelection? = null
)

data class AioFormattedText(
    val name: String,
    val description: String
)

data class AioUniformPresentation(
    val title: String,
    val detailLines: List<String>
)

data class AioParseValue(
    val config: AioConfigTemplateModel = AioConfigTemplateModel(),
    val stream: AioStreamTemplateModel = AioStreamTemplateModel(),
    val metadata: AioMetadataTemplateModel = AioMetadataTemplateModel(),
    val service: AioServiceTemplateModel = AioServiceTemplateModel(),
    val addon: AioAddonTemplateModel = AioAddonTemplateModel(),
    val debug: AioDebugTemplateModel = AioDebugTemplateModel()
) {
    fun section(sectionName: String): Map<String, Any?>? {
        return when (sectionName) {
            "config" -> config.values()
            "stream" -> stream.values()
            "metadata" -> metadata.values()
            "service" -> service.values()
            "addon" -> addon.values()
            "debug" -> debug.values()
            else -> null
        }
    }
}

data class AioConfigTemplateModel(
    val addonName: String? = null
) {
    fun values(): Map<String, Any?> = linkedMapOf(
        "addonName" to addonName
    )
}

data class AioStreamTemplateModel(
    val filename: String? = null,
    val folderName: String? = null,
    val size: Long? = null,
    val bitrate: Long? = null,
    val folderSize: Long? = null,
    val library: Boolean = false,
    val quality: String? = null,
    val resolution: String? = null,
    val subbed: Boolean = false,
    val dubbed: Boolean = false,
    val languages: List<String>? = null,
    val uLanguages: List<String>? = null,
    val subtitles: List<String>? = null,
    val uSubtitles: List<String>? = null,
    val languageEmojis: List<String>? = null,
    val uLanguageEmojis: List<String>? = null,
    val subtitleEmojis: List<String>? = null,
    val uSubtitleEmojis: List<String>? = null,
    val languageCodes: List<String>? = null,
    val uLanguageCodes: List<String>? = null,
    val subtitleCodes: List<String>? = null,
    val uSubtitleCodes: List<String>? = null,
    val smallLanguageCodes: List<String>? = null,
    val uSmallLanguageCodes: List<String>? = null,
    val smallSubtitleCodes: List<String>? = null,
    val uSmallSubtitleCodes: List<String>? = null,
    val wedontknowwhatakilometeris: List<String>? = null,
    val uWedontknowwhatakilometeris: List<String>? = null,
    val visualTags: List<String>? = null,
    val audioTags: List<String>? = null,
    val releaseGroup: String? = null,
    val regexMatched: String? = null,
    val rankedRegexMatched: List<String> = emptyList(),
    val regexScore: Int? = null,
    val nRegexScore: Int? = null,
    val encode: String? = null,
    val audioChannels: List<String>? = null,
    val edition: String? = null,
    val editions: List<String>? = null,
    val remastered: Boolean? = null,
    val regraded: Boolean = false,
    val repack: Boolean = false,
    val uncensored: Boolean = false,
    val unrated: Boolean = false,
    val upscaled: Boolean = false,
    val network: String? = null,
    val container: String? = null,
    val extension: String? = null,
    val indexer: String? = null,
    val year: String? = null,
    val title: String? = null,
    val date: String? = null,
    val folderSeasons: List<Int>? = null,
    val formattedFolderSeasons: String? = null,
    val seasons: List<Int>? = null,
    val season: Int? = null,
    val formattedSeasons: String? = null,
    val episodes: List<Int>? = null,
    val episode: Int? = null,
    val formattedEpisodes: String? = null,
    val folderEpisodes: List<Int>? = null,
    val formattedFolderEpisodes: String? = null,
    val seasonEpisode: List<String>? = null,
    val seasonPack: Boolean = false,
    val seeders: Int? = null,
    val private: Boolean = false,
    val freeleech: Boolean? = null,
    val age: String? = null,
    val ageHours: Int? = null,
    val duration: Long? = null,
    val infoHash: String? = null,
    val videoHash: String? = null,
    val type: String? = null,
    val bingeGroup: String? = null,
    val message: String? = null,
    val proxied: Boolean = false,
    val seadex: Boolean = false,
    val seadexBest: Boolean = false,
    val seScore: Int? = null,
    val nSeScore: Int? = null,
    val seMatched: String? = null,
    val rseMatched: List<String> = emptyList()
) {
    fun values(): Map<String, Any?> = linkedMapOf(
        "filename" to filename,
        "folderName" to folderName,
        "size" to size,
        "bitrate" to bitrate,
        "folderSize" to folderSize,
        "library" to library,
        "quality" to quality,
        "resolution" to resolution,
        "subbed" to subbed,
        "dubbed" to dubbed,
        "languages" to languages,
        "uLanguages" to uLanguages,
        "subtitles" to subtitles,
        "uSubtitles" to uSubtitles,
        "languageEmojis" to languageEmojis,
        "uLanguageEmojis" to uLanguageEmojis,
        "subtitleEmojis" to subtitleEmojis,
        "uSubtitleEmojis" to uSubtitleEmojis,
        "languageCodes" to languageCodes,
        "uLanguageCodes" to uLanguageCodes,
        "subtitleCodes" to subtitleCodes,
        "uSubtitleCodes" to uSubtitleCodes,
        "smallLanguageCodes" to smallLanguageCodes,
        "uSmallLanguageCodes" to uSmallLanguageCodes,
        "smallSubtitleCodes" to smallSubtitleCodes,
        "uSmallSubtitleCodes" to uSmallSubtitleCodes,
        "wedontknowwhatakilometeris" to wedontknowwhatakilometeris,
        "uWedontknowwhatakilometeris" to uWedontknowwhatakilometeris,
        "visualTags" to visualTags,
        "audioTags" to audioTags,
        "releaseGroup" to releaseGroup,
        "regexMatched" to regexMatched,
        "rankedRegexMatched" to rankedRegexMatched,
        "regexScore" to regexScore,
        "nRegexScore" to nRegexScore,
        "encode" to encode,
        "audioChannels" to audioChannels,
        "edition" to edition,
        "editions" to editions,
        "remastered" to remastered,
        "regraded" to regraded,
        "repack" to repack,
        "uncensored" to uncensored,
        "unrated" to unrated,
        "upscaled" to upscaled,
        "network" to network,
        "container" to container,
        "extension" to extension,
        "indexer" to indexer,
        "year" to year,
        "title" to title,
        "date" to date,
        "folderSeasons" to folderSeasons,
        "formattedFolderSeasons" to formattedFolderSeasons,
        "seasons" to seasons,
        "season" to season,
        "formattedSeasons" to formattedSeasons,
        "episodes" to episodes,
        "episode" to episode,
        "formattedEpisodes" to formattedEpisodes,
        "folderEpisodes" to folderEpisodes,
        "formattedFolderEpisodes" to formattedFolderEpisodes,
        "seasonEpisode" to seasonEpisode,
        "seasonPack" to seasonPack,
        "seeders" to seeders,
        "private" to private,
        "freeleech" to freeleech,
        "age" to age,
        "ageHours" to ageHours,
        "duration" to duration,
        "infoHash" to infoHash,
        "videoHash" to videoHash,
        "type" to type,
        "bingeGroup" to bingeGroup,
        "message" to message,
        "proxied" to proxied,
        "seadex" to seadex,
        "seadexBest" to seadexBest,
        "seScore" to seScore,
        "nSeScore" to nSeScore,
        "seMatched" to seMatched,
        "rseMatched" to rseMatched
    )
}

data class AioMetadataTemplateModel(
    val queryType: String? = null,
    val title: String? = null,
    val runtime: Int? = null,
    val genres: List<String>? = null,
    val year: Int? = null,
    val episodeRuntime: Int? = null
) {
    fun values(): Map<String, Any?> = linkedMapOf(
        "queryType" to queryType,
        "title" to title,
        "runtime" to runtime,
        "genres" to genres,
        "year" to year,
        "episodeRuntime" to episodeRuntime
    )
}

data class AioServiceTemplateModel(
    val id: String? = null,
    val shortName: String? = null,
    val name: String? = null,
    val cached: Boolean? = null
) {
    fun values(): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "shortName" to shortName,
        "name" to name,
        "cached" to cached
    )
}

data class AioAddonTemplateModel(
    val name: String? = null,
    val presetId: String? = null,
    val manifestUrl: String? = null
) {
    fun values(): Map<String, Any?> = linkedMapOf(
        "name" to name,
        "presetId" to presetId,
        "manifestUrl" to manifestUrl
    )
}

data class AioDebugTemplateModel(
    val json: String? = null,
    val jsonf: String? = null
) {
    fun values(): Map<String, Any?> = linkedMapOf(
        "json" to json,
        "jsonf" to jsonf
    )
}

class AioTemplateFormatter(
    private val nameTemplate: String,
    private val descriptionTemplate: String
) {
    fun format(parseValue: AioParseValue): AioFormattedText {
        return AioFormattedText(
            name = renderTemplate(nameTemplate, parseValue),
            description = renderTemplate(descriptionTemplate, parseValue)
        )
    }

    private fun renderTemplate(template: String, parseValue: AioParseValue, depth: Int = 0): String {
        if (depth > 5) return template
        val placeholders = findTopLevelPlaceholders(template)
        if (placeholders.isEmpty()) {
            return template
                .replace("\\n", "\n")
                .replace("{tools.newLine}", "\n")
                .lines()
                .filter { line -> line.trim().isNotEmpty() && !line.contains("{tools.removeLine}") }
                .joinToString("\n")
        }
        val rendered = buildString {
            var cursor = 0
            for (placeholder in placeholders) {
                append(template.substring(cursor, placeholder.start))
                append(evaluatePlaceholder(placeholder.content, parseValue, depth))
                cursor = placeholder.end
            }
            append(template.substring(cursor))
        }
        return rendered
            .replace("{tools.newLine}", "\n")
            .lines()
            .filter { line -> line.trim().isNotEmpty() && !line.contains("{tools.removeLine}") }
            .joinToString("\n")
    }

    private fun evaluatePlaceholder(content: String, parseValue: AioParseValue, depth: Int): String {
        val (expressionContent, check) = extractCheck(content)
        val resolved = evaluateComparatorExpression(expressionContent, parseValue, depth)
        val result = if (check != null) {
            val truthy = resolved.asBooleanForCheck()
            if (truthy == true) renderTemplate(check.first, parseValue, depth + 1)
            else if (truthy == false) renderTemplate(check.second, parseValue, depth + 1)
            else ""
        } else {
            resolved.value?.toString().orEmpty()
        }
        return result
    }

    private fun evaluateComparatorExpression(
        expression: String,
        parseValue: AioParseValue,
        depth: Int
    ): ResolvedValue {
        val parts = splitOnComparators(expression)
        var result = evaluateVariableExpression(parts.first().first, parseValue, depth)
        for (index in 1 until parts.size) {
            val comparator = parts[index - 1].second
            val next = evaluateVariableExpression(parts[index].first, parseValue, depth)
            result = ResolvedValue(
                when (comparator) {
                    "and" -> result.truthy() && next.truthy()
                    "or" -> result.truthy() || next.truthy()
                    "xor" -> result.truthy().xor(next.truthy())
                    "neq" -> result.value != next.value
                    "equal" -> result.value == next.value
                    "left" -> result.value
                    "right" -> next.value
                    else -> next.value
                }
            )
        }
        return result
    }

    private fun evaluateVariableExpression(
        expression: String,
        parseValue: AioParseValue,
        depth: Int
    ): ResolvedValue {
        val segments = splitModifiers(expression)
        val base = segments.firstOrNull().orEmpty()
        val section = base.substringBefore('.', "")
        val property = base.substringAfter('.', "")
        val initial = parseValue.section(section)?.get(property)
        var current: Any? = initial
        for (modifier in segments.drop(1)) {
            current = applyModifier(current, modifier, parseValue, depth)
        }
        return ResolvedValue(current)
    }

    private fun applyModifier(
        variable: Any?,
        modifier: String,
        parseValue: AioParseValue,
        depth: Int
    ): Any? {
        if (isConditionalModifier(modifier)) {
            return evaluateConditional(variable, modifier)
        }
        return when (variable) {
            is String -> applyStringModifier(variable, modifier, parseValue, depth)
            is List<*> -> applyArrayModifier(variable, modifier)
            is Number -> applyNumberModifier(variable.toLong(), modifier)
            is Boolean -> if (modifier == "string") variable.toString() else null
            null -> if (modifier == "exists") false else null
            else -> null
        }
    }

    private fun applyStringModifier(
        variable: String,
        modifier: String,
        parseValue: AioParseValue,
        depth: Int
    ): Any? {
        return when {
            modifier.equals("upper", true) -> variable.uppercase(Locale.US)
            modifier.equals("lower", true) -> variable.lowercase(Locale.US)
            modifier.equals("title", true) -> variable.split(' ').joinToString(" ") { word ->
                word.lowercase(Locale.US).replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString()
                }
            }
            modifier.equals("length", true) -> variable.length.toString()
            modifier.equals("reverse", true) -> variable.reversed()
            modifier.equals("base64", true) -> Base64.getEncoder().encodeToString(variable.toByteArray())
            modifier.equals("string", true) -> variable
            modifier.equals("smallcaps", true) -> makeSmallCaps(variable)
            modifier.startsWith("replace(", true) && modifier.endsWith(")") -> {
                val args = parseQuotedArguments(modifier.removePrefix("replace(").removeSuffix(")"))
                if (args.size >= 2) {
                    var key = args[0]
                    if (key.startsWith("{") && key.endsWith("}")) {
                        key = renderTemplate(key, parseValue, depth + 1)
                    }
                    if (key.isEmpty()) variable else variable.replace(key, args[1])
                } else null
            }
            modifier.startsWith("remove(", true) && modifier.endsWith(")") -> {
                parseQuotedArguments(modifier.removePrefix("remove(").removeSuffix(")"))
                    .fold(variable) { acc, item -> acc.replace(item, "") }
            }
            modifier.startsWith("truncate(", true) && modifier.endsWith(")") -> {
                val length = modifier.removePrefix("truncate(").removeSuffix(")").trim().toIntOrNull()
                if (length != null && length >= 0 && variable.length > length) {
                    variable.take(length).replace(Regex("\\s+$"), "") + "…"
                } else {
                    variable
                }
            }
            else -> null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun applyArrayModifier(variable: List<*>, modifier: String): Any? {
        val list = variable.filterNotNull()
        return when {
            modifier.equals("join", true) -> list.joinToString(", ")
            modifier.equals("length", true) -> list.size.toString()
            modifier.equals("first", true) -> list.firstOrNull()?.toString().orEmpty()
            modifier.equals("last", true) -> list.lastOrNull()?.toString().orEmpty()
            modifier.equals("random", true) -> if (list.isEmpty()) "" else list[Random.nextInt(list.size)].toString()
            modifier.equals("sort", true) -> sortMixedList(list, ascending = true)
            modifier.equals("rsort", true) -> sortMixedList(list, ascending = false)
            modifier.equals("lsort", true) -> list.sortedBy { it.toString() }
            modifier.equals("reverse", true) -> list.reversed()
            modifier.equals("string", true) -> list.joinToString(",")
            modifier.startsWith("slice(", true) && modifier.endsWith(")") -> {
                val args = modifier.removePrefix("slice(").removeSuffix(")").split(',').mapNotNull { it.trim().toIntOrNull() }
                if (args.isNotEmpty()) list.drop(args[0]).let { dropped ->
                    if (args.size > 1) dropped.take((args[1] - args[0]).coerceAtLeast(0)) else dropped
                } else null
            }
            modifier.startsWith("join(", true) && modifier.endsWith(")") -> {
                val separator = parseQuotedArguments(modifier.removePrefix("join(").removeSuffix(")")).firstOrNull() ?: return null
                list.joinToString(separator) { it.toString() }
            }
            modifier.startsWith("remove(", true) && modifier.endsWith(")") -> {
                val args = parseQuotedArguments(modifier.removePrefix("remove(").removeSuffix(")")).toSet()
                list.filterNot { args.contains(it.toString()) }
            }
            else -> if (isConditionalModifier(modifier)) evaluateConditional(list, modifier) else null
        }
    }

    private fun applyNumberModifier(variable: Long, modifier: String): Any? {
        return when {
            modifier.equals("comma", true) -> String.format(Locale.US, "%,d", variable)
            modifier.equals("hex", true) -> variable.toString(16)
            modifier.equals("octal", true) -> variable.toString(8)
            modifier.equals("binary", true) -> variable.toString(2)
            modifier.equals("bytes", true) || modifier.equals("bytes10", true) -> formatBytes(variable, 1000, false)
            modifier.equals("sbytes", true) || modifier.equals("sbytes10", true) -> formatSmartBytes(variable, 1000)
            modifier.equals("sbytes2", true) -> formatSmartBytes(variable, 1024)
            modifier.equals("rbytes", true) || modifier.equals("rbytes10", true) -> formatBytes(variable, 1000, true)
            modifier.equals("bytes2", true) -> formatBytes(variable, 1024, false)
            modifier.equals("rbytes2", true) -> formatBytes(variable, 1024, true)
            modifier.equals("bitrate", true) -> formatBitrate(variable, false)
            modifier.equals("rbitrate", true) -> formatBitrate(variable, true)
            modifier.equals("sbitrate", true) -> formatSmartBitrate(variable)
            modifier.equals("string", true) -> variable.toString()
            modifier.equals("time", true) -> formatDuration(variable)
            modifier.equals("star", true) -> formatStars(variable.toInt(), false)
            modifier.equals("pstar", true) -> formatStars(variable.toInt(), true)
            else -> if (isConditionalModifier(modifier)) evaluateConditional(variable, modifier) else null
        }
    }

    private fun evaluateConditional(variable: Any?, modifier: String): Boolean {
        val normalizedModifier = modifier.lowercase(Locale.US)
        return when {
            normalizedModifier == "istrue" -> variable == true
            normalizedModifier == "isfalse" -> variable == false
            normalizedModifier == "exists" -> exists(variable)
            normalizedModifier.startsWith(">=") -> compare(variable, normalizedModifier.removePrefix(">="), Comparison.GTE)
            normalizedModifier.startsWith("<=") -> compare(variable, normalizedModifier.removePrefix("<="), Comparison.LTE)
            normalizedModifier.startsWith(">") -> compare(variable, normalizedModifier.removePrefix(">"), Comparison.GT)
            normalizedModifier.startsWith("<") -> compare(variable, normalizedModifier.removePrefix("<"), Comparison.LT)
            normalizedModifier.startsWith("=") -> compare(variable, normalizedModifier.removePrefix("="), Comparison.EQ)
            normalizedModifier.startsWith("$") -> {
                val check = normalizedModifier.removePrefix("$")
                when (variable) {
                    is String -> normalizeConditionalSource(variable).startsWith(check)
                    is List<*> -> variable.firstOrNull()?.toString()?.lowercase(Locale.US) == check
                    else -> false
                }
            }
            normalizedModifier.startsWith("^") -> {
                val check = normalizedModifier.removePrefix("^")
                when (variable) {
                    is String -> normalizeConditionalSource(variable).endsWith(check)
                    is List<*> -> variable.lastOrNull()?.toString()?.lowercase(Locale.US) == check
                    else -> false
                }
            }
            normalizedModifier.startsWith("~") -> {
                val check = normalizedModifier.removePrefix("~")
                when (variable) {
                    is String -> normalizeConditionalSource(variable).contains(check)
                    is List<*> -> variable.map { it.toString().lowercase(Locale.US) }.any { it.contains(check) }
                    else -> false
                }
            }
            else -> false
        }
    }

    private fun compare(variable: Any?, check: String, comparison: Comparison): Boolean {
        val numberValue = when (variable) {
            is Number -> variable.toDouble()
            is String -> variable.replace(",", "").trim().toDoubleOrNull()
            else -> null
        }
        val numberCheck = check.replace(",", "").trim().toDoubleOrNull()
        if (numberValue != null && numberCheck != null) {
            return when (comparison) {
                Comparison.GTE -> numberValue >= numberCheck
                Comparison.GT -> numberValue > numberCheck
                Comparison.LTE -> numberValue <= numberCheck
                Comparison.LT -> numberValue < numberCheck
                Comparison.EQ -> numberValue == numberCheck
            }
        }
        val left = variable?.toString()?.lowercase(Locale.US).orEmpty()
        val right = check.lowercase(Locale.US)
        return when (comparison) {
            Comparison.EQ -> left == right
            Comparison.GTE -> left >= right
            Comparison.GT -> left > right
            Comparison.LTE -> left <= right
            Comparison.LT -> left < right
        }
    }

    private fun normalizeConditionalSource(value: String): String {
        val lower = value.lowercase(Locale.US)
        return if (lower.contains(' ')) lower else lower.replace(" ", "")
    }

    private fun exists(value: Any?): Boolean {
        return when (value) {
            null -> false
            is String -> value.any { !it.isWhitespace() }
            is List<*> -> value.isNotEmpty()
            else -> true
        }
    }

    private fun isConditionalModifier(modifier: String): Boolean {
        val lower = modifier.lowercase(Locale.US)
        return lower == "istrue" ||
            lower == "isfalse" ||
            lower == "exists" ||
            listOf(">=", "<=", ">", "<", "=", "$", "^", "~").any { lower.startsWith(it) }
    }

    private fun splitModifiers(expression: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var parenDepth = 0
        var braceDepth = 0
        var quote: Char? = null
        var index = 0
        while (index < expression.length) {
            val char = expression[index]
            if (quote != null) {
                if (char == quote) quote = null
                current.append(char)
                index += 1
                continue
            }
            when (char) {
                '\'', '"' -> {
                    quote = char
                    current.append(char)
                }
                '(' -> {
                    parenDepth += 1
                    current.append(char)
                }
                ')' -> {
                    parenDepth = (parenDepth - 1).coerceAtLeast(0)
                    current.append(char)
                }
                '{' -> {
                    braceDepth += 1
                    current.append(char)
                }
                '}' -> {
                    braceDepth = (braceDepth - 1).coerceAtLeast(0)
                    current.append(char)
                }
                ':' -> {
                    if (expression.startsWith("::", index) && parenDepth == 0 && braceDepth == 0) {
                        parts += current.toString()
                        current.clear()
                        index += 2
                        continue
                    }
                    current.append(char)
                }
                else -> current.append(char)
            }
            index += 1
        }
        parts += current.toString()
        return parts.filter { it.isNotEmpty() }
    }

    private fun splitOnComparators(expression: String): List<Pair<String, String?>> {
        val result = mutableListOf<Pair<String, String?>>()
        val current = StringBuilder()
        var parenDepth = 0
        var braceDepth = 0
        var quote: Char? = null
        var index = 0
        while (index < expression.length) {
            val char = expression[index]
            if (quote != null) {
                if (char == quote) quote = null
                current.append(char)
                index += 1
                continue
            }
            when (char) {
                '\'', '"' -> {
                    quote = char
                    current.append(char)
                }
                '(' -> {
                    parenDepth += 1
                    current.append(char)
                }
                ')' -> {
                    parenDepth = (parenDepth - 1).coerceAtLeast(0)
                    current.append(char)
                }
                '{' -> {
                    braceDepth += 1
                    current.append(char)
                }
                '}' -> {
                    braceDepth = (braceDepth - 1).coerceAtLeast(0)
                    current.append(char)
                }
                ':' -> {
                    if (parenDepth == 0 && braceDepth == 0) {
                        val matched = comparators.firstOrNull { expression.startsWith(it.token, index) }
                        if (matched != null) {
                            result += current.toString() to matched.name
                            current.clear()
                            index += matched.token.length
                            continue
                        }
                    }
                    current.append(char)
                }
                else -> current.append(char)
            }
            index += 1
        }
        result += current.toString() to null
        return result
    }

    private fun extractCheck(content: String): Pair<String, Pair<String, String>?> {
        if (!content.endsWith("]")) return content to null
        val start = findTopLevelOpeningBracket(content) ?: return content to null
        val closing = findMatchingClosingBracket(content, start) ?: return content to null
        if (closing != content.lastIndex) return content to null
        val separator = findTopLevelSeparator(content, start + 1, closing, "||") ?: return content to null
        val left = content.substring(start + 1, separator).trim().removeSurrounding("\"")
        val right = content.substring(separator + 2, closing).trim().removeSurrounding("\"")
        return content.substring(0, start) to (left to right)
    }

    private fun findTopLevelOpeningBracket(content: String): Int? {
        var parenDepth = 0
        var braceDepth = 0
        var quote: Char? = null
        content.forEachIndexed { index, char ->
            if (quote != null) {
                if (char == quote) quote = null
                return@forEachIndexed
            }
            when (char) {
                '\'', '"' -> quote = char
                '(' -> parenDepth += 1
                ')' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                '{' -> braceDepth += 1
                '}' -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
                '[' -> if (parenDepth == 0 && braceDepth == 0) return index
            }
        }
        return null
    }

    private fun findMatchingClosingBracket(content: String, openingIndex: Int): Int? {
        var quote: Char? = null
        var nestedBracketDepth = 0
        for (index in openingIndex + 1 until content.length) {
            val char = content[index]
            if (quote != null) {
                if (char == quote) quote = null
                continue
            }
            when (char) {
                '\'', '"' -> quote = char
                '[' -> nestedBracketDepth += 1
                ']' -> {
                    if (nestedBracketDepth == 0) return index
                    nestedBracketDepth -= 1
                }
            }
        }
        return null
    }

    private fun findTopLevelSeparator(
        content: String,
        start: Int,
        endExclusive: Int,
        separator: String
    ): Int? {
        var parenDepth = 0
        var braceDepth = 0
        var bracketDepth = 0
        var quote: Char? = null
        var index = start
        while (index <= endExclusive - separator.length) {
            val char = content[index]
            if (quote != null) {
                if (char == quote) quote = null
                index += 1
                continue
            }
            when (char) {
                '\'', '"' -> quote = char
                '(' -> parenDepth += 1
                ')' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                '{' -> braceDepth += 1
                '}' -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
                '[' -> bracketDepth += 1
                ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
            }
            if (parenDepth == 0 &&
                braceDepth == 0 &&
                bracketDepth == 0 &&
                content.startsWith(separator, index)
            ) {
                return index
            }
            index += 1
        }
        return null
    }

    private fun findTopLevelPlaceholders(template: String): List<Placeholder> {
        val placeholders = mutableListOf<Placeholder>()
        var start = -1
        var depth = 0
        var index = 0
        while (index < template.length) {
            when (template[index]) {
                '{' -> {
                    if (depth == 0) start = index
                    depth += 1
                }
                '}' -> {
                    depth -= 1
                    if (depth == 0 && start >= 0) {
                        placeholders += Placeholder(start, index + 1, template.substring(start + 1, index))
                        start = -1
                    }
                }
            }
            index += 1
        }
        return placeholders
    }

    private fun parseQuotedArguments(input: String): List<String> {
        val args = mutableListOf<String>()
        var index = 0
        while (index < input.length) {
            while (index < input.length && input[index].isWhitespace()) index += 1
            if (index >= input.length) break
            if (input[index] == '\'' || input[index] == '"') {
                val quote = input[index]
                val start = ++index
                while (index < input.length && input[index] != quote) index += 1
                args += input.substring(start, index)
                index += 1
            } else if (input[index] == '{') {
                val start = index
                var depth = 1
                index += 1
                while (index < input.length && depth > 0) {
                    if (input[index] == '{') depth += 1
                    if (input[index] == '}') depth -= 1
                    index += 1
                }
                args += input.substring(start, index)
            } else {
                val start = index
                while (index < input.length && input[index] != ',') index += 1
                args += input.substring(start, index).trim()
            }
            while (index < input.length && (input[index].isWhitespace() || input[index] == ',')) index += 1
        }
        return args
    }

    private fun sortMixedList(values: List<Any>, ascending: Boolean): List<Any> {
        val sorted = values.sortedWith { left, right ->
            val result = when {
                left is Number && right is Number -> left.toDouble().compareTo(right.toDouble())
                else -> left.toString().compareTo(right.toString(), ignoreCase = false)
            }
            if (ascending) result else -result
        }
        return sorted
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = floor(durationMs / 1000.0).toLong()
        val minutes = totalSeconds / 60
        val hours = minutes / 60
        val seconds = totalSeconds % 60
        val formattedMinutes = minutes % 60
        return if (hours > 0) {
            "${hours}h:${formattedMinutes}m:${seconds}s"
        } else if (seconds > 0) {
            "${formattedMinutes}m:${seconds}s"
        } else {
            "${formattedMinutes}m"
        }
    }

    private fun formatBytes(bytes: Long, base: Int, round: Boolean): String {
        if (bytes == 0L) return "0 B"
        val sizes = if (base == 1024) listOf("B", "KiB", "MiB", "GiB", "TiB") else listOf("B", "KB", "MB", "GB", "TB")
        val exponent = (kotlin.math.ln(bytes.toDouble()) / kotlin.math.ln(base.toDouble())).toInt().coerceAtMost(sizes.lastIndex)
        var value = bytes / Math.pow(base.toDouble(), exponent.toDouble())
        value = if (round) kotlin.math.round(value) else String.format(Locale.US, "%.2f", value).toDouble()
        return "${trimTrailingZeros(value)} ${sizes[exponent]}"
    }

    private fun formatSmartBytes(bytes: Long, base: Int): String {
        if (bytes == 0L) return "0 B"
        val sizes = if (base == 1024) listOf("B", "KiB", "MiB", "GiB", "TiB") else listOf("B", "KB", "MB", "GB", "TB")
        val exponent = (kotlin.math.ln(bytes.toDouble()) / kotlin.math.ln(base.toDouble())).toInt().coerceAtMost(sizes.lastIndex)
        val raw = bytes / Math.pow(base.toDouble(), exponent.toDouble())
        val integerPart = floor(raw).toInt()
        val formatted = when {
            integerPart >= 100 -> kotlin.math.round(raw).toLong().toString()
            integerPart >= 10 -> trimTrailingZeros((raw * 10.0).roundToLong() / 10.0)
            else -> trimTrailingZeros((raw * 100.0).roundToLong() / 100.0)
        }
        return "$formatted ${sizes[exponent]}"
    }

    private fun formatBitrate(bitrate: Long, round: Boolean): String {
        if (bitrate <= 0) return "0 bps"
        val sizes = listOf("bps", "Kbps", "Mbps", "Gbps", "Tbps")
        val exponent = (kotlin.math.ln(bitrate.toDouble()) / kotlin.math.ln(1000.0)).toInt().coerceAtMost(sizes.lastIndex)
        var value = bitrate / Math.pow(1000.0, exponent.toDouble())
        value = if (round) kotlin.math.round(value) else String.format(Locale.US, "%.2f", value).toDouble()
        return "${trimTrailingZeros(value)} ${sizes[exponent]}"
    }

    private fun formatSmartBitrate(bitrate: Long): String {
        if (bitrate <= 0) return "0 bps"
        val sizes = listOf("bps", "Kbps", "Mbps", "Gbps", "Tbps")
        val exponent = (kotlin.math.ln(bitrate.toDouble()) / kotlin.math.ln(1000.0)).toInt().coerceAtMost(sizes.lastIndex)
        val raw = bitrate / Math.pow(1000.0, exponent.toDouble())
        val integerPart = floor(raw).toInt()
        val formatted = when {
            integerPart >= 100 -> kotlin.math.round(raw).toLong().toString()
            integerPart >= 10 -> trimTrailingZeros((raw * 10.0).roundToLong() / 10.0)
            else -> trimTrailingZeros((raw * 100.0).roundToLong() / 100.0)
        }
        return "$formatted ${sizes[exponent]}"
    }

    private fun trimTrailingZeros(value: Double): String {
        return if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
    }

    private fun formatStars(value: Int, padWithEmpty: Boolean): String {
        val full = value / 20
        val half = if (value % 20 >= 10) 1 else 0
        val empty = 5 - full - half
        return buildString {
            append("★".repeat(full))
            append("⯪".repeat(half))
            if (padWithEmpty) append("☆".repeat(empty))
        }
    }

    private fun makeSmallCaps(input: String): String {
        return input.map { smallCapsMap[it.uppercaseChar()] ?: it }.joinToString("")
    }

    private data class Placeholder(val start: Int, val end: Int, val content: String)
    private data class ResolvedValue(val value: Any?) {
        fun truthy(): Boolean = when (value) {
            is Boolean -> value
            is String -> value.isNotBlank()
            is List<*> -> value.isNotEmpty()
            null -> false
            else -> true
        }

        fun asBooleanForCheck(): Boolean? = value as? Boolean
    }

    private data class ComparatorToken(val token: String, val name: String)

    private enum class Comparison { GTE, GT, LTE, LT, EQ }

    companion object {
        private val comparators = listOf(
            ComparatorToken("::equal::", "equal"),
            ComparatorToken("::right::", "right"),
            ComparatorToken("::left::", "left"),
            ComparatorToken("::and::", "and"),
            ComparatorToken("::xor::", "xor"),
            ComparatorToken("::neq::", "neq"),
            ComparatorToken("::or::", "or")
        )

        private val smallCapsMap = mapOf(
            'A' to 'ᴀ', 'B' to 'ʙ', 'C' to 'ᴄ', 'D' to 'ᴅ', 'E' to 'ᴇ', 'F' to 'ꜰ', 'G' to 'ɢ',
            'H' to 'ʜ', 'I' to 'ɪ', 'J' to 'ᴊ', 'K' to 'ᴋ', 'L' to 'ʟ', 'M' to 'ᴍ', 'N' to 'ɴ',
            'O' to 'ᴏ', 'P' to 'ᴘ', 'Q' to 'ꞯ', 'R' to 'ʀ', 'S' to 'ꜱ', 'T' to 'ᴛ', 'U' to 'ᴜ',
            'V' to 'ᴠ', 'W' to 'ᴡ', 'X' to 'х', 'Y' to 'ʏ', 'Z' to 'ᴢ'
        )
    }
}

object AioBuiltInFormatters {
    const val DEFAULT_TEMPLATE_ID = "universal"

    val TORRENTIO = AioTemplateDefinition(
        id = "torrentio",
        nameTemplate = "{stream.proxied::istrue[\"🕵️‍♂️ \"||\"\"]}{stream.private::istrue[\"🔑 \"||\"\"]}{stream.type::=p2p[\"[P2P] \"||\"\"]}{service.id::exists[\"[{service.shortName}\"||\"\"]}{service.cached::istrue[\"]+ \"||\"\"]}{service.cached::isfalse[\" download] \"||\"\"]}{addon.name} {stream.resolution::exists[\"{stream.resolution}\"||\"Unknown\"]}\n{stream.visualTags::exists[\"{stream.visualTags::join(' | ')}\"||\"\"]}",
        descriptionTemplate = "{stream.message::exists[\"ℹ️{stream.message}\"||\"\"]}\n{stream.folderName::exists[\"{stream.folderName}\"||\"\"]}\n{stream.filename::exists[\"{stream.filename}\"||\"\"]}\n{stream.size::>0[\"💾{stream.size::bytes2} \"||\"\"]}{stream.folderSize::>0[\"/ 💾{stream.folderSize::bytes2}\"||\"\"]}{stream.seeders::>=0[\"👤{stream.seeders} \"||\"\"]}{stream.age::exists[\"📅{stream.age} \"||\"\"]}{stream.indexer::exists[\"⚙️{stream.indexer}\"||\"\"]}\n{stream.languageEmojis::exists[\"{stream.languageEmojis::join(' / ')}\"||\"\"]}{stream.subtitles::exists::and::stream.languageEmojis::exists[\" \"||\"\"]}{stream.subtitles::exists[\"Subs / {stream.subtitleEmojis::join(' / ')}\"||\"\"]}"
    )

    val TORBOX = AioTemplateDefinition(
        id = "torbox",
        nameTemplate = "{stream.proxied::istrue[\"🕵️‍♂️ \"||\"\"]}{stream.private::istrue[\"🔑 \"||\"\"]}{stream.type::=p2p[\"[P2P] \"||\"\"]}{addon.name}{stream.library::istrue[\" (Your Media) \"||\"\"]}{service.cached::istrue[\" (Instant \"||\"\"]}{service.cached::isfalse[\" (\"||\"\"]}{service.id::exists[\"{service.shortName})\"||\"\"]}{stream.resolution::exists[\" ({stream.resolution})\"||\"\"]}",
        descriptionTemplate = "Quality: {stream.quality::exists[\"{stream.quality}\"||\"Unknown\"]}\nName: {stream.filename::exists[\"{stream.filename}\"||\"Unknown\"]}\nSize: {stream.size::>0[\"{stream.size::bytes} \"||\"\"]}{stream.folderSize::>0[\"/ {stream.folderSize::bytes} \"||\"\"]}{stream.indexer::exists[\"| Source: {stream.indexer} \"||\"\"]}{stream.duration::>0[\"| Duration: {stream.duration::time} \"||\"\"]}\nLanguages: {stream.languages::exists[\"{stream.languages::join(', ')}\"||\"\"]}{stream.subtitles::exists::and::stream.languages::exists[\" | \"||\"\"]}{stream.subtitles::exists[\"Subtitles: {stream.subtitles::join(', ')}\"||\"\"]}\n{stream.message::exists[\"Message: {stream.message}\"||\"\"]}"
    )

    val GDRIVE = AioTemplateDefinition(
        id = "gdrive",
        nameTemplate = "{stream.proxied::istrue[\"🕵️ \"||\"\"]}{stream.private::istrue[\"🔑 \"||\"\"]}{stream.type::=p2p[\"[P2P] \"||\"\"]}{service.shortName::exists[\"[{service.shortName}\"||\"\"]}{service.cached::istrue[\"⚡] \"||\"\"]}{service.cached::isfalse[\"⏳] \"||\"\"]}{addon.name}{stream.library::istrue[\" (Your Media)\"||\"\"]} {stream.resolution::exists[\"{stream.resolution}\"||\"\"]}{stream.seadexBest::istrue[\" (Best)\"||\"\"]}{stream.seadex::istrue::and::stream.seadexBest::isfalse[\" (SeaDex Alt.)\"||\"\"]}",
        descriptionTemplate = "{stream.quality::exists[\"🎥 {stream.quality} \"||\"\"]}{stream.encode::exists[\"🎞️ {stream.encode} \"||\"\"]}{stream.releaseGroup::exists[\"🏷️ {stream.releaseGroup} \"||\"\"]}{stream.network::exists[\"📡 {stream.network} \"||\"\"]}\n{stream.visualTags::exists[\"📺 {stream.visualTags::join(' | ')} \"||\"\"]}{stream.audioTags::exists[\"🎧 {stream.audioTags::join(' | ')} \"||\"\"]}{stream.audioChannels::exists[\"🔊 {stream.audioChannels::join(' | ')}\"||\"\"]}\n{stream.size::>0[\"📦 {stream.size::sbytes} \"||\"\"]}{stream.folderSize::>0[\"/ {stream.folderSize::sbytes} \"||\"\"]}{stream.bitrate::>0[\"({stream.bitrate::sbitrate})\"||\"\"]}{stream.duration::>0[\"⏱️ {stream.duration::time} \"||\"\"]}{stream.seeders::>0[\"👥 {stream.seeders} \"||\"\"]}{stream.age::exists[\"📅 {stream.age} \"||\"\"]}{stream.indexer::exists[\"🔍 {stream.indexer}\"||\"\"]}\n{stream.languages::exists[\"🌎 {stream.languages::join(' | ')}\"||\"\"]}{stream.subtitles::exists[\"📝 {stream.subtitles::join(' | ')}\"||\"\"]}\n{stream.filename::exists[\"📁\"||\"\"]} {stream.folderName::exists[\"{stream.folderName}/\"||\"\"]}{stream.filename::exists[\"{stream.filename}\"||\"\"]}\n{stream.message::exists[\"ℹ️ {stream.message}\"||\"\"]}"
    )

    val LIGHT_GDRIVE = AioTemplateDefinition(
        id = "lightgdrive",
        nameTemplate = "{stream.proxied::istrue[\"🕵️ \"||\"\"]}{stream.private::istrue[\"🔑 \"||\"\"]}{stream.type::=p2p[\"[P2P] \"||\"\"]}{service.shortName::exists[\"[{service.shortName}\"||\"\"]}{stream.library::istrue[\"☁️\"||\"\"]}{service.cached::istrue[\"⚡] \"||\"\"]}{service.cached::isfalse[\"⏳] \"||\"\"]}{addon.name}{stream.resolution::exists[\" {stream.resolution}\"||\"\"]}{stream.seadexBest::istrue[\" (Best)\"||\"\"]}{stream.seadex::istrue::and::stream.seadexBest::isfalse[\" (SeaDex Alt.)\"||\"\"]}",
        descriptionTemplate = "{stream.title::exists[\"📁 {stream.title::title}\"||\"\"]}{stream.year::exists[\" ({stream.year})\"||\"\"]}{stream.seasonEpisode::exists[\" {stream.seasonEpisode::join(' • ')}\"||\"\"]}\n{stream.quality::exists[\"🎥 {stream.quality} \"||\"\"]}{stream.encode::exists[\"🎞️ {stream.encode} \"||\"\"]}{stream.releaseGroup::exists[\"🏷️ {stream.releaseGroup}\"||\"\"]}{stream.network::exists[\"📡 {stream.network} \"||\"\"]}\n{stream.visualTags::exists[\"📺 {stream.visualTags::join(' • ')} \"||\"\"]}{stream.audioTags::exists[\"🎧 {stream.audioTags::join(' • ')} \"||\"\"]}{stream.audioChannels::exists[\"🔊 {stream.audioChannels::join(' • ')}\"||\"\"]}\n{stream.size::>0[\"📦 {stream.size::sbytes} \"||\"\"]}{stream.folderSize::>0[\"/ {stream.folderSize::sbytes} \"||\"\"]}{stream.duration::>0[\"⏱️ {stream.duration::time} \"||\"\"]}{stream.age::exists[\"📅 {stream.age} \"||\"\"]}{stream.indexer::exists[\"🔍 {stream.indexer}\"||\"\"]}\n{stream.languageEmojis::exists[\"🌐 {stream.languageEmojis::join(' / ')}\"||\"\"]}{stream.subtitles::exists[\"📝 {stream.subtitleEmojis::join(' / ')}\"||\"\"]}\n{stream.message::exists[\"ℹ️ {stream.message}\"||\"\"]}"
    )

    val UNIVERSAL = AioTemplateDefinition(
        id = "universal",
        nameTemplate = """{stream.resolution::exists["{stream.resolution::replace('2160p','[[icon:4k]]')::replace('1440p','[[icon:2k]]')::replace('1080p','[[icon:fullhd]]')::replace('720p','[[icon:hd]]')::replace('576p','[[icon:sd]]')::replace('480p','[[icon:sd]]')}"||""]}{stream.resolution::exists::and::stream.title::exists["   "||""]}{stream.title::exists["{stream.title::title::truncate(30)}"||"?"]}{stream.year::exists[" ({stream.year})"||""]}{stream.seasonEpisode::exists[" ({stream.seasonEpisode::join(' ')::replace('S','S')::replace('E','E')})"||""]}{stream.seadexBest::istrue[" 🏆"||""]}{stream.seadex::istrue::and::stream.seadexBest::isfalse[" 🥈"||""]}{stream.message::~Download["{tools.removeLine}"||""]}""",
        descriptionTemplate = """
🎥 {stream.quality::exists["{stream.quality::title::replace('Bluray Remux','Lossless BD')::replace('Bluray','Blu-ray')::replace('Web-Dl','Streaming')::replace('Web-dl','Streaming')::replace('Webrip','Web Rip')::replace('Hdrip','HD Rip')::replace('Dvdrip','DVD Rip')::replace('Hdtv','HDTV')::replace('Cam','CAM')::replace('Ts','Telesync')::replace('Tc','Telecine')::replace('Scr','Screener')}"||""]}    {stream.audioTags::exists["{stream.audioTags::join('  ')::replace('Atmos','[[icon:atmos]]')::replace('TrueHD','[[icon:truehd]]')::replace('DTS-HD MA','[[icon:dtshd]]')::replace('DTS:X','[[icon:dtsx]]')::replace('DD+','[[icon:ddp]]')::replace('DD','[[icon:dd]]')::replace('EAC3','[[icon:ddp]]')::replace('AC3','[[icon:dd]]')::replace('DTS','[[icon:dts]]')}"||""]}   ⏱️ {stream.duration::>0["{stream.duration::time}"||"Unknown"]}
💾 {stream.size::>0["{stream.size::bytes}"||"Unknown"]} • ☁️ {service.name::exists["{service.name}"||"Unknown"]} • {addon.name}
{stream.uLanguages::exists["🗣️ {stream.uLanguageEmojis::join(' ')} • "||""]}{stream.seasonPack::istrue["📦 Pack • "||""]}{stream.filename::~NF["[[icon:netflix]] Netflix"||""]}{stream.filename::~DSNP["[[icon:disneyplus]] Disney+"||""]}{stream.filename::~HMAX["[[icon:hbo]] HBO Max"||""]}{stream.filename::~.MAX.["[[icon:max]] Max"||""]}{stream.filename::~AMZN["[[icon:prime]] Amazon"||""]}{stream.filename::~APTV["[[icon:appletv]] Apple TV+"||""]}{stream.filename::~PMTP["[[icon:paramount]] Paramount+"||""]}{stream.filename::~PCOK["[[icon:peacock]] Peacock"||""]}{stream.filename::~CRTC["[[icon:crunchyroll]] Crunchyroll"||""]}{stream.filename::~CR.["[[icon:crunchyroll]] Crunchyroll"||""]}{stream.filename::~NF::or::stream.filename::~DSNP::or::stream.filename::~HMAX::or::stream.filename::~.MAX.::or::stream.filename::~AMZN::or::stream.filename::~APTV::or::stream.filename::~PMTP::or::stream.filename::~PCOK::or::stream.filename::~CRTC::or::stream.filename::~CR.::and::stream.releaseGroup::exists[" • "||""]}{stream.releaseGroup::exists["👤 {stream.releaseGroup::truncate(10)}"||""]}{stream.seadexBest::istrue[" • 🏆 BEST"||""]}{stream.seadex::istrue::and::stream.seadexBest::isfalse[" • 🥈 ALT"||""]}{stream.repack::istrue[" • 🔄 Repack"||""]}
📄 {stream.filename::exists["{stream.filename}"||"—"]}
""".trimIndent()
    )

    val PRISM = AioTemplateDefinition(
        id = "prism",
        nameTemplate = "{stream.resolution::exists[\"{stream.resolution::replace('2160p', '🔥4K UHD')::replace('1440p','✨ QHD')::replace('1080p','🚀 FHD')::replace('720p','💿 HD')::replace('576p','💩 Low Quality')::replace('480p','💩 Low Quality')::replace('360p','💩 Low Quality')::replace('240p','💩 Low Quality')::replace('144p','💩 Low Quality')}\"||\"💩 Unknown\"]}",
        descriptionTemplate = "{stream.title::exists[\"🎬 {stream.title::title} \"||\"\"]}{stream.year::exists[\"({stream.year}) \"||\"\"]}{stream.formattedSeasons::exists[\"🍂 {stream.formattedSeasons} \"||\"\"]}{stream.formattedEpisodes::exists[\"🎞️ {stream.formattedEpisodes}\"||\"\"]}"
    )

    val MINIMALISTIC = AioTemplateDefinition(
        id = "minimalisticgdrive",
        nameTemplate = "{stream.resolution::exists[\"{stream.resolution::replace('2160p','✨ 4K')::replace('1440p','📀 2K')::replace('1080p','🧿1080p')::replace('720p','💿720p')}\"||\"N/A\"]}{service.cached::istrue[\" 🎫 \"||\"\"]}{service.cached::isfalse[\" 🎟️ \"||\"\"]}",
        descriptionTemplate = "{stream.visualTags::exists[\"🔆 {stream.visualTags::join(' • ')}  \"||\"\"]}{stream.audioTags::exists[\"🔊 {stream.audioTags::join(' • ')}\"||\"\"]}"
    )

    val TAMTARO = AioTemplateDefinition(
        id = "tamtaro",
        nameTemplate = "{stream.resolution::exists[\"{stream.resolution::replace('2160p','   4K ')::replace('1440p','    2K ')::replace('p','P')}\"||\"     \"]}{stream.type::exists[\"{stream.type::replace('debrid','    ')::replace('p2p','⁽ᵖ²ᵖ⁾')::replace('live','⁽ˡᶦᵛᵉ⁾')::replace('http','⁽ʷᵉᵇ⁾')::replace('usenet','⁽ⁿᶻᵇ⁾')::replace('stremio-usenet','⁽ⁿᶻᵇ⁾')::replace('info','⁽ᶦⁿᶠᵒ⁾')::replace('statistic','⁽ˢᵗᵃᵗˢ⁾')::replace('external','⁽ᵉˣᵗ⁾')::replace('error','⁽ᵉʳʳᵒʳ⁾')::replace('youtube','⁽ʸᵗ⁾')}\"||\"\"]{service.cached::istrue[\"⚡\"||\"\"]}{service.cached::isfalse[\"⏳\"||\"\"]}{stream.quality::exists[\"\\n  〈{stream.quality::title::replace('Bluray Remux','Remux')::replace('Web-dl','Web-dl')::replace('Hc Hd-rip','HC HDRip')::replace('Hdrip','HDRip')}〉\"||\"\"]}{stream.message::~Download[\"{tools.removeLine}\\n\"||\"\"]}{stream.nSeScore::exists[\"\\n  {stream.nSeScore::star::replace('⯪','☆')}\"||\"\"]}{stream.message::~Download[\"{tools.removeLine}\\n\"||\"\"]}",
        descriptionTemplate = "{stream.title::exists::and::stream.library::isfalse[\"✎  {stream.title::title::truncate(15)}\"||\"\"]}{stream.title::exists::and::stream.library::istrue[\"☁︎  {stream.title::title::truncate(15)} \"||\"\"]}{stream.year::exists::and::stream.episodes::exists::isfalse::and::stream.seasons::exists::isfalse[\" ({stream.year})\"||\"\"]}{stream.seasonEpisode::exists[\"  {stream.seasonEpisode::join('·')::replace('E','ᴇ')::replace('S','s')}\"||\"\"]}\n{stream.audioTags::exists[\"♬  {stream.audioTags::lsort::join(' · ')}  \"||\"\"]}{stream.audioChannels::exists[\"♯  {stream.audioChannels::join(' · ')} \"||\"\"]}\n{stream.size::>0[\"◈  {stream.size::sbytes}\"||\"\"]}{stream.message::~Download[\"{tools.removeLine}\"||\"\"]}"
    )

    private val all = listOf(
        TORRENTIO,
        TORBOX,
        GDRIVE,
        LIGHT_GDRIVE,
        UNIVERSAL,
        PRISM,
        MINIMALISTIC,
        TAMTARO
    ).associateBy { it.id }

    fun byId(id: String): AioTemplateDefinition = all[id] ?: UNIVERSAL
    fun all(): List<AioTemplateDefinition> = all.values.toList()
}

object AioUniformFormatter {
    fun render(
        stream: Stream,
        parsed: ParsedStreamInfo,
        requestContext: StreamRequestContext = StreamRequestContext(),
        selection: AioFormatterSelection = AioFormatterSelection()
    ): AioUniformPresentation {
        val definition = resolveTemplate(selection)
        val formatter = AioTemplateFormatter(
            nameTemplate = definition.nameTemplate,
            descriptionTemplate = definition.descriptionTemplate
        )
        val formatted = formatter.format(
            AioParseValueFactory.from(stream, parsed, requestContext)
        )
        return AioUniformPresentation(
            title = formatted.name.trim(),
            detailLines = formatted.description.lines().map { it.trim() }.filter { it.isNotEmpty() }
        )
    }

    private fun resolveTemplate(selection: AioFormatterSelection): AioTemplateDefinition {
        val selectedId = selection.selectedTemplateId.trim().ifBlank { AioBuiltInFormatters.DEFAULT_TEMPLATE_ID }
        if (selectedId == "custom") {
            val customTemplate = selection.customTemplate
            if (customTemplate != null &&
                !customTemplate.nameTemplate.isNullOrBlank() &&
                !customTemplate.descriptionTemplate.isNullOrBlank()
            ) {
                return AioTemplateDefinition(
                    id = "custom",
                    nameTemplate = customTemplate.nameTemplate.orEmpty(),
                    descriptionTemplate = customTemplate.descriptionTemplate.orEmpty()
                )
            }
        }
        return AioBuiltInFormatters.byId(selectedId)
    }
}

object AioParseValueFactory {
    fun from(
        stream: Stream,
        parsed: ParsedStreamInfo,
        requestContext: StreamRequestContext = StreamRequestContext()
    ): AioParseValue {
        val seasons = parsed.seasons.ifEmpty { emptyList() }
        val episodes = parsed.episodes.ifEmpty { emptyList() }
        val formattedSeasons = seasons.takeIf { it.isNotEmpty() }?.let { formatSeasonRange(it) }
        val formattedEpisodes = episodes.takeIf { it.isNotEmpty() }?.let { formatEpisodeRange(it) }
        val seasonEpisode = buildList {
            formattedSeasons?.let { add(it) }
            formattedEpisodes?.let { add(it) }
        }.ifEmpty { null }
        val preservedMetadata = parsed.preservedMetadata
        val resolvedBingeGroup = preservedMetadata.bingeGroup ?: StreamBingeGroupResolver.buildFallback(stream, parsed)
        val effectiveFilename = preservedMetadata.filename ?: parsed.filename
        val effectiveSize = preservedMetadata.videoSize ?: parsed.sizeBytes
        val languages = parsed.languages.ifEmpty { null }
        val languageCodes = languages?.map { languageToCode(it) ?: it.uppercase(Locale.US) }?.distinct()
        val languageEmojis = languages?.map { languageToEmoji(it) ?: it }?.distinct()
        val smallLanguageCodes = languageCodes?.map { makeSmallCaps(it) }
        val extension = effectiveFilename?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() }
        val serviceId = parsed.serviceId
            ?: deriveServiceId(stream)
        val type = when {
            serviceId != null -> "debrid"
            !stream.infoHash.isNullOrBlank() -> "p2p"
            !stream.ytId.isNullOrBlank() -> "youtube"
            stream.isExternal() -> "external"
            !stream.url.isNullOrBlank() -> "http"
            else -> null
        }
        val serviceName = when (serviceId) {
            "RD" -> "Real-Debrid"
            "PM" -> "Premiumize"
            "AD" -> "AllDebrid"
            "DL" -> "Debrid-Link"
            "TB" -> "TorBox"
            "ED" -> "EasyDebrid"
            "PK" -> "PikPak"
            else -> serviceId
        }
        val metadataRuntimeMinutes = requestContext.runtimeMinutes?.takeIf { it > 0 }
        val effectiveDurationMs = parsed.durationMs?.takeIf { it > 0L }
            ?: metadataRuntimeMinutes?.let { it * 60_000L }
        val debugPayload = buildDebugPayload(
            stream = stream,
            parsed = parsed,
            effectiveFilename = effectiveFilename,
            effectiveSize = effectiveSize
        )
        val streamModel = AioStreamTemplateModel(
            filename = effectiveFilename,
            folderName = parsed.folderName,
            size = effectiveSize,
            bitrate = parsed.bitrate,
            folderSize = parsed.folderSizeBytes,
            library = false,
            quality = parsed.quality,
            resolution = parsed.resolution,
            subbed = parsed.subbed,
            dubbed = parsed.dubbed,
            languages = languages,
            uLanguages = languages,
            subtitles = parsed.subtitles.ifEmpty { null },
            uSubtitles = parsed.subtitles.ifEmpty { null },
            languageEmojis = languageEmojis,
            uLanguageEmojis = languageEmojis,
            subtitleEmojis = parsed.subtitles.ifEmpty { null }?.map { languageToEmoji(it) ?: it }?.distinct(),
            uSubtitleEmojis = parsed.subtitles.ifEmpty { null }?.map { languageToEmoji(it) ?: it }?.distinct(),
            languageCodes = languageCodes,
            uLanguageCodes = languageCodes,
            subtitleCodes = parsed.subtitles.ifEmpty { null }?.map { languageToCode(it) ?: it.uppercase(Locale.US) }?.distinct(),
            uSubtitleCodes = parsed.subtitles.ifEmpty { null }?.map { languageToCode(it) ?: it.uppercase(Locale.US) }?.distinct(),
            smallLanguageCodes = smallLanguageCodes,
            uSmallLanguageCodes = smallLanguageCodes,
            smallSubtitleCodes = parsed.subtitles.ifEmpty { null }?.map { languageToCode(it) ?: it.uppercase(Locale.US) }?.distinct()?.map { makeSmallCaps(it) },
            uSmallSubtitleCodes = parsed.subtitles.ifEmpty { null }?.map { languageToCode(it) ?: it.uppercase(Locale.US) }?.distinct()?.map { makeSmallCaps(it) },
            wedontknowwhatakilometeris = languageEmojis?.map { it.replace("🇬🇧", "🇺🇸🦅") },
            uWedontknowwhatakilometeris = languageEmojis?.map { it.replace("🇬🇧", "🇺🇸🦅") },
            visualTags = parsed.visualTags.ifEmpty { null },
            audioTags = parsed.audioTags.ifEmpty { null },
            releaseGroup = parsed.releaseGroup,
            regexMatched = null,
            rankedRegexMatched = emptyList(),
            regexScore = null,
            nRegexScore = null,
            encode = parsed.encode,
            audioChannels = parsed.audioChannels.ifEmpty { null },
            edition = parsed.editions.firstOrNull(),
            editions = parsed.editions.ifEmpty { null },
            remastered = null,
            regraded = parsed.regraded,
            repack = parsed.repack,
            uncensored = parsed.uncensored,
            unrated = parsed.unrated,
            upscaled = parsed.upscaled,
            network = parsed.network,
            container = parsed.container ?: extension,
            extension = parsed.extension ?: extension,
            indexer = parsed.indexer,
            year = parsed.year,
            title = parsed.title,
            date = parsed.date,
            folderSeasons = null,
            formattedFolderSeasons = null,
            seasons = seasons.ifEmpty { null },
            season = seasons.firstOrNull(),
            formattedSeasons = formattedSeasons,
            episodes = episodes.ifEmpty { null },
            episode = episodes.firstOrNull(),
            formattedEpisodes = formattedEpisodes,
            folderEpisodes = null,
            formattedFolderEpisodes = null,
            seasonEpisode = seasonEpisode,
            seasonPack = parsed.seasonPack,
            seeders = parsed.seeders,
            private = parsed.isPrivate,
            freeleech = null,
            age = parsed.age,
            ageHours = parsed.ageHours,
            duration = effectiveDurationMs,
            infoHash = stream.infoHash,
            videoHash = preservedMetadata.videoHash,
            type = type,
            bingeGroup = resolvedBingeGroup,
            message = parsed.message ?: if (stream.description == effectiveFilename) null else stream.description,
            proxied = false,
            seadex = false,
            seadexBest = false,
            seScore = null,
            nSeScore = null,
            seMatched = null,
            rseMatched = emptyList()
        )
        return AioParseValue(
            config = AioConfigTemplateModel(addonName = stream.addonName),
            stream = streamModel,
            metadata = AioMetadataTemplateModel(
                queryType = requestContext.contentType,
                title = requestContext.title,
                runtime = metadataRuntimeMinutes,
                genres = null,
                year = requestContext.year?.toIntOrNull(),
                episodeRuntime = metadataRuntimeMinutes
            ),
            service = AioServiceTemplateModel(
                id = serviceId,
                shortName = serviceId,
                name = serviceName,
                cached = parsed.isCached
            ),
            addon = AioAddonTemplateModel(
                name = stream.addonName,
                presetId = stream.addonParserPreset.name,
                manifestUrl = null
            ),
            debug = AioDebugTemplateModel(
                json = serializeDebugPayload(debugPayload, pretty = false),
                jsonf = serializeDebugPayload(debugPayload, pretty = true)
            )
        )
    }

    private fun buildDebugPayload(
        stream: Stream,
        parsed: ParsedStreamInfo,
        effectiveFilename: String?,
        effectiveSize: Long?
    ): Map<String, Any?> {
        return linkedMapOf(
            "addonName" to stream.addonName,
            "transportKind" to parsed.transportKind.name,
            "serviceId" to parsed.serviceId,
            "isCached" to parsed.isCached,
            "filename" to effectiveFilename,
            "title" to parsed.title,
            "year" to parsed.year,
            "size" to effectiveSize,
            "videoHash" to parsed.preservedMetadata.videoHash,
            "bingeGroup" to (parsed.preservedMetadata.bingeGroup ?: StreamBingeGroupResolver.buildFallback(stream, parsed)),
            "resolution" to parsed.resolution,
            "quality" to parsed.quality,
            "encode" to parsed.encode,
            "releaseGroup" to parsed.releaseGroup,
            "visualTags" to parsed.visualTags,
            "audioTags" to parsed.audioTags,
            "audioChannels" to parsed.audioChannels,
            "languages" to parsed.languages,
            "seasons" to parsed.seasons,
            "episodes" to parsed.episodes
        )
    }

    private fun serializeDebugPayload(value: Any?, pretty: Boolean, indentLevel: Int = 0): String {
        return when (value) {
            null -> "null"
            is String -> "\"${escapeJson(value)}\""
            is Number, is Boolean -> value.toString()
            is Map<*, *> -> {
                val entries = value.entries.mapNotNull { (key, mapValue) ->
                    (key as? String)?.let { it to mapValue }
                }
                if (entries.isEmpty()) "{}"
                else if (!pretty) {
                    entries.joinToString(prefix = "{", postfix = "}") { (key, mapValue) ->
                        "\"${escapeJson(key)}\":${serializeDebugPayload(mapValue, pretty, indentLevel + 1)}"
                    }
                } else {
                    val childIndent = "  ".repeat(indentLevel + 1)
                    val currentIndent = "  ".repeat(indentLevel)
                    entries.joinToString(prefix = "{\n", postfix = "\n$currentIndent}") { (key, mapValue) ->
                        "$childIndent\"${escapeJson(key)}\": ${serializeDebugPayload(mapValue, pretty, indentLevel + 1)}"
                    }
                }
            }
            is Iterable<*> -> {
                val items = value.toList()
                if (items.isEmpty()) "[]"
                else if (!pretty) {
                    items.joinToString(prefix = "[", postfix = "]") { item ->
                        serializeDebugPayload(item, pretty, indentLevel + 1)
                    }
                } else {
                    val childIndent = "  ".repeat(indentLevel + 1)
                    val currentIndent = "  ".repeat(indentLevel)
                    items.joinToString(prefix = "[\n", postfix = "\n$currentIndent]") { item ->
                        "$childIndent${serializeDebugPayload(item, pretty, indentLevel + 1)}"
                    }
                }
            }
            else -> "\"${escapeJson(value.toString())}\""
        }
    }

    private fun escapeJson(value: String): String {
        return buildString(value.length + 8) {
            value.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(char)
                }
            }
        }
    }

    private fun formatSeasonRange(values: List<Int>): String {
        return if (values.size == 1) "S${values.first().toString().padStart(2, '0')}"
        else "S${values.first().toString().padStart(2, '0')}-S${values.last().toString().padStart(2, '0')}"
    }

    private fun formatEpisodeRange(values: List<Int>): String {
        return if (values.size == 1) "E${values.first().toString().padStart(2, '0')}"
        else "E${values.first().toString().padStart(2, '0')}-E${values.last().toString().padStart(2, '0')}"
    }

    private fun languageToEmoji(language: String): String? {
        return when (language.lowercase(Locale.US)) {
            "multi" -> "🌎"
            "english" -> "🇬🇧"
            "spanish" -> "🇪🇸"
            "french" -> "🇫🇷"
            "german" -> "🇩🇪"
            "italian" -> "🇮🇹"
            "japanese" -> "🇯🇵"
            "dutch" -> "🇳🇱"
            else -> null
        }
    }

    private fun languageToCode(language: String): String? {
        return when (language.lowercase(Locale.US)) {
            "multi" -> "MULTI"
            "english" -> "EN"
            "spanish" -> "ES"
            "french" -> "FR"
            "german" -> "DE"
            "italian" -> "IT"
            "japanese" -> "JA"
            "dutch" -> "NL"
            "dual audio" -> "DUAL"
            else -> null
        }
    }

    private fun makeSmallCaps(input: String): String {
        val map = mapOf(
            'A' to 'ᴀ', 'B' to 'ʙ', 'C' to 'ᴄ', 'D' to 'ᴅ', 'E' to 'ᴇ', 'F' to 'ꜰ', 'G' to 'ɢ',
            'H' to 'ʜ', 'I' to 'ɪ', 'J' to 'ᴊ', 'K' to 'ᴋ', 'L' to 'ʟ', 'M' to 'ᴍ', 'N' to 'ɴ',
            'O' to 'ᴏ', 'P' to 'ᴘ', 'Q' to 'ꞯ', 'R' to 'ʀ', 'S' to 'ꜱ', 'T' to 'ᴛ', 'U' to 'ᴜ',
            'V' to 'ᴠ', 'W' to 'ᴡ', 'X' to 'х', 'Y' to 'ʏ', 'Z' to 'ᴢ'
        )
        return input.map { map[it.uppercaseChar()] ?: it }.joinToString("")
    }

    private fun deriveServiceId(stream: Stream): String? {
        val lowered = listOfNotNull(stream.name, stream.description, stream.addonName)
            .joinToString(" ")
            .lowercase(Locale.US)
        return when {
            lowered.contains("rd+") -> "RD"
            lowered.contains("pm+") -> "PM"
            Regex("""(?i)(?:^|[\s\[\(\|])rd(?:$|[\s\]\)\|])""").containsMatchIn(lowered) ||
                lowered.contains("realdebrid") || lowered.contains("real-debrid") -> "RD"
            Regex("""(?i)(?:^|[\s\[\(\|])pm(?:$|[\s\]\)\|])""").containsMatchIn(lowered) ||
                lowered.contains("premiumize") -> "PM"
            lowered.contains("alldebrid") || lowered.contains("all-debrid") -> "AD"
            lowered.contains("debridlink") || lowered.contains("debrid-link") || lowered.contains("dlink") -> "DL"
            lowered.contains("torbox") -> "TB"
            lowered.contains("easydebrid") || lowered.contains("easy-debrid") -> "ED"
            lowered.contains("pikpak") -> "PK"
            else -> null
        }
    }
}
