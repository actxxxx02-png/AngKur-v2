package com.promptcut.app.core

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

/**
 * Typed error hierarchy across PromptCut: security, networking, AI providers,
 * timeline validation, media analysis, rendering, and project storage.
 */
sealed class AppError(
    open val message: String,
    open val detail: String = "",
    open val retryable: Boolean = false,
    open val cause: Throwable? = null,
) {
    data class Network(
        override val message: String = "Network connection unavailable. Check your internet and try again.",
        override val detail: String = "",
        override val cause: Throwable? = null,
    ) : AppError(message, detail, retryable = true, cause = cause)

    data class Timeout(
        override val message: String = "The request timed out. Try again or shorten your clips.",
        override val detail: String = "",
        override val cause: Throwable? = null,
    ) : AppError(message, detail, retryable = true, cause = cause)

    data class Cancelled(
        override val message: String = "Operation cancelled.",
        override val detail: String = "",
        override val cause: Throwable? = null,
    ) : AppError(message, detail, retryable = false, cause = cause)

    data class Auth(
        val providerName: String,
        val httpCode: Int = 401,
        override val message: String = "Your $providerName API key was rejected. Open Settings and paste a valid key.",
        override val detail: String = "",
        override val cause: Throwable? = null,
    ) : AppError(message, detail, retryable = false, cause = cause)

    data class RateLimit(
        val providerName: String,
        val httpCode: Int = 429,
        override val message: String = "Rate limit reached on $providerName. Wait a moment or switch provider in Settings.",
        override val detail: String = "",
        override val cause: Throwable? = null,
    ) : AppError(message, detail, retryable = true, cause = cause)

    data class ProviderUnavailable(
        val providerName: String,
        val httpCode: Int = 503,
        override val message: String = "$providerName is temporarily busy ($httpCode). Try again shortly.",
        override val detail: String = "",
        override val cause: Throwable? = null,
    ) : AppError(message, detail, retryable = true, cause = cause)

    data class PaidConsentRequired(
        val providerName: String = "Anthropic Claude",
        override val message: String = "$providerName uses paid API credits. Enable paid API consent in Settings first.",
        override val detail: String = "",
    ) : AppError(message, detail, retryable = false)

    data class PrivacyBlocked(
        override val message: String,
        override val detail: String = "",
    ) : AppError(message, detail, retryable = false)

    data class Security(
        override val message: String = "Secure key storage failed. Please re-enter your API key.",
        override val detail: String = "",
        override val cause: Throwable? = null,
    ) : AppError(message, detail, retryable = false, cause = cause)

    data class Validation(
        override val message: String,
        override val detail: String = "",
        override val cause: Throwable? = null,
    ) : AppError(message, detail, retryable = false, cause = cause)

    data class Media(
        override val message: String,
        override val detail: String = "",
        override val cause: Throwable? = null,
    ) : AppError(message, detail, retryable = false, cause = cause)

    data class Render(
        override val message: String,
        override val detail: String = "",
        override val cause: Throwable? = null,
    ) : AppError(message, detail, retryable = true, cause = cause)

    data class Storage(
        override val message: String,
        override val detail: String = "",
        override val cause: Throwable? = null,
    ) : AppError(message, detail, retryable = false, cause = cause)

    data class Unknown(
        override val message: String = "Something went wrong.",
        override val detail: String = "",
        override val cause: Throwable? = null,
    ) : AppError(message, detail, retryable = false, cause = cause)

    companion object {
        fun fromThrowable(t: Throwable, providerName: String = "AI service"): AppError = when (t) {
            is TimeoutCancellationException, is SocketTimeoutException ->
                Timeout(detail = t.message.orEmpty(), cause = t)
            is CancellationException -> throw t
            is UnknownHostException ->
                Network("No internet connection. Check Wi-Fi or mobile data.", t.message.orEmpty(), t)
            is IOException ->
                Network("Network error: ${t.message ?: "connection interrupted"}.", t.message.orEmpty(), t)
            is com.promptcut.app.ApiException -> when (t.code) {
                401, 403 -> Auth(providerName, t.code, t.message ?: "Invalid API key.", t.body.take(240), t)
                429 -> RateLimit(providerName, 429, t.message ?: "Rate limit reached on $providerName.", t.body.take(240), t)
                in 500..599 -> ProviderUnavailable(providerName, t.code, t.message ?: "$providerName is busy.", t.body.take(240), t)
                else -> Validation(t.message ?: "Request failed (${t.code}).", t.body.take(240), t)
            }
            is com.promptcut.app.RenderException ->
                Render(t.message ?: "Video rendering failed.", cause = t)
            is com.promptcut.app.PlanException ->
                Validation(t.message ?: "Invalid edit plan.", cause = t)
            else -> Unknown(t.message ?: "Unexpected error.", cause = t)
        }
    }
}
