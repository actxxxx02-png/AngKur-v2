package com.promptcut.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promptcut.app.round1
import com.promptcut.app.timeline.TimelineProject
import com.promptcut.app.timeline.secToUs
import com.promptcut.app.timeline.usToSec
import com.promptcut.app.ui.theme.MonoTimecodeStyle
import kotlin.math.ceil

@Composable
fun TimelineEditor(
    project: TimelineProject,
    playheadUs: Long,
    zoom: Float,
    selectedVideoItemId: String?,
    selectedCaptionId: String?,
    canUndo: Boolean,
    canRedo: Boolean,
    segmentThumbnails: Map<String, List<Bitmap>>,
    musicWaveform: FloatArray?,
    clipWaveforms: Map<Int, FloatArray>,
    onPlayheadChange: (Long) -> Unit,
    onZoomChange: (Float) -> Unit,
    onSelectVideoItem: (String) -> Unit,
    onSelectCaption: (String) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSplit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pxPerSecDp = (46f * zoom).dp
    val totalSec = usToSec(project.totalDurationUs).coerceAtLeast(8.0)
    val totalWidthDp = (pxPerSecDp * totalSec.toFloat())
    val density = LocalDensity.current
    val pxPerUs = with(density) { (pxPerSecDp.toPx() / 1_000_000f) }
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .padding(vertical = 6.dp),
    ) {
        // Timeline Action Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            IconButton(onClick = onUndo, enabled = canUndo, modifier = Modifier.size(48.dp).testTag("undo_button")) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo", modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onRedo, enabled = canRedo, modifier = Modifier.size(48.dp).testTag("redo_button")) {
                Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo", modifier = Modifier.size(18.dp))
            }
            IconButton(
                onClick = onSplit,
                enabled = project.mainVideoTrack.items.isNotEmpty(),
                modifier = Modifier.size(48.dp).testTag("split_button"),
            ) {
                Icon(Icons.Default.ContentCut, contentDescription = "Split at playhead", modifier = Modifier.size(18.dp))
            }
            IconButton(
                onClick = onDuplicate,
                enabled = selectedVideoItemId != null,
                modifier = Modifier.size(48.dp).testTag("duplicate_button"),
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Duplicate clip", modifier = Modifier.size(18.dp))
            }
            IconButton(
                onClick = onDelete,
                enabled = selectedVideoItemId != null,
                modifier = Modifier.size(48.dp).testTag("delete_segment_button"),
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Delete clip", modifier = Modifier.size(18.dp))
            }
            IconButton(
                onClick = onMoveLeft,
                enabled = selectedVideoItemId != null,
                modifier = Modifier.size(48.dp).testTag("move_left_button"),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Move clip left", modifier = Modifier.size(16.dp))
            }
            IconButton(
                onClick = onMoveRight,
                enabled = selectedVideoItemId != null,
                modifier = Modifier.size(48.dp).testTag("move_right_button"),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Move clip right", modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(6.dp))
            IconButton(
                onClick = { onZoomChange((zoom - 0.25f).coerceIn(0.5f, 3.0f)) },
                modifier = Modifier.size(48.dp).testTag("zoom_out_button"),
            ) {
                Icon(Icons.Default.ZoomOut, contentDescription = "Zoom out timeline", modifier = Modifier.size(18.dp))
            }
            Text("${round1(zoom.toDouble())}x", style = MonoTimecodeStyle)
            IconButton(
                onClick = { onZoomChange((zoom + 0.25f).coerceIn(0.5f, 3.0f)) },
                modifier = Modifier.size(48.dp).testTag("zoom_in_button"),
            ) {
                Icon(Icons.Default.ZoomIn, contentDescription = "Zoom in timeline", modifier = Modifier.size(18.dp))
            }
        }

        // Scrollable Multi-Track Canvas + Playhead
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState)
                .padding(horizontal = 10.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(totalWidthDp)
                    .pointerInput(totalSec, pxPerUs) {
                        detectTapGestures { pos ->
                            val tappedUs = (pos.x / pxPerUs).toLong().coerceIn(0L, secToUs(totalSec))
                            onPlayheadChange(tappedUs)
                        }
                    },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    // Time Ruler
                    val rulerColor = MaterialTheme.colorScheme.onSurfaceVariant
                    Box(Modifier.fillMaxWidth().height(20.dp)) {
                        Canvas(Modifier.fillMaxSize()) {
                            val secLimit = ceil(totalSec).toInt()
                            for (s in 0..secLimit) {
                                val x = s * 1_000_000f * pxPerUs
                                val major = s % 5 == 0
                                drawLine(
                                    color = rulerColor.copy(alpha = if (major) 0.9f else 0.4f),
                                    start = Offset(x, if (major) 2f else 9f),
                                    end = Offset(x, size.height),
                                    strokeWidth = if (major) 2f else 1f,
                                )
                            }
                        }
                        val secLimit = ceil(totalSec).toInt()
                        for (s in 0..secLimit step 5) {
                            Text(
                                text = "${s}s",
                                style = MonoTimecodeStyle,
                                color = rulerColor,
                                modifier = Modifier.offset(x = (pxPerSecDp * s.toFloat()) + 3.dp, y = 0.dp),
                            )
                        }
                    }

                    // V1 Video Track
                    val vItems = project.mainVideoTrack.items
                    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (vItems.isEmpty()) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Text(
                                    "  V1 · Video track empty — add clips below",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            vItems.forEach { item ->
                                val widthDp = (pxPerSecDp * usToSec(item.timelineDurationUs).toFloat()).coerceAtLeast(32.dp)
                                val selected = item.id == selectedVideoItemId
                                val thumbs = segmentThumbnails[item.id].orEmpty()
                                Box(
                                    modifier = Modifier
                                        .width(widthDp)
                                        .fillMaxHeight()
                                        .padding(end = 2.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                        .border(
                                            width = if (selected) 2.dp else 1.dp,
                                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                            shape = RoundedCornerShape(6.dp),
                                        )
                                        .clickable { onSelectVideoItem(item.id) }
                                        .testTag("timeline_clip_${item.id}"),
                                ) {
                                    if (thumbs.isNotEmpty()) {
                                        Row(Modifier.fillMaxSize()) {
                                            thumbs.forEach { bmp ->
                                                Image(
                                                    bitmap = bmp.asImageBitmap(),
                                                    contentDescription = null,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                                )
                                            }
                                        }
                                    }
                                    Row(
                                        modifier = Modifier
                                            .align(Alignment.BottomStart)
                                            .fillMaxWidth()
                                            .background(Color(0x99000000))
                                            .padding(horizontal = 4.dp, vertical = 1.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            "#${item.sourceClipIndex + 1} · ${round1(usToSec(item.timelineDurationUs))}s",
                                            style = MonoTimecodeStyle,
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        if (item.editedByUser) {
                                            Icon(Icons.Default.Lock, contentDescription = "Manual edit", tint = Color.White, modifier = Modifier.size(10.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // A1 Audio / Waveform Track
                    val audioItems = project.mainAudioTrack.items
                    val wave = musicWaveform ?: project.mainVideoTrack.items.firstOrNull()?.let { clipWaveforms[it.sourceClipIndex] }
                    val waveColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(28.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)),
                    ) {
                        if (wave != null && wave.isNotEmpty()) {
                            Canvas(Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 3.dp)) {
                                val barW = (size.width / wave.size).coerceAtLeast(2f)
                                wave.forEachIndexed { i, amp ->
                                    val h = (amp.coerceIn(0.08f, 1f) * size.height)
                                    val x = i * barW
                                    drawLine(
                                        color = waveColor,
                                        start = Offset(x, (size.height - h) / 2f),
                                        end = Offset(x, (size.height + h) / 2f),
                                        strokeWidth = (barW * 0.6f).coerceAtLeast(1.5f),
                                    )
                                }
                            }
                        }
                        Text(
                            text = audioItems.firstOrNull()?.title?.let { "A1 · $it" } ?: "A1 · Original / Mood Audio",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 6.dp),
                        )
                    }

                    // T1 Caption Track
                    val captions = project.mainCaptionTrack.items
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(24.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer),
                    ) {
                        if (captions.isEmpty()) {
                            Text(
                                "T1 · No captions yet (generate in Captions tab)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 6.dp),
                            )
                        } else {
                            captions.forEach { cap ->
                                val xDp = pxPerSecDp * usToSec(cap.startUs).toFloat()
                                val wDp = (pxPerSecDp * usToSec(cap.durationUs).toFloat()).coerceAtLeast(26.dp)
                                val isSel = cap.id == selectedCaptionId
                                Surface(
                                    onClick = { onSelectCaption(cap.id) },
                                    color = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                                    contentColor = if (isSel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                    shape = RoundedCornerShape(4.dp),
                                    modifier = Modifier
                                        .offset(x = xDp, y = 2.dp)
                                        .width(wDp)
                                        .height(20.dp),
                                ) {
                                    Text(
                                        text = cap.text,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                // White Playhead Line
                val playheadDp = with(density) { (playheadUs * pxPerUs).toDp() }
                Box(
                    modifier = Modifier
                        .offset(x = playheadDp)
                        .width(2.dp)
                        .height(136.dp)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}
