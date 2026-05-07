# Rich Pause Overlay Viability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the current text-heavy pause overlay with reused detail-screen artwork: title logo, backdrop, and cast photos, while preserving the existing delayed render behavior after manual pause.

**Architecture:** Reuse artwork already present in `PlayerUiState` and metadata already fetched by `PlayerRuntimeController.fetchMetaDetails`; do not add a new playback-time metadata request. Keep all `AsyncImage` and `ImageRequest` construction inside the visible pause overlay content so image decoding/network work starts only after the existing pause delay promotes `showPauseOverlay` to `true`.

**Tech Stack:** Android TV, Kotlin, Jetpack Compose, androidx.tv Material3, Coil, Media3 player runtime, JUnit, Compose instrumentation tests.

---

## Viability Report

**Verdict:** High viability, low-to-medium risk.

The data needed for the richer overlay is already in the playback surface:

- `PlayerNavigationArgs` already carries `backdrop` and `logo`.
- `PlayerUiState` already stores `backdrop`, `logo`, and `castMembers`.
- `PlayerScreen` already renders `LoadingOverlay` with `uiState.backdrop` and `uiState.logo`.
- `PlayerScreen` already passes `uiState.castMembers` into `PauseOverlay`.
- `PauseOverlay` already receives `MetaCastMember`, and its cast detail view already uses `member.photo`.
- `CastSection` on the detail screen already renders cast photos using `MetaCastMember.photo`.
- `PlayerLocalizedMetadata.withLocalizedPlaybackMetadata` already updates `backdrop`, `logo`, and `castMembers` from provider enrichment.

The current gap is presentation, not availability. `PauseOverlay` currently ignores `backdrop` and `logo`, and `CastChip` only renders names. `PlayerRuntimeController.applyMetaDetails` also fills description and cast from the existing metadata fetch but does not currently fill blank `backdrop`/`logo` from the same `Meta` object; adding that fallback improves routes that entered playback without artwork.

## Cost Estimate

**Engineering:** 1.5 to 2.5 days.

- 0.25 day: add pure visibility/artwork policy tests and extract the existing delayed pause predicate.
- 0.5 day: wire existing metadata fallback for blank playback artwork.
- 0.5 day: update `PauseOverlay` UI to render backdrop, logo fallback, and cast photo cards.
- 0.25 day: add Compose instrumentation coverage for hidden vs visible overlay rendering.
- 0.5 day: Android TV manual QA across ExoPlayer/libmpv, cached/uncached artwork, movie/series, missing logo, missing cast photos.

**Runtime cost:** acceptable when implemented as below.

- During playback before pause: no extra UI image requests, no new metadata request, no additional player-surface work.
- During the existing pause delay: unchanged scheduling path; `showPauseOverlay` remains false.
- After pause delay: Coil may decode/fetch backdrop, logo, and up to 8 cast thumbnails. This happens only while paused.
- On cache hit: expected to be cheap because these are the same URLs used by detail/loading surfaces.
- On cache miss: first pause may load progressively; fallback title/initials must remain visible so the overlay is usable before images complete.

## Playback-Safety Requirements

- Keep the existing `schedulePauseOverlay()` delay and eligibility gates: pause overlay only after manual pause, first frame rendered, no error, no blocking panel.
- Keep `PauseOverlay(...)` called with `visible = uiState.showPauseOverlay && uiState.error == null && !uiState.showLoadingOverlay`.
- Place backdrop/logo/cast image request creation inside `AnimatedVisibility` content.
- Use Coil request sizes for logo and cast cards to avoid decoding oversized images.
- Use `crossfade(false)` for cast thumbnails and backdrop to avoid extra animation work on low-end TV devices.
- Fall back to black gradient/title text/initials when image URLs are missing or fail.

## File Structure

- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerPlaybackEvents.kt`
  - Replace the inline delayed-pause predicate with a pure helper.
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/PauseOverlayVisibilityPolicy.kt`
  - Own the pure `shouldShowPauseOverlayAfterDelay` predicate.
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/PauseOverlayVisibilityPolicyTest.kt`
  - Verify the overlay remains gated behind pause/enabled/no-error/no-panels.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerLocalizedMetadata.kt`
  - Add `withAddonMetaArtwork(meta)` so blank playback artwork can be filled from the already-fetched `Meta`.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerMetadata.kt`
  - Apply `withAddonMetaArtwork(meta)` inside `applyMetaDetails`.
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerLocalizedMetadataTest.kt`
  - Verify blank artwork is filled and existing route/localized artwork is not overwritten.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PauseOverlay.kt`
  - Render backdrop, logo, and detail-style cast photo cards.
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerScreen.kt`
  - Pass `uiState.backdrop` and `uiState.logo` to `PauseOverlay`.
- Test: `app/src/androidTest/java/com/nexio/tv/ui/screens/player/PauseOverlayRenderingTest.kt`
  - Verify hidden overlay emits no artwork/cast UI and visible overlay emits artwork/cast nodes.

---

### Task 1: Preserve Delayed Pause Overlay Eligibility as a Pure Policy

**Files:**
- Create: `app/src/main/java/com/nexio/tv/ui/screens/player/PauseOverlayVisibilityPolicy.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerPlaybackEvents.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/PauseOverlayVisibilityPolicyTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/nexio/tv/ui/screens/player/PauseOverlayVisibilityPolicyTest.kt`:

```kotlin
package com.nexio.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PauseOverlayVisibilityPolicyTest {

    @Test
    fun `delayed pause overlay can show only when playback is paused enabled and error free`() {
        assertTrue(
            shouldShowPauseOverlayAfterDelay(
                PlayerUiState(
                    isPlaying = false,
                    pauseOverlayEnabled = true,
                    error = null
                )
            )
        )

        assertFalse(
            shouldShowPauseOverlayAfterDelay(
                PlayerUiState(
                    isPlaying = true,
                    pauseOverlayEnabled = true,
                    error = null
                )
            )
        )

        assertFalse(
            shouldShowPauseOverlayAfterDelay(
                PlayerUiState(
                    isPlaying = false,
                    pauseOverlayEnabled = false,
                    error = null
                )
            )
        )

        assertFalse(
            shouldShowPauseOverlayAfterDelay(
                PlayerUiState(
                    isPlaying = false,
                    pauseOverlayEnabled = true,
                    error = "network error"
                )
            )
        )
    }

    @Test
    fun `delayed pause overlay is blocked while player panels are open`() {
        val base = PlayerUiState(
            isPlaying = false,
            pauseOverlayEnabled = true,
            error = null
        )

        assertFalse(shouldShowPauseOverlayAfterDelay(base.copy(showSubtitleDialog = true)))
        assertFalse(shouldShowPauseOverlayAfterDelay(base.copy(showSpeedDialog = true)))
        assertFalse(shouldShowPauseOverlayAfterDelay(base.copy(showMoreDialog = true)))
        assertFalse(shouldShowPauseOverlayAfterDelay(base.copy(showEpisodesPanel = true)))
        assertFalse(shouldShowPauseOverlayAfterDelay(base.copy(showSourcesPanel = true)))
        assertFalse(shouldShowPauseOverlayAfterDelay(base.copy(showAudioDialog = true)))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.player.PauseOverlayVisibilityPolicyTest`

Expected: FAIL with an unresolved reference to `shouldShowPauseOverlayAfterDelay`.

- [ ] **Step 3: Add the pure policy helper**

Create `app/src/main/java/com/nexio/tv/ui/screens/player/PauseOverlayVisibilityPolicy.kt`:

```kotlin
package com.nexio.tv.ui.screens.player

internal fun shouldShowPauseOverlayAfterDelay(state: PlayerUiState): Boolean {
    val anyPanelOpen = state.showSubtitleDialog ||
        state.showSpeedDialog ||
        state.showMoreDialog ||
        state.showEpisodesPanel ||
        state.showSourcesPanel ||
        state.showAudioDialog

    return !state.isPlaying &&
        state.pauseOverlayEnabled &&
        state.error == null &&
        !anyPanelOpen
}
```

- [ ] **Step 4: Use the helper in the runtime controller**

In `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerPlaybackEvents.kt`, replace the delayed job body in `schedulePauseOverlay()` with:

```kotlin
    pauseOverlayJob = scope.launch {
        delay(pauseOverlayDelayMs)
        val state = _uiState.value
        if (shouldShowPauseOverlayAfterDelay(state)) {
            _uiState.update { it.copy(showPauseOverlay = true, showControls = false) }
        }
    }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.player.PauseOverlayVisibilityPolicyTest`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PauseOverlayVisibilityPolicy.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerPlaybackEvents.kt app/src/test/java/com/nexio/tv/ui/screens/player/PauseOverlayVisibilityPolicyTest.kt
git commit -m "test: lock pause overlay visibility policy"
```

### Task 2: Reuse Already-Fetched Detail Artwork for Playback State

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerLocalizedMetadata.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerMetadata.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerLocalizedMetadataTest.kt`

- [ ] **Step 1: Write the failing tests**

Append to `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerLocalizedMetadataTest.kt`:

```kotlin
    @Test
    fun `addon metadata fills blank playback artwork from existing meta fetch`() {
        val state = PlayerUiState(
            title = "Playback Title",
            backdrop = null,
            logo = ""
        )

        val updated = state.withAddonMetaArtwork(
            meta = testMeta(
                background = "detail-backdrop",
                logo = "detail-logo"
            )
        )

        assertEquals("detail-backdrop", updated.backdrop)
        assertEquals("detail-logo", updated.logo)
    }

    @Test
    fun `addon metadata does not replace existing playback artwork`() {
        val state = PlayerUiState(
            title = "Playback Title",
            backdrop = "route-backdrop",
            logo = "route-logo"
        )

        val updated = state.withAddonMetaArtwork(
            meta = testMeta(
                background = "detail-backdrop",
                logo = "detail-logo"
            )
        )

        assertEquals("route-backdrop", updated.backdrop)
        assertEquals("route-logo", updated.logo)
    }
```

Add these imports to the same test file:

```kotlin
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.PosterShape
```

Add this helper at the bottom of the same test file:

```kotlin
private fun testMeta(
    background: String?,
    logo: String?
): Meta {
    return Meta(
        id = "movie-1",
        type = ContentType.MOVIE,
        name = "Playback Title",
        poster = null,
        posterShape = PosterShape.POSTER,
        background = background,
        logo = logo,
        description = "Description",
        releaseInfo = "2024",
        imdbRating = null,
        genres = emptyList(),
        runtime = null,
        director = emptyList(),
        cast = emptyList(),
        videos = emptyList(),
        country = null,
        awards = null,
        language = null,
        links = emptyList()
    )
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.player.PlayerLocalizedMetadataTest`

Expected: FAIL with an unresolved reference to `withAddonMetaArtwork`.

- [ ] **Step 3: Add the artwork merge helper**

In `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerLocalizedMetadata.kt`, add this import:

```kotlin
import com.nexio.tv.domain.model.Meta
```

Add this function above `private fun String?.nonBlank()`:

```kotlin
internal fun PlayerUiState.withAddonMetaArtwork(meta: Meta): PlayerUiState {
    val addonBackdrop = meta.background.nonBlank()
    val addonLogo = meta.logo.nonBlank()

    return copy(
        backdrop = backdrop.nonBlank() ?: addonBackdrop,
        logo = logo.nonBlank() ?: addonLogo
    )
}
```

- [ ] **Step 4: Apply the helper during the existing metadata fetch**

In `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerMetadata.kt`, change the `_uiState.update` block in `applyMetaDetails(meta)` to:

```kotlin
    _uiState.update { state ->
        state.withAddonMetaArtwork(meta).copy(
            description = description ?: state.description,
            castMembers = if (safeCastMembers.isNotEmpty()) safeCastMembers else state.castMembers
        )
    }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.player.PlayerLocalizedMetadataTest`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerLocalizedMetadata.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerMetadata.kt app/src/test/java/com/nexio/tv/ui/screens/player/PlayerLocalizedMetadataTest.kt
git commit -m "feat: reuse metadata artwork for playback overlays"
```

### Task 3: Render Backdrop and Logo Only Inside the Visible Pause Overlay

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PauseOverlay.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerScreen.kt`

- [ ] **Step 1: Add artwork parameters at the call site**

In `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerScreen.kt`, update the `PauseOverlay(...)` call:

```kotlin
        PauseOverlay(
            visible = uiState.showPauseOverlay && uiState.error == null && !uiState.showLoadingOverlay,
            onClose = { viewModel.onEvent(PlayerEvent.OnDismissPauseOverlay) },
            title = uiState.title,
            episodeTitle = uiState.currentEpisodeTitle,
            season = uiState.currentSeason,
            episode = uiState.currentEpisode,
            year = uiState.releaseYear,
            type = uiState.contentType,
            description = uiState.description,
            backdropUrl = uiState.backdrop,
            logoUrl = uiState.logo,
            cast = uiState.castMembers,
            modifier = Modifier
                .fillMaxSize()
                .zIndex(2.5f)
        )
```

- [ ] **Step 2: Update `PauseOverlay` signature**

In `app/src/main/java/com/nexio/tv/ui/screens/player/PauseOverlay.kt`, change the signature to:

```kotlin
fun PauseOverlay(
    visible: Boolean,
    onClose: () -> Unit,
    title: String,
    episodeTitle: String?,
    season: Int?,
    episode: Int?,
    year: String?,
    type: String?,
    description: String?,
    backdropUrl: String?,
    logoUrl: String?,
    cast: List<MetaCastMember>,
    modifier: Modifier = Modifier
)
```

- [ ] **Step 3: Add imports for sized requests and test tags**

Add these imports to `PauseOverlay.kt`:

```kotlin
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
```

- [ ] **Step 4: Render the backdrop before gradients**

Inside the `AnimatedVisibility` content in `PauseOverlay.kt`, replace the outer `Box` start with:

```kotlin
        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag("pause_overlay_root")
                .clickable(onClick = onClose)
        ) {
            PauseOverlayBackdrop(backdropUrl = backdropUrl)

            val leftGradient = remember {
                Brush.horizontalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.92f),
                        Color.Black.copy(alpha = 0.64f),
                        Color.Transparent
                    )
                )
            }
            val topGradient = remember {
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to Color.Black.copy(alpha = 0.62f),
                        0.3f to Color.Black.copy(alpha = 0.42f),
                        0.6f to Color.Black.copy(alpha = 0.2f),
                        1f to Color.Transparent
                    )
                )
            }
            val bottomGradient = remember {
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to Color.Transparent,
                        0.58f to Color.Black.copy(alpha = 0.16f),
                        1f to Color.Black.copy(alpha = 0.9f)
                    )
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithCache {
                        onDrawBehind {
                            drawRect(brush = leftGradient, size = size)
                            drawRect(brush = topGradient, size = size)
                            drawRect(brush = bottomGradient, size = size)
                        }
                    }
            )
```

Add this function below `PauseOverlayClock`:

```kotlin
@Composable
private fun PauseOverlayBackdrop(backdropUrl: String?) {
    var backdropLoadFailed by remember(backdropUrl) { mutableStateOf(false) }
    if (backdropUrl.isNullOrBlank() || backdropLoadFailed) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .testTag("pause_overlay_backdrop_fallback")
        )
        return
    }

    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val widthPx = remember(configuration.screenWidthDp, density) {
        with(density) { configuration.screenWidthDp.dp.roundToPx() }
    }
    val heightPx = remember(configuration.screenHeightDp, density) {
        with(density) { configuration.screenHeightDp.dp.roundToPx() }
    }
    val model = remember(context, backdropUrl, widthPx, heightPx) {
        ImageRequest.Builder(context)
            .data(backdropUrl)
            .crossfade(false)
            .size(width = widthPx, height = heightPx)
            .build()
    }

    AsyncImage(
        model = model,
        contentDescription = stringResource(R.string.cd_loading_backdrop),
        onError = { backdropLoadFailed = true },
        modifier = Modifier
            .fillMaxSize()
            .testTag("pause_overlay_backdrop"),
        contentScale = ContentScale.Crop
    )
}
```

- [ ] **Step 5: Replace title text with logo fallback**

Add `logoUrl: String?` to `PauseMetadataView(...)`, pass it from `PauseOverlay`, and replace the title `Text(...)` block with:

```kotlin
            PauseTitleLogo(
                logoUrl = logoUrl,
                title = title
            )
```

Add this function below `PauseMetadataView`:

```kotlin
@Composable
private fun PauseTitleLogo(
    logoUrl: String?,
    title: String
) {
    var logoLoadFailed by remember(logoUrl) { mutableStateOf(false) }
    val context = LocalContext.current
    val density = LocalDensity.current
    val logoWidth = 360.dp
    val logoHeight = 150.dp
    val logoWidthPx = remember(logoWidth, density) { with(density) { logoWidth.roundToPx() } }
    val logoHeightPx = remember(logoHeight, density) { with(density) { logoHeight.roundToPx() } }
    val logoModel = remember(context, logoUrl, logoWidthPx, logoHeightPx) {
        logoUrl?.takeIf { it.isNotBlank() }?.let { url ->
            ImageRequest.Builder(context)
                .data(url)
                .crossfade(false)
                .size(width = logoWidthPx, height = logoHeightPx)
                .build()
        }
    }

    if (logoModel != null && !logoLoadFailed) {
        AsyncImage(
            model = logoModel,
            contentDescription = stringResource(R.string.cd_loading_logo),
            onError = { logoLoadFailed = true },
            modifier = Modifier
                .width(logoWidth)
                .height(logoHeight)
                .testTag("pause_overlay_logo"),
            contentScale = ContentScale.Fit
        )
    } else {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineLarge,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("pause_overlay_title_fallback")
        )
    }
}
```

- [ ] **Step 6: Run compile check**

Run: `./gradlew :app:compileDebugKotlin`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PauseOverlay.kt app/src/main/java/com/nexio/tv/ui/screens/player/PlayerScreen.kt
git commit -m "feat: show artwork in pause overlay"
```

### Task 4: Replace Cast Name Chips with Detail-Style Cast Photos

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PauseOverlay.kt`

- [ ] **Step 1: Replace the cast chip call**

In `PauseOverlay.kt`, replace:

```kotlin
                            CastChip(member = member, onClick = { onCastSelected(member) })
```

with:

```kotlin
                            CastPictureChip(member = member, onClick = { onCastSelected(member) })
```

- [ ] **Step 2: Replace `CastChip` with `CastPictureChip`**

Delete the existing `CastChip` function and add:

```kotlin
@Composable
private fun CastPictureChip(
    member: MetaCastMember,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val cardSize = 92.dp
    val itemWidth = 132.dp
    val cardSizePx = remember(cardSize, density) {
        with(density) { cardSize.roundToPx() }
    }
    val photoModel = remember(context, member.photo, cardSizePx) {
        member.photo?.takeIf { it.isNotBlank() }?.let { url ->
            ImageRequest.Builder(context)
                .data(url)
                .crossfade(false)
                .size(width = cardSizePx, height = cardSizePx)
                .build()
        }
    }

    Column(
        modifier = Modifier.width(itemWidth),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            onClick = onClick,
            modifier = Modifier
                .size(cardSize)
                .testTag("pause_overlay_cast_photo"),
            colors = CardDefaults.colors(
                containerColor = Color.White.copy(alpha = 0.1f),
                focusedContainerColor = Color.White.copy(alpha = 0.18f)
            ),
            shape = CardDefaults.shape(shape = CircleShape)
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                if (photoModel != null) {
                    AsyncImage(
                        model = photoModel,
                        contentDescription = member.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Text(
                        text = member.name.firstOrNull()?.uppercase() ?: "?",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = member.name,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        if (!member.character.isNullOrBlank()) {
            Text(
                text = member.character,
                style = MaterialTheme.typography.labelSmall,
                color = NexioColors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
```

- [ ] **Step 3: Run compile check**

Run: `./gradlew :app:compileDebugKotlin`

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PauseOverlay.kt
git commit -m "feat: show cast photos on pause overlay"
```

### Task 5: Add Compose Rendering Coverage for the Render Gate

**Files:**
- Test: `app/src/androidTest/java/com/nexio/tv/ui/screens/player/PauseOverlayRenderingTest.kt`

- [ ] **Step 1: Write the instrumentation tests**

Create `app/src/androidTest/java/com/nexio/tv/ui/screens/player/PauseOverlayRenderingTest.kt`:

```kotlin
package com.nexio.tv.ui.screens.player

import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nexio.tv.domain.model.MetaCastMember
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PauseOverlayRenderingTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun hidden_pause_overlay_does_not_emit_artwork_or_cast_nodes() {
        composeRule.setContent {
            PauseOverlay(
                visible = false,
                onClose = {},
                title = "Monarch: Legacy of Monsters",
                episodeTitle = "Separate Ways",
                season = 1,
                episode = 10,
                year = "2024",
                type = "series",
                description = "Titan X makes landfall.",
                backdropUrl = "https://example.invalid/backdrop.jpg",
                logoUrl = "https://example.invalid/logo.png",
                cast = listOf(
                    MetaCastMember(
                        name = "Anna Sawai",
                        character = "Cate Randa",
                        photo = "https://example.invalid/anna.jpg"
                    )
                ),
                modifier = Modifier
            )
        }

        composeRule.waitForIdle()

        composeRule.onNodeWithTag("pause_overlay_root").assertDoesNotExist()
        composeRule.onNodeWithTag("pause_overlay_backdrop").assertDoesNotExist()
        composeRule.onNodeWithTag("pause_overlay_logo").assertDoesNotExist()
        composeRule.onNodeWithTag("pause_overlay_cast_photo").assertDoesNotExist()
    }

    @Test
    fun visible_pause_overlay_emits_artwork_and_cast_photo_slots() {
        composeRule.setContent {
            PauseOverlay(
                visible = true,
                onClose = {},
                title = "Monarch: Legacy of Monsters",
                episodeTitle = "Separate Ways",
                season = 1,
                episode = 10,
                year = "2024",
                type = "series",
                description = "Titan X makes landfall.",
                backdropUrl = "https://example.invalid/backdrop.jpg",
                logoUrl = "https://example.invalid/logo.png",
                cast = listOf(
                    MetaCastMember(
                        name = "Anna Sawai",
                        character = "Cate Randa",
                        photo = "https://example.invalid/anna.jpg"
                    ),
                    MetaCastMember(
                        name = "Kiersey Clemons",
                        character = "May",
                        photo = "https://example.invalid/kiersey.jpg"
                    )
                ),
                modifier = Modifier
            )
        }

        composeRule.waitForIdle()

        composeRule.onNodeWithTag("pause_overlay_root").assertIsDisplayed()
        composeRule.onNodeWithTag("pause_overlay_backdrop").assertIsDisplayed()
        composeRule.onNodeWithTag("pause_overlay_logo").assertIsDisplayed()
        assert(composeRule.onAllNodesWithTag("pause_overlay_cast_photo").fetchSemanticsNodes().size == 2)
    }
}
```

- [ ] **Step 2: Run instrumentation test**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.nexio.tv.ui.screens.player.PauseOverlayRenderingTest`

Expected: PASS on an attached Android TV emulator/device.

- [ ] **Step 3: Commit**

```bash
git add app/src/androidTest/java/com/nexio/tv/ui/screens/player/PauseOverlayRenderingTest.kt
git commit -m "test: cover pause overlay artwork render gate"
```

### Task 6: Playback Regression Verification

**Files:**
- No source files changed in this task.

- [ ] **Step 1: Run targeted local tests**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.player.PauseOverlayVisibilityPolicyTest --tests com.nexio.tv.ui.screens.player.PlayerLocalizedMetadataTest`

Expected: PASS.

- [ ] **Step 2: Run compile check**

Run: `./gradlew :app:compileDebugKotlin`

Expected: PASS.

- [ ] **Step 3: Run connected render test**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.nexio.tv.ui.screens.player.PauseOverlayRenderingTest`

Expected: PASS on an attached Android TV emulator/device.

- [ ] **Step 4: Manual TV playback check**

Run a debug build on a TV device or emulator and verify:

```text
1. Start movie playback from a detail screen with logo and backdrop.
2. Confirm playback starts with no new pause artwork shown before pressing pause.
3. Press pause.
4. Confirm the normal player controls appear immediately.
5. Wait for the configured pause overlay delay.
6. Confirm the richer pause overlay appears with backdrop, logo, and cast photo cards.
7. Press play/OK.
8. Confirm playback resumes without a surface reset, decoder restart, audio glitch, or dropped controls state.
9. Repeat with a title missing logo and cast photos.
10. Confirm fallback title text and cast initials appear.
11. Repeat with libmpv selected if available.
12. Confirm the same delayed overlay behavior.
```

- [ ] **Step 5: Commit final verification note if this branch tracks docs**

```bash
git status --short
```

Expected: no unstaged source changes after the implementation commits.

## Self-Review

**Spec coverage:** Covered title-to-logo, opaque overlay-to-backdrop, cast-name-to-cast-photo cards, existing delayed pause behavior, render gating, and playback regression checks.

**Placeholder scan:** No implementation step uses deferred placeholder wording. Every code-changing step includes the exact code to add or replace.

**Type consistency:** `backdropUrl`, `logoUrl`, `MetaCastMember`, `PlayerUiState`, and `shouldShowPauseOverlayAfterDelay` names are consistent across tasks and tests.

## Recommendation

Proceed with the implementation. The feature reuses existing detail-screen metadata and artwork with no new required backend or provider work. The only hard requirement is keeping image work inside the `visible == true` overlay path and retaining the current `schedulePauseOverlay()` delay gate.
