# AI Auto-Translate Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Address four non-blocking quality items from the final code review of the AI auto-translate language policy feature: eliminate silent test/prod drift on prompt source-clauses, align auto-detect phrasing across the three prompt builders, deduplicate the `bestByAccessibility` local helper, and tighten the SDH `" cc "` marker against whitespace-fragile names like `"English-CC"` or `"CC English"`.

**Architecture:** Two production files (`SubtitleTranslationService.kt`, `PlayerStartupSelectionPolicy.kt`) and one test file (`PlayerStartupSelectionPolicyTest.kt`). Every change is a refactor or a localized text tweak — no new public APIs, no behavior change for end users, no test signature changes.

**Tech Stack:** Kotlin / JUnit 4. JVM unit tests via `:app:testUniversalDebugUnitTest`.

**Source review:** `docs/superpowers/specs/2026-05-10-ai-auto-translate-language-policy-design.md` and the cross-task final review (commits `c29651a68`..`afbdc2e45`).

---

## File Structure

| File | Role | Status |
|---|---|---|
| `app/src/main/java/com/nexio/tv/data/repository/SubtitleTranslationService.kt` | Owns three prompt builders + their companion accessors. Adds two private file-level source-clause helpers (`rawSubRipSourceClause`, `rawAssSsaSourceClause`). Adjusts T6 JSON prompt's auto-detect copy. | Modify |
| `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerStartupSelectionPolicy.kt` | Hosts `bestByAccessibility` (duplicated). Hosts `SDH_SUBTITLE_MARKERS` (whitespace-fragile `" cc "`). Adds private file-level `bestSubtitleByAccessibility` helper. Replaces `" cc "` with a `\bcc\b` regex check. | Modify |
| `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerStartupSelectionPolicyTest.kt` | Adds 3 new tests pinning the SDH regex behavior on `"English-CC"`, `"CC English"`, and `"Soccer commentary"` (negative). | Modify |

`app/src/test/java/com/nexio/tv/data/repository/SubtitleTranslationServicePromptTest.kt` is **not** modified — its existing substring assertions (`"detect"`, `"automatically"`, target/source name) survive the auto-detect phrasing tweak in Task 2 unchanged.

**Build & test commands** (run from repo root):

```bash
./gradlew :app:testUniversalDebugUnitTest --tests 'com.nexio.tv.ui.screens.player.PlayerStartupSelectionPolicyTest'
./gradlew :app:testUniversalDebugUnitTest --tests 'com.nexio.tv.data.repository.SubtitleTranslationServicePromptTest'
./gradlew :app:testUniversalDebugUnitTest --tests 'com.nexio.tv.data.repository.SubtitleTranslationServiceProviderTest'
```

---

## Task 1: Hoist source-clause helpers for raw SubRip and ASS/SSA prompts

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/SubtitleTranslationService.kt`

T7 (`buildRawSubRipSystemPrompt`) and T8 (`buildRawAssSsaSystemPrompt`) currently duplicate their `sourceClause` `if/else` block character-for-character between the instance method and the companion accessor. A future copy edit applied to one and not the other silently ships drifted prompts while tests stay green.

Solution: introduce two private file-level helpers (`rawSubRipSourceClause`, `rawAssSsaSourceClause`) that compute the source-clause string. Both the instance method and the companion accessor call them. Single source of truth per prompt builder; no test-signature change required because the existing companion accessors continue to return identical text.

- [ ] **Step 1: Run the existing tests to confirm green baseline**

```
./gradlew :app:testUniversalDebugUnitTest --tests 'com.nexio.tv.data.repository.SubtitleTranslationServicePromptTest'
```

Expected: 6 tests pass.

- [ ] **Step 2: Add the two private file-level helpers**

In `SubtitleTranslationService.kt`, append the following at the very end of the file (after the closing `}` of the class, after any trailing typealias declarations). These are top-level `private` Kotlin functions, callable from both the class instance methods and the class companion object.

```kotlin
private fun rawSubRipSourceClause(
    targetLanguageName: String,
    sourceLanguageName: String
): String {
    return if (sourceLanguageName.equals("auto", ignoreCase = true)) {
        "The source language is unknown — detect it automatically from the cue text and translate to $targetLanguageName."
    } else {
        "Translate from $sourceLanguageName to $targetLanguageName."
    }
}

private fun rawAssSsaSourceClause(
    targetLanguageName: String,
    sourceLanguageName: String
): String {
    return if (sourceLanguageName.equals("auto", ignoreCase = true)) {
        "source_language = unknown — detect it automatically from the cue text and translate to $targetLanguageName."
    } else {
        "source_language = $sourceLanguageName — translate to $targetLanguageName."
    }
}
```

The em-dash character is `—` (U+2014), identical to the existing instance/accessor source-clause text.

- [ ] **Step 3: Replace the inline source clause in `buildRawSubRipSystemPrompt`**

Locate `private fun buildRawSubRipSystemPrompt(targetLanguageName: String, sourceLanguageName: String): String` (currently around line 1790 of `SubtitleTranslationService.kt`). The current top of the function is:

```kotlin
    private fun buildRawSubRipSystemPrompt(
        targetLanguageName: String,
        sourceLanguageName: String
    ): String {
        val sourceClause = if (sourceLanguageName.equals("auto", ignoreCase = true)) {
            "The source language is unknown — detect it automatically from the cue text and translate to $targetLanguageName."
        } else {
            "Translate from $sourceLanguageName to $targetLanguageName."
        }
        return """
            You are SRT_TRANSLATION_ENGINE.
            ...
```

Replace the `val sourceClause = if (...) ... else ...` block (lines that include the literal "The source language is unknown — detect it automatically from the cue text and translate to" and "Translate from $sourceLanguageName to $targetLanguageName.") with a single delegation:

```kotlin
    private fun buildRawSubRipSystemPrompt(
        targetLanguageName: String,
        sourceLanguageName: String
    ): String {
        val sourceClause = rawSubRipSourceClause(targetLanguageName, sourceLanguageName)
        return """
            You are SRT_TRANSLATION_ENGINE.
            ...
```

Do NOT change anything below `return """` — the entire prompt body (`You are SRT_TRANSLATION_ENGINE.`, the inserted `$sourceClause` line, the `Your task...` head sentence, and the entire OUTPUT RULE section + every line that follows up to the closing `""".trimIndent()`) stays byte-identical.

- [ ] **Step 4: Replace the inline source clause in `buildRawAssSsaSystemPrompt`**

Locate `private fun buildRawAssSsaSystemPrompt(targetLanguageName: String, sourceLanguageName: String): String` (currently around line 1686). The current top of the function is:

```kotlin
    private fun buildRawAssSsaSystemPrompt(
        targetLanguageName: String,
        sourceLanguageName: String
    ): String {
        val sourceClause = if (sourceLanguageName.equals("auto", ignoreCase = true)) {
            "source_language = unknown — detect it automatically from the cue text and translate to $targetLanguageName."
        } else {
            "source_language = $sourceLanguageName — translate to $targetLanguageName."
        }
        return """
            You are ASS_SSA_SUBTITLE_TRANSLATOR.
            ...
```

Replace the `val sourceClause = if (...) ... else ...` block with:

```kotlin
    private fun buildRawAssSsaSystemPrompt(
        targetLanguageName: String,
        sourceLanguageName: String
    ): String {
        val sourceClause = rawAssSsaSourceClause(targetLanguageName, sourceLanguageName)
        return """
            You are ASS_SSA_SUBTITLE_TRANSLATOR.
            ...
```

Do NOT change anything below `return """`. Body from `You are ASS_SSA_SUBTITLE_TRANSLATOR.` through the closing `""".trimIndent()` stays byte-identical.

- [ ] **Step 5: Replace the inline source clause in `buildRawSubRipSystemPromptForTest` (companion accessor)**

In the companion object (around line 204), find `internal fun buildRawSubRipSystemPromptForTest(...)`. Replace its inline `sourceClause` `if/else` block with a delegation. Current:

```kotlin
        @androidx.annotation.VisibleForTesting
        internal fun buildRawSubRipSystemPromptForTest(
            targetLanguageName: String,
            sourceLanguageName: String
        ): String {
            val sourceClause = if (sourceLanguageName.equals("auto", ignoreCase = true)) {
                "The source language is unknown — detect it automatically from the cue text and translate to $targetLanguageName."
            } else {
                "Translate from $sourceLanguageName to $targetLanguageName."
            }
            return """
                You are SRT_TRANSLATION_ENGINE.

                $sourceClause

                Your task is to translate raw SRT subtitle content into $targetLanguageName while preserving valid SRT format exactly.
            """.trimIndent()
        }
```

Replace with:

```kotlin
        @androidx.annotation.VisibleForTesting
        internal fun buildRawSubRipSystemPromptForTest(
            targetLanguageName: String,
            sourceLanguageName: String
        ): String {
            val sourceClause = rawSubRipSourceClause(targetLanguageName, sourceLanguageName)
            return """
                You are SRT_TRANSLATION_ENGINE.

                $sourceClause

                Your task is to translate raw SRT subtitle content into $targetLanguageName while preserving valid SRT format exactly.
            """.trimIndent()
        }
```

- [ ] **Step 6: Replace the inline source clause in `buildRawAssSsaSystemPromptForTest` (companion accessor)**

Find `internal fun buildRawAssSsaSystemPromptForTest(...)` in the same companion object (around line 222). Replace inline source clause with delegation. Current:

```kotlin
        @androidx.annotation.VisibleForTesting
        internal fun buildRawAssSsaSystemPromptForTest(
            targetLanguageName: String,
            sourceLanguageName: String
        ): String {
            val sourceClause = if (sourceLanguageName.equals("auto", ignoreCase = true)) {
                "source_language = unknown — detect it automatically from the cue text and translate to $targetLanguageName."
            } else {
                "source_language = $sourceLanguageName — translate to $targetLanguageName."
            }
            return """
                You are ASS_SSA_SUBTITLE_TRANSLATOR.

                target_language = $targetLanguageName
                $sourceClause
            """.trimIndent()
        }
```

Replace with:

```kotlin
        @androidx.annotation.VisibleForTesting
        internal fun buildRawAssSsaSystemPromptForTest(
            targetLanguageName: String,
            sourceLanguageName: String
        ): String {
            val sourceClause = rawAssSsaSourceClause(targetLanguageName, sourceLanguageName)
            return """
                You are ASS_SSA_SUBTITLE_TRANSLATOR.

                target_language = $targetLanguageName
                $sourceClause
            """.trimIndent()
        }
```

- [ ] **Step 7: Run the prompt tests and the provider tests to confirm no regression**

```
./gradlew :app:testUniversalDebugUnitTest --tests 'com.nexio.tv.data.repository.SubtitleTranslationServicePromptTest'
./gradlew :app:testUniversalDebugUnitTest --tests 'com.nexio.tv.data.repository.SubtitleTranslationServiceProviderTest'
```

Expected: prompt test = 6 pass, provider test = pass. The companion accessors return byte-identical strings as before (they now call the same helper that the instance methods call), so existing substring assertions in `SubtitleTranslationServicePromptTest.kt` continue to hold.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/SubtitleTranslationService.kt

git commit -m "$(cat <<'EOF'
refactor(translation): single source of truth for raw-prompt source clauses

Hoists the raw-SubRip and raw-ASS/SSA source-clause if/else blocks into
two private file-level helpers (rawSubRipSourceClause,
rawAssSsaSourceClause) used by both the instance method (full prompt)
and the @VisibleForTesting companion accessor (head-only). Removes the
silent test/prod drift hazard where a copy edit to one branch could go
unnoticed by tests against the other. No behavior change.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Align T6 JSON prompt auto-detect phrasing to include the target

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/SubtitleTranslationService.kt`

T7 and T8 both append "and translate to &lt;target&gt;" in their auto-detect branches. T6 does not — its auto-detect copy stops after "from the cue text. " and relies on the surrounding `Translate only the text fields ... into Dutch (nl).` sentence to carry the target. A future maintainer reading all three side-by-side will reasonably read this asymmetry as a missed edit.

Fix: append "and translate to &lt;target&gt;" to the T6 auto branch so all three builders read the same way.

- [ ] **Step 1: Confirm the existing T6 auto-detect test still asserts only on substrings**

Open `app/src/test/java/com/nexio/tv/data/repository/SubtitleTranslationServicePromptTest.kt`. Find `buildTranslationSystemPrompt instructs auto-detection when source is auto`. Verify it asserts: lowercased prompt contains `"detect"` AND `"automatically"`, and the un-lowered prompt contains `"Dutch"`. The new phrasing satisfies all three substrings (`detect`, `automatically`, `Dutch`) so no test edit is needed.

- [ ] **Step 2: Update the T6 JSON prompt builder companion accessor**

In `SubtitleTranslationService.kt`, find `internal fun buildTranslationSystemPromptForTest(...)` in the companion object (around line 176). The current auto branch reads:

```kotlin
                if (sourceLanguageName.equals("auto", ignoreCase = true)) {
                    append("The source language is unknown — detect it automatically from the cue text. ")
                } else {
```

Replace the `append(...)` line in the auto branch with:

```kotlin
                if (sourceLanguageName.equals("auto", ignoreCase = true)) {
                    append("The source language is unknown — detect it automatically from the cue text and translate to ")
                    append(targetLanguageName)
                    append(". ")
                } else {
```

Note the splitting into three `append` calls so the literal string interpolation matches the existing builder's style (the rest of `buildTranslationSystemPromptForTest` uses one `append` per segment).

The instance method `buildTranslationSystemPrompt` (around line 1057) already delegates to the companion accessor, so it picks up the new phrasing automatically — no instance edit is needed.

- [ ] **Step 3: Run the prompt tests**

```
./gradlew :app:testUniversalDebugUnitTest --tests 'com.nexio.tv.data.repository.SubtitleTranslationServicePromptTest'
```

Expected: 6 tests pass. The auto-detect test still finds `"detect"`, `"automatically"`, and `"Dutch"` in the prompt; the explicit-source test still finds `"Polish"` and `"Dutch"`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/SubtitleTranslationService.kt

git commit -m "$(cat <<'EOF'
chore(translation): align JSON-prompt auto-detect copy with raw prompts

T6 (JSON) auto-detect branch now ends with "and translate to <target>."
to match T7 (SRT) and T8 (ASS/SSA). Removes the visual asymmetry
between the three prompt builders without changing the behavioral
contract — the target language was already named earlier in the same
sentence. Existing substring tests still pass.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Hoist `bestByAccessibility` to a private file-level helper

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerStartupSelectionPolicy.kt`

`bestByAccessibility` is currently defined as a local function inside two call sites (`pickTranslatableInternalSubtitle` line ~627, `breakPortugueseSubtitleTieForStartup` line ~734). Same body, parameter names diverge (`candidates` vs `filtered`). Two call sites is right at the inflection point where extraction starts paying off; a third copy is likely the next time someone adds a tiebreaker.

Fix: introduce `private fun bestSubtitleByAccessibility(subtitleTracks: List<TrackInfo>, candidates: List<Int>): Int?` at file scope. Replace both local-function call sites.

- [ ] **Step 1: Run the existing tests to confirm green baseline**

```
./gradlew :app:testUniversalDebugUnitTest --tests 'com.nexio.tv.ui.screens.player.PlayerStartupSelectionPolicyTest'
```

Expected: 38 tests pass.

- [ ] **Step 2: Add the file-level helper**

In `PlayerStartupSelectionPolicy.kt`, append the helper at the end of the file, right after `subtitleAccessibilityRank` (currently lines 814-818). Position is important: it must be after `subtitleAccessibilityRank` so it can call it directly.

```kotlin
private fun bestSubtitleByAccessibility(
    subtitleTracks: List<TrackInfo>,
    candidates: List<Int>
): Int? {
    return candidates.minByOrNull { subtitleTracks[it].subtitleAccessibilityRank() }
}
```

- [ ] **Step 3: Replace the local helper in `pickTranslatableInternalSubtitle`**

Locate `pickTranslatableInternalSubtitle` (around line 616). The current body is:

```kotlin
internal fun pickTranslatableInternalSubtitle(
    subtitleTracks: List<TrackInfo>,
    secondaryLanguage: String?
): Int {
    if (subtitleTracks.isEmpty()) return -1
    val textTracks = subtitleTracks
        .mapIndexedNotNull { index, track ->
            if (isBitmapSubtitleMimeType(track.mimeType)) null else index
        }
    if (textTracks.isEmpty()) return -1

    fun bestByAccessibility(candidates: List<Int>): Int? {
        return candidates.minByOrNull { subtitleTracks[it].subtitleAccessibilityRank() }
    }

    if (!secondaryLanguage.isNullOrBlank()) {
        val secondaryMatches = textTracks.filter { index ->
            PlayerSubtitleUtils.matchesLanguageCode(subtitleTracks[index].language, secondaryLanguage)
        }
        bestByAccessibility(secondaryMatches)?.let { return it }
    }
    val englishMatches = textTracks.filter { index ->
        PlayerSubtitleUtils.matchesLanguageCode(subtitleTracks[index].language, "en")
    }
    bestByAccessibility(englishMatches)?.let { return it }
    return bestByAccessibility(textTracks) ?: textTracks.first()
}
```

Replace with:

```kotlin
internal fun pickTranslatableInternalSubtitle(
    subtitleTracks: List<TrackInfo>,
    secondaryLanguage: String?
): Int {
    if (subtitleTracks.isEmpty()) return -1
    val textTracks = subtitleTracks
        .mapIndexedNotNull { index, track ->
            if (isBitmapSubtitleMimeType(track.mimeType)) null else index
        }
    if (textTracks.isEmpty()) return -1

    if (!secondaryLanguage.isNullOrBlank()) {
        val secondaryMatches = textTracks.filter { index ->
            PlayerSubtitleUtils.matchesLanguageCode(subtitleTracks[index].language, secondaryLanguage)
        }
        bestSubtitleByAccessibility(subtitleTracks, secondaryMatches)?.let { return it }
    }
    val englishMatches = textTracks.filter { index ->
        PlayerSubtitleUtils.matchesLanguageCode(subtitleTracks[index].language, "en")
    }
    bestSubtitleByAccessibility(subtitleTracks, englishMatches)?.let { return it }
    return bestSubtitleByAccessibility(subtitleTracks, textTracks) ?: textTracks.first()
}
```

- [ ] **Step 4: Replace the local helper in `breakPortugueseSubtitleTieForStartup`**

Locate `breakPortugueseSubtitleTieForStartup` (around line 721). The current body is:

```kotlin
private fun breakPortugueseSubtitleTieForStartup(
    subtitleTracks: List<TrackInfo>,
    candidateIndexes: List<Int>,
    normalizedTarget: String
): Int {
    fun hasBrazilianTags(index: Int): Boolean {
        return subtitleHasAnyTagForStartup(subtitleTracks[index], PlayerRuntimeController.PORTUGUESE_BRAZILIAN_TAGS)
    }

    fun hasEuropeanTags(index: Int): Boolean {
        return subtitleHasAnyTagForStartup(subtitleTracks[index], PlayerRuntimeController.PORTUGUESE_EUROPEAN_TAGS)
    }

    fun bestByAccessibility(filtered: List<Int>): Int? {
        return filtered.minByOrNull { subtitleTracks[it].subtitleAccessibilityRank() }
    }

    return if (normalizedTarget == "pt-br") {
        bestByAccessibility(candidateIndexes.filter { hasBrazilianTags(it) && !hasEuropeanTags(it) })
            ?: bestByAccessibility(candidateIndexes.filter { hasBrazilianTags(it) })
            ?: candidateIndexes.first()
    } else {
        bestByAccessibility(candidateIndexes.filter { hasEuropeanTags(it) && !hasBrazilianTags(it) })
            ?: bestByAccessibility(candidateIndexes.filter { hasEuropeanTags(it) })
            ?: bestByAccessibility(candidateIndexes.filter { !hasBrazilianTags(it) })
            ?: candidateIndexes.first()
    }
}
```

Replace with:

```kotlin
private fun breakPortugueseSubtitleTieForStartup(
    subtitleTracks: List<TrackInfo>,
    candidateIndexes: List<Int>,
    normalizedTarget: String
): Int {
    fun hasBrazilianTags(index: Int): Boolean {
        return subtitleHasAnyTagForStartup(subtitleTracks[index], PlayerRuntimeController.PORTUGUESE_BRAZILIAN_TAGS)
    }

    fun hasEuropeanTags(index: Int): Boolean {
        return subtitleHasAnyTagForStartup(subtitleTracks[index], PlayerRuntimeController.PORTUGUESE_EUROPEAN_TAGS)
    }

    return if (normalizedTarget == "pt-br") {
        bestSubtitleByAccessibility(subtitleTracks, candidateIndexes.filter { hasBrazilianTags(it) && !hasEuropeanTags(it) })
            ?: bestSubtitleByAccessibility(subtitleTracks, candidateIndexes.filter { hasBrazilianTags(it) })
            ?: candidateIndexes.first()
    } else {
        bestSubtitleByAccessibility(subtitleTracks, candidateIndexes.filter { hasEuropeanTags(it) && !hasBrazilianTags(it) })
            ?: bestSubtitleByAccessibility(subtitleTracks, candidateIndexes.filter { hasEuropeanTags(it) })
            ?: bestSubtitleByAccessibility(subtitleTracks, candidateIndexes.filter { !hasBrazilianTags(it) })
            ?: candidateIndexes.first()
    }
}
```

- [ ] **Step 5: Replace the inline `minByOrNull` call in `findBestInternalSubtitleTrackIndexForStartup`**

Locate the function (around line 661). The fallback line currently reads:

```kotlin
        return candidateIndexes.minByOrNull { subtitleTracks[it].subtitleAccessibilityRank() }
            ?: candidateIndexes.first()
```

Replace with:

```kotlin
        return bestSubtitleByAccessibility(subtitleTracks, candidateIndexes)
            ?: candidateIndexes.first()
```

This is the third call site — completing the deduplication.

- [ ] **Step 6: Run all `PlayerStartupSelectionPolicyTest` tests**

```
./gradlew :app:testUniversalDebugUnitTest --tests 'com.nexio.tv.ui.screens.player.PlayerStartupSelectionPolicyTest'
```

Expected: all 38 tests pass — the refactor changes call shape but not behavior. Each `bestByAccessibility` (former local) now resolves to the file-level `bestSubtitleByAccessibility` with `subtitleTracks` passed explicitly.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerStartupSelectionPolicy.kt

git commit -m "$(cat <<'EOF'
refactor(player): hoist bestByAccessibility to a file-level helper

Replaces the duplicated local 'bestByAccessibility' function in
pickTranslatableInternalSubtitle and breakPortugueseSubtitleTieForStartup
with a single private file-level bestSubtitleByAccessibility helper, and
folds the inline minByOrNull at the end of
findBestInternalSubtitleTrackIndexForStartup into the same helper.
No behavior change.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Tighten the SDH `cc` marker against whitespace-fragile names

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerStartupSelectionPolicy.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerStartupSelectionPolicyTest.kt`

`SDH_SUBTITLE_MARKERS` currently contains the literal substrings `"sdh"`, `"[cc]"`, `" cc "` (space-cc-space), `"closed caption"`, `"hearing impaired"`. The `" cc "` form only matches when "cc" is bracketed by whitespace — `"English-CC"` (lowercased `"english-cc"`) and `"CC English"` (lowercased `"cc english"`) both fail. `"[cc]"` is also a special case of word-boundary match.

A regex-based word-boundary check covers both natural cases at once: `\bcc\b` matches "cc" between any non-word-character boundary, including string boundaries, brackets, parentheses, dashes, and whitespace. This subsumes both `"[cc]"` and `" cc "`. False-positive risk is low — `\bcc\b` does not match "soccer", "accordion", or any token where "cc" is a substring of a longer word.

TDD discipline: write three tests (two positives that currently fail, one negative that already passes) to drive the marker change.

- [ ] **Step 1: Write the failing tests**

Append to `PlayerStartupSelectionPolicyTest.kt` (after the last `@Test` method, before the closing `}` of the class):

```kotlin
    @Test
    fun `findBestInternal SDH detection matches dash-CC suffix`() {
        // "English-CC" lowercased is "english-cc". The current " cc " marker
        // (space-cc-space) does not match this because the leading char is
        // a hyphen, not a space. After the regex tightening, \bcc\b matches
        // "cc" with word boundaries on both sides (start-of-string is a
        // boundary; hyphen is a non-word char and therefore a boundary).
        val tracks = listOf(
            TrackInfo(index = 0, name = "English-CC", language = "en", isForced = false),
            TrackInfo(index = 1, name = "English", language = "en", isForced = false)
        )

        val index = findBestInternalSubtitleTrackIndexForStartup(
            subtitleTracks = tracks,
            targets = listOf("en")
        )

        assertEquals(1, index)
    }

    @Test
    fun `findBestInternal SDH detection matches CC-leading name`() {
        // "CC English" lowercased is "cc english". The current " cc " marker
        // does not match (no leading space). After regex tightening,
        // \bcc\b matches at the start of the string.
        val tracks = listOf(
            TrackInfo(index = 0, name = "CC English", language = "en", isForced = false),
            TrackInfo(index = 1, name = "English", language = "en", isForced = false)
        )

        val index = findBestInternalSubtitleTrackIndexForStartup(
            subtitleTracks = tracks,
            targets = listOf("en")
        )

        assertEquals(1, index)
    }

    @Test
    fun `findBestInternal SDH detection does not match cc inside another word`() {
        // "Soccer commentary" lowercased contains "ccer commen…" — "cc" only
        // appears as a substring of "soccer". \bcc\b requires word
        // boundaries on both sides, so this track must NOT be classified
        // as SDH. Track 0 stays the better pick over a forced fallback.
        val tracks = listOf(
            TrackInfo(index = 0, name = "Soccer commentary", language = "en", isForced = false),
            TrackInfo(index = 1, name = "English (Forced)", language = "en", isForced = true)
        )

        val index = findBestInternalSubtitleTrackIndexForStartup(
            subtitleTracks = tracks,
            targets = listOf("en")
        )

        assertEquals(0, index)
    }
```

- [ ] **Step 2: Run the new tests to verify the first two fail and the third passes**

```
./gradlew :app:testUniversalDebugUnitTest --tests 'com.nexio.tv.ui.screens.player.PlayerStartupSelectionPolicyTest.findBestInternal*'
```

Expected:
- `findBestInternal SDH detection matches dash-CC suffix` — FAIL with `expected:<1> but was:<0>`. Track 0 is currently classified as normal (rank 0) and beats track 1 by index order; the test expects SDH classification (rank 1) so track 1 (rank 0) wins.
- `findBestInternal SDH detection matches CC-leading name` — FAIL with `expected:<1> but was:<0>`. Same reason.
- `findBestInternal SDH detection does not match cc inside another word` — PASS already. "soccer" contains "cc" between word chars so neither the old `" cc "` marker nor the new `\bcc\b` regex matches; track 0 (normal, rank 0) beats track 1 (forced, rank 2).

- [ ] **Step 3: Tighten the SDH marker logic**

In `PlayerStartupSelectionPolicy.kt`, locate `SDH_SUBTITLE_MARKERS` and `isSdhSubtitle` (currently lines 790-804). Replace the marker list and the helper:

```kotlin
private val SDH_SUBTITLE_MARKERS: List<String> = listOf(
    "sdh",
    "[cc]",
    " cc ",
    "closed caption",
    "hearing impaired"
)

private fun TrackInfo.isSdhSubtitle(): Boolean {
    val haystack = listOfNotNull(name, trackId)
        .joinToString(" ")
        .lowercase(Locale.ROOT)
    if (haystack.isBlank()) return false
    return SDH_SUBTITLE_MARKERS.any { marker -> haystack.contains(marker) }
}
```

Replace with:

```kotlin
private val SDH_SUBTITLE_MARKERS: List<String> = listOf(
    "sdh",
    "closed caption",
    "hearing impaired"
)

// Standalone "cc" token (Closed Captions). Word-boundary anchored so
// "Soccer" / "accordion" do not match, but "[CC]", "(CC)", "English-CC",
// "CC English", and "English CC" all match correctly.
private val SDH_CC_TOKEN_REGEX: Regex = Regex("""\bcc\b""")

private fun TrackInfo.isSdhSubtitle(): Boolean {
    val haystack = listOfNotNull(name, trackId)
        .joinToString(" ")
        .lowercase(Locale.ROOT)
    if (haystack.isBlank()) return false
    return SDH_SUBTITLE_MARKERS.any { marker -> haystack.contains(marker) } ||
        SDH_CC_TOKEN_REGEX.containsMatchIn(haystack)
}
```

The previous `"[cc]"` marker is dropped — `\bcc\b` already matches "[cc]" because `[` and `]` are non-word characters and therefore word boundaries. The previous `" cc "` marker is dropped for the same reason — whitespace is a non-word character and therefore a word boundary, but a regex word boundary also handles dash-CC, CC-at-start-of-string, and CC-at-end-of-string cases.

`Regex` is already in scope: `kotlin.text.Regex` is auto-imported. No new import needed.

- [ ] **Step 4: Run the SDH-related tests to confirm they pass**

```
./gradlew :app:testUniversalDebugUnitTest --tests 'com.nexio.tv.ui.screens.player.PlayerStartupSelectionPolicyTest.findBestInternal*'
```

Expected: all `findBestInternal*` tests pass — including the three new ones and all pre-existing ones from Tasks 2-5.

- [ ] **Step 5: Run the full subtitle-policy test class to confirm no regression**

```
./gradlew :app:testUniversalDebugUnitTest --tests 'com.nexio.tv.ui.screens.player.PlayerStartupSelectionPolicyTest'
```

Expected: 41 tests pass (38 existing + 3 new).

- [ ] **Step 6: Commit**

```bash
git add \
  app/src/main/java/com/nexio/tv/ui/screens/player/PlayerStartupSelectionPolicy.kt \
  app/src/test/java/com/nexio/tv/ui/screens/player/PlayerStartupSelectionPolicyTest.kt

git commit -m "$(cat <<'EOF'
fix(player): use word-boundary regex for SDH "cc" marker

The previous SDH_SUBTITLE_MARKERS entry " cc " (space-cc-space) only
matched tracks like "English [CC]" or "[CC] English" via the surrounding
"[cc]" marker; tracks named "English-CC", "CC English", or "English CC"
went through as normal-rank because none of the literal substrings
matched. Replaces with a \bcc\b word-boundary regex that covers all
delimiter variants while still excluding "soccer", "accordion", and any
other word that happens to contain the substring "cc".

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Final test sweep

**Files:** none changed.

- [ ] **Step 1: Run all subtitle-related tests**

```
./gradlew :app:testUniversalDebugUnitTest \
  --tests 'com.nexio.tv.ui.screens.player.PlayerStartupSelectionPolicyTest' \
  --tests 'com.nexio.tv.data.repository.SubtitleTranslationServicePromptTest' \
  --tests 'com.nexio.tv.data.repository.SubtitleTranslationServiceProviderTest' \
  --tests 'com.nexio.tv.data.repository.OpenSubtitlesSourceImplTest'
```

Expected: all green. PlayerStartupSelectionPolicyTest = 41 (38 + 3 new), SubtitleTranslationServicePromptTest = 6, SubtitleTranslationServiceProviderTest = 16, OpenSubtitlesSourceImplTest = 4. 67 total.

- [ ] **Step 2: Verify the full debug build still compiles**

```
./gradlew :app:compileUniversalDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

---

## Self-Review

**Spec coverage:**
- Item 1 (Promote T7/T8 to full instance↔companion delegation, eliminate silent test/prod drift) → Task 1 introduces shared file-level `rawSubRipSourceClause` / `rawAssSsaSourceClause` helpers so instance and companion both call the same function. ✓
- Item 2 (Align T6/T7/T8 auto-detect phrasing) → Task 2 appends "and translate to &lt;target&gt;" to T6's auto branch. ✓
- Item 3 (Hoist `bestByAccessibility` to a private file-level helper) → Task 3 introduces `bestSubtitleByAccessibility` and replaces all three call sites (two former local-helper sites + one inline `minByOrNull`). ✓
- Item 4 (Tighten `" cc "` marker) → Task 4 replaces with a `\bcc\b` regex check, backed by three new TDD tests covering dash-CC, CC-leading, and the negative "soccer" case. ✓

**Placeholder scan:** no TBD/TODO/handwave language. All step bodies contain concrete code or exact commands.

**Type/name consistency:**
- `rawSubRipSourceClause(targetLanguageName, sourceLanguageName): String` and `rawAssSsaSourceClause(targetLanguageName, sourceLanguageName): String` — consistent parameter order and types across both helpers and across instance/companion call sites.
- `bestSubtitleByAccessibility(subtitleTracks: List<TrackInfo>, candidates: List<Int>): Int?` — consistent across all three call sites.
- `SDH_CC_TOKEN_REGEX` named consistently with the existing `SDH_SUBTITLE_MARKERS` casing.
