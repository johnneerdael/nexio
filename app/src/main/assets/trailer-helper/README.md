This directory is reserved for the bundled YouTube trailer helper runtime.

`yt_dlp` is embedded through Chaquopy Python sources in `app/src/main/python`.
This assets directory only carries a JS runtime executable for yt-dlp's challenge solver.

Expected staged payload per ABI:
- `runtime/arm64-v8a/node/bin/node`
- `runtime/arm64-v8a/quickjs/qjs`
- `runtime/armeabi-v7a/node/bin/node`
- `runtime/armeabi-v7a/quickjs/qjs`
- `runtime/x86_64/node/bin/node`
- `runtime/x86_64/quickjs/qjs`
- `runtime/x86/node/bin/node`
- `runtime/x86/quickjs/qjs`

The Android app selects the first matching ABI from `Build.SUPPORTED_ABIS`, copies that
runtime tree into app-private storage, then runs embedded `yt_dlp` against the live
YouTube bearer authorization header, optional `X-Goog-PageId` and `X-Goog-AuthUser`
values, plus the staged JS runtime executable to resolve authenticated trailer
playback URLs.
