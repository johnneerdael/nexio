# dovi_tool 2.3.2 Upgrade Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring Nexio's custom `dovi_tool` fork and Android `libdovi` prebuilts up to upstream `quietvoid/dovi_tool` 2.3.2, while preserving Nexio's custom C API conversion-mode mapping and applying small usage hardening found during review.

**Architecture:** Treat `dovi_tool` as the source of truth for `libdovi`, merge upstream tag `2.3.2` into the fork submodule, rebuild the ABI-specific static libraries under `third_party/libdovi`, then tighten Nexio's native bridge usage around the updated C API. The app-facing conversion API remains Kotlin -> JNI bridge -> `libdovi` C API, with explicit app modes mapped to upstream C API modes.

**Tech Stack:** Git submodule, Rust 1.88+, cargo-c, Android NDK 27.2.12479018, Kotlin/JVM tests, CMake/JNI, Media3 extractor hooks.

---

## Context

Upstream release page: https://github.com/quietvoid/dovi_tool/releases/tag/2.3.2

Release facts verified on 2026-04-17:

- Upstream tag `2.3.2` points to `9aca1b700ba696c36ad8bc0928e4bd42f7a5f79b`.
- GitHub release timestamp is 2026-04-05 13:17 UTC on the release page; the tagged commit timestamp is 2026-04-05 08:54:48 -0400.
- Release notes call out:
  - `info` and `plot`: L8 trims now display all trims instead of only the first.
  - validation/editor feedback improvements.
  - `inject-rpu`: duplicated metadata padding fix.
  - `libdovi`: EOF-before-CRC32 error improvement.
  - `libdovi`: C FFI and RPU parsing fixes.
  - `libdovi`: L11 byte1 parsing improvement and byte2 validation removal.

Local submodule facts:

- Root tracks `dovi_tool` as a git submodule at `2a7238a0efbacc3187b2ed4a51fd0ffce142bea5`.
- The submodule is currently detached at that commit.
- Local custom commit: `2a7238a fix: align C API conversion modes with documented mappings`.
- Merge base between local `HEAD` and upstream `2.3.2`: `d4734e5f59bd609a540de672dca5ea65bedbf76a`.
- Upstream commits still ahead of the fork:
  - `a718ac3 Update dependencies`
  - `ae3a185 Update lib dependencies`
  - `bf3b714 ci: update workflows actions versions`
  - `9aca1b7 Prepare v2.3.2`
- Local custom commit still ahead of upstream:
  - `2a7238a fix: align C API conversion modes with documented mappings`

## File Structure

- Modify submodule `dovi_tool`
  - Merge upstream tag `2.3.2` into a branch based on current `2a7238a`.
  - Preserve Nexio's custom mapping in `dovi_tool/dolby_vision/src/rpu/mod.rs`.
  - Accept upstream dependency and release metadata changes in `dovi_tool/Cargo.toml`, `dovi_tool/Cargo.lock`, `dovi_tool/dolby_vision/Cargo.toml`, and `dovi_tool/dolby_vision/Cargo.lock`.

- Modify root submodule pointer `dovi_tool`
  - Advance the root repository gitlink to the new fork commit.

- Regenerate tracked Android prebuilts
  - `third_party/libdovi/android-arm64/include/libdovi/rpu_parser.h`
  - `third_party/libdovi/android-arm64/lib/libdovi.a`
  - `third_party/libdovi/android-arm64/lib/pkgconfig/dovi.pc`
  - `third_party/libdovi/android-armeabi-v7a/include/libdovi/rpu_parser.h`
  - `third_party/libdovi/android-armeabi-v7a/lib/libdovi.a`
  - `third_party/libdovi/android-armeabi-v7a/lib/pkgconfig/dovi.pc`
  - `third_party/libdovi/android-x86/include/libdovi/rpu_parser.h`
  - `third_party/libdovi/android-x86/lib/libdovi.a`
  - `third_party/libdovi/android-x86/lib/pkgconfig/dovi.pc`
  - `third_party/libdovi/android-x86_64/include/libdovi/rpu_parser.h`
  - `third_party/libdovi/android-x86_64/lib/libdovi.a`
  - `third_party/libdovi/android-x86_64/lib/pkgconfig/dovi.pc`

- Modify app/native usage
  - `app/src/main/cpp/dovi_bridge.cpp`: include generated `libdovi/rpu_parser.h` instead of duplicating C API declarations; constrain conversion-mode mapping to the app's public modes; bump bridge version string.
  - `app/src/main/java/com/nexio/tv/core/player/DoviBridge.kt`: change the default DV7 conversion mode from mode `1` to the app's DV8.1 mode `2`.
  - `app/src/test/java/com/nexio/tv/core/player/DolbyVisionConversionModeSelectorTest.kt`: lock the app mode constants and bridge default mode.

- Modify secondary libdovi usage
  - `media/libraries/cpp_audiosink/kodi/xbmc/utils/BitstreamConverter.cpp`: add null/error checks after `dovi_parse_unspec62_nalu` before reading the header.

- Modify docs
  - `docs-site/android/technical/libdovi.md`: record that the current prebuilts are based on `dovi_tool` 2.3.2 plus Nexio's C API mode mapping patch, and document app mode `5` -> C API mode `4`.

### Task 1: Merge Upstream 2.3.2 Into the Fork

**Files:**
- Modify: `dovi_tool`
- Modify in submodule: `dovi_tool/Cargo.toml`
- Modify in submodule: `dovi_tool/Cargo.lock`
- Modify in submodule: `dovi_tool/dolby_vision/Cargo.toml`
- Modify in submodule: `dovi_tool/dolby_vision/Cargo.lock`
- Preserve in submodule: `dovi_tool/dolby_vision/src/rpu/mod.rs`

- [ ] **Step 1: Confirm clean root and submodule state**

Run:

```bash
git status --short
git -C dovi_tool status --short
git -C dovi_tool rev-parse HEAD
git -C dovi_tool rev-parse 2.3.2^{commit}
```

Expected:

```text
2a7238a0efbacc3187b2ed4a51fd0ffce142bea5
9aca1b700ba696c36ad8bc0928e4bd42f7a5f79b
```

The first two commands should print no local modifications. If `2.3.2` is missing, run:

```bash
git -C dovi_tool fetch --no-tags https://github.com/quietvoid/dovi_tool.git refs/tags/2.3.2:refs/tags/2.3.2
```

Expected:

```text
From https://github.com/quietvoid/dovi_tool
 * [new tag]         2.3.2 -> 2.3.2
```

- [ ] **Step 2: Create a named working branch for the detached submodule**

Run:

```bash
git -C dovi_tool switch -c nexio/dovi-tool-2.3.2-upgrade
```

Expected:

```text
Switched to a new branch 'nexio/dovi-tool-2.3.2-upgrade'
```

- [ ] **Step 3: Merge upstream 2.3.2**

Run:

```bash
git -C dovi_tool merge --no-ff 2.3.2 -m "merge: bring dovi_tool fork to 2.3.2"
```

Expected:

```text
Merge made by the 'ort' strategy.
```

- [ ] **Step 4: Verify Nexio's conversion-mode mapping survived the merge**

Run:

```bash
sed -n '30,48p' dovi_tool/dolby_vision/src/rpu/mod.rs
```

Expected code:

```rust
impl From<u8> for ConversionMode {
    fn from(mode: u8) -> Self {
        match mode {
            0 => ConversionMode::Lossless,
            1 => ConversionMode::ToMel,
            2 => ConversionMode::To81,
            3 => ConversionMode::To84,
            4 => ConversionMode::To81MappingPreserved,
            _ => ConversionMode::Lossless,
        }
    }
}
```

- [ ] **Step 5: Run the Rust test suite after the merge**

Run:

```bash
cargo test --manifest-path dovi_tool/Cargo.toml
cargo test --manifest-path dovi_tool/dolby_vision/Cargo.toml --features capi
```

Expected:

```text
test result: ok
```

- [ ] **Step 6: Commit the fork merge inside the submodule**

Run:

```bash
git -C dovi_tool status --short
git -C dovi_tool log --oneline --left-right --cherry-pick HEAD...2.3.2
git -C dovi_tool push origin nexio/dovi-tool-2.3.2-upgrade
```

Expected:

```text
< 2a7238a fix: align C API conversion modes with documented mappings
```

The push should create or update the branch on `johnneerdael/dovi_tool`.

### Task 2: Rebuild Android libdovi Prebuilts

**Files:**
- Modify: `third_party/libdovi/android-arm64/include/libdovi/rpu_parser.h`
- Modify: `third_party/libdovi/android-arm64/lib/libdovi.a`
- Modify: `third_party/libdovi/android-arm64/lib/pkgconfig/dovi.pc`
- Modify: `third_party/libdovi/android-armeabi-v7a/include/libdovi/rpu_parser.h`
- Modify: `third_party/libdovi/android-armeabi-v7a/lib/libdovi.a`
- Modify: `third_party/libdovi/android-armeabi-v7a/lib/pkgconfig/dovi.pc`
- Modify: `third_party/libdovi/android-x86/include/libdovi/rpu_parser.h`
- Modify: `third_party/libdovi/android-x86/lib/libdovi.a`
- Modify: `third_party/libdovi/android-x86/lib/pkgconfig/dovi.pc`
- Modify: `third_party/libdovi/android-x86_64/include/libdovi/rpu_parser.h`
- Modify: `third_party/libdovi/android-x86_64/lib/libdovi.a`
- Modify: `third_party/libdovi/android-x86_64/lib/pkgconfig/dovi.pc`

- [ ] **Step 1: Confirm cargo-c is installed**

Run:

```bash
cargo cinstall --version
```

Expected:

```text
cargo-cinstall
```

If the command is missing, run:

```bash
cargo install cargo-c --locked
```

Expected:

```text
Installed package `cargo-c`
```

- [ ] **Step 2: Configure the Android NDK toolchain**

Run from the repository root:

```bash
export ANDROID_NDK_HOME="$ANDROID_SDK_ROOT/ndk/27.2.12479018"
export NDK_HOST_TAG="$(basename "$(find "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt" -mindepth 1 -maxdepth 1 -type d | head -n 1)")"
export PATH="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$NDK_HOST_TAG/bin:$PATH"
test -x "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$NDK_HOST_TAG/bin/aarch64-linux-android26-clang"
```

Expected: the final `test` command exits with status `0`.

- [ ] **Step 3: Rebuild/install the arm64 prebuilt**

Run:

```bash
cd dovi_tool/dolby_vision
export CARGO_TARGET_AARCH64_LINUX_ANDROID_AR=llvm-ar
export CC_aarch64_linux_android=aarch64-linux-android26-clang
cargo cinstall --release --target aarch64-linux-android --prefix "$PWD/../../third_party/libdovi/android-arm64"
cd ../..
```

Expected:

```text
Installing
```

- [ ] **Step 4: Rebuild/install the armeabi-v7a prebuilt**

Run:

```bash
cd dovi_tool/dolby_vision
export CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_AR=llvm-ar
export CC_armv7_linux_androideabi=armv7a-linux-androideabi26-clang
cargo cinstall --release --target armv7-linux-androideabi --prefix "$PWD/../../third_party/libdovi/android-armeabi-v7a"
cd ../..
```

Expected:

```text
Installing
```

- [ ] **Step 5: Rebuild/install the x86 prebuilt**

Run:

```bash
cd dovi_tool/dolby_vision
export CARGO_TARGET_I686_LINUX_ANDROID_AR=llvm-ar
export CC_i686_linux_android=i686-linux-android26-clang
cargo cinstall --release --target i686-linux-android --prefix "$PWD/../../third_party/libdovi/android-x86"
cd ../..
```

Expected:

```text
Installing
```

- [ ] **Step 6: Rebuild/install the x86_64 prebuilt**

Run:

```bash
cd dovi_tool/dolby_vision
export CARGO_TARGET_X86_64_LINUX_ANDROID_AR=llvm-ar
export CC_x86_64_linux_android=x86_64-linux-android26-clang
cargo cinstall --release --target x86_64-linux-android --prefix "$PWD/../../third_party/libdovi/android-x86_64"
cd ../..
```

Expected:

```text
Installing
```

- [ ] **Step 7: Verify regenerated headers still expose libdovi 3.3.2**

Run:

```bash
grep -R "#define RPU_PARSER_" third_party/libdovi/*/include/libdovi/rpu_parser.h
```

Expected includes:

```text
#define RPU_PARSER_MAJOR 3
#define RPU_PARSER_MINOR 3
#define RPU_PARSER_PATCH 2
```

### Task 3: Tighten the App JNI Bridge Around the Updated C API

**Files:**
- Modify: `app/src/main/cpp/dovi_bridge.cpp`
- Modify: `app/src/main/java/com/nexio/tv/core/player/DoviBridge.kt`
- Test: `app/src/test/java/com/nexio/tv/core/player/DolbyVisionConversionModeSelectorTest.kt`

- [ ] **Step 1: Add failing Kotlin assertions for app mode constants and the bridge default**

Append this test to `app/src/test/java/com/nexio/tv/core/player/DolbyVisionConversionModeSelectorTest.kt`:

```kotlin
    @Test
    fun `bridge defaults to profile 8 1 mode`() {
        assertEquals(2, DolbyVisionConversionModeSelector.MODE_PROFILE_8_1)
        assertEquals(5, DolbyVisionConversionModeSelector.MODE_PROFILE_8_1_PRESERVE_MAPPING)
        assertEquals(
            DolbyVisionConversionModeSelector.MODE_PROFILE_8_1,
            DEFAULT_DV7_CONVERSION_MODE
        )
    }
```

- [ ] **Step 2: Run the targeted test and verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.player.DolbyVisionConversionModeSelectorTest
```

Expected:

```text
Unresolved reference: DEFAULT_DV7_CONVERSION_MODE
```

- [ ] **Step 3: Add an explicit default conversion constant**

In `app/src/main/java/com/nexio/tv/core/player/DoviBridge.kt`, add this top-level constant after imports:

```kotlin
internal const val DEFAULT_DV7_CONVERSION_MODE = DolbyVisionConversionModeSelector.MODE_PROFILE_8_1
```

Change the conversion method signature from:

```kotlin
fun convertDv7RpuToDv81(payload: ByteArray, mode: Int = 1): ByteArray? {
```

to:

```kotlin
fun convertDv7RpuToDv81(
    payload: ByteArray,
    mode: Int = DEFAULT_DV7_CONVERSION_MODE
): ByteArray? {
```

- [ ] **Step 4: Replace duplicate C declarations with the generated header**

In `app/src/main/cpp/dovi_bridge.cpp`, replace the manual declarations inside `#if DOVI_REAL_LINKED`:

```cpp
extern "C" {
typedef struct DoviRpuOpaque DoviRpuOpaque;
typedef struct DoviData {
    const uint8_t* data;
    size_t len;
} DoviData;

DoviRpuOpaque* dovi_parse_unspec62_nalu(const uint8_t* buf, size_t len);
DoviRpuOpaque* dovi_parse_rpu(const uint8_t* buf, size_t len);
const char* dovi_rpu_get_error(const DoviRpuOpaque* ptr);
void dovi_rpu_free(DoviRpuOpaque* ptr);
int32_t dovi_convert_rpu_with_mode(DoviRpuOpaque* ptr, uint8_t mode);
const DoviData* dovi_write_unspec62_nalu(DoviRpuOpaque* ptr);
void dovi_data_free(const DoviData* data);
}
```

with:

```cpp
#include <libdovi/rpu_parser.h>
```

- [ ] **Step 5: Constrain app mode mapping to the two app-supported DV8.1 modes**

Replace `map_conversion_mode` in `app/src/main/cpp/dovi_bridge.cpp` with:

```cpp
static inline uint8_t map_conversion_mode(jint mode) {
    switch (mode) {
        case 2:
            return 2U;
        case 5:
            return 4U;
        default:
            return 2U;
    }
}
```

Rationale: Kotlin exposes app mode `2` for default DV8.1 and app mode `5` for preserve-mapping DV8.1. The custom fork maps C API mode `4` to `To81MappingPreserved`; allowing raw mode `3` through this JNI method would invoke profile 8.4 despite the method name being `convertDv7RpuToDv81`.

- [ ] **Step 6: Bump the native bridge version string**

In `app/src/main/cpp/dovi_bridge.cpp`, change:

```cpp
return env->NewStringUTF("dovi-bridge-libdovi-capi-0.2");
```

to:

```cpp
return env->NewStringUTF("dovi-bridge-libdovi-capi-0.3");
```

- [ ] **Step 7: Run targeted Kotlin tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.player.DolbyVisionConversionModeSelectorTest --tests com.nexio.tv.core.player.DolbyVisionDiagnosticsTest
```

Expected:

```text
BUILD SUCCESSFUL
```

### Task 4: Harden the Kodi BitstreamConverter libdovi Call Site

**Files:**
- Modify: `media/libraries/cpp_audiosink/kodi/xbmc/utils/BitstreamConverter.cpp`

- [ ] **Step 1: Replace `processDoviRpu` with null/error-safe parsing**

Replace the existing `CBitstreamConverter::processDoviRpu` implementation with:

```cpp
const DoviData* CBitstreamConverter::processDoviRpu(uint8_t* buf, uint32_t nalSize)
{
  // early exit if no processing option is enabled
  if (!m_convert_dovi && !m_setDoviZeroLevel5)
    return NULL;

  DoviRpuOpaque* rpu = dovi_parse_unspec62_nalu(buf, nalSize);
  if (!rpu)
    return NULL;

  const char* parseError = dovi_rpu_get_error(rpu);
  if (parseError && parseError[0] != '\0')
  {
    dovi_rpu_free(rpu);
    return NULL;
  }

  const DoviRpuDataHeader* header = dovi_rpu_get_header(rpu);
  const DoviData* rpuData = NULL;

  int ret = 0;
  bool processed = false;

  if (!header)
  {
    dovi_rpu_free(rpu);
    return rpuData;
  }

  if (m_convert_dovi && header->guessed_profile == 7)
  {
    ret = dovi_convert_rpu_with_mode(rpu, 2);
    processed = true;
  }

  if (ret == 0 && m_setDoviZeroLevel5)
  {
    ret = dovi_rpu_set_active_area_offsets(rpu, 0, 0, 0, 0);
    processed = true;
  }

  if (ret == 0 && processed)
    rpuData = dovi_write_unspec62_nalu(rpu);

  dovi_rpu_free_header(header);
  dovi_rpu_free(rpu);

  return rpuData;
}
```

- [ ] **Step 2: Verify the native code compiles through the app build**

Run:

```bash
./gradlew :app:assembleDebug -PDOVI_NATIVE_ENABLED=true -PDOVI_EXTRACTOR_HOOK_READY=true -PDOVI_ENABLE_REAL_LINK=true -PDOVI_LIBDOVI_PREBUILT_ROOT=third_party/libdovi
```

Expected:

```text
BUILD SUCCESSFUL
```

### Task 5: Document the Upgraded libdovi Provenance

**Files:**
- Modify: `docs-site/android/technical/libdovi.md`

- [ ] **Step 1: Add a provenance section**

Add this section after `## Build-time behavior`:

```markdown
## Bundled libdovi provenance

The tracked Android prebuilts under `third_party/libdovi` are built from Nexio's `dovi_tool` fork after merging upstream `quietvoid/dovi_tool` 2.3.2. They also include Nexio's C API conversion-mode patch:

- C API mode `2`: default profile 8.1 conversion
- C API mode `4`: profile 8.1 conversion with mapping metadata preserved

The app intentionally exposes preserve mapping as app mode `5`, then maps it to C API mode `4` in `app/src/main/cpp/dovi_bridge.cpp`. This keeps the UI/runtime setting distinct from raw `libdovi` C API values and avoids accidentally selecting profile 8.4 from the DV7-to-DV8.1 bridge.
```

- [ ] **Step 2: Run docs spelling sanity check**

Run:

```bash
rg -n "dovi_tool|libdovi|profile 8.1|preserve mapping|mode `5`" docs-site/android/technical/libdovi.md
```

Expected: the new provenance section is included in the output.

### Task 6: Final Verification and Root Commit

**Files:**
- Modify: `dovi_tool`
- Modify: `third_party/libdovi/**`
- Modify: `app/src/main/cpp/dovi_bridge.cpp`
- Modify: `app/src/main/java/com/nexio/tv/core/player/DoviBridge.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/player/DolbyVisionConversionModeSelectorTest.kt`
- Modify: `media/libraries/cpp_audiosink/kodi/xbmc/utils/BitstreamConverter.cpp`
- Modify: `docs-site/android/technical/libdovi.md`

- [ ] **Step 1: Run full relevant verification**

Run:

```bash
cargo test --manifest-path dovi_tool/Cargo.toml
cargo test --manifest-path dovi_tool/dolby_vision/Cargo.toml --features capi
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.player.DolbyVisionConversionModeSelectorTest --tests com.nexio.tv.core.player.DolbyVisionDiagnosticsTest
./gradlew :app:assembleDebug -PDOVI_NATIVE_ENABLED=true -PDOVI_EXTRACTOR_HOOK_READY=true -PDOVI_ENABLE_REAL_LINK=true -PDOVI_LIBDOVI_PREBUILT_ROOT=third_party/libdovi
```

Expected:

```text
test result: ok
BUILD SUCCESSFUL
BUILD SUCCESSFUL
```

- [ ] **Step 2: Inspect the final diff**

Run:

```bash
git -C dovi_tool log --oneline --left-right --cherry-pick HEAD...2.3.2
git status --short
git diff --stat
git diff -- app/src/main/cpp/dovi_bridge.cpp app/src/main/java/com/nexio/tv/core/player/DoviBridge.kt app/src/test/java/com/nexio/tv/core/player/DolbyVisionConversionModeSelectorTest.kt media/libraries/cpp_audiosink/kodi/xbmc/utils/BitstreamConverter.cpp docs-site/android/technical/libdovi.md
```

Expected:

```text
< 2a7238a fix: align C API conversion modes with documented mappings
```

Root `git status --short` should include the `dovi_tool` gitlink update, regenerated `third_party/libdovi` artifacts, and the app/native/docs changes.

- [ ] **Step 3: Commit root repository changes**

Run:

```bash
git add dovi_tool third_party/libdovi app/src/main/cpp/dovi_bridge.cpp app/src/main/java/com/nexio/tv/core/player/DoviBridge.kt app/src/test/java/com/nexio/tv/core/player/DolbyVisionConversionModeSelectorTest.kt media/libraries/cpp_audiosink/kodi/xbmc/utils/BitstreamConverter.cpp docs-site/android/technical/libdovi.md
git commit -m "chore: upgrade dovi_tool fork to 2.3.2"
```

Expected:

```text
[branch commit] chore: upgrade dovi_tool fork to 2.3.2
```

## Self-Review

- Spec coverage: The plan upgrades the fork to upstream `2.3.2`, preserves Nexio's custom C API mapping, rebuilds Android prebuilts, updates root submodule state, and inspects the release changes for concrete usage improvements.
- Usage improvements selected from the release review:
  - Use the generated `libdovi/rpu_parser.h` in `dovi_bridge.cpp` so C API signature drift is caught at compile time.
  - Fix `DoviBridge.convertDv7RpuToDv81` default mode from ToMEL mode `1` to DV8.1 mode `2`.
  - Prevent raw app mode `3` from reaching the DV7-to-DV8.1 JNI bridge as profile 8.4 conversion.
  - Add parse error checks before `dovi_rpu_get_header` in the Kodi call site, matching upstream C FFI error-handling improvements.
- Placeholder scan: clean.
- Type consistency: Kotlin app modes remain `2` and `5`; JNI maps `5` to C API mode `4`; the fork maps C API mode `4` to `To81MappingPreserved`.
