---
phase: 09
slug: tvdb-advanced-tv-surfaces
status: draft
nyquist_compliant: true
wave_0_complete: false
created: 2026-04-14
---

# Phase 09 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 4.13.2, MockK 1.13.12, kotlinx-coroutines-test 1.8.1, MockWebServer 4.12.0 |
| **Config file** | `build.gradle.kts`, `app/build.gradle.kts`, `gradle/libs.versions.toml` |
| **Quick run command** | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.*"` |
| **Full suite command** | `./gradlew testArm64DebugUnitTest` |
| **Estimated runtime** | ~60-180 seconds depending on Gradle daemon/cache state |

---

## Sampling Rate

- **After every task commit:** Run the narrow test class for the touched mapper/service/ViewModel plus any affected existing test such as `TrailerServiceLatestSeasonTest`, `MetaDetailsSeasonMediaViewModelTest`, or `TmdbMetadataPerformanceTest`.
- **After every plan wave:** Run `./gradlew testArm64DebugUnitTest`.
- **Before `/gsd-verify-work`:** Run `./gradlew testArm64DebugUnitTest` and `./gradlew lintArm64Debug`.
- **Max feedback latency:** 180 seconds for narrow tests; full suite latency accepted at wave gates.

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 09-00-01 | 00 | 0 | META-03 | — | N/A | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.TvdbSeasonOrderMapperTest"` | ❌ W0 | ⬜ pending |
| 09-00-02 | 00 | 0 | META-05 | T-09-03 | TVDB active success skips duplicate TMDB TV surface calls | unit/call-count | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.TvdbProviderRoutingTest"` | ❌ W0 | ⬜ pending |
| 09-00-03 | 00 | 0 | META-05 | T-09-01 | TVDB trailer URLs are only treated as usable when supported by the existing playback/external model | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.data.trailer.TrailerServiceTvdbTest"` | ❌ W0 | ⬜ pending |
| 09-00-04 | 00 | 0 | META-05 | T-09-04 | Nullable/blank TVDB advanced metadata is omitted safely | unit | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.core.tvdb.TvdbAdvancedMetadataMapperTest"` | ❌ W0 | ⬜ pending |
| 09-00-05 | 00 | 0 | META-03 | — | N/A | unit/ViewModel | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.ui.screens.detail.MetaDetailsTvdbSeasonOrderTest"` | ❌ W0 | ⬜ pending |
| 09-00-06 | 00 | 0 | UX-02 | — | N/A | unit/static | `./gradlew testArm64DebugUnitTest --tests "com.nexio.tv.ui.screens.settings.TvdbSettingsNoAdvancedToggleTest"` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `app/src/test/java/com/nexio/tv/core/tvdb/TvdbSeasonOrderMapperTest.kt` — covers META-03 TVDB default season-type preservation and canonical numbering stability.
- [ ] `app/src/test/java/com/nexio/tv/core/tvdb/TvdbAdvancedMetadataMapperTest.kt` — covers META-05 cast/company/network/genre/content-rating mapping.
- [ ] `app/src/test/java/com/nexio/tv/data/trailer/TrailerServiceTvdbTest.kt` — covers META-05 TVDB trailer priority and fallback order.
- [ ] `app/src/test/java/com/nexio/tv/ui/screens/detail/MetaDetailsTvdbSeasonOrderTest.kt` — covers META-03 season tabs and progress-key stability.
- [ ] Provider-routing test in the actual Phase 7 TVDB package — covers META-05 skipped TMDB TV calls when TVDB advanced data succeeds.
- [ ] Settings/static test in the actual Phase 6/7 settings package — covers UX-02 no extra TVDB-specific toggle requirement.

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Android TV detail/Home user experience remains quiet when TVDB advanced data is missing | UX-02 | Visual/noise check is best confirmed on a TV/emulator after implementation | Open a TVDB-active series with incomplete advanced data and verify no browse-time warnings appear unless the surface is visibly inconsistent or empty. |

---

## Validation Sign-Off

- [x] All tasks have `<automated>` verify or Wave 0 dependencies
- [x] Sampling continuity: no 3 consecutive tasks without automated verify
- [x] Wave 0 covers all MISSING references
- [x] No watch-mode flags
- [x] Feedback latency < 180s for narrow checks
- [x] `nyquist_compliant: true` set in frontmatter

**Approval:** approved 2026-04-14
