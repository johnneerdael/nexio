package com.nexio.tv.ui.components

import com.nexio.tv.R
import com.nexio.tv.domain.model.AddonParserPreset
import com.nexio.tv.domain.model.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamBadgeSupportNexioPresetTest {

    private fun streamWithPreset(p: AddonParserPreset) = Stream(
        name = "x", title = null, description = null,
        url = "https://x", ytId = null, infoHash = null,
        fileIdx = null, externalUrl = null,
        behaviorHints = null, sources = null,
        addonName = "Test", addonLogo = null,
        addonParserPreset = p
    )

    @Test fun `nexio torii preset maps to torii drawable`() {
        assertEquals(R.drawable.ic_addon_nexiotorii, providerIconFor(streamWithPreset(AddonParserPreset.NEXIO_TORII)))
    }

    @Test fun `nexio nagare preset maps to nagare drawable`() {
        assertEquals(R.drawable.ic_addon_nexionagare, providerIconFor(streamWithPreset(AddonParserPreset.NEXIO_NAGARE)))
    }

    @Test fun `non-nexio presets return null`() {
        assertNull(providerIconFor(streamWithPreset(AddonParserPreset.GENERIC)))
        assertNull(providerIconFor(streamWithPreset(AddonParserPreset.STREMTHRU)))
        assertNull(providerIconFor(streamWithPreset(AddonParserPreset.TORRENTIO)))
        assertNull(providerIconFor(streamWithPreset(AddonParserPreset.WEBSTREAMR)))
    }
}
