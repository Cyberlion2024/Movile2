# Movile2 Kotlin Overlay Bot Base

Base Android skeleton in Kotlin with:
- `MainActivity` setup + runtime bot config.
- `OverlayService` with floating start/stop button.
- `BotAccessibilityService` with loop attack + skills + movement + potion logic.

## New runtime config
In app UI you can now set:
- Monster name (partial or exact text match from accessibility events).
- Maximum kills before auto-stop.

## Setup
1. Install and open app.
2. Insert **Monster name** and **Max kills**, then tap **Save Bot Config**.
3. Tap **Grant Overlay Permission**.
4. Tap **Open Accessibility Settings** and enable `Movile2 Bot` service.
5. Tap **Start Overlay** and switch to game.
6. Use floating button to toggle ON/OFF.

## Runtime behavior
- Tries to detect target monster from accessibility event text.
- Moves to search when target is not recently seen.
- Attacks continuously, and uses skills with cooldown tracking.
- Uses health potion on interval.
- Periodically opens inventory and drags a potion to slot (placeholder coordinates).
- Stops automatically when max kill count is reached.

## Build APK
Build command (local machine with Android SDK configured):
```bash
gradle assembleDebug
```

Expected output APK:
`app/build/outputs/apk/debug/app-debug.apk`
