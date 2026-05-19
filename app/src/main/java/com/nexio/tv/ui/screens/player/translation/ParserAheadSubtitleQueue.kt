package com.nexio.tv.ui.screens.player.translation

import androidx.media3.common.Format
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.extractor.text.CuesWithTiming
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.security.MessageDigest

internal class ParserAheadSubtitleQueue(
    scope: CoroutineScope,
    private val maxQueuedCues: Int = DEFAULT_MAX_QUEUED_CUES,
    private val playbackPositionUsProvider: () -> Long = { 0L },
    private val isEnabledProvider: (Format) -> Boolean = { true },
    private val diagnostics: ParserAheadSubtitleDiagnostics = ParserAheadSubtitleDiagnostics.disabled(),
    private val enqueueForTranslation: (Format, CueGroup) -> Unit
) : AheadSubtitleCueSink {
    private val channel = Channel<AheadSubtitleCue>(capacity = maxQueuedCues.coerceAtLeast(1))
    private val seenLock = Any()
    private val seenKeys = LinkedHashSet<String>()
    private var queuedCount = 0

    init {
        scope.launch {
            for (cue in channel) {
                queuedCount = (queuedCount - 1).coerceAtLeast(0)
                val cueGroup = cue.cues.toCueGroup() ?: continue
                enqueueForTranslation(cue.format, cueGroup)
            }
        }
    }

    override fun isEnabled(format: Format): Boolean {
        return isEnabledProvider(format)
    }

    override fun enqueue(format: Format, cues: CuesWithTiming) {
        if (!isEnabled(format)) return
        val key = stableKey(format, cues) ?: return
        synchronized(seenLock) {
            if (!seenKeys.add(key)) {
                diagnostics.onDuplicateDrop()
                return
            }
            trimSeenKeysLocked()
        }

        val result = channel.trySend(AheadSubtitleCue(format, cues))
        if (result.isFailure) {
            synchronized(seenLock) {
                seenKeys.remove(key)
            }
            diagnostics.onOverflowDrop()
            return
        }

        queuedCount += 1
        diagnostics.onEnqueued(
            ParserAheadSubtitleDiagnostics.EnqueueEvent(
                cueTimeUs = cues.startTimeUs,
                playbackPositionUs = safePlaybackPositionUs(),
                queuedCount = queuedCount,
                channelCapacity = maxQueuedCues.coerceAtLeast(1)
            )
        )
    }

    override fun onParserReset(format: Format) {
        synchronized(seenLock) {
            seenKeys.clear()
        }
        diagnostics.onParserReset(format)
    }

    private fun trimSeenKeysLocked() {
        val maxSeenKeys = maxQueuedCues.coerceAtLeast(1) * 2
        while (seenKeys.size > maxSeenKeys) {
            val iterator = seenKeys.iterator()
            if (!iterator.hasNext()) return
            iterator.next()
            iterator.remove()
        }
    }

    private fun safePlaybackPositionUs(): Long {
        return runCatching { playbackPositionUsProvider().coerceAtLeast(0L) }
            .getOrDefault(0L)
    }

    private fun CuesWithTiming.toCueGroup(): CueGroup? {
        if (!cues.hasTranslatableText()) return null
        return CueGroup(cues, startTimeUs)
    }

    private fun stableKey(format: Format, cues: CuesWithTiming): String? {
        val text = cues.cues
            .asSequence()
            .mapNotNull { cue -> cue.text?.toString()?.trim()?.takeIf(String::isNotBlank) }
            .joinToString(separator = "\n")
            .takeIf(String::isNotBlank)
            ?: return null
        return sha256(
            listOf(
                format.id.orEmpty(),
                format.sampleMimeType.orEmpty(),
                format.codecs.orEmpty(),
                format.language.orEmpty(),
                cues.startTimeUs.toString(),
                cues.durationUs.toString(),
                text
            ).joinToString(separator = "|")
        )
    }

    private fun List<Cue>.hasTranslatableText(): Boolean {
        if (isEmpty()) return false
        for (index in indices) {
            val cue = this[index]
            val text = cue.text?.toString()?.trim()
            if (!text.isNullOrEmpty()) {
                return true
            }
        }
        return false
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private companion object {
        private const val DEFAULT_MAX_QUEUED_CUES = 300
    }
}
