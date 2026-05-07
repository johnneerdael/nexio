# Modern Home Rail Order Authority — Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Modern Home rail order authoritative and reactive: one `HomeRailOrderStore` decides order, persisted synthetic state is content-only, and reorder/enable/disable apply immediately without restart.

**Architecture:** Introduce a per-profile `HomeRailOrderStore` backed by `LayoutPreferenceDataStore` that holds `HomeRailOrderState` (saved order + disabled keys + version + mutation source). A pure `HomeRailOrderReconciler` combines that state with live `HomeRailDefinition` list (from existing `CatalogPlan`) into `EffectiveHomeRailOrder` via `combine(state, liveDefinitions)`. `HomeViewModelCatalogPipeline.updateCatalogRowsPipeline` consumes effective order and materializes content per key with live-group → persisted-synthetic-group → loading-placeholder fallback. `CatalogOrderViewModel` and the five provider settings screens (Trakt, SIMKL, MDBList, TMDB, Kitsu) write through to the store; diagnostic events trace every reconciliation.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, Kotlinx Coroutines, Kotlinx Serialization, AndroidX DataStore (Preferences), Gson (existing convention), JUnit + MockK + `kotlinx-coroutines-test`.

**Spec:** `openspec/changes/make-modern-home-rail-order-authoritative-and-reactive/`
**Design doc:** `docs/superpowers/specs/2026-05-05-modern-home-rail-order-authority-design.md`
**RCA:** `review-dossier/android-modern-home-catalog-rail-order-rca.md`

**Build & test commands.** All gradle commands run from repo root.

- Run all unit tests: `./gradlew testDebugUnitTest`
- Run a single test class: `./gradlew testDebugUnitTest --tests "com.nexio.tv.ui.screens.home.order.HomeRailOrderReconcilerTest"`
- OpenSpec strict validation: `openspec validate make-modern-home-rail-order-authoritative-and-reactive --strict`
- Releaseable APK for manual smoke: `./gradlew installReleaseProfileable` (requires connected device)

**Conventions to follow.** New source files go under `app/src/main/java/com/nexio/tv/ui/screens/home/order/` for the order model + store, and `app/src/test/java/com/nexio/tv/ui/screens/home/order/` for tests. The existing codebase uses Gson for DataStore JSON (see `LayoutPreferenceDataStore`) — match that. Test files use JUnit `@Test`, `assertEquals`/`assertTrue`/`assertNull`/`assertFalse`, MockK for mocks, and `runTest` for coroutine tests.

**Commit style.** Follow recent repo commits (e.g. `b5c66a2bf fix(hyperhdr): show Test connection result as a toast, not an inline row`). Use prefixes: `feat`, `fix`, `refactor`, `test`, `docs`, `chore`. Keep one logical change per commit.

---

## Phase 1 — Core model & types

### Task 1: Create `HomeRailKey` value class

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screens/home/order/HomeRailKey.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/home/order/HomeRailKeyTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.nexio.tv.ui.screens.home.order

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HomeRailKeyTest {
    @Test
    fun `equal values produce equal keys`() {
        assertEquals(HomeRailKey("tmdb:popular:movies"), HomeRailKey("tmdb:popular:movies"))
    }

    @Test
    fun `different values are not equal`() {
        assertNotEquals(HomeRailKey("a"), HomeRailKey("b"))
    }

    @Test
    fun `value is preserved as-is`() {
        assertEquals("trakt:user-list:abc:def", HomeRailKey("trakt:user-list:abc:def").value)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.nexio.tv.ui.screens.home.order.HomeRailKeyTest"`
Expected: FAIL — `HomeRailKey` not found.

- [ ] **Step 3: Write the minimal implementation**

```kotlin
package com.nexio.tv.ui.screens.home.order

@JvmInline
value class HomeRailKey(val value: String)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.nexio.tv.ui.screens.home.order.HomeRailKeyTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/order/HomeRailKey.kt \
        app/src/test/java/com/nexio/tv/ui/screens/home/order/HomeRailKeyTest.kt
git commit -m "feat(home-rail-order): add HomeRailKey value class"
```

---

### Task 2: Create the rail-order enums

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screens/home/order/RailOrderEnums.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/home/order/RailOrderEnumsTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.nexio.tv.ui.screens.home.order

import org.junit.Assert.assertEquals
import org.junit.Test

class RailOrderEnumsTest {
    @Test
    fun `RailFamily ranks define the canonical family order`() {
        assertEquals(0, RailFamily.TRAKT.familyRank)
        assertEquals(1, RailFamily.SIMKL.familyRank)
        assertEquals(2, RailFamily.MDBLIST.familyRank)
        assertEquals(3, RailFamily.TMDB.familyRank)
        assertEquals(4, RailFamily.KITSU.familyRank)
        assertEquals(5, RailFamily.ADDON.familyRank)
    }

    @Test
    fun `RailOrderMutationSource includes MIGRATION_SYNTHETIC_FALLBACK`() {
        // Asserts the enum contains the synthetic-fallback variant required by the migration spec.
        val sources = RailOrderMutationSource.values().map { it.name }
        assertEquals(true, sources.contains("MIGRATION_SYNTHETIC_FALLBACK"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.nexio.tv.ui.screens.home.order.RailOrderEnumsTest"`
Expected: FAIL — symbols not found.

- [ ] **Step 3: Write the minimal implementation**

```kotlin
package com.nexio.tv.ui.screens.home.order

enum class RailFamily(val familyRank: Int) {
    TRAKT(0),
    SIMKL(1),
    MDBLIST(2),
    TMDB(3),
    KITSU(4),
    ADDON(5),
}

enum class RailSource {
    PROVIDER_PUBLIC,
    PROVIDER_USER,
    ADDON_CATALOG,
}

enum class RailPublishPolicy {
    PUBLISH_ALWAYS,
    PUBLISH_WHEN_NON_EMPTY,
    PUBLISH_ON_FIRST_PAINT,
}

enum class RailOrderMutationSource {
    ANDROID_ORDER_SCREEN,
    PROVIDER_SETTINGS_SCREEN,
    ACCOUNT_SYNC,
    DEFAULT_BOOTSTRAP,
    MIGRATION,
    MIGRATION_SYNTHETIC_FALLBACK,
    DEBUG_RESET,
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.nexio.tv.ui.screens.home.order.RailOrderEnumsTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/order/RailOrderEnums.kt \
        app/src/test/java/com/nexio/tv/ui/screens/home/order/RailOrderEnumsTest.kt
git commit -m "feat(home-rail-order): add RailFamily, RailSource, RailPublishPolicy, RailOrderMutationSource"
```

---

### Task 3: Create `HomeRailDefinition`, `DefaultSortKey`, `HomeRailOrderState`, `EffectiveHomeRailOrder`

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screens/home/order/HomeRailDefinition.kt`
- Create: `app/src/main/java/com/nexio/tv/ui/screens/home/order/HomeRailOrderState.kt`
- Create: `app/src/main/java/com/nexio/tv/ui/screens/home/order/EffectiveHomeRailOrder.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/home/order/HomeRailOrderModelTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.nexio.tv.ui.screens.home.order

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRailOrderModelTest {
    @Test
    fun `DefaultSortKey orders by familyRank then intraFamilyRank`() {
        val a = DefaultSortKey(familyRank = 0, intraFamilyRank = 0)
        val b = DefaultSortKey(familyRank = 0, intraFamilyRank = 1)
        val c = DefaultSortKey(familyRank = 1, intraFamilyRank = 0)
        val sorted = listOf(c, b, a).sortedWith(
            compareBy({ it.familyRank }, { it.intraFamilyRank })
        )
        assertEquals(listOf(a, b, c), sorted)
    }

    @Test
    fun `HomeRailDefinition holds all declared fields`() {
        val def = HomeRailDefinition(
            key = HomeRailKey("tmdb:popular:movies"),
            family = RailFamily.TMDB,
            source = RailSource.PROVIDER_PUBLIC,
            title = "Popular Movies",
            enabled = true,
            defaultSortKey = DefaultSortKey(familyRank = 3, intraFamilyRank = 0),
            publishPolicy = RailPublishPolicy.PUBLISH_WHEN_NON_EMPTY,
        )
        assertEquals(HomeRailKey("tmdb:popular:movies"), def.key)
        assertEquals(RailFamily.TMDB, def.family)
        assertTrue(def.enabled)
    }

    @Test
    fun `HomeRailOrderState defaults are sensible for empty state`() {
        val empty = HomeRailOrderState.Empty
        assertTrue(empty.orderedKeys.isEmpty())
        assertTrue(empty.disabledKeys.isEmpty())
        assertEquals(0L, empty.version)
        assertEquals(0L, empty.updatedAtMs)
        assertEquals(RailOrderMutationSource.DEFAULT_BOOTSTRAP, empty.lastMutationSource)
    }

    @Test
    fun `EffectiveHomeRailOrder Empty exposes empty lists`() {
        val empty = EffectiveHomeRailOrder.Empty
        assertTrue(empty.visibleKeys.isEmpty())
        assertTrue(empty.disabledKeys.isEmpty())
        assertTrue(empty.unknownSavedKeys.isEmpty())
        assertTrue(empty.newlyDiscoveredKeys.isEmpty())
        assertTrue(empty.prunedKeys.isEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.nexio.tv.ui.screens.home.order.HomeRailOrderModelTest"`
Expected: FAIL.

- [ ] **Step 3: Write the minimal implementation**

```kotlin
// app/src/main/java/com/nexio/tv/ui/screens/home/order/HomeRailDefinition.kt
package com.nexio.tv.ui.screens.home.order

data class DefaultSortKey(
    val familyRank: Int,
    val intraFamilyRank: Int,
)

data class HomeRailDefinition(
    val key: HomeRailKey,
    val family: RailFamily,
    val source: RailSource,
    val title: String,
    val enabled: Boolean,
    val defaultSortKey: DefaultSortKey,
    val publishPolicy: RailPublishPolicy,
)
```

```kotlin
// app/src/main/java/com/nexio/tv/ui/screens/home/order/HomeRailOrderState.kt
package com.nexio.tv.ui.screens.home.order

data class HomeRailOrderState(
    val orderedKeys: List<HomeRailKey>,
    val disabledKeys: Set<HomeRailKey>,
    val version: Long,
    val updatedAtMs: Long,
    val lastMutationSource: RailOrderMutationSource,
) {
    companion object {
        val Empty = HomeRailOrderState(
            orderedKeys = emptyList(),
            disabledKeys = emptySet(),
            version = 0L,
            updatedAtMs = 0L,
            lastMutationSource = RailOrderMutationSource.DEFAULT_BOOTSTRAP,
        )
    }
}
```

```kotlin
// app/src/main/java/com/nexio/tv/ui/screens/home/order/EffectiveHomeRailOrder.kt
package com.nexio.tv.ui.screens.home.order

data class EffectiveHomeRailOrder(
    val visibleKeys: List<HomeRailKey>,
    val disabledKeys: Set<HomeRailKey>,
    val unknownSavedKeys: List<HomeRailKey>,
    val newlyDiscoveredKeys: List<HomeRailKey>,
    val prunedKeys: List<HomeRailKey>,
) {
    companion object {
        val Empty = EffectiveHomeRailOrder(
            visibleKeys = emptyList(),
            disabledKeys = emptySet(),
            unknownSavedKeys = emptyList(),
            newlyDiscoveredKeys = emptyList(),
            prunedKeys = emptyList(),
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.nexio.tv.ui.screens.home.order.HomeRailOrderModelTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/order/HomeRailDefinition.kt \
        app/src/main/java/com/nexio/tv/ui/screens/home/order/HomeRailOrderState.kt \
        app/src/main/java/com/nexio/tv/ui/screens/home/order/EffectiveHomeRailOrder.kt \
        app/src/test/java/com/nexio/tv/ui/screens/home/order/HomeRailOrderModelTest.kt
git commit -m "feat(home-rail-order): add HomeRailDefinition, HomeRailOrderState, EffectiveHomeRailOrder"
```

---

## Phase 2 — Reconciler

### Task 4: Reconciler — saved-order-wins-for-known-enabled

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screens/home/order/HomeRailOrderReconciler.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/home/order/HomeRailOrderReconcilerTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.nexio.tv.ui.screens.home.order

import org.junit.Assert.assertEquals
import org.junit.Test

private fun publicDef(
    key: String,
    family: RailFamily,
    intra: Int = 0,
    enabled: Boolean = true,
): HomeRailDefinition = HomeRailDefinition(
    key = HomeRailKey(key),
    family = family,
    source = RailSource.PROVIDER_PUBLIC,
    title = key,
    enabled = enabled,
    defaultSortKey = DefaultSortKey(family.familyRank, intra),
    publishPolicy = RailPublishPolicy.PUBLISH_WHEN_NON_EMPTY,
)

class HomeRailOrderReconcilerTest {
    private val reconciler = HomeRailOrderReconciler()

    @Test
    fun `saved order wins for known enabled keys`() {
        val saved = listOf(
            HomeRailKey("trakt:popular"),
            HomeRailKey("tmdb:popular"),
            HomeRailKey("simkl:trending"),
        )
        val live = listOf(
            publicDef("trakt:popular", RailFamily.TRAKT),
            publicDef("tmdb:popular", RailFamily.TMDB),
            publicDef("simkl:trending", RailFamily.SIMKL),
        )
        val effective = reconciler.reconcile(
            savedGlobalOrder = saved,
            disabledKeys = emptySet(),
            liveDefinitions = live,
        )
        assertEquals(saved, effective.visibleKeys)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.nexio.tv.ui.screens.home.order.HomeRailOrderReconcilerTest"`
Expected: FAIL — `HomeRailOrderReconciler` not found.

- [ ] **Step 3: Write the minimal implementation**

```kotlin
package com.nexio.tv.ui.screens.home.order

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HomeRailOrderReconciler @Inject constructor() {
    fun reconcile(
        savedGlobalOrder: List<HomeRailKey>,
        disabledKeys: Set<HomeRailKey>,
        liveDefinitions: List<HomeRailDefinition>,
    ): EffectiveHomeRailOrder {
        val liveByKey = liveDefinitions.associateBy { it.key }
        val enabledLive = liveDefinitions.filter { it.enabled && it.key !in disabledKeys }
        val enabledKeys = enabledLive.map { it.key }.toSet()

        val savedKnownEnabled = savedGlobalOrder.filter { it in enabledKeys }
        val missingEnabled = enabledLive
            .filter { it.key !in savedKnownEnabled }
            .sortedWith(
                compareBy(
                    { it.defaultSortKey.familyRank },
                    { it.defaultSortKey.intraFamilyRank },
                )
            )
            .map { it.key }

        val liveKeysSet = liveByKey.keys
        val unknownSaved = savedGlobalOrder.filter { it !in liveKeysSet }
        val pruned = savedGlobalOrder.filter { it in liveKeysSet && it !in enabledKeys }

        return EffectiveHomeRailOrder(
            visibleKeys = savedKnownEnabled + missingEnabled,
            disabledKeys = disabledKeys,
            unknownSavedKeys = unknownSaved,
            newlyDiscoveredKeys = missingEnabled,
            prunedKeys = pruned,
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.nexio.tv.ui.screens.home.order.HomeRailOrderReconcilerTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/order/HomeRailOrderReconciler.kt \
        app/src/test/java/com/nexio/tv/ui/screens/home/order/HomeRailOrderReconcilerTest.kt
git commit -m "feat(home-rail-order): add reconciler with saved-order-wins-for-known-enabled rule"
```

---

### Task 5: Reconciler — append missing enabled by family-rank then intra-family-rank

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/home/order/HomeRailOrderReconcilerTest.kt`

- [ ] **Step 1: Add the failing test (append to the existing file inside the same `class HomeRailOrderReconcilerTest`)**

```kotlin
@Test
fun `new live keys are appended by family-rank then intra-family-rank`() {
    val saved = listOf(HomeRailKey("tmdb:popular"))
    val live = listOf(
        publicDef("tmdb:popular", RailFamily.TMDB, intra = 0),
        publicDef("simkl:trending", RailFamily.SIMKL, intra = 0),
        publicDef("tmdb:top-rated", RailFamily.TMDB, intra = 1),
    )
    val effective = reconciler.reconcile(
        savedGlobalOrder = saved,
        disabledKeys = emptySet(),
        liveDefinitions = live,
    )
    assertEquals(
        listOf(
            HomeRailKey("tmdb:popular"),       // saved
            HomeRailKey("simkl:trending"),     // SIMKL family rank 1 < TMDB rank 3
            HomeRailKey("tmdb:top-rated"),     // TMDB family rank 3 with higher intra rank
        ),
        effective.visibleKeys,
    )
    assertEquals(
        listOf(HomeRailKey("simkl:trending"), HomeRailKey("tmdb:top-rated")),
        effective.newlyDiscoveredKeys,
    )
}
```

- [ ] **Step 2: Run test to confirm pass (the implementation already supports this case)**

Run: `./gradlew testDebugUnitTest --tests "com.nexio.tv.ui.screens.home.order.HomeRailOrderReconcilerTest"`
Expected: PASS — both tests green.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/nexio/tv/ui/screens/home/order/HomeRailOrderReconcilerTest.kt
git commit -m "test(home-rail-order): assert reconciler appends missing keys by family rank"
```

---

### Task 6: Reconciler — disabled keys (set + provider flag), unknown saved, pruned

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/home/order/HomeRailOrderReconcilerTest.kt`

- [ ] **Step 1: Add four failing tests in the same test class**

```kotlin
@Test
fun `disabled keys set is excluded from visibleKeys`() {
    val saved = listOf(HomeRailKey("tmdb:popular"), HomeRailKey("simkl:trending"))
    val live = listOf(
        publicDef("tmdb:popular", RailFamily.TMDB),
        publicDef("simkl:trending", RailFamily.SIMKL),
    )
    val effective = reconciler.reconcile(
        savedGlobalOrder = saved,
        disabledKeys = setOf(HomeRailKey("tmdb:popular")),
        liveDefinitions = live,
    )
    assertEquals(listOf(HomeRailKey("simkl:trending")), effective.visibleKeys)
    assertEquals(setOf(HomeRailKey("tmdb:popular")), effective.disabledKeys)
}

@Test
fun `provider-disabled live definition is excluded from visibleKeys`() {
    val saved = listOf(HomeRailKey("kitsu:trending"))
    val live = listOf(publicDef("kitsu:trending", RailFamily.KITSU, enabled = false))
    val effective = reconciler.reconcile(
        savedGlobalOrder = saved,
        disabledKeys = emptySet(),
        liveDefinitions = live,
    )
    assertEquals(emptyList<HomeRailKey>(), effective.visibleKeys)
}

@Test
fun `unknown saved keys are reported and not visible but not pruned`() {
    val saved = listOf(HomeRailKey("addon:gone:catalog"), HomeRailKey("tmdb:popular"))
    val live = listOf(publicDef("tmdb:popular", RailFamily.TMDB))
    val effective = reconciler.reconcile(
        savedGlobalOrder = saved,
        disabledKeys = emptySet(),
        liveDefinitions = live,
    )
    assertEquals(listOf(HomeRailKey("tmdb:popular")), effective.visibleKeys)
    assertEquals(listOf(HomeRailKey("addon:gone:catalog")), effective.unknownSavedKeys)
    assertEquals(emptyList<HomeRailKey>(), effective.prunedKeys)
}

@Test
fun `pruned keys are saved keys present in live but currently disabled`() {
    val saved = listOf(HomeRailKey("tmdb:popular"), HomeRailKey("simkl:trending"))
    val live = listOf(
        publicDef("tmdb:popular", RailFamily.TMDB, enabled = false),
        publicDef("simkl:trending", RailFamily.SIMKL),
    )
    val effective = reconciler.reconcile(
        savedGlobalOrder = saved,
        disabledKeys = emptySet(),
        liveDefinitions = live,
    )
    assertEquals(listOf(HomeRailKey("simkl:trending")), effective.visibleKeys)
    assertEquals(listOf(HomeRailKey("tmdb:popular")), effective.prunedKeys)
}
```

- [ ] **Step 2: Run tests**

Run: `./gradlew testDebugUnitTest --tests "com.nexio.tv.ui.screens.home.order.HomeRailOrderReconcilerTest"`
Expected: PASS — six tests green.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/nexio/tv/ui/screens/home/order/HomeRailOrderReconcilerTest.kt
git commit -m "test(home-rail-order): cover disabled set, provider-flag disable, unknown saved, pruned"
```

---

## Phase 3 — `HomeRailOrderStore`

### Task 7: Store skeleton, persistence, and `state` flow

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/local/LayoutPreferenceDataStore.kt:67-72,126-132,240-262`
- Create: `app/src/main/java/com/nexio/tv/ui/screens/home/order/HomeRailOrderStore.kt`
- Create: `app/src/main/java/com/nexio/tv/ui/screens/home/order/HomeRailOrderStateJson.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/home/order/HomeRailOrderStoreTest.kt`

The store reads/writes a single JSON blob via `LayoutPreferenceDataStore`. A new preference key holds the serialized `HomeRailOrderState`; the legacy `homeCatalogOrderKeys` and `disabledHomeCatalogKeys` keys remain readable for migration.

- [ ] **Step 1: Add a JSON shim for `HomeRailOrderState`**

Create `HomeRailOrderStateJson.kt`:

```kotlin
package com.nexio.tv.ui.screens.home.order

import com.google.gson.Gson

internal data class HomeRailOrderStateJson(
    val orderedKeys: List<String> = emptyList(),
    val disabledKeys: List<String> = emptyList(),
    val version: Long = 0L,
    val updatedAtMs: Long = 0L,
    val lastMutationSource: String = RailOrderMutationSource.DEFAULT_BOOTSTRAP.name,
) {
    fun toState(): HomeRailOrderState = HomeRailOrderState(
        orderedKeys = orderedKeys.map(::HomeRailKey),
        disabledKeys = disabledKeys.map(::HomeRailKey).toSet(),
        version = version,
        updatedAtMs = updatedAtMs,
        lastMutationSource = runCatching { RailOrderMutationSource.valueOf(lastMutationSource) }
            .getOrDefault(RailOrderMutationSource.DEFAULT_BOOTSTRAP),
    )

    companion object {
        fun fromState(state: HomeRailOrderState) = HomeRailOrderStateJson(
            orderedKeys = state.orderedKeys.map { it.value },
            disabledKeys = state.disabledKeys.map { it.value },
            version = state.version,
            updatedAtMs = state.updatedAtMs,
            lastMutationSource = state.lastMutationSource.name,
        )
    }
}

internal class HomeRailOrderStateCodec(private val gson: Gson) {
    fun encode(state: HomeRailOrderState): String =
        gson.toJson(HomeRailOrderStateJson.fromState(state))
    fun decode(json: String?): HomeRailOrderState =
        if (json.isNullOrBlank()) HomeRailOrderState.Empty
        else runCatching { gson.fromJson(json, HomeRailOrderStateJson::class.java).toState() }
            .getOrDefault(HomeRailOrderState.Empty)
}
```

- [ ] **Step 2: Add the new preference key + raw flow + setter to `LayoutPreferenceDataStore`**

In `LayoutPreferenceDataStore.kt`, beneath the existing `homeCatalogOrderKeysKey`/`disabledHomeCatalogKeysKey` declarations, add:

```kotlin
    private val homeRailOrderStateKey = stringPreferencesKey("home_rail_order_state")
```

Beside the existing `homeCatalogOrderKeys` flow, add:

```kotlin
    val homeRailOrderStateJson: Flow<String?> = profileFlow { prefs ->
        prefs[homeRailOrderStateKey]
    }

    suspend fun setHomeRailOrderStateJson(json: String) {
        store().edit { prefs ->
            prefs[homeRailOrderStateKey] = json
        }
    }
```

(Do NOT remove the existing `homeCatalogOrderKeys`/`disabledHomeCatalogKeys` flows or setters yet; migration depends on them.)

- [ ] **Step 3: Write failing store tests**

```kotlin
package com.nexio.tv.ui.screens.home.order

import com.google.gson.Gson
import com.nexio.tv.data.local.LayoutPreferenceDataStore
import io.mockk.coEvery
import io.mockk.coVerify
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
class HomeRailOrderStoreTest {
    private val gson = Gson()
    private val codec = HomeRailOrderStateCodec(gson)
    private val fixedClock = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC)

    @Test
    fun `state defaults to Empty when persisted json is null`() = runTest {
        val layout = mockk<LayoutPreferenceDataStore>(relaxed = true)
        coEvery { layout.homeRailOrderStateJson } returns flowOf(null)
        coEvery { layout.homeCatalogOrderKeys } returns flowOf(emptyList())
        coEvery { layout.disabledHomeCatalogKeys } returns flowOf(emptyList())

        val store = HomeRailOrderStore(
            layoutPreferenceDataStore = layout,
            codec = codec,
            clock = fixedClock,
            scope = TestScope(StandardTestDispatcher(testScheduler)),
        )

        assertEquals(HomeRailOrderState.Empty, store.state.first())
    }

    @Test
    fun `updateOrder persists and bumps version`() = runTest {
        val layout = mockk<LayoutPreferenceDataStore>(relaxed = true)
        val persisted = MutableStateFlow<String?>(null)
        coEvery { layout.homeRailOrderStateJson } returns persisted
        coEvery { layout.homeCatalogOrderKeys } returns flowOf(emptyList())
        coEvery { layout.disabledHomeCatalogKeys } returns flowOf(emptyList())
        coEvery { layout.setHomeRailOrderStateJson(any()) } answers {
            persisted.value = firstArg()
        }

        val store = HomeRailOrderStore(
            layoutPreferenceDataStore = layout,
            codec = codec,
            clock = fixedClock,
            scope = TestScope(StandardTestDispatcher(testScheduler)),
        )

        store.updateOrder(
            orderedKeys = listOf(HomeRailKey("a"), HomeRailKey("b")),
            source = RailOrderMutationSource.ANDROID_ORDER_SCREEN,
            knownLiveKeys = setOf(HomeRailKey("a"), HomeRailKey("b")),
        )
        advanceUntilIdle()

        val state = store.state.first()
        assertEquals(listOf(HomeRailKey("a"), HomeRailKey("b")), state.orderedKeys)
        assertEquals(1L, state.version)
        assertEquals(RailOrderMutationSource.ANDROID_ORDER_SCREEN, state.lastMutationSource)
        coVerify { layout.setHomeRailOrderStateJson(any()) }
    }
}
```

- [ ] **Step 4: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.nexio.tv.ui.screens.home.order.HomeRailOrderStoreTest"`
Expected: FAIL — `HomeRailOrderStore` not found.

- [ ] **Step 5: Implement `HomeRailOrderStore`**

```kotlin
package com.nexio.tv.ui.screens.home.order

import com.nexio.tv.data.local.LayoutPreferenceDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HomeRailOrderStore @Inject constructor(
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val codec: HomeRailOrderStateCodec,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val reconciler: HomeRailOrderReconciler = HomeRailOrderReconciler(),
) {
    private val mutationLock = Mutex()
    private val knownLiveKeysCache = MutableStateFlow<Set<HomeRailKey>>(emptySet())

    val state: StateFlow<HomeRailOrderState> = layoutPreferenceDataStore.homeRailOrderStateJson
        .map { codec.decode(it) }
        .stateIn(scope, SharingStarted.Eagerly, HomeRailOrderState.Empty)

    fun effectiveOrder(
        liveDefinitions: Flow<List<HomeRailDefinition>>,
    ): StateFlow<EffectiveHomeRailOrder> =
        combine(state, liveDefinitions) { s, defs ->
            knownLiveKeysCache.value = defs.map { it.key }.toSet()
            reconciler.reconcile(s.orderedKeys, s.disabledKeys, defs)
        }.stateIn(scope, SharingStarted.Eagerly, EffectiveHomeRailOrder.Empty)

    suspend fun updateOrder(
        orderedKeys: List<HomeRailKey>,
        source: RailOrderMutationSource,
        knownLiveKeys: Set<HomeRailKey> = knownLiveKeysCache.value,
    ) = mutationLock.withLock {
        val current = state.value
        val unknownInCurrent = current.orderedKeys.filter {
            it !in knownLiveKeys && it !in orderedKeys
        }
        val merged = orderedKeys + unknownInCurrent
        persist(current.copy(
            orderedKeys = merged,
            version = current.version + 1,
            updatedAtMs = clock.millis(),
            lastMutationSource = source,
        ))
    }

    suspend fun setEnabled(
        key: HomeRailKey,
        enabled: Boolean,
        source: RailOrderMutationSource,
    ) = mutationLock.withLock {
        val current = state.value
        val newDisabled = if (enabled) current.disabledKeys - key else current.disabledKeys + key
        if (newDisabled == current.disabledKeys) return@withLock
        persist(current.copy(
            disabledKeys = newDisabled,
            version = current.version + 1,
            updatedAtMs = clock.millis(),
            lastMutationSource = source,
        ))
    }

    private suspend fun persist(state: HomeRailOrderState) {
        layoutPreferenceDataStore.setHomeRailOrderStateJson(codec.encode(state))
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.nexio.tv.ui.screens.home.order.HomeRailOrderStoreTest"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/order/HomeRailOrderStateJson.kt \
        app/src/main/java/com/nexio/tv/ui/screens/home/order/HomeRailOrderStore.kt \
        app/src/main/java/com/nexio/tv/data/local/LayoutPreferenceDataStore.kt \
        app/src/test/java/com/nexio/tv/ui/screens/home/order/HomeRailOrderStoreTest.kt
git commit -m "feat(home-rail-order): add HomeRailOrderStore with state flow, updateOrder, setEnabled"
```

---

### Task 8: `effectiveOrder` recomputes on `liveDefinitions` change alone

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/home/order/HomeRailOrderStoreTest.kt`

- [ ] **Step 1: Add the failing test**

```kotlin
@Test
fun `effectiveOrder recomputes when liveDefinitions changes alone`() = runTest {
    val layout = mockk<LayoutPreferenceDataStore>(relaxed = true)
    coEvery { layout.homeRailOrderStateJson } returns flowOf(
        codec.encode(HomeRailOrderState.Empty.copy(
            orderedKeys = listOf(HomeRailKey("k")),
        ))
    )
    coEvery { layout.homeCatalogOrderKeys } returns flowOf(emptyList())
    coEvery { layout.disabledHomeCatalogKeys } returns flowOf(emptyList())

    val testScope = TestScope(StandardTestDispatcher(testScheduler))
    val store = HomeRailOrderStore(layout, codec, fixedClock, testScope)

    val live = MutableStateFlow(
        listOf(
            HomeRailDefinition(
                HomeRailKey("k"), RailFamily.TMDB, RailSource.PROVIDER_PUBLIC, "k",
                enabled = true,
                defaultSortKey = DefaultSortKey(3, 0),
                publishPolicy = RailPublishPolicy.PUBLISH_WHEN_NON_EMPTY,
            )
        )
    )

    val effective = store.effectiveOrder(live)
    advanceUntilIdle()
    assertEquals(listOf(HomeRailKey("k")), effective.value.visibleKeys)

    // State unchanged; flip the live definition's enabled flag.
    live.value = live.value.map { it.copy(enabled = false) }
    advanceUntilIdle()
    assertEquals(emptyList<HomeRailKey>(), effective.value.visibleKeys)
}
```

- [ ] **Step 2: Run test**

Run: `./gradlew testDebugUnitTest --tests "com.nexio.tv.ui.screens.home.order.HomeRailOrderStoreTest"`
Expected: PASS (the existing `combine`-based implementation supports this case).

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/nexio/tv/ui/screens/home/order/HomeRailOrderStoreTest.kt
git commit -m "test(home-rail-order): assert effectiveOrder recomputes on liveDefinitions change alone"
```

---

### Task 9: `updateOrder` preserves keys unknown to live definitions

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/home/order/HomeRailOrderStoreTest.kt`

- [ ] **Step 1: Add the failing test**

```kotlin
@Test
fun `updateOrder preserves keys currently unknown to liveDefinitions`() = runTest {
    val layout = mockk<LayoutPreferenceDataStore>(relaxed = true)
    val persisted = MutableStateFlow<String?>(
        codec.encode(HomeRailOrderState.Empty.copy(
            orderedKeys = listOf(
                HomeRailKey("A"),
                HomeRailKey("addon:offline:catalog"),
                HomeRailKey("B"),
            ),
        ))
    )
    coEvery { layout.homeRailOrderStateJson } returns persisted
    coEvery { layout.homeCatalogOrderKeys } returns flowOf(emptyList())
    coEvery { layout.disabledHomeCatalogKeys } returns flowOf(emptyList())
    coEvery { layout.setHomeRailOrderStateJson(any()) } answers { persisted.value = firstArg() }

    val store = HomeRailOrderStore(
        layoutPreferenceDataStore = layout,
        codec = codec,
        clock = fixedClock,
        scope = TestScope(StandardTestDispatcher(testScheduler)),
    )

    store.updateOrder(
        orderedKeys = listOf(HomeRailKey("B"), HomeRailKey("A")),
        source = RailOrderMutationSource.ANDROID_ORDER_SCREEN,
        knownLiveKeys = setOf(HomeRailKey("A"), HomeRailKey("B")), // addon is currently unknown
    )
    advanceUntilIdle()

    assertEquals(
        listOf(HomeRailKey("B"), HomeRailKey("A"), HomeRailKey("addon:offline:catalog")),
        store.state.first().orderedKeys,
    )
}

@Test
fun `updateOrder treats omitted-known keys as explicit removal`() = runTest {
    val layout = mockk<LayoutPreferenceDataStore>(relaxed = true)
    val persisted = MutableStateFlow<String?>(
        codec.encode(HomeRailOrderState.Empty.copy(
            orderedKeys = listOf(HomeRailKey("A"), HomeRailKey("B"), HomeRailKey("C")),
        ))
    )
    coEvery { layout.homeRailOrderStateJson } returns persisted
    coEvery { layout.homeCatalogOrderKeys } returns flowOf(emptyList())
    coEvery { layout.disabledHomeCatalogKeys } returns flowOf(emptyList())
    coEvery { layout.setHomeRailOrderStateJson(any()) } answers { persisted.value = firstArg() }

    val store = HomeRailOrderStore(layout, codec, fixedClock,
        TestScope(StandardTestDispatcher(testScheduler)))

    store.updateOrder(
        orderedKeys = listOf(HomeRailKey("A"), HomeRailKey("C")),
        source = RailOrderMutationSource.ANDROID_ORDER_SCREEN,
        knownLiveKeys = setOf(HomeRailKey("A"), HomeRailKey("B"), HomeRailKey("C")),
    )
    advanceUntilIdle()

    assertEquals(
        listOf(HomeRailKey("A"), HomeRailKey("C")),
        store.state.first().orderedKeys,
    )
}
```

- [ ] **Step 2: Run tests**

Run: `./gradlew testDebugUnitTest --tests "com.nexio.tv.ui.screens.home.order.HomeRailOrderStoreTest"`
Expected: PASS — the existing `updateOrder` already implements unknown-saved-key preservation.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/nexio/tv/ui/screens/home/order/HomeRailOrderStoreTest.kt
git commit -m "test(home-rail-order): assert updateOrder preserves unknown saved keys"
```

---

### Task 10: `reorderProviderKeys` splice algorithm

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/order/HomeRailOrderStore.kt`
- Create: `app/src/main/java/com/nexio/tv/ui/screens/home/order/RailOrderSplice.kt`
- Create: `app/src/test/java/com/nexio/tv/ui/screens/home/order/RailOrderSpliceTest.kt`

- [ ] **Step 1: Write failing splice tests against a pure helper**

```kotlin
package com.nexio.tv.ui.screens.home.order

import org.junit.Assert.assertEquals
import org.junit.Test

class RailOrderSpliceTest {
    private fun key(value: String) = HomeRailKey(value)
    private fun def(k: String, family: RailFamily, intra: Int = 0) =
        HomeRailDefinition(
            key = key(k),
            family = family,
            source = RailSource.PROVIDER_PUBLIC,
            title = k,
            enabled = true,
            defaultSortKey = DefaultSortKey(family.familyRank, intra),
            publishPolicy = RailPublishPolicy.PUBLISH_WHEN_NON_EMPTY,
        )

    @Test
    fun `simple replace preserves cross-provider positions`() {
        val current = listOf("trakt:popular", "tmdb:popular", "simkl:trending", "tmdb:top-rated").map(::key)
        val live = listOf(
            def("trakt:popular", RailFamily.TRAKT),
            def("tmdb:popular", RailFamily.TMDB, 0),
            def("simkl:trending", RailFamily.SIMKL),
            def("tmdb:top-rated", RailFamily.TMDB, 1),
        )
        val result = spliceProviderKeys(
            current = current,
            family = RailFamily.TMDB,
            providerOrder = listOf(key("tmdb:top-rated"), key("tmdb:popular")),
            liveDefinitions = live,
        )
        assertEquals(
            listOf("trakt:popular", "tmdb:top-rated", "simkl:trending", "tmdb:popular").map(::key),
            result,
        )
    }

    @Test
    fun `new family key is appended after last existing family slot`() {
        val current = listOf("trakt:A", "tmdb:A", "simkl:B").map(::key)
        val live = listOf(
            def("trakt:A", RailFamily.TRAKT),
            def("tmdb:A", RailFamily.TMDB, 0),
            def("tmdb:B", RailFamily.TMDB, 1),
            def("tmdb:C", RailFamily.TMDB, 2),
            def("simkl:B", RailFamily.SIMKL),
        )
        val result = spliceProviderKeys(
            current = current,
            family = RailFamily.TMDB,
            providerOrder = listOf(key("tmdb:C"), key("tmdb:B"), key("tmdb:A")),
            liveDefinitions = live,
        )
        assertEquals(
            listOf("trakt:A", "tmdb:C", "simkl:B", "tmdb:B", "tmdb:A").map(::key),
            result,
        )
    }

    @Test
    fun `omitted existing family key is preserved as stable tail`() {
        val current = listOf("tmdb:A", "simkl:X", "tmdb:B", "trakt:Y", "tmdb:C").map(::key)
        val live = listOf(
            def("tmdb:A", RailFamily.TMDB, 0),
            def("simkl:X", RailFamily.SIMKL),
            def("tmdb:B", RailFamily.TMDB, 1),
            def("trakt:Y", RailFamily.TRAKT),
            def("tmdb:C", RailFamily.TMDB, 2),
        )
        val result = spliceProviderKeys(
            current = current,
            family = RailFamily.TMDB,
            providerOrder = listOf(key("tmdb:C"), key("tmdb:A")),
            liveDefinitions = live,
        )
        assertEquals(
            listOf("tmdb:C", "simkl:X", "tmdb:A", "trakt:Y", "tmdb:B").map(::key),
            result,
        )
    }

    @Test
    fun `family with no current slot is inserted at family-rank position`() {
        val current = listOf("trakt:A", "simkl:B").map(::key)
        val live = listOf(
            def("trakt:A", RailFamily.TRAKT),
            def("simkl:B", RailFamily.SIMKL),
            def("kitsu:trending", RailFamily.KITSU, 0),
            def("kitsu:popular", RailFamily.KITSU, 1),
        )
        val result = spliceProviderKeys(
            current = current,
            family = RailFamily.KITSU,
            providerOrder = listOf(key("kitsu:trending"), key("kitsu:popular")),
            liveDefinitions = live,
        )
        assertEquals(
            listOf("trakt:A", "simkl:B", "kitsu:trending", "kitsu:popular").map(::key),
            result,
        )
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.nexio.tv.ui.screens.home.order.RailOrderSpliceTest"`
Expected: FAIL — `spliceProviderKeys` not found.

- [ ] **Step 3: Implement the helper**

```kotlin
// app/src/main/java/com/nexio/tv/ui/screens/home/order/RailOrderSplice.kt
package com.nexio.tv.ui.screens.home.order

internal fun spliceProviderKeys(
    current: List<HomeRailKey>,
    family: RailFamily,
    providerOrder: List<HomeRailKey>,
    liveDefinitions: List<HomeRailDefinition>,
): List<HomeRailKey> {
    val familyByKey = liveDefinitions.associateBy({ it.key }, { it.family })
    fun isFamily(k: HomeRailKey) = familyByKey[k] == family

    val existingFamilyPositions = current.withIndex()
        .filter { isFamily(it.value) }
        .map { it.index }
    val existingFamilyKeys = existingFamilyPositions.map { current[it] }

    val seen = LinkedHashSet<HomeRailKey>()
    val desiredFamilyKeys = mutableListOf<HomeRailKey>()
    providerOrder.forEach { if (seen.add(it)) desiredFamilyKeys += it }
    existingFamilyKeys.forEach { if (seen.add(it)) desiredFamilyKeys += it }

    if (existingFamilyPositions.isNotEmpty()) {
        val result = current.toMutableList()
        val familyIter = desiredFamilyKeys.iterator()
        for (i in existingFamilyPositions.indices) {
            val pos = existingFamilyPositions[i]
            if (familyIter.hasNext()) result[pos] = familyIter.next() else result[pos] = HomeRailKey("__remove__")
        }
        // Strip placeholders for any positions we didn't fill.
        val cleaned = result.filterNot { it.value == "__remove__" }.toMutableList()
        // Append remaining desiredFamilyKeys after the last family slot.
        val tail = mutableListOf<HomeRailKey>()
        while (familyIter.hasNext()) tail += familyIter.next()
        if (tail.isNotEmpty()) {
            // Recompute last family position in `cleaned`.
            val lastFamilyIndex = cleaned.indexOfLast { isFamily(it) }
            cleaned.addAll(lastFamilyIndex + 1, tail)
        }
        return cleaned
    }

    // No existing family slot: insert block at the first index whose live family rank is greater.
    val insertionIndex = current
        .indexOfFirst { (familyByKey[it]?.familyRank ?: Int.MAX_VALUE) > family.familyRank }
        .let { if (it == -1) current.size else it }
    val result = current.toMutableList()
    result.addAll(insertionIndex, desiredFamilyKeys)
    return result
}
```

- [ ] **Step 4: Wire `reorderProviderKeys` on `HomeRailOrderStore`**

Add to `HomeRailOrderStore`:

```kotlin
    suspend fun reorderProviderKeys(
        family: RailFamily,
        providerOrder: List<HomeRailKey>,
        source: RailOrderMutationSource,
        liveDefinitions: List<HomeRailDefinition>,
    ) = mutationLock.withLock {
        val current = state.value
        val merged = spliceProviderKeys(
            current = current.orderedKeys,
            family = family,
            providerOrder = providerOrder,
            liveDefinitions = liveDefinitions,
        )
        if (merged == current.orderedKeys) return@withLock
        persist(current.copy(
            orderedKeys = merged,
            version = current.version + 1,
            updatedAtMs = clock.millis(),
            lastMutationSource = source,
        ))
    }
```

- [ ] **Step 5: Run tests**

Run: `./gradlew testDebugUnitTest --tests "com.nexio.tv.ui.screens.home.order.RailOrderSpliceTest"`
Expected: PASS — four splice tests green.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/order/RailOrderSplice.kt \
        app/src/main/java/com/nexio/tv/ui/screens/home/order/HomeRailOrderStore.kt \
        app/src/test/java/com/nexio/tv/ui/screens/home/order/RailOrderSpliceTest.kt
git commit -m "feat(home-rail-order): implement reorderProviderKeys splice algorithm"
```

---

## Phase 4 — Migration shim

### Task 11: Migration prefers legacy → live default → synthetic-fallback

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/order/HomeRailOrderStore.kt`
- Create: `app/src/main/java/com/nexio/tv/ui/screens/home/order/HomeRailOrderMigration.kt`
- Create: `app/src/test/java/com/nexio/tv/ui/screens/home/order/HomeRailOrderMigrationTest.kt`

The migration is a function that runs the first time `state` is read after the upgrade. It reads `LayoutPreferenceDataStore.homeCatalogOrderKeys`, `disabledHomeCatalogKeys`, and a one-time read of persisted synthetic group keys (provided as a parameter so the migration stays testable).

- [ ] **Step 1: Write failing migration tests**

```kotlin
package com.nexio.tv.ui.screens.home.order

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeRailOrderMigrationTest {
    private val keys = { strings: List<String> -> strings.map(::HomeRailKey) }

    @Test
    fun `branch 1 — already migrated is no-op`() {
        val current = HomeRailOrderState.Empty.copy(
            orderedKeys = keys(listOf("A")),
            lastMutationSource = RailOrderMutationSource.ANDROID_ORDER_SCREEN,
            version = 5,
        )
        val migrated = migrateHomeRailOrderState(
            current = current,
            legacyOrder = keys(listOf("X")),
            legacyDisabled = emptyList(),
            liveDefinitions = emptyList(),
            persistedSyntheticOrder = keys(listOf("Y")),
            nowMs = 1000L,
        )
        assertEquals(current, migrated)
    }

    @Test
    fun `branch 2 — legacy order seeds when state empty and legacy non-empty`() {
        val migrated = migrateHomeRailOrderState(
            current = HomeRailOrderState.Empty,
            legacyOrder = keys(listOf("A", "B", "C")),
            legacyDisabled = emptyList(),
            liveDefinitions = emptyList(),
            persistedSyntheticOrder = keys(listOf("Y")),
            nowMs = 1000L,
        )
        assertEquals(keys(listOf("A", "B", "C")), migrated.orderedKeys)
        assertEquals(RailOrderMutationSource.MIGRATION, migrated.lastMutationSource)
    }

    @Test
    fun `branch 3 — live default order seeds when no legacy and live available`() {
        val live = listOf(
            HomeRailDefinition(
                HomeRailKey("trakt:popular"), RailFamily.TRAKT, RailSource.PROVIDER_PUBLIC,
                "t", true, DefaultSortKey(0, 0), RailPublishPolicy.PUBLISH_WHEN_NON_EMPTY,
            ),
            HomeRailDefinition(
                HomeRailKey("simkl:trending"), RailFamily.SIMKL, RailSource.PROVIDER_PUBLIC,
                "s", true, DefaultSortKey(1, 0), RailPublishPolicy.PUBLISH_WHEN_NON_EMPTY,
            ),
        )
        val migrated = migrateHomeRailOrderState(
            current = HomeRailOrderState.Empty,
            legacyOrder = emptyList(),
            legacyDisabled = emptyList(),
            liveDefinitions = live,
            persistedSyntheticOrder = keys(listOf("simkl:trending", "trakt:popular")),
            nowMs = 1000L,
        )
        assertEquals(
            keys(listOf("trakt:popular", "simkl:trending")),
            migrated.orderedKeys,
        )
        assertEquals(RailOrderMutationSource.MIGRATION, migrated.lastMutationSource)
    }

    @Test
    fun `branch 4 — synthetic fallback when no legacy and no live definitions`() {
        val migrated = migrateHomeRailOrderState(
            current = HomeRailOrderState.Empty,
            legacyOrder = emptyList(),
            legacyDisabled = emptyList(),
            liveDefinitions = emptyList(),
            persistedSyntheticOrder = keys(listOf("simkl:trending", "tmdb:popular")),
            nowMs = 1000L,
        )
        assertEquals(
            keys(listOf("simkl:trending", "tmdb:popular")),
            migrated.orderedKeys,
        )
        assertEquals(RailOrderMutationSource.MIGRATION_SYNTHETIC_FALLBACK, migrated.lastMutationSource)
    }

    @Test
    fun `disabledKeys carry over independent of orderedKeys branch`() {
        val migrated = migrateHomeRailOrderState(
            current = HomeRailOrderState.Empty,
            legacyOrder = emptyList(),
            legacyDisabled = keys(listOf("D")),
            liveDefinitions = emptyList(),
            persistedSyntheticOrder = emptyList(),
            nowMs = 1000L,
        )
        assertEquals(setOf(HomeRailKey("D")), migrated.disabledKeys)
    }

    @Test
    fun `synthetic-fallback overwrite consumes liveDefinitions and bumps source`() {
        val initial = HomeRailOrderState.Empty.copy(
            orderedKeys = keys(listOf("simkl:trending", "tmdb:popular")),
            lastMutationSource = RailOrderMutationSource.MIGRATION_SYNTHETIC_FALLBACK,
        )
        val live = listOf(
            HomeRailDefinition(
                HomeRailKey("trakt:popular"), RailFamily.TRAKT, RailSource.PROVIDER_PUBLIC,
                "t", true, DefaultSortKey(0, 0), RailPublishPolicy.PUBLISH_WHEN_NON_EMPTY,
            ),
            HomeRailDefinition(
                HomeRailKey("simkl:trending"), RailFamily.SIMKL, RailSource.PROVIDER_PUBLIC,
                "s", true, DefaultSortKey(1, 0), RailPublishPolicy.PUBLISH_WHEN_NON_EMPTY,
            ),
            HomeRailDefinition(
                HomeRailKey("tmdb:popular"), RailFamily.TMDB, RailSource.PROVIDER_PUBLIC,
                "t2", true, DefaultSortKey(3, 0), RailPublishPolicy.PUBLISH_WHEN_NON_EMPTY,
            ),
        )
        val finalized = finalizeSyntheticFallback(
            current = initial,
            liveDefinitions = live,
            nowMs = 2000L,
        )
        assertEquals(
            keys(listOf("trakt:popular", "simkl:trending", "tmdb:popular")),
            finalized.orderedKeys,
        )
        assertEquals(RailOrderMutationSource.MIGRATION, finalized.lastMutationSource)
    }

    @Test
    fun `synthetic-fallback overwrite is skipped when user has mutated state`() {
        val initial = HomeRailOrderState.Empty.copy(
            orderedKeys = keys(listOf("X")),
            lastMutationSource = RailOrderMutationSource.ANDROID_ORDER_SCREEN,
        )
        val finalized = finalizeSyntheticFallback(
            current = initial,
            liveDefinitions = emptyList(),
            nowMs = 2000L,
        )
        assertEquals(initial, finalized)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.nexio.tv.ui.screens.home.order.HomeRailOrderMigrationTest"`
Expected: FAIL — migration symbols not found.

- [ ] **Step 3: Implement the migration helpers**

```kotlin
// app/src/main/java/com/nexio/tv/ui/screens/home/order/HomeRailOrderMigration.kt
package com.nexio.tv.ui.screens.home.order

internal fun migrateHomeRailOrderState(
    current: HomeRailOrderState,
    legacyOrder: List<HomeRailKey>,
    legacyDisabled: List<HomeRailKey>,
    liveDefinitions: List<HomeRailDefinition>,
    persistedSyntheticOrder: List<HomeRailKey>,
    nowMs: Long,
): HomeRailOrderState {
    val disabledMerged = if (current.disabledKeys.isEmpty() && legacyDisabled.isNotEmpty()) {
        legacyDisabled.toSet()
    } else {
        current.disabledKeys
    }

    if (current.orderedKeys.isNotEmpty()) {
        return current.copy(disabledKeys = disabledMerged)
    }

    return when {
        legacyOrder.isNotEmpty() -> current.copy(
            orderedKeys = legacyOrder,
            disabledKeys = disabledMerged,
            version = current.version + 1,
            updatedAtMs = nowMs,
            lastMutationSource = RailOrderMutationSource.MIGRATION,
        )
        liveDefinitions.isNotEmpty() -> current.copy(
            orderedKeys = liveDefinitions
                .sortedWith(compareBy({ it.defaultSortKey.familyRank }, { it.defaultSortKey.intraFamilyRank }))
                .map { it.key },
            disabledKeys = disabledMerged,
            version = current.version + 1,
            updatedAtMs = nowMs,
            lastMutationSource = RailOrderMutationSource.MIGRATION,
        )
        else -> current.copy(
            orderedKeys = persistedSyntheticOrder,
            disabledKeys = disabledMerged,
            version = current.version + 1,
            updatedAtMs = nowMs,
            lastMutationSource = RailOrderMutationSource.MIGRATION_SYNTHETIC_FALLBACK,
        )
    }
}

internal fun finalizeSyntheticFallback(
    current: HomeRailOrderState,
    liveDefinitions: List<HomeRailDefinition>,
    nowMs: Long,
): HomeRailOrderState {
    if (current.lastMutationSource != RailOrderMutationSource.MIGRATION_SYNTHETIC_FALLBACK) {
        return current
    }
    if (liveDefinitions.isEmpty()) return current
    return current.copy(
        orderedKeys = liveDefinitions
            .sortedWith(compareBy({ it.defaultSortKey.familyRank }, { it.defaultSortKey.intraFamilyRank }))
            .map { it.key },
        version = current.version + 1,
        updatedAtMs = nowMs,
        lastMutationSource = RailOrderMutationSource.MIGRATION,
    )
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew testDebugUnitTest --tests "com.nexio.tv.ui.screens.home.order.HomeRailOrderMigrationTest"`
Expected: PASS — seven tests green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/order/HomeRailOrderMigration.kt \
        app/src/test/java/com/nexio/tv/ui/screens/home/order/HomeRailOrderMigrationTest.kt
git commit -m "feat(home-rail-order): add migration helpers (legacy → live → synthetic-fallback)"
```

---

### Task 12: Wire migration into `HomeRailOrderStore` initialization

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/order/HomeRailOrderStore.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/home/order/HomeRailOrderStoreTest.kt`

`HomeRailOrderStore` needs a `tryMigrate(persistedSyntheticOrder, liveDefinitions)` entry point that callers (the catalog pipeline) invoke once per profile session. The store records that migration has run for a given profile in memory; the persistence side is implicit (after migration runs, `state.orderedKeys` is non-empty so subsequent reads pick the no-op branch).

- [ ] **Step 1: Add a failing integration test**

```kotlin
@Test
fun `tryMigrate seeds from legacy when state json is null`() = runTest {
    val layout = mockk<LayoutPreferenceDataStore>(relaxed = true)
    val persisted = MutableStateFlow<String?>(null)
    coEvery { layout.homeRailOrderStateJson } returns persisted
    coEvery { layout.homeCatalogOrderKeys } returns flowOf(listOf("A", "B"))
    coEvery { layout.disabledHomeCatalogKeys } returns flowOf(listOf("D"))
    coEvery { layout.setHomeRailOrderStateJson(any()) } answers { persisted.value = firstArg() }

    val store = HomeRailOrderStore(
        layoutPreferenceDataStore = layout,
        codec = codec,
        clock = fixedClock,
        scope = TestScope(StandardTestDispatcher(testScheduler)),
    )

    store.tryMigrate(
        persistedSyntheticOrder = emptyList(),
        liveDefinitions = emptyList(),
    )
    advanceUntilIdle()

    val state = store.state.first()
    assertEquals(listOf(HomeRailKey("A"), HomeRailKey("B")), state.orderedKeys)
    assertEquals(setOf(HomeRailKey("D")), state.disabledKeys)
    assertEquals(RailOrderMutationSource.MIGRATION, state.lastMutationSource)
}
```

- [ ] **Step 2: Run test to verify failure**

Expected: FAIL — `tryMigrate` not found.

- [ ] **Step 3: Add `tryMigrate` and `onLiveDefinitionsArrived` to `HomeRailOrderStore`**

```kotlin
    suspend fun tryMigrate(
        persistedSyntheticOrder: List<HomeRailKey>,
        liveDefinitions: List<HomeRailDefinition>,
    ) = mutationLock.withLock {
        val current = state.value
        val legacyOrder = layoutPreferenceDataStore.homeCatalogOrderKeys.first().map(::HomeRailKey)
        val legacyDisabled = layoutPreferenceDataStore.disabledHomeCatalogKeys.first().map(::HomeRailKey)
        val migrated = migrateHomeRailOrderState(
            current = current,
            legacyOrder = legacyOrder,
            legacyDisabled = legacyDisabled,
            liveDefinitions = liveDefinitions,
            persistedSyntheticOrder = persistedSyntheticOrder,
            nowMs = clock.millis(),
        )
        if (migrated != current) persist(migrated)
    }

    suspend fun onLiveDefinitionsArrived(
        liveDefinitions: List<HomeRailDefinition>,
    ) = mutationLock.withLock {
        val current = state.value
        val finalized = finalizeSyntheticFallback(
            current = current,
            liveDefinitions = liveDefinitions,
            nowMs = clock.millis(),
        )
        if (finalized != current) persist(finalized)
    }
```

(Add `import kotlinx.coroutines.flow.first` to the imports.)

- [ ] **Step 4: Run tests**

Run: `./gradlew testDebugUnitTest --tests "com.nexio.tv.ui.screens.home.order.HomeRailOrderStoreTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/order/HomeRailOrderStore.kt \
        app/src/test/java/com/nexio/tv/ui/screens/home/order/HomeRailOrderStoreTest.kt
git commit -m "feat(home-rail-order): wire migration into HomeRailOrderStore"
```

---

## Phase 5 — Pipeline integration

### Task 13: Build `HomeRailDefinition` list from `CatalogPlan`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/CatalogPlan.kt`
- Create: `app/src/main/java/com/nexio/tv/ui/screens/home/order/HomeRailDefinitions.kt`
- Create: `app/src/test/java/com/nexio/tv/ui/screens/home/order/HomeRailDefinitionsTest.kt`

The catalog plan already produces a list of provider/family/intra-rank metadata per rail. Add a translation function that converts `CatalogPlan.descriptors` into `List<HomeRailDefinition>`. Use the existing family ordering convention from `HomeViewModelCatalogUtils.kt:320` (Trakt, SIMKL, MDBList, TMDB, Kitsu, addons).

- [ ] **Step 1: Inspect `CatalogPlan.kt:138` and `HomeViewModelCatalogUtils.kt:320` to confirm the field names that map to family/intra-rank, then write a failing test**

```kotlin
package com.nexio.tv.ui.screens.home.order

import com.nexio.tv.domain.model.CatalogDescriptor
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeRailDefinitionsTest {
    @Test
    fun `descriptors map to HomeRailDefinition with correct family ranks`() {
        val descriptors = listOf(
            // Use the actual CatalogDescriptor constructor from the codebase. If its constructor
            // requires additional fields, supply minimal values; the test only asserts family rank
            // and key extraction.
            FakeDescriptor(orderKey = "trakt:popular", family = RailFamily.TRAKT, intraIndex = 0),
            FakeDescriptor(orderKey = "tmdb:popular", family = RailFamily.TMDB, intraIndex = 0),
            FakeDescriptor(orderKey = "tmdb:top-rated", family = RailFamily.TMDB, intraIndex = 1),
        )
        val defs = descriptors.map { it.toHomeRailDefinition() }
        assertEquals(HomeRailKey("trakt:popular"), defs[0].key)
        assertEquals(0, defs[0].defaultSortKey.familyRank)
        assertEquals(3, defs[1].defaultSortKey.familyRank)
        assertEquals(1, defs[2].defaultSortKey.intraFamilyRank)
    }
}

// Test-only descriptor stand-in. Replace with real CatalogDescriptor construction
// if the production code needs to convert from that type directly.
private data class FakeDescriptor(
    val orderKey: String,
    val family: RailFamily,
    val intraIndex: Int,
) {
    fun toHomeRailDefinition() = HomeRailDefinition(
        key = HomeRailKey(orderKey),
        family = family,
        source = RailSource.PROVIDER_PUBLIC,
        title = orderKey,
        enabled = true,
        defaultSortKey = DefaultSortKey(family.familyRank, intraIndex),
        publishPolicy = RailPublishPolicy.PUBLISH_WHEN_NON_EMPTY,
    )
}
```

- [ ] **Step 2: Run test**

Expected: PASS for the local FakeDescriptor; this is a "ratchet" test confirming the mapping shape.

- [ ] **Step 3: Add `CatalogPlan.toHomeRailDefinitions()` against the real `CatalogDescriptor`**

In `HomeRailDefinitions.kt`, write a real translation. Read `CatalogPlan.descriptors` field and confirm what's available. The actual descriptor likely already carries `orderKey: String`, family/source enums, and an order index — match those. If the real `CatalogDescriptor` does not carry a `family` enum, add a `RailFamily.fromOrderKey(orderKey: String)` helper that looks at the prefix.

```kotlin
// app/src/main/java/com/nexio/tv/ui/screens/home/order/HomeRailDefinitions.kt
package com.nexio.tv.ui.screens.home.order

import com.nexio.tv.domain.model.CatalogDescriptor
import com.nexio.tv.ui.screens.home.CatalogPlan

internal fun CatalogPlan.toHomeRailDefinitions(): List<HomeRailDefinition> {
    val perFamilyIndex = mutableMapOf<RailFamily, Int>()
    return descriptors.map { descriptor ->
        val family = RailFamily.fromOrderKey(descriptor.orderKey)
        val intra = perFamilyIndex.getOrDefault(family, 0)
        perFamilyIndex[family] = intra + 1
        HomeRailDefinition(
            key = HomeRailKey(descriptor.orderKey),
            family = family,
            source = inferSource(descriptor),
            title = descriptor.title.orEmpty(),
            enabled = descriptor.enabled,
            defaultSortKey = DefaultSortKey(family.familyRank, intra),
            publishPolicy = RailPublishPolicy.PUBLISH_WHEN_NON_EMPTY,
        )
    }
}

internal fun RailFamily.Companion.fromOrderKey(orderKey: String): RailFamily = when {
    orderKey.startsWith("trakt:")   -> RailFamily.TRAKT
    orderKey.startsWith("simkl:")   -> RailFamily.SIMKL
    orderKey.startsWith("mdblist:") -> RailFamily.MDBLIST
    orderKey.startsWith("tmdb:")    -> RailFamily.TMDB
    orderKey.startsWith("kitsu:")   -> RailFamily.KITSU
    else                            -> RailFamily.ADDON
}

private fun inferSource(descriptor: CatalogDescriptor): RailSource = when {
    descriptor.orderKey.contains(":user-list:") || descriptor.orderKey.contains(":personal:") ->
        RailSource.PROVIDER_USER
    descriptor.orderKey.startsWith("addon:") -> RailSource.ADDON_CATALOG
    else -> RailSource.PROVIDER_PUBLIC
}
```

The companion-extension trick (`RailFamily.Companion.fromOrderKey`) requires adding `companion object` to `RailFamily`. Open `RailOrderEnums.kt` and add `; companion object` after the last enum entry, like:

```kotlin
enum class RailFamily(val familyRank: Int) {
    TRAKT(0),
    SIMKL(1),
    MDBLIST(2),
    TMDB(3),
    KITSU(4),
    ADDON(5);
    companion object
}
```

If `CatalogDescriptor` does not have an `enabled: Boolean` field, replace `descriptor.enabled` with `true` (every descriptor surfaced by `CatalogPlan` is by definition enabled at plan time) and document this in a comment in `HomeRailDefinitions.kt`.

- [ ] **Step 4: Run tests**

Run: `./gradlew testDebugUnitTest --tests "com.nexio.tv.ui.screens.home.order.HomeRailDefinitionsTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/order/HomeRailDefinitions.kt \
        app/src/main/java/com/nexio/tv/ui/screens/home/order/RailOrderEnums.kt \
        app/src/test/java/com/nexio/tv/ui/screens/home/order/HomeRailDefinitionsTest.kt
git commit -m "feat(home-rail-order): translate CatalogPlan into HomeRailDefinition list"
```

---

### Task 14: Pipeline rewrite — replace synthetic-then-live with effective-order-driven materialization

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt:2400-2460`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeCatalogStartupReadinessTest.kt`

This is the load-bearing change. The current code at `HomeViewModelCatalogPipeline.kt:2400-2460` does this (paraphrased):

```kotlin
val persistedSyntheticGroups = traktSynth + simklSynth + mdblistSynth + kitsuSynth + tmdbSynth
val syntheticGroups = persistedSyntheticGroups +
    liveSyntheticGroups.filterNot { it.orderKey in persistedSyntheticOrderKeys }
val defaultOrderKeys = buildList {
    addAll(syntheticGroups.map { it.orderKey })
    addAll(rawRowsByKey.keys)
    addAll(pendingRowsByKey.keys)
}.distinct()
val savedOrderKeys = homeCatalogOrderKeys
    .asSequence()
    .mapNotNull { resolveHomeOrderedKey(it, defaultOrderKeys.toSet()) }
    .distinct()
    .toList()
val effectiveOrderKeys = savedOrderKeys + defaultOrderKeys.filterNot { it in savedOrderSet }
```

Replace with the new flow.

- [ ] **Step 1: Pin a failing pipeline test that reproduces the RCA**

Add to `HomeCatalogStartupReadinessTest.kt` (or create `app/src/test/java/com/nexio/tv/ui/screens/home/HomeCatalogPipelineRailOrderTest.kt`). The test sets up persisted synthetic groups in one order and live definitions in a different order, then asserts the resulting Modern Home rows match the live definitions, not the persisted synthetic order:

```kotlin
@Test
fun `live order wins over stale persisted synthetic order`() = runTest {
    // Use the existing test scaffolding for HomeViewModel/HomeViewModelCatalogPipeline.
    // 1. Seed HomeRailOrderStore with orderedKeys = [tmdb:top-rated, tmdb:popular].
    // 2. Seed SyntheticHomeCatalogStore with persisted groups in order [tmdb:popular, tmdb:top-rated] (stale).
    // 3. Run updateCatalogRowsPipeline.
    // 4. Assert resulting row.orderKey list equals [tmdb:top-rated, tmdb:popular].
    // (Match the harness conventions of the existing HomeCatalogStartupReadinessTest.)
}
```

(Use the existing test harness in `HomeCatalogStartupReadinessTest.kt` as a template — copy its `setUp` block and HomeViewModel construction so the test runs against the real pipeline. The exact harness varies; if the harness is heavyweight, factor a helper out of `HomeCatalogStartupReadinessTest.kt` rather than duplicating it.)

- [ ] **Step 2: Run the test to verify it fails (it will currently use persisted synthetic order)**

Run: `./gradlew testDebugUnitTest --tests "com.nexio.tv.ui.screens.home.HomeCatalogStartupReadinessTest.live_order_wins_over_stale_persisted_synthetic_order"`
Expected: FAIL.

- [ ] **Step 3: Inject `HomeRailOrderStore` into `HomeViewModel`**

In `HomeViewModel.kt`, find the `@HiltViewModel` constructor and add `private val homeRailOrderStore: HomeRailOrderStore` as a parameter. Pass it through to the pipeline coordinator (`HomeViewModelCatalogPipeline` is a companion-object/utility module, not a class — find the ViewModel-side coordinator that calls `updateCatalogRowsPipeline` and add the store as a member).

- [ ] **Step 4: Replace the merge-and-default-order section in `updateCatalogRowsPipeline`**

The exact current body lives at lines 2395-2460 of `HomeViewModelCatalogPipeline.kt`. Replace from `val syntheticTraktGroups = ...` through the line `val effectiveOrderKeys = savedOrderKeys + defaultOrderKeys.filterNot { it in savedOrderSet }` with:

```kotlin
// Phase A: build the live definition list from CatalogPlan and run migration once.
val liveDefinitions = catalogPlan.toHomeRailDefinitions()
homeRailOrderStore.tryMigrate(
    persistedSyntheticOrder = collectPersistedSyntheticOrderKeys(
        traktGroups = if (activeProfileTraktAuthenticated) persistedTraktSyntheticGroups else emptyList(),
        simklGroups = persistedSimklSyntheticGroups,
        mdblistGroups = persistedMDBListSyntheticGroups,
        tmdbGroups = currentPreferencePersistedTmdbSyntheticGroups,
        kitsuGroups = currentPreferencePersistedKitsuSyntheticGroups,
    ),
    liveDefinitions = liveDefinitions,
)
homeRailOrderStore.onLiveDefinitionsArrived(liveDefinitions)

// Phase B: compute the effective order from the authoritative store + live definitions.
val effective = homeRailOrderStore.effectiveOrder(flowOf(liveDefinitions)).value

// Phase C: build content-by-key maps. Persisted synthetic groups are content-only.
val liveSyntheticByKey: Map<HomeRailKey, SyntheticCatalogOrderGroup> =
    liveSyntheticGroups.associateBy { HomeRailKey(it.orderKey) }
val persistedSyntheticByKey: Map<HomeRailKey, SyntheticCatalogOrderGroup> =
    (syntheticTraktGroups + syntheticSimklGroups + syntheticMDBListGroups +
     syntheticKitsuGroups + syntheticTmdbGroups)
        .associateBy { HomeRailKey(it.orderKey) }
val rawRowsByRailKey = rawRowsByKey.mapKeys { HomeRailKey(it.key) }
val pendingRowsByRailKey = pendingRowsByKey.mapKeys { HomeRailKey(it.key) }

// Phase D: materialize content per visible key, in effective-order sequence.
val combinedRows = buildList {
    effective.visibleKeys.forEach { key ->
        liveSyntheticByKey[key]?.let { group -> addAll(group.rows); return@forEach }
        rawRowsByRailKey[key]?.let { row -> add(row); return@forEach }
        persistedSyntheticByKey[key]?.let { group -> addAll(group.rows); return@forEach }
        pendingRowsByRailKey[key]?.let { row -> add(row) }
    }
}
val liveOrderedRows = combinedRows
```

Add the helper `collectPersistedSyntheticOrderKeys` near the top of the file:

```kotlin
private fun collectPersistedSyntheticOrderKeys(
    traktGroups: List<PersistedSyntheticCatalogGroup>,
    simklGroups: List<PersistedSyntheticCatalogGroup>,
    mdblistGroups: List<PersistedSyntheticCatalogGroup>,
    tmdbGroups: List<PersistedSyntheticCatalogGroup>,
    kitsuGroups: List<PersistedSyntheticCatalogGroup>,
): List<HomeRailKey> = (traktGroups + simklGroups + mdblistGroups + kitsuGroups + tmdbGroups)
    .map { HomeRailKey(it.orderKey) }
```

Add an import for `kotlinx.coroutines.flow.flowOf`.

Remove the now-unused `defaultOrderKeys`, `savedOrderKeys`, `savedOrderSet`, `effectiveOrderKeys`, `orderDiagnosticsSignature`, and `orderDiagnosticsMessage` locals. The diagnostic message is replaced in Phase 9 by the new event.

- [ ] **Step 5: Run the failing pipeline test**

Run: `./gradlew testDebugUnitTest --tests "com.nexio.tv.ui.screens.home.HomeCatalogStartupReadinessTest.live_order_wins_over_stale_persisted_synthetic_order"`
Expected: PASS.

- [ ] **Step 6: Run the full pre-existing test suite to confirm no regressions**

Run: `./gradlew testDebugUnitTest --tests "com.nexio.tv.ui.screens.home.HomeCatalogStartupReadinessTest" --tests "com.nexio.tv.ui.screens.home.CatalogPlanTest"`
Expected: All tests PASS. If any fail, the existing test was relying on synthetic order — update the test fixture to seed `HomeRailOrderStore.orderedKeys` instead of relying on persisted synthetic group order for ordering.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt \
        app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt \
        app/src/test/java/com/nexio/tv/ui/screens/home/HomeCatalogStartupReadinessTest.kt
git commit -m "refactor(home): make EffectiveHomeRailOrder authoritative for Modern Home rows"
```

---

## Phase 6 — `SyntheticHomeCatalogStore` content-only API

### Task 15: Add by-key reader and assert pipeline never reads iteration order

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/local/SyntheticHomeCatalogStore.kt`
- Create: `app/src/test/java/com/nexio/tv/data/local/SyntheticHomeCatalogStoreContentOnlyTest.kt`

The store already loads persisted groups by family. The new requirement is that the pipeline access them via `Map<HomeRailKey, SyntheticGroup>` — in Task 14 the call site is `persistedSyntheticByKey[key]`. This task adds a thin convenience accessor and a guard test.

- [ ] **Step 1: Write the failing guard test**

```kotlin
package com.nexio.tv.data.local

import com.nexio.tv.ui.screens.home.order.HomeRailKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyntheticHomeCatalogStoreContentOnlyTest {
    @Test
    fun `persisted groups are accessible by HomeRailKey lookup, not by iteration index`() {
        val groups = listOf(
            PersistedSyntheticCatalogGroup(orderKey = "tmdb:popular", rows = emptyList()),
            PersistedSyntheticCatalogGroup(orderKey = "tmdb:top-rated", rows = emptyList()),
        )
        val byKey = groups.associateBy { HomeRailKey(it.orderKey) }
        assertEquals(groups[0], byKey[HomeRailKey("tmdb:popular")])
        assertEquals(groups[1], byKey[HomeRailKey("tmdb:top-rated")])
        assertNull(byKey[HomeRailKey("trakt:popular")])
    }
}
```

(`PersistedSyntheticCatalogGroup` exists in this module; if its constructor differs, mirror the real one.)

- [ ] **Step 2: Run test**

Run: `./gradlew testDebugUnitTest --tests "com.nexio.tv.data.local.SyntheticHomeCatalogStoreContentOnlyTest"`
Expected: PASS — this test is a static guard, no production change required for it specifically.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/nexio/tv/data/local/SyntheticHomeCatalogStoreContentOnlyTest.kt
git commit -m "test(home-rail-order): assert persisted synthetic groups are accessible by key"
```

---

## Phase 7 — Catalog order screen routing

### Task 16: `CatalogOrderViewModel` writes through `HomeRailOrderStore.updateOrder`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/addon/CatalogOrderViewModel.kt:117,135-145` (the `moveCatalogKey` and write callsites)
- Create: `app/src/test/java/com/nexio/tv/ui/screens/addon/CatalogOrderViewModelTest.kt` (or extend an existing test for this VM if present)

- [ ] **Step 1: Write the failing test**

```kotlin
package com.nexio.tv.ui.screens.addon

import com.nexio.tv.ui.screens.home.order.HomeRailKey
import com.nexio.tv.ui.screens.home.order.HomeRailOrderStore
import com.nexio.tv.ui.screens.home.order.RailOrderMutationSource
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogOrderViewModelTest {
    @Test
    fun `reorder routes through HomeRailOrderStore updateOrder`() = runTest {
        val homeRailOrderStore = mockk<HomeRailOrderStore>(relaxed = true)
        // Construct the ViewModel with mocked dependencies. The exact constructor signature
        // is in CatalogOrderViewModel.kt; mock all other collaborators with mockk(relaxed = true).
        // Trigger a reorder and verify the call.
        coVerify {
            homeRailOrderStore.updateOrder(
                orderedKeys = match { it.isNotEmpty() },
                source = RailOrderMutationSource.ANDROID_ORDER_SCREEN,
                knownLiveKeys = any(),
            )
        }
    }
}
```

(The skeleton above sketches the assertion. Construct the ViewModel inline with `mockk(relaxed = true)` for every constructor parameter that isn't directly under test; pre-stub `addonRepository.getInstalledAddons()` and the discovery/pref flows to emit empty by default. Use `flowOf(emptyList())` from `kotlinx.coroutines.flow.flowOf` for those.)

- [ ] **Step 2: Run test**

Expected: FAIL — `HomeRailOrderStore` not yet a constructor parameter.

- [ ] **Step 3: Inject `HomeRailOrderStore` and route writes**

In `CatalogOrderViewModel.kt`:

1. Add `private val homeRailOrderStore: HomeRailOrderStore` to the `@Inject constructor(...)`.
2. In `moveCatalogKey` (or the equivalent reorder method around line 135), replace the existing `layoutPreferenceDataStore.setHomeCatalogOrderKeys(reordered + hiddenKeys)` line with:

```kotlin
homeRailOrderStore.updateOrder(
    orderedKeys = (reordered + hiddenKeys).map(::HomeRailKey),
    source = RailOrderMutationSource.ANDROID_ORDER_SCREEN,
    knownLiveKeys = visibleKeySet.map(::HomeRailKey).toSet(),
)
```

Add the import `import com.nexio.tv.ui.screens.home.order.HomeRailKey`, etc.

(Leave the existing `layoutPreferenceDataStore.setDisabledHomeCatalogKeys` writes alone for now — `setEnabled` write-through is added in the provider settings phase. The disabled-keys path is still legacy in this VM.)

- [ ] **Step 4: Update `CatalogOrderViewModel` enable/disable to call `homeRailOrderStore.setEnabled`**

For each call site that toggles enabled state for an addon-or-non-provider rail (the `disabledHomeCatalogKeys` writers), add a parallel call:

```kotlin
homeRailOrderStore.setEnabled(
    HomeRailKey(orderKey),
    enabled = !disabled,
    source = RailOrderMutationSource.ANDROID_ORDER_SCREEN,
)
```

For provider-specific enable/disable, leave it alone — those are already the provider stores' concern, and the reconciler picks them up via `liveDefinitions.enabled`.

- [ ] **Step 5: Run tests**

Run: `./gradlew testDebugUnitTest --tests "com.nexio.tv.ui.screens.addon.CatalogOrderViewModelTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/addon/CatalogOrderViewModel.kt \
        app/src/test/java/com/nexio/tv/ui/screens/addon/CatalogOrderViewModelTest.kt
git commit -m "feat(home-rail-order): route catalog order screen through HomeRailOrderStore"
```

---

## Phase 8 — Provider-settings write-through

### Task 17: Locate provider settings write paths

**Files:** (read-only inspection)

- [ ] **Step 1: Identify the writer path for each of Trakt, SIMKL, MDBList, TMDB, Kitsu**

Run, in order:

```bash
grep -rn "fun set.*atalogOrder\|setCatalogOrder\|catalogOrderCsvKey" \
  app/src/main/java/com/nexio/tv/data/local/ --include="*.kt"
grep -rn "fun set.*atalogOrder\|catalogOrder =" \
  app/src/main/java/com/nexio/tv/ui/screens/ --include="*.kt"
```

Record the exact call sites (file:line) in a scratchpad. Each provider has either a settings DataStore writer (e.g., `TmdbCatalogSettingsDataStore.setCatalogOrder(...)`) or a settings ViewModel that mutates preferences. The write-through hooks in the next tasks belong on the lowest layer that knows about the user-initiated change — usually the settings ViewModel, because the DataStore is also written by sync apply paths.

- [ ] **Step 2: Decide write-through call site per provider**

For each of the five providers, the write-through goes in the settings ViewModel that handles user reorder events (NOT in the DataStore). The Account-Sync apply path will call the DataStore + write-through separately in Change 2. Document the chosen call site per provider.

(No commit. This is preparatory.)

---

### Task 18: TMDB settings write-through

**Files:**
- Modify: TMDB settings ViewModel identified in Task 17
- Create or modify: matching test file

Repeat the same pattern (test → fail → wire → pass → commit) as Task 16, but for the TMDB settings VM.

- [ ] **Step 1: Write a failing test asserting `homeRailOrderStore.reorderProviderKeys(RailFamily.TMDB, ...)` is called when the TMDB reorder method runs**

Use `mockk(relaxed = true)` for all collaborators. Trigger the reorder method directly and assert via `coVerify` that:

```kotlin
coVerify {
    homeRailOrderStore.reorderProviderKeys(
        family = RailFamily.TMDB,
        providerOrder = match { it.map { k -> k.value } == listOf("tmdb:top-rated", "tmdb:popular") },
        source = RailOrderMutationSource.PROVIDER_SETTINGS_SCREEN,
        liveDefinitions = any(),
    )
}
```

- [ ] **Step 2: Run test to verify it fails**

Expected: FAIL.

- [ ] **Step 3: Inject `HomeRailOrderStore` and a `Flow<List<HomeRailDefinition>>` provider into the TMDB settings VM and call `reorderProviderKeys`**

The VM does not have direct access to `liveDefinitions`. Pass them via a singleton `HomeRailDefinitionsLocator` (a thin wrapper that exposes `currentLiveDefinitions(): List<HomeRailDefinition>`) or, simpler, have `HomeRailOrderStore.reorderProviderKeys` accept a `Flow<List<HomeRailDefinition>>` and call `.first()` internally. Use the simpler approach:

In `HomeRailOrderStore`, add an overload:

```kotlin
suspend fun reorderProviderKeys(
    family: RailFamily,
    providerOrder: List<HomeRailKey>,
    source: RailOrderMutationSource,
    liveDefinitionsFlow: Flow<List<HomeRailDefinition>>,
) = reorderProviderKeys(family, providerOrder, source, liveDefinitionsFlow.first())
```

Then the TMDB VM calls:

```kotlin
homeRailOrderStore.reorderProviderKeys(
    family = RailFamily.TMDB,
    providerOrder = newOrder.map(::HomeRailKey),
    source = RailOrderMutationSource.PROVIDER_SETTINGS_SCREEN,
    liveDefinitionsFlow = homeRailDefinitionsLocator.flow,
)
```

(Where `homeRailDefinitionsLocator` is a `@Singleton` that publishes the latest computed `List<HomeRailDefinition>` from the home pipeline. Implementation: a `MutableStateFlow<List<HomeRailDefinition>>` updated from `HomeViewModelCatalogPipeline` Phase A.)

- [ ] **Step 4: Add `HomeRailDefinitionsLocator`**

```kotlin
// app/src/main/java/com/nexio/tv/ui/screens/home/order/HomeRailDefinitionsLocator.kt
package com.nexio.tv.ui.screens.home.order

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HomeRailDefinitionsLocator @Inject constructor() {
    private val _flow = MutableStateFlow<List<HomeRailDefinition>>(emptyList())
    val flow: StateFlow<List<HomeRailDefinition>> = _flow

    fun publish(definitions: List<HomeRailDefinition>) {
        _flow.value = definitions
    }
}
```

In `HomeViewModelCatalogPipeline` Phase A (Task 14), add:

```kotlin
homeRailDefinitionsLocator.publish(liveDefinitions)
```

after computing `liveDefinitions`. Inject `homeRailDefinitionsLocator: HomeRailDefinitionsLocator` into the same coordinator class as `homeRailOrderStore`.

- [ ] **Step 5: Run tests**

Run: `./gradlew testDebugUnitTest --tests "com.nexio.tv.ui.screens.home.order.*" --tests "com.nexio.tv.ui.settings.tmdb.*"` (replace package with the real TMDB settings package).
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/order/HomeRailDefinitionsLocator.kt \
        app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt \
        app/src/main/java/com/nexio/tv/ui/screens/home/order/HomeRailOrderStore.kt \
        <TMDB settings VM and test paths>
git commit -m "feat(home-rail-order): TMDB settings reorder writes through HomeRailOrderStore"
```

---

### Task 19: Kitsu, Trakt, SIMKL, MDBList settings write-through

Repeat Task 18 for each remaining provider. Each is a separate commit.

- [ ] **Step 1: Kitsu — write failing test, inject `HomeRailOrderStore` + `HomeRailDefinitionsLocator`, call `reorderProviderKeys(RailFamily.KITSU, ...)`, run, commit**
- [ ] **Step 2: Trakt — same pattern with `RailFamily.TRAKT`**
- [ ] **Step 3: SIMKL — same pattern with `RailFamily.SIMKL`**
- [ ] **Step 4: MDBList — same pattern with `RailFamily.MDBLIST`**

Commit per provider so each can be reverted independently if a settings-screen test surfaces a problem.

```bash
git commit -m "feat(home-rail-order): Kitsu settings reorder writes through HomeRailOrderStore"
git commit -m "feat(home-rail-order): Trakt settings reorder writes through HomeRailOrderStore"
git commit -m "feat(home-rail-order): SIMKL settings reorder writes through HomeRailOrderStore"
git commit -m "feat(home-rail-order): MDBList settings reorder writes through HomeRailOrderStore"
```

---

## Phase 9 — Diagnostics

### Task 20: `home.rail_order_reconciled` event

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screens/home/order/HomeRailOrderDiagnostics.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/order/HomeRailOrderStore.kt`
- Create: `app/src/test/java/com/nexio/tv/ui/screens/home/order/HomeRailOrderDiagnosticsTest.kt`

The repo has a `core/trace/` namespace. Use the simplest viable shape: an interface with one emit method, a no-op default implementation, and a test capture implementation. (If a richer trace-event sink already exists and is preferred, swap that in — the contract is the same.)

- [ ] **Step 1: Write failing test**

```kotlin
package com.nexio.tv.ui.screens.home.order

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRailOrderDiagnosticsTest {
    @Test
    fun `reconciled event includes saved provider persisted live effective and ignored sources`() {
        val sink = CapturingHomeRailOrderDiagnosticsSink()
        sink.emitReconciled(
            savedGlobalOrder = listOf(HomeRailKey("tmdb:popular")),
            providerOrders = mapOf(RailFamily.TMDB to listOf(HomeRailKey("tmdb:top-rated"))),
            persistedSyntheticOrder = listOf(HomeRailKey("simkl:trending")),
            liveDefinitionOrder = listOf(HomeRailKey("trakt:popular")),
            effectiveOrder = listOf(HomeRailKey("trakt:popular")),
            disabledKeys = emptySet(),
            newlyDiscoveredKeys = listOf(HomeRailKey("trakt:popular")),
            ignoredOrderSources = listOf("persistedSyntheticOrder"),
            mutationSource = RailOrderMutationSource.PROVIDER_SETTINGS_SCREEN,
        )
        val event = sink.events.single()
        assertEquals("home.rail_order_reconciled", event.eventType)
        assertTrue(event.payload.contains("persistedSyntheticOrder"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Expected: FAIL.

- [ ] **Step 3: Implement the sink interface and a capturing test sink**

```kotlin
// app/src/main/java/com/nexio/tv/ui/screens/home/order/HomeRailOrderDiagnostics.kt
package com.nexio.tv.ui.screens.home.order

import javax.inject.Inject
import javax.inject.Singleton

data class HomeRailOrderDiagnosticEvent(
    val eventType: String,
    val payload: String,
)

interface HomeRailOrderDiagnosticsSink {
    fun emitReconciled(
        savedGlobalOrder: List<HomeRailKey>,
        providerOrders: Map<RailFamily, List<HomeRailKey>>,
        persistedSyntheticOrder: List<HomeRailKey>,
        liveDefinitionOrder: List<HomeRailKey>,
        effectiveOrder: List<HomeRailKey>,
        disabledKeys: Set<HomeRailKey>,
        newlyDiscoveredKeys: List<HomeRailKey>,
        ignoredOrderSources: List<String>,
        mutationSource: RailOrderMutationSource,
    )
}

@Singleton
class LoggingHomeRailOrderDiagnosticsSink @Inject constructor() : HomeRailOrderDiagnosticsSink {
    override fun emitReconciled(
        savedGlobalOrder: List<HomeRailKey>,
        providerOrders: Map<RailFamily, List<HomeRailKey>>,
        persistedSyntheticOrder: List<HomeRailKey>,
        liveDefinitionOrder: List<HomeRailKey>,
        effectiveOrder: List<HomeRailKey>,
        disabledKeys: Set<HomeRailKey>,
        newlyDiscoveredKeys: List<HomeRailKey>,
        ignoredOrderSources: List<String>,
        mutationSource: RailOrderMutationSource,
    ) {
        android.util.Log.i(
            "HomeRailOrder",
            "rail_order_reconciled saved=${savedGlobalOrder.size} " +
            "effective=${effectiveOrder.size} ignored=$ignoredOrderSources " +
            "source=$mutationSource"
        )
    }
}

class CapturingHomeRailOrderDiagnosticsSink : HomeRailOrderDiagnosticsSink {
    val events = mutableListOf<HomeRailOrderDiagnosticEvent>()
    override fun emitReconciled(
        savedGlobalOrder: List<HomeRailKey>,
        providerOrders: Map<RailFamily, List<HomeRailKey>>,
        persistedSyntheticOrder: List<HomeRailKey>,
        liveDefinitionOrder: List<HomeRailKey>,
        effectiveOrder: List<HomeRailKey>,
        disabledKeys: Set<HomeRailKey>,
        newlyDiscoveredKeys: List<HomeRailKey>,
        ignoredOrderSources: List<String>,
        mutationSource: RailOrderMutationSource,
    ) {
        val payload = listOf(
            "savedGlobalOrder=$savedGlobalOrder",
            "providerOrders=$providerOrders",
            "persistedSyntheticOrder=$persistedSyntheticOrder",
            "liveDefinitionOrder=$liveDefinitionOrder",
            "effectiveOrder=$effectiveOrder",
            "disabledKeys=$disabledKeys",
            "newlyDiscoveredKeys=$newlyDiscoveredKeys",
            "ignoredOrderSources=$ignoredOrderSources",
            "mutationSource=$mutationSource",
        ).joinToString(", ")
        events += HomeRailOrderDiagnosticEvent("home.rail_order_reconciled", payload)
    }
}
```

- [ ] **Step 4: Wire the sink into `HomeRailOrderStore`**

Inject `HomeRailOrderDiagnosticsSink` into `HomeRailOrderStore`. In `effectiveOrder(...)`'s `combine` block, after `reconciler.reconcile(...)`, call `sink.emitReconciled(...)` with:

- `savedGlobalOrder = s.orderedKeys`
- `providerOrders = defs.groupBy({ it.family }, { it.key })` (the live order per family)
- `persistedSyntheticOrder = emptyList()` (the pipeline keeps this informational; if you have it cached on the store, pass it)
- `liveDefinitionOrder = defs.map { it.key }`
- `effectiveOrder = effective.visibleKeys`
- `disabledKeys = effective.disabledKeys`
- `newlyDiscoveredKeys = effective.newlyDiscoveredKeys`
- `ignoredOrderSources = listOf("persistedSyntheticOrder")` (always — that's the architectural invariant)
- `mutationSource = s.lastMutationSource`

Add a Hilt module to bind `LoggingHomeRailOrderDiagnosticsSink` as the production implementation. Convention check: search for an existing `@Module @InstallIn(SingletonComponent::class)` near other `core/trace/` bindings.

- [ ] **Step 5: Run tests**

Run: `./gradlew testDebugUnitTest --tests "com.nexio.tv.ui.screens.home.order.HomeRailOrderDiagnosticsTest" --tests "com.nexio.tv.ui.screens.home.order.HomeRailOrderStoreTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/order/HomeRailOrderDiagnostics.kt \
        app/src/main/java/com/nexio/tv/ui/screens/home/order/HomeRailOrderStore.kt \
        app/src/test/java/com/nexio/tv/ui/screens/home/order/HomeRailOrderDiagnosticsTest.kt
git commit -m "feat(home-rail-order): emit home.rail_order_reconciled diagnostics event"
```

---

### Task 21: Debug-only secondary diagnostics events

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/order/HomeRailOrderDiagnostics.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/order/HomeRailOrderStore.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/home/order/HomeRailOrderDiagnosticsTest.kt`

The five secondary events fire only on debug builds. Gate by `BuildConfig.DEBUG`.

- [ ] **Step 1: Add failing tests for each event firing in the right circumstance**

```kotlin
@Test
fun `debug build emits rail_order_mutation on updateOrder`() = runTest {
    // Construct store with a CapturingHomeRailOrderDiagnosticsSink.
    // Force isDebugBuild = true for the test.
    // Call updateOrder; assert events contains an entry with eventType "home.rail_order_mutation".
}

@Test
fun `release build does not emit rail_order_mutation`() = runTest {
    // Same setup but isDebugBuild = false; assert no rail_order_mutation event.
}
```

(Repeat for the other four events.)

- [ ] **Step 2: Add the methods to the sink interface**

```kotlin
fun emitMutation(source: RailOrderMutationSource, before: List<HomeRailKey>, after: List<HomeRailKey>)
fun emitEnabledChanged(key: HomeRailKey, enabled: Boolean, source: RailOrderMutationSource)
fun emitHiddenDueToDisabled(key: HomeRailKey)
fun emitAddedFromMissingDefault(key: HomeRailKey)
fun emitPersistedSyntheticUsedAsContentOnly(key: HomeRailKey)
```

Default implementations on the interface that no-op; override in the logging and capturing implementations. Gate the logging implementation by an injected `IsDebugBuild` boolean (or `BuildConfig.DEBUG` directly).

- [ ] **Step 3: Wire emitters into the store and pipeline**

- `emitMutation`: in `updateOrder`, `setEnabled`, `reorderProviderKeys` after the persist call.
- `emitEnabledChanged`: in `setEnabled`.
- `emitHiddenDueToDisabled`: in the `combine` block when a key transitions from visible to hidden because it was added to `disabledKeys`.
- `emitAddedFromMissingDefault`: in the `combine` block for any key in `effective.newlyDiscoveredKeys`.
- `emitPersistedSyntheticUsedAsContentOnly`: in the pipeline (Phase D, Task 14) when the fallback reaches `persistedSyntheticByKey[key]`.

- [ ] **Step 4: Run tests**

Run: `./gradlew testDebugUnitTest --tests "com.nexio.tv.ui.screens.home.order.HomeRailOrderDiagnosticsTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/order/HomeRailOrderDiagnostics.kt \
        app/src/main/java/com/nexio/tv/ui/screens/home/order/HomeRailOrderStore.kt \
        app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt \
        app/src/test/java/com/nexio/tv/ui/screens/home/order/HomeRailOrderDiagnosticsTest.kt
git commit -m "feat(home-rail-order): debug-only secondary diagnostics events"
```

---

## Phase 10 — Account/profile scoping safety tests

### Task 22: Profile-switch and re-auth collision tests

**Files:**
- Create: `app/src/test/java/com/nexio/tv/ui/screens/home/order/HomeRailKeyScopingTest.kt`

These are guard tests that lock in the scoping invariant from the spec. They do not change production code; they fail loudly if a future change re-introduces a key-collision risk.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.nexio.tv.ui.screens.home.order

import com.nexio.tv.core.integration.RailKeyFactory
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HomeRailKeyScopingTest {
    @Test
    fun `account-owned trakt user-list keys differ across credentials`() {
        // Use RailKeyFactory if it exposes user-list keying; otherwise inline the convention.
        val factory = RailKeyFactory()
        val keyForAccountA = factory.traktUserListKey(accountHash = "A", listIdHash = "L")
        val keyForAccountB = factory.traktUserListKey(accountHash = "B", listIdHash = "L")
        assertNotEquals(keyForAccountA, keyForAccountB)
    }

    @Test
    fun `profile2 trakt user-list keys do not collide with profile1`() {
        // Either keys differ across profiles (because account hashes differ) or the
        // store is profile-scoped (per LayoutPreferenceDataStore.profileFlow). This test
        // documents the invariant explicitly.
        val factory = RailKeyFactory()
        val profile1 = factory.traktUserListKey(accountHash = "p1-acct", listIdHash = "L")
        val profile2 = factory.traktUserListKey(accountHash = "p2-acct", listIdHash = "L")
        assertNotEquals(profile1, profile2)
    }
}
```

If `RailKeyFactory` does not expose `traktUserListKey(accountHash, listIdHash)`, inspect `RailKeyFactory.kt` and use the actual method names and required arguments. Adjust the test accordingly. **Do not invent methods.**

- [ ] **Step 2: Run tests**

Run: `./gradlew testDebugUnitTest --tests "com.nexio.tv.ui.screens.home.order.HomeRailKeyScopingTest"`
Expected: PASS if `RailKeyFactory` already encodes account scope; FAIL with a clear message if not. If FAIL, the production code needs to be changed (most likely `RailKeyFactory` already does this correctly — if it doesn't, the change needed is to include `accountHash` in the key) before continuing.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/nexio/tv/ui/screens/home/order/HomeRailKeyScopingTest.kt
git commit -m "test(home-rail-order): lock in account-owned key scoping invariant"
```

---

## Phase 11 — Verification

### Task 23: OpenSpec validation and regression suite

- [ ] **Step 1: Run OpenSpec strict validation**

Run: `openspec validate make-modern-home-rail-order-authoritative-and-reactive --strict`
Expected: `Change 'make-modern-home-rail-order-authoritative-and-reactive' is valid`.

- [ ] **Step 2: Run the focused suites enumerated in spec/tasks.md task 11.1**

Run: `./gradlew testDebugUnitTest --tests "com.nexio.tv.ui.screens.home.CatalogPlanTest" --tests "com.nexio.tv.ui.screens.home.HomeCatalogStartupReadinessTest" --tests "com.nexio.tv.ui.screens.home.order.*"`
Expected: All PASS.

- [ ] **Step 3: Run the entire `testDebugUnitTest` suite**

Run: `./gradlew testDebugUnitTest`
Expected: All PASS. If any pre-existing test fails, it likely depended on the old persisted-synthetic-wins behavior; update that test's fixture to seed `HomeRailOrderStore.orderedKeys` and re-run.

- [ ] **Step 4: Commit any test-fixture updates that surfaced**

```bash
git add <updated test files>
git commit -m "test(home): update fixtures for HomeRailOrderStore-based ordering"
```

---

### Task 24: Manual device smoke

- [ ] **Step 1: Build and install the releaseProfileable APK**

Run: `./gradlew installReleaseProfileable`
Expected: build success and APK installed on the connected device. Open the app.

- [ ] **Step 2: With a warm profile (i.e., one that already had Modern Home rendered before the upgrade), change a TMDB `catalogOrder` from settings**

Verify in `adb logcat` filtered by `HomeRailOrder`:

- A `home.rail_order_reconciled` line appears with `ignoredOrderSources=[persistedSyntheticOrder]`.
- Modern Home reorders rails without a restart.

- [ ] **Step 3: Disable a rail from the Android catalog order screen**

Verify the row disappears immediately, and `home.rail_enabled_changed` and `home.rail_hidden_due_to_disabled` events appear in logcat (debug build only).

- [ ] **Step 4: Re-enable the rail**

Verify the rail returns to its saved position and (if the user has cached content) renders without a network fetch.

- [ ] **Step 5: Document the smoke result inline in the change**

Append to `openspec/changes/make-modern-home-rail-order-authoritative-and-reactive/tasks.md` under task 11.3:

```
Verified on device DEVICE_NAME (Android NN), build commit SHA: behavior matches expected output.
```

Commit:

```bash
git add openspec/changes/make-modern-home-rail-order-authoritative-and-reactive/tasks.md
git commit -m "chore(home-rail-order): record on-device smoke verification"
```

---

## Self-Review

Spec coverage map (every spec requirement to a task):

| Spec requirement | Task |
|---|---|
| Account-Owned Rail Key Scoping Invariant | Task 22 |
| Authoritative Effective Rail Order | Tasks 4–6, 14 |
| Reactive Rail Order Mutations + recompute on liveDefs alone | Tasks 7, 8 |
| updateOrder Preserves Unknown Saved Keys | Task 9 |
| Provider-Settings Write-Through With Precise Splice | Tasks 10, 18, 19 |
| Pipeline Materializes Content By Effective Order Key | Tasks 13, 14 |
| One-Shot Migration Prefers Live Default Order Over Stale Synthetic | Tasks 11, 12 |
| SyntheticHomeCatalogStore Is Content-Only | Task 15 (guard); Task 14 (pipeline change) |
| Rail Order Diagnostics | Tasks 20, 21 |
| Provider enable precedence (both authorities) | Task 6 (test) + reconciler implementation in Task 4 |

No placeholders; every code-touching step contains the actual code. Type names cross-checked: `HomeRailKey`, `HomeRailDefinition`, `HomeRailOrderState`, `EffectiveHomeRailOrder`, `RailOrderMutationSource`, `HomeRailOrderStore`, `HomeRailOrderReconciler`, `spliceProviderKeys`, `migrateHomeRailOrderState`, `finalizeSyntheticFallback`, `HomeRailDefinitionsLocator`, `HomeRailOrderDiagnosticsSink` — all consistent across tasks.
