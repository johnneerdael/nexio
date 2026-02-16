package com.nuvio.tv.data.repository

import com.nuvio.tv.data.remote.api.AniSkipApi
import com.nuvio.tv.data.remote.api.AniSkipInterval
import com.nuvio.tv.data.remote.api.AniSkipResponse
import com.nuvio.tv.data.remote.api.AniSkipResult
import com.nuvio.tv.data.remote.api.ArmApi
import com.nuvio.tv.data.remote.api.ArmEntry
import com.nuvio.tv.data.remote.api.IntroDbApi
import com.nuvio.tv.data.remote.api.IntroDbSegment
import com.nuvio.tv.data.remote.api.IntroDbSegmentsResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

class SkipIntroRepositoryTest {
    private val introDbApi = mockk<IntroDbApi>()
    private val aniSkipApi = mockk<AniSkipApi>()
    private val armApi = mockk<ArmApi>()

    private lateinit var repository: SkipIntroRepository

    @Before
    fun setUp() {
        repository = SkipIntroRepository(
            introDbApi = introDbApi,
            aniSkipApi = aniSkipApi,
            armApi = armApi
        )
        setIntroDbConfigured(repository, true)
    }

    @Test
    fun `maps intro segment from seconds`() = runTest {
        coEvery { introDbApi.getSegments("tt100", 1, 1) } returns Response.success(
            IntroDbSegmentsResponse(
                intro = IntroDbSegment(startSec = 10.0, endSec = 42.0)
            )
        )
        coEvery { armApi.resolve(any()) } returns Response.success(emptyList())

        val result = repository.getSkipIntervals("tt100", 1, 1)

        assertEquals(1, result.size)
        assertEquals("intro", result[0].type)
        assertEquals(10.0, result[0].startTime, 0.0001)
        assertEquals(42.0, result[0].endTime, 0.0001)
        assertEquals("introdb", result[0].provider)
    }

    @Test
    fun `maps intro recap outro and keeps order`() = runTest {
        coEvery { introDbApi.getSegments("tt101", 2, 5) } returns Response.success(
            IntroDbSegmentsResponse(
                intro = IntroDbSegment(startSec = 5.0, endSec = 30.0),
                recap = IntroDbSegment(startSec = 31.0, endSec = 55.0),
                outro = IntroDbSegment(startSec = 1200.0, endSec = 1260.0)
            )
        )
        coEvery { armApi.resolve(any()) } returns Response.success(emptyList())

        val result = repository.getSkipIntervals("tt101", 2, 5)

        assertEquals(listOf("intro", "recap", "outro"), result.map { it.type })
    }

    @Test
    fun `ignores null segments and invalid ranges`() = runTest {
        coEvery { introDbApi.getSegments("tt102", 1, 3) } returns Response.success(
            IntroDbSegmentsResponse(
                intro = IntroDbSegment(startSec = 30.0, endSec = 30.0),
                recap = null,
                outro = IntroDbSegment(startSec = 500.0, endSec = 480.0)
            )
        )
        coEvery { armApi.resolve(any()) } returns Response.success(emptyList())

        val result = repository.getSkipIntervals("tt102", 1, 3)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `falls back to millisecond timestamps when second values missing`() = runTest {
        coEvery { introDbApi.getSegments("tt103", 1, 4) } returns Response.success(
            IntroDbSegmentsResponse(
                intro = IntroDbSegment(startMs = 12_000L, endMs = 47_500L)
            )
        )
        coEvery { armApi.resolve(any()) } returns Response.success(emptyList())

        val result = repository.getSkipIntervals("tt103", 1, 4)

        assertEquals(1, result.size)
        assertEquals(12.0, result[0].startTime, 0.0001)
        assertEquals(47.5, result[0].endTime, 0.0001)
    }

    @Test
    fun `uses aniskip when introdb returns no valid intervals`() = runTest {
        coEvery { introDbApi.getSegments("tt104", 1, 6) } returns Response.success(
            IntroDbSegmentsResponse()
        )
        coEvery { armApi.resolve("tt104") } returns Response.success(listOf(ArmEntry(myanimelist = 9001)))
        coEvery {
            aniSkipApi.getSkipTimes(
                malId = "9001",
                episode = 6,
                types = any(),
                episodeLength = any()
            )
        } returns Response.success(
            AniSkipResponse(
                found = true,
                results = listOf(
                    AniSkipResult(
                        interval = AniSkipInterval(startTime = 60.0, endTime = 95.0),
                        skipType = "op"
                    )
                )
            )
        )

        val result = repository.getSkipIntervals("tt104", 1, 6)

        assertEquals(1, result.size)
        assertEquals("aniskip", result[0].provider)
        coVerify(exactly = 1) { aniSkipApi.getSkipTimes(any(), any(), any(), any()) }
    }

    @Test
    fun `does not call aniskip when introdb provides intervals`() = runTest {
        coEvery { introDbApi.getSegments("tt105", 1, 7) } returns Response.success(
            IntroDbSegmentsResponse(
                intro = IntroDbSegment(startSec = 15.0, endSec = 40.0)
            )
        )

        val result = repository.getSkipIntervals("tt105", 1, 7)

        assertEquals(1, result.size)
        assertEquals("introdb", result[0].provider)
        coVerify(exactly = 0) { armApi.resolve(any()) }
        coVerify(exactly = 0) { aniSkipApi.getSkipTimes(any(), any(), any(), any()) }
    }

    private fun setIntroDbConfigured(repository: SkipIntroRepository, value: Boolean) {
        val field = repository.javaClass.getDeclaredField("introDbConfigured")
        field.isAccessible = true
        field.setBoolean(repository, value)
    }
}
