package com.nexio.tv.data.repository

import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ContentIdentity
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ContinueWatchingItemKeysTest {
    @Test
    fun `episode key uses canonical provider and coordinate`() {
        val identity = ContentIdentity(
            canonicalProvider = ProviderId.TVDB,
            canonicalId = "393268",
            providerIds = ProviderIds(tvdb = "393268", imdb = "tt9794044")
        )

        assertEquals(
            "series:tvdb:393268:s2e1",
            ContinueWatchingItemKeys.episodeKey(MetadataMediaKind.SERIES, identity, 2, 1, "tvdb:393268")
        )
    }

    @Test
    fun `unknown identity parent keys do not collide`() {
        val identity = ContentIdentity(
            canonicalProvider = null,
            canonicalId = null,
            providerIds = ProviderIds()
        )

        val first = ContinueWatchingItemKeys.parentKey(MetadataMediaKind.SERIES, identity, "addon-a:show")
        val second = ContinueWatchingItemKeys.parentKey(MetadataMediaKind.SERIES, identity, "addon-b:show")

        assertNotEquals(first, second)
        assertEquals(true, first.startsWith("series:raw:"))
        assertEquals(true, second.startsWith("series:raw:"))
    }

    @Test
    fun `unknown identity parent key rejects blank fallback raw id`() {
        val identity = ContentIdentity(
            canonicalProvider = null,
            canonicalId = null,
            providerIds = ProviderIds()
        )

        assertThrows(IllegalArgumentException::class.java) {
            ContinueWatchingItemKeys.parentKey(MetadataMediaKind.SERIES, identity, " ")
        }
    }

    @Test
    fun `anime sidecar provider ids produce stable parent keys`() {
        val malIdentity = ContentIdentity(
            canonicalProvider = null,
            canonicalId = null,
            providerIds = ProviderIds(mal = "5114")
        )
        val anilistIdentity = ContentIdentity(
            canonicalProvider = null,
            canonicalId = null,
            providerIds = ProviderIds(anilist = "9253")
        )

        assertEquals(
            "series:mal:5114",
            ContinueWatchingItemKeys.parentKey(MetadataMediaKind.SERIES, malIdentity, "mal:5114")
        )
        assertEquals(
            "series:anilist:9253",
            ContinueWatchingItemKeys.parentKey(MetadataMediaKind.SERIES, anilistIdentity, "anilist:9253")
        )
    }

    @Test
    fun `episode key rejects non positive coordinates`() {
        val identity = ContentIdentity(
            canonicalProvider = ProviderId.TVDB,
            canonicalId = "393268",
            providerIds = ProviderIds(tvdb = "393268")
        )

        assertThrows(IllegalArgumentException::class.java) {
            ContinueWatchingItemKeys.episodeKey(MetadataMediaKind.SERIES, identity, 0, 1, "tvdb:393268")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ContinueWatchingItemKeys.episodeKey(MetadataMediaKind.SERIES, identity, 1, -1, "tvdb:393268")
        }
    }

    @Test
    fun `source local canonical provider falls back to globally stable provider ids`() {
        val identity = ContentIdentity(
            canonicalProvider = ProviderId.ADDON,
            canonicalId = "addon-local-show",
            providerIds = ProviderIds(tvdb = "393268", imdb = "tt9794044")
        )

        assertEquals(
            "series:tvdb:393268",
            ContinueWatchingItemKeys.parentKey(MetadataMediaKind.SERIES, identity, "addon-a:show")
        )
    }

    @Test
    fun `source local canonical provider falls back to raw key without provider ids`() {
        val identity = ContentIdentity(
            canonicalProvider = ProviderId.MDBLIST,
            canonicalId = "list-local-show",
            providerIds = ProviderIds()
        )

        val key = ContinueWatchingItemKeys.parentKey(MetadataMediaKind.SERIES, identity, "mdblist:row:show")

        assertEquals(true, key.startsWith("series:raw:"))
    }
}
