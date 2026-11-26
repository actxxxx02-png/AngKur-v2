package com.promptcut.app.agent

import com.promptcut.app.ClipMeta
import com.promptcut.app.core.AppError
import com.promptcut.app.core.AppResult
import com.promptcut.app.timeline.TimelineProject

data class AgentValidationReport(
    val validatedTools: List<AgentToolInvocation>,
    val requiresManualEditApproval: Boolean,
    val affectedManualItemIds: List<String>,
    val approvalReason: String?,
)

/**
 * Validates all proposed AI tool calls against timeline state and enforces manual-edit protection.
 */
object AgentValidator {

    fun validate(
        invocations: List<AgentToolInvocation>,
        project: TimelineProject,
        clips: List<ClipMeta>,
        allowOverwriteManualEdits: Boolean,
        replacesWholeTimeline: Boolean = false,
    ): AppResult<AgentValidationReport> {
        val itemsById = project.mainVideoTrack.items.associateBy { it.id }
        val clipDurByIndex = clips.associate { it.index to it.durationSec }
        val captionsById = project.mainCaptionTrack.items.associateBy { it.id }
        val touchedManual = linkedSetOf<String>()

        if (replacesWholeTimeline) {
            val manualIds = project.mainVideoTrack.items.filter { it.editedByUser }.map { it.id } +
                project.mainCaptionTrack.items.filter { it.editedByUser }.map { it.id }
            touchedManual.addAll(manualIds)
        }

        for (tool in invocations) {
            when (tool) {
                is AgentToolInvocation.TrimSegment -> {
                    val item = itemsById[tool.itemId]
                        ?: return AppResult.Failure(AppError.Validation("Cannot trim: segment '${tool.itemId}' not found."))
                    val maxDur = clipDurByIndex[item.sourceClipIndex] ?: Double.MAX_VALUE
                    if (tool.endSec > maxDur + 0.1) {
                        return AppResult.Failure(AppError.Validation("Trim end ${tool.endSec}s exceeds clip duration ${maxDur}s."))
                    }
                    if (item.editedByUser) touchedManual += item.id
                }
                is AgentToolInvocation.SplitSegment -> {
                    val item = itemsById[tool.itemId]
                        ?: return AppResult.Failure(AppError.Validation("Cannot split: segment '${tool.itemId}' not found."))
                    if (item.editedByUser) touchedManual += item.id
                }
                is AgentToolInvocation.DeleteSegment -> {
                    val item = itemsById[tool.itemId]
                        ?: return AppResult.Failure(AppError.Validation("Cannot delete: segment '${tool.itemId}' not found."))
                    if (item.editedByUser) touchedManual += item.id
                }
                is AgentToolInvocation.DuplicateSegment -> {
                    if (tool.itemId !in itemsById) {
                        return AppResult.Failure(AppError.Validation("Cannot duplicate: segment '${tool.itemId}' not found."))
                    }
                }
                is AgentToolInvocation.ReorderSegments -> {
                    val count = project.mainVideoTrack.items.size
                    if (tool.fromIndex !in 0 until count || tool.toIndex !in 0 until count) {
                        return AppResult.Failure(AppError.Validation("Reorder indices (${tool.fromIndex} -> ${tool.toIndex}) out of range 0..${count - 1}."))
                    }
                    project.mainVideoTrack.items.filter { it.editedByUser }.forEach { touchedManual += it.id }
                }
                is AgentToolInvocation.SetSpeed -> {
                    val item = itemsById[tool.itemId]
                        ?: return AppResult.Failure(AppError.Validation("Cannot set speed: segment '${tool.itemId}' not found."))
                    if (tool.speed !in 0.25..4.0) {
                        return AppResult.Failure(AppError.Validation("Speed ${tool.speed}x must be between 0.25x and 4.0x."))
                    }
                    if (item.editedByUser) touchedManual += item.id
                }
                is AgentToolInvocation.SetCropResize -> {
                    if (tool.itemId != null && tool.itemId !in itemsById) {
                        return AppResult.Failure(AppError.Validation("Cannot crop: segment '${tool.itemId}' not found."))
                    }
                    val item = tool.itemId?.let { itemsById[it] }
                    if (item?.editedByUser == true) touchedManual += item.id
                }
                is AgentToolInvocation.SetTransition -> {
                    if (tool.itemId != null && tool.itemId !in itemsById) {
                        return AppResult.Failure(AppError.Validation("Cannot set transition: segment '${tool.itemId}' not found."))
                    }
                }
                is AgentToolInvocation.EditCaption -> {
                    if (tool.captionId != null && captionsById[tool.captionId]?.editedByUser == true) {
                        touchedManual += tool.captionId
                    }
                }
                is AgentToolInvocation.GenerateCaptions -> {
                    project.mainCaptionTrack.items.filter { it.editedByUser }.forEach { touchedManual += it.id }
                }
                is AgentToolInvocation.ConfigureAudioMusic -> {
                    if (tool.clipId != null && tool.clipId !in itemsById) {
                        return AppResult.Failure(AppError.Validation("Cannot configure audio: segment '${tool.clipId}' not found."))
                    }
                }
                is AgentToolInvocation.InspectClips,
                is AgentToolInvocation.DetectHighlights,
                is AgentToolInvocation.SetLook,
                is AgentToolInvocation.InspectTimelineQa -> Unit
            }
        }

        val needsApproval = touchedManual.isNotEmpty() && !allowOverwriteManualEdits
        val reason = if (needsApproval) {
            "This change modifies ${touchedManual.size} item(s) you edited manually on the timeline."
        } else null

        return AppResult.Success(
            AgentValidationReport(
                validatedTools = invocations,
                requiresManualEditApproval = needsApproval,
                affectedManualItemIds = touchedManual.toList(),
                approvalReason = reason,
            )
        )
    }
}
