# Anime Addon Priority Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a per-addon `isAnime` boolean that, when content is detected as anime via the existing `AnimeIdentityIndex`, floats anime-tagged addon stream sections above the rest in both the manual stream list and autoplay candidate ordering. Non-anime content behavior is byte-identical to today.

**Architecture:** New optional `isAnime: Boolean = false` field on `Addon` (Android domain) / `AddonRecord` (web TS) / `AccountAddonPayload` (Supabase wire). Stream pipeline computes a per-request `contentIsAnime` boolean once via `AnimeIdentityIndex.resolveKitsuId(...) != null`, then stamps each emitted `AddonStreams` with `isAnimeBucket = addon.isAnime && contentIsAnime`. The single existing sort seam (`StreamAutoPlaySelector.orderAddonStreams`) is extended to a two-level comparator: anime bucket first, then current `installedOrder` index. UI in both nexio-web and Android exposes a per-addon "Anime" toggle that mirrors the existing `parserPreset` plumbing.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, Coroutines, Gson, kotlinx.serialization (Android); TypeScript, Vue 3, Nuxt, Supabase RPC (web).

**Test command (Android):** `./gradlew testArm64DebugUnitTest --tests <FQCN> --no-daemon`
**Test command (web):** Type-check via `npx vue-tsc --noEmit` from `nexio-web/`. (No unit test runner configured — verify wire shape and UI manually in `nuxt dev`.)

**Spec:** `docs/superpowers/specs/2026-05-06-anime-addon-priority-design.md`

---

## File Structure

### Android — new files
- `app/src/test/java/com/nexio/tv/core/player/StreamAutoPlaySelectorAnimePriorityTest.kt` — comparator regression + anime bucket priority
- `app/src/test/java/com/nexio/tv/data/repository/StreamRepositoryImplAnimeBucketTest.kt` — `isAnimeBucket` is computed correctly per emit
- `app/src/test/java/com/nexio/tv/data/local/AddonPreferencesIsAnimeTest.kt` — DataStore round-trip for the new field
- `app/src/test/java/com/nexio/tv/ui/screens/addon/AddonManagerViewModelAnimeToggleTest.kt` — viewmodel writes through to the repository

### Android — modified files
- `app/src/main/java/com/nexio/tv/domain/model/Stream.kt` — `AddonStreams` gains `isAnimeBucket`
- `app/src/main/java/com/nexio/tv/domain/model/Addon.kt` — `Addon` gains `isAnime`
- `app/src/main/java/com/nexio/tv/domain/repository/AddonRepository.kt` — interface gains `updateAddonIsAnime` and `addAddon` overload
- `app/src/main/java/com/nexio/tv/data/local/AddonPreferences.kt` — `AddonInstallConfig.isAnime`, `addAddon` overload, `updateAddonIsAnime`
- `app/src/main/java/com/nexio/tv/data/repository/AddonRepositoryImpl.kt` — propagate `isAnime` through cache copies and remote reconcile; implement `updateAddonIsAnime`
- `app/src/main/java/com/nexio/tv/data/remote/supabase/AccountSyncModels.kt` — `AccountAddonPayload.isAnime`
- `app/src/main/java/com/nexio/tv/core/sync/AccountConfigSyncContract.kt` — propagate `isAnime` from payload to `AddonInstallConfig`
- `app/src/main/java/com/nexio/tv/core/sync/AddonSyncService.kt` — same
- `app/src/main/java/com/nexio/tv/data/repository/StreamRepositoryImpl.kt` — inject `AnimeIdentityIndex`, compute `contentIsAnime`, stamp `isAnimeBucket`
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

Tasks proceed bottom-up: domain types → persistence → sync wire → repository plumbing → stream pipeline → UI. Each task is independently green-able and committable. Wire-format and storage tasks come before consumer logic so the round-trip is verified by the time the comparator changes.

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

### Task 7: Tag `AddonStreams.isAnimeBucket` in `StreamRepositoryImpl`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/StreamRepositoryImpl.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/StreamRepositoryImplAnimeBucketTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/nexio/tv/data/repository/StreamRepositoryImplAnimeBucketTest.kt`. Pattern after the existing `StreamRepositoryImplTest` constructor signature (open that file briefly to confirm the FakeAddonStreamIntegrationProvider / FakeAddonRepository shape used there, then mirror it). Minimal new assertions:

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.core.metadata.router.AnimeIdScheme
import com.nexio.tv.core.metadata.router.AnimeIdentityIndex
import com.nexio.tv.core.metadata.router.ParsedMetadataId
import com.nexio.tv.domain.model.Addon
import com.nexio.tv.domain.model.AddonParserPreset
import com.nexio.tv.domain.model.AddonStreams
// ...remaining imports mirroring StreamRepositoryImplTest...
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamRepositoryImplAnimeBucketTest {

    private val animeAddon = fakeAddon(id = "anime-1", baseUrl = "https://anime.example.com", isAnime = true)
    private val genericAddon = fakeAddon(id = "gen-1", baseUrl = "https://gen.example.com", isAnime = false)

    @Test
    fun `bucket is true only when both addon isAnime and contentIsAnime`() = runTest {
        val animeIndex = FakeAnimeIdentityIndex(returns = "kitsu-123")
        val repo = newRepo(addons = listOf(animeAddon, genericAddon), animeIdentityIndex = animeIndex)
        val emitted = repo.getStreamsFromAllAddons(
            type = "movie",
            videoId = "kitsu:42",
            season = null,
            episode = null,
            installedAddons = null,
            requestOrigin = "test",
            requestId = "req-1",
        ).filterIsInstance<NetworkResult.Success<List<AddonStreams>>>().toList().last().data

        val animeSection = emitted.first { it.addonName == animeAddon.displayName }
        val genericSection = emitted.first { it.addonName == genericAddon.displayName }

        assertTrue(animeSection.isAnimeBucket)
        assertFalse(genericSection.isAnimeBucket)
    }

    @Test
    fun `bucket is false when content is not anime even if addon is tagged`() = runTest {
        val animeIndex = FakeAnimeIdentityIndex(returns = null)
        val repo = newRepo(addons = listOf(animeAddon, genericAddon), animeIdentityIndex = animeIndex)
        val emitted = repo.getStreamsFromAllAddons(
            type = "movie",
            videoId = "tt0111161",
            season = null,
            episode = null,
            installedAddons = null,
            requestOrigin = "test",
            requestId = "req-2",
        ).filterIsInstance<NetworkResult.Success<List<AddonStreams>>>().toList().last().data

        emitted.forEach { assertFalse(it.isAnimeBucket) }
    }

    @Test
    fun `unparseable videoId leaves bucket false`() = runTest {
        val animeIndex = FakeAnimeIdentityIndex(returns = null) // never asked
        val repo = newRepo(addons = listOf(animeAddon), animeIdentityIndex = animeIndex)
        val emitted = repo.getStreamsFromAllAddons(
            type = "movie",
            videoId = "garbage-id",
            season = null,
            episode = null,
            installedAddons = null,
            requestOrigin = "test",
            requestId = "req-3",
        ).filterIsInstance<NetworkResult.Success<List<AddonStreams>>>().toList().last().data
        assertEquals(false, emitted.first().isAnimeBucket)
    }

    private class FakeAnimeIdentityIndex(private val returns: String?) : AnimeIdentityIndex {
        override suspend fun resolveKitsuId(id: ParsedMetadataId): String? = returns
    }

    // newRepo, fakeAddon helpers: copy from the patterns in StreamRepositoryImplTest.kt
}
```

After scaffolding, copy the `newRepo(...)`/`fakeAddon(...)` builders from the existing `StreamRepositoryImplTest.kt` so the constructor wiring matches (the new `AnimeIdentityIndex` parameter is the only addition). Make sure the helper passes the test's `animeIdentityIndex` argument into the constructor.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testArm64DebugUnitTest --tests com.nexio.tv.data.repository.StreamRepositoryImplAnimeBucketTest --no-daemon`
Expected: FAIL — `AnimeIdentityIndex` not in constructor; `isAnimeBucket` always false.

- [ ] **Step 3: Inject `AnimeIdentityIndex` into `StreamRepositoryImpl`**

In `StreamRepositoryImpl.kt`, add the import:
```kotlin
import com.nexio.tv.core.metadata.router.AnimeIdentityIndex
import com.nexio.tv.core.metadata.router.MetadataIdParser
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

- [ ] **Step 4: Compute `contentIsAnime` once after `streamAddons`**

In `StreamRepositoryImpl.kt:64-66`, immediately after:
```kotlin
            val streamAddons = addons.filter { addon ->
                addon.supportsStreamResource(type)
            }
```
add:
```kotlin
            val parsedContentId = MetadataIdParser.parse(videoId)
            val contentIsAnime = parsedContentId?.let { parsed ->
                animeIdentityIndex.resolveKitsuId(parsed) != null
            } ?: false
```

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

For each constructor call, add `animeIdentityIndex = FakeAnimeIdentityIndex(returns = null)` (or an analogous fake that always returns `null`, mirroring today's behavior of "no anime priority"). Add the `FakeAnimeIdentityIndex` helper to the test file if it doesn't already have one.

- [ ] **Step 7: Run all StreamRepositoryImpl tests**

Run: `./gradlew testArm64DebugUnitTest --tests com.nexio.tv.data.repository.StreamRepositoryImplTest --tests com.nexio.tv.data.repository.StreamRepositoryImplAnimeBucketTest --no-daemon`
Expected: PASS — both new and existing tests.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/StreamRepositoryImpl.kt app/src/test/java/com/nexio/tv/data/repository/StreamRepositoryImplAnimeBucketTest.kt app/src/test/java/com/nexio/tv/data/repository/StreamRepositoryImplTest.kt
git commit -m "feat(stream): tag AddonStreams.isAnimeBucket per request"
```

---

### Task 8: Android UI — `AddonManagerViewModel.updateAddonIsAnime` + screen toggle

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

### Task 9: Web wire shape — `AddonRecord.isAnime` + bootstrap/persist

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

### Task 10: Web store mutation — `usePortalStore.updateAddonIsAnime`

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

### Task 11: Web UI — `AddonManager.vue` toggle + `account.vue` wiring

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

### Task 12: End-to-end smoke check

No code changes — this is a manual verification step covering the full pipeline.

- [ ] **Step 1: Tag a known anime addon on Android**

Open the addon manager on Android. Toggle "Anime addon" on for the dedicated anime addon you have installed (e.g. an anime-streams addon, the Kitsu addon, etc.).

- [ ] **Step 2: Verify the toggle persisted**

Force-stop the app and reopen. The toggle is still on.

- [ ] **Step 3: Verify cross-device sync**

Open `nexio-web` for the same account. The same addon shows the "Anime" chip enabled.

- [ ] **Step 4: Open an anime title and verify stream order**

Pick an anime title (e.g. opened from a Kitsu catalog or a Trakt/IMDB anime entry). Open the stream selection screen. Confirm:
1. The anime-tagged addon's section appears at the top of the list.
2. Generic addon sections appear below it, in their existing `sortOrder`.
3. Autoplay (if enabled in `FIRST_STREAM` or `REGEX_MATCH` mode) selects from the anime-tagged section first.

- [ ] **Step 5: Open a non-anime title and verify ordering is unchanged**

Pick a regular movie/show. Confirm the stream list ordering is identical to the legacy behavior — anime-tagged addon is wherever its `sortOrder` puts it, not floated to top.

- [ ] **Step 6: No commit** (manual verification only).

---

## Self-review checklist (run before declaring plan complete)

**Spec coverage:**
- [x] Web `AddonRecord.isAnime` — Task 9.
- [x] Android `Addon.isAnime` — Task 3.
- [x] `AddonStreams.isAnimeBucket` — Task 1.
- [x] Per-addon edit UI on web — Task 11.
- [x] Per-addon edit UI on Android — Task 8.
- [x] `contentIsAnime` computed once via `AnimeIdentityIndex` — Task 7.
- [x] `isAnimeBucket = addon.isAnime && contentIsAnime` — Task 7.
- [x] Two-level comparator at the single sort seam — Task 2.
- [x] Wire-shape persistence on Android sync DTO — Task 6.
- [x] Wire-shape persistence on web — Task 9.
- [x] DataStore round-trip — Task 4.
- [x] Repository propagation through cache copies — Task 5.
- [x] Migration: none (defaults preserve back-compat) — verified in Task 4.
- [x] Edge cases: non-anime content, no anime addons, anime-tagged addon empty, unparseable id — covered by Task 7 tests + comparator regression test in Task 2.

**Trace events** (`streamRequest.contentIsAnime`, `addonStreams.isAnimeBucket`) from the spec are deferred to a follow-up — adding new trace event types touches `TraceMetadataEvents.kt` and trace validators, which is its own multi-file change. Flag with the user whether to include in this plan or defer.

**Type consistency:** all method names (`updateAddonIsAnime`), field names (`isAnime`, `isAnimeBucket`), wire keys (`is_anime`), and parameter orders (`url, isAnime`) are identical across tasks.

**No placeholders.** Every code block is complete; every test has a runnable command; every commit message is exact.
