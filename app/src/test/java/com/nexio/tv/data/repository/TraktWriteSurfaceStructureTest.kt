package com.nexio.tv.data.repository

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile
import kotlin.streams.asSequence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TraktWriteSurfaceStructureTest {

    @Test
    fun trackedTraktWriteApisStayConfinedToDedicatedMutationServices() {
        val findings = trackedWriteMethods.associateWith { method ->
            findCallSites(Regex("""\btraktApi\.$method\s*\("""))
        }

        trackedWriteMethods.forEach { method ->
            assertTrue(
                "Expected to find at least one tracked call site for $method",
                findings.getValue(method).isNotEmpty(),
            )
        }

        assertEquals(
            mapOf(
                "addHistory" to setOf(
                    "com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt"
                ),
                "removeHistory" to setOf(
                    "com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt"
                ),
                "deletePlayback" to setOf(
                    "com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt"
                ),
                "addToWatchlist" to setOf("com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt"),
                "removeFromWatchlist" to setOf("com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt"),
                "addUserListItems" to setOf("com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt"),
                "removeUserListItems" to setOf("com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt"),
                "createUserList" to setOf("com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt"),
                "updateUserList" to setOf("com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt"),
                "deleteUserList" to setOf("com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt"),
                "reorderUserLists" to setOf("com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt"),
                "hideRecommendation" to setOf("com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt"),
                "checkin" to setOf("com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt"),
                "scrobbleStart" to setOf("com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt"),
                "scrobblePause" to setOf("com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt"),
                "scrobbleStop" to setOf("com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt"),
            ),
            findings,
        )
    }

    @Test
    fun trackedTraktWriteHelpersStayInsideDedicatedMutationServices() {
        val callSites = findCallSites(Regex("""\btraktAuthService\.executeAuthorizedWriteRequest\b"""))

        assertEquals(emptySet<String>(), callSites)
    }

    private fun findCallSites(pattern: Regex): Set<String> {
        val root = sourceRoot()
        return Files.walk(root).use { paths ->
            paths.asSequence()
                .filter { it.isRegularFile() && it.fileName.toString().endsWith(".kt") }
                .mapNotNull { path ->
                    val relativePath = root.relativize(path).invariantSeparatorsPathString
                    val source = path.toFile().readText()
                    relativePath.takeIf { pattern.containsMatchIn(source) }
                }
                .toSet()
        }
    }

    private fun sourceRoot(): Path {
        val cwd = Paths.get("").toAbsolutePath().normalize()
        val relative = Paths.get("app", "src", "main", "java")
        val directCandidate = cwd.resolve(relative)
        val parentCandidate = cwd.resolve("..").resolve(relative).normalize()
        return when {
            Files.exists(directCandidate) -> directCandidate
            Files.exists(parentCandidate) -> parentCandidate
            else -> error("Unable to locate app/src/main/java from working directory $cwd")
        }
    }

    private companion object {
        val trackedWriteMethods = listOf(
            "addHistory",
            "removeHistory",
            "deletePlayback",
            "addToWatchlist",
            "removeFromWatchlist",
            "addUserListItems",
            "removeUserListItems",
            "createUserList",
            "updateUserList",
            "deleteUserList",
            "reorderUserLists",
            "hideRecommendation",
            "checkin",
            "scrobbleStart",
            "scrobblePause",
            "scrobbleStop",
        )
    }
}
