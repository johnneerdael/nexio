package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.integration.IntegrationKeyFactory
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MetadataCacheKeys @Inject constructor() {
    fun providerMetadataKey(
        provider: MetadataPrimaryProvider,
        apiShapeId: String,
        operationKey: String
    ): String =
        "metadata:provider:${provider.name.lowercase()}:shape=$apiShapeId:operation=$operationKey"

    fun localized(
        provider: String,
        apiShapeId: String,
        contentId: String,
        language: String,
        policyVersion: Int,
        qualifiers: List<String> = emptyList()
    ): String {
        require(provider.isNotBlank()) { "provider must not be blank" }
        require(apiShapeId.isNotBlank()) { "apiShapeId must not be blank" }
        require(contentId.isNotBlank()) { "contentId must not be blank" }
        require(language.isNotBlank()) { "language must not be blank" }
        require(policyVersion > 0) { "policyVersion must be positive" }

        val normalizedLanguage = language.trim().lowercase(Locale.ROOT)
        val deterministicQualifiers = qualifiers
            .map(String::trim)
            .filter(String::isNotEmpty)
            .sorted()

        return IntegrationKeyFactory.build(
            "metadata",
            provider,
            apiShapeId,
            contentId,
            *deterministicQualifiers.toTypedArray(),
            "lang",
            normalizedLanguage,
            "policy",
            policyVersion.toString()
        )
    }

    fun routerDecisionKey(
        parentId: String,
        sourceContext: MetadataSourceContext,
        routingPolicyVersion: String,
        mediaKind: MetadataMediaKind? = null
    ): String =
        "router:v$routingPolicyVersion:parent=${parentId.trim()}" +
            ":addon=${sourceContext.addonId.orEmpty()}:catalog=${sourceContext.catalogId.orEmpty()}" +
            mediaKind?.let { ":mediaKind=${it.name.lowercase()}" }.orEmpty()

    fun resolvedDocumentKey(
        route: MetadataRoute,
        depth: MetadataDepth,
        fieldPolicyVersion: String,
        artworkPolicyVersion: String
    ): String =
        "metadata:resolved-document:${routeKey(route)}:depth=${depth.name.lowercase()}" +
            ":fieldPolicy=$fieldPolicyVersion:artworkPolicy=$artworkPolicyVersion"

    fun artworkDecisionKey(
        route: MetadataRoute,
        artworkPolicyVersion: String
    ): String =
        "metadata:artwork-decision:${routeKey(route)}:artworkPolicy=$artworkPolicyVersion"

    fun imageBlobKey(urlHash: String): String =
        "metadata:image-blob:sha256:$urlHash"

    private fun routeKey(route: MetadataRoute): String =
        "provider=${route.provider.name.lowercase()}:parent=${route.parentId}" +
            ":mediaKind=${route.mediaKind.name.lowercase()}" +
            ":addon=${route.sourceContext.addonId.keyPart()}" +
            ":catalog=${route.sourceContext.catalogId.keyPart()}" +
            ":language=${route.language.keyPart()}" +
            ":season=${route.seasonNumber?.toString().keyPart()}" +
            ":targetIds=${route.targetIds.targetIdsKeyPart()}"

    private fun String?.keyPart(): String = this ?: "none"

    private fun Map<MetadataPrimaryProvider, String>.targetIdsKeyPart(): String =
        entries
            .sortedBy { it.key.name }
            .joinToString(",") { "${it.key.name.lowercase()}=${it.value}" }
            .ifEmpty { "none" }
}
