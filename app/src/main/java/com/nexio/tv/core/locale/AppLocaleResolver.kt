package com.nexio.tv.core.locale

import android.content.Context
import java.util.Locale

data class AppLocaleOption(
    val tag: String?,
    val displayName: String
)

object AppLocaleResolver {
    private const val PREFS_NAME = "app_locale"
    private const val LOCALE_TAG_KEY = "locale_tag"

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
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(LOCALE_TAG_KEY, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    fun setStoredLocaleTag(context: Context, tag: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        if (tag.isNullOrBlank()) {
            editor.remove(LOCALE_TAG_KEY)
        } else {
            editor.putString(LOCALE_TAG_KEY, tag)
        }
        editor.apply()
    }

    fun resolveEffectiveAppLanguageTag(context: Context): String {
        val stored = getStoredLocaleTag(context)
        if (stored != null) {
            return normalizeToSupportedAppTag(stored)
        }
        return normalizeToSupportedAppTag(Locale.getDefault().toLanguageTag())
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
}
