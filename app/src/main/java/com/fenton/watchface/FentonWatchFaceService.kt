package com.fenton.watchface

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.support.wearable.watchface.CanvasWatchFaceService
import android.support.wearable.watchface.WatchFaceService
import android.support.wearable.watchface.WatchFaceStyle
import android.view.SurfaceHolder
import java.lang.ref.WeakReference
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.*

class FentonWatchFaceService : CanvasWatchFaceService() {

    companion object {
        const val MSG_UPDATE_TIME = 0
        const val INTERACTIVE_UPDATE_RATE_MS = 1000L
        const val PREFS_NAME = "fenton_watchface_prefs"
        const val KEY_DIAL_COLOR = "dial_color"
        const val DEFAULT_DIAL_COLOR = 0xFF2E4A3E.toInt() // dark green
    }

    override fun onCreateEngine(): Engine = FentonEngine()

    private class UpdateTimeHandler(reference: FentonEngine) : Handler(Looper.getMainLooper()) {
        private val weakRef = WeakReference(reference)
        override fun handleMessage(msg: Message) {
            weakRef.get()?.let { engine ->
                if (msg.what == MSG_UPDATE_TIME) {
                    engine.handleUpdateTimeMessage()
                }
            }
        }
    }

    inner class FentonEngine : CanvasWatchFaceService.Engine() {

        private lateinit var calendar: Calendar
        private var registeredTimeZoneReceiver = false
        private var isAmbient = false
        private var isMuteMode = false
        private var centerX = 0f
        private var centerY = 0f
        private var radius = 0f

        // Colors
        private var dialColor = DEFAULT_DIAL_COLOR
        private val silverColor = 0xFFC0C0C0.toInt()
        private val silverHighlight = 0xFFE8E8E8.toInt()
        private val silverShadow = 0xFF808080.toInt()
        private val darkSilver = 0xFF999999.toInt()
        private val fentonRed = 0xFFFF0000.toInt()

        // Paints
        private lateinit var dialPaint: Paint
        private lateinit var dialGradientPaint: Paint
        private lateinit var dialMetallicPaint: Paint
        private lateinit var hourMarkerPaint: Paint
        private lateinit var minuteMarkerPaint: Paint
        private lateinit var hourHandPaint: Paint
        private lateinit var minuteHandPaint: Paint
        private lateinit var secondHandPaint: Paint
        private lateinit var handCenterPaint: Paint
        private lateinit var textPaint: Paint
        private lateinit var fentonTextPaint: Paint
        private lateinit var fentonRedPaint: Paint
        private lateinit var subDialPaint: Paint
        private lateinit var subDialMarkerPaint: Paint
        private lateinit var subDialHandPaint: Paint
        private lateinit var dateBoxPaint: Paint
        private lateinit var dateTextPaint: Paint
        private lateinit var ambientPaint: Paint
        private lateinit var subDialTextPaint: Paint

        // Sound
        private var soundPool: SoundPool? = null
        private var tickSoundId = 0
        private var soundEnabled = true
        private var lastTickSecond = -1

        // Accelerometer-driven light angle for real-time metallic effect
        private var lightAngle = 315f
        private var sensorManager: SensorManager? = null
        private var accelerometer: Sensor? = null
        private val sensorListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                    val x = event.values[0]
                    val y = event.values[1]
                    // Map accelerometer tilt to light angle
                    val newAngle = (Math.toDegrees(atan2(y.toDouble(), x.toDouble())).toFloat() + 360f) % 360f
                    // Smooth the transition
                    lightAngle = lightAngle + 0.3f * ((newAngle - lightAngle + 540f) % 360f - 180f)
                    lightAngle = (lightAngle + 360f) % 360f
                    if (!isAmbient) {
                        invalidate()
                    }
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        private val updateTimeHandler = UpdateTimeHandler(this)

        private val timeZoneReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                calendar.timeZone = TimeZone.getDefault()
                invalidate()
            }
        }

        private lateinit var prefs: SharedPreferences

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)

            setWatchFaceStyle(
                WatchFaceStyle.Builder(this@FentonWatchFaceService)
                    .setAcceptsTapEvents(true)
                    .build()
            )

            calendar = Calendar.getInstance()
            prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            dialColor = prefs.getInt(KEY_DIAL_COLOR, DEFAULT_DIAL_COLOR)

            initPaints()
            initSound()
            initSensor()
        }

        private fun initSensor() {
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        }

        private fun initPaints() {
            // Main dial background
            dialPaint = Paint().apply {
                color = dialColor
                isAntiAlias = true
                style = Paint.Style.FILL
            }

            dialGradientPaint = Paint().apply {
                isAntiAlias = true
                style = Paint.Style.FILL
            }

            dialMetallicPaint = Paint().apply {
                isAntiAlias = true
                style = Paint.Style.FILL
                alpha = 140  // semi-transparent metallic overlay
            }

            // Hour markers
            hourMarkerPaint = Paint().apply {
                color = silverHighlight
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeWidth = 4f
                strokeCap = Paint.Cap.ROUND
            }

            // Minute tick markers
            minuteMarkerPaint = Paint().apply {
                color = silverColor
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeWidth = 1.5f
                strokeCap = Paint.Cap.ROUND
            }

            // Hour hand
            hourHandPaint = Paint().apply {
                color = silverHighlight
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeWidth = 6f
                strokeCap = Paint.Cap.ROUND
                setShadowLayer(3f, 1f, 1f, 0x80000000.toInt())
            }

            // Minute hand
            minuteHandPaint = Paint().apply {
                color = silverHighlight
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeWidth = 4f
                strokeCap = Paint.Cap.ROUND
                setShadowLayer(3f, 1f, 1f, 0x80000000.toInt())
            }

            // Second hand
            secondHandPaint = Paint().apply {
                color = Color.WHITE
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeWidth = 1.5f
                strokeCap = Paint.Cap.ROUND
            }

            // Center circle
            handCenterPaint = Paint().apply {
                color = silverHighlight
                isAntiAlias = true
                style = Paint.Style.FILL
                setShadowLayer(2f, 0f, 0f, 0x80000000.toInt())
            }

            // General text
            textPaint = Paint().apply {
                color = silverColor
                isAntiAlias = true
                textSize = 12f
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }

            // FENTON text (white part)
            fentonTextPaint = Paint().apply {
                color = Color.WHITE
                isAntiAlias = true
                textSize = 16f
                textAlign = Paint.Align.LEFT
                typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
                letterSpacing = 0.15f
            }

            // FENTON red F
            fentonRedPaint = Paint().apply {
                color = fentonRed
                isAntiAlias = true
                textSize = 16f
                textAlign = Paint.Align.LEFT
                typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
                letterSpacing = 0.15f
            }

            // Sub-dial
            subDialPaint = Paint().apply {
                color = adjustAlpha(dialColor, 200)
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeWidth = 1.5f
            }

            subDialMarkerPaint = Paint().apply {
                color = silverColor
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeWidth = 1f
                strokeCap = Paint.Cap.ROUND
            }

            subDialHandPaint = Paint().apply {
                color = silverHighlight
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeWidth = 1.5f
                strokeCap = Paint.Cap.ROUND
            }

            subDialTextPaint = Paint().apply {
                color = silverColor
                isAntiAlias = true
                textSize = 8f
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            }

            // Date box
            dateBoxPaint = Paint().apply {
                color = 0xFFE0E0E0.toInt()
                isAntiAlias = true
                style = Paint.Style.FILL
            }

            dateTextPaint = Paint().apply {
                color = Color.BLACK
                isAntiAlias = true
                textSize = 11f
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }

            // Ambient mode paint
            ambientPaint = Paint().apply {
                color = Color.WHITE
                isAntiAlias = false
                style = Paint.Style.STROKE
                strokeWidth = 2f
            }
        }

        private fun initSound() {
            val audioAttrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            soundPool = SoundPool.Builder()
                .setMaxStreams(1)
                .setAudioAttributes(audioAttrs)
                .build()

            // We'll generate a tick sound programmatically
            tickSoundId = generateTickSound()
        }

        private fun generateTickSound(): Int {
            try {
                val sampleRate = 44100
                val durationMs = 15
                val numSamples = sampleRate * durationMs / 1000
                val samples = ShortArray(numSamples)

                // Create a short click/tick sound
                for (i in samples.indices) {
                    val t = i.toFloat() / sampleRate
                    val envelope = (1.0 - i.toFloat() / numSamples).pow(3)
                    val wave = sin(2.0 * Math.PI * 3500.0 * t) * 0.7 +
                            sin(2.0 * Math.PI * 7000.0 * t) * 0.3
                    samples[i] = (wave * envelope * Short.MAX_VALUE * 0.5).toInt().toShort()
                }

                // Write to temp file as WAV
                val tempFile = java.io.File(cacheDir, "tick.wav")
                val byteBuffer = java.nio.ByteBuffer.allocate(44 + numSamples * 2)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)

                // WAV header
                byteBuffer.put("RIFF".toByteArray())
                byteBuffer.putInt(36 + numSamples * 2)
                byteBuffer.put("WAVE".toByteArray())
                byteBuffer.put("fmt ".toByteArray())
                byteBuffer.putInt(16) // chunk size
                byteBuffer.putShort(1) // PCM
                byteBuffer.putShort(1) // mono
                byteBuffer.putInt(sampleRate) // sample rate
                byteBuffer.putInt(sampleRate * 2) // byte rate
                byteBuffer.putShort(2) // block align
                byteBuffer.putShort(16) // bits per sample
                byteBuffer.put("data".toByteArray())
                byteBuffer.putInt(numSamples * 2)

                for (sample in samples) {
                    byteBuffer.putShort(sample)
                }

                tempFile.writeBytes(byteBuffer.array())
                return soundPool?.load(tempFile.absolutePath, 1) ?: 0
            } catch (e: Exception) {
                return 0
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            centerX = width / 2f
            centerY = height / 2f
            radius = min(centerX, centerY)
            updateDialGradient()
        }

        private fun updateDialGradient() {
            // Subtle radial gradient on the dial for depth
            val darkerDial = adjustBrightness(dialColor, 0.7f)
            val lighterDial = adjustBrightness(dialColor, 1.1f)
            dialGradientPaint.shader = RadialGradient(
                centerX, centerY * 0.85f, radius * 0.9f,
                intArrayOf(lighterDial, dialColor, darkerDial),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            updateDialMetallic()
        }

        private fun updateDialMetallic() {
            // Metallic sweep gradient overlay on the dial that rotates with light angle
            val dialLight = adjustBrightness(dialColor, 1.45f)
            val dialMid = adjustBrightness(dialColor, 1.15f)
            val dialDark = adjustBrightness(dialColor, 0.75f)
            val dialDeep = adjustBrightness(dialColor, 0.6f)

            dialMetallicPaint.shader = SweepGradient(
                centerX, centerY,
                intArrayOf(
                    dialDark, dialMid, dialLight, dialMid,
                    dialDark, dialDeep, dialDark
                ),
                floatArrayOf(0f, 0.15f, 0.28f, 0.40f, 0.55f, 0.80f, 1f)
            ).apply {
                val matrix = Matrix()
                matrix.setRotate(lightAngle + 30f, centerX, centerY)
                setLocalMatrix(matrix)
            }
        }

        override fun onDraw(canvas: Canvas, bounds: Rect) {
            calendar.timeInMillis = System.currentTimeMillis()

            // Update metallic dial effect based on current light angle (driven by accelerometer)
            updateDialMetallic()

            if (isAmbient) {
                drawAmbient(canvas, bounds)
            } else {
                drawInteractive(canvas, bounds)
                playTickSound()
            }
        }

        private fun drawInteractive(canvas: Canvas, bounds: Rect) {
            val width = bounds.width().toFloat()
            val height = bounds.height().toFloat()

            // Background: black
            canvas.drawColor(Color.BLACK)

            // Draw dial background with metallic gradient
            drawDial(canvas)

            // Draw minute tick marks
            drawMinuteMarks(canvas)

            // Draw hour markers
            drawHourMarkers(canvas)

            // Draw sub-dials
            drawDayOfWeekSubDial(canvas) // top
            drawSecondsSubDial(canvas) // bottom

            // Draw FENTON branding
            drawBranding(canvas)

            // Draw date window
            drawDateWindow(canvas)

            // Draw hands
            drawHands(canvas)

            // Draw center dot
            canvas.drawCircle(centerX, centerY, 5f, handCenterPaint)
            canvas.drawCircle(centerX, centerY, 2f, Paint().apply {
                color = Color.BLACK
                isAntiAlias = true
                style = Paint.Style.FILL
            })
        }

        private fun drawDial(canvas: Canvas) {
            val dialRadius = radius - 2f
            // Base fill
            canvas.drawCircle(centerX, centerY, dialRadius, dialPaint)
            // Radial depth gradient
            canvas.drawCircle(centerX, centerY, dialRadius, dialGradientPaint)
            // Metallic sweep highlight that moves with the light angle
            canvas.drawCircle(centerX, centerY, dialRadius, dialMetallicPaint)
        }

        private fun drawMinuteMarks(canvas: Canvas) {
            val outerRadius = radius - 6f
            val innerRadius = radius - 12f
            for (i in 0 until 60) {
                if (i % 5 == 0) continue // skip hour positions
                val angle = Math.toRadians((i * 6 - 90).toDouble())
                val startX = centerX + cos(angle).toFloat() * innerRadius
                val startY = centerY + sin(angle).toFloat() * innerRadius
                val endX = centerX + cos(angle).toFloat() * outerRadius
                val endY = centerY + sin(angle).toFloat() * outerRadius
                canvas.drawLine(startX, startY, endX, endY, minuteMarkerPaint)
            }
        }

        private fun drawHourMarkers(canvas: Canvas) {
            val outerRadius = radius - 6f
            val innerRadius = radius - 20f

            for (i in 0 until 12) {
                val angle = Math.toRadians((i * 30 - 90).toDouble())
                val cos = cos(angle).toFloat()
                val sin = sin(angle).toFloat()

                val markerPaint = Paint(hourMarkerPaint).apply {
                    strokeWidth = if (i == 0 || i == 3 || i == 6 || i == 9) 5f else 4f
                }

                val startX = centerX + cos * innerRadius
                val startY = centerY + sin * innerRadius
                val endX = centerX + cos * outerRadius
                val endY = centerY + sin * outerRadius
                canvas.drawLine(startX, startY, endX, endY, markerPaint)
            }
        }

        private fun drawDayOfWeekSubDial(canvas: Canvas) {
            val subCenterX = centerX
            val subCenterY = centerY - radius * 0.30f
            val subRadius = radius * 0.14f

            // Sub-dial circle
            val ringPaint = Paint().apply {
                color = adjustAlpha(silverColor, 100)
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeWidth = 1f
            }
            canvas.drawCircle(subCenterX, subCenterY, subRadius, ringPaint)

            // Day labels
            val days = arrayOf("S", "M", "T", "W", "T", "F", "S")
            val currentDay = calendar.get(Calendar.DAY_OF_WEEK) - 1 // 0=Sunday

            for (i in 0 until 7) {
                val angle = Math.toRadians((i * (360.0 / 7.0)) - 90.0)
                val labelRadius = subRadius * 0.75f
                val x = subCenterX + cos(angle).toFloat() * labelRadius
                val y = subCenterY + sin(angle).toFloat() * labelRadius

                val dayPaint = Paint(subDialTextPaint).apply {
                    textSize = radius * 0.045f
                    color = if (i == currentDay) Color.WHITE else adjustAlpha(silverColor, 150)
                    typeface = if (i == currentDay)
                        Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    else
                        Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                }
                canvas.drawText(days[i], x, y + dayPaint.textSize / 3f, dayPaint)
            }

            // Sub-dial hand pointing to current day
            val dayAngle = Math.toRadians((currentDay * (360.0 / 7.0)) - 90.0)
            val handLen = subRadius * 0.55f
            canvas.drawLine(
                subCenterX, subCenterY,
                subCenterX + cos(dayAngle).toFloat() * handLen,
                subCenterY + sin(dayAngle).toFloat() * handLen,
                subDialHandPaint
            )

            // Center dot
            canvas.drawCircle(subCenterX, subCenterY, 2f, handCenterPaint)
        }

        private fun drawSecondsSubDial(canvas: Canvas) {
            val subCenterX = centerX
            val subCenterY = centerY + radius * 0.30f
            val subRadius = radius * 0.14f

            // Sub-dial circle
            val ringPaint = Paint().apply {
                color = adjustAlpha(silverColor, 100)
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeWidth = 1f
            }
            canvas.drawCircle(subCenterX, subCenterY, subRadius, ringPaint)

            // Labels: 5, 10, 15, 20, 25, 30 (or 60-second markers)
            val labels = arrayOf("5", "10", "15", "20", "25", "30")
            for (i in labels.indices) {
                val angle = Math.toRadians((i * 60.0) - 90.0)
                val labelRadius = subRadius * 0.75f
                val x = subCenterX + cos(angle).toFloat() * labelRadius
                val y = subCenterY + sin(angle).toFloat() * labelRadius

                val labelPaint = Paint(subDialTextPaint).apply {
                    textSize = radius * 0.038f
                    color = adjustAlpha(silverColor, 150)
                }
                canvas.drawText(labels[i], x, y + labelPaint.textSize / 3f, labelPaint)
            }

            // Tick marks
            for (i in 0 until 30) {
                val angle = Math.toRadians((i * 12.0) - 90.0)
                val outerR = subRadius * 0.95f
                val innerR = if (i % 5 == 0) subRadius * 0.80f else subRadius * 0.88f
                canvas.drawLine(
                    subCenterX + cos(angle).toFloat() * innerR,
                    subCenterY + sin(angle).toFloat() * innerR,
                    subCenterX + cos(angle).toFloat() * outerR,
                    subCenterY + sin(angle).toFloat() * outerR,
                    subDialMarkerPaint
                )
            }

            // Second hand in sub-dial
            val second = calendar.get(Calendar.SECOND)
            val secondAngle = Math.toRadians((second * 6.0) - 90.0)
            val handLen = subRadius * 0.65f
            canvas.drawLine(
                subCenterX, subCenterY,
                subCenterX + cos(secondAngle).toFloat() * handLen,
                subCenterY + sin(secondAngle).toFloat() * handLen,
                subDialHandPaint
            )

            canvas.drawCircle(subCenterX, subCenterY, 2f, handCenterPaint)
        }

        private fun drawBranding(canvas: Canvas) {
            val brandY = centerY - radius * 0.05f
            val textSize = radius * 0.09f
            fentonRedPaint.textSize = textSize
            fentonTextPaint.textSize = textSize

            // Measure "F"
            val fWidth = fentonRedPaint.measureText("F")
            val entonWidth = fentonTextPaint.measureText("ENTON")
            val totalWidth = fWidth + entonWidth
            val startX = centerX - totalWidth / 2f

            // Draw "F" in red
            canvas.drawText("F", startX, brandY, fentonRedPaint)
            // Draw "ENTON" in white
            canvas.drawText("ENTON", startX + fWidth, brandY, fentonTextPaint)
        }

        private fun drawDateWindow(canvas: Canvas) {
            // Position at 3 o'clock
            val dateX = centerX + radius * 0.32f
            val dateY = centerY
            val boxWidth = radius * 0.22f
            val boxHeight = radius * 0.10f

            // Date box background
            val rect = RectF(
                dateX - boxWidth / 2f, dateY - boxHeight / 2f,
                dateX + boxWidth / 2f, dateY + boxHeight / 2f
            )

            // Box with slight rounding
            canvas.drawRoundRect(rect, 3f, 3f, dateBoxPaint)

            // Border
            val borderPaint = Paint().apply {
                color = silverShadow
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeWidth = 1f
            }
            canvas.drawRoundRect(rect, 3f, 3f, borderPaint)

            // Date text
            val dayOfWeek = calendar.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, java.util.Locale.getDefault())?.uppercase() ?: "MON"
            val dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH)
            val dateStr = "$dayOfWeek $dayOfMonth"

            dateTextPaint.textSize = boxHeight * 0.65f
            canvas.drawText(dateStr, dateX, dateY + dateTextPaint.textSize / 3f, dateTextPaint)
        }

        private fun drawHands(canvas: Canvas) {
            val hours = calendar.get(Calendar.HOUR)
            val minutes = calendar.get(Calendar.MINUTE)
            val seconds = calendar.get(Calendar.SECOND)

            val hourAngle = Math.toRadians(
                ((hours + minutes / 60f) * 30f - 90f).toDouble()
            )
            val minuteAngle = Math.toRadians(
                ((minutes + seconds / 60f) * 6f - 90f).toDouble()
            )
            val secondAngle = Math.toRadians((seconds * 6f - 90f).toDouble())

            // Apply lighting to hands
            val hourHandLen = radius * 0.45f
            val minuteHandLen = radius * 0.65f
            val secondHandLen = radius * 0.70f

            // Hour hand with metallic shading
            drawMetallicHand(canvas, centerX, centerY, hourAngle, hourHandLen, hourHandPaint, 6f)

            // Minute hand with metallic shading
            drawMetallicHand(canvas, centerX, centerY, minuteAngle, minuteHandLen, minuteHandPaint, 4f)

            // Second hand (thin, white)
            val secEndX = centerX + cos(secondAngle).toFloat() * secondHandLen
            val secEndY = centerY + sin(secondAngle).toFloat() * secondHandLen
            val secTailX = centerX - cos(secondAngle).toFloat() * (radius * 0.15f)
            val secTailY = centerY - sin(secondAngle).toFloat() * (radius * 0.15f)
            canvas.drawLine(secTailX, secTailY, secEndX, secEndY, secondHandPaint)
        }

        private fun drawMetallicHand(
            canvas: Canvas, cx: Float, cy: Float,
            angle: Double, length: Float, basePaint: Paint, width: Float
        ) {
            // Create a metallic gradient along the hand
            val endX = cx + cos(angle).toFloat() * length
            val endY = cy + sin(angle).toFloat() * length

            // Perpendicular direction for gradient
            val perpAngle = angle + Math.PI / 2
            val gradOffset = width * 1.5f
            val gx1 = cx + cos(perpAngle).toFloat() * gradOffset
            val gy1 = cy + sin(perpAngle).toFloat() * gradOffset
            val gx2 = cx - cos(perpAngle).toFloat() * gradOffset
            val gy2 = cy - sin(perpAngle).toFloat() * gradOffset

            val metallicPaint = Paint(basePaint).apply {
                strokeWidth = width
                shader = LinearGradient(
                    gx1, gy1, gx2, gy2,
                    intArrayOf(silverShadow, silverHighlight, Color.WHITE, silverHighlight, silverShadow),
                    floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f),
                    Shader.TileMode.CLAMP
                )
            }

            canvas.drawLine(cx, cy, endX, endY, metallicPaint)

            // Luminous tip effect
            val tipLen = length * 0.15f
            val tipStartX = endX - cos(angle).toFloat() * tipLen
            val tipStartY = endY - sin(angle).toFloat() * tipLen
            val tipPaint = Paint().apply {
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeWidth = width - 1f
                strokeCap = Paint.Cap.ROUND
                color = Color.WHITE
                alpha = 180
            }
            canvas.drawLine(tipStartX, tipStartY, endX, endY, tipPaint)
        }

        private fun playTickSound() {
            val currentSecond = calendar.get(Calendar.SECOND)
            if (currentSecond != lastTickSecond && soundEnabled && !isMuteMode) {
                soundPool?.play(tickSoundId, 0.3f, 0.3f, 1, 0, 1f)
                lastTickSecond = currentSecond
            }
        }

        private fun drawAmbient(canvas: Canvas, bounds: Rect) {
            canvas.drawColor(Color.BLACK)

            val hours = calendar.get(Calendar.HOUR)
            val minutes = calendar.get(Calendar.MINUTE)

            // Simple hour markers
            val ambientMarkerPaint = Paint().apply {
                color = Color.WHITE
                isAntiAlias = false
                style = Paint.Style.STROKE
                strokeWidth = 2f
            }
            for (i in 0 until 12) {
                val angle = Math.toRadians((i * 30 - 90).toDouble())
                val outerR = radius - 18f
                val innerR = radius - 30f
                canvas.drawLine(
                    centerX + cos(angle).toFloat() * innerR,
                    centerY + sin(angle).toFloat() * innerR,
                    centerX + cos(angle).toFloat() * outerR,
                    centerY + sin(angle).toFloat() * outerR,
                    ambientMarkerPaint
                )
            }

            // Hour hand
            val hourAngle = Math.toRadians(
                ((hours + minutes / 60f) * 30f - 90f).toDouble()
            )
            val hourLen = radius * 0.45f
            val ambientHourPaint = Paint(ambientPaint).apply { strokeWidth = 4f }
            canvas.drawLine(
                centerX, centerY,
                centerX + cos(hourAngle).toFloat() * hourLen,
                centerY + sin(hourAngle).toFloat() * hourLen,
                ambientHourPaint
            )

            // Minute hand
            val minuteAngle = Math.toRadians((minutes * 6f - 90f).toDouble())
            val minuteLen = radius * 0.65f
            canvas.drawLine(
                centerX, centerY,
                centerX + cos(minuteAngle).toFloat() * minuteLen,
                centerY + sin(minuteAngle).toFloat() * minuteLen,
                ambientPaint
            )

            // Date text in ambient
            val dayOfWeek = calendar.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, java.util.Locale.getDefault())?.uppercase() ?: "MON"
            val dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH)
            val ambientDatePaint = Paint().apply {
                color = Color.WHITE
                isAntiAlias = false
                textSize = radius * 0.08f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("$dayOfWeek $dayOfMonth", centerX + radius * 0.32f, centerY + ambientDatePaint.textSize / 3f, ambientDatePaint)
        }

        override fun onPropertiesChanged(properties: Bundle) {
            super.onPropertiesChanged(properties)
        }

        override fun onAmbientModeChanged(inAmbientMode: Boolean) {
            super.onAmbientModeChanged(inAmbientMode)
            isAmbient = inAmbientMode
            updateTimer()
            invalidate()
        }

        override fun onInterruptionFilterChanged(interruptionFilter: Int) {
            super.onInterruptionFilterChanged(interruptionFilter)
            val inMuteMode = (interruptionFilter == WatchFaceService.INTERRUPTION_FILTER_NONE)
            if (isMuteMode != inMuteMode) {
                isMuteMode = inMuteMode
                invalidate()
            }
        }

        override fun onTapCommand(tapType: Int, x: Int, y: Int, eventTime: Long) {
            when (tapType) {
                WatchFaceService.TAP_TYPE_TAP -> {
                    // Could toggle sound or open config
                    invalidate()
                }
            }
        }

        override fun onTimeTick() {
            super.onTimeTick()
            invalidate()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)

            if (visible) {
                registerReceiver()
                registerSensor()
                calendar.timeZone = TimeZone.getDefault()
                // Reload color preference
                dialColor = prefs.getInt(KEY_DIAL_COLOR, DEFAULT_DIAL_COLOR)
                dialPaint.color = dialColor
                subDialPaint.color = adjustAlpha(dialColor, 200)
                updateDialGradient()
                invalidate()
            } else {
                unregisterReceiver()
                unregisterSensor()
            }

            updateTimer()
        }

        private fun registerReceiver() {
            if (registeredTimeZoneReceiver) return
            registeredTimeZoneReceiver = true
            val filter = IntentFilter(Intent.ACTION_TIMEZONE_CHANGED)
            this@FentonWatchFaceService.registerReceiver(timeZoneReceiver, filter)
        }

        private fun unregisterReceiver() {
            if (!registeredTimeZoneReceiver) return
            registeredTimeZoneReceiver = false
            this@FentonWatchFaceService.unregisterReceiver(timeZoneReceiver)
        }

        private fun registerSensor() {
            accelerometer?.let {
                sensorManager?.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
            }
        }

        private fun unregisterSensor() {
            sensorManager?.unregisterListener(sensorListener)
        }

        private fun updateTimer() {
            updateTimeHandler.removeMessages(MSG_UPDATE_TIME)
            if (shouldTimerBeRunning()) {
                updateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME)
            }
        }

        private fun shouldTimerBeRunning(): Boolean {
            return isVisible && !isAmbient
        }

        fun handleUpdateTimeMessage() {
            invalidate()
            if (shouldTimerBeRunning()) {
                val timeMs = System.currentTimeMillis()
                val delayMs = INTERACTIVE_UPDATE_RATE_MS - (timeMs % INTERACTIVE_UPDATE_RATE_MS)
                updateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs)
            }
        }

        override fun onDestroy() {
            updateTimeHandler.removeMessages(MSG_UPDATE_TIME)
            unregisterSensor()
            soundPool?.release()
            soundPool = null
            super.onDestroy()
        }

        // Utility functions
        private fun adjustAlpha(color: Int, alpha: Int): Int {
            return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
        }

        private fun adjustBrightness(color: Int, factor: Float): Int {
            val r = min(255, (Color.red(color) * factor).toInt())
            val g = min(255, (Color.green(color) * factor).toInt())
            val b = min(255, (Color.blue(color) * factor).toInt())
            return Color.argb(Color.alpha(color), r, g, b)
        }

        private fun blendColor(color1: Int, color2: Int, ratio: Float): Int {
            val r = (Color.red(color1) * (1 - ratio) + Color.red(color2) * ratio).toInt()
            val g = (Color.green(color1) * (1 - ratio) + Color.green(color2) * ratio).toInt()
            val b = (Color.blue(color1) * (1 - ratio) + Color.blue(color2) * ratio).toInt()
            return Color.argb(255, r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
        }
    }
}
