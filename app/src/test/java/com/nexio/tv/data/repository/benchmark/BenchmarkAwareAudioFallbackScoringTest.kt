package com.nexio.tv.data.repository.benchmark

import org.junit.Assert.assertEquals
import org.junit.Test

class BenchmarkAwareAudioFallbackScoringTest {

    private fun audio(
        ac3: Boolean = false,
        eac3: Boolean = false,
        atmos: Boolean = false,
        truehd: Boolean = false,
        dts: Boolean = false,
        dtshd: Boolean = false,
        dtsx: Boolean = false
    ): DeviceAudioOutputCapabilities = DeviceAudioOutputCapabilities(
        ac3 = AudioEncodingSupport(supported = ac3, passthroughLikely = ac3),
        eac3 = AudioEncodingSupport(supported = eac3, passthroughLikely = eac3),
        atmos = AudioEncodingSupport(supported = atmos, passthroughLikely = atmos),
        truehd = AudioEncodingSupport(supported = truehd, passthroughLikely = truehd),
        dts = AudioEncodingSupport(supported = dts, passthroughLikely = dts),
        dtshd = AudioEncodingSupport(supported = dtshd, passthroughLikely = dtshd),
        dtsx = AudioEncodingSupport(supported = dtsx, passthroughLikely = dtsx)
    )

    private fun snapshot(audio: DeviceAudioOutputCapabilities) = DeviceCapabilitySnapshot(
        model = "Test Device",
        manufacturer = "Acme",
        sdkInt = 34,
        displayHdrTypes = emptySet(),
        videoDecode = DeviceVideoDecodeCapabilities(),
        audioOutput = audio,
        evidence = null,
        capturedAtMs = 1L
    )

    private fun resolve(
        tags: List<String>,
        audio: DeviceAudioOutputCapabilities,
        release: ShadowReleaseType
    ): ShadowAudioScoringDecision = resolveAudioScoringDecision(tags, snapshot(audio), release)

    private val basePoints = mapOf(
        ShadowAudioTier.TRUEHD_ATMOS to 16,
        ShadowAudioTier.DTSX to 16,
        ShadowAudioTier.DDP_ATMOS to 16,
        ShadowAudioTier.TRUEHD to 12,
        ShadowAudioTier.DTSHD to 12,
        ShadowAudioTier.DDP to 10,
        ShadowAudioTier.AC3 to 7,
        ShadowAudioTier.DTS to 7,
        ShadowAudioTier.OTHER to 0
    )

    private fun expectedScore(tier: ShadowAudioTier, supported: Boolean): Int {
        val base = basePoints.getValue(tier)
        return if (supported) base else -base
    }

    @Test fun `case 1 atmos plus ddp on eac3-only resolves to DDP`() {
        val d = resolve(listOf("atmos", "ddp"), audio(eac3 = true), ShadowReleaseType.WEBDL)
        assertEquals(ShadowAudioTier.DDP, d.effectiveTier)
        assertEquals(true, d.supported)
        assertEquals(10, expectedScore(d.effectiveTier, d.supported))
    }

    @Test fun `case 2 atmos plus ddp on atmos plus eac3 resolves to DDP_ATMOS`() {
        val d = resolve(listOf("atmos", "ddp"), audio(atmos = true, eac3 = true), ShadowReleaseType.WEBDL)
        assertEquals(ShadowAudioTier.DDP_ATMOS, d.effectiveTier)
        assertEquals(true, d.supported)
        assertEquals(16, expectedScore(d.effectiveTier, d.supported))
    }

    @Test fun `case 3 atmos plus truehd on truehd-only resolves to TRUEHD`() {
        val d = resolve(listOf("atmos", "truehd"), audio(truehd = true), ShadowReleaseType.REMUX)
        assertEquals(ShadowAudioTier.TRUEHD, d.effectiveTier)
        assertEquals(true, d.supported)
        assertEquals(12, expectedScore(d.effectiveTier, d.supported))
    }

    @Test fun `case 4 atmos plus truehd on atmos plus truehd resolves to TRUEHD_ATMOS`() {
        val d = resolve(listOf("atmos", "truehd"), audio(atmos = true, truehd = true), ShadowReleaseType.REMUX)
        assertEquals(ShadowAudioTier.TRUEHD_ATMOS, d.effectiveTier)
        assertEquals(true, d.supported)
        assertEquals(16, expectedScore(d.effectiveTier, d.supported))
    }

    @Test fun `case 5 atmos only on WEBDL eac3-only resolves to DDP`() {
        val d = resolve(listOf("atmos"), audio(eac3 = true), ShadowReleaseType.WEBDL)
        assertEquals(ShadowAudioTier.DDP, d.effectiveTier)
        assertEquals(true, d.supported)
    }

    @Test fun `case 6 atmos only on REMUX truehd-only resolves to TRUEHD`() {
        val d = resolve(listOf("atmos"), audio(truehd = true), ShadowReleaseType.REMUX)
        assertEquals(ShadowAudioTier.TRUEHD, d.effectiveTier)
        assertEquals(true, d.supported)
    }

    @Test fun `case 7 atmos only on UNKNOWN release with eac3-only resolves to DDP`() {
        val d = resolve(listOf("atmos"), audio(eac3 = true), ShadowReleaseType.UNKNOWN)
        assertEquals(ShadowAudioTier.DDP, d.effectiveTier)
        assertEquals(true, d.supported)
    }

    @Test fun `case 8 atmos only on BLURAY_ENCODE with atmos resolves to TRUEHD_ATMOS`() {
        val d = resolve(listOf("atmos"), audio(atmos = true), ShadowReleaseType.BLURAY_ENCODE)
        assertEquals(ShadowAudioTier.TRUEHD_ATMOS, d.effectiveTier)
        assertEquals(true, d.supported)
    }

    @Test fun `case 9 dtsx on dtshd-only resolves to DTSHD`() {
        val d = resolve(listOf("dts:x"), audio(dtshd = true), ShadowReleaseType.REMUX)
        assertEquals(ShadowAudioTier.DTSHD, d.effectiveTier)
        assertEquals(true, d.supported)
    }

    @Test fun `case 10 dtsx on dts-only resolves to DTS`() {
        val d = resolve(listOf("dts:x"), audio(dts = true), ShadowReleaseType.REMUX)
        assertEquals(ShadowAudioTier.DTS, d.effectiveTier)
        assertEquals(true, d.supported)
    }

    @Test fun `case 11 dtsx on full dts stack resolves to DTSX`() {
        val d = resolve(listOf("dts:x"), audio(dtsx = true, dtshd = true, dts = true), ShadowReleaseType.REMUX)
        assertEquals(ShadowAudioTier.DTSX, d.effectiveTier)
        assertEquals(true, d.supported)
    }

    @Test fun `case 12 dtshd on dts-only resolves to DTS`() {
        val d = resolve(listOf("dts-hd"), audio(dts = true), ShadowReleaseType.BLURAY_ENCODE)
        assertEquals(ShadowAudioTier.DTS, d.effectiveTier)
        assertEquals(true, d.supported)
    }

    @Test fun `case 13 dtshd on dtshd resolves to DTSHD`() {
        val d = resolve(listOf("dts-hd"), audio(dtshd = true), ShadowReleaseType.BLURAY_ENCODE)
        assertEquals(ShadowAudioTier.DTSHD, d.effectiveTier)
        assertEquals(true, d.supported)
    }

    @Test fun `case 14 truehd on ac3-only resolves to TRUEHD unsupported`() {
        val d = resolve(listOf("truehd"), audio(ac3 = true), ShadowReleaseType.REMUX)
        assertEquals(ShadowAudioTier.TRUEHD, d.effectiveTier)
        assertEquals(false, d.supported)
        assertEquals(-12, expectedScore(d.effectiveTier, d.supported))
    }

    @Test fun `case 15 ddp on ac3-only resolves to DDP unsupported`() {
        val d = resolve(listOf("ddp"), audio(ac3 = true), ShadowReleaseType.WEBDL)
        assertEquals(ShadowAudioTier.DDP, d.effectiveTier)
        assertEquals(false, d.supported)
        assertEquals(-10, expectedScore(d.effectiveTier, d.supported))
    }

    @Test fun `case 16 atmos plus truehd on WEBDL release still uses TRUEHD ladder`() {
        val d = resolve(listOf("atmos", "truehd"), audio(atmos = true), ShadowReleaseType.WEBDL)
        assertEquals(ShadowAudioTier.TRUEHD_ATMOS, d.effectiveTier)
        assertEquals(true, d.supported)
    }

    @Test fun `case 17 atmos plus ddp on REMUX release still uses DDP ladder`() {
        val d = resolve(listOf("atmos", "ddp"), audio(atmos = true), ShadowReleaseType.REMUX)
        assertEquals(ShadowAudioTier.DDP_ATMOS, d.effectiveTier)
        assertEquals(true, d.supported)
    }

    @Test fun `case 18 empty audio tags resolves to OTHER`() {
        val d = resolve(
            emptyList(),
            audio(ac3 = true, eac3 = true, atmos = true, truehd = true, dts = true, dtshd = true, dtsx = true),
            ShadowReleaseType.WEBDL
        )
        assertEquals(ShadowAudioTier.OTHER, d.effectiveTier)
        assertEquals(true, d.supported)
        assertEquals(0, expectedScore(d.effectiveTier, d.supported))
    }
}
