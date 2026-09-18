package com.yonatan.ktuvit

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.util.Log
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
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

    override fun load(context: Context) {
        KtuvitStore.init(context)

        when (val result = Injector.inject(KtuvitApi())) {
            is Injector.Result.Injected ->
                Log.i(TAG, "Ktuvit registered as a subtitle provider")

            is Injector.Result.AlreadyPresent ->
                Log.i(TAG, "Ktuvit was already registered")

            is Injector.Result.Failed -> {
                Log.e(TAG, "Injection failed: ${result.reason}", result.error)
                Toast.makeText(
                    context,
                    "Ktuvit: injection failed - ${result.reason}",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }

        openSettings = { ctx -> showSettings(ctx) }
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

        addLabel("Login cookie (optional, skips the login call)")
        val cookieField = addField(KtuvitStore.cookie)

        AlertDialog.Builder(context)
            .setTitle("Ktuvit")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                KtuvitStore.email = emailField.text.toString()
                KtuvitStore.hashedPassword = passwordField.text.toString()
                KtuvitStore.cookie = cookieField.text.toString()
                verifyCredentials(context)
            }
            .setNegativeButton("Cancel", null)
            .show()
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
