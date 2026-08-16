package com.sqmnet.bishoppocket

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var btnToggle: Button
    private lateinit var tvState: TextView
    private lateinit var tvFeed: TextView
    private lateinit var scroll: ScrollView
    private var feed = StringBuilder()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val text = intent?.getStringExtra("text") ?: return
            val st = intent.getStringExtra("state") ?: ""
            runOnUiThread {
                appendFeed(text)
                updateStateUI(st)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        btnToggle = findViewById(R.id.btn_toggle)
        tvState = findViewById(R.id.tv_state)
        tvFeed = findViewById(R.id.tv_feed)
        scroll = findViewById(R.id.scroll)

        btnToggle.setOnClickListener {
            if (!hasAudioPermission()) {
                requestAudioPermission()
                return@setOnClickListener
            }
            val i = Intent(this, PocketService::class.java)
            i.action = if (isServiceRunning()) PocketService.ACTION_STOP else PocketService.ACTION_TOGGLE
            startForegroundService(i)
            appendFeed(if (isServiceRunning()) "⚡ Escucha desactivada" else "⚡ Escucha activada")
            refreshState()
        }
        findViewById<Button>(R.id.btn_settings).setOnClickListener { showSettings() }
        findViewById<Button>(R.id.btn_download).setOnClickListener { onModelButton() }
        findViewById<Button>(R.id.btn_update).setOnClickListener { checkForUpdates(manual = true) }
        findViewById<Button>(R.id.btn_send_now).setOnClickListener {
            if (isServiceRunning()) {
                startService(Intent(this, PocketService::class.java)
                    .setAction(PocketService.ACTION_FORCE_SEND))
                appendFeed("📨 Enviar ahora solicitado")
            } else {
                appendFeed("⚠️ Inicia la escucha primero")
            }
        }
        findViewById<Button>(R.id.btn_interrupt).setOnClickListener {
            if (isServiceRunning()) {
                startService(Intent(this, PocketService::class.java)
                    .setAction(PocketService.ACTION_INTERRUPT))
                appendFeed("⚡ Interrumpir solicitado")
            } else {
                appendFeed("⚠️ Inicia la escucha primero")
            }
        }
        findViewById<Button>(R.id.btn_send_logs).setOnClickListener { sendLogsToBishop() }
        findViewById<TextView>(R.id.tv_version).text = "v" + (try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) { "?" })
        registerReceiver(receiver, IntentFilter(PocketService.EXTRA_EVENT), Context.RECEIVER_NOT_EXPORTED)
        appendFeed("Bishop Pocket — listo. Configura URL del agente y modelo Vosk en Ajustes.")
        refreshState()
        updateModelButton()
        startVuMeter()
        checkForUpdates(manual = false)
    }

    private val vuHandler = Handler(Looper.getMainLooper())
    private val vuRunnable = object : Runnable {
        override fun run() {
            val rms = PocketService.currentRms
            val meter = findViewById<ProgressBar>(R.id.vu_meter)
            val label = findViewById<TextView>(R.id.tv_vu_label)
            meter.progress = (rms * 1000).toInt().coerceIn(0, 1000)
            val db = if (rms > 0f) 20.0 * Math.log10(rms.toDouble()) else -120.0
            val running = isServiceRunning()
            label.text = if (!running) "🎙 Nivel de entrada: — (apagado)"
            else "🎙 Nivel de entrada: ${"%.1f".format(db)} dBFS" +
                    if (rms < 0.001f) "  ← ¡sin señal!" else ""
            label.setTextColor(if (running && rms > 0.005f) 0xFF4CAF50.toInt()
                               else if (running) 0xFFE53935.toInt() else 0xFF9E9E9E.toInt())
            vuHandler.postDelayed(this, 200)
        }
    }

    private fun startVuMeter() {
        vuHandler.removeCallbacks(vuRunnable)
        vuHandler.post(vuRunnable)
    }

    private fun sendLogsToBishop() {
        val prog = findViewById<TextView>(R.id.tv_progress)
        prog.text = "📤 Recopilando y enviando logs…"
        Thread {
            try {
                val pkg = packageManager.getPackageInfo(packageName, 0)
                val feedLog = StringBuilder()
                // reconstruir el feed desde el TextView no es fiable; usamos el log del servicio
                val svcLog = File(filesDir, "pocket.log")
                val tail = if (svcLog.exists()) svcLog.readLines().takeLast(60).joinToString("\n") else "(sin log del servicio)"
                val crash = File(filesDir, "crash.log")
                val crashTail = if (crash.exists()) crash.readLines().takeLast(40).joinToString("\n") else ""
                val report = JSONObject().apply {
                    put("app", "bishop-pocket")
                    put("versionName", pkg.versionName)
                    put("versionCode", pkg.versionCode)
                    put("api_url", PocketConfig.apiUrl(this@MainActivity))
                    put("session_id", PocketConfig.sessionId(this@MainActivity))
                    put("updates_url", PocketConfig.updatesUrl(this@MainActivity))
                    put("end_phrase", PocketConfig.endPhrase(this@MainActivity))
                    put("model_installed", SttEngine(this@MainActivity).isModelInstalled())
                    put("service_running", isServiceRunning())
                    put("service_state", PocketService.state.name)
                    put("last_rms", PocketService.currentRms)
                    put("service_log", tail)
                    put("crash_log", crashTail)
                }
                val url = PocketConfig.updatesUrl(this@MainActivity).trimEnd('/') + "/log"
                val client = okhttp3.OkHttpClient()
                val body = report.toString().toRequestBody("application/json".toMediaType())
                val req = Request.Builder().url(url).post(body).build()
                client.newCall(req).execute().use { resp ->
                    val respText = resp.body?.string() ?: ""
                    runOnUiThread {
                        if (resp.isSuccessful) {
                            prog.text = "✅ Logs enviados a Bishop (${respText})"
                            appendFeed("📤 Logs enviados a Bishop correctamente")
                        } else {
                            prog.text = "❌ Error enviando logs: HTTP ${resp.code} $respText"
                            appendFeed("❌ Error enviando logs (HTTP ${resp.code})")
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    prog.text = "❌ Error enviando logs: ${e.javaClass.simpleName}: ${e.message}"
                    appendFeed("❌ Error enviando logs: ${e.message}")
                }
            }
        }.start()
    }

    private fun updateModelButton() {
        val btn = findViewById<Button>(R.id.btn_download)
        btn.text = if (SttEngine(this).isModelInstalled())
            "✅ Modelo STT instalado (toca para reinstalar/desinstalar)"
        else
            "⬇ Descargar modelo STT (40MB)"
    }

    private fun onModelButton() {
        if (!SttEngine(this).isModelInstalled()) {
            downloadVoskModel()
            return
        }
        // instalado → opciones
        val options = arrayOf("↻ Reinstalar (volver a descargar)", "🗑 Desinstalar (eliminar modelo)")
        AlertDialog.Builder(this)
            .setTitle("Modelo STT")
            .setMessage("El modelo Vosk ya está instalado. ¿Qué quieres hacer?")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> downloadVoskModel()
                    1 -> {
                        val ok = SttEngine(this).deleteModel()
                        val prog = findViewById<TextView>(R.id.tv_progress)
                        prog.text = if (ok) "🗑 Modelo eliminado." else "❌ No se pudo eliminar el modelo."
                        appendFeed(if (ok) "🗑 Modelo STT desinstalado" else "❌ Error al desinstalar modelo")
                        updateModelButton()
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun checkForUpdates(manual: Boolean) {
        val prog = findViewById<TextView>(R.id.tv_progress)
        if (manual) prog.text = "🔍 Conectando al repo de actualizaciones…"
        Thread {
            val uc = UpdateChecker(this)
            val result = uc.check()
            runOnUiThread {
                if (!result.ok) {
                    prog.text = "❌ No se pudo consultar el repo"
                    appendFeed("❌ UPDATE: no conectado a ${result.url}")
                    appendFeed("❌ UPDATE: error → ${result.error}")
                    return@runOnUiThread
                }
                appendFeed("✅ UPDATE: conectado (HTTP ${result.httpCode}) → ${result.url}")
                val update = result.update
                if (update == null) {
                    val ver = try {
                        packageManager.getPackageInfo(packageName, 0).versionName
                    } catch (e: Exception) { "?" }
                    prog.text = "✅ Conectado: estás al día (v$ver)."
                    appendFeed("✅ UPDATE: manifiesto sin versión superior (instalada v$ver)")
                    return@runOnUiThread
                }
                prog.text = "⬇ Actualización v${update.versionName} disponible: ${update.changelog}"
                appendFeed("🔄 UPDATE: versión v${update.versionName} (code ${update.versionCode}) disponible")
                downloadAndInstall(update)
            }
        }.start()
    }

    private fun downloadAndInstall(update: UpdateChecker.Update) {
        val prog = findViewById<TextView>(R.id.tv_progress)
        Thread {
            val uc = UpdateChecker(this)
            val apk = uc.download(update) { pct ->
                runOnUiThread { prog.text = "⬇ Descargando v${update.versionName}… $pct%" }
            }
            runOnUiThread {
                if (apk == null) {
                    prog.text = "❌ Error de descarga o SHA256 no coincide."
                    appendFeed("❌ Error de descarga de actualización")
                    return@runOnUiThread
                }
                prog.text = "✅ Descargado. Instalando…"
                appendFeed("⬇ Instalando v${update.versionName}…")
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    this, "com.sqmnet.bishoppocket.fileprovider", apk)
                val i = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    startActivity(i)
                } catch (e: Exception) {
                    prog.text = "❌ No se pudo abrir el instalador: ${e.message}"
                }
            }
        }.start()
    }

    private fun downloadVoskModel() {
        val btn = findViewById<Button>(R.id.btn_download)
        val prog = findViewById<TextView>(R.id.tv_progress)
        btn.isEnabled = false
        prog.text = "Descargando modelo Vosk…"
        Thread {
            val stt = SttEngine(this)
            val ok = stt.downloadModel { pct ->
                runOnUiThread { prog.text = "Descargando modelo Vosk… $pct%" }
            }
            runOnUiThread {
                btn.isEnabled = true
                if (ok) {
                    prog.text = "✅ Modelo Vosk listo. Ya puedes iniciar la escucha."
                    appendFeed("✅ Modelo STT descargado e instalado")
                } else {
                    prog.text = "❌ No se pudo descargar el modelo. Revisa la conexión."
                    appendFeed("❌ Error descargando modelo STT")
                }
                updateModelButton()
            }
        }.start()
    }

    private fun isServiceRunning(): Boolean =
        (getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager)
            .getRunningServices(Int.MAX_VALUE).any { it.service.className == PocketService::class.java.name }

    private fun refreshState() {
        updateStateUI(if (isServiceRunning()) PocketService.State.LISTENING.name else PocketService.State.OFF.name)
    }

    private fun updateStateUI(state: String) {
        val running = state != PocketService.State.OFF.name
        btnToggle.text = if (running) "■ PARAR CONVERSACIÓN" else "▶ INICIAR CONVERSACIÓN"
        tvState.text = when (state) {
            PocketService.State.PROCESSING.name -> "⏳ PROCESANDO…"
            PocketService.State.VERIFYING.name -> "⏳ VERIFICANDO…"
            PocketService.State.LISTENING.name -> "● ESCUCHANDO"
            else -> "○ APAGADO"
        }
        tvState.setTextColor(
            when (state) {
                PocketService.State.PROCESSING.name, PocketService.State.VERIFYING.name -> 0xFFFFA726.toInt()
                PocketService.State.LISTENING.name -> 0xFF4CAF50.toInt()
                else -> 0xFFB71C1C.toInt()
            }
        )
    }

    private fun appendFeed(text: String) {
        feed.append(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date()))
            .append("  ").append(text).append("\n")
        tvFeed.text = feed
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestAudioPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
        }
    }

    private fun showSettings() {
        val v = layoutInflater.inflate(R.layout.dialog_settings, null)
        val etUrl = v.findViewById<EditText>(R.id.et_url)
        val etKey = v.findViewById<EditText>(R.id.et_key)
        val etSession = v.findViewById<EditText>(R.id.et_session)
        val etFloor = v.findViewById<EditText>(R.id.et_floor)
        etUrl.setText(PocketConfig.apiUrl(this))
        etKey.setText(PocketConfig.apiKey(this))
        etSession.setText(PocketConfig.sessionId(this))
        etFloor.setText(PocketConfig.voiceFloor(this).toString())
        AlertDialog.Builder(this)
            .setTitle("Ajustes")
            .setView(v)
            .setPositiveButton("Guardar") { _, _ ->
                PocketConfig.setApiUrl(this, etUrl.text.toString().trim())
                PocketConfig.setApiKey(this, etKey.text.toString().trim())
                PocketConfig.setSessionId(this, etSession.text.toString().trim())
                val floor = etFloor.text.toString().trim().toFloatOrNull()
                if (floor != null && floor in 0.005f..0.05f) {
                    PocketConfig.setVoiceFloor(this, floor)
                } else {
                    Toast.makeText(this, "Sensibilidad no válida (0.005-0.05); se mantiene", Toast.LENGTH_LONG).show()
                }
                Toast.makeText(this, "Guardado", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        super.onDestroy()
    }
}
