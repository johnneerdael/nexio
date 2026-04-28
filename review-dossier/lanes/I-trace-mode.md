# Lane I — On-Device Trace Mode

- **Review SHA:** `39b0df54ae5845f525de37791ff99356e2364044`
- **Phase:** 5
- **Owner task:** Task 33
- **Status:** PLACEHOLDER

<remainder filled in by the owner task>

## Pre-staged findings (from Task 23 red-flag scan)

- **F-RF-02** (cross-ref **F-01**): `metadata.first_paint` is emitted by `FirstPaintTracer.recordHomePreview` (`FirstPaintTracer.kt:31`), invoked from `HomeFirstPaintMetadataMapper.kt:17` during the home preview render path. PREVIEW depth must not produce `first_paint`; the emission site is wrong even though the caller count is non-zero. The validator rule `PreviewMustNotRouteOrNetwork` (`TraceValidationRules.kt`) is the corresponding detector. Detected by Red flag 5 + Red flag 13 (per-method audit).
