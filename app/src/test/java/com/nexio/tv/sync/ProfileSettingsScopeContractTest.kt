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
}
