# Movile2 Kotlin Overlay Bot Base

Base Android skeleton in Kotlin with:
- `MainActivity` for setup buttons.
- `OverlayService` with floating start/stop button.
- `BotAccessibilityService` that runs a loop for attack/skills/movement gestures.

## Setup
1. Install and open app.
2. Tap **Grant Overlay Permission**.
3. Tap **Open Accessibility Settings** and enable `Movile2 Bot` service.
4. Tap **Start Overlay** and switch to game.
5. Use floating button to toggle ON/OFF.

## Coordinate calibration
Current coordinates are placeholders tuned for screenshots:
- Attack: `(950, 700)`
- Skill 1: `(850, 650)`
- Skill 2: `(780, 720)`
- Move swipe: `(500, 800) -> (500, 400)`

Update values in `BotAccessibilityService.kt` to match your device resolution.

## Build APK
This repository now includes Gradle Kotlin DSL files.

Build command (local machine with Android SDK configured):
```bash
gradle assembleDebug
```

Expected output APK:
`app/build/outputs/apk/debug/app-debug.apk`
