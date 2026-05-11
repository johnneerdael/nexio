# Early Access auto-upgrade — design spec

**Date:** 2026-05-11
**Author:** John Neerdael (collaborator: Claude Opus 4.7)
**Status:** Approved for plan-writing

---

## Goal

The NEXIO Android TV app already has an in-app updater that polls GitHub
Releases on app start (`UpdateRepository`, `UpdateViewModel`,
`ApkDownloader`, `ApkInstaller`). Today it watches one channel: the latest
non-prerelease release of `johnneerdael/nexio`, picking the best APK by
ABI substring match.

We are introducing an **Early Access** build (`releaseEarlyAccess`,
applicationId `com.nexio.tv.earlyaccess`) and starting to publish
pre-release APKs on GitHub. Goals:

1. Early Access installs see **both** GitHub stable releases and GitHub
   pre-releases, and upgrade to whichever has the highest version.
2. Early Access installs always install the
   `nexio-earlyaccess.apk` asset (same applicationId → in-place upgrade
   works; cross-channel installs would fail with
   `INSTALL_FAILED_UPDATE_INCOMPATIBLE`).
3. Stable installs are unchanged: see only stable releases, install
   `nexio-release.apk`.

Out of scope: a user-facing channel toggle, nightly channel, signature
verification beyond what `ApkInstaller` already does, multi-version
concatenated changelogs.

## Decisions (locked from brainstorming)

- **Channel model:** EA installs always pull the `nexio-earlyaccess.apk`
  asset, regardless of whether the winning release is a stable release
  or a GitHub pre-release. Cross-package "graduate to stable" flow is
  not in scope.
- **Versioning policy:** Monotonic across channels (stable `0.55` → EA
  `0.56` → stable `0.57` → EA `0.58`…). A single numeric ordering
  orders everything; `VersionUtils.isRemoteNewer` already handles this.
- **Release-publishing contract** (humans must follow):
  | Release type | GitHub `prerelease` flag | APK assets |
  |---|---|---|
  | Stable cut    | `false` | `nexio-release.apk` **and** `nexio-earlyaccess.apk` |
  | EA-only cut   | `true`  | `nexio-earlyaccess.apk` only |
- **Channel detection:** Hard-wired via a new `BuildConfig.UPDATE_CHANNEL`
  per build type. No runtime toggle, no packageName-suffix sniffing.
- **Asset matching:** Channel-prefix filter on filename
  (`nexio-release*` vs `nexio-earlyaccess*`), then defer to existing
  `AbiSelector` for ABI tie-break inside that subset.
- **Release scan:** Stable channel issues one call to
  `/repos/:owner/:repo/releases/latest`. EA channel issues that *plus*
  one paginated call to `/repos/:owner/:repo/releases?per_page=10` to
  locate the newest entry with `prerelease=true`, then picks the higher
  version of the two candidates.
- **Release notes:** Show only the winning release's `body`. No
  multi-release concatenation.

## Non-goals (explicit YAGNI cuts)

- A `Settings → Update Channel` toggle inside the app.
- A "nightly" channel.
- Pagination beyond page 1 of `/releases` (10 entries is plenty for the
  current release cadence; if the most recent 10 releases contain zero
  pre-releases, the EA channel falls back to the stable winner).
- Signature pinning beyond Android's own installer signature check.
- Backfill changelogs across multiple intermediate versions.

## Architecture

### New types (all in `com.nexio.tv.updater`)

```kotlin
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

```kotlin
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

### `VersionUtils` extension

```kotlin
fun pickNewer(a: String?, b: String?): String? = when {
    a == null -> b
    b == null -> a
    isRemoteNewer(a, b) -> a   // a strictly newer than b
    else -> b                  // tie or b newer → prefer b
}
```

Tie-break note: `isRemoteNewer` returns `false` for equal versions, so
on equal versions `pickNewer` returns `b`. To make stable win ties when
called from the EA resolver, the resolver passes `stableCandidate` as
`a` and `preCandidate` as `b` — wait, that returns `b` (pre) on tie.
**Resolver passes pre as `a`, stable as `b`.** On tie → stable wins (we
prefer the stable-flagged release). On strict pre-newer → pre wins. On
strict stable-newer → stable wins.

### Channel resolution flow inside `UpdateRepository.getLatestUpdate()`

```
Stable channel:
  1. GET /releases/latest                            → release R
  2. assert !R.draft && !R.prerelease                (existing gate)
  3. ChannelAssetSelector.choose(Stable, R.assets)   → asset (or error)
  4. return AppUpdate(tag = R.tagName, …)

Early-access channel:
  1. coroutineScope {
       val stableDeferred = async {
         gitHubReleaseIntegrationProvider.fetchLatestRelease(owner, repo)
       }
       val preDeferred = async {
         gitHubReleaseIntegrationProvider.fetchReleases(owner, repo, perPage = 10)
       }
     }
  2. stableCandidate = stableDeferred.await().let { result →
        when (result) {
          is Success → release (require !draft && !prerelease)
          is HttpError(404) → null            // repo has no stable release
          is HttpError | NetworkError → propagate as failure
                                                 unless preCandidate also succeeded
          Missing → null
        }
     }
  3. preCandidate = preDeferred.await().let { result →
        when (result) {
          is Success → list.firstOrNull { it.prerelease && !it.draft }
          is HttpError | NetworkError → null
                                                 unless stableCandidate also null
          Missing → null
        }
     }
  4. if (both null) return failure("No release found for early-access channel")
  5. winner = whichever has higher version per VersionUtils.pickNewer(
                  preCandidate?.tagName, stableCandidate?.tagName)
              (pass pre as `a`, stable as `b` → stable wins ties)
  6. asset = ChannelAssetSelector.choose(EarlyAccess, winner.assets)
              ?: error("Release ${winner.tagName} has no nexio-earlyaccess.apk asset")
  7. return AppUpdate(tag = winner.tagName, …)
```

Per CLAUDE.md rule #6 (no large values pinned as outer-fun locals
across suspending fan-out): `stableCandidate` and `preCandidate` are
small DTOs (`GitHubReleaseDto`, `List<GitHubReleaseDto>` of <= 10
entries with a handful of `GitHubAssetDto` each), well under the
threshold that motivated rule #6. The pattern is still followed: both
fetches happen inside one `coroutineScope`, results are awaited and
folded immediately, no large `List<MetaPreview>`-style values are
captured.

### Hilt wiring

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object UpdaterModule {
    @Provides
    @Singleton
    fun provideUpdateChannel(): UpdateChannel =
        UpdateChannel.fromBuildConfig(BuildConfig.UPDATE_CHANNEL)
}
```

`UpdateRepository`'s constructor gains an `UpdateChannel` parameter. No
other DI changes.

### Transport: `GitHubReleaseIntegrationProvider.fetchReleases`

Add a sibling to `fetchLatestRelease`. Same `IntegrationRuntime.call`
shape, different API call:

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
            apiShapeId = GitHubApiShapes.LIST_RELEASES,   // new constant
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
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    IntegrationCallResult.NetworkError(e)
                }
            }
        )
    )
}
```

Retrofit interface (`GitHubReleaseApi`) gains:

```kotlin
@GET("repos/{owner}/{repo}/releases")
suspend fun getReleases(
    @Path("owner") owner: String,
    @Path("repo") repo: String,
    @Query("per_page") perPage: Int
): Response<List<GitHubReleaseDto>>
```

`GitHubApiShapes` gains a new constant `LIST_RELEASES` analogous to
`LATEST_RELEASE`.

## Gradle changes

### `BuildConfig.UPDATE_CHANNEL`

```kotlin
defaultConfig {
    // …
    buildConfigField("String", "UPDATE_CHANNEL", "\"stable\"")
}

buildTypes {
    release {
        // …
        // (no override needed if defaultConfig already says "stable",
        //  but be explicit to defend against future defaultConfig drift)
        buildConfigField("String", "UPDATE_CHANNEL", "\"stable\"")
    }
    create("releaseEarlyAccess") {
        initWith(getByName("release"))
        applicationIdSuffix = ".earlyaccess"
        versionNameSuffix = "-earlyaccess"
        matchingFallbacks += listOf("release")
        buildConfigField("String", "UPDATE_CHANNEL", "\"earlyAccess\"")
    }
    create("releaseProfileable") {
        // unchanged — inherits "stable" from release, fine (dev-only build)
    }
}
```

### APK output filename

Both build types must produce files named so the matcher can find them
after upload. Preferred approach is an AGP variant-output override; the
fallback is a shell rename in the publishing step.

Preferred (in `android { … }` or `androidComponents { … }`, whichever
AGP version makes cleanest):

```kotlin
applicationVariants.all {
    // Only override for the universal flavor — the arm64 / armv7 flavors
    // are local-testing only and must not collide with the published
    // filename for the universal variant.
    if (flavorName != "universal") return@all
    outputs.all {
        val expected = when (buildType.name) {
            "release"            -> "nexio-release.apk"
            "releaseEarlyAccess" -> "nexio-earlyaccess.apk"
            else                 -> outputFileName
        }
        (this as BaseVariantOutputImpl).outputFileName = expected
    }
}
```

Only the `universal` flavor is published to GitHub Releases; the
`arm64` / `armv7` flavors are built for local testing only. The
`if (flavorName != "universal") return@all` guard prevents two
variants from claiming the same output filename.

(Implementation plan will confirm AGP version compatibility; if the
deprecated `applicationVariants` API is unavailable, fall back to a
`Copy` task wired into `assembleUniversalRelease` /
`assembleUniversalReleaseEarlyAccess`, or a shell rename in the
publishing workflow. The end-state filename contract is the same.)
If per-ABI publishing is added later, the matcher already handles ABI
substrings inside the channel-scoped subset; the override above would
expand to include the ABI suffix in the expected filename.

## Error handling

| Scenario | UpdateRepository behaviour |
|---|---|
| Stable channel, `/releases/latest` returns prerelease/draft | Existing error: `"Latest release is draft/prerelease"` |
| Stable channel, no `nexio-release*` asset in `dto.assets` | New error: `"Release <tag> has no nexio-release.apk asset"` |
| Stable channel, network error / 5xx / 403 rate limit | Propagate as `Result.failure` (UI shows error) |
| EA channel, both `/releases/latest` and `/releases` succeed | Pick higher version; resolve EA asset on the winner |
| EA channel, `/releases/latest` returns 404 (no stable yet) | Treat as null; rely on prerelease candidate |
| EA channel, `/releases/latest` returns 5xx / 403 / NetworkError | Treat as null *only if* prerelease candidate succeeded; otherwise propagate |
| EA channel, `/releases` 5xx / 403 / NetworkError | Symmetric — treat as null only if stable candidate succeeded; otherwise propagate |
| EA channel, both null | `"No release found for early-access channel"` |
| EA channel, winner release has no EA asset | `"Release <tag> has no nexio-earlyaccess.apk asset"` |

The "soft miss only if the other arm succeeded" rule keeps a transient
GitHub outage from silently flipping the EA installer into "no update"
when there genuinely was one — we surface the error so the user sees
the existing error dialog, not nothing.

`UpdateViewModel.checkForUpdates()` continues to call
`updateRepository.getLatestUpdate()` and compare `update.tag` against
`BuildConfig.VERSION_NAME` via `VersionUtils.isRemoteNewer`. Because
EA's `VERSION_NAME` is `0.56-earlyaccess`,
`parseVersionParts` extracts `[0, 56]` — comparing cleanly against
`v0.57` (`[0, 57]`).

## Diagnostics

One `Log.i("UpdateRepository", …)` line after resolution per check:

```
channel=earlyAccess winner=v0.57 (stable=v0.57, pre=v0.56)
channel=stable winner=v0.57
```

No persistent state changes. No new DataStore key. No SharedPreferences
writes. (CLAUDE.md rule #3 is untouched — DTOs are small and
short-lived.)

## Migration: existing 0.56 EA installs

Existing EA installs at version `0.56` ship the *current*
`UpdateRepository` that hard-skips prereleases. When stable `v0.57` is
cut with both APKs attached:

- `/releases/latest` returns the v0.57 release (non-prerelease) → the
  prerelease gate is satisfied.
- `AbiSelector.chooseBestApkAsset` runs on `[nexio-release.apk,
  nexio-earlyaccess.apk]`. With no ABI token in either filename,
  it falls through to "first APK that doesn't mention a known ABI" —
  i.e. asset-list order in the JSON response. GitHub returns assets in
  upload order, so **uploading `nexio-earlyaccess.apk` first** in the
  v0.57 stable release will make existing EA installs at 0.56 fetch the
  correct asset and upgrade in place.

After that one-time migration, all subsequent EA installs run the
channel-aware updater and order-of-upload no longer matters.

This migration constraint is enforced by the publishing checklist, not
by code. The implementation plan will surface it in a release-runbook
note.

## Testing

Unit tests, all under `app/src/test/.../updater/`:

### `UpdateChannelTest`

- `fromBuildConfig("stable") == Stable`
- `fromBuildConfig("earlyAccess") == EarlyAccess`
- `fromBuildConfig("unknown") == Stable` (fallback)
- `fromBuildConfig("") == Stable`

### `ChannelAssetSelectorTest`

Assets list mixing both channels and unprefixed names:

- `Stable` picks only `nexio-release*`, never `nexio-earlyaccess*` or
  unprefixed.
- `EarlyAccess` picks only `nexio-earlyaccess*`.
- Within the scoped subset, ABI tie-break still works:
  `nexio-release-arm64-v8a.apk` chosen over
  `nexio-release-armeabi-v7a.apk` on an arm64 device.
- Empty scoped subset → `null` (caller surfaces error).
- Case-insensitive prefix match.

### `VersionUtilsTest` (extend existing)

- `pickNewer("v0.57", "v0.56-ea1") == "v0.57"`
- `pickNewer("v0.56-ea1", "v0.57") == "v0.57"`
- `pickNewer(null, "v0.56") == "v0.56"`
- `pickNewer("v0.56", null) == "v0.56"`
- `pickNewer(null, null) == null`
- `pickNewer("v0.57", "v0.57") == "v0.57"` (b wins on equality →
  passing stable as `b` makes stable win ties)

### `UpdateRepositoryTest`

Fake `GitHubReleaseIntegrationProvider` returns scripted
`IntegrationCallResult` values:

- **Stable channel, latest non-prerelease, both APK assets present** →
  returns stable APK update.
- **Stable channel, latest prerelease** → existing error.
- **Stable channel, only EA asset present** → new "no nexio-release.apk
  asset" error.
- **EA channel, only stable release exists, both APKs attached** →
  returns EA APK from stable release.
- **EA channel, only prerelease exists** → returns EA APK from
  prerelease.
- **EA channel, prerelease newer than stable** → returns EA APK from
  prerelease.
- **EA channel, stable newer than prerelease** → returns EA APK from
  stable.
- **EA channel, equal versions** → returns EA APK from stable (tie
  rule).
- **EA channel, stable 404 + prerelease present** → returns EA APK
  from prerelease (soft miss).
- **EA channel, stable 403 rate-limit + prerelease present** →
  returns EA APK from prerelease.
- **EA channel, prerelease list arm 5xx + stable present** → returns
  EA APK from stable.
- **EA channel, both arms failed** → propagates failure.
- **EA channel, winner has no nexio-earlyaccess.apk asset** →
  "no asset" error.

No instrumented tests. `UpdateUiState`, dialog composables,
`ApkDownloader`, `ApkInstaller` are untouched.

### Manual smoke

After implementation, before tagging release:

1. Build EA APK at version `0.55-earlyaccess`, install on Fire TV.
2. On a scratch repo (`localhost-test/nexio-updater`), tag `v0.56-ea1`
   as prerelease with `nexio-earlyaccess.apk` attached.
3. Override `GITHUB_OWNER`/`GITHUB_REPO` in a debug build for testing,
   or temporarily point production EA at the scratch repo.
4. Launch app → confirm update dialog shows `v0.56-ea1`, confirm
   download lands the EA APK, confirm install proceeds.
5. Repeat with stable `v0.57` + both APKs: confirm EA install sees
   v0.57, downloads `nexio-earlyaccess.apk` (not `nexio-release.apk`).

## Files touched

| File | Change |
|---|---|
| `app/build.gradle.kts` | Add `UPDATE_CHANNEL` buildConfigField to defaultConfig, `release`, `releaseEarlyAccess`. Add `outputFileName` override for universal-release and universal-releaseEarlyAccess variants. |
| `app/src/main/java/com/nexio/tv/updater/UpdateChannel.kt` | **New** — enum + `fromBuildConfig`. |
| `app/src/main/java/com/nexio/tv/updater/ChannelAssetSelector.kt` | **New** — wraps `AbiSelector` with channel-prefix scoping. |
| `app/src/main/java/com/nexio/tv/updater/VersionUtils.kt` | Add `pickNewer(a, b)`. |
| `app/src/main/java/com/nexio/tv/updater/UpdateRepository.kt` | Inject `UpdateChannel`; branch resolution per channel; soft-miss handling. |
| `app/src/main/java/com/nexio/tv/data/integration/github/GitHubReleaseIntegrationProvider.kt` | Add `fetchReleases(owner, repo, perPage)`. |
| `app/src/main/java/com/nexio/tv/data/remote/api/GitHubReleaseApi.kt` | Add `@GET("repos/{owner}/{repo}/releases")` method. |
| `app/src/main/java/com/nexio/tv/core/integration/GitHubApiShapes.kt` (or wherever `LATEST_RELEASE` lives) | Add `LIST_RELEASES` constant. |
| `app/src/main/java/com/nexio/tv/di/UpdaterModule.kt` (or existing updater Hilt module) | `@Provides fun provideUpdateChannel()`. |
| `app/src/test/.../updater/UpdateChannelTest.kt` | **New**. |
| `app/src/test/.../updater/ChannelAssetSelectorTest.kt` | **New**. |
| `app/src/test/.../updater/VersionUtilsTest.kt` | Extend with `pickNewer` cases (file may need to be created if absent). |
| `app/src/test/.../updater/UpdateRepositoryTest.kt` | **New** (no test exists today; this is the first). |

No changes to:

- `UpdateViewModel`, `UpdatePreferences`, `ApkDownloader`, `ApkInstaller`
- Updater UI composables
- Trakt / Simkl / TMDB / TVDB / Stremio integration providers
- Any home / catalog / artwork / playback code

## Hard-rule compliance audit

- **Rule #1 (display authority):** N/A — updater does not touch
  artwork or `ResolvedDisplaySurfaceRepository`.
- **Rule #2 (state retention):** N/A — `UpdateUiState` already exists
  and is unchanged. No new `List<X>` fields added to any observed
  `MutableState`.
- **Rule #3 (no large blobs in SharedPreferences, stream large JSON):**
  No new persistence. `UpdatePreferences` DataStore unchanged. Release
  DTOs are small and parsed once per check via Retrofit/Moshi (not
  via `gson.fromJson(rawString, …)`).
- **Rule #4 (no suspending forEach over lists):** Resolver uses
  explicit `firstOrNull { it.prerelease && !it.draft }` on a 10-entry
  list — `Iterable.firstOrNull` does not allocate `ArrayList$Itr`
  retained across suspension points (the predicate is non-suspending).
  `ChannelAssetSelector.choose` uses `filter` on a small list, also
  non-suspending.
- **Rule #5 (memoization):** N/A — single-shot fetch per app start;
  no per-emission allocation pressure.
- **Rule #6 (no large values pinned across coroutine fan-out):**
  Resolver uses one `coroutineScope { async; async }` fan-out; awaited
  values are folded into a single `winner` and the small candidate
  DTOs go out of scope. No `List<MetaPreview>`-class values captured.
- **Rule #7 (git staging):** Implementation will stage by explicit
  path only.
- **Rule #8 (smoke tests need profile selection):** N/A — updater
  runs in `init {}` of `UpdateViewModel`, before profile selection,
  so the existing app-start trigger is unaffected by this rule. Manual
  smoke checklist above tests the updater path explicitly.

## Implementation notes (post-build)

- **APK output rename** is wired via `applicationVariants.all` in
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
