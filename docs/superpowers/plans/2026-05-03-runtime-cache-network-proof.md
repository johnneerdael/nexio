# Runtime Cache Network Proof Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make provider metadata cache-vs-network behavior measurable on device, so fresh cached runtime data can be proven to avoid new provider network calls.

**Architecture:** Keep the proof inside the existing `IntegrationRuntime` and trace pipeline. Do not add a parallel metadata path, cache path, router path, or hydration path; improve the trace session id, logcat fields, validator rules, and local trace summarizer around existing `runtime.cache_decision` and `http.request` events.

**Tech Stack:** Kotlin, Hilt, OkHttp, Android logcat, JSONL runtime traces, JUnit/Robolectric, OpenSpec.

---

## File Structure

- Modify `app/src/main/java/com/nexio/tv/core/trace/RuntimeTraceSink.kt`
  - Add an optional `activeTraceSessionId()` contract used by runtime emitters.
- Modify `app/src/main/java/com/nexio/tv/core/trace/FileRuntimeTraceSink.kt`
  - Return its file trace session id.
- Modify `app/src/main/java/com/nexio/tv/core/trace/CompositeRuntimeTraceSink.kt`
  - Return the first active session id from child sinks.
- Modify `app/src/main/java/com/nexio/tv/core/di/RuntimeTraceModule.kt`
  - Make `ActiveSessionRuntimeTraceSink` expose `TraceSessionManager.activeSession()?.traceSessionId`.
- Modify `app/src/main/java/com/nexio/tv/core/integration/DefaultIntegrationRuntime.kt`
  - Use `traceSink.activeTraceSessionId()` instead of casting to `FileRuntimeTraceSink`.
- Modify `app/src/main/java/com/nexio/tv/core/trace/LogcatRuntimeTraceSink.kt`
  - Print cache proof fields in `Nexio.IntRuntime`.
- Modify `app/src/main/java/com/nexio/tv/core/trace/TraceValidationRules.kt`
  - Add suppressed stale-hit no-network validation.
- Modify `app/src/main/java/com/nexio/tv/core/trace/TraceValidationReport.kt`
  - Add per-operation cache proof entries.
- Modify `app/src/main/java/com/nexio/tv/core/trace/RuntimeTraceValidator.kt`
  - Build cache proof entries from runtime and HTTP events.
- Modify `app/src/main/java/com/nexio/tv/core/trace/TraceSummaryGenerator.kt`
  - Include cache proof entries in JSON and Markdown summaries.
- Add `scripts/trace-cache-proof.py`
  - Local JSONL analyzer for pulled device traces.
- Add `docs/debugging/runtime-cache-network-proof.md`
  - Repeatable rooted/profileable ADB procedure.
- Modify tests:
  - `app/src/test/java/com/nexio/tv/core/trace/RuntimeTraceValidatorRealEmissionTest.kt`
  - `app/src/test/java/com/nexio/tv/core/trace/LogcatRuntimeTraceSinkTest.kt`
  - `app/src/test/java/com/nexio/tv/core/trace/TraceValidationRulesTest.kt`
  - `app/src/test/java/com/nexio/tv/core/trace/RuntimeTraceValidatorTest.kt`
  - `app/src/test/java/com/nexio/tv/core/trace/TraceBundleGoldenTest.kt`

---

### Task 1: OpenSpec Contract

**Files:**
- Created: `openspec/changes/prove-runtime-cache-network-decisions/proposal.md`
- Created: `openspec/changes/prove-runtime-cache-network-decisions/tasks.md`
- Created: `openspec/changes/prove-runtime-cache-network-decisions/specs/integration-runtime/spec.md`

- [ ] **Step 1: Validate the OpenSpec scaffold**

Run:

```bash
openspec validate prove-runtime-cache-network-decisions --strict
```

Expected:

```text
Change 'prove-runtime-cache-network-decisions' is valid
```

- [ ] **Step 2: Commit the plan/spec scaffold**

Run:

```bash
git add openspec/changes/prove-runtime-cache-network-decisions docs/superpowers/plans/2026-05-03-runtime-cache-network-proof.md
git commit -m "docs: plan runtime cache network proof"
```

Expected:

```text
[codex/integration-runtime-phase-a ...] docs: plan runtime cache network proof
```

---

### Task 2: Active Trace Session Id Propagation

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/trace/RuntimeTraceSink.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/trace/FileRuntimeTraceSink.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/trace/CompositeRuntimeTraceSink.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/di/RuntimeTraceModule.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/integration/DefaultIntegrationRuntime.kt`
- Test: `app/src/test/java/com/nexio/tv/core/trace/RuntimeTraceValidatorRealEmissionTest.kt`

- [ ] **Step 1: Write the failing real-emission test**

Add this test to `RuntimeTraceValidatorRealEmissionTest`:

```kotlin
@Test
fun `composite production trace sink records runtime cache decisions with active session id`() = runTest {
    val tracesRoot = tmp.newFolder("composite-traces")
    val gson = Gson()
    val manager = TraceSessionManager(
        tracesRoot = tracesRoot,
        gson = gson,
        clock = { 1_700_000_123_000L },
        buildInfo = TraceBuildInfo(
            appVersion = "1.0",
            buildType = "releaseProfileable",
            gitSha = "cache-proof",
            deviceModel = "UGOOS-AM6",
            androidVersion = "9"
        )
    )
    manager.start(TraceMode.SAFE_METADATA_RUNTIME, activeProfileHash = "profile")
    val session = checkNotNull(manager.activeSession())
    val compositeSink = CompositeRuntimeTraceSink(
        listOf(
            manager.activeSink(),
            LogcatRuntimeTraceSink(gate = object : LogcatChannelGate {
                override fun isEnabled(channel: LogcatTraceChannel): Boolean = false
            })
        )
    )

    val registry = defaultIntegrationPolicyRegistry()
    val cacheStore = ByteArrayIntegrationCacheStore()
    val runtime = DefaultIntegrationRuntime(
        cacheStore = cacheStore,
        requestGate = ProviderRequestGate(registry),
        backoffManager = IntegrationBackoffManager(InMemoryIntegrationProviderBackoffDao()),
        singleFlight = IntegrationSingleFlight(),
        playbackGate = IntegrationPlaybackGate(),
        registry = registry,
        auditSink = RecordingIntegrationAuditSink(),
        traceSink = compositeSink
    )
    val spec = IntegrationSpec(
        provider = IntegrationProvider.KITSU,
        apiShapeId = "kitsu.anime.core",
        operationKey = "kitsu.fetch_enrichment",
        cacheKey = "kitsu:series:12:enrichment:policy:test",
        scope = IntegrationScope.GlobalContent,
        workClass = IntegrationWorkClass.USER_VISIBLE,
        cachePolicy = IntegrationCachePolicy.CacheFirst(
            ttlMs = 24L * 60L * 60L * 1000L,
            staleAfterExpiryMs = 7L * 24L * 60L * 60L * 1000L
        ),
        codec = StringIntegrationCodec,
        load = { IntegrationLoadResult.Success("one-piece") }
    )

    assertTrue(runtime.get(spec) is IntegrationFetchResult.Updated)
    assertTrue(runtime.get(spec) is IntegrationFetchResult.Fresh)
    manager.stop()

    val eventsFile = File(File(tracesRoot, session.traceSessionId), "trace-events.jsonl")
    assertTrue("trace-events.jsonl exists", eventsFile.isFile)
    val events = eventsFile.readLines()
        .filter { it.isNotBlank() }
        .map { gson.fromJson(it, TraceEventEnvelope::class.java) as TraceEventEnvelope<*> }
    val cacheHit = events.single {
        it.eventType == "runtime.cache_decision" &&
            (it.payload as Map<*, *>)["decision"] == "HIT"
    }
    assertEquals(session.traceSessionId, cacheHit.traceSessionId)
    assertEquals(true, (cacheHit.payload as Map<*, *>)["networkSuppressed"])
}
```

Also add this test helper near the bottom of the file:

```kotlin
private object StringIntegrationCodec : IntegrationCodec<String> {
    override val mimeType: String = "text/plain"
    override fun encode(value: String): ByteArray = value.toByteArray(Charsets.UTF_8)
    override fun decode(bytes: ByteArray): String = bytes.toString(Charsets.UTF_8)
}
```

- [ ] **Step 2: Run the failing test**

Run:

```bash
./gradlew :app:testUniversalReleaseProfileableUnitTest --tests "com.nexio.tv.core.trace.RuntimeTraceValidatorRealEmissionTest.composite production trace sink records runtime cache decisions with active session id"
```

Expected before implementation:

```text
FAIL
NoSuchElementException
```

or:

```text
FAIL
trace-events.jsonl exists
```

The failure proves runtime file traces are not recording the active composite-sink session correctly.

- [ ] **Step 3: Implement active session id propagation**

In `RuntimeTraceSink.kt`, replace the interface with:

```kotlin
interface RuntimeTraceSink {
    fun emit(event: TraceEventEnvelope<*>)
    fun eventsWritten(): Long
    fun eventsDropped(): Long
    fun activeTraceSessionId(): String? = null
}
```

In `FileRuntimeTraceSink.kt`, add:

```kotlin
override fun activeTraceSessionId(): String = sessionId
```

In `CompositeRuntimeTraceSink.kt`, add:

```kotlin
override fun activeTraceSessionId(): String? =
    sinks.firstNotNullOfOrNull { it.activeTraceSessionId() }
```

In `RuntimeTraceModule.kt`, update `ActiveSessionRuntimeTraceSink`:

```kotlin
override fun activeTraceSessionId(): String? =
    manager.activeSession()?.traceSessionId
```

In `DefaultIntegrationRuntime.kt`, change `buildTraceContext`:

```kotlin
val sessionId = traceSink.activeTraceSessionId() ?: "noop"
```

and remove the unused `FileRuntimeTraceSink` import.

- [ ] **Step 4: Run the test to verify it passes**

Run:

```bash
./gradlew :app:testUniversalReleaseProfileableUnitTest --tests "com.nexio.tv.core.trace.RuntimeTraceValidatorRealEmissionTest.composite production trace sink records runtime cache decisions with active session id"
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 5: Commit**

Run:

```bash
git add app/src/main/java/com/nexio/tv/core/trace/RuntimeTraceSink.kt app/src/main/java/com/nexio/tv/core/trace/FileRuntimeTraceSink.kt app/src/main/java/com/nexio/tv/core/trace/CompositeRuntimeTraceSink.kt app/src/main/java/com/nexio/tv/core/di/RuntimeTraceModule.kt app/src/main/java/com/nexio/tv/core/integration/DefaultIntegrationRuntime.kt app/src/test/java/com/nexio/tv/core/trace/RuntimeTraceValidatorRealEmissionTest.kt
git commit -m "fix(trace): preserve runtime session ids"
```

---

### Task 3: Cache-Proof Logcat Fields

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/trace/LogcatRuntimeTraceSink.kt`
- Test: `app/src/test/java/com/nexio/tv/core/trace/LogcatRuntimeTraceSinkTest.kt`

- [ ] **Step 1: Write failing logcat tests for every cache decision**

Replace the existing `cache_decision event writes to IntRuntime tag with decision and cacheKey` test with these tests:

```kotlin
@Test
fun `cache_decision fresh hit writes cache proof fields to IntRuntime tag`() {
    val sink = LogcatRuntimeTraceSink(allEnabled)
    sink.emit(envelope("runtime.cache_decision", mapOf(
        "runtimeOperationId" to "op-1",
        "provider" to "KITSU",
        "apiShapeId" to "kitsu.anime.characters",
        "operationKey" to "kitsu.anime_characters",
        "cacheKey" to "kitsu:series:12:anime_characters:v3",
        "decision" to "HIT",
        "reason" to "fresh-cache-hit",
        "networkSuppressed" to true,
        "ttlMs" to 604800000L,
        "staleWindowMs" to 2592000000L
    )))

    val msg = ShadowLog.getLogsForTag("Nexio.IntRuntime").first().msg
    assertTrue(msg.contains("t=runtime.cache_decision"))
    assertTrue(msg.contains("runtimeOperationId=op-1"))
    assertTrue(msg.contains("provider=KITSU"))
    assertTrue(msg.contains("apiShapeId=kitsu.anime.characters"))
    assertTrue(msg.contains("operationKey=kitsu.anime_characters"))
    assertTrue(msg.contains("decision=HIT"))
    assertTrue(msg.contains("reason=fresh-cache-hit"))
    assertTrue(msg.contains("networkSuppressed=true"))
    assertTrue(msg.contains("ttlMs=604800000"))
    assertTrue(msg.contains("staleWindowMs=2592000000"))
    assertTrue(msg.contains("cacheKey=kitsu:series:12:anime_characters:v3"))
}

@Test
fun `cache_decision miss then network writes network-required fields to IntRuntime tag`() {
    val sink = LogcatRuntimeTraceSink(allEnabled)
    sink.emit(envelope("runtime.cache_decision", mapOf(
        "runtimeOperationId" to "op-2",
        "provider" to "KITSU",
        "apiShapeId" to "kitsu.anime.core",
        "operationKey" to "kitsu.fetch_enrichment",
        "cacheKey" to "kitsu:series:12:enrichment:policy:test",
        "decision" to "MISS_THEN_NETWORK",
        "reason" to "cache-miss",
        "networkSuppressed" to false,
        "ttlMs" to 86400000L,
        "staleWindowMs" to 604800000L
    )))

    val msg = ShadowLog.getLogsForTag("Nexio.IntRuntime").first().msg
    assertTrue(msg.contains("t=runtime.cache_decision"))
    assertTrue(msg.contains("runtimeOperationId=op-2"))
    assertTrue(msg.contains("apiShapeId=kitsu.anime.core"))
    assertTrue(msg.contains("operationKey=kitsu.fetch_enrichment"))
    assertTrue(msg.contains("decision=MISS_THEN_NETWORK"))
    assertTrue(msg.contains("reason=cache-miss"))
    assertTrue(msg.contains("networkSuppressed=false"))
    assertTrue(msg.contains("ttlMs=86400000"))
    assertTrue(msg.contains("staleWindowMs=604800000"))
    assertTrue(msg.contains("cacheKey=kitsu:series:12:enrichment:policy:test"))
}

@Test
fun `cache_decision stale hit writes suppression fields to IntRuntime tag`() {
    val sink = LogcatRuntimeTraceSink(allEnabled)
    sink.emit(envelope("runtime.cache_decision", mapOf(
        "runtimeOperationId" to "op-3",
        "provider" to "KITSU",
        "apiShapeId" to "kitsu.anime.characters",
        "operationKey" to "kitsu.anime_characters",
        "cacheKey" to "kitsu:series:12:anime_characters:v3",
        "decision" to "STALE_HIT",
        "reason" to "stale-cache-hit-network-suppressed",
        "networkSuppressed" to true,
        "ttlMs" to 604800000L,
        "staleWindowMs" to 2592000000L
    )))

    val msg = ShadowLog.getLogsForTag("Nexio.IntRuntime").first().msg
    assertTrue(msg.contains("t=runtime.cache_decision"))
    assertTrue(msg.contains("runtimeOperationId=op-3"))
    assertTrue(msg.contains("apiShapeId=kitsu.anime.characters"))
    assertTrue(msg.contains("decision=STALE_HIT"))
    assertTrue(msg.contains("reason=stale-cache-hit-network-suppressed"))
    assertTrue(msg.contains("networkSuppressed=true"))
    assertTrue(msg.contains("ttlMs=604800000"))
    assertTrue(msg.contains("staleWindowMs=2592000000"))
    assertTrue(msg.contains("cacheKey=kitsu:series:12:anime_characters:v3"))
}

@Test
fun `cache_decision write records cache write fields to IntRuntime tag`() {
    val sink = LogcatRuntimeTraceSink(allEnabled)
    sink.emit(envelope("runtime.cache_decision", mapOf(
        "runtimeOperationId" to "op-4",
        "provider" to "KITSU",
        "apiShapeId" to "kitsu.anime.core",
        "operationKey" to "kitsu.fetch_enrichment",
        "cacheKey" to "kitsu:series:12:enrichment:policy:test",
        "decision" to "WRITE",
        "reason" to "network-response-cached",
        "networkSuppressed" to false,
        "ttlMs" to 86400000L,
        "staleWindowMs" to 604800000L
    )))

    val msg = ShadowLog.getLogsForTag("Nexio.IntRuntime").first().msg
    assertTrue(msg.contains("t=runtime.cache_decision"))
    assertTrue(msg.contains("runtimeOperationId=op-4"))
    assertTrue(msg.contains("apiShapeId=kitsu.anime.core"))
    assertTrue(msg.contains("operationKey=kitsu.fetch_enrichment"))
    assertTrue(msg.contains("decision=WRITE"))
    assertTrue(msg.contains("reason=network-response-cached"))
    assertTrue(msg.contains("networkSuppressed=false"))
    assertTrue(msg.contains("ttlMs=86400000"))
    assertTrue(msg.contains("staleWindowMs=604800000"))
    assertTrue(msg.contains("cacheKey=kitsu:series:12:enrichment:policy:test"))
}
```

- [ ] **Step 2: Run the failing test**

Run:

```bash
./gradlew :app:testUniversalReleaseProfileableUnitTest --tests "com.nexio.tv.core.trace.LogcatRuntimeTraceSinkTest.cache_decision fresh hit writes cache proof fields to IntRuntime tag" --tests "com.nexio.tv.core.trace.LogcatRuntimeTraceSinkTest.cache_decision miss then network writes network-required fields to IntRuntime tag" --tests "com.nexio.tv.core.trace.LogcatRuntimeTraceSinkTest.cache_decision stale hit writes suppression fields to IntRuntime tag" --tests "com.nexio.tv.core.trace.LogcatRuntimeTraceSinkTest.cache_decision write records cache write fields to IntRuntime tag"
```

Expected before implementation:

```text
FAIL
```

with missing `runtimeOperationId`, `apiShapeId`, `reason`, `networkSuppressed`, `ttlMs`, or `staleWindowMs`.

- [ ] **Step 3: Implement the logcat field expansion**

In `LogcatRuntimeTraceSink.kt`, replace the `runtime.cache_decision` block with:

```kotlin
"runtime.cache_decision" -> linkedMapOf(
    "runtimeOperationId" to payload["runtimeOperationId"],
    "provider" to payload["provider"],
    "apiShapeId" to payload["apiShapeId"],
    "operationKey" to payload["operationKey"],
    "decision" to payload["decision"],
    "reason" to payload["reason"],
    "networkSuppressed" to payload["networkSuppressed"],
    "ttlMs" to payload["ttlMs"],
    "staleWindowMs" to payload["staleWindowMs"],
    "cacheKey" to payload["cacheKey"]
)
```

- [ ] **Step 4: Run logcat sink tests**

Run:

```bash
./gradlew :app:testUniversalReleaseProfileableUnitTest --tests "com.nexio.tv.core.trace.LogcatRuntimeTraceSinkTest"
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 5: Commit**

Run:

```bash
git add app/src/main/java/com/nexio/tv/core/trace/LogcatRuntimeTraceSink.kt app/src/test/java/com/nexio/tv/core/trace/LogcatRuntimeTraceSinkTest.kt
git commit -m "chore(trace): expose cache proof fields in logcat"
```

---

### Task 4: Validator And Summary Cache Proof

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/trace/TraceValidationRules.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/trace/TraceValidationReport.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/trace/RuntimeTraceValidator.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/trace/TraceSummaryGenerator.kt`
- Test: `app/src/test/java/com/nexio/tv/core/trace/TraceValidationRulesTest.kt`
- Test: `app/src/test/java/com/nexio/tv/core/trace/RuntimeTraceValidatorTest.kt`
- Test: `app/src/test/java/com/nexio/tv/core/trace/TraceBundleGoldenTest.kt`

- [ ] **Step 1: Write failing validator tests**

Add to `TraceValidationRulesTest.kt`:

```kotlin
@Test
fun `SuppressedStaleCacheHitSuppressesNetwork fires when http_request follows suppressed stale hit for same op`() {
    val rule = TraceValidationRules.SuppressedStaleCacheHitSuppressesNetwork
    assertFires(rule, listOf(
        envelope("runtime.cache_decision", 1L, mapOf(
            "decision" to "STALE_HIT",
            "runtimeOperationId" to "op_stale",
            "networkSuppressed" to true
        )),
        envelope("http.request", 2L, mapOf("runtimeOperationId" to "op_stale"))
    ))
    assertSilent(rule, listOf(
        envelope("runtime.cache_decision", 1L, mapOf(
            "decision" to "STALE_HIT",
            "runtimeOperationId" to "op_after_miss",
            "networkSuppressed" to false
        )),
        envelope("http.request", 2L, mapOf("runtimeOperationId" to "op_after_miss"))
    ))
}
```

Add to `RuntimeTraceValidatorTest.kt`:

```kotlin
@Test
fun `validator reports per operation cache proof entries`() {
    val report = RuntimeTraceValidator().validate(sequenceOf(
        envelope("runtime.operation_start", 1L, mapOf(
            "runtimeOperationId" to "op_1",
            "provider" to "KITSU",
            "apiShapeId" to "kitsu.anime.characters",
            "operationKey" to "kitsu.anime_characters",
            "cacheKey" to "kitsu:series:12:anime_characters:v3"
        )),
        envelope("runtime.cache_decision", 2L, mapOf(
            "runtimeOperationId" to "op_1",
            "provider" to "KITSU",
            "apiShapeId" to "kitsu.anime.characters",
            "operationKey" to "kitsu.anime_characters",
            "cacheKey" to "kitsu:series:12:anime_characters:v3",
            "decision" to "HIT",
            "networkSuppressed" to true
        ))
    ))

    assertEquals(TraceVerdict.PASS, report.verdict)
    assertEquals(1, report.cacheProofs.size)
    assertEquals("op_1", report.cacheProofs.single().runtimeOperationId)
    assertEquals("KITSU", report.cacheProofs.single().provider)
    assertEquals("HIT", report.cacheProofs.single().cacheDecision)
    assertEquals(true, report.cacheProofs.single().networkSuppressed)
    assertEquals(0, report.cacheProofs.single().httpRequestCount)
}
```

- [ ] **Step 2: Run the failing tests**

Run:

```bash
./gradlew :app:testUniversalReleaseProfileableUnitTest --tests "com.nexio.tv.core.trace.TraceValidationRulesTest.SuppressedStaleCacheHitSuppressesNetwork fires when http_request follows suppressed stale hit for same op" --tests "com.nexio.tv.core.trace.RuntimeTraceValidatorTest.validator reports per operation cache proof entries"
```

Expected before implementation:

```text
FAIL
Unresolved reference: SuppressedStaleCacheHitSuppressesNetwork
```

and:

```text
FAIL
Unresolved reference: cacheProofs
```

- [ ] **Step 3: Add cache proof report models**

In `TraceValidationReport.kt`, add:

```kotlin
data class TraceCacheProofEntry(
    val runtimeOperationId: String,
    val provider: String?,
    val apiShapeId: String?,
    val operationKey: String?,
    val cacheKey: String?,
    val cacheDecision: String?,
    val networkSuppressed: Boolean?,
    val httpRequestCount: Long
)
```

and add to `TraceValidationReport`:

```kotlin
val cacheProofs: List<TraceCacheProofEntry> = emptyList()
```

Keep the default value so existing tests that directly construct `TraceValidationReport` remain source-compatible while implementation code can opt in to per-operation proof entries.

- [ ] **Step 4: Implement suppressed stale validation**

In `TraceValidationRules.kt`, add:

```kotlin
val SuppressedStaleCacheHitSuppressesNetwork: TraceValidationRule = object : TraceValidationRule {
    override val id = "SuppressedStaleCacheHitSuppressesNetwork"
    override fun apply(events: List<TraceEventEnvelope<*>>): List<TraceValidationFailure> {
        val failures = mutableListOf<TraceValidationFailure>()
        val suppressedStaleOps = mutableSetOf<String>()
        events.forEach { e ->
            val p = map(e)
            when (e.eventType) {
                "runtime.cache_decision" -> {
                    if (p["decision"] == "STALE_HIT" && p["networkSuppressed"] == true) {
                        (p["runtimeOperationId"] as? String)?.let { suppressedStaleOps += it }
                    }
                }
                "http.request" -> {
                    val opId = p["runtimeOperationId"] as? String
                    if (opId != null && opId in suppressedStaleOps) {
                        failures += fail(this, e, "http.request issued for op=$opId after suppressed stale cache HIT")
                    }
                }
            }
        }
        return failures
    }
}
```

Add it to `TraceValidationRules.ALL` after `FreshCacheHitSuppressesNetwork`.

- [ ] **Step 5: Build cache proof entries**

In `RuntimeTraceValidator.kt`, add:

```kotlin
private fun cacheProofs(events: List<TraceEventEnvelope<*>>): List<TraceCacheProofEntry> {
    val httpCounts = events
        .filter { it.eventType == "http.request" }
        .mapNotNull { e -> (e.payload as? Map<*, *>)?.get("runtimeOperationId") as? String }
        .groupingBy { it }
        .eachCount()

    return events
        .filter { it.eventType == "runtime.cache_decision" }
        .mapNotNull { e ->
            val payload = e.payload as? Map<*, *> ?: return@mapNotNull null
            val opId = payload["runtimeOperationId"] as? String ?: return@mapNotNull null
            TraceCacheProofEntry(
                runtimeOperationId = opId,
                provider = payload["provider"] as? String,
                apiShapeId = payload["apiShapeId"] as? String,
                operationKey = payload["operationKey"] as? String,
                cacheKey = payload["cacheKey"] as? String,
                cacheDecision = payload["decision"] as? String,
                networkSuppressed = payload["networkSuppressed"] as? Boolean,
                httpRequestCount = (httpCounts[opId] ?: 0).toLong()
            )
        }
}
```

Pass `cacheProofs = cacheProofs(list)` when building `TraceValidationReport`.

- [ ] **Step 6: Add summary output**

In `TraceSummaryGenerator.kt`, add `cacheProofs` to `JsonSummary`:

```kotlin
val cacheProofs: List<TraceCacheProofEntry>
```

Pass it from the report:

```kotlin
cacheProofs = report.cacheProofs
```

Add a Markdown section after counters:

```kotlin
appendLine("## Cache Proof")
if (report.cacheProofs.isEmpty()) {
    appendLine("- No runtime cache decisions captured.")
} else {
    report.cacheProofs.forEach { proof ->
        appendLine(
            "- ${proof.provider ?: "?"} / ${proof.apiShapeId ?: "?"} / ${proof.operationKey ?: "?"}: " +
                "decision=${proof.cacheDecision ?: "?"}, " +
                "networkSuppressed=${proof.networkSuppressed}, " +
                "httpRequests=${proof.httpRequestCount}, " +
                "cacheKey=${proof.cacheKey ?: "null"}"
        )
    }
}
appendLine()
```

- [ ] **Step 7: Run focused trace tests**

Run:

```bash
./gradlew :app:testUniversalReleaseProfileableUnitTest --tests "com.nexio.tv.core.trace.TraceValidationRulesTest" --tests "com.nexio.tv.core.trace.RuntimeTraceValidatorTest" --tests "com.nexio.tv.core.trace.TraceBundleGoldenTest"
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 8: Commit**

Run:

```bash
git add app/src/main/java/com/nexio/tv/core/trace/TraceValidationRules.kt app/src/main/java/com/nexio/tv/core/trace/TraceValidationReport.kt app/src/main/java/com/nexio/tv/core/trace/RuntimeTraceValidator.kt app/src/main/java/com/nexio/tv/core/trace/TraceSummaryGenerator.kt app/src/test/java/com/nexio/tv/core/trace/TraceValidationRulesTest.kt app/src/test/java/com/nexio/tv/core/trace/RuntimeTraceValidatorTest.kt app/src/test/java/com/nexio/tv/core/trace/TraceBundleGoldenTest.kt
git commit -m "feat(trace): summarize cache network proof"
```

---

### Task 5: Local Trace Cache Proof Script

**Files:**
- Create: `scripts/trace-cache-proof.py`
- Test: `app/src/test/java/com/nexio/tv/core/trace/TraceCacheProofScriptContractTest.kt`

- [ ] **Step 1: Write the failing script contract test**

Create `TraceCacheProofScriptContractTest.kt`:

```kotlin
package com.nexio.tv.core.trace

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TraceCacheProofScriptContractTest {
    @Test
    fun `trace cache proof script documents hit miss and http request columns`() {
        val script = File("scripts/trace-cache-proof.py")
        assertTrue("script exists", script.isFile)
        val text = script.readText()
        assertTrue(text.contains("runtimeOperationId"))
        assertTrue(text.contains("cacheDecision"))
        assertTrue(text.contains("networkSuppressed"))
        assertTrue(text.contains("httpRequestCount"))
        assertTrue(text.contains("MISS_THEN_NETWORK"))
    }
}
```

- [ ] **Step 2: Run the failing test**

Run:

```bash
./gradlew :app:testUniversalReleaseProfileableUnitTest --tests "com.nexio.tv.core.trace.TraceCacheProofScriptContractTest"
```

Expected before implementation:

```text
FAIL
script exists
```

- [ ] **Step 3: Create the script**

Create `scripts/trace-cache-proof.py`:

```python
#!/usr/bin/env python3
import json
import sys
from collections import defaultdict

if len(sys.argv) != 2:
    print("usage: scripts/trace-cache-proof.py trace-events.jsonl", file=sys.stderr)
    sys.exit(2)

path = sys.argv[1]
ops = {}
http_counts = defaultdict(int)

with open(path, "r", encoding="utf-8") as fh:
    for line in fh:
        line = line.strip()
        if not line:
            continue
        event = json.loads(line)
        payload = event.get("payload") or {}
        op_id = payload.get("runtimeOperationId")
        if event.get("eventType") == "runtime.operation_start" and op_id:
            entry = ops.setdefault(op_id, {"runtimeOperationId": op_id})
            for key in ("provider", "apiShapeId", "operationKey", "cacheKey"):
                entry[key] = payload.get(key)
        elif event.get("eventType") == "runtime.cache_decision" and op_id:
            entry = ops.setdefault(op_id, {"runtimeOperationId": op_id})
            for key in ("provider", "apiShapeId", "operationKey", "cacheKey"):
                entry[key] = entry.get(key) or payload.get(key)
            entry["cacheDecision"] = payload.get("decision")
            entry["networkSuppressed"] = payload.get("networkSuppressed")
            entry["reason"] = payload.get("reason")
        elif event.get("eventType") == "http.request" and op_id:
            http_counts[op_id] += 1

print("runtimeOperationId\tprovider\tapiShapeId\toperationKey\tcacheDecision\tnetworkSuppressed\thttpRequestCount\tcacheKey")
exit_code = 0
for op_id in sorted(ops):
    entry = ops[op_id]
    http_count = http_counts[op_id]
    decision = entry.get("cacheDecision")
    suppressed = entry.get("networkSuppressed")
    if decision in ("HIT", "STALE_HIT") and suppressed is True and http_count > 0:
        exit_code = 1
    print(
        f"{op_id}\t"
        f"{entry.get('provider') or ''}\t"
        f"{entry.get('apiShapeId') or ''}\t"
        f"{entry.get('operationKey') or ''}\t"
        f"{decision or ''}\t"
        f"{suppressed}\t"
        f"{http_count}\t"
        f"{entry.get('cacheKey') or ''}"
    )

misses = [entry for entry in ops.values() if entry.get("cacheDecision") == "MISS_THEN_NETWORK"]
if misses:
    print(f"\nMISS_THEN_NETWORK operations: {len(misses)}")
    for entry in misses:
        print(f"- {entry.get('provider')} {entry.get('apiShapeId')} {entry.get('operationKey')} {entry.get('cacheKey')}")

sys.exit(exit_code)
```

- [ ] **Step 4: Make the script executable and run the test**

Run:

```bash
chmod +x scripts/trace-cache-proof.py
./gradlew :app:testUniversalReleaseProfileableUnitTest --tests "com.nexio.tv.core.trace.TraceCacheProofScriptContractTest"
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 5: Commit**

Run:

```bash
git add scripts/trace-cache-proof.py app/src/test/java/com/nexio/tv/core/trace/TraceCacheProofScriptContractTest.kt
git commit -m "chore(trace): add cache proof analyzer"
```

---

### Task 6: Debugging Documentation

**Files:**
- Create: `docs/debugging/runtime-cache-network-proof.md`

- [ ] **Step 1: Create the documentation**

Create `docs/debugging/runtime-cache-network-proof.md`:

```markdown
# Runtime Cache Network Proof

Use this procedure on a rooted/profileable device to prove whether provider metadata calls used fresh cache or network.

## Quick Logcat Proof

Enable these troubleshooting toggles in the app:

- Runtime & Metadata Trace mode: `INCLUDE_HTTP_SUMMARY`
- Logcat first paint: enabled
- Logcat metadata route: enabled
- Logcat integration runtime: enabled

Capture:

```bash
ANDROID_SERIAL=192.168.50.98:5555 adb logcat -c
ANDROID_SERIAL=192.168.50.98:5555 adb logcat -v time -s Nexio.IntRuntime Nexio.MetaRoute Nexio.FirstPaint
```

Fresh cached provider metadata must show:

```text
t=runtime.cache_decision ... decision=HIT ... networkSuppressed=true
```

and must not show `t=http.request` with the same `runtimeOperationId`.

## File Trace Proof

Start a runtime trace session in the app with `INCLUDE_HTTP_SUMMARY`, reproduce the flow, then pull traces:

```bash
ANDROID_SERIAL=192.168.50.98:5555 adb root
ANDROID_SERIAL=192.168.50.98:5555 adb shell ls -t /data/data/com.nexio.tv/files/traces
ANDROID_SERIAL=192.168.50.98:5555 adb pull /data/data/com.nexio.tv/files/traces/<session-id>/trace-events.jsonl ./trace-events.jsonl
scripts/trace-cache-proof.py ./trace-events.jsonl
```

For a second open of cached Kitsu `kitsu:12`, expected Kitsu metadata rows:

```text
provider=KITSU
cacheDecision=HIT
networkSuppressed=true
httpRequestCount=0
```

No unexpired Kitsu metadata operation should report:

```text
cacheDecision=MISS_THEN_NETWORK
```

## Important Boundary

This proof covers provider metadata, identity, rail, and integration calls that go through `IntegrationRuntime`.

Coil image fetches are separate. A poster image request can still use network even when provider metadata is fresh. Image proof requires Coil memory/disk cache instrumentation.
```

- [ ] **Step 2: Commit**

Run:

```bash
git add docs/debugging/runtime-cache-network-proof.md
git commit -m "docs: describe runtime cache proof workflow"
```

---

### Task 7: Final Verification

**Files:**
- All files changed in Tasks 1-6.

- [ ] **Step 1: Run focused unit tests**

Run:

```bash
./gradlew :app:testUniversalReleaseProfileableUnitTest --tests "com.nexio.tv.core.trace.RuntimeTraceValidatorRealEmissionTest" --tests "com.nexio.tv.core.trace.LogcatRuntimeTraceSinkTest" --tests "com.nexio.tv.core.trace.TraceValidationRulesTest" --tests "com.nexio.tv.core.trace.RuntimeTraceValidatorTest" --tests "com.nexio.tv.core.trace.TraceBundleGoldenTest" --tests "com.nexio.tv.core.trace.TraceCacheProofScriptContractTest"
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 2: Validate OpenSpec**

Run:

```bash
openspec validate prove-runtime-cache-network-decisions --strict
```

Expected:

```text
Change 'prove-runtime-cache-network-decisions' is valid
```

- [ ] **Step 3: Install profileable build**

Run:

```bash
ANDROID_SERIAL=192.168.50.98:5555 ./gradlew :app:installUniversalReleaseProfileable
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 4: Capture one Kitsu second-open trace**

Run:

```bash
ANDROID_SERIAL=192.168.50.98:5555 adb logcat -c
ANDROID_SERIAL=192.168.50.98:5555 adb logcat -v time -s Nexio.IntRuntime Nexio.MetaRoute Nexio.FirstPaint
```

Manual device steps:

```text
1. Open One Piece from a Kitsu rail.
2. Wait until characters, productions, reviews, related, and episodes render.
3. Back out to Home.
4. Open One Piece again.
5. Stop logcat after detail content renders.
```

Expected second-open Kitsu metadata lines:

```text
t=runtime.cache_decision provider=KITSU ... decision=HIT ... networkSuppressed=true
```

Expected absent second-open Kitsu metadata lines:

```text
t=runtime.cache_decision provider=KITSU ... decision=MISS_THEN_NETWORK
t=http.request provider=KITSU ... runtimeOperationId=<same op as HIT>
```

- [ ] **Step 5: Pull and analyze file trace**

Run:

```bash
ANDROID_SERIAL=192.168.50.98:5555 adb root
ANDROID_SERIAL=192.168.50.98:5555 adb shell ls -t /data/data/com.nexio.tv/files/traces
ANDROID_SERIAL=192.168.50.98:5555 adb pull /data/data/com.nexio.tv/files/traces/<session-id>/trace-events.jsonl ./trace-events.jsonl
scripts/trace-cache-proof.py ./trace-events.jsonl
```

Expected:

```text
runtimeOperationId provider apiShapeId operationKey cacheDecision networkSuppressed httpRequestCount cacheKey
...
KITSU ... HIT True 0 ...
```

Exit code must be `0`.

- [ ] **Step 6: Commit final verification notes if docs changed**

Run:

```bash
git status --short
```

Expected:

```text
<only intended files modified>
```

If all intended changes are already committed, no commit is needed.

---

## Self-Review

- Spec coverage: The plan covers active trace session id integrity, logcat cache-proof fields, validator failure for illegal network after cache hit, per-operation summary output, local JSONL analysis, and rooted/profileable device workflow.
- Placeholder scan: No task uses deferred wording for implementation; each code change has concrete file paths and code snippets.
- Type consistency: The model name `TraceCacheProofEntry`, property `cacheProofs`, and rule id `SuppressedStaleCacheHitSuppressesNetwork` are used consistently across implementation and tests.
- Architecture check: The plan does not create any parallel metadata/cache/rendering path. It strengthens observability around the existing `IntegrationRuntime` and trace sinks only.
