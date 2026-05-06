# Premium Artwork Review Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the code-review gaps from the premium artwork decision-materialization patch: provider key redaction, disk-first decision hits, missing-decision diagnostics, and selected-option dialog focus.

**Architecture:** Keep the shared artwork architecture intact: UI/Coil still receive `nexio-artwork://decision/...` or `nexio-artwork://asset/...`, and provider fetching stays behind `ArtworkAssetRepository` plus `IntegrationRuntime`. The follow-up changes are narrow: harden trace redaction, make decision materialization prefer the asset disk cache, add the missing trace event, and improve TV dialog focus without changing provider selection.

**Tech Stack:** Android TV app, Kotlin, Hilt, Coil, OkHttp/MockWebServer, Compose, JUnit/Robolectric-style unit tests.

---

## Scope Check

This plan addresses four review findings that all stem from the same premium artwork follow-up patch. They are small enough to keep in one plan because each task is independently testable and does not require broad architecture changes.

Do not change these invariants:

- Do not return raw RPDB or Top-Posters URLs from UI-facing metadata.
- Do not reintroduce `integration-poster://` as the final premium artwork model.
- Do not move provider precedence into `NexioArtworkFetcher`.
- Do not install a debug build as part of this plan unless explicitly requested later.

## File Structure

Modify:

- `app/src/main/java/com/nexio/tv/core/trace/TraceRedactor.kt`
  - Redact provider API keys stored as the first path segment for `api.ratingposterdb.com` and `api.top-posters.com`.
  - Preserve existing query-string redaction behavior.

- `app/src/test/java/com/nexio/tv/core/trace/TraceRedactorTest.kt`
  - Add direct tests for RPDB and Top-Posters path credential redaction.

- `app/src/test/java/com/nexio/tv/core/trace/RuntimeTraceInterceptorTest.kt`
  - Add an interceptor-level test proving `http.request` trace payloads do not contain provider path credentials.

- `app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRepository.kt`
  - Add disk-first lookup after decision source materialization.
  - Add fallback-to-existing-disk-file when runtime returns `Missing`.
  - Emit `artwork.decision_missing` when a decision cache lookup misses.

- `app/src/test/java/com/nexio/tv/core/artwork/ArtworkAssetRepositoryTest.kt`
  - Add disk-first decision materialization tests.
  - Add runtime-missing fallback test.
  - Update missing-decision trace test.

- `app/src/main/java/com/nexio/tv/ui/screens/settings/PosterRatingsSettingsScreen.kt`
  - Initialize provider selection dialog list position to the selected item.
  - Request focus on the selected option when the dialog opens.

- `app/src/test/java/com/nexio/tv/ui/screens/settings/PosterRatingsSettingsDialogScrollContractTest.kt`
  - Strengthen the static contract test to cover selected list position and focus behavior.

No new production files are needed.

---

### Task 1: Redact Provider Path Credentials In Runtime Traces

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/trace/TraceRedactor.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/trace/TraceRedactorTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/trace/RuntimeTraceInterceptorTest.kt`

- [ ] **Step 1: Add failing direct redactor tests**

Append these tests to `TraceRedactorTest`:

```kotlin
@Test
fun `RPDB path credential is redacted`() {
    val redacted = r.redactUrl(
        "https://api.ratingposterdb.com/rpdb-secret/imdb/poster-default/tt0137523.jpg"
    )

    assertEquals(
        "https://api.ratingposterdb.com/<redacted>/imdb/poster-default/tt0137523.jpg",
        redacted
    )
    assertFalse(redacted.contains("rpdb-secret"))
}

@Test
fun `Top Posters path credential is redacted and query redaction still applies`() {
    val redacted = r.redactUrl(
        "https://api.top-posters.com/top-secret/tvdb/thumbnail/1399/S1E2.jpg?api_key=QUERY_SECRET&blur=false"
    )

    assertEquals(
        "https://api.top-posters.com/<redacted>/tvdb/thumbnail/1399/S1E2.jpg?api_key=<redacted>&blur=false",
        redacted
    )
    assertFalse(redacted.contains("top-secret"))
    assertFalse(redacted.contains("QUERY_SECRET"))
}

@Test
fun `non provider path segment is not redacted`() {
    val redacted = r.redactUrl("https://image.tmdb.org/t/p/w500/poster.jpg")

    assertEquals("https://image.tmdb.org/t/p/w500/poster.jpg", redacted)
}
```

- [ ] **Step 2: Add failing runtime interceptor redaction test**

Add these imports to `RuntimeTraceInterceptorTest`:

```kotlin
import java.net.InetAddress
import okhttp3.Dns
```

Append this test to `RuntimeTraceInterceptorTest`:

```kotlin
@Test
fun `provider path credential is redacted in http request trace`() {
    val server = MockWebServer().apply {
        enqueue(MockResponse().setResponseCode(200).setBody("ok"))
    }
    server.start()
    val sink = RecordingTraceSink()
    val client = OkHttpClient.Builder()
        .dns { hostname ->
            if (hostname == "api.ratingposterdb.com") {
                listOf(InetAddress.getByName("127.0.0.1"))
            } else {
                Dns.SYSTEM.lookup(hostname)
            }
        }
        .addInterceptor(
            RuntimeTraceInterceptor(
                sink = sink,
                redactor = TraceRedactor(),
                modeProvider = fixedMode(TraceMode.INCLUDE_HTTP_SUMMARY),
                unscopedGuard = UnscopedNetworkPolicyGuard(
                    sink,
                    sessionId = { "s1" },
                    isInternalBuild = false
                )
            )
        )
        .build()

    val request = Request.Builder()
        .url("http://api.ratingposterdb.com:${server.port}/rpdb-secret/imdb/poster-default/tt0137523.jpg")
        .tag(
            RuntimeTraceContext::class.java,
            ctx(opId = "rpdb_path_redaction").copy(
                provider = IntegrationProvider.RPDB,
                apiShapeId = "rpdb.poster_template"
            )
        )
        .build()

    client.newCall(request).execute().close()

    val req = sink.events.first { it.eventType == "http.request" }
    val payload = req.payload as Map<*, *>
    val url = payload["url"] as String
    assertTrue("provider credential path segment must be redacted: $url", url.contains("/<redacted>/imdb/"))
    assertFalse("provider credential must not appear in trace URL: $url", url.contains("rpdb-secret"))

    server.shutdown()
}
```

- [ ] **Step 3: Run the redaction tests and verify they fail**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.trace.TraceRedactorTest --tests com.nexio.tv.core.trace.RuntimeTraceInterceptorTest
```

Expected: FAIL. The direct tests should show provider keys still present in redacted URLs.

- [ ] **Step 4: Implement provider path redaction**

Replace `TraceRedactor.redactUrl` and add the helper/constants shown below:

```kotlin
fun redactUrl(url: String): String {
    val pathRedactedUrl = redactProviderCredentialPath(url)
    val q = pathRedactedUrl.indexOf('?')
    if (q < 0) return pathRedactedUrl
    val base = pathRedactedUrl.substring(0, q)
    val query = pathRedactedUrl.substring(q + 1)
    val redactedQuery = query.split('&').joinToString("&") { pair ->
        val eq = pair.indexOf('=')
        if (eq < 0) return@joinToString pair
        val key = pair.substring(0, eq)
        if (key.lowercase() in redactedUrlKeys) "$key=<redacted>" else pair
    }
    return "$base?$redactedQuery"
}

private fun redactProviderCredentialPath(url: String): String {
    val parsed = runCatching { java.net.URI(url) }.getOrNull() ?: return url
    val host = parsed.host?.lowercase() ?: return url
    if (host !in providerCredentialPathHosts) return url

    val rawPath = parsed.rawPath ?: return url
    val segments = rawPath.split("/")
    if (segments.size < 2 || segments[1].isBlank()) return url

    val redactedPath = segments.toMutableList()
        .also { it[1] = "<redacted>" }
        .joinToString("/")
    val scheme = parsed.scheme ?: return url
    val authority = parsed.rawAuthority ?: return url
    val query = parsed.rawQuery?.let { "?$it" }.orEmpty()
    val fragment = parsed.rawFragment?.let { "#$it" }.orEmpty()
    return "$scheme://$authority$redactedPath$query$fragment"
}

private val providerCredentialPathHosts = setOf(
    "api.ratingposterdb.com",
    "api.top-posters.com"
)
```

Keep the existing `redactedUrlKeys`, `redactedHeaders`, `redactedJsonKeys`, `redactHeaders`, `redactJsonBody`, and manifest accessor methods unchanged.

- [ ] **Step 5: Run the redaction tests and verify they pass**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.trace.TraceRedactorTest --tests com.nexio.tv.core.trace.RuntimeTraceInterceptorTest
```

Expected: PASS.

- [ ] **Step 6: Commit Task 1**

Run:

```bash
git add app/src/main/java/com/nexio/tv/core/trace/TraceRedactor.kt app/src/test/java/com/nexio/tv/core/trace/TraceRedactorTest.kt app/src/test/java/com/nexio/tv/core/trace/RuntimeTraceInterceptorTest.kt
git commit -m "fix(trace): redact premium artwork path credentials"
```

---

### Task 2: Make Decision Materialization Disk-First

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRepository.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/artwork/ArtworkAssetRepositoryTest.kt`

- [ ] **Step 1: Add failing disk-first and runtime-missing fallback tests**

In `ArtworkAssetRepositoryTest`, update the private `repository(...)` helper signature to accept a shared disk cache:

```kotlin
private fun repository(
    runtime: IntegrationRuntime,
    cache: ArtworkDecisionCache = InMemoryArtworkDecisionCache(),
    diskCache: ArtworkAssetDiskCache = ArtworkAssetDiskCache(temp.root),
    sourceMaterializer: ArtworkSourceMaterializer = ArtworkSourceMaterializer(emptyMap()),
    byteLoader: ArtworkByteLoader = ArtworkByteLoader { _, _ ->
        IntegrationLoadResult.Success("image-bytes".toByteArray())
    },
    traceSink: RuntimeTraceSink = RecordingArtworkTraceSink()
): ArtworkAssetRepository =
    ArtworkAssetRepository(
        runtime = runtime,
        diskCache = diskCache,
        sourceMaterializer = sourceMaterializer,
        byteLoader = byteLoader,
        decisionCache = cache,
        traceSink = traceSink
    )
```

Append these tests to `ArtworkAssetRepositoryTest`:

```kotlin
@Test
fun `decision materialization returns existing artwork asset before runtime`() = runTest {
    val diskCache = ArtworkAssetDiskCache(temp.root)
    val decision = rpdbTemplateDecision()
    val firstRepository = repository(
        runtime = LoadingIntegrationRuntime(),
        diskCache = diskCache,
        byteLoader = ArtworkByteLoader { _, _ ->
            IntegrationLoadResult.Success("disk-first".toByteArray())
        }
    )
    assertNotNull(firstRepository.getOrFetch(decision))

    val runtime = FailingIfCalledIntegrationRuntime()
    val secondRepository = repository(
        runtime = runtime,
        diskCache = diskCache,
        byteLoader = ArtworkByteLoader { _, _ ->
            IntegrationLoadResult.NetworkError(IllegalStateException("loader should not run"))
        }
    )

    val result = secondRepository.getOrFetch(decision)

    assertNotNull(result)
    assertEquals(false, runtime.called)
    assertEquals(false, result!!.networkExecuted)
    assertEquals("ARTWORK_DISK_HIT", result.cacheDecision)
    assertArrayEquals("disk-first".toByteArray(), result.localFile.readBytes())
}

@Test
fun `decision materialization falls back to existing artwork asset after runtime missing`() = runTest {
    val diskCache = ArtworkAssetDiskCache(temp.root)
    val decision = rpdbTemplateDecision()
    val materializedAssetKey = ArtworkCacheKeys.assetKeyForProviderTemplate(
        decision.selectedCandidate.providerTemplate!!
    )
    val runtime = MissingAfterConcurrentDiskWriteRuntime(
        diskCache = diskCache,
        decision = decision,
        assetKey = materializedAssetKey
    )
    val repository = repository(
        runtime = runtime,
        diskCache = diskCache,
        byteLoader = ArtworkByteLoader { _, _ ->
            IntegrationLoadResult.NetworkError(IllegalStateException("provider unavailable"))
        }
    )

    val result = repository.getOrFetch(decision)

    assertNotNull(result)
    assertEquals(true, runtime.called)
    assertEquals(false, result!!.networkExecuted)
    assertEquals("ARTWORK_DISK_HIT_AFTER_RUNTIME_MISS", result.cacheDecision)
    assertArrayEquals("late-disk".toByteArray(), result.localFile.readBytes())
}
```

Add these private runtime helpers near the existing test runtimes:

```kotlin
private class FailingIfCalledIntegrationRuntime : IntegrationRuntime {
    var called = false

    override suspend fun <T> get(
        spec: IntegrationSpec<T>,
        options: IntegrationFetchOptions
    ): IntegrationFetchResult<T> {
        called = true
        error("runtime should not be called when artwork asset disk cache has a hit")
    }

    override suspend fun <T> call(spec: IntegrationCallSpec<T>): IntegrationCallResult<T> =
        error("not used")

    override suspend fun <T> open(spec: IntegrationStreamSpec<T>): IntegrationStreamHandle<T>? =
        error("not used")
}

private class MissingAfterConcurrentDiskWriteRuntime(
    private val diskCache: ArtworkAssetDiskCache,
    private val decision: ArtworkDecision,
    private val assetKey: ArtworkAssetKey
) : IntegrationRuntime {
    var called = false

    override suspend fun <T> get(
        spec: IntegrationSpec<T>,
        options: IntegrationFetchOptions
    ): IntegrationFetchResult<T> {
        called = true
        val record = diskCache.recordFor(
            assetKey = assetKey,
            decision = decision,
            provider = decision.selectedCandidate.provider,
            sourceHash = decision.selectedCandidate.sourceHash ?: "unknown",
            mimeType = ByteArrayIntegrationCodec.mimeType,
            byteCount = "late-disk".toByteArray().size.toLong(),
            fetchedAtMs = 123L
        )
        diskCache.write(record, "late-disk".toByteArray())
        return IntegrationFetchResult.Missing
    }

    override suspend fun <T> call(spec: IntegrationCallSpec<T>): IntegrationCallResult<T> =
        error("not used")

    override suspend fun <T> open(spec: IntegrationStreamSpec<T>): IntegrationStreamHandle<T>? =
        error("not used")
}
```

- [ ] **Step 2: Run repository tests and verify they fail**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.artwork.ArtworkAssetRepositoryTest
```

Expected: FAIL. The first new test should fail because runtime is still called on an existing disk file. The second should fail because runtime `Missing` currently returns `null`.

- [ ] **Step 3: Implement disk-first and runtime-missing fallback**

In `ArtworkAssetRepository.getOrFetch(decision)`, replace the method body with this version:

```kotlin
suspend fun getOrFetch(decision: ArtworkDecision): ArtworkAssetResult? {
    val materialized = sourceMaterializer.materialize(decision) ?: return null
    diskCache.getExistingFile(materialized.assetKey)?.let { existing ->
        return existingAssetResult(
            file = existing,
            materialized = materialized,
            decision = decision,
            cacheDecision = "ARTWORK_DISK_HIT"
        )
    }

    val apiShapeId = materialized.apiShapeId
    val runtimeProvider = materialized.runtimeProvider
    var loaderInvoked = false
    val result = runtime.get(
        IntegrationSpec(
            provider = runtimeProvider,
            apiShapeId = apiShapeId,
            operationKey = materialized.assetKey.value,
            cacheKey = materialized.assetKey.value,
            codec = ByteArrayIntegrationCodec,
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = (decision.expiresAtMs - decision.createdAtMs).coerceAtLeast(1L),
                staleAfterExpiryMs = ((decision.staleUntilMs ?: decision.expiresAtMs) - decision.expiresAtMs)
                    .coerceAtLeast(0L)
            ),
            workClass = IntegrationWorkClass.BACKGROUND_HYDRATION,
            scope = IntegrationScope.GlobalEnglishImage,
            load = {
                loaderInvoked = true
                byteLoader.load(materialized.source, decision)
            }
        )
    )

    val bytes = result.bytesOrNull()
    if (bytes == null) {
        return diskCache.getExistingFile(materialized.assetKey)?.let { existing ->
            existingAssetResult(
                file = existing,
                materialized = materialized,
                decision = decision,
                cacheDecision = "ARTWORK_DISK_HIT_AFTER_RUNTIME_MISS"
            )
        }
    }

    val record = diskCache.recordFor(
        assetKey = materialized.assetKey,
        decision = decision,
        provider = materialized.provider,
        sourceHash = materialized.sourceHash,
        mimeType = ByteArrayIntegrationCodec.mimeType,
        byteCount = bytes.size.toLong(),
        fetchedAtMs = System.currentTimeMillis()
    )
    val write = diskCache.write(record, bytes)
    return ArtworkAssetResult(
        assetKey = materialized.assetKey,
        localFile = write.file,
        record = write.record,
        runtimeResult = result,
        runtimeApiShapeId = apiShapeId,
        cacheDecision = result.cacheDecision(),
        mimeType = write.record.mimeType,
        networkExecuted = loaderInvoked
    )
}
```

Add this helper below `getExistingFile(...)`:

```kotlin
private fun existingAssetResult(
    file: File,
    materialized: MaterializedArtworkSource,
    decision: ArtworkDecision,
    cacheDecision: String
): ArtworkAssetResult {
    val bytes = file.readBytes()
    val record = diskCache.recordFor(
        assetKey = materialized.assetKey,
        decision = decision,
        provider = materialized.provider,
        sourceHash = materialized.sourceHash,
        mimeType = ByteArrayIntegrationCodec.mimeType,
        byteCount = bytes.size.toLong(),
        fetchedAtMs = file.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
    )
    return ArtworkAssetResult(
        assetKey = materialized.assetKey,
        localFile = file,
        record = record,
        runtimeResult = IntegrationFetchResult.Fresh(bytes),
        runtimeApiShapeId = materialized.apiShapeId,
        cacheDecision = cacheDecision,
        mimeType = record.mimeType,
        networkExecuted = false
    )
}
```

- [ ] **Step 4: Run repository tests and verify they pass**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.artwork.ArtworkAssetRepositoryTest
```

Expected: PASS.

- [ ] **Step 5: Commit Task 2**

Run:

```bash
git add app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRepository.kt app/src/test/java/com/nexio/tv/core/artwork/ArtworkAssetRepositoryTest.kt
git commit -m "fix(artwork): prefer materialized asset disk hits"
```

---

### Task 3: Emit `artwork.decision_missing`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRepository.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/artwork/ArtworkAssetRepositoryTest.kt`

- [ ] **Step 1: Update the missing decision test to fail**

Replace the current `getOrFetchDecision returns null and traces missing decision` test with:

```kotlin
@Test
fun `getOrFetchDecision returns null and traces missing decision`() = runTest {
    val traceSink = RecordingArtworkTraceSink()
    val repository = repository(
        runtime = LoadingIntegrationRuntime(),
        cache = InMemoryArtworkDecisionCache(),
        traceSink = traceSink
    )

    val result = repository.getOrFetchDecision(ArtworkDecisionKey("missing-decision"))

    assertNull(result)
    assertEquals(
        listOf("artwork.decision_lookup", "artwork.decision_missing"),
        traceSink.events.map { it.eventType }
    )
    assertEquals(false, (traceSink.events.first().payload as Map<*, *>)["found"])
    assertEquals(
        "missing-decision",
        (traceSink.events.last().payload as Map<*, *>)["decisionKey"]
    )
}
```

- [ ] **Step 2: Run the missing decision test and verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.artwork.ArtworkAssetRepositoryTest
```

Expected: FAIL. The event list should contain only `artwork.decision_lookup`.

- [ ] **Step 3: Emit the missing decision event**

In `ArtworkAssetRepository.getOrFetchDecision(...)`, replace:

```kotlin
if (decision == null) return null
```

with:

```kotlin
if (decision == null) {
    traceArtwork(
        eventType = "artwork.decision_missing",
        payload = mapOf(
            "decisionKey" to decisionKey.value
        )
    )
    return null
}
```

- [ ] **Step 4: Run repository tests and verify they pass**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.artwork.ArtworkAssetRepositoryTest
```

Expected: PASS.

- [ ] **Step 5: Commit Task 3**

Run:

```bash
git add app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRepository.kt app/src/test/java/com/nexio/tv/core/artwork/ArtworkAssetRepositoryTest.kt
git commit -m "fix(artwork): trace missing artwork decisions"
```

---

### Task 4: Focus The Selected Provider Option In The Dialog

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PosterRatingsSettingsScreen.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/settings/PosterRatingsSettingsDialogScrollContractTest.kt`

- [ ] **Step 1: Strengthen the dialog contract test**

Replace `PosterRatingsSettingsDialogScrollContractTest` with:

```kotlin
package com.nexio.tv.ui.screens.settings

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PosterRatingsSettingsDialogScrollContractTest {
    @Test
    fun `provider selection dialog uses bounded lazy list for overflow choices`() {
        val dialogSource = dialogSource()

        assertTrue(
            "ArtworkProviderSelectionDialog must use LazyColumn so overflow choices remain focus-navigable.",
            dialogSource.contains("LazyColumn(")
        )
        assertTrue(
            "ArtworkProviderSelectionDialog must bound the list height so it scrolls inside NexioDialog.",
            dialogSource.contains(".heightIn(")
        )
    }

    @Test
    fun `provider selection dialog scrolls to and focuses the selected choice`() {
        val dialogSource = dialogSource()

        assertTrue(
            "ArtworkProviderSelectionDialog must initialize the LazyColumn to the selected option.",
            dialogSource.contains("rememberLazyListState(") &&
                dialogSource.contains("initialFirstVisibleItemIndex = selectedIndex")
        )
        assertTrue(
            "ArtworkProviderSelectionDialog must attach a FocusRequester to the selected choice.",
            dialogSource.contains("FocusRequester()") &&
                dialogSource.contains(".focusRequester(selectedFocusRequester)")
        )
        assertTrue(
            "ArtworkProviderSelectionDialog must request focus after the selected item is composed.",
            dialogSource.contains("selectedFocusRequester.requestFocus()")
        )
    }

    private fun dialogSource(): String {
        val source = File(
            "app/src/main/java/com/nexio/tv/ui/screens/settings/PosterRatingsSettingsScreen.kt"
        ).readText()
        return source.substringAfter("private fun ArtworkProviderSelectionDialog(")
            .substringBefore("private fun PosterApiKeyDialog(")
    }
}
```

- [ ] **Step 2: Run the dialog contract test and verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.settings.PosterRatingsSettingsDialogScrollContractTest
```

Expected: FAIL. The new selected-focus assertions should fail.

- [ ] **Step 3: Implement selected item scroll and focus**

In `PosterRatingsSettingsScreen.kt`, add this import:

```kotlin
import androidx.compose.foundation.lazy.rememberLazyListState
```

Inside `ArtworkProviderSelectionDialog(...)`, add this state before `NexioDialog(...)`:

```kotlin
val selectedIndex = remember(choices, selected) {
    choices.indexOf(selected).coerceAtLeast(0)
}
val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
val selectedFocusRequester = remember(selected) { FocusRequester() }

LaunchedEffect(selectedIndex, choices) {
    if (choices.isNotEmpty()) {
        listState.scrollToItem(selectedIndex)
        runCatching { selectedFocusRequester.requestFocus() }
    }
}
```

In the existing `LazyColumn(...)`, add the state argument:

```kotlin
LazyColumn(
    state = listState,
    verticalArrangement = Arrangement.spacedBy(10.dp),
    contentPadding = PaddingValues(vertical = 4.dp)
) {
```

Replace the current `SettingsChoiceChip(...)` modifier block with:

```kotlin
SettingsChoiceChip(
    label = providerChoiceLabel(choice),
    selected = choice == selected,
    onClick = { onSelect(choice) },
    modifier = Modifier
        .fillMaxWidth()
        .then(
            if (choice == selected) {
                Modifier.focusRequester(selectedFocusRequester)
            } else {
                Modifier
            }
        )
)
```

- [ ] **Step 4: Run the dialog contract test and verify it passes**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.settings.PosterRatingsSettingsDialogScrollContractTest
```

Expected: PASS.

- [ ] **Step 5: Commit Task 4**

Run:

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/settings/PosterRatingsSettingsScreen.kt app/src/test/java/com/nexio/tv/ui/screens/settings/PosterRatingsSettingsDialogScrollContractTest.kt
git commit -m "fix(settings): focus selected artwork provider choice"
```

---

### Task 5: Full Focused Verification

**Files:**
- No source changes expected.

- [ ] **Step 1: Run the focused artwork, trace, and dialog tests**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests com.nexio.tv.core.trace.TraceRedactorTest \
  --tests com.nexio.tv.core.trace.RuntimeTraceInterceptorTest \
  --tests com.nexio.tv.core.artwork.ArtworkAssetRepositoryTest \
  --tests com.nexio.tv.core.artwork.DefaultArtworkByteLoaderTest \
  --tests com.nexio.tv.core.artwork.PosterRatingsArtworkCredentialResolverTest \
  --tests com.nexio.tv.core.image.NexioArtworkFetcherTest \
  --tests com.nexio.tv.core.poster.PosterRatingsUrlResolverTest \
  --tests com.nexio.tv.architecture.PremiumArtworkSharedPipelineContractTest \
  --tests com.nexio.tv.ui.screens.settings.PosterRatingsSettingsDialogScrollContractTest
```

Expected: PASS.

- [ ] **Step 2: Run existing boundary/audit tests**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests com.nexio.tv.metadata.audit.MetadataExecutionAuditGoldenTest \
  --tests com.nexio.tv.architecture.RawRemoteArtworkUrlBoundaryTest
```

Expected: PASS.

- [ ] **Step 3: Build debug APK without installing it**

Run:

```bash
./gradlew :app:assembleDebug
```

Expected: PASS.

- [ ] **Step 4: Inspect staged cleanliness before final commit or push**

Run:

```bash
git status --short
```

Expected: only intended tracked changes from this plan are present. Do not stage unrelated assets, `media`, `tmp/`, or old untracked docs unless the user explicitly asks.

- [ ] **Step 5: Push after all task commits are present**

Run:

```bash
git push origin main
```

Expected: push succeeds and `origin/main` advances by the task commits.

---

## Self-Review

**Spec coverage:**

- Provider path key redaction is covered by Task 1 with direct and interceptor-level tests.
- Decision refs disk-hit before runtime is covered by Task 2 with a failing runtime guard.
- Runtime `Missing` fallback to existing disk file is covered by Task 2 with a concurrent disk-write simulation.
- `artwork.decision_missing` is covered by Task 3.
- Selected provider dialog focus is covered by Task 4.
- Focused verification and no install step are covered by Task 5.

**Placeholder scan:**

- No `TBD`, `TODO`, "implement later", or unspecified test requests remain.
- Every code-changing step includes the concrete code to add or replace.

**Type consistency:**

- `TraceRedactor.redactUrl(url: String): String` remains unchanged for callers.
- `ArtworkAssetRepository.getOrFetch(decision: ArtworkDecision): ArtworkAssetResult?` remains unchanged for callers.
- The new repository helper uses existing `MaterializedArtworkSource`, `ArtworkDecision`, `IntegrationFetchResult.Fresh`, and `ByteArrayIntegrationCodec`.
- Compose changes use existing `FocusRequester` and add `rememberLazyListState`.
