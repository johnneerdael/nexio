package com.nexio.tv.ui.screens.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nexio.tv.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProviderPrecedenceCopyTest {

    @Test
    fun `provider precedence summary states tvdb tmdb and poster ratings order`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val summary = context.getString(R.string.provider_precedence_summary)

        assertTrue(summary.contains("TVDB is used for TV metadata when configured"))
        assertTrue(summary.contains("TMDB remains movie metadata and TV fallback"))
        assertTrue(summary.contains("Poster-ratings providers override supported poster artwork"))
    }
}
