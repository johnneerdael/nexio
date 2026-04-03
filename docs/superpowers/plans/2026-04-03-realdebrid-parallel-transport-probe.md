# Real-Debrid Parallel Transport Probe Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a local Python CLI that authenticates with Real-Debrid from `.env`, resolves a large direct URL the way Nexio does, runs a controlled parallel range transfer with an in-order consumer model, and records enough worker-side and consumer-side telemetry to explain exactly what stalled and why.

**Architecture:** Implement a standalone probe under `tools/rd_probe/` rather than extending the Android benchmark codepath. The probe will include a Real-Debrid client, candidate resolver, range scheduler, worker execution layer, in-order assembler, telemetry output pipeline, and optional packet-capture integration.

**Tech Stack:** Python 3, `requests` or `httpx`, `python-dotenv`, JSONL/CSV output, optional `tcpdump`, existing Nexio Real-Debrid API behavior as a reference.

---

## File Structure

- Create: `tools/rd_probe/README.md`
  Responsibility: Usage, `.env` requirements, examples, and troubleshooting notes.
- Create: `tools/rd_probe/requirements.txt`
  Responsibility: Probe dependencies only.
- Create: `tools/rd_probe/.env.example`
  Responsibility: Real-Debrid token variable names and optional runtime settings.
- Create: `tools/rd_probe/rd_probe/__main__.py`
  Responsibility: CLI argument parsing and command dispatch.
- Create: `tools/rd_probe/rd_probe/config.py`
  Responsibility: `.env` loading, runtime config, output-dir setup.
- Create: `tools/rd_probe/rd_probe/rd_api.py`
  Responsibility: Real-Debrid HTTP client for listing candidates and unrestricting links.
- Create: `tools/rd_probe/rd_probe/candidate_resolver.py`
  Responsibility: Automatic Nexio-like large-file selection plus explicit id overrides.
- Create: `tools/rd_probe/rd_probe/range_scheduler.py`
  Responsibility: Range/chunk assignment for parallel workers.
- Create: `tools/rd_probe/rd_probe/worker.py`
  Responsibility: Worker request lifecycle, retries, byte counters, and emitted events.
- Create: `tools/rd_probe/rd_probe/assembler.py`
  Responsibility: In-order consumer model, head-of-line blocking detection, and consumer telemetry.
- Create: `tools/rd_probe/rd_probe/telemetry.py`
  Responsibility: Session metadata, JSONL event writers, CSV ranges, and summary generation.
- Create: `tools/rd_probe/rd_probe/pcap.py`
  Responsibility: Optional `tcpdump` integration.
- Create: `tools/rd_probe/rd_probe/analyze.py`
  Responsibility: Post-run summary analysis.
- Create: `tools/rd_probe/tests/test_config.py`
  Responsibility: `.env` loading and CLI configuration behavior.
- Create: `tools/rd_probe/tests/test_candidate_resolver.py`
  Responsibility: Candidate selection and override behavior.
- Create: `tools/rd_probe/tests/test_range_scheduler.py`
  Responsibility: Range assignment and chunk ordering.
- Create: `tools/rd_probe/tests/test_assembler.py`
  Responsibility: Consumer blocking detection and gap calculations.
- Create: `tools/rd_probe/tests/test_summary.py`
  Responsibility: Summary classification for isolated-vs-global stall scenarios.
- Create: `tools/rd_probe/tests/test_pcap.py`
  Responsibility: Packet-capture command construction and lifecycle behavior.

## Task 1: Scaffold probe package and configuration layer

**Files:**
- Create: `tools/rd_probe/requirements.txt`
- Create: `tools/rd_probe/.env.example`
- Create: `tools/rd_probe/rd_probe/__main__.py`
- Create: `tools/rd_probe/rd_probe/config.py`
- Create: `tools/rd_probe/README.md`

- [ ] **Step 1: Write the failing config/CLI tests**

```python
def test_loads_realdebrid_token_from_env(tmp_path):
    env = tmp_path / ".env"
    env.write_text("REALDEBRID_API_TOKEN=abc\n")

    config = load_config(env_file=env)

    assert config.realdebrid_api_token == "abc"
```

- [ ] **Step 2: Run the targeted tests to verify they fail**

Run: `cd tools/rd_probe && pytest tests/test_config.py -q`
Expected: FAIL because the probe package/config loader does not exist yet.

- [ ] **Step 3: Implement the minimal probe scaffold**

- [ ] **Step 4: Run the targeted tests to verify they pass**

Run: `cd tools/rd_probe && pytest tests/test_config.py -q`
Expected: PASS

## Task 2: Build the Real-Debrid client and candidate resolver

**Files:**
- Create: `tools/rd_probe/rd_probe/rd_api.py`
- Create: `tools/rd_probe/rd_probe/candidate_resolver.py`
- Create: `tools/rd_probe/tests/test_candidate_resolver.py`

- [ ] **Step 1: Write the failing candidate resolver tests**

```python
def test_automatic_selection_prefers_large_playable_item():
    client = FakeRdClient(...)
    resolver = CandidateResolver(client)

    result = resolver.resolve_large_candidate()

    assert result.filename.endswith(".mkv")
    assert result.size_bytes > 10 * 1024**3
```

- [ ] **Step 2: Run the targeted tests to verify they fail**

Run: `cd tools/rd_probe && pytest tests/test_candidate_resolver.py -q`
Expected: FAIL because the RD client/resolver does not exist yet.

- [ ] **Step 3: Implement token-authenticated RD access and automatic/override resolution**

Automatic selection must be deterministic: choose the largest playable file with known size, breaking ties by most recent listing time. Supported overrides for phase 1 are `--download-id`, `--torrent-id`, and debug-only `--direct-url`.

- [ ] **Step 4: Run the targeted tests to verify they pass**

Run: `cd tools/rd_probe && pytest tests/test_candidate_resolver.py -q`
Expected: PASS

## Task 3: Build the parallel range scheduler and assembler model

**Files:**
- Create: `tools/rd_probe/rd_probe/range_scheduler.py`
- Create: `tools/rd_probe/rd_probe/assembler.py`
- Create: `tools/rd_probe/tests/test_range_scheduler.py`
- Create: `tools/rd_probe/tests/test_assembler.py`

- [ ] **Step 1: Write the failing scheduler/assembler tests**

```python
def test_scheduler_assigns_expected_ranges_for_parallel_workers():
    scheduler = RangeScheduler(file_size=1024, chunk_size=128, parallelism=4)
    assigned = [scheduler.next_range() for _ in range(4)]
    assert assigned == [(0,127), (128,255), (256,383), (384,511)]


def test_assembler_blocks_on_missing_leading_chunk_even_if_later_chunks_finish():
    assembler = InOrderAssembler(chunk_size=128)
    assembler.mark_chunk_complete(index=1, start=128, end=255)
    assert assembler.consumer_bytes_available == 0
    assert assembler.blocked_on_chunk == 0
```

- [ ] **Step 2: Run the targeted tests to verify they fail**

Run: `cd tools/rd_probe && pytest tests/test_range_scheduler.py tests/test_assembler.py -q`
Expected: FAIL because the scheduler/assembler do not exist yet.

- [ ] **Step 3: Implement the range scheduler and in-order consumer model**

- [ ] **Step 4: Run the targeted tests to verify they pass**

Run: `cd tools/rd_probe && pytest tests/test_range_scheduler.py tests/test_assembler.py -q`
Expected: PASS

## Task 4: Implement worker execution and telemetry output

**Files:**
- Create: `tools/rd_probe/rd_probe/worker.py`
- Create: `tools/rd_probe/rd_probe/telemetry.py`
- Create: `tools/rd_probe/tests/test_summary.py`

- [ ] **Step 1: Write the failing worker/summary tests**

```python
def test_summary_classifies_head_of_line_block_when_only_one_required_chunk_stalls():
    summary = classify_session(...)
    assert summary["likely_mode"] == "head_of_line_block"
```

- [ ] **Step 2: Run the targeted tests to verify they fail**

Run: `cd tools/rd_probe && pytest tests/test_summary.py -q`
Expected: FAIL because the worker telemetry/summary layer does not exist yet.

- [ ] **Step 3: Implement worker lifecycle events and summary classification**

- [ ] **Step 4: Run the targeted tests to verify they pass**

Run: `cd tools/rd_probe && pytest tests/test_summary.py -q`
Expected: PASS

## Task 5: Add optional integrated packet capture

**Files:**
- Create: `tools/rd_probe/rd_probe/pcap.py`
- Modify: `tools/rd_probe/rd_probe/__main__.py`
- Modify: `tools/rd_probe/README.md`

- [ ] **Step 1: Write the failing pcap integration tests**

```python
def test_pcap_controller_builds_tcpdump_command_for_host_filter():
    cmd = build_tcpdump_command(output="capture.pcap", host="example.com")
    assert "tcpdump" in cmd[0]
```

- [ ] **Step 2: Run the targeted tests to verify they fail**

Run: `cd tools/rd_probe && pytest tests/test_pcap.py -q`
Expected: FAIL because the pcap helper does not exist yet.

- [ ] **Step 3: Implement non-blocking optional pcap orchestration**

The pcap controller must stop capture on normal completion, cancellation, and early failure so capture cleanup is testable and reliable.

- [ ] **Step 4: Run the targeted tests to verify they pass**

Run: `cd tools/rd_probe && pytest tests/test_pcap.py -q`
Expected: PASS

## Task 6: Wire end-to-end CLI run path and verify the probe suite

**Files:**
- Modify: `tools/rd_probe/rd_probe/__main__.py`
- Modify: `tools/rd_probe/README.md`
- Update any missing tests/docs from earlier tasks

- [ ] **Step 1: Run the full probe test suite**

Run: `cd tools/rd_probe && pytest -q`
Expected: PASS

- [ ] **Step 2: Run a smoke invocation without network execution**

Run: `cd tools/rd_probe && python -m rd_probe --help`
Expected: exit 0 with CLI help text

- [ ] **Step 3: Document first real-run usage**

Include an example like:

```bash
cd tools/rd_probe
cp .env.example .env
# fill REALDEBRID_API_TOKEN
python -m rd_probe run --parallel 4 --chunk-mb 16 --duration 120 --enable-pcap
```

- [ ] **Step 4: Commit the completed planning/execution slice when implementation is done**
