package com.promptcut.app.agent

import com.promptcut.app.ClipInsight
import com.promptcut.app.ClipMeta
import com.promptcut.app.EditPlan
import com.promptcut.app.core.AppError
import com.promptcut.app.core.AppResult
import com.promptcut.app.intelligence.StoryAnalyzer
import com.promptcut.app.round1
import com.promptcut.app.timeline.AudioClipItem
import com.promptcut.app.timeline.AudioTrackType
import com.promptcut.app.timeline.CaptionItem
import com.promptcut.app.timeline.TimelineCommand
import com.promptcut.app.timeline.TimelineEngine
import com.promptcut.app.timeline.TimelineProject
import com.promptcut.app.timeline.secToUs
import com.promptcut.app.timeline.usToSec
import java.util.UUID

data class ExecutionOutcome(
    val steps: List<AgentStep>,
    val updatedProject: TimelineProject,
    val musicRequested: Boolean? = null,
    val musicQuery: String? = null,
    val musicVolume: Float? = null,
)

/**
 * Executes validated [AgentToolInvocation] lists against [TimelineEngine] inside a single
 * atomic undoable [TimelineCommand.Batch].
 */
class AgentExecutor(
    private val engine: TimelineEngine,
) {
    fun execute(
        invocations: List<AgentToolInvocation>,
        clips: List<ClipMeta>,
        insights: Map<Int, ClipInsight>,
        clipUris: Map<Int, String>,
        replacementPlan: EditPlan? = null,
    ): AppResult<ExecutionOutcome> {
        val commands = mutableListOf<TimelineCommand>()
        val steps = mutableListOf<AgentStep>()
        var musicRequested: Boolean? = null
        var musicQuery: String? = null
        var musicVolume: Float? = null

        val metasByIdx = clips.associateBy { it.index }

        if (replacementPlan != null) {
            val next = TimelineProject.fromEditPlan(
                plan = replacementPlan,
                clips = clips,
                clipUris = clipUris,
                projectId = engine.project.id,
                projectName = engine.project.name,
                preserveLegacyJson = replacementPlan.toJson(),
            ).copy(
                fps = engine.project.fps,
                resolution = engine.project.resolution,
                videoBitrateBps = engine.project.videoBitrateBps,
                audioTracks = engine.project.audioTracks,
                captionTracks = engine.project.captionTracks,
            )
            commands += TimelineCommand.ReplaceProject(next, label = "AI Edit", byUser = false)
            steps += AgentStep("apply_edit_plan", "Built timeline (${replacementPlan.segments.size} shots, ${round1(replacementPlan.totalSec)}s)", true)
            musicRequested = replacementPlan.musicOn
            if (replacementPlan.musicQuery.isNotBlank()) musicQuery = replacementPlan.musicQuery
            musicVolume = replacementPlan.musicVolume.toFloat()
        }

        val referenceProject = if (replacementPlan != null) {
            (commands.last() as TimelineCommand.ReplaceProject).nextProject
        } else {
            engine.project
        }

        for (tool in invocations) {
            when (tool) {
                is AgentToolInvocation.InspectClips -> {
                    val count = if (tool.clipIndex != null) 1 else clips.size
                    steps += AgentStep(tool.toolName, "Inspected $count clip(s)", true)
                }

                is AgentToolInvocation.DetectHighlights -> {
                    val beats = StoryAnalyzer.analyzeStoryArc(clips, insights)
                    steps += AgentStep(tool.toolName, "Identified ${beats.size} story beat(s) across ${clips.size} clip(s)", true)
                }

                is AgentToolInvocation.TrimSegment -> {
                    val item = referenceProject.mainVideoTrack.items.firstOrNull { it.id == tool.itemId }
                    val maxSrcUs = item?.let { metasByIdx[it.sourceClipIndex]?.durationSec?.let(::secToUs) } ?: Long.MAX_VALUE
                    commands += TimelineCommand.TrimVideoClip(
                        itemId = tool.itemId,
                        newSourceStartUs = secToUs(tool.startSec),
                        newSourceEndUs = secToUs(tool.endSec),
                        maxSourceDurationUs = maxSrcUs,
                        byUser = false,
                    )
                    steps += AgentStep(tool.toolName, "Trimmed segment to ${round1(tool.startSec)}–${round1(tool.endSec)}s", true)
                }

                is AgentToolInvocation.SplitSegment -> {
                    commands += TimelineCommand.SplitVideoClip(
                        itemId = tool.itemId,
                        splitAtTimelineUs = secToUs(tool.splitAtSec),
                        byUser = false,
                    )
                    steps += AgentStep(tool.toolName, "Split segment at ${round1(tool.splitAtSec)}s", true)
                }

                is AgentToolInvocation.DeleteSegment -> {
                    commands += TimelineCommand.DeleteVideoClip(itemId = tool.itemId, byUser = false)
                    steps += AgentStep(tool.toolName, "Removed segment from timeline", true)
                }

                is AgentToolInvocation.DuplicateSegment -> {
                    commands += TimelineCommand.DuplicateVideoClip(itemId = tool.itemId, byUser = false)
                    steps += AgentStep(tool.toolName, "Duplicated segment", true)
                }

                is AgentToolInvocation.ReorderSegments -> {
                    commands += TimelineCommand.ReorderVideoClips(tool.fromIndex, tool.toIndex, byUser = false)
                    steps += AgentStep(tool.toolName, "Reordered segment #${tool.fromIndex + 1} → #${tool.toIndex + 1}", true)
                }

                is AgentToolInvocation.SetCropResize -> {
                    if (tool.aspect != null) {
                        commands += TimelineCommand.SetProjectStyle(aspect = tool.aspect, byUser = false)
                    }
                    if (tool.crop != null) {
                        val targets = if (tool.itemId != null) listOf(tool.itemId) else referenceProject.mainVideoTrack.items.map { it.id }
                        targets.forEach { id ->
                            commands += TimelineCommand.SetClipCrop(itemId = id, crop = tool.crop, byUser = false)
                        }
                    }
                    steps += AgentStep(tool.toolName, "Updated frame aspect/crop", true)
                }

                is AgentToolInvocation.GenerateCaptions -> {
                    val generated = StoryAnalyzer.generateCaptionsForTimeline(referenceProject, insights, tool.style)
                    if (generated.isNotEmpty()) {
                        commands += TimelineCommand.ReplaceCaptions(generated, byUser = false)
                        steps += AgentStep(tool.toolName, "Generated ${generated.size} synchronized caption(s)", true)
                    } else {
                        steps += AgentStep(tool.toolName, "No speech spans detected for captions", true)
                    }
                }

                is AgentToolInvocation.EditCaption -> {
                    val cap = CaptionItem(
                        id = tool.captionId ?: UUID.randomUUID().toString(),
                        startUs = secToUs(tool.startSec),
                        endUs = secToUs(tool.endSec),
                        text = tool.text,
                        style = tool.style,
                        editedByUser = false,
                    )
                    commands += TimelineCommand.AddOrUpdateCaption(cap, byUser = false)
                    steps += AgentStep(tool.toolName, "Updated caption \"${tool.text.take(24)}\"", true)
                }

                is AgentToolInvocation.SetTransition -> {
                    val targets = if (tool.itemId != null) listOf(tool.itemId) else referenceProject.mainVideoTrack.items.map { it.id }
                    targets.forEach { id ->
                        commands += TimelineCommand.SetClipTransition(
                            itemId = id,
                            transition = tool.transition,
                            durationUs = secToUs(tool.durationSec),
                            byUser = false,
                        )
                    }
                    steps += AgentStep(tool.toolName, "Set ${tool.transition.label} transition", true)
                }

                is AgentToolInvocation.SetLook -> {
                    commands += TimelineCommand.SetProjectStyle(
                        colorLook = tool.colorLook,
                        titleOverlay = tool.titleOverlay,
                        clearTitle = tool.clearTitle,
                        byUser = false,
                    )
                    steps += AgentStep(tool.toolName, "Applied ${tool.colorLook.label} look", true)
                }

                is AgentToolInvocation.SetSpeed -> {
                    commands += TimelineCommand.SetClipSpeed(itemId = tool.itemId, speed = tool.speed, byUser = false)
                    steps += AgentStep(tool.toolName, "Set speed ${tool.speed}x", true)
                }

                is AgentToolInvocation.ConfigureAudioMusic -> {
                    musicRequested = tool.musicOn
                    if (tool.musicQuery != null) musicQuery = tool.musicQuery
                    if (tool.musicVolume != null) musicVolume = tool.musicVolume
                    if (!tool.musicOn) {
                        referenceProject.mainAudioTrack.items.filter { it.trackType == AudioTrackType.MUSIC }.forEach {
                            commands += TimelineCommand.DeleteAudioItem(it.id, byUser = false)
                        }
                    } else if (tool.musicVolume != null) {
                        referenceProject.mainAudioTrack.items.firstOrNull { it.trackType == AudioTrackType.MUSIC }?.let { existing ->
                            commands += TimelineCommand.AddAudioItem(existing.copy(volume = tool.musicVolume, editedByUser = false), byUser = false)
                        }
                    }
                    if (tool.clipId != null && (tool.clipVolume != null || tool.clipMuted != null)) {
                        val existing = referenceProject.mainVideoTrack.items.firstOrNull { it.id == tool.clipId }
                        if (existing != null) {
                            commands += TimelineCommand.SetClipVolume(
                                itemId = tool.clipId,
                                volume = tool.clipVolume ?: existing.volume,
                                muted = tool.clipMuted ?: existing.muted,
                                byUser = false,
                            )
                        }
                    }
                    steps += AgentStep(tool.toolName, if (tool.musicOn) "Configured music & audio" else "Muted background music", true)
                }

                is AgentToolInvocation.InspectTimelineQa -> {
                    val dur = round1(usToSec(referenceProject.totalDurationUs))
                    steps += AgentStep(tool.toolName, "Timeline duration ${dur}s across ${referenceProject.mainVideoTrack.items.size} clip(s)", true)
                }
            }
        }

        if (commands.isEmpty()) {
            return AppResult.Success(ExecutionOutcome(steps, engine.project, musicRequested, musicQuery, musicVolume))
        }

        return when (val res = engine.execute(TimelineCommand.Batch(commands, label = "AI Agent Edit", byUser = false))) {
            is AppResult.Success -> AppResult.Success(
                ExecutionOutcome(
                    steps = steps,
                    updatedProject = res.value,
                    musicRequested = musicRequested,
                    musicQuery = musicQuery,
                    musicVolume = musicVolume,
                )
            )
            is AppResult.Failure -> AppResult.Failure(
                AppError.Validation(res.error.message, cause = res.error.cause)
            )
        }
    }
}
