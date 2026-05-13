package com.nexio.tv.core.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.nexio.tv.data.local.FileBackedJsonObjectStore
import com.nexio.tv.domain.model.ProviderIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class MediaClipStoreTest {
    private lateinit var context: Context
    private lateinit var prefsName: String
    private var nowMs: Long = 1_000_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefsName = "media_clip_store_test_${System.nanoTime()}"
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().commit()
        fileNamespace().deleteRecursively()
        FileBackedJsonObjectStore.resetSharedStateForTest(entriesFile())
    }

    @Test
    fun `media clip store persists youtube id in file backed store after restart`() {
        val identity = tmdbMovieIdentity()
        val firstStore = MediaClipStore(context, prefsName = prefsName, clock = { nowMs })

        firstStore.storeCandidates(
            listOf(
                MediaClipCandidate(
                    clipId = "tmdb:movie:550:abc123",
                    contentId = identity,
                    provider = "TMDB",
                    source = MediaClipSource.PROVIDER,
                    scope = MediaClipScope.Title(identity),
                    clipType = MediaClipType.TRAILER,
                    title = "Official Trailer",
                    language = "en",
                    site = ClipSite.YOUTUBE,
                    externalVideoId = "abc123",
                    playbackRef = MediaClipPlaybackRef.YouTubeId("abc123"),
                    confidence = Confidence.HIGH,
                    sourceTrace = listOf("tmdb.movie.videos"),
                    fetchedAtMs = nowMs
                )
            )
        )

        assertTrue(entriesFile().exists())
        assertEquals(emptyMap<String, Any>(), context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).all)
        FileBackedJsonObjectStore.resetSharedStateForTest(entriesFile())
        val restartedStore = MediaClipStore(context, prefsName = prefsName, clock = { nowMs + 10L })
        val clips = restartedStore.getCandidates(
            identity = identity,
            scope = MediaClipScope.Title(identity),
            clipTypes = setOf(MediaClipType.TRAILER),
            language = "en"
        )

        assertEquals(1, clips.size)
        assertEquals("abc123", clips.single().externalVideoId)
        assertEquals(MediaClipPlaybackRef.YouTubeId("abc123"), clips.single().playbackRef)
    }

    @Test
    fun `media clip store migrates legacy shared preferences and clears them`() {
        val identity = tmdbMovieIdentity()
        val legacyPrefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val record = legacyRecordJson(
            key = "media-clip:legacy",
            clipId = "tmdb:movie:550:legacy",
            contentId = identity,
            externalVideoId = "legacy"
        )
        legacyPrefs.edit()
            .putString("media-clip:legacy", Gson().toJson(record))
            .commit()

        val store = MediaClipStore(context, prefsName = prefsName, clock = { nowMs + 10L })
        val clips = store.getCandidates(
            identity = identity,
            scope = MediaClipScope.Title(identity),
            clipTypes = setOf(MediaClipType.TRAILER),
            language = "en"
        )

        assertEquals("legacy", clips.single().externalVideoId)
        assertEquals(emptyMap<String, Any>(), legacyPrefs.all)
        assertEquals("legacy", FileBackedJsonObjectStore(entriesFile())
            .get("media-clip:legacy")
            ?.get("externalVideoId")
            ?.asString)
    }

    @Test
    fun `media clip store migration does not overwrite existing file entry`() {
        val identity = tmdbMovieIdentity()
        val legacyPrefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        legacyPrefs.edit()
            .putString(
                "media-clip:same",
                Gson().toJson(
                    legacyRecordJson(
                        key = "media-clip:same",
                        clipId = "tmdb:movie:550:legacy",
                        contentId = identity,
                        externalVideoId = "legacy"
                    )
                )
            )
            .commit()
        FileBackedJsonObjectStore(entriesFile()).put(
            "media-clip:same",
            legacyRecordJson(
                key = "media-clip:same",
                clipId = "tmdb:movie:550:file",
                contentId = identity,
                externalVideoId = "file"
            )
        )
        FileBackedJsonObjectStore.resetSharedStateForTest(entriesFile())

        val store = MediaClipStore(context, prefsName = prefsName, clock = { nowMs + 10L })
        val clips = store.getCandidates(
            identity = identity,
            scope = MediaClipScope.Title(identity),
            clipTypes = setOf(MediaClipType.TRAILER),
            language = "en"
        )

        assertEquals("file", clips.single().externalVideoId)
        assertEquals(emptyMap<String, Any>(), legacyPrefs.all)
    }

    @Test
    fun `media clip store does not store expired playback url as durable candidate`() {
        val identity = tmdbMovieIdentity()
        val store = MediaClipStore(context, prefsName = prefsName, clock = { nowMs })

        store.storeCandidates(
            listOf(
                MediaClipCandidate(
                    clipId = "tmdb:movie:550:resolved",
                    contentId = identity,
                    provider = "TMDB",
                    source = MediaClipSource.PROVIDER,
                    scope = MediaClipScope.Title(identity),
                    clipType = MediaClipType.TRAILER,
                    title = "Resolved Stream",
                    language = "en",
                    site = ClipSite.PROVIDER,
                    externalVideoId = null,
                    playbackRef = MediaClipPlaybackRef.ResolvedPlaybackUri(
                        uri = "https://video.example.test/expired.m3u8",
                        expiresAtMs = nowMs + 5_000L
                    ),
                    confidence = Confidence.MEDIUM,
                    sourceTrace = listOf("youtube.playback.resolve"),
                    fetchedAtMs = nowMs
                )
            )
        )

        val clips = store.getCandidates(
            identity = identity,
            scope = MediaClipScope.Title(identity),
            clipTypes = setOf(MediaClipType.TRAILER),
            language = "en"
        )

        assertEquals(1, clips.size)
        assertNull(clips.single().playbackRef)
        assertNull(clips.single().providerUrlHash)
        assertNull(clips.single().redactedUrl)
    }

    @Test
    fun `media clip store stale candidate used when provider unavailable`() {
        val identity = tmdbMovieIdentity()
        val store = MediaClipStore(context, prefsName = prefsName, clock = { nowMs })
        store.storeCandidates(
            candidates = listOf(
                MediaClipCandidate(
                    clipId = "tmdb:movie:550:stale",
                    contentId = identity,
                    provider = "TMDB",
                    source = MediaClipSource.PROVIDER,
                    scope = MediaClipScope.Title(identity),
                    clipType = MediaClipType.TRAILER,
                    title = null,
                    language = "en",
                    site = ClipSite.YOUTUBE,
                    externalVideoId = "stale",
                    playbackRef = MediaClipPlaybackRef.YouTubeId("stale"),
                    confidence = Confidence.MEDIUM,
                    sourceTrace = listOf("tmdb.movie.videos"),
                    fetchedAtMs = nowMs
                )
            ),
            freshTtlMs = 100L,
            staleTtlMs = 1_000L
        )

        nowMs += 500L

        val clips = store.getCandidates(
            identity = identity,
            scope = MediaClipScope.Title(identity),
            clipTypes = setOf(MediaClipType.TRAILER),
            language = "en",
            includeStale = true
        )

        assertEquals("stale", clips.single().externalVideoId)
        assertEquals(CacheDecision.STALE_HIT, clips.single().cacheDecision)
    }

    @Test
    fun `media clip store getCandidates reads file entries when shared preferences are empty`() {
        val identity = tmdbMovieIdentity()
        FileBackedJsonObjectStore(entriesFile()).put(
            "media-clip:file-only",
            legacyRecordJson(
                key = "media-clip:file-only",
                clipId = "tmdb:movie:550:file-only",
                contentId = identity,
                externalVideoId = "file-only"
            )
        )
        FileBackedJsonObjectStore.resetSharedStateForTest(entriesFile())

        val store = MediaClipStore(context, prefsName = prefsName, clock = { nowMs + 10L })
        val clips = store.getCandidates(
            identity = identity,
            scope = MediaClipScope.Title(identity),
            clipTypes = setOf(MediaClipType.TRAILER),
            language = "en"
        )

        assertEquals("file-only", clips.single().externalVideoId)
        assertFalse(context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).all.containsKey("media-clip:file-only"))
    }

    private fun tmdbMovieIdentity(): ContentIdentity =
        ContentIdentity(
            contentId = "tmdb:550",
            itemType = "movie",
            stableIds = ProviderIds(tmdb = "550", imdb = "tt0137523")
        )

    private fun fileNamespace(): File = File(context.filesDir, prefsName)

    private fun entriesFile(): File = File(fileNamespace(), "entries.json")

    private fun legacyRecordJson(
        key: String,
        clipId: String,
        contentId: ContentIdentity,
        externalVideoId: String
    ): JsonObject = JsonObject().apply {
        addProperty("key", key)
        addProperty("clipId", clipId)
        addProperty("contentId", contentId.contentId)
        addProperty("itemType", contentId.itemType)
        addProperty("tmdbId", contentId.stableIds.tmdb)
        addProperty("tvdbId", contentId.stableIds.tvdb)
        addProperty("imdbId", contentId.stableIds.imdb)
        addProperty("kitsuId", contentId.stableIds.kitsu)
        addProperty("provider", "TMDB")
        addProperty("source", MediaClipSource.PROVIDER.name)
        addProperty("scopeKind", "title")
        add("season", null)
        add("episode", null)
        addProperty("clipType", MediaClipType.TRAILER.name)
        addProperty("title", "Official Trailer")
        addProperty("language", "en")
        addProperty("site", ClipSite.YOUTUBE.name)
        addProperty("externalVideoId", externalVideoId)
        addProperty("playbackKind", "youtube")
        addProperty("youtubeId", externalVideoId)
        add("providerUrlHash", null)
        add("redactedUrl", null)
        addProperty("confidence", Confidence.HIGH.name)
        addProperty("fetchedAtMs", nowMs)
        addProperty("expiresAtMs", nowMs + 1_000L)
        addProperty("staleUntilMs", nowMs + 10_000L)
        add("sourceTrace", Gson().toJsonTree(listOf("tmdb.movie.videos")))
    }
}
