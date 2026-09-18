package com.yonatan.ktuvit

import android.content.Context
import android.content.SharedPreferences

/**
 * Credential storage for the plugin.
 *
 * The provider is registered with requiresLogin = false, so CloudStream never asks
 * for an account - the credentials live here and are entered from the plugin's own
 * settings screen.
 */
object KtuvitStore {
    private const val PREFS = "ktuvit_subtitles"
    private const val KEY_EMAIL = "email"
    private const val KEY_HASHED_PASSWORD = "hashed_password"
    private const val KEY_COOKIE = "login_cookie"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    var email: String
        get() = prefs?.getString(KEY_EMAIL, "").orEmpty()
        set(value) {
            prefs?.edit()?.putString(KEY_EMAIL, value.trim())?.apply()
        }

    var hashedPassword: String
        get() = prefs?.getString(KEY_HASHED_PASSWORD, "").orEmpty()
        set(value) {
            prefs?.edit()?.putString(KEY_HASHED_PASSWORD, value.trim())?.apply()
        }

    var cookie: String
        get() = prefs?.getString(KEY_COOKIE, "").orEmpty()
        set(value) {
            prefs?.edit()?.putString(KEY_COOKIE, value.trim())?.apply()
        }

    val isConfigured: Boolean
        get() = cookie.isNotBlank() || (email.isNotBlank() && hashedPassword.isNotBlank())

    /**
     * Returns a usable login cookie, logging in with the stored credentials when
     * there is no cookie yet. Returns null when nothing is configured.
     */
    suspend fun resolveCookie(forceRefresh: Boolean = false): String? {
        if (!forceRefresh) {
            cookie.takeIf { it.isNotBlank() }?.let { return it }
        }

        val mail = email
        val password = hashedPassword
        if (mail.isBlank() || password.isBlank()) return null

        val fresh = KtuvitClient.login(mail, password) ?: return null
        cookie = fresh
        return fresh
    }
}
