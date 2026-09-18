package com.yonatan.ktuvit

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.util.Log
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@CloudstreamPlugin
class KtuvitPlugin : Plugin() {
    companion object {
        const val TAG = "KtuvitSubtitles"
    }

    /**
     * Plugins are loaded on a background thread, so nothing here may touch the UI -
     * a Toast from here throws and the whole plugin fails to load. The injection
     * result is kept in Injector.lastResult and shown by the diagnostics dialog.
     */
    override fun load(context: Context) {
        openSettings = { ctx -> showSettings(ctx) }

        try {
            KtuvitStore.init(context)

            when (val result = Injector.inject(KtuvitApi())) {
                is Injector.Result.Injected -> Log.i(TAG, "Registered as a subtitle provider")
                is Injector.Result.AlreadyPresent -> Log.i(TAG, "Already registered")
                is Injector.Result.Failed ->
                    Log.e(TAG, "Injection failed: ${result.reason}", result.error)
            }
        } catch (e: Throwable) {
            // Never let a failure here stop the plugin from loading.
            Log.e(TAG, "Load failed", e)
        }
    }

    private fun showSettings(context: Context) {
        val padding = (16 * context.resources.displayMetrics.density).toInt()

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        fun addLabel(text: String) {
            layout.addView(TextView(context).apply {
                this.text = text
                setPadding(0, padding / 2, 0, 0)
            })
        }

        fun addField(initial: String, password: Boolean = false): EditText {
            val field = EditText(context).apply {
                setText(initial)
                inputType = if (password) {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                } else {
                    InputType.TYPE_CLASS_TEXT
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            layout.addView(field)
            return field
        }

        addLabel("Email")
        val emailField = addField(KtuvitStore.email)

        addLabel("Hashed password")
        val passwordField = addField(KtuvitStore.hashedPassword, password = true)

        addLabel("Login cookie")
        val cookieField = addField(KtuvitStore.cookie)

        addLabel("Diagnostics: title  (add  |season|episode  for a series)")
        val testField = addField("")

        val scroll = ScrollView(context).apply { addView(layout) }

        AlertDialog.Builder(context)
            .setTitle("Ktuvit")
            .setView(scroll)
            .setPositiveButton("Save") { _, _ ->
                KtuvitStore.email = emailField.text.toString()
                KtuvitStore.hashedPassword = passwordField.text.toString()
                KtuvitStore.cookie = cookieField.text.toString()
                verifyCredentials(context)
            }
            .setNeutralButton("Test") { _, _ ->
                KtuvitStore.email = emailField.text.toString()
                KtuvitStore.hashedPassword = passwordField.text.toString()
                KtuvitStore.cookie = cookieField.text.toString()
                runDiagnostics(context, testField.text.toString())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Accepts "Breaking Bad|2|5" for a series, or just a title for a movie. */
    private fun runDiagnostics(context: Context, input: String) {
        val parts = input.split("|")
        val title = parts.getOrNull(0)?.trim().orEmpty()
        if (title.isBlank()) {
            Toast.makeText(context, "Ktuvit: enter a title first", Toast.LENGTH_SHORT).show()
            return
        }
        val season = parts.getOrNull(1)?.trim()?.toIntOrNull()
        val episode = parts.getOrNull(2)?.trim()?.toIntOrNull()

        Toast.makeText(context, "Ktuvit: running diagnostics...", Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.IO).launch {
            val report = try {
                KtuvitDiagnostics.report(title, season, episode)
            } catch (e: Throwable) {
                Log.e(TAG, "Diagnostics failed", e)
                "Unexpected failure: ${e.javaClass.simpleName}: ${e.message}"
            }

            Log.i(TAG, report)

            withContext(Dispatchers.Main) {
                val view = TextView(context).apply {
                    text = report
                    setTextIsSelectable(true)
                    val padding = (16 * context.resources.displayMetrics.density).toInt()
                    setPadding(padding, padding, padding, padding)
                }
                AlertDialog.Builder(context)
                    .setTitle("Ktuvit diagnostics")
                    .setView(ScrollView(context).apply { addView(view) })
                    .setPositiveButton("Close", null)
                    .show()
            }
        }
    }

    /** Logs in once right after saving so a wrong value is caught immediately. */
    private fun verifyCredentials(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            val message = try {
                val cookie = KtuvitStore.resolveCookie(forceRefresh = KtuvitStore.cookie.isBlank())
                if (cookie.isNullOrBlank()) {
                    "Ktuvit: login failed, check the email and hashed password"
                } else {
                    "Ktuvit: connected"
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Login check failed", e)
                "Ktuvit: ${e.message ?: "login check failed"}"
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }
}
