## Context

The archived passthrough transport validation change already established three useful invariants for
DTS-HD MA and DTS:X samples:

- the burst remains DTS-HD type IV (`Pc & 0x7F == 0x11`)
- the DTS-HD wrapper is present
- burst-by-burst golden comparison can catch payload mismatches

That closes the transport-label gap, but it does not provide a DTS-family-specific diagnosis for
the FFmpeg caveat where the DTS-HD SPDIF muxer preserves the type-IV wrapper while temporarily
reducing the wrapped payload to the DTS core size under overflow.

The next useful improvement is not a broader validator redesign. It is a focused DTS-family payload
classifier that supplements the existing transport invariants and golden comparison output.

## Goals

- Classify DTS-HD and DTS:X wrapped bursts as:
  - `type_iv_wrapper + hd_payload`
  - `type_iv_wrapper + likely_core_only_payload`
  - `type_iv_wrapper + unknown_payload`
- Surface the classifier result as explicit diagnostics in comparator results and export bundles
- Preserve the existing rule that bundled-golden burst comparison remains the primary pass/fail
  source of truth

## Non-Goals

- Do not replace golden burst comparison with heuristic payload inspection
- Do not add decoder-label-based validation rules
- Do not change passthrough transport bytes or packer behavior
- Do not try to prove AVR decode success from the validator

## Approach

Add a DTS-family classifier layer to the validation parser/comparator model.

1. Parse DTS-HD wrapper metadata from the burst as we already do.
2. Add DTS payload classification fields derived from the wrapped payload region.
3. Use a narrow, deterministic heuristic based on the FFmpeg caveat:
   - if the burst is type IV and wrapped, but the wrapped payload size or payload pattern strongly
     matches a core-sized payload path rather than an HD-sized payload path, classify it as
     `likely_core_only_payload`
   - if wrapped payload characteristics indicate the HD path is still present, classify it as
     `hd_payload`
   - otherwise classify as `unknown_payload`
4. Feed that classifier into comparison/export output so a failure can distinguish:
   - transport wrapper disappeared
   - wrapped payload likely collapsed to core
   - wrapped payload differs from golden but remains inconclusive heuristically

## Trade-Offs

### Why not rely only on golden comparison?

Golden comparison remains the strongest source of truth, but a payload-level classifier gives
sharper DTS-specific diagnostics and makes failures easier to interpret before a full offline diff.

### Why not add deep codec parsing?

A full DTS-HD/DTS:X payload parser would be more complex than needed for this step. The goal is a
minimal supplemental diagnostic layer grounded in the known FFmpeg fallback behavior.

### Why keep an `unknown` state?

Because heuristic payload inspection should not over-claim. When the burst remains type IV and
wrapped but the payload cannot be classified confidently, the validator should report that
ambiguity explicitly instead of fabricating certainty.

## Affected Areas

- `TransportValidationManifest.kt`
  - add DTS payload classification/result fields if needed
- `TransportValidationReferenceParser.kt`
  - derive DTS-family wrapped-payload classification metadata
- `TransportValidationComparator.kt`
  - incorporate the DTS payload classifier into DTS-family comparison results
- `TransportValidationDiagnosticsExporter.kt`
  - include payload classifier data in exported burst records and summaries
- DTS-specific unit tests
  - verify wrapped HD, wrapped likely-core-only, and wrapped unknown cases
