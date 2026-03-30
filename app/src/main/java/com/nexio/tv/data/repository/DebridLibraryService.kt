package com.nexio.tv.data.repository

import com.nexio.tv.data.local.RealDebridAuthDataStore
import com.nexio.tv.data.remote.api.PremiumizeApi
import com.nexio.tv.data.remote.api.RealDebridApi
import com.nexio.tv.data.remote.dto.debrid.PremiumizeItemDetailsDto
import com.nexio.tv.data.remote.dto.debrid.PremiumizeListAllFileDto
import com.nexio.tv.data.remote.dto.debrid.RealDebridDownloadDto
import com.nexio.tv.data.remote.dto.debrid.RealDebridTorrentFileDto
import com.nexio.tv.data.remote.dto.debrid.RealDebridTorrentInfoDto
import com.nexio.tv.data.remote.dto.debrid.RealDebridTorrentDto
import com.nexio.tv.data.remote.dto.debrid.RealDebridUnrestrictLinkDto
import com.nexio.tv.domain.model.LibraryEntry
import com.nexio.tv.domain.model.LibraryListTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DebridLibraryService @Inject constructor(
    private val realDebridApi: RealDebridApi,
    private val realDebridAuthDataStore: RealDebridAuthDataStore,
    private val realDebridAuthService: RealDebridAuthService,
    private val premiumizeApi: PremiumizeApi,
    private val premiumizeService: PremiumizeService
) {
    enum class RefreshTarget {
        ALL,
        REAL_DEBRID,
        PREMIUMIZE
    }

    private data class Snapshot(
        val listTabs: List<LibraryListTab> = emptyList(),
        val items: List<LibraryEntry> = emptyList(),
        val updatedAtMs: Long = 0L
    )

    private val snapshotState = MutableStateFlow(Snapshot())
    private val refreshingState = MutableStateFlow(false)
    private val cacheTtlMs = 60_000L

    fun observeListTabs(): Flow<List<LibraryListTab>> {
        return snapshotState
            .map { it.listTabs }
            .distinctUntilChanged()
            .onStart { ensureFresh(force = false) }
    }

    fun observeItems(): Flow<List<LibraryEntry>> {
        return snapshotState
            .map { it.items }
            .distinctUntilChanged()
            .onStart { ensureFresh(force = false) }
    }

    fun observeIsRefreshing(): Flow<Boolean> = refreshingState

    fun observeIsConnected(): Flow<Boolean> {
        return combine(
            realDebridAuthDataStore.isAuthenticated,
            premiumizeService.observeAccountState()
        ) { rdAuthenticated, pmState ->
            rdAuthenticated || pmState.isConnected || pmState.apiKey.isNotBlank()
        }.distinctUntilChanged()
    }

    suspend fun refreshNow() {
        ensureFresh(force = true, target = RefreshTarget.ALL)
    }

    suspend fun refreshNow(target: RefreshTarget) {
        ensureFresh(force = true, target = target)
    }

    suspend fun ensureFresh(force: Boolean, target: RefreshTarget = RefreshTarget.ALL) {
        val now = System.currentTimeMillis()
        if (
            target == RefreshTarget.ALL &&
            !force &&
            snapshotState.value.updatedAtMs > 0L &&
            now - snapshotState.value.updatedAtMs < cacheTtlMs
        ) {
            return
        }

        refreshingState.value = true
        try {
            val current = snapshotState.value
            val refreshRealDebrid = target == RefreshTarget.ALL || target == RefreshTarget.REAL_DEBRID
            val refreshPremiumize = target == RefreshTarget.ALL || target == RefreshTarget.PREMIUMIZE

            val baseTabs = if (refreshRealDebrid || refreshPremiumize) {
                current.listTabs.filterNot { tab ->
                    (refreshRealDebrid && tab.key == REAL_DEBRID_LIST_KEY) ||
                        (refreshPremiumize && tab.key == PREMIUMIZE_LIST_KEY)
                }
            } else {
                current.listTabs
            }

            val baseItems = if (refreshRealDebrid || refreshPremiumize) {
                current.items.filterNot { entry ->
                    (refreshRealDebrid && entry.listKeys.contains(REAL_DEBRID_LIST_KEY)) ||
                        (refreshPremiumize && entry.listKeys.contains(PREMIUMIZE_LIST_KEY))
                }
            } else {
                current.items
            }

            val tabs = baseTabs.toMutableList()
            val items = baseItems.toMutableList()

            if (refreshRealDebrid && realDebridAuthDataStore.isAuthenticated.first()) {
                val realDebridItems = fetchRealDebridTorrents()
                if (realDebridItems.isNotEmpty()) {
                    tabs += LibraryListTab(
                        key = REAL_DEBRID_LIST_KEY,
                        title = "Real-Debrid",
                        type = LibraryListTab.Type.SERVICE,
                        description = "Direct links from your Real-Debrid torrents."
                    )
                    items += realDebridItems
                }
            }

            if (refreshPremiumize) {
                premiumizeService.refreshAccountState()
                val premiumizeState = premiumizeService.observeAccountState().first()
                if (premiumizeState.apiKey.isNotBlank()) {
                    val premiumizeItems = fetchPremiumizeItems(premiumizeState.apiKey)
                    if (premiumizeItems.isNotEmpty()) {
                        tabs += LibraryListTab(
                            key = PREMIUMIZE_LIST_KEY,
                            title = "Premiumize",
                            type = LibraryListTab.Type.SERVICE,
                            description = "Files from your Premiumize cloud that have a direct stream link."
                        )
                        items += premiumizeItems
                    }
                }
            }

            snapshotState.value = Snapshot(
                listTabs = tabs,
                items = items.sortedByDescending { it.listedAt },
                updatedAtMs = System.currentTimeMillis()
            )
        } finally {
            refreshingState.value = false
        }
    }

    private suspend fun fetchRealDebridTorrents(): List<LibraryEntry> = withContext(Dispatchers.IO) {
        val playbackHeaders = buildRealDebridPlaybackHeaders()
        val torrentsResponse = realDebridAuthService.executeAuthorizedRequest { authHeader ->
            realDebridApi.getTorrents(authorization = authHeader)
        } ?: return@withContext emptyList()
        val downloadsResponse = realDebridAuthService.executeAuthorizedRequest { authHeader ->
            realDebridApi.getDownloads(authorization = authHeader)
        } ?: return@withContext emptyList()

        if (!torrentsResponse.isSuccessful || !downloadsResponse.isSuccessful) return@withContext emptyList()

        val resolvedDownloadsByLink = downloadsResponse.body().orEmpty()
            .mapNotNull(::toResolvedDownload)
            .associateBy { it.link }

        val items = mutableListOf<LibraryEntry>()
        torrentsResponse.body().orEmpty()
            .filter { it.status.equals("downloaded", ignoreCase = true) }
            .forEach { torrent ->
                items += fetchRealDebridTorrentEntries(
                    torrent = torrent,
                    resolvedDownloadsByLink = resolvedDownloadsByLink,
                    playbackHeaders = playbackHeaders
                )
            }
        return@withContext items
    }

    private suspend fun fetchRealDebridTorrentEntries(
        torrent: RealDebridTorrentDto,
        resolvedDownloadsByLink: Map<String, RealDebridResolvedDownload>,
        playbackHeaders: Map<String, String>?
    ): List<LibraryEntry> {
        val infoResponse = realDebridAuthService.executeAuthorizedRequest { authHeader ->
            realDebridApi.getTorrentInfo(authorization = authHeader, id = torrent.id)
        } ?: return emptyList()
        if (!infoResponse.isSuccessful) return emptyList()

        val info = infoResponse.body() ?: return emptyList()
        val selectedFiles = info.files.orEmpty()
            .filter { it.selected == 1 }
        if (selectedFiles.isEmpty()) return emptyList()

        val items = mutableListOf<LibraryEntry>()
        selectedFiles.zip(info.links.orEmpty()).forEach { (file, link) ->
            if (isLikelySampleFile(file) || !isLikelyPlayable(file)) {
                return@forEach
            }
            val resolvedDownload = resolvedDownloadsByLink[link]
                ?.takeIf(::isLikelyPlayable)
                ?: unrestrictRealDebridLink(link)?.takeIf(::isLikelyPlayable)
                ?: return@forEach
            items += mapRealDebridTorrentFile(
                torrent = torrent,
                info = info,
                file = file,
                resolvedDownload = resolvedDownload,
                playbackHeaders = playbackHeaders
            )
        }
        return items
    }

    private suspend fun fetchPremiumizeItems(apiKey: String): List<LibraryEntry> = withContext(Dispatchers.IO) {
        val response = runCatching { premiumizeApi.listAllItems(apiKey) }.getOrNull() ?: return@withContext emptyList()
        if (!response.isSuccessful) return@withContext emptyList()

        val candidates = response.body()?.files.orEmpty()
            .filter(::isLikelyPlayable)
            .take(120)

        val detailsSemaphore = Semaphore(6)
        coroutineScope {
            candidates.map { file ->
                async {
                    detailsSemaphore.withPermit {
                        val detailsResponse = runCatching {
                            premiumizeApi.getItemDetails(apiKey = apiKey, id = file.id)
                        }.getOrNull() ?: return@withPermit null
                        val details = detailsResponse.body() ?: return@withPermit null
                        mapPremiumizeItem(file, details)
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    private fun mapRealDebridTorrentFile(
        torrent: RealDebridTorrentDto,
        info: RealDebridTorrentInfoDto,
        file: RealDebridTorrentFileDto,
        resolvedDownload: RealDebridResolvedDownload,
        playbackHeaders: Map<String, String>?
    ): LibraryEntry {
        val filePath = file.path
        val filename = resolvedDownload.filename
            ?.takeIf { it.isNotBlank() }
            ?: extractFilenameFromPath(filePath)
            ?: info.originalFilename
            ?: info.filename
            ?: torrent.filename.orEmpty().ifBlank { "Real-Debrid Torrent" }
        return LibraryEntry(
            id = "rd:torrent:${torrent.id}:file:${file.id}",
            type = inferContentType(filename, mimeType = resolvedDownload.mimeType),
            name = stripVideoExtension(filename),
            poster = null,
            background = null,
            logo = null,
            description = filePath ?: "Real-Debrid torrent",
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList(),
            addonBaseUrl = null,
            listKeys = setOf(REAL_DEBRID_LIST_KEY),
            listedAt = parseIsoToMillis(info.ended ?: info.added ?: torrent.ended ?: torrent.added),
            directPlaybackUrl = resolvedDownload.downloadUrl,
            playbackHeaders = playbackHeaders,
            playbackStreamName = filename,
            playbackFilename = filename
        )
    }

    private fun mapPremiumizeItem(
        file: PremiumizeListAllFileDto,
        details: PremiumizeItemDetailsDto
    ): LibraryEntry? {
        val streamUrl = details.streamLink?.takeIf { it.isNotBlank() }
            ?: details.link?.takeIf { it.isNotBlank() }
            ?: return null
        val filename = file.name.ifBlank { details.name.orEmpty().ifBlank { "Premiumize File" } }
        val resolution = listOfNotNull(details.width, details.height)
            .joinToString("x")
            .takeIf { it.isNotBlank() }
        return LibraryEntry(
            id = "pm:item:${file.id}",
            type = inferContentType(filename, file.mimeType ?: details.mimeType),
            name = stripVideoExtension(filename),
            poster = null,
            background = null,
            logo = null,
            description = file.path,
            releaseInfo = resolution ?: details.duration,
            imdbRating = null,
            genres = emptyList(),
            addonBaseUrl = null,
            listKeys = setOf(PREMIUMIZE_LIST_KEY),
            listedAt = (file.createdAt ?: details.createdAt ?: 0L) * 1000L,
            directPlaybackUrl = streamUrl,
            playbackStreamName = filename,
            playbackFilename = filename
        )
    }

    private fun isLikelyPlayable(download: RealDebridResolvedDownload): Boolean {
        return isLikelyVideo(
            filename = download.filename ?: extractFilenameFromUrl(download.downloadUrl),
            mimeType = download.mimeType
        )
    }

    private fun isLikelyPlayable(file: RealDebridTorrentFileDto): Boolean {
        return isLikelyVideo(
            filename = extractFilenameFromPath(file.path),
            mimeType = null
        )
    }

    private fun isLikelyPlayable(file: PremiumizeListAllFileDto): Boolean {
        return isLikelyVideo(file.name, file.mimeType)
    }

    private fun isLikelySampleFile(file: RealDebridTorrentFileDto): Boolean {
        val normalizedPath = file.path.orEmpty().trim().lowercase()
        if (normalizedPath.isBlank()) return false
        val segments = normalizedPath.split('/').filter { it.isNotBlank() }
        if (segments.any { it == "sample" || it == "samples" }) return true
        val filename = extractFilenameFromPath(normalizedPath).orEmpty()
        return filename.startsWith("sample.") ||
            filename.startsWith("sample-") ||
            filename.contains(".sample.")
    }

    private fun isLikelyVideo(filename: String?, mimeType: String?): Boolean {
        val normalizedMime = mimeType.orEmpty().trim().lowercase()
        if (normalizedMime.startsWith("video/")) return true
        val normalizedName = filename.orEmpty().trim().lowercase()
        return VIDEO_EXTENSIONS.any { normalizedName.endsWith(it) }
    }

    private fun inferContentType(filename: String?, mimeType: String?): String {
        val normalizedName = filename.orEmpty().lowercase()
        if (SERIES_PATTERNS.any { it.containsMatchIn(normalizedName) }) {
            return "series"
        }
        return if (isLikelyVideo(filename, mimeType)) "movie" else "other"
    }

    private fun stripVideoExtension(filename: String): String {
        return filename.substringBeforeLast('.', filename)
    }

    private fun toResolvedDownload(download: RealDebridDownloadDto): RealDebridResolvedDownload? {
        val link = download.link?.takeIf { it.isNotBlank() } ?: return null
        val downloadUrl = download.download?.takeIf { it.isNotBlank() } ?: return null
        return RealDebridResolvedDownload(
            link = link,
            downloadUrl = downloadUrl,
            filename = download.filename,
            mimeType = download.mimeType
        )
    }

    private fun toResolvedDownload(download: RealDebridUnrestrictLinkDto): RealDebridResolvedDownload? {
        val link = download.link?.takeIf { it.isNotBlank() } ?: return null
        val downloadUrl = download.download?.takeIf { it.isNotBlank() } ?: return null
        return RealDebridResolvedDownload(
            link = link,
            downloadUrl = downloadUrl,
            filename = download.filename,
            mimeType = download.mimeType
        )
    }

    private fun extractFilenameFromUrl(url: String): String? {
        val path = url.substringBefore('?').substringAfterLast('/')
        return path.takeIf { it.isNotBlank() }
    }

    private fun extractFilenameFromPath(path: String?): String? {
        val normalizedPath = path?.substringBefore('?')?.trim().orEmpty()
        if (normalizedPath.isBlank()) return null
        return normalizedPath.substringAfterLast('/').takeIf { it.isNotBlank() }
    }

    private suspend fun unrestrictRealDebridLink(link: String): RealDebridResolvedDownload? {
        val response = realDebridAuthService.executeAuthorizedRequest { authHeader ->
            realDebridApi.unrestrictLink(
                authorization = authHeader,
                link = link,
                remote = 0
            )
        } ?: return null
        if (!response.isSuccessful) return null
        return response.body()?.let(::toResolvedDownload)
    }

    private suspend fun buildRealDebridPlaybackHeaders(): Map<String, String>? {
        val accessToken = realDebridAuthDataStore.state.first().accessToken?.takeIf { it.isNotBlank() }
            ?: return null
        return mapOf("Authorization" to "Bearer $accessToken")
    }

    private fun parseIsoToMillis(rawValue: String?): Long {
        if (rawValue.isNullOrBlank()) return 0L
        return runCatching { OffsetDateTime.parse(rawValue).toInstant().toEpochMilli() }.getOrDefault(0L)
    }

    companion object {
        const val REAL_DEBRID_LIST_KEY = "service:realdebrid"
        const val PREMIUMIZE_LIST_KEY = "service:premiumize"

        private val VIDEO_EXTENSIONS = listOf(
            ".mkv", ".mp4", ".avi", ".mov", ".wmv", ".ts", ".m2ts", ".webm", ".mpg", ".mpeg"
        )

        private val SERIES_PATTERNS = listOf(
            Regex("""\bs\d{1,2}e\d{1,2}\b"""),
            Regex("""\b\d{1,2}x\d{1,2}\b""")
        )
    }

    private data class RealDebridResolvedDownload(
        val link: String,
        val downloadUrl: String,
        val filename: String?,
        val mimeType: String?
    )
}
