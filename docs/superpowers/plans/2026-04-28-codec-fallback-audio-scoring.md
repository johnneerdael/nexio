# Codec-Fallback Audio Scoring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the autoplay scorer emit candidate audio tier *ladders* instead of single tiers, so a track resolves to the highest layer the receiver can actually decode (Atmos→DDP base on WEB-DL, Atmos→TrueHD base on BluRay/REMUX, DTS:X→DTS-HD MA→DTS core).

**Architecture:** Single-file change to `BenchmarkAwareStreamScorer.kt`. (1) Thread `ShadowReleaseType` into `resolveAudioScoringDecision` and `detectAudioTierCandidates`. (2) Replace `detectAudioTierCandidates` body with ladder construction per the spec table. (3) Tighten `audioTierSupported` for the two Atmos tiers so they require genuine Atmos passthrough — the ladder carries the base layer for fallback. New TDD test file covers the 18-row matrix from the spec.

**Tech Stack:** Kotlin, Android SDK 34, JUnit 4, MockK (already in use). Test runner: `./gradlew :app:testDebugUnitTest`.

**Spec:** `docs/superpowers/specs/2026-04-28-codec-fallback-audio-scoring-design.md` is the source of truth for ladder rules, edge cases, and the test matrix.

---

## File Structure

- **Modify** `app/src/main/java/com/nexio/tv/data/repository/benchmark/BenchmarkAwareStreamScorer.kt`:
  - `buildContentScoreBreakdown` (lines 566-624): one-line change at the audio resolution call site to pass `releaseType`.
  - `resolveAudioScoringDecision` (lines 977-1004): gains a `releaseType: ShadowReleaseType` parameter, threads it to `detectAudioTierCandidates`.
  - `detectAudioTierCandidates` (lines 1006-1034): gains a `releaseType: ShadowReleaseType` parameter; body fully replaced with ladder construction.
  - `audioTierSupported` (lines 1036-1052): two-line tightening on the Atmos tiers.
- **Create** `app/src/test/java/com/nexio/tv/data/repository/benchmark/BenchmarkAwareAudioFallbackScoringTest.kt`: new TDD test file with the 18-case matrix.
- **No other test file modifications expected.** `BenchmarkAwareScoringHarnessTest.kt` exercises the public scorer entry point (which already passes `releaseType` through `evaluateStreamWithManualCap`); it should continue to pass without edits. If it fails, surface as a BLOCKED status — likely it's calling a private function we didn't anticipate.

The change touches 4 contiguous regions in one file. Within those regions everything is internal; no public API or DI binding changes.

---

## Task 1: Failing test scaffold for ladder-construction unit cases

**Files:**
- Create: `app/src/test/java/com/nexio/tv/data/repository/benchmark/BenchmarkAwareAudioFallbackScoringTest.kt`

These tests exercise `detectAudioTierCandidates(tags, device, releaseType)` directly to assert the candidate-list shape. Calling a private function from a test in the same package works in Kotlin only if we drop the `private` qualifier or move the function up to package-internal. Since the spec test seam relies on direct calls, **Task 2 will change the function visibility to `internal`** (file-private to the module). For now, the test file references the function with `internal` access and will fail to compile.

- [ ] **Step 1: Create the test file with all 18 spec cases**

```kotlin
// app/src/test/java/com/nexio/tv/data/repository/benchmark/BenchmarkAwareAudioFallbackScoringTest.kt
package com.nexio.tv.data.repository.benchmark

import org.junit.Assert.assertEquals
import org.junit.Test

class BenchmarkAwareAudioFallbackScoringTest {

    // ---------- Helpers -------------------------------------------------

    private fun audio(
        ac3: Boolean = false,
        eac3: Boolean = false,
        atmos: Boolean = false,
        truehd: Boolean = false,
        dts: Boolean = false,
        dtshd: Boolean = false,
        dtsx: Boolean = false
    ): DeviceAudioOutputCapabilities = DeviceAudioOutputCapabilities(
        ac3 = AudioEncodingSupport(supported = ac3, passthroughLikely = ac3),
        eac3 = AudioEncodingSupport(supported = eac3, passthroughLikely = eac3),
        atmos = AudioEncodingSupport(supported = atmos, passthroughLikely = atmos),
        truehd = AudioEncodingSupport(supported = truehd, passthroughLikely = truehd),
        dts = AudioEncodingSupport(supported = dts, passthroughLikely = dts),
        dtshd = AudioEncodingSupport(supported = dtshd, passthroughLikely = dtshd),
        dtsx = AudioEncodingSupport(supported = dtsx, passthroughLikely = dtsx)
    )

    private fun snapshot(audio: DeviceAudioOutputCapabilities) = DeviceCapabilitySnapshot(
        model = "Test Device",
        manufacturer = "Acme",
        sdkInt = 34,
        displayHdrTypes = emptySet(),
        videoDecode = DeviceVideoDecodeCapabilities(),
        audioOutput = audio,
        evidence = null,
        capturedAtMs = 1L
    )

    private fun resolve(
        tags: List<String>,
        audio: DeviceAudioOutputCapabilities,
        release: ShadowReleaseType
    ): ShadowAudioScoringDecision = resolveAudioScoringDecision(tags, snapshot(audio), release)

    private val basePoints = mapOf(
        ShadowAudioTier.TRUEHD_ATMOS to 16,
        ShadowAudioTier.DTSX to 16,
        ShadowAudioTier.DDP_ATMOS to 16,
        ShadowAudioTier.TRUEHD to 12,
        ShadowAudioTier.DTSHD to 12,
        ShadowAudioTier.DDP to 10,
        ShadowAudioTier.AC3 to 7,
        ShadowAudioTier.DTS to 7,
        ShadowAudioTier.OTHER to 0
    )

    private fun expectedScore(tier: ShadowAudioTier, supported: Boolean): Int {
        val base = basePoints.getValue(tier)
        return if (supported) base else -base
    }

    // ---------- Cases 1–4: explicit co-tag path -------------------------

    @Test fun `case 1 atmos plus ddp on eac3-only resolves to DDP`() {
        val d = resolve(listOf("atmos", "ddp"), audio(eac3 = true), ShadowReleaseType.WEBDL)
        assertEquals(ShadowAudioTier.DDP, d.effectiveTier)
        assertEquals(true, d.supported)
        assertEquals(10, expectedScore(d.effectiveTier, d.supported))
    }

    @Test fun `case 2 atmos plus ddp on atmos plus eac3 resolves to DDP_ATMOS`() {
        val d = resolve(listOf("atmos", "ddp"), audio(atmos = true, eac3 = true), ShadowReleaseType.WEBDL)
        assertEquals(ShadowAudioTier.DDP_ATMOS, d.effectiveTier)
        assertEquals(true, d.supported)
        assertEquals(16, expectedScore(d.effectiveTier, d.supported))
    }

    @Test fun `case 3 atmos plus truehd on truehd-only resolves to TRUEHD`() {
        val d = resolve(listOf("atmos", "truehd"), audio(truehd = true), ShadowReleaseType.REMUX)
        assertEquals(ShadowAudioTier.TRUEHD, d.effectiveTier)
        assertEquals(true, d.supported)
        assertEquals(12, expectedScore(d.effectiveTier, d.supported))
    }

    @Test fun `case 4 atmos plus truehd on atmos plus truehd resolves to TRUEHD_ATMOS`() {
        val d = resolve(listOf("atmos", "truehd"), audio(atmos = true, truehd = true), ShadowReleaseType.REMUX)
        assertEquals(ShadowAudioTier.TRUEHD_ATMOS, d.effectiveTier)
        assertEquals(true, d.supported)
        assertEquals(16, expectedScore(d.effectiveTier, d.supported))
    }

    // ---------- Cases 5–8: release-type default for tag-only Atmos ------

    @Test fun `case 5 atmos only on WEBDL eac3-only resolves to DDP`() {
        val d = resolve(listOf("atmos"), audio(eac3 = true), ShadowReleaseType.WEBDL)
        assertEquals(ShadowAudioTier.DDP, d.effectiveTier)
        assertEquals(true, d.supported)
    }

    @Test fun `case 6 atmos only on REMUX truehd-only resolves to TRUEHD`() {
        val d = resolve(listOf("atmos"), audio(truehd = true), ShadowReleaseType.REMUX)
        assertEquals(ShadowAudioTier.TRUEHD, d.effectiveTier)
        assertEquals(true, d.supported)
    }

    @Test fun `case 7 atmos only on UNKNOWN release with eac3-only resolves to DDP`() {
        val d = resolve(listOf("atmos"), audio(eac3 = true), ShadowReleaseType.UNKNOWN)
        assertEquals(ShadowAudioTier.DDP, d.effectiveTier)
        assertEquals(true, d.supported)
    }

    @Test fun `case 8 atmos only on BLURAY_ENCODE with atmos resolves to TRUEHD_ATMOS`() {
        val d = resolve(listOf("atmos"), audio(atmos = true), ShadowReleaseType.BLURAY_ENCODE)
        assertEquals(ShadowAudioTier.TRUEHD_ATMOS, d.effectiveTier)
        assertEquals(true, d.supported)
    }

    // ---------- Cases 9–13: DTS family ladder ---------------------------

    @Test fun `case 9 dtsx on dtshd-only resolves to DTSHD`() {
        val d = resolve(listOf("dts:x"), audio(dtshd = true), ShadowReleaseType.REMUX)
        assertEquals(ShadowAudioTier.DTSHD, d.effectiveTier)
        assertEquals(true, d.supported)
    }

    @Test fun `case 10 dtsx on dts-only resolves to DTS`() {
        val d = resolve(listOf("dts:x"), audio(dts = true), ShadowReleaseType.REMUX)
        assertEquals(ShadowAudioTier.DTS, d.effectiveTier)
        assertEquals(true, d.supported)
    }

    @Test fun `case 11 dtsx on full dts stack resolves to DTSX`() {
        val d = resolve(listOf("dts:x"), audio(dtsx = true, dtshd = true, dts = true), ShadowReleaseType.REMUX)
        assertEquals(ShadowAudioTier.DTSX, d.effectiveTier)
        assertEquals(true, d.supported)
    }

    @Test fun `case 12 dtshd on dts-only resolves to DTS`() {
        val d = resolve(listOf("dts-hd"), audio(dts = true), ShadowReleaseType.BLURAY_ENCODE)
        assertEquals(ShadowAudioTier.DTS, d.effectiveTier)
        assertEquals(true, d.supported)
    }

    @Test fun `case 13 dtshd on dtshd resolves to DTSHD`() {
        val d = resolve(listOf("dts-hd"), audio(dtshd = true), ShadowReleaseType.BLURAY_ENCODE)
        assertEquals(ShadowAudioTier.DTSHD, d.effectiveTier)
        assertEquals(true, d.supported)
    }

    // ---------- Cases 14–15: no speculative AC3 fallback ----------------

    @Test fun `case 14 truehd on ac3-only resolves to TRUEHD unsupported`() {
        val d = resolve(listOf("truehd"), audio(ac3 = true), ShadowReleaseType.REMUX)
        assertEquals(ShadowAudioTier.TRUEHD, d.effectiveTier)
        assertEquals(false, d.supported)
        assertEquals(-12, expectedScore(d.effectiveTier, d.supported))
    }

    @Test fun `case 15 ddp on ac3-only resolves to DDP unsupported`() {
        val d = resolve(listOf("ddp"), audio(ac3 = true), ShadowReleaseType.WEBDL)
        assertEquals(ShadowAudioTier.DDP, d.effectiveTier)
        assertEquals(false, d.supported)
        assertEquals(-10, expectedScore(d.effectiveTier, d.supported))
    }

    // ---------- Cases 16–17: explicit co-tag overrides release type ----

    @Test fun `case 16 atmos plus truehd on WEBDL release still uses TRUEHD ladder`() {
        val d = resolve(listOf("atmos", "truehd"), audio(atmos = true), ShadowReleaseType.WEBDL)
        assertEquals(ShadowAudioTier.TRUEHD_ATMOS, d.effectiveTier)
        assertEquals(true, d.supported)
    }

    @Test fun `case 17 atmos plus ddp on REMUX release still uses DDP ladder`() {
        val d = resolve(listOf("atmos", "ddp"), audio(atmos = true), ShadowReleaseType.REMUX)
        assertEquals(ShadowAudioTier.DDP_ATMOS, d.effectiveTier)
        assertEquals(true, d.supported)
    }

    // ---------- Case 18: nothing recognized -----------------------------

    @Test fun `case 18 empty audio tags resolves to OTHER`() {
        val d = resolve(emptyList(), audio(ac3 = true, eac3 = true, atmos = true, truehd = true, dts = true, dtshd = true, dtsx = true), ShadowReleaseType.WEBDL)
        assertEquals(ShadowAudioTier.OTHER, d.effectiveTier)
        assertEquals(true, d.supported)
        assertEquals(0, expectedScore(d.effectiveTier, d.supported))
    }
}
```

- [ ] **Step 2: Run the file and confirm it fails to compile**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.benchmark.BenchmarkAwareAudioFallbackScoringTest" 2>&1 | tail -30`

Expected: compilation failure. Two distinct error categories:
1. `Cannot access 'resolveAudioScoringDecision': it is private in file` (or similar) — because the function is currently `private`. Task 2 changes this to `internal`.
2. **Even if visibility were OK**, the test invocations pass three args but `resolveAudioScoringDecision` currently takes two — so we'd see `Too many arguments` or `No value passed for parameter 'releaseType'`. Task 2 adds the parameter.

If the failure is something different (e.g., import resolution issue, test framework issue), STOP and report — the rest of the plan assumes the failure is in the function signature.

- [ ] **Step 3: Commit the failing test**

```bash
git add app/src/test/java/com/nexio/tv/data/repository/benchmark/BenchmarkAwareAudioFallbackScoringTest.kt
git commit -m "test(autoplay): add failing audio fallback ladder test matrix"
```

The unrelated `media`/`tmp/` items in `git status` must NOT be staged.

---

## Task 2: Thread `releaseType` through the audio resolution chain

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/benchmark/BenchmarkAwareStreamScorer.kt:582` (call site)
- Modify: `app/src/main/java/com/nexio/tv/data/repository/benchmark/BenchmarkAwareStreamScorer.kt:977-1004` (`resolveAudioScoringDecision`)
- Modify: `app/src/main/java/com/nexio/tv/data/repository/benchmark/BenchmarkAwareStreamScorer.kt:1006-1034` (`detectAudioTierCandidates`)

This task adds the `releaseType` parameter to both audio functions and changes their visibility from `private` to `internal` so the test file can call them. **Body changes to `detectAudioTierCandidates` happen in Task 3** — this task is plumbing only.

- [ ] **Step 1: Change `resolveAudioScoringDecision` signature and visibility**

In `BenchmarkAwareStreamScorer.kt`, find:

```kotlin
private fun resolveAudioScoringDecision(
    tags: List<String>,
    device: DeviceCapabilitySnapshot?
): ShadowAudioScoringDecision {
    val candidates = detectAudioTierCandidates(tags)
    if (candidates.isEmpty()) {
```

Replace with:

```kotlin
internal fun resolveAudioScoringDecision(
    tags: List<String>,
    device: DeviceCapabilitySnapshot?,
    releaseType: ShadowReleaseType
): ShadowAudioScoringDecision {
    val candidates = detectAudioTierCandidates(tags, releaseType)
    if (candidates.isEmpty()) {
```

Note the three changes: `private` → `internal`, added `releaseType` parameter, and the inner call to `detectAudioTierCandidates` now passes `releaseType`.

- [ ] **Step 2: Change `detectAudioTierCandidates` signature and visibility**

Find:

```kotlin
private fun detectAudioTierCandidates(tags: List<String>): List<ShadowAudioTier> {
    val normalized = tags.map { it.lowercase(Locale.US) }
```

Replace with:

```kotlin
internal fun detectAudioTierCandidates(
    tags: List<String>,
    releaseType: ShadowReleaseType
): List<ShadowAudioTier> {
    val normalized = tags.map { it.lowercase(Locale.US) }
```

Two changes: `private` → `internal`, added `releaseType` parameter. **Body unchanged for now** — it ignores `releaseType` until Task 3.

- [ ] **Step 3: Update the call site in `buildContentScoreBreakdown`**

Find at line 582:

```kotlin
val audioDecision = resolveAudioScoringDecision(parsed.audioTags, device)
```

Replace with:

```kotlin
val audioDecision = resolveAudioScoringDecision(parsed.audioTags, device, releaseType)
```

`buildContentScoreBreakdown` already has `releaseType: ShadowReleaseType` in scope (parameter at line 570).

- [ ] **Step 4: Build the project to confirm compilation**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -20`

Expected: BUILD SUCCESSFUL. The new tests should now compile (but mostly fail at runtime, since the body still doesn't honor `releaseType`).

- [ ] **Step 5: Run the new test class to baseline failures**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.benchmark.BenchmarkAwareAudioFallbackScoringTest" 2>&1 | tail -40`

Expected: many tests fail (the body of `detectAudioTierCandidates` still uses the old logic). Specifically:
- Cases 1, 5: today's code adds `[DDP_ATMOS]` for `[atmos, ddp]` candidates — `audioTierSupported(DDP_ATMOS)` returns true via the `|| eac3` fallback, so resolved tier is `DDP_ATMOS`, not `DDP`. Test fails.
- Cases 9, 10, 12: today's code emits only `[DTSX]` or `[DTSHD]` — no fallback to lower DTS layers. Test fails.
- Cases 16, 17 may pass coincidentally because the existing co-tag check still works.

This is the expected halfway state — Task 3 makes them pass.

- [ ] **Step 6: Run the existing harness test to confirm we didn't break anything**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.benchmark.BenchmarkAwareScoringHarnessTest" 2>&1 | tail -15`

Expected: BUILD SUCCESSFUL. Existing tests use the public scorer entry point which already passes `releaseType` down through `buildContentScoreBreakdown`; nothing about their behavior should change.

If a harness test fails, the most likely cause is that it called `resolveAudioScoringDecision` or `detectAudioTierCandidates` directly with the old signature. Look for the first compile error and add the `releaseType` argument inline in that test.

- [ ] **Step 7: Commit the plumbing**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/benchmark/BenchmarkAwareStreamScorer.kt
git commit -m "refactor(autoplay): thread releaseType through audio scoring resolution"
```

---

## Task 3: Replace `detectAudioTierCandidates` body with ladder construction

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/benchmark/BenchmarkAwareStreamScorer.kt:1006-1034` (`detectAudioTierCandidates` body)

This task replaces the candidate-emission logic per the spec ladder rules. The function signature already takes `releaseType` after Task 2.

- [ ] **Step 1: Replace the body**

Find the current body of `detectAudioTierCandidates` (after the `internal fun` signature) — the `val normalized` line through the closing `}.distinct()`. Replace with this new body:

```kotlin
    val normalized = tags.map { it.lowercase(Locale.US) }
    val hasAtmos = normalized.any { it.contains("atmos") }
    val hasTrueHd = normalized.any { it.contains("truehd") }
    val hasDtsX = normalized.any { it.contains("dts:x") || it.contains("dtsx") }
    val hasDtsHd = normalized.any { it.contains("dts-hd") }
    val hasDdp = normalized.any { it.contains("dd+") || it.contains("eac3") || it.contains("ddp") }
    val hasAc3 = normalized.any { it == "dd" || it.contains("ac3") }
    val hasDts = normalized.any { it == "dts" }

    // Source-format-aware Atmos disambiguation. Explicit container co-tags (truehd / ddp)
    // override the release-type heuristic — the title metadata is more reliable than the
    // release classifier for rare cross-source cases (e.g. "BluRay.Atmos.DDP+5.1").
    val isLosslessReleaseType = releaseType == ShadowReleaseType.REMUX ||
        releaseType == ShadowReleaseType.BLURAY_ENCODE

    return buildList {
        when {
            // Explicit TrueHD co-tag (or both co-tags present — TrueHD wins because it
            // implies the higher-quality source).
            hasAtmos && hasTrueHd -> {
                add(ShadowAudioTier.TRUEHD_ATMOS)
                add(ShadowAudioTier.TRUEHD)
            }
            // Explicit DDP co-tag.
            hasAtmos && hasDdp -> {
                add(ShadowAudioTier.DDP_ATMOS)
                add(ShadowAudioTier.DDP)
            }
            // Atmos with no container co-tag — disambiguate by release type.
            hasAtmos && isLosslessReleaseType -> {
                add(ShadowAudioTier.TRUEHD_ATMOS)
                add(ShadowAudioTier.TRUEHD)
            }
            hasAtmos -> {
                // Default for WEB-DL / WEBRIP / encodes / unknown — DDP base is the
                // most-permissive assumption and matches streaming-source reality.
                add(ShadowAudioTier.DDP_ATMOS)
                add(ShadowAudioTier.DDP)
            }
        }

        // DTS family — full backward-compat chain.
        if (hasDtsX) {
            add(ShadowAudioTier.DTSX)
            add(ShadowAudioTier.DTSHD)
            add(ShadowAudioTier.DTS)
        } else if (hasDtsHd) {
            add(ShadowAudioTier.DTSHD)
            add(ShadowAudioTier.DTS)
        }

        // Non-Atmos lossless / lossy formats — no speculative fallback below their tier.
        if (hasTrueHd && !hasAtmos) add(ShadowAudioTier.TRUEHD)
        if (hasDdp && !hasAtmos) add(ShadowAudioTier.DDP)
        if (hasAc3) add(ShadowAudioTier.AC3)
        if (hasDts && !hasDtsHd && !hasDtsX) add(ShadowAudioTier.DTS)
    }.distinct()
```

Notes for the reader:
- The `when` block is exhaustive only for the Atmos cases — all four branches handle `hasAtmos`. When `hasAtmos` is false, the `when` block does nothing (no `else` branch), and execution falls through to the DTS / non-Atmos blocks.
- The `hasDts && !hasDtsHd && !hasDtsX` guard prevents emitting `DTS` twice when DTS:X or DTS-HD is already present (the DTS family ladder above already handles those cases).
- `hasTrueHd && !hasAtmos` and `hasDdp && !hasAtmos` mirror the original code's intent: a non-Atmos TrueHD or DDP track gets just its tier, no fallback. Atmos cases above already added the appropriate base layer.
- `.distinct()` is preserved for safety against any duplicate insertions.

- [ ] **Step 2: Run the new test file**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.benchmark.BenchmarkAwareAudioFallbackScoringTest" 2>&1 | tail -30`

Expected: most tests now pass, but cases 1, 2, 14, 15 likely still fail because `audioTierSupported(DDP_ATMOS)` returns `true` whenever `eac3.passthroughLikely` is true (the masking bug from the spec). For example:
- Case 1: `[atmos, ddp]` on eac3-only → candidates `[DDP_ATMOS, DDP]` → `audioTierSupported(DDP_ATMOS)` = `atmos OR eac3` = `false OR true` = `true` → resolved tier is `DDP_ATMOS`, score 16. Expected was `DDP`, score 10. **FAIL.**
- Case 14: `[truehd]` on ac3-only → candidates `[TRUEHD]` → `audioTierSupported(TRUEHD)` = `truehd` = `false` → unsupported → minByOrNull picks `TRUEHD` → score -12. **PASS.** (This case actually does pass now.)

Don't fix yet — Task 4 fixes `audioTierSupported`, which makes these last cases pass.

- [ ] **Step 3: Commit the ladder body**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/benchmark/BenchmarkAwareStreamScorer.kt
git commit -m "feat(autoplay): emit codec-fallback ladder candidates by release type"
```

---

## Task 4: Tighten `audioTierSupported` for Atmos tiers

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/benchmark/BenchmarkAwareStreamScorer.kt:1041-1046` (two lines inside `audioTierSupported`)

The two Atmos tiers must require *actual* Atmos passthrough — the candidate ladder built in Task 3 already carries the base-layer tier for fallback.

- [ ] **Step 1: Update `audioTierSupported` Atmos branches**

Find:

```kotlin
        ShadowAudioTier.TRUEHD_ATMOS -> output.truehd.passthroughLikely
        ShadowAudioTier.DTSX -> output.dtsx.passthroughLikely
        ShadowAudioTier.TRUEHD -> output.truehd.passthroughLikely
        ShadowAudioTier.DTSHD -> output.dtshd.passthroughLikely
        ShadowAudioTier.DDP_ATMOS -> output.atmos.passthroughLikely || output.eac3.passthroughLikely
```

Replace with:

```kotlin
        ShadowAudioTier.TRUEHD_ATMOS -> output.atmos.passthroughLikely
        ShadowAudioTier.DTSX -> output.dtsx.passthroughLikely
        ShadowAudioTier.TRUEHD -> output.truehd.passthroughLikely
        ShadowAudioTier.DTSHD -> output.dtshd.passthroughLikely
        ShadowAudioTier.DDP_ATMOS -> output.atmos.passthroughLikely
```

Two lines change: the `TRUEHD_ATMOS` branch (was `output.truehd.passthroughLikely`) and the `DDP_ATMOS` branch (was `output.atmos.passthroughLikely || output.eac3.passthroughLikely`). Both now require genuine Atmos passthrough. The other Atmos-irrelevant branches (`DTSX`, `TRUEHD`, `DTSHD`, `DDP`, `AC3`, `DTS`, `OTHER`) are unchanged.

- [ ] **Step 2: Run the new test file and confirm all 18 cases pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.repository.benchmark.BenchmarkAwareAudioFallbackScoringTest" 2>&1 | tail -25`

Expected: BUILD SUCCESSFUL, all 18 tests PASS.

If any case still fails, read the assertion message carefully — the mismatch will tell you which ladder rule is wrong. Likely culprits: a typo in a `when` branch, a missed `releaseType` value (e.g., I forgot `BLURAY_ENCODE` belongs in `isLosslessReleaseType`), or a stale `.distinct()` reordering.

- [ ] **Step 3: Run the entire `:app:testDebugUnitTest` suite**

Run: `./gradlew :app:testDebugUnitTest 2>&1 | tail -10`

Expected: BUILD SUCCESSFUL.

If existing tests fail (most likely `BenchmarkAwareScoringHarnessTest` if its fixtures relied on the old "Atmos-on-DDP scores 16 on E-AC3-only" behavior), inspect each failure individually:
- If the failure is fixture data that explicitly modeled the old buggy behavior → update the fixture's expected score per the new ladder semantics. Document in the commit.
- If the failure is a regression in unrelated logic → STOP and report. The plan does not anticipate non-audio regressions.

- [ ] **Step 4: Build a debug APK to confirm Hilt graph and ProGuard pass**

Run: `./gradlew :app:assembleDebug 2>&1 | tail -8`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/benchmark/BenchmarkAwareStreamScorer.kt
git commit -m "fix(autoplay): require genuine Atmos passthrough for Atmos tier scoring"
```

If Step 3 required updating any test fixture, include that file in the commit:
```bash
git add app/src/main/java/com/nexio/tv/data/repository/benchmark/BenchmarkAwareStreamScorer.kt \
        app/src/test/java/com/nexio/tv/data/repository/benchmark/BenchmarkAwareScoringHarnessTest.kt
git commit -m "fix(autoplay): require genuine Atmos passthrough for Atmos tier scoring"
```

---

## Task 5: Final verification

- [ ] **Step 1: Run the full unit-test suite**

Run: `./gradlew :app:testDebugUnitTest 2>&1 | tail -10`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Build a debug APK**

Run: `./gradlew :app:assembleDebug 2>&1 | tail -10`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Verify the commit chain**

Run: `git log --oneline origin/main..main`

Expected: 4 new commits, in order:
```
<sha> fix(autoplay): require genuine Atmos passthrough for Atmos tier scoring
<sha> feat(autoplay): emit codec-fallback ladder candidates by release type
<sha> refactor(autoplay): thread releaseType through audio scoring resolution
<sha> test(autoplay): add failing audio fallback ladder test matrix
```

- [ ] **Step 4: Confirm clean working tree**

Run: `git status --short`

Expected: only the unrelated `media`/`tmp/` items present (no staged or modified `app/src/**` paths).

---

## Out of Scope (do not implement here)

- AC3 fallback for TrueHD or DDP tracks on AC3-only receivers.
- New user-facing settings.
- Telemetry on `UNKNOWN` release-type rate.
- Changes to `PREMIUM_AUDIO_TIERS` or synergy-bonus computation. Synergy continues to fire only on resolved premium tiers, which means an Atmos track that resolves to `DDP` on an E-AC3-only receiver does **not** get the UHD+HDR+premium synergy bonus. This is the spec's intent — full equivalence with a real DDP track.
- UI labeling on the collector dashboard (e.g., "DDP via Atmos fallback") — orthogonal to the Android-side scoring fix.
- Tuning the score table values themselves (TRUEHD_ATMOS=16 etc.). The fix changes which tier is *picked*, not the points each tier earns.
