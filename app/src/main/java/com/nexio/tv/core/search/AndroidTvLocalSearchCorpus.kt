package com.nexio.tv.core.search

import android.content.Context
import com.nexio.tv.core.locale.AppLocaleResolver
import com.nexio.tv.data.local.HomeCatalogSnapshotStore
import com.nexio.tv.data.local.MetadataDiskCacheStore
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.Rail
import com.nexio.tv.domain.model.RailItemKey
import com.nexio.tv.domain.model.homeDisplayItemKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidTvLocalSearchCorpus @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val homeCatalogSnapshotStore: HomeCatalogSnapshotStore,
    private val metadataDiskCacheStore: MetadataDiskCacheStore
) {
    suspend fun candidates(): List<AndroidTvSearchCandidate> {
        val posterProviderToken = runCatching {
            homeCatalogSnapshotStore.currentPosterProviderToken()
        }.getOrElse {
            "native"
        }
        val snapshot = runCatching {
            homeCatalogSnapshotStore.readActiveProfile(posterProviderToken)
        }.getOrNull() ?: return emptyList()

        // Plan B Task 6f.4 — corpus enumerates structure via rails + heroItemKeys
        // and resolves content via MetadataDiskCacheStore. The legacy
        // catalogRows/fullCatalogRows/heroItems MetaPreview content path is
        // retired; items with no disk-cached metadata are dropped (acceptable
        // — they appear in search after the next metadata refresh).
        val candidatesByKey = linkedMapOf<String, AndroidTvSearchCandidate>()
        val addonBaseUrlByItemKey = HashMap<String, String?>()

        for (i in snapshot.rails.indices) {
            val rail = snapshot.rails[i]
            buildRailCandidates(rail, candidatesByKey, addonBaseUrlByItemKey)
        }

        for (i in snapshot.heroItemKeys.indices) {
            val key = snapshot.heroItemKeys[i]
            val itemKey = key.key
            val addonBaseUrl = addonBaseUrlByItemKey[itemKey] ?: continue
            buildItemCandidate(key.apiType, key.contentId, addonBaseUrl)?.let { candidate ->
                candidatesByKey.merge(candidate.identityKey, candidate, ::richerCandidate)
            }
        }

        return candidatesByKey.values.toList()
    }

    private fun buildRailCandidates(
        rail: Rail,
        candidatesByKey: MutableMap<String, AndroidTvSearchCandidate>,
        addonBaseUrlByItemKey: MutableMap<String, String?>
    ) {
        val addonBaseUrl = rail.addonBaseUrl
        for (i in rail.items.indices) {
            val key = rail.items[i]
            val itemKey = key.key
            addonBaseUrlByItemKey.putIfAbsent(itemKey, addonBaseUrl)
            val candidate = buildItemCandidate(key.apiType, key.contentId, addonBaseUrl) ?: continue
            candidatesByKey.merge(candidate.identityKey, candidate, ::richerCandidate)
        }
    }

    private fun buildItemCandidate(
        apiType: String,
        contentId: String,
        addonBaseUrl: String?
    ): AndroidTvSearchCandidate? {
        val id = contentId.trim()
        val contentType = apiType.trim()
        if (id.isEmpty() || contentType.isEmpty()) return null
        val displayMetadata = readCurrentHomeDisplayMetadata(contentType, id)
        val enrichedMeta = readCurrentMeta(contentType, id)
        val title = displayMetadata?.title?.takeIf { it.isNotBlank() }
            ?: enrichedMeta?.name?.takeIf { it.isNotBlank() }
            ?: return null
        return AndroidTvSearchCandidate(
            id = id,
            contentType = contentType,
            title = title,
            poster = displayMetadata?.displayPoster?.takeIf { it.isNotBlank() }
                ?: enrichedMeta?.poster?.takeIf { it.isNotBlank() },
            background = displayMetadata?.displayBackdrop?.takeIf { it.isNotBlank() }
                ?: enrichedMeta?.background?.takeIf { it.isNotBlank() },
            description = displayMetadata?.description?.takeIf { it.isNotBlank() }
                ?: enrichedMeta?.description?.takeIf { it.isNotBlank() },
            releaseInfo = displayMetadata?.releaseInfo?.takeIf { it.isNotBlank() }
                ?: enrichedMeta?.releaseInfo?.takeIf { it.isNotBlank() },
            runtime = displayMetadata?.runtime?.takeIf { it.isNotBlank() }
                ?: enrichedMeta?.runtime?.takeIf { it.isNotBlank() },
            addonBaseUrl = addonBaseUrl?.trim()?.takeIf { it.isNotEmpty() },
            source = AndroidTvSearchCandidateSource.LOCAL_HOME
        )
    }

    private fun readCurrentHomeDisplayMetadata(contentType: String, id: String): HomeDisplayMetadata? {
        val languageTag = AppLocaleResolver.resolveEffectiveAppLanguageTag(appContext)
        return metadataDiskCacheStore.readCurrentHomeDisplayMetadataForItem(
            itemKey = homeDisplayItemKey(contentType, id),
            languageTag = languageTag
        )
    }

    private fun readCurrentMeta(contentType: String, id: String): Meta? {
        val languageTag = AppLocaleResolver.resolveEffectiveAppLanguageTag(appContext)
        return metadataDiskCacheStore.readCurrentMetaForItem(
            itemKey = homeDisplayItemKey(contentType, id),
            languageTag = languageTag
        )
    }

    private fun richerCandidate(
        current: AndroidTvSearchCandidate,
        candidate: AndroidTvSearchCandidate
    ): AndroidTvSearchCandidate {
        return if (candidate.richnessScore() > current.richnessScore()) candidate else current
    }

    private fun AndroidTvSearchCandidate.richnessScore(): Int {
        var score = 0
        if (AndroidTvSearchSuggestionMapper.parseProductionYear(releaseInfo) != null) score += 5
        if (AndroidTvSearchSuggestionMapper.parseDurationMs(runtime) != null) score += 6
        if (!addonBaseUrl.isNullOrBlank()) score += 4
        if (!description.isNullOrBlank()) score += 2
        if (!poster.isNullOrBlank()) score += 1
        if (!background.isNullOrBlank()) score += 1
        return score
    }
}
