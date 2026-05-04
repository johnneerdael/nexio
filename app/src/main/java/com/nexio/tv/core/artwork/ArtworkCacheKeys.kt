package com.nexio.tv.core.artwork

import java.net.URI
import java.security.MessageDigest
import java.util.Locale

object ArtworkCacheKeys {
    private const val IMAGE_LANGUAGE = "en"
    private val trackingQueryKeys = setOf(
        "utm_source",
        "utm_medium",
        "utm_campaign",
        "utm_term",
        "utm_content",
        "utm_name",
        "utm_cid",
        "utm_reader",
        "utm_viz_id",
        "utm_pubreferrer",
        "utm_swu",
        "fbclid",
        "gclid",
        "gbraid",
        "wbraid",
        "mc_cid",
        "mc_eid"
    )

    fun decisionKey(
        ownerKey: ArtworkOwnerKey,
        imageType: ArtworkType,
        provider: ArtworkProviderId?,
        premiumEnabled: Boolean,
        settingsHash: String?,
        credentialHash: String?,
        policyVersion: Int
    ): ArtworkDecisionKey = ArtworkDecisionKey(
        listOf(
            "artwork-decision",
            imageType.keyPart(),
            ownerKey.keyPart(),
            "provider",
            provider?.keyPart() ?: "none",
            "premium",
            premiumEnabled.toString(),
            "settings",
            settingsHash.safeOptionalPart(),
            "credential",
            credentialHash.safeOptionalPart(),
            "imageLang",
            IMAGE_LANGUAGE,
            "policy",
            policyVersion.toString()
        ).flattenParts()
    )

    fun assetKeyForRemoteUrl(
        provider: ArtworkProviderId,
        imageType: ArtworkType,
        normalizedUrlHash: String,
        variant: String?,
        policyVersion: Int
    ): ArtworkAssetKey = ArtworkAssetKey(
        buildList {
            add("artwork-asset")
            add(provider.keyPart())
            add(imageType.keyPart())
            add("urlHash")
            add(normalizedUrlHash.safeRequiredPart("normalizedUrlHash"))
            add("variant")
            add(variant.safeOptionalPart())
            add("imageLang")
            add(IMAGE_LANGUAGE)
            add("policy")
            add(policyVersion.toString())
        }.flattenParts()
    )

    fun assetKeyForProviderTemplate(template: PersistedProviderTemplate): ArtworkAssetKey =
        ArtworkAssetKey(
            listOf(
                "artwork-asset",
                template.provider.keyPart(),
                template.imageType.keyPart(),
                template.idType.safeRequiredPart("idType"),
                template.mediaId.safeRequiredPart("mediaId"),
                "settings",
                template.settingsHash.safeOptionalPart(),
                "credential",
                template.credentialHash.safeOptionalPart(),
                "imageLang",
                IMAGE_LANGUAGE,
                "policy",
                template.policyVersion.toString()
            ).flattenParts()
        )

    fun normalizedUrlHash(rawUrl: String): String = sha256(normalizeUrl(rawUrl))

    fun normalizeUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        require(trimmed.isNotBlank()) { "rawUrl must not be blank" }

        val uri = URI(trimmed)
        val scheme = uri.scheme?.lowercase(Locale.US)
        val host = uri.host?.lowercase(Locale.US)
        val authority = host?.let { normalizedAuthority(uri, it) } ?: uri.rawAuthority
        val query = normalizedQuery(uri.rawQuery)

        return buildString {
            scheme?.let {
                append(it)
                append(":")
            }
            authority?.let {
                append("//")
                append(it)
            }
            append(uri.rawPath.orEmpty())
            query?.let {
                append("?")
                append(it)
            }
        }
    }

    private fun normalizedAuthority(uri: URI, host: String): String {
        val userInfo = uri.rawUserInfo?.let { "$it@" } ?: ""
        val port = uri.port.takeIf { it != -1 }?.let { ":$it" } ?: ""
        return "$userInfo$host$port"
    }

    private fun normalizedQuery(rawQuery: String?): String? {
        val query = rawQuery ?: return null
        return query
            .split("&")
            .filter { it.isNotBlank() }
            .filterNot { part ->
                val key = part.substringBefore("=").lowercase(Locale.US)
                key in trackingQueryKeys
            }
            .joinToString("&")
            .takeIf { it.isNotBlank() }
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun ArtworkOwnerKey.keyPart(): String =
        when (this) {
            is ArtworkOwnerKey.CanonicalContent ->
                listOf("canonical", contentId.safeRequiredPart("contentId")).flattenParts()
            is ArtworkOwnerKey.PreviewItem ->
                listOf(
                    "preview",
                    itemKey.safeRequiredPart("itemKey"),
                    "payload",
                    sourcePayloadHash.safeRequiredPart("sourcePayloadHash")
                ).flattenParts()
        }

    private fun ArtworkProviderId.keyPart(): String = key.safeRequiredPart("provider")

    private fun ArtworkType.keyPart(): String = name.lowercase(Locale.US)

    private fun String?.safeOptionalPart(): String =
        this?.takeIf { it.isNotBlank() }?.safeRequiredPart("keyPart") ?: "none"

    private fun String.safeRequiredPart(name: String): String {
        val trimmed = trim()
        require(trimmed.isNotBlank()) { "$name must not be blank" }
        return if (trimmed.containsAny('/', '?', '#')) sha256(trimmed) else trimmed
    }

    private fun Iterable<String>.flattenParts(): String = joinToString(":")

    private fun String.containsAny(vararg chars: Char): Boolean =
        chars.any { contains(it) }
}
