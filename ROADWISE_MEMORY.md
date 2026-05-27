# RoadWise Development Context Memory

This document serves as a "save point" and summary of all major features, fixes, and configurations implemented in the RoadWise project. If changes are accidentally reverted, use this as a guide for restoration.

## 🛠 Project Technical Stack
- **Android Gradle Plugin (AGP):** 8.13.2
- **Kotlin Version:** 2.3.21
- **Compile/Target SDK:** 36 (Android 16 compatibility)
- **Java Compatibility:** VERSION_11
- **ML Engine:** ONNX Runtime 1.26.0 (16 KB Page Aligned)

## ✨ Major Implemented Features

### 1. Account & Profile Management
- **`AccountActivity.kt`**: Dedicated profile screen showing user name and email.
- **Modern UI**: Cross-fade transitions (`fade_in`/`fade_out`) between all screens.
- **Smart Logout**: Uses synchronous `commit()` to ensure UI resets instantly to "ACCOUNT" label on exit.

### 2. Admin & Privacy System
- **Dynamic Admin Management**: Admins are managed via a Firestore collection named `admins`. Document ID = user email.
- **Super-Admins**: Hardcoded fallback in `SessionManager.kt` (`admin@roadwise.com`, `admin2@gmail.com`).
- **Data Isolation**: 
    - Standard users only see/delete their own history.
    - Admins see and can delete all records globally.
    - Everyone can see all hazards on the shared Heatmap (safety first).

### 3. Build & System Modernization
- **16 KB Page Alignment**: Fully compliant with Android 15 requirements.
    - `extractNativeLibs="false"` in Manifest.
    - `useLegacyPackaging = false` in build.gradle.
- **Immersive Mode**: The system status/notification bar is automatically hidden across all activities using `WindowInsetsControllerCompat`.
- **SingleTop Launch**: `MainActivity` uses `launchMode="singleTop"` to prevent "rebuilding" UI when navigating back from Settings/Account.
- **Dynamic Versioning**: Splash and Settings screens dynamically pull the version string (`v1.0`) from the build config.

### 4. Reliable Auto-Start
- **Dual-Layer Detection**: Uses precise `ActivityTransition` events + periodic `ActivityRecognition` fallback (every 2 mins).
- **Still-Exit Wakeup**: Wakes the app immediately when a user stops being stationary to ensure driving isn't missed.

## 📜 Firestore Security Rules
```javascript
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    function isAdmin() {
      return request.auth != null && (
        request.auth.token.email in ["admin@roadwise.com", "roadwise.admin@gmail.com", "admin2@gmail.com"] ||
        exists(/databases/$(database)/documents/admins/$(request.auth.token.email))
      );
    }
    match /potholes/{potholeId} {
      allow read: if true;
      allow create: if request.auth != null;
      allow update, delete: if request.auth != null && (
        resource.data.createdByEmail == request.auth.token.email || 
        resource.data.email == request.auth.token.email ||
        isAdmin()
      );
    }
    match /admins/{email} {
      allow read: if request.auth != null && request.auth.token.email == email;
      allow write: if isAdmin();
    }
  }
}
```

## 📝 Maintenance Notes
- **Storage Used**: Now scans `shared_prefs`, `cache`, and `files` directories for an accurate byte count.
- **Coordinate Formatting**: Always use `Locale.US` to prevent crashes/errors in countries that use commas as decimal separators.
- **Open-source Libraries**: List updated in Settings to include ONNX Runtime, Firebase, and JTransforms.
