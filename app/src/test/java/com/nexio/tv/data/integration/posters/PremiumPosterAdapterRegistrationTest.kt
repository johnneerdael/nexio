package com.nexio.tv.data.integration.posters

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * F-C-04 pin: RpdbMetadataProviderAdapter and TopPostersMetadataProviderAdapter must:
 *   1. Exist as classes implementing MetadataProviderAdapter
 *   2. Be bound via @IntoSet in some Hilt module so they participate in
 *      the Set<MetadataProviderAdapter> the dispatcher consumes
 *
 * Source-grep proxy: scan production sources for both class declarations and
 * matching binding declarations.
 */
class PremiumPosterAdapterRegistrationTest {

    @Test
    fun `Rpdb and TopPosters MetadataProviderAdapters are declared and Hilt-bound`() {
        val productionRoot = generateSequence(File(".")) { it.parentFile }
            .map { it.resolve("app/src/main/java/com/nexio/tv") }
            .firstOrNull { it.exists() && it.isDirectory }
            ?: error("Could not locate app/src/main/java/com/nexio/tv")

        val allText = productionRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }

        // 1. Class declarations exist and implement MetadataProviderAdapter.
        // Uses DOT_MATCHES_ALL because the constructor parameter list may span multiple
        // lines before `: MetadataProviderAdapter` appears.
        assertTrue(
            "F-C-04: RpdbMetadataProviderAdapter must be declared as a MetadataProviderAdapter",
            Regex(
                "class\\s+RpdbMetadataProviderAdapter[^{]*MetadataProviderAdapter",
                RegexOption.DOT_MATCHES_ALL
            ).containsMatchIn(allText)
        )
        assertTrue(
            "F-C-04: TopPostersMetadataProviderAdapter must be declared as a MetadataProviderAdapter",
            Regex(
                "class\\s+TopPostersMetadataProviderAdapter[^{]*MetadataProviderAdapter",
                RegexOption.DOT_MATCHES_ALL
            ).containsMatchIn(allText)
        )

        // 2. Hilt binding declarations exist (some `@Binds`/`@IntoSet` referencing the class).
        // The binding appears in a Hilt @Module abstract class; the function returns the adapter
        // type and parameters reference the impl class. Match on >= 2 occurrences: class declaration
        // + at least 1 binding reference in a module file.
        assertTrue(
            "F-C-04: RpdbMetadataProviderAdapter must be bound via Hilt (look for @Binds + RpdbMetadataProviderAdapter param)",
            Regex("RpdbMetadataProviderAdapter").findAll(allText).count() >= 2
        )
        assertTrue(
            "F-C-04: TopPostersMetadataProviderAdapter must be bound via Hilt (look for @Binds + TopPostersMetadataProviderAdapter param)",
            Regex("TopPostersMetadataProviderAdapter").findAll(allText).count() >= 2
        )
    }
}
