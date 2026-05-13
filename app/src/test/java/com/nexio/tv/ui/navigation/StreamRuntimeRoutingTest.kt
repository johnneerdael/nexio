package com.nexio.tv.ui.navigation

import com.nexio.tv.core.metadata.parseRuntimeMinutes
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.HydratedHomeOverlay
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.WatchProgress
import com.nexio.tv.ui.screens.home.ContinueWatchingItem
import com.nexio.tv.ui.screens.home.NextUpInfo
import com.nexio.tv.ui.screens.home.continueWatchingIdentityMetadataRequest
import com.nexio.tv.ui.screens.home.displayMetadata
import com.nexio.tv.ui.screens.home.mergeContinueWatchingOverlaySidecars
import kotlinx.coroutines.test.runTest
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.invariantSeparatorsPathString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class StreamRuntimeRoutingTest {

    @Test
    fun `in progress continue watching runtime is derived from watch duration`() {
        val item = ContinueWatchingItem.InProgress(
            progress = watchProgress(durationMs = 9_091_000L)
        )

        assertEquals(152, continueWatchingRuntimeMinutes(item))
    }

    @Test
    fun `next up runtime is parsed from display metadata`() {
        val item = ContinueWatchingItem.NextUp(
            info = nextUpInfo(runtime = "47m")
        )

        assertEquals(47, continueWatchingRuntimeMinutes(item))
    }

    @Test
    fun `missing continue watching runtime stays null`() {
        val inProgress = ContinueWatchingItem.InProgress(
            progress = watchProgress(durationMs = 0L)
        )
        val nextUp = ContinueWatchingItem.NextUp(
            info = nextUpInfo(runtime = null)
        )

        assertNull(continueWatchingRuntimeMinutes(inProgress))
        assertNull(continueWatchingRuntimeMinutes(nextUp))
    }

    @Test
    fun `runtime parser accepts plain minutes and decorated strings`() {
        assertEquals(152, parseRuntimeMinutes("152"))
        assertEquals(47, parseRuntimeMinutes("47m"))
        assertEquals(125, parseRuntimeMinutes("125 min"))
        assertNull(parseRuntimeMinutes("unknown"))
    }

    @Test
    fun `continue watching route forwards deterministic autoplay when enabled`() {
        val route = buildContinueWatchingStreamRoute(
            item = ContinueWatchingItem.NextUp(
                info = nextUpInfo(runtime = "47m")
            ),
            deterministicAutoplayEnabled = true
        )

        assertTrue(route.contains("deterministicAutoplay=true"))
        assertTrue(route.contains("returnToDetailOnBack=true"))
    }

    @Test
    fun `continue watching start from beginning route preserves flags`() {
        val route = buildContinueWatchingStreamRoute(
            item = ContinueWatchingItem.InProgress(
                progress = watchProgress(durationMs = 9_091_000L)
            ),
            deterministicAutoplayEnabled = true,
            startFromBeginning = true
        )

        assertTrue(route.contains("startFromBeginning=true"))
        assertTrue(route.contains("deterministicAutoplay=true"))
    }

    @Test
    fun `continue watching start from beginning route clears resume progress`() {
        val route = buildContinueWatchingStreamRoute(
            item = ContinueWatchingItem.InProgress(
                progress = watchProgress(
                    durationMs = 9_091_000L,
                    positionMs = 123_000L,
                    progressPercent = 12.5f
                )
            ),
            deterministicAutoplayEnabled = true,
            startFromBeginning = true
        )

        assertFalse(route.contains("resumePositionMs=123000"))
        assertFalse(route.contains("resumeDurationMs=9091000"))
        assertFalse(route.contains("resumeProgressPercent=12.5"))
        assertFalse(route.contains("resumeLastWatchedMs=42"))
    }

    @Test
    fun `continue watching route carries resume progress into stream handoff`() {
        val route = buildContinueWatchingStreamRoute(
            item = ContinueWatchingItem.InProgress(
                progress = watchProgress(
                    durationMs = 9_091_000L,
                    positionMs = 123_000L,
                    progressPercent = 12.5f
                )
            ),
            deterministicAutoplayEnabled = true
        )

        assertTrue(route.contains("resumePositionMs=123000"))
        assertTrue(route.contains("resumeDurationMs=9091000"))
        assertTrue(route.contains("resumeProgressPercent=12.5"))
        assertTrue(route.contains("resumeLastWatchedMs=42"))
    }

    @Test
    fun `continue watching in progress route keeps resume id and stream fetch id separate`() {
        val route = buildContinueWatchingStreamRoute(
            item = ContinueWatchingItem.InProgress(
                progress = watchProgress(
                    durationMs = 2_468_000L,
                    positionMs = 1_234_000L,
                    progressPercent = 50.0f
                ).copy(
                    contentId = "tvdb:393268",
                    videoId = "tvdb:393268:2:1",
                    contentType = "series",
                    season = 2,
                    episode = 1,
                    source = WatchProgress.SOURCE_TRAKT_PLAYBACK
                ),
                streamFetchVideoId = "tt9794044:2:1"
            ),
            deterministicAutoplayEnabled = true
        )

        val args = decodedStreamRouteArgs(route)

        assertEquals("tvdb:393268:2:1", args.getValue("videoId"))
        assertEquals("tt9794044:2:1", args.getValue("streamVideoId"))
        assertEquals("tvdb:393268", args.getValue("contentId"))
        assertEquals("1234000", args.getValue("resumePositionMs"))
        assertEquals("2468000", args.getValue("resumeDurationMs"))
        assertEquals("50.0", args.getValue("resumeProgressPercent"))
        assertEquals(WatchProgress.SOURCE_TRAKT_PLAYBACK, args.getValue("resumeSource"))
    }

    @Test
    fun `manual continue watching in progress route keeps resume id and stream fetch id separate`() {
        val route = buildContinueWatchingManualSelectionStreamRoute(
            item = ContinueWatchingItem.InProgress(
                progress = watchProgress(
                    durationMs = 2_468_000L,
                    positionMs = 1_234_000L,
                    progressPercent = 50.0f
                ).copy(
                    contentId = "tvdb:393268",
                    videoId = "tvdb:393268:2:1",
                    contentType = "series",
                    season = 2,
                    episode = 1,
                    source = WatchProgress.SOURCE_TRAKT_PLAYBACK
                ),
                streamFetchVideoId = "tt9794044:2:1"
            )
        )

        val args = decodedStreamRouteArgs(route)

        assertEquals("tvdb:393268:2:1", args.getValue("videoId"))
        assertEquals("tt9794044:2:1", args.getValue("streamVideoId"))
        assertEquals("tvdb:393268", args.getValue("contentId"))
        assertEquals("1234000", args.getValue("resumePositionMs"))
        assertEquals("2468000", args.getValue("resumeDurationMs"))
        assertEquals("50.0", args.getValue("resumeProgressPercent"))
        assertEquals(WatchProgress.SOURCE_TRAKT_PLAYBACK, args.getValue("resumeSource"))
    }

    @Test
    fun `manual continue watching route derives episode stream fetch id from imdb metadata when persisted id is missing`() {
        val route = buildContinueWatchingManualSelectionStreamRoute(
            item = ContinueWatchingItem.InProgress(
                progress = watchProgress(
                    durationMs = 2_468_000L,
                    positionMs = 1_234_000L,
                    progressPercent = 50.0f
                ).copy(
                    contentId = "tvdb:393268",
                    videoId = "tvdb:393268:2:1",
                    contentType = "series",
                    season = 2,
                    episode = 1,
                    source = WatchProgress.SOURCE_TRAKT_PLAYBACK
                ),
                displayMetadata = HomeDisplayMetadata(imdbId = "tt9794044")
            )
        )

        val args = decodedStreamRouteArgs(route)

        assertEquals("tvdb:393268:2:1", args.getValue("videoId"))
        assertEquals("tt9794044:2:1", args.getValue("streamVideoId"))
        assertEquals("tt9794044", args.getValue("imdbId"))
    }

    @Test
    fun `continue watching route derives episode stream fetch id from imdb metadata when persisted id is tvdb shaped`() {
        val route = buildContinueWatchingStreamRoute(
            item = ContinueWatchingItem.InProgress(
                progress = watchProgress(
                    durationMs = 2_468_000L,
                    positionMs = 1_234_000L,
                    progressPercent = 50.0f
                ).copy(
                    contentId = "tvdb:393268",
                    videoId = "tvdb:393268:2:1",
                    contentType = "series",
                    season = 2,
                    episode = 1,
                    source = WatchProgress.SOURCE_TRAKT_PLAYBACK
                ),
                streamFetchVideoId = "tvdb:393268:2:1",
                displayMetadata = HomeDisplayMetadata(imdbId = "tt9794044")
            ),
            deterministicAutoplayEnabled = true
        )

        val args = decodedStreamRouteArgs(route)

        assertEquals("tvdb:393268:2:1", args.getValue("videoId"))
        assertEquals("tt9794044:2:1", args.getValue("streamVideoId"))
        assertEquals("tt9794044", args.getValue("imdbId"))
    }

    @Test
    fun `continue watching route derives episode stream fetch id from resolved imdb override`() {
        val route = buildContinueWatchingStreamRoute(
            item = ContinueWatchingItem.InProgress(
                progress = watchProgress(
                    durationMs = 2_468_000L,
                    positionMs = 1_234_000L,
                    progressPercent = 50.0f
                ).copy(
                    contentId = "tvdb:463433",
                    videoId = "tvdb:463433:1:9",
                    contentType = "series",
                    season = 1,
                    episode = 9,
                    source = WatchProgress.SOURCE_TRAKT_PLAYBACK
                ),
                streamFetchVideoId = "tvdb:463433:1:9"
            ),
            deterministicAutoplayEnabled = true,
            resolvedImdbHint = "tt12345678"
        )

        val args = decodedStreamRouteArgs(route)

        assertEquals("tvdb:463433:1:9", args.getValue("videoId"))
        assertEquals("tt12345678:1:9", args.getValue("streamVideoId"))
        assertEquals("tt12345678", args.getValue("imdbId"))
    }

    @Test
    fun `continue watching overlay sidecar supplies imdb metadata for tvdb item`() {
        val item = ContinueWatchingItem.NextUp(
            info = NextUpInfo(
                contentId = "tvdb:393268",
                contentType = "series",
                name = "Citadel",
                poster = null,
                backdrop = null,
                logo = null,
                displayMetadata = HomeDisplayMetadata(runtime = "43"),
                videoId = "tvdb:393268:2:2",
                season = 2,
                episode = 2,
                episodeTitle = "Cold Plunge",
                thumbnail = null,
                lastWatched = 42L
            )
        )
        val enriched = mergeContinueWatchingOverlaySidecars(
            items = listOf(item),
            overlaysByItemKey = mapOf(
                "series:tvdb:393268" to hydratedOverlay(
                    itemKey = "series:tvdb:393268",
                    imdbId = "tt9794044",
                    title = "Citadel"
                )
            )
        ).single()

        val route = buildContinueWatchingStreamRoute(
            item = enriched,
            deterministicAutoplayEnabled = true
        )
        val args = decodedStreamRouteArgs(route)

        assertEquals("tt9794044", enriched.displayMetadata().imdbId)
        assertEquals("tt9794044:2:2", args.getValue("streamVideoId"))
        assertEquals("tt9794044", args.getValue("imdbId"))
    }

    @Test
    fun `continue watching tv identity request seeds tvdb provider id from content id`() {
        val request = continueWatchingIdentityMetadataRequest(
            ContinueWatchingItem.InProgress(
                progress = watchProgress(
                    durationMs = 2_468_000L,
                    positionMs = 1_234_000L,
                    progressPercent = 50.0f
                ).copy(
                    contentId = "tvdb:463433",
                    videoId = "tvdb:463433:1:9",
                    contentType = "series",
                    season = 1,
                    episode = 9,
                    source = WatchProgress.SOURCE_TRAKT_PLAYBACK
                )
            )
        )

        assertEquals("tvdb:463433", request.contentId)
        assertEquals("463433", request.sourceContext.previewStableIds.tvdb)
        assertEquals("series", request.sourceContext.itemType)
    }

    @Test
    fun `continue watching movie identity request seeds tmdb provider id from content id`() {
        val request = continueWatchingIdentityMetadataRequest(
            ContinueWatchingItem.InProgress(
                progress = watchProgress(
                    durationMs = 2_468_000L,
                    contentType = "movie"
                ).copy(
                    contentId = "tmdb:1405769",
                    videoId = "tmdb:1405769",
                    contentType = "movie"
                )
            )
        )

        assertEquals("tmdb:1405769", request.contentId)
        assertEquals("1405769", request.sourceContext.previewStableIds.tmdb)
        assertEquals("movie", request.sourceContext.itemType)
    }

    @Test
    fun `manual continue watching route derives episode stream fetch id from imdb metadata when persisted id is tvdb shaped`() {
        val route = buildContinueWatchingManualSelectionStreamRoute(
            item = ContinueWatchingItem.InProgress(
                progress = watchProgress(
                    durationMs = 2_468_000L,
                    positionMs = 1_234_000L,
                    progressPercent = 50.0f
                ).copy(
                    contentId = "tvdb:393268",
                    videoId = "tvdb:393268:2:1",
                    contentType = "series",
                    season = 2,
                    episode = 1,
                    source = WatchProgress.SOURCE_TRAKT_PLAYBACK
                ),
                streamFetchVideoId = "tvdb:393268:2:1",
                displayMetadata = HomeDisplayMetadata(imdbId = "tt9794044")
            )
        )

        val args = decodedStreamRouteArgs(route)

        assertEquals("tvdb:393268:2:1", args.getValue("videoId"))
        assertEquals("tt9794044:2:1", args.getValue("streamVideoId"))
        assertEquals("tt9794044", args.getValue("imdbId"))
    }

    @Test
    fun `manual continue watching route derives episode stream fetch id from resolved imdb override`() {
        val route = buildContinueWatchingManualSelectionStreamRoute(
            item = ContinueWatchingItem.InProgress(
                progress = watchProgress(
                    durationMs = 2_468_000L,
                    positionMs = 1_234_000L,
                    progressPercent = 50.0f
                ).copy(
                    contentId = "tvdb:463433",
                    videoId = "tvdb:463433:1:9",
                    contentType = "series",
                    season = 1,
                    episode = 9,
                    source = WatchProgress.SOURCE_TRAKT_PLAYBACK
                ),
                streamFetchVideoId = "tvdb:463433:1:9"
            ),
            resolvedImdbHint = "tt12345678"
        )

        val args = decodedStreamRouteArgs(route)

        assertEquals("tvdb:463433:1:9", args.getValue("videoId"))
        assertEquals("tt12345678:1:9", args.getValue("streamVideoId"))
        assertEquals("tt12345678", args.getValue("imdbId"))
    }

    @Test
    fun `continue watching in progress route preserves stable ids addon context and resume args`() {
        val route = buildContinueWatchingStreamRoute(
            item = ContinueWatchingItem.InProgress(
                progress = WatchProgress(
                    contentId = "tt0239195",
                    contentType = "series",
                    name = "Survivor",
                    poster = null,
                    backdrop = null,
                    logo = null,
                    videoId = "tt0239195:5:10",
                    season = 5,
                    episode = 10,
                    episodeTitle = "Survivor Auction",
                    position = 1_234_000L,
                    duration = 2_468_000L,
                    lastWatched = 98_765L,
                    addonBaseUrl = "https://addon.example.com/manifest.json",
                    progressPercent = 50.0f,
                    source = WatchProgress.SOURCE_TRAKT_PLAYBACK
                )
            ),
            deterministicAutoplayEnabled = true
        )

        val args = decodedStreamRouteArgs(route)

        assertEquals("tt0239195", args.getValue("contentId"))
        assertEquals("tt0239195:5:10", args.getValue("videoId"))
        assertEquals("series", args.getValue("contentType"))
        assertEquals("5", args.getValue("season"))
        assertEquals("10", args.getValue("episode"))
        assertEquals("Survivor", args.getValue("contentName"))
        assertEquals("https://addon.example.com/manifest.json", args.getValue("addonBaseUrl"))
        assertEquals("1234000", args.getValue("resumePositionMs"))
        assertEquals("2468000", args.getValue("resumeDurationMs"))
        assertEquals("50.0", args.getValue("resumeProgressPercent"))
        assertEquals("98765", args.getValue("resumeLastWatchedMs"))
        assertEquals(WatchProgress.SOURCE_TRAKT_PLAYBACK, args.getValue("resumeSource"))
    }

    @Test
    fun `continue watching route uses resolved display title when source name is stale`() {
        val route = buildContinueWatchingStreamRoute(
            item = ContinueWatchingItem.InProgress(
                progress = WatchProgress(
                    contentId = "tt40898187",
                    contentType = "movie",
                    name = "The Roast of Kevin Hart",
                    poster = null,
                    backdrop = null,
                    logo = null,
                    videoId = "tt40898187",
                    season = null,
                    episode = null,
                    episodeTitle = null,
                    position = 0L,
                    duration = 0L,
                    lastWatched = 98_765L,
                    progressPercent = 72.0f,
                    source = WatchProgress.SOURCE_TRAKT_PLAYBACK
                ),
                displayMetadata = HomeDisplayMetadata(
                    title = "De roast van Kevin Hart",
                    description = "Nederlandse metadata",
                    imdbId = "tt40898187",
                    runtime = "172"
                )
            ),
            deterministicAutoplayEnabled = true
        )

        val args = decodedStreamRouteArgs(route)

        assertEquals("De roast van Kevin Hart", args.getValue("title"))
        assertEquals("De roast van Kevin Hart", args.getValue("contentName"))
        assertEquals("tt40898187", args.getValue("streamVideoId"))
        assertEquals("tt40898187", args.getValue("imdbId"))
        assertEquals("172", args.getValue("runtime"))
    }

    @Test
    fun `stream view model bridges route addon context into playback info fallback`() {
        val source = sourceFile("com/nexio/tv/ui/screens/stream/StreamScreenViewModel.kt").toFile().readText()

        assertTrue(source.contains("sourceAddonBaseUrl: String? = savedStateHandle.getOptionalString(\"addonBaseUrl\")"))
        assertTrue(source.contains("addonBaseUrl = sourceAddonBaseUrl"))
        assertTrue(source.contains("addonBaseUrl = stream.addonBaseUrl ?: sourceAddonBaseUrl"))
        assertTrue(source.contains("addonBaseUrl = candidate.stream.addonBaseUrl ?: sourceAddonBaseUrl"))
    }

    @Test
    fun `missing runtime is hydrated before continue watching route build`() = runTest {
        val route = buildContinueWatchingStreamRouteWithHydration(
            item = ContinueWatchingItem.NextUp(
                info = nextUpInfo(runtime = null)
            ),
            deterministicAutoplayEnabled = true,
            resolveRuntimeMinutes = { 62 }
        )

        assertTrue(route.contains("runtime=62"))
    }

    @Test
    fun `continue watching route still builds when runtime hydration fails`() = runTest {
        val route = buildContinueWatchingStreamRouteWithHydration(
            item = ContinueWatchingItem.NextUp(
                info = nextUpInfo(runtime = null)
            ),
            deterministicAutoplayEnabled = true,
            resolveRuntimeMinutes = { null }
        )

        assertTrue(route.contains("deterministicAutoplay=true"))
        assertTrue(route.contains("runtime="))
    }

    @Test
    fun `manual selection route forces manual mode and disables deterministic autoplay`() {
        val route = buildManualSelectionStreamRoute(
            videoId = "tt123",
            contentType = "movie",
            title = "Example",
            contentId = "tt123",
            contentName = "Example",
            returnToDetailOnBack = false
        )

        assertTrue(route.contains("manualSelection=true"))
        assertTrue(route.contains("deterministicAutoplay=false"))
        assertTrue(route.contains("returnToDetailOnBack=false"))
    }

    @Test
    fun `meta preview manual selection route uses resolved IMDB id for addon fetch and subtitles`() {
        val item = stubMetaPreview(
            id = "tmdb:1325734",
            providerIds = ProviderIds(imdb = "tt12345678", tmdb = "1325734")
        )

        val route = buildManualSelectionStreamRouteForMetaPreview(item)

        assertTrue(
            "route should carry streamVideoId=tt12345678 for addon fetch",
            route.contains("streamVideoId=tt12345678")
        )
        assertTrue(
            "route should carry imdbId=tt12345678 for downstream subtitle lookups",
            route.contains("imdbId=tt12345678")
        )
        assertTrue(route.contains("manualSelection=true"))
        assertTrue(
            "videoId belongs in the path segment, must reflect MetaPreview.id",
            route.startsWith("stream/tmdb%3A1325734/")
        )
    }

    @Test
    fun `meta preview manual selection route omits IMDB params when unresolved`() {
        val item = stubMetaPreview(
            id = "tmdb:1325734",
            providerIds = ProviderIds(tmdb = "1325734")
        )

        val route = buildManualSelectionStreamRouteForMetaPreview(item)

        assertFalse(
            "route must not carry a streamVideoId value when IMDB is null",
            route.contains(Regex("streamVideoId=[^&]+"))
        )
        assertFalse(
            "route must not carry an imdbId value when IMDB is null",
            route.contains(Regex("imdbId=[^&]+"))
        )
        assertTrue(route.contains("manualSelection=true"))
    }

    private fun stubMetaPreview(
        id: String,
        providerIds: ProviderIds = ProviderIds()
    ): MetaPreview =
        MetaPreview(
            id = id,
            type = ContentType.MOVIE,
            name = "Example",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList(),
            firstPaintStableIds = providerIds
        )

    @Test
    fun `manual selection route preserves detail return behavior for episodes`() {
        val route = buildManualSelectionStreamRoute(
            videoId = "tt123:1:2",
            contentType = "series",
            title = "Example",
            season = 1,
            episode = 2,
            episodeName = "Episode",
            contentId = "tt123",
            contentName = "Example",
            returnToDetailOnBack = true
        )

        assertTrue(route.contains("manualSelection=true"))
        assertTrue(route.contains("deterministicAutoplay=false"))
        assertTrue(route.contains("returnToDetailOnBack=true"))
    }

    @Test
    fun `continue watching movie manual route disables deterministic autoplay and skips detail return`() {
        val route = buildContinueWatchingManualSelectionStreamRoute(
            item = ContinueWatchingItem.InProgress(
                progress = watchProgress(durationMs = 9_091_000L, contentType = "movie")
            )
        )

        assertTrue(route.contains("manualSelection=true"))
        assertTrue(route.contains("deterministicAutoplay=false"))
        assertTrue(route.contains("returnToDetailOnBack=false"))
        assertTrue(route.contains("contentType=movie") || route.contains("/movie/"))
    }

    @Test
    fun `continue watching series manual route disables deterministic autoplay and returns to detail`() {
        val route = buildContinueWatchingManualSelectionStreamRoute(
            item = ContinueWatchingItem.NextUp(
                info = nextUpInfo(runtime = "47m")
            )
        )

        assertTrue(route.contains("manualSelection=true"))
        assertTrue(route.contains("deterministicAutoplay=false"))
        assertTrue(route.contains("returnToDetailOnBack=true"))
    }

    @Test
    fun `manual continue watching route hydrates missing runtime before routing`() = runTest {
        val route = buildContinueWatchingManualSelectionStreamRouteWithHydration(
            item = ContinueWatchingItem.NextUp(
                info = nextUpInfo(runtime = null)
            ),
            resolveRuntimeMinutes = { 62 }
        )

        assertTrue(route.contains("manualSelection=true"))
        assertTrue(route.contains("deterministicAutoplay=false"))
        assertTrue(route.contains("runtime=62"))
    }

    private fun watchProgress(
        durationMs: Long,
        contentType: String = "movie",
        positionMs: Long = 1_000L,
        progressPercent: Float? = null
    ): WatchProgress {
        return WatchProgress(
            contentId = "tt123",
            contentType = contentType,
            name = "Example",
            poster = null,
            backdrop = null,
            logo = null,
            videoId = "tt123",
            season = null,
            episode = null,
            episodeTitle = null,
            position = positionMs,
            duration = durationMs,
            lastWatched = 42L,
            progressPercent = progressPercent
        )
    }

    private fun nextUpInfo(runtime: String?): NextUpInfo {
        return NextUpInfo(
            contentId = "tt123",
            contentType = "series",
            name = "Example",
            poster = null,
            backdrop = null,
            logo = null,
            displayMetadata = HomeDisplayMetadata(runtime = runtime),
            videoId = "tt123:1:2",
            season = 1,
            episode = 2,
            episodeTitle = "Episode",
            thumbnail = null,
            lastWatched = 42L
        )
    }

    private fun hydratedOverlay(
        itemKey: String,
        imdbId: String,
        title: String
    ): HydratedHomeOverlay {
        return HydratedHomeOverlay(
            overlayKey = "canonical:TVDB:393268:type:SERIES:lang:nl:policy:1",
            itemKey = itemKey,
            canonicalProvider = ProviderId.TVDB,
            canonicalId = "393268",
            imdbId = imdbId,
            contentType = ContentType.SERIES,
            languageTag = "nl",
            fields = HomeDisplayMetadata(
                title = title,
                imdbId = imdbId,
                runtime = "43"
            ),
            fieldTrace = emptyList(),
            updatedAtMs = 1L,
            staleAtMs = Long.MAX_VALUE,
            expiresAtMs = Long.MAX_VALUE,
            stableIdsSnapshot = ProviderIds(tvdb = "393268", imdb = imdbId)
        )
    }

    private fun decodedStreamRouteArgs(route: String): Map<String, String> {
        val path = route.substringBefore("?")
        val pathParts = path.split("/")
        val queryArgs = route.substringAfter("?", "")
            .split("&")
            .filter { it.isNotBlank() }
            .associate { pair ->
                val key = pair.substringBefore("=")
                val value = pair.substringAfter("=", "")
                key to decode(value)
            }
        return queryArgs + mapOf(
            "videoId" to decode(pathParts[1]),
            "contentType" to decode(pathParts[2]),
            "title" to decode(pathParts[3])
        )
    }

    private fun decode(value: String): String {
        return URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }

    private fun sourceFile(relativePath: String): Path {
        val cwd = Paths.get("").toAbsolutePath().normalize()
        val relative = Paths.get("app", "src", "main", "java")
        val directCandidate = cwd.resolve(relative)
        val parentCandidate = cwd.resolve("..").resolve(relative).normalize()
        val sourceRoot = when {
            Files.exists(directCandidate) -> directCandidate
            Files.exists(parentCandidate) -> parentCandidate
            else -> error("Unable to locate app/src/main/java from working directory $cwd")
        }
        return sourceRoot.resolve(relativePath).also { path ->
            require(path.invariantSeparatorsPathString.endsWith(relativePath))
        }
    }
}
