package com.tak.lite.ui

import android.app.Dialog
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.android.billingclient.api.ProductDetails
import com.tak.lite.R
import com.tak.lite.util.BillingManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class PurchaseDialog : DialogFragment() {

    @Inject
    lateinit var billingManager: BillingManager

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return Dialog(requireContext(), R.style.Theme_TakLite_Dialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_purchase, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Observe offline state and product details
        viewLifecycleOwner.lifecycleScope.launch {
            billingManager.isOffline.collectLatest { isOffline ->
                updateUI(view, isOffline)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            billingManager.productDetails.collectLatest { productDetails ->
                updateProductDetails(view, productDetails)
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

    private fun updateProductDetails(view: View, productDetails: Map<String, ProductDetails>) {
        // Update Tier 1
        view.findViewById<TextView>(R.id.tier1Name)?.text = 
            productDetails[BillingManager.PRODUCT_ID_TIER_1]?.name ?: "Support Tier 1"
        view.findViewById<TextView>(R.id.tier1Description)?.text = 
            productDetails[BillingManager.PRODUCT_ID_TIER_1]?.description ?: "Basic support tier"
        view.findViewById<TextView>(R.id.tier1Price)?.text = 
            productDetails[BillingManager.PRODUCT_ID_TIER_1]?.getFormattedPrice() ?: "$10"

        // Update Tier 2
        view.findViewById<TextView>(R.id.tier2Name)?.text = 
            productDetails[BillingManager.PRODUCT_ID_TIER_2]?.name ?: "Support Tier 2"
        view.findViewById<TextView>(R.id.tier2Description)?.text = 
            productDetails[BillingManager.PRODUCT_ID_TIER_2]?.description ?: "Enhanced support tier"
        view.findViewById<TextView>(R.id.tier2Price)?.text = 
            productDetails[BillingManager.PRODUCT_ID_TIER_2]?.getFormattedPrice() ?: "$20"

        // Update Tier 3
        view.findViewById<TextView>(R.id.tier3Name)?.text = 
            productDetails[BillingManager.PRODUCT_ID_TIER_3]?.name ?: "Support Tier 3"
        view.findViewById<TextView>(R.id.tier3Description)?.text = 
            productDetails[BillingManager.PRODUCT_ID_TIER_3]?.description ?: "Premium support tier"
        view.findViewById<TextView>(R.id.tier3Price)?.text = 
            productDetails[BillingManager.PRODUCT_ID_TIER_3]?.getFormattedPrice() ?: "$30"
    }

    private fun ProductDetails.getFormattedPrice(): String {
        return subscriptionOfferDetails?.firstOrNull()?.pricingPhases?.pricingPhaseList?.firstOrNull()?.formattedPrice
            ?: oneTimePurchaseOfferDetails?.formattedPrice
            ?: "Price not available"
    }

    private fun updateUI(view: View, isOffline: Boolean) {
        val trialStatusText = view.findViewById<TextView>(R.id.trialStatusText)
        val tier1Button = view.findViewById<Button>(R.id.tier1Button)
        val tier2Button = view.findViewById<Button>(R.id.tier2Button)
        val tier3Button = view.findViewById<Button>(R.id.tier3Button)

        val baseMessage = if (billingManager.isInTrialPeriod()) {
            "You are currently in your 7-day free trial period. Your support is critical to ensuring the ongoing development of the app. Pick any paid option - we hope you'll support this app with the highest paid level you can afford. Thank you in advance."
        } else {
            "Your trial period has ended. Your support is critical to ensuring the ongoing development of the app. Pick any paid option to continue - we hope you'll support this app with the highest paid level you can afford. Thank you in advance."
        }

        trialStatusText.text = if (isEmulator()) {
            "$baseMessage\n\n[TEST MODE: Using Google Play Billing test products]"
        } else {
            baseMessage
        }

        if (isOffline) {
            trialStatusText.text = "You are currently offline. Please check your internet connection to make a purchase."
            tier1Button.isEnabled = false
            tier2Button.isEnabled = false
            tier3Button.isEnabled = false
        } else {
            tier1Button.isEnabled = true
            tier2Button.isEnabled = true
            tier3Button.isEnabled = true
        }

        tier1Button.apply {
            text = "Purchase"
            setOnClickListener {
                billingManager.launchBillingFlow(requireActivity(), BillingManager.PRODUCT_ID_TIER_1)
                dismiss()
            }
        }

        tier2Button.apply {
            text = "Purchase"
            setOnClickListener {
                billingManager.launchBillingFlow(requireActivity(), BillingManager.PRODUCT_ID_TIER_2)
                dismiss()
            }
        }

        tier3Button.apply {
            text = "Purchase"
            setOnClickListener {
                billingManager.launchBillingFlow(requireActivity(), BillingManager.PRODUCT_ID_TIER_3)
                dismiss()
            }
        }
    }
} 