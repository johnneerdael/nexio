package com.nexio.tv.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import com.nexio.tv.core.artwork.ArtworkAssetKey
import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.artwork.ArtworkDecisionKey
import com.nexio.tv.core.artwork.ArtworkDisplayHints
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkProviderId
import com.nexio.tv.core.artwork.ArtworkSourceRole
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.artwork.PersistedProviderTemplate
import com.nexio.tv.core.artwork.PlaceholderType
import com.nexio.tv.core.artwork.RejectedArtworkCandidate
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.locale.AppLocaleResolver
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.resolver.TrailerPlaybackRef
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.DisplaySourceRank
import com.nexio.tv.domain.model.HydrationState
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.ResolvedDisplayFields
import com.nexio.tv.domain.model.ResolvedDisplayFieldSlots
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.domain.model.ResolvedSlot
import com.nexio.tv.domain.model.TitleRating
import com.nexio.tv.domain.model.TrailerDisplayState
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 3.7 — file-backed streaming JSON persistence for the typed
 * authority's per-item state. Companion to [HomeCatalogSnapshotStore]:
 * the snapshot stores rail structure (legacy CatalogRow shape for now);
 * this store holds the typed [ResolvedDisplayItem] content. Both are
 * flushed in the same write-coordination call and read together at
 * cold-start; the repository's in-memory state is warmed from this file
 * before render so rails can be hydrated with typed content the moment
 * they paint.
 *
 * Per-profile + per-language file path under
 * `filesDir/resolved-display-v1/p<profileId>_<lang>.json`. Mirrors the
 * HomeCatalogSnapshotStore recipe: streaming JsonReader for reads,
 * streaming JsonWriter + atomic Files.move rename for writes.
 */
@Singleton
class ResolvedDisplaySnapshotStore private constructor(
    private val rootDir: () -> File,
    private val activeProfileId: () -> Int,
    private val currentLanguageTag: () -> String,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        profileManager: ProfileManager,
    ) : this(
        rootDir = { File(context.filesDir, SNAPSHOT_DIR) },
        activeProfileId = { profileManager.activeProfileId.value },
        currentLanguageTag = { AppLocaleResolver.resolveEffectiveAppLanguageTag(context) },
    )

    companion object {
        private const val TAG = "ResolvedDisplayStore"
        private const val SNAPSHOT_DIR = "resolved-display-v1"
        private const val SCHEMA_VERSION = 2
        private val gson = Gson()
        private val persistedMapType = object : TypeToken<Map<String, PersistedResolvedDisplayItem>>() {}.type
        private val legacyMapType = object : TypeToken<Map<String, ResolvedDisplayItem>>() {}.type

        @JvmStatic
        fun forTesting(
            rootDir: File,
            activeProfileId: () -> Int,
            currentLanguageTag: () -> String = { "en" },
        ): ResolvedDisplaySnapshotStore = ResolvedDisplaySnapshotStore(
            rootDir = { rootDir },
            activeProfileId = activeProfileId,
            currentLanguageTag = currentLanguageTag,
        )
    }

    fun write(items: Map<String, ResolvedDisplayItem>, profileId: Int = activeProfileId()) {
        runCatching {
            val persistedItems = items.mapValues { (_, item) -> item.toPersisted() }
            val target = snapshotFileFor(profileId)
            target.parentFile?.mkdirs()
            val tempFile = File(target.parentFile, "${target.name}.tmp")
            FileOutputStream(tempFile).use { fos ->
                BufferedWriter(OutputStreamWriter(fos, Charsets.UTF_8)).use { bw ->
                    JsonWriter(bw).use { writer ->
                        writer.beginObject()
                        writer.name("schemaVersion").value(SCHEMA_VERSION)
                        writer.name("items")
                        gson.toJson(persistedItems, persistedMapType, writer)
                        writer.endObject()
                    }
                }
            }
            Files.move(
                tempFile.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.onFailure { error ->
            Log.w(TAG, "Failed to write resolved display snapshot", error)
        }
    }

    fun read(profileId: Int = activeProfileId()): Map<String, ResolvedDisplayItem> {
        val file = snapshotFileFor(profileId)
        if (!file.exists()) return emptyMap()
        return runCatching {
            FileInputStream(file).use { fis ->
                BufferedReader(InputStreamReader(fis, Charsets.UTF_8)).use { br ->
                    JsonReader(br).use { reader ->
                        if (reader.peek() == JsonToken.NULL) {
                            reader.nextNull()
                            return@runCatching emptyMap<String, ResolvedDisplayItem>()
                        }
                        var schemaVersion = -1
                        var items: Map<String, ResolvedDisplayItem> = emptyMap()
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "schemaVersion" -> {
                                    schemaVersion = reader.nextInt()
                                    if (schemaVersion > SCHEMA_VERSION) {
                                        return@runCatching emptyMap<String, ResolvedDisplayItem>()
                                    }
                                }
                                "items" -> {
                                    items = if (schemaVersion <= 1) {
                                        gson.fromJson(reader, legacyMapType) ?: emptyMap()
                                    } else {
                                        val persisted: Map<String, PersistedResolvedDisplayItem> =
                                            gson.fromJson(reader, persistedMapType) ?: emptyMap()
                                        persisted.mapNotNull { (key, value) ->
                                            value.toDomain()?.let { item -> key to item }
                                        }.toMap()
                                    }
                                }
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                        items
                    }
                }
            }
        }.onFailure { error ->
            Log.w(TAG, "Failed to read resolved display snapshot", error)
        }.getOrDefault(emptyMap())
    }

    fun clear(profileId: Int = activeProfileId()) {
        runCatching {
            snapshotFileFor(profileId).takeIf { it.exists() }?.delete()
        }
    }

    private fun snapshotFileFor(profileId: Int): File {
        val parent = rootDir()
        if (!parent.exists()) parent.mkdirs()
        val sanitizedTag = currentLanguageTag()
            .lowercase()
            .replace(Regex("[^a-z0-9_-]"), "_")
            .ifBlank { "unknown" }
        return File(parent, "p${profileId.coerceAtLeast(1)}_${sanitizedTag}.json")
    }
}

private data class PersistedResolvedDisplayItem(
    val itemKey: String,
    val contentId: String,
    val parentId: String,
    val itemType: ContentType,
    val mediaKind: MetadataMediaKind,
    val canonicalProvider: String?,
    val canonicalId: String?,
    val imdbId: String?,
    val stableIds: ProviderIds,
    val display: ResolvedDisplayFields,
    val artwork: PersistedArtworkBundle,
    val rating: TitleRating?,
    val trailer: PersistedTrailerDisplayState,
    val hydrationState: HydrationState,
    val sourceTrace: List<com.nexio.tv.domain.model.HydratedHomeFieldTrace>,
    val updatedAtMs: Long,
    val slots: PersistedResolvedDisplayFieldSlots?,
    val preferredArtworkProviders: Map<String, PersistedArtworkProviderId>,
    val displayLanguageTag: String?
) {
    fun toDomain(): ResolvedDisplayItem? = runCatching {
        ResolvedDisplayItem(
            itemKey = itemKey,
            contentId = contentId,
            parentId = parentId,
            itemType = itemType,
            mediaKind = mediaKind,
            canonicalProvider = canonicalProvider,
            canonicalId = canonicalId,
            imdbId = imdbId,
            stableIds = stableIds,
            display = display,
            artwork = artwork.toDomain(),
            rating = rating,
            trailer = trailer.toDomain(),
            hydrationState = hydrationState,
            sourceTrace = sourceTrace,
            updatedAtMs = updatedAtMs,
            slots = slots?.toDomain(),
            preferredArtworkProviders = preferredArtworkProviders.mapNotNull { (key, value) ->
                val type = runCatching { ArtworkType.valueOf(key) }.getOrNull()
                val provider = value.toDomain()
                if (type != null && provider != null) type to provider else null
            }.toMap(),
            displayLanguageTag = displayLanguageTag
        )
    }.getOrNull()
}

private fun ResolvedDisplayItem.toPersisted(): PersistedResolvedDisplayItem =
    PersistedResolvedDisplayItem(
        itemKey = itemKey,
        contentId = contentId,
        parentId = parentId,
        itemType = itemType,
        mediaKind = mediaKind,
        canonicalProvider = canonicalProvider,
        canonicalId = canonicalId,
        imdbId = imdbId,
        stableIds = stableIds,
        display = display,
        artwork = artwork.toPersisted(),
        rating = rating,
        trailer = trailer.toPersisted(),
        hydrationState = hydrationState,
        sourceTrace = sourceTrace,
        updatedAtMs = updatedAtMs,
        slots = slots?.toPersisted(),
        preferredArtworkProviders = preferredArtworkProviders.mapKeys { (type, _) -> type.name }
            .mapValues { (_, provider) -> provider.toPersisted() },
        displayLanguageTag = displayLanguageTag
    )

private data class PersistedResolvedDisplayFieldSlots(
    val title: PersistedSlot<String>,
    val originalTitle: PersistedSlot<String>,
    val overview: PersistedSlot<String>,
    val genres: PersistedSlot<List<String>>,
    val releaseInfo: PersistedSlot<String>,
    val runtime: PersistedSlot<String>,
    val rating: PersistedSlot<TitleRating>,
    val poster: PersistedSlot<PersistedArtworkDisplayRef>,
    val backdrop: PersistedSlot<PersistedArtworkDisplayRef>,
    val logo: PersistedSlot<PersistedArtworkDisplayRef>,
    val thumbnail: PersistedSlot<PersistedArtworkDisplayRef>,
    val posterProviderTag: PersistedSlot<String>
) {
    fun toDomain(): ResolvedDisplayFieldSlots = ResolvedDisplayFieldSlots(
        title = title.toDomain(),
        originalTitle = originalTitle.toDomain(),
        overview = overview.toDomain(),
        genres = genres.toDomain(),
        releaseInfo = releaseInfo.toDomain(),
        runtime = runtime.toDomain(),
        rating = rating.toDomain(),
        poster = poster.toArtworkDomain(),
        backdrop = backdrop.toArtworkDomain(),
        logo = logo.toArtworkDomain(),
        thumbnail = thumbnail.toArtworkDomain(),
        posterProviderTag = posterProviderTag.toDomain()
    )
}

private fun ResolvedDisplayFieldSlots.toPersisted(): PersistedResolvedDisplayFieldSlots =
    PersistedResolvedDisplayFieldSlots(
        title = title.toPersisted(),
        originalTitle = originalTitle.toPersisted(),
        overview = overview.toPersisted(),
        genres = genres.toPersisted(),
        releaseInfo = releaseInfo.toPersisted(),
        runtime = runtime.toPersisted(),
        rating = rating.toPersisted(),
        poster = poster.toArtworkPersisted(),
        backdrop = backdrop.toArtworkPersisted(),
        logo = logo.toArtworkPersisted(),
        thumbnail = thumbnail.toArtworkPersisted(),
        posterProviderTag = posterProviderTag.toPersisted()
    )

private data class PersistedSlot<T>(
    val value: T?,
    val rank: DisplaySourceRank,
    val provider: String?,
    val role: String?,
    val updatedAtMs: Long,
    val expiresAtMs: Long?,
    val trace: List<String>
) {
    fun toDomain(): ResolvedSlot<T> = ResolvedSlot(
        value = value,
        rank = rank,
        provider = provider,
        role = role,
        updatedAtMs = updatedAtMs,
        expiresAtMs = expiresAtMs,
        trace = trace
    )
}

private fun <T> ResolvedSlot<T>.toPersisted(): PersistedSlot<T> = PersistedSlot(
    value = value,
    rank = rank,
    provider = provider,
    role = role,
    updatedAtMs = updatedAtMs,
    expiresAtMs = expiresAtMs,
    trace = trace
)

private fun ResolvedSlot<ArtworkDisplayRef>.toArtworkPersisted(): PersistedSlot<PersistedArtworkDisplayRef> =
    PersistedSlot(
        value = value?.toPersisted(),
        rank = rank,
        provider = provider,
        role = role,
        updatedAtMs = updatedAtMs,
        expiresAtMs = expiresAtMs,
        trace = trace
    )

private fun PersistedSlot<PersistedArtworkDisplayRef>.toArtworkDomain(): ResolvedSlot<ArtworkDisplayRef> =
    ResolvedSlot(
        value = value?.toDomain(),
        rank = rank,
        provider = provider,
        role = role,
        updatedAtMs = updatedAtMs,
        expiresAtMs = expiresAtMs,
        trace = trace
    )

private data class PersistedArtworkBundle(
    val poster: PersistedArtworkDisplayRef?,
    val backdrop: PersistedArtworkDisplayRef?,
    val logo: PersistedArtworkDisplayRef?,
    val thumbnail: PersistedArtworkDisplayRef?
) {
    fun toDomain(): ArtworkBundle = ArtworkBundle(
        poster = poster?.toDomain(),
        backdrop = backdrop?.toDomain(),
        logo = logo?.toDomain(),
        thumbnail = thumbnail?.toDomain()
    )
}

private fun ArtworkBundle.toPersisted(): PersistedArtworkBundle = PersistedArtworkBundle(
    poster = poster?.toPersisted(),
    backdrop = backdrop?.toPersisted(),
    logo = logo?.toPersisted(),
    thumbnail = thumbnail?.toPersisted()
)

private data class PersistedArtworkDisplayRef(
    val kind: String,
    val decisionKey: String?,
    val assetKey: String?,
    val imageType: ArtworkType,
    val selectedProvider: PersistedArtworkProviderId?,
    val sourceRole: ArtworkSourceRole?,
    val placeholderType: PlaceholderType?,
    val legacyValue: String?,
    val trace: PersistedArtworkTrace,
    val displayHints: ArtworkDisplayHints
) {
    fun toDomain(): ArtworkDisplayRef? = runCatching {
        when (kind) {
            "runtime_asset" -> ArtworkDisplayRef.RuntimeAsset(
                decisionKey = ArtworkDecisionKey(decisionKey ?: return null),
                assetKey = assetKey?.let(::ArtworkAssetKey),
                imageType = imageType,
                selectedProvider = selectedProvider?.toDomain(),
                sourceRole = sourceRole ?: return null,
                trace = trace.toDomain(),
                displayHints = displayHints
            )
            "placeholder" -> ArtworkDisplayRef.Placeholder(
                placeholderType = placeholderType ?: return null,
                imageType = imageType,
                trace = trace.toDomain(),
                displayHints = displayHints
            )
            "legacy_string" -> ArtworkDisplayRef.LegacyString(
                value = legacyValue ?: return null,
                imageType = imageType,
                trace = trace.toDomain(),
                displayHints = displayHints
            )
            else -> null
        }
    }.getOrNull()
}

private fun ArtworkDisplayRef.toPersisted(): PersistedArtworkDisplayRef =
    when (this) {
        is ArtworkDisplayRef.RuntimeAsset -> PersistedArtworkDisplayRef(
            kind = "runtime_asset",
            decisionKey = decisionKey.value,
            assetKey = assetKey?.value,
            imageType = imageType,
            selectedProvider = selectedProvider?.toPersisted(),
            sourceRole = sourceRole,
            placeholderType = null,
            legacyValue = null,
            trace = trace.toPersisted(),
            displayHints = displayHints
        )
        is ArtworkDisplayRef.Placeholder -> PersistedArtworkDisplayRef(
            kind = "placeholder",
            decisionKey = null,
            assetKey = null,
            imageType = imageType,
            selectedProvider = null,
            sourceRole = null,
            placeholderType = placeholderType,
            legacyValue = null,
            trace = trace.toPersisted(),
            displayHints = displayHints
        )
        is ArtworkDisplayRef.LegacyString -> PersistedArtworkDisplayRef(
            kind = "legacy_string",
            decisionKey = null,
            assetKey = null,
            imageType = imageType,
            selectedProvider = null,
            sourceRole = null,
            placeholderType = null,
            legacyValue = value,
            trace = trace.toPersisted(),
            displayHints = displayHints
        )
    }

private data class PersistedArtworkProviderId(
    val kind: String,
    val providerId: String?
) {
    fun toDomain(): ArtworkProviderId? =
        when (kind) {
            "runtime" -> providerId
                ?.let { runCatching { IntegrationProvider.valueOf(it) }.getOrNull() }
                ?.let(ArtworkProviderId::RuntimeProvider)
            "rail_preview" -> ArtworkProviderId.RailPreview
            "addon_preview" -> ArtworkProviderId.AddonPreview
            "placeholder" -> ArtworkProviderId.Placeholder
            else -> null
        }
}

private fun ArtworkProviderId.toPersisted(): PersistedArtworkProviderId =
    when (this) {
        is ArtworkProviderId.RuntimeProvider -> PersistedArtworkProviderId(
            kind = "runtime",
            providerId = providerId.name
        )
        ArtworkProviderId.RailPreview -> PersistedArtworkProviderId(kind = "rail_preview", providerId = null)
        ArtworkProviderId.AddonPreview -> PersistedArtworkProviderId(kind = "addon_preview", providerId = null)
        ArtworkProviderId.Placeholder -> PersistedArtworkProviderId(kind = "placeholder", providerId = null)
    }

private data class PersistedArtworkTrace(
    val selectedProvider: String?,
    val sourceRole: String?,
    val reason: String?,
    val rejectedCandidates: List<PersistedRejectedArtworkCandidate>? = null
) {
    fun toDomain(): ArtworkTrace = ArtworkTrace(
        selectedProvider = selectedProvider,
        sourceRole = sourceRole,
        reason = reason,
        rejectedCandidates = rejectedCandidates.orEmpty().mapNotNull { it.toDomain() }
    )
}

private fun ArtworkTrace.toPersisted(): PersistedArtworkTrace = PersistedArtworkTrace(
    selectedProvider = selectedProvider,
    sourceRole = sourceRole,
    reason = reason,
    rejectedCandidates = rejectedCandidates.map { it.toPersisted() }
)

private data class PersistedRejectedArtworkCandidate(
    val provider: PersistedArtworkProviderId?,
    val sourceRole: String?,
    val reason: String?,
    val sourceHash: String?,
    val redactedSourceForTrace: String?,
    val providerTemplate: PersistedArtworkProviderTemplate?,
    val priority: Int
) {
    fun toDomain(): RejectedArtworkCandidate? =
        runCatching {
            RejectedArtworkCandidate(
                provider = provider?.toDomain(),
                sourceRole = ArtworkSourceRole.valueOf(requireNotNull(sourceRole)),
                reason = requireNotNull(reason),
                sourceHash = sourceHash,
                redactedSourceForTrace = redactedSourceForTrace,
                providerTemplate = providerTemplate?.toDomain(),
                priority = priority
            )
        }.getOrNull()
}

private fun RejectedArtworkCandidate.toPersisted(): PersistedRejectedArtworkCandidate =
    PersistedRejectedArtworkCandidate(
        provider = provider?.toPersisted(),
        sourceRole = sourceRole.name,
        reason = reason,
        sourceHash = sourceHash,
        redactedSourceForTrace = redactedSourceForTrace,
        providerTemplate = providerTemplate?.toPersisted(),
        priority = priority
    )

private data class PersistedArtworkProviderTemplate(
    val provider: PersistedArtworkProviderId,
    val imageType: String,
    val idType: String,
    val mediaId: String,
    val providerPathHash: String?,
    val settingsHash: String?,
    val credentialHash: String?,
    val imageLanguage: String,
    val policyVersion: Int,
    val pathParams: Map<String, String> = emptyMap()
) {
    fun toDomain(): PersistedProviderTemplate? =
        runCatching {
            PersistedProviderTemplate(
                provider = requireNotNull(provider.toDomain()),
                imageType = ArtworkType.valueOf(imageType),
                idType = idType,
                mediaId = mediaId,
                providerPathHash = providerPathHash,
                settingsHash = settingsHash,
                credentialHash = credentialHash,
                imageLanguage = imageLanguage,
                policyVersion = policyVersion,
                pathParams = pathParams
            )
        }.getOrNull()
}

private fun PersistedProviderTemplate.toPersisted(): PersistedArtworkProviderTemplate =
    PersistedArtworkProviderTemplate(
        provider = provider.toPersisted(),
        imageType = imageType.name,
        idType = idType,
        mediaId = mediaId,
        providerPathHash = providerPathHash,
        settingsHash = settingsHash,
        credentialHash = credentialHash,
        imageLanguage = imageLanguage,
        policyVersion = policyVersion,
        pathParams = pathParams
    )

private data class PersistedTrailerDisplayState(
    val fallbackTrailerYtIds: List<String>,
    val selectedPlaybackRef: PersistedTrailerPlaybackRef?,
    val availabilityReason: String?,
    val surface: String?,
    val resolverSource: String?,
    val lastResolvedAtMs: Long?
) {
    fun toDomain(): TrailerDisplayState = TrailerDisplayState(
        fallbackTrailerYtIds = fallbackTrailerYtIds,
        selectedPlaybackRef = selectedPlaybackRef?.toDomain(),
        availabilityReason = availabilityReason,
        surface = surface,
        resolverSource = resolverSource,
        lastResolvedAtMs = lastResolvedAtMs
    )
}

private fun TrailerDisplayState.toPersisted(): PersistedTrailerDisplayState =
    PersistedTrailerDisplayState(
        fallbackTrailerYtIds = fallbackTrailerYtIds,
        selectedPlaybackRef = selectedPlaybackRef?.toPersisted(),
        availabilityReason = availabilityReason,
        surface = surface,
        resolverSource = resolverSource,
        lastResolvedAtMs = lastResolvedAtMs
    )

private data class PersistedTrailerPlaybackRef(
    val kind: String,
    val videoId: String?,
    val url: String?,
    val videoUrl: String?,
    val audioUrl: String?,
    val userAgent: String?,
    val title: String?,
    val year: String?,
    val stableIds: ProviderIds?,
    val type: String?,
    val seasonNumber: Int?,
    val contentId: String?,
    val fallbackYtIds: List<String>
) {
    fun toDomain(): TrailerPlaybackRef? =
        when (kind) {
            "youtube_id" -> videoId?.let(TrailerPlaybackRef::YouTubeId)
            "external_url" -> url?.let(TrailerPlaybackRef::ExternalUrl)
            "in_app_source" -> videoUrl?.let { value ->
                TrailerPlaybackRef.InAppSource(
                    videoUrl = value,
                    audioUrl = audioUrl,
                    userAgent = userAgent
                )
            }
            "item_lookup" -> title?.let { value ->
                TrailerPlaybackRef.ItemLookup(
                    title = value,
                    year = year,
                    stableIds = stableIds ?: ProviderIds(),
                    type = type,
                    seasonNumber = seasonNumber,
                    contentId = contentId,
                    fallbackYtIds = fallbackYtIds
                )
            }
            else -> null
        }
}

private fun TrailerPlaybackRef.toPersisted(): PersistedTrailerPlaybackRef =
    when (this) {
        is TrailerPlaybackRef.YouTubeId -> PersistedTrailerPlaybackRef(
            kind = "youtube_id",
            videoId = videoId,
            url = null,
            videoUrl = null,
            audioUrl = null,
            userAgent = null,
            title = null,
            year = null,
            stableIds = null,
            type = null,
            seasonNumber = null,
            contentId = null,
            fallbackYtIds = emptyList()
        )
        is TrailerPlaybackRef.ExternalUrl -> PersistedTrailerPlaybackRef(
            kind = "external_url",
            videoId = null,
            url = url,
            videoUrl = null,
            audioUrl = null,
            userAgent = null,
            title = null,
            year = null,
            stableIds = null,
            type = null,
            seasonNumber = null,
            contentId = null,
            fallbackYtIds = emptyList()
        )
        is TrailerPlaybackRef.InAppSource -> PersistedTrailerPlaybackRef(
            kind = "in_app_source",
            videoId = null,
            url = null,
            videoUrl = videoUrl,
            audioUrl = audioUrl,
            userAgent = userAgent,
            title = null,
            year = null,
            stableIds = null,
            type = null,
            seasonNumber = null,
            contentId = null,
            fallbackYtIds = emptyList()
        )
        is TrailerPlaybackRef.ItemLookup -> PersistedTrailerPlaybackRef(
            kind = "item_lookup",
            videoId = null,
            url = null,
            videoUrl = null,
            audioUrl = null,
            userAgent = null,
            title = title,
            year = year,
            stableIds = stableIds,
            type = type,
            seasonNumber = seasonNumber,
            contentId = contentId,
            fallbackYtIds = fallbackYtIds
        )
    }
