# Playback Tuning

Nexio has two progressive-stream tools that can make playback feel steadier on the right source: the VOD cache and parallel downloading. They help with network-bound playback, not with every stream type.

## What they solve

- VOD cache keeps progressive HTTP and HTTPS playback on local disk so repeated reads, seeks, and short bandwidth drops are less disruptive.
- Parallel downloading splits progressive fetching across multiple connections so startup and seek behavior can improve on slower or higher-latency hosts.
- Neither setting helps much on adaptive streams such as HLS or DASH, because those paths do not use the progressive downloader.

## Recommended starting point

For most users, the default path is the right one:

- Enable VOD cache.
- Leave parallel downloading off unless you have a repeatable buffering or seek problem.
- Keep the default cache size unless you know you need more or less disk use.

That setup gives you the safest improvement for ordinary VOD playback without turning tuning into a maintenance task.

## When to change more

- If a stable progressive stream still starts slowly or feels network-bound, try parallel downloading with a low connection count first.
- Increase one setting at a time and test the same title again.
- Use the same source when you compare results, otherwise you will not know which setting helped.

## Warning

Do not tune blindly. These settings only help when the source, network, and device are actually bottlenecked by progressive fetching. If the stream is already smooth, changing both settings at once usually adds complexity without improving playback.

## If this is not working

- If the same source still buffers, try a different source before increasing more settings.
- If the problem follows one device more than one source, move to [Audio Codecs and Device Advice](/playback/audio-codecs-and-device-advice).
- If you are not sure whether the issue is setup, source quality, or playback behavior, move to [Troubleshooting](/troubleshooting/).

## Related guides

- [Playback](/playback/)
- [Audio Codecs and Device Advice](/playback/audio-codecs-and-device-advice)
- [Subtitles and Auto-Translate](/playback/subtitles-and-auto-translate)
- [Troubleshooting](/troubleshooting/)
