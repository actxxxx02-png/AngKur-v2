package com.promptcut.app.ai

import com.promptcut.app.Settings
import com.promptcut.app.ai.providers.ClaudeProvider
import com.promptcut.app.ai.providers.GeminiProvider
import com.promptcut.app.ai.providers.GroqProvider
import com.promptcut.app.core.AppError
import com.promptcut.app.core.AppResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach

/**
 * Routes AI requests across Gemini, Groq, and Anthropic Claude with strict free-first & consent guarantees:
 * - Visual tasks prefer Google Gemini (free tier) when available.
 * - Text planning / reasoning tasks prefer Groq (free tier) when available & not rate-limited, falling back to Gemini.
 * - Anthropic Claude is NEVER selected automatically or silently; it is only used when explicitly chosen AND
 *   the user has granted explicit paid-API consent in Settings.
 */
class ProviderRouter(
    private val settings: Settings,
    private val providers: Map<ProviderId, AiProvider> = mapOf(
        ProviderId.GEMINI to GeminiProvider(),
        ProviderId.GROQ to GroqProvider(),
        ProviderId.CLAUDE to ClaudeProvider { settings.privacy.claudePaidConsentGranted },
    ),
) {
    data class RoutingDecision(
        val providerId: ProviderId,
        val reason: String,
        val isPaid: Boolean,
    )

    private val rateLimitedUntilMs = mutableMapOf<ProviderId, Long>()

    fun providerFor(id: ProviderId): AiProvider = providers.getValue(id)

    fun isRateLimitedNow(id: ProviderId, nowMs: Long = System.currentTimeMillis()): Boolean =
        (rateLimitedUntilMs[id] ?: 0L) > nowMs

    fun markRateLimited(id: ProviderId, cooldownMs: Long = 60_000L, nowMs: Long = System.currentTimeMillis()) {
        rateLimitedUntilMs[id] = nowMs + cooldownMs
    }

    /**
     * Selects the best provider according to task needs, configured keys, quota cooldowns, and consent.
     */
    fun chooseProvider(
        needsVision: Boolean,
        manualOverride: ProviderId? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): AppResult<RoutingDecision> {
        val chosenManual = manualOverride ?: if (!settings.routingModeAuto) settings.manualProviderId else null
        if (chosenManual != null) {
            return validateSpecificProvider(chosenManual, "Selected manually by user")
        }

        val hasGemini = settings.hasKey(ProviderId.GEMINI)
        val hasGroq = settings.hasKey(ProviderId.GROQ)
        val geminiCool = !isRateLimitedNow(ProviderId.GEMINI, nowMs)
        val groqCool = !isRateLimitedNow(ProviderId.GROQ, nowMs)

        // Free-first routing: never auto-route to paid Claude.
        if (needsVision) {
            if (hasGemini && geminiCool) {
                return AppResult.Success(RoutingDecision(ProviderId.GEMINI, "Free-first vision routing (Gemini)", isPaid = false))
            }
            if (hasGroq && groqCool) {
                return AppResult.Success(RoutingDecision(ProviderId.GROQ, "Fallback text-based clip understanding (Groq free tier)", isPaid = false))
            }
            if (hasGemini) {
                return AppResult.Success(RoutingDecision(ProviderId.GEMINI, "Retrying Gemini vision", isPaid = false))
            }
        } else {
            if (hasGroq && groqCool) {
                return AppResult.Success(RoutingDecision(ProviderId.GROQ, "Free-first fast text planning (Groq)", isPaid = false))
            }
            if (hasGemini && geminiCool) {
                return AppResult.Success(RoutingDecision(ProviderId.GEMINI, "Free-first text planning (Gemini)", isPaid = false))
            }
            if (hasGroq) {
                return AppResult.Success(RoutingDecision(ProviderId.GROQ, "Retrying Groq", isPaid = false))
            }
            if (hasGemini) {
                return AppResult.Success(RoutingDecision(ProviderId.GEMINI, "Retrying Gemini", isPaid = false))
            }
        }

        // If only Claude is configured, STILL refuse to run silently without explicit paid consent & manual opt-in.
        if (settings.hasKey(ProviderId.CLAUDE)) {
            return if (!settings.privacy.claudePaidConsentGranted) {
                AppResult.Failure(AppError.PaidConsentRequired("Anthropic Claude"))
            } else {
                AppResult.Failure(
                    AppError.Validation(
                        "No free provider key is configured. Switch to Manual mode and select Claude in Settings to use paid Claude credits."
                    )
                )
            }
        }

        return AppResult.Failure(
            AppError.Auth("AI service", message = "No API key configured. Open Settings to add a free Gemini or Groq key.")
        )
    }

    private fun validateSpecificProvider(id: ProviderId, reason: String): AppResult<RoutingDecision> {
        if (id == ProviderId.CLAUDE && !settings.privacy.claudePaidConsentGranted) {
            return AppResult.Failure(AppError.PaidConsentRequired("Anthropic Claude"))
        }
        val key = settings.keyFor(id)
        if (key.isBlank()) {
            return AppResult.Failure(
                AppError.Auth(id.shortName, message = "No ${id.shortName} API key saved. Add it in Settings.")
            )
        }
        return AppResult.Success(RoutingDecision(id, reason, isPaid = id == ProviderId.CLAUDE))
    }

    suspend fun testConnection(id: ProviderId, rawKey: String, preferredModel: String? = null): AppResult<String> {
        if (id == ProviderId.CLAUDE && !settings.privacy.claudePaidConsentGranted) {
            return AppResult.Failure(AppError.PaidConsentRequired("Anthropic Claude"))
        }
        return providerFor(id).testConnection(rawKey.trim(), preferredModel)
    }

    suspend fun complete(
        request: AiRequest,
        needsVision: Boolean = request.frames.isNotEmpty(),
        manualOverride: ProviderId? = null,
    ): AppResult<AiResponse> {
        val decision = when (val d = chooseProvider(needsVision, manualOverride)) {
            is AppResult.Success -> d.value
            is AppResult.Failure -> return d
        }
        val providerId = decision.providerId
        val privacyCheck = settings.privacy.validateRequest(
            provider = providerId,
            frameCount = if (providerFor(providerId).capabilities.supportsVision) request.frames.size else 0,
        )
        val effectiveFrames = when (privacyCheck) {
            is AppResult.Success -> request.frames.take(privacyCheck.value.sendsSampledFrames)
            is AppResult.Failure -> return privacyCheck
        }
        val safeRequest = request.copy(frames = effectiveFrames)
        val key = settings.keyFor(providerId)
        val model = settings.modelFor(providerId)

        val result = providerFor(providerId).complete(key, model, safeRequest)
        when (result) {
            is AppResult.Success -> {
                settings.setModelFor(providerId, result.value.model)
                result.value.usage?.let { settings.recordUsage(it.inputTokens, it.outputTokens) }
                return result
            }
            is AppResult.Failure -> {
                if (result.error is AppError.RateLimit) {
                    markRateLimited(providerId)
                    // Free-to-free automatic failover only (NEVER auto-failover to paid Claude!).
                    if (manualOverride == null && settings.routingModeAuto) {
                        val fallbackId = when (providerId) {
                            ProviderId.GROQ -> ProviderId.GEMINI.takeIf { settings.hasKey(it) && !isRateLimitedNow(it) }
                            ProviderId.GEMINI -> ProviderId.GROQ.takeIf { settings.hasKey(it) && !isRateLimitedNow(it) }
                            ProviderId.CLAUDE -> null
                        }
                        if (fallbackId != null) {
                            val fbKey = settings.keyFor(fallbackId)
                            val fbFrames = if (providerFor(fallbackId).capabilities.supportsVision) effectiveFrames else emptyList()
                            val fbResult = providerFor(fallbackId).complete(fbKey, settings.modelFor(fallbackId), safeRequest.copy(frames = fbFrames))
                            if (fbResult is AppResult.Success) {
                                settings.setModelFor(fallbackId, fbResult.value.model)
                                fbResult.value.usage?.let { settings.recordUsage(it.inputTokens, it.outputTokens) }
                                return fbResult
                            }
                        }
                    }
                }
                return result
            }
        }
    }

    fun stream(
        request: AiRequest,
        needsVision: Boolean = request.frames.isNotEmpty(),
        manualOverride: ProviderId? = null,
    ): Flow<AiStreamEvent> = flow {
        val decision = when (val d = chooseProvider(needsVision, manualOverride)) {
            is AppResult.Success -> d.value
            is AppResult.Failure -> {
                emit(AiStreamEvent.Error(d.error))
                return@flow
            }
        }
        val providerId = decision.providerId
        val privacyCheck = settings.privacy.validateRequest(
            provider = providerId,
            frameCount = if (providerFor(providerId).capabilities.supportsVision) request.frames.size else 0,
        )
        val effectiveFrames = when (privacyCheck) {
            is AppResult.Success -> request.frames.take(privacyCheck.value.sendsSampledFrames)
            is AppResult.Failure -> {
                emit(AiStreamEvent.Error(privacyCheck.error))
                return@flow
            }
        }
        val key = settings.keyFor(providerId)
        val model = settings.modelFor(providerId)
        providerFor(providerId).stream(key, model, request.copy(frames = effectiveFrames))
            .onEach { ev ->
                if (ev is AiStreamEvent.Completed) {
                    settings.setModelFor(providerId, ev.response.model)
                    ev.response.usage?.let { settings.recordUsage(it.inputTokens, it.outputTokens) }
                } else if (ev is AiStreamEvent.Error && ev.error is AppError.RateLimit) {
                    markRateLimited(providerId)
                }
            }
            .collect { emit(it) }
    }
}
