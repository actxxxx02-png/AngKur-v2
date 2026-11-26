package com.promptcut.app

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.RgbMatrix
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Constant volume for one audio stream (clip sound or music). */
@OptIn(UnstableApi::class)
class GainProcessor(private val gain: Float) : BaseAudioProcessor() {
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return if (gain == 1f) AudioProcessor.AudioFormat.NOT_SET else inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        val out = replaceOutputBuffer(size)
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
            while (inputBuffer.remaining() >= 4) out.putFloat(inputBuffer.float * gain)
        } else {
            while (inputBuffer.remaining() >= 2) {
                out.putShort((inputBuffer.short * gain).toInt().coerceIn(-32768, 32767).toShort())
            }
        }
        out.flip()
    }
}

/** Fade in at the start and out at the end of the whole mix, so the video doesn't end with a hard cut. */
@OptIn(UnstableApi::class)
class FadeProcessor(private val totalSec: Double, private val fadeIn: Double = 0.25, private val fadeOut: Double = 1.4) : BaseAudioProcessor() {
    private var frames = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun onFlush() { frames = 0 }
    override fun onReset() { frames = 0 }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val fmt = inputAudioFormat
        val isFloat = fmt.encoding == C.ENCODING_PCM_FLOAT
        val bytesPerFrame = fmt.bytesPerFrame
        val size = inputBuffer.remaining()
        val out = replaceOutputBuffer(size)
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        while (inputBuffer.remaining() >= bytesPerFrame) {
            val t = frames.toDouble() / fmt.sampleRate
            val g = (minOf(1.0, t / fadeIn, (totalSec - t) / fadeOut)).coerceIn(0.0, 1.0).toFloat()
            for (c in 0 until fmt.channelCount) {
                if (isFloat) out.putFloat(inputBuffer.float * g)
                else out.putShort((inputBuffer.short * g).toInt().toShort())
            }
            frames++
        }
        out.flip()
    }
}

/** Fades picture from/to black. Time is measured from the first frame this item sees. */
@OptIn(UnstableApi::class)
class FadeEffect(private val lengthSec: Double, private val fadeIn: Double, private val fadeOut: Double) : RgbMatrix {
    private var firstUs = Long.MIN_VALUE

    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
        if (firstUs == Long.MIN_VALUE || presentationTimeUs < firstUs) firstUs = presentationTimeUs
        val t = (presentationTimeUs - firstUs) / 1_000_000.0
        var g = 1.0
        if (fadeIn > 0) g = minOf(g, t / fadeIn)
        if (fadeOut > 0) g = minOf(g, (lengthSec - t) / fadeOut)
        val s = g.coerceIn(0.0, 1.0).toFloat()
        return floatArrayOf(
            s, 0f, 0f, 0f,
            0f, s, 0f, 0f,
            0f, 0f, s, 0f,
            0f, 0f, 0f, 1f,
        )
    }
}

/**
 * Fades a shot in from a colour and out to a colour (black, or white for a flash). Time is local to the shot,
 * measured from the first frame it sees, so it works inside a sequence of many shots.
 */
@OptIn(UnstableApi::class)
class DipEffect(
    private val lengthSec: Double,
    private val inSec: Double,
    private val outSec: Double,
    private val inWhite: Boolean = false,
    private val outWhite: Boolean = false,
) : RgbMatrix {
    private var firstUs = Long.MIN_VALUE

    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
        if (firstUs == Long.MIN_VALUE || presentationTimeUs < firstUs) firstUs = presentationTimeUs
        val t = (presentationTimeUs - firstUs) / 1_000_000.0
        var s = 1.0          // how much of the picture shows
        var white = false
        if (inSec > 0 && t < inSec) { s = ease(t / inSec); white = inWhite }
        if (outSec > 0 && t > lengthSec - outSec) {
            val o = ease(((lengthSec - t) / outSec).coerceIn(0.0, 1.0))
            if (o < s) { s = o; white = outWhite }
        }
        val k = s.coerceIn(0.0, 1.0).toFloat()
        val off = if (white) 1f - k else 0f  // shader computes M * (r,g,b,1): the last column adds white
        return floatArrayOf(
            k, 0f, 0f, 0f,
            0f, k, 0f, 0f,
            0f, 0f, k, 0f,
            off, off, off, 1f,
        )
    }
}

/**
 * Camera motion inside a shot: a quick "punch" zoom at the start (energy), and/or a slow Ken Burns drift
 * across the whole shot (smooth, cinematic). Keeps the frame size; zooming only ever crops in.
 */
@OptIn(UnstableApi::class)
class MotionEffect(
    private val lengthSec: Double,
    private val punch: Float = 0f,          // e.g. 0.12 = start 12% zoomed in, settle in 0.35 s
    private val driftFrom: Float = 1f,
    private val driftTo: Float = 1f,
    private val panX: Float = 0f,           // gentle sideways drift in NDC units (-0.05..0.05)
    private val pivotX: Float = 0f,         // zoom around this point (NDC, -1..1): the face, so it never slides out
) : MatrixTransformation {
    private var firstUs = Long.MIN_VALUE

    override fun getMatrix(presentationTimeUs: Long): android.graphics.Matrix {
        if (firstUs == Long.MIN_VALUE || presentationTimeUs < firstUs) firstUs = presentationTimeUs
        val t = (presentationTimeUs - firstUs) / 1_000_000.0
        val p = (t / lengthSec).coerceIn(0.0, 1.0).toFloat()
        var scale = driftFrom + (driftTo - driftFrom) * p
        if (punch > 0f && t < 0.35) scale += punch * (1f - ease(t / 0.35).toFloat())
        val m = android.graphics.Matrix()
        // Zooming around the face keeps it in place; the pivot is limited so the frame edges stay covered.
        m.setScale(scale, scale, pivotX, 0f)
        val maxPan = (scale - 1f).coerceAtLeast(0f)
        if (pivotX == 0f) m.postTranslate((panX * (p - 0.5f) * 2f).coerceIn(-maxPan, maxPan), 0f)
        return m
    }
}

private fun ease(x: Double): Double { val c = x.coerceIn(0.0, 1.0); return c * c * (3 - 2 * c) }

@OptIn(UnstableApi::class)
object Transitions {
    /** Effects for shot [i] of [n] with the given style. */
    fun effects(style: Transition, i: Int, n: Int, lengthSec: Double, faceX: Float? = null,
                joinIn: Boolean = false, joinOut: Boolean = false): List<Effect> {
        // Continuing the same clip: no dip, punch or zoom at the join.
        if (joinIn || joinOut) {
            val list = mutableListOf<Effect>()
            val inSec = if (i == 0) 0.5 else if (joinIn) 0.0 else 0.3
            val outSec = if (i == n - 1) 0.9 else if (joinOut) 0.0 else 0.3
            if (style != Transition.CUT && style != Transition.AUTO || i == 0 || i == n - 1) list += DipEffect(lengthSec, inSec, outSec)
            // No zoom drift either: a scale jump at the join would look like a glitch.
            return list
        }
        // With a face in the shot: zoom around it, and zoom less, so it is never cut off.
        val pivot = faceX?.coerceIn(-1f, 1f) ?: 0f
        val drift = if (faceX != null) 1.04f else 1.08f
        val punchMul = if (faceX != null) 0.6f else 1f
        val first = i == 0
        val last = i == n - 1
        val shortShot = lengthSec < 1.2
        val list = mutableListOf<Effect>()
        when (style) {
            Transition.SMOOTH -> {
                val d = if (shortShot) 0.2 else 0.45
                list += DipEffect(lengthSec, if (first) 0.5 else d, if (last) 0.9 else d)
                val zoomIn = i % 2 == 0
                list += MotionEffect(lengthSec, driftFrom = if (zoomIn) 1.0f else drift, driftTo = if (zoomIn) drift else 1.0f,
                    panX = if (i % 3 == 0) 0.03f else -0.03f, pivotX = pivot)
            }
            Transition.FADE -> {
                val d = if (shortShot) 0.15 else 0.3
                list += DipEffect(lengthSec, if (first) 0.5 else d, if (last) 0.9 else d)
                list += MotionEffect(lengthSec, driftFrom = 1.0f, driftTo = if (faceX != null) 1.03f else 1.05f, pivotX = pivot)
            }
            Transition.FLASH -> {
                list += DipEffect(lengthSec, if (first) 0.3 else 0.14, if (last) 0.8 else 0.0, inWhite = !first)
                if (!first) list += MotionEffect(lengthSec, punch = 0.08f * punchMul, pivotX = pivot)
            }
            Transition.ZOOM -> {
                list += DipEffect(lengthSec, if (first) 0.3 else 0.06, if (last) 0.8 else 0.0)
                if (!first) list += MotionEffect(lengthSec, punch = 0.14f * punchMul, pivotX = pivot)
            }
            Transition.CUT, Transition.AUTO -> {
                if (first || last) list += DipEffect(lengthSec, if (first) 0.35 else 0.0, if (last) 0.8 else 0.0)
            }
        }
        return list
    }
}

/**
 * Saturation that SCALES existing colour around each pixel's own brightness, so grey stays grey.
 * (Media3's HSL control adds saturation to every pixel, which turns noise on grey roads into rainbow speckle.)
 */
@OptIn(UnstableApi::class)
class SaturationMatrix(private val s: Float) : RgbMatrix {
    private val m: FloatArray = run {
        val lr = 0.2126f; val lg = 0.7152f; val lb = 0.0722f
        val a = 1 - s
        // Column-major 4x4: out = M * (r, g, b, 1)
        floatArrayOf(
            a * lr + s, a * lr, a * lr, 0f,
            a * lg, a * lg + s, a * lg, 0f,
            a * lb, a * lb, a * lb + s, 0f,
            0f, 0f, 0f, 1f,
        )
    }
    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray = m
}

@OptIn(UnstableApi::class)
object Looks {
    fun effects(look: ColorLook): List<Effect> = when (look) {
        ColorLook.NATURAL -> emptyList()
        ColorLook.WARM -> listOf(RgbAdjustment.Builder().setRedScale(1.08f).setGreenScale(1.02f).setBlueScale(0.88f).build())
        ColorLook.COOL -> listOf(RgbAdjustment.Builder().setRedScale(0.92f).setGreenScale(1.0f).setBlueScale(1.08f).build())
        ColorLook.VIVID -> listOf(SaturationMatrix(1.28f), Contrast(0.06f))
        ColorLook.CINEMATIC -> listOf(
            SaturationMatrix(0.82f),
            Contrast(0.15f),
            RgbAdjustment.Builder().setRedScale(1.04f).setBlueScale(0.96f).build(),
        )
        ColorLook.BW -> listOf(RgbFilter.createGrayscaleFilter(), Contrast(0.1f))
    }
}
