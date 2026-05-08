package com.nexio.tv.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.core.sync.profilePrefsName
import com.nexio.tv.core.locale.AppLocaleResolver
import com.nexio.tv.core.tvdb.TvdbAirAvailabilityDiagnosticReason
import com.nexio.tv.core.tvdb.TvdbAirAvailabilityPrecision
import com.nexio.tv.data.repository.ContinueWatchingCanonicalKey
import com.nexio.tv.data.repository.ContinueWatchingRecord
import com.nexio.tv.data.repository.ContinueWatchingSnapshot
import com.nexio.tv.data.repository.ContinueWatchingMetadataSnapshot
import com.nexio.tv.data.repository.ContinueWatchingSource
import com.nexio.tv.data.repository.IdentityConfidence
import com.nexio.tv.data.repository.ResumeIdentity
import com.nexio.tv.data.repository.StreamFetchIdentity
import com.nexio.tv.data.repository.StreamIdScheme
import com.nexio.tv.data.repository.TrackingIdentity
import com.nexio.tv.data.repository.TrackingNextUpEntry
import com.nexio.tv.domain.model.ContentIdentity
import com.nexio.tv.domain.model.WatchProgress
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.TrackingProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContinueWatchingSnapshotStore private constructor(
    private val context: Context,
    private val metadataDiskCacheStore: MetadataDiskCacheStore,
    private val activeProfileId: () -> Int,
    private val injectedProfileManager: ProfileManager?
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        metadataDiskCacheStore: MetadataDiskCacheStore,
        profileManager: ProfileManager
    ) : this(
        context = context,
        metadataDiskCacheStore = metadataDiskCacheStore,
        activeProfileId = { profileManager.activeProfileId.value },
        injectedProfileManager = profileManager
    )

    constructor(
        context: Context,
        metadataDiskCacheStore: MetadataDiskCacheStore
    ) : this(
        context = context,
        metadataDiskCacheStore = metadataDiskCacheStore,
        activeProfileId = { 1 },
        injectedProfileManager = null
    )

    companion object {
        private const val TAG = "ContinueWatchingStore"
        internal const val BASE_PREFS_NAME = "continue_watching_snapshot"
        private const val SNAPSHOT_KEY = "snapshot"
        private const val SCHEMA_VERSION = 5
    }

    private val gson = Gson()

    private fun injectedPrefsName(profileId: Int): String =
        profilePrefsName(BASE_PREFS_NAME, profileId)

    private fun prefsName(profileId: Int = activeProfileId()): String =
        if (injectedProfileManager != null) {
            injectedPrefsName(profileId)
        } else {
            profilePrefsName(BASE_PREFS_NAME, profileId)
        }

    fun read(profileId: Int = activeProfileId()): ContinueWatchingSnapshot? {
        return runCatching {
            val prefs = context.getSharedPreferences(prefsName(profileId), Context.MODE_PRIVATE)
            val raw = prefs.getString(SNAPSHOT_KEY, null)?.takeIf { it.isNotBlank() } ?: return null
            decode(raw)
        }.onFailure { error ->
            Log.w(TAG, "Failed to restore continue watching snapshot", error)
            clear()
        }.getOrNull()
    }

    fun write(
        snapshot: ContinueWatchingSnapshot,
        profileId: Int = activeProfileId()
    ) {
        runCatching {
            val prefs = context.getSharedPreferences(prefsName(profileId), Context.MODE_PRIVATE)
            val payload = JsonObject().apply {
                addProperty("schemaVersion", SCHEMA_VERSION)
                addProperty("languageEpoch", metadataDiskCacheStore.currentLanguageEpoch())
                addProperty("languageTag", currentLanguageTag())
                add("resumeItems", gson.toJsonTree(snapshot.resumeItems))
                add("nextUpItems", encodeNextUpItems(snapshot.nextUpItems))
                add("traktUpNextItems", encodeNextUpItems(snapshot.traktUpNextItems))
                add("scheduledReemit", encodeNextUpItems(snapshot.scheduledReemit))
                add("records", encodeRecords(snapshot.records))
                add("displayMetadataByItemKey", gson.toJsonTree(snapshot.displayMetadataByItemKey))
                add("metadataSnapshotsByItemKey", gson.toJsonTree(snapshot.metadataSnapshotsByItemKey))
                addProperty("updatedAtMs", snapshot.updatedAtMs)
            }
            prefs.edit().putString(SNAPSHOT_KEY, gson.toJson(payload)).apply()
        }.onFailure { error ->
            Log.w(TAG, "Failed to persist continue watching snapshot", error)
        }
    }

    fun clear(profileId: Int = activeProfileId()) {
        runCatching {
            val prefs = context.getSharedPreferences(prefsName(profileId), Context.MODE_PRIVATE)
            prefs.edit().remove(SNAPSHOT_KEY).apply()
        }.onFailure { error ->
            Log.w(TAG, "Failed to clear continue watching snapshot", error)
        }
    }

    private fun decode(raw: String): ContinueWatchingSnapshot? {
        val root = gson.fromJson(raw, JsonObject::class.java) ?: return null
        val schemaVersion = root.get("schemaVersion")?.asInt ?: 0
        if (schemaVersion != SCHEMA_VERSION) {
            return null
        }
        val languageTag = root.get("languageTag")?.asString?.trim().orEmpty()
        if (languageTag.isBlank() || languageTag != currentLanguageTag()) {
            return null
        }
        val canonical = ContinueWatchingSnapshot(
            resumeItems = decodeArray<WatchProgress>(root, "resumeItems").ifEmpty {
                decodeArray(root, "movieProgressItems")
            },
            nextUpItems = decodeNextUpItems(root, "nextUpItems"),
            traktUpNextItems = decodeNextUpItems(root, "traktUpNextItems").ifEmpty {
                decodeNextUpItems(root, "nextUpItems")
            },
            scheduledReemit = decodeNextUpItems(root, "scheduledReemit"),
            records = decodeRecords(root, "records"),
            displayMetadataByItemKey = decodeDisplayMetadata(root, "displayMetadataByItemKey"),
            metadataSnapshotsByItemKey = decodeMetadataSnapshots(root, "metadataSnapshotsByItemKey"),
            updatedAtMs = root.get("updatedAtMs")?.asLong ?: 0L
        )
        if (
            canonical.updatedAtMs > 0L ||
            canonical.resumeItems.isNotEmpty() ||
            canonical.nextUpItems.isNotEmpty() ||
            canonical.traktUpNextItems.isNotEmpty() ||
            canonical.records.isNotEmpty() ||
            canonical.displayMetadataByItemKey.isNotEmpty() ||
            canonical.metadataSnapshotsByItemKey.isNotEmpty()
        ) {
            return canonical
        }

        return runCatching {
            gson.fromJson(raw, ContinueWatchingSnapshot::class.java)
        }.getOrNull()
    }

    private inline fun <reified T> decodeArray(root: JsonObject, key: String): List<T> {
        val array = root.getAsJsonArray(key) ?: return emptyList()
        val type = object : TypeToken<List<T>>() {}.type
        return gson.fromJson<List<T>>(array, type) ?: emptyList()
    }

    private fun decodeRecords(
        root: JsonObject,
        key: String
    ): List<ContinueWatchingRecord> {
        val array = root.getAsJsonArray(key) ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = runCatching { element.asJsonObject }.getOrNull() ?: return@mapNotNull null
            decodeRecordObject(obj)
        }
    }

    private fun decodeRecordObject(
        obj: JsonObject
    ): ContinueWatchingRecord? {
        val profileId = obj.intOrNull("profileId", "a")?.takeIf { it > 0 } ?: return null
        val parentId = obj.stringOrNull("parentId", "b")?.takeIf { it.isNotBlank() } ?: return null
        val contentId = obj.stringOrNull("contentId", "c")?.takeIf { it.isNotBlank() } ?: return null
        val routingVersion = obj.intOrNull("routingVersion", "e")?.takeIf { it > 0 } ?: return null
        val positionMs = obj.longOrNull("positionMs", "f")?.takeIf { it >= 0L } ?: return null
        val durationMs = obj.longOrNull("durationMs", "g")?.takeIf { it >= 0L } ?: return null
        val updatedAt = obj.longOrNull("updatedAt", "k")?.takeIf { it > 0L } ?: return null
        val episodeContext = decodeEpisodeContext(obj.objectOrNull("episodeContext", "h"))
        val decodedResumeIdentities = decodeResumeIdentities(obj.arrayOrNull("resumeIdentities", "p"))
        val trackingIdentity = decodeTrackingIdentity(obj.objectOrNull("trackingIdentity", "o"))

        val explicitProvider = obj.enumOrNull<TrackingProvider>("provider", "d")
        val explicitSource = obj.enumOrNull<ContinueWatchingRecord.Source>("source", "j")

        val source = inferRecordSource(
            explicit = explicitSource,
            resumeIdentities = decodedResumeIdentities,
            trackingIdentity = trackingIdentity
        ) ?: return null
        val provider = inferRecordProvider(
            explicit = explicitProvider,
            trackingIdentity = trackingIdentity
        ) ?: return null

        val resumeIdentities = decodedResumeIdentities.ifEmpty {
            synthesizeLegacyResumeIdentity(
                contentId = contentId,
                episodeContext = episodeContext,
                positionMs = positionMs,
                durationMs = durationMs,
                updatedAt = updatedAt,
                source = source
            )
        }
        if (resumeIdentities.isEmpty()) return null

        val resumeLookupKeys = resumeIdentities.map { it.lookupKey() }.toSet()
        val primaryResumeLookupKey = obj.stringOrNull("primaryResumeLookupKey", "q")
            ?.takeIf { it in resumeLookupKeys }
            ?: resumeIdentities.firstOrNull()?.lookupKey()

        val clickTimeDisplayMetadata = runCatching {
            obj.objectOrNull("clickTimeDisplayMetadata", "i")
                ?.let { gson.fromJson(it, ContinueWatchingMetadataSnapshot::class.java) }
        }.getOrNull()

        return runCatching {
            ContinueWatchingRecord(
                profileId = profileId,
                parentId = parentId,
                contentId = contentId,
                provider = provider,
                routingVersion = routingVersion,
                positionMs = positionMs,
                durationMs = durationMs,
                episodeContext = episodeContext,
                clickTimeDisplayMetadata = clickTimeDisplayMetadata,
                source = source,
                updatedAt = updatedAt,
                canonicalKey = decodeCanonicalKey(obj.objectOrNull("canonicalKey", "l")),
                displayIdentity = decodeContentIdentity(obj.objectOrNull("displayIdentity", "m")),
                streamFetchIdentity = decodeStreamFetchIdentity(obj.objectOrNull("streamFetchIdentity", "n")),
                trackingIdentity = trackingIdentity,
                resumeIdentities = resumeIdentities,
                primaryResumeLookupKey = primaryResumeLookupKey,
                identityConfidence = obj.enumOrNull<IdentityConfidence>("identityConfidence", "r")
                    ?: IdentityConfidence.LOW,
                identityWarnings = obj.stringList("identityWarnings", "s"),
                languageTag = obj.stringOrNull("languageTag", "t")?.takeIf { it.isNotBlank() }
            )
        }.getOrNull()
    }

    private fun inferRecordProvider(
        explicit: TrackingProvider?,
        trackingIdentity: TrackingIdentity?
    ): TrackingProvider? =
        explicit ?: trackingIdentity?.takeIf {
            it.traktShowId != null ||
                it.traktEpisodeId != null ||
                it.traktPlaybackId != null ||
                it.traktMovieId != null
        }?.let { TrackingProvider.TRAKT }

    private fun inferRecordSource(
        explicit: ContinueWatchingRecord.Source?,
        resumeIdentities: List<ResumeIdentity>,
        trackingIdentity: TrackingIdentity?
    ): ContinueWatchingRecord.Source? =
        explicit ?: when {
            resumeIdentities.any { it.source == ContinueWatchingSource.LOCAL } -> ContinueWatchingRecord.Source.LOCAL
            trackingIdentity != null -> ContinueWatchingRecord.Source.REMOTE
            resumeIdentities.any { it.source == ContinueWatchingSource.SYNTHETIC } -> ContinueWatchingRecord.Source.SYNTHETIC
            else -> null
        }

    private fun synthesizeLegacyResumeIdentity(
        contentId: String,
        episodeContext: ContinueWatchingRecord.EpisodeContext?,
        positionMs: Long,
        durationMs: Long,
        updatedAt: Long,
        source: ContinueWatchingRecord.Source
    ): List<ResumeIdentity> {
        val resumeSource = when (source) {
            ContinueWatchingRecord.Source.LOCAL -> ContinueWatchingSource.LOCAL
            ContinueWatchingRecord.Source.REMOTE -> ContinueWatchingSource.TRAKT_PLAYBACK
            ContinueWatchingRecord.Source.SYNTHETIC -> ContinueWatchingSource.SYNTHETIC
        }
        val normalizedSeason = episodeContext?.season?.takeIf { it > 0 }
        val normalizedEpisode = episodeContext?.number?.takeIf { it > 0 }
        val videoId = if (normalizedSeason != null && normalizedEpisode != null) {
            "$contentId:$normalizedSeason:$normalizedEpisode"
        } else {
            contentId
        }
        return listOf(
            ResumeIdentity(
                source = resumeSource,
                contentId = contentId,
                videoId = videoId,
                season = normalizedSeason,
                episode = normalizedEpisode,
                positionMs = positionMs,
                durationMs = durationMs,
                progressPercent = null,
                lastWatchedMs = updatedAt
            )
        )
    }

    private fun decodeEpisodeContext(
        obj: JsonObject?
    ): ContinueWatchingRecord.EpisodeContext? {
        obj ?: return null
        val season = obj.intOrNull("season", "a")?.takeIf { it >= 0 } ?: return null
        val number = obj.intOrNull("number", "b")?.takeIf { it >= 0 } ?: return null
        return ContinueWatchingRecord.EpisodeContext(season = season, number = number)
    }

    private fun decodeResumeIdentities(
        array: JsonArray?
    ): List<ResumeIdentity> {
        if (array == null || array.size() == 0) return emptyList()
        return array.mapNotNull { element ->
            val obj = runCatching { element.asJsonObject }.getOrNull() ?: return@mapNotNull null
            val contentId = obj.stringOrNull("contentId", "b")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val videoId = obj.stringOrNull("videoId", "c")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val positionMs = obj.longOrNull("positionMs", "f")?.takeIf { it >= 0L } ?: return@mapNotNull null
            val lastWatchedMs = obj.longOrNull("lastWatchedMs", "i")?.takeIf { it >= 0L } ?: return@mapNotNull null
            val season = obj.intOrNull("season", "d")
            val episode = obj.intOrNull("episode", "e")
            if ((season == null) != (episode == null)) return@mapNotNull null
            runCatching {
                ResumeIdentity(
                    source = obj.enumOrNull<ContinueWatchingSource>("source", "a")
                        ?: ContinueWatchingSource.LOCAL,
                    contentId = contentId,
                    videoId = videoId,
                    season = season,
                    episode = episode,
                    positionMs = positionMs,
                    durationMs = obj.longOrNull("durationMs", "g")?.takeIf { it >= 0L },
                    progressPercent = obj.floatOrNull("progressPercent", "h")?.takeIf { it in 0f..100f },
                    lastWatchedMs = lastWatchedMs
                )
            }.getOrNull()
        }
    }

    private fun decodeCanonicalKey(
        obj: JsonObject?
    ): ContinueWatchingCanonicalKey? {
        obj ?: return null
        val canonicalParent = decodeContentIdentity(obj.objectOrNull("canonicalParent", "b")) ?: return null
        return runCatching {
            ContinueWatchingCanonicalKey(
                mediaKind = obj.enumOrNull<MetadataMediaKind>("mediaKind", "a") ?: MetadataMediaKind.UNKNOWN,
                canonicalParent = canonicalParent,
                season = obj.intOrNull("season", "c"),
                episode = obj.intOrNull("episode", "d"),
                profileId = obj.intOrNull("profileId", "e") ?: return null
            )
        }.getOrNull()
    }

    private fun decodeContentIdentity(
        obj: JsonObject?
    ): ContentIdentity? {
        obj ?: return null
        return ContentIdentity(
            canonicalProvider = obj.enumOrNull<ProviderId>("canonicalProvider"),
            canonicalId = obj.stringOrNull("canonicalId"),
            providerIds = decodeProviderIds(obj.objectOrNull("providerIds"))
        )
    }

    private fun decodeProviderIds(
        obj: JsonObject?
    ): ProviderIds {
        obj ?: return ProviderIds()
        return ProviderIds(
            imdb = obj.stringOrNull("imdb"),
            tmdb = obj.stringOrNull("tmdb"),
            tvdb = obj.stringOrNull("tvdb"),
            trakt = obj.stringOrNull("trakt"),
            simkl = obj.stringOrNull("simkl"),
            kitsu = obj.stringOrNull("kitsu"),
            slug = obj.stringOrNull("slug"),
            mal = obj.stringOrNull("mal"),
            anilist = obj.stringOrNull("anilist"),
            anidb = obj.stringOrNull("anidb")
        )
    }

    private fun decodeStreamFetchIdentity(
        obj: JsonObject?
    ): StreamFetchIdentity? {
        obj ?: return null
        return runCatching {
            StreamFetchIdentity(
                contentId = obj.stringOrNull("contentId", "a")?.takeIf { it.isNotBlank() } ?: return null,
                videoId = obj.stringOrNull("videoId", "b")?.takeIf { it.isNotBlank() } ?: return null,
                idScheme = obj.enumOrNull<StreamIdScheme>("idScheme", "c") ?: StreamIdScheme.UNRESOLVED,
                confidence = obj.enumOrNull<IdentityConfidence>("confidence", "d") ?: IdentityConfidence.LOW,
                trace = obj.stringList("trace", "e")
            )
        }.getOrNull()
    }

    private fun decodeTrackingIdentity(
        obj: JsonObject?
    ): TrackingIdentity? {
        obj ?: return null
        return runCatching {
            TrackingIdentity(
                traktShowId = obj.intOrNull("traktShowId", "a"),
                traktEpisodeId = obj.intOrNull("traktEpisodeId", "b"),
                traktPlaybackId = obj.longOrNull("traktPlaybackId", "c"),
                traktMovieId = obj.intOrNull("traktMovieId", "d"),
                providerIds = decodeProviderIds(obj.objectOrNull("providerIds", "e"))
            )
        }.getOrNull()
    }

    private fun encodeRecords(records: List<ContinueWatchingRecord>): JsonArray {
        val array = JsonArray()
        records.forEach { record ->
            array.add(encodeRecord(record))
        }
        return array
    }

    private fun encodeRecord(record: ContinueWatchingRecord): JsonObject {
        return JsonObject().apply {
            addProperty("profileId", record.profileId)
            addProperty("parentId", record.parentId)
            addProperty("contentId", record.contentId)
            addProperty("provider", record.provider.name)
            addProperty("routingVersion", record.routingVersion)
            addProperty("positionMs", record.positionMs)
            addProperty("durationMs", record.durationMs)
            record.episodeContext?.let { add("episodeContext", encodeEpisodeContext(it)) }
            record.clickTimeDisplayMetadata?.let {
                add("clickTimeDisplayMetadata", gson.toJsonTree(it))
            }
            addProperty("source", record.source.name)
            addProperty("updatedAt", record.updatedAt)
            record.canonicalKey?.let { add("canonicalKey", encodeCanonicalKey(it)) }
            record.displayIdentity?.let { add("displayIdentity", encodeContentIdentity(it)) }
            record.streamFetchIdentity?.let { add("streamFetchIdentity", encodeStreamFetchIdentity(it)) }
            record.trackingIdentity?.let { add("trackingIdentity", encodeTrackingIdentity(it)) }
            add("resumeIdentities", encodeResumeIdentities(record.resumeIdentities))
            record.primaryResumeLookupKey?.let { addProperty("primaryResumeLookupKey", it) }
            addProperty("identityConfidence", record.identityConfidence.name)
            add("identityWarnings", JsonArray().apply {
                record.identityWarnings.forEach { add(it) }
            })
            record.languageTag?.let { addProperty("languageTag", it) }
        }
    }

    private fun encodeEpisodeContext(context: ContinueWatchingRecord.EpisodeContext): JsonObject {
        return JsonObject().apply {
            addProperty("season", context.season)
            addProperty("number", context.number)
        }
    }

    private fun encodeResumeIdentities(items: List<ResumeIdentity>): JsonArray {
        val array = JsonArray()
        items.forEach { identity ->
            array.add(
                JsonObject().apply {
                    addProperty("source", identity.source.name)
                    addProperty("contentId", identity.contentId)
                    addProperty("videoId", identity.videoId)
                    identity.season?.let { addProperty("season", it) }
                    identity.episode?.let { addProperty("episode", it) }
                    addProperty("positionMs", identity.positionMs)
                    identity.durationMs?.let { addProperty("durationMs", it) }
                    identity.progressPercent?.let { addProperty("progressPercent", it) }
                    addProperty("lastWatchedMs", identity.lastWatchedMs)
                }
            )
        }
        return array
    }

    private fun encodeCanonicalKey(key: ContinueWatchingCanonicalKey): JsonObject {
        return JsonObject().apply {
            addProperty("mediaKind", key.mediaKind.name)
            add("canonicalParent", encodeContentIdentity(key.canonicalParent))
            key.season?.let { addProperty("season", it) }
            key.episode?.let { addProperty("episode", it) }
            addProperty("profileId", key.profileId)
        }
    }

    private fun encodeContentIdentity(identity: ContentIdentity): JsonObject {
        return JsonObject().apply {
            identity.canonicalProvider?.let { addProperty("canonicalProvider", it.name) }
            identity.canonicalId?.let { addProperty("canonicalId", it) }
            add("providerIds", encodeProviderIds(identity.providerIds))
        }
    }

    private fun encodeProviderIds(ids: ProviderIds): JsonObject {
        return JsonObject().apply {
            ids.imdb?.let { addProperty("imdb", it) }
            ids.tmdb?.let { addProperty("tmdb", it) }
            ids.tvdb?.let { addProperty("tvdb", it) }
            ids.trakt?.let { addProperty("trakt", it) }
            ids.simkl?.let { addProperty("simkl", it) }
            ids.kitsu?.let { addProperty("kitsu", it) }
            ids.slug?.let { addProperty("slug", it) }
            ids.mal?.let { addProperty("mal", it) }
            ids.anilist?.let { addProperty("anilist", it) }
            ids.anidb?.let { addProperty("anidb", it) }
        }
    }

    private fun encodeStreamFetchIdentity(identity: StreamFetchIdentity): JsonObject {
        return JsonObject().apply {
            addProperty("contentId", identity.contentId)
            addProperty("videoId", identity.videoId)
            addProperty("idScheme", identity.idScheme.name)
            addProperty("confidence", identity.confidence.name)
            add("trace", JsonArray().apply {
                identity.trace.forEach { add(it) }
            })
        }
    }

    private fun encodeTrackingIdentity(identity: TrackingIdentity): JsonObject {
        return JsonObject().apply {
            identity.traktShowId?.let { addProperty("traktShowId", it) }
            identity.traktEpisodeId?.let { addProperty("traktEpisodeId", it) }
            identity.traktPlaybackId?.let { addProperty("traktPlaybackId", it) }
            identity.traktMovieId?.let { addProperty("traktMovieId", it) }
            add("providerIds", encodeProviderIds(identity.providerIds))
        }
    }

    private fun encodeNextUpItems(
        items: List<TrackingNextUpEntry>
    ): JsonArray {
        return JsonArray().apply {
            items.forEach { entry ->
                val contentId = entry.contentId.trim()
                if (contentId.isBlank()) return@forEach
                add(
                    JsonObject().apply {
                        addProperty("contentId", contentId)
                        addProperty(
                            "contentType",
                            entry.contentType.takeIf { it.isNotBlank() } ?: "series"
                        )
                        addProperty("name", entry.name)
                        addProperty("season", entry.season)
                        addProperty("episode", entry.episode)
                        entry.episodeTitle?.let { addProperty("episodeTitle", it) }
                        addProperty(
                            "videoId",
                            entry.videoId.takeIf { it.isNotBlank() }
                                ?: "$contentId:${entry.season}:${entry.episode}"
                        )
                        entry.firstAired?.let { addProperty("firstAired", it) }
                        addProperty("firstAiredMs", entry.firstAiredMs)
                        addProperty("activityAtMs", entry.activityAtMs)
                        entry.poster?.let { addProperty("poster", it) }
                        entry.backdrop?.let { addProperty("backdrop", it) }
                        entry.logo?.let { addProperty("logo", it) }
                        entry.traktShowId?.let { addProperty("traktShowId", it) }
                        entry.traktEpisodeId?.let { addProperty("traktEpisodeId", it) }
                        entry.tvdbAvailabilityInstantMs
                            ?.takeIf { it > 0L }
                            ?.let { addProperty("tvdbAvailabilityInstantMs", it) }
                        if (entry.tvdbAvailabilityPrecision != TvdbAirAvailabilityPrecision.UNKNOWN) {
                            addProperty("tvdbAvailabilityPrecision", entry.tvdbAvailabilityPrecision.name)
                        }
                        entry.tvdbAvailabilitySourceZoneId?.let { addProperty("tvdbAvailabilitySourceZoneId", it) }
                        entry.tvdbAvailabilitySourcePolicy?.let { addProperty("tvdbAvailabilitySourcePolicy", it) }
                        entry.tvdbAvailabilityDiagnosticReason?.let {
                            addProperty("tvdbAvailabilityDiagnosticReason", it.name)
                        }
                        entry.tvdbAvailabilityDeviceLocalDateTime?.let {
                            addProperty("tvdbAvailabilityDeviceLocalDateTime", it)
                        }
                    }
                )
            }
        }
    }

    private fun decodeNextUpItems(
        root: JsonObject,
        key: String
    ): List<TrackingNextUpEntry> {
        val array = root.getAsJsonArray(key) ?: return emptyList()
        if (array.size() == 0) return emptyList()

        val canonical = array.mapNotNull { element ->
            val obj = runCatching { element.asJsonObject }.getOrNull() ?: return@mapNotNull null
            decodeNextUpItemObject(obj)
        }
        if (canonical.isNotEmpty()) {
            return canonical
        }

        val legacyType = object : TypeToken<List<TrackingNextUpEntry>>() {}.type
        val legacy = runCatching {
            gson.fromJson<List<TrackingNextUpEntry>>(array, legacyType).orEmpty()
        }.getOrDefault(emptyList())
        return legacy.mapNotNull(::normalizeNextUpEntry)
    }

    private fun decodeDisplayMetadata(
        root: JsonObject,
        key: String
    ): Map<String, HomeDisplayMetadata> {
        val obj = root.getAsJsonObject(key) ?: return emptyMap()
        val type = object : TypeToken<Map<String, HomeDisplayMetadata>>() {}.type
        return gson.fromJson<Map<String, HomeDisplayMetadata>>(obj, type)
            ?.mapValues { (_, metadata) -> metadata.sanitizedForCache() }
            ?: emptyMap()
    }

    private fun decodeMetadataSnapshots(
        root: JsonObject,
        key: String
    ): Map<String, ContinueWatchingMetadataSnapshot> {
        val obj = root.getAsJsonObject(key) ?: return emptyMap()
        val type = object : TypeToken<Map<String, ContinueWatchingMetadataSnapshot>>() {}.type
        return gson.fromJson<Map<String, ContinueWatchingMetadataSnapshot>>(obj, type)
            ?.mapValues { (_, snapshot) ->
                snapshot.copy(
                    clickTimeDisplayMetadata = snapshot.clickTimeDisplayMetadata.sanitizedForCache()
                )
            }
            ?: emptyMap()
    }

    private fun currentLanguageTag(): String {
        return AppLocaleResolver.resolveEffectiveAppLanguageTag(context)
    }

    private fun decodeNextUpItemObject(
        obj: JsonObject
    ): TrackingNextUpEntry? {
        val contentId = obj.stringOrNull("contentId")?.trim().orEmpty()
        if (contentId.isBlank()) return null

        val season = obj.intOrNull("season")?.takeIf { it > 0 } ?: return null
        val episode = obj.intOrNull("episode")?.takeIf { it > 0 } ?: return null
        val contentType = obj.stringOrNull("contentType")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "series"
        val name = obj.stringOrNull("name")
            ?.takeIf { it.isNotBlank() }
            ?: contentId
        val videoId = obj.stringOrNull("videoId")
            ?.takeIf { it.isNotBlank() }
            ?: "$contentId:$season:$episode"

        return TrackingNextUpEntry(
            contentId = contentId,
            contentType = contentType,
            name = name,
            season = season,
            episode = episode,
            episodeTitle = obj.stringOrNull("episodeTitle"),
            videoId = videoId,
            firstAired = obj.stringOrNull("firstAired"),
            firstAiredMs = obj.longOrNull("firstAiredMs") ?: 0L,
            activityAtMs = obj.longOrNull("activityAtMs")
                ?: obj.longOrNull("firstAiredMs")
                ?: 0L,
            poster = obj.stringOrNull("poster"),
            backdrop = obj.stringOrNull("backdrop"),
            logo = obj.stringOrNull("logo"),
            traktShowId = obj.intOrNull("traktShowId"),
            traktEpisodeId = obj.intOrNull("traktEpisodeId"),
            tvdbAvailabilityInstantMs = obj.longOrNull("tvdbAvailabilityInstantMs"),
            tvdbAvailabilityPrecision = obj.tvdbAvailabilityPrecisionOrDefault(),
            tvdbAvailabilitySourceZoneId = obj.stringOrNull("tvdbAvailabilitySourceZoneId"),
            tvdbAvailabilitySourcePolicy = obj.stringOrNull("tvdbAvailabilitySourcePolicy"),
            tvdbAvailabilityDiagnosticReason = obj.tvdbAvailabilityDiagnosticReasonOrNull(),
            tvdbAvailabilityDeviceLocalDateTime = obj.stringOrNull("tvdbAvailabilityDeviceLocalDateTime")
        )
    }

    private fun normalizeNextUpEntry(
        entry: TrackingNextUpEntry
    ): TrackingNextUpEntry? {
        return try {
            val contentId = entry.contentId.trim()
            if (contentId.isBlank()) return null
            val season = entry.season.takeIf { it > 0 } ?: return null
            val episode = entry.episode.takeIf { it > 0 } ?: return null
            entry.copy(
                contentId = contentId,
                contentType = entry.contentType.takeIf { it.isNotBlank() } ?: "series",
                name = entry.name.takeIf { it.isNotBlank() } ?: contentId,
                videoId = entry.videoId.takeIf { it.isNotBlank() } ?: "$contentId:$season:$episode"
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun JsonObject.stringOrNull(key: String): String? {
        return runCatching {
            get(key)?.takeIf { !it.isJsonNull }?.asString
        }.getOrNull()
    }

    private fun JsonObject.intOrNull(key: String): Int? {
        return runCatching {
            get(key)?.takeIf { !it.isJsonNull }?.asInt
        }.getOrNull()
    }

    private fun JsonObject.longOrNull(key: String): Long? {
        return runCatching {
            get(key)?.takeIf { !it.isJsonNull }?.asLong
        }.getOrNull()
    }

    private fun JsonObject.floatOrNull(key: String): Float? {
        return runCatching {
            get(key)?.takeIf { !it.isJsonNull }?.asFloat
        }.getOrNull()
    }

    private fun JsonObject.objectOrNull(key: String): JsonObject? {
        return runCatching {
            get(key)?.takeIf { !it.isJsonNull }?.asJsonObject
        }.getOrNull()
    }

    private fun JsonObject.arrayOrNull(key: String): JsonArray? {
        return runCatching {
            get(key)?.takeIf { !it.isJsonNull }?.asJsonArray
        }.getOrNull()
    }

    private inline fun <reified T : Enum<T>> JsonObject.enumOrNull(key: String): T? {
        val value = stringOrNull(key) ?: return null
        return runCatching { enumValueOf<T>(value) }.getOrNull()
    }

    private fun JsonObject.stringList(key: String): List<String> {
        val array = arrayOrNull(key) ?: return emptyList()
        return array.mapNotNull { element ->
            runCatching { element.takeIf { !it.isJsonNull }?.asString }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
        }
    }

    private fun JsonObject.stringList(vararg keys: String): List<String> {
        keys.forEach { key ->
            val array = arrayOrNull(key) ?: return@forEach
            return array.mapNotNull { element ->
                runCatching { element.takeIf { !it.isJsonNull }?.asString }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
            }
        }
        return emptyList()
    }

    private fun JsonObject.stringOrNull(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> stringOrNull(key) }

    private fun JsonObject.intOrNull(vararg keys: String): Int? =
        keys.firstNotNullOfOrNull { key -> intOrNull(key) }

    private fun JsonObject.longOrNull(vararg keys: String): Long? =
        keys.firstNotNullOfOrNull { key -> longOrNull(key) }

    private fun JsonObject.floatOrNull(vararg keys: String): Float? =
        keys.firstNotNullOfOrNull { key -> floatOrNull(key) }

    private fun JsonObject.objectOrNull(vararg keys: String): JsonObject? =
        keys.firstNotNullOfOrNull { key -> objectOrNull(key) }

    private fun JsonObject.arrayOrNull(vararg keys: String): JsonArray? =
        keys.firstNotNullOfOrNull { key -> arrayOrNull(key) }

    private inline fun <reified T : Enum<T>> JsonObject.enumOrNull(vararg keys: String): T? =
        keys.firstNotNullOfOrNull { key -> enumOrNull<T>(key) }

    private fun JsonObject.tvdbAvailabilityPrecisionOrDefault(): TvdbAirAvailabilityPrecision {
        val value = stringOrNull("tvdbAvailabilityPrecision") ?: return TvdbAirAvailabilityPrecision.UNKNOWN
        return runCatching {
            TvdbAirAvailabilityPrecision.valueOf(value)
        }.getOrDefault(TvdbAirAvailabilityPrecision.UNKNOWN)
    }

    private fun JsonObject.tvdbAvailabilityDiagnosticReasonOrNull(): TvdbAirAvailabilityDiagnosticReason? {
        val value = stringOrNull("tvdbAvailabilityDiagnosticReason") ?: return null
        return runCatching {
            TvdbAirAvailabilityDiagnosticReason.valueOf(value)
        }.getOrNull()
    }
}
