# TrueHD Audio Quality Parity Working Notes

## Post-Group C Baseline

| Field | Group B baseline `/tmp/transport-validation-truehd-1774122320221.zip` | Group C valid rerun `/tmp/transport-validation-truehd-1774123156108.zip` |
| --- | --- | --- |
| `transportVerdict` | `PASS` | `PASS` |
| `runtimeVerdict` | `DEGRADED` | `DEGRADED` |
| `timeToReadyMs` | `1471` | `2033` |
| `audioUnderrunCount` | `1` | `1` |
| `droppedVideoFrames` | `46` | `4` |
| `writeAttemptCount` | `5894` | `7177` |
| `successfulWriteCount` | `4552` | `4551` |
| `partialWriteCount` | `8` | `9` |
| `zeroWriteCount` | `1342` | `2626` |
| `remainderRetryEventCount` | `2204` | `2630` |
| `retryReasonCounts` | `steady_state_output_driven=1345`, `steady_state_zero_retry_backoff=859` | `forced_retry=1377`, `steady_state_output_driven=1252`, `steady_state_zero_retry_backoff=1` |
| `longestStuckRemainderMs` | `76` | `75` |
| `longestZeroWriteStreakMs` | `100` | `65` |
| `continuousPlayingWindowSatisfied` | `true` | `true` |

## Root-Cause Evidence

### Late underrun excerpt

From `/tmp/passthrough-validation-192.168.50.37-truehd-groupc-manual.log`:

```text
03-21 20:59:08.902 11043 14722 W AudioFlinger: pause because of UNDERRUN, framesReady = 512,minFrames = 8192, mFormat = 0xd000000
03-21 20:59:08.945 11043 14722 W AudioFlinger: pause because of UNDERRUN, framesReady = 0,minFrames = 8192, mFormat = 0xd000000
```

### Example `forced_retry` sink events

From `analytics-events.json` inside `/tmp/transport-validation-truehd-1774123156108.zip`:

```text
audio_write_zero value=20546 detail=requestedBytes=24192 ... ownership=steady_state ... retryReason=forced_retry ... startupActive=true startupCompleted=false ... selectedPath=startup_path
audio_write_success value=3040 detail=requestedBytes=36480 ... ownership=steady_state ... retryReason=forced_retry ... startupActive=false startupCompleted=true ... selectedPath=steady_state_path
audio_write_zero value=3968 detail=requestedBytes=28288 ... ownership=steady_state ... retryReason=forced_retry ... startupActive=false startupCompleted=true ... selectedPath=steady_state_path
```

### Active source condition

From [KodiTrueHdAEEngine.cpp](/Users/jneerdael/Scripts/nexio/.worktrees/codex-truehd-audio-quality/media/libraries/exoplayer_kodi_cpp_audiosink/src/main/jni/src/KodiTrueHdAEEngine.cpp#L1382):

```cpp
if (retryingPendingRemainder && output_.IsPlaying())
{
  ...
  shouldRetry = ShouldRetrySteadyStatePendingPackedRemainderLocked(..., &retryReason);
  ...
}
...
" retryReason=" + std::string(retryReason != nullptr ? retryReason : "forced_retry")
```

The active Group C code still only consults the explicit retry policy when the underlying `AudioTrack` reports `PLAYING`. When `outputStarted_` remains true but `AudioTrack` is no longer playing, steady-state retries can still fall back to synthetic `forced_retry`.

## Reference Mismatch

- Stock Media3 keeps encoded pending output as the truth and does not use a synthetic `forced_retry` branch for encoded pending output.
- Kodi keeps retry and resume behavior tied to the real output-thread state in the sink path.
- Our engine still allows steady-state retries to bypass explicit policy when `outputStarted_` and real `AudioTrack` play state diverge.

## Post-Group E Reality Check

Reference artifacts:
- `/tmp/transport-validation-truehd-1774128629972.zip`
- `/tmp/transport-validation-truehd-1774129824226.zip`

Grounded facts from the active branch:
- The old `forced_retry` path is gone.
- The active source now routes steady-state retry admission through
  `ShouldRetrySteadyStatePendingPackedRemainderLocked(...)`.
- The current issue is no longer hidden retry-reason fallback. It is steady-state
  zero-write cadence under a stable tuple.

Comparison snapshot:

| Field | Group D `/tmp/transport-validation-truehd-1774128629972.zip` | Group E `/tmp/transport-validation-truehd-1774129824226.zip` |
| --- | --- | --- |
| `transportVerdict` | `PASS` | `PASS` |
| `runtimeVerdict` | `DEGRADED` | `DEGRADED` |
| `timeToReadyMs` | `955` | `1076` |
| `audioUnderrunCount` | `1` | `0` |
| `writeAttemptCount` | `7119` | `6466` |
| `zeroWriteCount` | `2567` | `2365` |
| `remainderRetryEventCount` | `2570` | `2368` |
| `retryReasonCounts` | `steady_state_retry_reason_unset=1360`, `steady_state_output_driven=1210` | `steady_state_output_driven=2368` |

What the latest valid bundle disproves:
- The remaining churn is not coming from first-attempt steady-state zero writes with
  `offsetBytes=0`.
- In `/tmp/transport-validation-truehd-1774129824226.zip`, all exported steady-state
  `audio_write_zero` events occur on real remainders with `offsetBytes>0`.

What the latest valid bundle shows instead:
- The dominant packet-level sequences are:
  - `audio_write_zero -> audio_write_success`
  - `audio_write_zero -> audio_write_zero -> audio_write_success`
- Example packet sequences from `/tmp/transport-validation-truehd-1774129824226.zip`:

```text
packetId=87  : zero -> zero -> success   requestedBytes=36480
packetId=309 : zero -> zero -> success   requestedBytes=44672
packetId=317 : zero -> zero -> zero -> partial   requestedBytes=11904
```

- On the failed retries, `playbackHeadDeltaFrames` is often `0` or very small.
- On the eventual success for the same packet, `playbackHeadDeltaFrames` jumps materially.

That means the engine is still retrying the same steady-state remainder before the sink
has drained enough, even though route stability and transport stay clean.

Duration implication for the common remainders on `IEC61937|192000|7.1`:
- `11904` bytes ~= `3.875 ms`
- `16000` bytes ~= `5.208 ms`
- `20096` bytes ~= `6.542 ms`
- `36480` bytes ~= `11.875 ms`
- `44672` bytes ~= `14.542 ms`
- full `61440`-byte MAT packet ~= `20.0 ms`

Current parity conclusion:
- Kodi does one bounded retry after a zero write and waits roughly a packet duration.
- Stock Media3 leaves the encoded pending buffer as the truth and retries on the next
  renderer opportunity.
- Our current branch still revisits the same steady-state remainder too aggressively
  under a stable tuple, so the next native-only change should target bounded zero-write
  cadence tied to packet/output duration, not another play-state or transport change.

## Structural Pass Hypothesis

Current revert point before the structural pass:
- root `5253bb329`
- media `3757398fae`

Updated root-cause read after the cadence pass:
- the active branch is no longer failing on one retry constant alone
- Java still owns a custom TrueHD startup/steady-state handoff decision
- native still keeps layered startup and steady-state ownership above the actual pending output
- that layered ownership is still more custom than Media3 even though transport and the outer
  contract remain good

The next pass will therefore change architecture, not tuning:
- isolate startup-only behavior from steady-state completely
- move startup completion ownership into the native engine
- make Java handoff passive once native steady-state begins
- collapse steady-state native handling toward one pending-output truth model

Non-goals for this pass:
- no MAT/IEC transport changes
- no Java `AudioSink` contract changes
- no route tuple changes
- no buffer sizing changes

## Incremental Media3-First Handoff Steps

Reference artifacts:
- Step 1 `/tmp/transport-validation-truehd-1774138851778.zip`
- Step 2 `/tmp/transport-validation-truehd-1774139208103.zip`
- Step 3 `/tmp/transport-validation-truehd-1774140016993.zip`

Current grounded result after Step 3:
- Step 1 and Step 2 are still structurally valid and committed
- Step 3 now splits native startup-owned and steady-state-owned passthrough input state
- transport and outer runtime hard gates stayed clean through all three steps
- audio quality is still degraded, so the incremental handoff work should be treated as enabling structure, not the parity fix

Step 2 to Step 3 comparison:

| Field | Step 2 `/tmp/transport-validation-truehd-1774139208103.zip` | Step 3 `/tmp/transport-validation-truehd-1774140016993.zip` |
| --- | --- | --- |
| `transportVerdict` | `PASS` | `PASS` |
| `runtimeVerdict` | `DEGRADED` | `DEGRADED` |
| `playerStateVerdict` | `PASS` | `PASS` |
| `continuousPlayingWindowSatisfied` | `true` | `true` |
| `timeToReadyMs` | `865` | `876` |
| `audioUnderrunCount` | `1` | `1` |
| `droppedVideoFrames` | `34` | `44` |
| `writeAttemptCount` | `6861` | `6633` |
| `zeroWriteCount` | `2312` | `2085` |
| `partialWriteCount` | `5` | `6` |
| `remainderRetryEventCount` | `2313` | `2088` |
| `longestStuckRemainderMs` | `94` | `92` |
| `longestZeroWriteStreakMs` | `76` | `69` |

What Step 3 actually proved:
- the shared native pending passthrough input was a real architecture divergence and it can be removed without touching transport or the outer contract
- emitted packets can be tied to startup-owned vs steady-state-owned input without collapsing playback
- removing that shared slot alone does not eliminate the late-stream audio stutter or the single underrun

What still remains true after Step 3:
- the route tuple stays stable after startup
- transport stays exact
- the remaining defect is still inside audio continuity under the stable tuple, not in transport, not in route selection, and not in the outer Media3 state machine
