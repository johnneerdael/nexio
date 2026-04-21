package com.nexio.tv.architecture

import org.junit.Assert.fail
import org.junit.Test

class NoRawProviderInjectionTest {
    @Test
    fun `feature packages do not inject raw retrofit or okhttp types`() {
        val offenders = architectureScan(
            allowedPackages = setOf(
                "com.nexio.tv.data.integration",
                "com.nexio.tv.data.remote",
                "com.nexio.tv.core.di"
            ),
            forbiddenSimpleNames = setOf("Retrofit", "OkHttpClient")
        )

        if (offenders.isNotEmpty()) {
            fail("Raw networking types escaped integration boundary: $offenders")
        }
    }
}
