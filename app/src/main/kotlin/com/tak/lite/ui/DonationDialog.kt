package com.tak.lite.ui

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import com.tak.lite.R
import com.tak.lite.util.BillingManager
import com.tak.lite.util.CryptoQRCodeGenerator
import com.tak.lite.util.DonationManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class DonationDialog : DialogFragment() {

    @Inject
    lateinit var donationManager: DonationManager
    
    @Inject
    lateinit var qrCodeGenerator: CryptoQRCodeGenerator
    
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
        return inflater.inflate(R.layout.dialog_donation, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupDonationButtons(view)
        setupCryptocurrencyButtons(view)
        setupManualActivation(view)
        setupBackToPurchaseButton(view)
    }

    private fun setupDonationButtons(view: View) {
        // GitHub Sponsors
        view.findViewById<Button>(R.id.githubSponsorsButton)?.setOnClickListener {
            donationManager.openGitHubSponsors()
            dismiss()
        }
    }

    private fun setupCryptocurrencyButtons(view: View) {
        // Bitcoin
        view.findViewById<Button>(R.id.bitcoinButton)?.setOnClickListener {
            showCryptoDialog("Bitcoin", donationManager.copyBitcoinAddress(), "BTC")
        }

        // Ethereum
        view.findViewById<Button>(R.id.ethereumButton)?.setOnClickListener {
            showCryptoDialog("Ethereum", donationManager.copyEthereumAddress(), "ETH")
        }
    }

    private fun showCryptoDialog(cryptoName: String, address: String, symbol: String) {
        val dialog = Dialog(requireContext(), R.style.Theme_TakLite_Dialog)
        dialog.setContentView(R.layout.dialog_crypto_donation)
        
        val titleText = dialog.findViewById<TextView>(R.id.cryptoTitle)
        val addressText = dialog.findViewById<TextView>(R.id.cryptoAddress)
        val qrCodeImage = dialog.findViewById<ImageView>(R.id.qrCodeImage)
        val copyButton = dialog.findViewById<Button>(R.id.copyAddressButton)
        val closeButton = dialog.findViewById<Button>(R.id.closeButton)
        
        titleText.text = "$cryptoName ($symbol) Donation"
        addressText.text = address
        
        // Generate QR code
        val qrCode = when (cryptoName) {
            "Bitcoin" -> qrCodeGenerator.generateBitcoinQRCode(address)
            "Ethereum" -> qrCodeGenerator.generateEthereumQRCode(address)
            else -> qrCodeGenerator.generateGenericQRCode(address)
        }
        
        qrCode?.let { qrCodeImage.setImageBitmap(it) }
        
        copyButton.setOnClickListener {
            copyToClipboard(address, "$cryptoName address")
            Toast.makeText(context, context?.getString(R.string.address_copied_to_clipboard, cryptoName), Toast.LENGTH_SHORT).show()
        }
        
        closeButton.setOnClickListener {
            dialog.dismiss()
        }
        
        dialog.show()
    }

    private fun setupManualActivation(view: View) {
        val manualActivationButton = view.findViewById<Button>(R.id.manualActivationButton)
        if (manualActivationButton == null) {
            Log.e("DonationDialog", "Manual activation button not found!")
            return
        }
        
        Log.d("DonationDialog", "Setting up manual activation button")
        manualActivationButton.setOnClickListener {
            Log.d("DonationDialog", "Manual activation button clicked")
            donationManager.activatePremiumManually()
            billingManager.refreshPremiumStatus()
            Toast.makeText(context, context?.getString(R.string.premium_activated), Toast.LENGTH_LONG).show()
            dismiss()
        }
    }

    private fun setupBackToPurchaseButton(view: View) {
        val backToPurchaseButton = view.findViewById<Button>(R.id.backToPurchaseButton)
        if (backToPurchaseButton == null) {
            Log.e("DonationDialog", "Back to purchase button not found!")
            return
        }

        // Only show the back to purchase button if billing is functional
        val isBillingFunctional = billingManager.isBillingFunctional()
        Log.d("DonationDialog", "Setting up back to purchase button, billing functional: $isBillingFunctional")
        
        if (isBillingFunctional) {
            backToPurchaseButton.visibility = View.VISIBLE
            backToPurchaseButton.setOnClickListener {
                try {
                    Log.d("DonationDialog", "Back to purchase button clicked")
                    
                    // Dismiss current dialog
                    dismiss()
                    
                    // Show purchase dialog
                    val purchaseDialog = com.tak.lite.ui.PurchaseDialog()
                    purchaseDialog.show(parentFragmentManager, "purchase_dialog_from_donation")
                    
                    Log.d("DonationDialog", "Successfully switched to purchase dialog")
                } catch (e: Exception) {
                    Log.e("DonationDialog", "Failed to show purchase dialog: ${e.message}")
                    Toast.makeText(context, "Failed to open purchase options. Please try again.", Toast.LENGTH_LONG).show()
                }
            }
        } else {
            // Hide the button if billing is not functional
            backToPurchaseButton.visibility = View.GONE
            Log.d("DonationDialog", "Hiding back to purchase button - billing not functional")
        }
    }

    private fun copyToClipboard(text: String, label: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, context?.getString(R.string.label_copied_to_clipboard, label), Toast.LENGTH_SHORT).show()
    }
} 