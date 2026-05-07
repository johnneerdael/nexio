# Remove Poster Ratings Legacy Booleans Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove `rpdbEnabled` / `topPostersEnabled` from active poster-ratings domain, runtime, settings, and account-sync models while preserving only an on-device upgrade migration from old DataStore keys.

**Architecture:** The artwork provider selector model is now authoritative: `ArtworkProviderSettings.selection` stores the selected provider per artwork type. Legacy booleans may only be read inside `PosterRatingsSettingsDataStore` to migrate old local preferences when selector keys are absent. Account sync moves to schema v10 selector fields and skips applying old poster-ratings payloads instead of preserving cross-version boolean compatibility.

**Tech Stack:** Android Kotlin, Jetpack DataStore Preferences, kotlinx.serialization account-sync DTOs, JUnit/Robolectric unit tests, Gradle `testDebugUnitTest`.

---

## File Structure

Modify:

- `app/src/main/java/com/nexio/tv/domain/model/PosterRatingsSettings.kt`
  - Remove `PosterRatingsSettings` and `toArtworkProviderSettings`.
  - Keep only `PosterRatingsProvider`, or move it to `PosterRatingsProvider.kt` if preferred by the implementer.

- `app/src/main/java/com/nexio/tv/data/local/PosterRatingsSettingsDataStore.kt`
  - Keep private legacy preference keys only for local upgrade migration.
  - Remove active public setters `setRpdbEnabled` and `setTopPostersEnabled`.
  - Replace domain-model migration with a private DataStore migration function.

- `app/src/main/java/com/nexio/tv/data/remote/supabase/AccountSyncModels.kt`
  - Replace `PosterRatingsSyncSettings(rpdbEnabled, topPostersEnabled)` with selector fields:
    `posterProvider`, `logoProvider`, `backdropProvider`, `thumbnailProvider`.

- `app/src/main/java/com/nexio/tv/core/sync/AccountConfigSyncContract.kt`
  - Bump `ACCOUNT_CONFIG_SYNC_CONTRACT_VERSION` from `9` to `10`.
  - Replace `applyPosterRatingsProviderSelection` with selector-based application.
  - Skip poster-ratings apply for payloads with `schemaVersion < 10`.

- `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt`
  - Emit selector fields instead of legacy booleans.

- `app/src/test/java/com/nexio/tv/architecture/LegacyPosterRatingsBooleanBoundaryTest.kt`
  - Create permanent architecture test forbidding active legacy boolean usage outside local migration tests/DataStore migration.

- `app/src/test/java/com/nexio/tv/data/local/PosterRatingsSettingsDataStoreTest.kt`
  - Keep local upgrade migration tests.
  - Replace remote boolean sync test with selector sync tests.

- `app/src/test/java/com/nexio/tv/domain/model/ArtworkProviderSettingsTest.kt`
  - Remove tests for `PosterRatingsSettings`.
  - Keep tests for selector values, unknown provider defaults, and Top-Posters entitlement behavior.

- `app/src/test/java/com/nexio/tv/core/sync/AccountConfigSyncContractTest.kt`
  - Replace boolean payload assertions with selector payload assertions.
  - Add schema v9 skip-apply test.

- `app/src/test/java/com/nexio/tv/ui/screens/settings/PosterRatingsSettingsViewModelTest.kt`
  - Replace `PosterRatingsSettings().toArtworkProviderSettings()` with `ArtworkProviderSettings()`.

- `app/src/test/java/com/nexio/tv/data/integration/posters/PremiumPosterMetadataProviderAdapterStableIdTest.kt`
  - Replace legacy helper inputs with `ArtworkProviderSettings` builders.

Do not modify release files or unrelated root checkout artifacts.

---

## Task 1: Add Hard Boundary Test For Legacy Boolean Removal

**Files:**
- Create: `app/src/test/java/com/nexio/tv/architecture/LegacyPosterRatingsBooleanBoundaryTest.kt`

- [ ] **Step 1: Write the failing architecture test**

Create `app/src/test/java/com/nexio/tv/architecture/LegacyPosterRatingsBooleanBoundaryTest.kt`:

```kotlin
package com.nexio.tv.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyPosterRatingsBooleanBoundaryTest {

    @Test
    fun `legacy poster ratings booleans are confined to local migration boundary`() {
        val root = File("app/src/main/java/com/nexio/tv")
        val forbiddenTokens = listOf(
            "rpdbEnabled",
            "topPostersEnabled",
            "setRpdbEnabled",
            "setTopPostersEnabled",
            "PosterRatingsSettings("
        )
        val allowedFiles = setOf(
            "data/local/PosterRatingsSettingsDataStore.kt"
        )

        val violations = root.walkTopDown()
            .filter { file -> file.isFile && file.extension == "kt" }
            .flatMap { file ->
                val relativePath = file.relativeTo(root).path
                if (relativePath in allowedFiles) {
                    emptySequence()
                } else {
                    file.readLines().asSequence().mapIndexedNotNull { index, line ->
                        val token = forbiddenTokens.firstOrNull(line::contains)
                        if (token == null) {
                            null
                        } else {
                            "$relativePath:${index + 1}: contains `$token`: ${line.trim()}"
                        }
                    }
                }
            }
            .toList()

        assertTrue(
            "Legacy poster-ratings booleans must not exist outside local DataStore migration:\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
./gradlew --no-build-cache testDebugUnitTest --tests 'com.nexio.tv.architecture.LegacyPosterRatingsBooleanBoundaryTest'
```

Expected: FAIL. The failure should list current active usages in `PosterRatingsSettings.kt`, `AccountSyncModels.kt`, `AccountConfigSyncContract.kt`, and `AccountSettingsSyncService.kt`.

- [ ] **Step 3: Commit the failing guardrail**

```bash
git add app/src/test/java/com/nexio/tv/architecture/LegacyPosterRatingsBooleanBoundaryTest.kt
git commit -m "test: guard poster ratings legacy boolean boundary"
```

---

## Task 2: Remove Active Domain Legacy Settings Model

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/domain/model/PosterRatingsSettings.kt`
- Modify: `app/src/test/java/com/nexio/tv/domain/model/ArtworkProviderSettingsTest.kt`

- [ ] **Step 1: Replace `PosterRatingsSettings.kt` with provider enum only**

Replace the full contents of `app/src/main/java/com/nexio/tv/domain/model/PosterRatingsSettings.kt` with:

```kotlin
package com.nexio.tv.domain.model

enum class PosterRatingsProvider {
    NONE,
    RPDB,
    TOP_POSTERS
}
```

This keeps the existing `PosterRatingsProvider` type used by `PosterRatingsUrlResolver` without retaining the obsolete boolean-backed settings model.

- [ ] **Step 2: Remove legacy migration tests from `ArtworkProviderSettingsTest`**

In `app/src/test/java/com/nexio/tv/domain/model/ArtworkProviderSettingsTest.kt`, remove:

```kotlin
import com.nexio.tv.domain.model.PosterRatingsSettings
import com.nexio.tv.domain.model.PosterRatingsProvider
import com.nexio.tv.domain.model.toArtworkProviderSettings
```

Then delete the tests named:

```text
legacy enabled rpdb migrates to rpdb poster selection
legacy enabled top posters migrates to top posters poster selection
legacy enabled provider with blank key still migrates to poster selection
legacy migration preserves rpdb precedence when both providers are active
legacy disabled provider with key keeps key but defaults selection
```

Keep the existing tests for:

```text
topPostersCanProvideThumbnails
ArtworkProviderChoiceKey.fromStored
selection providerFor / withProvider behavior
```

- [ ] **Step 3: Run focused domain tests**

Run:

```bash
./gradlew --no-build-cache testDebugUnitTest --tests 'com.nexio.tv.domain.model.ArtworkProviderSettingsTest'
```

Expected: PASS.

- [ ] **Step 4: Run boundary test**

Run:

```bash
./gradlew --no-build-cache testDebugUnitTest --tests 'com.nexio.tv.architecture.LegacyPosterRatingsBooleanBoundaryTest'
```

Expected: still FAIL, but only for DataStore/sync/test callers not yet migrated.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/domain/model/PosterRatingsSettings.kt app/src/test/java/com/nexio/tv/domain/model/ArtworkProviderSettingsTest.kt
git commit -m "refactor: remove boolean poster ratings domain model"
```

---

## Task 3: Confine Legacy Boolean Reads To DataStore Upgrade Migration

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/local/PosterRatingsSettingsDataStore.kt`
- Modify: `app/src/test/java/com/nexio/tv/data/local/PosterRatingsSettingsDataStoreTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/settings/PosterRatingsSettingsViewModelTest.kt`

- [ ] **Step 1: Remove obsolete imports from DataStore**

In `app/src/main/java/com/nexio/tv/data/local/PosterRatingsSettingsDataStore.kt`, remove:

```kotlin
import com.nexio.tv.domain.model.PosterRatingsSettings
import com.nexio.tv.domain.model.toArtworkProviderSettings
```

- [ ] **Step 2: Replace `settings` flow migration logic**

Replace the current `val settings` block with:

```kotlin
    val settings: Flow<ArtworkProviderSettings> = dataStore.data.map { prefs ->
        val migratedPosterProvider = prefs.legacyPosterProviderSelection()

        ArtworkProviderSettings(
            rpdbApiKey = prefs[rpdbApiKeyKey] ?: "",
            topPostersApiKey = prefs[topPostersApiKeyKey] ?: "",
            selection = ArtworkProviderSelectionSettings(
                posterProvider = prefs.providerChoiceOrNull(posterProviderKey)
                    ?: migratedPosterProvider,
                logoProvider = prefs.providerChoiceOrNull(logoProviderKey)
                    ?: ArtworkProviderChoiceKey.DEFAULT,
                backdropProvider = prefs.providerChoiceOrNull(backdropProviderKey)
                    ?: ArtworkProviderChoiceKey.DEFAULT,
                thumbnailProvider = prefs.providerChoiceOrNull(thumbnailProviderKey)
                    ?: ArtworkProviderChoiceKey.DEFAULT
            ),
            topPostersEntitlement = prefs.topPostersEntitlement()
        )
    }
```

- [ ] **Step 3: Delete active legacy setters**

Delete these methods from `PosterRatingsSettingsDataStore`:

```kotlin
    suspend fun setRpdbEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[rpdbEnabledKey] = enabled
        }
    }

    suspend fun setTopPostersEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[topPostersEnabledKey] = enabled
        }
    }
```

- [ ] **Step 4: Add private migration helper**

Add this helper near `providerChoiceOrNull`:

```kotlin
    private fun Preferences.legacyPosterProviderSelection(): ArtworkProviderChoiceKey =
        when {
            this[rpdbEnabledKey] == true -> ArtworkProviderChoiceKey.RPDB
            this[topPostersEnabledKey] == true -> ArtworkProviderChoiceKey.TOP_POSTERS
            else -> ArtworkProviderChoiceKey.DEFAULT
        }
```

This is the only production-code place where old boolean preferences remain visible.

- [ ] **Step 5: Keep test-only legacy writer but clarify its name**

Rename `writeLegacyForTest` to `writeLegacyBooleanPreferencesForTest`:

```kotlin
    @VisibleForTesting
    suspend fun writeLegacyBooleanPreferencesForTest(
        rpdbEnabled: Boolean,
        rpdbApiKey: String,
        topPostersEnabled: Boolean,
        topPostersApiKey: String
    ) {
        dataStore.edit { prefs ->
            prefs[rpdbEnabledKey] = rpdbEnabled
            prefs[rpdbApiKeyKey] = rpdbApiKey
            prefs[topPostersEnabledKey] = topPostersEnabled
            prefs[topPostersApiKeyKey] = topPostersApiKey
            prefs.remove(posterProviderKey)
            prefs.remove(logoProviderKey)
            prefs.remove(backdropProviderKey)
            prefs.remove(thumbnailProviderKey)
        }
    }
```

- [ ] **Step 6: Update DataStore tests for new helper name**

In `app/src/test/java/com/nexio/tv/data/local/PosterRatingsSettingsDataStoreTest.kt`, replace each:

```kotlin
store.writeLegacyForTest(
```

with:

```kotlin
store.writeLegacyBooleanPreferencesForTest(
```

- [ ] **Step 7: Remove remote boolean sync test from DataStore tests**

Delete the test named:

```text
remote legacy booleans update poster selection without clearing keys
```

Also remove these imports if unused:

```kotlin
import com.nexio.tv.core.sync.applyPosterRatingsProviderSelection
import com.nexio.tv.data.remote.supabase.PosterRatingsSyncSettings
```

- [ ] **Step 8: Update ViewModel test default settings**

In `app/src/test/java/com/nexio/tv/ui/screens/settings/PosterRatingsSettingsViewModelTest.kt`, remove:

```kotlin
import com.nexio.tv.domain.model.PosterRatingsSettings
import com.nexio.tv.domain.model.toArtworkProviderSettings
```

Replace:

```kotlin
initialSettings: ArtworkProviderSettings = PosterRatingsSettings().toArtworkProviderSettings()
```

with:

```kotlin
initialSettings: ArtworkProviderSettings = ArtworkProviderSettings()
```

- [ ] **Step 9: Run focused tests**

Run:

```bash
./gradlew --no-build-cache testDebugUnitTest \
  --tests 'com.nexio.tv.data.local.PosterRatingsSettingsDataStoreTest' \
  --tests 'com.nexio.tv.ui.screens.settings.PosterRatingsSettingsViewModelTest' \
  --tests 'com.nexio.tv.architecture.LegacyPosterRatingsBooleanBoundaryTest'
```

Expected: DataStore and ViewModel tests PASS. Boundary test still FAILS only for account-sync model/contract references.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/local/PosterRatingsSettingsDataStore.kt app/src/test/java/com/nexio/tv/data/local/PosterRatingsSettingsDataStoreTest.kt app/src/test/java/com/nexio/tv/ui/screens/settings/PosterRatingsSettingsViewModelTest.kt
git commit -m "refactor: confine poster ratings booleans to datastore migration"
```

---

## Task 4: Replace Account Sync Booleans With Provider Selectors

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/remote/supabase/AccountSyncModels.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/sync/AccountConfigSyncContract.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt`

- [ ] **Step 1: Replace account-sync DTO fields**

In `app/src/main/java/com/nexio/tv/data/remote/supabase/AccountSyncModels.kt`, replace:

```kotlin
@Serializable
data class PosterRatingsSyncSettings(
    val rpdbEnabled: Boolean = false,
    val topPostersEnabled: Boolean = false
)
```

with:

```kotlin
@Serializable
data class PosterRatingsSyncSettings(
    @EncodeDefault
    val posterProvider: String = "default",
    @EncodeDefault
    val logoProvider: String = "default",
    @EncodeDefault
    val backdropProvider: String = "default",
    @EncodeDefault
    val thumbnailProvider: String = "default"
)
```

- [ ] **Step 2: Bump account config sync contract version**

In `app/src/main/java/com/nexio/tv/core/sync/AccountConfigSyncContract.kt`, replace:

```kotlin
internal const val ACCOUNT_CONFIG_SYNC_CONTRACT_VERSION = 9
```

with:

```kotlin
internal const val ACCOUNT_CONFIG_SYNC_CONTRACT_VERSION = 10
```

- [ ] **Step 3: Replace poster-ratings apply helper**

In `AccountConfigSyncContract.kt`, replace:

```kotlin
internal suspend fun applyPosterRatingsProviderSelection(
    settings: PosterRatingsSyncSettings,
    posterRatingsSettingsDataStore: PosterRatingsSettingsDataStore
) {
    val provider = when {
        settings.rpdbEnabled -> ArtworkProviderChoiceKey.RPDB
        settings.topPostersEnabled -> ArtworkProviderChoiceKey.TOP_POSTERS
        else -> ArtworkProviderChoiceKey.DEFAULT
    }

    posterRatingsSettingsDataStore.setProviderSelection(ArtworkTypeKey.POSTER, provider)
}
```

with:

```kotlin
internal suspend fun applyPosterRatingsProviderSelections(
    settings: PosterRatingsSyncSettings,
    posterRatingsSettingsDataStore: PosterRatingsSettingsDataStore
) {
    posterRatingsSettingsDataStore.setProviderSelection(
        ArtworkTypeKey.POSTER,
        ArtworkProviderChoiceKey.fromStored(settings.posterProvider)
    )
    posterRatingsSettingsDataStore.setProviderSelection(
        ArtworkTypeKey.LOGO,
        ArtworkProviderChoiceKey.fromStored(settings.logoProvider)
    )
    posterRatingsSettingsDataStore.setProviderSelection(
        ArtworkTypeKey.BACKDROP,
        ArtworkProviderChoiceKey.fromStored(settings.backdropProvider)
    )
    posterRatingsSettingsDataStore.setProviderSelection(
        ArtworkTypeKey.THUMBNAIL,
        ArtworkProviderChoiceKey.fromStored(settings.thumbnailProvider)
    )
}
```

- [ ] **Step 4: Gate remote apply by schema version**

In `applyAccountConfigSyncSettings`, replace:

```kotlin
    applyPosterRatingsProviderSelection(
        settings = settings.integrations.posterRatings,
        posterRatingsSettingsDataStore = posterRatingsSettingsDataStore
    )
```

with:

```kotlin
    if (settings.schemaVersion >= 10) {
        applyPosterRatingsProviderSelections(
            settings = settings.integrations.posterRatings,
            posterRatingsSettingsDataStore = posterRatingsSettingsDataStore
        )
    }
```

This intentionally avoids cross-version boolean compatibility. A schema v9 remote payload cannot clear or rewrite the new selector model.

- [ ] **Step 5: Update the second account-sync apply call**

In `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt`, there is another call to `applyPosterRatingsProviderSelection` in the account apply path. Replace it with:

```kotlin
        if (settings.schemaVersion >= 10) {
            applyPosterRatingsProviderSelections(
                settings = settings.integrations.posterRatings,
                posterRatingsSettingsDataStore = posterRatingsSettingsDataStore
            )
        }
```

- [ ] **Step 6: Emit selector fields during push**

In `AccountSettingsSyncService.kt`, replace:

```kotlin
                posterRatings = PosterRatingsSyncSettings(
                    rpdbEnabled = posterRatings.selection.posterProvider ==
                        ArtworkProviderChoiceKey.RPDB,
                    topPostersEnabled = posterRatings.selection.posterProvider ==
                        ArtworkProviderChoiceKey.TOP_POSTERS
                ),
```

with:

```kotlin
                posterRatings = PosterRatingsSyncSettings(
                    posterProvider = posterRatings.selection.posterProvider.value,
                    logoProvider = posterRatings.selection.logoProvider.value,
                    backdropProvider = posterRatings.selection.backdropProvider.value,
                    thumbnailProvider = posterRatings.selection.thumbnailProvider.value
                ),
```

- [ ] **Step 7: Remove unused imports**

Run:

```bash
./gradlew --no-build-cache compileDebugKotlin
```

Expected: either PASS or compile errors for stale imports/call names. Remove any stale imports or stale call sites reported for:

```text
applyPosterRatingsProviderSelection
rpdbEnabled
topPostersEnabled
```

- [ ] **Step 8: Run boundary test**

Run:

```bash
./gradlew --no-build-cache testDebugUnitTest --tests 'com.nexio.tv.architecture.LegacyPosterRatingsBooleanBoundaryTest'
```

Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/remote/supabase/AccountSyncModels.kt app/src/main/java/com/nexio/tv/core/sync/AccountConfigSyncContract.kt app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt
git commit -m "refactor: sync poster ratings provider selectors"
```

---

## Task 5: Update Sync Contract Tests

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/core/sync/AccountConfigSyncContractTest.kt`

- [ ] **Step 1: Replace boolean test payloads**

Replace any construction like:

```kotlin
posterRatings = PosterRatingsSyncSettings(rpdbEnabled = true, topPostersEnabled = true)
```

with selector payloads:

```kotlin
posterRatings = PosterRatingsSyncSettings(
    posterProvider = ArtworkProviderChoiceKey.RPDB.value,
    logoProvider = ArtworkProviderChoiceKey.DEFAULT.value,
    backdropProvider = ArtworkProviderChoiceKey.DEFAULT.value,
    thumbnailProvider = ArtworkProviderChoiceKey.TOP_POSTERS.value
)
```

Use `ArtworkProviderChoiceKey.TOP_POSTERS.value` for tests that specifically assert Top-Posters poster selection.

- [ ] **Step 2: Replace old no-boolean-setter verification**

Delete verifications like:

```kotlin
coVerify(exactly = 0) { posterRatingsSettingsDataStore.setRpdbEnabled(any()) }
coVerify(exactly = 0) { posterRatingsSettingsDataStore.setTopPostersEnabled(any()) }
```

Replace with selector-specific assertions:

```kotlin
coVerify(exactly = 1) {
    posterRatingsSettingsDataStore.setProviderSelection(
        ArtworkTypeKey.POSTER,
        ArtworkProviderChoiceKey.RPDB
    )
}
coVerify(exactly = 1) {
    posterRatingsSettingsDataStore.setProviderSelection(
        ArtworkTypeKey.THUMBNAIL,
        ArtworkProviderChoiceKey.TOP_POSTERS
    )
}
```

- [ ] **Step 3: Add v9 skip-apply test**

Add this test to `AccountConfigSyncContractTest`:

```kotlin
@Test
fun `schema v9 poster ratings payload does not overwrite provider selectors`() = runTest {
    val posterRatingsSettingsDataStore = mockk<PosterRatingsSettingsDataStore>(relaxed = true)
    val payload = buildAccountConfigSyncPayload(
        integrations = IntegrationSettings(
            posterRatings = PosterRatingsSyncSettings(
                posterProvider = ArtworkProviderChoiceKey.RPDB.value,
                logoProvider = ArtworkProviderChoiceKey.DEFAULT.value,
                backdropProvider = ArtworkProviderChoiceKey.DEFAULT.value,
                thumbnailProvider = ArtworkProviderChoiceKey.DEFAULT.value
            )
        ),
        heroCatalogKeys = emptyList(),
        homeCatalogOrderKeys = emptyList(),
        disabledHomeCatalogKeys = emptyList(),
        traktCatalogEnabledSet = emptyList(),
        traktCatalogOrder = emptyList(),
        traktSelectedPopularListKeys = emptyList(),
        simklCatalogEnabledSet = emptyList(),
        simklCatalogOrder = emptyList(),
        mdbListHiddenPersonalListKeys = emptyList(),
        mdbListSelectedTopListKeys = emptyList(),
        mdbListCatalogOrder = emptyList(),
        trackingProvider = TrackingProvider.TRAKT,
        formatter = FormatterSyncSettings()
    ).copy(
        schemaVersion = 9
    )

    applyAccountConfigSyncSettings(
        settings = payload,
        layoutPreferenceDataStore = mockk(relaxed = true),
        tmdbSettingsDataStore = mockk(relaxed = true),
        mdbListSettingsDataStore = mockk(relaxed = true),
        omdbSettingsDataStore = mockk(relaxed = true),
        theIntroDbSettingsDataStore = mockk(relaxed = true),
        animeSkipSettingsDataStore = mockk(relaxed = true),
        subtitleTranslationSettingsDataStore = mockk(relaxed = true),
        posterRatingsSettingsDataStore = posterRatingsSettingsDataStore,
        traktSettingsDataStore = mockk(relaxed = true),
        simklSettingsDataStore = mockk(relaxed = true),
        tmdbCatalogSettingsDataStore = mockk(relaxed = true),
        kitsuCatalogSettingsDataStore = mockk(relaxed = true),
        homeRailOrderStore = mockk(relaxed = true),
        playerSettingsDataStore = mockk(relaxed = true)
    )

    coVerify(exactly = 0) {
        posterRatingsSettingsDataStore.setProviderSelection(any(), any())
    }
}
```

- [ ] **Step 4: Add v10 selector apply test**

Add this test:

```kotlin
@Test
fun `schema v10 poster ratings payload applies provider selectors`() = runTest {
    val posterRatingsSettingsDataStore = mockk<PosterRatingsSettingsDataStore>(relaxed = true)
    val payload = buildAccountConfigSyncPayload(
        integrations = IntegrationSettings(
            posterRatings = PosterRatingsSyncSettings(
                posterProvider = ArtworkProviderChoiceKey.TOP_POSTERS.value,
                logoProvider = ArtworkProviderChoiceKey.DEFAULT.value,
                backdropProvider = ArtworkProviderChoiceKey.DEFAULT.value,
                thumbnailProvider = ArtworkProviderChoiceKey.TOP_POSTERS.value
            )
        ),
        heroCatalogKeys = emptyList(),
        homeCatalogOrderKeys = emptyList(),
        disabledHomeCatalogKeys = emptyList(),
        traktCatalogEnabledSet = emptyList(),
        traktCatalogOrder = emptyList(),
        traktSelectedPopularListKeys = emptyList(),
        simklCatalogEnabledSet = emptyList(),
        simklCatalogOrder = emptyList(),
        mdbListHiddenPersonalListKeys = emptyList(),
        mdbListSelectedTopListKeys = emptyList(),
        mdbListCatalogOrder = emptyList(),
        trackingProvider = TrackingProvider.TRAKT,
        formatter = FormatterSyncSettings()
    ).copy(
        schemaVersion = 10
    )

    applyAccountConfigSyncSettings(
        settings = payload,
        layoutPreferenceDataStore = mockk(relaxed = true),
        tmdbSettingsDataStore = mockk(relaxed = true),
        mdbListSettingsDataStore = mockk(relaxed = true),
        omdbSettingsDataStore = mockk(relaxed = true),
        theIntroDbSettingsDataStore = mockk(relaxed = true),
        animeSkipSettingsDataStore = mockk(relaxed = true),
        subtitleTranslationSettingsDataStore = mockk(relaxed = true),
        posterRatingsSettingsDataStore = posterRatingsSettingsDataStore,
        traktSettingsDataStore = mockk(relaxed = true),
        simklSettingsDataStore = mockk(relaxed = true),
        tmdbCatalogSettingsDataStore = mockk(relaxed = true),
        kitsuCatalogSettingsDataStore = mockk(relaxed = true),
        homeRailOrderStore = mockk(relaxed = true),
        playerSettingsDataStore = mockk(relaxed = true)
    )

    coVerify(exactly = 1) {
        posterRatingsSettingsDataStore.setProviderSelection(
            ArtworkTypeKey.POSTER,
            ArtworkProviderChoiceKey.TOP_POSTERS
        )
    }
    coVerify(exactly = 1) {
        posterRatingsSettingsDataStore.setProviderSelection(
            ArtworkTypeKey.THUMBNAIL,
            ArtworkProviderChoiceKey.TOP_POSTERS
        )
    }
}
```

- [ ] **Step 5: Run sync contract tests**

Run:

```bash
./gradlew --no-build-cache testDebugUnitTest --tests 'com.nexio.tv.core.sync.AccountConfigSyncContractTest'
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/test/java/com/nexio/tv/core/sync/AccountConfigSyncContractTest.kt
git commit -m "test: cover poster ratings selector sync contract"
```

---

## Task 6: Update Premium Poster Tests To Use Selector Settings Directly

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/data/integration/posters/PremiumPosterMetadataProviderAdapterStableIdTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/settings/PosterRatingsSettingsViewModelTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/poster/PosterRatingsUrlResolverTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/tmdb/TmdbMetadataPerformanceTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/tvdb/TvdbMetadataServiceTest.kt`

- [ ] **Step 1: Remove legacy imports from premium poster stable-id test**

In `PremiumPosterMetadataProviderAdapterStableIdTest.kt`, remove:

```kotlin
import com.nexio.tv.domain.model.PosterRatingsSettings
import com.nexio.tv.domain.model.toArtworkProviderSettings
```

Add imports if they are missing:

```kotlin
import com.nexio.tv.domain.model.ArtworkProviderChoiceKey
import com.nexio.tv.domain.model.ArtworkProviderSelectionSettings
import com.nexio.tv.domain.model.ArtworkProviderSettings
```

- [ ] **Step 2: Add direct settings helpers**

In `PremiumPosterMetadataProviderAdapterStableIdTest.kt`, replace the helper that accepts `PosterRatingsSettings` with these helpers:

```kotlin
    private fun topPostersSettings(apiKey: String = "TP-key"): ArtworkProviderSettings =
        ArtworkProviderSettings(
            topPostersApiKey = apiKey,
            selection = ArtworkProviderSelectionSettings(
                posterProvider = ArtworkProviderChoiceKey.TOP_POSTERS
            )
        )

    private fun rpdbSettings(apiKey: String = "rpdb-key"): ArtworkProviderSettings =
        ArtworkProviderSettings(
            rpdbApiKey = apiKey,
            selection = ArtworkProviderSelectionSettings(
                posterProvider = ArtworkProviderChoiceKey.RPDB
            )
        )
```

Then update calls:

```kotlin
resolver(
    PosterRatingsSettings(topPostersEnabled = true, topPostersApiKey = "TP-key")
        .toArtworkProviderSettings(),
    cache
)
```

to:

```kotlin
resolver(topPostersSettings("TP-key"), cache)
```

And update calls:

```kotlin
resolver(
    PosterRatingsSettings(rpdbEnabled = true, rpdbApiKey = "rpdb-key")
        .toArtworkProviderSettings(),
    cache
)
```

to:

```kotlin
resolver(rpdbSettings("rpdb-key"), cache)
```

- [ ] **Step 3: Update remaining test defaults**

Run:

```bash
rg -n "PosterRatingsSettings\\(|toArtworkProviderSettings|rpdbEnabled|topPostersEnabled" app/src/test/java
```

Expected remaining matches:

```text
app/src/test/java/com/nexio/tv/data/local/PosterRatingsSettingsDataStoreTest.kt
```

Those remaining matches are allowed because they test local upgrade migration only.

- [ ] **Step 4: Run premium poster tests**

Run:

```bash
./gradlew --no-build-cache testDebugUnitTest \
  --tests 'com.nexio.tv.data.integration.posters.PremiumPosterMetadataProviderAdapterStableIdTest' \
  --tests 'com.nexio.tv.core.poster.PosterRatingsUrlResolverTest'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/com/nexio/tv/data/integration/posters/PremiumPosterMetadataProviderAdapterStableIdTest.kt
git commit -m "test: use artwork provider settings in premium poster tests"
```

---

## Task 7: Final Search, Architecture Tests, And Targeted Suite

**Files:**
- No planned code changes unless this task finds a real violation.

- [ ] **Step 1: Run active-source legacy boolean search**

Run:

```bash
rg -n "PosterRatingsSettings\\(|toArtworkProviderSettings|setRpdbEnabled|setTopPostersEnabled|rpdbEnabled|topPostersEnabled" app/src/main/java/com/nexio/tv
```

Expected allowed output only:

```text
app/src/main/java/com/nexio/tv/data/local/PosterRatingsSettingsDataStore.kt:<line>: private val rpdbEnabledKey = booleanPreferencesKey("poster_ratings_rpdb_enabled")
app/src/main/java/com/nexio/tv/data/local/PosterRatingsSettingsDataStore.kt:<line>: private val topPostersEnabledKey = booleanPreferencesKey("poster_ratings_top_enabled")
app/src/main/java/com/nexio/tv/data/local/PosterRatingsSettingsDataStore.kt:<line>: this[rpdbEnabledKey] == true -> ArtworkProviderChoiceKey.RPDB
app/src/main/java/com/nexio/tv/data/local/PosterRatingsSettingsDataStore.kt:<line>: this[topPostersEnabledKey] == true -> ArtworkProviderChoiceKey.TOP_POSTERS
app/src/main/java/com/nexio/tv/data/local/PosterRatingsSettingsDataStore.kt:<line>: suspend fun writeLegacyBooleanPreferencesForTest(...)
```

If any other main-source file appears, remove that active dependency and rerun the search.

- [ ] **Step 2: Run architecture guardrail**

Run:

```bash
./gradlew --no-build-cache testDebugUnitTest --tests 'com.nexio.tv.architecture.LegacyPosterRatingsBooleanBoundaryTest'
```

Expected: PASS.

- [ ] **Step 3: Run settings and sync tests**

Run:

```bash
./gradlew --no-build-cache testDebugUnitTest \
  --tests 'com.nexio.tv.data.local.PosterRatingsSettingsDataStoreTest' \
  --tests 'com.nexio.tv.domain.model.ArtworkProviderSettingsTest' \
  --tests 'com.nexio.tv.ui.screens.settings.PosterRatingsSettingsViewModelTest' \
  --tests 'com.nexio.tv.core.sync.AccountConfigSyncContractTest'
```

Expected: PASS.

- [ ] **Step 4: Run premium artwork architecture bundle**

Run:

```bash
./gradlew --no-build-cache testDebugUnitTest \
  --tests 'com.nexio.tv.architecture.IntegrationProviderContractRegistryTest' \
  --tests 'com.nexio.tv.architecture.RawRemoteArtworkUrlBoundaryTest' \
  --tests 'com.nexio.tv.architecture.LegacyPosterRatingsBooleanBoundaryTest' \
  --tests 'com.nexio.tv.core.artwork.ArtworkRouterTest' \
  --tests 'com.nexio.tv.core.artwork.ArtworkProviderRegistryTest' \
  --tests 'com.nexio.tv.core.artwork.ArtworkProviderCapabilityResolverTest' \
  --tests 'com.nexio.tv.core.metadata.router.MetadataRouterFacadeTest' \
  --tests 'com.nexio.tv.core.poster.PosterRatingsUrlResolverTest' \
  --tests 'com.nexio.tv.ui.screens.detail.EpisodeRatingBadgeSupportTest'
```

Expected: PASS.

- [ ] **Step 5: Confirm working tree**

Run:

```bash
git status --short
```

Expected: only files changed by this cleanup. Do not stage unrelated local files such as `catalogs.png`, `review-dossier/*`, `tmp/`, or `media`.

- [ ] **Step 6: Final commit if Task 7 made code/test fixes**

If Task 7 required changes, commit them:

```bash
git add app/src/main/java app/src/test/java
git commit -m "test: enforce poster ratings selector architecture"
```

If Task 7 required no changes, do not create an empty commit.

---

## Acceptance Criteria

- `PosterRatingsSettings` data class no longer exists in active source.
- `rpdbEnabled` and `topPostersEnabled` no longer exist in active domain, UI, resolver, account-sync DTO, account-sync contract, or account-sync service code.
- `PosterRatingsSettingsDataStore` is the only production file allowed to reference old boolean preference keys.
- Old local DataStore installs still migrate:
  - `poster_ratings_rpdb_enabled=true` maps to `selection.posterProvider=rpdb`.
  - `poster_ratings_top_enabled=true` maps to `selection.posterProvider=top_posters`.
  - Explicit selector keys override old boolean keys.
- Account sync v10 emits and applies:
  - `posterProvider`
  - `logoProvider`
  - `backdropProvider`
  - `thumbnailProvider`
- Account sync payloads with `schemaVersion < 10` do not apply poster-ratings provider selections.
- Existing premium artwork guardrails still pass:
  - no raw premium URL leakage to UI/domain metadata models
  - `topposters.thumbnail` remains active runtime-covered
  - rejected candidate traces remain available

---

## Self-Review

**Spec coverage:** The plan removes active boolean compatibility from domain/runtime/UI/sync, keeps only local DataStore upgrade migration, and replaces sync compatibility with schema-version gating.

**Placeholder scan:** No task uses open-ended placeholders. Test examples call the existing `buildAccountConfigSyncPayload` helper and override `schemaVersion` with `copy`.

**Type consistency:** Selector fields use existing `ArtworkProviderChoiceKey`, `ArtworkProviderSelectionSettings`, `ArtworkProviderSettings`, and `ArtworkTypeKey`. Account sync stores selector values as strings using `ArtworkProviderChoiceKey.value` and reads them with `ArtworkProviderChoiceKey.fromStored`.
