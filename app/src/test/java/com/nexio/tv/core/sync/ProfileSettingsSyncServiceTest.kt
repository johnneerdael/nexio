package com.nexio.tv.core.sync

import com.nexio.tv.core.auth.AuthManager
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.data.local.ProfileDataStoreFactory
import io.github.jan.supabase.postgrest.Postgrest
import io.mockk.mockk
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
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
    private fun service(): ProfileSettingsSyncService {
        return ProfileSettingsSyncService(
            authManager = mockk<AuthManager>(relaxed = true),
            postgrest = mockk<Postgrest>(relaxed = true),
            profileManager = mockk<ProfileManager>(relaxed = true),
            profileDataStoreFactory = mockk<ProfileDataStoreFactory>(relaxed = true)
        )
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
                "layout_preferences",
                "theme_settings"
            ),
            syncedFeatures
        )
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
}
