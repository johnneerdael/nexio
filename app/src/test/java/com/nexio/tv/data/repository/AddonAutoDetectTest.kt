package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.AddonParserPreset
import org.junit.Assert.assertEquals
import org.junit.Test

class AddonAutoDetectTest {

    @Test fun `auto-detect resolves NEXIO_TORII from manifest id when user pick is GENERIC`() {
        assertEquals(
            AddonParserPreset.NEXIO_TORII,
            resolveAutoPreset(manifestId = "org.community.nexiotorii", userPick = AddonParserPreset.GENERIC)
        )
    }

    @Test fun `auto-detect resolves NEXIO_NAGARE from manifest id when user pick is GENERIC`() {
        assertEquals(
            AddonParserPreset.NEXIO_NAGARE,
            resolveAutoPreset(manifestId = "org.community.nexionagare", userPick = AddonParserPreset.GENERIC)
        )
    }

    @Test fun `auto-detect honours explicit user override`() {
        assertEquals(
            AddonParserPreset.STREMTHRU,
            resolveAutoPreset(manifestId = "org.community.nexiotorii", userPick = AddonParserPreset.STREMTHRU)
        )
    }

    @Test fun `auto-detect falls through to GENERIC for unknown ids`() {
        assertEquals(
            AddonParserPreset.GENERIC,
            resolveAutoPreset(manifestId = "com.someone.other", userPick = AddonParserPreset.GENERIC)
        )
        assertEquals(
            AddonParserPreset.GENERIC,
            resolveAutoPreset(manifestId = null, userPick = AddonParserPreset.GENERIC)
        )
    }
}
