package com.nexio.tv.core.anime.binary

import android.content.Context
import android.content.res.AssetManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files

class AnimeIdMapBinaryReaderTest {
    private lateinit var workDir: File
    private lateinit var fakeFilesDir: File
    private lateinit var context: Context

    @Before
    fun setUp() {
        workDir = Files.createTempDirectory("animeidmap-test").toFile()
        fakeFilesDir = File(workDir, "files").apply { mkdirs() }
        context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(fakeFilesDir)
        val assets = mock(AssetManager::class.java)
        `when`(context.assets).thenReturn(assets)
        val fixture = File("app/src/test/resources/anime/nexio-anime-map-v1-test.bin")
        require(fixture.exists()) { "fixture missing — run :app:generateAnimeIdMapBinaryFixture" }
        `when`(assets.open("anime/nexio-anime-map-v1.bin")).thenAnswer { FileInputStream(fixture) }
    }

    @After
    fun tearDown() {
        workDir.deleteRecursively()
    }

    @Test
    fun `ensureOpen copies asset to filesDir and maps successfully`() {
        val reader = AnimeIdMapBinaryReader(context)
        reader.ensureOpen()
        assertTrue(reader.isOpen())
        val onDisk = File(fakeFilesDir, "anime-id-map/fmt1.bin")
        assertTrue("expected on-disk copy at $onDisk", onDisk.exists())
        assertTrue(onDisk.length() > 0)
    }

    @Test
    fun `ensureOpen degrades to Failed when asset missing`() {
        `when`(context.assets.open("anime/nexio-anime-map-v1.bin"))
            .thenThrow(java.io.FileNotFoundException("missing"))
        val reader = AnimeIdMapBinaryReader(context)
        reader.ensureOpen()
        assertFalse(reader.isOpen())
        assertTrue(reader.isFailed())
    }

    @Test
    fun `containsKitsu returns true for known id, false for unknown`() {
        val reader = AnimeIdMapBinaryReader(context)
        reader.ensureOpen()
        assertTrue(reader.containsKitsu("11469"))
        assertFalse(reader.containsKitsu("000000"))
    }

    @Test
    fun `lookupSingle finds by mal anidb tmdbMovie`() {
        val reader = AnimeIdMapBinaryReader(context)
        reader.ensureOpen()
        // From fixture JSON: byAnidb { "11739" -> "11469" }
        assertEquals("11469", reader.lookupSingle(IndexKind.BY_ANIDB, "11739"))
        assertNull(reader.lookupSingle(IndexKind.BY_ANIDB, "99999999"))
    }

    @Test
    fun `lookupMultiFirst returns first kitsu for tvdb id with multiple records`() {
        val reader = AnimeIdMapBinaryReader(context)
        reader.ensureOpen()
        // Fixture: byTvdb { "305074" -> ["11469","13881"] }
        assertEquals("11469", reader.lookupMultiFirst(IndexKind.BY_TVDB, "305074"))
        assertNull(reader.lookupMultiFirst(IndexKind.BY_TVDB, "99999"))
    }

    @Test
    fun `recordOffsetsForMultiKey returns full list of offsets`() {
        val reader = AnimeIdMapBinaryReader(context)
        reader.ensureOpen()
        val offsets = reader.recordOffsetsForMultiKey(IndexKind.BY_TVDB, "305074")
        assertEquals(2, offsets.size)
    }

    @Test
    fun `recordOffsetsForImdb returns list for known imdb id`() {
        val reader = AnimeIdMapBinaryReader(context)
        reader.ensureOpen()
        // Fixture: byImdb { "tt5626028" -> ["11469","13881"] }
        val offsets = reader.recordOffsetsForImdb("tt5626028")
        assertEquals(2, offsets.size)
    }

    @Test
    fun `recordOffsetsForImdb returns empty for unknown imdb id`() {
        val reader = AnimeIdMapBinaryReader(context)
        reader.ensureOpen()
        assertEquals(0, reader.recordOffsetsForImdb("tt9999999").size)
    }

    @Test
    fun `recordAt returns full identity record with all fields populated`() {
        val reader = AnimeIdMapBinaryReader(context)
        reader.ensureOpen()
        // Fixture: kitsu "11469" -> anidb 11739, tvdb 305074, tmdb 65930, imdb tt5626028, mediaType series, sourceType TV
        val offsets = reader.recordOffsetsForMultiKey(IndexKind.BY_TVDB, "305074")
        val rec = reader.recordAt(offsets[0])
        assertNotNull(rec)
        assertEquals("11469", rec!!.kitsu)
        assertEquals("11739", rec.anidb)
        assertEquals("305074", rec.tvdb)
        assertEquals("65930", rec.tmdb)
        assertEquals("tt5626028", rec.imdb)
        assertEquals("series", rec.mediaType)
        assertEquals("tv", rec.sourceType?.lowercase())
    }

    @Test
    fun `recordForKitsu returns null for unknown kitsu id`() {
        val reader = AnimeIdMapBinaryReader(context)
        reader.ensureOpen()
        assertNull(reader.recordForKitsu("999999999"))
    }

    @Test
    fun `recordForKitsu strips kitsu prefix`() {
        val reader = AnimeIdMapBinaryReader(context)
        reader.ensureOpen()
        assertNotNull(reader.recordForKitsu("kitsu:11469"))
    }
}
