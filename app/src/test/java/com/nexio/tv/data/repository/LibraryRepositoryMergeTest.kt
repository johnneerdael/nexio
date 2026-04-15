package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.LibraryEntry
import com.nexio.tv.domain.model.LibraryListTab
import com.nexio.tv.domain.model.LibrarySourceMode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.invariantSeparatorsPathString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryRepositoryMergeTest {

    @Test
    fun `local source mode still exposes debrid integration items when they are present`() {
        val debridItem = libraryEntry(
            id = "rd:torrent:abc:file:1",
            listKey = DebridLibraryService.REAL_DEBRID_LIST_KEY
        )

        val merged = mergeLibraryItemsForMode(
            mode = LibrarySourceMode.LOCAL,
            traktItems = emptyList(),
            simklItems = emptyList(),
            debridItems = listOf(debridItem)
        )

        assertEquals(listOf(debridItem), merged)
    }

    @Test
    fun `local source mode still exposes debrid integration tabs when they are present`() {
        val debridTab = LibraryListTab(
            key = DebridLibraryService.PREMIUMIZE_LIST_KEY,
            title = "Premiumize",
            type = LibraryListTab.Type.SERVICE
        )

        val merged = mergeLibraryTabsForMode(
            mode = LibrarySourceMode.LOCAL,
            traktTabs = emptyList(),
            simklTabs = emptyList(),
            debridTabs = listOf(debridTab)
        )

        assertEquals(listOf(debridTab), merged)
    }

    @Test
    fun `library view model actively refreshes debrid integrations on startup`() {
        val source = sourceFile("com/nexio/tv/ui/screens/library/LibraryViewModel.kt").toFile().readText()

        assertTrue(
            "LibraryViewModel should not rely on passive flow startup for Real-Debrid and Premiumize tabs",
            source.contains("observeDebridBootstrap()") &&
                source.contains("libraryRepository.refreshDebridNow()")
        )
    }

    private fun libraryEntry(id: String, listKey: String): LibraryEntry {
        return LibraryEntry(
            id = id,
            type = "movie",
            name = "Library File",
            poster = null,
            background = null,
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList(),
            addonBaseUrl = null,
            listKeys = setOf(listKey),
            directPlaybackUrl = "https://cdn.example.test/file.mkv"
        )
    }

    private fun sourceFile(relativePath: String): Path {
        val cwd = Paths.get("").toAbsolutePath().normalize()
        val relative = Paths.get("app", "src", "main", "java")
        val directCandidate = cwd.resolve(relative)
        val parentCandidate = cwd.resolve("..").resolve(relative).normalize()
        val sourceRoot = when {
            Files.exists(directCandidate) -> directCandidate
            Files.exists(parentCandidate) -> parentCandidate
            else -> error("Unable to locate app/src/main/java from working directory $cwd")
        }
        return sourceRoot.resolve(relativePath).also { path ->
            require(path.invariantSeparatorsPathString.endsWith(relativePath))
        }
    }
}
