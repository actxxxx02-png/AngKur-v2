package com.promptcut.app.ui.components

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn as AndroidXOptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.promptcut.app.Clip
import com.promptcut.app.EditorMediaHelper
import com.promptcut.app.timeline.CaptionStyle
import com.promptcut.app.timeline.TimelineProject
import com.promptcut.app.timeline.usToSec
import com.promptcut.app.ui.theme.MonoTimecodeStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

@AndroidXOptIn(UnstableApi::class)
@Composable
fun PreviewStage(
    project: TimelineProject,
    clips: List<Clip>,
    renderedOutput: File?,
    playheadUs: Long,
    isPlaying: Boolean,
    preview720p: Boolean,
    onPlayheadChange: (Long) -> Unit,
    onTogglePlay: () -> Unit,
    onAddMedia: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val totalUs = project.totalDurationUs.coerceAtLeast(0L)
    val activeCaptions = remember(project, playheadUs) { project.activeCaptionsAt(playheadUs) }

    // Playback loop for timeline scrub preview when no final rendered MP4 is actively driving ExoPlayer
    LaunchedEffect(isPlaying, renderedOutput, totalUs) {
        if (isPlaying && renderedOutput == null && totalUs > 0L) {
            var lastTick = System.currentTimeMillis()
            while (isPlaying) {
                delay(40L)
                val now = System.currentTimeMillis()
                val dtUs = (now - lastTick) * 1000L
                lastTick = now
                val next = playheadUs + dtUs
                if (next >= totalUs) {
                    onPlayheadChange(0L)
                    onTogglePlay()
                    break
                } else {
                    onPlayheadChange(next)
                }
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(210.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            when {
                renderedOutput != null -> {
                    val player = remember { ExoPlayer.Builder(ctx).build() }
                    LaunchedEffect(renderedOutput.absolutePath) {
                        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(renderedOutput)))
                        player.prepare()
                        player.playWhenReady = true
                    }
                    DisposableEffect(Unit) { onDispose { player.release() } }
                    AndroidView(
                        factory = {
                            PlayerView(it).apply {
                                this.player = player
                                setShowNextButton(false)
                                setShowPreviousButton(false)
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                project.mainVideoTrack.items.isNotEmpty() -> {
                    val activeItem = remember(project, playheadUs) {
                        project.mainVideoTrack.items.firstOrNull { playheadUs in it.timelineStartUs..it.timelineEndUs }
                            ?: project.mainVideoTrack.items.last()
                    }
                    val clip = remember(clips, activeItem.sourceClipIndex) {
                        clips.firstOrNull { it.meta.index == activeItem.sourceClipIndex }
                    }
                    var frameBmp by remember { mutableStateOf<Bitmap?>(null) }
                    LaunchedEffect(clip?.uri, activeItem.id, playheadUs / 150_000L) {
                        if (clip == null) {
                            frameBmp = null
                            return@LaunchedEffect
                        }
                        val localOutUs = (playheadUs - activeItem.timelineStartUs).coerceAtLeast(0L)
                        val srcUs = (activeItem.sourceStartUs + (localOutUs * activeItem.speed).toLong())
                            .coerceIn(activeItem.sourceStartUs, activeItem.sourceEndUs)
                        val maxEdge = if (preview720p) 540 else 720
                        val bmp = withContext(Dispatchers.IO) {
                            runCatching {
                                MediaMetadataRetriever().use { r ->
                                    r.setDataSource(ctx, clip.uri)
                                    r.getScaledFrameAtTime(srcUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, maxEdge, maxEdge)
                                }
                            }.getOrNull()
                        }
                        if (bmp != null) frameBmp = bmp else if (frameBmp == null) frameBmp = clip.thumbnail
                    }
                    val shown = frameBmp ?: clip?.thumbnail
                    if (shown != null) {
                        Image(
                            bitmap = shown.asImageBitmap(),
                            contentDescription = "Timeline frame preview",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Text(
                            "Decoding frame…",
                            color = Color(0xFFA0A0A0),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                else -> {
                    Surface(
                        onClick = onAddMedia,
                        color = Color(0xFF141414),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag("empty_preview_add_media"),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(Icons.Default.Movie, contentDescription = "Add video clips", tint = Color.White, modifier = Modifier.size(28.dp))
                            Text("Tap to import video clips", color = Color.White, style = MaterialTheme.typography.titleSmall)
                            Text("On-device analysis · 720p preview ready", color = Color(0xFF9A9A9A), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            // Top stage badges
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StagePill(project.aspect.key)
                StagePill(if (preview720p) "720p Preview" else project.resolution.label)
                if (renderedOutput != null) StagePill("Rendered MP4")
            }

            // Title overlay preview
            if (!project.titleOverlay.isNullOrBlank() && usToSec(playheadUs) <= 3.0) {
                Text(
                    text = project.titleOverlay,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(Color(0x99000000), RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }

            // Live synchronized captions preview
            if (activeCaptions.isNotEmpty() && renderedOutput == null) {
                val cap = activeCaptions.first()
                val bg = when (cap.style) {
                    CaptionStyle.BOLD_BOX -> Color(0xE0000000)
                    CaptionStyle.SUBTITLE_BAR -> Color(0xA6000000)
                    CaptionStyle.MINIMAL -> Color.Transparent
                }
                Text(
                    text = cap.text,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .background(bg, RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }

        // Transport bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${EditorMediaHelper.formatTimecode(playheadUs)} / ${EditorMediaHelper.formatTimecode(totalUs)}",
                style = MonoTimecodeStyle,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag("playhead_timecode"),
            )
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = { onPlayheadChange((playheadUs - 1_000_000L).coerceAtLeast(0L)) },
                modifier = Modifier.size(48.dp).testTag("step_back_button"),
            ) {
                Icon(Icons.Default.FastRewind, contentDescription = "Back 1 second", modifier = Modifier.size(20.dp))
            }
            IconButton(
                onClick = onTogglePlay,
                enabled = project.mainVideoTrack.items.isNotEmpty(),
                modifier = Modifier.size(48.dp).testTag("play_pause_button"),
            ) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(24.dp),
                )
            }
            IconButton(
                onClick = { onPlayheadChange((playheadUs + 1_000_000L).coerceAtMost(totalUs)) },
                modifier = Modifier.size(48.dp).testTag("step_forward_button"),
            ) {
                Icon(Icons.Default.FastForward, contentDescription = "Forward 1 second", modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun StagePill(label: String) {
    Text(
        text = label,
        color = Color(0xFFE5E5E5),
        style = MonoTimecodeStyle,
        modifier = Modifier
            .background(Color(0xB3121212), RoundedCornerShape(6.dp))
            .border(0.5.dp, Color(0xFF3E3E3E), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
