package com.promptcut.app

import com.promptcut.app.ai.ProviderId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class RemoteVaultKey(
    val id: String,
    val providerId: ProviderId,
    val name: String,
    val key: String,
    val enabled: Boolean,
    val isPreferred: Boolean,
)

/** Manual HTTPS sync client. Keys are returned only after the vault password is verified. */
class RemoteKeyVaultClient(
    private val baseUrl: String,
    private val password: String,
) {
    suspend fun fetchKeys(): List<RemoteVaultKey> = withContext(Dispatchers.IO) {
        val root = baseUrl.trim().trimEnd('/')
        require(root.startsWith("https://", ignoreCase = true)) {
            "Vault URL must use HTTPS."
        }
        require(password.length >= 16) { "Vault password is missing or too short." }

        val endpoint = URL("$root/api/keys?sync=1")
        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $password")
            setRequestProperty("Cache-Control", "no-store")
            instanceFollowRedirects = false
        }

        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val message = runCatching { JSONObject(body).optString("error") }.getOrDefault("")
                throw IllegalStateException(
                    when (code) {
                        401 -> "Vault password is incorrect."
                        404 -> "Vault endpoint was not found. Check the URL."
                        else -> message.ifBlank { "Vault request failed (HTTP $code)." }
                    }
                )
            }

            val array = JSONObject(body).optJSONArray("keys")
                ?: throw IllegalStateException("Vault returned an invalid key list.")
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val provider = runCatching {
                        ProviderId.valueOf(item.optString("providerId"))
                    }.getOrNull() ?: continue
                    val key = item.optString("key").trim()
                    if (key.length < 8) continue
                    add(
                        RemoteVaultKey(
                            id = item.optString("id"),
                            providerId = provider,
                            name = item.optString("name").ifBlank { "${provider.shortName} Cloud Key" },
                            key = key,
                            enabled = item.optBoolean("enabled", true),
                            isPreferred = item.optBoolean("isPreferred", false),
                        )
                    )
                }
            }
        } finally {
            connection.disconnect()
        }
    }
}
