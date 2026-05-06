package com.nexio.tv.core.artwork

import com.nexio.tv.core.integration.IntegrationLoadResult
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.data.integration.posters.transport.PosterTransport
import com.nexio.tv.data.integration.posters.transport.PosterTransportResult
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

interface ArtworkPosterTransport {
    fun execute(url: String): PosterTransportResult
}

class DefaultArtworkPosterTransport @Inject constructor(
    private val posterTransport: PosterTransport
) : ArtworkPosterTransport {
    override fun execute(url: String): PosterTransportResult =
        posterTransport.execute(url)
}

@Singleton
class DefaultArtworkByteLoader @Inject constructor(
    private val credentialResolver: ArtworkCredentialResolver,
    private val posterTransport: ArtworkPosterTransport
) : ArtworkByteLoader {
    override suspend fun load(
        source: ArtworkSource,
        decision: ArtworkDecision
    ): IntegrationLoadResult<ByteArray> =
        when (source) {
            is ArtworkSource.ProviderTemplate -> loadProviderTemplate(source, decision)
            is ArtworkSource.RemoteUrl -> loadRemoteUrl(source)
            is UnavailableRemoteArtworkSource -> IntegrationLoadResult.NetworkError(
                IllegalStateException("Remote artwork source is unavailable for ${source.normalizedUrlHash}")
            )
            is ArtworkSource.LocalAsset -> IntegrationLoadResult.NetworkError(
                IllegalStateException("Local artwork asset should be loaded from disk")
            )
            is ArtworkSource.Placeholder -> IntegrationLoadResult.NetworkError(
                IllegalStateException("Placeholder artwork has no remote bytes")
            )
        }

    private suspend fun loadProviderTemplate(
        source: ArtworkSource.ProviderTemplate,
        decision: ArtworkDecision
    ): IntegrationLoadResult<ByteArray> {
        val runtimeProvider = (source.provider as? ArtworkProviderId.RuntimeProvider)?.providerId
            ?: return IntegrationLoadResult.NetworkError(
                IllegalStateException("Unsupported artwork provider ${source.provider.key}")
            )
        val credentialHash = source.credentialHash ?: decision.credentialHash
        val apiKey = credentialResolver.apiKeyFor(runtimeProvider, credentialHash)
            ?: return IntegrationLoadResult.NetworkError(
                IllegalStateException("Missing artwork credential for ${runtimeProvider.name}")
            )
        val url = try {
            when (runtimeProvider) {
                IntegrationProvider.RPDB -> rpdbPosterUrl(apiKey, source)
                IntegrationProvider.TOP_POSTERS -> topPostersUrl(apiKey, source, decision)
                    ?: return IntegrationLoadResult.NetworkError(
                        IllegalStateException("Top Posters does not support ${decision.imageType} provider-template byte loading")
                    )
                else -> return IntegrationLoadResult.NetworkError(
                    IllegalStateException("Unsupported artwork provider ${runtimeProvider.name}")
                )
            }
        } catch (error: Throwable) {
            return IntegrationLoadResult.NetworkError(error)
        }
        return execute(url)
    }

    private fun loadRemoteUrl(source: ArtworkSource.RemoteUrl): IntegrationLoadResult<ByteArray> =
        execute(source.rawUrl.value)

    private fun execute(url: String): IntegrationLoadResult<ByteArray> =
        runCatching { posterTransport.execute(url) }
            .fold(
                onSuccess = { result ->
                    when {
                        result.body == null ->
                            IntegrationLoadResult.HttpError(result.statusCode, reason = "artwork_missing_body")
                        !result.isSuccessful ->
                            IntegrationLoadResult.HttpError(result.statusCode, reason = "artwork_fetch_failed")
                        else ->
                            IntegrationLoadResult.Success(result.body)
                    }
                },
                onFailure = { IntegrationLoadResult.NetworkError(it) }
            )

    private fun rpdbPosterUrl(
        apiKey: String,
        source: ArtworkSource.ProviderTemplate
    ): String =
        "https://api.ratingposterdb.com/${apiKey.encodePathSegment()}/${source.idType.encodePathSegment()}/poster-default/${source.mediaId.encodePathSegment()}.jpg"

    private fun topPostersUrl(
        apiKey: String,
        source: ArtworkSource.ProviderTemplate,
        decision: ArtworkDecision
    ): String? =
        when (decision.imageType) {
            ArtworkType.POSTER ->
                "https://api.top-posters.com/${apiKey.encodePathSegment()}/${source.idType.encodePathSegment()}/poster/${source.mediaId.encodePathSegment()}.jpg"
            ArtworkType.THUMBNAIL ->
                topPostersThumbnailUrl(apiKey, source)
            else ->
                null
        }

    private fun topPostersThumbnailUrl(
        apiKey: String,
        source: ArtworkSource.ProviderTemplate
    ): String {
        val season = requireNotNull(source.pathParams["season"]) { "Top Posters thumbnail season is required" }
        val episode = requireNotNull(source.pathParams["episode"]) { "Top Posters thumbnail episode is required" }
        val badgeSize = source.pathParams["badgeSize"] ?: "small"
        val badgePosition = source.pathParams["badgePosition"] ?: "top-right"
        val blur = source.pathParams["blur"] ?: "false"
        return "https://api.top-posters.com/${apiKey.encodePathSegment()}/${source.idType.encodePathSegment()}/thumbnail/${source.mediaId.encodePathSegment()}/S${season.encodePathSegment()}E${episode.encodePathSegment()}.jpg" +
            "?badge_size=${badgeSize.encodeQuery()}" +
            "&badge_position=${badgePosition.encodeQuery()}" +
            "&blur=${blur.encodeQuery()}"
    }

    private fun String.encodePathSegment(): String =
        URLEncoder.encode(this, StandardCharsets.UTF_8.name())
            .replace("+", "%20")

    private fun String.encodeQuery(): String =
        URLEncoder.encode(this, StandardCharsets.UTF_8.name())
}
