package com.nexio.tv.core.recommendations

import android.database.MatrixCursor
import androidx.tvprovider.media.tv.TvContractCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AndroidTvOwnedChannelRowsTest {

    @Test
    fun `fromCursor reads browsable state from tv provider row`() {
        val cursor = MatrixCursor(AndroidTvOwnedChannelRows.PROJECTION).apply {
            addRow(
                arrayOf<Any>(
                    42L,
                    "nexio_android_tv_channel_trakt_popular",
                    1
                )
            )
        }

        cursor.moveToFirst()
        val channel = AndroidTvOwnedChannelRows.fromCursor(cursor)

        assertEquals(42L, channel.id)
        assertEquals("nexio_android_tv_channel_trakt_popular", channel.internalProviderId)
        assertTrue(channel.isBrowsable)
    }

    @Test
    fun `fromCursor treats missing browsable approval as not browsable`() {
        val cursor = MatrixCursor(AndroidTvOwnedChannelRows.PROJECTION).apply {
            addRow(
                arrayOf<Any>(
                    43L,
                    "nexio_android_tv_channel_trakt_watchlist",
                    0
                )
            )
        }

        cursor.moveToFirst()
        val channel = AndroidTvOwnedChannelRows.fromCursor(cursor)

        assertEquals(43L, channel.id)
        assertEquals("nexio_android_tv_channel_trakt_watchlist", channel.internalProviderId)
        assertFalse(channel.isBrowsable)
    }

    @Test
    fun `projection includes browsable column omitted by PreviewChannel helper`() {
        assertTrue(
            AndroidTvOwnedChannelRows.PROJECTION.contains(
                TvContractCompat.Channels.COLUMN_BROWSABLE
            )
        )
    }
}
