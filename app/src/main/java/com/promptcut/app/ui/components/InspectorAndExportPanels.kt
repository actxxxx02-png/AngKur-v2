package com.promptcut.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.promptcut.app.Aspect
import com.promptcut.app.ColorLook
import com.promptcut.app.EditorViewModel
import com.promptcut.app.MusicSource
import com.promptcut.app.QaReport
import com.promptcut.app.Transition
import com.promptcut.app.render.ExportPreset
import com.promptcut.app.round1
import com.promptcut.app.round2
import com.promptcut.app.timeline.CaptionItem
import com.promptcut.app.timeline.CaptionStyle
import com.promptcut.app.timeline.Keyframe
import com.promptcut.app.timeline.KeyframeProperty
import com.promptcut.app.timeline.TimelineProject
import com.promptcut.app.timeline.secToUs
import com.promptcut.app.timeline.usToSec
import com.promptcut.app.ui.theme.MonoTimecodeStyle
import java.util.UUID

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditInspectorPanel(
    vm: EditorViewModel,
    project: TimelineProject,
    onOpenCrop: (Int) -> Unit,
) {
    val item = vm.selectedVideoItem()
    if (item == null) {
        Text(
            "Select a clip on the timeline to trim, adjust speed, audio volume, transitions, or keyframes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val sourceClip = vm.clips.firstOrNull { it.meta.index == item.sourceClipIndex }
    val maxDur = sourceClip?.meta?.durationSec?.toFloat() ?: (usToSec(item.sourceEndUs).toFloat() + 5f)
    var trimRange by remember(item.id, item.sourceStartUs, item.sourceEndUs) {
        mutableStateOf(usToSec(item.sourceStartUs).toFloat()..usToSec(item.sourceEndUs).toFloat())
    }
    var vol by remember(item.id, item.volume) { mutableFloatStateOf(item.volume) }
    var kfValue by remember(item.id) { mutableFloatStateOf(1.1f) }
    var kfProp by remember { mutableStateOf(KeyframeProperty.SCALE) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Segment · Clip #${item.sourceClipIndex + 1}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { onOpenCrop(item.sourceClipIndex) }) { Text("Crop Box") }
        }

        Text("Trim Source: ${round2(trimRange.start.toDouble())}s – ${round2(trimRange.endInclusive.toDouble())}s", style = MonoTimecodeStyle)
        RangeSlider(
            value = trimRange,
            onValueChange = { trimRange = it },
            valueRange = 0f..maxDur.coerceAtLeast(0.5f),
            onValueChangeFinished = {
                vm.trimSelected(trimRange.start.toDouble(), trimRange.endInclusive.toDouble())
            },
        )

        Text("Speed (${round2(item.speed)}x)", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0).forEach { sp ->
                FilterChip(
                    selected = kotlin.math.abs(item.speed - sp) < 0.05,
                    onClick = { vm.setSelectedSpeed(sp) },
                    label = { Text("${sp}x", style = MonoTimecodeStyle) },
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Volume ${(vol * 100).toInt()}%", style = MonoTimecodeStyle, modifier = Modifier.weight(1f))
            Text("Mute", style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.width(6.dp))
            Switch(checked = item.muted, onCheckedChange = { vm.setSelectedAudio(vol, it) })
        }
        Slider(
            value = vol,
            onValueChange = { vol = it },
            valueRange = 0f..1.5f,
            onValueChangeFinished = { vm.setSelectedAudio(vol, item.muted) },
        )

        Text("Transition In", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Transition.entries.forEach { t ->
                FilterChip(
                    selected = item.transitionIn == t,
                    onClick = { vm.setSelectedTransition(t) },
                    label = { Text(t.label, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }

        HorizontalDivider()
        Text("Keyframes (${item.keyframes.size})", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KeyframeProperty.entries.forEach { p ->
                FilterChip(
                    selected = kfProp == p,
                    onClick = { kfProp = p },
                    label = { Text(p.label, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Slider(value = kfValue, onValueChange = { kfValue = it }, valueRange = 0f..2f, modifier = Modifier.weight(1f))
            Button(onClick = {
                val offset = (vm.playheadUs - item.timelineStartUs).coerceIn(0L, item.timelineDurationUs)
                vm.addOrUpdateSelectedKeyframe(Keyframe(offset, kfProp, kfValue))
            }) { Text("Set Keyframe") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CaptionsPanel(vm: EditorViewModel, project: TimelineProject) {
    val captions = project.mainCaptionTrack.items
    val selected = vm.selectedCaption()
    var style by remember { mutableStateOf(CaptionStyle.SUBTITLE_BAR) }
    var text by remember(selected?.id) { mutableStateOf(selected?.text ?: "") }
    var startSec by remember(selected?.id) {
        mutableStateOf(selected?.let { round1(usToSec(it.startUs)).toString() } ?: round1(usToSec(vm.playheadUs)).toString())
    }
    var endSec by remember(selected?.id) {
        mutableStateOf(selected?.let { round1(usToSec(it.endUs)).toString() } ?: round1(usToSec(vm.playheadUs) + 2.0).toString())
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { vm.generateCaptionsFromSpeech(style) },
                modifier = Modifier.weight(1f).testTag("auto_captions_button"),
            ) { Text("Auto-Generate from Speech") }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CaptionStyle.entries.forEach { st ->
                FilterChip(
                    selected = style == st,
                    onClick = { style = st },
                    label = { Text(st.label, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }

        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(if (selected == null) "New caption text" else "Edit caption text") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = startSec,
                onValueChange = { startSec = it },
                label = { Text("Start (s)") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            OutlinedTextField(
                value = endSec,
                onValueChange = { endSec = it },
                label = { Text("End (s)") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            Button(
                enabled = text.isNotBlank(),
                onClick = {
                    val s = startSec.toDoubleOrNull() ?: 0.0
                    val e = endSec.toDoubleOrNull() ?: (s + 2.0)
                    vm.upsertCaption(
                        CaptionItem(
                            id = selected?.id ?: UUID.randomUUID().toString(),
                            startUs = secToUs(s),
                            endUs = secToUs(e),
                            text = text.trim(),
                            style = style,
                        )
                    )
                    if (selected == null) text = ""
                },
            ) { Text(if (selected == null) "Add" else "Save") }
        }

        if (captions.isNotEmpty()) {
            HorizontalDivider()
            captions.forEach { c ->
                OutlinedCard(
                    onClick = {
                        vm.selectedCaptionId = c.id
                        vm.playheadUs = c.startUs
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.text, style = MaterialTheme.typography.bodySmall)
                            Text("${round1(usToSec(c.startUs))}s – ${round1(usToSec(c.endUs))}s · ${c.style.label}", style = MonoTimecodeStyle)
                        }
                        TextButton(onClick = { vm.deleteCaption(c.id) }) { Text("Delete") }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AudioStudioPanel(vm: EditorViewModel, onPickSong: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MusicSource.entries.forEach { src ->
                FilterChip(
                    selected = vm.musicSource == src,
                    onClick = {
                        vm.musicSource = src
                        if (src == MusicSource.MY_SONG && vm.song == null) onPickSong()
                        if (src == MusicSource.LIBRARY && vm.libResults.isEmpty()) vm.searchLibrary()
                    },
                    label = { Text(src.label, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }

        when (vm.musicSource) {
            MusicSource.LIBRARY -> LibraryPanel(vm)
            MusicSource.MOOD -> Text(
                "Offline procedural ${vm.mood.label.lowercase()} score synthesized on-device via MusicSynth (zero network required).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MusicSource.MY_SONG -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    vm.song?.let { "${it.name}" + (it.tempo?.let { t -> " · ${t.bpm.toInt()} BPM" } ?: "") }
                        ?: "No audio file picked yet",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = onPickSong) { Text(if (vm.song == null) "Choose File" else "Change") }
            }
            MusicSource.ORIGINAL -> Text("Using original clip audio only.", style = MaterialTheme.typography.bodySmall)
        }

        if (vm.sfxCues.isNotEmpty()) {
            HorizontalDivider()
            Text("Suggested Beat & Transition SFX Cues", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                vm.sfxCues.take(6).forEach { cue ->
                    AssistChip(
                        onClick = { vm.playheadUs = cue.timelineUs },
                        label = { Text("${cue.type} @ ${round1(usToSec(cue.timelineUs))}s", style = MonoTimecodeStyle) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EffectsPanel(vm: EditorViewModel, project: TimelineProject) {
    var titleText by remember(project.titleOverlay) { mutableStateOf(project.titleOverlay ?: "") }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Color Look", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ColorLook.entries.forEach { l ->
                FilterChip(
                    selected = project.colorLook == l,
                    onClick = { vm.setProjectLook(l) },
                    label = { Text(l.label, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }

        Text("Aspect Ratio", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Aspect.entries.forEach { a ->
                FilterChip(
                    selected = project.aspect == a,
                    onClick = { vm.setProjectAspect(a) },
                    label = { Text(a.label, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = titleText,
                onValueChange = { titleText = it },
                label = { Text("Opening Title Overlay") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { vm.setProjectTitle(titleText) }) { Text("Apply") }
        }

        if (vm.storyBeats.isNotEmpty()) {
            HorizontalDivider()
            Text("Detected Story Arc & Highlights", style = MaterialTheme.typography.labelMedium)
            vm.storyBeats.take(5).forEach { b ->
                Text(
                    "${b.act.label}: clip #${b.clipIndex + 1} (${round1(b.startSec)}–${round1(b.endSec)}s) — ${b.reason}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExportPanel(
    vm: EditorViewModel,
    onSaveGallery: () -> Unit,
    onShare: () -> Unit,
) {
    val es = vm.exportSettings
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Export Quality Preset", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ExportPreset.entries.forEach { preset ->
                val selected = es.resolution == preset.resolution && es.fps == preset.fps
                FilterChip(
                    selected = selected,
                    onClick = { vm.applyExportPreset(preset) },
                    label = { Text(preset.label, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Burn captions into exported video", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Switch(checked = es.burnCaptions, onCheckedChange = { vm.exportSettings = es.copy(burnCaptions = it) })
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Safe shot-by-shot render mode", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Switch(checked = es.forceSafeMode, onCheckedChange = {
                vm.exportSettings = es.copy(forceSafeMode = it)
                vm.settings.safeRender = it
            })
        }

        Button(
            onClick = { vm.renderTimelineOnly() },
            enabled = !vm.busy && vm.clips.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("export_render_button"),
        ) { Text("Render ${es.resolution.label} (${es.fps} FPS)") }

        if (vm.output != null && !vm.busy) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onSaveGallery, modifier = Modifier.weight(1f).testTag("save_gallery_button")) {
                    Text("Save to Gallery")
                }
                Button(onClick = onShare, modifier = Modifier.weight(1f).testTag("share_video_button")) {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Share")
                }
            }
        }

        vm.qa?.let { report ->
            QaCard(report = report, onApplyRepair = { vm.applySuggestedRepair(it) })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QaCard(report: QaReport, onApplyRepair: (com.promptcut.app.SuggestedRepair) -> Unit) {
    var open by remember(report) { mutableStateOf(!report.ok) }
    OutlinedCard(onClick = { open = !open }, modifier = Modifier.fillMaxWidth().testTag("qa_report_card")) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (report.ok) "Quality Check Passed (${report.items.size} checks)"
                else "Quality Check: ${report.issues.size} warning(s)",
                style = MaterialTheme.typography.labelLarge,
            )
            if (open) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    report.items.forEach { it ->
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(
                                    (if (it.ok) "OK " else "! ") + it.label + if (it.detail.isNotBlank()) " · ${it.detail}" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                        )
                    }
                }
                if (report.suggestedRepairs.isNotEmpty()) {
                    HorizontalDivider()
                    Text("Suggested Repairs (tap to apply)", style = MaterialTheme.typography.labelSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        report.suggestedRepairs.forEach { r ->
                            OutlinedButton(onClick = { onApplyRepair(r) }) {
                                Text(r.label, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
