package de.sawtschuk.hc2garmin.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import android.os.PowerManager
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import de.sawtschuk.hc2garmin.data.healthconnect.HealthConnectManager
import de.sawtschuk.hc2garmin.data.local.PreferencesManager
import de.sawtschuk.hc2garmin.data.remote.GarminApiService
import de.sawtschuk.hc2garmin.data.remote.GarminAuthService
import de.sawtschuk.hc2garmin.data.remote.MfaRequiredException
import de.sawtschuk.hc2garmin.data.remote.RateLimitedException
import de.sawtschuk.hc2garmin.domain.model.SyncResult
import de.sawtschuk.hc2garmin.domain.usecase.SyncBloodPressureUseCase
import de.sawtschuk.hc2garmin.domain.usecase.SyncWeightUseCase
import de.sawtschuk.hc2garmin.work.SyncWorker
import de.sawtschuk.hc2garmin.work.NotificationHelper
import de.sawtschuk.hc2garmin.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

data class MainUiState(
    val hasCredentials: Boolean = false,
    val hasHcPermission: Boolean = false,
    val hasHistoryPermission: Boolean = false,
    val isHistoryImportAvailable: Boolean = false,
    val hasNotificationPermission: Boolean = true,
    val isGarminAuthenticated: Boolean = false,
    val isIgnoringBatteryOptimizations: Boolean = true,
    val lastSyncText: String = "Never",
    val lastSyncCount: Int = 0,
    val isSyncing: Boolean = false,
    val syncError: String? = null,
    // Connect dialog
    val showConnectDialog: Boolean = false,
    val dialogEmail: String = "",
    val dialogPassword: String = "",
    val isConnecting: Boolean = false,
    val dialogError: String? = null,
    val isMfaRequired: Boolean = false,
    val mfaCode: String = "",
    val mfaMethod: String = "email",
    val isSubmittingMfa: Boolean = false
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = PreferencesManager(app)
    private val authService = GarminAuthService(prefs)
    private val apiService = GarminApiService(authService)
    private val hcManager = HealthConnectManager(app)

    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    val requiredPermissions: Set<String> get() = hcManager.requiredPermissions
    val historyPermissions: Set<String> get() = setOf(hcManager.historyPermission)

    fun loadState() {
        viewModelScope.launch {
            val ts = prefs.getLastSyncTimestamp()
            val tsText = if (ts == 0L) getApplication<Application>().getString(R.string.sync_never)
            else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ts))

            val hasHcPerm = hcManager.hasPermissions()
            val hasHistoryPerm = hcManager.hasHistoryPermission()
            val hasCredentials = prefs.getEmail() != null
            val isGarminAuth = prefs.getTokens()?.isAccessTokenExpired() == false
            
            val hasNotifPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    getApplication(), 
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else true

            checkBatteryOptimization()

            _state.value = _state.value.copy(
                hasCredentials = hasCredentials,
                hasHcPermission = hasHcPerm,
                hasHistoryPermission = hasHistoryPerm,
                isHistoryImportAvailable = hcManager.isHistoryReadAvailable(),
                hasNotificationPermission = hasNotifPerm,
                isGarminAuthenticated = isGarminAuth,
                lastSyncText = tsText,
                lastSyncCount = prefs.getLastSyncCount()
            )

            // Auto-schedule if everything is ready and NOT in the middle of authentication
            if (hasHcPerm && isGarminAuth && !_state.value.showConnectDialog) {
                SyncWorker.schedule(getApplication())
            }
        }
    }

    fun checkBatteryOptimization() {
        val pm = getApplication<Application>().getSystemService(Context.POWER_SERVICE) as PowerManager
        val isIgnoring = pm.isIgnoringBatteryOptimizations(getApplication<Application>().packageName)
        _state.value = _state.value.copy(isIgnoringBatteryOptimizations = isIgnoring)
    }

    fun showConnectDialog() {
        _state.value = _state.value.copy(
            showConnectDialog = true,
            dialogEmail = prefs.getEmail() ?: "",
            dialogPassword = prefs.getPassword() ?: "",
            dialogError = null,
            isMfaRequired = false,
            mfaCode = ""
        )
    }

    fun dismissConnectDialog() {
        _state.value = _state.value.copy(
            showConnectDialog = false,
            isMfaRequired = false,
            mfaCode = "",
            dialogError = null
        )
    }

    fun onDialogEmailChange(v: String) { _state.value = _state.value.copy(dialogEmail = v, dialogError = null) }
    fun onDialogPasswordChange(v: String) { _state.value = _state.value.copy(dialogPassword = v, dialogError = null) }
    fun onMfaCodeChange(v: String) { _state.value = _state.value.copy(mfaCode = v.filter { it.isDigit() }.take(6)) }

    fun connectGarmin() {
        val s = _state.value
        if (s.dialogEmail.isBlank() || s.dialogPassword.isBlank()) return
        prefs.saveCredentials(s.dialogEmail.trim(), s.dialogPassword)
        prefs.clearTokens()
        _state.value = s.copy(isConnecting = true, dialogError = null)
        viewModelScope.launch {
            authService.initiateLogin(s.dialogEmail.trim(), s.dialogPassword).fold(
                onSuccess = { ticket ->
                    authService.finishLoginWithTicket(ticket).fold(
                        onSuccess = {
                            SyncWorker.schedule(getApplication())
                            _state.value = _state.value.copy(
                                isConnecting = false,
                                showConnectDialog = false,
                                isGarminAuthenticated = true,
                                hasCredentials = true
                            )
                        },
                        onFailure = { e ->
                            _state.value = _state.value.copy(
                                isConnecting = false,
                                dialogError = "Token error: ${e.message}"
                            )
                        }
                    )
                },
                onFailure = { e ->
                    when (e) {
                        is MfaRequiredException -> _state.value = _state.value.copy(
                            isConnecting = false, isMfaRequired = true, mfaCode = "",
                            mfaMethod = e.mfaMethod
                        )
                        is RateLimitedException -> {
                            val minutes = (e.retryAfterMillis / 60_000).coerceAtLeast(1)
                            _state.value = _state.value.copy(
                                isConnecting = false,
                                dialogError = "Too many attempts. Wait $minutes min and try again."
                            )
                        }
                        else -> _state.value = _state.value.copy(
                            isConnecting = false,
                            dialogError = friendlyError(e.message ?: "Unknown error")
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
        val method = _state.value.mfaMethod
        viewModelScope.launch {
            authService.submitMfaCode(code, method).fold(
                onSuccess = { ticket ->
                    authService.finishLoginWithTicket(ticket).fold(
                        onSuccess = {
                            SyncWorker.schedule(getApplication())
                            _state.value = _state.value.copy(
                                isSubmittingMfa = false,
                                isMfaRequired = false,
                                showConnectDialog = false,
                                isGarminAuthenticated = true,
                                hasCredentials = true
                            )
                        },
                        onFailure = { e ->
                            _state.value = _state.value.copy(
                                isSubmittingMfa = false,
                                isMfaRequired = false,
                                dialogError = "Token error: ${e.message}"
                            )
                        }
                    )
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        isSubmittingMfa = false,
                        dialogError = "Wrong code: ${e.message}"
                    )
                }
            )
        }
    }

    fun triggerManualSync() {
        _state.value = _state.value.copy(isSyncing = true, syncError = null)
        viewModelScope.launch {
            val weightUseCase = SyncWeightUseCase(prefs, authService, apiService, hcManager)
            val bpUseCase = SyncBloodPressureUseCase(prefs, authService, apiService, hcManager)

            val weightResult = runCatching { weightUseCase.execute() }.getOrElse { SyncResult.NetworkError(it.message) }
            val bpResult = runCatching { bpUseCase.execute() }.getOrElse { SyncResult.NetworkError(it.message) }

            val bpUploaded = (bpResult as? SyncResult.Success)?.bpUploaded ?: 0

            val error: String? = when (weightResult) {
                is SyncResult.Success -> {
                    weightResult.lastMeasurement?.let {
                        NotificationHelper.showSyncNotification(getApplication(), it, bpUploaded)
                    } ?: run {
                        if (bpUploaded > 0) NotificationHelper.showSyncNotification(getApplication(), null, bpUploaded)
                    }
                    if (bpResult is SyncResult.NetworkError) "Network error (blood pressure): ${bpResult.message}"
                    else null
                }
                is SyncResult.AuthError -> "Garmin auth error: ${weightResult.message}"
                is SyncResult.NetworkError -> "Network error: ${weightResult.message}"
                is SyncResult.PermissionError -> "Health Connect permission required"
                is SyncResult.NoCredentials -> "Please configure Garmin credentials in Settings"
            }

            val weightUploaded = (weightResult as? SyncResult.Success)?.uploadedCount ?: 0
            val bpTotal = (bpResult as? SyncResult.Success)?.bpUploaded ?: 0
            val totalUploaded = weightUploaded + bpTotal

            // Always update count so the UI reflects the combined result
            if (weightResult is SyncResult.Success || bpResult is SyncResult.Success) {
                prefs.setLastSyncTimestamp(System.currentTimeMillis())
                prefs.setLastSyncCount(totalUploaded)
            }

            val ts = prefs.getLastSyncTimestamp()
            val tsText = if (ts == 0L) getApplication<Application>().getString(R.string.sync_never)
            else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ts))

            _state.value = _state.value.copy(
                isSyncing = false,
                syncError = error,
                lastSyncText = tsText,
                lastSyncCount = prefs.getLastSyncCount(),
                isGarminAuthenticated = prefs.getTokens()?.isAccessTokenExpired() == false
            )
        }
    }

    fun onHistoryPermissionResult(granted: Boolean) {
        _state.value = _state.value.copy(hasHistoryPermission = granted)
        if (granted) {
            triggerHistoryImport()
        } else {
            _state.value = _state.value.copy(
                syncError = getApplication<Application>().getString(R.string.history_permission_required)
            )
        }
    }

    fun triggerHistoryImport() {
        val current = _state.value
        if (!current.hasHcPermission || !current.hasHistoryPermission || !current.isGarminAuthenticated) {
            _state.value = current.copy(
                syncError = getApplication<Application>().getString(R.string.history_import_not_ready)
            )
            return
        }
        _state.value = current.copy(
            syncError = getApplication<Application>().getString(R.string.history_import_not_implemented)
        )
    }

    fun dismissError() { _state.value = _state.value.copy(syncError = null) }

    private fun friendlyError(msg: String) = when {
        msg.contains("401") || msg.contains("rejected") ->
            "Invalid email or password. Please check your Garmin Connect credentials."
        msg.contains("429") -> "Too many attempts. Please wait a minute and try again."
        else -> "Connection failed: $msg"
    }
}
