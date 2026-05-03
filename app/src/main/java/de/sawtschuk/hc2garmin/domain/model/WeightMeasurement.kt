package de.sawtschuk.hc2garmin.domain.model

data class WeightMeasurement(
    val epochSeconds: Long,
    val weightKg: Double,
    val bodyFatPercentage: Double? = null,
    val dateStr: String  // YYYY-MM-DD, used for Garmin dedup
)
