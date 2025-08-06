package com.tak.lite.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DonationManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val TAG = "DonationManager"

    private val _isPremium = MutableStateFlow(false)
    val isPremium: StateFlow<Boolean> = _isPremium

    companion object {
        const val BITCOIN_ADDRESS = "1BimJFWZA6LXhgiTcrj7rFBB3hbENrCLRc"
        const val ETHEREUM_ADDRESS = "0x035d513B41c91c61c4C4E8382a675FaeF53AD953"
        const val GITHUB_SPONSORS_URL = "https://github.com/sponsors/medentem"
    }

    fun openGitHubSponsors() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_SPONSORS_URL))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open GitHub Sponsors", e)
        }
    }

    fun copyBitcoinAddress(): String {
        return BITCOIN_ADDRESS
    }

    fun copyEthereumAddress(): String {
        return ETHEREUM_ADDRESS
    }

    // Manual premium activation for users who donate externally
    fun activatePremiumManually() {
        Log.d(TAG, "Manually activating premium status")
        _isPremium.value = true
        // Store in shared preferences for persistence
        val prefs = context.getSharedPreferences("donation_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("is_premium", true).apply()
        Log.d(TAG, "Premium status set to true in StateFlow and SharedPreferences")
    }

    fun isPremium(): Boolean {
        val prefs = context.getSharedPreferences("donation_prefs", Context.MODE_PRIVATE)
        val premium = prefs.getBoolean("is_premium", false)
        _isPremium.value = premium
        return premium
    }
} 