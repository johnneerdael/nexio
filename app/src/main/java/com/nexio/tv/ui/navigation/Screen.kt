package com.nexio.tv.ui.navigation

import com.nexio.tv.domain.model.MetaCompanyKind
import com.nexio.tv.domain.model.OrganizationDiscoverType
import com.nexio.tv.ui.screens.player.PlayerLaunchSource
import java.net.URLEncoder

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object ProfileSelection : Screen("profile_selection")
    data object Detail : Screen("detail/{itemId}/{itemType}?addonBaseUrl={addonBaseUrl}&detailSource={detailSource}&returnFocusSeason={returnFocusSeason}&returnFocusEpisode={returnFocusEpisode}") {
        private fun encode(value: String): String =
            URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        fun createRoute(
            itemId: String,
            itemType: String,
            addonBaseUrl: String? = null,
            detailSource: String? = null,
            returnFocusSeason: Int? = null,
            returnFocusEpisode: Int? = null
        ): String {
            val encodedItemId = encode(itemId)
            val encodedItemType = encode(itemType)
            val encodedAddon = addonBaseUrl?.let { encode(it) } ?: ""
            val encodedSource = detailSource?.let { encode(it) } ?: ""
            return "detail/$encodedItemId/$encodedItemType?addonBaseUrl=$encodedAddon&detailSource=$encodedSource&returnFocusSeason=${returnFocusSeason ?: ""}&returnFocusEpisode=${returnFocusEpisode ?: ""}"
        }
    }
    data object Stream : Screen("stream/{videoId}/{contentType}/{title}?poster={poster}&backdrop={backdrop}&logo={logo}&season={season}&episode={episode}&episodeName={episodeName}&genres={genres}&year={year}&contentId={contentId}&contentName={contentName}&runtime={runtime}&originalLanguage={originalLanguage}&manualSelection={manualSelection}&returnToDetailOnBack={returnToDetailOnBack}&startFromBeginning={startFromBeginning}&deterministicAutoplay={deterministicAutoplay}&resumePositionMs={resumePositionMs}&resumeDurationMs={resumeDurationMs}&resumeProgressPercent={resumeProgressPercent}&resumeLastWatchedMs={resumeLastWatchedMs}&resumeSource={resumeSource}") {
        private fun encode(value: String): String =
            URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        fun createRoute(
            videoId: String,
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
            manualSelection: Boolean = false,
            returnToDetailOnBack: Boolean = false,
            startFromBeginning: Boolean = false,
            deterministicAutoplay: Boolean = false,
            resumePositionMs: Long? = null,
            resumeDurationMs: Long? = null,
            resumeProgressPercent: Float? = null,
            resumeLastWatchedMs: Long? = null,
            resumeSource: String? = null
        ): String {
            val encodedVideoId = encode(videoId)
            val encodedContentTypePath = encode(contentType)
            val encodedTitle = encode(title)
            val encodedPoster = poster?.let { encode(it) } ?: ""
            val encodedBackdrop = backdrop?.let { encode(it) } ?: ""
            val encodedLogo = logo?.let { encode(it) } ?: ""
            val encodedEpisodeName = episodeName?.let { encode(it) } ?: ""
            val encodedGenres = genres?.let { encode(it) } ?: ""
            val encodedYear = year?.let { encode(it) } ?: ""
            val encodedContentId = contentId?.let { encode(it) } ?: ""
            val encodedContentName = contentName?.let { encode(it) } ?: ""
            val encodedOriginalLanguage = originalLanguage?.let { encode(it) } ?: ""
            val encodedResumeSource = resumeSource?.let { encode(it) } ?: ""
            return "stream/$encodedVideoId/$encodedContentTypePath/$encodedTitle?poster=$encodedPoster&backdrop=$encodedBackdrop&logo=$encodedLogo&season=${season ?: ""}&episode=${episode ?: ""}&episodeName=$encodedEpisodeName&genres=$encodedGenres&year=$encodedYear&contentId=$encodedContentId&contentName=$encodedContentName&runtime=${runtime ?: ""}&originalLanguage=$encodedOriginalLanguage&manualSelection=$manualSelection&returnToDetailOnBack=$returnToDetailOnBack&startFromBeginning=$startFromBeginning&deterministicAutoplay=$deterministicAutoplay&resumePositionMs=${resumePositionMs ?: ""}&resumeDurationMs=${resumeDurationMs ?: ""}&resumeProgressPercent=${resumeProgressPercent ?: ""}&resumeLastWatchedMs=${resumeLastWatchedMs ?: ""}&resumeSource=$encodedResumeSource"
        }
    }
    data object Player : Screen("player/{streamUrl}/{title}?streamName={streamName}&serviceKey={serviceKey}&year={year}&headers={headers}&contentId={contentId}&contentType={contentType}&contentName={contentName}&originalLanguage={originalLanguage}&poster={poster}&backdrop={backdrop}&logo={logo}&videoId={videoId}&season={season}&episode={episode}&episodeTitle={episodeTitle}&bingeGroup={bingeGroup}&rememberedAudioLanguage={rememberedAudioLanguage}&rememberedAudioName={rememberedAudioName}&playerBackend={playerBackend}&autoPlayNav={autoPlayNav}&returnToDetailOnBack={returnToDetailOnBack}&deterministicAutoplay={deterministicAutoplay}&filename={filename}&videoHash={videoHash}&videoSize={videoSize}&startFromBeginning={startFromBeginning}&launchSource={launchSource}&resumePositionMs={resumePositionMs}&resumeDurationMs={resumeDurationMs}&resumeProgressPercent={resumeProgressPercent}&resumeLastWatchedMs={resumeLastWatchedMs}&resumeSource={resumeSource}") {
        private fun encode(value: String): String =
            URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        fun createRoute(
            streamUrl: String,
            title: String,
            streamName: String? = null,
            serviceKey: String? = null,
            year: String? = null,
            headers: Map<String, String>? = null,
            contentId: String? = null,
            contentType: String? = null,
            contentName: String? = null,
            originalLanguage: String? = null,
            poster: String? = null,
            backdrop: String? = null,
            logo: String? = null,
            videoId: String? = null,
            season: Int? = null,
            episode: Int? = null,
            episodeTitle: String? = null,
            bingeGroup: String? = null,
            rememberedAudioLanguage: String? = null,
            rememberedAudioName: String? = null,
            playerBackend: String = "INTERNAL",
            autoPlayNav: Boolean = false,
            returnToDetailOnBack: Boolean = false,
            deterministicAutoplay: Boolean = false,
            filename: String? = null,
            videoHash: String? = null,
            videoSize: Long? = null,
            startFromBeginning: Boolean = false,
            launchSource: PlayerLaunchSource = PlayerLaunchSource.STREAM,
            resumePositionMs: Long? = null,
            resumeDurationMs: Long? = null,
            resumeProgressPercent: Float? = null,
            resumeLastWatchedMs: Long? = null,
            resumeSource: String? = null
        ): String {
            val encodedUrl = encode(streamUrl)
            val encodedTitle = encode(title)
            val encodedStreamName = streamName?.let { encode(it) } ?: ""
            val encodedServiceKey = serviceKey?.let { encode(it) } ?: ""
            val encodedYear = year?.let { encode(it) } ?: ""
            val encodedHeaders = headers?.entries?.joinToString("&") { (k, v) ->
                "${encode(k)}=${encode(v)}"
            }?.let { encode(it) } ?: ""
            val encodedContentId = contentId?.let { encode(it) } ?: ""
            val encodedContentType = contentType?.let { encode(it) } ?: ""
            val encodedContentName = contentName?.let { encode(it) } ?: ""
            val encodedOriginalLanguage = originalLanguage?.let { encode(it) } ?: ""
            val encodedPoster = poster?.let { encode(it) } ?: ""
            val encodedBackdrop = backdrop?.let { encode(it) } ?: ""
            val encodedLogo = logo?.let { encode(it) } ?: ""
            val encodedVideoId = videoId?.let { encode(it) } ?: ""
            val encodedEpisodeTitle = episodeTitle?.let { encode(it) } ?: ""
            val encodedBingeGroup = bingeGroup?.let { encode(it) } ?: ""
            val encodedRememberedAudioLanguage = rememberedAudioLanguage?.let { encode(it) } ?: ""
            val encodedRememberedAudioName = rememberedAudioName?.let { encode(it) } ?: ""
            val encodedPlayerBackend = encode(playerBackend)
            val encodedFilename = filename?.let { encode(it) } ?: ""
            val encodedVideoHash = videoHash ?: ""
            val encodedLaunchSource = encode(launchSource.routeValue)
            val encodedResumeSource = resumeSource?.let { encode(it) } ?: ""
            return "player/$encodedUrl/$encodedTitle?streamName=$encodedStreamName&serviceKey=$encodedServiceKey&year=$encodedYear&headers=$encodedHeaders&contentId=$encodedContentId&contentType=$encodedContentType&contentName=$encodedContentName&originalLanguage=$encodedOriginalLanguage&poster=$encodedPoster&backdrop=$encodedBackdrop&logo=$encodedLogo&videoId=$encodedVideoId&season=${season ?: ""}&episode=${episode ?: ""}&episodeTitle=$encodedEpisodeTitle&bingeGroup=$encodedBingeGroup&rememberedAudioLanguage=$encodedRememberedAudioLanguage&rememberedAudioName=$encodedRememberedAudioName&playerBackend=$encodedPlayerBackend&autoPlayNav=$autoPlayNav&returnToDetailOnBack=$returnToDetailOnBack&deterministicAutoplay=$deterministicAutoplay&filename=$encodedFilename&videoHash=$encodedVideoHash&videoSize=${videoSize ?: ""}&startFromBeginning=$startFromBeginning&launchSource=$encodedLaunchSource&resumePositionMs=${resumePositionMs ?: ""}&resumeDurationMs=${resumeDurationMs ?: ""}&resumeProgressPercent=${resumeProgressPercent ?: ""}&resumeLastWatchedMs=${resumeLastWatchedMs ?: ""}&resumeSource=$encodedResumeSource"
        }
    }
    data object Search : Screen("search")
    data object Discover : Screen("discover")
    data object Library : Screen("library")
    data object Settings : Screen("settings")
    data object Catalogs : Screen("catalogs")
    data object Trakt : Screen("trakt")
    data object TmdbSettings : Screen("tmdb_settings")
    data object YouTubeTrailerLogin : Screen("youtube_trailer_login")
    data object ThemeSettings : Screen("theme_settings")
    data object PlaybackSettings : Screen("playback_settings")
    data object About : Screen("about")
    data object AddonManager : Screen("addon_manager")
    data object CatalogOrder : Screen("catalog_order")
    data object LayoutSelection : Screen("layout_selection")
    data object LayoutSettings : Screen("layout_settings")
    data object Account : Screen("account")
    data object AuthSignIn : Screen("auth_sign_in")
    data object AuthQrSignIn : Screen("auth_qr_sign_in")
    data object SyncCodeGenerate : Screen("sync_code_generate")
    data object SyncCodeClaim : Screen("sync_code_claim")
    data object CatalogSeeAll : Screen("catalog_see_all/{catalogId}/{addonId}/{type}") {
        private fun encode(value: String): String =
            URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        fun createRoute(catalogId: String, addonId: String, type: String): String {
            return "catalog_see_all/${encode(catalogId)}/${encode(addonId)}/${encode(type)}"
        }
    }

    data object AndroidTvFeed : Screen("android_tv_feed/{feedKey}") {
        private fun encode(value: String): String =
            URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        fun createRoute(feedKey: String): String {
            return "android_tv_feed/${encode(feedKey)}"
        }
    }

    data object CastDetail : Screen("cast_detail/{personId}/{personName}?preferCrew={preferCrew}&provider={provider}") {
        private fun encode(value: String): String =
            URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        fun createRoute(
            personId: Int,
            personName: String,
            preferCrew: Boolean = false
        ): String {
            return "cast_detail/$personId/${encode(personName)}?preferCrew=$preferCrew&provider=tmdb"
        }

        fun createTvdbRoute(
            peopleId: Int,
            personName: String,
            preferCrew: Boolean = false
        ): String {
            return "cast_detail/$peopleId/${encode(personName)}?preferCrew=$preferCrew&provider=tvdb"
        }
    }

    data object OrganizationDetail : Screen("organization_detail/{entityId}/{entityName}?kind={kind}&discoverType={discoverType}") {
        private fun encode(value: String): String =
            URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        fun createRoute(
            entityId: Int,
            entityName: String,
            kind: MetaCompanyKind,
            discoverType: OrganizationDiscoverType
        ): String {
            return "organization_detail/$entityId/${encode(entityName)}?kind=${encode(kind.name)}&discoverType=${encode(discoverType.name)}"
        }
    }
}
