# YouTube StreamClient + ClosedCaptionClient Alignment Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the gaps between `InAppYouTubeExtractor` / `TrailerPlayer` and YoutubeExplode's reference implementation. Two independent subsystems are addressed: a reliable closed-caption pipeline (SRV3 fetch → typed parse → SRT serialize → ExoPlayer subtitle source) and five playback-extraction gaps (HEAD content-length verification, DASH manifest extraction, signature-timestamp passing on cipher requests, retry-with-cipher escalation, and a small follow-on for combined HEAD+tail-byte validation).

**Architecture:** Captions: switch from the unreliable `fmt=ttml` transcode to YouTube's stable `format=3&fmt=3` (SRV3) endpoint; parse SRV3 with the platform `XmlPullParser` (no new dependencies); serialize to SRT; cache one `.srt` file per `(videoId, languageCode)` under `cacheDir/trailer-subtitles/`; hand the `file://` URI to ExoPlayer via `MediaItem.SubtitleConfiguration` with `application/x-subrip` MIME. Playback: extend the existing extraction loop in `extractPlaybackSourceInternal` with verification + DASH + cipher-retry helpers — each one a small, self-contained addition that does not restructure the surrounding code.

**Tech Stack:** Kotlin, kotlinx.coroutines, `android.util.Xml` / `XmlPullParser` (platform), `HttpURLConnection`, ExoPlayer / Media3 `SubtitleConfiguration` (already wired), the cipher subsystem from commits `261a21e90..086c9be86`.

---

## Why this plan splits into two sections

Section A (captions) and Section B (playback alignment) target distinct subsystems and ship independently. The on-device smoke at the end of each section is the integration point — neither blocks the other.

If you have to pick one, ship **Section A first**: captions have never worked on this codebase, so the user-visible win is immediate and the surface area is small. Section B improves robustness on edge cases (404 URLs, missing DASH formats) but does not address a confirmed broken behavior.

You may also stop after Section A if smoke results show Section B is not needed for the current corpus of failing trailers (Project Hail Mary, Citadel, Ready or Not 2, The Drama).

---

## File Structure — Section A (captions)

**New files:**

| Path | Responsibility | LoC |
|---|---|---|
| `app/src/main/java/com/nexio/tv/data/trailer/captions/SrvCaptionParser.kt` | Parse YouTube SRV3 XML → typed `List<CaptionLine>`. Pure function, no IO. | ~80 |
| `app/src/main/java/com/nexio/tv/data/trailer/captions/SrtSerializer.kt` | Convert `List<CaptionLine>` → SRT string. Pure function. | ~50 |
| `app/src/main/java/com/nexio/tv/data/trailer/captions/CaptionLine.kt` | Data class: `offsetMs`, `durationMs`, `text`. Replaces the existing `kind`/`isTranslatable` shape inside the new pipeline only — `YouTubeCaptionTrack` (track metadata) stays as is. | ~10 |
| `app/src/main/java/com/nexio/tv/data/trailer/captions/TrailerSubtitleCache.kt` | Hilt `@Singleton`. Fetches SRV3 (HTTP), parses, serializes to SRT, writes to `cacheDir/trailer-subtitles/<videoId>-<lang>.srt`. Returns the cached `file://` URI on subsequent calls. | ~120 |

**Modified files:**

| Path | What changes |
|---|---|
| `app/src/main/java/com/nexio/tv/data/trailer/TrailerSubtitlePicker.kt` | Stop appending `&fmt=ttml`; the `TrailerSubtitleCache` constructs the SRV3 URL internally. Also strip `format=` from the baseUrl in `extractYouTubeCaptionTracks` (currently strips only `fmt` and `tlang`). |
| `app/src/main/java/com/nexio/tv/data/trailer/InAppYouTubeExtractor.kt` | One-line addition to `extractYouTubeCaptionTracks` to also strip `&format=[^&]*` from the baseUrl. |
| `app/src/main/java/com/nexio/tv/ui/components/TrailerPlayer.kt` | Replace the inline `buildTrailerSubtitleUrl(...) → MediaItem.SubtitleConfiguration(...)` block with a `TrailerSubtitleCache.ensure(...)` suspend call that returns the cached `file://` URI. MIME becomes `application/x-subrip`. |

**Test files:**

| Path | Coverage |
|---|---|
| `app/src/test/java/com/nexio/tv/data/trailer/captions/SrvCaptionParserTest.kt` | Parse a known-good SRV3 fixture; verify offsets, durations, and text. Plus malformed-input → empty list. |
| `app/src/test/java/com/nexio/tv/data/trailer/captions/SrtSerializerTest.kt` | Serialize 3 known-good `CaptionLine` entries → expected SRT string. |

---

## File Structure — Section B (playback alignment)

**Modified files only — no new files for Section B.**

| Path | What changes |
|---|---|
| `app/src/main/java/com/nexio/tv/data/trailer/InAppYouTubeExtractor.kt` | (B1) Add `verifyContentLength(url, signedClient): Long?` helper. (B2) Add a `dashManifestUrl` collection branch alongside the existing `hlsManifestUrl` collection at lines 304-307. (B3) Pass `cipherManifest.signatureTimestamp` to `fetchPlayerResponse` when the client is TVHTML5. (B4) On a "no candidates produced for any client" result, retry with `signatureTimestamp` propagated. |

**Test files:**

| Path | Coverage |
|---|---|
| `app/src/test/java/com/nexio/tv/data/trailer/InAppYouTubeExtractorPlaybackAlignmentTest.kt` | Unit tests for: `verifyContentLength` returns `null` on 404; DASH manifest URL is collected when present; signatureTimestamp is forwarded in the player-API payload when TVHTML5 is the client. |

---

## Scope Check

Two independent subsystems. Each ships on its own. Captions section listed first because it addresses confirmed broken behavior; playback section second.

**Deliberately out of scope:**
- AI translation of captions. This plan only ensures captions render natively. The SRT file is on disk under `cacheDir/trailer-subtitles/`; future AI translation can read that file, translate, write a sibling `-<targetLang>.srt`, and route the cache lookup through the translator before falling back to the source SRT.
- Compose-rendered subtitles overlay (an alternative to ExoPlayer's subtitle pipeline). The ExoPlayer path is simpler and gets us to working captions sooner.
- Full DASH manifest parsing — we collect the URL and hand it to ExoPlayer's DASH pipeline, which already exists in the codebase. Parsing each variant ourselves would duplicate ExoPlayer's manifest parser.
- HEAD/Range tail-byte validation (YoutubeExplode StreamClient.cs:67-83). This catches mis-sized streams. Folded into B1 as a single "is this URL playable" check — we do not split it into two separate HTTP round trips.

---

## Section A: Captions — SRV3 → SRT pipeline

### Task A1: `CaptionLine` data class

Trivial leaf. One file, no logic.

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/trailer/captions/CaptionLine.kt`

- [ ] **Step 1: Create the file**

```kotlin
package com.nexio.tv.data.trailer.captions

internal data class CaptionLine(
    val offsetMs: Long,
    val durationMs: Long,
    val text: String
)
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :app:compileUniversalDebugKotlin --console=plain 2>&1 | grep -E "^e: |^error:|BUILD FAIL" | head -10`
Expected: no `e:` lines.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/trailer/captions/CaptionLine.kt
git commit -m "feat(captions): CaptionLine data class for SRV3 parse output

Tiny leaf — offsetMs/durationMs/text. Used by SrvCaptionParser
(Task A2) to produce a structured list, and by SrtSerializer
(Task A3) to format SRT output."
```

---

### Task A2: SRV3 parser

YouTube's SRV3 format is XML with `<p>` elements:
```xml
<timedtext format="3">
  <body>
    <p t="1500" d="2700">Hello world</p>
    <p t="5000" d="2500">Second line</p>
  </body>
</timedtext>
```

Reference for the schema: `/Users/jneerdael/Scripts/YoutubeExplode/YoutubeExplode/Bridge/ClosedCaptionTrackResponse.cs` — confirms `@t` = offset ms, `@d` = duration ms, text content = caption text. Optional `<s>` sub-elements carry word-level timing; we ignore them (we only need line-level captions for subtitle display).

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/trailer/captions/SrvCaptionParser.kt`
- Create: `app/src/test/java/com/nexio/tv/data/trailer/captions/SrvCaptionParserTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/nexio/tv/data/trailer/captions/SrvCaptionParserTest.kt`:

```kotlin
package com.nexio.tv.data.trailer.captions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SrvCaptionParserTest {

    @Test
    fun `parses well-formed SRV3 into ordered caption lines`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <timedtext format="3">
              <body>
                <p t="1500" d="2700">Hello world</p>
                <p t="5000" d="2500">Second line</p>
                <p t="9000" d="1000">Third</p>
              </body>
            </timedtext>
        """.trimIndent()

        val lines = SrvCaptionParser.parse(xml)

        assertEquals(3, lines.size)
        assertEquals(CaptionLine(offsetMs = 1500, durationMs = 2700, text = "Hello world"), lines[0])
        assertEquals(CaptionLine(offsetMs = 5000, durationMs = 2500, text = "Second line"), lines[1])
        assertEquals(CaptionLine(offsetMs = 9000, durationMs = 1000, text = "Third"), lines[2])
    }

    @Test
    fun `skips paragraphs missing t or d attributes`() {
        val xml = """
            <timedtext format="3"><body>
              <p t="1000" d="2000">Valid</p>
              <p t="3000">Missing d</p>
              <p d="2000">Missing t</p>
              <p t="5000" d="1500">Also valid</p>
            </body></timedtext>
        """.trimIndent()

        val lines = SrvCaptionParser.parse(xml)
        assertEquals(2, lines.size)
        assertEquals("Valid", lines[0].text)
        assertEquals("Also valid", lines[1].text)
    }

    @Test
    fun `skips paragraphs with empty text`() {
        val xml = """
            <timedtext format="3"><body>
              <p t="1000" d="2000"></p>
              <p t="3000" d="2000">Real text</p>
              <p t="5000" d="2000">   </p>
            </body></timedtext>
        """.trimIndent()

        val lines = SrvCaptionParser.parse(xml)
        // YoutubeExplode skips empty strings but preserves whitespace-only.
        assertEquals(2, lines.size)
        assertEquals("Real text", lines[0].text)
        assertEquals("   ", lines[1].text)
    }

    @Test
    fun `concatenates s child element text with parent text`() {
        // YouTube ASR captions sometimes split a line into <s> child tokens.
        // For SRT we want the concatenated line.
        val xml = """
            <timedtext format="3"><body>
              <p t="1000" d="2000"><s>Hello </s><s>world</s></p>
            </body></timedtext>
        """.trimIndent()

        val lines = SrvCaptionParser.parse(xml)
        assertEquals(1, lines.size)
        assertEquals("Hello world", lines[0].text)
    }

    @Test
    fun `unescapes XML entities in text`() {
        val xml = """
            <timedtext format="3"><body>
              <p t="1000" d="2000">It&apos;s &quot;done&quot; &amp; ready</p>
            </body></timedtext>
        """.trimIndent()

        val lines = SrvCaptionParser.parse(xml)
        assertEquals("It's \"done\" & ready", lines[0].text)
    }

    @Test
    fun `returns empty list for malformed XML`() {
        val lines = SrvCaptionParser.parse("not actually xml <<<>>>")
        assertTrue(lines.isEmpty())
    }

    @Test
    fun `returns empty list for empty input`() {
        assertTrue(SrvCaptionParser.parse("").isEmpty())
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.trailer.captions.SrvCaptionParserTest" --console=plain 2>&1 | tail -10`
Expected: `Unresolved reference: SrvCaptionParser`.

- [ ] **Step 3: Implement the parser**

Create `app/src/main/java/com/nexio/tv/data/trailer/captions/SrvCaptionParser.kt`:

```kotlin
package com.nexio.tv.data.trailer.captions

import android.util.Xml
import java.io.StringReader
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException

internal object SrvCaptionParser {

    fun parse(srv3Xml: String): List<CaptionLine> {
        if (srv3Xml.isBlank()) return emptyList()
        return try {
            val parser = Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                setInput(StringReader(srv3Xml))
            }
            parseDocument(parser)
        } catch (e: XmlPullParserException) {
            emptyList()
        }
    }

    private fun parseDocument(parser: XmlPullParser): List<CaptionLine> {
        val out = mutableListOf<CaptionLine>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "p") {
                val t = parser.getAttributeValue(null, "t")?.toLongOrNull()
                val d = parser.getAttributeValue(null, "d")?.toLongOrNull()
                val text = readParagraphText(parser)
                if (t != null && d != null && text.isNotEmpty()) {
                    out += CaptionLine(offsetMs = t, durationMs = d, text = text)
                }
            }
            event = parser.next()
        }
        return out
    }

    /**
     * Reads the text content of a `<p>` element, concatenating any `<s>`
     * child elements (used by YouTube ASR captions for word-level timing
     * — we only care about the joined line). The parser is positioned at
     * the START_TAG of `<p>` on entry and at its END_TAG on exit.
     */
    private fun readParagraphText(parser: XmlPullParser): String {
        val builder = StringBuilder()
        while (parser.next() != XmlPullParser.END_TAG || parser.name != "p") {
            when (parser.eventType) {
                XmlPullParser.TEXT -> builder.append(parser.text)
                XmlPullParser.START_TAG -> {
                    if (parser.name == "s") {
                        // Accumulate <s> text; XmlPullParser will deliver its
                        // TEXT events in subsequent loop iterations, and we
                        // exit the inner branch when we hit its END_TAG.
                    }
                }
                XmlPullParser.END_DOCUMENT -> return builder.toString()
            }
        }
        return builder.toString()
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.trailer.captions.SrvCaptionParserTest" --console=plain 2>&1 | tail -10`

Expected: `BUILD SUCCESSFUL`, all 7 tests passing.

Note on `android.util.Xml` in JVM tests: it works under Robolectric. If a test fails with `Stub!`, add `@RunWith(RobolectricTestRunner::class)` + `@Config(manifest = Config.NONE, sdk = [33])` to the test class. The cipher port (commit `0ac28716a`) already uses this pattern for `android.net.Uri`, so the project pattern is established.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/trailer/captions/SrvCaptionParser.kt \
        app/src/test/java/com/nexio/tv/data/trailer/captions/SrvCaptionParserTest.kt
git commit -m "$(cat <<'EOF'
feat(captions): SRV3 XML parser using platform XmlPullParser

YouTube's format=3 timedtext response is XML with <p t=ms d=ms>text</p>
elements (and optional <s> sub-tokens we collapse into the parent
line). Parsed into a List<CaptionLine> via android.util.Xml's pull
parser — no new dependencies, no DOM materialization. Malformed input
returns an empty list rather than throwing.

Reference: YoutubeExplode/Bridge/ClosedCaptionTrackResponse.cs.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task A3: SRT serializer

SRT format:
```
1
00:00:01,500 --> 00:00:04,200
Hello world

2
00:00:05,000 --> 00:00:07,500
Second caption line
```

Index, then `HH:MM:SS,mmm --> HH:MM:SS,mmm`, then text, blank line.

YoutubeExplode also replaces literal `-->` in caption text with en-dashes to avoid SRT parser confusion (`ClosedCaptionClient.cs:170`). We do the same.

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/trailer/captions/SrtSerializer.kt`
- Create: `app/src/test/java/com/nexio/tv/data/trailer/captions/SrtSerializerTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/nexio/tv/data/trailer/captions/SrtSerializerTest.kt`:

```kotlin
package com.nexio.tv.data.trailer.captions

import org.junit.Assert.assertEquals
import org.junit.Test

class SrtSerializerTest {

    @Test
    fun `serializes well-formed lines`() {
        val lines = listOf(
            CaptionLine(offsetMs = 1500, durationMs = 2700, text = "Hello world"),
            CaptionLine(offsetMs = 5000, durationMs = 2500, text = "Second line"),
            CaptionLine(offsetMs = 9000, durationMs = 1000, text = "Third")
        )

        val srt = SrtSerializer.serialize(lines)

        val expected = """
            1
            00:00:01,500 --> 00:00:04,200
            Hello world

            2
            00:00:05,000 --> 00:00:07,500
            Second line

            3
            00:00:09,000 --> 00:00:10,000
            Third

        """.trimIndent()

        assertEquals(expected, srt)
    }

    @Test
    fun `formats hours minutes seconds milliseconds correctly`() {
        val lines = listOf(
            CaptionLine(offsetMs = 3_725_001L, durationMs = 500L, text = "Long")
        )
        val srt = SrtSerializer.serialize(lines)
        // 3,725,001 ms = 1h 02m 05.001s
        assertEquals(
            """
                1
                01:02:05,001 --> 01:02:05,501
                Long

            """.trimIndent(),
            srt
        )
    }

    @Test
    fun `replaces arrow sequence in caption text with en-dashes`() {
        val lines = listOf(
            CaptionLine(offsetMs = 0, durationMs = 1000, text = "Use --> for arrows")
        )
        val srt = SrtSerializer.serialize(lines)
        // Literal --> in text would confuse SRT readers. Replace with en-dashes.
        assertEquals(
            """
                1
                00:00:00,000 --> 00:00:01,000
                Use ––> for arrows

            """.trimIndent(),
            srt
        )
    }

    @Test
    fun `empty input yields empty string`() {
        assertEquals("", SrtSerializer.serialize(emptyList()))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.trailer.captions.SrtSerializerTest" --console=plain 2>&1 | tail -10`
Expected: `Unresolved reference: SrtSerializer`.

- [ ] **Step 3: Implement**

Create `app/src/main/java/com/nexio/tv/data/trailer/captions/SrtSerializer.kt`:

```kotlin
package com.nexio.tv.data.trailer.captions

internal object SrtSerializer {

    fun serialize(lines: List<CaptionLine>): String {
        if (lines.isEmpty()) return ""
        val sb = StringBuilder()
        for (i in lines.indices) {
            val line = lines[i]
            sb.append(i + 1).append('\n')
            sb.append(formatTimestamp(line.offsetMs))
                .append(" --> ")
                .append(formatTimestamp(line.offsetMs + line.durationMs))
                .append('\n')
            // Replace literal arrow sequences in the text with en-dashes
            // to avoid confusing SRT parsers; YoutubeExplode does the same
            // (ClosedCaptionClient.cs:170).
            sb.append(line.text.replace("-->", "––>")).append('\n')
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun formatTimestamp(totalMs: Long): String {
        val hours = totalMs / 3_600_000
        val minutes = (totalMs / 60_000) % 60
        val seconds = (totalMs / 1_000) % 60
        val ms = totalMs % 1_000
        return "%02d:%02d:%02d,%03d".format(hours, minutes, seconds, ms)
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.trailer.captions.SrtSerializerTest" --console=plain 2>&1 | tail -10`
Expected: `BUILD SUCCESSFUL`, all 4 tests passing.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/trailer/captions/SrtSerializer.kt \
        app/src/test/java/com/nexio/tv/data/trailer/captions/SrtSerializerTest.kt
git commit -m "$(cat <<'EOF'
feat(captions): serialize CaptionLine list to SRT format

SRT format: index, HH:MM:SS,mmm --> HH:MM:SS,mmm, text, blank line.
Literal '-->' in caption text is replaced with '––>' (en-dashes) to
avoid confusing SRT parsers — same workaround YoutubeExplode applies
in ClosedCaptionClient.cs:170 (since SRT has no escape mechanism).
ExoPlayer's SubripParser handles this output.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task A4: `TrailerSubtitleCache`

Fetches SRV3 over HTTP, parses, serializes, writes once per `(videoId, languageCode)`, returns the cached `file://` URI on later calls.

URL construction differs from our current code in three places:
- Strip both `format=` and `fmt=` from the baseUrl (we currently only strip `fmt`).
- Append `format=3&fmt=3` (matching YoutubeExplode's belt-and-suspenders).
- If `kind=asr` was in the baseUrl, preserve it (we currently drop it indirectly by stripping nothing tied to it — defensively re-strip and re-add).

Cache key: `<videoId>-<languageCode>[-<tlang>]` so translated tracks are cached separately from native ones.

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/trailer/captions/TrailerSubtitleCache.kt`

- [ ] **Step 1: Implement**

Create `app/src/main/java/com/nexio/tv/data/trailer/captions/TrailerSubtitleCache.kt`:

```kotlin
package com.nexio.tv.data.trailer.captions

import android.content.Context
import com.nexio.tv.data.trailer.SelectedTrailerCaptionTrack
import com.nexio.tv.data.trailer.YOUTUBE_STABLE_WEB_USER_AGENT
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Fetches a YouTube SRV3 caption track, parses + converts to SRT, and
 * caches the result under `cacheDir/trailer-subtitles/`. Returns a
 * `file://` URI suitable for `MediaItem.SubtitleConfiguration`.
 *
 * Cache key is `<videoId>-<languageCode>[-<tlang>]`. Translated tracks
 * cache independently from native tracks.
 */
@Singleton
class TrailerSubtitleCache @Inject constructor(
    @ApplicationContext private val applicationContext: Context
) {

    private val mutex = Mutex()
    private val baseDir: File by lazy {
        File(applicationContext.cacheDir, "trailer-subtitles").apply { mkdirs() }
    }

    /**
     * Returns a `file://` URI to an SRT file for the given track, fetching
     * and caching on first call. Returns `null` on network or parse failure.
     */
    suspend fun ensure(videoId: String, selected: SelectedTrailerCaptionTrack): String? =
        mutex.withLock {
            val target = cacheFileFor(videoId, selected)
            if (target.exists() && target.length() > 0) {
                return@withLock target.toURI().toString()
            }
            val srv3Url = buildSrv3Url(selected)
            val xml = fetchSrv3(srv3Url) ?: return@withLock null
            val lines = SrvCaptionParser.parse(xml)
            if (lines.isEmpty()) return@withLock null
            val srt = SrtSerializer.serialize(lines)
            try {
                target.writeText(srt, Charsets.UTF_8)
            } catch (e: IOException) {
                return@withLock null
            }
            target.toURI().toString()
        }

    private fun cacheFileFor(videoId: String, selected: SelectedTrailerCaptionTrack): File {
        val key = buildString {
            append(videoId).append('-').append(selected.languageCode.replace('/', '_'))
            selected.translateTo?.takeIf { it.isNotBlank() }?.let {
                append('-').append(it.replace('/', '_'))
            }
        }
        return File(baseDir, "$key.srt")
    }

    /**
     * Construct the SRV3 URL: strip pre-existing `format=` and `fmt=` from
     * the baseUrl (YouTube web baseUrls carry `format=json3` which we don't
     * want), then append `format=3&fmt=3` and any `tlang=` translation.
     */
    internal fun buildSrv3Url(selected: SelectedTrailerCaptionTrack): String {
        val cleaned = selected.baseUrl
            .replace(Regex("&format=[^&]*"), "")
            .replace(Regex("&fmt=[^&]*"), "")
            .replace(Regex("&tlang=[^&]*"), "")
        val separator = if (cleaned.contains('?')) "&" else "?"
        val builder = StringBuilder(cleaned)
        builder.append(separator).append("format=3&fmt=3")
        selected.translateTo
            ?.takeIf { it.isNotBlank() }
            ?.let { builder.append("&tlang=").append(it) }
        return builder.toString()
    }

    private suspend fun fetchSrv3(url: String): String? = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 8_000
            // timedtext lives on www.youtube.com — use the web profile UA.
            // Per-host dispatch in TrailerPlayer already routes web headers
            // for non-googlevideo hosts; here we are outside ExoPlayer so we
            // need to set them manually.
            setRequestProperty("User-Agent", YOUTUBE_STABLE_WEB_USER_AGENT)
            setRequestProperty("Referer", "https://www.youtube.com/")
            setRequestProperty("Origin", "https://www.youtube.com")
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        }
        try {
            if (conn.responseCode != 200) return@withContext null
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            null
        } finally {
            conn.disconnect()
        }
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :app:compileUniversalDebugKotlin --console=plain 2>&1 | grep -E "^e: |^error:|BUILD FAIL" | head -10`
Expected: no `e:` lines.

Note on visibility: `SelectedTrailerCaptionTrack` is `internal` (declared in `TrailerSubtitlePicker.kt:3`). `TrailerSubtitleCache` is `public` because Hilt needs to construct it for the public `TrailerPlayer` Composable. A public method exposing an `internal` parameter type is invalid — you may need to widen `SelectedTrailerCaptionTrack` to public, OR mark `TrailerSubtitleCache.ensure` as `internal` and inject the cache via a `@PublishedApi internal` path. **Simpler choice:** widen `SelectedTrailerCaptionTrack` to public. The cipher port made the same call for `CipherManifest` (commit `caf5b571d`).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/trailer/captions/TrailerSubtitleCache.kt
# If SelectedTrailerCaptionTrack visibility needed widening:
git add app/src/main/java/com/nexio/tv/data/trailer/TrailerSubtitlePicker.kt
git commit -m "$(cat <<'EOF'
feat(captions): TrailerSubtitleCache fetches SRV3, writes SRT to disk

@Singleton Hilt cache, keyed by <videoId>-<languageCode>[-<tlang>].
ensure() fetches /api/timedtext with format=3&fmt=3, parses SRV3 via
SrvCaptionParser, serializes via SrtSerializer, writes to
cacheDir/trailer-subtitles/<key>.srt, and returns the file:// URI.
Subsequent calls return the cached URI without re-fetching.

URL construction differs from the prior fmt=ttml approach: strip
pre-existing format=, fmt=, and tlang= from the baseUrl (YouTube web
baseUrls carry format=json3 by default), then append
format=3&fmt=3[&tlang=<target>]. This matches YoutubeExplode's
ClosedCaptionController.cs belt-and-suspenders approach.

Web-profile HTTP headers (UA, Origin, Referer, Accept-Language) are
set explicitly on the fetch — we're outside ExoPlayer's resolver here.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task A5: Wire `TrailerSubtitleCache` into `TrailerPlayer`

Replace the inline `MediaItem.SubtitleConfiguration` block at `TrailerPlayer.kt:120-130` with a `LaunchedEffect` that calls `TrailerSubtitleCache.ensure(...)` and writes the result to a `MutableState<MediaItem.SubtitleConfiguration?>`.

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/components/TrailerPlayer.kt:120-130`

This requires the `TrailerSubtitleCache` to be accessible from a Composable. We do not have a Hilt-from-Composable pattern documented in this file, but the codebase already uses `EntryPointAccessors` elsewhere — `grep -rn "EntryPointAccessors" app/src/main/` to confirm. If used, follow the same pattern; otherwise pass the cache as a parameter from the call site.

**Discovery step required.** This task has one degree of freedom (how to obtain the cache instance) that must be resolved by reading existing code. Do that first.

- [ ] **Step 1: Discover the Hilt-from-Composable pattern in the codebase**

Run: `grep -rn "EntryPointAccessors\|@EntryPoint\|@InstallIn(SingletonComponent::class)" /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/ --include="*.kt" | head -20`

Pick the pattern observed in the most-similar surface (a Composable in `ui/components/` or `ui/screens/` that depends on a singleton). Note the chosen approach in your implementation comments. If no such pattern exists, surface this as DONE_WITH_CONCERNS and propose passing the cache as a parameter from the nearest non-Composable caller.

- [ ] **Step 2: Add the entry point (if EntryPointAccessors pattern chosen)**

In `app/src/main/java/com/nexio/tv/ui/components/TrailerPlayer.kt`, add near the top:

```kotlin
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
internal interface TrailerSubtitleCacheEntryPoint {
    fun trailerSubtitleCache(): com.nexio.tv.data.trailer.captions.TrailerSubtitleCache
}
```

(Imports may live inline as above to avoid colliding with existing imports — adjust to project style if `grep` shows that block-importing is the convention.)

- [ ] **Step 3: Replace the inline subtitle config**

Locate the current block at `TrailerPlayer.kt:120-130`:

```kotlin
    val subtitleConfig = remember(trailerCaptions, preferredSubtitleLanguage) {
        val selected = pickTrailerCaptionTrack(trailerCaptions, preferredSubtitleLanguage)
            ?: return@remember null
        val format = TrailerSubtitleFormat.TTML
        val subtitleUrl = buildTrailerSubtitleUrl(selected, format)
        MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitleUrl))
            .setMimeType(format.mimeType)
            .setLanguage(selected.languageCode)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
    }
```

Replace with:

```kotlin
    val subtitleCache = remember(context) {
        dagger.hilt.android.EntryPointAccessors
            .fromApplication(context, TrailerSubtitleCacheEntryPoint::class.java)
            .trailerSubtitleCache()
    }
    val videoIdForSubtitles = trailerUrl?.let { extractYouTubeVideoIdForSubtitles(it) }
    var subtitleConfig by remember(trailerCaptions, preferredSubtitleLanguage, videoIdForSubtitles) {
        mutableStateOf<MediaItem.SubtitleConfiguration?>(null)
    }
    LaunchedEffect(trailerCaptions, preferredSubtitleLanguage, videoIdForSubtitles) {
        subtitleConfig = null
        val videoId = videoIdForSubtitles ?: return@LaunchedEffect
        val selected = pickTrailerCaptionTrack(trailerCaptions, preferredSubtitleLanguage)
            ?: return@LaunchedEffect
        val cachedUri = subtitleCache.ensure(videoId, selected) ?: return@LaunchedEffect
        subtitleConfig = MediaItem.SubtitleConfiguration.Builder(Uri.parse(cachedUri))
            .setMimeType(androidx.media3.common.MimeTypes.APPLICATION_SUBRIP)
            .setLanguage(selected.languageCode)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
    }
```

Add at the bottom of `TrailerPlayer.kt` (or a sibling file if this grows):

```kotlin
/**
 * Extracts the YouTube video ID from a watch URL, mirroring the logic in
 * InAppYouTubeExtractor.extractVideoId. Kept local here to avoid pulling
 * the entire extractor into a Composable.
 */
private fun extractYouTubeVideoIdForSubtitles(youtubeUrl: String): String? {
    val v = Regex("""[?&]v=([a-zA-Z0-9_-]{11})""").find(youtubeUrl)?.groupValues?.getOrNull(1)
    if (v != null) return v
    val short = Regex("""youtu\.be/([a-zA-Z0-9_-]{11})""").find(youtubeUrl)?.groupValues?.getOrNull(1)
    return short
}
```

- [ ] **Step 4: Verify compilation**

Run: `./gradlew :app:compileUniversalDebugKotlin --console=plain 2>&1 | grep -E "^e: |^error:|BUILD FAIL" | head -10`
Expected: no `e:` lines.

- [ ] **Step 5: Run targeted tests**

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.trailer.captions.*" --tests "com.nexio.tv.data.trailer.*" --console=plain 2>&1 | tail -20`
Expected: all caption + trailer tests pass.

- [ ] **Step 6: Remove the now-dead `buildTrailerSubtitleUrl` + `TrailerSubtitleFormat.TTML`**

Only if no other caller exists. Check first:

```bash
grep -rn "buildTrailerSubtitleUrl\|TrailerSubtitleFormat" /Users/jneerdael/Scripts/nexio/app/src/main/ /Users/jneerdael/Scripts/nexio/app/src/test/ 2>&1 | grep -v "TrailerSubtitlePicker.kt"
```

If the only remaining match is the `@Deprecated` `buildTrailerSubtitleVttUrl` (already an alias), delete `buildTrailerSubtitleUrl`, `TrailerSubtitleFormat`, and `buildTrailerSubtitleVttUrl` from `TrailerSubtitlePicker.kt`. If something else references them, leave as is and surface this in your report.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/components/TrailerPlayer.kt
# Plus any cleanup of TrailerSubtitlePicker.kt if Step 6 found callers
git commit -m "$(cat <<'EOF'
feat(captions): route trailer subtitles through TrailerSubtitleCache

Replace the inline buildTrailerSubtitleUrl + MediaItem.SubtitleConfig
block with a LaunchedEffect that fetches the SRV3 track via
TrailerSubtitleCache.ensure(...) and produces a file:// URI plus
application/x-subrip MIME for ExoPlayer's SubripParser.

The cache writes once per (videoId, languageCode, tlang) tuple under
cacheDir/trailer-subtitles/. Subsequent compositions of the same
trailer return the cached URI without re-fetching.

The prior fmt=ttml path is removed — that endpoint was the suspected
cause of 'captions never worked on a single trailer'. SRV3 is what
YouTube's web player and YoutubeExplode use, and it serves reliably.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task A6: On-device caption smoke

**Files:** none — operational verification.

- [ ] **Step 1: Build & install**

Run: `./gradlew :app:installUniversalDebug --console=plain 2>&1 | tail -3`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Force-stop, launch, select profile (CLAUDE.md rule #8)**

```bash
adb -s 192.168.50.98:5555 shell am force-stop com.nexiodebug.tv
adb -s 192.168.50.98:5555 logcat -c
adb -s 192.168.50.98:5555 shell monkey -p com.nexiodebug.tv 1
sleep 5
adb -s 192.168.50.98:5555 shell input keyevent KEYCODE_DPAD_CENTER
sleep 30
```

- [ ] **Step 3: Play one trailer known to have captions (e.g. Project Hail Mary)**

Navigate to detail; let the trailer play ~10s.

- [ ] **Step 4: Verify the SRT was written**

```bash
adb -s 192.168.50.98:5555 shell run-as com.nexiodebug.tv ls -la cache/trailer-subtitles/ 2>&1 | head -10
```

Expected: one or more `.srt` files. If empty, the fetch or parse failed — check logcat for `TrailerSubtitleCache` log lines (none are added by the plan — if you want them, add `Log.d(TAG, ...)` to `TrailerSubtitleCache` callsites; do this as a small follow-on commit if useful).

- [ ] **Step 5: Verify ExoPlayer rendered the captions**

```bash
adb -s 192.168.50.98:5555 logcat -d -t 6000 | grep -iE "SubripParser|TrailerSubtitleCache|file:.*trailer-subtitles|SubtitleConfiguration" | tail -30
```

Expected at minimum: a `SubripParser` log line (ExoPlayer logs the parser it picks). Visually confirm captions appear on screen.

- [ ] **Step 6: Decision gate**

**Branch A:** Captions render → ship Section A. Move to Section B or stop here.

**Branch B:** SRT file written but no captions on screen → ExoPlayer subtitle path issue. Inspect the `.srt` content (`adb shell run-as ... cat`) to confirm it's well-formed. Most likely the `MediaItem.SubtitleConfiguration` is correct but the player's track selector isn't picking it. Check `trackSelectionParameters` settings in `TrailerPlayer.kt:193-195` — `setForceHighestSupportedBitrate(true)` may interact with subtitle selection unexpectedly; try adding `.setPreferredTextLanguage(selected.languageCode)`.

**Branch C:** SRT file missing → fetch or parse failed. Test the URL directly:

```bash
adb -s 192.168.50.98:5555 shell logcat -d | grep -iE "TrailerSubtitleCache|api/timedtext|format=3" | tail -20
```

Try the constructed URL in a host browser. If the response is empty XML, the baseUrl we extracted is stale or the track has expired. If it's a 403, the host UA/headers may need adjustment beyond what `fetchSrv3` already sets.

---

## Section B: Playback alignment (5 gaps from YoutubeExplode StreamClient)

### Task B1: HEAD-verify content-length on candidate URLs

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/trailer/InAppYouTubeExtractor.kt`
- Create: `app/src/test/java/com/nexio/tv/data/trailer/InAppYouTubeExtractorVerifyContentLengthTest.kt`

Adds a `verifyContentLength(url, client): Long?` helper that issues a HEAD against the URL. On non-200 (especially 404 — common for stale stream URLs) it returns null; on 200 with no Content-Length header it returns null; on 200 with Content-Length, it issues a tiny Range request for the last 2 bytes — if THAT 404s or mismatches, the URL is rejected. This matches YoutubeExplode `StreamClient.cs:55-83`.

The check is opt-in: we only call it for adaptive entries that DID pass cipher decryption (since cipher URLs are the ones most likely to be stale at use-time). Direct URLs from iOS/ANDROID/TVHTML5 typically work without verification — skip them to avoid extra round trips.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/nexio/tv/data/trailer/InAppYouTubeExtractorVerifyContentLengthTest.kt`:

```kotlin
package com.nexio.tv.data.trailer

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class InAppYouTubeExtractorVerifyContentLengthTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `returns content length on successful HEAD + tail-byte verify`() {
        // HEAD returns 200 + Content-Length
        server.enqueue(MockResponse().setResponseCode(200).addHeader("Content-Length", "1024"))
        // GET Range request for tail bytes returns 206 Partial Content
        server.enqueue(MockResponse().setResponseCode(206))

        val length = verifyContentLengthForTest(server.url("/stream").toString())
        assertEquals(1024L, length)
    }

    @Test
    fun `returns null when HEAD returns 404`() {
        server.enqueue(MockResponse().setResponseCode(404))

        val length = verifyContentLengthForTest(server.url("/stream").toString())
        assertEquals(null, length)
    }

    @Test
    fun `returns null when tail-byte GET returns 404`() {
        // HEAD ok, tail GET fails
        server.enqueue(MockResponse().setResponseCode(200).addHeader("Content-Length", "1024"))
        server.enqueue(MockResponse().setResponseCode(404))

        val length = verifyContentLengthForTest(server.url("/stream").toString())
        assertEquals(null, length)
    }

    @Test
    fun `returns null when HEAD has no Content-Length header`() {
        server.enqueue(MockResponse().setResponseCode(200))

        val length = verifyContentLengthForTest(server.url("/stream").toString())
        assertEquals(null, length)
    }
}
```

> **Note on test helper:** `verifyContentLengthForTest` is a public-for-test wrapper around the new `private suspend fun verifyContentLength` in `InAppYouTubeExtractor.kt`. Add an `internal fun verifyContentLengthForTest(url: String): Long? = runBlocking { verifyContentLength(url, signedClientUserAgent = "test-ua") }` next to the existing `CLIENTS_FOR_TEST` accessor at line 131. The pattern is established.
>
> The test depends on `com.squareup.okhttp3:mockwebserver`. If not already a `testImplementation`, add it to `app/build.gradle.kts`:
> ```kotlin
> testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
> ```
> (Check existing version with `grep okhttp app/build.gradle.kts` — match the production okhttp version if present.)

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.trailer.InAppYouTubeExtractorVerifyContentLengthTest" --console=plain 2>&1 | tail -10`
Expected: `Unresolved reference: verifyContentLengthForTest` (or the function and the helper haven't been written yet).

- [ ] **Step 3: Add `verifyContentLength` to the extractor**

Add as a `private suspend fun` member inside `class InAppYouTubeExtractor` in `app/src/main/java/com/nexio/tv/data/trailer/InAppYouTubeExtractor.kt`:

```kotlin
    /**
     * HEAD + tail-byte-Range verification. YoutubeExplode StreamClient.cs:55-83.
     * Returns null when the URL is unusable (404, missing Content-Length, or
     * tail-byte mismatch — a known YouTube quirk where the reported length
     * doesn't match the actual stream).
     */
    private suspend fun verifyContentLength(
        url: String,
        signedClientUserAgent: String
    ): Long? = withContext(Dispatchers.IO) {
        // HEAD
        val headConn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "HEAD"
            connectTimeout = 3_000
            readTimeout = 5_000
            setRequestProperty("User-Agent", signedClientUserAgent)
        }
        val contentLength: Long = try {
            if (headConn.responseCode != 200) return@withContext null
            headConn.getHeaderField("Content-Length")?.toLongOrNull() ?: return@withContext null
        } catch (e: IOException) {
            return@withContext null
        } finally {
            headConn.disconnect()
        }
        if (contentLength < 2) return@withContext contentLength

        // Tail-byte Range
        val tailUrl = url
        val tailConn = (URL(tailUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 3_000
            readTimeout = 5_000
            setRequestProperty("User-Agent", signedClientUserAgent)
            setRequestProperty("Range", "bytes=${contentLength - 2}-${contentLength - 1}")
        }
        try {
            // 200 (server ignored Range) and 206 (Partial Content) both indicate
            // the URL is alive; 404 means the URL is broken at the tail.
            if (tailConn.responseCode !in setOf(200, 206)) return@withContext null
            contentLength
        } catch (e: IOException) {
            null
        } finally {
            tailConn.disconnect()
        }
    }

    // For tests only.
    internal suspend fun verifyContentLengthForTest(url: String): Long? =
        verifyContentLength(url, signedClientUserAgent = "test-ua")
```

Make sure the existing `import` block at the top of `InAppYouTubeExtractor.kt` contains `import java.net.URL`, `import java.net.HttpURLConnection`, and `import java.io.IOException`. (`URL` is already imported per line 19; check the others.)

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.trailer.InAppYouTubeExtractorVerifyContentLengthTest" --console=plain 2>&1 | tail -10`
Expected: `BUILD SUCCESSFUL`, all 4 tests passing.

- [ ] **Step 5: Wire into format collection — adaptive only, cipher-only entries**

In `app/src/main/java/com/nexio/tv/data/trailer/InAppYouTubeExtractor.kt`, locate the adaptive-format collection block (around line 357, where the cipher path was wired in Task 6b of the prior plan). After the URL is resolved (post-cipher-decode), add an opt-in verification only for entries that came from the cipher path:

```kotlin
// Original (post-Task-6b, post-Rule-#4-fix):
val adaptiveFormats = streamingData.listMapValue("adaptiveFormats")
for (i in adaptiveFormats.indices) {
    val format = adaptiveFormats[i]
    val directUrl = format.stringValue("url")
    val resolvedUrl = directUrl ?: run {
        val signatureCipher = format.stringValue("signatureCipher")
            ?: format.stringValue("cipher")
            ?: return@run null
        val manifest = cipherManifestDeferred.await() ?: return@run null
        SignatureCipherDecoder.decode(signatureCipher, manifest)
    } ?: continue

    // NEW: verify cipher-resolved URLs against HEAD + tail byte. Direct
    // iOS/ANDROID URLs are assumed live (low historical 404 rate).
    val url = if (directUrl == null) {
        verifyContentLength(resolvedUrl, signedClientUserAgent = client.userAgent)?.let { resolvedUrl }
            ?: continue
    } else {
        resolvedUrl
    }
    // ... rest of loop body unchanged: build StreamCandidate, etc.
}
```

You will need to be careful to preserve the exact rest-of-loop-body logic. Show the **complete** post-edit loop in the commit message if it's clearer than the diff.

- [ ] **Step 6: Run targeted tests after wiring**

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.trailer.*" --console=plain 2>&1 | tail -20`
Expected: all pass. No iterator-pinning regressions (the new `verifyContentLength` await is inside the same indexed-for loop, which is fine).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/trailer/InAppYouTubeExtractor.kt \
        app/src/test/java/com/nexio/tv/data/trailer/InAppYouTubeExtractorVerifyContentLengthTest.kt
# Plus app/build.gradle.kts if mockwebserver was added
git commit -m "$(cat <<'EOF'
feat(extractor): HEAD + tail-byte verification of cipher-resolved URLs

YoutubeExplode StreamClient.cs:55-83 issues a HEAD then a Range
request for the last two bytes to verify the URL is actually playable.
The tail-byte check catches a known YouTube quirk where the reported
Content-Length doesn't match the actual stream tail (404 on the last
bytes).

Wire the check into the adaptive-format loop ONLY for entries that
required cipher decoding (the ones most likely to be stale at use
time). Direct iOS/ANDROID/TVHTML5 URLs are not verified — they have a
low historical 404 rate and the verification round trip would cost
~50ms per format with no benefit.

testImplementation: mockwebserver for HTTP test fixtures.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task B2: DASH manifest URL extraction

YouTube's adaptive streaming has two manifest types: HLS (which we extract from `streamingData.hlsManifestUrl`) and DASH (which lives at `streamingData.dashManifestUrl`). When DASH is present, it typically has the richest adaptive set including 4K variants. ExoPlayer parses DASH natively via the `androidx.media3.exoplayer.dash` artifact.

YoutubeExplode `StreamClient.cs:235-251` fetches and parses the DASH manifest to enumerate stream metadata. We do not need to parse it — we hand the manifest URL to ExoPlayer which handles parsing + adaptive switching. We DO need to collect the URL alongside HLS so it becomes a candidate.

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/trailer/InAppYouTubeExtractor.kt` lines 304-307 (HLS collection block) and the `selectPreferredCombinedTrailerUrl` helper at line 112.

- [ ] **Step 1: Confirm the DASH ExoPlayer artifact is on the classpath**

Run: `grep -n "media3-exoplayer-dash\|exoplayer-dash\|DashMediaSource" /Users/jneerdael/Scripts/nexio/app/build.gradle.kts /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/components/TrailerPlayer.kt 2>&1 | head -10`

Expected: at least one match. If none, add to `app/build.gradle.kts`:

```kotlin
implementation("androidx.media3:media3-exoplayer-dash:${mediaThreeVersion}")
```

(Use the existing media3 version constant — search `media3-exoplayer\b` for the pattern.)

Make this a precondition: if DASH is missing, surface this as DONE_WITH_CONCERNS BEFORE further edits — adding a top-level dependency is a separate decision.

- [ ] **Step 2: Add a DASH manifest URL collection branch**

In `app/src/main/java/com/nexio/tv/data/trailer/InAppYouTubeExtractor.kt`, locate lines 303-307:

```kotlin
                val streamingData = playerResponse.mapValue("streamingData") ?: continue
                val hlsManifestUrl = streamingData.stringValue("hlsManifestUrl")
                if (!hlsManifestUrl.isNullOrBlank()) {
                    manifestUrls += Triple(client.key, client.priority, hlsManifestUrl)
                }
```

Replace with:

```kotlin
                val streamingData = playerResponse.mapValue("streamingData") ?: continue
                val hlsManifestUrl = streamingData.stringValue("hlsManifestUrl")
                if (!hlsManifestUrl.isNullOrBlank()) {
                    manifestUrls += Triple(client.key, client.priority, hlsManifestUrl)
                }
                val dashManifestUrl = streamingData.stringValue("dashManifestUrl")
                if (!dashManifestUrl.isNullOrBlank()) {
                    manifestUrls += Triple(client.key, client.priority + 10, dashManifestUrl)
                }
```

The `+ 10` priority offset for DASH means HLS is still preferred when both are present (matches NewPipe's preference). If you discover during smoke testing that DASH consistently outperforms HLS, swap the offsets.

> **Caveat:** the existing `manifestUrls` is typed `MutableList<Triple<String, Int, String>>` — three positional values for client/priority/url. There is no field distinguishing HLS from DASH. ExoPlayer's `DefaultMediaSourceFactory` infers manifest type from URL or MIME type — for DASH the URL typically ends with `.mpd`. Search for how `manifestUrls` is consumed: `grep -n "manifestUrls" app/src/main/java/com/nexio/tv/data/trailer/InAppYouTubeExtractor.kt`. If consumption assumes HLS, the consumer must be updated to dispatch on URL suffix. Most likely the answer is: pass the URL string through unchanged (because the rest of the pipeline already routes via `MediaItem.fromUri(...)` which `DefaultMediaSourceFactory` resolves to `DashMediaSource` for `.mpd`).

- [ ] **Step 3: Compile + targeted tests**

Run:
```
./gradlew :app:compileUniversalDebugKotlin --console=plain 2>&1 | grep -E "^e: |^error:|BUILD FAIL" | head -10
./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.trailer.*" --console=plain 2>&1 | tail -20
```
Expected: no `e:` lines; all trailer tests pass.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/trailer/InAppYouTubeExtractor.kt
# Plus app/build.gradle.kts if DASH artifact was added
git commit -m "$(cat <<'EOF'
feat(extractor): collect dashManifestUrl alongside hlsManifestUrl

When streamingData.dashManifestUrl is present (typically for TVHTML5
responses), add it to the manifest-candidate list with a slightly
lower priority than HLS from the same client. ExoPlayer's
DefaultMediaSourceFactory dispatches to DashMediaSource for .mpd URLs
automatically, so no consumer changes are needed.

Reference: YoutubeExplode StreamClient.cs:235-251 (their code parses
the manifest to enumerate streams; ExoPlayer parses for us).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task B3: Pass `signatureTimestamp` on player request when cipher is in play

YouTube's player API requires `signatureTimestamp` in the request playback context when the response will contain cipher fields (otherwise the player API may refuse with `Failed to extract any player response`). Currently `fetchPlayerResponse` does not pass it.

YoutubeExplode `StreamClient.cs:289` passes `cipherManifest.SignatureTimestamp` when retrying with cipher.

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/trailer/InAppYouTubeExtractor.kt` — `fetchPlayerResponse` signature and the call site.

- [ ] **Step 1: Locate `fetchPlayerResponse`**

```bash
grep -n "fun fetchPlayerResponse\|fetchPlayerResponse(" /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/data/trailer/InAppYouTubeExtractor.kt | head -10
```

Note the exact line numbers — the function may be `private suspend fun fetchPlayerResponse(apiKey, videoId, client, visitorData, cookieHeader)`. We will add a sixth parameter.

- [ ] **Step 2: Add `signatureTimestamp` parameter**

In `app/src/main/java/com/nexio/tv/data/trailer/InAppYouTubeExtractor.kt`, edit the signature of `fetchPlayerResponse`:

```kotlin
    private suspend fun fetchPlayerResponse(
        apiKey: String,
        videoId: String,
        client: YouTubeClient,
        visitorData: String?,
        cookieHeader: String?,
        signatureTimestamp: String? = null
    ): Map<*, *> {
```

Inside the function, find the JSON payload construction (the `mapOf("context" to mapOf(...), "videoId" to ..., ...)`). Add a conditional branch to embed the signatureTimestamp:

```kotlin
        val payload = buildMap<String, Any?> {
            put("context", mapOf("client" to client.context))
            put("videoId", videoId)
            if (cookieHeader != null) {
                // ... existing cookieHeader handling ...
            }
            // NEW: include playbackContext.contentPlaybackContext.signatureTimestamp
            // when we have a manifest. The player API uses this to bind the response
            // to a specific cipher version.
            if (signatureTimestamp != null) {
                put("playbackContext", mapOf(
                    "contentPlaybackContext" to mapOf(
                        "signatureTimestamp" to signatureTimestamp.toIntOrNull()
                    )
                ))
            }
        }
```

Adjust to match the existing payload-construction style (it may currently be a single inline `mapOf(...)` — convert to `buildMap` only if needed).

- [ ] **Step 3: Pass signatureTimestamp at the call site**

In `extractPlaybackSourceInternal`, the call at line 278 currently looks like:

```kotlin
                val playerResponse = fetchPlayerResponse(
                    apiKey = apiKey,
                    videoId = videoId,
                    client = client,
                    visitorData = watchConfig.visitorData,
                    cookieHeader = null
                )
```

Change to (only for clients that ship cipher: TVHTML5 — and any future cipher-requiring client added later):

```kotlin
                val sigTimestampForClient = if (client.key == "tv") {
                    cipherManifestDeferred.await()?.signatureTimestamp
                } else null
                val playerResponse = fetchPlayerResponse(
                    apiKey = apiKey,
                    videoId = videoId,
                    client = client,
                    visitorData = watchConfig.visitorData,
                    cookieHeader = null,
                    signatureTimestamp = sigTimestampForClient
                )
```

> The `await()` here is the first time `cipherManifestDeferred` is awaited for non-format reasons. It blocks per TVHTML5 client iteration on the manifest. Since the manifest fetch was kicked off in parallel with the loop, by the time we hit the TVHTML5 iteration the manifest is usually already complete. If not, we wait — this is the cost we already accept for cipher decoding.

- [ ] **Step 4: Compile + targeted tests**

```
./gradlew :app:compileUniversalDebugKotlin --console=plain 2>&1 | grep -E "^e: |^error:|BUILD FAIL" | head -10
./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.trailer.*" --console=plain 2>&1 | tail -20
```
Expected: clean.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/trailer/InAppYouTubeExtractor.kt
git commit -m "$(cat <<'EOF'
feat(extractor): pass signatureTimestamp on TVHTML5 player requests

YouTube's player API binds cipher responses to a specific cipher
version via context.contentPlaybackContext.signatureTimestamp. When
this is omitted on a cipher-requiring client, the API may return an
empty streamingData or refuse the request.

Forward the cipher manifest's signatureTimestamp on the TVHTML5
player API call (the only cipher-requiring client we currently use).
The await() pins on the parallel-fetched cipher manifest — usually
zero wait since the manifest is ready by the time we reach TVHTML5
in the client loop.

Reference: YoutubeExplode StreamClient.cs:289.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task B4: Retry-with-cipher on no-playable-stream

`StreamClient.cs:280-295` tries the cipher-less path first and escalates to cipher-with-signature-timestamp on `VideoUnplayableException`. In our flow, we already iterate through every client (including cipher-required ones), so the structural escalation is largely absent. The remaining gap: when ALL clients produce zero candidates, we currently bail with `null`. We should retry the TVHTML5 client with `signatureTimestamp` explicitly forced even if the cipher manifest async hasn't completed yet (block on it).

This task is small but defensive — most "no candidates" cases are unrecoverable (geographic blocks, age gates, etc.) and a retry will yield zero candidates again. It is worth doing for completeness.

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/trailer/InAppYouTubeExtractor.kt`

- [ ] **Step 1: Locate the no-candidates exit**

Find the point where `extractPlaybackSourceInternal` returns null because no `StreamCandidate` was produced. Typically near the end of the function, after the per-client loop.

```bash
grep -n "return null\|return@coroutineScope null" /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/data/trailer/InAppYouTubeExtractor.kt | head -10
```

Note the line(s).

- [ ] **Step 2: Decide if Task B3 already addresses this**

If Task B3 is already merged, the TVHTML5 path passes `signatureTimestamp` on first try. A separate retry is then redundant. Confirm by reading: after B3, is there a path where TVHTML5 was called WITHOUT `signatureTimestamp` and could be re-called WITH? If not, this task is a no-op and should be marked DONE with a note saying B3 subsumed it.

- [ ] **Step 3: If a meaningful retry path exists, add it; otherwise mark this task complete with a note**

If you find a meaningful retry path, implement it as a single conditional `if (allCandidates.isEmpty() && cipherManifestDeferred.await() != null) { retry once }`. Keep the retry bounded to ONE attempt — no infinite recursion.

If no meaningful retry path exists, write a commit that documents the analysis:

```bash
git commit --allow-empty -m "$(cat <<'EOF'
chore(extractor): retry-with-cipher (B4) subsumed by signatureTimestamp (B3)

Task B4 in the StreamClient alignment plan called for a retry-with-
cipher escalation matching YoutubeExplode StreamClient.cs:280-295.
After Task B3 (signatureTimestamp on TVHTML5 player request), the
first-pass extraction already passes the cipher binding token, so a
separate retry path provides no additional signal.

Leaving this commit as a no-op tombstone so the plan's task list shows
B4 considered and discharged.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

(Use `--allow-empty` only for the tombstone case. If you actually implement the retry, do a normal commit.)

---

### Task B5: On-device playback smoke

**Files:** none — operational verification.

- [ ] **Step 1: Build + install**

Run: `./gradlew :app:installUniversalDebug --console=plain 2>&1 | tail -3`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Force-stop, launch, select profile**

```bash
adb -s 192.168.50.98:5555 shell am force-stop com.nexiodebug.tv
adb -s 192.168.50.98:5555 logcat -c
adb -s 192.168.50.98:5555 shell monkey -p com.nexiodebug.tv 1
sleep 5
adb -s 192.168.50.98:5555 shell input keyevent KEYCODE_DPAD_CENTER
sleep 30
```

- [ ] **Step 3: Play the four canonical trailers**

Project Hail Mary → Citadel → Ready or Not 2 → The Drama. ~15s each.

- [ ] **Step 4: Inspect logcat**

```bash
adb -s 192.168.50.98:5555 logcat -d -t 6000 | grep -iE "verifyContentLength|dashManifestUrl|signatureTimestamp|TrailerSubtitleCache|OmxVideoDecoder.*nFrameWidth|Response code: 4|Source error" | tail -80
```

Look for:
- `verifyContentLength` calls only on cipher-resolved URLs, no calls on direct URLs.
- `dashManifestUrl` collected when TVHTML5 responds with one.
- `signatureTimestamp` non-null on TVHTML5 player API requests (or absence of "empty streamingData" warnings).
- `OmxVideoDecoder ... nFrameWidth=1920 nFrameHeight=1080` (or 4K) — adaptive resolution rising.
- Zero new `Response code: 4XX` lines.

- [ ] **Step 5: Decision gate**

**Branch A — All trailers play, captions render, no 404s:** ship. Push commits, archive Section B's individual smoke tests under `docs/superpowers/notes/2026-05-11-stream-client-alignment-smoke.md` with a one-line summary of what played.

**Branch B — Captions work but playback regresses (e.g. HEAD verification killed a working URL):** the HEAD-verify path is too aggressive. Either tighten its trigger condition (only verify when `format.stringValue("contentLength") == null`) or revert just Task B1.

**Branch C — DASH manifest plays but with codec issues:** ExoPlayer DASH artifact may be old or missing a decoder. Update `media3-exoplayer-dash` to the current version of `media3-exoplayer`.

**Branch D — TVHTML5 still produces no usable streams after B3:** the signatureTimestamp may be wrong, or the player API may be rejecting for an orthogonal reason (region lock, age gate). Capture the request body and response from logcat (`fetchPlayerResponse` already logs at debug level — increase if necessary) and inspect.

---

## Self-Review

**Spec coverage:**

| Spec item | Task | Covered |
|---|---|---|
| HEAD-verify content-length (StreamClient.cs:55-66) | B1 | ✅ |
| Range-validate tail bytes (StreamClient.cs:67-83) | B1 (folded into single helper) | ✅ |
| DASH manifest URL extraction (StreamClient.cs:235-251) | B2 | ✅ |
| Retry-with-cipher on VideoUnplayableException (StreamClient.cs:280-295) | B4 (likely subsumed by B3) | ✅ |
| Pass signatureTimestamp on player request (StreamClient.cs:289) | B3 | ✅ |
| Captions: SRV3 fetch + parse + SRT | A1, A2, A3, A4 | ✅ |
| Wire SRT into ExoPlayer | A5 | ✅ |
| Smoke each section | A6, B5 | ✅ |
| Strip `format=` from baseUrl (caption bugfix prerequisite) | A4 Step 1 (in `buildSrv3Url`) | ✅ |

**Placeholder scan:** None of the patterns from the "No Placeholders" section are present. Every step has concrete code or a verifiable command.

**Type consistency:**
- `CaptionLine(offsetMs: Long, durationMs: Long, text: String)` — same shape in Task A1, A2 test, A3 test, A3 impl.
- `SrvCaptionParser.parse(srv3Xml: String): List<CaptionLine>` — same signature in A2 test and impl.
- `SrtSerializer.serialize(lines: List<CaptionLine>): String` — same signature in A3 test and impl.
- `TrailerSubtitleCache.ensure(videoId: String, selected: SelectedTrailerCaptionTrack): String?` — same signature in A4 and A5.
- `verifyContentLength(url: String, signedClientUserAgent: String): Long?` — same signature in B1 impl and call site.
- `fetchPlayerResponse(..., signatureTimestamp: String? = null)` — sixth parameter is consistent in B3 declaration and call site.

**Known follow-ups (out of scope):**
- AI translation of captions: read the cached SRT file, translate, write a sibling file, route via locale-aware cache key. Pattern is established by `TrailerSubtitleCache.cacheFileFor`.
- Compose-rendered captions overlay: stronger control over styling, AI-translation hooking, and avoids ExoPlayer subtitle path entirely. Larger change; defer until the SRT path is proven.
- Full DASH parsing for stream metadata: would let us prefer specific variants from DASH manifests. ExoPlayer's automatic adaptive selection is sufficient for trailers.
- WEB / web_safari client for the highest-quality muxed formats: requires POToken handling, deferred per the original cipher plan's escalation path.

---

## Section ordering and execution choices

The plan supports two sane orderings:

**(a) Captions first (A1–A6) then playback (B1–B5):** prioritizes the confirmed broken behavior. Recommended.

**(b) Playback first (B1–B5) then captions (A1–A6):** if smoke from the cipher port (commit `086c9be86`) already showed Project Hail Mary playing at 1080p, the captions work would be the only outstanding item — start with A.

Either way, each section finishes with its own smoke before moving to the next. Do not interleave A and B tasks — they touch independent files except for `InAppYouTubeExtractor.kt`'s caption-baseUrl strip line, which lives in A4's `buildSrv3Url`.
