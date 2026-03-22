# FFmpeg

## Purpose
Summarize how FFmpeg-related components fit into Nexio Android playback support.

## Audience
- Developers diagnosing codec and decode-path behavior
- Testers validating format compatibility changes

## Prerequisites
- Basic understanding of Android playback pipelines
- Access to test media for validation

## Procedure and Guidance
1. Identify the playback scenario and codec involved.
2. Confirm whether the scenario uses standard playback components or FFmpeg-assisted paths.
3. Validate behavior in the Android player before and after any configuration change.
4. Record reproducible evidence (title, stream, device, observed behavior).

## Validation and Expected Outcome
- Codec-specific behavior is observable and repeatable
- Changes can be validated without broad regressions in unrelated formats

## Related pages
- [Media3](./media3.md)
- [Playback Interface](../screens/player.md)
- [Deployment](../../dev/deployment.md)
