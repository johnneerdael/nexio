package com.nexio.tv.data.repository.benchmark

import com.nexio.tv.data.repository.DebridLibraryService
import javax.inject.Inject
import kotlin.random.Random

class DebridBenchmarkCandidateResolver internal constructor(
    private val debridLibraryService: DebridLibraryService,
    private val chooseIndex: (Int) -> Int
) {
    @Inject
    constructor(
        debridLibraryService: DebridLibraryService
    ) : this(
        debridLibraryService = debridLibraryService,
        chooseIndex = { size -> Random.nextInt(size) }
    )

    suspend fun resolve(provider: DebridBenchmarkProvider): DebridBenchmarkCandidateResolution {
        return when (val lookup = debridLibraryService.getBenchmarkCandidates(provider)) {
            is DebridBenchmarkCandidateLookupResult.Candidates -> {
                val candidates = lookup.items
                if (candidates.isEmpty()) {
                    DebridBenchmarkCandidateResolution.NoPlayableLibraryItem
                } else {
                    DebridBenchmarkCandidateResolution.Candidate(
                        value = candidates[chooseIndex(candidates.size)]
                    )
                }
            }
            DebridBenchmarkCandidateLookupResult.NoLargeDownload ->
                DebridBenchmarkCandidateResolution.NoLargeDownload
            DebridBenchmarkCandidateLookupResult.NoPlayableLibraryItem ->
                DebridBenchmarkCandidateResolution.NoPlayableLibraryItem
        }
    }
}
