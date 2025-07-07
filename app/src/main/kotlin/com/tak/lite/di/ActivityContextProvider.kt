package com.tak.lite.di

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ActivityContextProvider @Inject constructor() {
    private val _activityContext = MutableStateFlow<Context?>(null)
    val activityContext: StateFlow<Context?> = _activityContext.asStateFlow()
    
    fun setActivityContext(context: Context?) {
        _activityContext.value = context
    }
    
    fun getActivityContext(): Context? {
        return _activityContext.value
    }
} 