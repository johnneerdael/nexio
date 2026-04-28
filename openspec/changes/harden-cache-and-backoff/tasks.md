## 1. Foundation
- [ ] 1.1 OpenSpec scaffold

## 2. F-A-01 — stream-open backoff parity
- [ ] 2.1 Failing test
- [ ] 2.2 Fix openInternal catch branch

## 3. F-D-01 — stale-on-429 fallback
- [ ] 3.1 Failing test
- [ ] 3.2 Fix HttpError + NetworkError branches

## 4. F-D-02 — atomic cache write
- [ ] 4.1 Add Room atomicRenameAndUpsert
- [ ] 4.2 Atomicity test
- [ ] 4.3 Apply tmp+rename + tolerant readers

## 5. F-TM-02 + F-D-05 — single-flight test + typed key
- [ ] 5.1 IntegrationSingleFlightTest regression suite
- [ ] 5.2 TypedSingleFlightKey wrapper

## 6. F-A-02 — broaden single-flight to non-cache paths
- [ ] 6.1 coalesceConcurrent opt-in

## 7. F-D-06 — exponential backoff + clear-on-success
- [ ] 7.1 Schedule + jitter + clear

## 8. F-D-03 + F-D-04 — strike dead surfaces
- [ ] 8.1 Remove INVALIDATED + EVICTED
- [ ] 8.2 Delete MetadataCacheKeys.localized

## 9. Nits
- [ ] 9.1 Drop NoopRuntimeTraceSink short-circuit (F-A-03)
- [ ] 9.2 Drop unused policy param (F-A-04)

## 10. Sign-off
- [ ] 10.1 Re-run audits; update SIGN-OFF
