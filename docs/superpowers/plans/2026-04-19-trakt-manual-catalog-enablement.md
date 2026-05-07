# Trakt Manual Catalog Enablement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop Trakt from auto-enabling default catalog rows after connection, while allowing user-created Trakt lists to be manually selected as Modern Home catalog feeds from Android or web.

**Architecture:** Keep default/profile Trakt separation unchanged: profile 1 continues through account/default settings and account auth, while profiles 2-4 continue through `ProfileBoundary`, profile auth tokens, and profile settings blobs. Reuse the existing Trakt catalog preference persistence (`catalogEnabledSet`, `catalogOrder`, `selectedPopularListKeys`) and broaden the selected-list source from public/popular lists to all manually selected Trakt list options, including `me/<list_id>` personal lists.

**Tech Stack:** Android Kotlin, Jetpack DataStore, coroutines/Flow, Retrofit/Moshi, Compose; Nuxt/Vue/TypeScript portal; Supabase settings sync; Trakt API blueprint in `trakt.apib`.

---

## Root Cause Summary

Android currently auto-enables Trakt rows because `TraktCatalogIds.DEFAULT_ENABLED` contains `trakt_up_next`, `trakt_recommended_movies`, `trakt_recommended_shows`, and `trakt_calendar_next_7_days`, and `TraktSettingsDataStore.catalogPreferences` falls back to that set when no persisted preference exists.

Web currently mirrors the same behavior in `nexio-web/utils/portal-defaults.ts` and legacy `nexio-web/composables/useSettings.ts`, where default Trakt settings include the same enabled set.

Custom Trakt list rows already exist as `TraktCustomListCatalog` and Home only publishes them when their key is in `selectedPopularListKeys`. The missing part is discovery/configuration of the authenticated user's own `/users/me/lists`; current Android and web discovery only expose public/popular/search list options.

The checked-in Trakt blueprint confirms:
- `GET /users/{id}/lists` returns all personal lists for a user.
- `GET /users/{id}/lists/{list_id}/items/{type}` returns items for a personal list.
- `GET /sync/last_activities` includes `lists.updated_at`, which should be part of the discovery freshness fingerprint.

## File Map

- Modify: `app/src/main/java/com/nexio/tv/data/local/TraktSettingsDataStore.kt`
  - Make new Android Trakt catalog preferences start with no enabled built-in catalogs.
  - Keep built-in order available for UI display.

- Modify: `app/src/main/java/com/nexio/tv/data/remote/dto/trakt/TraktSyncDtos.kt`
  - Add `lists.updated_at` parsing for Trakt last-activity freshness.

- Modify: `app/src/main/java/com/nexio/tv/data/repository/TraktDiscoveryService.kt`
  - Fetch authenticated user's personal list summaries.
  - Merge personal list options with public/popular list options for configuration.
  - Fetch selected personal list catalog rows using `/users/me/lists/{list_id}/items/{type}`.
  - Include list activity in refresh fingerprint.

- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/TraktViewModel.kt`
  - Keep manual enablement notification behavior.
  - Prefer a forced list-options refresh when the catalog management dialog opens.

- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/TraktScreen.kt`
  - Show personal Trakt lists in the catalog dialog and allow manual selection.

- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/TraktSettingsContent.kt`
  - Apply the same catalog dialog behavior used by the alternate Trakt settings content path.

- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/CatalogListSearchSupport.kt`
  - Keep search/filter working across personal and public Trakt list options.

- Modify: `app/src/main/res/values/strings.xml`
  - Update Trakt list section labels from public/popular-only wording to wording that includes personal lists.

- Modify localized string files only for changed string keys if this repo requires compile-time completeness:
  - `app/src/main/res/values-de/strings.xml`
  - `app/src/main/res/values-es/strings.xml`
  - `app/src/main/res/values-fr/strings.xml`
  - `app/src/main/res/values-nl/strings.xml`
  - `app/src/main/res/values-zh-rCN/strings.xml`

- Modify: `nexio-web/types/portal.ts`
  - Add an optional/source field to Trakt list options while preserving existing persisted keys.

- Modify: `nexio-web/utils/portal-defaults.ts`
  - Make default Trakt `catalogEnabledSet` empty.

- Modify: `nexio-web/composables/useSettings.ts`
  - Make legacy/local default Trakt `catalogEnabledSet` empty.

- Modify: `nexio-web/composables/usePortalStore.ts`
  - Treat account Trakt discovery as personal plus community list options.
  - Render selected personal list options as Trakt catalog inventory rows.

- Modify: `nexio-web/composables/useProfileStore.ts`
  - Treat profile Trakt discovery as personal plus community list options without crossing into account auth.

- Modify: `nexio-web/components/portal/SettingsWorkspace.vue`
  - Label and render personal list options separately from community/search results.

- Add: `nexio-web/server/utils/trakt-list-options.ts`
  - Centralize Trakt list option mapping, key creation, slugification, and de-duplication for account and profile routes.

- Modify: `nexio-web/server/api/integrations/trakt/popular-lists.post.ts`
  - Fetch account/default-profile personal lists with account token, then public popular lists.

- Modify: `nexio-web/server/api/integrations/profiles/trakt/popular-lists.post.ts`
  - Fetch secondary-profile personal lists with profile token, then public popular lists.

- Modify: `nexio-web/server/api/integrations/trakt/search-lists.post.ts`
  - Use shared list option mapping for public search results.

- Modify: `nexio-web/server/api/integrations/profiles/trakt/search-lists.post.ts`
  - Use shared list option mapping for profile public search results.

- Modify tests:
  - `app/src/test/java/com/nexio/tv/data/local/TraktSettingsDataStoreProfileTest.kt`
  - `app/src/test/java/com/nexio/tv/ui/screens/settings/TraktViewModelPriorityHydrationTest.kt`
  - `app/src/test/java/com/nexio/tv/ui/screens/settings/CatalogListSearchFilterTest.kt`
  - `app/src/test/java/com/nexio/tv/ui/screens/home/CatalogPlanTest.kt`
  - `app/src/test/java/com/nexio/tv/ui/screens/home/HomeCatalogStartupReadinessTest.kt`
  - `nexio-web/tests/portal-contract-v4.test.ts`
  - `nexio-web/tests/profile-settings-blob.test.ts`
  - Add `nexio-web/tests/trakt-list-options.test.ts`

## Task 1: Lock The No-Default-Enabled Contract With Tests

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/data/local/TraktSettingsDataStoreProfileTest.kt`
- Modify: `nexio-web/tests/portal-contract-v4.test.ts`
- Modify: `nexio-web/tests/profile-settings-blob.test.ts`

- [ ] **Step 1: Update Android failing tests for empty Trakt defaults**

In `TraktSettingsDataStoreProfileTest.kt`, change the profile isolation test so it expects empty enabled sets by default and proves manual enablement remains profile-scoped:

```kotlin
@Test
fun `catalogPreferences default to no enabled catalogs and stay isolated per profile`() = runTest {
    val manager = makeManager()
    val settingsStore = makeSettingsStore(manager)

    val p1Defaults = settingsStore.catalogPreferences.first()
    assertTrue("Profile 1 should start with no enabled Trakt catalogs", p1Defaults.enabledCatalogs.isEmpty())
    assertTrue("Built-in Trakt order should still be available for UI", p1Defaults.catalogOrder.contains(TraktCatalogIds.UP_NEXT))

    settingsStore.setCatalogEnabled(TraktCatalogIds.UP_NEXT, true)
    val p1Prefs = settingsStore.catalogPreferences.first { TraktCatalogIds.UP_NEXT in it.enabledCatalogs }
    assertTrue("Profile 1 should persist manual UP_NEXT enablement", TraktCatalogIds.UP_NEXT in p1Prefs.enabledCatalogs)

    manager.createProfile("Bob", "#8E24AA")
    val bobId = manager.profiles.first { it.size >= 2 }.first { it.id != 1 }.id
    manager.setActiveProfile(bobId)
    manager.activeProfileId.first { it == bobId }

    val p2Defaults = settingsStore.catalogPreferences.first()
    assertTrue("Profile 2 should also start with no enabled Trakt catalogs", p2Defaults.enabledCatalogs.isEmpty())

    settingsStore.setCatalogEnabled(TraktCatalogIds.CALENDAR, true)
    val p2PrefsAfter = settingsStore.catalogPreferences.first { TraktCatalogIds.CALENDAR in it.enabledCatalogs }
    assertTrue("Profile 2 should persist manual CALENDAR enablement", TraktCatalogIds.CALENDAR in p2PrefsAfter.enabledCatalogs)

    manager.setActiveProfile(1)
    manager.activeProfileId.first { it == 1 }
    val p1PrefsAgain = settingsStore.catalogPreferences.first()
    assertTrue("Profile 1 UP_NEXT should still be enabled", TraktCatalogIds.UP_NEXT in p1PrefsAgain.enabledCatalogs)
    assertFalse("Profile 1 CALENDAR should not inherit profile 2 enablement", TraktCatalogIds.CALENDAR in p1PrefsAgain.enabledCatalogs)
}
```

- [ ] **Step 2: Update web failing tests for empty Trakt defaults**

In `nexio-web/tests/portal-contract-v4.test.ts`, add this assertion to the default settings contract test:

```ts
assert.deepEqual(settings.catalogs.trakt.catalogEnabledSet, [])
assert.equal(settings.catalogs.trakt.catalogOrder.length, 8)
```

In `nexio-web/tests/profile-settings-blob.test.ts`, add an assertion to the default profile settings test that decoded/default settings keep Trakt built-ins disabled:

```ts
assert.deepEqual(decoded.catalogs.trakt.catalogEnabledSet, [])
assert.deepEqual(decoded.catalogs.trakt.selectedPopularListKeys, [])
```

- [ ] **Step 3: Run the failing tests**

Run:

```bash
./gradlew :app:testUniversalDebugUnitTest --tests com.nexio.tv.data.local.TraktSettingsDataStoreProfileTest
```

Expected: FAIL because Android still falls back to `TraktCatalogIds.DEFAULT_ENABLED`.

Run:

```bash
cd nexio-web && npx --yes tsx --test tests/portal-contract-v4.test.ts tests/profile-settings-blob.test.ts
```

Expected: FAIL because web defaults still include four enabled Trakt catalogs.

## Task 2: Make Android And Web Defaults Empty

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/local/TraktSettingsDataStore.kt`
- Modify: `nexio-web/utils/portal-defaults.ts`
- Modify: `nexio-web/composables/useSettings.ts`

- [ ] **Step 1: Change Android default enabled set**

Replace the current `DEFAULT_ENABLED` definition and fallback reads in `TraktSettingsDataStore.kt` with empty defaults:

```kotlin
val DEFAULT_ENABLED: Set<String> = emptySet()
```

Keep `BUILT_IN_ORDER` unchanged.

Ensure the `catalogPreferences` reader still sanitizes a missing preference using `DEFAULT_ENABLED`:

```kotlin
val enabled = sanitizeEnabledCatalogs(prefs[catalogEnabledSetKey] ?: TraktCatalogIds.DEFAULT_ENABLED)
```

Ensure `setCatalogEnabled()` still starts from the current persisted value or the empty default:

```kotlin
val current = sanitizeEnabledCatalogs(prefs[catalogEnabledSetKey] ?: TraktCatalogIds.DEFAULT_ENABLED)
```

- [ ] **Step 2: Change web defaults**

In `nexio-web/utils/portal-defaults.ts`, replace:

```ts
catalogEnabledSet: [
  'trakt_up_next',
  'trakt_recommended_movies',
  'trakt_recommended_shows',
  'trakt_calendar_next_7_days'
],
```

with:

```ts
catalogEnabledSet: [],
```

In `nexio-web/composables/useSettings.ts`, replace the local Trakt default:

```ts
catalogEnabledSet: ['trakt_up_next', 'trakt_recommended_movies', 'trakt_recommended_shows', 'trakt_calendar_next_7_days'],
```

with:

```ts
catalogEnabledSet: [],
```

- [ ] **Step 3: Re-run default tests**

Run:

```bash
./gradlew :app:testUniversalDebugUnitTest --tests com.nexio.tv.data.local.TraktSettingsDataStoreProfileTest
```

Expected: PASS.

Run:

```bash
cd nexio-web && npx --yes tsx --test tests/portal-contract-v4.test.ts tests/profile-settings-blob.test.ts
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/local/TraktSettingsDataStore.kt app/src/test/java/com/nexio/tv/data/local/TraktSettingsDataStoreProfileTest.kt nexio-web/utils/portal-defaults.ts nexio-web/composables/useSettings.ts nexio-web/tests/portal-contract-v4.test.ts nexio-web/tests/profile-settings-blob.test.ts
git commit -m "fix: stop auto-enabling trakt catalog rows"
```

## Task 3: Add Android Personal Trakt List Discovery

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/remote/dto/trakt/TraktSyncDtos.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/TraktDiscoveryService.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/settings/CatalogListSearchFilterTest.kt`
- Modify or add focused tests near existing Trakt discovery tests if a test fixture already exists.

- [ ] **Step 1: Add failing tests for list option mapping and filtering**

In `CatalogListSearchFilterTest.kt`, add a test that personal list options pass through the existing search helper:

```kotlin
@Test
fun `filterTraktPopularLists includes personal user lists`() {
    val lists = listOf(
        TraktPopularListOption(
            key = "me/favorite-sci-fi",
            userId = "me",
            listId = "favorite-sci-fi",
            catalogIdBase = "trakt_list_me_favorite_sci_fi",
            title = "Favorite Sci-Fi",
            itemCount = 12,
            source = TraktListSource.PERSONAL
        ),
        TraktPopularListOption(
            key = "community/best-of-2026",
            userId = "community",
            listId = "best-of-2026",
            catalogIdBase = "trakt_list_community_best_of_2026",
            title = "Best of 2026",
            itemCount = 40,
            source = TraktListSource.POPULAR
        )
    )

    val filtered = filterTraktPopularLists(lists, "sci")

    assertEquals(listOf("me/favorite-sci-fi"), filtered.map { it.key })
}
```

This test fails until `TraktListSource` and the new constructor field exist.

- [ ] **Step 2: Parse Trakt list activity**

In `TraktSyncDtos.kt`, extend `TraktLastActivitiesResponseDto`:

```kotlin
@Json(name = "lists") val lists: TraktLastActivitiesListsDto? = null,
@Json(name = "watchlist") val watchlist: TraktLastActivitiesUpdatedDto? = null
```

Add DTOs:

```kotlin
@JsonClass(generateAdapter = true)
data class TraktLastActivitiesListsDto(
    @Json(name = "liked_at") val likedAt: String? = null,
    @Json(name = "reacted_at") val reactedAt: String? = null,
    @Json(name = "updated_at") val updatedAt: String? = null,
    @Json(name = "commented_at") val commentedAt: String? = null
)

@JsonClass(generateAdapter = true)
data class TraktLastActivitiesUpdatedDto(
    @Json(name = "updated_at") val updatedAt: String? = null
)
```

- [ ] **Step 3: Add source metadata to Trakt list options**

In `TraktDiscoveryService.kt`, add:

```kotlin
enum class TraktListSource {
    PERSONAL,
    POPULAR,
    SEARCH
}
```

Update `TraktPopularListOption`:

```kotlin
data class TraktPopularListOption(
    val key: String,
    val userId: String,
    val listId: String,
    val catalogIdBase: String,
    val title: String,
    val itemCount: Int,
    val source: TraktListSource = TraktListSource.POPULAR
)
```

- [ ] **Step 4: Fetch personal list options**

Add a personal-list fetcher to `TraktDiscoveryService.kt`:

```kotlin
private suspend fun fetchPersonalListOptions(): List<TraktPopularListOption> {
    val response = traktAuthService.executeAuthorizedRequest { authHeader ->
        traktApi.getUserLists(
            authorization = authHeader,
            id = ME_PATH
        )
    } ?: return emptyList()
    if (!response.isSuccessful) return emptyList()

    return response.body().orEmpty()
        .filter { it.type.equals("personal", ignoreCase = true) }
        .mapNotNull { dto -> mapPersonalListOption(dto) }
}
```

Add the mapper:

```kotlin
private fun mapPersonalListOption(dto: TraktListSummaryDto): TraktPopularListOption? {
    val listId = dto.ids?.slug
        ?: dto.ids?.trakt?.toString()
        ?: return null
    val key = "$ME_PATH/$listId"
    return TraktPopularListOption(
        key = key,
        userId = ME_PATH,
        listId = listId,
        catalogIdBase = "trakt_list_${slugify(key)}",
        title = dto.name?.takeIf { it.isNotBlank() } ?: listId,
        itemCount = dto.itemCount ?: 0,
        source = TraktListSource.PERSONAL
    )
}
```

Use the existing constant value `ME_PATH = "me"` if it is accessible in this file; otherwise add:

```kotlin
private const val ME_PATH = "me"
```

- [ ] **Step 5: Merge personal and popular options without duplicates**

In `ensureFresh()`, replace:

```kotlin
val popularLists = fetchPopularLists()
```

with:

```kotlin
val popularLists = mergeTraktListOptions(
    personal = fetchPersonalListOptions(),
    popular = fetchPopularLists()
)
```

Add:

```kotlin
private fun mergeTraktListOptions(
    personal: List<TraktPopularListOption>,
    popular: List<TraktPopularListOption>
): List<TraktPopularListOption> {
    return (personal + popular)
        .distinctBy { it.key }
        .sortedWith(
            compareBy<TraktPopularListOption> { it.source != TraktListSource.PERSONAL }
                .thenBy { it.title.lowercase() }
        )
}
```

- [ ] **Step 6: Fetch selected list catalogs using option source**

Keep `fetchPopularListCatalog()` name if keeping the existing type name, but update it so `option.userId == "me"` uses `/users/me/lists/...`, which the current `getUserListItems()` API already supports. The current implementation already passes `option.userId`; no route change is needed once personal options use `userId = "me"`.

Ensure selected personal keys stored in `selectedPopularListKeys` build catalog rows exactly like public list keys:

```kotlin
TraktCustomListCatalog(
    key = option.key,
    catalogId = "${option.catalogIdBase}_movies",
    catalogName = "${option.title} (Movies)",
    type = ContentType.MOVIE,
    items = movies
)
```

- [ ] **Step 7: Include list updates in discovery freshness**

In `hasActivitiesChanged()`, extend the fingerprint:

```kotlin
val fingerprint = listOfNotNull(
    body.all,
    body.movies?.watchedAt,
    body.movies?.pausedAt,
    body.episodes?.watchedAt,
    body.episodes?.pausedAt,
    body.lists?.updatedAt,
    body.watchlist?.updatedAt
).joinToString("|")
```

This ensures newly created/edited Trakt lists can be discovered without waiting for the fallback refresh interval.

- [ ] **Step 8: Run Android tests**

Run:

```bash
./gradlew :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.settings.CatalogListSearchFilterTest
```

Expected: PASS after adding the source field and keeping search behavior.

Run:

```bash
./gradlew :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.home.CatalogPlanTest --tests com.nexio.tv.ui.screens.home.HomeCatalogStartupReadinessTest
```

Expected: PASS, proving Home still only emits selected Trakt custom-list rows.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/remote/dto/trakt/TraktSyncDtos.kt app/src/main/java/com/nexio/tv/data/repository/TraktDiscoveryService.kt app/src/test/java/com/nexio/tv/ui/screens/settings/CatalogListSearchFilterTest.kt app/src/test/java/com/nexio/tv/ui/screens/home/CatalogPlanTest.kt app/src/test/java/com/nexio/tv/ui/screens/home/HomeCatalogStartupReadinessTest.kt
git commit -m "feat: discover personal trakt lists for catalog selection"
```

## Task 4: Update Android Trakt Catalog UI Copy And Refresh Behavior

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/TraktViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/TraktScreen.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/TraktSettingsContent.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/CatalogListSearchSupport.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify localized string files if needed for resource completeness.
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/settings/TraktViewModelPriorityHydrationTest.kt`

- [ ] **Step 1: Make catalog management refresh list options explicitly**

In `TraktViewModel.onCatalogManagementOpened()`, change:

```kotlin
traktDiscoveryService.ensureFresh(force = false)
```

to:

```kotlin
traktDiscoveryService.ensureFresh(force = true)
```

This is an explicit UI action, so it can bypass stale list summary data and pick up recently created Trakt lists.

- [ ] **Step 2: Add a ViewModel test for dialog refresh**

In `TraktViewModelPriorityHydrationTest.kt`, add:

```kotlin
@Test
fun `onCatalogManagementOpened forces discovery refresh so personal lists are current`() = runTest(dispatcher) {
    val notifier = CatalogPriorityHydrationNotifier()
    val discoveryService = mockk<TraktDiscoveryService>(relaxed = true)
    val viewModel = buildViewModel(notifier = notifier, discoveryServiceOverride = discoveryService)

    viewModel.onCatalogManagementOpened()
    advanceUntilIdle()

    coVerify(exactly = 1) {
        discoveryService.ensureFresh(force = true)
    }
}
```

If the helper currently cannot accept overrides, change `buildViewModel()` signature to:

```kotlin
private fun buildViewModel(
    notifier: CatalogPriorityHydrationNotifier,
    discoveryServiceOverride: TraktDiscoveryService? = null
): TraktViewModel
```

and use:

```kotlin
val discoveryService = discoveryServiceOverride ?: mockk<TraktDiscoveryService>(relaxed = true)
```

- [ ] **Step 3: Update Android labels**

In `strings.xml`, replace user-facing strings:

```xml
<string name="trakt_popular_lists_title">Trakt lists</string>
<string name="trakt_popular_lists_subtitle">Enable your own Trakt lists or community lists as Home catalog rows.</string>
<string name="trakt_popular_lists_search_hint">Search Trakt lists</string>
<string name="trakt_popular_lists_empty">No Trakt lists found yet. Create a list in Trakt or try again later.</string>
```

Keep the existing resource names to avoid changing every call site.

- [ ] **Step 4: Update row subtitles to identify personal lists**

In both `TraktScreen.kt` and `TraktSettingsContent.kt`, change the subtitle for list toggles from only item count to include source:

```kotlin
val sourceLabel = when (option.source) {
    TraktListSource.PERSONAL -> stringResource(R.string.trakt_list_source_personal)
    TraktListSource.POPULAR -> stringResource(R.string.trakt_list_source_community)
    TraktListSource.SEARCH -> stringResource(R.string.trakt_list_source_community)
}
SettingsToggleRow(
    title = option.title,
    subtitle = "$sourceLabel • ${stringResource(R.string.mdblist_list_item_count_subtitle, option.itemCount)}",
    checked = selected,
    onToggle = { viewModel.onPopularListSelected(option.key, !selected) }
)
```

Add:

```xml
<string name="trakt_list_source_personal">Your list</string>
<string name="trakt_list_source_community">Community list</string>
```

Use a plain hyphen instead of a bullet in localized files if those files prefer ASCII-only punctuation.

- [ ] **Step 5: Run Android tests**

Run:

```bash
./gradlew :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.settings.TraktViewModelPriorityHydrationTest --tests com.nexio.tv.ui.screens.settings.CatalogListSearchFilterTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/settings/TraktViewModel.kt app/src/main/java/com/nexio/tv/ui/screens/settings/TraktScreen.kt app/src/main/java/com/nexio/tv/ui/screens/settings/TraktSettingsContent.kt app/src/main/java/com/nexio/tv/ui/screens/settings/CatalogListSearchSupport.kt app/src/main/res/values/strings.xml app/src/main/res/values-de/strings.xml app/src/main/res/values-es/strings.xml app/src/main/res/values-fr/strings.xml app/src/main/res/values-nl/strings.xml app/src/main/res/values-zh-rCN/strings.xml app/src/test/java/com/nexio/tv/ui/screens/settings/TraktViewModelPriorityHydrationTest.kt
git commit -m "chore: show personal trakt lists in android catalog settings"
```

## Task 5: Add Web Trakt Personal List Option Mapping

**Files:**
- Modify: `nexio-web/types/portal.ts`
- Add: `nexio-web/server/utils/trakt-list-options.ts`
- Add: `nexio-web/tests/trakt-list-options.test.ts`

- [ ] **Step 1: Extend web option type**

In `types/portal.ts`, update `TraktPopularListOption`:

```ts
export type TraktListSource = 'personal' | 'popular' | 'search'

export type TraktPopularListOption = {
  key: string
  userId: string
  listId: string
  catalogIdBase: string
  title: string
  itemCount: number
  source?: TraktListSource
}
```

Keep `source` optional so older cached portal snapshots remain valid.

- [ ] **Step 2: Add shared mapping helper**

Create `nexio-web/server/utils/trakt-list-options.ts`:

```ts
import type { TraktListSource, TraktPopularListOption } from '~/types/portal'

type TraktListSummary = {
  name?: string
  item_count?: number
  ids?: {
    slug?: string
    trakt?: number
  }
  user?: {
    username?: string
    ids?: {
      slug?: string
    }
  }
}

export type TraktPopularListEntry = {
  user?: {
    username?: string
    ids?: {
      slug?: string
    }
  }
  list?: TraktListSummary
}

export type TraktSearchListEntry = {
  type?: string
  list?: TraktListSummary
}

export function slugifyTraktListKey(value: string): string {
  return value.toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '') || 'custom'
}

export function traktListOptionFromSummary(
  list: TraktListSummary | undefined,
  source: TraktListSource,
  userOverride?: string
): TraktPopularListOption | null {
  if (!list) return null

  const userId = source === 'personal'
    ? 'me'
    : userOverride || list.user?.ids?.slug || list.user?.username || ''
  const listId = list.ids?.slug || String(list.ids?.trakt || '')
  if (!userId || !listId) return null

  const key = `${userId}/${listId}`
  return {
    key,
    userId,
    listId,
    catalogIdBase: `trakt_list_${slugifyTraktListKey(key)}`,
    title: list.name || key,
    itemCount: list.item_count || 0,
    source
  }
}

export function traktListOptionsFromPopular(payload: TraktPopularListEntry[]): TraktPopularListOption[] {
  return payload.flatMap((entry) => {
    const userId = entry.user?.ids?.slug || entry.user?.username || entry.list?.user?.ids?.slug || entry.list?.user?.username || ''
    const option = traktListOptionFromSummary(entry.list, 'popular', userId)
    return option ? [option] : []
  })
}

export function traktListOptionsFromSearch(payload: TraktSearchListEntry[]): TraktPopularListOption[] {
  return payload.flatMap((entry) => {
    if (entry.type !== 'list') return []
    const option = traktListOptionFromSummary(entry.list, 'search')
    return option ? [option] : []
  })
}

export function mergeTraktListOptions(...groups: TraktPopularListOption[][]): TraktPopularListOption[] {
  const byKey = new Map<string, TraktPopularListOption>()
  for (const option of groups.flat()) {
    if (!byKey.has(option.key)) byKey.set(option.key, option)
  }
  return [...byKey.values()].sort((left, right) => {
    const leftPersonal = left.source === 'personal' ? 0 : 1
    const rightPersonal = right.source === 'personal' ? 0 : 1
    return leftPersonal - rightPersonal || left.title.localeCompare(right.title)
  })
}
```

- [ ] **Step 3: Add helper tests**

Create `nexio-web/tests/trakt-list-options.test.ts`:

```ts
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  mergeTraktListOptions,
  traktListOptionFromSummary,
  traktListOptionsFromPopular,
  traktListOptionsFromSearch
} from '../server/utils/trakt-list-options.ts'

test('personal Trakt list options use stable me-prefixed keys', () => {
  const option = traktListOptionFromSummary({
    name: 'Favorite Sci-Fi',
    item_count: 12,
    ids: { slug: 'favorite-sci-fi', trakt: 123 }
  }, 'personal')

  assert.deepEqual(option, {
    key: 'me/favorite-sci-fi',
    userId: 'me',
    listId: 'favorite-sci-fi',
    catalogIdBase: 'trakt_list_me_favorite_sci_fi',
    title: 'Favorite Sci-Fi',
    itemCount: 12,
    source: 'personal'
  })
})

test('public Trakt list mapping keeps owner in key', () => {
  const lists = traktListOptionsFromPopular([{
    user: { username: 'sean', ids: { slug: 'sean' } },
    list: {
      name: 'Best Movies',
      item_count: 44,
      ids: { slug: 'best-movies' }
    }
  }])

  assert.equal(lists[0].key, 'sean/best-movies')
  assert.equal(lists[0].source, 'popular')
})

test('search mapping ignores non-list results', () => {
  const lists = traktListOptionsFromSearch([
    { type: 'movie', list: { name: 'Wrong' } },
    {
      type: 'list',
      list: {
        name: 'Noir',
        item_count: 8,
        ids: { trakt: 55 },
        user: { username: 'cine' }
      }
    }
  ])

  assert.deepEqual(lists.map((list) => list.key), ['cine/55'])
})

test('merge puts personal lists first and de-duplicates by key', () => {
  const merged = mergeTraktListOptions(
    [{ key: 'cine/noir', userId: 'cine', listId: 'noir', catalogIdBase: 'trakt_list_cine_noir', title: 'Noir', itemCount: 8, source: 'popular' }],
    [{ key: 'me/watch-next', userId: 'me', listId: 'watch-next', catalogIdBase: 'trakt_list_me_watch_next', title: 'Watch Next', itemCount: 2, source: 'personal' }],
    [{ key: 'cine/noir', userId: 'cine', listId: 'noir', catalogIdBase: 'trakt_list_cine_noir', title: 'Noir Copy', itemCount: 9, source: 'search' }]
  )

  assert.deepEqual(merged.map((list) => list.key), ['me/watch-next', 'cine/noir'])
  assert.equal(merged[1].title, 'Noir')
})
```

- [ ] **Step 4: Run helper tests**

Run:

```bash
cd nexio-web && npx --yes tsx --test tests/trakt-list-options.test.ts
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add nexio-web/types/portal.ts nexio-web/server/utils/trakt-list-options.ts nexio-web/tests/trakt-list-options.test.ts
git commit -m "feat: add trakt list option mapping"
```

## Task 6: Fetch Personal Lists In Web Account And Profile Routes

**Files:**
- Modify: `nexio-web/server/api/integrations/trakt/popular-lists.post.ts`
- Modify: `nexio-web/server/api/integrations/profiles/trakt/popular-lists.post.ts`
- Modify: `nexio-web/server/api/integrations/trakt/search-lists.post.ts`
- Modify: `nexio-web/server/api/integrations/profiles/trakt/search-lists.post.ts`
- Modify or add route tests if existing server route tests are present.

- [ ] **Step 1: Update account/default-profile route**

In `nexio-web/server/api/integrations/trakt/popular-lists.post.ts`, import:

```ts
import {
  mergeTraktListOptions,
  traktListOptionFromSummary,
  traktListOptionsFromPopular,
  type TraktPopularListEntry
} from '~/server/utils/trakt-list-options'
```

After resolving the account Trakt token, fetch both endpoints through the same auto-refresh wrapper:

```ts
const { response: personalResponse, refreshedTokens } = await fetchTraktWithAutoRefresh({
  accessToken,
  request: async (currentAccessToken) => await fetch('https://api.trakt.tv/users/me/lists', {
    headers: buildTraktHeaders({ clientId, accessToken: currentAccessToken })
  }),
  refreshAccessToken: refreshAccessTokenHandler
})
```

Then fetch public popular lists with the current access token after refresh handling. If sharing the exact refreshed token inside the existing helper is awkward, call `fetchTraktWithAutoRefresh()` twice, using the persisted refreshed token logic already in this route after each call.

Map:

```ts
const personalPayload = await personalResponse.json() as Array<Parameters<typeof traktListOptionFromSummary>[0]>
const personalLists = personalPayload.flatMap((list) => {
  const option = traktListOptionFromSummary(list, 'personal')
  return option ? [option] : []
})

const popularPayload = await popularResponse.json() as TraktPopularListEntry[]
const popularLists = traktListOptionsFromPopular(popularPayload)

return okJson({ lists: mergeTraktListOptions(personalLists, popularLists) })
```

Keep the route name `popular-lists.post.ts` for compatibility with current clients; it now returns "Trakt list options".

- [ ] **Step 2: Update profile route without crossing ProfileBoundary**

In `nexio-web/server/api/integrations/profiles/trakt/popular-lists.post.ts`, import the same helper and fetch:

```ts
const personalResponse = await fetchProfileTraktWithRefresh({
  userId: user.id,
  profileIndex,
  source: 'web-profile-trakt',
  request: async (accessToken, clientId) => await fetch('https://api.trakt.tv/users/me/lists', {
    headers: buildTraktHeaders({ clientId, accessToken })
  })
})
```

Then fetch public popular lists through `fetchProfileTraktWithRefresh()` as it does today. Return `mergeTraktListOptions(personalLists, popularLists)`.

Do not use account secret resolution in this profile route. It must continue to use `fetchProfileTraktWithRefresh()`.

- [ ] **Step 3: Update search routes to use shared mapping**

In both search routes, replace local slugify/mapping logic with:

```ts
const lists = traktListOptionsFromSearch(payload)
```

Search results should use `source: 'search'`.

- [ ] **Step 4: Run web route/helper tests and type check**

Run:

```bash
cd nexio-web && npx --yes tsx --test tests/trakt-list-options.test.ts tests/portal-contract-v4.test.ts tests/profile-settings-blob.test.ts
```

Expected: PASS.

Run:

```bash
cd nexio-web && npx --yes vue-tsc --noEmit
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add nexio-web/server/api/integrations/trakt/popular-lists.post.ts nexio-web/server/api/integrations/profiles/trakt/popular-lists.post.ts nexio-web/server/api/integrations/trakt/search-lists.post.ts nexio-web/server/api/integrations/profiles/trakt/search-lists.post.ts nexio-web/server/utils/trakt-list-options.ts nexio-web/tests/trakt-list-options.test.ts
git commit -m "feat: include personal trakt lists in web catalog selection"
```

## Task 7: Update Web Stores And UI To Display Personal Lists

**Files:**
- Modify: `nexio-web/composables/usePortalStore.ts`
- Modify: `nexio-web/composables/useProfileStore.ts`
- Modify: `nexio-web/components/portal/SettingsWorkspace.vue`
- Modify tests if existing Vue store tests cover catalog inventory.

- [ ] **Step 1: Treat selected Trakt lists as generic selected list options**

In `usePortalStore.ts`, keep `state.traktDiscovery.popularLists` for compatibility, but update comments and local variable names in `buildTraktCatalogs()`:

```ts
const selectedLists = state.traktDiscovery.popularLists
  .filter((list) => state.settings.catalogs.trakt.selectedPopularListKeys.includes(list.key))
  .map((list) => ({
    key: list.key,
    disableKey: '',
    addonId: 'trakt',
    addonName: 'Trakt',
    addonUrl: list.source === 'personal' ? 'trakt://personal-lists' : 'trakt://lists',
    catalogId: list.catalogIdBase || list.key,
    catalogName: list.title,
    type: list.source === 'personal' ? 'personal list' : 'community list',
    source: 'trakt' as const,
    isSearchOnly: false
  }))
```

Return:

```ts
return [...enabledBuiltIns, ...selectedLists]
```

Apply the same change in `useProfileStore.ts` inside `buildProfileTraktCatalogs()`.

- [ ] **Step 2: Split SettingsWorkspace list display by source**

In `SettingsWorkspace.vue`, add computed helpers:

```ts
const personalTraktLists = computed(() => (props.traktPopularLists ?? []).filter((list) => list.source === 'personal'))
const communityTraktLists = computed(() => (props.traktPopularLists ?? []).filter((list) => list.source !== 'personal'))
```

Replace the "Trending Lists" block label with "Your Trakt lists" and render `personalTraktLists` first. Add a second block labeled "Community lists" for `communityTraktLists`. Keep search results as "Search community lists".

Use the same toggle event:

```vue
@change="emit('toggle-trakt-list', list.key)"
```

This preserves manual enablement through `catalogs.trakt.selectedPopularListKeys`.

- [ ] **Step 3: Ensure account and profile refresh paths stay separate**

Verify these call sites remain unchanged:

```ts
await sessionApiFetch<{ lists: TraktPopularListOption[] }>('/api/integrations/trakt/popular-lists', ...)
```

and:

```ts
await $fetch<{ lists: TraktPopularListOption[] }>('/api/integrations/profiles/trakt/popular-lists', {
  method: 'POST',
  body: { profileIndex },
  headers: authHeaders()
})
```

Do not point profile routes at account routes or account routes at profile routes.

- [ ] **Step 4: Run web tests and type check**

Run:

```bash
cd nexio-web && npx --yes tsx --test tests/trakt-list-options.test.ts tests/portal-contract-v4.test.ts tests/profile-settings-blob.test.ts
```

Expected: PASS.

Run:

```bash
cd nexio-web && npx --yes vue-tsc --noEmit
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add nexio-web/composables/usePortalStore.ts nexio-web/composables/useProfileStore.ts nexio-web/components/portal/SettingsWorkspace.vue
git commit -m "chore: show personal trakt lists in web catalog settings"
```

## Task 8: Verify Home Rows Only Publish After Manual Enablement

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/home/CatalogPlanTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeCatalogStartupReadinessTest.kt`

- [ ] **Step 1: Add a CatalogPlan test for unselected personal custom lists**

In `CatalogPlanTest.kt`, add:

```kotlin
@Test
fun `trakt custom list snapshot does not publish when list key is not selected`() {
    val snapshot = TraktDiscoverySnapshot(
        customListCatalogs = listOf(
            TraktCustomListCatalog(
                key = "me/favorite-sci-fi",
                catalogId = "trakt_list_me_favorite_sci_fi_movies",
                catalogName = "Favorite Sci-Fi (Movies)",
                type = ContentType.MOVIE,
                items = listOf(testMetaPreview("movie-1", ContentType.MOVIE))
            )
        )
    )

    val plan = buildConfiguredCatalogPlan(
        addons = emptyList(),
        disabledHomeCatalogKeys = emptySet(),
        availableAddonOrderKeys = emptySet(),
        traktPrefs = TraktCatalogPreferences(
            enabledCatalogs = emptySet(),
            catalogOrder = TraktCatalogIds.BUILT_IN_ORDER,
            selectedPopularListKeys = emptySet()
        ),
        traktSnapshot = snapshot,
        hasTraktUpNextItems = false,
        simklPrefs = SimklCatalogPreferences(enabledCatalogs = emptySet(), catalogOrder = emptyList()),
        simklSnapshot = SimklDiscoverySnapshot(),
        mdbPrefs = MDBListCatalogPreferences(),
        mdbSnapshot = MDBListDiscoverySnapshot()
    )

    assertTrue(plan.publishableOrderKeys.isEmpty())
    assertTrue(plan.rails.isEmpty())
}
```

Use the existing test fixture function name if `testMetaPreview()` is already named differently in that file.

- [ ] **Step 2: Add selected custom list positive test**

Add:

```kotlin
@Test
fun `trakt personal custom list publishes after manual selection`() {
    val snapshot = TraktDiscoverySnapshot(
        customListCatalogs = listOf(
            TraktCustomListCatalog(
                key = "me/favorite-sci-fi",
                catalogId = "trakt_list_me_favorite_sci_fi_movies",
                catalogName = "Favorite Sci-Fi (Movies)",
                type = ContentType.MOVIE,
                items = listOf(testMetaPreview("movie-1", ContentType.MOVIE))
            )
        )
    )

    val plan = buildConfiguredCatalogPlan(
        addons = emptyList(),
        disabledHomeCatalogKeys = emptySet(),
        availableAddonOrderKeys = emptySet(),
        traktPrefs = TraktCatalogPreferences(
            enabledCatalogs = emptySet(),
            catalogOrder = TraktCatalogIds.BUILT_IN_ORDER,
            selectedPopularListKeys = setOf("me/favorite-sci-fi")
        ),
        traktSnapshot = snapshot,
        hasTraktUpNextItems = false,
        simklPrefs = SimklCatalogPreferences(enabledCatalogs = emptySet(), catalogOrder = emptyList()),
        simklSnapshot = SimklDiscoverySnapshot(),
        mdbPrefs = MDBListCatalogPreferences(),
        mdbSnapshot = MDBListDiscoverySnapshot()
    )

    assertEquals(listOf("me/favorite-sci-fi"), plan.publishableOrderKeys)
    assertEquals("trakt_list_me_favorite_sci_fi_movies", plan.rails.single().toPopulatedRows().single().catalogId)
}
```

- [ ] **Step 3: Run Home catalog planner tests**

Run:

```bash
./gradlew :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.home.CatalogPlanTest --tests com.nexio.tv.ui.screens.home.HomeCatalogStartupReadinessTest
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/test/java/com/nexio/tv/ui/screens/home/CatalogPlanTest.kt app/src/test/java/com/nexio/tv/ui/screens/home/HomeCatalogStartupReadinessTest.kt
git commit -m "test: require manual trakt list catalog selection"
```

## Task 9: Final Verification

**Files:**
- No planned source changes in this task.

- [ ] **Step 1: Run Android targeted test suite**

Run:

```bash
./gradlew :app:testUniversalDebugUnitTest --tests com.nexio.tv.data.local.TraktSettingsDataStoreProfileTest --tests com.nexio.tv.ui.screens.settings.TraktViewModelPriorityHydrationTest --tests com.nexio.tv.ui.screens.settings.CatalogListSearchFilterTest --tests com.nexio.tv.ui.screens.home.CatalogPlanTest --tests com.nexio.tv.ui.screens.home.HomeCatalogStartupReadinessTest
```

Expected: PASS.

- [ ] **Step 2: Run web targeted tests**

Run:

```bash
cd nexio-web && npx --yes tsx --test tests/trakt-list-options.test.ts tests/portal-contract-v4.test.ts tests/profile-settings-blob.test.ts
```

Expected: PASS.

- [ ] **Step 3: Run web type check**

Run:

```bash
cd nexio-web && npx --yes vue-tsc --noEmit
```

Expected: PASS.

- [ ] **Step 4: Check profile boundary separation**

Run:

```bash
rg -n "fetchProfileTraktWithRefresh|service_resolve_account_secret|profile_auth_tokens|ProfileBoundary|currentTraktProfileId" app/src/main/java nexio-web/server/api/integrations nexio-web/server/utils
```

Expected:
- Android Trakt auth still routes through `ProfileBoundary` for secondary profiles.
- `nexio-web/server/api/integrations/profiles/trakt/*` uses `fetchProfileTraktWithRefresh()`.
- `nexio-web/server/api/integrations/trakt/*` uses account secret resolution.
- No profile Trakt route resolves account secrets for list options.

- [ ] **Step 5: Inspect git diff for unrelated dirty files**

Run:

```bash
git status --short
```

Expected:
- Existing unrelated dirty files remain untouched unless this implementation deliberately changed them.
- No changes are made to `app/src/main/java/com/nexio/tv/data/repository/TraktLibraryService.kt` unless a later implementation step explicitly requires it and reviews the existing dirty diff first.

## Self-Review

Spec coverage:
- No default list can be enabled: covered by Tasks 1-2 on Android and web defaults.
- Catalog rows only after manual enablement: covered by existing Home gating plus Task 8 tests for selected versus unselected personal list keys.
- Custom user-created Trakt lists appear: covered by Tasks 3, 5, 6, and 7.
- Custom user-created Trakt lists can become Modern Home feeds: covered by Tasks 3 and 8 through `TraktCustomListCatalog` and `selectedPopularListKeys`.
- Default and non-default profile separation through `ProfileBoundary`: covered by architecture constraint and Task 9 boundary audit.

Placeholder scan:
- The plan avoids deferred placeholders and names exact files, functions, snippets, and commands.

Type consistency:
- Android keeps `selectedPopularListKeys` for sync compatibility while adding `TraktListSource`.
- Web keeps `TraktPopularListOption` for API compatibility while adding optional `source`.
- Personal Trakt list keys use `me/<list_id>` consistently on Android and web.
