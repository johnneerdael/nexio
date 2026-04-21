package com.nexio.tv.architecture

import org.junit.Assert.fail
import org.junit.Test

class NoIntegrationRuntimeInjectionOutsideBoundaryTest {
    @Test
    fun `feature and presentation packages do not inject integration runtime directly`() {
        val offenders = architectureScan(
            allowedPackages = setOf(
                "com.nexio.tv.data.integration",
                "com.nexio.tv.core.integration",
                "com.nexio.tv.core.di"
            ),
            forbiddenSimpleNames = setOf("IntegrationRuntime")
        )

        if (offenders.isNotEmpty()) {
            fail("IntegrationRuntime escaped approved layers: $offenders")
        }
    }
}
