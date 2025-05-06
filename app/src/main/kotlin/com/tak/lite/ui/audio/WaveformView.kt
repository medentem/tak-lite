package com.tak.lite.ui.audio

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val paint = Paint().apply {
        color = Color.GREEN
        strokeWidth = 4f
        isAntiAlias = true
    }
    private var amplitudes: List<Int> = emptyList()

    fun setAmplitudes(newAmplitudes: List<Int>) {
        amplitudes = newAmplitudes.takeLast(64) // Show last 64 samples
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (amplitudes.isEmpty()) return
        val w = width.toFloat()
        val h = height.toFloat()
        val n = amplitudes.size
        val maxAmp = amplitudes.maxOrNull()?.toFloat()?.coerceAtLeast(1f) ?: 1f
        val step = w / n
        for (i in amplitudes.indices) {
            val x = i * step
            val amp = amplitudes[i] / maxAmp
            val y0 = h / 2 - (h / 2) * amp
            val y1 = h / 2 + (h / 2) * amp
            canvas.drawLine(x, y0, x, y1, paint)
        }
    }
} 