package com.nexio.tv.core.recommendations

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AndroidTvChannelArtworkTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `poster uri is stable and uses app channel art authority`() {
        val uri = AndroidTvChannelArtwork.posterUri(
            context = context,
            diskCacheKey = "tt15940132_rpdb_poster"
        )

        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.channelart", uri.authority)
        assertEquals("poster", uri.pathSegments[0])
        assertTrue(uri.pathSegments[1].endsWith(".jpg"))
        assertEquals(uri, AndroidTvChannelArtwork.posterUri(context, "tt15940132_rpdb_poster"))
    }

    @Test
    fun `poster file resolves under channel artwork cache directory`() {
        val file = AndroidTvChannelArtwork.posterFile(
            context = context,
            diskCacheKey = "tt15940132_top_posters_poster"
        )

        assertEquals(
            File(context.cacheDir, "android_tv_channel_art/posters").canonicalFile,
            file.parentFile?.canonicalFile
        )
        assertTrue(file.name.endsWith(".jpg"))
    }

    @Test
    fun `resolve poster file rejects wrong authorities and traversal`() {
        val valid = AndroidTvChannelArtwork.posterUri(context, "tt15940132_rpdb_poster")
        val wrongAuthority = valid.buildUpon()
            .authority("com.example.other")
            .build()
        val traversal = Uri.parse("content://${context.packageName}.channelart/poster/../secret.jpg")

        assertNull(AndroidTvChannelArtwork.resolvePosterFile(context, wrongAuthority))
        assertNull(AndroidTvChannelArtwork.resolvePosterFile(context, traversal))
    }
}
