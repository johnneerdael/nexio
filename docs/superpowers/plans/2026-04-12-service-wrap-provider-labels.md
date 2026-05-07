# Service Wrap Provider Labels Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ensure wrapped streams use `Stream.wrappedProviderId` as the authoritative debrid service label so service wrap cannot surface TorBox or EasyDebrid labels when the resolved provider is Real-Debrid or Premiumize.

**Architecture:** Keep the fix local to the AIO stream parsing/formatting boundary. `AioStrictStreamParser` should set `ParsedStreamInfo.serviceId` from `wrappedProviderId` before text heuristics run, and `AioParseValueFactory` should use the same source in its defensive fallback path. Do not change service wrap construction, addon parsing presets, stream grouping, or legacy parser code that is not on the active presentation path.

**Tech Stack:** Android/Kotlin, JUnit4 unit tests, Gradle `arm64Debug` build/test tasks.

---

## File Structure

- Modify: `app/src/test/java/com/nexio/tv/core/stream/AioStrictStreamParserParityTest.kt`
  - Add the regression test for wrapped streams whose text mentions TorBox while `wrappedProviderId` is `RD`.
  - Extend the local `stream(...)` test helper with an optional `wrappedProviderId` parameter.
- Modify: `app/src/main/java/com/nexio/tv/core/stream/AioStrictStreamParser.kt`
  - Add the early `wrappedProviderId` return at the top of `deriveServiceId(stream, description, filename)`.
- Modify: `app/src/test/java/com/nexio/tv/core/stream/AioParseValueFactoryTest.kt`
  - Add a defensive fallback test proving `AioParseValueFactory` trusts `wrappedProviderId` when `parsed.serviceId` is null.
  - Extend the local `stream(...)` test helper with an optional `wrappedProviderId` parameter.
- Modify: `app/src/main/java/com/nexio/tv/core/stream/AioStreamFormatting.kt`
  - Add the early `wrappedProviderId` return at the top of `deriveServiceId(stream)`.
- Do not modify: `app/src/main/java/com/nexio/tv/data/repository/servicewrap/WrappedStreamBuilder.kt`
  - It already sets `wrappedProviderId = resolved.provider.providerId`.
- Do not modify for this fix: `app/src/main/java/com/nexio/tv/core/stream/StreamPresentationModels.kt`
  - The active presentation path calls `AioStrictStreamParser.parse(stream)` and then `AioParseValueFactory.from(...)`; this plan keeps the fix to the requested AIO parser and formatter paths.

## Task 1: Add Parser Regression Test

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/core/stream/AioStrictStreamParserParityTest.kt:65`
- Modify: `app/src/test/java/com/nexio/tv/core/stream/AioStrictStreamParserParityTest.kt:163`

- [ ] **Step 1: Add a failing regression test**

Insert this test after `stream parser defaults matched debrid service to uncached when no cache marker exists`:

```kotlin
    @Test
    fun `stream parser trusts wrapped provider id over debrid text matches`() {
        val stream = stream(
            filename = "Movie.Title.2023.2160p.BluRay.HEVC.TrueHD.Atmos.7.1-GROUP.mkv",
            description = """
                📄 Movie.Title.2023.2160p.BluRay.HEVC.TrueHD.Atmos.7.1-GROUP.mkv
                🔍 TorBox
            """.trimIndent(),
            name = "TorBox",
            wrappedProviderId = "RD"
        )

        val parsed = AioStrictStreamParser.parse(stream)

        assertEquals("RD", parsed.serviceId)
        assertEquals(false, parsed.isCached)
        assertEquals(StreamTransportKind.UNCACHED, parsed.transportKind)
    }
```

Change the private helper signature from:

```kotlin
    private fun stream(
        filename: String,
        description: String,
        name: String? = null,
        preset: AddonParserPreset = AddonParserPreset.GENERIC
    ): Stream {
```

to:

```kotlin
    private fun stream(
        filename: String,
        description: String,
        name: String? = null,
        preset: AddonParserPreset = AddonParserPreset.GENERIC,
        wrappedProviderId: String? = null
    ): Stream {
```

Change the helper's `Stream(...)` call from:

```kotlin
            addonName = "Test Addon",
            addonLogo = null,
            addonParserPreset = preset
        )
```

to:

```kotlin
            addonName = "Test Addon",
            addonLogo = null,
            addonParserPreset = preset,
            wrappedProviderId = wrappedProviderId
        )
```

- [ ] **Step 2: Run the targeted parser test and confirm it fails**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests com.nexio.tv.core.stream.AioStrictStreamParserParityTest
```

Expected: `stream parser trusts wrapped provider id over debrid text matches` fails with an assertion equivalent to `expected:<RD> but was:<TB>`. Existing tests in the class should not need changes.

## Task 2: Trust `wrappedProviderId` In The Strict Parser

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/stream/AioStrictStreamParser.kt:196`
- Test: `app/src/test/java/com/nexio/tv/core/stream/AioStrictStreamParserParityTest.kt`

- [ ] **Step 1: Add the authoritative provider guard**

Change `deriveServiceId(...)` from:

```kotlin
    private fun deriveServiceId(stream: Stream, description: String, filename: String?): String? {
        val descriptionSignals = description.lines()
```

to:

```kotlin
    private fun deriveServiceId(stream: Stream, description: String, filename: String?): String? {
        stream.wrappedProviderId?.takeIf { it.isNotBlank() }?.let { return it }

        val descriptionSignals = description.lines()
```

- [ ] **Step 2: Run the targeted parser test and confirm it passes**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests com.nexio.tv.core.stream.AioStrictStreamParserParityTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit the parser regression fix**

Run:

```bash
git add app/src/main/java/com/nexio/tv/core/stream/AioStrictStreamParser.kt app/src/test/java/com/nexio/tv/core/stream/AioStrictStreamParserParityTest.kt
git commit -m "fix: trust wrapped provider labels in aio parser"
```

Expected: commit succeeds with only those two files staged for this commit.

## Task 3: Add Formatter Fallback Regression Test

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/core/stream/AioParseValueFactoryTest.kt:76`
- Modify: `app/src/test/java/com/nexio/tv/core/stream/AioParseValueFactoryTest.kt:166`

- [ ] **Step 1: Add a failing formatter fallback test**

Insert this test after `debrid streams resolve debrid type and service names`:

```kotlin
    @Test
    fun `parse value fallback trusts wrapped provider id over stream text`() {
        val stream = stream(
            filename = "Movie.Title.2023.2160p.BluRay.HEVC.TrueHD.Atmos.7.1-GROUP.mkv",
            name = "TorBox",
            description = """
                📄 Movie.Title.2023.2160p.BluRay.HEVC.TrueHD.Atmos.7.1-GROUP.mkv
                🔍 TorBox
            """.trimIndent(),
            wrappedProviderId = "PM"
        )
        val parsed = AioStrictStreamParser.parse(stream).copy(serviceId = null)

        val parseValue = AioParseValueFactory.from(stream, parsed)

        assertEquals("PM", parseValue.service.id)
        assertEquals("Premiumize", parseValue.service.name)
        assertEquals("debrid", parseValue.stream.type)
    }
```

Change the private helper signature from:

```kotlin
    private fun stream(
        filename: String,
        name: String? = null,
        description: String? = filename,
        bingeGroup: String? = null,
        videoHash: String? = null
    ): Stream {
```

to:

```kotlin
    private fun stream(
        filename: String,
        name: String? = null,
        description: String? = filename,
        bingeGroup: String? = null,
        videoHash: String? = null,
        wrappedProviderId: String? = null
    ): Stream {
```

Change the helper's `Stream(...)` call from:

```kotlin
            addonName = "Test Addon",
            addonLogo = null,
            addonParserPreset = AddonParserPreset.GENERIC
        )
```

to:

```kotlin
            addonName = "Test Addon",
            addonLogo = null,
            addonParserPreset = AddonParserPreset.GENERIC,
            wrappedProviderId = wrappedProviderId
        )
```

- [ ] **Step 2: Run the targeted formatter test and confirm it fails**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests com.nexio.tv.core.stream.AioParseValueFactoryTest
```

Expected: `parse value fallback trusts wrapped provider id over stream text` fails with an assertion equivalent to `expected:<PM> but was:<TB>`.

## Task 4: Trust `wrappedProviderId` In The Formatter Fallback

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/stream/AioStreamFormatting.kt:1388`
- Test: `app/src/test/java/com/nexio/tv/core/stream/AioParseValueFactoryTest.kt`

- [ ] **Step 1: Add the defensive provider guard**

Change `deriveServiceId(stream)` from:

```kotlin
    private fun deriveServiceId(stream: Stream): String? {
        val lowered = listOfNotNull(stream.name, stream.description, stream.addonName)
```

to:

```kotlin
    private fun deriveServiceId(stream: Stream): String? {
        stream.wrappedProviderId?.takeIf { it.isNotBlank() }?.let { return it }

        val lowered = listOfNotNull(stream.name, stream.description, stream.addonName)
```

- [ ] **Step 2: Run the targeted formatter test and confirm it passes**

Run:

```bash
./gradlew testArm64DebugUnitTest --tests com.nexio.tv.core.stream.AioParseValueFactoryTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit the formatter fallback fix**

Run:

```bash
git add app/src/main/java/com/nexio/tv/core/stream/AioStreamFormatting.kt app/src/test/java/com/nexio/tv/core/stream/AioParseValueFactoryTest.kt
git commit -m "fix: trust wrapped provider labels in aio formatter"
```

Expected: commit succeeds with only those two files staged for this commit.

## Task 5: Run Final Verification

**Files:**
- Read only: all files modified in Tasks 1-4.

- [ ] **Step 1: Run the full requested unit test task**

Run:

```bash
./gradlew testArm64DebugUnitTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Run the requested debug build**

Run:

```bash
./gradlew assembleArm64Debug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Inspect the final diff**

Run:

```bash
git diff --stat HEAD~2..HEAD
```

Expected: the diff includes only:

```text
app/src/main/java/com/nexio/tv/core/stream/AioStrictStreamParser.kt
app/src/main/java/com/nexio/tv/core/stream/AioStreamFormatting.kt
app/src/test/java/com/nexio/tv/core/stream/AioStrictStreamParserParityTest.kt
app/src/test/java/com/nexio/tv/core/stream/AioParseValueFactoryTest.kt
```

- [ ] **Step 4: Perform manual validation on device**

Use a profile with:

```text
Service wrap: ON
Configured debrid providers: Real-Debrid and Premiumize only
Addon debrid providers: Real-Debrid and Premiumize only
```

Expected: stream selection shows no TorBox or EasyDebrid service labels for wrapped streams. Wrapped Real-Debrid streams label as Real-Debrid, and wrapped Premiumize streams label as Premiumize.

## Self-Review Notes

- Spec coverage: parser guard, formatter fallback guard, targeted parser unit test, build/test verification, and manual PM/RD-only validation are covered.
- Red-flag wording scan: the plan contains no unresolved template text and no deferred implementation steps.
- Type consistency: all snippets use the existing `Stream.wrappedProviderId`, `AioStrictStreamParser.parse(stream)`, `AioParseValueFactory.from(stream, parsed)`, and JUnit4 `assertEquals` APIs already present in the touched tests.
