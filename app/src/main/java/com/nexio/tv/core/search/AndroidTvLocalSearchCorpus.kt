package com.nexio.tv.core.search

import android.content.Context
import com.nexio.tv.core.locale.AppLocaleResolver
import com.nexio.tv.data.local.HomeCatalogSnapshotStore
import com.nexio.tv.data.local.MetadataDiskCacheStore
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.MetaPreview
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

        val rowCandidates = buildRowCandidates(snapshot.catalogRows) +
            buildRowCandidates(snapshot.fullCatalogRows)
        val candidatesByKey = linkedMapOf<String, AndroidTvSearchCandidate>()
        rowCandidates.forEach { candidate ->
            candidatesByKey.merge(candidate.identityKey, candidate, ::richerCandidate)
        }

        val rowAddonBaseUrlByKey = candidatesByKey.values.associate { it.identityKey to it.addonBaseUrl }
        snapshot.heroItems
            .mapNotNull { item ->
                val addonBaseUrl = rowAddonBaseUrlByKey[item.identityKey()]
                    ?: return@mapNotNull null
                item.toCandidate(addonBaseUrl)
            }
            .forEach { candidate ->
                candidatesByKey.merge(candidate.identityKey, candidate, ::richerCandidate)
            }

        return candidatesByKey.values.toList()
    }

    private fun buildRowCandidates(rows: List<CatalogRow>): List<AndroidTvSearchCandidate> {
        return rows.flatMap { row ->
            row.items.mapNotNull { item ->
                item.toCandidate(row.addonBaseUrl)
            }
        }
    }

    private fun MetaPreview.toCandidate(addonBaseUrl: String?): AndroidTvSearchCandidate? {
        val id = id.trim()
        val title = name.trim()
        val contentType = apiType.trim()
        if (id.isEmpty() || title.isEmpty() || contentType.isEmpty()) return null
        val enrichedMeta = readCurrentMeta(contentType, id)
        return AndroidTvSearchCandidate(
            id = id,
            contentType = contentType,
            title = enrichedMeta?.name?.takeIf { it.isNotBlank() } ?: title,
            poster = enrichedMeta?.poster?.takeIf { it.isNotBlank() } ?: poster,
            background = enrichedMeta?.background?.takeIf { it.isNotBlank() } ?: background,
            description = enrichedMeta?.description?.takeIf { it.isNotBlank() } ?: description,
            releaseInfo = enrichedMeta?.releaseInfo?.takeIf { it.isNotBlank() } ?: releaseInfo,
            runtime = enrichedMeta?.runtime?.takeIf { it.isNotBlank() } ?: runtime,
            addonBaseUrl = addonBaseUrl?.trim()?.takeIf { it.isNotEmpty() },
            source = AndroidTvSearchCandidateSource.LOCAL_HOME
        )
    }

    private fun readCurrentMeta(contentType: String, id: String): Meta? {
        val languageTag = AppLocaleResolver.resolveEffectiveAppLanguageTag(appContext)
        return metadataDiskCacheStore.readCurrentMetaForItem(
            itemKey = "${contentType.trim().lowercase()}:${id.trim()}",
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

    private fun MetaPreview.identityKey(): String {
        return "${apiType.trim().lowercase()}:${id.trim()}"
    }
}
