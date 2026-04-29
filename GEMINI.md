# Gemini CLI Context: HC2Garmin

This project is an Android application that automatically synchronizes weight measurements from **Android Health Connect** to **Garmin Connect**. It is designed to bridge the gap for smart scales that write to Health Connect but lack direct Garmin integration.

## Project Overview

*   **Architecture:** Follows a clean-ish architecture with `data`, `domain`, `ui`, and `work` layers.
*   **Key Technologies:**
    *   **Language:** Kotlin (2.1.20)
    *   **UI:** Jetpack Compose (BOM 2024.11.00)
    *   **Storage:** `EncryptedSharedPreferences` for secure credential storage.
    *   **Background Tasks:** `WorkManager` for periodic synchronization (1-hour intervals, Wi-Fi only).
    *   **Network:** `OkHttp` for API requests.
    *   **Health Data:** `androidx.health.connect` for reading weight records.
*   **Core Logic:**
    *   **`FitFileBuilder.kt`**: Manually generates binary FIT files (the format Garmin requires) to avoid the heavy official SDK.
    *   **`GarminAuthService.kt`**: Handles a reverse-engineered Garmin SSO flow, including Multi-Factor Authentication (MFA) via email OTP.
    *   **`SyncWeightUseCase.kt`**: Orchestrates the sync process: reads from Health Connect, deduplicates against existing Garmin entries, and uploads new measurements.

## Building and Running

### Prerequisites
*   **Java:** Must use the Android Studio bundled JBR (Java 17).
*   **SDK:** Android API 34+ (Health Connect background requirements).

### Commands
```powershell
# Set JAVA_HOME to Android Studio's JBR (Windows example)
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"

# Build debug APK
.\gradlew assembleDebug

# Install on connected device
adb install -r app\build\outputs\apk\debug\app-debug.apk

# View logs
adb logcat -s HC2Garmin
```

## Development Conventions

### Coding Standards
*   **Manual FIT Generation:** Do not introduce the official Garmin FIT SDK. Updates to the FIT format should be made directly in `FitFileBuilder.kt` using binary writes.
*   **Auth Flow:** The Garmin SSO flow is fragile. Maintain the cookie store across MFA steps in `GarminAuthService`. Do not use the deprecated `garth` library as a reference.
*   **Security:** Always use `PreferencesManager` (which wraps `EncryptedSharedPreferences`) for storing sensitive data like tokens or passwords.
*   **Dependency Management:** Use `gradle/libs.versions.toml` for all dependency declarations.

### Testing and Validation
*   **Validation:** Verify sync logic by checking `adb logcat -s HC2Garmin` for API response bodies.
*   **UI:** Use Compose Previews for UI development; the app targets API 34+ strictly.

## Project Structure

*   `app/src/main/java/de/sawtschuk/hc2garmin/`
    *   `data/`: Implementation of FIT generation, Health Connect access, and Remote APIs.
    *   `domain/`: Models and Use Cases (business logic).
    *   `ui/`: Compose-based screens and ViewModels.
    *   `work/`: `SyncWorker` for background job execution.
*   `gradle/libs.versions.toml`: Centralized version management.
*   `CLAUDE.md`: Contains detailed technical notes on the Garmin auth flow and FIT implementation.
