package com.nexio.tv.core.profile

import android.content.Context
import android.util.Log
import com.nexio.tv.core.auth.stockDefaultProfile
import com.nexio.tv.core.integration.ActiveProfileSession
import com.nexio.tv.core.integration.ProfileBoundaryEnforcer
import com.nexio.tv.core.integration.ProfileBoundaryException
import com.nexio.tv.core.integration.ProfileBoundaryViolation
import com.nexio.tv.core.locale.AppLocaleResolver
import com.nexio.tv.core.playback.PlaybackSessionRegistry
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
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ProfileManager"

@Singleton
class ProfileManager(
    private val dataStore: ProfileDataStoreImpl,
    private val factory: ProfileDataStoreFactory,
    private val context: Context,
    scope: CoroutineScope,
    private val postgrest: Postgrest? = null,
    private val playbackSessionRegistry: PlaybackSessionRegistry = PlaybackSessionRegistry()
) {
    /**
     * Primary Hilt constructor. Creates its own SupervisorJob+IO scope for production use.
     */
    @Inject
    constructor(
        profileDataStore: ProfileDataStore,
        factory: ProfileDataStoreFactory,
        @ApplicationContext context: Context,
        postgrest: Postgrest,
        playbackSessionRegistry: PlaybackSessionRegistry
    ) : this(
        dataStore = profileDataStore,
        factory = factory,
        context = context,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        postgrest = postgrest,
        playbackSessionRegistry = playbackSessionRegistry
    )

    private val _profileSwitched = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val profileSwitched: SharedFlow<Int> = _profileSwitched.asSharedFlow()

    private val _activeProfileId = MutableStateFlow(1)
    val activeProfileId: StateFlow<Int> = _activeProfileId.asStateFlow()
    private var nextSessionOrdinal = 1L
    private val _activeProfileSession = MutableStateFlow(newProfileSession(profileId = 1))
    val activeProfileSession: StateFlow<ActiveProfileSession> = _activeProfileSession.asStateFlow()

    private val deferralPolicy = ProfileSwitchDeferralPolicy(initialProfileId = _activeProfileId.value)

    val profiles: StateFlow<List<UserProfile>> = dataStore.profilesList
        .stateIn(
            scope, SharingStarted.Eagerly,
            listOf(UserProfile(id = 1, name = "Default", avatarColorHex = "#1E88E5"))
        )

    init {
        scope.launch {
            dataStore.activeProfileId.collect { id ->
                val previousId = _activeProfileId.value
                val applied = deferralPolicy.onIncomingSwitch(
                    targetProfileId = id,
                    hasActivePlayback = playbackSessionRegistry.activeOwner() != null
                )
                if (applied) {
                    applyProfileChange(previousId, id)
                }
                // else: deferred. Drained when ownerState becomes null below.
            }
        }
        scope.launch {
            playbackSessionRegistry.ownerState.collect { owner ->
                if (owner == null) {
                    val drainedTo = deferralPolicy.onPlaybackIdle()
                    if (drainedTo != null) {
                        val previousId = _activeProfileId.value
                        applyProfileChange(previousId, drainedTo)
                    }
                }
            }
        }
    }

    private suspend fun applyProfileChange(previousId: Int, newId: Int) {
        _activeProfileId.value = newId
        if (previousId != newId) {
            _activeProfileSession.value = newProfileSession(newId)
        }
        AppLocaleResolver.setActiveProfileId(context, newId)
        if (previousId != newId) {
            _profileSwitched.emit(newId)
        }
    }

    val activeProfile: UserProfile?
        get() = profiles.value.find { it.id == activeProfileId.value }

    val isPrimaryProfileActive: Boolean
        get() = activeProfileId.value == 1

    // F2-F-08: dual-write desync window — direct `_activeProfileId.value` update and the
    // DataStore write below are not atomic. `deferralPolicy.activeProfileId` resyncs on the
    // next DataStore collect (within ~1 ms typically under normal IO scheduling). This is
    // self-correcting and intentional; documented here for clarity.
    suspend fun setActiveProfile(id: Int) {
        val current = dataStore.profilesList.first()
        if (current.none { it.id == id }) return
        if (_activeProfileId.value == id) return
        val owner = playbackSessionRegistry.activeOwner()
        // F-F-02: route through enforcer so a profile.boundary_check trace event fires
        // (with verdict=FAIL, violation=PROFILE_SWITCH_BLOCKED_BY_ACTIVE_PLAYBACK) before
        // throwing. Throws ProfileBoundaryException itself when rejected.
        ProfileBoundaryEnforcer.assertCanSwitchProfile(
            activeProfileId = _activeProfileId.value,
            targetProfileId = id,
            hasActivePlaybackOwner = owner != null
        )
        AppLocaleResolver.setActiveProfileId(context, id)
        _activeProfileId.value = id
        _activeProfileSession.value = newProfileSession(id)
        dataStore.setActiveProfile(id)
        _profileSwitched.emit(id)
    }

    private fun newProfileSession(profileId: Int): ActiveProfileSession =
        ActiveProfileSession(
            profileId = profileId,
            sessionId = "profile:$profileId:${UUID.randomUUID()}",
            sessionOrdinal = nextSessionOrdinal++,
            startedAtMs = System.currentTimeMillis().coerceAtLeast(1L)
        )

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

    suspend fun deleteProfile(id: Int, syncRemoteDelete: Boolean = false): Boolean {
        if (id == 1) return false
        // Read latest from DataStore directly — StateFlow may lag behind DataStore writes
        val current = dataStore.profilesList.first()
        if (current.none { it.id == id }) return false
        deleteProfileDataAsync(id, syncRemoteDelete)
        dataStore.deleteProfile(id)
        return true
    }

    suspend fun resetToSingleDefaultProfile() {
        val current = dataStore.profilesList.first()
        current
            .map { it.id }
            .filter { it != 1 }
            .forEach { profileId ->
                deleteProfileDataAsync(profileId, syncRemoteDelete = false)
            }

        dataStore.replaceAllProfiles(listOf(stockDefaultProfile()))
        setActiveProfile(1)
    }

    suspend fun updateProfile(profile: UserProfile): Boolean {
        // Read latest from DataStore directly — StateFlow may lag behind DataStore writes
        val current = dataStore.profilesList.first()
        if (current.none { it.id == profile.id }) return false
        dataStore.upsertProfile(profile)
        return true
    }

    private suspend fun deleteProfileDataAsync(
        profileId: Int,
        syncRemoteDelete: Boolean = true
    ) {
        if (profileId == 1) return

        if (syncRemoteDelete) {
            deleteProfileRemote(profileId)
        }
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
