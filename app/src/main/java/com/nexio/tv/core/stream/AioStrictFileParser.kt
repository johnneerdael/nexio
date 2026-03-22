package com.nexio.tv.core.stream

import java.util.Locale

data class AioStrictParsedFile(
    val resolution: String? = null,
    val quality: String? = null,
    val languages: List<String> = emptyList(),
    val subtitles: List<String> = emptyList(),
    val encode: String? = null,
    val audioChannels: List<String> = emptyList(),
    val audioTags: List<String> = emptyList(),
    val visualTags: List<String> = emptyList(),
    val releaseGroup: String? = null,
    val title: String? = null,
    val year: String? = null,
    val subbed: Boolean = false,
    val dubbed: Boolean = false,
    val editions: List<String> = emptyList(),
    val regraded: Boolean = false,
    val repack: Boolean = false,
    val uncensored: Boolean = false,
    val unrated: Boolean = false,
    val upscaled: Boolean = false,
    val network: String? = null,
    val container: String? = null,
    val extension: String? = null,
    val seasons: List<Int> = emptyList(),
    val volumes: List<Int> = emptyList(),
    val episodes: List<Int> = emptyList(),
    val date: String? = null,
    val seasonPack: Boolean = false
)

object AioStrictFileParser {
    private val resolutionOrder = listOf("2160p", "1440p", "1080p", "720p", "576p", "480p", "360p", "240p", "144p")

    private fun createRegex(pattern: String): Regex =
        Regex("""(?<![^\s\[(_\-.,])($pattern)(?=[\s)\]_.\-,]|$)""", RegexOption.IGNORE_CASE)

    private fun createLanguageRegex(pattern: String): Regex =
        createRegex("""$pattern(?![ .\-_]?sub(title)?s?)""")

    private val resolutionPatterns = linkedMapOf(
        "2160p" to createRegex("""(bd|hd|m)?(4k|2160(p|i)?)|u(ltra)?[ .\-_]?hd|3840\s?x\s?(\d{4})"""),
        "1440p" to createRegex("""(bd|hd|m)?(1440(p|i)?)|2k|w?q(uad)?[ .\-_]?hd|2560\s?x(\d{4})"""),
        "1080p" to createRegex("""(bd|hd|m)?(1080(p|i)?)|f(ull)?[ .\-_]?hd|1920\s?x\s?(\d{3,4})"""),
        "720p" to createRegex("""(bd|hd|m)?((720|800)(p|i)?)|hd|1280\s?x\s?(\d{3,4})"""),
        "576p" to createRegex("""(bd|hd|m)?((576|534)(p|i)?)"""),
        "480p" to createRegex("""(bd|hd|m)?(480(p|i)?)|sd"""),
        "360p" to createRegex("""(bd|hd|m)?(360(p|i)?)"""),
        "240p" to createRegex("""(bd|hd|m)?(240(p|i)?)"""),
        "144p" to createRegex("""(bd|hd|m)?(144(p|i)?)""")
    )

    private val qualityPatterns = linkedMapOf(
        "BluRay REMUX" to createRegex("""(bd|br|b|uhd)?remux"""),
        "BluRay" to createRegex("""bd|blu[ .\-_]?ray|(bd|br)[ .\-_]?rip"""),
        "WEB-DL" to createRegex("""web[ .\-_]?(dl)?(?![ .\-_]?(rip|DLRip|cam))"""),
        "WEBRip" to createRegex("""web[ .\-_]?rip"""),
        "HDRip" to createRegex("""hd[ .\-_]?rip|web[ .\-_]?dl[ .\-_]?rip"""),
        "HC HD-Rip" to createRegex("""hc|hd[ .\-_]?rip"""),
        "DVDRip" to createRegex("""dvd[ .\-_]?(rip|mux|r|full|5|9)?"""),
        "HDTV" to createRegex("""(hd|pd)tv|tv[ .\-_]?rip|hdtv[ .\-_]?rip|dsr(ip)?|sat[ .\-_]?rip"""),
        "CAM" to createRegex("""cam|hdcam|cam[ .\-_]?rip"""),
        "TS" to createRegex("""telesync|ts|hd[ .\-_]?ts|pdvd|predvd(rip)?"""),
        "TC" to createRegex("""telecine|tc|hd[ .\-_]?tc"""),
        "SCR" to createRegex("""((dvd|bd|web|hd)?[ .\-_]?)?(scr(eener)?)""")
    )

    private val visualTagPatterns = linkedMapOf(
        "10bit" to createRegex("""10[ .\-_]?bit|hi10p?"""),
        "HDR10+" to createRegex("""hdr[ .\-_]?10[ .\-_]?(p(lus)?|[+])"""),
        "HDR10" to createRegex("""hdr[ .\-_]?10(?![ .\-_]?(?:\+|p(lus)?))"""),
        "HDR" to createRegex("""hdr(?![ .\-_]?10)(?![ .\-_]?(?:\+|p(lus)?))"""),
        "HLG" to createRegex("""hlg"""),
        "DV" to createRegex("""do?(lby)?[ .\-_]?vi?(sion)?(?:[ .\-_]?atmos)?|dv"""),
        "3D" to createRegex("""(bd)?(3|three)[ .\-_]?(d(imension)?(al)?)"""),
        "IMAX" to createRegex("""imax"""),
        "AI" to createRegex("""ai|(ai)?(upscal(ed?|ing)|enhanced?|re[ .\-_]?graded?)"""),
        "SDR" to createRegex("""sdr"""),
        "H-OU" to createRegex("""h?(alf)?[ .\-_]?(ou|over[ .\-_]?under)"""),
        "H-SBS" to createRegex("""h?(alf)?[ .\-_]?(sbs|side[ .\-_]?by[ .\-_]?side)""")
    )

    private val audioTagPatterns = linkedMapOf(
        "Atmos" to createRegex("""atmos|ddpa\d?"""),
        "DD+" to createRegex("""(d(olby)?[ .\-_]?d(igital)?[ .\-_]?((p(lus)?|\+)a?)(?:[ .\-_]?(2[ .\-_]?0|5[ .\-_]?1|7[ .\-_]?1))?)|e[ .\-_]?ac[ .\-_]?3"""),
        "DD" to createRegex("""(d(olby)?[ .\-_]?d(igital)?(?:[ .\-_]?(5[ .\-_]?1|7[ .\-_]?1|2[ .\-_]?0?))?)|(?<!e[ .\-_]?)ac[ .\-_]?3"""),
        "DTS:X" to createRegex("""dts[ .\-:_]?x"""),
        "DTS-HD MA" to createRegex("""dts[ .\-_]?hd[ .\-_]?ma"""),
        "DTS-HD" to createRegex("""dts[ .\-_]?hd(?![ .\-_]?ma)"""),
        "DTS-ES" to createRegex("""dts[ .\-_]?es"""),
        "DTS" to createRegex("""dts(?![ .\-:_]?(x(?=[\s)\]_.\-,]|$)|hd[ .\-_]?(ma)?|es))"""),
        "TrueHD" to createRegex("""true[ .\-_]?hd"""),
        "OPUS" to createRegex("""opus"""),
        "AAC" to createRegex("""q?aac(?:[ .\-_]?2)?"""),
        "FLAC" to createRegex("""flac(?:[ .\-_]?(lossless|2\.0|x[2-4]))?""")
    )

    private val audioChannelPatterns = linkedMapOf(
        "2.0" to createRegex("""(d(olby)?[ .\-_]?d(igital)?)?2[ .\-_]?0(ch)?"""),
        "5.1" to createRegex("""(d(olby)?[ .\-_]?d(igital)?[ .\-_]?((p(lus)?|\+)a?)?)?5[ .\-_]?1(ch)?"""),
        "6.1" to createRegex("""(d(olby)?[ .\-_]?d(igital)?[ .\-_]?((p(lus)?|\+)a?)?)?6[ .\-_]?1(ch)?"""),
        "7.1" to createRegex("""(d(olby)?[ .\-_]?d(igital)?[ .\-_]?((p(lus)?|\+)a?)?)?7[ .\-_]?1(ch)?""")
    )

    private val encodePatterns = linkedMapOf(
        "HEVC" to createRegex("""hevc[ .\-_]?(10)?|[xh][ .\-_]?265"""),
        "AVC" to createRegex("""avc|[xh][ .\-_]?264"""),
        "AV1" to createRegex("""av1"""),
        "XviD" to createRegex("""xvid"""),
        "DivX" to createRegex("""divx|dvix""")
    )

    private val languagePatterns = linkedMapOf(
        "Multi" to createLanguageRegex("""multi"""),
        "Dual Audio" to createLanguageRegex("""dual[ .\-_]?(audio|lang(uage)?|flac|ac3|aac2?)"""),
        "Dubbed" to createLanguageRegex("""dub(s|bed|bing)?"""),
        "English" to createLanguageRegex("""english|eng"""),
        "Japanese" to createLanguageRegex("""japanese|jap|jpn"""),
        "Chinese" to createLanguageRegex("""chinese|chi"""),
        "Russian" to createLanguageRegex("""russian|rus"""),
        "Arabic" to createLanguageRegex("""arabic|ara"""),
        "Portuguese" to createLanguageRegex("""portuguese"""),
        "Spanish" to createLanguageRegex("""spanish|spa|esp"""),
        "French" to createLanguageRegex("""french|fra|fr|vf|vff|vfi|vf2|vfq|truefrench"""),
        "German" to createLanguageRegex("""deu(tsch)?(land)?|ger(man)?"""),
        "Italian" to createLanguageRegex("""italian|ita"""),
        "Korean" to createLanguageRegex("""korean|kor"""),
        "Hindi" to createLanguageRegex("""hindi|hin"""),
        "Bengali" to createLanguageRegex("""bengali|ben(?![ .\-_]?the[ .\-_]?men)"""),
        "Punjabi" to createLanguageRegex("""punjabi|pan"""),
        "Marathi" to createLanguageRegex("""marathi|mar"""),
        "Gujarati" to createLanguageRegex("""gujarati|guj"""),
        "Tamil" to createLanguageRegex("""tamil|tam"""),
        "Telugu" to createLanguageRegex("""telugu|tel"""),
        "Kannada" to createLanguageRegex("""kannada|kan"""),
        "Malayalam" to createLanguageRegex("""malayalam|mal"""),
        "Thai" to createLanguageRegex("""thai|tha"""),
        "Vietnamese" to createLanguageRegex("""vietnamese|vie"""),
        "Indonesian" to createLanguageRegex("""indonesian|ind"""),
        "Turkish" to createLanguageRegex("""turkish|tur"""),
        "Hebrew" to createLanguageRegex("""hebrew|heb"""),
        "Persian" to createLanguageRegex("""persian|per"""),
        "Ukrainian" to createLanguageRegex("""ukrainian|ukr"""),
        "Greek" to createLanguageRegex("""greek|ell"""),
        "Lithuanian" to createLanguageRegex("""lithuanian|lit"""),
        "Latvian" to createLanguageRegex("""latvian|lav"""),
        "Estonian" to createLanguageRegex("""estonian|est"""),
        "Polish" to createLanguageRegex("""polish|pol"""),
        "Czech" to createLanguageRegex("""czech|cze"""),
        "Slovak" to createLanguageRegex("""slovak|slo"""),
        "Hungarian" to createLanguageRegex("""hungarian|hun"""),
        "Romanian" to createLanguageRegex("""romanian|rum"""),
        "Bulgarian" to createLanguageRegex("""bulgarian|bul"""),
        "Serbian" to createLanguageRegex("""serbian|srp"""),
        "Croatian" to createLanguageRegex("""croatian|hrv"""),
        "Slovenian" to createLanguageRegex("""slovenian|slv"""),
        "Dutch" to createLanguageRegex("""dutch|dut"""),
        "Danish" to createLanguageRegex("""danish|dan"""),
        "Finnish" to createLanguageRegex("""finnish|fin"""),
        "Swedish" to createLanguageRegex("""swedish|swe"""),
        "Norwegian" to createLanguageRegex("""norwegian|nor"""),
        "Malay" to createLanguageRegex("""malay"""),
        "Latino" to createLanguageRegex("""latino|lat""")
    )

    private val releaseGroupRegex = Regex(
        """-[. ]?(?!\d+$|S\d+|\d+x|ep?\d+|[^\[]+]$)([^\-. \[]+[^\-. \[\)\]\d][^\-. \[\)\]]*)(?:\[[\w.-]+])?(?=\)|[.-]+\w{2,4}$|$)""",
        RegexOption.IGNORE_CASE
    )
    private val yearRegex = Regex("""\b(19|20)\d{2}\b""")
    private val seasonEpisodeRegex = Regex("""(?i)\bS(\d{1,2})(?:E(\d{1,2}))?\b""")
    private val altEpisodeRegex = Regex("""(?i)\b(\d{1,2})x(\d{1,2})\b""")
    private val seasonOnlyRegex = Regex("""(?i)\bS(\d{1,2})\b""")
    private val extensionRegex = Regex("""\.([A-Za-z0-9]{2,5})$""")

    fun parse(filename: String): AioStrictParsedFile {
        val parsedTitle = deriveTitle(filename)
        val year = yearRegex.find(filename)?.value
        val filenameForMatching = stripParsedTitle(filename, parsedTitle)
        val normalizedResolution = normaliseResolution(
            matchPattern(filenameForMatching, resolutionPatterns)
                ?: year?.let { null }
        ) ?: matchPattern(filenameForMatching, resolutionPatterns)
        val quality = matchPattern(filenameForMatching, qualityPatterns)
        val encode = matchPattern(filenameForMatching, encodePatterns)
        val audioChannels = matchMultiplePatterns(filenameForMatching, audioChannelPatterns)
        val visualTags = matchMultiplePatterns(filenameForMatching, visualTagPatterns)
        val audioTags = matchMultiplePatterns(filenameForMatching, audioTagPatterns)
        val languages = matchMultiplePatterns(filenameForMatching, languagePatterns).distinct()
        val baseName = filename.substringBeforeLast('.', filename)
        val releaseGroup = releaseGroupRegex.find(baseName)?.groupValues?.getOrNull(1)
        val extension = extensionRegex.find(filename)?.groupValues?.getOrNull(1)
        val tokenized = filename.substringBeforeLast('.', filename)
            .split(Regex("""[.\s_\-]+"""))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val seasons = mutableListOf<Int>()
        val episodes = mutableListOf<Int>()
        tokenized.firstNotNullOfOrNull { token ->
            seasonEpisodeRegex.matchEntire(token)?.also { match ->
                match.groupValues.getOrNull(1)?.toIntOrNull()?.let { seasons += it }
                match.groupValues.getOrNull(2)?.toIntOrNull()?.let { episodes += it }
            }
        } ?: tokenized.firstNotNullOfOrNull { token ->
            altEpisodeRegex.matchEntire(token)?.also { match ->
                match.groupValues.getOrNull(1)?.toIntOrNull()?.let { seasons += it }
                match.groupValues.getOrNull(2)?.toIntOrNull()?.let { episodes += it }
            }
        } ?: tokenized.firstNotNullOfOrNull { token ->
            seasonOnlyRegex.matchEntire(token)?.also { match ->
                match.groupValues.getOrNull(1)?.toIntOrNull()?.let { seasons += it }
            }
        }

        return AioStrictParsedFile(
            resolution = normalizedResolution,
            quality = quality,
            languages = languages,
            subtitles = emptyList(),
            encode = encode,
            audioChannels = audioChannels,
            audioTags = audioTags,
            visualTags = visualTags,
            releaseGroup = releaseGroup,
            title = parsedTitle,
            year = year,
            container = extension,
            extension = extension,
            seasons = seasons,
            volumes = emptyList(),
            episodes = episodes,
            seasonPack = seasons.isNotEmpty() && episodes.isEmpty()
        )
    }

    private fun stripParsedTitle(filename: String, parsedTitle: String?): String {
        if (parsedTitle.isNullOrBlank() || parsedTitle.length <= 4) return filename
        val escapedTitle = Regex.escape(parsedTitle).replace("""\ """, """[._ ]""")
        val titleRegex = Regex(escapedTitle, RegexOption.IGNORE_CASE)
        return filename.replace(titleRegex, "").trim().replace(Regex("""\s+"""), ".").trim('.')
    }

    private fun matchPattern(filename: String, patterns: Map<String, Regex>): String? {
        return patterns.entries.firstOrNull { (_, pattern) -> pattern.containsMatchIn(filename) }?.key
    }

    private fun matchMultiplePatterns(filename: String, patterns: Map<String, Regex>): List<String> {
        return patterns.entries
            .filter { (_, pattern) -> pattern.containsMatchIn(filename) }
            .map { it.key }
    }

    private fun normaliseResolution(resolution: String?): String? {
        if (resolution == null) return null
        val lower = resolution.lowercase(Locale.US)
        if (lower == "4k") return "2160p"
        if (resolutionOrder.contains(lower)) return lower
        val match = Regex("""^(\d+)p$""").find(lower) ?: return null
        val numeric = match.groupValues[1].toIntOrNull() ?: return null
        return resolutionOrder.minByOrNull { candidate ->
            val candidateNumeric = candidate.removeSuffix("p").toIntOrNull() ?: Int.MAX_VALUE
            kotlin.math.abs(candidateNumeric - numeric)
        }
    }

    private fun deriveTitle(raw: String): String? {
        val withoutExtension = raw.substringBeforeLast('.', raw)
        val tokens = withoutExtension
            .replace(Regex("""[\[\](){}]"""), " ")
            .split(Regex("""[.\s_\-]+"""))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null
        val stopIndex = tokens.indexOfFirst { token -> isBoundaryToken(token) }
            .let { if (it == -1) tokens.size else it }
        val titleTokens = tokens.take(stopIndex).ifEmpty { tokens.take(1) }
        return titleTokens.joinToString(" ") { token ->
            token.lowercase(Locale.US).replaceFirstChar { ch ->
                if (ch.isLowerCase()) ch.titlecase(Locale.US) else ch.toString()
            }
        }.takeIf { it.isNotBlank() }
    }

    private fun isBoundaryToken(token: String): Boolean {
        val normalized = token.trim().trim('(', ')', '[', ']', '{', '}')
        if (normalized.isBlank()) return false
        if (yearRegex.matches(normalized)) return true
        if (seasonEpisodeRegex.matches(normalized)) return true
        if (seasonOnlyRegex.matches(normalized)) return true
        val text = " $normalized "
        return resolutionPatterns.values.any { it.containsMatchIn(text) } ||
            qualityPatterns.values.any { it.containsMatchIn(text) } ||
            encodePatterns.values.any { it.containsMatchIn(text) } ||
            visualTagPatterns.values.any { it.containsMatchIn(text) } ||
            audioTagPatterns.values.any { it.containsMatchIn(text) } ||
            audioChannelPatterns.values.any { it.containsMatchIn(text) } ||
            languagePatterns.values.any { it.containsMatchIn(text) }
    }
}
