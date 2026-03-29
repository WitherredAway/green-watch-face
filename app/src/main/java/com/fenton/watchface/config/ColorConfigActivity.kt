package com.fenton.watchface.config

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.fenton.watchface.FentonWatchFaceService
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class ColorConfigActivity : Activity() {

    private lateinit var colorWheelView: ColorWheelView
    private lateinit var previewView: ColorPreviewView
    private var selectedColor = FentonWatchFaceService.DEFAULT_DIAL_COLOR

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(FentonWatchFaceService.PREFS_NAME, Context.MODE_PRIVATE)
        selectedColor = prefs.getInt(FentonWatchFaceService.KEY_DIAL_COLOR, FentonWatchFaceService.DEFAULT_DIAL_COLOR)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(Color.BLACK)
        }

        val title = TextView(this).apply {
            text = "Dial Color"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 16)
        }
        layout.addView(title)

        previewView = ColorPreviewView(this, selectedColor)
        val previewParams = LinearLayout.LayoutParams(80, 80).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = 12
        }
        layout.addView(previewView, previewParams)

        colorWheelView = ColorWheelView(this) { color ->
            selectedColor = color
            previewView.setColor(color)
        }
        val wheelParams = LinearLayout.LayoutParams(180, 180).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = 12
        }
        layout.addView(colorWheelView, wheelParams)

        // Preset colors
        val presetsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 4, 0, 8)
        }

        val presetColors = listOf(
            0xFF2E4A3E.toInt(), // Default green
            0xFF1A3A5C.toInt(), // Navy blue
            0xFF5C1A1A.toInt(), // Dark red
            0xFF3C3C3C.toInt(), // Charcoal
            0xFF4A3E2E.toInt(), // Brown
            0xFF2E2E4A.toInt(), // Dark purple
            0xFF1A4A4A.toInt(), // Teal
            0xFF4A4A1A.toInt(), // Olive
        )

        for (color in presetColors) {
            val swatch = View(this).apply {
                setBackgroundColor(color)
                setOnClickListener {
                    selectedColor = color
                    previewView.setColor(color)
                }
            }
            val swatchParams = LinearLayout.LayoutParams(28, 28).apply {
                setMargins(4, 0, 4, 0)
            }
            presetsLayout.addView(swatch, swatchParams)
        }
        layout.addView(presetsLayout)

        // Save button
        val saveBtn = Button(this).apply {
            text = "SAVE"
            setTextColor(Color.WHITE)
            setBackgroundColor(0xFF333333.toInt())
            textSize = 14f
            setOnClickListener {
                prefs.edit().putInt(FentonWatchFaceService.KEY_DIAL_COLOR, selectedColor).apply()
                setResult(RESULT_OK)
                finish()
            }
        }
        val btnParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = 8
        }
        layout.addView(saveBtn, btnParams)

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        scrollView.addView(layout)
        setContentView(scrollView)
    }
}

class ColorWheelView(context: Context, private val onColorSelected: (Int) -> Unit) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) - 4f

        val colors = intArrayOf(
            0xFFFF0000.toInt(), 0xFFFF8800.toInt(), 0xFFFFFF00.toInt(),
            0xFF00FF00.toInt(), 0xFF00FFFF.toInt(), 0xFF0000FF.toInt(),
            0xFFFF00FF.toInt(), 0xFFFF0000.toInt()
        )
        paint.shader = SweepGradient(cx, cy, colors, null)
        canvas.drawCircle(cx, cy, r, paint)

        // Draw center indicator
        canvas.drawCircle(cx, cy, 5f, centerPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
            val cx = width / 2f
            val cy = height / 2f
            val dx = event.x - cx
            val dy = event.y - cy
            val dist = sqrt(dx * dx + dy * dy)
            val r = min(cx, cy) - 4f

            if (dist <= r) {
                val angle = (Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 360) % 360
                val hue = angle.toFloat()
                val sat = (dist / r).coerceIn(0.3f, 1f)
                val value = 0.4f + (1f - dist / r) * 0.2f // Keep it dark for watch dial

                val hsv = floatArrayOf(hue, sat, value)
                val color = Color.HSVToColor(hsv)
                onColorSelected(color)
            }
            return true
        }
        return super.onTouchEvent(event)
    }
}

class ColorPreviewView(context: Context, private var color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    fun setColor(c: Int) {
        color = c
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) - 4f
        paint.color = color
        canvas.drawCircle(cx, cy, r, paint)
        canvas.drawCircle(cx, cy, r, borderPaint)
    }
}
