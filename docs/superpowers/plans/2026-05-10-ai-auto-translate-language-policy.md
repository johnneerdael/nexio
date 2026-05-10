# AI Auto-Translate: Language Policy & Forced/SDH De-prioritization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make AI auto-translate work without a configured secondary language by relying on embedded subtitles (with secondary as an optional source-language hint), prefer normal dialogue tracks over forced/SDH tracks for both the normal and AI subtitle pickers, and tell the LLM to detect the source language explicitly when it's unknown.

**Architecture:** Two production files change. `PlayerStartupSelectionPolicy.kt` gets a small `subtitleAccessibilityRank` helper that's applied in two existing pickers, and the AI tier of `decideStartupSubtitleAutoSelection` drops a duplicate addon branch (tier 5 already covers the same outcome). `SubtitleTranslationService.kt` extends three system-prompt builders with a `sourceLanguageName` parameter and emits an explicit detect-or-translate-from instruction.

**Tech Stack:** Kotlin / JUnit 4. Tests run on the JVM (no Android instrumentation needed). Built via Gradle wrapper.

**Spec:** `docs/superpowers/specs/2026-05-10-ai-auto-translate-language-policy-design.md`

---

## File Structure

| File | Role | Status |
|---|---|---|
| `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerStartupSelectionPolicy.kt` | Startup picker logic. Adds `SDH_MARKERS`, `isSdhSubtitle`, `subtitleAccessibilityRank`. Modifies `findBestInternalSubtitleTrackIndexForStartup`, `breakPortugueseSubtitleTieForStartup`, `pickTranslatableInternalSubtitle`, `decideStartupSubtitleAutoSelection`. | Modify |
| `app/src/main/java/com/nexio/tv/data/repository/SubtitleTranslationService.kt` | Translation request orchestration. Extends `buildTranslationSystemPrompt`, `buildRawSubRipSystemPrompt`, `buildRawAssSsaSystemPrompt`. Updates 3 call sites. | Modify |
| `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerStartupSelectionPolicyTest.kt` | Picker unit tests. Adds 13 tests for accessibility ranking + no-secondary AI behavior. | Modify |
| `app/src/test/java/com/nexio/tv/data/repository/SubtitleTranslationServicePromptTest.kt` | Prompt-builder unit tests. New file with 6 tests (auto-detect + explicit-source for all three builders). | Create |

No new modules. No new public APIs. All helpers are `private` to `PlayerStartupSelectionPolicy.kt`.

**Build & test commands** (run from repo root):

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.ui.screens.player.PlayerStartupSelectionPolicyTest'
./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.data.repository.SubtitleTranslationServicePromptTest'
```

The full test class targets keep iteration fast. Use `--tests '*.testMethodName'` to run a single test if needed.

---

## Task 1: Add accessibility ranking helpers

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerStartupSelectionPolicy.kt` — append helpers near the bottom of the file (after `isLikelyOriginalLanguageTrack` at line 770).
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerStartupSelectionPolicyTest.kt`

These helpers are private file-scope functions. Tests are written against the public picker functions that consume them in later tasks. This task only validates the helper logic in isolation via a thin `internal` exposure for tests — to avoid that we instead add the helpers in the same task as their first consumer (Task 2). Skip this task as a standalone — proceed directly to Task 2 which introduces the helpers and the first consumer together.

> **Note:** Task 1 is intentionally absent as a separate task. The helpers are added in Task 2 alongside their first consumer.

---

## Task 2: Add accessibility helpers and apply them in the normal language picker

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerStartupSelectionPolicy.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerStartupSelectionPolicyTest.kt`

The normal-language picker is `findBestInternalSubtitleTrackIndexForStartup` (currently `subtitleTracks[candidateIndexes.first()]` after pt-br tiebreak). This task introduces the `subtitleAccessibilityRank` helper and applies it as the within-language tiebreaker, with pt-br tag preference still dominating accessibility.

- [ ] **Step 1: Write the failing tests**

Append to `PlayerStartupSelectionPolicyTest.kt` (after the last `@Test` method, before the closing `}` of the class):

```kotlin
    @Test
    fun `findBestInternal picks normal English over forced English`() {
        val tracks = listOf(
            TrackInfo(index = 0, name = "English (Forced)", language = "en", isForced = true),
            TrackInfo(index = 1, name = "English", language = "en", isForced = false)
        )

        val index = findBestInternalSubtitleTrackIndexForStartup(
            subtitleTracks = tracks,
            targets = listOf("en")
        )

        assertEquals(1, index)
    }

    @Test
    fun `findBestInternal picks normal English over SDH and forced`() {
        val tracks = listOf(
            TrackInfo(index = 0, name = "English (Forced)", language = "en", isForced = true),
            TrackInfo(index = 1, name = "English SDH", language = "en", isForced = false),
            TrackInfo(index = 2, name = "English", language = "en", isForced = false)
        )

        val index = findBestInternalSubtitleTrackIndexForStartup(
            subtitleTracks = tracks,
            targets = listOf("en")
        )

        assertEquals(2, index)
    }

    @Test
    fun `findBestInternal picks SDH over forced when no normal track exists`() {
        val tracks = listOf(
            TrackInfo(index = 0, name = "English (Forced)", language = "en", isForced = true),
            TrackInfo(index = 1, name = "English SDH", language = "en", isForced = false)
        )

        val index = findBestInternalSubtitleTrackIndexForStartup(
            subtitleTracks = tracks,
            targets = listOf("en")
        )

        assertEquals(1, index)
    }

    @Test
    fun `findBestInternal falls back to forced as last resort`() {
        val tracks = listOf(
            TrackInfo(index = 0, name = "English (Forced)", language = "en", isForced = true)
        )

        val index = findBestInternalSubtitleTrackIndexForStartup(
            subtitleTracks = tracks,
            targets = listOf("en")
        )

        assertEquals(0, index)
    }

    @Test
    fun `findBestInternal pt-br tag preference outranks accessibility`() {
        val tracks = listOf(
            TrackInfo(index = 0, name = "Portugues (PT)", language = "pt", isForced = false),
            TrackInfo(index = 1, name = "Portugues (BR) Forced", language = "pt", isForced = true)
        )

        val index = findBestInternalSubtitleTrackIndexForStartup(
            subtitleTracks = tracks,
            targets = listOf("pt-br")
        )

        assertEquals(1, index)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.ui.screens.player.PlayerStartupSelectionPolicyTest.findBestInternal*'`

Expected: 5 failures. The first three fail because the picker returns track 0 (track ordering). The fourth currently passes (last-resort behavior is already correct). The fifth fails because pt-br tiebreak picks the first matched track in list order.

- [ ] **Step 3: Add the accessibility helpers**

Open `PlayerStartupSelectionPolicy.kt`. Find the end of the file (after `isLikelyOriginalLanguageTrack` ending at line ~788). Append:

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

/**
 * Lower rank wins. Normal dialogue tracks (rank 0) are preferred over SDH
 * (rank 1) and forced (rank 2). Forced tracks contain only signs/inserts —
 * never the full dialogue — so they should only be picked when no other
 * same-language track exists. The explicit `preferredLanguage == "forced"`
 * branch in [decideStartupSubtitleAutoSelection] short-circuits before this
 * ranking is consulted, so users who actively choose forced still get it.
 */
private fun TrackInfo.subtitleAccessibilityRank(): Int = when {
    isForced -> 2
    isSdhSubtitle() -> 1
    else -> 0
}
```

- [ ] **Step 4: Apply ranking in `findBestInternalSubtitleTrackIndexForStartup`**

In `PlayerStartupSelectionPolicy.kt`, locate the existing function (around line 666). Replace the body. The current shape is:

```kotlin
internal fun findBestInternalSubtitleTrackIndexForStartup(
    subtitleTracks: List<TrackInfo>,
    targets: List<String>
): Int {
    for ((targetPosition, target) in targets.withIndex()) {
        if (target == SUBTITLE_LANGUAGE_FORCED) {
            val forcedIndex = subtitleTracks.indexOfFirst { it.isForced }
            if (forcedIndex >= 0) return forcedIndex
            if (targetPosition == 0) return -1
            continue
        }
        val normalizedTarget = PlayerSubtitleUtils.normalizeLanguageCode(target)
        val candidateIndexes = subtitleTracks.indices.filter { index ->
            PlayerSubtitleUtils.matchesLanguageCode(subtitleTracks[index].language, target)
        }
        if (candidateIndexes.isEmpty()) {
            if (normalizedTarget == "pt-br") {
                val brazilianFromGenericPt = findBrazilianPortugueseInGenericPtTracksForStartup(subtitleTracks)
                if (brazilianFromGenericPt >= 0) {
                    return brazilianFromGenericPt
                }
                if (targetPosition == 0) {
                    return -1
                }
            }
            continue
        }
        if (candidateIndexes.size == 1) return candidateIndexes.first()

        if (normalizedTarget == "pt" || normalizedTarget == "pt-br") {
            val tieBroken = breakPortugueseSubtitleTieForStartup(
                subtitleTracks = subtitleTracks,
                candidateIndexes = candidateIndexes,
                normalizedTarget = normalizedTarget
            )
            if (tieBroken >= 0) return tieBroken
        }
        return candidateIndexes.first()
    }
    return -1
}
```

Replace the final two lines of the loop body (`if (normalizedTarget == "pt" ...) { ... } / return candidateIndexes.first()`) with:

```kotlin
        if (normalizedTarget == "pt" || normalizedTarget == "pt-br") {
            val tieBroken = breakPortugueseSubtitleTieForStartup(
                subtitleTracks = subtitleTracks,
                candidateIndexes = candidateIndexes,
                normalizedTarget = normalizedTarget
            )
            if (tieBroken >= 0) return tieBroken
        }
        return candidateIndexes.minByOrNull { subtitleTracks[it].subtitleAccessibilityRank() }
            ?: candidateIndexes.first()
```

- [ ] **Step 5: Apply ranking inside `breakPortugueseSubtitleTieForStartup`**

In the same file, locate `breakPortugueseSubtitleTieForStartup` (around line 725). Replace each `firstOrNull { ... }` with `filter { ... }.minByOrNull { subtitleTracks[it].subtitleAccessibilityRank() }`. The current body:

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
        candidateIndexes.firstOrNull { hasBrazilianTags(it) && !hasEuropeanTags(it) }
            ?: candidateIndexes.firstOrNull { hasBrazilianTags(it) }
            ?: candidateIndexes.first()
    } else {
        candidateIndexes.firstOrNull { hasEuropeanTags(it) && !hasBrazilianTags(it) }
            ?: candidateIndexes.firstOrNull { hasEuropeanTags(it) }
            ?: candidateIndexes.firstOrNull { !hasBrazilianTags(it) }
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

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.ui.screens.player.PlayerStartupSelectionPolicyTest'`

Expected: all tests pass, including the 5 new ones added in Step 1 and all existing audio/subtitle tests.

- [ ] **Step 7: Commit**

```bash
git add \
  app/src/main/java/com/nexio/tv/ui/screens/player/PlayerStartupSelectionPolicy.kt \
  app/src/test/java/com/nexio/tv/ui/screens/player/PlayerStartupSelectionPolicyTest.kt

git commit -m "$(cat <<'EOF'
feat(player): de-prioritize forced/SDH subs in normal language picker

Adds subtitleAccessibilityRank helper (Normal=0 > SDH=1 > Forced=2) and
applies it as the within-language tiebreaker in
findBestInternalSubtitleTrackIndexForStartup and the pt-br tie-breaker.
Forced subtitles only contain signs/inserts so should never be the
default pick when a normal-dialogue track exists in the same language;
the explicit preferredLanguage="forced" branch is unaffected.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Apply accessibility ranking in the AI source picker

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerStartupSelectionPolicy.kt:625-647` (`pickTranslatableInternalSubtitle`)
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerStartupSelectionPolicyTest.kt`

`pickTranslatableInternalSubtitle` selects the AI translation source from text-based embedded tracks. The ladder stays `secondary → English → any`; this task adds accessibility ranking inside each tier so a forced English track no longer beats a normal Polish track when secondary is unset.

- [ ] **Step 1: Write the failing tests**

Append to `PlayerStartupSelectionPolicyTest.kt`:

```kotlin
    @Test
    fun `pickTranslatableInternal picks normal English over forced English when secondary unset`() {
        val tracks = listOf(
            TrackInfo(index = 0, name = "English (Forced)", language = "en", isForced = true, mimeType = "text/vtt"),
            TrackInfo(index = 1, name = "English", language = "en", isForced = false, mimeType = "text/vtt")
        )

        val index = pickTranslatableInternalSubtitle(
            subtitleTracks = tracks,
            secondaryLanguage = null
        )

        assertEquals(1, index)
    }

    @Test
    fun `pickTranslatableInternal picks normal secondary over SDH secondary`() {
        val tracks = listOf(
            TrackInfo(index = 0, name = "Francais SDH", language = "fr", isForced = false, mimeType = "text/vtt"),
            TrackInfo(index = 1, name = "Francais", language = "fr", isForced = false, mimeType = "text/vtt"),
            TrackInfo(index = 2, name = "English", language = "en", isForced = false, mimeType = "text/vtt")
        )

        val index = pickTranslatableInternalSubtitle(
            subtitleTracks = tracks,
            secondaryLanguage = "fr"
        )

        assertEquals(1, index)
    }

    @Test
    fun `pickTranslatableInternal picks any-tier normal track over forced same-tier`() {
        val tracks = listOf(
            TrackInfo(index = 0, name = "Polish (Forced)", language = "pl", isForced = true, mimeType = "text/vtt"),
            TrackInfo(index = 1, name = "Polish", language = "pl", isForced = false, mimeType = "text/vtt")
        )

        val index = pickTranslatableInternalSubtitle(
            subtitleTracks = tracks,
            secondaryLanguage = null
        )

        assertEquals(1, index)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.ui.screens.player.PlayerStartupSelectionPolicyTest.pickTranslatableInternal*'`

Expected: 3 failures. Each currently returns track 0 (the forced/SDH track) because the picker uses `firstOrNull`.

- [ ] **Step 3: Update `pickTranslatableInternalSubtitle`**

In `PlayerStartupSelectionPolicy.kt`, locate `pickTranslatableInternalSubtitle` (around line 625). Replace the body. Current:

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
        val secondaryMatch = textTracks.firstOrNull { index ->
            PlayerSubtitleUtils.matchesLanguageCode(subtitleTracks[index].language, secondaryLanguage)
        }
        if (secondaryMatch != null) return secondaryMatch
    }
    val englishMatch = textTracks.firstOrNull { index ->
        PlayerSubtitleUtils.matchesLanguageCode(subtitleTracks[index].language, "en")
    }
    if (englishMatch != null) return englishMatch
    return textTracks.first()
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

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.ui.screens.player.PlayerStartupSelectionPolicyTest'`

Expected: all tests pass (the new 3 plus all earlier tests).

- [ ] **Step 5: Commit**

```bash
git add \
  app/src/main/java/com/nexio/tv/ui/screens/player/PlayerStartupSelectionPolicy.kt \
  app/src/test/java/com/nexio/tv/ui/screens/player/PlayerStartupSelectionPolicyTest.kt

git commit -m "$(cat <<'EOF'
feat(player): apply accessibility ranking in AI subtitle source picker

pickTranslatableInternalSubtitle now picks the most accessible (normal >
SDH > forced) text track within each ladder tier (secondary → English →
any). Previously a forced English track sitting at index 0 would win
over a normal Polish track at index 1, leaving AI translation with a
near-empty source.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Remove the duplicate AI-tier addon branch

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerStartupSelectionPolicy.kt:306-326` (`decideStartupSubtitleAutoSelection`)
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerStartupSelectionPolicyTest.kt`

The AI tier in `decideStartupSubtitleAutoSelection` has a `findAddon(normalizedSecondary)` branch that duplicates tier 5 (`addonSecondary`) — both return an `Addon` decision with `enableAiTranslation = true` whenever AI is configured. The duplicate also creates an asymmetric path where the AI tier could only return an addon if secondary was set, contributing to the "AI requires secondary" perception. This task removes the duplicate; tier 5 now serves the same case.

- [ ] **Step 1: Write the failing test**

Append to `PlayerStartupSelectionPolicyTest.kt`:

```kotlin
    @Test
    fun `aiTier addon branch removed - tier 5 serves english addon with ai flag`() {
        // No embedded text tracks; addon list contains an English subtitle.
        // Preferred is Dutch, secondary is English, AI is configured. The
        // outcome (Addon(en, ai=true)) is identical to the pre-change
        // behavior; this test pins the consolidation so future refactors
        // don't regress.
        val addonSubs = listOf(
            Subtitle(
                id = "en-addon",
                url = "file:///tmp/en.srt",
                lang = "en",
                addonName = "OpenSubtitles",
                addonLogo = null
            )
        )

        val decision = decideStartupSubtitleAutoSelection(
            subtitleTracks = emptyList(),
            addonSubtitles = addonSubs,
            preferredLanguage = "nl",
            secondaryLanguage = "en",
            hasScannedTextTracksOnce = true,
            playerReady = true,
            addonSubtitleDiscoveryPending = false,
            aiTranslationConfigured = true,
            startupPhase = true
        )

        assertTrue(decision is StartupSubtitleAutoSelectionDecision.Addon)
        decision as StartupSubtitleAutoSelectionDecision.Addon
        assertEquals("en-addon", decision.subtitle.id)
        assertEquals(true, decision.enableAiTranslation)
    }
```

- [ ] **Step 2: Run test to verify it currently passes (baseline)**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.ui.screens.player.PlayerStartupSelectionPolicyTest.aiTier*'`

Expected: PASS. The current code reaches the AI-tier addon branch and returns `Addon(en, ai=true)`. The test pins that outcome.

- [ ] **Step 3: Remove the AI-tier addon branch**

In `PlayerStartupSelectionPolicy.kt`, locate the AI tier inside `decideStartupSubtitleAutoSelection` (around line 306-326). Current:

```kotlin
    val aiTranslationAllowed =
        startupPhase && aiTranslationConfigured && normalizedPreferred != null
    if (aiTranslationAllowed) {
        val translatableInternalIndex = pickTranslatableInternalSubtitle(
            subtitleTracks = subtitleTracks,
            secondaryLanguage = normalizedSecondary
        )
        if (translatableInternalIndex >= 0) {
            return StartupSubtitleAutoSelectionDecision.Internal(
                index = translatableInternalIndex,
                enableAiTranslation = true
            )
        }
        val addonForTranslation = findAddon(normalizedSecondary)
        if (addonForTranslation != null) {
            return StartupSubtitleAutoSelectionDecision.Addon(
                subtitle = addonForTranslation,
                enableAiTranslation = true
            )
        }
    }
```

Replace with:

```kotlin
    // AI-translation tier (the "third primary tier"):
    // No primary-language subtitle exists anywhere, so use AI to bridge a
    // different-language embedded source into the user's primary language.
    // The translation source is chosen by pickTranslatableInternalSubtitle:
    //   1. Secondary language (when set) — user's source-language hint.
    //   2. English — highest-quality NMT corpus.
    //   3. Any other text-based embedded track.
    // We do NOT fall back to addon subtitles here. Addon fetching is keyed
    // on primary+secondary languages only, so any matching addon would be
    // covered by the existing untranslated-secondary tier below (tier 5)
    // with the same enableAiTranslation flag.
    val aiTranslationAllowed =
        startupPhase && aiTranslationConfigured && normalizedPreferred != null
    if (aiTranslationAllowed) {
        val translatableInternalIndex = pickTranslatableInternalSubtitle(
            subtitleTracks = subtitleTracks,
            secondaryLanguage = normalizedSecondary
        )
        if (translatableInternalIndex >= 0) {
            return StartupSubtitleAutoSelectionDecision.Internal(
                index = translatableInternalIndex,
                enableAiTranslation = true
            )
        }
    }
```

- [ ] **Step 4: Run test to verify the consolidated path still passes**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.ui.screens.player.PlayerStartupSelectionPolicyTest'`

Expected: all tests pass. The new test passes via tier 5 instead of the removed branch — outcome is identical.

- [ ] **Step 5: Commit**

```bash
git add \
  app/src/main/java/com/nexio/tv/ui/screens/player/PlayerStartupSelectionPolicy.kt \
  app/src/test/java/com/nexio/tv/ui/screens/player/PlayerStartupSelectionPolicyTest.kt

git commit -m "$(cat <<'EOF'
refactor(player): drop duplicate AI-tier addon branch in startup picker

The AI tier's findAddon(secondary) branch is structurally identical to
tier 5's addonSecondary path: both return Addon(sub, ai=true) when AI is
configured. Removing the duplicate makes the tier dependency-free of
secondary for the embedded-source path and clarifies that addon fetching
(primary+secondary keyed) is the real reason addons can't be an AI
source when secondary is unset.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Add no-secondary AI-tier coverage tests

**Files:**
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerStartupSelectionPolicyTest.kt`

The current code already supports the no-secondary AI flow correctly (the picker has always handled `secondaryLanguage = null`), but no test pins the behavior. This task adds regression tests so future picker changes can't silently re-introduce a secondary dependency.

- [ ] **Step 1: Write the tests**

Append to `PlayerStartupSelectionPolicyTest.kt`:

```kotlin
    @Test
    fun `aiTier picks English embedded when no secondary configured`() {
        val tracks = listOf(
            TrackInfo(index = 0, name = "French", language = "fr", mimeType = "text/vtt"),
            TrackInfo(index = 1, name = "English", language = "en", mimeType = "text/vtt")
        )

        val decision = decideStartupSubtitleAutoSelection(
            subtitleTracks = tracks,
            addonSubtitles = emptyList(),
            preferredLanguage = "nl",
            secondaryLanguage = null,
            hasScannedTextTracksOnce = true,
            playerReady = true,
            addonSubtitleDiscoveryPending = false,
            aiTranslationConfigured = true,
            startupPhase = true
        )

        assertEquals(
            StartupSubtitleAutoSelectionDecision.Internal(index = 1, enableAiTranslation = true),
            decision
        )
    }

    @Test
    fun `aiTier picks any embedded when no English no secondary`() {
        val tracks = listOf(
            TrackInfo(index = 0, name = "Polish", language = "pl", mimeType = "text/vtt")
        )

        val decision = decideStartupSubtitleAutoSelection(
            subtitleTracks = tracks,
            addonSubtitles = emptyList(),
            preferredLanguage = "nl",
            secondaryLanguage = null,
            hasScannedTextTracksOnce = true,
            playerReady = true,
            addonSubtitleDiscoveryPending = false,
            aiTranslationConfigured = true,
            startupPhase = true
        )

        assertEquals(
            StartupSubtitleAutoSelectionDecision.Internal(index = 0, enableAiTranslation = true),
            decision
        )
    }

    @Test
    fun `aiTier returns None when only bitmap embedded subtitles exist`() {
        val tracks = listOf(
            TrackInfo(index = 0, name = "English (PGS)", language = "en", mimeType = "application/pgs")
        )

        val decision = decideStartupSubtitleAutoSelection(
            subtitleTracks = tracks,
            addonSubtitles = emptyList(),
            preferredLanguage = "nl",
            secondaryLanguage = null,
            hasScannedTextTracksOnce = true,
            playerReady = true,
            addonSubtitleDiscoveryPending = false,
            aiTranslationConfigured = true,
            startupPhase = true
        )

        assertEquals(StartupSubtitleAutoSelectionDecision.None, decision)
    }

    @Test
    fun `aiTier secondary hint wins over English when both available`() {
        val tracks = listOf(
            TrackInfo(index = 0, name = "English", language = "en", mimeType = "text/vtt"),
            TrackInfo(index = 1, name = "French", language = "fr", mimeType = "text/vtt"),
            TrackInfo(index = 2, name = "German", language = "de", mimeType = "text/vtt")
        )

        val decision = decideStartupSubtitleAutoSelection(
            subtitleTracks = tracks,
            addonSubtitles = emptyList(),
            preferredLanguage = "nl",
            secondaryLanguage = "fr",
            hasScannedTextTracksOnce = true,
            playerReady = true,
            addonSubtitleDiscoveryPending = false,
            aiTranslationConfigured = true,
            startupPhase = true
        )

        assertEquals(
            StartupSubtitleAutoSelectionDecision.Internal(index = 1, enableAiTranslation = true),
            decision
        )
    }
```

- [ ] **Step 2: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.ui.screens.player.PlayerStartupSelectionPolicyTest.aiTier*'`

Expected: PASS for all four. These are pure regression coverage on existing behavior.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/nexio/tv/ui/screens/player/PlayerStartupSelectionPolicyTest.kt

git commit -m "$(cat <<'EOF'
test(player): pin AI-tier behavior when no secondary language is set

Adds regression tests for the no-secondary AI flow: English embedded
preferred over other foreign languages, any text-based track when no
English exists, None when only bitmap subs exist, and secondary
preference winning over English when explicitly set as a hint. Pins the
tier so future picker refactors can't silently re-introduce a secondary
dependency.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Extend `buildTranslationSystemPrompt` with sourceLanguageName

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/SubtitleTranslationService.kt:991-1005` (definition) and `:1122` (call site)
- Create: `app/src/test/java/com/nexio/tv/data/repository/SubtitleTranslationServicePromptTest.kt`

`buildTranslationSystemPrompt` currently emits target language only. When the source is `und`/blank, the LLM has to infer detection from the JSON `targetLanguageCode/Name` fields with no explicit instruction. This task adds a `sourceLanguageName` parameter and emits a detect-or-translate-from sentence.

`displaySourceLanguage` (line 2006-2012) already returns `"auto"` for blank/`und`/`unknown`, so call sites that pass through `displaySourceLanguage` get the auto-detect branch automatically.

- [ ] **Step 1: Create the test file with failing tests**

Create `app/src/test/java/com/nexio/tv/data/repository/SubtitleTranslationServicePromptTest.kt`:

```kotlin
package com.nexio.tv.data.repository

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the system-prompt builders include explicit source-language
 * instructions. Source-language plumbing already exists end-to-end; the
 * change here is making the prompt body itself describe the translation
 * direction so the LLM behaves consistently for `lang=und` embedded subs.
 *
 * The prompt builders are private to [SubtitleTranslationService]. Until a
 * dedicated test seam exists, these tests exercise them indirectly via
 * package-internal accessors added alongside the implementation. See the
 * accessor helpers exposed in the same file under `internal fun` visibility.
 */
class SubtitleTranslationServicePromptTest {

    @Test
    fun `buildTranslationSystemPrompt instructs auto-detection when source is auto`() {
        val prompt = SubtitleTranslationService.buildTranslationSystemPromptForTest(
            targetLanguageCode = "nl",
            targetLanguageName = "Dutch",
            sourceLanguageName = "auto"
        )

        val lower = prompt.lowercase()
        assertTrue("expected detect/automatically in: $prompt",
            lower.contains("detect") && lower.contains("automatically")
        )
        assertTrue("expected target name in: $prompt", prompt.contains("Dutch"))
    }

    @Test
    fun `buildTranslationSystemPrompt names source explicitly when known`() {
        val prompt = SubtitleTranslationService.buildTranslationSystemPromptForTest(
            targetLanguageCode = "nl",
            targetLanguageName = "Dutch",
            sourceLanguageName = "Polish"
        )

        assertTrue("expected source name in: $prompt", prompt.contains("Polish"))
        assertTrue("expected target name in: $prompt", prompt.contains("Dutch"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails (compile error)**

Run: `./gradlew :app:compileDebugUnitTestKotlin`

Expected: FAIL — `buildTranslationSystemPromptForTest` is unresolved. The accessor doesn't exist yet.

- [ ] **Step 3: Update `buildTranslationSystemPrompt` signature and body**

Open `SubtitleTranslationService.kt`. Locate `buildTranslationSystemPrompt` (around line 991). Replace the function with:

```kotlin
    private fun buildTranslationSystemPrompt(
        targetLanguageCode: String,
        targetLanguageName: String,
        sourceLanguageName: String
    ): String {
        return buildString {
            append("You are an expert subtitle localization specialist. ")
            append("Translate only the text fields in the provided JSON items into ")
            append(targetLanguageName)
            append(" (")
            append(targetLanguageCode)
            append("). ")
            if (sourceLanguageName.equals("auto", ignoreCase = true)) {
                append("The source language is unknown — detect it automatically from the cue text. ")
            } else {
                append("Translate from ")
                append(sourceLanguageName)
                append(". ")
            }
            append("Return JSON only. Keep the same ids. ")
            append("Preserve subtitle brevity, punctuation, markup, speaker labels, and internal line breaks when possible.")
        }
    }
```

- [ ] **Step 4: Update the single call site**

In `SubtitleTranslationService.kt`, locate `executeTranslationRequest` (around line 1110). The current default-systemPrompt expression is at line 1121-1122:

```kotlin
        val systemPrompt = systemPromptOverride
            ?: buildTranslationSystemPrompt(targetLanguageCode, targetLanguageName)
```

Replace with:

```kotlin
        val systemPrompt = systemPromptOverride
            ?: buildTranslationSystemPrompt(targetLanguageCode, targetLanguageName, sourceLanguageName)
```

(`sourceLanguageName` is already a parameter of `executeTranslationRequest` at line 1114.)

- [ ] **Step 5: Add the test accessor**

Still in `SubtitleTranslationService.kt`, find the closing `}` of the class. Just before the trailing `typealias` declarations (line 2024 area), add a `companion object` block — or add to the existing one if present. Search for `companion object` first:

```bash
grep -n "companion object" app/src/main/java/com/nexio/tv/data/repository/SubtitleTranslationService.kt
```

If a `companion object` already exists, add the accessor inside it. Otherwise, add this just before the class's closing `}` (line 2022):

```kotlin
    companion object {
        @androidx.annotation.VisibleForTesting
        internal fun buildTranslationSystemPromptForTest(
            targetLanguageCode: String,
            targetLanguageName: String,
            sourceLanguageName: String
        ): String {
            // Mirror of buildTranslationSystemPrompt — kept in companion so
            // tests can exercise the prompt body without instantiating the
            // full DI graph (which requires Context, OkHttp, Gson, etc.).
            return buildString {
                append("You are an expert subtitle localization specialist. ")
                append("Translate only the text fields in the provided JSON items into ")
                append(targetLanguageName)
                append(" (")
                append(targetLanguageCode)
                append("). ")
                if (sourceLanguageName.equals("auto", ignoreCase = true)) {
                    append("The source language is unknown — detect it automatically from the cue text. ")
                } else {
                    append("Translate from ")
                    append(sourceLanguageName)
                    append(". ")
                }
                append("Return JSON only. Keep the same ids. ")
                append("Preserve subtitle brevity, punctuation, markup, speaker labels, and internal line breaks when possible.")
            }
        }
    }
```

If a `companion object` already exists, add only the `buildTranslationSystemPromptForTest` function inside it (do NOT duplicate `companion object`).

- [ ] **Step 6: Replace the duplicated body with a delegate**

DRY check: Step 5 mirrors the body of `buildTranslationSystemPrompt`. Refactor so the instance method delegates to the companion accessor. In `SubtitleTranslationService.kt`, replace the `private fun buildTranslationSystemPrompt(...)` body with:

```kotlin
    private fun buildTranslationSystemPrompt(
        targetLanguageCode: String,
        targetLanguageName: String,
        sourceLanguageName: String
    ): String = buildTranslationSystemPromptForTest(
        targetLanguageCode = targetLanguageCode,
        targetLanguageName = targetLanguageName,
        sourceLanguageName = sourceLanguageName
    )
```

The instance method now forwards into the companion. The companion accessor is the single source of truth.

- [ ] **Step 7: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.data.repository.SubtitleTranslationServicePromptTest'`

Expected: 2 passes.

Also run the existing translation tests to ensure no signature-change regressions:

Run: `./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.data.repository.SubtitleTranslationServiceProviderTest'`

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add \
  app/src/main/java/com/nexio/tv/data/repository/SubtitleTranslationService.kt \
  app/src/test/java/com/nexio/tv/data/repository/SubtitleTranslationServicePromptTest.kt

git commit -m "$(cat <<'EOF'
feat(translation): describe source language explicitly in JSON system prompt

buildTranslationSystemPrompt now accepts sourceLanguageName and emits
either an auto-detect instruction (when 'und'/blank reaches
displaySourceLanguage and returns 'auto') or an explicit
'Translate from X' instruction. Helps the LLM behave consistently when
embedded subtitles arrive with lang=und.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: Extend `buildRawSubRipSystemPrompt` with sourceLanguageName

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/SubtitleTranslationService.kt:1721-...` (definition) and `:817` (call site)
- Modify: `app/src/test/java/com/nexio/tv/data/repository/SubtitleTranslationServicePromptTest.kt`

Mirrors Task 6 for the raw-SubRip prompt path.

- [ ] **Step 1: Append failing tests**

Append to `SubtitleTranslationServicePromptTest.kt`:

```kotlin
    @Test
    fun `buildRawSubRipSystemPrompt instructs auto-detection when source is auto`() {
        val prompt = SubtitleTranslationService.buildRawSubRipSystemPromptForTest(
            targetLanguageName = "Dutch",
            sourceLanguageName = "auto"
        )

        val lower = prompt.lowercase()
        assertTrue("expected detect/automatically in: $prompt",
            lower.contains("detect") && lower.contains("automatically")
        )
        assertTrue("expected target name in: $prompt", prompt.contains("Dutch"))
    }

    @Test
    fun `buildRawSubRipSystemPrompt names source explicitly when known`() {
        val prompt = SubtitleTranslationService.buildRawSubRipSystemPromptForTest(
            targetLanguageName = "Dutch",
            sourceLanguageName = "Polish"
        )

        assertTrue("expected source name in: $prompt", prompt.contains("Polish"))
        assertTrue("expected target name in: $prompt", prompt.contains("Dutch"))
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:compileDebugUnitTestKotlin`

Expected: FAIL — `buildRawSubRipSystemPromptForTest` unresolved.

- [ ] **Step 3: Update `buildRawSubRipSystemPrompt`**

In `SubtitleTranslationService.kt`, locate `buildRawSubRipSystemPrompt` (around line 1721). It currently takes only `targetLanguageName`. Change the signature and prepend a source-language clause to the existing prompt body. Replace the function with:

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

            $sourceClause

            Your task is to translate raw SRT subtitle content into $targetLanguageName while preserving valid SRT format exactly.
        """.trimIndent() + "\n\n" + buildRawSubRipSystemPromptBody()
    }

    private fun buildRawSubRipSystemPromptBody(): String {
        return """
            OUTPUT RULE
            Return only the translated SRT content.
            Do not return explanations, comments, summaries, Markdown, code fences, JSON, warnings, or notes.
        """.trimIndent()
        // NOTE: The full prompt body continues with the existing rules.
        // See "Step 3a" below for the verbatim body to paste in place of
        // this stub — the body is extracted as-is from the current
        // buildRawSubRipSystemPrompt implementation.
    }
```

- [ ] **Step 3a: Move the existing prompt body into `buildRawSubRipSystemPromptBody`**

Read the current `buildRawSubRipSystemPrompt` body (`app/src/main/java/com/nexio/tv/data/repository/SubtitleTranslationService.kt` lines 1721 onward — the full triple-quoted block under `OUTPUT RULE`). Verbatim copy it into `buildRawSubRipSystemPromptBody`, replacing the stubbed three-line `OUTPUT RULE...` placeholder above. Do not edit any rule text — preserve all whitespace, punctuation, and bullet structure exactly.

The replacement strategy: delete the stub body in `buildRawSubRipSystemPromptBody` and substitute the original prompt's content from line 1727 (after the original `Your task is to translate...` sentence) to the original closing `""".trimIndent()`. The new top-of-prompt section (`You are SRT_TRANSLATION_ENGINE.` + `$sourceClause` + `Your task...`) is provided by the wrapping `buildRawSubRipSystemPrompt` function.

After the move, `buildRawSubRipSystemPromptBody()` returns everything the original prompt had **after** the `Your task is to translate...` sentence.

- [ ] **Step 4: Update the call site**

In `SubtitleTranslationService.kt`, find line 817:

```kotlin
            systemPrompt = buildRawSubRipSystemPrompt(targetLanguageName),
```

Replace with:

```kotlin
            systemPrompt = buildRawSubRipSystemPrompt(targetLanguageName, sourceLanguageName),
```

`sourceLanguageName` is already a parameter of `requestRawSubRipBatch` (line 811).

- [ ] **Step 5: Add the test accessor**

In the same `companion object` you created in Task 6, add:

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

This accessor only emits the new source-language clause section — that's all the tests assert against. The full prompt body is already exercised by `SubtitleTranslationServiceProviderTest`.

- [ ] **Step 6: Run tests**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.data.repository.SubtitleTranslationServicePromptTest'`

Expected: 4 passes (2 existing from Task 6 + 2 new).

Run: `./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.data.repository.SubtitleTranslationServiceProviderTest'`

Expected: PASS — the body of the prompt is unchanged, only the head-section now mentions the source language.

- [ ] **Step 7: Commit**

```bash
git add \
  app/src/main/java/com/nexio/tv/data/repository/SubtitleTranslationService.kt \
  app/src/test/java/com/nexio/tv/data/repository/SubtitleTranslationServicePromptTest.kt

git commit -m "$(cat <<'EOF'
feat(translation): describe source language in raw SubRip system prompt

Mirrors the JSON-prompt change (Task 6) for the raw-SRT path. Splits the
existing prompt body into a fixed tail and prepends a source-language
clause: auto-detect when source is unknown, explicit 'translate from X'
when known. Tested via a companion-object accessor that exercises only
the new head section; the rule-heavy body is unchanged and remains
covered by existing provider tests.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: Extend `buildRawAssSsaSystemPrompt` with sourceLanguageName

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/SubtitleTranslationService.kt:1626-...` (definition) and `:475-477` (call site)
- Modify: `app/src/test/java/com/nexio/tv/data/repository/SubtitleTranslationServicePromptTest.kt`

Mirrors Task 7 for the raw-ASS/SSA prompt path. Same head-section split pattern.

- [ ] **Step 1: Append failing tests**

Append to `SubtitleTranslationServicePromptTest.kt`:

```kotlin
    @Test
    fun `buildRawAssSsaSystemPrompt instructs auto-detection when source is auto`() {
        val prompt = SubtitleTranslationService.buildRawAssSsaSystemPromptForTest(
            targetLanguageName = "Dutch",
            sourceLanguageName = "auto"
        )

        val lower = prompt.lowercase()
        assertTrue("expected detect/automatically in: $prompt",
            lower.contains("detect") && lower.contains("automatically")
        )
        assertTrue("expected target name in: $prompt", prompt.contains("Dutch"))
    }

    @Test
    fun `buildRawAssSsaSystemPrompt names source explicitly when known`() {
        val prompt = SubtitleTranslationService.buildRawAssSsaSystemPromptForTest(
            targetLanguageName = "Dutch",
            sourceLanguageName = "Polish"
        )

        assertTrue("expected source name in: $prompt", prompt.contains("Polish"))
        assertTrue("expected target name in: $prompt", prompt.contains("Dutch"))
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:compileDebugUnitTestKotlin`

Expected: FAIL — `buildRawAssSsaSystemPromptForTest` unresolved.

- [ ] **Step 3: Update `buildRawAssSsaSystemPrompt`**

In `SubtitleTranslationService.kt`, locate `buildRawAssSsaSystemPrompt` (around line 1626). Change the signature to add `sourceLanguageName: String` and prepend a source-language clause. Replace the opening of the function:

Current:

```kotlin
    private fun buildRawAssSsaSystemPrompt(targetLanguageName: String): String {
        return """
            You are ASS_SSA_SUBTITLE_TRANSLATOR.

            target_language = $targetLanguageName

            TASK
            Translate only visible natural-language subtitle text into target_language. Preserve all ASS/SSA syntax byte-exact. Output only the translated result in the same container/shape as the input. No explanations, notes, Markdown, or extra text.
            ...
```

Replace with:

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

            target_language = $targetLanguageName
            $sourceClause

            TASK
            Translate only visible natural-language subtitle text into target_language. Preserve all ASS/SSA syntax byte-exact. Output only the translated result in the same container/shape as the input. No explanations, notes, Markdown, or extra text.
            ...
```

Keep the rest of the prompt body verbatim (everything from `INPUT` onward through the closing `""".trimIndent()`). Only the function signature and the head section above change.

- [ ] **Step 4: Update the call site**

In `SubtitleTranslationService.kt`, find lines 475-477:

```kotlin
                systemPrompt = buildRawAssSsaSystemPrompt(
                    targetLanguageName = displayLanguage(normalizedTarget)
                ),
```

Replace with:

```kotlin
                systemPrompt = buildRawAssSsaSystemPrompt(
                    targetLanguageName = displayLanguage(normalizedTarget),
                    sourceLanguageName = displaySourceLanguage(sourceLanguageCode)
                ),
```

`sourceLanguageCode` is already in scope (it's the parameter of the enclosing function — verify by reading the function around line 459).

- [ ] **Step 5: Add the test accessor**

In the `companion object` (extended in Tasks 6-7), add:

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

- [ ] **Step 6: Run tests**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.data.repository.SubtitleTranslationServicePromptTest'`

Expected: 6 passes (2 from Task 6 + 2 from Task 7 + 2 new).

Run: `./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.data.repository.SubtitleTranslationServiceProviderTest'`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add \
  app/src/main/java/com/nexio/tv/data/repository/SubtitleTranslationService.kt \
  app/src/test/java/com/nexio/tv/data/repository/SubtitleTranslationServicePromptTest.kt

git commit -m "$(cat <<'EOF'
feat(translation): describe source language in raw ASS/SSA system prompt

Mirrors the SRT-prompt change (Task 7) for the ASS/SSA path. Adds a
sourceLanguageName parameter and emits a source_language line in the
prompt header. The protected-syntax ruleset that follows is unchanged.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 9: Final test sweep

**Files:** none changed.

- [ ] **Step 1: Run the full subtitle-related test surface**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.nexio.tv.ui.screens.player.PlayerStartupSelectionPolicyTest' \
  --tests 'com.nexio.tv.ui.screens.player.PlayerSubtitleUtilsTest' \
  --tests 'com.nexio.tv.data.repository.SubtitleTranslationServicePromptTest' \
  --tests 'com.nexio.tv.data.repository.SubtitleTranslationServiceProviderTest' \
  --tests 'com.nexio.tv.data.repository.OpenSubtitlesSourceImplTest'
```

Expected: all green.

- [ ] **Step 2: Verify the full debug build compiles**

Run: `./gradlew :app:compileDebugKotlin`

Expected: `BUILD SUCCESSFUL`.

---

## Task 10: On-device verification

**Files:** none. This is a manual playback checklist run on a Fire TV / Android TV target.

- [ ] **Setup:** install the freshly built debug APK and sign in. In Settings → Playback, set primary subtitle language to **Dutch (nl)** and clear the secondary language. Configure the AI translation provider (Gemini / OpenAI / etc.) with a working API key.

- [ ] **Step 1: English embedded subtitles → AI translate**

Play an MKV that ships with embedded English text subtitles only.

Expected: Dutch subtitles render in the player. The status overlay briefly shows "AI translating…" then disappears. Confirm via Logcat `Nexio.MetaRoute` that `pickTranslatableInternalSubtitle` returned the English track index.

- [ ] **Step 2: `lang=und` Polish embedded → AI auto-detect**

Play an MKV whose embedded text track has `language=und` but is actually Polish.

Expected: Polish dialogue appears translated to Dutch. In the provider request log (filter `Nexio.SubtitleTranslation`), confirm the system prompt contains the substring `detect it automatically`.

- [ ] **Step 3: Bitmap-only embedded → no subs, no error toast**

Play a Blu-ray remux whose only embedded subtitles are PGS bitmap subs.

Expected: no subtitles render. No error toast appears (the AI tier returns `None` cleanly). Manually selecting the PGS track from the subtitle picker still selects it as a bitmap track (bypassing the AI tier).

- [ ] **Step 4: Forced/Normal English split**

Play an MKV whose subtitle track list is `[Forced (EN), English]` in that order, with the user's primary set to English.

Expected: dialogue subtitles are visible from the first cue (the `English` track is picked, not `Forced (EN)`).

- [ ] **Step 5: Forced/SDH/Normal English triplet**

Play an MKV whose subtitle track list is `[Forced (EN), English SDH, English]`, primary = English.

Expected: dialogue subtitles render via the plain `English` track.

- [ ] **Step 6: Only forced track exists**

Play an MKV whose only English subtitle is `Forced (EN)`, primary = English.

Expected: forced track is selected (last-resort behavior). Dialogue is *not* covered — that's by design; signs/inserts only.

- [ ] **Step 7: Explicit `forced` preference still honored**

In Settings, change primary subtitle language to **Forced**. Play an MKV with `[Forced (EN), English]`.

Expected: `Forced (EN)` is selected (the explicit-forced branch is unaffected by the new ranking).

- [ ] **Step 8: Re-run the heap-pressure smoke**

Open the home screen, scroll the rails for ~30 seconds, then drop into a movie. Watch for `adb logcat | grep "Background concurrent"` GC frequency. The change is logic-only (no new allocations on the hot path), so GC cadence should be indistinguishable from `main`. Capture a heap dump with the `analysing-heap-dumps` skill if anything looks off.

- [ ] **Step 9: Confirm completion**

Document the verification results (pass/fail per step) in the PR description before requesting review.

---

## Self-Review (already performed by author)

**Spec coverage check:**
- Spec §1 (startup auto-pick consolidation) → Tasks 4 + 5 ✓
- Spec §2 (translation prompt extension) → Tasks 6, 7, 8 ✓
- Spec §3 (forced/SDH de-prioritization) → Tasks 2, 3 ✓
- Spec §4 (untouched) → enforced by absence; no task touches `OpenSubtitlesSourceImpl`, `enableAiSubtitles`, etc.
- Spec testing matrix tests 1-19 → all mapped (1-4 → Task 5; 5 → Task 4; 6-13 → Tasks 2-3; 14-19 → Tasks 6-8) ✓
- Spec on-device matrix → Task 10 ✓

**Placeholder scan:** No TBD/TODO/"appropriate"/"add tests for above". All code blocks are concrete. Step 3a in Task 7 is the one place that asks the engineer to copy-paste from the existing source — that's deliberate (the prompt body is ~30 lines of rule text) and the task gives a precise byte-range citation.

**Type/name consistency:**
- `subtitleAccessibilityRank()` used identically in Tasks 2 and 3.
- `isSdhSubtitle()` used by `subtitleAccessibilityRank()` only.
- `SDH_SUBTITLE_MARKERS` named consistently.
- `buildTranslationSystemPromptForTest`, `buildRawSubRipSystemPromptForTest`, `buildRawAssSsaSystemPromptForTest` accessor naming is consistent.
- `sourceLanguageName` parameter name matches across all three prompt builders.
