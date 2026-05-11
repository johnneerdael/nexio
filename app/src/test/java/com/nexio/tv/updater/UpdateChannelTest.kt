package com.nexio.tv.updater

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateChannelTest {

    @Test
    fun `fromBuildConfig maps stable literal`() {
        assertEquals(UpdateChannel.Stable, UpdateChannel.fromBuildConfig("stable"))
    }

    @Test
    fun `fromBuildConfig maps earlyAccess literal`() {
        assertEquals(UpdateChannel.EarlyAccess, UpdateChannel.fromBuildConfig("earlyAccess"))
    }

    @Test
    fun `fromBuildConfig falls back to Stable for unknown values`() {
        assertEquals(UpdateChannel.Stable, UpdateChannel.fromBuildConfig("nightly"))
    }

    @Test
    fun `fromBuildConfig falls back to Stable for empty string`() {
        assertEquals(UpdateChannel.Stable, UpdateChannel.fromBuildConfig(""))
    }

    @Test
    fun `Stable channel uses nexio-release prefix`() {
        assertEquals("nexio-release", UpdateChannel.Stable.assetPrefix)
    }

    @Test
    fun `EarlyAccess channel uses nexio-earlyaccess prefix`() {
        assertEquals("nexio-earlyaccess", UpdateChannel.EarlyAccess.assetPrefix)
    }
}
