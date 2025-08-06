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
            Toast.makeText(context, "$cryptoName address copied to clipboard", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(context, "Premium features activated! Thank you for your support.", Toast.LENGTH_LONG).show()
            dismiss()
        }
    }

    private fun copyToClipboard(text: String, label: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "$label copied to clipboard", Toast.LENGTH_SHORT).show()
    }
} 