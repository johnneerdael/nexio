package com.nexio.tv.core.metadata.router

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nexio.tv.data.local.integration.ExternalIdEntity
import com.nexio.tv.data.local.integration.IntegrationCacheDatabase
import com.nexio.tv.data.local.integration.MediaIdentityEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LocalIdMappingStoreTest {
    @Test
    fun `persisted negative kitsu row is not returned by lookup`() = runTest {
        withDatabase { db ->
            val store = LocalIdMappingStore(db.mediaIdentityDao())
            val sourceId = imdbSource()

            store.persist(
                IdMapping(
                    sourceId = sourceId,
                    provider = MetadataPrimaryProvider.KITSU,
                    providerId = "negative",
                    source = IdMappingSource.NEGATIVE,
                    evidence = "negative cache"
                )
            )

            assertNull(store.lookupKitsu(sourceId))
        }
    }

    @Test
    fun `highest priority durable row wins and lower priority does not overwrite it`() = runTest {
        withDatabase { db ->
            val sourceId = imdbSource()
            val mediaKey = sourceId.mappingKey()
            val store = LocalIdMappingStore(db.mediaIdentityDao())

            db.mediaIdentityDao().upsertMediaIdentity(
                MediaIdentityEntity(
                    mediaKey = mediaKey,
                    mediaType = sourceId.scheme.name,
                    title = null,
                    year = null,
                    updatedAtEpochMs = 1_000L
                )
            )
            db.mediaIdentityDao().upsertExternalIds(
                listOf(
                    externalId(mediaKey = mediaKey, keySuffix = "fribb", providerId = "fribb", source = IdMappingSource.FRIBB, idType = "ANIDB"),
                    externalId(mediaKey = mediaKey, keySuffix = "local", providerId = "local", source = IdMappingSource.LOCAL, idType = "IMDB")
                )
            )

            assertEquals("local", store.lookupKitsu(sourceId)?.providerId)

            store.persist(
                IdMapping(
                    sourceId = sourceId,
                    provider = MetadataPrimaryProvider.KITSU,
                    providerId = "fribb-overwrite",
                    source = IdMappingSource.FRIBB,
                    evidence = "lower priority"
                )
            )

            assertEquals("local", store.lookupKitsu(sourceId)?.providerId)
        }
    }

    private suspend fun withDatabase(block: suspend (IntegrationCacheDatabase) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(
            context,
            IntegrationCacheDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            block(db)
        } finally {
            db.close()
        }
    }

    private fun imdbSource(): ParsedMetadataId =
        ParsedMetadataId(AnimeIdScheme.IMDB, "tt0388629", "tt0388629")

    private fun externalId(
        mediaKey: String,
        keySuffix: String,
        providerId: String,
        source: IdMappingSource,
        idType: String
    ): ExternalIdEntity =
        ExternalIdEntity(
            key = "$mediaKey:$keySuffix",
            mediaKey = mediaKey,
            provider = MetadataPrimaryProvider.KITSU.name,
            externalId = providerId,
            idType = idType,
            mappingSource = source.name,
            evidence = source.name
        )
}
