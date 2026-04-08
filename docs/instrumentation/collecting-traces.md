# Collecting Playback Diagnostics Traces

A step-by-step guide for capturing a playback-trace JSONL from a Nexio
install on Fire TV / Google TV / Chromecast and sending it to support.

There are **two** ways to drive the trace — the in-app UI (canonical,
no tools required) and an optional ADB control plane (for QA automation
or if the TV remote is hard to navigate). Both go through the same
underlying controller and produce the same JSONL files; the ADB path is
a thin wrapper, not a separate session model.

---

## TL;DR — the in-app path

1. Install Nexio **v0.41 or newer** (the diagnostics section is gated
   behind `PLAYBACK_TRACE_UI_ENABLED`, only shipped starting that version).
2. Go to **Debrid Integration** in settings and scroll to the
   **"Playback diagnostics trace"** tile.
3. Flip the master switch **on**.
4. Play a stream that reproduces the problem (e.g. a title that stutters
   during autoplay).
5. Come back to the same screen, tap **Export last**, and share the
   `.jsonl` file to yourself — email, Nearby Share, Google Drive, or any
   installed share target.
6. Send the file to support along with the device model and Nexio
   version.

That's it. Steps 7+ below are only needed if the share sheet is
inconvenient or if you want to script the capture from a workstation.

---

## What's being recorded

The tracer writes one **JSONL** file per playback session under the
private directory `filesDir/playback-traces/<sessionId>.jsonl`. Every
line is one event from one of 12 event families (spec §B):

| Family | Contents |
| --- | --- |
| `SESSION` | `playback_session_started` header + `playback_session_ended` summary |
| `POLICY` | Applied runtime policy + specialization-provenance |
| `BRANCH` | Which data-source branch the factory picked (`prds` / `okHttp` / `base`) |
| `PRDS` | Parallel range data source open/read/close lifecycle |
| `RANGE` | Per-range scheduler events + per-call OkHttp network timing |
| `FRONTIER` | Byte-store fill progress and monitor-wait histograms |
| `READ_WAIT` | Media3 `read()` wait times by reason |
| `CACHE` | VOD cache hit/miss + warm-ahead activity |
| `REBUFFER` | `rebuffer_start` / `rebuffer_end` with 5-second snapshot |
| `DECODE` | Decoder errors, dropped frames, audio underruns |
| `DEVICE` | 1 Hz memory snapshot, thermal status, low-memory |
| `TRACER` | Self-reporting — overflow counts + rotation events |

Every event shares the same `sessionId` and a monotonic `tNs` timestamp
so offline analysis tools can correlate a stutter across all families.

**Privacy**: stream URLs are SHA-256 hashed (first 12 hex chars) before
logging. Query strings and addon API keys never enter the trace. Nothing
that can deanonymise your account is written to the file.

**Overhead**: with the master toggle off, every tracer call path
compiles to a single volatile-read + early return. With the toggle on,
the hot path is a lock-free MPSC ring enqueue; there is no disk I/O on
the producer side.

---

## Path A — In-app UI (canonical)

### Turn the tracer on

1. Open **Settings → Debrid Integration**.
2. Scroll to the **Playback diagnostics trace** tile (below the debrid
   provider sections).
3. Toggle the master **Switch** on. The status line immediately updates
   to `Tracer is enabled · 0 sessions`.
4. Play the stream that reproduces the problem.
5. Exit playback back to the home screen (or leave the app running —
   the session is written continuously).

> **Toggle-off behavior**: turning the switch off *mid-session* flushes
> the current session with a `playback_session_ended` event and closes
> the file cleanly. Turning it back on during playback is a no-op until
> the next `createMediaSource()` call (for example when you start a new
> title or seek past the end) — a new session is opened from there.

### Export a trace

The tile has three export buttons and one clear button:

- **Export last** — FileProvider + `ACTION_SEND` share sheet for the
  most recently-written JSONL. Pick any installed share target. Works
  on TVs without a keyboard (up / down / select only).
- **Export all (.zip)** — bundles every retained session (up to 20)
  into a single zip under `cacheDir/playback-trace-exports/` and shares
  it the same way. Use this if support asks for "everything you've got".
- **Copy to…** — launches the SAF (`ACTION_CREATE_DOCUMENT`) document
  picker, letting you write the latest JSONL to any location the
  document picker exposes (Downloads, external USB, Google Drive, etc).
  This path is useful on Fire TV where the share sheet is sparse.
- **Clear traces** — deletes every `.jsonl` under `playback-traces/`.
  Use this if you want to guarantee a clean capture before reproducing
  a new problem.

### Read the status line

The status line below the master switch shows:

```
Tracer is enabled · 3 sessions · 4.2 MiB total
Latest: f9a8c1b1-…-29f0.jsonl (1250 KiB)
```

- **`N sessions`** — distinct session ids (rotated parts of one long
  session count as one session).
- **`X.X MiB total`** — total disk use under `playback-traces/`.
- **`Latest: <file> (N KiB)`** — filename and size of the most recent
  session. This is what **Export last** will share.

---

## Path B — ADB control plane (optional, gated)

The ADB path exists so QA automation, repro scripts, and unattended
capture rigs can drive the same controller without navigating the TV
settings UI every time. It is **secondary**, **thin**, and **guarded** —
production users should continue to use the in-app UI.

### First-time setup

The ADB control receiver is **off by default**. You have to explicitly
opt in from the debrid settings menu before any `am broadcast` command
does anything:

1. Open **Settings → Debrid Integration → Playback diagnostics trace**.
2. Find the small **"Allow ADB control"** switch in the row below the
   export buttons.
3. Flip it on.

Until that switch is on, the receiver silently drops every incoming
broadcast and logs a rejection at `PlaybackTraceAdb` tag. This gives
you time to review what ADB control would do on your device before
exposing it.

> **Why opt-in**: leaving a permanently exported control surface open
> in production would let any installed app on the same device send
> the same broadcasts. Keeping the receiver behind an explicit runtime
> gate means the surface is closed by default, and you can close it
> again at any time by flipping the switch off in the same menu.

### Command reference

All commands target the `PlaybackTraceAdbReceiver` in the Nexio
application package. The `-n` target is required so the broadcast is
delivered to the receiver explicitly rather than matching any exported
receiver on the device.

```sh
# 1. Enable the tracer.
adb shell am broadcast \
  -a com.nexio.tv.action.PLAYBACK_TRACE_ENABLE \
  -n com.nexio.tv/com.nexio.tv.instrumentation.PlaybackTraceAdbReceiver

# 2. (... play a stream from the remote, live, or via your automation ...)

# 3. Read the current status (count, bytes, latest file).
adb shell am broadcast \
  -a com.nexio.tv.action.PLAYBACK_TRACE_STATUS \
  -n com.nexio.tv/com.nexio.tv.instrumentation.PlaybackTraceAdbReceiver

# 4. Share the latest session via the system share sheet.
adb shell am broadcast \
  -a com.nexio.tv.action.PLAYBACK_TRACE_EXPORT_LAST \
  -n com.nexio.tv/com.nexio.tv.instrumentation.PlaybackTraceAdbReceiver

# 5. Share a zip of every retained session.
adb shell am broadcast \
  -a com.nexio.tv.action.PLAYBACK_TRACE_EXPORT_ALL \
  -n com.nexio.tv/com.nexio.tv.instrumentation.PlaybackTraceAdbReceiver

# 6. Turn the tracer off. Any open session is flushed and closed.
adb shell am broadcast \
  -a com.nexio.tv.action.PLAYBACK_TRACE_DISABLE \
  -n com.nexio.tv/com.nexio.tv.instrumentation.PlaybackTraceAdbReceiver

# 7. Delete every retained JSONL.
adb shell am broadcast \
  -a com.nexio.tv.action.PLAYBACK_TRACE_CLEAR \
  -n com.nexio.tv/com.nexio.tv.instrumentation.PlaybackTraceAdbReceiver
```

### Reading ADB results

Every accepted command logs a line at the `PlaybackTraceAdb` tag. Tail
logcat while you drive the commands:

```sh
adb logcat -s PlaybackTraceAdb:I
```

Example output after an enable + status + disable cycle:

```
I PlaybackTraceAdb: ADB: tracer enabled
I PlaybackTraceAdb: ADB: status enabled=true sessions=2 bytes=1258291 last=f9a8...-29f0.jsonl
I PlaybackTraceAdb: ADB: tracer disabled
```

If you see `Rejected <action> — ADB control is disabled (opt-in from
debrid settings)`, the **Allow ADB control** switch is still off.

### Pulling a file off the device

The ADB path does not move bytes over adb directly — the export
commands run the same system share sheet the in-app UI uses. If you
want a workstation-side pull without going through the share sheet:

```sh
# List available sessions.
adb shell 'run-as com.nexio.tv ls -la files/playback-traces/'

# Copy the most recent one out.
adb shell 'run-as com.nexio.tv cat files/playback-traces/<sessionId>.jsonl' \
  > playback-trace.jsonl

# Or, with cooperation from the device, dump the whole directory as a tar.
adb exec-out run-as com.nexio.tv tar c files/playback-traces \
  > playback-traces.tar
```

`run-as` is the standard Android shim for reading private app data on
debuggable installs. For release builds it requires the device to be
developer-mode'd, and the user must have "USB debugging" on.

---

## Rotating + retention

Every session writes up to 8 MiB of JSONL. Longer sessions split into
`<sessionId>-1.jsonl`, `<sessionId>-2.jsonl`, and so on. The retention
policy keeps the **20 most recent distinct sessions** — rotated parts
of one session count as one session, so a long binge never evicts the
older parts of itself.

The storage budget is bounded at **~160 MiB** in the worst case
(20 × 8 MiB), and in practice is much lower since most sessions close
at a few hundred KB.

---

## What to send to support

Minimum viable bug report:

1. **Nexio version** — `Settings → About → Version`.
2. **Device model** — e.g. "Fire TV 4K Max (AFTKA)".
3. **The JSONL for the bad session** — exported via **Export last** or
   pulled via `adb shell run-as`.
4. **A short description of the problem** — e.g. "stuttered for ~5
   seconds around 12:30 into the movie, on a stream that usually plays
   fine".

Optional but helpful:

5. **`Export all (.zip)`** if the problem is intermittent and you want
   to give support multiple recent sessions to compare against.
6. **Perfetto trace** if support asks — the v1 tracer writes atrace
   markers for `FRONTIER`, `RANGE`, and `REBUFFER` families whenever
   systrace is active, so a system trace captured with
   `adb shell perfetto` will deobfuscate cleanly against the JSONL on
   the same `sessionId` + monotonic clock.

---

## Troubleshooting

**"I don't see the Playback diagnostics section"**
— You're on a build older than v0.41 where the section is
compile-time elided. Update to the latest release.

**"I turned the switch on but no files appear"**
— Sessions are only written while a player is active. Play something
first, then come back to the settings screen. The status line updates
each time you open the tile.

**"Export last does nothing"**
— There has to be at least one complete session on disk. If you
toggled off *before* any playback started, `filesDir/playback-traces/`
is empty. Toggle on, play a stream for at least a few seconds, then
try again.

**"adb am broadcast hangs"**
— The `am broadcast` call itself returns immediately; the command that
hangs is usually `adb logcat` if you forgot to filter the tag. Use
`adb logcat -s PlaybackTraceAdb:I` to scope the output.

**"The device says 'Rejected — ADB control is disabled'"**
— The runtime gate in the debrid settings menu is off. Flip **Allow
ADB control** on and retry. This is the by-design security posture:
the receiver has to be explicitly armed before it does anything.

**"I don't see a share target on Fire TV"**
— Fire TV's share sheet is sparse. Use **Copy to…** instead — the SAF
document picker exposes Downloads, USB storage, and cloud providers.
On most Fire TV units, Amazon Downloader + a USB stick is the most
reliable way to get the file off-device.

---

## Design notes (for developers)

The in-app UI and the ADB receiver are both thin adapters over
[`PlaybackTraceController`](../../app/src/main/java/com/nexio/tv/instrumentation/PlaybackTraceController.kt).
Adding a third control surface (for example a notification action, a
MediaSession custom command, or an IntentService) means implementing
one more adapter, not reimplementing the controller. The controller
owns the DataStore toggle, the `filesDir` layout, the FileProvider
wiring, and the `clearAll` / `refreshStatus` lifecycle.

The spec that drives the design lives at
[`.omc/specs/deep-interview-playback-instrumentation.md`](../../.omc/specs/deep-interview-playback-instrumentation.md).
The classifier that consumes the JSONL for offline analysis is
[`StutterClassifier.kt`](../../app/src/main/java/com/nexio/tv/instrumentation/StutterClassifier.kt).
