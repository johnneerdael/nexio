package com.nexio.tv.data.repository

object ContinueWatchingMerger {
    // Stateless — instantiated once at object-init time. Hilt-injected planners elsewhere
    // reuse the same class; this internal instance is only consulted by mergeRecords.
    private val diffPlanner = ContinueWatchingProgressDiffPlanner()

    // Records at or above this completion ratio do not belong in Continue Watching.
    // Matches ContinueWatchingProgressDiffPlanner.NEAR_COMPLETE_PERCENT (95%) and
    // CrossWatch's _planner.py:218 max_percent default. The upstream
    // ContinueWatchingSnapshotService.shouldTreatAsResumeForContinueWatching already
    // drops >=85% records (WatchProgress.COMPLETED_THRESHOLD), but applying the cap here
    // too keeps the merger self-consistent regardless of upstream filter drift.
    private const val NEAR_COMPLETE_PERCENT = 95f

    fun merge(records: List<ContinueWatchingRecord>): List<ContinueWatchingRecord> {
        if (records.isEmpty()) return emptyList()
        val eligible = records.filter { it.isEligibleForContinueWatching() }
        if (eligible.isEmpty()) return emptyList()
        val sorted = eligible.sortedByDescending { it.updatedAt }

        // Union-find: two records share a group if they share any non-null ID under the
        // same provider key. Episode bundles include season+episode in the bucket key so
        // they only collapse when both episode coordinates match.
        val parent = IntArray(sorted.size) { it }
        fun find(x: Int): Int {
            var cur = x
            while (parent[cur] != cur) {
                parent[cur] = parent[parent[cur]]
                cur = parent[cur]
            }
            return cur
        }
        fun union(a: Int, b: Int) {
            val ra = find(a); val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }

        val byBucket = HashMap<String, MutableList<Int>>()
        sorted.forEachIndexed { idx, record ->
            // Harvest bucket keys from EVERY identity surface the record carries, not just
            // idBundle. After disk restore older snapshots may have an empty idBundle, and
            // even fresh in-memory records can have provider-disjoint identity sets (e.g.
            // Trakt record has imdb+trakt, local record has tmdb-only) — pulling keys
            // also from displayIdentity / trackingIdentity / canonicalKey.canonicalParent
            // means as soon as ONE record carries the cross-provider link, the union-find
            // catches both. Legacy identityKey() is appended as a final safety net for
            // records with no provider IDs at all.
            val keys = record.allBucketKeys()
            if (keys.isEmpty()) {
                byBucket.getOrPut("legacy:${record.identityKey()}") { mutableListOf() }.add(idx)
            } else {
                keys.forEach { key ->
                    byBucket.getOrPut(key) { mutableListOf() }.add(idx)
                }
            }
        }
        byBucket.values.forEach { indices ->
            for (i in 1 until indices.size) union(indices[0], indices[i])
        }

        // Reduce each group. mergeRecords returns null when the planner decides the group
        // should not surface in CW (e.g. all candidates >=95% complete after cross-provider
        // conflict resolution). Null groups are dropped from the final list.
        val groups = LinkedHashMap<Int, ContinueWatchingRecord?>()
        sorted.indices.forEach { idx ->
            val root = find(idx)
            if (!groups.containsKey(root)) {
                groups[root] = sorted[idx]
            } else {
                groups[root] = mergeRecords(groups[root], sorted[idx])
            }
        }
        return groups.values.filterNotNull().sortedByDescending { it.updatedAt }
    }

    /**
     * Union of all provider-id-derived bucket keys across every identity surface
     * the record carries: idBundle, displayIdentity, trackingIdentity, and the
     * canonicalKey's canonicalParent. Each emits one key per non-null provider
     * id ("imdb:tt..." / "tmdb:..." / etc.), suffixed with `:s<season>e<episode>`
     * when the record is an episode so episode bundles bucket separately from
     * one another. Returns an empty list when no record-level identity carries
     * any provider id (the legacy fallback path in [merge] handles that case).
     */
    private fun ContinueWatchingRecord.allBucketKeys(): List<String> {
        val episodeSuffix = episodeContext?.let { ":s${it.season}e${it.number}" } ?: ""
        val keys = LinkedHashSet<String>()
        keys.addAll(idBundle.toBucketKeys())
        displayIdentity?.providerIds?.let { keys.addAllProviderKeys(it, episodeSuffix) }
        trackingIdentity?.providerIds?.let { keys.addAllProviderKeys(it, episodeSuffix) }
        canonicalKey?.canonicalParent?.providerIds?.let { keys.addAllProviderKeys(it, episodeSuffix) }
        return keys.toList()
    }

    private fun MutableSet<String>.addAllProviderKeys(
        ids: com.nexio.tv.domain.model.ProviderIds,
        episodeSuffix: String
    ) {
        ids.imdb?.takeIf { it.isNotBlank() }?.let { add("imdb:$it$episodeSuffix") }
        ids.tmdb?.takeIf { it.isNotBlank() }?.let { add("tmdb:$it$episodeSuffix") }
        ids.tvdb?.takeIf { it.isNotBlank() }?.let { add("tvdb:$it$episodeSuffix") }
        ids.kitsu?.takeIf { it.isNotBlank() }?.let { add("kitsu:$it$episodeSuffix") }
        ids.mal?.takeIf { it.isNotBlank() }?.let { add("mal:$it$episodeSuffix") }
        ids.anilist?.takeIf { it.isNotBlank() }?.let { add("anilist:$it$episodeSuffix") }
        ids.anidb?.takeIf { it.isNotBlank() }?.let { add("anidb:$it$episodeSuffix") }
        ids.trakt?.takeIf { it.isNotBlank() }?.let { add("trakt:$it$episodeSuffix") }
        ids.simkl?.takeIf { it.isNotBlank() }?.let { add("simkl:$it$episodeSuffix") }
    }

    private fun ContinueWatchingRecord.isEligibleForContinueWatching(): Boolean {
        if (percentComplete() >= NEAR_COMPLETE_PERCENT) return false
        // ContinueWatchingRecord.Source has only LOCAL / SYNTHETIC / REMOTE today; all
        // three are eligible surfaces. If a future enum value (e.g. HISTORY) is added,
        // ContinueWatchingMergerTest.`all current source enum values pass the eligibility
        // backstop` fails and forces the maintainer to revisit this branch.
        return when (source) {
            ContinueWatchingRecord.Source.LOCAL,
            ContinueWatchingRecord.Source.SYNTHETIC,
            ContinueWatchingRecord.Source.REMOTE -> true
        }
    }

    private fun ContinueWatchingRecord.percentComplete(): Float =
        if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs.toFloat()) * 100f

    private fun mergeRecords(
        existing: ContinueWatchingRecord?,
        candidate: ContinueWatchingRecord
    ): ContinueWatchingRecord? {
        if (existing == null) return candidate
        val progressWinner = chooseProgressWinner(existing, candidate) ?: return null
        val aliases = (existing.resumeIdentities + candidate.resumeIdentities)
            .distinctBy { it.lookupKey() }
        val aliasLookupKeys = aliases.map { it.lookupKey() }.toSet()
        val primaryResumeLookupKey = progressWinner.primaryResumeLookupKey
            ?.takeIf { it in aliasLookupKeys }
            ?: progressWinner.resumeIdentities
                .firstOrNull { it.lookupKey() in aliasLookupKeys }
                ?.lookupKey()

        return progressWinner.copy(
            resumeIdentities = aliases,
            primaryResumeLookupKey = primaryResumeLookupKey,
            streamFetchIdentity = chooseStreamIdentity(
                existing.streamFetchIdentity,
                candidate.streamFetchIdentity
            ),
            trackingIdentity = existing.trackingIdentity ?: candidate.trackingIdentity,
            displayIdentity = existing.displayIdentity ?: candidate.displayIdentity,
            identityConfidence = listOf(existing.identityConfidence, candidate.identityConfidence)
                .minBy { it.ordinal },
            identityWarnings = (existing.identityWarnings + candidate.identityWarnings).distinct()
        )
    }

    private fun chooseProgressWinner(
        existing: ContinueWatchingRecord,
        candidate: ContinueWatchingRecord
    ): ContinueWatchingRecord? {
        // Cross-provider conflict: defer to the diff planner so a meaningful position lead
        // never regresses just because the trailing provider has a newer timestamp. Null
        // return means the planner judged neither candidate eligible for CW; propagate it.
        if (existing.provider != candidate.provider) {
            return diffPlanner.pickWinner(listOf(existing, candidate))
        }
        val existingHasProgress = existing.hasMeaningfulProgress()
        val candidateHasProgress = candidate.hasMeaningfulProgress()
        if (!existingHasProgress && candidateHasProgress) return candidate
        if (candidate.updatedAt > existing.updatedAt && candidateHasProgress) return candidate
        return existing
    }

    private fun ContinueWatchingRecord.hasMeaningfulProgress(): Boolean {
        if (positionMs > 0L) return true

        val primaryResumeLookupKey = primaryResumeLookupKey
        val currentResumeIdentities = if (primaryResumeLookupKey == null) {
            resumeIdentities.take(1)
        } else {
            resumeIdentities.filter { it.lookupKey() == primaryResumeLookupKey }
        }
        return currentResumeIdentities.any { (it.progressPercent ?: 0f) > 0f }
    }

    private fun chooseStreamIdentity(
        existing: StreamFetchIdentity?,
        candidate: StreamFetchIdentity?
    ): StreamFetchIdentity? {
        if (existing == null) return candidate
        if (candidate == null) return existing
        return if (candidate.confidence.ordinal < existing.confidence.ordinal) candidate else existing
    }
}
