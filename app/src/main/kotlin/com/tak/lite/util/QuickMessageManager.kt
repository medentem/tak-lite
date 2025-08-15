package com.tak.lite.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.tak.lite.data.model.QuickMessage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Utility class for managing quick messages
 */
object QuickMessageManager {
    private const val TAG = "QuickMessageManager"
    private const val PREFS_NAME = "quick_messages_prefs"
    private const val KEY_QUICK_MESSAGE_PREFIX = "quick_message_"
    private const val KEY_INITIALIZED = "quick_messages_initialized"

    /**
     * Get all quick messages, initializing with defaults if needed
     */
    fun getQuickMessages(context: Context): List<QuickMessage> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        // Check if we need to initialize with defaults
        if (!prefs.getBoolean(KEY_INITIALIZED, false)) {
            initializeWithDefaults(context, prefs)
        }
        
        return (0..5).mapNotNull { id ->
            val jsonString = prefs.getString("$KEY_QUICK_MESSAGE_PREFIX$id", null)
            if (jsonString != null) {
                try {
                    Json.decodeFromString<QuickMessage>(jsonString)
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing quick message $id: ${e.message}")
                    null
                }
            } else {
                null
            }
        }.sortedBy { it.id }
    }

    /**
     * Save a quick message
     */
    fun saveQuickMessage(context: Context, quickMessage: QuickMessage) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        try {
            val jsonString = Json.encodeToString(quickMessage)
            prefs.edit()
                .putString("$KEY_QUICK_MESSAGE_PREFIX${quickMessage.id}", jsonString)
                .apply()
            Log.d(TAG, "Saved quick message ${quickMessage.id}: ${quickMessage.text}")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving quick message ${quickMessage.id}: ${e.message}")
        }
    }

    /**
     * Save all quick messages at once
     */
    fun saveQuickMessages(context: Context, messages: List<QuickMessage>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        
        messages.forEach { message ->
            try {
                val jsonString = Json.encodeToString(message)
                editor.putString("$KEY_QUICK_MESSAGE_PREFIX${message.id}", jsonString)
            } catch (e: Exception) {
                Log.e(TAG, "Error saving quick message ${message.id}: ${e.message}")
            }
        }
        
        editor.putBoolean(KEY_INITIALIZED, true)
        editor.apply()
        Log.d(TAG, "Saved ${messages.size} quick messages")
    }

    /**
     * Initialize quick messages with default values
     */
    private fun initializeWithDefaults(context: Context, prefs: SharedPreferences) {
        Log.d(TAG, "Initializing quick messages with defaults")
        saveQuickMessages(context, QuickMessage.DEFAULT_MESSAGES)
    }

    /**
     * Reset quick messages to defaults
     */
    fun resetToDefaults(context: Context) {
        Log.d(TAG, "Resetting quick messages to defaults")
        saveQuickMessages(context, QuickMessage.DEFAULT_MESSAGES)
    }
}
