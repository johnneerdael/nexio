## Context

Late-stream TrueHD validation on `192.168.50.37` still shows audible degradation and a final underrun even though:

- transport integrity passes
- the route tuple is stable after startup
- playback reaches `ENDED`
- playback head progression remains monotonic

The remaining gap is that the steady-state native TrueHD path still behaves as an explicit packet retry machine. Stock Media3 instead keeps one pending encoded output buffer as the truth until the audio output fully handles it.

## Goals

- Move the steady-state TrueHD output model closer to stock Media3
- Keep startup behavior isolated from steady-state behavior
- Avoid regressions in transport integrity and Media3-facing contract behavior
- Validate after each pass and keep every successful step independently shippable

## Non-Goals

- Rewriting MAT / IEC transport
- Changing route selection or AudioTrack tuple logic
- Rewriting startup buffering behavior in the same pass
- Introducing blocking sleeps on the render path

## Design

### Pass 1

Refactor native steady-state ownership so the active steady-state path has one pending packed output truth and does not depend on separate retry-owned output control structures.

### Pass 2

Refactor steady-state drain semantics so zero and partial writes leave the same pending output truth active, with diagnostics preserved but no packet-episode control model.

### Pass 3

Refactor Java handoff logic so once steady-state playback begins, Java remains observational and does not participate in steady-state output pacing.

## Risks

- Startup and steady-state boundaries are still adjacent in the code and could accidentally cross again
- End-of-stream completion could regress if pending-output truth is not updated carefully
- Runtime validation may show worse sink churn even if transport remains green

## Mitigations

- Keep startup code structurally separate in every pass
- Use focused source-structure tests before implementation
- Validate with `scripts/run_adb_validation.sh` after each pass
- Commit only after a clean validation run
