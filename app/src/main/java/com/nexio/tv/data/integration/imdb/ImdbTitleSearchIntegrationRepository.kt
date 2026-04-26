package com.nexio.tv.data.integration.imdb

import com.nexio.tv.data.remote.api.ImdbSearchService
import com.nexio.tv.data.remote.api.ImdbSuggestion
import com.nexio.tv.data.repository.ImdbTitleSearchRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImdbTitleSearchIntegrationRepository @Inject constructor(
    private val searchService: ImdbSearchService
) : ImdbTitleSearchRepository {
    override suspend fun search(query: String): List<ImdbSuggestion> =
        searchService.search(query)
}
