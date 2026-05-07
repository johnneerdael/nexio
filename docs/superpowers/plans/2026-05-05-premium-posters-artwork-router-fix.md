# Premium Posters Artwork Router Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make premium poster models render on modern home and keep poster cache/provider metadata aligned with the selected artwork provider.

**Architecture:** The primary rendering failure is in the image boundary: `integration-poster://fetch?...` values are passed to Coil as strings, Coil maps strings to `Uri`, and the current `IntegrationPosterFetcher.Factory<String>` is skipped. The secondary metadata failure is in the router-to-home projection: selected RPDB/TOP_POSTERS poster values are copied into `HomeDisplayMetadata.poster` without updating or clearing `posterProviderTag`, leaving stale `tmdb` tags on premium poster models.

**Tech Stack:** Kotlin, Android, Coil 2.7, Hilt, Robolectric/JUnit4, MockK, existing metadata router unit tests.

---

## File Structure

- Modify `app/src/main/java/com/nexio/tv/core/image/IntegrationPosterFetcher.kt`
  - Change `IntegrationPosterFetcher.Factory` from `Fetcher.Factory<String>` to `Fetcher.Factory<Uri>`.
  - Parse `data.toString()` so Coil's string-to-Uri mapping still reaches the custom premium poster fetcher.
- Modify `app/src/test/java/com/nexio/tv/core/image/IntegrationPosterFetcherTest.kt`
  - Add Robolectric runner support for `android.net.Uri`.
  - Add factory tests that prove a mapped `Uri` creates `IntegrationPosterFetcher` and a remote HTTP `Uri` is rejected.
- Modify `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt`
  - Set `HomeDisplayMetadata.posterProviderTag` from the selected poster source.
  - Clear stale provider tags when a non-artwork poster replaces an older premium/native poster.
- Modify `app/src/test/java/com/nexio/tv/core/metadata/router/MetadataRouterFacadeTest.kt`
  - Add tests for RPDB provider tag propagation.
  - Add a regression test that a primary raw poster clears a stale premium provider tag.
  - Add a small premium poster adapter test helper.

## Task 1: Make Coil Reach `IntegrationPosterFetcher`

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/core/image/IntegrationPosterFetcherTest.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/image/IntegrationPosterFetcher.kt`

- [ ] **Step 1: Write failing factory tests**

In `app/src/test/java/com/nexio/tv/core/image/IntegrationPosterFetcherTest.kt`, add these imports near the top:

```kotlin
import android.net.Uri
import org.junit.Assert.assertNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
```

Add the Robolectric runner annotation immediately above the class:

```kotlin
@RunWith(RobolectricTestRunner::class)
class IntegrationPosterFetcherTest {
```

Add these tests inside `IntegrationPosterFetcherTest`:

```kotlin
    @Test
    fun `factory accepts mapped integration poster uri model`() {
        val factory = IntegrationPosterFetcher.Factory(
            rpdbProvider = mockk(relaxed = true),
            topPostersProvider = mockk(relaxed = true),
            fallbackTransport = mockk(relaxed = true)
        )
        val model = PosterIntegrationRequest(
            provider = IntegrationProvider.RPDB,
            cacheKey = "rpdb:imdb:tt0137523:poster-default",
            apiKey = "key",
            path = "imdb/poster-default/tt0137523.jpg"
        ).toModel()

        val fetcher = factory.create(
            data = Uri.parse(model),
            options = mockk(relaxed = true),
            imageLoader = mockk(relaxed = true)
        )

        assertTrue(fetcher is IntegrationPosterFetcher)
    }

    @Test
    fun `factory rejects non integration poster uri model`() {
        val factory = IntegrationPosterFetcher.Factory(
            rpdbProvider = mockk(relaxed = true),
            topPostersProvider = mockk(relaxed = true),
            fallbackTransport = mockk(relaxed = true)
        )

        val fetcher = factory.create(
            data = Uri.parse("https://image.tmdb.org/t/p/w500/native.jpg"),
            options = mockk(relaxed = true),
            imageLoader = mockk(relaxed = true)
        )

        assertNull(fetcher)
    }
```

- [ ] **Step 2: Run the targeted test and verify it fails**

Run:

```bash
./gradlew testDebugUnitTest --tests 'com.nexio.tv.core.image.IntegrationPosterFetcherTest'
```

Expected result before implementation: compilation fails because `IntegrationPosterFetcher.Factory.create` expects `String`, not `Uri`.

- [ ] **Step 3: Change the fetcher factory to accept Uri**

In `app/src/main/java/com/nexio/tv/core/image/IntegrationPosterFetcher.kt`, add this import:

```kotlin
import android.net.Uri
```

Replace the factory declaration and `create` signature:

```kotlin
    @Singleton
    class Factory @Inject constructor(
        private val rpdbProvider: RpdbIntegrationProvider,
        private val topPostersProvider: TopPostersIntegrationProvider,
        private val fallbackTransport: PosterTransport
    ) : Fetcher.Factory<Uri> {
        override fun create(
            data: Uri,
            options: coil.request.Options,
            imageLoader: ImageLoader
        ): Fetcher? {
            val request = PosterIntegrationRequest.fromModel(data.toString()) ?: return null
            return IntegrationPosterFetcher(
                request = request,
                options = options,
                rpdbProvider = rpdbProvider,
                topPostersProvider = topPostersProvider,
                fallbackTransport = fallbackTransport
            )
        }
    }
```

- [ ] **Step 4: Run the targeted test and verify it passes**

Run:

```bash
./gradlew testDebugUnitTest --tests 'com.nexio.tv.core.image.IntegrationPosterFetcherTest'
```

Expected result: all `IntegrationPosterFetcherTest` tests pass.

- [ ] **Step 5: Commit Task 1**

Run:

```bash
git add app/src/main/java/com/nexio/tv/core/image/IntegrationPosterFetcher.kt app/src/test/java/com/nexio/tv/core/image/IntegrationPosterFetcherTest.kt
git commit -m "fix: route premium poster uri models to custom fetcher"
```

## Task 2: Propagate the Selected Premium Poster Provider Tag

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/core/metadata/router/MetadataRouterFacadeTest.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt`

- [ ] **Step 1: Write failing metadata projection tests**

In `app/src/test/java/com/nexio/tv/core/metadata/router/MetadataRouterFacadeTest.kt`, add these imports:

```kotlin
import com.nexio.tv.core.image.PosterIntegrationRequest
import com.nexio.tv.core.integration.IntegrationProvider
```

Add these tests inside `MetadataRouterFacadeTest`:

```kotlin
    @Test
    fun `metadata facade tags resolved rpdb poster provider`() = runTest {
        val result = facade(
            PrimaryPosterMetadataProviderAdapter(MetadataPrimaryProvider.TMDB),
            PremiumPosterMetadataProviderAdapter(MetadataPrimaryProvider.RPDB)
        ).resolveRequest(
            MetadataRequest(
                contentId = "tmdb:550",
                contentType = ContentType.MOVIE,
                sourceContext = MetadataSourceContext(
                    addonMetadata = HomeDisplayMetadata(
                        poster = "previewPoster",
                        posterProviderTag = "tmdb"
                    ),
                    previewSourceRole = SourceRole.RAIL_PREVIEW,
                    previewSourceProvider = MetadataPrimaryProvider.TRAKT.name
                ),
                depth = MetadataDepth.DETAIL_CORE
            )
        )

        val request = PosterIntegrationRequest.fromModel(result.displayMetadata.poster!!)

        assertEquals(IntegrationProvider.RPDB, request?.provider)
        assertEquals("rpdb", result.displayMetadata.posterProviderTag)
        assertEquals(SourceRole.ARTWORK, result.resolvedDocument.sourceRoles[ResolvedField.POSTER])
    }

    @Test
    fun `metadata facade clears stale poster provider tag when primary poster wins`() = runTest {
        val result = facade(
            PrimaryPosterMetadataProviderAdapter(MetadataPrimaryProvider.TMDB)
        ).resolveRequest(
            MetadataRequest(
                contentId = "tmdb:550",
                contentType = ContentType.MOVIE,
                sourceContext = MetadataSourceContext(
                    addonMetadata = HomeDisplayMetadata(
                        poster = "oldPremiumPoster",
                        posterProviderTag = "rpdb"
                    ),
                    previewSourceRole = SourceRole.RAIL_PREVIEW,
                    previewSourceProvider = MetadataPrimaryProvider.TRAKT.name
                ),
                depth = MetadataDepth.DETAIL_CORE
            )
        )

        assertEquals("primaryPoster", result.displayMetadata.poster)
        assertNull(result.displayMetadata.posterProviderTag)
    }
```

Add this helper class near the existing `PrimaryPosterMetadataProviderAdapter` helper:

```kotlin
    private class PremiumPosterMetadataProviderAdapter(
        override val provider: MetadataPrimaryProvider
    ) : MetadataProviderAdapter {
        override fun supports(step: ProviderPlanStep): Boolean = step.provider == provider

        override suspend fun execute(route: MetadataRoute, step: ProviderPlanStep): ProviderStepResult {
            val model = PosterIntegrationRequest(
                provider = IntegrationProvider.RPDB,
                cacheKey = "rpdb:imdb:tt0137523:poster-default:credential",
                apiKey = "key",
                path = "imdb/poster-default/tt0137523.jpg"
            ).toModel()
            return ProviderStepResult(
                step = step,
                candidate = MetadataCandidate(
                    provider = provider,
                    resolverType = ResolverType.ARTWORK,
                    sourceProvider = provider.name,
                    sourceRole = SourceRole.ARTWORK,
                    fields = mapOf(
                        ResolvedField.POSTER to FieldValue(model, FieldOwner.ARTWORK, SourceRole.ARTWORK)
                    )
                )
            )
        }
    }
```

- [ ] **Step 2: Run the targeted metadata test and verify it fails**

Run:

```bash
./gradlew testDebugUnitTest --tests 'com.nexio.tv.core.metadata.router.MetadataRouterFacadeTest'
```

Expected result before implementation: the RPDB test fails with `expected:<rpdb> but was:<tmdb>` or `null`, and the stale tag test fails with `expected null`.

- [ ] **Step 3: Implement provider tag projection**

In `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt`, replace the `ResolvedMetadataDocument.toHomeDisplayMetadata` function with:

```kotlin
    private fun ResolvedMetadataDocument.toHomeDisplayMetadata(fallback: HomeDisplayMetadata): HomeDisplayMetadata =
        fallback.copy(
            title = title ?: fallback.title,
            logo = logo ?: fallback.logo,
            description = overview ?: fallback.description,
            runtime = runtimeMinutes?.toString() ?: fallback.runtime,
            imdbRating = (rating as? Number)?.toFloat() ?: fallback.imdbRating,
            poster = poster ?: fallback.poster,
            posterProviderTag = resolvedPosterProviderTag(fallback),
            backdrop = backdrop ?: fallback.backdrop,
            releaseInfo = releaseDate ?: fallback.releaseInfo,
            genres = genres.ifEmpty { fallback.genres },
            artwork = mergeResolvedArtwork(fallback)
        )

    private fun ResolvedMetadataDocument.resolvedPosterProviderTag(fallback: HomeDisplayMetadata): String? {
        val selectedPoster = poster
        val selectedRole = sourceRoles[ResolvedField.POSTER]
        val selectedProvider = sourceProviders[ResolvedField.POSTER]
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        return when {
            selectedPoster == null -> fallback.posterProviderTag
            selectedRole == SourceRole.ARTWORK && selectedProvider != null -> selectedProvider.lowercase()
            else -> null
        }
    }
```

- [ ] **Step 4: Run the targeted metadata test and verify it passes**

Run:

```bash
./gradlew testDebugUnitTest --tests 'com.nexio.tv.core.metadata.router.MetadataRouterFacadeTest'
```

Expected result: all `MetadataRouterFacadeTest` tests pass.

- [ ] **Step 5: Commit Task 2**

Run:

```bash
git add app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt app/src/test/java/com/nexio/tv/core/metadata/router/MetadataRouterFacadeTest.kt
git commit -m "fix: project selected poster provider tag"
```

## Task 3: Verify Combined Behavior

**Files:**
- No source changes expected.
- Verify: image fetcher tests, metadata router tests, relevant home presentation tests.

- [ ] **Step 1: Run focused regression tests**

Run:

```bash
./gradlew testDebugUnitTest --tests 'com.nexio.tv.core.image.IntegrationPosterFetcherTest' --tests 'com.nexio.tv.core.metadata.router.MetadataRouterFacadeTest' --tests 'com.nexio.tv.ui.screens.home.HomeViewModelPresentationPipelineTest' --tests 'com.nexio.tv.ui.screens.home.ModernHomeModelsTest'
```

Expected result: all selected tests pass.

- [ ] **Step 2: Run architecture boundary tests for artwork models**

Run:

```bash
./gradlew testDebugUnitTest --tests 'com.nexio.tv.architecture.RawRemoteArtworkUrlBoundaryTest' --tests 'com.nexio.tv.core.image.ArtworkImageCacheKeysTest'
```

Expected result: all selected tests pass. This confirms the fix did not reintroduce raw remote artwork leakage or break cache key semantics for internal artwork models.

- [ ] **Step 3: Install the debug build on the rooted device**

Run:

```bash
./gradlew installDebug
adb connect 192.168.50.98:5555
adb -s 192.168.50.98:5555 shell monkey -p com.nexiodebug.tv 1
```

Expected result: the debug app launches on the UGOOS device. If this repository's debug package is configured differently on the current branch, confirm the installed package with:

```bash
adb -s 192.168.50.98:5555 shell pm list packages | grep nexio
```

Use the package name printed by that command for the `monkey -p` launch.

- [ ] **Step 4: Capture a home screenshot and verify posters render**

Run:

```bash
adb -s 192.168.50.98:5555 shell screencap -p /sdcard/nexio-premium-posters-after.png
adb -s 192.168.50.98:5555 pull /sdcard/nexio-premium-posters-after.png /tmp/nexio-premium-posters-after.png
```

Expected result: `/tmp/nexio-premium-posters-after.png` shows poster images in the modern home rails instead of blank dark cards. Hero backdrops/logos should still render.

- [ ] **Step 5: Confirm RPDB poster runtime activity appears after launch**

Run:

```bash
adb -s 192.168.50.98:5555 logcat -d -v time | grep -E 'rpdb\\.poster|apiShapeId=rpdb\\.poster_template|provider=RPDB'
```

Expected result: logcat includes RPDB poster runtime activity after the home screen requests premium poster images. Metadata router lines that only say `field=POSTER selectedProvider=RPDB` are not sufficient for this check; the output must include runtime/cache/fetch activity for RPDB poster templates.

- [ ] **Step 6: Commit verification notes if source changes were adjusted**

If Task 3 required source or test updates, commit them:

```bash
git status --short
git add app/src/main/java app/src/test/java
git commit -m "test: cover premium poster home rendering regression"
```

Expected result: no commit is created when Task 3 only performed verification and `git status --short` shows no new changes from this task.

## Self-Review

- Spec coverage: Task 1 fixes the root cause where Coil cannot reach the custom premium poster fetcher. Task 2 fixes the secondary provider-tag issue by projecting the selected poster provider and clearing stale tags. Task 3 verifies tests and the adb-visible home screen symptom.
- Placeholder scan: This plan contains concrete file paths, code snippets, commands, and expected outcomes for every step.
- Type consistency: `IntegrationPosterFetcher.Factory` changes to `Fetcher.Factory<Uri>` and the test passes `Uri.parse(model)`. `resolvedPosterProviderTag` uses existing `ResolvedMetadataDocument.sourceRoles`, `sourceProviders`, `ResolvedField.POSTER`, and `SourceRole.ARTWORK`.
