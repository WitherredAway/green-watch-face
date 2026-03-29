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
        const val DEFAULT_DIAL_COLOR = 0xFF2E4A3E.toInt()
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

        private var dialColor = DEFAULT_DIAL_COLOR
        private val silverColor = 0xFFC0C0C0.toInt()
        private val silverHighlight = 0xFFE8E8E8.toInt()
        private val silverShadow = 0xFF808080.toInt()
        private val fentonRed = 0xFFCC0000.toInt()

        private lateinit var dialPaint: Paint
        private lateinit var dialGradientPaint: Paint
        private lateinit var dialMetallicPaint: Paint
        private lateinit var dialSpecularPaint: Paint
        private lateinit var hourMarkerPaint: Paint
        private lateinit var hourMarkerOutlinePaint: Paint
        private lateinit var minuteMarkerPaint: Paint
        private lateinit var handFillPaint: Paint
        private lateinit var handOutlinePaint: Paint
        private lateinit var secondHandPaint: Paint
        private lateinit var handCenterPaint: Paint
        private lateinit var handCenterOutlinePaint: Paint
        private lateinit var textPaint: Paint
        private lateinit var fentonTextPaint: Paint
        private lateinit var fentonFPaint: Paint
        private lateinit var fentonRedBoxPaint: Paint
        private lateinit var subDialRingPaint: Paint
        private lateinit var subDialTickPaint: Paint
        private lateinit var subDialHandPaint: Paint
        private lateinit var subDialTextPaint: Paint
        private lateinit var subDialCenterPaint: Paint
        private lateinit var dateBoxPaint: Paint
        private lateinit var dateBoxBorderPaint: Paint
        private lateinit var dateTextPaint: Paint
        private lateinit var ambientPaint: Paint

        private var soundPool: SoundPool? = null
        private var tickSoundId = 0
        private var soundEnabled = true
        private var lastTickSecond = -1

        private var lightX = 0f
        private var lightY = -1f
        private var lightZ = 0f
        private var sensorManager: SensorManager? = null
        private var accelerometer: Sensor? = null
        private val sensorListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                    val ax = event.values[0]
                    val ay = event.values[1]
                    val az = event.values[2]
                    val mag = sqrt(ax * ax + ay * ay + az * az).coerceAtLeast(0.001f)
                    val nx = ax / mag
                    val ny = ay / mag
                    val nz = az / mag
                    lightX = lightX + 0.5f * (nx - lightX)
                    lightY = lightY + 0.5f * (ny - lightY)
                    lightZ = lightZ + 0.5f * (nz - lightZ)
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
                alpha = 160
            }
            dialSpecularPaint = Paint().apply {
                isAntiAlias = true
                style = Paint.Style.FILL
                alpha = 80
            }
            hourMarkerPaint = Paint().apply {
                color = silverHighlight
                isAntiAlias = true
                style = Paint.Style.FILL
            }
            hourMarkerOutlinePaint = Paint().apply {
                color = 0xFF404040.toInt()
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeWidth = 0.8f
            }
            minuteMarkerPaint = Paint().apply {
                color = silverColor
                isAntiAlias = true
                style = Paint.Style.FILL
            }
            handFillPaint = Paint().apply {
                color = silverHighlight
                isAntiAlias = true
                style = Paint.Style.FILL
            }
            handOutlinePaint = Paint().apply {
                color = 0xFF1A1A1A.toInt()
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeWidth = 1.5f
                strokeJoin = Paint.Join.ROUND
            }
            secondHandPaint = Paint().apply {
                color = Color.WHITE
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeWidth = 1.5f
                strokeCap = Paint.Cap.ROUND
            }
            handCenterPaint = Paint().apply {
                color = silverHighlight
                isAntiAlias = true
                style = Paint.Style.FILL
                setShadowLayer(3f, 0f, 0f, 0x80000000.toInt())
            }
            handCenterOutlinePaint = Paint().apply {
                color = 0xFF333333.toInt()
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeWidth = 1.5f
            }
            textPaint = Paint().apply {
                color = silverColor
                isAntiAlias = true
                textSize = 12f
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            fentonTextPaint = Paint().apply {
                color = Color.WHITE
                isAntiAlias = true
                textSize = 16f
                textAlign = Paint.Align.LEFT
                typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
                letterSpacing = 0.12f
            }
            fentonFPaint = Paint().apply {
                color = Color.WHITE
                isAntiAlias = true
                textSize = 16f
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
                letterSpacing = 0.0f
            }
            fentonRedBoxPaint = Paint().apply {
                color = fentonRed
                isAntiAlias = true
                style = Paint.Style.FILL
            }
            subDialRingPaint = Paint().apply {
                color = silverColor
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeWidth = 1.5f
            }
            subDialTickPaint = Paint().apply {
                color = silverColor
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeWidth = 1f
                strokeCap = Paint.Cap.BUTT
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
            subDialCenterPaint = Paint().apply {
                color = silverHighlight
                isAntiAlias = true
                style = Paint.Style.FILL
            }
            dateBoxPaint = Paint().apply {
                color = 0xFFE8E8E8.toInt()
                isAntiAlias = true
                style = Paint.Style.FILL
            }
            dateBoxBorderPaint = Paint().apply {
                color = silverShadow
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeWidth = 1.2f
            }
            dateTextPaint = Paint().apply {
                color = Color.BLACK
                isAntiAlias = true
                textSize = 11f
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
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
            tickSoundId = generateTickSound()
        }

        private fun generateTickSound(): Int {
            try {
                val sampleRate = 44100
                val durationMs = 15
                val numSamples = sampleRate * durationMs / 1000
                val samples = ShortArray(numSamples)
                for (i in samples.indices) {
                    val t = i.toFloat() / sampleRate
                    val envelope = (1.0 - i.toFloat() / numSamples).pow(3)
                    val wave = sin(2.0 * Math.PI * 3500.0 * t) * 0.7 +
                            sin(2.0 * Math.PI * 7000.0 * t) * 0.3
                    samples[i] = (wave * envelope * Short.MAX_VALUE * 0.5).toInt().toShort()
                }
                val tempFile = java.io.File(cacheDir, "tick.wav")
                val byteBuffer = java.nio.ByteBuffer.allocate(44 + numSamples * 2)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                byteBuffer.put("RIFF".toByteArray())
                byteBuffer.putInt(36 + numSamples * 2)
                byteBuffer.put("WAVE".toByteArray())
                byteBuffer.put("fmt ".toByteArray())
                byteBuffer.putInt(16)
                byteBuffer.putShort(1)
                byteBuffer.putShort(1)
                byteBuffer.putInt(sampleRate)
                byteBuffer.putInt(sampleRate * 2)
                byteBuffer.putShort(2)
                byteBuffer.putShort(16)
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
            val darkerDial = adjustBrightness(dialColor, 0.7f)
            val lighterDial = adjustBrightness(dialColor, 1.15f)
            dialGradientPaint.shader = RadialGradient(
                centerX, centerY * 0.85f, radius * 0.95f,
                intArrayOf(lighterDial, dialColor, darkerDial),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            updateDialMetallic()
        }

        private fun updateDialMetallic() {
            val angle = (Math.toDegrees(atan2(lightY.toDouble(), lightX.toDouble())).toFloat() + 360f) % 360f
            val zFactor = (lightZ + 1f).coerceIn(0f, 2f) / 2f
            val dialLight = adjustBrightness(dialColor, 1.5f + zFactor * 0.3f)
            val dialBright = adjustBrightness(dialColor, 1.3f + zFactor * 0.2f)
            val dialMid = adjustBrightness(dialColor, 1.1f)
            val dialDark = adjustBrightness(dialColor, 0.75f)
            val dialDeep = adjustBrightness(dialColor, 0.55f)

            dialMetallicPaint.shader = SweepGradient(
                centerX, centerY,
                intArrayOf(dialDark, dialMid, dialBright, dialLight, dialBright, dialMid, dialDark, dialDeep, dialDark),
                floatArrayOf(0f, 0.10f, 0.20f, 0.30f, 0.40f, 0.50f, 0.60f, 0.85f, 1f)
            ).apply {
                val matrix = Matrix()
                matrix.setRotate(angle + 30f, centerX, centerY)
                setLocalMatrix(matrix)
            }
            dialMetallicPaint.alpha = 160

            val specX = centerX + lightX * radius * 0.4f
            val specY = centerY - lightY * radius * 0.4f
            val specRadius = radius * (0.5f + zFactor * 0.3f)
            val specAlpha = (60 + (zFactor * 60).toInt()).coerceIn(0, 120)
            val specBright = adjustBrightness(dialColor, 1.8f)
            val specMid = adjustBrightness(dialColor, 1.3f)
            dialSpecularPaint.shader = RadialGradient(
                specX, specY, specRadius,
                intArrayOf(adjustAlpha(specBright, specAlpha), adjustAlpha(specMid, specAlpha / 2), adjustAlpha(dialColor, 0)),
                floatArrayOf(0f, 0.4f, 1f),
                Shader.TileMode.CLAMP
            )
            dialSpecularPaint.alpha = specAlpha
        }

        override fun onDraw(canvas: Canvas, bounds: Rect) {
            calendar.timeInMillis = System.currentTimeMillis()
            updateDialMetallic()
            if (isAmbient) {
                drawAmbient(canvas, bounds)
            } else {
                drawInteractive(canvas, bounds)
                playTickSound()
            }
        }

        @Suppress("UNUSED_PARAMETER")
        private fun drawInteractive(canvas: Canvas, bounds: Rect) {
            canvas.drawColor(Color.BLACK)
            drawDial(canvas)
            drawMinuteMarks(canvas)
            drawHourMarkers(canvas)
            drawDayOfWeekSubDial(canvas)
            drawSecondsSubDial(canvas)
            drawBranding(canvas)
            drawDateWindow(canvas)
            drawHands(canvas)
            drawCenterHub(canvas)
        }

        private fun drawDial(canvas: Canvas) {
            val dialRadius = radius - 2f
            canvas.drawCircle(centerX, centerY, dialRadius, dialPaint)
            canvas.drawCircle(centerX, centerY, dialRadius, dialGradientPaint)
            canvas.drawCircle(centerX, centerY, dialRadius, dialMetallicPaint)
            canvas.save()
            val clipPath = Path().apply {
                addCircle(centerX, centerY, dialRadius, Path.Direction.CW)
            }
            canvas.clipPath(clipPath)
            canvas.drawCircle(centerX, centerY, dialRadius, dialSpecularPaint)
            canvas.restore()
        }

        private fun drawMinuteMarks(canvas: Canvas) {
            val outerRadius = radius - 8f
            val markerLength = radius * 0.04f
            val markerWidth = radius * 0.012f
            for (i in 0 until 60) {
                if (i % 5 == 0) continue
                val angleDeg = i * 6f - 90f
                canvas.save()
                canvas.rotate(angleDeg + 90f, centerX, centerY)
                val rect = RectF(
                    centerX - markerWidth / 2f, centerY - outerRadius,
                    centerX + markerWidth / 2f, centerY - outerRadius + markerLength
                )
                canvas.drawRect(rect, minuteMarkerPaint)
                canvas.restore()
            }
        }

        private fun drawHourMarkers(canvas: Canvas) {
            val outerRadius = radius - 8f
            for (i in 0 until 12) {
                val angleDeg = i * 30f - 90f
                val isMajor = (i == 0 || i == 3 || i == 6 || i == 9)
                val markerLength = if (isMajor) radius * 0.10f else radius * 0.07f
                val markerWidth = if (isMajor) radius * 0.042f else radius * 0.028f
                canvas.save()
                canvas.rotate(angleDeg + 90f, centerX, centerY)
                val rect = RectF(
                    centerX - markerWidth / 2f, centerY - outerRadius,
                    centerX + markerWidth / 2f, centerY - outerRadius + markerLength
                )
                val gradPaint = Paint(hourMarkerPaint).apply {
                    shader = LinearGradient(
                        rect.left, rect.top, rect.right, rect.top,
                        intArrayOf(silverShadow, silverHighlight, Color.WHITE, silverHighlight, silverShadow),
                        floatArrayOf(0f, 0.2f, 0.5f, 0.8f, 1f),
                        Shader.TileMode.CLAMP
                    )
                }
                canvas.drawRoundRect(rect, 1f, 1f, gradPaint)
                canvas.drawRoundRect(rect, 1f, 1f, hourMarkerOutlinePaint)
                canvas.restore()
            }
        }

        private fun drawDayOfWeekSubDial(canvas: Canvas) {
            val subCenterX = centerX
            val subCenterY = centerY - radius * 0.30f
            val subRadius = radius * 0.18f
            val outerRingPaint = Paint(subDialRingPaint).apply {
                strokeWidth = 1.8f
                color = adjustAlpha(silverColor, 180)
            }
            canvas.drawCircle(subCenterX, subCenterY, subRadius, outerRingPaint)
            val innerRingPaint = Paint(subDialRingPaint).apply {
                strokeWidth = 1f
                color = adjustAlpha(silverColor, 120)
            }
            canvas.drawCircle(subCenterX, subCenterY, subRadius * 0.65f, innerRingPaint)
            for (i in 0 until 28) {
                val tickAngle = Math.toRadians((i * (360.0 / 28.0)) - 90.0)
                val outerR = subRadius * 0.98f
                val innerR = if (i % 4 == 0) subRadius * 0.85f else subRadius * 0.92f
                val tickPaint = Paint(subDialTickPaint).apply {
                    strokeWidth = if (i % 4 == 0) 1.2f else 0.8f
                    color = adjustAlpha(silverColor, if (i % 4 == 0) 200 else 130)
                }
                canvas.drawLine(
                    subCenterX + cos(tickAngle).toFloat() * innerR,
                    subCenterY + sin(tickAngle).toFloat() * innerR,
                    subCenterX + cos(tickAngle).toFloat() * outerR,
                    subCenterY + sin(tickAngle).toFloat() * outerR,
                    tickPaint
                )
            }
            val days = arrayOf("S", "M", "T", "W", "T", "F", "S")
            val currentDay = calendar.get(Calendar.DAY_OF_WEEK) - 1
            for (i in 0 until 7) {
                val angle = Math.toRadians((i * (360.0 / 7.0)) - 90.0)
                val labelRadius = subRadius * 0.75f
                val x = subCenterX + cos(angle).toFloat() * labelRadius
                val y = subCenterY + sin(angle).toFloat() * labelRadius
                val dayPaint = Paint(subDialTextPaint).apply {
                    textSize = radius * 0.048f
                    color = if (i == currentDay) Color.WHITE else adjustAlpha(silverColor, 160)
                    typeface = if (i == currentDay) Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        else Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                }
                canvas.drawText(days[i], x, y + dayPaint.textSize / 3f, dayPaint)
            }
            val dayAngle = Math.toRadians((currentDay * (360.0 / 7.0)) - 90.0)
            val handLen = subRadius * 0.55f
            canvas.drawLine(
                subCenterX, subCenterY,
                subCenterX + cos(dayAngle).toFloat() * handLen,
                subCenterY + sin(dayAngle).toFloat() * handLen,
                subDialHandPaint
            )
            canvas.drawCircle(subCenterX, subCenterY, 2.5f, subDialCenterPaint)
            canvas.drawCircle(subCenterX, subCenterY, 1f, Paint().apply {
                color = 0xFF333333.toInt()
                isAntiAlias = true
                style = Paint.Style.FILL
            })
        }

        private fun drawSecondsSubDial(canvas: Canvas) {
            val subCenterX = centerX
            val subCenterY = centerY + radius * 0.30f
            val subRadius = radius * 0.18f
            val outerRingPaint = Paint(subDialRingPaint).apply {
                strokeWidth = 1.8f
                color = adjustAlpha(silverColor, 180)
            }
            canvas.drawCircle(subCenterX, subCenterY, subRadius, outerRingPaint)
            for (i in 0 until 30) {
                val tickAngle = Math.toRadians((i * 12.0) - 90.0)
                val outerR = subRadius * 0.98f
                val isMajor = (i % 5 == 0)
                val innerR = if (isMajor) subRadius * 0.82f else subRadius * 0.90f
                val tickPaint = Paint(subDialTickPaint).apply {
                    strokeWidth = if (isMajor) 1.3f else 0.8f
                    color = adjustAlpha(silverColor, if (isMajor) 220 else 140)
                }
                canvas.drawLine(
                    subCenterX + cos(tickAngle).toFloat() * innerR,
                    subCenterY + sin(tickAngle).toFloat() * innerR,
                    subCenterX + cos(tickAngle).toFloat() * outerR,
                    subCenterY + sin(tickAngle).toFloat() * outerR,
                    tickPaint
                )
            }
            val labels = arrayOf("5", "10", "15", "20", "25", "30")
            for (i in labels.indices) {
                val angle = Math.toRadians((i * 60.0) - 90.0)
                val labelRadius = subRadius * 0.68f
                val x = subCenterX + cos(angle).toFloat() * labelRadius
                val y = subCenterY + sin(angle).toFloat() * labelRadius
                val labelPaint = Paint(subDialTextPaint).apply {
                    textSize = radius * 0.040f
                    color = adjustAlpha(silverColor, 180)
                }
                canvas.drawText(labels[i], x, y + labelPaint.textSize / 3f, labelPaint)
            }
            val second = calendar.get(Calendar.SECOND)
            val secondAngle = Math.toRadians((second * 6.0) - 90.0)
            val handLen = subRadius * 0.65f
            canvas.drawLine(
                subCenterX, subCenterY,
                subCenterX + cos(secondAngle).toFloat() * handLen,
                subCenterY + sin(secondAngle).toFloat() * handLen,
                subDialHandPaint
            )
            canvas.drawCircle(subCenterX, subCenterY, 2.5f, subDialCenterPaint)
            canvas.drawCircle(subCenterX, subCenterY, 1f, Paint().apply {
                color = 0xFF333333.toInt()
                isAntiAlias = true
                style = Paint.Style.FILL
            })
        }

        private fun drawBranding(canvas: Canvas) {
            val brandY = centerY + radius * 0.02f
            val textSize = radius * 0.10f
            fentonTextPaint.textSize = textSize
            fentonFPaint.textSize = textSize
            val entonWidth = fentonTextPaint.measureText("ENTON")
            val fWidth = fentonFPaint.measureText("F")
            val boxPadH = textSize * 0.12f
            val boxPadV = textSize * 0.08f
            val boxHeight = textSize * 1.15f
            val totalWidth = (fWidth + boxPadH * 2) + entonWidth + textSize * 0.06f
            val startX = centerX - totalWidth / 2f
            val boxRect = RectF(
                startX, brandY - boxHeight + boxPadV,
                startX + fWidth + boxPadH * 2, brandY + boxPadV
            )
            canvas.drawRoundRect(boxRect, 2f, 2f, fentonRedBoxPaint)
            canvas.drawText("F", boxRect.centerX(), brandY, fentonFPaint)
            val entonX = boxRect.right + textSize * 0.06f
            fentonTextPaint.textAlign = Paint.Align.LEFT
            canvas.drawText("ENTON", entonX, brandY, fentonTextPaint)
        }

        private fun drawDateWindow(canvas: Canvas) {
            val dateX = centerX + radius * 0.35f
            val dateY = centerY
            val boxWidth = radius * 0.28f
            val boxHeight = radius * 0.13f
            val rect = RectF(
                dateX - boxWidth / 2f, dateY - boxHeight / 2f,
                dateX + boxWidth / 2f, dateY + boxHeight / 2f
            )
            canvas.drawRoundRect(rect, 3f, 3f, dateBoxPaint)
            canvas.drawRoundRect(rect, 3f, 3f, dateBoxBorderPaint)
            val dayOfWeek = calendar.getDisplayName(
                Calendar.DAY_OF_WEEK, Calendar.SHORT, java.util.Locale.getDefault()
            )?.uppercase() ?: "MON"
            val dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH)
            dateTextPaint.textSize = boxHeight * 0.58f
            canvas.drawText(
                "$dayOfWeek $dayOfMonth", dateX,
                dateY + dateTextPaint.textSize / 3f, dateTextPaint
            )
        }

        private fun drawHands(canvas: Canvas) {
            val hours = calendar.get(Calendar.HOUR)
            val minutes = calendar.get(Calendar.MINUTE)
            val seconds = calendar.get(Calendar.SECOND)
            val hourAngle = (hours + minutes / 60f) * 30f - 90f
            val minuteAngle = (minutes + seconds / 60f) * 6f - 90f
            val secondAngleDeg = seconds * 6f - 90f
            drawDiamondHand(
                canvas, hourAngle, radius * 0.45f,
                baseWidth = radius * 0.055f, tipWidth = radius * 0.015f,
                tailLen = radius * 0.10f
            )
            drawDiamondHand(
                canvas, minuteAngle, radius * 0.65f,
                baseWidth = radius * 0.042f, tipWidth = radius * 0.012f,
                tailLen = radius * 0.12f
            )
            val secAngleRad = Math.toRadians(secondAngleDeg.toDouble())
            canvas.drawLine(
                centerX - cos(secAngleRad).toFloat() * (radius * 0.18f),
                centerY - sin(secAngleRad).toFloat() * (radius * 0.18f),
                centerX + cos(secAngleRad).toFloat() * (radius * 0.72f),
                centerY + sin(secAngleRad).toFloat() * (radius * 0.72f),
                secondHandPaint
            )
        }

        private fun drawDiamondHand(
            canvas: Canvas, angleDeg: Float, length: Float,
            baseWidth: Float, tipWidth: Float, tailLen: Float
        ) {
            val angleRad = Math.toRadians(angleDeg.toDouble())
            val perpRad = angleRad + Math.PI / 2
            val cosA = cos(angleRad).toFloat()
            val sinA = sin(angleRad).toFloat()
            val cosP = cos(perpRad).toFloat()
            val sinP = sin(perpRad).toFloat()
            val widePoint = length * 0.30f
            val path = Path().apply {
                moveTo(
                    centerX - cosA * tailLen + cosP * tipWidth,
                    centerY - sinA * tailLen + sinP * tipWidth
                )
                lineTo(
                    centerX - cosA * tailLen - cosP * tipWidth,
                    centerY - sinA * tailLen - sinP * tipWidth
                )
                lineTo(
                    centerX + cosA * widePoint - cosP * baseWidth,
                    centerY + sinA * widePoint - sinP * baseWidth
                )
                lineTo(centerX + cosA * length, centerY + sinA * length)
                lineTo(
                    centerX + cosA * widePoint + cosP * baseWidth,
                    centerY + sinA * widePoint + sinP * baseWidth
                )
                close()
            }
            val fillPaint = Paint(handFillPaint).apply {
                shader = LinearGradient(
                    centerX + cosP * baseWidth * 2f,
                    centerY + sinP * baseWidth * 2f,
                    centerX - cosP * baseWidth * 2f,
                    centerY - sinP * baseWidth * 2f,
                    intArrayOf(silverShadow, silverHighlight, Color.WHITE, silverHighlight, silverShadow),
                    floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f),
                    Shader.TileMode.CLAMP
                )
                setShadowLayer(3f, 1f, 2f, 0x60000000.toInt())
            }
            canvas.drawPath(path, fillPaint)
            canvas.drawPath(path, handOutlinePaint)
        }

        private fun drawCenterHub(canvas: Canvas) {
            val hubRadius = radius * 0.04f
            val hubGradPaint = Paint(handCenterPaint).apply {
                shader = RadialGradient(
                    centerX - hubRadius * 0.3f, centerY - hubRadius * 0.3f,
                    hubRadius * 1.5f,
                    intArrayOf(Color.WHITE, silverHighlight, silverShadow),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawCircle(centerX, centerY, hubRadius, hubGradPaint)
            canvas.drawCircle(centerX, centerY, hubRadius, handCenterOutlinePaint)
            canvas.drawCircle(centerX, centerY, hubRadius * 0.35f, Paint().apply {
                color = 0xFF222222.toInt()
                isAntiAlias = true
                style = Paint.Style.FILL
            })
        }

        private fun playTickSound() {
            val currentSecond = calendar.get(Calendar.SECOND)
            if (currentSecond != lastTickSecond && soundEnabled && !isMuteMode) {
                soundPool?.play(tickSoundId, 0.3f, 0.3f, 1, 0, 1f)
                lastTickSecond = currentSecond
            }
        }

        @Suppress("UNUSED_PARAMETER")
        private fun drawAmbient(canvas: Canvas, bounds: Rect) {
            canvas.drawColor(Color.BLACK)
            val hours = calendar.get(Calendar.HOUR)
            val minutes = calendar.get(Calendar.MINUTE)
            val ambientMarkerPaint = Paint().apply {
                color = Color.WHITE
                isAntiAlias = false
                style = Paint.Style.STROKE
                strokeWidth = 2f
            }
            for (i in 0 until 12) {
                val angle = Math.toRadians((i * 30 - 90).toDouble())
                canvas.drawLine(
                    centerX + cos(angle).toFloat() * (radius - 24f),
                    centerY + sin(angle).toFloat() * (radius - 24f),
                    centerX + cos(angle).toFloat() * (radius - 10f),
                    centerY + sin(angle).toFloat() * (radius - 10f),
                    ambientMarkerPaint
                )
            }
            val hourAngle = Math.toRadians(
                ((hours + minutes / 60f) * 30f - 90f).toDouble()
            )
            val ambientHourPaint = Paint(ambientPaint).apply { strokeWidth = 4f }
            canvas.drawLine(
                centerX, centerY,
                centerX + cos(hourAngle).toFloat() * radius * 0.45f,
                centerY + sin(hourAngle).toFloat() * radius * 0.45f,
                ambientHourPaint
            )
            val minuteAngle = Math.toRadians((minutes * 6f - 90f).toDouble())
            canvas.drawLine(
                centerX, centerY,
                centerX + cos(minuteAngle).toFloat() * radius * 0.65f,
                centerY + sin(minuteAngle).toFloat() * radius * 0.65f,
                ambientPaint
            )
            val dayOfWeek = calendar.getDisplayName(
                Calendar.DAY_OF_WEEK, Calendar.SHORT, java.util.Locale.getDefault()
            )?.uppercase() ?: "MON"
            val dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH)
            val ambientDatePaint = Paint().apply {
                color = Color.WHITE
                isAntiAlias = false
                textSize = radius * 0.08f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(
                "$dayOfWeek $dayOfMonth",
                centerX + radius * 0.35f,
                centerY + ambientDatePaint.textSize / 3f,
                ambientDatePaint
            )
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
            if (tapType == WatchFaceService.TAP_TYPE_TAP) {
                invalidate()
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
                dialColor = prefs.getInt(KEY_DIAL_COLOR, DEFAULT_DIAL_COLOR)
                dialPaint.color = dialColor
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
            this@FentonWatchFaceService.registerReceiver(
                timeZoneReceiver, IntentFilter(Intent.ACTION_TIMEZONE_CHANGED)
            )
        }

        private fun unregisterReceiver() {
            if (!registeredTimeZoneReceiver) return
            registeredTimeZoneReceiver = false
            this@FentonWatchFaceService.unregisterReceiver(timeZoneReceiver)
        }

        private fun registerSensor() {
            accelerometer?.let {
                sensorManager?.registerListener(
                    sensorListener, it, SensorManager.SENSOR_DELAY_GAME
                )
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

        private fun shouldTimerBeRunning(): Boolean = isVisible && !isAmbient

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

        private fun adjustAlpha(color: Int, alpha: Int): Int {
            return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
        }

        private fun adjustBrightness(color: Int, factor: Float): Int {
            val r = min(255, (Color.red(color) * factor).toInt())
            val g = min(255, (Color.green(color) * factor).toInt())
            val b = min(255, (Color.blue(color) * factor).toInt())
            return Color.argb(Color.alpha(color), r, g, b)
        }
    }
}
