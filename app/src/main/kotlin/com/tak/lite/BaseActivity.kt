package com.tak.lite

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.tak.lite.di.ActivityContextProvider
import com.tak.lite.repository.MessageRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Base activity that provides common functionality for all activities.
 * 
 * This activity manages the activity context for UI operations only.
 * Background operations should use @ApplicationContext instead.
 */
@AndroidEntryPoint
abstract class BaseActivity : AppCompatActivity() {
    @Inject
    lateinit var messageRepository: MessageRepository
    
    @Inject
    lateinit var activityContextProvider: ActivityContextProvider

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Set the activity context for UI operations only
        // Background operations (protocols, services) should use @ApplicationContext
        activityContextProvider.setActivityContext(this)
        
        // Enable edge-to-edge display for all activities
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }
    
    override fun setContentView(layoutResID: Int) {
        super.setContentView(layoutResID)
        applyTopInsetToRoot()
    }
    
    override fun setContentView(view: View?) {
        super.setContentView(view)
        applyTopInsetToRoot()
    }
    
    private fun applyTopInsetToRoot() {
        val rootView = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            
            // Apply system bar insets to top, left, and right
            // Apply IME (keyboard) insets to bottom
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, ime.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Clear the activity context when activity is destroyed
        // This prevents background operations from using a destroyed activity context
        activityContextProvider.setActivityContext(null)
    }
} 