package com.tak.lite.util

import android.content.Context
import android.content.res.Configuration
import android.os.Build
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
                return values().find { it.code == code } ?: SYSTEM
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
        prefs.edit().putString(KEY_LANGUAGE, language.code).apply()
    }
    
    /**
     * Apply the current locale to the context
     */
    fun applyLocale(context: Context): Context {
        val language = getLanguage(context)
        return if (language == Language.SYSTEM) {
            // Use system locale
            context
        } else {
            // Apply specific locale
            val locale = Locale(language.code)
            Locale.setDefault(locale)
            
            val config = Configuration(context.resources.configuration)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                config.setLocale(locale)
            } else {
                @Suppress("DEPRECATION")
                config.locale = locale
            }
            
            context.createConfigurationContext(config)
        }
    }
    
    /**
     * Apply the current locale to the resources
     */
    fun applyLocaleToResources(context: Context) {
        val language = getLanguage(context)
        if (language != Language.SYSTEM) {
            val locale = Locale(language.code)
            Locale.setDefault(locale)
            
            val config = Configuration(context.resources.configuration)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                config.setLocale(locale)
            } else {
                @Suppress("DEPRECATION")
                config.locale = locale
            }
            
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
     * Apply locale to all activities in the app
     * This should be called when the app is in the foreground
     */
    fun applyLocaleToAllActivities(context: Context) {
        applyLocaleToResources(context)
        // Note: This would require additional implementation to track all activities
        // For now, we rely on individual activity recreation
    }
    
    /**
     * Get the display name for a language in the current locale
     */
    fun getLanguageDisplayName(context: Context, language: Language): String {
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
        return Language.values().map { language ->
            language to getLanguageDisplayName(context, language)
        }
    }
}
