package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.metadata.router.ResolvedField

internal object LocalizationResolver {
    fun selectField(
        field: ResolvedField,
        policy: LocalizationPolicy,
        candidates: List<LocalizedFieldCandidate>
    ): SelectedLocalizedField? {
        val providerCandidates = candidates
            .filter { it.field == field }
            .filter { it.provider == policy.provider }

        val rejected = mutableListOf<LocalizedFieldRejection>()
        val selected = providerCandidates
            .sortedWith(compareBy { candidate -> candidate.priority(policy) })
            .firstOrNull { candidate ->
                val valid = candidate.value.cleanLocalizedValue() != null
                if (!valid) {
                    rejected += candidate.rejection("missing_or_placeholder")
                }
                valid
            }
            ?: return null

        rejected += candidates
            .filter { it.field == field && it !== selected }
            .filterNot { candidate ->
                rejected.any {
                    it.provider == candidate.provider &&
                        it.language == candidate.language &&
                        it.fallbackRole == candidate.fallbackRole
                }
            }
            .map { candidate ->
                val reason = if (candidate.provider != policy.provider) {
                    "cross_provider_fallback_not_allowed_for_missing_localized_field"
                } else {
                    "lower_priority_than_${selected.fallbackRole.name.lowercase()}"
                }
                candidate.rejection(reason)
            }

        return SelectedLocalizedField(
            field = field,
            value = selected.value.cleanLocalizedValue()!!,
            language = selected.language,
            provider = selected.provider,
            sourceShape = selected.sourceShape,
            fallbackRole = selected.fallbackRole,
            rejectedCandidates = rejected
        )
    }

    private fun LocalizedFieldCandidate.priority(policy: LocalizationPolicy): Int =
        when {
            provider != policy.provider -> 100
            fallbackRole == FallbackRole.LOCALIZED &&
                language.providerCode == policy.requestedLanguage.providerCode -> 0
            fallbackRole == FallbackRole.LANGUAGE_FALLBACK &&
                language.providerCode == policy.fallbackLanguage.providerCode -> 1
            fallbackRole == FallbackRole.CANONICAL -> 2
            fallbackRole == FallbackRole.ADDON_FALLBACK -> 3
            else -> 50
        }

    private fun LocalizedFieldCandidate.rejection(reason: String): LocalizedFieldRejection =
        LocalizedFieldRejection(
            provider = provider,
            language = language,
            fallbackRole = fallbackRole,
            reason = reason
        )
}
