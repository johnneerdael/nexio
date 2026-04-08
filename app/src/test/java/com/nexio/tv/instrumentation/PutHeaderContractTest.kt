package com.nexio.tv.instrumentation

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M6 — contract test for [PayloadBuilder.putHeader].
 *
 * Renders a known [SessionHeader] via the writer and parses the resulting
 * JSON fragment back into a [com.google.gson.JsonObject], then asserts the
 * complete set of expected keys is present. Acts as a single-source-of-truth
 * key list shared with [ParsedSessionHeader] and the spec.
 *
 * This test catches:
 *  - accidental removal of a put-call from `putHeader` (the key disappears
 *    from the JSON output, the assertion fails)
 *  - rename of a key on the writer side without an update to the
 *    matching [SessionHeaderKeys] entry (compile failure, not a runtime
 *    failure — covered by the type system)
 *
 * It does **not** catch the inverse drift: adding a new field to
 * [SessionHeader] without an accompanying put-call. That class of drift
 * needs reflection-based introspection of `SessionHeader::class.memberProperties`,
 * which is fragile across the four nested data classes (FactoryArgs,
 * PolicySnapshot, ClientIdentitySnapshot, DeviceProvenance). The pragmatic
 * mitigation is the explicit key list below: any new field added to
 * [SessionHeader] must also be added here, which the code reviewer can
 * verify in the same diff.
 */
class PutHeaderContractTest {

    @Test
    fun putHeaderEmitsAllExpectedKeys() {
        val header = SessionHeaderFixture.build(
            sessionId = "contract-test",
            startedAtNanos = 42L,
            assetKeyHash = "deadbeefcafe",
            envelopePresent = true,
            specializationState = "confirmed",
        )
        val record = TraceRecord.obtain(EventFamily.SESSION, "playback_session_started")
        try {
            PayloadBuilder(record).putHeader(header)

            // The payload buffer is a sequence of `,"k":v` fragments without
            // an outer brace. Wrap it for gson, dropping the leading comma.
            val rawPayload = record.payloadBuffer.toString()
            assertTrue("payload must not be empty", rawPayload.isNotEmpty())
            val wrapped = "{${rawPayload.removePrefix(",")}}"
            val obj = JsonParser.parseString(wrapped).asJsonObject

            for (key in EXPECTED_KEYS) {
                assertTrue("missing key in putHeader output: $key", obj.has(key))
            }
        } finally {
            record.recycle()
        }
    }

    @Test
    fun putHeaderRoundTripsThroughParsedSessionHeader() {
        // End-to-end check: the writer's output is consumable by the offline
        // reader for the subset of fields ParsedSessionHeader cares about.
        val header = SessionHeaderFixture.build(
            sessionId = "round-trip",
            envelopePresent = true,
            specializationState = "confirmed",
            specializationMismatchReason = null,
        )
        val record = TraceRecord.obtain(EventFamily.SESSION, "playback_session_started")
        try {
            PayloadBuilder(record).putHeader(header)
            val wrapped = "{${record.payloadBuffer.toString().removePrefix(",")}}"
            val parsed = ParsedSessionHeader.fromJson(JsonParser.parseString(wrapped).asJsonObject)

            // sessionId is read from the `sessionId` field on the started event
            // (not the per-event `sid` envelope, which lives one level up).
            assertEquals("round-trip", parsed.sessionId)
            assertEquals("prds", parsed.branch)
            assertTrue(parsed.envelopePresent)
            assertEquals("confirmed", parsed.specializationState)
            assertNull(parsed.specializationMismatchReason)
            // Default fixture uses `source = "fallback"` on the policy snapshot.
            assertEquals("fallback", parsed.policySource)
        } finally {
            record.recycle()
        }
    }

    companion object {
        /**
         * The complete set of JSON keys [PayloadBuilder.putHeader] must emit
         * for every session-started event. Adding a field to [SessionHeader]
         * requires adding the corresponding key here AND a put-call in
         * [PayloadBuilder.putHeader]; the test enforces the matching pair.
         */
        private val EXPECTED_KEYS: List<String> = listOf(
            // Identity / lifecycle
            SessionHeaderKeys.SESSION_ID,
            SessionHeaderKeys.STARTED_AT_NANOS,
            SessionHeaderKeys.ASSET_KEY_HASH,
            SessionHeaderKeys.SERVICE_KEY,
            SessionHeaderKeys.PROVIDER,
            SessionHeaderKeys.BENCHMARK_RESULT_ID,
            SessionHeaderKeys.BENCHMARK_SOURCE,
            // Envelope / runtime hints
            SessionHeaderKeys.ENVELOPE_PRESENT,
            SessionHeaderKeys.RUNTIME_HINTS_PRESENT,
            // Specialization provenance
            SessionHeaderKeys.SPECIALIZATION_STATE,
            SessionHeaderKeys.HINT_SERVICE_KEY,
            SessionHeaderKeys.HINT_HOST_SCOPE,
            SessionHeaderKeys.HINT_TRANSPORT_CLASS,
            SessionHeaderKeys.HINT_AGE_MS,
            SessionHeaderKeys.HINT_FRESHNESS_BAND,
            SessionHeaderKeys.SPECIALIZATION_MISMATCH_REASON,
            SessionHeaderKeys.OBSERVED_HOST_SCOPE,
            SessionHeaderKeys.OBSERVED_TRANSPORT_CLASS,
            // Branch / cache
            SessionHeaderKeys.BRANCH,
            SessionHeaderKeys.CACHE_ACTIVE,
            SessionHeaderKeys.WARM_AHEAD_FACTORY,
            // FactoryArgs
            SessionHeaderKeys.ACTIVE_CHUNK_BYTES,
            SessionHeaderKeys.PARALLEL_CONNECTIONS,
            SessionHeaderKeys.KEEP_BEHIND_BYTES,
            SessionHeaderKeys.BOOTSTRAP_BYTES,
            // PolicySnapshot
            SessionHeaderKeys.POLICY_URGENT_WORKERS,
            SessionHeaderKeys.POLICY_PREFETCH_WORKERS,
            SessionHeaderKeys.POLICY_URGENT_CHUNK_BYTES,
            SessionHeaderKeys.POLICY_PREFETCH_CHUNK_BYTES,
            SessionHeaderKeys.POLICY_SOURCE,
            // ClientIdentitySnapshot
            SessionHeaderKeys.PLAYBACK_CLIENT_HASH,
            SessionHeaderKeys.DISPATCHER_MAX_REQUESTS,
            SessionHeaderKeys.DISPATCHER_MAX_REQUESTS_PER_HOST,
            SessionHeaderKeys.DISPATCHER_QUEUED_CALLS,
            SessionHeaderKeys.DISPATCHER_RUNNING_CALLS,
            SessionHeaderKeys.CONNECTION_POOL_IDLE_COUNT,
            SessionHeaderKeys.CONNECTION_POOL_TOTAL_COUNT,
            SessionHeaderKeys.CALL_TIMEOUT_MS,
            SessionHeaderKeys.READ_TIMEOUT_MS,
            SessionHeaderKeys.WRITE_TIMEOUT_MS,
            SessionHeaderKeys.CONNECT_TIMEOUT_MS,
            // DeviceProvenance
            SessionHeaderKeys.DEVICE_MODEL,
            SessionHeaderKeys.DEVICE_MANUFACTURER,
            SessionHeaderKeys.ANDROID_RELEASE,
            SessionHeaderKeys.ANDROID_SDK_INT,
            SessionHeaderKeys.APP_VERSION_NAME,
            SessionHeaderKeys.APP_VERSION_CODE,
            SessionHeaderKeys.GIT_SHA,
            SessionHeaderKeys.MEMORY_CLASS,
            SessionHeaderKeys.LARGE_MEMORY_CLASS,
            SessionHeaderKeys.IS_LOW_RAM_DEVICE,
            SessionHeaderKeys.NETWORK_TYPE,
            SessionHeaderKeys.NETWORK_TRANSPORT_HASH,
        )
    }
}
