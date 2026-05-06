package com.nexio.tv.core.artwork

import com.nexio.tv.core.integration.IntegrationLoadResult
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.data.integration.posters.transport.PosterTransportResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultArtworkByteLoaderTest {
    @Test
    fun `rpdb provider template builds redacted-safe poster request`() = runTest {
        val transport = RecordingArtworkPosterTransport(
            PosterTransportResult(statusCode = 200, isSuccessful = true, body = "rpdb".toByteArray())
        )
        val loader = DefaultArtworkByteLoader(
            credentialResolver = StaticCredentialResolver(IntegrationProvider.RPDB, "credentialhash", "rpdb-key"),
            posterTransport = transport
        )
        val decision = templateDecision(
            provider = IntegrationProvider.RPDB,
            idType = "imdb",
            mediaId = "tt0137523",
            credentialHash = "credentialhash",
            imageType = ArtworkType.POSTER
        )

        val result = loader.load(decision.selectedCandidate.providerTemplate!!.toSource(), decision)

        assertTrue(result is IntegrationLoadResult.Success)
        assertArrayEquals("rpdb".toByteArray(), (result as IntegrationLoadResult.Success).value)
        assertEquals("https://api.ratingposterdb.com/rpdb-key/imdb/poster-default/tt0137523.jpg", transport.lastUrl)
    }

    @Test
    fun `top posters provider template builds poster request`() = runTest {
        val transport = RecordingArtworkPosterTransport(
            PosterTransportResult(statusCode = 200, isSuccessful = true, body = "top".toByteArray())
        )
        val loader = DefaultArtworkByteLoader(
            credentialResolver = StaticCredentialResolver(IntegrationProvider.TOP_POSTERS, "credentialhash", "top-key"),
            posterTransport = transport
        )
        val decision = templateDecision(
            provider = IntegrationProvider.TOP_POSTERS,
            idType = "tmdb",
            mediaId = "movie-550",
            credentialHash = "credentialhash",
            imageType = ArtworkType.POSTER
        )

        val result = loader.load(decision.selectedCandidate.providerTemplate!!.toSource(), decision)

        assertTrue(result is IntegrationLoadResult.Success)
        assertArrayEquals("top".toByteArray(), (result as IntegrationLoadResult.Success).value)
        assertEquals("https://api.top-posters.com/top-key/tmdb/poster/movie-550.jpg", transport.lastUrl)
    }

    @Test
    fun `provider template encodes id type and media id as path segments`() = runTest {
        val transport = RecordingArtworkPosterTransport(
            PosterTransportResult(statusCode = 200, isSuccessful = true, body = "encoded".toByteArray())
        )
        val loader = DefaultArtworkByteLoader(
            credentialResolver = StaticCredentialResolver(IntegrationProvider.TOP_POSTERS, "credentialhash", "top-key"),
            posterTransport = transport
        )
        val decision = templateDecision(
            provider = IntegrationProvider.TOP_POSTERS,
            idType = "tmdb id",
            mediaId = "movie/550",
            credentialHash = "credentialhash",
            imageType = ArtworkType.POSTER
        )

        val result = loader.load(decision.selectedCandidate.providerTemplate!!.toSource(), decision)

        assertTrue(result is IntegrationLoadResult.Success)
        assertEquals("https://api.top-posters.com/top-key/tmdb%20id/poster/movie%2F550.jpg", transport.lastUrl)
    }

    @Test
    fun `top posters thumbnail provider template builds thumbnail request`() = runTest {
        val transport = RecordingArtworkPosterTransport(
            PosterTransportResult(statusCode = 200, isSuccessful = true, body = "thumb".toByteArray())
        )
        val loader = DefaultArtworkByteLoader(
            credentialResolver = StaticCredentialResolver(IntegrationProvider.TOP_POSTERS, "credentialhash", "top-key"),
            posterTransport = transport
        )
        val decision = templateDecision(
            provider = IntegrationProvider.TOP_POSTERS,
            idType = "tvdb",
            mediaId = "1399",
            credentialHash = "credentialhash",
            imageType = ArtworkType.THUMBNAIL,
            pathParams = mapOf(
                "season" to "1",
                "episode" to "2",
                "badgeSize" to "small",
                "badgePosition" to "top-right",
                "blur" to "false"
            )
        )

        val result = loader.load(decision.selectedCandidate.providerTemplate!!.toSource(), decision)

        assertTrue(result is IntegrationLoadResult.Success)
        assertEquals(
            "https://api.top-posters.com/top-key/tvdb/thumbnail/1399/S1E2.jpg?badge_size=small&badge_position=top-right&blur=false",
            transport.lastUrl
        )
    }

    @Test
    fun `provider template returns missing credential error without network call`() = runTest {
        val transport = RecordingArtworkPosterTransport(
            PosterTransportResult(statusCode = 200, isSuccessful = true, body = "unused".toByteArray())
        )
        val loader = DefaultArtworkByteLoader(
            credentialResolver = StaticCredentialResolver(IntegrationProvider.RPDB, "otherhash", "rpdb-key"),
            posterTransport = transport
        )
        val decision = templateDecision(
            provider = IntegrationProvider.RPDB,
            idType = "imdb",
            mediaId = "tt0137523",
            credentialHash = "credentialhash",
            imageType = ArtworkType.POSTER
        )

        val result = loader.load(decision.selectedCandidate.providerTemplate!!.toSource(), decision)

        assertTrue(result is IntegrationLoadResult.NetworkError)
        assertEquals(null, transport.lastUrl)
    }

    @Test
    fun `premium key change old decision does not materialize`() = runTest {
        val transport = RecordingArtworkPosterTransport(
            PosterTransportResult(statusCode = 200, isSuccessful = true, body = "unused".toByteArray())
        )
        val loader = DefaultArtworkByteLoader(
            credentialResolver = StaticCredentialResolver(IntegrationProvider.RPDB, "newhash", "new-rpdb-key"),
            posterTransport = transport
        )
        val oldDecision = templateDecision(
            provider = IntegrationProvider.RPDB,
            idType = "imdb",
            mediaId = "tt0137523",
            credentialHash = "oldhash",
            imageType = ArtworkType.POSTER
        )

        val result = loader.load(oldDecision.selectedCandidate.providerTemplate!!.toSource(), oldDecision)

        assertTrue(result is IntegrationLoadResult.NetworkError)
        assertEquals(null, transport.lastUrl)
    }

    @Test
    fun `premium key change new decision materializes`() = runTest {
        val transport = RecordingArtworkPosterTransport(
            PosterTransportResult(statusCode = 200, isSuccessful = true, body = "new".toByteArray())
        )
        val loader = DefaultArtworkByteLoader(
            credentialResolver = StaticCredentialResolver(IntegrationProvider.RPDB, "newhash", "new-rpdb-key"),
            posterTransport = transport
        )
        val newDecision = templateDecision(
            provider = IntegrationProvider.RPDB,
            idType = "imdb",
            mediaId = "tt0137523",
            credentialHash = "newhash",
            imageType = ArtworkType.POSTER
        )

        val result = loader.load(newDecision.selectedCandidate.providerTemplate!!.toSource(), newDecision)

        assertTrue(result is IntegrationLoadResult.Success)
        assertArrayEquals("new".toByteArray(), (result as IntegrationLoadResult.Success).value)
        assertEquals("https://api.ratingposterdb.com/new-rpdb-key/imdb/poster-default/tt0137523.jpg", transport.lastUrl)
    }

    @Test
    fun `top posters unsupported image type returns error without network call`() = runTest {
        val transport = RecordingArtworkPosterTransport(
            PosterTransportResult(statusCode = 200, isSuccessful = true, body = "unused".toByteArray())
        )
        val loader = DefaultArtworkByteLoader(
            credentialResolver = StaticCredentialResolver(IntegrationProvider.TOP_POSTERS, "credentialhash", "top-key"),
            posterTransport = transport
        )
        val decision = templateDecision(
            provider = IntegrationProvider.TOP_POSTERS,
            idType = "tmdb",
            mediaId = "movie-550",
            credentialHash = "credentialhash",
            imageType = ArtworkType.LOGO
        )

        val result = loader.load(decision.selectedCandidate.providerTemplate!!.toSource(), decision)

        assertTrue(result is IntegrationLoadResult.NetworkError)
        assertEquals(null, transport.lastUrl)
    }

    private class RecordingArtworkPosterTransport(
        private val result: PosterTransportResult
    ) : ArtworkPosterTransport {
        var lastUrl: String? = null

        override fun execute(url: String): PosterTransportResult {
            lastUrl = url
            return result
        }
    }

    private class StaticCredentialResolver(
        private val provider: IntegrationProvider,
        private val credentialHash: String,
        private val apiKey: String
    ) : ArtworkCredentialResolver {
        override suspend fun apiKeyFor(provider: IntegrationProvider, credentialHash: String?): String? =
            apiKey.takeIf {
                this.provider == provider && this.credentialHash == credentialHash
            }
    }

    private fun PersistedProviderTemplate.toSource(): ArtworkSource.ProviderTemplate =
        ArtworkSource.ProviderTemplate(
            provider = provider,
            idType = idType,
            mediaId = mediaId,
            providerPathHash = providerPathHash,
            settingsHash = settingsHash,
            credentialHash = credentialHash,
            pathParams = pathParams
        )

    private fun templateDecision(
        provider: IntegrationProvider,
        idType: String,
        mediaId: String,
        credentialHash: String,
        imageType: ArtworkType,
        pathParams: Map<String, String> = emptyMap()
    ): ArtworkDecision {
        val providerId = ArtworkProviderId.RuntimeProvider(provider)
        return ArtworkDecision(
            decisionKey = ArtworkDecisionKey("decision-$provider-$imageType"),
            ownerKey = ArtworkOwnerKey.CanonicalContent("$idType:$mediaId"),
            canonicalContentId = "$idType:$mediaId",
            imageType = imageType,
            selectedCandidate = PersistedArtworkCandidate(
                provider = providerId,
                sourceRole = ArtworkSourceRole.PREMIUM,
                sourceHash = "source-hash",
                redactedSourceForTrace = null,
                providerTemplate = PersistedProviderTemplate(
                    provider = providerId,
                    imageType = imageType,
                    idType = idType,
                    mediaId = mediaId,
                    providerPathHash = "pathhash",
                    settingsHash = "settingshash",
                    credentialHash = credentialHash,
                    policyVersion = 1,
                    pathParams = pathParams
                ),
                priority = 1
            ),
            rejectedCandidates = emptyList(),
            policyVersion = 1,
            settingsHash = "settingshash",
            credentialHash = credentialHash,
            createdAtMs = 1L,
            expiresAtMs = 2L,
            staleUntilMs = 3L
        )
    }
}
