package com.nexio.tv.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.nexio.tv.core.artwork.ArtworkDecisionCache
import com.nexio.tv.core.artwork.ArtworkDecisionKey
import com.nexio.tv.core.artwork.InMemoryArtworkDecisionCache
import com.nexio.tv.core.integration.RailKeyFactory
import com.nexio.tv.core.integration.RailMediaIdentityResolver
import com.nexio.tv.core.integration.RailMembership
import com.nexio.tv.core.locale.AppLocaleResolver
import com.nexio.tv.core.poster.PosterRatingsUrlResolver
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.data.local.integration.RailCacheEntity
import com.nexio.tv.data.local.integration.RailItemEntity
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.MetaPreview
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HomeCatalogSnapshotStore private constructor(
    @ApplicationContext private val context: Context,
    private val metadataDiskCacheStore: MetadataDiskCacheStore,
    private val posterRatingsUrlResolver: PosterRatingsUrlResolver,
    private val artworkDecisionCache: ArtworkDecisionCache,
    private val activeProfileId: () -> Int,
    private val identityResolver: RailMediaIdentityResolver
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        metadataDiskCacheStore: MetadataDiskCacheStore,
        posterRatingsUrlResolver: PosterRatingsUrlResolver,
        artworkDecisionCache: ArtworkDecisionCache,
        profileManager: ProfileManager,
        identityResolver: RailMediaIdentityResolver
    ) : this(
        context = context,
        metadataDiskCacheStore = metadataDiskCacheStore,
        posterRatingsUrlResolver = posterRatingsUrlResolver,
        artworkDecisionCache = artworkDecisionCache,
        activeProfileId = { profileManager.activeProfileId.value },
        identityResolver = identityResolver
    )

    constructor(
        context: Context,
        metadataDiskCacheStore: MetadataDiskCacheStore,
        posterRatingsUrlResolver: PosterRatingsUrlResolver,
        artworkDecisionCache: ArtworkDecisionCache = InMemoryArtworkDecisionCache()
    ) : this(
        context = context,
        metadataDiskCacheStore = metadataDiskCacheStore,
        posterRatingsUrlResolver = posterRatingsUrlResolver,
        artworkDecisionCache = artworkDecisionCache,
        activeProfileId = { 1 },
        identityResolver = RailMediaIdentityResolver()
    )

    companion object {
        private const val TAG = "HomeCatalogSnapshot"
        private const val PREFS_NAME = "home_catalog_snapshot"
        private const val SNAPSHOT_KEY = "snapshot"
        private const val SCHEMA_VERSION = 4
        private const val ARTWORK_DECISION_PREFIX = "nexio-artwork://decision/"
        private const val LEGACY_INTEGRATION_POSTER_PREFIX = "integration-poster://"
        private val PREMIUM_PROVIDER_URL_PREFIXES = listOf(
            "https://api.ratingposterdb.com/",
            "https://api.top-posters.com/"
        )
    }

    private val gson = Gson()

    suspend fun currentPosterProviderToken(): String {
        val provider = posterRatingsUrlResolver.getActiveProvider() ?: return "native"
        return "${provider.provider.name}:${provider.apiKey.hashCode()}"
    }

    data class Snapshot(
        val catalogRows: List<CatalogRow>,
        val fullCatalogRows: List<CatalogRow>,
        val heroItems: List<MetaPreview>,
        val orderedGroupKeys: List<String> = emptyList()
    )

    fun read(
        posterProviderToken: String,
        profileId: Int = activeProfileId()
    ): Snapshot? {
        return runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(snapshotKey(profileId), null)?.takeIf { it.isNotBlank() } ?: return null
            val requiredPosterProviderTag = requiredPosterProviderTag(posterProviderToken)
            decodeSnapshot(raw, posterProviderToken)
                ?.sanitize()
                ?.takeIf { it.hasValidPosterProviderTags(requiredPosterProviderTag) }
        }.onFailure { error ->
            Log.w(TAG, "Failed to restore home snapshot", error)
            clear(profileId)
        }.getOrNull()
    }

    fun readActiveProfile(posterProviderToken: String): Snapshot? {
        return read(posterProviderToken, activeProfileId())
    }

    fun write(
        snapshot: Snapshot,
        posterProviderToken: String,
        profileId: Int = activeProfileId()
    ) {
        runCatching {
            val sanitizedSnapshot = snapshot.sanitize()
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val payload = JsonObject().apply {
                addProperty("schemaVersion", SCHEMA_VERSION)
                addProperty("languageEpoch", metadataDiskCacheStore.currentLanguageEpoch())
                addProperty("languageTag", currentLanguageTag())
                addProperty("posterProviderToken", posterProviderToken)
                add("catalogRows", gson.toJsonTree(sanitizedSnapshot.catalogRows))
                add("fullCatalogRows", gson.toJsonTree(sanitizedSnapshot.fullCatalogRows))
                add("heroItems", gson.toJsonTree(sanitizedSnapshot.heroItems))
                add("orderedGroupKeys", gson.toJsonTree(sanitizedSnapshot.orderedGroupKeys))
            }
            prefs.edit().putString(snapshotKey(profileId), gson.toJson(payload)).commit()
        }.onFailure { error ->
            Log.w(TAG, "Failed to persist home snapshot", error)
        }
    }

    fun clear(profileId: Int = activeProfileId()) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(snapshotKey(profileId)).commit()
        }.onFailure { error ->
            Log.w(TAG, "Failed to clear home snapshot", error)
        }
    }

    internal fun buildRailMemberships(
        snapshot: Snapshot,
        posterProviderToken: String,
        profileId: Int
    ): List<RailMembership> {
        val now = System.currentTimeMillis()
        val rows = linkedMapOf<String, CatalogRow>().apply {
            snapshot.fullCatalogRows.forEach { put(it.catalogId, it) }
            snapshot.catalogRows.forEach { putIfAbsent(it.catalogId, it) }
        }.values.toList()

        return rows.map { row ->
            val railKey = RailKeyFactory.homeCatalog(profileId, row.catalogId)
            val resolvedItems = row.items.map { item ->
                identityResolver.fromPreview(item, updatedAtEpochMs = now)
            }
            RailMembership(
                rail = RailCacheEntity(
                    railKey = railKey,
                    provider = row.catalogId.substringBefore(':').uppercase(),
                    kind = row.type.name,
                    paramsHash = "$posterProviderToken:${currentLanguageTag()}",
                    fetchedAtEpochMs = now,
                    expiresAtEpochMs = now + 30_000L,
                    staleUntilEpochMs = now + 3_600_000L
                ),
                items = resolvedItems.mapIndexed { index, resolved ->
                    RailItemEntity(
                        key = "$railKey#${resolved.mediaIdentity.mediaKey}",
                        railKey = railKey,
                        mediaKey = resolved.mediaIdentity.mediaKey,
                        position = index,
                        updatedAtEpochMs = now
                    )
                },
                mediaIdentities = resolvedItems.map { it.mediaIdentity },
                externalIds = resolvedItems.flatMap { it.externalIds }
            )
        }
    }

    private fun decodeSnapshot(raw: String, posterProviderToken: String): Snapshot? {
        val root = gson.fromJson(raw, JsonObject::class.java) ?: return null
        val schemaVersion = root.get("schemaVersion")?.asInt ?: 0
        if (schemaVersion != SCHEMA_VERSION) {
            return null
        }
        val languageTag = root.get("languageTag")?.asString?.trim().orEmpty()
        if (languageTag.isBlank() || languageTag != currentLanguageTag()) {
            return null
        }
        val cachedPosterToken = root.get("posterProviderToken")?.asString?.trim().orEmpty()
        if (cachedPosterToken != posterProviderToken) {
            Log.d(TAG, "Poster provider changed ($cachedPosterToken -> $posterProviderToken), invalidating snapshot")
            return null
        }
        val canonical = Snapshot(
            catalogRows = decodeArray<CatalogRow>(root, "catalogRows"),
            fullCatalogRows = decodeArray<CatalogRow>(root, "fullCatalogRows"),
            heroItems = decodeArray<MetaPreview>(root, "heroItems"),
            orderedGroupKeys = decodeArray<String>(root, "orderedGroupKeys")
        )
        if (canonical.catalogRows.isNotEmpty() || canonical.fullCatalogRows.isNotEmpty() || canonical.heroItems.isNotEmpty()) {
            return canonical
        }

        // Legacy payloads were stored via direct Gson reflection and may use obfuscated field names.
        return runCatching {
            gson.fromJson(raw, Snapshot::class.java)
        }.getOrNull()
    }

    private inline fun <reified T> decodeArray(root: JsonObject, key: String): List<T> {
        val array = root.getAsJsonArray(key) ?: return emptyList()
        val type = object : TypeToken<List<T>>() {}.type
        return gson.fromJson<List<T>>(array, type) ?: emptyList()
    }

    private fun currentLanguageTag(): String {
        return AppLocaleResolver.resolveEffectiveAppLanguageTag(context)
    }

    private fun snapshotKey(profileId: Int = activeProfileId()): String {
        return "$SNAPSHOT_KEY:p$profileId:${currentLanguageTag()}"
    }

    private fun requiredPosterProviderTag(posterProviderToken: String): String? {
        val provider = posterProviderToken.substringBefore(':').trim()
        return provider
            .takeIf { it.isNotBlank() && !it.equals("native", ignoreCase = true) }
            ?.lowercase()
    }

    private fun Snapshot.hasValidPosterProviderTags(requiredTag: String?): Boolean {
        if (requiredTag == null) return true
        return sequence {
            catalogRows.forEach { row -> yieldAll(row.items) }
            fullCatalogRows.forEach { row -> yieldAll(row.items) }
            yieldAll(heroItems)
        }.all { item ->
            item.posterProviderTag == null || item.posterProviderTag == requiredTag
        }
    }

    private fun Snapshot.sanitize(): Snapshot {
        val sanitizedCatalogRows = sanitizeCatalogRows(catalogRows as List<*>, "catalogRows")
        val sanitizedFullCatalogRows = sanitizeCatalogRows(fullCatalogRows as List<*>, "fullCatalogRows")
        val sanitizedHeroItems = sanitizeMetaPreviews(heroItems as List<*>, "heroItems")

        val droppedCatalogRows = (catalogRows as List<*>).size - sanitizedCatalogRows.size
        val droppedFullCatalogRows = (fullCatalogRows as List<*>).size - sanitizedFullCatalogRows.size
        val droppedHeroItems = (heroItems as List<*>).size - sanitizedHeroItems.size

        if (droppedCatalogRows > 0 || droppedFullCatalogRows > 0 || droppedHeroItems > 0) {
            Log.w(
                TAG,
                "Discarded malformed cached home snapshot entries: " +
                    "catalogRows=$droppedCatalogRows fullCatalogRows=$droppedFullCatalogRows heroItems=$droppedHeroItems"
            )
        }

        return Snapshot(
            catalogRows = sanitizedCatalogRows,
            fullCatalogRows = sanitizedFullCatalogRows,
            heroItems = sanitizedHeroItems,
            orderedGroupKeys = orderedGroupKeys.distinct()
        )
    }

    private fun sanitizeCatalogRows(values: List<*>, label: String): List<CatalogRow> {
        return values.mapIndexedNotNull { index, value ->
            val row = value as? CatalogRow
            if (row == null) {
                Log.w(TAG, "Dropping malformed cached $label[$index]: ${value?.javaClass?.name}")
                return@mapIndexedNotNull null
            }

            val sanitizedItems = sanitizeMetaPreviews(row.items as List<*>, "$label[$index].items")
            if (sanitizedItems.size != (row.items as List<*>).size) {
                Log.w(
                    TAG,
                    "Dropping malformed cached items from $label[$index] for catalogId=${row.catalogId}"
                )
            }
            row.copy(items = sanitizedItems).sanitizedForCache()
        }
    }

    private fun sanitizeMetaPreviews(values: List<*>, label: String): List<MetaPreview> {
        return values.mapIndexedNotNull { index, value ->
            val item = value as? MetaPreview
            if (item == null) {
                Log.w(TAG, "Dropping malformed cached $label[$index]: ${value?.javaClass?.name}")
            }
            item?.sanitizedForCache()?.sanitizePremiumArtworkForSnapshot()
        }
    }

    private fun MetaPreview.sanitizePremiumArtworkForSnapshot(): MetaPreview {
        val posterRef = poster?.trim().orEmpty()
        if (posterRef.isBlank() || !shouldClearPosterRef(posterRef)) {
            return this
        }
        return copy(poster = null, posterProviderTag = null)
    }

    private fun shouldClearPosterRef(ref: String): Boolean {
        return isRawPremiumProviderUrl(ref) || isLegacyIntegrationPosterRef(ref) || isMissingDecisionRef(ref)
    }

    private fun isRawPremiumProviderUrl(ref: String): Boolean {
        return PREMIUM_PROVIDER_URL_PREFIXES.any { prefix ->
            ref.startsWith(prefix, ignoreCase = true)
        }
    }

    private fun isLegacyIntegrationPosterRef(ref: String): Boolean {
        return ref.startsWith(LEGACY_INTEGRATION_POSTER_PREFIX, ignoreCase = true)
    }

    private fun isMissingDecisionRef(ref: String): Boolean {
        return ref.startsWith(ARTWORK_DECISION_PREFIX) && !hasDurableDecision(ref)
    }

    private fun hasDurableDecision(ref: String): Boolean {
        val keyValue = ref.removePrefix(ARTWORK_DECISION_PREFIX)
            .takeIf { it.isNotBlank() }
            ?: return false

        return runCatching {
            artworkDecisionCache.get(ArtworkDecisionKey(keyValue)) != null
        }.getOrDefault(false)
    }
}
