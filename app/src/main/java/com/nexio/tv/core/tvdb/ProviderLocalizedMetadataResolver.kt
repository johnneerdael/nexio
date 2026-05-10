package com.nexio.tv.core.tvdb

import androidx.annotation.VisibleForTesting
import com.nexio.tv.core.metadata.router.MetadataRequest
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.core.metadata.router.ResolvedField
import com.nexio.tv.core.metadata.router.ResolvedMetadataDocument
import com.nexio.tv.core.metadata.router.SourceRole
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

@Singleton
class ProviderLocalizedMetadataResolver @Inject constructor(
    private val metadataRouterFacade: MetadataRouterFacade
) {
    suspend fun fetchDecision(
        metadataRequest: MetadataRequest,
        tvRequest: TvMetadataRequest
    ): TvMetadataDecision<TvMetadataEnrichment> {
        val canonical = try {
            metadataRouterFacade.resolveRequest(metadataRequest)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return canonicalRouteFailureDecision(tvRequest, "facade_exception:${e::class.java.simpleName}")
        }

        val canonicalDocument = canonical.resolvedDocument
        val canonicalRoute = canonical.route
        if (canonicalRoute == null) {
            return canonicalRouteFailureDecision(tvRequest, "canonical_resolution_empty")
        }

        val provider = canonicalRoute.provider.toTvProvider()
        val hasLocalizedPayload = canonicalDocument.hasProviderLocalizedHomePayload()
        return TvMetadataDecision(
            provider = provider,
            reason = provider.canonicalDecisionReason(hasLocalizedPayload),
            // Bug B fix (2026-05-10 dossier): the canonical document already
            // carries the production language; the short-circuit path used to
            // strip it. Production and tests share a single mapping
            // (`asTvMetadataEnrichmentForCanonicalRoute`) so they cannot drift.
            value = canonicalDocument.asTvMetadataEnrichmentForCanonicalRoute(),
            diagnostics = provider.canonicalDiagnostics(tvRequest, hasLocalizedPayload)
        )
    }

    private fun canonicalRouteFailureDecision(
        tvRequest: TvMetadataRequest,
        detail: String
    ): TvMetadataDecision<TvMetadataEnrichment> =
        TvMetadataDecision(
            provider = TvProvider.TVDB,
            reason = TvMetadataDecisionReason.PROVIDER_LOCALIZED_CANONICAL_ROUTE_FAILURE,
            value = null,
            diagnostics = listOf(
                TvMetadataDiagnosticEvent(
                    reason = TvMetadataDecisionReason.PROVIDER_LOCALIZED_CANONICAL_ROUTE_FAILURE,
                    contentId = tvRequest.contentId,
                    provider = TvProvider.TVDB,
                    detail = detail
                )
            )
        )

    private fun com.nexio.tv.core.metadata.router.ResolvedMetadataDocument.hasProviderLocalizedHomePayload(): Boolean {
        if (title.isNullOrBlank() || sourceRoles[ResolvedField.TITLE].isPreviewRole()) return false
        return listOf(
            ResolvedField.OVERVIEW,
            ResolvedField.POSTER,
            ResolvedField.BACKDROP,
            ResolvedField.LOGO,
            ResolvedField.RATING,
            ResolvedField.RUNTIME
        ).any { field -> hasValue(field) && !sourceRoles[field].isPreviewRole() }
    }

    private fun com.nexio.tv.core.metadata.router.ResolvedMetadataDocument.hasValue(field: ResolvedField): Boolean =
        when (field) {
            ResolvedField.OVERVIEW -> !overview.isNullOrBlank()
            ResolvedField.POSTER -> !poster.isNullOrBlank()
            ResolvedField.BACKDROP -> !backdrop.isNullOrBlank()
            ResolvedField.LOGO -> !logo.isNullOrBlank()
            ResolvedField.RATING -> rating != null
            ResolvedField.RUNTIME -> runtimeMinutes != null
            else -> false
        }

    private fun SourceRole?.isPreviewRole(): Boolean =
        this == SourceRole.ADDON_PREVIEW || this == SourceRole.RAIL_PREVIEW

    private fun MetadataPrimaryProvider.toTvProvider(): TvProvider =
        when (this) {
            MetadataPrimaryProvider.KITSU -> TvProvider.KITSU
            MetadataPrimaryProvider.TMDB -> TvProvider.TMDB
            MetadataPrimaryProvider.TVDB -> TvProvider.TVDB
            MetadataPrimaryProvider.IMDB,
            MetadataPrimaryProvider.TRAKT,
            MetadataPrimaryProvider.SIMKL -> TvProvider.TMDB
            // RPDB and TOP_POSTERS are artwork-only providers; fall back to TMDB for any
            // localization context that erroneously references them.
            MetadataPrimaryProvider.RPDB,
            MetadataPrimaryProvider.TOP_POSTERS -> TvProvider.TMDB
        }

    private fun TvProvider.canonicalDecisionReason(hasLocalizedPayload: Boolean): TvMetadataDecisionReason =
        when {
            this == TvProvider.TMDB -> TvMetadataDecisionReason.TVDB_FALLBACK_TMDB
            !hasLocalizedPayload -> TvMetadataDecisionReason.PROVIDER_LOCALIZED_CANONICAL_PAYLOAD_MISSING
            this == TvProvider.KITSU -> TvMetadataDecisionReason.KITSU_SUCCESS
            else -> TvMetadataDecisionReason.TVDB_SUCCESS
        }

    private fun TvProvider.canonicalDiagnostics(
        tvRequest: TvMetadataRequest,
        hasLocalizedPayload: Boolean
    ): List<TvMetadataDiagnosticEvent> =
        when (this) {
            TvProvider.TVDB -> buildList {
                if (!hasLocalizedPayload) {
                    add(
                        TvMetadataDiagnosticEvent(
                            reason = TvMetadataDecisionReason.PROVIDER_LOCALIZED_CANONICAL_PAYLOAD_MISSING,
                            contentId = tvRequest.contentId,
                            provider = TvProvider.TVDB,
                            detail = "canonical_provider_result_missing_localized_home_payload"
                        )
                    )
                }
                add(
                    TvMetadataDiagnosticEvent(
                        reason = TvMetadataDecisionReason.TMDB_TV_SKIPPED,
                        contentId = tvRequest.contentId,
                        provider = TvProvider.TVDB,
                        fallbackProvider = TvProvider.TMDB
                    )
                )
            }
            TvProvider.TMDB -> listOf(
                TvMetadataDiagnosticEvent(
                    reason = TvMetadataDecisionReason.TVDB_FALLBACK_TMDB,
                    contentId = tvRequest.contentId,
                    provider = TvProvider.TMDB,
                    fallbackProvider = TvProvider.TMDB
                )
            )
            else -> emptyList()
        }
}

// ---------------------------------------------------------------------------
// Canonical-route mapping. Production code in `fetchDecision` calls
// `asTvMetadataEnrichmentForCanonicalRoute` directly; the same function is the
// unit-test seam. Single mapping → no drift between production and tests.
// `stubCanonicalDocumentWithLanguage` exists only for testing.
// ---------------------------------------------------------------------------

@VisibleForTesting
internal fun stubCanonicalDocumentWithLanguage(language: String): ResolvedMetadataDocument =
    ResolvedMetadataDocument(
        canonicalId = "tvdb:393268",
        title = "Citadel",
        overview = null,
        poster = null,
        backdrop = null,
        logo = null,
        rating = null,
        runtimeMinutes = null,
        language = language,
        fieldOwners = emptyMap(),
        ignoredOverwrites = emptyList()
    )

@VisibleForTesting
internal fun ResolvedMetadataDocument.asTvMetadataEnrichmentForCanonicalRoute(): TvMetadataEnrichment =
    TvMetadataEnrichment(
        seriesTvdbId = canonicalId?.substringAfter("tvdb:")?.toIntOrNull(),
        localizedTitle = title,
        description = overview,
        poster = poster,
        backdrop = backdrop,
        logo = logo,
        rating = (rating as? Number)?.toDouble(),
        runtimeMinutes = runtimeMinutes,
        language = language,
        // E7: carry production language so the home/CW provider-localized
        // overlay (toHomeCompatibilityDisplayMetadata, applyTo) can plumb
        // it onto MetaPreview and into the player nav arg.
        originalLanguage = originalLanguage
    )
