package com.nexio.tv.core.metadata.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MetadataCacheKeysTest {
    private val cacheKeys = MetadataCacheKeys()

    @Test
    fun `router decision key includes parent id source context and policy version`() {
        val key = cacheKeys.routerDecisionKey(
            parentId = " tt123 ",
            sourceContext = MetadataSourceContext(
                addonId = "crunchyroll",
                catalogId = "anime-popular"
            ),
            routingPolicyVersion = "3"
        )

        assertEquals(
            "router:v3:parent=tt123:addon=crunchyroll:catalog=anime-popular",
            key
        )
    }

    @Test
    fun `resolved document key changes when field policy version changes`() {
        val route = route()

        val fieldPolicyV1 = cacheKeys.resolvedDocumentKey(
            route = route,
            depth = MetadataDepth.DETAIL_CORE,
            fieldPolicyVersion = "fields-v1",
            artworkPolicyVersion = "artwork-v1"
        )
        val fieldPolicyV2 = cacheKeys.resolvedDocumentKey(
            route = route,
            depth = MetadataDepth.DETAIL_CORE,
            fieldPolicyVersion = "fields-v2",
            artworkPolicyVersion = "artwork-v1"
        )

        assertNotEquals(fieldPolicyV1, fieldPolicyV2)
    }

    @Test
    fun `artwork policy change invalidates artwork and resolved document but not provider metadata`() {
        val route = route()

        val providerMetadataV1 = cacheKeys.providerMetadataKey(
            provider = MetadataPrimaryProvider.TMDB,
            apiShapeId = "tmdb.movie.core",
            operationKey = "movie/550"
        )
        val providerMetadataV2 = cacheKeys.providerMetadataKey(
            provider = MetadataPrimaryProvider.TMDB,
            apiShapeId = "tmdb.movie.core",
            operationKey = "movie/550"
        )
        val resolvedDocumentV1 = cacheKeys.resolvedDocumentKey(
            route = route,
            depth = MetadataDepth.DETAIL_CORE,
            fieldPolicyVersion = "fields-v1",
            artworkPolicyVersion = "artwork-v1"
        )
        val resolvedDocumentV2 = cacheKeys.resolvedDocumentKey(
            route = route,
            depth = MetadataDepth.DETAIL_CORE,
            fieldPolicyVersion = "fields-v1",
            artworkPolicyVersion = "artwork-v2"
        )
        val artworkDecisionV1 = cacheKeys.artworkDecisionKey(route, artworkPolicyVersion = "artwork-v1")
        val artworkDecisionV2 = cacheKeys.artworkDecisionKey(route, artworkPolicyVersion = "artwork-v2")

        assertEquals(providerMetadataV1, providerMetadataV2)
        assertNotEquals(resolvedDocumentV1, resolvedDocumentV2)
        assertNotEquals(artworkDecisionV1, artworkDecisionV2)
    }

    @Test
    fun `cache keys never include hashCode evidence`() {
        val route = route()
        val keys = listOf(
            cacheKeys.providerMetadataKey(
                provider = MetadataPrimaryProvider.TMDB,
                apiShapeId = "tmdb.movie.core",
                operationKey = "movie/550"
            ),
            cacheKeys.routerDecisionKey(
                parentId = route.parentId,
                sourceContext = route.sourceContext,
                routingPolicyVersion = "router-v1"
            ),
            cacheKeys.resolvedDocumentKey(
                route = route,
                depth = MetadataDepth.DETAIL_CORE,
                fieldPolicyVersion = "fields-v1",
                artworkPolicyVersion = "artwork-v1"
            ),
            cacheKeys.artworkDecisionKey(route, artworkPolicyVersion = "artwork-v1"),
            cacheKeys.imageBlobKey(urlHash = "abc123")
        )

        keys.forEach { key ->
            assertFalse("key contains JVM identity hash evidence: $key", hashCodeEvidence.containsMatchIn(key))
        }
    }

    @Test
    fun `image blob key labels url hash as sha256`() {
        assertEquals("metadata:image-blob:sha256:abc123", cacheKeys.imageBlobKey(urlHash = "abc123"))
    }

    private fun route(): MetadataRoute =
        MetadataRoute(
            provider = MetadataPrimaryProvider.TMDB,
            parentId = "tmdb:550",
            mediaKind = MetadataMediaKind.MOVIE,
            reason = MetadataDecisionReason.PROVIDER_NATIVE_DIRECT,
            sourceContext = MetadataSourceContext(
                addonId = "cinemeta",
                catalogId = "popular"
            ),
            targetIds = mapOf(MetadataPrimaryProvider.TMDB to "tmdb:550"),
            trace = listOf(
                MetadataRouteTrace(
                    reason = MetadataDecisionReason.PROVIDER_NATIVE_DIRECT,
                    detail = "native"
                )
            )
        )

    private companion object {
        val hashCodeEvidence = Regex("""@[0-9a-fA-F]{6,}""")
    }
}
