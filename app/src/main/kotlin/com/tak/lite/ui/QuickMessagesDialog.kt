package com.tak.lite.ui

import android.content.Context
import android.content.res.Configuration
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.PopupWindow
import android.widget.TextView
import com.tak.lite.R
import com.tak.lite.data.model.QuickMessage
import com.tak.lite.util.QuickMessageManager

class QuickMessagesDialog(
    private val context: Context,
    private val onMessageSelected: (QuickMessage) -> Unit
) {
    private var popupWindow: PopupWindow? = null

    fun show(anchorView: View) {
        val inflater = LayoutInflater.from(context)
        val popupView = inflater.inflate(R.layout.quick_messages_menu, null)

        // Get quick messages
        val quickMessages = QuickMessageManager.getQuickMessages(context)
        
        // Set up click listeners for each message
        val messageViews = listOf(
            popupView.findViewById<TextView>(R.id.quickMessage1),
            popupView.findViewById<TextView>(R.id.quickMessage2),
            popupView.findViewById<TextView>(R.id.quickMessage3),
            popupView.findViewById<TextView>(R.id.quickMessage4),
            popupView.findViewById<TextView>(R.id.quickMessage5),
            popupView.findViewById<TextView>(R.id.quickMessage6)
        )
        
        quickMessages.forEachIndexed { index, message ->
            if (index < messageViews.size) {
                messageViews[index].text = message.text
                messageViews[index].setOnClickListener {
                    onMessageSelected(message)
                    popupWindow?.dismiss()
                }
            }
        }

        // Create popup window
        popupWindow = PopupWindow(
            popupView,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            isFocusable = true
            isOutsideTouchable = true
            elevation = 16f
        }

        // Measure popup to get its dimensions
        popupView.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
        )
        val popupWidth = popupView.measuredWidth
        val popupHeight = popupView.measuredHeight

        // Get anchor view location
        val anchorLocation = IntArray(2)
        anchorView.getLocationInWindow(anchorLocation)
        val anchorX = anchorLocation[0]
        val anchorY = anchorLocation[1]
        val anchorWidth = anchorView.width
        val anchorHeight = anchorView.height

        // Get screen dimensions
        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        // Calculate position based on orientation
        val isPortrait = context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        
        val (xOffset, yOffset, gravity) = if (isPortrait) {
            // Portrait: show to the right of the FAB
            val x = anchorX + anchorWidth + 16 // 16dp offset to the right
            val y = anchorY - (popupHeight / 2) + (anchorHeight / 2) // Center vertically with FAB
            
            // Ensure popup doesn't go off-screen
            val adjustedX = if (x + popupWidth > screenWidth) {
                anchorX - popupWidth - 16 // Show to the left instead
            } else {
                x
            }
            
            val adjustedY = y.coerceIn(16, screenHeight - popupHeight - 16)
            
            Triple(adjustedX, adjustedY, Gravity.NO_GRAVITY)
        } else {
            // Landscape: show above the FAB
            val x = anchorX - (popupWidth / 2) + (anchorWidth / 2) // Center horizontally with FAB
            val y = anchorY - popupHeight - 16 // 16dp offset above
            
            // Ensure popup doesn't go off-screen
            val adjustedX = x.coerceIn(16, screenWidth - popupWidth - 16)
            val adjustedY = if (y < 16) {
                anchorY + anchorHeight + 16 // Show below instead
            } else {
                y
            }
            
            Triple(adjustedX, adjustedY, Gravity.NO_GRAVITY)
        }

        // Show popup
        popupWindow?.showAtLocation(anchorView, gravity, xOffset, yOffset)
    }

    fun dismiss() {
        popupWindow?.dismiss()
    }

    fun isShowing(): Boolean {
        return popupWindow?.isShowing == true
    }
}
