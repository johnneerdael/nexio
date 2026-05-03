package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.integration.TmdbApiShapes
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
import com.nexio.tv.domain.model.PersonDetail
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TMDB person / company / network adapter. Powers the
 * [com.nexio.tv.core.metadata.router.resolver.OrganizationPersonResolver] by emitting
 * candidates carrying [ResolvedField.CAST], [ResolvedField.CREW], or
 * [ResolvedField.ORGANIZATION_LIST].
 *
 * Shape conventions:
 * - [TmdbApiShapes.PERSON_DETAIL] — `route.targetIds[TMDB]` is the person id (or `parentId`
 *   strips the `tmdb:` prefix). Emits [ResolvedField.CAST] with a `PersonDetail` whose
 *   filmography defaults to the cast/acting credit set.
 * - [TmdbApiShapes.PERSON_COMBINED_CREDITS] — same id resolution, but requests crew
 *   filmography (`preferCrewCredits = true`) and emits [ResolvedField.CREW].
 * - [TmdbApiShapes.PERSON_FIND_BY_NAME] — `route.parentId` is the person's display name;
 *   resolves to a person id, then proceeds as PERSON_DETAIL.
 * - [TmdbApiShapes.COMPANY_DETAIL] / [TmdbApiShapes.NETWORK_DETAIL] —
 *   `route.targetIds[TMDB]` is the company / network id. Emits [ResolvedField.ORGANIZATION_LIST]
 *   carrying that id (richer detail TBD in follow-up tasks).
 * - [TmdbApiShapes.COMPANY_FIND_BY_NAME] — `route.parentId` is the company name; resolves
 *   to a company id and emits [ResolvedField.ORGANIZATION_LIST].
 *
 * Unsupported shapes return an empty candidate so the dispatcher can continue without error.
 */
@Singleton
class TmdbOrganizationPersonAdapter @Inject constructor(
    private val secondaryRepository: MetadataSecondaryRepository
) : MetadataProviderAdapter {
    override val provider: MetadataPrimaryProvider = MetadataPrimaryProvider.TMDB

    override fun supports(step: ProviderPlanStep): Boolean = step.apiShapeId in SUPPORTED_SHAPES

    override suspend fun execute(route: MetadataRoute, step: ProviderPlanStep): ProviderStepResult {
        return when (step.apiShapeId) {
            TmdbApiShapes.PERSON_DETAIL -> personCandidate(
                route = route,
                step = step,
                preferCrewCredits = false,
                field = ResolvedField.CAST
            )
            TmdbApiShapes.PERSON_COMBINED_CREDITS -> personCandidate(
                route = route,
                step = step,
                preferCrewCredits = true,
                field = ResolvedField.CREW
            )
            TmdbApiShapes.PERSON_FIND_BY_NAME -> {
                val name = nameFromParentId(route.parentId)
                    ?: return ProviderStepResult(step = step, candidate = emptyCandidate(this.provider))
                val resolvedId = secondaryRepository.findPersonIdByExactName(name)
                    ?: return ProviderStepResult(step = step, candidate = emptyCandidate(this.provider))
                personCandidate(
                    personId = resolvedId,
                    step = step,
                    preferCrewCredits = false,
                    field = ResolvedField.CAST
                )
            }
            TmdbApiShapes.COMPANY_DETAIL,
            TmdbApiShapes.NETWORK_DETAIL -> {
                val orgId = MetadataProviderTargetIds.tmdbInt(route.targetIds[MetadataPrimaryProvider.TMDB])
                    ?: route.parentId.substringAfterLast(':').toIntOrNull()
                    ?: return ProviderStepResult(step = step, candidate = emptyCandidate(this.provider))
                organizationCandidate(step = step, orgId = orgId)
            }
            TmdbApiShapes.COMPANY_FIND_BY_NAME -> {
                val name = nameFromParentId(route.parentId)
                    ?: return ProviderStepResult(step = step, candidate = emptyCandidate(this.provider))
                val resolvedId = secondaryRepository.findCompanyIdByExactName(name)
                    ?: return ProviderStepResult(step = step, candidate = emptyCandidate(this.provider))
                organizationCandidate(step = step, orgId = resolvedId)
            }
            else -> ProviderStepResult(step = step, candidate = emptyCandidate(this.provider))
        }
    }

    private suspend fun personCandidate(
        route: MetadataRoute,
        step: ProviderPlanStep,
        preferCrewCredits: Boolean,
        field: ResolvedField
    ): ProviderStepResult {
        val personId = MetadataProviderTargetIds.tmdbInt(route.targetIds[MetadataPrimaryProvider.TMDB])
            ?: route.parentId.substringAfterLast(':').toIntOrNull()
            ?: return ProviderStepResult(step = step, candidate = emptyCandidate(this.provider))
        return personCandidate(
            personId = personId,
            step = step,
            preferCrewCredits = preferCrewCredits,
            field = field
        )
    }

    private suspend fun personCandidate(
        personId: Int,
        step: ProviderPlanStep,
        preferCrewCredits: Boolean,
        field: ResolvedField
    ): ProviderStepResult {
        val detail: PersonDetail = secondaryRepository.fetchPersonDetail(
            personId = personId,
            preferCrewCredits = preferCrewCredits
        ) ?: return ProviderStepResult(step = step, candidate = emptyCandidate(this.provider))
        return ProviderStepResult(
            step = step,
            candidate = MetadataCandidate(
                provider = this.provider,
                resolverType = ResolverType.ORGANIZATION_PERSON,
                fields = mapOf(
                    field to FieldValue(detail, FieldOwner.ORGANIZATION_PERSON)
                )
            )
        )
    }

    private fun organizationCandidate(step: ProviderPlanStep, orgId: Int): ProviderStepResult =
        ProviderStepResult(
            step = step,
            candidate = MetadataCandidate(
                provider = this.provider,
                resolverType = ResolverType.ORGANIZATION_PERSON,
                fields = mapOf(
                    ResolvedField.ORGANIZATION_LIST to FieldValue(
                        listOf(orgId),
                        FieldOwner.ORGANIZATION_PERSON
                    )
                )
            )
        )

    /**
     * `parentId` for find-by-name routes is `tmdb:<urlencoded-name>` or just the raw
     * name; we accept either form and strip the optional `tmdb:` prefix.
     */
    private fun nameFromParentId(parentId: String): String? {
        val trimmed = parentId.trim().takeIf { it.isNotBlank() } ?: return null
        val stripped = if (trimmed.startsWith("tmdb:", ignoreCase = true)) {
            trimmed.substringAfter(':')
        } else {
            trimmed
        }
        return stripped.takeIf { it.isNotBlank() }
    }

    private companion object {
        val SUPPORTED_SHAPES = setOf(
            TmdbApiShapes.PERSON_DETAIL,
            TmdbApiShapes.PERSON_COMBINED_CREDITS,
            TmdbApiShapes.PERSON_FIND_BY_NAME,
            TmdbApiShapes.COMPANY_DETAIL,
            TmdbApiShapes.NETWORK_DETAIL,
            TmdbApiShapes.COMPANY_FIND_BY_NAME
        )
    }
}
