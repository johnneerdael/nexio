package com.nexio.tv.core.di

import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddonManifestNetworkModuleTest {
    @Test
    fun `addon manifest client disables redirects without changing base client`() {
        val baseClient = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        val manifestClient = NetworkModule.provideAddonManifestOkHttpClient(baseClient)

        assertTrue(baseClient.followRedirects)
        assertTrue(baseClient.followSslRedirects)
        assertFalse(manifestClient.followRedirects)
        assertFalse(manifestClient.followSslRedirects)
    }
}
