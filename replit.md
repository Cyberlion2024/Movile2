# Movile2 Kotlin Overlay Bot

## Project Overview
Android bot application in Kotlin with overlay and accessibility services for mobile game automation.

## Architecture
- **Type**: Android APK project (Kotlin + Gradle Kotlin DSL)
- **Package**: `com.movile2.bot`
- **Min SDK**: 26 (Android 8.0 Oreo)
- **Target SDK**: 34 (Android 14)

## Components
- `app/src/main/java/com/movile2/bot/MainActivity.kt` — Main activity with permission buttons
- `app/src/main/java/com/movile2/bot/OverlayService.kt` — Foreground service with floating button overlay
- `app/src/main/java/com/movile2/bot/BotAccessibilityService.kt` — Accessibility service running gesture loop
- `app/src/main/res/layout/activity_main.xml` — Main activity UI layout
- `app/src/main/AndroidManifest.xml` — App manifest with permissions

## Build System
- Gradle 8.x with Kotlin DSL (`build.gradle.kts`)
- JDK 17 required for compilation

## Replit Setup
- **Web server**: `serve.py` — Python HTTP server serving project info page on port 5000
- **Workflow**: "Start application" runs `python serve.py`
- **Deployment**: Autoscale, runs `python serve.py`

## Building the APK
The APK cannot be built directly in Replit (requires Android SDK build tools not available here).

### Option 1: GitHub Actions (recommended)
Push to GitHub — the workflow at `.github/workflows/build-apk.yml` will automatically build the APK and upload it as an artifact.

### Option 2: Local machine
```bash
gradle assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```
Requires: Android SDK, JDK 17, Gradle

## Coordinate Calibration
Update coordinates in `BotAccessibilityService.kt`:
- Attack: (950, 700)
- Skill 1: (850, 650)
- Skill 2: (780, 720)
- Move swipe: (500, 800) → (500, 400)
