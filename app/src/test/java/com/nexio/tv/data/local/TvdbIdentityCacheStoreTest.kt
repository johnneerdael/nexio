package com.nexio.tv.data.local

import android.content.Context
import com.nexio.tv.core.tvdb.TvdbRemoteIdSource
import com.nexio.tv.testutil.InMemorySharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvdbIdentityCacheStoreTest {

    @Test
    fun `read ignores legacy identity cache schema`() {
        val prefs = InMemorySharedPreferences()
        val store = TvdbIdentityCacheStore(context = mockContext(prefs))
        prefs.edit().putString(
            "tvdb_identity::IMDB:tt0944947",
            """
            {
              "schemaVersion": 1,
              "value": {
                "tvdbId": 121361,
                "remoteIds": {
                  "IMDB": ["tt0944947"]
                }
              }
            }
            """.trimIndent()
        ).apply()

        assertNull(store.read(TvdbRemoteIdSource.IMDB, "tt0944947"))
    }

    @Test
    fun `read rebuilds remote id map with enum keys from raw JSON`() {
        val prefs = InMemorySharedPreferences()
        val store = TvdbIdentityCacheStore(context = mockContext(prefs))
        prefs.edit().putString(
            "tvdb_identity::IMDB:tt0944947",
            """
            {
              "schemaVersion": 2,
              "value": {
                "tvdbId": 121361,
                "name": "Game of Thrones",
                "year": "2011",
                "remoteIds": {
                  "TVDB": ["121361"],
                  "IMDB": ["tt0944947"],
                  "TheMovieDB.com": ["1399"],
                  "official site": ["https://www.hbo.com/game-of-thrones/"]
                }
              }
            }
            """.trimIndent()
        ).apply()

        val identity = store.read(TvdbRemoteIdSource.IMDB, "tt0944947")

        assertEquals(121361, identity?.tvdbId)
        assertEquals(setOf("tt0944947"), identity?.remoteIds?.get(TvdbRemoteIdSource.IMDB))
        assertEquals(setOf("1399"), identity?.remoteIds?.get(TvdbRemoteIdSource.TMDB))
        assertEquals(
            setOf("https://www.hbo.com/game-of-thrones"),
            identity?.remoteIds?.get(TvdbRemoteIdSource.OFFICIAL_SITE)
        )
    }

    private fun mockContext(prefs: InMemorySharedPreferences): Context {
        return mockk {
            every { getSharedPreferences("tvdb_identity_cache_v1", Context.MODE_PRIVATE) } returns prefs
        }
    }
}
