# Kodi IEC Audio Audit Trail

This file records timestamped changes made while iterating on the experimental Kodi IEC audio path.

## 2026-03-16 00:59:00 +0100

- Removed Java-side DTS/DTS-HD channel-mask synthesis as an authority in:
  - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiIecPipeline.h`
  - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiIecPipeline.cpp`
  - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/kodi_cpp_packer_bridge.cpp`
  - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiIecPacker.java`
  - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiDtsPackerAudioOutput.java`
  - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiPassthroughAudioOutputProvider.java`
- Change:
  - Native packer packets now carry `outputChannelMask` in addition to `outputChannels`.
  - The native packer computes the Android IEC transport mask directly from Kodi stream type:
    - `DTSHD_MA` and `TRUEHD` -> `CHANNEL_OUT_7POINT1_SURROUND`
    - other DTS-family IEC carriers -> `CHANNEL_OUT_STEREO`
  - `KodiDtsPackerAudioOutput` now configures `AudioTrack` from that native packet mask and no longer translates `outputChannels` into a Java-derived mask.
  - The provider DTS config keeps a placeholder stereo mask until the first packed packet arrives, so there is no second Java authority competing with the native packet.
- Reason:
  - Repeated DTS-HD failures kept resolving to `Config (192000, 252, 13, 576000)`, which meant a Java-side channel-mask authority was still surviving.
  - Kodi source truth for IEC carrier channels already exists in `CAEBitstreamPacker::GetOutputChannelMap`, and the Android passthrough mapping policy already exists on the native side.
  - This change collapses DTS/DTS-HD carrier-channel authority to one source: the native packer bridge.

## 2026-03-16 01:08:00 +0100

- Latched the native-resolved DTS/DTS-HD carrier config across recovery/flush in:
  - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiDtsPackerAudioOutput.java`
- Change:
  - Once the native packer resolves a DTS carrier config, the Java transport now preserves the highest native-resolved channel mask and latest output rate for subsequent reconfiguration within the same sink instance.
  - Reconfigure no longer downgrades from a previously proven DTS-HD MA `7.1` carrier back to a transient stereo carrier during recovery.
- Reason:
  - The latest runtime log showed the same DTS-HD MA stream first opening correctly at `Channels 0x63f`, then later reopening incorrectly at `Channels 0x3` before failing with `AudioTrack write failed: -6`.
  - That means recovery was still letting a weaker packet-time config override the already-proven native carrier.

## 2026-03-15 14:39:12 +0100

- Reverted the ineffective native `outputPlayIssued_` / delayed-`STARTED` split in:
  - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiActiveAEEngine.cpp`
  - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiActiveAEEngine.h`
- Reason:
  - Logs showed it only delayed `STARTED` logging and inflated apparent written frames.
  - It did not change the persistent DTS-HD startup underrun or `384 -> 0` IEC drain cadence.
- Observed source-truth mismatch kept for follow-up:
  - Kodi `AESinkAUDIOTRACK` supervises sink delay/cache state.
  - Our native path still lacks equivalent fill-state supervision.

## 2026-03-15 14:39:12 +0100

- Added native submitted-frame accounting for passthrough partial writes in:
  - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiActiveAEEngine.cpp`
  - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiActiveAEEngine.h`
- Change:
  - Added `GetSubmittedOutputFramesLocked()`.
  - `HasPendingData()`, `GetSafePlayedFramesLocked()`, `GetWrittenAudioOutputPositionUsLocked()`, and `StartOutputIfPrimedLocked()` now use actual submitted output frames, including the partial current IEC burst already written into `AudioTrack`.
- Reason:
  - Partial IEC writes were previously invisible until a full packed burst completed.
  - That made startup/fill supervision undercount real native buffer occupancy and likely contributed to early-start/late-audio behavior.

## 2026-03-15 14:44:00 +0100

- Validation result for the submitted-frame accounting change:
  - No material behavioral improvement in DTS-HD passthrough startup/seek.
  - Logs still show:
    - `play()` arriving with clean pre-play state
    - immediate AudioFlinger underrun at `2048 / 8192` or `4096 / 8192`
    - the same repeated `bytesWritten=384` then `0` cadence
- Conclusion:
  - Partial native accounting was not the dominant remaining cause.
  - The remaining issue is that native still has no staged startup output when `Media3 -> AudioSink play()` is issued.

## 2026-03-15 14:44:00 +0100

- Current working diagnosis from DTS-HD logs:
  - Media3-side startup reservoir sizing is better, but startup bytes still live only on the Java side when `play()` is sent.
  - Native starts from an empty state and immediately underruns on the first IEC burst.
- Next candidate fix direction:
  - keep the existing Java startup reservoir
  - stage only a bounded initial startup write into native during `play()`
  - do not transfer the full reservoir and do not call native drain loops before playback stabilizes

## 2026-03-15 14:48:38 +0100

- Implemented a bounded Media3-side startup stage in:
  - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiNativeAudioSink.java`
- Change:
  - For non-E-AC3 passthrough, `play()` now performs one bounded startup `nWrite(...)` step from the Java startup reservoir before `nPlay()`.
  - After `nPlay()`, only one startup write step is performed instead of draining the whole reservoir in a loop.
  - Any native output bytes staged before `nPlay()` are subtracted from the startup commit target.
- Reason:
  - Logs showed startup still reached native with `pendingInput=0 pendingPacked=0 totalWrittenFrames=0`, which is why the IEC track immediately underruns from `2048 / 8192`.
  - Full-reservoir native staging previously broke DTS-HD payload behavior, so this keeps the staging bounded to one step.

## 2026-03-15 14:59:00 +0100

- Validation result for the bounded Media3-side startup stage:
  - No improvement in user-visible DTS-HD startup/seek delay.
  - Reintroduced paused pre-write symptoms on the first startup attempt:
    - `prePlayAcceptGapUs` and `prePlayWriteGapUs` became non-`-1`
    - `play()` reached native with `totalWrittenFrames=2048`
  - Second startup attempt still showed native instability:
    - `FlushPackedQueueToHardwareLocked phase=IDLE ... lastWriteResult=-6`
    - `play()` then reached native with `pendingInput=1 pendingPacked=1 totalWrittenFrames=0`
- Conclusion:
  - Even bounded pre-`play()` native staging is too aggressive for DTS-HD on this platform.
  - The remaining DTS-HD issue is not “stage before play”; it is that after `play()` we still only advance one burst at a time into a sink that needs four bursts (`8192` frames) to start cleanly.

## 2026-03-15 14:59:00 +0100

- Refined working diagnosis from DTS-HD logs:
  - DTS-HD IEC startup needs multiple post-`play()` bursts before audio is truly ready.
  - One DTS-HD IEC burst contributes `2048` frames, while the device reports `minFrames = 8192`.
  - Our current Media3/Kodi interaction still exposes only one-burst startup progression at a time, so video can begin while audio is still filling the sink.
- Next candidate fix direction:
  - remove the new pre-`play()` bounded staging
  - keep Java-side startup ownership
  - after `nPlay()`, allow a bounded startup flush of multiple DTS-HD bursts from the Java reservoir until:
    - real native output progress stalls, or
    - the native startup target is reached
  - do not call native drain loops and do not transfer full startup ownership into native state

## 2026-03-15 15:03:22 +0100

- Reverted the bounded pre-`play()` staging in:
  - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiNativeAudioSink.java`
- Implemented bounded multi-burst post-`play()` startup flush in the same file.
- Change:
  - Removed the one-step native startup write before `nPlay()`.
  - After `nPlay()`, non-E-AC3 passthrough now allows up to 4 startup write steps from the Java startup reservoir while:
    - startup output debt remains, and
    - native still reports real output progress.
- Reason:
  - The previous change reintroduced paused pre-write behavior for DTS-HD.
  - DTS-HD logs show one burst contributes about `2048` frames while the device wants `8192`, so startup needs multiple post-`play()` bursts rather than any pre-`play()` staging.

## 2026-03-15 15:11:04 +0100

- Validation result for bounded multi-burst post-`play()` startup flush:
  - Small user-visible improvement:
    - paused-start improved
    - A/V start gap reduced but not eliminated
  - Not a structural fix:
    - logs still show immediate underrun at `2048 / 8192`
    - native still enters `STARTED` after only one visible burst
    - the same `384 -> 0` cadence remains afterward
- New conclusion:
  - Additional Java-side startup flush calls are not enough on their own.
  - The limiting factor is that native `nWrite(...)` still only advances one DTS-HD burst window at a time once pending input/output exists, so Java cannot actually push four startup bursts into native progress before audio start.

## 2026-03-15 15:32:00 +0100

- Implemented a startup-only native ingest path for DTS-HD passthrough.
- Files changed:
  - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiActiveAEEngine.h`
  - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiActiveAEEngine.cpp`
  - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/kodi_cpp_session_bridge.cpp`
  - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiNativeAudioSink.java`
- Change:
  - Added `StagePassthroughStartupBuffer(...)` in native.
  - Added a native-side startup packed-output queue used only for staged startup packets.
  - Java `play()` for DTS-HD now stages the filled startup reservoir into native parser/packer ownership before issuing `nPlay()`.
  - No pre-play hardware writes were added.
  - Runtime `nWrite(...)` behavior remains unchanged.
- Reason:
  - Media3-only multi-burst flushes helped slightly but could not push more than one visible DTS-HD burst into native startup progress.
  - The remaining bottleneck was native single-window ownership after Java startup buffering.

## 2026-03-15 15:38:00 +0100

- Validation result for the DTS-HD startup-only native ingest path:
  - Regressed paused-start behavior from the user perspective.
  - No meaningful reduction in the video-leading-audio gap.
  - Startup shape changed from `2048 / 8192` to `4096 / 8192`, proving the ingest path can preload about two startup bursts into native state.
  - This still underruns immediately and falls back into the same `384 -> 0` non-blocking IEC drain cadence.
  - Second startup attempt shows an additional native instability:
    - `FlushPackedQueueToHardwareLocked phase=IDLE ... lastWriteResult=-6 pendingPacked=1`
- Conclusion:
  - The startup-only native ingest path is not the right fix shape.
  - It confirms startup burst quantity was only part of the problem.
  - The dominant remaining defect is still the native post-start IEC transport behavior after playback begins.

## 2026-03-15 15:52:00 +0100

- Reverted the DTS-HD startup-only native ingest path.
- Reverted files:
  - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiActiveAEEngine.h`
  - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiActiveAEEngine.cpp`
  - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/kodi_cpp_session_bridge.cpp`
  - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiNativeAudioSink.java`
- Reason:
  - The startup-ingest path improved startup priming from `2048 / 8192` to `4096 / 8192`, but did not materially reduce the A/V start gap.
  - It also reintroduced paused-start behavior from the user perspective and added a second-start `-6` instability.
- Explicit conclusion:
  - Media3-side DTS-HD startup staging variants are now treated as an exhausted fix class.
  - Further startup-delay work should not return to more Java-side readiness/staging heuristics unless new evidence invalidates this conclusion.

## 2026-03-15 16:25:12 +0100

- Started a new fix class: native startup-readiness truth exposed to Media3.
- Files changed:
  - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiActiveAEEngine.h`
  - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiActiveAEEngine.cpp`
  - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/kodi_cpp_session_bridge.cpp`
  - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiNativeAudioSink.java`
- Change:
  - Added native `IsPassthroughStartupReady()`.
  - Native readiness is now based on native transport truth instead of Java-owned startup-byte heuristics:
    - passthrough configured
    - play requested
    - and either native hardware playback has actually advanced beyond the initial playhead frame, or native queued/submitted frames have reached the output buffer target.
  - Removed the Java-side `startupCommitted` / `startupCommitRemainingOutputBytes` heuristic for non-E-AC3 passthrough.
  - Java now holds renderer clock position during non-E-AC3 passthrough startup until native reports startup-ready, while keeping `hasPendingData()` permissive enough to avoid the prior spinner/paused-start regressions.
- Reason:
  - The latest DTS-HD logs show Java-side pre-play state is already clean at `play()`, but native/hardware truth immediately disagrees via `UNDERRUN, framesReady = 4096, minFrames = 8192`.
  - That means the missing contract is not another Java staging heuristic; Media3 needs a native-reported startup-readiness signal.

## 2026-03-15 16:36:30 +0100

- Validation result for native startup-readiness truth exposed to Media3:
  - No material user-visible improvement.
  - No meaningful regression either.
  - The dominant native post-`play()` signature is unchanged.
- Current `debug-dts.log` still shows:
  - `FlushPackedQueueToHardwareLocked phase=STARTED` repeating with:
    - `attempts=3 writes=1 bytesWritten=384`
    - then repeated `attempts=2 writes=0 bytesWritten=0`
    - `pendingPacked=1`
    - `queuedDurationUs=10541`
  - This remains the same persistent transport bottleneck after `play()`.
- Conclusion:
  - The new native-startup-readiness signal is a cleaner contract than prior Java heuristics, but it still does not solve the actual DTS-HD startup delay because native transport truth itself remains bad.
  - Another local readiness or startup-stage patch is unlikely to be productive.
  - The next step should be an architectural change, not another small patch.

## 2026-03-15 16:38:59 +0100

- Began the architectural shift away from the native sink owning both IEC packing and transport.
- Added a standalone native packer-service foundation:
  - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiIecPackerSession.h`
  - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiIecPackerSession.cpp`
  - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/kodi_cpp_packer_bridge.cpp`
  - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiIecPacker.java`
- Also updated:
  - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/CMakeLists.txt`
- Change:
  - Introduced a separate JNI-native `KodiIecPackerSession` that wraps `KodiIecPipeline` without owning `AudioTrack` transport.
  - Added Java `KodiIecPacker` with configure/pack/ack/reset/release APIs.
  - This is intended to support the next migration phase where Java/Media3 owns passthrough transport and native only provides Kodi IEC burst construction.
- Important status:
  - This is scaffolding only.
  - It is not wired into `KodiNativeAudioSink` runtime yet.
  - No playback behavior should be expected to change from this step alone.
- Reason:
  - Stock Media3 cannot simply transport already-packed IEC through the normal sink path because IEC encapsulation is explicitly skipped in the standard `AudioCapabilities` flow.
  - The redesign therefore needs a dedicated packer service plus a Java-owned transport layer.

## 2026-03-15 16:44:32 +0100

- Added the Java transport-side foundation for the architectural DTS/DTS-HD migration:
  - `media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/KodiIecAudioOutput.java`
- Change:
  - Added a Java-owned IEC transport helper in the stock audio package so it can reuse `AudioTrackAudioOutputProvider`, `AudioOutputProvider.OutputConfig`, and `AudioTrackAudioOutput` infrastructure.
  - This helper is not wired into `KodiNativeAudioSink` yet.
- Reason:
  - The agreed redesign needs Java/Media3 to own passthrough transport while native only owns IEC packing.
  - Placing the helper in `androidx.media3.exoplayer.audio` avoids duplicating low-level `AudioTrack` transport machinery in the custom sink package.

## 2026-03-15 16:54:48 +0100

- Implemented the first DTS/DTS-HD runtime wiring pass for the architectural migration.
- Files changed:
  - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiNativeAudioSink.java`
  - `media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/KodiIecAudioOutput.java`
- Change:
  - DTS/DTS-HD/UHD passthrough now has a separate Java path in `KodiNativeAudioSink`.
  - That path uses:
    - `KodiIecPacker` for Kodi-native IEC burst generation
    - `KodiIecAudioOutput` for Java-owned IEC transport and position tracking
  - E-AC3 remains on the existing native sink path unchanged.
  - AC3/TrueHD/non-DTS codecs also remain on the existing path unchanged.
  - `KodiIecAudioOutput` was expanded from scaffolding into a Java-owned IEC transport helper using:
    - `AudioTrack`
    - `AudioTrackPositionTracker`
    - explicit written-frame accounting for IEC61937 transport
- Important status:
  - This is the first runtime migration pass only.
  - No validation has been done yet.
  - The DTS/DTS-HD native `KodiActiveAEEngine` transport loop is still present in the codebase for the legacy path, but DTS-family routing in `KodiNativeAudioSink` now targets the new Java path.


## 2026-03-15 17:01:56 +0100

- Continued the DTS/DTS-HD architectural migration from native transport to Java transport.
- Files changed:
  - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiNativeAudioSink.java`
- Change:
  - Replaced the first-pass single pending packed DTS packet with a Java-owned queue of packed IEC packets.
  - DTS/DTS-HD input is now consumed into queued packed IEC output as soon as the native Kodi packer emits a packet.
  - Acknowledgment to the native packer now happens when the packed IEC packet is queued in Java, not when native sink transport drains it.
  - Added Java-side DTS startup readiness based on queued IEC output frames versus the Java `KodiIecAudioOutput` buffer size.
  - Java `play()` now starts the DTS IEC transport only once the queued startup target is reached, then drains the queued packed IEC packets through `KodiIecAudioOutput`.
- Reason:
  - The prior DTS runtime wiring still kept only one packed packet, which was not a real transport migration and still mirrored the old native bottleneck too closely.
  - This change moves DTS/DTS-HD closer to the intended architecture:
    - native = IEC packer
    - Java/Media3 = transport, startup buffering, and `AudioTrack` writes
- Validation:
  - `./gradlew -p /Users/jneerdael/Scripts/nexio/media :lib-exoplayer:compileDebugJavaWithJavac :lib-exoplayer-kodi-cpp-audiosink:compileDebugJavaWithJavac :lib-exoplayer-kodi-cpp-audiosink:externalNativeBuildDebug`
  - `BUILD SUCCESSFUL`
- Important scope note:
  - E-AC3 remains on the existing native path unchanged.
  - This audit entry covers DTS/DTS-HD Java transport migration only.


## 2026-03-15 17:20:51 +0100

- Refined the DTS/DTS-HD Java transport after the first runtime log.
- Files changed:
  - `media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/KodiIecAudioOutput.java`
  - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiNativeAudioSink.java`
- Change:
  - `KodiIecAudioOutput` now reports a stable configured buffer-frame target from the chosen `AudioTrack` buffer size instead of querying the direct IEC track dynamically.
  - DTS seek/discontinuity/flush now release the Java direct IEC `AudioTrack` instead of reusing it, so the next packed DTS packet forces a fresh direct-track configure.
- Reason:
  - The runtime log showed startup issuing `play()` with `framesReady = 0`, which indicates the Java startup gate was still using an unreliable buffer target.
  - The same log showed `AudioTrack` dead-object restoration attempts on seek (`restoreTrack_l ... direct`), which means the direct IEC track must be explicitly recreated by our Java path on flush/discontinuity.
- Validation:
  - `./gradlew -p /Users/jneerdael/Scripts/nexio/media :lib-exoplayer:compileDebugJavaWithJavac :lib-exoplayer-kodi-cpp-audiosink:compileDebugJavaWithJavac`
  - `BUILD SUCCESSFUL`


## 2026-03-15 17:48:50 +0100

- Refined the DTS/DTS-HD Java transport a second time after the next runtime log.
- Files changed:
  - `media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/KodiIecAudioOutput.java`
  - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiNativeAudioSink.java`
- Change:
  - Added `KodiIecAudioOutput.getPendingFrames()` so the Java DTS path can distinguish queued IEC packets from IEC frames already written into the direct `AudioTrack`.
  - Changed DTS startup from `queue full` to `direct track primed`: the Java path now attempts to prefill the direct IEC `AudioTrack` before issuing `play()`.
  - `hasPendingData()` for the Java DTS path now keys off the direct-track primed state before playback has started.
- Reason:
  - The runtime log still showed `AudioFlinger: UNDERRUN, framesReady = 0` immediately after `play()`, which means the DTS Java path was starting once the queue was full, not once the direct IEC track had real written startup frames.
  - This is a transport/startup contract issue inside the new Java DTS path, not a packer issue.
- Validation:
  - `./gradlew -p /Users/jneerdael/Scripts/nexio/media :lib-exoplayer:compileDebugJavaWithJavac :lib-exoplayer-kodi-cpp-audiosink:compileDebugJavaWithJavac`
  - `BUILD SUCCESSFUL`


## 2026-03-15 19:56:32 +0100

- Corrected a deadlock in the DTS/DTS-HD Java transport startup gate.
- Files changed:
  - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiNativeAudioSink.java`
- Change:
  - Reverted Java DTS `hasPendingData()` before play from `direct track primed` back to `startup queue full`.
  - Kept the newer direct-track priming logic inside `play()` itself.
- Reason:
  - The latest runtime log showed no `Media3 -> AudioSink play()` events at all.
  - That proved the previous gate was circular: direct-track priming only happens once `play()` is entered, so using `direct track primed` as the pre-play readiness signal deadlocked renderer start.
- Validation:
  - `./gradlew -p /Users/jneerdael/Scripts/nexio/media :lib-exoplayer-kodi-cpp-audiosink:compileDebugJavaWithJavac`
  - `BUILD SUCCESSFUL`


## 2026-03-15 20:49:33 +0100

- Performed detailed Java-side parity analysis for the migrated DTS/DTS-HD path against stock Media3.
- Sources reviewed:
  - `media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DefaultAudioSink.java`
  - `media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/AudioTrackAudioOutput.java`
  - `media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/KodiIecAudioOutput.java`
  - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiNativeAudioSink.java`
- Architectural conclusion:
  - DTS/DTS-HD no longer fails because of Kodi packer semantics; the remaining issues are now in the custom Java transport and sink contract relative to stock Media3.
- Confirmed Java-side parity gaps versus stock Media3:
  - Stock `DefaultAudioSink` owns exactly one `inputBuffer` and one `outputBuffer`; the migrated DTS path currently owns an explicit queue of packed IEC buffers, which changes readiness and end-of-stream semantics.
  - Stock `AudioTrackAudioOutput.play()` always starts immediately once the sink is initialized; the migrated DTS path adds pre-play priming and delayed transport start, so `hasPendingData()` and `play()` semantics can diverge from renderer expectations.
  - Stock `AudioTrackAudioOutput` only counts encoded frames as written when a full encoded buffer is submitted; the migrated DTS path tracks queued IEC frames separately from frames actually written into the direct track, so startup gating must be careful not to treat queued bytes as hardware progress.
  - Stock `AudioTrackAudioOutput.flush()` resets a live `AudioTrack`; the migrated DTS path sometimes must fully release and recreate the direct IEC track on seek/discontinuity because the device does not support direct-track restoration.
- Next-fix boundary:
  - Further DTS work should focus on bringing `KodiNativeAudioSink` + `KodiIecAudioOutput` closer to stock `DefaultAudioSink`/`AudioTrackAudioOutput` semantics for `play()`, `hasPendingData()`, `isEnded()`, and direct-track lifecycle, while keeping native Kodi limited to IEC packing only.


## 2026-03-15 21:06:06 +0100

- Implemented the first parity-driven `KodiIecAudioOutput` fixes after the stock Media3 comparison.
- Files changed:
  - `media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/KodiIecAudioOutput.java`
- Change:
  - Reworked direct IEC `AudioTrack` creation to follow stock `AudioTrackAudioOutputProvider` more closely:
    - no forced generated session ID
    - explicit stock-style builder path
    - smaller-buffer fallback attempt on init failure
  - Reworked direct IEC track release to use delayed asynchronous release similar to stock `AudioTrackAudioOutput.release()`.
- Reason:
  - The latest DTS log showed `Cannot create AudioTrack [1000]` originating from our bespoke `AudioTrack.Builder` path in `KodiIecAudioOutput.configure()`.
  - Stock Media3 already has different init/release semantics around `AudioTrack`; this patch moves our custom Java IEC transport closer to those semantics without changing the DTS packer architecture.
- Validation:
  - `./gradlew -p /Users/jneerdael/Scripts/nexio/media :lib-exoplayer:compileDebugJavaWithJavac :lib-exoplayer-kodi-cpp-audiosink:compileDebugJavaWithJavac`
  - `BUILD SUCCESSFUL`

## 2026-03-15 21:21:00 +0100

- Implemented the next Java-side DTS/DTS-HD parity step by removing the explicit queued packed-IEC state machine.
- Files changed:
  - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiNativeAudioSink.java`
- Change:
  - Replaced the Java DTS `ArrayDeque<QueuedPackedPacket>` queue with a single current packed packet model.
  - `handleDtsIecBuffer(...)` now behaves closer to stock `DefaultAudioSink.handleBuffer(...)` semantics:
    - one current packed output packet at a time
    - drain the current output packet first
    - only then pack the next portion of the current input buffer
    - backpressure is driven by the current output packet, not by an independent queue-capacity state machine
  - Removed the Java DTS queue-full logic and queue-frame accounting.
  - Kept direct-track priming and Java-owned transport in place for now.
- Reason:
  - The detailed stock parity analysis showed the DTS Java path still diverged too far from `DefaultAudioSink` because it owned a long-lived queue of packed IEC packets and separate startup queue semantics.
  - Stock `DefaultAudioSink` is based on one current input buffer and one current output buffer; this patch moves the DTS path materially closer to that model instead of layering more heuristics on top of a queue.
- Validation:
  - `./gradlew -p /Users/jneerdael/Scripts/nexio/media :lib-exoplayer:compileDebugJavaWithJavac :lib-exoplayer-kodi-cpp-audiosink:compileDebugJavaWithJavac`
  - `./gradlew -p /Users/jneerdael/Scripts/nexio app:compileDebugKotlin`
  - `BUILD SUCCESSFUL`

## 2026-03-15 21:31:00 +0100

- Implemented the next DTS/DTS-HD Java transport parity fix after runtime validation of the single-current-buffer model.
- Files changed:
  - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiNativeAudioSink.java`
- Change:
  - Moved DTS direct-track priming out of a special `play()` startup phase and into the normal `handleDtsIecBuffer(...)` flow.
  - The Java DTS path now attempts to write the current packed IEC packet into the direct IEC `AudioTrack` during `handleBuffer()`, before `play()`, which is closer to how stock `DefaultAudioSink` / `AudioTrackAudioOutput` prefill the underlying `AudioTrack` before playback begins.
  - Kept `hasPendingData()` non-circular by continuing to use `currentPackedPacket || parser backlog || direct-track pending data` as the pre-play readiness signal, rather than requiring the track to already be primed before `play()` can ever be entered.
  - Simplified the DTS `play()` path so it starts the already-prefilled Java transport instead of owning a separate priming stage.
- Reason:
  - The latest DTS runtime log showed `play()` followed by immediate direct IEC underrun at `2048 / 8192`, which means the rewritten single-current-buffer model was still not matching stock Java transport behavior: the direct IEC `AudioTrack` was not being prefed enough before `play()`.
  - Stock Media3 writes into the underlying `AudioTrack` during buffer handling before `play()`; this patch moves the DTS Java path toward that behavior.
- Validation:
  - `./gradlew -p /Users/jneerdael/Scripts/nexio/media :lib-exoplayer:compileDebugJavaWithJavac :lib-exoplayer-kodi-cpp-audiosink:compileDebugJavaWithJavac`
  - `./gradlew -p /Users/jneerdael/Scripts/nexio app:compileDebugKotlin`
  - `BUILD SUCCESSFUL`

## 2026-03-15 21:41:00 +0100

- Implemented a broader Java-side DTS/DTS-HD parity pass after the latest runtime log showed multiple active parity gaps at once.
- Files changed:
  - `media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/KodiIecAudioOutput.java`
  - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiNativeAudioSink.java`
- Change:
  - Aligned `KodiIecAudioOutput.write(...)` with stock non-PCM frame accounting semantics:
    - the Java IEC output now only increments written encoded frames when a full packed IEC packet is submitted, instead of incrementing frames on partial byte writes.
  - Removed the remaining custom DTS startup gate from `KodiNativeAudioSink`:
    - DTS `play()` now starts the Java IEC output immediately when configured, instead of waiting for a custom `track primed` gate.
    - DTS `hasPendingData()` now uses one consistent stock-like pending state (`current packed packet || parser backlog || direct track pending data`) both before and after play, instead of switching startup modes.
  - Moved DTS direct-track lifecycle closer to stock `flush()` semantics:
    - DTS `flush()` / `handleDiscontinuity()` now flush the configured Java direct track instead of always releasing and recreating it.
    - explicit release/recreate is now reserved for actual recoverable dead-object write errors.
  - Added DTS recoverable write-error handling:
    - on `AudioTrack` dead-object style errors from the Java IEC output, release the direct track, preserve DTS state for retry, and clear the started state so reconfiguration can occur on the next packet.
- Reason:
  - The runtime log no longer pointed to a single startup bug; it showed a combination of parity gaps:
    - immediate `play()` followed by pause without stable progress
    - recoverable `AudioTrack write failed: -6`
    - continued custom startup-mode behavior in the Java DTS path
  - These changes move the migrated DTS path materially closer to stock `DefaultAudioSink` / `AudioTrackAudioOutput` semantics while keeping Kodi native limited to packing.
- Validation:
  - `./gradlew -p /Users/jneerdael/Scripts/nexio/media :lib-exoplayer:compileDebugJavaWithJavac :lib-exoplayer-kodi-cpp-audiosink:compileDebugJavaWithJavac`
  - `./gradlew -p /Users/jneerdael/Scripts/nexio app:compileDebugKotlin`
  - `BUILD SUCCESSFUL`

## 2026-03-15 21:55:00 +0100

- Implemented the larger DTS/DTS-HD Java transport refactor toward stock Media3 output infrastructure.
- Files changed:
  - `media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/KodiIecAudioOutput.java`
  - `media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/AudioTrackAudioOutput.java`
- Change:
  - Reworked `KodiIecAudioOutput` to stop owning `AudioTrack` and `AudioTrackPositionTracker` directly.
  - `KodiIecAudioOutput` now delegates transport/lifecycle to stock `AudioTrackAudioOutput`, created from a stock `AudioOutputProvider.OutputConfig` with `ENCODING_IEC61937`.
  - Preserved only minimal wrapper state in `KodiIecAudioOutput` for DTS pending-frame accounting needed by `KodiNativeAudioSink`.
  - Added IEC61937 frame-accounting support inside stock `AudioTrackAudioOutput.write(...)` by deriving encoded frames from the configured channel mask and submitted IEC burst size when the output encoding is `AudioFormat.ENCODING_IEC61937`.
- Reason:
  - Full parity requires moving off the hand-rolled Java `AudioTrack` wrapper path and onto stock Media3 output lifecycle semantics.
  - The remaining stock blocker was that `AudioTrackAudioOutput` had no frame-accounting path for IEC61937 bursts; patching that seam allows the Kodi-packed DTS/DTS-HD path to reuse stock output behavior instead of shadowing it.
- Validation:
  - `./gradlew -p /Users/jneerdael/Scripts/nexio/media :lib-exoplayer:compileDebugJavaWithJavac :lib-exoplayer-kodi-cpp-audiosink:compileDebugJavaWithJavac`
  - `./gradlew -p /Users/jneerdael/Scripts/nexio app:compileDebugKotlin`
  - `BUILD SUCCESSFUL`

## 2026-03-15 22:08:00 +0100

- Implemented the next DTS/DTS-HD parity step at the Java buffer-ownership boundary.
- Files changed:
  - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiNativeAudioSink.java`
- Change:
  - DTS input bytes are no longer acknowledged to the Kodi IEC packer at pack time.
  - The Java DTS path now keeps one current renderer-owned DTS input buffer and one derived packed IEC output packet, closer to stock `DefaultAudioSink` input/output ownership.
  - Input bytes are only advanced and acknowledged after the current packed IEC packet has been fully written to the Java audio output.
  - On recoverable direct-output dead-object errors, the current packed packet is invalidated, but the current renderer-owned DTS input buffer is retained so the packed output can be regenerated on the next pass instead of continuing with stale packed output ownership.
- Reason:
  - The latest runtime log still showed recoverable `AudioTrack write failed: -6` failures after the stock-output refactor.
  - The remaining parity gap was that DTS input was still being acknowledged too early, which prevented clean regeneration after output recovery and diverged from stock single-current-input ownership semantics.
- Validation:
  - `./gradlew -p /Users/jneerdael/Scripts/nexio/media :lib-exoplayer-kodi-cpp-audiosink:compileDebugJavaWithJavac :lib-exoplayer:compileDebugJavaWithJavac`
  - `./gradlew -p /Users/jneerdael/Scripts/nexio app:compileDebugKotlin`
  - `BUILD SUCCESSFUL`

## 2026-03-15 22:19:00 +0100

- Applied a broader DTS/DTS-HD Java parity sweep in:
  - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiNativeAudioSink.java`
- Parity changes:
  - Removed custom play-time DTS queue draining from `play()`.
  - Enforced stock-like current-input-buffer identity for DTS: a new renderer buffer is not accepted while the current one is still pending.
  - Stopped treating parser backlog / not-yet-packed DTS input as `hasPendingData()`.
  - DTS `hasPendingData()` now only reflects the current packed IEC packet and actual Java `AudioOutput` pending data.
  - DTS `flush()` now releases the Java `AudioOutput` instead of only flushing it, matching stock `DefaultAudioSink.flush()` ownership more closely.
  - Removed custom recoverable-write handling inside `writePendingPackedPacket()`; recoverable `AudioOutput.WriteException` now bubbles like stock `DefaultAudioSink` instead of releasing/reconfiguring the output inline.
  - DTS `playToEndOfStream()` no longer force-stops the Java output; it now mirrors stock end-of-stream handling more closely by waiting for pending data to drain.
- Reason:
  - Current logs show the main remaining DTS failures are parity gaps in the Java wrapper contract around stock `AudioTrackAudioOutput`, not the Kodi packer.
  - The old custom DTS queue and inline recovery logic were still diverging from stock sink ownership and pending-data semantics.

## 2026-03-15 22:31:00 +0100

- Reworked the DTS/DTS-HD Java path in `KodiNativeAudioSink` toward a stricter stock `DefaultAudioSink` buffer model.
- Changes:
  - DTS now uses one current renderer-owned input buffer and one current packed IEC output buffer as the only active transport state.
  - Replaced the old `flushJavaDtsQueue()` queue/startup flow with `flushJavaDtsState()` + `packCurrentJavaDtsInput()` to mirror stock `handleBuffer -> processBuffers -> drainOutputBuffer` ownership more closely.
  - DTS `handleBuffer()` now only accepts the same current renderer buffer until it is fully consumed.
  - DTS `playToEndOfStream()` now stops the Java output only after current input/output state has drained, instead of using a separate startup queue model.
  - DTS `isEnded()` now requires current renderer input, current packed output, and Java output pending data all to be drained.
  - Added explicit DTS stalled-output escalation as a recoverable write failure instead of silently continuing a custom startup mode.
- Reason:
  - The previous path still kept a custom DTS transport state machine around stock `AudioTrackAudioOutput`, which matched the unchanged `-6` recoverable write failures and immediate play/pause churn in logs.

## 2026-03-15 22:48:00 +0100

- Began the full DTS/DTS-HD sink-parity rewrite by moving DTS transport ownership out of `KodiNativeAudioSink` and into the wrapped `DefaultAudioSink` via a custom `AudioOutputProvider`.
- Added:
  - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiPassthroughAudioOutputProvider.java`
  - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiDtsPackerAudioOutput.java`
- Wiring:
  - `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt` now installs `KodiPassthroughAudioOutputProvider` into the underlying `DefaultAudioSink` before wrapping it in `KodiNativeAudioSink`.
- Runtime boundary change:
  - DTS/DTS-HD/UHD are no longer intercepted by the custom DTS branch inside `KodiNativeAudioSink`; those formats now delegate to the wrapped `DefaultAudioSink` so stock sink semantics own `handleBuffer`, `play`, `flush`, `isEnded`, `hasPendingData`, and recoverable write handling.
  - `KodiNativeAudioSink` remains responsible for the old native path only for E-AC3 and the other untouched non-DTS passthrough formats.
- Important note:
  - This is the first genuinely non-partial parity step because it removes DTS transport from the custom sink wrapper instead of patching that wrapper again.

## 2026-03-15 22:55:00 +0100

- Closed the missing `AudioOutputProvider` parity seam for DTS/DTS-HD.
- File changed:
  - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiPassthroughAudioOutputProvider.java`
- Change:
  - DTS/DTS-HD/UHD no longer delegate `getOutputConfig()` to stock passthrough negotiation, which does not know how to configure Kodi-packed IEC61937 DTS.
  - The provider now reports DTS-family formats as directly supported and synthesizes an IEC61937 `OutputConfig` itself for the wrapped `DefaultAudioSink`.
- Reason:
  - The previous full-parity rewrite moved DTS transport to stock `DefaultAudioSink`, but left `getOutputConfig()` delegating to stock passthrough config generation, causing:
    - `AudioOutputProvider$ConfigurationException: Unable to configure passthrough for ... audio/vnd.dts.hd`

## 2026-03-15 23:02:00 +0100

- Fixed the next stock `DefaultAudioSink` parity seam after the provider rewrite.
- File changed:
  - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiPassthroughAudioOutputProvider.java`
- Change:
  - DTS-family `OutputConfig` now reports the original DTS/DTS-HD/UHD encoding to `DefaultAudioSink`, not `ENCODING_IEC61937`.
  - The custom `AudioOutput` still performs Kodi IEC packing and IEC61937 transport internally.
- Reason:
  - `DefaultAudioSink.handleBuffer()` computes `framesPerEncodedSample` from `configuration.outputConfig.encoding` before the custom `AudioOutput` sees the buffer.
  - Reporting `ENCODING_IEC61937` there caused `Unexpected audio encoding: 13` because the sink is still receiving raw DTS-family input buffers at that layer.

## 2026-03-15 23:10:00 +0100

- Closed another remaining non-parity seam in the DTS provider-based path.
- Files changed:
  - `media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/KodiIecAudioOutput.java`
  - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiDtsPackerAudioOutput.java`
  - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiPassthroughAudioOutputProvider.java`
- Change:
  - The custom DTS `AudioOutput` no longer creates its IEC `AudioTrack` via its own static null-context provider.
  - It now reuses the same underlying `AudioOutputProvider` and base `OutputConfig` that the wrapped `DefaultAudioSink` already computed.
  - The final IEC61937 track config now derives from that stock config and only overrides the parts that must change for packed IEC transport.
- Reason:
  - The log still showed `createTrack() getOutputForAttr() return error -38` after the provider rewrite.
  - That matched a remaining parity gap where the packed DTS path was still constructing a second disconnected direct-output config instead of inheriting session/device/provider context from the stock sink path.

## 2026-03-15 23:25:00 +0100

- Completed the wrapper-level DTS/DTS-HD parity cleanup and removed the last dead custom DTS transport branch from `KodiNativeAudioSink`.
- Files changed:
  - `media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/AudioOutputProvider.java`
  - `media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DefaultAudioSink.java`
  - `media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/AudioTrackAudioOutput.java`
  - `media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/KodiIecAudioOutput.java`
  - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiPassthroughAudioOutputProvider.java`
  - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiNativeAudioSink.java`
- Change:
  - Split `AudioOutputProvider.OutputConfig` into two distinct meanings:
    - `encoding` now remains the actual `AudioTrack` transport encoding.
    - `inputFrameEncoding` is the raw encoded input format used by `DefaultAudioSink` / `AudioTrackAudioOutput` frame accounting.
  - DTS-family provider configs now advertise:
    - `encoding = ENCODING_IEC61937`
    - `inputFrameEncoding = DTS / DTS-HD / DTS-UHD`
  - `KodiIecAudioOutput` now forces `inputFrameEncoding = ENCODING_IEC61937` for the already-packed transport stage.
  - Removed the remaining dead custom DTS transport state from `KodiNativeAudioSink`:
    - no `KodiIecPacker` field
    - no `KodiIecAudioOutput` field
    - no current DTS input/output packet state
    - no custom DTS flush/play/drain helpers
  - Resulting DTS runtime boundary is now:
    - `DefaultAudioSink`
    - `KodiPassthroughAudioOutputProvider`
    - `KodiDtsPackerAudioOutput`
    - `KodiIecAudioOutput`
    - `AudioTrackAudioOutput`
- Reason:
  - The previous provider-backed rewrite still had one fundamental parity gap: stock sink frame accounting and actual `AudioTrack` creation were sharing the same `OutputConfig.encoding`, but the Kodi-packed DTS path needs those to differ.
  - `KodiNativeAudioSink` still retained a compile-only DTS transport branch, which meant parity was not actually complete at the wrapper boundary.
- Validation:
  - `./gradlew -p /Users/jneerdael/Scripts/nexio/media :lib-exoplayer-kodi-cpp-audiosink:compileDebugJavaWithJavac :lib-exoplayer:compileDebugJavaWithJavac`
  - `./gradlew -p /Users/jneerdael/Scripts/nexio app:compileDebugKotlin`
  - `BUILD SUCCESSFUL`

## 2026-03-15 23:35:00 +0100

- Closed the remaining stock `AudioOutput` contract gaps in the provider-backed DTS path.
- Files changed:
  - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiDtsPackerAudioOutput.java`
  - `media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/KodiIecAudioOutput.java`
- Change:
  - `KodiDtsPackerAudioOutput` now forwards `AudioOutput.Listener` callbacks to the underlying output instead of treating `addListener/removeListener` as no-ops.
  - `KodiDtsPackerAudioOutput` now forwards real `AudioSessionId` and preferred-device changes to the underlying output.
  - `KodiDtsPackerAudioOutput.flush()` no longer tries to re-play immediately after releasing the underlying output.
  - `KodiIecAudioOutput` now exposes and forwards:
    - `getAudioSessionId()`
    - `addListener/removeListener()`
    - `setPreferredDevice()`
- Reason:
  - The latest provider-backed log no longer showed initialization or write exceptions, but playback still reached `play()` and then stopped without establishing steady playout.
  - A remaining parity gap was that the custom DTS `AudioOutput` chain still did not behave like stock `AudioTrackAudioOutput` for listener propagation and session/device plumbing, which `DefaultAudioSink` relies on.
- Validation:
  - `./gradlew -p /Users/jneerdael/Scripts/nexio/media :lib-exoplayer-kodi-cpp-audiosink:compileDebugJavaWithJavac :lib-exoplayer:compileDebugJavaWithJavac`
  - `./gradlew -p /Users/jneerdael/Scripts/nexio app:compileDebugKotlin`
  - `BUILD SUCCESSFUL`

## 2026-03-15 23:43:00 +0100

- Closed the pre-configure lifecycle/state propagation gap in the provider-backed DTS adapter chain.
- Files changed:
  - `media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/KodiIecAudioOutput.java`
  - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiDtsPackerAudioOutput.java`
- Change:
  - `KodiIecAudioOutput` now stores and reapplies stock `AudioOutput` state across configure/recreate boundaries:
    - listeners
    - preferred device
    - player ID
    - aux effect attachment and send level
    - playback parameters
    - volume
  - `KodiDtsPackerAudioOutput` now forwards `setPlayerId`, `attachAuxEffect`, and `setAuxEffectSendLevel` instead of dropping them.
- Reason:
  - `DefaultAudioSink` installs listeners and session/device/effects policy before steady playout is established.
  - The provider-backed DTS path was still losing that state whenever the underlying IEC transport output did not yet exist or was recreated, which is a real stock-parity violation.
- Validation:
  - `./gradlew -p /Users/jneerdael/Scripts/nexio/media :lib-exoplayer-kodi-cpp-audiosink:compileDebugJavaWithJavac :lib-exoplayer:compileDebugJavaWithJavac`
  - `./gradlew -p /Users/jneerdael/Scripts/nexio app:compileDebugKotlin`
  - `BUILD SUCCESSFUL`

## 2026-03-15 23:50:00 +0100

- Final static parity sweep completed for the DTS/DTS-HD provider-backed path.
- Files changed:
  - `media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/KodiIecAudioOutput.java`
- Change:
  - Removed the last compatibility shims that only existed for the deleted wrapper-owned DTS transport path:
    - no-arg constructor using a null-context provider
    - legacy configure overload taking `AudioAttributes` directly
- Final static conclusion:
  - Wrapper-level DTS transport parity is complete in source apart from the intentional Kodi packer seam.
  - `KodiNativeAudioSink` no longer owns DTS transport state.
  - The DTS provider-backed chain now preserves stock lifecycle state across configure/recreate:
    - listeners
    - audio session ID
    - preferred device
    - player ID
    - aux effects
    - playback parameters
    - volume
  - The remaining custom logic is limited to:
    - Kodi DTS/DTS-HD IEC packing
    - packed-IEC adaptation into stock `AudioTrackAudioOutput`
- Validation:
  - `./gradlew -p /Users/jneerdael/Scripts/nexio/media :lib-exoplayer-kodi-cpp-audiosink:compileDebugJavaWithJavac :lib-exoplayer:compileDebugJavaWithJavac`
  - `./gradlew -p /Users/jneerdael/Scripts/nexio app:compileDebugKotlin`
  - `BUILD SUCCESSFUL`

## 2026-03-15 23:58:00 +0100

- Fixed the DTS provider-backed `AudioTrack` config mismatch that was still causing runtime init failures after the parity refactor.
- Files changed:
  - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiPassthroughAudioOutputProvider.java`
- Change:
  - Stopped deriving the DTS IEC transport channel mask from hardcoded channel-count fallbacks when a `Context` is available.
  - The provider now uses the public stock capability API `AudioCapabilities.getEncodingAndChannelConfigForPassthrough(format, audioAttributes)` to obtain the passthrough channel config and reuses that channel mask for the synthesized DTS IEC output config.
  - The hardcoded channel-count mapping remains only as fallback when stock capabilities cannot be queried.
- Reason:
  - The latest runtime failure was `AudioTrack init failed 0 Config (192000, 252, 13, 576000)` for DTS-HD.
  - `252` (`CHANNEL_OUT_7POINT1_SURROUND`) did not match the previously observed working DTS-HD direct-output channel config from AudioFlinger (`0x63f`).
  - This was a remaining provider-config parity gap: the DTS provider-backed path was not using the same passthrough capability-derived channel mask as stock.
- Validation:
  - `./gradlew -p /Users/jneerdael/Scripts/nexio/media :lib-exoplayer-kodi-cpp-audiosink:compileDebugJavaWithJavac :lib-exoplayer:compileDebugJavaWithJavac`
  - `./gradlew -p /Users/jneerdael/Scripts/nexio app:compileDebugKotlin`
  - `BUILD SUCCESSFUL`

## 2026-03-16 00:12:00 +0100

- Fixed a remaining DTS provider-backed config parity gap that was still forcing the failing `AudioTrack` init config even after the provider refactor.
- Files changed:
  - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiPassthroughAudioOutputProvider.java`
  - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiDtsPackerAudioOutput.java`
- Change:
  - `KodiPassthroughAudioOutputProvider` now derives the DTS-family passthrough channel mask from the public stock capability API `AudioCapabilities.getEncodingAndChannelConfigForPassthrough(format, audioAttributes)` when a `Context` is available.
  - `KodiDtsPackerAudioOutput` no longer re-synthesizes the direct IEC `AudioTrack` channel mask and buffer size from packet channel count and a zero buffer hint.
  - The packer output now configures `KodiIecAudioOutput` from the provider-authoritative `OutputConfig`:
    - provider channel mask
    - provider buffer size
    - packet sample rate only
- Reason:
  - The runtime error stayed at `AudioTrack init failed 0 Config (192000, 252, 13, 576000)` even after the provider change.
  - Root cause was that the provider-backed DTS path still had two competing transport config sources:
    1. provider `OutputConfig`
    2. packer-side hardcoded `channelMaskForCount(...)` and zero buffer size
  - So the provider parity fix was being overwritten before `AudioTrack` creation.
- Validation:
  - `./gradlew -p /Users/jneerdael/Scripts/nexio/media :lib-exoplayer-kodi-cpp-audiosink:compileDebugJavaWithJavac :lib-exoplayer:compileDebugJavaWithJavac`
  - `./gradlew -p /Users/jneerdael/Scripts/nexio app:compileDebugKotlin`
  - `BUILD SUCCESSFUL`

## 2026-03-16 00:22:00 +0100

- Fixed another remaining DTS provider-backed parity gap: the packer output was still lazy-initializing the inner IEC `AudioOutput` inside `write()` and masking init/config failures as generic `WriteException(-1)`.
- Files changed:
  - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiDtsPackerAudioOutput.java`
- Change:
  - `KodiDtsPackerAudioOutput` now configures the inner `KodiIecAudioOutput` eagerly in its constructor from the provider-authoritative `OutputConfig`.
  - `flush()` no longer releases and recreates the inner IEC output on every cycle; it now flushes it, matching stock `AudioOutput` lifecycle more closely.
  - The lazy `ensureAudioOutputConfigured(...)` path that converted `InitializationException` into `WriteException(-1)` during `write()` was removed.
- Reason:
  - The latest DTS-HD log failed with `AudioTrack write failed: -1`, but the adapter still had a code path where any inner `InitializationException` was being remapped to a generic recoverable write error.
  - This obscured the real failure class and kept a custom init lifecycle seam in the provider-backed path.
- Validation:
  - `./gradlew -p /Users/jneerdael/Scripts/nexio/media :lib-exoplayer-kodi-cpp-audiosink:compileDebugJavaWithJavac :lib-exoplayer:compileDebugJavaWithJavac`
  - `./gradlew -p /Users/jneerdael/Scripts/nexio app:compileDebugKotlin`
  - `BUILD SUCCESSFUL`

## 2026-03-16 00:31:00 +0100

- Added Kodi-style passthrough init retry behavior to the provider-backed DTS IEC output path.
- Files changed:
  - `media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/KodiIecAudioOutput.java`
- Change:
  - `KodiIecAudioOutput.configure(...)` now retries `AudioOutputProvider.getAudioOutput(outputConfig)` once after a 200 ms delay if the first passthrough IEC `AudioTrack` init attempt fails.
  - This mirrors Kodi `AESinkAUDIOTRACK`, which retries one PT-device open after a short delay because some devices reopen the passthrough device too fast around startup/seek.
- Reason:
  - After the previous refactor, DTS-HD failures became truthful init failures again: `AudioTrack init failed 0 Config (...)`.
  - Kodi source shows that a single delayed retry is part of the passthrough contract for these devices and had not yet been carried over into the provider-backed Java path.
- Validation:
  - `./gradlew -p /Users/jneerdael/Scripts/nexio/media :lib-exoplayer-kodi-cpp-audiosink:compileDebugJavaWithJavac :lib-exoplayer:compileDebugJavaWithJavac`
  - `./gradlew -p /Users/jneerdael/Scripts/nexio app:compileDebugKotlin`
  - `BUILD SUCCESSFUL`

## 2026-03-16 00:41:00 +0100

- Switched DTS-HD final IEC carrier selection from container metadata to packer-detected stream properties.
- Files changed:
  - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiDtsPackerAudioOutput.java`
- Change:
  - The provider-backed DTS path no longer finalizes `AudioTrack` carrier config from the source `Format` metadata alone.
  - It now waits for the first packed IEC packet and configures transport from the actual Kodi packer output:
    - `packet.outputRate`
    - `packet.outputChannels`
  - The final transport buffer size is also derived from the packed stream characteristics instead of the MKV metadata-derived base size alone.
- Reason:
  - The failing stream is signaled to the renderer as `Format(... [6, 48000])`, but Kodi's own parser logs it as `dtsHD MA stream detected (8 channels, 48000Hz ...)`.
  - Using the container metadata for DTS-HD MA kept forcing the wrong IEC carrier config (`5.1`/`252`) even though the actual packed stream required a multichannel DTS-HD carrier.
  - The only source-grounded place to know the real DTS-HD carrier characteristics is the Kodi packer output itself.
- Validation:
  - `./gradlew -p /Users/jneerdael/Scripts/nexio/media :lib-exoplayer-kodi-cpp-audiosink:compileDebugJavaWithJavac :lib-exoplayer:compileDebugJavaWithJavac`
  - `./gradlew -p /Users/jneerdael/Scripts/nexio app:compileDebugKotlin`
  - `BUILD SUCCESSFUL`

## 2026-03-16 01:15:00 +0100

- Hardened recoverable direct-track failure handling in:
  - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiDtsPackerAudioOutput.java`
- Change:
  - If a DTS direct IEC write fails with a recoverable error such as `-6`, the adapter now releases the dead `AudioOutput` immediately but preserves the current packed IEC packet.
  - Subsequent retries re-enter `ensureAudioOutputConfigured(...)` before writing again, so replay happens on a freshly created direct track instead of a dead one.
- Reason:
  - The latest log shows the DTS-HD MA carrier is now correct (`192000`, `0x63f`) and the remaining failure is a recoverable dead direct track during write.
  - This closes a remaining lifecycle gap in the provider-backed DTS path without changing Kodi packing or carrier selection.

## 2026-03-16 01:32:00 +0100

- Executed requested rollback from the later DTS provider-backed/packer-only transport refactor back toward the native-sink architecture used before the `2026-03-15 15:11:04 +0100` checkpoint.
- Removed provider-backed DTS transport wiring:
  - reverted stock Media3 transport config changes in:
    - `media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/AudioOutputProvider.java`
    - `media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/AudioTrackAudioOutput.java`
    - `media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DefaultAudioSink.java`
  - removed added DTS provider/packer transport files:
    - `media/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/KodiIecAudioOutput.java`
    - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiDtsPackerAudioOutput.java`
    - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiIecPacker.java`
    - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/java/androidx/media3/exoplayer/audio/kodi/KodiPassthroughAudioOutputProvider.java`
    - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiIecPackerSession.cpp`
    - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiIecPackerSession.h`
    - `media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/kodi_cpp_packer_bridge.cpp`
  - removed provider injection from:
    - `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`
- Restored the native-sink path as the active DTS/DTS-HD route and kept the pre-provider startup-window/burst-window machinery in `KodiNativeAudioSink`.
- Repaired the JNI pipeline interface to match the native sink contract after the rollback by restoring the single-packet `KodiIecPipeline::Feed(...)` signature.
- Build status after rollback:
  - `:media:lib-exoplayer-kodi-cpp-audiosink:externalNativeBuildDebug`
  - `:media:lib-exoplayer-kodi-cpp-audiosink:compileDebugJavaWithJavac`
  - `:media:lib-exoplayer:compileDebugJavaWithJavac`
  - `:app:compileDebugKotlin`
  - all passed
