# Playback Tracer Microbenchmark — Methodology & Results

This document records the methodology and results for the
`PlaybackTracerBenchmarkTest` microbenchmark introduced in WP10 of the
playback instrumentation rollout. The benchmark exists to defend the
"never perturb the hot path" principle from spec § Constraints — without
it the toggle-off claim is unverified.

## What it measures

Three variants of `PlaybackTracer.emit(EventFamily.FRONTIER, "frontier_advance") { putLong("delta", 65536) }`:

| Variant | Setup | What it measures |
| --- | --- | --- |
| `noEmitBaseline` | call wrapped in `if (false)` (constant-folded out) | empty loop body — the floor for the other variants |
| `emitToggleOff` | `PlaybackTracer.enabled = false` | inline `@JvmField` volatile read + early return |
| `emitToggleOn` | `enabled = true`, real `SessionWriter` draining | full hot path: `obtain` + `PayloadBuilder.putLong` + `MpscArrayQueue.offer` |

The hot-path event under measurement (`frontier_advance` with a single
`putLong`) is the *most frequent* tracer call in production: it fires from
`PagedFrontierBuffer` after every ranged write. If this call is cheap, the
rest of the tracer surface (rarer events with larger payloads) is cheap by
extension.

## Test class

`app/src/androidTest/java/com/nexio/tv/instrumentation/PlaybackTracerBenchmarkTest.kt`

Three `@Test` methods, one per variant. Uses
`androidx.benchmark:benchmark-junit4:1.4.1` (declared in
`gradle/libs.versions.toml` as `androidx-benchmark-junit4`, wired into
`app/build.gradle.kts` as `androidTestImplementation`).

`androidx.benchmark` auto-tunes warmup and iteration counts based on the
measured noise floor; the spec target of "1M iterations / 10K warmup" is
the upper bound the framework will not exceed — actual iteration counts
may converge sooner.

## Spec gates (amendment C3)

| Variant | Metric | Gate |
| --- | --- | --- |
| `emitToggleOff` | p99 nanoseconds per call | ≤ 20 ns |
| `emitToggleOn` | p99 nanoseconds per call | ≤ 5 000 ns (5 µs) |
| `emitToggleOff` vs `noEmitBaseline` | absolute p99 delta | ≤ 1 % of `noEmitBaseline` p99 |

## How to run

The microbenchmark runs as an Android instrumented test on a connected
device or emulator (it cannot run as a JVM unit test — it needs a real
clock and a real `MpscArrayQueue` measured under realistic conditions).

```sh
# 1. Connect a Fire TV / Pixel / emulator over adb (`adb devices` should list it).
# 2. Run the benchmark suite, scoped to just the WP10 class:
./gradlew :app:connectedArm64DebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.nexio.tv.instrumentation.PlaybackTracerBenchmarkTest

# 3. Pull the benchmark report off the device.
#    androidx.benchmark writes a JSON report under the app's external files dir.
adb shell run-as com.nexio.tv ls files/.androidx.benchmark/
adb pull \
  /sdcard/Android/media/com.nexio.tv/PlaybackTracerBenchmarkTest.json \
  docs/instrumentation/PlaybackTracerBenchmarkTest.json
```

The exact JSON path varies by androidx.benchmark version and Android API
level — if `adb pull` 404s, run `adb shell find /sdcard -name 'PlaybackTracerBenchmarkTest*'`
to locate the report.

## Recording results

After running, transcribe the p50/p99 figures from the report into the
table below, sign the row with the device and date, and commit the diff.

| Date | Device | Variant | Iterations | Warmup | p50 ns | p99 ns | Verdict |
| --- | --- | --- | --- | --- | --- | --- | --- |
| _pending_ | _pending_ | `noEmitBaseline` | _pending_ | _pending_ | _pending_ | _pending_ | _pending_ |
| _pending_ | _pending_ | `emitToggleOff` | _pending_ | _pending_ | _pending_ | _pending_ | _pending_ |
| _pending_ | _pending_ | `emitToggleOn` | _pending_ | _pending_ | _pending_ | _pending_ | _pending_ |

Verdict column: `PASS` if all three gates above are satisfied; `FAIL` with
a one-line note if any gate is broken — a `FAIL` verdict means the tracer
is perturbing the hot path and the cause must be root-caused before WP11
can mark the rollout shippable.

## Why this matters

The whole point of the playback-trace v1 rollout is to diagnose stutter
*without introducing new stutter*. If the toggle-off path is not free
(within 1 % of a no-op loop) the tracer is itself a source of regressions
and the JSONL data it produces becomes adversarial — every observation
made on a build with the tracer compiled in is contaminated. The spec
amendment C3 gate exists to prevent exactly that failure mode.

A `PASS` here is a hard prerequisite for marking WP11 done.
