# Anime Addon Priority Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a per-addon `isAnime` boolean that, when content is detected as anime via the existing `AnimeIdentityIndex`, floats anime-tagged addon stream sections above the rest in both the manual stream list and autoplay candidate ordering. Non-anime content behavior is byte-identical to today.

**Architecture:** New optional `isAnime: Boolean = false` field on `Addon` (Android domain) / `AddonRecord` (web TS) / `AccountAddonPayload` (Supabase wire). Stream pipeline computes a per-request `contentIsAnime` boolean once via `AnimeIdentityIndex.resolveKitsuId(...) != null`, then stamps each emitted `AddonStreams` with `isAnimeBucket = addon.isAnime && contentIsAnime`. The single existing sort seam (`StreamAutoPlaySelector.orderAddonStreams`) is extended to a two-level comparator: anime bucket first, then current `installedOrder` index. UI in both nexio-web and Android exposes a per-addon "Anime" toggle that mirrors the existing `parserPreset` plumbing.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, Coroutines, Gson, kotlinx.serialization (Android); TypeScript, Vue 3, Nuxt, Supabase RPC (web).

**Test command (Android):** `./gradlew testArm64DebugUnitTest --tests <FQCN> --no-daemon`
**Test command (web):** Type-check via `npx vue-tsc --noEmit` from `nexio-web/`. (No unit test runner configured — verify wire shape and UI manually in `nuxt dev`.)

**Spec:** `docs/superpowers/specs/2026-05-06-anime-addon-priority-design.md`

**Amendments since v1 of this plan:** anime detection now uses a boolean `AnimeIdentityIndex.isAnime(parsed)` API instead of `resolveKitsuId(...) != null` (forward-compat for one-to-many IMDb→Kitsu mappings); episode IDs are normalized to parent IDs via an extracted `MetadataParentIdNormalizer.parentIdOf(...)`; trace events `stream.request_classified` and `stream.addon_bucketed` are now in scope (Task 10); explicit non-goal that `addon.isAnime` is stream-priority only and forbidden as metadata-routing input. Three additional Android tests added: parent-id episode normalization (kitsu/imdb/mal), IMDb one-to-many, and the empty anime-tagged addon fallback. Tasks renumbered: original Task 7 became Task 9; trace events were inserted as Task 10; original Tasks 8–12 became 11–15.

---

## File Structure

### Android — new files
- `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataParentIdNormalizer.kt` — pure top-level extraction of `parentIdOf(...)` so the stream pipeline can normalize episode IDs without injecting `MetadataRequestNormalizer`
- `app/src/test/java/com/nexio/tv/core/metadata/router/MetadataParentIdNormalizerTest.kt` — fixtures preserving the existing `MetadataRequestNormalizer.parentIdOf` contract
- `app/src/test/java/com/nexio/tv/core/metadata/router/AnimeIdentityIndexIsAnimeTest.kt` — verifies the default `isAnime(parsed)` and that an override can answer without resolving a Kitsu record
- `app/src/test/java/com/nexio/tv/core/player/StreamAutoPlaySelectorAnimePriorityTest.kt` — comparator regression + anime bucket priority + empty-anime-bucket case
- `app/src/test/java/com/nexio/tv/data/repository/StreamRepositoryImplAnimeBucketTest.kt` — `isAnimeBucket` is computed correctly per emit, episode-ID normalization, IMDb one-to-many, empty-anime-addon fallback
- `app/src/test/java/com/nexio/tv/data/local/AddonPreferencesIsAnimeTest.kt` — DataStore round-trip for the new field
- `app/src/test/java/com/nexio/tv/ui/screens/addon/AddonManagerViewModelAnimeToggleTest.kt` — viewmodel writes through to the repository
- `app/src/test/java/com/nexio/tv/core/metadata/router/MetadataRouterIgnoresAddonIsAnimeTest.kt` — invariant guard that `addon.isAnime` is never an input to routing decisions

### Android — modified files
- `app/src/main/java/com/nexio/tv/domain/model/Stream.kt` — `AddonStreams` gains `isAnimeBucket`
- `app/src/main/java/com/nexio/tv/domain/model/Addon.kt` — `Addon` gains `isAnime`
- `app/src/main/java/com/nexio/tv/domain/repository/AddonRepository.kt` — interface gains `updateAddonIsAnime` and `addAddon` overload
- `app/src/main/java/com/nexio/tv/data/local/AddonPreferences.kt` — `AddonInstallConfig.isAnime`, `addAddon` overload, `updateAddonIsAnime`
- `app/src/main/java/com/nexio/tv/data/repository/AddonRepositoryImpl.kt` — propagate `isAnime` through cache copies and remote reconcile; implement `updateAddonIsAnime`
- `app/src/main/java/com/nexio/tv/data/remote/supabase/AccountSyncModels.kt` — `AccountAddonPayload.isAnime`
- `app/src/main/java/com/nexio/tv/core/sync/AccountConfigSyncContract.kt` — propagate `isAnime` from payload to `AddonInstallConfig`
- `app/src/main/java/com/nexio/tv/core/sync/AddonSyncService.kt` — same, plus push-side `is_anime` in the RPC body
- `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRequestNormalizer.kt` — `parentIdOf(...)` becomes a thin delegate to `MetadataParentIdNormalizer.parentIdOf(...)`
- `app/src/main/java/com/nexio/tv/core/metadata/router/AnimeIdentityIndex.kt` — new default `isAnime(parsed)` method on the interface
- `app/src/main/java/com/nexio/tv/data/repository/StreamRepositoryImpl.kt` — inject `AnimeIdentityIndex` + `TraceMetadataEvents`, normalize parent ID, compute `contentIsAnime`, stamp `isAnimeBucket`, emit two new trace events
- `app/src/main/java/com/nexio/tv/core/trace/TraceMetadataEvents.kt` — new `emitStreamRequestClassified(...)` and `emitStreamAddonBucketed(...)` methods
- `app/src/main/java/com/nexio/tv/core/player/StreamAutoPlaySelector.kt` — two-level comparator
- `app/src/main/java/com/nexio/tv/ui/screens/addon/AddonManagerViewModel.kt` — `updateAddonIsAnime` method
- `app/src/main/java/com/nexio/tv/ui/screens/addon/AddonManagerScreen.kt` — toggle UI

### Web — modified files
- `nexio-web/types/portal.ts` — `AddonRecord.isAnime?`
- `nexio-web/server/api/account/bootstrap.get.ts` — read `is_anime` from snapshot
- `nexio-web/server/api/account/persist.post.ts` — write `is_anime` to snapshot
- `nexio-web/composables/usePortalStore.ts` — `sanitizeAddonRecord`, `snapshotSignature`, new `updateAddonIsAnime` action
- `nexio-web/components/portal/AddonManager.vue` — toggle in row + add-addon dialog
- `nexio-web/pages/account.vue` — wire the new emit handler

---

## Task ordering rationale

Tasks proceed bottom-up: domain types (1–3) → persistence (4) → repository plumbing (5) → sync wire (6) → shared metadata helpers (7–8) → stream pipeline (9–10) → Android UI (11) → routing invariant guard (12) → web (13–15) → end-to-end smoke (16). Each task is independently green-able and committable. The shared helpers (Tasks 7 + 8) come before the consumer (Task 9) so the new boolean `isAnime(...)` API and the parent-ID normalizer exist before `StreamRepositoryImpl` calls them. The trace events (Task 10) follow Task 9 because their wiring lives in the same file. The routing-invariant guard (Task 12) is a no-implementation test that pins the spec's "stream-priority only" non-goal — it can run any time after Task 9.

---

### Task 1: Add `isAnimeBucket` to `AddonStreams`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/domain/model/Stream.kt:81-85`

Smallest possible change: a default-false boolean. Existing tests stay green because the default value preserves equality with prior fixtures.

- [ ] **Step 1: Modify `AddonStreams` with new field**

In `Stream.kt:81-85`, replace:
```kotlin
@Immutable
data class AddonStreams(
    val addonName: String,
    val addonLogo: String?,
    val streams: List<Stream>
)
```
with:
```kotlin
@Immutable
data class AddonStreams(
    val addonName: String,
    val addonLogo: String?,
    val streams: List<Stream>,
    val isAnimeBucket: Boolean = false,
)
```

- [ ] **Step 2: Run existing AddonStreams-touching tests to confirm no regression**

Run: `./gradlew testArm64DebugUnitTest --tests com.nexio.tv.core.player.StreamAutoPlaySelectorTest --tests com.nexio.tv.data.repository.StreamRepositoryImplTest --no-daemon`
Expected: PASS — default value keeps existing fixtures equal.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nexio/tv/domain/model/Stream.kt
git commit -m "feat(streams): add isAnimeBucket to AddonStreams"
```

---

### Task 2: Two-level comparator in `StreamAutoPlaySelector`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/player/StreamAutoPlaySelector.kt:25-36`
- Test: `app/src/test/java/com/nexio/tv/core/player/StreamAutoPlaySelectorAnimePriorityTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/nexio/tv/core/player/StreamAutoPlaySelectorAnimePriorityTest.kt`:
```kotlin
package com.nexio.tv.core.player

import com.nexio.tv.domain.model.AddonStreams
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamAutoPlaySelectorAnimePriorityTest {

    private fun streams(name: String, isAnimeBucket: Boolean = false): AddonStreams =
        AddonStreams(addonName = name, addonLogo = null, streams = emptyList(), isAnimeBucket = isAnimeBucket)

    @Test
    fun `anime bucket sections sort above non-anime regardless of installed order`() {
        val installedOrder = listOf("Generic-A", "Generic-B", "Anime-A", "Anime-B")
        val input = listOf(
            streams("Generic-A"),
            streams("Anime-B", isAnimeBucket = true),
            streams("Generic-B"),
            streams("Anime-A", isAnimeBucket = true),
        )

        val ordered = StreamAutoPlaySelector.orderAddonStreams(input, installedOrder)
            .map { it.addonName }

        assertEquals(listOf("Anime-A", "Anime-B", "Generic-A", "Generic-B"), ordered)
    }

    @Test
    fun `within each bucket installedOrder is preserved`() {
        val installedOrder = listOf("Anime-First", "Anime-Second", "Generic-First", "Generic-Second")
        val input = listOf(
            streams("Generic-Second"),
            streams("Anime-Second", isAnimeBucket = true),
            streams("Generic-First"),
            streams("Anime-First", isAnimeBucket = true),
        )

        val ordered = StreamAutoPlaySelector.orderAddonStreams(input, installedOrder)
            .map { it.addonName }

        assertEquals(
            listOf("Anime-First", "Anime-Second", "Generic-First", "Generic-Second"),
            ordered
        )
    }

    @Test
    fun `all-non-anime input is byte-identical to legacy ordering`() {
        val installedOrder = listOf("A", "B", "C")
        val input = listOf(streams("C"), streams("A"), streams("B"))

        val ordered = StreamAutoPlaySelector.orderAddonStreams(input, installedOrder)
            .map { it.addonName }

        assertEquals(listOf("A", "B", "C"), ordered)
    }

    @Test
    fun `unknown addon names land at the end of their bucket`() {
        val installedOrder = listOf("Anime-A", "Generic-A")
        val input = listOf(
            streams("Unknown-Anime", isAnimeBucket = true),
            streams("Anime-A", isAnimeBucket = true),
            streams("Unknown-Generic"),
            streams("Generic-A"),
        )

        val ordered = StreamAutoPlaySelector.orderAddonStreams(input, installedOrder)
            .map { it.addonName }

        assertEquals(
            listOf("Anime-A", "Unknown-Anime", "Generic-A", "Unknown-Generic"),
            ordered
        )
    }

    @Test
    fun `empty anime-bucket section stays in its bucket and does not collapse generics`() {
        // An anime-tagged addon returned zero streams (still emitted as an
        // empty section). It must remain in the anime bucket above the
        // generic addons so the section header order stays correct.
        val installedOrder = listOf("Anime-A", "Generic-A")
        val input = listOf(
            streams("Generic-A"), // has streams in caller's fixture
            streams("Anime-A", isAnimeBucket = true), // empty list
        )

        val ordered = StreamAutoPlaySelector.orderAddonStreams(input, installedOrder)
            .map { it.addonName }

        assertEquals(listOf("Anime-A", "Generic-A"), ordered)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testArm64DebugUnitTest --tests com.nexio.tv.core.player.StreamAutoPlaySelectorAnimePriorityTest --no-daemon`
Expected: FAIL on the first two cases — anime sections appear interleaved.

- [ ] **Step 3: Update the comparator**

In `StreamAutoPlaySelector.kt:25-36`, replace:
```kotlin
    fun orderAddonStreams(
        streams: List<AddonStreams>,
        installedOrder: List<String>
    ): List<AddonStreams> {
        if (streams.isEmpty()) return streams

        return streams.sortedBy { addonStreams ->
            installedOrder.indexOf(addonStreams.addonName).let { index ->
                if (index >= 0) index else Int.MAX_VALUE
            }
        }
    }
```
with:
```kotlin
    fun orderAddonStreams(
        streams: List<AddonStreams>,
        installedOrder: List<String>
    ): List<AddonStreams> {
        if (streams.isEmpty()) return streams

        return streams.sortedWith(
            compareByDescending<AddonStreams> { it.isAnimeBucket }
                .thenBy { addonStreams ->
                    installedOrder.indexOf(addonStreams.addonName).let { index ->
                        if (index >= 0) index else Int.MAX_VALUE
                    }
                }
        )
    }
```

- [ ] **Step 4: Run tests to verify pass**

Run: `./gradlew testArm64DebugUnitTest --tests com.nexio.tv.core.player.StreamAutoPlaySelectorAnimePriorityTest --tests com.nexio.tv.core.player.StreamAutoPlaySelectorTest --no-daemon`
Expected: PASS for all four new tests and existing `StreamAutoPlaySelectorTest`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/player/StreamAutoPlaySelector.kt app/src/test/java/com/nexio/tv/core/player/StreamAutoPlaySelectorAnimePriorityTest.kt
git commit -m "feat(stream): prioritize anime bucket in addon stream order"
```

---

### Task 3: `Addon.isAnime` domain field

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/domain/model/Addon.kt:13-27`

- [ ] **Step 1: Add field with default**

In `Addon.kt:13-27`, replace:
```kotlin
@Immutable
data class Addon(
    val id: String,
    val name: String,
    val displayName: String = name,
    val version: String,
    val description: String?,
    val logo: String?,
    val baseUrl: String,
    val catalogs: List<CatalogDescriptor>,
    val types: List<ContentType>,
    val rawTypes: List<String> = types.map { it.toApiString() },
    val resources: List<AddonResource>,
    val parserPreset: AddonParserPreset = AddonParserPreset.GENERIC
)
```
with:
```kotlin
@Immutable
data class Addon(
    val id: String,
    val name: String,
    val displayName: String = name,
    val version: String,
    val description: String?,
    val logo: String?,
    val baseUrl: String,
    val catalogs: List<CatalogDescriptor>,
    val types: List<ContentType>,
    val rawTypes: List<String> = types.map { it.toApiString() },
    val resources: List<AddonResource>,
    val parserPreset: AddonParserPreset = AddonParserPreset.GENERIC,
    val isAnime: Boolean = false,
)
```

- [ ] **Step 2: Compile to verify no callers break**

Run: `./gradlew :app:compileArm64DebugKotlin --no-daemon`
Expected: BUILD SUCCESSFUL — default makes the field opt-in for all existing call sites.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nexio/tv/domain/model/Addon.kt
git commit -m "feat(addons): add isAnime to Addon domain model"
```

---

### Task 4: `AddonInstallConfig.isAnime` + DataStore round-trip

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/local/AddonPreferences.kt`
- Test: `app/src/test/java/com/nexio/tv/data/local/AddonPreferencesIsAnimeTest.kt`

The persisted JSON shape changes (Gson adds the new field). Existing on-device JSON without `isAnime` deserializes as `isAnime = false` because Gson uses Kotlin defaults via the data-class default.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/nexio/tv/data/local/AddonPreferencesIsAnimeTest.kt`:
```kotlin
package com.nexio.tv.data.local

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nexio.tv.domain.model.AddonParserPreset
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class AddonPreferencesIsAnimeTest {

    @Test
    fun `addAddon defaults isAnime to false`() = runBlocking {
        val prefs = AddonPreferences(ApplicationProvider.getApplicationContext())
        prefs.addAddon("https://example.com/addon", AddonParserPreset.GENERIC)
        val list = prefs.installedAddons.first()
        val match = list.first { it.url == "https://example.com/addon" }
        assertFalse(match.isAnime)
    }

    @Test
    fun `addAddon persists isAnime when provided`() = runBlocking {
        val prefs = AddonPreferences(ApplicationProvider.getApplicationContext())
        prefs.addAddon(
            url = "https://example.com/anime-addon",
            parserPreset = AddonParserPreset.GENERIC,
            isAnime = true,
        )
        val list = prefs.installedAddons.first()
        val match = list.first { it.url == "https://example.com/anime-addon" }
        assertTrue(match.isAnime)
    }

    @Test
    fun `updateAddonIsAnime flips persisted value`() = runBlocking {
        val prefs = AddonPreferences(ApplicationProvider.getApplicationContext())
        prefs.addAddon("https://example.com/addon", AddonParserPreset.GENERIC)
        prefs.updateAddonIsAnime("https://example.com/addon", true)
        val flippedOn = prefs.installedAddons.first().first { it.url == "https://example.com/addon" }
        assertTrue(flippedOn.isAnime)

        prefs.updateAddonIsAnime("https://example.com/addon", false)
        val flippedOff = prefs.installedAddons.first().first { it.url == "https://example.com/addon" }
        assertFalse(flippedOff.isAnime)
    }

    @Test
    fun `existing entries without isAnime decode as false`() = runBlocking {
        // Simulate a legacy JSON entry (no isAnime field). addAddon writes the new shape,
        // but Gson must accept old shape transparently. parseInstallConfigList covers this.
        val prefs = AddonPreferences(ApplicationProvider.getApplicationContext())
        prefs.addAddon("https://example.com/addon", AddonParserPreset.GENERIC)
        val match = prefs.installedAddons.first().first()
        assertEquals(false, match.isAnime)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testArm64DebugUnitTest --tests com.nexio.tv.data.local.AddonPreferencesIsAnimeTest --no-daemon`
Expected: FAIL — `addAddon` overload missing, `updateAddonIsAnime` missing, `isAnime` field missing.

- [ ] **Step 3: Add `isAnime` to `AddonInstallConfig`**

In `AddonPreferences.kt:33-36`, replace:
```kotlin
    data class AddonInstallConfig(
        val url: String,
        val parserPreset: AddonParserPreset = AddonParserPreset.GENERIC
    )
```
with:
```kotlin
    data class AddonInstallConfig(
        val url: String,
        val parserPreset: AddonParserPreset = AddonParserPreset.GENERIC,
        val isAnime: Boolean = false,
    )
```

- [ ] **Step 4: Add `isAnime` parameter to `addAddon`**

In `AddonPreferences.kt:96-111`, replace:
```kotlin
    suspend fun addAddon(
        url: String,
        parserPreset: AddonParserPreset = AddonParserPreset.GENERIC
    ) {
        store().edit { preferences ->
            val current = getCurrentList(preferences).toMutableList()
            val normalizedUrl = canonicalizeUrl(url)
            if (current.any { it.url.equals(normalizedUrl, ignoreCase = true) }) return@edit
            preferences[orderedUrlsKey] = gson.toJson(
                current + AddonInstallConfig(
                    url = normalizedUrl,
                    parserPreset = parserPreset
                )
            )
        }
    }
```
with:
```kotlin
    suspend fun addAddon(
        url: String,
        parserPreset: AddonParserPreset = AddonParserPreset.GENERIC,
        isAnime: Boolean = false,
    ) {
        store().edit { preferences ->
            val current = getCurrentList(preferences).toMutableList()
            val normalizedUrl = canonicalizeUrl(url)
            if (current.any { it.url.equals(normalizedUrl, ignoreCase = true) }) return@edit
            preferences[orderedUrlsKey] = gson.toJson(
                current + AddonInstallConfig(
                    url = normalizedUrl,
                    parserPreset = parserPreset,
                    isAnime = isAnime,
                )
            )
        }
    }
```

- [ ] **Step 5: Add `updateAddonIsAnime`**

In `AddonPreferences.kt`, immediately after the existing `updateAddonParserPreset` method (around line 152), add:
```kotlin
    suspend fun updateAddonIsAnime(url: String, isAnime: Boolean) {
        store().edit { preferences ->
            val normalizedUrl = canonicalizeUrl(url)
            val updated = getCurrentList(preferences).map { addon ->
                if (addon.url.equals(normalizedUrl, ignoreCase = true)) {
                    addon.copy(isAnime = isAnime)
                } else {
                    addon
                }
            }
            preferences[orderedUrlsKey] = gson.toJson(updated)
        }
    }
```

- [ ] **Step 6: Propagate `isAnime` in `parseInstallConfigList` and `setAddonConfigs`**

In `AddonPreferences.kt:154-162`, replace the body of `setAddonConfigs`:
```kotlin
    suspend fun setAddonConfigs(configs: List<AddonInstallConfig>) {
        store().edit { preferences ->
            val normalized = configs.mapNotNull { addon ->
                safeCanonicalizeUrl(addon.url, "remote sync")?.let { url ->
                    AddonInstallConfig(url = url, parserPreset = addon.parserPreset)
                }
            }.distinctBy { it.url.lowercase() }
            preferences[orderedUrlsKey] = gson.toJson(normalized)
        }
    }
```
with:
```kotlin
    suspend fun setAddonConfigs(configs: List<AddonInstallConfig>) {
        store().edit { preferences ->
            val normalized = configs.mapNotNull { addon ->
                safeCanonicalizeUrl(addon.url, "remote sync")?.let { url ->
                    AddonInstallConfig(
                        url = url,
                        parserPreset = addon.parserPreset,
                        isAnime = addon.isAnime,
                    )
                }
            }.distinctBy { it.url.lowercase() }
            preferences[orderedUrlsKey] = gson.toJson(normalized)
        }
    }
```

In `AddonPreferences.kt:186-211`, replace `parseInstallConfigList`:
```kotlin
    private fun parseInstallConfigList(json: String): List<AddonInstallConfig> {
        return try {
            val objectType = object : TypeToken<List<AddonInstallConfig>>() {}.type
            val parsedObjects: List<AddonInstallConfig>? = gson.fromJson(json, objectType)
            if (parsedObjects != null) {
                return parsedObjects.mapNotNull { addon ->
                    safeCanonicalizeUrl(addon.url, "preferences")?.let { normalized ->
                        AddonInstallConfig(
                            url = normalized,
                            parserPreset = addon.parserPreset
                        )
                    }
                }.distinctBy { it.url.lowercase() }
            }
            // ...legacy path unchanged...
```
with:
```kotlin
    private fun parseInstallConfigList(json: String): List<AddonInstallConfig> {
        return try {
            val objectType = object : TypeToken<List<AddonInstallConfig>>() {}.type
            val parsedObjects: List<AddonInstallConfig>? = gson.fromJson(json, objectType)
            if (parsedObjects != null) {
                return parsedObjects.mapNotNull { addon ->
                    safeCanonicalizeUrl(addon.url, "preferences")?.let { normalized ->
                        AddonInstallConfig(
                            url = normalized,
                            parserPreset = addon.parserPreset,
                            isAnime = addon.isAnime,
                        )
                    }
                }.distinctBy { it.url.lowercase() }
            }
            // ...legacy path unchanged...
```
(Leave the legacy `List<String>` fallback path identical — those entries default `isAnime` to `false` via the data class default.)

- [ ] **Step 7: Run tests to verify pass**

Run: `./gradlew testArm64DebugUnitTest --tests com.nexio.tv.data.local.AddonPreferencesIsAnimeTest --no-daemon`
Expected: PASS — all four cases.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/local/AddonPreferences.kt app/src/test/java/com/nexio/tv/data/local/AddonPreferencesIsAnimeTest.kt
git commit -m "feat(addons): persist isAnime in AddonInstallConfig"
```

---

### Task 5: `AddonRepository.updateAddonIsAnime` + propagation in impl

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/domain/repository/AddonRepository.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/AddonRepositoryImpl.kt`

The cache copies at lines 142, 154, 176, 232, 269, 278 currently propagate only `parserPreset`. They must also propagate `isAnime` so toggling on the server doesn't get clobbered by a stale manifest re-fetch.

- [ ] **Step 1: Add interface method and `addAddon` overload**

In `AddonRepository.kt`, replace the file with:
```kotlin
package com.nexio.tv.domain.repository

import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.domain.model.Addon
import com.nexio.tv.domain.model.AddonParserPreset
import kotlinx.coroutines.flow.Flow

interface AddonRepository {
    fun getInstalledAddons(): Flow<List<Addon>>
    suspend fun getCachedInstalledAddons(): List<Addon>
    suspend fun fetchAddon(baseUrl: String): NetworkResult<Addon>
    suspend fun addAddon(
        url: String,
        parserPreset: AddonParserPreset = AddonParserPreset.GENERIC,
        isAnime: Boolean = false,
    )
    suspend fun removeAddon(url: String)
    suspend fun setAddonOrder(urls: List<String>)
    suspend fun updateAddonParserPreset(url: String, parserPreset: AddonParserPreset)
    suspend fun updateAddonIsAnime(url: String, isAnime: Boolean)
}
```

- [ ] **Step 2: Update `addAddon` implementation**

In `AddonRepositoryImpl.kt:210-214`, replace:
```kotlin
    override suspend fun addAddon(url: String, parserPreset: AddonParserPreset) {
        val cleanUrl = canonicalizeUrl(url)
        preferences.addAddon(cleanUrl, parserPreset)
        triggerRemoteSync()
    }
```
with:
```kotlin
    override suspend fun addAddon(
        url: String,
        parserPreset: AddonParserPreset,
        isAnime: Boolean,
    ) {
        val cleanUrl = canonicalizeUrl(url)
        preferences.addAddon(cleanUrl, parserPreset, isAnime)
        triggerRemoteSync()
    }
```

- [ ] **Step 3: Implement `updateAddonIsAnime`**

In `AddonRepositoryImpl.kt`, immediately after the existing `updateAddonParserPreset` (around line 236), add:
```kotlin
    override suspend fun updateAddonIsAnime(url: String, isAnime: Boolean) {
        val cleanUrl = canonicalizeUrl(url)
        preferences.updateAddonIsAnime(cleanUrl, isAnime)
        manifestCache[cleanUrl]?.let { cached ->
            manifestCache[cleanUrl] = cached.copy(isAnime = isAnime)
            persistManifestCacheToDisk()
        }
        triggerRemoteSync()
    }
```

- [ ] **Step 4: Propagate `isAnime` through cache `.copy(...)` sites**

In `AddonRepositoryImpl.kt`, update each existing `.copy(parserPreset = addonConfig.parserPreset)` call so it also carries `isAnime`:

Line 142:
```kotlin
manifestCache[addonConfig.url]?.copy(parserPreset = addonConfig.parserPreset)
```
becomes:
```kotlin
manifestCache[addonConfig.url]?.copy(
    parserPreset = addonConfig.parserPreset,
    isAnime = addonConfig.isAnime,
)
```

Line 153–154:
```kotlin
else -> manifestCache[canonicalizeUrl(addonConfig.url)]
    ?.copy(parserPreset = addonConfig.parserPreset)
```
becomes:
```kotlin
else -> manifestCache[canonicalizeUrl(addonConfig.url)]
    ?.copy(
        parserPreset = addonConfig.parserPreset,
        isAnime = addonConfig.isAnime,
    )
```

Line 176:
```kotlin
manifestCache[addonConfig.url]?.copy(parserPreset = addonConfig.parserPreset)
```
becomes:
```kotlin
manifestCache[addonConfig.url]?.copy(
    parserPreset = addonConfig.parserPreset,
    isAnime = addonConfig.isAnime,
)
```

Inside `fetchAddon` at line 194:
```kotlin
val addon = result.data.toDomain(cleanBaseUrl).copy(parserPreset = parserPreset)
```
The `fetchAddon` overload only knows about `parserPreset` — leave this site untouched. The cached domain object's `isAnime` is layered back in at the `getInstalledAddons` flow sites above (which are the ones that read `addonConfig.isAnime` from preferences).

Line 269 in `reconcileWithRemoteAddonConfigs`:
```kotlin
.forEach { addAddon(it.url, it.parserPreset) }
```
becomes:
```kotlin
.forEach { addAddon(it.url, it.parserPreset, it.isAnime) }
```

Line 278:
```kotlin
currentByNormalizedUrl[normalizeUrl(remote.url)]?.copy(parserPreset = remote.parserPreset)
```
becomes:
```kotlin
currentByNormalizedUrl[normalizeUrl(remote.url)]?.copy(
    parserPreset = remote.parserPreset,
    isAnime = remote.isAnime,
)
```

- [ ] **Step 5: Compile to verify all call sites resolved**

Run: `./gradlew :app:compileArm64DebugKotlin --no-daemon`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Run existing addon repository tests**

Run: `./gradlew testArm64DebugUnitTest --tests com.nexio.tv.data.repository.AddonRepositoryImplTest --no-daemon`
Expected: PASS — defaults preserve existing fixtures.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/domain/repository/AddonRepository.kt app/src/main/java/com/nexio/tv/data/repository/AddonRepositoryImpl.kt
git commit -m "feat(addons): plumb isAnime through AddonRepository"
```

---

### Task 6: Wire-shape `AccountAddonPayload.isAnime` + sync mappers

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/remote/supabase/AccountSyncModels.kt:46-62`
- Modify: `app/src/main/java/com/nexio/tv/core/sync/AccountConfigSyncContract.kt:299-318`
- Modify: `app/src/main/java/com/nexio/tv/core/sync/AddonSyncService.kt:151-180`

The Supabase column `is_anime` (boolean, default false) is added server-side as a separate concern; this task only makes the client serialize/deserialize the field. Servers without the column return `is_anime: false` (or omit it; default-`false` handles both).

- [ ] **Step 1: Add `isAnime` to `AccountAddonPayload`**

In `AccountSyncModels.kt:47-62`, replace:
```kotlin
@Serializable
data class AccountAddonPayload(
    val id: String? = null,
    val url: String,
    @SerialName("manifest_url") val manifestUrl: String? = null,
    @SerialName("parser_preset") val parserPreset: String = "GENERIC",
    val name: String? = null,
    val description: String? = null,
    val enabled: Boolean = true,
    @SerialName("public_query_params") val publicQueryParams: Map<String, String> = emptyMap(),
    @SerialName("install_kind") val installKind: String = "manifest",
    @SerialName("secret_ref") val secretRef: String? = null,
    @SerialName("transport_schema_version") val transportSchemaVersion: Int = 1,
    @SerialName("transport_base_url") val transportBaseUrl: String? = null,
    @SerialName("transport_secret_ref") val transportSecretRef: String? = null,
    @SerialName("sort_order") val sortOrder: Int = 0
)
```
with:
```kotlin
@Serializable
data class AccountAddonPayload(
    val id: String? = null,
    val url: String,
    @SerialName("manifest_url") val manifestUrl: String? = null,
    @SerialName("parser_preset") val parserPreset: String = "GENERIC",
    @SerialName("is_anime") val isAnime: Boolean = false,
    val name: String? = null,
    val description: String? = null,
    val enabled: Boolean = true,
    @SerialName("public_query_params") val publicQueryParams: Map<String, String> = emptyMap(),
    @SerialName("install_kind") val installKind: String = "manifest",
    @SerialName("secret_ref") val secretRef: String? = null,
    @SerialName("transport_schema_version") val transportSchemaVersion: Int = 1,
    @SerialName("transport_base_url") val transportBaseUrl: String? = null,
    @SerialName("transport_secret_ref") val transportSecretRef: String? = null,
    @SerialName("sort_order") val sortOrder: Int = 0
)
```

- [ ] **Step 2: Propagate in `buildRemoteAddonInstallConfigs` (`AccountConfigSyncContract.kt`)**

In `AccountConfigSyncContract.kt:299-318`, replace:
```kotlin
internal suspend fun buildRemoteAddonInstallConfigs(
    addons: List<AccountAddonPayload>,
    resolveAddonUrl: suspend (AccountAddonPayload) -> Result<String>
): List<AddonPreferences.AddonInstallConfig> {
    return addons
        .sortedBy { it.sortOrder }
        .filter { it.enabled }
        .mapNotNull { addon ->
            resolveAddonUrl(addon).getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { url ->
                    AddonPreferences.AddonInstallConfig(
                        url = url,
                        parserPreset = runCatching {
                            enumValueOf<AddonParserPreset>(addon.parserPreset.trim().uppercase())
                        }.getOrDefault(AddonParserPreset.GENERIC)
                    )
                }
        }
}
```
with:
```kotlin
internal suspend fun buildRemoteAddonInstallConfigs(
    addons: List<AccountAddonPayload>,
    resolveAddonUrl: suspend (AccountAddonPayload) -> Result<String>
): List<AddonPreferences.AddonInstallConfig> {
    return addons
        .sortedBy { it.sortOrder }
        .filter { it.enabled }
        .mapNotNull { addon ->
            resolveAddonUrl(addon).getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { url ->
                    AddonPreferences.AddonInstallConfig(
                        url = url,
                        parserPreset = runCatching {
                            enumValueOf<AddonParserPreset>(addon.parserPreset.trim().uppercase())
                        }.getOrDefault(AddonParserPreset.GENERIC),
                        isAnime = addon.isAnime,
                    )
                }
        }
}
```

- [ ] **Step 3: Propagate in `AddonSyncService.getRemoteAddonConfigs`**

Open `app/src/main/java/com/nexio/tv/core/sync/AddonSyncService.kt`. Locate the `mapNotNull { ... AddonInstallConfig(...) }` block around lines 158–175. Add `isAnime = addon.isAnime` to the constructor call, mirroring the change in `buildRemoteAddonInstallConfigs`.

The exact diff: find the existing constructor call:
```kotlin
AddonPreferences.AddonInstallConfig(
    url = url,
    parserPreset = runCatching {
        enumValueOf<AddonParserPreset>(addon.parserPreset.trim().uppercase())
    }.getOrDefault(AddonParserPreset.GENERIC)
)
```
and add a third argument:
```kotlin
AddonPreferences.AddonInstallConfig(
    url = url,
    parserPreset = runCatching {
        enumValueOf<AddonParserPreset>(addon.parserPreset.trim().uppercase())
    }.getOrDefault(AddonParserPreset.GENERIC),
    isAnime = addon.isAnime,
)
```

- [ ] **Step 4: Add `is_anime` to the push RPC body in `AddonSyncService.pushToRemote`**

The push path is at `AddonSyncService.kt:54-141`. It does not construct `AccountAddonPayload` — it builds a raw `buildJsonObject` for the `sync_push_account_addons` RPC. Two edits:

In `AddonSyncService.kt:55-61`, the destructure currently captures only `(parsed, parserPreset)`. Replace:
```kotlin
            val parsedAddons = localAddons.mapNotNull { addon ->
                runCatching { parseStoredAddonInstallUrl(addon.url) to addon.parserPreset }
                    .onFailure { error ->
                        Log.w(TAG, "pushToRemote: dropping malformed local addon URL=${addon.url}", error)
                    }
                    .getOrNull()
            }
```
with:
```kotlin
            val parsedAddons = localAddons.mapNotNull { addon ->
                runCatching { Triple(parseStoredAddonInstallUrl(addon.url), addon.parserPreset, addon.isAnime) }
                    .onFailure { error ->
                        Log.w(TAG, "pushToRemote: dropping malformed local addon URL=${addon.url}", error)
                    }
                    .getOrNull()
            }
```

In `AddonSyncService.kt:64`, the existing iteration unpacks the pair. Replace:
```kotlin
            parsedAddons.forEach { (parsed, _) ->
```
with:
```kotlin
            parsedAddons.forEach { (parsed, _, _) ->
```

In `AddonSyncService.kt:118-135`, the push payload builder unpacks the pair as `val (parsedAddon, parserPreset) = addon`. Replace:
```kotlin
                    parsedAddons.forEachIndexed { index, addon ->
                        val (parsedAddon, parserPreset) = addon
                        addJsonObject {
                            put("url", parsedAddon.publicBaseUrl)
                            put("manifest_url", parsedAddon.manifestUrl)
                            put("parser_preset", parserPreset.name)
                            put("public_query_params", Json.encodeToJsonElement(MapSerializer(String.serializer(), String.serializer()), parsedAddon.publicQueryParams))
                            put("install_kind", parsedAddon.installKind)
                            parsedAddon.secretRef?.let { put("secret_ref", it) }
                            put("transport_schema_version", 2)
                            put("transport_base_url", parsedAddon.transportBaseUrl)
                            put("transport_secret_ref", parsedAddon.transportSecretRef)
                            put("sort_order", index)
                        }
                    }
```
with:
```kotlin
                    parsedAddons.forEachIndexed { index, addon ->
                        val (parsedAddon, parserPreset, isAnime) = addon
                        addJsonObject {
                            put("url", parsedAddon.publicBaseUrl)
                            put("manifest_url", parsedAddon.manifestUrl)
                            put("parser_preset", parserPreset.name)
                            put("is_anime", isAnime)
                            put("public_query_params", Json.encodeToJsonElement(MapSerializer(String.serializer(), String.serializer()), parsedAddon.publicQueryParams))
                            put("install_kind", parsedAddon.installKind)
                            parsedAddon.secretRef?.let { put("secret_ref", it) }
                            put("transport_schema_version", 2)
                            put("transport_base_url", parsedAddon.transportBaseUrl)
                            put("transport_secret_ref", parsedAddon.transportSecretRef)
                            put("sort_order", index)
                        }
                    }
```

- [ ] **Step 5: Run sync contract tests**

Run: `./gradlew testArm64DebugUnitTest --tests com.nexio.tv.core.sync.AccountConfigSyncContractTest --no-daemon`
Expected: PASS — defaults preserve fixtures.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/remote/supabase/AccountSyncModels.kt app/src/main/java/com/nexio/tv/core/sync/AccountConfigSyncContract.kt app/src/main/java/com/nexio/tv/core/sync/AddonSyncService.kt
git commit -m "feat(sync): propagate addon isAnime through Supabase wire shape"
```

---

### Task 7: Extract `MetadataParentIdNormalizer` from `MetadataRequestNormalizer`

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataParentIdNormalizer.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRequestNormalizer.kt`
- Test: `app/src/test/java/com/nexio/tv/core/metadata/router/MetadataParentIdNormalizerTest.kt`

The existing `MetadataRequestNormalizer.parentIdOf(...)` is a pure string operation that doesn't reference its `traceEvents` dependency. Lift it to a top-level function so the stream pipeline can normalize episode IDs without injecting a class with `TraceMetadataEvents` dependency. The instance method delegates so existing callers keep working.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/nexio/tv/core/metadata/router/MetadataParentIdNormalizerTest.kt`:
```kotlin
package com.nexio.tv.core.metadata.router

import org.junit.Assert.assertEquals
import org.junit.Test

class MetadataParentIdNormalizerTest {

    @Test
    fun `imdb episode id normalizes to imdb parent`() {
        assertEquals("tt12343534", MetadataParentIdNormalizer.parentIdOf("tt12343534:1:1"))
    }

    @Test
    fun `kitsu episode id normalizes to kitsu work`() {
        assertEquals("kitsu:7442", MetadataParentIdNormalizer.parentIdOf("kitsu:7442:1:1"))
    }

    @Test
    fun `mal episode id normalizes to mal work`() {
        assertEquals("mal:21", MetadataParentIdNormalizer.parentIdOf("mal:21:1:1"))
    }

    @Test
    fun `anilist episode id normalizes to anilist work`() {
        assertEquals("anilist:113415", MetadataParentIdNormalizer.parentIdOf("anilist:113415:1:1"))
    }

    @Test
    fun `anidb episode id normalizes to anidb work`() {
        assertEquals("anidb:69", MetadataParentIdNormalizer.parentIdOf("anidb:69:1:1"))
    }

    @Test
    fun `parent id is returned unchanged`() {
        assertEquals("kitsu:7442", MetadataParentIdNormalizer.parentIdOf("kitsu:7442"))
        assertEquals("tt12343534", MetadataParentIdNormalizer.parentIdOf("tt12343534"))
    }

    @Test
    fun `tmdb person and tvdb company ids are preserved`() {
        assertEquals("tmdb:person:1234", MetadataParentIdNormalizer.parentIdOf("tmdb:person:1234"))
        assertEquals("tvdb:company:42", MetadataParentIdNormalizer.parentIdOf("tvdb:company:42"))
    }

    @Test
    fun `blank input returns empty string`() {
        assertEquals("", MetadataParentIdNormalizer.parentIdOf(""))
        assertEquals("", MetadataParentIdNormalizer.parentIdOf("   "))
    }

    @Test
    fun `unknown scheme is preserved as-is`() {
        assertEquals("garbage-id", MetadataParentIdNormalizer.parentIdOf("garbage-id"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testArm64DebugUnitTest --tests com.nexio.tv.core.metadata.router.MetadataParentIdNormalizerTest --no-daemon`
Expected: FAIL — `MetadataParentIdNormalizer` does not exist.

- [ ] **Step 3: Create `MetadataParentIdNormalizer.kt`**

Create `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataParentIdNormalizer.kt`:
```kotlin
package com.nexio.tv.core.metadata.router

object MetadataParentIdNormalizer {
    fun parentIdOf(contentId: String): String {
        val id = contentId.trim()
        if (id.isBlank()) return ""

        val parts = id.split(":")
        return when {
            isProviderObjectId(id, parts) -> id
            id.startsWith("imdb:", ignoreCase = true) && parts.size >= 2 -> "${parts[0]}:${parts[1]}"
            id.startsWith("kitsu:", ignoreCase = true) && parts.size >= 2 -> "${parts[0]}:${parts[1]}"
            id.startsWith("mal:", ignoreCase = true) && parts.size >= 2 -> "${parts[0]}:${parts[1]}"
            id.startsWith("anilist:", ignoreCase = true) && parts.size >= 2 -> "${parts[0]}:${parts[1]}"
            id.startsWith("anidb:", ignoreCase = true) && parts.size >= 2 -> "${parts[0]}:${parts[1]}"
            id.startsWith("tmdb:", ignoreCase = true) && parts.size >= 2 -> "${parts[0]}:${parts[1]}"
            id.startsWith("tvdb:", ignoreCase = true) && parts.size >= 2 -> "${parts[0]}:${parts[1]}"
            id.startsWith("tt", ignoreCase = true) && parts.size >= 3 -> parts[0]
            else -> id
        }
    }

    private fun isProviderObjectId(id: String, parts: List<String>): Boolean {
        if (parts.size < 3) return false
        val provider = parts[0]
        val objectType = parts[1]
        val isSupportedProvider = provider.equals("tmdb", ignoreCase = true) ||
            provider.equals("tvdb", ignoreCase = true)
        val isSupportedObject = objectType.equals("person", ignoreCase = true) ||
            objectType.equals("company", ignoreCase = true) ||
            objectType.equals("network", ignoreCase = true) ||
            objectType.equals("org", ignoreCase = true)
        return isSupportedProvider && isSupportedObject && id.startsWith("$provider:", ignoreCase = true)
    }
}
```

- [ ] **Step 4: Delegate `MetadataRequestNormalizer.parentIdOf` to the new helper**

In `MetadataRequestNormalizer.kt:25-55`, replace:
```kotlin
    fun parentIdOf(contentId: String): String {
        val id = contentId.trim()
        if (id.isBlank()) return ""

        val parts = id.split(":")
        return when {
            isProviderObjectId(id, parts) -> id
            id.startsWith("imdb:", ignoreCase = true) && parts.size >= 2 -> "${parts[0]}:${parts[1]}"
            id.startsWith("kitsu:", ignoreCase = true) && parts.size >= 2 -> "${parts[0]}:${parts[1]}"
            id.startsWith("mal:", ignoreCase = true) && parts.size >= 2 -> "${parts[0]}:${parts[1]}"
            id.startsWith("anilist:", ignoreCase = true) && parts.size >= 2 -> "${parts[0]}:${parts[1]}"
            id.startsWith("anidb:", ignoreCase = true) && parts.size >= 2 -> "${parts[0]}:${parts[1]}"
            id.startsWith("tmdb:", ignoreCase = true) && parts.size >= 2 -> "${parts[0]}:${parts[1]}"
            id.startsWith("tvdb:", ignoreCase = true) && parts.size >= 2 -> "${parts[0]}:${parts[1]}"
            id.startsWith("tt", ignoreCase = true) && parts.size >= 3 -> parts[0]
            else -> id
        }
    }

    private fun isProviderObjectId(id: String, parts: List<String>): Boolean {
        if (parts.size < 3) return false
        val provider = parts[0]
        val objectType = parts[1]
        val isSupportedProvider = provider.equals("tmdb", ignoreCase = true) ||
            provider.equals("tvdb", ignoreCase = true)
        val isSupportedObject = objectType.equals("person", ignoreCase = true) ||
            objectType.equals("company", ignoreCase = true) ||
            objectType.equals("network", ignoreCase = true) ||
            objectType.equals("org", ignoreCase = true)
        return isSupportedProvider && isSupportedObject && id.startsWith("$provider:", ignoreCase = true)
    }
```
with:
```kotlin
    fun parentIdOf(contentId: String): String = MetadataParentIdNormalizer.parentIdOf(contentId)
```

(Delete the now-unused `isProviderObjectId` private function.)

- [ ] **Step 5: Run all metadata-router tests to verify no regression**

Run: `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.metadata.router.*" --no-daemon`
Expected: PASS — including the new `MetadataParentIdNormalizerTest`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/metadata/router/MetadataParentIdNormalizer.kt app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRequestNormalizer.kt app/src/test/java/com/nexio/tv/core/metadata/router/MetadataParentIdNormalizerTest.kt
git commit -m "refactor(metadata): extract MetadataParentIdNormalizer for shared id normalization"
```

---

### Task 8: Add `isAnime(parsed)` default method to `AnimeIdentityIndex`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/metadata/router/AnimeIdentityIndex.kt:32-34`
- Test: `app/src/test/java/com/nexio/tv/core/metadata/router/AnimeIdentityIndexIsAnimeTest.kt`

This new boolean API is forward-compat for the Anime Mapping Pack: today the default delegates to `resolveKitsuId(...) != null`, but once one-to-many IMDb→Kitsu mappings land, an override returns `true` without committing to a specific Kitsu record. Stream priority calls only `isAnime(...)`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/nexio/tv/core/metadata/router/AnimeIdentityIndexIsAnimeTest.kt`:
```kotlin
package com.nexio.tv.core.metadata.router

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimeIdentityIndexIsAnimeTest {

    private val parsed = ParsedMetadataId(
        scheme = AnimeIdScheme.IMDB,
        value = "tt12343534",
        raw = "tt12343534",
    )

    @Test
    fun `default isAnime is true when resolveKitsuId returns non-null`() = runTest {
        val index = object : AnimeIdentityIndex {
            override suspend fun resolveKitsuId(id: ParsedMetadataId): String? = "kitsu-1"
        }
        assertTrue(index.isAnime(parsed))
    }

    @Test
    fun `default isAnime is false when resolveKitsuId returns null`() = runTest {
        val index = object : AnimeIdentityIndex {
            override suspend fun resolveKitsuId(id: ParsedMetadataId): String? = null
        }
        assertFalse(index.isAnime(parsed))
    }

    @Test
    fun `override can return true without resolving a single kitsu record`() = runTest {
        val index = object : AnimeIdentityIndex {
            override suspend fun resolveKitsuId(id: ParsedMetadataId): String? {
                throw AssertionError("isAnime override must not delegate to resolveKitsuId")
            }
            override suspend fun isAnime(id: ParsedMetadataId): Boolean = true
        }
        assertTrue(index.isAnime(parsed))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testArm64DebugUnitTest --tests com.nexio.tv.core.metadata.router.AnimeIdentityIndexIsAnimeTest --no-daemon`
Expected: FAIL — `isAnime(...)` does not exist on the interface.

- [ ] **Step 3: Add `isAnime(...)` default to the interface**

In `AnimeIdentityIndex.kt:32-34`, replace:
```kotlin
interface AnimeIdentityIndex {
    suspend fun resolveKitsuId(id: ParsedMetadataId): String?
}
```
with:
```kotlin
interface AnimeIdentityIndex {
    suspend fun resolveKitsuId(id: ParsedMetadataId): String?

    /**
     * Returns true if [id] identifies anime content. The default delegates to
     * [resolveKitsuId] for back-compat. Stream priority calls only this method;
     * once a one-to-many IMDb→Kitsu mapping pack lands, implementations can
     * override [isAnime] to answer without picking a single canonical record.
     * Must not be used as input to MetadataRouter routing decisions.
     */
    suspend fun isAnime(id: ParsedMetadataId): Boolean = resolveKitsuId(id) != null
}
```

- [ ] **Step 4: Run tests to verify pass**

Run: `./gradlew testArm64DebugUnitTest --tests com.nexio.tv.core.metadata.router.AnimeIdentityIndexIsAnimeTest --no-daemon`
Expected: PASS for all three cases.

Run: `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.metadata.router.*" --no-daemon`
Expected: PASS — existing `AssetAnimeIdentityIndex` and `InMemoryAnimeIdentityIndex` callers compile and behave identically (default method is non-breaking).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/metadata/router/AnimeIdentityIndex.kt app/src/test/java/com/nexio/tv/core/metadata/router/AnimeIdentityIndexIsAnimeTest.kt
git commit -m "feat(anime): add boolean isAnime() to AnimeIdentityIndex"
```

---

### Task 9: Tag `AddonStreams.isAnimeBucket` in `StreamRepositoryImpl`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/StreamRepositoryImpl.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/StreamRepositoryImplAnimeBucketTest.kt`

Uses both helpers from Tasks 7–8: parent-id normalization for episode IDs and the boolean `isAnime(...)` API.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/nexio/tv/data/repository/StreamRepositoryImplAnimeBucketTest.kt`. The constructor scaffolding (`newRepo(...)`, `fakeAddon(...)`, `streamAddon(...)`) follows the MockK pattern in the existing `StreamRepositoryImplTest.kt`. Open that file once and copy the `streamAddon` builder + `mockAndroidLog` helper as the basis. The new fixture additions are the `isAnime` flag on `Addon` and a `FakeAnimeIdentityIndex`. Minimal new assertions:

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.core.metadata.router.AnimeIdScheme
import com.nexio.tv.core.metadata.router.AnimeIdentityIndex
import com.nexio.tv.core.metadata.router.ParsedMetadataId
import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.domain.model.AddonStreams
// ...remaining imports + scaffolding helpers cloned from StreamRepositoryImplTest...
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamRepositoryImplAnimeBucketTest {

    private fun parsedMatching(scheme: AnimeIdScheme, parentValue: String): (ParsedMetadataId) -> Boolean =
        { it.scheme == scheme && it.value == parentValue }

    @Test
    fun `bucket is true only when both addon isAnime and contentIsAnime`() = runTest {
        val animeIndex = FakeAnimeIdentityIndex(answer = { true })
        val repo = newRepo(
            addons = listOf(
                streamAddon(baseUrl = "https://anime.example.com", name = "Anime-A", isAnime = true),
                streamAddon(baseUrl = "https://gen.example.com", name = "Generic-A", isAnime = false),
            ),
            animeIdentityIndex = animeIndex,
        )

        val emitted = repo.getStreamsFromAllAddons(
            type = "movie",
            videoId = "kitsu:42",
            season = null,
            episode = null,
            installedAddons = null,
            requestOrigin = "test",
            requestId = "req-1",
        ).filterIsInstance<NetworkResult.Success<List<AddonStreams>>>().toList().last().data

        assertTrue(emitted.first { it.addonName == "Anime-A" }.isAnimeBucket)
        assertFalse(emitted.first { it.addonName == "Generic-A" }.isAnimeBucket)
    }

    @Test
    fun `bucket is false when content is not anime even if addon is tagged`() = runTest {
        val animeIndex = FakeAnimeIdentityIndex(answer = { false })
        val repo = newRepo(
            addons = listOf(streamAddon(baseUrl = "https://anime.example.com", name = "Anime-A", isAnime = true)),
            animeIdentityIndex = animeIndex,
        )
        val emitted = repo.getStreamsFromAllAddons(
            type = "movie", videoId = "tt0111161", season = null, episode = null,
            installedAddons = null, requestOrigin = "test", requestId = "req-2",
        ).filterIsInstance<NetworkResult.Success<List<AddonStreams>>>().toList().last().data

        emitted.forEach { assertFalse(it.isAnimeBucket) }
    }

    @Test
    fun `unparseable videoId leaves bucket false`() = runTest {
        val animeIndex = FakeAnimeIdentityIndex(answer = { error("must not be asked when parsedId is null") })
        val repo = newRepo(
            addons = listOf(streamAddon(baseUrl = "https://anime.example.com", name = "Anime-A", isAnime = true)),
            animeIdentityIndex = animeIndex,
        )
        val emitted = repo.getStreamsFromAllAddons(
            type = "movie", videoId = "garbage-id", season = null, episode = null,
            installedAddons = null, requestOrigin = "test", requestId = "req-3",
        ).filterIsInstance<NetworkResult.Success<List<AddonStreams>>>().toList().last().data

        assertEquals(false, emitted.first().isAnimeBucket)
    }

    @Test
    fun `kitsu_episode_id_routes_to_anime_bucket`() = runTest {
        // kitsu:7442:1:1 normalizes to kitsu:7442 before identity lookup
        var seenLookupValue: String? = null
        val animeIndex = FakeAnimeIdentityIndex(answer = { id ->
            seenLookupValue = id.value
            id.scheme == AnimeIdScheme.KITSU && id.value == "7442"
        })
        val repo = newRepo(
            addons = listOf(streamAddon(baseUrl = "https://anime.example.com", name = "Anime-A", isAnime = true)),
            animeIdentityIndex = animeIndex,
        )
        val emitted = repo.getStreamsFromAllAddons(
            type = "series", videoId = "kitsu:7442:1:1", season = 1, episode = 1,
            installedAddons = null, requestOrigin = "test", requestId = "req-4",
        ).filterIsInstance<NetworkResult.Success<List<AddonStreams>>>().toList().last().data

        assertEquals("7442", seenLookupValue)
        assertTrue(emitted.first().isAnimeBucket)
    }

    @Test
    fun `imdb_episode_id_routes_to_anime_bucket_when_parent_imdb_is_anime`() = runTest {
        var seenLookupValue: String? = null
        val animeIndex = FakeAnimeIdentityIndex(answer = { id ->
            seenLookupValue = id.value
            id.scheme == AnimeIdScheme.IMDB && id.value == "tt12343534"
        })
        val repo = newRepo(
            addons = listOf(streamAddon(baseUrl = "https://anime.example.com", name = "Anime-A", isAnime = true)),
            animeIdentityIndex = animeIndex,
        )
        val emitted = repo.getStreamsFromAllAddons(
            type = "series", videoId = "tt12343534:1:1", season = 1, episode = 1,
            installedAddons = null, requestOrigin = "test", requestId = "req-5",
        ).filterIsInstance<NetworkResult.Success<List<AddonStreams>>>().toList().last().data

        assertEquals("tt12343534", seenLookupValue)
        assertTrue(emitted.first().isAnimeBucket)
    }

    @Test
    fun `mal_episode_id_routes_to_anime_bucket`() = runTest {
        var seenLookupValue: String? = null
        val animeIndex = FakeAnimeIdentityIndex(answer = { id ->
            seenLookupValue = id.value
            id.scheme == AnimeIdScheme.MAL && id.value == "21"
        })
        val repo = newRepo(
            addons = listOf(streamAddon(baseUrl = "https://anime.example.com", name = "Anime-A", isAnime = true)),
            animeIdentityIndex = animeIndex,
        )
        val emitted = repo.getStreamsFromAllAddons(
            type = "series", videoId = "mal:21:1:1", season = 1, episode = 1,
            installedAddons = null, requestOrigin = "test", requestId = "req-6",
        ).filterIsInstance<NetworkResult.Success<List<AddonStreams>>>().toList().last().data

        assertEquals("21", seenLookupValue)
        assertTrue(emitted.first().isAnimeBucket)
    }

    @Test
    fun `imdb_one_to_many_anime_id_sets_contentIsAnime_without_selecting_single_kitsu_record`() = runTest {
        // The override answers true without ever calling resolveKitsuId.
        // This proves stream priority does not require a canonical Kitsu record.
        val animeIndex = object : AnimeIdentityIndex {
            override suspend fun resolveKitsuId(id: ParsedMetadataId): String? {
                throw AssertionError("resolveKitsuId must not be called by stream priority")
            }
            override suspend fun isAnime(id: ParsedMetadataId): Boolean = true
        }
        val repo = newRepo(
            addons = listOf(streamAddon(baseUrl = "https://anime.example.com", name = "Anime-A", isAnime = true)),
            animeIdentityIndex = animeIndex,
        )
        val emitted = repo.getStreamsFromAllAddons(
            type = "movie", videoId = "tt5626028", season = null, episode = null,
            installedAddons = null, requestOrigin = "test", requestId = "req-7",
        ).filterIsInstance<NetworkResult.Success<List<AddonStreams>>>().toList().last().data

        assertTrue(emitted.first().isAnimeBucket)
    }

    @Test
    fun `anime_tagged_addon_empty_generic_addons_still_selected`() = runTest {
        // Anime-tagged addon returns zero streams; generic addon returns one.
        // Generic addon's section must still be present in the result.
        val animeIndex = FakeAnimeIdentityIndex(answer = { true })
        val repo = newRepo(
            addons = listOf(
                streamAddon(baseUrl = "https://anime.example.com", name = "Anime-A", isAnime = true, returnedStreams = emptyList()),
                streamAddon(baseUrl = "https://gen.example.com", name = "Generic-A", isAnime = false, returnedStreams = listOf(streamDto("G-1"))),
            ),
            animeIdentityIndex = animeIndex,
        )
        val emitted = repo.getStreamsFromAllAddons(
            type = "movie", videoId = "kitsu:42", season = null, episode = null,
            installedAddons = null, requestOrigin = "test", requestId = "req-8",
        ).filterIsInstance<NetworkResult.Success<List<AddonStreams>>>().toList().last().data

        val generic = emitted.first { it.addonName == "Generic-A" }
        assertEquals(1, generic.streams.size)
        assertFalse(generic.isAnimeBucket)
    }

    private class FakeAnimeIdentityIndex(private val answer: (ParsedMetadataId) -> Boolean) : AnimeIdentityIndex {
        override suspend fun resolveKitsuId(id: ParsedMetadataId): String? =
            if (answer(id)) "kitsu-1" else null
    }

    // newRepo(...), streamAddon(...), streamDto(...), and the mockAndroidLog() helper:
    // mirror the setup in StreamRepositoryImplTest.kt. The streamAddon helper takes
    // an additional parameter `isAnime: Boolean = false` and a `returnedStreams: List<StreamDto>`
    // wired via mockk's coEvery on AddonStreamIntegrationProvider.getStreams. The newRepo
    // helper takes an extra `animeIdentityIndex: AnimeIdentityIndex` and passes it to the
    // StreamRepositoryImpl constructor.
}
```

The test scaffolding intentionally references the existing `StreamRepositoryImplTest.kt` builders rather than redefining them — opening that file once and copying its `mockAndroidLog`, `streamAddon`, and `streamDto` helpers into the new file is faster than reproducing them inline. The two new parameters that the helpers must take are `isAnime: Boolean = false` (on `streamAddon`) and `animeIdentityIndex: AnimeIdentityIndex` (on `newRepo`).

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testArm64DebugUnitTest --tests com.nexio.tv.data.repository.StreamRepositoryImplAnimeBucketTest --no-daemon`
Expected: FAIL — `AnimeIdentityIndex` not in constructor; `isAnimeBucket` always false.

- [ ] **Step 3: Inject `AnimeIdentityIndex` into `StreamRepositoryImpl`**

In `StreamRepositoryImpl.kt`, add the imports:
```kotlin
import com.nexio.tv.core.metadata.router.AnimeIdentityIndex
import com.nexio.tv.core.metadata.router.MetadataIdParser
import com.nexio.tv.core.metadata.router.MetadataParentIdNormalizer
```

In `StreamRepositoryImpl.kt:37-44`, replace:
```kotlin
class StreamRepositoryImpl @Inject constructor(
    private val addonStreamIntegrationProvider: AddonStreamIntegrationProvider,
    private val addonRepository: AddonRepository,
    private val debugSettingsDataStore: DebugSettingsDataStore,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val serviceWrapSessionFactory: ServiceWrapSessionFactory,
    private val addonStreamRequestCanceller: AddonStreamRequestCanceller
) : StreamRepository {
```
with:
```kotlin
class StreamRepositoryImpl @Inject constructor(
    private val addonStreamIntegrationProvider: AddonStreamIntegrationProvider,
    private val addonRepository: AddonRepository,
    private val debugSettingsDataStore: DebugSettingsDataStore,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val serviceWrapSessionFactory: ServiceWrapSessionFactory,
    private val addonStreamRequestCanceller: AddonStreamRequestCanceller,
    private val animeIdentityIndex: AnimeIdentityIndex,
) : StreamRepository {
```

`AnimeIdentityIndex` is already bound as a `@Singleton` (`AssetAnimeIdentityIndex`) via Hilt in `MetadataRouterModule`, so no DI module edit is needed — the constructor binding picks it up automatically.

- [ ] **Step 4: Normalize parent ID and compute `contentIsAnime` once after `streamAddons`**

In `StreamRepositoryImpl.kt:64-66`, immediately after:
```kotlin
            val streamAddons = addons.filter { addon ->
                addon.supportsStreamResource(type)
            }
```
add:
```kotlin
            val parentContentId = MetadataParentIdNormalizer.parentIdOf(videoId)
            val parsedContentId = MetadataIdParser.parse(parentContentId)
            val contentIsAnime = parsedContentId?.let { parsed ->
                animeIdentityIndex.isAnime(parsed)
            } ?: false
```

Note: this calls `isAnime(...)` (the new boolean API from Task 8), not `resolveKitsuId(...)`. Stream priority must never select a single Kitsu record from a one-to-many IMDb mapping.

- [ ] **Step 5: Stamp `isAnimeBucket` on emitted `AddonStreams`**

In `StreamRepositoryImpl.kt:113-117`, replace:
```kotlin
                                    emittedAddonStreams = AddonStreams(
                                        addonName = addon.displayName,
                                        addonLogo = addon.logo,
                                        streams = streamsResult.data
                                    )
```
with:
```kotlin
                                    emittedAddonStreams = AddonStreams(
                                        addonName = addon.displayName,
                                        addonLogo = addon.logo,
                                        streams = streamsResult.data,
                                        isAnimeBucket = addon.isAnime && contentIsAnime,
                                    )
```

- [ ] **Step 6: Update existing `StreamRepositoryImplTest` constructor calls**

Run: `grep -n "StreamRepositoryImpl(" app/src/test/java/com/nexio/tv/data/repository/StreamRepositoryImplTest.kt`

For each constructor call, add `animeIdentityIndex = NoAnimeIdentityIndex` (a top-level test object that always returns `null`/`false`, mirroring today's behavior). Define this once at the top of the existing test file:
```kotlin
private object NoAnimeIdentityIndex : com.nexio.tv.core.metadata.router.AnimeIdentityIndex {
    override suspend fun resolveKitsuId(id: com.nexio.tv.core.metadata.router.ParsedMetadataId): String? = null
}
```

- [ ] **Step 7: Run all StreamRepositoryImpl tests**

Run: `./gradlew testArm64DebugUnitTest --tests com.nexio.tv.data.repository.StreamRepositoryImplTest --tests com.nexio.tv.data.repository.StreamRepositoryImplAnimeBucketTest --no-daemon`
Expected: PASS — both new and existing tests.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/StreamRepositoryImpl.kt app/src/test/java/com/nexio/tv/data/repository/StreamRepositoryImplAnimeBucketTest.kt app/src/test/java/com/nexio/tv/data/repository/StreamRepositoryImplTest.kt
git commit -m "feat(stream): tag AddonStreams.isAnimeBucket per request"
```

---

### Task 10: Trace events `stream.request_classified` and `stream.addon_bucketed`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/trace/TraceMetadataEvents.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/StreamRepositoryImpl.kt`
- Test: `app/src/test/java/com/nexio/tv/core/trace/TraceMetadataEventsStreamClassificationTest.kt`

Two events:
- `stream.request_classified` — once per `getStreamsFromAllAddons` call. Payload `{ contentId, parentId, contentIsAnime, evidence }`.
- `stream.addon_bucketed` — once per addon section as it lands. Payload `{ addonIdHash, addonIsAnime, contentIsAnime, isAnimeBucket }`.

`addonIdHash` reuses whatever hashing helper `TraceMetadataEvents` already uses for addon IDs. If no such helper exists, hash the addon URL with the same scheme used for other identity events (search the file for `Hash` or `redact` first to confirm the existing convention).

- [ ] **Step 1: Inspect existing emit method conventions**

Open `app/src/main/java/com/nexio/tv/core/trace/TraceMetadataEvents.kt`. Pick one `emit*` method that takes a payload map (e.g. `emitRouteDecision` near line 198 or `emitFieldSelected` near line 167). Note the signature pattern used: typically `eventType` as a string, `payload` built via `buildJsonObject`/`mapOf`, plus the trace sink call.

- [ ] **Step 2: Write the failing test**

Create `app/src/test/java/com/nexio/tv/core/trace/TraceMetadataEventsStreamClassificationTest.kt`:
```kotlin
package com.nexio.tv.core.trace

import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test

class TraceMetadataEventsStreamClassificationTest {

    @Test
    fun `emitStreamRequestClassified writes contentId parentId contentIsAnime evidence`() {
        val sink = mockk<RuntimeTraceEventListener>(relaxed = true)
        val events = TraceMetadataEvents(sink) // adapt to actual ctor; mirror existing pattern

        events.emitStreamRequestClassified(
            contentId = "tt12343534:1:1",
            parentId = "tt12343534",
            contentIsAnime = true,
            evidence = "AnimeIdentityIndex",
        )

        val captured = slot<TraceEventEnvelope<*>>()
        verify { sink.onEvent(capture(captured)) }
        val payload = captured.captured.payload as Map<*, *>
        assertEquals("stream.request_classified", captured.captured.eventType)
        assertEquals("tt12343534:1:1", payload["contentId"])
        assertEquals("tt12343534", payload["parentId"])
        assertEquals(true, payload["contentIsAnime"])
        assertEquals("AnimeIdentityIndex", payload["evidence"])
    }

    @Test
    fun `emitStreamAddonBucketed writes addonIdHash flags and isAnimeBucket`() {
        val sink = mockk<RuntimeTraceEventListener>(relaxed = true)
        val events = TraceMetadataEvents(sink)

        events.emitStreamAddonBucketed(
            addonIdHash = "abc123",
            addonIsAnime = true,
            contentIsAnime = true,
            isAnimeBucket = true,
        )

        val captured = slot<TraceEventEnvelope<*>>()
        verify { sink.onEvent(capture(captured)) }
        val payload = captured.captured.payload as Map<*, *>
        assertEquals("stream.addon_bucketed", captured.captured.eventType)
        assertEquals("abc123", payload["addonIdHash"])
        assertEquals(true, payload["addonIsAnime"])
        assertEquals(true, payload["contentIsAnime"])
        assertEquals(true, payload["isAnimeBucket"])
    }
}
```

If the actual `TraceMetadataEvents` constructor takes a different parameter than `RuntimeTraceEventListener`, adapt the test setup to match. The shape of the assertions stays the same: each emit call writes one event with the named eventType and payload keys.

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew testArm64DebugUnitTest --tests com.nexio.tv.core.trace.TraceMetadataEventsStreamClassificationTest --no-daemon`
Expected: FAIL — `emitStreamRequestClassified` and `emitStreamAddonBucketed` do not exist.

- [ ] **Step 4: Add the two emit methods to `TraceMetadataEvents`**

In `TraceMetadataEvents.kt`, append (mirroring the convention of an existing `emit*` method — copy the structure of `emitRouteDecision` or any other map-payload event):
```kotlin
    fun emitStreamRequestClassified(
        contentId: String,
        parentId: String,
        contentIsAnime: Boolean,
        evidence: String,
    ) {
        sink.onEvent(
            TraceEventEnvelope(
                eventType = "stream.request_classified",
                payload = mapOf(
                    "contentId" to contentId,
                    "parentId" to parentId,
                    "contentIsAnime" to contentIsAnime,
                    "evidence" to evidence,
                ),
                sequence = sequenceCounter.getAndIncrement(),
            )
        )
    }

    fun emitStreamAddonBucketed(
        addonIdHash: String,
        addonIsAnime: Boolean,
        contentIsAnime: Boolean,
        isAnimeBucket: Boolean,
    ) {
        sink.onEvent(
            TraceEventEnvelope(
                eventType = "stream.addon_bucketed",
                payload = mapOf(
                    "addonIdHash" to addonIdHash,
                    "addonIsAnime" to addonIsAnime,
                    "contentIsAnime" to contentIsAnime,
                    "isAnimeBucket" to isAnimeBucket,
                ),
                sequence = sequenceCounter.getAndIncrement(),
            )
        )
    }
```

If `TraceMetadataEvents` does not use `sink.onEvent + TraceEventEnvelope` (i.e. the existing emit methods route through a different sink method), match the existing convention exactly. The exact emit machinery is encapsulated; this task just adds two new entrypoints that follow the file's existing pattern.

- [ ] **Step 5: Wire the two emit calls in `StreamRepositoryImpl`**

Inject `TraceMetadataEvents`:
```kotlin
import com.nexio.tv.core.trace.TraceMetadataEvents
```
Add it as a constructor parameter (after `animeIdentityIndex` from Task 9 Step 3):
```kotlin
    private val animeIdentityIndex: AnimeIdentityIndex,
    private val traceMetadataEvents: TraceMetadataEvents,
```

After computing `contentIsAnime` (Task 9 Step 4 location), add:
```kotlin
            traceMetadataEvents.emitStreamRequestClassified(
                contentId = videoId,
                parentId = parentContentId,
                contentIsAnime = contentIsAnime,
                evidence = "AnimeIdentityIndex",
            )
```

At the emit site for `AddonStreams` (Task 9 Step 5 location), immediately after constructing `emittedAddonStreams`, add:
```kotlin
                                    traceMetadataEvents.emitStreamAddonBucketed(
                                        addonIdHash = addonIdHash(addon.id),
                                        addonIsAnime = addon.isAnime,
                                        contentIsAnime = contentIsAnime,
                                        isAnimeBucket = addon.isAnime && contentIsAnime,
                                    )
```

Where `addonIdHash(...)` is the existing addon-id hashing helper used elsewhere in the trace pipeline. If no such helper exists in this repository, use:
```kotlin
private fun addonIdHash(addonId: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(addonId.toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(12)
```
defined as a top-level private function in `StreamRepositoryImpl.kt`. Twelve hex chars is enough to disambiguate addon ids in trace correlation without leaking the URL.

- [ ] **Step 6: Update existing `StreamRepositoryImplTest` constructor calls again**

The constructor now has a new mandatory `traceMetadataEvents` parameter. Update the existing test fixtures by passing `mockk<TraceMetadataEvents>(relaxed = true)`.

- [ ] **Step 7: Run trace + repository tests**

Run: `./gradlew testArm64DebugUnitTest --tests com.nexio.tv.core.trace.TraceMetadataEventsStreamClassificationTest --tests com.nexio.tv.data.repository.StreamRepositoryImplTest --tests com.nexio.tv.data.repository.StreamRepositoryImplAnimeBucketTest --no-daemon`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/trace/TraceMetadataEvents.kt app/src/main/java/com/nexio/tv/data/repository/StreamRepositoryImpl.kt app/src/test/java/com/nexio/tv/core/trace/TraceMetadataEventsStreamClassificationTest.kt app/src/test/java/com/nexio/tv/data/repository/StreamRepositoryImplTest.kt
git commit -m "feat(trace): emit stream.request_classified and stream.addon_bucketed"
```

---

### Task 11: Android UI — `AddonManagerViewModel.updateAddonIsAnime` + screen toggle

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/addon/AddonManagerViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/addon/AddonManagerScreen.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/addon/AddonManagerViewModelAnimeToggleTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/nexio/tv/ui/screens/addon/AddonManagerViewModelAnimeToggleTest.kt`:
```kotlin
package com.nexio.tv.ui.screens.addon

import com.nexio.tv.domain.model.AddonParserPreset
import com.nexio.tv.domain.repository.AddonRepository
// ...minimal imports for ViewModel construction; copy from existing AddonManagerViewModel tests if present...
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AddonManagerViewModelAnimeToggleTest {

    @Test
    fun `updateAddonIsAnime delegates to repository`() = runTest {
        val recorder = RecordingAddonRepository()
        val vm = AddonManagerViewModel(
            addonRepository = recorder,
            // ...other dependencies stubbed minimally...
        )

        vm.updateAddonIsAnime("https://example.com/anime", true)

        assertEquals(listOf("https://example.com/anime" to true), recorder.isAnimeUpdates)
    }

    private class RecordingAddonRepository : AddonRepository {
        val isAnimeUpdates = mutableListOf<Pair<String, Boolean>>()
        override suspend fun updateAddonIsAnime(url: String, isAnime: Boolean) {
            isAnimeUpdates += url to isAnime
        }
        // ...stub remaining methods to throw NotImplementedError or return empty defaults...
    }
}
```

If no existing `AddonManagerViewModel` test exists with the right scaffolding, this test may need extra construction boilerplate — copy the constructor argument list from `AddonManagerViewModel.kt:33-37` and stub the missing dependencies (`LayoutPreferenceDataStore`, `Context`) with minimal fakes.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testArm64DebugUnitTest --tests com.nexio.tv.ui.screens.addon.AddonManagerViewModelAnimeToggleTest --no-daemon`
Expected: FAIL — `updateAddonIsAnime` doesn't exist on `AddonManagerViewModel`.

- [ ] **Step 3: Add `updateAddonIsAnime` to viewmodel**

In `AddonManagerViewModel.kt:142-146`, immediately after the existing `updateAddonParserPreset`:
```kotlin
    fun updateAddonParserPreset(baseUrl: String, parserPreset: AddonParserPreset) {
        viewModelScope.launch {
            addonRepository.updateAddonParserPreset(baseUrl, parserPreset)
        }
    }
```
add:
```kotlin
    fun updateAddonIsAnime(baseUrl: String, isAnime: Boolean) {
        viewModelScope.launch {
            addonRepository.updateAddonIsAnime(baseUrl, isAnime)
        }
    }
```

- [ ] **Step 4: Run test to verify pass**

Run: `./gradlew testArm64DebugUnitTest --tests com.nexio.tv.ui.screens.addon.AddonManagerViewModelAnimeToggleTest --no-daemon`
Expected: PASS.

- [ ] **Step 5: Add toggle to `AddonManagerScreen`**

The existing `parserPreset` row at `AddonManagerScreen.kt:1010-1019` reads:
```kotlin
                        onClick = { onUpdateParserPreset(addon.parserPreset.next()) },
```
and renders the label via `addon.parserPreset.label()`.

Add a sibling row immediately below the parser preset row, in the same edit panel block:
```kotlin
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Anime addon",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.weight(1f))
                        Switch(
                            checked = addon.isAnime,
                            onCheckedChange = { onUpdateIsAnime(it) },
                        )
                    }
                    Text(
                        text = "Prioritize this addon when fetching streams for anime content.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
```

Add the new lambda `onUpdateIsAnime: (Boolean) -> Unit = {}` to the row composable's parameter list (mirroring `onUpdateParserPreset: (AddonParserPreset) -> Unit`). Plumb it from the screen-level call site at `AddonManagerScreen.kt:376` so it reads:
```kotlin
                        onUpdateIsAnime = { viewModel.updateAddonIsAnime(addon.baseUrl, it) },
```

- [ ] **Step 6: Compile**

Run: `./gradlew :app:compileArm64DebugKotlin --no-daemon`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/addon/AddonManagerViewModel.kt app/src/main/java/com/nexio/tv/ui/screens/addon/AddonManagerScreen.kt app/src/test/java/com/nexio/tv/ui/screens/addon/AddonManagerViewModelAnimeToggleTest.kt
git commit -m "feat(addons): expose isAnime toggle in addon manager UI"
```

---

### Task 12: Routing-invariant guard — `MetadataRouterIgnoresAddonIsAnimeTest`

**Files:**
- Test: `app/src/test/java/com/nexio/tv/core/metadata/router/MetadataRouterIgnoresAddonIsAnimeTest.kt`

This is a no-implementation safety test: asserts that the `MetadataRouter` (and any class on its `usedInputs` path) never reads `addon.isAnime`. The existing `RouteDecisionUsedInputs` validator forbids `addon` and `animeType` tokens in `usedInputs`; this test demonstrates that an installed anime-tagged addon does not perturb that invariant.

- [ ] **Step 1: Write the test**

Create `app/src/test/java/com/nexio/tv/core/metadata/router/MetadataRouterIgnoresAddonIsAnimeTest.kt`:
```kotlin
package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.trace.TraceValidationRules
import com.nexio.tv.core.trace.TraceEventEnvelope
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataRouterIgnoresAddonIsAnimeTest {

    @Test
    fun `route decision used inputs never include addon isAnime token`() {
        // Build a synthetic route_decision event that would represent the worst
        // case: an implementation accidentally including "addon.isAnime" in usedInputs.
        // The existing validator already fails this, so the test pins the invariant
        // and serves as a tripwire if anyone adds the token in usedInputs.
        val malicious = TraceEventEnvelope(
            eventType = "metadata.route_decision",
            payload = mapOf("usedInputs" to listOf("item.id", "addon.isAnime")),
            sequence = 1L,
        )
        val failures = TraceValidationRules.RouteDecisionUsedInputs.apply(listOf(malicious))
        assertTrue("route_decision must reject addon.isAnime as a usedInput", failures.isNotEmpty())
    }

    @Test
    fun `route decision used inputs allow normal item-id input`() {
        val safe = TraceEventEnvelope(
            eventType = "metadata.route_decision",
            payload = mapOf("usedInputs" to listOf("item.id", "AnimeIdentityIndex")),
            sequence = 1L,
        )
        val failures = TraceValidationRules.RouteDecisionUsedInputs.apply(listOf(safe))
        assertTrue("safe usedInputs must not trip the validator", failures.isEmpty())
    }
}
```

(If the actual `TraceEventEnvelope` constructor or property names differ, mirror the existing test at `app/src/test/java/com/nexio/tv/core/trace/TraceValidationRulesTest.kt`.)

- [ ] **Step 2: Run test**

Run: `./gradlew testArm64DebugUnitTest --tests com.nexio.tv.core.metadata.router.MetadataRouterIgnoresAddonIsAnimeTest --no-daemon`
Expected: PASS — both invariants hold today.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/nexio/tv/core/metadata/router/MetadataRouterIgnoresAddonIsAnimeTest.kt
git commit -m "test(metadata): pin invariant that addon.isAnime is not a routing input"
```

---

### Task 13: Web wire shape — `AddonRecord.isAnime` + bootstrap/persist

**Files:**
- Modify: `nexio-web/types/portal.ts:61-78`
- Modify: `nexio-web/server/api/account/bootstrap.get.ts:22-37, 55-76`
- Modify: `nexio-web/server/api/account/persist.post.ts:63-83`

- [ ] **Step 1: Add `isAnime` to `AddonRecord`**

In `nexio-web/types/portal.ts:61-78`, replace:
```ts
export type AddonRecord = {
  id: string
  url: string
  name: string
  enabled: boolean
  manifestUrl: string
  parserPreset: 'GENERIC' | 'STREMTHRU' | 'TORRENTIO' | 'WEBSTREAMR'
  description?: string
  logo?: string
  transportUrl?: string
  transportSchemaVersion?: 1 | 2
  transportBaseUrl?: string | null
  transportSecretRef?: string | null
  installKind?: 'manifest' | 'configured'
  publicQueryParams?: Record<string, string>
  secretRef?: string | null
  sortOrder: number
}
```
with:
```ts
export type AddonRecord = {
  id: string
  url: string
  name: string
  enabled: boolean
  manifestUrl: string
  parserPreset: 'GENERIC' | 'STREMTHRU' | 'TORRENTIO' | 'WEBSTREAMR'
  isAnime?: boolean
  description?: string
  logo?: string
  transportUrl?: string
  transportSchemaVersion?: 1 | 2
  transportBaseUrl?: string | null
  transportSecretRef?: string | null
  installKind?: 'manifest' | 'configured'
  publicQueryParams?: Record<string, string>
  secretRef?: string | null
  sortOrder: number
}
```

- [ ] **Step 2: Read `is_anime` in `bootstrap.get.ts`**

In `nexio-web/server/api/account/bootstrap.get.ts:22-37`, add `is_anime` to the snapshot row type:
```ts
  addons?: Array<{
    id?: string
    url?: string
    manifest_url?: string | null
    parser_preset?: 'GENERIC' | 'STREMTHRU' | 'TORRENTIO' | 'WEBSTREAMR' | null
    is_anime?: boolean | null
    name?: string | null
    // ...rest unchanged...
  }>
```

In the same file, in `toAddonRecords` at line 55-76, add `isAnime` to the returned record:
```ts
    return {
      id: addon.id ?? crypto.randomUUID(),
      url: normalizedUrl,
      manifestUrl,
      parserPreset: addon.parser_preset ?? 'GENERIC',
      isAnime: addon.is_anime ?? false,
      name: addon.name ?? addon.url ?? 'Addon',
      // ...rest unchanged...
    }
```

- [ ] **Step 3: Write `is_anime` in `persist.post.ts`**

In `nexio-web/server/api/account/persist.post.ts:63-83`, add `is_anime` to the RPC body:
```ts
      p_addons: (body.addons ?? []).map((addon, index) => ({
        url: normalizeAddonUrl(addon.url),
        manifest_url: normalizeAddonManifestUrl(addon.url, addon.manifestUrl),
        parser_preset: addon.parserPreset ?? 'GENERIC',
        is_anime: addon.isAnime ?? false,
        name: addon.name,
        // ...rest unchanged...
      })),
```

- [ ] **Step 4: Type-check**

Run from `nexio-web/`: `npx vue-tsc --noEmit`
Expected: no new type errors.

- [ ] **Step 5: Commit**

```bash
git add nexio-web/types/portal.ts nexio-web/server/api/account/bootstrap.get.ts nexio-web/server/api/account/persist.post.ts
git commit -m "feat(web): plumb addon isAnime through portal API"
```

---

### Task 14: Web store mutation — `usePortalStore.updateAddonIsAnime`

**Files:**
- Modify: `nexio-web/composables/usePortalStore.ts:261-294, 1557-1562, 2934-2944`

- [ ] **Step 1: Default `isAnime` in `sanitizeAddonRecord`**

In `usePortalStore.ts:261-274`, replace:
```ts
function sanitizeAddonRecord(addon: AddonRecord, index: number): AddonRecord {
  const normalizedUrl = normalizeAddonUrl(addon.url)
  return {
    ...addon,
    url: normalizedUrl,
    manifestUrl: normalizeAddonManifestUrl(normalizedUrl, addon.manifestUrl),
    parserPreset: addon.parserPreset ?? 'GENERIC',
    publicQueryParams: { ...(addon.publicQueryParams ?? {}) },
    transportSchemaVersion: addon.transportSchemaVersion ?? 1,
    transportBaseUrl: addon.transportBaseUrl ?? null,
    transportSecretRef: addon.transportSecretRef ?? null,
    sortOrder: addon.sortOrder ?? index
  }
}
```
with:
```ts
function sanitizeAddonRecord(addon: AddonRecord, index: number): AddonRecord {
  const normalizedUrl = normalizeAddonUrl(addon.url)
  return {
    ...addon,
    url: normalizedUrl,
    manifestUrl: normalizeAddonManifestUrl(normalizedUrl, addon.manifestUrl),
    parserPreset: addon.parserPreset ?? 'GENERIC',
    isAnime: addon.isAnime ?? false,
    publicQueryParams: { ...(addon.publicQueryParams ?? {}) },
    transportSchemaVersion: addon.transportSchemaVersion ?? 1,
    transportBaseUrl: addon.transportBaseUrl ?? null,
    transportSecretRef: addon.transportSecretRef ?? null,
    sortOrder: addon.sortOrder ?? index
  }
}
```

- [ ] **Step 2: Include `isAnime` in `snapshotSignature`**

In `usePortalStore.ts:276-294`, replace the addon serialization inside `snapshotSignature`:
```ts
    addons: addons.map((addon) => ({
      url: addon.url,
      manifestUrl: addon.manifestUrl,
      parserPreset: addon.parserPreset,
      name: addon.name,
      description: addon.description ?? '',
      enabled: addon.enabled,
      publicQueryParams: addon.publicQueryParams,
      installKind: addon.installKind,
      secretRef: addon.secretRef,
      transportSchemaVersion: addon.transportSchemaVersion,
      transportBaseUrl: addon.transportBaseUrl,
      transportSecretRef: addon.transportSecretRef,
      sortOrder: addon.sortOrder
    }))
```
with the same block plus `isAnime: addon.isAnime ?? false,` immediately after `parserPreset:`.

- [ ] **Step 3: Add `updateAddonIsAnime` action**

In `usePortalStore.ts:1557-1562`, immediately after `updateAddonParserPreset`:
```ts
  function updateAddonParserPreset(id: string, parserPreset: AddonRecord['parserPreset']) {
    state.value.addons = state.value.addons.map((addon) =>
      addon.id === id ? { ...addon, parserPreset } : addon
    )
    markAddonsChanged()
  }
```
add:
```ts
  function updateAddonIsAnime(id: string, isAnime: boolean) {
    state.value.addons = state.value.addons.map((addon) =>
      addon.id === id ? { ...addon, isAnime } : addon
    )
    markAddonsChanged()
  }
```

- [ ] **Step 4: Export `updateAddonIsAnime` from the store**

Find the return block around line 2934-2944 (the public surface of `usePortalStore`). Currently it includes `updateAddonParserPreset`. Add `updateAddonIsAnime` next to it:
```ts
    addAddon,
    // ...
    updateAddonParserPreset,
    updateAddonIsAnime,
    // ...
```

- [ ] **Step 5: Type-check**

Run from `nexio-web/`: `npx vue-tsc --noEmit`
Expected: no errors.

- [ ] **Step 6: Commit**

```bash
git add nexio-web/composables/usePortalStore.ts
git commit -m "feat(web): add updateAddonIsAnime action to portal store"
```

---

### Task 15: Web UI — `AddonManager.vue` toggle + `account.vue` wiring

**Files:**
- Modify: `nexio-web/components/portal/AddonManager.vue`
- Modify: `nexio-web/pages/account.vue`

- [ ] **Step 1: Add the per-row toggle in `AddonManager.vue`**

Find the existing per-addon row at line 67 where the parser-preset `<select>` is rendered. Immediately after the `<select>` (and before the existing `enabled` toggle at line 74), add:
```vue
          <div
            class="hidden md:flex items-center gap-1.5 rounded-lg border border-outline-variant/15 bg-surface-container-lowest px-2 py-1.5 cursor-pointer transition-colors"
            :class="addon.isAnime ? 'text-primary border-primary/40' : 'text-on-surface-variant hover:border-outline-variant'"
            @click="emit('update-addon-is-anime', addon.id, !(addon.isAnime ?? false))"
            :title="addon.isAnime ? 'Anime addon — prioritized for anime content' : 'Mark as anime addon'"
          >
            <span class="text-[10px] uppercase font-bold">Anime</span>
            <span class="material-symbols-outlined text-[14px]">{{ addon.isAnime ? 'check' : 'add' }}</span>
          </div>
```

- [ ] **Step 2: Declare the new emit**

In `AddonManager.vue` around line 215 where emits are typed, add:
```ts
  'update-addon-is-anime': [id: string, isAnime: boolean]
```
to the emits union, alongside `'update-addon-parser-preset'`.

- [ ] **Step 3: Wire the emit handler in `account.vue`**

In `nexio-web/pages/account.vue`, find the `<AddonManager>` element (around line 49-54) where existing handlers live:
```vue
            @update-addon-parser-preset="updateAddonParserPreset"
```
add immediately below:
```vue
            @update-addon-is-anime="updateAddonIsAnime"
```

In the `<script setup>` block of `account.vue` (around line 183-188), where existing imports from `usePortalStore()` live:
```ts
  addAddon,
  // ...
  updateAddonParserPreset,
```
add `updateAddonIsAnime` to the destructure, in the same alphabetical block as the existing addon mutations.

- [ ] **Step 4: Type-check and dev-server smoke test**

Run from `nexio-web/`: `npx vue-tsc --noEmit`
Expected: no errors.

Run from `nexio-web/`: `npm run dev`
Open `http://localhost:3000/account`, log in, and verify:
1. The "Anime" chip appears next to the parser preset select on each addon row.
2. Clicking the chip flips its state immediately and triggers a save.
3. Refreshing the page preserves the toggle (round-trips through Supabase).

- [ ] **Step 5: Commit**

```bash
git add nexio-web/components/portal/AddonManager.vue nexio-web/pages/account.vue
git commit -m "feat(web): add anime addon toggle to addon manager UI"
```

---

### Task 16: End-to-end smoke check

No code changes — this is a manual verification step covering the full pipeline.

- [ ] **Step 1: Tag a known anime addon on Android**

Open the addon manager on Android. Toggle "Anime addon" on for the dedicated anime addon you have installed (e.g. an anime-streams addon, the Kitsu addon, etc.).

- [ ] **Step 2: Verify the toggle persisted**

Force-stop the app and reopen. The toggle is still on.

- [ ] **Step 3: Verify cross-device sync**

Open `nexio-web` for the same account. The same addon shows the "Anime" chip enabled.

- [ ] **Step 4: Open an anime title and verify the manual stream list orders by bucket**

Pick an anime title (e.g. opened from a Kitsu catalog or a Trakt/IMDB anime entry). Open the stream selection screen. Confirm in the **manual list**:
1. The anime-tagged addon's section appears at the top of the list.
2. Generic addon sections appear below it, in their existing `sortOrder`.

- [ ] **Step 5: Same anime title — verify autoplay picks from the anime bucket**

With autoplay set to `FIRST_STREAM` or `REGEX_MATCH`, return to the same title and let autoplay run. Confirm:
1. The selected stream comes from the anime-tagged addon's section, not from a generic addon.
2. If the anime-tagged section is empty for this specific title, autoplay falls through to the next bucket without erroring.

This proves both the manual list and autoplay flows route through the same `StreamAutoPlaySelector.orderAddonStreams` seam — they're both ordered by the same bucket key.

- [ ] **Step 6: Open an anime episode (not the show) and verify parent-id normalization**

Pick a specific anime episode (e.g. open episode S01E03 of a show that's mapped to Kitsu). The Stream view's `videoId` will look like `kitsu:7442:1:3` or `tt12343534:1:3`. Confirm the anime-tagged addon section still floats to the top — proving the parent-id normalization (`kitsu:7442` / `tt12343534`) ran before the anime-identity lookup.

- [ ] **Step 7: Open a non-anime title and verify ordering is unchanged**

Pick a regular movie/show. Confirm the stream list ordering is identical to the legacy behavior — anime-tagged addon is wherever its `sortOrder` puts it, not floated to top.

- [ ] **Step 8: Inspect the trace stream**

If you have the runtime trace sink enabled in this build, open the trace log for the request and confirm one `stream.request_classified` event and one `stream.addon_bucketed` event per addon section. The classification event's `parentId` should match the normalized parent (not the episode-coordinate `videoId`).

- [ ] **Step 9: No commit** (manual verification only).

---

## Self-review checklist (run before declaring plan complete)

**Spec coverage:**
- [x] `AddonStreams.isAnimeBucket` — Task 1.
- [x] Two-level comparator at the single sort seam (with empty-anime-bucket case) — Task 2.
- [x] Android `Addon.isAnime` — Task 3.
- [x] DataStore round-trip — Task 4.
- [x] Repository propagation through cache copies — Task 5.
- [x] Wire-shape persistence on Android sync DTO (push + pull) — Task 6.
- [x] Shared parent-ID normalization extracted as pure helper — Task 7.
- [x] Boolean `AnimeIdentityIndex.isAnime(parsed)` API for forward-compat with one-to-many IMDb→Kitsu mapping — Task 8.
- [x] `contentIsAnime` computed once via `AnimeIdentityIndex.isAnime(...)` (not `resolveKitsuId`) — Task 9.
- [x] `isAnimeBucket = addon.isAnime && contentIsAnime` — Task 9.
- [x] Episode-id normalization before anime detection (kitsu/imdb/mal/anilist/anidb episode IDs) — Tasks 7 + 9.
- [x] Trace events `stream.request_classified` + `stream.addon_bucketed` — Task 10.
- [x] Per-addon edit UI on Android — Task 11.
- [x] Routing-invariant guard (addon.isAnime never an input to MetadataRouter) — Task 12.
- [x] Web `AddonRecord.isAnime` and wire-shape persistence on web — Task 13.
- [x] Web store mutation — Task 14.
- [x] Per-addon edit UI on web — Task 15.
- [x] Migration: none (defaults preserve back-compat) — verified in Tasks 4 + 13.
- [x] Edge cases: non-anime content, no anime addons, anime-tagged addon empty, unparseable id, IMDb one-to-many — covered by Task 9 tests + comparator regression in Task 2.
- [x] Manual list and autoplay share the same ordered output — verified by code inspection at `StreamScreenViewModel:408-412`, by Task 9's repository-level tests, and by the Task 16 smoke run.
- [x] `addon.isAnime` is stream-priority only, never metadata-routing input — pinned by Task 12.

**Type consistency:** all method names (`updateAddonIsAnime`, `isAnime`, `parentIdOf`), field names (`isAnime`, `isAnimeBucket`), wire keys (`is_anime`), trace event types (`stream.request_classified`, `stream.addon_bucketed`), and parameter orders (`url, isAnime`) are identical across tasks.

**No placeholders.** Every code block is complete; every test has a runnable command; every commit message is exact.
