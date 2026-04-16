package com.nexio.tv.core.locale

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import java.util.Locale
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

data class AppLocaleOption(
    val tag: String?,
    val displayName: String
)

object AppLocaleResolver {
    private const val PREFS_NAME = "app_locale"
    private const val LOCALE_TAG_KEY = "locale_tag"
    private const val ACTIVE_PROFILE_ID_KEY = "active_profile_id"

    val supportedOptions: List<AppLocaleOption> = listOf(
        AppLocaleOption(tag = null, displayName = "System default"),
        AppLocaleOption(tag = "en", displayName = "English"),
        AppLocaleOption(tag = "es", displayName = "Español"),
        AppLocaleOption(tag = "fr", displayName = "Français"),
        AppLocaleOption(tag = "de", displayName = "Deutsch"),
        AppLocaleOption(tag = "nl", displayName = "Nederlands"),
        AppLocaleOption(tag = "zh-CN", displayName = "中文（简体）")
    )

    fun getStoredLocaleTag(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.readStoredLocaleTag()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.takeUnless { it.equals("system", ignoreCase = true) }
    }

    fun setStoredLocaleTag(context: Context, tag: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        if (tag.isNullOrBlank() || tag.equals("system", ignoreCase = true)) {
            editor.remove(localeTagKey(prefs.activeProfileId()))
        } else {
            editor.putString(localeTagKey(prefs.activeProfileId()), tag)
        }
        // Locale writes must complete before activity recreation to avoid reverting to stale values.
        editor.commit()
    }

    fun setActiveProfileId(context: Context, profileId: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(ACTIVE_PROFILE_ID_KEY, profileId.coerceAtLeast(1))
            .commit()
    }

    fun observeStoredLocaleTag(context: Context): Flow<String?> = callbackFlow {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPrefs, changedKey ->
            if (changedKey == ACTIVE_PROFILE_ID_KEY || changedKey == localeTagKey(sharedPrefs.activeProfileId())) {
                trySend(sharedPrefs.readStoredLocaleTag())
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(prefs.readStoredLocaleTag())
        awaitClose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }.distinctUntilChanged()

    private fun SharedPreferences.readStoredLocaleTag(): String? {
        return getString(localeTagKey(activeProfileId()), null)
    }

    private fun SharedPreferences.activeProfileId(): Int {
        return getInt(ACTIVE_PROFILE_ID_KEY, 1).coerceAtLeast(1)
    }

    private fun localeTagKey(profileId: Int): String {
        return if (profileId == 1) LOCALE_TAG_KEY else "${LOCALE_TAG_KEY}_p$profileId"
    }

    fun resolveEffectiveAppLanguageTag(context: Context): String {
        val stored = getStoredLocaleTag(context)
        if (stored != null) {
            return normalizeToSupportedAppTag(stored)
        }
        return normalizeToSupportedAppTag(systemLocaleTag())
    }

    fun resolveTmdbLanguageTag(context: Context): String {
        return when (resolveEffectiveAppLanguageTag(context)) {
            "es" -> "es-ES"
            "fr" -> "fr-FR"
            "de" -> "de-DE"
            "nl" -> "nl-NL"
            "zh-CN" -> "zh-CN"
            else -> "en-US"
        }
    }

    fun resolveTvdbLanguageTag(context: Context): String {
        return when (resolveEffectiveAppLanguageTag(context)) {
            "es" -> "spa"
            "fr" -> "fra"
            "de" -> "deu"
            "nl" -> "nld"
            "zh-CN" -> "zho"
            else -> "eng"
        }
    }

    private fun normalizeToSupportedAppTag(tag: String): String {
        val normalized = tag.trim().replace('_', '-')
        val lower = normalized.lowercase(Locale.US)
        return when {
            lower.startsWith("es") -> "es"
            lower.startsWith("fr") -> "fr"
            lower.startsWith("de") -> "de"
            lower.startsWith("nl") -> "nl"
            lower.startsWith("zh") -> "zh-CN"
            else -> "en"
        }
    }

    private fun systemLocaleTag(): String {
        val locales = Resources.getSystem().configuration.locales
        return locales.takeIf { !it.isEmpty }?.get(0)?.toLanguageTag()
            ?: Locale.getDefault().toLanguageTag()
    }
}
