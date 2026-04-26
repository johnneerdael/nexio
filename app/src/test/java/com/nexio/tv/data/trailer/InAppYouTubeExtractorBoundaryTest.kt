package com.nexio.tv.data.trailer

import com.nexio.tv.architecture.sourceTextScan
import org.junit.Assert.fail
import org.junit.Test

class InAppYouTubeExtractorBoundaryTest {
    @Test
    fun `extractor no longer mentions raw okhttp request primitives`() {
        val offenders = sourceTextScan(
            forbiddenPatterns = listOf(
                "OkHttpClient",
                "Request",
                "Headers",
                "RequestBody",
                "newCall"
            ),
            allowedPaths = emptyList()
        ).filter { it.contains("app/src/main/java/com/nexio/tv/data/trailer/InAppYouTubeExtractor.kt:") }

        if (offenders.isNotEmpty()) {
            fail("InAppYouTubeExtractor still references raw transport primitives: $offenders")
        }
    }
}
