# Artwork Routing & Invalidation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make user-chosen artwork provider settings authoritative on Modern Home, fix poster popping, self-heal overlays when stable IDs strengthen or settings change, and lay the resolver groundwork for a future fanart.tv default.

**Architecture:** A pure `ArtworkProviderResolver` maps `(artworkType, contentType, isAnime, IDs, settings)` → effective provider. `HydratedHomeOverlay` gains a `stableIdsSnapshot` + `settingsSignature` provenance pair. `HydratedHomeOverlayStore` gains `markStaleIfWeakerIds` + `markStaleAll` invalidation APIs. `CatalogItemCrossIdEnricher` pushes ID-strengthening invalidations; a new `ArtworkSettingsInvalidator` pushes settings-change invalidations. `ResolvedDisplaySurfaceRepository.applyNonDowngradeMerge` consults per-item `preferredArtworkProviders` to break same-rank ties (rejects regressions like RPDB→addon).

**Tech Stack:** Kotlin, Hilt, kotlinx.coroutines.Flow, JUnit 4, Jetpack DataStore (existing), JSON streaming reader/writer (Gson, existing).

**Spec:** `docs/superpowers/specs/2026-05-11-artwork-routing-invalidation-design.md` (`ee26f7ea4`).

---

## File Structure

| File | Status | Responsibility |
|---|---|---|
| `app/src/main/java/com/nexio/tv/domain/model/ContentType.kt` | Modify | Promote private `toMetadataMediaKind` to shared internal fn |
| `app/src/main/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapper.kt` | Modify | Drop the local private toMetadataMediaKind; compute `preferredArtworkProviders` per item via resolver |
| `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRequestNormalizer.kt` | Modify | Drop its local private toMetadataMediaKind |
| `app/src/main/java/com/nexio/tv/core/poster/PosterRatingsUrlResolver.kt` | Modify | Drop its local private toMetadataMediaKind |
| `app/src/main/java/com/nexio/tv/domain/model/ArtworkProviderSettings.kt` | Modify | Add `ArtworkProviderChoiceKey.toRuntimeProviderId()` extension; add `ArtworkProviderSettings.toSettingsSignature()` |
| `app/src/main/java/com/nexio/tv/domain/model/RailItemPreview.kt` | Modify | Add `ProviderIds.strictlyContains` extension (the data class lives in this file) |
| `app/src/main/java/com/nexio/tv/core/artwork/ArtworkProviderResolver.kt` | **Create** | Pure resolver: explicit-or-default routing |
| `app/src/main/java/com/nexio/tv/domain/model/HydratedHomeOverlay.kt` | Modify | Add `stableIdsSnapshot` + `settingsSignature` fields; update `contentEquals` |
| `app/src/main/java/com/nexio/tv/data/local/HydratedHomeOverlayStore.kt` | Modify | v2 read+write; add `markStaleIfWeakerIds` and `markStaleAll` |
| `app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationCoordinator.kt` | Modify | Stamp provenance into built overlay; bypass content-equality gate when existing is STALE_READY |
| `app/src/main/java/com/nexio/tv/domain/model/ResolvedDisplaySurfaceModels.kt` | Modify | Add `preferredArtworkProviders` to `ResolvedDisplayItem` |
| `app/src/main/java/com/nexio/tv/data/repository/ResolvedDisplaySurfaceRepository.kt` | Modify | Preferred-provider tie-breaker in `applyNonDowngradeMerge` |
| `app/src/main/java/com/nexio/tv/data/mapper/CatalogItemCrossIdEnricher.kt` | Modify | After writing new IDs for an item, push `markStaleIfWeakerIds` |
| `app/src/main/java/com/nexio/tv/data/invalidation/ArtworkSettingsInvalidator.kt` | **Create** | App-scoped settings flow observer; fires `markStaleAll` on signature change |
| `app/src/main/java/com/nexio/tv/core/di/IntegrationRuntimeModule.kt` (or NexioApplication) | Modify | Start the invalidator in app bootstrap |

Test files (created per task):
- `app/src/test/java/com/nexio/tv/domain/model/ProviderIdsStrictlyContainsTest.kt`
- `app/src/test/java/com/nexio/tv/domain/model/ArtworkProviderChoiceKeyTest.kt`
- `app/src/test/java/com/nexio/tv/domain/model/ArtworkProviderSettingsSignatureTest.kt`
- `app/src/test/java/com/nexio/tv/core/artwork/ArtworkProviderResolverTest.kt`
- `app/src/test/java/com/nexio/tv/data/local/HydratedHomeOverlayStoreInvalidationTest.kt`
- `app/src/test/java/com/nexio/tv/data/local/HydratedHomeOverlayStorePersistenceTest.kt`
- `app/src/test/java/com/nexio/tv/data/repository/ResolvedDisplaySurfaceRepositoryTieBreakerTest.kt`
- `app/src/test/java/com/nexio/tv/data/invalidation/ArtworkSettingsInvalidatorTest.kt`
- `app/src/test/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapperPreferredProvidersTest.kt`

---

## Task 1: Promote `ContentType.toMetadataMediaKind` to a shared internal fn

**Why first:** The resolver (Task 4), the mapper (Task 12), and three existing call sites all need this conversion. Today it's a private duplicated function in three places. Lift it once before any task that wants to call it from a new site.

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/domain/model/ContentType.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapper.kt:395-401`
- Modify: `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRequestNormalizer.kt:27-…` (delete the private fn)
- Modify: `app/src/main/java/com/nexio/tv/core/poster/PosterRatingsUrlResolver.kt:796-…` (delete the private fn)

- [ ] **Step 1: Read the existing private fns to confirm they're identical**

Run:
```bash
grep -A 8 "fun ContentType.toMetadataMediaKind" \
  app/src/main/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapper.kt \
  app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRequestNormalizer.kt \
  app/src/main/java/com/nexio/tv/core/poster/PosterRatingsUrlResolver.kt
```

Expected: three identical bodies mapping `MOVIE → MOVIE`, `SERIES/TV → SERIES`, default → `UNKNOWN`. If any differ, reconcile to the most permissive version (handle all ContentType cases the codebase exposes).

- [ ] **Step 2: Add the shared internal fn**

Append to `app/src/main/java/com/nexio/tv/domain/model/ContentType.kt`:

```kotlin
import com.nexio.tv.core.metadata.router.MetadataMediaKind

internal fun ContentType.toMetadataMediaKind(): MetadataMediaKind =
    when (this) {
        ContentType.MOVIE -> MetadataMediaKind.MOVIE
        ContentType.SERIES,
        ContentType.TV -> MetadataMediaKind.SERIES
        else -> MetadataMediaKind.UNKNOWN
    }
```

If `MetadataMediaKind` is currently in a module that `domain.model` can't depend on, instead place the fn in `app/src/main/java/com/nexio/tv/core/metadata/router/ContentTypeMediaKind.kt` and adjust imports in all consumers — verify with:
```bash
grep -n "package com.nexio.tv.core.metadata.router\|class MetadataMediaKind\|enum class MetadataMediaKind" app/src/main/java/com/nexio/tv/core/metadata/router/*.kt | head -3
```

- [ ] **Step 3: Delete the three private duplicates**

In each of the three files referenced above, delete the `private fun ContentType.toMetadataMediaKind()` block. Replace any in-file call site (always `expression.toMetadataMediaKind()`) — no syntactic change needed at call sites; the extension just resolves to the shared fn now.

- [ ] **Step 4: Compile**

Run:
```bash
./gradlew :app:compileUniversalDebugKotlin --max-workers=1 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/domain/model/ContentType.kt \
        app/src/main/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapper.kt \
        app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRequestNormalizer.kt \
        app/src/main/java/com/nexio/tv/core/poster/PosterRatingsUrlResolver.kt
git commit -m "$(cat <<'EOF'
refactor(model): promote ContentType.toMetadataMediaKind to shared fn

Three private duplicates collapsed into one internal fn so the
artwork resolver (next commit) and the existing call sites all share
one source. Pure refactor, no behavior change.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Add `ProviderIds.strictlyContains`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/domain/model/RailItemPreview.kt` (where `ProviderIds` is declared)
- Test: `app/src/test/java/com/nexio/tv/domain/model/ProviderIdsStrictlyContainsTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/nexio/tv/domain/model/ProviderIdsStrictlyContainsTest.kt`:

```kotlin
package com.nexio.tv.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderIdsStrictlyContainsTest {
    @Test fun `gained-only returns true`() {
        val current = ProviderIds(imdb = "tt1", tmdb = "550")
        val snapshot = ProviderIds(tmdb = "550")
        assertTrue(current.strictlyContains(snapshot))
    }

    @Test fun `identical returns false`() {
        val ids = ProviderIds(imdb = "tt1", tmdb = "550")
        assertFalse(ids.strictlyContains(ids))
    }

    @Test fun `lost-any returns false even if also gained`() {
        val current = ProviderIds(imdb = "tt1", tvdb = "999")
        val snapshot = ProviderIds(imdb = "tt1", tmdb = "550")
        assertFalse(current.strictlyContains(snapshot))
    }

    @Test fun `both empty returns false`() {
        val empty = ProviderIds()
        assertFalse(empty.strictlyContains(empty))
    }

    @Test fun `current empty snapshot non-empty returns false`() {
        val current = ProviderIds()
        val snapshot = ProviderIds(imdb = "tt1")
        assertFalse(current.strictlyContains(snapshot))
    }

    @Test fun `gained kitsu alone returns true`() {
        val current = ProviderIds(kitsu = "abc")
        val snapshot = ProviderIds()
        assertTrue(current.strictlyContains(snapshot))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :app:testUniversalDebugUnitTest --tests "*ProviderIdsStrictlyContainsTest*" --max-workers=1 2>&1 | grep -E "FAILED|compile" | head -5
```

Expected: compile failure ("Unresolved reference: strictlyContains").

- [ ] **Step 3: Add the extension**

Append to `app/src/main/java/com/nexio/tv/domain/model/RailItemPreview.kt`:

```kotlin
fun ProviderIds.strictlyContains(other: ProviderIds): Boolean {
    val gainedImdb    = imdb    != null && other.imdb    == null
    val gainedTmdb    = tmdb    != null && other.tmdb    == null
    val gainedTvdb    = tvdb    != null && other.tvdb    == null
    val gainedTrakt   = trakt   != null && other.trakt   == null
    val gainedSimkl   = simkl   != null && other.simkl   == null
    val gainedKitsu   = kitsu   != null && other.kitsu   == null
    val gainedSlug    = slug    != null && other.slug    == null
    val gainedMal     = mal     != null && other.mal     == null
    val gainedAnilist = anilist != null && other.anilist == null
    val gainedAnidb   = anidb   != null && other.anidb   == null
    val anyGain = gainedImdb || gainedTmdb || gainedTvdb || gainedTrakt ||
        gainedSimkl || gainedKitsu || gainedSlug || gainedMal ||
        gainedAnilist || gainedAnidb
    if (!anyGain) return false

    val lostImdb    = imdb    == null && other.imdb    != null
    val lostTmdb    = tmdb    == null && other.tmdb    != null
    val lostTvdb    = tvdb    == null && other.tvdb    != null
    val lostTrakt   = trakt   == null && other.trakt   != null
    val lostSimkl   = simkl   == null && other.simkl   != null
    val lostKitsu   = kitsu   == null && other.kitsu   != null
    val lostSlug    = slug    == null && other.slug    != null
    val lostMal     = mal     == null && other.mal     != null
    val lostAnilist = anilist == null && other.anilist != null
    val lostAnidb   = anidb   == null && other.anidb   != null
    val anyLoss = lostImdb || lostTmdb || lostTvdb || lostTrakt ||
        lostSimkl || lostKitsu || lostSlug || lostMal ||
        lostAnilist || lostAnidb
    return !anyLoss
}
```

- [ ] **Step 4: Run tests to verify pass**

```bash
./gradlew :app:testUniversalDebugUnitTest --tests "*ProviderIdsStrictlyContainsTest*" --max-workers=1 2>&1 | tail -3
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/domain/model/RailItemPreview.kt \
        app/src/test/java/com/nexio/tv/domain/model/ProviderIdsStrictlyContainsTest.kt
git commit -m "$(cat <<'EOF'
feat(model): add ProviderIds.strictlyContains

Returns true iff `this` carries at least one ID `other` lacked AND
`this` carries every ID `other` had. Used by HydratedHomeOverlayStore.
markStaleIfWeakerIds (next commits) to detect "current IDs are
strictly stronger than the overlay's snapshot".

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Add `ArtworkProviderChoiceKey.toRuntimeProviderId`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/domain/model/ArtworkProviderSettings.kt`
- Test: `app/src/test/java/com/nexio/tv/domain/model/ArtworkProviderChoiceKeyTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/nexio/tv/domain/model/ArtworkProviderChoiceKeyTest.kt`:

```kotlin
package com.nexio.tv.domain.model

import com.nexio.tv.core.artwork.ArtworkProviderId
import com.nexio.tv.core.integration.IntegrationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ArtworkProviderChoiceKeyTest {
    @Test fun `RPDB maps to RuntimeProvider(IntegrationProvider RPDB)`() {
        val result = ArtworkProviderChoiceKey.RPDB.toRuntimeProviderId()
        assertEquals(
            ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB),
            result
        )
    }

    @Test fun `TOP_POSTERS maps to RuntimeProvider(IntegrationProvider TOP_POSTERS)`() {
        val result = ArtworkProviderChoiceKey.TOP_POSTERS.toRuntimeProviderId()
        assertEquals(
            ArtworkProviderId.RuntimeProvider(IntegrationProvider.TOP_POSTERS),
            result
        )
    }

    @Test fun `DEFAULT throws IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) {
            ArtworkProviderChoiceKey.DEFAULT.toRuntimeProviderId()
        }
    }
}
```

- [ ] **Step 2: Run to verify failure**

```bash
./gradlew :app:testUniversalDebugUnitTest --tests "*ArtworkProviderChoiceKeyTest*" --max-workers=1 2>&1 | grep -E "Unresolved|FAILED" | head -3
```

Expected: compile failure on `toRuntimeProviderId`.

- [ ] **Step 3: Add the extension**

Append to `app/src/main/java/com/nexio/tv/domain/model/ArtworkProviderSettings.kt`:

```kotlin
import com.nexio.tv.core.artwork.ArtworkProviderId
import com.nexio.tv.core.integration.IntegrationProvider

fun ArtworkProviderChoiceKey.toRuntimeProviderId(): ArtworkProviderId =
    when (this) {
        ArtworkProviderChoiceKey.RPDB ->
            ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB)
        ArtworkProviderChoiceKey.TOP_POSTERS ->
            ArtworkProviderId.RuntimeProvider(IntegrationProvider.TOP_POSTERS)
        ArtworkProviderChoiceKey.DEFAULT ->
            throw IllegalArgumentException(
                "DEFAULT must be coerced upstream by ArtworkProviderResolver — never passed to toRuntimeProviderId"
            )
        else ->
            throw IllegalArgumentException(
                "Unknown ArtworkProviderChoiceKey: $value"
            )
    }
```

- [ ] **Step 4: Run tests**

```bash
./gradlew :app:testUniversalDebugUnitTest --tests "*ArtworkProviderChoiceKeyTest*" --max-workers=1 2>&1 | tail -3
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/domain/model/ArtworkProviderSettings.kt \
        app/src/test/java/com/nexio/tv/domain/model/ArtworkProviderChoiceKeyTest.kt
git commit -m "$(cat <<'EOF'
feat(model): add ArtworkProviderChoiceKey.toRuntimeProviderId

Maps settings-side enum (RPDB / TOP_POSTERS) to runtime provider id.
DEFAULT throws — it's a sentinel that the resolver (next task)
coerces to a content-type default before this helper is consulted.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Create `ArtworkProviderResolver`

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/artwork/ArtworkProviderResolver.kt`
- Test: `app/src/test/java/com/nexio/tv/core/artwork/ArtworkProviderResolverTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/nexio/tv/core/artwork/ArtworkProviderResolverTest.kt`:

```kotlin
package com.nexio.tv.core.artwork

import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.domain.model.ArtworkProviderChoiceKey
import com.nexio.tv.domain.model.ArtworkProviderSelectionSettings
import com.nexio.tv.domain.model.ArtworkProviderSettings
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.ProviderIds
import org.junit.Assert.assertEquals
import org.junit.Test

class ArtworkProviderResolverTest {
    private val resolver = ArtworkProviderResolver(ArtworkProviderCapabilityResolver())

    private val rpdbSettings = ArtworkProviderSettings(
        rpdbApiKey = "test-key",
        selection = ArtworkProviderSelectionSettings(
            posterProvider = ArtworkProviderChoiceKey.RPDB
        )
    )
    private val defaultSettings = ArtworkProviderSettings(
        selection = ArtworkProviderSelectionSettings()
    )

    @Test fun `explicit RPDB with imdb returns RPDB`() {
        val result = resolver.resolve(
            artworkType = ArtworkType.POSTER,
            contentType = ContentType.MOVIE,
            isAnime = false,
            availableIds = ProviderIds(imdb = "tt0137523"),
            settings = rpdbSettings
        )
        assertEquals(
            ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB),
            result
        )
    }

    @Test fun `explicit RPDB without imdb falls through to addon default`() {
        val result = resolver.resolve(
            artworkType = ArtworkType.POSTER,
            contentType = ContentType.MOVIE,
            isAnime = false,
            availableIds = ProviderIds(),
            settings = rpdbSettings
        )
        assertEquals(
            ArtworkProviderId.RuntimeProvider(IntegrationProvider.ADDON),
            result
        )
    }

    @Test fun `DEFAULT for non-anime poster returns addon`() {
        val result = resolver.resolve(
            artworkType = ArtworkType.POSTER,
            contentType = ContentType.MOVIE,
            isAnime = false,
            availableIds = ProviderIds(tmdb = "550"),
            settings = defaultSettings
        )
        assertEquals(
            ArtworkProviderId.RuntimeProvider(IntegrationProvider.ADDON),
            result
        )
    }

    @Test fun `DEFAULT for anime poster returns addon`() {
        val result = resolver.resolve(
            artworkType = ArtworkType.POSTER,
            contentType = ContentType.SERIES,
            isAnime = true,
            availableIds = ProviderIds(kitsu = "1234"),
            settings = defaultSettings
        )
        assertEquals(
            ArtworkProviderId.RuntimeProvider(IntegrationProvider.ADDON),
            result
        )
    }

    @Test fun `RPDB for anime with imdb wins over default (override rule)`() {
        val result = resolver.resolve(
            artworkType = ArtworkType.POSTER,
            contentType = ContentType.SERIES,
            isAnime = true,
            availableIds = ProviderIds(imdb = "tt1", kitsu = "1234"),
            settings = rpdbSettings
        )
        assertEquals(
            ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB),
            result
        )
    }

    @Test fun `thumbnail always returns addon`() {
        val result = resolver.resolve(
            artworkType = ArtworkType.THUMBNAIL,
            contentType = ContentType.SERIES,
            isAnime = false,
            availableIds = ProviderIds(tmdb = "550"),
            settings = defaultSettings
        )
        assertEquals(
            ArtworkProviderId.RuntimeProvider(IntegrationProvider.ADDON),
            result
        )
    }
}
```

- [ ] **Step 2: Run to verify compile failure**

```bash
./gradlew :app:testUniversalDebugUnitTest --tests "*ArtworkProviderResolverTest*" --max-workers=1 2>&1 | grep -E "Unresolved|FAILED" | head -3
```

Expected: `Unresolved reference: ArtworkProviderResolver`.

- [ ] **Step 3: Create the resolver**

Create `app/src/main/java/com/nexio/tv/core/artwork/ArtworkProviderResolver.kt`:

```kotlin
package com.nexio.tv.core.artwork

import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.domain.model.ArtworkProviderChoiceKey
import com.nexio.tv.domain.model.ArtworkProviderSettings
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.toMetadataMediaKind
import com.nexio.tv.domain.model.toRuntimeProviderId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ArtworkProviderResolver @Inject constructor(
    private val capabilityResolver: ArtworkProviderCapabilityResolver
) {
    fun resolve(
        artworkType: ArtworkType,
        contentType: ContentType,
        isAnime: Boolean,
        availableIds: ProviderIds,
        settings: ArtworkProviderSettings
    ): ArtworkProviderId {
        val explicit = settings.selection.providerFor(artworkType.toSettingsKey())
        if (explicit != ArtworkProviderChoiceKey.DEFAULT) {
            val provider = explicit.toRuntimeProviderId()
            val capable = capabilityResolver.evaluate(
                provider = provider,
                imageType = artworkType,
                ids = availableIds,
                mediaKind = contentType.toMetadataMediaKind(),
                settings = settings
            )
            if (capable.supported) return provider
        }
        return ContentTypeDefaults.resolve(artworkType, isAnime)
    }
}

internal object ContentTypeDefaults {
    private val addonProvider =
        ArtworkProviderId.RuntimeProvider(IntegrationProvider.ADDON)

    fun resolve(artworkType: ArtworkType, isAnime: Boolean): ArtworkProviderId =
        when (artworkType) {
            ArtworkType.POSTER,
            ArtworkType.BACKDROP,
            ArtworkType.LOGO ->
                if (isAnime) addonProvider else addonProvider
                //  ↑ fanart.tv lands → else fanartProvider
            ArtworkType.THUMBNAIL -> addonProvider
        }
}

const val DEFAULTS_TABLE_VERSION = 1
```

- [ ] **Step 4: Run tests**

```bash
./gradlew :app:testUniversalDebugUnitTest --tests "*ArtworkProviderResolverTest*" --max-workers=1 2>&1 | tail -3
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/artwork/ArtworkProviderResolver.kt \
        app/src/test/java/com/nexio/tv/core/artwork/ArtworkProviderResolverTest.kt
git commit -m "$(cat <<'EOF'
feat(artwork): ArtworkProviderResolver — DEFAULT-sentinel + content-type table

Pure resolver: explicit RPDB/TOP_POSTERS wins when capable, else
ContentTypeDefaults table picks addon (today) for all combinations.
When fanart.tv lands, flip the non-anime branch + bump
DEFAULTS_TABLE_VERSION for automatic mass invalidation via the
settings signature.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Add `ArtworkProviderSettings.toSettingsSignature`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/domain/model/ArtworkProviderSettings.kt`
- Test: `app/src/test/java/com/nexio/tv/domain/model/ArtworkProviderSettingsSignatureTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/nexio/tv/domain/model/ArtworkProviderSettingsSignatureTest.kt`:

```kotlin
package com.nexio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ArtworkProviderSettingsSignatureTest {
    @Test fun `identical settings produce identical signatures`() {
        val a = ArtworkProviderSettings(
            selection = ArtworkProviderSelectionSettings(
                posterProvider = ArtworkProviderChoiceKey.RPDB
            )
        )
        val b = ArtworkProviderSettings(
            selection = ArtworkProviderSelectionSettings(
                posterProvider = ArtworkProviderChoiceKey.RPDB
            )
        )
        assertEquals(a.toSettingsSignature(), b.toSettingsSignature())
    }

    @Test fun `different poster providers produce different signatures`() {
        val rpdb = ArtworkProviderSettings(
            selection = ArtworkProviderSelectionSettings(
                posterProvider = ArtworkProviderChoiceKey.RPDB
            )
        )
        val default = ArtworkProviderSettings(
            selection = ArtworkProviderSelectionSettings(
                posterProvider = ArtworkProviderChoiceKey.DEFAULT
            )
        )
        assertNotEquals(rpdb.toSettingsSignature(), default.toSettingsSignature())
    }

    @Test fun `signature includes defaults table version`() {
        val signature = ArtworkProviderSettings().toSettingsSignature()
        assertEquals(true, signature.contains("v="))
    }

    @Test fun `api keys do NOT affect signature (irrelevant to routing)`() {
        val a = ArtworkProviderSettings(rpdbApiKey = "key-a")
        val b = ArtworkProviderSettings(rpdbApiKey = "key-b")
        assertEquals(a.toSettingsSignature(), b.toSettingsSignature())
    }
}
```

- [ ] **Step 2: Run to verify failure**

```bash
./gradlew :app:testUniversalDebugUnitTest --tests "*ArtworkProviderSettingsSignatureTest*" --max-workers=1 2>&1 | grep -E "Unresolved|FAILED" | head -3
```

- [ ] **Step 3: Add the extension**

Append to `app/src/main/java/com/nexio/tv/domain/model/ArtworkProviderSettings.kt`:

```kotlin
import com.nexio.tv.core.artwork.DEFAULTS_TABLE_VERSION

fun ArtworkProviderSettings.toSettingsSignature(): String =
    "p=${selection.posterProvider.value};" +
    "l=${selection.logoProvider.value};" +
    "b=${selection.backdropProvider.value};" +
    "t=${selection.thumbnailProvider.value};" +
    "v=$DEFAULTS_TABLE_VERSION"
```

- [ ] **Step 4: Run tests**

```bash
./gradlew :app:testUniversalDebugUnitTest --tests "*ArtworkProviderSettingsSignatureTest*" --max-workers=1 2>&1 | tail -3
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/domain/model/ArtworkProviderSettings.kt \
        app/src/test/java/com/nexio/tv/domain/model/ArtworkProviderSettingsSignatureTest.kt
git commit -m "$(cat <<'EOF'
feat(model): ArtworkProviderSettings.toSettingsSignature

Stable hash of the four provider-choice fields plus
DEFAULTS_TABLE_VERSION. Used by the settings invalidator (later task)
to detect material changes. API keys are intentionally excluded —
they don't affect provider routing.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Extend `HydratedHomeOverlay` with provenance fields

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/domain/model/HydratedHomeOverlay.kt`
- Modify: callers that construct overlays via field-by-field — currently only `HomeHydrationCoordinator.toHydratedHomeOverlay` and any tests. The defaults make this additive.

- [ ] **Step 1: Add fields with defaults + update `contentEquals`**

In `app/src/main/java/com/nexio/tv/domain/model/HydratedHomeOverlay.kt`:

Add to the `HydratedHomeOverlay` data class (after `state`):

```kotlin
val stableIdsSnapshot: ProviderIds = ProviderIds(),
val settingsSignature: String = ""
```

Append to `contentEquals` (returns Boolean) the two new conjunctions:

```kotlin
        // existing conjunctions …
        state == other.state &&
        stableIdsSnapshot == other.stableIdsSnapshot &&
        settingsSignature == other.settingsSignature
```

- [ ] **Step 2: Compile**

```bash
./gradlew :app:compileUniversalDebugKotlin --max-workers=1 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL` (additive defaults; no caller changes needed).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nexio/tv/domain/model/HydratedHomeOverlay.kt
git commit -m "$(cat <<'EOF'
feat(model): add stableIdsSnapshot + settingsSignature to overlay

Provenance fields stamped at hydration time. Used by the
HydratedHomeOverlayStore invalidation API (next task) to detect:
  - "current MetaPreview.firstPaintStableIds strictly contains
    overlay.stableIdsSnapshot" → mark stale
  - "overlay.settingsSignature != current signature" → mark stale

Fields default to empty so v1-shape callers compile unchanged; the
persistence read path (later task) similarly defaults missing fields
to empty so v1 on-disk records load fine.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: `HydratedHomeOverlayStore` — invalidation API + persistence v2

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/local/HydratedHomeOverlayStore.kt`
- Test: `app/src/test/java/com/nexio/tv/data/local/HydratedHomeOverlayStoreInvalidationTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/nexio/tv/data/local/HydratedHomeOverlayStoreInvalidationTest.kt` (uses the existing test fixtures pattern — copy the setUp from `HomeCatalogSnapshotStoreTest.kt` for a temp-file profile-rooted store):

```kotlin
package com.nexio.tv.data.local

import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.HomeItemHydrationState
import com.nexio.tv.domain.model.HydratedHomeOverlay
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.hydratedHomeDisplayHash
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class HydratedHomeOverlayStoreInvalidationTest {
    private fun newStore(): HydratedHomeOverlayStore {
        val tempDir = Files.createTempDirectory("overlay-store").toFile()
        // TODO_AGENT — reuse the constructor wiring HomeCatalogSnapshotStoreTest uses
        //              (file-backed, profile-aware). The plan author left the exact
        //              factory call as a copy-from-neighbor task: open
        //              HomeCatalogSnapshotStoreTest.kt and mirror its `private fun store()`.
        return HydratedHomeOverlayStore.testFactory(tempDir, activeProfileId = 1)
    }

    private fun overlayWith(
        itemKey: String,
        stableIdsSnapshot: ProviderIds,
        settingsSignature: String = "p=default;l=default;b=default;t=default;v=1",
        state: HomeItemHydrationState = HomeItemHydrationState.CANONICAL_READY
    ): HydratedHomeOverlay {
        val fields = HomeDisplayMetadata(title = "test", poster = "p")
        return HydratedHomeOverlay(
            overlayKey = "canonical:TMDB:550:type:MOVIE:lang:en:policy:1",
            itemKey = itemKey,
            canonicalProvider = ProviderId.TMDB,
            canonicalId = "550",
            imdbId = stableIdsSnapshot.imdb,
            contentType = ContentType.MOVIE,
            languageTag = "en",
            policyVersion = 1,
            fields = fields,
            fieldTrace = emptyList(),
            displayHash = fields.hydratedHomeDisplayHash(),
            updatedAtMs = 1L,
            staleAtMs = 2L,
            expiresAtMs = 3L,
            state = state,
            stableIdsSnapshot = stableIdsSnapshot,
            settingsSignature = settingsSignature
        )
    }

    @Test fun `markStaleIfWeakerIds transitions state when current strictly contains snapshot`() = runTest {
        val store = newStore()
        store.upsert(overlayWith("movie:550", ProviderIds(tmdb = "550")), aliases = setOf("movie:550"))

        store.markStaleIfWeakerIds("movie:550", ProviderIds(tmdb = "550", imdb = "tt1"))

        val after = store.snapshotInMemory()["movie:550"]!!
        assertEquals(HomeItemHydrationState.STALE_READY, after.state)
    }

    @Test fun `markStaleIfWeakerIds is a no-op when current does not strictly contain snapshot`() = runTest {
        val store = newStore()
        store.upsert(overlayWith("movie:550", ProviderIds(tmdb = "550", imdb = "tt1")), aliases = setOf("movie:550"))

        store.markStaleIfWeakerIds("movie:550", ProviderIds(tmdb = "550"))  // lost imdb

        val after = store.snapshotInMemory()["movie:550"]!!
        assertEquals(HomeItemHydrationState.CANONICAL_READY, after.state)
    }

    @Test fun `markStaleAll transitions every overlay`() = runTest {
        val store = newStore()
        store.upsert(overlayWith("movie:1", ProviderIds(tmdb = "1")), aliases = setOf("movie:1"))
        store.upsert(overlayWith("movie:2", ProviderIds(tmdb = "2")), aliases = setOf("movie:2"))

        store.markStaleAll("settings_change")

        val after = store.snapshotInMemory()
        assertEquals(HomeItemHydrationState.STALE_READY, after["movie:1"]!!.state)
        assertEquals(HomeItemHydrationState.STALE_READY, after["movie:2"]!!.state)
    }

    @Test fun `markStale does NOT persist state change to disk`() = runTest {
        val store = newStore()
        store.upsert(overlayWith("movie:550", ProviderIds(tmdb = "550")), aliases = setOf("movie:550"))
        store.markStaleAll("settings_change")

        // Disk image still has CANONICAL_READY; in-memory mutation only.
        val onDisk = store.readByCanonicalIdentity(
            canonicalProvider = ProviderId.TMDB,
            canonicalId = "550",
            contentType = ContentType.MOVIE,
            languageTag = "en",
            policyVersion = 1,
            // bypass in-memory short-circuit
            forceDiskRead = true
        )
        assertNotEquals(HomeItemHydrationState.STALE_READY, onDisk?.state)
    }
}
```

NOTE — this test file uses 3 helpers that don't exist yet (`testFactory`, `snapshotInMemory()`, and a `forceDiskRead` overload on `readByCanonicalIdentity`). Add them only if the existing store's public surface doesn't already give equivalent access; otherwise inline the equivalent calls.

```bash
grep -n "fun readByCanonicalIdentity\|class HydratedHomeOverlayStore\|fun snapshotInMemory\|companion object" app/src/main/java/com/nexio/tv/data/local/HydratedHomeOverlayStore.kt | head -10
```

If the store already exposes the overlay map via `observeForItemKeys` or similar, prefer reading from that.

- [ ] **Step 2: Run to verify compile failure**

```bash
./gradlew :app:testUniversalDebugUnitTest --tests "*HydratedHomeOverlayStoreInvalidationTest*" --max-workers=1 2>&1 | grep -E "Unresolved|FAILED" | head -5
```

- [ ] **Step 3: Read the existing store to find the in-memory map**

```bash
sed -n '1,80p' app/src/main/java/com/nexio/tv/data/local/HydratedHomeOverlayStore.kt
```

Look for the in-memory `MutableStateFlow<Map<…>>` or similar. The new `markStale*` APIs mutate it.

- [ ] **Step 4: Implement `markStaleIfWeakerIds` and `markStaleAll`**

Add to `HydratedHomeOverlayStore` class body:

```kotlin
@Synchronized
fun markStaleIfWeakerIds(itemKey: String, currentIds: ProviderIds) {
    val overlay = inMemoryByItemKey().value[itemKey] ?: return
    if (overlay.state == HomeItemHydrationState.STALE_READY) return
    if (!currentIds.strictlyContains(overlay.stableIdsSnapshot)) return
    val staled = overlay.copy(state = HomeItemHydrationState.STALE_READY)
    inMemoryByItemKey().update { previous ->
        previous + (itemKey to staled)
    }
    traceEvents.emitOverlayStaleMarked(
        itemKey = itemKey,
        reason = "cross_id_enriched",
        oldState = overlay.state.name
    )
}

@Synchronized
fun markStaleAll(reason: String) {
    inMemoryByItemKey().update { previous ->
        val next = HashMap<String, HydratedHomeOverlay>(previous.size)
        for ((key, overlay) in previous) {
            if (overlay.state == HomeItemHydrationState.STALE_READY) {
                next[key] = overlay
            } else {
                next[key] = overlay.copy(state = HomeItemHydrationState.STALE_READY)
                traceEvents.emitOverlayStaleMarked(
                    itemKey = key,
                    reason = reason,
                    oldState = overlay.state.name
                )
            }
        }
        next
    }
}
```

Both functions are in-memory ONLY — no disk write. The names `inMemoryByItemKey()` and `traceEvents.emitOverlayStaleMarked` may not exist verbatim:
- `inMemoryByItemKey()`: replace with the actual MutableStateFlow accessor in the store (e.g., `hydratedHomeOverlaysByItemKey`).
- `traceEvents.emitOverlayStaleMarked`: stub call now; Task 14 (telemetry) wires the trace event sink.

If `traceEvents` isn't injected into the store today, leave the trace calls out and surface them via a hook the coordinator wires up — keep the invalidation mutation as the only authoritative behavior for this task.

- [ ] **Step 5: Add v2 read+write to existing streaming JSON**

```bash
grep -n "fun streamReadOverlay\|fun streamWriteOverlay\|JsonReader\|JsonWriter\|stableIdsSnapshot\|settingsSignature" app/src/main/java/com/nexio/tv/data/local/HydratedHomeOverlayStore.kt | head -20
```

In the reader's `when (reader.nextName())` block, add:

```kotlin
"stableIdsSnapshot" -> {
    stableIdsSnapshot = gson.fromJson<ProviderIds>(reader, providerIdsType)
        ?: ProviderIds()
}
"settingsSignature" -> {
    settingsSignature = reader.nextString().orEmpty()
}
```

Declare `stableIdsSnapshot` / `settingsSignature` locals at the top of the read fn with empty defaults, and add them to the `HydratedHomeOverlay(...)` constructor at the end:

```kotlin
return HydratedHomeOverlay(
    // … existing args
    state = state,
    stableIdsSnapshot = stableIdsSnapshot,
    settingsSignature = settingsSignature
)
```

In the writer's `JsonWriter` block, add (after `writer.name("state").value(state.name)` or equivalent):

```kotlin
writer.name("stableIdsSnapshot")
gson.toJson(overlay.stableIdsSnapshot, providerIdsType, writer)
writer.name("settingsSignature").value(overlay.settingsSignature)
```

Add the `private val providerIdsType: Type = object : TypeToken<ProviderIds>() {}.type` declaration alongside the existing type tokens.

- [ ] **Step 6: Run tests**

```bash
./gradlew :app:testUniversalDebugUnitTest --tests "*HydratedHomeOverlayStoreInvalidationTest*" --max-workers=1 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/local/HydratedHomeOverlayStore.kt \
        app/src/test/java/com/nexio/tv/data/local/HydratedHomeOverlayStoreInvalidationTest.kt
git commit -m "$(cat <<'EOF'
feat(overlay-store): markStaleIfWeakerIds, markStaleAll + v2 persistence

In-memory invalidation API: transitions overlay.state to STALE_READY
when current IDs strictly contain the overlay's stableIdsSnapshot
(markStaleIfWeakerIds) or unconditionally for the whole store
(markStaleAll, used by the settings invalidator).

State transitions are NOT persisted — on cold-start, overlays load
with their persisted state; invalidators (cross-id enricher + settings
observer) re-fire and re-mark if conditions still apply. Keeps disk
schema small and avoids state drift across app launches.

Persistence schema v2: stableIdsSnapshot + settingsSignature persisted
in the streaming JSON. v1 records load with empty defaults (self-
healing migration via first invalidator emission).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: `HydratedHomeOverlayStore` persistence v2 round-trip test

**Files:**
- Test: `app/src/test/java/com/nexio/tv/data/local/HydratedHomeOverlayStorePersistenceTest.kt`

- [ ] **Step 1: Write the test**

```kotlin
package com.nexio.tv.data.local

import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.HomeItemHydrationState
import com.nexio.tv.domain.model.HydratedHomeOverlay
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.hydratedHomeDisplayHash
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class HydratedHomeOverlayStorePersistenceTest {
    @Test fun `v2 round-trip preserves stableIdsSnapshot and settingsSignature`() = runTest {
        val tempDir = Files.createTempDirectory("overlay-store-v2").toFile()
        val store = HydratedHomeOverlayStore.testFactory(tempDir, activeProfileId = 1)
        val overlay = sampleOverlay(
            stableIdsSnapshot = ProviderIds(tmdb = "550", imdb = "tt1"),
            settingsSignature = "p=rpdb;l=default;b=default;t=default;v=1"
        )
        store.upsert(overlay, aliases = setOf(overlay.itemKey))

        // New store instance reading the same dir == cold-start
        val reloaded = HydratedHomeOverlayStore.testFactory(tempDir, activeProfileId = 1)
        val out = reloaded.readByCanonicalIdentity(
            canonicalProvider = overlay.canonicalProvider,
            canonicalId = overlay.canonicalId,
            contentType = overlay.contentType,
            languageTag = overlay.languageTag,
            policyVersion = overlay.policyVersion
        )

        assertEquals(overlay.stableIdsSnapshot, out!!.stableIdsSnapshot)
        assertEquals(overlay.settingsSignature, out.settingsSignature)
    }

    @Test fun `v1 record (missing new fields) loads with empty defaults`() = runTest {
        // Hand-craft a v1-shape JSON file by writing it directly to the temp dir,
        // then point a fresh store at that dir. Layout follows the store's
        // existing JSON shape (look at streamWriteOverlay).
        // Alternative: persist via the production writer, then overwrite the
        // file removing the two new keys with a manual JSON edit.
        // ⚠ The plan author left the exact path layout as a "follow the store's
        // existing layout" task — inspect HydratedHomeOverlayStore to see whether
        // it's one file per overlayKey or a single roll-up. Mirror that.
        TODO("inspect store layout and craft a v1 fixture; assert empty defaults on read")
    }

    private fun sampleOverlay(
        stableIdsSnapshot: ProviderIds,
        settingsSignature: String
    ): HydratedHomeOverlay {
        val fields = HomeDisplayMetadata(title = "test", poster = "p")
        return HydratedHomeOverlay(
            overlayKey = "canonical:TMDB:550:type:MOVIE:lang:en:policy:1",
            itemKey = "movie:550",
            canonicalProvider = ProviderId.TMDB,
            canonicalId = "550",
            imdbId = stableIdsSnapshot.imdb,
            contentType = ContentType.MOVIE,
            languageTag = "en",
            policyVersion = 1,
            fields = fields,
            fieldTrace = emptyList(),
            displayHash = fields.hydratedHomeDisplayHash(),
            updatedAtMs = 1L,
            staleAtMs = 2L,
            expiresAtMs = 3L,
            state = HomeItemHydrationState.CANONICAL_READY,
            stableIdsSnapshot = stableIdsSnapshot,
            settingsSignature = settingsSignature
        )
    }
}
```

- [ ] **Step 2: Implement the v1-fixture test body**

The plan author intentionally left the second test as a `TODO` so the implementing engineer inspects the store's actual file layout before crafting the fixture. Read:

```bash
grep -n "fun streamWriteOverlay\|fun overlayFileFor\|File(parent\|filesDir" app/src/main/java/com/nexio/tv/data/local/HydratedHomeOverlayStore.kt | head -10
```

Then either (a) write a v1 JSON file by hand (omitting `stableIdsSnapshot` and `settingsSignature` keys), or (b) round-trip a current overlay through the writer, then string-replace those keys out of the file before reading back. Approach (b) is more brittle to writer-format changes; approach (a) is more durable. Pick (a).

- [ ] **Step 3: Run tests**

```bash
./gradlew :app:testUniversalDebugUnitTest --tests "*HydratedHomeOverlayStorePersistenceTest*" --max-workers=1 2>&1 | tail -5
```

- [ ] **Step 4: Commit**

```bash
git add app/src/test/java/com/nexio/tv/data/local/HydratedHomeOverlayStorePersistenceTest.kt
git commit -m "$(cat <<'EOF'
test(overlay-store): v2 persistence round-trip + v1 self-heal fixture

Confirms stableIdsSnapshot + settingsSignature round-trip through the
streaming JSON. Also confirms v1 records (missing new keys) read with
empty defaults so the first invalidator emission marks them stale and
re-hydrates them naturally.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 9: `HomeHydrationCoordinator` stamps provenance + bypasses gate when STALE_READY

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationCoordinator.kt`
- Inject `ArtworkProviderSettingsSource` (`val settings: Flow<ArtworkProviderSettings>` — existing interface in `ArtworkCredentialResolver.kt:19`).

- [ ] **Step 1: Inject the settings source**

Read the constructor:
```bash
sed -n '46,55p' app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationCoordinator.kt
```

Add the new dependency:

```kotlin
@Singleton
class HomeHydrationCoordinator @Inject constructor(
    private val metadataRouterFacade: MetadataRouterFacade,
    private val overlayStore: HydratedHomeOverlayStore,
    private val traceEvents: TraceMetadataEvents,
    private val settingsSource: ArtworkProviderSettingsSource    // ← NEW
) { … }
```

- [ ] **Step 2: Stamp provenance into built overlay**

Locate `MetadataResolutionResult.toHydratedHomeOverlay` (around line 233):

```bash
grep -n "fun MetadataResolutionResult.toHydratedHomeOverlay\|return HydratedHomeOverlay(" app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationCoordinator.kt
```

Capture the current settings + IDs at hydration time. In `hydrate(...)`:

```kotlin
// near where the request is built
val currentSettings = settingsSource.settings.first()    // requires kotlinx.coroutines.flow.first
val settingsSignature = currentSettings.toSettingsSignature()
val stableIdsSnapshot = bundle?.canonicalAsProviderIds()?.merge(item.firstPaintStableIds)
    ?: item.firstPaintStableIds
```

Then thread `stableIdsSnapshot` and `settingsSignature` into the `HydratedHomeOverlay(...)` constructor at the return site:

```kotlin
return HydratedHomeOverlay(
    // … existing args
    state = HomeItemHydrationState.CANONICAL_READY,
    stableIdsSnapshot = stableIdsSnapshot,
    settingsSignature = settingsSignature
)
```

If `StableIdBundle.canonicalAsProviderIds(): ProviderIds` doesn't exist:

```bash
grep -n "fun StableIdBundle\|canonicalAsProviderIds" app/src/main/java/com/nexio/tv/core/metadata/router/ | head -5
```

Inline an equivalent construction at the call site (don't add a new helper for one call site):

```kotlin
val bundleIds = bundle?.let { b ->
    ProviderIds(
        imdb = b.sidecars.imdbId ?: item.firstPaintStableIds.imdb,
        tmdb = b.canonical.tmdbMovieId ?: item.firstPaintStableIds.tmdb,
        tvdb = b.canonical.tvdbSeriesId ?: item.firstPaintStableIds.tvdb,
        kitsu = b.canonical.kitsuAnimeId ?: item.firstPaintStableIds.kitsu,
        trakt = item.firstPaintStableIds.trakt,
        simkl = item.firstPaintStableIds.simkl
    )
} ?: item.firstPaintStableIds
```

- [ ] **Step 3: Bypass content-equality gate when existing is STALE_READY**

Locate lines 125–142:

```bash
sed -n '120,145p' app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationCoordinator.kt
```

The current gate skips upsert if `existing.displayHash == overlay.displayHash && existing.fields == overlay.fields`. Add a state check:

```kotlin
if (
    existingOverlay != null &&
    existingOverlay.state != HomeItemHydrationState.STALE_READY &&   // ← NEW
    existingOverlay.displayHash == overlay.displayHash &&
    existingOverlay.fields == overlay.fields
) {
    traceEvents.emitHomeHydrationIgnored(
        itemKey = itemKey,
        reason = "overlay_content_unchanged",
        trigger = trigger.name
    )
    return existingOverlay
}
```

- [ ] **Step 4: Compile**

```bash
./gradlew :app:compileUniversalDebugKotlin --max-workers=1 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL` (after adjusting Hilt module if needed — Hilt should auto-resolve `ArtworkProviderSettingsSource` since `PosterRatingsArtworkProviderSettingsSource` is `@Inject`-annotated).

- [ ] **Step 5: Add a unit test for the STALE_READY gate bypass**

`app/src/test/java/com/nexio/tv/ui/screens/home/HomeHydrationCoordinatorGateTest.kt` — assert that when the existing overlay has `state = STALE_READY`, the coordinator does NOT short-circuit on content-equality. Mock the overlay store; assert `overlayStore.upsert(...)` IS called even when displayHash matches.

(The implementing engineer's TDD step: write the failing test before Step 3's gate change. If you've already made the change, write the test now and confirm it passes.)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationCoordinator.kt \
        app/src/test/java/com/nexio/tv/ui/screens/home/HomeHydrationCoordinatorGateTest.kt
git commit -m "$(cat <<'EOF'
feat(home/hydration): stamp provenance + bypass content-equality on STALE_READY

Two behavior changes in HomeHydrationCoordinator:

1. Every built overlay now carries stableIdsSnapshot (IDs visible at
   hydration time) + settingsSignature (artwork provider choices +
   defaults table version). The invalidation pipeline uses these to
   detect "stale by content".

2. The content-equality gate at the upsert path no longer short-
   circuits when the existing overlay is STALE_READY. Without this
   bypass, an overlay marked stale by the invalidator could be
   "refreshed" with identical fields and the upsert would be skipped
   — keeping it stuck in STALE_READY forever even when the underlying
   data is correct.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 10: Add `preferredArtworkProviders` to `ResolvedDisplayItem`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/domain/model/ResolvedDisplaySurfaceModels.kt`

- [ ] **Step 1: Add the field with default**

In `data class ResolvedDisplayItem(...)`, add:

```kotlin
import com.nexio.tv.core.artwork.ArtworkProviderId
import com.nexio.tv.core.artwork.ArtworkType

data class ResolvedDisplayItem(
    // … existing fields unchanged
    val slots: ResolvedDisplayFieldSlots? = null,
    val preferredArtworkProviders: Map<ArtworkType, ArtworkProviderId> = emptyMap()
)
```

- [ ] **Step 2: Compile**

```bash
./gradlew :app:compileUniversalDebugKotlin --max-workers=1 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL` (additive default; no callers break).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nexio/tv/domain/model/ResolvedDisplaySurfaceModels.kt
git commit -m "$(cat <<'EOF'
feat(model): add ResolvedDisplayItem.preferredArtworkProviders

Per-item map from ArtworkType → preferred ArtworkProviderId, computed
once per item at projection time by HomeResolvedDisplayMapper via
ArtworkProviderResolver. Consumed by ResolvedDisplaySurfaceRepository
.applyNonDowngradeMerge for same-rank artwork slot tie-breaking.

Default empty so all existing callers compile unchanged; mapper +
surface-merge changes land in following tasks.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 11: `HomeResolvedDisplayMapper` computes `preferredArtworkProviders`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapper.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapperPreferredProvidersTest.kt`

- [ ] **Step 1: Inject the resolver + settings source into the mapper**

Today the mapper is an `object` (functions are top-level or static-ish). Check:

```bash
grep -n "object HomeResolvedDisplayMapper\|class HomeResolvedDisplayMapper" app/src/main/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapper.kt | head -3
```

If it's an `object`, convert to `@Singleton class … @Inject constructor(...)` and update its 3-5 call sites to inject. The biggest call site is `HomeViewModelCatalogPipeline.kt`. Search:

```bash
grep -rn "HomeResolvedDisplayMapper\." app/src/main/java/com/nexio/tv --include="*.kt" | head -10
```

For each call site (likely 5-10), change from `HomeResolvedDisplayMapper.toResolvedDisplayItems(...)` to using an injected `private val mapper: HomeResolvedDisplayMapper` field. If a call site has no clean injection path, accept the mapper as a function parameter passed from the caller.

If the change is too invasive, an acceptable alternative: keep mapper as `object`, change the projection methods to take `resolver: ArtworkProviderResolver` + `currentSettings: ArtworkProviderSettings` as parameters. Caller (`HomeViewModelCatalogPipeline`) reads `settingsSource.settings.value` (or `.first()`) at call time.

Prefer the second alternative — smaller blast radius. Document the choice in the commit.

- [ ] **Step 2: Compute the map in `toResolvedDisplayItem(item: MetaPreview, …)`**

Add to the projection (inside whichever overload computes the per-item ResolvedDisplayItem):

```kotlin
val isAnime = item.apiType.equals("anime", ignoreCase = true) ||
              item.firstPaintRailSource == RailSource.KITSU
val preferred = mapOf(
    ArtworkType.POSTER    to resolver.resolve(ArtworkType.POSTER, item.type, isAnime, item.firstPaintStableIds, currentSettings),
    ArtworkType.BACKDROP  to resolver.resolve(ArtworkType.BACKDROP, item.type, isAnime, item.firstPaintStableIds, currentSettings),
    ArtworkType.LOGO      to resolver.resolve(ArtworkType.LOGO, item.type, isAnime, item.firstPaintStableIds, currentSettings),
    ArtworkType.THUMBNAIL to resolver.resolve(ArtworkType.THUMBNAIL, item.type, isAnime, item.firstPaintStableIds, currentSettings)
)
return ResolvedDisplayItem(
    // … existing args
    preferredArtworkProviders = preferred
)
```

Verify `RailSource.KITSU` is the correct enum case:

```bash
grep -n "KITSU\|enum class RailSource" app/src/main/java/com/nexio/tv/domain/model/RailItemPreview.kt
```

- [ ] **Step 3: Write the test**

`app/src/test/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapperPreferredProvidersTest.kt`:

```kotlin
package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.artwork.ArtworkProviderCapabilityResolver
import com.nexio.tv.core.artwork.ArtworkProviderId
import com.nexio.tv.core.artwork.ArtworkProviderResolver
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.domain.model.ArtworkProviderChoiceKey
import com.nexio.tv.domain.model.ArtworkProviderSelectionSettings
import com.nexio.tv.domain.model.ArtworkProviderSettings
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.RailSource
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeResolvedDisplayMapperPreferredProvidersTest {
    private val resolver = ArtworkProviderResolver(ArtworkProviderCapabilityResolver())
    private val addon = ArtworkProviderId.RuntimeProvider(IntegrationProvider.ADDON)
    private val rpdb  = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB)

    private fun meta(
        apiType: String,
        railSource: RailSource? = null,
        ids: ProviderIds = ProviderIds()
    ) = MetaPreview(
        id = "x",
        type = ContentType.MOVIE,
        rawType = apiType,
        name = "x",
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList(),
        firstPaintRailSource = railSource,
        firstPaintStableIds = ids
    )

    @Test fun `DEFAULT for non-anime movie returns addon for all four types`() {
        val item = meta(apiType = "movie")
        val settings = ArtworkProviderSettings(
            selection = ArtworkProviderSelectionSettings()
        )
        val out = HomeResolvedDisplayMapper.toResolvedDisplayItem(
            item = item,
            // … other args as required by the actual API; this test compiles once
            // the mapper signature change in Step 1 is in place
            overlay = null,
            resolver = resolver,
            currentSettings = settings
        )
        assertEquals(addon, out.preferredArtworkProviders[ArtworkType.POSTER])
        assertEquals(addon, out.preferredArtworkProviders[ArtworkType.BACKDROP])
        assertEquals(addon, out.preferredArtworkProviders[ArtworkType.LOGO])
        assertEquals(addon, out.preferredArtworkProviders[ArtworkType.THUMBNAIL])
    }

    @Test fun `RPDB selected for movie with imdb prefers RPDB for poster`() {
        val item = meta(apiType = "movie", ids = ProviderIds(imdb = "tt1"))
        val settings = ArtworkProviderSettings(
            rpdbApiKey = "key",
            selection = ArtworkProviderSelectionSettings(
                posterProvider = ArtworkProviderChoiceKey.RPDB
            )
        )
        val out = HomeResolvedDisplayMapper.toResolvedDisplayItem(
            item = item,
            overlay = null,
            resolver = resolver,
            currentSettings = settings
        )
        assertEquals(rpdb,  out.preferredArtworkProviders[ArtworkType.POSTER])
        assertEquals(addon, out.preferredArtworkProviders[ArtworkType.BACKDROP])
    }

    @Test fun `anime detected via KITSU railSource picks addon defaults`() {
        val item = meta(apiType = "series", railSource = RailSource.KITSU)
        val settings = ArtworkProviderSettings()
        val out = HomeResolvedDisplayMapper.toResolvedDisplayItem(
            item = item,
            overlay = null,
            resolver = resolver,
            currentSettings = settings
        )
        assertEquals(addon, out.preferredArtworkProviders[ArtworkType.POSTER])
    }
}
```

- [ ] **Step 4: Run tests + compile**

```bash
./gradlew :app:compileUniversalDebugKotlin --max-workers=1 2>&1 | tail -5
./gradlew :app:testUniversalDebugUnitTest --tests "*HomeResolvedDisplayMapperPreferredProvidersTest*" --max-workers=1 2>&1 | tail -5
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapper.kt \
        app/src/test/java/com/nexio/tv/ui/screens/home/HomeResolvedDisplayMapperPreferredProvidersTest.kt \
        $(git status -s | awk '{print $2}' | grep "HomeViewModelCatalogPipeline\.kt\|/Module\.kt")
git commit -m "$(cat <<'EOF'
feat(home/mapper): compute preferredArtworkProviders per item

HomeResolvedDisplayMapper.toResolvedDisplayItem(...) now consults
ArtworkProviderResolver to populate ResolvedDisplayItem.preferred
ArtworkProviders for all four artwork types. The map is the input
to the surface tie-breaker (next task).

Anime detection at the mapper boundary: apiType == "anime"
(case-insensitive) OR firstPaintRailSource == RailSource.KITSU.

Mapper signature gains (resolver, currentSettings) parameters —
callers (HomeViewModelCatalogPipeline) pass them through from the
existing settings source.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 12: Surface tie-breaker — preferred-provider-aware merge

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/ResolvedDisplaySurfaceRepository.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/ResolvedDisplaySurfaceRepositoryTieBreakerTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkProviderId
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.integration.ActiveProfileSession
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.domain.model.DisplaySourceRank
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.domain.model.ResolvedSlot
import org.junit.Assert.assertEquals
import org.junit.Test

class ResolvedDisplaySurfaceRepositoryTieBreakerTest {
    private val testSession = ActiveProfileSession(profileId = 1, sessionId = "test")
    private val repo = ResolvedDisplaySurfaceRepository(activeProfileSession = { testSession })
    private val rpdb = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB)
    private val addon = ArtworkProviderId.RuntimeProvider(IntegrationProvider.ADDON)

    private fun posterSlot(value: String, provider: ArtworkProviderId) = ResolvedSlot(
        value = ArtworkDisplayRef.LegacyString(
            value = value,
            imageType = ArtworkType.POSTER,
            trace = ArtworkTrace.empty()
        ) as ArtworkDisplayRef,
        rank = DisplaySourceRank.RESOLVED,
        provider = provider.key,
        role = "HYDRATION_RESOLVED",
        updatedAtMs = 1L,
        expiresAtMs = null,
        trace = emptyList()
    )

    private fun itemWithPoster(slot: ResolvedSlot<ArtworkDisplayRef>): ResolvedDisplayItem {
        // build a minimal ResolvedDisplayItem with only poster slot populated;
        // copy the test boilerplate from existing surface-repo tests
        TODO("build via existing test fixture helper")
    }

    @Test fun `tie regression rejected — existing RPDB beats incoming addon`() {
        val existing = itemWithPoster(posterSlot("rpdb://", rpdb))
            .copy(preferredArtworkProviders = mapOf(ArtworkType.POSTER to rpdb))
        repo.publishResolvedItems(testSession, listOf(existing))
        val incoming = itemWithPoster(posterSlot("addon://stock", addon))
            .copy(preferredArtworkProviders = mapOf(ArtworkType.POSTER to rpdb))

        repo.publishResolvedItems(testSession, listOf(incoming))

        val surface = repo.snapshotNow(profileId = 1).single()
        // Existing should win
        val posterValue = (surface.slots?.poster?.value as? ArtworkDisplayRef.LegacyString)?.value
        assertEquals("rpdb://", posterValue)
    }

    @Test fun `tie upgrade — incoming RPDB beats existing addon`() {
        val existing = itemWithPoster(posterSlot("addon://stock", addon))
            .copy(preferredArtworkProviders = mapOf(ArtworkType.POSTER to rpdb))
        repo.publishResolvedItems(testSession, listOf(existing))
        val incoming = itemWithPoster(posterSlot("rpdb://", rpdb))
            .copy(preferredArtworkProviders = mapOf(ArtworkType.POSTER to rpdb))

        repo.publishResolvedItems(testSession, listOf(incoming))

        val surface = repo.snapshotNow(profileId = 1).single()
        val posterValue = (surface.slots?.poster?.value as? ArtworkDisplayRef.LegacyString)?.value
        assertEquals("rpdb://", posterValue)
    }

    @Test fun `tie both preferred — incoming wins`() {
        val existing = itemWithPoster(posterSlot("rpdb://A", rpdb))
            .copy(preferredArtworkProviders = mapOf(ArtworkType.POSTER to rpdb))
        repo.publishResolvedItems(testSession, listOf(existing))
        val incoming = itemWithPoster(posterSlot("rpdb://B", rpdb))
            .copy(preferredArtworkProviders = mapOf(ArtworkType.POSTER to rpdb))

        repo.publishResolvedItems(testSession, listOf(incoming))

        val surface = repo.snapshotNow(profileId = 1).single()
        val posterValue = (surface.slots?.poster?.value as? ArtworkDisplayRef.LegacyString)?.value
        assertEquals("rpdb://B", posterValue)
    }

    @Test fun `tie neither preferred — existing wins`() {
        val existing = itemWithPoster(posterSlot("addon://A", addon))
            .copy(preferredArtworkProviders = mapOf(ArtworkType.POSTER to rpdb))
        repo.publishResolvedItems(testSession, listOf(existing))
        val incoming = itemWithPoster(posterSlot("addon://B", addon))
            .copy(preferredArtworkProviders = mapOf(ArtworkType.POSTER to rpdb))

        repo.publishResolvedItems(testSession, listOf(incoming))

        val surface = repo.snapshotNow(profileId = 1).single()
        val posterValue = (surface.slots?.poster?.value as? ArtworkDisplayRef.LegacyString)?.value
        assertEquals("addon://A", posterValue)
    }
}
```

- [ ] **Step 2: Implement the tie-breaker**

Modify `applyNonDowngradeMerge` in `ResolvedDisplaySurfaceRepository.kt` to consult `incoming.preferredArtworkProviders`. The existing function delegates to `HomeRailProjectionReducer.reduce` for ALL slots. We need to add a post-pass that overrides the reducer's result for artwork slots on rank ties:

```kotlin
private fun applyNonDowngradeMerge(
    incoming: ResolvedDisplayItem,
    existing: ResolvedDisplayItem?
): ResolvedDisplayItem {
    if (existing == null) return incoming
    val incomingSlots = incoming.slots ?: return incoming
    val existingSlots = existing.slots ?: return incoming

    val reducerMerged = HomeRailProjectionReducer.reduce(
        firstPaint = incomingSlots,
        overlay = null,
        existing = existingSlots,
        profile = null
    )

    // Apply preferred-provider tie-break for artwork slots only.
    val mergedSlots = reducerMerged.copy(
        poster    = preferredAwareSlot(incomingSlots.poster,    existingSlots.poster,    incoming.preferredArtworkProviders[ArtworkType.POSTER]),
        backdrop  = preferredAwareSlot(incomingSlots.backdrop,  existingSlots.backdrop,  incoming.preferredArtworkProviders[ArtworkType.BACKDROP]),
        logo      = preferredAwareSlot(incomingSlots.logo,      existingSlots.logo,      incoming.preferredArtworkProviders[ArtworkType.LOGO]),
        thumbnail = preferredAwareSlot(incomingSlots.thumbnail, existingSlots.thumbnail, incoming.preferredArtworkProviders[ArtworkType.THUMBNAIL])
    )

    if (mergedSlots == existingSlots && incoming.slotDerivedFieldsMatch(existing)) {
        return existing
    }

    val mergedArtwork = mergedSlots.toArtworkBundle()
    val mergedDisplay = mergedSlots.toResolvedDisplayFields(
        fallbackTitle = incoming.display.title.orEmpty(),
        fallbackTomatoesRating = incoming.display.tomatoesRating ?: existing.display.tomatoesRating
    )
    val mergedRating = mergedSlots.toRating() ?: incoming.rating

    return incoming.copy(
        slots = mergedSlots,
        artwork = mergedArtwork,
        display = mergedDisplay,
        rating = mergedRating
    )
}

private fun preferredAwareSlot(
    incoming: ResolvedSlot<ArtworkDisplayRef>,
    existing: ResolvedSlot<ArtworkDisplayRef>,
    preferred: ArtworkProviderId?
): ResolvedSlot<ArtworkDisplayRef> {
    if (incoming.rank.ordinal > existing.rank.ordinal) return incoming
    if (incoming.rank.ordinal < existing.rank.ordinal) return existing
    // Rank tie. Consult preferred.
    if (preferred == null) return incoming  // no preference declared → newer wins
    val incomingMatches = incoming.provider == preferred.key
    val existingMatches = existing.provider == preferred.key
    return when {
        incomingMatches && !existingMatches -> incoming   // upgrade
        !incomingMatches && existingMatches -> existing   // REJECT REGRESSION
        else -> incoming                                  // both or neither → newer fine
    }
}
```

- [ ] **Step 3: Run the test**

```bash
./gradlew :app:testUniversalDebugUnitTest --tests "*ResolvedDisplaySurfaceRepositoryTieBreakerTest*" --max-workers=1 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/ResolvedDisplaySurfaceRepository.kt \
        app/src/test/java/com/nexio/tv/data/repository/ResolvedDisplaySurfaceRepositoryTieBreakerTest.kt
git commit -m "$(cat <<'EOF'
fix(home/surface): preferred-provider-aware tie-breaker (Bug A)

applyNonDowngradeMerge now performs a per-artwork-slot tie-break pass
after HomeRailProjectionReducer.reduce. For artwork slots (poster,
backdrop, logo, thumbnail) on rank tie, the slot whose provider
matches incoming.preferredArtworkProviders[slotType] wins. Reject-
regression case: existing has the preferred provider but incoming has
a fallback → existing wins (closes Bug A popping).

Text slots remain unchanged (incoming-wins-on-tie via reducer).

Reducer itself stays pure: settings/preferences are consulted only at
this merge boundary, not inside pickHigherRanked.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 13: `CatalogItemCrossIdEnricher` pushes `markStaleIfWeakerIds`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/mapper/CatalogItemCrossIdEnricher.kt`

- [ ] **Step 1: Inject the overlay store**

```bash
grep -n "class CatalogItemCrossIdEnricher\|@Inject constructor" app/src/main/java/com/nexio/tv/data/mapper/CatalogItemCrossIdEnricher.kt | head -3
```

Add to the constructor:

```kotlin
@Singleton
class CatalogItemCrossIdEnricher @Inject constructor(
    // … existing deps
    private val overlayStore: HydratedHomeOverlayStore
) { … }
```

- [ ] **Step 2: Push `markStaleIfWeakerIds` after writing new IDs**

Locate the function that writes back `firstPaintStableIds` onto items (or where the enricher reports newly-resolved IDs). After the write, call:

```kotlin
overlayStore.markStaleIfWeakerIds(
    itemKey = homeDisplayItemKey(item.apiType, item.id),
    currentIds = mergedIds
)
```

`mergedIds` is the new ProviderIds value after enrichment. `homeDisplayItemKey` is the existing helper used elsewhere; verify import path.

If the enricher operates on a collection of items, call the store once per item that materially gained an ID. Use `mergedIds.strictlyContains(item.firstPaintStableIds)` as the guard if `markStaleIfWeakerIds` doesn't already early-return for no-op cases (it does, per Task 7's implementation — `strictlyContains` returns false when current ≤ snapshot).

- [ ] **Step 3: Compile + verify no infinite-loop risk**

Manual inspection: `markStaleIfWeakerIds` is `@Synchronized` and mutates `state`. It does NOT trigger another enrichment pass. The flow is one-way: enricher → store. No loop.

```bash
./gradlew :app:compileUniversalDebugKotlin --max-workers=1 2>&1 | tail -5
```

- [ ] **Step 4: Add a unit test confirming the push happens**

`app/src/test/java/com/nexio/tv/data/mapper/CatalogItemCrossIdEnricherTest.kt` — extend the existing test class if present; mock `HydratedHomeOverlayStore`; assert `markStaleIfWeakerIds(itemKey, expectedIds)` is invoked once per item that gained an ID.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/mapper/CatalogItemCrossIdEnricher.kt \
        app/src/test/java/com/nexio/tv/data/mapper/CatalogItemCrossIdEnricherTest.kt
git commit -m "$(cat <<'EOF'
feat(catalog/enrich): push markStaleIfWeakerIds after ID strengthening

CatalogItemCrossIdEnricher now notifies HydratedHomeOverlayStore when
it writes new IDs for an item. The store compares to the overlay's
stableIdsSnapshot; if current strictly contains the snapshot, the
overlay transitions to STALE_READY and the next visibility event
re-fires hydration with the broader ID set (e.g., RPDB now unlockable
because imdb arrived).

Closes Bug B (stale overlay never re-hydrated after ID strengthening).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 14: `ArtworkSettingsInvalidator` + wire into bootstrap

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/invalidation/ArtworkSettingsInvalidator.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/di/IntegrationRuntimeModule.kt` OR `app/src/main/java/com/nexio/tv/NexioApplication.kt`
- Test: `app/src/test/java/com/nexio/tv/data/invalidation/ArtworkSettingsInvalidatorTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.nexio.tv.data.invalidation

import com.nexio.tv.data.local.HydratedHomeOverlayStore
import com.nexio.tv.domain.model.ArtworkProviderChoiceKey
import com.nexio.tv.domain.model.ArtworkProviderSelectionSettings
import com.nexio.tv.domain.model.ArtworkProviderSettings
import com.nexio.tv.core.artwork.ArtworkProviderSettingsSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions

class ArtworkSettingsInvalidatorTest {
    @Test fun `first emission does NOT mark all stale`() = runTest {
        val source = FakeSource(MutableStateFlow(ArtworkProviderSettings()))
        val store: HydratedHomeOverlayStore = mock()
        val invalidator = ArtworkSettingsInvalidator(source, store, this)
        invalidator.start()
        advanceUntilIdle()
        verifyNoInteractions(store)
    }

    @Test fun `same-signature re-emission does NOT mark all stale`() = runTest {
        val flow = MutableStateFlow(ArtworkProviderSettings())
        val source = FakeSource(flow)
        val store: HydratedHomeOverlayStore = mock()
        val invalidator = ArtworkSettingsInvalidator(source, store, this)
        invalidator.start()
        advanceUntilIdle()
        flow.update { it.copy(rpdbApiKey = "new-key") }  // api key change does NOT change signature
        advanceUntilIdle()
        verifyNoInteractions(store)
    }

    @Test fun `signature change triggers markStaleAll`() = runTest {
        val flow = MutableStateFlow(ArtworkProviderSettings())
        val source = FakeSource(flow)
        val store: HydratedHomeOverlayStore = mock()
        val invalidator = ArtworkSettingsInvalidator(source, store, this)
        invalidator.start()
        advanceUntilIdle()
        flow.update {
            it.copy(
                selection = ArtworkProviderSelectionSettings(
                    posterProvider = ArtworkProviderChoiceKey.RPDB
                )
            )
        }
        advanceUntilIdle()
        verify(store).markStaleAll("settings_change")
    }

    private class FakeSource(
        override val settings: MutableStateFlow<ArtworkProviderSettings>
    ) : ArtworkProviderSettingsSource
}
```

- [ ] **Step 2: Create the invalidator**

```kotlin
package com.nexio.tv.data.invalidation

import com.nexio.tv.core.artwork.ArtworkProviderSettingsSource
import com.nexio.tv.data.local.HydratedHomeOverlayStore
import com.nexio.tv.domain.model.toSettingsSignature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class ArtworkSettingsInvalidator @Inject constructor(
    private val settingsSource: ArtworkProviderSettingsSource,
    private val overlayStore: HydratedHomeOverlayStore,
    @Named("AppScope") private val appScope: CoroutineScope
) {
    fun start() {
        appScope.launch {
            var lastSignature: String? = null
            settingsSource.settings
                .map { it.toSettingsSignature() }
                .distinctUntilChanged()
                .collect { signature ->
                    if (lastSignature != null && lastSignature != signature) {
                        overlayStore.markStaleAll(reason = "settings_change")
                    }
                    lastSignature = signature
                }
        }
    }
}
```

`@Named("AppScope") CoroutineScope` may not be the existing app-scope injection name in this codebase. Look up:

```bash
grep -rn "@Provides.*CoroutineScope\|AppScope\b" app/src/main/java/com/nexio/tv/core/di --include="*.kt" | head -5
```

Use whatever qualifier the project uses for the application-lifetime coroutine scope.

- [ ] **Step 3: Wire `start()` into app bootstrap**

Find the bootstrap site:

```bash
grep -rn "fun onCreate\|class NexioApplication\|StartupSyncService\b" app/src/main/java/com/nexio/tv --include="*.kt" | head -10
```

Add to `NexioApplication.onCreate` (or the existing integration-runtime bootstrap class) after Hilt has injected fields:

```kotlin
@Inject lateinit var artworkSettingsInvalidator: ArtworkSettingsInvalidator
// in onCreate:
artworkSettingsInvalidator.start()
```

- [ ] **Step 4: Compile + run tests**

```bash
./gradlew :app:compileUniversalDebugKotlin --max-workers=1 2>&1 | tail -5
./gradlew :app:testUniversalDebugUnitTest --tests "*ArtworkSettingsInvalidatorTest*" --max-workers=1 2>&1 | tail -5
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/invalidation/ArtworkSettingsInvalidator.kt \
        app/src/main/java/com/nexio/tv/NexioApplication.kt \
        app/src/test/java/com/nexio/tv/data/invalidation/ArtworkSettingsInvalidatorTest.kt
git commit -m "$(cat <<'EOF'
feat(invalidation): ArtworkSettingsInvalidator — markStaleAll on settings change

App-scoped Flow observer. On any change to the ArtworkProviderSettings
signature (the four provider choices + DEFAULTS_TABLE_VERSION), calls
HydratedHomeOverlayStore.markStaleAll("settings_change"). First emission
is a no-op (lastSignature is null); subsequent identical-signature
emissions are no-ops (distinctUntilChanged).

Closes Bug D (settings changes had no effect on already-cached
overlays). Also drives the fanart.tv migration: bumping DEFAULTS_TABLE_
VERSION changes every overlay's persisted signature relative to current
→ first emission after upgrade triggers mass invalidation → re-hydrate
on visibility.

Wired into NexioApplication.onCreate via Hilt field injection.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 15: Telemetry — four new trace events

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/trace/TraceMetadataEvents.kt` (or wherever trace events are declared)
- Modify: `ArtworkProviderResolver.kt`, `HydratedHomeOverlayStore.kt`, `HomeHydrationCoordinator.kt`, `ResolvedDisplaySurfaceRepository.kt`

- [ ] **Step 1: Locate the trace events sink**

```bash
grep -rn "class TraceMetadataEvents\|fun emitHomeHydration" app/src/main/java/com/nexio/tv --include="*.kt" | head -5
```

Find the file that declares the existing `emitHomeHydration*` family. Add four new methods:

```kotlin
fun emitArtworkResolverDecision(
    itemKeyHash: String?,
    artworkType: String,
    contentType: String,
    isAnime: Boolean,
    explicit: String,           // settings choice (e.g., "rpdb", "default")
    fellThroughTo: String?,     // non-null when explicit failed capability check
    chosenProvider: String,
    capabilitySupported: Boolean
)

fun emitOverlayStaleMarked(
    itemKey: String,
    reason: String,             // "settings_change" | "cross_id_enriched" | "signature_mismatch_cold_start"
    oldState: String
)

fun emitOverlayRehydrationTriggered(
    itemKey: String,
    source: String,             // "visibility" | "focus" | "adjacent"
    priorState: String
)

fun emitSurfaceMergeTieBreakRejected(
    itemKey: String,
    slotType: String,
    existingProvider: String?,
    incomingProvider: String?,
    preferredProvider: String
)
```

Implement each with the same hash/logging pattern the existing `emit*` methods use (probably `traceSink.emit("event.name", mapOf(...))` or similar). Mirror the field-name conventions.

- [ ] **Step 2: Wire each emit to its call site**

- `ArtworkProviderResolver.resolve(...)` — emit `artwork.resolver.decision` at the end of `resolve`, capturing whether the explicit choice was used or fell through.
- `HydratedHomeOverlayStore.markStaleIfWeakerIds(...)` and `markStaleAll(...)` — emit `overlay.stale_marked` per overlay whose state was transitioned (already stubbed in Task 7 with placeholder calls; this task replaces stubs with real calls).
- `HomeHydrationCoordinator.hydrateVisibleHomeItemsWithCoordinator(...)` or wherever the visibility/focus path enters the coordinator — emit `overlay.rehydration_triggered` when a stale overlay re-fires.
- `ResolvedDisplaySurfaceRepository.preferredAwareSlot` (introduced in Task 12) — emit `surface.merge.tie_break_rejected_regression` when the `!incomingMatches && existingMatches → existing wins` branch executes.

- [ ] **Step 3: Compile**

```bash
./gradlew :app:compileUniversalDebugKotlin --max-workers=1 2>&1 | tail -5
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/trace/ \
        app/src/main/java/com/nexio/tv/core/artwork/ArtworkProviderResolver.kt \
        app/src/main/java/com/nexio/tv/data/local/HydratedHomeOverlayStore.kt \
        app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationCoordinator.kt \
        app/src/main/java/com/nexio/tv/data/repository/ResolvedDisplaySurfaceRepository.kt
git commit -m "$(cat <<'EOF'
feat(trace): four trace events for artwork routing + invalidation

- artwork.resolver.decision — every ArtworkProviderResolver.resolve.
  Shows whether explicit setting won or fell through, and which
  provider was chosen.
- overlay.stale_marked — every markStaleIfWeakerIds / markStaleAll
  state transition. Reason field discriminates settings_change vs
  cross_id_enriched vs signature_mismatch_cold_start.
- overlay.rehydration_triggered — every coordinator re-fire of a
  stale overlay.
- surface.merge.tie_break_rejected_regression — the "popping watchdog".
  Non-zero on home soak = the tie-breaker is actively rejecting
  RPDB→addon regressions.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 16: On-device acceptance gates

**Files:** none (verification only).

- [ ] **Step 1: Install + cold-start with multiple rails visible**

```bash
./gradlew :app:installUniversalDebug --max-workers=1 2>&1 | tail -3
adb -s 192.168.50.98:5555 shell am force-stop com.nexiodebug.tv
adb -s 192.168.50.98:5555 logcat -c
adb -s 192.168.50.98:5555 shell monkey -p com.nexiodebug.tv 1
sleep 10
adb -s 192.168.50.98:5555 shell input keyevent KEYCODE_DPAD_CENTER
sleep 90  # let catalog rows + cross-id enricher both complete
```

- [ ] **Step 2: Gate 1 — settings change observable**

```bash
# Capture pre-change screenshot
adb -s 192.168.50.98:5555 shell screencap -p /sdcard/pre-settings.png
adb -s 192.168.50.98:5555 pull /sdcard/pre-settings.png /tmp/

# Navigate to settings → toggle Poster from RPDB to Default. Use keyevents
# tuned to your launcher; below is illustrative — adjust to actual nav.
adb -s 192.168.50.98:5555 shell input keyevent KEYCODE_MENU
sleep 1
# … navigate to Integration → Poster provider → Default
# (manual step or scripted with known keycodes)

sleep 10  # visibility re-hydration window
adb -s 192.168.50.98:5555 shell screencap -p /sdcard/post-settings.png
adb -s 192.168.50.98:5555 pull /sdcard/post-settings.png /tmp/
adb -s 192.168.50.98:5555 logcat -d -t 5000 | grep "overlay.stale_marked" | grep "settings_change" | head -5
```

Expected:
- `/tmp/pre-settings.png` shows RPDB posters.
- `/tmp/post-settings.png` shows addon stock posters.
- Logcat: `overlay.stale_marked … reason=settings_change` fires N times.

- [ ] **Step 3: Gate 2 — stale-overlay self-healing**

```bash
# Wipe overlay store, fresh cold start
adb -s 192.168.50.98:5555 shell am force-stop com.nexiodebug.tv
adb -s 192.168.50.98:5555 shell run-as com.nexiodebug.tv rm -rf /data/data/com.nexiodebug.tv/files/hydrated-home-overlay-v1
adb -s 192.168.50.98:5555 logcat -c
adb -s 192.168.50.98:5555 shell monkey -p com.nexiodebug.tv 1
sleep 10
adb -s 192.168.50.98:5555 shell input keyevent KEYCODE_DPAD_CENTER
sleep 30  # let initial hydration land (likely imdb=null for Trakt rails)
adb -s 192.168.50.98:5555 logcat -d -t 5000 | grep -E "overlay.stale_marked|reason=cross_id_enriched" | head -10
```

Expected: at least one `overlay.stale_marked reason=cross_id_enriched` event within the 30s soak. Visually: a Trakt Trending poster that started addon-stock upgrades to RPDB.

- [ ] **Step 4: Gate 3 — popping does not recur**

```bash
# 5 minute soak with periodic input to keep producer emissions firing
for i in 1 2 3 4 5 6 7 8 9 10; do
  adb -s 192.168.50.98:5555 shell input keyevent KEYCODE_DPAD_DOWN
  sleep 30
done
adb -s 192.168.50.98:5555 logcat -d -t 30000 | grep "surface.merge.tie_break_rejected_regression" | head -10
```

Expected: non-zero count of `surface.merge.tie_break_rejected_regression` events. Visually: posters do NOT flicker between RPDB and addon during the 5min soak.

- [ ] **Step 5: Gate 4 — heap stable**

```bash
PID=$(adb -s 192.168.50.98:5555 shell pidof com.nexiodebug.tv)
adb -s 192.168.50.98:5555 shell am dumpheap "$PID" /data/local/tmp/heap-art-t0.hprof
sleep 8
adb -s 192.168.50.98:5555 pull /data/local/tmp/heap-art-t0.hprof /tmp/
sleep 30
adb -s 192.168.50.98:5555 shell am dumpheap "$PID" /data/local/tmp/heap-art-t30.hprof
sleep 8
adb -s 192.168.50.98:5555 pull /data/local/tmp/heap-art-t30.hprof /tmp/
heaptrail --diff-from /tmp/heap-art-t0.hprof --diff-to /tmp/heap-art-t30.hprof --diff-by count --top 20 2>&1 | head -30
heaptrail -i /tmp/heap-art-t30.hprof -t 600 2>&1 | grep -E "ResolvedDisplayItem|HydratedHomeOverlay|MetaPreview" | head -5
```

Expected:
- `ResolvedDisplayItem` count steady (~700–1400).
- `HydratedHomeOverlay` count steady (no leak from invalidation cycles).
- GC interval > 5 s steady state.

- [ ] **Step 6: Document acceptance results in the auto-memory and commit**

Save findings (heap deltas, log event counts, visual confirmation) to `~/.claude/projects/-Users-jneerdael-Scripts-nexio/memory/project_artwork_routing_invalidation_complete.md` per the auto-memory format. Reference the final commit SHA.

---

## Self-Review

**Spec coverage:**
- Bug A (popping) → Task 12 ✓
- Bug B (stale overlay) → Tasks 6, 7, 13 ✓
- Bug C (content-equality gate) → Task 9 ✓
- Bug D (settings changes) → Tasks 5, 14 ✓
- DEFAULT semantics + resolver → Tasks 3, 4 ✓
- Per-content-type defaults table → Task 4 ✓
- `stableIdsSnapshot` + `settingsSignature` provenance → Tasks 6, 7, 9 ✓
- Persistence v2 + v1 self-heal → Tasks 7, 8 ✓
- Telemetry → Task 15 ✓
- On-device gates → Task 16 ✓
- Fanart.tv migration hook (`DEFAULTS_TABLE_VERSION`) → declared in Task 4, exercised by settings signature in Task 5 ✓
- Test design (8 unit tests + 4 on-device gates) → all 8 tests authored (Tasks 2, 3, 4, 5, 7, 8, 11, 12, 14) plus a coordinator-gate test in Task 9 ✓

**Placeholder scan:**
- Task 8 Step 2 contains a `TODO("inspect store layout and craft a v1 fixture …")` — DELIBERATE; the implementing engineer must inspect the actual file layout to choose between hand-crafted fixture vs round-trip-then-edit. The body explicitly directs the engineer to use approach (a) (hand-craft).
- Task 7 Step 1 marks a `TODO_AGENT` for the test-factory wiring — DELIBERATE; the implementing engineer copies from the neighbor `HomeCatalogSnapshotStoreTest.kt` (path given).
- Task 12 Step 1 has `TODO("build via existing test fixture helper")` for `itemWithPoster` — DELIBERATE; the implementing engineer copies from existing surface-repo tests.

These TODOs are scoped, name the source to copy from, and are bounded — they're not "implement this later" debt.

**Type consistency:**
- `ArtworkProviderId.RuntimeProvider(IntegrationProvider.X)` used consistently in Tasks 3, 4, 11, 12.
- `ArtworkProviderChoiceKey.toRuntimeProviderId()` defined in Task 3, used in Task 4.
- `ContentType.toMetadataMediaKind()` (shared internal fn) defined in Task 1, used in Task 4.
- `ProviderIds.strictlyContains` defined in Task 2, used in Task 7.
- `HydratedHomeOverlay.{stableIdsSnapshot, settingsSignature}` defined in Task 6, written/read in Tasks 7, 9.
- `HydratedHomeOverlayStore.{markStaleIfWeakerIds, markStaleAll}` defined in Task 7, called in Tasks 13, 14.
- `ResolvedDisplayItem.preferredArtworkProviders` defined in Task 10, computed in Task 11, consumed in Task 12.
- `ArtworkSettingsInvalidator` defined in Task 14, wired in Task 14 Step 3.
- `DEFAULTS_TABLE_VERSION` defined in Task 4, consumed by `toSettingsSignature` in Task 5.

**Dependency order:**
1, 2, 3 → independent foundation.
4 depends on 1, 3.
5 depends on 4.
6 → independent.
7 depends on 2, 6.
8 depends on 7 (test for what 7 ships).
9 depends on 5, 6.
10 → independent.
11 depends on 4, 10.
12 depends on 10.
13 depends on 7.
14 depends on 5, 7, 8 (relies on signature change propagating through markStaleAll).
15 → cross-cutting, after everything it traces.
16 → final, after all code lands.

Order is acyclic. Tasks can ship serially in 1→16 order. Some can run in parallel (e.g., 6 and 10 are independent of 1–5).
