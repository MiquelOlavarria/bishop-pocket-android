package com.sqmnet.bishoppocket

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/** STT local con Vosk (modelo pequeño español, offline). */
class SttEngine(private val ctx: Context) {

    private val modelDir: File get() = PocketConfig.voskModelDir(ctx)
    private val modelFolderName = "vosk-model-small-es-0.42"
    private var model: Model? = null
    private var recognizer: Recognizer? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /** Comprueba el modelo; si falta, devuelve false (hay que descargarlo). */
    fun ensureModel(): Boolean {
        if (model != null) return true
        val dir = File(modelDir, modelFolderName)
        if (!dir.exists()) {
            Log.w(TAG, "Modelo Vosk ausente en $dir")
            return false
        }
        model = Model(dir.absolutePath)
        return true
    }

    /** ¿Está el modelo ya en disco (sin cargarlo)? */
    fun isModelInstalled(): Boolean = File(modelDir, modelFolderName).exists()

    /** Elimina el modelo del dispositivo. */
    fun deleteModel(): Boolean = try {
        modelDir.deleteRecursively()
        model = null
        recognizer = null
        !File(modelDir, modelFolderName).exists()
    } catch (e: Exception) {
        Log.e(TAG, "deleteModel: ${e.message}")
        false
    }

    /** Descarga y descomprime el modelo Vosk (bloqueante; onProgress 0-100). */
    fun downloadModel(onProgress: (Int) -> Unit): Boolean {
        try {
            val tmpZip = File(ctx.cacheDir, "vosk-model.zip")
            val url = PocketConfig.voskModelUrl(ctx)
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val body = resp.body ?: return false
                val total = body.contentLength()
                var downloaded = 0L
                val buf = ByteArray(64 * 1024)
                tmpZip.outputStream().use { out ->
                    body.byteStream().use { input ->
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            downloaded += n
                            if (total > 0) onProgress(((downloaded * 100) / total).toInt())
                        }
                    }
                }
            }
            modelDir.mkdirs()
            ZipFile(tmpZip).use { zip ->
                for (e in zip.entries()) {
                    val dest = File(modelDir, e.name)
                    if (e.isDirectory) {
                        dest.mkdirs()
                    } else {
                        dest.parentFile?.mkdirs()
                        zip.getInputStream(e).use { src -> dest.outputStream().use { src.copyTo(it) } }
                    }
                }
            }
            tmpZip.delete()
            return File(modelDir, modelFolderName).exists()
        } catch (e: Exception) {
            Log.e(TAG, "downloadModel: ${e.message}")
            return false
        }
    }

    fun resetRecognizer() {
        recognizer = model?.let { Recognizer(it, 16000.0f) }
    }

    fun accept(buf: ShortArray, n: Int) {
        recognizer?.acceptWaveForm(buf, n)
    }

    /** Texto final tras un silencio (lo que se ha dicho hasta ahora). */
    fun finalText(): String {
        val r = recognizer ?: return ""
        return try {
            val json = r.finalResult
            extractText(json)
        } catch (e: Exception) {
            Log.e(TAG, "finalResult: ${e.message}")
            ""
        }
    }

    private fun extractText(json: String): String {
        // Vosk devuelve {"text": "..."} — parse mínimo sin dependencia extra
        val m = Regex("\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(json)
        return m?.groupValues?.get(1)?.replace("\\'", "'")?.trim() ?: ""
    }

    companion object {
        private const val TAG = "BishopPocketStt"
    }
}
