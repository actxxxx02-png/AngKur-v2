package com.promptcut.app.render

import com.promptcut.app.Aspect
import com.promptcut.app.ExportResolution

enum class ExportPreset(val label: String, val resolution: ExportResolution, val fps: Int, val bitrateMbps: Int) {
    FAST_720P("720p Fast (Preview / Share)", ExportResolution.P720, 30, 5),
    STANDARD_1080P("1080p Standard HD", ExportResolution.P1080, 30, 8),
    SMOOTH_60FPS("1080p Smooth 60fps", ExportResolution.P1080, 60, 14),
}

data class ExportSettings(
    val resolution: ExportResolution = ExportResolution.P1080,
    val fps: Int = 30,
    val videoBitrateBps: Int = 8_000_000,
    val audioBitrateBps: Int = 192_000,
    val forceSafeMode: Boolean = false,
    val burnCaptions: Boolean = true,
) {
    fun outputDimensions(aspect: Aspect): Pair<Int, Int> {
        val short = resolution.shortEdge
        return when (aspect) {
            Aspect.PORTRAIT -> short to (short * 16 / 9)
            Aspect.LANDSCAPE -> (short * 16 / 9) to short
            Aspect.SQUARE -> short to short
        }
    }

    fun applyPreset(preset: ExportPreset): ExportSettings = copy(
        resolution = preset.resolution,
        fps = preset.fps,
        videoBitrateBps = preset.bitrateMbps * 1_000_000,
    )
}
