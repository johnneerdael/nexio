package com.nexio.tv.sync

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProfileSettingsScopeContractTest {
    private val scopeDoc = File("docs/architecture/profile-settings-scope.md")
    private val accountSettingsSyncService = File("app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt")
    private val startupSyncService = File("app/src/main/java/com/nexio/tv/core/sync/StartupSyncService.kt")
    private val profileWebSyncService = File("app/src/main/java/com/nexio/tv/core/sync/ProfileWebSyncService.kt")
    private val profileBoundary = File("app/src/main/java/com/nexio/tv/core/profile/ProfileBoundary.kt")
    private val profileModeRouter = File("app/src/main/java/com/nexio/tv/core/profile/ProfileModeRouter.kt")
    private val trackingProviderStateService = File("app/src/main/java/com/nexio/tv/data/repository/TrackingProviderStateService.kt")
    private val trackingAuthSession = File("app/src/main/java/com/nexio/tv/data/repository/TrackingAuthSession.kt")
    private val traktAuthService = File("app/src/main/java/com/nexio/tv/data/repository/TraktAuthService.kt")
    private val simklAuthService = File("app/src/main/java/com/nexio/tv/data/repository/SimklAuthService.kt")
    private val continueWatchingSnapshotService = File("app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt")
    private val continueWatchingIdentityResolver = File("app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingIdentityResolver.kt")
    private val nexioNavHost = File("app/src/main/java/com/nexio/tv/ui/navigation/NexioNavHost.kt")
    private val homeCatalogPipeline = File("app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt")
    private val homeContinueWatching = File("app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt")
    private val homeProfileSession = File("app/src/main/java/com/nexio/tv/ui/screens/home/HomeProfileSession.kt")
    private val traktDiscoveryService = File("app/src/main/java/com/nexio/tv/data/repository/TraktDiscoveryService.kt")
    private val simklDiscoveryService = File("app/src/main/java/com/nexio/tv/data/repository/SimklDiscoveryService.kt")
    private val mdbListDiscoveryService = File("app/src/main/java/com/nexio/tv/data/repository/MDBListDiscoveryService.kt")
    private val metadataDiskCacheStore = File("app/src/main/java/com/nexio/tv/data/local/MetadataDiskCacheStore.kt")
    private val artworkImageCacheKeys = File("app/src/main/java/com/nexio/tv/core/image/ArtworkImageCacheKeys.kt")
    private val secondaryProfileSettingsMigration = File("supabase/migrations/20260415000100_enforce_secondary_profile_settings.sql")

    private fun doc(): String {
        assertTrue(
            "profile settings scope document should exist",
            scopeDoc.exists()
        )
        return scopeDoc.readText()
    }

    private fun inventoryRowList(): List<List<String>> {
        val section = doc()
            .substringAfter("## Android Store Inventory")
            .substringBefore("## Classification Rules")
        val storeRowPattern = Regex("^\\|\\s*`([^`]+)`\\s*\\|")

        return section.lines()
            .filter { line -> storeRowPattern.containsMatchIn(line) }
            .map { line -> line.split("|").map { it.trim() } }
    }

    private fun inventoryRows(): Map<String, List<String>> {
        return inventoryRowList().associateBy { cells -> cells[1].removeSurrounding("`") }
    }

    @Test
    fun `ownership document defines two-axis scope classes`() {
        val text = doc()

        listOf(
            "Identity scope",
            "Persistence scope",
            "`account-remote`",
            "`profile-remote`",
            "`profile-local`",
            "`profile-derived-cache`",
            "`shared-language-cache`",
            "`global-device`"
        ).forEach { expected ->
            assertTrue("scope doc should mention $expected", text.contains(expected))
        }
    }

    @Test
    fun `inventory contains each expected store exactly once`() {
        val rowList = inventoryRowList()
        val rows = rowList.associateBy { cells -> cells[1].removeSurrounding("`") }
        val expectedStores = setOf(
            "AddonPreferences",
            "AddonRepositoryManifestCache",
            "AndroidTvRecommendationsDataStore",
            "AnimeSkipSettingsDataStore",
            "AppLocaleResolver",
            "AppOnboardingDataStore",
            "CatalogDiskCacheStore",
            "ContinueWatchingSnapshotStore",
            "DebridBenchmarkStore",
            "DebugSettingsDataStore",
            "EasyDebridSettingsDataStore",
            "HomeCatalogSnapshotStore",
            "LayoutPreferenceDataStore",
            "LibraryPreferences",
            "MDBListDiscoverySnapshotStore",
            "MDBListSettingsDataStore",
            "MetadataDiskCacheStore",
            "OmdbSettingsDataStore",
            "PlayerSettingsDataStore",
            "PosterRatingsSettingsDataStore",
            "PremiumizeSettingsDataStore",
            "ProfileDataStore",
            "ProfileDataStoreFactory",
            "RealDebridAuthDataStore",
            "SearchHistoryDataStore",
            "SimklAuthDataStore",
            "SimklDiscoverySnapshotStore",
            "SimklLibrarySnapshotStore",
            "SimklProgressSyncStateStore",
            "SimklSettingsDataStore",
            "StreamLinkCacheDataStore",
            "SubtitleTranslationSettingsDataStore",
            "SyntheticHomeCatalogStore",
            "TheIntroDbSettingsDataStore",
            "ThemeDataStore",
            "TmdbSettingsDataStore",
            "TorBoxSettingsDataStore",
            "TrailerSettingsDataStore",
            "TraktAuthDataStore",
            "TraktDiscoverySnapshotStore",
            "TraktLibrarySnapshotStore",
            "TraktMutationOutboxStore",
            "TraktSettingsDataStore",
            "TvdbIdentityCacheStore",
            "TvdbSettingsDataStore",
            "TvdbTokenStore",
            "WatchProgressPreferences",
            "UpdatePreferences",
            "profile_cleanup_state"
        )

        assertEquals(
            "store rows should not contain duplicates",
            rowList.map { cells -> cells[1].removeSurrounding("`") }.size,
            rows.keys.size
        )
        assertEquals(expectedStores, rows.keys)
    }

    @Test
    fun `known profile remote stores are classified`() {
        val rows = inventoryRows()

        listOf(
            "ThemeDataStore",
            "LayoutPreferenceDataStore",
            "PlayerSettingsDataStore",
            "TraktSettingsDataStore",
            "SimklSettingsDataStore",
            "TraktAuthDataStore",
            "SimklAuthDataStore"
        ).forEach { store ->
            assertEquals("$store should be profile-remote", "`profile-remote`", rows[store]?.get(2))
        }
    }

    @Test
    fun `known account remote stores are classified`() {
        val rows = inventoryRows()

        listOf(
            "AddonPreferences",
            "TmdbSettingsDataStore",
            "OmdbSettingsDataStore",
            "TheIntroDbSettingsDataStore",
            "AnimeSkipSettingsDataStore",
            "SubtitleTranslationSettingsDataStore",
            "PosterRatingsSettingsDataStore",
            "PremiumizeSettingsDataStore",
            "TorBoxSettingsDataStore",
            "EasyDebridSettingsDataStore",
            "RealDebridAuthDataStore",
            "TvdbSettingsDataStore",
            "TvdbTokenStore"
        ).forEach { store ->
            assertEquals("$store should be account-remote", "`account-remote`", rows[store]?.get(2))
        }
    }

    @Test
    fun `known profile local cache and device stores are classified`() {
        val rows = inventoryRows()

        listOf(
            "AppLocaleResolver",
            "SearchHistoryDataStore",
            "LibraryPreferences",
            "WatchProgressPreferences"
        ).forEach { store ->
            assertEquals("$store should be profile-local", "`profile-local`", rows[store]?.get(2))
        }

        listOf(
            "HomeCatalogSnapshotStore",
            "SyntheticHomeCatalogStore",
            "ContinueWatchingSnapshotStore",
            "TraktLibrarySnapshotStore",
            "TraktDiscoverySnapshotStore",
            "MDBListDiscoverySnapshotStore",
            "SimklLibrarySnapshotStore",
            "SimklDiscoverySnapshotStore",
            "SimklProgressSyncStateStore",
            "TraktMutationOutboxStore",
            "CatalogDiskCacheStore"
        ).forEach { store ->
            assertEquals("$store should be profile-derived-cache", "`profile-derived-cache`", rows[store]?.get(2))
        }

        listOf(
            "MetadataDiskCacheStore"
        ).forEach { store ->
            assertEquals("$store should be shared-language-cache", "`shared-language-cache`", rows[store]?.get(2))
        }

        listOf(
            "AndroidTvRecommendationsDataStore",
            "AppOnboardingDataStore",
            "DebridBenchmarkStore",
            "DebugSettingsDataStore",
            "ProfileDataStore",
            "ProfileDataStoreFactory",
            "AddonRepositoryManifestCache",
            "StreamLinkCacheDataStore",
            "TrailerSettingsDataStore",
            "TvdbIdentityCacheStore",
            "UpdatePreferences",
            "profile_cleanup_state"
        ).forEach { store ->
            assertEquals("$store should be global-device", "`global-device`", rows[store]?.get(2))
        }
    }

    @Test
    fun `profile local and derived cache classes do not have supabase owners`() {
        inventoryRows().values
            .filter {
                cells -> cells[2] == "`profile-local`" ||
                    cells[2] == "`profile-derived-cache`" ||
                    cells[2] == "`shared-language-cache`"
            }
            .forEach { cells ->
                assertEquals("${cells[1]} should have no Supabase owner", "none", cells[5])
            }
    }

    @Test
    fun `inventory rows enforce identity and persistence axes`() {
        inventoryRows().values.forEach { cells ->
            val store = cells[1]
            val ownershipClass = cells[2]
            val identityScope = cells[3]
            val persistenceScope = cells[4]

            when (ownershipClass) {
                "`account-remote`" -> {
                    assertEquals("$store should have account identity scope", "`account`", identityScope)
                    assertEquals("$store should be remote synced", "`remote-synced`", persistenceScope)
                }
                "`profile-remote`" -> {
                    assertEquals("$store should have profile identity scope", "`profile`", identityScope)
                    assertEquals("$store should be remote synced", "`remote-synced`", persistenceScope)
                }
                "`profile-local`" -> {
                    assertEquals("$store should have profile identity scope", "`profile`", identityScope)
                    assertEquals("$store should be local only", "`local-only`", persistenceScope)
                    assertEquals("$store should not have a Supabase owner", "none", cells[5])
                }
                "`profile-derived-cache`" -> {
                    assertEquals("$store should have profile identity scope", "`profile`", identityScope)
                    assertEquals("$store should be derived cache", "`derived-cache`", persistenceScope)
                    assertEquals("$store should not have a Supabase owner", "none", cells[5])
                }
                "`shared-language-cache`" -> {
                    assertEquals("$store should have device identity scope", "`device`", identityScope)
                    assertEquals("$store should be derived cache", "`derived-cache`", persistenceScope)
                    assertEquals("$store should not have a Supabase owner", "none", cells[5])
                }
                "`global-device`" -> {
                    assertEquals("$store should have device identity scope", "`device`", identityScope)
                    assertTrue(
                        "$store should be local-only or derived-cache",
                        persistenceScope == "`local-only`" || persistenceScope == "`derived-cache`"
                    )
                }
                else -> throw AssertionError("$store has unknown ownership class $ownershipClass")
            }
        }
    }

    @Test
    fun `profile settings sync excludes local cache account and device classes`() {
        val text = doc()

        assertTrue(text.contains("`ProfileSettingsSyncService.syncedFeatures` may include only `profile-remote` stores."))
        assertTrue(text.contains("Supabase `sync_pull_profile_settings_blob` and `sync_push_profile_settings_blob` reject profile 1"))
        assertTrue(text.contains("No `profile-local`, `profile-derived-cache`, `shared-language-cache`, or `global-device` value may be serialized to `profile_settings`."))
    }

    @Test
    fun `home session generation ownership is documented`() {
        val text = doc()

        assertTrue(text.contains("`HomeProfileSession` owns Home UI-session generation"))
        assertTrue(text.contains("`ProfileBoundary` owns secondary profile route decisions"))
    }

    @Test
    fun `account snapshot sync only reads and writes primary profile tracking auth`() {
        val source = accountSettingsSyncService.readText()

        val primaryProfileExpr = Regex(
            "(?:profileModeRouter\\.defaultLegacyProfileId\\(\\)|defaultProfileId)"
        )
        fun assertCallsWithPrimary(call: String) {
            val pattern = Regex(Regex.escape(call) + "\\s*\\(\\s*" + primaryProfileExpr.pattern + "\\s*\\)")
            assertTrue(
                "$call must scope to defaultLegacyProfileId — found:\n" +
                    Regex(Regex.escape(call) + "[^\\n]*").findAll(source).joinToString("\n") { it.value },
                pattern.containsMatchIn(source)
            )
        }

        assertTrue(source.contains("profileModeRouter.defaultLegacyProfileId()"))
        assertCallsWithPrimary("traktAuthDataStore.stateForProfile")
        assertCallsWithPrimary("simklAuthDataStore.stateForProfile")
        assertCallsWithPrimary("traktAuthDataStore.clearAuth")
        assertCallsWithPrimary("simklAuthDataStore.clearAuth")
        assertTrue(source.contains("profileId = profileModeRouter.defaultLegacyProfileId()"))
        assertTrue(source.contains("path == \"integrations.traktAuth\""))
        assertTrue(source.contains("path == \"integrations.simklAuth\""))
    }

    @Test
    fun `trakt up next descriptors require active profile trakt auth`() {
        val source = homeCatalogPipeline.readText()

        assertTrue(source.contains("hasTraktUpNextItems = activeProfileTraktAuthenticated && currentState.traktUpNextItems.isNotEmpty()"))
        assertTrue(source.contains("persistedTraktSyntheticGroups = if (providerState.traktAuthenticated) snapshot.traktGroups else emptyList()"))
        assertTrue(source.contains("trackingProviderStateService.currentState()"))
        assertTrue(
            "Trakt Up Next must not be described from stale UI state after profile auth is cleared",
            !source.contains("hasTraktUpNextItems = currentState.traktUpNextItems.isNotEmpty()")
        )
    }

    @Test
    fun `profile switch restores disk backed trakt rows before auth state settles`() {
        val source = homeCatalogPipeline.readText()

        assertTrue(source.contains("val hasTraktDiskState = diskState.hasTraktDiskState()"))
        assertTrue(source.contains("activeProfileTraktAuthenticated = diskState.traktAuthenticated || hasTraktDiskState"))
        assertTrue(source.contains("diskState.traktSnapshot?.let { snapshot ->"))
        assertTrue(
            "Disk-backed Trakt snapshots must not be dropped just because tracking auth state is still settling",
            !source.contains("diskState.traktSnapshot?.takeIf { diskState.traktAuthenticated }")
        )
        assertTrue(source.contains("persistedTraktSyntheticGroups = if (diskState.traktAuthenticated || hasTraktDiskState) snapshot.traktGroups else emptyList()"))
    }

    @Test
    fun `synthetic trakt and simkl rows include enabled catalogs missing from saved order`() {
        val source = homeCatalogPipeline.readText()

        assertTrue(source.contains("val orderedBuiltInKeys = prefs.catalogOrder.filter { it in builtInRows }"))
        assertTrue(source.contains("val remainingBuiltInKeys = builtInRows.keys.filterNot { it in orderedBuiltInKeys }"))
        assertTrue(source.contains("(orderedBuiltInKeys + remainingBuiltInKeys).mapNotNull"))
    }

    @Test
    fun `profile web auth sync does not own primary profile integrations`() {
        val source = profileWebSyncService.readText()

        assertTrue(source.contains("profileModeRouter.routeFor(profileIndex)"))
        assertTrue(source.contains("ProfileModeRoute.DefaultLegacyRoute"))
        assertTrue(source.contains("profileBoundary.authRoute"))
        assertTrue(source.contains("account sync owns legacy integrations"))
    }

    @Test
    fun `account sync entrypoints require live full account session`() {
        val accountSource = accountSettingsSyncService.readText()
        val startupSource = startupSyncService.readText()
        val addonSource = File("app/src/main/java/com/nexio/tv/core/sync/AddonSyncService.kt").readText()
        val addonRepositorySource = File("app/src/main/java/com/nexio/tv/data/repository/AddonRepositoryImpl.kt").readText()
        val syncRepositorySource = File("app/src/main/java/com/nexio/tv/data/repository/SyncRepositoryImpl.kt").readText()

        assertTrue(accountSource.contains("hasLiveFullAccountSyncSession"))
        assertTrue(accountSource.contains("liveFullAccountSessionUserId"))
        assertTrue(startupSource.contains("combine(authManager.authState, authManager.sessionUserId)"))
        assertTrue(startupSource.contains("liveFullAccountSessionUserId(authState, sessionUserId)"))
        assertTrue(addonSource.contains("hasLiveFullAccountSyncSession"))
        assertTrue(addonRepositorySource.contains("hasLiveFullAccountSyncSession(authManager.authState.value, authManager.currentSessionUserId)"))
        assertTrue(syncRepositorySource.contains("No live full account session"))
    }

    @Test
    fun `profile delete remote sync is gated on live full account session`() {
        val viewModelSource = File("app/src/main/java/com/nexio/tv/ui/screens/settings/SettingsViewModel.kt").readText()
        val screenSource = File("app/src/main/java/com/nexio/tv/ui/screens/settings/SettingsScreen.kt").readText()
        val profileManagerSource = File("app/src/main/java/com/nexio/tv/core/profile/ProfileManager.kt").readText()

        assertTrue(viewModelSource.contains("hasLiveFullAccountSyncSession("))
        assertTrue(viewModelSource.contains("val syncRemoteDelete = hasLiveFullAccountSyncSession("))
        assertTrue(viewModelSource.contains("syncRemoteDelete = syncRemoteDelete"))
        assertTrue(viewModelSource.contains("authManager.authState.value"))
        assertTrue(viewModelSource.contains("authManager.currentSessionUserId"))
        assertTrue(screenSource.contains("if (hasLiveFullAccountSession)"))
        assertTrue(profileManagerSource.contains("suspend fun deleteProfile(id: Int, syncRemoteDelete: Boolean = false): Boolean"))
        assertTrue(profileManagerSource.contains("deleteProfileDataAsync(id, syncRemoteDelete)"))
        assertTrue(profileManagerSource.contains("if (syncRemoteDelete) {"))
        assertTrue(profileManagerSource.contains("deleteProfileRemote(profileId)"))
    }

    @Test
    fun `profile boundary owns secondary route decisions and rejects default`() {
        val boundarySource = profileBoundary.readText()
        val routerSource = profileModeRouter.readText()

        assertTrue(routerSource.contains("DEFAULT_LEGACY_PROFILE_ID -> ProfileModeRoute.DefaultLegacyRoute"))
        assertTrue(routerSource.contains("in 2..4 -> ProfileModeRoute.SecondaryProfileRoute(profileId)"))
        assertTrue(boundarySource.contains("ProfileBoundary only accepts secondary profiles 2-4"))
        assertTrue(boundarySource.contains("ProfileCacheScope.SharedArtwork"))
        assertTrue(boundarySource.contains("ProfileCacheScope.SharedLanguageMetadata"))
        assertTrue(boundarySource.contains("ProfileCacheScope.ProfileSnapshot"))
    }

    @Test
    fun `startup sync routes default legacy away from secondary profile sync`() {
        val source = startupSyncService.readText()

        assertTrue(source.contains("profileModeRouter.routeFor(activeId)"))
        assertTrue(source.contains("ProfileModeRoute.DefaultLegacyRoute"))
        assertTrue(source.contains("Skipping secondary profile startup sync for default legacy profile"))
        assertTrue(source.contains("profileSettingsSyncService.pullBlobForProfile(route.profileId)"))
        assertTrue(source.contains("profileWebSyncService.syncActiveProfile(route.profileId)"))
    }

    @Test
    fun `tracking provider state reads auth through profile routes`() {
        val source = trackingProviderStateService.readText()

        assertTrue(source.contains("profileManager.activeProfileId.flatMapLatest(::authStateForProfile)"))
        assertTrue(source.contains("ProfileModeRoute.DefaultLegacyRoute -> authStateForRoutedProfile(profileModeRouter.defaultLegacyProfileId())"))
        assertTrue(source.contains("profileBoundary.authRoute(route, TrackingProvider.TRAKT).profileId"))
        assertTrue(source.contains("profileBoundary.authRoute(route, TrackingProvider.SIMKL).profileId"))
    }

    @Test
    fun `tracking provider state exposes unauthenticated runtime state explicitly`() {
        val source = trackingProviderStateService.readText()

        assertTrue(source.contains("val hasAuthenticatedProvider: Boolean"))
        assertTrue(source.contains("val canReadEffectiveProvider: Boolean"))
        assertTrue(source.contains("effectiveProvider = if (authState.hasAnyAuthenticatedProvider)"))
        assertTrue(!source.contains("else -> settings.trackingProvider"))
    }

    @Test
    fun `shared metadata and artwork cache keys stay profile independent`() {
        val metadataSource = metadataDiskCacheStore.readText()
        val artworkSource = artworkImageCacheKeys.readText()

        assertTrue(metadataSource.contains("return \"${'$'}META_PREFIX${'$'}itemKey::${'$'}languageTag::${'$'}providerToken\""))
        assertTrue(metadataSource.contains("return \"${'$'}TMDB_PREFIX${'$'}tmdbKey::${'$'}languageTag::${'$'}providerToken\""))
        assertTrue(metadataSource.contains("return \"${'$'}TVDB_PREFIX${'$'}seriesId::${'$'}{recordKind.trim().lowercase()}::${'$'}languageTag::${'$'}providerToken\""))
        assertTrue(artworkSource.contains("\"artwork:${'$'}provider:${'$'}type:${'$'}itemId:imageLang:en:policy:1\""))
        assertTrue(!artworkSource.contains("languageTag"))
        assertTrue(!artworkSource.contains("profileId"))
    }

    @Test
    fun `home refreshes are generation gated across profile switches`() {
        val homeViewModelSource = File("app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt").readText()
        val homePipelineSource = homeCatalogPipeline.readText()
        val homeContinueWatchingSource = homeContinueWatching.readText()

        assertTrue(homeViewModelSource.contains("internal var homeProfileGeneration"))
        assertTrue(homeProfileSession.readText().contains("data class DefaultLegacy"))
        assertTrue(homeProfileSession.readText().contains("data class Secondary"))
        assertTrue(homeViewModelSource.contains("activeHomeProfileSession"))
        assertTrue(homeViewModelSource.contains("homeProfileSessionCoordinator.start"))
        assertTrue(homeViewModelSource.contains("val activeHomeProfileSession: StateFlow<HomeProfileSession>"))
        assertTrue(homeViewModelSource.contains("activeHomeProfileSessionSnapshot: HomeProfileSession = activeHomeProfileSession.value"))
        assertTrue(homeViewModelSource.contains("activeHomeProfileSessionSnapshot = session"))
        val assignIndex = homeViewModelSource.indexOf("activeHomeProfileSessionSnapshot = session")
        val resetIndex = homeViewModelSource.indexOf("resetProfileScopedHomeState(\"home_session:")
        assertTrue(assignIndex >= 0)
        assertTrue(resetIndex >= 0)
        assertTrue(assignIndex < resetIndex)
        assertTrue(homeViewModelSource.contains("val previousSession = activeHomeProfileSessionSnapshot"))
        assertTrue(homeViewModelSource.contains("shouldResetProfileScopedHomeState("))
        assertTrue(homeViewModelSource.contains("previousSession.profileSessionKey != nextSession.profileSessionKey"))
        assertTrue(!homeViewModelSource.contains(".distinctUntilChangedBy { it.profileSessionKey }"))
        assertTrue(!homeViewModelSource.contains(".distinctUntilChangedBy { it.sessionId }"))
        assertTrue(homeViewModelSource.contains("collectLatest { session ->"))
        assertTrue(!homeViewModelSource.contains("profileManager.profileSwitched.collectLatest { profileId ->"))
        assertTrue(homeViewModelSource.contains("advanceHomeProfileGeneration()"))
        assertTrue(homeViewModelSource.contains("isCurrentHomeSession(session: HomeProfileSession)"))
        assertTrue(homeViewModelSource.contains("activeHomeProfileSession.value.sessionId == session.sessionId"))
        assertTrue(homeViewModelSource.contains("isCurrentHomeProfileGeneration(session.generation)"))
        assertTrue(homeViewModelSource.contains("val capturedGeneration = homeProfileGeneration"))
        assertTrue(homePipelineSource.contains("deferredStartupRefreshJob?.cancel()"))
        assertTrue(homePipelineSource.contains("homeReadiness = HomeInitialReadiness.started("))
        assertTrue(homePipelineSource.contains("sessionId = activeHomeProfileSessionSnapshot.sessionId"))
        assertTrue(homePipelineSource.contains("profileId = activeHomeProfileSessionSnapshot.profileId"))
        assertTrue(homePipelineSource.contains("loadActiveProfileDiskBackedHomeState("))
        assertTrue(homePipelineSource.contains("expectedGeneration: Long? = null"))
        assertTrue(homePipelineSource.contains("Skipping stale disk-backed home state"))
        assertTrue(homePipelineSource.contains("expectedProfileSession: ActiveProfileSession"))
        assertTrue(homePipelineSource.contains("Skipping stale serialized home refresh"))
        assertTrue(!homeContinueWatchingSource.contains("val capturedGeneration = homeProfileGeneration"))
        assertTrue(homeContinueWatchingSource.contains(".distinctUntilChangedBy { it.profileSessionKey }"))
        assertTrue(homeContinueWatchingSource.contains("isCurrentHomeSession(session)"))
        assertTrue(homeContinueWatchingSource.contains("continueWatchingSnapshotVersion"))
        assertTrue(homeContinueWatchingSource.contains("Skipping stale continue watching publish"))
        assertTrue(homeContinueWatchingSource.contains("Skipping stale continue watching enrichment"))
    }

    @Test
    fun `tracking auth services save and clear through routed profile ids`() {
        val traktSource = traktAuthService.readText()
        val simklSource = simklAuthService.readText()

        assertTrue(traktSource.contains("profileModeRouter.routeFor(profileManager.activeProfileId.value)"))
        assertTrue(traktSource.contains("ProfileModeRoute.DefaultLegacyRoute -> profileModeRouter.defaultLegacyProfileId()"))
        assertTrue(traktSource.contains("profileBoundary.authRoute(route, TrackingProvider.TRAKT).profileId"))
        assertTrue(traktSource.contains("ProfileModeRoute.InvalidProfileRoute -> error(\"Invalid active profile id ${'$'}{route.profileId}\")"))
        assertTrue(traktSource.contains("traktAuthDataStore.clearAuth(profileId)"))
        assertTrue(traktSource.contains("traktAuthDataStore.saveToken(tokenBody, profileId = profileId)"))
        assertTrue(simklSource.contains("profileModeRouter.routeFor(profileManager.activeProfileId.value)"))
        assertTrue(simklSource.contains("ProfileModeRoute.DefaultLegacyRoute -> profileModeRouter.defaultLegacyProfileId()"))
        assertTrue(simklSource.contains("profileBoundary.authRoute(route, TrackingProvider.SIMKL).profileId"))
        assertTrue(simklSource.contains("ProfileModeRoute.InvalidProfileRoute -> error(\"Invalid active profile id ${'$'}{route.profileId}\")"))
        assertTrue(simklSource.contains("simklAuthDataStore.clearAuth(currentAuthSession().profileId)"))
        assertTrue(simklSource.contains("simklAuthDataStore.saveAccessToken(body.accessToken, profileId = profileId"))
    }

    @Test
    fun `tracking auth services capture routed session once per operation`() {
        val sessionSource = trackingAuthSession.readText()
        val traktSource = traktAuthService.readText()
        val simklSource = simklAuthService.readText()

        assertTrue(sessionSource.contains("data class TrackingAuthSession"))
        assertTrue(sessionSource.contains("val profileId: Int"))
        assertTrue(traktSource.contains("fun currentAuthSession(): TrackingAuthSession"))
        assertTrue(traktSource.contains("getCurrentAuthState(session: TrackingAuthSession)"))
        assertTrue(traktSource.contains("fetchUserSettings(session: TrackingAuthSession)"))
        assertTrue(traktSource.contains("executeAuthOwnerRequest("))
        assertTrue(traktSource.contains("session: TrackingAuthSession"))
        assertTrue(simklSource.contains("fun currentAuthSession(): TrackingAuthSession"))
        assertTrue(simklSource.contains("getCurrentAuthState(session: TrackingAuthSession)"))
        assertTrue(simklSource.contains("fetchUserSettings(session: TrackingAuthSession)"))
        assertTrue(simklSource.contains("executeAuthOwnerRequest("))
        assertTrue(simklSource.contains("session: TrackingAuthSession"))
    }

    @Test
    fun `tracking runtime session exists and carries profile owner`() {
        val sessionFile = File("app/src/main/java/com/nexio/tv/data/repository/TrackingRuntimeSession.kt")
        val authSessionSource = trackingAuthSession.readText()
        val runtimeSessionSource = sessionFile.readText()

        assertTrue(authSessionSource.contains("fun toRuntimeSession"))
        assertTrue(runtimeSessionSource.contains("data class TrackingRuntimeSession"))
        assertTrue(runtimeSessionSource.contains("val provider: TrackingProvider"))
        assertTrue(runtimeSessionSource.contains("val profileId: Int"))
        assertTrue(runtimeSessionSource.contains("val generation: Long"))
    }

    @Test
    fun `supabase profile settings rpc rejects default profile route`() {
        val migrationSource = secondaryProfileSettingsMigration.readText()

        assertTrue(migrationSource.contains("sync_pull_profile_settings_blob"))
        assertTrue(migrationSource.contains("sync_push_profile_settings_blob"))
        assertTrue(migrationSource.contains("p_profile_id < 2 OR p_profile_id > 4"))
        assertTrue(migrationSource.contains("profile_id must be between 2 and 4 for profile settings"))
    }

    @Test
    fun `discovery services keep profile scoped in memory state`() {
        val traktSource = traktDiscoveryService.readText()
        val simklSource = simklDiscoveryService.readText()
        val mdbSource = mdbListDiscoveryService.readText()
        val homePipelineSource = homeCatalogPipeline.readText()

        assertTrue(traktSource.contains("profileSnapshots"))
        assertTrue(traktSource.contains("snapshotForProfile(profileId)"))
        assertTrue(traktSource.contains("snapshotStore.write(snapshotToPersist, profileId = profileId)"))
        assertTrue(simklSource.contains("profileSnapshots"))
        assertTrue(simklSource.contains("snapshotForProfile(profileId)"))
        assertTrue(simklSource.contains("snapshotStore.write(snapshotToPersist, profileId = profileId)"))
        assertTrue(mdbSource.contains("profileSnapshots"))
        assertTrue(mdbSource.contains("snapshotForProfile(profileId)"))
        assertTrue(mdbSource.contains("snapshotStore.write(snapshotState, profileId = profileId)"))
        assertTrue(homePipelineSource.contains("val capturedGeneration = homeProfileGeneration"))
        assertTrue(homePipelineSource.contains("Skipping stale discovery snapshot"))
    }

    @Test
    fun `home discovery observers preserve disk snapshots during profile switch empty emissions`() {
        val homeSource = File("app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt").readText()
        val source = homeCatalogPipeline.readText()

        assertTrue(source.contains("hydratedSnapshot.updatedAtMs <= 0L && shouldSuppressProfileSwitchRefresh(\"trakt_discovery\")"))
        assertTrue(source.contains("Skipping empty Trakt discovery emission during profile switch"))
        val traktSuppressIndex = source.indexOf("hydratedSnapshot.updatedAtMs <= 0L && shouldSuppressProfileSwitchRefresh(\"trakt_discovery\")")
        val traktClearIndex = source.indexOf("clearTraktHomeState(\"observe_trakt_discovery_unauthenticated\")")
        assertTrue(
            "Empty Trakt emissions during profile switch must be skipped before unauthenticated clearing can run",
            traktSuppressIndex >= 0 && traktClearIndex > traktSuppressIndex
        )
        assertTrue(source.contains("snapshot.updatedAtMs <= 0L && shouldSuppressProfileSwitchRefresh(\"simkl_discovery\")"))
        assertTrue(source.contains("Skipping empty Simkl discovery emission during profile switch"))
        assertTrue(source.contains("hydratedSnapshot.updatedAtMs <= 0L && shouldSuppressProfileSwitchRefresh(\"mdblist_discovery\")"))
        assertTrue(source.contains("Skipping empty MDBList discovery emission during profile switch"))
        assertTrue(source.contains("loadActiveProfileDiskBackedHomeState("))
        assertTrue(homeSource.contains("profileSwitchDiskHydrationActive"))
        assertTrue(homeSource.contains("shouldSuppressProfileSwitchRefresh(reason: String)"))
    }

    @Test
    fun `profile switch reloads disk cached addon catalogs after disk backed hydration`() {
        val homeSource = File("app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt").readText()
        val diskHydrationIndex = homeSource.indexOf("loadActiveProfileDiskBackedHomeState(")
        val addonReloadIndex = homeSource.indexOf(
            "reloadDiskCachedAddonCatalogsForActiveProfileSwitch(",
            startIndex = diskHydrationIndex
        )

        assertTrue(diskHydrationIndex >= 0)
        assertTrue(
            "Profile switches clear catalogsMap and catalogOrder, so they must reload cached addon catalogs from the already observed addons list instead of waiting for installed addons to emit again.",
            addonReloadIndex > diskHydrationIndex
        )
        assertTrue(homeCatalogPipeline.readText().contains("reloadDiskCachedAddonCatalogsForActiveProfileSwitchPipeline"))
    }

    @Test
    fun `profile switch uses disk-only catalog reload when profile disk state exists`() {
        val homeSource = File("app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt").readText()
        val pipelineSource = homeCatalogPipeline.readText()

        assertTrue(homeSource.contains("val hasDiskCacheState = loadActiveProfileDiskBackedHomeState("))
        assertTrue(homeSource.contains("reloadDiskCachedAddonCatalogsForActiveProfileSwitch(allowNetworkRefresh = !hasDiskCacheState)"))
        assertTrue(pipelineSource.contains("allowNetworkRefresh: Boolean"))
        assertTrue(pipelineSource.contains("loadAllCatalogsPipeline(addons, allowNetworkRefresh = allowNetworkRefresh)"))
        assertTrue(pipelineSource.contains("allowNetworkRefresh = allowNetworkRefresh"))
    }

    @Test
    fun `profile switch discovery observers do not auto refresh when disk snapshots exist`() {
        listOf(
            "TraktDiscoveryService" to traktDiscoveryService.readText(),
            "SimklDiscoveryService" to simklDiscoveryService.readText(),
            "MDBListDiscoveryService" to mdbListDiscoveryService.readText()
        ).forEach { (serviceName, source) ->
            assertTrue("$serviceName should track whether a persisted profile snapshot was restored", source.contains("var hadPersistedSnapshot = false"))
            assertTrue("$serviceName should mark restored snapshots before auto refresh decisions", source.contains("hadPersistedSnapshot = true"))
            assertTrue("$serviceName should only auto refresh when no profile disk snapshot exists", source.contains("if (autoRefreshOnStart && !hadPersistedSnapshot)"))
        }
    }

    @Test
    fun `profile switch suppresses direct provider ensureFresh calls from home observers`() {
        val source = homeCatalogPipeline.readText()

        assertTrue(source.contains("!shouldSuppressProfileSwitchRefresh(\"trakt_pref_change\")"))
        assertTrue(source.contains("!shouldSuppressProfileSwitchRefresh(\"simkl_pref_change\")"))
        assertTrue(source.contains("!shouldSuppressProfileSwitchRefresh(\"mdblist_settings_change\")"))
        assertTrue(source.contains("!shouldSuppressProfileSwitchRefresh(\"mdblist_pref_change\")"))
    }

    @Test
    fun `profile switch disk snapshot mode durably blocks observer refresh reasons`() {
        val homeSource = File("app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt").readText()

        assertTrue(homeSource.contains("internal var profileSwitchDiskSnapshotActive: Boolean = false"))
        assertTrue(homeSource.contains("internal var profileSwitchDiskSnapshotGeneration: Long = 0L"))
        assertTrue(homeSource.contains("activateProfileSwitchDiskSnapshotMode(session.generation)"))
        assertTrue(homeSource.contains("clearProfileSwitchDiskSnapshotMode(\"profile_switch_no_disk_state\")"))
        assertTrue(homeSource.contains("shouldBlockProfileSwitchDiskSnapshotRefresh(reason: String)"))
        assertTrue(homeSource.contains("if (shouldBlockProfileSwitchDiskSnapshotRefresh(reason)) return"))

        listOf(
            "trakt_discovery",
            "trakt_pref_change",
            "simkl_discovery",
            "simkl_pref_change",
            "mdblist_discovery",
            "mdblist_pref_change",
            "mdblist_settings_change",
            "mdblist_settings_disabled",
            "window_closed",
            "observe_disabled_home_catalogs",
            "observe_installed_addons"
        ).forEach { blockedReason ->
            assertTrue("disk snapshot mode should list blocked observer reason $blockedReason", homeSource.contains("\"$blockedReason\""))
        }
    }

    @Test
    fun `serialized refresh cannot bypass profile switch disk snapshot mode`() {
        val homeSource = File("app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt").readText()
        val source = homeCatalogPipeline.readText()
        val functionStart = source.indexOf("runSerializedPostStartupRefreshPipeline(")
        val guardIndex = source.indexOf("if (shouldBlockProfileSwitchDiskSnapshotRefresh(reason)) return", startIndex = functionStart)
        val traktRefreshIndex = source.indexOf("traktDiscoveryService.ensureFresh(force = false)", startIndex = functionStart)
        val simklRefreshIndex = source.indexOf("simklDiscoveryService.ensureFresh(force = false)", startIndex = functionStart)
        val mdbRefreshIndex = source.indexOf("mdbListDiscoveryService.ensureFresh(force = false)", startIndex = functionStart)
        val addonRefreshIndex = source.indexOf("homeCatalogRefreshCoordinator.refreshSerially(", startIndex = functionStart)

        assertTrue(homeSource.contains("expectedProfileSession = capturedProfileSession"))
        assertTrue(homeSource.contains("runSerializedPostStartupRefreshPipeline(expectedGeneration, expectedProfileSession, reason)"))
        assertTrue(source.contains("expectedProfileSession: ActiveProfileSession"))
        assertTrue("serialized refresh function should exist", functionStart >= 0)
        assertTrue("disk snapshot guard should exist in serialized refresh", guardIndex > functionStart)
        assertTrue("Trakt network refresh should be after disk snapshot guard", traktRefreshIndex > guardIndex)
        assertTrue("SIMKL network refresh should be after disk snapshot guard", simklRefreshIndex > guardIndex)
        assertTrue("MDBList network refresh should be after disk snapshot guard", mdbRefreshIndex > guardIndex)
        assertTrue("addon network refresh should be after disk snapshot guard", addonRefreshIndex > guardIndex)
    }

    @Test
    fun `disk backed profile switch marks restored discovery sources observed for immediate rows`() {
        val source = homeCatalogPipeline.readText()

        assertTrue(source.contains("traktDiscoveryObserved = true"))
        assertTrue(source.contains("simklDiscoveryObserved = true"))
        assertTrue(source.contains("mdbListDiscoveryObserved = true"))
        assertTrue(source.contains("scheduleUpdateCatalogRows()"))
        assertTrue(source.contains("hasDiskCacheState"))
    }

    @Test
    fun `trakt progress runtime state is profile keyed`() {
        val source = File("app/src/main/java/com/nexio/tv/data/repository/TraktProgressService.kt").readText()

        assertTrue(source.contains("class TraktProgressRuntimeRegistry"))
        assertTrue(
            source.contains("private val states = mutableMapOf<Int, TraktProgressRuntimeState>()") ||
                source.contains("private val states = java.util.concurrent.ConcurrentHashMap<Int, TraktProgressRuntimeState>()")
        )
        assertTrue(source.contains("fun stateFor(session: TrackingRuntimeSession): TraktProgressRuntimeState"))
        assertTrue(source.contains("fun clearProfile(profileId: Int)"))
        assertTrue(source.contains("private fun runtimeState(): TraktProgressRuntimeState"))
        assertTrue(!source.contains("private val remoteProgress = MutableStateFlow<List<WatchProgress>>(emptyList())"))
        assertTrue(!source.contains("private val myShowsNextUp = MutableStateFlow<List<NextUpEntry>>(emptyList())"))
    }

    @Test
    fun `continue watching snapshots carry and enforce profile owner`() {
        val serviceSource = continueWatchingSnapshotService.readText()
        val homeSource = homeContinueWatching.readText()

        assertTrue(serviceSource.contains("val profileId: Int = 1"))
        assertTrue(serviceSource.contains("ProfileOwnedContinueWatchingSnapshot"))
        assertTrue(serviceSource.contains("rawSnapshotState.value = ProfileOwnedContinueWatchingSnapshot(profileId = profileId)"))
        // F-G-01 path B + Task 3: profile ownership is enforced at the typed API boundary,
        // and Home restarts the collector from activeHomeProfileSession session identity.
        assertTrue(homeSource.contains("continueWatchingProfileScopedEmissions("))
        assertTrue(homeSource.contains(".flatMapLatest { session ->"))
        assertTrue(homeSource.contains("observeProfileSnapshot(session.profileId)"))
        assertTrue(!homeSource.contains("observeProfileSnapshot(activeHomeProfileSession.profileId)"))
    }

    @Test
    fun `continue watching p0 shared identity playback wiring exists`() {
        val serviceSource = continueWatchingSnapshotService.readText()
        val identityResolverSource = continueWatchingIdentityResolver.readText()
        val homeSource = homeContinueWatching.readText()
        val navSource = nexioNavHost.readText()

        assertTrue(
            "ContinueWatchingSnapshotService should build raw snapshots through a suspend path",
            serviceSource.contains("private suspend fun buildRawSnapshot(")
        )
        assertTrue(
            "ContinueWatchingSnapshotService should expose a suspend raw snapshot test helper",
            serviceSource.contains("internal suspend fun buildRawSnapshotForTest(")
        )
        assertTrue(
            "ContinueWatchingSnapshotService must not use the removed runBlockingSafelyForSnapshot path",
            !serviceSource.contains("runBlockingSafelyForSnapshot")
        )
        assertTrue(
            "Canonical continue watching rows should be deduped through ContinueWatchingMerger.merge",
            serviceSource.contains("val records = ContinueWatchingMerger.merge(")
        )
        assertTrue(
            "Continue Watching identity resolution should request identity-only metadata",
            identityResolverSource.contains("depth = MetadataDepth.IDENTITY")
        )
        assertTrue(
            "Home should build the row through buildContinueWatchingItemsForSnapshot",
            homeSource.contains("buildContinueWatchingItemsForSnapshot(snapshot, nowMs)")
        )
        val buildItemsStart = homeSource.indexOf("internal fun buildContinueWatchingItemsForSnapshot(")
        val emptyRecordsBranch = homeSource.indexOf("if (snapshot.records.isEmpty())", startIndex = buildItemsStart)
        val rawFallbackReturn = homeSource.indexOf(
            "return buildRawContinueWatchingItemsForSnapshot(snapshot, nowMs)",
            startIndex = emptyRecordsBranch
        )
        val canonicalRecordsTimeline = homeSource.indexOf("resumeItems = snapshot.records", startIndex = rawFallbackReturn)
        assertTrue("Home should define buildContinueWatchingItemsForSnapshot", buildItemsStart >= 0)
        assertTrue("Home should branch on snapshot.records before rendering", emptyRecordsBranch > buildItemsStart)
        assertTrue("Home should use raw fallback only when snapshot.records is empty", rawFallbackReturn > emptyRecordsBranch)
        assertTrue(
            "Home should render canonical Continue Watching rows from snapshot.records when records are present",
            canonicalRecordsTimeline > rawFallbackReturn
        )
        val inProgressRouteStart = navSource.indexOf("is ContinueWatchingItem.InProgress -> Screen.Stream.createRoute(")
        val resumeVideoIdArg = navSource.indexOf("videoId = item.progress.videoId", startIndex = inProgressRouteStart)
        val streamVideoIdArg = navSource.indexOf("streamVideoId = item.streamFetchVideoId", startIndex = inProgressRouteStart)
        val nextUpRouteStart = navSource.indexOf("is ContinueWatchingItem.NextUp -> Screen.Stream.createRoute(")
        assertTrue("CW in-progress playback route should exist", inProgressRouteStart >= 0)
        assertTrue("CW in-progress playback should preserve the resume videoId", resumeVideoIdArg > inProgressRouteStart)
        assertTrue("CW in-progress playback should pass the stream fetch id separately", streamVideoIdArg > resumeVideoIdArg)
        assertTrue(
            "CW in-progress playback stream fetch id should be passed before the NextUp route",
            nextUpRouteStart < 0 || streamVideoIdArg < nextUpRouteStart
        )
    }

    @Test
    fun `modern home presentation is built by view model and consumed by content`() {
        val stateSource = File("app/src/main/java/com/nexio/tv/ui/screens/home/HomeUiState.kt").readText()
        val viewModelSource = File("app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt").readText()
        val pipelineSource = File("app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt").readText()
        val contentSource = File("app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeContent.kt").readText()

        assertTrue(stateSource.contains("val modernHomePresentation: ModernHomePresentationState = ModernHomePresentationState()"))
        assertTrue(viewModelSource.contains("internal val modernCarouselRowBuildCache = ModernCarouselRowBuildCache()"))
        assertTrue(pipelineSource.contains("buildModernHomePresentation("))
        assertTrue(pipelineSource.contains("modernCarouselRowBuildCache"))
        assertTrue(contentSource.contains("val presentation = contentState.modernHomePresentation"))
        assertTrue(!contentSource.contains("val rowBuildCache = remember { ModernCarouselRowBuildCache() }"))
        assertTrue(!contentSource.contains("buildCatalogItem("))
    }

    @Test
    fun `initial continue watching resolution is owned by active home profile`() {
        val homeStateSource = File("app/src/main/java/com/nexio/tv/ui/screens/home/HomeUiState.kt").readText()
        val homeContinueWatchingSource = File("app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt").readText()
        val homeScreenSource = File("app/src/main/java/com/nexio/tv/ui/screens/home/HomeScreen.kt").readText()
        val homeViewModelSource = File("app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt").readText()

        assertTrue(homeStateSource.contains("val homeReadiness: HomeInitialReadiness = HomeInitialReadiness.started("))
        assertTrue(homeStateSource.contains("val initialContinueWatchingResolved: Boolean = false"))
        // F-G-01 path B + Task 3: profile ownership is enforced at the typed API boundary,
        // and Home drives Continue Watching from the active home session flow.
        assertTrue(homeContinueWatchingSource.contains("continueWatchingProfileScopedEmissions("))
        assertTrue(homeContinueWatchingSource.contains(".distinctUntilChangedBy { it.profileSessionKey }"))
        assertTrue(homeContinueWatchingSource.contains("observeProfileSnapshot(session.profileId)"))
        assertTrue(!homeContinueWatchingSource.contains("observeProfileSnapshot(activeHomeProfileSession.profileId)"))
        assertTrue(homeContinueWatchingSource.contains("HomeInitialReadiness"))
        assertTrue(homeContinueWatchingSource.contains("markContinueWatchingGateResolved("))
        assertTrue(homeContinueWatchingSource.contains("initialContinueWatchingResolved = true"))
        assertTrue(homeViewModelSource.contains("homeReadiness = HomeInitialReadiness.started("))
        assertTrue(homeViewModelSource.contains("initialContinueWatchingResolved = false"))
        assertTrue(homeScreenSource.contains("shouldShowFullHomeLoadingGate(uiState, displayCatalogRows, startupContentGateTimedOut)"))
        assertTrue(!homeScreenSource.contains("uiState.initialContinueWatchingResolved"))
    }

    @Test
    fun `modern vertical focus does not scan visible row pixels`() {
        val contentSource = File("app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeContent.kt").readText()
        val rowsSource = File("app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeRows.kt").readText()
        val combinedSource = contentSource + "\n" + rowsSource

        assertTrue(!combinedSource.contains("layoutInfo.visibleItemsInfo.find"))
        assertTrue(!combinedSource.contains("minByOrNull"))
        assertTrue(!combinedSource.contains("abs(targetCenter - sourceCenter"))
    }

    @Test
    fun `tracking scrobble service requires provider specific auth`() {
        val source = File("app/src/main/java/com/nexio/tv/data/repository/TrackingScrobbleService.kt").readText()

        assertTrue(source.contains("if (!providerState.traktAuthenticated) return"))
        assertTrue(source.contains("if (!providerState.simklAuthenticated) return"))
        assertTrue(source.contains("if (!providerState.traktAuthenticated) return false"))
        assertTrue(source.contains("if (!providerState.simklAuthenticated) return false"))
    }

    @Test
    fun `trakt mutation envelopes carry origin profile`() {
        val modelSource = File("app/src/main/java/com/nexio/tv/data/trakt/outbox/TraktMutationOutboxModels.kt").readText()
        val storeSource = File("app/src/main/java/com/nexio/tv/data/trakt/outbox/TraktMutationOutboxStore.kt").readText()
        val policySource = File("app/src/main/java/com/nexio/tv/data/trakt/outbox/TraktMutationOutboxPolicy.kt").readText()

        assertTrue(modelSource.contains("val profileId: Int = 1"))
        assertTrue(storeSource.contains("obj.intOrNull(\"profileId\") ?: 1"))
        assertTrue(storeSource.contains("addProperty(\"profileId\", envelope.profileId)"))
        assertTrue(policySource.contains("existing.profileId == incoming.profileId"))
    }

    @Test
    fun `trakt outbox adapters execute with envelope profile session`() {
        val authSource = File("app/src/main/java/com/nexio/tv/data/repository/TraktAuthService.kt").readText()
        val scrobbleSource = File("app/src/main/java/com/nexio/tv/data/repository/trakt/TraktScrobbleMutationAdapter.kt").readText()
        val historySource = File("app/src/main/java/com/nexio/tv/data/repository/trakt/TraktProgressHistoryMutationAdapter.kt").readText()

        assertTrue(authSource.contains("suspend fun <T> executeAuthorizedWriteRequest("))
        assertTrue(authSource.contains("session: TrackingAuthSession"))
        assertTrue(scrobbleSource.contains("private fun TraktMutationEnvelope.session(): TrackingAuthSession"))
        assertTrue(scrobbleSource.contains("profileId = profileId"))
        assertTrue(historySource.contains("private fun TraktMutationEnvelope.session(): TrackingAuthSession"))
        assertTrue(historySource.contains("profileId = profileId"))
        assertTrue(!scrobbleSource.contains("traktAuthService.executeAuthorizedWriteRequest { authHeader ->"))
    }

    @Test
    fun `tracking library services enforce profile owned state and api sessions`() {
        val traktLibrarySource = File("app/src/main/java/com/nexio/tv/data/repository/TraktLibraryService.kt").readText()
        val traktLibraryAdapterSource = File("app/src/main/java/com/nexio/tv/data/repository/trakt/TraktLibraryMutationAdapter.kt").readText()
        val simklLibrarySource = File("app/src/main/java/com/nexio/tv/data/repository/SimklLibraryService.kt").readText()
        val simklLibraryAdapterSource = File("app/src/main/java/com/nexio/tv/data/repository/simkl/SimklLibraryMutationAdapter.kt").readText()
        val simklRemoteSource = File("app/src/main/java/com/nexio/tv/data/repository/SimklTrackingRemoteDataSource.kt").readText()
        val simklIntegrationSource = File("app/src/main/java/com/nexio/tv/data/integration/simkl/SimklIntegrationProvider.kt").readText()

        assertTrue(traktLibrarySource.contains("restoreSnapshotForProfile(activeProfileId())"))
        assertTrue(traktLibrarySource.contains("restoreSnapshotForProfile(profileId)"))
        assertTrue(traktLibrarySource.contains("snapshotStore.read(profileId)"))
        assertTrue(traktLibrarySource.contains("snapshotStore.write(persisted, profileId)"))
        assertTrue(traktLibrarySource.contains("session = mutationSession()"))
        assertTrue(traktLibrarySource.contains("private suspend fun mutationSession(profileId: Int = activeProfileId())"))
        assertTrue(traktLibrarySource.contains("TrackingAuthSession(TrackingProvider.TRAKT, profileId)"))
        assertTrue(traktLibrarySource.contains("fetchSnapshot(session)"))
        assertTrue(traktLibraryAdapterSource.contains("executor.addToWatchlist(envelope.session(), envelope.listItemsBody())"))
        assertTrue(traktLibraryAdapterSource.contains("TrackingAuthSession("))
        assertTrue(traktLibraryAdapterSource.contains("provider = TrackingProvider.TRAKT"))
        assertTrue(traktLibraryAdapterSource.contains("profileId = profileId"))

        assertTrue(simklLibrarySource.contains("restoreSnapshotForProfile(activeProfileId())"))
        assertTrue(simklLibrarySource.contains("restoreSnapshotForProfile(profileId)"))
        assertTrue(simklLibrarySource.contains("snapshotStore.read(profileId)"))
        assertTrue(simklLibrarySource.contains("profileId = profileId"))
        assertTrue(simklLibrarySource.contains("TrackingAuthSession(TrackingProvider.SIMKL, profileId)"))
        assertTrue(simklLibrarySource.contains("remote.getLastActivities(session)"))
        assertTrue(simklLibrarySource.contains("previousTimestamps = previousTimestamps"))
        assertTrue(simklLibraryAdapterSource.contains("remote.addToList(envelope.addBody(), envelope.session())"))
        assertTrue(simklLibraryAdapterSource.contains("provider = TrackingProvider.SIMKL"))
        assertTrue(simklLibraryAdapterSource.contains("profileId = profileId"))

        assertTrue(simklRemoteSource.contains("session: TrackingAuthSession? = null"))
        assertTrue(simklRemoteSource.contains("simklIntegrationProvider.getLastActivities(session)"))
        assertTrue(simklRemoteSource.contains("simklIntegrationProvider.addToList(body = body, session = session)"))
        assertTrue(simklIntegrationSource.contains("simklAuthService.executeAuthOwnerRequest("))
        assertTrue(simklIntegrationSource.contains("session = ownerSession"))
        assertTrue(simklIntegrationSource.contains("simklAuthService.executeAuthorizedWriteRequest("))
    }

    @Test
    fun `new trakt mutations are stamped with captured profile id`() {
        val scrobbleServiceSource = File("app/src/main/java/com/nexio/tv/data/repository/TraktScrobbleService.kt").readText()
        val watchProgressSource = File("app/src/main/java/com/nexio/tv/data/repository/WatchProgressRepositoryImpl.kt").readText()
        val scrobbleAdapterSource = File("app/src/main/java/com/nexio/tv/data/repository/trakt/TraktScrobbleMutationAdapter.kt").readText()
        val historyAdapterSource = File("app/src/main/java/com/nexio/tv/data/repository/trakt/TraktProgressHistoryMutationAdapter.kt").readText()
        val seasonAdapterSource = File("app/src/main/java/com/nexio/tv/data/repository/trakt/TraktSeasonMarkMutationAdapter.kt").readText()

        assertTrue(scrobbleServiceSource.contains("val session = authSession(ownerProfileId)"))
        assertTrue(scrobbleServiceSource.contains("?: traktAuthService.currentAuthSession()"))
        assertTrue(scrobbleAdapterSource.contains("session: TrackingAuthSession"))
        assertTrue(scrobbleAdapterSource.contains("profileId = session.profileId"))
        assertTrue(historyAdapterSource.contains("session: TrackingAuthSession"))
        assertTrue(historyAdapterSource.contains("profileId = session.profileId"))
        assertTrue(seasonAdapterSource.contains("session: TrackingAuthSession"))
        assertTrue(seasonAdapterSource.contains("profileId = session.profileId"))
        assertTrue(watchProgressSource.contains("accountSessionFor("))
        assertTrue(watchProgressSource.contains("profileId = profileSession.profileId"))
    }

    @Test
    fun `player safe playback defaults are profile scoped settings`() {
        val source = File("app/src/main/java/com/nexio/tv/data/local/PlayerSettingsDataStore.kt").readText()

        assertTrue(source.contains("private const val FEATURE = \"player_settings\""))
        assertTrue(source.contains("factory.get(profileId, FEATURE)"))
        assertTrue(source.contains("migration_safe_playback_defaults_done"))
        assertTrue(source.contains("experimental_dv7_hevc_base_layer_enabled"))
        assertTrue(source.contains("experimental_dv7_to_dv81_enabled"))
        assertTrue(source.contains("vod_cache_size_mode"))
        assertTrue(source.contains("use_parallel_connections"))
    }
}
