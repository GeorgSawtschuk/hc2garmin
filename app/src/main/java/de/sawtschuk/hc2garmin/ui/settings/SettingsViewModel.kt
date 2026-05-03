package de.sawtschuk.hc2garmin.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import de.sawtschuk.hc2garmin.work.SyncWorker
import androidx.lifecycle.viewModelScope
import de.sawtschuk.hc2garmin.data.local.PreferencesManager
import de.sawtschuk.hc2garmin.data.local.PreferencesManager.Companion.MAX_LOGIN_ATTEMPTS
import de.sawtschuk.hc2garmin.data.remote.GarminAuthService
import de.sawtschuk.hc2garmin.data.remote.MfaRequiredException
import de.sawtschuk.hc2garmin.data.remote.RateLimitedException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SettingsUiState(
    val email: String = "",
    val password: String = "",
    val garminVersion: String = "4.75",
    val installedGarminVersion: String? = null,
    val isTesting: Boolean = false,
    val isMfaRequired: Boolean = false,
    val mfaCode: String = "",
    val isSubmittingMfa: Boolean = false,
    val testResult: TestResult? = null,
    val attemptsUsed: Int = 0,
    val maxAttempts: Int = 3
) {
    val attemptsLeft get() = (maxAttempts - attemptsUsed).coerceAtLeast(0)
    val isBlocked get() = attemptsUsed >= maxAttempts
}

sealed class TestResult {
    object Success : TestResult()
    data class Error(val message: String) : TestResult()
}

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = PreferencesManager(app)
    private val authService = GarminAuthService(prefs)

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    // Holds the pending service ticket while waiting for MFA code
    private var pendingTicket: String? = null

    init {
        refreshAttemptCount()
        _state.value = _state.value.copy(
            email = prefs.getEmail() ?: "",
            password = prefs.getPassword() ?: "",
            garminVersion = prefs.getGarminVersion(),
            installedGarminVersion = detectInstalledGarminVersion()
        )
    }

    private fun detectInstalledGarminVersion(): String? {
        return runCatching {
            val packageInfo = getApplication<android.app.Application>()
                .packageManager
                .getPackageInfo("com.garmin.android.apps.connectmobile", 0)
            packageInfo.versionName
        }.getOrNull()
    }

    private fun refreshAttemptCount() {
        _state.value = _state.value.copy(
            attemptsUsed = prefs.attemptsInCurrentWindow(),
            maxAttempts = PreferencesManager.MAX_LOGIN_ATTEMPTS
        )
    }

    fun onEmailChange(v: String) { _state.value = _state.value.copy(email = v, testResult = null) }
    fun onPasswordChange(v: String) { _state.value = _state.value.copy(password = v, testResult = null) }
    fun onGarminVersionChange(v: String) { _state.value = _state.value.copy(garminVersion = v) }
    fun onMfaCodeChange(v: String) { _state.value = _state.value.copy(mfaCode = v.filter { it.isDigit() }.take(6)) }

    fun saveCredentials() {
        val s = _state.value
        if (s.email.isBlank() || s.password.isBlank()) return
        prefs.saveCredentials(s.email.trim(), s.password)
        prefs.setGarminVersion(s.garminVersion.trim())
        prefs.clearTokens()
    }

    fun testConnection() {
        val s = _state.value
        if (s.email.isBlank() || s.password.isBlank()) {
            _state.value = s.copy(testResult = TestResult.Error("Please enter email and password"))
            return
        }
        prefs.saveCredentials(s.email.trim(), s.password)
        prefs.setGarminVersion(s.garminVersion.trim())
        prefs.clearTokens()
        _state.value = s.copy(isTesting = true, testResult = null)
        viewModelScope.launch {
            val result = authService.initiateLogin(s.email.trim(), s.password)
            result.fold(
                onSuccess = { ticket ->
                    // No 2FA — finish auth directly
                    authService.finishLoginWithTicket(ticket).fold(
                        onSuccess = {
                            SyncWorker.schedule(getApplication())
                            _state.value = _state.value.copy(
                                isTesting = false,
                                testResult = TestResult.Success
                            )
                        },
                        onFailure = { e ->
                            _state.value = _state.value.copy(
                                isTesting = false,
                                testResult = TestResult.Error("Token error: ${e.message}")
                            )
                        }
                    )
                },
                onFailure = { e ->
                    when (e) {
                        is MfaRequiredException -> _state.value = _state.value.copy(
                            isTesting = false, isMfaRequired = true, mfaCode = ""
                        )
                        is RateLimitedException -> {
                            val minutes = (e.retryAfterMillis / 60_000).coerceAtLeast(1)
                            _state.value = _state.value.copy(
                                isTesting = false,
                                testResult = TestResult.Error(
                                    "Garmin blocked further attempts. Please wait $minutes minutes before trying again."
                                )
                            )
                        }
                        else -> _state.value = _state.value.copy(
                            isTesting = false,
                            testResult = TestResult.Error(friendlyError(e.message ?: "Unknown error"))
                        )
                    }
                }
            )
        }
    }

    fun submitMfaCode() {
        val code = _state.value.mfaCode
        if (code.length < 6) return
        _state.value = _state.value.copy(isSubmittingMfa = true)
        viewModelScope.launch {
            authService.submitMfaCode(code).fold(
                onSuccess = { ticket ->
                    authService.finishLoginWithTicket(ticket).fold(
                        onSuccess = {
                            SyncWorker.schedule(getApplication())
                            _state.value = _state.value.copy(
                                isSubmittingMfa = false,
                                isMfaRequired = false,
                                mfaCode = "",
                                testResult = TestResult.Success
                            )
                        },
                        onFailure = { e ->
                            _state.value = _state.value.copy(
                                isSubmittingMfa = false,
                                isMfaRequired = false,
                                testResult = TestResult.Error("Token error: ${e.message}")
                            )
                        }
                    )
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        isSubmittingMfa = false,
                        testResult = TestResult.Error("Wrong code: ${e.message}")
                    )
                }
            )
        }
    }

    fun dismissMfa() {
        _state.value = _state.value.copy(isMfaRequired = false, mfaCode = "")
    }

    fun dismissTestResult() { _state.value = _state.value.copy(testResult = null) }

    fun logout() {
        prefs.clearCredentials()
        prefs.clearTokens()
        _state.value = _state.value.copy(
            email = "",
            password = "",
            testResult = null
        )
    }

    fun clearRateLimit() {
        prefs.clearRateLimit()
        refreshAttemptCount()
        _state.value = _state.value.copy(testResult = null)
    }

    private fun friendlyError(msg: String) = when {
        msg.contains("401") || msg.contains("rejected") ->
            "Invalid email or password. Please check your Garmin Connect credentials."
        msg.contains("429") -> "Too many attempts. Please wait a minute and try again."
        else -> "Connection failed: $msg"
    }
}
