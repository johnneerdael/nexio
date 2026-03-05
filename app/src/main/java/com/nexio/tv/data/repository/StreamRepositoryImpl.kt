package com.nexio.tv.data.repository

import android.util.Log
import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.core.network.safeApiCall
import com.nexio.tv.data.mapper.toDomain
import com.nexio.tv.data.remote.api.AddonApi
import com.nexio.tv.domain.model.Addon
import com.nexio.tv.domain.model.AddonStreams
import com.nexio.tv.domain.model.Stream
import com.nexio.tv.domain.repository.AddonRepository
import com.nexio.tv.domain.repository.StreamRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.net.URLEncoder
import javax.inject.Inject

private const val TAG = "StreamRepositoryImpl"

class StreamRepositoryImpl @Inject constructor(
    private val api: AddonApi,
    private val addonRepository: AddonRepository
) : StreamRepository {

    override fun getStreamsFromAllAddons(
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?
    ): Flow<NetworkResult<List<AddonStreams>>> = flow {
        emit(NetworkResult.Loading)

        try {
            val addons = addonRepository.getInstalledAddons().first()
            
            // Filter addons that support streams for this type
            val streamAddons = addons.filter { addon ->
                addon.supportsStreamResource(type)
            }

            // Accumulate results as they arrive
            val accumulatedResults = mutableListOf<AddonStreams>()

            coroutineScope {
                // Channel to receive results as they complete
                val resultChannel = Channel<AddonStreams>(Channel.UNLIMITED)
                
                // Track number of pending jobs
                val totalJobs = streamAddons.size
                var completedJobs = 0

                // Launch addon jobs
                streamAddons.forEach { addon ->
                    launch {
                        try {
                            val streamsResult = getStreamsFromAddon(addon.baseUrl, type, videoId)
                            when (streamsResult) {
                                is NetworkResult.Success -> {
                                    if (streamsResult.data.isNotEmpty()) {
                                        val namedStreams = streamsResult.data.map {
                                            it.copy(addonName = addon.displayName, addonLogo = addon.logo)
                                        }
                                        resultChannel.send(
                                            AddonStreams(
                                                addonName = addon.displayName,
                                                addonLogo = addon.logo,
                                                streams = namedStreams
                                            )
                                        )
                                    }
                                }
                                else -> { /* No streams */ }
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            Log.e(TAG, "Addon ${addon.name} failed: ${e.message}")
                        } finally {
                            completedJobs++
                            if (completedJobs >= totalJobs) {
                                resultChannel.close()
                            }
                        }
                    }
                }

                // Handle case where there are no jobs
                if (totalJobs == 0) {
                    resultChannel.close()
                }

                // Emit results as they arrive
                for (result in resultChannel) {
                    accumulatedResults.add(result)
                    emit(NetworkResult.Success(accumulatedResults.toList()))
                    Log.d(TAG, "Emitted ${accumulatedResults.size} addon(s), latest: ${result.addonName} with ${result.streams.size} streams")
                }
            }

            // Emit final result (even if empty)
            if (accumulatedResults.isEmpty()) {
                emit(NetworkResult.Success(emptyList()))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Failed to fetch streams: ${e.message}", e)
            emit(NetworkResult.Error(e.message ?: "Failed to fetch streams"))
        }
    }

    override suspend fun getStreamsFromAddon(
        baseUrl: String,
        type: String,
        videoId: String
    ): NetworkResult<List<Stream>> {
        val cleanBaseUrl = baseUrl.trimEnd('/')
        val encodedType = encodePathSegment(type)
        val encodedVideoId = encodePathSegment(videoId)
        val streamUrl = "$cleanBaseUrl/stream/$encodedType/$encodedVideoId.json"
        Log.d(TAG, "Fetching streams type=$type videoId=$videoId url=$streamUrl")

        // First, get addon info for name and logo
        val addonResult = addonRepository.fetchAddon(baseUrl)
        val addonName = when (addonResult) {
            is NetworkResult.Success -> addonResult.data.displayName
            else -> "Unknown"
        }
        val addonLogo = when (addonResult) {
            is NetworkResult.Success -> addonResult.data.logo
            else -> null
        }

        return when (val result = safeApiCall { api.getStreams(streamUrl) }) {
            is NetworkResult.Success -> {
                val streams = result.data.streams?.map { 
                    it.toDomain(addonName, addonLogo) 
                } ?: emptyList()
                Log.d(TAG, "Streams success addon=$addonName count=${streams.size} url=$streamUrl")
                NetworkResult.Success(streams)
            }
            is NetworkResult.Error -> {
                Log.w(
                    TAG,
                    "Streams failed addon=$addonName code=${result.code} message=${result.message} url=$streamUrl"
                )
                result
            }
            NetworkResult.Loading -> NetworkResult.Loading
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
}
