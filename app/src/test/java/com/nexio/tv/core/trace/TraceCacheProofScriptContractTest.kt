package com.nexio.tv.core.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempFile
import kotlin.io.path.writeText

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

    @Test
    fun `valid trace prints header and sorted rows`() {
        val trace = """
            {"eventType":"runtime.operation_start","payload":{"runtimeOperationId":"op-b","provider":"real-debrid","apiShapeId":"stream","operationKey":"stream:b","cacheKey":"cache-b"}}
            {"eventType":"runtime.cache_decision","payload":{"runtimeOperationId":"op-b","decision":"MISS_THEN_NETWORK","networkSuppressed":false}}
            {"eventType":"http.request","payload":{"runtimeOperationId":"op-b"}}
            {"eventType":"runtime.operation_start","payload":{"runtimeOperationId":"op-a","provider":"trakt","apiShapeId":"sync","operationKey":"sync:a","cacheKey":"cache-a"}}
            {"eventType":"runtime.cache_decision","payload":{"runtimeOperationId":"op-a","decision":"HIT","networkSuppressed":true}}
        """.trimIndent()

        val result = runScript(trace)

        assertEquals(result.stderr, 0, result.exitCode)
        assertEquals(
            listOf(
                "runtimeOperationId\tprovider\tapiShapeId\toperationKey\tcacheDecision\tnetworkSuppressed\thttpRequestCount\tcacheKey",
                "op-a\ttrakt\tsync\tsync:a\tHIT\ttrue\t0\tcache-a",
                "op-b\treal-debrid\tstream\tstream:b\tMISS_THEN_NETWORK\tfalse\t1\tcache-b",
                "",
                "MISS_THEN_NETWORK",
                "runtimeOperationId\tprovider\tapiShapeId\toperationKey\thttpRequestCount\tcacheKey",
                "op-b\treal-debrid\tstream\tstream:b\t1\tcache-b"
            ),
            result.stdout.lines()
        )
    }

    @Test
    fun `hit with suppressed network and http request exits with violation`() {
        val trace = """
            {"eventType":"runtime.cache_decision","payload":{"runtimeOperationId":"op-hit","decision":"HIT","networkSuppressed":true}}
            {"eventType":"http.request","payload":{"runtimeOperationId":"op-hit"}}
        """.trimIndent()

        val result = runScript(trace)

        assertEquals(1, result.exitCode)
        assertTrue(result.stderr.contains("Cache/network proof violations:"))
        assertTrue(result.stderr.contains("op-hit: HIT suppressed network but observed 1 http.request event(s)"))
    }

    @Test
    fun `malformed event shape exits with input error without traceback`() {
        val result = runScript("[]")

        assertEquals(2, result.exitCode)
        assertTrue(result.stderr.contains("line 1: event must be an object"))
        assertFalse(result.stderr.contains("Traceback"))
    }

    @Test
    fun `malformed payload shape exits with input error without traceback`() {
        val result = runScript("""{ "eventType":"runtime.cache_decision", "payload": [] }""")

        assertEquals(2, result.exitCode)
        assertTrue(result.stderr.contains("line 1: payload must be an object"))
        assertFalse(result.stderr.contains("Traceback"))
    }

    private fun runScript(trace: String): ScriptResult {
        val traceFile = createTempFile(prefix = "trace-cache-proof", suffix = ".jsonl")
        traceFile.writeText(trace)

        val process = ProcessBuilder("python3", "scripts/trace-cache-proof.py", traceFile.toAbsolutePath().toString())
            .directory(File("."))
            .start()

        val stdout = process.inputStream.bufferedReader().readText().trimEnd()
        val stderr = process.errorStream.bufferedReader().readText().trimEnd()
        return ScriptResult(process.waitFor(), stdout, stderr)
    }

    private data class ScriptResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    )
}
