package com.example.fluxrubber

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Erzeugt einen Stereo-Whoosh live: weißes Rauschen durch einen Zustandsvariablen-Filter,
 * danach Panning (Pegel), Laufzeitunterschied und Kopfschatten pro Ohr.
 * Lautstärke, Mittenfrequenz und Panorama (-1 links ... +1 rechts) kommen von außen.
 */
class WhooshSynth {

    private val sampleRate = 44100
    private val chunk = 512
    private val maxItdSamples = 28f      // ca. 0,63 ms Laufzeitunterschied

    @Volatile private var targetGain = 0f
    @Volatile private var targetFreq = 800f
    @Volatile private var targetPan = 0f
    @Volatile private var running = false
    private var thread: Thread? = null

    fun setParams(gain: Float, freq: Float, pan: Float) {
        targetGain = gain
        targetFreq = freq
        targetPan = pan
    }

    fun start() {
        if (running) return
        running = true
        thread = Thread({ runLoop() }, "WhooshSynth").apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    private fun runLoop() {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(max(minBuf, chunk * 2 * 2 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            running = false
            return
        }

        val buf = ShortArray(chunk * 2)
        val ring = FloatArray(64)
        val mask = 63
        var wpos = 0

        var gain = 0f
        var freq = 800f
        var pan = 0f
        var low = 0f
        var band = 0f
        var lpL = 0f
        var lpR = 0f
        var rng = 123456789
        var silent = 0
        val q = 0.35f   // Dämpfung des Filters (kleiner = schmalbandiger)
        val sqrt2 = 1.4142135f

        try {
            track.play()
            while (running) {
                val tg = targetGain
                val tf = targetFreq
                val tp = targetPan

                for (i in 0 until chunk) {
                    // Glättung: schneller Anstieg, etwas langsameres Abklingen
                    gain += (tg - gain) * (if (tg > gain) 0.004f else 0.001f)
                    freq += (tf - freq) * 0.002f
                    pan += (tp - pan) * 0.002f

                    // Rauschen (Xorshift) im Bereich -1..1
                    rng = rng xor (rng shl 13)
                    rng = rng xor (rng ushr 17)
                    rng = rng xor (rng shl 5)
                    val noise = rng / 2147483648f

                    // Zustandsvariablen-Filter (Chamberlin)
                    val f = 2f * sin(PI.toFloat() * freq / sampleRate)
                    low += f * band
                    val high = noise - low - q * band
                    band += f * high

                    // Pegelausgleich, damit hohe und tiefe Töne gleich laut sind
                    val norm = sqrt(40000f / freq)
                    val mono = (band * 0.8f + low * 0.3f) * norm * gain

                    // Mono-Signal in den Ring schreiben (für Laufzeitunterschied)
                    ring[wpos and mask] = mono

                    // Laufzeit: das vom Schall abgewandte Ohr hört später
                    val dL = max(pan, 0f) * maxItdSamples
                    val dR = max(-pan, 0f) * maxItdSamples
                    val diL = dL.toInt()
                    val fcL = dL - diL
                    val diR = dR.toInt()
                    val fcR = dR - diR
                    val sL = ring[(wpos - diL) and mask] * (1f - fcL) +
                            ring[(wpos - diL - 1) and mask] * fcL
                    val sR = ring[(wpos - diR) and mask] * (1f - fcR) +
                            ring[(wpos - diR - 1) and mask] * fcR
                    wpos = (wpos + 1) and mask

                    // Pegel: Equal-Power-Panning (Mitte = 1,0)
                    val theta = (pan + 1f) * (PI.toFloat() / 4f)
                    val gl = cos(theta) * sqrt2
                    val gr = sin(theta) * sqrt2

                    // Kopfschatten: abgewandtes Ohr dumpfer
                    val ap = abs(pan)
                    val aFar = 1f - 0.75f * ap
                    val aL = if (pan > 0f) aFar else 1f
                    val aR = if (pan < 0f) aFar else 1f
                    lpL += aL * (sL * gl - lpL)
                    lpR += aR * (sR * gr - lpR)

                    buf[2 * i] = (lpL / (1f + abs(lpL)) * 32000f).toInt().toShort()
                    buf[2 * i + 1] = (lpR / (1f + abs(lpR)) * 32000f).toInt().toShort()
                }

                val w = track.write(buf, 0, chunk * 2)
                if (w < 0) {
                    running = false
                    break
                }

                // Bei längerer Stille pausieren, um Strom zu sparen
                if (gain < 0.0005f && tg < 0.0005f) silent += chunk else silent = 0
                if (silent > sampleRate * 2) {
                    track.pause()
                    track.flush()
                    while (running && targetGain < 0.01f) Thread.sleep(25)
                    if (!running) break
                    low = 0f
                    band = 0f
                    lpL = 0f
                    lpR = 0f
                    track.play()
                    silent = 0
                }
            }
        } catch (e: Exception) {
            // Audio nicht verfügbar: still beenden
        } finally {
            try {
                track.release()
            } catch (e: Exception) {
            }
        }
    }
}