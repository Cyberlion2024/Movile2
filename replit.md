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

## Features v3
- Joystick virtuale: imposta il centro del joystick e il bot pattuglia N→E→S→W
- Movimento e attacco simultanei: joystickPush(350ms) + tap attacco + skills
- Mappa area di ricerca con angoli toccabili sullo schermo (fallback senza joystick)
- Ricerca mostro per nome (via albero accessibilità)
- Attacco + abilità 1 e 2 con cooldown separati
- Pozioni automatiche + ricarica dall'inventario
- Limite massimo uccisioni configurabile
- Fix crash Android 14 (foregroundServiceType = specialUse)
- Fix accessibilità su MIUI/Samsung (config semplificata)

## State Machine v3
- HUNT: ciclo fisso 800ms
  - Cicli normali: joystickPush(350ms) → attacco(+400ms) → skill1(+520ms) → skill2(+640ms)
  - Ogni 4 cicli: cameraSwipe(250ms) invece del joystick → attacco(+300ms) → skills(+420/+540ms)
- POTION: tap slot pozione
- REFILL: swipe inventario → slot

## Configurazione consigliata
1. Centro joystick: tocca il centro del joystick (basso-sinistra)
2. Punto visuale: tocca la zona centrale-destra dello schermo (area senza UI)
3. Bottone attacco: il bottone attacco principale (basso-destra)
4. Abilità 1 e 2: le abilità speciali con cooldown
5. Slot pozione: il bottone pozione se usato

## Replit Setup
- **Web server**: `serve.py` su porta 5000 (pagina informativa)
- **Workflow**: "Start application" → `python serve.py`

## Build APK
Push su GitHub → GitHub Actions (`build-apk.yml`) builda automaticamente.
APK scaricabile da Actions › Artifacts.

Local build: `gradle assembleDebug` (richiede Android SDK + JDK 17)

---

## Analisi APK del gioco target (Mobile2 Global 2.23)

Analisi eseguita il 30/03/2026 su `Mobile2_Global_2.23_APKPure.xapk`.

### Struttura del gioco
| Componente | Valore |
|---|---|
| Package | `com.vendsoft.mobile2` |
| Versione | 2.23 |
| Min SDK | 21 / Target SDK 35 |
| Engine | **Unreal Engine 4** |
| Main Activity | `com.epicgames.ue4.GameActivity` |
| Libreria nativa | `libUE4.so` (182 MB) — tutto il codice di gioco |
| Game project | `Mobile2Global` |
| Contenuti | `pakchunk0-Android_ETC2.pak` (UE4 pak file) |

### Struttura XAPK
- `com.vendsoft.mobile2.apk` — wrapper Java + risorse Android
- `config.arm64_v8a.apk` — librerie native arm64 (`libUE4.so` ecc.)
- `config.en.apk` — stringhe localizzate
- `config.xxhdpi.apk` — drawable xxhdpi

### Implicazioni per il bot

**Accessibility tree**: NON funziona. UE4 non crea alcuna Android View per la UI di gioco. L'intera interfaccia (nomi mostri, HP bar, joystick, skill buttons) è renderizzata da UE4 via OpenGL/Vulkan su una SurfaceView opaca.

**Screen pixel detection**: UNICO modo per "vedere" lo stato del gioco. Il nostro approccio con `takeScreenshot()` + ricerca pixel rossi (nomi nemici) è corretto.

**Gesti touch**: FUNZIONANO. UE4 riceve normalmente i `MotionEvent` Android sulla sua SurfaceView, quindi i gesti dell'Accessibility Service arrivano al gioco.

**Colori rilevabili**:
- Nomi mostri nemici: rosso vivace (R>180, G<110, B<90) — già implementato
- Barre HP nemiche: potenziale secondo canale di rilevamento

### Nota FLAG_SECURE
Se il gioco attiva `FLAG_SECURE`, `takeScreenshot()` restituisce schermo nero. In quel caso il bot opera in modalità cieca (joystick + attacco senza detection visiva).

### Classi Java notevoli nel DEX
- `com.epicgames.ue4.GameActivity` — activity principale UE4
- `com.epicgames.ue4.WebViewControl` — WebView per login/browser in-game
- `com.epicgames.ue4.MediaPlayer14` — riproduzione video
- `com.vendsoft.mobile2.OBBDownloaderService` — download OBB al primo avvio
- `com.vendsoft.mobile2.AlarmReceiver` — notifiche locali
