package com.nexio.tv.architecture

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * F2-J-07 pin: getMetaFromAllAddons() callers MUST be allowlisted facade-routing paths.
 *
 * Cluster G F2-J-01 closed the Home-specific case (AddonFirstPaintShapeArchitectureTest).
 * This pin extends coverage to ALL production source — no new callers may call
 * getMetaFromAllAddons() without being added to the allowlist and reviewed for
 * proper repository-layer routing.
 *
 * Allowlisted callers are either:
 *  - The interface declaration and canonical implementation (MetaRepository / MetaRepositoryImpl)
 *  - Repository-layer services that legitimately need addon metadata for data enrichment
 *  - Screen ViewModels and controllers that call through the MetaRepository interface
 *    (not bypassing the facade — they depend on the interface, not direct addon clients)
 */
class HomeAddonHydrationFacadeBypassPinTest {

    @Test
    fun `getMetaFromAllAddons is called only by allowlisted production callers`() {
        val productionRoot = generateSequence(File(".")) { it.parentFile }
            .map { it.resolve("app/src/main/java/com/nexio/tv") }
            .firstOrNull { it.exists() && it.isDirectory }
            ?: error("Could not locate app/src/main/java/com/nexio/tv production root")

        // Allowlisted callers: interface declaration, canonical implementation, and
        // intentional repository/screen callers reviewed at F2-J-07 audit time.
        val allowlist = setOf(
            // Interface declaration and canonical implementation
            "domain/repository/MetaRepository.kt",
            "data/repository/MetaRepositoryImpl.kt",
            // Repository-layer data enrichment services
            "data/repository/ContinueWatchingSnapshotService.kt",
            "data/repository/IdleScreensaverPreparation.kt",
            "data/repository/SimklDiscoveryService.kt",
            "data/repository/SimklLibraryService.kt",
            "data/repository/TraktDiscoveryService.kt",
            "data/repository/TraktLibraryService.kt",
            "data/repository/TraktProgressService.kt",
            "data/repository/WatchProgressRepositoryImpl.kt",
            // Screen-layer callers going through MetaRepository interface (not direct addon clients)
            "ui/screens/detail/MetaDetailsViewModel.kt",
            "ui/screens/player/PlayerRuntimeControllerMetadata.kt",
            "ui/screens/player/PlayerRuntimeControllerStreams.kt",
            "ui/screens/stream/StreamScreenViewModel.kt"
            // NOTE: ui/screens/home/** is enforced by AddonFirstPaintShapeArchitectureTest (F2-J-01)
        )

        val pattern = Regex("""\bgetMetaFromAllAddons\s*\(""")
        val offenders = productionRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { f ->
                val rel = f.relativeTo(productionRoot).invariantSeparatorsPath
                rel !in allowlist && pattern.containsMatchIn(f.readText())
            }
            .map { it.relativeTo(productionRoot).invariantSeparatorsPath }
            .sorted()
            .toList()

        assertEquals(
            "getMetaFromAllAddons must only be called from allowlisted production callers. " +
                "Home-specific enforcement is in AddonFirstPaintShapeArchitectureTest (F2-J-01). " +
                "New callers must be reviewed and added to this allowlist.",
            emptyList<String>(),
            offenders
        )
    }
}
