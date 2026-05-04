package com.nexio.tv.integrations.hyperhdr.discovery

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DiscoveryAggregatorTest {

    @Test
    fun `resolved record produces a discovered server`() {
        val agg = DiscoveryAggregator()
        agg.onResolved("hyperhdr:8090", "192.168.1.10", httpPort = 8090, flatbufPort = 19400)

        assertThat(agg.servers()).containsExactly(
            DiscoveredServer("hyperhdr:8090", "192.168.1.10", httpPort = 8090, flatbufPort = 19400),
        )
    }

    @Test
    fun `multiple instances produce multiple servers`() {
        val agg = DiscoveryAggregator()
        agg.onResolved("a", "192.168.1.10", 8090, 19400)
        agg.onResolved("b", "192.168.1.20", 8090, 19400)

        assertThat(agg.servers()).hasSize(2)
    }

    @Test
    fun `lost service is removed`() {
        val agg = DiscoveryAggregator()
        agg.onResolved("a", "192.168.1.10", 8090, 19400)
        agg.onLost("a")

        assertThat(agg.servers()).isEmpty()
    }

    @Test
    fun `re-resolve updates the record in place`() {
        val agg = DiscoveryAggregator()
        agg.onResolved("a", "192.168.1.10", 8090, 19400)
        agg.onResolved("a", "192.168.1.10", 9090, 19400)

        assertThat(agg.servers()).containsExactly(
            DiscoveredServer("a", "192.168.1.10", httpPort = 9090, flatbufPort = 19400),
        )
    }
}
