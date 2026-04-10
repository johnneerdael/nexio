package com.nexio.tv.ui.screens.player

import androidx.test.core.app.ApplicationProvider
import com.nexio.tv.data.local.VodCacheSizeMode
import java.util.concurrent.Future
import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlayerMediaSourceFactoryDirectPathRegressionTest {

    @Test
    fun `disabled path does not start VOD warm ahead`() {
        val factory = PlayerMediaSourceFactory(
            context = ApplicationProvider.getApplicationContext(),
            basePlaybackOkHttpClient = OkHttpClient(),
            tracedPlaybackOkHttpClient = OkHttpClient()
        ).apply {
            useParallelConnections = false
            vodCacheSizeMode = VodCacheSizeMode.ON
        }

        factory.createMediaSource(
            url = "https://example.com/movie.mkv",
            headers = emptyMap()
        )
        factory.notifyPlaybackFirstFrameRendered()

        assertFalse(factory.getBooleanField("currentProgressiveIsEligibleForWarmAhead"))
        assertNull(factory.getFutureField("prefetchFuture"))
        factory.shutdown()
    }

    private fun PlayerMediaSourceFactory.getBooleanField(name: String): Boolean {
        val field = PlayerMediaSourceFactory::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.getBoolean(this)
    }

    private fun PlayerMediaSourceFactory.getFutureField(name: String): Future<*>? {
        val field = PlayerMediaSourceFactory::class.java.getDeclaredField(name)
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(this) as Future<*>?
    }
}
