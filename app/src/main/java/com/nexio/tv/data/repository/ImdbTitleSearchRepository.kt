package com.nexio.tv.data.repository

import com.nexio.tv.data.remote.api.ImdbSuggestion

interface ImdbTitleSearchRepository {
    suspend fun search(query: String): List<ImdbSuggestion>
}
