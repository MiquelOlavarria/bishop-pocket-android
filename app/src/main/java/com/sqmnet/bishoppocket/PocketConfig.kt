package com.sqmnet.bishoppocket

import android.content.Context
import java.io.File

/** Configuración persistente de la app (SharedPreferences). */
object PocketConfig {
    private const val PREFS = "bishop_pocket"

    fun apiUrl(ctx: Context): String =
        prefs(ctx).getString("api_url", "http://192.168.68.175:8642") ?: "http://192.168.68.175:8642"

    fun apiKey(ctx: Context): String =
        prefs(ctx).getString("api_key", "") ?: ""

    fun sessionId(ctx: Context): String =
        prefs(ctx).getString("session_id", "") ?: ""

    fun endPhrase(ctx: Context): String =
        prefs(ctx).getString("end_phrase", "cambio") ?: "cambio"

    fun silenceSeconds(ctx: Context): Float =
        prefs(ctx).getFloat("silence_seconds", 5.0f)

    fun silenceMs(ctx: Context): Long = (silenceSeconds(ctx) * 1000).toLong()

    /** Sensibilidad: piso mínimo del umbral de voz y margen sobre el ruido de fondo. */
    fun voiceFloor(ctx: Context): Float = prefs(ctx).getFloat("voice_floor", 0.015f)

    fun setVoiceFloor(ctx: Context, v: Float) = prefs(ctx).edit().putFloat("voice_floor", v).apply()

    fun voiceMargin(ctx: Context): Float = prefs(ctx).getFloat("voice_margin", 2.5f)

    fun voskModelUrl(ctx: Context): String =
        prefs(ctx).getString("vosk_model_url",
            "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip")!!

    fun voskModelDir(ctx: Context): File = File(ctx.filesDir, "vosk-model")

    fun updatesUrl(ctx: Context): String =
        prefs(ctx).getString("updates_url", "http://192.168.68.175:8766")!!

    fun setApiUrl(ctx: Context, v: String) = prefs(ctx).edit().putString("api_url", v).apply()
    fun setApiKey(ctx: Context, v: String) = prefs(ctx).edit().putString("api_key", v).apply()
    fun setSessionId(ctx: Context, v: String) = prefs(ctx).edit().putString("session_id", v).apply()
    fun setEndPhrase(ctx: Context, v: String) = prefs(ctx).edit().putString("end_phrase", v).apply()

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
