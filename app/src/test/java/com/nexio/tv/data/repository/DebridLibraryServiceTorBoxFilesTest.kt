package com.nexio.tv.data.repository

import com.nexio.tv.data.remote.dto.debrid.TorBoxFileDto
import com.nexio.tv.data.remote.dto.debrid.TorBoxTorrentListItemDto
import com.nexio.tv.data.repository.DebridLibraryService.Companion.TorBoxNextFile
import com.nexio.tv.data.repository.DebridLibraryService.Companion.isTorBoxFilePlayable
import com.nexio.tv.data.repository.DebridLibraryService.Companion.pickNextFileInTorrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `null mimeType falls back to video extension match`() {
        val file = TorBoxFileDto(
            id = 1,
            name = "movie.mp4",
            shortName = "movie.mp4",
            size = 200L * 1024L * 1024L,
            mimeType = null,
        )
        // TorBox does not always populate mimetype. Filename has .mp4, size is over the
        // 50 MB floor, so this should be considered playable.
        assertTrue(isTorBoxFilePlayable(file))
    }

    @Test
    fun `null mimeType with non-video extension is not playable`() {
        val file = TorBoxFileDto(
            id = 1,
            name = "info.nfo",
            shortName = "info.nfo",
            size = 200L * 1024L * 1024L,
            mimeType = null,
        )
        assertFalse(isTorBoxFilePlayable(file))
    }

    @Test
    fun `null mimeType with no extension is not playable`() {
        val file = TorBoxFileDto(
            id = 1,
            name = "release",
            shortName = "release",
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

    @Test
    fun `pickNextFileInTorrent returns next file by alphabetical name`() {
        val torrent = TorBoxTorrentListItemDto(
            id = 7,
            name = "Show S01",
            files = listOf(
                TorBoxFileDto(
                    id = 12, name = "Show.S01E03.mkv", shortName = "Show.S01E03.mkv",
                    size = 500L * 1024L * 1024L, mimeType = "video/x-matroska",
                ),
                TorBoxFileDto(
                    id = 10, name = "Show.S01E01.mkv", shortName = "Show.S01E01.mkv",
                    size = 500L * 1024L * 1024L, mimeType = "video/x-matroska",
                ),
                TorBoxFileDto(
                    id = 11, name = "Show.S01E02.mkv", shortName = "Show.S01E02.mkv",
                    size = 500L * 1024L * 1024L, mimeType = "video/x-matroska",
                ),
            ),
        )

        val afterE1 = pickNextFileInTorrent(torrent, currentFileId = 10)
        assertEquals(TorBoxNextFile(torrentId = 7, fileId = 11, fileName = "Show.S01E02.mkv"), afterE1)

        val afterE3 = pickNextFileInTorrent(torrent, currentFileId = 12)
        assertNull(afterE3)
    }

    @Test
    fun `pickNextFileInTorrent skips unplayable files`() {
        val torrent = TorBoxTorrentListItemDto(
            id = 7,
            name = "Show S01",
            files = listOf(
                TorBoxFileDto(
                    id = 10, name = "Show.S01E01.mkv", shortName = "Show.S01E01.mkv",
                    size = 500L * 1024L * 1024L, mimeType = "video/x-matroska",
                ),
                TorBoxFileDto(
                    id = 99, name = "info.nfo", shortName = "info.nfo",
                    size = 4_096L, mimeType = "text/plain",
                ),
                TorBoxFileDto(
                    id = 11, name = "Show.S01E02.mkv", shortName = "Show.S01E02.mkv",
                    size = 500L * 1024L * 1024L, mimeType = "video/x-matroska",
                ),
            ),
        )

        val next = pickNextFileInTorrent(torrent, currentFileId = 10)
        assertEquals(11, next?.fileId)
    }

    @Test
    fun `buildTorBoxEntry produces an entry with null directPlaybackUrl`() {
        val torrent = TorBoxTorrentListItemDto(
            id = 7,
            name = "Movie",
            files = listOf(
                TorBoxFileDto(
                    id = 10, name = "movie.mkv", shortName = "movie.mkv",
                    size = 800L * 1024L * 1024L, mimeType = "video/x-matroska",
                ),
            ),
        )
        val file = torrent.files.first()
        val entry = DebridLibraryService.buildTorBoxEntry(torrent, file)
        assertNull(entry.directPlaybackUrl)
        assertEquals("tb:torrent:7:file:10", entry.id)
        assertEquals("movie.mkv", entry.playbackFilename)
    }

    @Test
    fun `pickNextFileInTorrent on single-file torrent returns null`() {
        val torrent = TorBoxTorrentListItemDto(
            id = 7,
            name = "Movie",
            files = listOf(
                TorBoxFileDto(
                    id = 10, name = "movie.mkv", shortName = "movie.mkv",
                    size = 1L * 1024L * 1024L * 1024L, mimeType = "video/x-matroska",
                ),
            ),
        )
        assertNull(pickNextFileInTorrent(torrent, currentFileId = 10))
    }
}
