package com.nexio.tv.data.local

import android.content.Context
import com.google.gson.JsonObject
import com.nexio.tv.core.tmdb.TmdbEnrichment
import com.nexio.tv.data.remote.api.TmdbVideoResult
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.MetaCastMember
import com.nexio.tv.domain.model.MetaCompany
import com.nexio.tv.domain.model.MetaCompanyKind
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.TitleRatingSource
import com.nexio.tv.testutil.InMemorySharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataDiskCacheStoreTest {

    @Test
    fun `home display metadata uses separate namespace from full meta`() {
        val store = MetadataDiskCacheStore(
            context = mockContext(InMemorySharedPreferences())
        )

        store.writeHomeDisplayMetadata(
            itemKey = "movie:tt1",
            languageTag = "en",
            metadata = HomeDisplayMetadata(title = "Localized title", runtime = "50 min")
        )

        val display = store.readCurrentHomeDisplayMetadataForItem("movie:tt1", "en")
        assertEquals("Localized title", display?.title)
        assertEquals("50 min", display?.runtime)
        assertNull(store.readCurrentMetaForItem("movie:tt1", "en"))
    }

    @Test
    fun `shared metadata remains available without legacy home ownership references`() {
        val store = MetadataDiskCacheStore(
            context = mockContext(InMemorySharedPreferences())
        )

        store.writeMeta("movie:tt1", "en", "native", meta("tt1"))
        store.writeMeta("movie:tt2", "en", "native", meta("tt2"))

        assertNotNull(store.readMeta("movie:tt1", "en", "native"))
        assertNotNull(store.readMeta("movie:tt2", "en", "native"))
    }

    @Test
    fun `language epoch changes do not invalidate language-keyed metadata`() {
        val store = MetadataDiskCacheStore(
            context = mockContext(InMemorySharedPreferences())
        )

        store.writeMeta("movie:tt1", "en-US", "native", meta("tt1").copy(name = "English title"))
        store.writeMeta("movie:tt1", "nl-NL", "native", meta("tt1").copy(name = "Dutch title"))
        assertEquals("English title", store.readMeta("movie:tt1", "en-US", "native")?.name)
        assertEquals("Dutch title", store.readMeta("movie:tt1", "nl-NL", "native")?.name)

        store.bumpLanguageEpoch()
        val removedImageUrls = store.removeEntriesFromStaleEpochs()

        assertEquals("English title", store.readMeta("movie:tt1", "en-US", "native")?.name)
        assertEquals("Dutch title", store.readMeta("movie:tt1", "nl-NL", "native")?.name)
        assertEquals(emptyList<String>(), removedImageUrls)
    }

    @Test
    fun `readMeta ignores cached entries from before current schema version`() {
        val prefs = InMemorySharedPreferences()
        val store = MetadataDiskCacheStore(context = mockContext(prefs))
        prefs.edit().putString(
            "meta::movie:tt1::en::native",
            """
            {
              "value": {
                "id": "tt1",
                "type": "MOVIE",
                "rawType": "movie",
                "name": "Legacy Movie",
                "poster": null,
                "posterShape": "POSTER",
                "background": null,
                "logo": null,
                "description": null,
                "releaseInfo": null,
                "imdbRating": null,
                "genres": [],
                "runtime": null,
                "director": [],
                "writer": [],
                "cast": [],
                "castMembers": [],
                "videos": [],
                "productionCompanies": [],
                "networks": [],
                "ageRating": null,
                "country": null,
                "awards": null,
                "language": null,
                "links": [],
                "trailerYtIds": ["legacyTrailer1"]
              },
              "metaSchemaVersion": 2,
              "languageEpoch": 0,
              "updatedAtMs": ${System.currentTimeMillis()}
            }
            """.trimIndent()
        ).apply()

        assertNull(store.readMeta("movie:tt1", "en", "native"))
    }

    @Test
    fun `readMeta ignores active poster provider entries without matching poster provider tag`() {
        val prefs = InMemorySharedPreferences()
        val store = MetadataDiskCacheStore(context = mockContext(prefs))

        store.writeMeta("movie:tt1", "en", "RPDB:12345", meta("tt1"))
        store.flushPendingWritesForTest()

        assertNull(store.readMeta("movie:tt1", "en", "RPDB:12345"))
    }

    @Test
    fun `readMeta tolerates current schema entry without rating source`() {
        val prefs = InMemorySharedPreferences()
        val store = MetadataDiskCacheStore(context = mockContext(prefs))
        prefs.edit().putString(
            "meta::movie:tt1::en::native",
            """
            {
              "value": {
                "id": "tt1",
                "type": "MOVIE",
                "rawType": "movie",
                "name": "Legacy Movie",
                "poster": null,
                "posterShape": "POSTER",
                "background": null,
                "logo": null,
                "description": null,
                "releaseInfo": null,
                "imdbRating": null,
                "genres": [],
                "runtime": null,
                "director": [],
                "writer": [],
                "cast": [],
                "castMembers": [],
                "videos": [],
                "productionCompanies": [],
                "networks": [],
                "ageRating": null,
                "country": null,
                "awards": null,
                "language": null,
                "links": [],
                "trailerYtIds": []
              },
              "metaSchemaVersion": 4,
              "languageEpoch": 0,
              "updatedAtMs": ${System.currentTimeMillis()}
            }
            """.trimIndent()
        ).apply()

        val meta = store.readMeta("movie:tt1", "en", "native")

        meta.hashCode()
        assertNull(meta?.ratingSource)
    }

    @Test
    fun `read TMDB enrichment tolerates legacy cache without rating source`() {
        val prefs = InMemorySharedPreferences()
        val store = MetadataDiskCacheStore(context = mockContext(prefs))
        prefs.edit().putString(
            "tmdb::movie:550::en-US::native",
            """
            {
              "value": {
                "localizedTitle":"Fight Club",
                "description":null,
                "genres":[],
                "backdrop":null,
                "logo":null,
                "poster":null,
                "directorMembers":[],
                "writerMembers":[],
                "castMembers":[],
                "releaseInfo":"1999",
                "rating":8.4,
                "runtimeMinutes":139,
                "director":[],
                "writer":[],
                "productionCompanies":[],
                "networks":[],
                "ageRating":null,
                "countries":null,
                "language":"en",
                "collectionId":null,
                "collectionName":null
              },
              "tmdbSchemaVersion": 2,
              "languageEpoch": 0,
              "updatedAtMs": ${System.currentTimeMillis()}
            }
            """.trimIndent()
        ).commit()

        val enrichment = store.readTmdbEnrichment("movie:550", "en-US", "native")

        enrichment.hashCode()
        assertEquals(TitleRatingSource.TMDB, enrichment?.ratingSource)
    }

    @Test
    fun `readMeta restores active poster provider entries with matching poster provider tag`() {
        val prefs = InMemorySharedPreferences()
        val store = MetadataDiskCacheStore(context = mockContext(prefs))
        val meta = meta("tt1").copy(posterProviderTag = "rpdb")

        store.writeMeta("movie:tt1", "en", "RPDB:12345", meta)
        store.flushPendingWritesForTest()

        assertEquals(meta, store.readMeta("movie:tt1", "en", "RPDB:12345"))
    }

    @Test
    fun `writeMeta sanitizes raw premium poster urls from persisted JSON`() {
        val prefs = InMemorySharedPreferences()
        val store = MetadataDiskCacheStore(context = mockContext(prefs))
        val meta = meta("tt1").copy(
            poster = "https://api.ratingposterdb.com/secret/imdb/poster-default/tt1.jpg",
            posterProviderTag = "rpdb"
        )

        store.writeMeta("movie:tt1", "en", "RPDB:12345", meta)
        store.flushPendingWritesForTest()

        val raw = prefs.all.getValue("meta::movie:tt1::en::RPDB:12345") as String
        assertFalse(raw.contains("api.ratingposterdb.com"))
        assertFalse(raw.contains("secret"))
        assertFalse(raw.contains("posterProviderTag"))
        assertNull(store.readMeta("movie:tt1", "en", "RPDB:12345"))
    }

    @Test
    fun `readMeta sanitizes legacy integration poster refs from cached JSON`() {
        val prefs = InMemorySharedPreferences()
        val store = MetadataDiskCacheStore(context = mockContext(prefs))
        prefs.edit().putString(
            "meta::movie:tt1::en::native",
            """
            {
              "value": {
                "id": "tt1",
                "type": "MOVIE",
                "rawType": "movie",
                "name": "Legacy Movie",
                "poster": "integration-poster://fetch?provider=RPDB",
                "posterShape": "POSTER",
                "background": null,
                "logo": null,
                "description": null,
                "releaseInfo": "2024",
                "imdbRating": 8.0,
                "ratingSource": "IMDB",
                "genres": [],
                "runtime": "120m",
                "director": [],
                "writer": [],
                "cast": [],
                "castMembers": [],
                "videos": [],
                "productionCompanies": [],
                "networks": [],
                "country": null,
                "awards": null,
                "language": null,
                "links": [],
                "trailerYtIds": [],
                "posterProviderTag": "rpdb"
              },
              "languageEpoch": 0,
              "metaSchemaVersion": 4,
              "updatedAtMs": 1700000000000
            }
            """.trimIndent()
        ).commit()

        val restored = store.readMeta("movie:tt1", "en", "native")

        assertNotNull(restored)
        assertNull(restored?.poster)
        assertNull(restored?.posterProviderTag)
    }

    @Test
    fun `readTmdbEnrichment preserves legacy companies cached before tmdbId and kind fields existed`() {
        val prefs = InMemorySharedPreferences()
        val context = mockContext(prefs)
        val store = MetadataDiskCacheStore(context = context)
        val key = "tmdb::123:MOVIE::en::native"
        prefs.edit().putString(
            key,
            """
            {
              "value": {
                "localizedTitle": "Legacy Movie",
                "description": null,
                "genres": [],
                "backdrop": null,
                "logo": null,
                "poster": null,
                "directorMembers": [],
                "writerMembers": [],
                "castMembers": [],
                "releaseInfo": null,
                "rating": null,
                "runtimeMinutes": null,
                "director": [],
                "writer": [],
                "productionCompanies": [
                  {
                    "name": "Lucasfilm Ltd.",
                    "logo": "https://image.tmdb.org/t/p/w300/logo.png"
                  }
                ],
                "networks": [
                  {
                    "name": "HBO",
                    "logo": "https://image.tmdb.org/t/p/w300/network.png"
                  }
                ],
                "ageRating": null,
                "countries": null,
                "language": null,
                "collectionId": null,
                "collectionName": null
              },
              "languageEpoch": 0,
              "tmdbSchemaVersion": 2,
              "updatedAtMs": ${System.currentTimeMillis()}
            }
            """.trimIndent()
        ).apply()

        val enrichment = store.readTmdbEnrichment("123:MOVIE", "en", "native")

        assertNotNull(enrichment)
        assertEquals(1, enrichment?.productionCompanies?.size)
        assertEquals("Lucasfilm Ltd.", enrichment?.productionCompanies?.single()?.name)
        assertEquals(MetaCompanyKind.COMPANY, enrichment?.productionCompanies?.single()?.kind)
        assertEquals(1, enrichment?.networks?.size)
        assertEquals("HBO", enrichment?.networks?.single()?.name)
        assertEquals(MetaCompanyKind.NETWORK, enrichment?.networks?.single()?.kind)
    }

    @Test
    fun `readTmdbEnrichment ignores cached entries from before current schema version`() {
        val prefs = InMemorySharedPreferences()
        val context = mockContext(prefs)
        val store = MetadataDiskCacheStore(context = context)
        prefs.edit().putString(
            "tmdb::123:MOVIE::en::native",
            """
            {
              "value": {
                "localizedTitle": "Legacy Movie",
                "description": null,
                "genres": [],
                "backdrop": null,
                "logo": null,
                "poster": null,
                "directorMembers": [],
                "writerMembers": [],
                "castMembers": [],
                "productionCompanies": [],
                "networks": [],
                "director": [],
                "writer": [],
                "releaseInfo": null,
                "rating": null,
                "runtimeMinutes": null,
                "ageRating": null,
                "countries": null,
                "language": null,
                "collectionId": null,
                "collectionName": null
              },
              "tmdbSchemaVersion": 1,
              "languageEpoch": 0,
              "updatedAtMs": 1
            }
            """.trimIndent()
        ).apply()

        val enrichment = store.readTmdbEnrichment("123:MOVIE", "en", "native")

        assertNull(enrichment)
    }

    @Test
    fun `expired tmdb enrichment cache entry is ignored`() {
        val prefs = InMemorySharedPreferences()
        val store = MetadataDiskCacheStore(context = mockContext(prefs))

        store.writeTmdbEnrichment(
            tmdbKey = "550:MOVIE",
            languageTag = "en-US",
            providerToken = "native",
            enrichment = tmdbEnrichment("Fight Club")
        )
        store.flushPendingWritesForTest()
        rewriteUpdatedAt(prefs, "tmdb::550:MOVIE::en-US::native", 0L)

        assertNull(store.readTmdbEnrichment("550:MOVIE", "en-US", "native"))
    }

    @Test
    fun `mergeTmdbEnrichmentCollections preserves parsed values when raw keys are unavailable`() {
        val parsed = TmdbEnrichment(
            localizedTitle = "Release Build",
            description = null,
            genres = emptyList(),
            backdrop = null,
            logo = null,
            poster = null,
            directorMembers = listOf(
                MetaCastMember(name = "Director", character = "Director", photo = "director-photo", tmdbId = 11)
            ),
            writerMembers = listOf(
                MetaCastMember(name = "Writer", character = "Writer", photo = "writer-photo", tmdbId = 12)
            ),
            castMembers = listOf(
                MetaCastMember(name = "Actor", character = "Lead", photo = "actor-photo", tmdbId = 13)
            ),
            releaseInfo = null,
            rating = null,
            runtimeMinutes = null,
            director = listOf("Director"),
            writer = listOf("Writer"),
            productionCompanies = listOf(
                MetaCompany(
                    tmdbId = 21,
                    name = "Studio",
                    logo = "studio-logo",
                    kind = MetaCompanyKind.COMPANY
                )
            ),
            networks = listOf(
                MetaCompany(
                    tmdbId = 22,
                    name = "Network",
                    logo = "network-logo",
                    kind = MetaCompanyKind.NETWORK
                )
            ),
            ageRating = null,
            countries = null,
            language = null,
            collectionId = null,
            collectionName = null
        )

        val merged = mergeTmdbEnrichmentCollections(parsed, JsonObject())

        assertEquals(parsed.directorMembers, merged.directorMembers)
        assertEquals(parsed.writerMembers, merged.writerMembers)
        assertEquals(parsed.castMembers, merged.castMembers)
        assertEquals(parsed.productionCompanies, merged.productionCompanies)
        assertEquals(parsed.networks, merged.networks)
    }

    @Test
    fun `read and write tmdb title videos round-trip by media type and language`() {
        val store = MetadataDiskCacheStore(
            context = mockContext(InMemorySharedPreferences())
        )
        val videos = listOf(
            tmdbVideo(key = "trailer-1", type = "Trailer"),
            tmdbVideo(key = "teaser-1", type = "Teaser")
        )

        store.writeTmdbTitleVideos(
            tmdbId = 123,
            mediaType = "movie",
            languageTag = "en-US",
            providerToken = "trailer",
            videos = videos
        )

        assertEquals(
            videos,
            store.readTmdbTitleVideos(
                tmdbId = 123,
                mediaType = "movie",
                languageTag = "en-US",
                providerToken = "trailer"
            )
        )
        assertNull(
            store.readTmdbTitleVideos(
                tmdbId = 123,
                mediaType = "tv",
                languageTag = "en-US",
                providerToken = "trailer"
            )
        )
    }

    @Test
    fun `tmdb title videos remain cached per language after a different profile changes locale`() {
        val store = MetadataDiskCacheStore(
            context = mockContext(InMemorySharedPreferences())
        )
        val englishVideos = listOf(tmdbVideo(key = "english-trailer", type = "Trailer"))
        val dutchVideos = listOf(tmdbVideo(key = "dutch-trailer", type = "Trailer"))

        store.writeTmdbTitleVideos(
            tmdbId = 123,
            mediaType = "movie",
            languageTag = "en-US",
            providerToken = "trailer",
            videos = englishVideos
        )
        store.writeTmdbTitleVideos(
            tmdbId = 123,
            mediaType = "movie",
            languageTag = "nl-NL",
            providerToken = "trailer",
            videos = dutchVideos
        )

        store.bumpLanguageEpoch()

        assertEquals(
            englishVideos,
            store.readTmdbTitleVideos(
                tmdbId = 123,
                mediaType = "movie",
                languageTag = "en-US",
                providerToken = "trailer"
            )
        )
        assertEquals(
            dutchVideos,
            store.readTmdbTitleVideos(
                tmdbId = 123,
                mediaType = "movie",
                languageTag = "nl-NL",
                providerToken = "trailer"
            )
        )
    }

    @Test
    fun `read and write meta round-trip preserves original language`() {
        val store = MetadataDiskCacheStore(
            context = mockContext(InMemorySharedPreferences())
        )

        store.writeMeta(
            itemKey = "movie:tt11",
            languageTag = "en-US",
            providerToken = "native",
            meta = meta("tt11").copy(language = "en")
        )

        assertEquals(
            "en",
            store.readMeta("movie:tt11", "en-US", "native")?.language
        )
    }

    @Test
    fun `read and write meta round-trip preserves runtime fields for movie and episodes`() {
        val store = MetadataDiskCacheStore(
            context = mockContext(InMemorySharedPreferences())
        )
        val meta = meta("show:1").copy(
            runtime = "121",
            videos = listOf(
                com.nexio.tv.domain.model.Video(
                    id = "show:1:1",
                    title = "Episode 1",
                    released = null,
                    thumbnail = null,
                    streams = emptyList(),
                    season = 1,
                    episode = 1,
                    overview = null,
                    runtime = 62
                )
            )
        )

        store.writeMeta(
            itemKey = "series:show:1",
            languageTag = "en-US",
            providerToken = "native",
            meta = meta
        )

        val restored = store.readMeta("series:show:1", "en-US", "native")
        assertEquals("121", restored?.runtime)
        assertEquals(62, restored?.videos?.singleOrNull()?.runtime)
    }

    @Test
    fun `writeTmdbTitleVideos only persists english trailer teaser and recap entries`() {
        val store = MetadataDiskCacheStore(
            context = mockContext(InMemorySharedPreferences())
        )
        val videos = listOf(
            tmdbVideo(key = "hindi-trailer", type = "Trailer", iso6391 = "hi"),
            tmdbVideo(key = "english-trailer", type = "Trailer", iso6391 = "en"),
            tmdbVideo(key = "english-featurette", type = "Featurette", iso6391 = "en"),
            tmdbVideo(key = "empty-language-recap", type = "Recap", iso6391 = ""),
            tmdbVideo(key = "english-teaser", type = "Teaser", iso6391 = "en"),
            tmdbVideo(key = "english-recap", type = "Recap", iso6391 = "en")
        )

        store.writeTmdbTitleVideos(
            tmdbId = 123,
            mediaType = "movie",
            languageTag = "nl-NL",
            providerToken = "trailer",
            videos = videos
        )

        assertEquals(
            listOf(
                tmdbVideo(key = "english-trailer", type = "Trailer", iso6391 = "en"),
                tmdbVideo(key = "english-teaser", type = "Teaser", iso6391 = "en"),
                tmdbVideo(key = "english-recap", type = "Recap", iso6391 = "en")
            ),
            store.readTmdbTitleVideos(
                tmdbId = 123,
                mediaType = "movie",
                languageTag = "nl-NL",
                providerToken = "trailer"
            )
        )
    }

    @Test
    fun `readTmdbTitleVideos ignores expired cache entries`() {
        val prefs = InMemorySharedPreferences()
        val store = MetadataDiskCacheStore(context = mockContext(prefs))
        prefs.edit().putString(
            "tmdb_videos::123::movie::en-US::trailer",
            """
            {
              "value": [
                {
                  "key": "trailer-1",
                  "site": "YouTube",
                  "type": "Trailer",
                  "official": true,
                  "size": 1080,
                  "publishedAt": "2024-01-01T00:00:00Z",
                  "id": "trailer-1"
                }
              ],
              "languageEpoch": 0,
              "tmdbVideoSchemaVersion": 1,
              "updatedAtMs": 1
            }
            """.trimIndent()
        ).apply()

        assertNull(
            store.readTmdbTitleVideos(
                tmdbId = 123,
                mediaType = "movie",
                languageTag = "en-US",
                providerToken = "trailer"
            )
        )
    }

    @Test
    fun `readTmdbTitleVideos ignores cached entries from before current video schema version`() {
        val prefs = InMemorySharedPreferences()
        val store = MetadataDiskCacheStore(context = mockContext(prefs))
        prefs.edit().putString(
            "tmdb_videos::123::movie::en-US::trailer",
            """
            {
              "value": [
                {
                  "key": "trailer-1",
                  "site": "YouTube",
                  "type": "Trailer",
                  "official": true,
                  "size": 1080,
                  "publishedAt": "2024-01-01T00:00:00Z",
                  "id": "trailer-1",
                  "iso6391": "en"
                }
              ],
              "languageEpoch": 0,
              "tmdbVideoSchemaVersion": 1,
              "updatedAtMs": 4102444800000
            }
            """.trimIndent()
        ).apply()

        assertNull(
            store.readTmdbTitleVideos(
                tmdbId = 123,
                mediaType = "movie",
                languageTag = "en-US",
                providerToken = "trailer"
            )
        )
    }

    @Test
    fun `read and write tmdb season videos round-trip by season and language`() {
        val store = MetadataDiskCacheStore(
            context = mockContext(InMemorySharedPreferences())
        )
        val seasonOneVideos = listOf(tmdbVideo(key = "season-1-trailer", type = "Trailer"))
        val seasonTwoVideos = listOf(tmdbVideo(key = "season-2-recap", type = "Recap"))

        store.writeTmdbSeasonVideos(
            tmdbId = 456,
            seasonNumber = 1,
            languageTag = "en-US",
            providerToken = "trailer",
            videos = seasonOneVideos
        )
        store.writeTmdbSeasonVideos(
            tmdbId = 456,
            seasonNumber = 2,
            languageTag = "en-US",
            providerToken = "trailer",
            videos = seasonTwoVideos
        )

        assertEquals(
            seasonOneVideos,
            store.readTmdbSeasonVideos(
                tmdbId = 456,
                seasonNumber = 1,
                languageTag = "en-US",
                providerToken = "trailer"
            )
        )
        assertEquals(
            seasonTwoVideos,
            store.readTmdbSeasonVideos(
                tmdbId = 456,
                seasonNumber = 2,
                languageTag = "en-US",
                providerToken = "trailer"
            )
        )
        assertNull(
            store.readTmdbSeasonVideos(
                tmdbId = 456,
                seasonNumber = 1,
                languageTag = "nl-NL",
                providerToken = "trailer"
            )
        )
    }

    @Test
    fun `writeTmdbSeasonVideos only persists english trailer teaser and recap entries`() {
        val store = MetadataDiskCacheStore(
            context = mockContext(InMemorySharedPreferences())
        )
        val videos = listOf(
            tmdbVideo(key = "season-hi-recap", type = "Recap", iso6391 = "hi"),
            tmdbVideo(key = "season-en-recap", type = "Recap", iso6391 = "en"),
            tmdbVideo(key = "season-en-trailer", type = "Trailer", iso6391 = "en"),
            tmdbVideo(key = "season-en-clip", type = "Clip", iso6391 = "en")
        )

        store.writeTmdbSeasonVideos(
            tmdbId = 456,
            seasonNumber = 2,
            languageTag = "de-DE",
            providerToken = "trailer",
            videos = videos
        )

        assertEquals(
            listOf(
                tmdbVideo(key = "season-en-recap", type = "Recap", iso6391 = "en"),
                tmdbVideo(key = "season-en-trailer", type = "Trailer", iso6391 = "en")
            ),
            store.readTmdbSeasonVideos(
                tmdbId = 456,
                seasonNumber = 2,
                languageTag = "de-DE",
                providerToken = "trailer"
            )
        )
    }

    @Test
    fun `readTmdbSeasonVideos ignores expired cache entries`() {
        val prefs = InMemorySharedPreferences()
        val store = MetadataDiskCacheStore(context = mockContext(prefs))
        prefs.edit().putString(
            "tmdb_season_videos::456::2::en-US::trailer",
            """
            {
              "value": [
                {
                  "key": "season-2-recap",
                  "site": "YouTube",
                  "type": "Recap",
                  "official": true,
                  "size": 1080,
                  "publishedAt": "2024-01-01T00:00:00Z",
                  "id": "season-2-recap"
                }
              ],
              "languageEpoch": 0,
              "tmdbVideoSchemaVersion": 1,
              "updatedAtMs": 1
            }
            """.trimIndent()
        ).apply()

        assertNull(
            store.readTmdbSeasonVideos(
                tmdbId = 456,
                seasonNumber = 2,
                languageTag = "en-US",
                providerToken = "trailer"
            )
        )
    }

    @Test
    fun `readTmdbSeasonVideos ignores cached entries from before current video schema version`() {
        val prefs = InMemorySharedPreferences()
        val store = MetadataDiskCacheStore(context = mockContext(prefs))
        prefs.edit().putString(
            "tmdb_season_videos::456::2::en-US::trailer",
            """
            {
              "value": [
                {
                  "key": "season-2-recap",
                  "site": "YouTube",
                  "type": "Recap",
                  "official": true,
                  "size": 1080,
                  "publishedAt": "2024-01-01T00:00:00Z",
                  "id": "season-2-recap",
                  "iso6391": "en"
                }
              ],
              "languageEpoch": 0,
              "tmdbVideoSchemaVersion": 1,
              "updatedAtMs": 4102444800000
            }
            """.trimIndent()
        ).apply()

        assertNull(
            store.readTmdbSeasonVideos(
                tmdbId = 456,
                seasonNumber = 2,
                languageTag = "en-US",
                providerToken = "trailer"
            )
        )
    }

    @Test
    fun `tvdb reference cache uses tvdb ref namespace`() {
        val prefs = InMemorySharedPreferences()
        val store = MetadataDiskCacheStore(context = mockContext(prefs))

        store.writeTvdbReference("genres", listOf(mapOf("id" to 1, "name" to "Drama")))
        store.flushPendingWritesForTest()

        // Verify the key uses tvdb_ref:: prefix
        val keys = prefs.all.keys
        assertTrue(
            "Expected a key starting with tvdb_ref::genres::",
            keys.any { it.startsWith("tvdb_ref::genres::") }
        )

        // Verify we can read it back
        val result = store.readTvdbReference<Map<String, Any>>("genres")
        assertNotNull(result)
        assertTrue(result!!.isNotEmpty())
    }

    @Test
    fun `tvdb reference cache schema mismatch returns null`() {
        val prefs = InMemorySharedPreferences()
        val store = MetadataDiskCacheStore(context = mockContext(prefs))

        // Manually write a reference entry with a wrong schema version
        prefs.edit().putString(
            "tvdb_ref::genres::data",
            """
            {
              "values": [{"id": 1, "name": "Drama"}],
              "tvdbReferenceSchemaVersion": 999,
              "updatedAtMs": 1
            }
            """.trimIndent()
        ).apply()

        val result = store.readTvdbReference<Map<String, Any>>("genres")
        assertNull(result)
    }

    @Test
    fun `tvdb reference cache never falls back to raw id label`() {
        val prefs = InMemorySharedPreferences()
        val store = MetadataDiskCacheStore(context = mockContext(prefs))

        // Write empty reference data
        store.writeTvdbReference("genres", emptyList<Any>())
        store.flushPendingWritesForTest()

        // Reading should return empty list or null, never raw IDs
        val result = store.readTvdbReference<Map<String, Any>>("genres")
        assertTrue(
            "Should return null or empty list, not raw IDs",
            result == null || result.isEmpty()
        )
    }

    private fun meta(id: String): Meta {
        return Meta(
            id = id,
            type = ContentType.MOVIE,
            rawType = "movie",
            name = "Movie $id",
            poster = "poster$id",
            posterShape = PosterShape.POSTER,
            background = "background$id",
            logo = "logo$id",
            description = "description$id",
            releaseInfo = "2024",
            imdbRating = 8.0f,
            genres = listOf("Drama"),
            runtime = "120m",
            director = emptyList(),
            cast = emptyList(),
            videos = emptyList(),
            country = null,
            awards = null,
            language = null,
            links = emptyList()
        )
    }

    private fun tmdbVideo(key: String, type: String, iso6391: String? = "en"): TmdbVideoResult {
        return TmdbVideoResult(
            key = key,
            iso6391 = iso6391,
            site = "YouTube",
            type = type,
            official = true,
            size = 1080,
            publishedAt = "2024-01-01T00:00:00Z",
            id = key
        )
    }

    private fun tmdbEnrichment(title: String): TmdbEnrichment {
        return TmdbEnrichment(
            localizedTitle = title,
            description = null,
            genres = emptyList(),
            backdrop = null,
            logo = null,
            poster = null,
            directorMembers = emptyList(),
            writerMembers = emptyList(),
            castMembers = emptyList(),
            releaseInfo = null,
            rating = null,
            runtimeMinutes = null,
            director = emptyList(),
            writer = emptyList(),
            productionCompanies = emptyList(),
            networks = emptyList(),
            ageRating = null,
            countries = null,
            language = null,
            collectionId = null,
            collectionName = null
        )
    }

    private fun mockContext(prefs: InMemorySharedPreferences): Context {
        return mockk {
            every { getSharedPreferences("metadata_disk_cache_v1", Context.MODE_PRIVATE) } returns prefs
        }
    }

    private fun rewriteUpdatedAt(prefs: InMemorySharedPreferences, key: String, updatedAtMs: Long) {
        val root = com.google.gson.Gson().fromJson(prefs.all[key] as String, JsonObject::class.java)
        root.addProperty("updatedAtMs", updatedAtMs)
        prefs.edit().putString(key, root.toString()).commit()
    }
}
