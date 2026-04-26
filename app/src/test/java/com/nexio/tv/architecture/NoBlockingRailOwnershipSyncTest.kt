package com.nexio.tv.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class NoBlockingRailOwnershipSyncTest {
    @Test
    fun `snapshot stores do not own rail sync authority`() {
        val offenders = listOf(
            "app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt",
            "app/src/main/java/com/nexio/tv/data/local/TraktLibrarySnapshotStore.kt",
            "app/src/main/java/com/nexio/tv/data/local/SimklLibrarySnapshotStore.kt"
        ).map(::File)
            .filter { file ->
                val text = file.readText()
                text.contains("runBlocking") ||
                    text.contains("IntegrationOwnershipService") ||
                    text.contains("queueOwnershipSync")
            }
            .map { it.path }

        assertTrue("Snapshot stores still own rail sync authority: $offenders", offenders.isEmpty())
    }
}
