package com.nexio.tv.notices

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.remoteNoticeDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "remote_notice_settings"
)

@Singleton
class RemoteNoticePreferences internal constructor(
    private val dataStore: DataStore<Preferences>
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context.remoteNoticeDataStore)

    private val baselineAtMsKey = longPreferencesKey("notice_baseline_at_ms")
    private val seenNoticeIdsKey = stringSetPreferencesKey("seen_notice_ids")
    private val lastCheckAtMsKey = longPreferencesKey("last_check_at_ms")

    val noticeBaselineAt: Flow<Instant?> = dataStore.data.map { prefs ->
        prefs[baselineAtMsKey]?.let(Instant::ofEpochMilli)
    }

    val seenNoticeIds: Flow<Set<String>> = dataStore.data.map { prefs ->
        prefs[seenNoticeIdsKey].orEmpty()
    }

    val lastCheckAtMs: Flow<Long> = dataStore.data.map { prefs ->
        prefs[lastCheckAtMsKey] ?: 0L
    }

    suspend fun setNoticeBaselineAtIfAbsent(value: Instant): Instant {
        var resultingValue = value
        dataStore.edit { prefs ->
            val existingValue = prefs[baselineAtMsKey]?.let(Instant::ofEpochMilli)
            if (existingValue == null) {
                prefs[baselineAtMsKey] = value.toEpochMilli()
                resultingValue = value
            } else {
                resultingValue = existingValue
            }
        }
        return resultingValue
    }

    suspend fun markSeen(id: String) {
        val cleanId = id.trim()
        if (cleanId.isBlank()) return
        dataStore.edit { prefs ->
            prefs[seenNoticeIdsKey] = prefs[seenNoticeIdsKey].orEmpty() + cleanId
        }
    }

    suspend fun setLastCheckAtMs(value: Long) {
        dataStore.edit { prefs ->
            prefs[lastCheckAtMsKey] = value
        }
    }
}
