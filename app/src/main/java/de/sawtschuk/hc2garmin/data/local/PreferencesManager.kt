package de.sawtschuk.hc2garmin.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import de.sawtschuk.hc2garmin.data.remote.GarminTokens
import com.google.gson.Gson

class PreferencesManager(context: Context) {

    private val gson = Gson()

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "hc2garmin_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun saveCredentials(email: String, password: String) {
        prefs.edit().putString(KEY_EMAIL, email).putString(KEY_PASSWORD, password).apply()
    }

    fun getEmail(): String? = prefs.getString(KEY_EMAIL, null)
    fun getPassword(): String? = prefs.getString(KEY_PASSWORD, null)

    fun saveTokens(tokens: GarminTokens) {
        prefs.edit().putString(KEY_TOKENS, gson.toJson(tokens)).apply()
    }

    fun getTokens(): GarminTokens? {
        val json = prefs.getString(KEY_TOKENS, null) ?: return null
        return runCatching { gson.fromJson(json, GarminTokens::class.java) }.getOrNull()
    }

    fun clearTokens() {
        prefs.edit().remove(KEY_TOKENS).apply()
    }

    fun getLastSyncTimestamp(): Long = prefs.getLong(KEY_LAST_SYNC_TS, 0L)
    fun setLastSyncTimestamp(ts: Long) { prefs.edit().putLong(KEY_LAST_SYNC_TS, ts).apply() }

    fun getLastSyncCount(): Int = prefs.getInt(KEY_LAST_SYNC_COUNT, 0)
    fun setLastSyncCount(count: Int) { prefs.edit().putInt(KEY_LAST_SYNC_COUNT, count).apply() }

    fun isFirstRun(): Boolean = !prefs.getBoolean(KEY_FIRST_RUN_DONE, false)
    fun setFirstRunComplete() { prefs.edit().putBoolean(KEY_FIRST_RUN_DONE, true).apply() }

    // Rate-limit from Garmin (429 response)
    fun getRateLimitUntil(): Long = prefs.getLong(KEY_RATE_LIMIT_UNTIL, 0L)
    fun setRateLimitUntil(epochMillis: Long) {
        prefs.edit().putLong(KEY_RATE_LIMIT_UNTIL, epochMillis).apply()
    }

    // Login attempt counter — resets after ATTEMPT_WINDOW_MS or on success
    fun getLoginAttempts(): Int = prefs.getInt(KEY_LOGIN_ATTEMPTS, 0)
    fun getLoginWindowStart(): Long = prefs.getLong(KEY_LOGIN_WINDOW_START, 0L)

    fun recordLoginAttempt() {
        val now = System.currentTimeMillis()
        val windowStart = getLoginWindowStart()
        val newCount = if (now - windowStart > ATTEMPT_WINDOW_MS) 1
                       else getLoginAttempts() + 1
        prefs.edit()
            .putInt(KEY_LOGIN_ATTEMPTS, newCount)
            .putLong(KEY_LOGIN_WINDOW_START, if (now - windowStart > ATTEMPT_WINDOW_MS) now else windowStart)
            .apply()
    }

    fun resetLoginAttempts() {
        prefs.edit().putInt(KEY_LOGIN_ATTEMPTS, 0).putLong(KEY_LOGIN_WINDOW_START, 0L).apply()
    }

    fun attemptsInCurrentWindow(): Int {
        val windowStart = getLoginWindowStart()
        return if (System.currentTimeMillis() - windowStart > ATTEMPT_WINDOW_MS) 0
               else getLoginAttempts()
    }

    companion object {
        const val MAX_LOGIN_ATTEMPTS = 3
        const val ATTEMPT_WINDOW_MS = 60 * 60 * 1000L  // 1 hour

        private const val KEY_EMAIL = "garmin_email"
        private const val KEY_PASSWORD = "garmin_password"
        private const val KEY_TOKENS = "garmin_tokens"
        private const val KEY_LAST_SYNC_TS = "last_sync_ts"
        private const val KEY_LAST_SYNC_COUNT = "last_sync_count"
        private const val KEY_FIRST_RUN_DONE = "first_run_done"
        private const val KEY_RATE_LIMIT_UNTIL = "rate_limit_until"
        private const val KEY_LOGIN_ATTEMPTS = "login_attempts"
        private const val KEY_LOGIN_WINDOW_START = "login_window_start"
    }
}
