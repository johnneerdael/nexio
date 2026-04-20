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

    @Test
    fun `top streaming uuid manifest path remains public addon path`() {
        val parsed = parseAddonInstallUrl(
            "https://top-streaming.stream/f5ab503d-0ac4-4540-84de-5fb0437727dc/manifest.json"
        )

        assertEquals("https://top-streaming.stream/f5ab503d-0ac4-4540-84de-5fb0437727dc", parsed.publicBaseUrl)
        assertEquals("https://top-streaming.stream/f5ab503d-0ac4-4540-84de-5fb0437727dc/manifest.json", parsed.manifestUrl)
        assertEquals("manifest", parsed.installKind)
        assertEquals(null, parsed.secretRef)
        assertEquals(null, parsed.secretPayload)
    }

    @Test
    fun `configured path manifest resolves back to original install url`() {
        val parsed = parseAddonInstallUrl(
            "https://cometfortheweebs.midnightignite.me/eyJjb25maWciOnRydWV9/manifest.json"
        )

        val resolved = buildResolvedAddonUrl(
            baseUrl = parsed.publicBaseUrl,
            manifestUrl = parsed.manifestUrl,
            publicQueryParams = parsed.publicQueryParams,
            secretPayload = parsed.secretPayload
        )

        assertEquals("https://cometfortheweebs.midnightignite.me", parsed.publicBaseUrl)
        assertEquals("configured", parsed.installKind)
        assertEquals("eyJjb25maWciOnRydWV9", parsed.secretPayload?.pathSegment)
        assertEquals(
            "https://cometfortheweebs.midnightignite.me/eyJjb25maWciOnRydWV9/manifest.json",
            resolved
        )
    }

    @Test
    fun `addon transport v2 stores origin and opaque manifest suffix`() {
        val parsed = parseAddonInstallUrl(
            "https://comet.feels.legal/eyJjb25maWciOnRydWV9/manifest.json"
        )

        val resolved = buildResolvedAddonUrl(
            baseUrl = parsed.transportBaseUrl,
            manifestUrl = null,
            publicQueryParams = emptyMap(),
            secretPayload = parsed.transportSecretPayload
        )

        assertEquals("https://comet.feels.legal", parsed.transportBaseUrl)
        assertEquals("manifest_suffix_v1", parsed.transportSecretPayload.kind)
        assertEquals("/eyJjb25maWciOnRydWV9/manifest.json", parsed.transportSecretPayload.suffix)
        assertEquals(
            "https://comet.feels.legal/eyJjb25maWciOnRydWV9/manifest.json",
            resolved
        )
    }

    @Test
    fun `addon transport v2 stores public addon manifest suffix too`() {
        val parsed = parseAddonInstallUrl("https://thepiratebay-plus.strem.fun/manifest.json")

        val resolved = buildResolvedAddonUrl(
            baseUrl = parsed.transportBaseUrl,
            manifestUrl = null,
            publicQueryParams = emptyMap(),
            secretPayload = parsed.transportSecretPayload
        )

        assertEquals("https://thepiratebay-plus.strem.fun", parsed.transportBaseUrl)
        assertEquals("/manifest.json", parsed.transportSecretPayload.suffix)
        assertEquals("https://thepiratebay-plus.strem.fun/manifest.json", resolved)
    }
}
