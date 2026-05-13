package com.nexio.tv.ui.screens.home.order

import com.google.gson.Gson
import com.nexio.tv.domain.model.HomeCatalogRail
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeCatalogRailFixtureTest {
    private val gson = Gson()

    @Test
    fun `rails with tmdb kitsu fixture visible keys match`() {
        val fixture = readFixture("rails-with-tmdb-kitsu")
        val definitions = fixture.inventory.map { definition(it.key, it.title) }

        assertEquals(
            fixture.expectedVisibleKeys.map(::HomeRailKey),
            visibleHomeRailKeysFromRails(fixture.rails, definitions)
        )
    }

    @Test
    fun `duplicates unknown fixture drops duplicate and unavailable visible rows`() {
        val fixture = readFixture("rails-duplicates-unknown")
        val definitions = fixture.inventory.map { definition(it.key, it.title) }

        assertEquals(
            fixture.expectedVisibleKeys.map(::HomeRailKey),
            visibleHomeRailKeysFromRails(fixture.rails, definitions)
        )
    }

    private fun readFixture(name: String): Fixture {
        return gson.fromJson(
            File("docs/superpowers/fixtures/home-catalog-rails/$name.json").readText(),
            Fixture::class.java
        )
    }

    private fun definition(key: String, title: String) = HomeRailDefinition(
        key = HomeRailKey(key),
        family = RailFamily.fromOrderKey(key),
        source = if (RailFamily.fromOrderKey(key) == RailFamily.ADDON) {
            RailSource.ADDON_CATALOG
        } else {
            RailSource.PROVIDER_PUBLIC
        },
        title = title,
        enabled = true,
        defaultSortKey = DefaultSortKey(RailFamily.fromOrderKey(key).familyRank, 0),
        publishPolicy = RailPublishPolicy.PUBLISH_WHEN_NON_EMPTY
    )

    private data class Fixture(
        val rails: List<HomeCatalogRail> = emptyList(),
        val inventory: List<InventoryRecord> = emptyList(),
        val expectedVisibleKeys: List<String> = emptyList()
    )

    private data class InventoryRecord(
        val key: String = "",
        val title: String = ""
    )
}
