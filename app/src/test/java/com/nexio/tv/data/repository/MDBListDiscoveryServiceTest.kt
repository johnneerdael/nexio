package com.nexio.tv.data.repository

import com.nexio.tv.core.poster.PosterRatingsUrlResolver
import com.nexio.tv.core.integration.passThroughTestRuntime
import com.nexio.tv.data.integration.mdblist.MDBListIntegrationProvider
import com.nexio.tv.data.local.DebugSettingsDataStore
import com.nexio.tv.data.local.MDBListCatalogPreferences
import com.nexio.tv.data.local.MDBListDiscoverySnapshotStore
import com.nexio.tv.data.local.MDBListSettingsDataStore
import com.nexio.tv.data.remote.api.MDBListApi
import com.nexio.tv.domain.model.MDBListSettings
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.testutil.testProfileManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.coEvery
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response

class MDBListDiscoveryServiceTest {
    @Test
    fun `fallback selected list uses MDBList detail name for custom catalog title`() = runTest {
        val api = mockk<MDBListApi>()
        coEvery { api.getRaw(relativeUrl = any(), apiKey = "api-key") } answers {
            successBody(
                when (firstArg<String>()) {
                    "lists/user", "my/lists", "lists/me", "lists/top", "top/lists" -> "[]"
                    "lists/jordipc/oscars-2026-the-98th-academy-awards" -> """
                        [
                          {
                            "id": 1176,
                            "user_name": "jordipc",
                            "name": "Oscars 2026 | The 98th Academy Awards",
                            "slug": "oscars-2026-the-98th-academy-awards",
                            "mediatype": "movie",
                            "items": 30
                          }
                        ]
                    """.trimIndent()
                    else -> "[]"
                }
            )
        }
        coEvery { api.getRawWithQuery(relativeUrl = any(), query = any()) } answers {
            successBody(
                when (firstArg<String>()) {
                    "lists/1176/items" -> """
                        {
                          "movies": [
                            {
                              "title": "Sample Oscar Movie",
                              "imdb_id": "tt1234567",
                              "mediatype": "movie",
                              "year": 2026
                            }
                          ]
                        }
                    """.trimIndent()
                    else -> ""
                }
            )
        }

        val service = buildService(
            api = api,
            selectedTopListKeys = setOf("top:jordipc/oscars-2026-the-98th-academy-awards")
        )

        service.ensureFresh(force = true)

        val snapshot = service.observeSnapshot(autoRefreshOnStart = false).first()
        val customCatalog = snapshot.customListCatalogs.single()
        assertEquals("Oscars 2026 | The 98th Academy Awards (Movies)", customCatalog.catalogName)
        assert(customCatalog.itemRecords.isNotEmpty())
    }

    @Test
    fun `numeric fallback selected list uses MDBList id endpoint`() = runTest {
        val api = mockk<MDBListApi>()
        coEvery { api.getRaw(relativeUrl = any(), apiKey = "api-key") } answers {
            successBody(
                when (firstArg<String>()) {
                    "lists/user", "my/lists", "lists/me", "lists/top", "top/lists" -> "[]"
                    "lists/jordipc/140566" -> ""
                    "lists/140566" -> """
                        [
                          {
                            "id": 140566,
                            "user_name": "jordipc",
                            "name": "Anime",
                            "slug": "anime",
                            "mediatype": "show",
                            "items": 30
                          }
                        ]
                    """.trimIndent()
                    else -> "[]"
                }
            )
        }
        coEvery { api.getRawWithQuery(relativeUrl = any(), query = any()) } answers {
            successBody(
                when (firstArg<String>()) {
                    "lists/140566/items" -> """
                        {
                          "shows": [
                            {
                              "title": "Sample Anime",
                              "imdb_id": "tt7654321",
                              "mediatype": "show",
                              "year": 2025
                            }
                          ]
                        }
                    """.trimIndent()
                    else -> ""
                }
            )
        }

        val service = buildService(
            api = api,
            selectedTopListKeys = setOf("top:jordipc/140566")
        )

        service.ensureFresh(force = true)

        val customCatalog = service.observeSnapshot(autoRefreshOnStart = false)
            .first()
            .customListCatalogs
            .single()
        assertEquals("Anime (Shows)", customCatalog.catalogName)
        assert(customCatalog.itemRecords.isNotEmpty())
    }

    private fun buildService(
        api: MDBListApi,
        selectedTopListKeys: Set<String>
    ): MDBListDiscoveryService {
        val dataStore = mockk<MDBListSettingsDataStore>()
        every { dataStore.settings } returns flowOf(MDBListSettings(enabled = true, apiKey = "api-key"))
        every { dataStore.catalogPreferences } returns flowOf(
            MDBListCatalogPreferences(
                selectedTopListKeys = selectedTopListKeys,
                catalogOrder = selectedTopListKeys.toList()
            )
        )
        every { dataStore.sanitizeCatalogOrder(any(), any()) } answers {
            secondArg<Set<String>>().toList()
        }

        val posterResolver = mockk<PosterRatingsUrlResolver>()
        coEvery { posterResolver.getActiveProvider() } returns null
        every { posterResolver.apply(any<MetaPreview>(), null) } answers { firstArg() }

        val snapshotStore = mockk<MDBListDiscoverySnapshotStore>(relaxed = true)
        every { snapshotStore.read(any()) } returns null
        val debugSettings = mockk<DebugSettingsDataStore>()
        every { debugSettings.diskFirstHomeStartupEnabled } returns flowOf(false)

        return MDBListDiscoveryService(
            mdbListIntegrationProvider = MDBListIntegrationProvider(passThroughTestRuntime(), api),
            mdbListSettingsDataStore = dataStore,
            posterRatingsUrlResolver = posterResolver,
            snapshotStore = snapshotStore,
            debugSettingsDataStore = debugSettings,
            profileManager = testProfileManager()
        )
    }

    private fun successBody(body: String): Response<okhttp3.ResponseBody> {
        return Response.success(body.toResponseBody())
    }
}
