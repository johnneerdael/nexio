package com.nexio.tv.core.recommendations

import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.FileInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AndroidTvChannelArtworkProviderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `openFile serves existing poster bytes read only`() {
        val diskKey = "tt15940132_rpdb_poster"
        val file = AndroidTvChannelArtwork.posterFile(context, diskKey)
        file.parentFile?.mkdirs()
        val bytes = byteArrayOf(1, 2, 3, 4)
        file.writeBytes(bytes)
        val provider = Robolectric.buildContentProvider(AndroidTvChannelArtworkProvider::class.java)
            .create(AndroidTvChannelArtwork.authority(context))
            .get()

        provider.openFile(AndroidTvChannelArtwork.posterUri(context, diskKey), "r").use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { input ->
                assertArrayEquals(bytes, input.readBytes())
            }
        }
    }

    @Test
    fun `provider rejects write operations`() {
        val provider = Robolectric.buildContentProvider(AndroidTvChannelArtworkProvider::class.java)
            .create(AndroidTvChannelArtwork.authority(context))
            .get()
        val uri = AndroidTvChannelArtwork.posterUri(context, "tt15940132_rpdb_poster")

        assertThrows(UnsupportedOperationException::class.java) {
            provider.insert(uri, ContentValues())
        }
        assertThrows(UnsupportedOperationException::class.java) {
            provider.update(uri, ContentValues(), null, null)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            provider.delete(uri, null, null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            provider.openFile(uri, "rw")
        }
    }

    @Test
    fun `query returns display name and size for existing poster`() {
        val diskKey = "tt15940132_top_posters_poster"
        val file = AndroidTvChannelArtwork.posterFile(context, diskKey)
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(9, 8, 7))
        val provider = Robolectric.buildContentProvider(AndroidTvChannelArtworkProvider::class.java)
            .create(AndroidTvChannelArtwork.authority(context))
            .get()

        provider.query(AndroidTvChannelArtwork.posterUri(context, diskKey), null, null, null, null).use { cursor ->
            assertNotNull(cursor)
            requireNotNull(cursor)
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals(file.name, cursor.getString(cursor.getColumnIndexOrThrow("_display_name")))
            assertEquals(3L, cursor.getLong(cursor.getColumnIndexOrThrow("_size")))
        }
    }
}
