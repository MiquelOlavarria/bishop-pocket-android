package com.sqmnet.bishoppocket

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.media.session.MediaSession
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.KeyEvent
import java.io.File
import java.util.Locale
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Servicio de escucha walkie-talkie: micro → VAD → silencio → "cambio" → agente → TTS. */
class PocketService : Service() {

    companion object {
        private const val TAG = "BishopPocket"
        private const val CHANNEL_ID = "pocket_channel"
        private const val NOTIF_ID = 42
        const val ACTION_TOGGLE = "com.sqmnet.bishoppocket.TOGGLE"
        const val ACTION_STOP = "com.sqmnet.bishoppocket.STOP"
        const val ACTION_FORCE_SEND = "com.sqmnet.bishoppocket.FORCE_SEND"
        const val ACTION_INTERRUPT = "com.sqmnet.bishoppocket.INTERRUPT"
        const val EXTRA_EVENT = "event"

        const val SAMPLE_RATE = 16000
        const val CHUNK_SECONDS = 0.6f

        @Volatile
        var currentRms = 0f
            private set

        @Volatile
        var state: State = State.OFF
            private set
    }

    enum class State { OFF, LISTENING, VERIFYING, PROCESSING }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var stt: SttEngine
    private lateinit var tts: TtsEngine
    private lateinit var agent: AgentClient
    private var recorder: AudioRecord? = null
    private var mediaSession: MediaSession? = null
    private var running = false
    private var silenceSince = 0L
    private var messageStarted = false
    private val noiseWindow = ArrayDeque<Float>()
    private var noiseFloor = 0.002f
    private var voiceMs = 0L          // duración acumulada de voz real (rms >= umbral)
    private var voiceEnergy = 0.0     // suma de rms de chunks con voz
    private val recBuffer = ArrayList<Short>()   // PCM del último mensaje (para enviar a Bishop)
    private var lastBeep = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        stt = SttEngine(this)
        tts = TtsEngine(this)
        agent = AgentClient(this)
        try {
            startForeground(NOTIF_ID, buildNotification("Inactivo"))
            fileLog("Servicio iniciado (onCreate)")
        } catch (e: Exception) {
            Log.e(TAG, "onCreate/startForeground: ${e.message}")
            fileLog("onCreate error: ${e.javaClass.simpleName}: ${e.message}")
        }
        setupMediaSession()
    }

    private fun setupMediaSession() {
        mediaSession = MediaSession(this, "bishop-pocket").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onSkipToNext() {
                    fileLog("Botón siguiente → interrumpir y volver a escuchar")
                    interruptProcessing()
                }

                override fun onSkipToPrevious() {
                    fileLog("Botón anterior → forzar envío")
                    forceSend()
                }
            })
            isActive = true
        }
    }

    private fun interruptProcessing() {
        if (state != State.PROCESSING && state != State.VERIFYING) return
        state = State.LISTENING
        updateNotification("Escuchando…")
        broadcastState("⚡ Interrumpido — vuelvo a escuchar")
        say("Dime.")
    }

    private fun forceSend() {
        if (state == State.PROCESSING || state == State.VERIFYING) {
            postEvent("📨 Enviar ahora ignorado: procesando en curso")
            say("Estoy procesando, un momento.")
            return
        }
        if (!messageStarted) {
            postEvent("📨 Enviar ahora ignorado: no hay mensaje en curso")
            say("No tengo nada que enviar.")
            return
        }
        state = State.VERIFYING
        updateNotification("Enviando…")
        broadcastState("📨 Enviando ahora (forzado)")
        val text = stt.finalText()
        postEvent("🎙 $text")
        if (text.isBlank()) {
            postEvent("Sin contenido para enviar")
            say("No te he oído nada.")
            state = State.LISTENING
            messageStarted = false
            silenceSince = 0L
            recBuffer.clear()
            stt.resetRecognizer()
            return
        }
        state = State.PROCESSING
        updateNotification("Procesando…")
        scope.launch { sendMessage(text) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        fileLog("onStartCommand: acción=${intent?.action ?: "null"}")
        try {
            if (intent != null) {
                startForeground(NOTIF_ID, buildNotification(if (running) "Escuchando…" else "Inactivo"))
            }
            when (intent?.action) {
                ACTION_TOGGLE -> if (running) stopListening(announce = true) else startListening()
                ACTION_STOP -> { stopListening(announce = true); stopSelf() }
                ACTION_FORCE_SEND -> forceSend()
                ACTION_INTERRUPT -> interruptProcessing()
                else -> if (intent == null && !running) startListening()
            }
        } catch (e: Exception) {
            Log.e(TAG, "onStartCommand: ${e.message}")
            fileLog("onStartCommand error: ${e.javaClass.simpleName}: ${e.message}")
        }
        return START_STICKY
    }

    private fun startListening() {
        if (running) return
        running = true
        state = State.LISTENING
        fileLog("startListening: LISTENING (arrancando captura)")
        updateNotification("Escuchando…")
        say("Te escucho.")
        broadcastState("⚡ Escucha activada")
        scope.launch { captureLoop() }
    }

    private fun stopListening(announce: Boolean = false) {
        running = false
        saveLastWav()
        recorder?.release()
        recorder = null
        state = State.OFF
        updateNotification("Inactivo")
        broadcastState("⚡ Escucha desactivada")
        if (announce) say("Dejo de escuchar.")
    }

    /** Guarda el PCM del último mensaje como WAV (para el análisis de Bishop). */
    private fun saveLastWav() {
        try {
            val data = recBuffer
            if (data.isEmpty()) return
            val f = File(filesDir, "pocket-last.wav")
            f.outputStream().use { out ->
                val byteRate = SAMPLE_RATE * 2
                out.write("RIFF".toByteArray())
                out.write(intLE(36 + data.size * 2))
                out.write("WAVE".toByteArray())
                out.write("fmt ".toByteArray())
                out.write(intLE(16))
                out.write(shortLE(1))
                out.write(shortLE(1))
                out.write(intLE(SAMPLE_RATE))
                out.write(intLE(byteRate))
                out.write(shortLE(2))
                out.write(shortLE(16))
                out.write("data".toByteArray())
                out.write(intLE(data.size * 2))
                val b = ByteArray(data.size * 2)
                for (i in data.indices) {
                    b[i * 2] = (data[i].toInt() and 0xFF).toByte()
                    b[i * 2 + 1] = ((data[i].toInt() shr 8) and 0xFF).toByte()
                }
                out.write(b)
            }
            fileLog("WAV guardado: ${data.size * 2} bytes (${data.size / SAMPLE_RATE}s)")
        } catch (e: Exception) {
            Log.w(TAG, "saveLastWav: ${e.message}")
        }
    }

    private fun intLE(v: Int): ByteArray = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte())

    private fun shortLE(v: Int): ByteArray = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    private fun captureLoop() {
        if (!stt.ensureModel()) {
            postEvent("Modelo STT no disponible; descárgalo con el botón ⬇")
            stopListening()
            return
        }
        stt.resetRecognizer()
        fileLog("captureLoop: modelo OK, creando AudioRecord…")
        val chunk = (SAMPLE_RATE * CHUNK_SECONDS).toInt()   // 16000 × 0.6 = 9600
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = maxOf(minBuf * 2, chunk * 2)
        val buf = ShortArray(chunk)
        recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                bufSize
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "AudioRecord sin permiso: ${e.message}")
            postEvent("❌ Sin permiso de micrófono (actívalo en Ajustes del sistema)")
            stopListening()
            return
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord: ${e.message}")
            postEvent("❌ Error al abrir el micrófono: ${e.message}")
            stopListening()
            return
        }
        fileLog("captureLoop: AudioRecord state=${recorder?.state} minBuf=$minBuf bufSize=$bufSize")
        try {
            recorder?.startRecording()
            fileLog("captureLoop: AudioRecord OK, grabando (VOICE_RECOGNITION)")
        } catch (e: Exception) {
            Log.e(TAG, "startRecording: ${e.message}")
            postEvent("❌ No se pudo iniciar la grabación: ${e.message}")
            recorder?.release()
            recorder = null
            stopListening()
            return
        }
        messageStarted = false
        silenceSince = 0L
        var framesRead = 0
        var readsWithData = 0
        var rmsMax = 0.0
        var lastReport = 0L
        while (running) {
            val n = recorder?.read(buf, 0, chunk) ?: -1
            if (n <= 0) continue
            framesRead++
            readsWithData += n
            when (state) {
                State.VERIFYING, State.PROCESSING -> continue
                State.OFF -> return
                State.LISTENING -> processChunk(buf, n)
            }
            if (rmsMax < PocketService.currentRms.toDouble()) rmsMax = PocketService.currentRms.toDouble()
            val now2 = System.currentTimeMillis()
            if (now2 - lastReport > 10000) {
                lastReport = now2
                val rmsStr = String.format(Locale.US, "%.4f", rmsMax)
                fileLog("captura viva: reads=$framesRead muestras=$readsWithData rms_max=$rmsStr")
            }
        }
        val rmsStrFin = String.format(Locale.US, "%.4f", rmsMax)
        fileLog("captureLoop fin: reads=$framesRead muestras=$readsWithData rms_max=$rmsStrFin")
    }

    private fun processChunk(buf: ShortArray, n: Int) {
        var sum = 0.0
        for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
        val rms = sqrt(sum / n) / 32768.0
        currentRms = rms.toFloat()
        val now = System.currentTimeMillis()
        val threshold = maxOf(PocketConfig.voiceFloor(this), noiseFloor * PocketConfig.voiceMargin(this))
        if (rms >= threshold) {
            silenceSince = 0L
            if (!messageStarted) {
                messageStarted = true
                voiceMs = 0L
                voiceEnergy = 0.0
                recBuffer.clear()
                stt.resetRecognizer()
            }
            voiceMs += (CHUNK_SECONDS * 1000).toLong()
            voiceEnergy += rms
            for (i in 0 until n) recBuffer.add(buf[i])
            stt.accept(buf, n)
        } else {
            if (!messageStarted) {
                noiseWindow.addLast(rms.toFloat())
                if (noiseWindow.size > 40) noiseWindow.removeFirst()
                if (noiseWindow.size >= 15) {
                    val sorted = noiseWindow.sorted()
                    noiseFloor = sorted[sorted.size * 6 / 10]
                }
            }
            if (messageStarted) {
                if (silenceSince == 0L) silenceSince = now
                else if (now - silenceSince >= PocketConfig.silenceMs(this)) {
                    state = State.VERIFYING
                    updateNotification("Verificando…")
                    val text = stt.finalText()
                    postEvent("🎙 $text")
                    val normWords = text.lowercase().trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                    // voz REAL = energía sostenida (varios chunks por encima del umbral).
                    // Las alucinaciones de Vosk con ruido no la tienen (el log: 0.023 vs 0.061).
                    val voiceReal = voiceMs >= 700L
                    val noiseOnly = !voiceReal && !hasEndPhrase(text)
                    if (text.isBlank() || noiseOnly) {
                        state = State.LISTENING
                        messageStarted = false
                        silenceSince = 0L
                        recBuffer.clear()
                        stt.resetRecognizer()
                    } else if (hasEndPhrase(text)) {
                        saveLastWav()
                        postEvent("📤 Enviando a Bishop…")
                        state = State.PROCESSING
                        updateNotification("Procesando…")
                        scope.launch { sendMessage(text) }
                    } else {
                        postEvent("Sin \"${PocketConfig.endPhrase(this)}\" — sigo escuchando")
                        say("Perdona, continúa.")
                        state = State.LISTENING
                        messageStarted = false
                        silenceSince = 0L
                        recBuffer.clear()
                        stt.resetRecognizer()
                    }
                }
            }
        }
    }

    private fun sendMessage(raw: String) {
        try {
            val text = stripEndPhrase(raw)
            val reply = agent.chat(text)
            if (reply == null) {
                postEvent("⚠️ El agente no respondió")
                say("No he recibido respuesta del agente.")
            } else {
                val clean = cleanForTts(reply)
                postEvent("🤖 $clean")
                say(clean)
            }
            beep()
        } finally {
            state = State.LISTENING
            messageStarted = false
            silenceSince = 0L
            stt.resetRecognizer()
            updateNotification("Escuchando…")
        }
    }

    private fun hasEndPhrase(text: String): Boolean {
        val phrase = PocketConfig.endPhrase(this)
        val norm = text.lowercase()
            .replace(Regex("[^a-záéíóúñü0-9 ]"), " ")
            .trim()
        if (norm.isBlank()) return false
        val words = norm.split(Regex("\\s+"))
        return words.last() == phrase
    }

    private fun stripEndPhrase(text: String): String {
        val phrase = PocketConfig.endPhrase(this)
        val norm = text.lowercase().replace(Regex("[^a-záéíóúñü0-9 ]"), " ").trim()
        val words = norm.split(Regex("\\s+"))
        if (words.isNotEmpty() && words.last() == phrase) {
            return text.trimEnd().removeSuffix(words.last().let { text.trimEnd().takeLast(it.length) })
                .trimEnd(' ', ',', '.', ';', ':', '!', '¡', '?', '¿', '-')
        }
        return text
    }

    private fun cleanForTts(t: String): String {
        val noNormalized = t.replace(Regex("(?:⚠️|⚠)?\\s*Normalized model\\b.*?\\bfor\\s+[\\w.-]+\\.?\\s*", RegexOption.IGNORE_CASE), "")
        val noBold = noNormalized.replace(Regex("\\*\\*(.*?)\\*\\*"), "$1")
        val noCode = noBold.replace(Regex("`([^`]*)`"), "$1")
        return noCode.replace(Regex("\\s+"), " ").trim()
    }

    private fun say(text: String) {
        scope.launch { tts.speak(text) }
    }

    private fun beep() {
        val now = System.currentTimeMillis()
        if (now - lastBeep < 1500) return
        lastBeep = now
        scope.launch { tts.beep() }
    }

    private fun postEvent(text: String) {
        Log.i(TAG, text)
        fileLog(text)
        val i = Intent(EXTRA_EVENT).setPackage(packageName)
        i.putExtra("text", text)
        i.putExtra("state", state.name)
        sendBroadcast(i)
    }

    private fun fileLog(text: String) {
        try {
            val f = File(filesDir, "pocket.log")
            val ts = java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(java.util.Date())
            val line = "[$ts] $text\n"
            f.appendText(line)
        } catch (e: Exception) {
            Log.w(TAG, "fileLog: ${e.message}")
        }
    }

    private fun broadcastState(text: String? = null) {
        if (text != null) fileLog(text)
        val i = Intent(EXTRA_EVENT).setPackage(packageName)
        if (text != null) i.putExtra("text", text)
        i.putExtra("state", state.name)
        sendBroadcast(i)
    }

    private fun updateNotification(text: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_ID, buildNotification(text))
        } catch (e: Exception) {
            Log.w(TAG, "updateNotification: ${e.message}")
        }
    }

    private fun buildNotification(text: String): Notification {
        val toggle = PendingIntent.getService(
            this, 1,
            Intent(this, PocketService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 2,
            Intent(this, PocketService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Bishop Pocket")
            .setContentText(text)
            .addAction(0, if (running) "Detener" else "Escuchar", if (running) stop else toggle)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Bishop Pocket", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    override fun onDestroy() {
        running = false
        recorder?.release()
        recorder = null
        scope.cancel()
        tts.shutdown()
        mediaSession?.release()
        super.onDestroy()
    }
}
