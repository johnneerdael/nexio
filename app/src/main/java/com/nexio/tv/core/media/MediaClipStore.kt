package com.nexio.tv.core.media

import android.content.Context
import com.google.gson.Gson
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.domain.model.ProviderIds
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

data class ContentIdentity(
    val contentId: String,
    val itemType: String?,
    val stableIds: ProviderIds
)

enum class MediaClipSource {
    PROVIDER,
    RAIL_FALLBACK,
    STREAILER,
    UNKNOWN
}

enum class MediaClipType {
    TRAILER,
    TEASER,
    RECAP,
    PREVIEW,
    CLIP,
    UNKNOWN
}

enum class ClipSite {
    YOUTUBE,
    PROVIDER,
    UNKNOWN
}

enum class Confidence {
    HIGH,
    MEDIUM,
    LOW
}

enum class CacheDecision {
    HIT,
    STALE_HIT
}

sealed interface MediaClipScope {
    val contentId: ContentIdentity

    data class Title(
        override val contentId: ContentIdentity
    ) : MediaClipScope

    data class Season(
        override val contentId: ContentIdentity,
        val season: Int
    ) : MediaClipScope

    data class Episode(
        override val contentId: ContentIdentity,
        val season: Int,
        val episode: Int
    ) : MediaClipScope
}

sealed interface MediaClipPlaybackRef {
    data class YouTubeId(val id: String) : MediaClipPlaybackRef
    data class ProviderUrl(val urlHash: String, val redactedUrl: String) : MediaClipPlaybackRef
    data class ResolvedPlaybackUri(val uri: String, val expiresAtMs: Long?) : MediaClipPlaybackRef
}

data class MediaClipCandidate(
    val clipId: String,
    val contentId: ContentIdentity,
    val provider: String,
    val source: MediaClipSource,
    val scope: MediaClipScope,
    val clipType: MediaClipType,
    val title: String?,
    val language: String?,
    val site: ClipSite,
    val externalVideoId: String?,
    val playbackRef: MediaClipPlaybackRef?,
    val confidence: Confidence,
    val sourceTrace: List<String>,
    val fetchedAtMs: Long
)

data class StoredMediaClip(
    val key: String,
    val clipId: String,
    val contentId: ContentIdentity,
    val provider: String,
    val source: MediaClipSource,
    val scope: MediaClipScope,
    val clipType: MediaClipType,
    val title: String?,
    val language: String?,
    val site: ClipSite,
    val externalVideoId: String?,
    val playbackRef: MediaClipPlaybackRef?,
    val providerUrlHash: String?,
    val redactedUrl: String?,
    val confidence: Confidence,
    val fetchedAtMs: Long,
    val expiresAtMs: Long,
    val staleUntilMs: Long,
    val sourceTrace: List<String>,
    val cacheDecision: CacheDecision
)

internal data class StoredMediaClipRecord(
    val key: String,
    val clipId: String,
    val contentId: String,
    val itemType: String?,
    val tmdbId: String?,
    val tvdbId: String?,
    val imdbId: String?,
    val kitsuId: String?,
    val provider: String,
    val source: String,
    val scopeKind: String,
    val season: Int?,
    val episode: Int?,
    val clipType: String,
    val title: String?,
    val language: String?,
    val site: String,
    val externalVideoId: String?,
    val playbackKind: String?,
    val youtubeId: String?,
    val providerUrlHash: String?,
    val redactedUrl: String?,
    val confidence: String,
    val fetchedAtMs: Long,
    val expiresAtMs: Long,
    val staleUntilMs: Long,
    val sourceTrace: List<String>
)

@Singleton
class MediaClipStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val traceEvents: TraceMetadataEvents? = null
) {
    private val gson = Gson()
    private val prefsName: String = DEFAULT_PREFS_NAME
    private val clock: () -> Long = { System.currentTimeMillis() }
    private val entryStore by lazy {
        MediaClipTypedStore(
            file = File(context.filesDir, "${fileNamespace()}/entries.json"),
            gson = gson
        ).also { store ->
            migrateV1FileIfNeeded(store)
            migrateLegacyPrefsIfNeeded(store)
        }
    }

    internal constructor(
        context: Context,
        prefsName: String = DEFAULT_PREFS_NAME,
        clock: () -> Long = { System.currentTimeMillis() }
    ) : this(context, null) {
        mutablePrefsName = prefsName
        mutableClock = clock
    }

    private var mutablePrefsName: String? = null
    private var mutableClock: (() -> Long)? = null

    fun storeCandidates(
        candidates: List<MediaClipCandidate>,
        freshTtlMs: Long = TITLE_TRAILER_FRESH_TTL_MS,
        staleTtlMs: Long = TITLE_TRAILER_STALE_TTL_MS
    ): Int {
        if (candidates.isEmpty()) return 0
        val now = nowMs()
        val records = candidates
            .mapNotNull { candidate ->
                candidate.toRecord(
                    nowMs = now,
                    freshTtlMs = freshTtlMs,
                    staleTtlMs = staleTtlMs
                )
            }
            .distinctBy { record -> record.key }
        if (records.isEmpty()) return 0
        if (!entryStore.putAll(records)) return 0
        records.forEach { record ->
            traceEvents?.emitMediaClipCandidateStored(
                itemKey = record.contentId,
                provider = record.provider,
                clipType = record.clipType,
                site = record.site,
                videoId = record.externalVideoId ?: record.youtubeId,
                scope = record.scopeKind,
                cacheDecision = "WRITE"
            )
        }
        return records.size
    }

    fun getCandidates(
        identity: ContentIdentity,
        scope: MediaClipScope,
        clipTypes: Set<MediaClipType>,
        language: String?,
        includeStale: Boolean = true
    ): List<StoredMediaClip> {
        val now = nowMs()
        val normalizedLanguage = language?.trim()?.takeIf { it.isNotBlank() }
        return entryStore.records()
            .asSequence()
            .mapNotNull { record ->
                record.toStoredMediaClipIfMatching(
                    identity = identity,
                    scope = scope,
                    clipTypes = clipTypes,
                    normalizedLanguage = normalizedLanguage,
                    nowMs = now,
                    includeStale = includeStale
                )
            }
            .sortedWith(
                compareBy<StoredMediaClip> { if (it.cacheDecision == CacheDecision.HIT) 0 else 1 }
                    .thenBy { it.clipType.ordinal }
                    .thenBy { it.confidence.ordinal }
            )
            .toList()
    }

    private fun MediaClipCandidate.toRecord(
        nowMs: Long,
        freshTtlMs: Long,
        staleTtlMs: Long
    ): StoredMediaClipRecord? {
        val normalizedClipId = clipId.trim().takeIf { it.isNotBlank() } ?: return null
        val normalizedContentId = contentId.contentId.trim().takeIf { it.isNotBlank() } ?: return null
        val normalizedExternalVideoId = externalVideoId?.trim()?.takeIf { it.isNotBlank() }
        val durableRef = playbackRef.toDurableRecordRef()
        return StoredMediaClipRecord(
            key = "$KEY_PREFIX${normalizedClipId.stableHash()}",
            clipId = normalizedClipId,
            contentId = normalizedContentId,
            itemType = contentId.itemType?.trim()?.takeIf { it.isNotBlank() },
            tmdbId = contentId.stableIds.tmdb?.trim()?.takeIf { it.isNotBlank() },
            tvdbId = contentId.stableIds.tvdb?.trim()?.takeIf { it.isNotBlank() },
            imdbId = contentId.stableIds.imdb?.trim()?.takeIf { it.isNotBlank() },
            kitsuId = contentId.stableIds.kitsu?.trim()?.takeIf { it.isNotBlank() },
            provider = provider.trim().takeIf { it.isNotBlank() } ?: "UNKNOWN",
            source = source.name,
            scopeKind = scope.kind,
            season = (scope as? MediaClipScope.Season)?.season ?: (scope as? MediaClipScope.Episode)?.season,
            episode = (scope as? MediaClipScope.Episode)?.episode,
            clipType = clipType.name,
            title = title?.trim()?.takeIf { it.isNotBlank() },
            language = language?.trim()?.takeIf { it.isNotBlank() },
            site = site.name,
            externalVideoId = normalizedExternalVideoId,
            playbackKind = durableRef?.kind,
            youtubeId = durableRef?.youtubeId,
            providerUrlHash = durableRef?.providerUrlHash,
            redactedUrl = durableRef?.redactedUrl,
            confidence = confidence.name,
            fetchedAtMs = fetchedAtMs.takeIf { it > 0L } ?: nowMs,
            expiresAtMs = nowMs + freshTtlMs,
            staleUntilMs = nowMs + staleTtlMs,
            sourceTrace = sourceTrace
        )
    }

    private fun MediaClipPlaybackRef?.toDurableRecordRef(): DurableRecordRef? =
        when (this) {
            null -> null
            is MediaClipPlaybackRef.YouTubeId -> id.trim()
                .takeIf { it.isNotBlank() }
                ?.let { DurableRecordRef(kind = PLAYBACK_YOUTUBE, youtubeId = it) }
            is MediaClipPlaybackRef.ProviderUrl -> DurableRecordRef(
                kind = PLAYBACK_PROVIDER_URL,
                providerUrlHash = urlHash.trim().takeIf { it.isNotBlank() },
                redactedUrl = redactedUrl.trim().takeIf { it.isNotBlank() }
            )
            is MediaClipPlaybackRef.ResolvedPlaybackUri -> null
        }

    private fun StoredMediaClipRecord.toStoredMediaClip(
        nowMs: Long,
        includeStale: Boolean
    ): StoredMediaClip? {
        val decision = when {
            nowMs <= expiresAtMs -> CacheDecision.HIT
            includeStale && nowMs <= staleUntilMs -> CacheDecision.STALE_HIT
            else -> return null
        }
        val identity = ContentIdentity(
            contentId = contentId,
            itemType = itemType,
            stableIds = ProviderIds(
                imdb = imdbId,
                tmdb = tmdbId,
                tvdb = tvdbId,
                kitsu = kitsuId
            )
        )
        val scope = when (scopeKind) {
            SCOPE_SEASON -> MediaClipScope.Season(identity, season ?: return null)
            SCOPE_EPISODE -> MediaClipScope.Episode(identity, season ?: return null, episode ?: return null)
            else -> MediaClipScope.Title(identity)
        }
        return StoredMediaClip(
            key = key,
            clipId = clipId,
            contentId = identity,
            provider = provider,
            source = enumValueOrDefault(source, MediaClipSource.UNKNOWN),
            scope = scope,
            clipType = enumValueOrDefault(clipType, MediaClipType.UNKNOWN),
            title = title,
            language = language,
            site = enumValueOrDefault(site, ClipSite.UNKNOWN),
            externalVideoId = externalVideoId,
            playbackRef = toPlaybackRef(),
            providerUrlHash = providerUrlHash,
            redactedUrl = redactedUrl,
            confidence = enumValueOrDefault(confidence, Confidence.LOW),
            fetchedAtMs = fetchedAtMs,
            expiresAtMs = expiresAtMs,
            staleUntilMs = staleUntilMs,
            sourceTrace = sourceTrace,
            cacheDecision = decision
        )
    }

    private fun StoredMediaClipRecord.toPlaybackRef(): MediaClipPlaybackRef? =
        when (playbackKind) {
            PLAYBACK_YOUTUBE -> youtubeId?.takeIf { it.isNotBlank() }?.let(MediaClipPlaybackRef::YouTubeId)
            PLAYBACK_PROVIDER_URL -> {
                val hash = providerUrlHash?.takeIf { it.isNotBlank() } ?: return null
                val redacted = redactedUrl?.takeIf { it.isNotBlank() } ?: return null
                MediaClipPlaybackRef.ProviderUrl(hash, redacted)
            }
            else -> null
        }

    private fun StoredMediaClipRecord.toStoredMediaClipIfMatching(
        identity: ContentIdentity,
        scope: MediaClipScope,
        clipTypes: Set<MediaClipType>,
        normalizedLanguage: String?,
        nowMs: Long,
        includeStale: Boolean
    ): StoredMediaClip? = runCatching {
        if (!matchesIdentity(identity)) return@runCatching null
        if (!matchesScope(scope)) return@runCatching null
        if (clipTypes.isNotEmpty() && enumValueOrDefault(clipType, MediaClipType.UNKNOWN) !in clipTypes) {
            return@runCatching null
        }
        if (normalizedLanguage != null && language != null && language != normalizedLanguage) {
            return@runCatching null
        }
        toStoredMediaClip(nowMs, includeStale)
    }.getOrNull()

    private fun StoredMediaClipRecord.matchesIdentity(identity: ContentIdentity): Boolean {
        val target = identity.normalized()
        if (contentId == target.contentId) return true
        return listOfNotNull(
            tmdbId?.let { it == target.stableIds.tmdb },
            tvdbId?.let { it == target.stableIds.tvdb },
            imdbId?.let { it == target.stableIds.imdb },
            kitsuId?.let { it == target.stableIds.kitsu }
        ).any { it }
    }

    private fun StoredMediaClipRecord.matchesScope(scope: MediaClipScope): Boolean {
        return when (scope) {
            is MediaClipScope.Title -> scopeKind == SCOPE_TITLE
            is MediaClipScope.Season -> scopeKind == SCOPE_SEASON && season == scope.season
            is MediaClipScope.Episode -> scopeKind == SCOPE_EPISODE && season == scope.season && episode == scope.episode
        }
    }

    private fun ContentIdentity.normalized(): ContentIdentity =
        copy(
            contentId = contentId.trim(),
            itemType = itemType?.trim()?.takeIf { it.isNotBlank() },
            stableIds = ProviderIds(
                imdb = stableIds.imdb?.trim()?.takeIf { it.isNotBlank() },
                tmdb = stableIds.tmdb?.trim()?.takeIf { it.isNotBlank() },
                tvdb = stableIds.tvdb?.trim()?.takeIf { it.isNotBlank() },
                kitsu = stableIds.kitsu?.trim()?.takeIf { it.isNotBlank() }
            )
        )

    private fun prefs() = context.getSharedPreferences(mutablePrefsName ?: prefsName, Context.MODE_PRIVATE)

    private fun fileNamespace(): String =
        mutablePrefsName
            ?.takeUnless { it == DEFAULT_PREFS_NAME }
            ?: DEFAULT_FILE_NAMESPACE

    private fun v1EntriesFile(): File =
        File(
            context.filesDir,
            "${mutablePrefsName?.takeUnless { it == DEFAULT_PREFS_NAME } ?: "media-clip-store-v1"}/entries.json"
        )

    private fun migrateV1FileIfNeeded(store: MediaClipTypedStore) {
        val v1File = v1EntriesFile()
        if (!v1File.isFile) return
        if (!store.migrateFromV1File(v1File)) return
        if (v1File.canonicalFile != File(context.filesDir, "${fileNamespace()}/entries.json").canonicalFile) {
            v1File.delete()
        }
    }

    private fun migrateLegacyPrefsIfNeeded(store: MediaClipTypedStore) {
        val legacy = prefs()
        val legacyKeys = legacy.all.keys
            .filter { key -> key.startsWith(KEY_PREFIX) }
        if (legacyKeys.isEmpty()) return

        val entriesToMigrate = linkedMapOf<String, String>()
        for (key in legacyKeys) {
            val raw = legacy.getString(key, null)?.takeIf { it.isNotBlank() } ?: continue
            entriesToMigrate[key] = raw
        }

        if (!store.migrateLegacyEntries(entriesToMigrate)) return

        val editor = legacy.edit()
        for (key in legacyKeys) {
            editor.remove(key)
        }
        editor.commit()
    }

    private fun nowMs(): Long = (mutableClock ?: clock).invoke()

    private data class DurableRecordRef(
        val kind: String,
        val youtubeId: String? = null,
        val providerUrlHash: String? = null,
        val redactedUrl: String? = null
    )

    private companion object {
        const val DEFAULT_PREFS_NAME = "media_clip_store_v1"
        const val DEFAULT_FILE_NAMESPACE = "media-clip-store-v2"
        const val KEY_PREFIX = "media-clip:"
        const val SCOPE_TITLE = "title"
        const val SCOPE_SEASON = "season"
        const val SCOPE_EPISODE = "episode"
        const val PLAYBACK_YOUTUBE = "youtube"
        const val PLAYBACK_PROVIDER_URL = "provider_url"
        const val TITLE_TRAILER_FRESH_TTL_MS = 7L * 24L * 60L * 60L * 1000L
        const val TITLE_TRAILER_STALE_TTL_MS = 30L * 24L * 60L * 60L * 1000L
    }
}

private val MediaClipScope.kind: String
    get() = when (this) {
        is MediaClipScope.Title -> "title"
        is MediaClipScope.Season -> "season"
        is MediaClipScope.Episode -> "episode"
    }

private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, default: T): T =
    runCatching { enumValueOf<T>(value.orEmpty()) }.getOrDefault(default)

private fun String.stableHash(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
    return digest.joinToString("") { byte -> "%02x".format(byte) }
}
