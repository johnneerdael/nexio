---
plan: docs/superpowers/plans/2026-04-13-disk-spool-ram-read-buffer.md
reviewers: [claude]
reviewed_at: 2026-04-13
---

# Cross-AI Plan Review — Disk Spool RAM Read Buffer

## Claude Review

### Summary

This is a well-structured, TDD-driven plan that correctly maintains the `network -> disk spool -> RAM buffer -> ExoPlayer` pipeline without introducing a second network path. The task decomposition follows the existing codebase patterns closely, and the code snippets are largely accurate. However, there are several medium-severity issues around the `DiskSpoolReadAheadBuffer` interaction with `DiskSpoolDataSource.read()` sequential position tracking, a missing edge case in the heap cap function, and a test that may not actually validate what it claims. The plan is executable with targeted fixes.

### Strengths

- Correct architecture: the buffer sits between `DiskSpoolDataSource.read()` and `session.read()`, reading exclusively from the disk file via `RandomAccessFile`. The network constraint is structurally enforced.
- Follows existing patterns: settings persistence, ViewModel threading, UI slider, and runtime controller wiring mirror the established `diskSpoolStartupBufferMb` pattern.
- TDD workflow: each task writes the failing test first, then implements, then verifies.
- Heap cap design: using `heapLimitBytes / 4` clamped to `[16 MB, 128 MB]` is a reasonable heuristic for Android TV devices that typically have 256-512 MB heaps.
- Clean separation: `DiskSpoolReadAheadBuffer` is its own class with a simple `read/reset` API, making it testable in isolation.
- Self-review section: naming consistency check and placeholder scan show discipline.

### Concerns

**HIGH — Sequential read position tracking conflict**

The plan inserts the buffer read before `session.read()` in `DiskSpoolDataSource.read()`. `DiskSpoolDataSource` tracks `position` internally and advances it after each read. The happy path works: request position 0, fill bytes 0-7, return bytes 0-3, next read at position 4 returns cached bytes 4-7. But the plan should explicitly state that partial buffered reads return immediately and rely on the caller to request more, which is valid for `DataSource.read()`.

**HIGH — Buffer refill can block on `session.read()` wait**

`DiskSpoolSession.read()` blocks up to `waitTimeoutMs` when data has not reached that position. The buffer calls `session.read(position, buffer, 0, buffer.size)` and may request a large capacity such as 64 MB even when the caller needs a small read. The session should return a partial positive count when data is available, but the plan should document that fills are opportunistic and not mandatory.

**MEDIUM — Heap cap can over-allocate on very low heaps**

The proposed `effectiveDiskSpoolReadAheadBytes` clamps `heapLimitBytes / 4` up to 16 MB. For a 48 MB heap, that would allocate 16 MB, roughly one third of the heap. Add a guard such as:

```kotlin
if (heapCapBytes > heapLimitBytes / 5) return 0L
```

**MEDIUM — Factory test may not validate heap capping**

The plan’s Task 5 test asserts the captured read-ahead bytes are in a range. On Robolectric, JVM heap may be large enough that the requested 128 MB is not actually capped. Make this deterministic by injecting a heap limit override into `PlayerMediaSourceFactory` for testing and asserting an exact value.

**MEDIUM — Factory constructor instructions are scattered**

The plan mentions adding `ramReadAheadBytes` to `DiskSpoolDataSource.Factory`, but it should show the complete `Factory` constructor and `createDataSource()` change in one code block to avoid implementation drift.

**LOW — Requested max may be too high**

`MAX_DISK_SPOOL_RAM_READ_BUFFER_MB = 512` is misleading if the effective cap is 128 MB. Consider a lower requested max such as 256 MB or showing both requested and effective values in the UI.

**LOW — Missing close cleanup for buffer**

The plan resets the buffer on seek, open, and fallback, but does not release the backing `ByteArray` on `DiskSpoolDataSource.close()`. For a large allocation, add `release()` and call it on close.

**LOW — Buffer constructor should defensively cap allocation**

The proposed constructor caps at `Int.MAX_VALUE`, which could still represent a massive allocation. Even though the factory cap should prevent it, the buffer class should also cap to a sane maximum such as 256 MB.

### Suggestions

- Add a low-heap safety guard to `MemoryBudget.effectiveDiskSpoolReadAheadBytes`.
- Make the factory test deterministic with a `heapLimitBytesForTesting` override.
- Show the effective heap-capped buffer value in the UI, or keep the requested max close to the effective max.
- Add `DiskSpoolReadAheadBuffer.release()` and call it from `DiskSpoolDataSource.close()`.
- Reduce `MAX_DISK_SPOOL_RAM_READ_BUFFER_MB` to 256 MB.
- Document that read-ahead fills are opportunistic: the buffer asks for up to capacity but accepts partial reads from the disk spool session.

### Risk Assessment

Overall risk: **MEDIUM**.

The architecture is sound and the critical constraint, disk-only reads with no new network path, is structurally enforceable. The main risks are over-allocation on constrained devices, non-deterministic tests, and UX confusion around requested versus effective RAM buffer sizes. These are fixable before execution by updating the plan with the heap guard, test override, release behavior, and a lower requested maximum.

## Consensus Summary

Only Claude reviewed this plan. The most important changes to fold back into the plan are:

- Add deterministic heap-cap testing instead of relying on host JVM heap.
- Add a low-heap guard so the minimum cap does not over-allocate.
- Add explicit buffer release behavior on close.
- Make the RAM buffer’s disk-only and opportunistic-fill behavior explicit in implementation notes and tests.
- Keep the requested UI range aligned with the effective heap cap.

## Second Review

### Summary

The second review assessed the original plan as end-to-end thorough but technically unsafe in the core `DiskSpoolReadAheadBuffer` design. The key issue is that the planned buffer was synchronous and contiguous-memory based, so it would not actually smooth disk stalls and could increase OOM risk on Android TV devices.

### Findings

**HIGH — Synchronous execution prevents actual read-ahead**

The proposed `refill()` method ran on the ExoPlayer loader thread. It did not prefill in the background, so it would either buffer only the small amount returned synchronously or block the playback thread while reading a large range.

**HIGH — Contiguous allocation OOM risk**

The proposed `ByteArray(capacity)` design could allocate up to 128 MB as one contiguous array. Android TV heaps are prone to fragmentation, so this is unsafe even if total free heap looks sufficient.

**MEDIUM — Delayed memory reclaim**

The original plan did not release the large buffer explicitly on `DiskSpoolDataSource.close()`, so memory could remain live until GC.

**LOW — Redundant fallback path**

If the buffer returned `0`, falling through to `session.read(...)` could duplicate the same disk read in some end-of-buffer cases.

### Required Plan Changes

- Replace the synchronous buffer with an asynchronous background producer.
- Store memory as fixed-size chunks in a bounded ring, not one large contiguous `ByteArray`.
- Feed the buffer only from `DiskSpoolSession.read(...)`; do not add an OkHttp or network-backed path.
- Explicitly release chunk memory on close.
- Add concurrent behavior tests.
- Prefer a local chunk pool/ring model matching `ParallelRangeDataSource` over a new Media3 allocator dependency unless implementation shows allocator integration is materially simpler.

### Resolution

Created `docs/superpowers/plans/2026-04-13-disk-spool-ram-read-buffer-v2.md`, which supersedes the original plan and incorporates these findings.

## Claude Review Of V2

### Summary

Claude assessed v2 as a sound review-driven rewrite that fixes the two v1 blockers: synchronous refill and one large contiguous allocation. The remaining issues are localized but important: circular spool-window eviction, data-source lifecycle after close/reopen, and test determinism.

### Findings

**HIGH — Circular spool window eviction can corrupt or stall the RAM buffer**

`DiskSpoolSession` is a circular buffer. If the RAM read-ahead worker attempts to read a position that has fallen behind `session.windowStartBytes()`, `DiskSpoolSession.read(...)` returns `-1`. The v2 worker loop would retry without advancing, creating a permanent retry loop. The plan must fast-forward to `session.windowStartBytes()` and clear stale chunks.

**HIGH — Constructor and factory snippets must preserve existing parameters**

`DiskSpoolDataSource` already has `startupPrebufferBytes` and `startupPrebufferWaitTimeoutMs`. The plan must show the full updated factory constructor and `createDataSource()` body when adding `ramReadAheadBytes`.

**MEDIUM — MemoryBudget companion helper is defensible but stylistically different**

`MemoryBudget` is mostly instance-oriented. A pure companion helper is acceptable, but an instance method could be cleaner. The plan keeps the companion helper because it makes deterministic testing straightforward.

**MEDIUM — Worker can retry too aggressively after non-positive reads**

Production `DiskSpoolSession.read(...)` blocks up to its timeout, so this is not a tight spin by default. The plan still needs a short backoff after non-positive reads when the session remains open.

**MEDIUM — Buffer lifecycle after close/reopen**

If `DiskSpoolDataSource` stores the read-ahead buffer as a `val` and calls `release()` on `close()`, a later `open()` on the same data source instance would not restart the buffer. The data source must recreate the buffer on `open()` or support revival.

**LOW — Test should not use `Thread.sleep(125L)`**

Tests should use `awaitBufferedBytesForTesting(...)`, a latch, or an observer to avoid CI race conditions.

**LOW — Add buffer-miss fallback test**

Add a test that verifies `DiskSpoolDataSource` still falls back to direct `session.read(...)` when the RAM buffer has no data ready.

### Resolution

Updated `docs/superpowers/plans/2026-04-13-disk-spool-ram-read-buffer-v2.md` with mandatory corrections:

- generation validation after blocking reads,
- eviction fast-forward to `session.windowStartBytes()`,
- non-positive-read backoff,
- per-worker scratch reuse,
- no sleep-based synchronization in tests,
- and read-ahead buffer recreation/revival across `DiskSpoolDataSource` close/reopen.

## Second External Review Of V2

### Summary

The second v2 review agreed that the architecture is now aligned with the project constraints: asynchronous, chunked, heap-capped, and disk-only. It also independently identified the same blocking stale-data bug after `reset(...)`.

### Findings

**HIGH — Stale data after reset**

The worker captures `startPosition`, blocks inside `session.read(...)`, and then writes data into shared state. If `reset(newPosition)` happens during that blocking read, the worker can overwrite the new sequence with stale data. The plan must validate that the generation and start position are still current after reacquiring the lock.

**MEDIUM — Busy loop on stalls**

If `DiskSpoolSession.read(...)` returns non-positive repeatedly, the worker should not immediately retry without a backoff. The current session blocks in production, but a small backoff is still appropriate.

**LOW — GC pressure from scratch allocation**

Allocate the scratch buffer outside the worker loop and only copy the actually-read bytes when adding a chunk.

**LOW — Partial read across chunk boundaries**

Returning one chunk at a time is valid for `DataSource.read(...)`, but less efficient. It is acceptable for the first implementation, as long as tests cover sequential reads across chunks.

### Resolution

The same mandatory v2 plan corrections address this review.
