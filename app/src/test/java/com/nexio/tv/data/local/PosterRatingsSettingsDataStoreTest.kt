package com.nexio.tv.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nexio.tv.domain.model.ArtworkProviderChoiceKey
import com.nexio.tv.domain.model.ArtworkTypeKey
import com.nexio.tv.domain.model.TopPostersEntitlementSnapshot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PosterRatingsSettingsDataStoreTest {

    @Test
    fun `legacy enabled top posters key migrates to top posters poster selection`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = PosterRatingsSettingsDataStore(context)

        store.writeLegacyForTest(
            rpdbEnabled = false,
            rpdbApiKey = "",
            topPostersEnabled = true,
            topPostersApiKey = "TP-key"
        )

        val settings = store.settings.first()

        assertEquals("TP-key", settings.topPostersApiKey)
        assertEquals(ArtworkProviderChoiceKey.TOP_POSTERS, settings.selection.posterProvider)
        assertEquals(ArtworkProviderChoiceKey.DEFAULT, settings.selection.thumbnailProvider)
    }

    @Test
    fun `setting poster provider does not disable provider keys`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = PosterRatingsSettingsDataStore(context)

        store.setRpdbApiKey("rpdb-key")
        store.setTopPostersApiKey("TP-key")
        store.setProviderSelection(ArtworkTypeKey.POSTER, ArtworkProviderChoiceKey.RPDB)
        store.setProviderSelection(ArtworkTypeKey.POSTER, ArtworkProviderChoiceKey.TOP_POSTERS)

        val settings = store.settings.first()

        assertEquals("rpdb-key", settings.rpdbApiKey)
        assertEquals("TP-key", settings.topPostersApiKey)
        assertEquals(ArtworkProviderChoiceKey.TOP_POSTERS, settings.selection.posterProvider)
    }

    @Test
    fun `top posters entitlement snapshot persists`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = PosterRatingsSettingsDataStore(context)
        val snapshot = TopPostersEntitlementSnapshot(
            valid = true,
            isActive = true,
            tier = 1,
            tierName = "Premium",
            episodeThumbnails = true,
            verifiedAtMs = 100L,
            expiresAtMs = 200L
        )

        store.setTopPostersEntitlement(snapshot)

        assertEquals(snapshot, store.settings.first().topPostersEntitlement)
    }
}
