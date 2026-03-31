package com.nexio.tv.data.repository.servicewrap

import org.junit.Assert.assertEquals
import org.junit.Test

class ServiceWrapProviderTest {

    @Test
    fun `service wrap provider registry includes TorBox and EasyDebrid`() {
        assertEquals(
            setOf("RD", "PM", "TB", "ED"),
            ServiceWrapProvider.entries.map { it.providerId }.toSet()
        )
    }
}
