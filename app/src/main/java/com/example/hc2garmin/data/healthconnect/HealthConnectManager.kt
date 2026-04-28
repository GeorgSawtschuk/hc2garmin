package com.example.hc2garmin.data.healthconnect

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.example.hc2garmin.domain.model.WeightMeasurement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

class HealthConnectManager(private val context: Context) {

    private val client: HealthConnectClient by lazy {
        HealthConnectClient.getOrCreate(context)
    }

    val requiredPermissions: Set<String> = setOf(
        HealthPermission.getReadPermission(WeightRecord::class),
        "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
    )

    fun isAvailable(): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    suspend fun hasPermissions(): Boolean = withContext(Dispatchers.IO) {
        if (!isAvailable()) return@withContext false
        val granted = client.permissionController.getGrantedPermissions()
        granted.containsAll(requiredPermissions)
    }

    suspend fun readWeightSince(sinceEpochMillis: Long): List<WeightMeasurement> =
        withContext(Dispatchers.IO) {
            val start = Instant.ofEpochMilli(sinceEpochMillis)
            val end = Instant.now()
            val request = ReadRecordsRequest(
                recordType = WeightRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start, end)
            )
            client.readRecords(request).records.map { record ->
                val date = record.time.atZone(ZoneId.systemDefault()).toLocalDate().toString()
                WeightMeasurement(
                    epochSeconds = record.time.epochSecond,
                    weightKg = record.weight.inKilograms,
                    dateStr = date
                )
            }
        }
}
