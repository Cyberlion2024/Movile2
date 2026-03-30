# Movile2 Kotlin Overlay Bot Base

Base Android skeleton in Kotlin with:
- `MainActivity` setup + runtime bot config.
- `OverlayService` with floating start/stop button.
- `BotAccessibilityService` with loop attack + skills + room search + potion logic.

## Runtime config
In app UI you can set:
- Monster name (partial/exact match from accessibility event text).
- Maximum kills before auto-stop.
- `Learn room perimeter`: while bot runs, it expands roaming bounds around areas where monsters are detected.

## No manual coordinates for movement
Movement now uses adaptive room roaming:
- Bot searches random points inside dynamic room bounds.
- If target text is found, bounds are expanded around current roam point.
- This allows scanning/farming inside the room perimeter without manually entering movement coordinates.

## Setup
1. Install and open app.
2. Insert **Monster name** and **Max kills**.
3. Enable **Learn room perimeter**.
4. Tap **Save Bot Config**.
5. Tap **Grant Overlay Permission**.
6. Tap **Open Accessibility Settings** and enable `Movile2 Bot` service.
7. Tap **Start Overlay** and switch to game.
8. Use floating button to toggle ON/OFF.

## Build APK
Build command (local machine with Android SDK configured):
```bash
gradle assembleDebug
```

Expected output APK:
`app/build/outputs/apk/debug/app-debug.apk`
