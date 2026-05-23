package com.nexio.tv.data.trailer.potoken

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PoTokenUrlAppendTest {
    @Test
    fun `appendPoTokenToGoogleVideoUri adds pot param to googlevideo url`() {
        val original = Uri.parse("https://rr1---sn-xyz.googlevideo.com/videoplayback?itag=137")
        val appended = appendPoTokenToGoogleVideoUri(original, "TOKENABC")
        assertEquals("TOKENABC", appended.getQueryParameter("pot"))
        assertEquals("137", appended.getQueryParameter("itag"))
    }

    @Test
    fun `appendPoTokenToGoogleVideoUri is no-op for non-googlevideo hosts`() {
        val original = Uri.parse("https://www.youtube.com/api/timedtext?lang=en")
        val appended = appendPoTokenToGoogleVideoUri(original, "TOKEN")
        assertEquals(original.toString(), appended.toString())
    }

    @Test
    fun `appendPoTokenToGoogleVideoUri does nothing when token is null`() {
        val original = Uri.parse("https://rr1---sn-xyz.googlevideo.com/videoplayback?itag=140")
        val appended = appendPoTokenToGoogleVideoUri(original, null)
        assertEquals(original.toString(), appended.toString())
    }
}
