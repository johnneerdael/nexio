# Media3

## Purpose
Provide a technical orientation for the Media3-based playback layer used by the Android app.

## Audience
- Android developers
- Contributors investigating playback behavior

## Prerequisites
- Familiarity with Android playback basics
- Access to the repository source tree

## Procedure and Guidance
1. Start with Android runtime behavior in [Playback Interface](../screens/player.md).
2. Review project media libraries under `media/` and packaged Android media dependencies under `app/libs/`.
3. Compare expected UI behavior with technical components before changing playback settings.
4. Keep changes incremental and validate one playback scenario at a time.

## Validation and Expected Outcome
- Playback behavior can be traced from UI flow to Media3-oriented components
- Technical investigations produce reproducible runtime observations

## Related pages
- [FFmpeg](./ffmpeg.md)
- [IEC Passthrough](./iec.md)
- [Architecture](../../dev/architecture.md)
