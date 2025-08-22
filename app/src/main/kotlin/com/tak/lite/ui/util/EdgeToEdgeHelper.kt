package com.tak.lite.ui.util

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Helper to apply edge-to-edge window insets precisely to target views.
 * - Top: status bars and display cutout
 * - Sides: system bars
 * - Bottom: max of IME (keyboard) and system bar (gesture/nav)
 */
object EdgeToEdgeHelper {
    fun applyTopInsets(targetView: View) {
        val initialPaddingTop = targetView.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(targetView) { v, insets ->
            val statusBars = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(v.paddingLeft, initialPaddingTop + statusBars.top, v.paddingRight, v.paddingBottom)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(targetView)
    }

    fun applySidesInsets(targetView: View) {
        val initialPaddingLeft = targetView.paddingLeft
        val initialPaddingRight = targetView.paddingRight
        ViewCompat.setOnApplyWindowInsetsListener(targetView) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(initialPaddingLeft + systemBars.left, v.paddingTop, initialPaddingRight + systemBars.right, v.paddingBottom)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(targetView)
    }

    fun applyBottomInsets(targetView: View) {
        val initialPaddingBottom = targetView.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(targetView) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottom = maxOf(systemBars.bottom, imeInsets.bottom)
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, initialPaddingBottom + bottom)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(targetView)
    }
}


