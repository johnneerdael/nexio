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
import com.nexio.tv.data.local.TheIntroDbSettingsDataStore
import com.nexio.tv.data.local.WyzieSettingsDataStore
import com.nexio.tv.data.local.ThemeDataStore
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
import com.nexio.tv.data.remote.supabase.AccountConfigSnapshotRpcResponse
import com.nexio.tv.data.remote.supabase.AccountConfigSyncPayload
import com.nexio.tv.data.remote.supabase.AccountConfigV7PushResult
import com.nexio.tv.data.remote.supabase.AccountRealDebridAccessSecretPayload
import com.nexio.tv.data.remote.supabase.AccountRealDebridRefreshSecretPayload
import com.nexio.tv.data.remote.supabase.AccountSettingsPayload
import com.nexio.tv.data.remote.supabase.AccountSimklAccessSecretPayload
import com.nexio.tv.data.remote.supabase.AccountSecretApiKeyPayload
import com.nexio.tv.data.remote.supabase.AccountSnapshotRpcResponse
import com.nexio.tv.data.remote.supabase.AccountTraktAccessSecretPayload
import com.nexio.tv.data.remote.supabase.AccountTraktRefreshSecretPayload
import com.nexio.tv.data.remote.supabase.AccountTvdbCredentialSecretPayload
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
import com.nexio.tv.data.remote.supabase.TheIntroDbSyncSettings
import com.nexio.tv.data.remote.supabase.TmdbSyncSettings
import com.nexio.tv.data.remote.supabase.TorBoxSyncSettings
import com.nexio.tv.data.remote.supabase.TraktAuthSyncSettings
import com.nexio.tv.data.remote.supabase.TraktPinnedListOptionSync
import com.nexio.tv.data.remote.supabase.TraktSettingsPayload
import com.nexio.tv.data.remote.supabase.TvdbSyncSettings
import com.nexio.tv.data.remote.supabase.WyzieSyncSettings
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.core.profile.ProfileModeRouter
import com.nexio.tv.data.repository.EasyDebridService
import com.nexio.tv.data.repository.PremiumizeService
import com.nexio.tv.data.repository.TorBoxService
import com.nexio.tv.domain.model.AddonParserPreset
import com.nexio.tv.domain.model.AppFont
import com.nexio.tv.domain.model.AppTheme
import com.nexio.tv.domain.model.HomeLayout
import com.nexio.tv.domain.model.TrackingProvider
import com.nexio.tv.domain.model.TvdbValidationStatus
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AccountSettingsSync"
private const val TMDB_SECRET_TYPE = "tmdb_api_key"
private const val TMDB_SECRET_REF = "integration:tmdb"
private const val TVDB_SECRET_TYPE = "tvdb_api_key"
private const val TVDB_SECRET_REF = "integration:tvdb"
private const val MDBLIST_SECRET_TYPE = "mdblist_api_key"
private const val MDBLIST_SECRET_REF = "integration:mdblist"
private const val OMDB_SECRET_TYPE = "omdb_api_key"
private const val OMDB_SECRET_REF = "integration:omdb"
private const val TRANSLATION_SECRET_TYPE = "translation_api_key"
private const val TRANSLATION_SECRET_REF = "integration:subtitle-translation"
private const val WYZIE_SECRET_TYPE = "wyzie_api_key"
private const val WYZIE_SECRET_REF = "integration:wyzie"
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

internal fun selectSubtitleTranslationApiKeySecret(
    genericTranslationKey: String?,
    legacyGeminiKey: String?,
    allowLegacyFallback: Boolean
): String? {
    if (genericTranslationKey == null) return null
    if (genericTranslationKey.isNotBlank()) return genericTranslationKey
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
    private val theIntroDbSettingsDataStore: TheIntroDbSettingsDataStore,
    private val animeSkipSettingsDataStore: AnimeSkipSettingsDataStore,
    private val subtitleTranslationSettingsDataStore: SubtitleTranslationSettingsDataStore,
    private val wyzieSettingsDataStore: WyzieSettingsDataStore,
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
    private val debugSettingsDataStore: DebugSettingsDataStore,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val profileManager: ProfileManager,
    private val profileModeRouter: ProfileModeRouter,
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

    private val startupPushGate = AccountConfigStartupPushGate()
    private val pendingChangedPaths = linkedSetOf<String>()
    private var pendingChangedPathsGeneration: Long = 0L

    @Volatile
    private var lastAppliedRemoteRevision: Long = 0L
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
                tvdbSettings = tvdbSettingsDataStore.settings.drop(1).map { Unit },
                mdbListSettings = mdbListSettingsDataStore.settings.drop(1).map { Unit },
                mdbListCatalogPreferences = mdbListSettingsDataStore.catalogPreferences.drop(1).map { Unit },
                omdbSettings = omdbSettingsDataStore.settings.drop(1).map { Unit },
                theIntroDbSettings = theIntroDbSettingsDataStore.settings
                    .map {
                        listOf(
                            it.enabled,
                            it.showIntroButton,
                            it.showRecapButton,
                            it.showCreditsButton,
                            it.showPreviewButton
                        ).joinToString("|")
                    }
                    .distinctUntilChanged()
                    .drop(1)
                    .map { Unit },
                animeSkipEnabled = animeSkipSettingsDataStore.enabled.drop(1).map { Unit },
                subtitleTranslationSettings = subtitleTranslationSettingsDataStore.settings.drop(1).map { Unit },
                wyzieSettings = wyzieSettingsDataStore.settings.drop(1).map { Unit },
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
        val baseRevision: Long,
        val changedPaths: List<String>,
        val changedPathsGeneration: Long,
        val secrets: AccountSecretPushSnapshot
    )

    private data class AccountSecretPushSnapshot(
        val tmdbApiKey: String,
        val tvdbApiKey: String,
        val tvdbPin: String,
        val mdbListApiKey: String,
        val omdbApiKey: String,
        val subtitleTranslationApiKey: String,
        val legacyGeminiApiKey: String?,
        val wyzieApiKey: String,
        val animeSkipClientId: String,
        val rpdbApiKey: String,
        val topPostersApiKey: String,
        val premiumizeApiKey: String,
        val torBoxApiKey: String,
        val easyDebridApiKey: String,
        val realDebrid: RealDebridSecretPushSnapshot,
        val trakt: TraktSecretPushSnapshot,
        val simkl: SimklSecretPushSnapshot
    )

    private data class RealDebridSecretPushSnapshot(
        val accessToken: String,
        val refreshToken: String,
        val tokenType: String,
        val expiresIn: Int,
        val userClientId: String,
        val userClientSecret: String
    )

    private data class TraktSecretPushSnapshot(
        val accessToken: String,
        val refreshToken: String,
        val tokenType: String,
        val createdAt: Long,
        val expiresIn: Int
    )

    private data class SimklSecretPushSnapshot(
        val accessToken: String
    )

    private data class ResolvedRemoteSecretsForApply(
        val tmdbApiKey: String?,
        val tvdbCredential: AccountTvdbCredentialSecretPayload?,
        val mdbListApiKey: String?,
        val omdbApiKey: String?,
        val subtitleTranslationApiKey: String?,
        val wyzieApiKey: String?,
        val animeSkipClientId: String?,
        val rpdbApiKey: String?,
        val topPostersApiKey: String?,
        val premiumizeApiKey: String?,
        val torBoxApiKey: String?,
        val easyDebridApiKey: String?,
        val realDebrid: ResolvedRemoteRealDebridSecrets?,
        val trakt: ResolvedRemoteTraktSecrets?,
        val simkl: ResolvedRemoteSimklSecrets?
    )

    private data class ResolvedRemoteRealDebridSecrets(
        val accessPayload: AccountRealDebridAccessSecretPayload?,
        val refreshPayload: AccountRealDebridRefreshSecretPayload?,
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
        val remote: SimklAuthSyncSettings
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
                    baseRevision = lastAppliedRemoteRevision,
                    changedPaths = changedPaths,
                    changedPathsGeneration = changedPathsGeneration,
                    secrets = buildAccountSecretPushSnapshot()
                )
            } ?: return@withContext Result.success(Unit)

            var scheduleFollowUpPush = false

            if (snapshot.changedPaths.isNotEmpty()) {
                if (!hasLiveFullAccountSession()) return@withContext Result.success(Unit)
                val pushResult = withJwtRefreshRetry {
                    postgrest.rpc(
                        "sync_push_account_settings_v7",
                        buildAccountConfigSyncPushParamsV7(
                            payload = snapshot.payload,
                            baseRevision = snapshot.baseRevision,
                            changedPaths = snapshot.changedPaths
                        )
                    ).decodeAs<AccountConfigV7PushResult>()
                }

                var pullAfterConflict = false
                if (!pushResult.applied) {
                    Log.w(TAG, "Account settings push conflicted paths=${pushResult.conflictPaths.joinToString(",")}")
                    applyingRemoteMutex.withLock {
                        if (isApplyingRemote || !hasLiveFullAccountSession()) return@withLock
                        val hasNewerLocalChanges = synchronized(pendingChangedPaths) {
                            pendingChangedPathsGeneration != snapshot.changedPathsGeneration
                        }
                        if (hasNewerLocalChanges) {
                            scheduleFollowUpPush = true
                        } else {
                            pullAfterConflict = true
                        }
                    }
                    if (scheduleFollowUpPush && hasLiveFullAccountSession()) {
                        pushJob = scope.launch {
                            delay(500)
                            pushToRemote()
                        }
                    }
                    if (pullAfterConflict) {
                        pullFromRemoteAndApply()
                    }
                    return@withContext Result.success(Unit)
                }

                applyingRemoteMutex.withLock {
                    if (isApplyingRemote || !hasLiveFullAccountSession()) return@withLock
                    lastAppliedRemoteRevision = pushResult.syncRevision
                    synchronized(pendingChangedPaths) {
                        if (pendingChangedPathsGeneration == snapshot.changedPathsGeneration) {
                            pendingChangedPaths.removeAll(snapshot.changedPaths.toSet())
                        } else {
                            scheduleFollowUpPush = true
                        }
                    }
                }
            }

            if (!hasLiveFullAccountSession()) return@withContext Result.success(Unit)
            syncAccountSecretPushSnapshotToRemote(snapshot.secrets)

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
        clearPendingChanges: Boolean = true
    ): Result<List<AddonPreferences.AddonInstallConfig>> = withContext(Dispatchers.IO) {
        try {
            if (!hasLiveFullAccountSession()) {
                return@withContext Result.failure(IllegalStateException("No live full account session"))
            }
            val pullStartedGeneration = synchronized(pendingChangedPaths) { pendingChangedPathsGeneration }
            val switchGenAtPullStart = suppressPushForSwitchGeneration
            val snapshot = withJwtRefreshRetry {
                postgrest.rpc(
                    "sync_pull_account_snapshot",
                    buildAccountConfigSyncPullParams()
                ).decodeAs<AccountConfigSnapshotRpcResponse>()
            }
            val resolvedSecrets = resolveRemoteSecretsForApply(snapshot.settings)

            var appliedRemoteSettings = false
            applyingRemoteMutex.withLock {
                if (!hasLiveFullAccountSession()) {
                    return@withLock
                }
                isApplyingRemote = true
                try {
                    applySharedAccountConfigSyncSettings(snapshot.settings)
                    applyResolvedRemoteSecrets(resolvedSecrets)
                    lastAppliedRemoteRevision = snapshot.settingsRevision
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

            premiumizeService.refreshAccountState()
            torBoxService.refreshAccountState()
            easyDebridService.refreshAccountState()

            if (!hasLiveFullAccountSession()) {
                return@withContext Result.failure(IllegalStateException("No live full account session"))
            }
            val remoteAddonConfigs = buildRemoteAddonInstallConfigs(snapshot.addons, ::resolveRemoteAddonUrl)
            if (!hasLiveFullAccountSession()) {
                return@withContext Result.failure(IllegalStateException("No live full account session"))
            }
            Result.success(remoteAddonConfigs)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull account snapshot from remote", e)
            Result.failure(e)
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

    private suspend fun buildLocalPayload(): AccountConfigSyncPayload {
        val tmdb = tmdbSettingsDataStore.settings.first()
        val tvdb = tvdbSettingsDataStore.settings.first()
        val mdbList = mdbListSettingsDataStore.settings.first()
        val mdbListPrefs = mdbListSettingsDataStore.catalogPreferences.first()
        val isPrimaryProfile = isDefaultLegacyActive()
        val heroCatalogKeys = if (isPrimaryProfile) layoutPreferenceDataStore.heroCatalogSelections.first() else emptyList()
        val homeCatalogOrderKeys = if (isPrimaryProfile) layoutPreferenceDataStore.homeCatalogOrderKeys.first() else emptyList()
        val disabledHomeCatalogKeys = if (isPrimaryProfile) layoutPreferenceDataStore.disabledHomeCatalogKeys.first() else emptyList()
        val traktCatalogPrefs = if (isPrimaryProfile) traktSettingsDataStore.catalogPreferences.first() else null
        val simklCatalogPrefs = if (isPrimaryProfile) simklSettingsDataStore.catalogPreferences.first() else null
        val playerSettings = if (isPrimaryProfile) playerSettingsDataStore.playerSettings.first() else null
        val theIntroDb = theIntroDbSettingsDataStore.settings.first()
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
                    enabled = tmdb.enabled,
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
                tvdb = TvdbSyncSettings(
                    enabled = tvdb.enabled,
                    configured = tvdb.configured,
                    validationStatus = tvdb.validationStatus.name,
                    lastFailure = tvdb.lastFailure
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
                theIntroDb = TheIntroDbSyncSettings(
                    enabled = true,
                    showIntroButton = theIntroDb.showIntroButton,
                    showRecapButton = theIntroDb.showRecapButton,
                    showCreditsButton = theIntroDb.showCreditsButton,
                    showPreviewButton = theIntroDb.showPreviewButton
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
                wyzie = WyzieSyncSettings(
                    enabled = wyzieSettingsDataStore.settings.first().enabled,
                ),
                posterRatings = PosterRatingsSyncSettings(
                    rpdbEnabled = posterRatings.rpdbEnabled,
                    topPostersEnabled = posterRatings.topPostersEnabled
                ),
                kitsuAuth = KitsuAuthSyncSettings(
                    enabled = true,
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
        resolveRemoteInlineSecrets: Boolean = true
    ) {
        lastRemoteTraktPinnedListOptions = settings.catalogs.trakt.pinnedListOptions
        lastRemoteMDBListPinnedTopListOptions = settings.catalogs.mdblist.pinnedTopListOptions

        if (isDefaultLegacyActive()) {
            layoutPreferenceDataStore.setHeroCatalogKeys(settings.catalogs.home.heroCatalogKeys)
            layoutPreferenceDataStore.setHomeCatalogOrderKeys(settings.catalogs.home.homeCatalogOrderKeys)
            layoutPreferenceDataStore.setDisabledHomeCatalogKeys(settings.catalogs.home.disabledHomeCatalogKeys)
            traktSettingsDataStore.setCatalogPreferences(
                enabledCatalogs = settings.catalogs.trakt.catalogEnabledSet.toSet(),
                catalogOrder = settings.catalogs.trakt.catalogOrder,
                selectedPopularListKeys = settings.catalogs.trakt.selectedPopularListKeys.toSet()
            )
            simklSettingsDataStore.setCatalogPreferences(
                enabledCatalogs = settings.catalogs.simkl.catalogEnabledSet.toSet(),
                catalogOrder = settings.catalogs.simkl.catalogOrder
            )
            playerSettingsDataStore.setTrackingProvider(
                runCatching { TrackingProvider.valueOf(settings.playback.streamSelection.trackingProvider) }
                    .getOrDefault(TrackingProvider.TRAKT)
            )
            playerSettingsDataStore.setSyncedFormatterEnabled(settings.formatter.enabled)
            playerSettingsDataStore.setSyncedFormatterSelectedTemplateId(settings.formatter.selectedTemplateId)
            playerSettingsDataStore.setSyncedFormatterCustomTemplate(
                label = settings.formatter.customTemplate?.label,
                nameTemplate = settings.formatter.customTemplate?.nameTemplate,
                descriptionTemplate = settings.formatter.customTemplate?.descriptionTemplate,
                badgeRowTemplate = settings.formatter.customTemplate?.badgeRowTemplate
            )
        }

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

        applyTvdbPublicSyncSettings(settings.integrations.tvdb)

        mdbListSettingsDataStore.setEnabled(settings.integrations.mdblist.enabled)
        mdbListSettingsDataStore.setShowTrakt(settings.integrations.mdblist.showTrakt)
        mdbListSettingsDataStore.setShowImdb(settings.integrations.mdblist.showImdb)
        mdbListSettingsDataStore.setShowTmdb(settings.integrations.mdblist.showTmdb)
        mdbListSettingsDataStore.setShowLetterboxd(settings.integrations.mdblist.showLetterboxd)
        mdbListSettingsDataStore.setShowTomatoes(settings.integrations.mdblist.showTomatoes)
        mdbListSettingsDataStore.setShowAudience(settings.integrations.mdblist.showAudience)
        mdbListSettingsDataStore.setShowMetacritic(settings.integrations.mdblist.showMetacritic)
        mdbListSettingsDataStore.setCatalogPreferences(
            hiddenPersonalListKeys = settings.catalogs.mdblist.hiddenPersonalListKeys.toSet(),
            selectedTopListKeys = settings.catalogs.mdblist.selectedTopListKeys.toSet(),
            catalogOrder = settings.catalogs.mdblist.catalogOrder
        )

        omdbSettingsDataStore.setEnabled(settings.integrations.omdb.enabled)

        theIntroDbSettingsDataStore.setEnabled(true)
        theIntroDbSettingsDataStore.setShowIntroButton(settings.integrations.theIntroDb.showIntroButton)
        theIntroDbSettingsDataStore.setShowRecapButton(settings.integrations.theIntroDb.showRecapButton)
        theIntroDbSettingsDataStore.setShowCreditsButton(settings.integrations.theIntroDb.showCreditsButton)
        theIntroDbSettingsDataStore.setShowPreviewButton(settings.integrations.theIntroDb.showPreviewButton)

        animeSkipSettingsDataStore.setEnabled(settings.integrations.animeSkip.enabled)
        if (resolveRemoteInlineSecrets) resolveApiKeySecretOrNull(ANIMESKIP_SECRET_TYPE, ANIMESKIP_SECRET_REF)?.let {
            animeSkipSettingsDataStore.setClientId(it)
        }

        val remoteTranslation = settings.integrations.subtitleTranslation
        subtitleTranslationSettingsDataStore.saveSyncedPublicSettings(
            enabled = remoteTranslation.enabled,
            provider = remoteTranslation.toDomainSettings().provider,
            model = remoteTranslation.model,
            baseUrl = remoteTranslation.baseUrl
        )
        val remoteWyzie = settings.integrations.wyzie
        wyzieSettingsDataStore.setEnabled(remoteWyzie.enabled)
        if (resolveRemoteInlineSecrets) resolveApiKeySecretOrNull(WYZIE_SECRET_TYPE, WYZIE_SECRET_REF)?.let {
            wyzieSettingsDataStore.setApiKey(it)
        }

        posterRatingsSettingsDataStore.setRpdbEnabled(settings.integrations.posterRatings.rpdbEnabled)
        posterRatingsSettingsDataStore.setTopPostersEnabled(settings.integrations.posterRatings.topPostersEnabled)

        val remoteKitsu = settings.integrations.kitsuAuth
        val defaultProfileId = profileModeRouter.defaultLegacyProfileId()
        val currentKitsu = kitsuAuthDataStore.stateForProfile(defaultProfileId).first()
        kitsuAuthDataStore.save(
            currentKitsu.copy(
                enabled = true,
                username = remoteKitsu.username,
                expiresAtEpochSeconds = remoteKitsu.expiresAtEpochSeconds ?: currentKitsu.expiresAtEpochSeconds,
                includeNsfw = remoteKitsu.includeNsfw
            )
        )

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
        wyzieSettingsDataStore.setApiKey("")
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

        theIntroDbSettingsDataStore.setEnabled(true)
        theIntroDbSettingsDataStore.setShowIntroButton(settings.integrations.theIntroDb.showIntroButton)
        theIntroDbSettingsDataStore.setShowRecapButton(settings.integrations.theIntroDb.showRecapButton)
        theIntroDbSettingsDataStore.setShowCreditsButton(settings.integrations.theIntroDb.showCreditsButton)
        theIntroDbSettingsDataStore.setShowPreviewButton(settings.integrations.theIntroDb.showPreviewButton)

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
        val remoteWyzie = settings.integrations.wyzie
        wyzieSettingsDataStore.setEnabled(remoteWyzie.enabled)
        resolveApiKeySecretOrNull(WYZIE_SECRET_TYPE, WYZIE_SECRET_REF)?.let {
            wyzieSettingsDataStore.setApiKey(it)
        }

        posterRatingsSettingsDataStore.setRpdbEnabled(settings.integrations.posterRatings.rpdbEnabled)
        posterRatingsSettingsDataStore.setTopPostersEnabled(settings.integrations.posterRatings.topPostersEnabled)

        val remoteKitsu = settings.integrations.kitsuAuth
        val defaultProfileId = profileModeRouter.defaultLegacyProfileId()
        val currentKitsu = kitsuAuthDataStore.stateForProfile(defaultProfileId).first()
        kitsuAuthDataStore.save(
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
        val tvdb = tvdbSettingsDataStore.settings.first()
        return AccountSecretPushSnapshot(
            tmdbApiKey = tmdbSettingsDataStore.settings.first().apiKey,
            tvdbApiKey = tvdb.apiKey,
            tvdbPin = tvdb.subscriberPin,
            mdbListApiKey = mdbListSettingsDataStore.settings.first().apiKey,
            omdbApiKey = omdbSettingsDataStore.settings.first().apiKey,
            subtitleTranslationApiKey = subtitleTranslationSettings.apiKey,
            legacyGeminiApiKey = legacyGeminiApiKeySecretForPush(
                providerName = subtitleTranslationSettings.provider.name,
                translationApiKey = subtitleTranslationSettings.apiKey
            ),
            wyzieApiKey = wyzieSettingsDataStore.settings.first().apiKey.orEmpty(),
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
            )
        )
    }

    private suspend fun syncAccountSecretPushSnapshotToRemote(snapshot: AccountSecretPushSnapshot) {
        syncApiKeySecretToRemote(TMDB_SECRET_TYPE, TMDB_SECRET_REF, snapshot.tmdbApiKey)
        syncTvdbCredentialSecretToRemote(snapshot.tvdbApiKey, snapshot.tvdbPin)
        syncApiKeySecretToRemote(MDBLIST_SECRET_TYPE, MDBLIST_SECRET_REF, snapshot.mdbListApiKey)
        syncApiKeySecretToRemote(OMDB_SECRET_TYPE, OMDB_SECRET_REF, snapshot.omdbApiKey)
        syncApiKeySecretToRemote(TRANSLATION_SECRET_TYPE, TRANSLATION_SECRET_REF, snapshot.subtitleTranslationApiKey)
        syncApiKeySecretToRemote(WYZIE_SECRET_TYPE, WYZIE_SECRET_REF, snapshot.wyzieApiKey)
        syncApiKeySecretToRemote(ANIMESKIP_SECRET_TYPE, ANIMESKIP_SECRET_REF, snapshot.animeSkipClientId)
        snapshot.legacyGeminiApiKey?.let { legacyGeminiKey ->
            syncApiKeySecretToRemote(GEMINI_SECRET_TYPE, GEMINI_SECRET_REF, legacyGeminiKey)
        }
        syncApiKeySecretToRemote(RPDB_SECRET_TYPE, RPDB_SECRET_REF, snapshot.rpdbApiKey)
        syncApiKeySecretToRemote(TOP_POSTERS_SECRET_TYPE, TOP_POSTERS_SECRET_REF, snapshot.topPostersApiKey)
        syncApiKeySecretToRemote(PREMIUMIZE_SECRET_TYPE, PREMIUMIZE_SECRET_REF, snapshot.premiumizeApiKey)
        syncApiKeySecretToRemote(TORBOX_SECRET_TYPE, TORBOX_SECRET_REF, snapshot.torBoxApiKey)
        syncApiKeySecretToRemote(EASY_DEBRID_SECRET_TYPE, EASY_DEBRID_SECRET_REF, snapshot.easyDebridApiKey)
        syncRealDebridSecretsToRemote(snapshot.realDebrid)
        syncTraktSecretsToRemote(snapshot.trakt)
        syncSimklSecretsToRemote(snapshot.simkl)
    }

    private suspend fun syncApiKeySecretToRemote(secretType: String, secretRef: String, rawApiKey: String) {
        val apiKey = rawApiKey.trim()

        if (apiKey.isBlank()) {
            withJwtRefreshRetry {
                postgrest.rpc(
                    "sync_delete_account_secret",
                    buildJsonObject {
                        put("p_secret_type", secretType)
                        put("p_secret_ref", secretRef)
                        put("p_source", "app")
                    }
                )
            }
            return
        }

        withJwtRefreshRetry {
            postgrest.rpc(
                "sync_set_account_secret",
                buildJsonObject {
                    put("p_secret_type", secretType)
                    put("p_secret_ref", secretRef)
                    put("p_secret_payload", Json.encodeToJsonElement(AccountSecretApiKeyPayload.serializer(), AccountSecretApiKeyPayload(apiKey)))
                    put("p_masked_preview", "Stored ••••${apiKey.takeLast(4)}")
                    put("p_status", "configured")
                    put("p_source", "app")
                }
            )
        }
    }

    private suspend fun syncTvdbCredentialSecretToRemote(
        rawTvdbApiKey: String,
        rawTvdbPin: String
    ) {
        val tvdbApiKey = rawTvdbApiKey.trim()
        val tvdbPin = rawTvdbPin.trim()

        if (tvdbApiKey.isBlank()) {
            withJwtRefreshRetry {
                postgrest.rpc(
                    "sync_delete_account_secret",
                    buildJsonObject {
                        put("p_secret_type", TVDB_SECRET_TYPE)
                        put("p_secret_ref", TVDB_SECRET_REF)
                        put("p_source", "app")
                    }
                )
            }
            return
        }

        withJwtRefreshRetry {
            postgrest.rpc(
                "sync_set_account_secret",
                buildJsonObject {
                    put("p_secret_type", TVDB_SECRET_TYPE)
                    put("p_secret_ref", TVDB_SECRET_REF)
                    put(
                        "p_secret_payload",
                        Json.encodeToJsonElement(
                            AccountTvdbCredentialSecretPayload.serializer(),
                            AccountTvdbCredentialSecretPayload(
                                apiKey = tvdbApiKey,
                                pin = tvdbPin.takeIf { it.isNotBlank() }
                            )
                        )
                    )
                    put("p_masked_preview", "Stored ••••${tvdbApiKey.takeLast(4)}")
                    put("p_status", "configured")
                    put("p_source", "app")
                }
            )
        }
    }

    private suspend fun syncRealDebridSecretsToRemote(state: RealDebridSecretPushSnapshot) {
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
            withJwtRefreshRetry {
                postgrest.rpc(
                    "sync_delete_account_secret",
                    buildJsonObject {
                        put("p_secret_type", REAL_DEBRID_ACCESS_SECRET_TYPE)
                        put("p_secret_ref", REAL_DEBRID_SECRET_REF)
                        put("p_source", "app")
                    }
                )
                postgrest.rpc(
                    "sync_delete_account_secret",
                    buildJsonObject {
                        put("p_secret_type", REAL_DEBRID_REFRESH_SECRET_TYPE)
                        put("p_secret_ref", REAL_DEBRID_SECRET_REF)
                        put("p_source", "app")
                    }
                )
            }
            return
        }

        withJwtRefreshRetry {
            postgrest.rpc(
                "sync_set_account_secret",
                buildJsonObject {
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
                }
            )
            postgrest.rpc(
                "sync_set_account_secret",
                buildJsonObject {
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
                }
            )
        }
    }

    private suspend fun syncTraktSecretsToRemote(traktState: TraktSecretPushSnapshot) {
        val accessToken = traktState.accessToken
        val refreshToken = traktState.refreshToken

        if (accessToken.isBlank() || refreshToken.isBlank()) {
            withJwtRefreshRetry {
                postgrest.rpc(
                    "sync_delete_account_secret",
                    buildJsonObject {
                        put("p_secret_type", TRAKT_ACCESS_SECRET_TYPE)
                        put("p_secret_ref", TRAKT_SECRET_REF)
                        put("p_source", "app")
                    }
                )
                postgrest.rpc(
                    "sync_delete_account_secret",
                    buildJsonObject {
                        put("p_secret_type", TRAKT_REFRESH_SECRET_TYPE)
                        put("p_secret_ref", TRAKT_SECRET_REF)
                        put("p_source", "app")
                    }
                )
            }
            return
        }

        withJwtRefreshRetry {
            postgrest.rpc(
                "sync_set_account_secret",
                buildJsonObject {
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
                }
            )
            postgrest.rpc(
                "sync_set_account_secret",
                buildJsonObject {
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
                }
            )
        }
    }

    private suspend fun syncSimklSecretsToRemote(simklState: SimklSecretPushSnapshot) {
        val accessToken = simklState.accessToken

        if (accessToken.isBlank()) {
            withJwtRefreshRetry {
                postgrest.rpc(
                    "sync_delete_account_secret",
                    buildJsonObject {
                        put("p_secret_type", SIMKL_ACCESS_SECRET_TYPE)
                        put("p_secret_ref", SIMKL_SECRET_REF)
                        put("p_source", "app")
                    }
                )
            }
            return
        }

        withJwtRefreshRetry {
            postgrest.rpc(
                "sync_set_account_secret",
                buildJsonObject {
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
                }
            )
        }
    }

    private suspend fun resolveRemoteSecretsForApply(settings: AccountConfigSyncPayload): ResolvedRemoteSecretsForApply {
        // Each helper returns null when the resolve RPC fails transiently (network,
        // JWT, decode). Only overwrite the local API key when we have an authoritative
        // response from the server — otherwise we'd wipe valid local credentials on
        // every flaky upgrade-time sync.
        val tmdbApiKey = resolveApiKeySecretOrNull(TMDB_SECRET_TYPE, TMDB_SECRET_REF)
        val tvdbCredential = resolveTvdbCredentialSecretOrNull()
        val mdbListApiKey = resolveApiKeySecretOrNull(MDBLIST_SECRET_TYPE, MDBLIST_SECRET_REF)
        val omdbApiKey = resolveApiKeySecretOrNull(OMDB_SECRET_TYPE, OMDB_SECRET_REF)
        val genericTranslationKey = resolveApiKeySecretOrNull(TRANSLATION_SECRET_TYPE, TRANSLATION_SECRET_REF)
        val allowLegacyFallback = settings.integrations.subtitleTranslation.provider.equals("GEMINI", ignoreCase = true)
        val legacyGeminiKey = if (genericTranslationKey != null && genericTranslationKey.isBlank() && allowLegacyFallback) {
            resolveApiKeySecretOrNull(GEMINI_SECRET_TYPE, GEMINI_SECRET_REF)
        } else {
            null
        }
        return ResolvedRemoteSecretsForApply(
            tmdbApiKey = tmdbApiKey,
            tvdbCredential = tvdbCredential,
            mdbListApiKey = mdbListApiKey,
            omdbApiKey = omdbApiKey,
            subtitleTranslationApiKey = selectSubtitleTranslationApiKeySecret(
                genericTranslationKey = genericTranslationKey,
                legacyGeminiKey = legacyGeminiKey,
                allowLegacyFallback = allowLegacyFallback
            ),
            wyzieApiKey = resolveApiKeySecretOrNull(WYZIE_SECRET_TYPE, WYZIE_SECRET_REF),
            animeSkipClientId = resolveApiKeySecretOrNull(ANIMESKIP_SECRET_TYPE, ANIMESKIP_SECRET_REF),
            rpdbApiKey = resolveApiKeySecretOrNull(RPDB_SECRET_TYPE, RPDB_SECRET_REF),
            topPostersApiKey = resolveApiKeySecretOrNull(TOP_POSTERS_SECRET_TYPE, TOP_POSTERS_SECRET_REF),
            premiumizeApiKey = resolveApiKeySecretOrNull(PREMIUMIZE_SECRET_TYPE, PREMIUMIZE_SECRET_REF),
            torBoxApiKey = resolveApiKeySecretOrNull(TORBOX_SECRET_TYPE, TORBOX_SECRET_REF),
            easyDebridApiKey = resolveApiKeySecretOrNull(EASY_DEBRID_SECRET_TYPE, EASY_DEBRID_SECRET_REF),
            realDebrid = resolveRemoteRealDebridSecrets(settings.integrations.debrid.realDebrid),
            trakt = resolveRemoteTraktSecrets(settings.integrations.traktAuth),
            simkl = resolveRemoteSimklSecrets(settings.integrations.simklAuth)
        )
    }

    private suspend fun applyResolvedRemoteSecrets(secrets: ResolvedRemoteSecretsForApply) {
        secrets.tmdbApiKey?.let { tmdbSettingsDataStore.setApiKey(it) }
        secrets.tvdbCredential?.let { tvdb ->
            tvdbSettingsDataStore.setCredentials(tvdb.apiKey, tvdb.pin.orEmpty())
        }
        secrets.mdbListApiKey?.let { mdbListSettingsDataStore.setApiKey(it) }
        secrets.omdbApiKey?.let { omdbSettingsDataStore.setApiKey(it) }
        secrets.subtitleTranslationApiKey?.let { subtitleTranslationSettingsDataStore.setApiKey(it) }
        secrets.wyzieApiKey?.let { wyzieSettingsDataStore.setApiKey(it) }
        secrets.animeSkipClientId?.let { animeSkipSettingsDataStore.setClientId(it) }
        secrets.rpdbApiKey?.let { posterRatingsSettingsDataStore.setRpdbApiKey(it) }
        secrets.topPostersApiKey?.let { posterRatingsSettingsDataStore.setTopPostersApiKey(it) }
        secrets.premiumizeApiKey?.let { premiumizeSettingsDataStore.setApiKey(it) }
        secrets.torBoxApiKey?.let { torBoxSettingsDataStore.setApiKey(it) }
        secrets.easyDebridApiKey?.let { easyDebridSettingsDataStore.setApiKey(it) }
        secrets.realDebrid?.let { applyResolvedRemoteRealDebridSecrets(it) }
        secrets.trakt?.let { applyResolvedRemoteTraktSecrets(it) }
        secrets.simkl?.let { applyResolvedRemoteSimklSecrets(it) }
    }

    private suspend fun resolveTvdbCredentialSecretOrNull(): AccountTvdbCredentialSecretPayload? {
        val result = runCatching {
            withJwtRefreshRetry {
                postgrest.rpc(
                    "sync_resolve_account_secret",
                    buildJsonObject {
                        put("p_secret_type", TVDB_SECRET_TYPE)
                        put("p_secret_ref", TVDB_SECRET_REF)
                        put("p_source", "app")
                    }
                ).decodeAs<AccountTvdbCredentialSecretPayload>()
            }
        }
        if (result.isFailure) return null
        val payload = result.getOrNull() ?: return AccountTvdbCredentialSecretPayload()
        return AccountTvdbCredentialSecretPayload(
            apiKey = payload.apiKey.trim(),
            pin = payload.pin?.trim()?.takeIf { it.isNotBlank() }
        )
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
            return ResolvedRemoteTraktSecrets(
                accessPayload = accessPayload,
                refreshPayload = refreshPayload,
                preserveLocalTokens = false,
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

        return ResolvedRemoteSimklSecrets(
            accessPayload = accessResult.getOrNull(),
            remote = remote
        )
    }

    private suspend fun applyResolvedRemoteSimklSecrets(secrets: ResolvedRemoteSimklSecrets) {
        val accessToken = secrets.accessPayload?.accessToken?.trim().orEmpty()
        if (accessToken.isBlank()) {
            val remote = secrets.remote
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

        return ResolvedRemoteRealDebridSecrets(
            accessPayload = accessResult.getOrNull(),
            refreshPayload = refreshResult.getOrNull(),
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

    private suspend fun applyTvdbPublicSyncSettings(settings: TvdbSyncSettings) {
        tvdbSettingsDataStore.setEnabled(true)
        tvdbSettingsDataStore.saveValidationFailure(
            status = runCatching { TvdbValidationStatus.valueOf(settings.validationStatus) }
                .getOrDefault(TvdbValidationStatus.VALID)
                .takeUnless { it == TvdbValidationStatus.NOT_CONFIGURED }
                ?: TvdbValidationStatus.VALID,
            lastFailure = settings.lastFailure.takeIf { settings.validationStatus == TvdbValidationStatus.INVALID.name }.orEmpty()
        )
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, default: T): T {
        return runCatching { enumValueOf<T>(value.trim().uppercase()) }.getOrDefault(default)
    }
}
