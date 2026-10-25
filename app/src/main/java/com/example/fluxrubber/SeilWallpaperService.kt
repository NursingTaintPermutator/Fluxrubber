package com.example.fluxrubber

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.service.wallpaper.WallpaperService
import android.view.Choreographer
import android.view.SurfaceHolder
import kotlin.math.sqrt

class SeilWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = SeilEngine()

    inner class SeilEngine : Engine(), SensorEventListener, Choreographer.FrameCallback {

        // ---------- Parameter (zum Spielen) ----------
        private val n = 40                 // Seilpunkte
        private val iterations = 6         // weniger = weicher/gummiartiger
        private val stiffness = 0.35f      // 1.0 = starres Seil, kleiner = dehnbar
        private val damping = 0.9995f      // pro Teilschritt (240 Hz)
        private val ballMassRatio = 8f     // Kugel vs. Seilpunkt
        private val ropeLengthFactor = 0.22f   // Seillänge in Bruchteilen der Bildschirmhöhe
        private val ballRadiusFactor = 0.07f   // Kugelradius in Bruchteilen der Bildschirmbreite
        private val restitution = 0.5f     // Abprallen am Bildschirmrand
        private val subDt = 1f / 240f

        // ---------- Zustand ----------
        private val x = FloatArray(n)
        private val y = FloatArray(n)
        private val ox = FloatArray(n)
        private val oy = FloatArray(n)
        private val invMass = FloatArray(n)

        private var width = 0
        private var height = 0
        private var anchorX = 0f
        private var anchorY = 0f
        private var restLen = 1f
        private var ballR = 30f
        private var pxPerMeter = 10000f

        // Sensorwerte (m/s², Geräte-Koordinaten)
        @Volatile private var sensorX = 0f
        @Volatile private var sensorY = 9.81f

        private var lastFrameNanos = 0L
        private var accumulator = 0f
        private var running = false

        private val sensorManager =
            getSystemService(Context.SENSOR_SERVICE) as SensorManager
        private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        // ---------- Zeichenwerkzeuge ----------
        private val ropePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(255, 220, 0)
            style = Paint.Style.STROKE
            strokeWidth = 8f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val anchorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(255, 220, 0)
        }
        private val ballPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
            super.onSurfaceChanged(holder, format, w, h)
            width = w
            height = h
            pxPerMeter = resources.displayMetrics.ydpi / 0.0254f
            anchorX = w / 2f
            anchorY = h / 2f
            ballR = w * ballRadiusFactor
            ropePaint.strokeWidth = w * 0.008f
            restLen = (h * ropeLengthFactor) / (n - 1)
            resetRope()
        }

        private fun resetRope() {
            for (i in 0 until n) {
                x[i] = anchorX
                y[i] = anchorY + i * restLen
                ox[i] = x[i]
                oy[i] = y[i]
                invMass[i] = 1f
            }
            invMass[0] = 0f                       // Aufhängung fest am Handy
            invMass[n - 1] = 1f / ballMassRatio   // schwere Kugel
        }

        override fun onVisibilityChanged(visible: Boolean) {
            if (visible) start() else stop()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            stop()
        }

        override fun onDestroy() {
            super.onDestroy()
            stop()
        }

        private fun start() {
            if (running) return
            running = true
            accelerometer?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
            lastFrameNanos = 0L
            accumulator = 0f
            Choreographer.getInstance().postFrameCallback(this)
        }

        private fun stop() {
            if (!running) return
            running = false
            sensorManager.unregisterListener(this)
            Choreographer.getInstance().removeFrameCallback(this)
        }

        override fun onSensorChanged(event: SensorEvent) {
            sensorX = event.values[0]
            sensorY = event.values[1]
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return

            if (lastFrameNanos != 0L) {
                var dt = (frameTimeNanos - lastFrameNanos) / 1_000_000_000f
                if (dt > 0.05f) dt = 0.05f
                accumulator += dt
                while (accumulator >= subDt) {
                    simulate(subDt)
                    accumulator -= subDt
                }
            }
            lastFrameNanos = frameTimeNanos

            draw()
            Choreographer.getInstance().postFrameCallback(this)
        }

        // ---------- Physik ----------
        private fun simulate(dt: Float) {
            // Pseudo-Schwerkraft im Handy-Bezugssystem: a = -f
            val ax = -sensorX * pxPerMeter
            val ay = sensorY * pxPerMeter
            val dt2 = dt * dt

            // Verlet-Integration (Masse & Trägheit)
            for (i in 1 until n) {
                val vx = (x[i] - ox[i]) * damping
                val vy = (y[i] - oy[i]) * damping
                ox[i] = x[i]
                oy[i] = y[i]
                x[i] += vx + ax * dt2
                y[i] += vy + ay * dt2
            }

            // Seilspannung lösen (gewichtet nach Masse, weich = Gummi)
            for (it in 0 until iterations) {
                x[0] = anchorX
                y[0] = anchorY
                for (i in 0 until n - 1) {
                    val dx = x[i + 1] - x[i]
                    val dy = y[i + 1] - y[i]
                    val dist = sqrt(dx * dx + dy * dy)
                    if (dist == 0f) continue
                    val wSum = invMass[i] + invMass[i + 1]
                    if (wSum == 0f) continue
                    val diff = (dist - restLen) / dist * stiffness / wSum
                    x[i] += dx * diff * invMass[i]
                    y[i] += dy * diff * invMass[i]
                    x[i + 1] -= dx * diff * invMass[i + 1]
                    y[i + 1] -= dy * diff * invMass[i + 1]
                }
            }
            x[0] = anchorX
            y[0] = anchorY

            collideBallWithScreen()
        }

        private fun collideBallWithScreen() {
            val b = n - 1
            if (x[b] < ballR) {
                val v = x[b] - ox[b]
                x[b] = ballR
                ox[b] = x[b] + v * restitution
            } else if (x[b] > width - ballR) {
                val v = x[b] - ox[b]
                x[b] = width - ballR
                ox[b] = x[b] + v * restitution
            }
            if (y[b] < ballR) {
                val v = y[b] - oy[b]
                y[b] = ballR
                oy[b] = y[b] + v * restitution
            } else if (y[b] > height - ballR) {
                val v = y[b] - oy[b]
                y[b] = height - ballR
                oy[b] = y[b] + v * restitution
            }
        }

        // ---------- Zeichnen ----------
        private fun draw() {
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas != null) drawScene(canvas)
            } finally {
                if (canvas != null) holder.unlockCanvasAndPost(canvas)
            }
        }

        private fun drawScene(canvas: Canvas) {
            canvas.drawColor(Color.rgb(12, 12, 16))

            path.reset()
            path.moveTo(x[0], y[0])
            for (i in 1 until n) path.lineTo(x[i], y[i])
            canvas.drawPath(path, ropePaint)

            canvas.drawCircle(anchorX, anchorY, width * 0.01f, anchorPaint)

            val bx = x[n - 1]
            val by = y[n - 1]
            ballPaint.shader = RadialGradient(
                bx - ballR * 0.3f, by - ballR * 0.3f, ballR * 1.4f,
                intArrayOf(Color.rgb(255, 245, 160), Color.rgb(255, 200, 0), Color.rgb(150, 100, 0)),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(bx, by, ballR, ballPaint)
        }
    }
}