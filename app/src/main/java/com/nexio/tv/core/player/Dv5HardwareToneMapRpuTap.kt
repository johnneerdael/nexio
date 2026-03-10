package com.nexio.tv.core.player

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import java.util.TreeMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Experimental RPU tap for the DV5 hardware path.
 *
 * Collects Dolby Vision RPU payloads keyed by sample PTS and matches them against rendered frame
 * metadata timestamps. This validates timing/synchronization before native GPU tonemap execution is
 * integrated for the hardware decode path.
 */
object Dv5HardwareToneMapRpuTap {
    private const val TAG = "Dv5HwRpuTap"
    private const val MAX_ENTRIES = 512
    private const val MATCH_TOLERANCE_US = 50_000L
    private const val SUMMARY_INTERVAL = 120L

    private val lock = Any()
    private val queue = TreeMap<Long, ByteArray>()
    private var enabled = false
    private var hostLabel: String = "unknown"

    private val queuedCount = AtomicLong(0L)
    private val droppedNoPtsCount = AtomicLong(0L)
    private val droppedOverflowCount = AtomicLong(0L)
    private val matchHitCount = AtomicLong(0L)
    private val matchMissCount = AtomicLong(0L)
    private data class MatchedRpu(
        val sampleTimeUs: Long,
        val payload: ByteArray
    )

    fun setEnabledForPlayback(enabled: Boolean, streamUrl: String) {
        synchronized(lock) {
            this.enabled = enabled
            queue.clear()
        }
        runCatching {
            FfmpegLibrary.setExperimentalDv5HardwareToneMapRpuBridgeEnabled(enabled)
        }.onFailure {
            Log.w(TAG, "Failed to update native RPU bridge: ${it.message}")
        }
        hostLabel = runCatching { Uri.parse(streamUrl).host ?: "unknown" }.getOrDefault("unknown")
        queuedCount.set(0L)
        droppedNoPtsCount.set(0L)
        droppedOverflowCount.set(0L)
        matchHitCount.set(0L)
        matchMissCount.set(0L)
        Log.i(TAG, "enabled=$enabled host=$hostLabel")
    }

    fun onRpuSample(sampleTimeUs: Long, rpuNalPayload: ByteArray, source: String) {
        if (!enabled || rpuNalPayload.isEmpty()) return
        if (sampleTimeUs == C.TIME_UNSET) {
            droppedNoPtsCount.incrementAndGet()
            return
        }
        synchronized(lock) {
            queue[sampleTimeUs] = rpuNalPayload.copyOf()
            queuedCount.incrementAndGet()
            while (queue.size > MAX_ENTRIES) {
                queue.pollFirstEntry()
                droppedOverflowCount.incrementAndGet()
            }
        }
        maybeLogSummary(source = source)
    }

    fun onFrameAboutToRender(presentationTimeUs: Long) {
        if (!enabled) return
        val matchedRpu = synchronized(lock) {
            if (queue.isEmpty()) {
                null
            } else {
                val floor = queue.floorEntry(presentationTimeUs)
                val ceil = queue.ceilingEntry(presentationTimeUs)
                val best = when {
                    floor == null -> ceil
                    ceil == null -> floor
                    else -> {
                        val floorDelta = kotlin.math.abs(presentationTimeUs - floor.key)
                        val ceilDelta = kotlin.math.abs(ceil.key - presentationTimeUs)
                        if (floorDelta <= ceilDelta) floor else ceil
                    }
                }
                if (best != null && kotlin.math.abs(best.key - presentationTimeUs) <= MATCH_TOLERANCE_US) {
                    queue.remove(best.key)
                    MatchedRpu(best.key, best.value)
                } else {
                    null
                }
            }
        }
        if (matchedRpu != null) {
            matchHitCount.incrementAndGet()
            runCatching {
                FfmpegLibrary.pushExperimentalDv5HardwareRpuSample(
                    matchedRpu.sampleTimeUs,
                    matchedRpu.payload
                )
            }.onFailure {
                Log.w(TAG, "Failed to push matched RPU to native bridge: ${it.message}")
            }
        } else {
            matchMissCount.incrementAndGet()
        }
        runCatching {
            FfmpegLibrary.notifyExperimentalDv5HardwareFramePresented(presentationTimeUs)
        }.onFailure {
            Log.w(TAG, "Failed to notify native frame presentation: ${it.message}")
        }
        maybeLogSummary(source = "frame")
    }

    private fun maybeLogSummary(source: String) {
        val totalMatches = matchHitCount.get() + matchMissCount.get()
        if (totalMatches > 0L && totalMatches % SUMMARY_INTERVAL == 0L) {
            Log.i(
                TAG,
                "source=$source host=$hostLabel queued=${queuedCount.get()} " +
                    "hits=${matchHitCount.get()} misses=${matchMissCount.get()} " +
                    "dropNoPts=${droppedNoPtsCount.get()} dropOverflow=${droppedOverflowCount.get()}"
            )
        }
    }
}
