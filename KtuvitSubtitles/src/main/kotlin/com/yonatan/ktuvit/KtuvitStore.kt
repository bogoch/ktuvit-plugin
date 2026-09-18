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
    private const val KEY_PASSWORD = "password"
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

    /** The plain password, which the plugin encrypts the same way the site does. */
    var password: String
        get() = prefs?.getString(KEY_PASSWORD, "").orEmpty()
        set(value) {
            prefs?.edit()?.putString(KEY_PASSWORD, value)?.apply()
        }

    /** Optional: the already encrypted value, for anyone who pulled it out by hand. */
    var hashedPassword: String
        get() = prefs?.getString(KEY_HASHED_PASSWORD, "").orEmpty()
        set(value) {
            prefs?.edit()?.putString(KEY_HASHED_PASSWORD, value.trim())?.apply()
        }

    /** Whatever form is available, in the shape the Login service expects. */
    private val loginSecret: String?
        get() {
            hashedPassword.takeIf { it.isNotBlank() }?.let { return it }
            val plain = password
            val mail = email
            if (plain.isBlank() || mail.isBlank()) return null
            return KtuvitCrypto.encryptPassword(mail, plain)
        }

    var cookie: String
        get() = prefs?.getString(KEY_COOKIE, "").orEmpty()
        set(value) {
            prefs?.edit()?.putString(KEY_COOKIE, value.trim())?.apply()
        }

    val isConfigured: Boolean
        get() = cookie.isNotBlank() || (email.isNotBlank() && loginSecret != null)

    /**
     * Returns a usable login cookie, logging in with the stored credentials when
     * there is no cookie yet - which is also how an expired cookie recovers by
     * itself. Returns null when nothing is configured.
     */
    suspend fun resolveCookie(forceRefresh: Boolean = false): String? {
        if (!forceRefresh) {
            cookie.takeIf { it.isNotBlank() }?.let { return it }
        }

        val mail = email
        val secret = loginSecret
        if (mail.isBlank() || secret.isNullOrBlank()) return null

        val fresh = KtuvitClient.login(mail, secret) ?: return null
        cookie = fresh
        return fresh
    }
}
