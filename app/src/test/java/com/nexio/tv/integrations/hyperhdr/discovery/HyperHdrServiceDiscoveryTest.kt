package com.nexio.tv.integrations.hyperhdr.discovery

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HyperHdrServiceDiscoveryTest {

    @Test
    fun `mergeServices correlates flatbuf + json by hostname`() {
        val flatbuf = listOf(
            HyperHdrServiceDiscovery.RawService("hdr-server", "192.168.1.10", 19400),
            HyperHdrServiceDiscovery.RawService("other-host", "192.168.1.20", 19400),
        )
        val json = listOf(
            HyperHdrServiceDiscovery.RawService("hdr-server", "192.168.1.10", 19444),
        )
        val merged = HyperHdrServiceDiscovery.mergeServices(flatbuf, json)
        assertThat(merged).hasSize(2)
        val first = merged.first { it.hostname == "hdr-server" }
        assertThat(first.flatbufPort).isEqualTo(19400)
        assertThat(first.jsonPort).isEqualTo(19444)
        val second = merged.first { it.hostname == "other-host" }
        assertThat(second.flatbufPort).isEqualTo(19400)
        assertThat(second.jsonPort).isNull()
    }

    @Test
    fun `mergeServices yields empty when no flatbuf services found`() {
        val merged = HyperHdrServiceDiscovery.mergeServices(
            flatbuf = emptyList(),
            json = listOf(HyperHdrServiceDiscovery.RawService("x", "1.1.1.1", 19444)),
        )
        assertThat(merged).isEmpty()
    }

    @Test
    fun `mergeServices deduplicates by hostname`() {
        val flatbuf = listOf(
            HyperHdrServiceDiscovery.RawService("dup", "192.168.1.10", 19400),
            HyperHdrServiceDiscovery.RawService("dup", "192.168.1.10", 19400),
        )
        val merged = HyperHdrServiceDiscovery.mergeServices(flatbuf, emptyList())
        assertThat(merged).hasSize(1)
    }
}
