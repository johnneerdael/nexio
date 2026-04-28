package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.integration.TvdbApiShapes
import com.nexio.tv.core.metadata.router.FieldOwner
import com.nexio.tv.core.metadata.router.FieldValue
import com.nexio.tv.core.metadata.router.MetadataCandidate
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataProviderAdapter
import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.core.metadata.router.ProviderPlanStep
import com.nexio.tv.core.metadata.router.ProviderStepResult
import com.nexio.tv.core.metadata.router.ResolvedField
import com.nexio.tv.core.metadata.router.ResolverType
import com.nexio.tv.core.tvdb.TvdbPersonService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TVDB person adapter mirroring [TmdbOrganizationPersonAdapter] for the
 * [com.nexio.tv.core.metadata.router.resolver.OrganizationPersonResolver].
 *
 * Shape conventions:
 * - [TvdbApiShapes.PERSON_EXTENDED] — `route.parentId` is `tvdb:person:<id>` (or any
 *   colon-delimited form with the integer id as the last segment). Resolves the id and
 *   emits [ResolvedField.CAST] with the [com.nexio.tv.domain.model.PersonDetail] returned
 *   by [TvdbPersonService.fetchPersonDetail].
 *
 * Unsupported shapes or unparseable ids fall back to an empty candidate so the dispatcher
 * can continue without raising an error.
 */
@Singleton
class TvdbOrganizationPersonAdapter @Inject constructor(
    private val tvdbPersonService: TvdbPersonService
) : MetadataProviderAdapter {

    override val provider: MetadataPrimaryProvider = MetadataPrimaryProvider.TVDB

    override fun supports(step: ProviderPlanStep): Boolean =
        step.apiShapeId == TvdbApiShapes.PERSON_EXTENDED

    override suspend fun execute(route: MetadataRoute, step: ProviderPlanStep): ProviderStepResult {
        if (step.apiShapeId != TvdbApiShapes.PERSON_EXTENDED) {
            return ProviderStepResult(step = step, candidate = emptyCandidate(this.provider))
        }
        val personId = route.parentId.substringAfterLast(':').toIntOrNull()
            ?: return ProviderStepResult(step = step, candidate = emptyCandidate(this.provider))
        val detail = tvdbPersonService.fetchPersonDetail(personId)
            ?: return ProviderStepResult(step = step, candidate = emptyCandidate(this.provider))
        return ProviderStepResult(
            step = step,
            candidate = MetadataCandidate(
                provider = this.provider,
                resolverType = ResolverType.ORGANIZATION_PERSON,
                fields = mapOf(
                    ResolvedField.CAST to FieldValue(detail, FieldOwner.ORGANIZATION_PERSON)
                )
            )
        )
    }
}
