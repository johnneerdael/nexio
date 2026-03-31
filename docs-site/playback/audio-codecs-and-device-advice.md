# Audio Codecs and Device Advice

Nexio can fall back to software decoding when a device cannot decode a format cleanly in hardware, but that does not make every stream equally practical on every device.

## AV1 software decoding support

- AV1 can fall back to FFmpeg software decode when the hardware decoder fails or `dav1d` does not initialize cleanly.
- That keeps playback alive on more devices.
- The tradeoff is CPU load, heat, and battery use.

## Why 4K is not recommended without hardware decoding

- 4K playback is much heavier than 1080p or lower.
- If you rely on software decode, 4K can turn into slow startup, stutter, dropped frames, or thermal throttling on weaker hardware.
- If the device cannot hardware decode the stream cleanly, a lower-resolution source is the safer choice.

## Dolby AC4 software decoding support

- FFmpeg includes AC-4 support, so Nexio can handle Dolby AC4 in software on supported builds.
- This helps when the device does not offer a reliable hardware path.
- Like every software decode path, it is more demanding than ordinary hardware playback.

## Practical device-level advice

- On weak TV boxes, keep the default hardware-first decoder preference unless you have a specific compatibility problem.
- If a stream struggles, change one thing at a time: lower the resolution first, then test a different codec or source.
- Avoid combining 4K, AV1, and software decode if you want smooth playback on modest hardware.
- Use compatibility audio tracks before you reach for advanced codec settings on older devices or receivers.

## If this is not working

- Start by switching to a lower-resolution or more compatible source before you change advanced settings.
- If audio is the main problem, prefer a simpler track before you assume the whole player is broken.
- If you need the deeper decoder and device-fallback context after that, move to [Advanced](/advanced/).
- If you are still not sure where the failure starts, use [Troubleshooting](/troubleshooting/).

## Related guides

- [Playback](/playback/)
- [Playback Tuning](/playback/playback-tuning)
- [Troubleshooting](/troubleshooting/)
- [Advanced](/advanced/)
