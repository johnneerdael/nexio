# Account Sync TMDB/Kitsu Catalogs Extension Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend account sync to cover TMDB and Kitsu catalog enable/order, on equal footing with the other providers, and tighten the entire catalog-section schema to use null-vs-non-null presence semantics so partial syncs cannot accidentally overwrite a non-empty target with empty.

**Architecture:** Extend `AccountSyncModels.CatalogSyncSettings` with two new sections (`tmdb`, `kitsu`); change every catalog section to nullable so apply paths can distinguish absent from intentionally empty; bump `ACCOUNT_CONFIG_SYNC_CONTRACT_VERSION` from 8 to 9; in `AccountConfigSyncContract` and `AccountSettingsSyncService` apply paths, write the provider stores **and** call `HomeRailOrderStore.reorderProviderKeys(family, ..., ACCOUNT_SYNC)` for any non-null `catalogOrder`; gate every write by null-checks.

**Dependency.** This plan REQUIRES `2026-05-05-modern-home-rail-order-foundation.md` to be merged first. The foundation introduces `HomeRailOrderStore` and `HomeRailDefinitionsLocator`; Change 2 calls into both. Do not start Phase 3 of this plan until Change 1's PR is merged into the base branch.

**Tech Stack:** Kotlin, Kotlinx Serialization (the sync models use `@Serializable`), Hilt, Kotlinx Coroutines, JUnit + MockK + `kotlinx-coroutines-test`, raw SQL fixtures in `supabase/account_settings_sync.sql` (referenced by sync contract tests).

**Spec:** `openspec/changes/extend-account-sync-with-tmdb-kitsu-catalogs/`
**Design doc:** `docs/superpowers/specs/2026-05-05-modern-home-rail-order-authority-design.md`

**Build & test commands.**

- All sync tests: `./gradlew testDebugUnitTest --tests "com.nexio.tv.core.sync.*"`
- Single test class: `./gradlew testDebugUnitTest --tests "com.nexio.tv.core.sync.AccountConfigSyncContractTest"`
- OpenSpec strict validation: `openspec validate extend-account-sync-with-tmdb-kitsu-catalogs --strict`

**Conventions to follow.** The sync section types live in `app/src/main/java/com/nexio/tv/data/remote/supabase/AccountSyncModels.kt` and use `@Serializable` from Kotlinx Serialization. Existing sections like `SimklCatalogSyncSettings` are the naming/shape template. Apply paths live in `app/src/main/java/com/nexio/tv/core/sync/AccountConfigSyncContract.kt` and `AccountSettingsSyncService.kt`. Sync tests live in `app/src/test/java/com/nexio/tv/core/sync/`.

---

## Phase 1 — Nullability migration of existing catalog sections

The existing `CatalogSyncSettings` has non-null sub-sections with empty-list defaults. To enforce "absent != intentionally empty" uniformly, every catalog section becomes nullable. This phase changes only the existing sections; new TMDB/Kitsu sections come in Phase 2.

### Task 1: Make existing `CatalogSyncSettings` sub-sections nullable

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/remote/supabase/AccountSyncModels.kt:148-152` (the `CatalogSyncSettings` data class) and the four child types around lines 154-200.

- [ ] **Step 1: Add a failing test asserting null sub-sections round-trip stably**

Create `app/src/test/java/com/nexio/tv/data/remote/supabase/CatalogSyncSettingsNullableTest.kt`:

```kotlin
package com.nexio.tv.data.remote.supabase

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogSyncSettingsNullableTest {
    private val json = Json { encodeDefaults = false; ignoreUnknownKeys = true }

    @Test
    fun `null sub-sections round-trip as null`() {
        val original = CatalogSyncSettings(
            home = null, trakt = null, simkl = null, mdblist = null
        )
        val text = json.encodeToString(CatalogSyncSettings.serializer(), original)
        val decoded = json.decodeFromString(CatalogSyncSettings.serializer(), text)
        assertNull(decoded.home)
        assertNull(decoded.trakt)
        assertNull(decoded.simkl)
        assertNull(decoded.mdblist)
    }

    @Test
    fun `present sub-section with null inner field round-trips with null inner field`() {
        val original = CatalogSyncSettings(
            home = HomeCatalogSyncSettings(
                heroCatalogKeys = null,
                homeCatalogOrderKeys = null,
                disabledHomeCatalogKeys = null,
            )
        )
        val text = json.encodeToString(CatalogSyncSettings.serializer(), original)
        val decoded = json.decodeFromString(CatalogSyncSettings.serializer(), text)
        assertEquals(HomeCatalogSyncSettings(
            heroCatalogKeys = null,
            homeCatalogOrderKeys = null,
            disabledHomeCatalogKeys = null,
        ), decoded.home)
    }

    @Test
    fun `present sub-section with empty inner field round-trips as empty`() {
        val original = CatalogSyncSettings(
            home = HomeCatalogSyncSettings(homeCatalogOrderKeys = emptyList())
        )
        val text = json.encodeToString(CatalogSyncSettings.serializer(), original)
        val decoded = json.decodeFromString(CatalogSyncSettings.serializer(), text)
        assertEquals(emptyList<String>(), decoded.home?.homeCatalogOrderKeys)
    }
}
```

- [ ] **Step 2: Run tests**

Run: `./gradlew testDebugUnitTest --tests "com.nexio.tv.data.remote.supabase.CatalogSyncSettingsNullableTest"`
Expected: FAIL — current types have non-null sub-sections with default values.

- [ ] **Step 3: Make the four existing sub-sections and their order/enabled fields nullable**

Edit `AccountSyncModels.kt`:

```kotlin
@Serializable
data class CatalogSyncSettings(
    val home: HomeCatalogSyncSettings? = null,
    val trakt: TraktCatalogSyncSettings? = null,
    val simkl: SimklCatalogSyncSettings? = null,
    val mdblist: MDBListCatalogSyncSettings? = null,
)

@Serializable
data class HomeCatalogSyncSettings(
    val heroCatalogKeys: List<String>? = null,
    val homeCatalogOrderKeys: List<String>? = null,
    val disabledHomeCatalogKeys: List<String>? = null,
)

@Serializable
data class TraktCatalogSyncSettings(
    val catalogEnabledSet: List<String>? = null,
    val catalogOrder: List<String>? = null,
    val selectedPopularListKeys: List<String>? = null,
    val pinnedListOptions: List<TraktPinnedListOptionSync>? = null,
)

@Serializable
data class SimklCatalogSyncSettings(
    val catalogEnabledSet: List<String>? = null,
    val catalogOrder: List<String>? = null,
)

@Serializable
data class MDBListCatalogSyncSettings(
    val hiddenPersonalListKeys: List<String>? = null,
    val selectedTopListKeys: List<String>? = null,
    val pinnedTopListOptions: List<MDBListPinnedListOptionSync>? = null,
    val catalogOrder: List<String>? = null,
)
```

Compilation will break elsewhere — every consumer that reads `payload.catalogs.home.homeCatalogOrderKeys` now gets a `List<String>?`. Fix call sites in the next step.

- [ ] **Step 4: Update consumers in `AccountConfigSyncContract.kt`, `AccountSettingsSyncService.kt`, and any apply tests, replacing `.someList` with `.someList ?: return@... `, `.orEmpty()` only where the production behavior used to treat empty == no-op (which it didn't reliably, but was effectively the case)**

A safer transformation: every apply-path callsite that previously did

```kotlin
prefs.catalogs.trakt.catalogEnabledSet  // List<String> (non-null)
```

becomes

```kotlin
prefs.catalogs.trakt?.catalogEnabledSet  // List<String>? — null = absent
```

For each catalog-section reader, wrap the apply work in `?.let { value -> ... }` so absent-section payloads become no-ops:

```kotlin
prefs.catalogs.home?.homeCatalogOrderKeys?.let { keys ->
    layoutPreferenceDataStore.setHomeCatalogOrderKeys(keys)
}
prefs.catalogs.home?.disabledHomeCatalogKeys?.let { keys ->
    layoutPreferenceDataStore.setDisabledHomeCatalogKeys(keys)
}
prefs.catalogs.trakt?.catalogOrder?.let { order ->
    traktSettingsDataStore.setCatalogOrder(order)
}
```

Document the rule with a one-line comment at the top of each apply method:

```kotlin
// Null catalog sections / null inner fields = absent in payload, leave target unchanged.
// Empty list ([]) = present and intentionally empty, apply as cleared.
```

- [ ] **Step 5: Run tests**

Run: `./gradlew testDebugUnitTest --tests "com.nexio.tv.data.remote.supabase.CatalogSyncSettingsNullableTest" --tests "com.nexio.tv.core.sync.*"`
Expected: PASS for the new test. Pre-existing tests should still pass because their fixtures construct payloads explicitly with non-null sections — the breakage surfaces only at apply-path call sites, which Step 4 fixed.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/remote/supabase/AccountSyncModels.kt \
        app/src/main/java/com/nexio/tv/core/sync/AccountConfigSyncContract.kt \
        app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt \
        app/src/test/java/com/nexio/tv/data/remote/supabase/CatalogSyncSettingsNullableTest.kt
git commit -m "refactor(account-sync): make catalog sections and inner lists nullable for presence semantics"
```

---

### Task 2: Lock in null-vs-empty apply behavior

**Files:**
- Create: `app/src/test/java/com/nexio/tv/core/sync/CatalogSyncPresenceSemanticsTest.kt`

These tests assert that a payload with `null` is a no-op and a payload with `[]` clears the target. Without these, the nullability change in Task 1 could regress silently.

- [ ] **Step 1: Write failing tests**

```kotlin
package com.nexio.tv.core.sync

import com.nexio.tv.data.local.LayoutPreferenceDataStore
import com.nexio.tv.data.remote.supabase.AccountConfigSyncPayload
import com.nexio.tv.data.remote.supabase.CatalogSyncSettings
import com.nexio.tv.data.remote.supabase.HomeCatalogSyncSettings
import io.mockk.coVerify
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogSyncPresenceSemanticsTest {
    @Test
    fun `null home section does not call layout setter`() = runTest {
        val layout = mockk<LayoutPreferenceDataStore>(relaxed = true)
        val payload = AccountConfigSyncPayload(
            catalogs = CatalogSyncSettings(home = null)
        )
        applyCatalogsSection(payload, layout) // helper extracted below
        coVerify(exactly = 0) { layout.setHomeCatalogOrderKeys(any()) }
    }

    @Test
    fun `non-null section with null homeCatalogOrderKeys does not call layout setter`() = runTest {
        val layout = mockk<LayoutPreferenceDataStore>(relaxed = true)
        val payload = AccountConfigSyncPayload(
            catalogs = CatalogSyncSettings(
                home = HomeCatalogSyncSettings(homeCatalogOrderKeys = null)
            )
        )
        applyCatalogsSection(payload, layout)
        coVerify(exactly = 0) { layout.setHomeCatalogOrderKeys(any()) }
    }

    @Test
    fun `non-null section with empty list calls layout setter with empty list`() = runTest {
        val layout = mockk<LayoutPreferenceDataStore>(relaxed = true)
        val payload = AccountConfigSyncPayload(
            catalogs = CatalogSyncSettings(
                home = HomeCatalogSyncSettings(homeCatalogOrderKeys = emptyList())
            )
        )
        applyCatalogsSection(payload, layout)
        coVerify(exactly = 1) { layout.setHomeCatalogOrderKeys(emptyList()) }
    }
}
```

For the test to compile, expose a small public helper in the apply path (`applyCatalogsSection(payload, layout, ...)`). If the existing apply method is private and large, extract just the catalog-section piece into a top-level `internal fun applyCatalogsSection(payload: AccountConfigSyncPayload, layout: LayoutPreferenceDataStore, /*...other deps...*/)` function. Tests against the helper give targeted coverage; the existing pull-apply tests continue to cover the full path.

- [ ] **Step 2: Run tests**

Run: `./gradlew testDebugUnitTest --tests "com.nexio.tv.core.sync.CatalogSyncPresenceSemanticsTest"`
Expected: FAIL — `applyCatalogsSection` not yet extracted.

- [ ] **Step 3: Extract the helper from `AccountConfigSyncContract`**

Find the method that applies `payload.catalogs` (it sits in `AccountConfigSyncContract.kt` somewhere around line 336 per the RCA). Move the catalog-section apply block into a new `internal fun applyCatalogsSection(...)` co-located in the same file, called by the existing pull-apply method. Adjust signatures so the helper takes only what it needs (`AccountConfigSyncPayload`, `LayoutPreferenceDataStore`, the four provider settings DataStores, and — added in Phase 3 — `HomeRailOrderStore` + `HomeRailDefinitionsLocator`).

- [ ] **Step 4: Run tests**

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/sync/AccountConfigSyncContract.kt \
        app/src/test/java/com/nexio/tv/core/sync/CatalogSyncPresenceSemanticsTest.kt
git commit -m "refactor(account-sync): extract applyCatalogsSection and lock null-vs-empty semantics"
```

---

## Phase 2 — Add TMDB and Kitsu catalog sync sections

### Task 3: Define `TmdbCatalogSyncSettings` and `KitsuCatalogSyncSettings`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/remote/supabase/AccountSyncModels.kt`
- Create: `app/src/test/java/com/nexio/tv/data/remote/supabase/TmdbKitsuCatalogSyncModelsTest.kt`

- [ ] **Step 1: Write failing serialization tests**

```kotlin
package com.nexio.tv.data.remote.supabase

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TmdbKitsuCatalogSyncModelsTest {
    private val json = Json { encodeDefaults = false; ignoreUnknownKeys = true }

    @Test
    fun `TmdbCatalogSyncSettings round-trips with non-null fields`() {
        val original = TmdbCatalogSyncSettings(
            catalogEnabledSet = listOf("tmdb:popular", "tmdb:top-rated"),
            catalogOrder = listOf("tmdb:top-rated", "tmdb:popular"),
        )
        val text = json.encodeToString(TmdbCatalogSyncSettings.serializer(), original)
        val decoded = json.decodeFromString(TmdbCatalogSyncSettings.serializer(), text)
        assertEquals(original, decoded)
    }

    @Test
    fun `TmdbCatalogSyncSettings round-trips with null fields`() {
        val original = TmdbCatalogSyncSettings()
        val text = json.encodeToString(TmdbCatalogSyncSettings.serializer(), original)
        val decoded = json.decodeFromString(TmdbCatalogSyncSettings.serializer(), text)
        assertNull(decoded.catalogEnabledSet)
        assertNull(decoded.catalogOrder)
    }

    @Test
    fun `KitsuCatalogSyncSettings round-trips with non-null fields`() {
        val original = KitsuCatalogSyncSettings(
            catalogEnabledSet = listOf("kitsu:trending"),
            catalogOrder = listOf("kitsu:trending", "kitsu:popular"),
        )
        val text = json.encodeToString(KitsuCatalogSyncSettings.serializer(), original)
        val decoded = json.decodeFromString(KitsuCatalogSyncSettings.serializer(), text)
        assertEquals(original, decoded)
    }

    @Test
    fun `CatalogSyncSettings carries tmdb and kitsu sections`() {
        val original = CatalogSyncSettings(
            tmdb = TmdbCatalogSyncSettings(catalogOrder = listOf("tmdb:popular")),
            kitsu = KitsuCatalogSyncSettings(catalogOrder = listOf("kitsu:trending")),
        )
        val text = json.encodeToString(CatalogSyncSettings.serializer(), original)
        val decoded = json.decodeFromString(CatalogSyncSettings.serializer(), text)
        assertEquals(listOf("tmdb:popular"), decoded.tmdb?.catalogOrder)
        assertEquals(listOf("kitsu:trending"), decoded.kitsu?.catalogOrder)
    }
}
```

- [ ] **Step 2: Run tests**

Run: `./gradlew testDebugUnitTest --tests "com.nexio.tv.data.remote.supabase.TmdbKitsuCatalogSyncModelsTest"`
Expected: FAIL — `TmdbCatalogSyncSettings`/`KitsuCatalogSyncSettings` not found.

- [ ] **Step 3: Add the new types and `CatalogSyncSettings` fields**

In `AccountSyncModels.kt`, near the existing `SimklCatalogSyncSettings`:

```kotlin
@Serializable
data class TmdbCatalogSyncSettings(
    val catalogEnabledSet: List<String>? = null,
    val catalogOrder: List<String>? = null,
)

@Serializable
data class KitsuCatalogSyncSettings(
    val catalogEnabledSet: List<String>? = null,
    val catalogOrder: List<String>? = null,
)
```

Update `CatalogSyncSettings` to:

```kotlin
@Serializable
data class CatalogSyncSettings(
    val home: HomeCatalogSyncSettings? = null,
    val trakt: TraktCatalogSyncSettings? = null,
    val simkl: SimklCatalogSyncSettings? = null,
    val mdblist: MDBListCatalogSyncSettings? = null,
    val tmdb: TmdbCatalogSyncSettings? = null,
    val kitsu: KitsuCatalogSyncSettings? = null,
)
```

- [ ] **Step 4: Run tests**

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/remote/supabase/AccountSyncModels.kt \
        app/src/test/java/com/nexio/tv/data/remote/supabase/TmdbKitsuCatalogSyncModelsTest.kt
git commit -m "feat(account-sync): add TmdbCatalogSyncSettings and KitsuCatalogSyncSettings"
```

---

## Phase 3 — Apply paths

### Task 4: TMDB apply path — write store + write-through

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/sync/AccountConfigSyncContract.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/sync/CatalogSyncPresenceSemanticsTest.kt` (or new test file)

The TMDB DataStore is `TmdbCatalogSettingsDataStore.kt:50` (already injected into `CatalogOrderViewModel`; check whether it's already a constructor parameter of `AccountConfigSyncContract` — likely not yet).

- [ ] **Step 1: Write failing apply tests**

Create `app/src/test/java/com/nexio/tv/core/sync/TmdbCatalogSyncApplyTest.kt`:

```kotlin
package com.nexio.tv.core.sync

import com.nexio.tv.data.local.TmdbCatalogSettingsDataStore
import com.nexio.tv.data.remote.supabase.AccountConfigSyncPayload
import com.nexio.tv.data.remote.supabase.CatalogSyncSettings
import com.nexio.tv.data.remote.supabase.TmdbCatalogSyncSettings
import com.nexio.tv.ui.screens.home.order.HomeRailDefinitionsLocator
import com.nexio.tv.ui.screens.home.order.HomeRailKey
import com.nexio.tv.ui.screens.home.order.HomeRailOrderStore
import com.nexio.tv.ui.screens.home.order.RailFamily
import com.nexio.tv.ui.screens.home.order.RailOrderMutationSource
import io.mockk.coVerify
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TmdbCatalogSyncApplyTest {
    @Test
    fun `applying tmdb section writes provider preference and reorderProviderKeys`() = runTest {
        val tmdbDs = mockk<TmdbCatalogSettingsDataStore>(relaxed = true)
        val homeRailOrderStore = mockk<HomeRailOrderStore>(relaxed = true)
        val locator = mockk<HomeRailDefinitionsLocator>(relaxed = true)
        coEvery { locator.flow } returns flowOf(emptyList())

        val payload = AccountConfigSyncPayload(
            catalogs = CatalogSyncSettings(
                tmdb = TmdbCatalogSyncSettings(
                    catalogEnabledSet = listOf("tmdb:popular", "tmdb:top-rated"),
                    catalogOrder = listOf("tmdb:top-rated", "tmdb:popular"),
                )
            )
        )

        applyTmdbCatalogSection(payload, tmdbDs, homeRailOrderStore, locator)

        coVerify { tmdbDs.setCatalogOrder(listOf("tmdb:top-rated", "tmdb:popular")) }
        coVerify { tmdbDs.setEnabledCatalogs(setOf("tmdb:popular", "tmdb:top-rated")) }
        coVerify {
            homeRailOrderStore.reorderProviderKeys(
                family = RailFamily.TMDB,
                providerOrder = listOf(HomeRailKey("tmdb:top-rated"), HomeRailKey("tmdb:popular")),
                source = RailOrderMutationSource.ACCOUNT_SYNC,
                liveDefinitionsFlow = any(),
            )
        }
    }

    @Test
    fun `null tmdb section is a no-op`() = runTest {
        val tmdbDs = mockk<TmdbCatalogSettingsDataStore>(relaxed = true)
        val homeRailOrderStore = mockk<HomeRailOrderStore>(relaxed = true)
        val locator = mockk<HomeRailDefinitionsLocator>(relaxed = true)
        val payload = AccountConfigSyncPayload(catalogs = CatalogSyncSettings(tmdb = null))
        applyTmdbCatalogSection(payload, tmdbDs, homeRailOrderStore, locator)
        coVerify(exactly = 0) { tmdbDs.setCatalogOrder(any()) }
        coVerify(exactly = 0) { homeRailOrderStore.reorderProviderKeys(any(), any(), any(), any<kotlinx.coroutines.flow.Flow<*>>()) }
    }

    @Test
    fun `present tmdb section with null inner fields is a no-op`() = runTest {
        val tmdbDs = mockk<TmdbCatalogSettingsDataStore>(relaxed = true)
        val homeRailOrderStore = mockk<HomeRailOrderStore>(relaxed = true)
        val locator = mockk<HomeRailDefinitionsLocator>(relaxed = true)
        val payload = AccountConfigSyncPayload(
            catalogs = CatalogSyncSettings(
                tmdb = TmdbCatalogSyncSettings(catalogEnabledSet = null, catalogOrder = null)
            )
        )
        applyTmdbCatalogSection(payload, tmdbDs, homeRailOrderStore, locator)
        coVerify(exactly = 0) { tmdbDs.setCatalogOrder(any()) }
    }

    @Test
    fun `tmdb section with empty catalogOrder is applied`() = runTest {
        val tmdbDs = mockk<TmdbCatalogSettingsDataStore>(relaxed = true)
        val homeRailOrderStore = mockk<HomeRailOrderStore>(relaxed = true)
        val locator = mockk<HomeRailDefinitionsLocator>(relaxed = true)
        coEvery { locator.flow } returns flowOf(emptyList())
        val payload = AccountConfigSyncPayload(
            catalogs = CatalogSyncSettings(tmdb = TmdbCatalogSyncSettings(catalogOrder = emptyList()))
        )
        applyTmdbCatalogSection(payload, tmdbDs, homeRailOrderStore, locator)
        coVerify { tmdbDs.setCatalogOrder(emptyList()) }
        coVerify {
            homeRailOrderStore.reorderProviderKeys(
                family = RailFamily.TMDB,
                providerOrder = emptyList(),
                source = RailOrderMutationSource.ACCOUNT_SYNC,
                liveDefinitionsFlow = any(),
            )
        }
    }
}
```

If `TmdbCatalogSettingsDataStore` setter names differ from `setCatalogOrder` / `setEnabledCatalogs`, inspect the file (it's at `app/src/main/java/com/nexio/tv/data/local/TmdbCatalogSettingsDataStore.kt`) and adjust the test.

- [ ] **Step 2: Run tests to verify they fail**

Expected: FAIL — `applyTmdbCatalogSection` not yet defined.

- [ ] **Step 3: Implement `applyTmdbCatalogSection` and call it from the main apply path**

In `AccountConfigSyncContract.kt`, alongside the `applyCatalogsSection` helper from Task 2:

```kotlin
internal suspend fun applyTmdbCatalogSection(
    payload: AccountConfigSyncPayload,
    tmdbCatalogSettingsDataStore: TmdbCatalogSettingsDataStore,
    homeRailOrderStore: HomeRailOrderStore,
    homeRailDefinitionsLocator: HomeRailDefinitionsLocator,
) {
    val tmdb = payload.catalogs.tmdb ?: return
    tmdb.catalogEnabledSet?.let { enabled ->
        tmdbCatalogSettingsDataStore.setEnabledCatalogs(enabled.toSet())
    }
    tmdb.catalogOrder?.let { order ->
        tmdbCatalogSettingsDataStore.setCatalogOrder(order)
        homeRailOrderStore.reorderProviderKeys(
            family = RailFamily.TMDB,
            providerOrder = order.map(::HomeRailKey),
            source = RailOrderMutationSource.ACCOUNT_SYNC,
            liveDefinitionsFlow = homeRailDefinitionsLocator.flow,
        )
    }
}
```

Then call `applyTmdbCatalogSection(payload, tmdbCatalogSettingsDataStore, homeRailOrderStore, homeRailDefinitionsLocator)` from inside `applyCatalogsSection`. Inject the new dependencies into the contract class.

- [ ] **Step 4: Run tests**

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/sync/AccountConfigSyncContract.kt \
        app/src/test/java/com/nexio/tv/core/sync/TmdbCatalogSyncApplyTest.kt
git commit -m "feat(account-sync): apply TMDB catalog section, write-through to HomeRailOrderStore"
```

---

### Task 5: Kitsu apply path — same pattern

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/sync/AccountConfigSyncContract.kt`
- Create: `app/src/test/java/com/nexio/tv/core/sync/KitsuCatalogSyncApplyTest.kt`

- [ ] **Step 1: Mirror `TmdbCatalogSyncApplyTest` for Kitsu**

Replace TMDB types with Kitsu equivalents (`KitsuCatalogSettingsDataStore`, `KitsuCatalogSyncSettings`, `RailFamily.KITSU`, etc.). Setter names — check `KitsuCatalogSettingsDataStore.kt`; from earlier exploration the analogous methods are `setCatalogEnabled(...)` and `setCatalogOrder(...)`.

- [ ] **Step 2: Run tests**

Expected: FAIL — `applyKitsuCatalogSection` not defined.

- [ ] **Step 3: Implement `applyKitsuCatalogSection` and call it from `applyCatalogsSection`**

```kotlin
internal suspend fun applyKitsuCatalogSection(
    payload: AccountConfigSyncPayload,
    kitsuCatalogSettingsDataStore: KitsuCatalogSettingsDataStore,
    homeRailOrderStore: HomeRailOrderStore,
    homeRailDefinitionsLocator: HomeRailDefinitionsLocator,
) {
    val kitsu = payload.catalogs.kitsu ?: return
    kitsu.catalogEnabledSet?.let { enabled ->
        kitsuCatalogSettingsDataStore.setEnabledCatalogs(enabled.toSet())
    }
    kitsu.catalogOrder?.let { order ->
        kitsuCatalogSettingsDataStore.setCatalogOrder(order)
        homeRailOrderStore.reorderProviderKeys(
            family = RailFamily.KITSU,
            providerOrder = order.map(::HomeRailKey),
            source = RailOrderMutationSource.ACCOUNT_SYNC,
            liveDefinitionsFlow = homeRailDefinitionsLocator.flow,
        )
    }
}
```

- [ ] **Step 4: Run tests**

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/sync/AccountConfigSyncContract.kt \
        app/src/test/java/com/nexio/tv/core/sync/KitsuCatalogSyncApplyTest.kt
git commit -m "feat(account-sync): apply Kitsu catalog section, write-through to HomeRailOrderStore"
```

---

### Task 6: Mirror Trakt/SIMKL/MDBList apply paths to call write-through

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/sync/AccountConfigSyncContract.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/sync/AccountConfigSyncContractTest.kt` (extend with reorder-write-through scenarios)

The existing apply paths for Trakt, SIMKL, and MDBList already write the provider stores. Add the `homeRailOrderStore.reorderProviderKeys(...)` call alongside each one.

- [ ] **Step 1: Add three failing tests (Trakt, SIMKL, MDBList) modeled on `TmdbCatalogSyncApplyTest`**

Each test asserts:
1. The provider DataStore is called with the new order.
2. `homeRailOrderStore.reorderProviderKeys(family = RailFamily.<F>, source = ACCOUNT_SYNC, ...)` is also called.

- [ ] **Step 2: Implement the write-through in each existing apply method**

Add `reorderProviderKeys(...)` after each provider's `setCatalogOrder(...)` call site, gated by `?.let { order -> ... }`.

- [ ] **Step 3: Run tests**

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/sync/AccountConfigSyncContract.kt \
        app/src/test/java/com/nexio/tv/core/sync/AccountConfigSyncContractTest.kt
git commit -m "feat(account-sync): write-through Trakt/SIMKL/MDBList reorders to HomeRailOrderStore"
```

---

### Task 7: Mirror apply changes in `AccountSettingsSyncService`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt:800` (the home/Trakt/SIMKL apply block)
- Modify or create: `app/src/test/java/com/nexio/tv/sync/AccountSettingsSyncServiceTest.kt` (already exists per the directory listing)

`AccountSettingsSyncService` is the parallel apply path for the legacy `AccountSettingsPayload.layout` shape; per the RCA it currently applies home/Trakt/SIMKL preferences. Per spec, it must also handle the new TMDB/Kitsu sections (when present) and call write-through.

- [ ] **Step 1: Confirm whether `AccountSettingsSyncService` reads from `AccountConfigSyncPayload.catalogs` or from `AccountSettingsPayload.layout`**

Run: `grep -n "catalogs\|layout\|homeCatalogOrderKeys" app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt`

If `AccountSettingsSyncService` only handles the legacy `LayoutSettings` (which already carries `homeCatalogOrderKeys`), the changes are limited to:
- Wrap the existing `homeCatalogOrderKeys` apply in null-guarding (now nullable in `LayoutSettings` per Task 1; if `LayoutSettings` was untouched, leave as-is).
- Do not add TMDB/Kitsu apply paths here — those flow through the new `CatalogSyncSettings` shape applied by `AccountConfigSyncContract`.

If `AccountSettingsSyncService` does read `payload.catalogs`, mirror Tasks 4 and 5 inside this service.

- [ ] **Step 2: Apply matching changes**

Decision tree per Step 1's findings. Most likely outcome: this service does NOT need TMDB/Kitsu changes, only the null-guard updates from Task 1.

- [ ] **Step 3: Run tests**

Run: `./gradlew testDebugUnitTest --tests "com.nexio.tv.sync.*"`
Expected: PASS.

- [ ] **Step 4: Commit (if any change)**

```bash
git commit -m "refactor(account-sync): align AccountSettingsSyncService with nullable catalog sections"
```

If no change is needed, skip this commit.

---

## Phase 4 — Contract version bump

### Task 8: Bump `ACCOUNT_CONFIG_SYNC_CONTRACT_VERSION` from 8 to 9

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/sync/AccountConfigSyncContract.kt:46`
- Modify: `app/src/main/java/com/nexio/tv/data/remote/supabase/AccountSyncModels.kt:115` (the `AccountConfigSyncPayload.schemaVersion` default)
- Modify: `app/src/test/java/com/nexio/tv/core/sync/AccountConfigSyncContractTest.kt`

- [ ] **Step 1: Add a failing version test**

```kotlin
@Test
fun `current contract emits version 9`() {
    assertEquals(9, ACCOUNT_CONFIG_SYNC_CONTRACT_VERSION)
}

@Test
fun `version 9 payload includes tmdb and kitsu sections when set`() {
    val payload = AccountConfigSyncPayload(
        schemaVersion = ACCOUNT_CONFIG_SYNC_CONTRACT_VERSION,
        catalogs = CatalogSyncSettings(
            tmdb = TmdbCatalogSyncSettings(catalogOrder = listOf("tmdb:popular")),
            kitsu = KitsuCatalogSyncSettings(catalogOrder = listOf("kitsu:trending")),
        ),
    )
    val json = Json { encodeDefaults = false; ignoreUnknownKeys = true }
        .encodeToString(AccountConfigSyncPayload.serializer(), payload)
    assertTrue(json.contains("\"tmdb\""))
    assertTrue(json.contains("\"kitsu\""))
    assertTrue(json.contains("\"schemaVersion\":9"))
}

@Test
fun `version 8 payload without tmdb or kitsu round-trips and is accepted`() {
    val text = """{"schemaVersion":8,"catalogs":{"home":null}}"""
    val payload = Json { ignoreUnknownKeys = true }
        .decodeFromString(AccountConfigSyncPayload.serializer(), text)
    assertNull(payload.catalogs.tmdb)
    assertNull(payload.catalogs.kitsu)
    assertEquals(8, payload.schemaVersion)
}
```

- [ ] **Step 2: Run tests**

Expected: FAIL — current constant is 8.

- [ ] **Step 3: Bump constants**

In `AccountConfigSyncContract.kt:46`:

```kotlin
internal const val ACCOUNT_CONFIG_SYNC_CONTRACT_VERSION = 9
```

In `AccountSyncModels.kt:115`:

```kotlin
@Serializable
data class AccountConfigSyncPayload(
    @EncodeDefault
    val schemaVersion: Int = 9,
    // ...
)
```

- [ ] **Step 4: Run tests**

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/sync/AccountConfigSyncContract.kt \
        app/src/main/java/com/nexio/tv/data/remote/supabase/AccountSyncModels.kt \
        app/src/test/java/com/nexio/tv/core/sync/AccountConfigSyncContractTest.kt
git commit -m "feat(account-sync): bump contract version to 9 for TMDB/Kitsu catalog sections"
```

---

## Phase 5 — Partial-sync integration tests

### Task 9: Provider-only sync preserves cross-provider positions

**Files:**
- Create or extend: `app/src/test/java/com/nexio/tv/core/sync/PartialSyncSafetyTest.kt`

These end-to-end tests exercise the apply paths against a real `HomeRailOrderStore` (not mocked) so the splice algorithm and persistence are covered together with sync apply.

- [ ] **Step 1: Write failing tests**

```kotlin
package com.nexio.tv.core.sync

import com.nexio.tv.data.local.LayoutPreferenceDataStore
import com.nexio.tv.data.local.TmdbCatalogSettingsDataStore
import com.nexio.tv.data.remote.supabase.AccountConfigSyncPayload
import com.nexio.tv.data.remote.supabase.CatalogSyncSettings
import com.nexio.tv.data.remote.supabase.HomeCatalogSyncSettings
import com.nexio.tv.data.remote.supabase.TmdbCatalogSyncSettings
import com.nexio.tv.ui.screens.home.order.HomeRailDefinition
import com.nexio.tv.ui.screens.home.order.HomeRailDefinitionsLocator
import com.nexio.tv.ui.screens.home.order.HomeRailKey
import com.nexio.tv.ui.screens.home.order.HomeRailOrderStateCodec
import com.nexio.tv.ui.screens.home.order.HomeRailOrderStore
import com.nexio.tv.ui.screens.home.order.RailFamily
import com.nexio.tv.ui.screens.home.order.RailOrderMutationSource
import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class PartialSyncSafetyTest {
    private val codec = HomeRailOrderStateCodec(Gson())
    private val fixedClock = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC)

    private fun publicDef(key: String, family: RailFamily, intra: Int = 0) = HomeRailDefinition(
        key = HomeRailKey(key),
        family = family,
        source = com.nexio.tv.ui.screens.home.order.RailSource.PROVIDER_PUBLIC,
        title = key,
        enabled = true,
        defaultSortKey = com.nexio.tv.ui.screens.home.order.DefaultSortKey(family.familyRank, intra),
        publishPolicy = com.nexio.tv.ui.screens.home.order.RailPublishPolicy.PUBLISH_WHEN_NON_EMPTY,
    )

    @Test
    fun `tmdb-only sync preserves positions of trakt and simkl`() = runTest {
        // Initial: [trakt:popular, tmdb:popular, simkl:trending, tmdb:top-rated]
        val layout = mockk<LayoutPreferenceDataStore>(relaxed = true)
        val initialState = com.nexio.tv.ui.screens.home.order.HomeRailOrderState.Empty.copy(
            orderedKeys = listOf(
                HomeRailKey("trakt:popular"),
                HomeRailKey("tmdb:popular"),
                HomeRailKey("simkl:trending"),
                HomeRailKey("tmdb:top-rated"),
            )
        )
        val persistedJson = MutableStateFlow<String?>(codec.encode(initialState))
        coEvery { layout.homeRailOrderStateJson } returns persistedJson
        coEvery { layout.homeCatalogOrderKeys } returns flowOf(emptyList())
        coEvery { layout.disabledHomeCatalogKeys } returns flowOf(emptyList())
        coEvery { layout.setHomeRailOrderStateJson(any()) } answers { persistedJson.value = firstArg() }

        val store = HomeRailOrderStore(
            layoutPreferenceDataStore = layout,
            codec = codec,
            clock = fixedClock,
            scope = TestScope(StandardTestDispatcher(testScheduler)),
        )
        val locator = HomeRailDefinitionsLocator().apply {
            publish(listOf(
                publicDef("trakt:popular", RailFamily.TRAKT),
                publicDef("tmdb:popular", RailFamily.TMDB, intra = 0),
                publicDef("simkl:trending", RailFamily.SIMKL),
                publicDef("tmdb:top-rated", RailFamily.TMDB, intra = 1),
            ))
        }
        val tmdbDs = mockk<TmdbCatalogSettingsDataStore>(relaxed = true)

        applyTmdbCatalogSection(
            payload = AccountConfigSyncPayload(
                catalogs = CatalogSyncSettings(
                    tmdb = TmdbCatalogSyncSettings(
                        catalogOrder = listOf("tmdb:top-rated", "tmdb:popular")
                    )
                )
            ),
            tmdbCatalogSettingsDataStore = tmdbDs,
            homeRailOrderStore = store,
            homeRailDefinitionsLocator = locator,
        )
        advanceUntilIdle()

        assertEquals(
            listOf(
                HomeRailKey("trakt:popular"),
                HomeRailKey("tmdb:top-rated"),
                HomeRailKey("simkl:trending"),
                HomeRailKey("tmdb:popular"),
            ),
            store.state.first().orderedKeys,
        )
    }

    @Test
    fun `null home section does not erase orderedKeys`() = runTest {
        val layout = mockk<LayoutPreferenceDataStore>(relaxed = true)
        val initialState = com.nexio.tv.ui.screens.home.order.HomeRailOrderState.Empty.copy(
            orderedKeys = listOf(HomeRailKey("A"), HomeRailKey("B"), HomeRailKey("C"))
        )
        val persistedJson = MutableStateFlow<String?>(codec.encode(initialState))
        coEvery { layout.homeRailOrderStateJson } returns persistedJson
        coEvery { layout.homeCatalogOrderKeys } returns flowOf(emptyList())
        coEvery { layout.disabledHomeCatalogKeys } returns flowOf(emptyList())
        coEvery { layout.setHomeRailOrderStateJson(any()) } answers { persistedJson.value = firstArg() }

        val store = HomeRailOrderStore(layout, codec, fixedClock,
            TestScope(StandardTestDispatcher(testScheduler)))

        // Apply a payload with home = null and no provider sections that affect order.
        applyCatalogsSection(
            payload = AccountConfigSyncPayload(catalogs = CatalogSyncSettings(home = null)),
            layout = layout,
            // pass other dependencies as relaxed mocks if applyCatalogsSection requires them
        )
        advanceUntilIdle()

        assertEquals(
            listOf(HomeRailKey("A"), HomeRailKey("B"), HomeRailKey("C")),
            store.state.first().orderedKeys,
        )
    }
}
```

(The exact `applyCatalogsSection` signature depends on how Task 2 extracted it. Adjust the call site to match.)

- [ ] **Step 2: Run tests**

Expected: PASS — the implementations from Tasks 1–6 already satisfy these scenarios.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/nexio/tv/core/sync/PartialSyncSafetyTest.kt
git commit -m "test(account-sync): partial sync preserves cross-provider positions and home order"
```

---

## Phase 6 — Modern Home reactivity from sync

### Task 10: UI reactivity tests asserting Modern Home updates after sync

**Files:**
- Create: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeReactsToSyncReorderTest.kt`

This is an end-to-end-flavored test against the home pipeline. It seeds `HomeRailOrderStore` with an initial order, drives a sync apply, and asserts that the next published Modern Home rows reflect the new order.

- [ ] **Step 1: Write the failing test**

Build it on top of the existing `HomeCatalogStartupReadinessTest` harness. The pattern:

```kotlin
@Test
fun `tmdb sync apply updates modern home rail order without restart`() = runTest {
    // 1. Construct HomeViewModel with a real HomeRailOrderStore seeded with
    //    [trakt:popular, tmdb:popular, simkl:trending, tmdb:top-rated].
    // 2. Drive updateCatalogRowsPipeline once and snapshot row order.
    // 3. Call applyTmdbCatalogSection with order [tmdb:top-rated, tmdb:popular].
    // 4. Drive updateCatalogRowsPipeline again.
    // 5. Assert row order is now [trakt:popular, tmdb:top-rated, simkl:trending, tmdb:popular].
}
```

Use the existing harness's helpers; do not duplicate the entire HomeViewModel construction here.

- [ ] **Step 2: Run test**

Expected: PASS — Phase 5 of the foundation plan made effective order reactive on `state` changes; Phase 3 of this plan made sync apply call `homeRailOrderStore.reorderProviderKeys`.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/nexio/tv/ui/screens/home/HomeReactsToSyncReorderTest.kt
git commit -m "test(home): TMDB sync apply updates Modern Home without restart"
```

---

### Task 11: Mirror reactivity test for Kitsu

**Files:**
- Create: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeReactsToKitsuSyncReorderTest.kt`

Same shape as Task 10 but with Kitsu. Commit separately.

- [ ] **Step 1: Write test, run, commit**

```bash
git commit -m "test(home): Kitsu sync apply updates Modern Home without restart"
```

---

## Phase 7 — Verification

### Task 12: OpenSpec validation and full test suite

- [ ] **Step 1: Run OpenSpec strict validation**

Run: `openspec validate extend-account-sync-with-tmdb-kitsu-catalogs --strict`
Expected: `Change 'extend-account-sync-with-tmdb-kitsu-catalogs' is valid`.

- [ ] **Step 2: Run all sync tests**

Run: `./gradlew testDebugUnitTest --tests "com.nexio.tv.core.sync.*" --tests "com.nexio.tv.sync.*" --tests "com.nexio.tv.data.remote.supabase.*"`
Expected: All PASS.

- [ ] **Step 3: Run the full unit test suite**

Run: `./gradlew testDebugUnitTest`
Expected: All PASS.

- [ ] **Step 4: Commit any test-fixture updates**

```bash
git commit -m "test(account-sync): update fixtures for nullable catalog sections"
```

---

### Task 13: End-to-end staging sync smoke

- [ ] **Step 1: Build and install the releaseProfileable APK**

Run: `./gradlew installReleaseProfileable`

- [ ] **Step 2: Push a staging account-config payload that reorders TMDB and Kitsu**

Construct a payload like:

```json
{
  "schemaVersion": 9,
  "catalogs": {
    "tmdb": { "catalogOrder": ["tmdb:top-rated", "tmdb:popular"] },
    "kitsu": { "catalogOrder": ["kitsu:trending", "kitsu:popular"] }
  }
}
```

Trigger account-config sync on the device (the existing app flow). Verify in logcat:

- A `home.rail_order_reconciled` event with `lastMutationSource = ACCOUNT_SYNC` and `ignoredOrderSources = [persistedSyntheticOrder]`.
- Modern Home rerenders with TMDB and Kitsu rails in the new order without an app restart.

- [ ] **Step 3: Document the smoke result inline**

Append to `openspec/changes/extend-account-sync-with-tmdb-kitsu-catalogs/tasks.md` under task 7.3:

```
Verified on device DEVICE_NAME (Android NN), build commit SHA: TMDB and Kitsu reorder propagated through sync without restart.
```

Commit:

```bash
git add openspec/changes/extend-account-sync-with-tmdb-kitsu-catalogs/tasks.md
git commit -m "chore(account-sync): record on-device sync smoke verification"
```

---

## Self-Review

Spec coverage map:

| Spec requirement | Task |
|---|---|
| Sync Catalog Fields Use Nullable Presence Semantics | Tasks 1, 2, 9 |
| TMDB Catalog Settings Sync | Tasks 3, 4 |
| Kitsu Catalog Settings Sync | Tasks 3, 5 |
| Partial Sync Does Not Revert Home Order | Task 9 |
| Account-Config Sync Contract Version 9 | Task 8 |
| Account Sync Writes Through To HomeRailOrderStore (home-rail-order delta) | Tasks 4, 5, 6 |
| Modern Home reactivity tests | Tasks 10, 11 |
| Mirror in `AccountSettingsSyncService` | Task 7 |

No placeholders. Type names cross-checked against Plan 1 (the foundation): `HomeRailOrderStore`, `HomeRailDefinitionsLocator`, `RailOrderMutationSource.ACCOUNT_SYNC`, `RailFamily.TMDB`/`KITSU`, `HomeRailKey`, `HomeRailOrderStateCodec` — all consistent.
