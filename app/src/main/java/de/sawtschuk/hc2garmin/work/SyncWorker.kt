package de.sawtschuk.hc2garmin.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Constraints
import androidx.work.NetworkType
import de.sawtschuk.hc2garmin.data.healthconnect.HealthConnectManager
import de.sawtschuk.hc2garmin.data.local.PreferencesManager
import de.sawtschuk.hc2garmin.data.remote.GarminApiService
import de.sawtschuk.hc2garmin.data.remote.GarminAuthService
import de.sawtschuk.hc2garmin.domain.model.SyncResult
import de.sawtschuk.hc2garmin.domain.usecase.SyncBloodPressureUseCase
import de.sawtschuk.hc2garmin.domain.usecase.SyncWeightUseCase
import java.util.concurrent.TimeUnit

class SyncWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = SyncCoordinator.runExclusive {
        runSync()
    }

    private suspend fun runSync(): Result {
        val prefs = PreferencesManager(applicationContext)
        val authService = GarminAuthService(prefs)
        val apiService = GarminApiService(authService)
        val hcManager = HealthConnectManager(applicationContext)

        val weightUseCase = SyncWeightUseCase(prefs, authService, apiService, hcManager)
        val bpUseCase = SyncBloodPressureUseCase(prefs, authService, apiService, hcManager)

        val weightResult = runCatching { weightUseCase.execute() }
            .getOrElse { SyncResult.NetworkError(it.message) }
        val bpResult = runCatching { bpUseCase.execute() }
            .getOrElse { SyncResult.NetworkError(it.message) }

        // Only retry on weight network errors; BP errors are logged but don't block the worker
        if (weightResult is SyncResult.NetworkError) {
            return Result.retry()
        }
        if (weightResult is SyncResult.AuthError || weightResult is SyncResult.PermissionError ||
            weightResult is SyncResult.NoCredentials) {
            return Result.failure()
        }

        val weightSuccess = weightResult as? SyncResult.Success
        val bpSuccess = bpResult as? SyncResult.Success

        val totalBp = bpSuccess?.bpUploaded ?: 0
        weightSuccess?.lastMeasurement?.let {
            NotificationHelper.showSyncNotification(applicationContext, it, totalBp)
        } ?: run {
            if (totalBp > 0) NotificationHelper.showSyncNotification(applicationContext, null, totalBp)
        }

        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "hc2garmin_sync"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

    }
}
