package com.nexio.tv.data.repository

import com.nexio.tv.data.local.RealDebridAuthDataStore
import com.nexio.tv.data.remote.api.EasyDebridApi
import com.nexio.tv.data.remote.api.PremiumizeApi
import com.nexio.tv.data.remote.api.RealDebridApi
import com.nexio.tv.data.remote.api.TorBoxApi
import com.nexio.tv.data.remote.dto.debrid.EasyDebridGenerateDto
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
    private val realDebridApi: RealDebridApi,
    private val realDebridAuthDataStore: RealDebridAuthDataStore,
    private val realDebridAuthService: RealDebridAuthService,
    private val premiumizeApi: PremiumizeApi,
    private val premiumizeService: PremiumizeService,
    private val torBoxApi: TorBoxApi,
    private val torBoxService: TorBoxService,
    private val easyDebridApi: EasyDebridApi,
    private val easyDebridService: EasyDebridService
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
                premiumizeService.refreshAccountState()
                val premiumizeState = premiumizeService.observeAccountState().first()
                val apiKey = premiumizeState.apiKey.trim()
                if (!premiumizeState.isConnected || apiKey.isBlank()) {
                    DebridBenchmarkCandidateLookupResult.NoPlayableLibraryItem
                } else {
                    fetchPremiumizeBenchmarkCandidates(apiKey)
                }
            }
            DebridBenchmarkProvider.TORBOX -> {
                torBoxService.refreshAccountState()
                val torBoxState = torBoxService.observeAccountState().first()
                val apiKey = torBoxState.apiKey.trim()
                if (!torBoxState.isConnected || apiKey.isBlank()) {
                    DebridBenchmarkCandidateLookupResult.NoPlayableLibraryItem
                } else {
                    fetchTorBoxBenchmarkCandidates(apiKey)
                }
            }
            DebridBenchmarkProvider.EASY_DEBRID -> {
                easyDebridService.refreshAccountState()
                val easyDebridState = easyDebridService.observeAccountState().first()
                val apiKey = easyDebridState.apiKey.trim()
                if (!easyDebridState.isConnected || apiKey.isBlank()) {
                    DebridBenchmarkCandidateLookupResult.NoPlayableLibraryItem
                } else {
                    fetchEasyDebridBenchmarkCandidates(apiKey)
                }
            }
        }
    }

    private suspend fun fetchRealDebridBenchmarkCandidates(): DebridBenchmarkCandidateLookupResult = withContext(Dispatchers.IO) {
        val playbackHeaders = buildRealDebridPlaybackHeaders()
        val torrentsResponse = realDebridAuthService.executeAuthorizedRequest { authHeader ->
            realDebridApi.getTorrents(authorization = authHeader)
        } ?: return@withContext DebridBenchmarkCandidateLookupResult.NoPlayableLibraryItem
        val downloadsResponse = realDebridAuthService.executeAuthorizedRequest { authHeader ->
            realDebridApi.getDownloads(authorization = authHeader)
        } ?: return@withContext DebridBenchmarkCandidateLookupResult.NoPlayableLibraryItem

        if (!torrentsResponse.isSuccessful || !downloadsResponse.isSuccessful) {
            return@withContext DebridBenchmarkCandidateLookupResult.NoPlayableLibraryItem
        }

        val torrents = torrentsResponse.body().orEmpty()
            .filter { it.status.equals("downloaded", ignoreCase = true) }
        val previews = resolveRealDebridBenchmarkPreviews(torrents)
        val shortlistedPreviews = selectBenchmarkResolutionShortlist(
            items = previews,
            sizeOf = { preview: RealDebridBenchmarkPreview -> preview.file.bytes },
            timestampOf = { preview: RealDebridBenchmarkPreview -> preview.listedAtMs }
        )
        if (shortlistedPreviews.isEmpty()) {
            return@withContext DebridBenchmarkCandidateLookupResult.NoLargeDownload
        }

        val resolvedDownloadsByLink = downloadsResponse.body().orEmpty()
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
        val response = runCatching { premiumizeApi.listAllItems(apiKey) }.getOrNull()
            ?: return@withContext DebridBenchmarkCandidateLookupResult.NoPlayableLibraryItem
        if (!response.isSuccessful) return@withContext DebridBenchmarkCandidateLookupResult.NoPlayableLibraryItem

        val shortlistedFiles = selectBenchmarkResolutionShortlist(
            items = response.body()?.files.orEmpty()
                .filter(::isLikelyPlayable),
            sizeOf = { file: PremiumizeListAllFileDto -> file.size },
            timestampOf = { file: PremiumizeListAllFileDto -> (file.createdAt ?: 0L) * 1000L }
        )
        if (shortlistedFiles.isEmpty()) {
            return@withContext DebridBenchmarkCandidateLookupResult.NoLargeDownload
        }

        val candidates = shortlistedFiles.mapNotNull { file ->
            val detailsResponse = runCatching {
                premiumizeApi.getItemDetails(apiKey = apiKey, id = file.id)
            }.getOrNull() ?: return@mapNotNull null
            val details = detailsResponse.body() ?: return@mapNotNull null
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

        val candidates = shortlistedEntries.mapNotNull {
            it.toBenchmarkCandidate(DebridBenchmarkProvider.TORBOX)
        }
        if (candidates.isEmpty()) {
            DebridBenchmarkCandidateLookupResult.NoPlayableLibraryItem
        } else {
            DebridBenchmarkCandidateLookupResult.Candidates(candidates)
        }
    }

    private suspend fun fetchEasyDebridBenchmarkCandidates(apiKey: String): DebridBenchmarkCandidateLookupResult = withContext(Dispatchers.IO) {
        val authorization = "Bearer $apiKey"
        val sources = EASY_DEBRID_BENCHMARK_SOURCES
        val lookupResponse = runCatching {
            easyDebridApi.lookupDetails(
                authorization = authorization,
                body = EasyDebridLookupRequestDto(urls = sources.map { it.url })
            )
        }.getOrNull() ?: return@withContext DebridBenchmarkCandidateLookupResult.NoPlayableLibraryItem
        val lookupBody = lookupResponse.body() ?: return@withContext DebridBenchmarkCandidateLookupResult.NoPlayableLibraryItem
        if (!lookupResponse.isSuccessful) {
            return@withContext DebridBenchmarkCandidateLookupResult.NoPlayableLibraryItem
        }

        val lookupCandidates = sources.mapIndexedNotNull { index, source ->
            val result = lookupBody.result.getOrNull(index) ?: return@mapIndexedNotNull null
            if (!result.cached) return@mapIndexedNotNull null
            val largestFile = result.files
                .filter { file ->
                    !isLikelySampleFile(file.name, file.folder) &&
                        isLikelyVideo(file.name, null)
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
            val generateResponse = runCatching {
                easyDebridApi.generate(
                    authorization = authorization,
                    body = com.nexio.tv.data.remote.dto.debrid.EasyDebridGenerateRequestDto(url = candidate.source.url)
                )
            }.getOrNull() ?: return@mapNotNull null
            val generateBody = generateResponse.body() ?: return@mapNotNull null
            if (!generateResponse.isSuccessful) return@mapNotNull null

            val selectedFile = generateBody.files
                .filter { file ->
                    !isLikelySampleFile(file.filename, file.directory.joinToString("/")) &&
                        isLikelyVideo(file.filename, null)
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
            premiumizeService.observeAccountState(),
            torBoxService.observeAccountState(),
            easyDebridService.observeAccountState()
        ) { rdAuthenticated, pmState, torBoxState, easyDebridState ->
            rdAuthenticated ||
                pmState.isConnected ||
                pmState.apiKey.isNotBlank() ||
                torBoxState.isConnected ||
                torBoxState.apiKey.isNotBlank() ||
                easyDebridState.isConnected ||
                easyDebridState.apiKey.isNotBlank()
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

            if (refreshTorBox) {
                torBoxService.refreshAccountState()
                val torBoxState = torBoxService.observeAccountState().first()
                if (torBoxState.apiKey.isNotBlank()) {
                    val torBoxItems = fetchTorBoxItems(torBoxState.apiKey)
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

    private suspend fun resolveRealDebridBenchmarkPreviews(
        torrents: List<RealDebridTorrentDto>
    ): List<RealDebridBenchmarkPreview> = coroutineScope {
        val infoSemaphore = Semaphore(6)
        torrents.map { torrent ->
            async {
                infoSemaphore.withPermit {
                    val infoResponse = realDebridAuthService.executeAuthorizedRequest { authHeader ->
                        realDebridApi.getTorrentInfo(authorization = authHeader, id = torrent.id)
                    } ?: return@withPermit null
                    if (!infoResponse.isSuccessful) return@withPermit null

                    val info = infoResponse.body() ?: return@withPermit null
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

    private suspend fun fetchTorBoxItems(apiKey: String): List<LibraryEntry> = withContext(Dispatchers.IO) {
        val response = runCatching {
            torBoxApi.getMyTorrentList(
                authorization = "Bearer $apiKey",
                bypassCache = true,
                limit = 100
            )
        }.getOrNull() ?: return@withContext emptyList()
        if (!response.isSuccessful) return@withContext emptyList()

        val candidates = response.body()?.data.orEmpty()
            .filter { it.id != null && it.files.isNotEmpty() }
            .filter { it.isDownloaded() || it.resolvedState().equals("downloaded", ignoreCase = true) }
            .take(100)

        val detailsSemaphore = Semaphore(6)
        coroutineScope {
            candidates.flatMap { torrent ->
                torrent.files.map { file -> torrent to file }
            }.map { (torrent, file) ->
                async {
                    detailsSemaphore.withPermit {
                        if (!isLikelyPlayable(file) || isLikelySampleFile(file)) {
                            return@withPermit null
                        }
                        val directUrl = requestTorBoxDownload(
                            apiKey = apiKey,
                            torrentId = torrent.id ?: return@withPermit null,
                            fileId = file.id ?: return@withPermit null
                        ) ?: return@withPermit null
                        mapTorBoxItem(torrent, file, directUrl)
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
        directUrl: String
    ): LibraryEntry {
        val filename = file.shortName
            ?.takeIf { it.isNotBlank() }
            ?: file.name
            ?.takeIf { it.isNotBlank() }
            ?: torrent.name.orEmpty().ifBlank { "TorBox File" }
        return LibraryEntry(
            id = "tb:torrent:${torrent.id}:file:${file.id}",
            type = inferContentType(filename, file.mimeType),
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
            directPlaybackUrl = directUrl,
            playbackStreamName = filename,
            playbackFilename = filename,
            playbackSizeBytes = file.size
        )
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

    private fun isLikelyPlayable(file: TorBoxFileDto): Boolean {
        return isLikelyVideo(file.shortName ?: file.name, file.mimeType)
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

    private fun isLikelySampleFile(file: TorBoxFileDto): Boolean {
        val normalizedName = (file.name ?: file.shortName).orEmpty().trim().lowercase()
        if (normalizedName.isBlank()) return false
        val segments = normalizedName.split('/').filter { it.isNotBlank() }
        if (segments.any { it == "sample" || it == "samples" }) return true
        val filename = extractFilenameFromPath(normalizedName).orEmpty()
        return filename.startsWith("sample.") ||
            filename.startsWith("sample-") ||
            filename.contains(".sample.")
    }

    private fun isLikelySampleFile(filename: String?, folder: String?): Boolean {
        val normalizedPath = listOfNotNull(folder, filename)
            .joinToString("/")
            .trim()
            .lowercase()
        if (normalizedPath.isBlank()) return false
        val segments = normalizedPath.split('/').filter { it.isNotBlank() }
        if (segments.any { it == "sample" || it == "samples" }) return true
        val extractedFilename = extractFilenameFromPath(normalizedPath).orEmpty()
        return extractedFilename.startsWith("sample.") ||
            extractedFilename.startsWith("sample-") ||
            extractedFilename.contains(".sample.")
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

    private suspend fun requestTorBoxDownload(
        apiKey: String,
        torrentId: Int,
        fileId: Int
    ): String? {
        val response = runCatching {
            torBoxApi.requestDownloadLink(
                token = apiKey,
                torrentId = torrentId,
                fileId = fileId,
                zipLink = false,
                redirect = false
            )
        }.getOrNull() ?: return null
        if (!response.isSuccessful) return null
        val body = response.body()
        if (body?.success == false) return null
        return body?.data?.takeIf { it.isNotBlank() }
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
        private const val BENCHMARK_SIZE_GIB = 1024L * 1024L * 1024L
        private const val BENCHMARK_MAX_RESOLUTION_COUNT = 2
        private const val EASY_DEBRID_MIN_BENCHMARK_BYTES = 1L * BENCHMARK_SIZE_GIB
        private val BENCHMARK_SIZE_THRESHOLDS_BYTES = listOf(
            20L * BENCHMARK_SIZE_GIB,
            15L * BENCHMARK_SIZE_GIB,
            10L * BENCHMARK_SIZE_GIB,
            5L * BENCHMARK_SIZE_GIB
        )

        private val VIDEO_EXTENSIONS = listOf(
            ".mkv", ".mp4", ".avi", ".mov", ".wmv", ".ts", ".m2ts", ".webm", ".mpg", ".mpeg"
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
