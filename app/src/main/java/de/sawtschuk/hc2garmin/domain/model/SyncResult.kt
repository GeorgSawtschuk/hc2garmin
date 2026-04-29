package de.sawtschuk.hc2garmin.domain.model

sealed class SyncResult {
    data class Success(
        val uploadedCount: Int,
        val lastMeasurement: WeightMeasurement? = null
    ) : SyncResult()
    data class AuthError(val message: String?) : SyncResult()
    data class NetworkError(val message: String?) : SyncResult()
    object PermissionError : SyncResult()
    object NoCredentials : SyncResult()
}
