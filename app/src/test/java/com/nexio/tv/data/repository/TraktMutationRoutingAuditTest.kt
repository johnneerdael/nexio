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
            .resolve("app/src/main/java/com/nexio/tv/data/repository")
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
                candidate.resolve("app/src/main/java/com/nexio/tv/data/repository").isDirectory
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
            "app/src/main/java/com/nexio/tv/data/repository/trakt/TraktLibraryMutationExecutor.kt:addToWatchlist" to 2,
            "app/src/main/java/com/nexio/tv/data/repository/trakt/TraktLibraryMutationExecutor.kt:addUserListItems" to 2,
            "app/src/main/java/com/nexio/tv/data/repository/trakt/TraktLibraryMutationExecutor.kt:createUserList" to 2,
            "app/src/main/java/com/nexio/tv/data/repository/trakt/TraktLibraryMutationExecutor.kt:deleteUserList" to 2,
            "app/src/main/java/com/nexio/tv/data/repository/trakt/TraktLibraryMutationExecutor.kt:removeFromWatchlist" to 2,
            "app/src/main/java/com/nexio/tv/data/repository/trakt/TraktLibraryMutationExecutor.kt:removeUserListItems" to 2,
            "app/src/main/java/com/nexio/tv/data/repository/trakt/TraktLibraryMutationExecutor.kt:reorderUserLists" to 2,
            "app/src/main/java/com/nexio/tv/data/repository/trakt/TraktLibraryMutationExecutor.kt:updateUserList" to 2,
            "app/src/main/java/com/nexio/tv/data/repository/trakt/TraktProgressMutationExecutor.kt:addHistory" to 2,
            "app/src/main/java/com/nexio/tv/data/repository/trakt/TraktProgressHistoryMutationAdapter.kt:addHistory" to 1,
            "app/src/main/java/com/nexio/tv/data/repository/trakt/TraktProgressMutationExecutor.kt:deletePlayback" to 2,
            "app/src/main/java/com/nexio/tv/data/repository/trakt/TraktProgressHistoryMutationAdapter.kt:deletePlayback" to 1,
            "app/src/main/java/com/nexio/tv/data/repository/trakt/TraktProgressMutationExecutor.kt:removeHistory" to 2,
            "app/src/main/java/com/nexio/tv/data/repository/trakt/TraktProgressHistoryMutationAdapter.kt:removeHistory" to 1,
            "app/src/main/java/com/nexio/tv/data/repository/trakt/TraktScrobbleMutationAdapter.kt:checkin" to 1,
            "app/src/main/java/com/nexio/tv/data/repository/trakt/TraktScrobbleMutationAdapter.kt:scrobblePause" to 1,
            "app/src/main/java/com/nexio/tv/data/repository/trakt/TraktScrobbleMutationAdapter.kt:scrobbleStart" to 1,
            "app/src/main/java/com/nexio/tv/data/repository/trakt/TraktScrobbleMutationAdapter.kt:scrobbleStop" to 1,
            "app/src/main/java/com/nexio/tv/data/repository/trakt/TraktDiscoveryMutationAdapter.kt:hideRecommendation" to 1
        )
    }
}
