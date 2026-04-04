# Movile2 Bot

## Panoramica
App Android (Kotlin) per automatizzare Mobile2 Global (clone Metin2 su Unreal Engine 4).
Usa AccessibilityService per inviare gesture e screenshot pixel-detection per rilevare oggetti a terra.

**Replit**: ospita solo `serve.py` (server informativo su porta 5000).  
**APK**: buildato da GitHub Actions al push su `main`.

---

## Architettura

| File | Ruolo |
|---|---|
| `MainActivity.kt` | Schermata principale: permessi, intervallo pozione, avvia/ferma overlay |
| `BotAccessibilityService.kt` | Logica bot: farm loop, pozioni, abilità, raccolta terra, camminata |
| `OverlayService.kt` | Pannello flottante draggabile con tutti i controlli |
| `BotState.kt` | Singleton condiviso per lo stato runtime |
| `BotLogger.kt` | Logger su file `Android/data/com.movile2.bot/files/bot_log.txt` |

---

## Funzionalità attuali

### Pannello flottante (OverlayService)
- **■ STOP TUTTO** — ferma immediatamente tutto (attacco, pozioni, abilità, loot, camminata, pull)
- **🕹️ IMPOSTA JOYSTICK** — cattura posizione centro joystick toccando lo schermo
- **🚶 WALK ON/OFF** — il bot spinge il joystick in avanti continuamente (solo se attack è OFF)
- **🎯 IMPOSTA POZ** — cattura slot pozione (fino a 3)
- **💊 POZ ON/OFF** — preme le pozioni all'intervallo configurato
- **🎒 LOOT ON/OFF** — raccoglie yang (OCR ML Kit) e oggetti con tag personaggio
- **🎯 IMPOSTA ATT** — cattura posizione bottone attacco
- **⚔️ ATT ON/OFF** — farm loop: cammina verso i mob E attacca (integrato)
- **🎯 IMPOSTA SKILL** — cattura fino a 5 slot abilità
- **🎯 N MOB** — tocca per incrementare il target di raggruppamento (1→2→3→4→5→1)
- **🔵 PULL ON/OFF** — modalità raggruppamento: cammina attraendo mob, poi si ferma e usa skill
- **✨ SKILL ON/OFF** — abilità con cooldown individuali

### Farm Loop (Attack ON) — v15
Il `farmLoop` è il cuore del bot. Ogni ~380ms:
1. Legge `detectedMobCount` e `mobDirX/Y` aggiornati dal `mobScanner` (ogni 500ms)
2. Decide se camminare:
   - **Pull mode + abbastanza mob** (`count >= pullTargetCount`): sta fermo e attacca (pull-hold)
   - **Altrimenti**: spinge il joystick verso i mob (o avanti se nessun mob) per 110ms
3. Dopo 140ms: tappa il tasto attacco (40ms)
4. Pianifica il ciclo successivo a 380ms dall'inizio

**Comportamento per situazione:**
- Mob visibili → personaggio si dirige verso di loro E attacca
- Nessun mob → personaggio cammina avanti esplorando E attacca
- Pull mode, N mob raggruppati → si ferma, attacca, le skill scattano

### Camminata standalone (Walk ON, senza Attack)
- Gesture da **150ms** (non più 400ms) con guard flag `walkPending`
- **FIX freeze v15**: 400ms continui saturavano la coda input su MIUI/OneUI → telefono bloccato che richiedeva riavvio. 150ms + flag anti-doppio-dispatch risolve il problema.
- Pausa automatica quando l'utente usa il joystick manualmente
- Riprende insieme alle altre funzioni attive dopo 1.5s

### Mob Scanner
- Screenshot ogni 500ms (solo quando attack o pull sono attivi)
- Conta cluster di pixel rossi (R>160, G<115, B<115, R-G>60) = nomi mob nemici
- Calcola centroide → vettore direzione normalizzato `mobDirX/Y`
- Aggiorna `BotState.detectedMobCount` e `BotState.mobDirX/Y` (usati dal farmLoop)

### Raccolta terra (Loot)
- OCR con ML Kit Text Recognition (on-device, offline)
- Cerca "yang" (case-insensitive) e nomi personaggio nel testo riconosciuto
- Pausa loot 3s dopo ogni uso manuale del joystick
- Coesiste con attacco, pozioni, abilità

### Pull Mode
- Quando `detectedMobCount >= pullTargetCount`: farmLoop sospende il movimento (pull-hold)
- Le skill si attivano solo in pull-hold (check in `skillLoops`)
- Quando i mob muoiono e il count scende: riprende a camminare e raccogliere mob

### Pausa joystick manuale
- Overlay con `FLAG_WATCH_OUTSIDE_TOUCH` riceve eventi `ACTION_OUTSIDE`
- Se il tocco è entro 140dp dal centro joystick → `joystickActive = true` → tutto si ferma
- Dopo 1.5s → `resumeAfterJoystick()` → riprende farmLoop, pozioni, abilità, loot, walk

---

## BotState — campi principali

```kotlin
attackRunning / attackPos                // Modalità farm
mobNearby / detectedMobCount            // Risultato mob scanner
mobDirX / mobDirY                       // Direzione verso i mob (normalizzata)
potionRunning / potionIntervalMs / potionSlots
skillsRunning / skillSlots / skillIntervals
lootRunning / lootItemsFound            // Raccolta terra
walkRunning / joystickPos / joystickRadius  // Camminata standalone
joystickActive                          // Pausa joystick manuale
pullMode / pullTargetCount              // Pull mode config
```

---

## Replit Setup
- Workflow: **Start application** → `python serve.py` (porta 5000)

## Build APK
Push su `main` → GitHub Actions (`build-apk.yml`) → `gradle assembleDebug`  
APK scaricabile da **Actions › Artifacts**

---

## Changelog

### v15 (attuale)
- **FIX CRITICO — Walk freeze**: gesture da 400ms → 150ms + guard flag `walkPending`. Risolve il blocco completo del telefono che richiedeva riavvio su MIUI/OneUI.
- **REDESIGN Attack → farmLoop**: `attackLoop` (tap fisso) sostituito da `farmLoop` integrato. Ogni ciclo: push joystick verso mob (110ms) → tap attacco (40ms). Il personaggio ora si muove attivamente verso i nemici.
- **Pull mode logica**: integrata nel farmLoop. `pullHold` = abbastanza mob → niente camminata → attacca. Mob diminuiscono → riprende a muoversi.
- **Mob scanner**: aggiorna `mobDirX/Y` sempre (non solo in pull mode). Il farmLoop usa la direzione per orientare il personaggio.

### v14
- **BotLogger**: log su file `Android/data/com.movile2.bot/files/bot_log.txt` accessibile senza ADB
- **loot/joystick interference**: `lastManualJoystickMs` + `LOOT_JOY_PAUSE_MS=3000ms` — loot pausa 3s dopo uso manuale joystick

### v13
- **OCR con Google ML Kit**: sostituisce il pixel-scan colore. `findLootItemsFromText()` cerca "yang" e nomi personaggio nel testo OCR.
- **Dipendenza**: `com.google.mlkit:text-recognition:16.0.0` in `app/build.gradle.kts`

### v12
- **BUG CRITICO — joystickActive permanente**: `return` dentro `.let{}`/`.forEach{}` = local return in Kotlin. Fix: `.any{}` + controlli espliciti.

### v11
- **Fix attacco con pozze**: dopo ogni tap pozione, il loop attacco riparte dopo 50ms.
- **Loot + attacco coesistono**: `startLoot()` non chiama più `stopAttack()`.

---

## Note gioco target (Mobile2 Global 2.23)
- Engine: **Unreal Engine 4** — UI renderizzata su SurfaceView OpenGL/Vulkan
- `AccessibilityService.takeScreenshot()` è l'unico modo per "vedere" il gioco
- I gesti Accessibility (`MotionEvent`) arrivano correttamente alla SurfaceView UE4
- Se il gioco attiva `FLAG_SECURE` → screenshot nero
- Mob nemici: nomi ROSSI (R>160, G<115, B<115, R-G>60) secondo `UmobNamer.medusman=true`
