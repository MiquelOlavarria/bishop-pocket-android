package com.sqmnet.bishoppocket

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Comprueba y descarga actualizaciones desde el repo del agente. */
class UpdateChecker(private val ctx: Context) {

    data class Update(
        val versionCode: Int,
        val versionName: String,
        val apk: String,
        val sha256: String,
        val changelog: String,
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    /** Devuelve la actualización disponible, o null si ya estás al día. */
    /** Resultado del check: ok=false significa que no se pudo consultar el repo. */
    data class CheckResult(
        val ok: Boolean,
        val update: Update?,
        val error: String = "",
        val httpCode: Int = 0,
        val url: String = "",
    )

    fun check(): CheckResult {
        val url = PocketConfig.updatesUrl(ctx)
        try {
            val req = Request.Builder().url("$url/manifest.json").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return CheckResult(false, null,
                        "HTTP ${resp.code} (${resp.message})", resp.code, url)
                }
                val j = JSONObject(resp.body!!.string())
                val vc = j.getInt("versionCode")
                if (vc <= currentVersionCode()) return CheckResult(true, null, httpCode = 200, url = url)
                return CheckResult(true, Update(vc, j.getString("versionName"), j.getString("apk"),
                    j.getString("sha256"), j.optString("changelog", "")), httpCode = 200, url = url)
            }
        } catch (e: Exception) {
            Log.w(TAG, "check: ${e.message}")
            return CheckResult(false, null, "${e.javaClass.simpleName}: ${e.message}", url = url)
        }
    }

    private fun currentVersionCode(): Int = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionCode
    } catch (e: Exception) {
        0
    }

    /** Descarga el APK a cacheDir y verifica su SHA-256. */
    fun download(update: Update, onProgress: (Int) -> Unit): File? {
        return try {
            val dest = File(ctx.cacheDir, "bishop-pocket-update.apk")
            val url = "${PocketConfig.updatesUrl(ctx).trimEnd('/')}/${update.apk}"
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body ?: return null
                val total = body.contentLength()
                var got = 0L
                val buf = ByteArray(128 * 1024)
                dest.outputStream().use { out ->
                    body.byteStream().use { input ->
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            got += n
                            if (total > 0) onProgress(((got * 100) / total).toInt())
                        }
                    }
                }
            }
            val sha = sha256(dest)
            if (sha.equals(update.sha256, ignoreCase = true)) dest else null
        } catch (e: Exception) {
            Log.w(TAG, "download: ${e.message}")
            null
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "BishopPocketUpd"
    }
}