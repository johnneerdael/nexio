package com.nexio.tv.data.repository

object ContinueWatchingMerger {
    fun merge(records: List<ContinueWatchingRecord>): List<ContinueWatchingRecord> {
        val byKey = linkedMapOf<String, ContinueWatchingRecord>()
        records.sortedByDescending { it.updatedAt }.forEach { record ->
            val key = record.identityKey()
            val existing = byKey[key]
            byKey[key] = if (existing == null) record else mergeRecords(existing, record)
        }
        return byKey.values.sortedByDescending { it.updatedAt }
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
