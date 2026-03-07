package com.nexio.tv.data.remote.supabase

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AccountSnapshotRpcResponse(
    @SerialName("user_id") val userId: String? = null,
    val revision: Long = 0,
    @SerialName("updated_at") val updatedAt: String? = null,
    val settings: AccountSettingsPayload = AccountSettingsPayload(),
    val addons: List<AccountAddonPayload> = emptyList()
)

@Serializable
data class AccountSyncMutationResult(
    @SerialName("sync_revision") val syncRevision: Long = 0,
    @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
data class AccountAddonPayload(
    val id: String? = null,
    val url: String,
    @SerialName("manifest_url") val manifestUrl: String? = null,
    val name: String? = null,
    val description: String? = null,
    val enabled: Boolean = true,
    @SerialName("public_query_params") val publicQueryParams: Map<String, String> = emptyMap(),
    @SerialName("install_kind") val installKind: String = "manifest",
    @SerialName("secret_ref") val secretRef: String? = null,
    @SerialName("sort_order") val sortOrder: Int = 0
)

@Serializable
data class AccountAddonSecretPayload(
    val kind: String = "query_params",
    val params: Map<String, String> = emptyMap(),
    @SerialName("pathSegment") val pathSegment: String? = null
)

@Serializable
data class AccountSettingsPayload(
    val appearance: AppearanceSettings = AppearanceSettings(),
    val layout: LayoutSettings = LayoutSettings(),
    val integrations: IntegrationSettings = IntegrationSettings(),
    val playback: PlaybackSettings = PlaybackSettings(),
    val trakt: TraktSettingsPayload = TraktSettingsPayload(),
    val debug: DebugSettingsPayload = DebugSettingsPayload()
)

@Serializable
data class AppearanceSettings(
    val theme: String = "WHITE",
    val font: String = "INTER",
    val localeTag: String = "system"
)

@Serializable
data class LayoutSettings(
    val selectedLayout: String = "MODERN",
    val modernLandscapePostersEnabled: Boolean = false,
    val heroCatalogKeys: List<String> = emptyList(),
    val homeCatalogOrderKeys: List<String> = emptyList(),
    val disabledHomeCatalogKeys: List<String> = emptyList(),
    val sidebarCollapsedByDefault: Boolean = false,
    val modernSidebarEnabled: Boolean = false,
    val modernSidebarBlurEnabled: Boolean = false,
    val heroSectionEnabled: Boolean = true,
    val searchDiscoverEnabled: Boolean = true,
    val posterLabelsEnabled: Boolean = true,
    val catalogAddonNameEnabled: Boolean = true,
    val catalogTypeSuffixEnabled: Boolean = true,
    val hideUnreleasedContent: Boolean = false,
    val blurUnwatchedEpisodes: Boolean = false,
    val preferExternalMetaAddonDetail: Boolean = false,
    val focusedPosterBackdropExpandEnabled: Boolean = false,
    val focusedPosterBackdropExpandDelaySeconds: Int = 3,
    val posterCardWidthDp: Int = 126,
    val posterCardCornerRadiusDp: Int = 12
)

@Serializable
data class IntegrationSettings(
    val tmdb: TmdbSyncSettings = TmdbSyncSettings(),
    val mdblist: MDBListSyncSettings = MDBListSyncSettings(),
    val animeSkip: AnimeSkipSyncSettings = AnimeSkipSyncSettings(),
    val gemini: GeminiSyncSettings = GeminiSyncSettings(),
    val posterRatings: PosterRatingsSyncSettings = PosterRatingsSyncSettings(),
    val traktAuth: TraktAuthSyncSettings = TraktAuthSyncSettings()
)

@Serializable
data class TmdbSyncSettings(
    val enabled: Boolean = false,
    val useArtwork: Boolean = true,
    val useBasicInfo: Boolean = true,
    val useDetails: Boolean = true,
    val useCredits: Boolean = true,
    val useProductions: Boolean = true,
    val useNetworks: Boolean = true,
    val useEpisodes: Boolean = true,
    val useMoreLikeThis: Boolean = true,
    val useCollections: Boolean = true
)

@Serializable
data class MDBListSyncSettings(
    val enabled: Boolean = false,
    val showTrakt: Boolean = true,
    val showImdb: Boolean = true,
    val showTmdb: Boolean = true,
    val showLetterboxd: Boolean = true,
    val showTomatoes: Boolean = true,
    val showAudience: Boolean = true,
    val showMetacritic: Boolean = true,
    val hiddenPersonalListKeys: List<String> = emptyList(),
    val selectedTopListKeys: List<String> = emptyList(),
    val catalogOrder: List<String> = emptyList()
)

@Serializable
data class AnimeSkipSyncSettings(
    val enabled: Boolean = false,
    val clientId: String = ""
)

@Serializable
data class GeminiSyncSettings(
    val enabled: Boolean = false
)

@Serializable
data class PosterRatingsSyncSettings(
    val rpdbEnabled: Boolean = false,
    val topPostersEnabled: Boolean = false
)

@Serializable
data class TraktAuthSyncSettings(
    val connected: Boolean = false,
    val username: String = "",
    val userSlug: String = "",
    val connectedAt: String? = null,
    val pending: Boolean = false
)

@Serializable
data class PlaybackSettings(
    val general: PlaybackGeneralSettings = PlaybackGeneralSettings(),
    val streamSelection: StreamSelectionSettings = StreamSelectionSettings(),
    val audio: AudioSettings = AudioSettings(),
    val subtitles: SubtitleSyncSettings = SubtitleSyncSettings(),
    val bufferNetwork: BufferNetworkSettings = BufferNetworkSettings()
)

@Serializable
data class PlaybackGeneralSettings(
    val loadingOverlayEnabled: Boolean = true,
    val pauseOverlayEnabled: Boolean = true,
    val osdClockEnabled: Boolean = true,
    val skipIntroEnabled: Boolean = true,
    val frameRateMatchingMode: String = "OFF",
    val resolutionMatchingEnabled: Boolean = false
)

@Serializable
data class StreamSelectionSettings(
    val playerPreference: String = "INTERNAL",
    val streamReuseLastLinkEnabled: Boolean = false,
    val streamReuseLastLinkCacheHours: Int = 24,
    val uniformStreamFormattingEnabled: Boolean = false,
    val groupStreamsAcrossAddonsEnabled: Boolean = false,
    val deduplicateGroupedStreamsEnabled: Boolean = false,
    val filterWebDolbyVisionStreamsEnabled: Boolean = false,
    val filterEpisodeMismatchStreamsEnabled: Boolean = false,
    val filterMovieYearMismatchStreamsEnabled: Boolean = false,
    val streamAutoPlayMode: String = "MANUAL",
    val streamAutoPlaySource: String = "ALL_SOURCES",
    val streamAutoPlaySelectedAddons: List<String> = emptyList(),
    val streamAutoPlayRegex: String = "",
    val streamAutoPlayNextEpisodeEnabled: Boolean = false,
    val streamAutoPlayPreferBingeGroupForNextEpisode: Boolean = true,
    val nextEpisodeThresholdMode: String = "PERCENTAGE",
    val nextEpisodeThresholdPercent: Float = 99f,
    val nextEpisodeThresholdMinutesBeforeEnd: Float = 2f
)

@Serializable
data class AudioSettings(
    val preferredAudioLanguage: String = "device",
    val secondaryPreferredAudioLanguage: String? = null,
    val skipSilence: Boolean = false,
    val decoderPriority: Int = 1,
    val tunnelingEnabled: Boolean = false
)

@Serializable
data class SubtitleSyncSettings(
    val preferredLanguage: String = "en",
    val secondaryPreferredLanguage: String? = null,
    val subtitleOrganizationMode: String = "NONE",
    val addonSubtitleStartupMode: String = "ALL_SUBTITLES",
    val size: Int = 100,
    val verticalOffset: Int = 5,
    val bold: Boolean = false,
    val textColor: Int = -1,
    val backgroundColor: Int = 0,
    val outlineEnabled: Boolean = true,
    val outlineColor: Int = -16777216,
    val useLibass: Boolean = false
)

@Serializable
data class BufferNetworkSettings(
    val minBufferMs: Int = 20_000,
    val maxBufferMs: Int = 50_000,
    val bufferForPlaybackMs: Int = 3_000,
    val bufferForPlaybackAfterRebufferMs: Int = 5_000,
    val targetBufferSizeMb: Int = 100,
    val backBufferDurationMs: Int = 0,
    val enableBufferLogs: Boolean = false
)

@Serializable
data class TraktSettingsPayload(
    val continueWatchingDaysCap: Int = 60,
    val showUnairedNextUp: Boolean = true,
    val catalogEnabledSet: List<String> = emptyList(),
    val catalogOrder: List<String> = emptyList(),
    val selectedPopularListKeys: List<String> = emptyList()
)

@Serializable
data class DebugSettingsPayload(
    val accountTabEnabled: Boolean = false,
    val syncCodeFeaturesEnabled: Boolean = false,
    val bufferLogsEnabled: Boolean = false
)

@Serializable
data class AccountSecretApiKeyPayload(
    val apiKey: String = ""
)

@Serializable
data class AccountTraktAccessSecretPayload(
    val accessToken: String = "",
    val tokenType: String = "bearer",
    val createdAt: Long = 0L,
    val expiresIn: Int = 0
)

@Serializable
data class AccountTraktRefreshSecretPayload(
    val refreshToken: String = ""
)
