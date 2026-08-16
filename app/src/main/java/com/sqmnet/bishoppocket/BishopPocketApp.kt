package com.sqmnet.bishoppocket

import android.app.Application
import android.util.Log
import java.io.File

/** Captura crashes no controlados y los guarda en filesDir/crash.log
 * (el botón "Enviar logs" los incluye — así Bishop puede ver el fallo exacto). */
class BishopPocketApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val f = File(filesDir, "crash.log")
                val stack = throwable.stackTraceToString()
                val line = "[${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date())}] $thread\n${throwable.javaClass.name}: ${throwable.message}\n$stack\n---\n"
                f.appendText(line)
                Log.e("BishopPocket", "crash capturado: ${throwable.message}", throwable)
            } catch (e: Exception) {
                Log.e("BishopPocket", "no se pudo guardar crash: ${e.message}")
            } finally {
                // re-lanzar para que Android muestre el diálogo de cierre
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }
}
