# Original Language Field Authority Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop the user's UI locale from leaking into the player's audio-track picker and the stream resolver's deterministic-autoplay filter. Establish the show's *production language* as a first-class field plumbed through the metadata router, distinct from the UI-locale `selectedLanguage` field.

**Architecture:** Add `ResolvedField.ORIGINAL_LANGUAGE` alongside the existing `LANGUAGE`/`ORIGINAL_COUNTRY` enum slots and route TVDB/TMDB/Kitsu producers into it via the existing `MetadataCandidate` field-ownership pattern. Add `originalLanguage: String?` as a parallel field on `Meta`, `MetaPreview`, and `DetailAdvancedMetadata`; flip the player nav arg, stream resolver, and detail-screen badge to read the new field. Drop the silent `?: localization.selectedLanguage` fallback at `MetaDetailsViewModel.kt:1550/1617/3398` so unmapped paths fail loudly (null) instead of silently substituting UI locale.

**Tech Stack:** Kotlin · Hilt · Coroutines/Flow · Retrofit/Moshi · JUnit4 · Mockk

**Spec source of truth:** `docs/superpowers/notes/2026-05-10-original-language-audio-track-bug.md` — root-cause dossier with per-provider audit, smoking-gun device log, and architecture options.

**Architectural alignment:** Mirrors the rank-aware first-class-field pattern from:
- `docs/superpowers/plans/2026-05-09-resolved-display-authority.md` — separates display-source ranks; this plan separates language *concepts* (production vs UI-locale) the same way.
- `docs/superpowers/plans/2026-05-09-resolved-display-ui-consumption-migration.md` — establishes the "introduce first-class field, then migrate consumers off the legacy alias" two-pass pattern. Phase C+D below follow that shape exactly.

**Out of scope:**
- Kitsu drama / manga production-language inference (no API field, no sound default; left null after Phase B and the player's naming-convention fallback handles it).
- Refactoring `StreamPlaybackInfo.originalLanguage` and downstream `StreamScreenViewModel` consumers — they already use the right name and just inherit the corrected nav arg.
- Replacing `Meta.language` with a typed UI-locale field; deprecation only in this plan, deletion deferred.

**Non-goals (must not regress):**
- `findOriginalTrackFallbackIndex` naming-convention fallback in the player (`PlayerRuntimeControllerTracks.kt:556`) — keep behavior unchanged, just feed it correct input.
- `findBestStartupAudioTrackIndex` scoring (`PlayerStartupSelectionPolicy.kt:20`) — no signature change.
- Existing `metadata.localization_plan` log event format.

---

## File Structure

### New files

| File | Responsibility |
|---|---|
| `app/src/test/java/com/nexio/tv/data/integration/metadata/MetadataAdapterCandidatesLanguageTest.kt` | Unit tests proving `TvMetadataEnrichment.toMetadataCandidate` and `TvdbSeriesExtendedRecord.toMetadataCandidate` emit `ORIGINAL_LANGUAGE` |
| `app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModelLanguageMappingTest.kt` | Unit tests proving the silent UI-locale fallback is dead and `Meta.originalLanguage` is populated from `advanced.originalLanguage` only |
| `app/src/test/java/com/nexio/tv/ui/navigation/PlayerNavOriginalLanguageTest.kt` | Unit tests proving nav-arg `originalLanguage` is sourced from `meta.originalLanguage`, never from `meta.language` |
| `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerStartupSelectionPolicyOriginalLanguageRegressionTest.kt` | Regression test: Citadel-shape input `[pl, en]` with `originalLanguage="eng"` picks English |
| `app/src/test/java/com/nexio/tv/data/integration/tvdb/TvdbMetadataServiceOriginalLanguageTest.kt` | Unit test proving `TvdbMetadataService` propagates TVDB `originalLanguage` end-to-end |

### Modified files

| File | Change |
|---|---|
| `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataModels.kt` | Add `ORIGINAL_LANGUAGE` to `ResolvedField` enum; add `originalLanguage: String?` field to `ResolvedMetadataDocument` |
| `app/src/main/java/com/nexio/tv/core/metadata/router/FieldResolver.kt:284-300` | Read `ORIGINAL_LANGUAGE` from fields map into `ResolvedMetadataDocument.originalLanguage` |
| `app/src/main/java/com/nexio/tv/data/integration/metadata/MetadataAdapterCandidates.kt:21-79,225-322` | Emit `ORIGINAL_LANGUAGE` from `TmdbEnrichment.toMetadataCandidate`, `TvMetadataEnrichment.toMetadataCandidate`, `TvdbSeriesExtendedRecord.toMetadataCandidate`, and `buildTmdbLocalizedCandidate` |
| `app/src/main/java/com/nexio/tv/core/tvdb/ProviderLocalizedMetadataResolver.kt:39-48` | Set `language` on canonical-route `TvMetadataEnrichment` from upstream document |
| `app/src/main/java/com/nexio/tv/domain/model/ResolvedDetailDisplayDocument.kt:56-67` | Add `originalLanguage: String?` to `DetailAdvancedMetadata` (parallel to existing `originalCountry`) |
| `app/src/main/java/com/nexio/tv/data/repository/MetadataDisplayRepository.kt:264-275` | Populate `originalLanguage = originalLanguage` on `DetailAdvancedMetadata` |
| `app/src/main/java/com/nexio/tv/domain/model/Meta.kt:34` | Add `originalLanguage: String? = null`; deprecate `language` for production-language consumers |
| `app/src/main/java/com/nexio/tv/domain/model/MetaPreview.kt:30` | Add `originalLanguage: String? = null`; deprecate `language` for production-language consumers |
| `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt:1545-1551, 1605-1629, 3375-3402` | Drop `?: localization.selectedLanguage` fallback; populate `Meta.originalLanguage` from `document.advanced.originalLanguage` |
| `app/src/main/java/com/nexio/tv/data/repository/TmdbDiscoveryService.kt:240-260` | Populate `MetaPreview.originalLanguage` from `result.originalLanguage`; stop populating `language` for production-language purposes |
| `app/src/main/java/com/nexio/tv/ui/navigation/NexioNavHost.kt:172, 958, 998` | Read `item.originalLanguage` for nav arg `originalLanguage` |
| `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsScreen.kt:668, 696, 726` | Source nav-arg `originalLanguage` from `meta.originalLanguage` |
| `app/src/main/java/com/nexio/tv/ui/screens/detail/HeroSection.kt:678` | Display `originalLanguage` first, fall back to `language` for cosmetic display only |

---

## Phase A — Stop the bleeding (Bug A: silent UI-locale leakage)

These three tasks are independent of all later work. They convert the user-visible failure mode from "wrong audio every playback" to "naming-convention fallback every playback", which matches the documented spec ("if unknown, use English"). Land Phase A first; ship to a beta channel if separate channels exist; then proceed to Phase B/C.

### Task A1: Add observability for the fallback firing

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt:1545-1551`

- [ ] **Step 1: Read current state**

Run: `grep -n "language = document.advanced.language" app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt`
Expected: three matches at lines `~1550`, `~1617`, `~3398`.

- [ ] **Step 2: Add a single warning log at the first fallback site**

Replace the line 1550 expression with a logging variant. The fallback semantics stay identical until Task A2 — this step only adds observability so the next on-device run produces evidence we can grep for.

```kotlin
// app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt
// Around line 1543-1551 — inside `if (settings.useDetails) { updated = updated.copy(...) }`
language = run {
    val production = document.advanced.language
    if (production == null) {
        Log.w(
            TAG,
            "META_LANG_FALLBACK: production language null for contentId=" +
                "${request.contentId} provider=${identity.canonicalProvider} " +
                "fellBackTo=${document.localization.selectedLanguage}"
        )
    }
    production ?: document.localization.selectedLanguage ?: updated.language
}
```

If `TAG` is not already in scope, add `private const val TAG = "MetaDetailsVM"` at the top of the file (or import the existing one — `grep -n "private const val TAG" app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt` to check).

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Install and observe one playback**

```bash
./gradlew :app:installDebug
adb -s 192.168.50.98 logcat -c
# Manually start playback of an English-original show on the device, with UI locale Dutch.
adb -s 192.168.50.98 logcat -d | grep "META_LANG_FALLBACK"
```

Expected: at least one `META_LANG_FALLBACK: production language null ... fellBackTo=nld` line. This confirms the fallback is the active path. Save the output to your task notes — it's the baseline for Task A4 verification.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt
git commit -m "chore: log when production language falls back to UI locale (Bug A observability)"
```

---

### Task A2: Drop the silent UI-locale fallback at all three sites

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt:1543-1551, 1605-1629, 3375-3402`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModelLanguageMappingTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModelLanguageMappingTest.kt`:

```kotlin
package com.nexio.tv.ui.screens.detail

import com.nexio.tv.domain.model.DetailAdvancedMetadata
import com.nexio.tv.domain.model.LocalizationDisplayState
import com.nexio.tv.domain.model.Meta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression tests for Bug A from the 2026-05-10 dossier:
 * the silent fallback to localization.selectedLanguage must not leak the
 * user's UI locale into the show's production-language slot.
 */
class MetaDetailsViewModelLanguageMappingTest {

    @Test
    fun `null production language stays null after merge`() {
        val advanced = DetailAdvancedMetadata(language = null)
        val localization = LocalizationDisplayState(
            requestedLanguage = "nld",
            selectedLanguage = "nld",
            fallbackReason = null
        )

        val merged = mergeProductionLanguageForTest(
            advancedLanguage = advanced.language,
            selectedLanguage = localization.selectedLanguage,
            existing = null
        )

        assertNull(
            "production language must be null when advanced.language is null; " +
                "the silent fallback to selectedLanguage was the bug",
            merged
        )
    }

    @Test
    fun `non-null advanced language wins`() {
        val merged = mergeProductionLanguageForTest(
            advancedLanguage = "eng",
            selectedLanguage = "nld",
            existing = "ita"
        )
        assertEquals("eng", merged)
    }

    @Test
    fun `existing language preserved when both new sources are null`() {
        val merged = mergeProductionLanguageForTest(
            advancedLanguage = null,
            selectedLanguage = null,
            existing = "eng"
        )
        assertEquals("eng", merged)
    }
}
```

The test references a tiny `internal` helper `mergeProductionLanguageForTest` which we'll extract in Step 3. This makes the merge rule unit-testable without standing up the full ViewModel.

- [ ] **Step 2: Run test — verify it fails (compile error)**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.detail.MetaDetailsViewModelLanguageMappingTest`
Expected: FAIL — `Unresolved reference: mergeProductionLanguageForTest`.

- [ ] **Step 3: Extract the merge into a tested helper, with the fallback removed**

In `MetaDetailsViewModel.kt`, add at file scope (top-level, not inside the class):

```kotlin
/**
 * Merge rule for `Meta.language` (production language). Pure function — exposed
 * `internal` so the unit test can pin the behavior.
 *
 * Bug A regression guard: this function MUST NOT consult any UI-locale source
 * (e.g. `LocalizationDisplayState.selectedLanguage`). Production language is a
 * property of the content; the user's UI locale is a property of the user.
 * Substituting one for the other is the bug we shipped from 2024 through
 * 2026-05-10. The dossier at
 * `docs/superpowers/notes/2026-05-10-original-language-audio-track-bug.md`
 * documents the failure mode end-to-end.
 *
 * Returns null when no source supplies a production language. Downstream
 * consumers (`PlayerRuntimeController.originalLanguage`,
 * `StreamScreenViewModel.applyDeterministicOriginalLanguageGuard`) handle
 * null correctly via naming-convention fallbacks.
 */
internal fun mergeProductionLanguageForTest(
    advancedLanguage: String?,
    @Suppress("UNUSED_PARAMETER") selectedLanguage: String?,
    existing: String?
): String? = advancedLanguage ?: existing
```

Then replace the three call sites:

**Site 1 — line ~1550 (inside `if (settings.useDetails) { updated = updated.copy(...) }`):**

```kotlin
language = mergeProductionLanguageForTest(
    advancedLanguage = document.advanced.language,
    selectedLanguage = document.localization.selectedLanguage,
    existing = updated.language
)
```

**Site 2 — line ~1617 (inside the `MetaPreview` projection emitted by `toRailPreview`):**

```kotlin
language = mergeProductionLanguageForTest(
    advancedLanguage = advanced.language,
    selectedLanguage = localization.selectedLanguage,
    existing = null
)
```

**Site 3 — line ~3398 (inside the legacy `Meta` builder):**

```kotlin
language = mergeProductionLanguageForTest(
    advancedLanguage = advanced.language,
    selectedLanguage = localization.selectedLanguage,
    existing = null
)
```

Remove the `Log.w(...)` block introduced in Task A1 from site 1 — the fallback no longer fires, so the warning is noise. Keep the function-level KDoc.

- [ ] **Step 4: Run unit test — verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.detail.MetaDetailsViewModelLanguageMappingTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Run full detail-screen test suite**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.detail.\*`
Expected: PASS. If anything red flags, it almost certainly is a test that was *asserting* the buggy fallback — read the failure carefully and confirm the test was wrong, then update it. Do not patch the production code to restore the fallback.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt \
        app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModelLanguageMappingTest.kt
git commit -m "fix: stop substituting UI locale for production language (Bug A)"
```

---

### Task A3: On-device verification of Phase A

**Files:** none (verification task)

- [ ] **Step 1: Install Phase A build**

```bash
./gradlew :app:installDebug
adb -s 192.168.50.98 logcat -c
```

- [ ] **Step 2: Reproduce the dossier scenario**

On device: UI locale Dutch, Audio language preference = Original. Start Citadel S1 episode that has tracks `[pl, en]`. Wait for playback to begin.

- [ ] **Step 3: Confirm `AUDIO_STARTUP_EVAL` shows the new behavior**

```bash
adb -s 192.168.50.98 logcat -d | grep "AUDIO_STARTUP_EVAL"
```

Expected: `pref=original origLang=null targets=[] wouldPick=...`. The `targets=[]` (empty) is what triggers `findOriginalTrackFallbackIndex` (the naming-convention picker). Look at the next log line:

```
AUDIO_STARTUP: Original-language fallback selected trackIndex=1
```

— index 1 is the English track. Bug A fixed.

If `origLang=nld` still appears, Task A2 was incomplete or the build didn't deploy. Re-run the gradle install and reproduce.

- [ ] **Step 4: Document the result in the commit log**

Add a follow-up empty commit recording the device verification:

```bash
git commit --allow-empty -m "verify: Phase A on-device shows origLang=null and English audio selected"
```

This makes the verification artifact greppable in `git log` later.

---

## Phase B — Plumb existing `language` candidates correctly (Bug B narrow)

Phase B closes the dropped-in-converter gap for TVDB and Kitsu while keeping the existing single `Meta.language` field. After Phase B, the `?:` chain at the call sites would ordinarily resolve to a correct value for canonical TVDB/Kitsu paths. Phase C then promotes the concept to its own field.

### Task B1: Forward `language` through `TvMetadataEnrichment.toMetadataCandidate`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/integration/metadata/MetadataAdapterCandidates.kt:43-61`
- Test: `app/src/test/java/com/nexio/tv/data/integration/metadata/MetadataAdapterCandidatesLanguageTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/nexio/tv/data/integration/metadata/MetadataAdapterCandidatesLanguageTest.kt`:

```kotlin
package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.ResolvedField
import com.nexio.tv.core.tvdb.TvMetadataEnrichment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetadataAdapterCandidatesLanguageTest {

    @Test
    fun `TvMetadataEnrichment toMetadataCandidate forwards language`() {
        val enrichment = TvMetadataEnrichment(
            seriesTvdbId = 393268,
            localizedTitle = "Citadel",
            description = null,
            backdrop = null,
            logo = null,
            poster = null,
            releaseInfo = null,
            runtimeMinutes = null,
            ageRating = null,
            language = "eng",
            remoteIds = mapOf("imdb" to setOf("tt12111188"))
        )

        val candidate = enrichment.toMetadataCandidate(MetadataPrimaryProvider.TVDB)

        assertEquals(
            "TVDB-routed series must carry production language as a primary-owned candidate; " +
                "see Bug B in 2026-05-10 dossier",
            "eng",
            candidate.fields[ResolvedField.LANGUAGE]?.value
        )
    }

    @Test
    fun `TvMetadataEnrichment toMetadataCandidate omits LANGUAGE when null`() {
        val enrichment = TvMetadataEnrichment(
            seriesTvdbId = null,
            localizedTitle = null,
            description = null,
            backdrop = null,
            logo = null,
            poster = null,
            releaseInfo = null,
            runtimeMinutes = null,
            ageRating = null,
            language = null,
            remoteIds = emptyMap()
        )

        val candidate = enrichment.toMetadataCandidate(MetadataPrimaryProvider.TVDB)

        assertNull(candidate.fields[ResolvedField.LANGUAGE])
    }

    @Test
    fun `null receiver yields candidate with no LANGUAGE field`() {
        val candidate = (null as TvMetadataEnrichment?).toMetadataCandidate(MetadataPrimaryProvider.TVDB)
        assertNull(candidate.fields[ResolvedField.LANGUAGE])
    }
}
```

The exact constructor parameters above must match the current `TvMetadataEnrichment` data class. If the data class has parameters this test omits, add them with their default values — do not change the data class signature in this task.

- [ ] **Step 2: Run test — verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.integration.metadata.MetadataAdapterCandidatesLanguageTest`
Expected: FAIL — first test fails with `expected:<eng> but was:<null>`. The other two pass coincidentally.

- [ ] **Step 3: Add the missing put-call**

In `MetadataAdapterCandidates.kt`, find the `TvMetadataEnrichment.toMetadataCandidate` function (currently lines 43-61) and add a new line inside the `buildMap` block, after the existing `remoteIds`/`REMOTE_IDS` lines:

```kotlin
internal fun TvMetadataEnrichment?.toMetadataCandidate(provider: MetadataPrimaryProvider): MetadataCandidate =
    MetadataCandidate(
        provider = provider,
        fields = buildMap {
            this@toMetadataCandidate ?: return@buildMap
            seriesTvdbId?.let { put(ResolvedField.CANONICAL_ID, FieldValue("tvdb:$it", FieldOwner.PRIMARY)) }
            localizedTitle?.let { put(ResolvedField.TITLE, FieldValue(it, FieldOwner.PRIMARY)) }
            description?.let { put(ResolvedField.OVERVIEW, FieldValue(it, FieldOwner.PRIMARY)) }
            poster?.let { put(ResolvedField.POSTER, FieldValue(it, FieldOwner.PRIMARY)) }
            backdrop?.let { put(ResolvedField.BACKDROP, FieldValue(it, FieldOwner.PRIMARY)) }
            logo?.let { put(ResolvedField.LOGO, FieldValue(it, FieldOwner.PRIMARY)) }
            rating?.let { put(ResolvedField.RATING, FieldValue(it, FieldOwner.PRIMARY)) }
            runtimeMinutes?.let { put(ResolvedField.RUNTIME, FieldValue(it, FieldOwner.PRIMARY)) }
            averageRuntimeMinutes?.let { putIfAbsent(ResolvedField.RUNTIME, FieldValue(it, FieldOwner.PRIMARY)) }
            language?.takeIf { it.isNotBlank() }
                ?.let { put(ResolvedField.LANGUAGE, FieldValue(it, FieldOwner.PRIMARY)) }
            if (remoteIds.isNotEmpty()) {
                put(ResolvedField.REMOTE_IDS, FieldValue(remoteIds, FieldOwner.PRIMARY))
            }
        }
    )
```

- [ ] **Step 4: Run test — verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.integration.metadata.MetadataAdapterCandidatesLanguageTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/integration/metadata/MetadataAdapterCandidates.kt \
        app/src/test/java/com/nexio/tv/data/integration/metadata/MetadataAdapterCandidatesLanguageTest.kt
git commit -m "fix: forward language through TvMetadataEnrichment candidate (Bug B for TVDB/Kitsu)"
```

---

### Task B2: Forward `originalLanguage` through `TvdbSeriesExtendedRecord.toMetadataCandidate`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/integration/metadata/MetadataAdapterCandidates.kt:63-79`
- Test: `app/src/test/java/com/nexio/tv/data/integration/metadata/MetadataAdapterCandidatesLanguageTest.kt`

- [ ] **Step 1: Add the failing test case**

Append to `MetadataAdapterCandidatesLanguageTest.kt`:

```kotlin
    @Test
    fun `TvdbSeriesExtendedRecord toMetadataCandidate forwards originalLanguage`() {
        val record = com.nexio.tv.data.remote.api.TvdbSeriesExtendedRecord(
            id = 393268,
            name = "Citadel",
            overview = null,
            image = null,
            score = null,
            averageRuntime = null,
            originalLanguage = "eng",
            originalCountry = "usa",
            remoteIds = emptyList()
        )

        val candidate = record.toMetadataCandidate(MetadataPrimaryProvider.TVDB)

        assertEquals("eng", candidate.fields[ResolvedField.LANGUAGE]?.value)
    }
```

If `TvdbSeriesExtendedRecord` requires additional non-null fields, supply them with sensible defaults (null/empty list). Do not modify the DTO's signature.

- [ ] **Step 2: Run test — verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.integration.metadata.MetadataAdapterCandidatesLanguageTest`
Expected: FAIL — the new test fails with `expected:<eng> but was:<null>`.

- [ ] **Step 3: Add the put-call to the raw-record converter**

Modify `MetadataAdapterCandidates.kt` lines 63-79:

```kotlin
internal fun TvdbSeriesExtendedRecord?.toMetadataCandidate(provider: MetadataPrimaryProvider): MetadataCandidate =
    MetadataCandidate(
        provider = provider,
        fields = buildMap {
            this@toMetadataCandidate ?: return@buildMap
            id?.let { put(ResolvedField.CANONICAL_ID, FieldValue("tvdb:$it", FieldOwner.PRIMARY)) }
            name?.let { put(ResolvedField.TITLE, FieldValue(it, FieldOwner.PRIMARY)) }
            overview?.let { put(ResolvedField.OVERVIEW, FieldValue(it, FieldOwner.PRIMARY)) }
            image?.let { put(ResolvedField.POSTER, FieldValue(it, FieldOwner.PRIMARY)) }
            score?.let { put(ResolvedField.RATING, FieldValue(it, FieldOwner.PRIMARY)) }
            averageRuntime?.let { put(ResolvedField.RUNTIME, FieldValue(it, FieldOwner.PRIMARY)) }
            originalLanguage?.takeIf { it.isNotBlank() }
                ?.let { put(ResolvedField.LANGUAGE, FieldValue(it, FieldOwner.PRIMARY)) }
            originalCountry?.takeIf { it.isNotBlank() }
                ?.let { put(ResolvedField.ORIGINAL_COUNTRY, FieldValue(it, FieldOwner.PRIMARY)) }
            val remoteIds = remoteIds.toRemoteIdsMap(id)
            if (remoteIds.isNotEmpty()) {
                put(ResolvedField.REMOTE_IDS, FieldValue(remoteIds, FieldOwner.PRIMARY))
            }
        }
    )
```

- [ ] **Step 4: Run test — verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.integration.metadata.MetadataAdapterCandidatesLanguageTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/integration/metadata/MetadataAdapterCandidates.kt \
        app/src/test/java/com/nexio/tv/data/integration/metadata/MetadataAdapterCandidatesLanguageTest.kt
git commit -m "fix: forward originalLanguage/originalCountry through TvdbSeriesExtendedRecord candidate"
```

---

### Task B3: Populate `language` on the `ProviderLocalizedMetadataResolver` short-circuit

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/tvdb/ProviderLocalizedMetadataResolver.kt:36-50`
- Test: `app/src/test/java/com/nexio/tv/data/integration/tvdb/TvdbMetadataServiceOriginalLanguageTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/nexio/tv/data/integration/tvdb/TvdbMetadataServiceOriginalLanguageTest.kt`:

```kotlin
package com.nexio.tv.data.integration.tvdb

import com.nexio.tv.core.metadata.router.ResolvedField
import com.nexio.tv.core.metadata.router.ResolvedMetadataDocument
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * High-level seam test for the canonical-route short-circuit. We assert that
 * when the canonical document already supplies a `language`, the
 * `ProviderLocalizedMetadataResolver` short-circuit propagates it through
 * `TvMetadataEnrichment.language` into the `ResolvedMetadataDocument`.
 *
 * This is the path that fires when the metadata router decides not to
 * re-fetch (cache hit, no localization request). Per the 2026-05-10 dossier,
 * it currently strips `language`.
 */
class TvdbMetadataServiceOriginalLanguageTest {
    @Test
    fun `canonical-route enrichment carries language from upstream document`() {
        // Build a ResolvedMetadataDocument with a known production language
        // (mirrors what the canonical route reads).
        val canonical = stubCanonicalDocumentWithLanguage("eng")

        val enrichment = canonical.asTvMetadataEnrichmentForCanonicalRoute()

        assertEquals("eng", enrichment.language)
    }
}
```

The helpers `stubCanonicalDocumentWithLanguage` and `asTvMetadataEnrichmentForCanonicalRoute` will be added in Step 3 — first as test fixtures, then made `internal` and shared with production.

- [ ] **Step 2: Run test — verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.integration.tvdb.TvdbMetadataServiceOriginalLanguageTest`
Expected: FAIL — `Unresolved reference: stubCanonicalDocumentWithLanguage`.

- [ ] **Step 3: Modify `ProviderLocalizedMetadataResolver` to populate `language`**

Open `app/src/main/java/com/nexio/tv/core/tvdb/ProviderLocalizedMetadataResolver.kt` around line 36-50 and modify the canonical-route enrichment construction:

```kotlin
return TvMetadataDecision(
    provider = provider,
    reason = provider.canonicalDecisionReason(hasLocalizedPayload),
    value = TvMetadataEnrichment(
        seriesTvdbId = canonicalDocument.canonicalId?.substringAfter("tvdb:")?.toIntOrNull(),
        localizedTitle = canonicalDocument.title,
        description = canonicalDocument.overview,
        poster = canonicalDocument.poster,
        backdrop = canonicalDocument.backdrop,
        logo = canonicalDocument.logo,
        rating = (canonicalDocument.rating as? Number)?.toDouble(),
        runtimeMinutes = canonicalDocument.runtimeMinutes,
        // Bug B fix (2026-05-10 dossier): canonical document already knows the
        // production language; do not strip it on the short-circuit path.
        language = canonicalDocument.language
    ),
    diagnostics = provider.canonicalDiagnostics(tvRequest, hasLocalizedPayload)
)
```

Add the test helpers as `internal` extension/factory functions in the same package:

```kotlin
// app/src/main/java/com/nexio/tv/core/tvdb/ProviderLocalizedMetadataResolver.kt (file-scope, after the class)

internal fun stubCanonicalDocumentWithLanguage(language: String): ResolvedMetadataDocument =
    ResolvedMetadataDocument(
        canonicalId = "tvdb:393268",
        title = "Citadel",
        overview = null,
        poster = null,
        backdrop = null,
        logo = null,
        rating = null,
        runtimeMinutes = null,
        language = language,
        fieldOwners = emptyMap(),
        ignoredOverwrites = emptyList()
    )

internal fun ResolvedMetadataDocument.asTvMetadataEnrichmentForCanonicalRoute(): TvMetadataEnrichment =
    TvMetadataEnrichment(
        seriesTvdbId = canonicalId?.substringAfter("tvdb:")?.toIntOrNull(),
        localizedTitle = title,
        description = overview,
        poster = poster,
        backdrop = backdrop,
        logo = logo,
        rating = (rating as? Number)?.toDouble(),
        runtimeMinutes = runtimeMinutes,
        language = language
    )
```

If `ResolvedMetadataDocument` requires additional non-null fields not shown here, add them in the stub with their default values. Do not change the data class.

- [ ] **Step 4: Run test — verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.integration.tvdb.TvdbMetadataServiceOriginalLanguageTest`
Expected: PASS.

- [ ] **Step 5: Run a wider regression sweep**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.tvdb.\*`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/tvdb/ProviderLocalizedMetadataResolver.kt \
        app/src/test/java/com/nexio/tv/data/integration/tvdb/TvdbMetadataServiceOriginalLanguageTest.kt
git commit -m "fix: propagate language through canonical-route TvMetadataEnrichment"
```

---

### Task B4: Phase B device verification

**Files:** none.

- [ ] **Step 1: Install Phase B build**

```bash
./gradlew :app:installDebug
adb -s 192.168.50.98 logcat -c
```

- [ ] **Step 2: Reproduce the dossier scenario again**

Same setup as A3 — UI Dutch, Audio = Original, Citadel `[pl, en]`.

- [ ] **Step 3: Confirm `AUDIO_STARTUP_EVAL` shows the correct production language**

```bash
adb -s 192.168.50.98 logcat -d | grep "AUDIO_STARTUP_EVAL"
```

Expected: `pref=original origLang=eng targets=[en] wouldPick=[1]en|English (E-AC-3 5.1) current=[1]en|English (E-AC-3 5.1)`. Different from A3 in two ways:
- `origLang=eng` (was `null` after Phase A only)
- `wouldPick=[1]en|English` (was `<none>` from naming-convention fallback after Phase A only)

If `origLang=null` still appears for series, the TVDB → ResolvedField.LANGUAGE chain is broken somewhere. Tasks B1/B2/B3 produced unit-test evidence of correctness; the failure mode if device says null is most likely (a) install didn't deploy, (b) the route is not going through any of the three patched converters. Re-run unit tests; install again; if still null, escalate — there's a fourth converter that needs the same fix.

- [ ] **Step 4: Document the result**

```bash
git commit --allow-empty -m "verify: Phase B on-device shows origLang=eng for TVDB-routed series"
```

---

## Phase C — Structural split: introduce `originalLanguage` as a first-class field

Phase C eliminates the conceptual collision permanently: production language and UI-locale language stop sharing a field name. After Phase C, no future codepath can leak UI locale into a content-property slot via the `?:` operator, because the two fields have different types-of-meaning and grep-different names.

This phase mirrors plan-1's pattern: introduce the new first-class field at the metadata-router layer first, then migrate each consumer one task at a time.

### Task C1: Add `ResolvedField.ORIGINAL_LANGUAGE` to the enum

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataModels.kt:35-60`

- [ ] **Step 1: Read current enum**

Run: `sed -n '35,65p' app/src/main/java/com/nexio/tv/core/metadata/router/MetadataModels.kt`
Expected: enum with `LANGUAGE`, `ORIGINAL_COUNTRY`, etc.

- [ ] **Step 2: Add the new value next to its counterpart**

Edit the enum to add `ORIGINAL_LANGUAGE` immediately after `ORIGINAL_COUNTRY` (so the pair is grep-adjacent):

```kotlin
enum class ResolvedField {
    CANONICAL_ID,
    TITLE,
    OVERVIEW,
    RELEASE_DATE,
    RUNTIME,
    GENRES,
    AGE_RATING,
    COUNTRIES,
    LANGUAGE,
    CAST,
    CREW,
    ORGANIZATION_LIST,
    EPISODES,
    POSTER,
    BACKDROP,
    LOGO,
    RATING,
    REVIEWS,
    TRAILERS,
    RECOMMENDATIONS,
    TRACKING,
    AIRS_TIME,
    ORIGINAL_COUNTRY,
    ORIGINAL_LANGUAGE,
    // …existing remaining values stay in their original order
}
```

Preserve any values listed after `ORIGINAL_COUNTRY` — do not reorder.

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin`
Expected: BUILD SUCCESSFUL. Adding an enum value is fully backwards-compatible.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/metadata/router/MetadataModels.kt
git commit -m "feat: add ResolvedField.ORIGINAL_LANGUAGE enum value"
```

---

### Task C2: Add `originalLanguage` field to `ResolvedMetadataDocument`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataModels.kt:155-200`

- [ ] **Step 1: Add the field with default null**

In `ResolvedMetadataDocument`, add `originalLanguage` immediately after the existing `language` field:

```kotlin
data class ResolvedMetadataDocument(
    val canonicalId: String?,
    val title: String?,
    val overview: String?,
    val poster: String?,
    val backdrop: String?,
    val logo: String?,
    val rating: Any?,
    val runtimeMinutes: Int?,
    val genres: List<String> = emptyList(),
    val releaseDate: String? = null,
    val ageRating: String? = null,
    val countries: List<String> = emptyList(),
    val language: String? = null,
    /**
     * Production language of the title (e.g. `"eng"` for Citadel). Sourced from
     * TMDB `original_language`, TVDB `originalLanguage`, or hardcoded inference
     * for Kitsu's typed shapes (`anime`→`"ja"`). Distinct from [language] which
     * historically conflated this concept with the UI-locale fetch language.
     * See `docs/superpowers/notes/2026-05-10-original-language-audio-track-bug.md`.
     */
    val originalLanguage: String? = null,
    val castMembers: List<com.nexio.tv.domain.model.MetaCastMember> = emptyList(),
    // …rest unchanged
)
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/metadata/router/MetadataModels.kt
git commit -m "feat: add originalLanguage to ResolvedMetadataDocument"
```

---

### Task C3: Update `FieldResolver` to populate `originalLanguage`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/metadata/router/FieldResolver.kt:284-300`
- Test: `app/src/test/java/com/nexio/tv/core/metadata/router/FieldResolverOriginalLanguageTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/nexio/tv/core/metadata/router/FieldResolverOriginalLanguageTest.kt`:

```kotlin
package com.nexio.tv.core.metadata.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FieldResolverOriginalLanguageTest {
    @Test
    fun `originalLanguage routed from ORIGINAL_LANGUAGE field`() {
        val fields = mapOf<ResolvedField, Any>(
            ResolvedField.ORIGINAL_LANGUAGE to "eng"
        )
        // Direct call to whatever resolver entry-point reads `fields[ORIGINAL_LANGUAGE]`.
        // The exact function name will be confirmed in Step 3.
        val document = buildDocumentFromFieldsForTest(fields)
        assertEquals("eng", document.originalLanguage)
    }

    @Test
    fun `originalLanguage null when ORIGINAL_LANGUAGE field absent`() {
        val document = buildDocumentFromFieldsForTest(emptyMap())
        assertNull(document.originalLanguage)
    }
}
```

- [ ] **Step 2: Run test — verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.metadata.router.FieldResolverOriginalLanguageTest`
Expected: FAIL — `Unresolved reference: buildDocumentFromFieldsForTest`.

- [ ] **Step 3: Add the field-read line**

In `FieldResolver.kt` around line 284-300 (the `ResolvedMetadataDocument(...)` construction), add a new line after the existing `language = fields[ResolvedField.LANGUAGE] as? String,`:

```kotlin
return ResolvedMetadataDocument(
    canonicalId = fields[ResolvedField.CANONICAL_ID] as? String,
    title = fields[ResolvedField.TITLE] as? String,
    // …existing field reads…
    language = fields[ResolvedField.LANGUAGE] as? String,
    originalLanguage = fields[ResolvedField.ORIGINAL_LANGUAGE] as? String,
    // …existing field reads…
)
```

Add the test seam in the same file at file scope:

```kotlin
internal fun buildDocumentFromFieldsForTest(
    fields: Map<ResolvedField, Any>
): ResolvedMetadataDocument =
    ResolvedMetadataDocument(
        canonicalId = fields[ResolvedField.CANONICAL_ID] as? String,
        title = fields[ResolvedField.TITLE] as? String,
        overview = null,
        poster = null,
        backdrop = null,
        logo = null,
        rating = null,
        runtimeMinutes = null,
        language = fields[ResolvedField.LANGUAGE] as? String,
        originalLanguage = fields[ResolvedField.ORIGINAL_LANGUAGE] as? String,
        fieldOwners = emptyMap(),
        ignoredOverwrites = emptyList()
    )
```

- [ ] **Step 4: Run test — verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.metadata.router.FieldResolverOriginalLanguageTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/metadata/router/FieldResolver.kt \
        app/src/test/java/com/nexio/tv/core/metadata/router/FieldResolverOriginalLanguageTest.kt
git commit -m "feat: route ORIGINAL_LANGUAGE through FieldResolver"
```

---

### Task C4: Add `originalLanguage` to `DetailAdvancedMetadata`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/domain/model/ResolvedDetailDisplayDocument.kt:55-67`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/MetadataDisplayRepository.kt:264-275`

- [ ] **Step 1: Add the field to the data class**

Edit `ResolvedDetailDisplayDocument.kt`, parallel to the existing `originalCountry`:

```kotlin
@Immutable
data class DetailAdvancedMetadata(
    val ageRating: String? = null,
    val countries: List<String> = emptyList(),
    val language: String? = null,
    val originalLanguage: String? = null,
    val productionCompanies: List<MetaCompany> = emptyList(),
    val networks: List<MetaCompany> = emptyList(),
    val airsTime: String? = null,
    val originalCountry: String? = null,
    val originalNetwork: String? = null,
    val latestNetwork: String? = null,
    val platformName: String? = null
)
```

- [ ] **Step 2: Update `MetadataDisplayRepository.toDetailAdvancedMetadata`**

In `MetadataDisplayRepository.kt:264-275`, add the new copy line:

```kotlin
private fun ResolvedMetadataDocument.toDetailAdvancedMetadata(
    productionCompanies: List<MetaCompany>,
    networks: List<MetaCompany>
): DetailAdvancedMetadata =
    DetailAdvancedMetadata(
        ageRating = ageRating,
        countries = countries,
        language = language,
        originalLanguage = originalLanguage,
        productionCompanies = productionCompanies,
        networks = networks,
        airsTime = airsTime,
        originalCountry = originalCountry,
        originalNetwork = originalNetwork,
        latestNetwork = latestNetwork,
        platformName = platformName
    )
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/domain/model/ResolvedDetailDisplayDocument.kt \
        app/src/main/java/com/nexio/tv/data/repository/MetadataDisplayRepository.kt
git commit -m "feat: thread originalLanguage through DetailAdvancedMetadata"
```

---

### Task C5: Emit `ORIGINAL_LANGUAGE` from all four candidate builders

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/integration/metadata/MetadataAdapterCandidates.kt:21-41, 43-61, 63-79, 225-322`
- Test: `app/src/test/java/com/nexio/tv/data/integration/metadata/MetadataAdapterCandidatesLanguageTest.kt`

- [ ] **Step 1: Update tests to assert ORIGINAL_LANGUAGE**

Append four new tests to the existing `MetadataAdapterCandidatesLanguageTest.kt`. Each asserts the `ORIGINAL_LANGUAGE` slot is populated for one builder.

```kotlin
    @Test
    fun `TmdbEnrichment toMetadataCandidate emits ORIGINAL_LANGUAGE`() {
        val enrichment = com.nexio.tv.core.tmdb.TmdbEnrichment(
            localizedTitle = "Fight Club",
            description = null,
            genres = emptyList(),
            backdrop = null,
            logo = null,
            poster = null,
            directorMembers = emptyList(),
            writerMembers = emptyList(),
            castMembers = emptyList(),
            releaseInfo = null,
            rating = null,
            runtimeMinutes = null,
            director = emptyList(),
            writer = emptyList(),
            productionCompanies = emptyList(),
            networks = emptyList(),
            ageRating = null,
            countries = null,
            language = "en",
            collectionId = null,
            collectionName = null
        )
        val candidate = enrichment.toMetadataCandidate(MetadataPrimaryProvider.TMDB)
        assertEquals("en", candidate.fields[ResolvedField.ORIGINAL_LANGUAGE]?.value)
    }

    @Test
    fun `TvMetadataEnrichment toMetadataCandidate emits ORIGINAL_LANGUAGE alongside LANGUAGE`() {
        val enrichment = com.nexio.tv.core.tvdb.TvMetadataEnrichment(
            seriesTvdbId = 393268,
            localizedTitle = "Citadel",
            description = null,
            backdrop = null,
            logo = null,
            poster = null,
            releaseInfo = null,
            runtimeMinutes = null,
            ageRating = null,
            language = "eng",
            remoteIds = emptyMap()
        )
        val candidate = enrichment.toMetadataCandidate(MetadataPrimaryProvider.TVDB)
        // Both fields populated during the deprecation window.
        assertEquals("eng", candidate.fields[ResolvedField.LANGUAGE]?.value)
        assertEquals("eng", candidate.fields[ResolvedField.ORIGINAL_LANGUAGE]?.value)
    }

    @Test
    fun `TvdbSeriesExtendedRecord toMetadataCandidate emits ORIGINAL_LANGUAGE`() {
        val record = com.nexio.tv.data.remote.api.TvdbSeriesExtendedRecord(
            id = 393268,
            name = "Citadel",
            overview = null,
            image = null,
            score = null,
            averageRuntime = null,
            originalLanguage = "eng",
            originalCountry = "usa",
            remoteIds = emptyList()
        )
        val candidate = record.toMetadataCandidate(MetadataPrimaryProvider.TVDB)
        assertEquals("eng", candidate.fields[ResolvedField.ORIGINAL_LANGUAGE]?.value)
    }
```

Add a fifth test for `buildTmdbLocalizedCandidate` — this requires constructing a fake `LocalizationPolicy` and `TmdbEnrichment`. Use whatever test helper already exists in the suite (`grep -n "buildTmdbLocalizedCandidate" app/src/test`); if none, write a minimal one inline:

```kotlin
    @Test
    fun `buildTmdbLocalizedCandidate emits ORIGINAL_LANGUAGE from source enrichment`() {
        val policy = com.nexio.tv.data.integration.metadata.LocalizationPolicy(
            requestedLanguage = stubLang("en"),
            fallbackLanguage = stubLang("en"),
            requestedIsFallback = true,
            policyVersion = 1,
            maxPerEpisodeTranslationFallbacksPerRequest = 0
        )
        val enrichment = com.nexio.tv.core.tmdb.TmdbEnrichment(
            localizedTitle = "Fight Club",
            description = null,
            genres = emptyList(),
            backdrop = null,
            logo = null,
            poster = null,
            directorMembers = emptyList(),
            writerMembers = emptyList(),
            castMembers = emptyList(),
            releaseInfo = null,
            rating = null,
            runtimeMinutes = null,
            director = emptyList(),
            writer = emptyList(),
            productionCompanies = emptyList(),
            networks = emptyList(),
            ageRating = null,
            countries = null,
            language = "en",
            collectionId = null,
            collectionName = null
        )
        val candidate = buildTmdbLocalizedCandidate(
            provider = MetadataPrimaryProvider.TMDB,
            policy = policy,
            requested = enrichment,
            english = enrichment
        )
        assertEquals("en", candidate.fields[ResolvedField.ORIGINAL_LANGUAGE]?.value)
    }

    private fun stubLang(code: String) = com.nexio.tv.data.integration.metadata.NormalizedLanguage(
        providerCode = code,
        canonicalCode = code
    )
```

If `LocalizationPolicy` / `NormalizedLanguage` constructors require additional fields, supply sensible defaults from the existing data classes — do not change their signatures.

- [ ] **Step 2: Run tests — verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.integration.metadata.MetadataAdapterCandidatesLanguageTest`
Expected: FAIL — four new tests fail with `expected:<en|eng> but was:<null>`.

- [ ] **Step 3: Update `TmdbEnrichment.toMetadataCandidate` (lines 21-41)**

Add the new put-call:

```kotlin
internal fun TmdbEnrichment?.toMetadataCandidate(provider: MetadataPrimaryProvider): MetadataCandidate =
    MetadataCandidate(
        provider = provider,
        fields = buildMap {
            this@toMetadataCandidate ?: return@buildMap
            localizedTitle?.let { put(ResolvedField.TITLE, FieldValue(it, FieldOwner.PRIMARY)) }
            description?.let { put(ResolvedField.OVERVIEW, FieldValue(it, FieldOwner.PRIMARY)) }
            poster?.let { put(ResolvedField.POSTER, FieldValue(it, FieldOwner.PRIMARY)) }
            backdrop?.let { put(ResolvedField.BACKDROP, FieldValue(it, FieldOwner.PRIMARY)) }
            logo?.let { put(ResolvedField.LOGO, FieldValue(it, FieldOwner.PRIMARY)) }
            rating?.let { put(ResolvedField.RATING, FieldValue(it, FieldOwner.PRIMARY)) }
            runtimeMinutes?.let { put(ResolvedField.RUNTIME, FieldValue(it, FieldOwner.PRIMARY)) }
            language?.takeIf { it.isNotBlank() }
                ?.let { put(ResolvedField.ORIGINAL_LANGUAGE, FieldValue(it, FieldOwner.PRIMARY)) }
            val remoteIds = buildMap<String, Set<String>> {
                imdbId?.takeIf { it.isNotBlank() }?.let { put("imdb", setOf(it)) }
                tvdbId?.let { put("tvdb", setOf(it.toString())) }
            }
            if (remoteIds.isNotEmpty()) {
                put(ResolvedField.REMOTE_IDS, FieldValue(remoteIds, FieldOwner.PRIMARY))
            }
        }
    )
```

- [ ] **Step 4: Update `TvMetadataEnrichment.toMetadataCandidate` (lines 43-61)**

Add a second put-call alongside the existing `LANGUAGE` (which Task B1 added). During the deprecation window, both fields point at the same value:

```kotlin
language?.takeIf { it.isNotBlank() }?.let {
    put(ResolvedField.LANGUAGE, FieldValue(it, FieldOwner.PRIMARY))
    put(ResolvedField.ORIGINAL_LANGUAGE, FieldValue(it, FieldOwner.PRIMARY))
}
```

- [ ] **Step 5: Update `TvdbSeriesExtendedRecord.toMetadataCandidate` (lines 63-79)**

```kotlin
originalLanguage?.takeIf { it.isNotBlank() }?.let {
    put(ResolvedField.LANGUAGE, FieldValue(it, FieldOwner.PRIMARY))
    put(ResolvedField.ORIGINAL_LANGUAGE, FieldValue(it, FieldOwner.PRIMARY))
}
```

- [ ] **Step 6: Update `buildTmdbLocalizedCandidate` (line 291)**

Replace the existing `source?.language?.let { put(ResolvedField.LANGUAGE, ...) }` with a dual emit:

```kotlin
source?.language?.takeIf { it.isNotBlank() }?.let {
    put(ResolvedField.LANGUAGE, FieldValue(it, FieldOwner.PRIMARY))
    put(ResolvedField.ORIGINAL_LANGUAGE, FieldValue(it, FieldOwner.PRIMARY))
}
```

- [ ] **Step 7: Run tests — verify all pass**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.integration.metadata.MetadataAdapterCandidatesLanguageTest`
Expected: PASS (8 tests total).

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/integration/metadata/MetadataAdapterCandidates.kt \
        app/src/test/java/com/nexio/tv/data/integration/metadata/MetadataAdapterCandidatesLanguageTest.kt
git commit -m "feat: emit ORIGINAL_LANGUAGE from all four candidate builders"
```

---

### Task C6: Add `originalLanguage` to `Meta` and `MetaPreview`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/domain/model/Meta.kt:34`
- Modify: `app/src/main/java/com/nexio/tv/domain/model/MetaPreview.kt:30`

- [ ] **Step 1: Add the new field to `Meta`**

Edit `Meta.kt` to insert `originalLanguage` after `language`:

```kotlin
@Immutable
data class Meta(
    // …unchanged fields above…
    val country: String?,
    val awards: String?,
    val language: String?,
    /**
     * Production language of the title. Distinct from [language] which has
     * historically been overloaded to also carry the user's UI-locale fetch
     * language. New code MUST read this field for "what language is this
     * content in" decisions (player audio targeting, stream filtering).
     * See `docs/superpowers/notes/2026-05-10-original-language-audio-track-bug.md`.
     */
    val originalLanguage: String? = null,
    val links: List<MetaLink>,
    // …unchanged fields below…
)
```

- [ ] **Step 2: Add the new field to `MetaPreview`**

Edit `MetaPreview.kt` to insert `originalLanguage` after `language`:

```kotlin
@Immutable
data class MetaPreview(
    // …unchanged fields above…
    val language: String? = null,
    /**
     * Production language of the title (e.g. `"eng"` for English-original
     * content). Distinct from [language] which is overloaded.
     * See `docs/superpowers/notes/2026-05-10-original-language-audio-track-bug.md`.
     */
    val originalLanguage: String? = null,
    val posterProviderTag: String? = null,
    // …unchanged fields below…
)
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin`
Expected: BUILD SUCCESSFUL. Default-arg field on a data class with `copy` is fully backwards-compatible.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/domain/model/Meta.kt \
        app/src/main/java/com/nexio/tv/domain/model/MetaPreview.kt
git commit -m "feat: add originalLanguage field to Meta and MetaPreview"
```

---

### Task C7: Populate `Meta.originalLanguage` in `MetaDetailsViewModel`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt:1543-1551, 1605-1629, 3375-3402`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModelLanguageMappingTest.kt`

- [ ] **Step 1: Append a test for the new field**

Add to `MetaDetailsViewModelLanguageMappingTest.kt`:

```kotlin
    @Test
    fun `Meta originalLanguage populated from advanced originalLanguage`() {
        val populated = mergeOriginalLanguageForTest(
            advancedOriginalLanguage = "eng",
            existing = null
        )
        assertEquals("eng", populated)
    }

    @Test
    fun `Meta originalLanguage stays null when advanced originalLanguage is null`() {
        val populated = mergeOriginalLanguageForTest(
            advancedOriginalLanguage = null,
            existing = null
        )
        assertNull(populated)
    }
```

- [ ] **Step 2: Run test — verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.detail.MetaDetailsViewModelLanguageMappingTest`
Expected: FAIL — `Unresolved reference: mergeOriginalLanguageForTest`.

- [ ] **Step 3: Add the helper and route it through all three sites**

In `MetaDetailsViewModel.kt`, add at file scope:

```kotlin
internal fun mergeOriginalLanguageForTest(
    advancedOriginalLanguage: String?,
    existing: String?
): String? = advancedOriginalLanguage ?: existing
```

Update the three sites to also populate `originalLanguage`:

**Site 1 (line ~1545, full updated.copy block):**

```kotlin
if (settings.useDetails) {
    val countries = document.advanced.countries.takeIf { it.isNotEmpty() }
    updated = updated.copy(
        runtime = document.fields.runtimeText?.parseRuntimeMinutesText() ?: updated.runtime,
        releaseInfo = document.fields.releaseDate ?: document.fields.year?.toString() ?: updated.releaseInfo,
        ageRating = document.advanced.ageRating ?: updated.ageRating,
        country = countries?.joinToString(", ") ?: updated.country,
        language = mergeProductionLanguageForTest(
            advancedLanguage = document.advanced.language,
            selectedLanguage = document.localization.selectedLanguage,
            existing = updated.language
        ),
        originalLanguage = mergeOriginalLanguageForTest(
            advancedOriginalLanguage = document.advanced.originalLanguage,
            existing = updated.originalLanguage
        )
    )
}
```

**Site 2 (line ~1617, the rail-preview projection):** add `originalLanguage = advanced.originalLanguage`.

**Site 3 (line ~3398, the legacy `Meta` builder):** add `originalLanguage = advanced.originalLanguage`.

- [ ] **Step 4: Run tests — verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.detail.MetaDetailsViewModelLanguageMappingTest`
Expected: PASS (5 tests total).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt \
        app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModelLanguageMappingTest.kt
git commit -m "feat: populate Meta.originalLanguage from advanced.originalLanguage"
```

---

### Task C8: Populate `MetaPreview.originalLanguage` in `TmdbDiscoveryService`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TmdbDiscoveryService.kt:240-260`

- [ ] **Step 1: Read the current copy site**

Run: `sed -n '240,265p' app/src/main/java/com/nexio/tv/data/repository/TmdbDiscoveryService.kt`
Expected: a `MetaPreview(...)` constructor call with `language = result.originalLanguage?.trim()?.takeIf { it.isNotBlank() }`.

- [ ] **Step 2: Add `originalLanguage` to the same constructor**

Edit the file so both fields are populated:

```kotlin
MetaPreview(
    // …unchanged fields…
    language = result.originalLanguage?.trim()?.takeIf { it.isNotBlank() },
    originalLanguage = result.originalLanguage?.trim()?.takeIf { it.isNotBlank() },
    // …unchanged fields…
)
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/TmdbDiscoveryService.kt
git commit -m "feat: populate MetaPreview.originalLanguage from TMDB original_language"
```

---

### Task C9: Migrate the player nav arg to read `originalLanguage`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/navigation/NexioNavHost.kt:172, 958, 998`
- Test: `app/src/test/java/com/nexio/tv/ui/navigation/PlayerNavOriginalLanguageTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/nexio/tv/ui/navigation/PlayerNavOriginalLanguageTest.kt`:

```kotlin
package com.nexio.tv.ui.navigation

import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerNavOriginalLanguageTest {
    @Test
    fun `nav arg sources from originalLanguage when present`() {
        val item = stubMetaPreview(
            language = "nld",          // UI-locale leakage value
            originalLanguage = "eng"    // production language
        )
        val navArg = chooseNavOriginalLanguage(item)
        assertEquals("eng", navArg)
    }

    @Test
    fun `nav arg falls back to legacy language when originalLanguage absent`() {
        // Until every producer is migrated (e.g. addon-only paths), fall back
        // to the legacy field for compatibility, but ONLY when originalLanguage
        // is null.
        val item = stubMetaPreview(language = "eng", originalLanguage = null)
        val navArg = chooseNavOriginalLanguage(item)
        assertEquals("eng", navArg)
    }

    @Test
    fun `nav arg null when both fields absent`() {
        val item = stubMetaPreview(language = null, originalLanguage = null)
        val navArg = chooseNavOriginalLanguage(item)
        assertEquals(null, navArg)
    }

    private fun stubMetaPreview(language: String?, originalLanguage: String?): MetaPreview =
        MetaPreview(
            id = "tmdb:1",
            type = ContentType.SERIES,
            name = "Stub",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList(),
            language = language,
            originalLanguage = originalLanguage
        )
}
```

The function `chooseNavOriginalLanguage` is a small helper we'll extract from `NexioNavHost.kt` so the routing rule is unit-testable.

- [ ] **Step 2: Run test — verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.navigation.PlayerNavOriginalLanguageTest`
Expected: FAIL — `Unresolved reference: chooseNavOriginalLanguage`.

- [ ] **Step 3: Extract the helper, then route it through three call sites**

Add to `NexioNavHost.kt` at file scope (top-level), or a sibling file `NexioNavOriginalLanguage.kt` if you prefer to keep it small:

```kotlin
package com.nexio.tv.ui.navigation

import com.nexio.tv.domain.model.MetaPreview

/**
 * Choose the value to pass as the player's `originalLanguage` nav arg.
 *
 * Bug A regression guard: prefer the production-language field, fall back to
 * the legacy `language` field only when the new field is null. The legacy
 * field is itself sometimes the user's UI locale (Bug A) — but the only path
 * that produces such leakage is `MetaDetailsViewModel`'s pre-Phase-A code,
 * which Phase A removed. After Phase C lands, `language` should never be
 * production-meaningful in any new write path.
 */
fun chooseNavOriginalLanguage(item: MetaPreview): String? =
    item.originalLanguage ?: item.language
```

Update the three sites in `NexioNavHost.kt` (lines ~172, ~958, ~998) — anywhere that currently reads `originalLanguage = item.language` — to read `originalLanguage = chooseNavOriginalLanguage(item)`.

- [ ] **Step 4: Run tests — verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.navigation.PlayerNavOriginalLanguageTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Run a wider regression sweep**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.navigation.\*`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/navigation/NexioNavHost.kt \
        app/src/test/java/com/nexio/tv/ui/navigation/PlayerNavOriginalLanguageTest.kt
git commit -m "feat: source player nav arg originalLanguage from MetaPreview.originalLanguage"
```

---

### Task C10: Migrate `MetaDetailsScreen` nav-arg producers

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsScreen.kt:668, 696, 726`

- [ ] **Step 1: Read current sites**

Run: `grep -n "meta.language" app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsScreen.kt`
Expected: 3-5 matches around lines 668, 696, 726.

- [ ] **Step 2: Replace with the helper**

For each site that passes `originalLanguage = meta.language` (or builds a player nav route from it), import or qualify the helper from Task C9 and call it. If the receiver here is `Meta` rather than `MetaPreview`, add a sibling overload in `NexioNavHost.kt` or wherever Task C9 placed `chooseNavOriginalLanguage`:

```kotlin
fun chooseNavOriginalLanguage(meta: com.nexio.tv.domain.model.Meta): String? =
    meta.originalLanguage ?: meta.language
```

Then update each call site:

```kotlin
// Before:
//   originalLanguage = meta.language,
// After:
originalLanguage = chooseNavOriginalLanguage(meta),
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsScreen.kt \
        app/src/main/java/com/nexio/tv/ui/navigation/NexioNavHost.kt
git commit -m "feat: detail-screen nav routes prefer Meta.originalLanguage"
```

---

### Task C11: `HeroSection` language badge prefers `originalLanguage`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/HeroSection.kt:670-685`

- [ ] **Step 1: Read the current site**

Run: `sed -n '670,685p' app/src/main/java/com/nexio/tv/ui/screens/detail/HeroSection.kt`
Expected: a `secondaryItems` builder that calls `meta.language?.trim()?.takeIf { it.isNotBlank() }?.let { add(it.uppercase()) }`.

- [ ] **Step 2: Update to prefer `originalLanguage`, fall back to `language` for cosmetics**

```kotlin
val secondaryItems = remember(meta.ageRating, meta.country, meta.originalLanguage, meta.language) {
    buildList {
        meta.ageRating?.trim()?.takeIf { it.isNotBlank() }?.let { add(it) }
        meta.country?.trim()?.takeIf { it.isNotBlank() }?.let { add(it) }
        // Prefer the production-language field; fall back to the legacy field
        // for cosmetic display only. The badge is purely informational, so the
        // fallback is acceptable here even though it is forbidden for the
        // player's audio targeting (Task C9).
        (meta.originalLanguage ?: meta.language)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { add(it.uppercase()) }
    }
}
```

Note the new `meta.originalLanguage` parameter to `remember(...)` — without it, Compose won't recompute when the new field flips from null to non-null after Phase C lands.

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/detail/HeroSection.kt
git commit -m "feat: hero language badge prefers originalLanguage over legacy language"
```

---

### Task C12: Stream resolver — verification only

**Files:** none (read-only verification).

`StreamScreenViewModel.kt:130` reads `originalLanguage` from the `SavedStateHandle`. The nav-arg ingress is what we corrected in Task C9. No code change is needed here; this task confirms.

- [ ] **Step 1: Verify the consumer signature**

Run: `grep -n "private val originalLanguage" app/src/main/java/com/nexio/tv/ui/screens/stream/StreamScreenViewModel.kt`
Expected: `private val originalLanguage: String? = savedStateHandle.getOptionalString("originalLanguage")`.

- [ ] **Step 2: Verify the filter consumers haven't drifted**

Run: `grep -n "originalLanguage" app/src/main/java/com/nexio/tv/ui/screens/stream/StreamScreenViewModel.kt | head -20`
Expected: same six call sites as the dossier (line ~130 ingress, ~367/1054/1201/1320/1404 propagation, ~2055-2086 filter).

- [ ] **Step 3: On-device verification — autoplay no longer rejects English releases**

```bash
./gradlew :app:installDebug
adb -s 192.168.50.98 logcat -c
# On device: enable deterministic autoplay; navigate to an English-original show; pick a stream.
adb -s 192.168.50.98 logcat -d | grep -E "AUDIO_STARTUP|DETERMINISTIC|ORIGINAL_LANGUAGE_GUARD"
```

Expected: deterministic autoplay no longer filters out English-tagged releases for an English-original show with a Dutch UI locale. The audio picker selects English.

- [ ] **Step 4: Document the result**

```bash
git commit --allow-empty -m "verify: stream resolver consumes corrected originalLanguage nav arg (C9)"
```

---

### Task C13: Mark `Meta.language` and `MetaPreview.language` as `@Deprecated` for production-language use

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/domain/model/Meta.kt:34`
- Modify: `app/src/main/java/com/nexio/tv/domain/model/MetaPreview.kt:30`

- [ ] **Step 1: Add deprecation KDoc and message**

Edit `Meta.kt`:

```kotlin
@Deprecated(
    message = "Reading `language` for production-language decisions (audio targeting, " +
        "stream filtering, original-language matching) is unsafe. This field has " +
        "historically been overloaded with the user's UI-locale fetch language. " +
        "Use `originalLanguage` instead. The field remains for cosmetic display " +
        "in the detail-screen language badge (HeroSection); see " +
        "docs/superpowers/notes/2026-05-10-original-language-audio-track-bug.md.",
    replaceWith = ReplaceWith("originalLanguage")
)
val language: String?,
```

Mirror in `MetaPreview.kt`.

- [ ] **Step 2: Verify it compiles (with deprecation warnings)**

Run: `./gradlew :app:compileDebugKotlin -x lint 2>&1 | grep -E "language: deprecation|warning"` 
Expected: warnings at any remaining read sites. Each is a follow-up task — add to the plan's "Pre-Plan-X housekeeping" section if the count is non-trivial. Do not fail the build on deprecation; the field stays for cosmetic use.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nexio/tv/domain/model/Meta.kt \
        app/src/main/java/com/nexio/tv/domain/model/MetaPreview.kt
git commit -m "chore: deprecate language for production-language use; prefer originalLanguage"
```

---

## Phase D — Acceptance

### Task D1: Player picker regression test for the dossier scenario

**Files:**
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerStartupSelectionPolicyOriginalLanguageRegressionTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerStartupSelectionPolicyOriginalLanguageRegressionTest.kt`:

```kotlin
package com.nexio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * End-to-end regression for the 2026-05-10 dossier scenario:
 * Citadel S1, UI locale Dutch, audio preference Original. Tracks: [pl, en].
 * After Phase C, `originalLanguage` is "eng" — picker must select index 1
 * (English). Before the fix it was "nld" — picker selected index 0 (Polish).
 */
class PlayerStartupSelectionPolicyOriginalLanguageRegressionTest {
    @Test
    fun `Citadel-shape input picks English when originalLanguage is eng`() {
        val tracks = listOf(
            stubTrack(index = 0, language = "pl", name = "Polish (E-AC-3 5.1)"),
            stubTrack(index = 1, language = "en", name = "English (E-AC-3 5.1)")
        )

        val pickedIndex = findBestStartupAudioTrackIndex(
            audioTracks = tracks,
            targets = listOf("en"),     // resolved from originalLanguage="eng"
            originalLanguage = "eng"
        )

        assertEquals(1, pickedIndex)
    }

    @Test
    fun `Citadel-shape input does not pick Polish when targets are nl`() {
        // The pre-fix bug: originalLanguage leaked from UI locale, became "nld",
        // targets resolved to ["nl"], no match, default Polish track 0 won.
        // We assert the picker correctly returns -1 in that scenario, so the
        // fix at the upstream layer is what fixes the user-visible behavior.
        val tracks = listOf(
            stubTrack(index = 0, language = "pl", name = "Polish (E-AC-3 5.1)"),
            stubTrack(index = 1, language = "en", name = "English (E-AC-3 5.1)")
        )

        val pickedIndex = findBestStartupAudioTrackIndex(
            audioTracks = tracks,
            targets = listOf("nl"),
            originalLanguage = "nld"
        )

        assertEquals(
            "Picker returns -1 when no track matches; the audio-track regression " +
                "is fixed upstream by feeding correct originalLanguage, not by " +
                "weakening the picker.",
            -1,
            pickedIndex
        )
    }

    private fun stubTrack(index: Int, language: String, name: String): TrackInfo =
        TrackInfo(
            trackIndex = index,
            language = language,
            name = name,
            mimeType = "audio/eac3",
            channelCount = 6,
            codec = "eac3",
            isForced = false,
            trackId = "audio:$index"
        )
}
```

If `TrackInfo` requires additional fields, supply sensible defaults — do not change its signature.

- [ ] **Step 2: Run test — verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.player.PlayerStartupSelectionPolicyOriginalLanguageRegressionTest`
Expected: PASS (2 tests). The picker code itself didn't need changes — this is a guard that proves the picker still does the right thing given a correct input.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/nexio/tv/ui/screens/player/PlayerStartupSelectionPolicyOriginalLanguageRegressionTest.kt
git commit -m "test: regression guard for Citadel-shape audio picker scenario"
```

---

### Task D2: Full unit-test sweep + on-device acceptance

**Files:** none (verification + tagging task).

- [ ] **Step 1: Run the full unit-test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS. If any test fails, it almost certainly is a stale assertion that depended on the buggy fallback or on `Meta.language` carrying production-language meaning. Read each failure carefully:
- If the test was asserting buggy behavior, update the test to assert the new correct behavior and reference this plan in the diff message.
- If the test reveals a real regression, stop and triage before tagging.

- [ ] **Step 2: Lint + Compose stability sweep**

Run: `./gradlew :app:lintDebug -PenableComposeCompilerReports=true 2>&1 | tail -50`
Expected: no new errors. New `@Deprecated` warnings on `Meta.language` / `MetaPreview.language` are expected at remaining cosmetic-read sites.

- [ ] **Step 3: On-device acceptance — Citadel scenario**

```bash
./gradlew :app:installDebug
adb -s 192.168.50.98 logcat -c
# On device: UI locale Dutch, Audio preference Original, start Citadel S1 episode.
adb -s 192.168.50.98 logcat -d | grep -E "AUDIO_STARTUP|metadata.field_selected.*ORIGINAL_LANGUAGE"
```

Expected output snippet:

```
metadata.field_selected ... field=ORIGINAL_LANGUAGE selectedProvider=TVDB sourceRole=PRIMARY ...
AUDIO_STARTUP_EVAL: pref=original origLang=eng targets=[en] wouldPick=[1]en|... current=[1]en|...
```

- [ ] **Step 4: On-device acceptance — Kitsu anime scenario**

Repeat with a Kitsu-routed anime (any title where the home rail tags it as anime). Expected: `origLang=ja` (or `origLang=jpn` if normalization yields ISO-3) → targets resolve correctly → Japanese audio selected if present.

- [ ] **Step 5: On-device acceptance — Movie (TMDB) scenario**

Repeat with an English-original movie via TMDB. Expected: `origLang=en` → English audio selected.

- [ ] **Step 6: Document the verification trio**

```bash
git commit --allow-empty -m "verify: Phase C+D on-device acceptance for TVDB / Kitsu / TMDB paths"
```

- [ ] **Step 7: Tag the release**

```bash
git tag original-language-field-authority-2026-05-10
```

---

## Self-Review Checklist

Run these as a final sanity sweep before shipping:

- [ ] **Spec coverage:** every section of the dossier mapped to a task.
  - Bug A → Phase A (Tasks A1, A2, A3).
  - Bug B narrow → Phase B (Tasks B1-B4).
  - Structural split (Option 3) → Phase C (Tasks C1-C13).
  - Acceptance / regression → Phase D (Tasks D1-D2).
  - Per-provider audit:
    - TMDB MOVIE_CORE → Task C5 (continues to emit, now also as ORIGINAL_LANGUAGE).
    - TMDB TV_CORE → Task C5 (same converter).
    - TVDB → Tasks B1, B2, B3, C5.
    - Kitsu anime → Task B1 (drops are fixed by the same converter Task B1 patches).
    - Kitsu drama / manga → out of scope (documented in plan header).
    - `ProviderLocalizedMetadataResolver` short-circuit → Task B3.
  - Stream resolver → Task C12 (verification, no code change).
  - Detail-screen badge → Task C11.

- [ ] **No placeholders:** no "TBD", "implement later", "add appropriate handling", or "similar to Task N" stubs. Every code block contains real Kotlin.

- [ ] **Type consistency:** name and signature audit:
  - `mergeProductionLanguageForTest` — defined in Task A2, referenced in Task C7's site updates. Same signature.
  - `mergeOriginalLanguageForTest` — defined in Task C7, referenced only there.
  - `chooseNavOriginalLanguage` — defined in Task C9, overload added in Task C10. Both take their respective receivers and return `String?`.
  - `ResolvedField.ORIGINAL_LANGUAGE` — added in C1, consumed in C3, emitted in C5, referenced in tests for B/C.
  - `originalLanguage` field — added to `Meta`, `MetaPreview`, `DetailAdvancedMetadata`, `ResolvedMetadataDocument` in Tasks C2, C4, C6. All use the same default `null`.
  - `TvMetadataEnrichment.language` — set by `TvdbMetadataService.kt:518` (production code, unchanged) and `KitsuMetadataProviderAdapter.kt:105` (production code, unchanged). Forwarded by the converter in Task B1.

---

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-05-10-original-language-field-authority.md`. Two execution options:**

**1. Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration. Phases A and B are independent and ship value in isolation; ideal for review-checkpoint cadence (review after A2, after B3, after C5, after C13, after D2).

**2. Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints. Recommended checkpoint boundaries: end of Phase A (user-visible fix shipped), end of Phase B (canonical case correct), end of Phase C (architectural split complete), end of Phase D (regression-test gate).

**Which approach?**
