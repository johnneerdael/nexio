package com.nexio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nexio.tv.domain.model.AppFont
import com.nexio.tv.domain.model.AppTheme
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.themeDataStore: DataStore<Preferences> by preferencesDataStore(name = "theme_settings")

@Singleton
class ThemeDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.themeDataStore

    private val themeKey = stringPreferencesKey("selected_theme")
    private val fontKey = stringPreferencesKey("selected_font")

    val selectedTheme: Flow<AppTheme> = dataStore.data.map { prefs ->
        val themeName = prefs[themeKey] ?: AppTheme.WHITE.name
        try {
            AppTheme.valueOf(themeName)
        } catch (e: IllegalArgumentException) {
            AppTheme.WHITE
        }
    }

    val selectedFont: Flow<AppFont> = dataStore.data.map { prefs ->
        val fontName = prefs[fontKey] ?: AppFont.INTER.name
        try {
            AppFont.valueOf(fontName)
        } catch (e: IllegalArgumentException) {
            AppFont.INTER
        }
    }

    suspend fun setTheme(theme: AppTheme) {
        dataStore.edit { prefs ->
            prefs[themeKey] = theme.name
        }
    }

    suspend fun setFont(font: AppFont) {
        dataStore.edit { prefs ->
            prefs[fontKey] = font.name
        }
    }
}
