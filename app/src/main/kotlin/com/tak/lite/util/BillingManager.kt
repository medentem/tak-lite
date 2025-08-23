package com.tak.lite.util

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BillingManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val donationManager: DonationManager
) {
    private val TAG = "BillingManager"

    // For debugging
    private val IS_PREMIUM_OVERRIDE = false

    // Debug mode for troubleshooting billing issues
    private val DEBUG_MODE = false

    private val _isPremium = MutableStateFlow(false)
    val isPremium: StateFlow<Boolean> = _isPremium

    private val _isOffline = MutableStateFlow(false)
    val isOffline: StateFlow<Boolean> = _isOffline

    private val _productDetails = MutableStateFlow<Map<String, ProductDetails>>(emptyMap())
    val productDetails: StateFlow<Map<String, ProductDetails>> = _productDetails

    private val _isGooglePlayAvailable = MutableStateFlow(false)
    val isGooglePlayAvailable: StateFlow<Boolean> = _isGooglePlayAvailable

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "billing_prefs"
        private const val KEY_FIRST_LAUNCH = "first_launch"
        private const val KEY_IS_PREMIUM = "is_premium"
        private const val KEY_LAST_DIALOG_SHOWN = "last_dialog_shown"
        private const val TRIAL_PERIOD_DAYS = 7L
        private const val DIALOG_COOLDOWN_HOURS = 24L

        // Product IDs for different price tiers
        const val PRODUCT_ID_TIER_1 = "premium_tier_1" // $10
        const val PRODUCT_ID_TIER_2 = "premium_tier_2" // $20
        const val PRODUCT_ID_TIER_3 = "premium_tier_3" // $30

        // Test product IDs for emulator testing
        const val TEST_PRODUCT_ID_TIER_1 = "android.test.purchased" // $10
    }

    private val purchasesUpdatedListener = PurchasesUpdatedListener { billingResult, purchases ->
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (purchase in purchases) {
                handlePurchase(purchase)
            }
        }
    }

    private val billingClient = BillingClient.newBuilder(context)
        .setListener(purchasesUpdatedListener)
        .enablePendingPurchases()
        .build()

    init {
        Log.d(TAG, "Initializing BillingManager")
        checkConnectivity()
        checkGooglePlayAvailability()
        
        // Perform runtime verification on a background thread
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val billingFunctional = verifyBillingClientConnection()
            if (!billingFunctional && _isGooglePlayAvailable.value) {
                Log.w(TAG, "Google Play packages detected but billing client cannot connect - treating as degoogled device")
                _isGooglePlayAvailable.value = false
            }
            // Mark initialization as complete
            _isInitialized.value = true
        }
        
        if (_isGooglePlayAvailable.value) {
            billingClient.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(billingResult: BillingResult) {
                    Log.d(TAG, "Billing setup finished with response code: ${billingResult.responseCode}")
                    if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                        queryPurchases()
                        queryProductDetails()
                    } else {
                        Log.e(TAG, "Billing setup failed: ${billingResult.debugMessage}")
                        // If billing setup fails, treat as degoogled device
                        _isGooglePlayAvailable.value = false
                    }
                }

                override fun onBillingServiceDisconnected() {
                    Log.w(TAG, "Billing service disconnected")
                }
            })
        } else {
            Log.d(TAG, "Google Play Services not available, using donation-based system")
            // Check if user has manually activated premium through donations
            val manualPremium = donationManager.isPremium()
            if (manualPremium) {
                setPremiumStatus(true)
            }
        }
        
        // Observe donation manager premium status changes
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            donationManager.isPremium.collectLatest { donationPremium ->
                Log.d(TAG, "Donation premium status changed: $donationPremium")
                if (donationPremium) {
                    setPremiumStatus(true)
                }
            }
        }
    }

    private fun isEmulator(): Boolean {
        return (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic")
                || Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.HARDWARE.contains("goldfish")
                || Build.HARDWARE.contains("ranchu")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MANUFACTURER.contains("Genymotion")
                || Build.PRODUCT.contains("sdk_gphone")
                || Build.PRODUCT.contains("google_sdk")
                || Build.PRODUCT.contains("sdk")
                || Build.PRODUCT.contains("sdk_x86")
                || Build.PRODUCT.contains("vbox86p")
                || Build.PRODUCT.contains("emulator")
                || Build.PRODUCT.contains("simulator"))
    }

    private fun checkConnectivity() {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        _isOffline.value = capabilities == null || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        Log.d(TAG, "Connectivity check: isOffline = ${_isOffline.value}")
    }

    private fun checkGooglePlayAvailability() {
        // DEBUG: Set to true to simulate de-googled device
        val simulateDeGoogledDevice = false
        
        if (simulateDeGoogledDevice) {
            _isGooglePlayAvailable.value = false
            Log.d(TAG, "DEBUG: Simulating de-googled device")
            return
        }
        
        try {
            val packageManager = context.packageManager
            
            // Check for Google Play Store
            val playStoreInstalled = try {
                val packageInfo = packageManager.getPackageInfo("com.android.vending", 0)
                if (DEBUG_MODE) {
                    Log.d(TAG, "Google Play Store found: version=${packageInfo.versionName}, versionCode=${packageInfo.versionCode}")
                }
                true
            } catch (e: Exception) {
                Log.d(TAG, "Google Play Store not found: ${e.message}")
                false
            }
            
            // Check for Google Play Services
            val playServicesInstalled = try {
                val packageInfo = packageManager.getPackageInfo("com.google.android.gms", 0)
                if (DEBUG_MODE) {
                    Log.d(TAG, "Google Play Services found: version=${packageInfo.versionName}, versionCode=${packageInfo.versionCode}")
                }
                true
            } catch (e: Exception) {
                Log.d(TAG, "Google Play Services not found: ${e.message}")
                false
            }
            
            // Check for MicroG (common in degoogled devices)
            val microGInstalled = try {
                val packageInfo = packageManager.getPackageInfo("com.google.android.gms", 0)
                // Check if it's actually MicroG by looking for specific MicroG components
                val microGSignature = packageInfo.applicationInfo?.sourceDir
                val isMicroG = microGSignature?.contains("microg")!! || microGSignature.contains("microG")
                if (DEBUG_MODE && isMicroG) {
                    Log.d(TAG, "MicroG detected: $microGSignature")
                }
                isMicroG
            } catch (e: Exception) {
                false
            }
            
            // Additional check: try to get Google Play Services version
            val playServicesVersion = try {
                val packageInfo = packageManager.getPackageInfo("com.google.android.gms", 0)
                packageInfo.versionName
            } catch (e: Exception) {
                null
            }
            
            Log.d(TAG, "Google Play availability check: PlayStore=$playStoreInstalled, PlayServices=$playServicesInstalled, MicroG=$microGInstalled, Version=$playServicesVersion")
            
            // Only consider Google Play available if both Play Store and Play Services are installed
            // AND it's not MicroG (which has limited billing support)
            val isAvailable = playStoreInstalled && playServicesInstalled && !microGInstalled
            
            _isGooglePlayAvailable.value = isAvailable
            Log.d(TAG, "Final Google Play Services availability: $isAvailable")
            
        } catch (e: Exception) {
            _isGooglePlayAvailable.value = false
            Log.d(TAG, "Google Play Services availability check failed: ${e.message}")
        }
    }

    /**
     * Runtime verification to test if billing client can actually connect
     * This helps catch cases where packages exist but don't function properly
     */
    private fun verifyBillingClientConnection(): Boolean {
        if (!_isGooglePlayAvailable.value) {
            return false
        }
        
        var connectionSuccessful = false
        val connectionLatch = java.util.concurrent.CountDownLatch(1)
        
        try {
            billingClient.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(billingResult: BillingResult) {
                    Log.d(TAG, "Billing client verification result: ${billingResult.responseCode}")
                    connectionSuccessful = billingResult.responseCode == BillingClient.BillingResponseCode.OK
                    connectionLatch.countDown()
                }

                override fun onBillingServiceDisconnected() {
                    Log.w(TAG, "Billing client verification: service disconnected")
                    connectionSuccessful = false
                    connectionLatch.countDown()
                }
            })
            
            // Wait for connection result with timeout
            connectionLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            
            if (connectionSuccessful) {
                // Disconnect after successful verification
                billingClient.endConnection()
            }
            
            Log.d(TAG, "Billing client verification completed: $connectionSuccessful")
            return connectionSuccessful
            
        } catch (e: Exception) {
            Log.e(TAG, "Billing client verification failed: ${e.message}")
            return false
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        Log.d(TAG, "Handling purchase: ${purchase.products}, state: ${purchase.purchaseState}")
        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
            if (!purchase.isAcknowledged) {
                Log.d(TAG, "Acknowledging purchase")
                val acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()
                billingClient.acknowledgePurchase(acknowledgePurchaseParams) { billingResult ->
                    Log.d(TAG, "Purchase acknowledgment result: ${billingResult.responseCode}")
                    if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                        setPremiumStatus(true)
                    }
                }
            } else {
                Log.d(TAG, "Purchase already acknowledged, setting premium status")
                setPremiumStatus(true)
            }
        }
    }

    private fun queryPurchases() {
        Log.d(TAG, "Querying purchases, isOffline: ${_isOffline.value}")
        if (_isOffline.value) {
            val cachedPremium = isPremium()
            Log.d(TAG, "Offline mode, using cached premium status: $cachedPremium")
            _isPremium.value = cachedPremium
            return
        }

        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        ) { billingResult, purchases ->
            Log.d(TAG, "Query purchases result: ${billingResult.responseCode}, found ${purchases.size} purchases")
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                for (purchase in purchases) {
                    if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                        Log.d(TAG, "Found valid purchase, setting premium status")
                        setPremiumStatus(true)
                        return@queryPurchasesAsync
                    }
                }
            }
        }
    }

    private fun queryProductDetails() {
        if (_isOffline.value) {
            Log.d(TAG, "Skipping product details query: device is offline")
            return
        }

        val productIds = if (isEmulator()) {
            listOf(TEST_PRODUCT_ID_TIER_1)
        } else {
            listOf(PRODUCT_ID_TIER_1, PRODUCT_ID_TIER_2, PRODUCT_ID_TIER_3)
        }

        val productList = productIds.map { productId ->
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(productId)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        }

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        billingClient.queryProductDetailsAsync(params) { billingResult, productDetailsList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                val detailsMap = productDetailsList.associateBy { it.productId }
                _productDetails.value = detailsMap
                Log.d(TAG, "Successfully loaded ${detailsMap.size} product details")
            } else {
                Log.e(TAG, "Failed to query product details: ${billingResult.debugMessage}")
            }
        }
    }

    fun isInTrialPeriod(): Boolean {
        val firstLaunchTime = prefs.getLong(KEY_FIRST_LAUNCH, 0L)
        if (firstLaunchTime == 0L) {
            Log.d(TAG, "First launch detected, starting trial period")
            prefs.edit().putLong(KEY_FIRST_LAUNCH, System.currentTimeMillis()).apply()
            return true
        }

        val trialEndTime = firstLaunchTime + TimeUnit.DAYS.toMillis(TRIAL_PERIOD_DAYS)
        val isInTrial = System.currentTimeMillis() < trialEndTime
        Log.d(TAG, "Trial period check: isInTrial=$isInTrial, firstLaunchTime=$firstLaunchTime, trialEndTime=$trialEndTime")
        return isInTrial
    }

    fun isPremium(): Boolean {
        if (IS_PREMIUM_OVERRIDE) {
            return true
        }

        // Check both Google Play purchases and manual donations
        val googlePlayPremium = prefs.getBoolean(KEY_IS_PREMIUM, false)
        val donationPremium = donationManager.isPremium()
        val isPremium = googlePlayPremium || donationPremium
        Log.d(TAG, "Checking premium status: googlePlay=$googlePlayPremium, donation=$donationPremium, total=$isPremium")
        
        // Update the StateFlow to reflect current status
        if (_isPremium.value != isPremium) {
            _isPremium.value = isPremium
            Log.d(TAG, "Updated premium StateFlow to: $isPremium")
        }
        
        return isPremium
    }

    private fun setPremiumStatus(isPremium: Boolean) {
        Log.d(TAG, "Setting premium status to: $isPremium")
        prefs.edit().putBoolean(KEY_IS_PREMIUM, isPremium).apply()
        _isPremium.value = isPremium
    }

    fun shouldShowPurchaseDialog(): Boolean {
        val isPremium = isPremium()
        val inTrial = isInTrialPeriod()
        Log.d(TAG, "Checking if should show purchase dialog: isPremium=$isPremium, inTrial=$inTrial")
        
        if (isPremium) {
            Log.d(TAG, "Not showing purchase dialog: user is premium")
            return false
        }

        val lastShown = prefs.getLong(KEY_LAST_DIALOG_SHOWN, 0L)
        val cooldownEnd = lastShown + TimeUnit.HOURS.toMillis(DIALOG_COOLDOWN_HOURS)
        val shouldShow = System.currentTimeMillis() >= cooldownEnd
        Log.d(TAG, "Purchase dialog cooldown check: lastShown=$lastShown, cooldownEnd=$cooldownEnd, shouldShow=$shouldShow")
        return shouldShow
    }

    fun markPurchaseDialogShown() {
        Log.d(TAG, "Marking purchase dialog as shown")
        prefs.edit().putLong(KEY_LAST_DIALOG_SHOWN, System.currentTimeMillis()).apply()
    }

    // DEBUG: Force refresh premium status
    fun refreshPremiumStatus() {
        val currentStatus = isPremium()
        Log.d(TAG, "Forced refresh of premium status: $currentStatus")
        _isPremium.value = currentStatus
    }

    /**
     * Force refresh Google Play availability check
     * Useful for debugging or when system state changes
     */
    fun refreshGooglePlayAvailability() {
        Log.d(TAG, "Forcing refresh of Google Play availability check")
        checkGooglePlayAvailability()
        
        // Re-run runtime verification
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val billingFunctional = verifyBillingClientConnection()
            if (!billingFunctional && _isGooglePlayAvailable.value) {
                Log.w(TAG, "Google Play packages detected but billing client cannot connect - treating as degoogled device")
                _isGooglePlayAvailable.value = false
            }
        }
    }

    fun launchBillingFlow(activity: Activity, productId: String) {
        Log.d(TAG, "Launching billing flow for product: $productId")
        
        // Check if Google Play is actually available and functional
        if (!_isGooglePlayAvailable.value) {
            Log.w(TAG, "Cannot launch billing flow: Google Play Services not available")
            showDonationDialogFallback(activity)
            return
        }
        
        if (_isOffline.value) {
            Log.w(TAG, "Cannot launch billing flow: device is offline")
            android.widget.Toast.makeText(
                context,
                "Please check your internet connection to make a purchase",
                android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }

        val actualProductId = if (isEmulator()) {
            Log.d(TAG, "Using test product ID for emulator")
            TEST_PRODUCT_ID_TIER_1
        } else {
            productId
        }

        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(actualProductId)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        )

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        Log.d(TAG, "Querying product details for: $actualProductId")
        billingClient.queryProductDetailsAsync(params) { billingResult, productDetailsList ->
            Log.d(TAG, "Product details query result: ${billingResult.responseCode}, found ${productDetailsList.size} products")
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                productDetailsList.firstOrNull()?.let { productDetails ->
                    Log.d(TAG, "Launching billing flow for product: ${productDetails.productId}")
                    val productDetailsParamsList = listOf(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                            .setProductDetails(productDetails)
                            .build()
                    )

                    val billingFlowParams = BillingFlowParams.newBuilder()
                        .setProductDetailsParamsList(productDetailsParamsList)
                        .build()

                    try {
                        billingClient.launchBillingFlow(activity, billingFlowParams)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to launch billing flow: ${e.message}")
                        showDonationDialogFallback(activity)
                    }
                } ?: run {
                    Log.w(TAG, "No product details found for ID: $actualProductId")
                    showDonationDialogFallback(activity)
                }
            } else {
                Log.e(TAG, "Failed to query product details: ${billingResult.debugMessage}")
                showDonationDialogFallback(activity)
            }
        }
    }

    /**
     * Fallback method to show donation dialog when billing fails
     */
    private fun showDonationDialogFallback(activity: Activity) {
        Log.d(TAG, "Showing donation dialog as fallback for billing failure")
        activity.runOnUiThread {
            try {
                val dialog = com.tak.lite.ui.DonationDialog()
                if (activity is androidx.fragment.app.FragmentActivity) {
                    dialog.show(activity.supportFragmentManager, "donation_dialog_fallback")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show donation dialog fallback: ${e.message}")
                android.widget.Toast.makeText(
                    context,
                    "Billing not available. Please use donation options instead.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Public method to check if billing is actually functional
     * This can be called before showing the purchase dialog
     */
    fun isBillingFunctional(): Boolean {
        return _isGooglePlayAvailable.value && !_isOffline.value
    }

    /**
     * Wait for BillingManager to be fully initialized
     * This ensures all checks are complete before making decisions
     */
    suspend fun waitForInitialization() {
        while (!_isInitialized.value) {
            kotlinx.coroutines.delay(100) // Wait 100ms before checking again
        }
    }

    /**
     * Check if BillingManager is ready to make decisions
     */
    fun isReady(): Boolean {
        return _isInitialized.value
    }

    /**
     * Debug method to get detailed information about Google Play availability
     * This can be called from the UI for troubleshooting
     */
    fun getDebugInfo(): String {
        val sb = StringBuilder()
        sb.appendLine("=== BillingManager Debug Info ===")
        sb.appendLine("Google Play Available: ${_isGooglePlayAvailable.value}")
        sb.appendLine("Billing Functional: ${isBillingFunctional()}")
        sb.appendLine("Is Offline: ${_isOffline.value}")
        sb.appendLine("Is Initialized: ${_isInitialized.value}")
        sb.appendLine("Is Premium: ${_isPremium.value}")
        
        try {
            val packageManager = context.packageManager
            
            // Check Play Store
            try {
                val playStoreInfo = packageManager.getPackageInfo("com.android.vending", 0)
                sb.appendLine("Play Store: Found (v${playStoreInfo.versionName})")
            } catch (e: Exception) {
                sb.appendLine("Play Store: Not found (${e.message})")
            }
            
            // Check Play Services
            try {
                val playServicesInfo = packageManager.getPackageInfo("com.google.android.gms", 0)
                sb.appendLine("Play Services: Found (v${playServicesInfo.versionName})")
                
                // Check for MicroG
                val sourceDir = playServicesInfo.applicationInfo?.sourceDir
                if (sourceDir?.contains("microg")!! || sourceDir.contains("microG")) {
                    sb.appendLine("MicroG: Detected")
                } else {
                    sb.appendLine("MicroG: Not detected")
                }
            } catch (e: Exception) {
                sb.appendLine("Play Services: Not found (${e.message})")
            }
            
        } catch (e: Exception) {
            sb.appendLine("Error getting package info: ${e.message}")
        }
        
        sb.appendLine("================================")
        return sb.toString()
    }
} 