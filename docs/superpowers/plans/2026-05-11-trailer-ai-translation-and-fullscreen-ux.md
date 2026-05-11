# Trailer AI Translation + Fullscreen UX Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add AI auto-translation to the trailer captions pipeline (atomic one-call translation when the user's preferred language differs from the source track), match stream-player subtitle styling on trailers (transparent background + outline), and skip the modern-home hero gradient overlay when the trailer is rendered fullscreen.

**Architecture:** A new public method on `SubtitleTranslationService` translates an entire SRT body in one provider call (no chunking, no per-cue parallelism). `TrailerSubtitleCache.ensure()` invokes it when `selected.translateTo` is set, AI translation is enabled, and the target differs from the source. The existing `applySubtitleStyle(...)` block in `PlayerScreen` is extracted into a shared helper and called from `TrailerPlayer.bindTrailerPlayerView`. The existing `fullscreenTrailerActive` flag in `ModernHomeContent` is propagated to `ModernHeroGradientLayer`, which short-circuits its `drawWithCache` block when the trailer is fullscreen.

**Tech Stack:** Kotlin, kotlinx.coroutines, Hilt, Media3 `SubtitleView` / `CaptionStyleCompat`, existing `SubtitleTranslationService` infrastructure (OpenAI / Anthropic / Gemini / DashScope).

Reference spec: `docs/superpowers/specs/2026-05-11-trailer-ai-translation-and-fullscreen-ux-design.md`.

---

## Existing context the plan relies on

- `SubtitleTranslationSettings` lives at `app/src/main/java/com/nexio/tv/domain/model/GeminiSettings.kt:21`. Fields: `enabled`, `provider`, `apiKey`, `model`, `baseUrl`, `assSsaSystemPromptEnabled`, `subRipSystemPromptEnabled`.
- `SubtitleTranslationService` lives at `app/src/main/java/com/nexio/tv/data/repository/SubtitleTranslationService.kt:146` and is a public `@Singleton` class.
- The private dispatch method `executeRawTranslationRequest(systemPrompt, userPayload, sourceLanguageName, targetLanguageName, settings): String?` already handles the per-provider request construction + retry + response parsing. The new atomic method composes a system prompt and delegates to it.
- `displayLanguage(code)` and `displaySourceLanguage(code?)` are existing helpers on the service that convert language codes to display names.
- `applySubtitleStyle(subtitleView, subtitleStyle: SubtitleStyleSettings, burnInProtection: BurnInProtectionState)` lives at `PlayerScreen.kt:133`. `BurnInProtectionState.DISABLED` is a static instance at `core/player/BurnInProtectionState.kt:9`.
- `SubtitleStyleSettings` defaults `backgroundColor` to `Color.Transparent.toArgb()` (`PlayerSettingsDataStore.kt:94`), so respecting the user's settings naturally yields a transparent caption background.
- `fullscreenTrailerActive` is already computed inside `ModernHomeContent.kt:1028` (`heroTrailerFullscreenMode && heroTrailerInternalPlaying`) and threaded through the file. The `ModernHeroGradientLayer` call at line 1574 currently does NOT receive it.
- `ModernHeroGradientLayer` lives at `ModernHomeHero.kt:187`. It paints three brushes inside `drawWithCache { onDrawBehind { ... } }`.
- `TrailerSubtitlePicker.pickTrailerCaptionTrack` was simplified in commit `6c73f702d`: when no native match exists, it now returns the source track with `translateTo = null`. This plan re-introduces `translateTo` with shifted semantics.
- `TrailerSubtitleCache.ensure(selected)` lives at `app/src/main/java/com/nexio/tv/data/trailer/captions/TrailerSubtitleCache.kt:46`. It already has a `Mutex` and the `cacheFileFor(selected)` helper handles the `translateTo` tlang suffix.

---

## File Structure

**New files:**

| Path | Responsibility | Lines |
|---|---|---|
| `app/src/main/java/com/nexio/tv/ui/components/SubtitleStylePainter.kt` | Shared `applyTrailerSubtitleViewStyle(view, settings, burnInProtection)` helper. Body is the same code currently inside `PlayerScreen.applySubtitleStyle`. | ~60 |
| `app/src/test/java/com/nexio/tv/data/repository/SubtitleTranslationServiceAtomicTest.kt` | Unit tests for `translateSrtAtomically` — validates cue count, rejects malformed responses, requires `enabled = true` and non-blank apiKey. | ~120 |
| `app/src/test/java/com/nexio/tv/data/trailer/captions/TrailerSubtitleCacheTranslationTest.kt` | Robolectric tests covering native-match / AI-disabled / AI-enabled-success / AI-enabled-failure paths. | ~140 |
| `app/src/test/java/com/nexio/tv/data/trailer/TrailerSubtitlePickerTranslationTest.kt` | Picker emits `translateTo = preferredLang` when no native match. | ~50 |

**Modified files:**

| Path | Change |
|---|---|
| `app/src/main/java/com/nexio/tv/data/repository/SubtitleTranslationService.kt` | Add public `translateSrtAtomically(...)`. |
| `app/src/main/java/com/nexio/tv/data/trailer/TrailerSubtitlePicker.kt` | Restore `translateTo = normalized` in the no-native-match branch. |
| `app/src/main/java/com/nexio/tv/data/trailer/captions/TrailerSubtitleCache.kt` | Inject `SubtitleTranslationService` + `PlayerSettingsDataStore`. Extend `ensure()` with the post-parse translation branch. |
| `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerScreen.kt` | Refactor `applySubtitleStyle` to delegate to the shared painter. |
| `app/src/main/java/com/nexio/tv/ui/components/TrailerPlayer.kt` | Call the shared painter from `bindTrailerPlayerView` using user's `SubtitleStyleSettings` + `BurnInProtectionState.DISABLED`. |
| `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeHero.kt` | `ModernHeroGradientLayer` gains a `fullscreenTrailerActive: Boolean` parameter and skips drawing when `true`. |
| `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeContent.kt` | Pass `fullscreenTrailerActive` to `ModernHeroGradientLayer` at the call site (~line 1574). |

---

## Scope Check

Two subsystems (AI translation, fullscreen UX) inside one spec. They share the trailer-captions context. The UX section is small (3 tasks); the AI translation section is the bulk (5 tasks). Total fits one plan.

---

## Task ordering rationale

A → B → C → D → E. Each phase is independently shippable: A1 alone restores the picker contract for any future translation work; B1–B3 land the AI pipeline; C1–C2 ship the styling fix; D1–D2 ship the gradient fix; E is the on-device verification. Tasks A1 and C1 are touched by multiple phases — they're ordered first within their phases to set up the surface area.

---

## Task A1: Restore `translateTo` in the no-native picker branch

The picker once again signals "this is a source-language track; translate to `<preferredLang>` if downstream can." The translateTo field is consumed by `TrailerSubtitleCache` (Task B3) to drive AI translation.

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/trailer/TrailerSubtitlePicker.kt`
- Create: `app/src/test/java/com/nexio/tv/data/trailer/TrailerSubtitlePickerTranslationTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/nexio/tv/data/trailer/TrailerSubtitlePickerTranslationTest.kt`:

```kotlin
package com.nexio.tv.data.trailer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrailerSubtitlePickerTranslationTest {

    private val englishAsr = YouTubeCaptionTrack(
        baseUrl = "https://www.youtube.com/api/timedtext?v=abc&lang=en&caps=asr",
        languageCode = "en",
        languageName = "English (auto-generated)",
        kind = "asr",
        isTranslatable = true
    )

    private val englishManual = YouTubeCaptionTrack(
        baseUrl = "https://www.youtube.com/api/timedtext?v=abc&lang=en",
        languageCode = "en",
        languageName = "English",
        kind = null,
        isTranslatable = true
    )

    private val dutchManual = YouTubeCaptionTrack(
        baseUrl = "https://www.youtube.com/api/timedtext?v=abc&lang=nl",
        languageCode = "nl",
        languageName = "Dutch",
        kind = null,
        isTranslatable = true
    )

    @Test
    fun `native match returns source language without translateTo`() {
        val selected = pickTrailerCaptionTrack(
            tracks = listOf(englishManual, dutchManual),
            preferredLanguage = "nl"
        )
        assertEquals("nl", selected?.languageCode)
        assertNull(selected?.translateTo)
    }

    @Test
    fun `no native match returns english source with translateTo set to preferred lang`() {
        val selected = pickTrailerCaptionTrack(
            tracks = listOf(englishManual),
            preferredLanguage = "nl"
        )
        // Source track unchanged
        assertEquals("en", selected?.languageCode)
        assertEquals(englishManual.baseUrl, selected?.baseUrl)
        // Target language requested for AI translation
        assertEquals("nl", selected?.translateTo)
    }

    @Test
    fun `no native match prefers manual english over ASR english as source`() {
        val selected = pickTrailerCaptionTrack(
            tracks = listOf(englishAsr, englishManual),
            preferredLanguage = "nl"
        )
        assertEquals(englishManual.baseUrl, selected?.baseUrl)
        assertEquals("nl", selected?.translateTo)
    }

    @Test
    fun `preferredLanguage off disables track selection regardless of translation`() {
        val selected = pickTrailerCaptionTrack(
            tracks = listOf(englishManual),
            preferredLanguage = "off"
        )
        assertNull(selected)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.trailer.TrailerSubtitlePickerTranslationTest" --console=plain 2>&1 | tail -10`

Expected: `no native match returns english source with translateTo set to preferred lang` FAILS — `translateTo` is currently `null`.

- [ ] **Step 3: Restore `translateTo` in the no-native branch**

Edit `app/src/main/java/com/nexio/tv/data/trailer/TrailerSubtitlePicker.kt`. Locate the no-native-match block (introduced in `6c73f702d`):

```kotlin
    val sourceTrack = ordered.firstOrNull {
        it.languageCode.lowercase().startsWith("en") && (it.kind ?: "").lowercase() != "asr"
    }
        ?: ordered.firstOrNull { it.languageCode.lowercase().startsWith("en") }
        ?: ordered.firstOrNull { (it.kind ?: "").lowercase() != "asr" }
        ?: ordered.firstOrNull()
        ?: return null

    return SelectedTrailerCaptionTrack(
        baseUrl = sourceTrack.baseUrl,
        languageCode = sourceTrack.languageCode,
        translateTo = null
    )
}
```

Replace with:

```kotlin
    // No native match. Pick a source-language track (English preferred) and
    // request AI translation downstream via translateTo. The cache layer
    // decides whether to actually run translation: if AI translation isn't
    // configured, translateTo is ignored and the source-language SRT is
    // served as-is. This is the contract change in
    // docs/superpowers/specs/2026-05-11-trailer-ai-translation-and-fullscreen-ux-design.md
    // (Task A1) — translateTo no longer drives YouTube's `&tlang=` (banned
    // since 6c73f702d due to WAF rate limits); it drives in-app translation.
    val sourceTrack = ordered.firstOrNull {
        it.languageCode.lowercase().startsWith("en") && (it.kind ?: "").lowercase() != "asr"
    }
        ?: ordered.firstOrNull { it.languageCode.lowercase().startsWith("en") }
        ?: ordered.firstOrNull { (it.kind ?: "").lowercase() != "asr" }
        ?: ordered.firstOrNull()
        ?: return null

    return SelectedTrailerCaptionTrack(
        baseUrl = sourceTrack.baseUrl,
        languageCode = sourceTrack.languageCode,
        translateTo = normalized
    )
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.trailer.TrailerSubtitlePickerTranslationTest" --console=plain 2>&1 | tail -10`

Expected: `BUILD SUCCESSFUL`, all 4 tests passing.

- [ ] **Step 5: Run the existing picker tests to verify no regression**

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.trailer.TrailerSubtitlePickerTest" --tests "com.nexio.tv.data.trailer.TrailerSubtitlePickerTranslationTest" --console=plain 2>&1 | tail -10`

Expected: `BUILD SUCCESSFUL`. If any test fails because its assertions don't expect `translateTo`, update those tests' assertions — they were written under the previous contract.

- [ ] **Step 6: Commit (explicit-path staging only — CLAUDE.md rule #7)**

```bash
git add app/src/main/java/com/nexio/tv/data/trailer/TrailerSubtitlePicker.kt \
        app/src/test/java/com/nexio/tv/data/trailer/TrailerSubtitlePickerTranslationTest.kt
# Plus any TrailerSubtitlePickerTest edits if assertions had to be updated
git commit -m "$(cat <<'EOF'
feat(trailer/captions): restore translateTo on no-native picker branch

When the trailer has no caption track matching the user's preferred
subtitle language, pickTrailerCaptionTrack now sets translateTo to the
preferred language code (as a request to downstream — TrailerSubtitleCache
decides whether to actually translate based on AI translation settings).

The translateTo field's contract has shifted: it previously drove
YouTube's `&tlang=` server-side translation (banned in 6c73f702d after
the WAF 429-rate-limited every request). It now drives in-app AI
translation if configured; otherwise the cache ignores it and serves
source-language captions as today.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task B1: Add `translateSrtAtomically` to `SubtitleTranslationService`

A new public suspend method that translates a complete SRT body in one provider call. No chunking. Validates the response is a parseable SRT with the same cue count.

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/SubtitleTranslationService.kt`
- Create: `app/src/test/java/com/nexio/tv/data/repository/SubtitleTranslationServiceAtomicTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/nexio/tv/data/repository/SubtitleTranslationServiceAtomicTest.kt`:

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.SubtitleTranslationProvider
import com.nexio.tv.domain.model.SubtitleTranslationSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitleTranslationServiceAtomicTest {

    private val sampleSourceSrt = """
        1
        00:00:01,000 --> 00:00:03,000
        Hello world

        2
        00:00:05,000 --> 00:00:07,500
        Second line

    """.trimIndent() + "\n"

    private val sampleTranslatedSrt = """
        1
        00:00:01,000 --> 00:00:03,000
        Hallo wereld

        2
        00:00:05,000 --> 00:00:07,500
        Tweede regel

    """.trimIndent() + "\n"

    @Test
    fun `returns null when settings enabled is false`() = runBlocking {
        val service = newServiceUnderTest(rawResponse = sampleTranslatedSrt)
        val settings = baseSettings.copy(enabled = false)

        val result = service.translateSrtAtomically(
            srt = sampleSourceSrt,
            sourceLanguageCode = "en",
            targetLanguageCode = "nl",
            settings = settings
        )

        assertNull(result)
    }

    @Test
    fun `returns null when apiKey is blank`() = runBlocking {
        val service = newServiceUnderTest(rawResponse = sampleTranslatedSrt)
        val settings = baseSettings.copy(apiKey = "  ")

        val result = service.translateSrtAtomically(
            srt = sampleSourceSrt,
            sourceLanguageCode = "en",
            targetLanguageCode = "nl",
            settings = settings
        )

        assertNull(result)
    }

    @Test
    fun `returns translated SRT when provider response validates`() = runBlocking {
        val service = newServiceUnderTest(rawResponse = sampleTranslatedSrt)

        val result = service.translateSrtAtomically(
            srt = sampleSourceSrt,
            sourceLanguageCode = "en",
            targetLanguageCode = "nl",
            settings = baseSettings
        )

        assertNotNull(result)
        assertEquals(sampleTranslatedSrt.trim(), result!!.trim())
    }

    @Test
    fun `returns null when provider response has fewer cues than source`() = runBlocking {
        // Source has 2 cues; response has 1.
        val truncated = """
            1
            00:00:01,000 --> 00:00:03,000
            Hallo wereld

        """.trimIndent() + "\n"
        val service = newServiceUnderTest(rawResponse = truncated)

        val result = service.translateSrtAtomically(
            srt = sampleSourceSrt,
            sourceLanguageCode = "en",
            targetLanguageCode = "nl",
            settings = baseSettings
        )

        assertNull(result)
    }

    @Test
    fun `returns null when provider response has no timestamp lines`() = runBlocking {
        val garbage = "I'm sorry, I cannot translate that.\n"
        val service = newServiceUnderTest(rawResponse = garbage)

        val result = service.translateSrtAtomically(
            srt = sampleSourceSrt,
            sourceLanguageCode = "en",
            targetLanguageCode = "nl",
            settings = baseSettings
        )

        assertNull(result)
    }

    @Test
    fun `strips conversational preamble before the first cue index`() = runBlocking {
        val withPreamble = "Sure! Here's the translation:\n\n" + sampleTranslatedSrt
        val service = newServiceUnderTest(rawResponse = withPreamble)

        val result = service.translateSrtAtomically(
            srt = sampleSourceSrt,
            sourceLanguageCode = "en",
            targetLanguageCode = "nl",
            settings = baseSettings
        )

        assertNotNull(result)
        assertEquals(sampleTranslatedSrt.trim(), result!!.trim())
    }

    private val baseSettings = SubtitleTranslationSettings(
        enabled = true,
        provider = SubtitleTranslationProvider.OPENAI,
        apiKey = "test-key",
        model = "gpt-4o-mini",
        baseUrl = "https://example.test/v1"
    )

    /**
     * Construct a SubtitleTranslationService whose private
     * executeRawTranslationRequest returns `rawResponse` for any input.
     * Implementation detail of Task B1 — the production class exposes
     * an injectable seam (`@VisibleForTesting var rawResponderForTest`)
     * that the test sets. See production Step 4 below.
     */
    private fun newServiceUnderTest(rawResponse: String?): SubtitleTranslationService {
        return SubtitleTranslationService.forAtomicTranslationTest(
            rawResponder = { _, _, _, _, _ -> rawResponse }
        )
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.repository.SubtitleTranslationServiceAtomicTest" --console=plain 2>&1 | tail -10`

Expected: `Unresolved reference: translateSrtAtomically` (or `Unresolved reference: forAtomicTranslationTest`).

- [ ] **Step 3: Implement `translateSrtAtomically`**

Open `app/src/main/java/com/nexio/tv/data/repository/SubtitleTranslationService.kt`. Inside the class (any location works; pick somewhere near `translateRawSubRipText` for cohesion):

```kotlin
    /**
     * Translate a complete SRT body in a single provider call. Trailer
     * captions are short (~10–50 cues, 1–3 KB) so chunking adds latency
     * without benefit. Validates the response parses as SRT with the same
     * cue count as the input; returns null on any failure so the caller
     * can fall back to source-language captions.
     *
     * Designed for TrailerSubtitleCache; the stream subtitle pipeline
     * continues to use the chunked translateRawSubRipText path.
     */
    suspend fun translateSrtAtomically(
        srt: String,
        sourceLanguageCode: String,
        targetLanguageCode: String,
        settings: SubtitleTranslationSettings
    ): String? = withContext(Dispatchers.IO) {
        val normalizedSettings = settings.copy(apiKey = settings.apiKey.trim())
        if (!normalizedSettings.enabled) return@withContext null
        if (normalizedSettings.apiKey.isBlank()) return@withContext null
        if (srt.isBlank()) return@withContext null

        val sourceCueCount = countSrtCues(srt)
        if (sourceCueCount == 0) return@withContext null

        val targetLanguageName = displayLanguage(targetLanguageCode)
        val sourceLanguageName = displaySourceLanguage(sourceLanguageCode)
        val systemPrompt = buildAtomicSrtSystemPrompt(
            sourceLanguageName = sourceLanguageName,
            targetLanguageName = targetLanguageName
        )

        val raw = runCatching {
            atomicRawResponder.invoke(
                systemPrompt,
                srt,
                sourceLanguageName,
                targetLanguageName,
                normalizedSettings
            )
        }.getOrNull() ?: return@withContext null

        val cleaned = stripConversationalPreamble(raw)
        val translatedCueCount = countSrtCues(cleaned)
        if (translatedCueCount != sourceCueCount) {
            return@withContext null
        }
        cleaned
    }

    /**
     * Pluggable seam for tests. In production this delegates to
     * executeRawTranslationRequest. Tests inject a fake responder.
     */
    @VisibleForTesting
    internal var atomicRawResponder: (
        systemPrompt: String,
        userPayload: String,
        sourceLanguageName: String,
        targetLanguageName: String,
        settings: SubtitleTranslationSettings
    ) -> String? = { systemPrompt, userPayload, sourceLanguageName, targetLanguageName, settings ->
        executeRawTranslationRequest(
            systemPrompt = systemPrompt,
            userPayload = userPayload,
            sourceLanguageName = sourceLanguageName,
            targetLanguageName = targetLanguageName,
            settings = settings
        )
    }

    private fun buildAtomicSrtSystemPrompt(
        sourceLanguageName: String,
        targetLanguageName: String
    ): String {
        val sourceClause = if (sourceLanguageName.equals("auto", ignoreCase = true)) {
            "the source language"
        } else {
            sourceLanguageName
        }
        return """
            You are a subtitle translator. Translate the SRT content below
            from $sourceClause into $targetLanguageName.

            Rules:
            1. Preserve every cue number line and every timestamp line
               (HH:MM:SS,mmm --> HH:MM:SS,mmm) exactly as written.
            2. Translate only the text-content lines between the timestamp
               and the blank-line separator. Replace each source-language
               text with its $targetLanguageName translation.
            3. Keep the same total number of cues. Do not merge, drop, or
               add cues.
            4. Output the full translated SRT and nothing else — no
               commentary, no markdown fences, no preamble.
        """.trimIndent()
    }

    private fun countSrtCues(srt: String): Int {
        return SRT_TIMESTAMP_REGEX.findAll(srt).count()
    }

    private fun stripConversationalPreamble(raw: String): String {
        val firstCueIndex = SRT_CUE_HEADER_REGEX.find(raw)?.range?.first ?: return raw
        return if (firstCueIndex == 0) raw else raw.substring(firstCueIndex)
    }

    companion object {
        private val SRT_TIMESTAMP_REGEX =
            Regex("""\d{2}:\d{2}:\d{2}[,.]\d{3}\s*-->\s*\d{2}:\d{2}:\d{2}[,.]\d{3}""")
        private val SRT_CUE_HEADER_REGEX = Regex("""(^|\n)\s*1\s*\r?\n""")

        /**
         * Test-only factory that produces a SubtitleTranslationService with
         * its atomic responder seam pre-populated. Production callers go
         * through Hilt; tests use this to avoid wiring the full @Inject
         * graph.
         */
        @VisibleForTesting
        internal fun forAtomicTranslationTest(
            rawResponder: (
                systemPrompt: String,
                userPayload: String,
                sourceLanguageName: String,
                targetLanguageName: String,
                settings: SubtitleTranslationSettings
            ) -> String?
        ): SubtitleTranslationService {
            // The other constructor params are not exercised by the atomic
            // path. We pass nulls / no-op stubs since the seam short-
            // circuits the rawResponder before any other field is read.
            val instance = SubtitleTranslationService(
                context = NoopContextForAtomicTest,
                subtitleTranslationIntegrationProvider = NoopSubtitleTranslationIntegrationProvider,
                subtitleSourceDownloadIntegrationProvider = NoopSubtitleSourceDownloadIntegrationProvider,
                diagnosticsLogger = NoopDiagnosticsLogger,
                reasoningModels = NoopReasoningModels
            )
            instance.atomicRawResponder = rawResponder
            return instance
        }
    }
```

Add imports at the top of the file if not present:

```kotlin
import androidx.annotation.VisibleForTesting
```

> **Note on test seam vs full Hilt construction:**
> The `forAtomicTranslationTest` factory bypasses Hilt by supplying no-op stubs for the four collaborator types (`SubtitleTranslationIntegrationProvider`, `SubtitleSourceDownloadIntegrationProvider`, `AutoTranslateDiagnosticsLogger`, `ReasoningModelsRepository`). If these types are interfaces with parameter-less defaults, declare four `private object`s in the file (e.g. `private object NoopSubtitleTranslationIntegrationProvider : SubtitleTranslationIntegrationProvider { ... }`). If they are concrete classes without trivial constructors, this seam approach won't compile cleanly — fall back to constructing the service through Hilt's testing artifact (`@HiltAndroidTest` + `@AndroidEntryPoint`), which is heavier but reliable. **Inspect the four types in `SubtitleTranslationService.kt` constructor before implementing this step** and pick whichever approach compiles. The test code in Step 1 only depends on the factory existing and returning a service with `atomicRawResponder` populated — the internal mechanism is at the implementer's discretion.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.repository.SubtitleTranslationServiceAtomicTest" --console=plain 2>&1 | tail -15`

Expected: `BUILD SUCCESSFUL`, all 6 tests passing.

- [ ] **Step 5: Run all SubtitleTranslationService-related tests for regression**

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.repository.SubtitleTranslation*" --console=plain 2>&1 | tail -15`

Expected: `BUILD SUCCESSFUL`. Existing stream-translation tests must remain green.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/SubtitleTranslationService.kt \
        app/src/test/java/com/nexio/tv/data/repository/SubtitleTranslationServiceAtomicTest.kt
git commit -m "$(cat <<'EOF'
feat(translation): translateSrtAtomically for trailer captions

Trailer SRTs are short (typically 10–50 cues, 1–3 KB). The existing
chunked path (translateRawSubRipText with batched cues) adds latency
without benefit at this size. Introduce a parallel public method that
sends one provider request with the entire SRT body in the prompt,
preserves cue numbers and timestamps via the system instruction, and
validates the response parses as SRT with the same cue count.

Returns null on every failure mode (disabled, missing API key,
provider error, malformed response, conversational preamble that
breaks parse). Caller (TrailerSubtitleCache, wired in Task B3) falls
back to source-language captions in all those cases.

The pluggable atomicRawResponder seam lets unit tests inject a fake
provider response without spinning up the full Hilt graph. Production
behavior is unchanged for stream subtitle translation; the new entry
point is additive.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task B2: Inject `SubtitleTranslationService` + settings into `TrailerSubtitleCache`

Plumbing only — no behavior change yet. Splits the structural risk away from the logic change in Task B3.

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/trailer/captions/TrailerSubtitleCache.kt`

- [ ] **Step 1: Inspect current constructor**

Read `app/src/main/java/com/nexio/tv/data/trailer/captions/TrailerSubtitleCache.kt:33`. Today's constructor takes only `@ApplicationContext context`.

- [ ] **Step 2: Find the user-settings entry-point that the cache should read**

Run: `grep -nE "data class PlayerSettings\b|val subtitleStyle: SubtitleStyleSettings|class PlayerSettingsDataStore|subtitleTranslationSettings" /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt | head -10`

If `PlayerSettingsDataStore` exposes a `subtitleTranslationSettings: Flow<SubtitleTranslationSettings>` (or equivalent), the cache reads from there. If translation settings live in a separate `SubtitleTranslationSettingsDataStore`, inject that instead.

Confirm the exact type and accessor name. Use it verbatim in the next step.

- [ ] **Step 3: Add the two injected dependencies**

Edit `app/src/main/java/com/nexio/tv/data/trailer/captions/TrailerSubtitleCache.kt`. Change the class declaration from:

```kotlin
@Singleton
class TrailerSubtitleCache @Inject constructor(
    @ApplicationContext private val applicationContext: Context
) {
```

to:

```kotlin
@Singleton
class TrailerSubtitleCache @Inject constructor(
    @ApplicationContext private val applicationContext: Context,
    private val subtitleTranslationService: com.nexio.tv.data.repository.SubtitleTranslationService,
    private val playerSettingsDataStore: com.nexio.tv.data.local.PlayerSettingsDataStore
) {
```

(If the discovery in Step 2 found a different datastore name for translation settings, use that fully qualified name instead of `PlayerSettingsDataStore`.)

- [ ] **Step 4: Verify compilation**

Run: `./gradlew :app:compileUniversalDebugKotlin --console=plain 2>&1 | grep -E "^e: |^error:|BUILD FAIL|BUILD SUCCESSFUL" | head -10`

Expected: `BUILD SUCCESSFUL`. Hilt resolves both new dependencies (both are `@Singleton` with `@Inject` constructors).

- [ ] **Step 5: Run the cache tests for regression**

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.trailer.captions.*" --console=plain 2>&1 | tail -10`

Expected: `BUILD SUCCESSFUL`. The existing cipher / parser / SRT tests don't construct `TrailerSubtitleCache` so they're unaffected; if any future test constructed it directly, update its call site.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/trailer/captions/TrailerSubtitleCache.kt
git commit -m "$(cat <<'EOF'
chore(trailer/captions): inject SubtitleTranslationService + settings

Plumbing for the AI translation branch landing in Task B3. Adds two
@Inject-resolved fields to TrailerSubtitleCache:
- SubtitleTranslationService: the production translator
- PlayerSettingsDataStore: source of SubtitleTranslationSettings

No behavior change yet — fields are unused until Task B3 reads them.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task B3: Wire AI translation into `TrailerSubtitleCache.ensure`

The substantive logic change. After the source SRT is written, optionally translate to `selected.translateTo` and return the translated URI; on any failure path, return the source URI.

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/trailer/captions/TrailerSubtitleCache.kt`
- Create: `app/src/test/java/com/nexio/tv/data/trailer/captions/TrailerSubtitleCacheTranslationTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/nexio/tv/data/trailer/captions/TrailerSubtitleCacheTranslationTest.kt`:

```kotlin
package com.nexio.tv.data.trailer.captions

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nexio.tv.data.local.PlayerSettings
import com.nexio.tv.data.local.PlayerSettingsDataStore
import com.nexio.tv.data.local.SubtitleStyleSettings
import com.nexio.tv.data.repository.SubtitleTranslationService
import com.nexio.tv.data.trailer.SelectedTrailerCaptionTrack
import com.nexio.tv.domain.model.SubtitleTranslationProvider
import com.nexio.tv.domain.model.SubtitleTranslationSettings
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TrailerSubtitleCacheTranslationTest {

    private lateinit var server: MockWebServer
    private lateinit var context: Context
    private lateinit var cacheDir: File

    // SRV3 fixture that parses to two cues.
    private val srv3Xml = """
        <?xml version="1.0" encoding="utf-8" ?>
        <timedtext format="3">
          <body>
            <p t="1000" d="2000">Hello world</p>
            <p t="5000" d="2500">Second line</p>
          </body>
        </timedtext>
    """.trimIndent()

    private val expectedSourceSrt = "1\n00:00:01,000 --> 00:00:03,000\nHello world\n\n" +
        "2\n00:00:05,000 --> 00:00:07,500\nSecond line\n\n"

    private val expectedTranslatedSrt = "1\n00:00:01,000 --> 00:00:03,000\nHallo wereld\n\n" +
        "2\n00:00:05,000 --> 00:00:07,500\nTweede regel\n\n"

    private val translationSettingsFlow = MutableStateFlow(
        PlayerSettings() // default: subtitle translation disabled
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        // SRV3 fetch returns our fixture for every request.
        server.enqueue(MockResponse().setResponseCode(200).setBody(srv3Xml))
        context = ApplicationProvider.getApplicationContext()
        cacheDir = File(context.cacheDir, "trailer-subtitles")
        cacheDir.deleteRecursively()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `translateTo null returns source SRT URI and writes only source file`() = runBlocking {
        val service = mockk<SubtitleTranslationService>(relaxed = true)
        val cache = newCache(service, settings = SubtitleTranslationSettings(enabled = false))
        val selected = SelectedTrailerCaptionTrack(
            baseUrl = server.url("/api/timedtext?v=abc").toString(),
            languageCode = "en",
            translateTo = null
        )

        val uri = cache.ensure(selected)

        assertNotNull(uri)
        assertTrue(uri!!.endsWith("-en.srt"))
        assertTrue(cacheDir.listFiles()!!.none { it.name.contains("-en-") })
    }

    @Test
    fun `AI disabled returns source SRT even when translateTo set`() = runBlocking {
        val service = mockk<SubtitleTranslationService>(relaxed = true)
        val cache = newCache(service, settings = SubtitleTranslationSettings(enabled = false))
        val selected = SelectedTrailerCaptionTrack(
            baseUrl = server.url("/api/timedtext?v=abc").toString(),
            languageCode = "en",
            translateTo = "nl"
        )

        val uri = cache.ensure(selected)

        assertNotNull(uri)
        assertTrue(uri!!.endsWith("-en.srt"))
        // No translation attempt
        io.mockk.coVerify(exactly = 0) {
            service.translateSrtAtomically(any(), any(), any(), any())
        }
    }

    @Test
    fun `AI enabled and translation succeeds returns translated SRT URI`() = runBlocking {
        val service = mockk<SubtitleTranslationService>()
        coEvery {
            service.translateSrtAtomically(any(), eq("en"), eq("nl"), any())
        } returns expectedTranslatedSrt
        val cache = newCache(
            service,
            settings = SubtitleTranslationSettings(
                enabled = true,
                provider = SubtitleTranslationProvider.OPENAI,
                apiKey = "test-key"
            )
        )
        val selected = SelectedTrailerCaptionTrack(
            baseUrl = server.url("/api/timedtext?v=abc").toString(),
            languageCode = "en",
            translateTo = "nl"
        )

        val uri = cache.ensure(selected)

        assertNotNull(uri)
        assertTrue(uri!!.endsWith("-en-nl.srt"))
        // Both files written
        val files = cacheDir.listFiles()!!.map { it.name }.toSet()
        assertTrue(files.any { it.endsWith("-en.srt") && !it.contains("-en-") })
        assertTrue(files.any { it.endsWith("-en-nl.srt") })
        // Translated file content matches what the service returned
        val translatedFile = cacheDir.listFiles()!!.first { it.name.endsWith("-en-nl.srt") }
        assertEquals(expectedTranslatedSrt, translatedFile.readText())
    }

    @Test
    fun `translation failure falls back to source SRT URI`() = runBlocking {
        val service = mockk<SubtitleTranslationService>()
        coEvery {
            service.translateSrtAtomically(any(), any(), any(), any())
        } returns null
        val cache = newCache(
            service,
            settings = SubtitleTranslationSettings(enabled = true, apiKey = "test-key")
        )
        val selected = SelectedTrailerCaptionTrack(
            baseUrl = server.url("/api/timedtext?v=abc").toString(),
            languageCode = "en",
            translateTo = "nl"
        )

        val uri = cache.ensure(selected)

        assertNotNull(uri)
        assertTrue(uri!!.endsWith("-en.srt"))
        // No translated file
        assertTrue(cacheDir.listFiles()!!.none { it.name.contains("-en-nl") })
    }

    @Test
    fun `cache hit on translated file skips network and translation`() = runBlocking {
        // Pre-populate the translated file on disk.
        cacheDir.mkdirs()
        val sourceFile = File(cacheDir, "deadbeefcafef00d-en.srt")
        sourceFile.writeText(expectedSourceSrt)
        val translatedFile = File(cacheDir, "deadbeefcafef00d-en-nl.srt")
        translatedFile.writeText(expectedTranslatedSrt)

        val service = mockk<SubtitleTranslationService>(relaxed = true)
        val cache = newCache(
            service,
            settings = SubtitleTranslationSettings(enabled = true, apiKey = "test-key")
        )
        // Construct a SelectedTrailerCaptionTrack whose cache key produces
        // the same hash. The cacheFileFor uses sha1 of baseUrl; we don't
        // know it ahead of time, so this test instead relies on probing
        // the cache after the first call to assert it didn't re-fetch.

        // First call populates whatever the actual hash is — wipe state.
        cacheDir.deleteRecursively()
        cacheDir.mkdirs()

        val selected = SelectedTrailerCaptionTrack(
            baseUrl = server.url("/api/timedtext?v=abc").toString(),
            languageCode = "en",
            translateTo = "nl"
        )
        coEvery {
            service.translateSrtAtomically(any(), any(), any(), any())
        } returns expectedTranslatedSrt

        cache.ensure(selected)
        // Second call must NOT translate again (cache hit)
        cache.ensure(selected)

        io.mockk.coVerify(exactly = 1) {
            service.translateSrtAtomically(any(), any(), any(), any())
        }
    }

    private fun newCache(
        service: SubtitleTranslationService,
        settings: SubtitleTranslationSettings
    ): TrailerSubtitleCache {
        translationSettingsFlow.value = PlayerSettings(
            subtitleTranslationSettings = settings,
            subtitleStyle = SubtitleStyleSettings(preferredLanguage = settings.targetLanguageOrEmpty())
        )
        val dataStore = mockk<PlayerSettingsDataStore>(relaxed = true)
        io.mockk.every { dataStore.playerSettings } returns translationSettingsFlow as StateFlow<PlayerSettings?>
        return TrailerSubtitleCache(
            applicationContext = context,
            subtitleTranslationService = service,
            playerSettingsDataStore = dataStore
        )
    }

    private fun SubtitleTranslationSettings.targetLanguageOrEmpty(): String = "nl"
}
```

> **Note on `PlayerSettings` shape:**
> The test assumes `PlayerSettings` has a `subtitleTranslationSettings: SubtitleTranslationSettings` field. If the actual field name is different (e.g. `aiTranslation: SubtitleTranslationSettings`), adjust the test fixture and the production code in Step 3 to match. Run `grep -n "subtitleTranslationSettings\|aiTranslation" app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt | head -5` and use the actual accessor.
>
> **Note on `mockk`:** if `io.mockk:mockk-android` is not already a `testImplementation`, add it with the version that matches existing test deps. Run `grep "mockk" app/build.gradle.kts` to confirm.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.trailer.captions.TrailerSubtitleCacheTranslationTest" --console=plain 2>&1 | tail -15`

Expected: tests fail because `ensure()` doesn't call the translator at all.

- [ ] **Step 3: Wire the translation branch**

Edit `app/src/main/java/com/nexio/tv/data/trailer/captions/TrailerSubtitleCache.kt`. Replace the `ensure(...)` method body:

```kotlin
    suspend fun ensure(selected: SelectedTrailerCaptionTrack): String? =
        mutex.withLock {
            val sourceTarget = sourceCacheFileFor(selected)
            val translatedTarget = translatedCacheFileFor(selected)

            // 1. Translated cache hit?
            if (selected.translateTo != null && translatedTarget != null &&
                translatedTarget.exists() && translatedTarget.length() > 0
            ) {
                return@withLock translatedTarget.toURI().toString()
            }

            // 2. Source cache hit OR fetch + parse + write source.
            val sourceUri = if (sourceTarget.exists() && sourceTarget.length() > 0) {
                sourceTarget.toURI().toString()
            } else {
                val srv3Url = buildSrv3Url(selected)
                Log.d(TAG, "fetching srv3 url=$srv3Url")
                val xml = fetchSrv3(srv3Url)
                if (xml == null) {
                    Log.d(TAG, "fetch returned null (HTTP failure)")
                    return@withLock null
                }
                Log.d(TAG, "fetch ok bytes=${xml.length} preview=${xml.take(200).replace('\n', ' ')}")
                val lines = SrvCaptionParser.parse(xml)
                if (lines.isEmpty()) {
                    Log.d(TAG, "parse produced zero caption lines")
                    return@withLock null
                }
                Log.d(TAG, "parsed lines=${lines.size}")
                val srt = SrtSerializer.serialize(lines)
                try {
                    sourceTarget.writeText(srt, Charsets.UTF_8)
                } catch (e: IOException) {
                    Log.d(TAG, "source SRT write failed ${e.message}")
                    return@withLock null
                }
                sourceTarget.toURI().toString()
            }

            // 3. No translation requested → return source URI.
            if (selected.translateTo == null || translatedTarget == null) {
                return@withLock sourceUri
            }
            if (selected.translateTo.equals(selected.languageCode, ignoreCase = true)) {
                return@withLock sourceUri
            }

            // 4. AI translation gate.
            val settings = currentTranslationSettings()
            if (settings == null || !settings.enabled || settings.apiKey.isBlank()) {
                Log.d(TAG, "AI translation disabled — serving source SRT")
                return@withLock sourceUri
            }

            // 5. Translate.
            val sourceSrt = try {
                sourceTarget.readText(Charsets.UTF_8)
            } catch (e: IOException) {
                Log.d(TAG, "source SRT read failed ${e.message}")
                return@withLock sourceUri
            }
            val translatedSrt = try {
                subtitleTranslationService.translateSrtAtomically(
                    srt = sourceSrt,
                    sourceLanguageCode = selected.languageCode,
                    targetLanguageCode = selected.translateTo,
                    settings = settings
                )
            } catch (e: Throwable) {
                Log.d(TAG, "translateSrtAtomically threw ${e.javaClass.simpleName}: ${e.message}")
                null
            }
            if (translatedSrt.isNullOrBlank()) {
                Log.d(TAG, "translation returned null/blank — serving source SRT")
                return@withLock sourceUri
            }
            try {
                translatedTarget.writeText(translatedSrt, Charsets.UTF_8)
            } catch (e: IOException) {
                Log.d(TAG, "translated SRT write failed ${e.message}")
                return@withLock sourceUri
            }
            Log.d(TAG, "translated SRT cached at ${translatedTarget.absolutePath}")
            translatedTarget.toURI().toString()
        }

    /**
     * Cache file path for the source SRT (no translation suffix). Pure
     * function of baseUrl + sourceLang.
     */
    private fun sourceCacheFileFor(selected: SelectedTrailerCaptionTrack): File {
        val sourceOnly = selected.copy(translateTo = null)
        return cacheFileFor(sourceOnly)
    }

    /**
     * Cache file path for the translated SRT, or null if no translation is
     * requested (translateTo unset). Pure function of baseUrl + sourceLang
     * + targetLang.
     */
    private fun translatedCacheFileFor(selected: SelectedTrailerCaptionTrack): File? {
        if (selected.translateTo.isNullOrBlank()) return null
        return cacheFileFor(selected)
    }

    /**
     * Snapshot the current AI translation settings. Returns null when the
     * datastore hasn't emitted yet.
     */
    private suspend fun currentTranslationSettings(): SubtitleTranslationSettings? {
        return playerSettingsDataStore.playerSettings.firstOrNull()
            ?.subtitleTranslationSettings
    }
```

Add imports at the top of `TrailerSubtitleCache.kt`:

```kotlin
import com.nexio.tv.domain.model.SubtitleTranslationSettings
import kotlinx.coroutines.flow.firstOrNull
```

If `currentTranslationSettings()` needs a different accessor (per the discovery in B2 Step 2), adjust the field name in this step accordingly.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.trailer.captions.TrailerSubtitleCacheTranslationTest" --console=plain 2>&1 | tail -15`

Expected: `BUILD SUCCESSFUL`, all 5 tests passing.

- [ ] **Step 5: Run all trailer + captions tests for regression**

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.trailer.*" --tests "com.nexio.tv.data.trailer.captions.*" --tests "com.nexio.tv.data.trailer.cipher.*" --console=plain 2>&1 | tail -15`

Expected: `BUILD SUCCESSFUL`. None of the pre-existing cipher / parser / serializer / picker / verifier tests should regress.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/trailer/captions/TrailerSubtitleCache.kt \
        app/src/test/java/com/nexio/tv/data/trailer/captions/TrailerSubtitleCacheTranslationTest.kt
git commit -m "$(cat <<'EOF'
feat(trailer/captions): AI translation branch in TrailerSubtitleCache.ensure

After the source SRT is fetched + parsed + written, the cache inspects
selected.translateTo. When it's non-null AND distinct from source
language AND SubtitleTranslationSettings.enabled is true AND apiKey is
non-blank, call SubtitleTranslationService.translateSrtAtomically and
write the result to <hash>-<src>-<tgt>.srt next to the source. Return
its file:// URI.

Every failure path (translation disabled, apiKey blank, service
returned null, write IO failure, even throw) falls back to the source
SRT URI. Translation failures never break caption rendering — at worst
the user sees source-language captions instead of their preferred-
language captions.

Cache lifecycle:
- Source SRT: cacheDir/trailer-subtitles/<hash>-<src>.srt
- Translated SRT: cacheDir/trailer-subtitles/<hash>-<src>-<tgt>.srt
- Both keyed by sha1_16(baseUrl). Cache hit on the translated file
  short-circuits source fetch entirely.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task C1: Extract `applySubtitleStyle` into a shared painter

Move the helper without changing its body, so both stream and trailer can call it.

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/components/SubtitleStylePainter.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerScreen.kt`

- [ ] **Step 1: Create the shared painter file**

Create `app/src/main/java/com/nexio/tv/ui/components/SubtitleStylePainter.kt`:

```kotlin
package com.nexio.tv.ui.components

import android.graphics.Typeface
import android.util.TypedValue
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import com.nexio.tv.core.player.BurnInProtectionState
import com.nexio.tv.data.local.SubtitleStyleSettings

internal const val SUBTITLE_OFF_WHITE_ARGB = 0xFFEDEDED.toInt()
internal const val SUBTITLE_MAX_ALPHA = 0.95f

/**
 * Apply the user's subtitle style to a Media3 SubtitleView. Shared between
 * the stream player (PlayerScreen) and the trailer player (TrailerPlayer)
 * so captions render identically across both surfaces (transparent
 * background, outlined edge, configured font/size/color).
 *
 * Trailer callers pass [BurnInProtectionState.DISABLED]: trailers are
 * short (~30s–2min) so burn-in mitigation is irrelevant.
 */
internal fun applySubtitleViewStyle(
    subtitleView: SubtitleView,
    subtitleStyle: SubtitleStyleSettings,
    burnInProtection: BurnInProtectionState,
) {
    val baseFontSize = 24f
    val scaledFontSize = baseFontSize * (subtitleStyle.size / 100f)
    subtitleView.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, scaledFontSize)
    subtitleView.setApplyEmbeddedFontSizes(false)

    val typeface = if (subtitleStyle.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    val edgeType = if (subtitleStyle.outlineEnabled) {
        CaptionStyleCompat.EDGE_TYPE_OUTLINE
    } else {
        CaptionStyleCompat.EDGE_TYPE_NONE
    }

    val foregroundColor = if (burnInProtection.enabled) {
        SUBTITLE_OFF_WHITE_ARGB
    } else {
        android.graphics.Color.WHITE
    }

    subtitleView.setStyle(
        CaptionStyleCompat(
            foregroundColor,
            subtitleStyle.backgroundColor,
            android.graphics.Color.TRANSPARENT,
            edgeType,
            subtitleStyle.outlineColor,
            typeface
        )
    )
    subtitleView.setApplyEmbeddedStyles(false)
    subtitleView.alpha = if (burnInProtection.enabled) SUBTITLE_MAX_ALPHA else 1.0f
    subtitleView.translationX = burnInProtection.horizontalOffsetPx

    val effectivePercent = subtitleStyle.verticalOffset + burnInProtection.verticalDeltaPercent
    val bottomPaddingFraction = (0.06f + (effectivePercent / 250f)).coerceIn(0f, 0.4f)
    subtitleView.setBottomPaddingFraction(bottomPaddingFraction)
    subtitleView.post {
        val extraPadding = (subtitleView.height * (effectivePercent / 400f))
            .toInt()
            .coerceAtLeast(0)
        subtitleView.setPadding(
            subtitleView.paddingLeft,
            subtitleView.paddingTop,
            subtitleView.paddingRight,
            extraPadding
        )
    }
}
```

> **Note on the `SUBTITLE_OFF_WHITE_ARGB` / `SUBTITLE_MAX_ALPHA` constants:**
> These are currently defined in `PlayerScreen.kt`. Search for their declarations there (`grep -n "SUBTITLE_OFF_WHITE_ARGB\|SUBTITLE_MAX_ALPHA" app/src/main/java/com/nexio/tv/ui/screens/player/PlayerScreen.kt`). If they're declared at file-level or in a companion, you have two choices:
> 1. Move them into the new painter file (preferred — single source of truth) and update PlayerScreen to import them from `com.nexio.tv.ui.components.SUBTITLE_OFF_WHITE_ARGB`.
> 2. Leave them in PlayerScreen and reference via fully-qualified imports.
> Use whichever causes the fewest cross-file edits. The declaration shown in the file above assumes option 1 — adjust if the existing constants live elsewhere.

- [ ] **Step 2: Refactor `PlayerScreen.applySubtitleStyle` to delegate**

In `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerScreen.kt`, replace the body of `applySubtitleStyle` (starting line 133) with a single delegation:

```kotlin
internal fun applySubtitleStyle(
    subtitleView: SubtitleView,
    subtitleStyle: com.nexio.tv.data.local.SubtitleStyleSettings,
    burnInProtection: BurnInProtectionState,
) {
    com.nexio.tv.ui.components.applySubtitleViewStyle(
        subtitleView = subtitleView,
        subtitleStyle = subtitleStyle,
        burnInProtection = burnInProtection
    )
}
```

The function name `applySubtitleStyle` and its signature remain — only the body is hollowed out. All existing callers of `applySubtitleStyle` are unaffected.

If the existing `SUBTITLE_OFF_WHITE_ARGB` / `SUBTITLE_MAX_ALPHA` declarations now live in the painter file, remove their duplicates from PlayerScreen.

- [ ] **Step 3: Verify compilation**

Run: `./gradlew :app:compileUniversalDebugKotlin --console=plain 2>&1 | grep -E "^e: |^error:|BUILD FAIL|BUILD SUCCESSFUL" | head -10`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Run stream player tests for regression**

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.player.*" --console=plain 2>&1 | tail -10`

Expected: `BUILD SUCCESSFUL`. If a test references the moved constants by their old path, fix the import.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/components/SubtitleStylePainter.kt \
        app/src/main/java/com/nexio/tv/ui/screens/player/PlayerScreen.kt
git commit -m "$(cat <<'EOF'
refactor(player): extract applySubtitleStyle into shared SubtitleStylePainter

Lift the CaptionStyleCompat configuration logic from PlayerScreen into
a shared file in ui/components/ so the trailer player can call it too
(Task C2). Pure refactor: byte-equivalent behavior, function name and
signature unchanged on the stream side.

Trailer player will pass BurnInProtectionState.DISABLED — trailers are
short and burn-in mitigation is irrelevant.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task C2: Call the shared painter from `bindTrailerPlayerView`

Stop relying on Media3's default subtitle styling (black background); apply the user's `SubtitleStyleSettings` instead.

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/components/TrailerPlayer.kt`

- [ ] **Step 1: Inspect current `bindTrailerPlayerView`**

Run: `sed -n '74,95p' /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/components/TrailerPlayer.kt`

Note the function signature. It currently takes `(view: PlayerView, player: ExoPlayer?)` and does NOT receive `SubtitleStyleSettings`. We add it.

- [ ] **Step 2: Add `subtitleStyle` parameter and style application**

Edit `bindTrailerPlayerView` to take a nullable `SubtitleStyleSettings`:

```kotlin
internal fun bindTrailerPlayerView(
    view: PlayerView,
    player: ExoPlayer?,
    subtitleStyle: com.nexio.tv.data.local.SubtitleStyleSettings? = null,
) {
    view.player = player
    val subtitleView = view.subtitleView
    if (subtitleView != null && subtitleStyle != null) {
        applySubtitleViewStyle(
            subtitleView = subtitleView,
            subtitleStyle = subtitleStyle,
            burnInProtection = com.nexio.tv.core.player.BurnInProtectionState.DISABLED
        )
    }
}
```

(If `bindTrailerPlayerView` does more than `view.player = player` today — open the file at the function and preserve those extra lines verbatim. The diff is only the parameter addition + the styling call.)

- [ ] **Step 3: Plumb `subtitleStyle` to both callers**

Find the two call sites:

```bash
grep -n "bindTrailerPlayerView" /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/components/TrailerPlayer.kt
```

Both call sites are inside the `TrailerPlayer` Composable (around lines 486 and 505 per the earlier grep). The Composable already reads `playerSettingsSnapshot` from `TrailerSubtitlePrefAccess`. Extract the subtitleStyle from that snapshot and pass it to both call sites:

Inside the Composable, near where `preferredSubtitleLanguage` is computed:

```kotlin
val subtitleStyleForView = playerSettingsSnapshot?.subtitleStyle
```

At both call sites:

```kotlin
bindTrailerPlayerView(this, trailerPlayer, subtitleStyleForView)
// and
bindTrailerPlayerView(view, trailerPlayer, subtitleStyleForView)
```

- [ ] **Step 4: Verify compilation**

Run: `./gradlew :app:compileUniversalDebugKotlin --console=plain 2>&1 | grep -E "^e: |^error:|BUILD FAIL|BUILD SUCCESSFUL" | head -10`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Run targeted tests**

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.trailer.*" --tests "com.nexio.tv.data.trailer.captions.*" --tests "com.nexio.tv.data.trailer.cipher.*" --console=plain 2>&1 | tail -10`

Expected: `BUILD SUCCESSFUL`. No new tests for this task — the style application is verified on-device in Task E.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/components/TrailerPlayer.kt
git commit -m "$(cat <<'EOF'
fix(trailer/captions): apply user subtitle style to trailer PlayerView

Media3's default SubtitleView styling paints a black box behind cue
text. The stream player's PlayerScreen.applySubtitleStyle overrides
this with the user's SubtitleStyleSettings — transparent background,
outline edge, configured font/size/color. The trailer's
bindTrailerPlayerView previously applied no styling, so trailers
showed captions over an unwanted black background.

Pass the user's subtitle style from the TrailerPlayer Composable
(already reads playerSettingsSnapshot via TrailerSubtitlePrefAccess)
into bindTrailerPlayerView, which calls the shared
applySubtitleViewStyle helper extracted in Task C1.
BurnInProtectionState.DISABLED — trailers are too short for burn-in
mitigation to matter.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task D1: Gate `ModernHeroGradientLayer` on `fullscreenTrailerActive`

When the trailer is rendered fullscreen, skip the gradient overlay entirely so corners don't darken.

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeHero.kt`

- [ ] **Step 1: Add the `fullscreenTrailerActive` parameter**

Open `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeHero.kt` at line 187. Change the function:

```kotlin
@Composable
internal fun ModernHeroGradientLayer(
    bgColor: Color,
    modifier: Modifier
) {
    Box(
        modifier = modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithCache {
                // ...existing gradient construction + onDrawBehind...
            }
    )
}
```

to:

```kotlin
@Composable
internal fun ModernHeroGradientLayer(
    bgColor: Color,
    fullscreenTrailerActive: Boolean,
    modifier: Modifier
) {
    if (fullscreenTrailerActive) {
        // Skip the gradient overlay entirely when the trailer occupies the
        // full hero area. The gradient is designed to blend the corner-
        // window trailer back into the home composition; in fullscreen
        // it would just darken the trailer's left/bottom corners and
        // reduce subtitle readability.
        Box(modifier = modifier)
        return
    }
    Box(
        modifier = modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithCache {
                // ...existing gradient construction + onDrawBehind unchanged...
            }
    )
}
```

The existing `drawWithCache { ... onDrawBehind { ... } }` block is unchanged — only the surrounding early-return is added.

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :app:compileUniversalDebugKotlin --console=plain 2>&1 | grep -E "^e: |^error:|BUILD FAIL|BUILD SUCCESSFUL" | head -10`

Expected: compilation FAILS at the existing `ModernHeroGradientLayer(bgColor = ..., modifier = ...)` call site in `ModernHomeContent.kt:1574` because the new required parameter is missing. That's the cue for Task D2.

- [ ] **Step 3: Commit (compilation fix in next task)**

We intentionally leave the codebase in a non-building state for one commit to keep the diff focused. Task D2 immediately follows.

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeHero.kt
git commit -m "$(cat <<'EOF'
feat(home/hero): ModernHeroGradientLayer skips draw when trailer fullscreen

Add a required fullscreenTrailerActive: Boolean parameter to the
gradient layer composable. When the trailer occupies the full hero
area (fullscreenTrailerActive == true), the function returns an empty
Box carrying only the modifier — no gradients painted.

The corner-window trailer view continues to use the gradient to blend
back into the home composition (matches the existing UX). Only the
fullscreen path is changed.

Note: this commit leaves the codebase in a non-building state on its
own — the call site in ModernHomeContent.kt:1574 must be updated to
pass the new parameter. Task D2 follows immediately.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task D2: Pass `fullscreenTrailerActive` from `ModernHomeContent` to the gradient layer

The flag is already computed in `ModernHomeContent.kt:1028`. We thread it through to the gradient call site.

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeContent.kt`

- [ ] **Step 1: Inspect the gradient call site**

Run: `sed -n '1565,1580p' /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeContent.kt`

The current call:

```kotlin
ModernHeroGradientLayer(
    bgColor = bgColor,
    modifier = mediaModifier
)
```

- [ ] **Step 2: Identify where `fullscreenTrailerActive` is available at this call site**

The composable function containing the call already receives or computes `fullscreenTrailerActive`. Scroll up from line 1574 to find the enclosing function signature and check whether `fullscreenTrailerActive: Boolean` is already a parameter. Per the earlier grep, the flag is widely plumbed — line 1446 in this file already passes it into a related callee.

If the enclosing function already has the parameter → use it directly (Step 3a).
If the enclosing function needs the parameter added → propagate one level up (Step 3b).

- [ ] **Step 3a: Use the existing parameter**

If `fullscreenTrailerActive` is already in scope:

```kotlin
ModernHeroGradientLayer(
    bgColor = bgColor,
    fullscreenTrailerActive = fullscreenTrailerActive,
    modifier = mediaModifier
)
```

- [ ] **Step 3b: Propagate the parameter (only if 3a is not possible)**

Add `fullscreenTrailerActive: Boolean` to the enclosing function's parameter list. Find that function's caller(s) and pass the flag through. The caller chain ends at the site that originally computed it (line 1028); every link in between just receives + passes the parameter. Each new parameter is a 1-line addition per file.

- [ ] **Step 4: Verify compilation**

Run: `./gradlew :app:compileUniversalDebugKotlin --console=plain 2>&1 | grep -E "^e: |^error:|BUILD FAIL|BUILD SUCCESSFUL" | head -10`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Run targeted UI tests**

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.home.*" --console=plain 2>&1 | tail -10`

Expected: `BUILD SUCCESSFUL`. No new test for the gradient skip — it's a Compose draw concern, validated on-device in Task E.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeContent.kt
git commit -m "$(cat <<'EOF'
feat(home/hero): pass fullscreenTrailerActive into ModernHeroGradientLayer

Plug the existing fullscreenTrailerActive flag (computed at
ModernHomeContent.kt:1028 as heroTrailerFullscreenMode &&
heroTrailerInternalPlaying) through to the gradient call at line 1574,
completing the change started in Task D1.

After this commit:
- Corner-window trailer: gradient drawn (unchanged behavior).
- Fullscreen trailer: gradient skipped, corners no longer darkened,
  subtitle readability improved.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task E: On-device smoke test

End-to-end verification of all four user-visible changes.

**Files:** none — operational.

- [ ] **Step 1: Build + install**

Run: `./gradlew :app:installUniversalDebug --console=plain 2>&1 | tail -3`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Force-stop, launch, select profile (CLAUDE.md rule #8)**

```bash
adb -s 192.168.50.98:5555 shell am force-stop com.nexiodebug.tv
adb -s 192.168.50.98:5555 logcat -c
adb -s 192.168.50.98:5555 shell monkey -p com.nexiodebug.tv 1
sleep 5
adb -s 192.168.50.98:5555 shell input keyevent KEYCODE_DPAD_CENTER
sleep 35
```

- [ ] **Step 3: Confirm AI translation is configured (or pre-configure for testing)**

```bash
adb -s 192.168.50.98:5555 shell run-as com.nexiodebug.tv ls -la databases/ 2>&1 | head -10
```

If the user already has a provider + apiKey set in Settings → Subtitles → Translation, proceed. Otherwise navigate Settings via the remote and configure (or skip translation verification and only check the styling + gradient fixes — both can be checked without AI translation).

- [ ] **Step 4: Play a trailer with captions**

Navigate to a movie whose YouTube trailer ships English captions (Project Hail Mary, confirmed from earlier smoke runs). Let it play ~15 seconds.

- [ ] **Step 5: Verify the cache + logs**

```bash
adb -s 192.168.50.98:5555 shell run-as com.nexiodebug.tv ls -la cache/trailer-subtitles/ 2>&1
```

Expected (with AI translation configured and preferred lang ≠ source):
```
<hash>-en.srt        ← source
<hash>-en-<lang>.srt ← translated
```

Expected (without AI translation): only `<hash>-en.srt`.

```bash
adb -s 192.168.50.98:5555 logcat -d -t 6000 | grep -iE "TrailerSubtitleCache|translateSrtAtomically|subtitle ready|TrailerPlayer.*subtitle" | tail -20
```

Look for the `translated SRT cached at` log line on the AI-enabled path. On AI-disabled, look for `AI translation disabled — serving source SRT`.

- [ ] **Step 6: Verify subtitle visual styling**

Watch the trailer for cues to appear. Confirm:
- No black box behind caption text (matches stream player).
- Outline around text (matches stream player).
- Font size + color match the user's Settings → Subtitles preferences.

- [ ] **Step 7: Verify fullscreen gradient skip**

Trigger the trailer's fullscreen mode (mechanism depends on the home UX — typically a long idle that lets the screensaver expand the trailer, OR a specific key press from the hero card). Once fullscreen is active:

```bash
adb -s 192.168.50.98:5555 logcat -d -t 2000 | grep -iE "fullscreenTrailerActive|heroTrailerFullscreenMode|IdleScreensaverDebug.*inAppTrailerActive=true" | tail -10
```

Visually inspect the trailer corners (especially left + bottom). Confirm:
- No darkening on the corners (gradient gone).
- Captions remain readable through any video content; nothing else changed.

Compare to corner-window mode (back out of fullscreen, observe the trailer in the hero corner): the gradient should still be present there.

- [ ] **Step 8: Decision gate**

**Branch A — All four user-visible changes work:**
✅ Captions render with user style on trailers + AI translation produces a translated `.srt` file + fullscreen drops the gradient. Push commits, archive earlier diagnostic-only logs if any remain.

**Branch B — Translation produces a file but captions still render in source language:**
ExoPlayer is probably picking the wrong SubtitleConfiguration. Inspect `TrailerPlayer` LaunchedEffect logs for `subtitle ready lang=...`. If `lang=en` and the file is `<hash>-en-nl.srt`, the `MediaItem.SubtitleConfiguration.Builder.setLanguage(...)` is being told `en` instead of `nl`. Fix in `TrailerPlayer.kt` LaunchedEffect: use `selected.translateTo ?: selected.languageCode` for the language tag.

**Branch C — Translation file written but malformed (cue count or timestamps off):**
The provider response is bypassing the cue-count validation somehow. Inspect `subtitleTranslationServiceAtomicTest`'s production parity — the prompt may need tightening, or `SRT_TIMESTAMP_REGEX` may not match the model's output (e.g. period vs comma in milliseconds). Update the regex / prompt accordingly. Worth a follow-up commit.

**Branch D — Gradient still appears in fullscreen:**
Either `fullscreenTrailerActive` is being computed false at the call site, or it's not propagating. Add a one-shot `Log.d(...)` in the gradient layer's early-return branch (`Log.d("ModernHomeHero", "gradient SKIPPED fullscreen=true")`) to confirm the branch is hit. If it never logs, the flag isn't reaching this call site — trace back to ModernHomeContent.kt:1028.

**Branch E — Subtitles still have black background:**
The painter wasn't actually invoked. Check Task C2's plumbing: was `subtitleStyle` propagated to BOTH `bindTrailerPlayerView` call sites? Are there OTHER `PlayerView` inflations bypassing `bindTrailerPlayerView`? Search: `grep -n "PlayerView\b\|exo_trailer_player_view" app/src/main/java/`.

---

## Self-Review

**Spec coverage:**

| Spec item | Task |
|---|---|
| New `translateSrtAtomically` method, single provider call, no batching | B1 |
| `TrailerSubtitleCache.ensure()` translation hook | B3 |
| Picker emits `translateTo` when no native match | A1 |
| Cache key reuses `cacheFileFor` with tlang suffix | B3 Step 3 (uses existing `cacheFileFor`) |
| Failure modes fall back to source SRT | B3 Step 3 + B3 tests |
| Validation: cue count + timestamp shape | B1 Step 3 (`countSrtCues`, `SRT_TIMESTAMP_REGEX`) |
| Strip conversational preamble | B1 Step 3 (`stripConversationalPreamble`) |
| Shared `applyTrailerCompatibleSubtitleStyle` helper | C1 |
| Trailer player applies user `SubtitleStyleSettings` | C2 |
| BurnInProtection disabled on trailers | C2 Step 2 |
| `ModernHeroGradientLayer` gains `fullscreenTrailerActive` | D1 |
| Gradient skipped when fullscreen | D1 Step 1 |
| Flag propagated from `ModernHomeContent` | D2 |
| On-device smoke | E |

**Placeholder scan:** Every code step has executable code. Every test step has runnable assertions. The two `> Note on ...` callouts (Task B1 Step 3, Task B3 Step 1) explicitly instruct the implementer to inspect a specific file with a specific grep before choosing between two well-defined options — that's not a placeholder, that's an informed branch.

**Type consistency:**
- `SelectedTrailerCaptionTrack` shape: `baseUrl`, `languageCode`, `translateTo` — consistent in A1 picker, B3 cache, and tests.
- `SubtitleTranslationService.translateSrtAtomically(srt: String, sourceLanguageCode: String, targetLanguageCode: String, settings: SubtitleTranslationSettings): String?` — same signature in B1 declaration, B1 tests, and B3 callers.
- `applySubtitleViewStyle(view, subtitleStyle, burnInProtection)` — same signature in C1 declaration, C1 PlayerScreen delegation, and C2 trailer call.
- `ModernHeroGradientLayer(bgColor, fullscreenTrailerActive, modifier)` — same signature in D1 declaration and D2 call site.

**Known follow-ups (out of scope for this plan):**

- AI translation for subtitles loaded from OpenSubtitles into trailers (today only YouTube captions are translatable). Not in scope.
- Provider switching as a cache-key dimension (today the cache survives provider switches; rare event, acceptable trade-off).
- Burn-in protection for trailers if playback duration ever grows beyond ~2 min. Not in scope today.
- Replacing the latent `false` literal at `ModernHomeContent.kt:166` (`resolveModernHomeHeroFullscreenHintEndPadding(fullscreenTrailerActive = false)`). Originally flagged in the spec as a latent bug; deferring to a follow-up because it's a different concern (hint padding, not gradient) from this plan's scope.
