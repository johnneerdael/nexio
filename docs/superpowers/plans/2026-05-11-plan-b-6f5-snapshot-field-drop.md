# Plan B Task 6f.5 — Drop Legacy Snapshot Fields Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Drop `Snapshot.catalogRows`/`fullCatalogRows`/`heroItems` and migrate the remaining consumers (apply pipeline, three filter helpers, producer-path transient construction, persist store) to operate on `rails` + `heroItemKeys` + a typed-surface content lookup.

**Architecture:** Reconstruct `List<CatalogRow>`/`heroItems` on demand from `(rails, heroItemKeys, ResolvedDisplayItem→MetaPreview map)` at the read sites that need MetaPreview content. The typed surface (`ResolvedDisplaySurfaceRepository`) is already populated from disk before snapshot apply runs (synchronously, same coroutine — verified at `HomeViewModelCatalogPipeline.kt:157-208`), so reconstruction is always safe at apply time. Filter helpers operate purely structurally on Rail (no content lookup needed). Persist store stops writing legacy fields; reader stops skipping `rails`/`heroItemKeys`.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, Gson streaming JSON (`JsonReader`/`JsonWriter`), Android TV.

**Pre-flight context — what 6f.1–6f.4 already shipped:**
- `778013fec` 6f.1 `SnapshotContentLookup.kt` — additive `Rail.toCatalogRowOrNull(typedItemsByKey)` + `Snapshot.reconstructHeroItems(typedItemsByKey)` helpers, no callers yet.
- `27f72a397` 6f.2 filter helpers populate `rails`+`heroItemKeys` alongside legacy fields. Internal filter logic still reads legacy fields. `homeCatalogGlobalKey(Rail)` overload added.
- `35f867822` 6f.3 eligibility checks + 4 log lines flipped to `rails`/`heroItemKeys`. **BUG: depends on reader parsing these fields — currently skipped, see Task 0.**
- `3051c31d4` 6f.4 `AndroidTvLocalSearchCorpus` enumerates via `rails`+`heroItemKeys`, content via `MetadataDiskCacheStore`.

**Acceptance gate:** Cold-start two-session on-device verification — Session 1 fresh persist, Session 2 restore + home renders within 30s, no ANR, snapshot file <300 KB (was 1.06 MB after 6e), MetaPreview count in heap dump down from 6e baseline.

---

## File Structure

Files modified or created:

| File | Responsibility |
|---|---|
| `app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt` | Stop skipping `rails`/`heroItemKeys` on read (Task 0). Drop legacy fields from `Snapshot` data class (Task 9). Stop writing legacy fields (Task 10). Update `decodeSnapshot` legacy migration path (Task 11). |
| `app/src/main/java/com/nexio/tv/ui/screens/home/ResolvedDisplayItemToMetaPreview.kt` | **NEW** — `ResolvedDisplayItem.toMetaPreview()` adapter (Task 1). |
| `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt` | **NEW method** — `currentTypedItemsByKey(): Map<String, MetaPreview>` reads `ResolvedDisplaySurfaceRepository.snapshotNow(activeProfileId)` and projects via `toMetaPreview()` (Task 2). |
| `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt` | Reshape three filter helpers to drop legacy field reads (Tasks 3, 4, 5). Reshape `applyHomeSnapshotToUiPipeline` to reconstruct rows from rails via typed-items map (Task 6). Reshape producer-path `transientSnapshot` construction (Task 7). |
| `app/src/test/java/com/nexio/tv/data/local/HomeCatalogSnapshotStoreTest.kt` | Update fixtures to construct rails-only `Snapshot` (Task 12). |
| `app/src/main/java/com/nexio/tv/ui/screens/home/SnapshotContentLookup.kt` | Drop the `Snapshot.heroItems` fallback in `reconstructHeroItems` (Task 13). |

---

## Task 0: Fix `streamReadSnapshot` to populate `rails` + `heroItemKeys`

**Why first:** 6f.3 shipped early-return checks `if (snapshot.rails.isEmpty() && snapshot.heroItemKeys.isEmpty()) return` at `HomeViewModelCatalogPipeline.kt:174,196`. The current reader at `HomeCatalogSnapshotStore.kt:372` skips both fields (`"rails", "heroItemKeys" -> reader.skipValue()`), so the checks always short-circuit — cold-start restore is broken. Must fix BEFORE any further field-dropping or readers below 6f.3 won't have data to read.

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt:307-396`

- [ ] **Step 1: Read the current reader**

Run: `sed -n '305,400p' app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt`

Note `var catalogRows`, `var fullCatalogRows`, `var heroItems` at lines 312-314, and the `"rails", "heroItemKeys" -> reader.skipValue()` at line 372. Note `railListType` and `railItemKeyListType` constants exist (referenced by the writer at lines 489, 491).

- [ ] **Step 2: Locate the type constants**

Run: `grep -n "railListType\|railItemKeyListType" app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt | head -5`

Confirm both type tokens exist. If they don't, add them next to the existing `catalogRowListType`/`metaPreviewListType`/`stringListType` declarations.

- [ ] **Step 3: Add `rails` and `heroItemKeys` locals; parse them**

Edit `streamReadSnapshot`. Add locals after line 315 and replace the skip on line 372:

```kotlin
private fun streamReadSnapshot(file: File, posterProviderToken: String): Snapshot? {
    val expectedLanguageTag = currentLanguageTag()
    var schemaVersion: Int = -1
    var languageTag: String? = null
    var cachedPosterToken: String? = null
    var catalogRows: List<CatalogRow> = emptyList()
    var fullCatalogRows: List<CatalogRow> = emptyList()
    var heroItems: List<MetaPreview> = emptyList()
    var orderedGroupKeys: List<String> = emptyList()
    var rails: List<Rail> = emptyList()
    var heroItemKeys: List<RailItemKey> = emptyList()

    return runCatching {
        FileInputStream(file).use { fis ->
            BufferedReader(InputStreamReader(fis, Charsets.UTF_8)).use { br ->
                JsonReader(br).use { reader ->
                    if (reader.peek() == JsonToken.NULL) {
                        reader.nextNull()
                        return@runCatching null
                    }
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "schemaVersion" -> {
                                schemaVersion = reader.nextInt()
                                if (schemaVersion != SCHEMA_VERSION) return@runCatching null
                            }
                            "languageTag" -> {
                                languageTag = reader.nextString().trim()
                                if (languageTag.isNullOrBlank() ||
                                    languageTag != expectedLanguageTag
                                ) {
                                    return@runCatching null
                                }
                            }
                            "posterProviderToken" -> {
                                cachedPosterToken = reader.nextString().trim()
                                if (cachedPosterToken != posterProviderToken) {
                                    Log.d(
                                        TAG,
                                        "Poster provider changed " +
                                            "($cachedPosterToken -> $posterProviderToken), " +
                                            "invalidating snapshot"
                                    )
                                    return@runCatching null
                                }
                            }
                            "catalogRows" -> {
                                catalogRows = gson.fromJson<List<CatalogRow>>(reader, catalogRowListType)
                                    ?: emptyList()
                            }
                            "fullCatalogRows" -> {
                                fullCatalogRows = gson.fromJson<List<CatalogRow>>(reader, catalogRowListType)
                                    ?: emptyList()
                            }
                            "heroItems" -> {
                                heroItems = gson.fromJson<List<MetaPreview>>(reader, metaPreviewListType)
                                    ?: emptyList()
                            }
                            "orderedGroupKeys" -> {
                                orderedGroupKeys = gson.fromJson<List<String>>(reader, stringListType)
                                    ?: emptyList()
                            }
                            "rails" -> {
                                rails = gson.fromJson<List<Rail>>(reader, railListType)
                                    ?: emptyList()
                            }
                            "heroItemKeys" -> {
                                heroItemKeys = gson.fromJson<List<RailItemKey>>(reader, railItemKeyListType)
                                    ?: emptyList()
                            }
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                }
            }
        }
        Snapshot(
            catalogRows = catalogRows,
            fullCatalogRows = fullCatalogRows,
            heroItems = heroItems,
            orderedGroupKeys = orderedGroupKeys,
            rails = rails,
            heroItemKeys = heroItemKeys
        )
    }.onFailure { error ->
        Log.w(TAG, "Failed to stream-read home snapshot from file", error)
    }.getOrNull()
}
```

- [ ] **Step 4: Compile**

Run: `./gradlew :app:compileUniversalDebugKotlin --max-workers=1 2>&1 | tail -3`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Update unit-test assertions if any reference the skip behavior**

Run: `grep -rn "skipValue\|rails = emptyList\|heroItemKeys = emptyList" app/src/test/java/com/nexio/tv/data/local | head -10`

If any test asserts that `rails`/`heroItemKeys` come back empty after read, those assertions are now wrong — update them or delete (acceptable: behavior was always to round-trip, the skip was transitional).

- [ ] **Step 6: Run snapshot tests**

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "*HomeCatalogSnapshotStore*" --max-workers=1 2>&1 | tail -10`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt
git status -sb
git commit -m "$(cat <<'EOF'
fix(home/snapshot): streamReadSnapshot parses rails + heroItemKeys

Plan B Task 6f.5 prerequisite. The reader was skipping rails +
heroItemKeys (`reader.skipValue()`), so on-disk values never made it back
into memory after restore. 6f.3 shipped eligibility checks that depend
on these fields being populated:

  if (snapshot.rails.isEmpty() && snapshot.heroItemKeys.isEmpty()) return

With the skip, those checks always short-circuited and cold-start
snapshot restore was silently broken. Parse the fields the same way
streamSnapshotToFile writes them (railListType / railItemKeyListType).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 1: Add `ResolvedDisplayItem.toMetaPreview()` adapter

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screens/home/ResolvedDisplayItemToMetaPreview.kt`

Maps the typed authority's item shape back to the legacy `MetaPreview` shape needed by `composeHydratedHomeOverlaySnapshot` (which takes `List<CatalogRow>` with `MetaPreview.items`). Used by `applyHomeSnapshotToUiPipeline` to feed reconstructed rows downstream.

- [ ] **Step 1: Inspect available fields**

Run: `sed -n '1,60p' app/src/main/java/com/nexio/tv/domain/model/ResolvedDisplaySurfaceModels.kt`

Note: `ResolvedDisplayItem` has `display.title`, `display.year`, `display.overview`, `display.runtimeText`, `display.releaseDate`, `artwork: ArtworkBundle`, `itemType: ContentType`, `contentId`, `stableIds: ProviderIds`. No direct poster URL string; `artwork.poster` is `ArtworkDisplayRef?` which needs `.toLegacyArtworkString()`.

Run: `grep -n "fun.*toLegacyArtworkString\|fun ArtworkDisplayRef\?\.toLegacyArtworkString" app/src/main/java/com/nexio/tv/core/artwork | head -5`

Confirm the helper exists. If not, locate the field accessor pattern used in `HomeDisplayMetadata.displayPoster`.

- [ ] **Step 2: Inspect `MetaPreview` constructor**

Run: `sed -n '13,55p' app/src/main/java/com/nexio/tv/domain/model/MetaPreview.kt`

Note required fields (`id`, `type`, `name`, `posterShape`, `imdbRating`, `genres`). All have defaults except `id`, `type`, `name`, `poster`, `posterShape`, `background`, `logo`, `description`, `releaseInfo`, `imdbRating`, `genres`.

- [ ] **Step 3: Write the adapter file**

Create `app/src/main/java/com/nexio/tv/ui/screens/home/ResolvedDisplayItemToMetaPreview.kt`:

```kotlin
package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.artwork.toLegacyArtworkString
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.domain.model.TitleRatingSource

/**
 * Plan B Task 6f.5 — project the typed authority's [ResolvedDisplayItem] back to a
 * [MetaPreview] for legacy consumers ([composeHydratedHomeOverlaySnapshot]) that
 * still operate on [CatalogRow]. Used during snapshot apply to reconstruct
 * [CatalogRow.items] from [Rail.items] (a list of [RailItemKey]) + the typed
 * surface lookup.
 *
 * Field choices match the existing artwork/display fallback chain in
 * [com.nexio.tv.domain.model.HomeDisplayMetadata.displayPoster] etc. — prefer the
 * structured [com.nexio.tv.core.artwork.ArtworkBundle] field, fall back to nothing
 * (legacy reads on `MetaPreview.poster` etc. already tolerate null).
 *
 * The mapping is lossy on purpose: ratings, genres, trailers, language metadata
 * etc. come from downstream enrichment, not the typed surface. Consumers of
 * reconstructed rows must not assume those fields are populated.
 */
internal fun ResolvedDisplayItem.toMetaPreview(): MetaPreview {
    return MetaPreview(
        id = contentId,
        type = itemType,
        name = display.title.orEmpty(),
        poster = artwork.poster?.toLegacyArtworkString(),
        posterShape = PosterShape.POSTER,
        background = artwork.backdrop?.toLegacyArtworkString(),
        logo = artwork.logo?.toLegacyArtworkString(),
        description = display.overview,
        releaseInfo = display.releaseDate ?: display.year?.toString(),
        runtime = display.runtimeText,
        imdbRating = rating?.imdbRating,
        ratingSource = rating?.source ?: TitleRatingSource.IMDB,
        tomatoesRating = display.tomatoesRating,
        genres = display.genres,
        artwork = artwork
    )
}
```

If `rating?.imdbRating` doesn't compile (TitleRating shape differs), inspect:

```bash
grep -n "data class TitleRating\|val imdbRating\|val source" app/src/main/java/com/nexio/tv/domain/model | head -5
```

Adjust the rating field accesses to match. If `rating?.source` is the wrong shape, drop it (default `TitleRatingSource.IMDB`) and add a `// TODO investigate` only if the loss is non-obvious.

- [ ] **Step 4: Compile**

Run: `./gradlew :app:compileUniversalDebugKotlin --max-workers=1 2>&1 | tail -3`

Expected: `BUILD SUCCESSFUL` (additive; no callers yet).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/ResolvedDisplayItemToMetaPreview.kt
git commit -m "$(cat <<'EOF'
feat(home/snapshot): add ResolvedDisplayItem.toMetaPreview adapter

Plan B Task 6f.5 prerequisite. Additive projection from the typed
authority's ResolvedDisplayItem back to the legacy MetaPreview shape,
used during snapshot apply to reconstruct CatalogRow.items from
Rail.items (List<RailItemKey>) + the typed surface lookup.

Lossy on purpose: ratings, genres, trailers, language metadata come from
downstream enrichment, not the typed surface. Consumers of reconstructed
rows must not assume those fields are populated.

No callers yet — Task 6 wires this into applyHomeSnapshotToUiPipeline.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Add `HomeViewModel.currentTypedItemsByKey()` helper

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt` (add internal method)

Single source of truth for the `Map<String, MetaPreview>` lookup that filter helpers + apply pipeline use to reconstruct content from rails.

- [ ] **Step 1: Locate the right injection point**

Run: `grep -n "resolvedDisplaySurfaceRepository\|fun.*: Map<String" app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt | head -10`

Confirm `resolvedDisplaySurfaceRepository` is already an injected field. Pick a location near other lookup helpers (after `_metaByItemKey` declaration is fine).

- [ ] **Step 2: Add the helper**

Add this method to `HomeViewModel` (top-level, not nested inside another helper):

```kotlin
/**
 * Plan B Task 6f.5 — snapshot reconstruction lookup. Returns a content-keyed
 * map of every item currently in the typed authority's home surface, projected
 * back to MetaPreview shape. Used by snapshot apply + filter helpers to
 * reconstruct CatalogRow.items from Rail.items.
 *
 * Reference-stability: callers should treat this as a per-call snapshot. It is
 * not memoized; reconstructing once per apply is acceptable (apply is a
 * low-frequency edge — cold-start restore + producer transient flush).
 */
internal fun currentTypedItemsByKey(): Map<String, MetaPreview> {
    val activeProfileId = profileManager.activeProfileId.value
    val items = resolvedDisplaySurfaceRepository.snapshotNow(activeProfileId)
    if (items.isEmpty()) return emptyMap()
    val out = HashMap<String, MetaPreview>(items.size)
    for (i in items.indices) {
        val item = items[i]
        out[item.itemKey] = item.toMetaPreview()
    }
    return out
}
```

- [ ] **Step 3: Compile**

Run: `./gradlew :app:compileUniversalDebugKotlin --max-workers=1 2>&1 | tail -3`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt
git commit -m "$(cat <<'EOF'
feat(home/snapshot): add HomeViewModel.currentTypedItemsByKey() helper

Plan B Task 6f.5 prerequisite. Single source of truth for the
Map<String, MetaPreview> lookup that filter helpers + apply pipeline use
to reconstruct content from rails (Tasks 3-7). Projects every item
currently in ResolvedDisplaySurfaceRepository.snapshotNow(profileId) via
ResolvedDisplayItem.toMetaPreview().

Not memoized — reconstructing per-apply is acceptable since apply is a
low-frequency edge (cold-start restore + producer transient flush).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Reshape `filterDisabledHomeCatalogRows` to operate on rails

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt:3476-3533`

This filter is currently a private `Snapshot` extension on the receiver. It needs to migrate to rails+heroItemKeys; the predicate `isDisabled(row)` becomes `isDisabledRail(rail)` (structurally identical — checks `addonId` + `catalogId` + slug containment).

- [ ] **Step 1: Read the current implementation**

Run: `sed -n '3476,3535p' app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt`

Note the function takes `isAddonRowDisabled: (CatalogRow) -> Boolean` from the caller. After migration this becomes `(Rail) -> Boolean`. Caller adjustment is in Task 6.

- [ ] **Step 2: Rewrite the function**

Replace the entire function body:

```kotlin
private fun com.nexio.tv.data.local.HomeCatalogSnapshotStore.Snapshot.filterDisabledHomeCatalogRows(
    disabledHomeCatalogKeys: Set<String>,
    isAddonRailDisabled: (com.nexio.tv.domain.model.Rail) -> Boolean
): com.nexio.tv.data.local.HomeCatalogSnapshotStore.Snapshot {
    // Pre-compute non-"custom" disabled slugs ONCE for the whole filter pass.
    // CLAUDE.md hard rule #5: memoize at every reference-fresh boundary.
    val disabledSlugs: Set<String> = if (disabledHomeCatalogKeys.isEmpty()) {
        emptySet()
    } else {
        val out = HashSet<String>(disabledHomeCatalogKeys.size)
        for (k in disabledHomeCatalogKeys) {
            val slug = slugifySyntheticHomeCatalogKey(k)
            if (slug != "custom") out += slug
        }
        out
    }

    fun isDisabled(rail: com.nexio.tv.domain.model.Rail): Boolean {
        return when (rail.addonId) {
            TRAKT_RAIL_ADDON_ID,
            SIMKL_RAIL_ADDON_ID,
            MDBLIST_RAIL_ADDON_ID,
            TMDB_RAIL_ADDON_ID -> {
                if (isSyntheticHomeCatalogDisabled(rail.catalogId, disabledHomeCatalogKeys)) return true
                if (isSyntheticHomeCatalogDisabled(homeCatalogGlobalKey(rail), disabledHomeCatalogKeys)) return true
                if (disabledSlugs.isEmpty()) return false
                val railCatalogIdLower = rail.catalogId.lowercase()
                disabledSlugs.any { slug -> railCatalogIdLower.contains(slug) }
            }
            else -> isAddonRailDisabled(rail)
        }
    }

    val filteredRails = rails.filterNot(::isDisabled)
    if (filteredRails.size == rails.size) {
        return this
    }

    val retainedItemKeys = filteredRails
        .asSequence()
        .flatMap { rail -> rail.items.asSequence() }
        .map { key -> "${key.apiType}:${key.contentId}" }
        .toSet()
    return copy(
        rails = filteredRails,
        heroItemKeys = heroItemKeys.filter { key ->
            "${key.apiType}:${key.contentId}" in retainedItemKeys
        },
        orderedGroupKeys = orderedGroupKeys.filterNot { key ->
            isSyntheticHomeCatalogDisabled(key, disabledHomeCatalogKeys)
        }
    )
}
```

- [ ] **Step 3: Compile (will fail at caller)**

Run: `./gradlew :app:compileUniversalDebugKotlin --max-workers=1 2>&1 | grep "^e:" | head -3`

Expected: one or more errors at the caller in `applyHomeSnapshotToUiPipeline` because the lambda parameter shape changed. Defer fixing — Task 6 rewrites the caller.

- [ ] **Step 4: DO NOT commit yet — gated on Task 6**

Move on to Task 4. The compile failure is OK because Tasks 4, 5, 6 will land together.

---

## Task 4: Reshape `filterRestoredHomeSnapshotTmdbRows` to operate purely on rails

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt:3282-3361`

Drop all legacy field reads from the function body. Predicate operates on `Rail`; `removedTmdbKeys`/`retainedItemKeys` derive from rails. Legacy fields stop being filtered (will be dropped from `Snapshot` in Task 9).

- [ ] **Step 1: Read the current implementation**

Run: `sed -n '3282,3361p' app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt`

Note 6f.2 kept legacy field filtering for transitional safety. This task removes that.

- [ ] **Step 2: Rewrite the function**

Replace the entire function body:

```kotlin
internal fun filterRestoredHomeSnapshotTmdbRows(
    snapshot: com.nexio.tv.data.local.HomeCatalogSnapshotStore.Snapshot,
    tmdbPrefs: TmdbCatalogPreferences,
    tmdbSnapshot: com.nexio.tv.data.repository.TmdbDiscoverySnapshot,
    currentSyntheticTmdbGroups: List<PersistedSyntheticCatalogGroup> = emptyList()
): com.nexio.tv.data.local.HomeCatalogSnapshotStore.Snapshot {
    val currentTmdbCatalogIds = currentTmdbCatalogIds(
        tmdbPrefs = tmdbPrefs,
        tmdbSnapshot = tmdbSnapshot,
        currentSyntheticTmdbGroups = currentSyntheticTmdbGroups
    )

    fun isRetained(rail: com.nexio.tv.domain.model.Rail): Boolean {
        return rail.addonId != TMDB_RAIL_ADDON_ID || rail.catalogId in currentTmdbCatalogIds
    }

    val filteredRails = snapshot.rails.filter(::isRetained)
    if (filteredRails.size == snapshot.rails.size) {
        return snapshot
    }

    val removedTmdbKeys = snapshot.rails
        .asSequence()
        .filterNot(::isRetained)
        .filter { rail -> rail.addonId == TMDB_RAIL_ADDON_ID }
        .flatMap { rail -> sequenceOf(rail.catalogId, homeCatalogGlobalKey(rail)) }
        .toSet()
    val removedTmdbItemKeys = snapshot.rails
        .asSequence()
        .filterNot(::isRetained)
        .filter { rail -> rail.addonId == TMDB_RAIL_ADDON_ID }
        .flatMap { rail -> rail.items.asSequence() }
        .map { key -> "${key.apiType}:${key.contentId}" }
        .toSet()
    val retainedItemKeys = filteredRails
        .asSequence()
        .flatMap { rail -> rail.items.asSequence() }
        .map { key -> "${key.apiType}:${key.contentId}" }
        .toSet()
    val retainedCurrentTmdbItemKeys = filteredRails
        .asSequence()
        .filter { rail -> rail.addonId == TMDB_RAIL_ADDON_ID }
        .flatMap { rail -> rail.items.asSequence() }
        .map { key -> "${key.apiType}:${key.contentId}" }
        .toSet()

    return snapshot.copy(
        rails = filteredRails,
        heroItemKeys = snapshot.heroItemKeys.filter { key ->
            val asStr = "${key.apiType}:${key.contentId}"
            asStr in retainedItemKeys &&
                (asStr !in removedTmdbItemKeys || asStr in retainedCurrentTmdbItemKeys)
        },
        orderedGroupKeys = snapshot.orderedGroupKeys.filterNot { key -> key in removedTmdbKeys }
    )
}
```

- [ ] **Step 3: DO NOT commit yet** (still gated on Task 6 caller migration).

---

## Task 5: Reshape `filterRestoredHomeSnapshotKitsuRows` to operate purely on rails

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt:3363-3438`

Same shape as Task 4 with `KITSU_HOME_ADDON_ID` and `kitsuPrefs`/`kitsuSnapshot`.

- [ ] **Step 1: Rewrite the function**

Replace the entire function body:

```kotlin
internal fun filterRestoredHomeSnapshotKitsuRows(
    snapshot: com.nexio.tv.data.local.HomeCatalogSnapshotStore.Snapshot,
    kitsuPrefs: KitsuCatalogPreferences,
    kitsuSnapshot: com.nexio.tv.data.repository.KitsuDiscoverySnapshot,
    currentSyntheticKitsuGroups: List<PersistedSyntheticCatalogGroup> = emptyList()
): com.nexio.tv.data.local.HomeCatalogSnapshotStore.Snapshot {
    val currentKitsuCatalogIds = currentKitsuCatalogIds(
        kitsuPrefs = kitsuPrefs,
        kitsuSnapshot = kitsuSnapshot,
        currentSyntheticKitsuGroups = currentSyntheticKitsuGroups
    )

    fun isRetained(rail: com.nexio.tv.domain.model.Rail): Boolean {
        return rail.addonId != KITSU_HOME_ADDON_ID || rail.catalogId in currentKitsuCatalogIds
    }

    val filteredRails = snapshot.rails.filter(::isRetained)
    if (filteredRails.size == snapshot.rails.size) {
        return snapshot
    }

    val removedKitsuKeys = snapshot.rails
        .asSequence()
        .filterNot(::isRetained)
        .filter { rail -> rail.addonId == KITSU_HOME_ADDON_ID }
        .flatMap { rail -> sequenceOf(rail.catalogId, homeCatalogGlobalKey(rail)) }
        .toSet()
    val removedKitsuItemKeys = snapshot.rails
        .asSequence()
        .filterNot(::isRetained)
        .filter { rail -> rail.addonId == KITSU_HOME_ADDON_ID }
        .flatMap { rail -> rail.items.asSequence() }
        .map { key -> "${key.apiType}:${key.contentId}" }
        .toSet()
    val retainedItemKeys = filteredRails
        .asSequence()
        .flatMap { rail -> rail.items.asSequence() }
        .map { key -> "${key.apiType}:${key.contentId}" }
        .toSet()
    val retainedCurrentKitsuItemKeys = filteredRails
        .asSequence()
        .filter { rail -> rail.addonId == KITSU_HOME_ADDON_ID }
        .flatMap { rail -> rail.items.asSequence() }
        .map { key -> "${key.apiType}:${key.contentId}" }
        .toSet()

    return snapshot.copy(
        rails = filteredRails,
        heroItemKeys = snapshot.heroItemKeys.filter { key ->
            val asStr = "${key.apiType}:${key.contentId}"
            asStr in retainedItemKeys &&
                (asStr !in removedKitsuItemKeys || asStr in retainedCurrentKitsuItemKeys)
        },
        orderedGroupKeys = snapshot.orderedGroupKeys.filterNot { key -> key in removedKitsuKeys }
    )
}
```

- [ ] **Step 2: DO NOT commit yet** (still gated on Task 6 caller migration).

---

## Task 6: Reshape `applyHomeSnapshotToUiPipeline` to reconstruct rows from rails

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt:3190-3222`

The apply pipeline currently reads `filteredSnapshot.catalogRows`/`fullCatalogRows`/`heroItems` to feed `composeHydratedHomeOverlaySnapshot`. After this task, reconstruct via `Rail.toCatalogRowOrNull(typedItemsByKey)` + `Snapshot.reconstructHeroItems(typedItemsByKey)`. Also flip the `filterDisabledHomeCatalogRows` lambda from `(CatalogRow) -> Boolean` to `(Rail) -> Boolean`.

- [ ] **Step 1: Read the current implementation**

Run: `sed -n '3190,3245p' app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt`

- [ ] **Step 2: Rewrite the function header through the `composedSnapshot` call**

Replace lines 3190-3222 with:

```kotlin
internal fun HomeViewModel.applyHomeSnapshotToUiPipeline(
    snapshot: com.nexio.tv.data.local.HomeCatalogSnapshotStore.Snapshot
) {
    val builtInSafeSnapshot = filterRestoredHomeSnapshotKitsuRows(
        snapshot = filterRestoredHomeSnapshotTmdbRows(
            snapshot = snapshot,
            tmdbPrefs = tmdbCatalogPreferences,
            tmdbSnapshot = tmdbDiscoverySnapshot,
            currentSyntheticTmdbGroups = persistedTmdbSyntheticGroupsMatchingPreferences(tmdbCatalogPreferences)
        ),
        kitsuPrefs = kitsuCatalogPreferences,
        kitsuSnapshot = kitsuDiscoverySnapshot,
        currentSyntheticKitsuGroups = persistedKitsuSyntheticGroupsMatchingPreferences(kitsuCatalogPreferences)
    )
    val filteredSnapshot = builtInSafeSnapshot.filterDisabledHomeCatalogRows(
        disabledHomeCatalogKeys = disabledHomeCatalogKeys,
        isAddonRailDisabled = { rail ->
            isCatalogDisabled(
                addonBaseUrl = rail.addonBaseUrl,
                addonId = rail.addonId,
                type = rail.apiType,
                catalogId = rail.catalogId,
                catalogName = rail.catalogName
            )
        }
    )

    // Plan B Task 6f.5 — reconstruct CatalogRow content from rails via the
    // typed surface lookup. The typed authority is populated synchronously
    // before this function runs (restorePersistedCatalogSnapshotPipeline does
    // `resolvedDisplaySurfaceRepository.restoreFromDisk(...)` immediately
    // before `withContext(Main.immediate) { applyPending... }`). Empty
    // reconstruction is acceptable degradation: home renders empty briefly,
    // the next producer emission populates everything.
    val typedItemsByKey = currentTypedItemsByKey()
    val reconstructedDisplayRows = filteredSnapshot.rails.mapNotNull { rail ->
        rail.toCatalogRowOrNull(typedItemsByKey)
    }
    val reconstructedFullRows = reconstructedDisplayRows  // 6f.5: no separate fullRows persisted
    val reconstructedHeroItems = filteredSnapshot.reconstructHeroItems(typedItemsByKey)

    val composedSnapshot = composeHydratedHomeOverlaySnapshot(
        displayRows = reconstructedDisplayRows,
        fullRows = reconstructedFullRows,
        heroItems = reconstructedHeroItems,
        overlaysByItemKey = hydratedHomeOverlaysByItemKey.value,
        heroTmdbSettings = currentTmdbSettings
    )
```

Note: `reconstructedFullRows = reconstructedDisplayRows` because Task 9 drops `fullCatalogRows` from the persisted shape — only one row representation survives. If a downstream consumer truly needs a distinct "full" projection, that's a separate spec bug; verify in Step 4 below by grepping for `composedSnapshot.fullRows`.

- [ ] **Step 3: Update `SnapshotContentLookup.reconstructHeroItems` to drop the legacy fallback**

Since `Snapshot.heroItems` will be gone in Task 9, the fallback `keys.ifEmpty { return heroItems }` becomes a compile error. Edit `app/src/main/java/com/nexio/tv/ui/screens/home/SnapshotContentLookup.kt`:

```kotlin
internal fun Snapshot.reconstructHeroItems(
    typedItemsByKey: Map<String, MetaPreview>
): List<MetaPreview> {
    val keys = heroItemKeys
    if (keys.isEmpty()) return emptyList()
    val out = ArrayList<MetaPreview>(keys.size)
    for (i in keys.indices) {
        typedItemsByKey[keys[i].key]?.let(out::add)
    }
    return out
}
```

- [ ] **Step 4: Verify `composedSnapshot.fullRows` semantics**

Run: `grep -n "composedSnapshot\.fullRows\|composedSnapshot\.displayRows" app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt`

Confirm `composedSnapshot.fullRows` is published to `catalogInventoryRepository` and read by `observeHydratedHomeOverlaysForRows(displayRows + fullRows + ...)`. Both `displayRows` and `fullRows` flow through `composeHydratedHomeOverlaySnapshot` whose signature accepts them separately. By passing the same list for both we're saying snapshot-restore has no separate "full" projection (the producer pipeline at line 2983 still gets distinct lists because it computes them in-memory).

Confirm this matches reality: search for any consumer that compares `displayRows` and `fullRows` to derive truncation state. If any do, fall back to `reconstructedFullRows = reconstructedDisplayRows` is safe (they were identical content pre-truncation anyway after 6f.5 since rails carry all items).

- [ ] **Step 5: Compile (all of Tasks 3-6 together)**

Run: `./gradlew :app:compileUniversalDebugKotlin --max-workers=1 2>&1 | tail -3`

Expected: `BUILD SUCCESSFUL`. If errors, fix the specific call sites flagged by the compiler.

- [ ] **Step 6: Run snapshot-related unit tests**

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "*HomeViewModelCatalogPipeline*" --tests "*HomeCatalogSnapshot*" --tests "*ApplyHomeSnapshot*" --max-workers=1 2>&1 | tail -10`

Expected: `BUILD SUCCESSFUL`. If failures, inspect — most likely fixture mismatches (Task 12 will fix the rest).

- [ ] **Step 7: Commit Tasks 3-6 together**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt \
        app/src/main/java/com/nexio/tv/ui/screens/home/SnapshotContentLookup.kt
git commit -m "$(cat <<'EOF'
refactor(home/snapshot): apply pipeline reconstructs from rails + typed surface

Plan B Task 6f.5 phase 1 — Tasks 3-6 land together because the three
filter helpers and applyHomeSnapshotToUiPipeline must change in lockstep
(predicate parameter type flips from CatalogRow to Rail).

- filterDisabledHomeCatalogRows now takes (Rail) -> Boolean
- filterRestoredHomeSnapshotTmdbRows / Kitsu operate purely on
  snapshot.rails + heroItemKeys (legacy field reads gone)
- applyHomeSnapshotToUiPipeline reconstructs displayRows/heroItems via
  Rail.toCatalogRowOrNull(typedItemsByKey) + reconstructHeroItems
  (typed surface is populated synchronously before apply runs)
- SnapshotContentLookup.reconstructHeroItems drops the heroItems fallback
  (Task 9 deletes the field)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: Migrate producer-path `transientSnapshot` construction

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt:3049-3055`

After Task 9, `Snapshot(catalogRows = ..., fullCatalogRows = ..., heroItems = ..., orderedGroupKeys = ...)` won't compile. The producer must construct with `rails` + `heroItemKeys`.

- [ ] **Step 1: Read the current producer-path construction**

Run: `sed -n '3045,3070p' app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt`

Note `displayRows`, `fullRowsFiltered`, `baseHeroItems` are `List<CatalogRow>` / `List<MetaPreview>` at this point (in-memory producer output).

- [ ] **Step 2: Rewrite construction to use rails + heroItemKeys**

Replace lines 3049-3055:

```kotlin
        val transientRails: List<com.nexio.tv.domain.model.Rail> = run {
            // Mirror buildRailMemberships' row dedupe (fullRows wins; displayRows fills gaps).
            val merged = linkedMapOf<String, com.nexio.tv.domain.model.CatalogRow>()
            for (i in fullRowsFiltered.indices) {
                val row = fullRowsFiltered[i]
                merged[row.catalogId] = row
            }
            for (i in displayRows.indices) {
                val row = displayRows[i]
                merged.putIfAbsent(row.catalogId, row)
            }
            val out = ArrayList<com.nexio.tv.domain.model.Rail>(merged.size)
            for (row in merged.values) out += row.toRail()
            out
        }
        val transientHeroItemKeys = ArrayList<com.nexio.tv.domain.model.RailItemKey>(baseHeroItems.size).apply {
            for (i in baseHeroItems.indices) {
                val item = baseHeroItems[i]
                add(com.nexio.tv.domain.model.RailItemKey(apiType = item.apiType, contentId = item.id))
            }
        }
        val transientSnapshot = com.nexio.tv.data.local.HomeCatalogSnapshotStore.Snapshot(
            orderedGroupKeys = orderedGroupKeys,
            rails = transientRails,
            heroItemKeys = transientHeroItemKeys
        )
        applyHomeSnapshotToUiPipeline(transientSnapshot)
```

After Task 9 this construction works because `catalogRows`/`fullCatalogRows`/`heroItems` are gone. Until Task 9 lands the construction will still compile because those legacy fields default to `emptyList()`.

- [ ] **Step 3: Compile**

Run: `./gradlew :app:compileUniversalDebugKotlin --max-workers=1 2>&1 | tail -3`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt
git commit -m "$(cat <<'EOF'
refactor(home/snapshot): producer-path transientSnapshot uses rails + heroItemKeys

Plan B Task 6f.5 phase 2. The producer pipeline's transient snapshot
construction at updateCatalogRowsPipeline now feeds applyHomeSnapshot
ToUiPipeline through the same shape as cold-start restore: rails
(derived from displayRows+fullRowsFiltered with fullRows-wins dedupe)
and heroItemKeys (derived from baseHeroItems).

Apply then reconstructs CatalogRow content via the typed surface lookup
(Task 6). Both code paths converge on the rails representation.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: Audit remaining legacy field readers

**Files:**
- Read-only verification across `app/src/main/java/com/nexio/tv`.

Before dropping the fields, confirm no other reader exists.

- [ ] **Step 1: Run the grep gate**

```bash
grep -rn "\.catalogRows\b\|\.fullCatalogRows\b\|\.heroItems\b" app/src/main/java/com/nexio/tv --include="*.kt" \
  | grep -v "HomeCatalogSnapshotStore\.kt\|ContinueWatching\|ModernHomePresentation\|ClassicHomePresentation\|SearchViewModel\|SearchScreen\|//\|@deprecated\|^Binary" \
  | head -20
```

Note expected non-Snapshot hits to filter out:
- `ContinueWatching*` (`ContinueWatchingItem.heroItems` doesn't exist; the grep matches `.heroItemsTime` or similar — verify)
- `Modern/ClassicHomePresentation.kt` (`input.catalogRows` is a producer-state shape, NOT `Snapshot.catalogRows`)
- `SearchViewModel.kt` / `SearchScreen.kt` (`uiState.catalogRows` is `SearchUiState.catalogRows`, NOT `Snapshot.catalogRows`)

Inspect each remaining hit. If any reads `HomeCatalogSnapshotStore.Snapshot.catalogRows` (or the others), migrate it inline before proceeding to Task 9.

- [ ] **Step 2: Run the same grep against tests**

```bash
grep -rn "Snapshot(\s*catalogRows\|\.catalogRows\b\|\.fullCatalogRows\b\|\.heroItems\b" app/src/test/java/com/nexio/tv --include="*.kt" | head -20
```

Note the hits — Task 12 will update them. No action here.

- [ ] **Step 3: No commit; this is a verification gate.**

If hits exist outside the expected list, STOP and write a follow-up task to migrate them. Do not proceed to Task 9 with unmigrated readers.

---

## Task 9: Drop legacy fields from `Snapshot` data class

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt:145-152`

- [ ] **Step 1: Read the current data class**

Run: `sed -n '140,153p' app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt`

- [ ] **Step 2: Drop the three fields**

Replace lines 145-152 with:

```kotlin
    data class Snapshot(
        val orderedGroupKeys: List<String> = emptyList(),
        val rails: List<Rail> = emptyList(),
        val heroItemKeys: List<RailItemKey> = emptyList()
    )
```

Also update the doc comment immediately above (around line 130-144) to drop references to the legacy fields. Replace mentions of "catalogRows / fullCatalogRows / heroItems" with "rails / heroItemKeys".

- [ ] **Step 3: Compile (will fail at writer + reader + tests)**

Run: `./gradlew :app:compileUniversalDebugKotlin --max-workers=1 2>&1 | grep "^e:" | head -20`

Expect failures at:
- `streamReadSnapshot` (still has `catalogRows`/`fullCatalogRows`/`heroItems` locals + `Snapshot(catalogRows = ..., ...)` construction)
- `streamSnapshotToFile` (still writes the legacy fields)
- `capForPersist` (if it reads legacy fields)
- `decodeSnapshot` (legacy migration path)

Don't commit yet — Tasks 10, 11 fix the writer/reader. Test fixtures (Task 12) come last.

---

## Task 10: Update `streamReadSnapshot` to stop tracking legacy locals

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt:307-396`

- [ ] **Step 1: Drop the three legacy locals + their parse cases + the Snapshot construction**

Replace the function with:

```kotlin
private fun streamReadSnapshot(file: File, posterProviderToken: String): Snapshot? {
    val expectedLanguageTag = currentLanguageTag()
    var schemaVersion: Int = -1
    var languageTag: String? = null
    var cachedPosterToken: String? = null
    var orderedGroupKeys: List<String> = emptyList()
    var rails: List<Rail> = emptyList()
    var heroItemKeys: List<RailItemKey> = emptyList()

    return runCatching {
        FileInputStream(file).use { fis ->
            BufferedReader(InputStreamReader(fis, Charsets.UTF_8)).use { br ->
                JsonReader(br).use { reader ->
                    if (reader.peek() == JsonToken.NULL) {
                        reader.nextNull()
                        return@runCatching null
                    }
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "schemaVersion" -> {
                                schemaVersion = reader.nextInt()
                                if (schemaVersion != SCHEMA_VERSION) return@runCatching null
                            }
                            "languageTag" -> {
                                languageTag = reader.nextString().trim()
                                if (languageTag.isNullOrBlank() ||
                                    languageTag != expectedLanguageTag
                                ) {
                                    return@runCatching null
                                }
                            }
                            "posterProviderToken" -> {
                                cachedPosterToken = reader.nextString().trim()
                                if (cachedPosterToken != posterProviderToken) {
                                    Log.d(
                                        TAG,
                                        "Poster provider changed " +
                                            "($cachedPosterToken -> $posterProviderToken), " +
                                            "invalidating snapshot"
                                    )
                                    return@runCatching null
                                }
                            }
                            "orderedGroupKeys" -> {
                                orderedGroupKeys = gson.fromJson<List<String>>(reader, stringListType)
                                    ?: emptyList()
                            }
                            "rails" -> {
                                rails = gson.fromJson<List<Rail>>(reader, railListType)
                                    ?: emptyList()
                            }
                            "heroItemKeys" -> {
                                heroItemKeys = gson.fromJson<List<RailItemKey>>(reader, railItemKeyListType)
                                    ?: emptyList()
                            }
                            // Legacy fields persisted by schema <=v5 writers (pre-Plan B 6f.5).
                            // Skip — the rails + heroItemKeys derived from them during the v4->v5
                            // writer migration are authoritative on disk now.
                            "catalogRows", "fullCatalogRows", "heroItems" -> reader.skipValue()
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                }
            }
        }
        Snapshot(
            orderedGroupKeys = orderedGroupKeys,
            rails = rails,
            heroItemKeys = heroItemKeys
        )
    }.onFailure { error ->
        Log.w(TAG, "Failed to stream-read home snapshot from file", error)
    }.getOrNull()
}
```

- [ ] **Step 2: Verify `capForPersist` doesn't reference legacy fields**

Run: `grep -n "fun capForPersist\|capForPersist" app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt | head -3`

Inspect the function body. If it caps `catalogRows`/`fullCatalogRows`/`heroItems`, drop those caps. If it has cap logic for `rails`/`heroItemKeys`, keep that.

```bash
sed -n '<capForPersist start>,<capForPersist end>p' app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt
```

If the function only operated on legacy fields, the function may become a no-op — delete it and its callers. If it's mixed, keep the rails/heroItemKeys caps and remove the legacy.

- [ ] **Step 3: Do not commit yet** — writer and test fixtures still broken.

---

## Task 11: Update `streamSnapshotToFile` + `decodeSnapshot` to stop writing legacy fields

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt:429-495` (writer), `:585-630` approx (decodeSnapshot)

- [ ] **Step 1: Read the current writer**

Run: `sed -n '429,495p' app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt`

Note: the derive-from-legacy `railsForPersist = snapshot.rails.ifEmpty { /* derive from catalogRows */ }` is no longer reachable (snapshot has no `catalogRows`). Simplify to just `snapshot.rails` / `snapshot.heroItemKeys`.

- [ ] **Step 2: Rewrite the writer**

Replace lines 429-495 with:

```kotlin
private fun streamSnapshotToFile(
    snapshot: Snapshot,
    schemaVersion: Int,
    languageEpoch: Int,
    languageTag: String,
    posterProviderToken: String,
    target: File
) {
    var tempFile: File? = null
    try {
        val parent = target.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        tempFile = File(parent ?: File("."), "${target.name}.tmp")

        FileOutputStream(tempFile).use { fos ->
            BufferedWriter(OutputStreamWriter(fos, Charsets.UTF_8)).use { bw ->
                JsonWriter(bw).use { writer ->
                    writer.beginObject()
                    writer.name("schemaVersion").value(schemaVersion)
                    writer.name("languageEpoch").value(languageEpoch)
                    writer.name("languageTag").value(languageTag)
                    writer.name("posterProviderToken").value(posterProviderToken)
                    writer.name("orderedGroupKeys")
                    gson.toJson(snapshot.orderedGroupKeys, stringListType, writer)
                    writer.name("rails")
                    gson.toJson(snapshot.rails, railListType, writer)
                    writer.name("heroItemKeys")
                    gson.toJson(snapshot.heroItemKeys, railItemKeyListType, writer)
                    writer.endObject()
                }
            }
        }
        // ... atomic move (preserve existing code below line 495)
```

Preserve the atomic-move code that follows the JSON write (`Files.move(tempFile.toPath(), ...)` etc.).

- [ ] **Step 3: Update `decodeSnapshot` legacy migration path**

Run: `sed -n '580,640p' app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt`

This function is the one-time SharedPreferences→file migration. Inspect:
- If it reads legacy fields and constructs a Snapshot, it must now derive rails+heroItemKeys from the legacy data the same way the old writer did.
- Simpler alternative: return `null` from `decodeSnapshot` if the on-disk data is legacy (force a fresh fetch from network). Acceptable since this is a one-time migration path that triggers only for users on schema <=v3.

Recommended: return null. Replace the construction at the end of `decodeSnapshot` (the legacy → Snapshot conversion) with:

```kotlin
// Plan B Task 6f.5 — legacy SharedPreferences payloads pre-date the rails
// representation. Rather than back-derive rails+heroItemKeys here (which
// would require reading catalogRows/fullCatalogRows/heroItems we no longer
// model), return null to force a fresh catalog fetch. Migration is one-shot
// and the user perceives at most one extra refresh.
return null
```

Update the function comment block to reflect this. Drop any locals (`catalogRows`, `fullCatalogRows`, `heroItems`) that the function no longer constructs.

- [ ] **Step 4: Compile**

Run: `./gradlew :app:compileUniversalDebugKotlin --max-workers=1 2>&1 | grep "^e:" | head -10`

Expected: zero errors in `main/`. Test errors (`app/src/test`) are still expected — Task 12 fixes them.

- [ ] **Step 5: Commit Tasks 9-11 together**

```bash
git add app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt
git commit -m "$(cat <<'EOF'
refactor(home/snapshot): drop catalogRows/fullCatalogRows/heroItems

Plan B Task 6f.5 phase 3 — drop the denormalized legacy fields from the
Snapshot data class and stop writing them to disk. rails + heroItemKeys
are now the sole representation; row content is reconstructed on-demand
via SnapshotContentLookup at apply time.

- Snapshot data class loses catalogRows/fullCatalogRows/heroItems
- streamSnapshotToFile writes only orderedGroupKeys/rails/heroItemKeys
  (gson tree allocation for ~7.6MB legacy catalogRows JSON is eliminated)
- streamReadSnapshot drops the three legacy field locals; legacy field
  names persisted by pre-6f.5 writers are skipped on read (rails +
  heroItemKeys are derived during the v4->v5 writer migration)
- decodeSnapshot legacy-SharedPreferences path returns null (forces fresh
  fetch; one-shot, perceived as at most one extra refresh)
- capForPersist legacy caps removed (rails/heroItemKey caps retained)

On-disk file size expected to drop from 1.06 MB (post 6e) to 100-300 KB
(structure-only).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 12: Update test fixtures

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/data/local/HomeCatalogSnapshotStoreTest.kt`

After Tasks 9-11, any test that constructs `Snapshot(catalogRows = ..., ...)` fails to compile. Migrate fixtures to construct via rails + heroItemKeys.

- [ ] **Step 1: List all affected test files**

Run: `grep -rn "Snapshot(\s*catalogRows\|catalogRows = \|fullCatalogRows = \|heroItems = " app/src/test/java/com/nexio/tv --include="*.kt"`

For each fixture site, rewrite to use rails+heroItemKeys:

```kotlin
// Before:
val s = HomeCatalogSnapshotStore.Snapshot(
    catalogRows = listOf(row1, row2),
    fullCatalogRows = listOf(row1, row2),
    heroItems = listOf(hero1),
    orderedGroupKeys = listOf("k1", "k2")
)

// After:
val s = HomeCatalogSnapshotStore.Snapshot(
    orderedGroupKeys = listOf("k1", "k2"),
    rails = listOf(row1.toRail(), row2.toRail()),
    heroItemKeys = listOf(RailItemKey(apiType = hero1.apiType, contentId = hero1.id))
)
```

- [ ] **Step 2: Update assertions that read legacy fields**

Run: `grep -rn "\.catalogRows\b\|\.fullCatalogRows\b\|\.heroItems\b" app/src/test/java/com/nexio/tv --include="*.kt" | grep -v "//"`

For each assertion site, switch to the equivalent rails+heroItemKeys check. If a test asserted on row-item content from `snapshot.catalogRows[0].items[0]`, the test should switch to asserting on `snapshot.rails[0].items[0]` (which is a RailItemKey, not a MetaPreview) OR drop that assertion entirely if it was checking content the typed surface now owns.

- [ ] **Step 3: Compile tests**

Run: `./gradlew :app:compileUniversalDebugUnitTestKotlin --max-workers=1 2>&1 | tail -3`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Run the affected test suites**

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "*HomeCatalogSnapshot*" --tests "*HomeViewModelCatalogPipeline*" --tests "*ApplyHomeSnapshot*" --tests "*Search*" --max-workers=1 2>&1 | tail -15`

Expected: `BUILD SUCCESSFUL`. If failures, fix the specific fixture/assertion mismatch.

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/com/nexio/tv
git commit -m "$(cat <<'EOF'
test(home/snapshot): migrate fixtures to rails + heroItemKeys

Plan B Task 6f.5 phase 4. Snapshot fixtures and assertions in
HomeCatalogSnapshotStoreTest (and any other snapshot-reading tests)
flip from catalogRows/fullCatalogRows/heroItems to rails +
heroItemKeys. Content-shape assertions (`snapshot.catalogRows[0].items[0].title`)
that were really verifying typed-authority behavior are dropped — the
typed surface owns that data now.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 13: On-device verification

**Files:**
- None (verification only).

Two-session cold-start test on the test TV. Confirms snapshot persist + restore roundtrip, snapshot file shrinks, no ANR, home renders within 30s.

- [ ] **Step 1: Confirm device reachable**

Run: `adb -s 192.168.50.98:5555 shell echo ok`

Expected: `ok`. If unreachable, reconnect via `adb connect 192.168.50.98:5555` and retry.

- [ ] **Step 2: Install the new build**

Run: `./gradlew :app:installUniversalDebug --max-workers=1 2>&1 | tail -3`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Session 1 — fresh persist (wipe snapshot)**

```bash
adb -s 192.168.50.98:5555 shell run-as com.nexiodebug.tv rm -rf /data/data/com.nexiodebug.tv/files/home-catalog-snapshot-v1
adb -s 192.168.50.98:5555 shell am force-stop com.nexiodebug.tv
adb -s 192.168.50.98:5555 logcat -c
adb -s 192.168.50.98:5555 shell monkey -p com.nexiodebug.tv 1
sleep 8
adb -s 192.168.50.98:5555 shell input keyevent KEYCODE_DPAD_CENTER
sleep 90
```

The 8s wait lets the profile picker render; `KEYCODE_DPAD_CENTER` selects a profile (CLAUDE.md hard rule #8 — smoke tests need profile selection); the 90s soak gives home time to load rails and persist the snapshot.

- [ ] **Step 4: Verify snapshot file size**

```bash
adb -s 192.168.50.98:5555 shell run-as com.nexiodebug.tv ls -la /data/data/com.nexiodebug.tv/files/home-catalog-snapshot-v1/
```

Expected: a `p<profileId>_*.json` file. Size should be 100-300 KB (was 1.06 MB after 6e — structure-only is dramatically smaller).

- [ ] **Step 5: Verify JSON has no legacy fields**

```bash
adb -s 192.168.50.98:5555 shell run-as com.nexiodebug.tv cat /data/data/com.nexiodebug.tv/files/home-catalog-snapshot-v1/p*_*.json \
  | python3 -c "import sys, json; s = json.load(sys.stdin); print('keys:', sorted(s.keys()))"
```

Expected output (alphabetical):
```
keys: ['heroItemKeys', 'languageEpoch', 'languageTag', 'orderedGroupKeys', 'posterProviderToken', 'rails', 'schemaVersion']
```

No `catalogRows`, `fullCatalogRows`, or `heroItems`. If any appear, the writer is still emitting them — re-check Task 11.

- [ ] **Step 6: Verify Session 1 logcat shows no FATAL/ANR**

```bash
adb -s 192.168.50.98:5555 logcat -d -t 5000 \
  | grep -E "FATAL|ANR in com\.nexiodebug|Restored merged home snapshot|Persisted merged home snapshot write" \
  | head -20
```

Expected:
- `Persisted merged home snapshot write rails=N hero=M orderedKeys=K` line present, no zeros for `rails`/`hero`.
- No `FATAL`, no `ANR in com.nexiodebug`.

- [ ] **Step 7: Session 2 — warm restore**

```bash
adb -s 192.168.50.98:5555 shell am force-stop com.nexiodebug.tv
adb -s 192.168.50.98:5555 logcat -c
adb -s 192.168.50.98:5555 shell monkey -p com.nexiodebug.tv 1
sleep 8
adb -s 192.168.50.98:5555 shell input keyevent KEYCODE_DPAD_CENTER
sleep 30
```

- [ ] **Step 8: Verify restore log + no ANR**

```bash
adb -s 192.168.50.98:5555 logcat -d -t 5000 \
  | grep -E "FATAL|ANR in com\.nexiodebug|Restored merged home snapshot|Persisted snapshot applied" \
  | head -20
```

Expected:
- `Restored merged home snapshot rails=N hero=M orderedKeys=K` with `N>0` (restore actually parsed rails).
- `Persisted snapshot applied orderedKeys=K expected=K sourceCachesReady=true rails=N hero=M`.
- No `FATAL`, no `ANR`.

- [ ] **Step 9: Verify home renders (manual visual)**

The user (or operator) should observe the TV: home screen visible within 30s of profile selection in Session 2, rails populated with posters, hero carousel populated. If home stays empty past 30s, restore reconstruction is producing empty rows — investigate `currentTypedItemsByKey()` return value at apply time.

- [ ] **Step 10: Capture a heap dump for MetaPreview retention check**

```bash
PID=$(adb -s 192.168.50.98:5555 shell pidof com.nexiodebug.tv)
adb -s 192.168.50.98:5555 shell am dumpheap "$PID" /data/local/tmp/heap-6f5.hprof
adb -s 192.168.50.98:5555 pull /data/local/tmp/heap-6f5.hprof /tmp/heap-6f5.hprof
heaptrail -i /tmp/heap-6f5.hprof -t 10 | grep -E "MetaPreview|CatalogRow|Rail\b" | head -5
```

Compare against the Phase 2C ext heap milestone (MetaPreview −96.3% / CatalogRow −97.0% vs baseline; the 6e final showed ~1400 MetaPreview / 0 CatalogRow). Expected after 6f.5: MetaPreview further reduced (no more legacy field retention through Snapshot), CatalogRow either same or zero.

If MetaPreview count is HIGHER than 6e baseline, investigate — most likely `currentTypedItemsByKey()` is being called more often than expected or reconstruction is allocating unnecessarily.

---

## Task 14: Final commit + task list cleanup + push

**Files:**
- None (housekeeping).

- [ ] **Step 1: Verify all 6f.5 commits are present**

```bash
git log origin/main..HEAD --oneline | grep -E "Task 6f\.5|6f5|streamReadSnapshot|toMetaPreview|currentTypedItemsByKey|drop catalogRows|filter helpers reshape"
```

Expected to see commits from Tasks 0, 1, 2, 3-6 (one commit), 7, 9-11 (one commit), 12.

- [ ] **Step 2: Mark task #118 (PlanB 6f) completed in TaskList**

Use `TaskUpdate({taskId: "118", status: "completed"})`.

- [ ] **Step 3: Create a final tag for the 6f.5 milestone**

```bash
git tag plan-b-6f5-complete -m "Plan B Task 6f.5 complete — legacy Snapshot fields dropped, snapshot persistence is structure-only (rails + heroItemKeys + orderedGroupKeys)"
```

- [ ] **Step 4: Push everything**

Confirm with the user first (since pushing to `main` is shared-state). Then:

```bash
git push origin main
git push origin plan-b-6f5-complete
```

- [ ] **Step 5: Update auto-memory**

Save a `project_plan_b_6f5_complete_2026_05_11.md` memory documenting:
- Final commit range
- Snapshot file size reduction (1.06 MB → measured)
- MetaPreview heap count delta
- Any open follow-ups (e.g., if the apply pipeline reconstruction produced a measurable allocation hot path, that becomes a perf follow-up)

---

## Self-Review

**Spec coverage:**
- Task 0 fixes the latent 6f.3 bug (reader skipping rails/heroItemKeys) ✓
- Tasks 1, 2 introduce the adapter + lookup helper that Task 6 needs ✓
- Tasks 3, 4, 5 reshape the three filter helpers (filterDisabledHomeCatalogRows, filterRestoredHomeSnapshotTmdbRows, filterRestoredHomeSnapshotKitsuRows) ✓
- Task 6 reshapes applyHomeSnapshotToUiPipeline (the apply-pipeline content consumer gap I flagged) ✓
- Task 7 reshapes the producer-path transient-snapshot construction ✓
- Task 8 is the verification gate ✓
- Task 9 drops the Snapshot fields ✓
- Tasks 10, 11 update the store ✓
- Task 12 updates test fixtures ✓
- Task 13 is on-device verification (cold-start, file shrink, heap) ✓
- Task 14 is the housekeeping wrap-up ✓

**Placeholder scan:** No "TBD", "implement later", "add appropriate error handling". One conditional in Task 1 Step 3 ("If `rating?.imdbRating` doesn't compile, inspect...") which is reasonable since the field shape is verified at compile time, not authored speculatively.

**Type consistency:**
- `currentTypedItemsByKey(): Map<String, MetaPreview>` used consistently in Tasks 2, 6
- `(Rail) -> Boolean` predicate shape in Task 3 lambda matches Task 6 caller `isAddonRailDisabled` parameter
- `Rail.toCatalogRowOrNull(typedItemsByKey)` and `Snapshot.reconstructHeroItems(typedItemsByKey)` signatures consistent with the existing helper file (`SnapshotContentLookup.kt` from 6f.1)
- Task 6 modifies `reconstructHeroItems` to drop the legacy fallback — matches the field drop in Task 9
- `Rail.toRail()` extension used in Task 7 producer migration matches the existing extension in `Rail.kt:54`

**Open spec gap:** Task 6 collapses `reconstructedFullRows = reconstructedDisplayRows`. Step 4 of Task 6 verifies this is safe (`composedSnapshot.fullRows` consumer doesn't compare displayRows vs fullRows for truncation logic). If verification fails in Step 4, write a follow-up task to either persist a separate `fullRails` field on Snapshot or accept that snapshot-restore loses the truncation distinction (acceptable since the producer pipeline reasserts it on the next emission).
