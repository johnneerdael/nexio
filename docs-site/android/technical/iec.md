# IEC Passthrough

## Purpose
Provide an operational reference for IEC passthrough behavior in the Android audio path.

## Audience
- Developers investigating passthrough audio behavior
- Testers validating transport and sink-level output

## Prerequisites
- Device and content that exercise passthrough scenarios
- Familiarity with Android player behavior

## Procedure and Guidance
1. Validate baseline playback in [Playback Interface](../screens/player.md).
2. Use controlled passthrough test cases to capture runtime behavior.
3. Cross-check implementation notes with developer architecture guidance before changing audio components.
4. Keep diagnostics scoped to one device, one stream, and one expected output per run.

## Validation and Expected Outcome
- Passthrough behavior is reproducible for defined test cases
- Findings can be mapped to specific runtime conditions and component paths

## Related pages
- [Media3](./media3.md)
- [Architecture](../../dev/architecture.md)
- [Deployment](../../dev/deployment.md)
