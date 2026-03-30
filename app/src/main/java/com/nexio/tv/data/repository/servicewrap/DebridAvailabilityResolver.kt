package com.nexio.tv.data.repository.servicewrap

import com.nexio.tv.data.local.PremiumizeSettingsDataStore
import com.nexio.tv.data.remote.api.PremiumizeApi
import com.nexio.tv.data.remote.api.RealDebridApi
import com.nexio.tv.data.remote.dto.debrid.PremiumizeDirectDownloadContentDto
import com.nexio.tv.data.remote.dto.debrid.RealDebridInstantAvailabilityFileDto
import com.nexio.tv.data.remote.dto.debrid.RealDebridMediaInfoDto
import com.nexio.tv.data.remote.dto.debrid.RealDebridTorrentFileDto
import com.nexio.tv.data.remote.dto.debrid.RealDebridTorrentInfoDto
import com.nexio.tv.data.repository.RealDebridAuthService
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.supervisorScope
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

@Singleton
class DebridAvailabilityResolver @Inject constructor(
    private val realDebridAuthService: RealDebridAuthService,
    private val realDebridApi: RealDebridApi,
    private val premiumizeApi: PremiumizeApi,
    private val premiumizeSettingsDataStore: PremiumizeSettingsDataStore
) : ServiceWrapResolver {

    override suspend fun resolve(
        candidate: WrapCandidate,
        requestContext: ServiceWrapRequestContext
    ): List<ResolvedServiceWrapStream> = supervisorScope {
        val tasks = buildList {
            if (realDebridAuthService.getCurrentAuthState().isAuthenticated) {
                add(async { resolveRealDebrid(candidate, requestContext) })
            }
            val premiumizeApiKey = premiumizeSettingsDataStore.settings.first().apiKey.trim()
            if (premiumizeApiKey.isNotBlank()) {
                add(async { resolvePremiumize(candidate, requestContext, premiumizeApiKey) })
            }
        }
        tasks.flatMap { task -> task.await() }
    }

    private suspend fun resolvePremiumize(
        candidate: WrapCandidate,
        requestContext: ServiceWrapRequestContext,
        apiKey: String
    ): List<ResolvedServiceWrapStream> {
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
        val playbackUrl = selected.streamUrl?.takeIf { it.isNotBlank() } ?: return emptyList()
        return listOf(
            ResolvedServiceWrapStream(
                provider = ServiceWrapProvider.PREMIUMIZE,
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

    private suspend fun resolveRealDebrid(
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

        val variants = availabilityBody[candidate.normalizedInfoHash]
            ?.get("rd")
            .orEmpty()
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

            val torrentInfo = pollTorrentInfo(torrentId) ?: return emptyList()
            val resolvedFile = resolveTorrentFile(torrentInfo, selectedVariant.targetFileId) ?: return emptyList()
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
                    provider = ServiceWrapProvider.REAL_DEBRID,
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

    private suspend fun pollTorrentInfo(torrentId: String): RealDebridTorrentInfoDto? {
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
            val score = scoreCandidateFile(
                filename = path?.substringAfterLast('/'),
                fullPath = path,
                sizeBytes = item.size,
                candidate = candidate,
                requestContext = requestContext
            )
            PremiumizeSelection(
                index = index,
                path = path,
                size = item.size,
                streamUrl = item.streamLink ?: item.link,
                score = score
            )
        }.maxWithOrNull(
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
                val score = scoreCandidateFile(
                    filename = entry.value.filename,
                    fullPath = entry.value.filename,
                    sizeBytes = entry.value.filesize,
                    candidate = candidate,
                    requestContext = requestContext
                )
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
            scoreCandidateFile(
                filename = selection.targetFilename,
                fullPath = selection.targetFilename,
                sizeBytes = null,
                candidate = candidate,
                requestContext = requestContext
            )
        }
    }

    private fun resolveTorrentFile(
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

    private fun scoreCandidateFile(
        filename: String?,
        fullPath: String?,
        sizeBytes: Long?,
        candidate: WrapCandidate,
        requestContext: ServiceWrapRequestContext
    ): Int {
        val parsed = parseFileCandidate(fullPath ?: filename)
        var score = 0
        val ext = filename?.substringAfterLast('.', "")?.lowercase(Locale.US).orEmpty()
        if (ext in PLAYABLE_VIDEO_EXTENSIONS) {
            score += 1_000
        } else {
            score -= 500
        }

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

        val sourceTitleKey = normalizeMatchKey(candidate.sourceParsed.title)
        val parsedTitleKey = normalizeMatchKey(parsedFile?.title)
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

    private fun normalizeMatchKey(value: String?): String? {
        return value
            ?.lowercase(Locale.US)
            ?.replace(Regex("""[^\p{L}\p{N}]+"""), "")
            ?.takeIf { it.isNotBlank() }
    }

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

    private companion object {
        val PLAYABLE_VIDEO_EXTENSIONS = setOf(
            "mkv",
            "mp4",
            "avi",
            "m4v",
            "mov",
            "ts",
            "m2ts",
            "wmv",
            "webm"
        )
    }
}
