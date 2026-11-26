package com.promptcut.app.ai.providers

import android.util.Base64
import com.promptcut.app.ModelPicker
import com.promptcut.app.ai.AiCapabilities
import com.promptcut.app.ai.AiProvider
import com.promptcut.app.ai.AiRequest
import com.promptcut.app.ai.AiResponse
import com.promptcut.app.ai.AiRole
import com.promptcut.app.ai.AiStreamEvent
import com.promptcut.app.ai.AiToolCall
import com.promptcut.app.ai.AiUsage
import com.promptcut.app.ai.ProviderId
import com.promptcut.app.core.AppError
import com.promptcut.app.core.AppResult
import com.promptcut.app.core.runCatchingResult
import com.promptcut.app.tryModels
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONArray
import org.json.JSONObject

class GeminiProvider : AiProvider {
    override val id: ProviderId = ProviderId.GEMINI

    override val capabilities = AiCapabilities(
        supportsVision = true,
        supportsStreaming = true,
        supportsToolCalling = true,
        supportsJsonMode = true,
        maxFrames = 36,
        isFreeTierAvailable = true,
        requiresPaidConsent = false,
        contextWindowTokens = 1_000_000,
    )

    private val base = "https://generativelanguage.googleapis.com/v1beta"

    override suspend fun listModels(apiKey: String): AppResult<List<String>> = runCatchingResult("Gemini") {
        val json = ProviderHttp.request(
            method = "GET",
            url = "$base/models?pageSize=200",
            headers = mapOf("x-goog-api-key" to apiKey),
            body = null,
            providerName = "Gemini",
        )
        val models = JSONObject(json).optJSONArray("models") ?: JSONArray()
        val names = (0 until models.length()).mapNotNull { i ->
            val m = models.getJSONObject(i)
            val methods = m.optJSONArray("supportedGenerationMethods")?.toString().orEmpty()
            if ("generateContent" in methods) m.getString("name").removePrefix("models/") else null
        }
        ModelPicker.geminiRanked(names)
    }

    override suspend fun testConnection(apiKey: String, preferredModel: String?): AppResult<String> {
        return listModels(apiKey).flatMap { ranked ->
            val chosen = preferredModel?.takeIf { it in ranked } ?: ranked.firstOrNull()
            if (chosen == null) AppResult.Failure(AppError.ProviderUnavailable("Gemini", 404, "No compatible Gemini models available."))
            else AppResult.Success(chosen)
        }
    }

    override suspend fun complete(apiKey: String, preferredModel: String?, request: AiRequest): AppResult<AiResponse> =
        runCatchingResult("Gemini") {
            val bodyText = buildPayload(request).toString()
            val rankedFn: suspend () -> List<String> = {
                when (val res = listModels(apiKey)) {
                    is AppResult.Success -> res.value
                    is AppResult.Failure -> throw com.promptcut.app.ApiException(res.error.message)
                }
            }
            var parsedResp: AiResponse? = null
            val (usedModel, _) = tryModels(preferredModel, rankedFn, "Gemini") { m ->
                val raw = ProviderHttp.request(
                    method = "POST",
                    url = "$base/models/$m:generateContent",
                    headers = mapOf("x-goog-api-key" to apiKey),
                    body = bodyText,
                    providerName = "Gemini",
                    timeoutMs = request.timeoutMs,
                )
                parsedResp = parseResponse(raw, m)
                parsedResp!!.text
            }
            parsedResp ?: AiResponse("", usedModel, ProviderId.GEMINI)
        }

    override fun stream(apiKey: String, preferredModel: String?, request: AiRequest): Flow<AiStreamEvent> = flow {
        val model = preferredModel ?: when (val res = listModels(apiKey)) {
            is AppResult.Success -> res.value.firstOrNull() ?: "gemini-2.5-flash"
            is AppResult.Failure -> {
                emit(AiStreamEvent.Error(res.error))
                return@flow
            }
        }
        val bodyText = buildPayload(request).toString()
        val fullText = StringBuilder()
        val toolCalls = mutableListOf<AiToolCall>()
        var lastUsage: AiUsage? = null
        try {
            ProviderHttp.streamSse(
                url = "$base/models/$model:streamGenerateContent?alt=sse",
                headers = mapOf("x-goog-api-key" to apiKey),
                body = bodyText,
                providerName = "Gemini",
                timeoutMs = request.timeoutMs,
            ) { _, data ->
                val chunk = parseResponse(data, model, allowEmpty = true)
                if (chunk.text.isNotEmpty()) {
                    fullText.append(chunk.text)
                    emit(AiStreamEvent.Delta(chunk.text))
                }
                chunk.toolCalls.forEach { tc ->
                    toolCalls += tc
                    emit(AiStreamEvent.ToolCallReady(tc))
                }
                if (chunk.usage != null) lastUsage = chunk.usage
            }
            emit(AiStreamEvent.Completed(AiResponse(fullText.toString(), model, ProviderId.GEMINI, toolCalls, lastUsage)))
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            emit(AiStreamEvent.Error(AppError.fromThrowable(t, "Gemini")))
        }
    }

    private fun buildPayload(request: AiRequest): JSONObject {
        val contents = JSONArray()
        request.messages.forEachIndexed { idx, msg ->
            val parts = JSONArray()
            if (msg.role == AiRole.TOOL) {
                parts.put(
                    JSONObject().put(
                        "functionResponse",
                        JSONObject()
                            .put("name", msg.toolName ?: "tool")
                            .put("response", JSONObject().put("result", msg.content)),
                    )
                )
            } else {
                if (msg.content.isNotBlank()) parts.put(JSONObject().put("text", msg.content))
                msg.toolCalls.forEach { tc ->
                    parts.put(
                        JSONObject().put(
                            "functionCall",
                            JSONObject()
                                .put("name", tc.name)
                                .put("args", runCatching { JSONObject(tc.argumentsJson) }.getOrElse { JSONObject() }),
                        )
                    )
                }
                val isLastUser = msg.role == AiRole.USER && idx == request.messages.indexOfLast { it.role == AiRole.USER }
                if (isLastUser && request.frames.isNotEmpty()) {
                    request.frames.take(capabilities.maxFrames).forEach { f ->
                        parts.put(JSONObject().put("text", f.label))
                        parts.put(
                            JSONObject().put(
                                "inline_data",
                                JSONObject()
                                    .put("mime_type", "image/jpeg")
                                    .put("data", Base64.encodeToString(f.jpeg, Base64.NO_WRAP)),
                            )
                        )
                    }
                }
            }
            val role = when (msg.role) {
                AiRole.ASSISTANT -> "model"
                AiRole.USER, AiRole.TOOL, AiRole.SYSTEM -> "user"
            }
            if (parts.length() > 0) {
                contents.put(JSONObject().put("role", role).put("parts", parts))
            }
        }

        val genConfig = JSONObject()
            .put("temperature", request.temperature)
            .put("maxOutputTokens", request.maxTokens)
        if (request.jsonMode && request.tools.isEmpty()) {
            genConfig.put("responseMimeType", "application/json")
        }

        val payload = JSONObject()
            .put("contents", contents)
            .put("generationConfig", genConfig)

        if (request.systemPrompt.isNotBlank()) {
            payload.put(
                "system_instruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", request.systemPrompt))),
            )
        }

        if (request.tools.isNotEmpty()) {
            val decls = JSONArray()
            request.tools.forEach { t ->
                decls.put(
                    JSONObject()
                        .put("name", t.name)
                        .put("description", t.description)
                        .put("parameters", runCatching { JSONObject(t.parametersJsonSchema) }.getOrElse { JSONObject() })
                )
            }
            payload.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", decls)))
        }
        return payload
    }

    private fun parseResponse(raw: String, model: String, allowEmpty: Boolean = false): AiResponse {
        val root = JSONObject(raw)
        val cand = root.optJSONArray("candidates")?.optJSONObject(0)
        if (cand == null && !allowEmpty) {
            throw com.promptcut.app.ApiException("Gemini returned no answer (it may have been blocked). Try rephrasing.")
        }
        val outParts = cand?.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        val sb = StringBuilder()
        val toolCalls = mutableListOf<AiToolCall>()
        for (i in 0 until outParts.length()) {
            val part = outParts.getJSONObject(i)
            val txt = part.optString("text", "")
            if (txt.isNotEmpty()) sb.append(txt)
            val fc = part.optJSONObject("functionCall")
            if (fc != null) {
                val name = fc.optString("name", "")
                val args = fc.optJSONObject("args")?.toString() ?: "{}"
                if (name.isNotBlank()) {
                    toolCalls += AiToolCall(id = "gemini_${System.nanoTime()}_$i", name = name, argumentsJson = args)
                }
            }
        }
        val um = root.optJSONObject("usageMetadata")
        val usage = um?.let {
            AiUsage(
                inputTokens = it.optInt("promptTokenCount", 0),
                outputTokens = it.optInt("candidatesTokenCount", 0),
                estimatedCostUsd = 0.0,
            )
        }
        return AiResponse(sb.toString(), model, ProviderId.GEMINI, toolCalls, usage)
    }
}
