package de.sawtschuk.hc2garmin.domain.model

data class BloodPressureMeasurement(
    val epochSeconds: Long,
    val systolicMmhg: Int,
    val diastolicMmhg: Int,
    val dateStr: String  // YYYY-MM-DD, used for Garmin dedup
)
