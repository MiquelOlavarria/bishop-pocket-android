package com.sqmnet.bishoppocket

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Recibe los media buttons mientras la MediaSession está activa (registrado
 *  dinámicamente vía setMediaButtonBroadcastReceiver, SIN declararlo en el manifest,
 *  para no disparar el aviso de Play Protect). */
class MediaKeyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MEDIA_BUTTON) return
        val key = intent.getParcelableExtra<android.view.KeyEvent>(Intent.EXTRA_KEY_EVENT)
        val code = key?.keyCode ?: return
        val i = Intent(context, PocketService::class.java)
            .setAction(PocketService.ACTION_MEDIA_KEY)
            .putExtra("key_code", code)
        context.startService(i)
    }
}
