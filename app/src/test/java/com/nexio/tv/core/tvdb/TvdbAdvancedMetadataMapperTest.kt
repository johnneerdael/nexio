package com.nexio.tv.core.tvdb

import com.nexio.tv.data.remote.api.TvdbCharacterRecord
import com.nexio.tv.data.remote.api.TvdbCompanyExtendedRecord
import com.nexio.tv.data.remote.api.TvdbCompanyRecord
import com.nexio.tv.data.remote.api.TvdbContentRating
import com.nexio.tv.data.remote.api.TvdbGenreRecord
import com.nexio.tv.data.remote.api.TvdbSeriesExtendedRecord
import com.nexio.tv.domain.model.MetaCastMember
import com.nexio.tv.domain.model.MetaCompany
import com.nexio.tv.domain.model.MetaCompanyKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for META-05: TVDB advanced metadata mapping.
 *
 * Validates mapping of TVDB characters, companies, networks, genres, and content
 * ratings into existing Meta surfaces via [TvdbAdvancedMetadataMapper].
 */
class TvdbAdvancedMetadataMapperTest {

    private val mapper = TvdbAdvancedMetadataMapper()

    @Test
    fun `maps characters companies networks genres and content ratings`() {
        val series = TvdbSeriesExtendedRecord(
            id = 73255,
            name = "Fringe",
            characters = listOf(
                TvdbCharacterRecord(
                    personName = "Anna Torv",
                    name = "Olivia Dunham",
                    personImgURL = "https://artworks.thetvdb.com/fake/anna_torv.jpg",
                    type = 3,
                    sort = 1
                ),
                TvdbCharacterRecord(
                    personName = "Joshua Jackson",
                    name = "Peter Bishop",
                    personImgURL = "https://artworks.thetvdb.com/fake/joshua_jackson.jpg",
                    type = 3,
                    sort = 2
                )
            ),
            companies = listOf(
                TvdbCompanyExtendedRecord(
                    name = "Bad Robot Productions",
                    primaryCompanyType = 3
                )
            ),
            originalNetwork = TvdbCompanyRecord(name = "FOX"),
            latestNetwork = TvdbCompanyRecord(name = "FOX"),
            genres = listOf(
                TvdbGenreRecord(name = "Science Fiction"),
                TvdbGenreRecord(name = "Drama"),
                TvdbGenreRecord(name = "Mystery")
            ),
            contentRatings = listOf(
                TvdbContentRating(name = "TV-14", country = "usa"),
                TvdbContentRating(name = "12", country = "gbr")
            )
        )

        val result = mapper.mapAdvancedMetadata(series, listOf("usa"))

        // Characters map to MetaCastMember
        assertEquals(2, result.castMembers.size)
        assertEquals(
            MetaCastMember(
                name = "Anna Torv",
                character = "Olivia Dunham",
                photo = "https://artworks.thetvdb.com/fake/anna_torv.jpg",
                tmdbId = null,
                provider = "tvdb"
            ),
            result.castMembers[0]
        )
        assertEquals(
            MetaCastMember(
                name = "Joshua Jackson",
                character = "Peter Bishop",
                photo = "https://artworks.thetvdb.com/fake/joshua_jackson.jpg",
                tmdbId = null,
                provider = "tvdb"
            ),
            result.castMembers[1]
        )

        // Networks from originalNetwork/latestNetwork, distinct
        assertEquals(1, result.networks.size)
        assertEquals(MetaCompany(name = "FOX", kind = MetaCompanyKind.NETWORK, provider = "tvdb"), result.networks[0])

        // Production companies exclude network names
        assertEquals(1, result.productionCompanies.size)
        assertEquals(
            MetaCompany(name = "Bad Robot Productions", kind = MetaCompanyKind.COMPANY, provider = "tvdb"),
            result.productionCompanies[0]
        )

        // Genres nonblank, distinct preserving order
        assertEquals(listOf("Science Fiction", "Drama", "Mystery"), result.genres)

        // Content rating prefers usa
        assertEquals("TV-14", result.ageRating)
    }

    @Test
    fun `blank nullable advanced metadata is omitted safely`() {
        val series = TvdbSeriesExtendedRecord(
            id = 99999,
            name = "Blank Show",
            characters = listOf(
                TvdbCharacterRecord(personName = null, name = "   ", personImgURL = null, type = 3),
                TvdbCharacterRecord(personName = "", name = null, personImgURL = "", type = null),
                TvdbCharacterRecord(personName = "  ", name = "Role", personImgURL = "http://img", type = 3)
            ),
            companies = listOf(
                TvdbCompanyExtendedRecord(name = null, primaryCompanyType = null),
                TvdbCompanyExtendedRecord(name = "  ", primaryCompanyType = 1)
            ),
            originalNetwork = null,
            latestNetwork = TvdbCompanyRecord(name = "  "),
            genres = listOf(
                TvdbGenreRecord(name = ""),
                TvdbGenreRecord(name = "  "),
                TvdbGenreRecord(name = "Drama")
            ),
            contentRatings = listOf(
                TvdbContentRating(name = null, country = "usa"),
                TvdbContentRating(name = "", country = "gbr")
            )
        )

        val result = mapper.mapAdvancedMetadata(series, listOf("usa"))

        // D-16: blank/null advanced data produces empty lists and null rating
        // (graceful omission, no UI warning or fallback error state)
        assertEquals("Cast members must be empty list for blank data", emptyList<Any>(), result.castMembers)
        assertEquals("Production companies must be empty list for blank data", emptyList<Any>(), result.productionCompanies)
        assertEquals("Networks must be empty list for blank data", emptyList<Any>(), result.networks)

        // Only nonblank genres survive
        assertEquals(listOf("Drama"), result.genres)

        // Content ratings with blank/null name produce null age rating (not a placeholder or warning)
        assertNull("Age rating must be null for blank content ratings, not a fallback warning", result.ageRating)
    }

    @Test
    fun `content rating prefers locale country before first available rating`() {
        val series = TvdbSeriesExtendedRecord(
            id = 88888,
            name = "Rating Show",
            contentRatings = listOf(
                TvdbContentRating(name = "12", country = "gbr"),
                TvdbContentRating(name = "TV-14", country = "usa"),
                TvdbContentRating(name = "FSK 12", country = "deu"),
                TvdbContentRating(name = "12A", country = "aus")
            )
        )

        // Preferred country found
        val result = mapper.mapAdvancedMetadata(series, listOf("usa"))
        assertEquals("TV-14", result.ageRating)

        // Preferred country not found, falls back to US
        val resultJpn = mapper.mapAdvancedMetadata(series, listOf("jpn"))
        assertEquals("TV-14", resultJpn.ageRating)

        // No US match, non-US preferred found
        val seriesNoUs = TvdbSeriesExtendedRecord(
            id = 88889,
            name = "No US Rating",
            contentRatings = listOf(
                TvdbContentRating(name = "12", country = "gbr"),
                TvdbContentRating(name = "FSK 12", country = "deu")
            )
        )
        val resultGbr = mapper.mapAdvancedMetadata(seriesNoUs, listOf("gbr"))
        assertEquals("12", resultGbr.ageRating)

        // No preferred, no US - falls back to first nonblank
        val resultNone = mapper.mapAdvancedMetadata(seriesNoUs, listOf("jpn"))
        assertEquals("12", resultNone.ageRating)
    }

    @Test
    fun `characters sorted by sort ascending with null sort last`() {
        val series = TvdbSeriesExtendedRecord(
            id = 77777,
            name = "Sort Show",
            characters = listOf(
                TvdbCharacterRecord(personName = "Actor C", name = "Role C", sort = null),
                TvdbCharacterRecord(personName = "Actor A", name = "Role A", sort = 1),
                TvdbCharacterRecord(personName = "Actor B", name = "Role B", sort = 3)
            )
        )

        val result = mapper.mapAdvancedMetadata(series, emptyList())

        assertEquals(3, result.castMembers.size)
        assertEquals("Actor A", result.castMembers[0].name)
        assertEquals("Actor B", result.castMembers[1].name)
        assertEquals("Actor C", result.castMembers[2].name) // null sort last
    }

    @Test
    fun `tvdb creator role uses people type when no character name exists`() {
        val series = TvdbSeriesExtendedRecord(
            id = 22222,
            name = "Creator Show",
            characters = listOf(
                TvdbCharacterRecord(
                    personName = "Craig Mazin",
                    name = null,
                    peopleType = "Creator",
                    sort = 0
                ),
                TvdbCharacterRecord(
                    personName = "Pedro Pascal",
                    name = "Joel Miller",
                    peopleType = "Actor",
                    sort = 1
                )
            )
        )

        val result = mapper.mapAdvancedMetadata(series, emptyList())

        assertEquals(2, result.castMembers.size)
        assertEquals("Craig Mazin", result.castMembers[0].name)
        assertEquals("Creator", result.castMembers[0].character)
        assertEquals("Pedro Pascal", result.castMembers[1].name)
        assertEquals("Joel Miller", result.castMembers[1].character)
    }

    @Test
    fun `companies exclude names already in networks case insensitive`() {
        val series = TvdbSeriesExtendedRecord(
            id = 66666,
            name = "Overlap Show",
            originalNetwork = TvdbCompanyRecord(name = "HBO"),
            latestNetwork = TvdbCompanyRecord(name = "hbo"), // duplicate by case
            companies = listOf(
                TvdbCompanyExtendedRecord(name = "HBO", primaryCompanyType = 1), // duplicate of network
                TvdbCompanyExtendedRecord(name = "Warner Bros", primaryCompanyType = 3)
            )
        )

        val result = mapper.mapAdvancedMetadata(series, emptyList())

        // Only one network (HBO) despite case variant
        assertEquals(1, result.networks.size)
        assertEquals("HBO", result.networks[0].name)
        assertEquals(MetaCompanyKind.NETWORK, result.networks[0].kind)

        // Only Warner Bros - HBO excluded as network duplicate
        assertEquals(1, result.productionCompanies.size)
        assertEquals("Warner Bros", result.productionCompanies[0].name)
        assertEquals(MetaCompanyKind.COMPANY, result.productionCompanies[0].kind)
    }

    @Test
    fun `genres deduplicated preserving tvdb order`() {
        val series = TvdbSeriesExtendedRecord(
            id = 55555,
            name = "Dupe Genre Show",
            genres = listOf(
                TvdbGenreRecord(name = "Drama"),
                TvdbGenreRecord(name = "Action"),
                TvdbGenreRecord(name = "drama"), // case-insensitive duplicate
                TvdbGenreRecord(name = "Action") // exact duplicate
            )
        )

        val result = mapper.mapAdvancedMetadata(series, emptyList())

        // "Drama" and "Action" only, preserving first occurrence
        assertEquals(listOf("Drama", "Action"), result.genres)
    }

    @Test
    fun `tmdbId remains null for tvdb characters`() {
        val series = TvdbSeriesExtendedRecord(
            id = 44444,
            name = "tmdbId Test",
            characters = listOf(
                TvdbCharacterRecord(personName = "John Doe", name = "Hero", sort = 1)
            )
        )

        val result = mapper.mapAdvancedMetadata(series, emptyList())

        assertNotNull(result.castMembers.firstOrNull())
        assertNull(result.castMembers[0].tmdbId)
    }

    @Test
    fun `empty series produces empty advanced metadata`() {
        val series = TvdbSeriesExtendedRecord(id = 33333, name = "Empty")

        val result = mapper.mapAdvancedMetadata(series, emptyList())

        assertTrue(result.castMembers.isEmpty())
        assertTrue(result.productionCompanies.isEmpty())
        assertTrue(result.networks.isEmpty())
        assertTrue(result.genres.isEmpty())
        assertNull(result.ageRating)
    }
}
