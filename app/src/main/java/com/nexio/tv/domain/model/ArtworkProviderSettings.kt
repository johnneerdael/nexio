package com.nexio.tv.domain.model

import com.nexio.tv.core.artwork.ArtworkProviderId
import com.nexio.tv.core.artwork.DEFAULTS_TABLE_VERSION
import com.nexio.tv.core.integration.IntegrationProvider

enum class ArtworkTypeKey {
    POSTER,
    LOGO,
    BACKDROP,
    THUMBNAIL
}

@JvmInline
value class ArtworkProviderChoiceKey(val value: String) {
    init {
        require(value.isNotBlank()) { "Artwork provider choice key must not be blank" }
    }

    override fun toString(): String = value

    companion object {
        val DEFAULT = ArtworkProviderChoiceKey("default")
        val RPDB = ArtworkProviderChoiceKey("rpdb")
        val TOP_POSTERS = ArtworkProviderChoiceKey("top_posters")
        val FANART_TV = ArtworkProviderChoiceKey("fanart_tv")

        fun fromStored(value: String?): ArtworkProviderChoiceKey = when (value) {
            RPDB.value -> RPDB
            TOP_POSTERS.value -> TOP_POSTERS
            FANART_TV.value -> FANART_TV
            DEFAULT.value -> DEFAULT
            else -> DEFAULT
        }
    }
}

data class ArtworkProviderSelectionSettings(
    val posterProvider: ArtworkProviderChoiceKey = ArtworkProviderChoiceKey.DEFAULT,
    val logoProvider: ArtworkProviderChoiceKey = ArtworkProviderChoiceKey.DEFAULT,
    val backdropProvider: ArtworkProviderChoiceKey = ArtworkProviderChoiceKey.DEFAULT,
    val thumbnailProvider: ArtworkProviderChoiceKey = ArtworkProviderChoiceKey.DEFAULT
) {
    fun providerFor(type: ArtworkTypeKey): ArtworkProviderChoiceKey = when (type) {
        ArtworkTypeKey.POSTER -> posterProvider
        ArtworkTypeKey.LOGO -> logoProvider
        ArtworkTypeKey.BACKDROP -> backdropProvider
        ArtworkTypeKey.THUMBNAIL -> thumbnailProvider
    }

    fun withProvider(
        type: ArtworkTypeKey,
        provider: ArtworkProviderChoiceKey
    ): ArtworkProviderSelectionSettings = when (type) {
        ArtworkTypeKey.POSTER -> copy(posterProvider = provider)
        ArtworkTypeKey.LOGO -> copy(logoProvider = provider)
        ArtworkTypeKey.BACKDROP -> copy(backdropProvider = provider)
        ArtworkTypeKey.THUMBNAIL -> copy(thumbnailProvider = provider)
    }
}

data class TopPostersEntitlementSnapshot(
    val valid: Boolean,
    val isActive: Boolean,
    val tier: Int,
    val tierName: String,
    val episodeThumbnails: Boolean,
    val verifiedAtMs: Long,
    val expiresAtMs: Long
) {
    val isFreshAtNow: Boolean
        get() = System.currentTimeMillis() < expiresAtMs

    val allowsEpisodeThumbnails: Boolean
        get() = valid && isActive && tier == 1 && episodeThumbnails && isFreshAtNow
}

data class ArtworkProviderSettings(
    val rpdbApiKey: String = "",
    val topPostersApiKey: String = "",
    val selection: ArtworkProviderSelectionSettings = ArtworkProviderSelectionSettings(),
    val topPostersEntitlement: TopPostersEntitlementSnapshot? = null
) {
    val hasRpdbKey: Boolean
        get() = rpdbApiKey.isNotBlank()

    val hasTopPostersKey: Boolean
        get() = topPostersApiKey.isNotBlank()

    val topPostersCanProvideThumbnails: Boolean
        get() = hasTopPostersKey && topPostersEntitlement?.allowsEpisodeThumbnails == true
}

fun ArtworkProviderSettings.toSettingsSignature(): String =
    "p=${selection.posterProvider.value};" +
    "l=${selection.logoProvider.value};" +
    "b=${selection.backdropProvider.value};" +
    "t=${selection.thumbnailProvider.value};" +
    "v=$DEFAULTS_TABLE_VERSION"

fun ArtworkProviderChoiceKey.toRuntimeProviderId(): ArtworkProviderId =
    when (this) {
        ArtworkProviderChoiceKey.RPDB ->
            ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB)
        ArtworkProviderChoiceKey.TOP_POSTERS ->
            ArtworkProviderId.RuntimeProvider(IntegrationProvider.TOP_POSTERS)
        ArtworkProviderChoiceKey.FANART_TV ->
            ArtworkProviderId.RuntimeProvider(IntegrationProvider.FANART_TV)
        ArtworkProviderChoiceKey.DEFAULT ->
            throw IllegalArgumentException(
                "DEFAULT must be coerced upstream by ArtworkProviderResolver — never passed to toRuntimeProviderId"
            )
        else ->
            throw IllegalArgumentException(
                "Unknown ArtworkProviderChoiceKey: $value"
            )
    }
