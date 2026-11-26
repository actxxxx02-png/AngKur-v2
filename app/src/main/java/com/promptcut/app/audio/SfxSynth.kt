package com.promptcut.app.audio

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

enum class SfxType(val key: String, val label: String, val durationSec: Double) {
    WHOOSH("whoosh", "Whoosh", 0.55),
    IMPACT("impact", "Impact Hit", 0.70),
    POP("pop", "UI Pop", 0.22),
    RISER("riser", "Riser Swell", 1.35),
    CHIME("chime", "Bright Chime", 0.90),
    CLICK("click", "Soft Click", 0.10),
    TICK("tick", "Tick", 0.08),
    CAMERA_SHUTTER("camera_shutter", "Camera Shutter", 0.24),
    FILM_STOP("film_stop", "Film Stop", 0.42),
    NOTIFICATION("notification", "Notification", 0.36),
    SUCCESS("success", "Success", 0.65),
    ERROR("error", "Error", 0.32),
    HEARTBEAT("heartbeat", "Heartbeat", 1.20),
    THUNDER("thunder", "Thunder", 1.80),
    RAIN("rain", "Rain Ambience", 2.50),
    WIND("wind", "Wind Ambience", 2.00),
    CROWD_CHEER("crowd_cheer", "Crowd Cheer", 1.80),
    LAUGHTER("laughter", "Laughter", 1.10),
    BUBBLE("bubble", "Bubble", 0.48),
    MAGIC("magic", "Magic Sparkle", 0.90),
    LASER("laser", "Laser Zap", 0.38),
    GAME_LEVEL_UP("game_level_up", "Game Level Up", 0.90),
    ;


    companion object {
        fun fromKey(raw: String): SfxType {
            val k = raw.trim().lowercase()
            return entries.firstOrNull { it.key == k || k.contains(it.key) } ?: WHOOSH
        }
    }
}

/**
 * Deterministic PCM sound-effect synthesizer producing clean stereo WAV files for
 * timeline SFX tracks, audio preview, and multi-track MediaMuxer/MusicMux export.
 */
object SfxSynth {
    const val SR = 44100
    private const val TWO_PI = 2.0 * PI

    fun synthesizePcm(type: SfxType, sampleRate: Int = SR): FloatArray {
        val frames = (type.durationSec * sampleRate).toInt().coerceAtLeast(1)
        val out = FloatArray(frames * 2)
        val rnd = Random(type.ordinal * 31337 + 7)
        var noiseState = 0.0
        var phase = 0.0

        for (i in 0 until frames) {
            val t = i.toDouble() / sampleRate
            val norm = (i.toDouble() / frames).coerceIn(0.0, 1.0)
            var left = 0.0
            var right = 0.0

            when (type) {
                SfxType.WHOOSH -> {
                    // Filtered noise sweep + stereo Doppler pan
                    val white = rnd.nextDouble(-1.0, 1.0)
                    val lpAlpha = 0.04 + 0.35 * sin(PI * norm)
                    noiseState += (white - noiseState) * lpAlpha
                    val env = sin(PI * norm).pow(1.4)
                    val pan = (norm - 0.5) * 0.75
                    val tone = sin(TWO_PI * (140.0 + 380.0 * sin(PI * norm)) * t) * 0.12
                    val sig = (noiseState * 0.85 + tone) * env
                    left = sig * (1.0 - pan * 0.5)
                    right = sig * (1.0 + pan * 0.5)
                }
                SfxType.IMPACT -> {
                    // Sub-bass punch drop + crisp transient click
                    val freq = 38.0 + 170.0 * exp(-t * 24.0)
                    phase += TWO_PI * freq / sampleRate
                    val sub = sin(phase) * exp(-t * 5.2)
                    val click = rnd.nextDouble(-1.0, 1.0) * exp(-t * 55.0) * 0.45
                    val attack = min(1.0, t / 0.003)
                    val sig = (sub * 0.9 + click) * attack
                    left = sig
                    right = sig
                }
                SfxType.POP -> {
                    // Quick pitch-bent bubble pop
                    val freq = 320.0 + 520.0 * (1.0 - norm).pow(2.0)
                    phase += TWO_PI * freq / sampleRate
                    val env = min(1.0, t / 0.004) * exp(-t * 22.0)
                    val sig = sin(phase) * env * 0.85
                    left = sig * 0.96
                    right = sig * 1.04
                }
                SfxType.RISER -> {
                    // Exponential rising harmonic tension swell + air rush
                    val freq = 110.0 * (2.0.pow(norm * 2.4))
                    phase += TWO_PI * freq / sampleRate
                    val white = rnd.nextDouble(-1.0, 1.0)
                    noiseState += (white - noiseState) * (0.05 + 0.30 * norm)
                    val env = norm.pow(1.8) * (1.0 - max(0.0, (norm - 0.95) / 0.05))
                    val harm = 0.55 * sin(phase) + 0.25 * sin(phase * 2.01) + 0.20 * noiseState
                    left = harm * env * (0.9 + 0.1 * sin(t * 9.0))
                    right = harm * env * (0.9 - 0.1 * sin(t * 9.0))
                }
                SfxType.CHIME -> {
                    // Clean C5-G5 bell chord shimmer
                    val env = min(1.0, t / 0.005) * exp(-t * 3.8)
                    val c5 = sin(TWO_PI * 523.25 * t) * 0.50
                    val g5 = sin(TWO_PI * 783.99 * t) * 0.32 * exp(-t * 1.2)
                    val c6 = sin(TWO_PI * 1046.5 * t) * 0.18 * exp(-t * 2.0)
                    left = (c5 + g5 * 0.9 + c6 * 1.1) * env
                    right = (c5 + g5 * 1.1 + c6 * 0.9) * env
                }
                SfxType.CLICK, SfxType.TICK, SfxType.CAMERA_SHUTTER, SfxType.FILM_STOP,
                SfxType.NOTIFICATION, SfxType.SUCCESS, SfxType.ERROR, SfxType.HEARTBEAT,
                SfxType.THUNDER, SfxType.RAIN, SfxType.WIND, SfxType.CROWD_CHEER,
                SfxType.LAUGHTER, SfxType.BUBBLE, SfxType.MAGIC, SfxType.LASER,
                SfxType.GAME_LEVEL_UP -> {
                    val v = when (type) {
                        SfxType.CLICK -> sin(TWO_PI * 1800.0 * t) * exp(-t * 65.0)
                        SfxType.TICK -> sin(TWO_PI * 2300.0 * t) * exp(-t * 85.0)
                        SfxType.CAMERA_SHUTTER -> (sin(TWO_PI * 1450.0 * t) * exp(-t * 18.0) * 0.35) + (rnd.nextDouble(-1.0, 1.0) * exp(-t * 28.0) * 0.45)
                        SfxType.FILM_STOP -> sin(TWO_PI * (170.0 - 95.0 * norm) * t) * exp(-t * 5.0)
                        SfxType.NOTIFICATION -> sin(TWO_PI * 880.0 * t) * exp(-t * 7.0) * 0.6 + sin(TWO_PI * 1320.0 * t) * exp(-t * 9.0) * 0.3
                        SfxType.SUCCESS -> sin(TWO_PI * (523.25 + 392.0 * norm) * t) * exp(-t * 3.0) * 0.7
                        SfxType.ERROR -> sin(TWO_PI * (220.0 - 90.0 * norm) * t) * exp(-t * 10.0) * 0.7
                        SfxType.HEARTBEAT -> {
                            val beat = (t % 0.85)
                            val pulse = exp(-beat * 20.0) + 0.7 * exp(-abs(beat - 0.13) * 25.0)
                            sin(TWO_PI * 58.0 * t) * pulse * 0.8
                        }
                        SfxType.THUNDER -> (rnd.nextDouble(-1.0, 1.0) * exp(-t * 1.1) + sin(TWO_PI * 42.0 * t) * exp(-t * 2.0)) * 0.75
                        SfxType.RAIN -> rnd.nextDouble(-1.0, 1.0) * (0.18 + 0.12 * sin(TWO_PI * 0.7 * t)) * 0.45
                        SfxType.WIND -> {
                            noiseState += (rnd.nextDouble(-1.0, 1.0) - noiseState) * 0.025
                            noiseState * (0.45 + 0.35 * sin(PI * norm))
                        }
                        SfxType.CROWD_CHEER -> rnd.nextDouble(-1.0, 1.0) * (0.3 + 0.35 * sin(PI * norm)) * 0.5 + sin(TWO_PI * 180.0 * t) * 0.1
                        SfxType.LAUGHTER -> sin(TWO_PI * (310.0 + 80.0 * sin(TWO_PI * 3.0 * t)) * t) * (0.25 + 0.2 * sin(TWO_PI * 4.0 * t)) * 0.35
                        SfxType.BUBBLE -> sin(TWO_PI * (500.0 + 900.0 * norm) * t) * exp(-t * 6.0)
                        SfxType.MAGIC -> sin(TWO_PI * 900.0 * t) * exp(-t * 3.0) * (0.4 + 0.4 * sin(TWO_PI * 13.0 * t))
                        SfxType.LASER -> sin(TWO_PI * (1600.0 - 1100.0 * norm) * t) * exp(-t * 7.0)
                        SfxType.GAME_LEVEL_UP -> sin(TWO_PI * (440.0 + 660.0 * norm) * t) * exp(-t * 2.4) * 0.75
                        else -> 0.0
                    }
                    left = v
                    right = if (type == SfxType.RAIN || type == SfxType.WIND || type == SfxType.THUNDER || type == SfxType.CROWD_CHEER) v * 0.92 else v * 1.02
                }
            }

            out[2 * i] = softClip(left.toFloat())
            out[2 * i + 1] = softClip(right.toFloat())
        }
        return normalize(out)
    }

    fun writeWav(type: SfxType, outFile: File, sampleRate: Int = SR): File {
        outFile.parentFile?.mkdirs()
        val pcm = synthesizePcm(type, sampleRate)
        val frames = pcm.size / 2
        val dataBytes = frames * 4
        RandomAccessFile(outFile, "rw").use { raf ->
            raf.setLength(0)
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
            header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(sampleRate).putInt(sampleRate * 4).putShort(4).putShort(16)
            header.put("data".toByteArray()).putInt(dataBytes)
            raf.write(header.array())
            val buf = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (s in pcm) {
                buf.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            }
            raf.write(buf.array())
        }
        return outFile
    }

    fun ensureCachedWav(context: Context, type: SfxType): File {
        val dir = File(context.cacheDir, "sfx").apply { mkdirs() }
        val file = File(dir, "sfx_${type.key}.wav")
        if (!file.exists() || file.length() <= 44L) {
            writeWav(type, file)
        }
        return file
    }

    private fun normalize(buf: FloatArray): FloatArray {
        var peak = 1e-6f
        for (v in buf) peak = max(peak, abs(v))
        val gain = (0.88f / peak).coerceAtMost(2.5f)
        return FloatArray(buf.size) { i -> (buf[i] * gain).coerceIn(-1f, 1f) }
    }

    private fun softClip(x: Float): Float =
        if (abs(x) <= 0.85f) x else (x / abs(x)) * (0.85f + 0.15f * tanh((abs(x) - 0.85f) * 5f))
}
