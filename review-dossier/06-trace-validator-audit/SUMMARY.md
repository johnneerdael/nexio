# Trace Validator Audit Verdict

- **Review SHA:** `39b0df54ae5845f525de37791ff99356e2364044`
- **Audit task:** `:app:generateTraceValidatorAudit` (currently scoped to `TraceBundleGoldenTest` only)
- **Verdict:** PASS

## Test runs

| Test | Result | In audit task? |
|---|---|---|
| `TraceBundleGoldenTest` (synthetic events) | PASS (1/1, 0 failures, 0.053s) | yes |
| `RuntimeTraceValidatorRealEmissionTest` (real emissions) | PASS (1/1, 0 failures, 0.333s) | no — confirmed by inspecting `app/build.gradle.kts` lines 403–410 (only `includeTestsMatching("com.nexio.tv.core.trace.TraceBundleGoldenTest")`) |

## Real-emission proof

`TraceBundleGoldenTest` exercises the validator against synthetic envelopes (test name: `synthetic session validates PASS and bundle has no raw tokens`).

`RuntimeTraceValidatorRealEmissionTest` (added in commit `39b0df54a`, test name: `real emissions across runtime metadata profile and first-paint validate as PASS`) drives REAL emissions through `DefaultIntegrationRuntime`, `MetadataRouter`, `FieldResolver`, `ProfileBoundaryEnforcer`, and `MetaPreview.toFirstPaintHomeDisplayMetadata`, then runs the validator over the captured JSONL.

Both tests pass at the review SHA.

## Audit-task scope check

`RuntimeTraceValidatorRealEmissionTest` is NOT in the audit task's `includeTestsMatching` filter (verified at `app/build.gradle.kts:403–410`). The test itself passes when run via `:app:testUniversalDebugUnitTest`, so this is a P1 audit-scope follow-up to be filed in `lanes/I-trace-mode.md` (Task 33 owner) recommending its inclusion in `generateTraceValidatorAudit` so the gate exercises real-emission validation alongside the synthetic golden.

## Pass criteria

- `TraceBundleGoldenTest` PASS — met.
- `RuntimeTraceValidatorRealEmissionTest` PASS (regardless of whether it's in the audit task) — met.

## Artifacts

- `gradle-output.txt` — full `:app:generateTraceValidatorAudit --rerun-tasks` output, BUILD SUCCESSFUL in 1m 1s.
- `TEST-com.nexio.tv.core.trace.TraceBundleGoldenTest.xml` — JUnit XML from audit task.
- `TEST-com.nexio.tv.core.trace.RuntimeTraceValidatorRealEmissionTest.xml` — JUnit XML from explicit run.

## Outcome

PASS — gate cleared. P1 audit-scope follow-up to be filed in Lane I (recommend adding `RuntimeTraceValidatorRealEmissionTest` to `generateTraceValidatorAudit`'s `includeTestsMatching` filter).
