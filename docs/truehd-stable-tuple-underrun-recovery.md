## TrueHD stable-tuple late-stream recovery note

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
- The newer Group D runs replace `forced_retry` with `steady_state_output_driven`, but audio quality is still operator-reported as `WEAK` / `CHOPPY`.

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

Current native mismatch to investigate:
- `FlushTrueHdPackedQueueToHardwareLocked()` resets steady-state retry state after any positive progress on a retried remainder
- that turns the next smaller tail on the same packet into a fresh retry episode
- the next tail can immediately hit another zero write instead of staying inside one bounded steady-state retry episode

Updated read after the latest valid Group E run:
- Removing the partial-success retry reset helped somewhat, but it did not normalize audio quality.
- In `/tmp/transport-validation-truehd-1774129824226.zip`, the dominant late-stream steady-state
  packet sequences are still `zero -> success` and `zero -> zero -> success`.
- The remaining gap is now best described as late-stream bounded zero-write cadence under a stable
  tuple, not route churn and not retry-reason fallback.
