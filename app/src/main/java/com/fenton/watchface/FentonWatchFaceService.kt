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
        private val darkSilver = 0xFF999999.toInt()
        private val fentonRed = 0xFFCC0000.toInt()

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

        private var soundPool: SoundPool? = null
        private var tickSoundId = 0
        private var soundEnabled = true
        private var lastTickSecond = -1

        private var lightAngle = 315f
        private var lightTilt = 0.5f
        private var sensorManager: SensorManager? = null
        private var accelerometer: Sensor? = null
        private val sensorListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                    val x = event.values[0]
                    val y = event.values[1]
                    val z = event.values[2]
                    val newAngle = (Math.toDegrees(atan2(y.toDouble(), x.toDouble())).toFloat() + 360f) % 360f
                    val gravity = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
                    val tiltRatio = if (gravity > 0.1f) sqrt((x * x + y * y).toDouble()).toFloat() / gravity else 0f
                    val newTilt = (1f - tiltRatio).coerceIn(0f, 1f)
                    lightAngle = lightAngle + 0.5f * ((newAngle - lightAngle + 540f) % 360f - 180f)
                    lightAngle = (lightAngle + 360f) % 360f
                    lightTilt = lightTilt + 0.4f * (newTilt - lightTilt)
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
                alpha = 200
            }
            hourMarkerPaint = Paint().apply {
                color = silverHighlight
                isAntiAlias = true
                style = Paint.Style.FILL
            }
            minuteMarkerPaint = Paint().apply {
                color = silverColor
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeWidth = 1.5f
                strokeCap = Paint.Cap.BUTT
            }
            hourHandPaint = Paint().apply {
                color = silverHighlight
                isAntiAlias = true
                style = Paint.Style.FILL
                setShadowLayer(4f, 2f, 2f, 0xAA000000.toInt())
            }
            minuteHandPaint = Paint().apply {
                color = silverHighlight
                isAntiAlias = true
                style = Paint.Style.FILL
                setShadowLayer(4f, 2f, 2f, 0xAA000000.toInt())
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
                setShadowLayer(3f, 0f, 0f, 0xAA000000.toInt())
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
            fentonRedPaint = Paint().apply {
                color = Color.WHITE
                isAntiAlias = true
                textSize = 16f
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            }
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
            dateBoxPaint = Paint().apply {
                color = 0xFFE8E8E8.toInt()
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
                centerX, centerY * 0.85f, radius * 0.9f,
                intArrayOf(lighterDial, dialColor, darkerDial),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            updateDialMetallic()
        }

        private fun updateDialMetallic() {
            val highlightBoost = 1.0f + lightTilt * 0.6f
            val dialBright = adjustBrightness(dialColor, 1.8f * highlightBoost)
            val dialLight = adjustBrightness(dialColor, 1.55f)
            val dialMid = adjustBrightness(dialColor, 1.2f)
            val dialDark = adjustBrightness(dialColor, 0.6f)
            val dialDeep = adjustBrightness(dialColor, 0.4f)
            dialMetallicPaint.shader = SweepGradient(
                centerX, centerY,
                intArrayOf(dialDark, dialMid, dialLight, dialBright, dialLight, dialMid, dialDark, dialDeep, dialDark),
                floatArrayOf(0f, 0.10f, 0.18f, 0.26f, 0.34f, 0.42f, 0.55f, 0.78f, 1f)
            ).apply {
                val matrix = Matrix()
                matrix.setRotate(lightAngle, centerX, centerY)
                setLocalMatrix(matrix)
            }
            dialMetallicPaint.alpha = (170 + (lightTilt * 70f).toInt()).coerceIn(170, 240)
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
            canvas.drawCircle(centerX, centerY, 6f, handCenterPaint)
            canvas.drawCircle(centerX, centerY, 2.5f, Paint().apply {
                color = 0xFF333333.toInt()
                isAntiAlias = true
                style = Paint.Style.FILL
            })
        }

        private fun drawDial(canvas: Canvas) {
            val dialRadius = radius - 2f
            canvas.drawCircle(centerX, centerY, dialRadius, dialPaint)
            canvas.drawCircle(centerX, centerY, dialRadius, dialGradientPaint)
            canvas.drawCircle(centerX, centerY, dialRadius, dialMetallicPaint)
            val spotAngleRad = Math.toRadians(lightAngle.toDouble())
            val spotDist = radius * 0.3f * (1f - lightTilt)
            val spotX = centerX + cos(spotAngleRad).toFloat() * spotDist
            val spotY = centerY + sin(spotAngleRad).toFloat() * spotDist
            val spotRadius = radius * (0.35f + lightTilt * 0.35f)
            val specularPaint = Paint().apply {
                isAntiAlias = true
                style = Paint.Style.FILL
                shader = RadialGradient(
                    spotX, spotY, spotRadius,
                    intArrayOf(
                        adjustAlpha(Color.WHITE, (80 * (0.4f + lightTilt * 0.6f)).toInt()),
                        adjustAlpha(Color.WHITE, (30 * (0.3f + lightTilt * 0.5f)).toInt()),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawCircle(centerX, centerY, dialRadius, specularPaint)
        }

        private fun drawMinuteMarks(canvas: Canvas) {
            val outerRadius = radius - 8f
            val innerRadius = radius - 16f
            for (i in 0 until 60) {
                if (i % 5 == 0) continue
                val angle = Math.toRadians((i * 6 - 90).toDouble())
                canvas.drawLine(
                    centerX + cos(angle).toFloat() * innerRadius,
                    centerY + sin(angle).toFloat() * innerRadius,
                    centerX + cos(angle).toFloat() * outerRadius,
                    centerY + sin(angle).toFloat() * outerRadius,
                    minuteMarkerPaint
                )
            }
        }

        private fun drawHourMarkers(canvas: Canvas) {
            val outerRadius = radius - 8f
            for (i in 0 until 12) {
                val angleDeg = i * 30f - 90f
                val angle = Math.toRadians(angleDeg.toDouble())
                val cosVal = cos(angle).toFloat()
                val sinVal = sin(angle).toFloat()
                val isMajor = (i == 0 || i == 3 || i == 6 || i == 9)
                val markerLength = if (isMajor) radius * 0.10f else radius * 0.07f
                val markerWidth = if (isMajor) radius * 0.028f else radius * 0.020f
                val innerRadius = outerRadius - markerLength
                val midX = centerX + cosVal * (innerRadius + markerLength / 2f)
                val midY = centerY + sinVal * (innerRadius + markerLength / 2f)
                val halfLen = markerLength / 2f
                val halfWid = markerWidth / 2f
                val rotMatrix = Matrix()
                rotMatrix.setRotate(angleDeg + 90f, midX, midY)
                val pts = floatArrayOf(
                    midX - halfWid, midY - halfLen,
                    midX + halfWid, midY - halfLen,
                    midX + halfWid, midY + halfLen,
                    midX - halfWid, midY + halfLen
                )
                rotMatrix.mapPoints(pts)
                val path = Path().apply {
                    moveTo(pts[0], pts[1])
                    lineTo(pts[2], pts[3])
                    lineTo(pts[4], pts[5])
                    lineTo(pts[6], pts[7])
                    close()
                }
                val perpAngle = angle + Math.PI / 2
                val gx1 = midX + cos(perpAngle).toFloat() * markerWidth * 2f
                val gy1 = midY + sin(perpAngle).toFloat() * markerWidth * 2f
                val gx2 = midX - cos(perpAngle).toFloat() * markerWidth * 2f
                val gy2 = midY - sin(perpAngle).toFloat() * markerWidth * 2f
                val markerPaint = Paint(hourMarkerPaint).apply {
                    shader = LinearGradient(
                        gx1, gy1, gx2, gy2,
                        intArrayOf(silverShadow, silverHighlight, Color.WHITE, silverHighlight, silverShadow),
                        floatArrayOf(0f, 0.3f, 0.5f, 0.7f, 1f),
                        Shader.TileMode.CLAMP
                    )
                }
                canvas.drawPath(path, markerPaint)
                canvas.drawPath(path, Paint().apply {
                    color = 0x40000000.toInt()
                    isAntiAlias = true
                    style = Paint.Style.STROKE
                    strokeWidth = 0.5f
                })
            }
        }

        private fun drawDayOfWeekSubDial(canvas: Canvas) {
            val subCenterX = centerX
            val subCenterY = centerY - radius * 0.30f
            val subRadius = radius * 0.18f
            canvas.drawCircle(subCenterX, subCenterY, subRadius, Paint().apply {
                color = adjustAlpha(silverColor, 120)
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeWidth = 1.5f
            })
            canvas.drawCircle(subCenterX, subCenterY, subRadius * 0.92f, Paint().apply {
                color = adjustAlpha(silverColor, 60)
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeWidth = 0.5f
            })
            val tickPaint = Paint().apply {
                color = silverColor
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeWidth = 1f
            }
            for (i in 0 until 7) {
                val angle = Math.toRadians((i * (360.0 / 7.0)) - 90.0)
                val outerR = subRadius * 0.92f
                val innerR = subRadius * 0.82f
                canvas.drawLine(
                    subCenterX + cos(angle).toFloat() * innerR,
                    subCenterY + sin(angle).toFloat() * innerR,
                    subCenterX + cos(angle).toFloat() * outerR,
                    subCenterY + sin(angle).toFloat() * outerR,
                    tickPaint
                )
            }
            val days = arrayOf("S", "M", "T", "W", "T", "F", "S")
            val currentDay = calendar.get(Calendar.DAY_OF_WEEK) - 1
            for (i in 0 until 7) {
                val angle = Math.toRadians((i * (360.0 / 7.0)) - 90.0)
                val labelRadius = subRadius * 0.65f
                val x = subCenterX + cos(angle).toFloat() * labelRadius
                val y = subCenterY + sin(angle).toFloat() * labelRadius
                val dayPaint = Paint(subDialTextPaint).apply {
                    textSize = radius * 0.052f
                    color = if (i == currentDay) Color.WHITE else adjustAlpha(silverColor, 170)
                    typeface = if (i == currentDay)
                        Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    else
                        Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
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
            canvas.drawCircle(subCenterX, subCenterY, 2.5f, handCenterPaint)
        }

        private fun drawSecondsSubDial(canvas: Canvas) {
            val subCenterX = centerX
            val subCenterY = centerY + radius * 0.30f
            val subRadius = radius * 0.18f
            canvas.drawCircle(subCenterX, subCenterY, subRadius, Paint().apply {
                color = adjustAlpha(silverColor, 120)
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeWidth = 1.5f
            })
            canvas.drawCircle(subCenterX, subCenterY, subRadius * 0.92f, Paint().apply {
                color = adjustAlpha(silverColor, 60)
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeWidth = 0.5f
            })
            for (i in 0 until 30) {
                val angle = Math.toRadians((i * 12.0) - 90.0)
                val outerR = subRadius * 0.92f
                val innerR = if (i % 5 == 0) subRadius * 0.78f else subRadius * 0.85f
                canvas.drawLine(
                    subCenterX + cos(angle).toFloat() * innerR,
                    subCenterY + sin(angle).toFloat() * innerR,
                    subCenterX + cos(angle).toFloat() * outerR,
                    subCenterY + sin(angle).toFloat() * outerR,
                    subDialMarkerPaint
                )
            }
            val labels = arrayOf("5", "10", "15", "20", "25")
            for (i in labels.indices) {
                val angle = Math.toRadians(((i + 1) * 60.0) - 90.0)
                val labelRadius = subRadius * 0.65f
                val x = subCenterX + cos(angle).toFloat() * labelRadius
                val y = subCenterY + sin(angle).toFloat() * labelRadius
                canvas.drawText(labels[i], x, y + radius * 0.015f, Paint(subDialTextPaint).apply {
                    textSize = radius * 0.042f
                    color = adjustAlpha(silverColor, 170)
                })
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
            canvas.drawCircle(subCenterX, subCenterY, 2.5f, handCenterPaint)
        }

        private fun drawBranding(canvas: Canvas) {
            val brandY = centerY + radius * 0.02f
            val ts = radius * 0.10f
            fentonRedPaint.textSize = ts * 0.95f
            fentonTextPaint.textSize = ts
            val fCharWidth = fentonRedPaint.measureText("F")
            val boxPadH = ts * 0.18f
            val fBoxWidth = fCharWidth + boxPadH * 2f
            val entonWidth = fentonTextPaint.measureText("ENTON")
            val gap = ts * 0.06f
            val totalWidth = fBoxWidth + gap + entonWidth
            val startX = centerX - totalWidth / 2f
            val fontMetrics = fentonRedPaint.fontMetrics
            val textTop = brandY + fontMetrics.ascent
            val textBottom = brandY + fontMetrics.descent
            val boxPadV = ts * 0.06f
            canvas.drawRect(
                startX, textTop - boxPadV,
                startX + fBoxWidth, textBottom + boxPadV,
                Paint().apply {
                    color = fentonRed
                    isAntiAlias = true
                    style = Paint.Style.FILL
                }
            )
            canvas.drawText("F", startX + fBoxWidth / 2f, brandY, fentonRedPaint)
            canvas.drawText("ENTON", startX + fBoxWidth + gap, brandY, fentonTextPaint)
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
            canvas.drawRoundRect(rect, 3f, 3f, Paint().apply {
                color = silverShadow
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeWidth = 1f
            })
            val dayOfWeek = calendar.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, java.util.Locale.getDefault())?.uppercase() ?: "MON"
            val dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH)
            dateTextPaint.textSize = boxHeight * 0.60f
            canvas.drawText("$dayOfWeek $dayOfMonth", dateX, dateY + dateTextPaint.textSize / 3f, dateTextPaint)
        }

        private fun drawHands(canvas: Canvas) {
            val hours = calendar.get(Calendar.HOUR)
            val minutes = calendar.get(Calendar.MINUTE)
            val seconds = calendar.get(Calendar.SECOND)
            val hourAngleDeg = (hours + minutes / 60f) * 30f - 90f
            val minuteAngleDeg = (minutes + seconds / 60f) * 6f - 90f
            val secondAngleDeg = seconds * 6f - 90f
            drawDiamondHand(canvas, hourAngleDeg, radius * 0.48f, radius * 0.045f, radius * 0.12f, hourHandPaint)
            drawDiamondHand(canvas, minuteAngleDeg, radius * 0.68f, radius * 0.035f, radius * 0.12f, minuteHandPaint)
            val secAngle = Math.toRadians(secondAngleDeg.toDouble())
            canvas.drawLine(
                centerX - cos(secAngle).toFloat() * (radius * 0.18f),
                centerY - sin(secAngle).toFloat() * (radius * 0.18f),
                centerX + cos(secAngle).toFloat() * (radius * 0.72f),
                centerY + sin(secAngle).toFloat() * (radius * 0.72f),
                secondHandPaint
            )
        }

        private fun drawDiamondHand(
            canvas: Canvas, angleDeg: Float, length: Float,
            halfWidth: Float, tailLength: Float, basePaint: Paint
        ) {
            val angleRad = Math.toRadians(angleDeg.toDouble())
            val perpRad = angleRad + Math.PI / 2
            val cosA = cos(angleRad).toFloat()
            val sinA = sin(angleRad).toFloat()
            val cosP = cos(perpRad).toFloat()
            val sinP = sin(perpRad).toFloat()
            val tipX = centerX + cosA * length
            val tipY = centerY + sinA * length
            val tailX = centerX - cosA * tailLength
            val tailY = centerY - sinA * tailLength
            val wideX = centerX + cosA * (length * 0.35f)
            val wideY = centerY + sinA * (length * 0.35f)
            val path = Path().apply {
                moveTo(tailX, tailY)
                lineTo(wideX + cosP * halfWidth, wideY + sinP * halfWidth)
                lineTo(tipX, tipY)
                lineTo(wideX - cosP * halfWidth, wideY - sinP * halfWidth)
                close()
            }
            val fillPaint = Paint(basePaint).apply {
                style = Paint.Style.FILL
                shader = LinearGradient(
                    centerX + cosP * halfWidth * 3f, centerY + sinP * halfWidth * 3f,
                    centerX - cosP * halfWidth * 3f, centerY - sinP * halfWidth * 3f,
                    intArrayOf(silverShadow, silverHighlight, Color.WHITE, silverHighlight, silverShadow),
                    floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawPath(path, fillPaint)
            canvas.drawPath(path, Paint().apply {
                color = 0xFF404040.toInt()
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeWidth = 1f
                strokeJoin = Paint.Join.MITER
            })
            val tipStartX = tipX - cosA * (length * 0.18f)
            val tipStartY = tipY - sinA * (length * 0.18f)
            val tipPath = Path().apply {
                moveTo(tipStartX + cosP * halfWidth * 0.3f, tipStartY + sinP * halfWidth * 0.3f)
                lineTo(tipX, tipY)
                lineTo(tipStartX - cosP * halfWidth * 0.3f, tipStartY - sinP * halfWidth * 0.3f)
                close()
            }
            canvas.drawPath(tipPath, Paint().apply {
                isAntiAlias = true
                style = Paint.Style.FILL
                color = Color.WHITE
                alpha = 140
            })
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
            val ambientMarkerPaint = Paint().apply {
                color = Color.WHITE
                isAntiAlias = false
                style = Paint.Style.STROKE
                strokeWidth = 2f
            }
            for (i in 0 until 12) {
                val angle = Math.toRadians((i * 30 - 90).toDouble())
                val outerR = radius - 10f
                val innerR = radius - 24f
                canvas.drawLine(
                    centerX + cos(angle).toFloat() * innerR,
                    centerY + sin(angle).toFloat() * innerR,
                    centerX + cos(angle).toFloat() * outerR,
                    centerY + sin(angle).toFloat() * outerR,
                    ambientMarkerPaint
                )
            }
            val hourAngle = Math.toRadians(((hours + minutes / 60f) * 30f - 90f).toDouble())
            canvas.drawLine(
                centerX, centerY,
                centerX + cos(hourAngle).toFloat() * radius * 0.48f,
                centerY + sin(hourAngle).toFloat() * radius * 0.48f,
                Paint(ambientPaint).apply { strokeWidth = 5f }
            )
            val minuteAngle = Math.toRadians((minutes * 6f - 90f).toDouble())
            canvas.drawLine(
                centerX, centerY,
                centerX + cos(minuteAngle).toFloat() * radius * 0.68f,
                centerY + sin(minuteAngle).toFloat() * radius * 0.68f,
                ambientPaint
            )
            val dayOfWeek = calendar.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, java.util.Locale.getDefault())?.uppercase() ?: "MON"
            val dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH)
            canvas.drawText("$dayOfWeek $dayOfMonth", centerX + radius * 0.35f, centerY + radius * 0.03f, Paint().apply {
                color = Color.WHITE
                isAntiAlias = false
                textSize = radius * 0.09f
                textAlign = Paint.Align.CENTER
            })
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
                WatchFaceService.TAP_TYPE_TAP -> invalidate()
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
            this@FentonWatchFaceService.registerReceiver(timeZoneReceiver, IntentFilter(Intent.ACTION_TIMEZONE_CHANGED))
        }

        private fun unregisterReceiver() {
            if (!registeredTimeZoneReceiver) return
            registeredTimeZoneReceiver = false
            this@FentonWatchFaceService.unregisterReceiver(timeZoneReceiver)
        }

        private fun registerSensor() {
            accelerometer?.let {
                sensorManager?.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME)
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
