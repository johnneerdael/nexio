# TVDB Average Runtime Fallback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow episode autoplay and manual stream routes to use a TVDB series average runtime when the selected episode has no per-episode runtime.

**Architecture:** Centralize runtime parsing and episode fallback selection in a small core utility, then wire the detail episode actions and stream-screen metadata hydration through that utility. Keep benchmark/autoplay scoring unchanged except that it receives runtime minutes more reliably, so existing manual-cap bitrate checks continue to work.

**Tech Stack:** Kotlin, Android Jetpack Compose, Android Navigation Compose, JUnit4, Gradle Android unit tests.

---

## File Structure

- Create `app/src/main/java/com/nexio/tv/core/metadata/RuntimeMinutes.kt`
  - Owns parsing display/runtime strings such as `"49"`, `"49m"`, and `"49 minutes"`.
  - Owns the fallback rule: use a positive episode runtime first, otherwise parse the series average runtime.
- Create `app/src/test/java/com/nexio/tv/core/metadata/RuntimeMinutesTest.kt`
  - Unit tests for parsing and episode-to-series fallback behavior.
- Modify `app/src/main/java/com/nexio/tv/ui/navigation/NexioNavHost.kt`
  - Import the shared parser and remove the private duplicate parser.
- Modify `app/src/test/java/com/nexio/tv/ui/navigation/StreamRuntimeRoutingTest.kt`
  - Import the shared parser so existing route/runtime tests keep exercising the same behavior.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsScreen.kt`
  - Add an internal helper that resolves episode playback runtime from `video.runtime ?: meta.runtime`.
  - Use that helper for normal episode play and manual stream selection.
- Create `app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsScreenRuntimeTest.kt`
  - Unit tests for the detail helper.
- Create `app/src/main/java/com/nexio/tv/ui/screens/stream/StreamRuntimeResolver.kt`
  - Resolves runtime during stream-screen metadata hydration using the same fallback rule.
- Create `app/src/test/java/com/nexio/tv/ui/screens/stream/StreamRuntimeResolverTest.kt`
  - Unit tests for stream metadata fallback, including the Survivor S50E08 case.
- Modify `app/src/main/java/com/nexio/tv/ui/screens/stream/StreamScreenViewModel.kt`
  - Replace the private per-episode-only runtime extraction with the shared stream resolver.

## Context Notes

- TheTVDB season page for Survivor season 50 shows blank runtime cells for S50E07 and S50E08 while earlier episodes have runtime values.
- TheTVDB API blueprint `tvdb.yml` exposes `averageRuntime` on `SeriesBaseRecord` and `SeriesExtendedRecord`; episode `runtime` is nullable.
- `TvdbMetadataService` already maps TVDB `averageRuntime` into both `TvMetadataEnrichment.runtimeMinutes` and `TvMetadataEnrichment.averageRuntimeMinutes`.
- `MetaDetailsViewModel` already stores series-level runtime on `Meta.runtime`.
- Current playback route construction passes only `Video.runtime`, so an episode with missing per-episode runtime sends `runtime=` even when `Meta.runtime` is available.
- Current `StreamScreenViewModel.extractRuntimeMinutes` checks exact episode runtime first and never falls back to `Meta.runtime` for episode playback.

---

### Task 1: Add Shared Runtime Resolver

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/metadata/RuntimeMinutes.kt`
- Create: `app/src/test/java/com/nexio/tv/core/metadata/RuntimeMinutesTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/nexio/tv/core/metadata/RuntimeMinutesTest.kt`:

```kotlin
package com.nexio.tv.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimeMinutesTest {

    @Test
    fun `parse runtime minutes accepts plain and decorated values`() {
        assertEquals(49, parseRuntimeMinutes("49"))
        assertEquals(49, parseRuntimeMinutes("49m"))
        assertEquals(49, parseRuntimeMinutes("49 minutes"))
        assertEquals(125, parseRuntimeMinutes("125 min"))
    }

    @Test
    fun `parse runtime minutes rejects missing non numeric and non positive values`() {
        assertNull(parseRuntimeMinutes(null))
        assertNull(parseRuntimeMinutes(""))
        assertNull(parseRuntimeMinutes("unknown"))
        assertNull(parseRuntimeMinutes("0"))
    }

    @Test
    fun `episode runtime wins over series average runtime`() {
        assertEquals(
            64,
            episodeRuntimeOrSeriesAverageMinutes(
                episodeRuntimeMinutes = 64,
                seriesRuntime = "49"
            )
        )
    }

    @Test
    fun `series average runtime is used when episode runtime is missing`() {
        assertEquals(
            49,
            episodeRuntimeOrSeriesAverageMinutes(
                episodeRuntimeMinutes = null,
                seriesRuntime = "49 minutes"
            )
        )
    }

    @Test
    fun `series average runtime is used when episode runtime is not positive`() {
        assertEquals(
            49,
            episodeRuntimeOrSeriesAverageMinutes(
                episodeRuntimeMinutes = 0,
                seriesRuntime = "49"
            )
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.core.metadata.RuntimeMinutesTest"
```

Expected: FAIL during compilation because `parseRuntimeMinutes` and `episodeRuntimeOrSeriesAverageMinutes` do not exist.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/nexio/tv/core/metadata/RuntimeMinutes.kt`:

```kotlin
package com.nexio.tv.core.metadata

fun parseRuntimeMinutes(raw: String?): Int? {
    return raw
        ?.let { Regex("(\\d+)").find(it)?.groupValues?.getOrNull(1) }
        ?.toIntOrNull()
        ?.takeIf { it > 0 }
}

fun episodeRuntimeOrSeriesAverageMinutes(
    episodeRuntimeMinutes: Int?,
    seriesRuntime: String?
): Int? {
    return episodeRuntimeMinutes?.takeIf { it > 0 }
        ?: parseRuntimeMinutes(seriesRuntime)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.core.metadata.RuntimeMinutesTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/metadata/RuntimeMinutes.kt app/src/test/java/com/nexio/tv/core/metadata/RuntimeMinutesTest.kt
git commit -m "test: add shared runtime minutes resolver"
```

---

### Task 2: Replace Navigation Runtime Parser Duplication

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/navigation/NexioNavHost.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/navigation/StreamRuntimeRoutingTest.kt`

- [ ] **Step 1: Write the failing test change**

Modify `app/src/test/java/com/nexio/tv/ui/navigation/StreamRuntimeRoutingTest.kt` imports to use the shared parser:

```kotlin
package com.nexio.tv.ui.navigation

import com.nexio.tv.core.metadata.parseRuntimeMinutes
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.WatchProgress
import com.nexio.tv.ui.screens.home.ContinueWatchingItem
import com.nexio.tv.ui.screens.home.NextUpInfo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
```

- [ ] **Step 2: Run test to verify current implementation still passes before removing duplicate**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.ui.navigation.StreamRuntimeRoutingTest"
```

Expected: PASS. This confirms the shared parser matches existing route test expectations before deleting the duplicate function.

- [ ] **Step 3: Use shared parser in navigation**

Modify imports in `app/src/main/java/com/nexio/tv/ui/navigation/NexioNavHost.kt`:

```kotlin
import com.nexio.tv.core.metadata.parseRuntimeMinutes
```

Remove this duplicate function from `app/src/main/java/com/nexio/tv/ui/navigation/NexioNavHost.kt`:

```kotlin
internal fun parseRuntimeMinutes(raw: String?): Int? {
    return raw
        ?.let { Regex("(\\d+)").find(it)?.groupValues?.getOrNull(1) }
        ?.toIntOrNull()
        ?.takeIf { it > 0 }
}
```

Keep all existing calls to `parseRuntimeMinutes(...)` unchanged; the import resolves them to the shared utility.

- [ ] **Step 4: Run navigation runtime tests**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.ui.navigation.StreamRuntimeRoutingTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/navigation/NexioNavHost.kt app/src/test/java/com/nexio/tv/ui/navigation/StreamRuntimeRoutingTest.kt
git commit -m "refactor: share runtime minutes parser"
```

---

### Task 3: Fallback Runtime for Detail Episode Playback Routes

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsScreen.kt`
- Create: `app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsScreenRuntimeTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsScreenRuntimeTest.kt`:

```kotlin
package com.nexio.tv.ui.screens.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetaDetailsScreenRuntimeTest {

    @Test
    fun `episode playback runtime uses episode runtime when present`() {
        assertEquals(
            64,
            resolveEpisodePlaybackRuntimeMinutes(
                episodeRuntimeMinutes = 64,
                seriesRuntime = "49"
            )
        )
    }

    @Test
    fun `episode playback runtime falls back to series average runtime`() {
        assertEquals(
            49,
            resolveEpisodePlaybackRuntimeMinutes(
                episodeRuntimeMinutes = null,
                seriesRuntime = "49"
            )
        )
    }

    @Test
    fun `episode playback runtime stays null when no runtime source exists`() {
        assertNull(
            resolveEpisodePlaybackRuntimeMinutes(
                episodeRuntimeMinutes = null,
                seriesRuntime = null
            )
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.ui.screens.detail.MetaDetailsScreenRuntimeTest"
```

Expected: FAIL during compilation because `resolveEpisodePlaybackRuntimeMinutes` does not exist.

- [ ] **Step 3: Add detail helper**

Modify `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsScreen.kt` imports:

```kotlin
import com.nexio.tv.core.metadata.episodeRuntimeOrSeriesAverageMinutes
```

Add this top-level helper near the other internal helper functions in `MetaDetailsScreen.kt`:

```kotlin
internal fun resolveEpisodePlaybackRuntimeMinutes(
    episodeRuntimeMinutes: Int?,
    seriesRuntime: String?
): Int? {
    return episodeRuntimeOrSeriesAverageMinutes(
        episodeRuntimeMinutes = episodeRuntimeMinutes,
        seriesRuntime = seriesRuntime
    )
}
```

- [ ] **Step 4: Run helper test to verify it passes**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.ui.screens.detail.MetaDetailsScreenRuntimeTest"
```

Expected: PASS.

- [ ] **Step 5: Wire normal episode playback route**

In `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsScreen.kt`, inside the `onEpisodeClick = { video -> ... }` block, add the local before `onPlayClick(...)`:

```kotlin
val playbackRuntime = resolveEpisodePlaybackRuntimeMinutes(
    episodeRuntimeMinutes = video.runtime,
    seriesRuntime = meta.runtime
)
```

Then replace the `video.runtime` argument in the `onPlayClick(...)` call with:

```kotlin
playbackRuntime
```

The relevant block should become:

```kotlin
onEpisodeClick = { video ->
    if (uiState.universalStreamerModeEnabled) {
        handleUniversalStreamerPlayRequest(meta.name)
    } else {
        val playbackRuntime = resolveEpisodePlaybackRuntimeMinutes(
            episodeRuntimeMinutes = video.runtime,
            seriesRuntime = meta.runtime
        )
        onPlayClick(
            video.id,
            meta.apiType,
            meta.id,
            meta.name,
            video.thumbnail ?: meta.poster,
            meta.background,
            meta.logo,
            video.season,
            video.episode,
            video.title,
            null,
            null,
            playbackRuntime,
            meta.language,
            uiState.deterministicAutoplayEnabled
        )
    }
}
```

- [ ] **Step 6: Wire manual episode stream selection route**

In `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsScreen.kt`, inside the `onPlayEpisodeWithManualStreamSelection = { video -> ... }` block, add the local before `onPlayEpisodeWithManualStreamSelection(...)`:

```kotlin
val playbackRuntime = resolveEpisodePlaybackRuntimeMinutes(
    episodeRuntimeMinutes = video.runtime,
    seriesRuntime = meta.runtime
)
```

Then replace the `video.runtime` argument in the `onPlayEpisodeWithManualStreamSelection(...)` call with:

```kotlin
playbackRuntime
```

The relevant block should become:

```kotlin
onPlayEpisodeWithManualStreamSelection = { video ->
    if (uiState.universalStreamerModeEnabled) {
        handleUniversalStreamerPlayRequest(meta.name)
    } else {
        val playbackRuntime = resolveEpisodePlaybackRuntimeMinutes(
            episodeRuntimeMinutes = video.runtime,
            seriesRuntime = meta.runtime
        )
        onPlayEpisodeWithManualStreamSelection(
            video.id,
            meta.apiType,
            meta.id,
            meta.name,
            video.thumbnail ?: meta.poster,
            meta.background,
            meta.logo,
            video.season,
            video.episode,
            video.title,
            playbackRuntime,
            meta.language
        )
    }
}
```

- [ ] **Step 7: Run detail runtime test**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.ui.screens.detail.MetaDetailsScreenRuntimeTest"
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsScreen.kt app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsScreenRuntimeTest.kt
git commit -m "fix: use series runtime for episode stream routes"
```

---

### Task 4: Fallback Runtime During Stream Metadata Hydration

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screens/stream/StreamRuntimeResolver.kt`
- Create: `app/src/test/java/com/nexio/tv/ui/screens/stream/StreamRuntimeResolverTest.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/stream/StreamScreenViewModel.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/nexio/tv/ui/screens/stream/StreamRuntimeResolverTest.kt`:

```kotlin
package com.nexio.tv.ui.screens.stream

import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamRuntimeResolverTest {

    @Test
    fun `episode runtime is used when available`() {
        val meta = meta(
            runtime = "49",
            videos = listOf(video(season = 50, episode = 6, runtime = 45))
        )

        assertEquals(
            45,
            resolveStreamRuntimeMinutes(
                meta = meta,
                season = 50,
                episode = 6
            )
        )
    }

    @Test
    fun `series average runtime is used when episode runtime is missing`() {
        val meta = meta(
            runtime = "49",
            videos = listOf(video(season = 50, episode = 8, runtime = null))
        )

        assertEquals(
            49,
            resolveStreamRuntimeMinutes(
                meta = meta,
                season = 50,
                episode = 8
            )
        )
    }

    @Test
    fun `series average runtime is used when episode metadata is absent`() {
        val meta = meta(
            runtime = "49 minutes",
            videos = emptyList()
        )

        assertEquals(
            49,
            resolveStreamRuntimeMinutes(
                meta = meta,
                season = 50,
                episode = 8
            )
        )
    }

    @Test
    fun `movie runtime parsing still uses title runtime`() {
        val meta = meta(
            type = ContentType.MOVIE,
            runtime = "152 min",
            videos = emptyList()
        )

        assertEquals(
            152,
            resolveStreamRuntimeMinutes(
                meta = meta,
                season = null,
                episode = null
            )
        )
    }

    @Test
    fun `runtime stays null when episode and series runtime are missing`() {
        val meta = meta(
            runtime = null,
            videos = listOf(video(season = 50, episode = 8, runtime = null))
        )

        assertNull(
            resolveStreamRuntimeMinutes(
                meta = meta,
                season = 50,
                episode = 8
            )
        )
    }

    private fun video(
        season: Int,
        episode: Int,
        runtime: Int?
    ): Video {
        return Video(
            id = "tt0239195:$season:$episode",
            title = "Episode",
            released = null,
            thumbnail = null,
            streams = emptyList(),
            season = season,
            episode = episode,
            overview = null,
            runtime = runtime
        )
    }

    private fun meta(
        type: ContentType = ContentType.SERIES,
        runtime: String?,
        videos: List<Video>
    ): Meta {
        return Meta(
            id = "tt0239195",
            type = type,
            name = "Survivor",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList(),
            runtime = runtime,
            director = emptyList(),
            cast = emptyList(),
            videos = videos,
            country = null,
            awards = null,
            language = "en",
            links = emptyList()
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.ui.screens.stream.StreamRuntimeResolverTest"
```

Expected: FAIL during compilation because `resolveStreamRuntimeMinutes` does not exist.

- [ ] **Step 3: Add stream runtime resolver**

Create `app/src/main/java/com/nexio/tv/ui/screens/stream/StreamRuntimeResolver.kt`:

```kotlin
package com.nexio.tv.ui.screens.stream

import com.nexio.tv.core.metadata.episodeRuntimeOrSeriesAverageMinutes
import com.nexio.tv.core.metadata.parseRuntimeMinutes
import com.nexio.tv.domain.model.Meta

internal fun resolveStreamRuntimeMinutes(
    meta: Meta,
    season: Int?,
    episode: Int?
): Int? {
    if (season != null && episode != null) {
        val episodeRuntime = meta.videos
            .firstOrNull { video -> video.season == season && video.episode == episode }
            ?.runtime
        return episodeRuntimeOrSeriesAverageMinutes(
            episodeRuntimeMinutes = episodeRuntime,
            seriesRuntime = meta.runtime
        )
    }

    return parseRuntimeMinutes(meta.runtime)
}
```

- [ ] **Step 4: Run resolver test to verify it passes**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.ui.screens.stream.StreamRuntimeResolverTest"
```

Expected: PASS.

- [ ] **Step 5: Wire resolver into StreamScreenViewModel**

Modify `app/src/main/java/com/nexio/tv/ui/screens/stream/StreamScreenViewModel.kt`.

Replace the private `extractRuntimeMinutes` function:

```kotlin
private fun extractRuntimeMinutes(meta: Meta): Int? {
    if (season != null && episode != null) {
        return meta.videos.firstOrNull { it.season == season && it.episode == episode }?.runtime
    }
    return meta.runtime
        ?.let { Regex("(\\d+)").find(it)?.groupValues?.getOrNull(1) }
        ?.toIntOrNull()
}
```

with:

```kotlin
private fun extractRuntimeMinutes(meta: Meta): Int? {
    return resolveStreamRuntimeMinutes(
        meta = meta,
        season = season,
        episode = episode
    )
}
```

- [ ] **Step 6: Run stream runtime tests**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest --tests "com.nexio.tv.ui.screens.stream.StreamRuntimeResolverTest"
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/stream/StreamRuntimeResolver.kt app/src/test/java/com/nexio/tv/ui/screens/stream/StreamRuntimeResolverTest.kt app/src/main/java/com/nexio/tv/ui/screens/stream/StreamScreenViewModel.kt
git commit -m "fix: fallback to series runtime during stream hydration"
```

---

### Task 5: Verify Autoplay Regression Coverage

**Files:**
- Test only: `app/src/test/java/com/nexio/tv/core/metadata/RuntimeMinutesTest.kt`
- Test only: `app/src/test/java/com/nexio/tv/ui/navigation/StreamRuntimeRoutingTest.kt`
- Test only: `app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsScreenRuntimeTest.kt`
- Test only: `app/src/test/java/com/nexio/tv/ui/screens/stream/StreamRuntimeResolverTest.kt`
- Test only: `app/src/test/java/com/nexio/tv/ui/screens/stream/StreamScreenViewModelDeterministicAutoplayTest.kt`

- [ ] **Step 1: Run targeted runtime and autoplay tests**

Run:

```bash
./gradlew :app:testArm64DebugUnitTest \
  --tests "com.nexio.tv.core.metadata.RuntimeMinutesTest" \
  --tests "com.nexio.tv.ui.navigation.StreamRuntimeRoutingTest" \
  --tests "com.nexio.tv.ui.screens.detail.MetaDetailsScreenRuntimeTest" \
  --tests "com.nexio.tv.ui.screens.stream.StreamRuntimeResolverTest" \
  --tests "com.nexio.tv.ui.screens.stream.StreamScreenViewModelDeterministicAutoplayTest"
```

Expected: PASS.

- [ ] **Step 2: Install and validate manually on the Android TV device**

Build and install the same package variant currently used on the device:

```bash
./gradlew :app:installArm64Release
```

Expected: Gradle reports `BUILD SUCCESSFUL`.

Connect to the AM9 Pro if needed:

```bash
adb connect 192.168.50.71
```

Expected: output includes `connected to 192.168.50.71:5555` or `already connected to 192.168.50.71:5555`.

Clear logs:

```bash
adb -s 192.168.50.71:5555 logcat -c
```

Open Survivor season 50 episode 8, start deterministic autoplay, and capture logs:

```bash
adb -s 192.168.50.71:5555 logcat -d | grep -E "AutoPlayShadowJson|AutoPlayShadow|SHADOW_AUTOPLAY_READY|missing_runtime"
```

Expected:

```text
AutoPlayShadow: winner=...
```

Expected absence:

```text
reasons":["missing_runtime"]
AutoPlayShadow: winner=none eligible=0
```

- [ ] **Step 3: Confirm manual stream selection still works**

On the same Survivor episode, open manual stream selection.

Expected visible entries include Real-Debrid non-4K streams such as:

```text
Survivor.S50E08.1080p.WEB.h264-EDITH.mkv
Survivor.S50E08.1080p.HEVC.x265-MeGusta.mkv
Survivor.S50E08.720p.HEVC.x265-MeGusta[EZTVx.to].mkv
```

- [ ] **Step 4: Commit verification notes if any docs or test fixtures changed**

If Task 5 only ran tests and manual validation, do not create a commit. If the manual validation notes are added to the runtime resolver test file as comments or assertions, commit only that intended file:

```bash
git add app/src/test/java/com/nexio/tv/ui/screens/stream/StreamRuntimeResolverTest.kt
git commit -m "test: document tvdb runtime fallback verification"
```

---

## Self-Review

**Spec coverage:** The plan covers the user requirement to fall back from missing episode runtime to series average runtime. Task 3 covers routes launched from detail episode actions. Task 4 covers stream-screen metadata hydration when route runtime is missing or stale. Task 5 covers unit and device-level regression validation.

**Placeholder scan:** The plan contains no placeholder implementation steps. Every code change step includes concrete code, exact file paths, and exact test commands.

**Type consistency:** The shared functions are named `parseRuntimeMinutes` and `episodeRuntimeOrSeriesAverageMinutes` in Task 1 and referenced with the same names in Tasks 2, 3, and 4. The stream resolver is named `resolveStreamRuntimeMinutes` in Task 4 and referenced consistently by the test and `StreamScreenViewModel`.
