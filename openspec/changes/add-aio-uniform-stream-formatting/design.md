## Context
Nexio already contains two separate worlds for stream presentation:
- the Android app's hardcoded uniform stream formatting path in
  `StreamPresentationModels.kt`
- a top-level AIOStreams-style parser and formatter implementation in `parser/` and `formatter/`

The AIO formatter supports a richer template grammar than Nexio's current Kotlin presentation
builders and already defines the contract we want to follow long-term. The Android app should stop
treating uniform formatting as a fixed visual algorithm and instead treat it as template-driven
rendering over an AIO-compatible parsed stream model.

## Goals / Non-Goals
- Goals:
  - Make the AIO-compatible parser and formatter contract the source of truth for uniform stream
    card text.
  - Support the full modifier/comparator/template grammar currently implemented in the AIO
    formatter engine.
  - Support built-in AIO-compatible templates in Android code, including the new universal
    template, even if UI selection is deferred.
  - Keep non-uniform formatting behavior unchanged.
- Non-Goals:
  - Expose template editing or template selection in the Android UI in this change.
  - Rework stream selection, deduplication, or filtering rules beyond the parsed fields they
    already depend on.
  - Port every Node.js-specific parser implementation detail verbatim when an equivalent Android
    adapter can produce the same parse-value contract.

## Decisions
- Decision: Introduce an Android AIO compatibility layer instead of extending the current hardcoded
  Kotlin presentation builders.
  - Rationale: the compatibility layer gives us a stable contract for future template swaps rather
    than encoding each formatter revision directly into app UI logic.
- Decision: Uniform formatting will render through template output only.
  - Rationale: this removes split ownership between hardcoded builders and templates and matches the
    approved "single source of truth" direction.
- Decision: Port both the AIO formatter grammar and the AIO parse-value contract into Android.
  - Rationale: formatter-only compatibility would still drift because templates depend on specific
    field names, field types, and derived values.
- Decision: Add a built-in template registry in code with an internal default template ID.
  - Rationale: it keeps later UI work simple while letting this change ship multiple templates now.
- Decision: Build parity-focused tests around representative parsed streams and formatter output.
  - Rationale: compatibility claims need evidence at the template-output level, not just unit tests
    for isolated helper methods.

## Alternatives Considered
- Port only the formatter engine and keep Nexio's existing parsed model.
  - Rejected because field semantics would continue to drift from AIO templates over time.
- Recreate only the requested universal template as hardcoded Kotlin formatting.
  - Rejected because it solves one template revision but not the ongoing compatibility problem.
- Keep both hardcoded Kotlin builders and AIO templates active in the uniform path.
  - Rejected because it creates ambiguous ownership and makes future template changes harder to
    reason about.

## Architecture
The Android uniform formatting path should be split into three pieces:

1. AIO-compatible parse layer
   - Produces an Android model equivalent to the AIO formatter parse-value contract for stream,
     service, addon, metadata, and debug fields used by templates.
   - Reuses Nexio stream data where possible and derives AIO-compatible values when needed.

2. AIO template engine
   - Compiles template strings into evaluators.
   - Supports nested variable expansion, conditional checks, chained modifiers, comparator chains,
     and post-processing directives like `{tools.removeLine}` and `{tools.newLine}`.

3. Uniform presentation adapter
   - Selects a built-in template definition.
   - Evaluates the name and description templates against the AIO-compatible parse-value.
   - Splits formatter output into stream-card title and detail lines for the existing
     `StreamCardModel` contract.

## Risks / Trade-offs
- Risk: Full grammar compatibility introduces a substantial amount of string-processing logic in
  Android.
  - Mitigation: isolate the engine behind dedicated classes and verify parity with golden-style
    tests.
- Risk: AIO parser and Nexio parser field semantics diverge for edge cases.
  - Mitigation: treat the parse-value contract as the compatibility surface and add focused test
    fixtures for representative filenames and descriptions.
- Risk: Template-driven output changes existing visible stream card text in unexpected ways.
  - Mitigation: gate the new path behind `uniformStreamFormattingEnabled` and add regression tests
    for known examples.

## Migration Plan
1. Add Android AIO-compatible parsed models and derived field builders.
2. Port the AIO formatter grammar into Android and cover modifiers/comparators with tests.
3. Add built-in template definitions and set a default internal universal template.
4. Switch the uniform formatting path to template-driven rendering only.
5. Verify representative stream cards match expected AIO-compatible output.

## Rollback Plan
- Keep the legacy non-uniform rendering path untouched.
- If the new uniform path regresses badly, revert the uniform rendering switch while keeping the new
  compatibility classes out of use.
- Since template selection is code-only in this phase, rollback does not require a settings or UI
  migration.
