package com.nexio.tv.ui.screens.settings

import com.nexio.tv.data.repository.MDBListListOption
import com.nexio.tv.data.repository.TraktListSource
import com.nexio.tv.data.repository.TraktPopularListOption
import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogListSearchFilterTest {

    @Test
    fun `filterTraktPopularLists returns all items for blank query`() {
        val lists = listOf(
            traktListOption(
                key = "featured-anime",
                title = "Featured Anime",
                userId = "otaku",
                listId = "anime"
            ),
            traktListOption(
                key = "award-winners",
                title = "Award Winners",
                userId = "cinephile",
                listId = "awards"
            )
        )

        val filtered = filterTraktPopularLists(lists, "   ")

        assertEquals(lists, filtered)
    }

    @Test
    fun `filterTraktPopularLists matches title user id list id and key case insensitively`() {
        val anime = traktListOption(
            key = "featured-anime",
            title = "Featured Anime",
            userId = "otaku",
            listId = "anime"
        )
        val awards = traktListOption(
            key = "award-winners",
            title = "Award Winners",
            userId = "cinephile",
            listId = "awards"
        )
        val lists = listOf(anime, awards)

        assertEquals(listOf(anime), filterTraktPopularLists(lists, "ANIME"))
        assertEquals(listOf(awards), filterTraktPopularLists(lists, "phile"))
        assertEquals(listOf(awards), filterTraktPopularLists(lists, "award"))
        assertEquals(listOf(anime), filterTraktPopularLists(lists, "featured-"))
    }

    @Test
    fun `filterTraktPopularLists includes personal user lists`() {
        val lists = listOf(
            TraktPopularListOption(
                key = "me/favorite-sci-fi",
                userId = "me",
                listId = "favorite-sci-fi",
                catalogIdBase = "trakt_list_me_favorite_sci_fi",
                title = "Favorite Sci-Fi",
                itemCount = 12,
                source = TraktListSource.PERSONAL
            ),
            TraktPopularListOption(
                key = "community/best-of-2026",
                userId = "community",
                listId = "best-of-2026",
                catalogIdBase = "trakt_list_community_best_of_2026",
                title = "Best of 2026",
                itemCount = 40,
                source = TraktListSource.POPULAR
            )
        )

        val filtered = filterTraktPopularLists(lists, "sci")

        assertEquals(listOf("me/favorite-sci-fi"), filtered.map { it.key })
    }

    @Test
    fun `filterMdbListTopLists returns all items for blank query`() {
        val lists = listOf(
            mdbListOption(
                key = "top-anime",
                title = "Top Anime",
                owner = "otaku",
                listId = "anime"
            ),
            mdbListOption(
                key = "top-thrillers",
                title = "Top Thrillers",
                owner = "cinephile",
                listId = "thrillers"
            )
        )

        val filtered = filterMDBListTopLists(lists, "")

        assertEquals(lists, filtered)
    }

    @Test
    fun `filterMdbListTopLists matches title owner and list id case insensitively`() {
        val anime = mdbListOption(
            key = "top-anime",
            title = "Top Anime",
            owner = "otaku",
            listId = "anime"
        )
        val thrillers = mdbListOption(
            key = "top-thrillers",
            title = "Top Thrillers",
            owner = "cinephile",
            listId = "thrillers"
        )
        val lists = listOf(anime, thrillers)

        assertEquals(listOf(anime), filterMDBListTopLists(lists, "ANIME"))
        assertEquals(listOf(thrillers), filterMDBListTopLists(lists, "phile"))
        assertEquals(listOf(thrillers), filterMDBListTopLists(lists, "thrillers"))
    }

    private fun traktListOption(
        key: String,
        title: String,
        userId: String,
        listId: String
    ) = TraktPopularListOption(
        key = key,
        userId = userId,
        listId = listId,
        catalogIdBase = key,
        title = title,
        itemCount = 24
    )

    private fun mdbListOption(
        key: String,
        title: String,
        owner: String,
        listId: String
    ) = MDBListListOption(
        key = key,
        owner = owner,
        listId = listId,
        title = title,
        itemCount = 24,
        isPersonal = false
    )
}
