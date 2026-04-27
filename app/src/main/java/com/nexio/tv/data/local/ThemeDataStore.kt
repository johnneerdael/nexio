package com.nexio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.domain.model.AppFont
import com.nexio.tv.domain.model.AppTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class ThemeDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "theme_settings"
    }

    private val themeKey = stringPreferencesKey("selected_theme")
    private val fontKey = stringPreferencesKey("selected_font")

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    val selectedTheme: Flow<AppTheme> = profileManager.activeProfileId.flatMapLatest { pid ->
        store(pid).data.map { prefs ->
            migrateThemePreference(prefs[themeKey])
        }
    }

    val selectedFont: Flow<AppFont> = profileManager.activeProfileId.flatMapLatest { pid ->
        store(pid).data.map { prefs ->
            val fontName = prefs[fontKey] ?: AppFont.INTER.name
            runCatching { AppFont.valueOf(fontName) }.getOrDefault(AppFont.INTER)
        }
    }

    suspend fun setTheme(theme: AppTheme) {
        store().edit { prefs -> prefs[themeKey] = theme.name }
    }

    suspend fun setFont(font: AppFont) {
        store().edit { prefs -> prefs[fontKey] = font.name }
    }
}
