package com.nexio.tv.data.repository

import com.nexio.tv.data.remote.dto.debrid.TorBoxFileDto
import com.nexio.tv.data.repository.DebridLibraryService.Companion.isTorBoxFilePlayable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebridLibraryServiceTorBoxFilesTest {

    @Test
    fun `video mp4 of 51MB is playable`() {
        val file = TorBoxFileDto(
            id = 1,
            name = "movie.mp4",
            shortName = "movie.mp4",
            size = 51L * 1024L * 1024L,
            mimeType = "video/mp4",
        )
        assertTrue(isTorBoxFilePlayable(file))
    }

    @Test
    fun `video mp4 of 47MB is not playable`() {
        val file = TorBoxFileDto(
            id = 1,
            name = "movie.mp4",
            shortName = "movie.mp4",
            size = 47L * 1024L * 1024L,
            mimeType = "video/mp4",
        )
        assertFalse(isTorBoxFilePlayable(file))
    }

    @Test
    fun `null mimeType is not playable even if extension is mp4`() {
        val file = TorBoxFileDto(
            id = 1,
            name = "movie.mp4",
            shortName = "movie.mp4",
            size = 200L * 1024L * 1024L,
            mimeType = null,
        )
        assertFalse(isTorBoxFilePlayable(file))
    }

    @Test
    fun `nfo file is not playable`() {
        val file = TorBoxFileDto(
            id = 1,
            name = "info.nfo",
            shortName = "info.nfo",
            size = 4_096L,
            mimeType = "text/plain",
        )
        assertFalse(isTorBoxFilePlayable(file))
    }

    @Test
    fun `srt subtitle is not playable`() {
        val file = TorBoxFileDto(
            id = 1,
            name = "movie.srt",
            shortName = "movie.srt",
            size = 200_000L,
            mimeType = "application/x-subrip",
        )
        assertFalse(isTorBoxFilePlayable(file))
    }

    @Test
    fun `null size is not playable`() {
        val file = TorBoxFileDto(
            id = 1,
            name = "movie.mp4",
            shortName = "movie.mp4",
            size = null,
            mimeType = "video/mp4",
        )
        assertFalse(isTorBoxFilePlayable(file))
    }

    @Test
    fun `webm video over threshold is playable`() {
        val file = TorBoxFileDto(
            id = 2,
            name = "clip.webm",
            shortName = "clip.webm",
            size = 300L * 1024L * 1024L,
            mimeType = "video/webm",
        )
        assertTrue(isTorBoxFilePlayable(file))
    }
}
