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

## Features v5 — Architettura Professionale
- **Multi-touch simultaneo**: Attack + Skill1..5 + Target nello stesso frame (GestureDescription multi-stroke, fino a 10 pointer)
- **Loop unificato**: nessuna state machine HUNT/DEFEND; pozione inline senza interrompere il combattimento
- **Ciclo combattimento 320ms**: attack immediato + 2 spam extra a 75/150ms
- **Scan ogni 600ms**: step 3 per più precisione; rilevamento mostri migliorato (R>160, diff≥38)
- **Loot aggressivo**: raccolta anche durante combattimento (1.4s CD), post-kill multi-tap in 4 direzioni, finestra 7s
- **Pozione inline + refill automatico**: priorità massima, nessuna fase separata
- **5 skill tutte attive**: multi-touch quando pronte; skill 1-3 anche durante pattuglia
- Joystick virtuale N→E→S→W; si ferma se c'è bersaglio
- Monitor HP automatico (auto-detection barra top-left)
- Kill counter + timer sessione + overlay draggabile

## Architettura v5
```
doLoop() ogni 320ms (combat) / 550ms (patrol):
  1. Kill counter aggiornato (prevTarget→no target = kill)
  2. Pozione inline se hpLow (tap immediato, no fase separata)
  3. if (inCombat):
       multiTap([attack, target, skill1..5 pronte]) → 1 gesto multi-touch
       +2 attack spam a 75/150ms via postDelayed
       loot anche in combat se visibile (CD 1.4s)
       → schedula prossimo loop a 320ms
  4. else (no target):
       loot aggressivo (tap diretto se visibile, multi-tap intorno player se post-kill)
       skill 1-3 durante patrol
       camera rotation ogni 4 cicli
       joystick patrol N→E→S→W
       → schedula prossimo loop a 550ms
```

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

## Analisi libUE4.so (31/03/2026)

### Findings chiave
- **Emulator detection**: BlueStacks, NoxPlayer, MEmu, vPhone, ChromeOS-ARC → solo dispositivi fisici reali
- **MOB_COLOR confermato**: nomi mob rosso vivace → R>170, G<110, B<110, diff≥45
- **AttackTimeMsec**: 60ms TAP_MS confermato corretto
- **AGGRESSIVE_HP_PCT / AGGRESSIVE_SIGHT**: mob con aggro e raggio visione
- **SKILL_VNUM0-4**: 5 slot abilità confermati (già implementati)
- **dropLocs / dropedAt**: drop con posizione precisa e lifetime → centroide OK
- **Origine Metin2** (turco): yang, buyuAttack, buyuDef, coinBonusYuzde
- **Nessun IP server** trovato nel .so → endpoint nel PAK/OBB

### File generati
- `mobile2_decompiled/ANALISI_LIBRERIA.md` — analisi completa
- `mobile2_decompiled/libUE4_strings.txt` — 821.971 stringhe estratte
- `mobile2_decompiled/xapk_contents/` — tutti gli APK decompressi

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
