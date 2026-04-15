package com.nexio.tv.data.repository

internal object AirDateGate {
    /**
     * Returns true if the entry should be treated as aired and shown in the rail.
     *
     * Priority order:
     * 1. If [availabilityInstantMs] > 0, compare it against [nowMs].
     * 2. Else if [firstAiredMs] > 0, compare it against [nowMs].
     * 3. Else if [tmdbAirDate] is a non-blank ISO date (YYYY-MM-DD), parse and compare.
     * 4. If all are unknown, return true (treat as aired — preserves behaviour for
     *    entries with no air-date data).
     */
    fun isAired(availabilityInstantMs: Long?, firstAiredMs: Long, tmdbAirDate: String?, nowMs: Long): Boolean {
        if (availabilityInstantMs != null && availabilityInstantMs > 0L) {
            return availabilityInstantMs <= nowMs
        }
        return isAired(firstAiredMs = firstAiredMs, tmdbAirDate = tmdbAirDate, nowMs = nowMs)
    }

    fun isAired(firstAiredMs: Long, tmdbAirDate: String?, nowMs: Long): Boolean {
        if (firstAiredMs > 0L) {
            return firstAiredMs <= nowMs
        }
        val dateStr = tmdbAirDate?.trim()?.takeIf { it.isNotBlank() }
        if (dateStr != null) {
            val parsedMs = parseDateToEpochMs(dateStr)
            if (parsedMs != null) {
                return parsedMs <= nowMs
            }
        }
        return true
    }

    fun pendingTriggerMs(
        firstAiredMs: Long,
        availabilityInstantMs: Long?,
        tmdbAirDate: String?
    ): Long? {
        if (availabilityInstantMs != null && availabilityInstantMs > 0L) return availabilityInstantMs
        if (firstAiredMs > 0L) return firstAiredMs
        return parseDateToEpochMs(tmdbAirDate?.trim().orEmpty())
    }

    fun <T> hasDuePending(
        entries: List<T>,
        firstAiredMsSelector: (T) -> Long,
        availabilityInstantMsSelector: (T) -> Long? = { null },
        tmdbAirDateSelector: (T) -> String? = { null },
        nowMs: Long
    ): Boolean = entries.any { entry ->
        pendingTriggerMs(
            firstAiredMs = firstAiredMsSelector(entry),
            availabilityInstantMs = availabilityInstantMsSelector(entry),
            tmdbAirDate = tmdbAirDateSelector(entry)
        )?.let { it <= nowMs } == true
    }

    /**
     * Returns the smallest future air-date ms among [entries] whose [isAired] is false,
     * using [firstAiredMsSelector] to extract the air-date ms from each entry.
     * Returns null if all entries are already aired (or there are none).
     */
    fun <T> soonestPendingMs(
        entries: List<T>,
        firstAiredMsSelector: (T) -> Long,
        availabilityInstantMsSelector: (T) -> Long? = { null },
        tmdbAirDateSelector: (T) -> String? = { null },
        nowMs: Long
    ): Long? {
        return entries
            .asSequence()
            .mapNotNull { entry ->
                pendingTriggerMs(
                    firstAiredMs = firstAiredMsSelector(entry),
                    availabilityInstantMs = availabilityInstantMsSelector(entry),
                    tmdbAirDate = tmdbAirDateSelector(entry)
                )
            }
            .filter { it > nowMs }
            .minOrNull()
    }

    /** Parses an ISO date string (YYYY-MM-DD) to epoch milliseconds at midnight UTC. */
    private fun parseDateToEpochMs(dateStr: String): Long? {
        return try {
            val parts = dateStr.split('-')
            if (parts.size != 3) return null
            val year = parts[0].toInt()
            val month = parts[1].toInt() - 1
            val day = parts[2].toInt()

            if (month !in 0..11 || day !in 1..31) return null

            val cal = java.util.GregorianCalendar(java.util.TimeZone.getTimeZone("UTC")).apply {
                isLenient = false
                clear()
                set(year, month, day, 0, 0, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            if (cal.get(java.util.Calendar.YEAR) == year &&
                cal.get(java.util.Calendar.MONTH) == month &&
                cal.get(java.util.Calendar.DAY_OF_MONTH) == day
            ) {
                cal.timeInMillis
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }
}
