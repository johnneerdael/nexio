package com.nexio.tv.architecture

import org.junit.Assert.fail
import org.junit.Test

class NoIntegrationRuntimeInjectionOutsideBoundaryTest {
    @Test
    fun `feature and presentation packages do not inject integration runtime directly across the whole tree`() {
        val offenders = architectureScan(
            allowedPackages = setOf(
                "com.nexio.tv.core.anime",
                "com.nexio.tv.data.integration",
                "com.nexio.tv.core.integration",
                "com.nexio.tv.core.di",
                "com.nexio.tv.core.tmdb",
                "com.nexio.tv.core.tvdb"
            ),
            forbiddenSimpleNames = setOf("IntegrationRuntime")
        )

        if (offenders.isNotEmpty()) {
            fail("IntegrationRuntime escaped approved layers: $offenders")
        }
    }
}
