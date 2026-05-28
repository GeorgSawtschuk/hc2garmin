package de.sawtschuk.hc2garmin.data.healthconnect

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import de.sawtschuk.hc2garmin.domain.model.BloodPressureMeasurement
import de.sawtschuk.hc2garmin.domain.model.WeightMeasurement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

class HealthConnectManager(private val context: Context) {

    private val client: HealthConnectClient by lazy {
        HealthConnectClient.getOrCreate(context)
    }

    val requiredPermissions: Set<String> = setOf(
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(BodyFatRecord::class),
        HealthPermission.getReadPermission(BloodPressureRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
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

            val weightRecords = client.readRecords(
                ReadRecordsRequest(
                    recordType = WeightRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end)
                )
            ).records

            val fatRecords = client.readRecords(
                ReadRecordsRequest(
                    recordType = BodyFatRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end)
                )
            ).records

            weightRecords.map { weightRecord ->
                val date = weightRecord.time.atZone(ZoneId.systemDefault()).toLocalDate().toString()

                val matchingFat = fatRecords.minByOrNull {
                    abs(it.time.epochSecond - weightRecord.time.epochSecond)
                }?.takeIf {
                    abs(it.time.epochSecond - weightRecord.time.epochSecond) < 60
                }

                WeightMeasurement(
                    epochSeconds = weightRecord.time.epochSecond,
                    weightKg = weightRecord.weight.inKilograms,
                    bodyFatPercentage = matchingFat?.percentage?.value,
                    dateStr = date
                )
            }
        }

    suspend fun readBloodPressureSince(sinceEpochMillis: Long): List<BloodPressureMeasurement> =
        withContext(Dispatchers.IO) {
            val start = Instant.ofEpochMilli(sinceEpochMillis)
            val end = Instant.now()

            val records = client.readRecords(
                ReadRecordsRequest(
                    recordType = BloodPressureRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end)
                )
            ).records

            val restingHrRecords = runCatching {
                client.readRecords(
                    ReadRecordsRequest(
                        recordType = RestingHeartRateRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(start, end)
                    )
                ).records
            }.getOrElse { emptyList() }

            records.map { r ->
                val hr = restingHrRecords
                    .minByOrNull { abs(it.time.epochSecond - r.time.epochSecond) }
                    ?.takeIf { abs(it.time.epochSecond - r.time.epochSecond) <= 60 }
                    ?.beatsPerMinute?.toInt()

                BloodPressureMeasurement(
                    epochSeconds = r.time.epochSecond,
                    systolicMmhg = r.systolic.inMillimetersOfMercury.roundToInt(),
                    diastolicMmhg = r.diastolic.inMillimetersOfMercury.roundToInt(),
                    heartRateBpm = hr,
                    dateStr = r.time.atZone(ZoneId.systemDefault()).toLocalDate().toString()
                )
            }
        }
}
