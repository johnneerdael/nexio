# TorBox Library Direct-Play Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Spec: `docs/superpowers/specs/2026-05-12-torbox-library-direct-play-design.md`.

**Goal:** Surface a user's TorBox cloud library as a tab inside Nexio's Library screen with click-to-direct-play (no detail view, no stream selection), lazy `requestdl` resolution, per-file resume, and within-torrent autoplay-next.

**Architecture:** Items flow through `DebridLibraryService` as `LibraryEntry` rows with a tightened playable filter; `LibraryViewModel` emits a `DirectPlayCommand` on click that `LibraryScreen` translates into a `Screen.Player.createRoute(...)` navigation; the Player parses TorBox context out of the existing `videoId`/`launchSource`/`filename` route args, drives resume saves into a new per-profile `TorBoxResumeStore`, and triggers autoplay-next through `TorBoxAutoplayNext`. Items stay outside the typed-authority pipeline (no rail source, no `ResolvedDisplaySurfaceRepository` involvement).

**Tech Stack:** Kotlin, Jetpack Compose for TV, Hilt, Retrofit/Moshi, Coroutines, Preferences DataStore, JUnit 4 + MockK + Turbine for tests.

**Spec-to-plan deviation worth flagging:** The spec described adding a `torBoxContext: TorBoxPlaybackContext?` field to `PlayerRouteArgs`. `Screen.Player.createRoute` is positional with 30+ params; instead we tunnel TorBox context via the existing `videoId = "tb:torrent:{torrentId}:file:{fileId}"` (the format `mapTorBoxItem` already uses), `filename = fileName`, and `launchSource = "torbox"`. The Player parses these on its side. Zero new nav args, same behavior.

---

## File Structure

**New files**

| File | Responsibility |
| --- | --- |
| `app/src/main/java/com/nexio/tv/data/local/TorBoxResumeStore.kt` | Per-profile Preferences DataStore mapping `torbox:{torrentId}:{fileId}` → resume millis. |
| `app/src/main/java/com/nexio/tv/data/repository/TorBoxDirectPlayHandler.kt` | Resolves a fresh `requestDownloadLink` URL + reads resume position. Returns `TorBoxResolvedPlayback`. |
| `app/src/main/java/com/nexio/tv/data/repository/TorBoxAutoplayNext.kt` | Thin facade over `DebridLibraryService.nextPlayableFileInTorrent`. |
| `app/src/main/java/com/nexio/tv/domain/model/TorBoxPlaybackContext.kt` | Tiny data class `{torrentId, fileId, fileName}` parsed by the Player. |
| `app/src/test/java/com/nexio/tv/data/repository/DebridLibraryServiceTorBoxFilesTest.kt` | Unit tests for the playable filter + `nextPlayableFileInTorrent`. |
| `app/src/test/java/com/nexio/tv/data/local/TorBoxResumeStoreTest.kt` | DataStore save/load/clear unit tests. |
| `app/src/test/java/com/nexio/tv/data/repository/TorBoxDirectPlayHandlerTest.kt` | Handler unit tests against a faked `TorBoxIntegrationProvider`. |
| `app/src/test/java/com/nexio/tv/ui/screens/library/LibraryViewModelTorBoxClickTest.kt` | VM integration test: click → `DirectPlayCommand` sequence. |

**Modified files**

| File | Why |
| --- | --- |
| `app/src/main/java/com/nexio/tv/data/repository/DebridLibraryService.kt` | Tighten playable filter for `TorBoxFileDto`; drop eager `requestDownloadLink`; add `nextPlayableFileInTorrent`. |
| `app/src/main/java/com/nexio/tv/ui/screens/library/LibraryViewModel.kt` | Add `onTorBoxItemClick`, `refreshTorBoxLibraryNow`, `torBoxRefreshing` flow, `DirectPlayCommand` channel. |
| `app/src/main/java/com/nexio/tv/ui/screens/library/LibraryScreen.kt` | First-focus refresh `LaunchedEffect`, refresh button, `DirectPlayCommand` collector → Navigator. |
| `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerViewModel.kt` (and the relevant `PlayerRuntimeController*.kt` files) | Parse TorBox context from route args; tick → `TorBoxResumeStore.savePosition`; onMediaEnded → autoplay-next or pop. |

No DTO changes, no Retrofit changes, no Hilt module changes beyond the new singletons (Hilt's default `@Singleton` constructor binding picks them up automatically since the existing modules don't enumerate per-class).

---

## Task 1: Tighten TorBox playable filter

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/DebridLibraryService.kt:688-689`
- Test: `app/src/test/java/com/nexio/tv/data/repository/DebridLibraryServiceTorBoxFilesTest.kt` (new)

- [ ] **Step 1: Write failing tests**

Create `app/src/test/java/com/nexio/tv/data/repository/DebridLibraryServiceTorBoxFilesTest.kt`:

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.data.remote.dto.debrid.TorBoxFileDto
import com.nexio.tv.data.repository.DebridLibraryService.Companion.isTorBoxFilePlayable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebridLibraryServiceTorBoxFilesTest {

    @Test
    fun `video mp4 of 51MB is playable`() {
        val file = TorBoxFileDto(
            id = 1, name = "movie.mp4", shortName = "movie.mp4",
            size = 51L * 1024L * 1024L, mimeType = "video/mp4"
        )
        assertTrue(isTorBoxFilePlayable(file))
    }

    @Test
    fun `video mp4 of 47MB is not playable`() {
        val file = TorBoxFileDto(
            id = 1, name = "movie.mp4", shortName = "movie.mp4",
            size = 47L * 1024L * 1024L, mimeType = "video/mp4"
        )
        assertFalse(isTorBoxFilePlayable(file))
    }

    @Test
    fun `null mimeType is not playable even if extension is mp4`() {
        val file = TorBoxFileDto(
            id = 1, name = "movie.mp4", shortName = "movie.mp4",
            size = 200L * 1024L * 1024L, mimeType = null
        )
        assertFalse(isTorBoxFilePlayable(file))
    }

    @Test
    fun `nfo file is not playable`() {
        val file = TorBoxFileDto(
            id = 1, name = "info.nfo", shortName = "info.nfo",
            size = 4_096L, mimeType = "text/plain"
        )
        assertFalse(isTorBoxFilePlayable(file))
    }

    @Test
    fun `srt subtitle is not playable`() {
        val file = TorBoxFileDto(
            id = 1, name = "movie.srt", shortName = "movie.srt",
            size = 200_000L, mimeType = "application/x-subrip"
        )
        assertFalse(isTorBoxFilePlayable(file))
    }

    @Test
    fun `null size is not playable`() {
        val file = TorBoxFileDto(
            id = 1, name = "movie.mp4", shortName = "movie.mp4",
            size = null, mimeType = "video/mp4"
        )
        assertFalse(isTorBoxFilePlayable(file))
    }

    @Test
    fun `webm video over threshold is playable`() {
        val file = TorBoxFileDto(
            id = 2, name = "clip.webm", shortName = "clip.webm",
            size = 300L * 1024L * 1024L, mimeType = "video/webm"
        )
        assertTrue(isTorBoxFilePlayable(file))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.repository.DebridLibraryServiceTorBoxFilesTest" 2>&1 | tail -20`
Expected: FAIL — `Unresolved reference 'isTorBoxFilePlayable'`.

- [ ] **Step 3: Add the predicate to `DebridLibraryService`**

In `DebridLibraryService.kt`, find the companion object (`companion object { const val TORBOX_LIST_KEY = "service:torbox" ... }` around line 800) and add:

```kotlin
internal const val TORBOX_MIN_PLAYABLE_BYTES: Long = 50L * 1024L * 1024L

@JvmStatic
fun isTorBoxFilePlayable(file: TorBoxFileDto): Boolean {
    val mime = file.mimeType ?: return false
    if (!mime.startsWith("video/", ignoreCase = true)) return false
    val size = file.size ?: return false
    return size >= TORBOX_MIN_PLAYABLE_BYTES
}
```

Make sure `TorBoxFileDto` is imported at the top of the file (it already is — line referencing `com.nexio.tv.data.remote.dto.debrid.TorBoxFileDto`).

- [ ] **Step 4: Replace the existing TorBox-specific `isLikelyPlayable(TorBoxFileDto)` and remove the sample-file fallback**

Replace the body of `private fun isLikelyPlayable(file: TorBoxFileDto): Boolean` (line ~688) with:

```kotlin
private fun isLikelyPlayable(file: TorBoxFileDto): Boolean =
    isTorBoxFilePlayable(file)
```

Then, in `fetchTorBoxItems` (line ~532), drop the `isLikelySampleFile(file)` check from the chain — the size floor already covers samples. The block becomes:

```kotlin
if (!isLikelyPlayable(file)) {
    return@withPermit null
}
```

(Delete the `|| isLikelySampleFile(file)` half of the previous condition.)

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.repository.DebridLibraryServiceTorBoxFilesTest" 2>&1 | tail -10`
Expected: PASS — 7 tests.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/DebridLibraryService.kt \
        app/src/test/java/com/nexio/tv/data/repository/DebridLibraryServiceTorBoxFilesTest.kt
git status -sb
git commit -m "$(cat <<'EOF'
feat(torbox): tighten playable-file filter to mimeType + 50MB

TorBox library entries now require mimeType starts-with "video/" AND
size >= 50 MB. Drops the legacy sample-filename heuristic; size floor
sweeps up samples cleanly without false-positive renames.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: `nextPlayableFileInTorrent` helper

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/DebridLibraryService.kt` (add public suspend fun + companion data class)
- Modify: `app/src/test/java/com/nexio/tv/data/repository/DebridLibraryServiceTorBoxFilesTest.kt` (extend)

- [ ] **Step 1: Add the failing test for the helper**

Append to `DebridLibraryServiceTorBoxFilesTest.kt`:

```kotlin
import com.nexio.tv.data.remote.dto.debrid.TorBoxTorrentListItemDto
import com.nexio.tv.data.repository.DebridLibraryService.Companion.pickNextFileInTorrent
import com.nexio.tv.data.repository.DebridLibraryService.TorBoxNextFile

@Test
fun `pickNextFileInTorrent returns next file by alphabetical name`() {
    val torrent = TorBoxTorrentListItemDto(
        id = 7, name = "Show S01",
        files = listOf(
            TorBoxFileDto(id = 12, name = "Show.S01E03.mkv", shortName = "Show.S01E03.mkv",
                size = 500L * 1024L * 1024L, mimeType = "video/x-matroska"),
            TorBoxFileDto(id = 10, name = "Show.S01E01.mkv", shortName = "Show.S01E01.mkv",
                size = 500L * 1024L * 1024L, mimeType = "video/x-matroska"),
            TorBoxFileDto(id = 11, name = "Show.S01E02.mkv", shortName = "Show.S01E02.mkv",
                size = 500L * 1024L * 1024L, mimeType = "video/x-matroska")
        )
    )

    val afterE1 = pickNextFileInTorrent(torrent, currentFileId = 10)
    assertEquals(TorBoxNextFile(torrentId = 7, fileId = 11, fileName = "Show.S01E02.mkv"), afterE1)

    val afterE3 = pickNextFileInTorrent(torrent, currentFileId = 12)
    assertEquals(null, afterE3)
}

@Test
fun `pickNextFileInTorrent skips unplayable files`() {
    val torrent = TorBoxTorrentListItemDto(
        id = 7, name = "Show S01",
        files = listOf(
            TorBoxFileDto(id = 10, name = "Show.S01E01.mkv", shortName = "Show.S01E01.mkv",
                size = 500L * 1024L * 1024L, mimeType = "video/x-matroska"),
            TorBoxFileDto(id = 99, name = "info.nfo", shortName = "info.nfo",
                size = 4_096L, mimeType = "text/plain"),
            TorBoxFileDto(id = 11, name = "Show.S01E02.mkv", shortName = "Show.S01E02.mkv",
                size = 500L * 1024L * 1024L, mimeType = "video/x-matroska")
        )
    )

    val next = pickNextFileInTorrent(torrent, currentFileId = 10)
    assertEquals(11, next?.fileId)
}

@Test
fun `pickNextFileInTorrent on single-file torrent returns null`() {
    val torrent = TorBoxTorrentListItemDto(
        id = 7, name = "Movie",
        files = listOf(
            TorBoxFileDto(id = 10, name = "movie.mkv", shortName = "movie.mkv",
                size = 1L * 1024L * 1024L * 1024L, mimeType = "video/x-matroska")
        )
    )
    assertEquals(null, pickNextFileInTorrent(torrent, currentFileId = 10))
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.repository.DebridLibraryServiceTorBoxFilesTest" 2>&1 | tail -20`
Expected: FAIL — `Unresolved reference 'pickNextFileInTorrent'` and `TorBoxNextFile`.

- [ ] **Step 3: Add the data class and pure helper**

In `DebridLibraryService.kt`, inside the companion object, add:

```kotlin
data class TorBoxNextFile(
    val torrentId: Int,
    val fileId: Int,
    val fileName: String
)

@JvmStatic
fun pickNextFileInTorrent(
    torrent: TorBoxTorrentListItemDto,
    currentFileId: Int
): TorBoxNextFile? {
    val torrentId = torrent.id ?: return null
    val playable = torrent.files
        .filter { it.id != null && isTorBoxFilePlayable(it) }
        .sortedBy { it.shortName ?: it.name ?: "" }
    var found = false
    for (i in playable.indices) {                            // CLAUDE.md #4 — no Iterable.forEach in suspending paths
        val file = playable[i]
        if (found) {
            val name = file.shortName ?: file.name ?: continue
            return TorBoxNextFile(torrentId = torrentId, fileId = file.id!!, fileName = name)
        }
        if (file.id == currentFileId) found = true
    }
    return null
}
```

- [ ] **Step 4: Add the public suspend method that reads the live torrent list cache**

Below `fetchTorBoxItems` (around line 545), add:

```kotlin
suspend fun nextPlayableFileInTorrent(
    torrentId: Int,
    currentFileId: Int
): TorBoxNextFile? = withContext(Dispatchers.IO) {
    val apiKey = torBoxSettingsDataStore.settings.first().apiKey.trim()
    if (apiKey.isBlank()) return@withContext null
    val response = torBoxProvider.fetchTorrentList(apiKey = apiKey, id = torrentId, limit = 1)
        ?: return@withContext null
    val torrent = response.data.orEmpty().firstOrNull { it.id == torrentId }
        ?: return@withContext null
    pickNextFileInTorrent(torrent, currentFileId)
}
```

- [ ] **Step 5: Run tests to verify pass**

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.repository.DebridLibraryServiceTorBoxFilesTest" 2>&1 | tail -10`
Expected: PASS — 10 tests total.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/DebridLibraryService.kt \
        app/src/test/java/com/nexio/tv/data/repository/DebridLibraryServiceTorBoxFilesTest.kt
git commit -m "$(cat <<'EOF'
feat(torbox): add nextPlayableFileInTorrent for within-torrent autoplay

Pure helper (pickNextFileInTorrent) sorts playable files alphabetically
and returns the entry after currentFileId. Public suspend wrapper
nextPlayableFileInTorrent queries TorBox by torrent id and resolves
through the pure helper. Uses indexed-for per CLAUDE.md #4.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Drop eager `requestDownloadLink` from `fetchTorBoxItems`

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/repository/DebridLibraryService.kt:516-545`

- [ ] **Step 1: Add a regression test guarding zero `requestDownloadLink` calls per refresh**

Append to `DebridLibraryServiceTorBoxFilesTest.kt`:

```kotlin
import io.mockk.coVerify
import io.mockk.mockk

@Test
fun `mapTorBoxItem produces an entry with null directPlaybackUrl`() {
    // Sanity check that the spec-mandated "lazy URL" decision is preserved at
    // construction time: we no longer eagerly resolve, so directPlaybackUrl is null.
    val torrent = TorBoxTorrentListItemDto(
        id = 7, name = "Movie",
        files = listOf(
            TorBoxFileDto(
                id = 10, name = "movie.mkv", shortName = "movie.mkv",
                size = 800L * 1024L * 1024L, mimeType = "video/x-matroska"
            )
        )
    )
    val file = torrent.files.first()
    val entry = DebridLibraryService.buildTorBoxEntry(torrent, file)
    assertEquals(null, entry.directPlaybackUrl)
    assertEquals("tb:torrent:7:file:10", entry.id)
}
```

- [ ] **Step 2: Run to verify the new test fails**

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.repository.DebridLibraryServiceTorBoxFilesTest" 2>&1 | tail -10`
Expected: FAIL — `Unresolved reference 'buildTorBoxEntry'`.

- [ ] **Step 3: Refactor `mapTorBoxItem` into a public pure builder and drop eager URL fetch**

Replace `mapTorBoxItem` (line ~615) signature and body so it no longer takes `directUrl`:

```kotlin
companion object {
    // ... existing TorBoxNextFile + isTorBoxFilePlayable + pickNextFileInTorrent ...

    @JvmStatic
    fun buildTorBoxEntry(
        torrent: TorBoxTorrentListItemDto,
        file: TorBoxFileDto
    ): LibraryEntry {
        val filename = file.shortName
            ?.takeIf { it.isNotBlank() }
            ?: file.name
            ?.takeIf { it.isNotBlank() }
            ?: torrent.name.orEmpty().ifBlank { "TorBox File" }
        return LibraryEntry(
            id = "tb:torrent:${torrent.id}:file:${file.id}",
            type = "other",                       // type inference moved into the instance method below
            name = stripVideoExtension(filename),
            poster = null,
            background = null,
            logo = null,
            description = torrent.name,
            releaseInfo = file.size?.takeIf { it > 0L }?.let { "${it / (1024 * 1024)} MB" },
            imdbRating = null,
            genres = emptyList(),
            addonBaseUrl = null,
            listKeys = setOf(TORBOX_LIST_KEY),
            listedAt = parseIsoToMillis(torrent.createdAt ?: torrent.legacyCreatedAt),
            directPlaybackUrl = null,             // ← lazy: resolved on click
            playbackStreamName = filename,
            playbackFilename = filename,
            playbackSizeBytes = file.size
        )
    }
}
```

(`type = "other"` is a placeholder; the next step adds back the inferred type via an instance wrapper.)

- [ ] **Step 4: Add a thin instance wrapper that re-applies `inferContentType`**

Right after the public companion `buildTorBoxEntry`, in the class body (not companion), add:

```kotlin
private fun mapTorBoxItem(
    torrent: TorBoxTorrentListItemDto,
    file: TorBoxFileDto
): LibraryEntry {
    val base = buildTorBoxEntry(torrent, file)
    val typed = inferContentType(base.playbackFilename, file.mimeType)
    return if (typed == base.type) base else base.copy(type = typed)
}
```

- [ ] **Step 5: Strip eager URL resolution from `fetchTorBoxItems`**

Replace the body of `fetchTorBoxItems` (line ~516) with:

```kotlin
private suspend fun fetchTorBoxItems(apiKey: String): List<LibraryEntry> = withContext(Dispatchers.IO) {
    val response = torBoxProvider.fetchTorrentList(apiKey = apiKey, limit = 100)
        ?: return@withContext emptyList()

    val candidates = response.data.orEmpty()
        .filter { it.id != null && it.files.isNotEmpty() }
        .filter { it.isDownloaded() || it.resolvedState().equals("downloaded", ignoreCase = true) }
        .take(100)

    val out = mutableListOf<LibraryEntry>()
    for (i in candidates.indices) {                          // CLAUDE.md #4 — indexed for inside suspend
        val torrent = candidates[i]
        for (j in torrent.files.indices) {
            val file = torrent.files[j]
            if (file.id == null || !isLikelyPlayable(file)) continue
            out += mapTorBoxItem(torrent, file)
        }
    }
    out
}
```

The `Semaphore`, `coroutineScope { … awaitAll }`, and `requestTorBoxDownload` calls are removed from this function. (`requestTorBoxDownload` at line ~782 stays — it's called from `TorBoxDirectPlayHandler` in Task 5; do not delete the function.)

- [ ] **Step 6: Run all TorBox tests**

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.repository.DebridLibraryServiceTorBoxFilesTest" 2>&1 | tail -10`
Expected: PASS — 11 tests.

- [ ] **Step 7: Build the full app to verify no broken callers**

Run: `./gradlew :app:compileUniversalDebugKotlin 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL with no errors. Warnings about unnecessary null checks etc. are acceptable.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/DebridLibraryService.kt \
        app/src/test/java/com/nexio/tv/data/repository/DebridLibraryServiceTorBoxFilesTest.kt
git commit -m "$(cat <<'EOF'
feat(torbox): drop eager requestdl prefetch from fetchTorBoxItems

A 100-torrent library used to fire ~100 requestDownloadLink calls per
refresh (semaphore-limited 6 concurrent) and stored a directPlaybackUrl
that was often stale by the time the user clicked. New mapTorBoxItem
emits LibraryEntry with directPlaybackUrl = null; the URL is resolved
fresh on click by TorBoxDirectPlayHandler (next task). buildTorBoxEntry
extracted to a pure companion helper for unit-testability.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: `TorBoxResumeStore`

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/local/TorBoxResumeStore.kt`
- Test: `app/src/test/java/com/nexio/tv/data/local/TorBoxResumeStoreTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/nexio/tv/data/local/TorBoxResumeStoreTest.kt`:

```kotlin
package com.nexio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TorBoxResumeStoreTest {

    private fun fakeDataStore(): DataStore<Preferences> {
        val state = MutableStateFlow<Preferences>(mutablePreferencesOf())
        return object : DataStore<Preferences> {
            override val data = state
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                val mutable = (state.value as Preferences).toMutablePreferences()
                val next = transform(mutable)
                state.update { next }
                return next
            }
            private fun Preferences.toMutablePreferences(): MutablePreferences {
                val mp = mutablePreferencesOf()
                this.asMap().forEach { (k, v) -> mp[k as Preferences.Key<Any>] = v }
                return mp
            }
        }
    }

    @Test
    fun `loadPosition returns null when nothing saved`() = runTest {
        val store = TorBoxResumeStore(fakeDataStore())
        assertNull(store.loadPosition(torrentId = 7, fileId = 10))
    }

    @Test
    fun `savePosition then loadPosition round-trips millis`() = runTest {
        val store = TorBoxResumeStore(fakeDataStore())
        store.savePosition(torrentId = 7, fileId = 10, positionMs = 123_456L, durationMs = 3_600_000L)
        assertEquals(123_456L, store.loadPosition(torrentId = 7, fileId = 10))
    }

    @Test
    fun `savePosition near end clears stored entry`() = runTest {
        val store = TorBoxResumeStore(fakeDataStore())
        store.savePosition(torrentId = 7, fileId = 10, positionMs = 1_500_000L, durationMs = 3_600_000L)
        // Within 30s of end (durationMs - 30_000 = 3_570_000) — should auto-clear
        store.savePosition(torrentId = 7, fileId = 10, positionMs = 3_580_000L, durationMs = 3_600_000L)
        assertNull(store.loadPosition(torrentId = 7, fileId = 10))
    }

    @Test
    fun `clear removes the entry`() = runTest {
        val store = TorBoxResumeStore(fakeDataStore())
        store.savePosition(torrentId = 7, fileId = 10, positionMs = 100L, durationMs = 1_000_000L)
        store.clear(torrentId = 7, fileId = 10)
        assertNull(store.loadPosition(torrentId = 7, fileId = 10))
    }

    @Test
    fun `entries are isolated per torrent and file`() = runTest {
        val store = TorBoxResumeStore(fakeDataStore())
        store.savePosition(torrentId = 7, fileId = 10, positionMs = 100L, durationMs = 1_000_000L)
        store.savePosition(torrentId = 7, fileId = 11, positionMs = 200L, durationMs = 1_000_000L)
        store.savePosition(torrentId = 8, fileId = 10, positionMs = 300L, durationMs = 1_000_000L)
        assertEquals(100L, store.loadPosition(7, 10))
        assertEquals(200L, store.loadPosition(7, 11))
        assertEquals(300L, store.loadPosition(8, 10))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.local.TorBoxResumeStoreTest" 2>&1 | tail -15`
Expected: FAIL — `Unresolved reference: TorBoxResumeStore`.

- [ ] **Step 3: Implement `TorBoxResumeStore`**

Create `app/src/main/java/com/nexio/tv/data/local/TorBoxResumeStore.kt`:

```kotlin
package com.nexio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nexio.tv.core.profile.ProfileManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.torBoxResumeDataStore: DataStore<Preferences> by preferencesDataStore(name = "torbox_resume_v1")

/**
 * Per-profile resume position store for TorBox library playback.
 *
 * Keys: "torbox:p{profileId}:t{torrentId}:f{fileId}" → Long millis.
 *
 * Per CLAUDE.md #3 this is small scalar data (one Long per key); no JSON blobs.
 * The store auto-clears entries within 30 s of the end of media so completed
 * files do not leave stale resume points.
 */
@Singleton
class TorBoxResumeStore(
    private val dataStore: DataStore<Preferences>,
    private val profileManager: ProfileManager? = null,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        profileManager: ProfileManager,
    ) : this(context.torBoxResumeDataStore, profileManager)

    suspend fun savePosition(torrentId: Int, fileId: Int, positionMs: Long, durationMs: Long) {
        val nearEndThresholdMs = durationMs - NEAR_END_THRESHOLD_MS
        if (durationMs > 0L && positionMs >= nearEndThresholdMs) {
            clear(torrentId, fileId)
            return
        }
        val key = preferenceKey(torrentId, fileId)
        dataStore.edit { it[key] = positionMs }
    }

    suspend fun loadPosition(torrentId: Int, fileId: Int): Long? {
        val key = preferenceKey(torrentId, fileId)
        return dataStore.data.map { it[key] }.first()
    }

    suspend fun clear(torrentId: Int, fileId: Int) {
        val key = preferenceKey(torrentId, fileId)
        dataStore.edit { it.remove(key) }
    }

    private suspend fun preferenceKey(torrentId: Int, fileId: Int): Preferences.Key<Long> {
        val profileId = profileManager?.activeProfileId?.value ?: 0
        return longPreferencesKey("torbox:p$profileId:t$torrentId:f$fileId")
    }

    companion object {
        const val NEAR_END_THRESHOLD_MS: Long = 30_000L
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.local.TorBoxResumeStoreTest" 2>&1 | tail -10`
Expected: PASS — 5 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/local/TorBoxResumeStore.kt \
        app/src/test/java/com/nexio/tv/data/local/TorBoxResumeStoreTest.kt
git commit -m "$(cat <<'EOF'
feat(torbox): add TorBoxResumeStore for per-file resume positions

Per-profile Preferences DataStore mapping torbox:p{profile}:t{torrent}:f{file}
to position millis. savePosition auto-clears within 30 s of end-of-media so
finished files do not leave stale resume points. Compliant with CLAUDE.md #3
(small scalars only, no JSON blobs).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: `TorBoxDirectPlayHandler`

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/repository/TorBoxDirectPlayHandler.kt`
- Create: `app/src/main/java/com/nexio/tv/domain/model/TorBoxPlaybackContext.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/TorBoxDirectPlayHandlerTest.kt`

- [ ] **Step 1: Add the playback-context model first**

Create `app/src/main/java/com/nexio/tv/domain/model/TorBoxPlaybackContext.kt`:

```kotlin
package com.nexio.tv.domain.model

/** Identity of a TorBox library file. Parsed by the Player out of route args. */
data class TorBoxPlaybackContext(
    val torrentId: Int,
    val fileId: Int,
    val fileName: String,
) {
    companion object {
        private val ID_PATTERN = Regex("""^tb:torrent:(\d+):file:(\d+)$""")

        /**
         * Parse a [TorBoxPlaybackContext] from the existing route args (`videoId` is the
         * id format `mapTorBoxItem` already emits; `filename` is `fileName`). Returns null
         * unless the route originated from the TorBox library tab.
         */
        fun fromRouteArgs(launchSource: String?, videoId: String?, filename: String?): TorBoxPlaybackContext? {
            if (launchSource != "torbox") return null
            val match = ID_PATTERN.matchEntire(videoId.orEmpty()) ?: return null
            val name = filename?.takeIf { it.isNotBlank() } ?: return null
            return TorBoxPlaybackContext(
                torrentId = match.groupValues[1].toInt(),
                fileId = match.groupValues[2].toInt(),
                fileName = name,
            )
        }
    }
}
```

- [ ] **Step 2: Write the failing handler tests**

Create `app/src/test/java/com/nexio/tv/data/repository/TorBoxDirectPlayHandlerTest.kt`:

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.data.integration.debrid.TorBoxIntegrationProvider
import com.nexio.tv.data.local.TorBoxResumeStore
import com.nexio.tv.data.local.TorBoxSettingsDataStore
import com.nexio.tv.data.remote.dto.debrid.TorBoxEnvelopeDto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TorBoxDirectPlayHandlerTest {

    private val provider = mockk<TorBoxIntegrationProvider>()
    private val resumeStore = mockk<TorBoxResumeStore>()
    private val settings = mockk<TorBoxSettingsDataStore>(relaxed = true)

    private fun handler(): TorBoxDirectPlayHandler {
        coEvery { settings.settings } returns MutableStateFlow(
            TorBoxSettingsDataStore.Settings(apiKey = "tb-key")
        )
        return TorBoxDirectPlayHandler(provider, resumeStore, settings)
    }

    @Test
    fun `resolve returns Resolved with fresh url and resume position`() = runTest {
        coEvery {
            provider.requestDownloadLink(apiKey = "tb-key", torrentId = 7, fileId = 10)
        } returns "https://torbox.example/stream.mkv"
        coEvery { resumeStore.loadPosition(7, 10) } returns 60_000L

        val out = handler().resolve(torrentId = 7, fileId = 10, fileName = "movie.mkv")

        assertTrue(out is TorBoxResolvedPlayback.Resolved)
        out as TorBoxResolvedPlayback.Resolved
        assertEquals("https://torbox.example/stream.mkv", out.url)
        assertEquals(60_000L, out.resumePositionMs)
        coVerify(exactly = 1) {
            provider.requestDownloadLink(apiKey = "tb-key", torrentId = 7, fileId = 10)
        }
    }

    @Test
    fun `resolve returns Resolved with zero resume position when none stored`() = runTest {
        coEvery {
            provider.requestDownloadLink(apiKey = "tb-key", torrentId = 7, fileId = 10)
        } returns "https://torbox.example/stream.mkv"
        coEvery { resumeStore.loadPosition(7, 10) } returns null

        val out = handler().resolve(torrentId = 7, fileId = 10, fileName = "movie.mkv")

        out as TorBoxResolvedPlayback.Resolved
        assertEquals(0L, out.resumePositionMs)
    }

    @Test
    fun `resolve returns Failed when provider returns blank url`() = runTest {
        coEvery {
            provider.requestDownloadLink(apiKey = "tb-key", torrentId = 7, fileId = 10)
        } returns ""
        coEvery { resumeStore.loadPosition(7, 10) } returns null

        val out = handler().resolve(torrentId = 7, fileId = 10, fileName = "movie.mkv")

        assertTrue(out is TorBoxResolvedPlayback.Failed)
    }

    @Test
    fun `resolve returns Failed when provider returns null`() = runTest {
        coEvery {
            provider.requestDownloadLink(apiKey = "tb-key", torrentId = 7, fileId = 10)
        } returns null
        coEvery { resumeStore.loadPosition(7, 10) } returns null

        val out = handler().resolve(torrentId = 7, fileId = 10, fileName = "movie.mkv")

        assertTrue(out is TorBoxResolvedPlayback.Failed)
    }

    @Test
    fun `resolve returns Failed when api key is blank`() = runTest {
        coEvery { settings.settings } returns MutableStateFlow(
            TorBoxSettingsDataStore.Settings(apiKey = "")
        )
        val out = TorBoxDirectPlayHandler(provider, resumeStore, settings)
            .resolve(torrentId = 7, fileId = 10, fileName = "movie.mkv")

        assertTrue(out is TorBoxResolvedPlayback.Failed)
        out as TorBoxResolvedPlayback.Failed
        assertEquals("TorBox is not connected.", out.message)
    }
}
```

(If `TorBoxSettingsDataStore.Settings` is named differently in this codebase, adapt the construction; the explore confirmed `torBoxSettingsDataStore.settings.first().apiKey` is the existing access path.)

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.repository.TorBoxDirectPlayHandlerTest" 2>&1 | tail -15`
Expected: FAIL — `Unresolved reference: TorBoxDirectPlayHandler`.

- [ ] **Step 4: Implement the handler**

Create `app/src/main/java/com/nexio/tv/data/repository/TorBoxDirectPlayHandler.kt`:

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.data.integration.debrid.TorBoxIntegrationProvider
import com.nexio.tv.data.local.TorBoxResumeStore
import com.nexio.tv.data.local.TorBoxSettingsDataStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

sealed interface TorBoxResolvedPlayback {
    data class Resolved(
        val url: String,
        val torrentId: Int,
        val fileId: Int,
        val fileName: String,
        val resumePositionMs: Long,
    ) : TorBoxResolvedPlayback

    data class Failed(val message: String) : TorBoxResolvedPlayback
}

@Singleton
class TorBoxDirectPlayHandler @Inject constructor(
    private val torBoxProvider: TorBoxIntegrationProvider,
    private val resumeStore: TorBoxResumeStore,
    private val settings: TorBoxSettingsDataStore,
) {
    suspend fun resolve(torrentId: Int, fileId: Int, fileName: String): TorBoxResolvedPlayback {
        val apiKey = settings.settings.first().apiKey.trim()
        if (apiKey.isBlank()) {
            return TorBoxResolvedPlayback.Failed("TorBox is not connected.")
        }
        val url = try {
            torBoxProvider.requestDownloadLink(apiKey = apiKey, torrentId = torrentId, fileId = fileId)
                .orEmpty()
                .trim()
        } catch (t: Throwable) {
            return TorBoxResolvedPlayback.Failed(t.message ?: "TorBox playback request failed.")
        }
        if (url.isBlank()) {
            return TorBoxResolvedPlayback.Failed("TorBox returned no playback link.")
        }
        val resume = resumeStore.loadPosition(torrentId, fileId) ?: 0L
        return TorBoxResolvedPlayback.Resolved(
            url = url,
            torrentId = torrentId,
            fileId = fileId,
            fileName = fileName,
            resumePositionMs = resume,
        )
    }
}
```

- [ ] **Step 5: Run tests to verify pass**

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.repository.TorBoxDirectPlayHandlerTest" 2>&1 | tail -10`
Expected: PASS — 5 tests.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/TorBoxDirectPlayHandler.kt \
        app/src/main/java/com/nexio/tv/domain/model/TorBoxPlaybackContext.kt \
        app/src/test/java/com/nexio/tv/data/repository/TorBoxDirectPlayHandlerTest.kt
git commit -m "$(cat <<'EOF'
feat(torbox): add TorBoxDirectPlayHandler + TorBoxPlaybackContext

Handler lazily resolves a fresh TorBox playback URL on click (one
requestDownloadLink round-trip) and reads any existing resume position
from TorBoxResumeStore. Returns a sealed Resolved/Failed result.
TorBoxPlaybackContext is the small parcelable identity Player parses
out of existing route args (videoId + launchSource + filename) — no
new nav-arg surface.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: `TorBoxAutoplayNext` facade

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/repository/TorBoxAutoplayNext.kt`

- [ ] **Step 1: Implement the thin wrapper**

Create `app/src/main/java/com/nexio/tv/data/repository/TorBoxAutoplayNext.kt`:

```kotlin
package com.nexio.tv.data.repository

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin facade over [DebridLibraryService.nextPlayableFileInTorrent] so the Player
 * module does not have to depend on the full library service surface.
 */
@Singleton
class TorBoxAutoplayNext @Inject constructor(
    private val library: DebridLibraryService,
) {
    suspend fun nextEntryInSameTorrent(
        torrentId: Int,
        currentFileId: Int
    ): DebridLibraryService.Companion.TorBoxNextFile? =
        library.nextPlayableFileInTorrent(torrentId = torrentId, currentFileId = currentFileId)
}
```

- [ ] **Step 2: Build to verify**

Run: `./gradlew :app:compileUniversalDebugKotlin 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/TorBoxAutoplayNext.kt
git commit -m "$(cat <<'EOF'
feat(torbox): add TorBoxAutoplayNext facade

Single-purpose injection target for the Player. Wraps
DebridLibraryService.nextPlayableFileInTorrent so the Player module
only depends on this small interface, not the full library service.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: `LibraryViewModel` wiring — click handler, refresh, command channel

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/library/LibraryViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/library/LibraryUiState.kt` (or wherever the UI state lives — check the file the VM imports it from)
- Test: `app/src/test/java/com/nexio/tv/ui/screens/library/LibraryViewModelTorBoxClickTest.kt` (new)

- [ ] **Step 1: Add the failing VM test**

Create `app/src/test/java/com/nexio/tv/ui/screens/library/LibraryViewModelTorBoxClickTest.kt`:

```kotlin
package com.nexio.tv.ui.screens.library

import app.cash.turbine.test
import com.nexio.tv.data.repository.DebridLibraryService
import com.nexio.tv.data.repository.TorBoxDirectPlayHandler
import com.nexio.tv.data.repository.TorBoxResolvedPlayback
import com.nexio.tv.domain.model.LibraryEntry
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTorBoxClickTest {

    private val handler = mockk<TorBoxDirectPlayHandler>()

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun viewModelUnderTest(): LibraryViewModel {
        // NOTE for implementer: the LibraryViewModel constructor has many dependencies. Construct
        // it with relaxed mocks for every other dependency; only `torBoxDirectPlayHandler` and the
        // dispatcher matter for these tests. See LibraryViewModelTest fixtures for a working pattern.
        TODO("Construct LibraryViewModel with mocks; inject `handler` as the TorBoxDirectPlayHandler dependency")
    }

    @Test
    fun `onTorBoxItemClick emits Resolving then Navigate when handler resolves`() = runTest {
        coEvery { handler.resolve(7, 10, "movie.mkv") } returns TorBoxResolvedPlayback.Resolved(
            url = "https://torbox.example/stream.mkv",
            torrentId = 7, fileId = 10, fileName = "movie.mkv",
            resumePositionMs = 0L
        )

        val vm = viewModelUnderTest()
        vm.directPlayCommands.test {
            vm.onTorBoxItemClick(
                LibraryEntry(
                    id = "tb:torrent:7:file:10", type = "movie", name = "movie",
                    poster = null, background = null, logo = null, description = null,
                    releaseInfo = null, imdbRating = null, genres = emptyList(),
                    addonBaseUrl = null, listKeys = setOf(DebridLibraryService.TORBOX_LIST_KEY),
                    playbackFilename = "movie.mkv"
                )
            )

            val first = awaitItem()
            assertTrue(first is DirectPlayCommand.Resolving)
            assertEquals("movie.mkv", (first as DirectPlayCommand.Resolving).fileName)

            val second = awaitItem()
            assertTrue(second is DirectPlayCommand.Navigate)
            second as DirectPlayCommand.Navigate
            assertEquals("https://torbox.example/stream.mkv", second.url)
            assertEquals(true, second.deterministicAutoplay)
            assertEquals(7, second.torBoxTorrentId)
            assertEquals(10, second.torBoxFileId)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onTorBoxItemClick emits Failed on handler failure and never Navigate`() = runTest {
        coEvery { handler.resolve(7, 10, "movie.mkv") } returns TorBoxResolvedPlayback.Failed("TorBox returned no playback link.")

        val vm = viewModelUnderTest()
        vm.directPlayCommands.test {
            vm.onTorBoxItemClick(
                LibraryEntry(
                    id = "tb:torrent:7:file:10", type = "movie", name = "movie",
                    poster = null, background = null, logo = null, description = null,
                    releaseInfo = null, imdbRating = null, genres = emptyList(),
                    addonBaseUrl = null, listKeys = setOf(DebridLibraryService.TORBOX_LIST_KEY),
                    playbackFilename = "movie.mkv"
                )
            )

            assertTrue(awaitItem() is DirectPlayCommand.Resolving)
            val failed = awaitItem()
            assertTrue(failed is DirectPlayCommand.Failed)
            assertEquals("TorBox returned no playback link.", (failed as DirectPlayCommand.Failed).message)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
```

- [ ] **Step 2: Run to verify the tests fail (unresolved references for the new VM API)**

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.library.LibraryViewModelTorBoxClickTest" 2>&1 | tail -15`
Expected: FAIL — `Unresolved reference: DirectPlayCommand`, `onTorBoxItemClick`, `directPlayCommands`.

- [ ] **Step 3: Add `DirectPlayCommand` sealed interface**

In `LibraryViewModel.kt` (top-level, outside the class), add:

```kotlin
internal sealed interface DirectPlayCommand {
    data class Resolving(val fileName: String) : DirectPlayCommand
    data class Navigate(
        val url: String,
        val torBoxTorrentId: Int,
        val torBoxFileId: Int,
        val fileName: String,
        val resumePositionMs: Long,
        val deterministicAutoplay: Boolean = true,
    ) : DirectPlayCommand
    data class Failed(val message: String) : DirectPlayCommand
}
```

- [ ] **Step 4: Add the click handler + command channel + refresh-now to the VM**

Inject `TorBoxDirectPlayHandler` (add it to the `@Inject internal constructor` parameter list). Add the channel and methods inside `LibraryViewModel`:

```kotlin
// near the existing flows
private val _directPlayCommands = MutableSharedFlow<DirectPlayCommand>(extraBufferCapacity = 4)
internal val directPlayCommands: SharedFlow<DirectPlayCommand> = _directPlayCommands.asSharedFlow()

private val _torBoxRefreshing = MutableStateFlow(false)
internal val torBoxRefreshing: StateFlow<Boolean> = _torBoxRefreshing.asStateFlow()

internal fun onTorBoxItemClick(entry: LibraryEntry) {
    val match = Regex("""^tb:torrent:(\d+):file:(\d+)$""").matchEntire(entry.id) ?: return
    val torrentId = match.groupValues[1].toInt()
    val fileId = match.groupValues[2].toInt()
    val fileName = entry.playbackFilename ?: entry.name
    viewModelScope.launch {
        _directPlayCommands.tryEmit(DirectPlayCommand.Resolving(fileName))
        when (val result = torBoxDirectPlayHandler.resolve(torrentId, fileId, fileName)) {
            is TorBoxResolvedPlayback.Resolved -> _directPlayCommands.tryEmit(
                DirectPlayCommand.Navigate(
                    url = result.url,
                    torBoxTorrentId = result.torrentId,
                    torBoxFileId = result.fileId,
                    fileName = result.fileName,
                    resumePositionMs = result.resumePositionMs,
                )
            )
            is TorBoxResolvedPlayback.Failed -> _directPlayCommands.tryEmit(
                DirectPlayCommand.Failed(result.message)
            )
        }
    }
}

internal fun refreshTorBoxLibraryNow() {
    viewModelScope.launch {
        _torBoxRefreshing.value = true
        try {
            libraryRepository.refreshTorBoxNow()
        } finally {
            _torBoxRefreshing.value = false
        }
    }
}
```

Add the imports at the top of the file:

```kotlin
import com.nexio.tv.data.repository.TorBoxDirectPlayHandler
import com.nexio.tv.data.repository.TorBoxResolvedPlayback
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
```

(Several of these may already be imported — check before adding duplicates.)

- [ ] **Step 5: Run the new tests + the existing LibraryViewModel test suite**

Run: `./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.ui.screens.library.LibraryViewModelTorBoxClickTest" --tests "com.nexio.tv.ui.screens.library.LibraryViewModelTest" 2>&1 | tail -15`
Expected: PASS for both. If `LibraryViewModelTest` fixtures broke because of the new constructor parameter, add a `mockk<TorBoxDirectPlayHandler>(relaxed = true)` to those fixtures.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/library/LibraryViewModel.kt \
        app/src/test/java/com/nexio/tv/ui/screens/library/LibraryViewModelTorBoxClickTest.kt
git commit -m "$(cat <<'EOF'
feat(torbox): wire LibraryViewModel direct-play + manual refresh

Adds onTorBoxItemClick that resolves via TorBoxDirectPlayHandler and
emits a DirectPlayCommand (Resolving → Navigate or Failed) into a
SharedFlow the screen collects. Adds refreshTorBoxLibraryNow that
drives libraryRepository.refreshTorBoxNow with a torBoxRefreshing
StateFlow so the UI can spin.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: `LibraryScreen` UI — tab-focus refresh, refresh button, navigate

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/library/LibraryScreen.kt`

- [ ] **Step 1: Collect new VM state at the top of `LibraryScreen`**

Where the screen already does `val uiState by viewModel.uiState.collectAsStateWithLifecycle()`, add:

```kotlin
val torBoxRefreshing by viewModel.torBoxRefreshing.collectAsStateWithLifecycle()
val context = LocalContext.current
val navigator = LocalNexioNavigator.current  // or whichever Navigator the screen already uses
```

- [ ] **Step 2: Add a `LaunchedEffect` that refreshes on first TorBox-tab focus**

Inside the same Composable scope:

```kotlin
LaunchedEffect(uiState.selectedListKey) {
    if (uiState.selectedListKey == DebridLibraryService.TORBOX_LIST_KEY) {
        viewModel.refreshTorBoxLibraryNow()
    }
}
```

- [ ] **Step 3: Add a refresh affordance in the tab header**

Find the existing tab header rendering (the area that draws the tab strip, around line 815 — `tab.title`, `onClick = { onSelect(tab.key) }`). Below the tab strip or in the header area when the selected tab is TorBox, render:

```kotlin
if (uiState.selectedListKey == DebridLibraryService.TORBOX_LIST_KEY) {
    SettingsActionButton(
        onClick = { viewModel.refreshTorBoxLibraryNow() },
        enabled = !torBoxRefreshing,
        surface = SettingsButtonSurface.BackgroundElevated,
    ) {
        Text(
            if (torBoxRefreshing) stringResource(R.string.action_refreshing)
            else stringResource(R.string.action_refresh)
        )
    }
}
```

(`action_refresh` and `action_refreshing` may already exist — if not, add them to `strings.xml`:)

```xml
<string name="action_refresh">Refresh</string>
<string name="action_refreshing">Refreshing…</string>
```

- [ ] **Step 4: Collect `directPlayCommands` and navigate**

Below the existing `LaunchedEffect`s, add:

```kotlin
LaunchedEffect(viewModel) {
    viewModel.directPlayCommands.collect { command ->
        when (command) {
            is DirectPlayCommand.Resolving -> {
                // Brief toast / inline indicator; reuses the existing snackbar pattern in this screen.
                Toast.makeText(context, "Opening ${command.fileName}…", Toast.LENGTH_SHORT).show()
            }
            is DirectPlayCommand.Failed -> {
                Toast.makeText(context, command.message, Toast.LENGTH_LONG).show()
            }
            is DirectPlayCommand.Navigate -> {
                navigator.navigate(
                    Screen.Player.createRoute(
                        streamUrl = command.url,
                        title = command.fileName,
                        videoId = "tb:torrent:${command.torBoxTorrentId}:file:${command.torBoxFileId}",
                        filename = command.fileName,
                        launchSource = "torbox",
                        resumePositionMs = command.resumePositionMs.takeIf { it > 0L },
                        deterministicAutoplay = command.deterministicAutoplay,
                        // Remaining createRoute args use defaults — see Screen.kt:96 for the signature.
                    )
                )
            }
        }
    }
}
```

Add imports at the top of `LibraryScreen.kt`:

```kotlin
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import com.nexio.tv.data.repository.DebridLibraryService
import com.nexio.tv.ui.navigation.Screen
```

- [ ] **Step 5: Wire the card click handler**

Find where library entries are rendered (likely a `LibraryGrid` or `LibraryCard` Composable invocation). When `selectedListKey == "service:torbox"`, the card's `onClick` should call `viewModel.onTorBoxItemClick(entry)` *instead of* the default detail-screen navigation. Wrap the existing click with:

```kotlin
val onCardClick: (LibraryEntry) -> Unit = if (uiState.selectedListKey == DebridLibraryService.TORBOX_LIST_KEY) {
    { entry -> viewModel.onTorBoxItemClick(entry) }
} else {
    { entry -> /* existing handler — call by name, do not duplicate */ }
}
```

(Locate the existing handler — likely `onCardClick = { entry -> ... }` near the grid call site — and reuse it in the `else` branch.)

- [ ] **Step 6: Build the app**

Run: `./gradlew :app:compileUniversalDebugKotlin 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL. Address any compile errors before continuing.

- [ ] **Step 7: Smoke-test on device**

Per CLAUDE.md #8, smoke tests against any home / library surface MUST select a profile before scanning:

```bash
adb -s 192.168.50.98:5555 shell am force-stop com.nexiodebug.tv
adb -s 192.168.50.98:5555 logcat -c
adb -s 192.168.50.98:5555 shell monkey -p com.nexiodebug.tv 1
sleep 5
adb -s 192.168.50.98:5555 shell input keyevent KEYCODE_DPAD_CENTER   # tap profile
sleep 30                                                              # home loads + rails populate
adb -s 192.168.50.98:5555 logcat -d -t 600 | grep -E "FATAL|AndroidRuntime|ANR|ClassCast|NoSuchMethod" | tail -10
```

Expected: zero matches. Then manually navigate to Library → TorBox tab and verify the grid renders without crash.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/library/LibraryScreen.kt \
        app/src/main/res/values/strings.xml
git commit -m "$(cat <<'EOF'
feat(torbox): wire LibraryScreen direct-play + refresh affordance

LaunchedEffect refreshes the TorBox library when the user focuses the
service:torbox tab. Refresh button in the tab header spins on
torBoxRefreshing. Card clicks on the TorBox tab call onTorBoxItemClick
and the DirectPlayCommand stream is collected into Navigator routes
that skip detail+stream-selection (deterministicAutoplay=true).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 9: Player wiring — context parse, resume save, autoplay-next

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerViewModel.kt` (and `PlayerRuntimeControllerLifecycle.kt` / `PlayerRuntimeControllerObservers.kt` for tick + ended hooks — open them first to locate the existing position-tick and onMediaEnded hooks)

- [ ] **Step 1: Locate the Player route-arg ingestion and the tick / ended hooks**

Open `PlayerViewModel.kt` and grep for `launchSource`, `videoId`, and the SavedStateHandle / nav-args extraction. The TorBox context will be parsed once, near construction, from the existing args. Open `PlayerRuntimeControllerLifecycle.kt` / `PlayerRuntimeControllerObservers.kt` and locate (a) the periodic position tick (likely a `playbackPositionMs.collect { … }` or a `setInterval`-equivalent) and (b) the end-of-media handler (likely a `Player.Listener.onPlaybackStateChanged(STATE_ENDED)` or `onMediaItemTransition`).

- [ ] **Step 2: Inject `TorBoxResumeStore` and `TorBoxAutoplayNext` into `PlayerViewModel`**

Add to the `@Inject` constructor parameter list:

```kotlin
private val torBoxResumeStore: TorBoxResumeStore,
private val torBoxAutoplayNext: TorBoxAutoplayNext,
```

And the imports:

```kotlin
import com.nexio.tv.data.local.TorBoxResumeStore
import com.nexio.tv.data.repository.TorBoxAutoplayNext
import com.nexio.tv.data.repository.TorBoxDirectPlayHandler
import com.nexio.tv.data.repository.TorBoxResolvedPlayback
import com.nexio.tv.domain.model.TorBoxPlaybackContext
```

(Also inject `TorBoxDirectPlayHandler` — autoplay-next needs to resolve fresh URLs.)

- [ ] **Step 3: Parse `TorBoxPlaybackContext` at construction time**

After the existing route-arg extraction in `PlayerViewModel`'s init, add:

```kotlin
private var torBoxContext: TorBoxPlaybackContext? = TorBoxPlaybackContext.fromRouteArgs(
    launchSource = savedStateHandle["launchSource"],
    videoId = savedStateHandle["videoId"],
    filename = savedStateHandle["filename"],
)
```

Hold it as a `var` (not a field on UiState — keeping CLAUDE.md #2 happy by not retaining state in observed `UiState`) so autoplay-next can swap it when transitioning to the next file.

- [ ] **Step 4: Hook the position tick to `TorBoxResumeStore.savePosition`**

In whichever controller / VM observer drives the per-10s position tick, add (inside the existing tick block):

```kotlin
torBoxContext?.let { ctx ->
    viewModelScope.launch {
        torBoxResumeStore.savePosition(
            torrentId = ctx.torrentId,
            fileId = ctx.fileId,
            positionMs = currentPositionMs,
            durationMs = currentDurationMs,
        )
    }
}
```

Place it next to any existing resume-save invocation (the codebase already saves Trakt watch progress on a similar tick — mirror that placement).

- [ ] **Step 5: Hook end-of-media to `TorBoxAutoplayNext` + `TorBoxDirectPlayHandler`**

In the end-of-media handler (`onPlaybackStateChanged(STATE_ENDED)` or equivalent), add:

```kotlin
val ctx = torBoxContext ?: return  // not a TorBox playback session — fall through to the existing handler
viewModelScope.launch {
    torBoxResumeStore.clear(ctx.torrentId, ctx.fileId)
    val next = torBoxAutoplayNext.nextEntryInSameTorrent(ctx.torrentId, ctx.fileId)
    if (next == null) {
        navigateBackToLibrary()    // existing nav helper; if absent, mirror the pop-back-on-end pattern already used
        return@launch
    }
    when (val resolved = torBoxDirectPlayHandler.resolve(next.torrentId, next.fileId, next.fileName)) {
        is TorBoxResolvedPlayback.Resolved -> {
            torBoxContext = TorBoxPlaybackContext(next.torrentId, next.fileId, next.fileName)
            replaceMedia(url = resolved.url, fileName = next.fileName, resumePositionMs = 0L)
        }
        is TorBoxResolvedPlayback.Failed -> {
            postErrorToast(resolved.message)
            navigateBackToLibrary()
        }
    }
}
```

`replaceMedia(...)` exists in the Player controller surface — if it has a different name in this codebase (`prepareMedia`, `setMediaItem`, etc.), use that; check `PlayerRuntimeControllerStreams.kt` for the existing in-session media swap.

- [ ] **Step 6: Build & smoke-test**

Run: `./gradlew :app:compileUniversalDebugKotlin 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL.

Then on device (profile-selection prelude per CLAUDE.md #8):

```bash
adb -s 192.168.50.98:5555 shell am force-stop com.nexiodebug.tv
adb -s 192.168.50.98:5555 logcat -c
adb -s 192.168.50.98:5555 shell monkey -p com.nexiodebug.tv 1
sleep 5
adb -s 192.168.50.98:5555 shell input keyevent KEYCODE_DPAD_CENTER
sleep 30
# manual: navigate to Library → TorBox tab → click a multi-file torrent's first episode
# expected: spinner overlay briefly, then Player opens; tick saves should be visible:
adb -s 192.168.50.98:5555 logcat -d -t 300 | grep -E "TorBoxResumeStore|TorBoxDirectPlayHandler|FATAL|ANR" | tail -20
```

Expected: no FATAL/ANR. Stop playback briefly, exit, re-open the same file — Player should resume.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/screens/player/PlayerViewModel.kt \
        app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerLifecycle.kt \
        app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerObservers.kt
# (Adjust the file list to match what you actually modified.)
git commit -m "$(cat <<'EOF'
feat(torbox): Player resume save + within-torrent autoplay-next

PlayerViewModel parses TorBoxPlaybackContext out of existing route args
(launchSource=torbox + videoId=tb:torrent:N:file:N + filename) once at
init. Per-tick position is persisted into TorBoxResumeStore so re-opening
the same file resumes. On end-of-media the next playable file in the same
torrent is resolved via TorBoxAutoplayNext + TorBoxDirectPlayHandler and
loaded in-place; null next file pops back to the library tab.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Final verification

- [ ] **Run the full unit-test suite for the modified modules**

```bash
./gradlew :app:testUniversalDebugUnitTest --tests "com.nexio.tv.data.*" --tests "com.nexio.tv.ui.screens.library.*" 2>&1 | tail -30
```

Expected: all green. Address any breakage in pre-existing tests that depended on the eager `directPlaybackUrl` for TorBox entries (it is now null on TorBox entries — Real-Debrid / Premiumize entries are unchanged).

- [ ] **End-to-end smoke**

With the existing TorBox device-code feature already on `origin/main`, connect a real account, drop a small torrent into TorBox (or use one already there), and walk through:

1. Authorize TorBox via QR (skill from previous session — should already work).
2. Open Library → TorBox tab. Verify the tab appears and grid renders within ~3 s.
3. Click a single-file movie. Player opens (no detail, no stream selection). Watch ~30 s, exit. Re-click — resumes.
4. Click episode 1 of a season pack. Watch to end (use the player's "skip to end" if available). Verify episode 2 auto-loads.
5. Disconnect TorBox via settings. Verify the tab disappears within one refresh cycle.

- [ ] **Final commit / push**

Per CLAUDE.md #7, stage by explicit path. After verifying `git status -sb` only lists your TorBox files:

```bash
git push origin main
```

---

## Self-review checklist (run before invoking executing-plans)

- [ ] **Spec coverage:** Every locked decision from the spec maps to a task — surface (Task 7/8), rendering (Task 3 builds filename-only entries), refresh policy (Task 7/8), lazy URL (Tasks 3+5), playable filter (Task 1), resume + autoplay-next (Tasks 4, 6, 9).
- [ ] **Placeholder scan:** No TBDs / TODOs / "handle edge cases" stand-ins. The only `TODO()` is the explicit one in Step 1 of Task 7, calling out that the test setup needs the implementer to mirror an existing fixture pattern they have to look up.
- [ ] **Type consistency:** `TorBoxNextFile` defined once (Task 2 companion), referenced by Tasks 6 and 9. `TorBoxResolvedPlayback` defined in Task 5, consumed by Tasks 7 and 9. `TorBoxPlaybackContext` defined in Task 5 step 1, parsed by Task 9 step 3. `DirectPlayCommand` defined in Task 7 step 3, consumed by Task 8 step 4. All consistent.
- [ ] **CLAUDE.md compliance:** Indexed-for in suspending paths (Tasks 2, 3). No `git add -A` or stash anywhere (every commit is explicit-path). Smoke tests pre-select a profile (Tasks 8 step 7 + 9 step 6 + final E2E).
