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
    private val profileWebSyncService = File("app/src/main/java/com/nexio/tv/core/sync/ProfileWebSyncService.kt")
    private val homeCatalogPipeline = File("app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt")

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
            "ImdbSettingsDataStore",
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
            "WatchedItemsPreferences",
            "YouTubeTrailerAuthDataStore",
            "YouTubeTrailerTokenStore",
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
            "ImdbSettingsDataStore",
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
            "WatchProgressPreferences",
            "WatchedItemsPreferences"
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
            "MetadataDiskCacheStore",
            "CatalogDiskCacheStore"
        ).forEach { store ->
            assertEquals("$store should be profile-derived-cache", "`profile-derived-cache`", rows[store]?.get(2))
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
            "YouTubeTrailerAuthDataStore",
            "YouTubeTrailerTokenStore",
            "UpdatePreferences",
            "profile_cleanup_state"
        ).forEach { store ->
            assertEquals("$store should be global-device", "`global-device`", rows[store]?.get(2))
        }
    }

    @Test
    fun `profile local and derived cache classes do not have supabase owners`() {
        inventoryRows().values
            .filter { cells -> cells[2] == "`profile-local`" || cells[2] == "`profile-derived-cache`" }
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
        assertTrue(text.contains("No `profile-local`, `profile-derived-cache`, or `global-device` value may be serialized to `profile_settings`."))
    }

    @Test
    fun `account snapshot sync only reads and writes primary profile tracking auth`() {
        val source = accountSettingsSyncService.readText()

        assertTrue(source.contains("private const val PRIMARY_PROFILE_ID = 1"))
        assertTrue(source.contains("traktAuthDataStore.stateForProfile(PRIMARY_PROFILE_ID).drop(1).map { Unit }"))
        assertTrue(source.contains("simklAuthDataStore.stateForProfile(PRIMARY_PROFILE_ID).drop(1).map { Unit }"))
        assertTrue(source.contains("traktAuthDataStore.stateForProfile(PRIMARY_PROFILE_ID).first()"))
        assertTrue(source.contains("simklAuthDataStore.stateForProfile(PRIMARY_PROFILE_ID).first()"))
        assertTrue(source.contains("traktAuthDataStore.clearAuth(PRIMARY_PROFILE_ID)"))
        assertTrue(source.contains("simklAuthDataStore.clearAuth(PRIMARY_PROFILE_ID)"))
        assertTrue(source.contains("profileId = PRIMARY_PROFILE_ID"))
        assertTrue(source.contains("path == \"integrations.traktAuth\""))
        assertTrue(source.contains("path == \"integrations.simklAuth\""))
    }

    @Test
    fun `trakt up next descriptors require active profile trakt auth`() {
        val source = homeCatalogPipeline.readText()

        assertTrue(source.contains("hasTraktUpNextItems = activeProfileTraktAuthenticated && currentState.traktUpNextItems.isNotEmpty()"))
        assertTrue(source.contains("persistedTraktSyntheticGroups = if (providerState.traktAuthenticated) snapshot.traktGroups else emptyList()"))
        assertTrue(source.contains("activeProfileTraktAuthenticated = providerState.traktAuthenticated"))
        assertTrue(source.contains("trackingProviderStateService.currentState()"))
        assertTrue(
            "Trakt Up Next must not be described from stale UI state after profile auth is cleared",
            !source.contains("hasTraktUpNextItems = currentState.traktUpNextItems.isNotEmpty()")
        )
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

        assertTrue(source.contains("private const val PRIMARY_PROFILE_INDEX = 1"))
        assertTrue(source.contains("if (profileIndex == PRIMARY_PROFILE_INDEX)"))
        assertTrue(source.contains("account sync owns legacy integrations"))
    }
}
