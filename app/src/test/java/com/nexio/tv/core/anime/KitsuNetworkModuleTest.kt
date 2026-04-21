package com.nexio.tv.core.anime

import com.nexio.tv.data.remote.api.KitsuApi
import com.nexio.tv.data.remote.api.KitsuAuthApi
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.http.GET
import retrofit2.http.POST

class KitsuNetworkModuleTest {
    @Test
    fun `kitsu api exposes expected endpoints`() {
        val anime = KitsuApi::class.java.methods.first { it.name == "getAnime" }
        val episodes = KitsuApi::class.java.methods.first { it.name == "getAnimeEpisodes" }
        val animeCharacters = KitsuApi::class.java.methods.first { it.name == "getAnimeCharacters" }
        val animeStaff = KitsuApi::class.java.methods.first { it.name == "getAnimeStaff" }
        val animeProductions = KitsuApi::class.java.methods.first { it.name == "getAnimeProductions" }
        val mediaRelationships = KitsuApi::class.java.methods.first { it.name == "getAnimeMediaRelationships" }
        val installments = KitsuApi::class.java.methods.first { it.name == "getAnimeInstallments" }
        val characters = KitsuApi::class.java.methods.first { it.name == "getCharacters" }
        val castings = KitsuApi::class.java.methods.first { it.name == "getCastings" }
        val people = KitsuApi::class.java.methods.first { it.name == "getPeople" }
        val producers = KitsuApi::class.java.methods.first { it.name == "getProducers" }
        val franchises = KitsuApi::class.java.methods.first { it.name == "getFranchises" }
        val castingsByMedia = KitsuApi::class.java.methods.first { it.name == "getCastingsByMedia" }

        assertEquals("anime/{id}", anime.getAnnotation(GET::class.java)?.value)
        assertEquals("anime/{id}/episodes", episodes.getAnnotation(GET::class.java)?.value)
        assertEquals("anime/{id}/anime-characters", animeCharacters.getAnnotation(GET::class.java)?.value)
        assertEquals("anime/{id}/anime-staff", animeStaff.getAnnotation(GET::class.java)?.value)
        assertEquals("anime/{id}/anime-productions", animeProductions.getAnnotation(GET::class.java)?.value)
        assertEquals("anime/{id}/media-relationships", mediaRelationships.getAnnotation(GET::class.java)?.value)
        assertEquals("anime/{id}/installments", installments.getAnnotation(GET::class.java)?.value)
        assertEquals("characters", characters.getAnnotation(GET::class.java)?.value)
        assertEquals("castings", castings.getAnnotation(GET::class.java)?.value)
        assertEquals("people", people.getAnnotation(GET::class.java)?.value)
        assertEquals("producers", producers.getAnnotation(GET::class.java)?.value)
        assertEquals("franchises", franchises.getAnnotation(GET::class.java)?.value)
        assertEquals("castings", castingsByMedia.getAnnotation(GET::class.java)?.value)
    }

    @Test
    fun `kitsu oauth api exposes token endpoint`() {
        val token = KitsuAuthApi::class.java.methods.first { it.name == "token" }

        assertEquals("token", token.getAnnotation(POST::class.java)?.value)
    }
}
