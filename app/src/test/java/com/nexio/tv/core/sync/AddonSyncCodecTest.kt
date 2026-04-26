package com.nexio.tv.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
    fun `top streaming uuid manifest path is stored as v2 transport suffix`() {
        val parsed = parseAddonInstallUrl(
            "https://top-streaming.stream/f5ab503d-0ac4-4540-84de-5fb0437727dc/manifest.json"
        )

        assertEquals("https://top-streaming.stream", parsed.publicBaseUrl)
        assertEquals("https://top-streaming.stream/manifest.json", parsed.manifestUrl)
        assertEquals("configured", parsed.installKind)
        assertNull(parsed.secretRef)
        assertNull(parsed.secretPayload)
        assertEquals("https://top-streaming.stream", parsed.transportBaseUrl)
        assertEquals("/f5ab503d-0ac4-4540-84de-5fb0437727dc/manifest.json", parsed.transportSecretPayload.suffix)
    }

    @Test
    fun `configured path manifest resolves back to original install url via transport suffix`() {
        val parsed = parseAddonInstallUrl(
            "https://cometfortheweebs.midnightignite.me/eyJjb25maWciOnRydWV9/manifest.json"
        )

        val resolved = buildResolvedAddonUrl(
            baseUrl = parsed.transportBaseUrl,
            manifestUrl = null,
            publicQueryParams = emptyMap(),
            secretPayload = parsed.transportSecretPayload
        )

        assertEquals("https://cometfortheweebs.midnightignite.me", parsed.publicBaseUrl)
        assertEquals("configured", parsed.installKind)
        assertNull(parsed.secretPayload)
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

    private val torrentioRdSuffix = "/debridoptions=nodownloadlinks%7Crealdebrid=L7VSEJBKIQE52BW7NQMSJGYTP3IPDTLKR6RUBNF5GKNZ646G5ZRA/manifest.json"
    private val torrentioPmSuffix = "/debridoptions=nodownloadlinks%7Cpremiumize=szpwe4fx4ngs8u9q/manifest.json"

    @Test
    fun `parseAddonInstallUrl rewrites stremio scheme to https`() {
        val stremio = parseAddonInstallUrl("stremio://torrentio.strem.fun$torrentioRdSuffix")
        val https = parseAddonInstallUrl("https://torrentio.strem.fun$torrentioRdSuffix")

        assertEquals("https://torrentio.strem.fun", stremio.publicBaseUrl)
        assertEquals("https://torrentio.strem.fun", stremio.transportBaseUrl)
        assertEquals(torrentioRdSuffix, stremio.transportSecretPayload.suffix)
        assertEquals(https.transportSecretRef, stremio.transportSecretRef)
    }

    @Test
    fun `normalizeAddonInstallUrl rewrites stremio scheme to https`() {
        val normalized = normalizeAddonInstallUrl("stremio://torrentio.strem.fun$torrentioRdSuffix")
        assertEquals(
            "https://torrentio.strem.fun" + torrentioRdSuffix.removeSuffix("/manifest.json"),
            normalized
        )
    }

    @Test
    fun `parseAddonInstallUrl produces null secretRef for v2 installs`() {
        val configured = parseAddonInstallUrl("https://torrentio.strem.fun$torrentioRdSuffix")
        assertNull(configured.secretRef)
        assertNull(configured.secretPayload)
        assertEquals("configured", configured.installKind)

        val bare = parseAddonInstallUrl("https://thepiratebay-plus.strem.fun/manifest.json")
        assertNull(bare.secretRef)
        assertNull(bare.secretPayload)
        assertEquals("manifest", bare.installKind)
    }

    @Test
    fun `two same-FQDN configured installs share base url but differ in transportSecretRef`() {
        val rd = parseAddonInstallUrl("https://torrentio.strem.fun$torrentioRdSuffix")
        val pm = parseAddonInstallUrl("https://torrentio.strem.fun$torrentioPmSuffix")

        assertEquals(rd.publicBaseUrl, pm.publicBaseUrl)
        assertEquals(rd.transportBaseUrl, pm.transportBaseUrl)
        assertNotEquals(rd.transportSecretRef, pm.transportSecretRef)
        assertEquals(torrentioRdSuffix, rd.transportSecretPayload.suffix)
        assertEquals(torrentioPmSuffix, pm.transportSecretPayload.suffix)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parseAddonInstallUrl rejects URLs without manifest path`() {
        parseAddonInstallUrl("https://torrentio.strem.fun/configure")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parseAddonInstallUrl rejects bare origin`() {
        parseAddonInstallUrl("https://torrentio.strem.fun/")
    }
}
