package com.nexio.tv.data.local

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.nexio.tv.core.tvdb.TvEpisodeMetadata
import com.nexio.tv.core.tvdb.TvMetadataEnrichment
import com.nexio.tv.domain.model.MetaCastMember
import com.nexio.tv.domain.model.MetaCompany
import com.nexio.tv.domain.model.MetaCompanyKind
import com.nexio.tv.testutil.InMemorySharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataDiskCacheStoreTvdbTest {

    @Test
    fun `read and write TVDB enrichment uses tvdb namespace and schema`() {
        val prefs = InMemorySharedPreferences()
        val store = MetadataDiskCacheStore(context = mockContext(prefs))
        val enrichment = TvMetadataEnrichment(
            seriesTvdbId = 121361,
            localizedTitle = "Game of Thrones",
            description = "Nine noble families fight for control.",
            genres = listOf("Drama"),
            poster = "https://art.example/poster.jpg",
            airsDays = mapOf("sunday" to true),
            airsTime = "21:00",
            averageRuntimeMinutes = 57,
            originalCountry = "usa",
            originalLanguage = "eng",
            status = "Ended",
            aliases = listOf("GoT"),
            contentRatings = listOf("TV-MA"),
            remoteIds = mapOf("imdb" to setOf("tt0944947"))
        )

        store.writeTvdbEnrichment(
            seriesId = 121361,
            recordKind = " Extended ",
            languageTag = "en-US",
            providerToken = "topposters-tvdb",
            enrichment = enrichment
        )
        store.flushPendingWritesForTest()

        val key = prefs.all.keys.single { it.startsWith("tvdb::") }
        val root = Gson().fromJson(prefs.all[key] as String, JsonObject::class.java)

        assertEquals("tvdb::121361::extended::en-US::topposters-tvdb", key)
        assertTrue(root.has("tvdbSchemaVersion"))
        assertFalse(root.has("tmdbSchemaVersion"))
        assertEquals(
            enrichment.copy(ratingSource = null),
            store.readTvdbEnrichment(
                seriesId = 121361,
                recordKind = "extended",
                languageTag = "en-US",
                providerToken = "topposters-tvdb"
            )
        )
        assertNull(
            store.readTvdbEnrichment(
                seriesId = 121361,
                recordKind = "extended",
                languageTag = "nl-NL",
                providerToken = "topposters-tvdb"
            )
        )
    }

    @Test
    fun `read and write TVDB season episodes uses tvdb episode namespace and schema`() {
        val prefs = InMemorySharedPreferences()
        val store = MetadataDiskCacheStore(context = mockContext(prefs))
        val episodes = listOf(
            TvEpisodeMetadata(
                providerEpisodeId = "tvdb:1001",
                seasonNumber = 1,
                episodeNumber = 1,
                title = "Winter Is Coming",
                overview = "The first episode.",
                thumbnail = "https://art.example/episode.jpg",
                airDate = "2011-04-17",
                runtimeMinutes = 62,
                absoluteNumber = 1,
                finaleType = null
            ),
            TvEpisodeMetadata(
                providerEpisodeId = "tvdb:1002",
                seasonNumber = 1,
                episodeNumber = 2,
                title = "The Kingsroad",
                overview = "The second episode.",
                thumbnail = null,
                airDate = "2011-04-24",
                runtimeMinutes = 56,
                airsBeforeSeason = 2,
                airsBeforeEpisode = 1,
                linkedMovieTvdbId = 500,
                finaleType = "midseason"
            )
        )

        store.writeTvdbSeasonEpisodes(
            seriesId = 121361,
            seasonType = " Default ",
            seasonNumber = 1,
            languageTag = "en-US",
            episodes = episodes
        )
        store.flushPendingWritesForTest()

        val key = prefs.all.keys.single { it.startsWith("tvdb_episode::") }
        val root = Gson().fromJson(prefs.all[key] as String, JsonObject::class.java)

        assertEquals("tvdb_episode::121361::default::1::en-US", key)
        assertTrue(root.has("tvdbEpisodeSchemaVersion"))
        assertFalse(root.has("tmdbSchemaVersion"))
        assertEquals(
            episodes,
            store.readTvdbSeasonEpisodes(
                seriesId = 121361,
                seasonType = "default",
                seasonNumber = 1,
                languageTag = "en-US"
            )
        )
        assertNull(
            store.readTvdbSeasonEpisodes(
                seriesId = 121361,
                seasonType = "absolute",
                seasonNumber = 1,
                languageTag = "en-US"
            )
        )
    }

    @Test
    fun `read TVDB enrichment rebuilds typed advanced surfaces from raw JSON`() {
        val prefs = InMemorySharedPreferences()
        val store = MetadataDiskCacheStore(context = mockContext(prefs))
        prefs.edit().putString(
            "tvdb::121361::series_extended::en-US::native",
            """
            {
              "value": {
                "seriesTvdbId": 121361,
                "localizedTitle": "Game of Thrones",
                "genres": [],
                "airsDays": {},
                "remoteIds": {},
                "aliases": [],
                "contentRatings": [],
                "castMembers": [
                  {
                    "name": "Pedro Pascal",
                    "character": "Joel Miller",
                    "photo": "https://art.example/pedro.jpg",
                    "tmdbId": null
                  }
                ],
                "productionCompanies": [
                  {
                    "name": "Bighead Littlehead",
                    "kind": "COMPANY"
                  }
                ],
                "networks": [
                  {
                    "name": "HBO",
                    "kind": "NETWORK"
                  }
                ]
              },
              "tvdbSchemaVersion": 2,
              "languageEpoch": 0,
              "updatedAtMs": ${System.currentTimeMillis()}
            }
            """.trimIndent()
        ).apply()

        val enrichment = store.readTvdbEnrichment(
            seriesId = 121361,
            recordKind = "series_extended",
            languageTag = "en-US",
            providerToken = "native"
        )

        assertEquals(
            listOf(
                MetaCastMember(
                    name = "Pedro Pascal",
                    character = "Joel Miller",
                    photo = "https://art.example/pedro.jpg"
                )
            ),
            enrichment?.castMembers
        )
        assertEquals(
            listOf(MetaCompany(name = "Bighead Littlehead", kind = MetaCompanyKind.COMPANY)),
            enrichment?.productionCompanies
        )
        assertEquals(
            listOf(MetaCompany(name = "HBO", kind = MetaCompanyKind.NETWORK)),
            enrichment?.networks
        )
    }

    @Test
    fun `read TVDB enrichment drops cached score values from rating`() {
        val prefs = InMemorySharedPreferences()
        val store = MetadataDiskCacheStore(context = mockContext(prefs))
        prefs.edit().putString(
            "tvdb::121361::series_extended::en-US::native",
            """
            {
              "value": {
                "seriesTvdbId": 121361,
                "localizedTitle": "Game of Thrones",
                "rating": 14244.2,
                "genres": [],
                "airsDays": {},
                "remoteIds": {},
                "aliases": [],
                "contentRatings": []
              },
              "tvdbSchemaVersion": 2,
              "languageEpoch": 0,
              "updatedAtMs": ${System.currentTimeMillis()}
            }
            """.trimIndent()
        ).apply()

        val enrichment = store.readTvdbEnrichment(
            seriesId = 121361,
            recordKind = "series_extended",
            languageTag = "en-US",
            providerToken = "native"
        )

        assertNull(enrichment?.rating)
    }

    @Test
    fun `read TVDB enrichment tolerates missing rating source`() {
        val prefs = InMemorySharedPreferences()
        val store = MetadataDiskCacheStore(context = mockContext(prefs))
        prefs.edit().putString(
            "tvdb::121361::series_extended::en-US::native",
            """
            {
              "value": {
                "seriesTvdbId": 121361,
                "localizedTitle": "Game of Thrones",
                "rating": null,
                "genres": [],
                "airsDays": {},
                "remoteIds": {},
                "aliases": [],
                "contentRatings": [],
                "castMembers": [],
                "productionCompanies": [],
                "networks": []
              },
              "tvdbSchemaVersion": 2,
              "languageEpoch": 0,
              "updatedAtMs": ${System.currentTimeMillis()}
            }
            """.trimIndent()
        ).commit()

        val enrichment = store.readTvdbEnrichment(121361, "series_extended", "en-US", "native")

        enrichment.hashCode()
        assertNull(enrichment?.ratingSource)
    }

    @Test
    fun `expired TVDB enrichment cache entry is ignored`() {
        val prefs = InMemorySharedPreferences()
        val store = MetadataDiskCacheStore(context = mockContext(prefs))
        store.writeTvdbEnrichment(
            seriesId = 121361,
            recordKind = "series_extended",
            languageTag = "en-US",
            providerToken = "native",
            enrichment = TvMetadataEnrichment(seriesTvdbId = 121361, localizedTitle = "Game of Thrones")
        )
        store.flushPendingWritesForTest()
        rewriteUpdatedAt(prefs, "tvdb::121361::series_extended::en-US::native", 0L)

        assertNull(store.readTvdbEnrichment(121361, "series_extended", "en-US", "native"))
    }

    @Test
    fun `expired TVDB season episode cache entry is ignored`() {
        val prefs = InMemorySharedPreferences()
        val store = MetadataDiskCacheStore(context = mockContext(prefs))
        store.writeTvdbSeasonEpisodes(
            seriesId = 121361,
            seasonType = "default",
            seasonNumber = 1,
            languageTag = "en-US",
            episodes = listOf(TvEpisodeMetadata(seasonNumber = 1, episodeNumber = 1, title = "Winter Is Coming"))
        )
        store.flushPendingWritesForTest()
        rewriteUpdatedAt(prefs, "tvdb_episode::121361::default::1::en-US", 0L)

        assertNull(store.readTvdbSeasonEpisodes(121361, "default", 1, "en-US"))
    }

    private fun mockContext(prefs: InMemorySharedPreferences): Context {
        return mockk {
            every { getSharedPreferences("metadata_disk_cache_v1", Context.MODE_PRIVATE) } returns prefs
        }
    }

    private fun rewriteUpdatedAt(prefs: InMemorySharedPreferences, key: String, updatedAtMs: Long) {
        val root = Gson().fromJson(prefs.all[key] as String, JsonObject::class.java)
        root.addProperty("updatedAtMs", updatedAtMs)
        prefs.edit().putString(key, root.toString()).commit()
    }
}
