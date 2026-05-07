# YouTube Trailer Helper Auth Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the cookie/WebView trailer-auth design with SmartTube-style device-code sign-in plus a bearer-auth yt-dlp helper that owns all signed-in YouTube trailer resolution.

**Architecture:** Signed-out behavior remains the current internal trailer resolver. Signed-in behavior adds a TV-friendly device-code login under `Integration > YouTube Trailer Login`, persists refresh/access token state, refreshes bearer auth on demand, and routes all YouTube-backed trailers through the patched bundled `yt-dlp` helper. Non-YouTube trailers continue to use the existing internal resolver, and trailer UI stays hidden unless playback is internal and ready.

**Tech Stack:** Android TV, Kotlin, Jetpack Compose, Hilt, DataStore, Media3/ExoPlayer, Chaquopy, bundled `yt-dlp`, bundled `node`, OpenSpec

---

## File Structure

**OpenSpec files to update**
- Modify: `openspec/changes/add-youtube-trailer-helper-auth/proposal.md`
- Modify: `openspec/changes/add-youtube-trailer-helper-auth/design.md`
- Modify: `openspec/changes/add-youtube-trailer-helper-auth/tasks.md`
- Modify: `openspec/changes/add-youtube-trailer-helper-auth/specs/trailer-playback/spec.md`

**Existing Android files to modify**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/SettingsScreen.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/navigation/Screen.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/navigation/NexioNavHost.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/di/RepositoryModule.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/trailer/TrailerService.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeUiState.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeScreen.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/YouTubeTrailerLoginViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/YouTubeTrailerLoginScreen.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/trailer/helper/BundledTrailerHelper.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/trailer/helper/TrailerHelperModels.kt`

**New Android files to create**
- Create: `app/src/main/java/com/nexio/tv/data/local/YouTubeTrailerAuthDataStore.kt`
- Create: `app/src/main/java/com/nexio/tv/data/trailer/helper/YouTubeTrailerAuthManager.kt`
- Create: `app/src/main/java/com/nexio/tv/data/trailer/helper/YouTubeTrailerTokenStore.kt`
- Create: `app/src/main/java/com/nexio/tv/data/trailer/helper/YouTubeDeviceCodeAuthService.kt`
- Create: `app/src/main/java/com/nexio/tv/data/trailer/helper/TrailerAvailabilityService.kt`
- Create: `app/src/main/java/com/nexio/tv/data/trailer/helper/TrailerHelperCache.kt`
- Create: `app/src/main/java/com/nexio/tv/ui/screens/home/HomePosterTrailerOptions.kt`

**Python helper files to modify**
- Modify: `app/src/main/python/nexio_trailer_helper.py`
- Modify vendor copy of the yt-dlp fork under the trailer-helper asset payload so the Android helper uses the same bearer-auth seam validated locally

**Tests to add or update**
- Create: `app/src/test/java/com/nexio/tv/data/trailer/helper/YouTubeTrailerAuthManagerTest.kt`
- Create: `app/src/test/java/com/nexio/tv/data/trailer/helper/YouTubeTrailerTokenStoreTest.kt`
- Create: `app/src/test/java/com/nexio/tv/data/trailer/helper/BundledTrailerHelperBearerAuthTest.kt`
- Create: `app/src/test/java/com/nexio/tv/data/trailer/helper/TrailerAvailabilityServiceTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/settings/YouTubeTrailerLoginViewModelTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/home/HomePosterTrailerOptionsTest.kt`

## Task 1: Replace Trailer Auth State With Device-Code Session Models

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/local/YouTubeTrailerAuthDataStore.kt`
- Create: `app/src/main/java/com/nexio/tv/data/trailer/helper/YouTubeTrailerTokenStore.kt`
- Test: `app/src/test/java/com/nexio/tv/data/trailer/helper/YouTubeTrailerTokenStoreTest.kt`

- [ ] **Step 1: Write the failing token persistence test**

```kotlin
@Test
fun `access token expiry is persisted with refresh token state`() = runTest {
    val store = YouTubeTrailerTokenStore(fakeDataStore())
    store.saveSession(
        refreshToken = "refresh",
        accessToken = "access",
        accessTokenExpiryEpochMs = 1234L,
        pageId = null
    )
    val session = store.currentSession()
    assertEquals("refresh", session?.refreshToken)
    assertEquals("access", session?.accessToken)
    assertEquals(1234L, session?.accessTokenExpiryEpochMs)
}
```

- [ ] **Step 2: Run the focused test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.trailer.helper.YouTubeTrailerTokenStoreTest`
Expected: FAIL because the token store does not exist yet, or be blocked by an unrelated existing compile failure outside this feature.

- [ ] **Step 3: Implement the minimal token session model**

Add a small immutable session model with:
- `refreshToken`
- `accessToken`
- `accessTokenExpiryEpochMs`
- optional `pageId`
- signed-in status and last refresh metadata

- [ ] **Step 4: Update DataStore-backed auth settings**

Replace cookie-oriented fields with token-oriented fields:
- remove cookie file/version wording
- add token refresh and status wording
- keep the settings API small and explicit

- [ ] **Step 5: Run the focused test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.trailer.helper.YouTubeTrailerTokenStoreTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/local/YouTubeTrailerAuthDataStore.kt app/src/main/java/com/nexio/tv/data/trailer/helper/YouTubeTrailerTokenStore.kt app/src/test/java/com/nexio/tv/data/trailer/helper/YouTubeTrailerTokenStoreTest.kt
git commit -m "feat: add youtube trailer token session storage"
```

## Task 2: Add Device-Code / QR Sign-In Flow

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/trailer/helper/YouTubeDeviceCodeAuthService.kt`
- Create: `app/src/main/java/com/nexio/tv/data/trailer/helper/YouTubeTrailerAuthManager.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/YouTubeTrailerLoginViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/YouTubeTrailerLoginScreen.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/SettingsScreen.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/navigation/Screen.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/navigation/NexioNavHost.kt`
- Test: `app/src/test/java/com/nexio/tv/ui/screens/settings/YouTubeTrailerLoginViewModelTest.kt`
- Test: `app/src/test/java/com/nexio/tv/data/trailer/helper/YouTubeTrailerAuthManagerTest.kt`

- [ ] **Step 1: Write the failing device-code state test**

```kotlin
@Test
fun `sign in exposes device code and verification url`() = runTest {
    val manager = FakeYouTubeTrailerAuthManager(
        pendingCode = DeviceCodeSession(
            userCode = "ABCD-EFGH",
            verificationUrl = "https://www.google.com/device",
            expiresInSeconds = 1800,
            pollIntervalSeconds = 5
        )
    )
    val state = manager.beginSignIn()
    assertEquals("ABCD-EFGH", state.userCode)
    assertEquals("https://www.google.com/device", state.verificationUrl)
}
```

- [ ] **Step 2: Run the focused tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.settings.YouTubeTrailerLoginViewModelTest --tests com.nexio.tv.data.trailer.helper.YouTubeTrailerAuthManagerTest`
Expected: FAIL because the device-code flow and auth manager do not exist yet.

- [ ] **Step 3: Implement the device-code service**

Create a narrow service that can:
- request a device code using the validated SmartTube-compatible client
- poll for token completion
- refresh access tokens from a stored refresh token

Keep this service independent from UI state.

- [ ] **Step 4: Implement the auth manager**

The manager should:
- start sign-in and expose `userCode`, `verificationUrl`, and expiry
- poll until a refresh token is issued or the session expires
- refresh access tokens on demand
- publish small health/status messages for settings UI

- [ ] **Step 5: Replace the old WebView UI with a TV-friendly code screen**

Update `YouTubeTrailerLoginScreen` and its viewmodel to:
- show the code prominently
- show the verification URL
- optionally render a QR image
- provide `Start Sign In`, `Refresh Session`, and `Sign Out`
- remove the embedded WebView login surface

- [ ] **Step 6: Run the focused tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.settings.YouTubeTrailerLoginViewModelTest --tests com.nexio.tv.data.trailer.helper.YouTubeTrailerAuthManagerTest`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/trailer/helper/YouTubeDeviceCodeAuthService.kt app/src/main/java/com/nexio/tv/data/trailer/helper/YouTubeTrailerAuthManager.kt app/src/main/java/com/nexio/tv/ui/screens/settings/YouTubeTrailerLoginViewModel.kt app/src/main/java/com/nexio/tv/ui/screens/settings/YouTubeTrailerLoginScreen.kt app/src/main/java/com/nexio/tv/ui/screens/settings/SettingsScreen.kt app/src/main/java/com/nexio/tv/ui/navigation/Screen.kt app/src/main/java/com/nexio/tv/ui/navigation/NexioNavHost.kt app/src/test/java/com/nexio/tv/ui/screens/settings/YouTubeTrailerLoginViewModelTest.kt app/src/test/java/com/nexio/tv/data/trailer/helper/YouTubeTrailerAuthManagerTest.kt
git commit -m "feat: add device code youtube trailer login"
```

## Task 3: Convert the Bundled Helper Contract to Bearer Auth

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/trailer/helper/TrailerHelperModels.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/trailer/helper/BundledTrailerHelper.kt`
- Modify: `app/src/main/python/nexio_trailer_helper.py`
- Test: `app/src/test/java/com/nexio/tv/data/trailer/helper/BundledTrailerHelperBearerAuthTest.kt`

- [ ] **Step 1: Write the failing bearer-auth helper test**

```kotlin
@Test
fun `helper request carries authorization header instead of cookies`() {
    val request = TrailerHelperRequest(
        youtubeUrl = "https://www.youtube.com/watch?v=test1234567",
        authorizationHeader = "Bearer token",
        pageId = null
    )
    assertEquals("Bearer token", request.authorizationHeader)
}
```

- [ ] **Step 2: Run the focused test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.trailer.helper.BundledTrailerHelperBearerAuthTest`
Expected: FAIL because the helper request still expects cookie fields.

- [ ] **Step 3: Replace cookie fields with bearer auth fields**

Update the helper request and execution path to accept:
- `authorizationHeader`
- optional `pageId`
- optional `authUser`

Remove cookie-header-specific failure cases from the Android contract.

- [ ] **Step 4: Update the Python bridge**

Change `nexio_trailer_helper.py` to pass auth through yt-dlp-compatible headers:
- `Authorization`
- optional `X-Goog-PageId`
- optional `X-Goog-AuthUser`

- [ ] **Step 5: Run the focused test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.trailer.helper.BundledTrailerHelperBearerAuthTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/trailer/helper/TrailerHelperModels.kt app/src/main/java/com/nexio/tv/data/trailer/helper/BundledTrailerHelper.kt app/src/main/python/nexio_trailer_helper.py app/src/test/java/com/nexio/tv/data/trailer/helper/BundledTrailerHelperBearerAuthTest.kt
git commit -m "feat: switch trailer helper to bearer auth"
```

## Task 4: Vendor the Narrow yt-dlp Bearer-Auth Patch Into the Android Helper Runtime

**Files:**
- Modify bundled yt-dlp fork in the trailer-helper asset/runtime source
- Modify: `app/src/main/assets/trailer-helper/README.md`
- Test locally in `~/Scripts/yt-dlp` and then mirror the same patch into the Android-bundled copy

- [ ] **Step 1: Write down the required yt-dlp seam in the maintainer README**

Document that the Android helper depends on a narrow fork with:
- external auth header injection
- external auth treated as authenticated
- stable default auth-user handling

- [ ] **Step 2: Verify the local fork still passes the focused yt-dlp tests**

Run: `/opt/miniconda3/bin/python3.13 -m unittest /Users/jneerdael/Scripts/yt-dlp/test/test_youtube_misc.py`
Expected: PASS

- [ ] **Step 3: Mirror the validated patch into the Android helper’s vendored yt-dlp copy**

Copy only the minimal YouTube extractor changes needed for:
- `Authorization` header injection
- `is_authenticated` flip for external auth

- [ ] **Step 4: Rebuild or restage the Android helper payload**

Ensure the packaged helper runtime includes the patched yt-dlp source used by Chaquopy / the embedded runtime.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/assets/trailer-helper/README.md
git commit -m "feat: vendor bearer-auth yt-dlp trailer patch"
```

## Task 5: Change Signed-In YouTube Trailer Policy to Helper-Only

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/trailer/TrailerService.kt`
- Create: `app/src/main/java/com/nexio/tv/data/trailer/helper/TrailerAvailabilityService.kt`
- Create: `app/src/main/java/com/nexio/tv/data/trailer/helper/TrailerHelperCache.kt`
- Test: `app/src/test/java/com/nexio/tv/data/trailer/helper/TrailerAvailabilityServiceTest.kt`

- [ ] **Step 1: Write the failing signed-in policy test**

```kotlin
@Test
fun `signed in youtube trailer uses helper only`() = runTest {
    val result = service.resolveAvailability(
        isSignedIn = true,
        youtubeUrl = "https://www.youtube.com/watch?v=test1234567",
        internalPlayable = false,
        helperPlayable = true
    )
    assertTrue(result.available)
    assertEquals(TrailerAvailabilitySource.Helper, result.source)
}
```

- [ ] **Step 2: Run the focused test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.trailer.helper.TrailerAvailabilityServiceTest`
Expected: FAIL because the helper-only policy for signed-in YouTube trailers does not exist yet.

- [ ] **Step 3: Implement the new resolution policy**

Policy must be:
- signed out + YouTube: existing internal resolver only
- signed in + YouTube: helper only
- any non-YouTube source: existing internal resolver

- [ ] **Step 4: Add cache and negative-cache behavior**

Cache helper playback results until near URL expiry and cache misses briefly to avoid repeated focus churn.

- [ ] **Step 5: Run the focused test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.trailer.helper.TrailerAvailabilityServiceTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/trailer/TrailerService.kt app/src/main/java/com/nexio/tv/data/trailer/helper/TrailerAvailabilityService.kt app/src/main/java/com/nexio/tv/data/trailer/helper/TrailerHelperCache.kt app/src/test/java/com/nexio/tv/data/trailer/helper/TrailerAvailabilityServiceTest.kt
git commit -m "feat: make signed-in youtube trailers helper-owned"
```

## Task 6: Wire Detail and Home Surfaces to the New Availability Contract

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeUiState.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/home/HomeScreen.kt`
- Create: `app/src/main/java/com/nexio/tv/ui/screens/home/HomePosterTrailerOptions.kt`
- Modify: `app/src/test/java/com/nexio/tv/ui/screens/home/HomePosterTrailerOptionsTest.kt`

- [ ] **Step 1: Write the failing long-press trailer action test**

```kotlin
@Test
fun `play trailer action is shown only when playable trailer exists`() {
    val options = buildPosterOptions(hasPlayableTrailer = true)
    assertTrue(options.any { it.label == "Play Trailer" })
}
```

- [ ] **Step 2: Run the focused test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.home.HomePosterTrailerOptionsTest`
Expected: FAIL because the helper-backed availability contract is not wired to the shared poster dialog yet.

- [ ] **Step 3: Wire detail trailer availability**

Ensure detail-page trailer affordances only appear when:
- signed-out internal playback is available, or
- signed-in helper playback is available for YouTube, or
- non-YouTube internal playback is available

- [ ] **Step 4: Wire home trailer availability**

Ensure the shared poster long-press dialog in Classic, Grid, and Modern home includes `Play Trailer` only when the new availability service says playback is internal and ready.

- [ ] **Step 5: Run the focused test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.screens.home.HomePosterTrailerOptionsTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt app/src/main/java/com/nexio/tv/ui/screens/home/HomeUiState.kt app/src/main/java/com/nexio/tv/ui/screens/home/HomeScreen.kt app/src/main/java/com/nexio/tv/ui/screens/home/HomePosterTrailerOptions.kt app/src/test/java/com/nexio/tv/ui/screens/home/HomePosterTrailerOptionsTest.kt
git commit -m "feat: wire helper-backed trailer availability to ui"
```

## Task 7: Verify End-to-End Behavior and Update Spec Artifacts

**Files:**
- Modify: `openspec/changes/add-youtube-trailer-helper-auth/proposal.md`
- Modify: `openspec/changes/add-youtube-trailer-helper-auth/design.md`
- Modify: `openspec/changes/add-youtube-trailer-helper-auth/tasks.md`
- Modify: `openspec/changes/add-youtube-trailer-helper-auth/specs/trailer-playback/spec.md`
- Modify: `docs/superpowers/plans/2026-03-31-youtube-trailer-helper-auth.md`

- [ ] **Step 1: Run strict OpenSpec validation**

Run: `openspec validate add-youtube-trailer-helper-auth --strict`
Expected: PASS

- [ ] **Step 2: Run focused Kotlin tests**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.trailer.helper.YouTubeTrailerTokenStoreTest --tests com.nexio.tv.data.trailer.helper.YouTubeTrailerAuthManagerTest --tests com.nexio.tv.data.trailer.helper.BundledTrailerHelperBearerAuthTest --tests com.nexio.tv.data.trailer.helper.TrailerAvailabilityServiceTest --tests com.nexio.tv.ui.screens.home.HomePosterTrailerOptionsTest`
Expected: PASS, or explicit note of any unrelated baseline failures outside the changed files.

- [ ] **Step 3: Run Android compilation**

Run: `./gradlew :app:compileDebugKotlin`
Expected: PASS, or explicit note of any unrelated baseline failures outside the changed files.

- [ ] **Step 4: Do the validated local yt-dlp live check one more time**

Run the patched local fork with the device-auth bearer flow against the known age-restricted trailer and confirm:
- `-F` returns formats
- `-g` returns direct playback URLs

- [ ] **Step 5: Commit**

```bash
git add openspec/changes/add-youtube-trailer-helper-auth/proposal.md openspec/changes/add-youtube-trailer-helper-auth/design.md openspec/changes/add-youtube-trailer-helper-auth/tasks.md openspec/changes/add-youtube-trailer-helper-auth/specs/trailer-playback/spec.md docs/superpowers/plans/2026-03-31-youtube-trailer-helper-auth.md
git commit -m "docs: update youtube trailer helper auth spec and plan"
```
