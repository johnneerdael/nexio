package com.nexio.tv.data.repository

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class TraktMutationRoutingAuditTest {

    @Test
    fun `all known direct Trakt write endpoints stay covered by the routing audit`() {
        val actualMethods = findTrackedMutationCallSites()
            .map { it.method }
            .toSortedSet()

        assertEquals(
            "Tracked Trakt mutation methods drifted. Audit new write surfaces before merging direct calls.",
            expectedTrackedMethods,
            actualMethods
        )
    }

    @Test
    fun `direct Trakt write call sites stay confined to audited owners`() {
        val actualCounts = findTrackedMutationCallSites()
            .groupingBy { "${it.path}:${it.method}" }
            .eachCount()
            .toSortedMap()

        assertEquals(
            buildString {
                appendLine("Unexpected direct Trakt mutation call sites detected.")
                appendLine("Route new writes through the shared Trakt outbox/adapter layer,")
                appendLine("or update this audit only when ownership intentionally changes.")
            },
            expectedCallSiteCounts,
            actualCounts
        )
    }

    private fun findTrackedMutationCallSites(): List<MutationCallSite> {
        val regex = Regex("""traktApi\.(\w+)\(""")
        return repositoryDir
            .resolve("app/src/main/java/com/nexio/tv/data/integration/trakt")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    val match = regex.find(line) ?: return@mapIndexedNotNull null
                    val method = match.groupValues[1]
                    if (method !in expectedTrackedMethods) return@mapIndexedNotNull null
                    MutationCallSite(
                        path = file.relativeTo(repositoryDir).invariantSeparatorsPath,
                        method = method,
                        lineNumber = index + 1
                    )
                }
            }
            .toList()
    }

    private data class MutationCallSite(
        val path: String,
        val method: String,
        val lineNumber: Int
    )

    private companion object {
        private val repositoryDir: File = sequenceOf(File("."), File(".."))
            .map { it.absoluteFile.normalize() }
            .firstOrNull { candidate ->
	                candidate.resolve("app/src/main/java/com/nexio/tv/data/integration/trakt").isDirectory
            }
            ?: error("Unable to resolve repository root for Trakt mutation routing audit")

        private val expectedTrackedMethods = sortedSetOf(
            "addHistory",
            "addToWatchlist",
            "addUserListItems",
            "checkin",
            "createUserList",
            "deletePlayback",
            "deleteUserList",
            "hideRecommendation",
            "removeFromWatchlist",
            "removeHistory",
            "removeUserListItems",
            "reorderUserLists",
            "scrobblePause",
            "scrobbleStart",
            "scrobbleStop",
            "updateUserList"
        )

        private val expectedCallSiteCounts = sortedMapOf(
            "app/src/main/java/com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt:addHistory" to 2,
            "app/src/main/java/com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt:addToWatchlist" to 2,
            "app/src/main/java/com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt:addUserListItems" to 2,
            "app/src/main/java/com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt:checkin" to 1,
            "app/src/main/java/com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt:createUserList" to 2,
            "app/src/main/java/com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt:deletePlayback" to 2,
            "app/src/main/java/com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt:deleteUserList" to 2,
            "app/src/main/java/com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt:hideRecommendation" to 1,
            "app/src/main/java/com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt:removeFromWatchlist" to 2,
            "app/src/main/java/com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt:removeHistory" to 2,
            "app/src/main/java/com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt:removeUserListItems" to 2,
            "app/src/main/java/com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt:reorderUserLists" to 2,
            "app/src/main/java/com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt:scrobblePause" to 1,
            "app/src/main/java/com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt:scrobbleStart" to 1,
            "app/src/main/java/com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt:scrobbleStop" to 1,
            "app/src/main/java/com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt:updateUserList" to 2
        )
    }
}
