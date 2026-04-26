package com.nexio.tv.ui.screens.player

import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.domain.model.Subtitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CoroutineStart
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify

class PlayerRuntimeControllerAddonSubtitleOverlayTest {

    @Test
    fun `overlay mime routing accepts srt and vtt only`() {
        assertTrue(addonSubtitleSupportsOverlay(MimeTypes.APPLICATION_SUBRIP))
        assertTrue(addonSubtitleSupportsOverlay(MimeTypes.TEXT_VTT))
        assertFalse(addonSubtitleSupportsOverlay(MimeTypes.TEXT_SSA))
        assertFalse(addonSubtitleSupportsOverlay(MimeTypes.APPLICATION_TTML))
    }

    @Test
    fun `subrip system prompt mode bypasses addon overlay translation`() {
        assertFalse(
            shouldUseAddonOverlayTranslation(
                mimeType = MimeTypes.APPLICATION_SUBRIP,
                subRipSystemPromptEnabled = true
            )
        )
        assertTrue(
            shouldUseAddonOverlayTranslation(
                mimeType = MimeTypes.APPLICATION_SUBRIP,
                subRipSystemPromptEnabled = false
            )
        )
        assertTrue(
            shouldUseAddonOverlayTranslation(
                mimeType = MimeTypes.TEXT_VTT,
                subRipSystemPromptEnabled = true
            )
        )
    }

    @Test
    fun `ass and ssa urls route to text ssa and bypass overlay`() {
        assertEquals(
            MimeTypes.TEXT_SSA,
            PlayerSubtitleUtils.mimeTypeFromUrl("https://example.test/subs/movie.ass")
        )
        assertEquals(
            MimeTypes.TEXT_SSA,
            PlayerSubtitleUtils.mimeTypeFromUrl("https://example.test/subs/movie.ssa")
        )
        assertFalse(addonSubtitleSupportsOverlay(PlayerSubtitleUtils.mimeTypeFromUrl("https://example.test/subs/movie.ass")))
        assertFalse(addonSubtitleSupportsOverlay(PlayerSubtitleUtils.mimeTypeFromUrl("https://example.test/subs/movie.ssa")))
    }

    @Test
    fun `ai translation accepts ass and ssa`() {
        assertTrue(subtitleSupportsAiTranslationForTest("https://example.test/subtitle.ass"))
        assertTrue(subtitleSupportsAiTranslationForTest("https://example.test/subtitle.ssa"))
    }

    @Test
    fun `effective overlay position matches subtitle delay sign`() {
        assertEquals(
            900L,
            delayedAddonSubtitleOverlayPositionMs(currentPositionMs = 1_500L, subtitleDelayUs = 600_000L)
        )
        assertEquals(
            2_100L,
            delayedAddonSubtitleOverlayPositionMs(currentPositionMs = 1_500L, subtitleDelayUs = -600_000L)
        )
        assertEquals(
            0L,
            delayedAddonSubtitleOverlayPositionMs(currentPositionMs = 200L, subtitleDelayUs = 600_000L)
        )
    }

    @Test
    fun `active cue lookup returns overlapping cue groups and respects end boundary`() {
        val early = cue("early")
        val overlap = cue("overlap")
        val expired = cue("expired")
        val groups = listOf(
            TimedAddonCueGroup(startMs = 0L, endMs = 1_000L, cues = listOf(early)),
            TimedAddonCueGroup(startMs = 500L, endMs = 1_500L, cues = listOf(overlap)),
            TimedAddonCueGroup(startMs = 700L, endMs = 900L, cues = listOf(expired))
        )

        assertEquals(listOf(early, overlap, expired), activeAddonOverlayCuesAt(groups, 800L))
        assertEquals(listOf(overlap), activeAddonOverlayCuesAt(groups, 1_000L))
        assertEquals(emptyList<Cue>(), activeAddonOverlayCuesAt(groups, 1_500L))
    }

    @Test
    fun `next overlay delay wakes at next cue start boundary`() {
        val groups = listOf(
            TimedAddonCueGroup(startMs = 1_000L, endMs = 2_000L, cues = listOf(cue("a"))),
            TimedAddonCueGroup(startMs = 3_500L, endMs = 4_000L, cues = listOf(cue("b")))
        )

        assertEquals(250L, nextAddonOverlayUpdateDelayMs(groups, positionMs = 750L))
    }

    @Test
    fun `next overlay delay wakes at next cue end boundary`() {
        val groups = listOf(
            TimedAddonCueGroup(startMs = 1_000L, endMs = 2_000L, cues = listOf(cue("a")))
        )

        assertEquals(200L, nextAddonOverlayUpdateDelayMs(groups, positionMs = 1_800L))
    }

    @Test
    fun `next overlay delay falls back to bounded idle delay when no further cues exist`() {
        val groups = listOf(
            TimedAddonCueGroup(startMs = 1_000L, endMs = 2_000L, cues = listOf(cue("a")))
        )

        assertEquals(500L, nextAddonOverlayUpdateDelayMs(groups, positionMs = 5_000L))
    }

    @Test
    fun `srt parser fallback produces timed cue groups`() {
        val raw = """
            1
            00:00:01,000 --> 00:00:02,000
            Hello overlay
        """.trimIndent().toByteArray()

        val groups = parseAddonSubtitleOverlayCueGroups(
            url = "https://example.test/subtitle.srt",
            mimeType = MimeTypes.APPLICATION_SUBRIP,
            bytes = raw
        )

        assertEquals(1, groups.size)
        assertEquals(1_000L, groups.single().startMs)
        assertEquals(2_000L, groups.single().endMs)
        assertEquals("Hello overlay", groups.single().cues.single().text.toString())
    }

    @Test
    fun `source texts for translation are trimmed and deduplicated`() {
        val groups = listOf(
            TimedAddonCueGroup(startMs = 0L, endMs = 1_000L, cues = listOf(cue(" Hello "), cue("World"))),
            TimedAddonCueGroup(startMs = 1_000L, endMs = 2_000L, cues = listOf(cue("Hello")))
        )

        assertEquals(listOf("Hello", "World"), sourceTextsForTranslation(groups))
    }

    @Test
    fun `translated cue groups preserve timing and cue metadata`() {
        val sourceCue = Cue.Builder()
            .setText("Hello")
            .setPosition(0.25f)
            .build()
        val groups = listOf(
            TimedAddonCueGroup(startMs = 500L, endMs = 1_500L, cues = listOf(sourceCue))
        )

        val translated = translateTimedAddonCueGroups(groups, mapOf("Hello" to "Hallo"))

        assertEquals(500L, translated.single().startMs)
        assertEquals(1_500L, translated.single().endMs)
        assertEquals("Hallo", translated.single().cues.single().text.toString())
        assertEquals(sourceCue.position, translated.single().cues.single().position)
    }

    @Test
    fun `download result success is converted to utf8 subtitle bytes`() {
        val bytes = addonSubtitleBytesFromDownloadResult(
            IntegrationCallResult.Success("Ol\u00e1 legenda")
        )

        assertEquals(
            "Ol\u00e1 legenda",
            bytes.toString(StandardCharsets.UTF_8)
        )
    }

    @Test
    fun `download result http failure raises overlay download error`() {
        try {
            addonSubtitleBytesFromDownloadResult(
                IntegrationCallResult.HttpError(
                    statusCode = 503,
                    reason = "subtitle_source_download_failed"
                )
            )
        } catch (exception: IllegalStateException) {
            assertEquals("Addon subtitle download failed http=503", exception.message)
            return
        }

        throw AssertionError("Expected IllegalStateException")
    }

    @Test
    fun `provider backed materialization writes remote subtitle to cached file url`() {
        val cacheRoot = createTempDirectory("addon-subtitle-cache-").toFile()

        try {
            val selectedSubtitle = runBlockingTestMaterialization(cacheRoot)

            assertTrue(selectedSubtitle.url.startsWith("file:"))
            val materializedFile = File(java.net.URI(selectedSubtitle.url))
            assertTrue(materializedFile.exists())
            assertEquals(
                "1\n00:00:00,000 --> 00:00:01,000\nHello\n",
                materializedFile.readText(StandardCharsets.UTF_8)
            )
        } finally {
            cacheRoot.deleteRecursively()
        }
    }

    @Test
    fun `provider backed materialization removes temp file and leaves final cache empty when move fails`() {
        val cacheRoot = createTempDirectory("addon-subtitle-cache-fail-").toFile()
        val subtitle = Subtitle(
            id = "remote-subtitle",
            url = "https://example.test/subtitles/episode.srt",
            lang = "en",
            addonName = "Addon",
            addonLogo = null
        )

        mockkStatic(Files::class)
        try {
            every {
                Files.move(any(), any(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } throws IOException("move failed")

            val materialized = kotlinx.coroutines.runBlocking {
                materializeAddonSubtitleToCacheViaProvider(
                    cacheRoot = cacheRoot,
                    subtitle = subtitle,
                    downloadBytes = { _, _ ->
                        IntegrationCallResult.Success("partial".toByteArray(StandardCharsets.UTF_8))
                    }
                )
            }

            assertNull(materialized)
            assertEquals(emptyList<String>(), cacheRoot.list()?.toList().orEmpty())
            verify(exactly = 1) {
                Files.move(any(), any(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }
        } finally {
            unmockkStatic(Files::class)
            cacheRoot.deleteRecursively()
        }
    }

    @Test
    fun `concurrent same-url materialization uses unique temporary files`() {
        val cacheRoot = createTempDirectory("addon-subtitle-concurrent-").toFile()
        val subtitle = Subtitle(
            id = "remote-subtitle",
            url = "https://example.test/subtitles/episode.srt",
            lang = "en",
            addonName = "Addon",
            addonLogo = null
        )
        val expectedBytes = "1\n00:00:00,000 --> 00:00:01,000\nHello\n".toByteArray(StandardCharsets.UTF_8)
        val createdTempFiles = Collections.synchronizedList(mutableListOf<Path>())
        val concurrentDownload = AtomicInteger(0)
        val continueDownload = kotlinx.coroutines.CompletableDeferred<Unit>()

        mockkStatic(File::class)
        try {
            every {
                File.createTempFile(any(), any(), cacheRoot)
            } answers {
                val created = callOriginal()
                createdTempFiles.add(created.toPath())
                created
            }

            val downloadBytes: suspend (String, Map<String, String>) -> IntegrationCallResult<ByteArray> = { _, _ ->
                if (concurrentDownload.incrementAndGet() == 2) {
                    continueDownload.complete(Unit)
                }
                runBlocking {
                    continueDownload.await()
                }
                IntegrationCallResult.Success(expectedBytes)
            }

            val materialized: List<Subtitle?> = runBlocking {
                coroutineScope {
                    val first = async(start = CoroutineStart.UNDISPATCHED) {
                        materializeAddonSubtitleToCacheViaProvider(
                            cacheRoot = cacheRoot,
                            subtitle = subtitle,
                            downloadBytes = downloadBytes
                        )
                    }
                    val second = async(start = CoroutineStart.UNDISPATCHED) {
                        materializeAddonSubtitleToCacheViaProvider(
                            cacheRoot = cacheRoot,
                            subtitle = subtitle,
                            downloadBytes = downloadBytes
                        )
                    }

                    listOf(first.await(), second.await())
                }
            }

            assertTrue(materialized.all { it != null })
            val cacheFile = materialized.first()!!.let { File(java.net.URI(it.url)) }
            assertTrue(cacheFile.exists())
            assertEquals(
                "1\n00:00:00,000 --> 00:00:01,000\nHello\n",
                cacheFile.readText(StandardCharsets.UTF_8)
            )
            assertEquals(2, createdTempFiles.size)
            assertEquals(2, createdTempFiles.toSet().size)
            val remainingTempFiles = cacheRoot.listFiles { file ->
                file.name.endsWith(".tmp")
            }.orEmpty()
            assertEquals(0, remainingTempFiles.size)
        } finally {
            unmockkStatic(File::class)
            cacheRoot.deleteRecursively()
        }
    }

    @Test
    fun `refresh selection preparation uses materialized file url for state and media source`() {
        val cacheRoot = createTempDirectory("addon-subtitle-refresh-").toFile()
        val remoteSubtitle = Subtitle(
            id = "remote-subtitle",
            url = "https://example.test/subtitles/episode.ass",
            lang = "en",
            addonName = "Addon",
            addonLogo = null
        )

        try {
            val prepared = kotlinx.coroutines.runBlocking {
                prepareAddonSubtitleRefreshSelectionViaProvider(
                    cacheRoot = cacheRoot,
                    existingAddonSubtitles = listOf(remoteSubtitle),
                    subtitle = remoteSubtitle,
                    selectedSubtitle = remoteSubtitle,
                    downloadBytes = { _, _ ->
                        IntegrationCallResult.Success(
                            "1\n00:00:00,000 --> 00:00:01,000\nHello\n".toByteArray()
                        )
                    }
                )
            }

            assertTrue(prepared.subtitle.url.startsWith("file:"))
            assertTrue(prepared.selectedSubtitle.url.startsWith("file:"))
            assertTrue(prepared.effectiveAddonSubtitles.single().url.startsWith("file:"))
            assertEquals(prepared.selectedSubtitle, prepared.effectiveAddonSubtitles.single())
            assertEquals(buildAddonSubtitleTrackIdForTest(prepared.subtitle), prepared.addonTrackId)
        } finally {
            cacheRoot.deleteRecursively()
        }
    }

    @Test
    fun `refresh selection preparation preserves ass mime type after materialization`() {
        val cacheRoot = createTempDirectory("addon-subtitle-ass-").toFile()
        val remoteSubtitle = Subtitle(
            id = "remote-subtitle",
            url = "https://example.test/subtitles/episode.ass",
            lang = "en",
            addonName = "Addon",
            addonLogo = null
        )

        try {
            val prepared = kotlinx.coroutines.runBlocking {
                prepareAddonSubtitleRefreshSelectionViaProvider(
                    cacheRoot = cacheRoot,
                    existingAddonSubtitles = listOf(remoteSubtitle),
                    subtitle = remoteSubtitle,
                    selectedSubtitle = remoteSubtitle,
                    downloadBytes = { _, _ ->
                        IntegrationCallResult.Success(
                            "[Script Info]\nTitle: Example\n".toByteArray()
                        )
                    }
                )
            }

            assertTrue(prepared.subtitle.url.endsWith(".ass"))
            assertEquals(MimeTypes.TEXT_SSA, prepared.subtitleConfigurations.single().mimeType)
        } finally {
            cacheRoot.deleteRecursively()
        }
    }

    @Test
    fun `refresh selection preparation timeout resolves with cleanup`() = runTest {
        val cacheRoot = createTempDirectory("addon-subtitle-timeout-").toFile()
        val remoteSubtitle = Subtitle(
            id = "remote-subtitle",
            url = "https://example.test/subtitles/episode.srt",
            lang = "en",
            addonName = "Addon",
            addonLogo = null
        )
        var cleanupCount = 0

        try {
            val prepared = prepareAddonSubtitleRefreshSelectionViaProviderWithTimeout(
                cacheRoot = cacheRoot,
                existingAddonSubtitles = listOf(remoteSubtitle),
                subtitle = remoteSubtitle,
                selectedSubtitle = remoteSubtitle,
                timeoutMs = 50L,
                onTimeout = { cleanupCount++ },
                downloadBytes = { _, _ ->
                    delay(5_000L)
                    IntegrationCallResult.Success("late".toByteArray())
                }
            )

            assertEquals(null, prepared)
            assertEquals(1, cleanupCount)
        } finally {
            cacheRoot.deleteRecursively()
        }
    }

    private fun cue(text: String): Cue {
        return Cue.Builder().setText(text).build()
    }

    private fun runBlockingTestMaterialization(cacheRoot: File): Subtitle {
        val subtitle = Subtitle(
            id = "remote-subtitle",
            url = "https://example.test/subtitles/episode.srt",
            lang = "en",
            addonName = "Addon",
            addonLogo = null
        )

        return kotlinx.coroutines.runBlocking {
            materializeAddonSubtitleToCacheViaProvider(
                cacheRoot = cacheRoot,
                subtitle = subtitle,
                downloadBytes = { _, _ ->
                    IntegrationCallResult.Success(
                        "1\n00:00:00,000 --> 00:00:01,000\nHello\n".toByteArray()
                    )
                }
            )
        } ?: throw AssertionError("Expected cached subtitle")
    }

    private fun buildAddonSubtitleTrackIdForTest(subtitle: Subtitle): String {
        val urlHashSuffix = subtitle.url.hashCode().toUInt().toString(16)
        return "${PlayerRuntimeController.ADDON_SUBTITLE_TRACK_ID_PREFIX}${subtitle.id}:$urlHashSuffix"
    }
}
