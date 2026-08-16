package com.sqmnet.bishoppocket

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Captura los media buttons del auricular (BT) y los reenvía al servicio.
 *  Sin este receiver, Android manda los botones al asistente (Gemini) o los descarta. */
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
