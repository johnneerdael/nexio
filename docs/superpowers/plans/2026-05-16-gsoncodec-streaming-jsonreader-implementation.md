# gsonCodec Streaming JsonReader Refactor — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate the CLAUDE.md rule #3 anti-pattern from `gsonCodec<T>()` in `IntegrationCodec.kt`. The current `decodeFn` calls `gson.fromJson(bytes.toString(Charsets.UTF_8), type)` — the documented anti-pattern that pins a UTF-16 String through `StringReader.str` for the parse. Rewrite to stream via `JsonReader` over `InputStreamReader(ByteArrayInputStream(bytes))`. ~93 `gsonCodec<X>()` instantiations across 15 providers inherit the fix automatically.

**Architecture:** Single-point repair. Modify one file (`IntegrationCodec.kt`), add one new test file. No interface change, no cache-store change, no provider edits, no test-fake edits. Behavior is preserved by construction (same gson instance, same lenient-mode defaults). Discipline: characterization test FIRST against the old code, then the refactor (test continues to pass = proof of behavior preservation).

**Tech Stack:** Kotlin · gson (`com.google.gson.stream.JsonReader`) · JUnit4. Matches the committed reference pattern in `TraktMutationOutboxStore.kt:150` / `MetadataDiskCacheStore.kt` / `ResolvedDisplaySnapshotStore.kt`.

**Spec:** `docs/superpowers/specs/2026-05-16-gsoncodec-streaming-jsonreader-design.md`.

**Branch:** `feat/fanarttv-peer-selectable-provider` (layered on the Fanart.tv PR per the brainstorming Q2 answer — the Fanart-specific okio fix in commit `e0b29a10d` becomes the leading example; this plan lifts the streaming pattern to all gson-backed providers).

**Working directory for all commands:** `/Users/jneerdael/Scripts/nexio/.worktrees/fanarttv-peer-selectable`.

**Gradle task names:** the project uses flavored variants. Use `:app:compileUniversalDebugKotlin` and `:app:testUniversalDebugUnitTest -x generateIntegrationRuntimeAudit` for everything in this plan. The audit-bypass is unnecessary here (this change doesn't introduce runtime calls) but harmless and matches the rest of the Fanart.tv workflow on this branch.

---

## File Structure

**New files:**
```
app/src/test/java/com/nexio/tv/core/integration/
  GsonCodecStreamingTest.kt          # 5 characterization tests for gsonCodec
```

**Modified files:**
```
app/src/main/java/com/nexio/tv/core/integration/
  IntegrationCodec.kt                # rewrite decodeFn inside gsonCodec<T>()
```

**Unchanged** (despite this affecting them transitively): all 15 provider files that call `gsonCodec<X>()`, the `IntegrationCodec<T>` interface, `LocalIntegrationCacheStore`, the other codec implementations (`StringIntegrationCodec`, `ByteArrayIntegrationCodec`, `FileCodec`, `JsonCodec`), and all existing tests.

---

## Phase 1 — Characterization test (pin behavior of the CURRENT codec)

### Task 1.1: Write GsonCodecStreamingTest against the unchanged codec

The test exists BEFORE the refactor and passes against the OLD `bytes.toString(Charsets.UTF_8)` implementation. After the refactor in Phase 2, the test continues to pass — that's the proof of behavior preservation.

**Files:**
- Create: `app/src/test/java/com/nexio/tv/core/integration/GsonCodecStreamingTest.kt`

- [ ] **Step 1: Write the test file**

```kotlin
package com.nexio.tv.core.integration

import com.google.gson.JsonSyntaxException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GsonCodecStreamingTest {

    private data class Nested(val value: Int)
    private data class Sample(
        val name: String,
        val tags: List<String>,
        val optional: String? = null,
        val nested: Nested
    )

    private val codec = gsonCodec<Sample>()

    @Test
    fun `round-trip preserves content`() {
        val original = Sample(
            name = "Fight Club",
            tags = listOf("drama", "thriller"),
            optional = "extra",
            nested = Nested(value = 42)
        )
        val bytes = codec.encode(original)
        val decoded = codec.decode(bytes)
        assertEquals(original, decoded)
    }

    @Test
    fun `decodes non-ASCII utf-8 body`() {
        // Japanese + emoji to exercise the UTF-8 multi-byte decoding path.
        val json = """{"name":"鬼灭の刃 🔥","tags":["anime"],"nested":{"value":1}}"""
        val decoded = codec.decode(json.toByteArray(Charsets.UTF_8))
        assertEquals("鬼滅の刃 🔥", decoded.name)
        assertEquals(listOf("anime"), decoded.tags)
        assertEquals(1, decoded.nested.value)
    }

    @Test
    fun `decodes body with utf-8 BOM`() {
        // BOM = EF BB BF; gson.fromJson(String) silently tolerates a leading BOM,
        // and the streaming InputStreamReader(Charsets.UTF_8) path does too.
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val json = """{"name":"bom-prefixed","tags":[],"nested":{"value":0}}""".toByteArray(Charsets.UTF_8)
        val withBom = bom + json
        val decoded = codec.decode(withBom)
        assertEquals("bom-prefixed", decoded.name)
    }

    @Test
    fun `accepts trailing whitespace`() {
        val json = """{"name":"trailing-ws","tags":[],"nested":{"value":0}}    """
        val decoded = codec.decode(json.toByteArray(Charsets.UTF_8))
        assertEquals("trailing-ws", decoded.name)
    }

    @Test
    fun `rejects trailing comma to match strict-mode behavior of String overload`() {
        // gson.fromJson(String) in non-lenient mode rejects trailing commas in arrays.
        // The streaming JsonReader(reader) constructor defaults isLenient = false,
        // so the streaming codec must reject the same input.
        val invalid = """{"name":"x","tags":["a","b",],"nested":{"value":0}}"""
        var threw = false
        try {
            codec.decode(invalid.toByteArray(Charsets.UTF_8))
        } catch (_: JsonSyntaxException) {
            threw = true
        }
        assertTrue("expected JsonSyntaxException on trailing comma", threw)
    }
}
```

- [ ] **Step 2: Run the new test against the CURRENT (unchanged) gsonCodec**

```
./gradlew :app:testUniversalDebugUnitTest -x generateIntegrationRuntimeAudit \
  --tests "com.nexio.tv.core.integration.GsonCodecStreamingTest"
```

Expected: BUILD SUCCESSFUL. All 5 tests pass against the OLD `bytes.toString(Charsets.UTF_8)` implementation. This proves the characterization test correctly captures the existing contract.

Verify in the XML report:
```
grep '<testsuite ' app/build/test-results/testUniversalDebugUnitTest/TEST-com.nexio.tv.core.integration.GsonCodecStreamingTest.xml
```
Expected line contains: `tests="5" skipped="0" failures="0" errors="0"`.

- [ ] **Step 3: Commit**

```bash
git status -sb
git add app/src/test/java/com/nexio/tv/core/integration/GsonCodecStreamingTest.kt
git commit -m "test(integration-codec): pin gsonCodec behavior with characterization tests"
```

Confirm `git status -sb` after the commit shows the working tree is clean of authored files (only the pre-existing submodule `m` markers and unrelated `openrouter_reasoning_models.json` modification remain).

---

## Phase 2 — Refactor (replace the anti-pattern, prove tests still pass)

### Task 2.1: Rewrite `gsonCodec.decodeFn` to stream via JsonReader

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/integration/IntegrationCodec.kt`

- [ ] **Step 1: Update the imports**

In `IntegrationCodec.kt`, replace the import block at the top of the file:

Old (lines 3-5):
```kotlin
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
```

New (lines 3-7):
```kotlin
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStreamReader
```

- [ ] **Step 2: Rewrite the `gsonCodec<T>()` body**

In `IntegrationCodec.kt`, replace the entire `gsonCodec` factory function (lines 31-40 in the pre-refactor file):

Old:
```kotlin
inline fun <reified T> gsonCodec(gson: Gson = Gson()): IntegrationCodec<T> =
    JsonCodec(
        encodeFn = { value -> gson.toJson(value).toByteArray(Charsets.UTF_8) },
        decodeFn = { bytes ->
            gson.fromJson(
                bytes.toString(Charsets.UTF_8),
                object : TypeToken<T>() {}.type
            )
        }
    )
```

New:
```kotlin
inline fun <reified T> gsonCodec(gson: Gson = Gson()): IntegrationCodec<T> =
    JsonCodec(
        encodeFn = { value -> gson.toJson(value).toByteArray(Charsets.UTF_8) },
        decodeFn = { bytes ->
            // CLAUDE.md rule #3: stream the body via JsonReader instead of
            // gson.fromJson(bytes.toString(Charsets.UTF_8), type). The String
            // overload wraps the body in a StringReader whose .str field pins
            // the entire UTF-16 char[] (~2x body size) for the parse duration —
            // documented in heap dumps as the source of 205 KiB transient char[]
            // orphans on TVDB extended series. Streaming through
            // InputStreamReader + JsonReader keeps the body as the original
            // ByteArray plus a small (~8 KB) CharsetDecoder buffer.
            ByteArrayInputStream(bytes).use { byteStream ->
                InputStreamReader(byteStream, Charsets.UTF_8).use { reader ->
                    JsonReader(reader).use { jsonReader ->
                        gson.fromJson(jsonReader, object : TypeToken<T>() {}.type)
                    }
                }
            }
        }
    )
```

The `.use {}` blocks ensure each layer is closed in reverse order, releasing the JsonReader's internal buffer immediately after parse. This matches the committed reference pattern in `TraktMutationOutboxStore.kt:150`.

- [ ] **Step 3: Build to verify compilation**

```
./gradlew :app:compileUniversalDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run the characterization tests — all 5 MUST still pass**

```
./gradlew :app:testUniversalDebugUnitTest -x generateIntegrationRuntimeAudit \
  --tests "com.nexio.tv.core.integration.GsonCodecStreamingTest"
```

Expected: BUILD SUCCESSFUL with `tests="5" skipped="0" failures="0" errors="0"` in the XML report.

If ANY of the 5 tests fail, do NOT commit. The refactor has changed observable behavior. Re-read the test that failed, compare the expected vs actual, and re-verify against the rewritten code. The most likely failure modes:
- Trailing-comma test now passes (unexpected): the streaming `JsonReader` was constructed in lenient mode. Verify the constructor used is `JsonReader(reader)` (defaults `isLenient = false`), NOT `JsonReader(reader).apply { isLenient = true }`.
- BOM test fails: `InputStreamReader(..., Charsets.UTF_8)` should pass the BOM through transparently. If it surfaces as a `JsonSyntaxException`, the UTF-8 decoder doesn't see it; verify the byte concatenation.
- Round-trip / non-ASCII tests fail: verify the gson instance shared between encode and decode paths is the same default `Gson()`.

- [ ] **Step 5: Run the full app unit-test suite to catch any provider regression**

```
./gradlew :app:testUniversalDebugUnitTest -x generateIntegrationRuntimeAudit
```

Expected: BUILD SUCCESSFUL.

If any test that previously passed now fails, investigate before committing. The most likely failure mode is a provider test that asserts an exact `JsonSyntaxException` message — gson's streaming and String paths may produce slightly different error messages for the same malformed input. The behavior should be equivalent at the type level (both raise `JsonSyntaxException`) but the message text may differ.

- [ ] **Step 6: Run the integration-runtime audit (sanity check)**

```
./gradlew :app:generateIntegrationRuntimeAudit
```

Expected: BUILD SUCCESSFUL with `Verdict: PASS`. The audit is structural; codec internals don't appear, so this should be unchanged from the pre-refactor verdict.

Verify by inspecting the report:
```
grep "^Verdict" app/build/reports/integration-runtime-audit/integration-runtime-audit.md
```

- [ ] **Step 7: Commit**

```bash
git status -sb
git add app/src/main/java/com/nexio/tv/core/integration/IntegrationCodec.kt
git commit -m "$(cat <<'EOF'
perf(integration-codec): stream JSON via gson JsonReader to honor CLAUDE.md rule #3

The gsonCodec<T>() factory's decodeFn was constructing
bytes.toString(Charsets.UTF_8) and feeding the materialized String to
gson.fromJson(String, type) — the documented rule #3 anti-pattern that
pins the body's UTF-16 char[] via StringReader.str for the parse
duration. TVDB extended series reads were observed in heap dumps as
3 x 205 KiB transient char[] orphans from this path.

Rewrite to stream via ByteArrayInputStream + InputStreamReader +
JsonReader, matching the committed reference pattern in
TraktMutationOutboxStore.kt, MetadataDiskCacheStore.kt, and four other
file-backed snapshot stores. The body remains a single ByteArray (the
cache store still hands us bytes); the previous UTF-16 char[] and
StringReader pinning are eliminated.

This is a single-point repair. All ~93 gsonCodec<X>() instantiations
across 15 provider files (TVDB, Trakt, TMDB, MDBList, OMDb, Kitsu,
Real-Debrid, Premiumize, TorBox, EasyDebrid, IntroDB, AniSkip,
AnimeSkip, ARM, OpenSubtitles) inherit the fix automatically. Heaviest
beneficiaries: TVDB extended series, Trakt collection-shows /
watched-shows / calendar / trending lists, TMDB pages.

Behavior is preserved by construction — same gson instance, same
non-lenient JsonReader defaults. Locked by GsonCodecStreamingTest
which pinned the contract against the old impl before the refactor
and continues to pass against the new one.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

Confirm `git status -sb` after the commit shows the working tree is clean of authored files.

---

## Self-Review Notes

This section is for the plan author, not the implementer.

**Spec coverage:**
- Architecture (single-point repair via `gsonCodec.decodeFn` rewrite) → Task 2.1 Step 2.
- Component change (modify `IntegrationCodec.kt`) → Task 2.1 Steps 1–2 (imports + body).
- New test (`GsonCodecStreamingTest` with 5 cases) → Task 1.1 Step 1.
- Risks (gson strict-mode default, close cascade, InputStreamReader buffer) → covered by the trailing-comma test (Task 1.1) and the `.use {}` pattern (Task 2.1 Step 2).
- Test plan (new unit test + existing suite regression guard + audit) → Tasks 1.1 + 2.1 Steps 4–6.
- Implementation phases (Phase 1: test, Phase 2: refactor) → exactly mirrored.
- Out-of-scope items (interface change, cache store change, StringIntegrationCodec, gson reflection) → not touched; no task creates them.

**Type/name consistency:**
- `JsonReader` → consistently `com.google.gson.stream.JsonReader` (Tasks 1.1, 2.1).
- `ByteArrayInputStream` → `java.io.ByteArrayInputStream` (Task 2.1 imports + body).
- `InputStreamReader` → `java.io.InputStreamReader` (Task 2.1 imports + body).
- Test file path matches Components in spec: `app/src/test/java/com/nexio/tv/core/integration/GsonCodecStreamingTest.kt`.
- Production file path matches: `app/src/main/java/com/nexio/tv/core/integration/IntegrationCodec.kt`.
- Function signature `gsonCodec<T>(gson: Gson = Gson()): IntegrationCodec<T>` unchanged across pre/post.

**Placeholder scan:** No "TBD", "TODO", "implement later", or unbacked references. Every code step ships complete code. Every command shows the exact expected output.

**Discipline (TDD-flavored for a refactor):** Characterization test lands BEFORE the refactor, passes against the old code, then continues to pass against the new code. This is the right TDD pattern for behavior-preserving refactors — write the test, observe it pass, change the code, observe it still pass. Failing-first is impossible here because the contract being tested already exists.

**Removed from scope:**
- StringIntegrationCodec rewrite (no anti-pattern — String IS the output, not a parse input; documented as Non-Goal in spec).
- Cache store interface change to `BufferedSource` / `InputStream` (deferred follow-up; documented as Non-Goal).
- gson reflection adapter caching (separate concern from the 2026-05-11 ANR notes; out of scope).
- Provider-level migration to Moshi / kotlinx-serialization (out of scope; would touch 93 sites + DTOs).
