package com.nexio.tv.core.sync

import android.content.Context
import android.util.Log
import com.nexio.tv.core.auth.AuthManager
import com.nexio.tv.core.auth.hasLiveFullAccountSyncSession
import com.nexio.tv.core.auth.liveFullAccountSessionUserId
import com.nexio.tv.core.auth.stockAccountConfigSyncPayload
import com.nexio.tv.core.locale.AppLocaleResolver
import com.nexio.tv.data.local.AddonPreferences
import com.nexio.tv.data.local.AddonSubtitleStartupMode
import com.nexio.tv.data.local.AnimeSkipSettingsDataStore
import com.nexio.tv.data.local.DebugSettingsDataStore
import com.nexio.tv.data.local.EasyDebridSettingsDataStore
import com.nexio.tv.data.local.FrameRateMatchingMode
import com.nexio.tv.data.local.KitsuCatalogSettingsDataStore
import com.nexio.tv.data.local.LayoutPreferenceDataStore
import com.nexio.tv.data.local.KitsuAuthDataStore
import com.nexio.tv.data.local.MDBListSettingsDataStore
import com.nexio.tv.data.local.NextEpisodeThresholdMode
import com.nexio.tv.data.local.OmdbSettingsDataStore
import com.nexio.tv.data.local.PlayerSettingsDataStore
import com.nexio.tv.data.local.PosterRatingsSettingsDataStore
import com.nexio.tv.data.local.PremiumizeSettingsDataStore
import com.nexio.tv.data.local.RealDebridAuthDataStore
import com.nexio.tv.data.local.SimklAuthDataStore
import com.nexio.tv.data.local.SimklSettingsDataStore
import com.nexio.tv.data.local.StreamAutoPlayMode
import com.nexio.tv.data.local.StreamAutoPlaySource
import com.nexio.tv.data.local.SubtitleOrganizationMode
import com.nexio.tv.data.local.SubtitleTranslationSettingsDataStore
import com.nexio.tv.data.local.SyncWatermarkDataStore
import com.nexio.tv.data.local.ThemeDataStore
import com.nexio.tv.data.local.TmdbCatalogSettingsDataStore
import com.nexio.tv.data.local.TmdbSettingsDataStore
import com.nexio.tv.data.local.TorBoxSettingsDataStore
import com.nexio.tv.data.local.TraktAuthDataStore
import com.nexio.tv.data.local.TraktSettingsDataStore
import com.nexio.tv.data.local.TvdbSettingsDataStore
import com.nexio.tv.data.remote.dto.debrid.RealDebridDeviceCodeResponseDto
import com.nexio.tv.data.remote.dto.debrid.RealDebridDeviceCredentialsResponseDto
import com.nexio.tv.data.remote.dto.debrid.RealDebridTokenResponseDto
import com.nexio.tv.data.remote.dto.trakt.TraktTokenResponseDto
import com.nexio.tv.data.remote.supabase.AccountAddonPayload
import com.nexio.tv.data.remote.supabase.AccountAddonSecretPayload
import com.nexio.tv.data.remote.supabase.AccountConfigSyncPayload
import com.nexio.tv.data.remote.supabase.AccountKitsuAccessSecretPayload
import com.nexio.tv.data.remote.supabase.AccountKitsuRefreshSecretPayload
import com.nexio.tv.data.remote.supabase.AccountRealDebridAccessSecretPayload
import com.nexio.tv.data.remote.supabase.AccountRealDebridRefreshSecretPayload
import com.nexio.tv.data.remote.supabase.AccountSettingsPayload
import com.nexio.tv.data.remote.supabase.AccountSimklAccessSecretPayload
import com.nexio.tv.data.remote.supabase.AccountSecretApiKeyPayload
import com.nexio.tv.data.remote.supabase.AccountSnapshotRpcResponse
import com.nexio.tv.data.remote.supabase.AccountTraktAccessSecretPayload
import com.nexio.tv.data.remote.supabase.AccountTraktRefreshSecretPayload
import com.nexio.tv.data.remote.supabase.requireValidV1Secret
import com.nexio.tv.data.remote.supabase.requireValidV2Transport
import com.nexio.tv.data.remote.supabase.AnimeSkipSyncSettings
import com.nexio.tv.data.remote.supabase.AppearanceSettings
import com.nexio.tv.data.remote.supabase.AudioSettings
import com.nexio.tv.data.remote.supabase.BufferNetworkSettings
import com.nexio.tv.data.remote.supabase.CustomFormatterSyncTemplate
import com.nexio.tv.data.remote.supabase.DebridSyncSettings
import com.nexio.tv.data.remote.supabase.DebugSettingsPayload
import com.nexio.tv.data.remote.supabase.EasyDebridSyncSettings
import com.nexio.tv.data.remote.supabase.FormatterSyncSettings
import com.nexio.tv.data.remote.supabase.GeminiSyncSettings
import com.nexio.tv.data.remote.supabase.IntegrationSettings
import com.nexio.tv.data.remote.supabase.KitsuAuthSyncSettings
import com.nexio.tv.data.remote.supabase.LayoutSettings
import com.nexio.tv.data.remote.supabase.MDBListPinnedListOptionSync
import com.nexio.tv.data.remote.supabase.MDBListSyncSettings
import com.nexio.tv.data.remote.supabase.OmdbSyncSettings
import com.nexio.tv.data.remote.supabase.PlaybackGeneralSettings
import com.nexio.tv.data.remote.supabase.PlaybackSettings
import com.nexio.tv.data.remote.supabase.PosterRatingsSyncSettings
import com.nexio.tv.data.remote.supabase.PremiumizeSyncSettings
import com.nexio.tv.data.remote.supabase.RealDebridSyncSettings
import com.nexio.tv.data.remote.supabase.SimklAuthSyncSettings
import com.nexio.tv.data.remote.supabase.StreamSelectionSettings
import com.nexio.tv.data.remote.supabase.SubtitleSyncSettings
import com.nexio.tv.data.remote.supabase.SubtitleTranslationSyncSettings
import com.nexio.tv.data.remote.supabase.TmdbSyncSettings
import com.nexio.tv.data.remote.supabase.TorBoxSyncSettings
import com.nexio.tv.data.remote.supabase.TraktAuthSyncSettings
import com.nexio.tv.data.remote.supabase.TraktPinnedListOptionSync
import com.nexio.tv.data.remote.supabase.TraktSettingsPayload
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.core.profile.ProfileModeRouter
import com.nexio.tv.data.repository.EasyDebridService
import com.nexio.tv.data.repository.PremiumizeService
import com.nexio.tv.data.repository.TorBoxService
import com.nexio.tv.domain.model.AddonParserPreset
import com.nexio.tv.domain.model.AppFont
import com.nexio.tv.domain.model.AppTheme
import com.nexio.tv.domain.model.ArtworkProviderChoiceKey
import com.nexio.tv.domain.model.HomeLayout
import com.nexio.tv.domain.model.TrackingProvider
import com.nexio.tv.ui.screens.home.order.HomeRailOrderStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.nexio.tv.data.remote.supabase.V13AccountSnapshotEnvelope
import com.nexio.tv.data.remote.supabase.V10PushResult
import com.nexio.tv.data.remote.supabase.V13BatchPushResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AccountSettingsSync"
private const val MDBLIST_SECRET_TYPE = "mdblist_api_key"
private const val MDBLIST_SECRET_REF = "integration:mdblist"
private const val OMDB_SECRET_TYPE = "omdb_api_key"
private const val OMDB_SECRET_REF = "integration:omdb"
private const val TRANSLATION_SECRET_TYPE = "translation_api_key"
private const val TRANSLATION_SECRET_REF = "integration:subtitle-translation"
private const val ANIMESKIP_SECRET_TYPE = "animeskip_api_key"
private const val ANIMESKIP_SECRET_REF = "integration:animeSkip"
private const val GEMINI_SECRET_TYPE = "gemini_api_key"
private const val GEMINI_SECRET_REF = "integration:gemini"
private const val RPDB_SECRET_TYPE = "rpdb_api_key"
private const val RPDB_SECRET_REF = "integration:rpdb"
private const val TOP_POSTERS_SECRET_TYPE = "top_posters_api_key"
private const val TOP_POSTERS_SECRET_REF = "integration:topposters"
private const val PREMIUMIZE_SECRET_TYPE = "premiumize_api_key"
private const val PREMIUMIZE_SECRET_REF = "integration:premiumize"
private const val TORBOX_SECRET_TYPE = "torbox_api_key"
private const val TORBOX_SECRET_REF = "integration:torbox"
private const val EASY_DEBRID_SECRET_TYPE = "easydebrid_api_key"
private const val EASY_DEBRID_SECRET_REF = "integration:easydebrid"
private const val REAL_DEBRID_ACCESS_SECRET_TYPE = "realdebrid_access_token"
private const val REAL_DEBRID_REFRESH_SECRET_TYPE = "realdebrid_refresh_token"
private const val REAL_DEBRID_SECRET_REF = "integration:realdebrid"
private const val TRAKT_ACCESS_SECRET_TYPE = "trakt_access_token"
private const val TRAKT_REFRESH_SECRET_TYPE = "trakt_refresh_token"
private const val TRAKT_SECRET_REF = "integration:trakt"
private const val SIMKL_ACCESS_SECRET_TYPE = "simkl_access_token"
private const val SIMKL_SECRET_REF = "integration:simkl"
private const val KITSU_ACCESS_SECRET_TYPE = "kitsu_access_token"
private const val KITSU_REFRESH_SECRET_TYPE = "kitsu_refresh_token"
private const val KITSU_SECRET_REF = "integration:kitsu"

private val CATALOG_SECTION_KEYS = setOf(
    AccountSettingsSectionKey.CATALOGS_MDBLIST,
    AccountSettingsSectionKey.CATALOGS_TRAKT,
    AccountSettingsSectionKey.CATALOGS_SIMKL,
    AccountSettingsSectionKey.CATALOGS_TMDB,
    AccountSettingsSectionKey.CATALOGS_KITSU,
    AccountSettingsSectionKey.CATALOGS_HOME
)

private val ACCOUNT_SECRET_SECTION_KEYS = setOf(
    AccountSettingsSectionKey.INTEGRATIONS_MDBLIST,
    AccountSettingsSectionKey.INTEGRATIONS_OMDB,
    AccountSettingsSectionKey.INTEGRATIONS_SUBTITLE_TRANSLATION,
    AccountSettingsSectionKey.INTEGRATIONS_GEMINI,
    AccountSettingsSectionKey.INTEGRATIONS_ANIME_SKIP,
    AccountSettingsSectionKey.INTEGRATIONS_POSTER_RATINGS,
    AccountSettingsSectionKey.INTEGRATIONS_DEBRID_PREMIUMIZE,
    AccountSettingsSectionKey.INTEGRATIONS_DEBRID_TOR_BOX,
    AccountSettingsSectionKey.INTEGRATIONS_DEBRID_EASY_DEBRID,
    AccountSettingsSectionKey.INTEGRATIONS_DEBRID_REAL_DEBRID,
    AccountSettingsSectionKey.INTEGRATIONS_TRAKT_AUTH,
    AccountSettingsSectionKey.INTEGRATIONS_SIMKL_AUTH,
    AccountSettingsSectionKey.INTEGRATIONS_KITSU_AUTH
)

private val SUBTITLE_TRANSLATION_SECRET_SECTION_KEYS = setOf(
    AccountSettingsSectionKey.INTEGRATIONS_SUBTITLE_TRANSLATION,
    AccountSettingsSectionKey.INTEGRATIONS_GEMINI
)

private fun Set<AccountSettingsSectionKey>?.includesSection(section: AccountSettingsSectionKey): Boolean {
    return this == null || section in this
}

private fun Set<AccountSettingsSectionKey>?.includesAnySection(sections: Set<AccountSettingsSectionKey>): Boolean {
    return this == null || sections.any { it in this }
}

internal fun selectSubtitleTranslationApiKeySecret(
    genericTranslationKey: String?,
    legacyGeminiKey: String?,
    allowLegacyFallback: Boolean
): String? {
    if (genericTranslationKey == null) return null
    if (genericTranslationKey.isNotBlank()) return genericTranslationKey
    if (allowLegacyFallback && legacyGeminiKey == null) return null
    return legacyGeminiKey
        ?.takeIf { allowLegacyFallback && it.isNotBlank() }
        ?: genericTranslationKey
}

internal fun legacyGeminiApiKeySecretForPush(
    providerName: String,
    translationApiKey: String
): String? {
    return translationApiKey.takeIf { providerName.equals("GEMINI", ignoreCase = true) }
}

internal fun clearAppliedChangedPathsForGeneration(
    pendingChangedPaths: MutableSet<String>,
    pendingChangedPathsGeneration: Long,
    snapshotChangedPathsGeneration: Long,
    appliedChangedPaths: Set<String>
): Boolean {
    if (pendingChangedPathsGeneration != snapshotChangedPathsGeneration) return false

    pendingChangedPaths.removeAll(appliedChangedPaths)
    return true
}

internal fun buildStaleRecoveryPreserveLocalSectionKeys(
    pendingChangedPaths: Set<String>,
    dirtySettingsSectionKeys: Set<AccountSettingsSectionKey>,
    dirtySecretSectionKeys: Set<AccountSettingsSectionKey>
): Set<AccountSettingsSectionKey> {
    return linkedSetOf<AccountSettingsSectionKey>().apply {
        pendingChangedPaths.mapNotNullTo(this, AccountSettingsSectionKey::fromChangedPath)
        addAll(dirtySettingsSectionKeys)
        addAll(dirtySecretSectionKeys)
    }
}

internal data class AccountSecretPushSnapshot(
    val mdbListApiKey: String,
    val omdbApiKey: String,
    val subtitleTranslationApiKey: String,
    val legacyGeminiApiKey: String?,
    val animeSkipClientId: String,
    val rpdbApiKey: String,
    val topPostersApiKey: String,
    val premiumizeApiKey: String,
    val torBoxApiKey: String,
    val easyDebridApiKey: String,
    val realDebrid: RealDebridSecretPushSnapshot,
    val trakt: TraktSecretPushSnapshot,
    val simkl: SimklSecretPushSnapshot,
    val kitsu: KitsuSecretPushSnapshot
)

internal data class RealDebridSecretPushSnapshot(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String,
    val expiresIn: Int,
    val userClientId: String,
    val userClientSecret: String
)

internal data class TraktSecretPushSnapshot(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String,
    val createdAt: Long,
    val expiresIn: Int
)

internal data class SimklSecretPushSnapshot(
    val accessToken: String
)

internal data class KitsuSecretPushSnapshot(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochSeconds: Long?
)

internal fun dirtyAccountSecretSectionKeys(
    current: AccountSecretPushSnapshot,
    baseline: AccountSecretPushSnapshot?
): Set<AccountSettingsSectionKey> {
    val normalizedCurrent = current.normalizedForPush()
    if (baseline == null) return configuredAccountSecretSectionKeys(normalizedCurrent)

    val normalizedBaseline = baseline.normalizedForPush()
    return linkedSetOf<AccountSettingsSectionKey>().apply {
        if (normalizedCurrent.mdbListApiKey != normalizedBaseline.mdbListApiKey) {
            add(AccountSettingsSectionKey.INTEGRATIONS_MDBLIST)
        }
        if (normalizedCurrent.omdbApiKey != normalizedBaseline.omdbApiKey) {
            add(AccountSettingsSectionKey.INTEGRATIONS_OMDB)
        }
        if (
            normalizedCurrent.subtitleTranslationApiKey != normalizedBaseline.subtitleTranslationApiKey ||
            normalizedCurrent.legacyGeminiApiKey != normalizedBaseline.legacyGeminiApiKey
        ) {
            add(AccountSettingsSectionKey.INTEGRATIONS_SUBTITLE_TRANSLATION)
        }
        if (normalizedCurrent.animeSkipClientId != normalizedBaseline.animeSkipClientId) {
            add(AccountSettingsSectionKey.INTEGRATIONS_ANIME_SKIP)
        }
        if (
            normalizedCurrent.rpdbApiKey != normalizedBaseline.rpdbApiKey ||
            normalizedCurrent.topPostersApiKey != normalizedBaseline.topPostersApiKey
        ) {
            add(AccountSettingsSectionKey.INTEGRATIONS_POSTER_RATINGS)
        }
        if (normalizedCurrent.premiumizeApiKey != normalizedBaseline.premiumizeApiKey) {
            add(AccountSettingsSectionKey.INTEGRATIONS_DEBRID_PREMIUMIZE)
        }
        if (normalizedCurrent.torBoxApiKey != normalizedBaseline.torBoxApiKey) {
            add(AccountSettingsSectionKey.INTEGRATIONS_DEBRID_TOR_BOX)
        }
        if (normalizedCurrent.easyDebridApiKey != normalizedBaseline.easyDebridApiKey) {
            add(AccountSettingsSectionKey.INTEGRATIONS_DEBRID_EASY_DEBRID)
        }
        if (normalizedCurrent.realDebrid != normalizedBaseline.realDebrid) {
            add(AccountSettingsSectionKey.INTEGRATIONS_DEBRID_REAL_DEBRID)
        }
        if (normalizedCurrent.trakt != normalizedBaseline.trakt) {
            add(AccountSettingsSectionKey.INTEGRATIONS_TRAKT_AUTH)
        }
        if (normalizedCurrent.simkl != normalizedBaseline.simkl) {
            add(AccountSettingsSectionKey.INTEGRATIONS_SIMKL_AUTH)
        }
        if (normalizedCurrent.kitsu != normalizedBaseline.kitsu) {
            add(AccountSettingsSectionKey.INTEGRATIONS_KITSU_AUTH)
        }
    }
}

internal fun configuredAccountSecretSectionKeys(
    current: AccountSecretPushSnapshot
): Set<AccountSettingsSectionKey> {
    val normalized = current.normalizedForPush()
    return linkedSetOf<AccountSettingsSectionKey>().apply {
        if (normalized.mdbListApiKey.isNotBlank()) add(AccountSettingsSectionKey.INTEGRATIONS_MDBLIST)
        if (normalized.omdbApiKey.isNotBlank()) add(AccountSettingsSectionKey.INTEGRATIONS_OMDB)
        if (
            normalized.subtitleTranslationApiKey.isNotBlank() ||
            normalized.legacyGeminiApiKey?.isNotBlank() == true
        ) {
            add(AccountSettingsSectionKey.INTEGRATIONS_SUBTITLE_TRANSLATION)
        }
        if (normalized.animeSkipClientId.isNotBlank()) add(AccountSettingsSectionKey.INTEGRATIONS_ANIME_SKIP)
        if (
            normalized.rpdbApiKey.isNotBlank() ||
            normalized.topPostersApiKey.isNotBlank()
        ) {
            add(AccountSettingsSectionKey.INTEGRATIONS_POSTER_RATINGS)
        }
        if (normalized.premiumizeApiKey.isNotBlank()) add(AccountSettingsSectionKey.INTEGRATIONS_DEBRID_PREMIUMIZE)
        if (normalized.torBoxApiKey.isNotBlank()) add(AccountSettingsSectionKey.INTEGRATIONS_DEBRID_TOR_BOX)
        if (normalized.easyDebridApiKey.isNotBlank()) add(AccountSettingsSectionKey.INTEGRATIONS_DEBRID_EASY_DEBRID)
        if (
            normalized.realDebrid.accessToken.isNotBlank() ||
            normalized.realDebrid.refreshToken.isNotBlank() ||
            normalized.realDebrid.userClientId.isNotBlank() ||
            normalized.realDebrid.userClientSecret.isNotBlank()
        ) {
            add(AccountSettingsSectionKey.INTEGRATIONS_DEBRID_REAL_DEBRID)
        }
        if (
            normalized.trakt.accessToken.isNotBlank() ||
            normalized.trakt.refreshToken.isNotBlank()
        ) {
            add(AccountSettingsSectionKey.INTEGRATIONS_TRAKT_AUTH)
        }
        if (normalized.simkl.accessToken.isNotBlank()) add(AccountSettingsSectionKey.INTEGRATIONS_SIMKL_AUTH)
        if (
            normalized.kitsu.accessToken.isNotBlank() ||
            normalized.kitsu.refreshToken.isNotBlank()
        ) {
            add(AccountSettingsSectionKey.INTEGRATIONS_KITSU_AUTH)
        }
    }
}

private fun AccountSecretPushSnapshot.normalizedForPush(): AccountSecretPushSnapshot {
    return copy(
        mdbListApiKey = mdbListApiKey.trim(),
        omdbApiKey = omdbApiKey.trim(),
        subtitleTranslationApiKey = subtitleTranslationApiKey.trim(),
        legacyGeminiApiKey = legacyGeminiApiKey?.trim(),
        animeSkipClientId = animeSkipClientId.trim(),
        rpdbApiKey = rpdbApiKey.trim(),
        topPostersApiKey = topPostersApiKey.trim(),
        premiumizeApiKey = premiumizeApiKey.trim(),
        torBoxApiKey = torBoxApiKey.trim(),
        easyDebridApiKey = easyDebridApiKey.trim(),
        realDebrid = realDebrid.copy(
            accessToken = realDebrid.accessToken.trim(),
            refreshToken = realDebrid.refreshToken.trim(),
            userClientId = realDebrid.userClientId.trim(),
            userClientSecret = realDebrid.userClientSecret.trim()
        ),
        trakt = trakt.copy(
            accessToken = trakt.accessToken.trim(),
            refreshToken = trakt.refreshToken.trim()
        ),
        simkl = simkl.copy(accessToken = simkl.accessToken.trim()),
        kitsu = kitsu.copy(
            accessToken = kitsu.accessToken.trim(),
            refreshToken = kitsu.refreshToken.trim()
        )
    )
}

internal fun emptyAccountSecretPushSnapshot(): AccountSecretPushSnapshot {
    return AccountSecretPushSnapshot(
        mdbListApiKey = "",
        omdbApiKey = "",
        subtitleTranslationApiKey = "",
        legacyGeminiApiKey = null,
        animeSkipClientId = "",
        rpdbApiKey = "",
        topPostersApiKey = "",
        premiumizeApiKey = "",
        torBoxApiKey = "",
        easyDebridApiKey = "",
        realDebrid = RealDebridSecretPushSnapshot(
            accessToken = "",
            refreshToken = "",
            tokenType = "Bearer",
            expiresIn = 0,
            userClientId = "",
            userClientSecret = ""
        ),
        trakt = TraktSecretPushSnapshot(
            accessToken = "",
            refreshToken = "",
            tokenType = "bearer",
            createdAt = 0L,
            expiresIn = 0
        ),
        simkl = SimklSecretPushSnapshot(accessToken = ""),
        kitsu = KitsuSecretPushSnapshot(accessToken = "", refreshToken = "", expiresAtEpochSeconds = null)
    )
}

internal fun accountSecretBaselineAfterPull(
    current: AccountSecretPushSnapshot,
    existing: AccountSecretPushSnapshot?,
    appliedSectionKeys: Set<AccountSettingsSectionKey>,
    preserveLocalSectionKeys: Set<AccountSettingsSectionKey>
): AccountSecretPushSnapshot? {
    val syncedSectionKeys = appliedSectionKeys - preserveLocalSectionKeys
    if (syncedSectionKeys.isEmpty()) return existing
    val base = existing ?: emptyAccountSecretPushSnapshot()
    return base.withSectionsFrom(current.normalizedForPush(), syncedSectionKeys)
}

private fun AccountSecretPushSnapshot.withSectionsFrom(
    source: AccountSecretPushSnapshot,
    sectionKeys: Set<AccountSettingsSectionKey>
): AccountSecretPushSnapshot {
    var merged = this
    sectionKeys.forEach { sectionKey ->
        merged = when (sectionKey) {
            AccountSettingsSectionKey.INTEGRATIONS_MDBLIST ->
                merged.copy(mdbListApiKey = source.mdbListApiKey)
            AccountSettingsSectionKey.INTEGRATIONS_OMDB ->
                merged.copy(omdbApiKey = source.omdbApiKey)
            AccountSettingsSectionKey.INTEGRATIONS_SUBTITLE_TRANSLATION,
            AccountSettingsSectionKey.INTEGRATIONS_GEMINI ->
                merged.copy(
                    subtitleTranslationApiKey = source.subtitleTranslationApiKey,
                    legacyGeminiApiKey = source.legacyGeminiApiKey
                )
            AccountSettingsSectionKey.INTEGRATIONS_ANIME_SKIP ->
                merged.copy(animeSkipClientId = source.animeSkipClientId)
            AccountSettingsSectionKey.INTEGRATIONS_POSTER_RATINGS ->
                merged.copy(
                    rpdbApiKey = source.rpdbApiKey,
                    topPostersApiKey = source.topPostersApiKey
                )
            AccountSettingsSectionKey.INTEGRATIONS_DEBRID_PREMIUMIZE ->
                merged.copy(premiumizeApiKey = source.premiumizeApiKey)
            AccountSettingsSectionKey.INTEGRATIONS_DEBRID_TOR_BOX ->
                merged.copy(torBoxApiKey = source.torBoxApiKey)
            AccountSettingsSectionKey.INTEGRATIONS_DEBRID_EASY_DEBRID ->
                merged.copy(easyDebridApiKey = source.easyDebridApiKey)
            AccountSettingsSectionKey.INTEGRATIONS_DEBRID_REAL_DEBRID ->
                merged.copy(realDebrid = source.realDebrid)
            AccountSettingsSectionKey.INTEGRATIONS_TRAKT_AUTH ->
                merged.copy(trakt = source.trakt)
            AccountSettingsSectionKey.INTEGRATIONS_SIMKL_AUTH ->
                merged.copy(simkl = source.simkl)
            AccountSettingsSectionKey.INTEGRATIONS_KITSU_AUTH ->
                merged.copy(kitsu = source.kitsu)
            else -> merged
        }
    }
    return merged
}

@Singleton
class AccountSettingsSyncService @Inject constructor(
    private val authManager: AuthManager,
    private val postgrest: Postgrest,
    private val themeDataStore: ThemeDataStore,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    private val tvdbSettingsDataStore: TvdbSettingsDataStore,
    private val mdbListSettingsDataStore: MDBListSettingsDataStore,
    private val omdbSettingsDataStore: OmdbSettingsDataStore,
    private val animeSkipSettingsDataStore: AnimeSkipSettingsDataStore,
    private val subtitleTranslationSettingsDataStore: SubtitleTranslationSettingsDataStore,
    private val posterRatingsSettingsDataStore: PosterRatingsSettingsDataStore,
    private val premiumizeSettingsDataStore: PremiumizeSettingsDataStore,
    private val premiumizeService: PremiumizeService,
    private val torBoxSettingsDataStore: TorBoxSettingsDataStore,
    private val torBoxService: TorBoxService,
    private val easyDebridSettingsDataStore: EasyDebridSettingsDataStore,
    private val easyDebridService: EasyDebridService,
    private val realDebridAuthDataStore: RealDebridAuthDataStore,
    private val traktAuthDataStore: TraktAuthDataStore,
    private val simklAuthDataStore: SimklAuthDataStore,
    private val kitsuAuthDataStore: KitsuAuthDataStore,
    private val traktSettingsDataStore: TraktSettingsDataStore,
    private val simklSettingsDataStore: SimklSettingsDataStore,
    private val tmdbCatalogSettingsDataStore: TmdbCatalogSettingsDataStore,
    private val kitsuCatalogSettingsDataStore: KitsuCatalogSettingsDataStore,
    private val homeRailOrderStore: HomeRailOrderStore,
    private val debugSettingsDataStore: DebugSettingsDataStore,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val profileManager: ProfileManager,
    private val profileModeRouter: ProfileModeRouter,
    private val startupPushGate: AccountConfigStartupPushGate,
    private val syncWatermarkStore: SyncWatermarkDataStore,
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pushJob: Job? = null

    @Volatile
    private var isApplyingRemote = false
    private val applyingRemoteMutex = Mutex()

    // Generation counter: incremented on every profile switch, cleared after the
    // post-switch pull succeeds. Replaces the fixed 2-second boolean window, which
    // had a TOCTOU gap between the check and push launch, and could expire before
    // initial DataStore emissions arrived under storage pressure.
    @Volatile private var suppressPushForSwitchGeneration: Long = 0L
    private var currentSwitchGeneration: Long = 0L

    private val pendingChangedPaths = linkedSetOf<String>()
    private var pendingChangedPathsGeneration: Long = 0L

    @Volatile
    private var lastAppliedRemoteRevision: Long = 0L
    @Volatile
    private var lastSyncedAccountSecretSnapshot: AccountSecretPushSnapshot? = null
    private var lastRemoteTraktPinnedListOptions: List<TraktPinnedListOptionSync> = emptyList()
    private var lastRemoteMDBListPinnedTopListOptions: List<MDBListPinnedListOptionSync> = emptyList()

    init {
        observeLocalChanges()
        observeProfileSwitches()
    }

    private fun observeProfileSwitches() {
        scope.launch {
            profileManager.activeProfileId.drop(1).collect {
                val gen = ++currentSwitchGeneration
                suppressPushForSwitchGeneration = gen
                pushJob?.cancel()
                pushJob = null
                // Suppression is cleared by clearSuppression(gen) after the post-switch
                // pull succeeds in pullFromRemoteAndApply(), not by a fixed timeout.
            }
        }
    }

    private fun clearSuppression(gen: Long) {
        if (suppressPushForSwitchGeneration == gen) suppressPushForSwitchGeneration = 0L
    }

    private fun observeLocalChanges() {
        scope.launch {
            observeAccountConfigSyncChangedPaths(
                // Default profile keeps these in the account contract; secondary
                // profiles sync them through ProfileSettingsSyncService blobs.
                heroCatalogSelections = layoutPreferenceDataStore.heroCatalogSelections.drop(1).map { Unit },
                homeCatalogOrderKeys = layoutPreferenceDataStore.homeCatalogOrderKeys.drop(1).map { Unit },
                disabledHomeCatalogKeys = layoutPreferenceDataStore.disabledHomeCatalogKeys.drop(1).map { Unit },
                tmdbSettings = tmdbSettingsDataStore.settings.drop(1).map { Unit },
                mdbListSettings = mdbListSettingsDataStore.settings.drop(1).map { Unit },
                mdbListCatalogPreferences = mdbListSettingsDataStore.catalogPreferences.drop(1).map { Unit },
                omdbSettings = omdbSettingsDataStore.settings.drop(1).map { Unit },
                animeSkipEnabled = animeSkipSettingsDataStore.enabled.drop(1).map { Unit },
                subtitleTranslationSettings = subtitleTranslationSettingsDataStore.settings.drop(1).map { Unit },
                posterRatingsSettings = posterRatingsSettingsDataStore.settings.drop(1).map { Unit },
                premiumizeSettings = premiumizeSettingsDataStore.settings.drop(1).map { Unit },
                premiumizeAccountState = premiumizeService.observeAccountState().drop(1).map { Unit },
                torBoxSettings = torBoxSettingsDataStore.settings.drop(1).map { Unit },
                torBoxAccountState = torBoxService.observeAccountState().drop(1).map { Unit },
                easyDebridSettings = easyDebridSettingsDataStore.settings.drop(1).map { Unit },
                easyDebridAccountState = easyDebridService.observeAccountState().drop(1).map { Unit },
                realDebridState = realDebridAuthDataStore.state.drop(1).map { Unit },
                kitsuAuthState = kitsuAuthDataStore.stateForProfile(profileModeRouter.defaultLegacyProfileId()).drop(1).map { Unit },
                traktAuthState = traktAuthDataStore.stateForProfile(profileModeRouter.defaultLegacyProfileId()).drop(1).map { Unit },
                // Default profile keeps these in the account contract; secondary
                // profiles sync them through ProfileSettingsSyncService blobs.
                traktCatalogPreferences = traktSettingsDataStore.catalogPreferences.drop(1).map { Unit },
                simklCatalogPreferences = simklSettingsDataStore.catalogPreferences.drop(1).map { Unit },
                simklAuthState = simklAuthDataStore.stateForProfile(profileModeRouter.defaultLegacyProfileId()).drop(1).map { Unit },
                playerSettings = playerSettingsDataStore.playerSettings.drop(1).map { Unit }
            ).collect { changedPath ->
                if (!isDefaultLegacyActive() && isPrimaryProfileAccountPath(changedPath)) {
                    return@collect
                }
                if (isApplyingRemote || suppressPushForSwitchGeneration != 0L) return@collect
                synchronized(pendingChangedPaths) {
                    pendingChangedPaths.add(changedPath)
                    pendingChangedPathsGeneration += 1L
                }
                schedulePush()
            }
        }
    }

    private fun schedulePush() {
        if (isApplyingRemote || suppressPushForSwitchGeneration != 0L) return
        val userId = liveSessionUserId() ?: return
        if (!startupPushGate.canPush(userId)) {
            Log.d(TAG, "Skipping account settings push before startup remote pull completes")
            return
        }

        pushJob?.cancel()
        pushJob = scope.launch {
            delay(500)
            pushToRemote()
        }
    }

    private fun isPrimaryProfileAccountPath(path: String): Boolean {
        return path == "formatter" ||
            path.startsWith("catalogs.home") ||
            path.startsWith("catalogs.trakt") ||
            path.startsWith("catalogs.simkl") ||
            path == "integrations.kitsuAuth" ||
            path == "integrations.traktAuth" ||
            path == "integrations.simklAuth" ||
            path.startsWith("playback.streamSelection")
    }

    private fun isDefaultLegacyActive(): Boolean {
        return profileModeRouter.isDefaultLegacy(profileManager.activeProfileId.value)
    }

    fun onStartupSyncUserChanged(userId: String?) {
        if (startupPushGate.onSessionUserChanged(userId)) {
            pushJob?.cancel()
            pushJob = null
        }
    }

    fun markStartupRemotePullSucceeded(userId: String) {
        startupPushGate.markRemotePullSucceeded(userId)
    }

    /**
     * v10 wrapper for `sync_set_account_secret`. Reads the current
     * ACCOUNT_SECRETS watermark, injects it as `p_base_updated_at_ms`, and on
     * applied responses advances the watermark. Stale-base rejections keep the
     * old base because advancing without resolving the remote secret payload
     * would permit an older local value to overwrite the newer remote value.
     */
    private suspend fun setAccountSecretV10(extraParams: JsonObject): Boolean {
        val baseMs = syncWatermarkStore.get(SyncWatermarkSurface.ACCOUNT_SECRETS, profileId = null)
        val params = JsonObject(extraParams + ("p_base_updated_at_ms" to JsonPrimitive(baseMs)))
        val outcome = runV10Push {
            withJwtRefreshRetry {
                postgrest.rpc("sync_set_account_secret_v10", params).decodeAs<V10PushResult>()
            }
        }
        when (outcome) {
            is V10PushOutcome.Applied -> {
                syncWatermarkStore.set(SyncWatermarkSurface.ACCOUNT_SECRETS, profileId = null, ms = outcome.currentUpdatedAtMs)
                return true
            }
            is V10PushOutcome.StaleBase -> {
                Log.w(
                    TAG,
                    "setAccountSecretV10 stale (server=${outcome.currentUpdatedAtMs}, base=$baseMs); " +
                        "preserving local dirty secret until a pull resolves the remote payload"
                )
                return false
            }
            is V10PushOutcome.Failed -> throw outcome.cause
            is V10PushOutcome.FieldConflict -> return false
        }
    }

    /** v10 wrapper for `sync_delete_account_secret`. See [setAccountSecretV10]. */
    private suspend fun deleteAccountSecretV10(extraParams: JsonObject): Boolean {
        val baseMs = syncWatermarkStore.get(SyncWatermarkSurface.ACCOUNT_SECRETS, profileId = null)
        val params = JsonObject(extraParams + ("p_base_updated_at_ms" to JsonPrimitive(baseMs)))
        val outcome = runV10Push {
            withJwtRefreshRetry {
                postgrest.rpc("sync_delete_account_secret_v10", params).decodeAs<V10PushResult>()
            }
        }
        when (outcome) {
            is V10PushOutcome.Applied -> {
                syncWatermarkStore.set(SyncWatermarkSurface.ACCOUNT_SECRETS, profileId = null, ms = outcome.currentUpdatedAtMs)
                return true
            }
            is V10PushOutcome.StaleBase -> {
                Log.w(
                    TAG,
                    "deleteAccountSecretV10 stale (server=${outcome.currentUpdatedAtMs}, base=$baseMs); " +
                        "preserving local dirty secret until a pull resolves the remote payload"
                )
                return false
            }
            is V10PushOutcome.Failed -> throw outcome.cause
            is V10PushOutcome.FieldConflict -> return false
        }
    }

    private suspend fun <T> withJwtRefreshRetry(block: suspend () -> T): T {
        return try {
            block()
        } catch (e: Exception) {
            if (!authManager.refreshSessionIfJwtExpired(e)) throw e
            block()
        }
    }

    private data class AccountPushSnapshot(
        val payload: AccountConfigSyncPayload,
        val changedPaths: List<String>,
        val changedPathsGeneration: Long,
        val secrets: AccountSecretPushSnapshot
    )

    private data class ResolvedRemoteSecretsForApply(
        val mdbListApiKey: String?,
        val omdbApiKey: String?,
        val subtitleTranslationApiKey: String?,
        val animeSkipClientId: String?,
        val rpdbApiKey: String?,
        val topPostersApiKey: String?,
        val premiumizeApiKey: String?,
        val torBoxApiKey: String?,
        val easyDebridApiKey: String?,
        val realDebrid: ResolvedRemoteRealDebridSecrets?,
        val trakt: ResolvedRemoteTraktSecrets?,
        val simkl: ResolvedRemoteSimklSecrets?,
        val kitsu: ResolvedRemoteKitsuSecrets?,
        val preservedLocalSectionKeys: Set<AccountSettingsSectionKey> = emptySet(),
        val unresolvedRemoteSecretSectionKeys: Set<AccountSettingsSectionKey> = emptySet(),
        val followUpLocalSecretSectionKeys: Set<AccountSettingsSectionKey> = emptySet()
    )

    private data class ResolvedRemoteRealDebridSecrets(
        val accessPayload: AccountRealDebridAccessSecretPayload?,
        val refreshPayload: AccountRealDebridRefreshSecretPayload?,
        val preserveLocalTokens: Boolean,
        val remote: RealDebridSyncSettings
    )

    private data class ResolvedRemoteTraktSecrets(
        val accessPayload: AccountTraktAccessSecretPayload?,
        val refreshPayload: AccountTraktRefreshSecretPayload?,
        val preserveLocalTokens: Boolean,
        val remote: TraktAuthSyncSettings
    )

    private data class ResolvedRemoteSimklSecrets(
        val accessPayload: AccountSimklAccessSecretPayload?,
        val preserveLocalTokens: Boolean,
        val remote: SimklAuthSyncSettings
    )

    private data class ResolvedRemoteKitsuSecrets(
        val accessPayload: AccountKitsuAccessSecretPayload?,
        val refreshPayload: AccountKitsuRefreshSecretPayload?,
        val preserveLocalTokens: Boolean,
        val remote: KitsuAuthSyncSettings
    )

    suspend fun pushToRemote(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (isApplyingRemote) return@withContext Result.success(Unit)
            val snapshot = applyingRemoteMutex.withLock {
                if (isApplyingRemote || !hasLiveFullAccountSession()) return@withLock null
                val payload = buildLocalPayload()
                val (changedPaths, changedPathsGeneration) = synchronized(pendingChangedPaths) {
                    pendingChangedPaths.toList() to pendingChangedPathsGeneration
                }
                AccountPushSnapshot(
                    payload = payload,
                    changedPaths = changedPaths,
                    changedPathsGeneration = changedPathsGeneration,
                    secrets = buildAccountSecretPushSnapshot()
                )
            } ?: return@withContext Result.success(Unit)

            var scheduleFollowUpPush = false
            val dirtySettingsSectionKeys = dirtyAccountSettingsSectionKeys(
                current = snapshot.payload,
                baseline = syncWatermarkStore.getAccountSettingsSectionBaselines()
            )
            val dirtySecretSectionKeys = dirtyAccountSecretSectionKeys(snapshot.secrets)

            if (dirtySettingsSectionKeys.isNotEmpty()) {
                if (!hasLiveFullAccountSession()) return@withContext Result.success(Unit)
                val changedPathsBySection = snapshot.changedPaths
                    .mapNotNull { changedPath ->
                        AccountSettingsSectionKey.fromChangedPath(changedPath)
                            ?.let { sectionKey -> sectionKey to changedPath }
                    }
                    .groupBy(
                        keySelector = { it.first },
                        valueTransform = { it.second }
                    )
                val pushableChangedPathsBySection = changedPathsBySection
                    .filterKeys { sectionKey -> snapshot.payload.sectionPayload(sectionKey) != null }

                val pushableDirtySectionKeys = dirtySettingsSectionKeys
                    .filter { sectionKey -> snapshot.payload.sectionPayload(sectionKey) != null }
                    .toSet()

                if (pushableDirtySectionKeys.isNotEmpty()) {
                    val params = buildAccountSettingsSectionsPushParamsV13(
                        payload = snapshot.payload,
                        sectionKeys = pushableDirtySectionKeys,
                        watermarkStore = syncWatermarkStore
                    )

                    val result = withJwtRefreshRetry {
                        postgrest.rpc("sync_push_account_settings_sections_v13", params)
                            .decodeAs<V13BatchPushResult>()
                    }

                    val appliedChangedPaths = linkedSetOf<String>()
                    val appliedSectionKeys = linkedSetOf<AccountSettingsSectionKey>()
                    var maxAppliedRevision: Long? = null
                    var hasStaleSection = false
                    result.sections.forEach { result ->
                        val sectionKey = AccountSettingsSectionKey.fromKey(result.sectionKey)
                            ?: return@forEach
                        if (result.applied) {
                            if (result.currentUpdatedAtMs != null) {
                                syncWatermarkStore.setAccountSettingsSection(sectionKey, result.currentUpdatedAtMs)
                            }
                            result.syncRevision?.let { revision ->
                                maxAppliedRevision = maxOf(maxAppliedRevision ?: revision, revision)
                            }
                            appliedSectionKeys += sectionKey
                            appliedChangedPaths += pushableChangedPathsBySection[sectionKey].orEmpty()
                        } else if (result.reason == "stale_base") {
                            hasStaleSection = true
                        }
                    }

                    syncWatermarkStore.setAccountSettingsSectionBaselines(
                        accountSettingsSectionBaselinePayloads(snapshot.payload)
                            .filterKeys { sectionKey -> sectionKey in appliedSectionKeys }
                    )

                    if (appliedChangedPaths.isNotEmpty()) {
                        applyingRemoteMutex.withLock {
                            if (isApplyingRemote || !hasLiveFullAccountSession()) return@withLock
                            maxAppliedRevision?.let { revision ->
                                lastAppliedRemoteRevision = maxOf(lastAppliedRemoteRevision, revision)
                            }
                            synchronized(pendingChangedPaths) {
                                if (!clearAppliedChangedPathsForGeneration(
                                        pendingChangedPaths = pendingChangedPaths,
                                        pendingChangedPathsGeneration = pendingChangedPathsGeneration,
                                        snapshotChangedPathsGeneration = snapshot.changedPathsGeneration,
                                        appliedChangedPaths = appliedChangedPaths
                                    )
                                ) {
                                    scheduleFollowUpPush = true
                                }
                            }
                        }
                    }

                    if (hasStaleSection) {
                        val preserveLocalSectionKeys = synchronized(pendingChangedPaths) {
                            if (pendingChangedPathsGeneration != snapshot.changedPathsGeneration) {
                                scheduleFollowUpPush = true
                            }
                            buildStaleRecoveryPreserveLocalSectionKeys(
                                pendingChangedPaths = pendingChangedPaths.toSet(),
                                dirtySettingsSectionKeys = dirtySettingsSectionKeys - appliedSectionKeys,
                                dirtySecretSectionKeys = dirtySecretSectionKeys
                            )
                        }
                        scheduleFollowUpPush = true
                        Log.w(TAG, "Account settings section push stale; pulling without clearing pending local changes")
                        val staleRecoveryResult = pullFromRemoteAndApply(
                            clearPendingChanges = false,
                            preserveLocalSectionKeys = preserveLocalSectionKeys
                        )
                        if (staleRecoveryResult.isFailure) {
                            return@withContext Result.failure(
                                staleRecoveryResult.exceptionOrNull()
                                    ?: IllegalStateException("Account settings stale recovery pull failed")
                            )
                        }
                        if (scheduleFollowUpPush) {
                            pushJob = scope.launch {
                                delay(500)
                                pushToRemote()
                            }
                        }
                        return@withContext Result.success(Unit)
                    }
                }
            }

            if (!hasLiveFullAccountSession()) return@withContext Result.success(Unit)
            val secretPushSucceeded = syncAccountSecretPushSnapshotToRemote(snapshot.secrets, dirtySecretSectionKeys)
            if (secretPushSucceeded) {
                if (dirtySecretSectionKeys.isNotEmpty()) {
                    lastSyncedAccountSecretSnapshot = snapshot.secrets.normalizedForPush()
                }
                if (snapshot.changedPaths.isNotEmpty()) {
                    applyingRemoteMutex.withLock {
                        if (isApplyingRemote || !hasLiveFullAccountSession()) return@withLock
                        synchronized(pendingChangedPaths) {
                            if (!clearAppliedChangedPathsForGeneration(
                                    pendingChangedPaths = pendingChangedPaths,
                                    pendingChangedPathsGeneration = pendingChangedPathsGeneration,
                                    snapshotChangedPathsGeneration = snapshot.changedPathsGeneration,
                                    appliedChangedPaths = snapshot.changedPaths.toSet()
                                )
                            ) {
                                scheduleFollowUpPush = true
                            }
                        }
                    }
                }
            } else {
                val baseBeforeRecovery = syncWatermarkStore.get(SyncWatermarkSurface.ACCOUNT_SECRETS, profileId = null)
                val preserveLocalSectionKeys = synchronized(pendingChangedPaths) {
                    buildStaleRecoveryPreserveLocalSectionKeys(
                        pendingChangedPaths = pendingChangedPaths.toSet(),
                        dirtySettingsSectionKeys = dirtySettingsSectionKeys,
                        dirtySecretSectionKeys = dirtySecretSectionKeys
                    )
                }
                Log.w(TAG, "Account secret push did not fully apply; pulling remote before retrying dirty secrets")
                val recoveryResult = pullFromRemoteAndApply(
                    clearPendingChanges = false,
                    preserveLocalSectionKeys = preserveLocalSectionKeys
                )
                if (recoveryResult.isFailure) {
                    return@withContext Result.failure(
                        recoveryResult.exceptionOrNull()
                            ?: IllegalStateException("Account secret stale recovery pull failed")
                    )
                }
                val baseAfterRecovery = syncWatermarkStore.get(SyncWatermarkSurface.ACCOUNT_SECRETS, profileId = null)
                scheduleFollowUpPush = baseAfterRecovery > baseBeforeRecovery
            }

            if (scheduleFollowUpPush) {
                pushJob = scope.launch {
                    delay(500)
                    pushToRemote()
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push account settings to remote", e)
            Result.failure(e)
        }
    }

    suspend fun pullFromRemoteAndApply(
        clearPendingChanges: Boolean = true,
        preserveLocalSectionKeys: Set<AccountSettingsSectionKey> = emptySet()
    ): Result<List<AddonPreferences.AddonInstallConfig>> = withContext(Dispatchers.IO) {
        try {
            if (!hasLiveFullAccountSession()) {
                return@withContext Result.failure(IllegalStateException("No live full account session"))
            }
            val pullStartedGeneration = synchronized(pendingChangedPaths) { pendingChangedPathsGeneration }
            val switchGenAtPullStart = suppressPushForSwitchGeneration
            val envelope = withJwtRefreshRetry {
                postgrest.rpc("sync_pull_account_snapshot_v13")
                    .decodeAs<V13AccountSnapshotEnvelope>()
            }
            var settings = AccountConfigSyncPayload(schemaVersion = ACCOUNT_CONFIG_SYNC_CONTRACT_VERSION)
            val appliedSections = mutableListOf<Pair<AccountSettingsSectionKey, Long>>()
            envelope.settings.sections.forEach { section ->
                val key = AccountSettingsSectionKey.fromKey(section.sectionKey)
                if (key == null) {
                    Log.d(TAG, "Ignoring unknown account settings section ${section.sectionKey}")
                    return@forEach
                }
                settings = key.applyToPayload(settings, section.payload)
                appliedSections += key to section.updatedAtMs
            }
            val appliedSectionKeys = appliedSections.map { it.first }.toSet()
            syncWatermarkStore.set(SyncWatermarkSurface.ACCOUNT_SETTINGS, profileId = null, ms = envelope.settings.updatedAtMs)
            appliedSections.forEach { (key, updatedAtMs) ->
                syncWatermarkStore.setAccountSettingsSection(key, updatedAtMs)
            }
            syncWatermarkStore.set(SyncWatermarkSurface.ACCOUNT_ADDONS, profileId = null, ms = envelope.addons.updatedAtMs)
            val settingsRevision = envelope.settings.sections.maxOfOrNull { it.syncRevision } ?: lastAppliedRemoteRevision
            val sectionKeysToApply = appliedSectionKeys - preserveLocalSectionKeys
            val preservedPullSecretSectionKeys = preserveLocalSectionKeys.intersect(ACCOUNT_SECRET_SECTION_KEYS)
            val sectionKeysToResolveSecretsFor = sectionKeysToApply + preservedPullSecretSectionKeys
            val resolvedSecrets = resolveRemoteSecretsForApply(settings, sectionKeysToResolveSecretsFor)
            if (resolvedSecrets.unresolvedRemoteSecretSectionKeys.isEmpty()) {
                syncWatermarkStore.set(SyncWatermarkSurface.ACCOUNT_SECRETS, profileId = null, ms = envelope.secrets.updatedAtMs)
            } else {
                Log.w(
                    TAG,
                    "Not advancing account secrets watermark; unresolved=${resolvedSecrets.unresolvedRemoteSecretSectionKeys}, preserved=$preservedPullSecretSectionKeys"
                )
            }
            val secretBaselinePreserveSectionKeys = preserveLocalSectionKeys + resolvedSecrets.preservedLocalSectionKeys
            val scheduleSecretFollowUpPush = resolvedSecrets.followUpLocalSecretSectionKeys.isNotEmpty() &&
                resolvedSecrets.unresolvedRemoteSecretSectionKeys.isEmpty()

            var appliedRemoteSettings = false
            applyingRemoteMutex.withLock {
                if (!hasLiveFullAccountSession()) {
                    return@withLock
                }
                isApplyingRemote = true
                try {
                    applySharedAccountConfigSyncSettings(
                        settings = settings,
                        sectionKeys = sectionKeysToApply
                    )
                    applyResolvedRemoteSecrets(resolvedSecrets, sectionKeysToApply)
                    updateLastSyncedAccountSecretBaselineAfterPull(
                        current = buildAccountSecretPushSnapshot(),
                        appliedSectionKeys = sectionKeysToApply,
                        preserveLocalSectionKeys = secretBaselinePreserveSectionKeys
                    )
                    syncWatermarkStore.setAccountSettingsSectionBaselines(
                        accountSettingsSectionBaselinePayloads(buildLocalPayload())
                            .filterKeys { sectionKey -> sectionKey !in preserveLocalSectionKeys }
                    )
                    lastAppliedRemoteRevision = settingsRevision
                    clearSuppression(switchGenAtPullStart)
                    if (clearPendingChanges) {
                        synchronized(pendingChangedPaths) {
                            if (pendingChangedPathsGeneration == pullStartedGeneration) {
                                pendingChangedPaths.clear()
                            }
                        }
                    }
                    appliedRemoteSettings = true
                } finally {
                    isApplyingRemote = false
                }
            }
            if (!appliedRemoteSettings) {
                return@withContext Result.failure(IllegalStateException("No live full account session"))
            }

            refreshDebridAccountStatesForAppliedSections(sectionKeysToApply)
            if (scheduleSecretFollowUpPush && hasLiveFullAccountSession()) {
                pushJob = scope.launch {
                    delay(500)
                    pushToRemote()
                }
            }

            if (!hasLiveFullAccountSession()) {
                return@withContext Result.failure(IllegalStateException("No live full account session"))
            }
            val remoteAddonConfigs = buildRemoteAddonInstallConfigs(envelope.addons.items, ::resolveRemoteAddonUrl)
            if (!hasLiveFullAccountSession()) {
                return@withContext Result.failure(IllegalStateException("No live full account session"))
            }
            Result.success(remoteAddonConfigs)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull account snapshot from remote", e)
            Result.failure(e)
        }
    }

    private suspend fun refreshDebridAccountStatesForAppliedSections(sectionKeys: Set<AccountSettingsSectionKey>) {
        if (sectionKeys.includesSection(AccountSettingsSectionKey.INTEGRATIONS_DEBRID_PREMIUMIZE)) {
            premiumizeService.refreshAccountState()
        }
        if (sectionKeys.includesSection(AccountSettingsSectionKey.INTEGRATIONS_DEBRID_TOR_BOX)) {
            torBoxService.refreshAccountState()
        }
        if (sectionKeys.includesSection(AccountSettingsSectionKey.INTEGRATIONS_DEBRID_EASY_DEBRID)) {
            easyDebridService.refreshAccountState()
        }
    }

    private fun hasLiveFullAccountSession(): Boolean {
        return hasLiveFullAccountSyncSession(
            authState = authManager.authState.value,
            sessionUserId = authManager.currentSessionUserId
        )
    }

    private fun liveSessionUserId(): String? {
        return liveFullAccountSessionUserId(
            authState = authManager.authState.value,
            sessionUserId = authManager.currentSessionUserId
        )
    }

    private fun dirtyAccountSecretSectionKeys(
        current: AccountSecretPushSnapshot
    ): Set<AccountSettingsSectionKey> {
        return dirtyAccountSecretSectionKeys(current, lastSyncedAccountSecretSnapshot)
    }

    private fun updateLastSyncedAccountSecretBaselineAfterPull(
        current: AccountSecretPushSnapshot,
        appliedSectionKeys: Set<AccountSettingsSectionKey>,
        preserveLocalSectionKeys: Set<AccountSettingsSectionKey>
    ) {
        lastSyncedAccountSecretSnapshot = accountSecretBaselineAfterPull(
            current = current,
            existing = lastSyncedAccountSecretSnapshot,
            appliedSectionKeys = appliedSectionKeys,
            preserveLocalSectionKeys = preserveLocalSectionKeys
        )
    }

    private suspend fun buildLocalPayload(): AccountConfigSyncPayload {
        val tmdb = tmdbSettingsDataStore.settings.first()
        val mdbList = mdbListSettingsDataStore.settings.first()
        val mdbListPrefs = mdbListSettingsDataStore.catalogPreferences.first()
        val isPrimaryProfile = isDefaultLegacyActive()
        val heroCatalogKeys = if (isPrimaryProfile) layoutPreferenceDataStore.heroCatalogSelections.first() else emptyList()
        val homeCatalogOrderKeys = if (isPrimaryProfile) layoutPreferenceDataStore.homeCatalogOrderKeys.first() else emptyList()
        val disabledHomeCatalogKeys = if (isPrimaryProfile) layoutPreferenceDataStore.disabledHomeCatalogKeys.first() else emptyList()
        val traktCatalogPrefs = if (isPrimaryProfile) traktSettingsDataStore.catalogPreferences.first() else null
        val simklCatalogPrefs = if (isPrimaryProfile) simklSettingsDataStore.catalogPreferences.first() else null
        val playerSettings = if (isPrimaryProfile) playerSettingsDataStore.playerSettings.first() else null
        val animeSkipEnabled = animeSkipSettingsDataStore.enabled.first()
        val subtitleTranslation = subtitleTranslationSettingsDataStore.settings.first()
        val posterRatings = posterRatingsSettingsDataStore.settings.first()
        val premiumize = premiumizeSettingsDataStore.settings.first()
        val premiumizeAccount = premiumizeService.observeAccountState().first()
        val torBox = torBoxSettingsDataStore.settings.first()
        val torBoxAccount = torBoxService.observeAccountState().first()
        val easyDebrid = easyDebridSettingsDataStore.settings.first()
        val easyDebridAccount = easyDebridService.observeAccountState().first()
        val realDebrid = realDebridAuthDataStore.state.first()
        val defaultProfileId = profileModeRouter.defaultLegacyProfileId()
        val traktAuth = traktAuthDataStore.stateForProfile(defaultProfileId).first()
        val simklAuth = simklAuthDataStore.stateForProfile(defaultProfileId).first()
        val kitsuAuth = kitsuAuthDataStore.stateForProfile(defaultProfileId).first()

        return buildAccountConfigSyncPayload(
            integrations = IntegrationSettings(
                debrid = DebridSyncSettings(
                    premiumize = PremiumizeSyncSettings(
                        configured = premiumize.isConfigured,
                        customerId = premiumizeAccount.customerId
                    ),
                    torBox = TorBoxSyncSettings(
                        configured = torBox.isConfigured,
                        email = torBoxAccount.email.orEmpty(),
                        plan = torBoxAccount.plan.orEmpty()
                    ),
                    easyDebrid = EasyDebridSyncSettings(
                        configured = easyDebrid.isConfigured,
                        userId = easyDebridAccount.userId.orEmpty(),
                        paidUntil = easyDebridAccount.paidUntil.orEmpty()
                    ),
                    realDebrid = RealDebridSyncSettings(
                        connected = realDebrid.isAuthenticated,
                        username = realDebrid.username.orEmpty(),
                        pending = !realDebrid.isAuthenticated && !realDebrid.deviceCode.isNullOrBlank(),
                        deviceCode = realDebrid.deviceCode.orEmpty(),
                        userCode = realDebrid.userCode.orEmpty(),
                        verificationUrl = realDebrid.verificationUrl.orEmpty(),
                        expiresAt = realDebrid.expiresAt
                    )
                ),
                tmdb = TmdbSyncSettings(
                    enabled = true,
                    useArtwork = tmdb.useArtwork,
                    useBasicInfo = tmdb.useBasicInfo,
                    useDetails = tmdb.useDetails,
                    useCredits = tmdb.useCredits,
                    useProductions = tmdb.useProductions,
                    useNetworks = tmdb.useNetworks,
                    useEpisodes = tmdb.useEpisodes,
                    useMoreLikeThis = tmdb.useMoreLikeThis,
                    useCollections = tmdb.useCollections
                ),
                mdblist = MDBListSyncSettings(
                    enabled = mdbList.enabled,
                    showTrakt = mdbList.showTrakt,
                    showImdb = mdbList.showImdb,
                    showTmdb = mdbList.showTmdb,
                    showLetterboxd = mdbList.showLetterboxd,
                    showTomatoes = mdbList.showTomatoes,
                    showAudience = mdbList.showAudience,
                    showMetacritic = mdbList.showMetacritic
                ),
                omdb = OmdbSyncSettings(
                    enabled = omdbSettingsDataStore.settings.first().enabled
                ),
                animeSkip = AnimeSkipSyncSettings(
                    enabled = animeSkipEnabled,
                ),
                subtitleTranslation = SubtitleTranslationSyncSettings(
                    enabled = subtitleTranslation.enabled,
                    provider = subtitleTranslation.provider.name,
                    model = subtitleTranslation.model,
                    baseUrl = subtitleTranslation.baseUrl
                ),
                gemini = GeminiSyncSettings(
                    enabled = subtitleTranslation.enabled
                ),
                posterRatings = PosterRatingsSyncSettings(
                    rpdbEnabled = posterRatings.selection.posterProvider ==
                        ArtworkProviderChoiceKey.RPDB,
                    topPostersEnabled = posterRatings.selection.posterProvider ==
                        ArtworkProviderChoiceKey.TOP_POSTERS
                ),
                kitsuAuth = KitsuAuthSyncSettings(
                    connected = kitsuAuth.isAuthenticated,
                    username = kitsuAuth.username.orEmpty(),
                    accessTokenSecretRef = KITSU_ACCESS_SECRET_TYPE,
                    refreshTokenSecretRef = KITSU_REFRESH_SECRET_TYPE,
                    expiresAtEpochSeconds = kitsuAuth.expiresAtEpochSeconds,
                    includeNsfw = kitsuAuth.includeNsfw
                ),
                traktAuth = TraktAuthSyncSettings(
                    connected = traktAuth.isAuthenticated,
                    username = traktAuth.username.orEmpty(),
                    userSlug = traktAuth.userSlug.orEmpty(),
                    connectedAt = null,
                    pending = traktAuth.deviceCode != null && !traktAuth.isAuthenticated
                ),
                simklAuth = SimklAuthSyncSettings(
                    connected = simklAuth.isAuthenticated,
                    username = simklAuth.username.orEmpty(),
                    accountId = simklAuth.accountId,
                    accountType = simklAuth.accountType.orEmpty(),
                    pending = simklAuth.userCode != null && !simklAuth.isAuthenticated
                )
            ),
            // Layout/catalog-order settings moved to v8 per-profile blob sync.
            heroCatalogKeys = heroCatalogKeys,
            homeCatalogOrderKeys = homeCatalogOrderKeys,
            disabledHomeCatalogKeys = disabledHomeCatalogKeys,
            traktCatalogEnabledSet = traktCatalogPrefs?.enabledCatalogs?.toList() ?: emptyList(),
            traktCatalogOrder = traktCatalogPrefs?.catalogOrder ?: emptyList(),
            traktSelectedPopularListKeys = traktCatalogPrefs?.selectedPopularListKeys?.toList() ?: emptyList(),
            traktPinnedListOptions = lastRemoteTraktPinnedListOptions,
            simklCatalogEnabledSet = simklCatalogPrefs?.enabledCatalogs?.toList() ?: emptyList(),
            simklCatalogOrder = simklCatalogPrefs?.catalogOrder ?: emptyList(),
            mdbListHiddenPersonalListKeys = mdbListPrefs.hiddenPersonalListKeys.toList(),
            mdbListSelectedTopListKeys = mdbListPrefs.selectedTopListKeys.toList(),
            mdbListPinnedTopListOptions = lastRemoteMDBListPinnedTopListOptions,
            mdbListCatalogOrder = mdbListPrefs.catalogOrder,
            trackingProvider = playerSettings?.trackingProvider ?: TrackingProvider.TRAKT,
            formatter = playerSettings?.syncedFormatterTemplate?.let { formatter ->
                FormatterSyncSettings(
                    enabled = formatter.enabled,
                    selectedTemplateId = formatter.selectedTemplateId,
                    customTemplate = if (
                        formatter.customTemplateLabel == null &&
                        formatter.customNameTemplate == null &&
                        formatter.customDescriptionTemplate == null &&
                        formatter.customBadgeRowTemplate == null
                    ) {
                        null
                    } else {
                        CustomFormatterSyncTemplate(
                            label = formatter.customTemplateLabel ?: "Custom",
                            nameTemplate = formatter.customNameTemplate ?: "",
                            descriptionTemplate = formatter.customDescriptionTemplate ?: "",
                            badgeRowTemplate = formatter.customBadgeRowTemplate ?: ""
                        )
                    }
                )
            } ?: FormatterSyncSettings()
        )
    }

    private suspend fun applySharedAccountConfigSyncSettings(
        settings: AccountConfigSyncPayload,
        resolveRemoteInlineSecrets: Boolean = true,
        sectionKeys: Set<AccountSettingsSectionKey>? = null
    ) {
        // Null catalog sections / null inner fields = absent in payload, leave target unchanged.
        // Empty list ([]) = present and intentionally empty, apply as cleared.
        // pinnedListOptions / pinnedTopListOptions remain non-null typed-object
        // lists; when their sub-section is null we fall back to the
        // last-known value.
        if (sectionKeys.includesSection(AccountSettingsSectionKey.CATALOGS_TRAKT)) settings.catalogs.trakt?.let {
            lastRemoteTraktPinnedListOptions = it.pinnedListOptions
        }
        if (sectionKeys.includesSection(AccountSettingsSectionKey.CATALOGS_MDBLIST)) settings.catalogs.mdblist?.let {
            lastRemoteMDBListPinnedTopListOptions = it.pinnedTopListOptions
        }

        if (isDefaultLegacyActive()) {
            if (sectionKeys.includesAnySection(CATALOG_SECTION_KEYS)) {
                applyCatalogsSection(
                    payload = settings,
                    layoutPreferenceDataStore = layoutPreferenceDataStore,
                    traktSettingsDataStore = traktSettingsDataStore,
                    simklSettingsDataStore = simklSettingsDataStore,
                    mdbListSettingsDataStore = mdbListSettingsDataStore,
                    tmdbCatalogSettingsDataStore = tmdbCatalogSettingsDataStore,
                    kitsuCatalogSettingsDataStore = kitsuCatalogSettingsDataStore,
                    homeRailOrderStore = homeRailOrderStore
                )
            }
            if (sectionKeys.includesSection(AccountSettingsSectionKey.PLAYBACK_STREAM_SELECTION)) {
                playerSettingsDataStore.setTrackingProvider(
                    runCatching { TrackingProvider.valueOf(settings.playback.streamSelection.trackingProvider) }
                        .getOrDefault(TrackingProvider.TRAKT)
                )
            }
            if (sectionKeys.includesSection(AccountSettingsSectionKey.FORMATTER)) {
                playerSettingsDataStore.setSyncedFormatterEnabled(settings.formatter.enabled)
                playerSettingsDataStore.setSyncedFormatterSelectedTemplateId(settings.formatter.selectedTemplateId)
                playerSettingsDataStore.setSyncedFormatterCustomTemplate(
                    label = settings.formatter.customTemplate?.label,
                    nameTemplate = settings.formatter.customTemplate?.nameTemplate,
                    descriptionTemplate = settings.formatter.customTemplate?.descriptionTemplate,
                    badgeRowTemplate = settings.formatter.customTemplate?.badgeRowTemplate
                )
            }
        }

        if (sectionKeys.includesSection(AccountSettingsSectionKey.INTEGRATIONS_TMDB)) {
            tmdbSettingsDataStore.setEnabled(true)
            tmdbSettingsDataStore.setUseArtwork(settings.integrations.tmdb.useArtwork)
            tmdbSettingsDataStore.setUseBasicInfo(settings.integrations.tmdb.useBasicInfo)
            tmdbSettingsDataStore.setUseDetails(settings.integrations.tmdb.useDetails)
            tmdbSettingsDataStore.setUseCredits(settings.integrations.tmdb.useCredits)
            tmdbSettingsDataStore.setUseProductions(settings.integrations.tmdb.useProductions)
            tmdbSettingsDataStore.setUseNetworks(settings.integrations.tmdb.useNetworks)
            tmdbSettingsDataStore.setUseEpisodes(settings.integrations.tmdb.useEpisodes)
            tmdbSettingsDataStore.setUseMoreLikeThis(settings.integrations.tmdb.useMoreLikeThis)
            tmdbSettingsDataStore.setUseCollections(settings.integrations.tmdb.useCollections)
        }

        if (sectionKeys.includesSection(AccountSettingsSectionKey.INTEGRATIONS_MDBLIST)) {
            mdbListSettingsDataStore.setEnabled(settings.integrations.mdblist.enabled)
            mdbListSettingsDataStore.setShowTrakt(settings.integrations.mdblist.showTrakt)
            mdbListSettingsDataStore.setShowImdb(settings.integrations.mdblist.showImdb)
            mdbListSettingsDataStore.setShowTmdb(settings.integrations.mdblist.showTmdb)
            mdbListSettingsDataStore.setShowLetterboxd(settings.integrations.mdblist.showLetterboxd)
            mdbListSettingsDataStore.setShowTomatoes(settings.integrations.mdblist.showTomatoes)
            mdbListSettingsDataStore.setShowAudience(settings.integrations.mdblist.showAudience)
            mdbListSettingsDataStore.setShowMetacritic(settings.integrations.mdblist.showMetacritic)
        }
        // Null catalogs.mdblist / null inner fields = absent in payload, leave target unchanged.
        // Empty list ([]) = present and intentionally empty, apply as cleared.
        if (sectionKeys.includesSection(AccountSettingsSectionKey.CATALOGS_MDBLIST)) settings.catalogs.mdblist?.let { mdblist ->
            val hidden = mdblist.hiddenPersonalListKeys
            val selected = mdblist.selectedTopListKeys
            val order = mdblist.catalogOrder
            if (hidden != null && selected != null && order != null) {
                mdbListSettingsDataStore.setCatalogPreferences(
                    hiddenPersonalListKeys = hidden.toSet(),
                    selectedTopListKeys = selected.toSet(),
                    catalogOrder = order
                )
            }
        }

        if (sectionKeys.includesSection(AccountSettingsSectionKey.INTEGRATIONS_OMDB)) {
            omdbSettingsDataStore.setEnabled(settings.integrations.omdb.enabled)
        }

        if (sectionKeys.includesSection(AccountSettingsSectionKey.INTEGRATIONS_ANIME_SKIP)) {
            animeSkipSettingsDataStore.setEnabled(settings.integrations.animeSkip.enabled)
            if (resolveRemoteInlineSecrets) resolveApiKeySecretOrNull(ANIMESKIP_SECRET_TYPE, ANIMESKIP_SECRET_REF)?.let {
                animeSkipSettingsDataStore.setClientId(it)
            }
        }

        if (sectionKeys.includesSection(AccountSettingsSectionKey.INTEGRATIONS_SUBTITLE_TRANSLATION)) {
            val remoteTranslation = settings.integrations.subtitleTranslation
            subtitleTranslationSettingsDataStore.saveSyncedPublicSettings(
                enabled = remoteTranslation.enabled,
                provider = remoteTranslation.toDomainSettings().provider,
                model = remoteTranslation.model,
                baseUrl = remoteTranslation.baseUrl
            )
        }
        if (sectionKeys.includesSection(AccountSettingsSectionKey.INTEGRATIONS_POSTER_RATINGS)) {
            applyPosterRatingsProviderSelection(
                settings = settings.integrations.posterRatings,
                posterRatingsSettingsDataStore = posterRatingsSettingsDataStore
            )
        }

        if (sectionKeys.includesSection(AccountSettingsSectionKey.INTEGRATIONS_KITSU_AUTH)) {
            val remoteKitsu = settings.integrations.kitsuAuth
            val defaultProfileId = profileModeRouter.defaultLegacyProfileId()
            val currentKitsu = kitsuAuthDataStore.stateForProfile(defaultProfileId).first()
            kitsuAuthDataStore.saveForProfile(
                defaultProfileId,
                currentKitsu.copy(
                    enabled = true,
                    username = remoteKitsu.username,
                    expiresAtEpochSeconds = remoteKitsu.expiresAtEpochSeconds ?: currentKitsu.expiresAtEpochSeconds,
                    includeNsfw = remoteKitsu.includeNsfw
                )
            )
        }

        // Moved to v8 per-profile blob sync: Trakt catalog preferences
        // Moved to v8 per-profile blob sync: Simkl catalog preferences
        // Moved to v8 per-profile blob sync: player tracking provider and formatter settings
    }

    suspend fun runWithLocalResetPushSuppressed(block: suspend () -> Unit) = withContext(Dispatchers.IO) {
        applyingRemoteMutex.withLock {
            isApplyingRemote = true
            pushJob?.cancel()
            pushJob = null
            try {
                block()
                synchronized(pendingChangedPaths) {
                    pendingChangedPaths.clear()
                    pendingChangedPathsGeneration += 1L
                }
            } finally {
                isApplyingRemote = false
            }
        }
    }

    suspend fun resetLocalAccountConfigToDefaults() = runWithLocalResetPushSuppressed {
        applySharedAccountConfigSyncSettings(
            settings = stockAccountConfigSyncPayload(),
            resolveRemoteInlineSecrets = false
        )
        clearLocalAccountSecrets()
    }

    private suspend fun clearLocalAccountSecrets() {
        tmdbSettingsDataStore.setApiKey("")
        tvdbSettingsDataStore.clearCredentials()
        mdbListSettingsDataStore.setApiKey("")
        omdbSettingsDataStore.setApiKey("")
        subtitleTranslationSettingsDataStore.setApiKey("")
        animeSkipSettingsDataStore.setClientId("")
        posterRatingsSettingsDataStore.setRpdbApiKey("")
        posterRatingsSettingsDataStore.setTopPostersApiKey("")
        premiumizeSettingsDataStore.setApiKey("")
        premiumizeService.clearLocalAccountState()
        torBoxSettingsDataStore.setApiKey("")
        torBoxService.clearLocalAccountState()
        easyDebridSettingsDataStore.setApiKey("")
        easyDebridService.clearLocalAccountState()
        realDebridAuthDataStore.clearAuth()
        traktAuthDataStore.clearAuth(profileModeRouter.defaultLegacyProfileId())
        simklAuthDataStore.clearAuth(profileModeRouter.defaultLegacyProfileId())
        kitsuAuthDataStore.clearAuth(profileModeRouter.defaultLegacyProfileId())
    }

    private suspend fun applyRemoteSettings(settings: AccountSettingsPayload) {
        Log.d(
            TAG,
            "Applying remote layout order keys count=${settings.layout.homeCatalogOrderKeys.size} disabled count=${settings.layout.disabledHomeCatalogKeys.size}"
        )
        themeDataStore.setTheme(enumValueOrDefault(settings.appearance.theme, AppTheme.CRIMSON))
        themeDataStore.setFont(enumValueOrDefault(settings.appearance.font, AppFont.INTER))
        // Locale: apply remote only when it is explicitly set to a language.
        // Never allow remote "system" to clear/override a local preference.
        val remoteLocaleTag = settings.appearance.localeTag
            .takeUnless { it.equals("system", ignoreCase = true) }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (remoteLocaleTag != null) {
            AppLocaleResolver.setStoredLocaleTag(context, remoteLocaleTag)
        }

        layoutPreferenceDataStore.setLayout(enumValueOrDefault(settings.layout.selectedLayout, HomeLayout.MODERN))
        layoutPreferenceDataStore.setModernLandscapePostersEnabled(settings.layout.modernLandscapePostersEnabled)
        layoutPreferenceDataStore.setHeroCatalogKeys(settings.layout.heroCatalogKeys)
        layoutPreferenceDataStore.setHomeCatalogOrderKeys(settings.layout.homeCatalogOrderKeys)
        layoutPreferenceDataStore.setDisabledHomeCatalogKeys(settings.layout.disabledHomeCatalogKeys)
        layoutPreferenceDataStore.setHeroSectionEnabled(settings.layout.heroSectionEnabled)
        layoutPreferenceDataStore.setSearchDiscoverEnabled(settings.layout.searchDiscoverEnabled)
        layoutPreferenceDataStore.setPosterLabelsEnabled(settings.layout.posterLabelsEnabled)
        layoutPreferenceDataStore.setCatalogAddonNameEnabled(settings.layout.catalogAddonNameEnabled)
        layoutPreferenceDataStore.setCatalogTypeSuffixEnabled(settings.layout.catalogTypeSuffixEnabled)
        layoutPreferenceDataStore.setBlurUnwatchedEpisodes(settings.layout.blurUnwatchedEpisodes)
        layoutPreferenceDataStore.setPreferExternalMetaAddonDetail(settings.layout.preferExternalMetaAddonDetail)
        layoutPreferenceDataStore.setFocusedPosterBackdropExpandEnabled(settings.layout.focusedPosterBackdropExpandEnabled)
        layoutPreferenceDataStore.setFocusedPosterBackdropExpandDelaySeconds(settings.layout.focusedPosterBackdropExpandDelaySeconds)
        layoutPreferenceDataStore.setPosterCardWidthDp(settings.layout.posterCardWidthDp)
        layoutPreferenceDataStore.setPosterCardCornerRadiusDp(settings.layout.posterCardCornerRadiusDp)

        tmdbSettingsDataStore.setEnabled(settings.integrations.tmdb.enabled)
        tmdbSettingsDataStore.setUseArtwork(settings.integrations.tmdb.useArtwork)
        tmdbSettingsDataStore.setUseBasicInfo(settings.integrations.tmdb.useBasicInfo)
        tmdbSettingsDataStore.setUseDetails(settings.integrations.tmdb.useDetails)
        tmdbSettingsDataStore.setUseCredits(settings.integrations.tmdb.useCredits)
        tmdbSettingsDataStore.setUseProductions(settings.integrations.tmdb.useProductions)
        tmdbSettingsDataStore.setUseNetworks(settings.integrations.tmdb.useNetworks)
        tmdbSettingsDataStore.setUseEpisodes(settings.integrations.tmdb.useEpisodes)
        tmdbSettingsDataStore.setUseMoreLikeThis(settings.integrations.tmdb.useMoreLikeThis)
        tmdbSettingsDataStore.setUseCollections(settings.integrations.tmdb.useCollections)

        mdbListSettingsDataStore.setEnabled(settings.integrations.mdblist.enabled)
        mdbListSettingsDataStore.setShowTrakt(settings.integrations.mdblist.showTrakt)
        mdbListSettingsDataStore.setShowImdb(settings.integrations.mdblist.showImdb)
        mdbListSettingsDataStore.setShowTmdb(settings.integrations.mdblist.showTmdb)
        mdbListSettingsDataStore.setShowLetterboxd(settings.integrations.mdblist.showLetterboxd)
        mdbListSettingsDataStore.setShowTomatoes(settings.integrations.mdblist.showTomatoes)
        mdbListSettingsDataStore.setShowAudience(settings.integrations.mdblist.showAudience)
        mdbListSettingsDataStore.setShowMetacritic(settings.integrations.mdblist.showMetacritic)
        mdbListSettingsDataStore.setCatalogPreferences(
            hiddenPersonalListKeys = settings.integrations.mdblist.hiddenPersonalListKeys.toSet(),
            selectedTopListKeys = settings.integrations.mdblist.selectedTopListKeys.toSet(),
            catalogOrder = settings.integrations.mdblist.catalogOrder
        )

        animeSkipSettingsDataStore.setEnabled(settings.integrations.animeSkip.enabled)
        resolveApiKeySecretOrNull(ANIMESKIP_SECRET_TYPE, ANIMESKIP_SECRET_REF)?.let {
            animeSkipSettingsDataStore.setClientId(it)
        }

        val remoteTranslation = settings.integrations.subtitleTranslation
        subtitleTranslationSettingsDataStore.saveSyncedPublicSettings(
            enabled = remoteTranslation.enabled,
            provider = remoteTranslation.toDomainSettings().provider,
            model = remoteTranslation.model,
            baseUrl = remoteTranslation.baseUrl
        )
        applyPosterRatingsProviderSelection(
            settings = settings.integrations.posterRatings,
            posterRatingsSettingsDataStore = posterRatingsSettingsDataStore
        )

        val remoteKitsu = settings.integrations.kitsuAuth
        val defaultProfileId = profileModeRouter.defaultLegacyProfileId()
        val currentKitsu = kitsuAuthDataStore.stateForProfile(defaultProfileId).first()
        kitsuAuthDataStore.saveForProfile(
            defaultProfileId,
            currentKitsu.copy(
                enabled = true,
                username = remoteKitsu.username,
                expiresAtEpochSeconds = remoteKitsu.expiresAtEpochSeconds ?: currentKitsu.expiresAtEpochSeconds,
                includeNsfw = remoteKitsu.includeNsfw
            )
        )

        playerSettingsDataStore.setLoadingOverlayEnabled(settings.playback.general.loadingOverlayEnabled)
        playerSettingsDataStore.setPauseOverlayEnabled(settings.playback.general.pauseOverlayEnabled)
        playerSettingsDataStore.setOsdClockEnabled(settings.playback.general.osdClockEnabled)
        playerSettingsDataStore.setSkipIntroEnabled(settings.playback.general.skipIntroEnabled)
        playerSettingsDataStore.setFrameRateMatchingMode(enumValueOrDefault(settings.playback.general.frameRateMatchingMode, FrameRateMatchingMode.OFF))
        playerSettingsDataStore.setResolutionMatchingEnabled(settings.playback.general.resolutionMatchingEnabled)
        playerSettingsDataStore.setStreamReuseLastLinkEnabled(settings.playback.streamSelection.streamReuseLastLinkEnabled)
        playerSettingsDataStore.setStreamReuseLastLinkCacheHours(settings.playback.streamSelection.streamReuseLastLinkCacheHours)
        playerSettingsDataStore.setUniformStreamFormattingEnabled(settings.playback.streamSelection.uniformStreamFormattingEnabled)
        playerSettingsDataStore.setGroupStreamsAcrossAddonsEnabled(true)
        playerSettingsDataStore.setDeduplicateGroupedStreamsEnabled(settings.playback.streamSelection.deduplicateGroupedStreamsEnabled)
        playerSettingsDataStore.setFilterEpisodeMismatchStreamsEnabled(settings.playback.streamSelection.filterEpisodeMismatchStreamsEnabled)
        playerSettingsDataStore.setFilterMovieYearMismatchStreamsEnabled(settings.playback.streamSelection.filterMovieYearMismatchStreamsEnabled)
        // Legacy stream auto-selection is retired from UX in favor of deterministic autoplay.
        playerSettingsDataStore.setStreamAutoPlayMode(StreamAutoPlayMode.MANUAL)
        playerSettingsDataStore.setStreamAutoPlaySource(StreamAutoPlaySource.ALL_SOURCES)
        playerSettingsDataStore.setTrackingProvider(enumValueOrDefault(settings.playback.streamSelection.trackingProvider, TrackingProvider.TRAKT))
        playerSettingsDataStore.setStreamAutoPlaySelectedAddons(emptySet())
        playerSettingsDataStore.setStreamAutoPlayRegex("")
        playerSettingsDataStore.setStreamAutoPlayNextEpisodeEnabled(settings.playback.streamSelection.streamAutoPlayNextEpisodeEnabled)
        playerSettingsDataStore.setStreamAutoPlayPreferBingeGroupForNextEpisode(settings.playback.streamSelection.streamAutoPlayPreferBingeGroupForNextEpisode)
        playerSettingsDataStore.setNextEpisodeThresholdMode(enumValueOrDefault(settings.playback.streamSelection.nextEpisodeThresholdMode, NextEpisodeThresholdMode.PERCENTAGE))
        playerSettingsDataStore.setNextEpisodeThresholdPercent(settings.playback.streamSelection.nextEpisodeThresholdPercent)
        playerSettingsDataStore.setNextEpisodeThresholdMinutesBeforeEnd(settings.playback.streamSelection.nextEpisodeThresholdMinutesBeforeEnd)
        playerSettingsDataStore.setPreferredAudioLanguage(settings.playback.audio.preferredAudioLanguage)
        playerSettingsDataStore.setSecondaryPreferredAudioLanguage(settings.playback.audio.secondaryPreferredAudioLanguage)
        playerSettingsDataStore.setSkipSilence(settings.playback.audio.skipSilence)
        playerSettingsDataStore.setDecoderPriority(settings.playback.audio.decoderPriority)
        playerSettingsDataStore.setTunnelingEnabled(settings.playback.audio.tunnelingEnabled)
        playerSettingsDataStore.setSubtitlePreferredLanguage(settings.playback.subtitles.preferredLanguage)
        playerSettingsDataStore.setSubtitleSecondaryLanguage(settings.playback.subtitles.secondaryPreferredLanguage)
        playerSettingsDataStore.setSubtitleOrganizationMode(SubtitleOrganizationMode.BY_LANGUAGE)
        playerSettingsDataStore.setAddonSubtitleStartupMode(enumValueOrDefault(settings.playback.subtitles.addonSubtitleStartupMode, AddonSubtitleStartupMode.ALL_SUBTITLES))
        playerSettingsDataStore.setSubtitleSize(settings.playback.subtitles.size)
        playerSettingsDataStore.setSubtitleVerticalOffset(settings.playback.subtitles.verticalOffset)
        playerSettingsDataStore.setSubtitleBold(settings.playback.subtitles.bold)
        playerSettingsDataStore.setSubtitleBackgroundColor(settings.playback.subtitles.backgroundColor)
        playerSettingsDataStore.setSubtitleOutlineEnabled(settings.playback.subtitles.outlineEnabled)
        playerSettingsDataStore.setSubtitleOutlineColor(settings.playback.subtitles.outlineColor)
        playerSettingsDataStore.setBufferMinBufferMs(settings.playback.bufferNetwork.minBufferMs)
        playerSettingsDataStore.setBufferMaxBufferMs(settings.playback.bufferNetwork.maxBufferMs)
        playerSettingsDataStore.setBufferForPlaybackMs(settings.playback.bufferNetwork.bufferForPlaybackMs)
        playerSettingsDataStore.setBufferForPlaybackAfterRebufferMs(settings.playback.bufferNetwork.bufferForPlaybackAfterRebufferMs)
        playerSettingsDataStore.setBufferTargetSizeMb(settings.playback.bufferNetwork.targetBufferSizeMb)
        playerSettingsDataStore.setBufferBackBufferDurationMs(settings.playback.bufferNetwork.backBufferDurationMs)
        playerSettingsDataStore.setEnableBufferLogs(settings.playback.bufferNetwork.enableBufferLogs)

        traktSettingsDataStore.setCatalogPreferences(
            enabledCatalogs = settings.trakt.catalogEnabledSet.toSet(),
            catalogOrder = settings.trakt.catalogOrder,
            selectedPopularListKeys = settings.trakt.selectedPopularListKeys.toSet()
        )

        debugSettingsDataStore.setAccountTabEnabled(settings.debug.accountTabEnabled)
        debugSettingsDataStore.setSyncCodeFeaturesEnabled(settings.debug.syncCodeFeaturesEnabled)
    }

    private suspend fun buildAccountSecretPushSnapshot(): AccountSecretPushSnapshot {
        val subtitleTranslationSettings = subtitleTranslationSettingsDataStore.settings.first()
        val realDebrid = realDebridAuthDataStore.state.first()
        val trakt = traktAuthDataStore.stateForProfile(profileModeRouter.defaultLegacyProfileId()).first()
        val simkl = simklAuthDataStore.stateForProfile(profileModeRouter.defaultLegacyProfileId()).first()
        val kitsu = kitsuAuthDataStore.stateForProfile(profileModeRouter.defaultLegacyProfileId()).first()
        return AccountSecretPushSnapshot(
            mdbListApiKey = mdbListSettingsDataStore.settings.first().apiKey,
            omdbApiKey = omdbSettingsDataStore.settings.first().apiKey,
            subtitleTranslationApiKey = subtitleTranslationSettings.apiKey,
            legacyGeminiApiKey = legacyGeminiApiKeySecretForPush(
                providerName = subtitleTranslationSettings.provider.name,
                translationApiKey = subtitleTranslationSettings.apiKey
            ),
            animeSkipClientId = animeSkipSettingsDataStore.clientId.first(),
            rpdbApiKey = posterRatingsSettingsDataStore.settings.first().rpdbApiKey,
            topPostersApiKey = posterRatingsSettingsDataStore.settings.first().topPostersApiKey,
            premiumizeApiKey = premiumizeSettingsDataStore.settings.first().apiKey,
            torBoxApiKey = torBoxSettingsDataStore.settings.first().apiKey,
            easyDebridApiKey = easyDebridSettingsDataStore.settings.first().apiKey,
            realDebrid = RealDebridSecretPushSnapshot(
                accessToken = realDebrid.accessToken?.trim().orEmpty(),
                refreshToken = realDebrid.refreshToken?.trim().orEmpty(),
                tokenType = realDebrid.tokenType ?: "Bearer",
                expiresIn = realDebrid.expiresIn ?: 0,
                userClientId = realDebrid.userClientId?.trim().orEmpty(),
                userClientSecret = realDebrid.userClientSecret?.trim().orEmpty()
            ),
            trakt = TraktSecretPushSnapshot(
                accessToken = trakt.accessToken?.trim().orEmpty(),
                refreshToken = trakt.refreshToken?.trim().orEmpty(),
                tokenType = trakt.tokenType ?: "bearer",
                createdAt = trakt.createdAt ?: 0L,
                expiresIn = trakt.expiresIn ?: 0
            ),
            simkl = SimklSecretPushSnapshot(
                accessToken = simkl.accessToken?.trim().orEmpty()
            ),
            kitsu = KitsuSecretPushSnapshot(
                accessToken = kitsu.accessToken?.trim().orEmpty(),
                refreshToken = kitsu.refreshToken?.trim().orEmpty(),
                expiresAtEpochSeconds = kitsu.expiresAtEpochSeconds
            )
        )
    }

    private suspend fun syncAccountSecretPushSnapshotToRemote(
        snapshot: AccountSecretPushSnapshot,
        dirtySectionKeys: Set<AccountSettingsSectionKey>
    ): Boolean {
        if (dirtySectionKeys.isEmpty()) return true

        var allApplied = true
        if (AccountSettingsSectionKey.INTEGRATIONS_MDBLIST in dirtySectionKeys) {
            allApplied = syncApiKeySecretToRemote(MDBLIST_SECRET_TYPE, MDBLIST_SECRET_REF, snapshot.mdbListApiKey) && allApplied
        }
        if (AccountSettingsSectionKey.INTEGRATIONS_OMDB in dirtySectionKeys) {
            allApplied = syncApiKeySecretToRemote(OMDB_SECRET_TYPE, OMDB_SECRET_REF, snapshot.omdbApiKey) && allApplied
        }
        if (AccountSettingsSectionKey.INTEGRATIONS_SUBTITLE_TRANSLATION in dirtySectionKeys) {
            allApplied = syncApiKeySecretToRemote(
                TRANSLATION_SECRET_TYPE,
                TRANSLATION_SECRET_REF,
                snapshot.subtitleTranslationApiKey
            ) && allApplied
            snapshot.legacyGeminiApiKey?.let { legacyGeminiKey ->
                allApplied = syncApiKeySecretToRemote(GEMINI_SECRET_TYPE, GEMINI_SECRET_REF, legacyGeminiKey) && allApplied
            }
        }
        if (AccountSettingsSectionKey.INTEGRATIONS_ANIME_SKIP in dirtySectionKeys) {
            allApplied = syncApiKeySecretToRemote(ANIMESKIP_SECRET_TYPE, ANIMESKIP_SECRET_REF, snapshot.animeSkipClientId) && allApplied
        }
        if (AccountSettingsSectionKey.INTEGRATIONS_POSTER_RATINGS in dirtySectionKeys) {
            allApplied = syncApiKeySecretToRemote(RPDB_SECRET_TYPE, RPDB_SECRET_REF, snapshot.rpdbApiKey) && allApplied
            allApplied = syncApiKeySecretToRemote(TOP_POSTERS_SECRET_TYPE, TOP_POSTERS_SECRET_REF, snapshot.topPostersApiKey) && allApplied
        }
        if (AccountSettingsSectionKey.INTEGRATIONS_DEBRID_PREMIUMIZE in dirtySectionKeys) {
            allApplied = syncApiKeySecretToRemote(PREMIUMIZE_SECRET_TYPE, PREMIUMIZE_SECRET_REF, snapshot.premiumizeApiKey) && allApplied
        }
        if (AccountSettingsSectionKey.INTEGRATIONS_DEBRID_TOR_BOX in dirtySectionKeys) {
            allApplied = syncApiKeySecretToRemote(TORBOX_SECRET_TYPE, TORBOX_SECRET_REF, snapshot.torBoxApiKey) && allApplied
        }
        if (AccountSettingsSectionKey.INTEGRATIONS_DEBRID_EASY_DEBRID in dirtySectionKeys) {
            allApplied = syncApiKeySecretToRemote(EASY_DEBRID_SECRET_TYPE, EASY_DEBRID_SECRET_REF, snapshot.easyDebridApiKey) && allApplied
        }
        if (AccountSettingsSectionKey.INTEGRATIONS_DEBRID_REAL_DEBRID in dirtySectionKeys) {
            allApplied = syncRealDebridSecretsToRemote(snapshot.realDebrid) && allApplied
        }
        if (AccountSettingsSectionKey.INTEGRATIONS_TRAKT_AUTH in dirtySectionKeys) {
            allApplied = syncTraktSecretsToRemote(snapshot.trakt) && allApplied
        }
        if (AccountSettingsSectionKey.INTEGRATIONS_SIMKL_AUTH in dirtySectionKeys) {
            allApplied = syncSimklSecretsToRemote(snapshot.simkl) && allApplied
        }
        if (AccountSettingsSectionKey.INTEGRATIONS_KITSU_AUTH in dirtySectionKeys) {
            allApplied = syncKitsuSecretsToRemote(snapshot.kitsu) && allApplied
        }
        return allApplied
    }

    private suspend fun syncApiKeySecretToRemote(secretType: String, secretRef: String, rawApiKey: String): Boolean {
        val apiKey = rawApiKey.trim()

        if (apiKey.isBlank()) {
            return deleteAccountSecretV10(buildJsonObject {
                        put("p_secret_type", secretType)
                        put("p_secret_ref", secretRef)
                        put("p_source", "app")
                    })
        }

        return setAccountSecretV10(buildJsonObject {
                    put("p_secret_type", secretType)
                    put("p_secret_ref", secretRef)
                    put("p_secret_payload", Json.encodeToJsonElement(AccountSecretApiKeyPayload.serializer(), AccountSecretApiKeyPayload(apiKey)))
                    put("p_masked_preview", "Stored ••••${apiKey.takeLast(4)}")
                    put("p_status", "configured")
                    put("p_source", "app")
                })
    }

    private suspend fun syncRealDebridSecretsToRemote(state: RealDebridSecretPushSnapshot): Boolean {
        val accessToken = state.accessToken
        val refreshToken = state.refreshToken
        val userClientId = state.userClientId
        val userClientSecret = state.userClientSecret

        if (
            accessToken.isBlank() ||
            refreshToken.isBlank() ||
            userClientId.isBlank() ||
            userClientSecret.isBlank()
        ) {
            val accessApplied = deleteAccountSecretV10(buildJsonObject {
                put("p_secret_type", REAL_DEBRID_ACCESS_SECRET_TYPE)
                put("p_secret_ref", REAL_DEBRID_SECRET_REF)
                put("p_source", "app")
            })
            val refreshApplied = deleteAccountSecretV10(buildJsonObject {
                put("p_secret_type", REAL_DEBRID_REFRESH_SECRET_TYPE)
                put("p_secret_ref", REAL_DEBRID_SECRET_REF)
                put("p_source", "app")
            })
            return accessApplied && refreshApplied
        }

        val accessApplied = setAccountSecretV10(buildJsonObject {
            put("p_secret_type", REAL_DEBRID_ACCESS_SECRET_TYPE)
            put("p_secret_ref", REAL_DEBRID_SECRET_REF)
            put(
                "p_secret_payload",
                Json.encodeToJsonElement(
                    AccountRealDebridAccessSecretPayload.serializer(),
                    AccountRealDebridAccessSecretPayload(
                        accessToken = accessToken,
                        tokenType = state.tokenType,
                        expiresIn = state.expiresIn,
                        userClientId = userClientId,
                        userClientSecret = userClientSecret
                    )
                )
            )
            put("p_masked_preview", "Connected ••••${accessToken.takeLast(4)}")
            put("p_status", "configured")
            put("p_source", "app")
        })
        val refreshApplied = setAccountSecretV10(buildJsonObject {
            put("p_secret_type", REAL_DEBRID_REFRESH_SECRET_TYPE)
            put("p_secret_ref", REAL_DEBRID_SECRET_REF)
            put(
                "p_secret_payload",
                Json.encodeToJsonElement(
                    AccountRealDebridRefreshSecretPayload.serializer(),
                    AccountRealDebridRefreshSecretPayload(refreshToken = refreshToken)
                )
            )
            put("p_masked_preview", "Connected ••••${refreshToken.takeLast(4)}")
            put("p_status", "configured")
            put("p_source", "app")
        })
        return accessApplied && refreshApplied
    }

    private suspend fun syncTraktSecretsToRemote(traktState: TraktSecretPushSnapshot): Boolean {
        val accessToken = traktState.accessToken
        val refreshToken = traktState.refreshToken

        if (accessToken.isBlank() || refreshToken.isBlank()) {
            val accessApplied = deleteAccountSecretV10(buildJsonObject {
                put("p_secret_type", TRAKT_ACCESS_SECRET_TYPE)
                put("p_secret_ref", TRAKT_SECRET_REF)
                put("p_source", "app")
            })
            val refreshApplied = deleteAccountSecretV10(buildJsonObject {
                put("p_secret_type", TRAKT_REFRESH_SECRET_TYPE)
                put("p_secret_ref", TRAKT_SECRET_REF)
                put("p_source", "app")
            })
            return accessApplied && refreshApplied
        }

        val accessApplied = setAccountSecretV10(buildJsonObject {
            put("p_secret_type", TRAKT_ACCESS_SECRET_TYPE)
            put("p_secret_ref", TRAKT_SECRET_REF)
            put(
                "p_secret_payload",
                Json.encodeToJsonElement(
                    AccountTraktAccessSecretPayload.serializer(),
                    AccountTraktAccessSecretPayload(
                        accessToken = accessToken,
                        tokenType = traktState.tokenType,
                        createdAt = traktState.createdAt,
                        expiresIn = traktState.expiresIn
                    )
                )
            )
            put("p_masked_preview", "Connected ••••${accessToken.takeLast(4)}")
            put("p_status", "configured")
            put("p_source", "app")
        })
        val refreshApplied = setAccountSecretV10(buildJsonObject {
            put("p_secret_type", TRAKT_REFRESH_SECRET_TYPE)
            put("p_secret_ref", TRAKT_SECRET_REF)
            put(
                "p_secret_payload",
                Json.encodeToJsonElement(
                    AccountTraktRefreshSecretPayload.serializer(),
                    AccountTraktRefreshSecretPayload(refreshToken = refreshToken)
                )
            )
            put("p_masked_preview", "Stored ••••${refreshToken.takeLast(4)}")
            put("p_status", "configured")
            put("p_source", "app")
        })
        return accessApplied && refreshApplied
    }

    private suspend fun syncSimklSecretsToRemote(simklState: SimklSecretPushSnapshot): Boolean {
        val accessToken = simklState.accessToken

        if (accessToken.isBlank()) {
            return deleteAccountSecretV10(buildJsonObject {
                        put("p_secret_type", SIMKL_ACCESS_SECRET_TYPE)
                        put("p_secret_ref", SIMKL_SECRET_REF)
                        put("p_source", "app")
                    })
        }

        return setAccountSecretV10(buildJsonObject {
                    put("p_secret_type", SIMKL_ACCESS_SECRET_TYPE)
                    put("p_secret_ref", SIMKL_SECRET_REF)
                    put(
                        "p_secret_payload",
                        Json.encodeToJsonElement(
                            AccountSimklAccessSecretPayload.serializer(),
                            AccountSimklAccessSecretPayload(accessToken = accessToken)
                        )
                    )
                    put("p_masked_preview", "Connected ••••${accessToken.takeLast(4)}")
                    put("p_status", "configured")
                    put("p_source", "app")
                })
    }

    private suspend fun syncKitsuSecretsToRemote(kitsuState: KitsuSecretPushSnapshot): Boolean {
        val accessToken = kitsuState.accessToken
        val refreshToken = kitsuState.refreshToken

        if (accessToken.isBlank() || refreshToken.isBlank()) {
            val accessApplied = deleteAccountSecretV10(buildJsonObject {
                put("p_secret_type", KITSU_ACCESS_SECRET_TYPE)
                put("p_secret_ref", KITSU_SECRET_REF)
                put("p_source", "app")
            })
            val refreshApplied = deleteAccountSecretV10(buildJsonObject {
                put("p_secret_type", KITSU_REFRESH_SECRET_TYPE)
                put("p_secret_ref", KITSU_SECRET_REF)
                put("p_source", "app")
            })
            return accessApplied && refreshApplied
        }

        val accessApplied = setAccountSecretV10(buildJsonObject {
            put("p_secret_type", KITSU_ACCESS_SECRET_TYPE)
            put("p_secret_ref", KITSU_SECRET_REF)
            put(
                "p_secret_payload",
                Json.encodeToJsonElement(
                    AccountKitsuAccessSecretPayload.serializer(),
                    AccountKitsuAccessSecretPayload(
                        accessToken = accessToken,
                        expiresAtEpochSeconds = kitsuState.expiresAtEpochSeconds
                    )
                )
            )
            put("p_masked_preview", "Connected ••••${accessToken.takeLast(4)}")
            put("p_status", "configured")
            put("p_source", "app")
        })
        val refreshApplied = setAccountSecretV10(buildJsonObject {
            put("p_secret_type", KITSU_REFRESH_SECRET_TYPE)
            put("p_secret_ref", KITSU_SECRET_REF)
            put(
                "p_secret_payload",
                Json.encodeToJsonElement(
                    AccountKitsuRefreshSecretPayload.serializer(),
                    AccountKitsuRefreshSecretPayload(refreshToken = refreshToken)
                )
            )
            put("p_masked_preview", "Stored ••••${refreshToken.takeLast(4)}")
            put("p_status", "configured")
            put("p_source", "app")
        })
        return accessApplied && refreshApplied
    }

    private suspend fun resolveRemoteSecretsForApply(
        settings: AccountConfigSyncPayload,
        sectionKeys: Set<AccountSettingsSectionKey>? = null
    ): ResolvedRemoteSecretsForApply {
        // Each helper returns null when the resolve RPC fails transiently (network,
        // JWT, decode). Only overwrite the local API key when we have an authoritative
        // response from the server — otherwise we'd wipe valid local credentials on
        // every flaky upgrade-time sync.
        val preservedLocalSecretSections = linkedSetOf<AccountSettingsSectionKey>()
        val unresolvedRemoteSecretSections = linkedSetOf<AccountSettingsSectionKey>()
        val followUpLocalSecretSections = linkedSetOf<AccountSettingsSectionKey>()
        fun preserveUnresolvedRemoteSecretSection(sectionKey: AccountSettingsSectionKey) {
            preservedLocalSecretSections += sectionKey
            unresolvedRemoteSecretSections += sectionKey
        }

        val mdbListApiKey = if (sectionKeys.includesSection(AccountSettingsSectionKey.INTEGRATIONS_MDBLIST)) {
            resolveApiKeySecretOrNull(MDBLIST_SECRET_TYPE, MDBLIST_SECRET_REF)
        } else {
            null
        }
        if (sectionKeys.includesSection(AccountSettingsSectionKey.INTEGRATIONS_MDBLIST) && mdbListApiKey == null) {
            preserveUnresolvedRemoteSecretSection(AccountSettingsSectionKey.INTEGRATIONS_MDBLIST)
        }
        val omdbApiKey = if (sectionKeys.includesSection(AccountSettingsSectionKey.INTEGRATIONS_OMDB)) {
            resolveApiKeySecretOrNull(OMDB_SECRET_TYPE, OMDB_SECRET_REF)
        } else {
            null
        }
        if (sectionKeys.includesSection(AccountSettingsSectionKey.INTEGRATIONS_OMDB) && omdbApiKey == null) {
            preserveUnresolvedRemoteSecretSection(AccountSettingsSectionKey.INTEGRATIONS_OMDB)
        }
        val resolveSubtitleTranslation = sectionKeys.includesAnySection(SUBTITLE_TRANSLATION_SECRET_SECTION_KEYS)
        val genericTranslationKey = if (resolveSubtitleTranslation) {
            resolveApiKeySecretOrNull(TRANSLATION_SECRET_TYPE, TRANSLATION_SECRET_REF)
        } else {
            null
        }
        val allowLegacyFallback = resolveSubtitleTranslation &&
            settings.integrations.subtitleTranslation.provider.equals("GEMINI", ignoreCase = true)
        val legacyGeminiKey = if (genericTranslationKey != null && genericTranslationKey.isBlank() && allowLegacyFallback) {
            resolveApiKeySecretOrNull(GEMINI_SECRET_TYPE, GEMINI_SECRET_REF)
        } else {
            null
        }
        val subtitleTranslationSecretUnresolved =
            resolveSubtitleTranslation &&
            (
                genericTranslationKey == null ||
                    (allowLegacyFallback && genericTranslationKey.isBlank() && legacyGeminiKey == null)
                )
        if (subtitleTranslationSecretUnresolved) {
            preserveUnresolvedRemoteSecretSection(AccountSettingsSectionKey.INTEGRATIONS_SUBTITLE_TRANSLATION)
        }
        val animeSkipClientId = if (sectionKeys.includesSection(AccountSettingsSectionKey.INTEGRATIONS_ANIME_SKIP)) {
            resolveApiKeySecretOrNull(ANIMESKIP_SECRET_TYPE, ANIMESKIP_SECRET_REF)
        } else {
            null
        }
        if (sectionKeys.includesSection(AccountSettingsSectionKey.INTEGRATIONS_ANIME_SKIP) && animeSkipClientId == null) {
            preserveUnresolvedRemoteSecretSection(AccountSettingsSectionKey.INTEGRATIONS_ANIME_SKIP)
        }
        val rpdbApiKey = if (sectionKeys.includesSection(AccountSettingsSectionKey.INTEGRATIONS_POSTER_RATINGS)) {
            resolveApiKeySecretOrNull(RPDB_SECRET_TYPE, RPDB_SECRET_REF)
        } else {
            null
        }
        val topPostersApiKey = if (sectionKeys.includesSection(AccountSettingsSectionKey.INTEGRATIONS_POSTER_RATINGS)) {
            resolveApiKeySecretOrNull(TOP_POSTERS_SECRET_TYPE, TOP_POSTERS_SECRET_REF)
        } else {
            null
        }
        if (
            sectionKeys.includesSection(AccountSettingsSectionKey.INTEGRATIONS_POSTER_RATINGS) &&
            (rpdbApiKey == null || topPostersApiKey == null)
        ) {
            preserveUnresolvedRemoteSecretSection(AccountSettingsSectionKey.INTEGRATIONS_POSTER_RATINGS)
        }
        val premiumizeApiKey = if (sectionKeys.includesSection(AccountSettingsSectionKey.INTEGRATIONS_DEBRID_PREMIUMIZE)) {
            resolveApiKeySecretOrNull(PREMIUMIZE_SECRET_TYPE, PREMIUMIZE_SECRET_REF)
        } else {
            null
        }
        if (
            sectionKeys.includesSection(AccountSettingsSectionKey.INTEGRATIONS_DEBRID_PREMIUMIZE) &&
            premiumizeApiKey == null
        ) {
            preserveUnresolvedRemoteSecretSection(AccountSettingsSectionKey.INTEGRATIONS_DEBRID_PREMIUMIZE)
        }
        val torBoxApiKey = if (sectionKeys.includesSection(AccountSettingsSectionKey.INTEGRATIONS_DEBRID_TOR_BOX)) {
            resolveApiKeySecretOrNull(TORBOX_SECRET_TYPE, TORBOX_SECRET_REF)
        } else {
            null
        }
        if (sectionKeys.includesSection(AccountSettingsSectionKey.INTEGRATIONS_DEBRID_TOR_BOX) && torBoxApiKey == null) {
            preserveUnresolvedRemoteSecretSection(AccountSettingsSectionKey.INTEGRATIONS_DEBRID_TOR_BOX)
        }
        val easyDebridApiKey = if (sectionKeys.includesSection(AccountSettingsSectionKey.INTEGRATIONS_DEBRID_EASY_DEBRID)) {
            resolveApiKeySecretOrNull(EASY_DEBRID_SECRET_TYPE, EASY_DEBRID_SECRET_REF)
        } else {
            null
        }
        if (
            sectionKeys.includesSection(AccountSettingsSectionKey.INTEGRATIONS_DEBRID_EASY_DEBRID) &&
            easyDebridApiKey == null
        ) {
            preserveUnresolvedRemoteSecretSection(AccountSettingsSectionKey.INTEGRATIONS_DEBRID_EASY_DEBRID)
        }
        val resolvedRealDebrid = if (sectionKeys.includesSection(AccountSettingsSectionKey.INTEGRATIONS_DEBRID_REAL_DEBRID)) {
            resolveRemoteRealDebridSecrets(settings.integrations.debrid.realDebrid)
        } else {
            null
        }
        if (
            sectionKeys.includesSection(AccountSettingsSectionKey.INTEGRATIONS_DEBRID_REAL_DEBRID) &&
            resolvedRealDebrid == null
        ) {
            preserveUnresolvedRemoteSecretSection(AccountSettingsSectionKey.INTEGRATIONS_DEBRID_REAL_DEBRID)
        } else if (
            sectionKeys.includesSection(AccountSettingsSectionKey.INTEGRATIONS_DEBRID_REAL_DEBRID) &&
            resolvedRealDebrid?.preserveLocalTokens == true
        ) {
            preservedLocalSecretSections += AccountSettingsSectionKey.INTEGRATIONS_DEBRID_REAL_DEBRID
            followUpLocalSecretSections += AccountSettingsSectionKey.INTEGRATIONS_DEBRID_REAL_DEBRID
        }
        val resolvedTrakt = if (sectionKeys.includesSection(AccountSettingsSectionKey.INTEGRATIONS_TRAKT_AUTH)) {
            resolveRemoteTraktSecrets(settings.integrations.traktAuth)
        } else {
            null
        }
        if (sectionKeys.includesSection(AccountSettingsSectionKey.INTEGRATIONS_TRAKT_AUTH)) {
            when {
                resolvedTrakt == null -> preserveUnresolvedRemoteSecretSection(AccountSettingsSectionKey.INTEGRATIONS_TRAKT_AUTH)
                resolvedTrakt.preserveLocalTokens -> {
                    preservedLocalSecretSections += AccountSettingsSectionKey.INTEGRATIONS_TRAKT_AUTH
                    followUpLocalSecretSections += AccountSettingsSectionKey.INTEGRATIONS_TRAKT_AUTH
                }
            }
        }
        val resolvedSimkl = if (sectionKeys.includesSection(AccountSettingsSectionKey.INTEGRATIONS_SIMKL_AUTH)) {
            resolveRemoteSimklSecrets(settings.integrations.simklAuth)
        } else {
            null
        }
        if (sectionKeys.includesSection(AccountSettingsSectionKey.INTEGRATIONS_SIMKL_AUTH) && resolvedSimkl == null) {
            preserveUnresolvedRemoteSecretSection(AccountSettingsSectionKey.INTEGRATIONS_SIMKL_AUTH)
        } else if (sectionKeys.includesSection(AccountSettingsSectionKey.INTEGRATIONS_SIMKL_AUTH) && resolvedSimkl?.preserveLocalTokens == true) {
            preservedLocalSecretSections += AccountSettingsSectionKey.INTEGRATIONS_SIMKL_AUTH
            followUpLocalSecretSections += AccountSettingsSectionKey.INTEGRATIONS_SIMKL_AUTH
        }
        val resolvedKitsu = if (sectionKeys.includesSection(AccountSettingsSectionKey.INTEGRATIONS_KITSU_AUTH)) {
            resolveRemoteKitsuSecrets(settings.integrations.kitsuAuth)
        } else {
            null
        }
        if (sectionKeys.includesSection(AccountSettingsSectionKey.INTEGRATIONS_KITSU_AUTH)) {
            when {
                resolvedKitsu == null -> preserveUnresolvedRemoteSecretSection(AccountSettingsSectionKey.INTEGRATIONS_KITSU_AUTH)
                resolvedKitsu.preserveLocalTokens -> {
                    preservedLocalSecretSections += AccountSettingsSectionKey.INTEGRATIONS_KITSU_AUTH
                    followUpLocalSecretSections += AccountSettingsSectionKey.INTEGRATIONS_KITSU_AUTH
                }
            }
        }
        return ResolvedRemoteSecretsForApply(
            mdbListApiKey = mdbListApiKey,
            omdbApiKey = omdbApiKey,
            subtitleTranslationApiKey = if (subtitleTranslationSecretUnresolved) {
                null
            } else {
                selectSubtitleTranslationApiKeySecret(
                    genericTranslationKey = genericTranslationKey,
                    legacyGeminiKey = legacyGeminiKey,
                    allowLegacyFallback = allowLegacyFallback
                )
            },
            animeSkipClientId = animeSkipClientId,
            rpdbApiKey = rpdbApiKey,
            topPostersApiKey = topPostersApiKey,
            premiumizeApiKey = premiumizeApiKey,
            torBoxApiKey = torBoxApiKey,
            easyDebridApiKey = easyDebridApiKey,
            realDebrid = resolvedRealDebrid,
            trakt = resolvedTrakt,
            simkl = resolvedSimkl,
            kitsu = resolvedKitsu,
            preservedLocalSectionKeys = preservedLocalSecretSections,
            unresolvedRemoteSecretSectionKeys = unresolvedRemoteSecretSections,
            followUpLocalSecretSectionKeys = followUpLocalSecretSections
        )
    }

    private suspend fun applyResolvedRemoteSecrets(
        secrets: ResolvedRemoteSecretsForApply,
        sectionKeys: Set<AccountSettingsSectionKey>
    ) {
        if (sectionKeys.includesSection(AccountSettingsSectionKey.INTEGRATIONS_MDBLIST)) {
            secrets.mdbListApiKey?.let { mdbListSettingsDataStore.setApiKey(it) }
        }
        if (sectionKeys.includesSection(AccountSettingsSectionKey.INTEGRATIONS_OMDB)) {
            secrets.omdbApiKey?.let { omdbSettingsDataStore.setApiKey(it) }
        }
        if (sectionKeys.includesAnySection(SUBTITLE_TRANSLATION_SECRET_SECTION_KEYS)) {
            secrets.subtitleTranslationApiKey?.let { subtitleTranslationSettingsDataStore.setApiKey(it) }
        }
        if (sectionKeys.includesSection(AccountSettingsSectionKey.INTEGRATIONS_ANIME_SKIP)) {
            secrets.animeSkipClientId?.let { animeSkipSettingsDataStore.setClientId(it) }
        }
        if (sectionKeys.includesSection(AccountSettingsSectionKey.INTEGRATIONS_POSTER_RATINGS)) {
            secrets.rpdbApiKey?.let { posterRatingsSettingsDataStore.setRpdbApiKey(it) }
            secrets.topPostersApiKey?.let { posterRatingsSettingsDataStore.setTopPostersApiKey(it) }
        }
        if (sectionKeys.includesSection(AccountSettingsSectionKey.INTEGRATIONS_DEBRID_PREMIUMIZE)) {
            secrets.premiumizeApiKey?.let { premiumizeSettingsDataStore.setApiKey(it) }
        }
        if (sectionKeys.includesSection(AccountSettingsSectionKey.INTEGRATIONS_DEBRID_TOR_BOX)) {
            secrets.torBoxApiKey?.let { torBoxSettingsDataStore.setApiKey(it) }
        }
        if (sectionKeys.includesSection(AccountSettingsSectionKey.INTEGRATIONS_DEBRID_EASY_DEBRID)) {
            secrets.easyDebridApiKey?.let { easyDebridSettingsDataStore.setApiKey(it) }
        }
        if (sectionKeys.includesSection(AccountSettingsSectionKey.INTEGRATIONS_DEBRID_REAL_DEBRID)) {
            secrets.realDebrid?.let { applyResolvedRemoteRealDebridSecrets(it) }
        }
        if (sectionKeys.includesSection(AccountSettingsSectionKey.INTEGRATIONS_TRAKT_AUTH)) {
            secrets.trakt?.let { applyResolvedRemoteTraktSecrets(it) }
        }
        if (sectionKeys.includesSection(AccountSettingsSectionKey.INTEGRATIONS_SIMKL_AUTH)) {
            secrets.simkl?.let { applyResolvedRemoteSimklSecrets(it) }
        }
        if (sectionKeys.includesSection(AccountSettingsSectionKey.INTEGRATIONS_KITSU_AUTH)) {
            secrets.kitsu?.let { applyResolvedRemoteKitsuSecrets(it) }
        }
    }

    /**
     * Returns null if the resolve RPC failed (network/JWT/decode) so callers can
     * leave local credentials untouched. Returns the trimmed key (possibly empty)
     * when the server authoritatively responded.
     */
    private suspend fun resolveApiKeySecretOrNull(secretType: String, secretRef: String): String? {
        val result = runCatching {
            withJwtRefreshRetry {
                postgrest.rpc(
                    "sync_resolve_account_secret",
                    buildJsonObject {
                        put("p_secret_type", secretType)
                        put("p_secret_ref", secretRef)
                        put("p_source", "app")
                    }
                ).decodeAs<AccountSecretApiKeyPayload>()
            }
        }
        if (result.isFailure) return null
        return result.getOrNull()?.apiKey?.trim().orEmpty()
    }

    private suspend fun resolveRemoteTraktSecrets(remote: TraktAuthSyncSettings): ResolvedRemoteTraktSecrets? {
        val accessResult = runCatching {
            withJwtRefreshRetry {
                postgrest.rpc(
                    "sync_resolve_account_secret",
                    buildJsonObject {
                        put("p_secret_type", TRAKT_ACCESS_SECRET_TYPE)
                        put("p_secret_ref", TRAKT_SECRET_REF)
                        put("p_source", "app")
                    }
                ).decodeAs<AccountTraktAccessSecretPayload>()
            }
        }

        val refreshResult = runCatching {
            withJwtRefreshRetry {
                postgrest.rpc(
                    "sync_resolve_account_secret",
                    buildJsonObject {
                        put("p_secret_type", TRAKT_REFRESH_SECRET_TYPE)
                        put("p_secret_ref", TRAKT_SECRET_REF)
                        put("p_source", "app")
                    }
                ).decodeAs<AccountTraktRefreshSecretPayload>()
            }
        }

        // If either secret RPC failed (network/JWT/decode), do NOT touch local auth.
        // Clearing on transient failure is what was logging the user out on every upgrade.
        if (accessResult.isFailure || refreshResult.isFailure) {
            return null
        }

        val accessPayload = accessResult.getOrNull()
        val refreshPayload = refreshResult.getOrNull()
        val accessToken = accessPayload?.accessToken?.trim().orEmpty()
        val refreshToken = refreshPayload?.refreshToken?.trim().orEmpty()
        if (accessToken.isBlank() || refreshToken.isBlank()) {
            val localState = traktAuthDataStore.stateForProfile(profileModeRouter.defaultLegacyProfileId()).first()
            val localHasTokens = !localState.accessToken.isNullOrBlank() &&
                !localState.refreshToken.isNullOrBlank()
            val preserveLocalTokens = localHasTokens && (remote.connected || remote.pending)
            return ResolvedRemoteTraktSecrets(
                accessPayload = accessPayload,
                refreshPayload = refreshPayload,
                preserveLocalTokens = preserveLocalTokens,
                remote = remote
            )
        }

        // Trakt rotates the refresh token on every refresh — once we've used a
        // refresh token, the previous pair is dead at the auth server. If local
        // has a *newer* token pair than what's stored in Supabase (which can
        // happen when an earlier refresh push was skipped — e.g. because the
        // sync session wasn't yet hydrated when refreshTokenIfNeeded fired), we
        // must NOT overwrite local with the stale remote pair: doing so makes
        // the next API call hit 401 → force-refresh against the already-rotated
        // remote refresh token → invalid_grant → clearAuth(). That's the
        // upgrade-time logout. Instead, keep local and shove it back upstream
        // so both sides converge.
        val localState = traktAuthDataStore.stateForProfile(profileModeRouter.defaultLegacyProfileId()).first()
        val localCreatedAt = localState.createdAt ?: 0L
        val remoteCreatedAt = accessPayload?.createdAt ?: 0L
        val localHasTokens = !localState.accessToken.isNullOrBlank() &&
            !localState.refreshToken.isNullOrBlank()
        val preserveLocalTokens = localHasTokens && localCreatedAt >= remoteCreatedAt
        if (preserveLocalTokens) {
            Log.w(
                TAG,
                "resolveRemoteTraktSecrets: local token (createdAt=$localCreatedAt) is newer " +
                    "than remote (createdAt=$remoteCreatedAt); preserving local"
            )
        }

        return ResolvedRemoteTraktSecrets(
            accessPayload = accessPayload,
            refreshPayload = refreshPayload,
            preserveLocalTokens = preserveLocalTokens,
            remote = remote
        )
    }

    private suspend fun applyResolvedRemoteTraktSecrets(secrets: ResolvedRemoteTraktSecrets) {
        val accessPayload = secrets.accessPayload
        val refreshPayload = secrets.refreshPayload
        val accessToken = accessPayload?.accessToken?.trim().orEmpty()
        val refreshToken = refreshPayload?.refreshToken?.trim().orEmpty()

        if (accessToken.isBlank() || refreshToken.isBlank()) {
            val remote = secrets.remote
            if (!remote.connected && !remote.pending) {
                traktAuthDataStore.clearAuth(profileModeRouter.defaultLegacyProfileId())
            }
            return
        }

        if (secrets.preserveLocalTokens) {
            traktAuthDataStore.saveUser(
                username = secrets.remote.username.takeIf { it.isNotBlank() },
                userSlug = secrets.remote.userSlug.takeIf { it.isNotBlank() },
                profileId = profileModeRouter.defaultLegacyProfileId()
            )
            if (!secrets.remote.pending) {
                traktAuthDataStore.clearDeviceFlow(profileModeRouter.defaultLegacyProfileId())
            }
            return
        }

        traktAuthDataStore.saveToken(
            TraktTokenResponseDto(
                accessToken = accessToken,
                tokenType = accessPayload?.tokenType?.ifBlank { "bearer" } ?: "bearer",
                expiresIn = accessPayload?.expiresIn ?: 0,
                refreshToken = refreshToken,
                createdAt = accessPayload?.createdAt ?: 0L
            ),
            profileId = profileModeRouter.defaultLegacyProfileId()
        )
        traktAuthDataStore.saveUser(
            username = secrets.remote.username.takeIf { it.isNotBlank() },
            userSlug = secrets.remote.userSlug.takeIf { it.isNotBlank() },
            profileId = profileModeRouter.defaultLegacyProfileId()
        )
        if (!secrets.remote.pending) {
            traktAuthDataStore.clearDeviceFlow(profileModeRouter.defaultLegacyProfileId())
        }
    }

    private suspend fun resolveRemoteSimklSecrets(remote: SimklAuthSyncSettings): ResolvedRemoteSimklSecrets? {
        val accessResult = runCatching {
            withJwtRefreshRetry {
                postgrest.rpc(
                    "sync_resolve_account_secret",
                    buildJsonObject {
                        put("p_secret_type", SIMKL_ACCESS_SECRET_TYPE)
                        put("p_secret_ref", SIMKL_SECRET_REF)
                        put("p_source", "app")
                    }
                ).decodeAs<AccountSimklAccessSecretPayload>()
            }
        }

        if (accessResult.isFailure) {
            return null
        }

        val accessPayload = accessResult.getOrNull()
        val accessToken = accessPayload?.accessToken?.trim().orEmpty()
        val localState = simklAuthDataStore.stateForProfile(profileModeRouter.defaultLegacyProfileId()).first()
        val preserveLocalTokens = accessToken.isBlank() &&
            !localState.accessToken.isNullOrBlank() &&
            (remote.connected || remote.pending)

        return ResolvedRemoteSimklSecrets(
            accessPayload = accessPayload,
            preserveLocalTokens = preserveLocalTokens,
            remote = remote
        )
    }

    private suspend fun applyResolvedRemoteSimklSecrets(secrets: ResolvedRemoteSimklSecrets) {
        val accessToken = secrets.accessPayload?.accessToken?.trim().orEmpty()
        if (accessToken.isBlank()) {
            val remote = secrets.remote
            if (secrets.preserveLocalTokens) {
                simklAuthDataStore.saveUser(
                    username = remote.username.takeIf { it.isNotBlank() },
                    accountId = remote.accountId,
                    accountType = remote.accountType.takeIf { it.isNotBlank() },
                    profileId = profileModeRouter.defaultLegacyProfileId()
                )
                if (!remote.pending) {
                    simklAuthDataStore.clearDeviceFlow(profileModeRouter.defaultLegacyProfileId())
                }
                return
            }
            if (!remote.connected && !remote.pending) {
                simklAuthDataStore.clearAuth(profileModeRouter.defaultLegacyProfileId())
            }
            return
        }

        simklAuthDataStore.saveAccessToken(accessToken, profileId = profileModeRouter.defaultLegacyProfileId())
        simklAuthDataStore.saveUser(
            username = secrets.remote.username.takeIf { it.isNotBlank() },
            accountId = secrets.remote.accountId,
            accountType = secrets.remote.accountType.takeIf { it.isNotBlank() },
            profileId = profileModeRouter.defaultLegacyProfileId()
        )
        if (!secrets.remote.pending) {
            simklAuthDataStore.clearDeviceFlow(profileModeRouter.defaultLegacyProfileId())
        }
    }

    private suspend fun resolveRemoteKitsuSecrets(remote: KitsuAuthSyncSettings): ResolvedRemoteKitsuSecrets? {
        val accessResult = runCatching {
            withJwtRefreshRetry {
                postgrest.rpc(
                    "sync_resolve_account_secret",
                    buildJsonObject {
                        put("p_secret_type", KITSU_ACCESS_SECRET_TYPE)
                        put("p_secret_ref", KITSU_SECRET_REF)
                        put("p_source", "app")
                    }
                ).decodeAs<AccountKitsuAccessSecretPayload>()
            }
        }

        val refreshResult = runCatching {
            withJwtRefreshRetry {
                postgrest.rpc(
                    "sync_resolve_account_secret",
                    buildJsonObject {
                        put("p_secret_type", KITSU_REFRESH_SECRET_TYPE)
                        put("p_secret_ref", KITSU_SECRET_REF)
                        put("p_source", "app")
                    }
                ).decodeAs<AccountKitsuRefreshSecretPayload>()
            }
        }

        if (accessResult.isFailure || refreshResult.isFailure) {
            return null
        }

        val accessPayload = accessResult.getOrNull()
        val refreshPayload = refreshResult.getOrNull()
        val accessToken = accessPayload?.accessToken?.trim().orEmpty()
        val refreshToken = refreshPayload?.refreshToken?.trim().orEmpty()
        val remoteExpiresAt = accessPayload?.expiresAtEpochSeconds ?: remote.expiresAtEpochSeconds ?: 0L
        val localState = kitsuAuthDataStore.stateForProfile(profileModeRouter.defaultLegacyProfileId()).first()
        val localExpiresAt = localState.expiresAtEpochSeconds ?: 0L
        val localHasTokens = !localState.accessToken.isNullOrBlank() &&
            !localState.refreshToken.isNullOrBlank()
        val remoteHasTokens = accessToken.isNotBlank() && refreshToken.isNotBlank()
        val preserveLocalTokens = localHasTokens &&
            (
                (remoteHasTokens && localExpiresAt >= remoteExpiresAt) ||
                    (!remoteHasTokens && remote.connected)
                )
        if (preserveLocalTokens) {
            Log.w(
                TAG,
                "resolveRemoteKitsuSecrets: local token (expiresAt=$localExpiresAt) is newer " +
                    "than remote (expiresAt=$remoteExpiresAt); preserving local"
            )
        }

        return ResolvedRemoteKitsuSecrets(
            accessPayload = accessPayload,
            refreshPayload = refreshPayload,
            preserveLocalTokens = preserveLocalTokens,
            remote = remote
        )
    }

    private suspend fun applyResolvedRemoteKitsuSecrets(secrets: ResolvedRemoteKitsuSecrets) {
        val accessToken = secrets.accessPayload?.accessToken?.trim().orEmpty()
        val refreshToken = secrets.refreshPayload?.refreshToken?.trim().orEmpty()
        val remote = secrets.remote
        if (accessToken.isBlank() || refreshToken.isBlank()) {
            if (!remote.connected) {
                kitsuAuthDataStore.clearAuth(profileModeRouter.defaultLegacyProfileId())
            }
            return
        }

        val defaultProfileId = profileModeRouter.defaultLegacyProfileId()
        val currentKitsu = kitsuAuthDataStore.stateForProfile(defaultProfileId).first()
        if (secrets.preserveLocalTokens) {
            kitsuAuthDataStore.saveForProfile(
                defaultProfileId,
                currentKitsu.copy(
                    enabled = true,
                    username = remote.username.takeIf { it.isNotBlank() },
                    includeNsfw = remote.includeNsfw,
                    password = null
                )
            )
            return
        }
        kitsuAuthDataStore.saveForProfile(
            defaultProfileId,
            currentKitsu.copy(
                enabled = true,
                username = remote.username.takeIf { it.isNotBlank() },
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresAtEpochSeconds = secrets.accessPayload?.expiresAtEpochSeconds
                    ?: remote.expiresAtEpochSeconds
                    ?: currentKitsu.expiresAtEpochSeconds,
                includeNsfw = remote.includeNsfw,
                password = null
            )
        )
    }

    private suspend fun resolveRemoteRealDebridSecrets(remote: RealDebridSyncSettings): ResolvedRemoteRealDebridSecrets? {
        val accessResult = runCatching {
            withJwtRefreshRetry {
                postgrest.rpc(
                    "sync_resolve_account_secret",
                    buildJsonObject {
                        put("p_secret_type", REAL_DEBRID_ACCESS_SECRET_TYPE)
                        put("p_secret_ref", REAL_DEBRID_SECRET_REF)
                        put("p_source", "app")
                    }
                ).decodeAs<AccountRealDebridAccessSecretPayload>()
            }
        }

        val refreshResult = runCatching {
            withJwtRefreshRetry {
                postgrest.rpc(
                    "sync_resolve_account_secret",
                    buildJsonObject {
                        put("p_secret_type", REAL_DEBRID_REFRESH_SECRET_TYPE)
                        put("p_secret_ref", REAL_DEBRID_SECRET_REF)
                        put("p_source", "app")
                    }
                ).decodeAs<AccountRealDebridRefreshSecretPayload>()
            }
        }

        // If either secret RPC failed (network/JWT/decode), do NOT touch local auth.
        // Same upgrade-time logout class of bug as the Trakt path used to have.
        if (accessResult.isFailure || refreshResult.isFailure) {
            return null
        }

        val accessPayload = accessResult.getOrNull()
        val refreshPayload = refreshResult.getOrNull()
        val accessToken = accessPayload?.accessToken?.trim().orEmpty()
        val refreshToken = refreshPayload?.refreshToken?.trim().orEmpty()
        val userClientId = accessPayload?.userClientId?.trim().orEmpty()
        val userClientSecret = accessPayload?.userClientSecret?.trim().orEmpty()
        val remoteHasTokens = accessToken.isNotBlank() &&
            refreshToken.isNotBlank() &&
            userClientId.isNotBlank() &&
            userClientSecret.isNotBlank()
        val localState = realDebridAuthDataStore.state.first()
        val preserveLocalTokens = !remoteHasTokens &&
            localState.isAuthenticated &&
            (remote.connected || remote.pending)

        return ResolvedRemoteRealDebridSecrets(
            accessPayload = accessPayload,
            refreshPayload = refreshPayload,
            preserveLocalTokens = preserveLocalTokens,
            remote = remote
        )
    }

    private suspend fun applyResolvedRemoteRealDebridSecrets(secrets: ResolvedRemoteRealDebridSecrets) {
        val accessPayload = secrets.accessPayload
        val refreshPayload = secrets.refreshPayload
        val accessToken = accessPayload?.accessToken?.trim().orEmpty()
        val refreshToken = refreshPayload?.refreshToken?.trim().orEmpty()
        val userClientId = accessPayload?.userClientId?.trim().orEmpty()
        val userClientSecret = accessPayload?.userClientSecret?.trim().orEmpty()

        if (secrets.preserveLocalTokens) {
            realDebridAuthDataStore.saveUsername(secrets.remote.username.takeIf { it.isNotBlank() })
            return
        }

        if (
            accessToken.isNotBlank() &&
            refreshToken.isNotBlank() &&
            userClientId.isNotBlank() &&
            userClientSecret.isNotBlank()
        ) {
            realDebridAuthDataStore.saveUserCredentials(
                RealDebridDeviceCredentialsResponseDto(
                    clientId = userClientId,
                    clientSecret = userClientSecret
                )
            )
            realDebridAuthDataStore.saveToken(
                RealDebridTokenResponseDto(
                    accessToken = accessToken,
                    expiresIn = accessPayload?.expiresIn ?: 0,
                    tokenType = accessPayload?.tokenType ?: "Bearer",
                    refreshToken = refreshToken
                )
            )
            realDebridAuthDataStore.saveUsername(
                secrets.remote.username.takeIf { it.isNotBlank() }
            )
            realDebridAuthDataStore.clearDeviceFlow()
            return
        }

        val remoteFlow = secrets.remote
        if (remoteFlow.pending && remoteFlow.deviceCode.isNotBlank()) {
            realDebridAuthDataStore.clearAuth()
            val expiresInSeconds = remoteFlow.expiresAt
                ?.let { expiresAt -> ((expiresAt - System.currentTimeMillis()).coerceAtLeast(1_000L) / 1_000L).toInt() }
                ?: 600
            realDebridAuthDataStore.saveDeviceFlow(
                RealDebridDeviceCodeResponseDto(
                    deviceCode = remoteFlow.deviceCode,
                    userCode = remoteFlow.userCode.ifBlank { "PENDING" },
                    expiresIn = expiresInSeconds,
                    verificationUrl = remoteFlow.verificationUrl.ifBlank { "https://real-debrid.com/device" }
                )
            )
            realDebridAuthDataStore.saveUsername(remoteFlow.username.takeIf { it.isNotBlank() })
            return
        }

        realDebridAuthDataStore.clearAuth()
    }

    private suspend fun resolveRemoteAddonUrl(addon: AccountAddonPayload): Result<String> {
        return runCatching {
            if (addon.transportSchemaVersion == 2 && !addon.transportSecretRef.isNullOrBlank()) {
                val transportPayload = withJwtRefreshRetry {
                    postgrest.rpc(
                        "sync_resolve_account_secret",
                        buildJsonObject {
                            put("p_secret_type", "addon_credential")
                            put("p_secret_ref", addon.transportSecretRef)
                            put("p_source", "app")
                        }
                    ).decodeAs<AccountAddonSecretPayload>()
                }.requireValidV2Transport(
                    secretRef = addon.transportSecretRef,
                    addonUrl = addon.url
                )
                return@runCatching buildResolvedAddonUrl(
                    baseUrl = addon.transportBaseUrl ?: addon.url,
                    manifestUrl = null,
                    publicQueryParams = emptyMap(),
                    secretPayload = transportPayload
                ).let(::normalizeAddonInstallUrl)
            }

            val secretPayload = addon.secretRef
                ?.takeIf { it.isNotBlank() }
                ?.let { secretRef ->
                    withJwtRefreshRetry {
                        postgrest.rpc(
                            "sync_resolve_account_secret",
                            buildJsonObject {
                                put("p_secret_type", "addon_credential")
                                put("p_secret_ref", secretRef)
                                put("p_source", "app")
                            }
                        ).decodeAs<AccountAddonSecretPayload>()
                    }.requireValidV1Secret(
                        secretRef = secretRef,
                        addonUrl = addon.url
                    )
                }

            buildResolvedAddonUrl(
                baseUrl = addon.url,
                manifestUrl = addon.manifestUrl,
                publicQueryParams = addon.publicQueryParams,
                secretPayload = secretPayload
            ).let(::normalizeAddonInstallUrl)
        }
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, default: T): T {
        return runCatching { enumValueOf<T>(value.trim().uppercase()) }.getOrDefault(default)
    }
}
