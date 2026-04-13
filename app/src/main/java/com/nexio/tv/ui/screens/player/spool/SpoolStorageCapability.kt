package com.nexio.tv.ui.screens.player.spool

import org.json.JSONObject

internal data class SpoolStorageProbeResult(
    val writeMbps: Double,
    val readMbps: Double,
    val combinedMbps: Double,
    val p99ReadLatencyMs: Long,
    val maxReadStallMs: Long,
    val measuredAtMs: Long,
    val durationMs: Long,
    val bytesWritten: Long,
    val bytesRead: Long,
    val spoolDirectoryPath: String
) {
    fun toJsonOrNull(): String? = runCatching { toJson() }.getOrNull()

    fun toJson(): String {
        return JSONObject()
            .put("writeMbps", writeMbps)
            .put("readMbps", readMbps)
            .put("combinedMbps", combinedMbps)
            .put("p99ReadLatencyMs", p99ReadLatencyMs)
            .put("maxReadStallMs", maxReadStallMs)
            .put("measuredAtMs", measuredAtMs)
            .put("durationMs", durationMs)
            .put("bytesWritten", bytesWritten)
            .put("bytesRead", bytesRead)
            .put("spoolDirectoryPath", spoolDirectoryPath)
            .toString()
    }

    companion object {
        fun fromJsonOrNull(raw: String?): SpoolStorageProbeResult? {
            val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val json = runCatching { JSONObject(text) }.getOrNull() ?: return null

            val writeMbps = json.finiteDoubleOrNull("writeMbps") ?: return null
            val readMbps = json.finiteDoubleOrNull("readMbps") ?: return null
            val combinedMbps = json.finiteDoubleOrNull("combinedMbps") ?: return null
            val p99ReadLatencyMs = json.longOrNull("p99ReadLatencyMs") ?: return null
            val maxReadStallMs = json.longOrNull("maxReadStallMs") ?: return null
            val measuredAtMs = json.longOrNull("measuredAtMs") ?: return null
            val durationMs = json.longOrNull("durationMs") ?: return null
            val bytesWritten = json.longOrNull("bytesWritten") ?: return null
            val bytesRead = json.longOrNull("bytesRead") ?: return null
            val spoolDirectoryPath = json.stringOrNull("spoolDirectoryPath") ?: return null

            return SpoolStorageProbeResult(
                writeMbps = writeMbps,
                readMbps = readMbps,
                combinedMbps = combinedMbps,
                p99ReadLatencyMs = p99ReadLatencyMs,
                maxReadStallMs = maxReadStallMs,
                measuredAtMs = measuredAtMs,
                durationMs = durationMs,
                bytesWritten = bytesWritten,
                bytesRead = bytesRead,
                spoolDirectoryPath = spoolDirectoryPath
            )
        }

        fun fromJson(raw: String): SpoolStorageProbeResult {
            return fromJsonOrNull(raw) ?: throw IllegalArgumentException("Invalid spool storage probe result")
        }
    }
}

internal object SpoolStoragePolicy {
    private const val FALLBACK_TARGET_MBPS = 80.0
    private const val MIN_HEADROOM_MULTIPLIER = 2.5
    private const val MAX_P99_READ_LATENCY_MS = 100L
    private const val MAX_READ_STALL_MS = 500L
    private const val MAX_RESULT_AGE_MS = 7L * 24L * 60L * 60L * 1000L

    fun targetBitrateMbps(streamBitrateMbps: Double?, userCapMbps: Double?): Double {
        if (streamBitrateMbps != null && streamBitrateMbps.isFinite() && streamBitrateMbps > 0.0) {
            return streamBitrateMbps
        }
        return maxOf(
            FALLBACK_TARGET_MBPS,
            userCapMbps?.takeIf { it.isFinite() && it > 0.0 } ?: FALLBACK_TARGET_MBPS
        )
    }

    fun canSustain(result: SpoolStorageProbeResult, targetVideoMbps: Double): Boolean {
        if (!targetVideoMbps.isFinite() || targetVideoMbps <= 0.0) return false
        if (result.durationMs < 30_000L) return false

        val requiredThroughputMbps = targetVideoMbps * MIN_HEADROOM_MULTIPLIER
        return result.writeMbps >= targetVideoMbps &&
            result.readMbps >= targetVideoMbps &&
            result.combinedMbps >= requiredThroughputMbps &&
            result.p99ReadLatencyMs <= MAX_P99_READ_LATENCY_MS &&
            result.maxReadStallMs <= MAX_READ_STALL_MS
    }

    fun isFresh(result: SpoolStorageProbeResult, nowMs: Long, spoolDirectoryPath: String): Boolean {
        if (result.measuredAtMs < 0L) return false
        if (nowMs < result.measuredAtMs) return false

        val age = nowMs - result.measuredAtMs
        return spoolDirectoryPath == result.spoolDirectoryPath &&
            age <= MAX_RESULT_AGE_MS
    }
}

private fun JSONObject.finiteDoubleOrNull(name: String): Double? {
    val value = opt(name) as? Number ?: return null
    val doubleValue = value.toDouble()
    return doubleValue.takeIf { it.isFinite() }
}

private fun JSONObject.longOrNull(name: String): Long? {
    return when (val value = opt(name)) {
        is Int -> value.toLong()
        is Long -> value
        is Short -> value.toLong()
        is Byte -> value.toLong()
        is String -> value.toLongOrNullFromIntegralString()
        is Number -> null
        else -> null
    }
}

private fun JSONObject.stringOrNull(name: String): String? {
    return opt(name) as? String
}

private fun String.toLongOrNullFromIntegralString(): Long? {
    if (!matches(Regex("^-?\\d+$"))) return null
    return toLongOrNull()
}
