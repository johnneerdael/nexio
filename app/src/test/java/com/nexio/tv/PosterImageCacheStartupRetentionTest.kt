package com.nexio.tv

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PosterImageCacheStartupRetentionTest {

    @Test
    fun `startup cleanup preserves existing poster disk cache files`() {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val application = NexioApplication().apply {
            Application::class.java
                .getDeclaredMethod("attach", Context::class.java)
                .apply { isAccessible = true }
                .invoke(this, baseContext)
        }
        val cachedPoster = File(application.cacheDir, "image_cache/poster-file.0").apply {
            parentFile?.mkdirs()
            writeText("cached-poster")
            setLastModified(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(14))
        }

        NexioApplication::class.java
            .getDeclaredMethod("retainPosterCacheOnStartup")
            .apply { isAccessible = true }
            .invoke(application)

        assertTrue(cachedPoster.exists())
    }
}
