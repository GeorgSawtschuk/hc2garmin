package de.sawtschuk.hc2garmin.domain.model

data class BloodPressureMeasurement(
    val epochSeconds: Long,
    val systolicMmhg: Int,
    val diastolicMmhg: Int,
    val heartRateBpm: Int? = null,
    val dateStr: String
)
