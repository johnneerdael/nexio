package com.nuvio.tv.ui.screens.stream

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.local.PlayerPreference
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.StreamLinkCacheDataStore
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.StreamRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StreamScreenViewModel @Inject constructor(
    private val streamRepository: StreamRepository,
    private val addonRepository: AddonRepository,
    private val metaRepository: MetaRepository,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val streamLinkCacheDataStore: StreamLinkCacheDataStore,
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    private var autoPlayHandledForSession = false

    private val videoId: String = savedStateHandle["videoId"] ?: ""
    private val contentType: String = savedStateHandle["contentType"] ?: ""
    private val title: String = savedStateHandle["title"] ?: ""
    private val poster: String? = savedStateHandle.getOptionalString("poster")
    private val backdrop: String? = savedStateHandle.getOptionalString("backdrop")
    private val logo: String? = savedStateHandle.getOptionalString("logo")
    private val season: Int? = savedStateHandle.get<String>("season")?.toIntOrNull()
    private val episode: Int? = savedStateHandle.get<String>("episode")?.toIntOrNull()
    private val episodeName: String? = savedStateHandle.getOptionalString("episodeName")
    private val runtime: Int? = savedStateHandle.get<String>("runtime")?.toIntOrNull()
    private val genres: String? = savedStateHandle.getOptionalString("genres")
    private val year: String? = savedStateHandle.getOptionalString("year")
    private val contentId: String? = savedStateHandle.getOptionalString("contentId")
    private val contentName: String? = savedStateHandle.getOptionalString("contentName")
    private val streamCacheKey: String = "${contentType.lowercase()}|$videoId"

    private val _uiState = MutableStateFlow(
        StreamScreenUiState(
            videoId = videoId,
            contentType = contentType,
            title = title,
            poster = poster,
            backdrop = backdrop,
            logo = logo,
            season = season,
            episode = episode,
            episodeName = episodeName,
            runtime = runtime,
            genres = genres,
            year = year
        )
    )
    val uiState: StateFlow<StreamScreenUiState> = _uiState.asStateFlow()

    val playerPreference = playerSettingsDataStore.playerSettings
        .map { it.playerPreference }

    init {
        loadMissingMetaDetailsIfNeeded()
        loadStreams()
    }

    private fun SavedStateHandle.getOptionalString(key: String): String? {
        return get<String>(key)?.takeIf { it.isNotBlank() }
    }

    fun onEvent(event: StreamScreenEvent) {
        when (event) {
            is StreamScreenEvent.OnAddonFilterSelected -> filterByAddon(event.addonName)
            is StreamScreenEvent.OnStreamSelected -> { /* Handle stream selection - will be handled in UI */ }
            StreamScreenEvent.OnAutoPlayConsumed -> {
                autoPlayHandledForSession = true
                _uiState.update { it.copy(autoPlayStream = null, autoPlayPlaybackInfo = null) }
            }
            StreamScreenEvent.OnRetry -> loadStreams()
            StreamScreenEvent.OnBackPress -> { /* Handle in screen */ }
        }
    }

    private fun loadStreams() {
        viewModelScope.launch {
            val playerSettings = playerSettingsDataStore.playerSettings.first()

            if (!autoPlayHandledForSession && playerSettings.streamReuseLastLinkEnabled) {
                val cached = streamLinkCacheDataStore.getValid(
                    contentKey = streamCacheKey,
                    maxAgeMs = playerSettings.streamReuseLastLinkCacheHours * 60L * 60L * 1000L
                )
                if (cached != null) {
                    autoPlayHandledForSession = true
                    _uiState.update {
                        it.copy(
                            autoPlayPlaybackInfo = StreamPlaybackInfo(
                                url = cached.url,
                                title = title,
                                streamName = cached.streamName,
                                year = year,
                                isExternal = false,
                                isTorrent = false,
                                infoHash = null,
                                ytId = null,
                                headers = cached.headers,
                                contentId = contentId ?: videoId.substringBefore(":"),
                                contentType = contentType,
                                contentName = contentName ?: title,
                                poster = poster,
                                backdrop = backdrop,
                                logo = logo,
                                videoId = videoId,
                                season = season,
                                episode = episode,
                                episodeTitle = episodeName,
                                rememberedAudioLanguage = cached.rememberedAudioLanguage,
                                rememberedAudioName = cached.rememberedAudioName
                            )
                        )
                    }
                }
            }

            _uiState.update { it.copy(isLoading = true, error = null) }

            val installedAddons = addonRepository.getInstalledAddons().first()
            val installedAddonOrder = installedAddons.map { it.name }

            streamRepository.getStreamsFromAllAddons(
                type = contentType,
                videoId = videoId,
                season = season,
                episode = episode
            ).collect { result ->
                when (result) {
                    is NetworkResult.Success -> {
                        val addonStreams = orderStreams(result.data, installedAddonOrder)
                        val allStreams = addonStreams.flatMap { it.streams }
                        val availableAddons = addonStreams.map { it.addonName }
                        
                        // Apply current filter if one is selected
                        val currentFilter = _uiState.value.selectedAddonFilter
                        val filteredStreams = if (currentFilter == null) {
                            allStreams
                        } else {
                            allStreams.filter { it.addonName == currentFilter }
                        }

                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                addonStreams = addonStreams,
                                allStreams = allStreams,
                                filteredStreams = filteredStreams,
                                availableAddons = availableAddons,
                                autoPlayStream = if (autoPlayHandledForSession) {
                                    null
                                } else {
                                    selectAutoPlayStream(
                                        streams = allStreams,
                                        mode = playerSettings.streamAutoPlayMode,
                                        regexPattern = playerSettings.streamAutoPlayRegex,
                                        source = playerSettings.streamAutoPlaySource,
                                        installedAddonNames = installedAddonOrder.toSet(),
                                        selectedAddons = playerSettings.streamAutoPlaySelectedAddons,
                                        selectedPlugins = playerSettings.streamAutoPlaySelectedPlugins
                                    )
                                },
                                error = null
                            )
                        }
                    }
                    is NetworkResult.Error -> {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                error = result.message
                            )
                        }
                    }
                    NetworkResult.Loading -> {
                        _uiState.update { it.copy(isLoading = true) }
                    }
                }
            }
        }
    }

    private fun loadMissingMetaDetailsIfNeeded() {
        val requiresMetadataLookup = genres.isNullOrBlank() || year.isNullOrBlank() || runtime == null
        if (!requiresMetadataLookup) return

        val metaId = contentId ?: videoId.substringBefore(":")
        if (metaId.isBlank() || contentType.isBlank()) return

        viewModelScope.launch {
            val result = metaRepository.getMetaFromAllAddons(type = contentType, id = metaId)
                .first { it !is NetworkResult.Loading }

            if (result !is NetworkResult.Success) return@launch

            val meta = result.data
            val metaGenres = meta.genres.takeIf { it.isNotEmpty() }?.joinToString(" • ")
            val metaYear = meta.releaseInfo
                ?.substringBefore("-")
                ?.takeIf { it.isNotBlank() }
            val metaRuntime = extractRuntimeMinutes(meta)

            _uiState.update { state ->
                state.copy(
                    poster = state.poster ?: meta.poster,
                    backdrop = state.backdrop ?: meta.background,
                    logo = state.logo ?: meta.logo,
                    genres = state.genres?.takeIf { it.isNotBlank() } ?: metaGenres,
                    year = state.year?.takeIf { it.isNotBlank() } ?: metaYear,
                    runtime = state.runtime ?: metaRuntime
                )
            }
        }
    }

    private fun extractRuntimeMinutes(meta: Meta): Int? {
        if (season != null && episode != null) {
            return meta.videos.firstOrNull { it.season == season && it.episode == episode }?.runtime
        }
        return meta.runtime
            ?.let { Regex("(\\d+)").find(it)?.groupValues?.getOrNull(1) }
            ?.toIntOrNull()
    }

    private fun filterByAddon(addonName: String?) {
        val allStreams = _uiState.value.allStreams
        val filteredStreams = if (addonName == null) {
            allStreams
        } else {
            allStreams.filter { it.addonName == addonName }
        }

        _uiState.update {
            it.copy(
                selectedAddonFilter = addonName,
                filteredStreams = filteredStreams
            )
        }
    }

    /**
     * Gets the selected stream for playback
     */
    fun getStreamForPlayback(stream: Stream): StreamPlaybackInfo {
        val playbackInfo = StreamPlaybackInfo(
            url = stream.getStreamUrl(),
            title = _uiState.value.title,
            streamName = stream.name ?: stream.addonName,
            year = year,
            isExternal = stream.isExternal(),
            isTorrent = stream.isTorrent(),
            infoHash = stream.infoHash,
            ytId = stream.ytId,
            headers = stream.behaviorHints?.proxyHeaders?.request,
            contentId = contentId ?: videoId.substringBefore(":"),  // Use explicit contentId or extract from videoId
            contentType = contentType,
            contentName = contentName ?: title,
            poster = poster,
            backdrop = backdrop,
            logo = logo,
            videoId = videoId,
            season = season,
            episode = episode,
            episodeTitle = episodeName,
            rememberedAudioLanguage = null,
            rememberedAudioName = null
        )

        val url = playbackInfo.url
        if (!url.isNullOrBlank()) {
            viewModelScope.launch {
                streamLinkCacheDataStore.save(
                    contentKey = streamCacheKey,
                    url = url,
                    streamName = playbackInfo.streamName,
                    headers = playbackInfo.headers
                )
            }
        }

        return playbackInfo
    }

    private fun orderStreams(
        streams: List<com.nuvio.tv.domain.model.AddonStreams>,
        installedOrder: List<String>
    ): List<com.nuvio.tv.domain.model.AddonStreams> {
        if (streams.isEmpty()) return streams

        val (addonEntries, pluginEntries) = streams.partition { it.addonName in installedOrder }
        val orderedAddons = addonEntries.sortedBy { installedOrder.indexOf(it.addonName) }
        return orderedAddons + pluginEntries
    }

    private fun selectAutoPlayStream(
        streams: List<Stream>,
        mode: StreamAutoPlayMode,
        regexPattern: String,
        source: StreamAutoPlaySource,
        installedAddonNames: Set<String>,
        selectedAddons: Set<String>,
        selectedPlugins: Set<String>
    ): Stream? {
        if (streams.isEmpty()) return null
        val sourceScopedStreams = when (source) {
            StreamAutoPlaySource.ALL_SOURCES -> streams
            StreamAutoPlaySource.INSTALLED_ADDONS_ONLY -> streams.filter { it.addonName in installedAddonNames }
            StreamAutoPlaySource.ENABLED_PLUGINS_ONLY -> streams.filter { it.addonName !in installedAddonNames }
        }
        val candidateStreams = sourceScopedStreams.filter { stream ->
            val isAddonStream = stream.addonName in installedAddonNames
            if (isAddonStream) {
                selectedAddons.isEmpty() || stream.addonName in selectedAddons
            } else {
                selectedPlugins.isEmpty() || stream.addonName in selectedPlugins
            }
        }
        if (candidateStreams.isEmpty()) return null

        return when (mode) {
            StreamAutoPlayMode.MANUAL -> null
            StreamAutoPlayMode.FIRST_STREAM -> candidateStreams.firstOrNull { it.getStreamUrl() != null }
            StreamAutoPlayMode.REGEX_MATCH -> {
                val pattern = regexPattern.trim()
                if (pattern.isBlank()) return null
                val regex = runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull() ?: return null

                candidateStreams.firstOrNull { stream ->
                    val searchableText = buildString {
                        append(stream.addonName)
                        append(' ')
                        append(stream.name.orEmpty())
                        append(' ')
                        append(stream.title.orEmpty())
                        append(' ')
                        append(stream.description.orEmpty())
                        append(' ')
                        append(stream.getStreamUrl().orEmpty())
                    }
                    stream.getStreamUrl() != null && regex.containsMatchIn(searchableText)
                }
            }
        }
    }
}

data class StreamPlaybackInfo(
    val url: String?,
    val title: String,
    val streamName: String,
    val year: String?,
    val isExternal: Boolean,
    val isTorrent: Boolean,
    val infoHash: String?,
    val ytId: String?,
    val headers: Map<String, String>?,
    // Watch progress metadata
    val contentId: String?,
    val contentType: String?,
    val contentName: String?,
    val poster: String?,
    val backdrop: String?,
    val logo: String?,
    val videoId: String?,
    val season: Int?,
    val episode: Int?,
    val episodeTitle: String?,
    val rememberedAudioLanguage: String?,
    val rememberedAudioName: String?
)
