## TrueHD stable-tuple late-stream recovery note

Primary audit reference:
- `/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/docs/truehd-audio-quality-full-parity-audit.md`

Reference artifacts:
- `/tmp/transport-validation-truehd-1774123156108.zip`
- `/tmp/transport-validation-truehd-1774128629972.zip`
- `/tmp/transport-validation-truehd-1774129081182.zip`
- `/tmp/passthrough-validation-192.168.50.37-truehd-groupc-manual.log`

Grounded facts:
- The route tuple remains stable at `IEC61937|192000|7.1` after startup.
- `routeTupleChangeCountAfterStableStart=0`.
- `routeReopenCountAfterStart=0`.
- The Group C manual log contains `AudioFlinger: pause because of UNDERRUN` near the late-stream failure window.
- The active branch no longer treats `forced_retry` as the primary late-stream gap.
- Audio quality is still operator-reported as `WEAK` / `CHOPPY`.

Late-stream write-path shape from the latest valid runs:
- steady-state pending remainders repeatedly hit `audio_write_zero`
- the next flush then records `audio_write_success` on the same `pendingRemainderId`
- the route tuple does not change while this happens
- transport remains correct while the audible defect persists

Representative stable-tuple late-stream sequence from `/tmp/transport-validation-truehd-1774129081182.zip`:

```text
audio_write_zero    requestedBytes=48768 pendingRemainderId=2916 retryReason=steady_state_output_driven
audio_write_success requestedBytes=48768 pendingRemainderId=2916 retryReason=steady_state_output_driven
audio_write_zero    requestedBytes=40576 pendingRemainderId=2918 retryReason=steady_state_output_driven
audio_write_success requestedBytes=40576 pendingRemainderId=2918 retryReason=steady_state_output_driven
audio_write_zero    requestedBytes=3712  pendingRemainderId=2911 retryReason=steady_state_output_driven
audio_write_success requestedBytes=3712  pendingRemainderId=2911 retryReason=steady_state_output_driven
```

Required invariant:
- once the route tuple is stable
- late-stream recovery must stay inside the current native output path
- pending steady-state remainders must not devolve into repeated hidden zero-write churn under the same tuple
- transport bytes and Java `AudioSink` contract behavior must remain unchanged

Updated read after the latest valid Group E run:
- Removing the partial-success retry reset helped somewhat, but it did not normalize audio quality.
- In `/tmp/transport-validation-truehd-1774129824226.zip`, the dominant late-stream steady-state
  packet sequences are still `zero -> success` and `zero -> zero -> success`.
- The remaining gap is now best described as late-stream bounded zero-write cadence under a stable
  tuple, not route churn and not retry-reason fallback.

Current primary mismatch to fix:
- `ShouldRetrySteadyStatePendingPackedRemainderLocked(...)` still uses a fixed `4000 us`
  repeated-zero backoff.
- The common late-stream remainders in the active runtime evidence are often materially larger than
  `4 ms` worth of audio.
- The next implementation group is packet-duration-shaped zero-write gating in the native
  steady-state path.

## Group F validation note

Validated with:
- `/Users/jneerdael/Scripts/nexio/scripts/run_adb_validation.sh`
- bundle: `/tmp/transport-validation-truehd-1774133657269.zip`
- comparison baseline: `/tmp/transport-validation-truehd-1774129824226.zip`

Hard-gate result:
- transport stayed `PASS`
- burst chain stayed `8 -> 64 -> 64 -> 64`
- `routeChangeCountAfterStableStart=0`
- `routeTupleChangeCountAfterStableStart=0`
- `routeReopenCountAfterStart=0`
- `playerStateVerdict=PASS`
- `continuousPlayingWindowSatisfied=true`

What improved:
- `timeToReadyMs`: `1076 -> 996`
- `zeroWriteCount`: `2365 -> 2242`
- `remainderRetryEventCount`: `2368 -> 2242`
- `successfulWriteCount`: `4101 -> 4546`
- retry reasons are now explicit and split:
  - `steady_state_packet_duration_backoff=1385`
  - `steady_state_output_driven=857`

What got worse:
- `audioUnderrunCount`: `0 -> 1`
- `droppedVideoFrames`: `0 -> 51`
- `maxZeroWriteStreak`: `3 -> 4`
- `longestZeroWriteStreakMs`: `37 -> 74`
- `longestStuckRemainderMs`: `13 -> 98`
- packets with `zero -> zero -> success`: `117 -> 484`

Grounded conclusion after Group F:
- Packet-duration gating did not break transport or the validated outer contract boundary.
- It did not normalize late-stream audio quality.
- The active gap is no longer just “too-fast fixed backoff”; it is still stable-tuple late-stream
  zero-write cadence and recovery behavior under the same direct-output route.
- Because Group F preserved hard gates but still worsened late-stream movie-shape metrics, the next
  follow-up should be a separate audit/plan, not an in-place extension of this step.
