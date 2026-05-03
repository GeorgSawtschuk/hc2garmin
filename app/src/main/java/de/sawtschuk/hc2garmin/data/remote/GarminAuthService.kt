package de.sawtschuk.hc2garmin.data.remote

import android.util.Base64
import android.util.Log
import de.sawtschuk.hc2garmin.BuildConfig
import de.sawtschuk.hc2garmin.data.local.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class MfaRequiredException(val mfaMethod: String = "email") : Exception("MFA_REQUIRED")
class RateLimitedException(val retryAfterMillis: Long) : Exception("RATE_LIMITED")

class GarminAuthService(private val prefs: PreferencesManager) {

    // Cookie jar shared across requests so MFA session is maintained
    private val cookieStore = mutableMapOf<String, MutableList<Cookie>>()
    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookieStore.getOrPut(url.host) { mutableListOf() }.apply {
                removeAll { existing -> cookies.any { it.name == existing.name } }
                addAll(cookies)
            }
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            cookieStore[url.host] ?: emptyList()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .cookieJar(cookieJar)
        .build()

    private val ssoParams = "?clientId=GCM_IOS_DARK&locale=en-US" +
            "&service=https%3A%2F%2Fmobile.integration.garmin.com%2Fgcm%2Fios"
    private val ssoUrl = "https://sso.garmin.com/mobile/api/login$ssoParams"
    private val mfaUrl = "https://sso.garmin.com/mobile/api/mfa/verifyCode$ssoParams"
    private val mfaFallbackUrl = "https://sso.garmin.com/portal/api/mfa/verifyCode$ssoParams"

    suspend fun login(email: String, password: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            checkRateLimit()
            cookieStore.clear()  // fresh session for full login
            val ticket = fetchServiceTicket(email, password)
            val tokens = exchangeTicketForTokens(ticket)
            prefs.saveTokens(tokens)
        }
    }

    // Returns the service ticket. Throws MfaRequiredException if 2FA is needed.
    // Does NOT clear cookies — the session must persist until submitMfaCode is called.
    suspend fun initiateLogin(email: String, password: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                checkRateLimit()
                cookieStore.clear()  // start fresh before this specific login attempt
                fetchServiceTicket(email, password)
                // On MfaRequiredException, cookies are left intact for submitMfaCode
            }
        }

    // Call this after initiateLogin threw MfaRequiredException.
    // Session cookies from initiateLogin must still be present (do NOT clear them).
    // Returns the service ticket on success.
    suspend fun submitMfaCode(mfaCode: String, mfaMethod: String = "email"): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val storedCookies = cookieStore["sso.garmin.com"] ?: emptyList()
                Log.d(TAG, "MFA cookies (${storedCookies.size}): ${storedCookies.map { it.name }}")

                val ticket = trySubmitMfa(mfaUrl, mfaCode, mfaMethod)
                    ?: trySubmitMfa(mfaFallbackUrl, mfaCode, mfaMethod)
                    ?: throw Exception("MFA verification failed on all endpoints")
                ticket
            }
        }

    private fun getUserAgent(): String = "$ANDROID_USER_AGENT_PREFIX/${prefs.getGarminVersion()}"

    private fun trySubmitMfa(url: String, mfaCode: String, mfaMethod: String): String? {
        val bodyJson = JSONObject().apply {
            put("mfaMethod", mfaMethod)
            put("mfaVerificationCode", mfaCode.trim())
            put("rememberMyBrowser", true)
            put("reconsentList", org.json.JSONArray())
            put("mfaSetup", false)
        }.toString()

        val ua = getUserAgent()
        val gVersion = prefs.getGarminVersion()
        val request = Request.Builder()
            .url(url)
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .addHeader("User-Agent", ua)
            .addHeader("X-Garmin-User-Agent", ua)
            .addHeader("X-Garmin-App-Version", gVersion)
            .addHeader("Accept", "application/json, text/plain, */*")
            .addHeader("Origin", "https://sso.garmin.com")
            .addHeader("Referer", "https://sso.garmin.com/")
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return null

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "MFA response url=$url code=${response.code} body=$body")
        }

        if (response.code == 429) {
            val retryMs = 30 * 60 * 1000L
            prefs.setRateLimitUntil(System.currentTimeMillis() + retryMs)
            throw RateLimitedException(retryMs)
        }
        if (!response.isSuccessful) return null

        val json = JSONObject(body)
        val status = json.optJSONObject("responseStatus")?.optString("type") ?: ""
        Log.d(TAG, "MFA status=$status")
        if (status != "SUCCESSFUL") return null

        return json.optString("serviceTicketId").takeIf { it.isNotEmpty() }
    }

    suspend fun finishLoginWithTicket(ticket: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val tokens = exchangeTicketForTokens(ticket)
            prefs.saveTokens(tokens)
            prefs.resetLoginAttempts()
        }
    }

    suspend fun ensureValidToken(): Result<String> = withContext(Dispatchers.IO) {
        val tokens = prefs.getTokens()
        when {
            tokens == null -> relogin()
            tokens.isAccessTokenExpired() && !tokens.isRefreshTokenExpired() ->
                runCatching { refreshTokens(tokens).accessToken }
            tokens.isRefreshTokenExpired() -> relogin()
            else -> Result.success(tokens.accessToken)
        }
    }

    fun clearTokens() = prefs.clearTokens()

    private fun relogin(): Result<String> {
        val email = prefs.getEmail() ?: return Result.failure(Exception("No credentials saved"))
        val password = prefs.getPassword() ?: return Result.failure(Exception("No credentials saved"))
        return runCatching {
            checkRateLimit()
            cookieStore.clear()
            val ticket = fetchServiceTicket(email, password)
            val tokens = exchangeTicketForTokens(ticket)
            prefs.saveTokens(tokens)
            tokens.accessToken
        }
    }

    private fun checkRateLimit() {
        val until = prefs.getRateLimitUntil()
        if (System.currentTimeMillis() < until) {
            throw RateLimitedException(until - System.currentTimeMillis())
        }
        val attempts = prefs.attemptsInCurrentWindow()
        if (attempts >= PreferencesManager.MAX_LOGIN_ATTEMPTS) {
            val windowStart = prefs.getLoginWindowStart()
            val retryMs = PreferencesManager.ATTEMPT_WINDOW_MS - (System.currentTimeMillis() - windowStart)
            throw RateLimitedException(retryMs.coerceAtLeast(60_000))
        }
    }

    private fun fetchServiceTicket(email: String, password: String): String {
        val bodyJson = JSONObject().apply {
            put("username", email)
            put("password", password)
            put("rememberMe", true)
            put("captchaToken", "")
        }.toString()

        prefs.recordLoginAttempt()
        Log.d(TAG, "SSO attempt ${prefs.attemptsInCurrentWindow()}/${PreferencesManager.MAX_LOGIN_ATTEMPTS}")

        val ua = getUserAgent()
        val gVersion = prefs.getGarminVersion()
        val request = Request.Builder()
            .url(ssoUrl)
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .addHeader("User-Agent", ua)
            .addHeader("X-Garmin-User-Agent", ua)
            .addHeader("X-Garmin-App-Version", gVersion)
            .addHeader("Accept", "application/json, text/plain, */*")
            .addHeader("Origin", "https://sso.garmin.com")
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw Exception("Empty SSO response")

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "SSO response code=${response.code} body=$body")
        }

        if (response.code == 429) {
            val retryMs = 30 * 60 * 1000L
            prefs.setRateLimitUntil(System.currentTimeMillis() + retryMs)
            throw RateLimitedException(retryMs)
        }
        if (!response.isSuccessful) {
            val errorMsg = if (BuildConfig.DEBUG) "SSO login failed: HTTP ${response.code} body=$body"
                           else "SSO login failed: HTTP ${response.code}"
            throw Exception(errorMsg)
        }

        val json = JSONObject(body)
        val status = json.optJSONObject("responseStatus")?.optString("type") ?: ""
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "SSO status=$status")
        }

        if (status == "MFA_REQUIRED") {
            val mfaMethod = json.optJSONObject("customerMfaInfo")
                ?.optString("mfaLastMethodUsed", "email") ?: "email"
            throw MfaRequiredException(mfaMethod)
        }
        if (status != "SUCCESSFUL") {
            val errorMsg = if (BuildConfig.DEBUG) "SSO login rejected: $status body=$body"
                           else "SSO login rejected: $status"
            throw Exception(errorMsg)
        }

        return json.getString("serviceTicketId")
    }

    private fun exchangeTicketForTokens(ticket: String): GarminTokens {
        val clientIds = listOf(
            "GARMIN_CONNECT_MOBILE_ANDROID_DI_2025Q2",
            "GARMIN_CONNECT_MOBILE_ANDROID_DI_2024Q4",
            "GARMIN_CONNECT_MOBILE_ANDROID_DI",
            "GARMIN_CONNECT_MOBILE_IOS_DI"
        )
        var lastError: Exception = Exception("All client IDs failed")
        for (clientId in clientIds) {
            runCatching { tryExchangeTicket(ticket, clientId) }
                .onSuccess { return it }
                .onFailure { lastError = it as? Exception ?: Exception(it.message) }
        }
        throw lastError
    }

    private fun tryExchangeTicket(ticket: String, clientId: String): GarminTokens {
        val basicAuth = Base64.encodeToString("$clientId:".toByteArray(), Base64.NO_WRAP)
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("service_ticket", ticket)
            .add("grant_type", "https://connectapi.garmin.com/di-oauth2-service/oauth/grant/service_ticket")
            .add("service_url", "https://mobile.integration.garmin.com/gcm/ios")
            .build()

        val request = Request.Builder()
            .url("https://diauth.garmin.com/di-oauth2-service/oauth/token")
            .post(body)
            .addHeader("Authorization", "Basic $basicAuth")
            .addHeader("Accept", "application/json")
            .build()

        val response = client.newCall(request).execute()
        val bodyStr = response.body?.string() ?: throw Exception("Empty token response")
        
        if (!response.isSuccessful) {
            Log.e(TAG, "Token exchange failed ($clientId): HTTP ${response.code} body=$bodyStr")
            throw Exception("Token exchange failed ($clientId): HTTP ${response.code}")
        }
        return parseTokenResponse(bodyStr, clientId)
    }

    private fun refreshTokens(tokens: GarminTokens): GarminTokens {
        val clientId = tokens.workingClientId
        val basicAuth = Base64.encodeToString("$clientId:".toByteArray(), Base64.NO_WRAP)
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("grant_type", "refresh_token")
            .add("refresh_token", tokens.refreshToken)
            .build()

        val request = Request.Builder()
            .url("https://diauth.garmin.com/di-oauth2-service/oauth/token")
            .post(body)
            .addHeader("Authorization", "Basic $basicAuth")
            .addHeader("Accept", "application/json")
            .build()

        val response = client.newCall(request).execute()
        val bodyStr = response.body?.string() ?: throw Exception("Empty refresh response")
        if (!response.isSuccessful) throw Exception("Token refresh failed: HTTP ${response.code}")
        val newTokens = parseTokenResponse(bodyStr, clientId)
        prefs.saveTokens(newTokens)
        return newTokens
    }

    private fun parseTokenResponse(body: String, clientId: String): GarminTokens {
        val json = JSONObject(body)
        val now = System.currentTimeMillis()
        return GarminTokens(
            accessToken = json.getString("access_token"),
            refreshToken = json.getString("refresh_token"),
            accessTokenExpiresAt = now + json.getLong("expires_in") * 1000L,
            refreshTokenExpiresAt = now + json.optLong("refresh_token_expires_in", 7776000L) * 1000L,
            workingClientId = clientId
        )
    }

    companion object {
        private const val TAG = "HC2Garmin"
        private const val ANDROID_USER_AGENT_PREFIX = "com.garmin.android.apps.connectmobile"
    }
}
