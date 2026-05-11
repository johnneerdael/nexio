# Early Access auto-upgrade — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the in-app updater channel-aware so Early Access builds (`com.nexio.tv.earlyaccess`) upgrade off the highest version across stable releases AND GitHub prereleases, always installing the `nexio-earlyaccess.apk` asset; stable builds keep their current single-channel behavior with `nexio-release.apk`.

**Architecture:** Introduce an `UpdateChannel` enum sourced from a new `BuildConfig.UPDATE_CHANNEL` per build type. `UpdateRepository` branches on the channel: stable does one `/releases/latest` call (unchanged semantics); early-access does that *plus* one `/releases?per_page=10` call, picks the higher-versioned candidate, and resolves the asset through a new `ChannelAssetSelector` that filters by filename prefix before delegating to the existing `AbiSelector`. No UI, ViewModel, or download/install code changes.

**Tech Stack:** Kotlin 2.x, AGP 8.13.2, Hilt (Dagger), Retrofit + Moshi, kotlinx.coroutines, MockK, JUnit 4, `kotlinx.coroutines.test.runTest`.

---

## Spec reference

Companion spec: `docs/superpowers/specs/2026-05-11-early-access-auto-upgrade-design.md` (commit `eefbbfdb9`). Read it before starting — every architectural decision below is locked there.

## File map (what each file is for)

**New files**

- `app/src/main/java/com/nexio/tv/updater/UpdateChannel.kt` — enum + `fromBuildConfig`. Single source of truth for "which channel is this build."
- `app/src/main/java/com/nexio/tv/updater/ChannelAssetSelector.kt` — filename-prefix-aware wrapper around `AbiSelector`. One responsibility: given assets + channel, pick the right APK.
- `app/src/main/java/com/nexio/tv/core/di/UpdaterModule.kt` — `@Provides` for `UpdateChannel` (constructor-injection doesn't work for enums).
- `app/src/test/java/com/nexio/tv/updater/UpdateChannelTest.kt` — unit tests for the enum.
- `app/src/test/java/com/nexio/tv/updater/ChannelAssetSelectorTest.kt` — unit tests for the selector.
- `app/src/test/java/com/nexio/tv/updater/VersionUtilsTest.kt` — does not exist today; created here for new `pickNewer` tests.

**Modified files**

- `app/build.gradle.kts` — add `UPDATE_CHANNEL` buildConfigField; add `androidComponents.onVariants` block to rename universal-variant APKs.
- `app/src/main/java/com/nexio/tv/updater/VersionUtils.kt` — add `pickNewer(a, b)`.
- `app/src/main/java/com/nexio/tv/updater/UpdateRepository.kt` — inject `UpdateChannel`; branch resolution per channel; soft-miss handling.
- `app/src/main/java/com/nexio/tv/data/remote/api/GitHubReleaseApi.kt` — add `getReleases(owner, repo, perPage)`.
- `app/src/main/java/com/nexio/tv/data/integration/github/GitHubReleaseIntegrationProvider.kt` — add `fetchReleases(owner, repo, perPage)`.
- `app/src/main/java/com/nexio/tv/core/integration/IntegrationApiShapes.kt` — add `GitHubApiShapes.LIST_RELEASES` constant.
- `app/src/test/java/com/nexio/tv/updater/UpdateRepositoryTest.kt` — update existing 9 tests to pass `UpdateChannel.Stable`; add 13 new tests covering EA channel + new failure modes.

---

## Conventions to follow

- **Commit hygiene (CLAUDE.md rule #7, hard):** stage by explicit path only. Never `git add -A`, `git add .`, `git commit -a`, or `git stash`. Each task's commit step lists exact paths.
- **Test style:** JUnit 4 + MockK + `runTest`. Match `UpdateRepositoryTest.kt`. Use `coEvery` for suspending stubs, `assertEquals`/`assertTrue`/`assertNull` from `org.junit.Assert`.
- **Failure messages are part of the contract:** `UpdateRepositoryTest` asserts exact strings (`"Latest release is draft/prerelease"`, etc.). New error messages below are also asserted exactly — keep them in sync between code and tests.
- **Build verification:** after each task, before committing, run the relevant test target:
  - Single test: `./gradlew :app:testReleaseUnitTest --tests com.nexio.tv.updater.<TestClass>` (uses release variant for `BuildConfig.*` constants if a test reads them; otherwise `:app:testDebugUnitTest` is fine).
  - Most updater tests only need a build variant where `com.nexio.tv.BuildConfig` exists — `:app:testDebugUnitTest` is the cheap default. The existing `UpdateRepositoryTest` reads `BuildConfig.GITHUB_OWNER`/`GITHUB_REPO` and works under debug — keep that pattern.
  - Build wiring: `./gradlew :app:assembleUniversalRelease :app:assembleUniversalReleaseEarlyAccess` — verifies both APK outputs land at the expected names. (Requires signing config — if the keystore isn't available locally, `./gradlew :app:assembleUniversalDebug` plus `./gradlew :app:packageUniversalReleaseEarlyAccess` is enough to validate the rename block without producing a signed APK.)

---

## Task 1: Add `BuildConfig.UPDATE_CHANNEL`

**Files:**
- Modify: `app/build.gradle.kts` (defaultConfig block ~line 350 and buildTypes block ~line 429)

- [ ] **Step 1: Add `UPDATE_CHANNEL` to `defaultConfig`**

In `app/build.gradle.kts`, inside `defaultConfig { ... }`, right after the existing `buildConfigField("String", "GITHUB_REPO", "\"nexio\"")` line (around line 409), add:

```kotlin
buildConfigField("String", "UPDATE_CHANNEL", "\"stable\"")
```

- [ ] **Step 2: Make `release` build type explicit about its channel**

Inside `buildTypes { release { ... } }`, after the existing `buildConfigField("boolean", "IS_DEBUG_BUILD", "false")` line (around line 453), add:

```kotlin
buildConfigField("String", "UPDATE_CHANNEL", "\"stable\"")
```

(This is redundant given the `defaultConfig` value, but defends against future `defaultConfig` drift and is symmetric with the EA override below.)

- [ ] **Step 3: Override for `releaseEarlyAccess`**

Inside `buildTypes { create("releaseEarlyAccess") { ... } }`, after `matchingFallbacks += listOf("release")` (around line 476), add:

```kotlin
buildConfigField("String", "UPDATE_CHANNEL", "\"earlyAccess\"")
```

Final EA block should read:

```kotlin
create("releaseEarlyAccess") {
    initWith(getByName("release"))
    applicationIdSuffix = ".earlyaccess"
    versionNameSuffix = "-earlyaccess"
    matchingFallbacks += listOf("release")
    buildConfigField("String", "UPDATE_CHANNEL", "\"earlyAccess\"")
}
```

- [ ] **Step 4: Verify Gradle sync succeeds**

Run: `./gradlew :app:tasks --quiet >/dev/null && echo OK`
Expected: prints `OK`. Any error means the Gradle file is malformed.

- [ ] **Step 5: Verify the buildConfig fields appear after a debug build**

Run: `./gradlew :app:generateDebugBuildConfig --quiet && grep -E 'UPDATE_CHANNEL' app/build/generated/source/buildConfig/debug/com/nexio/tv/BuildConfig.java`
Expected output (debug inherits defaultConfig):
```
  public static final String UPDATE_CHANNEL = "stable";
```

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle.kts
git status -sb | head -3   # confirm only app/build.gradle.kts is staged
git commit -m "$(cat <<'EOF'
build(updater): add BuildConfig.UPDATE_CHANNEL per build type

Adds the channel identifier (stable | earlyAccess) the in-app updater
will consume to decide whether to scan prereleases and which APK asset
to match.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Override APK output filenames for universal variants

**Files:**
- Modify: `app/build.gradle.kts` (add to existing `androidComponents { ... }` block around line 561)

- [ ] **Step 1: Add `onVariants` block that renames universal-flavor APKs**

In `app/build.gradle.kts`, inside the existing `androidComponents { ... }` block (it currently has `beforeVariants` for play selection and `onVariants(selector().withBuildType("debug"))` for the `com.nexiodebug.tv` id override), append:

```kotlin
    onVariants(selector().all()) { variant ->
        val abiFlavor = variant.productFlavors
            .firstOrNull { (dimension, _) -> dimension == "abiPackaging" }
            ?.second
        if (abiFlavor != "universal") return@onVariants
        val target = when (variant.buildType) {
            "release"            -> "nexio-release.apk"
            "releaseEarlyAccess" -> "nexio-earlyaccess.apk"
            else                 -> null
        } ?: return@onVariants
        variant.outputs.forEach { output ->
            output.outputFileName.set(target)
        }
    }
```

Place this *after* the existing `onVariants(selector().withBuildType("debug"))` block and *before* the existing `onVariants(selector().all())` block that adds the generated asset source dir — or merge it in: a single `onVariants(selector().all())` block can do both jobs. **Recommended:** add it as a separate block to keep diffs minimal and responsibilities single.

The final `androidComponents { ... }` shape:

```kotlin
androidComponents {
    beforeVariants(selector().withBuildType("release")) { /* existing — unchanged */ }

    onVariants(selector().withBuildType("debug")) { /* existing — unchanged */ }

    onVariants(selector().all()) { variant ->          // NEW BLOCK
        val abiFlavor = variant.productFlavors
            .firstOrNull { (dimension, _) -> dimension == "abiPackaging" }
            ?.second
        if (abiFlavor != "universal") return@onVariants
        val target = when (variant.buildType) {
            "release"            -> "nexio-release.apk"
            "releaseEarlyAccess" -> "nexio-earlyaccess.apk"
            else                 -> null
        } ?: return@onVariants
        variant.outputs.forEach { output ->
            output.outputFileName.set(target)
        }
    }

    onVariants(selector().all()) { /* existing — addGeneratedSourceDirectory */ }
}
```

- [ ] **Step 2: Verify Gradle sync still succeeds**

Run: `./gradlew :app:tasks --quiet >/dev/null && echo OK`
Expected: `OK`.

- [ ] **Step 3: Verify output filename for universal-releaseEarlyAccess**

Run: `./gradlew :app:packageUniversalReleaseEarlyAccess --quiet && ls -1 app/build/outputs/apk/universal/releaseEarlyAccess/`
Expected: directory listing includes `nexio-earlyaccess.apk`. If signing fails because the keystore is unavailable on this machine, fall back to `./gradlew :app:assembleUniversalReleaseEarlyAccess --dry-run` and skip the file check; the Gradle sync in Step 2 is the load-bearing validation.

- [ ] **Step 4: Verify the rename does NOT touch non-universal variants**

Run: `./gradlew :app:tasks --quiet | grep -E '^  assemble(Arm64|Armv7)Release' | head -5`
Expected: arm64/armv7 release tasks still exist with their default output names (the override returns early for non-universal flavors). No need to actually build them.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts
git status -sb | head -3
git commit -m "$(cat <<'EOF'
build(updater): rename published universal APKs to nexio-release.apk / nexio-earlyaccess.apk

Locks the channel-asset filename contract the channel-aware updater
will match on. Only the universal flavor is renamed; arm64 / armv7
locals keep their default names.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Add `UpdateChannel` enum (TDD)

**Files:**
- Create: `app/src/main/java/com/nexio/tv/updater/UpdateChannel.kt`
- Create test: `app/src/test/java/com/nexio/tv/updater/UpdateChannelTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/nexio/tv/updater/UpdateChannelTest.kt`:

```kotlin
package com.nexio.tv.updater

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateChannelTest {

    @Test
    fun `fromBuildConfig maps stable literal`() {
        assertEquals(UpdateChannel.Stable, UpdateChannel.fromBuildConfig("stable"))
    }

    @Test
    fun `fromBuildConfig maps earlyAccess literal`() {
        assertEquals(UpdateChannel.EarlyAccess, UpdateChannel.fromBuildConfig("earlyAccess"))
    }

    @Test
    fun `fromBuildConfig falls back to Stable for unknown values`() {
        assertEquals(UpdateChannel.Stable, UpdateChannel.fromBuildConfig("nightly"))
    }

    @Test
    fun `fromBuildConfig falls back to Stable for empty string`() {
        assertEquals(UpdateChannel.Stable, UpdateChannel.fromBuildConfig(""))
    }

    @Test
    fun `Stable channel uses nexio-release prefix`() {
        assertEquals("nexio-release", UpdateChannel.Stable.assetPrefix)
    }

    @Test
    fun `EarlyAccess channel uses nexio-earlyaccess prefix`() {
        assertEquals("nexio-earlyaccess", UpdateChannel.EarlyAccess.assetPrefix)
    }
}
```

- [ ] **Step 2: Run the test to confirm it fails to compile**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.updater.UpdateChannelTest`
Expected: compile failure — `Unresolved reference: UpdateChannel`.

- [ ] **Step 3: Implement `UpdateChannel`**

Create `app/src/main/java/com/nexio/tv/updater/UpdateChannel.kt`:

```kotlin
package com.nexio.tv.updater

enum class UpdateChannel(
    val buildConfigValue: String,
    val assetPrefix: String
) {
    Stable("stable", "nexio-release"),
    EarlyAccess("earlyAccess", "nexio-earlyaccess");

    companion object {
        fun fromBuildConfig(raw: String): UpdateChannel =
            entries.firstOrNull { it.buildConfigValue == raw } ?: Stable
    }
}
```

- [ ] **Step 4: Run the tests and confirm they pass**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.updater.UpdateChannelTest`
Expected: 6 tests, all PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/updater/UpdateChannel.kt \
        app/src/test/java/com/nexio/tv/updater/UpdateChannelTest.kt
git status -sb | head -4
git commit -m "$(cat <<'EOF'
feat(updater): add UpdateChannel enum sourced from BuildConfig

Stable and EarlyAccess channels carry their asset-prefix contract
alongside the BuildConfig literal so subsequent channel-aware code can
ask the enum directly.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Add `VersionUtils.pickNewer` (TDD)

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/updater/VersionUtils.kt`
- Create test: `app/src/test/java/com/nexio/tv/updater/VersionUtilsTest.kt`

- [ ] **Step 1: Write failing tests**

Create `app/src/test/java/com/nexio/tv/updater/VersionUtilsTest.kt`:

```kotlin
package com.nexio.tv.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VersionUtilsTest {

    @Test
    fun `pickNewer returns higher version when a is newer`() {
        assertEquals("v0.57", VersionUtils.pickNewer("v0.57", "v0.56-ea1"))
    }

    @Test
    fun `pickNewer returns higher version when b is newer`() {
        assertEquals("v0.57", VersionUtils.pickNewer("v0.56-ea1", "v0.57"))
    }

    @Test
    fun `pickNewer returns b when a is null`() {
        assertEquals("v0.56", VersionUtils.pickNewer(null, "v0.56"))
    }

    @Test
    fun `pickNewer returns a when b is null`() {
        assertEquals("v0.56", VersionUtils.pickNewer("v0.56", null))
    }

    @Test
    fun `pickNewer returns null when both null`() {
        assertNull(VersionUtils.pickNewer(null, null))
    }

    @Test
    fun `pickNewer returns b on equal versions`() {
        // EA channel passes pre as `a`, stable as `b`. Stable should win ties.
        assertEquals("v0.57", VersionUtils.pickNewer("v0.57", "v0.57"))
    }
}
```

- [ ] **Step 2: Run tests, confirm compile failure**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.updater.VersionUtilsTest`
Expected: compile failure — `Unresolved reference: pickNewer`.

- [ ] **Step 3: Add `pickNewer` to `VersionUtils`**

In `app/src/main/java/com/nexio/tv/updater/VersionUtils.kt`, inside the existing `object VersionUtils { ... }`, add (after `isRemoteNewer`):

```kotlin
    fun pickNewer(a: String?, b: String?): String? = when {
        a == null -> b
        b == null -> a
        isRemoteNewer(a, b) -> a
        else -> b
    }
```

The full file becomes:

```kotlin
package com.nexio.tv.updater

internal object VersionUtils {

    fun normalize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw.trim().removePrefix("v").removePrefix("V")
    }

    fun parseVersionParts(raw: String?): List<Int>? {
        val normalized = normalize(raw)
        if (normalized.isBlank()) return null

        val parts = normalized.split('.', '-', '_')
            .filter { it.isNotBlank() }
            .mapNotNull { token -> token.takeWhile { it.isDigit() }.toIntOrNull() }

        return parts.takeIf { it.isNotEmpty() }
    }

    fun isRemoteNewer(remote: String?, local: String?): Boolean {
        val remoteParts = parseVersionParts(remote)
        val localParts = parseVersionParts(local)

        if (remoteParts == null || localParts == null) {
            val r = normalize(remote)
            val l = normalize(local)
            return r.isNotBlank() && l.isNotBlank() && r != l
        }

        val max = maxOf(remoteParts.size, localParts.size)
        for (i in 0 until max) {
            val r = remoteParts.getOrElse(i) { 0 }
            val l = localParts.getOrElse(i) { 0 }
            if (r != l) return r > l
        }
        return false
    }

    fun pickNewer(a: String?, b: String?): String? = when {
        a == null -> b
        b == null -> a
        isRemoteNewer(a, b) -> a
        else -> b
    }
}
```

`VersionUtils` is `internal` — the new test must live in the same module, which it does (`app/src/test/...`).

- [ ] **Step 4: Run tests, confirm pass**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.updater.VersionUtilsTest`
Expected: 6 tests, all PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/updater/VersionUtils.kt \
        app/src/test/java/com/nexio/tv/updater/VersionUtilsTest.kt
git status -sb | head -4
git commit -m "$(cat <<'EOF'
feat(updater): add VersionUtils.pickNewer for cross-channel candidate selection

Returns the higher of two version strings, with the convention that
on equal versions the second argument wins — so callers can pass the
prerelease candidate as `a` and the stable candidate as `b` to make
stable win ties.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Add `ChannelAssetSelector` (TDD)

**Files:**
- Create: `app/src/main/java/com/nexio/tv/updater/ChannelAssetSelector.kt`
- Create test: `app/src/test/java/com/nexio/tv/updater/ChannelAssetSelectorTest.kt`

- [ ] **Step 1: Write failing tests**

Create `app/src/test/java/com/nexio/tv/updater/ChannelAssetSelectorTest.kt`:

```kotlin
package com.nexio.tv.updater

import com.nexio.tv.data.remote.dto.GitHubAssetDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelAssetSelectorTest {

    private fun asset(name: String) = GitHubAssetDto(
        name = name,
        browserDownloadUrl = "https://example.invalid/$name",
        size = 1L,
        contentType = "application/vnd.android.package-archive"
    )

    @Test
    fun `Stable picks nexio-release asset when both channels present`() {
        val picked = ChannelAssetSelector.choose(
            UpdateChannel.Stable,
            listOf(asset("nexio-earlyaccess.apk"), asset("nexio-release.apk"))
        )
        assertEquals("nexio-release.apk", picked?.name)
    }

    @Test
    fun `EarlyAccess picks nexio-earlyaccess asset when both channels present`() {
        val picked = ChannelAssetSelector.choose(
            UpdateChannel.EarlyAccess,
            listOf(asset("nexio-release.apk"), asset("nexio-earlyaccess.apk"))
        )
        assertEquals("nexio-earlyaccess.apk", picked?.name)
    }

    @Test
    fun `Stable rejects unprefixed apk`() {
        val picked = ChannelAssetSelector.choose(
            UpdateChannel.Stable,
            listOf(asset("app-universal-release.apk"))
        )
        assertNull(picked)
    }

    @Test
    fun `EarlyAccess returns null when no early-access asset present`() {
        val picked = ChannelAssetSelector.choose(
            UpdateChannel.EarlyAccess,
            listOf(asset("nexio-release.apk"))
        )
        assertNull(picked)
    }

    @Test
    fun `Stable returns null on empty list`() {
        assertNull(ChannelAssetSelector.choose(UpdateChannel.Stable, emptyList()))
    }

    @Test
    fun `Prefix match is case-insensitive`() {
        val picked = ChannelAssetSelector.choose(
            UpdateChannel.EarlyAccess,
            listOf(asset("NEXIO-EARLYACCESS.APK"))
        )
        assertEquals("NEXIO-EARLYACCESS.APK", picked?.name)
    }

    @Test
    fun `Non-apk files in channel are rejected`() {
        // e.g. a checksum file named nexio-earlyaccess.apk.sha256
        val picked = ChannelAssetSelector.choose(
            UpdateChannel.EarlyAccess,
            listOf(asset("nexio-earlyaccess.apk.sha256"))
        )
        assertNull(picked)
    }
}
```

- [ ] **Step 2: Run tests, confirm compile failure**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.updater.ChannelAssetSelectorTest`
Expected: compile failure — `Unresolved reference: ChannelAssetSelector`.

- [ ] **Step 3: Implement `ChannelAssetSelector`**

Create `app/src/main/java/com/nexio/tv/updater/ChannelAssetSelector.kt`:

```kotlin
package com.nexio.tv.updater

import com.nexio.tv.data.remote.dto.GitHubAssetDto

internal object ChannelAssetSelector {

    fun choose(
        channel: UpdateChannel,
        assets: List<GitHubAssetDto>
    ): GitHubAssetDto? {
        val scoped = assets.filter { asset ->
            asset.name.startsWith(channel.assetPrefix, ignoreCase = true) &&
                asset.name.endsWith(".apk", ignoreCase = true)
        }
        return AbiSelector.chooseBestApkAsset(scoped)
    }
}
```

- [ ] **Step 4: Run tests, confirm pass**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.updater.ChannelAssetSelectorTest`
Expected: 7 tests, all PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/updater/ChannelAssetSelector.kt \
        app/src/test/java/com/nexio/tv/updater/ChannelAssetSelectorTest.kt
git status -sb | head -4
git commit -m "$(cat <<'EOF'
feat(updater): add ChannelAssetSelector with filename-prefix scoping

Filters a release's asset list to the channel's nexio-release* or
nexio-earlyaccess* prefix before delegating to the existing
AbiSelector for ABI tie-breaks, so the wrong-channel APK can never
be picked even when both live in the same release.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Extend `GitHubReleaseApi` + integration provider with `getReleases` (TDD)

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/integration/IntegrationApiShapes.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/remote/api/GitHubReleaseApi.kt`
- Modify: `app/src/main/java/com/nexio/tv/data/integration/github/GitHubReleaseIntegrationProvider.kt`
- Modify: `app/src/test/java/com/nexio/tv/updater/GitHubReleaseIntegrationProviderTest.kt` (extend with `fetchReleases` cases)

- [ ] **Step 1: Read the existing provider test to match style**

Read `app/src/test/java/com/nexio/tv/updater/GitHubReleaseIntegrationProviderTest.kt` — it tests `fetchLatestRelease`. The new tests for `fetchReleases` should follow the same MockK + `IntegrationRuntime` faking pattern. If after reading, the integration-runtime fake setup looks too involved to extend in one task, factor the shared fixture into a helper inside that test file.

- [ ] **Step 2: Write failing tests for `fetchReleases`**

Append to `app/src/test/java/com/nexio/tv/updater/GitHubReleaseIntegrationProviderTest.kt` (inside the existing test class):

```kotlin
    @Test
    fun `fetchReleases returns list success when api returns 200`() = runTest {
        val api = mockk<GitHubReleaseApi>()
        val list = listOf(
            GitHubReleaseDto(tagName = "v0.57", prerelease = false),
            GitHubReleaseDto(tagName = "v0.56", prerelease = true)
        )
        coEvery { api.getReleases(owner = "o", repo = "r", perPage = 10) } returns
            Response.success(list)
        // ... assemble provider with the existing IntegrationRuntime fake pattern
        val provider = GitHubReleaseIntegrationProvider(fakeRuntime(), api)

        val result = provider.fetchReleases(owner = "o", repo = "r", perPage = 10)

        assertTrue(result is IntegrationCallResult.Success)
        assertEquals(2, (result as IntegrationCallResult.Success).value.size)
        coVerify(exactly = 1) { api.getReleases("o", "r", 10) }
    }

    @Test
    fun `fetchReleases returns HttpError when api returns non-2xx`() = runTest {
        val api = mockk<GitHubReleaseApi>()
        coEvery { api.getReleases("o", "r", 10) } returns
            Response.error(404, okhttp3.ResponseBody.create(null, ""))
        val provider = GitHubReleaseIntegrationProvider(fakeRuntime(), api)

        val result = provider.fetchReleases(owner = "o", repo = "r", perPage = 10)

        assertTrue(result is IntegrationCallResult.HttpError)
        assertEquals(404, (result as IntegrationCallResult.HttpError).statusCode)
    }

    @Test
    fun `fetchReleases returns NetworkError on thrown exception`() = runTest {
        val api = mockk<GitHubReleaseApi>()
        coEvery { api.getReleases("o", "r", 10) } throws java.io.IOException("offline")
        val provider = GitHubReleaseIntegrationProvider(fakeRuntime(), api)

        val result = provider.fetchReleases(owner = "o", repo = "r", perPage = 10)

        assertTrue(result is IntegrationCallResult.NetworkError)
    }
```

`fakeRuntime()` is the existing test fixture in this file — reuse it. If it does not currently exist as a named helper, factor it out from the existing `fetchLatestRelease` test setup as a private function.

- [ ] **Step 3: Run tests, confirm compile failure**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.updater.GitHubReleaseIntegrationProviderTest`
Expected: compile failure — `Unresolved reference: getReleases` and/or `fetchReleases`.

- [ ] **Step 4: Add the `LIST_RELEASES` API-shape constant**

In `app/src/main/java/com/nexio/tv/core/integration/IntegrationApiShapes.kt`, extend `GitHubApiShapes`:

```kotlin
object GitHubApiShapes {
    const val LATEST_RELEASE = "github.latest_release"
    const val LIST_RELEASES = "github.list_releases"
    const val ASSET_DOWNLOAD = "github.asset_download"
}
```

- [ ] **Step 5: Add `getReleases` to the Retrofit interface**

In `app/src/main/java/com/nexio/tv/data/remote/api/GitHubReleaseApi.kt`:

```kotlin
package com.nexio.tv.data.remote.api

import com.nexio.tv.data.remote.dto.GitHubReleaseDto
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface GitHubReleaseApi {

    @GET("repos/{owner}/{repo}/releases/latest")
    suspend fun getLatestRelease(
        @Path("owner") owner: String,
        @Path("repo") repo: String
    ): Response<GitHubReleaseDto>

    @GET("repos/{owner}/{repo}/releases")
    suspend fun getReleases(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Query("per_page") perPage: Int
    ): Response<List<GitHubReleaseDto>>
}
```

- [ ] **Step 6: Add `fetchReleases` to the integration provider**

In `app/src/main/java/com/nexio/tv/data/integration/github/GitHubReleaseIntegrationProvider.kt`, append a sibling method to `fetchLatestRelease`:

```kotlin
    suspend fun fetchReleases(
        owner: String,
        repo: String,
        perPage: Int = 10
    ): IntegrationCallResult<List<GitHubReleaseDto>> {
        return runtime.call(
            IntegrationCallSpec(
                provider = IntegrationProvider.GITHUB,
                workClass = IntegrationWorkClass.USER_VISIBLE,
                scope = IntegrationScope.ProviderConfig("github:$owner:$repo"),
                apiShapeId = GitHubApiShapes.LIST_RELEASES,
                operationKey = "github.release.fetchReleases",
                call = {
                    try {
                        val response = gitHubReleaseApi.getReleases(
                            owner = owner, repo = repo, perPage = perPage
                        )
                        val body = response.body()
                        when {
                            !response.isSuccessful -> IntegrationCallResult.HttpError(
                                statusCode = response.code(),
                                reason = "github_list_releases_failed"
                            )
                            body == null -> IntegrationCallResult.Missing
                            else -> IntegrationCallResult.Success(body)
                        }
                    } catch (exception: Exception) {
                        if (exception is CancellationException) throw exception
                        IntegrationCallResult.NetworkError(exception)
                    }
                }
            )
        )
    }
```

- [ ] **Step 7: Run tests, confirm pass**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.updater.GitHubReleaseIntegrationProviderTest`
Expected: all existing `fetchLatestRelease` tests still PASS, plus 3 new `fetchReleases` tests PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/integration/IntegrationApiShapes.kt \
        app/src/main/java/com/nexio/tv/data/remote/api/GitHubReleaseApi.kt \
        app/src/main/java/com/nexio/tv/data/integration/github/GitHubReleaseIntegrationProvider.kt \
        app/src/test/java/com/nexio/tv/updater/GitHubReleaseIntegrationProviderTest.kt
git status -sb | head -6
git commit -m "$(cat <<'EOF'
feat(updater): add GitHubReleaseApi.getReleases + provider.fetchReleases

Exposes the paginated /releases endpoint so the early-access channel
can locate the newest GitHub prerelease alongside the existing
fetchLatestRelease call.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: Wire `UpdateChannel` into Hilt

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/di/UpdaterModule.kt`

- [ ] **Step 1: Create the module**

Create `app/src/main/java/com/nexio/tv/core/di/UpdaterModule.kt`:

```kotlin
package com.nexio.tv.core.di

import com.nexio.tv.BuildConfig
import com.nexio.tv.updater.UpdateChannel
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object UpdaterModule {

    @Provides
    @Singleton
    fun provideUpdateChannel(): UpdateChannel =
        UpdateChannel.fromBuildConfig(BuildConfig.UPDATE_CHANNEL)
}
```

- [ ] **Step 2: Verify the project compiles**

Run: `./gradlew :app:compileDebugKotlin --quiet && echo OK`
Expected: `OK`. (Even though `UpdateRepository` doesn't yet inject `UpdateChannel`, the module compiles standalone.)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/di/UpdaterModule.kt
git status -sb | head -3
git commit -m "$(cat <<'EOF'
feat(updater): provide UpdateChannel via Hilt from BuildConfig

Enum bindings can't use constructor injection, so the runtime channel
value is provided through a singleton module that reads the
per-build-type BuildConfig.UPDATE_CHANNEL literal.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: Update existing `UpdateRepositoryTest` cases to pass `UpdateChannel.Stable`

The existing 9 tests in `UpdateRepositoryTest.kt` instantiate `UpdateRepository(provider)`. Once we add the channel param, they must pass `UpdateChannel.Stable` to keep the same semantics.

**Files:**
- Modify: `app/src/test/java/com/nexio/tv/updater/UpdateRepositoryTest.kt`

- [ ] **Step 1: Search-and-replace existing constructor calls**

In `app/src/test/java/com/nexio/tv/updater/UpdateRepositoryTest.kt`, replace every occurrence of:

```kotlin
val repository = UpdateRepository(provider)
```

with:

```kotlin
val repository = UpdateRepository(provider, UpdateChannel.Stable)
```

There are 9 such occurrences (one per `@Test` method). Do not change anything else in those tests yet — the channel-aware `UpdateRepository` from Task 9 will preserve current behavior for the Stable branch, so the assertions should keep passing once Task 9 ships.

There is one test (`'getLatestUpdate maps provider release into app update'`) where the asset is `nexio-arm64-v8a.apk`. Under the new channel-prefix matcher with `UpdateChannel.Stable`, that asset does NOT start with `nexio-release` and would now fail. Update the asset name in *that test* to `nexio-release-arm64-v8a.apk`, AND in the assertion below:

```kotlin
assertEquals("nexio-release-arm64-v8a.apk", update.assetName)
```

(Original asserted `nexio-arm64-v8a.apk` — must update both the input and the assertion together. Four test methods reference `nexio-arm64-v8a.apk` as input fixture — `maps provider release into app update`, `uses release name as title when tag is set`, `uses tag as title when release name is blank`, and `fails when tagName is blank`. Update the asset filename in all four to `nexio-release-arm64-v8a.apk` and update the asserted asset name in `maps provider release into app update`.)

- [ ] **Step 2: Confirm test file still compiles**

Run: `./gradlew :app:compileDebugUnitTestKotlin --quiet && echo OK`
Expected: `OK`. (Tests will fail to run until Task 9 ships the channel-aware repository — that's fine; we'll run them in Task 9.)

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/nexio/tv/updater/UpdateRepositoryTest.kt
git status -sb | head -3
git commit -m "$(cat <<'EOF'
test(updater): pin existing UpdateRepository tests to UpdateChannel.Stable

Prepares the existing test suite for the channel-aware UpdateRepository
constructor that lands next. Also renames in-test asset fixtures from
nexio-arm64-v8a.apk to nexio-release-arm64-v8a.apk so the new
ChannelAssetSelector accepts them under the Stable channel prefix.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 9: Make `UpdateRepository` channel-aware (TDD)

This is the central change. We rewrite `getLatestUpdate` to branch on the injected `UpdateChannel`, add the EA-channel fan-out, and update the asset selection path to go through `ChannelAssetSelector`.

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/updater/UpdateRepository.kt`
- Modify: `app/src/test/java/com/nexio/tv/updater/UpdateRepositoryTest.kt` (add new tests)

- [ ] **Step 1: Write new failing tests for EA channel**

Append to `app/src/test/java/com/nexio/tv/updater/UpdateRepositoryTest.kt` (inside the existing class):

```kotlin
    // ----- EA channel: candidate selection -----

    @Test
    fun `EA channel returns EA asset from stable release when only stable exists`() = runTest {
        val provider = mockk<GitHubReleaseIntegrationProvider>()
        coEvery { provider.fetchLatestRelease(any(), any()) } returns
            IntegrationCallResult.Success(
                GitHubReleaseDto(
                    tagName = "v0.57",
                    prerelease = false,
                    htmlUrl = "https://example.invalid/v0.57",
                    assets = listOf(
                        GitHubAssetDto(
                            name = "nexio-release.apk",
                            browserDownloadUrl = "https://example.invalid/nexio-release.apk",
                            size = 10L,
                            contentType = null
                        ),
                        GitHubAssetDto(
                            name = "nexio-earlyaccess.apk",
                            browserDownloadUrl = "https://example.invalid/nexio-earlyaccess.apk",
                            size = 11L,
                            contentType = null
                        )
                    )
                )
            )
        coEvery { provider.fetchReleases(any(), any(), any()) } returns
            IntegrationCallResult.Success(emptyList())

        val result = UpdateRepository(provider, UpdateChannel.EarlyAccess).getLatestUpdate()

        assertTrue(result.isSuccess)
        assertEquals("v0.57", result.getOrThrow().tag)
        assertEquals("nexio-earlyaccess.apk", result.getOrThrow().assetName)
    }

    @Test
    fun `EA channel returns prerelease when stable lookup is 404 and prerelease present`() = runTest {
        val provider = mockk<GitHubReleaseIntegrationProvider>()
        coEvery { provider.fetchLatestRelease(any(), any()) } returns
            IntegrationCallResult.HttpError(statusCode = 404)
        coEvery { provider.fetchReleases(any(), any(), any()) } returns
            IntegrationCallResult.Success(
                listOf(
                    GitHubReleaseDto(
                        tagName = "v0.56",
                        prerelease = true,
                        assets = listOf(
                            GitHubAssetDto(
                                name = "nexio-earlyaccess.apk",
                                browserDownloadUrl = "https://example.invalid/ea.apk",
                                size = 5L,
                                contentType = null
                            )
                        )
                    )
                )
            )

        val result = UpdateRepository(provider, UpdateChannel.EarlyAccess).getLatestUpdate()

        assertTrue(result.isSuccess)
        assertEquals("v0.56", result.getOrThrow().tag)
    }

    @Test
    fun `EA channel picks prerelease when prerelease is newer than stable`() = runTest {
        val provider = mockk<GitHubReleaseIntegrationProvider>()
        coEvery { provider.fetchLatestRelease(any(), any()) } returns
            IntegrationCallResult.Success(
                GitHubReleaseDto(
                    tagName = "v0.55",
                    prerelease = false,
                    assets = listOf(
                        GitHubAssetDto(
                            name = "nexio-earlyaccess.apk",
                            browserDownloadUrl = "https://example.invalid/v055-ea.apk",
                            size = 1L,
                            contentType = null
                        )
                    )
                )
            )
        coEvery { provider.fetchReleases(any(), any(), any()) } returns
            IntegrationCallResult.Success(
                listOf(
                    GitHubReleaseDto(
                        tagName = "v0.56",
                        prerelease = true,
                        assets = listOf(
                            GitHubAssetDto(
                                name = "nexio-earlyaccess.apk",
                                browserDownloadUrl = "https://example.invalid/v056-ea.apk",
                                size = 1L,
                                contentType = null
                            )
                        )
                    )
                )
            )

        val result = UpdateRepository(provider, UpdateChannel.EarlyAccess).getLatestUpdate()

        assertEquals("v0.56", result.getOrThrow().tag)
        assertEquals("https://example.invalid/v056-ea.apk", result.getOrThrow().assetUrl)
    }

    @Test
    fun `EA channel picks stable when stable is newer than prerelease`() = runTest {
        val provider = mockk<GitHubReleaseIntegrationProvider>()
        coEvery { provider.fetchLatestRelease(any(), any()) } returns
            IntegrationCallResult.Success(
                GitHubReleaseDto(
                    tagName = "v0.57",
                    prerelease = false,
                    assets = listOf(
                        GitHubAssetDto(
                            name = "nexio-earlyaccess.apk",
                            browserDownloadUrl = "https://example.invalid/v057-ea.apk",
                            size = 1L,
                            contentType = null
                        )
                    )
                )
            )
        coEvery { provider.fetchReleases(any(), any(), any()) } returns
            IntegrationCallResult.Success(
                listOf(
                    GitHubReleaseDto(
                        tagName = "v0.56",
                        prerelease = true,
                        assets = listOf(
                            GitHubAssetDto(
                                name = "nexio-earlyaccess.apk",
                                browserDownloadUrl = "https://example.invalid/v056-ea.apk",
                                size = 1L,
                                contentType = null
                            )
                        )
                    )
                )
            )

        val result = UpdateRepository(provider, UpdateChannel.EarlyAccess).getLatestUpdate()

        assertEquals("v0.57", result.getOrThrow().tag)
        assertEquals("https://example.invalid/v057-ea.apk", result.getOrThrow().assetUrl)
    }

    @Test
    fun `EA channel ties go to stable`() = runTest {
        val provider = mockk<GitHubReleaseIntegrationProvider>()
        coEvery { provider.fetchLatestRelease(any(), any()) } returns
            IntegrationCallResult.Success(
                GitHubReleaseDto(
                    tagName = "v0.57",
                    prerelease = false,
                    name = "Stable",
                    assets = listOf(
                        GitHubAssetDto(
                            name = "nexio-earlyaccess.apk",
                            browserDownloadUrl = "https://example.invalid/stable.apk",
                            size = 1L,
                            contentType = null
                        )
                    )
                )
            )
        coEvery { provider.fetchReleases(any(), any(), any()) } returns
            IntegrationCallResult.Success(
                listOf(
                    GitHubReleaseDto(
                        tagName = "v0.57",
                        prerelease = true,
                        name = "Pre",
                        assets = listOf(
                            GitHubAssetDto(
                                name = "nexio-earlyaccess.apk",
                                browserDownloadUrl = "https://example.invalid/pre.apk",
                                size = 1L,
                                contentType = null
                            )
                        )
                    )
                )
            )

        val result = UpdateRepository(provider, UpdateChannel.EarlyAccess).getLatestUpdate()

        assertEquals("Stable", result.getOrThrow().title)
        assertEquals("https://example.invalid/stable.apk", result.getOrThrow().assetUrl)
    }

    @Test
    fun `EA channel fails when both arms return null candidates`() = runTest {
        val provider = mockk<GitHubReleaseIntegrationProvider>()
        coEvery { provider.fetchLatestRelease(any(), any()) } returns
            IntegrationCallResult.HttpError(statusCode = 404)
        coEvery { provider.fetchReleases(any(), any(), any()) } returns
            IntegrationCallResult.Success(emptyList())

        val result = UpdateRepository(provider, UpdateChannel.EarlyAccess).getLatestUpdate()

        assertTrue(result.isFailure)
        assertEquals(
            "No release found for early-access channel",
            result.exceptionOrNull()?.message
        )
    }

    @Test
    fun `EA channel propagates stable HttpError when prerelease arm also failed`() = runTest {
        val provider = mockk<GitHubReleaseIntegrationProvider>()
        coEvery { provider.fetchLatestRelease(any(), any()) } returns
            IntegrationCallResult.HttpError(statusCode = 503)
        coEvery { provider.fetchReleases(any(), any(), any()) } returns
            IntegrationCallResult.NetworkError(IllegalStateException("offline"))

        val result = UpdateRepository(provider, UpdateChannel.EarlyAccess).getLatestUpdate()

        assertTrue(result.isFailure)
        // Either message is acceptable; the contract is that we surface an error,
        // not silent success. Prefer the stable-arm error to match the existing
        // Stable channel diagnostic.
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(
            "Expected to surface a GitHub error, got: $message",
            message.startsWith("GitHub API error:") || message.startsWith("Unable to contact GitHub:")
        )
    }

    @Test
    fun `EA channel soft-misses prerelease arm error when stable candidate succeeded`() = runTest {
        val provider = mockk<GitHubReleaseIntegrationProvider>()
        coEvery { provider.fetchLatestRelease(any(), any()) } returns
            IntegrationCallResult.Success(
                GitHubReleaseDto(
                    tagName = "v0.57",
                    prerelease = false,
                    assets = listOf(
                        GitHubAssetDto(
                            name = "nexio-earlyaccess.apk",
                            browserDownloadUrl = "https://example.invalid/ea.apk",
                            size = 1L,
                            contentType = null
                        )
                    )
                )
            )
        coEvery { provider.fetchReleases(any(), any(), any()) } returns
            IntegrationCallResult.NetworkError(IllegalStateException("offline"))

        val result = UpdateRepository(provider, UpdateChannel.EarlyAccess).getLatestUpdate()

        assertTrue(result.isSuccess)
        assertEquals("v0.57", result.getOrThrow().tag)
    }

    @Test
    fun `EA channel fails when winner has no EA asset`() = runTest {
        val provider = mockk<GitHubReleaseIntegrationProvider>()
        coEvery { provider.fetchLatestRelease(any(), any()) } returns
            IntegrationCallResult.Success(
                GitHubReleaseDto(
                    tagName = "v0.57",
                    prerelease = false,
                    assets = listOf(
                        GitHubAssetDto(
                            name = "nexio-release.apk",
                            browserDownloadUrl = "https://example.invalid/stable.apk",
                            size = 1L,
                            contentType = null
                        )
                    )
                )
            )
        coEvery { provider.fetchReleases(any(), any(), any()) } returns
            IntegrationCallResult.Success(emptyList())

        val result = UpdateRepository(provider, UpdateChannel.EarlyAccess).getLatestUpdate()

        assertTrue(result.isFailure)
        assertEquals(
            "Release v0.57 has no nexio-earlyaccess.apk asset",
            result.exceptionOrNull()?.message
        )
    }

    @Test
    fun `Stable channel fails when release has no nexio-release asset`() = runTest {
        val provider = mockk<GitHubReleaseIntegrationProvider>()
        coEvery { provider.fetchLatestRelease(any(), any()) } returns
            IntegrationCallResult.Success(
                GitHubReleaseDto(
                    tagName = "v0.57",
                    prerelease = false,
                    assets = listOf(
                        GitHubAssetDto(
                            name = "nexio-earlyaccess.apk",
                            browserDownloadUrl = "https://example.invalid/ea.apk",
                            size = 1L,
                            contentType = null
                        )
                    )
                )
            )

        val result = UpdateRepository(provider, UpdateChannel.Stable).getLatestUpdate()

        assertTrue(result.isFailure)
        assertEquals(
            "Release v0.57 has no nexio-release.apk asset",
            result.exceptionOrNull()?.message
        )
    }
```

- [ ] **Step 2: Run tests, confirm they fail**

Run: `./gradlew :app:testDebugUnitTest --tests com.nexio.tv.updater.UpdateRepositoryTest`
Expected: compile failure on `UpdateRepository(provider, UpdateChannel.Stable)` and `UpdateChannel.EarlyAccess` arguments because the constructor doesn't accept a channel yet.

- [ ] **Step 3: Rewrite `UpdateRepository.kt`**

Replace the entire body of `app/src/main/java/com/nexio/tv/updater/UpdateRepository.kt` with:

```kotlin
package com.nexio.tv.updater

import com.nexio.tv.BuildConfig
import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.data.integration.github.GitHubReleaseIntegrationProvider
import com.nexio.tv.data.remote.dto.GitHubAssetDto
import com.nexio.tv.data.remote.dto.GitHubReleaseDto
import com.nexio.tv.updater.model.AppUpdate
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

@Singleton
class UpdateRepository @Inject constructor(
    private val gitHubReleaseIntegrationProvider: GitHubReleaseIntegrationProvider,
    private val channel: UpdateChannel
) {

    suspend fun getLatestUpdate(): Result<AppUpdate> = try {
        when (channel) {
            UpdateChannel.Stable -> resolveStable()
            UpdateChannel.EarlyAccess -> resolveEarlyAccess()
        }
    } catch (exception: Exception) {
        if (exception is CancellationException) throw exception
        Result.failure(exception)
    }

    private suspend fun resolveStable(): Result<AppUpdate> {
        val dto = fetchLatestOrThrow()
        if (dto.draft || dto.prerelease) {
            error("Latest release is draft/prerelease")
        }
        val tag = dto.tagName?.takeIf { it.isNotBlank() }
            ?: error("Release has no tag")
        val asset = ChannelAssetSelector.choose(UpdateChannel.Stable, dto.assets)
            ?: error("Release $tag has no nexio-release.apk asset")
        return Result.success(toAppUpdate(dto, tag, asset))
    }

    private suspend fun resolveEarlyAccess(): Result<AppUpdate> = coroutineScope {
        val stableDeferred = async {
            gitHubReleaseIntegrationProvider.fetchLatestRelease(
                owner = BuildConfig.GITHUB_OWNER,
                repo = BuildConfig.GITHUB_REPO
            )
        }
        val preDeferred = async {
            gitHubReleaseIntegrationProvider.fetchReleases(
                owner = BuildConfig.GITHUB_OWNER,
                repo = BuildConfig.GITHUB_REPO,
                perPage = 10
            )
        }

        val stableResult = stableDeferred.await()
        val preResult = preDeferred.await()

        val stableCandidate: GitHubReleaseDto? = when (stableResult) {
            is IntegrationCallResult.Success -> stableResult.value.takeIf {
                !it.draft && !it.prerelease
            }
            is IntegrationCallResult.HttpError -> if (stableResult.statusCode == 404) null
                else throw IllegalStateException("GitHub API error: ${stableResult.statusCode}")
            is IntegrationCallResult.NetworkError -> {
                val detail = stableResult.throwable.message?.takeIf { it.isNotBlank() }
                    ?: stableResult.throwable::class.simpleName
                    ?: "unknown error"
                throw IllegalStateException("Unable to contact GitHub: $detail")
            }
            IntegrationCallResult.Missing -> null
        }

        val preCandidate: GitHubReleaseDto? = when (preResult) {
            is IntegrationCallResult.Success -> preResult.value.firstOrNull {
                it.prerelease && !it.draft
            }
            is IntegrationCallResult.HttpError -> null
            is IntegrationCallResult.NetworkError -> null
            IntegrationCallResult.Missing -> null
        }

        // Soft-miss only if the OTHER arm produced a candidate; otherwise the
        // earlier throw on stableResult already propagated.
        if (stableCandidate == null && preCandidate == null) {
            return@coroutineScope Result.failure<AppUpdate>(
                IllegalStateException("No release found for early-access channel")
            )
        }

        val winnerTag = VersionUtils.pickNewer(
            preCandidate?.tagName,
            stableCandidate?.tagName
        ) ?: error("No release found for early-access channel")

        val winner = listOfNotNull(preCandidate, stableCandidate)
            .first { it.tagName == winnerTag }

        val tag = winner.tagName?.takeIf { it.isNotBlank() }
            ?: error("Release has no tag")
        val asset = ChannelAssetSelector.choose(UpdateChannel.EarlyAccess, winner.assets)
            ?: error("Release $tag has no nexio-earlyaccess.apk asset")

        Result.success(toAppUpdate(winner, tag, asset))
    }

    private suspend fun fetchLatestOrThrow(): GitHubReleaseDto {
        return when (val result = gitHubReleaseIntegrationProvider.fetchLatestRelease(
            owner = BuildConfig.GITHUB_OWNER,
            repo = BuildConfig.GITHUB_REPO
        )) {
            is IntegrationCallResult.Success -> result.value
            is IntegrationCallResult.HttpError -> error("GitHub API error: ${result.statusCode}")
            is IntegrationCallResult.NetworkError -> {
                val detail = result.throwable.message?.takeIf { it.isNotBlank() }
                    ?: result.throwable::class.simpleName
                    ?: "unknown error"
                error("Unable to contact GitHub: $detail")
            }
            IntegrationCallResult.Missing -> error("Empty GitHub release response")
        }
    }

    private fun toAppUpdate(
        dto: GitHubReleaseDto,
        tag: String,
        asset: GitHubAssetDto
    ): AppUpdate = AppUpdate(
        tag = tag,
        title = dto.name?.takeIf { it.isNotBlank() } ?: tag,
        notes = dto.body.orEmpty(),
        releaseUrl = dto.htmlUrl,
        assetName = asset.name,
        assetUrl = asset.browserDownloadUrl,
        assetSizeBytes = asset.size
    )
}
```

Notes on the rewrite:

- Stable path semantics are preserved: same error messages, same DTO mapping, but `ChannelAssetSelector` now scopes to `nexio-release*` so the new error message `"Release <tag> has no nexio-release.apk asset"` replaces the older `"No APK asset found in release"` (covered by a new Stable test in Step 1).
- The single outer `try/catch` wraps both branches and is the only place `CancellationException` is rethrown.
- The EA branch uses `coroutineScope { async/async }` per CLAUDE.md rule #6 — small DTOs, awaited and folded, no large value captured as an outer-fun local across the fan-out.
- Stable-arm `HttpError` other than 404 throws to be caught by the outer `try/catch` → `Result.failure`. This means a transient 503 on the stable lookup currently surfaces as a failure even if the prerelease arm succeeded. This is a *deliberate* trade-off: a transient stable-arm error is more likely to mean "we missed a stable release we should have shown" than "the prerelease is fine on its own." If you want the symmetric soft-miss instead, replace the `throw IllegalStateException(...)` lines with `null` — the tests cover both behaviors via `'EA channel propagates stable HttpError when prerelease arm also failed'`; you'd need to add a positive-case "EA channel soft-misses stable arm error when prerelease succeeded" if going the symmetric route. **Stick with the current design unless the test reveals a real problem.**

- [ ] **Step 4: Run all updater tests**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.updater.*'`
Expected: all tests PASS — the 9 updated Stable tests, 10 new EA + new-error tests, plus `UpdateChannelTest`, `ChannelAssetSelectorTest`, `VersionUtilsTest`, `GitHubReleaseIntegrationProviderTest`, `GitHubAssetDownloadIntegrationProviderTest`, `ApkDownloaderTest`.

If `'EA channel propagates stable HttpError when prerelease arm also failed'` fails because the new code soft-misses both arms before the throw is reached, re-order `resolveEarlyAccess()` so the stable-arm error is thrown *before* the prerelease arm's null is converted. The fix: move the `is IntegrationCallResult.HttpError -> ...` throw above the `preCandidate` resolution and guard with `if (preResult is Success && preResult.value.any { it.prerelease && !it.draft }) null else throw` — i.e., only soft-miss when prerelease succeeded.

If many tests fail with "unmocked method" errors from MockK, you forgot to `coEvery` one of the two provider methods. The new `resolveEarlyAccess` always calls both — every EA test must stub `fetchLatestRelease` *and* `fetchReleases`. The Stable tests still call only `fetchLatestRelease`.

- [ ] **Step 5: Run the full unit test suite as a regression check**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS. (This is large — ~2-5 min per CLAUDE.md `forkEvery = 10`.) If unrelated tests fail, investigate before continuing.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/updater/UpdateRepository.kt \
        app/src/test/java/com/nexio/tv/updater/UpdateRepositoryTest.kt
git status -sb | head -3
git commit -m "$(cat <<'EOF'
feat(updater): make UpdateRepository channel-aware (stable + earlyAccess)

Stable channel keeps single fetchLatestRelease call with prerelease
gate. Early-access channel fans out to fetchLatestRelease +
fetchReleases, picks the higher-versioned candidate via
VersionUtils.pickNewer (stable wins ties), and resolves the asset
through ChannelAssetSelector so the wrong-channel APK can never be
installed. Soft-misses prerelease arm errors when stable candidate
succeeded; propagates stable arm errors so transient outages don't
silently hide a missed stable release.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 10: Wire `UpdateChannel` injection at the call-site verification

`UpdateRepository`'s `@Inject constructor` now has two parameters; Hilt needs to resolve `UpdateChannel`. Task 7 created the provider. Verify the graph compiles by running the full module's Hilt processor.

**Files:**
- No new files. Verification step only.

- [ ] **Step 1: Run a debug build to exercise the Hilt processor**

Run: `./gradlew :app:assembleDebug --quiet && echo OK`
Expected: `OK`. If Hilt complains about a missing binding for `UpdateChannel`, recheck `app/src/main/java/com/nexio/tv/core/di/UpdaterModule.kt` from Task 7 — confirm `@InstallIn(SingletonComponent::class)`, `@Provides`, `@Singleton`, and that the file is under `com.nexio.tv.core.di` package (which is scanned by the existing Hilt setup; other `core/di` modules already work).

- [ ] **Step 2: Run a release-EA build to exercise the EA-specific `BuildConfig.UPDATE_CHANNEL`**

Run: `./gradlew :app:packageUniversalReleaseEarlyAccess --quiet && grep -E 'UPDATE_CHANNEL' app/build/generated/source/buildConfig/universalReleaseEarlyAccess/com/nexio/tv/BuildConfig.java`
Expected:
```
  public static final String UPDATE_CHANNEL = "earlyAccess";
```
(`packageUniversal*` may fail if the keystore isn't on this machine; in that case `./gradlew :app:generateUniversalReleaseEarlyAccessBuildConfig` is the minimum that confirms the constant.)

- [ ] **Step 3: No code change → no commit.** Move on.

---

## Task 11: Add a one-line diagnostic log in `UpdateRepository`

Small enough to ride alongside the previous task, but kept separate for a clean revert if it ever gets noisy.

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/updater/UpdateRepository.kt`

- [ ] **Step 1: Add the log statement**

In `resolveStable()`, immediately before `return Result.success(toAppUpdate(...))`, add:

```kotlin
android.util.Log.i("UpdateRepository", "channel=stable winner=$tag")
```

In `resolveEarlyAccess()`, immediately before `Result.success(toAppUpdate(...))`, add:

```kotlin
android.util.Log.i(
    "UpdateRepository",
    "channel=earlyAccess winner=$tag (stable=${stableCandidate?.tagName}, pre=${preCandidate?.tagName})"
)
```

Use the fully-qualified `android.util.Log` (no import) since this file currently has no `android.util.Log` import and unit tests run on the JVM where the Android shadow returns 0 — adding the import is fine but unnecessary; either is acceptable.

- [ ] **Step 2: Re-run updater tests**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.nexio.tv.updater.*'`
Expected: PASS. The `android.util.Log` calls are no-ops under unit tests by default (or shadow-logged); no assertion changes.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nexio/tv/updater/UpdateRepository.kt
git status -sb | head -3
git commit -m "$(cat <<'EOF'
chore(updater): log resolved update channel + winner tag

One Log.i line per check so logcat tells you which release won the
EA-channel candidate race without bisecting through coroutines.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 12: Update spec with implementation notes (Hilt module location, asset-rename approach)

Two things the spec deferred:

- "Exact AGP-compatible `outputFileName` override syntax" — the implementation lands on `androidComponents.onVariants` (modern, non-deprecated).
- "Whether `GitHubApiShapes.LATEST_RELEASE` lives in `core.integration` package or somewhere else" — confirmed: `app/src/main/java/com/nexio/tv/core/integration/IntegrationApiShapes.kt`.

Optional but recommended: close the loop in the spec.

**Files:**
- Modify: `docs/superpowers/specs/2026-05-11-early-access-auto-upgrade-design.md`

- [ ] **Step 1: Replace the "Open questions deferred to implementation" section**

In the spec's final section, replace the two open-question bullets with a `## Implementation notes (post-build)` section that records:

```markdown
## Implementation notes (post-build)

- **APK output rename** is wired via `androidComponents.onVariants` in
  `app/build.gradle.kts`, scoped to the `universal` ABI flavor only.
  Arm64 / armv7 flavor outputs keep their default filenames.
- **`GitHubApiShapes.LIST_RELEASES`** lives alongside `LATEST_RELEASE`
  in `app/src/main/java/com/nexio/tv/core/integration/IntegrationApiShapes.kt`.
- **`UpdateChannel` Hilt binding** lives in
  `app/src/main/java/com/nexio/tv/core/di/UpdaterModule.kt`, following
  the existing `core/di` module convention.
- **Stable-arm error policy in EA channel:** transient stable-arm
  errors (5xx / NetworkError) propagate as failure even when the
  prerelease arm succeeded. Only 404 on the stable arm is treated as a
  soft miss (genuine "no stable release yet"). This is asymmetric with
  the prerelease arm, which always soft-misses when the stable arm
  succeeded — a deliberate choice so a transient outage cannot silently
  hide a missed stable cut.
```

- [ ] **Step 2: Commit**

```bash
git add docs/superpowers/specs/2026-05-11-early-access-auto-upgrade-design.md
git status -sb | head -3
git commit -m "$(cat <<'EOF'
docs(updater): record post-implementation notes on spec

Closes the two deferred open questions and documents the asymmetric
stable-vs-prerelease error policy applied in resolveEarlyAccess.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Manual smoke test (after Task 11; before tagging a release)

The unit suite covers logic; this validates the end-to-end on-device path. Follow the CLAUDE.md rule #8 profile-selection sequence.

1. **Install EA `0.55-earlyaccess` on Fire TV:**
   ```bash
   ./gradlew :app:installUniversalReleaseEarlyAccess
   adb -s <device> shell am force-stop com.nexio.tv.earlyaccess
   ```
2. **Override the GitHub repo for testing.** Either:
   - Temporarily edit `app/build.gradle.kts` to point `GITHUB_OWNER`/`GITHUB_REPO` at a scratch repo and rebuild, or
   - Use a real scratch tag on `johnneerdael/nexio` that the production updater will pick up.
3. **Tag a prerelease on the chosen test repo:**
   - Create release `v0.56-ea1`, flag `prerelease=true`, attach `nexio-earlyaccess.apk` from the EA build artifact.
4. **Launch the EA app:**
   ```bash
   adb -s <device> logcat -c
   adb -s <device> shell monkey -p com.nexio.tv.earlyaccess 1
   sleep 5
   adb -s <device> shell input keyevent KEYCODE_DPAD_CENTER   # select a profile
   sleep 15
   adb -s <device> logcat -d -t 600 | grep 'UpdateRepository' | tail -5
   ```
   Expected: a `channel=earlyAccess winner=v0.56-ea1 (stable=…, pre=v0.56-ea1)` log line.
5. **Confirm the update dialog appears** with `nexio-earlyaccess.apk` as the download. Accept; confirm install.
6. **Then cut `v0.57` stable** with both APKs attached, EA-asset uploaded **first** (per the migration note in the spec). Launch EA app again, confirm log line shows `winner=v0.57`, confirm download is `nexio-earlyaccess.apk` (not `nexio-release.apk`).

If the stable cut step is being done against the production repo, also confirm a stable build on a separate device sees `v0.57` via the unchanged Stable path.

---

## Self-review checklist (run before tagging the plan complete)

- [ ] Every spec requirement maps to a task. Cross-check:
  - Spec §"Decisions" channel model → Tasks 3, 5, 9.
  - Spec §"Decisions" versioning policy → Task 4 (`pickNewer`).
  - Spec §"Decisions" release-publishing contract → Tasks 1, 2 (the code half — the human-process half is documented in the spec and the smoke test).
  - Spec §"Decisions" channel detection → Tasks 1, 7.
  - Spec §"Decisions" asset matching → Task 5.
  - Spec §"Decisions" release scan → Task 6 (transport), Task 9 (resolver).
  - Spec §"Architecture" — every named type/method has a task that creates or modifies it.
  - Spec §"Error handling" — every row has a `UpdateRepositoryTest` case in Tasks 8 or 9.
  - Spec §"Gradle changes" → Tasks 1, 2.
  - Spec §"Migration: existing 0.56 EA installs" → documented in the manual smoke step #6 + spec migration note (already there).
  - Spec §"Testing" → Tasks 3, 4, 5, 6, 8, 9 cover all listed unit tests.
- [ ] No `TBD` / `TODO` / `implement later` strings in the plan.
- [ ] Every test step shows complete test code, not a description.
- [ ] Every commit step lists explicit paths (CLAUDE.md rule #7).
- [ ] Type/method names match across tasks: `UpdateChannel.Stable` / `UpdateChannel.EarlyAccess`, `assetPrefix`, `fromBuildConfig`, `ChannelAssetSelector.choose`, `VersionUtils.pickNewer`, `GitHubReleaseIntegrationProvider.fetchReleases`, `GitHubApiShapes.LIST_RELEASES`, `UpdaterModule.provideUpdateChannel`.
- [ ] No reference to types or functions defined in no task.
