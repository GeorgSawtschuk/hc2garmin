package com.example.hc2garmin.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Constraints
import androidx.work.NetworkType
import com.example.hc2garmin.data.healthconnect.HealthConnectManager
import com.example.hc2garmin.data.local.PreferencesManager
import com.example.hc2garmin.data.remote.GarminApiService
import com.example.hc2garmin.data.remote.GarminAuthService
import com.example.hc2garmin.domain.model.SyncResult
import com.example.hc2garmin.domain.usecase.SyncWeightUseCase
import java.util.concurrent.TimeUnit

class SyncWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prefs = PreferencesManager(applicationContext)
        val authService = GarminAuthService(prefs)
        val apiService = GarminApiService(authService)
        val hcManager = HealthConnectManager(applicationContext)
        val useCase = SyncWeightUseCase(prefs, authService, apiService, hcManager)

        return when (val result = runCatching { useCase.execute() }.getOrElse {
            SyncResult.NetworkError(it.message)
        }) {
            is SyncResult.Success -> Result.success()
            is SyncResult.NetworkError -> Result.retry()
            is SyncResult.AuthError -> Result.failure()
            is SyncResult.PermissionError -> Result.failure()
            is SyncResult.NoCredentials -> Result.failure()
        }
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
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
