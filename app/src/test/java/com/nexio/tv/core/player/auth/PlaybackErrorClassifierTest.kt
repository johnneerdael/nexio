package com.nexio.tv.core.player.auth

import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaybackErrorClassifierTest {
    private val dataSpec = DataSpec(Uri.parse("https://example.com/movie.mp4"))

    @Test
    fun `401 invalid response code is classified as link expired`() {
        val exception = wrap(
            HttpDataSource.InvalidResponseCodeException(
                401,
                "Unauthorized",
                null,
                emptyMap(),
                dataSpec,
                ByteArray(0)
            )
        )

        assertEquals(
            PlaybackErrorClassifier.Classification.LinkExpired,
            PlaybackErrorClassifier.classify(exception)
        )
    }

    @Test
    fun `410 invalid response code is classified as link expired`() {
        val exception = wrap(
            HttpDataSource.InvalidResponseCodeException(
                410,
                "Gone",
                null,
                emptyMap(),
                dataSpec,
                ByteArray(0)
            )
        )

        assertEquals(
            PlaybackErrorClassifier.Classification.LinkExpired,
            PlaybackErrorClassifier.classify(exception)
        )
    }

    @Test
    fun `403 invalid response code is classified as forbidden`() {
        val exception = wrap(
            HttpDataSource.InvalidResponseCodeException(
                403,
                "Forbidden",
                null,
                emptyMap(),
                dataSpec,
                ByteArray(0)
            )
        )

        assertEquals(
            PlaybackErrorClassifier.Classification.Forbidden,
            PlaybackErrorClassifier.classify(exception)
        )
    }

    @Test
    fun `non-auth invalid response code is classified as generic`() {
        val exception = wrap(
            HttpDataSource.InvalidResponseCodeException(
                416,
                "Range Not Satisfiable",
                null,
                emptyMap(),
                dataSpec,
                ByteArray(0)
            )
        )

        assertEquals(
            PlaybackErrorClassifier.Classification.Generic,
            PlaybackErrorClassifier.classify(exception)
        )
    }

    @Test
    fun `nested invalid response code is classified`() {
        val http = HttpDataSource.InvalidResponseCodeException(
            403,
            "Forbidden",
            null,
            emptyMap(),
            dataSpec,
            ByteArray(0)
        )
        val exception = wrap(IllegalStateException("outer", http))

        assertEquals(
            PlaybackErrorClassifier.Classification.Forbidden,
            PlaybackErrorClassifier.classify(exception)
        )
    }

    @Test
    fun `non-http exception classifies to generic`() {
        val exception = PlaybackException(
            "decode",
            IllegalStateException(),
            PlaybackException.ERROR_CODE_DECODING_FAILED
        )

        assertEquals(
            PlaybackErrorClassifier.Classification.Generic,
            PlaybackErrorClassifier.classify(exception)
        )
    }

    private fun wrap(cause: Throwable): PlaybackException = PlaybackException(
        "open",
        cause,
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
    )
}
