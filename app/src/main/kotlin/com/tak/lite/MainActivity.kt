package com.tak.lite

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.tak.lite.databinding.ActivityMainBinding
import com.tak.lite.viewmodel.MeshNetworkViewModel
import com.tak.lite.viewmodel.MeshNetworkUiState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityMainBinding
    private lateinit var map: GoogleMap
    private val PERMISSIONS_REQUEST_CODE = 100
    private val viewModel: MeshNetworkViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize map fragment
        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        // Setup PTT button
        setupPTTButton()
        
        // Observe mesh network state
        observeMeshNetworkState()
    }

    private fun observeMeshNetworkState() {
        lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                when (state) {
                    is MeshNetworkUiState.Connected -> {
                        // Update UI to show connected state
                        binding.pttButton.isEnabled = true
                        // TODO: Update map with peer locations
                    }
                    is MeshNetworkUiState.Disconnected -> {
                        // Update UI to show disconnected state
                        binding.pttButton.isEnabled = false
                        Toast.makeText(this@MainActivity, "Disconnected from mesh network", Toast.LENGTH_SHORT).show()
                    }
                    is MeshNetworkUiState.Error -> {
                        Toast.makeText(this@MainActivity, state.message, Toast.LENGTH_LONG).show()
                    }
                    MeshNetworkUiState.Initial -> {
                        // Initial state, no action needed
                    }
                }
            }
        }
    }

    private fun setupPTTButton() {
        binding.pttButton.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    // Start audio transmission
                    startAudioTransmission()
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    // Stop audio transmission
                    stopAudioTransmission()
                    true
                }
                else -> false
            }
        }
    }

    private fun startAudioTransmission() {
        // TODO: Implement audio transmission start
        // This will be implemented in the audio layer
    }

    private fun stopAudioTransmission() {
        // TODO: Implement audio transmission stop
        // This will be implemented in the audio layer
    }

    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap
        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO
        )

        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest, PERMISSIONS_REQUEST_CODE)
        } else {
            setupMap()
        }
    }

    private fun setupMap() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            map.isMyLocationEnabled = true
            // TODO: Setup location updates and mesh network integration
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                setupMap()
            } else {
                Toast.makeText(this, "Permissions required for app functionality", Toast.LENGTH_LONG).show()
            }
        }
    }
} 