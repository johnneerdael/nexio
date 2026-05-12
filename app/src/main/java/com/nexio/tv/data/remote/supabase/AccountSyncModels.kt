@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.nexio.tv.data.remote.supabase

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject

/**
 * Account settings payloads are written by multiple clients. Android owns a typed subset of the
 * portal settings object, so v10 pulls must ignore newer web-only fields while preserving the
 * fields Android does understand.
 */
val AccountConfigSyncPayloadJson: Json = Json {
    ignoreUnknownKeys = true
}

@Serializable
data class AccountSnapshotRpcResponse(
    @SerialName("user_id") val userId: String? = null,
    val revision: Long = 0,
    @SerialName("updated_at") val updatedAt: String? = null,
    val settings: AccountSettingsPayload = AccountSettingsPayload(),
    val addons: List<AccountAddonPayload> = emptyList()
)

@Serializable
data class AccountConfigSnapshotRpcResponse(
    @SerialName("user_id") val userId: String? = null,
    val revision: Long = 0,
    @SerialName("settings_revision") val settingsRevision: Long = 0,
    @SerialName("updated_at") val updatedAt: String? = null,
    val settings: AccountConfigSyncPayload = AccountConfigSyncPayload(),
    val addons: List<AccountAddonPayload> = emptyList()
)

@Serializable
data class AccountSyncMutationResult(
    @SerialName("sync_revision") val syncRevision: Long = 0,
    @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
data class AccountConfigV7PushResult(
    val applied: Boolean = true,
    @SerialName("sync_revision") val syncRevision: Long = 0,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("updated_from") val updatedFrom: String? = null,
    val settings: AccountConfigSyncPayload = AccountConfigSyncPayload(),
    @SerialName("conflict_paths") val conflictPaths: List<String> = emptyList()
)

@Serializable
data class AccountAddonPayload(
    val id: String? = null,
    val url: String,
    @SerialName("manifest_url") val manifestUrl: String? = null,
    @SerialName("parser_preset") val parserPreset: String = "GENERIC",
    @SerialName("is_anime") val isAnime: Boolean = false,
    val name: String? = null,
    val description: String? = null,
    val enabled: Boolean = true,
    @SerialName("public_query_params") val publicQueryParams: Map<String, String> = emptyMap(),
    @SerialName("install_kind") val installKind: String = "manifest",
    @SerialName("secret_ref") val secretRef: String? = null,
    @SerialName("transport_schema_version") val transportSchemaVersion: Int = 1,
    @SerialName("transport_base_url") val transportBaseUrl: String? = null,
    @SerialName("transport_secret_ref") val transportSecretRef: String? = null,
    @SerialName("sort_order") val sortOrder: Int = 0
)

@Serializable
data class AccountAddonSecretPayload(
    val kind: String = "query_params",
    val params: Map<String, String> = emptyMap(),
    @SerialName("pathSegment") val pathSegment: String? = null,
    val suffix: String? = null
)

internal fun AccountAddonSecretPayload?.requireValidV2Transport(
    secretRef: String,
    addonUrl: String
): AccountAddonSecretPayload {
    val payload = this
        ?: error(
            "transport-secret invariant violated for $addonUrl (ref=$secretRef): " +
                "vault returned an empty payload"
        )
    check(payload.kind == "manifest_suffix_v1") {
        "transport-secret invariant violated for $addonUrl (ref=$secretRef): " +
            "kind=${payload.kind}, expected manifest_suffix_v1"
    }
    check(!payload.suffix.isNullOrBlank()) {
        "transport-secret invariant violated for $addonUrl (ref=$secretRef): blank suffix"
    }
    return payload
}

internal fun AccountAddonSecretPayload?.requireValidV1Secret(
    secretRef: String,
    addonUrl: String
): AccountAddonSecretPayload {
    val payload = this
        ?: error(
            "legacy-secret invariant violated for $addonUrl (ref=$secretRef): " +
                "vault returned an empty payload"
        )
    check(payload.params.isNotEmpty() || !payload.pathSegment.isNullOrBlank()) {
        "legacy-secret invariant violated for $addonUrl (ref=$secretRef): " +
            "empty params and pathSegment"
    }
    return payload
}

@Serializable
data class AccountTvdbCredentialSecretPayload(
    val apiKey: String = "",
    val pin: String? = null
)

@Serializable
data class AccountConfigSyncPayload(
    @EncodeDefault
    val schemaVersion: Int = 12,
    val integrations: IntegrationSettings = IntegrationSettings(),
    val catalogs: CatalogSyncSettings = CatalogSyncSettings(),
    val playback: PlaybackConfigSyncSettings = PlaybackConfigSyncSettings(),
    val formatter: FormatterSyncSettings = FormatterSyncSettings()
)

@Serializable
data class PlaybackConfigSyncSettings(
    val streamSelection: StreamSelectionConfigSyncSettings = StreamSelectionConfigSyncSettings()
)

@Serializable
data class StreamSelectionConfigSyncSettings(
    val trackingProvider: String = "TRAKT"
)

@Serializable
data class FormatterSyncSettings(
    val enabled: Boolean = true,
    val selectedTemplateId: String = "universal",
    val customTemplate: CustomFormatterSyncTemplate? = null
)

@Serializable
data class CustomFormatterSyncTemplate(
    val id: String = "custom",
    val label: String = "Custom",
    val nameTemplate: String = "",
    val descriptionTemplate: String = "",
    val badgeRowTemplate: String = ""
)

@Serializable
data class CatalogSyncSettings(
    val home: HomeCatalogSyncSettings? = null,
    val trakt: TraktCatalogSyncSettings? = null,
    val simkl: SimklCatalogSyncSettings? = null,
    val mdblist: MDBListCatalogSyncSettings? = null,
    val tmdb: TmdbCatalogSyncSettings? = null,
    val kitsu: KitsuCatalogSyncSettings? = null
)

@Serializable
data class HomeCatalogSyncSettings(
    val heroCatalogKeys: List<String>? = null,
    val homeCatalogOrderKeys: List<String>? = null,
    val disabledHomeCatalogKeys: List<String>? = null
)

@Serializable
data class TraktCatalogSyncSettings(
    val catalogEnabledSet: List<String>? = null,
    val catalogOrder: List<String>? = null,
    val selectedPopularListKeys: List<String>? = null,
    // pinnedListOptions is a typed-object list (not an ORDER/ENABLED key list)
    // and is only mirrored into in-memory remote-state tracking, not applied as
    // a clobbering write. Kept non-nullable for the nullability migration.
    val pinnedListOptions: List<TraktPinnedListOptionSync> = emptyList()
)

@Serializable
data class SimklCatalogSyncSettings(
    val catalogEnabledSet: List<String>? = null,
    val catalogOrder: List<String>? = null
)

@Serializable
data class TmdbCatalogSyncSettings(
    val catalogEnabledSet: List<String>? = null,
    val catalogOrder: List<String>? = null
)

@Serializable
data class KitsuCatalogSyncSettings(
    val catalogEnabledSet: List<String>? = null,
    val catalogOrder: List<String>? = null
)

@Serializable
data class MDBListCatalogSyncSettings(
    val hiddenPersonalListKeys: List<String>? = null,
    val selectedTopListKeys: List<String>? = null,
    // pinnedTopListOptions is a typed-object list (not an ORDER/ENABLED key
    // list) and is only mirrored into in-memory remote-state tracking, not
    // applied as a clobbering write. Kept non-nullable for the nullability
    // migration.
    val pinnedTopListOptions: List<MDBListPinnedListOptionSync> = emptyList(),
    val catalogOrder: List<String>? = null
)

@Serializable
data class TraktPinnedListOptionSync(
    val key: String = "",
    val userId: String = "",
    val listId: String = "",
    val catalogIdBase: String = "",
    val title: String = "",
    val itemCount: Int = 0
)

@Serializable
data class MDBListPinnedListOptionSync(
    val key: String = "",
    val owner: String = "",
    val listId: String = "",
    val title: String = "",
    val itemCount: Int = 0,
    val isPersonal: Boolean = false
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
    val theme: String = "CRIMSON",
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
    val debrid: DebridSyncSettings = DebridSyncSettings(),
    val tmdb: TmdbSyncSettings = TmdbSyncSettings(),
    val omdb: OmdbSyncSettings = OmdbSyncSettings(),
    @EncodeDefault
    val imdb: ImdbSyncSettings = ImdbSyncSettings(),
    val mdblist: MDBListSyncSettings = MDBListSyncSettings(),
    val animeSkip: AnimeSkipSyncSettings = AnimeSkipSyncSettings(),
    @EncodeDefault
    val subtitleTranslation: SubtitleTranslationSyncSettings = SubtitleTranslationSyncSettings(),
    @EncodeDefault
    val gemini: GeminiSyncSettings = GeminiSyncSettings(),
    val posterRatings: PosterRatingsSyncSettings = PosterRatingsSyncSettings(),
    val kitsuAuth: KitsuAuthSyncSettings = KitsuAuthSyncSettings(),
    val traktAuth: TraktAuthSyncSettings = TraktAuthSyncSettings(),
    val simklAuth: SimklAuthSyncSettings = SimklAuthSyncSettings()
)

@Serializable
data class DebridSyncSettings(
    val premiumize: PremiumizeSyncSettings = PremiumizeSyncSettings(),
    val realDebrid: RealDebridSyncSettings = RealDebridSyncSettings(),
    @EncodeDefault
    val torBox: TorBoxSyncSettings = TorBoxSyncSettings(),
    @EncodeDefault
    val easyDebrid: EasyDebridSyncSettings = EasyDebridSyncSettings()
)

@Serializable
data class PremiumizeSyncSettings(
    val configured: Boolean = false,
    val customerId: Int? = null
)

@Serializable
data class RealDebridSyncSettings(
    val connected: Boolean = false,
    val username: String = "",
    val pending: Boolean = false,
    val deviceCode: String = "",
    val userCode: String = "",
    val verificationUrl: String = "",
    val expiresAt: Long? = null
)

@Serializable
data class TorBoxSyncSettings(
    val configured: Boolean = false,
    val email: String = "",
    val plan: String = ""
)

@Serializable
data class EasyDebridSyncSettings(
    val configured: Boolean = false,
    val userId: String = "",
    val paidUntil: String = ""
)

@Serializable
data class TmdbSyncSettings(
    @EncodeDefault
    val enabled: Boolean = true,
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
data class OmdbSyncSettings(
    val enabled: Boolean = false
)

@Serializable
data class ImdbSyncSettings(
    val enabled: Boolean = false,
    val baseUrl: String = ""
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
    val enabled: Boolean = false
)

@Serializable
data class GeminiSyncSettings(
    val enabled: Boolean = false
)

@Serializable
data class SubtitleTranslationSyncSettings(
    @EncodeDefault
    val enabled: Boolean = false,
    @EncodeDefault
    val provider: String = "OPENAI",
    @EncodeDefault
    val model: String = "openrouter/free",
    @EncodeDefault
    val baseUrl: String = "https://openrouter.ai/api/v1"
)

@Serializable
data class PosterRatingsSyncSettings(
    val rpdbEnabled: Boolean = false,
    val topPostersEnabled: Boolean = false
)

@Serializable
data class KitsuAuthSyncSettings(
    val connected: Boolean = false,
    val username: String = "",
    val accessTokenSecretRef: String? = null,
    val refreshTokenSecretRef: String? = null,
    val expiresAtEpochSeconds: Long? = null,
    val includeNsfw: Boolean = false
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
data class SimklAuthSyncSettings(
    val connected: Boolean = false,
    val username: String = "",
    val accountId: Long? = null,
    val accountType: String = "",
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
    val streamReuseLastLinkEnabled: Boolean = false,
    val streamReuseLastLinkCacheHours: Int = 24,
    val uniformStreamFormattingEnabled: Boolean = true,
    val groupStreamsAcrossAddonsEnabled: Boolean = true,
    val deduplicateGroupedStreamsEnabled: Boolean = true,
    val filterEpisodeMismatchStreamsEnabled: Boolean = true,
    val filterMovieYearMismatchStreamsEnabled: Boolean = true,
    val streamAutoPlayMode: String = "MANUAL",
    val streamAutoPlaySource: String = "ALL_SOURCES",
    val trackingProvider: String = "TRAKT",
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
    val preferredAudioLanguage: String = "original",
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
    val outlineColor: Int = -16777216
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

@Serializable
data class AccountSimklAccessSecretPayload(
    val accessToken: String = ""
)

@Serializable
data class AccountKitsuAccessSecretPayload(
    val accessToken: String = "",
    val expiresAtEpochSeconds: Long? = null
)

@Serializable
data class AccountKitsuRefreshSecretPayload(
    val refreshToken: String = ""
)

@Serializable
data class AccountRealDebridAccessSecretPayload(
    val accessToken: String = "",
    val tokenType: String = "Bearer",
    val expiresIn: Int = 0,
    val userClientId: String = "",
    val userClientSecret: String = ""
)

@Serializable
data class AccountRealDebridRefreshSecretPayload(
    val refreshToken: String = ""
)

@Serializable
data class ProfileSettingsBlobResponse(
    @SerialName("user_id") val userId: String? = null,
    @SerialName("profile_id") val profileId: Int = 1,
    val platform: String = "tv",
    @SerialName("settings_json") val settingsJson: JsonElement = buildJsonObject {},
    @SerialName("updated_at") val updatedAt: String? = null
)
