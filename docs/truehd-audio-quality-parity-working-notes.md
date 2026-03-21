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
