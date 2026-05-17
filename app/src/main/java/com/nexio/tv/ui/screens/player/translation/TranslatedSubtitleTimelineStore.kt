package com.nexio.tv.ui.screens.player.translation

import androidx.media3.common.text.CueGroup
import java.security.MessageDigest
import java.util.Locale

internal data class TranslationTimelineSessionKey(
    val streamKey: String,
    val trackKey: String,
    val targetLanguage: String,
    val settingsKey: String
)

internal data class TranslationTimelineCueKey(
    val presentationTimeUs: Long,
    val sourceTextHash: String
)

internal data class TranslationTimelineSourceCue(
    val sessionKey: TranslationTimelineSessionKey,
    val cueKey: TranslationTimelineCueKey,
    val sourceText: String,
    val cueGroup: CueGroup
)

internal data class TranslationTimelineStats(
    val sourceCueCount: Int,
    val translatedCueCount: Int,
    val pendingBackfillCount: Int,
    val hitCount: Long,
    val missCount: Long
)

internal class TranslatedSubtitleTimelineStore(maxCueRecords: Int = 5_000) {
    private val maxCueRecords = maxCueRecords.coerceAtLeast(1)
    private val lock = Any()
    private val sourceCues = LinkedHashMap<RecordKey, TranslationTimelineSourceCue>()
    private val translatedCueGroups = LinkedHashMap<RecordKey, CueGroup>()
    private val pendingBackfill = LinkedHashMap<RecordKey, TranslationTimelineSourceCue>()

    private var activeSessionKey: TranslationTimelineSessionKey? = null
    private var hitCount = 0L
    private var missCount = 0L

    fun beginSession(sessionKey: TranslationTimelineSessionKey) {
        synchronized(lock) {
            if (activeSessionKey != sessionKey) {
                sourceCues.clear()
                translatedCueGroups.clear()
                pendingBackfill.clear()
                hitCount = 0L
                missCount = 0L
                activeSessionKey = sessionKey
            }
        }
    }

    fun putSourceCue(
        sessionKey: TranslationTimelineSessionKey,
        sourceCueGroup: CueGroup
    ): TranslationTimelineSourceCue? {
        return synchronized(lock) {
            putSourceCueLocked(sessionKey, sourceCueGroup)
        }
    }

    fun putTranslatedCueGroup(
        sessionKey: TranslationTimelineSessionKey,
        sourceCueGroup: CueGroup,
        translatedCueGroup: CueGroup
    ) {
        synchronized(lock) {
            val sourceCue = putSourceCueLocked(sessionKey, sourceCueGroup) ?: return@synchronized
            translatedCueGroups[RecordKey(sessionKey, sourceCue.cueKey)] = translatedCueGroup
            trimToMaxRecords(translatedCueGroups)
        }
    }

    fun lookup(
        sessionKey: TranslationTimelineSessionKey,
        sourceCueGroup: CueGroup
    ): CueGroup? {
        return synchronized(lock) {
            val sourceCue = sourceCueFor(sessionKey, sourceCueGroup)
            val translatedCueGroup = sourceCue
                ?.let { translatedCueGroups[RecordKey(sessionKey, it.cueKey)] }
            if (translatedCueGroup == null) {
                missCount += 1
            } else {
                hitCount += 1
            }
            translatedCueGroup
        }
    }

    fun registerMiss(
        sessionKey: TranslationTimelineSessionKey,
        sourceCueGroup: CueGroup
    ): TranslationTimelineSourceCue? {
        return synchronized(lock) {
            missCount += 1
            val sourceCue = putSourceCueLocked(sessionKey, sourceCueGroup) ?: return@synchronized null
            pendingBackfill.putIfAbsent(RecordKey(sessionKey, sourceCue.cueKey), sourceCue)
            trimToMaxRecords(pendingBackfill)
            sourceCue
        }
    }

    fun pendingBackfill(): List<TranslationTimelineSourceCue> {
        return synchronized(lock) {
            pendingBackfill.values.toList()
        }
    }

    fun stats(): TranslationTimelineStats {
        return synchronized(lock) {
            TranslationTimelineStats(
                sourceCueCount = sourceCues.size,
                translatedCueCount = translatedCueGroups.size,
                pendingBackfillCount = pendingBackfill.size,
                hitCount = hitCount,
                missCount = missCount
            )
        }
    }

    private fun putSourceCueLocked(
        sessionKey: TranslationTimelineSessionKey,
        sourceCueGroup: CueGroup
    ): TranslationTimelineSourceCue? {
        if (activeSessionKey != sessionKey) return null
        val sourceCue = sourceCueFor(sessionKey, sourceCueGroup) ?: return null
        sourceCues[RecordKey(sessionKey, sourceCue.cueKey)] = sourceCue
        trimToMaxRecords(sourceCues)
        return sourceCue
    }

    private fun sourceCueFor(
        sessionKey: TranslationTimelineSessionKey,
        sourceCueGroup: CueGroup
    ): TranslationTimelineSourceCue? {
        val sourceText = sourceCueGroup.cues
            .mapNotNull { cue -> cue.text?.toString()?.trim()?.takeIf(String::isNotBlank) }
            .joinToString(separator = "\n")
            .takeIf(String::isNotBlank)
            ?: return null
        return TranslationTimelineSourceCue(
            sessionKey = sessionKey,
            cueKey = TranslationTimelineCueKey(
                presentationTimeUs = sourceCueGroup.presentationTimeUs,
                sourceTextHash = shortSha256Hex(sourceText)
            ),
            sourceText = sourceText,
            cueGroup = sourceCueGroup
        )
    }

    private fun <T> trimToMaxRecords(records: LinkedHashMap<RecordKey, T>) {
        while (records.size > maxCueRecords) {
            val iterator = records.entries.iterator()
            if (!iterator.hasNext()) return
            iterator.next()
            iterator.remove()
        }
    }

    private data class RecordKey(
        val sessionKey: TranslationTimelineSessionKey,
        val cueKey: TranslationTimelineCueKey
    )

    companion object {
        fun translateCueGroupTexts(
            cueGroup: CueGroup,
            translatedTexts: Map<String, String>
        ): CueGroup {
            return CueGroup(
                cueGroup.cues.map { cue ->
                    val sourceText = cue.text?.toString()?.trim()
                    val translatedText = sourceText
                        ?.let { translatedTexts[it] }
                        ?.takeIf(String::isNotBlank)
                    if (translatedText == null) {
                        cue
                    } else {
                        cue.buildUpon()
                            .setText(translatedText)
                            .build()
                    }
                },
                cueGroup.presentationTimeUs
            )
        }

        private fun shortSha256Hex(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
            return digest
                .take(8)
                .joinToString(separator = "") { byte ->
                    "%02x".format(Locale.US, byte.toInt() and 0xff)
                }
        }
    }
}
