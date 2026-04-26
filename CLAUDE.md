# HC2Garmin — Claude Code Context

## What this project is

Android app (Kotlin/Jetpack Compose) that reads weight measurements from Android Health Connect and uploads them to Garmin Connect as FIT files. Runs as a background WorkManager job, triggers once per hour on Wi-Fi.

## Build

```powershell
# Must use Android Studio JBR, not system Java
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew assembleDebug
# APK: app\build\outputs\apk\debug\app-debug.apk

adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Min SDK 34 (Android 14) — required by Health Connect background permission.

## Key architectural decisions

- **No Garmin FIT SDK** — FIT files are generated manually (binary, CRC16). The official SDK is too large and not on Maven Central. See `FitFileBuilder.kt`.
- **No Garmin official API client** — auth flow is reverse-engineered from the `python-garminconnect` Python library. Note: `garth` is deprecated as of 2025 because Garmin changed their MFA endpoint; do not use garth as a reference.
- **Cookie persistence** — `GarminAuthService` keeps a `cookieStore` map across requests. This is critical for the MFA flow: `initiateLogin` stores the session cookie; `submitMfaCode` reuses that cookie. Do NOT clear cookies between these two calls.
- **Token storage** — `EncryptedSharedPreferences` (AES256-GCM). Tokens are a JSON-serialised `GarminTokens` data class.
- **Rate-limit protection** — two layers: (1) Garmin 429 response → 30 min cooldown stored in prefs; (2) self-imposed 3-attempts-per-hour counter. Both checked in `checkRateLimit()` before every SSO call.

## Garmin auth flow

```
POST sso.garmin.com/mobile/api/login?clientId=GCM_IOS_DARK&locale=en-US&service=...
  body: {"username": "...", "password": "...", "rememberMe": true, "captchaToken": ""}
  → serviceTicketId   OR   status=MFA_REQUIRED (keep cookies!)

If MFA:
  POST sso.garmin.com/mobile/api/mfa/verifyCode?clientId=GCM_IOS_DARK&locale=en-US&service=...
  body: {"mfaMethod": "email", "mfaVerificationCode": "...", "rememberMyBrowser": true,
         "reconsentList": [], "mfaSetup": false}
  Fallback: sso.garmin.com/portal/api/mfa/verifyCode  (same params, different rate-limit bucket)
  → serviceTicketId

POST diauth.garmin.com/di-oauth2-service/oauth/token
  (try client IDs in order: 2025Q2, 2024Q4, _DI, _IOS_DI)
  → access_token, refresh_token
```

Headers required on SSO calls:
```
User-Agent: GarminConnect/4 CFNetwork/1404.0.5 Darwin/22.3.0
Origin: https://sso.garmin.com
Referer: https://sso.garmin.com/
```

**Important:** The old MFA endpoint `POST /mobile/api/login` with `{"mfaCode": "..."}` (used by garth)
no longer works — Garmin returns `INVALID_REQUEST`. Always use `/mobile/api/mfa/verifyCode`.

## Debugging auth issues

```bash
adb logcat -s HC2Garmin
```

Full SSO response body is logged at every step. MFA type is in `"mfaLastMethodUsed"` field (value `"email"` means code arrives by email, not TOTP).

## Known issues / watch-outs

- `SyncWorker` must NOT be scheduled from `MainActivity.onCreate()`. It would start a background SSO call immediately, corrupting the MFA session cookies. Schedule only after successful token acquisition.
- `EncryptedSharedPreferences` version `1.1.0-alpha06` is needed for API 34 compatibility.
- `android.suppressUnsupportedCompileSdk=35` is set in `gradle.properties` as a safety net; compileSdk is actually 36.
- Build tool chain: AGP 8.9.1, Kotlin 2.1.20, Gradle 8.11.1, compileSdk/targetSdk 36.
- Gradle daemon picks up the wrong JDK if `JAVA_HOME` is not set to the Android Studio JBR before running `gradlew`.

## Project structure

```
app/src/main/java/com/example/hc2garmin/
├── data/
│   ├── fit/FitFileBuilder.kt          # Manual FIT binary generation
│   ├── healthconnect/HealthConnectManager.kt
│   ├── local/PreferencesManager.kt    # EncryptedSharedPreferences wrapper
│   └── remote/
│       ├── GarminAuthService.kt       # OAuth flow + MFA + rate limiting
│       ├── GarminApiService.kt        # Weight fetch + FIT upload
│       └── GarminTokenStore.kt        # GarminTokens data class
├── domain/
│   ├── model/                         # WeightMeasurement, SyncResult
│   └── usecase/SyncWeightUseCase.kt   # Orchestrates HC read → dedup → upload
├── ui/
│   ├── main/                          # MainScreen + MainViewModel (connect dialog)
│   ├── settings/                      # SettingsScreen + SettingsViewModel
│   └── navigation/AppNavigation.kt
└── work/SyncWorker.kt                 # PeriodicWorkRequest, 1h, Wi-Fi only
```
