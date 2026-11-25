package fr.remi.claudeappel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Relance l'écoute automatiquement après un redémarrage du téléphone. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Prefs.enabled(context)) {
            ListenService.start(context)
        }
    }
}
