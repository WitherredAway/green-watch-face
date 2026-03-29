package com.fenton.watchface

import android.content.Context
import android.graphics.*
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

object IconGenerator {
    fun generateIcon(context: Context): Bitmap {
        val size = 192
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cx = size / 2f
        val cy = size / 2f
        val r = size / 2f - 4f

        // Background
        canvas.drawColor(Color.BLACK)

        // Bezel
        val bezelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = SweepGradient(
                cx, cy,
                intArrayOf(0xFF808080.toInt(), 0xFFE8E8E8.toInt(), Color.WHITE, 0xFFE8E8E8.toInt(), 0xFF808080.toInt(), 0xFF999999.toInt(), 0xFF808080.toInt()),
                floatArrayOf(0f, 0.15f, 0.25f, 0.35f, 0.5f, 0.75f, 1f)
            )
            style = Paint.Style.STROKE
            strokeWidth = 12f
        }
        canvas.drawCircle(cx, cy, r - 6f, bezelPaint)

        // Dial
        val dialPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF2E4A3E.toInt()
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, r - 14f, dialPaint)

        // Hour markers
        val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFE8E8E8.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 4f
            strokeCap = Paint.Cap.ROUND
        }
        for (i in 0 until 12) {
            val angle = Math.toRadians((i * 30 - 90).toDouble())
            val outerR = r - 18f
            val innerR = r - 32f
            canvas.drawLine(
                cx + cos(angle).toFloat() * innerR, cy + sin(angle).toFloat() * innerR,
                cx + cos(angle).toFloat() * outerR, cy + sin(angle).toFloat() * outerR,
                markerPaint
            )
        }

        // Hands (static at 10:10)
        val handPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFE8E8E8.toInt()
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        // Hour at ~10
        handPaint.strokeWidth = 6f
        val hourA = Math.toRadians(-30.0 - 90.0)
        canvas.drawLine(cx, cy, cx + cos(hourA).toFloat() * (r * 0.4f), cy + sin(hourA).toFloat() * (r * 0.4f), handPaint)
        // Minute at ~2
        handPaint.strokeWidth = 4f
        val minA = Math.toRadians(60.0 - 90.0)
        canvas.drawLine(cx, cy, cx + cos(minA).toFloat() * (r * 0.6f), cy + sin(minA).toFloat() * (r * 0.6f), handPaint)

        return bitmap
    }
}
