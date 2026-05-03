# HC2Garmin

Android app that automatically syncs weight and body fat measurements from **Android Health Connect** to **Garmin Connect**.

If you have a smart scale that writes to Health Connect (e.g. Withings, Xiaomi, Eufy, or any scale with a companion app that supports Health Connect), this app forwards those measurements to your Garmin account so they appear in Garmin Connect and on your Garmin device.

## Features

- Reads **Weight** and **Body Fat** records from Android Health Connect
- Pairs fat measurements with weight if they occur within 60 seconds of each other
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
5. Grant the **Health Connect** read permissions (Weight and Body Fat) when prompted
6. Tap **Sync Now** to run the first sync, or wait for the automatic background sync

> **Note:** The Health Connect background permission (`READ_HEALTH_DATA_IN_BACKGROUND`) must be granted manually in the Health Connect app under App permissions for HC2Garmin.

## How it works

```
Health Connect (Weight/BodyFatRecord)
        ↓
  SyncWeightUseCase
        ↓  dedup against Garmin
  FitFileBuilder  →  binary .fit file
        ↓
  Garmin Connect upload-service API
```

Authentication uses the same mobile OAuth flow as the official Garmin Connect app (`sso.garmin.com/mobile/api/login` → service ticket → DI OAuth2 tokens). 

FIT files are generated without the official Garmin FIT SDK — the binary format is written directly to keep the APK small.

## Garmin Authentication & Troubleshooting

Garmin Connect uses a sensitive Single Sign-On (SSO) system that strictly monitors the identity of connecting clients. 

### Why the "Too Many Attempts" Error?
If you see an error message stating **"Too many attempts, please wait..."**, it corresponds to a server-side **HTTP 429** response. This happens when Garmin's security system (Cloudflare) flags the connection as suspicious or "bot-like." 

Common reasons include:
*   Using an unrecognized or generic `User-Agent` string.
*   Too many failed login attempts in a short window.
*   Frequent re-authentication from the same IP address.

### The App Version / User-Agent Workaround
To minimize blocking, HC2Garmin "impersonates" the official Garmin Connect Android app by spoofing its `User-Agent` and `X-Garmin-App-Version` headers. 

In the **Settings** screen, you can manually configure the **Garmin App Version**. 
*   **Default:** `4.75` (a known stable version for authentication).
*   **Customization:** If you are blocked, try appending a random build number to the version string (e.g., `4.75.121313`) or matching the version currently installed on your phone.
*   **Detection:** The app will automatically show you the version of the official Garmin Connect app if it is installed on your device.

### Resetting the Block
If the app internally blocks further attempts due to previous failures, use the **"Reset Rate Limit Counter"** button at the bottom of the Settings screen to clear the local cooldown immediately.

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
- The app requests only the Health Connect `READ_WEIGHT` and `READ_BODY_FAT` permissions.

## Known limitations

- Garmin's SSO endpoint can change without notice; the app tries four different OAuth client IDs as a fallback.
- The `EncryptedSharedPreferences` API used is still in alpha (`1.1.0-alpha06`) — this is the version that works correctly on API 34+.
