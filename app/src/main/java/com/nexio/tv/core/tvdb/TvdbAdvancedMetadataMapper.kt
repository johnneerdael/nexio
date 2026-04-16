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
import javax.inject.Inject

/**
 * Maps TVDB advanced series metadata (characters, companies, networks, genres,
 * content ratings) into existing domain surfaces without adding new UI sections.
 *
 * Mapping rules follow decisions D-09, D-10, D-11, D-12 from Phase 9 context.
 */
class TvdbAdvancedMetadataMapper @Inject constructor() {

    /**
     * Maps advanced metadata from a [TvdbSeriesExtendedRecord] into domain types.
     *
     * @param series the TVDB extended series record
     * @param preferredCountryCodes ordered list of preferred country codes for content rating selection
     * @return advanced metadata result with cast, companies, networks, genres, and age rating
     */
    fun mapAdvancedMetadata(
        series: TvdbSeriesExtendedRecord,
        preferredCountryCodes: List<String>
    ): TvdbAdvancedMetadata {
        val castMembers = mapCharacters(series.characters.orEmpty())
        val networks = mapNetworks(series.originalNetwork, series.latestNetwork)
        val networkNames = networks.map { it.name.lowercase() }.toSet()
        val productionCompanies = mapCompanies(series.companies.orEmpty(), networkNames)
        val genres = mapGenres(series.genres.orEmpty())
        val ageRating = selectContentRating(series.contentRatings.orEmpty(), preferredCountryCodes)

        return TvdbAdvancedMetadata(
            castMembers = castMembers,
            productionCompanies = productionCompanies,
            networks = networks,
            genres = genres,
            ageRating = ageRating
        )
    }

    /**
     * Maps TVDB characters to [MetaCastMember] list.
     *
     * - Uses personName as MetaCastMember.name (blank names omitted)
     * - Uses character name (the `name` field) as MetaCastMember.character
     * - Uses personImgURL as MetaCastMember.photo
     * - Sorts by `sort` ascending, null sort last
     * - MetaCastMember.tmdbId remains null for TVDB characters
     */
    private fun mapCharacters(characters: List<TvdbCharacterRecord>): List<MetaCastMember> {
        return characters
            .filter { !it.personName.trimmed().isNullOrBlank() }
            .sortedWith(compareBy(nullsLast<Int>()) { it.sort })
            .map { character ->
                MetaCastMember(
                    name = character.personName.trimmed()!!,
                    character = character.name.trimmed() ?: character.peopleType.trimmed()?.toCreditRoleLabel(),
                    photo = character.personImgURL.trimmed(),
                    tmdbId = null,
                    tvdbPeopleId = character.peopleId
                )
            }
    }

    /**
     * Maps originalNetwork and latestNetwork into [MetaCompany] list with NETWORK kind.
     * Distinct by case-insensitive name; originalNetwork first, then latestNetwork.
     */
    private fun mapNetworks(
        originalNetwork: TvdbCompanyRecord?,
        latestNetwork: TvdbCompanyRecord?
    ): List<MetaCompany> {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<MetaCompany>()

        listOfNotNull(originalNetwork, latestNetwork).forEach { network ->
            val name = network.name.trimmed() ?: return@forEach
            val key = name.lowercase()
            if (seen.add(key)) {
                result.add(
                    MetaCompany(
                        name = name,
                        kind = MetaCompanyKind.NETWORK
                    )
                )
            }
        }

        return result
    }

    /**
     * Maps TVDB companies into [MetaCompany] list with COMPANY kind.
     * Distinct by case-insensitive name; excludes names already present in networks.
     */
    private fun mapCompanies(
        companies: List<TvdbCompanyExtendedRecord>,
        networkNames: Set<String>
    ): List<MetaCompany> {
        val seen = networkNames.toMutableSet()
        val result = mutableListOf<MetaCompany>()

        companies.forEach { company ->
            val name = company.name.trimmed() ?: return@forEach
            val key = name.lowercase()
            if (seen.add(key)) {
                result.add(
                    MetaCompany(
                        name = name,
                        kind = MetaCompanyKind.COMPANY
                    )
                )
            }
        }

        return result
    }

    /**
     * Maps TVDB genres to a list of nonblank genre names, distinct preserving TVDB order.
     */
    private fun mapGenres(genres: List<TvdbGenreRecord>): List<String> {
        val seen = mutableSetOf<String>()
        return genres.mapNotNull { genre ->
            val name = genre.name.trimmed() ?: return@mapNotNull null
            if (seen.add(name.lowercase())) name else null
        }
    }

    /**
     * Selects a content rating by preferred country codes.
     *
     * Priority: matching preferredCountryCodes (in order), then "us"/"usa", then first nonblank.
     */
    private fun selectContentRating(
        contentRatings: List<TvdbContentRating>,
        preferredCountryCodes: List<String>
    ): String? {
        if (contentRatings.isEmpty()) return null

        val usable = contentRatings.filter { !it.name.trimmed().isNullOrBlank() }
        if (usable.isEmpty()) return null

        // Try preferred country codes in order
        for (code in preferredCountryCodes) {
            val match = usable.firstOrNull { it.country?.trim()?.equals(code.trim(), ignoreCase = true) == true }
            if (match != null) return match.name.trimmed()
        }

        // Try US fallback
        val usFallback = usable.firstOrNull { country ->
            val c = country.country?.trim()?.lowercase()
            c == "us" || c == "usa"
        }
        if (usFallback != null) return usFallback.name.trimmed()

        // First nonblank
        return usable.firstOrNull()?.name.trimmed()
    }

    private fun String?.trimmed(): String? = this?.trim()?.takeIf { it.isNotBlank() }

    private fun String.toCreditRoleLabel(): String {
        val normalized = trim().lowercase()
        return when {
            "creator" in normalized || "created by" in normalized -> "Creator"
            "director" in normalized -> "Director"
            "writer" in normalized -> "Writer"
            else -> trim()
        }
    }
}

/**
 * Result of advanced metadata mapping from TVDB series data.
 */
data class TvdbAdvancedMetadata(
    val castMembers: List<MetaCastMember> = emptyList(),
    val productionCompanies: List<MetaCompany> = emptyList(),
    val networks: List<MetaCompany> = emptyList(),
    val genres: List<String> = emptyList(),
    val ageRating: String? = null
)
