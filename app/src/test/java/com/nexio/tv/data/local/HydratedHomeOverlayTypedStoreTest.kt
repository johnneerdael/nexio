package com.nexio.tv.data.local

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.HomeItemHydrationState
import com.nexio.tv.domain.model.HydratedHomeOverlay
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.hydratedHomeDisplayHash
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HydratedHomeOverlayTypedStoreTest {
    private val gson = Gson()

    @Test
    fun `v2 round trip stores aliases as strings and overlays as typed values`() {
        val dir = Files.createTempDirectory("overlay-typed-v2").toFile()
        val file = File(dir, "hydrated-home-overlay-v2/entries.json")
        val store = HydratedHomeOverlayTypedStore(file, gson)
        val overlay = overlay(title = "Fight Club")

        assertTrue(store.upsert(overlay, setOf("alias::en::policy:1::movie:tmdb:550")))
        HydratedHomeOverlayTypedStore.resetSharedStateForTest(file)
        val reloaded = HydratedHomeOverlayTypedStore(file, gson)

        assertEquals(overlay.overlayKey, reloaded.aliasOverlayKey("alias::en::policy:1::movie:tmdb:550"))
        assertEquals("Fight Club", reloaded.overlay(overlay.overlayKey)?.fields?.title)
        val raw = file.readText()
        assertTrue(raw.contains("\"aliases\""))
        assertTrue(raw.contains("\"overlays\""))
        assertFalse(raw.contains("\"overlayKey\":{\""))
    }

    @Test
    fun `malformed v2 entries are skipped without dropping valid entries`() {
        val dir = Files.createTempDirectory("overlay-typed-malformed").toFile()
        val file = File(dir, "hydrated-home-overlay-v2/entries.json")
        val defaultsKey = "canonical:TMDB:551:type:MOVIE:lang:en:policy:1"
        val corruptKey = "canonical:TMDB:552:type:MOVIE:lang:en:policy:1"
        val missingRequiredKey = "canonical:TMDB:553:type:MOVIE:lang:en:policy:1"
        val missingDefaults = gson.toJsonTree(
            overlay(title = "Missing Defaults", overlayKey = defaultsKey, canonicalId = "551")
        ).asJsonObject.apply {
            remove("stableIdsSnapshot")
            remove("settingsSignature")
        }
        val corruptedHash = gson.toJsonTree(
            overlay(title = "Corrupt Hash", overlayKey = corruptKey, canonicalId = "552")
        ).asJsonObject.apply {
            addProperty("displayHash", "not-the-fields-hash")
        }
        val missingRequired = gson.toJsonTree(
            overlay(title = "Missing Required", overlayKey = missingRequiredKey, canonicalId = "553")
        ).asJsonObject.apply {
            remove("itemKey")
            remove("state")
        }
        file.parentFile?.mkdirs()
        file.writeText(
            """
            {
              "schemaVersion": 2,
              "aliases": {
                "alias::en::policy:1::movie:tmdb:550": "canonical:TMDB:550:type:MOVIE:lang:en:policy:1",
                "alias::overflow": "canonical:overflow",
                "alias::bad": { "not": "a string" }
              },
              "overlays": {
                "canonical:TMDB:550:type:MOVIE:lang:en:policy:1": {
                  "schemaVersion": 1,
                  "value": ${gson.toJson(overlay(title = "Fight Club"))}
                },
                "$defaultsKey": {
                  "schemaVersion": 1,
                  "value": $missingDefaults
                },
                "$corruptKey": {
                  "schemaVersion": 1,
                  "value": $corruptedHash
                },
                "$missingRequiredKey": {
                  "schemaVersion": 1,
                  "value": $missingRequired
                },
                "canonical:overflow": {
                  "schemaVersion": 999999999999,
                  "value": ${gson.toJson(overlay(title = "Overflow", overlayKey = "canonical:overflow"))}
                },
                "canonical:bad": { "schemaVersion": 1, "value": "bad" }
              }
            }
            """.trimIndent()
        )

        val store = HydratedHomeOverlayTypedStore(file, gson)

        assertEquals("canonical:TMDB:550:type:MOVIE:lang:en:policy:1", store.aliasOverlayKey("alias::en::policy:1::movie:tmdb:550"))
        assertEquals("Fight Club", store.overlay("canonical:TMDB:550:type:MOVIE:lang:en:policy:1")?.fields?.title)
        val defaults = store.overlay(defaultsKey)
        assertEquals(ProviderIds(), defaults?.stableIdsSnapshot)
        assertEquals("", defaults?.settingsSignature)
        assertNull(store.aliasOverlayKey("alias::bad"))
        assertNull(store.aliasOverlayKey("alias::overflow"))
        assertNull(store.overlay("canonical:overflow"))
        assertNull(store.overlay(corruptKey))
        assertNull(store.overlay(missingRequiredKey))
        assertNull(store.overlay("canonical:bad"))
    }

    @Test
    fun `upsert rejects non canonical overlay keys`() {
        val dir = Files.createTempDirectory("overlay-typed-trim").toFile()
        val file = File(dir, "hydrated-home-overlay-v2/entries.json")
        val store = HydratedHomeOverlayTypedStore(file, gson)
        val overlay = overlay(
            title = "Trimmed",
            overlayKey = " canonical:TMDB:550:type:MOVIE:lang:en:policy:1 "
        )

        assertFalse(store.upsert(overlay, setOf("alias::en::policy:1::movie:tmdb:550")))
        assertNull(store.overlay("canonical:TMDB:550:type:MOVIE:lang:en:policy:1"))

        val inconsistent = overlay(
            title = "Inconsistent",
            overlayKey = "canonical:TMDB:550:type:MOVIE:lang:en:policy:1",
            canonicalId = "551"
        )

        assertFalse(store.upsert(inconsistent, setOf("alias::en::policy:1::movie:tmdb:551")))
        assertNull(store.overlay("canonical:TMDB:550:type:MOVIE:lang:en:policy:1"))
    }

    @Test
    fun `v1 file migration preserves valid aliases and overlays`() {
        val dir = Files.createTempDirectory("overlay-typed-v1").toFile()
        val v1File = File(dir, "hydrated-home-overlay-v1/entries.json")
        val v2File = File(dir, "hydrated-home-overlay-v2/entries.json")
        val overlay = overlay(title = "Legacy")
        val legacyValue = gson.toJsonTree(overlay).asJsonObject.apply {
            remove("stableIdsSnapshot")
            remove("settingsSignature")
        }
        FileBackedJsonObjectStore(v1File).putAll(
            mapOf(
                "overlay::${overlay.overlayKey}" to JsonObject().apply {
                    addProperty("schemaVersion", 1)
                    add("value", legacyValue)
                },
                "alias::en::policy:1::movie:tmdb:550" to JsonObject().apply {
                    addProperty("overlayKey", overlay.overlayKey)
                },
                "alias::bad" to JsonObject()
            )
        )
        FileBackedJsonObjectStore.resetSharedStateForTest(v1File)

        val store = HydratedHomeOverlayTypedStore(v2File, gson)
        assertTrue(store.migrateFromV1File(v1File))
        HydratedHomeOverlayTypedStore.resetSharedStateForTest(v2File)
        val reloaded = HydratedHomeOverlayTypedStore(v2File, gson)

        assertEquals(overlay.overlayKey, reloaded.aliasOverlayKey("alias::en::policy:1::movie:tmdb:550"))
        val migrated = reloaded.overlay(overlay.overlayKey)
        assertEquals("Legacy", migrated?.fields?.title)
        assertEquals(ProviderIds(), migrated?.stableIdsSnapshot)
        assertEquals("", migrated?.settingsSignature)
        assertTrue(v2File.exists())
    }

    @Test
    fun `v1 migration does not overwrite existing v2 entries`() {
        val dir = Files.createTempDirectory("overlay-typed-no-overwrite").toFile()
        val v1File = File(dir, "hydrated-home-overlay-v1/entries.json")
        val v2File = File(dir, "hydrated-home-overlay-v2/entries.json")
        val current = overlay(title = "Current")
        val stale = overlay(title = "Stale")
        val store = HydratedHomeOverlayTypedStore(v2File, gson)
        assertTrue(store.upsert(current, setOf("alias::en::policy:1::movie:tmdb:550")))
        FileBackedJsonObjectStore(v1File).putAll(
            mapOf(
                "overlay::${stale.overlayKey}" to JsonObject().apply {
                    addProperty("schemaVersion", 1)
                    add("value", gson.toJsonTree(stale))
                },
                "alias::en::policy:1::movie:tmdb:550" to JsonObject().apply {
                    addProperty("overlayKey", stale.overlayKey)
                }
            )
        )
        FileBackedJsonObjectStore.resetSharedStateForTest(v1File)

        assertTrue(store.migrateFromV1File(v1File))
        HydratedHomeOverlayTypedStore.resetSharedStateForTest(v2File)
        val reloaded = HydratedHomeOverlayTypedStore(v2File, gson)

        assertEquals(current.overlayKey, reloaded.aliasOverlayKey("alias::en::policy:1::movie:tmdb:550"))
        assertEquals("Current", reloaded.overlay(current.overlayKey)?.fields?.title)
    }

    private fun overlay(
        title: String,
        overlayKey: String = "canonical:TMDB:550:type:MOVIE:lang:en:policy:1",
        canonicalId: String = "550"
    ): HydratedHomeOverlay {
        val fields = HomeDisplayMetadata(title = title, poster = "rpdb://550.jpg")
        return HydratedHomeOverlay(
            overlayKey = overlayKey,
            itemKey = "movie:tmdb:550",
            canonicalProvider = ProviderId.TMDB,
            canonicalId = canonicalId,
            imdbId = "tt0137523",
            contentType = ContentType.MOVIE,
            languageTag = "en",
            policyVersion = 1,
            fields = fields,
            fieldTrace = emptyList(),
            displayHash = fields.hydratedHomeDisplayHash(),
            updatedAtMs = 1L,
            staleAtMs = Long.MAX_VALUE,
            expiresAtMs = Long.MAX_VALUE,
            state = HomeItemHydrationState.CANONICAL_READY,
            stableIdsSnapshot = ProviderIds(tmdb = "550", imdb = "tt0137523"),
            settingsSignature = "settings"
        )
    }
}
