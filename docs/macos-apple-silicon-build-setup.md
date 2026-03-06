# macOS Apple Silicon Build Setup (Nexio)

This runbook sets up a clean Apple Silicon Mac for Android debug/release builds, including optional native `libdovi` rebuilds.

Target machine profile:

- MacBook Pro with Apple Silicon
- tuned for an M4 class machine with 48 GB RAM

This guide assumes:

- macOS 15+
- zsh shell
- local SSD build workspace

## 1. Xcode command line tools

```bash
xcode-select --install
```

Verify:

```bash
xcode-select -p
clang --version
```

## 2. Homebrew base toolchain

Install Homebrew if needed:

```bash
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
```

Add Homebrew to `~/.zprofile` on Apple Silicon:

```bash
echo 'eval "$(/opt/homebrew/bin/brew shellenv)"' >> ~/.zprofile
eval "$(/opt/homebrew/bin/brew shellenv)"
```

Install build dependencies:

```bash
brew install \
  git git-lfs \
  openjdk@17 \
  cmake ninja pkg-config \
  python \
  rustup-init \
  cocoapods
```

Verify Java:

```bash
/opt/homebrew/opt/openjdk@17/bin/java -version
# should show 17.x
```

## 3. Android SDK command-line tools

Create SDK directories:

```bash
mkdir -p "$HOME/Library/Android/sdk/cmdline-tools"
cd "$HOME/Library/Android/sdk/cmdline-tools"
curl -L -o cmdline-tools.zip "https://dl.google.com/android/repository/commandlinetools-mac-11076708_latest.zip"
unzip -q cmdline-tools.zip
rm cmdline-tools.zip
mv cmdline-tools latest
```

Add Android env vars to `~/.zshrc`:

```bash
cat >> ~/.zshrc <<'EOF'
export ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"
EOF
source ~/.zshrc
```

Install required Android packages:

```bash
yes | sdkmanager --licenses
sdkmanager \
  "platform-tools" \
  "platforms;android-36" \
  "build-tools;36.0.0" \
  "cmake;3.22.1" \
  "ndk;27.2.12479018"
```

## 4. Rust toolchain (for optional `libdovi` rebuild)

Install Rust:

```bash
rustup-init -y
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

## 5. Clone repo and submodules

```bash
git clone --recurse-submodules https://github.com/johnneerdael/nexio.git
cd nexio
git submodule update --init --recursive
git lfs install
git lfs pull
```

## 6. `local.properties` / `local.dev.properties`

Create `local.properties`:

```properties
sdk.dir=/Users/<user>/Library/Android/sdk

SUPABASE_URL=...
SUPABASE_ANON_KEY=...
TRAKT_CLIENT_ID=...
TRAKT_CLIENT_SECRET=...
TV_LOGIN_WEB_BASE_URL=...
TRAKT_API_URL=https://api.trakt.tv/
INTRODB_API_URL=http://api.introdb.app

USE_MEDIA3_SOURCE=true
DOVI_NATIVE_ENABLED=true
DOVI_EXTRACTOR_HOOK_READY=true
DOVI_ENABLE_REAL_LINK=true

# Optional explicit paths if you are overriding auto-discovery:
# DOVI_LIBDOVI_PREBUILT_ROOT=/abs/path/to/nexio/third_party/libdovi
# DOVI_LIBDOVI_STATIC_LIB=/abs/path/to/libdovi.a
# DOVI_LIBDOVI_INCLUDE_DIR=/abs/path/to/include
```

If you want separate debug secrets:

```bash
cp local.properties local.dev.properties
```

## 6.1 Release signing keystore

`release` signing currently expects:

- keystore path: `../nexio.jks` relative to `app/`
- repo root path: `./nexio.jks`
- alias: `nexio`

Create a keystore if needed:

```bash
cd ~/src/nexio
keytool -genkeypair \
  -v \
  -keystore nexio.jks \
  -alias nexio \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000
```

Validate it:

```bash
ls -l ./nexio.jks
keytool -list -v -keystore ./nexio.jks -alias nexio
```

## 7. Apple Silicon performance tuning

Do not bake these into the repo-wide `gradle.properties`. Put them in `~/.gradle/gradle.properties` so they stay machine-local.

Create or update `~/.gradle/gradle.properties`:

```properties
org.gradle.daemon=true
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.vfs.watch=true

# M4 / 48 GB RAM tuning
org.gradle.jvmargs=-Xmx12288m -XX:MaxMetaspaceSize=2048m -Dfile.encoding=UTF-8 -Dkotlin.daemon.jvm.options=-Xmx6144m

# Keep workers below total cores so native and packaging phases still have headroom
org.gradle.workers.max=12

android.useAndroidX=true
android.nonTransitiveRClass=true
kotlin.code.style=official
```

Why these values:

- `Xmx12g` gives Gradle enough heap for Android + KSP + Compose without pointless over-allocation.
- `kotlin daemon 6g` reduces Kotlin compile churn on large incremental builds.
- `workers.max=12` is a good high-throughput starting point on a strong Apple Silicon laptop without saturating the whole machine.

If the machine stays responsive and thermals are fine, you can test:

```properties
org.gradle.workers.max=14
```

Do not start at 20+ workers. It usually hurts real build time once native and I/O-heavy phases overlap.

## 8. Project-local build cache

Use a repo-local Gradle cache when you want isolated reproducible builds:

```bash
export GRADLE_USER_HOME="$PWD/.gradle-user"
```

For fastest day-to-day local work on the MacBook, you can also leave `GRADLE_USER_HOME` unset and use the normal `~/.gradle` cache.

## 9. Build commands

Fast local debug build:

```bash
./gradlew :app:assembleDebug --console=plain
```

Debug universal APK:

```bash
./gradlew :app:packageDebugUniversalApk --console=plain
```

Release universal APK:

```bash
./gradlew :app:packageReleaseUniversalApk --console=plain
```

Compile-only sanity pass:

```bash
./gradlew :app:compileDebugKotlin --console=plain
```

If you want deterministic CI-like behavior:

```bash
./gradlew :app:packageDebugUniversalApk --no-daemon --max-workers=12 --console=plain
```

Output paths:

- Debug universal: `app/build/outputs/apk_from_bundle/debug/app-debug-universal.apk`
- Release universal: `app/build/outputs/apk_from_bundle/release/app-release-universal.apk`

## 10. CMake direct sanity check

Use this to validate native toolchain wiring independently from Gradle.

Resolve the NDK host prebuilt dir dynamically:

```bash
export NDK_HOST_TAG="$(basename "$(find "$ANDROID_SDK_ROOT/ndk/27.2.12479018/toolchains/llvm/prebuilt" -mindepth 1 -maxdepth 1 -type d | head -n 1)")"
echo "$NDK_HOST_TAG"
```

Configure:

```bash
cmake -S app/src/main/cpp -B app/.cmake-manual-arm64 -G Ninja \
  -DCMAKE_MAKE_PROGRAM="$ANDROID_SDK_ROOT/cmake/3.22.1/bin/ninja" \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_SDK_ROOT/ndk/27.2.12479018/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-26 \
  -DDOVI_ENABLE_LIBDOVI=ON \
  -DDOVI_LIBDOVI_PREBUILT_ROOT="$PWD/third_party/libdovi"
```

Build:

```bash
cmake --build app/.cmake-manual-arm64 -j"$(sysctl -n hw.ncpu)"
```

## 11. Optional: rebuild `libdovi` prebuilts for Android ABIs

If you changed `dovi_tool` C API and need fresh `third_party/libdovi/*`:

```bash
cd dovi_tool/dolby_vision
export ANDROID_NDK_HOME="$ANDROID_SDK_ROOT/ndk/27.2.12479018"
export NDK_HOST_TAG="$(basename "$(find "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt" -mindepth 1 -maxdepth 1 -type d | head -n 1)")"
export PATH="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$NDK_HOST_TAG/bin:$PATH"
```

Build/install per ABI:

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

## 12. ADB install commands

```bash
adb devices
adb install -r --streaming app/build/outputs/apk_from_bundle/debug/app-debug-universal.apk
```

For network ADB:

```bash
adb connect <ip>:5555
adb -s <ip>:5555 install -r --streaming app/build/outputs/apk_from_bundle/debug/app-debug-universal.apk
```

## 13. Speed notes specific to the MacBook Pro M4

- Keep the repo on the internal SSD, not on external USB storage.
- Exclude the repo and Gradle caches from Spotlight if indexing becomes noisy:
  - System Settings -> Siri & Spotlight -> Privacy
- Prefer the Gradle daemon for day-to-day local work on this machine.
- Do not use `clean` between normal iterations.
- Use `:app:assembleDebug` for most iterations; universal APK packaging is slower.
- Keep Android Studio closed during terminal-only release builds if you want maximum memory and I/O headroom.

## 14. Troubleshooting quick hits

Stop Gradle daemons:

```bash
./gradlew --stop
```

Clear native staging dirs:

```bash
rm -rf app/.cxx app/.cmake-manual-arm64 .cxx-build
```

Clean rebuild:

```bash
./gradlew :app:clean :app:packageDebugUniversalApk --no-daemon --max-workers=12 --console=plain
```

If release signing fails:

```bash
ls -l ./nexio.jks
keytool -list -v -keystore ./nexio.jks -alias nexio
```
