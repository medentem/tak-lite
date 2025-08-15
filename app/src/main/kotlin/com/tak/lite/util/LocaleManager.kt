package com.tak.lite.util

import android.content.Context
import android.content.res.Configuration
import com.tak.lite.R
import java.util.Locale

/**
 * Utility class to manage app locale settings.
 * Handles locale persistence and application.
 */
object LocaleManager {
    private const val PREF_NAME = "locale_prefs"
    private const val KEY_LANGUAGE = "language"
    
    /**
     * Available languages supported by the app
     */
    enum class Language(val code: String, val displayNameResId: Int) {
        SYSTEM("system", 0), // Special case for system language
        ENGLISH("en", 0),
        GERMAN("de", 0),
        SPANISH("es", 0),
        FRENCH("fr", 0),
        ITALIAN("it", 0),
        PORTUGUESE("pt", 0),
        ROMANSH("rm", 0);
        
        companion object {
            fun fromCode(code: String): Language {
                return entries.find { it.code == code } ?: SYSTEM
            }
        }
    }
    
    /**
     * Get the current language setting from preferences
     */
    fun getLanguage(context: Context): Language {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val languageCode = prefs.getString(KEY_LANGUAGE, "system") ?: "system"
        return Language.fromCode(languageCode)
    }
    
    /**
     * Set the language preference
     */
    fun setLanguage(context: Context, language: Language) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        if (language == Language.SYSTEM) {
            // Clear the preference to use system default
            prefs.edit().remove(KEY_LANGUAGE).apply()
        } else {
            prefs.edit().putString(KEY_LANGUAGE, language.code).apply()
        }
    }
    
    /**
     * Apply the current locale to the resources
     */
    fun applyLocaleToResources(context: Context) {
        val language = getLanguage(context)
        if (language == Language.SYSTEM) {
            // For system language, we need to reset the configuration
            // We'll use the default locale and let the system handle it
            val config = Configuration(context.resources.configuration)
            // Use the default locale for system language
            val defaultLocale = Locale.getDefault()
            config.setLocale(defaultLocale)
            context.resources.updateConfiguration(config, context.resources.displayMetrics)
        } else {
            // Apply specific locale
            val locale = Locale(language.code)
            Locale.setDefault(locale)
            
            val config = Configuration(context.resources.configuration)
            config.setLocale(locale)

            context.resources.updateConfiguration(config, context.resources.displayMetrics)
        }
    }

    /**
     * Apply locale and recreate activity for immediate language change
     */
    fun applyLocaleAndRecreate(activity: android.app.Activity) {
        applyLocaleToResources(activity)
        activity.recreate()
    }
    
    /**
     * Get the display name for a language in the current locale
     */
    private fun getLanguageDisplayName(context: Context, language: Language): String {
        return when (language) {
            Language.SYSTEM -> context.getString(R.string.use_system_language)
            Language.ENGLISH -> context.getString(R.string.english)
            Language.GERMAN -> context.getString(R.string.german)
            Language.SPANISH -> context.getString(R.string.spanish)
            Language.FRENCH -> context.getString(R.string.french)
            Language.ITALIAN -> context.getString(R.string.italian)
            Language.PORTUGUESE -> context.getString(R.string.portuguese)
            Language.ROMANSH -> context.getString(R.string.romansh)
        }
    }
    
    /**
     * Get all available languages with their display names
     */
    fun getAvailableLanguages(context: Context): List<Pair<Language, String>> {
        return Language.entries.map { language ->
            language to getLanguageDisplayName(context, language)
        }
    }
}
