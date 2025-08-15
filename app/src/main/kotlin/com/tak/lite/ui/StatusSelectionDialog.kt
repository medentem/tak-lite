package com.tak.lite.ui

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.PopupWindow
import com.tak.lite.R
import com.tak.lite.model.UserStatus

class StatusSelectionDialog(
    private val context: Context,
    private val currentStatus: UserStatus,
    private val onStatusSelected: (UserStatus) -> Unit
) {
    private var popupWindow: PopupWindow? = null

    fun show(anchorView: View) {
        val inflater = LayoutInflater.from(context)
        val popupView = inflater.inflate(R.layout.status_selection_menu, null)

        // Set up click listeners for each status option
        val redContainer = popupView.findViewById<View>(R.id.statusRedContainer)
        val yellowContainer = popupView.findViewById<View>(R.id.statusYellowContainer)
        val blueContainer = popupView.findViewById<View>(R.id.statusBlueContainer)
        val orangeContainer = popupView.findViewById<View>(R.id.statusOrangeContainer)
        val violetContainer = popupView.findViewById<View>(R.id.statusVioletContainer)
        val greenContainer = popupView.findViewById<View>(R.id.statusGreenContainer)

        redContainer.setOnClickListener {
            onStatusSelected(UserStatus.RED)
            dismiss()
        }

        yellowContainer.setOnClickListener {
            onStatusSelected(UserStatus.YELLOW)
            dismiss()
        }

        blueContainer.setOnClickListener {
            onStatusSelected(UserStatus.BLUE)
            dismiss()
        }

        orangeContainer.setOnClickListener {
            onStatusSelected(UserStatus.ORANGE)
            dismiss()
        }

        violetContainer.setOnClickListener {
            onStatusSelected(UserStatus.VIOLET)
            dismiss()
        }

        greenContainer.setOnClickListener {
            onStatusSelected(UserStatus.GREEN)
            dismiss()
        }

        // Highlight current status
        when (currentStatus) {
            UserStatus.RED -> redContainer.alpha = 0.7f
            UserStatus.YELLOW -> yellowContainer.alpha = 0.7f
            UserStatus.BLUE -> blueContainer.alpha = 0.7f
            UserStatus.ORANGE -> orangeContainer.alpha = 0.7f
            UserStatus.VIOLET -> violetContainer.alpha = 0.7f
            UserStatus.GREEN -> greenContainer.alpha = 0.7f
        }

        // Create and show popup window
        popupWindow = PopupWindow(
            popupView,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            isFocusable = true
            isOutsideTouchable = true
            elevation = 8f
        }

        // Calculate position to show popup to the left of the anchor view
        val anchorLocation = IntArray(2)
        anchorView.getLocationInWindow(anchorLocation)
        
        // Get popup dimensions
        popupView.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val popupWidth = popupView.measuredWidth
        val popupHeight = popupView.measuredHeight
        
        // Calculate x position (to the left of anchor)
        val xOffset = -popupWidth - 8 // 8dp margin
        
        // Calculate y position (centered vertically with anchor)
        val yOffset = -(popupHeight / 2) + (anchorView.height / 2)
        
        // Show popup to the left of the anchor view
        popupWindow?.showAtLocation(anchorView, Gravity.NO_GRAVITY, anchorLocation[0] + xOffset, anchorLocation[1] + yOffset)
    }

    fun dismiss() {
        popupWindow?.dismiss()
        popupWindow = null
    }
} 