package com.nexio.tv.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class TmdbOrganizationDetail(
    val tmdbId: Int,
    val kind: MetaCompanyKind,
    val name: String,
    val description: String?,
    val headquarters: String?,
    val homepage: String?,
    val originCountry: String?,
    val logo: String?,
    val parentCompanyName: String?,
    val titles: List<MetaPreview>,
    val totalResults: Int
)
