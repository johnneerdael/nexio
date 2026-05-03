## 1. Trace Session Integrity
- [ ] 1.1 Add active trace session id propagation to runtime sinks.
- [ ] 1.2 Verify `DefaultIntegrationRuntime` file traces no longer use `traceSessionId = "noop"` while a session is active.

## 2. Logcat Cache-Proof Fields
- [ ] 2.1 Add `runtimeOperationId`, `apiShapeId`, `reason`, `networkSuppressed`, `ttlMs`, and `staleWindowMs` to `Nexio.IntRuntime` cache-decision lines.
- [ ] 2.2 Add logcat sink tests for fresh hit, miss, stale hit, and write fields.

## 3. Validator And Summary
- [ ] 3.1 Add stale-hit-with-network-suppressed validation.
- [ ] 3.2 Add per-operation cache proof summary output.
- [ ] 3.3 Add tests proving fresh/stale suppressed cache hits fail if an HTTP request is emitted for the same operation id.

## 4. Local Device Proof
- [ ] 4.1 Add a local trace-cache proof script for `trace-events.jsonl`.
- [ ] 4.2 Add debugging docs with ADB/logcat and rooted trace-pull commands.

## 5. Verification
- [ ] 5.1 Run focused trace tests.
- [ ] 5.2 Run OpenSpec strict validation.
- [ ] 5.3 Capture one Kitsu second-open trace and verify no unexpired Kitsu metadata operation reports `MISS_THEN_NETWORK`.
