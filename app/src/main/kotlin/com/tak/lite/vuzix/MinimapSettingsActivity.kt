package com.tak.lite.vuzix

import android.os.Bundle
import androidx.fragment.app.Fragment
import com.google.android.material.appbar.MaterialToolbar
import com.tak.lite.BaseActivity
import com.tak.lite.R
import com.tak.lite.ui.util.EdgeToEdgeHelper
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Minimap Settings Activity for Vuzix Z100 Smart Glasses
 * Provides settings interface for minimap configuration
 */
@AndroidEntryPoint
class MinimapSettingsActivity : BaseActivity() {

    @Inject
    lateinit var vuzixManager: VuzixManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_minimap_settings)

        val toolbar = findViewById<MaterialToolbar>(R.id.vuzixSettingsToolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        title = getString(R.string.vuzix_settings)
        // Apply top insets to toolbar for edge-to-edge
        EdgeToEdgeHelper.applyTopInsets(toolbar)

        // Add minimap settings fragment
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, MinimapSettingsFragment())
                .commit()
        }
    }

    override fun onResume() {
        super.onResume()
        // Notify VuzixManager that user is in settings
        vuzixManager.setInSettingsActivity(true)
    }

    override fun onPause() {
        super.onPause()
        // Notify VuzixManager that user left settings
        vuzixManager.setInSettingsActivity(false)
    }
}
