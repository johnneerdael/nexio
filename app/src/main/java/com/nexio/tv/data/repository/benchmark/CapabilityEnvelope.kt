package com.nexio.tv.data.repository.benchmark

import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class CapabilityEnvelope(
    val maxSafeUrgentWorkers: Int,
    val maxSafePrefetchWorkers: Int,
    val maxSafeUrgentChunkBytes: Long,
    val maxSafePrefetchChunkBytes: Long,
    val sustainedThroughputMbps: Double,
    val p10ThroughputMbps: Double? = null,
    val seekTtfbP50Ms: Long? = null,
    val stabilityPenalty: Double = 0.0,
    val supportsRangeRequests: Boolean = true,
    val measuredAtMs: Long
) {
    companion object {
        val DEFAULT = CapabilityEnvelope(
            maxSafeUrgentWorkers = 1,
            maxSafePrefetchWorkers = 1,
            maxSafeUrgentChunkBytes = 24L * 1024L * 1024L,
            maxSafePrefetchChunkBytes = 24L * 1024L * 1024L,
            sustainedThroughputMbps = 50.0,
            stabilityPenalty = 0.5,
            measuredAtMs = 0L
        )

        /**
         * Locked shape for Real Debrid. Benchmarks may only refresh
         * sustainedThroughputMbps, stabilityPenalty, and measuredAtMs —
         * the structural shape (workers, chunk sizes, supportsRangeRequests) is immutable.
         */
        val LOCKED_REAL_DEBRID = CapabilityEnvelope(
            maxSafeUrgentWorkers = 1,
            maxSafePrefetchWorkers = 1,
            maxSafeUrgentChunkBytes = 33_554_432L,
            maxSafePrefetchChunkBytes = 67_108_864L,
            sustainedThroughputMbps = 0.0,
            stabilityPenalty = 0.0,
            supportsRangeRequests = true,
            measuredAtMs = 0L
        )

        /**
         * Locked shape for Premiumize. Benchmarks may only refresh
         * sustainedThroughputMbps, stabilityPenalty, and measuredAtMs —
         * the structural shape (workers, chunk sizes, supportsRangeRequests) is immutable.
         */
        val LOCKED_PREMIUMIZE = CapabilityEnvelope(
            maxSafeUrgentWorkers = 2,
            maxSafePrefetchWorkers = 1,
            maxSafeUrgentChunkBytes = 16_777_216L,
            maxSafePrefetchChunkBytes = 16_777_216L,
            sustainedThroughputMbps = 0.0,
            stabilityPenalty = 0.0,
            supportsRangeRequests = true,
            measuredAtMs = 0L
        )

        /**
         * Returns the locked [CapabilityEnvelope] for the given provider key, or null if the
         * provider has no locked shape. Provider keys match benchmark storage
         * keys: "real_debrid", "premiumize", "torbox", "easy_debrid".
         */
        fun lockedFor(provider: String): CapabilityEnvelope? = when (provider) {
            "real_debrid" -> LOCKED_REAL_DEBRID
            "premiumize" -> LOCKED_PREMIUMIZE
            else -> null
        }

        fun fromJson(json: String): CapabilityEnvelope? {
            return try {
                val obj = JsonParser.parseString(json).asJsonObject
                val maxSafeUrgentWorkers = obj.get("maxSafeUrgentWorkers")?.asInt ?: return null
                val maxSafePrefetchWorkers = obj.get("maxSafePrefetchWorkers")?.asInt ?: return null
                val maxSafeUrgentChunkBytes = obj.get("maxSafeUrgentChunkBytes")?.asLong ?: return null
                val maxSafePrefetchChunkBytes = obj.get("maxSafePrefetchChunkBytes")?.asLong ?: return null
                val sustainedThroughputMbps = obj.get("sustainedThroughputMbps")?.asDouble ?: return null
                val measuredAtMs = obj.get("measuredAtMs")?.asLong ?: return null
                val p10ThroughputMbps = obj.get("p10ThroughputMbps")?.takeIf { !it.isJsonNull }?.asDouble
                val seekTtfbP50Ms = obj.get("seekTtfbP50Ms")?.takeIf { !it.isJsonNull }?.asLong
                val stabilityPenalty = obj.get("stabilityPenalty")?.asDouble ?: 0.0
                val supportsRangeRequests = obj.get("supportsRangeRequests")?.asBoolean ?: true
                CapabilityEnvelope(
                    maxSafeUrgentWorkers = maxSafeUrgentWorkers,
                    maxSafePrefetchWorkers = maxSafePrefetchWorkers,
                    maxSafeUrgentChunkBytes = maxSafeUrgentChunkBytes,
                    maxSafePrefetchChunkBytes = maxSafePrefetchChunkBytes,
                    sustainedThroughputMbps = sustainedThroughputMbps,
                    p10ThroughputMbps = p10ThroughputMbps,
                    seekTtfbP50Ms = seekTtfbP50Ms,
                    stabilityPenalty = stabilityPenalty,
                    supportsRangeRequests = supportsRangeRequests,
                    measuredAtMs = measuredAtMs
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    fun toJson(): String {
        return JsonObject().apply {
            addProperty("maxSafeUrgentWorkers", maxSafeUrgentWorkers)
            addProperty("maxSafePrefetchWorkers", maxSafePrefetchWorkers)
            addProperty("maxSafeUrgentChunkBytes", maxSafeUrgentChunkBytes)
            addProperty("maxSafePrefetchChunkBytes", maxSafePrefetchChunkBytes)
            addProperty("sustainedThroughputMbps", sustainedThroughputMbps)
            p10ThroughputMbps?.let { addProperty("p10ThroughputMbps", it) }
            seekTtfbP50Ms?.let { addProperty("seekTtfbP50Ms", it) }
            addProperty("stabilityPenalty", stabilityPenalty)
            addProperty("supportsRangeRequests", supportsRangeRequests)
            addProperty("measuredAtMs", measuredAtMs)
        }.toString()
    }

    val totalWorkers: Int get() = maxSafeUrgentWorkers + maxSafePrefetchWorkers

    val isMeasured: Boolean get() = measuredAtMs > 0L

    /**
     * Returns true if [other] has the same structural shape as this envelope.
     * Only compares shape fields (workers, chunk sizes, supportsRangeRequests);
     * measurement fields (sustainedThroughputMbps, stabilityPenalty, measuredAtMs) are ignored.
     */
    fun matchesLockedShape(other: CapabilityEnvelope): Boolean =
        maxSafeUrgentWorkers == other.maxSafeUrgentWorkers &&
        maxSafePrefetchWorkers == other.maxSafePrefetchWorkers &&
        maxSafeUrgentChunkBytes == other.maxSafeUrgentChunkBytes &&
        maxSafePrefetchChunkBytes == other.maxSafePrefetchChunkBytes &&
        supportsRangeRequests == other.supportsRangeRequests
}
