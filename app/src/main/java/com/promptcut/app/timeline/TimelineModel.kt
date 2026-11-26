package com.promptcut.app.timeline

import com.promptcut.app.Aspect
import com.promptcut.app.ClipMeta
import com.promptcut.app.ColorLook
import com.promptcut.app.CropBox
import com.promptcut.app.EditPlan
import com.promptcut.app.ExportResolution
import com.promptcut.app.Mood
import com.promptcut.app.Segment
import com.promptcut.app.Transition
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToLong

const val US_PER_SEC = 1_000_000L

fun secToUs(sec: Double): Long = (sec * US_PER_SEC).roundToLong().coerceAtLeast(0L)
fun usToSec(us: Long): Double = us / 1_000_000.0

enum class KeyframeProperty(val label: String) {
    SCALE("Scale"),
    OPACITY("Opacity"),
    VOLUME("Volume"),
    PAN_X("Pan X"),
    PAN_Y("Pan Y"),
}

data class Keyframe(
    val timeOffsetUs: Long,
    val property: KeyframeProperty,
    val value: Float,
)

data class VideoClipItem(
    val id: String = UUID.randomUUID().toString(),
    val sourceClipIndex: Int,
    val sourceUri: String = "",
    val timelineStartUs: Long,
    val sourceStartUs: Long,
    val sourceEndUs: Long,
    val speed: Double = 1.0,
    val volume: Float = 1.0f,
    val muted: Boolean = false,
    val crop: CropBox? = null,
    val focusX: Double? = null,
    val transitionIn: Transition = Transition.CUT,
    val transitionDurationUs: Long = 350_000L,
    val colorLookOverride: ColorLook? = null,
    val keyframes: List<Keyframe> = emptyList(),
    val why: String = "",
    val editedByUser: Boolean = false,
) {
    val sourceDurationUs: Long get() = (sourceEndUs - sourceStartUs).coerceAtLeast(0L)
    val timelineDurationUs: Long get() = (sourceDurationUs / speed.coerceIn(0.25, 4.0)).roundToLong().coerceAtLeast(100_000L)
    val timelineEndUs: Long get() = timelineStartUs + timelineDurationUs
}

data class VideoTrack(
    val id: String = "video_main",
    val name: String = "Video 1",
    val isMain: Boolean = true,
    val muted: Boolean = false,
    val visible: Boolean = true,
    val items: List<VideoClipItem> = emptyList(),
) {
    val endUs: Long get() = items.maxOfOrNull { it.timelineEndUs } ?: 0L
}

enum class AudioTrackType(val label: String) {
    MUSIC("Music"),
    SFX("Sound FX"),
    VOICEOVER("Voiceover"),
}

data class AudioClipItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val sourceUri: String,
    val trackType: AudioTrackType,
    val timelineStartUs: Long,
    val sourceStartUs: Long = 0L,
    val durationUs: Long,
    val volume: Float = 0.8f,
    val fadeInUs: Long = 400_000L,
    val fadeOutUs: Long = 1_400_000L,
    val duckUnderSpeech: Boolean = true,
    val bpm: Double? = null,
    val credit: String? = null,
    val editedByUser: Boolean = false,
) {
    val timelineEndUs: Long get() = timelineStartUs + durationUs.coerceAtLeast(100_000L)
}

data class AudioTrack(
    val id: String = "audio_music",
    val name: String = "Audio 1",
    val type: AudioTrackType = AudioTrackType.MUSIC,
    val muted: Boolean = false,
    val volume: Float = 1.0f,
    val items: List<AudioClipItem> = emptyList(),
) {
    val endUs: Long get() = items.maxOfOrNull { it.timelineEndUs } ?: 0L
}

enum class CaptionStyle(val label: String) {
    SUBTITLE_BAR("Subtitle Bar"),
    BOLD_BOX("Bold Box"),
    MINIMAL("Minimal"),
}

data class CaptionItem(
    val id: String = UUID.randomUUID().toString(),
    val startUs: Long,
    val endUs: Long,
    val text: String,
    val style: CaptionStyle = CaptionStyle.SUBTITLE_BAR,
    val speaker: String? = null,
    val editedByUser: Boolean = false,
) {
    val durationUs: Long get() = (endUs - startUs).coerceAtLeast(0L)
}

data class CaptionTrack(
    val id: String = "captions_main",
    val name: String = "Captions",
    val visible: Boolean = true,
    val items: List<CaptionItem> = emptyList(),
) {
    val endUs: Long get() = items.maxOfOrNull { it.endUs } ?: 0L
}

data class TimelineProject(
    val id: String = UUID.randomUUID().toString(),
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val name: String = "Untitled Edit",
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis(),
    val aspect: Aspect = Aspect.PORTRAIT,
    val fps: Int = 30,
    val resolution: ExportResolution = ExportResolution.P1080,
    val videoBitrateBps: Int = 8_000_000,
    val audioBitrateBps: Int = 192_000,
    val colorLook: ColorLook = ColorLook.NATURAL,
    val mood: Mood = Mood.HAPPY,
    val titleOverlay: String? = null,
    val summary: String = "",
    val videoTracks: List<VideoTrack> = listOf(VideoTrack()),
    val audioTracks: List<AudioTrack> = listOf(AudioTrack()),
    val captionTracks: List<CaptionTrack> = listOf(CaptionTrack()),
    val legacyPlanJson: String? = null,
) {
    val mainVideoTrack: VideoTrack get() = videoTracks.firstOrNull { it.isMain } ?: videoTracks.firstOrNull() ?: VideoTrack()
    val mainCaptionTrack: CaptionTrack get() = captionTracks.firstOrNull() ?: CaptionTrack()
    val mainAudioTrack: AudioTrack get() = audioTracks.firstOrNull() ?: AudioTrack()

    val totalDurationUs: Long
        get() = max(
            videoTracks.maxOfOrNull { it.endUs } ?: 0L,
            max(audioTracks.maxOfOrNull { it.endUs } ?: 0L, captionTracks.maxOfOrNull { it.endUs } ?: 0L),
        )

    val videoDurationUs: Long get() = mainVideoTrack.endUs

    val hasUserManualEdits: Boolean
        get() = videoTracks.any { t -> t.items.any { it.editedByUser } } ||
            audioTracks.any { t -> t.items.any { it.editedByUser } } ||
            captionTracks.any { t -> t.items.any { it.editedByUser } }

    fun activeCaptionsAt(timeUs: Long): List<CaptionItem> =
        captionTracks.filter { it.visible }
            .flatMap { it.items }
            .filter { timeUs in it.startUs..it.endUs }

    /**
     * Converts the main video track + project settings back into a Media3-compatible [EditPlan].
     */
    fun toEditPlan(): EditPlan {
        val segs = mainVideoTrack.items.sortedBy { it.timelineStartUs }.map { item ->
            Segment(
                clip = item.sourceClipIndex,
                start = usToSec(item.sourceStartUs),
                end = usToSec(item.sourceEndUs),
                speed = item.speed,
                why = item.why,
                sound = !item.muted,
                focusX = item.focusX,
                soundGain = if (item.muted) 0.0 else item.volume.toDouble(),
            )
        }
        val firstTransition = mainVideoTrack.items.drop(1).firstOrNull()?.transitionIn ?: Transition.AUTO
        val musicItem = mainAudioTrack.items.firstOrNull { it.trackType == AudioTrackType.MUSIC }
        return EditPlan(
            summary = summary.ifBlank { "Timeline edit (${segs.size} clips)" },
            aspect = aspect,
            title = titleOverlay,
            segments = segs,
            mood = mood,
            color = colorLook,
            originalVolume = 1.0,
            musicVolume = musicItem?.volume?.toDouble() ?: 0.8,
            transition = firstTransition,
            musicOn = musicItem != null,
        )
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 2

        fun empty(name: String = "Untitled Edit"): TimelineProject = TimelineProject(name = name)

        fun fromEditPlan(
            plan: EditPlan,
            clips: List<ClipMeta>,
            clipUris: Map<Int, String> = emptyMap(),
            projectId: String = UUID.randomUUID().toString(),
            projectName: String = "PromptCut Edit",
            preserveLegacyJson: String? = null,
        ): TimelineProject {
            val byIdx = clips.associateBy { it.index }
            var cursorUs = 0L
            val items = plan.segments.mapIndexed { idx, seg ->
                val meta = byIdx[seg.clip]
                val item = VideoClipItem(
                    sourceClipIndex = seg.clip,
                    sourceUri = clipUris[seg.clip] ?: "",
                    timelineStartUs = cursorUs,
                    sourceStartUs = secToUs(seg.start),
                    sourceEndUs = secToUs(seg.end),
                    speed = seg.speed,
                    volume = (seg.soundGain * plan.originalVolume).toFloat().coerceIn(0f, 1.5f),
                    muted = seg.sound == false || seg.soundGain <= 0.01,
                    crop = meta?.crop,
                    focusX = seg.focusX,
                    transitionIn = if (idx == 0) Transition.CUT else plan.effectiveTransition,
                    why = seg.why,
                    editedByUser = false,
                )
                cursorUs += item.timelineDurationUs
                item
            }
            return TimelineProject(
                id = projectId,
                name = projectName,
                aspect = plan.aspect,
                colorLook = plan.color,
                mood = plan.mood,
                titleOverlay = plan.title,
                summary = plan.summary,
                videoTracks = listOf(VideoTrack(items = items)),
                audioTracks = listOf(AudioTrack()),
                captionTracks = listOf(CaptionTrack()),
                legacyPlanJson = preserveLegacyJson ?: plan.toJson(),
            )
        }
    }
}
