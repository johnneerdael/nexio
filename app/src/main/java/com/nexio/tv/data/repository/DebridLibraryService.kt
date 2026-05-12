package com.nexio.tv.data.repository

import com.nexio.tv.data.local.EasyDebridSettingsDataStore
import com.nexio.tv.data.local.PremiumizeSettingsDataStore
import com.nexio.tv.data.local.RealDebridAuthDataStore
import com.nexio.tv.data.local.TorBoxSettingsDataStore
import com.nexio.tv.data.integration.debrid.EasyDebridIntegrationProvider
import com.nexio.tv.data.integration.debrid.PremiumizeIntegrationProvider
import com.nexio.tv.data.integration.debrid.RealDebridIntegrationProvider
import com.nexio.tv.data.integration.debrid.TorBoxIntegrationProvider
import com.nexio.tv.data.remote.dto.debrid.EasyDebridGenerateDto
import com.nexio.tv.data.remote.dto.debrid.EasyDebridGenerateRequestDto
import com.nexio.tv.data.remote.dto.debrid.EasyDebridGeneratedFileDto
import com.nexio.tv.data.remote.dto.debrid.EasyDebridLookupDetailsResultDto
import com.nexio.tv.data.remote.dto.debrid.EasyDebridLookupRequestDto
import com.nexio.tv.data.remote.dto.debrid.PremiumizeItemDetailsDto
import com.nexio.tv.data.remote.dto.debrid.PremiumizeListAllFileDto
import com.nexio.tv.data.remote.dto.debrid.RealDebridDownloadDto
import com.nexio.tv.data.remote.dto.debrid.RealDebridTorrentFileDto
import com.nexio.tv.data.remote.dto.debrid.RealDebridTorrentInfoDto
import com.nexio.tv.data.remote.dto.debrid.RealDebridTorrentDto
import com.nexio.tv.data.remote.dto.debrid.RealDebridUnrestrictLinkDto
import com.nexio.tv.data.remote.dto.debrid.TorBoxFileDto
import com.nexio.tv.data.remote.dto.debrid.TorBoxTorrentListItemDto
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkCandidate
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkCandidateLookupResult
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkProvider
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
    private val realDebridProvider: RealDebridIntegrationProvider,
    private val realDebridAuthDataStore: RealDebridAuthDataStore,
    private val premiumizeProvider: PremiumizeIntegrationProvider,
    private val premiumizeSettingsDataStore: PremiumizeSettingsDataStore,
    private val torBoxProvider: TorBoxIntegrationProvider,
    private val torBoxSettingsDataStore: TorBoxSettingsDataStore,
    private val easyDebridProvider: EasyDebridIntegrationProvider,
    private val easyDebridSettingsDataStore: EasyDebridSettingsDataStore
) {
    private data class RealDebridBenchmarkPreview(
        val torrent: RealDebridTorrentDto,
        val info: RealDebridTorrentInfoDto,
        val file: RealDebridTorrentFileDto,
        val link: String,
        val listedAtMs: Long
    )

    enum class RefreshTarget {
        ALL,
        REAL_DEBRID,
        PREMIUMIZE,
        TORBOX
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

    suspend fun getBenchmarkCandidates(provider: DebridBenchmarkProvider): DebridBenchmarkCandidateLookupResult {
        return when (provider) {
            DebridBenchmarkProvider.REAL_DEBRID -> {
                if (!realDebridAuthDataStore.isAuthenticated.first()) {
                    DebridBenchmarkCandidateLookupResult.NoPlayableLibraryItem
                } else {
                    fetchRealDebridBenchmarkCandidates()
                }
            }
            DebridBenchmarkProvider.PREMIUMIZE -> {
                val apiKey = premiumizeSettingsDataStore.settings.first().apiKey.trim()
                if (apiKey.isBlank() || !hasPremiumizeAccount(apiKey)) {
                    DebridBenchmarkCandidateLookupResult.NoPlayableLibraryItem
                } else {
                    fetchPremiumizeBenchmarkCandidates(apiKey)
                }
            }
            DebridBenchmarkProvider.TORBOX -> {
                val apiKey = torBoxSettingsDataStore.settings.first().apiKey.trim()
                if (apiKey.isBlank() || !hasTorBoxAccount(apiKey)) {
                    DebridBenchmarkCandidateLookupResult.NoPlayableLibraryItem
                } else {
                    fetchTorBoxBenchmarkCandidates(apiKey)
                }
            }
            DebridBenchmarkProvider.EASY_DEBRID -> {
                val apiKey = easyDebridSettingsDataStore.settings.first().apiKey.trim()
                if (apiKey.isBlank() || !hasEasyDebridAccount(apiKey)) {
                    DebridBenchmarkCandidateLookupResult.NoPlayableLibraryItem
                } else {
                    fetchEasyDebridBenchmarkCandidates(apiKey)
                }
            }
        }
    }

    private suspend fun fetchRealDebridBenchmarkCandidates(): DebridBenchmarkCandidateLookupResult = withContext(Dispatchers.IO) {
        val playbackHeaders = buildRealDebridPlaybackHeaders()
        val torrents = realDebridProvider.fetchTorrents()
            .filter { it.status.equals("downloaded", ignoreCase = true) }
        val downloads = realDebridProvider.fetchDownloads()
        if (torrents.isEmpty() && downloads.isEmpty()) {
            return@withContext DebridBenchmarkCandidateLookupResult.NoPlayableLibraryItem
        }
        val previews = resolveRealDebridBenchmarkPreviews(torrents)
        val shortlistedPreviews = selectBenchmarkResolutionShortlist(
            items = previews,
            sizeOf = { preview: RealDebridBenchmarkPreview -> preview.file.bytes },
            timestampOf = { preview: RealDebridBenchmarkPreview -> preview.listedAtMs }
        )
        if (shortlistedPreviews.isEmpty()) {
            return@withContext DebridBenchmarkCandidateLookupResult.NoLargeDownload
        }

        val resolvedDownloadsByLink = downloads
            .mapNotNull(::toResolvedDownload)
            .associateBy { it.link }

        val candidates = shortlistedPreviews.mapNotNull { preview ->
            resolveRealDebridBenchmarkCandidate(
                preview = preview,
                resolvedDownloadsByLink = resolvedDownloadsByLink,
                playbackHeaders = playbackHeaders
            )
        }
        if (candidates.isEmpty()) {
            DebridBenchmarkCandidateLookupResult.NoPlayableLibraryItem
        } else {
            DebridBenchmarkCandidateLookupResult.Candidates(candidates)
        }
    }

    private suspend fun fetchPremiumizeBenchmarkCandidates(apiKey: String): DebridBenchmarkCandidateLookupResult = withContext(Dispatchers.IO) {
        val listAll = premiumizeProvider.fetchListAllItems(apiKey)
            ?: return@withContext DebridBenchmarkCandidateLookupResult.NoPlayableLibraryItem

        val shortlistedFiles = selectBenchmarkResolutionShortlist(
            items = listAll.files.orEmpty()
                .filter(::isLikelyPlayable),
            sizeOf = { file: PremiumizeListAllFileDto -> file.size },
            timestampOf = { file: PremiumizeListAllFileDto -> (file.createdAt ?: 0L) * 1000L }
        )
        if (shortlistedFiles.isEmpty()) {
            return@withContext DebridBenchmarkCandidateLookupResult.NoLargeDownload
        }

        val candidates = shortlistedFiles.mapNotNull { file ->
            val details = premiumizeProvider.fetchItemDetails(apiKey = apiKey, id = file.id)
                ?: return@mapNotNull null
            mapPremiumizeItem(file, details)?.toBenchmarkCandidate(DebridBenchmarkProvider.PREMIUMIZE)
        }
        if (candidates.isEmpty()) {
            DebridBenchmarkCandidateLookupResult.NoPlayableLibraryItem
        } else {
            DebridBenchmarkCandidateLookupResult.Candidates(candidates)
        }
    }

    private suspend fun fetchTorBoxBenchmarkCandidates(apiKey: String): DebridBenchmarkCandidateLookupResult = withContext(Dispatchers.IO) {
        val entries = fetchTorBoxItems(apiKey)
        val shortlistedEntries = selectBenchmarkResolutionShortlist(
            items = entries,
            sizeOf = { entry: LibraryEntry -> entry.playbackSizeBytes },
            timestampOf = { entry: LibraryEntry -> entry.listedAt }
        )
        if (shortlistedEntries.isEmpty()) {
            return@withContext DebridBenchmarkCandidateLookupResult.NoLargeDownload
        }

        // Library entries now carry no eager directPlaybackUrl (lazy-resolved on click in normal use).
        // For benchmarks we still need a resolved URL per shortlisted entry, so resolve on demand here.
        // The shortlist is small (<= a few items), so this is cheap compared to the prior
        // resolve-every-entry approach.
        val torrentEntryRegex = Regex("""^tb:torrent:(\d+):file:(\d+)$""")
        val candidates = mutableListOf<DebridBenchmarkCandidate>()
        for (i in shortlistedEntries.indices) {
            val entry = shortlistedEntries[i]
            val match = torrentEntryRegex.matchEntire(entry.id) ?: continue
            val torrentId = match.groupValues[1].toIntOrNull() ?: continue
            val fileId = match.groupValues[2].toIntOrNull() ?: continue
            val url = requestTorBoxDownload(apiKey = apiKey, torrentId = torrentId, fileId = fileId)
                ?: continue
            entry.copy(directPlaybackUrl = url).toBenchmarkCandidate(DebridBenchmarkProvider.TORBOX)
                ?.let { candidates += it }
        }
        if (candidates.isEmpty()) {
            DebridBenchmarkCandidateLookupResult.NoPlayableLibraryItem
        } else {
            DebridBenchmarkCandidateLookupResult.Candidates(candidates)
        }
    }

    private suspend fun fetchEasyDebridBenchmarkCandidates(apiKey: String): DebridBenchmarkCandidateLookupResult = withContext(Dispatchers.IO) {
        val sources = EASY_DEBRID_BENCHMARK_SOURCES
        val lookupBody = easyDebridProvider.lookupDetails(
            apiKey = apiKey,
            body = EasyDebridLookupRequestDto(urls = sources.map { it.url })
        ) ?: return@withContext DebridBenchmarkCandidateLookupResult.NoPlayableLibraryItem

        val lookupCandidates = sources.mapIndexedNotNull { index, source ->
            val result = lookupBody.result.getOrNull(index) ?: return@mapIndexedNotNull null
            if (!result.cached) return@mapIndexedNotNull null
            val largestFile = result.files
                .filter { file ->
                    !isLikelySampleFile(file.name, file.folder) &&
                        isStrictPlayableVideoCandidate(
                            filename = file.name,
                            folder = file.folder
                        )
                }
                .maxByOrNull { it.size ?: 0L }
                ?: return@mapIndexedNotNull null
            EasyDebridBenchmarkLookupCandidate(
                source = source,
                largestSizeBytes = largestFile.size ?: 0L
            )
        }.sortedByDescending { it.largestSizeBytes }

        val shortlisted = lookupCandidates.filter { it.largestSizeBytes >= EASY_DEBRID_MIN_BENCHMARK_BYTES }
        if (shortlisted.isEmpty()) {
            return@withContext DebridBenchmarkCandidateLookupResult.NoLargeDownload
        }

        val resolvedCandidates = shortlisted.take(BENCHMARK_MAX_RESOLUTION_COUNT).mapNotNull { candidate ->
            val generateBody = easyDebridProvider.generate(
                apiKey = apiKey,
                body = EasyDebridGenerateRequestDto(url = candidate.source.url)
            ) ?: return@mapNotNull null

            val selectedFile = generateBody.files
                .filter { file ->
                    !isLikelySampleFile(file.filename, file.directory.joinToString("/")) &&
                        isStrictPlayableVideoCandidate(
                            filename = file.filename,
                            folder = file.directory.joinToString("/")
                        )
                }
                .maxByOrNull { it.size ?: 0L }
                ?: return@mapNotNull null

            selectedFile.toBenchmarkCandidate(DebridBenchmarkProvider.EASY_DEBRID)
        }

        if (resolvedCandidates.isEmpty()) {
            DebridBenchmarkCandidateLookupResult.NoPlayableLibraryItem
        } else {
            DebridBenchmarkCandidateLookupResult.Candidates(resolvedCandidates)
        }
    }

    fun observeIsRefreshing(): Flow<Boolean> = refreshingState

    fun observeIsConnected(): Flow<Boolean> {
        return combine(
            realDebridAuthDataStore.isAuthenticated,
            premiumizeSettingsDataStore.settings,
            torBoxSettingsDataStore.settings,
            easyDebridSettingsDataStore.settings
        ) { rdAuthenticated, premiumizeSettings, torBoxSettings, easyDebridSettings ->
            rdAuthenticated ||
                premiumizeSettings.apiKey.isNotBlank() ||
                torBoxSettings.apiKey.isNotBlank() ||
                easyDebridSettings.apiKey.isNotBlank()
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
            val refreshTorBox = target == RefreshTarget.ALL || target == RefreshTarget.TORBOX

            val baseTabs = if (refreshRealDebrid || refreshPremiumize || refreshTorBox) {
                current.listTabs.filterNot { tab ->
                    (refreshRealDebrid && tab.key == REAL_DEBRID_LIST_KEY) ||
                        (refreshPremiumize && tab.key == PREMIUMIZE_LIST_KEY) ||
                        (refreshTorBox && tab.key == TORBOX_LIST_KEY)
                }
            } else {
                current.listTabs
            }

            val baseItems = if (refreshRealDebrid || refreshPremiumize || refreshTorBox) {
                current.items.filterNot { entry ->
                    (refreshRealDebrid && entry.listKeys.contains(REAL_DEBRID_LIST_KEY)) ||
                        (refreshPremiumize && entry.listKeys.contains(PREMIUMIZE_LIST_KEY)) ||
                        (refreshTorBox && entry.listKeys.contains(TORBOX_LIST_KEY))
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
                val premiumizeApiKey = premiumizeSettingsDataStore.settings.first().apiKey.trim()
                if (premiumizeApiKey.isNotBlank() && hasPremiumizeAccount(premiumizeApiKey)) {
                    val premiumizeItems = fetchPremiumizeItems(premiumizeApiKey)
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

            if (refreshTorBox) {
                val torBoxApiKey = torBoxSettingsDataStore.settings.first().apiKey.trim()
                if (torBoxApiKey.isNotBlank() && hasTorBoxAccount(torBoxApiKey)) {
                    val torBoxItems = fetchTorBoxItems(torBoxApiKey)
                    if (torBoxItems.isNotEmpty()) {
                        tabs += LibraryListTab(
                            key = TORBOX_LIST_KEY,
                            title = "TorBox",
                            type = LibraryListTab.Type.SERVICE,
                            description = "Cached files from your TorBox torrent list."
                        )
                        items += torBoxItems
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
        val torrents = realDebridProvider.fetchTorrents()
        val downloads = realDebridProvider.fetchDownloads()
        if (torrents.isEmpty() && downloads.isEmpty()) return@withContext emptyList()

        val resolvedDownloadsByLink = downloads
            .mapNotNull(::toResolvedDownload)
            .associateBy { it.link }

        val items = mutableListOf<LibraryEntry>()
        torrents
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
        val info = realDebridProvider.fetchTorrentInfo(torrent.id) ?: return emptyList()
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

    private suspend fun resolveRealDebridBenchmarkPreviews(
        torrents: List<RealDebridTorrentDto>
    ): List<RealDebridBenchmarkPreview> = coroutineScope {
        val infoSemaphore = Semaphore(6)
        torrents.map { torrent ->
            async {
                infoSemaphore.withPermit {
                    val info = realDebridProvider.fetchTorrentInfo(torrent.id) ?: return@withPermit null
                    val fileLinkPair = info.files.orEmpty()
                        .filter { it.selected == 1 }
                        .zip(info.links.orEmpty())
                        .filter { (file, _) -> !isLikelySampleFile(file) && isLikelyPlayable(file) }
                        .maxByOrNull { (file, _) -> file.bytes ?: 0L }
                        ?: return@withPermit null

                    val (file, link) = fileLinkPair
                    RealDebridBenchmarkPreview(
                        torrent = torrent,
                        info = info,
                        file = file,
                        link = link,
                        listedAtMs = parseIsoToMillis(torrent.ended ?: torrent.added)
                    )
                }
            }
        }.awaitAll().filterNotNull()
    }

    private suspend fun resolveRealDebridBenchmarkCandidate(
        preview: RealDebridBenchmarkPreview,
        resolvedDownloadsByLink: Map<String, RealDebridResolvedDownload>,
        playbackHeaders: Map<String, String>?
    ): DebridBenchmarkCandidate? {
        val resolvedDownload = resolvedDownloadsByLink[preview.link]
            ?.takeIf(::isLikelyPlayable)
            ?: unrestrictRealDebridLink(preview.link)?.takeIf(::isLikelyPlayable)
            ?: return null

        return mapRealDebridTorrentFile(
            torrent = preview.torrent,
            info = preview.info,
            file = preview.file,
            resolvedDownload = resolvedDownload,
            playbackHeaders = playbackHeaders
        ).toBenchmarkCandidate(DebridBenchmarkProvider.REAL_DEBRID)
    }

    private suspend fun fetchPremiumizeItems(apiKey: String): List<LibraryEntry> = withContext(Dispatchers.IO) {
        val listAll = premiumizeProvider.fetchListAllItems(apiKey) ?: return@withContext emptyList()

        val candidates = listAll.files.orEmpty()
            .filter(::isLikelyPlayable)
            .take(120)

        val detailsSemaphore = Semaphore(6)
        coroutineScope {
            candidates.map { file ->
                async {
                    detailsSemaphore.withPermit {
                        val details = premiumizeProvider.fetchItemDetails(apiKey = apiKey, id = file.id)
                            ?: return@withPermit null
                        mapPremiumizeItem(file, details)
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    private suspend fun fetchTorBoxItems(apiKey: String): List<LibraryEntry> = withContext(Dispatchers.IO) {
        val response = torBoxProvider.fetchTorrentList(apiKey = apiKey, limit = 100)
            ?: return@withContext emptyList()

        val candidates = response.data.orEmpty()
            .filter { it.id != null && it.files.isNotEmpty() }
            .filter { it.isDownloaded() || it.resolvedState().equals("downloaded", ignoreCase = true) }
            .take(100)

        val out = mutableListOf<LibraryEntry>()
        for (i in candidates.indices) {
            val torrent = candidates[i]
            for (j in torrent.files.indices) {
                val file = torrent.files[j]
                if (file.id == null || !isLikelyPlayable(file)) continue
                out += mapTorBoxItem(torrent, file)
            }
        }
        out
    }

    suspend fun nextPlayableFileInTorrent(
        torrentId: Int,
        currentFileId: Int,
    ): TorBoxNextFile? = withContext(Dispatchers.IO) {
        val apiKey = torBoxSettingsDataStore.settings.first().apiKey.trim()
        if (apiKey.isBlank()) return@withContext null
        val response = torBoxProvider.fetchTorrentList(apiKey = apiKey, id = torrentId, limit = 1)
            ?: return@withContext null
        // CLAUDE.md #4: indexed-for inside suspend fun — no Iterable.firstOrNull / forEach.
        val items = response.data.orEmpty()
        var torrent: TorBoxTorrentListItemDto? = null
        for (i in items.indices) {
            val candidate = items[i]
            if (candidate.id == torrentId) {
                torrent = candidate
                break
            }
        }
        if (torrent == null) return@withContext null
        pickNextFileInTorrent(torrent, currentFileId)
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
            playbackFilename = filename,
            playbackSizeBytes = resolvedDownload.fileSize ?: file.bytes
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
            playbackFilename = filename,
            playbackSizeBytes = details.size ?: file.size
        )
    }

    private fun mapTorBoxItem(
        torrent: TorBoxTorrentListItemDto,
        file: TorBoxFileDto,
    ): LibraryEntry {
        val base = buildTorBoxEntry(torrent, file)
        val typed = inferContentType(base.playbackFilename, file.mimeType)
        return if (typed == base.type) base else base.copy(type = typed)
    }

    private fun LibraryEntry.toBenchmarkCandidate(provider: DebridBenchmarkProvider): DebridBenchmarkCandidate? {
        val directUrl = directPlaybackUrl?.takeIf { it.isNotBlank() } ?: return null
        return DebridBenchmarkCandidate(
            provider = provider,
            directUrl = directUrl,
            headers = playbackHeaders.orEmpty(),
            filename = playbackFilename?.takeIf { it.isNotBlank() },
            sourceSizeBytes = playbackSizeBytes
        )
    }

    private fun EasyDebridGeneratedFileDto.toBenchmarkCandidate(provider: DebridBenchmarkProvider): DebridBenchmarkCandidate? {
        val directUrl = url?.takeIf { it.isNotBlank() } ?: return null
        return DebridBenchmarkCandidate(
            provider = provider,
            directUrl = directUrl,
            headers = emptyMap(),
            filename = filename?.takeIf { it.isNotBlank() },
            sourceSizeBytes = size
        )
    }

    private fun isLikelyPlayable(download: RealDebridResolvedDownload): Boolean {
        return isStrictPlayableVideoCandidate(
            filename = download.filename ?: extractFilenameFromUrl(download.downloadUrl)
        )
    }

    private fun isLikelyPlayable(file: RealDebridTorrentFileDto): Boolean {
        return isStrictPlayableVideoCandidate(
            filename = extractFilenameFromPath(file.path),
            fullPath = file.path
        )
    }

    private fun isLikelyPlayable(file: PremiumizeListAllFileDto): Boolean {
        return isStrictPlayableVideoCandidate(
            filename = file.name,
            fullPath = file.path
        )
    }

    private fun isLikelyPlayable(file: TorBoxFileDto): Boolean =
        isTorBoxFilePlayable(file)

    private fun isLikelySampleFile(file: RealDebridTorrentFileDto): Boolean {
        return isLikelySampleVideoFile(
            filename = extractFilenameFromPath(file.path),
            folder = file.path?.substringBeforeLast('/', "")
        )
    }

    private fun isLikelySampleFile(file: TorBoxFileDto): Boolean {
        return isLikelySampleVideoFile(filename = file.name ?: file.shortName)
    }

    private fun isLikelySampleFile(filename: String?, folder: String?): Boolean {
        return isLikelySampleVideoFile(filename = filename, folder = folder)
    }

    private fun inferContentType(filename: String?, mimeType: String?): String {
        val normalizedName = filename.orEmpty().lowercase()
        if (SERIES_PATTERNS.any { it.containsMatchIn(normalizedName) }) {
            return "series"
        }
        return if (isStrictPlayableVideoCandidate(filename = filename)) "movie" else "other"
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
            mimeType = download.mimeType,
            fileSize = download.fileSize
        )
    }

    private fun toResolvedDownload(download: RealDebridUnrestrictLinkDto): RealDebridResolvedDownload? {
        val link = download.link?.takeIf { it.isNotBlank() } ?: return null
        val downloadUrl = download.download?.takeIf { it.isNotBlank() } ?: return null
        return RealDebridResolvedDownload(
            link = link,
            downloadUrl = downloadUrl,
            filename = download.filename,
            mimeType = download.mimeType,
            fileSize = download.fileSize
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
        return realDebridProvider.unrestrictLink(link)?.let(::toResolvedDownload)
    }

    private suspend fun hasPremiumizeAccount(apiKey: String): Boolean {
        val result = premiumizeProvider.fetchAccountInfo(apiKey)
        return result is com.nexio.tv.core.integration.IntegrationCallResult.Success &&
            result.value.status.equals("success", ignoreCase = true)
    }

    private suspend fun hasTorBoxAccount(apiKey: String): Boolean {
        val result = torBoxProvider.fetchAccountInfo(apiKey)
        return result is com.nexio.tv.core.integration.IntegrationCallResult.Success &&
            result.value.success == true
    }

    private suspend fun hasEasyDebridAccount(apiKey: String): Boolean {
        val result = easyDebridProvider.fetchAccountInfo(apiKey)
        return result is com.nexio.tv.core.integration.IntegrationCallResult.Success &&
            result.value.id?.isNotBlank() == true
    }

    private suspend fun buildRealDebridPlaybackHeaders(): Map<String, String>? {
        val accessToken = realDebridAuthDataStore.state.first().accessToken?.takeIf { it.isNotBlank() }
            ?: return null
        return mapOf("Authorization" to "Bearer $accessToken")
    }

    private suspend fun requestTorBoxDownload(
        apiKey: String,
        torrentId: Int,
        fileId: Int
    ): String? {
        return torBoxProvider.requestDownloadLink(
            apiKey = apiKey,
            torrentId = torrentId,
            fileId = fileId
        )?.takeIf { it.isNotBlank() }
    }

    private fun parseIsoToMillis(rawValue: String?): Long {
        if (rawValue.isNullOrBlank()) return 0L
        return runCatching { OffsetDateTime.parse(rawValue).toInstant().toEpochMilli() }.getOrDefault(0L)
    }

    companion object {
        const val REAL_DEBRID_LIST_KEY = "service:realdebrid"
        const val PREMIUMIZE_LIST_KEY = "service:premiumize"
        const val TORBOX_LIST_KEY = "service:torbox"
        const val EASY_DEBRID_LIST_KEY = "service:easydebrid"

        internal const val TORBOX_MIN_PLAYABLE_BYTES: Long = 50L * 1024L * 1024L

        @JvmStatic
        fun isTorBoxFilePlayable(file: TorBoxFileDto): Boolean {
            val size = file.size ?: return false
            if (size < TORBOX_MIN_PLAYABLE_BYTES) return false
            // Accept either a video/* mimeType OR a known video extension. TorBox populates
            // mimetype for most files but not all; falling back to the extension whitelist
            // recovers entries the strict mimeType-only filter was rejecting.
            val mime = file.mimeType
            if (mime != null && mime.startsWith("video/", ignoreCase = true)) return true
            val filename = file.shortName ?: file.name ?: return false
            val extension = filename.substringAfterLast('.', "").lowercase(java.util.Locale.US)
            return extension.isNotEmpty() && extension in STRICT_PLAYABLE_VIDEO_EXTENSIONS
        }

        data class TorBoxNextFile(
            val torrentId: Int,
            val fileId: Int,
            val fileName: String,
        )

        /**
         * Build a [LibraryEntry] for a single TorBox file. The playback URL is intentionally
         * left null — the lazy resolve happens on click via TorBoxDirectPlayHandler.
         */
        @JvmStatic
        fun buildTorBoxEntry(
            torrent: TorBoxTorrentListItemDto,
            file: TorBoxFileDto,
        ): LibraryEntry {
            val filename = file.shortName
                ?.takeIf { it.isNotBlank() }
                ?: file.name
                ?.takeIf { it.isNotBlank() }
                ?: torrent.name.orEmpty().ifBlank { "TorBox File" }
            return LibraryEntry(
                id = "tb:torrent:${torrent.id}:file:${file.id}",
                type = "other",
                name = stripVideoExtensionPure(filename),
                poster = null,
                background = null,
                logo = null,
                description = torrent.name,
                releaseInfo = file.size?.takeIf { it > 0L }?.let { "${it / (1024 * 1024)} MB" },
                imdbRating = null,
                genres = emptyList(),
                addonBaseUrl = null,
                listKeys = setOf(TORBOX_LIST_KEY),
                listedAt = parseIsoToMillisPure(torrent.createdAt ?: torrent.legacyCreatedAt),
                directPlaybackUrl = null,
                playbackStreamName = filename,
                playbackFilename = filename,
                playbackSizeBytes = file.size,
            )
        }

        /** Pure mirror of the instance `stripVideoExtension` helper. */
        @JvmStatic
        internal fun stripVideoExtensionPure(filename: String): String =
            filename.substringBeforeLast('.', filename)

        /** Pure mirror of the instance `parseIsoToMillis` helper. */
        @JvmStatic
        internal fun parseIsoToMillisPure(raw: String?): Long {
            if (raw.isNullOrBlank()) return 0L
            return runCatching { java.time.OffsetDateTime.parse(raw).toInstant().toEpochMilli() }
                .getOrDefault(0L)
        }

        @JvmStatic
        fun pickNextFileInTorrent(
            torrent: TorBoxTorrentListItemDto,
            currentFileId: Int,
        ): TorBoxNextFile? {
            val torrentId = torrent.id ?: return null
            val playable = torrent.files
                .filter { it.id != null && isTorBoxFilePlayable(it) }
                .sortedBy { it.shortName ?: it.name ?: "" }
            var found = false
            for (i in playable.indices) {
                val file = playable[i]
                if (found) {
                    val name = file.shortName ?: file.name ?: continue
                    return TorBoxNextFile(torrentId = torrentId, fileId = file.id!!, fileName = name)
                }
                if (file.id == currentFileId) found = true
            }
            return null
        }
        private const val BENCHMARK_SIZE_GIB = 1024L * 1024L * 1024L
        private const val BENCHMARK_MAX_RESOLUTION_COUNT = 2
        private const val EASY_DEBRID_MIN_BENCHMARK_BYTES = 1L * BENCHMARK_SIZE_GIB
        private val BENCHMARK_SIZE_THRESHOLDS_BYTES = listOf(
            20L * BENCHMARK_SIZE_GIB,
            15L * BENCHMARK_SIZE_GIB,
            10L * BENCHMARK_SIZE_GIB,
            5L * BENCHMARK_SIZE_GIB
        )

        private val SERIES_PATTERNS = listOf(
            Regex("""\bs\d{1,2}e\d{1,2}\b"""),
            Regex("""\b\d{1,2}x\d{1,2}\b""")
        )

        private val EASY_DEBRID_BENCHMARK_SOURCES = listOf(
            EasyDebridBenchmarkSource(
                label = "Ubuntu 22.04.4 desktop",
                url = "magnet:?xt=urn:btih:018e50b58106b84a42c223ccf0494334f8d55958&dn=ubuntu-22.04.4-desktop-amd64.iso&tr=https%3A%2F%2Ftorrent.ubuntu.com%2Fannounce&tr=https%3A%2F%2Fipv6.torrent.ubuntu.com%2Fannounce"
            ),
            EasyDebridBenchmarkSource(
                label = "Big Buck Bunny sample",
                url = "magnet:?xt=urn:btih:dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c&dn=Big+Buck+Bunny&tr=udp%3A%2F%2Fexplodie.org%3A6969&tr=udp%3A%2F%2Ftracker.coppersurfer.tk%3A6969&tr=udp%3A%2F%2Ftracker.empire-js.us%3A1337&tr=udp%3A%2F%2Ftracker.leechers-paradise.org%3A6969&tr=udp%3A%2F%2Ftracker.opentrackr.org%3A1337&tr=wss%3A%2F%2Ftracker.btorrent.xyz&tr=wss%3A%2F%2Ftracker.fastcast.nz&tr=wss%3A%2F%2Ftracker.openwebtorrent.com&ws=https%3A%2F%2Fwebtorrent.io%2Ftorrents%2F&xs=https%3A%2F%2Fwebtorrent.io%2Ftorrents%2Fbig-buck-bunny.torrent"
            )
        )
    }

    private data class RealDebridResolvedDownload(
        val link: String,
        val downloadUrl: String,
        val filename: String?,
        val mimeType: String?,
        val fileSize: Long?
    )

    private data class EasyDebridBenchmarkSource(
        val label: String,
        val url: String
    )

    private data class EasyDebridBenchmarkLookupCandidate(
        val source: EasyDebridBenchmarkSource,
        val largestSizeBytes: Long
    )

    private fun <T> selectBenchmarkResolutionShortlist(
        items: List<T>,
        sizeOf: (T) -> Long?,
        timestampOf: (T) -> Long
    ): List<T> {
        val activeBand = BENCHMARK_SIZE_THRESHOLDS_BYTES
            .asSequence()
            .map { threshold ->
                items.filter { item -> (sizeOf(item) ?: 0L) >= threshold }
            }
            .firstOrNull { band -> band.isNotEmpty() }
            .orEmpty()
        return activeBand.sortedWith(
            compareByDescending<T> { item -> sizeOf(item) ?: -1L }
                .thenByDescending { item -> timestampOf(item) }
        ).take(BENCHMARK_MAX_RESOLUTION_COUNT)
    }
}
