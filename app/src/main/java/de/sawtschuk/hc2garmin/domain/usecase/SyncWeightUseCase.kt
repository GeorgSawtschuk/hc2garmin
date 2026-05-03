package de.sawtschuk.hc2garmin.domain.usecase

import de.sawtschuk.hc2garmin.data.fit.FitFileBuilder
import de.sawtschuk.hc2garmin.data.healthconnect.HealthConnectManager
import de.sawtschuk.hc2garmin.data.local.PreferencesManager
import de.sawtschuk.hc2garmin.data.remote.GarminApiService
import de.sawtschuk.hc2garmin.data.remote.GarminAuthService
import de.sawtschuk.hc2garmin.domain.model.SyncResult
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class SyncWeightUseCase(
    private val prefs: PreferencesManager,
    private val authService: GarminAuthService,
    private val apiService: GarminApiService,
    private val hcManager: HealthConnectManager
) {
    suspend fun execute(): SyncResult {
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

        // Determine sync window: first run = 30 days, otherwise since last sync
        val sinceMillis = if (prefs.isFirstRun()) {
            System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        } else {
            prefs.getLastSyncTimestamp().coerceAtLeast(
                System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
            )
        }

        val records = runCatching { hcManager.readWeightSince(sinceMillis) }
            .getOrElse { return SyncResult.NetworkError("Health Connect read failed: ${it.message}") }

        if (records.isEmpty()) {
            if (prefs.isFirstRun()) prefs.setFirstRunComplete()
            return SyncResult.Success(0)
        }

        val startDate = Instant.ofEpochMilli(sinceMillis).atZone(ZoneId.systemDefault()).toLocalDate()
        val endDate = LocalDate.now(ZoneId.systemDefault())
        val existingDates = runCatching {
            apiService.fetchExistingWeightDates(startDate, endDate)
        }.getOrElse { 
            return SyncResult.NetworkError("Failed to fetch existing weights from Garmin: ${it.message}")
        }

        var uploadedCount = 0
        var lastUploadedMeasurement: de.sawtschuk.hc2garmin.domain.model.WeightMeasurement? = null
        for (record in records) {
            if (record.dateStr in existingDates) continue

            val fitBytes = FitFileBuilder.buildWeightFitFile(
                record.weightKg,
                record.bodyFatPercentage,
                record.epochSeconds
            )
            val filename = "weight_${record.epochSeconds}.fit"
            val uploadResult = apiService.uploadFit(fitBytes, filename)
            if (uploadResult.isSuccess) {
                uploadedCount++
                lastUploadedMeasurement = record
            }
        }

        prefs.setLastSyncTimestamp(System.currentTimeMillis())
        prefs.setLastSyncCount(uploadedCount)
        if (prefs.isFirstRun()) prefs.setFirstRunComplete()

        return SyncResult.Success(uploadedCount, lastUploadedMeasurement)
    }
}
