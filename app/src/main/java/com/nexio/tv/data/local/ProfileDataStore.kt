package com.nexio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.nexio.tv.domain.model.UserProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.profileDataStore: DataStore<Preferences> by preferencesDataStore(name = "profile_settings")

/**
 * Persistence layer for the profile list.
 *
 * Production: injected via Hilt using the @ApplicationContext delegate DataStore.
 * Tests: use the internal two-arg constructor to supply a test DataStore and a real Gson instance.
 */
@Singleton
class ProfileDataStore @Inject constructor(
    @ApplicationContext context: Context,
    gson: Gson
) : ProfileDataStoreImpl(context.profileDataStore, gson)

/**
 * Internal base that holds all logic. Separated so tests can pass a DataStore<Preferences>
 * directly without needing an Android Context.
 */
open class ProfileDataStoreImpl internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val gson: Gson
) {
    private val profilesJsonKey = stringPreferencesKey("profiles_json")
    private val activeProfileIdKey = intPreferencesKey("active_profile_id")
    private val profileListType = object : TypeToken<List<ProfileJson>>() {}.type

    val profilesList: Flow<List<UserProfile>> = dataStore.data.map { prefs ->
        parseProfiles(prefs[profilesJsonKey])
    }

    val activeProfileId: Flow<Int> = dataStore.data.map { prefs ->
        prefs[activeProfileIdKey] ?: 1
    }

    suspend fun setActiveProfile(id: Int) {
        dataStore.edit { prefs ->
            prefs[activeProfileIdKey] = id
        }
    }

    suspend fun upsertProfile(profile: UserProfile) {
        dataStore.edit { prefs ->
            val current = parseProfiles(prefs[profilesJsonKey]).toMutableList()
            val index = current.indexOfFirst { it.id == profile.id }
            if (index >= 0) {
                current[index] = profile
            } else {
                current.add(profile)
            }
            prefs[profilesJsonKey] = serializeProfiles(current)
        }
    }

    suspend fun deleteProfile(id: Int) {
        if (id == 1) return
        dataStore.edit { prefs ->
            val current = parseProfiles(prefs[profilesJsonKey]).toMutableList()
            current.removeAll { it.id == id }
            prefs[profilesJsonKey] = serializeProfiles(current)
            if ((prefs[activeProfileIdKey] ?: 1) == id) {
                prefs[activeProfileIdKey] = 1
            }
        }
    }

    suspend fun replaceAllProfiles(profiles: List<UserProfile>) {
        dataStore.edit { prefs ->
            val normalizedProfiles = normalizeProfiles(profiles)
            prefs[profilesJsonKey] = serializeProfiles(normalizedProfiles)
            val activeId = prefs[activeProfileIdKey] ?: 1
            if (normalizedProfiles.none { it.id == activeId }) {
                prefs[activeProfileIdKey] = 1
            }
        }
    }

    private fun defaultPrimaryProfile() = UserProfile(
        id = 1,
        name = "Default",
        avatarColorHex = "#1E88E5"
    )

    private fun parseProfiles(json: String?): List<UserProfile> {
        if (json.isNullOrBlank()) return listOf(defaultPrimaryProfile())
        return try {
            val parsed: List<ProfileJson>? = gson.fromJson(json, profileListType)
            normalizeProfiles((parsed ?: return listOf(defaultPrimaryProfile())).map { it.toDomain() })
        } catch (e: Exception) {
            listOf(defaultPrimaryProfile())
        }
    }

    private fun normalizeProfiles(profiles: List<UserProfile>): List<UserProfile> {
        val nonEmpty = profiles.ifEmpty { listOf(defaultPrimaryProfile()) }
        if (nonEmpty.any { it.id == 1 }) return nonEmpty
        return listOf(defaultPrimaryProfile()) + nonEmpty
    }

    private fun serializeProfiles(profiles: List<UserProfile>): String {
        return gson.toJson(profiles.map { ProfileJson.fromDomain(it) }, profileListType)
    }
}

internal data class ProfileJson(
    @SerializedName("id") val id: Int,
    @SerializedName("name") val name: String,
    @SerializedName("avatar_color_hex") val avatarColorHex: String,
    @SerializedName("uses_primary_addons") val usesPrimaryAddons: Boolean = false,
    @SerializedName("avatar_url") val avatarUrl: String? = null,
    @SerializedName("avatar_id") val avatarId: String? = null,
    @SerializedName("pin_enabled") val pinEnabled: Boolean = false
) {
    fun toDomain() = UserProfile(
        id = id,
        name = name,
        avatarColorHex = avatarColorHex,
        usesPrimaryAddons = usesPrimaryAddons,
        avatarUrl = avatarUrl,
        avatarId = avatarId,
        pinEnabled = pinEnabled
    )

    companion object {
        fun fromDomain(p: UserProfile) = ProfileJson(
            id = p.id,
            name = p.name,
            avatarColorHex = p.avatarColorHex,
            usesPrimaryAddons = p.usesPrimaryAddons,
            avatarUrl = p.avatarUrl,
            avatarId = p.avatarId,
            pinEnabled = p.pinEnabled
        )
    }
}
