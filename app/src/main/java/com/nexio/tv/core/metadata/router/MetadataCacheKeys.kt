package com.nexio.tv.core.metadata.router

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

    fun routerDecisionKey(
        parentId: String,
        sourceContext: MetadataSourceContext,
        routingPolicyVersion: String
    ): String =
        "router:v$routingPolicyVersion:parent=${parentId.trim()}" +
            ":addon=${sourceContext.addonId.orEmpty()}:catalog=${sourceContext.catalogId.orEmpty()}"

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
            ":season=${route.seasonNumber?.toString().keyPart()}"

    private fun String?.keyPart(): String = this ?: "none"
}
