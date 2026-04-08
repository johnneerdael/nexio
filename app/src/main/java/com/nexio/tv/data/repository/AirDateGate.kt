package com.nexio.tv.data.repository

internal object AirDateGate {
    /**
     * Returns true if the entry should be treated as aired and shown in the rail.
     *
     * Priority order:
     * 1. If [firstAiredMs] > 0, compare it against [nowMs].
     * 2. Else if [tmdbAirDate] is a non-blank ISO date (YYYY-MM-DD), parse and compare.
     * 3. If both are unknown, return true (treat as aired — preserves behaviour for
     *    entries with no air-date data).
     */
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

    /**
     * Returns the smallest future air-date ms among [entries] whose [isAired] is false,
     * using [firstAiredMsSelector] to extract the air-date ms from each entry.
     * Returns null if all entries are already aired (or there are none).
     */
    fun <T> soonestPendingMs(
        entries: List<T>,
        firstAiredMsSelector: (T) -> Long,
        tmdbAirDateSelector: (T) -> String? = { null },
        nowMs: Long
    ): Long? {
        return entries
            .asSequence()
            .mapNotNull { entry ->
                val ms = firstAiredMsSelector(entry)
                if (ms > 0L) ms else parseDateToEpochMs(tmdbAirDateSelector(entry)?.trim() ?: "")
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
