---
review_target: docs/superpowers/plans/2026-04-14-assrender-inspired-ass-ssa-rendering.md
reviewers: [gemini, claude]
reviewed_at: 2026-04-14T02:38:43.177Z
plans_reviewed: [docs/superpowers/plans/2026-04-14-assrender-inspired-ass-ssa-rendering.md]
status:
  gemini: completed
  claude: completed_via_user_supplied_review_after_cli_timeout
---

# Cross-AI Plan Review - ASS/SSA Assrender-Inspired Rendering

## Gemini Review

MCP issues detected. Run /mcp list for status.# Implementation Plan Review: ASS/SSA Assrender-Inspired Rendering

This review evaluates the implementation plan for the local `libass` rendering pipeline in NEXIO, intended to replace the `ass-media` dependency while maintaining architectural integrity.

## Summary
The plan is an exceptionally detailed and technically sound blueprint for bringing high-fidelity subtitle rendering in-house. By porting proven logic from `assrender` and integrating it directly into NEXIO's existing `SubtitleOffsetRenderersFactory`, the plan successfully balances "always-on" advanced rendering with the project's unique requirements (AI translation, custom offsets, and device-specific tuning). The strategy of intercepting raw Matroska data via a custom extractor is the "correct" way to handle ASS/SSA in Media3, avoiding the data loss inherent in standard cue-parsing pipelines.

## Strengths
- **Architectural Preservation:** The plan correctly identifies `SubtitleOffsetRenderersFactory` as a "crown jewel" of NEXIO and ensures the new renderer is added to it rather than replacing the entire factory.
- **Raw Data Integrity:** Using `MatroskaExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA` and a no-op parser is the only reliable way to ensure Aegisub-style tags and drawing commands reach `libass` without being mangled by the Media3 `SubtitleParser`.
- **Comprehensive Audit:** The inclusion of a mapping between `assrender` source functions and NEXIO destinations shows a deep understanding of the reference implementation.
- **Font Attachment Handling:** Specifically addressing Matroska font attachments and JNI font loading is critical for "fansub" style content common in high-quality debrid setups.
- **Clean Decoupling:** Removing the user-facing "Use Libass" toggle in favor of automatic detection improves the UX while reducing the maintenance surface of the settings screen.

## Concerns
- **Font Provider Configuration (HIGH):** Linking `fontconfig` and `expat` in CMake is only half the battle on Android. `libass` needs a configured font provider to find system fonts or fallback fonts when an MKV doesn't provide them. The plan doesn't explicitly detail how `fonts.conf` or a default font path will be initialized in the native layer for Android-specific environments.
- **Player Rebuild Latency (MEDIUM):** The plan mentions rebuilding the player when switching to an ASS/SSA track. While often necessary when changing the `MediaSource` configuration (parsers/extractors), this can cause a noticeable 500ms–1s gap in playback. If the user toggles between SRT and ASS frequently, this will feel "heavy."
- **Bitmap Copy Performance (MEDIUM):** Rendering into a `Bitmap` and then drawing that `Bitmap` to a `View` overlay can be taxing on low-end Android TV hardware (e.g., older Fire Sticks). The double-buffering logic needs to be extremely tight to avoid dropping UI frames during heavy animated ASS sequences.
- **JNI Handle Lifecycle (LOW):** While `nativeDestroy` is mentioned, the plan should explicitly ensure it is tied to the `ExoPlayer` release lifecycle and the `RenderController` disposal to prevent native memory leaks during long multi-episode binge sessions.

## Suggestions
- **Optimize Bitmap Reuse:** Ensure `AssSsaRenderOverlayView` uses a persistent, reused `Bitmap` instance (or a pool) to avoid frequent GC pressure during the render loop.
- **Pre-fill System Font Path:** In Task 4 (Native Bridge), add a step to identify and pass the Android system font path (usually `/system/fonts`) to the native `ass_set_fonts` or `fontconfig` init to ensure fallback rendering works even without MKV attachments.
- **Seek Performance:** Confirm that `nativeFlush` is called during player seeks to prevent stale subtitle events from being rendered briefly after a jump.
- **AI Translation Alignment:** Explicitly verify that the `AssNoOpSubtitleParserFactory` doesn't accidentally intercept the *output* of the AI translation if the translator produces a virtual SRT/VTT file. The factory must be selective enough to only no-op when the format is genuinely ASS/SSA.

## Risk Assessment: MEDIUM
The overall risk is **MEDIUM**. The technical complexity is high due to the native JNI/CMake requirements and the deep hooks into Media3's extraction layer. However, the plan mitigates the biggest risks (data loss, architectural conflict) through surgical design. The primary remaining risk is "environmental": ensuring the native `libass` stack behaves predictably across the fragmented landscape of Android TV/Fire TV SOCs (specifically regarding font paths and memory limits).

## Final Verdict
**PROCEED.** The plan is robust, respects the project's existing "Superpowers" workflows, and provides a clear path to a premium, "living-room ready" subtitle experience.

---

## Claude Review

The Claude CLI attempt timed out twice with empty output. The user supplied the Claude review manually afterward.

### Verdict

REVISE -- architecturally sound but has blocking issues that would derail execution.

### Critical

- **CRITICAL: CMake native build is gated behind `DOVI_NATIVE_ENABLED`.** The plan adds `assrender_direct` to `CMakeLists.txt`, but `app/build.gradle.kts` currently only enables `externalNativeBuild` when `enableDoviNative` is true. Default builds would not build `assrender_direct`, and `System.loadLibrary("assrender_direct")` would crash with `UnsatisfiedLinkError`.

### High

- **HIGH: Gradle test task names are wrong.** The app uses ABI product flavors. The correct debug tasks are `testArm64DebugUnitTest` and `compileArm64DebugKotlin`, not `testDebugUnitTest` and `compileDebugKotlin`.
- **HIGH: Missing native controller release lifecycle.** `PlayerRuntimeControllerLifecycle.kt` was not in the plan. `assSsaRenderController?.release()` must be called from `releasePlayer()` and the field must be nulled so stream switches and `onCleared()` destroy native libass handles.
- **HIGH: No graceful native loading fallback.** Removing the user toggle means native load failure must not crash the player. Add a `nativeAvailable` check around `System.loadLibrary` and fall back to Media3's native SSA path.
- **HIGH: `createRenderers` override needs exact implementation detail.** The plan needs the exact `DefaultRenderersFactory.createRenderers(...)` signature and the array-to-list-to-array append of `AssSsaTimeRenderer`.
- **HIGH: Prebuilt `.so` sourcing strategy is unspecified.** The plan must say whether libraries come from `assrender`, source builds, or the old AAR.

### Medium

- **MEDIUM: Overlay mutual exclusion is underspecified.** Selecting an addon SRT/VTT subtitle while an embedded ASS track is active can render two overlays unless the plan explicitly clears/suppresses one path.
- **MEDIUM: Reflection-based `extractorOutput` wrapping is fragile.** The plan needs more detail and a fail-closed fallback because Media3 internals can change.
- **MEDIUM: Missing `onVideoSizeChanged` hook.** `AssSsaRenderController.setVideoSize(width, height)` is declared, but the plan must specify where it is called.
- **MEDIUM: Missing ProGuard keep rule.** JNI bridge methods need a keep rule.

### Low

- **LOW: `currentAssSsaOverlayViewProvider()` plumbing is undefined.** The plan references this without defining who owns/sets it.
- **LOW: Every ASS/SSA stream initializes twice.** The existing first-track-scan rebuild pattern carries latency risk.
- **LOW: Confirm FFmpeg cleanup in ported C.** The plan excludes FFmpeg but should ensure ported direct files do not retain `libav*` includes.

### Positives

- TDD structure is solid.
- The `assrender` function mapping table is thorough.
- Aegisub tag coverage delegates semantics to libass instead of reinventing tags in Kotlin.
- Extraction stays lossless while native rendering owns ASS behavior.
- Cleanup of the current `ass-media` path is comprehensive.
- AI translation and ASS/SSA coexistence are explicitly tested.

---

## Consensus Summary

Both reviewers agree the architecture is directionally sound: preserve Nexio's renderer factory, intercept raw ASS/SSA data, delegate tag semantics to libass, and remove the old optional `ass-media` settings path. Claude is more severe because it verified repo-specific execution blockers around Gradle and native build gating.

### Agreed Strengths

- Preserving `SubtitleOffsetRenderersFactory` is the right architectural constraint.
- Raw ASS/SSA interception plus a no-op parser is the right strategy for preserving Aegisub tag semantics.
- Removing the user-facing libass toggle simplifies UX and avoids a split maintenance path.
- Font attachment handling is important enough to be explicit.

### Agreed Concerns

- Native font setup needs explicit Android fontconfig/system-font behavior.
- Native lifecycle and fallback handling need to be stronger before implementation.
- Bitmap overlay performance on low-end Android TV/Fire TV hardware is a real risk.
- Player rebuild/switch latency is worth revisiting or at least documenting.

### Highest Priority Revisions

- CRITICAL: Make the CMake/native build for `assrender_direct` independent of `DOVI_NATIVE_ENABLED`.
- HIGH: Replace all generic Gradle tasks with flavor-specific tasks (`testArm64DebugUnitTest`, `compileArm64DebugKotlin`, `connectedArm64DebugAndroidTest`).
- HIGH: Add `AssSsaNativeBridge.nativeAvailable` and fallback to Media3's native SSA renderer if native loading fails.
- HIGH: Add `assSsaRenderController?.release()` to `releasePlayer()` and include `PlayerRuntimeControllerLifecycle.kt` in the plan.
- HIGH: Specify `.so` and header sourcing from `/Users/jneerdael/Scripts/assrender/prebuilt/{arm64-v8a,armeabi-v7a}`.
- HIGH: Add explicit Android font provider/fontconfig setup, including `fonts.conf`, cache paths, and `/system/fonts` fallback behavior.
- MEDIUM: Revisit player rebuild latency when switching to or from ASS/SSA tracks.
- MEDIUM: Tighten bitmap reuse/double-buffering expectations for low-end Android TV and Fire TV devices.
- MEDIUM: Define overlay mutual exclusion and video-size update hooks.
- MEDIUM: Add a ProGuard keep rule for `AssSsaNativeBridge`.

### Divergent Views

- Gemini's verdict was "proceed" with medium risk. Claude's verdict was "revise" because of verified build/lifecycle blockers. Claude's stricter position should drive the plan revision.
