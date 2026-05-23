The binary ffmpeg extension was build with following decoders:

```
ENABLED_DECODERS=(vorbis opus flac alac pcm_mulaw pcm_alaw mp3 amrnb amrwb aac ac3 eac3 dca mlp truehd h263 h264 hevc av1 vc1 mjpeg mpeg4 mpegvideo mpeg2video vp8 vp9)
```

Complete [build instructions](https://github.com/androidx/media/blob/release/libraries/decoder_ffmpeg/README.md).

For 2160p YouTube trailer playback, rebuild FFmpeg with `FFMPEG_ENABLE_LIBDAV1D=1`
so AV1 streams use VideoLAN's `libdav1d` decoder instead of FFmpeg's built-in
`av1` decoder.

To assemble ``.aar``:

```
./gradlew :extension-ffmpeg:bundleReleaseAar
```
