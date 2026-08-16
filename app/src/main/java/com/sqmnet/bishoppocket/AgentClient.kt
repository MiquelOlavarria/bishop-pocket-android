package com.sqmnet.bishoppocket

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Cliente HTTP del agente: manda TEXTO y recibe TEXTO. */
class AgentClient(private val ctx: android.content.Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)   // el agente puede tardar (herramientas)
        .build()

    /** Devuelve el texto de la respuesta, o null si falla. */
    fun chat(message: String): String? {
        val url = PocketConfig.apiUrl(ctx).trimEnd('/') + "/api/sessions/" +
            PocketConfig.sessionId(ctx) + "/chat"
        val key = PocketConfig.apiKey(ctx)
        val body = JSONObject().put("message", message).toString()
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url(url)
            .post(body)
            .apply { if (key.isNotBlank()) header("Authorization", "Bearer $key") }
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val json = JSONObject(resp.body?.string() ?: return null)
            return json.optJSONObject("message")?.optString("content")?.trim()
                ?.takeIf { it.isNotEmpty() }
        }
    }
}
