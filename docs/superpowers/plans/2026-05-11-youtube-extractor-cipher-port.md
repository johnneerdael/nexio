# YouTube Extractor — YoutubeExplode-Style Cipher Port

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port `YoutubeExplode`'s pure-Kotlin signatureCipher decryption (regex-based parser + 3 primitive string-operation classes) into our extractor so adaptive streams with `signatureCipher` fields can be used. Verify that this — combined with our existing `YoutubeChunkedDataSourceFactory` range-chunking — is sufficient to play trailers at full quality without WebView, Duktape, or poToken.

**Architecture:** Tyrrrz's `YoutubeExplode` (the most widely-used C# YouTube extraction library, actively maintained, ~10M downloads) avoids any JavaScript engine. Instead it parses YouTube's player JS with regex to identify the three primitive string operations that comprise the cipher (swap, splice, reverse), re-implements each in native C#, builds an ordered `CipherManifest` per player-JS revision, and applies the operations natively to encrypted `s=` signature blobs. For YouTube's `n=` throttle parameter, YoutubeExplode doesn't descramble — it chunks downloads into ~9.8MB segments via `&range=from-to` URL parameters, which sidesteps the per-session throttle entirely. We already do this chunking in `YoutubeChunkedDataSourceFactory.kt`, so the throttle problem is already solved on our side. The remaining work is just the cipher decryption: ~6 small files port from `~/Scripts/YoutubeExplode/YoutubeExplode/Bridge/`, ~50 lines of integration into `InAppYouTubeExtractor`, and a sanity smoke test.

**Tech Stack:** Kotlin (no JVM/native deps), Kotlin `Regex`, our existing `fetchTransport` for the player-JS HTTP fetch, our existing `YoutubeChunkedDataSourceFactory` for throttle bypass. **Zero new dependencies.**

---

## Why this approach over the Duktape and poToken plans

Two earlier plans tackled this problem with much heavier machinery:

1. `2026-05-11-youtube-potoken-provider.md` — ~600 LoC + a hidden WebView + dependency on Google's BotGuard challenge endpoint (breaks 2–3 times/year).
2. `2026-05-11-youtube-extractor-duktape-port.md` — ~700 LoC + Duktape native lib (~200KB) + JS engine API.

YoutubeExplode demonstrates a third path: **no JS execution at all.** Just regex-parse the player JS to figure out which of three primitive ops are being applied in what order, then apply them natively. Their cipher pipeline is 5 files totaling ~80 lines of substantive code; the throttle problem they handle by range-chunking the download — exactly what our `YoutubeChunkedDataSourceFactory` already does.

**The hypothesis this plan tests:** for trailer playback (public unauthenticated content), the only YouTube anti-extraction layer we genuinely need to handle is `signatureCipher` decryption. The n-throttle is bypassed by chunked downloads. poToken anti-abuse may or may not be enforced — empirically, YoutubeExplode works without it for the same kind of public content we're targeting.

**If smoke testing reveals this is sufficient**: ship and archive both heavier plans. **If videos still fail**: those failures are the specific videos that require poToken; escalate per the existing poToken plan as Phase 2. Either way this work is non-wasted because the cipher decryption is necessary for handling `signatureCipher` fields, which poToken alone wouldn't address.

---

## File Structure

**New files (port from `~/Scripts/YoutubeExplode/YoutubeExplode/Bridge/`):**

| Our path | Upstream source | Lines | Adaptations |
|---|---|---|---|
| `app/src/main/java/com/nexio/tv/data/trailer/cipher/CipherOperation.kt` | `Bridge/Cipher/ICipherOperation.cs` | ~5 | Interface — single method `fun decipher(input: String): String` |
| `app/src/main/java/com/nexio/tv/data/trailer/cipher/SwapCipherOperation.kt` | `Bridge/Cipher/SwapCipherOperation.cs` | ~10 | Verbatim port — swap chars[0] and chars[index] |
| `app/src/main/java/com/nexio/tv/data/trailer/cipher/SpliceCipherOperation.kt` | `Bridge/Cipher/SpliceCipherOperation.cs` | ~10 | Verbatim — drop first N chars |
| `app/src/main/java/com/nexio/tv/data/trailer/cipher/ReverseCipherOperation.kt` | `Bridge/Cipher/ReverseCipherOperation.cs` | ~10 | Verbatim — `String.reversed()` |
| `app/src/main/java/com/nexio/tv/data/trailer/cipher/CipherManifest.kt` | `Bridge/Cipher/CipherManifest.cs` | ~20 | Holds `signatureTimestamp` + ordered list of ops; `decipher` is just `fold` |
| `app/src/main/java/com/nexio/tv/data/trailer/cipher/PlayerSourceParser.kt` | `Bridge/PlayerSource.cs` | ~160 | The regex-based extractor that builds a `CipherManifest` from raw player JS. Regex strings are portable C#→Kotlin; only syntax around them changes |
| `app/src/main/java/com/nexio/tv/data/trailer/cipher/PlayerSourceCache.kt` | NEW (shared with Duktape plan) | ~50 | Fetches `/s/player/.../base.js` and caches per-URL within a session. Identical concept to NewPipe's `PoTokenProviderImpl` player-JS handling but tiny because we don't need to run the JS |
| `app/src/main/java/com/nexio/tv/data/trailer/cipher/SignatureCipherDecoder.kt` | derived from `StreamClient.cs:88–113` | ~50 | Given a `signatureCipher` blob (URL-encoded `s=...&sp=sig&url=...`) and a `CipherManifest`, produce the deciphered playable URL |

**Modified files:**

| Path | What changes |
|---|---|
| `app/src/main/java/com/nexio/tv/data/trailer/InAppYouTubeExtractor.kt` | Constructor adds `PlayerSourceCache`. In `extractPlaybackSourceInternal`, fetch player JS in parallel with the player API calls. In the per-format loops, when a format has `signatureCipher` but no `url`, decipher via `SignatureCipherDecoder` instead of dropping the entry. |

**Test files:**

| Path | Coverage |
|---|---|
| `app/src/test/java/com/nexio/tv/data/trailer/cipher/CipherOperationTest.kt` | Each of the 3 primitive ops applied to known input → known output. |
| `app/src/test/java/com/nexio/tv/data/trailer/cipher/CipherManifestTest.kt` | A 3-op manifest applied to a known input produces a known output (left-to-right fold). |
| `app/src/test/java/com/nexio/tv/data/trailer/cipher/SignatureCipherDecoderTest.kt` | Parses a sample `signatureCipher=s=ENCRYPTED&sp=sig&url=...` blob and produces the URL with `sig=DECRYPTED`. |
| `app/src/test/java/com/nexio/tv/data/trailer/cipher/PlayerSourceParserTest.kt` | (Skipped initially.) Real player JS is large (~3 MB); add a regression fixture only after the first on-device smoke confirms regexes match. Optional. |

---

## Scope Check

One cohesive subsystem. No split needed.

**Deliberately out of scope:**
- **n-throttle descrambling**: bypassed by `YoutubeChunkedDataSourceFactory`. If chunking isn't sufficient, the next escalation is the Duktape plan — but YoutubeExplode's empirical track record says chunking is enough for typical streams.
- **poToken (BotGuard)**: separate plan, executed only if smoke reveals videos that need it.
- **WEB-client extraction**: currently we use iOS + Android. WEB-client adaptive formats often carry `signatureCipher` instead of `url` — this plan unlocks handling them, but doesn't change which clients we hit. Adding the WEB client is a small follow-up if useful.

---

## Task 1: Port the three cipher operations + the interface

Trivial leaf classes. Port verbatim from C#.

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/trailer/cipher/CipherOperation.kt`
- Create: `app/src/main/java/com/nexio/tv/data/trailer/cipher/SwapCipherOperation.kt`
- Create: `app/src/main/java/com/nexio/tv/data/trailer/cipher/SpliceCipherOperation.kt`
- Create: `app/src/main/java/com/nexio/tv/data/trailer/cipher/ReverseCipherOperation.kt`
- Create: `app/src/test/java/com/nexio/tv/data/trailer/cipher/CipherOperationTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/nexio/tv/data/trailer/cipher/CipherOperationTest.kt`:

```kotlin
package com.nexio.tv.data.trailer.cipher

import org.junit.Assert.assertEquals
import org.junit.Test

class CipherOperationTest {

    @Test
    fun `swap exchanges char at index 0 with char at index`() {
        // "abcdef" with index=3 → swap chars[0]='a' with chars[3]='d' → "dbcaef"
        assertEquals("dbcaef", SwapCipherOperation(3).decipher("abcdef"))
    }

    @Test
    fun `splice drops first N chars`() {
        // "abcdef" with index=2 → "cdef"
        assertEquals("cdef", SpliceCipherOperation(2).decipher("abcdef"))
    }

    @Test
    fun `reverse mirrors the string`() {
        assertEquals("fedcba", ReverseCipherOperation.decipher("abcdef"))
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.trailer.cipher.CipherOperationTest" --console=plain 2>&1 | tail -8`
Expected: `Unresolved reference: SwapCipherOperation`.

- [ ] **Step 3: Implement the interface and 3 operations**

Create `app/src/main/java/com/nexio/tv/data/trailer/cipher/CipherOperation.kt`:

```kotlin
package com.nexio.tv.data.trailer.cipher

internal interface CipherOperation {
    fun decipher(input: String): String
}
```

Create `app/src/main/java/com/nexio/tv/data/trailer/cipher/SwapCipherOperation.kt`:

```kotlin
package com.nexio.tv.data.trailer.cipher

/**
 * Swap operation: exchanges chars[0] with chars[index]. Port of
 * YoutubeExplode's SwapCipherOperation.cs.
 */
internal data class SwapCipherOperation(val index: Int) : CipherOperation {
    override fun decipher(input: String): String {
        val chars = input.toCharArray()
        val tmp = chars[0]
        chars[0] = chars[index]
        chars[index] = tmp
        return String(chars)
    }
}
```

Create `app/src/main/java/com/nexio/tv/data/trailer/cipher/SpliceCipherOperation.kt`:

```kotlin
package com.nexio.tv.data.trailer.cipher

/**
 * Splice operation: drops the first `index` characters. Port of
 * YoutubeExplode's SpliceCipherOperation.cs.
 */
internal data class SpliceCipherOperation(val index: Int) : CipherOperation {
    override fun decipher(input: String): String = input.substring(index)
}
```

Create `app/src/main/java/com/nexio/tv/data/trailer/cipher/ReverseCipherOperation.kt`:

```kotlin
package com.nexio.tv.data.trailer.cipher

/**
 * Reverse operation: reverses the input string. Port of YoutubeExplode's
 * ReverseCipherOperation.cs.
 */
internal object ReverseCipherOperation : CipherOperation {
    override fun decipher(input: String): String = input.reversed()
}
```

- [ ] **Step 4: Run tests to confirm pass**

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.trailer.cipher.CipherOperationTest" --console=plain 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`, 3 tests passing.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/trailer/cipher/
git add app/src/test/java/com/nexio/tv/data/trailer/cipher/CipherOperationTest.kt
git commit -m "feat(cipher): the three YouTube signature cipher primitives

Verbatim port of YoutubeExplode's Bridge/Cipher operations: swap
exchanges chars[0] with chars[index], splice drops the first N chars,
reverse mirrors the string. The CipherOperation interface ties them
together. YouTube's signature cipher decomposes into an ordered
sequence of these three primitives — re-implementing in native Kotlin
avoids needing a JS engine to evaluate the player code."
```

---

## Task 2: Port `CipherManifest` + its application logic

A `CipherManifest` is an ordered list of operations + the signature timestamp (used later for player-request signing). `decipher(input)` is just a fold.

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/trailer/cipher/CipherManifest.kt`
- Create: `app/src/test/java/com/nexio/tv/data/trailer/cipher/CipherManifestTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/nexio/tv/data/trailer/cipher/CipherManifestTest.kt`:

```kotlin
package com.nexio.tv.data.trailer.cipher

import org.junit.Assert.assertEquals
import org.junit.Test

class CipherManifestTest {

    @Test
    fun `decipher applies operations in order`() {
        // Input "abcdef" → splice(1) → "bcdef" → reverse → "fedcb" → swap(2) → "decbf"
        val manifest = CipherManifest(
            signatureTimestamp = "19999",
            operations = listOf(
                SpliceCipherOperation(1),
                ReverseCipherOperation,
                SwapCipherOperation(2)
            )
        )
        assertEquals("decbf", manifest.decipher("abcdef"))
    }

    @Test
    fun `empty operations list returns input unchanged`() {
        val manifest = CipherManifest(signatureTimestamp = "1", operations = emptyList())
        assertEquals("xyz", manifest.decipher("xyz"))
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.trailer.cipher.CipherManifestTest" --console=plain 2>&1 | tail -5`
Expected: `Unresolved reference: CipherManifest`.

- [ ] **Step 3: Implement `CipherManifest.kt`**

Create `app/src/main/java/com/nexio/tv/data/trailer/cipher/CipherManifest.kt`:

```kotlin
package com.nexio.tv.data.trailer.cipher

/**
 * Ordered list of cipher operations extracted from a single YouTube
 * player JS revision. `decipher` applies each operation left-to-right.
 *
 * Port of YoutubeExplode's CipherManifest.cs. The signatureTimestamp
 * is parsed from the player JS and required for some player-request
 * shapes; we keep it in the manifest for completeness even though our
 * current request flow doesn't send it.
 */
internal data class CipherManifest(
    val signatureTimestamp: String,
    val operations: List<CipherOperation>
) {
    fun decipher(input: String): String =
        operations.fold(input) { acc, op -> op.decipher(acc) }
}
```

- [ ] **Step 4: Run tests to confirm pass**

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.trailer.cipher.CipherManifestTest" --console=plain 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`, 2 tests passing.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/trailer/cipher/CipherManifest.kt \
        app/src/test/java/com/nexio/tv/data/trailer/cipher/CipherManifestTest.kt
git commit -m "feat(cipher): CipherManifest sequence with fold-based decipher

A CipherManifest is just an ordered list of primitives plus the
signature timestamp (parsed from the player JS — required for some
player-request shapes, kept for completeness). decipher() is a
left-to-right fold over the operations."
```

---

## Task 3: Port `PlayerSourceParser` — the regex-based cipher manifest extractor

The substantive piece. Parses raw YouTube player JS via regex to:

1. Find the signatureTimestamp (`signatureTimestamp:NNNNN`).
2. Find the cipher entrypoint function (the one that splits, mutates, then joins the signature string).
3. Identify the cipher container object name from the entrypoint.
4. Find the container's function definitions.
5. Identify by signature which named function is swap/splice/reverse:
   - Contains `%` operator → swap.
   - Contains `splice` → splice.
   - Contains `reverse` → reverse.
6. Walk the entrypoint statements in order, look up each call's function name, append the appropriate `CipherOperation` to the manifest.

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/trailer/cipher/PlayerSourceParser.kt`

- [ ] **Step 1: Open the upstream `PlayerSource.cs` for reference**

Run: `cat /Users/jneerdael/Scripts/YoutubeExplode/YoutubeExplode/Bridge/PlayerSource.cs`

Keep this open in another window — Step 2 ports each regex literal one-to-one.

- [ ] **Step 2: Implement `PlayerSourceParser.kt`**

Create `app/src/main/java/com/nexio/tv/data/trailer/cipher/PlayerSourceParser.kt`:

```kotlin
package com.nexio.tv.data.trailer.cipher

/**
 * Parses a YouTube player JS source string and extracts a [CipherManifest]
 * describing the sequence of operations applied to the signature string.
 *
 * Port of YoutubeExplode's PlayerSource.cs. Regex strings are kept
 * structurally identical to the upstream so they're easy to update if
 * YouTube rotates their player JS minification pattern.
 *
 * Returns `null` if the parser fails — caller should treat that as
 * "the JS shape changed, fall back to non-cipher streams only."
 */
internal object PlayerSourceParser {

    fun parse(playerJs: String): CipherManifest? {
        // 1. Signature timestamp — 5 digits after `signatureTimestamp:` or `sts:`
        val signatureTimestamp =
            Regex("""(?:signatureTimestamp|sts):(\d{5})""")
                .find(playerJs)
                ?.groupValues
                ?.getOrNull(1)
                ?.takeIf { it.isNotBlank() }
                ?: return null

        // 2. Cipher entrypoint — a function that does
        //    str.split("") ... return X.join("")
        val cipherCallsite =
            Regex(
                """[$_\w]+=function\([$_\w]+\)\{([$_\w]+)=\1\.split\(['"]{2}\);.*?return \1\.join\(['"]{2}\)\}""",
                RegexOption.DOT_MATCHES_ALL
            )
                .find(playerJs)
                ?.value
                ?.takeIf { it.isNotBlank() }
                ?: return null

        // 3. Cipher container object name — the object whose methods are called
        //    by the entrypoint statements
        val cipherContainerName =
            Regex("""([$_\w]+)\.[$_\w]+\([$_\w]+,\d+\);""")
                .find(cipherCallsite)
                ?.groupValues
                ?.getOrNull(1)
                ?.takeIf { it.isNotBlank() }
                ?: return null

        // 4. Cipher container definition — `var Foo={...};`
        val cipherDefinition =
            Regex(
                """var ${Regex.escape(cipherContainerName)}=\{.*?\};""",
                RegexOption.DOT_MATCHES_ALL
            )
                .find(playerJs)
                ?.value
                ?.takeIf { it.isNotBlank() }
                ?: return null

        // 5. Identify each named function by its body signature:
        //    - contains `%`  → swap
        //    - contains `splice` → splice
        //    - contains `reverse` → reverse
        val swapFuncName = Regex(
            """([$_\w]+):function\([$_\w]+,[$_\w]+\)\{+[^}]*?%[^}]*?\}""",
            RegexOption.DOT_MATCHES_ALL
        ).find(cipherDefinition)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }

        val spliceFuncName = Regex(
            """([$_\w]+):function\([$_\w]+,[$_\w]+\)\{+[^}]*?splice[^}]*?\}""",
            RegexOption.DOT_MATCHES_ALL
        ).find(cipherDefinition)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }

        val reverseFuncName = Regex(
            """([$_\w]+):function\([$_\w]+\)\{+[^}]*?reverse[^}]*?\}""",
            RegexOption.DOT_MATCHES_ALL
        ).find(cipherDefinition)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }

        // 6. Walk the entrypoint statements; build the ordered ops list
        val operations = mutableListOf<CipherOperation>()
        val callPattern = Regex("""[$_\w]+\.([$_\w]+)\([$_\w]+,(\d+)\)""")
        val reverseCallPattern = Regex("""[$_\w]+\.([$_\w]+)\([$_\w]+\)""")

        for (statement in cipherCallsite.split(';')) {
            // Try the two-arg form first (swap/splice both take an index)
            callPattern.find(statement)?.let { match ->
                val calledFuncName = match.groupValues[1]
                val index = match.groupValues[2].toIntOrNull() ?: return@let
                when (calledFuncName) {
                    swapFuncName -> operations += SwapCipherOperation(index)
                    spliceFuncName -> operations += SpliceCipherOperation(index)
                    reverseFuncName -> operations += ReverseCipherOperation
                }
                return@let
            }
            // Otherwise try the one-arg form (reverse takes no index)
            reverseCallPattern.find(statement)?.let { match ->
                val calledFuncName = match.groupValues[1]
                if (calledFuncName == reverseFuncName) {
                    operations += ReverseCipherOperation
                }
            }
        }

        return CipherManifest(signatureTimestamp, operations)
    }
}
```

> **Note:** YoutubeExplode's original parser uses just `callPattern` (two-arg form) and identifies reverse via the same. The reverse function in modern YouTube player JS sometimes takes one arg and sometimes two — adding the fallback `reverseCallPattern` covers both shapes. If the regexes don't match a future player JS revision, lift the current ones from YoutubeExplode's `PlayerSource.cs` (it's actively maintained).

- [ ] **Step 3: Verify compilation**

Run: `./gradlew :app:compileUniversalDebugKotlin --console=plain 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/trailer/cipher/PlayerSourceParser.kt
git commit -m "feat(cipher): regex-based player-JS cipher manifest extractor

Port of YoutubeExplode's PlayerSource.cs. Identifies signatureTimestamp,
finds the cipher entrypoint function, the container object, the named
swap/splice/reverse functions (by telltale `%` / `splice` / `reverse`
substrings), and walks the entrypoint statements to build an ordered
CipherManifest. Regex strings kept structurally identical to upstream
so we can lift updates verbatim if YouTube rotates their player JS
minification pattern. Returns null on parse failure — caller treats
that as 'cipher unavailable, drop signatureCipher entries.'"
```

---

## Task 4: Player JS fetcher + per-session cache

Need to fetch `https://www.youtube.com/s/player/<hash>/.../base.js` once per session. The URL is in the watch-page HTML.

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/trailer/cipher/PlayerSourceCache.kt`

- [ ] **Step 1: Implement `PlayerSourceCache.kt`**

```kotlin
package com.nexio.tv.data.trailer.cipher

import com.nexio.tv.data.trailer.YOUTUBE_STABLE_WEB_USER_AGENT
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
 * Fetches and caches the YouTube player JS for cipher extraction.
 * The actual JS source is ~3 MB; we keep it in memory and a parsed
 * [CipherManifest] alongside. Cache key is the player JS URL —
 * whenever YouTube rotates their player, a new URL appears in the
 * watch page and this cache naturally invalidates.
 */
@Singleton
class PlayerSourceCache @Inject constructor() {

    private val mutex = Mutex()
    private var cachedUrl: String? = null
    private var cachedManifest: CipherManifest? = null

    /**
     * Extracts the player JS URL from a watch page HTML. Returns null
     * if the URL can't be found (e.g. malformed HTML or a YouTube
     * response shape we don't recognize).
     */
    fun extractPlayerJsUrl(watchPageHtml: String): String? {
        // Three observed shapes (from YouTube's HTML over the past 2 years)
        val patterns = listOf(
            Regex(""""jsUrl":"(/s/player/[^"]+/base\.js)""""),
            Regex("""<script\s+src="(/s/player/[^"]+/base\.js)""""),
            Regex(""""PLAYER_JS_URL":"(/s/player/[^"]+/base\.js)"""")
        )
        for (pattern in patterns) {
            pattern.find(watchPageHtml)?.let { m ->
                val path = m.groupValues[1].replace("\\/", "/")
                return "https://www.youtube.com$path"
            }
        }
        return null
    }

    /**
     * Returns the parsed [CipherManifest] for the given player JS URL.
     * Fetches the JS on cache miss, parses it, and stores the result.
     * Returns null on network failure or parse failure.
     */
    suspend fun getCipherManifest(playerJsUrl: String): CipherManifest? = mutex.withLock {
        if (cachedUrl == playerJsUrl && cachedManifest != null) {
            return@withLock cachedManifest
        }

        val playerJs = fetchPlayerJs(playerJsUrl) ?: return@withLock null
        val manifest = PlayerSourceParser.parse(playerJs)
        cachedUrl = playerJsUrl
        cachedManifest = manifest
        manifest
    }

    private suspend fun fetchPlayerJs(playerJsUrl: String): String? = withContext(Dispatchers.IO) {
        val conn = (URL(playerJsUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 8_000
            setRequestProperty("User-Agent", YOUTUBE_STABLE_WEB_USER_AGENT)
            setRequestProperty("Referer", "https://www.youtube.com/")
            setRequestProperty("Accept", "*/*")
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

Run: `./gradlew :app:compileUniversalDebugKotlin --console=plain 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/trailer/cipher/PlayerSourceCache.kt
git commit -m "feat(cipher): player JS fetcher with per-URL manifest cache

@Singleton, Hilt-injectable. extractPlayerJsUrl scans the watch-page
HTML for the player JS URL via three forgiving regex patterns covering
the YouTube HTML shapes seen in the wild. getCipherManifest fetches
once per URL and caches both the raw JS URL and the parsed manifest.
When YouTube rotates the player, the URL changes and the cache
naturally invalidates."
```

---

## Task 5: `SignatureCipherDecoder` — apply manifest to `signatureCipher` blob

Parse the `signatureCipher` field (`s=ENCRYPTED&sp=sig&url=ENCODED_URL`), decipher `s`, set the `sp`-named query param on the URL to the deciphered value, return the playable URL.

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/trailer/cipher/SignatureCipherDecoder.kt`
- Create: `app/src/test/java/com/nexio/tv/data/trailer/cipher/SignatureCipherDecoderTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/nexio/tv/data/trailer/cipher/SignatureCipherDecoderTest.kt`:

```kotlin
package com.nexio.tv.data.trailer.cipher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignatureCipherDecoderTest {

    @Test
    fun `decodes a sample signatureCipher into a playable URL`() {
        // Manifest that applies reverse → splice(2) → swap(3)
        // Input signature: "abcdef" → "fedcba" → "dcba" → "acbd"
        val manifest = CipherManifest(
            signatureTimestamp = "12345",
            operations = listOf(
                ReverseCipherOperation,
                SpliceCipherOperation(2),
                SwapCipherOperation(3)
            )
        )

        // URL-encoded `s=abcdef&sp=sig&url=https%3A%2F%2Fexample.com%2Fplay%3Fa%3D1`
        val signatureCipher = "s=abcdef&sp=sig&url=https%3A%2F%2Fexample.com%2Fplay%3Fa%3D1"
        val decoded = SignatureCipherDecoder.decode(signatureCipher, manifest)

        assertTrue("expected non-null decoded URL", decoded != null)
        assertTrue("expected sig param present", decoded!!.contains("sig=acbd"))
        assertTrue("expected base url preserved", decoded.startsWith("https://example.com/play"))
        assertTrue("expected a=1 preserved", decoded.contains("a=1"))
    }

    @Test
    fun `returns null when signatureCipher is missing required fields`() {
        val manifest = CipherManifest("1", emptyList())
        assertEquals(null, SignatureCipherDecoder.decode("only=garbage", manifest))
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.trailer.cipher.SignatureCipherDecoderTest" --console=plain 2>&1 | tail -5`
Expected: `Unresolved reference: SignatureCipherDecoder`.

- [ ] **Step 3: Implement `SignatureCipherDecoder.kt`**

```kotlin
package com.nexio.tv.data.trailer.cipher

import android.net.Uri
import java.net.URLDecoder

/**
 * Decodes a YouTube `signatureCipher` field into a playable URL.
 *
 * The `signatureCipher` field is URL-encoded key-value pairs:
 * `s=<encrypted-signature>&sp=<sig-param-name>&url=<URL-encoded-base-URL>`
 *
 * After deciphering `s` with [manifest] and setting it as the `sp`-named
 * query parameter on the base URL, the result is directly fetchable.
 *
 * Port of YoutubeExplode's StreamClient.cs:104-113 cipher-application
 * logic, adapted to take the signatureCipher blob as input.
 */
internal object SignatureCipherDecoder {

    fun decode(signatureCipher: String, manifest: CipherManifest): String? {
        val pairs = signatureCipher
            .split('&')
            .mapNotNull { kv ->
                val eq = kv.indexOf('=')
                if (eq < 0) null else kv.substring(0, eq) to kv.substring(eq + 1)
            }
            .toMap()

        val encryptedSig = pairs["s"] ?: return null
        val sigParam = pairs["sp"] ?: "sig"
        val urlEncoded = pairs["url"] ?: return null

        // The blob's `s` value is URL-encoded twice in some shapes;
        // decode once. The deciphered output goes into a query param
        // value — Uri.Builder will encode it again on append.
        val sigDecoded = runCatching { URLDecoder.decode(encryptedSig, "UTF-8") }
            .getOrNull() ?: return null

        val deciphered = manifest.decipher(sigDecoded)

        val baseUrl = runCatching { URLDecoder.decode(urlEncoded, "UTF-8") }
            .getOrNull() ?: return null

        val baseUri = runCatching { Uri.parse(baseUrl) }.getOrNull() ?: return null

        // Rebuild URL with the deciphered sig added as the sp-named param,
        // preserving all other existing query params.
        val rebuilt = Uri.Builder()
            .scheme(baseUri.scheme)
            .authority(baseUri.authority)
            .path(baseUri.path)
        for (key in baseUri.queryParameterNames) {
            if (key == sigParam) continue  // replace, don't duplicate
            rebuilt.appendQueryParameter(key, baseUri.getQueryParameter(key).orEmpty())
        }
        rebuilt.appendQueryParameter(sigParam, deciphered)
        return rebuilt.build().toString()
    }
}
```

- [ ] **Step 4: Run tests to confirm pass**

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.trailer.cipher.SignatureCipherDecoderTest" --console=plain 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`, 2 tests passing.

Note: `android.net.Uri` works in Android unit tests since recent AGP versions ship a stub that handles basic parse/build. If the test fails with `Stub!`, mark with `@RunWith(RobolectricTestRunner::class)` and add `testImplementation("org.robolectric:robolectric:...")` if not already present. We use Robolectric elsewhere — verify with `grep -E "robolectric" app/build.gradle.kts`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/trailer/cipher/SignatureCipherDecoder.kt \
        app/src/test/java/com/nexio/tv/data/trailer/cipher/SignatureCipherDecoderTest.kt
git commit -m "feat(cipher): decode signatureCipher blob to playable URL

Port of YoutubeExplode's StreamClient cipher-application logic. Parses
the s=...&sp=...&url=... blob, deciphers the encrypted signature via
the cipher manifest, and inserts the result as the sp-named query
parameter on the base URL. Uses android.net.Uri throughout — no Ktor
dependency. Returns null on any parse failure (missing s, missing url,
unparseable base URL) so callers can drop the format gracefully."
```

---

## Task 6: Wire into `InAppYouTubeExtractor`

Fetch the player JS + parse cipher manifest in parallel with the player API calls. In the per-format extraction loops, when an entry has `signatureCipher` but no `url`, decipher it.

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/trailer/InAppYouTubeExtractor.kt`

- [ ] **Step 1: Inject `PlayerSourceCache`**

Modify the class declaration. Current:

```kotlin
@Singleton
class InAppYouTubeExtractor @Inject constructor(
    private val integrationProvider: YouTubeTrailerIntegrationProvider,
    @ApplicationContext private val applicationContext: Context
)
```

Change to:

```kotlin
@Singleton
class InAppYouTubeExtractor @Inject constructor(
    private val integrationProvider: YouTubeTrailerIntegrationProvider,
    @ApplicationContext private val applicationContext: Context,
    private val playerSourceCache: PlayerSourceCache
)
```

Add imports:

```kotlin
import com.nexio.tv.data.trailer.cipher.CipherManifest
import com.nexio.tv.data.trailer.cipher.PlayerSourceCache
import com.nexio.tv.data.trailer.cipher.SignatureCipherDecoder
```

- [ ] **Step 2: Fetch player JS in parallel with the watch-page + player-API flow**

Find the start of `extractPlaybackSourceInternal`. After the watch-response is parsed and `apiKey` is extracted, add (inside `coroutineScope { ... }` or wrap an `async` if not already):

```kotlin
// Fetch the cipher manifest in parallel — needed to decode any
// signatureCipher fields in formats[] / adaptiveFormats[]. If the
// fetch or parse fails, we'll still process formats that have a
// direct `url` field; only signatureCipher-only formats will be
// dropped.
val cipherManifestDeferred = coroutineScope.async(Dispatchers.IO) {
    val playerJsUrl = playerSourceCache.extractPlayerJsUrl(watchResponse.body)
        ?: return@async null
    playerSourceCache.getCipherManifest(playerJsUrl)
}
```

If `extractPlaybackSourceInternal` isn't already in a `coroutineScope { ... }`, wrap the per-client loop and the final selection inside one. The function likely already uses `withContext(Dispatchers.IO)` at the outer level — confirm and adjust accordingly.

- [ ] **Step 3: Use the manifest in the format-collection loops**

The current `format.stringValue("url") ?: continue` lines silently drop entries that lack a direct URL. After this task, those entries try `signatureCipher` instead.

Locate the two for-each loops over `streamingData.listMapValue("formats")` and `streamingData.listMapValue("adaptiveFormats")`. Replace the URL-extraction line in EACH loop.

Before (each loop has one of these):

```kotlin
val url = format.stringValue("url") ?: continue
```

After:

```kotlin
val rawUrl = format.stringValue("url")
val signatureCipher = format.stringValue("signatureCipher")
    ?: format.stringValue("cipher")  // older shape, still seen occasionally
val url = rawUrl ?: signatureCipher?.let { sc ->
    val manifest = cipherManifestDeferred.await() ?: return@let null
    SignatureCipherDecoder.decode(sc, manifest)
} ?: continue
```

Note: `cipherManifestDeferred.await()` blocks the per-format iteration on the manifest fetch. The first format with a signatureCipher will pay the cost (~200ms typically); subsequent formats use the cached manifest from the deferred result and don't re-await.

- [ ] **Step 4: Verify compilation**

Run: `./gradlew :app:compileUniversalDebugKotlin --console=plain 2>&1 | tail -10`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/trailer/InAppYouTubeExtractor.kt
git commit -m "feat(cipher): decode signatureCipher fields during extraction

InAppYouTubeExtractor now fetches the YouTube player JS in parallel
with the per-client player API calls and parses a CipherManifest. In
the formats[] and adaptiveFormats[] iteration, entries with a
signatureCipher field (instead of a direct `url`) are deciphered into
playable URLs via the manifest. Previously these entries were silently
dropped — losing significant high-quality adaptive variants on many
videos."
```

---

## Task 7: On-device smoke test + decision gate

The empirical test of whether cipher decoding alone (plus our existing range-chunking for throttle bypass) is sufficient for trailer playback at full quality.

**Files:** none — operational verification.

- [ ] **Step 1: Build and install the APK**

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

- [ ] **Step 3: Play each canonical trailer**

Project Hail Mary → Citadel → Ready or Not 2 → The Drama. Allow each ~15 seconds to confirm decoder dimensions stabilize.

- [ ] **Step 4: Pull logcat and inspect for success signals**

```bash
adb -s 192.168.50.98:5555 logcat -d -t 6000 \
  | grep -iE "PlayerSourceCache|CipherManifest|signatureCipher|Kotlin selection|OmxVideoDecoder.*nFrameWidth|Response code: 4|Source error" \
  | tail -50
```

Success indicators:
- `Kotlin selection ... combinedSelected=true|false ... adaptiveVideoCount=>0 adaptiveAudioCount=>0` — extraction yields adaptive streams (which is where signatureCipher matters most).
- `OmxVideoDecoder ... nFrameWidth=1920 nFrameHeight=1080` (or higher) — actually selected a 1080p+ variant.
- Zero `Response code: 403` lines from `TrailerPlayer`.
- No `Source error` with throttle-symptom signatures (long pauses every ~10s of playback).

- [ ] **Step 5: Decision gate**

**Branch A — All four trailers play at 1080p+ with no 403s:**
✅ Cipher decoding alone was sufficient. Push commits, archive `2026-05-11-youtube-extractor-newpipe-alignment.md` / `2026-05-11-youtube-potoken-provider.md` / `2026-05-11-youtube-extractor-duktape-port.md`:

```bash
mkdir -p docs/superpowers/plans/archived
git mv docs/superpowers/plans/2026-05-11-youtube-extractor-newpipe-alignment.md docs/superpowers/plans/archived/
git mv docs/superpowers/plans/2026-05-11-youtube-potoken-provider.md docs/superpowers/plans/archived/
git mv docs/superpowers/plans/2026-05-11-youtube-extractor-duktape-port.md docs/superpowers/plans/archived/
git add docs/superpowers/plans/archived/
git commit -m "docs(plans): archive earlier heavier YouTube extractor plans

The YoutubeExplode-style cipher port (this plan) plus the existing
YoutubeChunkedDataSourceFactory provided sufficient capability without
WebView, Duktape, or poToken. Archiving the three earlier plans that
proposed heavier approaches — preserved for context but not the path
we shipped."
git push
```

**Branch B — Most trailers work but ≥1 specific video returns 403 on adaptive streams:**
Those specific videos likely require poToken. Two sub-options:
- B1: deny-list them at extraction time, fall back to iOS HLS. Acceptable if it's <10% of titles.
- B2: implement `2026-05-11-youtube-potoken-provider.md` on top for the remaining failures.

**Branch C — Cipher manifest parse fails (e.g. `Kotlin selection` shows formats lost without explanation, logcat shows null manifests):**
Most likely the regex patterns in `PlayerSourceParser` don't match YouTube's current minifier output. Mitigation:
- C1: Capture a sample player JS URL from logcat, fetch it with `curl` on your host, inspect the cipher region (search for `signatureTimestamp:`).
- C2: Compare against YoutubeExplode's regexes at `/Users/jneerdael/Scripts/YoutubeExplode/YoutubeExplode/Bridge/PlayerSource.cs`. If they've updated, lift the new patterns.
- C3: If even YoutubeExplode's current patterns don't match, file a bug upstream and treat this as a YouTube-side change all the libraries are racing to handle.

**Branch D — Long pauses every 10–15 seconds during playback (throttle symptom):**
The range-chunking in `YoutubeChunkedDataSourceFactory` may not be engaging properly. Verify with:

```bash
adb -s 192.168.50.98:5555 logcat -d | grep -iE "YoutubeChunkedDataSource|range="
```

If chunking isn't visible, debug why — but this is likely a separate problem from the cipher port.

- [ ] **Step 6: If Branch A: push and close out**

```bash
git push
```

---

## Self-Review

**Spec coverage:**

- "Port 3 cipher operations" → Task 1.
- "Port CipherManifest sequence" → Task 2.
- "Port PlayerSourceParser regex-based extractor" → Task 3.
- "Add player JS fetcher with per-URL cache" → Task 4.
- "SignatureCipherDecoder applies manifest to signatureCipher blob" → Task 5.
- "Wire into InAppYouTubeExtractor (parallel fetch + format loop adaptation)" → Task 6.
- "On-device smoke + decision gate" → Task 7.

**Placeholder scan:**

- No "TBD" / "implement later" content.
- Task 5 Step 4 notes a Robolectric possibility for the `Uri` test — verified the codebase already uses Robolectric elsewhere via `grep` so this is a no-op.
- The `coroutineScope.async` reference in Task 6 Step 2 assumes `extractPlaybackSourceInternal` is already in a coroutineScope; if not, Step 2 explicitly notes "wrap the per-client loop in `coroutineScope { ... }`".

**Type consistency:**

- `CipherOperation` interface used by all three `*CipherOperation` classes — consistent.
- `CipherManifest(signatureTimestamp: String, operations: List<CipherOperation>)` — same signature used in tests, parser, and decoder.
- `PlayerSourceCache.getCipherManifest(playerJsUrl: String): CipherManifest?` — suspend, nullable. Caller (Task 6) handles null gracefully.
- `SignatureCipherDecoder.decode(signatureCipher: String, manifest: CipherManifest): String?` — non-suspend, nullable.

---

## Known follow-ups (out of scope for this plan)

- **WEB client extraction**: adding the WEB client surfaces more `signatureCipher` formats (some at higher resolution than what iOS/Android return). Now that we can decode those, adding the WEB client to `CLIENTS` is a small follow-up.
- **CipherManifest disk persistence**: ~3 MB player JS plus parsed manifest, keyed by player URL hash, written to `filesDir`. Saves the fetch on cold start (~500ms). Only worthwhile if we observe the cost matters in practice.
- **poToken WebView+BotGuard implementation**: documented in `2026-05-11-youtube-potoken-provider.md`. Only execute if Task 7 Branch B materializes.
- **Duktape port**: `2026-05-11-youtube-extractor-duktape-port.md` — only needed if the n-throttle bypass via chunking proves insufficient for some videos (Branch D).
