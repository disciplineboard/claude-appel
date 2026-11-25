package fr.remi.claudeappel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Service qui reste allumé en arrière-plan et écoute le sujet ntfy.
 * Quand un message arrive, il fait « sonner » le téléphone comme un appel.
 */
class ListenService : Service() {

    companion object {
        private const val TAG = "ClaudeAppel"
        const val ACTION_START = "fr.remi.claudeappel.START"
        const val ACTION_STOP = "fr.remi.claudeappel.STOP"
        const val ACTION_RING = "fr.remi.claudeappel.RING"
        const val ACTION_STOP_RING = "fr.remi.claudeappel.STOP_RING"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_CALLER = "caller"

        const val CHANNEL_LISTEN = "ecoute"
        const val CHANNEL_CALL = "appels"
        const val NOTIF_LISTEN = 1
        const val NOTIF_CALL = 2
        const val NOTIF_MISSED = 3
        private const val RING_MAX_MS = 45_000L

        fun start(ctx: Context) {
            val i = Intent(ctx, ListenService::class.java).setAction(ACTION_START)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, ListenService::class.java).setAction(ACTION_STOP))
        }

        /** Déclenche un faux appel en local (bouton « Tester »). */
        /** Instance en cours, pour éviter de relancer le service depuis l'arrière-plan. */
        @Volatile var instance: ListenService? = null

        fun ring(ctx: Context, message: String, caller: String = "Claude") {
            instance?.let { it.handler.post { it.startRing(message, caller) }; return }
            val i = Intent(ctx, ListenService::class.java)
                .setAction(ACTION_RING)
                .putExtra(EXTRA_MESSAGE, message)
                .putExtra(EXTRA_CALLER, caller)
            ctx.startForegroundService(i)
        }

        fun stopRinging(ctx: Context) {
            instance?.let { it.handler.post { it.endCall() }; return }
            ctx.startService(Intent(ctx, ListenService::class.java).setAction(ACTION_STOP_RING))
        }
    }

    private var inForeground = false
    @Volatile private var running = false
    @Volatile private var connection: HttpURLConnection? = null
    private var worker: Thread? = null
    private var lastId: String? = null

    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    internal val handler = Handler(Looper.getMainLooper())
    private var lastMessage = ""
    private var lastCaller = "Claude"
    private val autoStop = Runnable { missedCall() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Passer au premier plan tout de suite (exigé par Android), une seule fois.
        if (!inForeground) {
            try {
                goForeground()
                inForeground = true
            } catch (e: Exception) {
                Log.w(TAG, "Impossible de passer au premier plan", e)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        when (intent?.action) {
            ACTION_STOP -> {
                Prefs.setEnabled(this, false)
                stopRing(cancelNotif = true)
                stopListening()
                stopForeground(STOP_FOREGROUND_REMOVE)
                inForeground = false
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RING -> {
                startRing(
                    intent.getStringExtra(EXTRA_MESSAGE) ?: "Claude a fini ta tâche.",
                    intent.getStringExtra(EXTRA_CALLER) ?: "Claude"
                )
            }
            ACTION_STOP_RING -> { endCall(); if (!Prefs.enabled(this)) return START_NOT_STICKY }
        }
        if (Prefs.enabled(this)) startListening()
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        stopRing(cancelNotif = true)
        stopListening()
        super.onDestroy()
    }

    // ---------- Notifications ----------

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_LISTEN, "Écoute active", NotificationManager.IMPORTANCE_MIN)
                .apply { description = "Indique que l'appli attend les appels de Claude" }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CALL, "Appels de Claude", NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    description = "Appels entrants de Claude"
                    setSound(null, null) // la sonnerie est jouée par le service
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
        )
    }

    private fun goForeground() {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif = Notification.Builder(this, CHANNEL_LISTEN)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("Claude peut t'appeler")
            .setContentText("En écoute sur ${Prefs.topic(this)}")
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_LISTEN, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_LISTEN, notif)
        }
    }

    private fun showCallNotification(message: String, caller: String) {
        val fullScreen = PendingIntent.getActivity(
            this, 1, CallActivity.intent(this, message, caller, answer = false),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val answer = PendingIntent.getActivity(
            this, 2, CallActivity.intent(this, message, caller, answer = true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val decline = PendingIntent.getService(
            this, 3, Intent(this, ListenService::class.java).setAction(ACTION_STOP_RING),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif = Notification.Builder(this, CHANNEL_CALL)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("$caller t'appelle")
            .setContentText("Appel entrant…")
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null as Icon?, "Raccrocher", decline).build())
            .addAction(Notification.Action.Builder(null as Icon?, "Décrocher", answer).build())
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_CALL, notif)
    }

    // ---------- Sonnerie ----------

    internal fun startRing(message: String, caller: String) {
        stopRing(cancelNotif = false)
        lastMessage = message
        lastCaller = caller
        Log.i(TAG, "Appel entrant : $message")

        val pm = getSystemService(PowerManager::class.java)
        @Suppress("DEPRECATION")
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "ClaudeAppel:ring"
        ).apply { acquire(RING_MAX_MS) }

        showCallNotification(message, caller)

        val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
            audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            isLooping = true
            play()
        }

        @Suppress("DEPRECATION")
        vibrator = (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).apply {
            vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 800), 0))
        }

        handler.removeCallbacks(autoStop)
        handler.postDelayed(autoStop, RING_MAX_MS)
    }

    internal fun stopRing(cancelNotif: Boolean) {
        handler.removeCallbacks(autoStop)
        ringtone?.stop(); ringtone = null
        vibrator?.cancel(); vibrator = null
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        if (cancelNotif) getSystemService(NotificationManager::class.java).cancel(NOTIF_CALL)
    }

    /** Fin d'appel (décroché ou raccroché). Si l'écoute est coupée, le service s'arrête. */
    internal fun endCall() {
        stopRing(cancelNotif = true)
        if (!Prefs.enabled(this)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            inForeground = false
            stopSelf()
        }
    }

    /** Personne n'a décroché : on laisse une notification « appel manqué » avec le message. */
    private fun missedCall() {
        endCall()
        val open = PendingIntent.getActivity(
            this, 4, CallActivity.intent(this, lastMessage, lastCaller, answer = true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif = Notification.Builder(this, CHANNEL_CALL)
            .setSmallIcon(android.R.drawable.sym_call_missed)
            .setContentTitle("Appel manqué de $lastCaller")
            .setContentText(lastMessage)
            .setStyle(Notification.BigTextStyle().bigText(lastMessage))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_MISSED, notif)
    }

    // ---------- Écoute ntfy ----------

    private fun startListening() {
        if (running) return
        running = true
        worker = Thread({ listenLoop() }, "ntfy-listener").apply { start() }
    }

    private fun stopListening() {
        running = false
        connection?.disconnect()
        worker?.interrupt()
        worker = null
    }

    private fun listenLoop() {
        var backoff = 2_000L
        while (running) {
            try {
                val topic = Prefs.topic(this)
                val since = lastId?.let { "?since=$it" } ?: ""
                val conn = URL("https://ntfy.sh/$topic/json$since").openConnection() as HttpURLConnection
                connection = conn
                conn.connectTimeout = 15_000
                conn.readTimeout = 120_000 // ntfy envoie un « keepalive » environ toutes les 45 s
                conn.setRequestProperty("User-Agent", "ClaudeAppel/1.0")
                val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
                try {
                    backoff = 2_000L
                    while (running) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        val json = JSONObject(line)
                        if (json.optString("event") != "message") continue
                        lastId = json.optString("id", lastId)
                        val msg = json.optString("message").ifBlank { "Claude a fini ta tâche." }
                        val caller = json.optString("title").ifBlank { "Claude" }
                        handler.post { startRing(msg, caller) }
                    }
                } finally {
                    reader.close()
                }
            } catch (e: Exception) {
                if (!running) break
                Log.w(TAG, "Connexion perdue, nouvel essai dans ${backoff / 1000} s", e)
                try { Thread.sleep(backoff) } catch (ie: InterruptedException) { break }
                backoff = (backoff * 2).coerceAtMost(60_000L)
            } finally {
                connection?.disconnect()
                connection = null
            }
        }
    }
}
