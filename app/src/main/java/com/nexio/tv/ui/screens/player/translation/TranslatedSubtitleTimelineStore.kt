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
) {
    companion object {
        val ZERO = TranslationTimelineStats(
            sourceCueCount = 0,
            translatedCueCount = 0,
            pendingBackfillCount = 0,
            hitCount = 0,
            missCount = 0
        )
    }
}

internal class TranslatedSubtitleTimelineStore(
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    private val lock = Any()
    private val sourceCues = LinkedHashMap<RecordKey, TranslationTimelineSourceCue>()
    private val translatedCueGroups = LinkedHashMap<RecordKey, CueGroup>()
    private val pendingBackfill = LinkedHashMap<RecordKey, TranslationTimelineSourceCue>()
    private val deferredBackfillRetryAfterMs = LinkedHashMap<RecordKey, Long>()

    private var activeSessionKey: TranslationTimelineSessionKey? = null
    private var hitCount = 0L
    private var missCount = 0L

    fun beginSession(sessionKey: TranslationTimelineSessionKey) {
        synchronized(lock) {
            if (activeSessionKey != sessionKey) {
                sourceCues.clear()
                translatedCueGroups.clear()
                pendingBackfill.clear()
                deferredBackfillRetryAfterMs.clear()
                hitCount = 0L
                missCount = 0L
                activeSessionKey = sessionKey
            }
        }
    }

    fun clearActiveSession() {
        synchronized(lock) {
            sourceCues.clear()
            translatedCueGroups.clear()
            pendingBackfill.clear()
            deferredBackfillRetryAfterMs.clear()
            hitCount = 0L
            missCount = 0L
            activeSessionKey = null
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
    ): TranslationTimelineCueKey? {
        return synchronized(lock) {
            val sourceCue = putSourceCueLocked(sessionKey, sourceCueGroup)
            if (sourceCue == null) {
                null
            } else {
                val recordKey = RecordKey(sessionKey, sourceCue.cueKey)
                translatedCueGroups[recordKey] = translatedCueGroup
                pendingBackfill.remove(recordKey)
                deferredBackfillRetryAfterMs.remove(recordKey)
                sourceCue.cueKey
            }
        }
    }

    fun lookupCueGroup(
        session: TranslationTimelineSessionKey,
        sourceCueGroup: CueGroup
    ): CueGroup? {
        return synchronized(lock) {
            if (activeSessionKey != session) return@synchronized null
            val sourceCue = sourceCueFor(session, sourceCueGroup)
            val translatedCueGroup = sourceCue
                ?.let { translatedCueGroups[RecordKey(session, it.cueKey)] }
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
            val sourceCue = putSourceCueLocked(sessionKey, sourceCueGroup) ?: return@synchronized null
            val recordKey = RecordKey(sessionKey, sourceCue.cueKey)
            if (translatedCueGroups.containsKey(recordKey)) return@synchronized sourceCue
            val retryAfterMs = deferredBackfillRetryAfterMs[recordKey]
            if (retryAfterMs != null) {
                if (retryAfterMs > nowMs()) {
                    return@synchronized sourceCue
                }
                deferredBackfillRetryAfterMs.remove(recordKey)
            }
            pendingBackfill.putIfAbsent(recordKey, sourceCue)
            sourceCue
        }
    }

    fun deferPendingBackfill(
        sessionKey: TranslationTimelineSessionKey,
        sourceCueGroup: CueGroup,
        retryAfterMs: Long
    ): Boolean {
        return synchronized(lock) {
            val sourceCue = putSourceCueLocked(sessionKey, sourceCueGroup) ?: return@synchronized false
            val recordKey = RecordKey(sessionKey, sourceCue.cueKey)
            if (translatedCueGroups.containsKey(recordKey)) return@synchronized false
            pendingBackfill.remove(recordKey)
            deferredBackfillRetryAfterMs[recordKey] = retryAfterMs
            true
        }
    }

    fun pendingBackfill(session: TranslationTimelineSessionKey): List<TranslationTimelineSourceCue> {
        return synchronized(lock) {
            if (activeSessionKey != session) return@synchronized emptyList()
            pendingBackfill.values.toList()
        }
    }

    fun stats(session: TranslationTimelineSessionKey): TranslationTimelineStats {
        return synchronized(lock) {
            if (activeSessionKey != session) return@synchronized TranslationTimelineStats.ZERO
            TranslationTimelineStats(
                sourceCueCount = sourceCues.size,
                translatedCueCount = translatedCueGroups.size,
                pendingBackfillCount = pendingBackfill.size,
                hitCount = hitCount,
                missCount = missCount
            )
        }
    }

    fun cueKeyFor(cueGroup: CueGroup): TranslationTimelineCueKey? {
        val sourceText = sourceTextFor(cueGroup) ?: return null
        return TranslationTimelineCueKey(
            presentationTimeUs = cueGroup.presentationTimeUs,
            sourceTextHash = shortSha256Hex(sourceText)
        )
    }

    private fun putSourceCueLocked(
        sessionKey: TranslationTimelineSessionKey,
        sourceCueGroup: CueGroup
    ): TranslationTimelineSourceCue? {
        if (activeSessionKey != sessionKey) return null
        val sourceCue = sourceCueFor(sessionKey, sourceCueGroup) ?: return null
        sourceCues[RecordKey(sessionKey, sourceCue.cueKey)] = sourceCue
        return sourceCue
    }

    private fun sourceCueFor(
        sessionKey: TranslationTimelineSessionKey,
        sourceCueGroup: CueGroup
    ): TranslationTimelineSourceCue? {
        val sourceText = sourceTextFor(sourceCueGroup) ?: return null
        return TranslationTimelineSourceCue(
            sessionKey = sessionKey,
            cueKey = cueKeyFor(sourceCueGroup) ?: return null,
            sourceText = sourceText,
            cueGroup = sourceCueGroup
        )
    }

    private fun sourceTextFor(cueGroup: CueGroup): String? {
        return cueGroup.cues
            .mapNotNull { cue -> cue.text?.toString()?.trim()?.takeIf(String::isNotBlank) }
            .joinToString(separator = "\n")
            .takeIf(String::isNotBlank)
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
