package com.nexio.tv.data.repository.servicewrap

import com.nexio.tv.data.remote.dto.debrid.RealDebridInstantAvailabilityFileDto
import org.junit.Assert.assertEquals
import org.junit.Test

class DebridAvailabilityResolverTest {

    @Test
    fun `extractRealDebridVariants matches hash keys case-insensitively`() {
        val variants = listOf(
            mapOf(
                "1" to RealDebridInstantAvailabilityFileDto(
                    filename = "Show.S01E02.1080p.WEB-DL.mkv",
                    filesize = 4_000_000_000L
                )
            )
        )

        val availability = mapOf(
            "abcdef0123456789abcdef0123456789abcdef01" to mapOf(
                "rd" to variants
            )
        )

        val resolved = extractRealDebridVariants(
            availabilityBody = availability,
            normalizedInfoHash = "ABCDEF0123456789ABCDEF0123456789ABCDEF01"
        )

        assertEquals(variants, resolved)
    }
}
