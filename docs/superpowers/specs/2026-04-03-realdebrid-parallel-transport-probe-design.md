# Real-Debrid Parallel Transport Probe Design

## Context

Nexio's current Real-Debrid optimized benchmark repeatedly shows strong sustained throughput but also
surfaces periodic multi-second starvation windows on the parallel path. The benchmark currently
proves that the assembled consumer stream stalled, but it does not tell us whether the stall came
from one head-of-line chunk, a multi-worker retry storm, server-side throttling, or a bug in our
local parallel-range scheduling and assembly logic.

We need a local troubleshooting tool with raw transport visibility against the same Real-Debrid path
Nexio uses. This tool is not a user-facing benchmark feature. It is a developer/operator forensic
probe that authenticates with Real-Debrid, resolves a large direct URL the way Nexio does, runs a
controlled parallel range transfer, and records enough worker-side and consumer-side evidence to
explain exactly what stalled and why.

## Goals / Non-Goals

- Goals:
  - Build a local Real-Debrid-only transport investigation CLI.
  - Authenticate via `.env` and resolve a large Real-Debrid candidate the same way Nexio does.
  - Support both automatic large-file selection and explicit item override for reproducible runs.
  - Reproduce Nexio-like parallel downloading with controllable connection-count and chunk-size
    settings.
  - Record per-worker, per-range, and assembled-consumer telemetry that distinguishes head-of-line
    blocking from global worker stalls.
  - Optionally start and stop packet capture for the run and save a `.pcap` alongside structured
    logs.
  - Emit machine-readable artifacts suitable for offline analysis and side-by-side run comparison.
- Non-Goals:
  - Replace the app's production benchmark path.
  - Add Premiumize support in phase 1.
  - Ship a GUI or in-app settings surface.
  - Perfectly mirror every Kotlin implementation detail before the first useful version exists.
  - Automatically change Nexio playback settings based on probe output.

## Decision Summary

- Decision: implement the probe as a Python CLI.
  - Rationale: Python gives the fastest iteration loop for networking experiments, structured
    telemetry, and optional process orchestration such as `tcpdump`.
- Decision: keep provider scope to Real-Debrid only in phase 1.
  - Rationale: Real-Debrid is the provider showing the suspicious parallel behavior, so widening
    provider support now would dilute the investigation.
- Decision: include both automatic candidate resolution and explicit override inputs.
  - Rationale: we need both realistic Nexio-like selection and reproducible targeted reruns.
- Decision: model an in-order assembled consumer, not just independent worker downloads.
  - Rationale: the key unknown is whether a single required chunk blocked consumer progress while
    other workers continued ahead.
- Decision: integrate packet capture as an optional workflow feature.
  - Rationale: the tool should be fully useful in userspace-only mode, but pcap must be available
    when lower-level TCP behavior is needed.

## Recommended Architecture

### CLI Layout

Create a dedicated probe under `tools/rd_probe/` with a small, composable module structure:

- `rd_probe/config.py` — `.env` loading, CLI config, defaults
- `rd_probe/rd_api.py` — Real-Debrid HTTP client
- `rd_probe/candidate_resolver.py` — Nexio-like large-file discovery + item overrides
- `rd_probe/range_scheduler.py` — chunk assignment and worker scheduling
- `rd_probe/worker.py` — per-range worker lifecycle and retries
- `rd_probe/assembler.py` — in-order consumer assembly / head-of-line blocking detection
- `rd_probe/telemetry.py` — JSONL/CSV/session summary output
- `rd_probe/pcap.py` — optional `tcpdump` start/stop handling
- `rd_probe/analyze.py` — post-run summary helpers
- `rd_probe/__main__.py` — CLI entrypoint

### Real-Debrid Resolution Flow

The probe should authenticate with a Real-Debrid token from `.env` and then resolve a direct URL
using Nexio-like candidate logic:

1. fetch candidate library/download items from Real-Debrid
2. filter to playable video files with a known size
3. prefer the largest candidate by size; if sizes tie, prefer the most recently listed item
4. optionally accept explicit overrides via `--download-id`, `--torrent-id`, or debug-only `--direct-url`
5. unrestrict the chosen link and capture:
   - filename
   - source size
   - resolved host
   - direct URL fingerprint

The default path should stay close enough to Nexio's logic to make results comparable: large playable item selection must be deterministic and documented as largest-by-size with newest-item tie-break. The probe must also allow fixed overrides for repeatability.

### Parallel Transport Model

The probe must explicitly control the parallel transfer path rather than relying on a generic
download helper.

Each run should allow:
- connection count
- chunk size MB
- run duration
- optional byte limit
- optional retry policy tuning for experiments

Workers issue HTTP range requests against the same direct URL. The scheduler assigns ranges in the
same general style as Nexio's parallel path: multiple range workers running concurrently with data
becoming consumable only when the next in-order range segment is available.

### Consumer / Assembler Model

The assembler is the most important component.

It should track:
- the next required byte / chunk index
- which chunks are completed but ahead of the consumer
- how long the consumer was blocked waiting on a specific chunk
- consumer-visible bytes delivered over time
- consumer read gaps and assembled throughput buckets

This is what will tell us whether:
- one critical leading chunk blocked the stream while others continued, or
- all workers stalled together.

### Worker Telemetry

Each worker should emit lifecycle events with timestamps:
- worker started
- range assigned
- request opened
- response headers received
- first byte received
- bytes received over time
- inactivity window detected
- request retried
- request completed
- request failed
- request canceled

Key fields to capture:
- worker id
- chunk index or byte range
- response status
- relevant headers (`Content-Range`, `Content-Length`, server hints, etc.)
- bytes read per interval
- retry count
- exception type / message

### Optional Packet Capture

When enabled, the tool should:
- start `tcpdump` before transport begins
- optionally narrow capture to the resolved host
- stop capture at session end, cancellation, or early failure
- save the resulting `.pcap` path in session metadata

The probe should still work normally when packet capture is unavailable or disabled.

## Output Artifacts

Each run should create a timestamped output directory with at least:

- `session.json` — run metadata, candidate info, config, timing
- `workers.jsonl` — per-worker lifecycle events
- `consumer.jsonl` — consumer / assembler events
- `ranges.csv` — assigned and completed ranges
- `summary.json` — rolled-up metrics and probable failure mode
- optional `capture.pcap`

The summary should include:
- average throughput
- p10 / p50 if applicable
- longest consumer read gap
- longest per-worker inactivity window
- whether the evidence suggests:
  - head-of-line blocked on one chunk
  - multi-worker stall
  - likely server-side throttling/reset burst
  - local retry/assembly pathology

## CLI Behavior

Recommended commands:

- `python -m rd_probe run`
- `python -m rd_probe run --download-id ...`
- `python -m rd_probe run --torrent-id ...`
- `python -m rd_probe analyze <run-dir>`

Recommended flags for `run`:
- `--parallel`
- `--chunk-mb`
- `--duration`
- `--byte-limit`
- `--output-dir`
- `--enable-pcap`
- `--tcpdump-filter`
- `--download-id`
- `--torrent-id`
- `--direct-url` (debug-only override, optional)

## Risks / Trade-offs

- Risk: Real-Debrid candidate-resolution parity may not perfectly match Nexio at first.
  - Mitigation: support explicit item overrides and document where the resolver intentionally differs.
- Risk: packet capture may require local privileges and can fail independently of the transport run.
  - Mitigation: keep pcap optional and non-blocking.
- Risk: a Python probe may not match Kotlin runtime behavior perfectly.
  - Mitigation: prioritize scheduler and assembler parity, not language parity.
- Risk: the probe could reveal that the issue is not provider throttling but local head-of-line
  blocking or retry behavior.
  - Mitigation: that is a desired outcome; the tool exists to answer exactly that question.

## Phase 1 Deliverable

A useful first version is one that can:
- authenticate with Real-Debrid from `.env`
- select or override a large file
- run controlled parallel range workers
- model in-order consumer delivery
- emit JSONL transport traces
- optionally collect a `.pcap`
- produce a summary that explains whether the stall looked isolated or global

That is enough to validate the suspicion before we iterate further on parity or visualization.
