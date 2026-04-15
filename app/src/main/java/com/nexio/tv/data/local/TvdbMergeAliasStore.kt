package com.nexio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.tvdbMergeAliasDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "tvdb_merge_aliases"
)

data class TvdbMergeAlias(
    val fromEntityType: String,
    val fromId: Int,
    val toEntityType: String,
    val toId: Int,
    val updatedAtMs: Long
)

@Singleton
class TvdbMergeAliasStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.tvdbMergeAliasDataStore

    /**
     * Records a merge alias mapping a source entity to its merge target.
     *
     * Rejects aliases with blank entity types, non-positive IDs, or self-maps
     * (where source equals target).
     */
    suspend fun recordAlias(fromEntityType: String, fromId: Int, toEntityType: String, toId: Int) {
        if (fromEntityType.isBlank() || toEntityType.isBlank()) return
        if (fromId <= 0 || toId <= 0) return
        if (fromEntityType == toEntityType && fromId == toId) return

        val sourceKey = aliasKey(fromEntityType, fromId)
        val value = "$toEntityType:$toId:${System.currentTimeMillis()}"
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey(sourceKey)] = value
        }
    }

    /**
     * Resolves a merge alias for the given entity type and ID.
     *
     * This is the read-path contract: metadata readers must call this before
     * TVDB cache lookup or API fetch so requests for an old duplicate ID use
     * the merge target when TVDB provides those fields.
     *
     * Returns null if no alias exists for the given source.
     */
    suspend fun resolveAlias(entityType: String, id: Int): TvdbMergeAlias? {
        if (entityType.isBlank() || id <= 0) return null
        val sourceKey = aliasKey(entityType, id)
        val raw = dataStore.data.first()[stringPreferencesKey(sourceKey)]
            ?: return null
        return parseAlias(entityType, id, raw)
    }

    /**
     * Removes a merge alias for the given source entity.
     *
     * Called for delete events without merge target fields to clean up
     * stale aliases.
     */
    suspend fun removeAlias(entityType: String, id: Int) {
        if (entityType.isBlank() || id <= 0) return
        val sourceKey = aliasKey(entityType, id)
        dataStore.edit { prefs ->
            prefs.remove(stringPreferencesKey(sourceKey))
        }
    }

    private fun aliasKey(entityType: String, id: Int): String {
        return "${entityType.trim().lowercase()}:$id"
    }

    private fun parseAlias(fromEntityType: String, fromId: Int, raw: String): TvdbMergeAlias? {
        val parts = raw.split(":", limit = 3)
        if (parts.size < 3) return null
        val toEntityType = parts[0].takeIf { it.isNotBlank() } ?: return null
        val toId = parts[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val updatedAtMs = parts[2].toLongOrNull() ?: return null
        return TvdbMergeAlias(
            fromEntityType = fromEntityType,
            fromId = fromId,
            toEntityType = toEntityType,
            toId = toId,
            updatedAtMs = updatedAtMs
        )
    }
}
