# HC2Garmin

Android app that automatically syncs weight measurements from **Android Health Connect** to **Garmin Connect**.

If you have a smart scale that writes to Health Connect (e.g. Withings, Xiaomi, Eufy, or any scale with a companion app that supports Health Connect), this app forwards those measurements to your Garmin account so they appear in Garmin Connect and on your Garmin device.

## Features

- Reads weight records from Android Health Connect
- Uploads measurements to Garmin Connect as FIT files (the native format Garmin uses)
- Background sync every hour when connected to Wi-Fi (WorkManager)
- Manual sync button in the app
- Supports Garmin two-factor authentication (email OTP)
- Deduplication — already uploaded measurements are skipped
- Credentials stored encrypted on device (Android Keystore / EncryptedSharedPreferences)
- Rate-limit protection to avoid Garmin account lockout

## Requirements

- Android 14 (API 34) or higher
- Android Health Connect installed and set up
- A Garmin Connect account
- A scale (or any app) that writes weight data to Health Connect

## Setup

1. Install the APK (see Build section below or download from Releases)
2. Open the app and tap **Connect to Garmin**
3. Enter your Garmin Connect email and password
4. If you have two-factor authentication enabled on your Garmin account, enter the code sent to your email
5. Grant the **Health Connect** read permission when prompted
6. Tap **Sync Now** to run the first sync, or wait for the automatic background sync

> **Note:** The Health Connect background permission (`READ_HEALTH_DATA_IN_BACKGROUND`) must be granted manually in the Health Connect app under App permissions for HC2Garmin.

## How it works

```
Health Connect (WeightRecord)
        ↓
  SyncWeightUseCase
        ↓  dedup against Garmin
  FitFileBuilder  →  binary .fit file
        ↓
  Garmin Connect upload-service API
```

Authentication uses the same mobile OAuth flow as the official Garmin Connect app (`sso.garmin.com/mobile/api/login` → service ticket → DI OAuth2 tokens). No third-party auth libraries are used.

FIT files are generated without the official Garmin FIT SDK — the binary format is written directly to keep the APK small.

## Build

Prerequisites: Android Studio (for the JBR) and Android SDK.

```powershell
# Windows — set Java to Android Studio's bundled JBR
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"

cd hc2garmin
.\gradlew assembleDebug
```

Output: `app\build\outputs\apk\debug\app-debug.apk`

Install on a connected device:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

### Build toolchain

| Component | Version |
|---|---|
| AGP | 8.9.1 |
| Kotlin | 2.1.20 |
| Gradle | 8.11.1 |
| compileSdk / targetSdk | 36 |
| minSdk | 34 |

## Debugging

```bash
adb logcat -s HC2Garmin
```

Full Garmin API response bodies are logged under this tag.

## Privacy

- Credentials are stored exclusively on the device using Android's encrypted storage — they are never transmitted anywhere other than Garmin's own servers.
- No analytics, no crash reporting, no third-party SDKs.
- The app requests only the Health Connect `READ_WEIGHT` permission.

## Known limitations

- Weight is the only metric synced (no body fat, BMI, etc.)
- Garmin's SSO endpoint can change without notice; the app tries four different OAuth client IDs as a fallback
- The `EncryptedSharedPreferences` API used is still in alpha (`1.1.0-alpha06`) — this is the version that works correctly on API 34+
