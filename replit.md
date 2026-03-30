# Movile2 Bot

## Project Overview
Android bot app in Kotlin for MMORPG automation using Accessibility Service + Overlay.

## Architecture
- **Type**: Android APK (Kotlin + Gradle Kotlin DSL)
- **Package**: `com.movile2.bot`
- **Min SDK**: 26 / Target SDK: 34

## Source Files
| File | Descrizione |
|---|---|
| `MainActivity.kt` | Schermata impostazioni con tutti i campi configurabili |
| `CoordinatePickerActivity.kt` | Activity trasparente per selezionare coordinate toccando lo schermo |
| `BotAccessibilityService.kt` | Logica principale bot: ricerca a griglia, attacco, abilità, pozioni |
| `OverlayService.kt` | Pannello flottante draggabile con Start/Stop e contatore kills |
| `BotConfig.kt` | Data class + SharedPreferences per persistere le impostazioni |
| `BotState.kt` | Singleton condiviso per stato runtime (isRunning, killCount) |

## Features v2
- Mappa area di ricerca con angoli toccabili sullo schermo
- Ricerca mostro per nome (via albero accessibilità)
- Griglia 5x4 a serpentina dentro l'area
- Attacco + abilità 1 e 2 con cooldown separati
- Pozioni automatiche + ricarica dall'inventario
- Limite massimo uccisioni configurabile
- Fix crash Android 14 (foregroundServiceType = specialUse)
- Fix accessibilità su MIUI/Samsung (config semplificata)

## Replit Setup
- **Web server**: `serve.py` su porta 5000 (pagina informativa)
- **Workflow**: "Start application" → `python serve.py`

## Build APK
Push su GitHub → GitHub Actions (`build-apk.yml`) builda automaticamente.
APK scaricabile da Actions › Artifacts.

Local build: `gradle assembleDebug` (richiede Android SDK + JDK 17)
