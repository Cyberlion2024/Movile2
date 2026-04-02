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
| `BotAccessibilityService.kt` | Logica bot: attacco, pozioni, abilità, raccolta terra, camminata |
| `OverlayService.kt` | Pannello flottante draggabile con tutti i controlli |
| `BotState.kt` | Singleton condiviso per lo stato runtime |

---

## Funzionalità attuali

### Pannello flottante (OverlayService)
- **■ STOP TUTTO** — ferma immediatamente tutto (attacco, pozioni, abilità, loot, camminata)
- **🕹️ IMPOSTA JOYSTICK** — cattura posizione centro joystick toccando lo schermo
- **🚶 WALK ON/OFF** — il bot spinge il joystick in avanti continuamente (400ms gestures a catena)
- **🎯 IMPOSTA POZ** — cattura slot pozione (fino a 3)
- **💊 POZ ON/OFF** — preme le pozioni all'intervallo configurato
- **🎒 LOOT ON/OFF** — raccoglie yang (oro) e oggetti con nome verde; ferma l'attacco automaticamente
- **⚔️ ATT ON/OFF** — attacco automatico (solo se mob rosso rilevato)
- **✨ SKILL ON/OFF** — abilità con cooldown individuali

### Camminata (Walk)
- Spinge il joystick di `widthPixels × 0.07` px verso l'alto (avanti)  
- Gesture da 400ms concatenate via `GestureResultCallback` → movimento continuo senza gap  
- Pausa automatica quando l'utente usa il joystick manualmente (rilevato via `FLAG_WATCH_OUTSIDE_TOUCH`)  
- Riprende insieme alle altre funzioni attive dopo `JOY_RESUME_DELAY_MS` (1.5s)

### Raccolta terra (Loot)
- **Attiva**: ferma sempre l'attacco (`stopAttack()` in `startLoot()`)  
- **Funziona con**: pozioni (indipendenti), abilità, camminata  
- **Rileva**:  
  - Yang: pixel giallo-oro (R>220, G>170, B<60)  
  - Oggetti col nome del personaggio: testo verde (G>170, R<120, B<100)  
  - Testo bianco brillante generico (R>220, G>220, B>220)  
- **Tap**: singolo (no multitouch con attacco), doppio su ogni oggetto con delay

### Pausa joystick manuale
- Overlay con `FLAG_WATCH_OUTSIDE_TOUCH` riceve eventi `ACTION_OUTSIDE`  
- Se il tocco è entro `140dp` dal centro joystick → `joystickActive = true` → tutte le azioni si fermano  
- Dopo 1.5s senza tocchi → `resumeAfterJoystick()` → riprendono attacco, pozioni, abilità, loot, camminata

---

## BotState — campi principali

```kotlin
attackRunning / attackPos / mobNearby   // Modalità attacco
potionRunning / potionIntervalMs / potionSlots
skillsRunning / skillSlots / skillIntervals
lootRunning / lootItemsFound            // Raccolta terra
walkRunning / joystickPos               // Camminata bot
joystickActive                          // Pausa joystick manuale
```

---

## Replit Setup
- Workflow: **Start application** → `python serve.py` (porta 5000)

## Build APK
Push su `main` → GitHub Actions (`build-apk.yml`) → `gradle assembleDebug`  
APK scaricabile da **Actions › Artifacts**

---

## Note gioco target (Mobile2 Global 2.23)
- Engine: **Unreal Engine 4** — UI renderizzata su SurfaceView OpenGL/Vulkan
- `AccessibilityService.takeScreenshot()` è l'unico modo per "vedere" il gioco
- I gesti Accessibility (`MotionEvent`) arrivano correttamente alla SurfaceView UE4
- Se il gioco attiva `FLAG_SECURE` → screenshot nero
