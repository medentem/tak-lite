package com.tak.lite.di

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides access to the current activity context for UI-related operations.
 * 
 * IMPORTANT: This should ONLY be used for:
 * - UI updates and dialogs
 * - Short-lived operations tied to activity lifecycle
 * - Activity-scoped components
 * 
 * DO NOT use this for:
 * - Background operations (use @ApplicationContext instead)
 * - Long-lived broadcast receivers (use @ApplicationContext instead)
 * - Service bindings (use @ApplicationContext instead)
 * - Protocol implementations (use @ApplicationContext instead)
 * 
 * The activity context becomes null when activities are destroyed, which can cause
 * issues for background operations that need to continue running.
 */
@Singleton
class ActivityContextProvider @Inject constructor() {
    private val _activityContext = MutableStateFlow<Context?>(null)
    val activityContext: StateFlow<Context?> = _activityContext.asStateFlow()
    
    /**
     * Set the current activity context. Called by activities in onCreate().
     * 
     * @param context The activity context, or null when activity is destroyed
     */
    fun setActivityContext(context: Context?) {
        _activityContext.value = context
    }
    
    /**
     * Get the current activity context. May return null if no activity is active.
     * 
     * @return The current activity context, or null if no activity is available
     */
    fun getActivityContext(): Context? {
        return _activityContext.value
    }
} 