package de.sawtschuk.hc2garmin.domain.usecase

import de.sawtschuk.hc2garmin.data.fit.FitFileBuilder
import de.sawtschuk.hc2garmin.data.healthconnect.HealthConnectManager
import de.sawtschuk.hc2garmin.data.local.PreferencesManager
import de.sawtschuk.hc2garmin.data.remote.GarminApiService
import de.sawtschuk.hc2garmin.data.remote.GarminAuthService
import de.sawtschuk.hc2garmin.data.remote.MfaRequiredException
import de.sawtschuk.hc2garmin.data.remote.RateLimitedException
import de.sawtschuk.hc2garmin.domain.model.SyncResult
import java.time.LocalDate
import java.time.ZoneId

class SyncBloodPressureUseCase(
    private val prefs: PreferencesManager,
    private val authService: GarminAuthService,
    private val apiService: GarminApiService,
    private val hcManager: HealthConnectManager
) {
    suspend fun execute(sinceOverrideMillis: Long? = null): SyncResult {
        if (!hcManager.isAvailable()) return SyncResult.PermissionError
        if (!hcManager.hasPermissions()) return SyncResult.PermissionError
        if (prefs.getEmail() == null) return SyncResult.NoCredentials

        val tokenResult = authService.ensureValidToken()
        if (tokenResult.isFailure) {
            val exception = tokenResult.exceptionOrNull()
            val msg = exception?.message ?: "Auth failed"
            return when (exception) {
                is MfaRequiredException -> SyncResult.AuthError("MFA_REQUIRED")
                is RateLimitedException -> SyncResult.NetworkError("RATE_LIMITED: $msg")
                else -> SyncResult.AuthError(msg)
            }
        }

        val lastBpTs = prefs.getLastBpMeasTimestamp()
        val sinceMillis = sinceOverrideMillis ?: if (lastBpTs == 0L) {
            // First sync: start from today midnight (local time)
            LocalDate.now(ZoneId.systemDefault())
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        } else {
            lastBpTs + 1L
        }

        val records = runCatching { hcManager.readBloodPressureSince(sinceMillis) }
            .getOrElse { return SyncResult.NetworkError("Health Connect BP read failed: ${it.message}") }

        if (records.isEmpty()) {
            return SyncResult.Success(bpUploaded = 0)
        }

        var uploaded = 0
        var lastError: String? = null
        var maxUploadedTs = lastBpTs
        for (record in records) {
            val fitBytes = FitFileBuilder.buildBloodPressureFitFile(
                record.systolicMmhg, record.diastolicMmhg, record.epochSeconds,
                record.heartRateBpm ?: 72
            )
            val result = apiService.uploadFit(fitBytes, "bp_${record.epochSeconds}.fit")
            if (result.isSuccess) {
                uploaded++
                val recordTs = record.epochSeconds * 1000L + 999L
                if (recordTs > maxUploadedTs) maxUploadedTs = recordTs
            } else {
                lastError = result.exceptionOrNull()?.message
                android.util.Log.e("HC2Garmin", "BP upload failed: $lastError")
            }
        }

        if (maxUploadedTs > lastBpTs) prefs.setLastBpMeasTimestamp(maxUploadedTs)
        android.util.Log.d("HC2Garmin", "BP sync done: $uploaded/${records.size} uploaded")

        if (uploaded == 0 && lastError != null) {
            return SyncResult.NetworkError("BP upload failed: $lastError")
        }
        return SyncResult.Success(bpUploaded = uploaded)
    }
}
