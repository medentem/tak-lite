package com.tak.lite.vuzix

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.tak.lite.R
import dagger.hilt.android.AndroidEntryPoint

/**
 * Minimap Settings Activity for Vuzix Z100 Smart Glasses
 * Provides settings interface for minimap configuration
 */
@AndroidEntryPoint
class MinimapSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_minimap_settings)

        // Set up action bar
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Vuzix Z100 Minimap Settings"

        // Add minimap settings fragment
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, MinimapSettingsFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}
