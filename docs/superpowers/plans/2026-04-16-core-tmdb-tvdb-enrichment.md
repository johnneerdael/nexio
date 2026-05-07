# Core TMDB and TVDB Enrichment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make TMDB and TVDB enrichment core Nexio functionality that is enabled by default, backed by built-in server/app credentials, and still overridable by user-provided custom API keys.

**Architecture:** Keep existing user secret sync as the custom-key override path, but introduce a single metadata credential resolver that returns `CUSTOM` first and `BUILT_IN` second. Preserve existing TMDB/TVDB caches and request coalescing, then add explicit freshness TTLs for metadata cache entries so default-enabled enrichment does not increase provider load through repeated lookups.

**Tech Stack:** Android Kotlin + Hilt + Retrofit + DataStore + SharedPreferences disk cache, Nuxt 3 server runtime config, Vue portal settings, Node test runner, Android JVM tests.

---

## Current API Usage Analysis

TMDB usage in Android:
- `TmdbService` converts IMDb/TMDB IDs and already has in-memory maps plus in-flight de-duplication, but no disk persistence.
- `TmdbMetadataService.fetchEnrichment` fetches title details with `append_to_response=credits,images,release_dates|content_ratings`, which avoids separate credits/images/release-rating calls for the main enrichment path.
- TMDB episode requests use `fetchEpisodeEnrichment`/`fetchSeasonEpisodes`; existing tests verify overlapping episode requests reuse the season-level cache.
- `TrailerService` caches TMDB title and season videos through `MetadataDiskCacheStore` with a 12-hour TTL.
- `MetadataDiskCacheStore` stores TMDB enrichment without an explicit TTL today. This avoids frequent calls, but it can keep stale metadata forever unless schema changes.

TVDB usage in Android:
- `TvdbAuthService` caches bearer tokens for 30 days with a 24-hour refresh skew.
- `TvdbMetadataService` reads TVDB series and season caches before network calls, and serves stale cache on credential/network failure.
- `TvdbUpdateCoordinator` runs TVDB `/updates` catch-up on startup and every 12 hours.
- `TvMetadataRouter` skips TMDB TV metadata when TVDB succeeds, which is the right provider-load behavior and must be preserved.
- `TvdbTrailerResolver` currently calls `getSeriesExtended` independently and does not reuse `TvdbMetadataService`'s disk cache; this is an unnecessary API call path to close.

Web usage:
- `nexio-web` only validates TVDB directly in `server/api/integrations/tvdb/validate.post.ts`; TMDB is stored as a custom secret but not validated server-side.
- Portal settings currently model TMDB/TVDB as optional integrations: defaults are disabled, cards can appear in the "available" section, enable toggles exist, and `deleteIntegration` can reset/delete them.

TTL policy to implement:
- TMDB title enrichment: 7 days. The provider data is mostly stable but can change for new releases, artwork, translations, and ratings.
- TVDB series enrichment: 7 days. TVDB `/updates` remains the faster invalidation path; TTL prevents indefinite staleness when update events are missed.
- TVDB season episodes: 24 hours. Air dates, episode names, runtime, and thumbnails can shift while a season is active.
- TVDB reference data: 30 days. Reference labels are stable and already warmed by update coordination.
- TMDB trailer videos: keep the existing 12-hour TTL.
- TVDB bearer token: keep the existing 30-day TTL and 24-hour refresh skew.
- TVDB `/updates`: keep the existing 12-hour periodic interval.

## File Structure

Android credential and config layer:
- Create `app/src/main/java/com/nexio/tv/core/metadata/MetadataProviderConfig.kt`
  - Own default TMDB/TVDB URLs, BuildConfig URL normalization, and credential source modeling.
- Create `app/src/main/java/com/nexio/tv/core/metadata/MetadataApiKeyResolver.kt`
  - Resolves effective credentials in precedence order: local custom key from DataStore, then built-in BuildConfig key, then missing.
- Modify `app/build.gradle.kts`
  - Add `TMDB_API_KEY`, `TMDB_API_URL`, `TVDB_API_KEY`, `TVDB_API_URL` BuildConfig fields from `local.dev.properties` before `local.properties`.
- Modify `app/src/main/java/com/nexio/tv/core/di/NetworkModule.kt`
  - Use the configured TMDB/TVDB base URLs instead of hard-coded URLs.

Android settings and sync:
- Modify `app/src/main/java/com/nexio/tv/domain/model/TmdbSettings.kt`
  - Default enabled to true and make activation independent of custom key presence.
- Modify `app/src/main/java/com/nexio/tv/domain/model/TvdbSettings.kt`
  - Default enabled/configured behavior to core-on and treat `INVALID` as custom-key-invalid, not integration-disabled.
- Modify `app/src/main/java/com/nexio/tv/data/local/TmdbSettingsDataStore.kt`
  - Default `tmdb_enabled` to true and force any old false value back to true.
- Modify `app/src/main/java/com/nexio/tv/data/local/TvdbSettingsDataStore.kt`
  - Default `tvdb_enabled` to true, keep optional custom key storage, and avoid clearing core enablement when custom credentials are cleared.
- Modify `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt`
  - Apply remote TMDB/TVDB settings as enabled regardless of old remote false values; continue syncing custom secrets only when users provide keys.
- Modify `app/src/main/java/com/nexio/tv/data/remote/supabase/AccountSyncModels.kt`
  - Change sync defaults for TMDB/TVDB enabled/configured.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/settings/TmdbSettingsScreen.kt`
  - Remove the enable toggle; keep optional custom API-key entry.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/settings/TvdbSettingsScreen.kt`
  - Remove the enable toggle; keep optional custom API-key/PIN entry and explain built-in fallback.
- Modify localized strings under `app/src/main/res/values*/strings.xml`
  - Replace "required" copy with "optional custom key" copy.

Android services and cache:
- Modify `app/src/main/java/com/nexio/tv/core/tmdb/TmdbService.kt`
- Modify `app/src/main/java/com/nexio/tv/core/tmdb/TmdbMetadataService.kt`
- Modify `app/src/main/java/com/nexio/tv/core/tmdb/TmdbOrganizationService.kt`
- Modify `app/src/main/java/com/nexio/tv/data/trailer/TrailerService.kt`
- Modify `app/src/main/java/com/nexio/tv/core/tvdb/TvdbAuthService.kt`
- Modify `app/src/main/java/com/nexio/tv/core/tvdb/TvdbCredentialHealth.kt`
- Modify `app/src/main/java/com/nexio/tv/core/tvdb/TvdbTrailerResolver.kt`
- Modify `app/src/main/java/com/nexio/tv/data/local/MetadataDiskCacheStore.kt`
  - Add effective-key use, stable cache tokens, metadata TTLs, and TVDB trailer cache reuse.

Web server and portal:
- Modify `nexio-web/nuxt.config.ts`
- Modify `nexio-web/compose.yml`
- Create `nexio-web/server/utils/metadata-provider-config.ts`
- Modify `nexio-web/server/api/integrations/tvdb/validate.post.ts`
- Modify `nexio-web/utils/portal-defaults.ts`
- Modify `nexio-web/utils/portal-settings.ts`
- Modify `nexio-web/utils/integration-delete.ts`
- Modify `nexio-web/utils/integration-secret-bindings.ts`
- Modify `nexio-web/composables/usePortalStore.ts`
- Modify `nexio-web/components/portal/SettingsWorkspace.vue`
- Modify `nexio-web/types/portal.ts`
  - Add runtime envs, core defaults, and remove disable/delete behavior for TMDB/TVDB while preserving "remove custom key" actions.

Tests:
- Add `app/src/test/java/com/nexio/tv/core/metadata/MetadataApiKeyResolverTest.kt`
- Add or extend `app/src/test/java/com/nexio/tv/core/tmdb/TmdbMetadataPerformanceTest.kt`
- Add or extend `app/src/test/java/com/nexio/tv/core/tvdb/TvMetadataRouterTest.kt`
- Add or extend `app/src/test/java/com/nexio/tv/data/local/MetadataDiskCacheStoreTest.kt`
- Add or extend `app/src/test/java/com/nexio/tv/core/sync/AccountConfigSyncContractTest.kt`
- Add `nexio-web/tests/metadata-provider-config.test.ts`
- Extend `nexio-web/tests/portal-contract-v4.test.ts`
- Extend `nexio-web/tests/integration-delete.test.ts`

## Tasks

### Task 1: Add Failing Tests for Core Default Behavior

**Files:**
- Create: `app/src/test/java/com/nexio/tv/core/metadata/MetadataApiKeyResolverTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/tvdb/TvMetadataRouterTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/sync/AccountConfigSyncContractTest.kt`
- Modify: `nexio-web/tests/portal-contract-v4.test.ts`
- Modify: `nexio-web/tests/integration-delete.test.ts`

- [ ] **Step 1: Write Android resolver tests**

```kotlin
package com.nexio.tv.core.metadata

import com.nexio.tv.data.local.TmdbSettingsDataStore
import com.nexio.tv.data.local.TvdbSettingsDataStore
import com.nexio.tv.domain.model.TmdbSettings
import com.nexio.tv.domain.model.TvdbSettings
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataApiKeyResolverTest {
    @Test
    fun `tmdb custom key overrides builtin key`() = runTest {
        val tmdbStore = mockk<TmdbSettingsDataStore>()
        val tvdbStore = mockk<TvdbSettingsDataStore>()
        every { tmdbStore.settings } returns flowOf(TmdbSettings(apiKey = " custom-tmdb "))
        every { tvdbStore.settings } returns flowOf(TvdbSettings())

        val resolver = MetadataApiKeyResolver(
            tmdbSettingsDataStore = tmdbStore,
            tvdbSettingsDataStore = tvdbStore,
            builtInTmdbKey = { "builtin-tmdb" },
            builtInTvdbKey = { "builtin-tvdb" }
        )

        val credential = resolver.tmdbCredential()

        assertEquals("custom-tmdb", credential.apiKey)
        assertEquals(MetadataCredentialSource.CUSTOM, credential.source)
    }

    @Test
    fun `tmdb falls back to builtin key when custom key is blank`() = runTest {
        val tmdbStore = mockk<TmdbSettingsDataStore>()
        val tvdbStore = mockk<TvdbSettingsDataStore>()
        every { tmdbStore.settings } returns flowOf(TmdbSettings(apiKey = " "))
        every { tvdbStore.settings } returns flowOf(TvdbSettings())

        val resolver = MetadataApiKeyResolver(
            tmdbSettingsDataStore = tmdbStore,
            tvdbSettingsDataStore = tvdbStore,
            builtInTmdbKey = { "builtin-tmdb" },
            builtInTvdbKey = { "builtin-tvdb" }
        )

        val credential = resolver.tmdbCredential()

        assertEquals("builtin-tmdb", credential.apiKey)
        assertEquals(MetadataCredentialSource.BUILT_IN, credential.source)
    }

    @Test
    fun `tvdb custom key overrides builtin key and keeps pin`() = runTest {
        val tmdbStore = mockk<TmdbSettingsDataStore>()
        val tvdbStore = mockk<TvdbSettingsDataStore>()
        every { tmdbStore.settings } returns flowOf(TmdbSettings())
        every { tvdbStore.settings } returns flowOf(TvdbSettings(apiKey = " custom-tvdb ", subscriberPin = " 1234 "))

        val resolver = MetadataApiKeyResolver(
            tmdbSettingsDataStore = tmdbStore,
            tvdbSettingsDataStore = tvdbStore,
            builtInTmdbKey = { "builtin-tmdb" },
            builtInTvdbKey = { "builtin-tvdb" }
        )

        val credential = resolver.tvdbCredential()

        assertEquals("custom-tvdb", credential.apiKey)
        assertEquals("1234", credential.pin)
        assertEquals(MetadataCredentialSource.CUSTOM, credential.source)
    }

    @Test
    fun `missing credentials are explicit`() = runTest {
        val tmdbStore = mockk<TmdbSettingsDataStore>()
        val tvdbStore = mockk<TvdbSettingsDataStore>()
        every { tmdbStore.settings } returns flowOf(TmdbSettings(apiKey = ""))
        every { tvdbStore.settings } returns flowOf(TvdbSettings(apiKey = ""))

        val resolver = MetadataApiKeyResolver(
            tmdbSettingsDataStore = tmdbStore,
            tvdbSettingsDataStore = tvdbStore,
            builtInTmdbKey = { "" },
            builtInTvdbKey = { "" }
        )

        assertTrue(resolver.tmdbCredential().missing)
        assertTrue(resolver.tvdbCredential().missing)
    }
}
```

- [ ] **Step 2: Extend router tests so TVDB is no longer inactive by default**

Add this test to `app/src/test/java/com/nexio/tv/core/tvdb/TvMetadataRouterTest.kt`:

```kotlin
@Test
fun `tvdb default settings route through tvdb instead of inactive fallback`() = runTest {
    val tvdbIdentityService = mockk<TvdbIdentityService>()
    val tvdbMetadataService = mockk<TvdbMetadataService>()
    val tmdbService = mockk<TmdbService>(relaxed = true)
    val tmdbMetadataService = mockk<TmdbMetadataService>(relaxed = true)
    val identity = TvdbSeriesIdentity(tvdbId = 121361)
    val router = tvMetadataRouter(
        settings = TvdbSettings(),
        tvdbIdentityService = tvdbIdentityService,
        tvdbMetadataService = tvdbMetadataService,
        tmdbService = tmdbService,
        tmdbMetadataService = tmdbMetadataService
    )

    coEvery {
        tvdbIdentityService.resolveSeriesByRemoteId("tt0944947", TvdbRemoteIdSource.IMDB)
    } returns identity
    coEvery { tvdbMetadataService.fetchSeriesEnrichment(identity, "en-US") } returns TvMetadataEnrichment(
        seriesTvdbId = 121361,
        localizedTitle = "Game of Thrones"
    )

    val decision = router.fetchEnrichment(
        TvMetadataRequest(
            contentId = "tt0944947",
            contentType = ContentType.SERIES,
            language = "en-US"
        )
    )

    assertEquals(TvProvider.TVDB, decision.provider)
    assertEquals(TvMetadataDecisionReason.TVDB_SUCCESS, decision.reason)
    coVerify(exactly = 0) { tmdbService.ensureTmdbId(any(), any()) }
    coVerify(exactly = 0) { tmdbMetadataService.fetchEnrichment(any(), any(), any()) }
}
```

- [ ] **Step 3: Extend Android sync contract tests**

Add this test to `app/src/test/java/com/nexio/tv/core/sync/AccountConfigSyncContractTest.kt` near the existing TMDB/TVDB sync tests:

```kotlin
@Test
fun `remote false cannot disable core tmdb or tvdb integrations`() = runTest {
    val tmdbSettingsDataStore = mockk<TmdbSettingsDataStore>(relaxed = true)
    val tvdbSettingsDataStore = mockk<TvdbSettingsDataStore>(relaxed = true)
    val service = buildService(
        tmdbSettingsDataStore = tmdbSettingsDataStore,
        tvdbSettingsDataStore = tvdbSettingsDataStore
    )
    val payload = accountSettingsPayload().copy(
        integrations = accountSettingsPayload().integrations.copy(
            tmdb = TmdbSyncSettings(enabled = false),
            tvdb = TvdbSyncSettings(
                enabled = false,
                configured = false,
                validationStatus = "NOT_CONFIGURED",
                lastFailure = ""
            )
        )
    )

    service.applyRemoteSettingsForTest(payload)

    coVerify { tmdbSettingsDataStore.setEnabled(true) }
    coVerify { tvdbSettingsDataStore.setEnabled(true) }
}
```

Use the existing `AccountConfigSyncContractTest` fixture builder names in this file if they differ from the snippet above; keep the assertions exactly about forced `true`.

- [ ] **Step 4: Extend web portal defaults tests**

Add these tests to `nexio-web/tests/portal-contract-v4.test.ts`:

```ts
test('default portal settings make TMDB and TVDB core enabled integrations', () => {
  const settings = defaultSettings()

  assert.equal(settings.integrations.tmdb.enabled, true)
  assert.equal(settings.integrations.tvdb.enabled, true)
  assert.equal(settings.integrations.tvdb.configured, true)
  assert.equal(settings.integrations.tvdb.validationStatus, 'VALID')
})

test('sanitized portal settings cannot disable TMDB or TVDB from stale remote data', () => {
  const settings = sanitizePortalSettings({
    integrations: {
      tmdb: { enabled: false },
      tvdb: {
        enabled: false,
        configured: false,
        validationStatus: 'NOT_CONFIGURED',
        lastFailure: 'old state'
      }
    }
  } as never)

  assert.equal(settings.integrations.tmdb.enabled, true)
  assert.equal(settings.integrations.tvdb.enabled, true)
  assert.equal(settings.integrations.tvdb.configured, true)
  assert.equal(settings.integrations.tvdb.validationStatus, 'VALID')
  assert.equal(settings.integrations.tvdb.lastFailure, '')
})
```

- [ ] **Step 5: Extend web deletion tests**

Add these tests to `nexio-web/tests/integration-delete.test.ts`:

```ts
test('TMDB and TVDB are not deletable integrations', () => {
  assert.equal(integrationSecretDeletion('tmdb' as never), null)
  assert.equal(integrationSecretDeletion('tvdb' as never), null)
})

test('resetIntegrationSettings keeps core TMDB and TVDB enabled', () => {
  const settings = defaultSettings()
  settings.integrations.tmdb.enabled = true
  settings.integrations.tvdb.enabled = true
  settings.integrations.tvdb.configured = true

  const afterTmdb = resetIntegrationSettings(settings, 'tmdb' as never)
  const afterTvdb = resetIntegrationSettings(settings, 'tvdb' as never)

  assert.equal(afterTmdb.integrations.tmdb.enabled, true)
  assert.equal(afterTvdb.integrations.tvdb.enabled, true)
  assert.equal(afterTvdb.integrations.tvdb.configured, true)
})
```

- [ ] **Step 6: Run tests to verify they fail**

Run:

```bash
./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.core.metadata.MetadataApiKeyResolverTest" --tests "com.nexio.tv.core.tvdb.TvMetadataRouterTest" --tests "com.nexio.tv.core.sync.AccountConfigSyncContractTest"
```

Expected: FAIL because `MetadataApiKeyResolver` does not exist and default settings still allow disabled TMDB/TVDB.

Run:

```bash
cd nexio-web
npm test -- tests/portal-contract-v4.test.ts tests/integration-delete.test.ts
```

Expected: FAIL because TMDB/TVDB defaults are still disabled and deletion still includes those integrations.

- [ ] **Step 7: Commit failing tests**

```bash
git add app/src/test/java/com/nexio/tv/core/metadata/MetadataApiKeyResolverTest.kt app/src/test/java/com/nexio/tv/core/tvdb/TvMetadataRouterTest.kt app/src/test/java/com/nexio/tv/core/sync/AccountConfigSyncContractTest.kt nexio-web/tests/portal-contract-v4.test.ts nexio-web/tests/integration-delete.test.ts
git commit -m "test: define core metadata integration defaults"
```

### Task 2: Add Android Built-In Credential and URL Configuration

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/metadata/MetadataProviderConfig.kt`
- Create: `app/src/main/java/com/nexio/tv/core/metadata/MetadataApiKeyResolver.kt`
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/java/com/nexio/tv/core/di/NetworkModule.kt`
- Test: `app/src/test/java/com/nexio/tv/core/metadata/MetadataApiKeyResolverTest.kt`

- [ ] **Step 1: Add BuildConfig fields**

In `app/build.gradle.kts`, add these fields inside `defaultConfig`, next to the other API URL/key fields:

```kotlin
        buildConfigField("String", "TMDB_API_KEY", "\"${resolveProperty(devProperties, localProperties, "TMDB_API_KEY")}\"")
        buildConfigField("String", "TMDB_API_URL", "\"${resolveProperty(devProperties, localProperties, "TMDB_API_URL", "https://api.themoviedb.org/3/")}\"")
        buildConfigField("String", "TVDB_API_KEY", "\"${resolveProperty(devProperties, localProperties, "TVDB_API_KEY")}\"")
        buildConfigField("String", "TVDB_API_URL", "\"${resolveProperty(devProperties, localProperties, "TVDB_API_URL", "https://api4.thetvdb.com/v4/")}\"")
```

Do not add real keys to git. The actual values live in `local.properties` and `local.dev.properties`.

- [ ] **Step 2: Create provider config**

Create `app/src/main/java/com/nexio/tv/core/metadata/MetadataProviderConfig.kt`:

```kotlin
package com.nexio.tv.core.metadata

import com.nexio.tv.BuildConfig

enum class MetadataCredentialSource {
    CUSTOM,
    BUILT_IN,
    MISSING
}

data class MetadataProviderCredential(
    val apiKey: String,
    val pin: String = "",
    val source: MetadataCredentialSource
) {
    val missing: Boolean get() = source == MetadataCredentialSource.MISSING || apiKey.isBlank()
    val cacheToken: String get() = "${source.name.lowercase()}:${apiKey.hashCode()}:${pin.hashCode()}"
}

object MetadataProviderConfig {
    const val DEFAULT_TMDB_API_URL = "https://api.themoviedb.org/3/"
    const val DEFAULT_TVDB_API_URL = "https://api4.thetvdb.com/v4/"

    fun tmdbBaseUrl(): String = normalizeBaseUrl(BuildConfig.TMDB_API_URL, DEFAULT_TMDB_API_URL)
    fun tvdbBaseUrl(): String = normalizeBaseUrl(BuildConfig.TVDB_API_URL, DEFAULT_TVDB_API_URL)
    fun builtInTmdbApiKey(): String = BuildConfig.TMDB_API_KEY.trim()
    fun builtInTvdbApiKey(): String = BuildConfig.TVDB_API_KEY.trim()

    fun normalizeBaseUrl(raw: String?, fallback: String): String {
        val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: fallback
        return if (value.endsWith("/")) value else "$value/"
    }

    fun resolveCredential(
        customApiKey: String,
        builtInApiKey: String,
        pin: String = ""
    ): MetadataProviderCredential {
        val custom = customApiKey.trim()
        if (custom.isNotBlank()) {
            return MetadataProviderCredential(
                apiKey = custom,
                pin = pin.trim(),
                source = MetadataCredentialSource.CUSTOM
            )
        }

        val builtIn = builtInApiKey.trim()
        if (builtIn.isNotBlank()) {
            return MetadataProviderCredential(
                apiKey = builtIn,
                pin = "",
                source = MetadataCredentialSource.BUILT_IN
            )
        }

        return MetadataProviderCredential(
            apiKey = "",
            pin = "",
            source = MetadataCredentialSource.MISSING
        )
    }
}
```

- [ ] **Step 3: Create resolver**

Create `app/src/main/java/com/nexio/tv/core/metadata/MetadataApiKeyResolver.kt`:

```kotlin
package com.nexio.tv.core.metadata

import com.nexio.tv.data.local.TmdbSettingsDataStore
import com.nexio.tv.data.local.TvdbSettingsDataStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

@Singleton
class MetadataApiKeyResolver @Inject constructor(
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    private val tvdbSettingsDataStore: TvdbSettingsDataStore
) {
    constructor(
        tmdbSettingsDataStore: TmdbSettingsDataStore,
        tvdbSettingsDataStore: TvdbSettingsDataStore,
        builtInTmdbKey: () -> String,
        builtInTvdbKey: () -> String
    ) : this(tmdbSettingsDataStore, tvdbSettingsDataStore) {
        this.builtInTmdbKey = builtInTmdbKey
        this.builtInTvdbKey = builtInTvdbKey
    }

    private var builtInTmdbKey: () -> String = { MetadataProviderConfig.builtInTmdbApiKey() }
    private var builtInTvdbKey: () -> String = { MetadataProviderConfig.builtInTvdbApiKey() }

    suspend fun tmdbCredential(): MetadataProviderCredential {
        val settings = tmdbSettingsDataStore.settings.first()
        return MetadataProviderConfig.resolveCredential(
            customApiKey = settings.apiKey,
            builtInApiKey = builtInTmdbKey()
        )
    }

    suspend fun tvdbCredential(): MetadataProviderCredential {
        val settings = tvdbSettingsDataStore.settings.first()
        return MetadataProviderConfig.resolveCredential(
            customApiKey = settings.apiKey,
            builtInApiKey = builtInTvdbKey(),
            pin = settings.subscriberPin
        )
    }
}
```

- [ ] **Step 4: Use configured base URLs in Retrofit**

In `app/src/main/java/com/nexio/tv/core/di/NetworkModule.kt`, import:

```kotlin
import com.nexio.tv.core.metadata.MetadataProviderConfig
```

Replace TMDB and TVDB base URLs:

```kotlin
    @Provides
    @Singleton
    @Named("tmdb")
    fun provideTmdbRetrofit(okHttpClient: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder()
            .baseUrl(MetadataProviderConfig.tmdbBaseUrl())
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    @Named("tvdb")
    fun provideTvdbRetrofit(okHttpClient: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder()
            .baseUrl(MetadataProviderConfig.tvdbBaseUrl())
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
```

- [ ] **Step 5: Run resolver tests**

Run:

```bash
./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.core.metadata.MetadataApiKeyResolverTest"
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/com/nexio/tv/core/metadata/MetadataProviderConfig.kt app/src/main/java/com/nexio/tv/core/metadata/MetadataApiKeyResolver.kt app/src/main/java/com/nexio/tv/core/di/NetworkModule.kt app/src/test/java/com/nexio/tv/core/metadata/MetadataApiKeyResolverTest.kt
git commit -m "feat: add builtin metadata provider credentials"
```

### Task 3: Make Android TMDB and TVDB Core Enabled

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/domain/model/TmdbSettings.kt`
- Modify: `app/src/main/java/com/nexio/tv/domain/model/TvdbSettings.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/local/TmdbSettingsDataStore.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/local/TvdbSettingsDataStore.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/remote/supabase/AccountSyncModels.kt`
- Test: `app/src/test/java/com/nexio/tv/core/tvdb/TvMetadataRouterTest.kt`
- Test: `app/src/test/java/com/nexio/tv/core/sync/AccountConfigSyncContractTest.kt`

- [ ] **Step 1: Update domain defaults**

In `TmdbSettings.kt`, change to:

```kotlin
data class TmdbSettings(
    val enabled: Boolean = true,
    val apiKey: String = "",
    val useArtwork: Boolean = true,
    val useBasicInfo: Boolean = true,
    val useDetails: Boolean = true,
    val useCredits: Boolean = true,
    val useProductions: Boolean = true,
    val useNetworks: Boolean = true,
    val useEpisodes: Boolean = true,
    val useMoreLikeThis: Boolean = true,
    val useReviews: Boolean = true,
    val useCollections: Boolean = true
) {
    val isActive: Boolean
        get() = enabled
}
```

In `TvdbSettings.kt`, change activation to no longer require a custom key:

```kotlin
data class TvdbSettings(
    val enabled: Boolean = true,
    val apiKey: String = "",
    val subscriberPin: String = "",
    val validationStatus: TvdbValidationStatus = TvdbValidationStatus.VALID,
    val lastFailure: String = "",
    val lastValidatedAtEpochMs: Long? = null
) {
    val configured: Boolean get() = true
    val hasCustomCredentials: Boolean get() = apiKey.isNotBlank()
    val isActive: Boolean get() = enabled && validationStatus != TvdbValidationStatus.INVALID
}
```

- [ ] **Step 2: Force DataStore defaults to enabled**

In `TmdbSettingsDataStore.settings`, use:

```kotlin
            enabled = prefs[enabledKey] ?: true,
```

Then replace `setEnabled`:

```kotlin
    suspend fun setEnabled(enabled: Boolean) {
        store().edit { it[enabledKey] = true }
    }
```

In `TvdbSettingsDataStore.settings`, use:

```kotlin
            enabled = prefs[enabledKey] ?: true,
            apiKey = prefs[apiKeyKey] ?: "",
            subscriberPin = prefs[subscriberPinKey] ?: "",
            validationStatus = parseValidationStatus(statusName),
```

Then update `parseValidationStatus` so missing/legacy not-configured becomes valid for built-in fallback:

```kotlin
    private fun parseValidationStatus(statusName: String?): TvdbValidationStatus {
        return statusName
            ?.let { runCatching { TvdbValidationStatus.valueOf(it) }.getOrNull() }
            ?.takeUnless { it == TvdbValidationStatus.NOT_CONFIGURED }
            ?: TvdbValidationStatus.VALID
    }
```

Replace `setEnabled`:

```kotlin
    suspend fun setEnabled(enabled: Boolean) {
        store().edit { prefs -> prefs[enabledKey] = true }
    }
```

Replace the blank-key branch in `setCredentials`:

```kotlin
        val nextStatus = if (trimmedApiKey.isBlank()) {
            TvdbValidationStatus.VALID
        } else {
            current.validationStatus
        }
```

Replace `clearCredentials`:

```kotlin
    suspend fun clearCredentials() {
        store().edit { prefs ->
            prefs.remove(apiKeyKey)
            prefs.remove(subscriberPinKey)
            prefs[validationStatusKey] = TvdbValidationStatus.VALID.name
            prefs.remove(lastFailureKey)
            prefs.remove(lastValidatedAtEpochMsKey)
            prefs[enabledKey] = true
        }
        tokenStore.clear()
    }
```

- [ ] **Step 3: Force remote sync apply to core-enabled**

In `AccountSettingsSyncService.applyRemoteSettings`, replace:

```kotlin
        tmdbSettingsDataStore.setEnabled(settings.integrations.tmdb.enabled)
```

with:

```kotlin
        tmdbSettingsDataStore.setEnabled(true)
```

Add TVDB forced enable near the TMDB section if the method already applies TVDB elsewhere:

```kotlin
        tvdbSettingsDataStore.setEnabled(true)
```

If TVDB is only secret-applied in `applyRemoteSecrets`, add the forced enable before `resolveTvdbCredentialSecretOrNull()`:

```kotlin
        tvdbSettingsDataStore.setEnabled(true)
        resolveTvdbCredentialSecretOrNull()?.let { tvdb ->
            tvdbSettingsDataStore.setCredentials(tvdb.apiKey, tvdb.pin.orEmpty())
        }
```

- [ ] **Step 4: Update sync model defaults**

In `AccountSyncModels.kt`, change:

```kotlin
data class TmdbSyncSettings(
    val enabled: Boolean = true,
```

and:

```kotlin
data class TvdbSyncSettings(
    val enabled: Boolean = true,
    val configured: Boolean = true,
    val validationStatus: String = "VALID",
    val lastFailure: String = ""
)
```

- [ ] **Step 5: Run Android default behavior tests**

Run:

```bash
./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.core.tvdb.TvMetadataRouterTest" --tests "com.nexio.tv.core.sync.AccountConfigSyncContractTest"
```

Expected: PASS for the new default/core tests and existing tests adjusted to explicitly pass disabled settings when they need disabled behavior.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/domain/model/TmdbSettings.kt app/src/main/java/com/nexio/tv/domain/model/TvdbSettings.kt app/src/main/java/com/nexio/tv/data/local/TmdbSettingsDataStore.kt app/src/main/java/com/nexio/tv/data/local/TvdbSettingsDataStore.kt app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt app/src/main/java/com/nexio/tv/data/remote/supabase/AccountSyncModels.kt app/src/test/java/com/nexio/tv/core/tvdb/TvMetadataRouterTest.kt app/src/test/java/com/nexio/tv/core/sync/AccountConfigSyncContractTest.kt
git commit -m "feat: make metadata providers core enabled on Android"
```

### Task 4: Use Effective Credentials in Android API Callers

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/tmdb/TmdbService.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/tmdb/TmdbMetadataService.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/tmdb/TmdbOrganizationService.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/trailer/TrailerService.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/tvdb/TvdbAuthService.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/tvdb/TvdbCredentialHealth.kt`
- Test: existing TMDB/TVDB service tests

- [ ] **Step 1: Inject resolver into `TmdbService`**

Replace the constructor dependency:

```kotlin
class TmdbService @Inject constructor(
    private val tmdbApi: TmdbApi,
    private val metadataApiKeyResolver: MetadataApiKeyResolver
)
```

Replace `requireApiKey`:

```kotlin
    private suspend fun requireApiKey(): String? {
        val credential = metadataApiKeyResolver.tmdbCredential()
        if (credential.missing) {
            Log.w(TAG, "TMDB API key is missing; lookup skipped")
            return null
        }
        return credential.apiKey
    }
```

Update tests that construct `TmdbService` directly to pass a mocked `MetadataApiKeyResolver`:

```kotlin
val resolver = mockk<MetadataApiKeyResolver>()
coEvery { resolver.tmdbCredential() } returns MetadataProviderCredential(
    apiKey = "tmdb-key",
    source = MetadataCredentialSource.CUSTOM
)
val service = TmdbService(tmdbApi, resolver)
```

- [ ] **Step 2: Inject resolver into `TmdbMetadataService`**

Replace the constructor dependency:

```kotlin
    private val metadataApiKeyResolver: MetadataApiKeyResolver,
```

Replace `requireApiKey`:

```kotlin
    private suspend fun requireApiKey(): String? {
        val credential = metadataApiKeyResolver.tmdbCredential()
        if (credential.missing) {
            Log.w(TAG, "TMDB API key is missing; metadata request skipped")
            return null
        }
        return credential.apiKey
    }
```

Update test helper `buildMetadataService` to provide the resolver:

```kotlin
val resolver = mockk<MetadataApiKeyResolver>()
coEvery { resolver.tmdbCredential() } returns MetadataProviderCredential(
    apiKey = "tmdb-key",
    source = MetadataCredentialSource.CUSTOM
)
return TmdbMetadataService(
    appContext = context,
    tmdbApi = tmdbApi,
    posterRatingsUrlResolver = posterRatingsUrlResolver,
    metadataApiKeyResolver = resolver,
    metadataDiskCacheStore = metadataDiskCacheStore
)
```

- [ ] **Step 3: Update `TmdbOrganizationService`**

Replace any `tmdbSettingsDataStore.settings.map { it.apiKey.trim() }` flow with resolver calls at request time:

```kotlin
private suspend fun requireApiKey(): String? {
    val credential = metadataApiKeyResolver.tmdbCredential()
    return credential.apiKey.takeUnless { credential.missing }
}
```

Use that helper before TMDB company/network discover/detail calls.

- [ ] **Step 4: Update `TrailerService` TMDB key lookup**

Replace `requireTmdbApiKey()` with:

```kotlin
    private suspend fun requireTmdbApiKey(): String? {
        val credential = metadataApiKeyResolver.tmdbCredential()
        if (credential.missing) {
            trailerDebugLog("TMDB trailer lookup skipped: api_key_missing")
            return null
        }
        return credential.apiKey
    }
```

Inject `MetadataApiKeyResolver` in the constructor and update tests.

- [ ] **Step 5: Update `TvdbAuthService`**

Inject resolver:

```kotlin
    private val metadataApiKeyResolver: MetadataApiKeyResolver,
```

Replace the top of `bearerToken()`:

```kotlin
        val settingsStore = settingsDataStore ?: return@withContext null
        val credential = metadataApiKeyResolver.tvdbCredential()
        val apiKey = credential.apiKey
        val pin = credential.pin
        if (credential.missing) {
            return@withContext null
        }
```

When login succeeds, only persist custom credentials:

```kotlin
                    if (credential.source == MetadataCredentialSource.CUSTOM) {
                        settingsStore.saveCredentials(
                            apiKey = apiKey,
                            pin = pin,
                            validationStatus = TvdbValidationStatus.VALID
                        )
                    } else {
                        settingsStore.saveValidationFailure(
                            status = TvdbValidationStatus.VALID,
                            lastFailure = ""
                        )
                    }
```

Keep `credentialFingerprint(apiKey, pin)` based on the effective key so built-in and custom tokens do not collide.

- [ ] **Step 6: Update `TvdbCredentialHealth`**

Inject resolver and replace the key check in `canCallTvdb()`:

```kotlin
        val settings = settingsDataStore.settings.first()
        if (!settings.enabled) {
            return false
        }

        if (metadataApiKeyResolver.tvdbCredential().missing) {
            return false
        }
```

Keep the existing `validationStatus == INVALID` block, because an invalid custom key should stop retries until the user removes/replaces it. In Task 6, the portal will remove custom invalid state when the custom key is removed.

- [ ] **Step 7: Run affected Android tests**

Run:

```bash
./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.core.tmdb.TmdbMetadataPerformanceTest" --tests "com.nexio.tv.core.tvdb.TvdbAuthServiceTest" --tests "com.nexio.tv.core.tvdb.TvdbCredentialHealthTest" --tests "com.nexio.tv.data.trailer.TrailerServiceTvdbTest" --tests "com.nexio.tv.data.trailer.TrailerServiceLatestSeasonTest"
```

Expected: PASS after constructor/test helper updates.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/tmdb/TmdbService.kt app/src/main/java/com/nexio/tv/core/tmdb/TmdbMetadataService.kt app/src/main/java/com/nexio/tv/core/tmdb/TmdbOrganizationService.kt app/src/main/java/com/nexio/tv/data/trailer/TrailerService.kt app/src/main/java/com/nexio/tv/core/tvdb/TvdbAuthService.kt app/src/main/java/com/nexio/tv/core/tvdb/TvdbCredentialHealth.kt app/src/test/java/com/nexio/tv
git commit -m "feat: resolve metadata API keys with builtin fallback"
```

### Task 5: Add Metadata TTLs and Remove Unnecessary TVDB Trailer Calls

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/local/MetadataDiskCacheStore.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/tvdb/TvdbTrailerResolver.kt`
- Test: `app/src/test/java/com/nexio/tv/data/local/MetadataDiskCacheStoreTest.kt`
- Test: `app/src/test/java/com/nexio/tv/core/tvdb/TvdbTrailerResolverTest.kt` if present; otherwise create it.

- [ ] **Step 1: Add TTL constants**

In `MetadataDiskCacheStore.Companion`, add:

```kotlin
        private val TMDB_ENRICHMENT_CACHE_TTL: Duration = Duration.ofDays(7)
        private val TVDB_ENRICHMENT_CACHE_TTL: Duration = Duration.ofDays(7)
        private val TVDB_EPISODE_CACHE_TTL: Duration = Duration.ofHours(24)
        private val TVDB_REFERENCE_CACHE_TTL: Duration = Duration.ofDays(30)
```

- [ ] **Step 2: Add expiration helper**

Add:

```kotlin
    private fun isCacheEntryExpired(updatedAtMs: Long, ttl: Duration): Boolean {
        val updatedAt = Instant.ofEpochMilli(updatedAtMs)
        return Duration.between(updatedAt, Instant.now()) > ttl
    }
```

Replace `isTmdbVideoCacheEntryExpired` with:

```kotlin
    private fun isTmdbVideoCacheEntryExpired(updatedAtMs: Long): Boolean {
        return isCacheEntryExpired(updatedAtMs, TMDB_VIDEO_CACHE_TTL)
    }
```

- [ ] **Step 3: Enforce TMDB enrichment TTL**

In `readTmdbEnrichment`, after schema check:

```kotlin
            val updatedAtMs = root.get("updatedAtMs")?.asLong ?: return null
            if (isCacheEntryExpired(updatedAtMs, TMDB_ENRICHMENT_CACHE_TTL)) return null
```

- [ ] **Step 4: Enforce TVDB enrichment TTL**

In `readTvdbEnrichment`, after schema check:

```kotlin
            val updatedAtMs = root.get("updatedAtMs")?.asLong ?: return null
            if (isCacheEntryExpired(updatedAtMs, TVDB_ENRICHMENT_CACHE_TTL)) return null
```

- [ ] **Step 5: Enforce TVDB episode TTL**

In `readTvdbSeasonEpisodes`, after schema check:

```kotlin
            val updatedAtMs = root.get("updatedAtMs")?.asLong ?: return null
            if (isCacheEntryExpired(updatedAtMs, TVDB_EPISODE_CACHE_TTL)) return null
```

- [ ] **Step 6: Enforce TVDB reference TTL**

In `readTvdbReference`, after schema check:

```kotlin
            val updatedAtMs = root.get("updatedAtMs")?.asLong ?: return null
            if (isCacheEntryExpired(updatedAtMs, TVDB_REFERENCE_CACHE_TTL)) return null
```

- [ ] **Step 7: Reuse TVDB metadata cache for trailers**

In `TvdbTrailerResolver`, inject `TvdbMetadataService` and change `fetchSeriesRecord` to avoid a second network call when the series is already cached by the metadata service.

Add this helper to `TvdbMetadataService`:

```kotlin
    suspend fun fetchTrailerSourceRecord(
        identity: TvdbSeriesIdentity,
        language: String? = null
    ): TvdbSeriesExtendedRecord? = withContext(Dispatchers.IO) {
        val authorization = authService.bearerToken() ?: return@withContext null
        runCatching {
            tvdbApi.getSeriesExtended(
                authorization = authorization,
                id = resolveSeriesAlias(identity.tvdbId),
                meta = null,
                short = false
            )
        }.getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.body()
            ?.data
    }
```

Then in `TvdbTrailerResolver.fetchSeriesRecord`:

```kotlin
    private suspend fun fetchSeriesRecord(identity: TvdbSeriesIdentity): TvdbSeriesExtendedRecord? {
        return tvdbMetadataService.fetchTrailerSourceRecord(identity)
    }
```

This is still a network fetch if no cache exists. If the worker finds `TvdbSeriesExtendedRecord` too large to persist separately, keep the existing call but add an in-flight map by TVDB id:

```kotlin
private val seriesRecordInFlight = ConcurrentHashMap<Int, CompletableDeferred<TvdbSeriesExtendedRecord?>>()
```

and coalesce concurrent title/season/recap trailer calls. Do not add both a new persisted record cache and in-flight coalescing unless tests prove both are needed.

- [ ] **Step 8: Add cache TTL tests**

In `MetadataDiskCacheStoreTest`, add tests using the existing test context/helper pattern:

```kotlin
@Test
fun `expired tmdb enrichment cache entry is ignored`() {
    val store = MetadataDiskCacheStore(context, ioScope = testScope, debounceMs = 0L)
    store.writeTmdbEnrichment(
        tmdbKey = "550:MOVIE",
        languageTag = "en-US",
        providerToken = "native",
        enrichment = TmdbEnrichment(localizedTitle = "Fight Club")
    )
    store.flushPendingWritesForTest()
    rewriteUpdatedAtForTest(prefix = "tmdb::550:MOVIE::en-US::native", updatedAtMs = 0L)

    assertNull(store.readTmdbEnrichment("550:MOVIE", "en-US", "native"))
}

@Test
fun `fresh tvdb season episode cache entry is reused`() {
    val store = MetadataDiskCacheStore(context, ioScope = testScope, debounceMs = 0L)
    store.writeTvdbSeasonEpisodes(
        seriesId = 121361,
        seasonType = "default",
        seasonNumber = 1,
        languageTag = "en-US",
        episodes = listOf(TvEpisodeMetadata(seasonNumber = 1, episodeNumber = 1, title = "Winter Is Coming"))
    )
    store.flushPendingWritesForTest()

    val cached = store.readTvdbSeasonEpisodes(121361, "default", 1, "en-US")

    assertEquals("Winter Is Coming", cached?.single()?.title)
}
```

Add this package-visible test helper to `MetadataDiskCacheStore` when the test file does not already have a way to rewrite cache timestamps:

```kotlin
internal fun rewriteUpdatedAtForTest(key: String, updatedAtMs: Long) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val raw = prefs.getString(key, null) ?: return
    val root = gson.fromJson(raw, JsonObject::class.java)
    root.addProperty("updatedAtMs", updatedAtMs)
    prefs.edit().putString(key, gson.toJson(root)).commit()
}
```

- [ ] **Step 9: Run TTL and trailer tests**

Run:

```bash
./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.local.MetadataDiskCacheStoreTest" --tests "com.nexio.tv.core.tvdb.TvdbTrailerResolverTest" --tests "com.nexio.tv.core.tmdb.TmdbMetadataPerformanceTest"
```

Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/local/MetadataDiskCacheStore.kt app/src/main/java/com/nexio/tv/core/tvdb/TvdbTrailerResolver.kt app/src/main/java/com/nexio/tv/core/tvdb/TvdbMetadataService.kt app/src/test/java/com/nexio/tv/data/local/MetadataDiskCacheStoreTest.kt app/src/test/java/com/nexio/tv/core/tvdb/TvdbTrailerResolverTest.kt
git commit -m "perf: add metadata cache TTLs"
```

### Task 6: Add Web Runtime Built-In Config and Validation Fallback

**Files:**
- Modify: `nexio-web/nuxt.config.ts`
- Modify: `nexio-web/compose.yml`
- Create: `nexio-web/server/utils/metadata-provider-config.ts`
- Modify: `nexio-web/server/api/integrations/tvdb/validate.post.ts`
- Test: `nexio-web/tests/metadata-provider-config.test.ts`

- [ ] **Step 1: Add Nuxt runtime config**

In `nexio-web/nuxt.config.ts`, add:

```ts
    tvdbApiKey: '',
    tvdbApiUrl: '',
    tmdbApiKey: '',
    tmdbApiUrl: '',
```

inside `runtimeConfig`, not `public`.

- [ ] **Step 2: Add Docker environment variables**

In `nexio-web/compose.yml`, add:

```yaml
      NUXT_TVDB_API_KEY: ${NUXT_TVDB_API_KEY:-}
      NUXT_TVDB_API_URL: ${NUXT_TVDB_API_URL:-}
      NUXT_TMDB_API_KEY: ${NUXT_TMDB_API_KEY:-}
      NUXT_TMDB_API_URL: ${NUXT_TMDB_API_URL:-}
```

- [ ] **Step 3: Create server provider config**

Create `nexio-web/server/utils/metadata-provider-config.ts`:

```ts
const DEFAULT_TVDB_API_URL = 'https://api4.thetvdb.com/v4/'
const DEFAULT_TMDB_API_URL = 'https://api.themoviedb.org/3/'

function normalizeBaseUrl(value: unknown, fallback: string): string {
  const raw = typeof value === 'string' ? value.trim() : ''
  const selected = raw || fallback
  return selected.endsWith('/') ? selected : `${selected}/`
}

export function metadataProviderConfig() {
  const config = useRuntimeConfig()
  return {
    tvdbApiKey: String(config.tvdbApiKey || '').trim(),
    tvdbApiUrl: normalizeBaseUrl(config.tvdbApiUrl, DEFAULT_TVDB_API_URL),
    tmdbApiKey: String(config.tmdbApiKey || '').trim(),
    tmdbApiUrl: normalizeBaseUrl(config.tmdbApiUrl, DEFAULT_TMDB_API_URL)
  }
}

export function resolveMetadataApiKey(customApiKey: unknown, builtInApiKey: string): string {
  const custom = typeof customApiKey === 'string' ? customApiKey.trim() : ''
  return custom || builtInApiKey.trim()
}
```

- [ ] **Step 4: Use built-in fallback in TVDB validation**

In `nexio-web/server/api/integrations/tvdb/validate.post.ts`, import:

```ts
import { metadataProviderConfig, resolveMetadataApiKey } from '~/server/utils/metadata-provider-config'
```

Replace the key resolution after the secret lookup with:

```ts
  const providerConfig = metadataProviderConfig()
  apiKey = resolveMetadataApiKey(apiKey, providerConfig.tvdbApiKey)

  if (!apiKey) {
    throw createError({ statusCode: 500, statusMessage: 'TVDB built-in API key is not configured.' })
  }
```

Replace the fetch URL:

```ts
    response = await fetch(new URL('login', providerConfig.tvdbApiUrl), {
```

- [ ] **Step 5: Add web config tests**

Create `nexio-web/tests/metadata-provider-config.test.ts`:

```ts
import test from 'node:test'
import assert from 'node:assert/strict'
import { resolveMetadataApiKey } from '../server/utils/metadata-provider-config.ts'

test('resolveMetadataApiKey prefers custom key over builtin key', () => {
  assert.equal(resolveMetadataApiKey(' custom ', 'builtin'), 'custom')
})

test('resolveMetadataApiKey falls back to builtin key', () => {
  assert.equal(resolveMetadataApiKey('', ' builtin '), 'builtin')
})

test('resolveMetadataApiKey returns blank only when both keys are blank', () => {
  assert.equal(resolveMetadataApiKey(' ', ' '), '')
})
```

- [ ] **Step 6: Run web config tests**

Run:

```bash
cd nexio-web
npm test -- tests/metadata-provider-config.test.ts
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add nexio-web/nuxt.config.ts nexio-web/compose.yml nexio-web/server/utils/metadata-provider-config.ts nexio-web/server/api/integrations/tvdb/validate.post.ts nexio-web/tests/metadata-provider-config.test.ts
git commit -m "feat: add builtin metadata provider config to web"
```

### Task 7: Make Portal TMDB and TVDB Non-Deletable Core Integrations

**Files:**
- Modify: `nexio-web/utils/portal-defaults.ts`
- Modify: `nexio-web/utils/portal-settings.ts`
- Modify: `nexio-web/utils/integration-delete.ts`
- Modify: `nexio-web/utils/integration-secret-bindings.ts`
- Modify: `nexio-web/composables/usePortalStore.ts`
- Modify: `nexio-web/components/portal/SettingsWorkspace.vue`
- Modify: `nexio-web/types/portal.ts`
- Test: `nexio-web/tests/portal-contract-v4.test.ts`
- Test: `nexio-web/tests/integration-delete.test.ts`

- [ ] **Step 1: Update portal defaults**

In `portal-defaults.ts`, set:

```ts
    tmdb: {
      enabled: true,
      useArtwork: true,
      useBasicInfo: true,
      useDetails: true,
      useCredits: true,
      useProductions: true,
      useNetworks: true,
      useEpisodes: true,
      useMoreLikeThis: true,
      useCollections: true
    },
    tvdb: {
      enabled: true,
      configured: true,
      validationStatus: 'VALID',
      lastFailure: ''
    },
```

- [ ] **Step 2: Sanitize stale false values to enabled**

In `portal-settings.ts`, after merging TMDB and TVDB, force core values:

```ts
      tmdb: {
        ...defaults.integrations.tmdb,
        ...(input?.integrations?.tmdb ?? {}),
        enabled: true
      },
      tvdb: {
        ...defaults.integrations.tvdb,
        ...(input?.integrations?.tvdb ?? {}),
        enabled: true,
        configured: true,
        validationStatus:
          input?.integrations?.tvdb?.validationStatus === 'INVALID'
            ? 'FALLBACK_ACTIVE'
            : 'VALID',
        lastFailure:
          input?.integrations?.tvdb?.validationStatus === 'INVALID'
            ? 'Custom TVDB key failed; built-in TVDB access remains active.'
            : ''
      },
```

- [ ] **Step 3: Remove TMDB/TVDB from deletable integration type**

In `integration-delete.ts`, remove `'tmdb'` and `'tvdb'` from `DeletableIntegrationId`.

Remove these switch cases:

```ts
    case 'tmdb':
      return { secretType: 'tmdb_api_key', secretRef: secretRefs.tmdb }
    case 'tvdb':
      return { secretType: 'tvdb_api_key', secretRef: secretRefs.tvdb }
```

Remove reset cases that assign TMDB/TVDB defaults. If TypeScript needs defensive runtime handling, use:

```ts
    default:
      return next
```

- [ ] **Step 4: Keep custom secret binding**

Do not remove TMDB/TVDB from `integration-secret-bindings.ts`; those bindings are now "optional custom key" bindings, not integration lifecycle bindings.

- [ ] **Step 5: Block deleteIntegration defensively**

In `usePortalStore.ts`, add at the start of `deleteIntegration`:

```ts
    if (id === 'tmdb' || id === 'tvdb') {
      return
    }
```

If TypeScript complains because the type no longer includes those values, use a string guard before the typed function is called in the component and remove this defensive block.

- [ ] **Step 6: Update portal configuration state**

In `SettingsWorkspace.vue`, change:

```ts
    tmdb: props.settings.integrations.tmdb.enabled || !!props.secretStatuses['integration:tmdb'],
    tvdb: !!props.settings.integrations.tvdb?.enabled || !!props.secretStatuses['integration:tvdb'],
```

to:

```ts
    tmdb: true,
    tvdb: true,
```

Change `hasEnableToggle`:

```ts
const hasEnableToggle = (id: string) => ['theintrodb', 'omdb', 'imdb', 'mdblist', 'animeskip', 'subtitle-translation'].includes(id)
```

Change `integrationEnabled`:

```ts
  if (id === 'tmdb') return true
  if (id === 'tvdb') return true
```

Remove TMDB/TVDB branches from `toggleGenericIntegration`.

- [ ] **Step 7: Remove delete buttons from TMDB/TVDB cards**

In the TMDB card, delete this button:

```vue
<button @click.stop="emit('delete-integration', 'tmdb')" class="px-3 bg-surface-container-highest/60 hover:bg-error/15 text-xs font-semibold py-2.5 rounded-lg transition-colors border border-error/20 text-red-400 hover:text-red-300" aria-label="Delete TMDB integration"><span class="material-symbols-outlined text-[18px]">delete</span></button>
```

In the TVDB card, delete this button:

```vue
<button @click.stop="emit('delete-integration', 'tvdb')" class="px-3 bg-surface-container-highest/60 hover:bg-error/15 text-xs font-semibold py-2.5 rounded-lg transition-colors border border-error/20 text-red-400 hover:text-red-300" aria-label="Delete TVDB integration"><span class="material-symbols-outlined text-[18px]">delete</span></button>
```

Keep "Remove stored key" buttons inside modals; those now remove only the optional custom key.

- [ ] **Step 8: Update modal copy**

Change TMDB/TVDB copy so it does not say "required". Use:

```vue
<p class="text-sm text-on-surface-variant">
  Nexio includes metadata access by default. Add a custom API key only if you want this profile to use your own provider quota.
</p>
```

Use the same sentence for TMDB and TVDB modal bodies, adjusted only for provider name if the component has provider-specific copy.

- [ ] **Step 9: Run portal tests**

Run:

```bash
cd nexio-web
npm test -- tests/portal-contract-v4.test.ts tests/integration-delete.test.ts tests/integration-secret-bindings.test.ts
```

Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add nexio-web/utils/portal-defaults.ts nexio-web/utils/portal-settings.ts nexio-web/utils/integration-delete.ts nexio-web/utils/integration-secret-bindings.ts nexio-web/composables/usePortalStore.ts nexio-web/components/portal/SettingsWorkspace.vue nexio-web/types/portal.ts nexio-web/tests/portal-contract-v4.test.ts nexio-web/tests/integration-delete.test.ts
git commit -m "feat: make portal metadata providers core"
```

### Task 8: Update Android Settings UI Copy and Remove Disable Controls

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/TmdbSettingsScreen.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/TmdbSettingsViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/TvdbSettingsScreen.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/TvdbSettingsViewModel.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-de/strings.xml`
- Modify: `app/src/main/res/values-es/strings.xml`
- Modify: `app/src/main/res/values-nl/strings.xml`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/settings/TvdbSettingsViewModelTest.kt`

- [ ] **Step 1: Remove TMDB enable event handling**

In `TmdbSettingsViewModel.kt`, remove `ToggleEnabled` from the event handling. If tests or sync still call it, keep the event but force true:

```kotlin
            is TmdbSettingsEvent.ToggleEnabled -> update { dataStore.setEnabled(true) }
```

The preferred UI path must no longer dispatch this event.

- [ ] **Step 2: Remove TMDB enable row from screen**

In `TmdbSettingsScreen.kt`, remove the `SettingsToggleRow` or equivalent row with `R.string.tmdb_enable_title`.

Keep the API key row, but change its subtitle from required to optional.

- [ ] **Step 3: Remove TVDB enable event handling**

In `TvdbSettingsViewModel.kt`, replace toggle handling with forced true:

```kotlin
            is TvdbSettingsEvent.ToggleEnabled -> update { dataStore.setEnabled(true) }
```

Do not expose a UI control that calls it.

- [ ] **Step 4: Remove TVDB enable row from screen**

In `TvdbSettingsScreen.kt`, remove the enable toggle row. Keep the credential dialog and validation status block for custom credentials.

- [ ] **Step 5: Update English strings**

In `app/src/main/res/values/strings.xml`, change or add:

```xml
<string name="tmdb_api_key_subtitle">Optional custom TMDB key. Nexio works with built-in metadata access when this is empty.</string>
<string name="tmdb_dialog_subtitle">Add a personal TMDB API key only if you want to use your own provider quota.</string>
<string name="tmdb_missing_api_key">TMDB uses built-in access when no custom key is saved.</string>
<string name="tvdb_api_key_subtitle">Optional custom TheTVDB key. Nexio works with built-in metadata access when this is empty.</string>
<string name="tvdb_dialog_subtitle">Add a personal TheTVDB API key only if you want to use your own provider quota.</string>
<string name="tvdb_missing_api_key">TheTVDB uses built-in access when no custom key is saved.</string>
```

For localized files, use a direct English fallback if no translator is available. Do not leave the old "required" meaning in any locale.

- [ ] **Step 6: Update TVDB ViewModel tests**

Change tests that expect blank TVDB key to disable or error. Add:

```kotlin
@Test
fun `clearing custom tvdb credentials keeps core tvdb enabled`() = runTest {
    val settingsFlow = MutableStateFlow(
        TvdbSettings(enabled = true, apiKey = "custom-key", validationStatus = TvdbValidationStatus.VALID)
    )
    val dataStore = mockk<TvdbSettingsDataStore>(relaxed = true)
    every { dataStore.settings } returns settingsFlow
    val viewModel = TvdbSettingsViewModel(
        dataStore = dataStore,
        authService = mockk(relaxed = true),
        diagnosticsDataStore = emptyDiagnosticsDataStore
    )

    viewModel.onEvent(TvdbSettingsEvent.ClearCredentials)

    coVerify { dataStore.clearCredentials() }
    coVerify(exactly = 0) { dataStore.setEnabled(false) }
}
```

- [ ] **Step 7: Run settings tests**

Run:

```bash
./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.settings.TvdbSettingsViewModelTest"
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/settings/TmdbSettingsScreen.kt app/src/main/java/com/nexio/tv/ui/screens/settings/TmdbSettingsViewModel.kt app/src/main/java/com/nexio/tv/ui/screens/settings/TvdbSettingsScreen.kt app/src/main/java/com/nexio/tv/ui/screens/settings/TvdbSettingsViewModel.kt app/src/main/res/values/strings.xml app/src/main/res/values-de/strings.xml app/src/main/res/values-es/strings.xml app/src/main/res/values-nl/strings.xml app/src/test/java/com/nexio/tv/ui/screens/settings/TvdbSettingsViewModelTest.kt
git commit -m "feat: remove metadata provider disable controls"
```

### Task 9: Documentation, Env Samples, and Final Verification

**Files:**
- Modify: `README.md`
- Modify: `docs-site/integrations/ratings-and-metadata.md`
- Modify: `docs-site/android/screens/settings.md`
- Modify: `nexio-web/.env` only if it is a committed non-secret example; otherwise do not edit.
- Do not commit real `local.properties` or `local.dev.properties` secrets.

- [ ] **Step 1: Update root README metadata copy**

Replace any text implying TMDB requires user setup with:

```markdown
- **TMDB and TheTVDB** are core metadata providers. Nexio includes built-in access for non-commercial app usage, and users can optionally save their own API keys to use their own provider quota.
```

- [ ] **Step 2: Update docs-site integration docs**

In `docs-site/integrations/ratings-and-metadata.md`, add:

```markdown
## TMDB and TheTVDB

TMDB and TheTVDB enrichment are enabled by default. Nexio uses built-in metadata access so artwork, descriptions, cast, release details, episode data, trailers, and TV season ordering work without setup.

Optional custom API keys remain supported. When a custom key is saved, Nexio uses it before the built-in key. Removing the custom key returns the app to built-in access.
```

- [ ] **Step 3: Document developer env variables**

In developer docs, add:

````markdown
### Metadata Provider Credentials

Android local builds read these from `local.dev.properties` first and `local.properties` second:

```properties
TMDB_API_KEY=
TMDB_API_URL=https://api.themoviedb.org/3/
TVDB_API_KEY=
TVDB_API_URL=https://api4.thetvdb.com/v4/
```

The web server reads:

```env
NUXT_TMDB_API_KEY=
NUXT_TMDB_API_URL=https://api.themoviedb.org/3/
NUXT_TVDB_API_KEY=
NUXT_TVDB_API_URL=https://api4.thetvdb.com/v4/
```
````

- [ ] **Step 4: Run Android focused tests**

Run:

```bash
./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.core.metadata.MetadataApiKeyResolverTest" --tests "com.nexio.tv.core.tmdb.TmdbMetadataPerformanceTest" --tests "com.nexio.tv.core.tvdb.TvMetadataRouterTest" --tests "com.nexio.tv.core.tvdb.TvdbAuthServiceTest" --tests "com.nexio.tv.core.tvdb.TvdbCredentialHealthTest" --tests "com.nexio.tv.data.local.MetadataDiskCacheStoreTest" --tests "com.nexio.tv.core.sync.AccountConfigSyncContractTest"
```

Expected: PASS.

- [ ] **Step 5: Run web focused tests**

Run:

```bash
cd nexio-web
npm test -- tests/metadata-provider-config.test.ts tests/portal-contract-v4.test.ts tests/integration-delete.test.ts tests/integration-secret-bindings.test.ts
```

Expected: PASS.

- [ ] **Step 6: Run broader smoke checks**

Run:

```bash
./gradlew :app:testUniversalDebugUnitTest
```

Expected: PASS.

Run:

```bash
cd nexio-web
npm test
```

Expected: PASS.

- [ ] **Step 7: Manual API-call audit**

Check code paths and confirm:
- TMDB custom key overrides `BuildConfig.TMDB_API_KEY`.
- TVDB custom key overrides `BuildConfig.TVDB_API_KEY`.
- Blank custom keys fall back to built-in keys.
- TMDB/TVDB Retrofit base URLs come from configured URLs with defaults.
- TMDB/TVDB cannot be disabled from Android settings.
- TMDB/TVDB cannot be deleted from the web portal.
- Removing a stored TMDB/TVDB secret removes only the custom key and leaves built-in access active.
- TVDB successful routing still records `TMDB_TV_FETCH_SKIPPED`.
- TMDB title details still use appended subresources.
- TMDB trailer videos still use 12-hour TTL.
- TMDB title enrichment uses 7-day TTL.
- TVDB series enrichment uses 7-day TTL.
- TVDB season episodes use 24-hour TTL.
- TVDB reference data uses 30-day TTL.
- TVDB `/updates` remains 12 hours.
- TVDB bearer tokens remain 30 days with 24-hour refresh skew.

- [ ] **Step 8: Commit docs and final fixes**

```bash
git add README.md docs-site/integrations/ratings-and-metadata.md docs-site/android/screens/settings.md
git commit -m "docs: document core metadata provider access"
```

## Self-Review

Spec coverage:
- Built-in backend keys: Tasks 2 and 6 add Android BuildConfig fields and Nuxt runtime config.
- Optional custom keys: Tasks 2, 4, 6, and 7 keep custom secret storage and make custom keys override built-ins.
- Enabled by default: Tasks 3 and 7 set defaults and sanitize stale false values.
- Cannot delete/disable: Tasks 7 and 8 remove portal delete/disable paths and Android disable controls.
- API call avoidance: Tasks 4 and 5 preserve existing de-duplication, skip TMDB when TVDB succeeds, add TTLs, and close TVDB trailer duplicate-call risk.
- TTLs: Task 5 defines and tests explicit metadata TTLs.
- Docs: Task 9 updates user and developer docs.

Placeholder scan:
- No deferred-work placeholders remain.
- Every code-changing step includes concrete code or an exact replacement rule.

Type consistency:
- `MetadataCredentialSource`, `MetadataProviderCredential`, `MetadataProviderConfig`, and `MetadataApiKeyResolver` names are consistent across tasks.
- Web helper names `metadataProviderConfig` and `resolveMetadataApiKey` are consistent across creation and tests.
- Existing sync names `TmdbSyncSettings` and `TvdbSyncSettings` match current files.

Execution note:
- Do not commit actual API key values. `local.properties`, `local.dev.properties`, and deployment env vars should be changed outside git.
