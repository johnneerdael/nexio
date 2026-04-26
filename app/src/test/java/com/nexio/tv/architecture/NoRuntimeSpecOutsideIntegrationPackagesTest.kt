package com.nexio.tv.architecture

import org.junit.Assert.fail
import org.junit.Test

class NoRuntimeSpecOutsideIntegrationPackagesTest {
    @Test
    fun `only integration and approved provider packages create integration specs across the whole tree`() {
        val offenders = architectureScan(
            allowedPackages = setOf(
                "com.nexio.tv.core.anime",
                "com.nexio.tv.data.integration",
                "com.nexio.tv.core.integration",
                "com.nexio.tv.core.tmdb",
                "com.nexio.tv.core.tvdb",
                "com.nexio.tv.data.local.integration"
            ),
            forbiddenSimpleNames = setOf("IntegrationSpec")
        )

        if (offenders.isNotEmpty()) {
            fail("IntegrationSpec creation escaped approved packages: $offenders")
        }
    }
}
