package com.promptcut.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Spannable
import android.text.SpannableString
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.OverlaySettings
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextOverlay
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.google.common.collect.ImmutableList
import com.promptcut.app.render.ExportSettings
import com.promptcut.app.render.RenderCheckpoint
import com.promptcut.app.timeline.CaptionItem
import com.promptcut.app.timeline.CaptionStyle
import com.promptcut.app.timeline.TimelineProject
import com.promptcut.app.timeline.usToSec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class RenderException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Turns an [EditPlan] or [TimelineProject] into an MP4 on the phone using Media3 Transformer.
 * Preserves the legacy [render] entrypoint while adding [renderProject] for 720p/1080p settings,
 * FPS, bitrate, timed caption overlays, and checkpointed safe-mode recovery.
 */
@OptIn(UnstableApi::class)
object Renderer {
    private const val TAG = "PromptCut"
    private const val MUXER_PATIENCE_MS = 45_000L

    suspend fun render(
        context: Context,
        plan: EditPlan,
        clips: List<Clip>,
        music: MusicMux.Source?,
        forceSafeMode: Boolean = false,
        onStatus: (String) -> Unit = {},
        onProgress: (Int) -> Unit,
    ): File {
        val project = TimelineProject.fromEditPlan(plan, clips.map { it.meta })
        return renderProject(
            context = context,
            project = project,
            clips = clips,
            music = music,
            exportSettings = ExportSettings(forceSafeMode = forceSafeMode, burnCaptions = false),
            onStatus = onStatus,
            onProgress = onProgress,
        )
    }

    suspend fun renderProject(
        context: Context,
        project: TimelineProject,
        clips: List<Clip>,
        music: MusicMux.Source?,
        exportSettings: ExportSettings = ExportSettings(),
        onStatus: (String) -> Unit = {},
        onProgress: (Int) -> Unit,
    ): File {
        val plan = project.toEditPlan()
        val dir = File(context.cacheDir, "render").apply { mkdirs() }
        val checkpointFile = File(dir, "checkpoint.json")
        val planHash = "${project.id}:${plan.toJson().hashCode()}:${exportSettings.resolution}:${exportSettings.fps}:${exportSettings.videoBitrateBps}"
        val out = File(dir, "promptcut_${System.currentTimeMillis()}.mp4")

        // Clean stale MP4 artifacts from unrelated projects while preserving resumable parts for identical hash.
        val existingCp = RenderCheckpoint.loadFrom(checkpointFile, project.id, planHash)
        if (existingCp == null) {
            dir.listFiles()?.forEach { it.delete() }
        } else {
            dir.listFiles()?.filter { it.name.startsWith("promptcut_") || it.name.startsWith("final_") }?.forEach { it.delete() }
        }

        val video = run {
            if (!exportSettings.forceSafeMode) {
                try {
                    export(
                        context = context,
                        composition = fullComposition(plan, project, clips, exportSettings),
                        out = out,
                        exportSettings = exportSettings,
                    ) { p -> onProgress(if (music != null) p * 9 / 10 else p) }
                    checkpointFile.delete()
                    return@run out
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "One-pass render failed, switching to safe mode", e)
                    out.delete()
                }
            }
            onStatus("Rendering shot by shot (safe mode)…")
            renderSafe(
                context = context,
                plan = plan,
                project = project,
                clips = clips,
                dir = dir,
                out = out,
                exportSettings = exportSettings,
                checkpointFile = checkpointFile,
                initialCheckpoint = existingCp ?: RenderCheckpoint(project.id, planHash, plan.segments.size),
                onStatus = onStatus,
            ) { p -> onProgress(if (music != null) p * 9 / 10 else p) }
        }

        if (music == null) return video

        onStatus("Adding music…")
        val withMusic = File(dir, "final_${System.currentTimeMillis()}.mp4")
        return try {
            withContext(Dispatchers.Default) {
                MusicMux.addMusic(context, video, music, plan.musicVolume.toFloat(), withMusic)
            }
            onProgress(100)
            video.delete()
            withMusic
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Adding music failed", e)
            withMusic.delete()
            onStatus("Couldn't add the music on this phone; here's the video with its own sound.")
            video
        }
    }

    // ------------------------------------------------------------------ building blocks

    private fun speedProvider(speed: Double) = object : SpeedProvider {
        override fun getSpeed(timeUs: Long) = speed.toFloat()
        override fun getNextSpeedChangeTimeUs(timeUs: Long) = C.TIME_UNSET
    }

    private fun clipped(uri: Uri, startSec: Double, endSec: Double?): MediaItem =
        MediaItem.Builder()
            .setUri(uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs((startSec * 1000).toLong())
                    .apply { if (endSec != null) setEndPositionMs((endSec * 1000).toLong()) }
                    .build()
            )
            .build()

    private fun shotItem(
        plan: EditPlan,
        project: TimelineProject,
        seg: Segment,
        clip: Clip,
        index: Int,
        timelineStartUs: Long,
        keepClipSound: Boolean,
        exportSettings: ExportSettings,
    ): EditedMediaItem {
        val video = mutableListOf<Effect>()
        val audio = mutableListOf<AudioProcessor>()
        val soundOn = keepClipSound && clip.meta.hasAudio
        if (soundOn) {
            val g = plan.originalVolume * seg.soundGain
            if (g < 0.99) audio += GainProcessor(g.toFloat())
            audio += FadeProcessor(seg.outputSec, 0.03, 0.06)
        }

        video += FrameDropEffect.createDefaultFrameDropEffect(exportSettings.fps.toFloat())
        val (outW, outH) = exportSettings.outputDimensions(plan.aspect)
        val timelineItem = project.mainVideoTrack.items.getOrNull(index)
        val userCrop = timelineItem?.crop ?: clip.meta.crop
        if (userCrop != null) {
            video += androidx.media3.effect.Crop(
                (2 * userCrop.x0 - 1).toFloat(), (2 * userCrop.x1 - 1).toFloat(),
                (1 - 2 * userCrop.y1).toFloat(), (1 - 2 * userCrop.y0).toFloat(),
            )
            video += Presentation.createForWidthAndHeight(outW, outH, Presentation.LAYOUT_SCALE_TO_FIT)
        } else {
            reframe(clip, seg, plan.aspect)?.let { video += it }
            video += Presentation.createForWidthAndHeight(outW, outH, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
        }

        val look = timelineItem?.colorLookOverride ?: plan.color
        video += Looks.effects(look)

        fun continues(a: Segment?, b: Segment?) =
            a != null && b != null && a.clip == b.clip && b.start >= a.end - 0.01 && b.start - a.end < 8.0

        val transition = timelineItem?.transitionIn?.takeIf { index > 0 } ?: plan.effectiveTransition
        video += Transitions.effects(
            transition, index, plan.segments.size, seg.outputSec,
            if (userCrop != null) null else faceInOutput(clip, seg, plan.aspect),
            joinIn = continues(plan.segments.getOrNull(index - 1), seg),
            joinOut = continues(seg, plan.segments.getOrNull(index + 1)),
        )

        if (index == 0 && plan.title != null) video += titleOverlay(plan.title)

        if (exportSettings.burnCaptions) {
            val segEndUs = timelineStartUs + (seg.outputSec * 1_000_000.0).toLong()
            val overlappingCaptions = project.mainCaptionTrack.items.filter { c ->
                c.endUs > timelineStartUs && c.startUs < segEndUs
            }
            if (overlappingCaptions.isNotEmpty()) {
                video += timedCaptionOverlay(timelineStartUs, overlappingCaptions)
            }
        }

        return EditedMediaItem.Builder(clipped(clip.uri, seg.start, seg.end))
            .apply { if (seg.speed != 1.0) setSpeed(speedProvider(seg.speed)) }
            .setRemoveAudio(!soundOn)
            .setEffects(Effects(audio, video))
            .build()
    }

    private fun faceInOutput(clip: Clip, seg: Segment, aspect: Aspect): Float? {
        val fx = seg.focusX ?: return null
        val ndc = (2 * fx - 1).toFloat()
        if (clip.meta.width <= 0 || clip.meta.height <= 0) return ndc
        val src = clip.meta.width.toDouble() / clip.meta.height
        val dst = aspect.width.toDouble() / aspect.height
        if (src <= dst * 1.05) return ndc
        val w = (dst / src).toFloat()
        val left = (ndc - w).coerceIn(-1f, 1f - 2 * w)
        return ((ndc - left) / w - 1f).coerceIn(-1f, 1f)
    }

    private fun reframe(clip: Clip, seg: Segment, aspect: Aspect): Effect? {
        val fx = seg.focusX ?: return null
        if (clip.meta.width <= 0 || clip.meta.height <= 0) return null
        val src = clip.meta.width.toDouble() / clip.meta.height
        val dst = aspect.width.toDouble() / aspect.height
        if (src <= dst * 1.05) return null
        val w = (dst / src).toFloat()
        val left = ((2 * fx - 1).toFloat() - w).coerceIn(-1f, 1f - 2 * w)
        return androidx.media3.effect.Crop(left, left + 2 * w, -1f, 1f)
    }

    private fun fullComposition(
        plan: EditPlan,
        project: TimelineProject,
        clips: List<Clip>,
        exportSettings: ExportSettings,
    ): Composition {
        val byIndex = clips.associateBy { it.meta.index }
        val keepClipSound = plan.segments.any { byIndex[it.clip]?.meta?.hasAudio == true && plan.originalVolume * it.soundGain > 0.02 }
        var cursorUs = 0L
        val items = plan.segments.mapIndexedNotNull { i, seg ->
            val clip = byIndex[seg.clip] ?: return@mapIndexedNotNull null
            val item = shotItem(plan, project, seg, clip, i, cursorUs, keepClipSound, exportSettings)
            cursorUs += (seg.outputSec * 1_000_000.0).toLong()
            item
        }
        val sequence = EditedMediaItemSequence.Builder(items)
            .experimentalSetForceAudioTrack(keepClipSound)
            .build()
        return Composition.Builder(sequence)
            .apply { if (clips.any { it.isHdr }) setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL) }
            .setEffects(Effects(listOf(FadeProcessor(plan.totalSec)), emptyList()))
            .build()
    }

    // ------------------------------------------------------------------ safe mode + checkpoint

    private suspend fun renderSafe(
        context: Context,
        plan: EditPlan,
        project: TimelineProject,
        clips: List<Clip>,
        dir: File,
        out: File,
        exportSettings: ExportSettings,
        checkpointFile: File,
        initialCheckpoint: RenderCheckpoint,
        onStatus: (String) -> Unit,
        onProgress: (Int) -> Unit,
    ): File {
        val byIndex = clips.associateBy { it.meta.index }
        val keepClipSound = plan.segments.any { byIndex[it.clip]?.meta?.hasAudio == true && plan.originalVolume * it.soundGain > 0.02 }
        val parts = mutableListOf<File>()
        val n = plan.segments.size
        var skipped = 0
        var checkpoint = initialCheckpoint
        var cursorUs = 0L

        plan.segments.forEachIndexed { i, seg0 ->
            val clip = byIndex[seg0.clip] ?: return@forEachIndexed
            val segStartUs = cursorUs
            cursorUs += (seg0.outputSec * 1_000_000.0).toLong()

            val part = File(dir, "part_$i.mp4")
            if (checkpoint.isCompleteFor(i, part)) {
                parts += part
                onProgress(((i + 1) * 80) / n.coerceAtLeast(1))
                return@forEachIndexed
            }

            onStatus("Rendering shot ${i + 1} of $n (safe mode)…")
            val scale: (Int) -> Unit = { p -> onProgress((i * 100 + p) * 80 / (n * 100).coerceAtLeast(1)) }
            fun single(seg: Segment) = Composition.Builder(
                EditedMediaItemSequence.Builder(
                    listOf(shotItem(plan, project, seg, clip, i, segStartUs, keepClipSound && clip.meta.hasAudio, exportSettings))
                )
                    .experimentalSetForceAudioTrack(true)
                    .build()
            ).apply { if (clip.isHdr) setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL) }.build()

            val ok = try {
                export(context, single(seg0), part, exportSettings, scale); true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Shot $i failed, retrying without speed change", e)
                if (seg0.speed != 1.0) {
                    val plain = seg0.copy(end = (seg0.start + seg0.outputSec).coerceAtMost(clip.meta.durationSec), speed = 1.0)
                    try {
                        export(context, single(plain), part, exportSettings, scale); true
                    } catch (e2: CancellationException) {
                        throw e2
                    } catch (_: Exception) {
                        false
                    }
                } else false
            }
            if (ok) {
                parts += part
                checkpoint = checkpoint.markCompleted(i)
                checkpoint.saveTo(checkpointFile)
            } else skipped++
        }
        if (parts.isEmpty()) throw RenderException("Couldn't render any of the shots on this phone. Try fewer or shorter clips.")

        onStatus("Joining ${parts.size} shots…")
        val joined = parts.map { f ->
            EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(f))).build()
        }
        val finalComp = Composition.Builder(EditedMediaItemSequence.Builder(joined).experimentalSetForceAudioTrack(true).build())
            .setEffects(Effects(listOf(FadeProcessor(plan.totalSec)), emptyList()))
            .build()
        try {
            export(context, finalComp, out, exportSettings) { p -> onProgress(80 + p / 5) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw RenderException("Joining the shots failed: ${(e as? ExportException)?.errorCodeName ?: e.message}", e)
        }
        parts.forEach { it.delete() }
        checkpointFile.delete()
        if (skipped > 0) onStatus("Done. $skipped shot(s) couldn't be rendered and were left out.")
        return out
    }

    // ------------------------------------------------------------------ export

    private suspend fun export(
        context: Context,
        composition: Composition,
        out: File,
        exportSettings: ExportSettings,
        onProgress: (Int) -> Unit,
    ) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            lateinit var transformer: Transformer
            val poll = object : Runnable {
                val holder = ProgressHolder()
                override fun run() {
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
                    handler.postDelayed(this, 300)
                }
            }
            transformer = Transformer.Builder(context)
                .setEncoderFactory(
                    DefaultEncoderFactory.Builder(context)
                        .setRequestedVideoEncoderSettings(
                            VideoEncoderSettings.Builder()
                                .setBitrate(exportSettings.videoBitrateBps)
                                .setMaxBFrames(0)
                                .build()
                        )
                        .build()
                )
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setMaxDelayBetweenMuxerSamplesMs(MUXER_PATIENCE_MS)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        handler.removeCallbacks(poll)
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        handler.removeCallbacks(poll)
                        if (cont.isActive) cont.resumeWithException(
                            RenderException("Rendering failed: ${exportException.errorCodeName}", exportException)
                        )
                    }
                })
                .build()
            transformer.start(composition, out.absolutePath)
            handler.post(poll)
            cont.invokeOnCancellation { handler.post { handler.removeCallbacks(poll); transformer.cancel() } }
        }
    }

    private fun titleOverlay(title: String): OverlayEffect {
        val text = SpannableString(" $title ")
        val all = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        text.setSpan(AbsoluteSizeSpan(88), 0, text.length, all)
        text.setSpan(ForegroundColorSpan(Color.WHITE), 0, text.length, all)
        text.setSpan(BackgroundColorSpan(Color.argb(140, 0, 0, 0)), 0, text.length, all)
        val settings = StaticOverlaySettings.Builder()
            .setBackgroundFrameAnchor(0f, -0.6f)
            .build()
        return OverlayEffect(ImmutableList.of<TextureOverlay>(TextOverlay.createStaticTextOverlay(text, settings)))
    }

    private fun timedCaptionOverlay(shotTimelineStartUs: Long, captions: List<CaptionItem>): OverlayEffect {
        val settings = StaticOverlaySettings.Builder()
            .setBackgroundFrameAnchor(0f, -0.72f)
            .build()
        val overlay = object : TextOverlay() {
            private var firstPtsUs = Long.MIN_VALUE

            override fun getText(presentationTimeUs: Long): SpannableString {
                if (firstPtsUs == Long.MIN_VALUE || presentationTimeUs < firstPtsUs) {
                    firstPtsUs = presentationTimeUs
                }
                val globalUs = shotTimelineStartUs + (presentationTimeUs - firstPtsUs)
                val active = captions.firstOrNull { globalUs in it.startUs..it.endUs }
                    ?: return SpannableString("")
                val str = SpannableString(" ${active.text} ")
                val all = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                val sizePx = when (active.style) {
                    CaptionStyle.BOLD_BOX -> 66
                    CaptionStyle.SUBTITLE_BAR -> 58
                    CaptionStyle.MINIMAL -> 52
                }
                str.setSpan(AbsoluteSizeSpan(sizePx), 0, str.length, all)
                str.setSpan(ForegroundColorSpan(Color.WHITE), 0, str.length, all)
                str.setSpan(StyleSpan(Typeface.BOLD), 0, str.length, all)
                if (active.style != CaptionStyle.MINIMAL) {
                    val alpha = if (active.style == CaptionStyle.BOLD_BOX) 215 else 155
                    str.setSpan(BackgroundColorSpan(Color.argb(alpha, 0, 0, 0)), 0, str.length, all)
                }
                return str
            }

            override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settings
        }
        return OverlayEffect(ImmutableList.of<TextureOverlay>(overlay))
    }

    suspend fun saveToGallery(context: Context, file: File): Uri = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "PromptCut_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/PromptCut")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw Exception("Couldn't create a file in your gallery.")
        resolver.openOutputStream(uri)!!.use { os -> file.inputStream().use { it.copyTo(os) } }
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        uri
    }
}
