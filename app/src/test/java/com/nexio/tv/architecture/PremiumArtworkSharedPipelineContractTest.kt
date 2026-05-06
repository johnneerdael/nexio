package com.nexio.tv.architecture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PremiumArtworkSharedPipelineContractTest {
    @Test
    fun `application does not manually construct partial artwork asset repository`() {
        val source = File("app/src/main/java/com/nexio/tv/NexioApplication.kt").readText()

        assertFalse(
            "NexioApplication must use the injected NexioArtworkFetcher.Factory, not construct ArtworkAssetRepository manually.",
            source.contains("ArtworkAssetRepository(")
        )
        assertTrue(source.contains("@Inject lateinit var nexioArtworkFetcherFactory: NexioArtworkFetcher.Factory"))
    }

    @Test
    fun `integration runtime module binds artwork byte loader and disk cache`() {
        val source = File("app/src/main/java/com/nexio/tv/core/di/IntegrationRuntimeModule.kt").readText()

        assertTrue(source.contains("provideArtworkAssetDiskCache"))
        assertTrue(source.contains("provideArtworkByteLoader"))
        assertTrue(source.contains("provideArtworkPosterTransport"))
        assertTrue(source.contains("provideArtworkProviderSettingsSource"))
        assertTrue(source.contains("provideArtworkCredentialResolver"))
    }

    @Test
    fun `default byte loader is not called directly by production code`() {
        val productionReferences = File("app/src/main/java/com/nexio/tv")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.name == "DefaultArtworkByteLoader.kt" }
            .filterNot { it.name == "IntegrationRuntimeModule.kt" }
            .joinToString("\n") { it.readText() }
        val repository = File("app/src/main/java/com/nexio/tv/core/artwork/ArtworkAssetRepository.kt").readText()

        assertFalse(
            "DefaultArtworkByteLoader must stay behind ArtworkAssetRepository and IntegrationRuntime.",
            productionReferences.contains("DefaultArtworkByteLoader(")
        )
        assertTrue(repository.contains("runtime.get("))
        assertTrue(repository.contains("byteLoader.load(materialized.source, decision)"))
    }

    @Test
    fun `premium poster adapters emit nexio artwork refs instead of integration poster refs`() {
        val rpdb = File("app/src/main/java/com/nexio/tv/data/integration/posters/RpdbMetadataProviderAdapter.kt").readText()
        val topPosters = File("app/src/main/java/com/nexio/tv/data/integration/posters/TopPostersMetadataProviderAdapter.kt").readText()
        val combined = rpdb + "\n" + topPosters

        assertTrue(combined.contains("resolvePosterArtworkString"))
        assertFalse(combined.contains("resolvePosterUrl("))
        assertFalse(combined.contains("integration-poster://"))
        assertFalse(combined.contains("api.ratingposterdb.com"))
        assertFalse(combined.contains("api.top-posters.com"))
    }

    @Test
    fun `production metadata paths do not call legacy premium poster url resolver`() {
        val offenders = File("app/src/main/java/com/nexio/tv")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.invariantSeparatorsPath.endsWith("/core/poster/PosterRatingsUrlResolver.kt") }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if (line.contains("resolvePosterUrl(")) {
                        "${file.invariantSeparatorsPath}:${index + 1}:resolvePosterUrl"
                    } else {
                        null
                    }
                }
            }
            .toList()

        assertTrue(
            "Production metadata/display paths must use resolvePosterArtworkRef/String so premium posters stay in the shared artwork pipeline:\n" +
                offenders.joinToString(separator = "\n"),
            offenders.isEmpty()
        )
    }
}
