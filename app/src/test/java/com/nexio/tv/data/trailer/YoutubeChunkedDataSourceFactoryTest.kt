package com.nexio.tv.data.trailer

import android.net.Uri
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YoutubeChunkedDataSourceFactoryTest {

    @Test
    fun `chunked transfer is disabled for youtube hls manifests`() {
        val uri = Uri.parse(
            "https://manifest.googlevideo.com/api/manifest/hls_variant/file/index.m3u8"
        )

        assertFalse(shouldUseYouTubeChunkedTransfer(uri))
    }

    @Test
    fun `chunked transfer stays enabled for direct googlevideo media urls`() {
        val uri = Uri.parse(
            "https://rr4---sn-uhvcpaxoa-xpoe.googlevideo.com/videoplayback?id=abc123"
        )

        assertTrue(shouldUseYouTubeChunkedTransfer(uri))
    }
}
