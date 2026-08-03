package de.sawtschuk.hc2garmin.domain.usecase

import de.sawtschuk.hc2garmin.data.fit.FitFileBuilder
import de.sawtschuk.hc2garmin.data.healthconnect.HealthConnectManager
import de.sawtschuk.hc2garmin.data.local.PreferencesManager
import de.sawtschuk.hc2garmin.data.remote.GarminApiService
import de.sawtschuk.hc2garmin.data.remote.GarminAuthService
import de.sawtschuk.hc2garmin.domain.model.WeightMeasurement
import de.sawtschuk.hc2garmin.domain.model.SyncResult
import java.time.LocalDate
import java.time.ZoneId

class SyncWeightUseCase(
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
                is de.sawtschuk.hc2garmin.data.remote.MfaRequiredException -> SyncResult.AuthError("MFA_REQUIRED")
                is de.sawtschuk.hc2garmin.data.remote.RateLimitedException -> SyncResult.NetworkError("RATE_LIMITED: $msg")
                else -> SyncResult.AuthError(msg)
            }
        }

        // Read only measurements newer than the last successfully uploaded one
        val lastWeightTs = prefs.getLastWeightMeasTimestamp()
        val sinceMillis = sinceOverrideMillis ?: if (lastWeightTs == 0L) {
            // First sync: start from today midnight (local time)
            LocalDate.now(ZoneId.systemDefault())
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        } else {
            lastWeightTs + 1L
        }

        val records = runCatching { hcManager.readWeightSince(sinceMillis) }
            .getOrElse { return SyncResult.NetworkError("Health Connect read failed: ${it.message}") }

        if (records.isEmpty()) {
            prefs.setLastSyncTimestamp(System.currentTimeMillis())
            return SyncResult.Success(0)
        }

        var uploadedCount = 0
        var lastUploadedMeasurement: WeightMeasurement? = null
        var maxUploadedTs = lastWeightTs
        for (record in records) {
            val fitBytes = FitFileBuilder.buildWeightFitFile(
                record.weightKg,
                record.bodyFatPercentage,
                record.epochSeconds
            )
            val uploadResult = apiService.uploadFit(fitBytes, "weight_${record.epochSeconds}.fit")
            if (uploadResult.isSuccess) {
                uploadedCount++
                lastUploadedMeasurement = record
                // +999ms to cover the full second — avoids re-reading the same measurement
                // on next sync due to sub-second precision in Health Connect timestamps
                val recordTs = record.epochSeconds * 1000L + 999L
                if (recordTs > maxUploadedTs) maxUploadedTs = recordTs
            }
        }

        prefs.setLastSyncTimestamp(System.currentTimeMillis())
        prefs.setLastSyncCount(uploadedCount)
        if (maxUploadedTs > lastWeightTs) prefs.setLastWeightMeasTimestamp(maxUploadedTs)

        return SyncResult.Success(uploadedCount = uploadedCount, lastMeasurement = lastUploadedMeasurement)
    }
}
