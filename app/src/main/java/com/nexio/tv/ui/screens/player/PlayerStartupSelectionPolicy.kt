package com.nexio.tv.ui.screens.player

import android.util.Log
import com.nexio.tv.core.stream.AioStrictFileParser
import com.nexio.tv.core.stream.AioStrictParsedFile
import com.nexio.tv.data.local.SUBTITLE_LANGUAGE_FORCED
import com.nexio.tv.domain.model.Subtitle
import java.util.Locale

internal data class StartupAudioCapabilitySupport(
    val ac3Supported: Boolean = true,
    val eac3Supported: Boolean = true,
    val truehdSupported: Boolean = true,
    val dtsSupported: Boolean = true,
    val dtshdSupported: Boolean = true,
    val aacSupported: Boolean = true,
    val unknownSupported: Boolean = true
)

internal fun findBestStartupAudioTrackIndex(
    audioTracks: List<TrackInfo>,
    targets: List<String>,
    originalLanguage: String? = null,
    capabilitySupport: StartupAudioCapabilitySupport = StartupAudioCapabilitySupport()
): Int {
    for (target in targets) {
        val candidateIndexes = audioTracks.indices.filter { index ->
            val track = audioTracks[index]
            audioTrackMatchesLanguage(track, target, originalLanguage)
        }
        if (candidateIndexes.isNotEmpty()) {
            fun score(index: Int): StartupAudioTrackScore {
                return startupAudioTrackScore(
                    track = audioTracks[index],
                    target = target,
                    originalLanguage = originalLanguage,
                    capabilitySupport = capabilitySupport
                )
            }

            return candidateIndexes.maxWithOrNull(
                compareBy<Int> { score(it).supportedRank }
                    .thenBy { score(it).primaryRank }
                    .thenBy { score(it).dubbedRank }
                    .thenBy { score(it).originalRank }
                    .thenBy { score(it).codecRank }
                    .thenBy { score(it).channelRank }
                    .thenBy { -it }
            ) ?: candidateIndexes.first()
        }
    }
    return -1
}

internal fun resolveStartupAudioSelectionIndex(
    audioTracks: List<TrackInfo>,
    targets: List<String>,
    currentSelectedIndex: Int,
    originalLanguage: String? = null,
    capabilitySupport: StartupAudioCapabilitySupport = StartupAudioCapabilitySupport()
): Int {
    val bestIndex = findBestStartupAudioTrackIndex(
        audioTracks = audioTracks,
        targets = targets,
        originalLanguage = originalLanguage,
        capabilitySupport = capabilitySupport
    )
    if (bestIndex < 0) {
        return if (currentSelectedIndex in audioTracks.indices) currentSelectedIndex else -1
    }

    if (currentSelectedIndex !in audioTracks.indices) {
        return bestIndex
    }

    val currentTrack = audioTracks[currentSelectedIndex]
    return if (targets.any { target -> audioTrackMatchesLanguage(currentTrack, target, originalLanguage) } &&
        currentSelectedIndex == bestIndex
    ) {
        currentSelectedIndex
    } else {
        bestIndex
    }
}

private data class StartupAudioTrackScore(
    val supportedRank: Int,
    val primaryRank: Int,
    val dubbedRank: Int,
    val originalRank: Int,
    val codecRank: Int,
    val channelRank: Int
)

private fun startupAudioTrackScore(
    track: TrackInfo,
    target: String,
    originalLanguage: String?,
    capabilitySupport: StartupAudioCapabilitySupport
): StartupAudioTrackScore {
    val haystack = listOfNotNull(track.name, track.language, track.trackId, track.codec)
        .joinToString(" ")
        .lowercase(Locale.ROOT)
    val trackTypes = PlayerAudioTrackNamingConventions.trackTypes(track)
    val normalizedTarget = PlayerSubtitleUtils.normalizeLanguageCode(target)
    val normalizedOriginalLanguage = originalLanguage
        ?.takeIf { it.isNotBlank() }
        ?.let(PlayerSubtitleUtils::normalizeLanguageCode)
    val targetingOriginalLanguage =
        normalizedOriginalLanguage != null && normalizedTarget == normalizedOriginalLanguage

    fun containsAny(vararg terms: String): Boolean = terms.any { haystack.contains(it) }

    val primaryRank = if (trackTypes.any { it in NON_PRIMARY_AUDIO_TRACK_TYPES }) 0 else 1
    val dubbedRank = if (targetingOriginalLanguage && AudioTrackType.DUBBED in trackTypes) 0 else 1
    val originalBoost = if (
        targetingOriginalLanguage &&
        (AudioTrackType.ORIGINAL in trackTypes || containsAny("main", "default"))
    ) {
        1
    } else {
        0
    }
    val codecRank = when {
        containsAny("truehd", "true hd", "mlp") -> 5
        containsAny("dts-hd ma", "dts hd ma", "dtshd-ma") -> 4
        containsAny("dts-hd", "dts hd", "dtshd") -> 3
        containsAny("e-ac-3", "eac3", "dd+", "dolby digital plus") -> 2
        containsAny("ac-3", "ac3", "dolby digital") -> 1
        containsAny("aac") -> 0
        else -> 0
    }
    val supportedRank = when {
        containsAny("truehd", "true hd", "mlp") -> if (capabilitySupport.truehdSupported) 1 else 0
        containsAny("dts-hd ma", "dts hd ma", "dtshd-ma", "dts-hd", "dts hd", "dtshd") ->
            if (capabilitySupport.dtshdSupported) 1 else 0
        containsAny("dts") -> if (capabilitySupport.dtsSupported) 1 else 0
        containsAny("e-ac-3", "eac3", "dd+", "dolby digital plus") ->
            if (capabilitySupport.eac3Supported) 1 else 0
        containsAny("ac-3", "ac3", "dolby digital") -> if (capabilitySupport.ac3Supported) 1 else 0
        containsAny("aac") -> if (capabilitySupport.aacSupported) 1 else 0
        else -> if (capabilitySupport.unknownSupported) 1 else 0
    }
    val channelRank = track.channelCount ?: 0

    return StartupAudioTrackScore(
        supportedRank = supportedRank,
        primaryRank = primaryRank,
        dubbedRank = dubbedRank,
        originalRank = originalBoost,
        codecRank = codecRank,
        channelRank = channelRank
    )
}

internal fun audioTrackMatchesLanguage(
    track: TrackInfo,
    target: String,
    originalLanguage: String? = null
): Boolean {
    if (PlayerSubtitleUtils.matchesLanguageCode(track.language, target)) {
        return true
    }
    val normalizedTarget = PlayerSubtitleUtils.normalizeLanguageCode(target)
    val normalizedOriginalLanguage = originalLanguage
        ?.takeIf { it.isNotBlank() }
        ?.let(PlayerSubtitleUtils::normalizeLanguageCode)
    val inferredCodes = PlayerAudioTrackNamingConventions.inferredLanguageCodes(track)
    val inferredMatch = inferredCodes.any { code ->
        code == normalizedTarget ||
            code.startsWith("$normalizedTarget-") ||
            code.startsWith("${normalizedTarget}_")
    }
    if (inferredMatch) {
        return true
    }
    // If we successfully inferred a concrete language from the track's name
    // and it is not the target, do not fall through to the "undetermined
    // original language" heuristic — the track is clearly a different
    // language (e.g. an Italian track in a file whose original language is
    // English). Ignore "und"/blank explicit codes so actual undetermined
    // tracks still pass through to the original-language check below.
    val nameInferredCodes = inferredCodes.filterNot { isUndeterminedAudioTrackLanguage(it) }
    if (nameInferredCodes.isNotEmpty()) {
        return false
    }

    return normalizedOriginalLanguage != null &&
        normalizedTarget == normalizedOriginalLanguage &&
        isUndeterminedAudioTrackLanguage(track.language) &&
        isLikelyOriginalLanguageTrack(track)
}

internal sealed interface StartupSubtitleAutoSelectionDecision {
    data object None : StartupSubtitleAutoSelectionDecision
    data object DeferAddonFallback : StartupSubtitleAutoSelectionDecision
    data class Internal(
        val index: Int,
        val enableAiTranslation: Boolean
    ) : StartupSubtitleAutoSelectionDecision
    data class Addon(
        val subtitle: Subtitle,
        val enableAiTranslation: Boolean
    ) : StartupSubtitleAutoSelectionDecision
}

internal fun decideStartupSubtitleAutoSelection(
    subtitleTracks: List<TrackInfo>,
    addonSubtitles: List<Subtitle>,
    preferredLanguage: String,
    secondaryLanguage: String?,
    hasScannedTextTracksOnce: Boolean,
    playerReady: Boolean,
    addonSubtitleDiscoveryPending: Boolean = false,
    aiTranslationConfigured: Boolean,
    startupPhase: Boolean,
    videoRelease: AioStrictParsedFile? = null
): StartupSubtitleAutoSelectionDecision {
    val normalizedPreferred = PlayerSubtitleUtils.normalizeLanguageCode(preferredLanguage)
        .takeIf { it.isNotBlank() && !it.equals("none", ignoreCase = true) }
    val normalizedSecondary = secondaryLanguage
        ?.takeIf { it.isNotBlank() }
        ?.let(PlayerSubtitleUtils::normalizeLanguageCode)
        ?.takeIf { it.isNotBlank() && !it.equals("none", ignoreCase = true) }

    if (normalizedPreferred == null && normalizedSecondary == null) {
        return StartupSubtitleAutoSelectionDecision.None
    }

    if (normalizedPreferred == "forced") {
        if (!hasScannedTextTracksOnce) return StartupSubtitleAutoSelectionDecision.DeferAddonFallback
        val forcedIndex = subtitleTracks.indexOfFirst { it.isForced }
        return if (forcedIndex >= 0) {
            StartupSubtitleAutoSelectionDecision.Internal(
                index = forcedIndex,
                enableAiTranslation = false
            )
        } else {
            StartupSubtitleAutoSelectionDecision.None
        }
    }

    fun findInternal(language: String?): Int {
        if (language == null) return -1
        return findBestInternalSubtitleTrackIndexForStartup(
            subtitleTracks = subtitleTracks,
            targets = listOf(language)
        )
    }

    fun findAddon(language: String?): Subtitle? {
        if (language == null) return null
        if (!hasScannedTextTracksOnce || !playerReady) {
            return null
        }
        val matches = addonSubtitles.filter { subtitle ->
            PlayerSubtitleUtils.matchesLanguageCode(subtitle.lang, language)
        }
        if (matches.isEmpty()) return null
        return pickBestAddonSubtitleByRelease(matches, videoRelease)
    }

    val internalPreferred = findInternal(normalizedPreferred)
    if (internalPreferred >= 0) {
        return StartupSubtitleAutoSelectionDecision.Internal(
            index = internalPreferred,
            enableAiTranslation = false
        )
    }

    val addonPreferred = findAddon(normalizedPreferred)
    if (addonPreferred != null) {
        return StartupSubtitleAutoSelectionDecision.Addon(
            subtitle = addonPreferred,
            enableAiTranslation = false
        )
    }

    if (addonSubtitleDiscoveryPending) {
        return StartupSubtitleAutoSelectionDecision.DeferAddonFallback
    }

    // findAddon() returns null both when no addon match exists AND when the
    // readiness gate (!hasScannedTextTracksOnce || !playerReady) blocks it.
    // Without this defer, the AI translation tier below would intercept any
    // window where the addon list has loaded but the player's internal text
    // tracks haven't been scanned yet — auto-translating an English internal
    // track even though primary-language addon subs are sitting right there.
    if (!hasScannedTextTracksOnce || !playerReady) {
        return StartupSubtitleAutoSelectionDecision.DeferAddonFallback
    }

    // AI-translation tier (the "third primary tier"):
    // No primary-language subtitle exists anywhere, so use AI to bridge a
    // different-language embedded source into the user's primary language.
    // The translation source is chosen by pickTranslatableInternalSubtitle:
    //   1. Secondary language (when set) — user's source-language hint.
    //   2. English — highest-quality NMT corpus.
    //   3. Any other text-based embedded track.
    // We do NOT fall back to addon subtitles here. Addon fetching is keyed
    // on primary+secondary languages only, so any matching addon would be
    // covered by the existing untranslated-secondary tier below (tier 5)
    // with the same enableAiTranslation flag.
    val aiTranslationAllowed =
        startupPhase && aiTranslationConfigured && normalizedPreferred != null
    if (aiTranslationAllowed) {
        val translatableInternalIndex = pickTranslatableInternalSubtitle(
            subtitleTracks = subtitleTracks,
            secondaryLanguage = normalizedSecondary
        )
        if (translatableInternalIndex >= 0) {
            return StartupSubtitleAutoSelectionDecision.Internal(
                index = translatableInternalIndex,
                enableAiTranslation = true
            )
        }
    }

    val internalSecondary = findInternal(normalizedSecondary)
    if (internalSecondary >= 0) {
        return StartupSubtitleAutoSelectionDecision.Internal(
            index = internalSecondary,
            enableAiTranslation = startupPhase && aiTranslationConfigured && normalizedPreferred != null
        )
    }

    val addonSecondary = findAddon(normalizedSecondary)
    if (addonSecondary != null) {
        return StartupSubtitleAutoSelectionDecision.Addon(
            subtitle = addonSecondary,
            enableAiTranslation = startupPhase && aiTranslationConfigured && normalizedPreferred != null
        )
    }

    return if (!hasScannedTextTracksOnce || !playerReady) {
        StartupSubtitleAutoSelectionDecision.DeferAddonFallback
    } else {
        StartupSubtitleAutoSelectionDecision.None
    }
}

/**
 * Rule-based scorer that picks the addon subtitle most likely to be in sync
 * with the playing release. Reuses [AioStrictFileParser] (the same parser
 * the stream-list pipeline already runs over every stream) so the candidate
 * is parsed once and the video-side parse is supplied pre-computed by the
 * caller — no duplicate parsing.
 *
 * Scoring (additive; the highest total wins):
 *   - Title  / Year mismatch  → -1000 (effective rule-out)
 *   - Source class incompatible (CAM/TS/TC/SCR vs retail) → -1000
 *   - Source class match (e.g. WEB-DL ↔ WEB-DL)          → +50
 *   - Source class soft mismatch (WEB-DL ↔ WEBRip)       → +10
 *   - Service / network match (e.g. AMZN ↔ AMZN)         → +30
 *   - Release group match (e.g. NatusVincere)            → +40
 *   - Visual tag match per tag (DV, HDR10+, …)           → +10
 *   - Audio tag match per tag (Atmos, DDP5.1, …)         → +5
 *   - Resolution match (2160p ↔ 2160p)                   → +5
 *   - "Early Release" / leak penalty                     → -15
 *
 * Selection rules:
 *   - TV releases require an exact parsed release-group match.
 *   - Zero / negative scores return null so callers can fall through to
 *     lower-confidence tiers (for example AI translation) instead of
 *     silently picking the first addon subtitle in list order.
 */
internal fun pickBestAddonSubtitleByRelease(
    candidates: List<Subtitle>,
    videoRelease: AioStrictParsedFile?
): Subtitle? {
    if (candidates.isEmpty()) return null
    if (videoRelease == null) return candidates.first()

    val requireExactReleaseGroup = requiresExactTvSubtitleReleaseGroupMatch(videoRelease)
    val scored = candidates.map { subtitle ->
        val parseInput = subtitleReleaseParseInput(subtitle)
        val candidateRelease = AioStrictFileParser.parse(parseInput)
        val score = scoreSubtitleAgainstRelease(candidateRelease, videoRelease)
        ScoredSubtitleCandidate(
            subtitle = subtitle,
            score = score,
            releaseGroupMatched = releaseGroupsMatch(videoRelease, candidateRelease)
        )
    }

    val eligible = scored.filter { candidate ->
        candidate.score > 0 &&
            (!requireExactReleaseGroup || candidate.releaseGroupMatched)
    }
    val best = eligible.maxByOrNull { it.score }
    Log.i(
        PlayerRuntimeController.TAG,
        "ADDON_SUB: auto-pick scores=" + scored.joinToString(", ") {
            buildString {
                append(it.subtitle.id.take(40))
                append("=")
                append(it.score)
                if (requireExactReleaseGroup) {
                    append(if (it.releaseGroupMatched) "[group]" else "[no-group-match]")
                }
            }
        } + when (best) {
            null -> " → none (strictTv=$requireExactReleaseGroup)"
            else -> " → ${best.subtitle.id.take(80)} (score=${best.score}, strictTv=$requireExactReleaseGroup)"
        }
    )
    return best?.subtitle
}

private const val SUBTITLE_RULE_OUT_SCORE: Int = -1000

private data class ScoredSubtitleCandidate(
    val subtitle: Subtitle,
    val score: Int,
    val releaseGroupMatched: Boolean
)

private val INCOMPATIBLE_SOURCE_CLASSES: Set<String> = setOf(
    "CAM", "TS", "TC", "SCR"
)

private val RETAIL_SOURCE_CLASSES: Set<String> = setOf(
    "BluRay REMUX", "BluRay", "WEB-DL", "WEBRip", "HDRip", "DVDRip", "HDTV"
)

private fun requiresExactTvSubtitleReleaseGroupMatch(release: AioStrictParsedFile): Boolean {
    return release.seasons.isNotEmpty() || release.episodes.isNotEmpty() || release.seasonPack
}

private fun releaseGroupsMatch(
    video: AioStrictParsedFile,
    candidate: AioStrictParsedFile
): Boolean {
    val videoGroup = video.releaseGroup?.trim()?.takeIf { it.isNotBlank() } ?: return false
    val candidateGroup = candidate.releaseGroup?.trim()?.takeIf { it.isNotBlank() } ?: return false
    return videoGroup.equals(candidateGroup, ignoreCase = true)
}

private fun subtitleReleaseParseInput(subtitle: Subtitle): String {
    val idHint = subtitle.id
        .substringAfterLast('|')
        .takeIf(::looksLikeReleaseParseHint)
    val urlHint = subtitle.url
        .substringAfterLast('/')
        .substringBefore('?')
        .takeIf(::looksLikeReleaseParseHint)
    return listOfNotNull(idHint, urlHint)
        .distinct()
        .joinToString(" ")
        .ifBlank { subtitle.id }
}

private fun looksLikeReleaseParseHint(value: String): Boolean {
    if (value.isBlank()) return false
    if (value.none { it.isLetter() }) return false
    return Regex(
        """(?i)(\b(19|20)\d{2}\b|\bS\d{1,2}E\d{1,2}\b|\b\d{1,2}x\d{1,2}\b|\b(2160|1080|720|480)p\b|WEB[ ._-]DL|WEBRip|BluRay|HDTV)"""
    ).containsMatchIn(value)
}

private fun scoreSubtitleAgainstRelease(
    candidate: AioStrictParsedFile,
    video: AioStrictParsedFile
): Int {
    // Hard rule-out: title mismatch (when both sides have a parseable title)
    val candidateTitle = candidate.title?.lowercase(Locale.ROOT)?.trim()
    val videoTitle = video.title?.lowercase(Locale.ROOT)?.trim()
    if (!candidateTitle.isNullOrBlank() && !videoTitle.isNullOrBlank()) {
        // Soft title compare: token Jaccard >= 0.5 OR one fully contains the
        // other. Stricter equality would reject e.g. "Avatar Fire And Ash"
        // vs "Avatar.Fire.and.Ash" purely on punctuation.
        if (!titlesMatchLoosely(candidateTitle, videoTitle)) {
            return SUBTITLE_RULE_OUT_SCORE
        }
    }

    // Hard rule-out: year mismatch (only when both sides have a year)
    if (!candidate.year.isNullOrBlank() && !video.year.isNullOrBlank() &&
        candidate.year != video.year
    ) {
        return SUBTITLE_RULE_OUT_SCORE
    }

    // Hard rule-out: incompatible source class (cinema cap vs retail digital).
    // Subtitles from these sources will never sync with a retail stream
    // because the cuts, frame rates, and idents differ.
    val videoQuality = video.quality
    val candidateQuality = candidate.quality
    if (videoQuality in RETAIL_SOURCE_CLASSES && candidateQuality in INCOMPATIBLE_SOURCE_CLASSES) {
        return SUBTITLE_RULE_OUT_SCORE
    }
    if (videoQuality in INCOMPATIBLE_SOURCE_CLASSES && candidateQuality in RETAIL_SOURCE_CLASSES) {
        return SUBTITLE_RULE_OUT_SCORE
    }

    var score = 0

    // Source class match — the single biggest signal for sync compatibility.
    when {
        videoQuality == null || candidateQuality == null -> Unit
        videoQuality == candidateQuality -> score += 50
        // WEB-DL ↔ WEBRip: not a perfect match (WEBRip is a re-capture and
        // can drift), but usually serviceable when no exact match exists.
        (videoQuality == "WEB-DL" && candidateQuality == "WEBRip") ||
            (videoQuality == "WEBRip" && candidateQuality == "WEB-DL") -> score += 10
        // BluRay ↔ BluRay REMUX share the same master, treat as near-match.
        (videoQuality == "BluRay" && candidateQuality == "BluRay REMUX") ||
            (videoQuality == "BluRay REMUX" && candidateQuality == "BluRay") -> score += 30
    }

    // Service / network match (AMZN, NF, ATVP, MA, …). Soft signal: even
    // when the network differs, two retail digital sources usually share
    // the same master, so we score this lower than the source class match.
    if (!video.network.isNullOrBlank() && !candidate.network.isNullOrBlank() &&
        video.network.equals(candidate.network, ignoreCase = true)
    ) {
        score += 30
    }

    // Release group match — when both sides came from the same scene/p2p
    // group the subtitle was almost certainly demuxed from the same master.
    if (!video.releaseGroup.isNullOrBlank() && !candidate.releaseGroup.isNullOrBlank() &&
        video.releaseGroup.equals(candidate.releaseGroup, ignoreCase = true)
    ) {
        score += 40
    }

    // Visual tag overlap (DV, HDR10, HDR10+, …). Each shared tag implies
    // the subtitle was extracted from a master sharing the same colour
    // pipeline as our stream, which strongly implies same-master origin.
    val visualOverlap = candidate.visualTags.toSet()
        .intersect(video.visualTags.toSet())
    score += visualOverlap.size * 10

    // Audio tag overlap (Atmos, DDP5.1, TrueHD, …). Weaker than visual but
    // still a same-master indicator.
    val audioTagOverlap = candidate.audioTags.toSet()
        .intersect(video.audioTags.toSet())
    score += audioTagOverlap.size * 5

    // Audio channel layout overlap.
    val channelOverlap = candidate.audioChannels.toSet()
        .intersect(video.audioChannels.toSet())
    score += channelOverlap.size * 3

    // Resolution match — small tiebreaker; resolution doesn't affect sync
    // but matches usually correlate with same-master.
    if (!video.resolution.isNullOrBlank() && !candidate.resolution.isNullOrBlank() &&
        video.resolution.equals(candidate.resolution, ignoreCase = true)
    ) {
        score += 5
    }

    // Edition / cut match (Director's Cut, Extended, IMAX, …). Different
    // cuts have different runtimes and will desync.
    val editionOverlap = candidate.editions.toSet()
        .intersect(video.editions.toSet())
    score += editionOverlap.size * 15
    // Edition mismatch penalty: if both sides declare editions and they
    // share none, that's a real sync risk.
    if (candidate.editions.isNotEmpty() && video.editions.isNotEmpty() && editionOverlap.isEmpty()) {
        score -= 20
    }

    // Repack / Proper match (small bonus when both are the corrected release).
    if (candidate.repack && video.repack) score += 5

    // Early release / leak penalty: leaked variants often have different
    // cuts, missing scenes, or hardcoded subs and rarely sync with retail.
    val candidateBlob = listOfNotNull(candidate.title, candidate.releaseGroup)
        .joinToString(" ")
        .lowercase(Locale.ROOT)
    if (Regex("""\b(early[ ._-]?release|leaked?|kor[ ._-]?sub|hc[ ._-]?subs?)\b""").containsMatchIn(candidateBlob)) {
        score -= 15
    }

    return score
}

private fun titlesMatchLoosely(a: String, b: String): Boolean {
    val tokensA = titleTokens(a)
    val tokensB = titleTokens(b)
    if (tokensA.isEmpty() || tokensB.isEmpty()) return true // can't judge → don't rule out
    if (tokensA == tokensB) return true
    val intersect = tokensA.intersect(tokensB).size
    val union = tokensA.union(tokensB).size
    val jaccard = intersect.toDouble() / union.toDouble()
    return jaccard >= 0.5
}

private fun titleTokens(value: String): Set<String> {
    return value
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9 ]"), " ")
        .split(Regex("\\s+"))
        .map { it.trim() }
        .filter { it.length >= 2 && it !in TITLE_STOPWORDS }
        .toSet()
}

private val TITLE_STOPWORDS: Set<String> = setOf(
    "the", "a", "an", "and", "of", "in", "on", "to", "for", "with", "from"
)

/**
 * Picks the best internal subtitle track to use as a translation source for
 * the AI-translation tier. Bitmap subtitles (PGS / VobSub / DVB) are
 * filtered out — they cannot be translated because the cue payload is
 * pixels, not text. Among text-based candidates, prefers (in order):
 *   1. The secondary language (the user's translation hint).
 *   2. English (highest-quality NMT corpus, statistically most likely to
 *      produce a good translation).
 *   3. Any other text-based track.
 * Returns -1 when no text-based internal subtitle exists.
 */
internal fun pickTranslatableInternalSubtitle(
    subtitleTracks: List<TrackInfo>,
    secondaryLanguage: String?
): Int {
    if (subtitleTracks.isEmpty()) return -1
    val textTracks = subtitleTracks
        .mapIndexedNotNull { index, track ->
            if (isBitmapSubtitleMimeType(track.mimeType)) null else index
        }
    if (textTracks.isEmpty()) return -1

    if (!secondaryLanguage.isNullOrBlank()) {
        val secondaryMatches = textTracks.filter { index ->
            PlayerSubtitleUtils.matchesLanguageCode(subtitleTracks[index].language, secondaryLanguage)
        }
        bestSubtitleByAccessibility(subtitleTracks, secondaryMatches)?.let { return it }
    }
    val englishMatches = textTracks.filter { index ->
        PlayerSubtitleUtils.matchesLanguageCode(subtitleTracks[index].language, "en")
    }
    bestSubtitleByAccessibility(subtitleTracks, englishMatches)?.let { return it }
    return bestSubtitleByAccessibility(subtitleTracks, textTracks) ?: textTracks.first()
}

/**
 * Returns true when the supplied Media3 sample MIME type names a bitmap
 * subtitle codec (PGS / VobSub / DVB). Bitmap subs cannot be passed through
 * Gemini for translation because the cue payload is rasterized image data,
 * not Unicode text. Unknown / null MIME types are treated as text so we
 * never silently drop a translatable track.
 */
internal fun isBitmapSubtitleMimeType(mimeType: String?): Boolean {
    if (mimeType.isNullOrBlank()) return false
    val normalized = mimeType.lowercase(Locale.ROOT)
    return normalized == "application/pgs" ||
        normalized == "application/x-pgs" ||
        normalized == "application/vobsub" ||
        normalized == "application/dvbsubs" ||
        normalized == "application/x-subpic"
}

internal fun findBestInternalSubtitleTrackIndexForStartup(
    subtitleTracks: List<TrackInfo>,
    targets: List<String>
): Int {
    for ((targetPosition, target) in targets.withIndex()) {
        if (target == SUBTITLE_LANGUAGE_FORCED) {
            val forcedIndex = subtitleTracks.indexOfFirst { it.isForced }
            if (forcedIndex >= 0) return forcedIndex
            if (targetPosition == 0) return -1
            continue
        }
        val normalizedTarget = PlayerSubtitleUtils.normalizeLanguageCode(target)
        val candidateIndexes = subtitleTracks.indices.filter { index ->
            PlayerSubtitleUtils.matchesLanguageCode(subtitleTracks[index].language, target)
        }
        if (candidateIndexes.isEmpty()) {
            if (normalizedTarget == "pt-br") {
                val brazilianFromGenericPt = findBrazilianPortugueseInGenericPtTracksForStartup(subtitleTracks)
                if (brazilianFromGenericPt >= 0) {
                    return brazilianFromGenericPt
                }
                if (targetPosition == 0) {
                    return -1
                }
            }
            continue
        }
        if (candidateIndexes.size == 1) return candidateIndexes.first()

        if (normalizedTarget == "pt" || normalizedTarget == "pt-br") {
            val tieBroken = breakPortugueseSubtitleTieForStartup(
                subtitleTracks = subtitleTracks,
                candidateIndexes = candidateIndexes,
                normalizedTarget = normalizedTarget
            )
            if (tieBroken >= 0) return tieBroken
        }
        return bestSubtitleByAccessibility(subtitleTracks, candidateIndexes)
            ?: candidateIndexes.first()
    }
    return -1
}

private fun findBrazilianPortugueseInGenericPtTracksForStartup(
    subtitleTracks: List<TrackInfo>
): Int {
    val genericPtIndexes = subtitleTracks.indices.filter { index ->
        val trackLanguage = subtitleTracks[index].language ?: return@filter false
        PlayerSubtitleUtils.normalizeLanguageCode(trackLanguage) == "pt"
    }
    if (genericPtIndexes.isEmpty()) return -1

    return genericPtIndexes.firstOrNull { index ->
        subtitleHasAnyTagForStartup(subtitleTracks[index], PlayerRuntimeController.PORTUGUESE_BRAZILIAN_TAGS) &&
            !subtitleHasAnyTagForStartup(subtitleTracks[index], PlayerRuntimeController.PORTUGUESE_EUROPEAN_TAGS)
    } ?: genericPtIndexes.firstOrNull { index ->
        subtitleHasAnyTagForStartup(subtitleTracks[index], PlayerRuntimeController.PORTUGUESE_BRAZILIAN_TAGS)
    } ?: -1
}

private fun breakPortugueseSubtitleTieForStartup(
    subtitleTracks: List<TrackInfo>,
    candidateIndexes: List<Int>,
    normalizedTarget: String
): Int {
    fun hasBrazilianTags(index: Int): Boolean {
        return subtitleHasAnyTagForStartup(subtitleTracks[index], PlayerRuntimeController.PORTUGUESE_BRAZILIAN_TAGS)
    }

    fun hasEuropeanTags(index: Int): Boolean {
        return subtitleHasAnyTagForStartup(subtitleTracks[index], PlayerRuntimeController.PORTUGUESE_EUROPEAN_TAGS)
    }

    return if (normalizedTarget == "pt-br") {
        bestSubtitleByAccessibility(subtitleTracks, candidateIndexes.filter { hasBrazilianTags(it) && !hasEuropeanTags(it) })
            ?: bestSubtitleByAccessibility(subtitleTracks, candidateIndexes.filter { hasBrazilianTags(it) })
            ?: candidateIndexes.first()
    } else {
        bestSubtitleByAccessibility(subtitleTracks, candidateIndexes.filter { hasEuropeanTags(it) && !hasBrazilianTags(it) })
            ?: bestSubtitleByAccessibility(subtitleTracks, candidateIndexes.filter { hasEuropeanTags(it) })
            ?: bestSubtitleByAccessibility(subtitleTracks, candidateIndexes.filter { !hasBrazilianTags(it) })
            ?: candidateIndexes.first()
    }
}

private fun subtitleHasAnyTagForStartup(track: TrackInfo, tags: List<String>): Boolean {
    val haystack = listOfNotNull(track.name, track.language, track.trackId)
        .joinToString(" ")
        .lowercase(Locale.ROOT)
    return tags.any { tag -> haystack.contains(tag) }
}

private val NON_PRIMARY_AUDIO_TRACK_TYPES = setOf(
    AudioTrackType.COMMENTARY,
    AudioTrackType.DESCRIPTIVE_AUDIO,
    AudioTrackType.ISOLATED_SCORE,
    AudioTrackType.VOICEOVER
)

private fun isUndeterminedAudioTrackLanguage(language: String?): Boolean {
    if (language.isNullOrBlank()) return true
    val normalized = language.trim().lowercase(Locale.ROOT)
    return normalized == "und" || normalized == "undetermined"
}

private fun isLikelyOriginalLanguageTrack(track: TrackInfo): Boolean {
    val trackTypes = PlayerAudioTrackNamingConventions.trackTypes(track)
    if (trackTypes.any { it in NON_PRIMARY_AUDIO_TRACK_TYPES || it == AudioTrackType.DUBBED }) {
        return false
    }

    if (AudioTrackType.ORIGINAL in trackTypes) {
        return true
    }

    if (isUndeterminedAudioTrackLanguage(track.language)) {
        return true
    }

    val haystack = listOfNotNull(track.name, track.trackId)
        .joinToString(" ")
        .lowercase(Locale.ROOT)
    return Regex("""\b(main|default)\b""").containsMatchIn(haystack)
}

private val SDH_SUBTITLE_MARKERS: List<String> = listOf(
    "sdh",
    "closed caption",
    "hearing impaired"
)

// Standalone "cc" token (Closed Captions). Word-boundary anchored so
// "Soccer" / "accordion" do not match, but "[CC]", "(CC)", "English-CC",
// "CC English", and "English CC" all match correctly. The pattern is
// lowercase because [isSdhSubtitle] lowercases the haystack before
// matching; if a future caller passes a non-lowercased input, prefer
// adding a lowercase step there over mutating this regex.
private val SDH_CC_TOKEN_REGEX: Regex = Regex("""\bcc\b""")

private fun TrackInfo.isSdhSubtitle(): Boolean {
    val haystack = listOfNotNull(name, trackId)
        .joinToString(" ")
        .lowercase(Locale.ROOT)
    if (haystack.isBlank()) return false
    return SDH_SUBTITLE_MARKERS.any { marker -> haystack.contains(marker) } ||
        SDH_CC_TOKEN_REGEX.containsMatchIn(haystack)
}

/**
 * Lower rank wins. Normal dialogue tracks (rank 0) are preferred over SDH
 * (rank 1) and forced (rank 2). Forced tracks contain only signs/inserts —
 * never the full dialogue — so they should only be picked when no other
 * same-language track exists. The explicit `preferredLanguage == "forced"`
 * branch in [decideStartupSubtitleAutoSelection] short-circuits before this
 * ranking is consulted, so users who actively choose forced still get it.
 */
private fun TrackInfo.subtitleAccessibilityRank(): Int = when {
    isForced -> 2
    isSdhSubtitle() -> 1
    else -> 0
}

private fun bestSubtitleByAccessibility(
    subtitleTracks: List<TrackInfo>,
    candidates: List<Int>
): Int? {
    return candidates.minByOrNull { subtitleTracks[it].subtitleAccessibilityRank() }
}
