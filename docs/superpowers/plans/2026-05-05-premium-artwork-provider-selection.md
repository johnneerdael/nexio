# Premium Artwork Provider Selection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the global premium poster provider toggle model with per-artwork-type provider selection, API-derived Top Posters entitlements, and Top Posters episode thumbnails that suppress local ratings only when the selected thumbnail embeds ratings.

**Architecture:** Store provider API keys separately from provider usage choices. A shared `ArtworkProviderRegistry` computes capabilities from configured keys and cached entitlement snapshots, `ArtworkRouter` applies the selected provider per `ArtworkType`, and Top Posters validation, posters, and thumbnails all flow through `IntegrationRuntime`. Episode UI reads display hints from the selected `ArtworkDisplayRef` instead of checking global Top Posters settings.

**Tech Stack:** Android Kotlin, Jetpack DataStore Preferences, Hilt, Retrofit/OkHttp, IntegrationRuntime, Coil, Jetpack Compose for TV, JUnit, MockK, Robolectric.

---

## Scope

Implement now:

- Per-artwork-type provider choices for `POSTER`, `LOGO`, `BACKDROP`, and `THUMBNAIL`.
- Key-presence provider configuration for RPDB and Top Posters with no enabled/disabled toggles.
- Migration from existing RPDB and Top Posters enabled toggles into initial poster selection.
- Top Posters entitlement verification via `topposters.key_validation` through `IntegrationRuntime`, cached for 24 hours.
- Top Posters poster selection through the new selector model.
- Top Posters episode thumbnails when verified entitlement says `episode_thumbnails=true` and the user selects Top Posters for thumbnails.
- Thumbnail fallback to primary, preview, and placeholder artwork.
- Local episode rating overlay suppression only when selected thumbnail display hints say ratings are embedded.

Do not implement provider fetches for Fanart.tv, OpenPosterDB, AIORatings, RPDB logo, or RPDB backdrop in this plan. The models and registry must make those providers additive without changing the settings schema.

## File Structure

Create:

- `app/src/main/java/com/nexio/tv/domain/model/ArtworkProviderSettings.kt`  
  Owns serializable provider choices, API keys, Top Posters entitlement snapshot, and selection helpers.
- `app/src/main/java/com/nexio/tv/core/artwork/ArtworkProviderRegistry.kt`  
  Computes configured providers, per-type available choices, and provider capabilities.
- `app/src/main/java/com/nexio/tv/core/artwork/ArtworkExternalIdSelector.kt`  
  Centralizes provider-specific ID ordering for posters and thumbnails.
- `app/src/main/java/com/nexio/tv/data/integration/posters/TopPostersEntitlement.kt`  
  Parses `/auth/verify/{api_key}` responses into stable entitlement snapshots.
- `app/src/test/java/com/nexio/tv/domain/model/ArtworkProviderSettingsTest.kt`
- `app/src/test/java/com/nexio/tv/core/artwork/ArtworkProviderRegistryTest.kt`
- `app/src/test/java/com/nexio/tv/core/artwork/ArtworkExternalIdSelectorTest.kt`
- `app/src/test/java/com/nexio/tv/data/integration/posters/TopPostersEntitlementTest.kt`

Modify:

- `app/src/main/java/com/nexio/tv/domain/model/PosterRatingsSettings.kt`  
  Keep as a compatibility facade while call sites move to `ArtworkProviderSettings`.
- `app/src/main/java/com/nexio/tv/data/local/PosterRatingsSettingsDataStore.kt`  
  Emit the new settings model, preserve old preference keys for migration, add selection and entitlement keys.
- `app/src/main/java/com/nexio/tv/data/repository/ProviderSettingsRepository.kt`  
  Return typed validation results for Top Posters and save entitlement snapshots through the DataStore call path.
- `app/src/main/java/com/nexio/tv/data/remote/api/PosterRatingsApi.kt`  
  Keep `GET auth/verify/{apiKey}` and stop parsing entitlement with string contains checks.
- `app/src/main/java/com/nexio/tv/data/integration/posters/TopPostersIntegrationProvider.kt`  
  Validate keys through runtime with 24-hour cache and fetch thumbnails through runtime.
- `app/src/main/java/com/nexio/tv/core/integration/IntegrationApiShapes.kt`  
  Add `PosterApiShapes.TOP_POSTERS_THUMBNAIL = "topposters.thumbnail"` and mirror in `ArtworkApiShapes`.
- `app/src/main/java/com/nexio/tv/core/artwork/ArtworkModels.kt`  
  Add `ArtworkDisplayHints`, persist selected-display hints, and add provider-template path params for thumbnail season/episode routing.
- `app/src/main/java/com/nexio/tv/core/artwork/ArtworkRouter.kt`  
  Replace `activePremiumProvider` with per-type provider selections.
- `app/src/main/java/com/nexio/tv/core/artwork/ArtworkCacheKeys.kt`  
  Add provider-selection and thumbnail parameter cache key helpers.
- `app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRepository.kt`  
  Preserve display hints when materializing runtime assets.
- `app/src/main/java/com/nexio/tv/core/image/IntegrationPosterFetcher.kt`  
  Route Top Posters thumbnail runtime requests without a parallel fetch path.
- `app/src/main/java/com/nexio/tv/core/image/PosterIntegrationRequest.kt`  
  Add `TopPostersThumbnailRequest` beside the existing `PosterIntegrationRequest`.
- `app/src/main/java/com/nexio/tv/core/poster/PosterRatingsUrlResolver.kt`  
  Convert legacy global-provider logic into selection-aware premium poster URL generation.
- `app/src/main/java/com/nexio/tv/data/integration/posters/RpdbMetadataProviderAdapter.kt`
- `app/src/main/java/com/nexio/tv/data/integration/posters/TopPostersMetadataProviderAdapter.kt`
- `app/src/main/java/com/nexio/tv/data/integration/posters/PosterAdapterUtils.kt`
- `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt`
- `app/src/main/java/com/nexio/tv/ui/screens/settings/PosterRatingsSettingsViewModel.kt`
- `app/src/main/java/com/nexio/tv/ui/screens/settings/PosterRatingsSettingsScreen.kt`
- `app/src/main/java/com/nexio/tv/ui/screens/detail/EpisodesSection.kt`
- `app/src/main/java/com/nexio/tv/domain/model/Meta.kt`
- `app/src/main/java/com/nexio/tv/domain/model/HomeDisplayMetadata.kt`
- Existing tests under `app/src/test/java/com/nexio/tv/core/artwork`, `app/src/test/java/com/nexio/tv/core/poster`, `app/src/test/java/com/nexio/tv/data/integration/posters`, `app/src/test/java/com/nexio/tv/ui/screens/settings`, and `app/src/test/java/com/nexio/tv/metadata/audit`.

## Task 1: Add Artwork Provider Settings Model

**Files:**

- Create: `app/src/main/java/com/nexio/tv/domain/model/ArtworkProviderSettings.kt`
- Modify: `app/src/main/java/com/nexio/tv/domain/model/PosterRatingsSettings.kt`
- Test: `app/src/test/java/com/nexio/tv/domain/model/ArtworkProviderSettingsTest.kt`

- [ ] **Step 1: Write failing model tests**

Add:

```kotlin
package com.nexio.tv.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArtworkProviderSettingsTest {
    @Test
    fun `no keys gives default choices only`() {
        val settings = ArtworkProviderSettings()

        assertEquals(ArtworkProviderChoiceKey.DEFAULT, settings.selection.providerFor(ArtworkTypeKey.POSTER))
        assertFalse(settings.hasRpdbKey)
        assertFalse(settings.hasTopPostersKey)
        assertFalse(settings.topPostersCanProvideThumbnails)
    }

    @Test
    fun `top posters premium entitlement enables thumbnail capability`() {
        val settings = ArtworkProviderSettings(
            topPostersApiKey = "TP-test",
            topPostersEntitlement = TopPostersEntitlementSnapshot(
                valid = true,
                isActive = true,
                tier = 1,
                tierName = "Premium",
                episodeThumbnails = true,
                verifiedAtMs = 1_000L,
                expiresAtMs = 86_401_000L
            )
        )

        assertTrue(settings.hasTopPostersKey)
        assertTrue(settings.topPostersCanProvideThumbnails)
    }

    @Test
    fun `unverified top posters key exposes poster capability only`() {
        val settings = ArtworkProviderSettings(topPostersApiKey = "TP-test")

        assertTrue(settings.hasTopPostersKey)
        assertFalse(settings.topPostersCanProvideThumbnails)
    }

    @Test
    fun `inactive top posters entitlement does not enable thumbnails`() {
        val settings = ArtworkProviderSettings(
            topPostersApiKey = "TP-test",
            topPostersEntitlement = TopPostersEntitlementSnapshot(
                valid = true,
                isActive = false,
                tier = 1,
                tierName = "Premium",
                episodeThumbnails = true,
                verifiedAtMs = 1_000L,
                expiresAtMs = 86_401_000L
            )
        )

        assertFalse(settings.topPostersCanProvideThumbnails)
    }

    @Test
    fun `legacy enabled rpdb migrates to rpdb poster selection`() {
        val legacy = PosterRatingsSettings(
            rpdbEnabled = true,
            rpdbApiKey = "rpdb-key",
            topPostersEnabled = false,
            topPostersApiKey = "TP-unused"
        )

        val migrated = legacy.toArtworkProviderSettings()

        assertEquals(ArtworkProviderChoiceKey.RPDB, migrated.selection.posterProvider)
        assertEquals("rpdb-key", migrated.rpdbApiKey)
        assertEquals("TP-unused", migrated.topPostersApiKey)
    }

    @Test
    fun `legacy disabled provider with key keeps key but defaults selection`() {
        val legacy = PosterRatingsSettings(
            rpdbEnabled = false,
            rpdbApiKey = "rpdb-key",
            topPostersEnabled = false,
            topPostersApiKey = "TP-key"
        )

        val migrated = legacy.toArtworkProviderSettings()

        assertEquals(ArtworkProviderChoiceKey.DEFAULT, migrated.selection.posterProvider)
        assertEquals("rpdb-key", migrated.rpdbApiKey)
        assertEquals("TP-key", migrated.topPostersApiKey)
    }
}
```

- [ ] **Step 2: Run model tests and verify they fail**

Run:

```bash
./gradlew --no-build-cache testDebugUnitTest --tests 'com.nexio.tv.domain.model.ArtworkProviderSettingsTest'
```

Expected: compilation fails because `ArtworkProviderSettings`, `ArtworkProviderChoiceKey`, `ArtworkTypeKey`, `TopPostersEntitlementSnapshot`, and `toArtworkProviderSettings` do not exist.

- [ ] **Step 3: Add settings model**

Create `app/src/main/java/com/nexio/tv/domain/model/ArtworkProviderSettings.kt`:

```kotlin
package com.nexio.tv.domain.model

enum class ArtworkTypeKey {
    POSTER,
    LOGO,
    BACKDROP,
    THUMBNAIL
}

@JvmInline
value class ArtworkProviderChoiceKey(val value: String) {
    init { require(value.isNotBlank()) { "ArtworkProviderChoiceKey must not be blank" } }

    override fun toString(): String = value

    companion object {
        val DEFAULT = ArtworkProviderChoiceKey("DEFAULT")
        val RPDB = ArtworkProviderChoiceKey("RPDB")
        val TOP_POSTERS = ArtworkProviderChoiceKey("TOP_POSTERS")

        fun fromStored(value: String?): ArtworkProviderChoiceKey =
            when (value?.trim().orEmpty()) {
                RPDB.value -> RPDB
                TOP_POSTERS.value -> TOP_POSTERS
                else -> DEFAULT
            }
    }
}

data class ArtworkProviderSelectionSettings(
    val posterProvider: ArtworkProviderChoiceKey = ArtworkProviderChoiceKey.DEFAULT,
    val logoProvider: ArtworkProviderChoiceKey = ArtworkProviderChoiceKey.DEFAULT,
    val backdropProvider: ArtworkProviderChoiceKey = ArtworkProviderChoiceKey.DEFAULT,
    val thumbnailProvider: ArtworkProviderChoiceKey = ArtworkProviderChoiceKey.DEFAULT
) {
    fun providerFor(type: ArtworkTypeKey): ArtworkProviderChoiceKey =
        when (type) {
            ArtworkTypeKey.POSTER -> posterProvider
            ArtworkTypeKey.LOGO -> logoProvider
            ArtworkTypeKey.BACKDROP -> backdropProvider
            ArtworkTypeKey.THUMBNAIL -> thumbnailProvider
        }

    fun withProvider(type: ArtworkTypeKey, provider: ArtworkProviderChoiceKey): ArtworkProviderSelectionSettings =
        when (type) {
            ArtworkTypeKey.POSTER -> copy(posterProvider = provider)
            ArtworkTypeKey.LOGO -> copy(logoProvider = provider)
            ArtworkTypeKey.BACKDROP -> copy(backdropProvider = provider)
            ArtworkTypeKey.THUMBNAIL -> copy(thumbnailProvider = provider)
        }
}

data class TopPostersEntitlementSnapshot(
    val valid: Boolean,
    val isActive: Boolean,
    val tier: Int?,
    val tierName: String?,
    val episodeThumbnails: Boolean,
    val verifiedAtMs: Long,
    val expiresAtMs: Long
) {
    val isFreshAtNow: Boolean
        get() = System.currentTimeMillis() < expiresAtMs

    val allowsEpisodeThumbnails: Boolean
        get() = valid && isActive && tier == 1 && episodeThumbnails
}

data class ArtworkProviderSettings(
    val rpdbApiKey: String = "",
    val topPostersApiKey: String = "",
    val selection: ArtworkProviderSelectionSettings = ArtworkProviderSelectionSettings(),
    val topPostersEntitlement: TopPostersEntitlementSnapshot? = null
) {
    val hasRpdbKey: Boolean get() = rpdbApiKey.isNotBlank()
    val hasTopPostersKey: Boolean get() = topPostersApiKey.isNotBlank()

    val topPostersCanProvideThumbnails: Boolean
        get() = hasTopPostersKey && topPostersEntitlement?.allowsEpisodeThumbnails == true
}
```

- [ ] **Step 4: Add compatibility migration helper**

Modify `app/src/main/java/com/nexio/tv/domain/model/PosterRatingsSettings.kt` to keep existing call sites compiling while the next tasks migrate them:

```kotlin
fun PosterRatingsSettings.toArtworkProviderSettings(): ArtworkProviderSettings {
    val selectedPosterProvider = when {
        rpdbEnabled && rpdbApiKey.isNotBlank() -> ArtworkProviderChoiceKey.RPDB
        topPostersEnabled && topPostersApiKey.isNotBlank() -> ArtworkProviderChoiceKey.TOP_POSTERS
        else -> ArtworkProviderChoiceKey.DEFAULT
    }

    return ArtworkProviderSettings(
        rpdbApiKey = rpdbApiKey,
        topPostersApiKey = topPostersApiKey,
        selection = ArtworkProviderSelectionSettings(posterProvider = selectedPosterProvider)
    )
}
```

- [ ] **Step 5: Run model tests and verify they pass**

Run:

```bash
./gradlew --no-build-cache testDebugUnitTest --tests 'com.nexio.tv.domain.model.ArtworkProviderSettingsTest'
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit Task 1**

Run:

```bash
git add app/src/main/java/com/nexio/tv/domain/model/ArtworkProviderSettings.kt app/src/main/java/com/nexio/tv/domain/model/PosterRatingsSettings.kt app/src/test/java/com/nexio/tv/domain/model/ArtworkProviderSettingsTest.kt
git commit -m "feat: add artwork provider settings model"
```

Expected: commit succeeds with only the three listed paths staged.

## Task 2: Migrate DataStore From Toggles To Selections

**Files:**

- Modify: `app/src/main/java/com/nexio/tv/data/local/PosterRatingsSettingsDataStore.kt`
- Test: `app/src/test/java/com/nexio/tv/data/local/PosterRatingsSettingsDataStoreTest.kt`

- [ ] **Step 1: Write failing DataStore migration tests**

Create `app/src/test/java/com/nexio/tv/data/local/PosterRatingsSettingsDataStoreTest.kt` with:

```kotlin
@Test
fun `legacy enabled top posters key migrates to top posters poster selection`() = runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val store = PosterRatingsSettingsDataStore(context)

    store.writeLegacyForTest(
        rpdbEnabled = false,
        rpdbApiKey = "",
        topPostersEnabled = true,
        topPostersApiKey = "TP-key"
    )

    val settings = store.settings.first()

    assertEquals("TP-key", settings.topPostersApiKey)
    assertEquals(ArtworkProviderChoiceKey.TOP_POSTERS, settings.selection.posterProvider)
    assertEquals(ArtworkProviderChoiceKey.DEFAULT, settings.selection.thumbnailProvider)
}

@Test
fun `setting poster provider does not disable provider keys`() = runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val store = PosterRatingsSettingsDataStore(context)

    store.setRpdbApiKey("rpdb-key")
    store.setTopPostersApiKey("TP-key")
    store.setProviderSelection(ArtworkTypeKey.POSTER, ArtworkProviderChoiceKey.RPDB)
    store.setProviderSelection(ArtworkTypeKey.POSTER, ArtworkProviderChoiceKey.TOP_POSTERS)

    val settings = store.settings.first()

    assertEquals("rpdb-key", settings.rpdbApiKey)
    assertEquals("TP-key", settings.topPostersApiKey)
    assertEquals(ArtworkProviderChoiceKey.TOP_POSTERS, settings.selection.posterProvider)
}

@Test
fun `top posters entitlement snapshot persists`() = runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val store = PosterRatingsSettingsDataStore(context)
    val snapshot = TopPostersEntitlementSnapshot(
        valid = true,
        isActive = true,
        tier = 1,
        tierName = "Premium",
        episodeThumbnails = true,
        verifiedAtMs = 100L,
        expiresAtMs = 200L
    )

    store.setTopPostersEntitlement(snapshot)

    assertEquals(snapshot, store.settings.first().topPostersEntitlement)
}
```

- [ ] **Step 2: Run DataStore tests and verify they fail**

Run:

```bash
./gradlew --no-build-cache testDebugUnitTest --tests 'com.nexio.tv.data.local.PosterRatingsSettingsDataStoreTest'
```

Expected: compilation fails because `settings` still emits `PosterRatingsSettings`, selection setters do not exist, and the entitlement snapshot is not persisted.

- [ ] **Step 3: Change DataStore to emit `ArtworkProviderSettings`**

In `PosterRatingsSettingsDataStore`, keep the existing preference name `poster_ratings_settings`, keep old keys for one-read migration, add new keys:

```kotlin
private val posterProviderKey = stringPreferencesKey("artwork_provider_poster")
private val logoProviderKey = stringPreferencesKey("artwork_provider_logo")
private val backdropProviderKey = stringPreferencesKey("artwork_provider_backdrop")
private val thumbnailProviderKey = stringPreferencesKey("artwork_provider_thumbnail")
private val topPostersEntitlementValidKey = booleanPreferencesKey("topposters_entitlement_valid")
private val topPostersEntitlementActiveKey = booleanPreferencesKey("topposters_entitlement_active")
private val topPostersEntitlementTierKey = stringPreferencesKey("topposters_entitlement_tier")
private val topPostersEntitlementTierNameKey = stringPreferencesKey("topposters_entitlement_tier_name")
private val topPostersEntitlementEpisodeThumbnailsKey = booleanPreferencesKey("topposters_entitlement_episode_thumbnails")
private val topPostersEntitlementVerifiedAtMsKey = stringPreferencesKey("topposters_entitlement_verified_at_ms")
private val topPostersEntitlementExpiresAtMsKey = stringPreferencesKey("topposters_entitlement_expires_at_ms")
```

Update `settings` mapping to:

```kotlin
val settings: Flow<ArtworkProviderSettings> = dataStore.data.map { prefs ->
    val legacy = PosterRatingsSettings(
        rpdbEnabled = prefs[rpdbEnabledKey] ?: false,
        rpdbApiKey = prefs[rpdbApiKeyKey] ?: "",
        topPostersEnabled = prefs[topPostersEnabledKey] ?: false,
        topPostersApiKey = prefs[topPostersApiKeyKey] ?: ""
    ).toArtworkProviderSettings()

    val selection = ArtworkProviderSelectionSettings(
        posterProvider = prefs.providerChoice(posterProviderKey) ?: legacy.selection.posterProvider,
        logoProvider = prefs.providerChoice(logoProviderKey) ?: ArtworkProviderChoiceKey.DEFAULT,
        backdropProvider = prefs.providerChoice(backdropProviderKey) ?: ArtworkProviderChoiceKey.DEFAULT,
        thumbnailProvider = prefs.providerChoice(thumbnailProviderKey) ?: ArtworkProviderChoiceKey.DEFAULT
    )

    ArtworkProviderSettings(
        rpdbApiKey = prefs[rpdbApiKeyKey] ?: "",
        topPostersApiKey = prefs[topPostersApiKeyKey] ?: "",
        selection = selection,
        topPostersEntitlement = prefs.topPostersEntitlementSnapshot()
    )
}
```

Add private mapping helpers:

```kotlin
private fun Preferences.providerChoice(key: Preferences.Key<String>): ArtworkProviderChoiceKey? =
    this[key]?.let(ArtworkProviderChoiceKey::fromStored)

private fun Preferences.topPostersEntitlementSnapshot(): TopPostersEntitlementSnapshot? {
    val valid = this[topPostersEntitlementValidKey] ?: return null
    val isActive = this[topPostersEntitlementActiveKey] ?: false
    val verifiedAtMs = this[topPostersEntitlementVerifiedAtMsKey]?.toLongOrNull() ?: return null
    val expiresAtMs = this[topPostersEntitlementExpiresAtMsKey]?.toLongOrNull() ?: return null
    return TopPostersEntitlementSnapshot(
        valid = valid,
        isActive = isActive,
        tier = this[topPostersEntitlementTierKey]?.toIntOrNull(),
        tierName = this[topPostersEntitlementTierNameKey],
        episodeThumbnails = this[topPostersEntitlementEpisodeThumbnailsKey] ?: false,
        verifiedAtMs = verifiedAtMs,
        expiresAtMs = expiresAtMs
    )
}
```

- [ ] **Step 4: Replace toggle mutators with selection mutator**

Remove public use of `setRpdbEnabled` and `setTopPostersEnabled`. Add:

```kotlin
suspend fun setProviderSelection(type: ArtworkTypeKey, provider: ArtworkProviderChoiceKey) {
    dataStore.edit { prefs ->
        val key = when (type) {
            ArtworkTypeKey.POSTER -> posterProviderKey
            ArtworkTypeKey.LOGO -> logoProviderKey
            ArtworkTypeKey.BACKDROP -> backdropProviderKey
            ArtworkTypeKey.THUMBNAIL -> thumbnailProviderKey
        }
        prefs[key] = provider.value
    }
}

suspend fun setTopPostersEntitlement(snapshot: TopPostersEntitlementSnapshot?) {
    dataStore.edit { prefs ->
        if (snapshot == null) {
            prefs.remove(topPostersEntitlementValidKey)
            prefs.remove(topPostersEntitlementActiveKey)
            prefs.remove(topPostersEntitlementTierKey)
            prefs.remove(topPostersEntitlementTierNameKey)
            prefs.remove(topPostersEntitlementEpisodeThumbnailsKey)
            prefs.remove(topPostersEntitlementVerifiedAtMsKey)
            prefs.remove(topPostersEntitlementExpiresAtMsKey)
        } else {
            prefs[topPostersEntitlementValidKey] = snapshot.valid
            prefs[topPostersEntitlementActiveKey] = snapshot.isActive
            snapshot.tier?.let { prefs[topPostersEntitlementTierKey] = it.toString() } ?: prefs.remove(topPostersEntitlementTierKey)
            snapshot.tierName?.let { prefs[topPostersEntitlementTierNameKey] = it } ?: prefs.remove(topPostersEntitlementTierNameKey)
            prefs[topPostersEntitlementEpisodeThumbnailsKey] = snapshot.episodeThumbnails
            prefs[topPostersEntitlementVerifiedAtMsKey] = snapshot.verifiedAtMs.toString()
            prefs[topPostersEntitlementExpiresAtMsKey] = snapshot.expiresAtMs.toString()
        }
    }
}
```

- [ ] **Step 5: Keep test-only legacy writer internal to tests**

Expose a test-only helper only in `androidTest` or guarded with `@VisibleForTesting`:

```kotlin
@VisibleForTesting
suspend fun writeLegacyForTest(
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
    }
}
```

- [ ] **Step 6: Run DataStore tests**

Run:

```bash
./gradlew --no-build-cache testDebugUnitTest --tests 'com.nexio.tv.data.local.PosterRatingsSettingsDataStoreTest'
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit Task 2**

Run:

```bash
git add app/src/main/java/com/nexio/tv/data/local/PosterRatingsSettingsDataStore.kt app/src/test/java/com/nexio/tv/data/local/PosterRatingsSettingsDataStoreTest.kt
git commit -m "feat: migrate premium artwork settings to provider selections"
```

Expected: commit succeeds with only DataStore paths staged.

## Task 3: Parse Top Posters Entitlements And Cache Validation Through Runtime

**Files:**

- Create: `app/src/main/java/com/nexio/tv/data/integration/posters/TopPostersEntitlement.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/integration/posters/TopPostersIntegrationProvider.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/repository/ProviderSettingsRepository.kt`
- Test: `app/src/test/java/com/nexio/tv/data/integration/posters/TopPostersEntitlementTest.kt`
- Test: `app/src/test/java/com/nexio/tv/data/integration/posters/TopPostersIntegrationProviderTest.kt`

- [ ] **Step 1: Write failing entitlement parser tests**

Add:

```kotlin
package com.nexio.tv.data.integration.posters

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TopPostersEntitlementTest {
    @Test
    fun `parses premium tier episode thumbnail entitlement`() {
        val json = """
            {
              "valid": true,
              "is_active": true,
              "tier": 1,
              "tier_name": "Premium",
              "tier_info": {
                "features": {
                  "episode_thumbnails": true
                }
              }
            }
        """.trimIndent()

        val snapshot = TopPostersEntitlementParser.parse(json, verifiedAtMs = 10L, ttlMs = 86_400_000L)

        assertTrue(snapshot.valid)
        assertTrue(snapshot.isActive)
        assertEquals(1, snapshot.tier)
        assertEquals("Premium", snapshot.tierName)
        assertTrue(snapshot.episodeThumbnails)
        assertEquals(86_400_010L, snapshot.expiresAtMs)
    }

    @Test
    fun `missing episode thumbnail feature defaults false`() {
        val json = """{"valid":true,"is_active":true,"tier":3,"tier_name":"Free"}"""

        val snapshot = TopPostersEntitlementParser.parse(json, verifiedAtMs = 10L, ttlMs = 86_400_000L)

        assertEquals(3, snapshot.tier)
        assertEquals(false, snapshot.episodeThumbnails)
    }
}
```

- [ ] **Step 2: Run parser tests and verify they fail**

Run:

```bash
./gradlew --no-build-cache testDebugUnitTest --tests 'com.nexio.tv.data.integration.posters.TopPostersEntitlementTest'
```

Expected: compilation fails because `TopPostersEntitlementParser` does not exist.

- [ ] **Step 3: Add parser using structured JSON**

Create `TopPostersEntitlement.kt`:

```kotlin
package com.nexio.tv.data.integration.posters

import com.nexio.tv.domain.model.TopPostersEntitlementSnapshot
import org.json.JSONObject

object TopPostersEntitlementParser {
    fun parse(body: String, verifiedAtMs: Long, ttlMs: Long): TopPostersEntitlementSnapshot {
        val root = JSONObject(body)
        val tierInfo = root.optJSONObject("tier_info")
        val features = tierInfo?.optJSONObject("features")
        return TopPostersEntitlementSnapshot(
            valid = root.optBoolean("valid", false),
            isActive = root.optBoolean("is_active", false),
            tier = root.optIntOrNull("tier"),
            tierName = root.optStringOrNull("tier_name"),
            episodeThumbnails = features?.optBoolean("episode_thumbnails", false) == true,
            verifiedAtMs = verifiedAtMs,
            expiresAtMs = verifiedAtMs + ttlMs
        )
    }
}

private fun JSONObject.optIntOrNull(name: String): Int? =
    if (has(name) && !isNull(name)) optInt(name) else null

private fun JSONObject.optStringOrNull(name: String): String? =
    if (has(name) && !isNull(name)) optString(name).takeIf { it.isNotBlank() } else null
```

- [ ] **Step 4: Change Top Posters provider validation return type**

In `TopPostersIntegrationProvider`, replace `validateApiKey(apiKey): Boolean` with:

```kotlin
companion object {
    const val TOP_POSTERS_ENTITLEMENT_TTL_MS: Long = 86_400_000L
}

suspend fun validateApiKey(apiKey: String, forceRefresh: Boolean = false): TopPostersEntitlementSnapshot? {
    val trimmed = apiKey.trim()
    if (trimmed.isBlank()) return null
    val hash = credentialHash(IntegrationProvider.TOP_POSTERS, trimmed)
    val verifiedAtMs = System.currentTimeMillis()
    val spec = IntegrationSpec(
        provider = IntegrationProvider.TOP_POSTERS,
        cacheKey = "topposters:validate:credentialHash:$hash",
        codec = StringIntegrationCodec,
        cachePolicy = if (forceRefresh) {
            IntegrationCachePolicy.Disabled
        } else {
            IntegrationCachePolicy.CacheFirst(
                ttlMs = TOP_POSTERS_ENTITLEMENT_TTL_MS,
                staleAfterExpiryMs = TOP_POSTERS_ENTITLEMENT_TTL_MS
            )
        },
        workClass = IntegrationWorkClass.USER_VISIBLE,
        apiShapeId = PosterApiShapes.TOP_POSTERS_KEY_VALIDATION,
        headerPolicyId = IntegrationHeaderPolicies.TOP_POSTERS_IMAGE_PATH_KEY_V1,
        operationKey = "topposters.key.validate",
        load = {
            runCatching { topPostersApi.verifyApiKey(trimmed) }
                .fold(
                    onSuccess = { response ->
                        val body = response.body()?.string().orEmpty()
                        if (!response.isSuccessful) {
                            IntegrationLoadResult.HttpError(response.code(), reason = "topposters_key_validation_failed")
                        } else {
                            IntegrationLoadResult.Success(body)
                        }
                    },
                    onFailure = { IntegrationLoadResult.NetworkError(it) }
                )
        }
    )
    val body = runtime.get(spec).valueOrNull() ?: return null
    return TopPostersEntitlementParser.parse(body, verifiedAtMs, TOP_POSTERS_ENTITLEMENT_TTL_MS)
}
```

- [ ] **Step 5: Update provider repository contract**

Change `ProviderSettingsRepository.validateTopPostersApiKey` to return `TopPostersEntitlementSnapshot?` and keep RPDB as Boolean:

```kotlin
suspend fun validateTopPostersApiKey(apiKey: String, forceRefresh: Boolean = false): TopPostersEntitlementSnapshot? =
    topPostersIntegrationProvider.validateApiKey(apiKey, forceRefresh)
```

Update callers in `PosterRatingsSettingsViewModel` in Task 10; for this task, keep Boolean-only tests compiling with this compatibility adapter:

```kotlin
suspend fun isTopPostersApiKeyValid(apiKey: String): Boolean =
    validateTopPostersApiKey(apiKey)?.valid == true
```

- [ ] **Step 6: Update runtime validation tests**

In `TopPostersIntegrationProviderTest`, assert:

```kotlin
assertEquals(PosterApiShapes.TOP_POSTERS_KEY_VALIDATION, specSlot.captured.apiShapeId)
assertTrue(specSlot.captured.cachePolicy is IntegrationCachePolicy.CacheFirst)
assertEquals("topposters.key.validate", specSlot.captured.operationKey)
```

Add one `forceRefresh=true` test:

```kotlin
assertTrue(specSlot.captured.cachePolicy is IntegrationCachePolicy.Disabled)
```

- [ ] **Step 7: Run entitlement and provider tests**

Run:

```bash
./gradlew --no-build-cache testDebugUnitTest --tests 'com.nexio.tv.data.integration.posters.TopPostersEntitlementTest' --tests 'com.nexio.tv.data.integration.posters.TopPostersIntegrationProviderTest'
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit Task 3**

Run:

```bash
git add app/src/main/java/com/nexio/tv/data/integration/posters/TopPostersEntitlement.kt app/src/main/java/com/nexio/tv/data/integration/posters/TopPostersIntegrationProvider.kt app/src/main/java/com/nexio/tv/data/repository/ProviderSettingsRepository.kt app/src/test/java/com/nexio/tv/data/integration/posters/TopPostersEntitlementTest.kt app/src/test/java/com/nexio/tv/data/integration/posters/TopPostersIntegrationProviderTest.kt
git commit -m "feat: cache top posters entitlement validation"
```

Expected: commit succeeds with only entitlement and provider validation paths staged.

## Task 4: Add Artwork Provider Registry

**Files:**

- Create: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkProviderRegistry.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkProviderCapabilityResolver.kt`
- Test: `app/src/test/java/com/nexio/tv/core/artwork/ArtworkProviderRegistryTest.kt`
- Test: `app/src/test/java/com/nexio/tv/core/artwork/ArtworkProviderCapabilityResolverTest.kt`

- [ ] **Step 1: Write failing registry tests**

Add:

```kotlin
package com.nexio.tv.core.artwork

import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.domain.model.ArtworkProviderChoiceKey
import com.nexio.tv.domain.model.ArtworkProviderSettings
import com.nexio.tv.domain.model.ArtworkTypeKey
import com.nexio.tv.domain.model.TopPostersEntitlementSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals

class ArtworkProviderRegistryTest {
    private val registry = ArtworkProviderRegistry()

    @Test
    fun `no keys exposes default choices only`() {
        val choices = registry.availableChoices(ArtworkType.POSTER, ArtworkProviderSettings())

        assertEquals(listOf(ArtworkProviderChoiceKey.DEFAULT), choices)
    }

    @Test
    fun `top posters free or pro exposes posters only`() {
        val settings = ArtworkProviderSettings(
            topPostersApiKey = "TP-key",
            topPostersEntitlement = TopPostersEntitlementSnapshot(
                valid = true,
                isActive = true,
                tier = 2,
                tierName = "Pro",
                episodeThumbnails = false,
                verifiedAtMs = 0L,
                expiresAtMs = Long.MAX_VALUE
            )
        )

        assertEquals(
            listOf(ArtworkProviderChoiceKey.DEFAULT, ArtworkProviderChoiceKey.TOP_POSTERS),
            registry.availableChoices(ArtworkType.POSTER, settings)
        )
        assertEquals(
            listOf(ArtworkProviderChoiceKey.DEFAULT),
            registry.availableChoices(ArtworkType.THUMBNAIL, settings)
        )
    }

    @Test
    fun `top posters premium entitlement exposes thumbnails`() {
        val settings = ArtworkProviderSettings(
            topPostersApiKey = "TP-key",
            topPostersEntitlement = TopPostersEntitlementSnapshot(
                valid = true,
                isActive = true,
                tier = 1,
                tierName = "Premium",
                episodeThumbnails = true,
                verifiedAtMs = 0L,
                expiresAtMs = Long.MAX_VALUE
            )
        )

        assertEquals(
            listOf(ArtworkProviderChoiceKey.DEFAULT, ArtworkProviderChoiceKey.TOP_POSTERS),
            registry.availableChoices(ArtworkType.THUMBNAIL, settings)
        )
    }

    @Test
    fun `rpdb and top posters expose both poster choices`() {
        val settings = ArtworkProviderSettings(
            rpdbApiKey = "rpdb",
            topPostersApiKey = "TP-key"
        )

        assertEquals(
            listOf(ArtworkProviderChoiceKey.DEFAULT, ArtworkProviderChoiceKey.TOP_POSTERS, ArtworkProviderChoiceKey.RPDB),
            registry.availableChoices(ArtworkType.POSTER, settings)
        )
    }

    @Test
    fun `inactive top posters entitlement hides thumbnail choice`() {
        val settings = ArtworkProviderSettings(
            topPostersApiKey = "TP-key",
            topPostersEntitlement = TopPostersEntitlementSnapshot(
                valid = true,
                isActive = false,
                tier = 1,
                tierName = "Premium",
                episodeThumbnails = true,
                verifiedAtMs = 0L,
                expiresAtMs = Long.MAX_VALUE
            )
        )

        assertEquals(
            listOf(ArtworkProviderChoiceKey.DEFAULT),
            registry.availableChoices(ArtworkType.THUMBNAIL, settings)
        )
    }

    @Test
    fun `choice key maps to runtime provider id`() {
        assertEquals(
            ArtworkProviderId.RuntimeProvider(IntegrationProvider.TOP_POSTERS),
            registry.providerIdFor(ArtworkProviderChoiceKey.TOP_POSTERS)
        )
    }
}
```

- [ ] **Step 2: Run registry tests and verify they fail**

Run:

```bash
./gradlew --no-build-cache testDebugUnitTest --tests 'com.nexio.tv.core.artwork.ArtworkProviderRegistryTest'
```

Expected: compilation fails because the registry does not exist.

- [ ] **Step 3: Implement registry**

Create:

```kotlin
package com.nexio.tv.core.artwork

import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.domain.model.ArtworkProviderChoiceKey
import com.nexio.tv.domain.model.ArtworkProviderSettings
import com.nexio.tv.domain.model.ArtworkTypeKey

data class ArtworkProviderDescriptor(
    val providerId: ArtworkProviderId,
    val supportedTypes: Set<ArtworkType>,
    val supportedIdTypes: Set<String>,
    val embedsRatingsByType: Set<ArtworkType> = emptySet()
)

class ArtworkProviderRegistry {
    fun availableChoices(type: ArtworkType, settings: ArtworkProviderSettings): List<ArtworkProviderChoiceKey> {
        val choices = mutableListOf(ArtworkProviderChoiceKey.DEFAULT)
        if (type == ArtworkType.POSTER && settings.hasTopPostersKey) choices += ArtworkProviderChoiceKey.TOP_POSTERS
        if (type == ArtworkType.POSTER && settings.hasRpdbKey) choices += ArtworkProviderChoiceKey.RPDB
        if (type == ArtworkType.THUMBNAIL && settings.topPostersCanProvideThumbnails) choices += ArtworkProviderChoiceKey.TOP_POSTERS
        return choices
    }

    fun providerIdFor(choice: ArtworkProviderChoiceKey): ArtworkProviderId? =
        when (choice) {
            ArtworkProviderChoiceKey.DEFAULT -> null
            ArtworkProviderChoiceKey.RPDB -> ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB)
            ArtworkProviderChoiceKey.TOP_POSTERS -> ArtworkProviderId.RuntimeProvider(IntegrationProvider.TOP_POSTERS)
        }

    fun capability(choice: ArtworkProviderChoiceKey, settings: ArtworkProviderSettings): ArtworkProviderDescriptor? {
        val providerId = providerIdFor(choice) ?: return null
        return when (choice) {
            ArtworkProviderChoiceKey.RPDB -> ArtworkProviderDescriptor(
                providerId = providerId,
                supportedTypes = setOf(ArtworkType.POSTER),
                supportedIdTypes = setOf("imdb", "tmdb", "tvdb"),
                embedsRatingsByType = setOf(ArtworkType.POSTER)
            )
            ArtworkProviderChoiceKey.TOP_POSTERS -> ArtworkProviderDescriptor(
                providerId = providerId,
                supportedTypes = if (settings.topPostersCanProvideThumbnails) {
                    setOf(ArtworkType.POSTER, ArtworkType.THUMBNAIL)
                } else {
                    setOf(ArtworkType.POSTER)
                },
                supportedIdTypes = setOf("imdb", "tmdb", "tvdb", "trakt", "mal", "kitsu", "anilist", "anidb"),
                embedsRatingsByType = setOf(ArtworkType.POSTER, ArtworkType.THUMBNAIL)
            )
            ArtworkProviderChoiceKey.DEFAULT -> null
        }
    }
}

fun ArtworkType.toSettingsKey(): ArtworkTypeKey =
    when (this) {
        ArtworkType.POSTER -> ArtworkTypeKey.POSTER
        ArtworkType.LOGO -> ArtworkTypeKey.LOGO
        ArtworkType.BACKDROP -> ArtworkTypeKey.BACKDROP
        ArtworkType.THUMBNAIL -> ArtworkTypeKey.THUMBNAIL
    }
```

- [ ] **Step 4: Make existing capability resolver delegate to registry with real settings**

Update `ArtworkProviderCapabilityResolver` so it evaluates RPDB and Top Posters using the same supported ID sets and the real `ArtworkProviderSettings`. Do not synthesize Top Posters Premium entitlement inside the resolver.

```kotlin
class ArtworkProviderCapabilityResolver(
    private val registry: ArtworkProviderRegistry = ArtworkProviderRegistry()
) {
    fun evaluate(
        provider: ArtworkProviderId,
        imageType: ArtworkType,
        ids: ProviderIds,
        mediaKind: MetadataMediaKind,
        settings: ArtworkProviderSettings
    ): ArtworkProviderCapability {
        val choice = when (provider) {
            ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB) -> ArtworkProviderChoiceKey.RPDB
            ArtworkProviderId.RuntimeProvider(IntegrationProvider.TOP_POSTERS) -> ArtworkProviderChoiceKey.TOP_POSTERS
            else -> return ArtworkProviderCapability(false, "unsupported premium artwork provider")
        }
        if (settings.selection.providerFor(imageType.toSettingsKey()) != choice) {
            return ArtworkProviderCapability(false, "provider_not_selected_for_artwork_type")
        }
        if (choice !in registry.availableChoices(imageType, settings)) {
            return ArtworkProviderCapability(false, capabilityUnavailableReason(choice, imageType, settings))
        }
        val capability = registry.capability(choice, settings)
        if (capability == null || imageType !in capability.supportedTypes) {
            return ArtworkProviderCapability(false, "unsupported artwork type for provider")
        }
        val hasSupportedId = capability.supportedIdTypes.any { ids.hasIdType(it, mediaKind) }
        return if (hasSupportedId) {
            ArtworkProviderCapability(true, null)
        } else {
            ArtworkProviderCapability(false, "missing supported provider id")
        }
    }

    private fun capabilityUnavailableReason(
        choice: ArtworkProviderChoiceKey,
        imageType: ArtworkType,
        settings: ArtworkProviderSettings
    ): String =
        when {
            choice == ArtworkProviderChoiceKey.TOP_POSTERS && !settings.hasTopPostersKey -> "topposters_not_configured"
            choice == ArtworkProviderChoiceKey.TOP_POSTERS && imageType == ArtworkType.THUMBNAIL && settings.topPostersEntitlement == null -> "topposters_entitlement_missing"
            choice == ArtworkProviderChoiceKey.TOP_POSTERS && imageType == ArtworkType.THUMBNAIL && settings.topPostersEntitlement?.isActive != true -> "topposters_entitlement_inactive"
            choice == ArtworkProviderChoiceKey.TOP_POSTERS && imageType == ArtworkType.THUMBNAIL && settings.topPostersEntitlement?.tier != 1 -> "topposters_tier_not_premium"
            choice == ArtworkProviderChoiceKey.TOP_POSTERS && imageType == ArtworkType.THUMBNAIL && settings.topPostersEntitlement?.episodeThumbnails != true -> "topposters_episode_thumbnails_not_enabled"
            choice == ArtworkProviderChoiceKey.RPDB && !settings.hasRpdbKey -> "rpdb_not_configured"
            else -> "provider_unavailable_for_artwork_type"
        }
}
```

Use this local helper in `ArtworkProviderCapabilityResolver`:

```kotlin
private fun ProviderIds.hasIdType(idType: String): Boolean =
    when (idType) {
        "imdb" -> imdb != null
        "tmdb" -> tmdb != null
        "tvdb" -> tvdb != null
        "trakt" -> trakt != null
        "mal" -> mal != null
        "kitsu" -> kitsu != null
        "anilist" -> anilist != null
        "anidb" -> anidb != null
        else -> false
    }
```

Change the call site to `capability.supportedIdTypes.any { ids.hasIdType(it) }`.

- [ ] **Step 5: Run registry and capability tests**

Run:

```bash
./gradlew --no-build-cache testDebugUnitTest --tests 'com.nexio.tv.core.artwork.ArtworkProviderRegistryTest' --tests 'com.nexio.tv.core.artwork.ArtworkProviderCapabilityResolverTest'
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit Task 4**

Run:

```bash
git add app/src/main/java/com/nexio/tv/core/artwork/ArtworkProviderRegistry.kt app/src/main/java/com/nexio/tv/core/artwork/ArtworkProviderCapabilityResolver.kt app/src/test/java/com/nexio/tv/core/artwork/ArtworkProviderRegistryTest.kt app/src/test/java/com/nexio/tv/core/artwork/ArtworkProviderCapabilityResolverTest.kt
git commit -m "feat: add artwork provider capability registry"
```

Expected: commit succeeds with only registry and capability paths staged.

## Task 5: Add Shared External ID Selector

**Files:**

- Create: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkExternalIdSelector.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/integration/posters/PosterAdapterUtils.kt`
- Test: `app/src/test/java/com/nexio/tv/core/artwork/ArtworkExternalIdSelectorTest.kt`
- Test: `app/src/test/java/com/nexio/tv/data/integration/posters/PremiumPosterStableContentIdTest.kt`

- [ ] **Step 1: Write failing selector tests**

Add:

```kotlin
package com.nexio.tv.core.artwork

import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ProviderIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArtworkExternalIdSelectorTest {
    private val selector = ArtworkExternalIdSelector()

    @Test
    fun `top posters anime poster prefers kitsu before imdb`() {
        val ids = ProviderIds(kitsu = "7442", imdb = "tt0388629")

        val selected = selector.selectIds(
            provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TOP_POSTERS),
            imageType = ArtworkType.POSTER,
            mediaKind = MetadataMediaKind.SERIES,
            providerIds = ids,
            episodeContext = null
        )

        assertEquals("kitsu", selected.first().idType)
        assertEquals("7442", selected.first().mediaId)
    }

    @Test
    fun `rpdb poster never returns kitsu id`() {
        val ids = ProviderIds(kitsu = "7442", imdb = "tt0388629")

        val selected = selector.selectIds(
            provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB),
            imageType = ArtworkType.POSTER,
            mediaKind = MetadataMediaKind.SERIES,
            providerIds = ids,
            episodeContext = null
        )

        assertEquals(listOf("imdb"), selected.map { it.idType })
    }

    @Test
    fun `top posters thumbnail requires episode context`() {
        val selected = selector.selectIds(
            provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TOP_POSTERS),
            imageType = ArtworkType.THUMBNAIL,
            mediaKind = MetadataMediaKind.SERIES,
            providerIds = ProviderIds(kitsu = "7442"),
            episodeContext = null
        )

        assertTrue(selected.isEmpty())
    }

    @Test
    fun `top posters tv thumbnail formats tmdb series id`() {
        val selected = selector.selectIds(
            provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TOP_POSTERS),
            imageType = ArtworkType.THUMBNAIL,
            mediaKind = MetadataMediaKind.SERIES,
            providerIds = ProviderIds(tmdb = "1399", tvdb = "121361"),
            episodeContext = EpisodeArtworkContext(season = 1, episode = 5)
        )

        assertEquals("tvdb", selected[0].idType)
        assertEquals("121361", selected[0].mediaId)
        assertEquals("tmdb", selected[1].idType)
        assertEquals("series-1399", selected[1].mediaId)
    }
}
```

- [ ] **Step 2: Run selector tests and verify they fail**

Run:

```bash
./gradlew --no-build-cache testDebugUnitTest --tests 'com.nexio.tv.core.artwork.ArtworkExternalIdSelectorTest'
```

Expected: compilation fails because selector types do not exist.

- [ ] **Step 3: Implement selector**

Create:

```kotlin
package com.nexio.tv.core.artwork

import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ProviderIds

data class ArtworkProviderExternalId(
    val idType: String,
    val mediaId: String
)

data class EpisodeArtworkContext(
    val season: Int,
    val episode: Int
) {
    val isValid: Boolean get() = season >= 1 && episode >= 1
    val episodePath: String get() = "S${season}E${episode}"
}

class ArtworkExternalIdSelector {
    fun selectIds(
        provider: ArtworkProviderId,
        imageType: ArtworkType,
        mediaKind: MetadataMediaKind,
        providerIds: ProviderIds,
        episodeContext: EpisodeArtworkContext?
    ): List<ArtworkProviderExternalId> {
        if (imageType == ArtworkType.THUMBNAIL && episodeContext?.isValid != true) return emptyList()
        return when (provider) {
            ArtworkProviderId.RuntimeProvider(IntegrationProvider.TOP_POSTERS) ->
                topPostersIds(imageType, mediaKind, providerIds)
            ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB) ->
                rpdbIds(mediaKind, providerIds)
            else -> emptyList()
        }
    }

    private fun topPostersIds(
        imageType: ArtworkType,
        mediaKind: MetadataMediaKind,
        ids: ProviderIds
    ): List<ArtworkProviderExternalId> {
        val animePreferred = buildList {
            ids.kitsu?.let { add(ArtworkProviderExternalId("kitsu", it)) }
            ids.mal?.let { add(ArtworkProviderExternalId("mal", it)) }
            ids.anilist?.let { add(ArtworkProviderExternalId("anilist", it)) }
            ids.anidb?.let { add(ArtworkProviderExternalId("anidb", it)) }
            ids.imdb?.let { add(ArtworkProviderExternalId("imdb", it)) }
        }
        if (animePreferred.isNotEmpty()) return animePreferred
        return buildList {
            ids.tvdb?.let { add(ArtworkProviderExternalId("tvdb", it)) }
            ids.tmdb?.let { add(ArtworkProviderExternalId("tmdb", "${tmdbPrefix(mediaKind)}-$it")) }
            ids.imdb?.let { add(ArtworkProviderExternalId("imdb", it)) }
            ids.trakt?.let { add(ArtworkProviderExternalId("trakt", it)) }
        }
    }

    private fun rpdbIds(mediaKind: MetadataMediaKind, ids: ProviderIds): List<ArtworkProviderExternalId> =
        buildList {
            ids.imdb?.let { add(ArtworkProviderExternalId("imdb", it)) }
            ids.tmdb?.let { add(ArtworkProviderExternalId("tmdb", "${tmdbPrefix(mediaKind)}-$it")) }
            ids.tvdb?.let { add(ArtworkProviderExternalId("tvdb", "series-$it")) }
        }

    private fun tmdbPrefix(mediaKind: MetadataMediaKind): String =
        if (mediaKind == MetadataMediaKind.MOVIE) "movie" else "series"
}
```

Keep the order rules exactly: Top Posters anime IDs beat IMDb; RPDB never uses Kitsu.

- [ ] **Step 4: Route existing stable content ID helpers through selector**

Modify `PosterAdapterUtils.kt` so `premiumPosterStableContentId(provider)` calls `ArtworkExternalIdSelector` and formats the first selected ID as `"idType:mediaId"`.

- [ ] **Step 5: Run selector and stable ID tests**

Run:

```bash
./gradlew --no-build-cache testDebugUnitTest --tests 'com.nexio.tv.core.artwork.ArtworkExternalIdSelectorTest' --tests 'com.nexio.tv.data.integration.posters.PremiumPosterStableContentIdTest'
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit Task 5**

Run:

```bash
git add app/src/main/java/com/nexio/tv/core/artwork/ArtworkExternalIdSelector.kt app/src/main/java/com/nexio/tv/data/integration/posters/PosterAdapterUtils.kt app/src/test/java/com/nexio/tv/core/artwork/ArtworkExternalIdSelectorTest.kt app/src/test/java/com/nexio/tv/data/integration/posters/PremiumPosterStableContentIdTest.kt
git commit -m "feat: centralize premium artwork id selection"
```

Expected: commit succeeds with selector and stable ID paths staged.

## Task 6: Replace Router Active Provider With Per-Type Selection

**Files:**

- Modify: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkRouter.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkModels.kt`
- Test: `app/src/test/java/com/nexio/tv/core/artwork/ArtworkRouterTest.kt`

- [ ] **Step 1: Write failing router tests**

Add to `ArtworkRouterTest`:

```kotlin
@Test
fun `selected poster provider wins only for poster`() {
    val router = ArtworkRouter()
    val topPosters = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TOP_POSTERS)
    val rpdb = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB)
    val policy = ArtworkRoutingPolicy(
        settings = ArtworkProviderSettings(
            topPostersApiKey = "TP-key",
            rpdbApiKey = "rpdb-key",
            selection = ArtworkProviderSelectionSettings(
                posterProvider = ArtworkProviderChoiceKey.RPDB,
                thumbnailProvider = ArtworkProviderChoiceKey.TOP_POSTERS
            ),
            topPostersEntitlement = TopPostersEntitlementSnapshot(true, true, 1, "Premium", true, 0L, Long.MAX_VALUE)
        )
    )

    val posterDecision = router.select(
        candidates = listOf(
            premiumCandidate(ArtworkType.POSTER, topPosters, priority = 1),
            premiumCandidate(ArtworkType.POSTER, rpdb, priority = 2),
            primaryCandidate(ArtworkType.POSTER)
        ),
        policy = policy
    )

    assertEquals(rpdb, posterDecision.selectedCandidate.provider)
}

@Test
fun `unselected premium provider is rejected as inactive for that artwork type`() {
    val router = ArtworkRouter()
    val topPosters = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TOP_POSTERS)
    val policy = ArtworkRoutingPolicy(
        settings = ArtworkProviderSettings(
            topPostersApiKey = "TP-key",
            selection = ArtworkProviderSelectionSettings(posterProvider = ArtworkProviderChoiceKey.DEFAULT)
        )
    )

    val decision = router.select(
        candidates = listOf(
            premiumCandidate(ArtworkType.POSTER, topPosters),
            primaryCandidate(ArtworkType.POSTER)
        ),
        policy = policy
    )

    assertEquals(ArtworkSourceRole.PRIMARY, decision.selectedCandidate.sourceRole)
    assertTrue(decision.rejectedCandidates.any { it.reason == "inactive premium artwork provider for poster" })
}
```

- [ ] **Step 2: Run router tests and verify they fail**

Run:

```bash
./gradlew --no-build-cache testDebugUnitTest --tests 'com.nexio.tv.core.artwork.ArtworkRouterTest'
```

Expected: compilation fails because `ArtworkRoutingPolicy` still accepts `activePremiumProvider`.

- [ ] **Step 3: Change routing policy**

Replace `ArtworkRoutingPolicy` with:

```kotlin
data class ArtworkRoutingPolicy(
    val settings: ArtworkProviderSettings = ArtworkProviderSettings(),
    val policyVersion: Int = 1
)
```

Add helpers inside `ArtworkRouter`:

```kotlin
private val registry = ArtworkProviderRegistry()

private fun ArtworkRoutingPolicy.selectedProviderFor(imageType: ArtworkType): ArtworkProviderId? {
    val choice = settings.selection.providerFor(imageType.toSettingsKey())
    if (choice == ArtworkProviderChoiceKey.DEFAULT) return null
    if (choice !in registry.availableChoices(imageType, settings)) return null
    return registry.providerIdFor(choice)
}
```

Change premium activation checks to:

```kotlin
private fun ArtworkCandidate.isActiveSupportedPremium(policy: ArtworkRoutingPolicy): Boolean {
    val selectedProvider = policy.selectedProviderFor(imageType)
    return provider != null &&
        provider == selectedProvider &&
        provider.evaluatePremiumCandidate(this, policy.settings).supported
}

private fun ArtworkCandidate.unsupportedPremiumRejection(policy: ArtworkRoutingPolicy): RejectedArtworkCandidate? {
    if (sourceRole != ArtworkSourceRole.PREMIUM) return null
    val selectedProvider = policy.selectedProviderFor(imageType)
    if (provider == null || provider != selectedProvider) {
        return rejected("inactive premium artwork provider for ${imageType.name.lowercase()}")
    }
    val capability = provider.evaluatePremiumCandidate(this, policy.settings)
    return if (capability.supported) null else rejected(capability.reason ?: "unsupported premium artwork provider")
}

private fun ArtworkProviderId.evaluatePremiumCandidate(
    candidate: ArtworkCandidate,
    settings: ArtworkProviderSettings
): ArtworkProviderCapability =
    capabilityResolver.evaluate(
        provider = this,
        imageType = candidate.imageType,
        ids = candidate.providerIds,
        mediaKind = candidate.mediaKind,
        settings = settings
    )
```

- [ ] **Step 4: Update existing call sites**

Replace constructor usage:

```kotlin
ArtworkRoutingPolicy(activePremiumProvider = providerId)
```

with:

```kotlin
ArtworkRoutingPolicy(
    settings = ArtworkProviderSettings(
        rpdbApiKey = if (providerId?.key == "RPDB") "configured" else "",
        topPostersApiKey = if (providerId?.key == "TOP_POSTERS") "configured" else "",
        selection = ArtworkProviderSelectionSettings(
            posterProvider = when (providerId?.key) {
                "RPDB" -> ArtworkProviderChoiceKey.RPDB
                "TOP_POSTERS" -> ArtworkProviderChoiceKey.TOP_POSTERS
                else -> ArtworkProviderChoiceKey.DEFAULT
            }
        )
    )
)
```

Use this compatibility construction only in tests and audit fixtures. Production code should pass real `ArtworkProviderSettings` from DataStore.

- [ ] **Step 5: Run router tests**

Run:

```bash
./gradlew --no-build-cache testDebugUnitTest --tests 'com.nexio.tv.core.artwork.ArtworkRouterTest'
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit Task 6**

Run:

```bash
git add app/src/main/java/com/nexio/tv/core/artwork/ArtworkRouter.kt app/src/main/java/com/nexio/tv/core/artwork/ArtworkModels.kt app/src/test/java/com/nexio/tv/core/artwork/ArtworkRouterTest.kt
git commit -m "feat: route artwork by selected provider per type"
```

Expected: commit succeeds with router paths staged.

## Task 7: Migrate Poster Adapters To Provider Selection

**Files:**

- Modify: `app/src/main/java/com/nexio/tv/core/poster/PosterRatingsUrlResolver.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/integration/posters/RpdbMetadataProviderAdapter.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/integration/posters/TopPostersMetadataProviderAdapter.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt`
- Test: `app/src/test/java/com/nexio/tv/core/poster/PosterRatingsUrlResolverTest.kt`
- Test: `app/src/test/java/com/nexio/tv/data/integration/posters/PremiumPosterMetadataProviderAdapterStableIdTest.kt`
- Test: `app/src/test/java/com/nexio/tv/core/metadata/router/MetadataRouterFacadeStableIdBundleTest.kt`

- [ ] **Step 1: Write failing adapter tests for typed artwork refs and independent configured keys**

Add to `PosterRatingsUrlResolverTest`:

```kotlin
@Test
fun `top posters selected poster returns internal artwork ref not raw provider url`() {
    val settings = ArtworkProviderSettings(
        rpdbApiKey = "rpdb-key",
        topPostersApiKey = "TP-key",
        selection = ArtworkProviderSelectionSettings(posterProvider = ArtworkProviderChoiceKey.TOP_POSTERS)
    )

    val ref = resolver.resolvePosterArtworkRef(
        settings = settings,
        providerIds = ProviderIds(kitsu = "7442", imdb = "tt0388629"),
        mediaKind = MetadataMediaKind.SERIES,
        fallbackPosterUrl = "https://image.tmdb.org/t/p/w500/poster.jpg"
    )

    val legacy = ref!!.toLegacyArtworkString()
    assertTrue(legacy!!.startsWith("nexio-artwork://"))
    assertFalse(legacy.contains("api.top-posters.com"))
    assertFalse(legacy.contains("ratingposterdb.com"))
}

@Test
fun `default poster selection returns primary typed or fallback artwork ref`() {
    val settings = ArtworkProviderSettings(
        rpdbApiKey = "rpdb-key",
        topPostersApiKey = "TP-key",
        selection = ArtworkProviderSelectionSettings(posterProvider = ArtworkProviderChoiceKey.DEFAULT)
    )

    val ref = resolver.resolvePosterArtworkRef(
        settings = settings,
        providerIds = ProviderIds(imdb = "tt0388629"),
        mediaKind = MetadataMediaKind.SERIES,
        fallbackPosterUrl = "https://image.tmdb.org/t/p/w500/poster.jpg"
    )

    val legacy = ref!!.toLegacyArtworkString()
    assertTrue(legacy!!.startsWith("nexio-artwork://"))
}
```

- [ ] **Step 2: Run poster adapter tests and verify they fail**

Run:

```bash
./gradlew --no-build-cache testDebugUnitTest --tests 'com.nexio.tv.core.poster.PosterRatingsUrlResolverTest' --tests 'com.nexio.tv.data.integration.posters.PremiumPosterMetadataProviderAdapterStableIdTest'
```

Expected: failures show global `activeProvider` logic and raw remote URL return paths are still used.

- [ ] **Step 3: Replace raw URL resolver with typed artwork ref resolver**

Update resolver to accept `ArtworkProviderSettings` and selected type. It must build `ArtworkCandidate.ProviderTemplate` candidates and return an `ArtworkDisplayRef`, not a remote provider URL:

```kotlin
fun resolvePosterArtworkRef(
    settings: ArtworkProviderSettings,
    providerIds: ProviderIds,
    mediaKind: MetadataMediaKind,
    ownerKey: ArtworkOwnerKey,
    fallbackPosterUrl: String?
): ArtworkDisplayRef? {
    val candidates = buildList {
        premiumPosterCandidate(settings, providerIds, mediaKind, ownerKey)?.let(::add)
        fallbackPosterUrl?.let { add(primaryRemotePosterCandidate(ownerKey, providerIds, mediaKind, it)) }
    }
    if (candidates.isEmpty()) return null
    val selection = artworkRouter.select(candidates, ArtworkRoutingPolicy(settings = settings))
    val decision = createArtworkDecision(ownerKey, selection, ArtworkRoutingPolicy(settings = settings))
    artworkDecisionCache.put(decision)
    return decision.toDisplayRef()
}
```

Add these private helpers to `PosterRatingsUrlResolver`:

```kotlin
private fun createArtworkDecision(
    ownerKey: ArtworkOwnerKey,
    selection: ArtworkSelectionResult,
    policy: ArtworkRoutingPolicy
): ArtworkDecision {
    val selected = selection.selectedCandidate
    val now = System.currentTimeMillis()
    val persistedTemplate = (selected.source as? ArtworkSource.ProviderTemplate)?.let { template ->
        PersistedProviderTemplate(
            provider = template.provider,
            imageType = selected.imageType,
            idType = template.idType,
            mediaId = template.mediaId,
            providerPathHash = template.providerPathHash,
            settingsHash = template.settingsHash,
            credentialHash = template.credentialHash,
            policyVersion = policy.policyVersion,
            pathParams = template.pathParams
        )
    }
    val persisted = PersistedArtworkCandidate(
        provider = selected.provider,
        sourceRole = selected.sourceRole,
        sourceHash = selected.source.sourceHashForDecision(),
        redactedSourceForTrace = selected.source.redactedSourceForTrace(),
        providerTemplate = persistedTemplate,
        priority = selected.priority
    )
    return ArtworkDecision(
        decisionKey = ArtworkCacheKeys.decisionKey(
            ownerKey = ownerKey,
            imageType = selected.imageType,
            provider = selected.provider,
            premiumEnabled = selected.sourceRole == ArtworkSourceRole.PREMIUM,
            settingsHash = persistedTemplate?.settingsHash,
            credentialHash = persistedTemplate?.credentialHash,
            policyVersion = policy.policyVersion
        ),
        ownerKey = ownerKey,
        canonicalContentId = selected.canonicalContentId,
        imageType = selected.imageType,
        selectedCandidate = persisted,
        rejectedCandidates = selection.rejectedCandidates,
        policyVersion = policy.policyVersion,
        settingsHash = persistedTemplate?.settingsHash,
        credentialHash = persistedTemplate?.credentialHash,
        createdAtMs = now,
        expiresAtMs = now + 86_400_000L,
        staleUntilMs = now + 604_800_000L
    )
}
```

Add these source helpers beside `createArtworkDecision`:

```kotlin
private fun ArtworkSource.sourceHashForDecision(): String? =
    when (this) {
        is ArtworkSource.RemoteUrl -> normalizedUrlHash
        is ArtworkSource.ProviderTemplate -> listOf(
            provider.key,
            idType,
            mediaId,
            providerPathHash.orEmpty(),
            settingsHash.orEmpty(),
            credentialHash.orEmpty(),
            pathParams.toSortedMap().entries.joinToString(",") { "${it.key}=${it.value}" }
        ).joinToString(":")
        is ArtworkSource.LocalAsset -> assetKey.value
        is ArtworkSource.Placeholder -> placeholderType.name
    }

private fun ArtworkSource.redactedSourceForTrace(): String? =
    when (this) {
        is ArtworkSource.RemoteUrl -> redactedUrlForTrace
        is ArtworkSource.ProviderTemplate -> "${provider.key}:$idType:$mediaId"
        is ArtworkSource.LocalAsset -> assetKey.value
        is ArtworkSource.Placeholder -> placeholderType.name
    }
```

```kotlin
private fun ArtworkDecision.toDisplayRef(): ArtworkDisplayRef.RuntimeAsset =
    ArtworkDisplayRef.RuntimeAsset(
        decisionKey = decisionKey,
        assetKey = null,
        imageType = imageType,
        selectedProvider = selectedCandidate.provider,
        sourceRole = selectedCandidate.sourceRole,
        trace = ArtworkTrace(
            selectedProvider = selectedCandidate.provider?.key,
            sourceRole = selectedCandidate.sourceRole.name,
            rejectedCandidates = rejectedCandidates
        )
    )
```

The architectural requirement is fixed: no RPDB or Top Posters raw remote URL may be returned to UI or legacy string fields.

- [ ] **Step 4: Delete or privatize raw provider URL helpers**

Remove public use of helpers that return:

```text
Top Posters HTTPS provider URLs
RPDB HTTPS provider URLs
```

Provider URL construction belongs only inside `TopPostersIntegrationProvider` and `RpdbIntegrationProvider` runtime `load` blocks. Legacy strings must be derived from:

```kotlin
ArtworkDisplayRef.toLegacyArtworkString()
```

Expected legacy value shape:

```text
nexio-artwork://asset/{assetKey}
nexio-artwork://decision/{decisionKey}
```

- [ ] **Step 5: Update metadata adapters**

In `RpdbMetadataProviderAdapter` and `TopPostersMetadataProviderAdapter`, remove checks against `PosterRatingsProvider`. Inject or pass `ArtworkProviderSettings` and compare:

```kotlin
val selectedPosterProvider = settings.selection.posterProvider
if (selectedPosterProvider != ArtworkProviderChoiceKey.RPDB) return route
```

and:

```kotlin
if (selectedPosterProvider != ArtworkProviderChoiceKey.TOP_POSTERS) return route
```

- [ ] **Step 6: Update facade policy creation**

In `MetadataRouterFacade`, replace global provider policy creation with:

```kotlin
val artworkPolicy = ArtworkRoutingPolicy(settings = artworkProviderSettings)
```

Ensure the settings value comes from `PosterRatingsSettingsDataStore.settings` after Task 2.

- [ ] **Step 7: Run poster routing tests**

Run:

```bash
./gradlew --no-build-cache testDebugUnitTest --tests 'com.nexio.tv.core.poster.PosterRatingsUrlResolverTest' --tests 'com.nexio.tv.data.integration.posters.PremiumPosterMetadataProviderAdapterStableIdTest' --tests 'com.nexio.tv.core.metadata.router.MetadataRouterFacadeStableIdBundleTest'
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit Task 7**

Run:

```bash
git add app/src/main/java/com/nexio/tv/core/poster/PosterRatingsUrlResolver.kt app/src/main/java/com/nexio/tv/data/integration/posters/RpdbMetadataProviderAdapter.kt app/src/main/java/com/nexio/tv/data/integration/posters/TopPostersMetadataProviderAdapter.kt app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt app/src/test/java/com/nexio/tv/core/poster/PosterRatingsUrlResolverTest.kt app/src/test/java/com/nexio/tv/data/integration/posters/PremiumPosterMetadataProviderAdapterStableIdTest.kt app/src/test/java/com/nexio/tv/core/metadata/router/MetadataRouterFacadeStableIdBundleTest.kt
git commit -m "feat: select premium poster provider per artwork settings"
```

Expected: commit succeeds with poster adapter paths staged.

## Task 8: Add Top Posters Thumbnail Runtime Fetch

**Files:**

- Modify: `app/src/main/java/com/nexio/tv/core/integration/IntegrationApiShapes.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/integration/posters/TopPostersIntegrationProvider.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/image/IntegrationPosterFetcher.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/image/PosterIntegrationRequest.kt`
- Test: `app/src/test/java/com/nexio/tv/data/integration/posters/TopPostersIntegrationProviderTest.kt`
- Test: `app/src/test/java/com/nexio/tv/core/image/IntegrationPosterFetcherTest.kt`
- Test: `app/src/test/java/com/nexio/tv/architecture/IntegrationProviderContractRegistryTest.kt`

- [ ] **Step 1: Write failing thumbnail runtime tests**

Add to `TopPostersIntegrationProviderTest`:

```kotlin
@Test
fun `top posters thumbnail fetch uses runtime thumbnail shape and forced badge parameters`() = runTest {
    val runtime = mockk<IntegrationRuntime>()
    val specSlot = slot<IntegrationSpec<ByteArray>>()
    coEvery { runtime.get(capture(specSlot)) } returns IntegrationLoadResult.Success(byteArrayOf(1, 2, 3))
    val transport = FakePosterTransport()
    val provider = TopPostersIntegrationProvider(runtime, topPostersApi, transport)

    provider.fetchThumbnail(
        TopPostersThumbnailRequest(
            apiKey = "TP-key",
            idType = "tmdb",
            mediaId = "series-157239",
            season = 1,
            episode = 5,
            credentialHash = "credential"
        )
    )

    assertEquals(PosterApiShapes.TOP_POSTERS_THUMBNAIL, specSlot.captured.apiShapeId)
    assertEquals(IntegrationHeaderPolicies.TOP_POSTERS_THUMBNAIL_V1, specSlot.captured.headerPolicyId)
    assertTrue(specSlot.captured.requiredCacheKey.contains("badgePos:top-right"))
    assertTrue(specSlot.captured.requiredCacheKey.contains("badgeSize:small"))
    assertTrue(transport.lastUrl.contains("/TP-key/tmdb/thumbnail/series-157239/S1E5.jpg"))
    assertTrue(transport.lastUrl.contains("badge_position=top-right"))
    assertTrue(transport.lastUrl.contains("badge_size=small"))
    assertTrue(transport.lastUrl.contains("blur=false"))
    assertFalse(transport.lastUrl.contains("fallback_url="))
}
```

- [ ] **Step 2: Run thumbnail runtime tests and verify they fail**

Run:

```bash
./gradlew --no-build-cache testDebugUnitTest --tests 'com.nexio.tv.data.integration.posters.TopPostersIntegrationProviderTest'
```

Expected: compilation fails because thumbnail request and API shape do not exist.

- [ ] **Step 3: Add API shape constants**

Update `IntegrationApiShapes.kt`:

```kotlin
object PosterApiShapes {
    const val RPDB_KEY_VALIDATION = "rpdb.key_validation"
    const val TOP_POSTERS_KEY_VALIDATION = "topposters.key_validation"
    const val RPDB_POSTER_TEMPLATE = "rpdb.poster_template"
    const val TOP_POSTERS_POSTER_TEMPLATE = "topposters.poster_template"
    const val TOP_POSTERS_THUMBNAIL = "topposters.thumbnail"
}

object ArtworkApiShapes {
    const val GENERIC_IMAGE_FETCH = "artwork.image_fetch"
    const val RAIL_PREVIEW_IMAGE_FETCH = "artwork.rail_preview.image_fetch"
    const val ADDON_PREVIEW_IMAGE_FETCH = "artwork.addon_preview.image_fetch"
    const val RPDB_POSTER_TEMPLATE = "rpdb.poster_template"
    const val TOP_POSTERS_POSTER_TEMPLATE = "topposters.poster_template"
    const val TOP_POSTERS_THUMBNAIL = "topposters.thumbnail"
}
```

- [ ] **Step 4: Add thumbnail request model**

In the same file as `PosterIntegrationRequest`, add:

```kotlin
data class TopPostersThumbnailRequest(
    val apiKey: String,
    val idType: String,
    val mediaId: String,
    val season: Int,
    val episode: Int,
    val credentialHash: String,
    val badgePosition: String = "top-right",
    val badgeSize: String = "small",
    val blur: Boolean = false,
    val ttlMs: Long = 86_400_000L,
    val staleAfterExpiryMs: Long = 604_800_000L
) {
    val episodePath: String = "S${season}E${episode}"
    val cacheKey: String =
        "artwork-asset:TOP_POSTERS:thumbnail:$idType:$mediaId:$episodePath:badgeSize:$badgeSize:badgePos:$badgePosition:blur:$blur:credential:$credentialHash:imageLang:en:policy:1"
}
```

Keep `mediaId` and `episodePath` separate. `mediaId` must be `series-157239`, not `series-157239/S1E5`.

- [ ] **Step 5: Implement `fetchThumbnail` through IntegrationRuntime**

Add to `TopPostersIntegrationProvider`:

```kotlin
suspend fun fetchThumbnail(request: TopPostersThumbnailRequest): ByteArray? {
    val spec = IntegrationSpec(
        provider = IntegrationProvider.TOP_POSTERS,
        cacheKey = request.cacheKey,
        codec = ByteArrayIntegrationCodec,
        cachePolicy = IntegrationCachePolicy.CacheFirst(
            ttlMs = request.ttlMs,
            staleAfterExpiryMs = request.staleAfterExpiryMs
        ),
        workClass = IntegrationWorkClass.USER_VISIBLE,
        apiShapeId = PosterApiShapes.TOP_POSTERS_THUMBNAIL,
        headerPolicyId = IntegrationHeaderPolicies.TOP_POSTERS_THUMBNAIL_V1,
        operationKey = "topposters.thumbnail.fetch:${request.idType}:${request.mediaId}:${request.episodePath}:badgeSize:${request.badgeSize}:badgePos:${request.badgePosition}:blur:${request.blur}:credential:${request.credentialHash}",
        load = {
            runCatching {
                val result = posterTransport.execute(request.toRemoteUrl())
                when {
                    result.body == null -> IntegrationLoadResult.HttpError(result.statusCode, reason = "topposters_thumbnail_missing_body")
                    !result.isSuccessful -> IntegrationLoadResult.HttpError(result.statusCode, reason = "topposters_thumbnail_failed")
                    else -> IntegrationLoadResult.Success(result.body)
                }
            }.fold(
                onSuccess = { it },
                onFailure = { IntegrationLoadResult.NetworkError(it) }
            )
        }
    )
    return runtime.get(spec).valueOrNull()
}

private fun TopPostersThumbnailRequest.toRemoteUrl(): String {
    val baseUrl = "https://api.top-posters.com/$apiKey/$idType/thumbnail/$mediaId/$episodePath.jpg"
    val params = buildList {
        add("badge_size=${URLEncoder.encode(badgeSize, StandardCharsets.UTF_8.name())}")
        add("badge_position=${URLEncoder.encode(badgePosition, StandardCharsets.UTF_8.name())}")
        add("blur=$blur")
    }.joinToString("&")
    return "$baseUrl?$params"
}
```

Do not send `fallback_url` for Top Posters thumbnails in Phase 1. App-side fallback is required so `embedsRatingOverlay=true` only when the actual selected image is a generated Top Posters thumbnail.

- [ ] **Step 6: Route thumbnail fetch from shared fetcher**

Add path params to provider templates so thumbnail season/episode are persisted separately from `mediaId`:

```kotlin
data class ProviderTemplate(
    val provider: ArtworkProviderId,
    val idType: String,
    val mediaId: String,
    val providerPathHash: String?,
    val settingsHash: String?,
    val credentialHash: String?,
    val pathParams: Map<String, String> = emptyMap()
) : ArtworkSource

data class PersistedProviderTemplate(
    val provider: ArtworkProviderId,
    val imageType: ArtworkType,
    val idType: String,
    val mediaId: String,
    val providerPathHash: String?,
    val settingsHash: String?,
    val credentialHash: String?,
    val imageLanguage: String = "en",
    val policyVersion: Int,
    val pathParams: Map<String, String> = emptyMap()
)
```

Update `IntegrationPosterFetcher` so `ArtworkSource.ProviderTemplate` with `imageType == ArtworkType.THUMBNAIL` and provider Top Posters dispatches to `fetchThumbnail`. The request must be built from the persisted provider template:

```kotlin
TopPostersThumbnailRequest(
    apiKey = apiKey,
    idType = template.idType,
    mediaId = template.mediaId,
    season = template.pathParams.getValue("season").toInt(),
    episode = template.pathParams.getValue("episode").toInt(),
    credentialHash = template.credentialHash ?: credentialHash(IntegrationProvider.TOP_POSTERS, apiKey)
)
```

No UI code may construct the remote Top Posters thumbnail URL.

- [ ] **Step 7: Run thumbnail runtime and architecture tests**

Run:

```bash
./gradlew --no-build-cache testDebugUnitTest --tests 'com.nexio.tv.data.integration.posters.TopPostersIntegrationProviderTest' --tests 'com.nexio.tv.core.image.IntegrationPosterFetcherTest' --tests 'com.nexio.tv.architecture.IntegrationProviderContractRegistryTest'
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit Task 8**

Run:

```bash
git add app/src/main/java/com/nexio/tv/core/integration/IntegrationApiShapes.kt app/src/main/java/com/nexio/tv/data/integration/posters/TopPostersIntegrationProvider.kt app/src/main/java/com/nexio/tv/core/image/IntegrationPosterFetcher.kt app/src/main/java/com/nexio/tv/core/image/PosterIntegrationRequest.kt app/src/test/java/com/nexio/tv/data/integration/posters/TopPostersIntegrationProviderTest.kt app/src/test/java/com/nexio/tv/core/image/IntegrationPosterFetcherTest.kt app/src/test/java/com/nexio/tv/architecture/IntegrationProviderContractRegistryTest.kt
git commit -m "feat: fetch top posters thumbnails through runtime"
```

Expected: commit succeeds with thumbnail runtime paths staged.

## Task 9: Add Thumbnail Candidates And Fallback In Metadata Routing

**Files:**

- Modify: `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkCacheKeys.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkSourceMaterializer.kt`
- Test: `app/src/test/java/com/nexio/tv/core/metadata/router/MetadataRouterFacadeTest.kt`
- Test: `app/src/test/java/com/nexio/tv/core/artwork/ArtworkAssetRepositoryTest.kt`

- [ ] **Step 1: Write failing thumbnail fallback test**

Add to metadata facade tests:

```kotlin
@Test
fun `top posters thumbnail candidate falls back to primary episode thumbnail`() = runTest {
    val settings = ArtworkProviderSettings(
        topPostersApiKey = "TP-key",
        selection = ArtworkProviderSelectionSettings(thumbnailProvider = ArtworkProviderChoiceKey.TOP_POSTERS),
        topPostersEntitlement = TopPostersEntitlementSnapshot(true, true, 1, "Premium", true, 0L, Long.MAX_VALUE)
    )

    val episode = facade.resolveEpisodeArtworkForTest(
        settings = settings,
        providerIds = ProviderIds(kitsu = "7442"),
        season = 1,
        episode = 5,
        primaryThumbnail = "https://media.kitsu.io/episode.jpg"
    )

    assertEquals(ArtworkType.THUMBNAIL, episode.thumbnailArtwork!!.imageType)
    assertEquals("TOP_POSTERS", (episode.thumbnailArtwork as ArtworkDisplayRef.RuntimeAsset).selectedProvider!!.key)
    assertTrue(episode.thumbnailArtwork.trace.rejectedCandidates.none { it.reason.contains("inactive") })
}
```

Add a second test where Top Posters has no supported ID and primary wins:

```kotlin
assertEquals("KITSU", (episode.thumbnailArtwork as ArtworkDisplayRef.RuntimeAsset).selectedProvider?.key)
assertEquals(false, (episode.thumbnailArtwork as ArtworkDisplayRef.RuntimeAsset).displayHints.embedsRatingOverlay)
```

Add a third test where Top Posters is selected but entitlement is missing:

```kotlin
val settings = ArtworkProviderSettings(
    topPostersApiKey = "TP-key",
    selection = ArtworkProviderSelectionSettings(thumbnailProvider = ArtworkProviderChoiceKey.TOP_POSTERS),
    topPostersEntitlement = null
)

val episode = facade.resolveEpisodeArtworkForTest(
    settings = settings,
    providerIds = ProviderIds(kitsu = "7442"),
    season = 1,
    episode = 5,
    primaryThumbnail = "https://media.kitsu.io/episode.jpg"
)

assertEquals("KITSU", (episode.thumbnailArtwork as ArtworkDisplayRef.RuntimeAsset).selectedProvider?.key)
assertEquals(false, (episode.thumbnailArtwork as ArtworkDisplayRef.RuntimeAsset).displayHints.embedsRatingOverlay)
assertTrue(episode.thumbnailArtwork.trace.rejectedCandidates.any { it.reason == "topposters_entitlement_missing" })
```

- [ ] **Step 2: Run metadata thumbnail tests and verify they fail**

Run:

```bash
./gradlew --no-build-cache testDebugUnitTest --tests 'com.nexio.tv.core.metadata.router.MetadataRouterFacadeTest'
```

Expected: failing assertions because Top Posters thumbnail candidates are not generated.

- [ ] **Step 3: Add provider template hash helpers**

Add to `ArtworkCacheKeys`:

```kotlin
fun providerTemplatePathHash(value: String): String =
    sha256(value)

fun providerTemplateSettingsHash(value: String): String =
    sha256(value)
```

- [ ] **Step 4: Build Top Posters thumbnail candidate**

Where episode artwork candidates are built, add:

```kotlin
private fun topPostersThumbnailCandidate(
    settings: ArtworkProviderSettings,
    providerIds: ProviderIds,
    mediaKind: MetadataMediaKind,
    season: Int?,
    episode: Int?,
    fallbackThumbnail: String?
): ArtworkCandidate? {
    if (settings.selection.thumbnailProvider != ArtworkProviderChoiceKey.TOP_POSTERS) return null
    val context = EpisodeArtworkContext(season ?: return null, episode ?: return null)
    val provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TOP_POSTERS)
    val selectedId = externalIdSelector.selectIds(provider, ArtworkType.THUMBNAIL, mediaKind, providerIds, context).firstOrNull()
        ?: return null
    return ArtworkCandidate(
        ownerKey = ArtworkOwnerKey.CanonicalContent("episode:${selectedId.idType}:${selectedId.mediaId}:${context.episodePath}"),
        canonicalContentId = "${selectedId.idType}:${selectedId.mediaId}:${context.episodePath}",
        providerIds = providerIds,
        mediaKind = mediaKind,
        imageType = ArtworkType.THUMBNAIL,
        provider = provider,
        sourceRole = ArtworkSourceRole.PREMIUM,
        source = ArtworkSource.ProviderTemplate(
            provider = provider,
            idType = selectedId.idType,
            mediaId = selectedId.mediaId,
            providerPathHash = ArtworkCacheKeys.providerTemplatePathHash("thumbnail:${selectedId.idType}:${selectedId.mediaId}:${context.episodePath}"),
            settingsHash = ArtworkCacheKeys.providerTemplateSettingsHash("badgeSize:small:badgePos:top-right:blur:false"),
            credentialHash = credentialHash(IntegrationProvider.TOP_POSTERS, settings.topPostersApiKey),
            pathParams = mapOf(
                "season" to context.season.toString(),
                "episode" to context.episode.toString()
            )
        ),
        priority = 0,
        requiresRuntimeFetch = true,
        trace = ArtworkTrace(reason = "top posters thumbnail candidate; router validates entitlement before fetch")
    )
}
```

This deliberately produces a premium candidate when selection, episode context, and a supported ID exist. `ArtworkRouter` must reject it with the real capability reason when entitlement is missing, inactive, non-Premium, or lacks `episode_thumbnails`. Materialization must only fetch the selected candidate, so rejected Top Posters candidates never call the network.

- [ ] **Step 5: Ensure fallback candidates remain after premium rejection**

Keep primary thumbnail candidates after the premium candidate in the candidate list:

```kotlin
val thumbnailCandidates = buildList {
    topPostersThumbnailCandidate(
        settings = artworkProviderSettings,
        providerIds = episodeProviderIds,
        mediaKind = MetadataMediaKind.SERIES,
        season = seasonNumber,
        episode = episodeNumber,
        fallbackThumbnail = primaryThumbnailUrl
    )?.let(::add)
    primaryEpisodeThumbnailCandidate(
        ownerKey = episodeOwnerKey,
        providerIds = episodeProviderIds,
        thumbnailUrl = primaryThumbnailUrl
    )?.let(::add)
    previewEpisodeThumbnailCandidate(
        ownerKey = episodeOwnerKey,
        providerIds = episodeProviderIds,
        thumbnailUrl = previewThumbnailUrl
    )?.let(::add)
    add(placeholderThumbnailCandidate(ownerKey = episodeOwnerKey))
}
```

- [ ] **Step 6: Run metadata thumbnail tests**

Run:

```bash
./gradlew --no-build-cache testDebugUnitTest --tests 'com.nexio.tv.core.metadata.router.MetadataRouterFacadeTest' --tests 'com.nexio.tv.core.artwork.ArtworkAssetRepositoryTest'
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit Task 9**

Run:

```bash
git add app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt app/src/main/java/com/nexio/tv/core/artwork/ArtworkCacheKeys.kt app/src/main/java/com/nexio/tv/core/artwork/ArtworkSourceMaterializer.kt app/src/test/java/com/nexio/tv/core/metadata/router/MetadataRouterFacadeTest.kt app/src/test/java/com/nexio/tv/core/artwork/ArtworkAssetRepositoryTest.kt
git commit -m "feat: add premium thumbnail candidates with fallback"
```

Expected: commit succeeds with metadata thumbnail paths staged.

## Task 10: Add Display Hints And Suppress Episode Rating Overlay

**Files:**

- Modify: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkModels.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRepository.kt`
- Modify: `app/src/main/java/com/nexio/tv/domain/model/Meta.kt`
- Modify: `app/src/main/java/com/nexio/tv/domain/model/HomeDisplayMetadata.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/EpisodesSection.kt`
- Test: `app/src/test/java/com/nexio/tv/core/artwork/ArtworkModelsTest.kt`
- Test: `app/src/test/java/com/nexio/tv/domain/model/HomeDisplayMetadataTest.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/detail/EpisodesSectionRatingOverlayTest.kt`

- [ ] **Step 1: Write failing display hint tests**

Add to `ArtworkModelsTest`:

```kotlin
@Test
fun `runtime asset defaults to no embedded rating overlay`() {
    val ref = ArtworkDisplayRef.RuntimeAsset(
        decisionKey = ArtworkDecisionKey("decision"),
        assetKey = ArtworkAssetKey("asset"),
        imageType = ArtworkType.THUMBNAIL,
        selectedProvider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TOP_POSTERS),
        sourceRole = ArtworkSourceRole.PREMIUM,
        trace = ArtworkTrace.empty()
    )

    assertFalse(ref.displayHints.embedsRatingOverlay)
}

@Test
fun `runtime asset can mark embedded rating overlay`() {
    val ref = ArtworkDisplayRef.RuntimeAsset(
        decisionKey = ArtworkDecisionKey("decision"),
        assetKey = ArtworkAssetKey("asset"),
        imageType = ArtworkType.THUMBNAIL,
        selectedProvider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TOP_POSTERS),
        sourceRole = ArtworkSourceRole.PREMIUM,
        trace = ArtworkTrace.empty(),
        displayHints = ArtworkDisplayHints(embedsRatingOverlay = true)
    )

    assertTrue(ref.displayHints.embedsRatingOverlay)
}
```

- [ ] **Step 2: Run display hint tests and verify they fail**

Run:

```bash
./gradlew --no-build-cache testDebugUnitTest --tests 'com.nexio.tv.core.artwork.ArtworkModelsTest'
```

Expected: compilation fails because `ArtworkDisplayHints` does not exist.

- [ ] **Step 3: Add display hints**

In `ArtworkModels.kt`:

```kotlin
data class ArtworkDisplayHints(
    val embedsRatingOverlay: Boolean = false
)
```

Add defaults:

```kotlin
sealed interface ArtworkDisplayRef {
    val imageType: ArtworkType
    val trace: ArtworkTrace
    val displayHints: ArtworkDisplayHints

    data class RuntimeAsset(
        val decisionKey: ArtworkDecisionKey,
        val assetKey: ArtworkAssetKey?,
        override val imageType: ArtworkType,
        val selectedProvider: ArtworkProviderId?,
        val sourceRole: ArtworkSourceRole,
        override val trace: ArtworkTrace,
        override val displayHints: ArtworkDisplayHints = ArtworkDisplayHints()
    ) : ArtworkDisplayRef

    data class Placeholder(
        val placeholderType: PlaceholderType,
        override val imageType: ArtworkType,
        override val trace: ArtworkTrace,
        override val displayHints: ArtworkDisplayHints = ArtworkDisplayHints()
    ) : ArtworkDisplayRef
}
```

Add to persisted decision/candidate if materialization cannot derive it from provider and image type:

```kotlin
val displayHints: ArtworkDisplayHints = ArtworkDisplayHints()
```

- [ ] **Step 4: Mark Top Posters thumbnails as embedded ratings**

Where runtime assets are created from decisions, set:

```kotlin
displayHints = if (
    decision.imageType == ArtworkType.THUMBNAIL &&
    decision.selectedCandidate.provider == ArtworkProviderId.RuntimeProvider(IntegrationProvider.TOP_POSTERS)
) {
    ArtworkDisplayHints(embedsRatingOverlay = true)
} else {
    ArtworkDisplayHints()
}
```

- [ ] **Step 5: Add episode overlay helper**

In `EpisodesSection.kt`, before `ratingLabel` is rendered:

```kotlin
private fun Video.thumbnailEmbedsRatingOverlay(): Boolean =
    thumbnailArtwork?.displayHints?.embedsRatingOverlay == true
```

Inside `EpisodeCard`:

```kotlin
val showLocalRatingOverlay = !episode.thumbnailEmbedsRatingOverlay()
val ratingLabel = remember(episodeRating, showLocalRatingOverlay) {
    if (!showLocalRatingOverlay) null else episodeRating?.value?.takeIf { it > 0.0 }?.let { String.format(Locale.US, "%.1f", it) }
}
val ratingBadge = remember(episodeRating?.source, showLocalRatingOverlay) {
    if (!showLocalRatingOverlay) null else episodeRating?.source?.let(::episodeRatingBadge)
}
```

- [ ] **Step 6: Run model and home metadata tests**

Run:

```bash
./gradlew --no-build-cache testDebugUnitTest --tests 'com.nexio.tv.core.artwork.ArtworkModelsTest' --tests 'com.nexio.tv.domain.model.HomeDisplayMetadataTest'
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit Task 10**

Run:

```bash
git add app/src/main/java/com/nexio/tv/core/artwork/ArtworkModels.kt app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRepository.kt app/src/main/java/com/nexio/tv/domain/model/Meta.kt app/src/main/java/com/nexio/tv/domain/model/HomeDisplayMetadata.kt app/src/main/java/com/nexio/tv/ui/screens/detail/EpisodesSection.kt app/src/test/java/com/nexio/tv/core/artwork/ArtworkModelsTest.kt app/src/test/java/com/nexio/tv/domain/model/HomeDisplayMetadataTest.kt
git commit -m "feat: suppress episode ratings for embedded thumbnail badges"
```

Expected: commit succeeds with display hint and episode UI paths staged.

## Task 11: Replace Settings UI Toggles With Dynamic Provider Selectors

**Files:**

- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PosterRatingsSettingsViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/PosterRatingsSettingsScreen.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/settings/PosterRatingsSettingsViewModelTest.kt`

- [ ] **Step 1: Write failing ViewModel tests**

Add:

```kotlin
@Test
fun `api keys configure providers without enabled toggles`() = runTest {
    dataStore.emit(
        ArtworkProviderSettings(
            rpdbApiKey = "rpdb",
            topPostersApiKey = "TP-key",
            selection = ArtworkProviderSelectionSettings()
        )
    )

    val state = viewModel.uiState.first { it.rpdbApiKey == "rpdb" }

    assertFalse(state.hasRpdbToggle)
    assertFalse(state.hasTopPostersToggle)
    assertEquals(
        listOf(ArtworkProviderChoiceKey.DEFAULT, ArtworkProviderChoiceKey.TOP_POSTERS, ArtworkProviderChoiceKey.RPDB),
        state.posterProviderChoices
    )
}

@Test
fun `top posters validation saves entitlement snapshot`() = runTest {
    coEvery { providerSettingsRepository.validateTopPostersApiKey("TP-key", forceRefresh = true) } returns
        TopPostersEntitlementSnapshot(true, true, 1, "Premium", true, 10L, 86_400_010L)

    viewModel.validateAndSaveTopPostersApiKey("TP-key") {}

    coVerify { dataStore.setTopPostersApiKey("TP-key") }
    coVerify { dataStore.setTopPostersEntitlement(match { it?.episodeThumbnails == true }) }
}
```

- [ ] **Step 2: Run ViewModel tests and verify they fail**

Run:

```bash
./gradlew --no-build-cache testDebugUnitTest --tests 'com.nexio.tv.ui.screens.settings.PosterRatingsSettingsViewModelTest'
```

Expected: failures show toggle fields and events still exist.

- [ ] **Step 3: Replace UI state**

In `PosterRatingsSettingsViewModel.kt`, replace `PosterRatingsSettingsUiState` with:

```kotlin
data class PosterRatingsSettingsUiState(
    val rpdbApiKey: String = "",
    val topPostersApiKey: String = "",
    val topPostersTierLabel: String? = null,
    val topPostersEpisodeThumbnailsAvailable: Boolean = false,
    val posterProvider: ArtworkProviderChoiceKey = ArtworkProviderChoiceKey.DEFAULT,
    val logoProvider: ArtworkProviderChoiceKey = ArtworkProviderChoiceKey.DEFAULT,
    val backdropProvider: ArtworkProviderChoiceKey = ArtworkProviderChoiceKey.DEFAULT,
    val thumbnailProvider: ArtworkProviderChoiceKey = ArtworkProviderChoiceKey.DEFAULT,
    val posterProviderChoices: List<ArtworkProviderChoiceKey> = listOf(ArtworkProviderChoiceKey.DEFAULT),
    val logoProviderChoices: List<ArtworkProviderChoiceKey> = listOf(ArtworkProviderChoiceKey.DEFAULT),
    val backdropProviderChoices: List<ArtworkProviderChoiceKey> = listOf(ArtworkProviderChoiceKey.DEFAULT),
    val thumbnailProviderChoices: List<ArtworkProviderChoiceKey> = listOf(ArtworkProviderChoiceKey.DEFAULT),
    val hasRpdbToggle: Boolean = false,
    val hasTopPostersToggle: Boolean = false
)
```

Map settings:

```kotlin
private fun PosterRatingsSettingsUiState.fromSettings(
    settings: ArtworkProviderSettings,
    registry: ArtworkProviderRegistry
): PosterRatingsSettingsUiState = copy(
    rpdbApiKey = settings.rpdbApiKey,
    topPostersApiKey = settings.topPostersApiKey,
    topPostersTierLabel = settings.topPostersEntitlement?.tierName,
    topPostersEpisodeThumbnailsAvailable = settings.topPostersCanProvideThumbnails,
    posterProvider = settings.selection.posterProvider,
    logoProvider = settings.selection.logoProvider,
    backdropProvider = settings.selection.backdropProvider,
    thumbnailProvider = settings.selection.thumbnailProvider,
    posterProviderChoices = registry.availableChoices(ArtworkType.POSTER, settings),
    logoProviderChoices = registry.availableChoices(ArtworkType.LOGO, settings),
    backdropProviderChoices = registry.availableChoices(ArtworkType.BACKDROP, settings),
    thumbnailProviderChoices = registry.availableChoices(ArtworkType.THUMBNAIL, settings)
)
```

- [ ] **Step 4: Replace toggle events with selection events**

Use:

```kotlin
sealed class PosterRatingsSettingsEvent {
    data class SelectProvider(val type: ArtworkTypeKey, val provider: ArtworkProviderChoiceKey) : PosterRatingsSettingsEvent()
    data object InvalidatePosterCache : PosterRatingsSettingsEvent()
}
```

Handle it:

```kotlin
is PosterRatingsSettingsEvent.SelectProvider -> update {
    dataStore.setProviderSelection(event.type, event.provider)
    invalidateArtworkDisplayState()
}
```

- [ ] **Step 5: Save Top Posters entitlement during validation**

In `validateAndSaveTopPostersApiKey`:

```kotlin
val entitlement = providerSettingsRepository.validateTopPostersApiKey(trimmed, forceRefresh = true)
_validatingTopPosters.value = false
if (entitlement?.valid == true) {
    dataStore.setTopPostersApiKey(trimmed)
    dataStore.setTopPostersEntitlement(entitlement)
    invalidateArtworkDisplayState()
    onSuccess()
} else {
    _validationError.tryEmit(PosterRatingsProviderType.TOP_POSTERS)
}
```

When the key is blank:

```kotlin
dataStore.setTopPostersApiKey("")
dataStore.setTopPostersEntitlement(null)
dataStore.setProviderSelection(ArtworkTypeKey.POSTER, ArtworkProviderChoiceKey.DEFAULT)
dataStore.setProviderSelection(ArtworkTypeKey.THUMBNAIL, ArtworkProviderChoiceKey.DEFAULT)
```

- [ ] **Step 6: Update settings screen**

Remove RPDB and Top Posters enable/disable toggle rows. Keep API key rows and add four selection rows:

```kotlin
ProviderSelectionRow(
    title = "Poster provider",
    selected = uiState.posterProvider,
    choices = uiState.posterProviderChoices,
    onSelect = { onEvent(PosterRatingsSettingsEvent.SelectProvider(ArtworkTypeKey.POSTER, it)) }
)
ProviderSelectionRow(
    title = "Logo provider",
    selected = uiState.logoProvider,
    choices = uiState.logoProviderChoices,
    onSelect = { onEvent(PosterRatingsSettingsEvent.SelectProvider(ArtworkTypeKey.LOGO, it)) }
)
ProviderSelectionRow(
    title = "Backdrop provider",
    selected = uiState.backdropProvider,
    choices = uiState.backdropProviderChoices,
    onSelect = { onEvent(PosterRatingsSettingsEvent.SelectProvider(ArtworkTypeKey.BACKDROP, it)) }
)
ProviderSelectionRow(
    title = "Thumbnail provider",
    selected = uiState.thumbnailProvider,
    choices = uiState.thumbnailProviderChoices,
    onSelect = { onEvent(PosterRatingsSettingsEvent.SelectProvider(ArtworkTypeKey.THUMBNAIL, it)) }
)
```

Disable a selector when `choices.size == 1`.

- [ ] **Step 7: Run settings tests**

Run:

```bash
./gradlew --no-build-cache testDebugUnitTest --tests 'com.nexio.tv.ui.screens.settings.PosterRatingsSettingsViewModelTest'
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit Task 11**

Run:

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/settings/PosterRatingsSettingsViewModel.kt app/src/main/java/com/nexio/tv/ui/screens/settings/PosterRatingsSettingsScreen.kt app/src/test/java/com/nexio/tv/ui/screens/settings/PosterRatingsSettingsViewModelTest.kt
git commit -m "feat: replace premium toggles with artwork selectors"
```

Expected: commit succeeds with settings UI paths staged.

## Task 12: Add Audit Scenarios And Trace Fields

**Files:**

- Modify: `app/src/test/java/com/nexio/tv/metadata/audit/MetadataAuditRunner.kt`
- Modify: `app/src/test/java/com/nexio/tv/metadata/audit/MetadataAuditReport.kt`
- Modify: `app/src/test/java/com/nexio/tv/metadata/audit/MetadataAuditReportWriter.kt`
- Modify: `app/src/test/java/com/nexio/tv/metadata/audit/MetadataExecutionAuditGoldenTest.kt`
- Modify: `app/src/test/resources/integration/expected_integration_contracts.yaml`

- [ ] **Step 1: Write failing audit assertions**

Add golden assertions:

```kotlin
@Test
fun `top posters thumbnail selected hides local rating overlay in audit`() {
    val report = auditReport("premium-artwork-topposters-thumbnail")
    val thumbnail = report.items.single().artworkAudit.single { it.field == "episode.thumbnail" }

    assertEquals("TOP_POSTERS", thumbnail.selectedProvider)
    assertEquals("topposters.thumbnail", thumbnail.runtimeApiShapeId)
    assertEquals(true, thumbnail.embedsRatingOverlay)
    assertEquals(true, thumbnail.localRatingOverlaySuppressed)
}

@Test
fun `top posters thumbnail fallback shows local rating overlay in audit`() {
    val report = auditReport("premium-artwork-topposters-thumbnail-fallback")
    val thumbnail = report.items.single().artworkAudit.single { it.field == "episode.thumbnail" }

    assertNotEquals("TOP_POSTERS", thumbnail.selectedProvider)
    assertEquals(false, thumbnail.embedsRatingOverlay)
    assertEquals(false, thumbnail.localRatingOverlaySuppressed)
}

@Test
fun `top posters thumbnail runtime shape is active covered in audit`() {
    val report = auditReport("premium-artwork-topposters-thumbnail")
    val thumbnail = report.items.single().artworkAudit.single { it.field == "episode.thumbnail" }

    assertEquals("topposters.thumbnail", thumbnail.runtimeApiShapeId)
    assertEquals("HIT", thumbnail.assetCacheDecision)
    assertFalse(thumbnail.rawRemoteUrlUsedByUi)
    assertTrue(thumbnail.coilModel!!.startsWith("nexio-artwork://"))
    assertFalse(thumbnail.coilModel!!.startsWith("https://"))
}

@Test
fun `thumbnail entitlement rejection reasons are present in audit`() {
    val report = auditReport("premium-artwork-topposters-thumbnail-entitlement-missing")
    val thumbnail = report.items.single().artworkAudit.single { it.field == "episode.thumbnail" }

    assertTrue(thumbnail.rejectedCandidates.any { rejected ->
        rejected["provider"] == "TOP_POSTERS" &&
            rejected["reason"] == "topposters_entitlement_missing"
    })
}

@Test
fun `artwork rejected candidates always include provider source role and reason`() {
    val report = auditReport("premium-artwork-topposters-thumbnail-entitlement-missing")
    val rejected = report.items
        .flatMap { it.artworkAudit }
        .flatMap { it.rejectedCandidates }

    assertTrue("Scenario must contain rejected artwork candidates.", rejected.isNotEmpty())
    rejected.forEach { candidate ->
        assertFalse(candidate["provider"].isNullOrBlank())
        assertFalse(candidate["sourceRole"].isNullOrBlank())
        assertFalse(candidate["reason"].isNullOrBlank())
    }
}

@Test
fun `top posters thumbnail rejection reason matrix is audited`() {
    val expectations = mapOf(
        "premium-artwork-topposters-thumbnail-entitlement-missing" to "topposters_entitlement_missing",
        "premium-artwork-topposters-thumbnail-entitlement-inactive" to "topposters_entitlement_inactive",
        "premium-artwork-topposters-thumbnail-tier-not-premium" to "topposters_tier_not_premium",
        "premium-artwork-topposters-thumbnail-feature-disabled" to "topposters_episode_thumbnails_not_enabled",
        "premium-artwork-topposters-thumbnail-missing-episode-context" to "missing_episode_context",
        "premium-artwork-topposters-thumbnail-unsupported-id" to "missing_supported_id",
        "premium-artwork-topposters-thumbnail-fetch-failed" to "topposters_thumbnail_failed",
        "premium-artwork-topposters-thumbnail-429-no-stale" to "topposters_thumbnail_429_no_stale"
    )

    expectations.forEach { (scenarioName, expectedReason) ->
        val report = auditReport(scenarioName)
        val thumbnail = report.items.single().artworkAudit.single { it.field == "episode.thumbnail" }
        assertTrue("$scenarioName rejected candidates: ${thumbnail.rejectedCandidates}", thumbnail.rejectedCandidates.any { rejected ->
            rejected["provider"] == "TOP_POSTERS" &&
                rejected["sourceRole"] == "PREMIUM" &&
                rejected["reason"] == expectedReason
        })
    }
}
```

- [ ] **Step 2: Run audit tests and verify they fail**

Run:

```bash
./gradlew --no-build-cache testDebugUnitTest --tests 'com.nexio.tv.metadata.audit.MetadataExecutionAuditGoldenTest'
```

Expected: failure because scenarios and fields are absent.

- [ ] **Step 3: Extend audit report model**

Add nullable fields to the artwork audit entry:

```kotlin
val embedsRatingOverlay: Boolean = false,
val localRatingOverlaySuppressed: Boolean = false
```

Update markdown and JSON writers to include both fields.

- [ ] **Step 4: Add scenarios**

Add scenarios:

```kotlin
MetadataAuditScenario(
    name = "premium-artwork-topposters-thumbnail",
    depth = MetadataDepth.DETAIL_CORE,
    visibleItemIds = setOf("tt0388629"),
    premiumArtworkProvider = "TOP_POSTERS",
    thumbnailArtworkProvider = "TOP_POSTERS",
    cacheMode = AuditCacheMode.WARM_FRESH
)

MetadataAuditScenario(
    name = "premium-artwork-topposters-thumbnail-fallback",
    depth = MetadataDepth.DETAIL_CORE,
    visibleItemIds = setOf("no-supported-id-episode"),
    premiumArtworkProvider = "TOP_POSTERS",
    thumbnailArtworkProvider = "TOP_POSTERS",
    cacheMode = AuditCacheMode.COLD
)

MetadataAuditScenario(
    name = "premium-artwork-topposters-thumbnail-entitlement-missing",
    depth = MetadataDepth.DETAIL_CORE,
    visibleItemIds = setOf("tt0388629"),
    premiumArtworkProvider = "TOP_POSTERS",
    thumbnailArtworkProvider = "TOP_POSTERS",
    topPostersEntitlementFixture = TopPostersEntitlementFixture.MISSING,
    cacheMode = AuditCacheMode.COLD
)

MetadataAuditScenario(
    name = "premium-artwork-topposters-thumbnail-entitlement-inactive",
    depth = MetadataDepth.DETAIL_CORE,
    visibleItemIds = setOf("tt0388629"),
    thumbnailArtworkProvider = "TOP_POSTERS",
    topPostersEntitlementFixture = TopPostersEntitlementFixture.INACTIVE,
    cacheMode = AuditCacheMode.COLD
)

MetadataAuditScenario(
    name = "premium-artwork-topposters-thumbnail-tier-not-premium",
    depth = MetadataDepth.DETAIL_CORE,
    visibleItemIds = setOf("tt0388629"),
    thumbnailArtworkProvider = "TOP_POSTERS",
    topPostersEntitlementFixture = TopPostersEntitlementFixture.TIER_NOT_PREMIUM,
    cacheMode = AuditCacheMode.COLD
)

MetadataAuditScenario(
    name = "premium-artwork-topposters-thumbnail-feature-disabled",
    depth = MetadataDepth.DETAIL_CORE,
    visibleItemIds = setOf("tt0388629"),
    thumbnailArtworkProvider = "TOP_POSTERS",
    topPostersEntitlementFixture = TopPostersEntitlementFixture.EPISODE_THUMBNAILS_DISABLED,
    cacheMode = AuditCacheMode.COLD
)

MetadataAuditScenario(
    name = "premium-artwork-topposters-thumbnail-missing-episode-context",
    depth = MetadataDepth.DETAIL_CORE,
    visibleItemIds = setOf("tt0388629"),
    thumbnailArtworkProvider = "TOP_POSTERS",
    episodeContextFixture = EpisodeContextFixture.MISSING,
    cacheMode = AuditCacheMode.COLD
)

MetadataAuditScenario(
    name = "premium-artwork-topposters-thumbnail-unsupported-id",
    depth = MetadataDepth.DETAIL_CORE,
    visibleItemIds = setOf("no-supported-id-episode"),
    thumbnailArtworkProvider = "TOP_POSTERS",
    cacheMode = AuditCacheMode.COLD
)

MetadataAuditScenario(
    name = "premium-artwork-topposters-thumbnail-fetch-failed",
    depth = MetadataDepth.DETAIL_CORE,
    visibleItemIds = setOf("tt0388629"),
    thumbnailArtworkProvider = "TOP_POSTERS",
    thumbnailFetchFixture = ThumbnailFetchFixture.HTTP_500,
    cacheMode = AuditCacheMode.COLD
)

MetadataAuditScenario(
    name = "premium-artwork-topposters-thumbnail-429-no-stale",
    depth = MetadataDepth.DETAIL_CORE,
    visibleItemIds = setOf("tt0388629"),
    thumbnailArtworkProvider = "TOP_POSTERS",
    thumbnailFetchFixture = ThumbnailFetchFixture.HTTP_429_NO_STALE,
    cacheMode = AuditCacheMode.COLD
)
```

Add this property to `MetadataAuditScenario`:

```kotlin
val thumbnailArtworkProvider: String? = null
```

Convert it into `ArtworkProviderSelectionSettings` in the scenario setup:

```kotlin
thumbnailProvider = when (scenario.thumbnailArtworkProvider) {
    "TOP_POSTERS" -> ArtworkProviderChoiceKey.TOP_POSTERS
    else -> ArtworkProviderChoiceKey.DEFAULT
}
```

- [ ] **Step 5: Add mandatory rejected candidate trace reasons**

Add `topPostersEntitlementFixture` to `MetadataAuditScenario`:

```kotlin
enum class TopPostersEntitlementFixture {
    PREMIUM_WITH_THUMBNAILS,
    MISSING,
    INACTIVE,
    TIER_NOT_PREMIUM,
    EPISODE_THUMBNAILS_DISABLED
}

val topPostersEntitlementFixture: TopPostersEntitlementFixture = TopPostersEntitlementFixture.PREMIUM_WITH_THUMBNAILS
```

Add these fixture properties to `MetadataAuditScenario`:

```kotlin
enum class EpisodeContextFixture {
    PRESENT,
    MISSING
}

enum class ThumbnailFetchFixture {
    SUCCESS,
    HTTP_500,
    HTTP_429_NO_STALE
}

val episodeContextFixture: EpisodeContextFixture = EpisodeContextFixture.PRESENT
val thumbnailFetchFixture: ThumbnailFetchFixture = ThumbnailFetchFixture.SUCCESS
```

Make the audit setup create the matching `TopPostersEntitlementSnapshot`, episode context, and fetch outcome. The rejection reason matrix test above must fail if any reason is missing from `MetadataAuditReport`.

For fetch failures, add rejected candidates when `ArtworkAssetRepository` cannot materialize the selected Top Posters thumbnail and falls back to primary:

```kotlin
RejectedArtworkCandidate(
    provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TOP_POSTERS),
    sourceRole = ArtworkSourceRole.PREMIUM,
    reason = "topposters_thumbnail_failed"
)
```

For `429` with no stale asset, use:

```kotlin
reason = "topposters_thumbnail_429_no_stale"
```

- [ ] **Step 6: Mark `topposters.thumbnail` active runtime-covered**

Update `app/src/test/resources/integration/expected_integration_contracts.yaml` so `topposters.thumbnail` is not planned, exempt, or direct-only:

```yaml
  topposters.thumbnail:
    provider: TOP_POSTERS
    lifecycleStatus: ACTIVE_RUNTIME_COVERED
    headerPolicy: topposters-thumbnail-v1
    cachePolicy: poster-generated-v1
    ttlMs: 86400000
    staleMs: 604800000
    runtimeRequired: true
```

The exact YAML field names must match the surrounding registry style, but the values above are mandatory.

- [ ] **Step 7: Run audit tests**

Run:

```bash
./gradlew --no-build-cache testDebugUnitTest --tests 'com.nexio.tv.metadata.audit.MetadataExecutionAuditGoldenTest'
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit Task 12**

Run:

```bash
git add app/src/test/java/com/nexio/tv/metadata/audit/MetadataAuditRunner.kt app/src/test/java/com/nexio/tv/metadata/audit/MetadataAuditReport.kt app/src/test/java/com/nexio/tv/metadata/audit/MetadataAuditReportWriter.kt app/src/test/java/com/nexio/tv/metadata/audit/MetadataExecutionAuditGoldenTest.kt app/src/test/resources/integration/expected_integration_contracts.yaml
git commit -m "test: audit premium artwork thumbnail routing"
```

Expected: commit succeeds with audit paths staged.

## Task 13: Add Architecture Guards

**Files:**

- Modify: `app/src/test/java/com/nexio/tv/architecture/RawRemoteArtworkUrlBoundaryTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/architecture/IntegrationProviderContractRegistryTest.kt`

- [ ] **Step 1: Add hard raw premium URL leakage tests**

Add to `RawRemoteArtworkUrlBoundaryTest`:

```kotlin
@Test
fun `scanner catches premium provider urls in metadata artwork ui models`() {
    val offenders = premiumProviderArtworkUrlViolations(
        filePath = "app/src/main/java/com/nexio/tv/domain/model/FakeDisplayModel.kt",
        text = """
            data class FakeDisplayModel(
                val poster: String = "https://api.top-posters.com/TP-key/kitsu/poster/7442.jpg",
                val thumbnail: String = "https://api.ratingposterdb.com/abc/imdb/poster/tt123.jpg"
            )
        """.trimIndent()
    )

    assertEquals(
        listOf(
            "app/src/main/java/com/nexio/tv/domain/model/FakeDisplayModel.kt:2:premium-provider-url",
            "app/src/main/java/com/nexio/tv/domain/model/FakeDisplayModel.kt:3:premium-provider-url"
        ),
        offenders
    )
}

@Test
fun `scanner allows internal artwork and placeholder schemes in metadata artwork ui models`() {
    val offenders = premiumProviderArtworkUrlViolations(
        filePath = "app/src/main/java/com/nexio/tv/domain/model/FakeDisplayModel.kt",
        text = """
            data class FakeDisplayModel(
                val poster: String = "nexio-artwork://asset/posterAsset",
                val thumbnail: String = "nexio-artwork://decision/thumbnailDecision",
                val fallback: String = "nexio-placeholder://thumbnail/default"
            )
        """.trimIndent()
    )

    assertTrue("Internal artwork schemes must be allowed: $offenders", offenders.isEmpty())
}

@Test
fun `metadata artwork ui models do not expose premium provider urls`() {
    val offenders = metadataArtworkUiDisplayModelFiles()
        .flatMap { file ->
            premiumProviderArtworkUrlViolations(
                filePath = file.invariantSeparatorsPath,
                text = file.readText()
            )
        }

    if (offenders.isNotEmpty()) {
        fail(
            "Metadata artwork UI models must expose internal artwork refs, not raw premium provider URLs:\n" +
                offenders.joinToString(separator = "\n")
        )
    }
}
```

Add helpers:

```kotlin
private fun metadataArtworkUiDisplayModelFiles(): List<File> =
    listOf(
        File("app/src/main/java/com/nexio/tv/domain/model/Meta.kt"),
        File("app/src/main/java/com/nexio/tv/domain/model/HomeDisplayMetadata.kt"),
        File("app/src/main/java/com/nexio/tv/ui/screens/home/HomeUiState.kt"),
        File("app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeModels.kt"),
        File("app/src/main/java/com/nexio/tv/ui/screens/detail/EpisodesSection.kt"),
        File("app/src/main/java/com/nexio/tv/ui/components/ContentCard.kt"),
        File("app/src/main/java/com/nexio/tv/ui/components/GridContentCard.kt")
    ).onEach { file ->
        require(file.isFile) { "Required metadata artwork UI model file is missing: ${file.path}" }
    }

private fun premiumProviderArtworkUrlViolations(filePath: String, text: String): List<String> =
    text.lines().flatMapIndexed { index, line ->
        if (premiumProviderUrlRegex.containsMatchIn(line)) {
            listOf("$filePath:${index + 1}:premium-provider-url")
        } else {
            emptyList()
        }
    }

private val premiumProviderUrlRegex = Regex(
    """https://(?:api\.top-posters\.com|api\.ratingposterdb\.com)"""
)
```

- [ ] **Step 2: Add hard `topposters.thumbnail` contract tests**

Add to `IntegrationProviderContractRegistryTest`:

```kotlin
@Test
fun `top posters thumbnail contract is active runtime covered`() {
    val source = registry.readText()
    val block = apiShapeBlock(source, "topposters.thumbnail")

    assertTrue(block.contains("lifecycleStatus: ACTIVE_RUNTIME_COVERED"))
    assertTrue(block.contains("headerPolicy: topposters-thumbnail-v1"))
    assertTrue(block.contains("ttlMs: 86400000"))
    assertTrue(block.contains("staleMs: 604800000"))
    assertTrue(block.contains("runtimeRequired: true"))
    assertTrue("topposters.thumbnail must not be planned or exempt: $block", !block.contains("PLANNED_NOT_ACTIVE"))
    assertTrue("topposters.thumbnail must not be exempt: $block", !block.contains("EXEMPT"))
}

private fun apiShapeBlock(source: String, shapeId: String): String {
    val start = source.indexOf("  $shapeId:")
    require(start >= 0) { "Missing contract for $shapeId" }
    val next = Regex("""\n  [A-Za-z0-9_.-]+:""")
        .find(source, start + 1)
        ?.range
        ?.first
        ?: source.length
    return source.substring(start, next)
}
```

- [ ] **Step 3: Run architecture guard tests**

Run:

```bash
./gradlew --no-build-cache testDebugUnitTest --tests 'com.nexio.tv.architecture.RawRemoteArtworkUrlBoundaryTest' --tests 'com.nexio.tv.architecture.IntegrationProviderContractRegistryTest'
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit Task 13**

Run:

```bash
git add app/src/test/java/com/nexio/tv/architecture/RawRemoteArtworkUrlBoundaryTest.kt app/src/test/java/com/nexio/tv/architecture/IntegrationProviderContractRegistryTest.kt
git commit -m "test: guard premium artwork runtime boundaries"
```

Expected: commit succeeds with architecture test paths staged.

## Task 14: Full Verification

**Files:**

- Review all files changed by Tasks 1-13.

- [ ] **Step 1: Search for removed toggle usage**

Run:

```bash
rg -n "ToggleRpdb|ToggleTopPosters|setRpdbEnabled|setTopPostersEnabled|rpdbEnabled|topPostersEnabled|activePremiumProvider|activeProvider" app/src/main/java app/src/test/java
```

Expected: no production usage. Legacy preference keys and migration tests may still contain `rpdbEnabled` and `topPostersEnabled`.

- [ ] **Step 2: Search for raw premium artwork URL leakage**

Run:

```bash
rg -n "api\\.top-posters\\.com|ratingposterdb\\.com|rpdbPosterUrl|topPostersPosterUrl|resolvePosterUrl" app/src/main/java app/src/test/java
```

Expected:

- Raw provider hosts appear only inside `TopPostersIntegrationProvider`, `RpdbIntegrationProvider`, and tests that assert those hosts do not leak to UI.
- `resolvePosterUrl` has no production references.
- No UI, metadata model, or legacy display string code returns Top Posters or RPDB HTTPS URLs.

- [ ] **Step 3: Search for parallel Top Posters network paths**

Run:

```bash
rg -n "topPostersApi\\.verifyApiKey|posterTransport\\.execute|https://api\\.top-posters\\.com" app/src/main/java/com/nexio/tv
```

Expected:

- `topPostersApi.verifyApiKey` appears only inside `TopPostersIntegrationProvider` runtime `load`.
- `posterTransport.execute` appears only inside integration provider runtime `load` blocks.
- Top Posters URL construction appears only inside `TopPostersIntegrationProvider`.

- [ ] **Step 4: Run targeted unit test suite**

Run:

```bash
./gradlew --no-build-cache testDebugUnitTest \
  --tests 'com.nexio.tv.domain.model.ArtworkProviderSettingsTest' \
  --tests 'com.nexio.tv.data.local.PosterRatingsSettingsDataStoreTest' \
  --tests 'com.nexio.tv.data.integration.posters.TopPostersEntitlementTest' \
  --tests 'com.nexio.tv.data.integration.posters.TopPostersIntegrationProviderTest' \
  --tests 'com.nexio.tv.core.artwork.ArtworkProviderRegistryTest' \
  --tests 'com.nexio.tv.core.artwork.ArtworkExternalIdSelectorTest' \
  --tests 'com.nexio.tv.core.artwork.ArtworkRouterTest' \
  --tests 'com.nexio.tv.core.poster.PosterRatingsUrlResolverTest' \
  --tests 'com.nexio.tv.data.integration.posters.PremiumPosterMetadataProviderAdapterStableIdTest' \
  --tests 'com.nexio.tv.core.metadata.router.MetadataRouterFacadeTest' \
  --tests 'com.nexio.tv.core.artwork.ArtworkModelsTest' \
  --tests 'com.nexio.tv.domain.model.HomeDisplayMetadataTest' \
  --tests 'com.nexio.tv.ui.screens.settings.PosterRatingsSettingsViewModelTest' \
  --tests 'com.nexio.tv.metadata.audit.MetadataExecutionAuditGoldenTest'
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Run architecture tests for runtime boundaries**

Run:

```bash
./gradlew --no-build-cache testDebugUnitTest \
  --tests 'com.nexio.tv.architecture.IntegrationProviderContractRegistryTest' \
  --tests 'com.nexio.tv.architecture.NoUnwrappedProviderCallsInsideIntegrationPackagesTest' \
  --tests 'com.nexio.tv.architecture.RawRemoteArtworkUrlBoundaryTest'
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Run full unit test task**

Run:

```bash
./gradlew --no-build-cache testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Build debug APK**

Run:

```bash
./gradlew --no-build-cache assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Inspect git status**

Run:

```bash
git status --short
```

Expected: only intended task files are modified or untracked. Existing unrelated user changes may remain; do not stage them.

- [ ] **Step 9: Commit final integration fixes**

If Step 5 or Step 6 required small fixes, stage only the files changed for this feature:

```bash
git add app/src/main/java/com/nexio/tv/domain/model/ArtworkProviderSettings.kt app/src/main/java/com/nexio/tv/core/artwork app/src/main/java/com/nexio/tv/data/local/PosterRatingsSettingsDataStore.kt app/src/main/java/com/nexio/tv/data/integration/posters app/src/main/java/com/nexio/tv/core/image app/src/main/java/com/nexio/tv/core/poster/PosterRatingsUrlResolver.kt app/src/main/java/com/nexio/tv/core/metadata/router app/src/main/java/com/nexio/tv/ui/screens/settings app/src/main/java/com/nexio/tv/ui/screens/detail/EpisodesSection.kt app/src/main/java/com/nexio/tv/domain/model/Meta.kt app/src/main/java/com/nexio/tv/domain/model/HomeDisplayMetadata.kt app/src/test/java/com/nexio/tv
git commit -m "fix: complete premium artwork provider selection"
```

Expected: commit succeeds or there is nothing to commit.

## Implementation Notes

- Do not expose a Top Posters tier picker. Tier and feature flags come from `GET /auth/verify/{api_key}`.
- Do not expose a Top Posters episode thumbnail toggle. The thumbnail provider selector is the opt-in control.
- Do not expose RPDB or Top Posters enabled toggles. A configured API key makes the provider available; provider selectors decide usage.
- Do not query Top Posters entitlement during every poster or thumbnail fetch. Normal validation uses `IntegrationRuntime` CacheFirst with 24-hour TTL.
- Force Top Posters thumbnail params on every thumbnail request: `badge_position=top-right`, `badge_size=small`, `blur=false`.
- Do not send `fallback_url` on Top Posters thumbnail requests in Phase 1.
- Do not return raw RPDB or Top Posters HTTPS URLs to UI, legacy string fields, or Coil models. Premium artwork display strings must use `nexio-artwork://asset/{assetKey}` or `nexio-artwork://decision/{decisionKey}`.
- Do not suppress local episode ratings based on global settings. Suppression must depend on `thumbnailArtwork.displayHints.embedsRatingOverlay`.
- Do not create a parallel HTTP path for validation, posters, or thumbnails. All new Top Posters API calls must use shared runtime components.
