package com.nexio.tv.core.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class AddonSyncCodecTest {
    @Test
    fun `normalize addon install url strips manifest path while preserving query parameters`() {
        val normalized = normalizeAddonInstallUrl(
            " https://addon.example.com/config/manifest.json?token=abc&lang=nl "
        )

        assertEquals("https://addon.example.com/config?token=abc&lang=nl", normalized)
    }

    @Test
    fun `addon request url inserts relative path before query parameters`() {
        val requestUrl = buildAddonRequestUrl(
            baseUrl = "https://addon.example.com/config?token=abc&lang=nl",
            relativePath = "catalog/movie/top/search=alien.json"
        )

        assertEquals(
            "https://addon.example.com/config/catalog/movie/top/search=alien.json?token=abc&lang=nl",
            requestUrl
        )
    }

    @Test
    fun `addon request url builds manifest url before query parameters`() {
        val requestUrl = buildAddonRequestUrl(
            baseUrl = "https://addon.example.com/config?token=abc",
            relativePath = "manifest.json"
        )

        assertEquals("https://addon.example.com/config/manifest.json?token=abc", requestUrl)
    }
}
