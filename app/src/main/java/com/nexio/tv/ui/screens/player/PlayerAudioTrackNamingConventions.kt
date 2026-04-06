package com.nexio.tv.ui.screens.player

import com.nexio.tv.data.local.AVAILABLE_SUBTITLE_LANGUAGES
import java.io.File
import java.io.InputStream
import java.util.Locale

private const val AUDIO_TRACK_NAMING_MAP_RESOURCE = "player_audio_track_naming_map.csv"
private const val AUDIO_TRACK_NAMING_MAP_REPO_FILE =
    "Audio Track Naming Conventions Map - Audio Track Naming Conventions Map.csv"

internal enum class AudioTrackType {
    ORIGINAL,
    COMMENTARY,
    DUBBED,
    DESCRIPTIVE_AUDIO,
    ISOLATED_SCORE,
    VOICEOVER
}

private data class AudioTrackNamingMap(
    val languageAliasesByCode: Map<String, Set<String>>,
    val trackTypeAliases: Map<AudioTrackType, Set<String>>
)

internal object PlayerAudioTrackNamingConventions {
    private val namingMap: AudioTrackNamingMap by lazy(::loadNamingMap)

    fun inferredLanguageCodes(track: TrackInfo): Set<String> {
        val explicitCode = track.language
            ?.takeIf { it.isNotBlank() }
            ?.let(PlayerSubtitleUtils::normalizeLanguageCode)
            ?.takeIf { it.isNotBlank() }
        val haystack = buildHaystack(track)
        val inferredCodes = namingMap.languageAliasesByCode
            .asSequence()
            .filter { (_, aliases) -> aliases.any { alias -> haystackContainsAlias(haystack, alias) } }
            .map { it.key }
            .toSet()
        return buildSet {
            if (explicitCode != null) add(explicitCode)
            addAll(inferredCodes)
        }
    }

    fun trackTypes(track: TrackInfo): Set<AudioTrackType> {
        val haystack = buildHaystack(track)
        return namingMap.trackTypeAliases
            .asSequence()
            .filter { (_, aliases) -> aliases.any { alias -> haystackContainsAlias(haystack, alias) } }
            .map { it.key }
            .toSet()
    }

    private fun loadNamingMap(): AudioTrackNamingMap {
        val stream = openNamingMapStream()
        return if (stream == null) {
            fallbackNamingMap()
        } else {
            stream.use(::parseNamingMap)
        }
    }

    private fun openNamingMapStream(): InputStream? {
        val classLoader = PlayerAudioTrackNamingConventions::class.java.classLoader
        classLoader?.getResourceAsStream(AUDIO_TRACK_NAMING_MAP_RESOURCE)?.let { return it }

        val candidates = listOf(
            File(AUDIO_TRACK_NAMING_MAP_REPO_FILE),
            File("..", AUDIO_TRACK_NAMING_MAP_REPO_FILE),
            File("../..", AUDIO_TRACK_NAMING_MAP_REPO_FILE)
        )
        return candidates.firstOrNull(File::exists)?.inputStream()
    }

    private fun parseNamingMap(inputStream: InputStream): AudioTrackNamingMap {
        val languages = linkedMapOf<String, MutableSet<String>>()
        val trackTypes = mutableMapOf<AudioTrackType, MutableSet<String>>()
        var section: CsvSection? = null

        inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val columns = parseCsvRow(line)
                if (columns.all { it.isBlank() }) {
                    return@forEach
                }
                val first = columns.firstOrNull()?.trim().orEmpty()
                when {
                    first.equals("language", ignoreCase = true) -> {
                        section = CsvSection.LANGUAGE
                        return@forEach
                    }

                    first.equals("track type", ignoreCase = true) -> {
                        section = CsvSection.TRACK_TYPE
                        return@forEach
                    }
                }

                when (section) {
                    CsvSection.LANGUAGE -> parseLanguageRow(columns, languages)
                    CsvSection.TRACK_TYPE -> parseTrackTypeRow(columns, trackTypes)
                    null -> Unit
                }
            }
        }

        return AudioTrackNamingMap(
            languageAliasesByCode = languages.mapValues { (_, aliases) -> aliases.toSet() },
            trackTypeAliases = trackTypes.mapValues { (_, aliases) -> aliases.toSet() }
        )
    }

    private fun parseLanguageRow(
        columns: List<String>,
        languages: MutableMap<String, MutableSet<String>>
    ) {
        val alpha2Codes = splitAliases(columns.getOrNull(2))
        val alpha3Codes = splitAliases(columns.getOrNull(3))
        val canonicalCode = (alpha2Codes + alpha3Codes)
            .map(PlayerSubtitleUtils::normalizeLanguageCode)
            .firstOrNull { it.isNotBlank() && !it.startsWith("(") }
            ?: return

        val aliases = linkedSetOf<String>()
        aliases.addAll(splitAliases(columns.getOrNull(0)))
        aliases.addAll(splitAliases(columns.getOrNull(1)))
        aliases.addAll(alpha2Codes)
        aliases.addAll(alpha3Codes)
        aliases.addAll(splitAliases(columns.getOrNull(4)))
        aliases.add(canonicalCode)
        aliases.add(canonicalCode.replace('-', ' '))
        aliases.add(canonicalCode.replace('-', '_'))

        languages.getOrPut(canonicalCode) { linkedSetOf() }
            .addAll(aliases.map(::normalizeAlias).filter(String::isNotBlank))
    }

    private fun parseTrackTypeRow(
        columns: List<String>,
        trackTypes: MutableMap<AudioTrackType, MutableSet<String>>
    ) {
        val type = when (columns.firstOrNull()?.trim()?.lowercase(Locale.ROOT)) {
            "original audio" -> AudioTrackType.ORIGINAL
            "commentary" -> AudioTrackType.COMMENTARY
            "dubbed" -> AudioTrackType.DUBBED
            "descriptive audio" -> AudioTrackType.DESCRIPTIVE_AUDIO
            "isolated score" -> AudioTrackType.ISOLATED_SCORE
            "voiceover" -> AudioTrackType.VOICEOVER
            else -> null
        } ?: return

        val aliases = linkedSetOf<String>()
        aliases.addAll(splitAliases(columns.getOrNull(0)))
        aliases.addAll(splitAliases(columns.getOrNull(1)))
        trackTypes.getOrPut(type) { linkedSetOf() }
            .addAll(aliases.map(::normalizeAlias).filter(String::isNotBlank))
    }

    private fun fallbackNamingMap(): AudioTrackNamingMap {
        val fallbackLanguageAliases = AVAILABLE_SUBTITLE_LANGUAGES.associate { language ->
            val aliases = linkedSetOf(
                language.code.lowercase(Locale.ROOT),
                language.name.lowercase(Locale.ROOT),
                language.name.substringBefore(" (").lowercase(Locale.ROOT)
            )
            aliases.addAll(FALLBACK_THREE_LETTER_ALIASES[language.code].orEmpty())
            language.code to aliases
        }

        val fallbackTrackTypeAliases = mapOf(
            AudioTrackType.ORIGINAL to setOf("original", "orig", "undetermined", "und", "main", "default"),
            AudioTrackType.COMMENTARY to setOf("commentary", "comm", "director's commentary", "cast commentary"),
            AudioTrackType.DUBBED to setOf("dub", "dubbed", "dual audio"),
            AudioTrackType.DESCRIPTIVE_AUDIO to
                setOf("ad", "audio description", "dvs", "visual description"),
            AudioTrackType.ISOLATED_SCORE to setOf("score", "music only", "isolated score"),
            AudioTrackType.VOICEOVER to setOf("vo", "lektor", "gavrilov")
        )

        return AudioTrackNamingMap(
            languageAliasesByCode = fallbackLanguageAliases,
            trackTypeAliases = fallbackTrackTypeAliases
        )
    }

    private fun buildHaystack(track: TrackInfo): String {
        return listOfNotNull(track.name, track.language, track.trackId, track.codec)
            .joinToString(" ")
            .lowercase(Locale.ROOT)
    }

    private fun splitAliases(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        return value.split(',')
            .map(::normalizeAlias)
            .filter(String::isNotBlank)
            .filterNot { it == "(none)" }
    }

    private fun normalizeAlias(value: String): String {
        return value.trim()
            .trim('"')
            .lowercase(Locale.ROOT)
    }

    private fun parseCsvRow(line: String): List<String> {
        val values = mutableListOf<String>()
        val current = StringBuilder()
        var insideQuotes = false
        var index = 0

        while (index < line.length) {
            val ch = line[index]
            when {
                ch == '"' && insideQuotes && index + 1 < line.length && line[index + 1] == '"' -> {
                    current.append('"')
                    index++
                }

                ch == '"' -> insideQuotes = !insideQuotes
                ch == ',' && !insideQuotes -> {
                    values += current.toString()
                    current.clear()
                }

                else -> current.append(ch)
            }
            index++
        }

        values += current.toString()
        return values
    }

    private fun haystackContainsAlias(haystack: String, alias: String): Boolean {
        val normalizedAlias = normalizeAlias(alias)
        if (normalizedAlias.isBlank()) return false
        return Regex(
            """(?i)(?:^|[\s(\[|/_-])${Regex.escape(normalizedAlias)}(?=$|[\s)\]|/_-])"""
        ).containsMatchIn(haystack)
    }

    private enum class CsvSection {
        LANGUAGE,
        TRACK_TYPE
    }
}

private val FALLBACK_THREE_LETTER_ALIASES = mapOf(
    "ar" to setOf("ara"),
    "cs" to setOf("ces", "cze"),
    "da" to setOf("dan"),
    "de" to setOf("deu", "ger"),
    "el" to setOf("ell", "gre"),
    "en" to setOf("eng"),
    "es" to setOf("spa"),
    "fi" to setOf("fin"),
    "fr" to setOf("fra", "fre"),
    "he" to setOf("heb"),
    "hi" to setOf("hin"),
    "hu" to setOf("hun"),
    "id" to setOf("ind"),
    "it" to setOf("ita"),
    "ja" to setOf("jpn"),
    "ko" to setOf("kor"),
    "ms" to setOf("may", "msa"),
    "nl" to setOf("dut", "nld"),
    "no" to setOf("nor"),
    "pl" to setOf("pol"),
    "pt" to setOf("por"),
    "pt-br" to setOf("pob"),
    "ro" to setOf("ron", "rum"),
    "ru" to setOf("rus"),
    "sv" to setOf("swe"),
    "ta" to setOf("tam"),
    "te" to setOf("tel"),
    "tr" to setOf("tur"),
    "uk" to setOf("ukr"),
    "zh" to setOf("chi", "cmn", "zho")
)
