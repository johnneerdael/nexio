package com.nuvio.tv.ui.screens.detail

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.foundation.lazy.list.TvLazyColumn
import androidx.tv.foundation.lazy.list.rememberTvLazyListState
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaCastMember
import com.nuvio.tv.domain.model.NextToWatch
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.ui.components.ErrorState
import com.nuvio.tv.ui.components.FadeInAsyncImage
import com.nuvio.tv.ui.components.MetaDetailsSkeleton
import com.nuvio.tv.ui.components.TrailerPlayer
import com.nuvio.tv.ui.theme.NuvioColors

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun MetaDetailsScreen(
    viewModel: MetaDetailsViewModel = hiltViewModel(),
    onBackPress: () -> Unit,
    onPlayClick: (
        videoId: String,
        contentType: String,
        contentId: String,
        title: String,
        poster: String?,
        backdrop: String?,
        logo: String?,
        season: Int?,
        episode: Int?,
        episodeName: String?,
        genres: String?,
        year: String?,
        runtime: Int?
    ) -> Unit = { _, _, _, _, _, _, _, _, _, _, _, _, _ -> }
) {
    val uiState by viewModel.uiState.collectAsState()

    BackHandler {
        if (uiState.isTrailerPlaying) {
            viewModel.onEvent(MetaDetailsEvent.OnUserInteraction)
        } else {
            onBackPress()
        }
    }

    val currentIsTrailerPlaying by rememberUpdatedState(uiState.isTrailerPlaying)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioColors.Background)
            .onPreviewKeyEvent { keyEvent ->
                if (currentIsTrailerPlaying) {
                    // During trailer, consume all keys except back/ESC so content doesn't scroll
                    val keyCode = keyEvent.nativeKeyEvent.keyCode
                    return@onPreviewKeyEvent keyCode != KeyEvent.KEYCODE_BACK &&
                            keyCode != KeyEvent.KEYCODE_ESCAPE
                }
                if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                    viewModel.onEvent(MetaDetailsEvent.OnUserInteraction)
                }
                false
            }
    ) {
        when {
            uiState.isLoading -> {
                MetaDetailsSkeleton()
            }
            uiState.error != null -> {
                ErrorState(
                    message = uiState.error ?: "An error occurred",
                    onRetry = { viewModel.onEvent(MetaDetailsEvent.OnRetry) }
                )
            }
            uiState.meta != null -> {
                val meta = uiState.meta!!
                val genresString = remember(meta.genres) {
                    meta.genres.takeIf { it.isNotEmpty() }?.joinToString(" • ")
                }
                val yearString = remember(meta.releaseInfo) {
                    meta.releaseInfo?.split("-")?.firstOrNull() ?: meta.releaseInfo
                }

                MetaDetailsContent(
                    meta = meta,
                    seasons = uiState.seasons,
                    selectedSeason = uiState.selectedSeason,
                    episodesForSeason = uiState.episodesForSeason,
                    isInLibrary = uiState.isInLibrary,
                    nextToWatch = uiState.nextToWatch,
                    episodeProgressMap = uiState.episodeProgressMap,
                    onSeasonSelected = { viewModel.onEvent(MetaDetailsEvent.OnSeasonSelected(it)) },
                    onEpisodeClick = { video ->
                        onPlayClick(
                            video.id,
                            meta.type.toApiString(),
                            meta.id,
                            meta.name,
                            video.thumbnail ?: meta.poster,
                            meta.background,
                            meta.logo,
                            video.season,
                            video.episode,
                            video.title,
                            null,
                            null,
                            video.runtime
                        )
                    },
                    onPlayClick = { videoId ->
                        onPlayClick(
                            videoId,
                            meta.type.toApiString(),
                            meta.id,
                            meta.name,
                            meta.poster,
                            meta.background,
                            meta.logo,
                            null,
                            null,
                            null,
                            genresString,
                            yearString,
                            null
                        )
                    },
                    onToggleLibrary = { viewModel.onEvent(MetaDetailsEvent.OnToggleLibrary) },
                    trailerUrl = uiState.trailerUrl,
                    isTrailerPlaying = uiState.isTrailerPlaying,
                    onTrailerEnded = { viewModel.onEvent(MetaDetailsEvent.OnTrailerEnded) }
                )
            }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MetaDetailsContent(
    meta: Meta,
    seasons: List<Int>,
    selectedSeason: Int,
    episodesForSeason: List<Video>,
    isInLibrary: Boolean,
    nextToWatch: NextToWatch?,
    episodeProgressMap: Map<Pair<Int, Int>, WatchProgress>,
    onSeasonSelected: (Int) -> Unit,
    onEpisodeClick: (Video) -> Unit,
    onPlayClick: (String) -> Unit,
    onToggleLibrary: () -> Unit,
    trailerUrl: String?,
    isTrailerPlaying: Boolean,
    onTrailerEnded: () -> Unit
) {
    val isSeries = remember(meta.type, meta.videos) {
        meta.type == ContentType.SERIES || meta.videos.isNotEmpty()
    }
    val nextEpisode = remember(episodesForSeason) { episodesForSeason.firstOrNull() }
    val heroVideo = remember(meta.videos, nextToWatch, nextEpisode, isSeries) {
        if (!isSeries) return@remember null
        val byId = nextToWatch?.nextVideoId?.let { id ->
            meta.videos.firstOrNull { it.id == id }
        }
        val bySeasonEpisode = if (byId == null && nextToWatch?.nextSeason != null && nextToWatch.nextEpisode != null) {
            meta.videos.firstOrNull { it.season == nextToWatch.nextSeason && it.episode == nextToWatch.nextEpisode }
        } else {
            null
        }
        byId ?: bySeasonEpisode ?: nextEpisode
    }
    val listState = rememberTvLazyListState()
    val initialFocusRequester = remember { FocusRequester() }
    val selectedSeasonFocusRequester = remember { FocusRequester() }

    // Track if scrolled past hero (first item)
    val isScrolledPastHero by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 ||
            (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset > 200)
        }
    }

    // Pre-compute cast members to avoid recomputation in lazy scope
    val castMembersToShow = remember(meta.castMembers, meta.cast) {
        if (meta.castMembers.isNotEmpty()) {
            meta.castMembers
        } else {
            meta.cast.map { name -> MetaCastMember(name = name) }
        }
    }

    // Backdrop alpha for crossfade
    val backdropAlpha by animateFloatAsState(
        targetValue = if (isTrailerPlaying) 0f else 1f,
        animationSpec = tween(durationMillis = 800),
        label = "backdropFade"
    )

    val backgroundColor = NuvioColors.Background

    // Pre-compute gradient brushes once
    val leftGradient = remember(backgroundColor) {
        Brush.horizontalGradient(
            colorStops = arrayOf(
                0.0f to backgroundColor,
                0.20f to backgroundColor.copy(alpha = 0.95f),
                0.35f to backgroundColor.copy(alpha = 0.8f),
                0.45f to backgroundColor.copy(alpha = 0.6f),
                0.55f to backgroundColor.copy(alpha = 0.4f),
                0.65f to backgroundColor.copy(alpha = 0.2f),
                0.75f to Color.Transparent,
                1.0f to Color.Transparent
            )
        )
    }
    val bottomGradient = remember(backgroundColor) {
        Brush.verticalGradient(
            colorStops = arrayOf(
                0.0f to Color.Transparent,
                0.5f to Color.Transparent,
                0.7f to backgroundColor.copy(alpha = 0.5f),
                0.85f to backgroundColor.copy(alpha = 0.8f),
                1.0f to backgroundColor
            )
        )
    }
    val dimColor = remember(backgroundColor) { backgroundColor.copy(alpha = 0.08f) }

    // Stable hero play callback
    val heroPlayClick = remember(heroVideo, meta.id) {
        {
            if (heroVideo != null) {
                onEpisodeClick(heroVideo)
            } else {
                onPlayClick(meta.id)
            }
        }
    }

    // Pre-compute screen dimensions to avoid BoxWithConstraints subcomposition overhead
    val configuration = LocalConfiguration.current
    val screenWidthDp = remember(configuration) { configuration.screenWidthDp.dp }
    val screenHeightDp = remember(configuration) { configuration.screenHeightDp.dp }

    // Animated gradient alpha (moved outside subcomposition scope)
    val gradientAlpha by animateFloatAsState(
        targetValue = if (isTrailerPlaying) 0f else 1f,
        animationSpec = tween(durationMillis = 800),
        label = "gradientFade"
    )

    // Always-composed bottom gradient alpha (avoids add/remove during scroll)
    val bottomGradientAlpha by animateFloatAsState(
        targetValue = if (isScrolledPastHero) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "bottomGradientFade"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        // Sticky background — backdrop or trailer
        Box(modifier = Modifier.fillMaxSize()) {
            // Backdrop image (fades out when trailer plays)
            FadeInAsyncImage(
                model = meta.background ?: meta.poster,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = backdropAlpha },
                contentScale = ContentScale.Crop,
                fadeDurationMs = 600,
                requestedWidthDp = screenWidthDp,
                requestedHeightDp = screenHeightDp
            )

            // Trailer video (fades in when trailer plays)
            TrailerPlayer(
                trailerUrl = trailerUrl,
                isPlaying = isTrailerPlaying,
                onEnded = onTrailerEnded,
                modifier = Modifier.fillMaxSize()
            )

            // Light global dim so text remains readable
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(dimColor)
            )

            // Left side gradient fade for text readability (fades out during trailer)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = gradientAlpha }
                    .background(leftGradient)
            )

            // Bottom gradient — always composed, alpha-controlled to avoid layout churn
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = bottomGradientAlpha }
                    .background(bottomGradient)
            )
        }

        // Single scrollable column with hero + content
        TvLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState
        ) {
            // Invisible focus anchor — captures initial focus so no button is highlighted on entry
            item(key = "focus_anchor", contentType = "focus_anchor") {
                Box(
                    modifier = Modifier
                        .focusRequester(initialFocusRequester)
                        .focusable()
                )
                LaunchedEffect(Unit) {
                    initialFocusRequester.requestFocus()
                }
            }

            // Hero as first item in the lazy column
            item(key = "hero", contentType = "hero") {
                HeroContentSection(
                    meta = meta,
                    nextEpisode = nextEpisode,
                    nextToWatch = nextToWatch,
                    onPlayClick = heroPlayClick,
                    isInLibrary = isInLibrary,
                    onToggleLibrary = onToggleLibrary,
                    isTrailerPlaying = isTrailerPlaying
                )
            }

            // Season tabs and episodes for series
            if (isSeries && seasons.isNotEmpty()) {
                item(key = "season_tabs", contentType = "season_tabs") {
                    SeasonTabs(
                        seasons = seasons,
                        selectedSeason = selectedSeason,
                        onSeasonSelected = onSeasonSelected,
                        selectedTabFocusRequester = selectedSeasonFocusRequester
                    )
                }
                item(key = "episodes_$selectedSeason", contentType = "episodes") {
                    EpisodesRow(
                        episodes = episodesForSeason,
                        episodeProgressMap = episodeProgressMap,
                        onEpisodeClick = onEpisodeClick,
                        upFocusRequester = selectedSeasonFocusRequester
                    )
                }
            }

            // Cast section below episodes
            if (castMembersToShow.isNotEmpty()) {
                item(key = "cast", contentType = "horizontal_row") {
                    CastSection(cast = castMembersToShow)
                }
            }

            if (meta.productionCompanies.isNotEmpty()) {
                item(key = "production", contentType = "horizontal_row") {
                    CompanyLogosSection(
                        title = "Production",
                        companies = meta.productionCompanies
                    )
                }
            }

            if (meta.networks.isNotEmpty()) {
                item(key = "networks", contentType = "horizontal_row") {
                    CompanyLogosSection(
                        title = "Network",
                        companies = meta.networks
                    )
                }
            }
        }
    }
}
