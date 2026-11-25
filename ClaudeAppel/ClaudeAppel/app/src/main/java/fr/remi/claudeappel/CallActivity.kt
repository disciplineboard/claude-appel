package fr.remi.claudeappel

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

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
