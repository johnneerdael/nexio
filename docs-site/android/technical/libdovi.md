# libdovi

## Purpose
Document the role of libdovi-related integration points in Nexio Android video processing.

## Audience
- Developers working on Dolby Vision metadata handling
- Maintainers reviewing native bridge behavior

## Prerequisites
- Familiarity with Android native components
- Access to repository native bridge sources

## Procedure and Guidance
1. Identify the affected playback scenario and expected video behavior.
2. Review native bridge components used for Dolby Vision metadata flow.
3. Validate behavior using controlled test content before broad rollout.
4. Keep runtime notes tied to exact app build and content sample.

## Validation and Expected Outcome
- Dolby Vision-related behavior is testable and documented per scenario
- Native integration changes remain traceable and reviewable

## Related pages
- [Media3](./media3.md)
- [FFmpeg](./ffmpeg.md)
- [Architecture](../../dev/architecture.md)
