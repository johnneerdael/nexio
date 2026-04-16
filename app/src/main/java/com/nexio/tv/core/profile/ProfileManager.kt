package com.nexio.tv.core.profile

import android.content.Context
import android.util.Log
import com.nexio.tv.core.locale.AppLocaleResolver
import com.nexio.tv.core.sync.profilePrefsName
import com.nexio.tv.data.local.ProfileDataStore
import com.nexio.tv.data.local.ProfileDataStoreFactory
import com.nexio.tv.data.local.ProfileDataStoreImpl
import com.nexio.tv.domain.model.UserProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ProfileManager"

@Singleton
class ProfileManager(
    private val dataStore: ProfileDataStoreImpl,
    private val factory: ProfileDataStoreFactory,
    private val context: Context,
    scope: CoroutineScope,
    private val postgrest: Postgrest? = null
) {
    /**
     * Primary Hilt constructor. Creates its own SupervisorJob+IO scope for production use.
     */
    @Inject
    constructor(
        profileDataStore: ProfileDataStore,
        factory: ProfileDataStoreFactory,
        @ApplicationContext context: Context,
        postgrest: Postgrest
    ) : this(
        dataStore = profileDataStore,
        factory = factory,
        context = context,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        postgrest = postgrest
    )

    private val _profileSwitched = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val profileSwitched: SharedFlow<Int> = _profileSwitched.asSharedFlow()

    private val _activeProfileId = MutableStateFlow(1)
    val activeProfileId: StateFlow<Int> = _activeProfileId.asStateFlow()

    val profiles: StateFlow<List<UserProfile>> = dataStore.profilesList
        .stateIn(
            scope, SharingStarted.Eagerly,
            listOf(UserProfile(id = 1, name = "Default", avatarColorHex = "#1E88E5"))
        )

    init {
        scope.launch {
            dataStore.activeProfileId.collect { id ->
                val previousId = _activeProfileId.value
                _activeProfileId.value = id
                AppLocaleResolver.setActiveProfileId(context, id)
                if (previousId != id) {
                    _profileSwitched.emit(id)
                }
            }
        }
    }

    val activeProfile: UserProfile?
        get() = profiles.value.find { it.id == activeProfileId.value }

    val isPrimaryProfileActive: Boolean
        get() = activeProfileId.value == 1

    suspend fun setActiveProfile(id: Int) {
        // Read latest from DataStore directly to avoid StateFlow lag
        val current = dataStore.profilesList.first()
        if (current.any { it.id == id }) {
            if (_activeProfileId.value == id) return
            AppLocaleResolver.setActiveProfileId(context, id)
            _activeProfileId.value = id
            dataStore.setActiveProfile(id)
            _profileSwitched.emit(id)
        }
    }

    suspend fun createProfile(
        name: String,
        avatarColorHex: String,
        usesPrimaryAddons: Boolean = false,
        avatarId: String? = null
    ): Boolean {
        // Read latest from DataStore directly — StateFlow may lag behind DataStore writes
        val current = dataStore.profilesList.first()
        if (current.size >= 4) return false

        val usedIds = current.map { it.id }.toSet()
        val nextId = (2..4).firstOrNull { it !in usedIds } ?: return false

        val profile = UserProfile(
            id = nextId,
            name = name.trim().ifEmpty { "Profile $nextId" },
            avatarColorHex = avatarColorHex,
            usesPrimaryAddons = usesPrimaryAddons,
            avatarId = avatarId
        )
        factory.markProfileCreated(nextId)
        dataStore.upsertProfile(profile)
        return true
    }

    suspend fun deleteProfile(id: Int): Boolean {
        if (id == 1) return false
        // Read latest from DataStore directly — StateFlow may lag behind DataStore writes
        val current = dataStore.profilesList.first()
        if (current.none { it.id == id }) return false
        deleteProfileDataAsync(id)
        dataStore.deleteProfile(id)
        return true
    }

    suspend fun updateProfile(profile: UserProfile): Boolean {
        // Read latest from DataStore directly — StateFlow may lag behind DataStore writes
        val current = dataStore.profilesList.first()
        if (current.none { it.id == profile.id }) return false
        dataStore.upsertProfile(profile)
        return true
    }

    private suspend fun deleteProfileDataAsync(profileId: Int) {
        if (profileId == 1) return

        deleteProfileRemote(profileId)
        factory.clearProfile(profileId)

        val suffixWithExtension = "_p${profileId}.preferences_pb"
        val dataStoreDir = File(context.filesDir, "datastore")
        if (dataStoreDir.exists()) {
            dataStoreDir.listFiles()?.forEach { file ->
                if (file.name.endsWith(suffixWithExtension)) {
                    file.delete()
                }
            }
        }

        deleteSharedPreferencesForProfile(profileId)
    }

    private fun deleteSharedPreferencesForProfile(profileId: Int) {
        if (profileId == 1) return
        val spStoreBaseNames = listOf(
            "trakt_library_snapshot",
            "continue_watching_snapshot",
            "simkl_library_snapshot",
            "simkl_discovery_snapshot_v2",
            "simkl_progress_sync_state",
            "trakt_mutation_outbox",
            "trakt_discovery_snapshot"
        )
        spStoreBaseNames.forEach { baseName ->
            val prefsName = profilePrefsName(baseName, profileId)
            context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                .edit().clear().commit()
            val file = File(context.applicationInfo.dataDir, "shared_prefs/${prefsName}.xml")
            if (file.exists()) file.delete()
        }
    }

    private suspend fun deleteProfileRemote(profileId: Int) {
        val pg = postgrest ?: return
        try {
            pg.rpc(
                "sync_delete_profile",
                buildJsonObject {
                    put("p_profile_id", profileId)
                }
            )
            Log.d(TAG, "Remote profile $profileId deleted")
        } catch (e: Exception) {
            Log.w(TAG, "Remote deletion failed for profile $profileId, scheduling retry", e)
            val prefs = context.getSharedPreferences("profile_cleanup_state", Context.MODE_PRIVATE)
            val pending = prefs.getStringSet("pending_remote_cleanup", emptySet())
                ?.toMutableSet()
                ?: mutableSetOf()
            pending.add(profileId.toString())
            if (pending.size > 4) pending.retainAll(pending.take(4).toSet())
            prefs.edit().putStringSet("pending_remote_cleanup", pending).apply()
        }
    }
}
