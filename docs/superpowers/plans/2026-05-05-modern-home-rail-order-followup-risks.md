# Modern Home Rail Order — Follow-up Risks Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Resolve seven of the eight outstanding final-review items (I5, I6, M1, M4, M5, M6, M7) flagged after the home-rail-order RCA work landed. M2 (deprecate legacy DataStore setters) is explicitly deferred — it requires a release-cycle decision that should not be made autonomously.

**Architecture:** Each item is independent, so this plan organises by item rather than by layer. Phases land smallest-payoff-first to harvest quick wins before committing to the larger refactors. The pipeline cleanup (M1) is the heaviest task and lands last so any earlier instability (e.g., a test-coverage gap surfaced by I5/I6) doesn't tangle with a refactor diff.

**Tech Stack:** Kotlin, JUnit 4, MockK, kotlinx-coroutines-test, Hilt. No new dependencies.

**Spec source:** Final review report from the run on 2026-05-05 against commit `2ce76dc83` (head before final-review fixes).
**Base commit for this plan:** `10dd287ec` (last home-rail-order commit, after the foundation, sync extension, ship-blocker fix-up, and three follow-ups).

**Build & test commands.** All from repo root.
- All unit tests: `./gradlew testDebugUnitTest`
- Single class: `./gradlew testDebugUnitTest --tests "<fully.qualified.ClassName>"`
- OpenSpec strict: `openspec validate make-modern-home-rail-order-authoritative-and-reactive --strict` and `openspec validate extend-account-sync-with-tmdb-kitsu-catalogs --strict`
- APK build: `./gradlew assembleDebug`

**Commit style.** Match recent repo commits: `fix(home-rail-order): ...`, `refactor(home-rail-order): ...`, `test(home-rail-order): ...`, `docs(home-rail-order): ...`. Never use `git add -A` or `git add .` — the working tree has pre-existing dirty paths (`openrouter_reasoning_models.json`, `media`, `review-dossier/...`) that must NOT be staged.

---

## Phase 1 — M7: spec key-format consistency

The OpenSpec scenarios use colon-delimited keys (`tmdb:popular`, `simkl:trending`) but production keys use underscore delimiters (`tmdb_popular_movies`, `simkl_tv_trending_today`). The reconciler is delimiter-agnostic so the *implementation* works fine, but `RailFamily.fromOrderKey` would classify the spec-scenario keys as `ADDON` (else-branch) under its underscore-prefix dispatch — making it impossible to literally execute the scenarios as test data.

The fix is a search-replace inside the OpenSpec spec files. Production code is untouched.

### Task 1: Replace colon delimiters in foundation spec scenarios

**Files:**
- Modify: `openspec/changes/make-modern-home-rail-order-authoritative-and-reactive/specs/home-rail-order/spec.md`

- [ ] **Step 1: Sanity-grep the file for colon-delimited keys**

```bash
grep -n "tmdb:\|trakt:\|simkl:\|kitsu:\|mdblist:" openspec/changes/make-modern-home-rail-order-authoritative-and-reactive/specs/home-rail-order/spec.md
```

Expected: ~42 matches (the foundation spec has the most scenarios).

- [ ] **Step 2: Apply the substitutions**

Use `sed` for the substitutions. Do all five providers in one pass. Run from the repo root:

```bash
sed -i.bak \
    -e 's/tmdb:popular\b/tmdb_popular_movies/g' \
    -e 's/tmdb:top-rated\b/tmdb_top_rated_movies/g' \
    -e 's/simkl:trending\b/simkl_tv_trending_today/g' \
    -e 's/simkl:movies\b/simkl_movie_trending_week/g' \
    -e 's/simkl:anime\b/simkl_anime_trending_week/g' \
    -e 's/trakt:popular\b/trakt_popular_movies/g' \
    -e 's/trakt:trending:shows\b/trakt_trending_shows/g' \
    -e 's/trakt:user-list:{listIdHash}/trakt_user_list_{listIdHash}/g' \
    -e 's/kitsu:trending:anime\b/kitsu_trending_anime/g' \
    -e 's/kitsu:trending\b/kitsu_trending_anime/g' \
    -e 's/mdblist:list:{listIdHash}/mdblist_list_{listIdHash}/g' \
    -e 's/addon:abc:catalog:movie:popular/cinemeta_movie_top/g' \
    -e 's/addon:gone:catalog/cinemeta_gone_catalog/g' \
    -e 's/addon:offline:catalog/cinemeta_offline_catalog/g' \
    -e 's/addon:{addonIdHash}:catalog:{type}:{id}/{addonIdHash}_{type}_{id}/g' \
    openspec/changes/make-modern-home-rail-order-authoritative-and-reactive/specs/home-rail-order/spec.md
rm openspec/changes/make-modern-home-rail-order-authoritative-and-reactive/specs/home-rail-order/spec.md.bak
```

- [ ] **Step 3: Verify no colon-delimited keys remain**

```bash
grep -n "tmdb:\|trakt:\|simkl:\|kitsu:\|mdblist:" openspec/changes/make-modern-home-rail-order-authoritative-and-reactive/specs/home-rail-order/spec.md
```

Expected: 0 matches. If matches remain, eyeball them — they're likely scenario keys that didn't match the substitution patterns above. Apply additional `sed -e 's/<found>/<replacement>/g'` lines and re-run.

- [ ] **Step 4: Run OpenSpec strict validation**

```bash
openspec validate make-modern-home-rail-order-authoritative-and-reactive --strict
```

Expected: `Change 'make-modern-home-rail-order-authoritative-and-reactive' is valid`. The OpenSpec parser doesn't care about key shapes — it only validates the scenario block structure. Confirms we didn't break the markdown.

- [ ] **Step 5: Commit**

```bash
git add openspec/changes/make-modern-home-rail-order-authoritative-and-reactive/specs/home-rail-order/spec.md
git commit -m "docs(home-rail-order): use underscore-delimited keys in foundation spec scenarios"
```

---

### Task 2: Replace colon delimiters in sync-extension spec scenarios

**Files:**
- Modify: `openspec/changes/extend-account-sync-with-tmdb-kitsu-catalogs/specs/account-config-sync/spec.md`
- Modify: `openspec/changes/extend-account-sync-with-tmdb-kitsu-catalogs/specs/home-rail-order/spec.md`

- [ ] **Step 1: Apply substitutions to both files**

```bash
for f in \
    openspec/changes/extend-account-sync-with-tmdb-kitsu-catalogs/specs/account-config-sync/spec.md \
    openspec/changes/extend-account-sync-with-tmdb-kitsu-catalogs/specs/home-rail-order/spec.md; do
  sed -i.bak \
      -e 's/tmdb:popular\b/tmdb_popular_movies/g' \
      -e 's/tmdb:top-rated\b/tmdb_top_rated_movies/g' \
      -e 's/simkl:trending\b/simkl_tv_trending_today/g' \
      -e 's/trakt:popular\b/trakt_popular_movies/g' \
      -e 's/kitsu:trending\b/kitsu_trending_anime/g' \
      -e 's/kitsu:popular\b/kitsu_popular_anime/g' \
      -e 's/mdblist:list:{listIdHash}/mdblist_list_{listIdHash}/g' \
      "$f"
  rm "$f.bak"
done
```

- [ ] **Step 2: Verify no colon-delimited keys remain**

```bash
grep -n "tmdb:\|trakt:\|simkl:\|kitsu:\|mdblist:" \
    openspec/changes/extend-account-sync-with-tmdb-kitsu-catalogs/specs/account-config-sync/spec.md \
    openspec/changes/extend-account-sync-with-tmdb-kitsu-catalogs/specs/home-rail-order/spec.md
```

Expected: 0 matches.

- [ ] **Step 3: Run OpenSpec strict validation**

```bash
openspec validate extend-account-sync-with-tmdb-kitsu-catalogs --strict
```

Expected: `Change 'extend-account-sync-with-tmdb-kitsu-catalogs' is valid`.

- [ ] **Step 4: Commit**

```bash
git add openspec/changes/extend-account-sync-with-tmdb-kitsu-catalogs/specs/account-config-sync/spec.md \
        openspec/changes/extend-account-sync-with-tmdb-kitsu-catalogs/specs/home-rail-order/spec.md
git commit -m "docs(account-sync): use underscore-delimited keys in extension spec scenarios"
```

---

## Phase 2 — I5: profile-scoping test for `HomeRailOrderState`

The existing `HomeRailKeyScopingTest.kt` exercises `RailKeyFactory` (a sibling system used by hydration and continue-watching), not `HomeRailOrderState`. The OpenSpec scenario "Account-owned key collision across profiles is impossible" is about `HomeRailOrderState` and `SyntheticHomeCatalogStore` directly. The security property holds today via `LayoutPreferenceDataStore.profileFlow`, but the test doesn't *prove* it for the system the spec talks about.

This phase adds a focused test that constructs a real `HomeRailOrderStore` with a profile-switched mock `LayoutPreferenceDataStore` and asserts that profile A's persisted state does not surface to profile B's store reads.

### Task 3: Add `HomeRailOrderState` profile-scoping test

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/home/order/HomeRailKeyScopingTest.kt`

- [ ] **Step 1: Read the existing test file to confirm imports and helpers**

```bash
sed -n '1,40p' app/src/test/java/com/nexio/tv/ui/screens/home/order/HomeRailKeyScopingTest.kt
```

Note the package (`com.nexio.tv.ui.screens.home.order`), the existing imports, and any helper utilities. The new test will live in the same file but pivots to exercising the store directly.

- [ ] **Step 2: Append the new test method**

Add the following `@Test` method INSIDE `class HomeRailKeyScopingTest { ... }` before the closing `}`. Indent four spaces to match existing methods.

```kotlin
    @Test
    fun `HomeRailOrderState reads are scoped per profile via LayoutPreferenceDataStore`() = runTest {
        // GIVEN two profiles each with distinct persisted HomeRailOrderState backing.
        val profile1Json = MutableStateFlow<String?>(
            HomeRailOrderStateCodec(Gson()).encode(
                HomeRailOrderState.Empty.copy(orderedKeys = listOf(HomeRailKey("profile1_key")))
            )
        )
        val profile2Json = MutableStateFlow<String?>(
            HomeRailOrderStateCodec(Gson()).encode(
                HomeRailOrderState.Empty.copy(orderedKeys = listOf(HomeRailKey("profile2_key")))
            )
        )

        val activeProfileId = MutableStateFlow(1)
        val profileManager = mockk<com.nexio.tv.core.profile.ProfileManager>(relaxed = true)
        every { profileManager.activeProfileId } returns activeProfileId

        val layout = mockk<LayoutPreferenceDataStore>(relaxed = true)
        // The real LayoutPreferenceDataStore.homeRailOrderStateJson uses profileFlow,
        // which routes reads through the active profile id. Simulate that by switching
        // the returned flow based on the current profileId.
        coEvery { layout.homeRailOrderStateJson } answers {
            when (activeProfileId.value) {
                1 -> profile1Json
                else -> profile2Json
            }
        }
        coEvery { layout.homeCatalogOrderKeys } returns flowOf(emptyList())
        coEvery { layout.disabledHomeCatalogKeys } returns flowOf(emptyList())

        val testScope = TestScope(StandardTestDispatcher(testScheduler))
        val store = HomeRailOrderStore(
            layoutPreferenceDataStore = layout,
            codec = HomeRailOrderStateCodec(Gson()),
            clock = Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC),
            scope = testScope,
            profileManager = profileManager,
            diagnostics = mockk(relaxed = true),
        )

        // WHEN the store is observed under profile 1.
        advanceUntilIdle()
        val profile1State = store.state.first()

        // THEN we see profile 1's keys, not profile 2's.
        assertEquals(listOf(HomeRailKey("profile1_key")), profile1State.orderedKeys)

        // WHEN the active profile switches to profile 2.
        activeProfileId.value = 2
        advanceUntilIdle()

        // THEN the store sees profile 2's keys, never leaking profile 1's.
        // The store's caches are invalidated on profile switch (per the I3 fix-up),
        // so subsequent reads come from profile 2's persisted backing.
        // Note: store.state is built from a single homeRailOrderStateJson collection
        // started at construction, so we exercise this via tryMigrate / reconcileNow,
        // which read currentForMutation() (which awaits homeRailOrderStateJson.first()).
        store.tryMigrate(persistedSyntheticOrder = emptyList(), liveDefinitions = emptyList())
        advanceUntilIdle()

        // The migration helper's branch 1 (already-migrated) should observe profile 2's
        // persisted [profile2_key] state. If profile-scoping were broken, it would observe
        // profile 1's [profile1_key] from the cache and incorrectly short-circuit on that.
        // We can't observe migration directly from outside the store, so we make a
        // mutation that exposes which state was used as the merge base:
        store.updateOrder(
            orderedKeys = listOf(HomeRailKey("profile2_added")),
            source = RailOrderMutationSource.ANDROID_ORDER_SCREEN,
            knownLiveKeys = setOf(HomeRailKey("profile2_added")),
        )
        advanceUntilIdle()

        // The unknown-saved-key preservation rule means the merge base's keys (those
        // absent from the live set) get appended. If the cache leaked profile 1's
        // state, "profile1_key" would appear in the persisted result; if profile-scoping
        // worked, "profile2_key" appears instead.
        val captured = profile2Json.value!!
        val mergedState = HomeRailOrderStateCodec(Gson()).decode(captured)
        assertEquals(false, mergedState.orderedKeys.contains(HomeRailKey("profile1_key")))
        assertEquals(true, mergedState.orderedKeys.contains(HomeRailKey("profile2_key")))
        assertEquals(true, mergedState.orderedKeys.contains(HomeRailKey("profile2_added")))
    }
```

Add these imports if not already present in the test file:

```kotlin
import com.google.gson.Gson
import com.nexio.tv.data.local.LayoutPreferenceDataStore
import com.nexio.tv.ui.screens.home.order.HomeRailOrderState
import com.nexio.tv.ui.screens.home.order.HomeRailOrderStateCodec
import com.nexio.tv.ui.screens.home.order.HomeRailOrderStore
import com.nexio.tv.ui.screens.home.order.RailOrderMutationSource
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
```

Add `@OptIn(ExperimentalCoroutinesApi::class)` to the class declaration if not already present.

- [ ] **Step 3: Run the test to verify it passes**

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.ui.screens.home.order.HomeRailKeyScopingTest"
```

Expected: PASS. The test asserts behaviour that already holds (per the I3 fix-up); this commit ratchets it.

- [ ] **Step 4: Run the broader package suite**

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.ui.screens.home.order.*"
```

Expected: All PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/com/nexio/tv/ui/screens/home/order/HomeRailKeyScopingTest.kt
git commit -m "test(home-rail-order): assert HomeRailOrderState reads are profile-scoped"
```

---

## Phase 3 — I6: re-auth scenario test

The OpenSpec scenario "Re-authentication with a different provider account does not reuse previous account's rails" is currently uncovered. The behaviour depends on whether Trakt/MDBList user-list rail keys encode account scope. Today the codebase derives those keys from API responses (Trakt's list IDs are globally unique per Trakt account), so re-authentication produces a different listKey set — but no test pins this.

This phase adds a guard test against `RailKeyFactory.traktLibrary(profileId, listKey)`: same profile, different listKey strings (simulating different Trakt accounts) produce different `HomeRailKey` values. The test is documentary — if a future change drops the listKey parameter or normalises it across accounts, this catches it.

### Task 4: Add re-authentication guard test

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/home/order/HomeRailKeyScopingTest.kt`

- [ ] **Step 1: Append the new test method to the existing class**

Add inside `class HomeRailKeyScopingTest { ... }`:

```kotlin
    @Test
    fun `re-authentication with a different account produces distinct rail keys`() {
        // Trakt user-list keys are derived from per-account listKey strings (the API's
        // unique list identifier). Re-authenticating as a different Trakt account
        // surfaces a different listKey set, which produces different rail keys —
        // ensuring the new account's rails do not collide with the previous account's
        // entries in HomeRailOrderState or SyntheticHomeCatalogStore.
        val profileId = 1
        val accountAList = "trakt-account-A-list-42"
        val accountBList = "trakt-account-B-list-42"

        // Same profile, different list keys (one from each account).
        val keyForAccountA = RailKeyFactory.traktLibrary(profileId, accountAList)
        val keyForAccountB = RailKeyFactory.traktLibrary(profileId, accountBList)

        assertNotEquals(keyForAccountA, keyForAccountB)

        // Sanity check: the same profile + same listKey does produce the same key
        // (idempotent), which means re-running the same auth produces stable rails.
        val keyForAccountARepeat = RailKeyFactory.traktLibrary(profileId, accountAList)
        assertEquals(keyForAccountA, keyForAccountARepeat)
    }
```

If `RailKeyFactory.traktLibrary` is not the actual method name, `grep -n "fun.*[Tt]rakt" app/src/main/java/com/nexio/tv/core/integration/RailKeyFactory.kt` and use the actual signature. Existing tests in the same file (added in Plan 1's Phase 10) call `RailKeyFactory.traktLibrary(profileId, listKey)` — reuse the same call shape.

Add the import if not present:

```kotlin
import com.nexio.tv.core.integration.RailKeyFactory
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
```

- [ ] **Step 2: Run the test to verify it passes**

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.ui.screens.home.order.HomeRailKeyScopingTest"
```

Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/nexio/tv/ui/screens/home/order/HomeRailKeyScopingTest.kt
git commit -m "test(home-rail-order): assert re-authentication produces distinct rail keys"
```

---

## Phase 4 — M4: drop the misleading `rail_order_mutation` event from `setEnabled`

`setEnabled` emits two diagnostic events: `home.rail_enabled_changed` (correct, reports the toggle) and `home.rail_order_mutation` (with `before == after` for `orderedKeys`, since enable/disable doesn't reorder). The second event is misleading — analysing the event stream for "did the order change?" yields false positives.

Decision: drop the `rail_order_mutation` emission from `setEnabled`. The `rail_enabled_changed` event already carries the relevant information. `updateOrder` and `reorderProviderKeys` continue to emit `rail_order_mutation` (those genuinely change order).

### Task 5: Remove `rail_order_mutation` from `setEnabled`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/order/HomeRailOrderStore.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/home/order/HomeRailOrderStoreTest.kt`

- [ ] **Step 1: Read the current `setEnabled` body**

```bash
sed -n '/suspend fun setEnabled/,/^    }/p' app/src/main/java/com/nexio/tv/ui/screens/home/order/HomeRailOrderStore.kt
```

Locate the `diagnostics.emitMutation(...)` call inside `setEnabled`. It will be the line after `persist(...)`.

- [ ] **Step 2: Add a failing test asserting the cleanup**

Append inside `class HomeRailOrderStoreTest { ... }`:

```kotlin
    @Test
    fun `setEnabled does not emit rail_order_mutation event`() = runTest {
        val capturingSink = CapturingHomeRailOrderDiagnosticsSink()
        val layout = mockk<LayoutPreferenceDataStore>(relaxed = true)
        val persisted = MutableStateFlow<String?>(
            codec.encode(
                HomeRailOrderState.Empty.copy(orderedKeys = listOf(HomeRailKey("k")))
            )
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
            profileManager = stubProfileManager(profileId = 1),
            diagnostics = capturingSink,
        )

        store.setEnabled(
            key = HomeRailKey("k"),
            enabled = false,
            source = RailOrderMutationSource.PROVIDER_SETTINGS_SCREEN,
        )
        advanceUntilIdle()

        // Sanity: the enabled-changed event did fire.
        assertEquals(
            true,
            capturingSink.events.any { it.eventType == "home.rail_enabled_changed" },
        )
        // Goal: rail_order_mutation must NOT fire for setEnabled — the orderedKeys
        // field is unchanged, so emitting it would be misleading in the event stream.
        assertEquals(
            false,
            capturingSink.events.any { it.eventType == "home.rail_order_mutation" },
        )
    }
```

If `stubProfileManager(profileId)` doesn't already exist as a helper in this file, inline a `mockk<ProfileManager>(relaxed = true).also { coEvery { it.activeProfileId } returns MutableStateFlow(1) }` call instead. (Check the file: the I3 follow-up may have introduced this helper.)

- [ ] **Step 3: Run the test to verify it FAILS**

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.ui.screens.home.order.HomeRailOrderStoreTest.setEnabled does not emit rail_order_mutation event"
```

Expected: FAIL. The current `setEnabled` emits `rail_order_mutation` after persisting; the new test asserts it does NOT.

- [ ] **Step 4: Remove the `emitMutation` call from `setEnabled`**

In `app/src/main/java/com/nexio/tv/ui/screens/home/order/HomeRailOrderStore.kt`, find the `setEnabled` function body. Inside the `mutationLock.withLock { ... }` block, after `persist(...)`, there is a line like:

```kotlin
diagnostics.emitMutation(source = source, before = before, after = before)
```

Delete that line. Add a one-line comment in its place explaining why:

```kotlin
// Intentionally no rail_order_mutation emission here — orderedKeys did not change;
// the rail_enabled_changed event below carries the actual semantic.
```

The `diagnostics.emitEnabledChanged(...)` call elsewhere in the same method body must remain.

- [ ] **Step 5: Run the test to verify it now PASSES**

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.ui.screens.home.order.HomeRailOrderStoreTest"
```

Expected: All PASS, including the new test.

- [ ] **Step 6: Run the full home/order suite to confirm no other test was relying on the dropped event**

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.ui.screens.home.order.*"
```

Expected: All PASS. If a different test (e.g., `HomeRailOrderDiagnosticsTest`) was asserting the existence of `rail_order_mutation` after a `setEnabled` call, update its assertion to match the new behaviour and document the change in the commit message.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/order/HomeRailOrderStore.kt \
        app/src/test/java/com/nexio/tv/ui/screens/home/order/HomeRailOrderStoreTest.kt
git commit -m "fix(home-rail-order): drop misleading rail_order_mutation event from setEnabled"
```

---

## Phase 5 — M5: enforce `publishPolicy` inside the materializer

Today the materializer publishes any row found in `pendingRowsByKey`, regardless of the rail's `publishPolicy`. Policy enforcement is *implicit*: the upstream pipeline is supposed to filter `pendingRowsByKey` before passing it in, leaving only `PUBLISH_ON_FIRST_PAINT`-policy rails. This works today but couples correctness to the upstream filter — a future refactor that moves rendering elsewhere could silently drop the policy.

Decision: explicitly pass `liveDefinitions` (or a precomputed `Map<HomeRailKey, RailPublishPolicy>`) into `materializeHomeRows`, and gate the pending-row branch on policy. This makes the contract self-contained.

### Task 6: Thread policy lookup into `materializeHomeRows`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeRowMaterializer.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeRowMaterializerTest.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt` (the call site)

- [ ] **Step 1: Add a failing test for policy enforcement**

Append inside `class HomeRowMaterializerTest { ... }`:

```kotlin
    @Test
    fun `pending row is published only when publishPolicy is PUBLISH_ON_FIRST_PAINT`() {
        val keyAlways = HomeRailKey("key_always")
        val keyOnFirstPaint = HomeRailKey("key_first_paint")
        val keyWhenNonEmpty = HomeRailKey("key_when_non_empty")

        val pendingRow = mockk<CatalogRow>(relaxed = true)
        val effective = EffectiveHomeRailOrder.Empty.copy(
            visibleKeys = listOf(keyAlways, keyOnFirstPaint, keyWhenNonEmpty),
        )
        val pending = mapOf(
            keyAlways to pendingRow,
            keyOnFirstPaint to pendingRow,
            keyWhenNonEmpty to pendingRow,
        )
        val policyByKey = mapOf(
            keyAlways to RailPublishPolicy.PUBLISH_ALWAYS,
            keyOnFirstPaint to RailPublishPolicy.PUBLISH_ON_FIRST_PAINT,
            keyWhenNonEmpty to RailPublishPolicy.PUBLISH_WHEN_NON_EMPTY,
        )

        val result = materializeHomeRows(
            effectiveOrder = effective,
            liveSyntheticGroupsByKey = emptyMap(),
            persistedSyntheticGroupsByKey = emptyMap(),
            rawRowsByKey = emptyMap(),
            pendingRowsByKey = pending,
            publishPolicyByKey = policyByKey,
        )

        // Only the PUBLISH_ON_FIRST_PAINT rail produces a pending row;
        // the other two are skipped (policy doesn't permit a placeholder).
        assertEquals(listOf(pendingRow), result)
    }
```

Update existing tests in the same file to pass an empty `publishPolicyByKey = emptyMap()` argument (which under the new rule means "no policy known, fall back to the previous behaviour" — see Step 3 for the implementation).

- [ ] **Step 2: Run the test to verify it FAILS**

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.ui.screens.home.HomeRowMaterializerTest"
```

Expected: FAIL — the new parameter doesn't exist.

- [ ] **Step 3: Add the `publishPolicyByKey` parameter to `materializeHomeRows`**

In `app/src/main/java/com/nexio/tv/ui/screens/home/HomeRowMaterializer.kt`, modify the function:

```kotlin
internal fun materializeHomeRows(
    effectiveOrder: EffectiveHomeRailOrder,
    liveSyntheticGroupsByKey: Map<HomeRailKey, List<CatalogRow>>,
    persistedSyntheticGroupsByKey: Map<HomeRailKey, List<CatalogRow>>,
    rawRowsByKey: Map<HomeRailKey, CatalogRow>,
    pendingRowsByKey: Map<HomeRailKey, CatalogRow>,
    publishPolicyByKey: Map<HomeRailKey, RailPublishPolicy> = emptyMap(),
): List<CatalogRow> = buildList {
    effectiveOrder.visibleKeys.forEach { key ->
        liveSyntheticGroupsByKey[key]?.let { rows -> addAll(rows); return@forEach }
        rawRowsByKey[key]?.let { row -> add(row); return@forEach }
        persistedSyntheticGroupsByKey[key]?.let { rows -> addAll(rows); return@forEach }
        pendingRowsByKey[key]?.let { row ->
            // Only publish the pending placeholder when the rail's publish policy
            // explicitly allows first-paint placeholders. If the caller didn't supply
            // a policy map (`emptyMap()`), fall back to the legacy behaviour of
            // unconditionally publishing — this preserves the upstream contract that
            // the caller filters pendingRowsByKey before passing it in.
            val policy = publishPolicyByKey[key]
            val allowedByPolicy = policy == null || policy == RailPublishPolicy.PUBLISH_ON_FIRST_PAINT
            if (allowedByPolicy) add(row)
        }
    }
}
```

Add the import if not present: `import com.nexio.tv.ui.screens.home.order.RailPublishPolicy`.

- [ ] **Step 4: Run the test to verify it PASSES**

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.ui.screens.home.HomeRowMaterializerTest"
```

Expected: All PASS.

- [ ] **Step 5: Pass `publishPolicyByKey` from the pipeline call site**

In `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt`, find the `materializeHomeRows(...)` call (added in Plan 1 Task 14c). After the existing `liveDefinitions = catalogPlan.toHomeRailDefinitions()` line, add:

```kotlin
val publishPolicyByKey: Map<HomeRailKey, RailPublishPolicy> =
    liveDefinitions.associate { it.key to it.publishPolicy }
```

Then in the `materializeHomeRows(...)` call, add the new argument:

```kotlin
val combinedRows = materializeHomeRows(
    effectiveOrder = effectiveOrder,
    liveSyntheticGroupsByKey = liveSyntheticByKey,
    persistedSyntheticGroupsByKey = persistedSyntheticByKey,
    rawRowsByKey = rawRowsByRailKey,
    pendingRowsByKey = pendingRowsByRailKey,
    publishPolicyByKey = publishPolicyByKey,
)
```

Add the import if not present: `import com.nexio.tv.ui.screens.home.order.RailPublishPolicy`.

- [ ] **Step 6: Run home and home/order tests**

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.ui.screens.home.*" --tests "com.nexio.tv.ui.screens.home.order.*"
```

Expected: All PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeRowMaterializer.kt \
        app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt \
        app/src/test/java/com/nexio/tv/ui/screens/home/HomeRowMaterializerTest.kt
git commit -m "refactor(home-rail-order): enforce publishPolicy inside materializeHomeRows"
```

---

## Phase 6 — M6: thread real `enabled` value through `ConfiguredHomeCatalogDescriptor`

Today `toHomeRailDefinitions` hardcodes `enabled = true` because `ConfiguredHomeCatalogDescriptor` has no `enabled` field — `CatalogPlan` only surfaces enabled rails (filtered upstream by `isSyntheticHomeCatalogDisabled` and addon-disable checks). This works today but if `CatalogPlan` ever surfaces disabled rails for diagnostic purposes, the reconciler would treat them as enabled and render them.

Decision: add an `enabled: Boolean = true` field to `ConfiguredHomeCatalogDescriptor`, default to `true` so existing call sites compile unchanged, and read it in `toHomeRailDefinitions`. Existing upstream code that filters out disabled rails continues to do so before constructing the descriptor — the new field is a safety net, not a behavioural change today.

### Task 7: Add `enabled` field to `ConfiguredHomeCatalogDescriptor` and propagate

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogUtils.kt` (the `ConfiguredHomeCatalogDescriptor` data class)
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/order/HomeRailDefinitions.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/home/order/HomeRailDefinitionsTest.kt`

- [ ] **Step 1: Read the current `ConfiguredHomeCatalogDescriptor` declaration**

```bash
grep -n "data class ConfiguredHomeCatalogDescriptor" app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogUtils.kt
sed -n '/data class ConfiguredHomeCatalogDescriptor/,/^)$/p' app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogUtils.kt
```

Confirm the existing field list. Per earlier reconnaissance: `orderKey, addonId, addonName, addonBaseUrl, catalogId, catalogName, type, rawType`.

- [ ] **Step 2: Add a failing test asserting the new field flows through**

In `app/src/test/java/com/nexio/tv/ui/screens/home/order/HomeRailDefinitionsTest.kt`, add inside the existing class:

```kotlin
    @Test
    fun `toHomeRailDefinitions reflects descriptor enabled flag`() {
        // Build two ConfiguredHomeCatalogDescriptor instances — one enabled, one disabled —
        // and confirm toHomeRailDefinitions threads the value through to HomeRailDefinition.
        val enabledDescriptor = ConfiguredHomeCatalogDescriptor(
            orderKey = "tmdb_popular_movies",
            addonId = "tmdb",
            addonName = "TMDB",
            addonBaseUrl = "https://api.themoviedb.org",
            catalogId = "popular",
            catalogName = "Popular Movies",
            type = ContentType.MOVIE,
            enabled = true,
        )
        val disabledDescriptor = ConfiguredHomeCatalogDescriptor(
            orderKey = "tmdb_top_rated_movies",
            addonId = "tmdb",
            addonName = "TMDB",
            addonBaseUrl = "https://api.themoviedb.org",
            catalogId = "top_rated",
            catalogName = "Top Rated Movies",
            type = ContentType.MOVIE,
            enabled = false,
        )

        val plan = CatalogPlan(
            expectedOrderKeys = listOf("tmdb_popular_movies", "tmdb_top_rated_movies"),
            publishableOrderKeys = listOf("tmdb_popular_movies", "tmdb_top_rated_movies"),
            descriptors = listOf(enabledDescriptor, disabledDescriptor),
            rails = emptyList(),
        )

        val defs = plan.toHomeRailDefinitions()
        assertEquals(2, defs.size)
        assertEquals(true, defs[0].enabled)
        assertEquals(false, defs[1].enabled)
    }
```

Add the imports for `ConfiguredHomeCatalogDescriptor`, `CatalogPlan`, `ContentType`. If `CatalogPlan` is `internal data class` (per earlier reconnaissance), the test in the same module can construct it.

- [ ] **Step 3: Run the test to verify it FAILS**

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.ui.screens.home.order.HomeRailDefinitionsTest"
```

Expected: FAIL — `ConfiguredHomeCatalogDescriptor` doesn't have an `enabled` field.

- [ ] **Step 4: Add the `enabled` field with `default = true`**

In `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogUtils.kt`, modify `ConfiguredHomeCatalogDescriptor` to add `val enabled: Boolean = true,` as the new last constructor parameter (default `true` keeps existing call sites working unchanged):

```kotlin
internal data class ConfiguredHomeCatalogDescriptor(
    val orderKey: String,
    val addonId: String,
    val addonName: String,
    val addonBaseUrl: String,
    val catalogId: String,
    val catalogName: String,
    val type: ContentType,
    val rawType: String = type.toApiString(),
    val enabled: Boolean = true,
)
```

- [ ] **Step 5: Read the new value in `toHomeRailDefinitions`**

In `app/src/main/java/com/nexio/tv/ui/screens/home/order/HomeRailDefinitions.kt`, replace the hardcoded `enabled = true` in the `HomeRailDefinition(...)` construction with `enabled = descriptor.enabled`. Remove the inline comment explaining why it was hardcoded — the comment is no longer applicable:

```kotlin
internal fun CatalogPlan.toHomeRailDefinitions(): List<HomeRailDefinition> {
    val perFamilyIndex = mutableMapOf<RailFamily, Int>()
    return descriptors.map { descriptor ->
        val family = RailFamily.fromOrderKey(descriptor.orderKey)
        val intra = perFamilyIndex.getOrDefault(family, 0)
        perFamilyIndex[family] = intra + 1
        HomeRailDefinition(
            key = HomeRailKey(descriptor.orderKey),
            family = family,
            source = inferSource(family),
            title = descriptor.catalogName,
            enabled = descriptor.enabled,
            defaultSortKey = DefaultSortKey(family.familyRank, intra),
            publishPolicy = RailPublishPolicy.PUBLISH_WHEN_NON_EMPTY,
        )
    }
}
```

- [ ] **Step 6: Run the test to verify it PASSES**

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.ui.screens.home.order.HomeRailDefinitionsTest"
```

Expected: All PASS.

- [ ] **Step 7: Run the full home and home/order suites to catch any compile breakage in call sites**

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.ui.screens.home.*" --tests "com.nexio.tv.ui.screens.home.order.*"
```

Expected: All PASS. The `default = true` on the new field means existing call sites that build `ConfiguredHomeCatalogDescriptor` without the new argument continue to work and produce `enabled = true` — preserving today's behaviour.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogUtils.kt \
        app/src/main/java/com/nexio/tv/ui/screens/home/order/HomeRailDefinitions.kt \
        app/src/test/java/com/nexio/tv/ui/screens/home/order/HomeRailDefinitionsTest.kt
git commit -m "refactor(home-rail-order): thread descriptor enabled flag through toHomeRailDefinitions"
```

---

## Phase 7 — M1: clean up dead-effect synthetic group concatenation

The pre-rewrite code `persistedSyntheticGroups + liveSyntheticGroups.filterNot { duplicate }` is still computed in the pipeline (around `HomeViewModelCatalogPipeline.kt:2411–2422`) and used to populate `existingRowsByOrderKey`, `syntheticRowsByKey`, and `rowOrderKeyByGlobalKey` — three downstream maps used for metadata-merging. The result no longer drives row order (Task 14c moved that to `effectiveOrder`-driven materialization), but the code reads as if both systems are competing.

Decision: redirect the three downstream maps to read from the per-key materialization path. The `persistedSyntheticGroups` and `liveSyntheticGroups` collections are still needed (they're inputs to the by-key maps), but the *concatenation* and *duplicate-drop* are removed. This is the largest task in this plan; it lands last so any earlier instability doesn't tangle with this diff.

### Task 8: Refactor pipeline to remove the dead concatenation

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt`

**Strategy.** This is a refactor with no behavioural change. Plan: (a) read the existing block carefully to identify exactly which downstream consumers depend on the concatenation, (b) replace those consumers with reads from the by-key maps that already exist (or build new by-key maps where needed), (c) delete the concatenation, (d) run the full home/home-order test suite to catch any behavioural drift.

- [ ] **Step 1: Locate the concatenation and its three consumers**

```bash
grep -n "persistedSyntheticGroups\b\|syntheticGroups\b\|existingRowsByOrderKey\|syntheticRowsByKey\|rowOrderKeyByGlobalKey" \
    app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt | head -40
```

Document the line ranges where each name is read. The concatenation (`val syntheticGroups = persistedSyntheticGroups + liveSyntheticGroups.filterNot { ... }`) is the source. Each downstream consumer uses `syntheticGroups` (or a derivative) to build a map keyed by `orderKey`.

- [ ] **Step 2: Add a regression test that pins current behaviour**

This refactor is behaviour-preserving by intent. To prove it, add a snapshot test that builds a `CatalogPlan` and asserts the resulting Modern Home rows are unchanged after the refactor. The test goes against the same harness used by `HomeReactsToSyncReorderTest.kt` and similar — a focused integration test that constructs `HomeRailOrderStore` and exercises a representative slice of the pipeline.

If full pipeline construction is too heavyweight (per the earlier dispatches' findings), defer the regression to the on-device smoke and rely on the existing focused tests (`HomeRowMaterializerTest`, `HomeRailOrderStoreTest`, `PartialSyncSafetyTest`, `HomeReactsToSyncReorderTest`) to catch behavioural drift.

For a pure-function-style regression, factor `existingRowsByOrderKey` / `syntheticRowsByKey` / `rowOrderKeyByGlobalKey` construction out into a helper and test the helper in isolation:

Create `app/src/main/java/com/nexio/tv/ui/screens/home/SyntheticGroupContentMaps.kt`:

```kotlin
package com.nexio.tv.ui.screens.home

import com.nexio.tv.domain.model.CatalogRow

/**
 * Pure-function builder for the three by-orderKey maps the pipeline needs alongside
 * `materializeHomeRows`. Replaces the pre-rewrite "concatenate persisted + live, drop
 * duplicates, derive default order from synthetic group iteration" code with a
 * by-key construction whose iteration order does not influence Modern Home row order.
 */
internal data class SyntheticGroupContentMaps(
    val syntheticRowsByKey: Map<String, List<CatalogRow>>,
    val existingRowsByOrderKey: Map<String, CatalogRow>,
    val rowOrderKeyByGlobalKey: Map<String, String>,
)

internal fun buildSyntheticGroupContentMaps(
    persistedSyntheticGroupsByKey: Map<String, List<CatalogRow>>,
    liveSyntheticGroupsByKey: Map<String, List<CatalogRow>>,
    rawRowsByOrderKey: Map<String, CatalogRow>,
    homeCatalogGlobalKey: (CatalogRow) -> String,
): SyntheticGroupContentMaps {
    // Live synthetic content takes precedence over persisted synthetic content,
    // matching the materializer's priority — the by-key merge is "live wins, persisted fills".
    val syntheticRowsByKey: Map<String, List<CatalogRow>> = buildMap {
        putAll(persistedSyntheticGroupsByKey)
        putAll(liveSyntheticGroupsByKey) // overwrites persisted entries for the same orderKey
    }
    val existingRowsByOrderKey: Map<String, CatalogRow> = buildMap {
        putAll(rawRowsByOrderKey)
        syntheticRowsByKey.forEach { (orderKey, rows) ->
            rows.firstOrNull()?.let { put(orderKey, it) }
        }
    }
    val rowOrderKeyByGlobalKey: Map<String, String> = buildMap {
        rawRowsByOrderKey.keys.forEach { globalKey -> put(globalKey, globalKey) }
        syntheticRowsByKey.forEach { (orderKey, rows) ->
            rows.forEach { row -> put(homeCatalogGlobalKey(row), orderKey) }
        }
    }
    return SyntheticGroupContentMaps(
        syntheticRowsByKey = syntheticRowsByKey,
        existingRowsByOrderKey = existingRowsByOrderKey,
        rowOrderKeyByGlobalKey = rowOrderKeyByGlobalKey,
    )
}
```

Create `app/src/test/java/com/nexio/tv/ui/screens/home/SyntheticGroupContentMapsTest.kt`:

```kotlin
package com.nexio.tv.ui.screens.home

import com.nexio.tv.domain.model.CatalogRow
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class SyntheticGroupContentMapsTest {
    private fun row(globalKey: String): CatalogRow = mockk(relaxed = true) {
        every { hashCode() } returns globalKey.hashCode()
    }

    @Test
    fun `live synthetic content wins over persisted synthetic content for same orderKey`() {
        val keyA = "tmdb_popular_movies"
        val liveRowA = row("live-a")
        val persistedRowA = row("persisted-a")

        val maps = buildSyntheticGroupContentMaps(
            persistedSyntheticGroupsByKey = mapOf(keyA to listOf(persistedRowA)),
            liveSyntheticGroupsByKey = mapOf(keyA to listOf(liveRowA)),
            rawRowsByOrderKey = emptyMap(),
            homeCatalogGlobalKey = { "global-${it.hashCode()}" },
        )

        // Live wins.
        assertEquals(listOf(liveRowA), maps.syntheticRowsByKey[keyA])
        // existingRowsByOrderKey gets the first live row (since live wins in syntheticRowsByKey).
        assertEquals(liveRowA, maps.existingRowsByOrderKey[keyA])
    }

    @Test
    fun `raw rows win over synthetic content in existingRowsByOrderKey only when key is exclusively raw`() {
        val keyA = "tmdb_popular_movies"
        val keyB = "raw_only_key"
        val rawRowB = row("raw-b")
        val liveRowA = row("live-a")

        val maps = buildSyntheticGroupContentMaps(
            persistedSyntheticGroupsByKey = emptyMap(),
            liveSyntheticGroupsByKey = mapOf(keyA to listOf(liveRowA)),
            rawRowsByOrderKey = mapOf(keyB to rawRowB),
            homeCatalogGlobalKey = { "global-${it.hashCode()}" },
        )

        assertEquals(rawRowB, maps.existingRowsByOrderKey[keyB])
        assertEquals(liveRowA, maps.existingRowsByOrderKey[keyA])
    }

    @Test
    fun `rowOrderKeyByGlobalKey maps each synthetic row's global key to its orderKey`() {
        val keyA = "tmdb_popular_movies"
        val row1 = row("g1")
        val row2 = row("g2")

        val maps = buildSyntheticGroupContentMaps(
            persistedSyntheticGroupsByKey = emptyMap(),
            liveSyntheticGroupsByKey = mapOf(keyA to listOf(row1, row2)),
            rawRowsByOrderKey = emptyMap(),
            homeCatalogGlobalKey = { "g-${it.hashCode()}" },
        )

        assertEquals(keyA, maps.rowOrderKeyByGlobalKey["g-${row1.hashCode()}"])
        assertEquals(keyA, maps.rowOrderKeyByGlobalKey["g-${row2.hashCode()}"])
    }
}
```

- [ ] **Step 3: Run the new tests; they must PASS against the new helper**

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.ui.screens.home.SyntheticGroupContentMapsTest"
```

Expected: 3/3 PASS.

- [ ] **Step 4: Replace the inline concatenation with a call to the new helper**

In `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt`:

(a) Locate the existing block (per Step 1's grep). It builds `persistedSyntheticGroups`, `liveSyntheticGroups`, `syntheticGroups`, `syntheticRowsByKey`, `existingRowsByOrderKey`, and `rowOrderKeyByGlobalKey`.

(b) Replace the `syntheticGroups` concatenation and the three downstream map constructions with calls to `buildSyntheticGroupContentMaps`. Build the inputs:

```kotlin
val persistedSyntheticGroupsByKey: Map<String, List<CatalogRow>> = (
    (if (activeProfileTraktAuthenticated) syntheticTraktGroups else emptyList()) +
        syntheticSimklGroups + syntheticMDBListGroups +
        syntheticKitsuGroups + syntheticTmdbGroups
).associate { it.orderKey to it.rows }

val liveSyntheticGroupsByKey: Map<String, List<CatalogRow>> =
    liveSyntheticGroups.associate { it.orderKey to it.rows }

val syntheticContent = buildSyntheticGroupContentMaps(
    persistedSyntheticGroupsByKey = persistedSyntheticGroupsByKey,
    liveSyntheticGroupsByKey = liveSyntheticGroupsByKey,
    rawRowsByOrderKey = rawRowsByKey,
    homeCatalogGlobalKey = ::homeCatalogGlobalKey,
)
val syntheticRowsByKey = syntheticContent.syntheticRowsByKey
val existingRowsByOrderKey = syntheticContent.existingRowsByOrderKey
val rowOrderKeyByGlobalKey = syntheticContent.rowOrderKeyByGlobalKey
```

(c) Delete the original `val persistedSyntheticGroups = ... ; val syntheticGroups = persistedSyntheticGroups + liveSyntheticGroups.filterNot { ... }` block and the inline assembly of the three downstream maps. Note: `homeCatalogGlobalKey` is an existing function in the same file; the helper passes it as a function reference.

(d) The downstream `liveSyntheticByKey` / `persistedSyntheticByKey` / `rawRowsByRailKey` / `pendingRowsByRailKey` maps that feed `materializeHomeRows` (added in Plan 1 Task 14c) should NOT be touched — they already follow the new pattern.

- [ ] **Step 5: Run the focused tests**

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.ui.screens.home.SyntheticGroupContentMapsTest" \
    --tests "com.nexio.tv.ui.screens.home.HomeRowMaterializerTest" \
    --tests "com.nexio.tv.ui.screens.home.HomeReactsToSyncReorderTest" \
    --tests "com.nexio.tv.ui.screens.home.order.*"
```

Expected: All PASS.

- [ ] **Step 6: Run the full home + home/order + sync test suites to catch behavioural drift**

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.ui.screens.home.*" \
    --tests "com.nexio.tv.ui.screens.home.order.*" \
    --tests "com.nexio.tv.core.sync.*"
```

Expected: All PASS. If any test fails, the refactor changed behaviour somewhere — investigate and adjust the helper or the call site.

- [ ] **Step 7: Build the APK**

```bash
./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt \
        app/src/main/java/com/nexio/tv/ui/screens/home/SyntheticGroupContentMaps.kt \
        app/src/test/java/com/nexio/tv/ui/screens/home/SyntheticGroupContentMapsTest.kt
git commit -m "refactor(home-rail-order): replace dead-effect synthetic concatenation with by-key helper"
```

---

## Phase 8 — Final verification

The seven items from the final review are now closed (or, for M2, explicitly deferred). Run the full validation pass to confirm nothing regressed.

### Task 9: Strict validation, full focused suites, APK build

- [ ] **Step 1: OpenSpec strict validation on both changes**

```bash
openspec validate make-modern-home-rail-order-authoritative-and-reactive --strict
openspec validate extend-account-sync-with-tmdb-kitsu-catalogs --strict
```

Expected: both report `Change '<...>' is valid`.

- [ ] **Step 2: Run all home + home/order + sync + supabase tests**

```bash
./gradlew testDebugUnitTest --tests "com.nexio.tv.ui.screens.home.*" \
    --tests "com.nexio.tv.ui.screens.home.order.*" \
    --tests "com.nexio.tv.core.sync.*" \
    --tests "com.nexio.tv.data.remote.supabase.*" \
    --tests "com.nexio.tv.ui.screens.addon.*" \
    --tests "com.nexio.tv.ui.screens.settings.*"
```

Expected: All PASS. Document the per-class counts.

- [ ] **Step 3: Build the APK**

```bash
./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Append a verification log entry to both OpenSpec tasks.md files**

Append to `openspec/changes/make-modern-home-rail-order-authoritative-and-reactive/tasks.md` under the existing verification log:

```
- 2026-05-05 (follow-up): final-review remediations landed (M7, I5, I6, M4, M5, M6, M1) — see commits in the follow-up plan series. Strict validation, focused suites, and APK build all PASS.
```

Append to `openspec/changes/extend-account-sync-with-tmdb-kitsu-catalogs/tasks.md` under the existing verification log:

```
- 2026-05-05 (follow-up): final-review remediations landed for spec key-format consistency (M7) and shared subsystem improvements. Strict validation passes; no sync-extension-specific changes required.
```

- [ ] **Step 5: Done**

The work is feature-complete. M2 (deprecate legacy DataStore setters) remains explicitly deferred until 2-3 release cycles after this lands — at that point a separate small change can land to add `@Deprecated(message = "use HomeRailOrderStore.updateOrder", level = ERROR)` and remove the migration-window readers.

No commit needed for the verification log changes — `openspec/` is gitignored.

---

## Self-Review

Spec coverage map (final-review item → tasks):

| Item | Phase | Tasks |
|---|---|---|
| M7 — colon-delimited keys in spec | 1 | 1, 2 |
| I5 — `HomeRailOrderState` profile-scoping | 2 | 3 |
| I6 — re-auth scenario | 3 | 4 |
| M4 — `setEnabled` mutation event | 4 | 5 |
| M5 — materializer `publishPolicy` | 5 | 6 |
| M6 — descriptor `enabled` passthrough | 6 | 7 |
| M1 — synthetic concatenation cleanup | 7 | 8 |
| Verification | 8 | 9 |
| M2 — deprecate legacy setters | (deferred) | — |

No placeholders. Type names cross-checked: `HomeRailOrderStore`, `HomeRailOrderState`, `HomeRailOrderStateCodec`, `HomeRailKey`, `RailFamily`, `RailPublishPolicy`, `RailOrderMutationSource`, `EffectiveHomeRailOrder`, `HomeRailDefinition`, `ConfiguredHomeCatalogDescriptor`, `CatalogPlan`, `CatalogRow`, `RailKeyFactory`, `LayoutPreferenceDataStore`, `LoggingHomeRailOrderDiagnosticsSink`, `CapturingHomeRailOrderDiagnosticsSink` — all consistent across tasks.

Each task structured TDD: failing test → run-and-confirm-fail → minimal implementation → run-and-confirm-pass → broader regression suite → commit. Commit messages match the recent repo pattern.
