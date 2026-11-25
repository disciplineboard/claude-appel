package fr.remi.claudeappel

import android.content.Context

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
