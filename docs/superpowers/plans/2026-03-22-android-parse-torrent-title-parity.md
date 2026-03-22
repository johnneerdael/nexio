# Android Parse-Torrent-Title Parity Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port the full `parse-torrent-title` filename parsing behavior into the Android TV app as native Kotlin and make `AioStrictFileParser` an adapter over that engine.

**Architecture:** Add a dedicated Kotlin handler engine that mirrors upstream `parse-torrent-title` concepts: ordered handlers, parse metadata, processors, validators, transforms, and a final parsed result model. Keep Nexio’s existing stream parser and formatter pipeline intact by mapping the new engine result into `AioStrictParsedFile`, then use parity tests derived from upstream test families to drive the port slice by slice.

**Tech Stack:** Kotlin, JUnit4, Android unit tests via Gradle, existing Nexio stream formatting/parsing models

---

### Task 1: Establish parity test scaffolding for the new engine

**Files:**
- Create: `app/src/test/java/com/nexio/tv/core/stream/ParseTorrentTitleParityFixtures.kt`
- Create: `app/src/test/java/com/nexio/tv/core/stream/ParseTorrentTitleEngineTitleParityTest.kt`
- Create: `app/src/test/java/com/nexio/tv/core/stream/ParseTorrentTitleEngineMediaTagsParityTest.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/stream/AioStrictFileParserParityTest.kt`

- [ ] **Step 1: Write the failing title/year/season/episode parity test**

Add a minimal fixture list based on upstream `title.test.ts`, `year.test.ts`, `seasons.test.ts`, `episodes.test.ts`, and `volumes.test.ts`. Include assertions for:
- `Movie.Title.2023.2160p.BluRay.x265-GROUP`
- `Show.Name.S02E03.1080p.WEB-DL.DDP5.1-GROUP`
- `Anime.Name.S01.1080p.BluRay.FLAC-Judas`
- `Series.1x07.720p.HDTV.x264-GROUP`

- [ ] **Step 2: Run the failing title parity test**

Run: `./gradlew --no-daemon :app:testDebugUnitTest --tests com.nexio.tv.core.stream.ParseTorrentTitleEngineTitleParityTest`

Expected: FAIL because the Kotlin engine classes do not exist yet.

- [ ] **Step 3: Write the failing media tag parity test**

Add fixture assertions for:
- resolution
- quality
- encode
- audio tags
- audio channels
- HDR/DV tags
- release group
- network/container/extension

Use filenames inspired by upstream `resolution.test.ts`, `quality.test.ts`, `codec.test.ts`, `audio.test.ts`, `hdr.test.ts`, `group.test.ts`, `network.test.ts`, `container.test.ts`.

- [ ] **Step 4: Run the failing media tags parity test**

Run: `./gradlew --no-daemon :app:testDebugUnitTest --tests com.nexio.tv.core.stream.ParseTorrentTitleEngineMediaTagsParityTest`

Expected: FAIL because the Kotlin engine classes do not exist yet.

- [ ] **Step 5: Commit parity scaffolding**

```bash
git add app/src/test/java/com/nexio/tv/core/stream/ParseTorrentTitleParityFixtures.kt app/src/test/java/com/nexio/tv/core/stream/ParseTorrentTitleEngineTitleParityTest.kt app/src/test/java/com/nexio/tv/core/stream/ParseTorrentTitleEngineMediaTagsParityTest.kt app/src/test/java/com/nexio/tv/core/stream/AioStrictFileParserParityTest.kt
git commit -m "test: add parse torrent title parity scaffolding"
```

### Task 2: Add the core parse-torrent-title engine model

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/stream/ptt/ParseTorrentTitleModels.kt`
- Create: `app/src/main/java/com/nexio/tv/core/stream/ptt/ParseTorrentTitleEngine.kt`
- Test: `app/src/test/java/com/nexio/tv/core/stream/ParseTorrentTitleEngineTitleParityTest.kt`

- [ ] **Step 1: Write one more failing test for ordered handler execution**

Add a single focused test proving title extraction stops at the first meaningful metadata boundary for a filename like `Movie.Title.2023.1080p.WEB-DL-GROUP`.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew --no-daemon :app:testDebugUnitTest --tests com.nexio.tv.core.stream.ParseTorrentTitleEngineTitleParityTest`

Expected: FAIL because `ParseTorrentTitleEngine` does not exist.

- [ ] **Step 3: Write minimal engine/result/model code**

Implement:
- parsed result equivalent to upstream `ParsedResult`
- parse metadata storage
- value-set helper
- handler descriptor types
- main ordered handler loop skeleton

Do not add all handlers yet. Only add enough structure for title-oriented handlers later.

- [ ] **Step 4: Run the title parity test**

Run: `./gradlew --no-daemon :app:testDebugUnitTest --tests com.nexio.tv.core.stream.ParseTorrentTitleEngineTitleParityTest`

Expected: still FAIL, but now because handlers/transforms are missing rather than missing classes.

- [ ] **Step 5: Commit engine skeleton**

```bash
git add app/src/main/java/com/nexio/tv/core/stream/ptt/ParseTorrentTitleModels.kt app/src/main/java/com/nexio/tv/core/stream/ptt/ParseTorrentTitleEngine.kt
git commit -m "feat: add parse torrent title engine skeleton"
```

### Task 3: Port transforms, validators, and processors

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/stream/ptt/ParseTorrentTitleTransforms.kt`
- Create: `app/src/main/java/com/nexio/tv/core/stream/ptt/ParseTorrentTitleValidators.kt`
- Create: `app/src/main/java/com/nexio/tv/core/stream/ptt/ParseTorrentTitleProcessors.kt`
- Test: `app/src/test/java/com/nexio/tv/core/stream/ParseTorrentTitleEngineTitleParityTest.kt`

- [ ] **Step 1: Write a failing transform-focused test**

Add a test for lowercase/uppercase/value-with-suffix/int-array/title cleanup behavior derived from upstream transform use.

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew --no-daemon :app:testDebugUnitTest --tests com.nexio.tv.core.stream.ParseTorrentTitleEngineTitleParityTest`

Expected: FAIL because transforms/validators/processors are incomplete.

- [ ] **Step 3: Implement minimal transforms/validators/processors**

Port only the helpers required by the currently failing tests:
- `toValue`
- `toLowercase`
- `toUppercase`
- `toBoolean`
- `toWithSuffix`
- `toIntArray`
- `toYear`
- `toDate`
- match validators and title-boundary processors used by early handlers

- [ ] **Step 4: Run the title parity test again**

Run: `./gradlew --no-daemon :app:testDebugUnitTest --tests com.nexio.tv.core.stream.ParseTorrentTitleEngineTitleParityTest`

Expected: FAIL only on missing handler coverage, not helper behavior.

- [ ] **Step 5: Commit helper layer**

```bash
git add app/src/main/java/com/nexio/tv/core/stream/ptt/ParseTorrentTitleTransforms.kt app/src/main/java/com/nexio/tv/core/stream/ptt/ParseTorrentTitleValidators.kt app/src/main/java/com/nexio/tv/core/stream/ptt/ParseTorrentTitleProcessors.kt
git commit -m "feat: port parse torrent title helper layers"
```

### Task 4: Port the title/year/season/episode/volume handler slice

**Files:**
- Create: `app/src/main/java/com/nexio/tv/core/stream/ptt/ParseTorrentTitleHandlers.kt`
- Test: `app/src/test/java/com/nexio/tv/core/stream/ParseTorrentTitleEngineTitleParityTest.kt`

- [ ] **Step 1: Write a failing test for volume and alternate episode formats**

Add fixtures for:
- `Show.Name.1x07.1080p...`
- `Anime.Name.Vol.03.1080p...`
- `Series.Name.S01...` season-pack style

- [ ] **Step 2: Run the title parity test**

Run: `./gradlew --no-daemon :app:testDebugUnitTest --tests com.nexio.tv.core.stream.ParseTorrentTitleEngineTitleParityTest`

Expected: FAIL on missing handler behavior.

- [ ] **Step 3: Port the upstream ordered handlers for early structure**

Port the handler groups needed for:
- title trimming
- year
- seasons
- episodes
- volumes
- episode code if required by the tests

Match upstream ordering semantics rather than rewriting them as one regex.

- [ ] **Step 4: Run the title parity test**

Run: `./gradlew --no-daemon :app:testDebugUnitTest --tests com.nexio.tv.core.stream.ParseTorrentTitleEngineTitleParityTest`

Expected: PASS.

- [ ] **Step 5: Commit the title slice**

```bash
git add app/src/main/java/com/nexio/tv/core/stream/ptt/ParseTorrentTitleHandlers.kt app/src/test/java/com/nexio/tv/core/stream/ParseTorrentTitleEngineTitleParityTest.kt
git commit -m "feat: port title and episode parse torrent title handlers"
```

### Task 5: Port the media-tag handler slice

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/stream/ptt/ParseTorrentTitleHandlers.kt`
- Test: `app/src/test/java/com/nexio/tv/core/stream/ParseTorrentTitleEngineMediaTagsParityTest.kt`

- [ ] **Step 1: Write a failing test for resolution, quality, codec, HDR, audio, channels**

Include representative filenames from upstream media-tag tests.

- [ ] **Step 2: Run the media tags parity test**

Run: `./gradlew --no-daemon :app:testDebugUnitTest --tests com.nexio.tv.core.stream.ParseTorrentTitleEngineMediaTagsParityTest`

Expected: FAIL because handlers are not implemented yet.

- [ ] **Step 3: Port the corresponding handler groups**

Port ordered handlers for:
- resolution
- quality
- codec
- audio
- channels
- HDR / DV / 3D / bit depth where used by upstream

- [ ] **Step 4: Run the media tags parity test**

Run: `./gradlew --no-daemon :app:testDebugUnitTest --tests com.nexio.tv.core.stream.ParseTorrentTitleEngineMediaTagsParityTest`

Expected: PASS.

- [ ] **Step 5: Commit the media-tag slice**

```bash
git add app/src/main/java/com/nexio/tv/core/stream/ptt/ParseTorrentTitleHandlers.kt app/src/test/java/com/nexio/tv/core/stream/ParseTorrentTitleEngineMediaTagsParityTest.kt
git commit -m "feat: port media tag parse torrent title handlers"
```

### Task 6: Port language/network/group/container and flag handlers

**Files:**
- Create: `app/src/test/java/com/nexio/tv/core/stream/ParseTorrentTitleEngineFlagsParityTest.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/stream/ptt/ParseTorrentTitleHandlers.kt`

- [ ] **Step 1: Write the failing flags parity test**

Cover:
- languages
- network
- group
- container / extension
- editions
- regraded
- repack
- uncensored
- unrated
- dubbed / subbed

- [ ] **Step 2: Run the flags parity test**

Run: `./gradlew --no-daemon :app:testDebugUnitTest --tests com.nexio.tv.core.stream.ParseTorrentTitleEngineFlagsParityTest`

Expected: FAIL on missing handlers.

- [ ] **Step 3: Port the upstream handler groups for these flags**

Keep handler order aligned with upstream `handlers.ts`.

- [ ] **Step 4: Run the flags parity test**

Run: `./gradlew --no-daemon :app:testDebugUnitTest --tests com.nexio.tv.core.stream.ParseTorrentTitleEngineFlagsParityTest`

Expected: PASS.

- [ ] **Step 5: Commit the flags slice**

```bash
git add app/src/test/java/com/nexio/tv/core/stream/ParseTorrentTitleEngineFlagsParityTest.kt app/src/main/java/com/nexio/tv/core/stream/ptt/ParseTorrentTitleHandlers.kt
git commit -m "feat: port parse torrent title flag handlers"
```

### Task 7: Port secondary upstream fields and edge-case handlers

**Files:**
- Create: `app/src/test/java/com/nexio/tv/core/stream/ParseTorrentTitleEngineSecondaryParityTest.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/stream/ptt/ParseTorrentTitleHandlers.kt`

- [ ] **Step 1: Write the failing secondary parity test**

Cover:
- date
- site
- size
- proper
- extended
- hardcoded
- complete
- convert
- ppv
- retail
- region
- sports if feasible from current Android use cases

- [ ] **Step 2: Run the secondary parity test**

Run: `./gradlew --no-daemon :app:testDebugUnitTest --tests com.nexio.tv.core.stream.ParseTorrentTitleEngineSecondaryParityTest`

Expected: FAIL on missing handlers.

- [ ] **Step 3: Port the remaining handler groups**

Keep parity-focused behavior, even if some fields are not yet consumed by the formatter.

- [ ] **Step 4: Run the secondary parity test**

Run: `./gradlew --no-daemon :app:testDebugUnitTest --tests com.nexio.tv.core.stream.ParseTorrentTitleEngineSecondaryParityTest`

Expected: PASS.

- [ ] **Step 5: Commit the secondary slice**

```bash
git add app/src/test/java/com/nexio/tv/core/stream/ParseTorrentTitleEngineSecondaryParityTest.kt app/src/main/java/com/nexio/tv/core/stream/ptt/ParseTorrentTitleHandlers.kt
git commit -m "feat: port secondary parse torrent title handlers"
```

### Task 8: Adapt AioStrictFileParser to the new engine

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/core/stream/AioStrictFileParser.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/stream/AioStrictFileParserParityTest.kt`

- [ ] **Step 1: Write the failing adapter test**

Add one focused test proving `AioStrictFileParser.parse(...)` maps engine results into `AioStrictParsedFile` without losing formatter-critical fields.

- [ ] **Step 2: Run the adapter test**

Run: `./gradlew --no-daemon :app:testDebugUnitTest --tests com.nexio.tv.core.stream.AioStrictFileParserParityTest`

Expected: FAIL because `AioStrictFileParser` still uses its internal regex parser.

- [ ] **Step 3: Replace parser internals with engine adapter logic**

Map:
- title
- year
- seasons / episodes / seasonPack
- resolution / quality / encode
- languages / subtitles
- audio / visual tags / channels
- release group
- network / container / extension
- editions and flags

- [ ] **Step 4: Run the adapter and engine parity tests**

Run: `./gradlew --no-daemon :app:testDebugUnitTest --tests com.nexio.tv.core.stream.AioStrictFileParserParityTest --tests com.nexio.tv.core.stream.ParseTorrentTitleEngineTitleParityTest --tests com.nexio.tv.core.stream.ParseTorrentTitleEngineMediaTagsParityTest --tests com.nexio.tv.core.stream.ParseTorrentTitleEngineFlagsParityTest --tests com.nexio.tv.core.stream.ParseTorrentTitleEngineSecondaryParityTest`

Expected: PASS.

- [ ] **Step 5: Commit the adapter switch**

```bash
git add app/src/main/java/com/nexio/tv/core/stream/AioStrictFileParser.kt app/src/test/java/com/nexio/tv/core/stream/AioStrictFileParserParityTest.kt
git commit -m "refactor: back aio strict file parser with parse torrent title engine"
```

### Task 9: Run full relevant verification

**Files:**
- No code changes required unless verification reveals issues

- [ ] **Step 1: Run the dedicated parser parity suite**

Run: `./gradlew --no-daemon :app:testDebugUnitTest --tests com.nexio.tv.core.stream.ParseTorrentTitleEngineTitleParityTest --tests com.nexio.tv.core.stream.ParseTorrentTitleEngineMediaTagsParityTest --tests com.nexio.tv.core.stream.ParseTorrentTitleEngineFlagsParityTest --tests com.nexio.tv.core.stream.ParseTorrentTitleEngineSecondaryParityTest --tests com.nexio.tv.core.stream.AioStrictFileParserParityTest`

Expected: PASS.

- [ ] **Step 2: Run the downstream formatter/presentation regression tests**

Run: `./gradlew --no-daemon :app:testDebugUnitTest --tests com.nexio.tv.core.stream.AioParseValueFactoryTest --tests com.nexio.tv.core.stream.AioTemplateFormatterTest --tests com.nexio.tv.core.stream.StreamPresentationEngineTest`

Expected: PASS.

- [ ] **Step 3: Run Kotlin compile verification**

Run: `./gradlew --no-daemon :app:compileDebugKotlin`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit verification-only follow-up fixes if needed**

```bash
git add app
git commit -m "test: finish parse torrent title parity verification"
```
