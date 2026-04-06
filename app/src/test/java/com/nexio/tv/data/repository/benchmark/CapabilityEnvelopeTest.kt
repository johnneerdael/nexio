package com.nexio.tv.data.repository.benchmark

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityEnvelopeTest {

    @Test
    fun `construction with typical values produces correct derived properties`() {
        val envelope = CapabilityEnvelope(
            maxSafeUrgentWorkers = 3,
            maxSafePrefetchWorkers = 2,
            maxSafeUrgentChunkBytes = 4L * 1024L * 1024L,
            maxSafePrefetchChunkBytes = 12L * 1024L * 1024L,
            sustainedThroughputMbps = 120.0,
            measuredAtMs = 1_000_000L
        )

        assertEquals(5, envelope.totalWorkers)
        assertTrue(envelope.isMeasured)
    }

    @Test
    fun `DEFAULT envelope has expected conservative values and isMeasured is false`() {
        val d = CapabilityEnvelope.DEFAULT

        assertEquals(2, d.maxSafeUrgentWorkers)
        assertEquals(1, d.maxSafePrefetchWorkers)
        assertEquals(8L * 1024L * 1024L, d.maxSafeUrgentChunkBytes)
        assertEquals(16L * 1024L * 1024L, d.maxSafePrefetchChunkBytes)
        assertEquals(50.0, d.sustainedThroughputMbps, 0.0)
        assertEquals(0.5, d.stabilityPenalty, 0.0)
        assertEquals(0L, d.measuredAtMs)
        assertEquals(3, d.totalWorkers)
        assertFalse(d.isMeasured)
    }

    @Test
    fun `toJson produces valid JSON and fromJson round-trips correctly`() {
        val envelope = CapabilityEnvelope(
            maxSafeUrgentWorkers = 3,
            maxSafePrefetchWorkers = 2,
            maxSafeUrgentChunkBytes = 4L * 1024L * 1024L,
            maxSafePrefetchChunkBytes = 12L * 1024L * 1024L,
            sustainedThroughputMbps = 120.0,
            p10ThroughputMbps = 95.0,
            seekTtfbP50Ms = 180L,
            stabilityPenalty = 0.1,
            supportsRangeRequests = true,
            measuredAtMs = 1_700_000_000_000L
        )

        val json = envelope.toJson()
        val parsed = JsonParser.parseString(json).asJsonObject

        assertEquals(3, parsed.get("maxSafeUrgentWorkers").asInt)
        assertEquals(2, parsed.get("maxSafePrefetchWorkers").asInt)
        assertEquals(4L * 1024L * 1024L, parsed.get("maxSafeUrgentChunkBytes").asLong)
        assertEquals(12L * 1024L * 1024L, parsed.get("maxSafePrefetchChunkBytes").asLong)
        assertEquals(120.0, parsed.get("sustainedThroughputMbps").asDouble, 0.0)
        assertEquals(95.0, parsed.get("p10ThroughputMbps").asDouble, 0.0)
        assertEquals(180L, parsed.get("seekTtfbP50Ms").asLong)
        assertEquals(0.1, parsed.get("stabilityPenalty").asDouble, 0.0)
        assertTrue(parsed.get("supportsRangeRequests").asBoolean)
        assertEquals(1_700_000_000_000L, parsed.get("measuredAtMs").asLong)

        val restored = CapabilityEnvelope.fromJson(json)
        assertNotNull(restored)
        assertEquals(envelope.maxSafeUrgentWorkers, restored!!.maxSafeUrgentWorkers)
        assertEquals(envelope.maxSafePrefetchWorkers, restored.maxSafePrefetchWorkers)
        assertEquals(envelope.maxSafeUrgentChunkBytes, restored.maxSafeUrgentChunkBytes)
        assertEquals(envelope.maxSafePrefetchChunkBytes, restored.maxSafePrefetchChunkBytes)
        assertEquals(envelope.sustainedThroughputMbps, restored.sustainedThroughputMbps, 0.0)
        assertEquals(envelope.p10ThroughputMbps, restored.p10ThroughputMbps)
        assertEquals(envelope.seekTtfbP50Ms, restored.seekTtfbP50Ms)
        assertEquals(envelope.stabilityPenalty, restored.stabilityPenalty, 0.0)
        assertEquals(envelope.supportsRangeRequests, restored.supportsRangeRequests)
        assertEquals(envelope.measuredAtMs, restored.measuredAtMs)
    }

    @Test
    fun `fromJson returns null for malformed JSON`() {
        assertNull(CapabilityEnvelope.fromJson("not json at all"))
        assertNull(CapabilityEnvelope.fromJson("{invalid}"))
        assertNull(CapabilityEnvelope.fromJson(""))
    }

    @Test
    fun `fromJson returns null when required fields are missing`() {
        val base = mapOf(
            "maxSafeUrgentWorkers" to "2",
            "maxSafePrefetchWorkers" to "1",
            "maxSafeUrgentChunkBytes" to "8388608",
            "maxSafePrefetchChunkBytes" to "16777216",
            "sustainedThroughputMbps" to "50.0",
            "measuredAtMs" to "1000000"
        )
        val requiredKeys = listOf(
            "maxSafeUrgentWorkers",
            "maxSafePrefetchWorkers",
            "maxSafeUrgentChunkBytes",
            "maxSafePrefetchChunkBytes",
            "sustainedThroughputMbps",
            "measuredAtMs"
        )

        for (missingKey in requiredKeys) {
            val withoutKey = base.filter { it.key != missingKey }
            val json = withoutKey.entries.joinToString(",", prefix = "{", postfix = "}") {
                "\"${it.key}\": ${it.value}"
            }
            assertNull("Expected null when $missingKey is missing", CapabilityEnvelope.fromJson(json))
        }
    }

    @Test
    fun `optional fields round-trip as null when not set`() {
        val envelope = CapabilityEnvelope(
            maxSafeUrgentWorkers = 2,
            maxSafePrefetchWorkers = 1,
            maxSafeUrgentChunkBytes = 8L * 1024L * 1024L,
            maxSafePrefetchChunkBytes = 16L * 1024L * 1024L,
            sustainedThroughputMbps = 50.0,
            measuredAtMs = 999L
        )

        val json = envelope.toJson()
        val parsed = JsonParser.parseString(json).asJsonObject

        assertFalse(parsed.has("p10ThroughputMbps"))
        assertFalse(parsed.has("seekTtfbP50Ms"))

        val restored = CapabilityEnvelope.fromJson(json)
        assertNotNull(restored)
        assertNull(restored!!.p10ThroughputMbps)
        assertNull(restored.seekTtfbP50Ms)
    }
}
