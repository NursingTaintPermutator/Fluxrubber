package com.example.fluxrubber

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.service.wallpaper.WallpaperService
import android.view.Choreographer
import android.view.SurfaceHolder
import java.util.Random
import kotlin.math.max
import kotlin.math.sqrt

class SeilWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = SeilEngine()

    inner class SeilEngine : Engine(), SensorEventListener, Choreographer.FrameCallback {

        // ---------- Parameter (zum Spielen) ----------
        private val n = 30                      // Seilpunkte
        private val iterations = 20             // Constraint-Durchläufe pro Teilschritt
        private val stiffness = 0.5f            // 1.0 = starr, kleiner = gummiartiger
        private val damping = 0.99995f          // pro Teilschritt (240 Hz); 1.0 = keine Dämpfung
        private val ballMassRatio = 6f          // Kugelmasse relativ zu einem Seilpunkt
        private val ropeLengthM = 0.25f         // Ruhelänge des Seils in Metern (Welt)
        private val ballRadiusM = 0.025f        // Kugelradius in Metern
        private val ropeWidthM = 0.003f         // Seildicke in Metern
        private val cameraDistanceM = 0.6f      // Kamera vor dem Display; kleiner = stärkere Perspektive
        private val screenRopeFraction = 0.29f  // Seil in Ruhe = Anteil der Bildschirmhöhe
        private val maxAccel = 60f              // Begrenzung gegen Ausreißer (m/s²)
        private val subDt = 1f / 240f
        private val floorZ = -1.0f              // Boden 1 m unter dem Aufhängepunkt
        private val starCount = 160
        private val gravity = 9.81f
        private val nearPlane = 0.1f

        // ---------- Parameter Sound ----------
        private val soundEnabled = true         // false = stumm
        private val soundVolume = 0.6f          // 0..1, zusätzlich zur Medienlautstärke
        private val soundRefSpeed = 1.2f        // Kugelgeschwindigkeit (m/s) für volle Lautstärke
        private val soundBaseFreq = 900f        // Grundton des Whooshs in Hz
        private val panAxis = 0                 // 0 = Handy-X (Kopfhörer, Hochformat), 1 = Handy-Y (Stereo-Lautsprecher im Querformat)
        private val panSign = 1f                // -1f dreht links/rechts um
        private val panStrength = 1.6f          // Stärke des Schwenks (1 = physikalisch, größer = deutlicher)
        // Größte mögliche Kugel-Skalierung (Kugel am nächsten an der Kamera)
        private val kNear = cameraDistanceM / max(cameraDistanceM - ropeLengthM, 0.1f)

        // ---------- Zustand Seil (Welt-Koordinaten in Metern, Ursprung = Aufhängepunkt) ----------
        private val px = FloatArray(n)
        private val py = FloatArray(n)
        private val pz = FloatArray(n)
        private val ox = FloatArray(n)
        private val oy = FloatArray(n)
        private val oz = FloatArray(n)
        private val invMass = FloatArray(n)
        private var restLen = ropeLengthM / (n - 1)

        // Bildschirmpositionen
        private val sx = FloatArray(n)
        private val sy = FloatArray(n)
        private val sk = FloatArray(n)

        private var width = 0
        private var height = 0
        private var cx = 0f
        private var cy = 0f
        private var pxPerM = 1000f

        // ---------- Sensoren ----------
        private val rot = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        private var devAx = 0f
        private var devAy = 0f
        private var devAz = 0f
        private var useLinear = true
        private var effX = 0f
        private var effY = 0f
        private var effZ = -9.81f

        private val sensorManager =
            getSystemService(Context.SENSOR_SERVICE) as SensorManager
        private val rotSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        private val linSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        private val accSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        // ---------- Sound ----------
        private val synth = WhooshSynth()
        private var prevBallDepth = -1f

        // ---------- Zeitsteuerung ----------
        private var lastFrameNanos = 0L
        private var accumulator = 0f
        private var running = false

        // ---------- Hintergrund ----------
        private val starDir = FloatArray(starCount * 3)
        private val starAlpha = IntArray(starCount)
        private val starSize = FloatArray(starCount)
        private val floorSegs: FloatArray = buildFloor()

        // ---------- Zeichenwerkzeuge ----------
        private val bgColor = Color.rgb(8, 8, 14)
        private val ropePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(255, 220, 0)
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val anchorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(255, 220, 0)
        }
        private val ballPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
        }
        private val floorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(60, 120, 160, 255)
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }

        // Zwischenspeicher für die Projektion (vermeidet Objekte pro Frame)
        private var camX = 0f
        private var camY = 0f
        private var camD = 0f

        init {
            // Einheitskugel-Verlauf; wird beim Zeichnen per Canvas skaliert
            ballPaint.shader = RadialGradient(
                -0.3f, -0.3f, 1.4f,
                intArrayOf(Color.rgb(255, 245, 160), Color.rgb(255, 200, 0), Color.rgb(150, 100, 0)),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            val rnd = Random(42)
            for (i in 0 until starCount) {
                var vx = rnd.nextGaussian().toFloat()
                var vy = rnd.nextGaussian().toFloat()
                var vz = rnd.nextGaussian().toFloat()
                val len = sqrt(vx * vx + vy * vy + vz * vz)
                if (len == 0f) {
                    vx = 1f
                    vy = 0f
                    vz = 0f
                } else {
                    vx /= len
                    vy /= len
                    vz /= len
                }
                starDir[i * 3] = vx
                starDir[i * 3 + 1] = vy
                starDir[i * 3 + 2] = vz
                starAlpha[i] = 80 + rnd.nextInt(150)
                starSize[i] = 1f + rnd.nextFloat() * 2f
            }
        }

        private fun buildFloor(): FloatArray {
            val lines = 17
            val half = 4f
            val step = 0.5f
            val a = FloatArray(lines * 2 * 6)
            var o = 0
            for (j in 0 until lines) {
                val c = -half + j * step
                // Linie parallel zur X-Achse
                a[o++] = -half; a[o++] = c; a[o++] = floorZ
                a[o++] = half; a[o++] = c; a[o++] = floorZ
                // Linie parallel zur Y-Achse
                a[o++] = c; a[o++] = -half; a[o++] = floorZ
                a[o++] = c; a[o++] = half; a[o++] = floorZ
            }
            return a
        }

        // ---------- Lebenszyklus ----------
        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
            super.onSurfaceChanged(holder, format, w, h)
            width = w
            height = h
            cx = w / 2f
            cy = h / 2f
            pxPerM = screenRopeFraction * h / ropeLengthM
            restLen = ropeLengthM / (n - 1)
            resetRope()
        }

        private fun resetRope() {
            for (i in 0 until n) {
                px[i] = 0f
                py[i] = 0f
                pz[i] = -i * restLen      // hängt nach unten (Welt -Z)
                ox[i] = px[i]
                oy[i] = py[i]
                oz[i] = pz[i]
                invMass[i] = 1f
            }
            invMass[0] = 0f                       // Aufhängung fest im Ursprung
            invMass[n - 1] = 1f / ballMassRatio   // träge Kugel
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
            rotSensor?.let {
                sensorManager.registerListener(this, it, 10_000)
            }
            if (linSensor != null) {
                useLinear = true
                sensorManager.registerListener(this, linSensor, 10_000)
            } else if (accSensor != null) {
                useLinear = false
                sensorManager.registerListener(this, accSensor, 10_000)
            }
            prevBallDepth = -1f
            if (soundEnabled) synth.start()
            lastFrameNanos = 0L
            accumulator = 0f
            Choreographer.getInstance().postFrameCallback(this)
        }

        private fun stop() {
            if (!running) return
            running = false
            sensorManager.unregisterListener(this)
            Choreographer.getInstance().removeFrameCallback(this)
            synth.setParams(0f, 800f, 0f)
            synth.stop()
        }

        // ---------- Sensoren ----------
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_GAME_ROTATION_VECTOR ->
                    SensorManager.getRotationMatrixFromVector(rot, event.values)
                Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_ACCELEROMETER -> {
                    devAx = event.values[0]
                    devAy = event.values[1]
                    devAz = event.values[2]
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

        // ---------- Frame ----------
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return

            if (lastFrameNanos != 0L) {
                var dt = (frameTimeNanos - lastFrameNanos) / 1_000_000_000f
                if (dt > 0.05f) dt = 0.05f
                accumulator += dt
                updateForces()
                while (accumulator >= subDt) {
                    simulate(subDt)
                    accumulator -= subDt
                }
                if (dt > 0f) updateSound(dt)
            }
            lastFrameNanos = frameTimeNanos

            draw()
            Choreographer.getInstance().postFrameCallback(this)
        }

        // Effektive Beschleunigung im mitbewegten Weltsystem: a = g_w - a_w
        private fun updateForces() {
            val r = rot
            val awx = r[0] * devAx + r[1] * devAy + r[2] * devAz
            val awy = r[3] * devAx + r[4] * devAy + r[5] * devAz
            var awz = r[6] * devAx + r[7] * devAy + r[8] * devAz
            if (!useLinear) awz -= gravity   // Rohwert des Beschleunigungssensors: Schwerkraftanteil abziehen

            var ex = -awx
            var ey = -awy
            var ez = -gravity - awz

            val len = sqrt(ex * ex + ey * ey + ez * ez)
            if (len > maxAccel) {
                val s = maxAccel / len
                ex *= s
                ey *= s
                ez *= s
            }
            effX = ex
            effY = ey
            effZ = ez
        }

        // ---------- Sound: Vorbeiflug der Kugel an der Kamera ----------
        private fun updateSound(dt: Float) {
            if (!soundEnabled) return
            val b = n - 1
            toCam(px[b], py[b], pz[b])
            val depth = max(camD, 0.05f)
            val k = cameraDistanceM / depth              // Skalierung der Kugel (1 = Ruhegröße)

            // Richtung zur Kugel aus Sicht der Kamera (Hörposition): Panorama
            val dist = sqrt(camX * camX + camY * camY + camD * camD)
            val lateral = if (panAxis == 0) camX else camY
            val pan = (panSign * panStrength * lateral / max(dist, 0.05f)).coerceIn(-1f, 1f)

            // Geschwindigkeit der Kugel relativ zum Handy (letzter Teilschritt)
            val vx = (px[b] - ox[b]) / subDt
            val vy = (py[b] - oy[b]) / subDt
            val vz = (pz[b] - oz[b]) / subDt
            val speed = sqrt(vx * vx + vy * vy + vz * vz)
            val speedN = (speed / soundRefSpeed).coerceIn(0f, 1f)

            // Nähe: 0 bei Ruhegröße oder kleiner, 1 bei größter Annäherung
            val closeness = ((k - 1f) / (kNear - 1f)).coerceIn(0f, 1f)
            val gain = closeness * sqrt(closeness) * speedN * soundVolume

            // Annäherungsgeschwindigkeit (positiv = kommt näher) für den Doppler-Effekt
            val vr = if (prevBallDepth > 0f) (prevBallDepth - depth) / dt else 0f
            prevBallDepth = depth
            val vrN = (vr / soundRefSpeed).coerceIn(-1f, 1f)

            val freq = soundBaseFreq * (0.6f + 0.8f * speedN) * (1f + 0.5f * vrN)
            synth.setParams(gain, freq, pan)
        }

        // ---------- Physik ----------
        private fun simulate(dt: Float) {
            val dt2 = dt * dt

            // Verlet-Integration (Masse und Trägheit)
            for (i in 1 until n) {
                val vx = (px[i] - ox[i]) * damping
                val vy = (py[i] - oy[i]) * damping
                val vz = (pz[i] - oz[i]) * damping
                ox[i] = px[i]
                oy[i] = py[i]
                oz[i] = pz[i]
                px[i] += vx + effX * dt2
                py[i] += vy + effY * dt2
                pz[i] += vz + effZ * dt2
            }

            // Seilspannung lösen, abwechselnd von oben nach unten und zurück
            for (iter in 0 until iterations) {
                px[0] = 0f
                py[0] = 0f
                pz[0] = 0f
                if (iter % 2 == 0) {
                    for (i in 0 until n - 1) solveLink(i)
                } else {
                    for (i in n - 2 downTo 0) solveLink(i)
                }
            }
            px[0] = 0f
            py[0] = 0f
            pz[0] = 0f
            ox[0] = 0f
            oy[0] = 0f
            oz[0] = 0f
        }

        private fun solveLink(i: Int) {
            val dx = px[i + 1] - px[i]
            val dy = py[i + 1] - py[i]
            val dz = pz[i + 1] - pz[i]
            val dist = sqrt(dx * dx + dy * dy + dz * dz)
            if (dist == 0f) return
            val wSum = invMass[i] + invMass[i + 1]
            if (wSum == 0f) return
            val diff = (dist - restLen) / dist * stiffness / wSum
            px[i] += dx * diff * invMass[i]
            py[i] += dy * diff * invMass[i]
            pz[i] += dz * diff * invMass[i]
            px[i + 1] -= dx * diff * invMass[i + 1]
            py[i + 1] -= dy * diff * invMass[i + 1]
            pz[i + 1] -= dz * diff * invMass[i + 1]
        }

        // ---------- Kamera: Welt -> Handy-Koordinaten (Rᵀ) -> Tiefe ----------
        private fun toCam(wx: Float, wy: Float, wz: Float) {
            val r = rot
            camX = r[0] * wx + r[3] * wy + r[6] * wz
            camY = r[1] * wx + r[4] * wy + r[7] * wz
            camD = cameraDistanceM - (r[2] * wx + r[5] * wy + r[8] * wz)
        }

        // ---------- Zeichnen ----------
        private fun draw() {
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    holder.lockHardwareCanvas()
                } else {
                    holder.lockCanvas()
                }
                if (canvas != null) drawScene(canvas)
            } catch (e: Exception) {
                // Surface gerade ungültig: Frame überspringen
            } finally {
                if (canvas != null) holder.unlockCanvasAndPost(canvas)
            }
        }

        private fun drawScene(canvas: Canvas) {
            canvas.drawColor(bgColor)
            drawStars(canvas)
            drawFloor(canvas)
            drawRope(canvas)
        }

        private fun drawStars(canvas: Canvas) {
            val r = rot
            val focal = pxPerM * cameraDistanceM
            for (i in 0 until starCount) {
                val vx = starDir[i * 3]
                val vy = starDir[i * 3 + 1]
                val vz = starDir[i * 3 + 2]
                val dx = r[0] * vx + r[3] * vy + r[6] * vz
                val dy = r[1] * vx + r[4] * vy + r[7] * vz
                val dz = r[2] * vx + r[5] * vy + r[8] * vz
                if (dz > -0.1f) continue
                val k = focal / (-dz)
                val x = cx + dx * k
                val y = cy - dy * k
                if (x < 0f || x > width || y < 0f || y > height) continue
                starPaint.alpha = starAlpha[i]
                canvas.drawCircle(x, y, starSize[i], starPaint)
            }
        }

        private fun drawFloor(canvas: Canvas) {
            var o = 0
            while (o < floorSegs.size) {
                toCam(floorSegs[o], floorSegs[o + 1], floorSegs[o + 2])
                var x1 = camX
                var y1 = camY
                var d1 = camD
                toCam(floorSegs[o + 3], floorSegs[o + 4], floorSegs[o + 5])
                var x2 = camX
                var y2 = camY
                var d2 = camD
                o += 6

                if (d1 < nearPlane && d2 < nearPlane) continue
                if (d1 < nearPlane) {
                    val t = (nearPlane - d1) / (d2 - d1)
                    x1 += t * (x2 - x1)
                    y1 += t * (y2 - y1)
                    d1 = nearPlane
                } else if (d2 < nearPlane) {
                    val t = (nearPlane - d2) / (d1 - d2)
                    x2 += t * (x1 - x2)
                    y2 += t * (y1 - y2)
                    d2 = nearPlane
                }
                val k1 = cameraDistanceM / d1
                val k2 = cameraDistanceM / d2
                canvas.drawLine(
                    cx + pxPerM * x1 * k1, cy - pxPerM * y1 * k1,
                    cx + pxPerM * x2 * k2, cy - pxPerM * y2 * k2,
                    floorPaint
                )
            }
        }

        private fun drawRope(canvas: Canvas) {
            for (i in 0 until n) {
                toCam(px[i], py[i], pz[i])
                val depth = max(camD, 0.05f)
                val k = cameraDistanceM / depth
                sx[i] = cx + pxPerM * camX * k
                sy[i] = cy - pxPerM * camY * k
                sk[i] = k
            }

            for (i in 0 until n - 1) {
                ropePaint.strokeWidth = ropeWidthM * pxPerM * 0.5f * (sk[i] + sk[i + 1])
                canvas.drawLine(sx[i], sy[i], sx[i + 1], sy[i + 1], ropePaint)
            }

            // Aufhängepunkt
            canvas.drawCircle(cx, cy, 0.005f * pxPerM, anchorPaint)

            // Kugel
            val b = n - 1
            val radius = ballRadiusM * pxPerM * sk[b]
            canvas.save()
            canvas.translate(sx[b], sy[b])
            canvas.scale(radius, radius)
            canvas.drawCircle(0f, 0f, 1f, ballPaint)
            canvas.restore()
        }
    }
}