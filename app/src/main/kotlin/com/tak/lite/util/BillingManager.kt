package com.tak.lite.util

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.android.billingclient.api.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BillingManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val TAG = "BillingManager"

    private val _isPremium = MutableStateFlow(false)
    val isPremium: StateFlow<Boolean> = _isPremium

    private val _isOffline = MutableStateFlow(false)
    val isOffline: StateFlow<Boolean> = _isOffline

    private val _productDetails = MutableStateFlow<Map<String, ProductDetails>>(emptyMap())
    val productDetails: StateFlow<Map<String, ProductDetails>> = _productDetails

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
        const val TEST_PRODUCT_ID_TIER_2 = "android.test.purchased" // $20
        const val TEST_PRODUCT_ID_TIER_3 = "android.test.purchased" // $30
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
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                Log.d(TAG, "Billing setup finished with response code: ${billingResult.responseCode}")
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryPurchases()
                    queryProductDetails()
                } else {
                    Log.e(TAG, "Billing setup failed: ${billingResult.debugMessage}")
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "Billing service disconnected")
            }
        })
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

    fun getProductName(productId: String): String {
        return _productDetails.value[productId]?.name ?: productId
    }

    fun getProductDescription(productId: String): String {
        return _productDetails.value[productId]?.description ?: ""
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
        val premium = prefs.getBoolean(KEY_IS_PREMIUM, false)
        Log.d(TAG, "Checking premium status: $premium")
        return premium
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

    fun launchBillingFlow(activity: Activity, productId: String) {
        Log.d(TAG, "Launching billing flow for product: $productId")
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

                    billingClient.launchBillingFlow(activity, billingFlowParams)
                } ?: Log.w(TAG, "No product details found for ID: $actualProductId")
            } else {
                Log.e(TAG, "Failed to query product details: ${billingResult.debugMessage}")
            }
        }
    }
} 