package com.nexio.tv.data.remote.supabase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AccountAddonSecretPayloadValidatorsTest {

    @Test
    fun `requireValidV2Transport accepts manifest_suffix_v1 with non-blank suffix`() {
        val payload = AccountAddonSecretPayload(
            kind = "manifest_suffix_v1",
            suffix = "/debridoptions=nodownloadlinks%7Crealdebrid=KEY/manifest.json"
        )

        val resolved = payload.requireValidV2Transport(
            secretRef = "addon:torrentio_strem_fun:transport:abc",
            addonUrl = "https://torrentio.strem.fun"
        )

        assertEquals(payload, resolved)
    }

    @Test
    fun `requireValidV2Transport throws on null payload`() {
        val error = assertThrows(IllegalStateException::class.java) {
            (null as AccountAddonSecretPayload?).requireValidV2Transport(
                secretRef = "addon:torrentio_strem_fun:transport:abc",
                addonUrl = "https://torrentio.strem.fun"
            )
        }
        assertEquals(true, error.message?.contains("addon:torrentio_strem_fun:transport:abc"))
        assertEquals(true, error.message?.contains("https://torrentio.strem.fun"))
        assertEquals(true, error.message?.contains("invariant"))
    }

    @Test
    fun `requireValidV2Transport throws when kind is not manifest_suffix_v1`() {
        val payload = AccountAddonSecretPayload(kind = "query_params")

        val error = assertThrows(IllegalStateException::class.java) {
            payload.requireValidV2Transport(
                secretRef = "addon:torrentio_strem_fun:transport:abc",
                addonUrl = "https://torrentio.strem.fun"
            )
        }
        assertEquals(true, error.message?.contains("kind=query_params"))
    }

    @Test
    fun `requireValidV2Transport throws when suffix is blank`() {
        val payload = AccountAddonSecretPayload(kind = "manifest_suffix_v1", suffix = "  ")

        val error = assertThrows(IllegalStateException::class.java) {
            payload.requireValidV2Transport(
                secretRef = "addon:torrentio_strem_fun:transport:abc",
                addonUrl = "https://torrentio.strem.fun"
            )
        }
        assertEquals(true, error.message?.contains("blank suffix"))
    }

    @Test
    fun `requireValidV1Secret accepts payload with populated params`() {
        val payload = AccountAddonSecretPayload(
            kind = "query_params",
            params = mapOf("token" to "deadbeef")
        )

        val resolved = payload.requireValidV1Secret(
            secretRef = "addon:legacy_addon",
            addonUrl = "https://legacy.example.com"
        )

        assertEquals(payload, resolved)
    }

    @Test
    fun `requireValidV1Secret accepts payload with non-blank pathSegment`() {
        val payload = AccountAddonSecretPayload(
            kind = "path_segment",
            pathSegment = "abc-123-def"
        )

        val resolved = payload.requireValidV1Secret(
            secretRef = "addon:legacy_addon",
            addonUrl = "https://legacy.example.com"
        )

        assertEquals(payload, resolved)
    }

    @Test
    fun `requireValidV1Secret throws on null payload`() {
        val error = assertThrows(IllegalStateException::class.java) {
            (null as AccountAddonSecretPayload?).requireValidV1Secret(
                secretRef = "addon:legacy_addon",
                addonUrl = "https://legacy.example.com"
            )
        }
        assertEquals(true, error.message?.contains("invariant"))
    }

    @Test
    fun `requireValidV1Secret throws when both params and pathSegment are empty`() {
        val payload = AccountAddonSecretPayload()

        val error = assertThrows(IllegalStateException::class.java) {
            payload.requireValidV1Secret(
                secretRef = "addon:legacy_addon",
                addonUrl = "https://legacy.example.com"
            )
        }
        assertEquals(true, error.message?.contains("empty"))
    }
}
