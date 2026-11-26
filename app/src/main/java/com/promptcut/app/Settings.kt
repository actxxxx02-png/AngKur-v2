package com.promptcut.app

import android.content.Context
import com.promptcut.app.ai.ProviderId
import com.promptcut.app.ai.toProviderId
import com.promptcut.app.core.AppResult
import com.promptcut.app.security.KeyVault
import com.promptcut.app.security.PrivacyManager

enum class ThemePreference(val label: String) {
    DARK("Dark (Studio)"),
    LIGHT("Light"),
    SYSTEM("System"),
}

enum class ExportResolution(val label: String, val shortEdge: Int) {
    P720("720p HD", 720),
    P1080("1080p Full HD", 1080),
}

/**
 * Encrypted settings & preferences store.
 * Keys stay on this phone only, encrypted via Android Keystore ([KeyVault]) with zero plaintext fallback.
 */
class Settings(context: Context, private val vault: KeyVault = KeyVault()) {
    private val prefs = context.getSharedPreferences("promptcut", Context.MODE_PRIVATE)
    val privacy = PrivacyManager(prefs)

    var provider: Provider
        get() = runCatching { Provider.valueOf(prefs.getString("provider", null) ?: "") }.getOrDefault(Provider.GEMINI)
        set(v) = prefs.edit().putString("provider", v.name).apply()

    var routingModeAuto: Boolean
        get() = prefs.getBoolean("routing_mode_auto", true)
        set(v) = prefs.edit().putBoolean("routing_mode_auto", v).apply()

    var manualProviderId: ProviderId
        get() = runCatching { ProviderId.valueOf(prefs.getString("manual_provider_id", null) ?: "") }
            .getOrDefault(provider.toProviderId())
        set(v) {
            prefs.edit().putString("manual_provider_id", v.name).apply()
            v.toLegacyProvider()?.let { provider = it }
        }

    var themePreference: ThemePreference
        get() = runCatching { ThemePreference.valueOf(prefs.getString("theme_pref", null) ?: "") }
            .getOrDefault(ThemePreference.DARK)
        set(v) = prefs.edit().putString("theme_pref", v.name).apply()

    var preview720pMode: Boolean
        get() = prefs.getBoolean("preview_720p", true)
        set(v) = prefs.edit().putBoolean("preview_720p", v).apply()

    var preferredResolution: ExportResolution
        get() = runCatching { ExportResolution.valueOf(prefs.getString("export_res", null) ?: "") }
            .getOrDefault(ExportResolution.P1080)
        set(v) = prefs.edit().putString("export_res", v.name).apply()

    var preferredFps: Int
        get() = prefs.getInt("export_fps", 30).coerceIn(24, 60)
        set(v) = prefs.edit().putInt("export_fps", v.coerceIn(24, 60)).apply()

    var preferredBitrateMbps: Int
        get() = prefs.getInt("export_bitrate_mbps", 8).coerceIn(4, 24)
        set(v) = prefs.edit().putInt("export_bitrate_mbps", v.coerceIn(4, 24)).apply()

    var lastSecurityError: String? = null
        private set

    fun key(p: Provider): String = keyFor(p.toProviderId())

    fun keyFor(id: ProviderId): String {
        return when (val res = vault.readAndMigrate(prefs, "key_${id.name}")) {
            is AppResult.Success -> {
                lastSecurityError = null
                res.value
            }
            is AppResult.Failure -> {
                lastSecurityError = res.error.message
                ""
            }
        }
    }

    fun setKey(p: Provider, key: String): Boolean = setKeyFor(p.toProviderId(), key).isSuccess

    fun setKeyFor(id: ProviderId, key: String): AppResult<Unit> {
        val res = vault.writeKey(prefs, "key_${id.name}", key)
        lastSecurityError = res.errorOrNull()?.message
        return res
    }

    fun model(p: Provider): String? = modelFor(p.toProviderId())
    fun modelFor(id: ProviderId): String? = prefs.getString("model_${id.name}", null)

    fun setModel(p: Provider, model: String?) = setModelFor(p.toProviderId(), model)
    fun setModelFor(id: ProviderId, model: String?) = prefs.edit().putString("model_${id.name}", model).apply()

    /** Set after the fast one-pass render failed once on this phone. */
    var safeRender: Boolean
        get() = prefs.getBoolean("safe_render", false)
        set(v) = prefs.edit().putBoolean("safe_render", v).apply()

    var totalPromptTokensUsed: Long
        get() = prefs.getLong("usage_input_tokens", 0L)
        private set(v) = prefs.edit().putLong("usage_input_tokens", v).apply()

    var totalCompletionTokensUsed: Long
        get() = prefs.getLong("usage_output_tokens", 0L)
        private set(v) = prefs.edit().putLong("usage_output_tokens", v).apply()

    fun recordUsage(inputTokens: Int, outputTokens: Int) {
        totalPromptTokensUsed += inputTokens.coerceAtLeast(0)
        totalCompletionTokensUsed += outputTokens.coerceAtLeast(0)
    }

    fun hasKey(id: ProviderId): Boolean = keyFor(id).isNotBlank()

    val hasActiveKey: Boolean
        get() = hasKey(ProviderId.GEMINI) || hasKey(ProviderId.GROQ) ||
            (privacy.claudePaidConsentGranted && hasKey(ProviderId.CLAUDE))

    fun allConfiguredKeys(): List<String> =
        ProviderId.entries.mapNotNull { keyFor(it).takeIf { k -> k.isNotBlank() } }
}
