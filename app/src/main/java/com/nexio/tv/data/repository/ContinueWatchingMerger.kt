package com.nexio.tv.data.repository

object ContinueWatchingMerger {
    fun merge(records: List<ContinueWatchingRecord>): List<ContinueWatchingRecord> {
        if (records.isEmpty()) return emptyList()
        val sorted = records.sortedByDescending { it.updatedAt }

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
            val keys = record.idBundle.toBucketKeys()
            if (keys.isEmpty()) {
                // Back-compat: records without idBundle fall back to legacy identityKey().
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

        val groups = LinkedHashMap<Int, ContinueWatchingRecord>()
        sorted.indices.forEach { idx ->
            val root = find(idx)
            val cur = groups[root]
            groups[root] = if (cur == null) sorted[idx] else mergeRecords(cur, sorted[idx])
        }
        return groups.values.sortedByDescending { it.updatedAt }
    }

    private fun mergeRecords(
        existing: ContinueWatchingRecord,
        candidate: ContinueWatchingRecord
    ): ContinueWatchingRecord {
        val progressWinner = chooseProgressWinner(existing, candidate)
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
    ): ContinueWatchingRecord {
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
