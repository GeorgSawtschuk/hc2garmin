package de.sawtschuk.hc2garmin.data.remote

import de.sawtschuk.hc2garmin.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class GarminApiService(private val authService: GarminAuthService) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val baseUrl = "https://connectapi.garmin.com"

    suspend fun uploadFit(fitBytes: ByteArray, filename: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val token = authService.ensureValidToken().getOrThrow()

                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "file", filename,
                        fitBytes.toRequestBody("application/octet-stream".toMediaType())
                    )
                    .build()

                val request = Request.Builder()
                    .url("$baseUrl/upload-service/upload")
                    .post(requestBody)
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("DI-Backend", "connectapi.garmin.com")
                    .addHeader("NK", "NT")
                    .addHeader("Accept", "application/json")
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                android.util.Log.d("HC2Garmin", "Upload $filename → HTTP ${response.code}: ${responseBody.take(300)}")
                when (response.code) {
                    200, 201 -> Unit
                    409 -> Unit  // duplicate — treat as success
                    else -> {
                        val errorMsg = if (BuildConfig.DEBUG) "Upload failed: HTTP ${response.code} — $responseBody"
                                       else "Upload failed: HTTP ${response.code}"
                        throw Exception(errorMsg)
                    }
                }
            }
        }
}
