# gsonCodec Streaming JsonReader Refactor Design

Date: 2026-05-16

## Purpose

Eliminate the CLAUDE.md rule #3 anti-pattern from the `gsonCodec<T>()` factory in `app/src/main/java/com/nexio/tv/core/integration/IntegrationCodec.kt`. The current `decodeFn` calls `gson.fromJson(bytes.toString(Charsets.UTF_8), type)`, which materializes the cached body as a UTF-16 `String` and pins it through `StringReader.str` for the parse duration. ~93 `gsonCodec<X>()` instantiations across 15 provider files inherit the anti-pattern.

This spec is a single-point repair: rewrite the inline `decodeFn` to feed cached bytes through a streaming `JsonReader` over `InputStreamReader(ByteArrayInputStream(bytes))`. Every site that calls `gsonCodec<X>()` picks up the fix automatically — no provider edits, no test edits, no interface change, no cache-store change.

## Goals

- Eliminate the `bytes.toString(Charsets.UTF_8)` UTF-16 String allocation in `gsonCodec.decodeFn`.
- Eliminate the `StringReader.str` pinning that the existing parse path triggers.
- Behavioral equivalence with the previous decode path — same gson instance, same output type, same lenient-mode defaults.
- Lock the behavior with a new `GsonCodecStreamingTest` covering round-trip + non-ASCII + BOM + trailing-whitespace edge cases.
- No interface change. No cache-store change. No provider edits.

## Non-Goals

- Touching the `IntegrationCodec<T>` interface signature (`decode(bytes: ByteArray): T` stays).
- Eliminating the cache-store's `file.readBytes()` ByteArray allocation. That would require an interface change to `BufferedSource`/`InputStream` and a `LocalIntegrationCacheStore.readFresh/readStale` rewrite — explicitly out of scope; tracked as a deferred follow-up.
- Touching `StringIntegrationCodec`. Its String is the codec's **output**, not an input to a downstream parse. There is no `StringReader.str` pinning to remove. The doubled `ByteArray` + `String` allocation is structural to the String-output contract and only removable via the deferred interface refactor above.
- Touching `ByteArrayIntegrationCodec` or `FileCodec` (neither has the anti-pattern).
- Touching the new `FanartTvIntegrationProvider.documentCodec` — already fixed in commit `e0b29a10d` (uses `okio.Buffer().write(bytes)` + `Moshi.fromJson(BufferedSource)`).
- Gson reflection adapter caching. The 2026-05-11 ANR investigation noted gson reflection at ~50 MB/s allocation rate; that's a separate concern requiring adapter caching or migration to Moshi/kotlinx-serialization. Out of scope here.
- Migrating providers off gson. Out of scope.

## Existing Context

CLAUDE.md rule #3 documents the anti-pattern with heap evidence:

> Multiple concurrent reads of similarly-shaped TVDB cache entries (per Modern Home pipeline emission) appeared in the heap as 3 × 205 KiB transient `char[]` orphans plus the String backing storage — observed via `heaptrail -i ... -l --preview-bytes 65536` showing matching `{"airsDays":...}` content held by `StringReader.str`.

The committed reference fix pattern (used in `TraktMutationOutboxStore.kt:150`, `MetadataDiskCacheStore.kt`, `ResolvedDisplaySnapshotStore.kt`, `SimklLibrarySnapshotStore.kt`, `SyntheticHomeCatalogStore.kt`):

```kotlin
FileInputStream(file).use { fis ->
    BufferedReader(InputStreamReader(fis, Charsets.UTF_8)).use { br ->
        JsonReader(br).use { reader ->
            gson.fromJson<T>(reader, type)
        }
    }
}
```

This refactor lifts the same pattern into `gsonCodec`. Source is `ByteArrayInputStream(bytes)` instead of `FileInputStream(file)` because the cache store has already loaded the file into a ByteArray; we don't change that contract here.

## Blast Radius

Survey of `app/src/main/java/com/nexio/tv/`:
- **93** `gsonCodec<X>()` instantiations
- **15** provider files using `gsonCodec`:
  - `kitsu/KitsuIntegrationProvider.kt`
  - `mdblist/MDBListIntegrationProvider.kt`
  - `debrid/PremiumizeIntegrationProvider.kt`, `RealDebridIntegrationProvider.kt`, `TorBoxIntegrationProvider.kt`, `EasyDebridIntegrationProvider.kt`
  - `tmdb/TmdbIntegrationProvider.kt`
  - `omdb/OmdbIntegrationProvider.kt`
  - `trakt/TraktIntegrationProvider.kt`
  - `skip/IntroDbIntegrationProvider.kt`, `AnimeSkipIntegrationProvider.kt`, `AniSkipIntegrationProvider.kt`, `ArmIntegrationProvider.kt`
  - `subtitles/opensubtitles/OpenSubtitlesIntegrationProvider.kt`
  - `tvdb/TvdbIntegrationProvider.kt`

The heaviest payloads (largest expected savings):
- TVDB extended series — already documented as the 205 KiB char[] offender.
- Trakt collection-shows / watched-shows / calendar / trending lists — `List<...Dto>` payloads can be megabytes for users with large libraries.
- TMDB recommendation / discover / season-episodes pages — hundreds of KB per page.
- MDBList batch ratings (`EpisodeRatingsCacheDto`) — bulk season data.

## Architecture

Single-point repair. The factory function `gsonCodec<T>()` in `IntegrationCodec.kt` is rewritten. Every caller picks up the fix transparently.

```
BEFORE (every cache-hit decode):
  file.readBytes()                  → ByteArray (N bytes)
  bytes.toString(Charsets.UTF_8)    → String + UTF-16 char[] (2N bytes)
  gson.fromJson(string, type)       → wraps in StringReader → pinned for parse
  ────────────────────────────
  Peak transient: ~3N bytes per decode.

AFTER (every cache-hit decode):
  file.readBytes()                  → ByteArray (N bytes)   [unchanged]
  ByteArrayInputStream(bytes)       → wrapper, ~16 bytes
  InputStreamReader(..., UTF-8)     → CharsetDecoder buffer (~8 KB constant)
  JsonReader(reader)                → streaming tokenizer
  gson.fromJson(reader, type)       → consumes tokens directly
  ────────────────────────────
  Peak transient: ~N bytes for the cache body + ~8 KB constant.
  The 2N UTF-16 char[] and StringReader.str pinning are gone.
```

The interface (`decode(bytes: ByteArray): T`), the cache store (`spec.codec.decode(file.readBytes())`), the other codec implementations (`StringIntegrationCodec`, `ByteArrayIntegrationCodec`, `FileCodec`, `JsonCodec`), and every provider's `gsonCodec<X>()` call remain unchanged.

## Components

### Modified file

`app/src/main/java/com/nexio/tv/core/integration/IntegrationCodec.kt`

Rewrite the inline `decodeFn` lambda inside `gsonCodec<T>()`:

```kotlin
inline fun <reified T> gsonCodec(gson: Gson = Gson()): IntegrationCodec<T> =
    JsonCodec(
        encodeFn = { value -> gson.toJson(value).toByteArray(Charsets.UTF_8) },
        decodeFn = { bytes ->
            // CLAUDE.md rule #3: stream the body via JsonReader instead of
            // gson.fromJson(bytes.toString(Charsets.UTF_8), type). The String
            // overload wraps the body in a StringReader whose .str field pins
            // the entire UTF-16 char[] (~2x body size) for the parse duration.
            // Streaming through InputStreamReader + JsonReader keeps the body
            // as the original ByteArray plus an ~8 KB CharsetDecoder buffer.
            ByteArrayInputStream(bytes).use { byteStream ->
                InputStreamReader(byteStream, Charsets.UTF_8).use { reader ->
                    JsonReader(reader).use { jsonReader ->
                        gson.fromJson(jsonReader, object : TypeToken<T>() {}.type)
                    }
                }
            }
        }
    )
```

Imports added at top of file:
```kotlin
import com.google.gson.stream.JsonReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
```

The `.use {}` blocks ensure each layer is closed in reverse order, releasing the JsonReader's internal buffer immediately after parse. Matches the committed reference pattern in `TraktMutationOutboxStore.kt:150` etc.

### New test file

`app/src/test/java/com/nexio/tv/core/integration/GsonCodecStreamingTest.kt`

Locks the streaming behavior with four cases:

1. **Round-trip equivalence** — encode a representative DTO (nested fields, list, optional, Unicode string), decode the bytes, assert content equality. Locks the codec contract.
2. **Non-ASCII bodies** — decode a JSON body containing UTF-8 multi-byte characters (e.g. Japanese, emoji), assert the decoded string field reads back identical. Catches encoding regressions.
3. **UTF-8 BOM tolerance** — decode a body prefixed with the UTF-8 BOM (`EF BB BF`), assert successful parse. gson's String parser strips it transparently; the streaming path via `InputStreamReader(Charsets.UTF_8)` does the same.
4. **Strict-mode parity** — decode a body with trailing whitespace (valid) and a body with a trailing `,` after the last array element (invalid JSON). Confirm the streaming parser accepts the former and rejects the latter — matching `gson.fromJson(String)` default strictness exactly.

No mocking required. Uses the production `Gson()` default and the production `gsonCodec` factory.

### Unchanged files

- `IntegrationCodec.kt`'s other codecs (`StringIntegrationCodec`, `ByteArrayIntegrationCodec`, `FileCodec`, `JsonCodec`).
- The `IntegrationCodec<T>` interface itself.
- `LocalIntegrationCacheStore` and `IntegrationCacheStore` interface.
- All 15 provider files that call `gsonCodec<X>()`.
- All existing tests that fake codecs or cache stores.

## Risks & Rollback

**Risks (small, all known):**

1. **Gson strict-mode default.** `JsonReader(reader)` defaults `isLenient = false`. `gson.fromJson(String)` also produces a non-lenient parser by default. Behavior is identical. Risk: zero if gson version is unchanged.
2. **Close cascade.** `.use {}` handles each layer; `ByteArrayInputStream.close()` is a no-op (no cascade issues).
3. **Per-call InputStreamReader buffer.** ~8 KB transient stack-like allocation, GC'd immediately when the `.use {}` block exits. Net win for any body > 8 KB; structurally neutral for smaller ones.
4. **gson reflection cost is unchanged.** Out of scope (separate concern documented in the 2026-05-11 ANR notes).

**Rollback:** `git revert <sha>` of the single `IntegrationCodec.kt` commit. Zero downstream code depends on the new path; reverting restores the old `bytes.toString(Charsets.UTF_8)` behavior atomically.

## Test Plan

### New unit test — `GsonCodecStreamingTest`

Four cases described in Components. Sample structure:

```kotlin
class GsonCodecStreamingTest {
    private data class Sample(
        val name: String,
        val tags: List<String>,
        val optional: String? = null,
        val nested: Nested
    ) {
        data class Nested(val value: Int)
    }

    private val codec = gsonCodec<Sample>()

    @Test fun `round-trip preserves content`() { ... }
    @Test fun `decodes non-ASCII utf-8 body`() { ... }
    @Test fun `decodes body with utf-8 BOM`() { ... }
    @Test fun `rejects trailing comma (matches String overload strictness)`() { ... }
    @Test fun `accepts trailing whitespace`() { ... }
}
```

Run: `./gradlew :app:testUniversalDebugUnitTest -x generateIntegrationRuntimeAudit --tests "com.nexio.tv.core.integration.GsonCodecStreamingTest"`
Expected: BUILD SUCCESSFUL with `tests="5" failures="0" errors="0"`.

### Existing provider tests (regression guard)

No test changes. Re-run `:app:testUniversalDebugUnitTest` to confirm zero regressions across all gsonCodec users. Any test that fakes a cached body through `gsonCodec<X>()` exercises the new path; a failure indicates a real behavioral incompatibility worth investigating, not a test to silence.

### Integration-runtime audit

No expected changes. Run `:app:generateIntegrationRuntimeAudit` and confirm verdict remains `PASS` with all provider rows unchanged. The audit is structural; codec internals don't appear.

### No on-device verification required

Behavior is preserved by construction. Memory savings are visible in heap dumps and `Background concurrent GC` allocation rates but are not a ship gate. Optional: capture before/after `heaptrail` against a Modern Home soak for quantitative confirmation — informational only.

## Implementation Phases

This is a single small refactor — two commits total.

### Phase 1: rewrite `gsonCodec.decodeFn`

- Modify `app/src/main/java/com/nexio/tv/core/integration/IntegrationCodec.kt` as shown in Components.
- Add the three new imports.
- Build: `./gradlew :app:compileUniversalDebugKotlin` → BUILD SUCCESSFUL.
- Commit: `perf(integration-codec): stream JSON via gson JsonReader to honor CLAUDE.md rule #3`.

### Phase 2: lock behavior with the new test

- Create `app/src/test/java/com/nexio/tv/core/integration/GsonCodecStreamingTest.kt` with the four cases (5 tests total).
- Run: `./gradlew :app:testUniversalDebugUnitTest -x generateIntegrationRuntimeAudit --tests "com.nexio.tv.core.integration.GsonCodecStreamingTest"` → PASS.
- Run the full app test suite: `./gradlew :app:testUniversalDebugUnitTest -x generateIntegrationRuntimeAudit` → PASS (no provider regressions).
- Run the audit: `./gradlew :app:generateIntegrationRuntimeAudit` → verdict `PASS`.
- Commit: `test(integration-codec): lock streaming gsonCodec behavior`.

Both commits land on the `feat/fanarttv-peer-selectable-provider` branch (per the brainstorming Q2 answer to layer on the Fanart.tv PR). The Fanart-specific okio fix in commit `e0b29a10d` becomes the leading example of the streaming pattern; this refactor lifts the pattern to all providers in a single place.
