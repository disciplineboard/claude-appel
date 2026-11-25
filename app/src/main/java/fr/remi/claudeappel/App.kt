package fr.remi.claudeappel

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import org.json.JSONObject

/** Réglages enregistrés sur le téléphone. */
object Prefs {
    private const val FILE = "claude_appel"
    // Sujet ntfy secret : c'est l'« adresse » à laquelle Claude envoie ses appels.
    const val DEFAULT_TOPIC = "claude-remi-id04cdc0yg"

    fun topic(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString("topic", DEFAULT_TOPIC) ?: DEFAULT_TOPIC

    fun setTopic(ctx: Context, value: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("topic", value.trim()).apply()
    }

    fun enabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("enabled", false)

    fun setEnabled(ctx: Context, value: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", value).apply()
    }
}

/** Relance l'écoute automatiquement après un redémarrage du téléphone. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Prefs.enabled(context)) {
            ListenService.start(context)
        }
    }
}

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

/** L'écran d'appel entrant, en plein écran, même téléphone verrouillé. */
class CallActivity : Activity() {

    companion object {
        private const val EXTRA_ANSWER = "answer"

        fun intent(ctx: Context, message: String, caller: String, answer: Boolean): Intent =
            Intent(ctx, CallActivity::class.java)
                .putExtra(ListenService.EXTRA_MESSAGE, message)
                .putExtra(ListenService.EXTRA_CALLER, caller)
                .putExtra(EXTRA_ANSWER, answer)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    private var tts: TextToSpeech? = null
    private lateinit var status: TextView
    private lateinit var messageView: TextView
    private lateinit var buttons: LinearLayout
    private var message = ""
    private var caller = "Claude"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        buildUi()
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        message = intent.getStringExtra(ListenService.EXTRA_MESSAGE) ?: "Claude a fini ta tâche."
        caller = intent.getStringExtra(ListenService.EXTRA_CALLER) ?: "Claude"
        findViewById<TextView>(R.id.callerName)?.text = caller
        if (intent.getBooleanExtra(EXTRA_ANSWER, false)) answer()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor("#1F1E1D"))
            setPadding(48, 160, 48, 120)
        }
        val avatar = TextView(this).apply {
            text = "C"
            textSize = 56f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#D97757"))
            }
        }
        root.addView(avatar, LinearLayout.LayoutParams(320, 320))

        root.addView(TextView(this).apply {
            id = R.id.callerName
            text = caller
            textSize = 34f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 48, 0, 8)
        })
        status = TextView(this).apply {
            text = "Appel entrant…"
            textSize = 18f
            setTextColor(Color.parseColor("#BBBBBB"))
            gravity = Gravity.CENTER
        }
        root.addView(status)

        messageView = TextView(this).apply {
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 64, 0, 0)
        }
        root.addView(messageView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        buttons.addView(roundButton("Raccrocher", "#E5484D") { hangUp() })
        buttons.addView(roundButton("Décrocher", "#30A46C") { answer() })
        root.addView(buttons)

        setContentView(root)
    }

    private fun roundButton(label: String, color: String, onClick: () -> Unit) =
        Button(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 16f
            background = GradientDrawable().apply {
                cornerRadius = 120f
                setColor(Color.parseColor(color))
            }
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(380, 170).apply { setMargins(30, 0, 30, 0) }
        }

    private fun answer() {
        ListenService.stopRinging(this)
        status.text = "En ligne"
        messageView.text = message
        buttons.removeAllViews()
        buttons.addView(roundButton("Raccrocher", "#E5484D") { hangUp() })

        tts?.shutdown()
        tts = TextToSpeech(this) { ok ->
            if (ok == TextToSpeech.SUCCESS) {
                tts?.setLanguage(Locale.FRANCE)
                tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "msg1")
                tts?.playSilentUtterance(700, TextToSpeech.QUEUE_ADD, "pause")
                tts?.speak("Je répète. $message", TextToSpeech.QUEUE_ADD, null, "msg2")
            } else {
                status.text = "En ligne (voix indisponible)"
            }
        }
    }

    private fun hangUp() {
        ListenService.stopRinging(this)
        finish()
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}

/** Écran de réglage : sujet, autorisations, marche/arrêt et test. */
class MainActivity : Activity() {

    private lateinit var topicInput: EditText
    private lateinit var stateText: TextView
    private lateinit var toggleBtn: Button
    private lateinit var permsBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
            setBackgroundColor(Color.parseColor("#1F1E1D"))
        }

        root.addView(text("📞 Claude t'appelle", 28f, bold = true))
        root.addView(text("Quand Claude a fini une tâche, ton téléphone sonne comme un vrai appel et une voix lit son message.", 15f, "#BBBBBB"))

        root.addView(text("Sujet secret (ne le partage qu'avec Claude) :", 15f, "#BBBBBB", top = 48))
        topicInput = EditText(this).apply {
            setText(Prefs.topic(this@MainActivity))
            setTextColor(Color.WHITE)
            isSingleLine = true
        }
        root.addView(topicInput)

        stateText = text("", 17f, top = 24)
        root.addView(stateText)

        toggleBtn = button("") { toggle() }
        root.addView(toggleBtn)
        root.addView(button("Tester : ça sonne dans 5 secondes") {
            Toast.makeText(this, "Verrouille ton téléphone, ça va sonner…", Toast.LENGTH_SHORT).show()
            Handler(Looper.getMainLooper()).postDelayed({
                ListenService.ring(this, "Salut Rémi ! C'est Claude. Ceci est un appel de test, tout fonctionne.")
            }, 5_000)
        })

        root.addView(text("Autorisations nécessaires", 18f, bold = true, top = 48))
        permsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(permsBox)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#1F1E1D"))
            addView(root)
        })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun toggle() {
        if (Prefs.enabled(this)) {
            ListenService.stop(this)
        } else {
            val topic = topicInput.text.toString().trim()
            if (topic.length < 8) {
                Toast.makeText(this, "Choisis un sujet d'au moins 8 caractères", Toast.LENGTH_SHORT).show()
                return
            }
            Prefs.setTopic(this, topic)
            Prefs.setEnabled(this, true)
            ListenService.start(this)
        }
        Handler(Looper.getMainLooper()).postDelayed({ refresh() }, 300)
    }

    private fun refresh() {
        val on = Prefs.enabled(this)
        stateText.text = if (on) "✅ En écoute : Claude peut t'appeler" else "⏸️ Écoute arrêtée"
        toggleBtn.text = if (on) "Arrêter l'écoute" else "Activer l'écoute"
        topicInput.isEnabled = !on

        permsBox.removeAllViews()
        val nm = getSystemService(NotificationManager::class.java)
        val pm = getSystemService(PowerManager::class.java)

        if (Build.VERSION.SDK_INT >= 33) {
            val ok = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            permsBox.addView(permRow("Notifications", ok) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            })
        }
        if (Build.VERSION.SDK_INT >= 34) {
            permsBox.addView(permRow("Écran d'appel plein écran", nm.canUseFullScreenIntent()) {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                    Uri.parse("package:$packageName")))
            })
        }
        permsBox.addView(permRow("Pas de mise en veille (batterie)", pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")))
        })
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    // ---------- Petits outils d'interface ----------

    private fun text(s: String, size: Float, color: String = "#FFFFFF", bold: Boolean = false, top: Int = 12) =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(Color.parseColor(color))
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, top, 0, 8)
        }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    private fun permRow(label: String, ok: Boolean, fix: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(text((if (ok) "✅ " else "❌ ") + label, 15f, top = 16),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (!ok) addView(button("Autoriser") { fix() })
        }
}
