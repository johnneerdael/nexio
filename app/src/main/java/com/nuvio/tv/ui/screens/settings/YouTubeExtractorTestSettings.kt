@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.trailer.InAppYouTubeExtractor
import com.nuvio.tv.ui.components.TrailerPlayer
import com.nuvio.tv.ui.screens.account.InputField
import com.nuvio.tv.ui.theme.NuvioColors
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class YouTubeExtractorTestUiState(
    val youtubeUrl: String = "",
    val isLoading: Boolean = false,
    val videoUrl: String? = null,
    val audioUrl: String? = null,
    val isPlaying: Boolean = false,
    val errorMessageResId: Int? = null
)

@HiltViewModel
internal class YouTubeExtractorTestViewModel @Inject constructor(
    private val inAppYouTubeExtractor: InAppYouTubeExtractor
) : ViewModel() {

    private val _uiState = MutableStateFlow(YouTubeExtractorTestUiState())
    val uiState: StateFlow<YouTubeExtractorTestUiState> = _uiState.asStateFlow()

    fun onUrlChanged(url: String) {
        _uiState.update { it.copy(youtubeUrl = url, errorMessageResId = null) }
    }

    fun onExtract() {
        val inputUrl = _uiState.value.youtubeUrl.trim()
        if (inputUrl.isBlank()) {
            _uiState.update {
                it.copy(
                    errorMessageResId = R.string.settings_youtube_extractor_error_enter_url,
                    videoUrl = null,
                    audioUrl = null,
                    isPlaying = false
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    errorMessageResId = null,
                    videoUrl = null,
                    audioUrl = null,
                    isPlaying = false
                )
            }

            val source = runCatching {
                inAppYouTubeExtractor.extractPlaybackSource(inputUrl)
            }.getOrNull()

            if (source == null) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessageResId = R.string.settings_youtube_extractor_error_failed
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        videoUrl = source.videoUrl,
                        audioUrl = source.audioUrl,
                        isPlaying = true
                    )
                }
            }
        }
    }

    fun togglePlayback() {
        _uiState.update { state ->
            if (state.videoUrl.isNullOrBlank()) {
                state.copy(isPlaying = false)
            } else {
                state.copy(isPlaying = !state.isPlaying)
            }
        }
    }

    fun stopPlayback() {
        _uiState.update { it.copy(isPlaying = false) }
    }
}

@Composable
internal fun YouTubeExtractorTestContent(
    initialFocusRequester: FocusRequester,
    viewModel: YouTubeExtractorTestViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val videoUrl = uiState.videoUrl
    val audioUrl = uiState.audioUrl
    val hasExtractedSource = !videoUrl.isNullOrBlank()

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.settings_youtube_extractor_test_title),
            subtitle = stringResource(R.string.settings_youtube_extractor_test_subtitle)
        )

        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
        ) {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(key = "yt_extractor_input") {
                    InputField(
                        value = uiState.youtubeUrl,
                        onValueChange = viewModel::onUrlChanged,
                        placeholder = stringResource(R.string.settings_youtube_extractor_input_placeholder),
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                        onImeAction = viewModel::onExtract
                    )
                }

                item(key = "yt_extractor_run") {
                    SettingsActionRow(
                        title = if (uiState.isLoading) {
                            stringResource(R.string.settings_youtube_extractor_running)
                        } else {
                            stringResource(R.string.settings_youtube_extractor_extract_action)
                        },
                        subtitle = stringResource(R.string.settings_youtube_extractor_extract_action_subtitle),
                        onClick = viewModel::onExtract,
                        enabled = !uiState.isLoading,
                        modifier = Modifier.focusRequester(initialFocusRequester)
                    )
                }

                if (hasExtractedSource) {
                    item(key = "yt_extractor_toggle_playback") {
                        SettingsActionRow(
                            title = if (uiState.isPlaying) {
                                stringResource(R.string.settings_youtube_extractor_pause_preview)
                            } else {
                                stringResource(R.string.settings_youtube_extractor_play_preview)
                            },
                            subtitle = stringResource(R.string.settings_youtube_extractor_play_preview_subtitle),
                            value = if (audioUrl.isNullOrBlank()) {
                                stringResource(R.string.settings_youtube_extractor_stream_single)
                            } else {
                                stringResource(R.string.settings_youtube_extractor_stream_split)
                            },
                            onClick = viewModel::togglePlayback,
                            trailingIcon = if (uiState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow
                        )
                    }

                    item(key = "yt_extractor_video_url") {
                        UrlInfoCard(
                            label = stringResource(R.string.settings_youtube_extractor_video_url),
                            value = videoUrl.orEmpty()
                        )
                    }

                    item(key = "yt_extractor_audio_url") {
                        UrlInfoCard(
                            label = stringResource(R.string.settings_youtube_extractor_audio_url),
                            value = audioUrl ?: stringResource(R.string.settings_youtube_extractor_audio_url_missing)
                        )
                    }

                    item(key = "yt_extractor_preview") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(260.dp)
                                .clip(RoundedCornerShape(SettingsSecondaryCardRadius))
                                .background(NuvioColors.Background)
                        ) {
                            TrailerPlayer(
                                trailerUrl = videoUrl,
                                trailerAudioUrl = audioUrl,
                                isPlaying = uiState.isPlaying,
                                onEnded = viewModel::stopPlayback,
                                muted = false,
                                modifier = Modifier.fillMaxSize()
                            )

                            if (!uiState.isPlaying) {
                                Text(
                                    text = stringResource(R.string.settings_youtube_extractor_preview_idle),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = NuvioColors.TextSecondary,
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .padding(horizontal = 16.dp)
                                )
                            }
                        }
                    }
                }

                val errorMessageResId = uiState.errorMessageResId
                if (errorMessageResId != null) {
                    item(key = "yt_extractor_error") {
                        Text(
                            text = stringResource(errorMessageResId),
                            style = MaterialTheme.typography.bodyMedium,
                            color = NuvioColors.Error,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UrlInfoCard(
    label: String,
    value: String
) {
    var expanded by remember { mutableStateOf(false) }
    SettingsGroupCard(
        title = label
    ) {
        val maxLines = if (expanded) 8 else 2
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = NuvioColors.TextSecondary,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp)
        )
        SettingsActionRow(
            title = if (expanded) {
                stringResource(R.string.settings_youtube_extractor_show_less)
            } else {
                stringResource(R.string.settings_youtube_extractor_show_full_url)
            },
            subtitle = null,
            onClick = { expanded = !expanded }
        )
    }
}
