package com.nexio.tv.data.local.artwork

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ArtworkCacheModuleSmokeTest {
    @Test
    fun `artwork cache database exposes all daos`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ArtworkCacheDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        assertNotNull(db.decisionDao())
        assertNotNull(db.assetRecordDao())
        assertNotNull(db.migrationDao())
    }
}
