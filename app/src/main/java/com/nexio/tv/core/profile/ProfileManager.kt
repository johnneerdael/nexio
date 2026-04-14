package com.nexio.tv.core.profile

import android.content.Context
import com.nexio.tv.data.local.ProfileDataStore
import com.nexio.tv.data.local.ProfileDataStoreFactory
import com.nexio.tv.data.local.ProfileDataStoreImpl
import com.nexio.tv.domain.model.UserProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileManager(
    private val dataStore: ProfileDataStoreImpl,
    private val factory: ProfileDataStoreFactory,
    private val context: Context,
    scope: CoroutineScope
) {
    /**
     * Primary Hilt constructor. Creates its own SupervisorJob+IO scope for production use.
     */
    @Inject
    constructor(
        profileDataStore: ProfileDataStore,
        factory: ProfileDataStoreFactory,
        @ApplicationContext context: Context
    ) : this(
        dataStore = profileDataStore,
        factory = factory,
        context = context,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    )

    private val _profileSwitched = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val profileSwitched: SharedFlow<Unit> = _profileSwitched.asSharedFlow()

    val activeProfileId: StateFlow<Int> = dataStore.activeProfileId
        .stateIn(scope, SharingStarted.Eagerly, 1)

    val profiles: StateFlow<List<UserProfile>> = dataStore.profilesList
        .stateIn(
            scope, SharingStarted.Eagerly,
            listOf(UserProfile(id = 1, name = "Default", avatarColorHex = "#1E88E5"))
        )

    val activeProfile: UserProfile?
        get() = profiles.value.find { it.id == activeProfileId.value }

    val isPrimaryProfileActive: Boolean
        get() = activeProfileId.value == 1

    suspend fun setActiveProfile(id: Int) {
        // Read latest from DataStore directly to avoid StateFlow lag
        val current = dataStore.profilesList.first()
        if (current.any { it.id == id }) {
            dataStore.setActiveProfile(id)
            _profileSwitched.emit(Unit)
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
    }
}
