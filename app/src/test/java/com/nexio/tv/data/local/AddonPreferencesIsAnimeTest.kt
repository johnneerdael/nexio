package com.nexio.tv.data.local

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.nexio.tv.domain.model.AddonParserPreset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AddonPreferencesIsAnimeTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `addAddon defaults isAnime to false`() = runTest {
        val store = addonPreferencesForTest()

        store.addAddon("https://stremio.example/anime")

        val addon = store.installedAddons.first().single()

        assertEquals("https://stremio.example/anime", addon.url)
        assertEquals(false, addon.isAnime)
        assertEquals(AddonParserPreset.GENERIC, addon.parserPreset)
    }

    @Test
    fun `addAddon persists isAnime when provided`() = runTest {
        val store = addonPreferencesForTest()

        store.addAddon(
            url = "https://stremio.example/anime",
            parserPreset = AddonParserPreset.TORRENTIO,
            isAnime = true
        )

        val addon = store.installedAddons.first().single()

        assertEquals(true, addon.isAnime)
        assertEquals(AddonParserPreset.TORRENTIO, addon.parserPreset)
    }

    @Test
    fun `updateAddonIsAnime flips persisted value`() = runTest {
        val store = addonPreferencesForTest()

        store.addAddon(url = "https://stremio.example/anime")
        store.updateAddonIsAnime("https://stremio.example/anime", true)

        val addon = store.installedAddons.first().single()

        assertEquals(true, addon.isAnime)
    }

    @Test
    fun `setAddonConfigs preserves isAnime`() = runTest {
        val store = addonPreferencesForTest()

        val remoteConfigs = listOf(
            AddonPreferences.AddonInstallConfig(
                url = "https://stremio.example/anime",
                parserPreset = AddonParserPreset.TORRENTIO,
                isAnime = true
            ),
            AddonPreferences.AddonInstallConfig(
                url = "https://stremio.example/movies",
                parserPreset = AddonParserPreset.WEBSTREAMR,
                isAnime = false
            )
        )

        store.setAddonConfigs(remoteConfigs)

        val installed = store.installedAddons.first().associateBy { it.url }

        assertEquals(true, installed["https://stremio.example/anime"]?.isAnime)
        assertEquals(false, installed["https://stremio.example/movies"]?.isAnime)
    }

    @Test
    fun `existing entries without isAnime decode as false`() = runTest {
        val store = addonPreferencesForTest()

        store.writeRawOrderedJson("""[{"url":"https://legacy.example/anime","parserPreset":"GENERIC"}]""")

        val addon = store.installedAddons.first().single()

        assertFalse(addon.isAnime)
    }

    private suspend fun addonPreferencesForTest(): AddonPreferences {
        val store = AddonPreferences(context)
        store.clearForTest()
        return store
    }

    private suspend fun AddonPreferences.clearForTest() {
        dataStoreForTest().edit { prefs ->
            prefs[orderedUrlsKeyForTest()] = "[]"
            prefs.remove(legacyUrlsKeyForTest())
        }
    }

    private suspend fun AddonPreferences.writeRawOrderedJson(json: String) {
        dataStoreForTest().edit { prefs ->
            prefs[orderedUrlsKeyForTest()] = json
        }
    }

    private fun AddonPreferences.dataStoreForTest(): DataStore<Preferences> {
        val field = AddonPreferences::class.java.getDeclaredField("dataStore")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(this) as DataStore<Preferences>
    }

    private fun AddonPreferences.orderedUrlsKeyForTest(): Preferences.Key<String> {
        val field = AddonPreferences::class.java.getDeclaredField("orderedUrlsKey")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(this) as Preferences.Key<String>
    }

    private fun AddonPreferences.legacyUrlsKeyForTest(): Preferences.Key<kotlin.collections.Set<String>> {
        val field = AddonPreferences::class.java.getDeclaredField("legacyUrlsKey")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(this) as Preferences.Key<kotlin.collections.Set<String>>
    }

}
