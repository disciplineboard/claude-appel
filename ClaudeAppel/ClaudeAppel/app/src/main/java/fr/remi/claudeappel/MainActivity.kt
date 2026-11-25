package fr.remi.claudeappel

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

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
