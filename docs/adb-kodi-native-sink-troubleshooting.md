# ADB Runbook: Kodi Native IEC Sink Troubleshooting

This runbook is for the native Kodi-backed IEC audio path in Nexio. Use it when:

* video looks choppy or slow even though buffering is not happening
* audio passthrough opens but no sound is heard
* image quality looks degraded while IEC playback is active
* playback drifts out of sync
* audio is present but stutters, or video renders like a low-FPS slideshow

The goal is to separate these failure classes:

1. Native sink transport issue
2. AudioTrack write stall / reopen loop
3. Bad position or delay accounting
4. Decoder/renderer starvation
5. Surface / display timing issue
6. Wrong track / wrong route / muted output

## 1. Capture the core logs first

Use these while reproducing the issue:

```Shell
adb logcat -c
adb logcat -v time -s KodiNativeSink MediaCodecAudioRenderer AudioTrack AudioFlinger ACodec CCodec BufferQueueProducer SurfaceFlinger
```

What to look for:

* `KodiNativeSink configure`
  * Confirms the sink mode, codec family, output encoding, channel config, and buffer size.
* `KodiNativeSink handleBuffer queue`
  * Confirms Media3 is feeding the native sink.
* `KodiNativeSink engine emit`
  * Confirms the native parser/packer is producing output packets.
* `KodiNativeSink track write`
  * Confirms packets are reaching Android `AudioTrack`.
* `KodiNativeSink track write stalled`
  * Indicates writes are accepted slowly or not at all.
* `KodiNativeSink position`
  * Shows the sink’s reported media time and delay.
* `AudioTrack` / `AudioFlinger` warnings
  * Usually route, format, write, or underrun problems.
* `MediaCodecAudioRenderer`
  * Useful for identifying decoder starvation or repeated discontinuities.

Interpretation:

* `configure` appears, but no `handleBuffer queue`:
  * Renderer is not feeding the sink. This is upstream of the native sink.
* `handleBuffer queue` appears, but no `engine emit`:
  * Parser / packer path is failing to produce packets.
* `engine emit` appears, but no `track write`:
  * Native session is not draining packets into transport.
* `track write stalled` repeats:
  * AudioTrack transport stall or route/format mismatch.
* `track write` is healthy, but `position` barely moves:
  * Playback head or delay accounting problem.
* `position` moves quickly, but audio is silent:
  * Wrong route, muted device, unsupported passthrough payload, or AVR rejection.

## 2. Check whether Android thinks audio is really playing

```Shell
adb shell dumpsys media.audio_flinger
adb shell dumpsys media.audio_policy
```

What to look for in `audio_flinger`:

* active tracks
* sample rate
* channel mask
* format / encoding
* output thread state
* standby vs active
* underrun counters

Interpretation:

* No active track while Nexio is playing:
  * Sink never opened or AudioTrack immediately died.
* Active track exists with IEC/encoded format:
  * Passthrough transport opened.
* Active track exists with PCM format:
  * Playback fell back to PCM, or the stream is decoded before sink.
* Underruns increasing:
  * Writer is not feeding audio fast enough, or transport is reopening/stalling.
* Track is active but output device is wrong:
  * Route selection issue, often TV speakers vs HDMI AVR.

What to look for in `audio_policy`:

* selected output device
* HDMI vs speaker route
* encoded format support
* direct output route decisions

Interpretation:

* HDMI route is missing:
  * AVR/TV route is not active.
* Route is active but format/profile mismatch appears:
  * Android accepted a config that the actual receiver path is not honoring correctly.

## 3. Check the sink position vs actual write cadence

Filter just the native sink tag:

```Shell
adb logcat -v time -s KodiNativeSink
```

Important lines:

* `configure`
* `handleBuffer queue`
* `drain packet`
* `track write`
* `track write stalled`
* `track position`
* `track drain complete`
* `queuePauseBurst`

How to interpret timing:

* Healthy:
  * `handleBuffer queue` and `track write` continue steadily.
  * `track position` climbs smoothly.
  * `delayBeforeUs` and `delayAfterUs` stay reasonable and do not explode upward.
* AudioTrack blocked:
  * many `track write stalled`
  * few or no successful `track write`
  * `track position` flatlines
* Drift from bad accounting:
  * `track position` increases even while writes stall
  * or `track position` advances much faster than actual audible playback
* Transport backlog:
  * `engine emit` / `drain packet` continue, but `track write` falls behind
  * indicates queueing inside native session or transport write contention

Red flags:

* `delayAfterUs` constantly grows
  * sink is accumulating queued audio faster than it drains
* `position` jumps in large chunks
  * timestamp / playback head instability
* repeated `queuePauseBurst` during normal playback
  * pause keep-alive path is firing at the wrong time

## 4. Check if video is actually decoder-bound instead of sink-bound

```Shell
adb logcat -v time -s MediaCodecAudioRenderer MediaCodecVideoRenderer ACodec CCodec
```

What to look for:

* dropped frames
* codec dequeue timeouts
* decoder reinitialization
* audio renderer discontinuities
* video renderer behind / late rendering

Interpretation:

* Video decoder errors or repeated late-frame logs:
  * choppy image is probably decoder/render pipeline, not the IEC sink.
* Audio renderer discontinuity spam:
  * audio clock is unstable, often causing visible video judder.
* Audio renderer waits while video looks slow:
  * sink clock may be dominating playback incorrectly.

## 5. Check UI / Surface timing for the “10 FPS movie” symptom

```Shell
adb shell dumpsys SurfaceFlinger --latency
adb shell dumpsys gfxinfo com.nexio.tv framestats
```

If the device build rejects `--latency`, still run:

```Shell
adb shell dumpsys SurfaceFlinger
adb shell dumpsys gfxinfo com.nexio.tv
```

What to look for:

* long frame times
* janky UI frame stats
* compositor backlog
* display mode mismatch

Interpretation:

* UI frame stats are bad while audio transport is healthy:
  * the issue is UI/composition/render timing, not no-audio root cause.
* UI frame stats are normal, but playback still looks slow:
  * more likely clocking or renderer pacing.

## 6. Check route, volume, and mute state

No-audio with apparently healthy writes often comes from route or volume state.

```Shell
adb shell dumpsys audio
adb shell media volume --show
```

What to look for:

* muted stream
* very low media volume
* wrong active output device
* HDMI device absent or suspended

Interpretation:

* Healthy `track write` logs plus muted output:
  * not a packer problem
* Healthy writes plus wrong device route:
  * transport is working, route is wrong

## 7. Check track selection and format identity

If all formats “play” but all produce no sound, confirm the app picked the expected audio track.

```Shell
adb logcat -v time | grep -i "audio/"
adb logcat -v time | grep -i "MimeTypes"
```

Also inspect the app’s own track-selection logs if present.

What to confirm:

* `audio/true-hd`
* `audio/vnd.dts`
* `audio/vnd.dts.hd`
* `audio/eac3`
* `audio/ac3`

Interpretation:

* Wrong MIME or fallback MIME:
  * extractor / selection issue
* Correct MIME but wrong output encoding in `KodiNativeSink configure`:
  * native decision or route capability issue

## 8. One-shot bugreport capture for deep failures

If the problem is hard to catch live:

```Shell
adb bugreport bugreport-nexio.zip
```

Useful when:

* route changes unexpectedly
* no-audio only happens after resume
* device enters a slow degraded state after several minutes

## 9. Symptom-to-root-cause quick map

### Symptom: No audio, video mostly plays

Most likely:

* wrong route
* AVR rejected the payload
* AudioTrack accepted config but hardware path is silent
* writes stall before audible output

Confirm with:

```Shell
adb logcat -v time -s KodiNativeSink AudioTrack AudioFlinger
adb shell dumpsys media.audio_flinger
adb shell dumpsys media.audio_policy
adb shell dumpsys audio
```

Strong indicators:

* `track write` healthy but no audible output:
  * route or AVR acceptance issue
* no active audio track in `audio_flinger`:
  * sink open/write failure

### Symptom: Video looks like 10 FPS, no buffering spinner

Most likely:

* unstable audio clock
* repeated sink stalls / reopen
* renderer paced by bad audio position
* display/compositor jank

Confirm with:

```Shell
adb logcat -v time -s KodiNativeSink MediaCodecAudioRenderer MediaCodecVideoRenderer
adb shell dumpsys gfxinfo com.nexio.tv framestats
adb shell dumpsys SurfaceFlinger
```

Strong indicators:

* `track position` inconsistent or too fast:
  * sink clock issue
* `track write stalled` loops:
  * transport blocking playback
* video renderer late-frame logs:
  * renderer pacing issue downstream of clock drift

### Symptom: Good startup, then quick A/V drift

Most likely:

* delay accounting error
* playback head mismatch
* pause-burst accounting leak
* sink writes are bursty, not smooth

Confirm with:

```Shell
adb logcat -v time -s KodiNativeSink
```

Strong indicators:

* `position` rises faster than `track write` cadence suggests
* `delayAfterUs` trends upward without recovering
* repeated synthetic pause activity during active playback

### Symptom: Every format has degraded image quality when IEC is enabled

Most likely:

* audio sink path is controlling playback pace badly
* renderer blocks on sink writes
* route change or direct output path causes device-wide scheduling pressure

Confirm with:

```Shell
adb logcat -v time -s KodiNativeSink MediaCodecAudioRenderer MediaCodecVideoRenderer
adb shell dumpsys media.audio_flinger
adb shell dumpsys gfxinfo com.nexio.tv framestats
```

Strong indicators:

* all formats show write stalls:
  * transport-wide issue
* all formats show healthy writes but bad frame stats:
  * UI/render path problem

## 10. Recommended capture sequence

Use this exact order on a failing stream:

1. Clear logs

```Shell
adb logcat -c
```

1. Start focused live logging

```Shell
adb logcat -v time -s KodiNativeSink MediaCodecAudioRenderer AudioTrack AudioFlinger
```

1. In another terminal, capture system state after playback has visibly failed

```Shell
adb shell dumpsys media.audio_flinger > /sdcard/audio_flinger.txt
adb shell dumpsys media.audio_policy > /sdcard/audio_policy.txt
adb shell dumpsys gfxinfo com.nexio.tv framestats > /sdcard/gfxinfo.txt
adb shell dumpsys SurfaceFlinger > /sdcard/surfaceflinger.txt
adb shell dumpsys audio > /sdcard/audio.txt
adb pull /sdcard/audio_flinger.txt
adb pull /sdcard/audio_policy.txt
adb pull /sdcard/gfxinfo.txt
adb pull /sdcard/surfaceflinger.txt
adb pull /sdcard/audio.txt
```

1. If still ambiguous, collect a bugreport

```Shell
adb bugreport bugreport-nexio.zip
```

## 11. What “good” looks like

Healthy native sink playback usually looks like:

* one `configure`
* steady `handleBuffer queue`
* steady `drain packet`
* steady `track write`
* `track position` increasing smoothly
* no repeated `track write stalled`
* no repeated reopen-like recovery
* `audio_flinger` shows an active output track with no growing underruns

If that is true and audio is still silent, the problem is likely outside the packer:

* route
* device policy
* AVR support
* volume/mute
* display/render pipeline

