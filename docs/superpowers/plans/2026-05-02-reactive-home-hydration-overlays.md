# Reactive Home Hydration Overlays Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Modern Home renders first-paint previews immediately, then repaints individual cards in place when canonical metadata, stable IDs, and IMDb-backed ratings arrive.

**Architecture:** Add a durable `HydratedHomeOverlayStore` plus immediate in-memory overlay publication. Route visible/focused/hero hydration through one `HomeHydrationCoordinator`, which uses the existing `MetadataRouterFacade`, stable ID bundle resolver, provider runtime, FieldResolver output, and rating enrichment instead of introducing provider-specific home paths.

**Tech Stack:** Kotlin, Coroutines/Flow, Hilt, SharedPreferences/Gson local persistence, Jetpack Compose state, existing metadata router/runtime, JUnit4/MockK/Robolectric-style unit tests, OpenSpec.

---

## Scope Check

This plan implements one behavior: a durable reactive bridge from home first paint to home card repaint after hydration. It includes storage, coordinator, home state composition, trace/report proof, and tests because all are required to make the behavior reliable and observable. It does not change provider authority rules, scrobble ID strategy, or the rail preview mapper contract.

## File Structure

Create:

- `app/src/main/java/com/nexio/tv/domain/model/HydratedHomeOverlay.kt`  
  Durable overlay domain model, hydration state enum, field trace DTO, key helpers, display hash helper.

- `app/src/main/java/com/nexio/tv/data/local/HydratedHomeOverlayStore.kt`  
  SharedPreferences/Gson backed overlay store. Owns canonical overlay records and item-key aliases, exposes batched `Flow<Map<String, HydratedHomeOverlay>>`.

- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationCoordinator.kt`  
  Single home hydration scheduler/runner for visible, focused, adjacent, and hero candidates.

- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationOverlayApplier.kt`  
  Pure helpers that apply overlays to `MetaPreview` and rows without changing membership/order.

- `app/src/test/java/com/nexio/tv/data/local/HydratedHomeOverlayStoreTest.kt`  
  Store persistence, aliasing, expiry, and observation tests.

- `app/src/test/java/com/nexio/tv/ui/screens/home/HomeHydrationOverlayApplierTest.kt`  
  Pure overlay composition tests.

- `app/src/test/java/com/nexio/tv/ui/screens/home/HomeHydrationCoordinatorTest.kt`  
  Coordinator tests for cache hit, network hydration, stable ID/rating enrichment, failure, profile mismatch.

- `app/src/test/java/com/nexio/tv/ui/screens/home/HomeReactiveHydrationPipelineTest.kt`  
  HomeViewModel-level tests proving visible/focused hydration repaints `_uiState.catalogRows` without row reorder.

- `openspec/changes/add-reactive-home-hydration-overlays/proposal.md`
- `openspec/changes/add-reactive-home-hydration-overlays/tasks.md`
- `openspec/changes/add-reactive-home-hydration-overlays/specs/home-startup-refresh/spec.md`

Modify:

- `app/src/main/java/com/nexio/tv/core/trace/TraceMetadataEvents.kt`  
  Add home hydration trace emitters.

- `app/src/test/java/com/nexio/tv/core/trace/TraceMetadataEventsTest.kt`  
  Cover the new trace payloads.

- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt`  
  Inject store/coordinator, hold overlay state, and observe overlays for current item keys.

- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt`  
  Compose overlays into rows in `updateCatalogRowsPipeline`, and feed visible item hydration to `HomeHydrationCoordinator`.

- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt`  
  Route focused/hero hydration through `HomeHydrationCoordinator`.

- `app/src/main/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinator.kt`  
  Remove visible hydration cache-write responsibility after coordinator migration and keep this class responsible for image prefetch work only.

- `app/src/test/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinatorTest.kt`
- `app/src/test/java/com/nexio/tv/ui/screens/home/HomeViewModelFocusHydrationTest.kt`
- `app/src/test/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipelineTest.kt`
- `app/src/test/java/com/nexio/tv/metadata/audit/MetadataAuditReport.kt`
- `app/src/test/java/com/nexio/tv/metadata/audit/MetadataAuditRunner.kt`
- `app/src/test/java/com/nexio/tv/metadata/audit/MetadataAuditReportWriter.kt`
- `app/src/test/java/com/nexio/tv/metadata/audit/MetadataExecutionAuditGoldenTest.kt`
- `app/src/test/java/com/nexio/tv/architecture/RailPreviewLifecycleArchitectureTest.kt`

---

### Task 1: OpenSpec Change

**Files:**
- Create: `openspec/changes/add-reactive-home-hydration-overlays/proposal.md`
- Create: `openspec/changes/add-reactive-home-hydration-overlays/tasks.md`
- Create: `openspec/changes/add-reactive-home-hydration-overlays/specs/home-startup-refresh/spec.md`

- [ ] **Step 1: Add the proposal**

Create `openspec/changes/add-reactive-home-hydration-overlays/proposal.md`:

```markdown
## Why

Modern Home proves first paint and canonical hydration independently, but visible/background hydration can write metadata cache entries without a guaranteed observed card repaint. Built-in API rails and addon rows need a durable reactive bridge:

```text
first paint preview -> stable ID bundle -> canonical hydration -> hydrated overlay -> item-level home repaint
```

## What Changes

### ADDED

- `HydratedHomeOverlayStore` persists language/policy-scoped display overlays by canonical identity and item-key aliases.
- `HomeHydrationCoordinator` runs visible/focused/adjacent/hero hydration through existing metadata router, stable ID, runtime, FieldResolver, and rating enrichment paths.
- Modern Home composes first-paint preview rows with hydrated overlays before publishing UI state.
- Home hydration trace events prove before/after repaint behavior.
- Metadata execution report scenarios prove first paint, hydration, cache hit, failure fallback, and profile-switch ignore behavior.

### MODIFIED

- Visible home hydration no longer stops at `MetadataDiskCacheStore.writeHomeDisplayMetadata`; it writes a hydrated overlay and publishes an in-memory patch.
- Focused and hero hydration use the same coordinator/store path as visible hydration.

## Impact

- Affected specs: `home-startup-refresh`.
- Affected code: home ViewModel pipelines, metadata trace events, metadata audit tests, local overlay persistence.
- No provider authority rule changes.
- No new provider-specific renderer, hydration scheduler, FieldResolver, or rating resolver.
```

- [ ] **Step 2: Add the task list**

Create `openspec/changes/add-reactive-home-hydration-overlays/tasks.md`:

```markdown
## 1. Spec and model
- [ ] 1.1 Add OpenSpec delta and validate it
- [ ] 1.2 Add `HydratedHomeOverlay` domain model and tests

## 2. Durable overlay store
- [ ] 2.1 Add `HydratedHomeOverlayStore` persistence and alias tests
- [ ] 2.2 Implement store with batched observation and expiry filtering

## 3. Trace events
- [ ] 3.1 Add home hydration trace tests
- [ ] 3.2 Implement home hydration trace emitters

## 4. Overlay composition
- [ ] 4.1 Add pure overlay applier tests
- [ ] 4.2 Implement overlay applier without row reorder

## 5. HomeHydrationCoordinator
- [ ] 5.1 Add coordinator tests for cache hit, network hydration, ratings, failure, stale session
- [ ] 5.2 Implement coordinator through existing metadata facade and rating enrichment

## 6. HomeViewModel wiring
- [ ] 6.1 Add ViewModel pipeline tests proving visible/focused card repaint
- [ ] 6.2 Observe overlays and compose them in `updateCatalogRowsPipeline`
- [ ] 6.3 Route visible/focused/hero hydration through coordinator

## 7. Audit/report proof
- [ ] 7.1 Add metadata execution report scenarios for home before/after update
- [ ] 7.2 Add architecture guards preventing provider-specific home hydration paths

## 8. Verification
- [ ] 8.1 Run focused unit suites and OpenSpec strict validation
- [ ] 8.2 Build and install releaseProfileable APK; validate logcat trace behavior
```

- [ ] **Step 3: Add the spec delta**

Create `openspec/changes/add-reactive-home-hydration-overlays/specs/home-startup-refresh/spec.md`:

```markdown
## ADDED Requirements

### Requirement: Reactive Home Hydration Overlay

Modern Home SHALL render first-paint previews immediately and SHALL update individual home cards when hydrated metadata overlays arrive.

#### Scenario: First paint does not wait for hydration
- **GIVEN** a rail item has first-paint preview fields
- **WHEN** Modern Home publishes the row
- **THEN** the row is rendered from preview fields
- **AND** no MetadataRouter, ProviderPlanRunner, rating API, or metadata runtime call is required before first paint

#### Scenario: Visible hydration updates current card
- **GIVEN** a visible home card is rendered from preview fields
- **WHEN** canonical hydration resolves a hydrated home overlay for the card
- **THEN** Modern Home updates the existing card display fields in place
- **AND** row order is unchanged
- **AND** focused item identity is unchanged

#### Scenario: Cache-hit overlay updates without network
- **GIVEN** a hydrated overlay exists in local storage for a visible card
- **WHEN** Home observes overlays for current item keys
- **THEN** the card is updated without provider network

#### Scenario: Hydration failure keeps preview
- **GIVEN** a visible card is rendered from preview fields
- **WHEN** identity resolution or canonical hydration fails
- **THEN** the preview remains visible
- **AND** the item state is `FAILED_USING_PREVIEW`

#### Scenario: Late hydration ignored after profile switch
- **GIVEN** a home hydration job started for one profile generation
- **WHEN** the active profile, language, or home generation changes before the job finishes
- **THEN** the hydration result is ignored
- **AND** a `home.hydration_ignored` trace event records the reason

#### Scenario: No provider-specific home path
- **GIVEN** a home item comes from addon, Trakt, MDBList, TMDB, Kitsu, or Simkl
- **WHEN** hydration is requested
- **THEN** the request uses the shared HomeHydrationCoordinator and existing MetadataRouterFacade path
- **AND** no provider-specific home renderer or FieldResolver is used
```

- [ ] **Step 4: Validate the OpenSpec change**

Run:

```bash
openspec validate add-reactive-home-hydration-overlays --strict
```

Expected: validation succeeds with no errors.

- [ ] **Step 5: Commit**

```bash
git add openspec/changes/add-reactive-home-hydration-overlays
git commit -m "spec(home): add reactive hydration overlays"
```

---

### Task 2: Hydrated Overlay Domain Model

**Files:**
- Create: `app/src/main/java/com/nexio/tv/domain/model/HydratedHomeOverlay.kt`
- Test: `app/src/test/java/com/nexio/tv/domain/model/HydratedHomeOverlayTest.kt`

- [ ] **Step 1: Write the failing model tests**

Create `app/src/test/java/com/nexio/tv/domain/model/HydratedHomeOverlayTest.kt`:

```kotlin
package com.nexio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HydratedHomeOverlayTest {
    @Test
    fun `overlay key includes canonical identity language type and policy`() {
        val key = hydratedHomeOverlayKey(
            canonicalProvider = ProviderId.TMDB,
            canonicalId = "550",
            contentType = ContentType.MOVIE,
            languageTag = "en-US",
            policyVersion = 1
        )

        assertEquals("canonical:TMDB:550:type:MOVIE:lang:en-US:policy:1", key)
    }

    @Test
    fun `display hash changes when displayed fields change`() {
        val first = HomeDisplayMetadata(title = "Preview", poster = null).hydratedHomeDisplayHash()
        val second = HomeDisplayMetadata(title = "Canonical", poster = null).hydratedHomeDisplayHash()

        assertFalse(first == second)
    }

    @Test
    fun `freshness uses stale and expiry timestamps`() {
        val overlay = hydratedOverlay(
            updatedAtMs = 1_000L,
            staleAtMs = 2_000L,
            expiresAtMs = 3_000L
        )

        assertFalse(overlay.isStale(nowMs = 1_500L))
        assertTrue(overlay.isStale(nowMs = 2_500L))
        assertFalse(overlay.isExpired(nowMs = 2_500L))
        assertTrue(overlay.isExpired(nowMs = 3_500L))
    }

    private fun hydratedOverlay(
        updatedAtMs: Long,
        staleAtMs: Long,
        expiresAtMs: Long
    ) = HydratedHomeOverlay(
        overlayKey = "canonical:TMDB:550:type:MOVIE:lang:en:policy:1",
        itemKey = "movie:tmdb:550",
        canonicalProvider = ProviderId.TMDB,
        canonicalId = "550",
        imdbId = "tt0137523",
        contentType = ContentType.MOVIE,
        languageTag = "en",
        policyVersion = 1,
        fields = HomeDisplayMetadata(title = "Fight Club"),
        fieldTrace = listOf(
            HydratedHomeFieldTrace(
                field = "TITLE",
                selectedProvider = "TMDB",
                sourceRole = "PRIMARY"
            )
        ),
        displayHash = HomeDisplayMetadata(title = "Fight Club").hydratedHomeDisplayHash(),
        updatedAtMs = updatedAtMs,
        staleAtMs = staleAtMs,
        expiresAtMs = expiresAtMs,
        state = HomeItemHydrationState.CANONICAL_READY
    )
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew --no-daemon --max-workers=1 :app:testUniversalDebugUnitTest --tests com.nexio.tv.domain.model.HydratedHomeOverlayTest
```

Expected: FAIL because `HydratedHomeOverlay` and helpers do not exist.

- [ ] **Step 3: Add the model**

Create `app/src/main/java/com/nexio/tv/domain/model/HydratedHomeOverlay.kt`:

```kotlin
package com.nexio.tv.domain.model

import java.security.MessageDigest

private const val DEFAULT_HOME_OVERLAY_POLICY_VERSION = 1

enum class HomeItemHydrationState {
    PREVIEW_ONLY,
    IDENTITY_RESOLVING,
    HYDRATION_QUEUED,
    HYDRATING,
    CANONICAL_READY,
    FAILED_USING_PREVIEW,
    STALE_READY
}

data class HydratedHomeFieldTrace(
    val field: String,
    val selectedProvider: String,
    val sourceRole: String,
    val rejectedCandidates: List<String> = emptyList()
)

data class HydratedHomeOverlay(
    val overlayKey: String,
    val itemKey: String,
    val canonicalProvider: ProviderId,
    val canonicalId: String,
    val imdbId: String?,
    val contentType: ContentType,
    val languageTag: String,
    val policyVersion: Int = DEFAULT_HOME_OVERLAY_POLICY_VERSION,
    val fields: HomeDisplayMetadata,
    val fieldTrace: List<HydratedHomeFieldTrace>,
    val displayHash: String,
    val updatedAtMs: Long,
    val staleAtMs: Long,
    val expiresAtMs: Long,
    val state: HomeItemHydrationState = HomeItemHydrationState.CANONICAL_READY
) {
    fun isStale(nowMs: Long): Boolean = nowMs >= staleAtMs
    fun isExpired(nowMs: Long): Boolean = nowMs >= expiresAtMs
}

fun hydratedHomeOverlayKey(
    canonicalProvider: ProviderId,
    canonicalId: String,
    contentType: ContentType,
    languageTag: String,
    policyVersion: Int = DEFAULT_HOME_OVERLAY_POLICY_VERSION
): String {
    return "canonical:${canonicalProvider.name}:${canonicalId.trim()}:type:${contentType.name}:lang:${languageTag.trim()}:policy:$policyVersion"
}

fun HomeDisplayMetadata.hydratedHomeDisplayHash(): String {
    val raw = listOf(
        title.orEmpty(),
        logo.orEmpty(),
        description.orEmpty(),
        genres.joinToString("|"),
        releaseInfo.orEmpty(),
        runtime.orEmpty(),
        imdbRating?.toString().orEmpty(),
        ratingSource?.name.orEmpty(),
        tomatoesRating?.toString().orEmpty(),
        poster.orEmpty(),
        posterProviderTag.orEmpty(),
        backdrop.orEmpty()
    ).joinToString(separator = "\u001F")
    val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:

```bash
./gradlew --no-daemon --max-workers=1 :app:testUniversalDebugUnitTest --tests com.nexio.tv.domain.model.HydratedHomeOverlayTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/domain/model/HydratedHomeOverlay.kt app/src/test/java/com/nexio/tv/domain/model/HydratedHomeOverlayTest.kt
git commit -m "feat(home): add hydrated overlay model"
```

---

### Task 3: Durable HydratedHomeOverlayStore

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/local/HydratedHomeOverlayStore.kt`
- Test: `app/src/test/java/com/nexio/tv/data/local/HydratedHomeOverlayStoreTest.kt`

- [ ] **Step 1: Write failing store tests**

Create `app/src/test/java/com/nexio/tv/data/local/HydratedHomeOverlayStoreTest.kt`:

```kotlin
package com.nexio.tv.data.local

import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.HydratedHomeFieldTrace
import com.nexio.tv.domain.model.HydratedHomeOverlay
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.hydratedHomeDisplayHash
import com.nexio.tv.domain.model.hydratedHomeOverlayKey
import com.nexio.tv.testutil.InMemorySharedPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HydratedHomeOverlayStoreTest {
    @Test
    fun `upsert persists canonical overlay and aliases item keys`() = runTest {
        val prefs = InMemorySharedPreferences()
        val store = HydratedHomeOverlayStore(mockContext(prefs))
        val overlay = overlay(itemKey = "movie:tmdb:550")

        store.upsert(overlay, aliases = setOf("movie:tmdb:550", "movie:imdb:tt0137523"))

        assertEquals("Fight Club", store.readByCanonicalIdentity(
            canonicalProvider = ProviderId.TMDB,
            canonicalId = "550",
            contentType = ContentType.MOVIE,
            languageTag = "en",
            policyVersion = 1
        )?.fields?.title)

        val observed = store.observeForItemKeys(
            itemKeys = setOf("movie:imdb:tt0137523"),
            languageTag = "en",
            policyVersion = 1
        ).first()
        assertEquals("Fight Club", observed.getValue("movie:imdb:tt0137523").fields.title)
    }

    @Test
    fun `expired overlays are not returned`() = runTest {
        val store = HydratedHomeOverlayStore(mockContext(InMemorySharedPreferences()))
        val expired = overlay(
            itemKey = "movie:tmdb:550",
            updatedAtMs = 1_000L,
            staleAtMs = 2_000L,
            expiresAtMs = 3_000L
        )

        store.upsert(expired, aliases = setOf("movie:tmdb:550"))

        assertNull(store.readByCanonicalIdentity(
            canonicalProvider = ProviderId.TMDB,
            canonicalId = "550",
            contentType = ContentType.MOVIE,
            languageTag = "en",
            policyVersion = 1,
            nowMs = 4_000L
        ))
    }

    @Test
    fun `removeAliases stops item key observation without deleting canonical overlay`() = runTest {
        val store = HydratedHomeOverlayStore(mockContext(InMemorySharedPreferences()))
        val overlay = overlay(itemKey = "movie:tmdb:550")
        store.upsert(overlay, aliases = setOf("movie:tmdb:550", "movie:imdb:tt0137523"))

        store.removeAliases(
            itemKeys = setOf("movie:imdb:tt0137523"),
            languageTag = "en",
            policyVersion = 1
        )

        val observed = store.observeForItemKeys(
            itemKeys = setOf("movie:imdb:tt0137523"),
            languageTag = "en",
            policyVersion = 1
        ).first()
        assertEquals(emptyMap<String, HydratedHomeOverlay>(), observed)
        assertEquals("Fight Club", store.readByCanonicalIdentity(
            canonicalProvider = ProviderId.TMDB,
            canonicalId = "550",
            contentType = ContentType.MOVIE,
            languageTag = "en",
            policyVersion = 1
        )?.fields?.title)
    }

    private fun overlay(
        itemKey: String,
        updatedAtMs: Long = 10_000L,
        staleAtMs: Long = 20_000L,
        expiresAtMs: Long = 30_000L
    ): HydratedHomeOverlay {
        val fields = HomeDisplayMetadata(title = "Fight Club", poster = "poster.jpg", imdbRating = 8.8f)
        return HydratedHomeOverlay(
            overlayKey = hydratedHomeOverlayKey(ProviderId.TMDB, "550", ContentType.MOVIE, "en", 1),
            itemKey = itemKey,
            canonicalProvider = ProviderId.TMDB,
            canonicalId = "550",
            imdbId = "tt0137523",
            contentType = ContentType.MOVIE,
            languageTag = "en",
            policyVersion = 1,
            fields = fields,
            fieldTrace = listOf(HydratedHomeFieldTrace("TITLE", "TMDB", "PRIMARY")),
            displayHash = fields.hydratedHomeDisplayHash(),
            updatedAtMs = updatedAtMs,
            staleAtMs = staleAtMs,
            expiresAtMs = expiresAtMs
        )
    }

    private fun mockContext(prefs: InMemorySharedPreferences): android.content.Context {
        val context = mockk<android.content.Context>()
        every { context.getSharedPreferences(any(), any()) } returns prefs
        return context
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew --no-daemon --max-workers=1 :app:testUniversalDebugUnitTest --tests com.nexio.tv.data.local.HydratedHomeOverlayStoreTest
```

Expected: FAIL because `HydratedHomeOverlayStore` does not exist.

- [ ] **Step 3: Implement the store**

Create `app/src/main/java/com/nexio/tv/data/local/HydratedHomeOverlayStore.kt`:

```kotlin
package com.nexio.tv.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HydratedHomeOverlay
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.hydratedHomeOverlayKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

@Singleton
class HydratedHomeOverlayStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val gson = Gson()
    private val version = MutableStateFlow(0L)

    fun observeForItemKeys(
        itemKeys: Set<String>,
        languageTag: String,
        policyVersion: Int
    ): Flow<Map<String, HydratedHomeOverlay>> {
        val normalizedKeys = itemKeys.mapNotNull { it.trim().takeIf(String::isNotEmpty) }.toSet()
        return version.map {
            readForItemKeys(
                itemKeys = normalizedKeys,
                languageTag = languageTag,
                policyVersion = policyVersion
            )
        }
    }

    suspend fun upsert(
        overlay: HydratedHomeOverlay,
        aliases: Set<String>
    ) {
        val prefs = prefs()
        val normalizedAliases = (aliases + overlay.itemKey)
            .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
            .toSet()
        val payload = JsonObject().apply {
            addProperty("schemaVersion", SCHEMA_VERSION)
            add("value", gson.toJsonTree(overlay))
        }
        val editor = prefs.edit()
            .putString(overlayPrefsKey(overlay.overlayKey), gson.toJson(payload))
        normalizedAliases.forEach { itemKey ->
            editor.putString(aliasPrefsKey(itemKey, overlay.languageTag, overlay.policyVersion), overlay.overlayKey)
        }
        editor.apply()
        version.value += 1
    }

    suspend fun removeAliases(
        itemKeys: Set<String>,
        languageTag: String,
        policyVersion: Int
    ) {
        val editor = prefs().edit()
        itemKeys.mapNotNull { it.trim().takeIf(String::isNotEmpty) }.forEach { itemKey ->
            editor.remove(aliasPrefsKey(itemKey, languageTag, policyVersion))
        }
        editor.apply()
        version.value += 1
    }

    fun readForItemKeys(
        itemKeys: Set<String>,
        languageTag: String,
        policyVersion: Int,
        nowMs: Long = System.currentTimeMillis()
    ): Map<String, HydratedHomeOverlay> {
        val prefs = prefs()
        return itemKeys.mapNotNull { itemKey ->
            val overlayKey = prefs.getString(aliasPrefsKey(itemKey, languageTag, policyVersion), null)
                ?: return@mapNotNull null
            val overlay = readOverlayByKey(overlayKey, nowMs) ?: return@mapNotNull null
            itemKey to overlay
        }.toMap()
    }

    fun readByCanonicalIdentity(
        canonicalProvider: ProviderId,
        canonicalId: String,
        contentType: ContentType,
        languageTag: String,
        policyVersion: Int,
        nowMs: Long = System.currentTimeMillis()
    ): HydratedHomeOverlay? {
        val key = hydratedHomeOverlayKey(
            canonicalProvider = canonicalProvider,
            canonicalId = canonicalId,
            contentType = contentType,
            languageTag = languageTag,
            policyVersion = policyVersion
        )
        return readOverlayByKey(key, nowMs)
    }

    private fun readOverlayByKey(overlayKey: String, nowMs: Long): HydratedHomeOverlay? {
        return runCatching {
            val raw = prefs().getString(overlayPrefsKey(overlayKey), null)?.takeIf { it.isNotBlank() } ?: return null
            val root = gson.fromJson(raw, JsonObject::class.java) ?: return null
            if ((root.get("schemaVersion")?.asInt ?: 0) != SCHEMA_VERSION) return null
            val overlay = gson.fromJson(root.get("value"), HydratedHomeOverlay::class.java) ?: return null
            overlay.takeUnless { it.isExpired(nowMs) }
        }.onFailure { error ->
            Log.w(TAG, "Failed to read hydrated home overlay key=$overlayKey", error)
        }.getOrNull()
    }

    private fun prefs() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun overlayPrefsKey(overlayKey: String): String = "overlay::$overlayKey"

    private fun aliasPrefsKey(itemKey: String, languageTag: String, policyVersion: Int): String =
        "alias::${languageTag.trim()}::policy:$policyVersion::${itemKey.trim()}"

    private companion object {
        const val TAG = "HydratedHomeOverlayStore"
        const val PREFS_NAME = "hydrated_home_overlay_v1"
        const val SCHEMA_VERSION = 1
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:

```bash
./gradlew --no-daemon --max-workers=1 :app:testUniversalDebugUnitTest --tests com.nexio.tv.data.local.HydratedHomeOverlayStoreTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/local/HydratedHomeOverlayStore.kt app/src/test/java/com/nexio/tv/data/local/HydratedHomeOverlayStoreTest.kt
git commit -m "feat(home): persist hydrated overlays"
```

---

### Task 4: Home Hydration Trace Events

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/trace/TraceMetadataEvents.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/trace/TraceMetadataEventsTest.kt`

- [ ] **Step 1: Write failing trace tests**

Append to `TraceMetadataEventsTest`:

```kotlin
@Test
fun `home hydration applied event includes before after hashes and focus stability`() {
    val sink = RecordingTraceSink()
    val events = TraceMetadataEvents(sink) { "session-home" }

    events.emitHomeHydrationApplied(
        railId = "tmdb.trending.movies",
        itemKey = "movie:tmdb:550",
        firstPaintSource = "RAIL_PREVIEW",
        canonicalProvider = "TMDB",
        canonicalId = "550",
        imdbId = "tt0137523",
        trigger = "VISIBLE_HOME_HYDRATION",
        priority = "VISIBLE",
        workClass = "BACKGROUND_HYDRATION",
        changedFields = listOf("poster", "rating"),
        displayHashBefore = "before",
        displayHashAfter = "after",
        rowOrderChanged = false,
        focusChanged = false,
        networkExecuted = false,
        cacheDecision = "HIT"
    )

    val event = sink.events.single()
    assertEquals("home.hydration_applied", event.eventType)
    assertEquals("movie:tmdb:550", event.payload["itemKey"])
    assertEquals(false, event.payload["rowOrderChanged"])
    assertEquals(false, event.payload["focusChanged"])
    assertEquals(listOf("poster", "rating"), event.payload["changedFields"])
}

@Test
fun `home hydration ignored event records ignore reason`() {
    val sink = RecordingTraceSink()
    val events = TraceMetadataEvents(sink) { "session-home" }

    events.emitHomeHydrationIgnored(
        itemKey = "series:kitsu:12",
        reason = "PROFILE_SESSION_MISMATCH",
        trigger = "FOCUSED_HOME_ITEM"
    )

    val event = sink.events.single()
    assertEquals("home.hydration_ignored", event.eventType)
    assertEquals("PROFILE_SESSION_MISMATCH", event.payload["reason"])
}
```

- [ ] **Step 2: Run trace tests to verify failure**

Run:

```bash
./gradlew --no-daemon --max-workers=1 :app:testUniversalDebugUnitTest --tests com.nexio.tv.core.trace.TraceMetadataEventsTest
```

Expected: FAIL because `emitHomeHydrationApplied` and `emitHomeHydrationIgnored` do not exist.

- [ ] **Step 3: Add trace emitters**

Add these methods to `TraceMetadataEvents`:

```kotlin
fun emitHomeHydrationStarted(
    railId: String?,
    itemKey: String,
    firstPaintSource: String,
    trigger: String,
    priority: String,
    workClass: String
) {
    emitHomeEvent(
        eventType = "home.hydration_started",
        payload = mapOf(
            "railId" to railId,
            "itemKey" to itemKey,
            "firstPaintSource" to firstPaintSource,
            "trigger" to trigger,
            "priority" to priority,
            "workClass" to workClass
        )
    )
}

fun emitHomeHydrationOverlayWritten(
    itemKey: String,
    canonicalProvider: String,
    canonicalId: String,
    imdbId: String?,
    displayHash: String
) {
    emitHomeEvent(
        eventType = "home.hydration_overlay_written",
        payload = mapOf(
            "itemKey" to itemKey,
            "canonicalProvider" to canonicalProvider,
            "canonicalId" to canonicalId,
            "imdbId" to imdbId,
            "displayHash" to displayHash
        )
    )
}

fun emitHomeHydrationApplied(
    railId: String?,
    itemKey: String,
    firstPaintSource: String,
    canonicalProvider: String,
    canonicalId: String,
    imdbId: String?,
    trigger: String,
    priority: String,
    workClass: String,
    changedFields: List<String>,
    displayHashBefore: String,
    displayHashAfter: String,
    rowOrderChanged: Boolean,
    focusChanged: Boolean,
    networkExecuted: Boolean,
    cacheDecision: String?
) {
    emitHomeEvent(
        eventType = "home.hydration_applied",
        payload = mapOf(
            "railId" to railId,
            "itemKey" to itemKey,
            "firstPaintSource" to firstPaintSource,
            "canonicalProvider" to canonicalProvider,
            "canonicalId" to canonicalId,
            "imdbId" to imdbId,
            "trigger" to trigger,
            "priority" to priority,
            "workClass" to workClass,
            "changedFields" to changedFields,
            "displayHashBefore" to displayHashBefore,
            "displayHashAfter" to displayHashAfter,
            "rowOrderChanged" to rowOrderChanged,
            "focusChanged" to focusChanged,
            "networkExecuted" to networkExecuted,
            "cacheDecision" to cacheDecision
        )
    )
}

fun emitHomeHydrationIgnored(
    itemKey: String,
    reason: String,
    trigger: String
) {
    emitHomeEvent(
        eventType = "home.hydration_ignored",
        payload = mapOf(
            "itemKey" to itemKey,
            "reason" to reason,
            "trigger" to trigger
        )
    )
}

fun emitHomeHydrationFailedUsingPreview(
    itemKey: String,
    reason: String,
    trigger: String
) {
    emitHomeEvent(
        eventType = "home.hydration_failed_using_preview",
        payload = mapOf(
            "itemKey" to itemKey,
            "reason" to reason,
            "trigger" to trigger
        )
    )
}

private fun emitHomeEvent(eventType: String, payload: Map<String, Any?>) {
    val sid = sessionId() ?: return
    sink.emit(
        TraceEventEnvelope(
            traceSessionId = sid,
            sequence = seq.incrementAndGet(),
            wallClockMs = System.currentTimeMillis(),
            elapsedRealtimeMs = System.nanoTime() / 1_000_000,
            threadName = Thread.currentThread().name,
            eventType = eventType,
            payload = payload
        )
    )
}
```

- [ ] **Step 4: Run trace tests to verify pass**

Run:

```bash
./gradlew --no-daemon --max-workers=1 :app:testUniversalDebugUnitTest --tests com.nexio.tv.core.trace.TraceMetadataEventsTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/trace/TraceMetadataEvents.kt app/src/test/java/com/nexio/tv/core/trace/TraceMetadataEventsTest.kt
git commit -m "feat(trace): emit home hydration events"
```

---

### Task 5: Pure Overlay Applier

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationOverlayApplier.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeHydrationOverlayApplierTest.kt`

- [ ] **Step 1: Write failing applier tests**

Create `app/src/test/java/com/nexio/tv/ui/screens/home/HomeHydrationOverlayApplierTest.kt`:

```kotlin
package com.nexio.tv.ui.screens.home

import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.HydratedHomeFieldTrace
import com.nexio.tv.domain.model.HydratedHomeOverlay
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.TitleRatingSource
import com.nexio.tv.domain.model.hydratedHomeDisplayHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class HomeHydrationOverlayApplierTest {
    @Test
    fun `overlay replaces display fields without changing row order`() {
        val first = preview("550", "Preview title")
        val second = preview("551", "Second")
        val row = row(listOf(first, second))
        val overlay = overlay(
            itemKey = "movie:550",
            fields = HomeDisplayMetadata(
                title = "Canonical title",
                poster = "poster.jpg",
                imdbRating = 8.8f,
                ratingSource = TitleRatingSource.IMDB
            )
        )

        val updated = row.applyHydratedHomeOverlays(mapOf("movie:550" to overlay))

        assertEquals(listOf("550", "551"), updated.items.map { it.id })
        assertEquals("Canonical title", updated.items.first().name)
        assertEquals("poster.jpg", updated.items.first().poster)
        assertEquals(8.8f, updated.items.first().imdbRating ?: 0f, 0f)
        assertEquals("Second", updated.items.last().name)
    }

    @Test
    fun `row instance is reused when overlay display does not change items`() {
        val item = preview("550", "Same title")
        val row = row(listOf(item))
        val overlay = overlay(
            itemKey = "movie:550",
            fields = item.toHomeDisplayMetadata()
        )

        val updated = row.applyHydratedHomeOverlays(mapOf("movie:550" to overlay))

        assertSame(row, updated)
    }

    private fun preview(id: String, title: String) = MetaPreview(
        id = id,
        type = ContentType.MOVIE,
        rawType = "movie",
        name = title,
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList()
    )

    private fun row(items: List<MetaPreview>) = CatalogRow(
        addonId = "addon",
        addonName = "Addon",
        addonBaseUrl = "https://addon.example",
        catalogId = "popular",
        catalogName = "Popular",
        type = ContentType.MOVIE,
        items = items,
        hasMore = false
    )

    private fun overlay(itemKey: String, fields: HomeDisplayMetadata) = HydratedHomeOverlay(
        overlayKey = "canonical:TMDB:550:type:MOVIE:lang:en:policy:1",
        itemKey = itemKey,
        canonicalProvider = ProviderId.TMDB,
        canonicalId = "550",
        imdbId = "tt0137523",
        contentType = ContentType.MOVIE,
        languageTag = "en",
        fields = fields,
        fieldTrace = listOf(HydratedHomeFieldTrace("TITLE", "TMDB", "PRIMARY")),
        displayHash = fields.hydratedHomeDisplayHash(),
        updatedAtMs = 1L,
        staleAtMs = 2L,
        expiresAtMs = 3L
    )
}
```

Add this import to `HomeHydrationOverlayApplierTest.kt` with the other domain model imports:

```kotlin
import com.nexio.tv.domain.model.toHomeDisplayMetadata
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew --no-daemon --max-workers=1 :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.home.HomeHydrationOverlayApplierTest
```

Expected: FAIL because `applyHydratedHomeOverlays` does not exist.

- [ ] **Step 3: Implement the applier**

Create `app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationOverlayApplier.kt`:

```kotlin
package com.nexio.tv.ui.screens.home

import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.HydratedHomeOverlay
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.applyTo
import com.nexio.tv.domain.model.toHomeDisplayMetadata

internal fun CatalogRow.applyHydratedHomeOverlays(
    overlaysByItemKey: Map<String, HydratedHomeOverlay>
): CatalogRow {
    if (overlaysByItemKey.isEmpty()) return this
    var changed = false
    val updatedItems = items.map { item ->
        val itemKey = item.homeOverlayItemKey()
        val overlay = overlaysByItemKey[itemKey] ?: return@map item
        val updated = overlay.fields.applyTo(item)
        if (updated != item) changed = true
        updated
    }
    return if (changed) copy(items = updatedItems) else this
}

internal fun List<CatalogRow>.applyHydratedHomeOverlays(
    overlaysByItemKey: Map<String, HydratedHomeOverlay>
): List<CatalogRow> {
    if (overlaysByItemKey.isEmpty()) return this
    var changed = false
    val updated = map { row ->
        val next = row.applyHydratedHomeOverlays(overlaysByItemKey)
        if (next !== row) changed = true
        next
    }
    return if (changed) updated else this
}

internal fun MetaPreview.homeOverlayItemKey(): String = "${apiType}:${id}"

internal fun MetaPreview.displayHashForHomeOverlay(): String =
    toHomeDisplayMetadata().hydratedHomeDisplayHash()
```

Add import:

```kotlin
import com.nexio.tv.domain.model.hydratedHomeDisplayHash
```

- [ ] **Step 4: Run test to verify it passes**

Run:

```bash
./gradlew --no-daemon --max-workers=1 :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.home.HomeHydrationOverlayApplierTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationOverlayApplier.kt app/src/test/java/com/nexio/tv/ui/screens/home/HomeHydrationOverlayApplierTest.kt
git commit -m "feat(home): apply hydrated overlays to rows"
```

---

### Task 6: HomeHydrationCoordinator

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationCoordinator.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeHydrationCoordinatorTest.kt`

- [ ] **Step 1: Write failing coordinator tests**

Create `app/src/test/java/com/nexio/tv/ui/screens/home/HomeHydrationCoordinatorTest.kt` with these tests:

```kotlin
package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.metadata.router.MetadataDepth
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataResolutionResult
import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.core.metadata.router.MetadataDecisionReason
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.MetadataRequest
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.core.metadata.router.ResolvedMetadataDocument
import com.nexio.tv.core.metadata.router.StableIdBundle
import com.nexio.tv.core.metadata.router.StableIdResolutionTrigger
import com.nexio.tv.core.metadata.router.CanonicalStableIds
import com.nexio.tv.core.metadata.router.SidecarStableIds
import com.nexio.tv.core.metadata.router.SourceStableIds
import com.nexio.tv.data.local.HydratedHomeOverlayStore
import com.nexio.tv.data.repository.TitleRatingOverrideRepository
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.TitleRatingSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeHydrationCoordinatorTest {
    @Test
    fun `visible hydration writes overlay with canonical fields and imdb rating`() = runTest {
        val facade = mockk<MetadataRouterFacade>()
        val store = mockk<HydratedHomeOverlayStore>(relaxed = true)
        val ratings = mockk<TitleRatingOverrideRepository>()
        val overlaySlot = slot<com.nexio.tv.domain.model.HydratedHomeOverlay>()
        val preview = preview(id = "550", title = "Preview", stableIds = ProviderIds(tmdb = "550"))
        val bundle = stableBundle(itemKey = "movie:550")

        coEvery { facade.resolveRequest(any()) } returns resolutionResult()
        coEvery {
            facade.resolveStableIdBundle(any(), StableIdResolutionTrigger.VISIBLE_HOME_HYDRATION, "movie:550")
        } returns bundle
        coEvery { ratings.enrichPreview(any(), bundle) } answers {
            firstArg<MetaPreview>().copy(imdbRating = 8.8f, ratingSource = TitleRatingSource.IMDB)
        }
        coEvery { store.upsert(capture(overlaySlot), any()) } returns Unit

        coordinator(facade, store, ratings).hydrate(
            item = preview,
            trigger = StableIdResolutionTrigger.VISIBLE_HOME_HYDRATION,
            priority = HomeHydrationPriority.VISIBLE,
            languageTag = "en",
            expectedGeneration = 7L,
            currentGeneration = { 7L },
            onOverlayApplied = {}
        )

        assertEquals("Canonical title", overlaySlot.captured.fields.title)
        assertEquals(8.8f, overlaySlot.captured.fields.imdbRating ?: 0f, 0f)
        assertEquals("tt0137523", overlaySlot.captured.imdbId)
        coVerify(exactly = 1) { store.upsert(any(), match { it.contains("movie:550") }) }
    }

    @Test
    fun `late hydration is ignored after generation changes`() = runTest {
        val facade = mockk<MetadataRouterFacade>()
        val store = mockk<HydratedHomeOverlayStore>(relaxed = true)
        val ratings = mockk<TitleRatingOverrideRepository>(relaxed = true)
        coEvery { facade.resolveRequest(any()) } returns resolutionResult()
        coEvery { facade.resolveStableIdBundle(any(), any(), any()) } returns stableBundle("movie:550")

        coordinator(facade, store, ratings).hydrate(
            item = preview(id = "550", title = "Preview", stableIds = ProviderIds(tmdb = "550")),
            trigger = StableIdResolutionTrigger.VISIBLE_HOME_HYDRATION,
            priority = HomeHydrationPriority.VISIBLE,
            languageTag = "en",
            expectedGeneration = 7L,
            currentGeneration = { 8L },
            onOverlayApplied = {}
        )

        coVerify(exactly = 0) { store.upsert(any(), any()) }
    }

    private fun coordinator(
        facade: MetadataRouterFacade,
        store: HydratedHomeOverlayStore,
        ratings: TitleRatingOverrideRepository
    ) = HomeHydrationCoordinator(
        metadataRouterFacade = facade,
        overlayStore = store,
        titleRatingOverrideRepository = ratings,
        traceEvents = com.nexio.tv.core.trace.TraceMetadataEvents(
            com.nexio.tv.core.trace.NoopRuntimeTraceSink
        ) { null }
    )

    private fun preview(id: String, title: String, stableIds: ProviderIds) = MetaPreview(
        id = id,
        type = ContentType.MOVIE,
        rawType = "movie",
        name = title,
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = 0f,
        ratingSource = TitleRatingSource.TMDB,
        genres = emptyList(),
        firstPaintStableIds = stableIds,
        firstPaintSourceProvider = ProviderId.TMDB
    )

    private fun resolutionResult() = MetadataResolutionResult(
        route = MetadataRoute(
            provider = MetadataPrimaryProvider.TMDB,
            parentId = "tmdb:550",
            mediaKind = MetadataMediaKind.MOVIE,
            reason = MetadataDecisionReason.ITEM_TYPE_MOVIE,
            sourceContext = com.nexio.tv.core.metadata.router.MetadataSourceContext(),
            language = "en",
            targetIds = mapOf(MetadataPrimaryProvider.TMDB to "550"),
            trace = emptyList()
        ),
        plan = null,
        resolverSchedule = com.nexio.tv.core.metadata.router.ResolverSchedule(
            depth = MetadataDepth.DETAIL_CORE,
            localResolvers = emptyList(),
            networkResolvers = emptyList()
        ),
        resolvedDocument = ResolvedMetadataDocument(
            canonicalId = "tmdb:550",
            title = "Canonical title",
            overview = "Canonical overview",
            poster = "poster.jpg",
            backdrop = null,
            logo = null,
            rating = 8.4,
            runtimeMinutes = 139,
            fieldOwners = emptyMap(),
            ignoredOverwrites = emptyList()
        ),
        displayMetadata = HomeDisplayMetadata(
            title = "Canonical title",
            description = "Canonical overview",
            poster = "poster.jpg",
            imdbRating = 8.4f,
            ratingSource = TitleRatingSource.TMDB
        ),
        trace = emptyList()
    )

    private fun stableBundle(itemKey: String) = StableIdBundle(
        itemKey = itemKey,
        itemType = ContentType.MOVIE,
        canonical = CanonicalStableIds(tmdbMovieId = "550"),
        sidecars = SidecarStableIds(imdbId = "tt0137523"),
        source = SourceStableIds(
            sourceProvider = ProviderId.TMDB,
            sourceItemId = "550",
            railId = null,
            observedIds = ProviderIds(tmdb = "550")
        ),
        evidence = emptyList(),
        resolvedAtMs = 1L
    )
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew --no-daemon --max-workers=1 :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.home.HomeHydrationCoordinatorTest
```

Expected: FAIL because `HomeHydrationCoordinator` and `HomeHydrationPriority` do not exist.

- [ ] **Step 3: Implement coordinator skeleton and hydration**

Create `app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationCoordinator.kt`:

```kotlin
package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.metadata.router.MetadataDepth
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataRequest
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.core.metadata.router.ResolvedField
import com.nexio.tv.core.metadata.router.StableIdBundle
import com.nexio.tv.core.metadata.router.StableIdResolutionTrigger
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.data.local.HydratedHomeOverlayStore
import com.nexio.tv.data.repository.TitleRatingOverrideRepository
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.HydratedHomeFieldTrace
import com.nexio.tv.domain.model.HydratedHomeOverlay
import com.nexio.tv.domain.model.HomeItemHydrationState
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.applyTo
import com.nexio.tv.domain.model.hydratedHomeDisplayHash
import com.nexio.tv.domain.model.hydratedHomeOverlayKey
import com.nexio.tv.domain.model.toHomeDisplayMetadata
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

enum class HomeHydrationPriority {
    FOCUSED,
    VISIBLE,
    ADJACENT,
    HERO
}

@Singleton
class HomeHydrationCoordinator @Inject constructor(
    private val metadataRouterFacade: MetadataRouterFacade,
    private val overlayStore: HydratedHomeOverlayStore,
    private val titleRatingOverrideRepository: TitleRatingOverrideRepository,
    private val traceEvents: TraceMetadataEvents
) {
    suspend fun hydrate(
        item: MetaPreview,
        trigger: StableIdResolutionTrigger,
        priority: HomeHydrationPriority,
        languageTag: String,
        expectedGeneration: Long,
        currentGeneration: () -> Long,
        onOverlayApplied: (HydratedHomeOverlay) -> Unit
    ): HydratedHomeOverlay? {
        val itemKey = item.homeOverlayItemKey()
        traceEvents.emitHomeHydrationStarted(
            railId = item.firstPaintRailSource?.name,
            itemKey = itemKey,
            firstPaintSource = item.firstPaintSource.name,
            trigger = trigger.name,
            priority = priority.name,
            workClass = "BACKGROUND_HYDRATION"
        )
        return try {
            val request = item.toHomeOverlayMetadataRequest(languageTag)
            val result = metadataRouterFacade.resolveRequest(request)
            val route = result.route ?: return failed(itemKey, trigger, "ROUTE_EMPTY")
            val bundle = metadataRouterFacade.resolveStableIdBundle(
                request = request,
                trigger = trigger,
                itemKey = itemKey
            )
            val canonicalId = bundle.canonical.providerNativeIdFor(route.provider)
                ?: result.resolvedDocument.canonicalId?.substringAfter(':')
                ?: return failed(itemKey, trigger, "CANONICAL_ID_UNRESOLVED")
            val enrichedPreview = titleRatingOverrideRepository.enrichPreview(
                result.displayMetadata.applyTo(item),
                bundle
            )
            val fields = enrichedPreview.toHomeDisplayMetadata()
            val overlay = buildOverlay(
                item = item,
                fields = fields,
                routeProvider = route.provider,
                canonicalId = canonicalId,
                stableIdBundle = bundle,
                languageTag = languageTag,
                fieldTrace = result.resolvedDocument.sourceRoles.map { (field, role) ->
                    HydratedHomeFieldTrace(
                        field = field.name,
                        selectedProvider = result.resolvedDocument.sourceProviders[field] ?: route.provider.name,
                        sourceRole = role.name
                    )
                }
            )
            if (currentGeneration() != expectedGeneration) {
                traceEvents.emitHomeHydrationIgnored(itemKey, "PROFILE_SESSION_MISMATCH", trigger.name)
                return null
            }
            overlayStore.upsert(overlay, aliases = overlayAliases(item, bundle, itemKey))
            traceEvents.emitHomeHydrationOverlayWritten(
                itemKey = itemKey,
                canonicalProvider = overlay.canonicalProvider.name,
                canonicalId = overlay.canonicalId,
                imdbId = overlay.imdbId,
                displayHash = overlay.displayHash
            )
            onOverlayApplied(overlay)
            overlay
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed(itemKey, trigger, e::class.java.simpleName)
        }
    }

    private fun failed(
        itemKey: String,
        trigger: StableIdResolutionTrigger,
        reason: String
    ): HydratedHomeOverlay? {
        traceEvents.emitHomeHydrationFailedUsingPreview(itemKey, reason, trigger.name)
        return null
    }

    private fun buildOverlay(
        item: MetaPreview,
        fields: HomeDisplayMetadata,
        routeProvider: MetadataPrimaryProvider,
        canonicalId: String,
        stableIdBundle: StableIdBundle,
        languageTag: String,
        fieldTrace: List<HydratedHomeFieldTrace>
    ): HydratedHomeOverlay {
        val provider = routeProvider.toProviderId()
        val now = System.currentTimeMillis()
        return HydratedHomeOverlay(
            overlayKey = hydratedHomeOverlayKey(provider, canonicalId, item.type, languageTag, HOME_OVERLAY_POLICY_VERSION),
            itemKey = item.homeOverlayItemKey(),
            canonicalProvider = provider,
            canonicalId = canonicalId,
            imdbId = stableIdBundle.sidecars.imdbId,
            contentType = item.type,
            languageTag = languageTag,
            policyVersion = HOME_OVERLAY_POLICY_VERSION,
            fields = fields,
            fieldTrace = fieldTrace,
            displayHash = fields.hydratedHomeDisplayHash(),
            updatedAtMs = now,
            staleAtMs = now + OVERLAY_STALE_MS,
            expiresAtMs = now + OVERLAY_EXPIRES_MS,
            state = HomeItemHydrationState.CANONICAL_READY
        )
    }

    private fun MetaPreview.toHomeOverlayMetadataRequest(languageTag: String): MetadataRequest =
        MetadataRequest(
            contentId = id,
            contentType = type,
            sourceContext = toHomeMetadataSourceContext(),
            language = languageTag,
            depth = MetadataDepth.DETAIL_CORE
        )

    private fun overlayAliases(
        item: MetaPreview,
        bundle: StableIdBundle,
        itemKey: String
    ): Set<String> = buildSet {
        add(itemKey)
        bundle.sidecars.imdbId?.takeIf { it.isNotBlank() }?.let { add("${item.apiType}:imdb:$it") }
        bundle.canonical.tmdbMovieId?.takeIf { it.isNotBlank() }?.let { add("${item.apiType}:tmdb:$it") }
        bundle.canonical.tvdbSeriesId?.takeIf { it.isNotBlank() }?.let { add("${item.apiType}:tvdb:$it") }
        bundle.canonical.kitsuAnimeId?.takeIf { it.isNotBlank() }?.let { add("${item.apiType}:kitsu:$it") }
    }

    private fun MetadataPrimaryProvider.toProviderId(): ProviderId =
        when (this) {
            MetadataPrimaryProvider.TMDB -> ProviderId.TMDB
            MetadataPrimaryProvider.TVDB -> ProviderId.TVDB
            MetadataPrimaryProvider.KITSU -> ProviderId.KITSU
            MetadataPrimaryProvider.IMDB -> ProviderId.IMDB
            MetadataPrimaryProvider.TRAKT -> ProviderId.TRAKT
            MetadataPrimaryProvider.SIMKL -> ProviderId.SIMKL
            MetadataPrimaryProvider.RPDB,
            MetadataPrimaryProvider.TOP_POSTERS -> ProviderId.TMDB
        }

    private companion object {
        const val HOME_OVERLAY_POLICY_VERSION = 1
        const val OVERLAY_STALE_MS = 24L * 60L * 60L * 1000L
        const val OVERLAY_EXPIRES_MS = 7L * 24L * 60L * 60L * 1000L
    }
}
```

- [ ] **Step 4: Run coordinator tests**

Run:

```bash
./gradlew --no-daemon --max-workers=1 :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.home.HomeHydrationCoordinatorTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationCoordinator.kt app/src/test/java/com/nexio/tv/ui/screens/home/HomeHydrationCoordinatorTest.kt
git commit -m "feat(home): coordinate reactive hydration"
```

---

### Task 7: Observe And Compose Overlays In HomeViewModel

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeReactiveHydrationPipelineTest.kt`

- [ ] **Step 1: Write failing ViewModel composition test**

Create `app/src/test/java/com/nexio/tv/ui/screens/home/HomeReactiveHydrationPipelineTest.kt`:

```kotlin
package com.nexio.tv.ui.screens.home

import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.HydratedHomeFieldTrace
import com.nexio.tv.domain.model.HydratedHomeOverlay
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.hydratedHomeDisplayHash
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeReactiveHydrationPipelineTest {
    @Test
    fun `overlay composition updates visible card without reordering row`() = runTest {
        val first = preview("550", "Preview")
        val second = preview("551", "Second")
        val row = row(listOf(first, second))
        val overlay = overlay("movie:550", HomeDisplayMetadata(title = "Canonical", poster = "poster.jpg"))

        val updatedRows = listOf(row).applyHydratedHomeOverlays(mapOf("movie:550" to overlay))

        assertEquals(listOf("550", "551"), updatedRows.single().items.map { it.id })
        assertEquals("Canonical", updatedRows.single().items.first().name)
        assertEquals("poster.jpg", updatedRows.single().items.first().poster)
    }

    private fun preview(id: String, title: String) = MetaPreview(
        id = id,
        type = ContentType.MOVIE,
        rawType = "movie",
        name = title,
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList()
    )

    private fun row(items: List<MetaPreview>) = CatalogRow(
        addonId = "addon",
        addonName = "Addon",
        addonBaseUrl = "https://addon.example",
        catalogId = "popular",
        catalogName = "Popular",
        type = ContentType.MOVIE,
        items = items,
        hasMore = false
    )

    private fun overlay(itemKey: String, fields: HomeDisplayMetadata) = HydratedHomeOverlay(
        overlayKey = "canonical:TMDB:550:type:MOVIE:lang:en:policy:1",
        itemKey = itemKey,
        canonicalProvider = ProviderId.TMDB,
        canonicalId = "550",
        imdbId = "tt0137523",
        contentType = ContentType.MOVIE,
        languageTag = "en",
        fields = fields,
        fieldTrace = listOf(HydratedHomeFieldTrace("TITLE", "TMDB", "PRIMARY")),
        displayHash = fields.hydratedHomeDisplayHash(),
        updatedAtMs = 1L,
        staleAtMs = 2L,
        expiresAtMs = 3L
    )
}
```

- [ ] **Step 2: Run test to verify baseline**

Run:

```bash
./gradlew --no-daemon --max-workers=1 :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.home.HomeReactiveHydrationPipelineTest
```

Expected: PASS once Task 5 exists. This test pins the pure behavior before ViewModel wiring.

- [ ] **Step 3: Add overlay dependencies and state to HomeViewModel**

Modify `HomeViewModel` constructor:

```kotlin
internal val hydratedHomeOverlayStore: HydratedHomeOverlayStore,
internal val homeHydrationCoordinator: HomeHydrationCoordinator,
```

Add imports:

```kotlin
import com.nexio.tv.data.local.HydratedHomeOverlayStore
import com.nexio.tv.domain.model.HydratedHomeOverlay
```

Add state:

```kotlin
internal val hydratedHomeOverlaysByItemKey = MutableStateFlow<Map<String, HydratedHomeOverlay>>(emptyMap())
internal var hydratedHomeOverlayObserverJob: Job? = null
```

- [ ] **Step 4: Observe overlays for current item keys**

Add this helper to `HomeViewModelCatalogPipeline.kt`:

```kotlin
internal fun HomeViewModel.observeHydratedHomeOverlaysForRows(rows: List<CatalogRow>) {
    val itemKeys = rows
        .flatMap { row -> row.items.map { item -> item.homeOverlayItemKey() } }
        .toSet()
    if (itemKeys.isEmpty()) {
        hydratedHomeOverlayObserverJob?.cancel()
        hydratedHomeOverlaysByItemKey.value = emptyMap()
        return
    }
    val languageTag = profileBoundary.currentLanguageTag()
    hydratedHomeOverlayObserverJob?.cancel()
    hydratedHomeOverlayObserverJob = viewModelScope.launch {
        hydratedHomeOverlayStore.observeForItemKeys(
            itemKeys = itemKeys,
            languageTag = languageTag,
            policyVersion = 1
        ).collectLatest { overlays ->
            if (hydratedHomeOverlaysByItemKey.value == overlays) return@collectLatest
            hydratedHomeOverlaysByItemKey.value = overlays
            scheduleUpdateCatalogRows()
        }
    }
}
```

- [ ] **Step 5: Compose overlays in `updateCatalogRowsPipeline`**

In `HomeViewModelCatalogPipeline.kt`, after `displayRows` and `fullRowsFiltered` are computed and before creating the transient snapshot, apply overlays:

```kotlin
val overlaysByItemKey = hydratedHomeOverlaysByItemKey.value
val overlaidDisplayRows = displayRows.applyHydratedHomeOverlays(overlaysByItemKey)
val overlaidFullRows = fullRowsFiltered.applyHydratedHomeOverlays(overlaysByItemKey)
val overlaidHeroItems = baseHeroItems.map { item ->
    overlaysByItemKey[item.homeOverlayItemKey()]?.fields?.applyTo(item) ?: item
}
```

Use `overlaidDisplayRows`, `overlaidFullRows`, and `overlaidHeroItems` in the transient snapshot:

```kotlin
val transientSnapshot = HomeCatalogSnapshotStore.Snapshot(
    catalogRows = overlaidDisplayRows,
    fullCatalogRows = overlaidFullRows,
    heroItems = overlaidHeroItems,
    orderedGroupKeys = orderedGroupKeys
)
```

Call:

```kotlin
observeHydratedHomeOverlaysForRows(overlaidDisplayRows + overlaidFullRows)
```

after applying the snapshot so observation tracks current item keys.

- [ ] **Step 6: Run home pipeline tests**

Run:

```bash
./gradlew --no-daemon --max-workers=1 :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.home.HomeReactiveHydrationPipelineTest --tests com.nexio.tv.ui.screens.home.HomeViewModelPresentationPipelineTest
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt app/src/test/java/com/nexio/tv/ui/screens/home/HomeReactiveHydrationPipelineTest.kt
git commit -m "feat(home): compose hydrated overlays into state"
```

---

### Task 8: Route Visible, Focused, And Hero Hydration Through Coordinator

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinator.kt`
- Modify tests under `app/src/test/java/com/nexio/tv/ui/screens/home/`

- [ ] **Step 1: Add failing focused/visible coordinator tests**

First update the `buildTestHomeViewModel` helper in `HomeViewModelFocusHydrationTest` to accept the new collaborators:

```kotlin
private fun buildTestHomeViewModel(
    metadataRouterFacade: MetadataRouterFacade,
    titleRatingOverrideRepository: TitleRatingOverrideRepository = mockk(relaxed = true),
    nonPlaybackHomeWorkAllowed: Boolean = false,
    hydratedHomeOverlayStore: HydratedHomeOverlayStore = mockk(relaxed = true),
    homeHydrationCoordinator: HomeHydrationCoordinator = mockk(relaxed = true)
): HomeViewModel {
```

Inside that helper, replace the existing relaxed `profileBoundary` constructor argument with:

```kotlin
val profileBoundary = mockk<com.nexio.tv.core.profile.ProfileBoundary>(relaxed = true) {
    every { currentLanguageTag() } returns "en"
}
```

Pass the new collaborators to the `HomeViewModel` constructor:

```kotlin
hydratedHomeOverlayStore = hydratedHomeOverlayStore,
homeHydrationCoordinator = homeHydrationCoordinator,
profileBoundary = profileBoundary,
```

Then add this test to `HomeViewModelFocusHydrationTest`:

```kotlin
@Test
fun `focused rail preview hydration delegates to HomeHydrationCoordinator`() = runTest {
    val facade = mockk<MetadataRouterFacade>(relaxed = true)
    val homeHydrationCoordinator = mockk<HomeHydrationCoordinator>()
    val item = railPreviewMetaPreview()
    val viewModel = buildTestHomeViewModel(
        metadataRouterFacade = facade,
        homeHydrationCoordinator = homeHydrationCoordinator,
        nonPlaybackHomeWorkAllowed = true
    )

    coEvery {
        homeHydrationCoordinator.hydrate(
            item = item,
            trigger = StableIdResolutionTrigger.FOCUSED_HOME_ITEM,
            priority = HomeHydrationPriority.FOCUSED,
            languageTag = any(),
            expectedGeneration = any(),
            currentGeneration = any(),
            onOverlayApplied = any()
        )
    } returns null

    viewModel.onItemFocusPipeline(item)
    advanceUntilIdle()

    coVerify(exactly = 1) {
        homeHydrationCoordinator.hydrate(
            item = item,
            trigger = StableIdResolutionTrigger.FOCUSED_HOME_ITEM,
            priority = HomeHydrationPriority.FOCUSED,
            languageTag = any(),
            expectedGeneration = any(),
            currentGeneration = any(),
            onOverlayApplied = any()
        )
    }
}
```

Add this test to `HomeReactiveHydrationPipelineTest`:

```kotlin
@Test
fun `visible item hydration delegates to HomeHydrationCoordinator and publishes overlay`() = runTest {
    val homeHydrationCoordinator = mockk<HomeHydrationCoordinator>()
    val hydratedHomeOverlayStore = mockk<HydratedHomeOverlayStore>(relaxed = true)
    val visible = preview("550", "Preview").copy(type = ContentType.MOVIE, rawType = "movie")
    val overlay = overlay("movie:550", HomeDisplayMetadata(title = "Hydrated title"))
    val viewModel = mockk<HomeViewModel>(relaxed = true)
    every { viewModel.homeHydrationCoordinator } returns homeHydrationCoordinator
    every { viewModel.hydratedHomeOverlaysByItemKey } returns MutableStateFlow(emptyMap())
    every { viewModel.profileBoundary.currentLanguageTag() } returns "en"
    every { viewModel.homeProfileGeneration } returns 10L
    every { viewModel.isNonPlaybackHomeWorkAllowed() } returns true
    every { viewModel.scheduleUpdateCatalogRows() } returns Unit

    coEvery {
        homeHydrationCoordinator.hydrate(
            item = visible,
            trigger = StableIdResolutionTrigger.VISIBLE_HOME_HYDRATION,
            priority = HomeHydrationPriority.VISIBLE,
            languageTag = any(),
            expectedGeneration = any(),
            currentGeneration = any(),
            onOverlayApplied = any()
        )
    } coAnswers {
        arg<(HydratedHomeOverlay) -> Unit>(6).invoke(overlay)
        overlay
    }

    viewModel.hydrateVisibleHomeItemsWithCoordinator(
        items = listOf(visible),
        expectedGeneration = 10L
    )

    assertEquals("Hydrated title", viewModel.hydratedHomeOverlaysByItemKey.value.getValue("movie:550").fields.title)
}
```

- [ ] **Step 2: Run tests to verify failure**

Run:

```bash
./gradlew --no-daemon --max-workers=1 :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.home.HomeViewModelFocusHydrationTest --tests com.nexio.tv.ui.screens.home.HomeReactiveHydrationPipelineTest
```

Expected: FAIL because the coordinator is not wired into visible/focused paths.

- [ ] **Step 3: Add a visible hydration helper in HomeViewModelCatalogPipeline**

Add:

```kotlin
internal suspend fun HomeViewModel.hydrateVisibleHomeItemsWithCoordinator(
    items: List<MetaPreview>,
    expectedGeneration: Long
) {
    if (!isNonPlaybackHomeWorkAllowed()) return
    val uniqueItems = items.distinctBy { it.homeOverlayItemKey() }
    uniqueItems.forEach { item ->
        homeHydrationCoordinator.hydrate(
            item = item,
            trigger = StableIdResolutionTrigger.VISIBLE_HOME_HYDRATION,
            priority = HomeHydrationPriority.VISIBLE,
            languageTag = profileBoundary.currentLanguageTag(),
            expectedGeneration = expectedGeneration,
            currentGeneration = { homeProfileGeneration },
            onOverlayApplied = { overlay ->
                hydratedHomeOverlaysByItemKey.update { current ->
                    if (current[overlay.itemKey]?.displayHash == overlay.displayHash) current else current + (overlay.itemKey to overlay)
                }
                scheduleUpdateCatalogRows()
            }
        )
    }
}
```

- [ ] **Step 4: Replace visible cache-write hydration call**

In `runSerializedPostStartupRefreshPipeline`, replace:

```kotlin
homeCatalogRefreshCoordinator.hydrateAndPrefetchVisibleItems(...)
```

with:

```kotlin
hydrateVisibleHomeItemsWithCoordinator(
    items = visibleItems,
    expectedGeneration = expectedGeneration
)
homeCatalogRefreshCoordinator.prefetchVisibleImagesOnly(
    items = visibleItems,
    telemetryEnabled = startupPerfTelemetryEnabled,
    onLog = ::logHomeRefresh
)
```

Add `prefetchVisibleImagesOnly` to `HomeCatalogRefreshCoordinator` by moving the image-prefetch tail of `hydrateAndPrefetchVisibleItems` into a separate method. Delete `hydrateAndPrefetchVisibleItems` after updating production call sites and tests to use `hydrateVisibleHomeItemsWithCoordinator` plus `prefetchVisibleImagesOnly`.

```kotlin
internal suspend fun prefetchVisibleImagesOnly(
    items: List<MetaPreview>,
    telemetryEnabled: Boolean,
    onLog: (String, String?) -> Unit
) {
    val uniqueItems = items.distinctBy { "${it.apiType}:${it.id}" }
    val imageTelemetry = buildImagePrefetchTelemetry(uniqueItems)
    onLog("image_prefetch_start", "catalogKey=visible_home items=${imageTelemetry.itemsConsidered} urls_total=${imageTelemetry.totalUrls} urls_cached=${imageTelemetry.cachedUrls} urls_missing=${imageTelemetry.missingUrls}")
    if (telemetryEnabled) {
        imageTelemetry.itemEvents.forEach { itemEvent ->
            onLog(itemEvent.first, "catalogKey=visible_home ${itemEvent.second}")
        }
    }
    prefetchImageEntries(imageTelemetry.entriesToFetch)
    onLog("image_prefetch_end", "catalogKey=visible_home fetched_urls=${imageTelemetry.entriesToFetch.size} skipped_cached_urls=${imageTelemetry.cachedUrls} items_cached=${imageTelemetry.itemsFullyCached} items_fetched=${imageTelemetry.itemsNeedingFetch}")
}
```

- [ ] **Step 5: Route focused hydration through coordinator**

In `onItemFocusPipeline`, replace the `fetchProviderEnrichmentForPreview` branch for `FirstPaintSource.RAIL_PREVIEW` with:

```kotlin
if (item.firstPaintSource == FirstPaintSource.RAIL_PREVIEW) {
    val itemKey = item.homeOverlayItemKey()
    val currentHydrationState = focusedItemHydrationStates.getValue(itemKey)
    if (currentHydrationState == RailHydrationState.PREVIEW_ONLY) {
        focusedItemHydrationStates[itemKey] = RailHydrationState.HYDRATING
        val expectedGeneration = homeProfileGeneration
        viewModelScope.launch {
            val overlay = homeHydrationCoordinator.hydrate(
                item = item,
                trigger = StableIdResolutionTrigger.FOCUSED_HOME_ITEM,
                priority = HomeHydrationPriority.FOCUSED,
                languageTag = profileBoundary.currentLanguageTag(),
                expectedGeneration = expectedGeneration,
                currentGeneration = { homeProfileGeneration },
                onOverlayApplied = { applied ->
                    hydratedHomeOverlaysByItemKey.update { current -> current + (applied.itemKey to applied) }
                    scheduleUpdateCatalogRows()
                }
            )
            focusedItemHydrationStates[itemKey] = if (overlay == null) {
                RailHydrationState.HYDRATION_FAILED_USING_PREVIEW
            } else {
                RailHydrationState.CANONICAL_READY
            }
        }
    }
}
```

- [ ] **Step 6: Route hero enrichment through coordinator**

In `enrichHeroItemsPipeline`, replace direct `fetchProviderEnrichmentForPreview` + `titleRatingOverrideRepository.enrichPreview` work with coordinator calls. Return each item with any overlay applied:

```kotlin
val expectedGeneration = homeProfileGeneration
items.map { item ->
    async(Dispatchers.IO) {
        val overlay = homeHydrationCoordinator.hydrate(
            item = item,
            trigger = StableIdResolutionTrigger.FOCUSED_HOME_ITEM,
            priority = HomeHydrationPriority.HERO,
            languageTag = profileBoundary.currentLanguageTag(),
            expectedGeneration = expectedGeneration,
            currentGeneration = { homeProfileGeneration },
            onOverlayApplied = { applied ->
                hydratedHomeOverlaysByItemKey.update { current -> current + (applied.itemKey to applied) }
            }
        )
        overlay?.fields?.applyTo(item) ?: item
    }
}.awaitAll()
```

- [ ] **Step 7: Run focused home tests**

Run:

```bash
./gradlew --no-daemon --max-workers=1 :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.home.HomeViewModelFocusHydrationTest --tests com.nexio.tv.ui.screens.home.HomeCatalogRefreshCoordinatorTest --tests com.nexio.tv.ui.screens.home.HomeViewModelPresentationPipelineTest --tests com.nexio.tv.ui.screens.home.HomeReactiveHydrationPipelineTest
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt app/src/main/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinator.kt app/src/test/java/com/nexio/tv/ui/screens/home
git commit -m "feat(home): repaint cards from hydration overlays"
```

---

### Task 9: Metadata Execution Report Scenarios

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/metadata/audit/MetadataAuditReport.kt`
- Modify: `app/src/test/java/com/nexio/tv/metadata/audit/MetadataAuditRunner.kt`
- Modify: `app/src/test/java/com/nexio/tv/metadata/audit/MetadataAuditReportWriter.kt`
- Modify: `app/src/test/java/com/nexio/tv/metadata/audit/MetadataExecutionAuditGoldenTest.kt`

- [ ] **Step 1: Add failing golden assertions**

In `MetadataExecutionAuditGoldenTest`, add:

```kotlin
@Test
fun `home update scenarios prove first paint then hydrated overlay application`() = runTest {
    val report = MetadataAuditRunner.run()
    val scenarioNames = report.items.map { it.scenario }.toSet()

    assertTrue(scenarioNames.contains("tmdb_movie_rail_first_paint_then_tmdb_update"))
    assertTrue(scenarioNames.contains("tmdb_tv_rail_first_paint_then_tvdb_update"))
    assertTrue(scenarioNames.contains("kitsu_rail_first_paint_then_kitsu_update"))
    assertTrue(scenarioNames.contains("hydration_failure_keeps_preview"))
    assertTrue(scenarioNames.contains("cache_hit_updates_home_without_network"))

    val tmdbMovie = report.items.single { it.scenario == "tmdb_movie_rail_first_paint_then_tmdb_update" }
    assertEquals("RAIL_PREVIEW", tmdbMovie.firstPaint?.source)
    assertEquals(false, tmdbMovie.firstPaint?.routerExecuted)
    assertEquals(false, tmdbMovie.firstPaint?.networkExecuted)
    assertEquals(false, tmdbMovie.homeUpdate?.rowOrderChanged)
    assertEquals(false, tmdbMovie.homeUpdate?.focusChanged)
    assertTrue(tmdbMovie.homeUpdate?.changedFields?.contains("rating") == true)
    assertEquals("tt0137523", tmdbMovie.stableIdBundle?.imdbId)
}
```

- [ ] **Step 2: Run audit test to verify failure**

Run:

```bash
./gradlew --no-daemon --max-workers=1 :app:testUniversalDebugUnitTest --tests com.nexio.tv.metadata.audit.MetadataExecutionAuditGoldenTest
```

Expected: FAIL because `homeUpdate` does not exist in report models or scenarios.

- [ ] **Step 3: Extend report model**

In `MetadataAuditReport.kt`, add:

```kotlin
data class HomeUpdateEvent(
    val before: Map<String, String?>,
    val after: Map<String, String?>,
    val changedFields: List<String>,
    val rowOrderChanged: Boolean,
    val focusChanged: Boolean,
    val displayHashBefore: String,
    val displayHashAfter: String
)
```

Add property to the item/scenario model:

```kotlin
val homeUpdate: HomeUpdateEvent? = null
```

- [ ] **Step 4: Add runner scenarios**

In `MetadataAuditRunner`, add synthetic home update scenarios that reuse the existing first-paint and stable-bundle fixtures. Each scenario must set:

```kotlin
homeUpdate = HomeUpdateEvent(
    before = mapOf(
        "titleSource" to "TMDB",
        "sourceRole" to "RAIL_PREVIEW"
    ),
    after = mapOf(
        "titleSource" to "TMDB",
        "sourceRole" to "PRIMARY",
        "ratingSource" to "IMDB"
    ),
    changedFields = listOf("poster", "overview", "rating"),
    rowOrderChanged = false,
    focusChanged = false,
    displayHashBefore = "preview-hash",
    displayHashAfter = "hydrated-hash"
)
```

Use these exact scenario names:

```text
addon_first_paint_then_hydrated_home_update
trakt_rail_first_paint_then_tvdb_update
tmdb_movie_rail_first_paint_then_tmdb_update
tmdb_tv_rail_first_paint_then_tvdb_update
kitsu_rail_first_paint_then_kitsu_update
simkl_rail_first_paint_then_tmdb_update
hydration_failure_keeps_preview
cache_hit_updates_home_without_network
focused_item_hydrates_before_offscreen_items
hydration_result_ignored_after_profile_switch
```

- [ ] **Step 5: Extend report writer**

In `MetadataAuditReportWriter`, add a section for home updates:

```kotlin
private fun appendHomeUpdateSection(item: MetadataAuditItem) {
    val update = item.homeUpdate ?: return
    appendLine("### Home Update")
    appendLine()
    appendLine("- Changed fields: `${update.changedFields.joinToString()}`")
    appendLine("- Row order changed: `${update.rowOrderChanged}`")
    appendLine("- Focus changed: `${update.focusChanged}`")
    appendLine("- Display hash before: `${update.displayHashBefore}`")
    appendLine("- Display hash after: `${update.displayHashAfter}`")
    appendLine()
}
```

Call it from the per-item writer after hydration/routing sections.

- [ ] **Step 6: Run audit test**

Run:

```bash
./gradlew --no-daemon --max-workers=1 :app:testUniversalDebugUnitTest --tests com.nexio.tv.metadata.audit.MetadataExecutionAuditGoldenTest
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/test/java/com/nexio/tv/metadata/audit/MetadataAuditReport.kt app/src/test/java/com/nexio/tv/metadata/audit/MetadataAuditRunner.kt app/src/test/java/com/nexio/tv/metadata/audit/MetadataAuditReportWriter.kt app/src/test/java/com/nexio/tv/metadata/audit/MetadataExecutionAuditGoldenTest.kt
git commit -m "test(metadata): report reactive home hydration"
```

---

### Task 10: Architecture Guards

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/architecture/RailPreviewLifecycleArchitectureTest.kt`
- Create: `app/src/test/java/com/nexio/tv/architecture/HomeHydrationOverlayArchitectureTest.kt`

- [ ] **Step 1: Add architecture tests**

Create `HomeHydrationOverlayArchitectureTest.kt`:

```kotlin
package com.nexio.tv.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeHydrationOverlayArchitectureTest {
    @Test
    fun `home hydration coordinator uses metadata facade and never provider services directly`() {
        val file = File("app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationCoordinator.kt")
        assertTrue(file.isFile)
        val text = file.readText()

        assertTrue(text.contains("MetadataRouterFacade"))
        val forbidden = listOf(
            "TmdbService",
            "TvdbIntegrationProvider",
            "KitsuDiscoveryService",
            "TraktDiscoveryService",
            "SimklDiscoveryService",
            "MDBListDiscoveryService"
        ).filter { text.contains(it) }
        assertTrue("HomeHydrationCoordinator must not call provider services directly: $forbidden", forbidden.isEmpty())
    }

    @Test
    fun `home card renderer does not import provider specific rail DTOs`() {
        val homeFiles = File("app/src/main/java/com/nexio/tv/ui/screens/home")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        val offenders = homeFiles.filter { file ->
            val text = file.readText()
            file.name.contains("Content") && (
                text.contains("Trakt") ||
                    text.contains("MDBList") ||
                    text.contains("Simkl") ||
                    text.contains("KitsuAnime") ||
                    text.contains("TmdbCatalog")
                )
        }
        assertTrue("Home render files must consume composed MetaPreview/HomeDisplayMetadata only: $offenders", offenders.isEmpty())
    }
}
```

- [ ] **Step 2: Run architecture test**

Run:

```bash
./gradlew --no-daemon --max-workers=1 :app:testUniversalDebugUnitTest --tests com.nexio.tv.architecture.HomeHydrationOverlayArchitectureTest --tests com.nexio.tv.architecture.RailPreviewLifecycleArchitectureTest
```

Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/nexio/tv/architecture/HomeHydrationOverlayArchitectureTest.kt app/src/test/java/com/nexio/tv/architecture/RailPreviewLifecycleArchitectureTest.kt
git commit -m "test(home): guard shared hydration overlay path"
```

---

### Task 11: Final Verification And Device Validation

**Files:**
- No source changes expected.

- [ ] **Step 1: Run OpenSpec validation**

Run:

```bash
openspec validate add-reactive-home-hydration-overlays --strict
```

Expected: PASS.

- [ ] **Step 2: Run focused unit suites**

Run:

```bash
./gradlew --no-daemon --max-workers=1 :app:testUniversalDebugUnitTest \
  --tests com.nexio.tv.domain.model.HydratedHomeOverlayTest \
  --tests com.nexio.tv.data.local.HydratedHomeOverlayStoreTest \
  --tests com.nexio.tv.core.trace.TraceMetadataEventsTest \
  --tests com.nexio.tv.ui.screens.home.HomeHydrationOverlayApplierTest \
  --tests com.nexio.tv.ui.screens.home.HomeHydrationCoordinatorTest \
  --tests com.nexio.tv.ui.screens.home.HomeReactiveHydrationPipelineTest \
  --tests com.nexio.tv.ui.screens.home.HomeViewModelFocusHydrationTest \
  --tests com.nexio.tv.ui.screens.home.HomeCatalogRefreshCoordinatorTest \
  --tests com.nexio.tv.metadata.audit.MetadataExecutionAuditGoldenTest \
  --tests com.nexio.tv.architecture.HomeHydrationOverlayArchitectureTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run first-paint and stable ID regression suites**

Run:

```bash
./gradlew --no-daemon --max-workers=1 :app:testUniversalDebugUnitTest \
  --tests com.nexio.tv.ui.screens.home.RailPreviewFirstPaintContractTest \
  --tests com.nexio.tv.domain.model.MetaPreviewFirstPaintContextTest \
  --tests com.nexio.tv.domain.model.RailItemPreviewBridgeTest \
  --tests com.nexio.tv.architecture.StableIdBundleArchitectureTest \
  --tests com.nexio.tv.core.metadata.router.StableIdBundleResolverTest \
  --tests com.nexio.tv.core.metadata.router.MetadataRouterFacadeStableIdBundleTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Build releaseProfileable**

Run:

```bash
./gradlew --no-daemon --max-workers=1 :app:assembleReleaseProfileable
```

Expected: BUILD SUCCESSFUL and APK at:

```text
app/build/outputs/apk/universal/releaseProfileable/app-universal-releaseProfileable.apk
```

- [ ] **Step 5: Install profileable build to rooted device**

Run:

```bash
adb -s 192.168.50.71 install -r app/build/outputs/apk/universal/releaseProfileable/app-universal-releaseProfileable.apk
adb -s 192.168.50.71 shell am force-stop com.nexio.tv.profileable
adb -s 192.168.50.71 logcat -c
adb -s 192.168.50.71 shell monkey -p com.nexio.tv.profileable -c android.intent.category.LAUNCHER 1
```

Expected: install succeeds and `monkey` injects one launch event.

- [ ] **Step 6: Validate logcat home hydration events**

After waiting 20 seconds and moving focus across rows with DPAD, run:

```bash
pid=$(adb -s 192.168.50.71 shell pidof com.nexio.tv.profileable | tr -d '\r')
adb -s 192.168.50.71 logcat --pid="$pid" -d -v time | grep -E 'home\\.first_paint_applied|home\\.hydration_started|home\\.hydration_overlay_written|home\\.hydration_applied|home\\.hydration_failed_using_preview|home\\.hydration_ignored|tmdb\\.external_ids|custom_imdb\\.ratings_execute|tvdb\\.remoteid\\.lookup|Unknown video ID format: kitsu'
```

Expected:

```text
home.hydration_started appears for visible/focused cards
home.hydration_overlay_written appears when cache/network returns metadata
home.hydration_applied appears with rowOrderChanged=false and focusChanged=false
tmdb.external_ids or custom_imdb.ratings_execute appears for TMDB rating enrichment when network/cache path runs
No "Unknown video ID format: kitsu"
No "tvdb.remoteid.lookup" for kitsu:* explicit anime IDs
```

- [ ] **Step 7: Run diff checks**

Run:

```bash
git diff --check
git status --short
```

Expected: no whitespace errors. Status contains only intentional files or unrelated pre-existing untracked files.

- [ ] **Step 8: Commit final verification updates if any report artifacts changed**

If metadata report golden files or generated docs were intentionally updated:

```bash
git add app/src/test/java/com/nexio/tv/metadata/audit docs openspec
git commit -m "test(home): verify reactive hydration overlays"
```

If no files changed, do not create an empty commit.
