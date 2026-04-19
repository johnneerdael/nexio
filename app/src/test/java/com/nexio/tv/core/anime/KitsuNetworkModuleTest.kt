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

        assertEquals("anime/{id}", anime.getAnnotation(GET::class.java)?.value)
        assertEquals("anime/{id}/episodes", episodes.getAnnotation(GET::class.java)?.value)
    }

    @Test
    fun `kitsu oauth api exposes token endpoint`() {
        val token = KitsuAuthApi::class.java.methods.first { it.name == "token" }

        assertEquals("token", token.getAnnotation(POST::class.java)?.value)
    }
}
