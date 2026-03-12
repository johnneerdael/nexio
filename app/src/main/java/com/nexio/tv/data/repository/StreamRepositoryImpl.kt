package com.nexio.tv.data.repository

import android.util.Log
import com.nexio.tv.data.local.DebugSettingsDataStore
import com.nexio.tv.core.sync.buildAddonRequestUrl
import com.nexio.tv.core.logging.sanitizeUrlForLogs
import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.core.network.safeApiCall
import com.nexio.tv.data.mapper.toDomain
import com.nexio.tv.data.remote.api.AddonApi
import com.nexio.tv.data.remote.api.StreamSearchRequestTag
import com.nexio.tv.domain.model.Addon
import com.nexio.tv.domain.model.AddonParserPreset
import com.nexio.tv.domain.model.AddonStreams
import com.nexio.tv.domain.model.Stream
import com.nexio.tv.domain.repository.AddonRepository
import com.nexio.tv.domain.repository.StreamRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Named

private const val TAG = "StreamRepositoryImpl"

class StreamRepositoryImpl @Inject constructor(
    @Named("addonStreams") private val api: AddonApi,
    private val addonRepository: AddonRepository,
    private val debugSettingsDataStore: DebugSettingsDataStore,
    @Named("addonStreams") private val okHttpClient: OkHttpClient
) : StreamRepository {

    override fun getStreamsFromAllAddons(
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
        installedAddons: List<Addon>?,
        requestOrigin: String,
        requestId: String
    ): Flow<NetworkResult<List<AddonStreams>>> = flow {
        emit(NetworkResult.Loading)

        try {
            val diagnosticsEnabled = debugSettingsDataStore.streamDiagnosticsEnabled.first()
            val requestStartedAtNs = System.nanoTime()
            val addons = installedAddons ?: addonRepository.getInstalledAddons().first()

            // Filter addons that support streams for this type
            val streamAddons = addons.filter { addon ->
                addon.supportsStreamResource(type)
            }

            // Accumulate results as they arrive
            val accumulatedResults = mutableListOf<AddonStreams>()
            val addonDiagnostics = mutableListOf<AddonFetchDiagnostic>()

            coroutineScope {
                // Channel to receive results as they complete
                val resultChannel = Channel<AddonFetchResult>(Channel.UNLIMITED)

                // Launch addon jobs
                val jobs = streamAddons.map { addon ->
                    launch(Dispatchers.IO) {
                        val addonStartedAtNs = System.nanoTime()
                        var outcome = "exception"
                        var streamCount = 0
                        var httpCode: Int? = null
                        var emittedAddonStreams: AddonStreams? = null
                        var shouldReport = true
                        try {
                            val streamsResult = getStreamsFromAddon(
                                baseUrl = addon.baseUrl,
                                type = type,
                                videoId = videoId,
                                addonName = addon.displayName,
                                addonLogo = addon.logo,
                                addonParserPreset = addon.parserPreset,
                                requestTag = StreamSearchRequestTag(requestId)
                            )
                            when (streamsResult) {
                                is NetworkResult.Success -> {
                                    streamCount = streamsResult.data.size
                                    outcome = if (streamCount > 0) "success_non_empty" else "success_empty"
                                    if (streamCount > 0) {
                                        emittedAddonStreams = AddonStreams(
                                            addonName = addon.displayName,
                                            addonLogo = addon.logo,
                                            streams = streamsResult.data
                                        )
                                    }
                                }
                                is NetworkResult.Error -> {
                                    outcome = "error"
                                    httpCode = streamsResult.code
                                }
                                NetworkResult.Loading -> {
                                    outcome = "loading"
                                }
                            }
                        } catch (e: CancellationException) {
                            shouldReport = false
                            throw e
                        } catch (e: Exception) {
                            Log.e(TAG, "Addon ${addon.name} failed: ${e.message}")
                            outcome = "exception"
                        } finally {
                            if (shouldReport) {
                                resultChannel.send(
                                    AddonFetchResult(
                                        addonStreams = emittedAddonStreams,
                                        diagnostic = AddonFetchDiagnostic(
                                            addonName = addon.displayName,
                                            outcome = outcome,
                                            streamCount = streamCount,
                                            httpCode = httpCode,
                                            durationMs = (System.nanoTime() - addonStartedAtNs) / 1_000_000L
                                        )
                                    )
                                )
                            }
                        }
                    }
                }

                val closeJob = launch {
                    jobs.joinAll()
                    resultChannel.close()
                }

                // Emit results as they arrive
                for (result in resultChannel) {
                    addonDiagnostics += result.diagnostic
                    if (diagnosticsEnabled) {
                        logAddonFetchDiagnostic(
                            requestOrigin = requestOrigin,
                            type = type,
                            season = season,
                            episode = episode,
                            diagnostic = result.diagnostic
                        )
                    }
                    result.addonStreams?.let { addonStreams ->
                        accumulatedResults.add(addonStreams)
                        emit(NetworkResult.Success(accumulatedResults.toList()))
                        Log.d(TAG, "Emitted ${accumulatedResults.size} addon(s), latest: ${addonStreams.addonName} with ${addonStreams.streams.size} streams")
                    }
                }
                closeJob.join()
            }

            if (diagnosticsEnabled) {
                logRequestSummary(
                    requestOrigin = requestOrigin,
                    type = type,
                    season = season,
                    episode = episode,
                    totalAddonCandidates = streamAddons.size,
                    diagnostics = addonDiagnostics,
                    totalDurationMs = (System.nanoTime() - requestStartedAtNs) / 1_000_000L
                )
            }

            // Emit final result (even if empty)
            if (accumulatedResults.isEmpty()) {
                emit(NetworkResult.Success(emptyList()))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Failed to fetch streams: ${e.message}", e)
            emit(NetworkResult.Error(e.message ?: "Failed to fetch streams"))
        } finally {
            cancelActiveStreamRequests(requestId)
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun getStreamsFromAddon(
        baseUrl: String,
        type: String,
        videoId: String
    ): NetworkResult<List<Stream>> {
        return getStreamsFromAddon(
            baseUrl = baseUrl,
            type = type,
            videoId = videoId,
            addonName = "Unknown",
            addonLogo = null,
            addonParserPreset = AddonParserPreset.GENERIC,
            requestTag = StreamSearchRequestTag("standalone")
        )
    }

    private suspend fun getStreamsFromAddon(
        baseUrl: String,
        type: String,
        videoId: String,
        addonName: String,
        addonLogo: String?,
        addonParserPreset: AddonParserPreset,
        requestTag: StreamSearchRequestTag
    ): NetworkResult<List<Stream>> {
        val encodedType = encodePathSegment(type)
        val encodedVideoId = encodePathSegment(videoId)
        val streamUrl = buildAddonRequestUrl(baseUrl, "stream/$encodedType/$encodedVideoId.json")
        Log.d(TAG, "Fetching streams type=$type videoId=$videoId url=${sanitizeUrlForLogs(streamUrl)}")

        return when (val result = safeApiCall { api.getStreams(streamUrl, requestTag) }) {
            is NetworkResult.Success -> {
                val streams = result.data.streams?.map { 
                    it.toDomain(addonName, addonLogo, addonParserPreset)
                } ?: emptyList()
                Log.d(TAG, "Streams success addon=$addonName count=${streams.size} url=${sanitizeUrlForLogs(streamUrl)}")
                NetworkResult.Success(streams)
            }
            is NetworkResult.Error -> {
                Log.w(
                    TAG,
                    "Streams failed addon=$addonName code=${result.code} message=${result.message} url=${sanitizeUrlForLogs(streamUrl)}"
                )
                result
            }
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    override fun cancelActiveStreamRequests(requestId: String) {
        val calls = okHttpClient.dispatcher.runningCalls() + okHttpClient.dispatcher.queuedCalls()
        var cancelledCount = 0
        calls.forEach { call ->
            val tag = call.request().tag(StreamSearchRequestTag::class.java)
            if (tag?.requestId == requestId) {
                cancelledCount += 1
                call.cancel()
            }
        }
        if (cancelledCount > 0) {
            Log.d(TAG, "Cancelled $cancelledCount in-flight addon stream request(s) requestId=$requestId")
        }
    }

    /**
     * Check if addon supports stream resource for the given type
     */
    private fun Addon.supportsStreamResource(type: String): Boolean {
        return resources.any { resource ->
            resource.name == "stream" && 
            (resource.types.isEmpty() || resource.types.contains(type))
        }
    }

    private fun encodePathSegment(value: String): String {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }

    private fun logAddonFetchDiagnostic(
        requestOrigin: String,
        type: String,
        season: Int?,
        episode: Int?,
        diagnostic: AddonFetchDiagnostic
    ) {
        Log.d(
            TAG,
            "STREAM_DIAG addon origin=$requestOrigin type=$type season=${season ?: "-"} episode=${episode ?: "-"} " +
                "addon=${diagnostic.addonName} outcome=${diagnostic.outcome} count=${diagnostic.streamCount} " +
                "durationMs=${diagnostic.durationMs} httpCode=${diagnostic.httpCode ?: "-"}"
        )
    }

    private fun logRequestSummary(
        requestOrigin: String,
        type: String,
        season: Int?,
        episode: Int?,
        totalAddonCandidates: Int,
        diagnostics: List<AddonFetchDiagnostic>,
        totalDurationMs: Long
    ) {
        val nonEmptyAddons = diagnostics.count { it.outcome == "success_non_empty" }
        val totalRawStreams = diagnostics.sumOf { it.streamCount }
        val slowest = diagnostics
            .sortedByDescending { it.durationMs }
            .take(3)
            .joinToString(";") { "${it.addonName}:${it.durationMs}ms:${it.outcome}" }
            .ifBlank { "-" }

        Log.d(
            TAG,
            "STREAM_DIAG summary origin=$requestOrigin type=$type season=${season ?: "-"} episode=${episode ?: "-"} " +
                "addonsConsidered=$totalAddonCandidates addonsCompleted=${diagnostics.size} nonEmptyAddons=$nonEmptyAddons " +
                "totalRawStreams=$totalRawStreams totalDurationMs=$totalDurationMs slowest=$slowest"
        )
    }
}

private data class AddonFetchResult(
    val addonStreams: AddonStreams?,
    val diagnostic: AddonFetchDiagnostic
)

private data class AddonFetchDiagnostic(
    val addonName: String,
    val outcome: String,
    val streamCount: Int,
    val httpCode: Int?,
    val durationMs: Long
)
