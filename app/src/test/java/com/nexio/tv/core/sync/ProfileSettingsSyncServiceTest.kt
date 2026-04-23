package com.nexio.tv.core.sync

import android.app.Application
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.nexio.tv.core.auth.AuthManager
import com.nexio.tv.domain.model.AuthState
import com.nexio.tv.core.profile.ProfileBoundary
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.core.profile.ProfileModeRouter
import com.nexio.tv.data.local.ProfileDataStoreFactory
import io.github.jan.supabase.postgrest.Postgrest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

typealias AndroidJUnit4 = RobolectricTestRunner

@RunWith(AndroidJUnit4::class)
class ProfileSettingsSyncServiceTest {
    private companion object {
        val realProfileDataStoreFactory: ProfileDataStoreFactory by lazy {
            val context = ApplicationProvider.getApplicationContext<Application>()
            ProfileDataStoreFactory(context)
        }
    }

    private fun profileManagerForTest(activeProfileId: MutableStateFlow<Int> = MutableStateFlow(1)): ProfileManager {
        return mockk {
            every { this@mockk.activeProfileId } returns activeProfileId
            every { this@mockk.isPrimaryProfileActive } answers { activeProfileId.value == 1 }
        }
    }

    private fun service(): ProfileSettingsSyncService {
        val profileManager = profileManagerForTest()
        return ProfileSettingsSyncService(
            authManager = mockk<AuthManager>(relaxed = true),
            postgrest = mockk<Postgrest>(relaxed = true),
            profileManager = profileManager,
            profileDataStoreFactory = mockk<ProfileDataStoreFactory>(relaxed = true),
            profileModeRouter = ProfileModeRouter(),
            profileBoundary = ProfileBoundary(profileManager, languageTagProvider = { "en" })
        )
    }

    private fun realDataStoreService(): ProfileSettingsSyncService {
        val profileManager = profileManagerForTest()
        return ProfileSettingsSyncService(
            authManager = mockk<AuthManager>(relaxed = true),
            postgrest = mockk<Postgrest>(relaxed = true),
            profileManager = profileManager,
            profileDataStoreFactory = realProfileDataStoreFactory,
            profileModeRouter = ProfileModeRouter(),
            profileBoundary = ProfileBoundary(profileManager, languageTagProvider = { "en" })
        )
    }

    private fun extractMethodBody(source: String, methodName: String): String {
        val methodStart = source.indexOf("fun $methodName")
        assertTrue("$methodName method should exist", methodStart >= 0)
        val bodyStart = source.indexOf('{', methodStart)
        assertTrue("$methodName method body should start", bodyStart >= 0)

        var depth = 0
        for (index in bodyStart until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(bodyStart, index + 1)
                }
            }
        }

        throw AssertionError("$methodName method body should be balanced")
    }

    @Test
    fun `encodePreferenceValue string returns correct type and value`() {
        val encoded = service().encodePreferenceValue("hello")

        assertNotNull(encoded)
        assertEquals("string", encoded!!["type"]!!.jsonPrimitive.content)
        assertEquals("hello", encoded["value"]!!.jsonPrimitive.content)
    }

    @Test
    fun `encodePreferenceValue boolean returns correct type`() {
        val encoded = service().encodePreferenceValue(true)

        assertNotNull(encoded)
        assertEquals("boolean", encoded!!["type"]!!.jsonPrimitive.content)
        assertEquals("true", encoded["value"]!!.jsonPrimitive.content)
    }

    @Test
    fun `encodePreferenceValue int returns correct type`() {
        val encoded = service().encodePreferenceValue(42)

        assertNotNull(encoded)
        assertEquals("int", encoded!!["type"]!!.jsonPrimitive.content)
        assertEquals(42, encoded["value"]!!.jsonPrimitive.int)
    }

    @Test
    fun `encodePreferenceValue long returns correct type`() {
        val encoded = service().encodePreferenceValue(42L)

        assertNotNull(encoded)
        assertEquals("long", encoded!!["type"]!!.jsonPrimitive.content)
        assertEquals(42L, encoded["value"]!!.jsonPrimitive.long)
    }

    @Test
    fun `encodePreferenceValue float converts to double`() {
        val encoded = service().encodePreferenceValue(1.5f)

        assertNotNull(encoded)
        assertEquals("float", encoded!!["type"]!!.jsonPrimitive.content)
        assertEquals(1.5, encoded["value"]!!.jsonPrimitive.double, 0.0001)
    }

    @Test
    fun `encodePreferenceValue double returns correct type`() {
        val encoded = service().encodePreferenceValue(2.5)

        assertNotNull(encoded)
        assertEquals("double", encoded!!["type"]!!.jsonPrimitive.content)
        assertEquals(2.5, encoded["value"]!!.jsonPrimitive.double, 0.0001)
    }

    @Test
    fun `encodePreferenceValue string set returns array`() {
        val encoded = service().encodePreferenceValue(setOf("a", "b"))

        assertNotNull(encoded)
        assertEquals("string_set", encoded!!["type"]!!.jsonPrimitive.content)
        assertEquals(
            setOf("a", "b"),
            encoded["value"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        )
    }

    @Test
    fun `encodePreferenceValue null returns null`() {
        assertNull(service().encodePreferenceValue(null))
    }

    @Test
    fun `syncedFeatures contains exactly 5 settings stores`() {
        val syncedFeatures = service().syncedFeatures

        assertEquals(5, syncedFeatures.size)
        assertEquals(
            listOf(
                "trakt_settings",
                "simkl_settings",
                "player_settings",
                "layout_settings",
                "theme_settings"
            ),
            syncedFeatures
        )
    }

    @Test
    fun `syncedFeatures does not include layout preferences`() {
        assertTrue("layout_preferences should not sync in settings blob", "layout_preferences" !in service().syncedFeatures)
    }

    @Test
    fun `syncedFeatures excludes auth stores`() {
        val syncedFeatures = service().syncedFeatures

        assertTrue("trakt_auth_store should not sync in settings blob", "trakt_auth_store" !in syncedFeatures)
        assertTrue("simkl_auth_store should not sync in settings blob", "simkl_auth_store" !in syncedFeatures)
    }

    @Test
    fun `buildSettingsSignature is deterministic`() {
        val blob = buildJsonObject {
            put(
                "theme_settings",
                buildJsonObject {
                    put(
                        "theme",
                        buildJsonObject {
                            put("type", "string")
                            put("value", "dark")
                        }
                    )
                }
            )
        }
        val settingsSyncService = service()

        assertEquals(
            settingsSyncService.buildSettingsSignature(blob),
            settingsSyncService.buildSettingsSignature(blob)
        )
    }

    @Test
    fun `normalizeSettingsBlob inserts empty objects for missing synced features`() {
        val settingsSyncService = service()
        val normalized = settingsSyncService.normalizeSettingsBlob(
            buildJsonObject {
                put("theme_settings", buildJsonObject {})
            }
        )

        settingsSyncService.syncedFeatures.forEach { feature ->
            assertNotNull(normalized[feature])
            if (feature != "theme_settings") {
                assertEquals(buildJsonObject {}, normalized[feature]!!.jsonObject)
            }
        }
    }

    @Test
    fun `normalizeSettingsBlob ignores unknown feature keys`() {
        val normalized = service().normalizeSettingsBlob(
            buildJsonObject {
                put("theme_settings", buildJsonObject {})
                put("unknown_feature", buildJsonObject {})
            }
        )

        assertTrue("unknown_feature should not be normalized", "unknown_feature" !in normalized)
    }

    @Test
    fun `importSettingsBlob clears absent local keys before applying remote values`() = runTest {
        val settingsSyncService = realDataStoreService()
        val store = realProfileDataStoreFactory.get(2, "theme_settings")
        val oldThemeKey = stringPreferencesKey("old_theme")
        val themeKey = stringPreferencesKey("theme")
        store.edit { preferences ->
            preferences.clear()
            preferences[oldThemeKey] = "ocean"
        }

        settingsSyncService.importSettingsBlob(
            profileId = 2,
            blob = buildJsonObject {
                put(
                    "theme_settings",
                    buildJsonObject {
                        put(
                            "theme",
                            buildJsonObject {
                                put("type", "string")
                                put("value", "crimson")
                            }
                        )
                    }
                )
            }
        )

        val preferences = store.data.first()
        assertNull(preferences[oldThemeKey])
        assertEquals("crimson", preferences[themeKey])
    }

    @Test
    fun `importSettingsBlob clears missing synced feature blob as empty snapshot`() = runTest {
        val settingsSyncService = realDataStoreService()
        val store = realProfileDataStoreFactory.get(2, "player_settings")
        val arbitraryKey = stringPreferencesKey("arbitrary_player_setting")
        store.edit { preferences ->
            preferences.clear()
            preferences[arbitraryKey] = "enabled"
        }

        settingsSyncService.importSettingsBlob(
            profileId = 2,
            blob = buildJsonObject {
                put("theme_settings", buildJsonObject {})
            }
        )

        assertTrue(store.data.first().asMap().isEmpty())
    }

    @Test
    fun `importSettingsBlob writes layout settings to real LayoutPreferenceDataStore feature`() = runTest {
        val settingsSyncService = realDataStoreService()
        val realLayoutStore = realProfileDataStoreFactory.get(2, "layout_settings")
        val unusedLayoutStore = realProfileDataStoreFactory.get(2, "layout_preferences")
        val homeCatalogOrderKeysKey = stringPreferencesKey("home_catalog_order_keys")

        realLayoutStore.edit { preferences -> preferences.clear() }
        unusedLayoutStore.edit { preferences -> preferences.clear() }

        settingsSyncService.importSettingsBlob(
            profileId = 2,
            blob = buildJsonObject {
                put(
                    "layout_settings",
                    buildJsonObject {
                        put(
                            "home_catalog_order_keys",
                            buildJsonObject {
                                put("type", "string")
                                put("value", "[\"featured\",\"movies\"]")
                            }
                        )
                    }
                )
            }
        )

        assertEquals(
            "[\"featured\",\"movies\"]",
            realLayoutStore.data.first()[homeCatalogOrderKeysKey]
        )
        assertTrue(unusedLayoutStore.data.first().asMap().isEmpty())
    }

    @Test
    fun `importSettingsBlob imports web adapter catalog fixture into layout settings DataStore`() = runTest {
        val settingsSyncService = realDataStoreService()
        val realLayoutStore = realProfileDataStoreFactory.get(2, "layout_settings")
        val homeCatalogOrderKeysKey = stringPreferencesKey("home_catalog_order_keys")
        val disabledHomeCatalogKeysKey = stringPreferencesKey("disabled_home_catalog_keys")

        realLayoutStore.edit { preferences -> preferences.clear() }

        settingsSyncService.importSettingsBlob(
            profileId = 2,
            blob = buildJsonObject {
                put(
                    "layout_settings",
                    buildJsonObject {
                        put(
                            "home_catalog_order_keys",
                            buildJsonObject {
                                put("type", "string")
                                put("value", "[\"featured\",\"movies\"]")
                            }
                        )
                        put(
                            "disabled_home_catalog_keys",
                            buildJsonObject {
                                put("type", "string")
                                put("value", "[\"addon-old\"]")
                            }
                        )
                    }
                )
            }
        )

        val preferences = realLayoutStore.data.first()
        assertEquals("[\"featured\",\"movies\"]", preferences[homeCatalogOrderKeysKey])
        assertEquals("[\"addon-old\"]", preferences[disabledHomeCatalogKeysKey])
    }

    @Test
    fun `importSettingsBlob imports web adapter formatter fixture into player settings DataStore`() = runTest {
        val settingsSyncService = realDataStoreService()
        val realPlayerStore = realProfileDataStoreFactory.get(2, "player_settings")
        val formatterEnabledKey = booleanPreferencesKey("synced_formatter_enabled")
        val selectedTemplateIdKey = stringPreferencesKey("synced_formatter_selected_template_id")
        val customTemplateLabelKey = stringPreferencesKey("synced_formatter_custom_template_label")
        val customNameTemplateKey = stringPreferencesKey("synced_formatter_custom_name_template")
        val customDescriptionTemplateKey = stringPreferencesKey("synced_formatter_custom_description_template")

        realPlayerStore.edit { preferences -> preferences.clear() }

        settingsSyncService.importSettingsBlob(
            profileId = 2,
            blob = buildJsonObject {
                put(
                    "player_settings",
                    buildJsonObject {
                        put(
                            "synced_formatter_enabled",
                            buildJsonObject {
                                put("type", "boolean")
                                put("value", false)
                            }
                        )
                        put(
                            "synced_formatter_selected_template_id",
                            buildJsonObject {
                                put("type", "string")
                                put("value", "custom")
                            }
                        )
                        put(
                            "synced_formatter_custom_template_label",
                            buildJsonObject {
                                put("type", "string")
                                put("value", "Custom")
                            }
                        )
                        put(
                            "synced_formatter_custom_name_template",
                            buildJsonObject {
                                put("type", "string")
                                put("value", "{title}")
                            }
                        )
                        put(
                            "synced_formatter_custom_description_template",
                            buildJsonObject {
                                put("type", "string")
                                put("value", "{size}")
                            }
                        )
                    }
                )
            }
        )

        val preferences = realPlayerStore.data.first()
        assertEquals(false, preferences[formatterEnabledKey])
        assertEquals("custom", preferences[selectedTemplateIdKey])
        assertEquals("Custom", preferences[customTemplateLabelKey])
        assertEquals("{title}", preferences[customNameTemplateKey])
        assertEquals("{size}", preferences[customDescriptionTemplateKey])
    }

    @Test
    fun `startObserving pulls selected profile before observing settings for push`() {
        val source = java.io.File(
            "app/src/main/java/com/nexio/tv/core/sync/ProfileSettingsSyncService.kt"
        ).readText()
        val startObservingText = extractMethodBody(source, "startObserving")

        assertTrue(startObservingText.contains("distinctUntilChanged()"))
        assertTrue("primary profile should not observe profile settings blob", startObservingText.contains("ProfileModeRoute.DefaultLegacyRoute"))
        assertTrue(startObservingText.contains("profileModeRouter.routeFor(profileId)"))
        assertTrue(startObservingText.contains("pullBlobForProfile(scopedProfileId)"))
        assertTrue(startObservingText.contains("observeProfileSettings(scopedProfileId)"))
        assertTrue(
            startObservingText.indexOf("pullBlobForProfile(scopedProfileId)") <
                startObservingText.indexOf("observeProfileSettings(scopedProfileId)")
        )
        assertTrue(startObservingText.contains("debounce(2000)"))
        assertTrue(startObservingText.contains("pushBlobForProfile(profileId)"))
    }

    @Test
    fun `primary profile settings blob sync is a no-op`() {
        val source = java.io.File(
            "app/src/main/java/com/nexio/tv/core/sync/ProfileSettingsSyncService.kt"
        ).readText()
        val pullText = extractMethodBody(source, "pullBlobForProfile")
        val pushText = extractMethodBody(source, "pushBlobForProfile")

        assertTrue("primary profile should not pull profile settings blob", pullText.contains("ProfileModeRoute.DefaultLegacyRoute"))
        assertTrue("primary profile pull should return success", pullText.contains("Result.success(Unit)"))
        assertTrue("primary profile should not push profile settings blob", pushText.contains("ProfileModeRoute.DefaultLegacyRoute"))
        assertTrue("primary profile push should return success", pushText.contains("Result.success(Unit)"))
    }

    @Test
    fun `pushBlobForProfile requires full account session`() = runTest {
        val authManager = mockk<AuthManager> {
            every { authState } returns MutableStateFlow(AuthState.SessionLost)
        }
        val postgrest = mockk<Postgrest>(relaxed = true)
        val profileManager = profileManagerForTest(MutableStateFlow(2))
        val profileDataStoreFactory = mockk<ProfileDataStoreFactory>(relaxed = true)
        val settingsSyncService = ProfileSettingsSyncService(
            authManager = authManager,
            postgrest = postgrest,
            profileManager = profileManager,
            profileDataStoreFactory = profileDataStoreFactory,
            profileModeRouter = ProfileModeRouter(),
            profileBoundary = ProfileBoundary(profileManager, languageTagProvider = { "en" })
        )

        val result = settingsSyncService.pushBlobForProfile(2)

        assertTrue(result.isFailure)
        verify(exactly = 0) { profileDataStoreFactory.get(any(), any()) }
    }

    @Test
    fun `pullBlobForProfile requires full account session`() = runTest {
        val authManager = mockk<AuthManager> {
            every { authState } returns MutableStateFlow(AuthState.SessionLost)
        }
        val postgrest = mockk<Postgrest>(relaxed = true)
        val profileManager = profileManagerForTest(MutableStateFlow(2))
        val profileDataStoreFactory = mockk<ProfileDataStoreFactory>(relaxed = true)
        val settingsSyncService = ProfileSettingsSyncService(
            authManager = authManager,
            postgrest = postgrest,
            profileManager = profileManager,
            profileDataStoreFactory = profileDataStoreFactory,
            profileModeRouter = ProfileModeRouter(),
            profileBoundary = ProfileBoundary(profileManager, languageTagProvider = { "en" })
        )

        val result = settingsSyncService.pullBlobForProfile(2)

        assertTrue(result.isFailure)
        verify(exactly = 0) { profileDataStoreFactory.get(any(), any()) }
    }
}
