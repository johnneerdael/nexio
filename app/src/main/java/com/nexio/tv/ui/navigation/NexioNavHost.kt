package com.nexio.tv.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.nexio.tv.core.metadata.parseRuntimeMinutes
import com.nexio.tv.ui.screens.CatalogSeeAllScreen
import com.nexio.tv.ui.screens.LayoutSelectionScreen
import com.nexio.tv.ui.screens.AndroidTvFeedBrowserScreen
import com.nexio.tv.ui.screens.detail.MetaDetailsScreen
import com.nexio.tv.ui.screens.home.HomeScreen
import com.nexio.tv.ui.screens.home.HomeViewModel
import com.nexio.tv.ui.screens.home.displayMetadata
import com.nexio.tv.ui.screens.home.recordContinueWatchingRouteContextForPlayback
import com.nexio.tv.ui.screens.home.resolveContinueWatchingRuntimeMinutes
import com.nexio.tv.ui.screens.home.withHydratedRuntimeMinutes
import com.nexio.tv.ui.screens.addon.AddonManagerScreen
import com.nexio.tv.ui.screens.addon.CatalogOrderScreen
import com.nexio.tv.ui.screens.library.LibraryScreen
import com.nexio.tv.ui.screens.player.PlayerLaunchSource
import com.nexio.tv.ui.screens.player.PlayerScreen
import com.nexio.tv.ui.screens.search.DiscoverScreen
import com.nexio.tv.ui.screens.search.SearchScreen
import com.nexio.tv.ui.screens.settings.AboutScreen
import com.nexio.tv.ui.screens.settings.LayoutSettingsScreen
import com.nexio.tv.ui.screens.settings.PlaybackSettingsScreen
import com.nexio.tv.ui.screens.settings.SettingsScreen
import com.nexio.tv.ui.screens.settings.ThemeSettingsScreen
import com.nexio.tv.ui.screens.settings.TraktScreen
import com.nexio.tv.ui.screens.settings.TmdbSettingsScreen
import com.nexio.tv.ui.screens.stream.StreamScreen
import com.nexio.tv.ui.screens.home.ContinueWatchingItem
import com.nexio.tv.ui.screens.account.AuthSignInScreen
import kotlinx.coroutines.launch
import com.nexio.tv.ui.screens.account.AuthQrSignInScreen
import com.nexio.tv.ui.screens.cast.CastDetailScreen
import com.nexio.tv.ui.screens.organization.OrganizationDetailScreen
import androidx.hilt.navigation.compose.hiltViewModel
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.MetaPreview

/**
 * Choose the value to pass as the player's `originalLanguage` nav arg.
 *
 * Bug A regression guard: prefer the production-language field, fall back to
 * the legacy `language` field only when the new field is null. The legacy
 * field is itself sometimes the user's UI locale (Bug A) — but the only path
 * that produces such leakage is `MetaDetailsViewModel`'s pre-Phase-A code,
 * which Phase A removed. After Phase C lands, `language` should never be
 * production-meaningful in any new write path.
 */
fun chooseNavOriginalLanguage(item: MetaPreview): String? =
    item.originalLanguage ?: item.language

fun chooseNavOriginalLanguage(meta: Meta): String? =
    meta.originalLanguage ?: meta.language

@Composable
fun NexioNavHost(
    navController: NavHostController,
    startDestination: String = Screen.Home.route,
    hideBuiltInHeaders: Boolean = false,
    idleScreensaverVisible: Boolean = false,
    startupSplashVisible: Boolean = false,
    onModernHomeTrailerPlaybackStarted: () -> Unit = {},
    onModernHomeTrailerPlaybackActiveChanged: (Boolean) -> Unit = {},
    onModernHomeTrailerFullscreenActiveChanged: (Boolean) -> Unit = {},
    onDetailTrailerPlaybackActiveChanged: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    fun returnPlayerToLibrary() {
        val returnedToLibrary = navController.popBackStack(Screen.Library.route, inclusive = false)
        if (!returnedToLibrary) {
            navController.navigate(Screen.Library.route) {
                popUpTo(Screen.Player.route) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    fun isStreamToPlayer(from: String, to: String): Boolean {
        return from.startsWith("stream/") && to.startsWith("player/")
    }

    fun isPlayerToStream(from: String, to: String): Boolean {
        return from.startsWith("player/") && to.startsWith("stream/")
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = {
            val from = initialState.destination.route.orEmpty()
            val to = targetState.destination.route.orEmpty()
            val isAutoPlayNav = targetState.arguments
                ?.getString("autoPlayNav")
                ?.toBooleanStrictOrNull() == true
            if (isStreamToPlayer(from, to) && isAutoPlayNav) {
                EnterTransition.None
            } else {
                fadeIn(animationSpec = tween(350))
            }
        },
        exitTransition = {
            val from = initialState.destination.route.orEmpty()
            val to = targetState.destination.route.orEmpty()
            val isAutoPlayNav = targetState.arguments
                ?.getString("autoPlayNav")
                ?.toBooleanStrictOrNull() == true
            if (isStreamToPlayer(from, to) && isAutoPlayNav) {
                ExitTransition.None
            } else {
                fadeOut(animationSpec = tween(350))
            }
        },
        popEnterTransition = {
            val from = initialState.destination.route.orEmpty()
            val to = targetState.destination.route.orEmpty()
            val isAutoPlayNav = initialState.arguments
                ?.getString("autoPlayNav")
                ?.toBooleanStrictOrNull() == true
            if (isPlayerToStream(from, to) && isAutoPlayNav) {
                EnterTransition.None
            } else {
                fadeIn(animationSpec = tween(350))
            }
        },
        popExitTransition = {
            val from = initialState.destination.route.orEmpty()
            val to = targetState.destination.route.orEmpty()
            val isAutoPlayNav = initialState.arguments
                ?.getString("autoPlayNav")
                ?.toBooleanStrictOrNull() == true
            if (isPlayerToStream(from, to) && isAutoPlayNav) {
                ExitTransition.None
            } else {
                fadeOut(animationSpec = tween(350))
            }
        }
    ) {
        composable(Screen.LayoutSelection.route) {
            LayoutSelectionScreen(
                onContinue = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.LayoutSelection.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Home.route) {
            val homeViewModel: HomeViewModel = hiltViewModel()
            val homeUiState by homeViewModel.uiState.collectAsState()
            val homeScope = rememberCoroutineScope()
            HomeScreen(
                viewModel = homeViewModel,
                idleScreensaverVisible = idleScreensaverVisible,
                startupSplashVisible = startupSplashVisible,
                onModernHomeTrailerPlaybackStarted = onModernHomeTrailerPlaybackStarted,
                onModernHomeTrailerPlaybackActiveChanged = onModernHomeTrailerPlaybackActiveChanged,
                onModernHomeTrailerFullscreenActiveChanged = onModernHomeTrailerFullscreenActiveChanged,
                onNavigateToDetail = { itemId, itemType, addonBaseUrl ->
                    navController.navigate(Screen.Detail.createRoute(itemId, itemType, addonBaseUrl))
                },
                onPlayWithManualStreamSelection = { item ->
                    navController.navigate(
                        buildManualSelectionStreamRoute(
                            videoId = item.id,
                            contentType = item.apiType,
                            title = item.name,
                            poster = item.displayPoster,
                            backdrop = item.displayBackground,
                            logo = item.displayLogo,
                            contentId = item.id,
                            contentName = item.name,
                            genres = item.genres.takeIf { it.isNotEmpty() }?.joinToString(", "),
                            runtime = parseRuntimeMinutes(item.runtime),
                            originalLanguage = chooseNavOriginalLanguage(item),
                            returnToDetailOnBack = false
                        )
                    )
                },
                onContinueWatchingClick = { item ->
                    homeScope.launch {
                        homeViewModel.recordContinueWatchingRouteContextForPlayback(item)
                        val route = buildContinueWatchingStreamRouteWithHydration(
                            item = item,
                            deterministicAutoplayEnabled = homeUiState.deterministicAutoplayEnabled,
                            resolveRuntimeMinutes = { candidate ->
                                homeViewModel.resolveContinueWatchingRuntimeMinutes(candidate)
                            }
                        )
                        navController.navigate(route)
                    }
                },
                onContinueWatchingStartFromBeginning = { item ->
                    homeScope.launch {
                        homeViewModel.recordContinueWatchingRouteContextForPlayback(item)
                        val route = buildContinueWatchingStreamRouteWithHydration(
                            item = item,
                            deterministicAutoplayEnabled = homeUiState.deterministicAutoplayEnabled,
                            startFromBeginning = true,
                            resolveRuntimeMinutes = { candidate ->
                                homeViewModel.resolveContinueWatchingRuntimeMinutes(candidate)
                            }
                        )
                        navController.navigate(route)
                    }
                },
                onContinueWatchingManualStreamSelection = { item ->
                    homeScope.launch {
                        homeViewModel.recordContinueWatchingRouteContextForPlayback(item)
                        val route = buildContinueWatchingManualSelectionStreamRouteWithHydration(
                            item = item,
                            resolveRuntimeMinutes = { candidate ->
                                homeViewModel.resolveContinueWatchingRuntimeMinutes(candidate)
                            }
                        )
                        navController.navigate(route)
                    }
                },
                onNavigateToCatalogSeeAll = { catalogId, addonId, type ->
                    navController.navigate(Screen.CatalogSeeAll.createRoute(catalogId, addonId, type))
                }
            )
        }

        composable(
            route = Screen.Detail.route,
            arguments = listOf(
                navArgument("itemId") { type = NavType.StringType },
                navArgument("itemType") { type = NavType.StringType },
                navArgument("addonBaseUrl") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("detailSource") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("returnFocusSeason") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("returnFocusEpisode") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val detailArgs = requireNotNull(backStackEntry.arguments)
            val savedState = backStackEntry.savedStateHandle
            val returnFocusSeason by savedState.getStateFlow(
                "returnFocusSeason",
                detailArgs.getString("returnFocusSeason")?.toIntOrNull()
            ).collectAsState()
            val returnFocusEpisode by savedState.getStateFlow(
                "returnFocusEpisode",
                detailArgs.getString("returnFocusEpisode")?.toIntOrNull()
            ).collectAsState()
            MetaDetailsScreen(
                returnFocusSeason = returnFocusSeason,
                returnFocusEpisode = returnFocusEpisode,
                onReturnFocusConsumed = {
                    savedState["returnFocusSeason"] = null
                    savedState["returnFocusEpisode"] = null
                },
                onTrailerPlaybackActiveChanged = onDetailTrailerPlaybackActiveChanged,
                onBackPress = { navController.navigateDetailBack(detailArgs.getString("detailSource")) },
                onNavigateToCastDetail = { personId, personName, preferCrew, provider ->
                    val route = if (provider.equals("tvdb", ignoreCase = true)) {
                        Screen.CastDetail.createTvdbRoute(personId, personName, preferCrew)
                    } else {
                        Screen.CastDetail.createRoute(personId, personName, preferCrew)
                    }
                    navController.navigate(route)
                },
                onNavigateToOrganizationDetail = { entityId, entityName, kindName, discoverTypeName ->
                    navController.navigate(
                        Screen.OrganizationDetail.createRoute(
                            entityId = entityId,
                            entityName = entityName,
                            kind = enumValueOf(kindName),
                            discoverType = enumValueOf(discoverTypeName)
                        )
                    )
                },
                onNavigateToDetail = { itemId, itemType, addonBaseUrl ->
                    navController.navigate(Screen.Detail.createRoute(itemId, itemType, addonBaseUrl))
                },
                onPlayClick = { videoId, contentType, contentId, title, poster, backdrop, logo, season, episode, episodeName, genres, year, runtime, originalLanguage, deterministicAutoplay, streamVideoId ->
                    navController.navigate(
                        Screen.Stream.createRoute(
                            videoId = videoId,
                            streamVideoId = streamVideoId,
                            contentType = contentType,
                            title = title,
                            poster = poster,
                            backdrop = backdrop,
                            logo = logo,
                            season = season,
                            episode = episode,
                            episodeName = episodeName,
                            genres = genres,
                            year = year,
                            contentId = contentId,
                            contentName = title,
                            runtime = runtime,
                            originalLanguage = originalLanguage,
                            returnToDetailOnBack = deterministicAutoplay || contentType.equals("series", ignoreCase = true),
                            deterministicAutoplay = deterministicAutoplay
                        )
                    )
                },
                onPlayEpisodeWithManualStreamSelection = { videoId, contentType, contentId, title, poster, backdrop, logo, season, episode, episodeName, runtime, originalLanguage, streamVideoId ->
                    navController.navigate(
                        buildManualSelectionStreamRoute(
                            videoId = videoId,
                            streamVideoId = streamVideoId,
                            contentType = contentType,
                            title = title,
                            poster = poster,
                            backdrop = backdrop,
                            logo = logo,
                            season = season,
                            episode = episode,
                            episodeName = episodeName,
                            contentId = contentId,
                            contentName = title,
                            runtime = runtime,
                            originalLanguage = originalLanguage,
                            returnToDetailOnBack = true
                        )
                    )
                }
            )
        }

        composable(
            route = Screen.Stream.route,
            arguments = listOf(
                navArgument("videoId") { type = NavType.StringType },
                navArgument("contentType") { type = NavType.StringType },
                navArgument("title") { type = NavType.StringType },
                navArgument("poster") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("backdrop") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("logo") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("season") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("episode") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("episodeName") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("genres") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("year") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("contentId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("contentName") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("runtime") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("originalLanguage") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("manualSelection") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = "false"
                },
                navArgument("returnToDetailOnBack") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = "false"
                },
                navArgument("startFromBeginning") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = "false"
                },
                navArgument("deterministicAutoplay") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = "false"
                },
                navArgument("resumePositionMs") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("resumeDurationMs") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("resumeProgressPercent") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("resumeLastWatchedMs") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("resumeSource") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("addonBaseUrl") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("streamVideoId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val streamArgs = requireNotNull(backStackEntry.arguments)
            val returnToDetailOnBack = streamArgs
                .getString("returnToDetailOnBack")
                ?.toBooleanStrictOrNull() == true
            val deterministicAutoplay = streamArgs
                .getString("deterministicAutoplay")
                ?.toBooleanStrictOrNull() == true
            val startFromBeginning = streamArgs
                .getString("startFromBeginning")
                ?.toBooleanStrictOrNull() == true
            StreamScreen(
                onBackPress = {
                    val streamContentType = streamArgs.getString("contentType").orEmpty()
                    val streamContentId = streamArgs.getString("contentId").orEmpty()
                    val season = streamArgs.getString("season")?.toIntOrNull()
                    val episode = streamArgs.getString("episode")?.toIntOrNull()
                    if (returnToDetailOnBack && streamContentId.isNotBlank()) {
                        val detailEntry = runCatching {
                            navController.getBackStackEntry(Screen.Detail.route)
                        }.getOrNull()
                        if (detailEntry != null) {
                            detailEntry.savedStateHandle["returnFocusSeason"] = season
                            detailEntry.savedStateHandle["returnFocusEpisode"] = episode
                            navController.popBackStack(Screen.Detail.route, inclusive = false)
                        } else {
                            navController.navigate(
                                Screen.Detail.createRoute(
                                    itemId = streamContentId,
                                    itemType = streamContentType,
                                    addonBaseUrl = null,
                                    returnFocusSeason = season,
                                    returnFocusEpisode = episode
                                )
                            ) {
                                popUpTo(Screen.Stream.route) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    } else {
                        navController.popBackStack()
                    }
                },
                onStreamSelected = { playbackInfo ->
                    playbackInfo.url?.let { url ->
                        navController.navigate(
                            Screen.Player.createRoute(
                                streamUrl = url,
                                title = playbackInfo.title,
                                streamName = playbackInfo.streamName,
                                serviceKey = playbackInfo.serviceKey,
                                year = playbackInfo.year,
                                headers = playbackInfo.headers,
                                contentId = playbackInfo.contentId,
                                contentType = playbackInfo.contentType,
                                contentName = playbackInfo.contentName,
                                originalLanguage = playbackInfo.originalLanguage,
                                poster = playbackInfo.displayPoster,
                                backdrop = playbackInfo.displayBackdrop,
                                logo = playbackInfo.displayLogo,
                                videoId = playbackInfo.videoId,
                                season = playbackInfo.season,
                                episode = playbackInfo.episode,
                                episodeTitle = playbackInfo.episodeTitle,
                                bingeGroup = playbackInfo.bingeGroup,
                                rememberedAudioLanguage = playbackInfo.rememberedAudioLanguage,
                                rememberedAudioName = playbackInfo.rememberedAudioName,
                                playerBackend = playbackInfo.playerBackend.name,
                                autoPlayNav = false,
                                returnToDetailOnBack = returnToDetailOnBack,
                                deterministicAutoplay = deterministicAutoplay,
                                filename = playbackInfo.filename,
                                videoHash = playbackInfo.videoHash,
                                videoSize = playbackInfo.videoSize,
                                startFromBeginning = startFromBeginning,
                                launchSource = PlayerLaunchSource.STREAM,
                                resumePositionMs = playbackInfo.resumePositionMs,
                                resumeDurationMs = playbackInfo.resumeDurationMs,
                                resumeProgressPercent = playbackInfo.resumeProgressPercent,
                                resumeLastWatchedMs = playbackInfo.resumeLastWatchedMs,
                                resumeSource = playbackInfo.resumeSource,
                                addonBaseUrl = playbackInfo.addonBaseUrl
                            )
                        )
                    }
                },
                onAutoPlayResolved = { playbackInfo ->
                    playbackInfo.url?.let { url ->
                        navController.navigate(
                            Screen.Player.createRoute(
                                streamUrl = url,
                                title = playbackInfo.title,
                                streamName = playbackInfo.streamName,
                                serviceKey = playbackInfo.serviceKey,
                                year = playbackInfo.year,
                                headers = playbackInfo.headers,
                                contentId = playbackInfo.contentId,
                                contentType = playbackInfo.contentType,
                                contentName = playbackInfo.contentName,
                                originalLanguage = playbackInfo.originalLanguage,
                                poster = playbackInfo.displayPoster,
                                backdrop = playbackInfo.displayBackdrop,
                                logo = playbackInfo.displayLogo,
                                videoId = playbackInfo.videoId,
                                season = playbackInfo.season,
                                episode = playbackInfo.episode,
                                episodeTitle = playbackInfo.episodeTitle,
                                bingeGroup = playbackInfo.bingeGroup,
                                rememberedAudioLanguage = playbackInfo.rememberedAudioLanguage,
                                rememberedAudioName = playbackInfo.rememberedAudioName,
                                playerBackend = playbackInfo.playerBackend.name,
                                autoPlayNav = true,
                                returnToDetailOnBack = returnToDetailOnBack,
                                deterministicAutoplay = deterministicAutoplay,
                                filename = playbackInfo.filename,
                                videoHash = playbackInfo.videoHash,
                                videoSize = playbackInfo.videoSize,
                                startFromBeginning = startFromBeginning,
                                launchSource = PlayerLaunchSource.STREAM,
                                resumePositionMs = playbackInfo.resumePositionMs,
                                resumeDurationMs = playbackInfo.resumeDurationMs,
                                resumeProgressPercent = playbackInfo.resumeProgressPercent,
                                resumeLastWatchedMs = playbackInfo.resumeLastWatchedMs,
                                resumeSource = playbackInfo.resumeSource,
                                addonBaseUrl = playbackInfo.addonBaseUrl
                            )
                        ) {
                            popUpTo(Screen.Stream.route) { inclusive = true }
                        }
                    }
                },
                onDeterministicAutoplayFailed = { message ->
                    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
                    if (returnToDetailOnBack && streamArgs.getString("contentId").orEmpty().isNotBlank()) {
                        val season = streamArgs.getString("season")?.toIntOrNull()
                        val episode = streamArgs.getString("episode")?.toIntOrNull()
                        val detailEntry = runCatching { navController.getBackStackEntry(Screen.Detail.route) }.getOrNull()
                        if (detailEntry != null) {
                            detailEntry.savedStateHandle["returnFocusSeason"] = season
                            detailEntry.savedStateHandle["returnFocusEpisode"] = episode
                            navController.popBackStack(Screen.Detail.route, inclusive = false)
                        } else {
                            navController.popBackStack()
                        }
                    } else {
                        navController.popBackStack()
                    }
                }
            )
        }

        composable(
            route = Screen.Player.route,
            arguments = listOf(
                navArgument("streamUrl") { type = NavType.StringType },
                navArgument("title") { type = NavType.StringType },
                navArgument("streamName") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("year") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("headers") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("contentId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("contentType") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("contentName") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("originalLanguage") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("poster") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("backdrop") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("logo") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("videoId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("season") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("episode") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("episodeTitle") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("bingeGroup") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("rememberedAudioLanguage") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("rememberedAudioName") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("autoPlayNav") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = "false"
                },
                navArgument("returnToDetailOnBack") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = "false"
                },
                navArgument("deterministicAutoplay") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = "false"
                },
                navArgument("filename") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("videoHash") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("videoSize") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("startFromBeginning") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = "false"
                },
                navArgument("launchSource") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = PlayerLaunchSource.STREAM.routeValue
                },
                navArgument("resumePositionMs") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("resumeDurationMs") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("resumeProgressPercent") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("resumeLastWatchedMs") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("resumeSource") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("addonBaseUrl") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            PlayerScreen(
                onBackPress = {
                    val args = requireNotNull(backStackEntry.arguments)
                    val launchSource = PlayerLaunchSource.from(args.getString("launchSource"))
                    if (
                        shouldReturnDirectLibraryPlaybackToLibrary(
                            launchSource = launchSource,
                            contentId = args.getString("contentId"),
                            videoId = args.getString("videoId")
                        )
                    ) {
                        return@PlayerScreen returnPlayerToLibrary()
                    }
                    val returnedToStream = navController.popBackStack(Screen.Stream.route, inclusive = false)
                    if (!returnedToStream) {
                        val returnToDetailOnBack = args.getString("returnToDetailOnBack")
                            ?.toBooleanStrictOrNull() == true
                        val contentType = args.getString("contentType").orEmpty()
                        val contentId = args.getString("contentId").orEmpty()
                        if (
                            returnToDetailOnBack &&
                            contentType.equals("series", ignoreCase = true) &&
                            contentId.isNotBlank()
                        ) {
                            navController.navigate(
                                Screen.Detail.createRoute(
                                    itemId = contentId,
                                    itemType = contentType,
                                    addonBaseUrl = null,
                                    returnFocusSeason = args.getString("season")?.toIntOrNull(),
                                    returnFocusEpisode = args.getString("episode")?.toIntOrNull()
                                )
                            ) {
                                popUpTo(Screen.Stream.route) { inclusive = true }
                                launchSingleTop = true
                            }
                        } else {
                            navController.popBackStack()
                        }
                    }
                },
                onPlaybackEnded = { nextVideoId, nextSeason, nextEpisode ->
                    val args = requireNotNull(backStackEntry.arguments)
                    val launchSource = PlayerLaunchSource.from(args.getString("launchSource"))
                    if (
                        shouldReturnDirectLibraryPlaybackToLibrary(
                            launchSource = launchSource,
                            contentId = args.getString("contentId"),
                            videoId = args.getString("videoId")
                        )
                    ) {
                        return@PlayerScreen returnPlayerToLibrary()
                    }
                    val contentType = args.getString("contentType").orEmpty()
                    val contentId = args.getString("contentId").orEmpty()
                    val returnToDetailOnBack = args.getString("returnToDetailOnBack")
                        ?.toBooleanStrictOrNull() == true
                    if (nextVideoId != null && nextSeason != null && nextEpisode != null) {
                        val route = Screen.Stream.createRoute(
                            videoId = nextVideoId,
                            contentType = contentType,
                            title = args.getString("title").orEmpty(),
                            poster = args.getString("poster"),
                            backdrop = args.getString("backdrop"),
                            logo = args.getString("logo"),
                            season = nextSeason,
                            episode = nextEpisode,
                            episodeName = null,
                            genres = null,
                            year = args.getString("year"),
                            contentId = contentId.takeIf { it.isNotBlank() },
                            contentName = args.getString("contentName"),
                            runtime = parseRuntimeMinutes(args.getString("runtime")),
                            returnToDetailOnBack = returnToDetailOnBack
                        )
                        navController.navigate(route) {
                            popUpTo(Screen.Detail.route) { inclusive = false }
                            launchSingleTop = true
                        }
                    } else {
                        // No next episode: return to detail if it's still on the stack.
                        val poppedToDetail = navController.popBackStack(Screen.Detail.route, inclusive = false)
                        if (!poppedToDetail) {
                            navController.popBackStack(Screen.Stream.route, inclusive = true)
                        }
                    }
                },
                onPlaybackErrorBack = {
                    val args = requireNotNull(backStackEntry.arguments)
                    val launchSource = PlayerLaunchSource.from(args.getString("launchSource"))
                    val deterministicAutoplay = args.getString("deterministicAutoplay")
                        ?.toBooleanStrictOrNull() == true
                    if (
                        shouldReturnDirectLibraryPlaybackToLibrary(
                            launchSource = launchSource,
                            contentId = args.getString("contentId"),
                            videoId = args.getString("videoId")
                        )
                    ) {
                        return@PlayerScreen returnPlayerToLibrary()
                    }
                    if (deterministicAutoplay) {
                        val contentType = args.getString("contentType").orEmpty()
                        val contentId = args.getString("contentId").orEmpty()
                        val season = args.getString("season")?.toIntOrNull()
                        val episode = args.getString("episode")?.toIntOrNull()
                        if (contentId.isNotBlank()) {
                            val detailEntry = runCatching {
                                navController.getBackStackEntry(Screen.Detail.route)
                            }.getOrNull()
                            if (detailEntry != null) {
                                detailEntry.savedStateHandle["returnFocusSeason"] = season
                                detailEntry.savedStateHandle["returnFocusEpisode"] = episode
                                navController.popBackStack(Screen.Detail.route, inclusive = false)
                            } else {
                                navController.popBackStack()
                            }
                        } else {
                            navController.popBackStack()
                        }
                        return@PlayerScreen
                    }
                    val returnedToStream = navController.popBackStack(Screen.Stream.route, inclusive = false)
                    if (!returnedToStream) {
                        val videoId = args.getString("videoId").orEmpty()
                        val contentType = args.getString("contentType").orEmpty()
                        val title = args.getString("title").orEmpty()

                        if (videoId.isBlank() || contentType.isBlank() || title.isBlank()) {
                            navController.popBackStack()
                        } else {
                            val route = Screen.Stream.createRoute(
                                videoId = videoId,
                                contentType = contentType,
                                title = title,
                                poster = args.getString("poster"),
                                backdrop = args.getString("backdrop"),
                                logo = args.getString("logo"),
                                season = args.getString("season")?.toIntOrNull(),
                                episode = args.getString("episode")?.toIntOrNull(),
                                episodeName = args.getString("episodeTitle"),
                                genres = null,
                                year = args.getString("year"),
                                contentId = args.getString("contentId"),
                                contentName = args.getString("contentName"),
                                runtime = parseRuntimeMinutes(args.getString("runtime")),
                                manualSelection = true,
                                returnToDetailOnBack = args.getString("returnToDetailOnBack")
                                    ?.toBooleanStrictOrNull() == true
                            )

                            navController.navigate(route) {
                                popUpTo(Screen.Stream.route) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    }
                }
            )
        }

        composable(Screen.Search.route) {
            SearchScreen(
                onNavigateToDetail = { itemId, itemType, addonBaseUrl ->
                    navController.navigate(
                        Screen.Detail.createRoute(
                            itemId = itemId,
                            itemType = itemType,
                            addonBaseUrl = addonBaseUrl,
                            detailSource = "search"
                        )
                    )
                },
                onPlayWithManualStreamSelection = { item, _ ->
                    navController.navigate(
                        buildManualSelectionStreamRoute(
                            videoId = item.id,
                            contentType = item.apiType,
                            title = item.name,
                            poster = item.displayPoster,
                            backdrop = item.displayBackground,
                            logo = item.displayLogo,
                            contentId = item.id,
                            contentName = item.name,
                            genres = item.genres.takeIf { it.isNotEmpty() }?.joinToString(", "),
                            runtime = parseRuntimeMinutes(item.runtime),
                            originalLanguage = chooseNavOriginalLanguage(item),
                            returnToDetailOnBack = false
                        )
                    )
                },
                onNavigateToSeeAll = { catalogId, addonId, type ->
                    navController.navigate(Screen.CatalogSeeAll.createRoute(catalogId, addonId, type))
                },
                onNavigateToCastDetail = { personId, personName ->
                    navController.navigate(Screen.CastDetail.createRoute(personId, personName))
                },
                onOpenDiscover = { navController.navigate(Screen.Discover.route) }
            )
        }

        composable(Screen.Discover.route) {
            DiscoverScreen(
                onNavigateToDetail = { itemId, itemType, addonBaseUrl ->
                    navController.navigate(
                        Screen.Detail.createRoute(
                            itemId = itemId,
                            itemType = itemType,
                            addonBaseUrl = addonBaseUrl,
                            detailSource = "search"
                        )
                    )
                },
                onPlayWithManualStreamSelection = { item, _ ->
                    navController.navigate(
                        buildManualSelectionStreamRoute(
                            videoId = item.id,
                            contentType = item.apiType,
                            title = item.name,
                            poster = item.displayPoster,
                            backdrop = item.displayBackground,
                            logo = item.displayLogo,
                            contentId = item.id,
                            contentName = item.name,
                            genres = item.genres.takeIf { it.isNotEmpty() }?.joinToString(", "),
                            runtime = parseRuntimeMinutes(item.runtime),
                            originalLanguage = chooseNavOriginalLanguage(item),
                            returnToDetailOnBack = false
                        )
                    )
                }
            )
        }

        composable(Screen.Library.route) {
            LibraryScreen(
                showBuiltInHeader = !hideBuiltInHeaders,
                onOpenEntry = { entry ->
                    val directPlaybackUrl = entry.directPlaybackUrl
                    if (!directPlaybackUrl.isNullOrBlank()) {
                        navController.navigate(
                            Screen.Player.createRoute(
                                streamUrl = directPlaybackUrl,
                                title = entry.name,
                                streamName = entry.playbackStreamName ?: entry.name,
                                contentId = entry.id,
                                contentType = entry.type,
                                contentName = entry.name,
                                poster = entry.displayPoster,
                                backdrop = entry.displayBackground,
                                logo = entry.displayLogo,
                                videoId = entry.id,
                                filename = entry.playbackFilename,
                                videoSize = entry.playbackSizeBytes,
                                headers = entry.playbackHeaders,
                                launchSource = PlayerLaunchSource.LIBRARY_DIRECT,
                                addonBaseUrl = entry.addonBaseUrl
                            )
                        )
                    } else {
                        navController.navigate(
                            Screen.Detail.createRoute(
                                itemId = entry.id,
                                itemType = entry.type,
                                addonBaseUrl = entry.addonBaseUrl,
                                detailSource = "library"
                            )
                        )
                    }
                }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                showBuiltInHeader = !hideBuiltInHeaders,
                onNavigateToCatalogs = { navController.navigate(Screen.Catalogs.route) },
                onNavigateToAuthQrSignIn = { navController.navigate(Screen.AuthQrSignIn.route) }
            )
        }

        composable(Screen.Catalogs.route) {
            CatalogOrderScreen(
                onBackPress = { navController.popBackStack() }
            )
        }

        composable(Screen.Trakt.route) {
            TraktScreen(
                onBackPress = { navController.popBackStack() }
            )
        }

        composable(Screen.TmdbSettings.route) {
            TmdbSettingsScreen(
                onBackPress = { navController.popBackStack() }
            )
        }

        composable(Screen.ThemeSettings.route) {
            ThemeSettingsScreen(
                onBackPress = { navController.popBackStack() }
            )
        }

        composable(Screen.PlaybackSettings.route) {
            PlaybackSettingsScreen(
                onBackPress = { navController.popBackStack() }
            )
        }

        composable(Screen.About.route) {
            AboutScreen(
                onBackPress = { navController.popBackStack() }
            )
        }

        composable(Screen.AddonManager.route) {
            AddonManagerScreen(
                showBuiltInHeader = !hideBuiltInHeaders,
                onNavigateToCatalogOrder = { navController.navigate(Screen.CatalogOrder.route) }
            )
        }

        composable(Screen.CatalogOrder.route) {
            CatalogOrderScreen(
                onBackPress = { navController.popBackStack() }
            )
        }

        composable(Screen.Account.route) {
            AuthQrSignInScreen(
                onBackPress = { navController.popBackStack() }
            )
        }

        composable(Screen.AuthSignIn.route) {
            AuthSignInScreen(
                onBackPress = { navController.popBackStack() },
                onNavigateToQrSignIn = { navController.navigate(Screen.AuthQrSignIn.route) },
                onSuccess = { navController.popBackStack() }
            )
        }

        composable(Screen.AuthQrSignIn.route) {
            AuthQrSignInScreen(
                onBackPress = { navController.popBackStack() }
            )
        }

        composable(Screen.LayoutSettings.route) {
            LayoutSettingsScreen(
                onBackPress = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.CatalogSeeAll.route,
            arguments = listOf(
                navArgument("catalogId") { type = NavType.StringType },
                navArgument("addonId") { type = NavType.StringType },
                navArgument("type") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val args = requireNotNull(backStackEntry.arguments)
            val catalogId = args.getString("catalogId").orEmpty()
            val addonId = args.getString("addonId").orEmpty()
            val type = args.getString("type").orEmpty()
            CatalogSeeAllScreen(
                catalogId = catalogId,
                addonId = addonId,
                type = type,
                onNavigateToDetail = { itemId, itemType, addonBaseUrl ->
                    navController.navigate(Screen.Detail.createRoute(itemId, itemType, addonBaseUrl))
                },
                onBackPress = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.AndroidTvFeed.route,
            arguments = listOf(
                navArgument("feedKey") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val args = requireNotNull(backStackEntry.arguments)
            val feedKey = args.getString("feedKey").orEmpty()
            AndroidTvFeedBrowserScreen(
                onNavigateToDetail = { itemId, itemType, addonBaseUrl ->
                    navController.navigate(Screen.Detail.createRoute(itemId, itemType, addonBaseUrl))
                },
                onBackPress = {
                    if (!navController.popBackStack()) {
                        navController.navigate(Screen.Home.route)
                    }
                }
            )
        }

        composable(
            route = Screen.CastDetail.route,
            arguments = listOf(
                navArgument("personId") { type = NavType.StringType },
                navArgument("personName") { type = NavType.StringType },
                navArgument("preferCrew") {
                    type = NavType.BoolType
                    defaultValue = false
                },
                navArgument("provider") {
                    type = NavType.StringType
                    defaultValue = "tmdb"
                }
            )
        ) {
            CastDetailScreen(
                onBackPress = { navController.popBackStack() },
                onNavigateToDetail = { itemId, itemType, addonBaseUrl ->
                    navController.navigate(Screen.Detail.createRoute(itemId, itemType, addonBaseUrl))
                }
            )
        }

        composable(
            route = Screen.OrganizationDetail.route,
            arguments = listOf(
                navArgument("entityId") { type = NavType.StringType },
                navArgument("entityName") { type = NavType.StringType },
                navArgument("kind") { type = NavType.StringType },
                navArgument("discoverType") { type = NavType.StringType }
            )
        ) {
            OrganizationDetailScreen(
                onBackPress = { navController.popBackStack() },
                onNavigateToDetail = { itemId, itemType, addonBaseUrl ->
                    navController.navigate(Screen.Detail.createRoute(itemId, itemType, addonBaseUrl))
                }
            )
        }
    }
}

internal fun continueWatchingRuntimeMinutes(item: ContinueWatchingItem): Int? {
    return when (item) {
        is ContinueWatchingItem.InProgress -> runtimeMinutesFromDurationMs(item.progress.duration)
        is ContinueWatchingItem.NextUp -> parseRuntimeMinutes(item.info.displayMetadata?.runtime)
    }
}

internal fun buildManualSelectionStreamRoute(
    videoId: String,
    streamVideoId: String? = null,
    contentType: String,
    title: String,
    poster: String? = null,
    backdrop: String? = null,
    logo: String? = null,
    season: Int? = null,
    episode: Int? = null,
    episodeName: String? = null,
    genres: String? = null,
    year: String? = null,
    contentId: String? = null,
    contentName: String? = null,
    runtime: Int? = null,
    originalLanguage: String? = null,
    returnToDetailOnBack: Boolean,
    resumePositionMs: Long? = null,
    resumeDurationMs: Long? = null,
    resumeProgressPercent: Float? = null,
    resumeLastWatchedMs: Long? = null,
    resumeSource: String? = null,
    addonBaseUrl: String? = null
): String {
    return Screen.Stream.createRoute(
        videoId = videoId,
        streamVideoId = streamVideoId,
        contentType = contentType,
        title = title,
        poster = poster,
        backdrop = backdrop,
        logo = logo,
        season = season,
        episode = episode,
        episodeName = episodeName,
        genres = genres,
        year = year,
        contentId = contentId,
        contentName = contentName,
        runtime = runtime,
        originalLanguage = originalLanguage,
        manualSelection = true,
        returnToDetailOnBack = returnToDetailOnBack,
        deterministicAutoplay = false,
        resumePositionMs = resumePositionMs,
        resumeDurationMs = resumeDurationMs,
        resumeProgressPercent = resumeProgressPercent,
        resumeLastWatchedMs = resumeLastWatchedMs,
        resumeSource = resumeSource,
        addonBaseUrl = addonBaseUrl
    )
}

internal fun buildContinueWatchingStreamRoute(
    item: ContinueWatchingItem,
    deterministicAutoplayEnabled: Boolean,
    startFromBeginning: Boolean = false
): String {
    return when (item) {
        is ContinueWatchingItem.InProgress -> Screen.Stream.createRoute(
            videoId = item.progress.videoId,
            streamVideoId = item.streamFetchVideoId,
            contentType = item.progress.contentType,
            title = item.progress.name,
            poster = item.displayMetadata().displayPoster,
            backdrop = item.displayMetadata().displayBackdrop,
            logo = item.displayMetadata().displayLogo,
            season = item.progress.season,
            episode = item.progress.episode,
            episodeName = item.progress.episodeTitle,
            genres = null,
            year = null,
            contentId = item.progress.contentId,
            contentName = item.progress.name,
            runtime = continueWatchingRuntimeMinutes(item),
            originalLanguage = item.displayMetadata().originalLanguage,
            returnToDetailOnBack = deterministicAutoplayEnabled ||
                item.progress.contentType.equals("series", ignoreCase = true),
            startFromBeginning = startFromBeginning,
            deterministicAutoplay = deterministicAutoplayEnabled,
            resumePositionMs = item.progress.position.takeUnless { startFromBeginning },
            resumeDurationMs = item.progress.duration.takeUnless { startFromBeginning },
            resumeProgressPercent = item.progress.progressPercent.takeUnless { startFromBeginning },
            resumeLastWatchedMs = item.progress.lastWatched.takeUnless { startFromBeginning },
            resumeSource = item.progress.source.takeUnless { startFromBeginning },
            addonBaseUrl = item.progress.addonBaseUrl
        )

        is ContinueWatchingItem.NextUp -> Screen.Stream.createRoute(
            videoId = item.info.videoId,
            streamVideoId = item.info.streamFetchVideoId,
            contentType = item.info.contentType,
            title = item.info.name,
            poster = item.displayMetadata().displayPoster,
            backdrop = item.displayMetadata().displayBackdrop,
            logo = item.displayMetadata().displayLogo,
            season = item.info.season,
            episode = item.info.episode,
            episodeName = item.info.episodeTitle,
            genres = null,
            year = null,
            contentId = item.info.contentId,
            contentName = item.info.name,
            runtime = continueWatchingRuntimeMinutes(item),
            originalLanguage = item.displayMetadata().originalLanguage,
            returnToDetailOnBack = deterministicAutoplayEnabled ||
                item.info.contentType.equals("series", ignoreCase = true),
            startFromBeginning = startFromBeginning,
            deterministicAutoplay = deterministicAutoplayEnabled
        )
    }
}

internal suspend fun buildContinueWatchingStreamRouteWithHydration(
    item: ContinueWatchingItem,
    deterministicAutoplayEnabled: Boolean,
    startFromBeginning: Boolean = false,
    resolveRuntimeMinutes: suspend (ContinueWatchingItem) -> Int?
): String {
    val hydratedItem = item.withHydratedRuntimeMinutes(
        continueWatchingRuntimeMinutes(item) ?: resolveRuntimeMinutes(item)
    )
    return buildContinueWatchingStreamRoute(
        item = hydratedItem,
        deterministicAutoplayEnabled = deterministicAutoplayEnabled,
        startFromBeginning = startFromBeginning
    )
}

internal fun buildContinueWatchingManualSelectionStreamRoute(
    item: ContinueWatchingItem
): String {
    return when (item) {
        is ContinueWatchingItem.InProgress -> buildManualSelectionStreamRoute(
            videoId = item.progress.videoId,
            streamVideoId = item.streamFetchVideoId,
            contentType = item.progress.contentType,
            title = item.progress.name,
            poster = item.displayMetadata().displayPoster,
            backdrop = item.displayMetadata().displayBackdrop,
            logo = item.displayMetadata().displayLogo,
            season = item.progress.season,
            episode = item.progress.episode,
            episodeName = item.progress.episodeTitle,
            contentId = item.progress.contentId,
            contentName = item.progress.name,
            runtime = continueWatchingRuntimeMinutes(item),
            originalLanguage = item.displayMetadata().originalLanguage,
            returnToDetailOnBack = item.progress.contentType.equals("series", ignoreCase = true),
            resumePositionMs = item.progress.position,
            resumeDurationMs = item.progress.duration,
            resumeProgressPercent = item.progress.progressPercent,
            resumeLastWatchedMs = item.progress.lastWatched,
            resumeSource = item.progress.source,
            addonBaseUrl = item.progress.addonBaseUrl
        )

        is ContinueWatchingItem.NextUp -> buildManualSelectionStreamRoute(
            videoId = item.info.videoId,
            streamVideoId = item.info.streamFetchVideoId,
            contentType = item.info.contentType,
            title = item.info.name,
            poster = item.displayMetadata().displayPoster,
            backdrop = item.displayMetadata().displayBackdrop,
            logo = item.displayMetadata().displayLogo,
            season = item.info.season,
            episode = item.info.episode,
            episodeName = item.info.episodeTitle,
            contentId = item.info.contentId,
            contentName = item.info.name,
            runtime = continueWatchingRuntimeMinutes(item),
            originalLanguage = item.displayMetadata().originalLanguage,
            returnToDetailOnBack = item.info.contentType.equals("series", ignoreCase = true)
        )
    }
}

internal suspend fun buildContinueWatchingManualSelectionStreamRouteWithHydration(
    item: ContinueWatchingItem,
    resolveRuntimeMinutes: suspend (ContinueWatchingItem) -> Int?
): String {
    val hydratedItem = item.withHydratedRuntimeMinutes(
        continueWatchingRuntimeMinutes(item) ?: resolveRuntimeMinutes(item)
    )
    return buildContinueWatchingManualSelectionStreamRoute(hydratedItem)
}

internal fun runtimeMinutesFromDurationMs(durationMs: Long?): Int? {
    val value = durationMs?.takeIf { it > 0L } ?: return null
    return ((value + 59_999L) / 60_000L).toInt()
}
