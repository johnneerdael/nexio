# Trakt Watched-State Regression Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the three watched-state regressions surfaced by Plan B's contract-test exploration: (1) hidden/dropped filter normalisation mismatch, (2) silent "last writer wins" on shared IMDB ids, (3) missing episode-mapping warmup for absolute-numbered shows.

**Architecture:** D.1 and D.2 are surgical fixes inside `TraktProgressService.kt` (~10–50 LOC each, TDD-driven). D.3 is a substantial port from NuvioTV — `TraktEpisodeMappingService` (~354 LOC), `TraktEpisodeMapping` model (~102 LOC), the `warmTraktEpisodeMappingForCurrentPlayback` extension (~33 LOC), plus controller-state additions and Hilt wiring. **D.3 is most of the plan's effort** — about a day on its own. Consider running D.1+D.2 as a fast first pass and D.3 as a separate session.

**Tech Stack:** Kotlin, Hilt DI, Retrofit (`TraktApi`), Moshi DTOs, JUnit + MockK.

**Source review:**
- `docs/superpowers/specs/2026-05-04-trakt-scrobble-correctness-findings.md` (the Plan B escalations)
- NuvioTV reference: `~/Scripts/trakt-integrations/NuvioTV/app/src/main/java/com/nuvio/tv/data/repository/TraktEpisodeMappingService.kt` (354 LOC) and `~/Scripts/trakt-integrations/NuvioTV/app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeControllerScrobble.kt` (116 LOC).

---

## File Map

**D.1 (Modify):**
- `app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt:1448-1463` — `getHiddenProgressSnapshot` to use the kind-aware overload + carry alias keys.
- `app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt:1577-1600` — `deriveNextUpFromWatchedShows` filter to consult alias keys.

**D.1 (Create):**
- `app/src/test/java/com/nexio/tv/data/repository/trakt/TraktProgressServiceHiddenDroppedFilterTest.kt`

**D.2 (Modify):**
- `app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt:1356-1361` — `getWatchedShowsSnapshot` `buildMap` to detect alias-key collisions and mark them ambiguous.
- `app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt:670-690` — `observeEpisodeProgress` lookup to skip ambiguous keys (resolve via canonical id only).

**D.2 (Create):**
- `app/src/test/java/com/nexio/tv/data/repository/trakt/TraktProgressServiceShowSiblingsAmbiguityTest.kt`

**D.3 (Create):**
- `app/src/main/java/com/nexio/tv/data/repository/TraktEpisodeMappingService.kt` — port from NuvioTV (~354 LOC).
- `app/src/main/java/com/nexio/tv/data/repository/TraktEpisodeMapping.kt` — port from NuvioTV (~102 LOC).
- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerScrobble.kt` — port `preparePlaybackBeforeStart` and `warmTraktEpisodeMappingForCurrentPlayback` (~116 LOC).
- `app/src/test/java/com/nexio/tv/data/repository/TraktEpisodeMappingServiceTest.kt`
- `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerScrobbleEpisodeMappingOrderingTest.kt` (the test originally planned for B.6).

**D.3 (Modify):**
- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt` — add `traktEpisodeMappingService` constructor parameter; add `currentTraktEpisodeMapping`, `currentTraktEpisodeMappingKey`, `playbackPreparationJob` properties; add `currentEpisodeMappingCacheKey()` if not present.
- `app/src/main/java/com/nexio/tv/core/di/RepositoryModule.kt` (or whichever Hilt module binds repository services) — bind `TraktEpisodeMappingService`.
- The `refreshScrobbleItem` call site (`PlayerRuntimeController.kt:392` per recon) — replaced by `preparePlaybackBeforeStart()` call.

---

## Task 1: Baseline test build

**Files:** none.

- [ ] **Step 1: Confirm working tree state**

```bash
git status
git rev-parse --abbrev-ref HEAD
```
Expected: branch `codex/integration-runtime-phase-a`. Concurrent codex WIP may be present.

- [ ] **Step 2: Run baseline**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.data.repository.Trakt*" \
  --tests "com.nexio.tv.data.repository.trakt.*" \
  --tests "com.nexio.tv.data.integration.trakt.*" \
  --tests "com.nexio.tv.ui.screens.player.*" \
  -x generateIntegrationRuntimeAudit
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: No commit**

---

## Task 2 (D.1): Fix hidden-dropped filter normalisation mismatch

**The bug:** `getHiddenProgressSnapshot` populates `droppedShowIds` via the legacy `normalizeContentId(ids)` overload (IMDB-first), but `mapWatchedShowItem` uses `normalizeContentId(ids, kind = MediaKind.SHOW)` (TVDB-first). The filter at `TraktProgressService.kt:1589-1592` compares mismatched id forms and never matches. Dropped shows reappear in Continue Watching.

**Two-part fix:**
1. Update `getHiddenProgressSnapshot` to use the kind-aware overload AND collect every alias id (so the filter has multiple chances to hit).
2. Update `deriveNextUpFromWatchedShows`'s filter to also check `entry.aliasContentIds` against the dropped/hidden sets.

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt:1448-1463`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt:1577-1600`
- Test: `app/src/test/java/com/nexio/tv/data/repository/trakt/TraktProgressServiceHiddenDroppedFilterTest.kt` (create)

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/nexio/tv/data/repository/trakt/TraktProgressServiceHiddenDroppedFilterTest.kt`. Mirror the wiring of `TraktProgressServiceWatchedShowsTest.kt`:

```kotlin
package com.nexio.tv.data.repository.trakt

import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.data.integration.trakt.TraktIntegrationProvider
import com.nexio.tv.data.integration.trakt.TraktPagedResponse
import com.nexio.tv.data.remote.dto.trakt.TraktHiddenItemDto
import com.nexio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nexio.tv.data.remote.dto.trakt.TraktShowDto
import com.nexio.tv.data.remote.dto.trakt.TraktWatchedEpisodeDto
import com.nexio.tv.data.remote.dto.trakt.TraktWatchedSeasonDto
import com.nexio.tv.data.remote.dto.trakt.TraktWatchedShowItemDto
import com.nexio.tv.data.repository.TraktProgressService
import com.nexio.tv.data.repository.trakt.TraktProgressMutationExecutor
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class TraktProgressServiceHiddenDroppedFilterTest {

    private val traktIntegrationProvider = mockk<TraktIntegrationProvider>(relaxed = true)
    private val service = TraktProgressService(
        traktIntegrationProvider = traktIntegrationProvider,
        traktProgressMutationExecutor = mockk<TraktProgressMutationExecutor>(relaxed = true),
        metadataRouterFacade = mockk<MetadataRouterFacade>(relaxed = true)
    )

    @Test
    fun dropped_show_canonicalised_with_show_kind_is_excluded_from_next_up() = runBlocking {
        // Reproduces the Plan A regression: Show A canonicalises to tvdb:999 in
        // mapWatchedShowItem (kind=SHOW), but the dropped-set keys it under tt9999998
        // (the legacy IMDB-first normalize). Without the fix, the filter sees no match
        // and the show appears in next-up.
        val show = TraktShowDto(
            title = "Dropped Show", year = 2020,
            ids = TraktIdsDto(trakt = 99, slug = "dropped-show", tvdb = 999, imdb = "tt9999998", tmdb = 9998)
        )
        val watched = TraktWatchedShowItemDto(
            plays = 1, lastWatchedAt = "2026-05-04T10:00:00Z",
            show = show,
            seasons = listOf(TraktWatchedSeasonDto(number = 1, episodes = listOf(
                TraktWatchedEpisodeDto(number = 1, plays = 1, lastWatchedAt = "2026-05-04T10:00:00Z")
            )))
        )
        coEvery { traktIntegrationProvider.getWatchedShows() } returns
            IntegrationCallResult.Success(listOf(watched))
        coEvery {
            traktIntegrationProvider.getHiddenItems(section = "dropped", type = "show", page = any(), limit = any())
        } returns IntegrationCallResult.Success(
            TraktPagedResponse(body = listOf(TraktHiddenItemDto(type = "show", show = show)), pageCount = 1)
        )
        // Other hidden sections empty.
        coEvery {
            traktIntegrationProvider.getHiddenItems(section = "progress_watched", type = any(), page = any(), limit = any())
        } returns IntegrationCallResult.Success(TraktPagedResponse(body = emptyList(), pageCount = 1))

        val nextUp = service.observeNextUp().first()

        assertTrue(
            "dropped show must be excluded from next-up regardless of which id form the dropped set uses. Got: ${nextUp.map { it.contentId }}",
            nextUp.none { it.contentId == "tvdb:999" || it.contentId == "tt9999998" }
        )
    }
}
```

**Note on the public next-up flow:** if `service.observeNextUp()` doesn't compile, find the actual public flow with `grep -n "fun observe.*NextUp\|val myShowsNextUp" app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt | head` and adapt.

- [ ] **Step 2: Run to confirm failure**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.data.repository.trakt.TraktProgressServiceHiddenDroppedFilterTest" \
  -x generateIntegrationRuntimeAudit
```
Expected: failure — the filter doesn't match because of the normalisation mismatch.

- [ ] **Step 3: Update `getHiddenProgressSnapshot`**

Edit `TraktProgressService.kt:1448-1463`. Replace:

```kotlin
val snapshot = HiddenProgressSnapshot(
    hiddenShowIds = hiddenShows.mapNotNull { item ->
        normalizeContentId(item.show?.ids).takeIf { it.isNotBlank() }
    }.toSet(),
    hiddenSeasonKeys = hiddenSeasons.mapNotNull { item ->
        val contentId = normalizeContentId(item.show?.ids)
        val season = item.season?.number
        if (contentId.isBlank() || season == null || season <= 0) {
            null
        } else {
            hiddenSeasonKey(contentId, season)
        }
    }.toSet(),
    droppedShowIds = droppedShows.mapNotNull { item ->
        normalizeContentId(item.show?.ids).takeIf { it.isNotBlank() }
    }.toSet()
)
```

With:

```kotlin
val snapshot = HiddenProgressSnapshot(
    hiddenShowIds = hiddenShows.flatMap { item ->
        showAliasKeys(item.show?.ids)
    }.toSet(),
    hiddenSeasonKeys = hiddenSeasons.mapNotNull { item ->
        val contentId = normalizeContentId(item.show?.ids, kind = MediaKind.SHOW)
        val season = item.season?.number
        if (contentId.isBlank() || season == null || season <= 0) {
            null
        } else {
            hiddenSeasonKey(contentId, season)
        }
    }.toSet(),
    droppedShowIds = droppedShows.flatMap { item ->
        showAliasKeys(item.show?.ids)
    }.toSet()
)
```

Add the `showAliasKeys` helper somewhere private in the file:

```kotlin
private fun showAliasKeys(ids: TraktIdsDto?): List<String> {
    if (ids == null) return emptyList()
    val canonical = normalizeContentId(ids, kind = MediaKind.SHOW)
    val aliases = traktIdLookupKeys(ids, kind = MediaKind.SHOW)
    return buildList {
        if (canonical.isNotBlank()) add(canonical)
        addAll(aliases)
    }.distinct()
}
```

This collects every id flavour for a hidden/dropped show, so the filter set contains the canonical SHOW key (`tvdb:N`) AND the IMDB key AND the TMDB key etc. The filter now matches whichever id flavour the watched-shows projection emits.

- [ ] **Step 4: Update the filter to also consult alias keys (defence-in-depth)**

Find `deriveNextUpFromWatchedShows` (around line 1577). The current filter compares `contentId` and `canonicalId` against the hidden/dropped sets:

```kotlin
contentId !in hiddenProgress.hiddenShowIds &&
    contentId !in hiddenProgress.droppedShowIds &&
    canonicalId !in hiddenProgress.hiddenShowIds &&
    canonicalId !in hiddenProgress.droppedShowIds
```

Extend it to also check the watched entry's alias set:

```kotlin
val entryAliases = entry.aliasContentIds + entry.canonicalContentId
val anyHiddenMatch = entryAliases.any { it in hiddenProgress.hiddenShowIds || it in hiddenProgress.droppedShowIds }
!anyHiddenMatch
```

The exact code shape depends on the surrounding `filter { ... }` lambda — read 30 lines of context and adapt. The principle: if ANY of the watched entry's alias ids appears in the hidden or dropped sets, exclude the entry.

- [ ] **Step 5: Run to confirm pass**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.data.repository.trakt.TraktProgressServiceHiddenDroppedFilterTest" \
  -x generateIntegrationRuntimeAudit
```
Expected: pass.

- [ ] **Step 6: Run broader regression**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.data.repository.Trakt*" \
  --tests "com.nexio.tv.data.repository.trakt.*" \
  -x generateIntegrationRuntimeAudit
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt \
        app/src/test/java/com/nexio/tv/data/repository/trakt/TraktProgressServiceHiddenDroppedFilterTest.kt
git commit -m "$(cat <<'EOF'
fix(trakt): hidden/dropped filter respects show-kind canonicalisation

getHiddenProgressSnapshot used the legacy normalizeContentId(ids)
overload (IMDB-first), while mapWatchedShowItem now uses the kind-aware
overload (TVDB-first per Plan A). The filter compared mismatched id
forms and never matched, so dropped shows reappeared in Continue
Watching.

Two-part fix:
- Hidden snapshot now collects every alias id form via the new
  showAliasKeys helper.
- deriveNextUpFromWatchedShows additionally checks each watched
  entry's alias set against the hidden/dropped sets (defence in depth).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3 (D.2): Add alias-id ambiguity guard

**The bug:** `getWatchedShowsSnapshot`'s `buildMap` does `put(alias, entry)` for every alias key. When two shows share an alias (e.g. shared IMDB id — a known Trakt data-quality issue), the second entry silently overwrites the first. The first show's progress is lost.

**Fix:** detect alias-key collisions during the build pass. When an alias would be overwritten by a different entry, mark that alias as "ambiguous" — `observeEpisodeProgress` will refuse to resolve via the ambiguous key and fall back to the canonical id (which is unique because it always carries the per-show TVDB id, not the shared IMDB).

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt:1356-1361` — `getWatchedShowsSnapshot` `buildMap` collision detection.
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt:670-690` — `observeEpisodeProgress` skips ambiguous lookup keys.
- Test: `app/src/test/java/com/nexio/tv/data/repository/trakt/TraktProgressServiceShowSiblingsAmbiguityTest.kt` (create)

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/nexio/tv/data/repository/trakt/TraktProgressServiceShowSiblingsAmbiguityTest.kt`:

```kotlin
package com.nexio.tv.data.repository.trakt

import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.data.integration.trakt.TraktIntegrationProvider
import com.nexio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nexio.tv.data.remote.dto.trakt.TraktShowDto
import com.nexio.tv.data.remote.dto.trakt.TraktWatchedEpisodeDto
import com.nexio.tv.data.remote.dto.trakt.TraktWatchedSeasonDto
import com.nexio.tv.data.remote.dto.trakt.TraktWatchedShowItemDto
import com.nexio.tv.data.repository.TraktProgressService
import com.nexio.tv.data.repository.trakt.TraktProgressMutationExecutor
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class TraktProgressServiceShowSiblingsAmbiguityTest {

    private val traktIntegrationProvider = mockk<TraktIntegrationProvider>(relaxed = true)
    private val service = TraktProgressService(
        traktIntegrationProvider = traktIntegrationProvider,
        traktProgressMutationExecutor = mockk<TraktProgressMutationExecutor>(relaxed = true),
        metadataRouterFacade = mockk<MetadataRouterFacade>(relaxed = true)
    )

    @Test
    fun two_shows_sharing_imdb_id_keep_their_own_episode_sets_under_unique_ids() = runBlocking {
        // Show A and Show B share imdb tt9999999 (a known Trakt data-quality issue).
        // Their unique tvdb ids should each return their own watched-episode set.
        val showA = TraktWatchedShowItemDto(
            plays = 1, lastWatchedAt = "2026-05-04T10:00:00Z",
            show = TraktShowDto(
                title = "Show A", year = 2020,
                ids = TraktIdsDto(trakt = 1, slug = "show-a", imdb = "tt9999999", tvdb = 100)
            ),
            seasons = listOf(TraktWatchedSeasonDto(number = 1, episodes = listOf(
                TraktWatchedEpisodeDto(number = 1, plays = 1, lastWatchedAt = "2026-05-04T10:00:00Z")
            )))
        )
        val showB = TraktWatchedShowItemDto(
            plays = 1, lastWatchedAt = "2026-05-04T11:00:00Z",
            show = TraktShowDto(
                title = "Show B", year = 2021,
                ids = TraktIdsDto(trakt = 2, slug = "show-b", imdb = "tt9999999", tvdb = 200)
            ),
            seasons = listOf(TraktWatchedSeasonDto(number = 1, episodes = listOf(
                TraktWatchedEpisodeDto(number = 2, plays = 1, lastWatchedAt = "2026-05-04T11:00:00Z")
            )))
        )
        coEvery { traktIntegrationProvider.getWatchedShows() } returns
            IntegrationCallResult.Success(listOf(showA, showB))

        val watchedA = service.observeEpisodeProgress("tvdb:100").first()
        val watchedB = service.observeEpisodeProgress("tvdb:200").first()

        assertEquals("Show A's episode (1,1) must be preserved", setOf(1 to 1), watchedA.keys)
        assertEquals("Show B's episode (1,2) must be preserved", setOf(1 to 2), watchedB.keys)
    }

    @Test
    fun ambiguous_imdb_lookup_returns_empty_rather_than_wrong_show() = runBlocking {
        val showA = TraktWatchedShowItemDto(
            plays = 1, lastWatchedAt = "2026-05-04T10:00:00Z",
            show = TraktShowDto(
                title = "Show A", year = 2020,
                ids = TraktIdsDto(trakt = 1, slug = "show-a", imdb = "tt9999999", tvdb = 100)
            ),
            seasons = listOf(TraktWatchedSeasonDto(number = 1, episodes = listOf(
                TraktWatchedEpisodeDto(number = 1, plays = 1, lastWatchedAt = "2026-05-04T10:00:00Z")
            )))
        )
        val showB = TraktWatchedShowItemDto(
            plays = 1, lastWatchedAt = "2026-05-04T11:00:00Z",
            show = TraktShowDto(
                title = "Show B", year = 2021,
                ids = TraktIdsDto(trakt = 2, slug = "show-b", imdb = "tt9999999", tvdb = 200)
            ),
            seasons = listOf(TraktWatchedSeasonDto(number = 1, episodes = listOf(
                TraktWatchedEpisodeDto(number = 2, plays = 1, lastWatchedAt = "2026-05-04T11:00:00Z")
            )))
        )
        coEvery { traktIntegrationProvider.getWatchedShows() } returns
            IntegrationCallResult.Success(listOf(showA, showB))

        // Looking up by the ambiguous IMDB id must return empty — neither show owns
        // it uniquely, so we refuse to guess.
        val ambiguous = service.observeEpisodeProgress("tt9999999").first()

        assertEquals("ambiguous IMDB lookup must return empty (refuse to guess)", emptySet<Pair<Int, Int>>(), ambiguous.keys)
    }
}
```

- [ ] **Step 2: Run failure**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.data.repository.trakt.TraktProgressServiceShowSiblingsAmbiguityTest" \
  -x generateIntegrationRuntimeAudit
```
Expected: at least one failure (likely both tests — Show A's data is overwritten, AND the IMDB lookup returns Show B's set instead of empty).

- [ ] **Step 3: Add ambiguity detection in `getWatchedShowsSnapshot`**

Edit `TraktProgressService.kt:1356-1361`. Replace:

```kotlin
val entries = items.mapNotNull(::mapWatchedShowItem)
return buildMap<String, WatchedShowIndexEntry> {
    entries.forEach { entry ->
        put(entry.canonicalContentId, entry)
        entry.aliasContentIds.forEach { alias -> put(alias, entry) }
    }
}
```

With:

```kotlin
val entries = items.mapNotNull(::mapWatchedShowItem)
val seen = mutableMapOf<String, WatchedShowIndexEntry>()
val ambiguous = mutableSetOf<String>()
entries.forEach { entry ->
    // Canonical key always wins — it is unique per show by construction (TVDB id for
    // shows, fribb canonical for anime). If the canonical itself collides, that's a
    // genuine duplicate row from Trakt and we accept last-writer-wins.
    seen[entry.canonicalContentId] = entry
}
entries.forEach { entry ->
    entry.aliasContentIds.forEach { alias ->
        if (alias == entry.canonicalContentId) return@forEach
        val existing = seen[alias]
        when {
            existing == null -> seen[alias] = entry
            existing.canonicalContentId == entry.canonicalContentId -> Unit  // same entry, no conflict
            else -> ambiguous.add(alias)  // two different shows want this alias
        }
    }
}
ambiguous.forEach { seen.remove(it) }
return seen
```

- [ ] **Step 4: Update `observeEpisodeProgress` to handle the case where lookup returns null because the alias was ambiguous**

This actually requires no change — the existing fallback chain already returns `emptyMap()` when the lookup yields null. Verify by reading the function:

```bash
sed -n '670,700p' app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt
```

If the fallback chain already handles a null lookup gracefully (returns empty), no production change needed. The ambiguity-removal in Step 3 is sufficient.

- [ ] **Step 5: Run to confirm pass**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.data.repository.trakt.TraktProgressServiceShowSiblingsAmbiguityTest" \
  -x generateIntegrationRuntimeAudit
```
Expected: both tests pass.

- [ ] **Step 6: Run broader regression**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.data.repository.Trakt*" \
  --tests "com.nexio.tv.data.repository.trakt.*" \
  -x generateIntegrationRuntimeAudit
```
Expected: BUILD SUCCESSFUL. The existing watched-shows tests should still pass because shows with unique aliases continue to resolve normally.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt \
        app/src/test/java/com/nexio/tv/data/repository/trakt/TraktProgressServiceShowSiblingsAmbiguityTest.kt
git commit -m "$(cat <<'EOF'
fix(trakt): detect alias-id collisions in watched-shows projection

Two shows sharing an external id (e.g. an IMDB id — a known Trakt
data-quality issue) silently overwrote each other in the alias-keyed
lookup map (last-writer-wins via put()). The losing show's watched
progress was discarded.

Build the alias map in two passes: canonical ids first (always unique
per show), then aliases with collision detection. When two different
shows want the same alias, remove it — looking up by the ambiguous
id returns empty, and the two shows remain reachable via their
unique canonical (tvdb / kitsu) keys.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4 (D.3): Episode-mapping warmup port — pre-flight

**WARNING: D.3 is the bulk of this plan's effort.** Allocate at least half a day. If you'd rather defer this and ship D.1+D.2 first, stop here and commit only Tasks 2 and 3.

**The bug:** `warmTraktEpisodeMappingForCurrentPlayback()` and the `TraktEpisodeMappingService` are absent from this fork. Anime / absolute-numbered shows post the wrong (season, episode) numbers in scrobbles because the mapping isn't resolved before the scrobble item is built.

**Files:**
- none modified, no test added — this task is reconnaissance only.

- [ ] **Step 1: Read the NuvioTV reference end-to-end**

```bash
cat ~/Scripts/trakt-integrations/NuvioTV/app/src/main/java/com/nuvio/tv/data/repository/TraktEpisodeMappingService.kt
cat ~/Scripts/trakt-integrations/NuvioTV/app/src/main/java/com/nuvio/tv/data/repository/TraktEpisodeMapping.kt
cat ~/Scripts/trakt-integrations/NuvioTV/app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeControllerScrobble.kt
```

- [ ] **Step 2: Identify nexio package equivalents for every NuvioTV import**

The NuvioTV files import:
- `com.nuvio.tv.data.local.toTrackPreference` — confirm `com.nexio.tv.data.local.toTrackPreference` exists.
- Various Trakt API DTOs and the `TraktApi` interface.
- `PlayerRuntimeController` and its properties (`scope`, `contentId`, `currentSeason`, `currentEpisode`, `currentVideoId`, `contentType`, `_uiState`, `subtitleDelayUs`, `persistedTrackPreference`, `trackPreferenceDataStore`).

For each NuvioTV import, find the nexio equivalent:

```bash
grep -rn "class PlayerRuntimeController\b\|val scope\|val contentId\|var currentSeason\|var currentEpisode\|val currentVideoId\|val contentType\|val _uiState\|val subtitleDelayUs\|var persistedTrackPreference\|val trackPreferenceDataStore\|fun refreshScrobbleItem\|fun initializePlayer\|fun loadSavedProgressFor\|val playbackPreparationJob\|fun clearPendingEngineSwitchTrackPreference\|fun logSwitchTrace" app/src/main/java/com/nexio/tv/ui/screens/player --include="*.kt" | head -30
```

List which NuvioTV symbols have nexio equivalents and which are missing.

- [ ] **Step 3: Document the gap inventory**

If symbols are missing (e.g. `playbackPreparationJob`, `logSwitchTrace`, `clearPendingEngineSwitchTrackPreference`), they need to be ported too. Estimate the additional surface — D.3 may be larger than the line count suggests.

- [ ] **Step 4: Decision point**

Based on the inventory:
- **If all supporting infrastructure exists**: proceed to Tasks 5-9 (D.3 implementation).
- **If significant supporting infrastructure is missing**: stop here. The warmup port becomes a multi-day project and warrants its own dedicated plan with broader scope. Document the inventory in `docs/superpowers/specs/2026-05-04-episode-mapping-warmup-gap.md` and call it out as a known gap.

Either path is acceptable. The inventory is the deliverable.

- [ ] **Step 5: No commit unless inventory is documented**

If you wrote a gap doc, commit it:

```bash
git add docs/superpowers/specs/2026-05-04-episode-mapping-warmup-gap.md
git commit -m "$(cat <<'EOF'
docs(specs): document episode-mapping warmup port gap inventory

NuvioTV's TraktEpisodeMappingService + warmup wiring depends on
several PlayerRuntimeController properties (playbackPreparationJob,
logSwitchTrace, clearPendingEngineSwitchTrackPreference, etc.) that
may not exist in this fork. Inventory the gaps before committing
to a port.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5 (D.3): Port `TraktEpisodeMapping` model

Only proceed to Tasks 5-9 if Task 4's inventory confirmed the supporting infrastructure exists.

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/repository/TraktEpisodeMapping.kt`

- [ ] **Step 1: Copy the NuvioTV model verbatim and rewrite the package**

```bash
cp ~/Scripts/trakt-integrations/NuvioTV/app/src/main/java/com/nuvio/tv/data/repository/TraktEpisodeMapping.kt \
   app/src/main/java/com/nexio/tv/data/repository/TraktEpisodeMapping.kt
```

Edit the new file: replace `package com.nuvio.tv.data.repository` with `package com.nexio.tv.data.repository`. Replace any `com.nuvio.tv.` import with `com.nexio.tv.`.

- [ ] **Step 2: Compile**

```bash
./gradlew :app:compileUniversalDebugKotlin -x generateIntegrationRuntimeAudit
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/TraktEpisodeMapping.kt
git commit -m "$(cat <<'EOF'
feat(trakt): port TraktEpisodeMapping model from NuvioTV

Pure data classes for season/episode mapping; no behaviour. The
TraktEpisodeMappingService that uses these will be ported in the
next commit.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6 (D.3): Port `TraktEpisodeMappingService`

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/repository/TraktEpisodeMappingService.kt`
- Create: `app/src/test/java/com/nexio/tv/data/repository/TraktEpisodeMappingServiceTest.kt`

- [ ] **Step 1: Copy + rewrite package**

```bash
cp ~/Scripts/trakt-integrations/NuvioTV/app/src/main/java/com/nuvio/tv/data/repository/TraktEpisodeMappingService.kt \
   app/src/main/java/com/nexio/tv/data/repository/TraktEpisodeMappingService.kt
```

Edit: rewrite package and imports as in Task 5. Read the file end-to-end to identify any references that don't have nexio equivalents (per Task 4's inventory). Adapt as needed.

- [ ] **Step 2: Compile — fix imports and signatures until clean**

```bash
./gradlew :app:compileUniversalDebugKotlin -x generateIntegrationRuntimeAudit
```

Likely first failures: missing imports, missing methods on `TraktApi` (NuvioTV may use endpoints we haven't surfaced). For each compile error:
- If the call is to a NuvioTV-specific helper that doesn't exist here, find the closest nexio equivalent OR add the helper as part of this commit.
- If the call hits a Trakt API endpoint we don't expose (e.g. `getSeasonEpisodes` or similar), check `app/src/main/java/com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt` for an existing `getSeasonEpisodes` or `getEpisodeSummary` method (those exist per recon).

- [ ] **Step 3: Write a focused unit test**

Create `app/src/test/java/com/nexio/tv/data/repository/TraktEpisodeMappingServiceTest.kt`. The test stubs `TraktApi`/`TraktIntegrationProvider` to return a known season-episodes payload and asserts that `prefetchEpisodeMapping` resolves the absolute-to-relative mapping correctly.

The exact test depends on the public surface of the ported service. Read NuvioTV's test (if any) for hints:

```bash
find ~/Scripts/trakt-integrations/NuvioTV -name "TraktEpisodeMappingService*Test*.kt"
```

If NuvioTV ships a test, port it the same way (copy + rewrite). If not, write at minimum:

```kotlin
@Test
fun prefetch_returns_mapping_for_known_season_episode() = runBlocking {
    // Stub the provider to return a season with episodes; assert prefetchEpisodeMapping
    // returns the expected TraktEpisodeMapping.
    val service = TraktEpisodeMappingService(/* deps */)
    val mapping = service.prefetchEpisodeMapping(
        contentId = "tvdb:81189",
        contentType = "series",
        videoId = null,
        season = 1,
        episode = 1
    )
    assertNotNull(mapping)
    // Assertions on mapping shape.
}
```

- [ ] **Step 4: Run the test**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.data.repository.TraktEpisodeMappingServiceTest" \
  -x generateIntegrationRuntimeAudit
```

- [ ] **Step 5: Bind in Hilt**

Find the appropriate Hilt module:

```bash
grep -rln "@Singleton\|TraktAuthService\|TraktProgressService" app/src/main/java/com/nexio/tv/core/di --include="*.kt" | head -5
```

If `TraktEpisodeMappingService` is annotated `@Singleton` and uses `@Inject constructor`, no module change is needed — Hilt finds it automatically.

- [ ] **Step 6: Compile + test**

```bash
./gradlew :app:compileUniversalDebugKotlin :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.data.repository.TraktEpisodeMapping*" \
  -x generateIntegrationRuntimeAudit
```

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/TraktEpisodeMappingService.kt \
        app/src/test/java/com/nexio/tv/data/repository/TraktEpisodeMappingServiceTest.kt
git commit -m "$(cat <<'EOF'
feat(trakt): port TraktEpisodeMappingService from NuvioTV

Resolves absolute-to-relative episode numbering for shows that use
absolute numbering (anime in particular). The mapping is consulted
by the player's scrobble item builder (port of warmTraktEpisodeMapping
follows in the next commit) so /scrobble/start and /scrobble/stop
post the correct (season, episode) numbers.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 7 (D.3): Port `PlayerRuntimeControllerScrobble.kt`

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerScrobble.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt` — add the missing properties (`currentTraktEpisodeMapping`, `currentTraktEpisodeMappingKey`, `playbackPreparationJob`, `traktEpisodeMappingService` injection).

- [ ] **Step 1: Copy + rewrite package**

```bash
cp ~/Scripts/trakt-integrations/NuvioTV/app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerRuntimeControllerScrobble.kt \
   app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerScrobble.kt
```

Edit: rewrite package and imports. The file uses `import com.nuvio.tv.data.local.toTrackPreference` — change to `com.nexio.tv.data.local.toTrackPreference`.

- [ ] **Step 2: Add the missing properties to `PlayerRuntimeController`**

Read the current `PlayerRuntimeController.kt` constructor and field list:

```bash
grep -n "class PlayerRuntimeController\b\|@Inject constructor" app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt | head
```

Add:
- Constructor parameter: `private val traktEpisodeMappingService: TraktEpisodeMappingService`
- Properties:
  ```kotlin
  internal var currentTraktEpisodeMapping: TraktEpisodeMapping? = null
  internal var currentTraktEpisodeMappingKey: String? = null
  internal var playbackPreparationJob: Job? = null
  ```
- Imports for `TraktEpisodeMapping`, `TraktEpisodeMappingService`, and `kotlinx.coroutines.Job`.

- [ ] **Step 3: Wire `preparePlaybackBeforeStart()` into the playback start flow**

Find where `refreshScrobbleItem()` is currently called to start playback (per recon: `PlayerRuntimeController.kt:392`). Replace that call with `preparePlaybackBeforeStart(url, headers, loadSavedProgress)`. Some additional refactoring may be needed because `preparePlaybackBeforeStart` does more than `refreshScrobbleItem` (it also handles track-preference load, subtitle delay, and `initializePlayer`/`loadSavedProgressFor` calls).

If our existing `PlayerRuntimeController.kt` already has its own version of those steps, you have two choices:
- (a) Delete the existing inline equivalents and route everything through `preparePlaybackBeforeStart`.
- (b) Keep our existing flow and only add the warmup call.

Choose (b) for the smallest blast radius — at the call site for `refreshScrobbleItem`, prepend `warmTraktEpisodeMappingForCurrentPlayback()` and let the rest of the existing flow continue. This sacrifices NuvioTV's other improvements but minimises the chance of regression.

- [ ] **Step 4: Compile**

```bash
./gradlew :app:compileUniversalDebugKotlin -x generateIntegrationRuntimeAudit
```

- [ ] **Step 5: Update test sites that construct `PlayerRuntimeController` directly**

The new constructor parameter (`traktEpisodeMappingService`) breaks tests that construct the controller directly. Find them:

```bash
grep -rn "PlayerRuntimeController(" app/src/test --include="*.kt" | head
```

For each, add `traktEpisodeMappingService = mockk(relaxed = true)` to the constructor call.

If the codex agent has reverted similar test changes before (Plan A's `cacheStore` saga), expect this to potentially require the same workaround: give `traktEpisodeMappingService` a default value in the production constructor (`= NoopTraktEpisodeMappingService` or similar). Decide based on how many test sites are affected — if just 1-2, fix the tests directly; if many, use the default workaround.

- [ ] **Step 6: Run broader regression**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.ui.screens.player.*" \
  --tests "com.nexio.tv.data.repository.Trakt*" \
  -x generateIntegrationRuntimeAudit
```

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerScrobble.kt \
        app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt
# Plus any tests you updated.
git commit -m "$(cat <<'EOF'
feat(player): port warmTraktEpisodeMappingForCurrentPlayback from NuvioTV

Wires the new TraktEpisodeMappingService into the player runtime
controller. The warmup runs before refreshScrobbleItem during playback
preparation so the scrobble item is built with the correct
(season, episode) numbers for absolute-numbered shows (anime in
particular). Without this, /scrobble/start and /scrobble/stop
posted the absolute episode number, which Trakt interpreted as
the wrong episode in the wrong season.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 8 (D.3): Lock the warmup ordering with the deferred B.6 contract test

**Files:**
- Create: `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerScrobbleEpisodeMappingOrderingTest.kt`

- [ ] **Step 1: Write the source-inspection test (matching B.3/B.4/B.5 style)**

Create `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerScrobbleEpisodeMappingOrderingTest.kt`:

```kotlin
package com.nexio.tv.ui.screens.player

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerScrobbleEpisodeMappingOrderingTest {

    @Test
    fun warm_episode_mapping_runs_before_refresh_scrobble_item() {
        val src = source()
        // Find the function that contains both calls — likely preparePlaybackBeforeStart
        // or whatever Task 7 wired the warmup into.
        val warmIdx = src.indexOf("warmTraktEpisodeMappingForCurrentPlayback")
        val refreshIdx = src.indexOf("refreshScrobbleItem")
        assertTrue("warmup call must be present in PlayerRuntimeControllerScrobble.kt: $src", warmIdx >= 0)
        assertTrue("refreshScrobbleItem call must be present", refreshIdx >= 0)
        assertTrue(
            "warmup must precede refreshScrobbleItem (warmIdx=$warmIdx refreshIdx=$refreshIdx)",
            warmIdx < refreshIdx
        )
    }

    private fun source(): String =
        File("app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerScrobble.kt").readText()
}
```

If Task 7 used approach (b) (kept the existing flow and only prepended warmup at the call site), the warmup call may live in `PlayerRuntimeController.kt` rather than the new file. Adjust the source path accordingly, OR test against both files concatenated.

- [ ] **Step 2: Run + verify**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.ui.screens.player.PlayerScrobbleEpisodeMappingOrderingTest" \
  -x generateIntegrationRuntimeAudit
```

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/nexio/tv/ui/screens/player/PlayerScrobbleEpisodeMappingOrderingTest.kt
git commit -m "$(cat <<'EOF'
test(player): lock warmup-before-refresh ordering (deferred from B.6)

Plan B Task 6 escalated because the warmup function did not exist
in this fork. With D.3's port complete, lock the ordering so a
future refactor can't swap warmup and refresh — which would silently
post wrong scrobble (season, episode) for absolute-numbered shows.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 9 (D.3): Final D.3 verification

**Files:** none.

- [ ] **Step 1: Full Trakt + player regression**

```bash
./gradlew :app:testUniversalDebugUnitTest \
  --tests "com.nexio.tv.data.repository.Trakt*" \
  --tests "com.nexio.tv.data.repository.trakt.*" \
  --tests "com.nexio.tv.ui.screens.player.*" \
  --tests "com.nexio.tv.data.integration.trakt.*" \
  -x generateIntegrationRuntimeAudit
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Smoke-test on device (if available)**

If a real Android device with Trakt linked is available, install:

```bash
./gradlew :app:installDebug
```

Play an absolute-numbered show (anime works best). Watch a few minutes, then stop. Verify on Trakt's website that the correct (season, episode) was scrobbled. Without the warmup, Trakt would have recorded an absolute episode number (e.g. S0E27) instead of the correct relative pair (e.g. S2E3).

If no device is available, document that the UI/network behaviour was not verified.

- [ ] **Step 3: Update findings doc**

Edit `docs/superpowers/specs/2026-05-04-trakt-scrobble-correctness-findings.md` and mark the three issues as resolved with commit references:

```bash
# Edit the doc — replace "❌ Escalated" markers with "✅ Resolved (commit SHA …)"
# Then:
git add docs/superpowers/specs/2026-05-04-trakt-scrobble-correctness-findings.md
git commit -m "$(cat <<'EOF'
docs(specs): mark plan B escalations resolved by plan D

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Self-Review

**Spec coverage (vs `docs/superpowers/specs/2026-05-04-trakt-scrobble-correctness-findings.md`):**
- Finding B.6 (episode-mapping warmup absent): Tasks 4-8 (D.3). ✅
- Finding B.7 (alias-id ambiguity guard missing): Task 3 (D.2). ✅
- Finding B.8 (hidden-dropped filter normalisation mismatch): Task 2 (D.1). ✅

**Placeholder scan:** clean. Each fix step contains the actual code to insert. The D.3 sub-plan acknowledges the port may need adaptation per Task 4's inventory and gives a fallback path (the gap doc).

**Type consistency:** `showAliasKeys`, `MediaKind.SHOW`, `traktIdLookupKeys`, `TraktEpisodeMappingService`, `TraktEpisodeMapping`, `currentTraktEpisodeMapping`, `currentTraktEpisodeMappingKey`, `playbackPreparationJob`, `warmTraktEpisodeMappingForCurrentPlayback` referenced consistently across tasks.

**Scope honesty:** D.3 is genuinely larger than D.1 + D.2 combined. The plan flags this in the header and at the start of Task 4. If the user wants a faster first pass, they can run Tasks 1-3 only (D.1 + D.2) and run D.3 as a separate session (or break it into its own plan).
