package com.nexio.tv.core.media

import android.content.Context
import android.content.ContextWrapper
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
        MediaClipTypedStore.resetSharedStateForTest(v2EntriesFile())
        FileBackedJsonObjectStore.resetSharedStateForTest(v1EntriesFile())
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

        assertTrue(v2EntriesFile().exists())
        assertEquals(emptyMap<String, Any>(), context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).all)
        MediaClipTypedStore.resetSharedStateForTest(v2EntriesFile())
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
    fun `default constructor writes under media clip store file namespace`() {
        val identity = tmdbMovieIdentity()
        val defaultFile = File(context.filesDir, "media-clip-store-v2/entries.json")
        File(context.filesDir, "media-clip-store-v1").deleteRecursively()
        File(context.filesDir, "media-clip-store-v2").deleteRecursively()
        MediaClipTypedStore.resetSharedStateForTest(defaultFile)
        val store = MediaClipStore(context, traceEvents = null)

        store.storeCandidates(
            listOf(
                MediaClipCandidate(
                    clipId = "tmdb:movie:550:default",
                    contentId = identity,
                    provider = "TMDB",
                    source = MediaClipSource.PROVIDER,
                    scope = MediaClipScope.Title(identity),
                    clipType = MediaClipType.TRAILER,
                    title = "Official Trailer",
                    language = "en",
                    site = ClipSite.YOUTUBE,
                    externalVideoId = "default",
                    playbackRef = MediaClipPlaybackRef.YouTubeId("default"),
                    confidence = Confidence.HIGH,
                    sourceTrace = listOf("tmdb.movie.videos"),
                    fetchedAtMs = nowMs
                )
            )
        )

        assertTrue(defaultFile.exists())
    }

    @Test
    fun `secondary constructor default prefs name writes under production file namespace`() {
        val identity = tmdbMovieIdentity()
        val defaultFile = File(context.filesDir, "media-clip-store-v2/entries.json")
        File(context.filesDir, "media-clip-store-v1").deleteRecursively()
        File(context.filesDir, "media-clip-store-v2").deleteRecursively()
        File(context.filesDir, "media_clip_store_v1").deleteRecursively()
        MediaClipTypedStore.resetSharedStateForTest(defaultFile)
        FileBackedJsonObjectStore.resetSharedStateForTest(File(context.filesDir, "media_clip_store_v1/entries.json"))
        val store = MediaClipStore(context, clock = { nowMs })

        store.storeCandidates(
            listOf(
                MediaClipCandidate(
                    clipId = "tmdb:movie:550:secondary-default",
                    contentId = identity,
                    provider = "TMDB",
                    source = MediaClipSource.PROVIDER,
                    scope = MediaClipScope.Title(identity),
                    clipType = MediaClipType.TRAILER,
                    title = "Official Trailer",
                    language = "en",
                    site = ClipSite.YOUTUBE,
                    externalVideoId = "secondary-default",
                    playbackRef = MediaClipPlaybackRef.YouTubeId("secondary-default"),
                    confidence = Confidence.HIGH,
                    sourceTrace = listOf("tmdb.movie.videos"),
                    fetchedAtMs = nowMs
                )
            )
        )

        assertTrue(defaultFile.exists())
        assertFalse(File(context.filesDir, "media_clip_store_v1/entries.json").exists())
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
        assertEquals(
            "legacy",
            v2Records().getAsJsonObject("media-clip:legacy").get("externalVideoId").asString
        )
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
        MediaClipTypedStore(v2EntriesFile(), Gson()).putAll(
            listOf(
                legacyRecord(
                    key = "media-clip:same",
                    clipId = "tmdb:movie:550:file",
                    contentId = identity,
                    externalVideoId = "file"
                )
            )
        )
        MediaClipTypedStore.resetSharedStateForTest(v2EntriesFile())

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
    fun `media clip store drops malformed file entries without crashing query`() {
        val identity = tmdbMovieIdentity()
        FileBackedJsonObjectStore(v1EntriesFile()).putAll(
            mapOf(
                "media-clip:bad" to JsonObject().apply {
                    addProperty("contentId", identity.contentId)
                    addProperty("scopeKind", "title")
                    addProperty("clipType", MediaClipType.TRAILER.name)
                    addProperty("language", "en")
                    addProperty("expiresAtMs", nowMs + 1_000L)
                    addProperty("staleUntilMs", nowMs + 10_000L)
                },
                "media-clip:valid" to legacyRecordJson(
                    key = "media-clip:valid",
                    clipId = "tmdb:movie:550:valid",
                    contentId = identity,
                    externalVideoId = "valid"
                )
            )
        )
        FileBackedJsonObjectStore.resetSharedStateForTest(v1EntriesFile())

        val store = MediaClipStore(context, prefsName = prefsName, clock = { nowMs + 10L })
        val clips = store.getCandidates(
            identity = identity,
            scope = MediaClipScope.Title(identity),
            clipTypes = setOf(MediaClipType.TRAILER),
            language = "en"
        )

        assertEquals(1, clips.size)
        assertEquals("valid", clips.single().externalVideoId)
        assertTrue(v2EntriesFile().exists())
    }

    @Test
    fun `media clip store returns zero when file persistence fails`() {
        val identity = tmdbMovieIdentity()
        val blockingFile = File(context.filesDir, "media-clip-store-blocked").apply {
            parentFile?.mkdirs()
            writeText("not a directory", Charsets.UTF_8)
        }
        val failingContext = object : ContextWrapper(context) {
            override fun getFilesDir(): File = blockingFile
        }
        val store = MediaClipStore(failingContext, prefsName = prefsName, clock = { nowMs })

        val stored = store.storeCandidates(
            listOf(
                MediaClipCandidate(
                    clipId = "tmdb:movie:550:failed",
                    contentId = identity,
                    provider = "TMDB",
                    source = MediaClipSource.PROVIDER,
                    scope = MediaClipScope.Title(identity),
                    clipType = MediaClipType.TRAILER,
                    title = "Official Trailer",
                    language = "en",
                    site = ClipSite.YOUTUBE,
                    externalVideoId = "failed",
                    playbackRef = MediaClipPlaybackRef.YouTubeId("failed"),
                    confidence = Confidence.HIGH,
                    sourceTrace = listOf("tmdb.movie.videos"),
                    fetchedAtMs = nowMs
                )
            )
        )

        assertEquals(0, stored)
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
        MediaClipTypedStore(v2EntriesFile(), Gson()).putAll(
            listOf(
                legacyRecord(
                    key = "media-clip:file-only",
                    clipId = "tmdb:movie:550:file-only",
                    contentId = identity,
                    externalVideoId = "file-only"
                )
            )
        )
        MediaClipTypedStore.resetSharedStateForTest(v2EntriesFile())

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

    private fun fileNamespace(): File = File(context.filesDir, fileNamespaceName())

    private fun v2EntriesFile(): File =
        File(context.filesDir, "${fileNamespaceName()}/entries.json")

    private fun v1EntriesFile(): File =
        File(context.filesDir, "${prefsName.takeUnless { it == "media_clip_store_v1" } ?: "media-clip-store-v1"}/entries.json")

    private fun fileNamespaceName(): String =
        prefsName.takeUnless { it == "media_clip_store_v1" } ?: "media-clip-store-v2"

    private fun v2Records(): JsonObject =
        Gson().fromJson(v2EntriesFile().readText(), JsonObject::class.java).getAsJsonObject("records")

    private fun legacyRecordJson(
        key: String,
        clipId: String,
        contentId: ContentIdentity,
        externalVideoId: String
    ): JsonObject = Gson().toJsonTree(legacyRecord(key, clipId, contentId, externalVideoId)).asJsonObject

    private fun legacyRecord(
        key: String,
        clipId: String,
        contentId: ContentIdentity,
        externalVideoId: String
    ): StoredMediaClipRecord = StoredMediaClipRecord(
        key = key,
        clipId = clipId,
        contentId = contentId.contentId,
        itemType = contentId.itemType,
        tmdbId = contentId.stableIds.tmdb,
        tvdbId = contentId.stableIds.tvdb,
        imdbId = contentId.stableIds.imdb,
        kitsuId = contentId.stableIds.kitsu,
        provider = "TMDB",
        source = MediaClipSource.PROVIDER.name,
        scopeKind = "title",
        season = null,
        episode = null,
        clipType = MediaClipType.TRAILER.name,
        title = "Official Trailer",
        language = "en",
        site = ClipSite.YOUTUBE.name,
        externalVideoId = externalVideoId,
        playbackKind = "youtube",
        youtubeId = externalVideoId,
        providerUrlHash = null,
        redactedUrl = null,
        confidence = Confidence.HIGH.name,
        fetchedAtMs = nowMs,
        expiresAtMs = nowMs + 1_000L,
        staleUntilMs = nowMs + 10_000L,
        sourceTrace = listOf("tmdb.movie.videos")
    )
}
