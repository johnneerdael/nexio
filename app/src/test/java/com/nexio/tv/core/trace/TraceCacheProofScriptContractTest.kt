package com.nexio.tv.core.trace

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TraceCacheProofScriptContractTest {
    @Test
    fun `trace cache proof script exposes required proof fields`() {
        val script = File("scripts/trace-cache-proof.py")

        assertTrue("Expected trace cache proof script to exist.", script.isFile)

        val source = script.readText()
        listOf(
            "runtimeOperationId",
            "cacheDecision",
            "networkSuppressed",
            "httpRequestCount",
            "MISS_THEN_NETWORK"
        ).forEach { required ->
            assertTrue("Expected script to contain $required.", source.contains(required))
        }
    }
}
