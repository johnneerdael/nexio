package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.PosterShape
import org.junit.Assert.assertEquals
import org.junit.Test

class MetaRepositoryImplTest {
    @Test
    fun `meta disk aliases include resolved response type when request type differs`() {
        val meta = meta(type = ContentType.SERIES, rawType = "series")

        assertEquals(
            setOf("anime:kitsu:12", "series:kitsu:12"),
            buildMetaDiskAliasKeys(
                candidateType = "anime",
                candidateId = "kitsu:12",
                meta = meta
            )
        )
    }

    private fun meta(type: ContentType, rawType: String): Meta {
        return Meta(
            id = "kitsu:12",
            type = type,
            rawType = rawType,
            name = "One Piece",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList(),
            runtime = null,
            director = emptyList(),
            cast = emptyList(),
            videos = emptyList(),
            country = null,
            awards = null,
            language = null,
            links = emptyList()
        )
    }
}
