package com.nexio.tv.ui.screens.library

import com.nexio.tv.domain.model.LibraryEntry
import com.nexio.tv.domain.model.PosterShape
import org.junit.Assert.assertEquals
import org.junit.Test

class DebridRowPresentationTest {

    @Test
    fun `presentation keeps filename as the only rendered title`() {
        val item = debridItem(
            name = "Daredevil Born Again S02E02 Shoot the Moon 2160p DSNP WEB-DL DD 5 1 Atmos HDR H 265-playWEB",
            playbackFilename = "Daredevil Born Again S02E02 Shoot the Moon 2160p DSNP WEB-DL DD 5 1 Atmos HDR H 265-playWEB.mkv",
            playbackStreamName = "Daredevil Born Again S02E02 Shoot the Moon 2160p DSNP WEB-DL DD 5 1 Atmos HDR H 265-playWEB"
        )

        assertEquals(
            "Daredevil Born Again S02E02 Shoot the Moon 2160p DSNP WEB-DL DD 5 1 Atmos HDR H 265-playWEB.mkv",
            debridRowPresentation(item).title
        )
    }

    @Test
    fun `presentation falls back from filename to stream name to library title`() {
        val streamNameOnly = debridItem(
            name = "Library title",
            playbackFilename = null,
            playbackStreamName = "Movie.File.1080p.mkv"
        )
        val titleOnly = debridItem(
            name = "Library title",
            playbackFilename = null,
            playbackStreamName = null
        )

        assertEquals("Movie.File.1080p.mkv", debridRowPresentation(streamNameOnly).title)
        assertEquals("Library title", debridRowPresentation(titleOnly).title)
    }

    private fun debridItem(
        name: String,
        playbackFilename: String?,
        playbackStreamName: String?
    ): LibraryEntry {
        return LibraryEntry(
            id = "rd:1",
            type = "movie",
            name = name,
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = "/$name",
            releaseInfo = "4.2 GB",
            imdbRating = null,
            genres = emptyList(),
            addonBaseUrl = null,
            playbackFilename = playbackFilename,
            playbackStreamName = playbackStreamName
        )
    }
}
