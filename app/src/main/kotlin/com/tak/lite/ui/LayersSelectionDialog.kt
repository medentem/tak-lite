package com.tak.lite.ui

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.PopupWindow
import com.tak.lite.R

class LayersSelectionDialog(
    private val context: Context,
    isWeatherEnabled: Boolean,
    isPredictionsEnabled: Boolean,
    private val isPremium: Boolean,
    private val onWeatherToggled: (Boolean) -> Unit,
    private val onPredictionsToggled: (Boolean) -> Unit,
    private val onCoverageToggled: (Boolean) -> Unit,
    private val onWeatherPremiumRequired: () -> Unit,
    isCoverageActive: Boolean
) {
    private var popupWindow: PopupWindow? = null
    private var weatherEnabledInternal: Boolean = isWeatherEnabled
    private var predictionsEnabledInternal: Boolean = isPredictionsEnabled
    private var coverageActiveInternal: Boolean = isCoverageActive

    fun show(anchorView: View) {
        val inflater = LayoutInflater.from(context)
        val popupView = inflater.inflate(R.layout.layers_selection_menu, null)

        val weatherRow = popupView.findViewById<View>(R.id.layerWeatherRow)
        val predictionsRow = popupView.findViewById<View>(R.id.layerPredictionsRow)
        val coverageRow = popupView.findViewById<View>(R.id.layerCoverageRow)
        val weatherState = popupView.findViewById<ImageView>(R.id.layerWeatherState)
        val predictionsState = popupView.findViewById<ImageView>(R.id.layerPredictionsState)
        val coverageState = popupView.findViewById<ImageView>(R.id.layerCoverageState)

        // Always show weather row, but handle premium status in click handler
        weatherRow.visibility = View.VISIBLE

        updateIndicator(weatherState, weatherEnabledInternal)
        updateIndicator(predictionsState, predictionsEnabledInternal)
        updateIndicator(coverageState, coverageActiveInternal)

        weatherRow.setOnClickListener {
            val next = !weatherEnabledInternal
            android.util.Log.d("LayersSelectionDialog", "Weather row clicked. next=$next, isPremium=$isPremium")
            
            if (next && !isPremium) {
                // User is trying to enable weather but isn't premium
                onWeatherPremiumRequired()
            } else {
                // User is either disabling weather or is premium and enabling it
                onWeatherToggled(next)
                weatherEnabledInternal = next
                updateIndicator(weatherState, weatherEnabledInternal)
            }
        }
        predictionsRow.setOnClickListener {
            val next = !predictionsEnabledInternal
            onPredictionsToggled(next)
            predictionsEnabledInternal = next
            updateIndicator(predictionsState, predictionsEnabledInternal)
        }
        coverageRow.setOnClickListener {
            val next = !coverageActiveInternal
            onCoverageToggled(next)
            coverageActiveInternal = next
            updateIndicator(coverageState, coverageActiveInternal)
        }

        popupWindow = PopupWindow(
            popupView,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            isFocusable = true
            isOutsideTouchable = true
            elevation = 8f
        }

        // Position to the left of anchor, vertically centered
        val anchorLocation = IntArray(2)
        anchorView.getLocationInWindow(anchorLocation)
        popupView.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val popupWidth = popupView.measuredWidth
        val popupHeight = popupView.measuredHeight
        val xOffset = -popupWidth - 8
        val yOffset = -(popupHeight / 2) + (anchorView.height / 2)
        popupWindow?.showAtLocation(anchorView, Gravity.NO_GRAVITY, anchorLocation[0] + xOffset, anchorLocation[1] + yOffset)
    }

    private fun updateIndicator(indicator: ImageView, enabled: Boolean) {
        indicator.setImageResource(if (enabled) R.drawable.ic_check_circle_filled else R.drawable.ic_check_circle_outline)
        val tint = if (enabled) android.graphics.Color.parseColor("#4CAF50") else android.graphics.Color.parseColor("#888888")
        indicator.setColorFilter(tint)
    }

    val isShowing: Boolean
        get() = popupWindow?.isShowing == true

    fun dismiss() {
        popupWindow?.dismiss()
        popupWindow = null
    }
}


