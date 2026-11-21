package com.animenexus

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimeNexusPlugin : Plugin() {
    override fun load(context: Context) {
        val prefs = context.getSharedPreferences("AnimeNexusPrefs", Context.MODE_PRIVATE)
        registerMainAPI(AnimeNexusProvider(prefs))

        openSettings = { ctx ->
            val layout = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(50, 40, 50, 10)
            }

            val userInput = EditText(ctx).apply {
                hint = "Email / Username AnimeNexus"
                setText(prefs.getString("username", ""))
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            }

            val passInput = EditText(ctx).apply {
                hint = "Parola AnimeNexus"
                setText(prefs.getString("password", ""))
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }

            layout.addView(userInput)
            layout.addView(passInput)

            AlertDialog.Builder(ctx)
                .setTitle("Autentificare AnimeNexus")
                .setView(layout)
                .setPositiveButton("Salvează") { _, _ ->
                    prefs.edit()
                        .putString("username", userInput.text.toString().trim())
                        .putString("password", passInput.text.toString())
                        .apply()
                    AnimeNexusProvider.clearSession()
                    Toast.makeText(ctx, "Date salvate! Reîncarcă pagina.", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Anulează", null)
                .show()
        }
    }
}
