package com.nexio.tv.core.search

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AndroidTvNativeSearchIntentTest {

    @Test
    fun `detail uri round trips item identity and addon base url`() {
        val suggestion = AndroidTvSearchSuggestion(
            rowId = 1,
            title = "Friends",
            subtitle = "Series",
            itemId = "tt0108778",
            contentType = "series",
            addonBaseUrl = "https://v3-cinemeta.strem.io",
            productionYear = 1994,
            durationMs = null
        )

        val target = AndroidTvNativeSearchIntent.parseDetailUri(
            AndroidTvNativeSearchIntent.buildDetailUri(suggestion)
        )

        assertEquals("tt0108778", target?.itemId)
        assertEquals("series", target?.itemType)
        assertEquals("https://v3-cinemeta.strem.io", target?.addonBaseUrl)
    }

    @Test
    fun `parse detail uri rejects malformed native search data`() {
        assertNull(AndroidTvNativeSearchIntent.parseDetailUri(null))
        assertNull(AndroidTvNativeSearchIntent.parseDetailUri(Uri.parse("https://example.com/detail?itemId=tt1&contentType=movie")))
        assertNull(AndroidTvNativeSearchIntent.parseDetailUri(Uri.parse("nexio://other?itemId=tt1&contentType=movie")))
        assertNull(AndroidTvNativeSearchIntent.parseDetailUri(Uri.parse("nexio://detail?contentType=movie")))
        assertNull(AndroidTvNativeSearchIntent.parseDetailUri(Uri.parse("nexio://detail?itemId=tt1")))
    }
}
