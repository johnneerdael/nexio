# Remote Informational Notices Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a startup-only remote informational notice system that reads a GitHub-hosted manifest and Markdown body, shows the newest eligible notice once, and suppresses notices that predate a fresh install's first successful manifest baseline.

**Architecture:** Add a dedicated `com.nexio.tv.notices` feature beside the existing updater. Raw GitHub notice files are fetched through the existing integration runtime, eligibility is pure Kotlin, app-wide notice state lives in a separate DataStore, and `MainActivity` gates rendering so update prompts, playback, splash, trailers, and screensavers take precedence.

**Tech Stack:** Kotlin, Hilt, Retrofit, OkHttp `ResponseBody`, Moshi, DataStore Preferences, Compose for Android TV, `com.mikepenz.markdown.m3.Markdown`, kotlinx-coroutines-test, MockK, Robolectric, Android Compose UI tests.

---

## Scope Check

The spec describes one coherent subsystem: remote informational notices. It has independent layers, but each is required for the same user-visible behavior, so a single implementation plan is appropriate.

## File Structure

Create:

- `app/src/main/java/com/nexio/tv/data/remote/api/GitHubRawContentApi.kt`: Retrofit API for absolute raw HTTPS text URLs.
- `app/src/main/java/com/nexio/tv/data/integration/github/GitHubRawContentIntegrationProvider.kt`: wraps raw-text fetches in `IntegrationRuntime.call`.
- `app/src/main/java/com/nexio/tv/notices/model/RemoteNoticeModels.kt`: manifest DTOs and UI/domain notice models.
- `app/src/main/java/com/nexio/tv/notices/RemoteNoticeSelector.kt`: pure eligibility and newest-only selection.
- `app/src/main/java/com/nexio/tv/notices/RemoteNoticePreferences.kt`: app-wide DataStore for baseline and seen IDs.
- `app/src/main/java/com/nexio/tv/notices/RemoteNoticeRepository.kt`: orchestrates manifest fetch, baseline, filtering, Markdown fetch, and display model creation.
- `app/src/main/java/com/nexio/tv/notices/RemoteNoticeViewModel.kt`: startup check and dismiss/seen behavior.
- `app/src/main/java/com/nexio/tv/notices/ui/RemoteNoticeDialog.kt`: TV modal with Markdown body and Close action.
- `app/src/test/java/com/nexio/tv/data/integration/github/GitHubRawContentIntegrationProviderTest.kt`
- `app/src/test/java/com/nexio/tv/notices/RemoteNoticeSelectorTest.kt`
- `app/src/test/java/com/nexio/tv/notices/RemoteNoticePreferencesTest.kt`
- `app/src/test/java/com/nexio/tv/notices/RemoteNoticeRepositoryTest.kt`
- `app/src/test/java/com/nexio/tv/notices/RemoteNoticeViewModelTest.kt`
- `app/src/androidTest/java/com/nexio/tv/notices/ui/RemoteNoticeDialogTest.kt`

Modify:

- `app/build.gradle.kts`: add `BuildConfig.NOTICES_MANIFEST_URL`.
- `app/src/main/java/com/nexio/tv/core/integration/IntegrationApiShapes.kt`: add notice API shape IDs.
- `app/src/main/java/com/nexio/tv/core/integration/IntegrationNetworkPermit.kt`: include `raw.githubusercontent.com` in scoped GitHub hosts.
- `app/src/main/java/com/nexio/tv/core/di/NetworkModule.kt`: provide `GitHubRawContentApi`.
- `app/src/main/java/com/nexio/tv/MainActivity.kt`: collect notice state, render dialog, and expose a pure gate helper.
- `app/src/main/res/values/strings.xml`: add notice dialog strings.
- `app/src/main/res/values-de/strings.xml`, `app/src/main/res/values-es/strings.xml`, `app/src/main/res/values-fr/strings.xml`, `app/src/main/res/values-nl/strings.xml`, `app/src/main/res/values-zh-rCN/strings.xml`: add English fallback values unless this project already localizes new strings in the same PR.
- `app/src/test/java/com/nexio/tv/MainActivityIdleScreensaverTest.kt`: add remote notice gate tests.

Do not touch the unrelated dirty files currently in the working tree unless the implementation task explicitly needs them.

---

### Task 1: Raw GitHub Text Integration

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/java/com/nexio/tv/core/integration/IntegrationApiShapes.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/integration/IntegrationNetworkPermit.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/di/NetworkModule.kt`
- Create: `app/src/main/java/com/nexio/tv/data/remote/api/GitHubRawContentApi.kt`
- Create: `app/src/main/java/com/nexio/tv/data/integration/github/GitHubRawContentIntegrationProvider.kt`
- Test: `app/src/test/java/com/nexio/tv/data/integration/github/GitHubRawContentIntegrationProviderTest.kt`

- [ ] **Step 1: Write the failing provider tests**

Create `app/src/test/java/com/nexio/tv/data/integration/github/GitHubRawContentIntegrationProviderTest.kt`:

```kotlin
package com.nexio.tv.data.integration.github

import com.nexio.tv.core.integration.GitHubApiShapes
import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.IntegrationCallSpec
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.data.remote.api.GitHubRawContentApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.Response
import java.io.IOException

class GitHubRawContentIntegrationProviderTest {

    @Test
    fun `fetchText routes manifest request through integration runtime`() = runTest {
        val runtime = mockk<IntegrationRuntime>(relaxed = true)
        val api = mockk<GitHubRawContentApi>()
        val specSlot = slot<IntegrationCallSpec<String>>()

        coEvery { runtime.call(capture(specSlot)) } coAnswers {
            firstArg<IntegrationCallSpec<String>>().call()
        }
        coEvery { api.getText("https://raw.githubusercontent.com/johnneerdael/nexio/main/notices/manifest.json") } returns
            Response.success("""{"schemaVersion":1}""".toResponseBody("application/json".toMediaType()))

        val provider = GitHubRawContentIntegrationProvider(runtime, api)
        val result = provider.fetchNoticeManifest(
            "https://raw.githubusercontent.com/johnneerdael/nexio/main/notices/manifest.json"
        )

        assertTrue(result is IntegrationCallResult.Success)
        assertEquals("""{"schemaVersion":1}""", (result as IntegrationCallResult.Success).value)
        assertEquals(IntegrationProvider.GITHUB, specSlot.captured.provider)
        assertEquals(GitHubApiShapes.NOTICE_MANIFEST, specSlot.captured.apiShapeId)
        assertEquals("github.notice.fetchManifest", specSlot.captured.operationKey)
        assertEquals(IntegrationWorkClass.USER_VISIBLE, specSlot.captured.workClass)
        assertEquals(IntegrationScope.ProviderConfig("github:notices"), specSlot.captured.scope)
        coVerify(exactly = 1) { api.getText("https://raw.githubusercontent.com/johnneerdael/nexio/main/notices/manifest.json") }
    }

    @Test
    fun `fetchText maps http error`() = runTest {
        val runtime = mockk<IntegrationRuntime>(relaxed = true)
        val api = mockk<GitHubRawContentApi>()
        coEvery { runtime.call(any<IntegrationCallSpec<String>>()) } coAnswers {
            firstArg<IntegrationCallSpec<String>>().call()
        }
        coEvery { api.getText("https://raw.githubusercontent.com/johnneerdael/nexio/main/notices/a.md") } returns
            Response.error(404, ByteArray(0).toResponseBody("text/plain".toMediaType()))

        val result = GitHubRawContentIntegrationProvider(runtime, api)
            .fetchNoticeMarkdown("https://raw.githubusercontent.com/johnneerdael/nexio/main/notices/a.md")

        assertTrue(result is IntegrationCallResult.HttpError)
        assertEquals(404, (result as IntegrationCallResult.HttpError).statusCode)
    }

    @Test
    fun `fetchText maps network error`() = runTest {
        val runtime = mockk<IntegrationRuntime>(relaxed = true)
        val api = mockk<GitHubRawContentApi>()
        coEvery { runtime.call(any<IntegrationCallSpec<String>>()) } coAnswers {
            firstArg<IntegrationCallSpec<String>>().call()
        }
        coEvery { api.getText(any()) } throws IOException("offline")

        val result = GitHubRawContentIntegrationProvider(runtime, api)
            .fetchNoticeManifest("https://raw.githubusercontent.com/johnneerdael/nexio/main/notices/manifest.json")

        assertTrue(result is IntegrationCallResult.NetworkError)
        assertEquals("offline", (result as IntegrationCallResult.NetworkError).throwable.message)
    }

    @Test
    fun `fetchText rethrows cancellation`() = runTest {
        val runtime = mockk<IntegrationRuntime>(relaxed = true)
        val api = mockk<GitHubRawContentApi>()
        coEvery { runtime.call(any<IntegrationCallSpec<String>>()) } coAnswers {
            firstArg<IntegrationCallSpec<String>>().call()
        }
        coEvery { api.getText(any()) } throws CancellationException("cancelled")

        try {
            GitHubRawContentIntegrationProvider(runtime, api)
                .fetchNoticeManifest("https://raw.githubusercontent.com/johnneerdael/nexio/main/notices/manifest.json")
            fail("Expected CancellationException")
        } catch (exception: CancellationException) {
            assertEquals("cancelled", exception.message)
        }
    }
}
```

- [ ] **Step 2: Run the failing tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.integration.github.GitHubRawContentIntegrationProviderTest"
```

Expected: FAIL with unresolved references for `GitHubRawContentApi`, `GitHubRawContentIntegrationProvider`, and notice `GitHubApiShapes`.

- [ ] **Step 3: Add the raw content API and integration provider**

Add to `app/build.gradle.kts` inside the same `defaultConfig` block that already defines `GITHUB_OWNER` and `GITHUB_REPO`:

```kotlin
buildConfigField(
    "String",
    "NOTICES_MANIFEST_URL",
    "\"https://raw.githubusercontent.com/johnneerdael/nexio/main/notices/manifest.json\""
)
```

In `app/src/main/java/com/nexio/tv/core/integration/IntegrationApiShapes.kt`, extend `GitHubApiShapes`:

```kotlin
object GitHubApiShapes {
    const val LATEST_RELEASE = "github.latest_release"
    const val ASSET_DOWNLOAD = "github.asset_download"
    const val NOTICE_MANIFEST = "github.notice_manifest"
    const val NOTICE_MARKDOWN = "github.notice_markdown"
}
```

In `app/src/main/java/com/nexio/tv/core/integration/IntegrationNetworkPermit.kt`, add `raw.githubusercontent.com` to the default in-scope host set beside `api.github.com`:

```kotlin
"api.github.com",
"raw.githubusercontent.com"
```

Create `app/src/main/java/com/nexio/tv/data/remote/api/GitHubRawContentApi.kt`:

```kotlin
package com.nexio.tv.data.remote.api

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Url

interface GitHubRawContentApi {
    @GET
    suspend fun getText(@Url url: String): Response<ResponseBody>
}
```

In `app/src/main/java/com/nexio/tv/core/di/NetworkModule.kt`, add the import and provider near `provideGitHubReleaseApi`:

```kotlin
import com.nexio.tv.data.remote.api.GitHubRawContentApi
```

```kotlin
@Provides
@Singleton
fun provideGitHubRawContentApi(@Named("github") retrofit: Retrofit): GitHubRawContentApi =
    retrofit.create(GitHubRawContentApi::class.java)
```

Create `app/src/main/java/com/nexio/tv/data/integration/github/GitHubRawContentIntegrationProvider.kt`:

```kotlin
package com.nexio.tv.data.integration.github

import com.nexio.tv.core.integration.GitHubApiShapes
import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.IntegrationCallSpec
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.data.remote.api.GitHubRawContentApi
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

@Singleton
class GitHubRawContentIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val gitHubRawContentApi: GitHubRawContentApi
) {
    suspend fun fetchNoticeManifest(url: String): IntegrationCallResult<String> =
        fetchText(
            url = url,
            apiShapeId = GitHubApiShapes.NOTICE_MANIFEST,
            operationKey = "github.notice.fetchManifest",
            reason = "github_notice_manifest_failed"
        )

    suspend fun fetchNoticeMarkdown(url: String): IntegrationCallResult<String> =
        fetchText(
            url = url,
            apiShapeId = GitHubApiShapes.NOTICE_MARKDOWN,
            operationKey = "github.notice.fetchMarkdown",
            reason = "github_notice_markdown_failed"
        )

    private suspend fun fetchText(
        url: String,
        apiShapeId: String,
        operationKey: String,
        reason: String
    ): IntegrationCallResult<String> {
        return runtime.call(
            IntegrationCallSpec(
                provider = IntegrationProvider.GITHUB,
                workClass = IntegrationWorkClass.USER_VISIBLE,
                scope = IntegrationScope.ProviderConfig("github:notices"),
                apiShapeId = apiShapeId,
                operationKey = operationKey,
                call = {
                    try {
                        val response = gitHubRawContentApi.getText(url)
                        val body = response.body()
                        when {
                            !response.isSuccessful -> IntegrationCallResult.HttpError(
                                statusCode = response.code(),
                                reason = reason
                            )
                            body == null -> IntegrationCallResult.Missing
                            else -> IntegrationCallResult.Success(body.string())
                        }
                    } catch (exception: Exception) {
                        if (exception is CancellationException) throw exception
                        IntegrationCallResult.NetworkError(exception)
                    }
                }
            )
        )
    }
}
```

- [ ] **Step 4: Run the provider tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.data.integration.github.GitHubRawContentIntegrationProviderTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts \
  app/src/main/java/com/nexio/tv/core/integration/IntegrationApiShapes.kt \
  app/src/main/java/com/nexio/tv/core/integration/IntegrationNetworkPermit.kt \
  app/src/main/java/com/nexio/tv/core/di/NetworkModule.kt \
  app/src/main/java/com/nexio/tv/data/remote/api/GitHubRawContentApi.kt \
  app/src/main/java/com/nexio/tv/data/integration/github/GitHubRawContentIntegrationProvider.kt \
  app/src/test/java/com/nexio/tv/data/integration/github/GitHubRawContentIntegrationProviderTest.kt
git commit -m "feat: add raw github notice fetcher"
```

---

### Task 2: Notice Models And Eligibility Selector

**Files:**
- Create: `app/src/main/java/com/nexio/tv/notices/model/RemoteNoticeModels.kt`
- Create: `app/src/main/java/com/nexio/tv/notices/RemoteNoticeSelector.kt`
- Test: `app/src/test/java/com/nexio/tv/notices/RemoteNoticeSelectorTest.kt`

- [ ] **Step 1: Write selector tests**

Create `app/src/test/java/com/nexio/tv/notices/RemoteNoticeSelectorTest.kt`:

```kotlin
package com.nexio.tv.notices

import com.nexio.tv.notices.model.RemoteNoticeManifest
import com.nexio.tv.notices.model.RemoteNoticeManifestItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class RemoteNoticeSelectorTest {
    private val now = Instant.parse("2026-05-12T12:00:00Z")
    private val baseline = Instant.parse("2026-05-12T10:00:00Z")

    @Test
    fun `first install baseline suppresses existing notices`() {
        val selected = RemoteNoticeSelector.selectNewestEligible(
            manifest = manifest(
                notice("old", "2026-05-12T09:00:00Z"),
                notice("same", "2026-05-12T10:00:00Z")
            ),
            now = baseline,
            baselineAt = baseline,
            seenIds = emptySet(),
            appVersion = "1.5.0"
        )

        assertNull(selected)
    }

    @Test
    fun `notice published after baseline is eligible`() {
        val selected = RemoteNoticeSelector.selectNewestEligible(
            manifest = manifest(notice("new", "2026-05-12T10:01:00Z")),
            now = now,
            baselineAt = baseline,
            seenIds = emptySet(),
            appVersion = "1.5.0"
        )

        assertEquals("new", selected?.id)
    }

    @Test
    fun `seen notice is skipped`() {
        val selected = RemoteNoticeSelector.selectNewestEligible(
            manifest = manifest(notice("new", "2026-05-12T10:01:00Z")),
            now = now,
            baselineAt = baseline,
            seenIds = setOf("new"),
            appVersion = "1.5.0"
        )

        assertNull(selected)
    }

    @Test
    fun `version and expiry filters are applied`() {
        val selected = RemoteNoticeSelector.selectNewestEligible(
            manifest = manifest(
                notice("too-low", "2026-05-12T10:01:00Z", minVersion = "2.0.0"),
                notice("too-high", "2026-05-12T10:02:00Z", maxVersion = "1.4.9"),
                notice("expired", "2026-05-12T10:03:00Z", expiresAt = "2026-05-12T11:00:00Z"),
                notice("valid", "2026-05-12T10:04:00Z", minVersion = "1.4.0", maxVersion = "1.9.0")
            ),
            now = now,
            baselineAt = baseline,
            seenIds = emptySet(),
            appVersion = "1.5.0"
        )

        assertEquals("valid", selected?.id)
    }

    @Test
    fun `future notices are skipped`() {
        val selected = RemoteNoticeSelector.selectNewestEligible(
            manifest = manifest(notice("future", "2026-05-12T13:00:00Z")),
            now = now,
            baselineAt = baseline,
            seenIds = emptySet(),
            appVersion = "1.5.0"
        )

        assertNull(selected)
    }

    @Test
    fun `newest notice wins with id tie break`() {
        val selected = RemoteNoticeSelector.selectNewestEligible(
            manifest = manifest(
                notice("b", "2026-05-12T10:10:00Z"),
                notice("a", "2026-05-12T10:10:00Z"),
                notice("older", "2026-05-12T10:09:00Z")
            ),
            now = now,
            baselineAt = baseline,
            seenIds = emptySet(),
            appVersion = "1.5.0"
        )

        assertEquals("a", selected?.id)
    }

    @Test
    fun `invalid manifest and invalid urls return no selection`() {
        assertNull(
            RemoteNoticeSelector.selectNewestEligible(
                manifest = RemoteNoticeManifest(schemaVersion = 2, notices = listOf(notice("new", "2026-05-12T10:01:00Z"))),
                now = now,
                baselineAt = baseline,
                seenIds = emptySet(),
                appVersion = "1.5.0"
            )
        )

        assertNull(
            RemoteNoticeSelector.selectNewestEligible(
                manifest = manifest(notice("bad-url", "2026-05-12T10:01:00Z", markdownUrl = "http://example.com/a.md")),
                now = now,
                baselineAt = baseline,
                seenIds = emptySet(),
                appVersion = "1.5.0"
            )
        )
    }

    private fun manifest(vararg notices: RemoteNoticeManifestItem) =
        RemoteNoticeManifest(schemaVersion = 1, notices = notices.toList())

    private fun notice(
        id: String,
        publishedAt: String,
        title: String = "Notice $id",
        markdownUrl: String = "https://raw.githubusercontent.com/johnneerdael/nexio/main/notices/$id.md",
        minVersion: String? = null,
        maxVersion: String? = null,
        expiresAt: String? = null
    ) = RemoteNoticeManifestItem(
        id = id,
        title = title,
        publishedAt = publishedAt,
        markdownUrl = markdownUrl,
        minVersion = minVersion,
        maxVersion = maxVersion,
        expiresAt = expiresAt
    )
}
```

- [ ] **Step 2: Run selector tests to verify failure**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.notices.RemoteNoticeSelectorTest"
```

Expected: FAIL with unresolved references for `RemoteNoticeManifest`, `RemoteNoticeManifestItem`, and `RemoteNoticeSelector`.

- [ ] **Step 3: Add models and selector**

Create `app/src/main/java/com/nexio/tv/notices/model/RemoteNoticeModels.kt`:

```kotlin
package com.nexio.tv.notices.model

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class RemoteNoticeManifest(
    val schemaVersion: Int,
    val notices: List<RemoteNoticeManifestItem> = emptyList()
)

@JsonClass(generateAdapter = true)
data class RemoteNoticeManifestItem(
    val id: String,
    val title: String,
    val publishedAt: String,
    val markdownUrl: String,
    val minVersion: String? = null,
    val maxVersion: String? = null,
    val expiresAt: String? = null
)

data class RemoteNoticeDisplay(
    val id: String,
    val title: String,
    val markdown: String,
    val markdownUrl: String,
    val publishedAt: String
)
```

Create `app/src/main/java/com/nexio/tv/notices/RemoteNoticeSelector.kt`:

```kotlin
package com.nexio.tv.notices

import com.nexio.tv.notices.model.RemoteNoticeManifest
import com.nexio.tv.notices.model.RemoteNoticeManifestItem
import com.nexio.tv.updater.VersionUtils
import java.time.Instant

internal object RemoteNoticeSelector {
    fun selectNewestEligible(
        manifest: RemoteNoticeManifest,
        now: Instant,
        baselineAt: Instant,
        seenIds: Set<String>,
        appVersion: String
    ): RemoteNoticeManifestItem? {
        if (manifest.schemaVersion != 1) return null

        return manifest.notices
            .asSequence()
            .mapNotNull { item -> item.toCandidateOrNull() }
            .filter { candidate -> !candidate.publishedAt.isAfter(now) }
            .filter { candidate -> candidate.publishedAt.isAfter(baselineAt) }
            .filter { candidate -> candidate.expiresAt == null || candidate.expiresAt.isAfter(now) }
            .filter { candidate -> candidate.item.id !in seenIds }
            .filter { candidate -> candidate.item.minVersion?.let { min -> !VersionUtils.isRemoteNewer(min, appVersion) } ?: true }
            .filter { candidate -> candidate.item.maxVersion?.let { max -> !VersionUtils.isRemoteNewer(appVersion, max) } ?: true }
            .sortedWith(compareByDescending<RemoteNoticeCandidate> { it.publishedAt }.thenBy { it.item.id })
            .firstOrNull()
            ?.item
    }

    private fun RemoteNoticeManifestItem.toCandidateOrNull(): RemoteNoticeCandidate? {
        val cleanId = id.trim()
        val cleanTitle = title.trim()
        val cleanUrl = markdownUrl.trim()
        if (cleanId.isBlank() || cleanTitle.isBlank() || cleanUrl.isBlank()) return null
        if (!cleanUrl.startsWith("https://", ignoreCase = true)) return null

        val publishedInstant = runCatching { Instant.parse(publishedAt.trim()) }.getOrNull() ?: return null
        val expiresInstant = expiresAt?.trim()?.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching { Instant.parse(raw) }.getOrNull() ?: return null
        }

        return RemoteNoticeCandidate(
            item = copy(
                id = cleanId,
                title = cleanTitle,
                publishedAt = publishedAt.trim(),
                markdownUrl = cleanUrl,
                minVersion = minVersion?.trim()?.takeIf { it.isNotBlank() },
                maxVersion = maxVersion?.trim()?.takeIf { it.isNotBlank() },
                expiresAt = expiresAt?.trim()?.takeIf { it.isNotBlank() }
            ),
            publishedAt = publishedInstant,
            expiresAt = expiresInstant
        )
    }
}

private data class RemoteNoticeCandidate(
    val item: RemoteNoticeManifestItem,
    val publishedAt: Instant,
    val expiresAt: Instant?
)
```

- [ ] **Step 4: Run selector tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.notices.RemoteNoticeSelectorTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/notices/model/RemoteNoticeModels.kt \
  app/src/main/java/com/nexio/tv/notices/RemoteNoticeSelector.kt \
  app/src/test/java/com/nexio/tv/notices/RemoteNoticeSelectorTest.kt
git commit -m "feat: add remote notice eligibility selector"
```

---

### Task 3: App-Wide Notice Preferences

**Files:**
- Create: `app/src/main/java/com/nexio/tv/notices/RemoteNoticePreferences.kt`
- Test: `app/src/test/java/com/nexio/tv/notices/RemoteNoticePreferencesTest.kt`

- [ ] **Step 1: Write preferences tests**

Create `app/src/test/java/com/nexio/tv/notices/RemoteNoticePreferencesTest.kt`:

```kotlin
package com.nexio.tv.notices

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

class RemoteNoticePreferencesTest {

    @Test
    fun `baseline is absent by default and can be set once`() = runTest {
        val prefs = RemoteNoticePreferences(createDataStore())

        assertNull(prefs.noticeBaselineAt.first())

        val baseline = Instant.parse("2026-05-12T10:00:00Z")
        prefs.setNoticeBaselineAtIfAbsent(baseline)
        prefs.setNoticeBaselineAtIfAbsent(Instant.parse("2026-05-13T10:00:00Z"))

        assertEquals(baseline, prefs.noticeBaselineAt.first())
    }

    @Test
    fun `seen ids accumulate without duplicates`() = runTest {
        val prefs = RemoteNoticePreferences(createDataStore())

        assertTrue(prefs.seenNoticeIds.first().isEmpty())

        prefs.markSeen("a")
        prefs.markSeen("b")
        prefs.markSeen("a")

        assertEquals(setOf("a", "b"), prefs.seenNoticeIds.first())
    }

    @Test
    fun `last check timestamp is stored`() = runTest {
        val prefs = RemoteNoticePreferences(createDataStore())

        prefs.setLastCheckAtMs(1234L)

        assertEquals(1234L, prefs.lastCheckAtMs.first())
    }

    private fun createDataStore(): DataStore<Preferences> {
        val tempFile = File.createTempFile("remote_notice_test", ".preferences_pb")
        tempFile.deleteOnExit()
        return PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { tempFile }
        )
    }
}
```

- [ ] **Step 2: Run preferences tests to verify failure**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.notices.RemoteNoticePreferencesTest"
```

Expected: FAIL with unresolved reference `RemoteNoticePreferences`.

- [ ] **Step 3: Add preferences implementation**

Create `app/src/main/java/com/nexio/tv/notices/RemoteNoticePreferences.kt`:

```kotlin
package com.nexio.tv.notices

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

private val Context.remoteNoticeDataStore: DataStore<Preferences> by preferencesDataStore(name = "remote_notice_settings")

@Singleton
class RemoteNoticePreferences {
    private val dataStore: DataStore<Preferences>

    @Inject
    constructor(@ApplicationContext context: Context) {
        dataStore = context.remoteNoticeDataStore
    }

    internal constructor(dataStore: DataStore<Preferences>) {
        this.dataStore = dataStore
    }

    private val baselineAtMsKey = longPreferencesKey("notice_baseline_at_ms")
    private val seenNoticeIdsKey = stringSetPreferencesKey("seen_notice_ids")
    private val lastCheckAtMsKey = longPreferencesKey("last_check_at_ms")

    val noticeBaselineAt: Flow<Instant?> = dataStore.data.map { prefs ->
        prefs[baselineAtMsKey]?.let(Instant::ofEpochMilli)
    }

    val seenNoticeIds: Flow<Set<String>> = dataStore.data.map { prefs ->
        prefs[seenNoticeIdsKey].orEmpty()
    }

    val lastCheckAtMs: Flow<Long> = dataStore.data.map { prefs ->
        prefs[lastCheckAtMsKey] ?: 0L
    }

    suspend fun setNoticeBaselineAtIfAbsent(value: Instant) {
        dataStore.edit { prefs ->
            if (prefs[baselineAtMsKey] == null) {
                prefs[baselineAtMsKey] = value.toEpochMilli()
            }
        }
    }

    suspend fun markSeen(id: String) {
        val cleanId = id.trim()
        if (cleanId.isBlank()) return
        dataStore.edit { prefs ->
            prefs[seenNoticeIdsKey] = prefs[seenNoticeIdsKey].orEmpty() + cleanId
        }
    }

    suspend fun setLastCheckAtMs(value: Long) {
        dataStore.edit { prefs ->
            prefs[lastCheckAtMsKey] = value
        }
    }
}
```

- [ ] **Step 4: Run preferences tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.notices.RemoteNoticePreferencesTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/notices/RemoteNoticePreferences.kt \
  app/src/test/java/com/nexio/tv/notices/RemoteNoticePreferencesTest.kt
git commit -m "feat: persist remote notice state"
```

---

### Task 4: Remote Notice Repository

**Files:**
- Create: `app/src/main/java/com/nexio/tv/notices/RemoteNoticeRepository.kt`
- Test: `app/src/test/java/com/nexio/tv/notices/RemoteNoticeRepositoryTest.kt`

- [ ] **Step 1: Write repository tests**

Create `app/src/test/java/com/nexio/tv/notices/RemoteNoticeRepositoryTest.kt`:

```kotlin
package com.nexio.tv.notices

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.data.integration.github.GitHubRawContentIntegrationProvider
import com.squareup.moshi.Moshi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

class RemoteNoticeRepositoryTest {
    private val manifestUrl = "https://raw.githubusercontent.com/johnneerdael/nexio/main/notices/manifest.json"
    private val markdownUrl = "https://raw.githubusercontent.com/johnneerdael/nexio/main/notices/new.md"
    private val now = Instant.parse("2026-05-12T12:00:00Z")

    @Test
    fun `first successful fetch sets baseline and suppresses existing notices`() = runTest {
        val provider = mockProvider(
            manifest = manifestJson(
                id = "old",
                publishedAt = "2026-05-12T11:59:00Z",
                markdownUrl = markdownUrl
            ),
            markdown = "# Old"
        )
        val prefs = RemoteNoticePreferences(createDataStore())
        val repo = repository(provider, prefs)

        val notice = repo.fetchStartupNotice(now = now)

        assertNull(notice)
        assertEquals(now, prefs.noticeBaselineAt.first())
        coVerify(exactly = 0) { provider.fetchNoticeMarkdown(any()) }
    }

    @Test
    fun `notice after existing baseline returns display model`() = runTest {
        val provider = mockProvider(
            manifest = manifestJson(
                id = "new",
                title = "Important",
                publishedAt = "2026-05-12T12:01:00Z",
                markdownUrl = markdownUrl
            ),
            markdown = "# Important\n\n![Image](https://raw.githubusercontent.com/johnneerdael/nexio/main/notices/images/a.png)"
        )
        val prefs = RemoteNoticePreferences(createDataStore())
        prefs.setNoticeBaselineAtIfAbsent(Instant.parse("2026-05-12T12:00:00Z"))
        val repo = repository(provider, prefs)

        val notice = repo.fetchStartupNotice(now = Instant.parse("2026-05-12T12:02:00Z"))

        assertEquals("new", notice?.id)
        assertEquals("Important", notice?.title)
        assertTrue(notice?.markdown.orEmpty().contains("![Image]"))
        assertEquals(markdownUrl, notice?.markdownUrl)
    }

    @Test
    fun `seen notice and markdown fetch failure return no display model`() = runTest {
        val provider = mockProvider(
            manifest = manifestJson(id = "new", publishedAt = "2026-05-12T12:01:00Z", markdownUrl = markdownUrl),
            markdown = "# Important"
        )
        val prefs = RemoteNoticePreferences(createDataStore())
        prefs.setNoticeBaselineAtIfAbsent(Instant.parse("2026-05-12T12:00:00Z"))
        prefs.markSeen("new")

        assertNull(repository(provider, prefs).fetchStartupNotice(now = Instant.parse("2026-05-12T12:02:00Z")))

        val failingMarkdownProvider = mockk<GitHubRawContentIntegrationProvider>()
        coEvery { failingMarkdownProvider.fetchNoticeManifest(manifestUrl) } returns IntegrationCallResult.Success(
            manifestJson(id = "new", publishedAt = "2026-05-12T12:01:00Z", markdownUrl = markdownUrl)
        )
        coEvery { failingMarkdownProvider.fetchNoticeMarkdown(markdownUrl) } returns IntegrationCallResult.HttpError(404)

        val prefs2 = RemoteNoticePreferences(createDataStore())
        prefs2.setNoticeBaselineAtIfAbsent(Instant.parse("2026-05-12T12:00:00Z"))

        assertNull(repository(failingMarkdownProvider, prefs2).fetchStartupNotice(now = Instant.parse("2026-05-12T12:02:00Z")))
        assertTrue(prefs2.seenNoticeIds.first().isEmpty())
    }

    @Test
    fun `malformed manifest does not set baseline`() = runTest {
        val provider = mockk<GitHubRawContentIntegrationProvider>()
        coEvery { provider.fetchNoticeManifest(manifestUrl) } returns IntegrationCallResult.Success("{")
        val prefs = RemoteNoticePreferences(createDataStore())

        val notice = repository(provider, prefs).fetchStartupNotice(now = now)

        assertNull(notice)
        assertNull(prefs.noticeBaselineAt.first())
    }

    private fun repository(
        provider: GitHubRawContentIntegrationProvider,
        prefs: RemoteNoticePreferences
    ) = RemoteNoticeRepository(
        gitHubRawContentIntegrationProvider = provider,
        remoteNoticePreferences = prefs,
        moshi = Moshi.Builder().build(),
        manifestUrl = manifestUrl,
        appVersion = "1.5.0"
    )

    private fun mockProvider(manifest: String, markdown: String): GitHubRawContentIntegrationProvider {
        val provider = mockk<GitHubRawContentIntegrationProvider>()
        coEvery { provider.fetchNoticeManifest(manifestUrl) } returns IntegrationCallResult.Success(manifest)
        coEvery { provider.fetchNoticeMarkdown(markdownUrl) } returns IntegrationCallResult.Success(markdown)
        return provider
    }

    private fun manifestJson(
        id: String,
        title: String = "Notice",
        publishedAt: String,
        markdownUrl: String
    ): String = """
        {
          "schemaVersion": 1,
          "notices": [
            {
              "id": "$id",
              "title": "$title",
              "publishedAt": "$publishedAt",
              "markdownUrl": "$markdownUrl"
            }
          ]
        }
    """.trimIndent()

    private fun createDataStore(): DataStore<Preferences> {
        val tempFile = File.createTempFile("remote_notice_repo_test", ".preferences_pb")
        tempFile.deleteOnExit()
        return PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { tempFile }
        )
    }
}
```

- [ ] **Step 2: Run repository tests to verify failure**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.notices.RemoteNoticeRepositoryTest"
```

Expected: FAIL with unresolved reference `RemoteNoticeRepository`.

- [ ] **Step 3: Add repository implementation**

Create `app/src/main/java/com/nexio/tv/notices/RemoteNoticeRepository.kt`:

```kotlin
package com.nexio.tv.notices

import com.nexio.tv.BuildConfig
import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.data.integration.github.GitHubRawContentIntegrationProvider
import com.nexio.tv.notices.model.RemoteNoticeDisplay
import com.nexio.tv.notices.model.RemoteNoticeManifest
import com.squareup.moshi.Moshi
import kotlinx.coroutines.flow.first
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

@Singleton
class RemoteNoticeRepository @Inject constructor(
    private val gitHubRawContentIntegrationProvider: GitHubRawContentIntegrationProvider,
    private val remoteNoticePreferences: RemoteNoticePreferences,
    private val moshi: Moshi
) {
    private val manifestUrl: String = BuildConfig.NOTICES_MANIFEST_URL
    private val appVersion: String = BuildConfig.VERSION_NAME

    internal constructor(
        gitHubRawContentIntegrationProvider: GitHubRawContentIntegrationProvider,
        remoteNoticePreferences: RemoteNoticePreferences,
        moshi: Moshi,
        manifestUrl: String,
        appVersion: String
    ) : this(gitHubRawContentIntegrationProvider, remoteNoticePreferences, moshi) {
        testManifestUrl = manifestUrl
        testAppVersion = appVersion
    }

    private var testManifestUrl: String? = null
    private var testAppVersion: String? = null

    suspend fun fetchStartupNotice(now: Instant = Instant.now()): RemoteNoticeDisplay? {
        return try {
            remoteNoticePreferences.setLastCheckAtMs(now.toEpochMilli())
            val manifestText = when (val result = gitHubRawContentIntegrationProvider.fetchNoticeManifest(activeManifestUrl())) {
                is IntegrationCallResult.Success -> result.value
                is IntegrationCallResult.HttpError -> return null
                is IntegrationCallResult.NetworkError -> return null
                IntegrationCallResult.Missing -> return null
            }

            val manifest = runCatching {
                moshi.adapter(RemoteNoticeManifest::class.java).fromJson(manifestText)
            }.getOrNull() ?: return null

            val existingBaseline = remoteNoticePreferences.noticeBaselineAt.first()
            val baselineAt = existingBaseline ?: now.also {
                remoteNoticePreferences.setNoticeBaselineAtIfAbsent(it)
            }

            val selected = RemoteNoticeSelector.selectNewestEligible(
                manifest = manifest,
                now = now,
                baselineAt = baselineAt,
                seenIds = remoteNoticePreferences.seenNoticeIds.first(),
                appVersion = activeAppVersion()
            ) ?: return null

            val markdown = when (val result = gitHubRawContentIntegrationProvider.fetchNoticeMarkdown(selected.markdownUrl)) {
                is IntegrationCallResult.Success -> result.value.trim().takeIf { it.isNotBlank() } ?: return null
                is IntegrationCallResult.HttpError -> return null
                is IntegrationCallResult.NetworkError -> return null
                IntegrationCallResult.Missing -> return null
            }

            RemoteNoticeDisplay(
                id = selected.id,
                title = selected.title,
                markdown = markdown,
                markdownUrl = selected.markdownUrl,
                publishedAt = selected.publishedAt
            )
        } catch (exception: Exception) {
            if (exception is CancellationException) throw exception
            null
        }
    }

    private fun activeManifestUrl(): String = testManifestUrl ?: manifestUrl

    private fun activeAppVersion(): String = testAppVersion ?: appVersion
}
```

- [ ] **Step 4: Run repository tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.notices.RemoteNoticeRepositoryTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/notices/RemoteNoticeRepository.kt \
  app/src/test/java/com/nexio/tv/notices/RemoteNoticeRepositoryTest.kt
git commit -m "feat: resolve startup remote notices"
```

---

### Task 5: Remote Notice ViewModel

**Files:**
- Create: `app/src/main/java/com/nexio/tv/notices/RemoteNoticeViewModel.kt`
- Test: `app/src/test/java/com/nexio/tv/notices/RemoteNoticeViewModelTest.kt`

- [ ] **Step 1: Write ViewModel tests**

Create `app/src/test/java/com/nexio/tv/notices/RemoteNoticeViewModelTest.kt`:

```kotlin
package com.nexio.tv.notices

import com.nexio.tv.notices.model.RemoteNoticeDisplay
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteNoticeViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `init fetches notice and shows dialog`() = runTest(dispatcher) {
        val repository = mockk<RemoteNoticeRepository>()
        val preferences = mockk<RemoteNoticePreferences>(relaxed = true)
        coEvery { repository.fetchStartupNotice() } returns display()

        val viewModel = RemoteNoticeViewModel(repository, preferences)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.showDialog)
        assertEquals("notice-1", viewModel.uiState.value.notice?.id)
    }

    @Test
    fun `dismiss marks current notice seen`() = runTest(dispatcher) {
        val repository = mockk<RemoteNoticeRepository>()
        val preferences = mockk<RemoteNoticePreferences>(relaxed = true)
        coEvery { repository.fetchStartupNotice() } returns display()

        val viewModel = RemoteNoticeViewModel(repository, preferences)
        advanceUntilIdle()

        viewModel.dismissNotice()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showDialog)
        coVerify(exactly = 1) { preferences.markSeen("notice-1") }
    }

    @Test
    fun `suppress for startup hides without marking seen`() = runTest(dispatcher) {
        val repository = mockk<RemoteNoticeRepository>()
        val preferences = mockk<RemoteNoticePreferences>(relaxed = true)
        coEvery { repository.fetchStartupNotice() } returns display()

        val viewModel = RemoteNoticeViewModel(repository, preferences)
        advanceUntilIdle()

        viewModel.suppressForStartup()

        assertFalse(viewModel.uiState.value.showDialog)
        coVerify(exactly = 0) { preferences.markSeen(any()) }
    }

    private fun display() = RemoteNoticeDisplay(
        id = "notice-1",
        title = "Important",
        markdown = "# Important",
        markdownUrl = "https://raw.githubusercontent.com/johnneerdael/nexio/main/notices/notice-1.md",
        publishedAt = "2026-05-12T12:01:00Z"
    )
}
```

- [ ] **Step 2: Run ViewModel tests to verify failure**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.notices.RemoteNoticeViewModelTest"
```

Expected: FAIL with unresolved references for `RemoteNoticeViewModel` and `RemoteNoticeUiState`.

- [ ] **Step 3: Add ViewModel implementation**

Create `app/src/main/java/com/nexio/tv/notices/RemoteNoticeViewModel.kt`:

```kotlin
package com.nexio.tv.notices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexio.tv.notices.model.RemoteNoticeDisplay
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RemoteNoticeUiState(
    val isChecking: Boolean = false,
    val notice: RemoteNoticeDisplay? = null,
    val showDialog: Boolean = false
)

@HiltViewModel
class RemoteNoticeViewModel @Inject constructor(
    private val remoteNoticeRepository: RemoteNoticeRepository,
    private val remoteNoticePreferences: RemoteNoticePreferences
) : ViewModel() {
    private val _uiState = MutableStateFlow(RemoteNoticeUiState())
    val uiState: StateFlow<RemoteNoticeUiState> = _uiState.asStateFlow()

    init {
        checkForNotice()
    }

    fun checkForNotice() {
        viewModelScope.launch {
            _uiState.update { it.copy(isChecking = true) }
            val notice = remoteNoticeRepository.fetchStartupNotice()
            _uiState.update {
                it.copy(
                    isChecking = false,
                    notice = notice,
                    showDialog = notice != null
                )
            }
        }
    }

    fun dismissNotice() {
        val noticeId = _uiState.value.notice?.id
        viewModelScope.launch {
            if (noticeId != null) {
                remoteNoticePreferences.markSeen(noticeId)
            }
            _uiState.update { it.copy(showDialog = false, notice = null) }
        }
    }

    fun suppressForStartup() {
        _uiState.update { it.copy(showDialog = false) }
    }
}
```

- [ ] **Step 4: Run ViewModel tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.notices.RemoteNoticeViewModelTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/notices/RemoteNoticeViewModel.kt \
  app/src/test/java/com/nexio/tv/notices/RemoteNoticeViewModelTest.kt
git commit -m "feat: add remote notice view model"
```

---

### Task 6: Notice Dialog UI

**Files:**
- Create: `app/src/main/java/com/nexio/tv/notices/ui/RemoteNoticeDialog.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify localized string files listed in File Structure
- Test: `app/src/androidTest/java/com/nexio/tv/notices/ui/RemoteNoticeDialogTest.kt`

- [ ] **Step 1: Write Android Compose UI test**

Create `app/src/androidTest/java/com/nexio/tv/notices/ui/RemoteNoticeDialogTest.kt`:

```kotlin
package com.nexio.tv.notices.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nexio.tv.notices.model.RemoteNoticeDisplay
import com.nexio.tv.ui.theme.NexioTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteNoticeDialogTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun renders_markdown_notice_and_closes() {
        composeRule.setContent {
            NexioTheme {
                var visible by remember { mutableStateOf(true) }
                if (visible) {
                    RemoteNoticeDialog(
                        notice = RemoteNoticeDisplay(
                            id = "notice-1",
                            title = "Important notice",
                            markdown = "# Heading\n\nBody copy",
                            markdownUrl = "https://raw.githubusercontent.com/johnneerdael/nexio/main/notices/notice-1.md",
                            publishedAt = "2026-05-12T12:01:00Z"
                        ),
                        onDismiss = { visible = false }
                    )
                }
            }
        }

        composeRule.onNodeWithText("Important notice").assertIsDisplayed()
        composeRule.onNodeWithText("Body copy").assertIsDisplayed()
        composeRule.onNodeWithText("Close").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Important notice").assertDoesNotExist()
    }
}
```

- [ ] **Step 2: Run UI test to verify failure**

Run:

```bash
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.nexio.tv.notices.ui.RemoteNoticeDialogTest
```

Expected: FAIL with unresolved reference `RemoteNoticeDialog`. If no emulator/device is attached, record that the test could not run and run it during final verification on an available Android target.

- [ ] **Step 3: Add strings**

Add to `app/src/main/res/values/strings.xml` near the update strings:

```xml
<string name="notice_close">Close</string>
```

Add the same value to:

```text
app/src/main/res/values-de/strings.xml
app/src/main/res/values-es/strings.xml
app/src/main/res/values-fr/strings.xml
app/src/main/res/values-nl/strings.xml
app/src/main/res/values-zh-rCN/strings.xml
```

- [ ] **Step 4: Add dialog implementation**

Create `app/src/main/java/com/nexio/tv/notices/ui/RemoteNoticeDialog.kt`:

```kotlin
package com.nexio.tv.notices.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.nexio.tv.R
import com.nexio.tv.notices.model.RemoteNoticeDisplay
import com.nexio.tv.ui.components.NexioDialog
import com.nexio.tv.ui.theme.NexioColors
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.tv.material3.MaterialTheme

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RemoteNoticeDialog(
    notice: RemoteNoticeDisplay,
    onDismiss: () -> Unit
) {
    val closeFocusRequester = remember { FocusRequester() }

    NexioDialog(
        onDismiss = onDismiss,
        title = notice.title,
        width = 760.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 4.dp)
        ) {
            Markdown(
                content = notice.markdown,
                modifier = Modifier.fillMaxWidth(),
                colors = markdownColor(text = NexioColors.TextSecondary),
                typography = markdownTypography(
                    paragraph = MaterialTheme.typography.bodyMedium,
                    h1 = MaterialTheme.typography.titleLarge,
                    h2 = MaterialTheme.typography.titleMedium,
                    h3 = MaterialTheme.typography.titleSmall
                )
            )
        }

        Button(
            onClick = onDismiss,
            modifier = Modifier.focusRequester(closeFocusRequester),
            colors = ButtonDefaults.colors(
                containerColor = NexioColors.Background,
                contentColor = NexioColors.TextPrimary,
                focusedContainerColor = NexioColors.FocusBackground,
                focusedContentColor = NexioColors.Primary
            ),
            shape = ButtonDefaults.shape(RoundedCornerShape(12.dp))
        ) {
            Text(stringResource(R.string.notice_close))
        }
    }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { closeFocusRequester.requestFocus() }
    }
}
```

- [ ] **Step 5: Run UI test**

Run:

```bash
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.nexio.tv.notices.ui.RemoteNoticeDialogTest
```

Expected: PASS on an attached Android test target.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/notices/ui/RemoteNoticeDialog.kt \
  app/src/main/res/values/strings.xml \
  app/src/main/res/values-de/strings.xml \
  app/src/main/res/values-es/strings.xml \
  app/src/main/res/values-fr/strings.xml \
  app/src/main/res/values-nl/strings.xml \
  app/src/main/res/values-zh-rCN/strings.xml \
  app/src/androidTest/java/com/nexio/tv/notices/ui/RemoteNoticeDialogTest.kt
git commit -m "feat: add remote notice dialog"
```

---

### Task 7: MainActivity Startup Gating

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/MainActivity.kt`
- Test: `app/src/test/java/com/nexio/tv/MainActivityIdleScreensaverTest.kt`

- [ ] **Step 1: Add gate tests**

Append these tests to `app/src/test/java/com/nexio/tv/MainActivityIdleScreensaverTest.kt`:

```kotlin
    @Test
    fun `remote notice dialog is blocked by startup update playback trailer and screensaver gates`() {
        assertTrue(
            shouldRenderRemoteNoticeDialog(
                noticeDialogRequested = true,
                updateDialogVisible = false,
                startupSplashVisible = false,
                playbackActive = false,
                fullscreenTrailerActive = false,
                idleScreensaverVisible = false,
                startupNoticeGateOpen = true
            )
        )

        assertFalse(
            shouldRenderRemoteNoticeDialog(
                noticeDialogRequested = true,
                updateDialogVisible = true,
                startupSplashVisible = false,
                playbackActive = false,
                fullscreenTrailerActive = false,
                idleScreensaverVisible = false,
                startupNoticeGateOpen = true
            )
        )

        assertFalse(
            shouldRenderRemoteNoticeDialog(
                noticeDialogRequested = true,
                updateDialogVisible = false,
                startupSplashVisible = true,
                playbackActive = false,
                fullscreenTrailerActive = false,
                idleScreensaverVisible = false,
                startupNoticeGateOpen = true
            )
        )

        assertFalse(
            shouldRenderRemoteNoticeDialog(
                noticeDialogRequested = true,
                updateDialogVisible = false,
                startupSplashVisible = false,
                playbackActive = true,
                fullscreenTrailerActive = false,
                idleScreensaverVisible = false,
                startupNoticeGateOpen = true
            )
        )

        assertFalse(
            shouldRenderRemoteNoticeDialog(
                noticeDialogRequested = true,
                updateDialogVisible = false,
                startupSplashVisible = false,
                playbackActive = false,
                fullscreenTrailerActive = true,
                idleScreensaverVisible = false,
                startupNoticeGateOpen = true
            )
        )

        assertFalse(
            shouldRenderRemoteNoticeDialog(
                noticeDialogRequested = true,
                updateDialogVisible = false,
                startupSplashVisible = false,
                playbackActive = false,
                fullscreenTrailerActive = false,
                idleScreensaverVisible = true,
                startupNoticeGateOpen = true
            )
        )
    }

    @Test
    fun `remote notice startup gate prevents middle of use popup`() {
        assertFalse(
            shouldRenderRemoteNoticeDialog(
                noticeDialogRequested = true,
                updateDialogVisible = false,
                startupSplashVisible = false,
                playbackActive = false,
                fullscreenTrailerActive = false,
                idleScreensaverVisible = false,
                startupNoticeGateOpen = false
            )
        )
    }
```

- [ ] **Step 2: Run gate tests to verify failure**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.MainActivityIdleScreensaverTest"
```

Expected: FAIL with unresolved reference `shouldRenderRemoteNoticeDialog`.

- [ ] **Step 3: Add imports and ViewModel wiring in MainActivity**

In `app/src/main/java/com/nexio/tv/MainActivity.kt`, add imports:

```kotlin
import com.nexio.tv.notices.RemoteNoticeViewModel
import com.nexio.tv.notices.ui.RemoteNoticeDialog
import kotlinx.coroutines.delay
```

Near the existing update ViewModel setup:

```kotlin
val updateViewModel: UpdateViewModel = hiltViewModel(this@MainActivity)
val updateState by updateViewModel.uiState.collectAsState()
val remoteNoticeViewModel: RemoteNoticeViewModel = hiltViewModel(this@MainActivity)
val remoteNoticeState by remoteNoticeViewModel.uiState.collectAsState()
```

Near startup state declarations:

```kotlin
var remoteNoticeStartupGateOpen by rememberSaveable {
    mutableStateOf(true)
}
```

After `showStartupSplash` is available, add a bounded startup window:

```kotlin
LaunchedEffect(showStartupSplash, remoteNoticeStartupGateOpen) {
    if (!showStartupSplash && remoteNoticeStartupGateOpen) {
        delay(2_000L)
        remoteNoticeStartupGateOpen = false
    }
}

LaunchedEffect(remoteNoticeStartupGateOpen, remoteNoticeState.showDialog) {
    if (!remoteNoticeStartupGateOpen && remoteNoticeState.showDialog) {
        remoteNoticeViewModel.suppressForStartup()
    }
}

LaunchedEffect(updateState.showDialog, remoteNoticeState.showDialog) {
    if (updateState.showDialog && remoteNoticeState.showDialog) {
        remoteNoticeViewModel.suppressForStartup()
    }
}
```

Before rendering dialogs, compute:

```kotlin
val showRemoteNoticeDialog = shouldRenderRemoteNoticeDialog(
    noticeDialogRequested = remoteNoticeState.showDialog,
    updateDialogVisible = updateState.showDialog,
    startupSplashVisible = showStartupSplash,
    playbackActive = playbackIdleSnapshot.hasActiveSession,
    fullscreenTrailerActive = homeTrailerFullscreenActive,
    idleScreensaverVisible = idleScreensaverVisible,
    startupNoticeGateOpen = remoteNoticeStartupGateOpen
)
```

Render the notice next to `UpdatePromptDialog`, after the update dialog block:

```kotlin
if (showRemoteNoticeDialog) {
    remoteNoticeState.notice?.let { notice ->
        RemoteNoticeDialog(
            notice = notice,
            onDismiss = { remoteNoticeViewModel.dismissNotice() }
        )
    }
}
```

- [ ] **Step 4: Add the pure gate helper**

Add this function near the other `internal fun` helpers in `MainActivity.kt`:

```kotlin
internal fun shouldRenderRemoteNoticeDialog(
    noticeDialogRequested: Boolean,
    updateDialogVisible: Boolean,
    startupSplashVisible: Boolean,
    playbackActive: Boolean,
    fullscreenTrailerActive: Boolean,
    idleScreensaverVisible: Boolean,
    startupNoticeGateOpen: Boolean
): Boolean {
    return noticeDialogRequested &&
        startupNoticeGateOpen &&
        !updateDialogVisible &&
        !startupSplashVisible &&
        !playbackActive &&
        !fullscreenTrailerActive &&
        !idleScreensaverVisible
}
```

- [ ] **Step 5: Run gate tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.MainActivityIdleScreensaverTest"
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/MainActivity.kt \
  app/src/test/java/com/nexio/tv/MainActivityIdleScreensaverTest.kt
git commit -m "feat: gate remote notices on startup"
```

---

### Task 8: Final Verification

**Files:**
- Verify all files from Tasks 1-7.

- [ ] **Step 1: Run focused unit tests**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.nexio.tv.data.integration.github.GitHubRawContentIntegrationProviderTest" \
  --tests "com.nexio.tv.notices.RemoteNoticeSelectorTest" \
  --tests "com.nexio.tv.notices.RemoteNoticePreferencesTest" \
  --tests "com.nexio.tv.notices.RemoteNoticeRepositoryTest" \
  --tests "com.nexio.tv.notices.RemoteNoticeViewModelTest" \
  --tests "com.nexio.tv.MainActivityIdleScreensaverTest"
```

Expected: PASS.

- [ ] **Step 2: Run Android UI test when a device is available**

Run:

```bash
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.nexio.tv.notices.ui.RemoteNoticeDialogTest
```

Expected: PASS. If no emulator/device is attached, record that the UI test was not run and include the reason in the final implementation summary.

- [ ] **Step 3: Run compile check**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: PASS.

- [ ] **Step 4: Inspect dirty working tree**

Run:

```bash
git status --short
```

Expected: only intended remote-notice files are modified or all intended changes are committed. Do not revert unrelated pre-existing dirty files.

- [ ] **Step 5: Commit final test or wiring fixes if needed**

If Step 1, Step 2, or Step 3 required small fixes, commit those fixes:

```bash
git add app/src/main/java/com/nexio/tv app/src/test/java/com/nexio/tv app/src/androidTest/java/com/nexio/tv app/src/main/res app/build.gradle.kts
git commit -m "fix: complete remote notice verification"
```

Expected: if no fixes were needed after Task 7, skip this commit.

---

## Self-Review Notes

Spec coverage:

- GitHub repo manifest and Markdown body: Tasks 1 and 4.
- Markdown display including images: Task 6 uses the existing Markdown renderer; image loading is left to the renderer/Coil stack.
- Newest-only selection: Task 2 selector tests.
- Version targeting, expiry, future-date filtering: Task 2 selector tests.
- Fresh-install baseline: Tasks 3 and 4 repository tests.
- One-time seen IDs on close: Tasks 3 and 5.
- Startup-only and precedence gates: Task 7.
- Fail-closed behavior: Tasks 1, 2, and 4.
- Testing expectations: Tasks 1-8.

Type consistency:

- UI/domain model is `RemoteNoticeDisplay`.
- Manifest DTOs are `RemoteNoticeManifest` and `RemoteNoticeManifestItem`.
- ViewModel state is `RemoteNoticeUiState`.
- Repository method is `fetchStartupNotice`.
- Gate helper is `shouldRenderRemoteNoticeDialog`.
