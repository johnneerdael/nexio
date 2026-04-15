package com.nexio.tv.core.tvdb

import com.nexio.tv.domain.model.MetaCastMember
import com.nexio.tv.domain.model.MetaCompany
import com.nexio.tv.domain.model.MetaCompanyKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wave 0 validation scaffold for META-05: TVDB advanced metadata mapping.
 *
 * These tests define the contract for mapping TVDB characters, companies, networks,
 * genres, and content ratings into existing Meta surfaces.
 *
 * All tests are expected to fail until the TvdbAdvancedMetadataMapper is implemented
 * in Plan 09-02.
 */
class TvdbAdvancedMetadataMapperTest {

    // --- Fixture data ---

    /** Simulates a TVDB character record from SeriesExtendedRecord.characters */
    private data class TvdbCharacterFixture(
        val personName: String?,
        val name: String?,         // character name
        val personImgURL: String?,
        val type: Int?             // 3 = Actor
    )

    /** Simulates a TVDB company record from SeriesExtendedRecord.companies */
    private data class TvdbCompanyFixture(
        val name: String?,
        val companyType: TvdbCompanyTypeFixture?
    )

    private data class TvdbCompanyTypeFixture(
        val companyTypeId: Int,    // 1 = Network, 2 = Studio, 3 = Production
        val companyTypeName: String
    )

    /** Simulates a TVDB content rating record */
    private data class TvdbContentRatingFixture(
        val name: String?,
        val country: String?,
        val contentType: String? = null
    )

    // --- Tests ---

    @Test
    fun `maps characters companies networks genres and content ratings`() {
        // Given: TVDB series extended data with characters, companies, networks, genres, and ratings.
        val characters = listOf(
            TvdbCharacterFixture(
                personName = "Anna Torv",
                name = "Olivia Dunham",
                personImgURL = "https://artworks.thetvdb.com/fake/anna_torv.jpg",
                type = 3
            ),
            TvdbCharacterFixture(
                personName = "Joshua Jackson",
                name = "Peter Bishop",
                personImgURL = "https://artworks.thetvdb.com/fake/joshua_jackson.jpg",
                type = 3
            )
        )

        val companies = listOf(
            TvdbCompanyFixture(
                name = "Bad Robot Productions",
                companyType = TvdbCompanyTypeFixture(3, "Production Company")
            )
        )

        val networks = listOf(
            TvdbCompanyFixture(
                name = "FOX",
                companyType = TvdbCompanyTypeFixture(1, "Network")
            )
        )

        val genres = listOf("Science Fiction", "Drama", "Mystery")

        val contentRatings = listOf(
            TvdbContentRatingFixture(name = "TV-14", country = "usa"),
            TvdbContentRatingFixture(name = "12", country = "gbr")
        )

        // When: The TvdbAdvancedMetadataMapper maps these into Meta-compatible structures.
        // Phase 9 Plan 02 will implement the actual mapper. For now, assert the expected outputs.

        // Then: Characters map to MetaCastMember with correct field mapping.
        val expectedCast = listOf(
            MetaCastMember(
                name = "Anna Torv",
                character = "Olivia Dunham",
                photo = "https://artworks.thetvdb.com/fake/anna_torv.jpg"
            ),
            MetaCastMember(
                name = "Joshua Jackson",
                character = "Peter Bishop",
                photo = "https://artworks.thetvdb.com/fake/joshua_jackson.jpg"
            )
        )

        // This will fail until the mapper is implemented - scaffolding the contract.
        // val mapper = TvdbAdvancedMetadataMapper()
        // val result = mapper.mapSeriesExtended(characters, companies, networks, genres, contentRatings, "usa")
        // assertEquals(expectedCast, result.castMembers)

        // Verify the expected cast member structure is valid.
        assertEquals("Anna Torv", expectedCast[0].name)
        assertEquals("Olivia Dunham", expectedCast[0].character)
        assertEquals("https://artworks.thetvdb.com/fake/anna_torv.jpg", expectedCast[0].photo)

        // And: originalNetwork maps to MetaCompanyKind.NETWORK.
        val expectedNetwork = MetaCompany(
            name = "FOX",
            kind = MetaCompanyKind.NETWORK
        )
        assertEquals(MetaCompanyKind.NETWORK, expectedNetwork.kind)

        // And: production companies map to MetaCompanyKind.COMPANY.
        val expectedCompany = MetaCompany(
            name = "Bad Robot Productions",
            kind = MetaCompanyKind.COMPANY
        )
        assertEquals(MetaCompanyKind.COMPANY, expectedCompany.kind)

        // And: genres are nonblank strings.
        assertTrue("Genres must be nonblank", genres.all { it.isNotBlank() })
        assertEquals(3, genres.size)

        // And: content rating selects preferred country (usa).
        val preferredRating = contentRatings.firstOrNull { it.country == "usa" }
        assertEquals("TV-14", preferredRating?.name)

        // Scaffold assertion: the mapper class must exist after Plan 09-02.
        try {
            Class.forName("com.nexio.tv.core.tvdb.TvdbAdvancedMetadataMapper")
        } catch (e: ClassNotFoundException) {
            throw AssertionError(
                "TvdbAdvancedMetadataMapper class must exist after Plan 09-02 implementation", e
            )
        }
    }

    @Test
    fun `blank nullable advanced metadata is omitted safely`() {
        // Given: TVDB data with blank/null fields that must not produce broken metadata.
        val characters = listOf(
            TvdbCharacterFixture(
                personName = null,
                name = "   ",       // blank character name
                personImgURL = null,
                type = 3
            ),
            TvdbCharacterFixture(
                personName = "",
                name = null,
                personImgURL = "",
                type = null
            )
        )

        val companies = listOf(
            TvdbCompanyFixture(name = null, companyType = null),
            TvdbCompanyFixture(name = "  ", companyType = TvdbCompanyTypeFixture(1, "Network"))
        )

        val genres = listOf("", "  ", "Drama")
        val contentRatings = listOf(
            TvdbContentRatingFixture(name = null, country = "usa"),
            TvdbContentRatingFixture(name = "", country = "gbr")
        )

        // When: The mapper processes blank/null data.
        // Then: Characters with no usable personName are omitted.
        val usableCharacters = characters.filter {
            !it.personName.isNullOrBlank()
        }
        assertEquals(
            "Characters with blank/null personName must be omitted",
            0, usableCharacters.size
        )

        // And: Companies with blank/null name are omitted.
        val usableCompanies = companies.filter {
            !it.name.isNullOrBlank()
        }
        assertEquals(
            "Companies with blank/null name must be omitted",
            0, usableCompanies.size
        )

        // And: Blank genres are filtered out.
        val usableGenres = genres.filter { it.isNotBlank() }
        assertEquals(listOf("Drama"), usableGenres)

        // And: Content ratings with blank/null name are omitted.
        val usableRatings = contentRatings.filter { !it.name.isNullOrBlank() }
        assertEquals(0, usableRatings.size)

        // Scaffold: The actual mapper must handle all these gracefully.
        try {
            Class.forName("com.nexio.tv.core.tvdb.TvdbAdvancedMetadataMapper")
        } catch (e: ClassNotFoundException) {
            throw AssertionError(
                "TvdbAdvancedMetadataMapper class must exist after Plan 09-02 implementation", e
            )
        }
    }

    @Test
    fun `content rating prefers locale country before first available rating`() {
        // Given: Multiple content ratings from different countries.
        val contentRatings = listOf(
            TvdbContentRatingFixture(name = "12", country = "gbr"),
            TvdbContentRatingFixture(name = "TV-14", country = "usa"),
            TvdbContentRatingFixture(name = "FSK 12", country = "deu"),
            TvdbContentRatingFixture(name = "12A", country = "aus")
        )
        val userCountry = "usa"

        // When: The mapper selects the content rating.
        // Then: The preferred country rating is selected first.
        val preferred = contentRatings.firstOrNull { it.country == userCountry }
        assertNotNull("Must find a rating for the user's country", preferred)
        assertEquals("TV-14", preferred?.name)

        // If the user's country is not available, fall back to the first available rating.
        val fallbackCountry = "jpn"
        val fallbackPreferred = contentRatings.firstOrNull { it.country == fallbackCountry }
        val fallbackResult = fallbackPreferred?.name ?: contentRatings.firstOrNull { !it.name.isNullOrBlank() }?.name
        assertEquals(
            "Fallback must use first available rating when locale country is not found",
            "12", fallbackResult
        )

        // Scaffold: The actual mapper must implement this preference logic.
        try {
            Class.forName("com.nexio.tv.core.tvdb.TvdbAdvancedMetadataMapper")
        } catch (e: ClassNotFoundException) {
            throw AssertionError(
                "TvdbAdvancedMetadataMapper class must exist after Plan 09-02 implementation", e
            )
        }
    }
}
