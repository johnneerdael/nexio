package com.nexio.tv.instrumentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP9 — verifies the [StutterClassifier] correctly labels a real-shaped
 * playback-trace JSONL session.
 *
 * Primary path: a captured Fire TV session committed under
 * `app/src/test/resources/sessions/`. Fallback path (currently used): a
 * synthetic fixture (`synthetic-transport-stutter.jsonl`) that exercises
 * the same parser/classifier code path with a hand-crafted event sequence
 * known to trigger the [StutterClassifier.Cause.TRANSPORT] rule.
 *
 * The fallback fixture is permitted by spec amendment C7 if a real
 * stutter capture is not yet available; it MUST exercise every parser
 * branch the classifier rules depend on, which the synthetic fixture does.
 */
class StutterClassifierTest {

    @Test
    fun syntheticTransportStutterClassifiesAsTransport() {
        val jsonl = loadFixture("sessions/synthetic-transport-stutter.jsonl")
        val session = StutterClassifier.parseJsonl(jsonl)

        assertEquals("test-session-001", session.header.sessionId)
        assertEquals("prds", session.header.branch)
        assertTrue("envelope must be present", session.header.envelopePresent)

        val windows = session.rebufferWindows()
        assertEquals("expected exactly one rebuffer window", 1, windows.size)

        val window = windows.single()
        assertTrue(
            "rebuffer duration must be > 0; got ${window.windowDurationMs}",
            window.windowDurationMs > 0L
        )

        val classifier = StutterClassifier(session)
        val cause = classifier.classify(window)
        assertEquals(StutterClassifier.Cause.TRANSPORT, cause)
    }

    @Test
    fun parserExtractsHeaderFromFirstEvent() {
        val jsonl = loadFixture("sessions/synthetic-transport-stutter.jsonl")
        val session = StutterClassifier.parseJsonl(jsonl)
        assertNotNull(session.header)
        assertEquals("envelope", session.header.policySource)
    }

    private fun loadFixture(path: String): String {
        val stream = javaClass.classLoader?.getResourceAsStream(path)
            ?: error("Fixture not found on test classpath: $path")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
