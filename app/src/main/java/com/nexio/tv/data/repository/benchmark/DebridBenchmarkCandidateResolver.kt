package com.nexio.tv.data.repository.benchmark

import com.nexio.tv.data.repository.DebridLibraryService
import javax.inject.Inject

class DebridBenchmarkCandidateResolver @Inject constructor(
    private val debridLibraryService: DebridLibraryService
) {
    suspend fun resolve(provider: DebridBenchmarkProvider): DebridBenchmarkCandidate? {
        return debridLibraryService.getBenchmarkCandidates(provider).firstOrNull()
    }
}
