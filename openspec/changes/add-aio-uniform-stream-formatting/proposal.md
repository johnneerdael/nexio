# Change: Add AIO-Compatible Uniform Stream Formatting

## Why
Nexio's current uniform stream formatting path is implemented with hardcoded Kotlin title and
detail-line builders. That makes it difficult to adopt evolving universal templates from the
AIOStreams ecosystem and keeps parser/formatter behavior coupled to app code instead of a stable
template contract.

We want uniform stream formatting to use an AIO-compatible parser and formatter contract so future
template changes can be swapped in without rewriting Kotlin presentation logic. When uniform
formatting is enabled, the AIO-compatible formatter should become the single source of truth for
stream card text.

## What Changes
- Add an AIO-compatible stream parsing contract in Android that exposes the fields expected by the
  AIO formatter templates.
- Add an AIO-compatible template compiler/evaluator in Android with support for the current
  formatter grammar used by the built-in AIO templates.
- Add a built-in formatter registry in code so Nexio can ship multiple AIO-compatible templates,
  including the new universal template, without exposing template selection in the UI yet.
- Switch Nexio's uniform formatting path to render stream card title and detail lines exclusively
  from the AIO template output.
- Preserve the existing non-uniform stream presentation path as-is.

## Impact
- Affected specs: `uniform-stream-formatting` (new capability)
- Affected code:
  - `app/src/main/java/com/nexio/tv/core/stream/StreamPresentationModels.kt`
  - `app/src/test/java/com/nexio/tv/core/stream/StreamPresentationEngineTest.kt`
  - new AIO-compatible parser/formatter classes under `app/src/main/java/com/nexio/tv/core/stream/`
- Reference source:
  - `formatter/base.ts`
  - `formatter/predefined.ts`
  - `parser/file.ts`
  - `parser/streams.ts`
  - `parser/streamExpression.ts`

## Rollout & Safety
- Limit the new engine to the existing `uniformStreamFormattingEnabled` path so non-uniform
  rendering behavior is unchanged.
- Keep AIO compatibility tests focused on template parity and parsed-field parity to reduce the
  chance of silent formatting regressions.
- Ship built-in templates in code first without UI selection to keep rollout scope focused on the
  engine and compatibility layer.
