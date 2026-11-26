package com.promptcut.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.promptcut.app.agent.AgentEvent
import com.promptcut.app.agent.AgentExecutor
import com.promptcut.app.agent.AgentLoop
import com.promptcut.app.agent.AgentPlanner
import com.promptcut.app.agent.AgentToolInvocation
import com.promptcut.app.agent.AgentTools
import com.promptcut.app.agent.AgentValidator
import com.promptcut.app.ai.AiCapabilities
import com.promptcut.app.ai.AiProvider
import com.promptcut.app.ai.AiRequest
import com.promptcut.app.ai.AiResponse
import com.promptcut.app.ai.AiStreamEvent
import com.promptcut.app.ai.AiToolCall
import com.promptcut.app.ai.ConversationManager
import com.promptcut.app.ai.ProviderId
import com.promptcut.app.ai.ProviderRouter
import com.promptcut.app.core.AppError
import com.promptcut.app.core.AppResult
import com.promptcut.app.security.KeyVault
import com.promptcut.app.security.SoftwareAesGcmEngine
import com.promptcut.app.timeline.TimelineCommand
import com.promptcut.app.timeline.TimelineEngine
import com.promptcut.app.timeline.TimelineProject
import com.promptcut.app.timeline.VideoClipItem
import com.promptcut.app.timeline.VideoTrack
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EditingAgentTest {

    private lateinit var settings: Settings
    private val sampleClips = listOf(
        ClipMeta(0, "game.mp4", 12.0, 1080, 1920, true),
        ClipMeta(1, "reaction.mp4", 6.0, 1080, 1920, true),
    )
    private val sampleInsights = mapOf(
        0 to ClipInsight(
            clip = 0,
            shots = listOf(Shot(0.0, 6.0, listOf("action"), 0, false, interest = 2)),
            speech = listOf(Span(1.0, 3.0, "Huge clutch victory")),
            gamingEvents = listOf("HEADSHOT @ 2.5s"),
        )
    )

    private class ScriptedProvider(
        private val onComplete: suspend (AiRequest) -> AppResult<AiResponse>,
    ) : AiProvider {
        override val id = ProviderId.GROQ
        override val capabilities = AiCapabilities(false, true, true, true, 0, true, false, 32_000)
        override suspend fun listModels(apiKey: String) = AppResult.Success(listOf("fake-model"))
        override suspend fun testConnection(apiKey: String, preferredModel: String?) = AppResult.Success("fake-model")
        override suspend fun complete(apiKey: String, preferredModel: String?, request: AiRequest) = onComplete(request)
        override fun stream(apiKey: String, preferredModel: String?, request: AiRequest): Flow<AiStreamEvent> = flowOf()
    }

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("promptcut", Context.MODE_PRIVATE).edit().clear().commit()
        settings = Settings(ctx, KeyVault(SoftwareAesGcmEngine()))
        settings.setKeyFor(ProviderId.GROQ, "gsk_test_agent_key")
    }

    @Test
    fun agentTools_rejectUnknownToolsAndInvalidBounds() {
        val unknown = AgentTools.parseToolCall(AiToolCall("1", "exec_shell", """{"cmd":"rm -rf"}"""))
        assertTrue(unknown is AppResult.Failure)

        val badSpeed = AgentTools.parseToolCall(AiToolCall("2", "set_speed", """{"item_id":"seg1","speed":99.0}"""))
        assertTrue(badSpeed is AppResult.Failure)

        val badTrim = AgentTools.parseToolCall(AiToolCall("3", "trim_segment", """{"item_id":"seg1","start_sec":3.0,"end_sec":2.0}"""))
        assertTrue(badTrim is AppResult.Failure)

        val valid = AgentTools.parseToolCall(AiToolCall("4", "set_look", """{"color":"cinematic","title":"Victory"}"""))
        assertTrue(valid is AppResult.Success)
    }

    @Test
    fun validator_protectsManualEditsUntilApproved() {
        val manualItem = VideoClipItem(
            id = "manual_1",
            sourceClipIndex = 0,
            timelineStartUs = 0L,
            sourceStartUs = 0L,
            sourceEndUs = 5_000_000L,
            editedByUser = true,
        )
        val project = TimelineProject(videoTracks = listOf(VideoTrack(items = listOf(manualItem))))
        val tools = listOf(AgentToolInvocation.SetSpeed("manual_1", 1.5))

        val unapproved = AgentValidator.validate(tools, project, sampleClips, allowOverwriteManualEdits = false).getOrNull()!!
        assertTrue(unapproved.requiresManualEditApproval)
        assertEquals(listOf("manual_1"), unapproved.affectedManualItemIds)

        val approved = AgentValidator.validate(tools, project, sampleClips, allowOverwriteManualEdits = true).getOrNull()!!
        assertFalse(approved.requiresManualEditApproval)
    }

    @Test
    fun agentLoop_executesToolBatchAtomicallyAndSupportsUndoAndTimeouts() = runTest {
        val initialItem = VideoClipItem(
            id = "seg_0",
            sourceClipIndex = 0,
            timelineStartUs = 0L,
            sourceStartUs = 0L,
            sourceEndUs = 6_000_000L,
            editedByUser = false,
        )
        val engine = TimelineEngine(TimelineProject(videoTracks = listOf(VideoTrack(items = listOf(initialItem)))))
        val conv = ConversationManager()
        val provider = ScriptedProvider {
            AppResult.Success(
                AiResponse(
                    text = "Trimmed clip, sped up action, and generated subtitles.",
                    model = "fake-model",
                    provider = ProviderId.GROQ,
                    toolCalls = listOf(
                        AiToolCall("t1", "trim_segment", """{"item_id":"seg_0","start_sec":0.5,"end_sec":4.5}"""),
                        AiToolCall("t2", "set_speed", """{"item_id":"seg_0","speed":1.25}"""),
                        AiToolCall("t3", "generate_captions", """{"style":"BOLD_BOX"}"""),
                    ),
                )
            )
        }
        val router = ProviderRouter(settings, mapOf(ProviderId.GROQ to provider, ProviderId.GEMINI to provider, ProviderId.CLAUDE to provider))
        val loop = AgentLoop(AgentPlanner(router, conv), AgentExecutor(engine), engine, conv, timeoutMs = 5_000L)

        val events = loop.run(
            userPrompt = "Speed it up and add captions",
            clips = sampleClips,
            insights = sampleInsights,
            clipUris = mapOf(0 to "file:///game.mp4"),
            frames = emptyList(),
            freshEdit = false,
        ).toList()

        assertTrue(events.any { it is AgentEvent.Completed })
        assertEquals(1.25, engine.project.mainVideoTrack.items.first().speed, 0.01)
        assertEquals(1, engine.project.mainCaptionTrack.items.size)
        assertTrue(conv.messages.isNotEmpty())

        // Single Undo reverts entire AI batch cleanly!
        assertTrue(engine.undo().isSuccess)
        assertEquals(1.0, engine.project.mainVideoTrack.items.first().speed, 0.01)
        assertEquals(0, engine.project.mainCaptionTrack.items.size)

        // Timeout handling returns clean Failed event rather than hanging or crashing
        val slowProvider = ScriptedProvider {
            delay(1_000)
            AppResult.Success(AiResponse("late", "fake", ProviderId.GROQ))
        }
        val slowRouter = ProviderRouter(settings, mapOf(ProviderId.GROQ to slowProvider, ProviderId.GEMINI to slowProvider, ProviderId.CLAUDE to slowProvider))
        val shortLoop = AgentLoop(AgentPlanner(slowRouter, conv), AgentExecutor(engine), engine, conv, timeoutMs = 20L)
        val timeoutEvents = shortLoop.run("edit", sampleClips, sampleInsights, emptyMap(), emptyList(), freshEdit = false).toList()
        val failed = timeoutEvents.filterIsInstance<AgentEvent.Failed>().firstOrNull()
        assertTrue(failed != null && failed.error is AppError.Timeout)
    }
}
