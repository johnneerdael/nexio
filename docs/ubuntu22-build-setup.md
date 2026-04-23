# Ubuntu 22.04 Build Setup (NEXIO / NuvioTV)

This runbook sets up a clean Ubuntu 22.04 machine for debug/release Android builds, including optional native `libdovi` rebuilds.

## 1. System packages

```bash
sudo apt update
sudo apt install -y \
  git git-lfs unzip zip curl wget ca-certificates \
  openjdk-17-jdk \
  cmake ninja-build pkg-config \
  python3 python3-pip \
  clang lld make
```

Verify Java:

```bash
java -version
# should show 17.x
```

## 2. Android SDK command-line tools

```bash
mkdir -p "$HOME/Android/Sdk/cmdline-tools"
cd "$HOME/Android/Sdk/cmdline-tools"
wget -O cmdline-tools.zip "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
unzip -q cmdline-tools.zip
rm cmdline-tools.zip
mv cmdline-tools latest
```

Add Android env vars (`~/.bashrc`):

```bash
cat >> ~/.bashrc <<'EOF'
export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export PATH="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"
EOF
source ~/.bashrc
```

Install required SDK/NDK/CMake:

```bash
yes | sdkmanager --licenses
sdkmanager \
  "platform-tools" \
  "platforms;android-36" \
  "build-tools;36.0.0" \
  "cmake;3.22.1" \
  "ndk;27.2.12479018"
```

## 3. Rust toolchain (for optional `libdovi` rebuild)

```bash
curl https://sh.rustup.rs -sSf | sh -s -- -y
source "$HOME/.cargo/env"
rustup default stable
cargo install cargo-c --locked
```

Add Android Rust targets:

```bash
rustup target add \
  aarch64-linux-android \
  armv7-linux-androideabi \
  i686-linux-android \
  x86_64-linux-android
```

## 4. Clone repo and submodules

```bash
git clone --recurse-submodules https://github.com/johnneerdael/NuvioTV.git
cd NuvioTV
git submodule update --init --recursive
```

## 5. `local.properties` / `local.dev.properties`

Create `local.properties`:

```properties
sdk.dir=/home/<user>/Android/Sdk

SUPABASE_URL=...
SUPABASE_ANON_KEY=...
TRAKT_CLIENT_ID=...
TRAKT_CLIENT_SECRET=...
TV_LOGIN_WEB_BASE_URL=...
TRAKT_API_URL=https://api.trakt.tv/
INTRODB_API_URL=http://api.introdb.app
TRAILER_API_URL=https://api.kinocheck.com/trailers

USE_MEDIA3_SOURCE=true
DOVI_NATIVE_ENABLED=true
DOVI_EXTRACTOR_HOOK_READY=true
DOVI_ENABLE_REAL_LINK=true

# Optional explicit paths (usually not required; CMake auto-resolves from third_party/libdovi):
# DOVI_LIBDOVI_PREBUILT_ROOT=/abs/path/to/NuvioTV/third_party/libdovi
# DOVI_LIBDOVI_STATIC_LIB=/abs/path/to/libdovi.a
# DOVI_LIBDOVI_INCLUDE_DIR=/abs/path/to/include
```

Copy to dev file if needed:

```bash
cp local.properties local.dev.properties
```

## 5.1 Release signing keystore (required for release builds)

`release` signing is configured in `app/build.gradle.kts` and currently expects:

- Keystore path: `../nexio.jks` (relative to `app/`, so at repo root: `NuvioTV/nexio.jks`)
- Alias: `nexio`

Important:

- `debug` builds do **not** require this keystore anymore.
- `release` builds will fail if `nexio.jks` is missing or alias/password does not match Gradle config.
- Do not commit keystore files to git.

If you need to create a new keystore:

```bash
cd /homeassistant/NuvioTV
keytool -genkeypair \
  -v \
  -keystore nexio.jks \
  -alias nexio \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000
```

Validate keystore/alias:

```bash
ls -l /homeassistant/NuvioTV/nexio.jks
keytool -list -v -keystore /homeassistant/NuvioTV/nexio.jks -alias nexio
```

## 6. Gradle build commands

Use a project-local Gradle cache:

```bash
export GRADLE_USER_HOME="$PWD/.gradle-user"
```

Daemon guidance:

- `--no-daemon` is the safest default for reproducible builds and avoids stale daemon/native lock issues.
- For day-to-day local development on a stable machine, you can omit `--no-daemon` for faster incremental builds.
- If builds hang/crash/lock, run `./gradlew --stop` and retry with `--no-daemon`.

Debug universal APK:

```bash
./gradlew :app:packageDebugUniversalApk --no-daemon --max-workers=4 --console=plain
```

Release universal APK:

```bash
./gradlew :app:packageReleaseUniversalApk --no-daemon --max-workers=4 --console=plain
```

Faster non-universal debug APK:

```bash
./gradlew :app:assembleDebug --no-daemon --max-workers=4 --console=plain
```

Output paths:

- Debug universal: `app/build/outputs/apk_from_bundle/debug/app-debug-universal.apk`
- Release universal: `app/build/outputs/apk_from_bundle/release/app-release-universal.apk`

## 7. CMake direct sanity check (without Gradle)

Use this to validate native toolchain independently:

```bash
cmake -S app/src/main/cpp -B app/.cmake-manual-arm64 -G Ninja \
  -DCMAKE_MAKE_PROGRAM="$ANDROID_SDK_ROOT/cmake/3.22.1/bin/ninja" \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_SDK_ROOT/ndk/27.2.12479018/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-26 \
  -DDOVI_ENABLE_LIBDOVI=ON \
  -DDOVI_LIBDOVI_PREBUILT_ROOT="$PWD/third_party/libdovi"
```

Optional native build:

```bash
cmake --build app/.cmake-manual-arm64 -j"$(nproc)"
```

## 8. Optional: rebuild `libdovi` prebuilts for all Android ABIs

If you changed `dovi_tool` C API and need fresh `third_party/libdovi/*`:

```bash
cd dovi_tool/dolby_vision
```

Set Android toolchain env once:

```bash
export ANDROID_NDK_HOME="$ANDROID_SDK_ROOT/ndk/27.2.12479018"
export PATH="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH"
```

Build/install per ABI (repeat with matching target/ar/cc):

```bash
# arm64
export CARGO_TARGET_AARCH64_LINUX_ANDROID_AR=llvm-ar
export CC_aarch64_linux_android=aarch64-linux-android26-clang
cargo cinstall --release --target aarch64-linux-android --prefix "$PWD/../../third_party/libdovi/android-arm64"

# armeabi-v7a
export CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_AR=llvm-ar
export CC_armv7_linux_androideabi=armv7a-linux-androideabi26-clang
cargo cinstall --release --target armv7-linux-androideabi --prefix "$PWD/../../third_party/libdovi/android-armeabi-v7a"

# x86
export CARGO_TARGET_I686_LINUX_ANDROID_AR=llvm-ar
export CC_i686_linux_android=i686-linux-android26-clang
cargo cinstall --release --target i686-linux-android --prefix "$PWD/../../third_party/libdovi/android-x86"

# x86_64
export CARGO_TARGET_X86_64_LINUX_ANDROID_AR=llvm-ar
export CC_x86_64_linux_android=x86_64-linux-android26-clang
cargo cinstall --release --target x86_64-linux-android --prefix "$PWD/../../third_party/libdovi/android-x86_64"
```

## 9. ADB install commands

```bash
adb devices
adb install -r --streaming app/build/outputs/apk_from_bundle/debug/app-debug-universal.apk
```

For network ADB:

```bash
adb connect <ip>:5555
adb -s <ip>:5555 install -r --streaming app/build/outputs/apk_from_bundle/debug/app-debug-universal.apk
```

## 10. Troubleshooting quick hits

- Kill stale native processes before retry:
  ```bash
  pkill -f cmake || true
  pkill -f ninja || true
  ```
- Stop Gradle daemons:
  ```bash
  ./gradlew --stop
  ```
- Clear native staging dirs:
  ```bash
  rm -rf app/.cxx app/.cmake-manual-arm64 .cxx-build
  ```
- Clean rebuild:
  ```bash
  ./gradlew :app:clean :app:packageDebugUniversalApk --no-daemon --max-workers=4 --console=plain
  ```
- If release signing fails:
  ```bash
  ls -l ./nexio.jks
  keytool -list -v -keystore ./nexio.jks -alias nexio
  ```
