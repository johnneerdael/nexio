This directory is reserved for the bundled YouTube trailer helper runtime.

`yt_dlp` is embedded through Chaquopy Python sources in `app/src/main/python`.
The packaged Android app uses Chaquopy plus the Android JavaScript sandbox and
only ships `yt.solver.android.min.js` from the main assets tree.

Legacy Node/QuickJS helper binaries may still be staged here for local tooling:
- `runtime/arm64-v8a/node/bin/node`
- `runtime/arm64-v8a/node/lib/libnode.so`
- `runtime/arm64-v8a/node/lib/libc++_shared.so`
- `runtime/arm64-v8a/quickjs/qjs`
- `runtime/armeabi-v7a/node/bin/node`
- `runtime/armeabi-v7a/node/lib/libnode.so`
- `runtime/armeabi-v7a/node/lib/libc++_shared.so`
- `runtime/armeabi-v7a/quickjs/qjs`
- `runtime/x86_64/node/bin/node`
- `runtime/x86_64/node/lib/libnode.so`
- `runtime/x86_64/node/lib/libc++_shared.so`
- `runtime/x86_64/quickjs/qjs`
- `runtime/x86/quickjs/qjs`

These staged binaries are excluded from packaged APKs in `app/build.gradle.kts`
to avoid shipping all ABI copies inside every split APK.
