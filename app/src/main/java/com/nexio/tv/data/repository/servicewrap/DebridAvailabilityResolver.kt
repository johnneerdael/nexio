package com.nexio.tv.data.repository.servicewrap

import com.nexio.tv.data.repository.extractFilenameFromCandidatePath
import com.nexio.tv.data.repository.isStrictPlayableVideoCandidate
import com.nexio.tv.data.local.EasyDebridSettingsDataStore
import com.nexio.tv.data.local.PremiumizeSettingsDataStore
import com.nexio.tv.data.local.TorBoxSettingsDataStore
import com.nexio.tv.data.remote.api.EasyDebridApi
import com.nexio.tv.data.remote.api.PremiumizeApi
import com.nexio.tv.data.remote.api.RealDebridApi
import com.nexio.tv.data.remote.api.TorBoxApi
import com.nexio.tv.data.remote.dto.debrid.EasyDebridGenerateDto
import com.nexio.tv.data.remote.dto.debrid.EasyDebridGenerateRequestDto
import com.nexio.tv.data.remote.dto.debrid.EasyDebridGeneratedFileDto
import com.nexio.tv.data.remote.dto.debrid.EasyDebridLookupDetailsResultDto
import com.nexio.tv.data.remote.dto.debrid.EasyDebridLookupRequestDto
import com.nexio.tv.data.remote.dto.debrid.PremiumizeDirectDownloadContentDto
import com.nexio.tv.data.remote.dto.debrid.RealDebridInstantAvailabilityFileDto
import com.nexio.tv.data.remote.dto.debrid.RealDebridMediaInfoDto
import com.nexio.tv.data.remote.dto.debrid.RealDebridTorrentFileDto
import com.nexio.tv.data.remote.dto.debrid.RealDebridTorrentInfoDto
import com.nexio.tv.data.remote.dto.debrid.TorBoxCachedTorrentDto
import com.nexio.tv.data.remote.dto.debrid.TorBoxCheckCachedRequestDto
import com.nexio.tv.data.remote.dto.debrid.TorBoxFileDto
import com.nexio.tv.data.remote.dto.debrid.TorBoxTorrentListItemDto
import com.nexio.tv.data.repository.EasyDebridService
import com.nexio.tv.data.repository.PremiumizeService
import com.nexio.tv.data.repository.RealDebridAuthService
import com.nexio.tv.data.repository.TorBoxService
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.supervisorScope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

@Singleton
class DebridAvailabilityResolver @Inject constructor(
    private val realDebridAuthService: RealDebridAuthService,
    private val realDebridApi: RealDebridApi,
    private val premiumizeApi: PremiumizeApi,
    private val premiumizeSettingsDataStore: PremiumizeSettingsDataStore,
    private val premiumizeService: PremiumizeService,
    private val torBoxApi: TorBoxApi,
    private val torBoxSettingsDataStore: TorBoxSettingsDataStore,
    private val torBoxService: TorBoxService,
    private val easyDebridApi: EasyDebridApi,
    private val easyDebridSettingsDataStore: EasyDebridSettingsDataStore,
    private val easyDebridService: EasyDebridService
) : ServiceWrapResolver {

    private val backends: List<ServiceWrapProviderBackend> = listOf(
        RealDebridBackend(),
        PremiumizeBackend(),
        TorBoxBackend(),
        EasyDebridBackend()
    )

    override suspend fun resolve(
        candidate: WrapCandidate,
        requestContext: ServiceWrapRequestContext
    ): List<ResolvedServiceWrapStream> {
        val resolved = mutableListOf<ResolvedServiceWrapStream>()
        resolveProgressively(candidate, requestContext).collect { batch ->
            resolved += batch.streams
        }
        return resolved
    }

    override fun resolveProgressively(
        candidate: WrapCandidate,
        requestContext: ServiceWrapRequestContext
    ): Flow<ServiceWrapResolutionBatch> = flow {
        val tasks = backends
            .filter { backend -> backend.isConfigured() }
        if (tasks.isEmpty()) {
            emit(ServiceWrapResolutionBatch(streams = emptyList(), isTerminal = true))
            return@flow
        }

        supervisorScope {
            val results = Channel<List<ResolvedServiceWrapStream>>(Channel.UNLIMITED)
            val jobs = tasks.map { backend ->
                async {
                    results.send(
                        runCatching {
                            backend.resolve(candidate, requestContext)
                        }.getOrDefault(emptyList())
                    )
                }
            }
            repeat(tasks.size) { index ->
                emit(
                    ServiceWrapResolutionBatch(
                        streams = results.receive(),
                        isTerminal = index == tasks.lastIndex
                    )
                )
            }
            jobs.forEach { it.await() }
            results.close()
        }
    }

    private inner class PremiumizeBackend : ServiceWrapProviderBackend {
        override val provider: ServiceWrapProvider = ServiceWrapProvider.PREMIUMIZE

        override suspend fun isConfigured(): Boolean {
            return premiumizeSettingsDataStore.settings.first().apiKey.trim().isNotBlank()
        }

        override suspend fun resolve(
            candidate: WrapCandidate,
            requestContext: ServiceWrapRequestContext
        ): List<ResolvedServiceWrapStream> {
            val apiKey = premiumizeSettingsDataStore.settings.first().apiKey.trim()
            if (apiKey.isBlank()) return emptyList()

            val cacheResponse = runCatching {
                premiumizeApi.checkCache(apiKey = apiKey, items = listOf(candidate.magnetUri))
            }.getOrNull() ?: return emptyList()
            val cacheBody = cacheResponse.body() ?: return emptyList()
            if (!cacheResponse.isSuccessful ||
                !cacheBody.status.equals("success", ignoreCase = true) ||
                cacheBody.response.firstOrNull() != true
            ) {
                return emptyList()
            }

            val directResponse = runCatching {
                premiumizeApi.createDirectDownload(apiKey = apiKey, source = candidate.magnetUri)
            }.getOrNull() ?: return emptyList()
            val directBody = directResponse.body() ?: return emptyList()
            if (!directResponse.isSuccessful || !directBody.status.equals("success", ignoreCase = true)) {
                return emptyList()
            }

            val content = directBody.content
            val selected = if (content.isNotEmpty()) {
                choosePremiumizeContent(content, candidate, requestContext)
            } else {
                PremiumizeSelection(
                    index = 0,
                    path = directBody.filename,
                    size = directBody.filesize,
                    streamUrl = directBody.location
                )
            }
            if (selected.score == Int.MIN_VALUE) return emptyList()
            val playbackUrl = selected.streamUrl?.takeIf { it.isNotBlank() } ?: return emptyList()
            return listOf(
                ResolvedServiceWrapStream(
                    provider = provider,
                    normalizedInfoHash = candidate.normalizedInfoHash,
                    playbackUrl = playbackUrl,
                    selectedFileIndex = selected.index,
                    filename = selected.path?.substringAfterLast('/'),
                    folderName = selected.path?.substringBeforeLast('/', "")?.takeIf { it.isNotBlank() },
                    sizeBytes = selected.size,
                    durationMs = null,
                    bitrate = null,
                    width = null,
                    height = null,
                    sourceLink = playbackUrl
                )
            )
        }
    }

    private inner class RealDebridBackend : ServiceWrapProviderBackend {
        override val provider: ServiceWrapProvider = ServiceWrapProvider.REAL_DEBRID

        override suspend fun isConfigured(): Boolean {
            return realDebridAuthService.getCurrentAuthState().isAuthenticated
        }

        override suspend fun resolve(
            candidate: WrapCandidate,
            requestContext: ServiceWrapRequestContext
        ): List<ResolvedServiceWrapStream> {
            val availabilityResponse = realDebridAuthService.executeAuthorizedRequest { authorization ->
                realDebridApi.getInstantAvailability(
                    authorization = authorization,
                    hash = candidate.normalizedInfoHash
                )
            } ?: return emptyList()
            val availabilityBody = availabilityResponse.body() ?: return emptyList()
            if (!availabilityResponse.isSuccessful) return emptyList()

            val variants = extractRealDebridVariants(
                availabilityBody = availabilityBody,
                normalizedInfoHash = candidate.normalizedInfoHash
            )
            if (variants.isEmpty()) return emptyList()

            val selectedVariant = chooseRealDebridVariant(variants, candidate, requestContext) ?: return emptyList()

            var torrentId: String? = null
            try {
                val addMagnetResponse = realDebridAuthService.executeAuthorizedRequest { authorization ->
                    realDebridApi.addMagnet(authorization = authorization, magnet = candidate.magnetUri)
                } ?: return emptyList()
                val magnetBody = addMagnetResponse.body() ?: return emptyList()
                if (!addMagnetResponse.isSuccessful) return emptyList()
                torrentId = magnetBody.id

                val selectedIds = selectedVariant.fileIds.joinToString(",")
                val selectResponse = realDebridAuthService.executeAuthorizedRequest { authorization ->
                    realDebridApi.selectFiles(
                        authorization = authorization,
                        id = torrentId,
                        files = selectedIds
                    )
                } ?: return emptyList()
                if (!selectResponse.isSuccessful) return emptyList()

                val torrentInfo = pollRealDebridTorrentInfo(torrentId) ?: return emptyList()
                val resolvedFile = resolveRealDebridTorrentFile(torrentInfo, selectedVariant.targetFileId) ?: return emptyList()
                val unrestrictResponse = realDebridAuthService.executeAuthorizedRequest { authorization ->
                    realDebridApi.unrestrictLink(
                        authorization = authorization,
                        link = resolvedFile.sourceLink
                    )
                } ?: return emptyList()
                val unrestrictBody = unrestrictResponse.body() ?: return emptyList()
                if (!unrestrictResponse.isSuccessful) return emptyList()
                val playbackUrl = unrestrictBody.download?.takeIf { it.isNotBlank() } ?: return emptyList()
                val mediaInfo = if (!unrestrictBody.id.isNullOrBlank()) {
                    fetchRealDebridMediaInfo(unrestrictBody.id)
                } else {
                    null
                }

                val filename = listOfNotNull(
                    mediaInfo?.filename,
                    unrestrictBody.filename,
                    resolvedFile.file.path?.substringAfterLast('/'),
                    candidate.sourceParsed.filename
                ).firstOrNull { it.isNotBlank() }
                val folderName = resolvedFile.file.path
                    ?.substringBeforeLast('/', "")
                    ?.trimStart('/')
                    ?.takeIf { it.isNotBlank() }

                return listOf(
                    ResolvedServiceWrapStream(
                        provider = provider,
                        normalizedInfoHash = candidate.normalizedInfoHash,
                        playbackUrl = playbackUrl,
                        selectedFileIndex = resolvedFile.selectedIndex,
                        filename = filename,
                        folderName = folderName,
                        sizeBytes = mediaInfo?.size ?: resolvedFile.file.bytes ?: unrestrictBody.fileSize,
                        durationMs = mediaInfo?.duration?.times(1000.0)?.toLong(),
                        bitrate = mediaInfo?.bitrate,
                        width = mediaInfo?.details?.video?.values?.firstNotNullOfOrNull { it.width },
                        height = mediaInfo?.details?.video?.values?.firstNotNullOfOrNull { it.height },
                        sourceLink = resolvedFile.sourceLink
                    )
                )
            } finally {
                torrentId?.let { id ->
                    realDebridAuthService.executeAuthorizedRequest { authorization ->
                        realDebridApi.deleteTorrent(authorization = authorization, id = id)
                    }
                }
            }
        }
    }

    private inner class TorBoxBackend : ServiceWrapProviderBackend {
        override val provider: ServiceWrapProvider = ServiceWrapProvider.TORBOX

        override suspend fun isConfigured(): Boolean {
            return torBoxSettingsDataStore.settings.first().apiKey.trim().isNotBlank()
        }

        override suspend fun resolve(
            candidate: WrapCandidate,
            requestContext: ServiceWrapRequestContext
        ): List<ResolvedServiceWrapStream> {
            val apiKey = torBoxSettingsDataStore.settings.first().apiKey.trim()
            if (apiKey.isBlank()) return emptyList()

            val cacheResponse = runCatching {
                torBoxApi.checkCachedTorrents(
                    authorization = "Bearer $apiKey",
                    body = TorBoxCheckCachedRequestDto(hashes = listOf(candidate.normalizedInfoHash))
                )
            }.getOrNull() ?: return emptyList()
            val cacheBody = cacheResponse.body() ?: return emptyList()
            if (!cacheResponse.isSuccessful || cacheBody.success == false) {
                return emptyList()
            }

            val cachedTorrent = cacheBody.data
                ?.firstOrNull { it.hash.equals(candidate.normalizedInfoHash, ignoreCase = true) }
                ?: cacheBody.data?.firstOrNull()
                ?: return emptyList()
            val cachedSelection = chooseTorBoxFile(cachedTorrent, candidate, requestContext) ?: return emptyList()

            val createResponse = runCatching {
                torBoxApi.createTorrent(
                    authorization = "Bearer $apiKey",
                    magnet = candidate.magnetUri.toPlainTextBody(),
                    allowZip = "false".toPlainTextBody(),
                    addOnlyIfCached = "true".toPlainTextBody()
                )
            }.getOrNull() ?: return emptyList()
            val createBody = createResponse.body() ?: return emptyList()
            if (!createResponse.isSuccessful || createBody.success == false) {
                return emptyList()
            }

            val torrentId = createBody.data?.resolvedTorrentId() ?: return emptyList()
            val torrentItem = pollTorBoxTorrent(apiKey, torrentId) ?: return emptyList()
            val selectedFile = chooseTorBoxFileFromItem(torrentItem, candidate, requestContext, cachedSelection.file.id)
                ?: cachedSelection

            val requestLinkResponse = runCatching {
                torBoxApi.requestDownloadLink(
                    token = apiKey,
                    torrentId = torrentId,
                    fileId = selectedFile.file.id
                )
            }.getOrNull() ?: return emptyList()
            val requestLinkBody = requestLinkResponse.body() ?: return emptyList()
            val playbackUrl = requestLinkBody.data?.takeIf { requestLinkResponse.isSuccessful && requestLinkBody.success != false && it.isNotBlank() }
                ?: return emptyList()

            val path = selectedFile.path
            return listOf(
                ResolvedServiceWrapStream(
                    provider = provider,
                    normalizedInfoHash = candidate.normalizedInfoHash,
                    playbackUrl = playbackUrl,
                    selectedFileIndex = selectedFile.index,
                    filename = selectedFile.filename,
                    folderName = path?.substringBeforeLast('/', "")?.takeIf { it.isNotBlank() },
                    sizeBytes = selectedFile.file.size ?: cachedTorrent.size,
                    durationMs = null,
                    bitrate = null,
                    width = null,
                    height = null,
                    sourceLink = playbackUrl
                )
            )
        }
    }

    private inner class EasyDebridBackend : ServiceWrapProviderBackend {
        override val provider: ServiceWrapProvider = ServiceWrapProvider.EASY_DEBRID

        override suspend fun isConfigured(): Boolean {
            return easyDebridSettingsDataStore.settings.first().apiKey.trim().isNotBlank()
        }

        override suspend fun resolve(
            candidate: WrapCandidate,
            requestContext: ServiceWrapRequestContext
        ): List<ResolvedServiceWrapStream> {
            val apiKey = easyDebridSettingsDataStore.settings.first().apiKey.trim()
            if (apiKey.isBlank()) return emptyList()

            val authorization = "Bearer $apiKey"
            val lookupBody = runCatching {
                easyDebridApi.lookupDetails(
                    authorization = authorization,
                    body = EasyDebridLookupRequestDto(urls = listOf(candidate.magnetUri))
                )
            }.getOrNull()?.body() ?: return emptyList()

            val lookupResult = lookupBody.result.firstOrNull()?.takeIf { it.cached } ?: return emptyList()

            val generateBody = runCatching {
                easyDebridApi.generate(
                    authorization = authorization,
                    body = EasyDebridGenerateRequestDto(url = candidate.magnetUri)
                )
            }.getOrNull()?.body() ?: return emptyList()

            val selectedFile = chooseEasyDebridFile(generateBody, lookupResult, candidate, requestContext)
                ?: return emptyList()
            val playbackUrl = selectedFile.file.url?.takeIf { it.isNotBlank() } ?: return emptyList()
            val directory = selectedFile.file.directory.joinToString("/").takeIf { it.isNotBlank() }
            return listOf(
                ResolvedServiceWrapStream(
                    provider = provider,
                    normalizedInfoHash = candidate.normalizedInfoHash,
                    playbackUrl = playbackUrl,
                    selectedFileIndex = selectedFile.index,
                    filename = selectedFile.file.filename,
                    folderName = directory,
                    sizeBytes = selectedFile.file.size,
                    durationMs = null,
                    bitrate = null,
                    width = null,
                    height = null,
                    sourceLink = playbackUrl
                )
            )
        }
    }

    private suspend fun pollRealDebridTorrentInfo(torrentId: String): RealDebridTorrentInfoDto? {
        repeat(20) { attempt ->
            val infoResponse = realDebridAuthService.executeAuthorizedRequest { authorization ->
                realDebridApi.getTorrentInfo(authorization = authorization, id = torrentId)
            } ?: return null
            val body = infoResponse.body()
            if (infoResponse.isSuccessful && body != null) {
                if (body.links.isNotEmpty() && body.files.any { it.selected == 1 }) {
                    return body
                }
                if (body.status.equals("downloaded", ignoreCase = true) && body.links.isNotEmpty()) {
                    return body
                }
                if (body.status.equals("error", ignoreCase = true) || body.status.equals("dead", ignoreCase = true)) {
                    return null
                }
            }
            if (attempt < 19) {
                delay(300L)
            }
        }
        return null
    }

    private suspend fun pollTorBoxTorrent(apiKey: String, torrentId: Int): TorBoxTorrentListItemDto? {
        repeat(20) { attempt ->
            val response = runCatching {
                torBoxApi.getMyTorrentList(
                    authorization = "Bearer $apiKey",
                    id = torrentId,
                    bypassCache = true
                )
            }.getOrNull() ?: return null
            val body = response.body()
            val item = body?.data?.firstOrNull { it.id == torrentId } ?: body?.data?.firstOrNull()
            if (response.isSuccessful && body?.success != false && item != null) {
                if (item.isDownloaded() || item.resolvedState().equals("downloaded", ignoreCase = true)) {
                    return item
                }
                val state = item.resolvedState().orEmpty()
                if (state.equals("error", ignoreCase = true) || state.equals("failed", ignoreCase = true)) {
                    return null
                }
            }
            if (attempt < 19) {
                delay(300L)
            }
        }
        return null
    }

    private suspend fun fetchRealDebridMediaInfo(downloadId: String): RealDebridMediaInfoDto? {
        val response = realDebridAuthService.executeAuthorizedRequest { authorization ->
            realDebridApi.getMediaInfos(authorization = authorization, id = downloadId)
        } ?: return null
        if (!response.isSuccessful) return null
        return response.body()
    }

    private fun choosePremiumizeContent(
        content: List<PremiumizeDirectDownloadContentDto>,
        candidate: WrapCandidate,
        requestContext: ServiceWrapRequestContext
    ): PremiumizeSelection {
        return content.mapIndexed { index, item ->
            val path = item.path?.trim().takeUnless { it.isNullOrBlank() }
            val score = scoreSecureWrapCandidateFile(
                filename = path?.substringAfterLast('/'),
                fullPath = path,
                sizeBytes = item.size,
                candidate = candidate,
                requestContext = requestContext
            ) ?: return@mapIndexed null
            PremiumizeSelection(
                index = index,
                path = path,
                size = item.size,
                streamUrl = item.streamLink ?: item.link,
                score = score
            )
        }.filterNotNull().maxWithOrNull(
            compareBy<PremiumizeSelection> { it.score }
                .thenBy { it.size ?: 0L }
        ) ?: PremiumizeSelection(index = 0, path = null, size = null, streamUrl = null)
    }

    private fun chooseRealDebridVariant(
        variants: List<Map<String, RealDebridInstantAvailabilityFileDto>>,
        candidate: WrapCandidate,
        requestContext: ServiceWrapRequestContext
    ): RealDebridVariantSelection? {
        return variants.mapNotNull { variant ->
            val fileSelections = variant.entries.mapNotNull { entry ->
                val fileId = entry.key.toIntOrNull() ?: return@mapNotNull null
                val score = scoreSecureWrapCandidateFile(
                    filename = entry.value.filename,
                    fullPath = entry.value.filename,
                    sizeBytes = entry.value.filesize,
                    candidate = candidate,
                    requestContext = requestContext
                ) ?: return@mapNotNull null
                RealDebridVariantFileScore(
                    fileId = fileId,
                    filename = entry.value.filename,
                    sizeBytes = entry.value.filesize,
                    score = score
                )
            }
            if (fileSelections.isEmpty()) {
                null
            } else {
                val target = fileSelections.maxWithOrNull(
                    compareBy<RealDebridVariantFileScore> { it.score }
                        .thenBy { it.sizeBytes ?: 0L }
                ) ?: return@mapNotNull null
                RealDebridVariantSelection(
                    fileIds = fileSelections.map { it.fileId },
                    targetFileId = target.fileId,
                    targetFilename = target.filename
                )
            }
        }.maxByOrNull { selection ->
            scoreSecureWrapCandidateFile(
                filename = selection.targetFilename,
                fullPath = selection.targetFilename,
                sizeBytes = null,
                candidate = candidate,
                requestContext = requestContext
            ) ?: Int.MIN_VALUE
        }
    }

    private fun resolveRealDebridTorrentFile(
        torrentInfo: RealDebridTorrentInfoDto,
        targetFileId: Int
    ): RealDebridResolvedFile? {
        val selectedFiles = torrentInfo.files.filter { it.selected == 1 }
        val targetFile = selectedFiles.firstOrNull { it.id == targetFileId }
            ?: torrentInfo.files.firstOrNull { it.id == targetFileId }
            ?: return null
        val selectedIndex = max(0, selectedFiles.indexOfFirst { it.id == targetFile.id })
        val sourceLink = torrentInfo.links.getOrNull(selectedIndex)
            ?: torrentInfo.links.firstOrNull()
            ?: return null
        return RealDebridResolvedFile(
            file = targetFile,
            selectedIndex = selectedIndex,
            sourceLink = sourceLink
        )
    }

    private fun chooseTorBoxFile(
        torrent: TorBoxCachedTorrentDto,
        candidate: WrapCandidate,
        requestContext: ServiceWrapRequestContext
    ): TorBoxSelection? {
        return torrent.files.mapIndexedNotNull { index, file ->
            val filename = file.shortName?.takeIf { it.isNotBlank() } ?: file.name?.takeIf { it.isNotBlank() }
            if (filename == null) return@mapIndexedNotNull null
            val score = scoreSecureWrapCandidateFile(
                filename = filename,
                fullPath = filename,
                sizeBytes = file.size,
                candidate = candidate,
                requestContext = requestContext
            ) ?: return@mapIndexedNotNull null
            TorBoxSelection(
                index = index,
                file = file,
                filename = filename,
                path = filename,
                score = score
            )
        }.maxWithOrNull(compareBy<TorBoxSelection> { it.score }.thenBy { it.file.size ?: 0L })
    }

    private fun chooseTorBoxFileFromItem(
        torrent: TorBoxTorrentListItemDto,
        candidate: WrapCandidate,
        requestContext: ServiceWrapRequestContext,
        preferredFileId: Int?
    ): TorBoxSelection? {
        val candidateSelections = torrent.files.mapIndexedNotNull { index, file ->
            val filename = file.shortName?.takeIf { it.isNotBlank() } ?: file.name?.takeIf { it.isNotBlank() }
            if (filename == null) return@mapIndexedNotNull null
            var score = scoreSecureWrapCandidateFile(
                filename = filename,
                fullPath = filename,
                sizeBytes = file.size,
                candidate = candidate,
                requestContext = requestContext
            ) ?: return@mapIndexedNotNull null
            if (preferredFileId != null && preferredFileId == file.id) {
                score += 250
            }
            TorBoxSelection(
                index = index,
                file = file,
                filename = filename,
                path = filename,
                score = score
            )
        }
        return candidateSelections.maxWithOrNull(compareBy<TorBoxSelection> { it.score }.thenBy { it.file.size ?: 0L })
    }

    private fun chooseEasyDebridFile(
        generate: EasyDebridGenerateDto,
        lookup: EasyDebridLookupDetailsResultDto,
        candidate: WrapCandidate,
        requestContext: ServiceWrapRequestContext
    ): EasyDebridSelection? {
        val lookupFilesByName = lookup.files.associateBy { normalizeLookupKey(it.name, it.folder) }
        return generate.files.mapIndexedNotNull { index, file ->
            val filename = file.filename?.trim()?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
            val path = buildEasyDebridPath(file)
            var score = scoreSecureWrapCandidateFile(
                filename = filename,
                fullPath = path,
                sizeBytes = file.size,
                candidate = candidate,
                requestContext = requestContext
            ) ?: return@mapIndexedNotNull null
            val lookupMatch = lookupFilesByName[normalizeLookupKey(filename, file.directory.joinToString("/"))]
            if (lookupMatch != null) {
                score += 120
            }
            EasyDebridSelection(
                index = index,
                file = file,
                score = score
            )
        }.maxWithOrNull(compareBy<EasyDebridSelection> { it.score }.thenBy { it.file.size ?: 0L })
    }

    private fun buildEasyDebridPath(file: EasyDebridGeneratedFileDto): String {
        val directory = file.directory.joinToString("/").trim('/')
        return if (directory.isBlank()) {
            file.filename.orEmpty()
        } else {
            "$directory/${file.filename.orEmpty()}"
        }
    }

    private fun normalizeLookupKey(name: String?, folder: String?): String? {
        val filename = name?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val path = buildString {
            folder?.trim('/')?.takeIf { it.isNotBlank() }?.let {
                append(it)
                append('/')
            }
            append(filename)
        }
        return normalizeMatchKey(path)
    }

    private fun normalizeMatchKey(value: String?): String? {
        return value
            ?.lowercase(Locale.US)
            ?.replace(Regex("""[^\p{L}\p{N}]+"""), "")
            ?.takeIf { it.isNotBlank() }
    }

    private fun String.toPlainTextBody() = toRequestBody("text/plain".toMediaType())

    private data class PremiumizeSelection(
        val index: Int,
        val path: String?,
        val size: Long?,
        val streamUrl: String?,
        val score: Int = Int.MIN_VALUE
    )

    private data class RealDebridVariantSelection(
        val fileIds: List<Int>,
        val targetFileId: Int,
        val targetFilename: String?
    )

    private data class RealDebridVariantFileScore(
        val fileId: Int,
        val filename: String?,
        val sizeBytes: Long?,
        val score: Int
    )

    private data class RealDebridResolvedFile(
        val file: RealDebridTorrentFileDto,
        val selectedIndex: Int,
        val sourceLink: String
    )

    private data class TorBoxSelection(
        val index: Int,
        val file: TorBoxFileDto,
        val filename: String,
        val path: String?,
        val score: Int
    )

    private data class EasyDebridSelection(
        val index: Int,
        val file: EasyDebridGeneratedFileDto,
        val score: Int
    )
}

internal fun scoreSecureWrapCandidateFile(
    filename: String?,
    fullPath: String?,
    sizeBytes: Long?,
    candidate: WrapCandidate,
    requestContext: ServiceWrapRequestContext
): Int? {
    val normalizedPath = fullPath ?: filename
    val normalizedFilename = filename ?: extractFilenameFromCandidatePath(normalizedPath)
    if (!isStrictPlayableVideoCandidate(filename = normalizedFilename, fullPath = normalizedPath)) {
        return null
    }

    val parsed = parseFileCandidate(normalizedPath ?: normalizedFilename)
    var score = 1_000

    val requestedSeason = requestContext.season ?: candidate.sourceParsed.seasons.firstOrNull()
    val requestedEpisode = requestContext.episode ?: candidate.sourceParsed.episodes.firstOrNull()
    val parsedFile = parsed?.parsed

    if (requestedSeason != null) {
        when {
            parsedFile?.seasons?.contains(requestedSeason) == true -> score += 350
            !parsedFile?.seasons.isNullOrEmpty() -> score -= 250
        }
    }
    if (requestedEpisode != null) {
        when {
            parsedFile?.episodes?.contains(requestedEpisode) == true -> score += 450
            !parsedFile?.episodes.isNullOrEmpty() -> score -= 300
        }
    }

    val sourceTitleKey = normalizeSecureWrapMatchKey(candidate.sourceParsed.title)
    val parsedTitleKey = normalizeSecureWrapMatchKey(parsedFile?.title)
    if (sourceTitleKey != null && parsedTitleKey != null) {
        score += if (sourceTitleKey == parsedTitleKey) 180 else -40
    }

    val sourceYear = candidate.sourceParsed.year
    val parsedYear = parsedFile?.year
    if (sourceYear != null && parsedYear != null) {
        score += if (sourceYear == parsedYear) 90 else -80
    }

    val sourceResolution = candidate.sourceParsed.resolution
    if (sourceResolution != null && parsedFile?.resolution == sourceResolution) {
        score += 40
    }
    val sourceQuality = candidate.sourceParsed.quality
    if (sourceQuality != null && parsedFile?.quality == sourceQuality) {
        score += 25
    }

    score += ((sizeBytes ?: 0L) / (1024L * 1024L * 1024L)).coerceAtMost(120L).toInt()
    return score
}

private fun normalizeSecureWrapMatchKey(value: String?): String? {
    return value
        ?.lowercase(Locale.US)
        ?.replace(Regex("""[^\p{L}\p{N}]+"""), "")
        ?.takeIf { it.isNotBlank() }
}

internal fun extractRealDebridVariants(
    availabilityBody: Map<String, Map<String, List<Map<String, RealDebridInstantAvailabilityFileDto>>>>,
    normalizedInfoHash: String
): List<Map<String, RealDebridInstantAvailabilityFileDto>> {
    val providerMap = availabilityBody[normalizedInfoHash]
        ?: availabilityBody[normalizedInfoHash.lowercase(Locale.US)]
        ?: availabilityBody[normalizedInfoHash.uppercase(Locale.US)]
        ?: availabilityBody.entries.firstOrNull { (hash, _) ->
            hash.equals(normalizedInfoHash, ignoreCase = true)
        }?.value
        ?: return emptyList()

    return providerMap["rd"]
        ?: providerMap["RD"]
        ?: providerMap.entries.firstOrNull { (providerId, _) ->
            providerId.equals("rd", ignoreCase = true)
        }?.value
        .orEmpty()
}
