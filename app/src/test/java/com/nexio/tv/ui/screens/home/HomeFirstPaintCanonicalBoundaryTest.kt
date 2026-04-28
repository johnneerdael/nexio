package com.nexio.tv.ui.screens.home

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * F-I-02 + F-02-01 architecture pin: `metadata.first_paint` MUST be emitted only from the
 * canonical Home presentation boundary (`buildCatalogItem` in `ModernHomeModels.kt`),
 * NOT from router pre-flight sites (`HomeViewModelPresentationPipeline.kt`'s
 * `fetchProviderEnrichmentForPreview`).
 *
 * If this test fails, someone re-introduced a `.toFirstPaintHomeDisplayMetadata()` call from
 * a non-canonical site, which would emit spurious first-paint events and confuse trace bundles.
 */
class HomeFirstPaintCanonicalBoundaryTest {

    private val productionRoot = File("app/src/main/java/com/nexio/tv/ui/screens/home")
    private val canonicalEmitterFiles = setOf(
        "ModernHomeModels.kt",        // buildCatalogItem (canonical Home tile boundary)
        "HomeFirstPaintMetadataMapper.kt"  // the mapper itself
    )

    @Test
    fun `toFirstPaintHomeDisplayMetadata is only invoked from canonical boundaries`() {
        require(productionRoot.exists() && productionRoot.isDirectory) {
            "Production root not found at ${productionRoot.absolutePath}"
        }

        val offenders = productionRoot.walkTopDown()
            .filter { it.extension == "kt" }
            .filter { it.name !in canonicalEmitterFiles }
            .filter { f ->
                val text = f.readText()
                Regex("\\btoFirstPaintHomeDisplayMetadata\\(\\)").containsMatchIn(text)
            }
            .map { it.relativeTo(productionRoot).path }
            .toList()

        assertEquals(
            "F-I-02: toFirstPaintHomeDisplayMetadata must only be called from canonical first-paint " +
                "boundaries (currently $canonicalEmitterFiles). Other call sites must use the pure " +
                "toHomeDisplayMetadata() extension. Offenders: $offenders",
            emptyList<String>(),
            offenders
        )
    }
}
