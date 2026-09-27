# Cosmo Store Android App

[![Build and Release Debug APK](https://github.com/nunu125125nunu-collab/cosmo-aply-apk/actions/workflows/build-apk.yml/badge.svg)](https://github.com/nunu125125nunu-collab/cosmo-aply-apk/actions/workflows/build-apk.yml)
[![Download APK](https://img.shields.io/badge/Download-Debug%20APK-brightgreen.svg?logo=android)](https://github.com/nunu125125nunu-collab/cosmo-aply-apk/releases/latest/download/cosmo-store-debug.apk)
[![Release](https://img.shields.io/github/v/release/nunu125125nunu-collab/cosmo-aply-apk?include_prereleases&label=Latest%20Release)](https://github.com/nunu125125nunu-collab/cosmo-aply-apk/releases)
[![Platform](https://img.shields.io/badge/Platform-Android%207.0%2B%20(API%2024%2B)-blue.svg)](https://developer.android.com)

A modern, native-feeling Android App Store client engineered with Kotlin, Jetpack Compose / ViewBinding, Material Design 3, Room Database, and an in-app APK/XAPK installation engine.

---

## 📥 Direct APK Download

You can download the latest pre-compiled debug APK directly from GitHub Releases:

- 🔗 **[Direct Download: cosmo-store-debug.apk](https://github.com/nunu125125nunu-collab/cosmo-aply-apk/releases/latest/download/cosmo-store-debug.apk)**
- 📦 **[All Releases & Changelogs](https://github.com/nunu125125nunu-collab/cosmo-aply-apk/releases)**

---

## ⚡ GitHub Actions CI/CD Pipeline

The repository includes a GitHub Actions workflow located at `.github/workflows/build-apk.yml` that automatically compiles the debug APK and publishes it to GitHub Releases.

### Automatic Triggers:
1. **Push to `main` or `master`**: Automatically builds and publishes/updates the release.
2. **Version Tag Push** (`v*` e.g., `v1.0.0`): Automatically creates a new tagged release with the APK attached.
3. **Manual Trigger (`workflow_dispatch`)**:
   - Go to **Actions** → **Build and Release Debug APK** → Click **Run workflow**.
   - Optionally specify a custom release tag or draft mode.
4. **Pull Requests**: Runs a test compilation without creating releases to verify PR integrity.

### Artifacts Produced:
- `cosmo-store-debug.apk`: Ready-to-install debug APK.
- `cosmo-store-debug.apk.sha256`: SHA-256 checksum for verification.
- GitHub Actions Artifacts: Stored under workflow runs for 30 days.

---

## 📱 Key Features & Architecture

### 1. Native Store Browser
- Direct edge-to-edge web core loading `https://cosmo-game.pages.dev/`.
- Pull-to-refresh (`SwipeRefreshLayout`) and top linear loading indicator.
- Custom native offline error state with an instant retry button and network settings shortcut.
- Deep link intent filters supporting web URLs and the `cosmostore://` scheme.

### 2. Background Download Manager
- Runs as an Android Foreground Service (`DownloadService`) with `FOREGROUND_SERVICE_DATA_SYNC`.
- Persistent status bar notification featuring live progress bars, downloaded/total size, and download speed.
- Full **Pause**, **Resume**, and **Cancel** controls via HTTP Range headers (`Range: bytes=X-`), both from the notification panel and the in-app progress card.

### 3. APK & XAPK Auto-Installer Engine
- **Standard APKs**: Uses Android's `FileProvider` to trigger package installation.
- **XAPK Archives**: Unpacks `.xapk` archives, parses `manifest.json`, automatically routes OBB expansion files to `/sdcard/Android/obb/<package_name>/`, and commits multi-split APKs using the Android `PackageInstaller` Session API.
- Dynamic permission management for `REQUEST_INSTALL_PACKAGES` (Android 8.0+).

### 4. "My Library" with Room Database
- Local persistence using Room Database (`AppDatabase`, `InstalledAppEntity`, `InstalledAppDao`).
- Displays all installed apps with game icon, title, package name, installation status, and an **"Open"** button to launch apps directly via `PackageManager`.

### 5. Native Preferences
- **Download Network Mode**: Choose between *Wi-Fi or Mobile Data* or *Wi-Fi Only*.
- **Auto-Install Behavior**: Choose between *Allow Auto Install*, *Ask Before Install*, or *Don't Install Automatically*.
- **Theme Selector**: Toggle between *System Default*, *Light*, *Dark*, and *Site-dependent*.

---

## 🛠 Local Build Instructions

To build the APK locally on your machine:

```bash
# Clone the repository
git clone https://github.com/nunu125125nunu-collab/cosmo-aply-apk.git
cd cosmo-aply-apk

# Make Gradle wrapper executable
chmod +x gradlew

# Build the Debug APK
./gradlew assembleDebug

# Output APK location:
# app/build/outputs/apk/debug/app-debug.apk
```

---

## 📦 How to Install on Android

1. Download `cosmo-store-debug.apk` to your Android device.
2. Tap the downloaded file in your browser or file manager.
3. If prompted, toggle **"Allow from this source"** in Android Settings.
4. Tap **Install** and open **Cosmo Store**.
