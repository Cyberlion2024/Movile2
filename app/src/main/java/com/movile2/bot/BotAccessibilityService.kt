package com.movile2.bot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class BotAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    // ML Kit Text Recognizer — riconosce testo sullo schermo per trovare "Yang"
    // e i tag dei personaggi vicino agli item. Singleton per l'intera sessione.
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    @Volatile private var lootTargets: List<Pair<Float, Float>> = emptyList()
    private var gestureInProgress = false

    private val ATTACK_TAP_MS      = 40L
    private val POTION_TAP_MS      = 35L
    private val SKILL_TAP_MS       = 40L
    // Dopo ogni pozione, ripristina immediatamente il loop di attacco
    // per colmare il gap causato dal gesto accessibility che interrompe il tap corrente.
    private val ATTACK_RESTART_AFTER_POTION_MS = 50L

    // ═══════════════════════════════════════════════════════════════════════════
    // v18 - FARM LOOP CALLBACK-DRIVEN
    //
    // PROBLEMA v17: il loop usava un timer fisso a 400ms come driver principale.
    // Swipe da 350ms ogni 400ms = 87.5% duty cycle → ancora 50ms di pausa.
    //
    // SOLUZIONE v18:
    // 1. Il prossimo ciclo parte dal CALLBACK del swipe, non da un timer fisso.
    //    → swipe 380ms: dopo il completamento, pausa di soli 20ms (no mob)
    //      oppure 55ms (mob: 40ms attack tap + 15ms buffer). Duty cycle ~93-95%.
    // 2. Un safety runnable a 600ms copre il caso rarissimo in cui Android
    //    non invoca il callback (gesture dropped sotto carico di sistema).
    // 3. L'attacco scatta SOLO se hasMobs=true: niente tap "a vuoto" in SEARCH.
    // 4. PULL_HOLD: attacco rapido ogni 200ms, anch'esso con check hasMobs.
    // ═══════════════════════════════════════════════════════════════════════════

    // Safety net: se il callback del swipe non arriva, riavvia entro 600ms.
    // Dichiarato come lateinit per evitare il forward reference circolare con farmLoop:
    // farmLoopSafety referenzia farmLoop e farmLoop referenzia farmLoopSafety.
    // L'init{} block inizializza farmLoopSafety dopo che farmLoop è già definito.
    private lateinit var farmLoopSafety: Runnable

    private val farmLoop: Runnable = object : Runnable {
        override fun run() {
            if (!BotState.attackRunning) return
            if (BotState.joystickActive) { handler.postDelayed(this, 200L); return }
            // Pausa umana: il bot si "ferma" periodicamente come un vero giocatore
            if (isHumanBreak()) { handler.postDelayed(this, 400L); return }

            val state  = BotState.lastGameState
            val action = AIPlayerEngine.decide(state)
            val joyPos = BotState.joystickPos
            val atkPos = BotState.attackPos

            val hpLog = if (state.hpPercent >= GameStateAnalyzer.HP_NOT_DETECTED) "N/A"
                        else "${"%.0f".format(state.hpPercent * 100)}%"
            BotLogger.d("BotAtk", "[${AIPlayerEngine.currentState}] ${action::class.simpleName}" +
                " hp=$hpLog mobs=${state.mobCount} melee=${state.nearestMobInMeleeRange}")

            // Nota: `val self = this` cattura il Runnable esterno prima di entrare
            // nei lambda, dove `this` non punta più all'oggetto esterno.
            val self = this

            when (action) {
                // MOVE: cammina nella direzione indicata dall'AI (SEARCH/APPROACH/PULL_GATHER)
                is AIPlayerEngine.AIAction.Move -> {
                    if (joyPos == null) { handler.postDelayed(self, 300L); return }
                    handler.removeCallbacks(farmLoopSafety)
                    doJoystickSwipe(joyPos, action.dirX, action.dirY, action.durationMs) {
                        handler.removeCallbacks(farmLoopSafety)
                        if (!BotState.attackRunning || BotState.joystickActive) return@doJoystickSwipe
                        // Dopo ogni movimento: attacca se ci sono mob, poi riprendi
                        if (state.mobCount > 0 && atkPos != null) {
                            tapAttack()
                            handler.postDelayed(self, 55L)
                        } else {
                            handler.postDelayed(self, 20L)
                        }
                    }
                    // Safety: se il callback non arriva, riprendi entro 600ms
                    handler.postDelayed(farmLoopSafety, 600L)
                }

                // ATTACK: mob in melee → tappa attacco (ATTACK / PULL_HOLD)
                // Jitter 180-230ms: evita pattern regolare rilevabile dall'anti-cheat
                AIPlayerEngine.AIAction.Attack -> {
                    if (atkPos != null) tapAttack()
                    handler.postDelayed(self, 180L + (Math.random() * 50).toLong())
                }

                // USE_SKILL / USE_POTION: già gestiti da skillLoops e potionLoop
                // indipendentemente. farmLoop NON duplica queste azioni per evitare
                // double-tap e per mantenere il ritmo di movimento fluido.
                // Fallback: se l'AI lo chiede ma siamo nel ciclo di attacco,
                // comportarsi come Attack (se mob in range) o Wait.
                is AIPlayerEngine.AIAction.UseSkill,
                AIPlayerEngine.AIAction.UsePotion -> {
                    if (atkPos != null && state.mobCount > 0) tapAttack()
                    handler.postDelayed(self, 200L)
                }

                // WAIT: nessuna azione utile in questo ciclo
                is AIPlayerEngine.AIAction.Wait -> {
                    handler.postDelayed(self, action.ms)
                }
            }
        }
    }
    
    init {
        farmLoopSafety = Runnable {
            if (BotState.attackRunning && !BotState.joystickActive) {
                handler.removeCallbacks(farmLoop)
                handler.post(farmLoop)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HUMANIZER — simulazione comportamento umano per evitare anti-cheat
    //
    // L'anti-cheat rileva bot tramite:
    //   1. Timing perfettamente regolare (meccanico vs umano)
    //   2. Traiettorie rette (il pollice umano descrive archi, non linee)
    //   3. Posizioni identiche ripetute all'infinito (±0px)
    //   4. Nessuna pausa naturale (un umano si distrae, guarda lo schermo ecc.)
    //   5. Direzione di movimento sempre esatta (0°, 60°, 120° ecc.)
    //
    // Contromisure implementate:
    //   A. doJoystickSwipe: traiettoria quadratica (bezier) + jitter direzione ±6°
    //   B. tapAttack/humanTap: jitter posizione ±4px + durata ±15%
    //   C. humanBreakLoop: pausa 3-6s ogni 65-130s
    //   D. Tutti i timer: jitter già presente nei loop
    // ═══════════════════════════════════════════════════════════════════════════
    @Volatile private var humanBreakUntil = 0L

    private val humanBreakLoop = object : Runnable {
        override fun run() {
            if (!BotState.attackRunning && !BotState.skillsRunning) return
            // Pausa 3-6s: simula distrazione umana (guarda messaggio, aggiusta posizione ecc.)
            val breakMs = 3_000L + (Math.random() * 3_000).toLong()
            humanBreakUntil = System.currentTimeMillis() + breakMs
            BotLogger.d("BotHuman", "Pausa umana: ${breakMs}ms")
            // Prossima pausa tra 65-130s
            val nextMs = 65_000L + (Math.random() * 65_000).toLong()
            handler.postDelayed(this, nextMs + breakMs)
        }
    }

    private fun isHumanBreak() = System.currentTimeMillis() < humanBreakUntil

    /** Tap con jitter posizione ±4px e durata ±15% */
    private fun humanTap(x: Float, y: Float, baseDurationMs: Long) {
        val jx = ((Math.random() * 8) - 4).toFloat()
        val jy = ((Math.random() * 8) - 4).toFloat()
        val dur = (baseDurationMs * (0.87 + Math.random() * 0.26)).toLong().coerceAtLeast(20L)
        tapSingle(x + jx, y + jy, dur)
    }

    private fun doJoystickSwipe(pos: Pair<Float, Float>, dirX: Float, dirY: Float,
                                 durationMs: Long, onComplete: () -> Unit) {
        val r = if (BotState.joystickRadius > 0f) BotState.joystickRadius * 0.75f
                else resources.displayMetrics.widthPixels * 0.09f

        // Jitter direzione ±6°: il pollice umano non è mai perfettamente preciso
        val baseAngle = Math.atan2(dirY.toDouble(), dirX.toDouble())
        val angleJitter = Math.toRadians((Math.random() * 12.0) - 6.0)
        val finalAngle = baseAngle + angleJitter
        val jDirX = Math.cos(finalAngle).toFloat()
        val jDirY = Math.sin(finalAngle).toFloat()

        // Jitter posizione di partenza ±3px
        val sJx = ((Math.random() * 6) - 3).toFloat()
        val sJy = ((Math.random() * 6) - 3).toFloat()

        val startX = pos.first  + sJx
        val startY = pos.second + sJy
        val endX   = pos.first  + jDirX * r + ((Math.random() * 8) - 4).toFloat()
        val endY   = pos.second + jDirY * r + ((Math.random() * 8) - 4).toFloat()

        // Punto di controllo bezier: perpendicolare alla direzione ±8px
        // Riproduce l'arco naturale del pollice che scivola sul vetro
        val midX = (startX + endX) / 2f + ((Math.random() * 16) - 8).toFloat()
        val midY = (startY + endY) / 2f + ((Math.random() * 16) - 8).toFloat()

        // Durata ±15%
        val dur = (durationMs * (0.87 + Math.random() * 0.26)).toLong().coerceAtLeast(100L)

        try {
            dispatchGesture(
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(
                        Path().apply {
                            moveTo(startX, startY)
                            quadTo(midX, midY, endX, endY)   // curva bezier, non linea retta
                        }, 0L, dur))
                    .build(),
                object : GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) { onComplete() }
                    override fun onCancelled(g: GestureDescription?) { onComplete() }
                },
                handler
            )
        } catch (_: Exception) {
            onComplete()
        }
    }

    private fun tapAttack() {
        val pos = BotState.attackPos ?: return
        // Jitter ±4px + durata ±15%: ogni tap sembra leggermente diverso
        val jx  = ((Math.random() * 8) - 4).toFloat()
        val jy  = ((Math.random() * 8) - 4).toFloat()
        val dur = (ATTACK_TAP_MS * (0.87 + Math.random() * 0.26)).toLong().coerceAtLeast(20L)
        try {
            dispatchGesture(
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(
                        Path().apply { moveTo(pos.first + jx, pos.second + jy) }, 0L, dur))
                    .build(), null, null)
        } catch (_: Exception) {}
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GAME STATE SCANNER — ogni 200ms produce un GameState completo tramite
    // GameStateAnalyzer: HP barra, mob (posizione + rank), melee range, skill cooldown.
    //
    // Sostituisce il vecchio mobScanner (500ms, solo pixel scan rossi) con
    // un'analisi visiva completa usata da AIPlayerEngine per tutte le decisioni.
    // I campi legacy (detectedMobCount, mobNearby, mobDirX/Y) vengono aggiornati
    // per retrocompatibilità con OverlayService ticker e lootScanner.
    // ═══════════════════════════════════════════════════════════════════════════
    private val gameStateScanner = object : Runnable {
        override fun run() {
            if (!BotState.attackRunning && !BotState.pullMode && !BotState.walkRunning) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !BotState.joystickActive) {
                doGameStateScan()
            }
            // 430-480ms: più lento del 200ms originale, riduce carico bitmap e CPU
            // Il jitter ±25ms rende il pattern meno prevedibile per l'anti-cheat
            handler.postDelayed(this, 430L + (Math.random() * 50).toLong())
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun doGameStateScan() {
        takeScreenshot(Display.DEFAULT_DISPLAY, ContextCompat.getMainExecutor(this),
            object : TakeScreenshotCallback {
                override fun onSuccess(s: ScreenshotResult) {
                    val hw  = Bitmap.wrapHardwareBuffer(s.hardwareBuffer, s.colorSpace)
                    val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    hw?.recycle(); s.hardwareBuffer.close()
                    bmp?.let { b ->
                        val state = GameStateAnalyzer.analyze(b, BotState.skillSlots)
                        BotState.lastGameState  = state
                        // Mantieni campi legacy in sync per OverlayService e lootScanner
                        BotState.detectedMobCount = state.mobCount
                        BotState.mobNearby        = state.mobCount > 0
                        BotState.mobDirX          = state.nearestMobDir.first
                        BotState.mobDirY          = state.nearestMobDir.second
                        val hpStr = if (state.hpPercent >= GameStateAnalyzer.HP_NOT_DETECTED)
                            "N/A" else "${"%.0f".format(state.hpPercent * 100)}%"
                        BotLogger.d("BotScan", "hp=$hpStr mobs=${state.mobCount}" +
                            " melee=${state.nearestMobInMeleeRange}" +
                            " skills=${state.skillsReady.count { it }}/${state.skillsReady.size} ready")
                        b.recycle()
                    }
                }
                override fun onFailure(e: Int) {
                    BotLogger.w("BotScan", "Screenshot fallito: $e")
                    BotState.mobNearby        = false
                    BotState.detectedMobCount = 0
                }
            })
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LOOP POZIONE — timer primario + early trigger HP vision
    //
    // Strategia ibrida per massima affidabilità:
    //
    //   PRIMARIO (timer): scatta ogni potionIntervalMs esattamente come il
    //     sistema precedente. Funziona anche se la vision non rileva HP.
    //
    //   EARLY TRIGGER (vision): se hpPercent < autoPotionHpThreshold E la
    //     barra è stata effettivamente rilevata (≠ HP_NOT_DETECTED), scatta
    //     subito senza aspettare il timer.
    //
    // Questo garantisce che le pozioni vengano sempre usate indipendentemente
    // dal successo della vision, eliminando la regressione introdotta dal
    // sistema puramente HP-driven.
    // ═══════════════════════════════════════════════════════════════════════════
    private val potionLoop = object : Runnable {
        // Timestamp del prossimo uso programmato (timer fallback)
        private var nextUseAt = 0L

        override fun run() {
            if (!BotState.potionRunning) return
            if (BotState.joystickActive || isHumanBreak()) {
                handler.postDelayed(this, 800L + (Math.random() * 200).toLong())
                return
            }

            val now   = System.currentTimeMillis()
            val hp    = BotState.lastGameState.hpPercent
            val interval = BotState.potionIntervalMs.coerceAtLeast(500L)

            // HP rilevata (non sentinella) e sotto soglia → early trigger
            val hpLow       = hp < GameStateAnalyzer.HP_NOT_DETECTED &&
                              hp < BotState.autoPotionHpThreshold
            // Timer scaduto → fallback (come il vecchio sistema)
            val timerExpired = now >= nextUseAt

            if (hpLow || timerExpired) {
                val slots = BotState.potionSlots
                var delay = 0L
                for ((px, py) in slots) {
                    handler.postDelayed({
                        if (!BotState.potionRunning || BotState.joystickActive) return@postDelayed
                        humanTap(px, py, POTION_TAP_MS)   // jitter ±4px
                        if (BotState.attackRunning) {
                            handler.removeCallbacks(farmLoop)
                            handler.removeCallbacks(farmLoopSafety)
                            handler.postDelayed(farmLoop, ATTACK_RESTART_AFTER_POTION_MS)
                        }
                    }, delay)
                    delay += 350L
                }
                nextUseAt = now + interval + delay
            }

            // Poll ogni 800-1000ms con jitter: bilanciamento tra reattività HP e carico CPU
            handler.postDelayed(this, 800L + (Math.random() * 200).toLong())
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LOOP ABILITÀ — 5 timer vision-driven con fallback a timer.
    //
    // Strategia ibrida (vision primary + timer fallback):
    //   - Se state.skillsReady[idx] = true E timer > metà intervallo → scatta subito
    //   - Se timer >= intervallo completo → scatta comunque (fallback)
    //
    // Poll quando non pronta: 900-1100ms con jitter (era 500ms, troppo aggressivo).
    // Il jitter ±100ms rende il pattern meno rilevabile.
    //
    // IMPORTANTE: le skill vengono sparate SOLO da questo loop, NON da farmLoop,
    // per evitare double-tap e sovraccarico di gesture sull'accessibilità.
    // ═══════════════════════════════════════════════════════════════════════════
    private val skillLoops: Array<Runnable> = Array(5) { idx ->
        object : Runnable {
            var lastFiredAt = 0L
            override fun run() {
                if (!BotState.skillsRunning) return
                val slots    = BotState.skillSlots
                val interval = BotState.skillIntervals.getOrElse(idx) { 5000L }.coerceAtLeast(1000L)

                if (idx >= slots.size) {
                    handler.postDelayed(this, interval + (Math.random() * 200).toLong())
                    return
                }
                if (BotState.joystickActive || isHumanBreak()) {
                    handler.postDelayed(this, 600L + (Math.random() * 300).toLong())
                    return
                }

                val state = BotState.lastGameState
                val now   = System.currentTimeMillis()
                val elapsed = now - lastFiredAt

                // Vision dice pronta E almeno metà del cooldown è passato → early fire
                val visionReady  = elapsed > interval / 2 &&
                                   idx < state.skillsReady.size && state.skillsReady[idx]
                // Timer pieno scaduto → fallback garantito
                val timerElapsed = elapsed >= interval

                val canFire = (visionReady || timerElapsed) && when {
                    BotState.pullMode -> state.mobCount >= BotState.pullTargetCount
                    else              -> true
                }

                if (canFire) {
                    val (sx, sy) = slots[idx]
                    humanTap(sx, sy, SKILL_TAP_MS)   // jitter ±4px + durata ±15%
                    lastFiredAt = now
                    // Aspetta il prossimo intervallo completo + piccolo jitter
                    handler.postDelayed(this, interval + (Math.random() * 150).toLong())
                } else {
                    // Non pronta: controlla in ~1s con jitter ±100ms
                    handler.postDelayed(this, 900L + (Math.random() * 200).toLong())
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LOOP RACCOLTA
    // ═══════════════════════════════════════════════════════════════════════════
    @Volatile private var scanCount = 0

    private val lootLoop = object : Runnable {
        override fun run() {
            if (!BotState.lootRunning) return
            // Blocca loot se joystick attivo OPPURE se l'utente ha toccato il joystick
            // negli ultimi LOOT_JOY_PAUSE_MS ms (copre la camminata continua dove
            // ACTION_OUTSIDE arriva solo al primo DOWN, non durante il drag).
            if (joystickRecentlyUsed()) { handler.postDelayed(this, 400L); return }
            if (scanCount < 2) { handler.postDelayed(this, 300L); return }
            val items = lootTargets
            BotState.lootItemsFound = items.size
            val toTap = items.take(4)
            if (toTap.isEmpty()) { handler.postDelayed(this, 500L); return }
            BotLogger.d("BotLoot", "Tappo ${toTap.size} item")
            tapItemsSequentially(toTap, 0) {
                if (BotState.lootRunning) handler.postDelayed(this, 600L)
            }
        }
    }

    private fun joystickRecentlyUsed(): Boolean =
        BotState.joystickActive ||
        System.currentTimeMillis() - BotState.lastManualJoystickMs < BotState.LOOT_JOY_PAUSE_MS

    private fun tapItemsSequentially(items: List<Pair<Float, Float>>, index: Int, onAllDone: () -> Unit) {
        if (!BotState.lootRunning || joystickRecentlyUsed() || index >= items.size) { onAllDone(); return }
        val (x, y) = items[index]
        gestureInProgress = true
        val dispatched = tapSingle(x, y, 55L)
        gestureInProgress = false
        handler.postDelayed({ tapItemsSequentially(items, index + 1, onAllDone) }, if (dispatched) 220L else 260L)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SCANNER LOOT — ogni 600ms
    // ═══════════════════════════════════════════════════════════════════════════
    private val lootScanner = object : Runnable {
        override fun run() {
            if (!BotState.lootRunning) return
            if (!BotState.joystickActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) doLootScan()
            handler.postDelayed(this, 600L)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun doLootScan() {
        takeScreenshot(Display.DEFAULT_DISPLAY, ContextCompat.getMainExecutor(this),
            object : TakeScreenshotCallback {
                override fun onSuccess(s: ScreenshotResult) {
                    val hw = Bitmap.wrapHardwareBuffer(s.hardwareBuffer, s.colorSpace)
                    val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    hw?.recycle(); s.hardwareBuffer.close()
                    if (bmp == null) return

                    // ML Kit OCR asincrono: processa il bitmap e cerca il testo
                    val image = InputImage.fromBitmap(bmp, 0)
                    textRecognizer.process(image)
                        .addOnSuccessListener { visionText ->
                            lootTargets = findLootItemsFromText(visionText, bmp.width, bmp.height)
                            bmp.recycle()
                            scanCount++
                        }
                        .addOnFailureListener { e ->
                            Log.w("BotLoot", "ML Kit fallito: ${e.message}")
                            bmp.recycle()
                        }
                }
                override fun onFailure(e: Int) {
                    Log.w("BotLoot", "Screenshot fallito: $e")
                }
            })
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RILEVAMENTO OGGETTI A TERRA — v13: ML Kit Text Recognition
    //
    // ML Kit riconosce il testo sullo screenshot e restituisce ogni blocco
    // con la sua bounding box. Cerchiamo:
    //   • "yang" (qualsiasi case) → moneta Yang
    //   • nomi personaggio configurati (bashy, Anyasama) → item del nostro char
    //
    // Vantaggi rispetto al pixel-scan:
    //   • Funziona a qualsiasi colore, luminosità o sfondo
    //   • Nessuna calibrazione — testo = testo
    //   • Posizione precisa: center della bounding box della parola
    //
    // Filtri applicati dopo il riconoscimento:
    //   • Zona verticale: 35-88% altezza (esclude HUD in alto, barra in basso)
    //   • Distanza dal personaggio: max 30% larghezza schermo
    //   • Max 8 target per ciclo (ordinati dal più vicino)
    // ═══════════════════════════════════════════════════════════════════════════
    private fun findLootItemsFromText(visionText: Text, w: Int, h: Int): List<Pair<Float, Float>> {
        val charX = w * 0.50f
        val charY = h * 0.60f
        val maxDist = w * 0.30f
        val maxDistSq = maxDist * maxDist
        val yMin = h * 0.35f
        val yMax = h * 0.88f

        val names = BotState.characterNames.map { it.lowercase() }
        val found = mutableListOf<Pair<Float, Float>>()

        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                val cx = box.exactCenterX()
                val cy = box.exactCenterY()

                // Filtra per zona verticale (esclude HUD)
                if (cy < yMin || cy > yMax) continue

                // Filtra per distanza dal personaggio
                val ddx = cx - charX; val ddy = cy - charY
                if (ddx * ddx + ddy * ddy > maxDistSq) continue

                val text = line.text.lowercase().trim()

                val isYang   = text.contains("yang")
                val isMyItem = names.any { name -> text.contains(name) }

                if (isYang || isMyItem) {
                    val tipo = if (isYang) "Yang" else "Item[${line.text.trim()}]"
                    Log.d("BotLoot", "✔ $tipo @ (${"%.0f".format(cx)},${
                        "%.0f".format(cy)}) → '${line.text.trim()}'")
                    found.add(cx to cy)
                    if (found.size >= 8) break
                }
            }
            if (found.size >= 8) break
        }

        val totalBlocks = visionText.textBlocks.size
        val totalLines  = visionText.textBlocks.sumOf { it.lines.size }
        if (found.isEmpty()) {
            Log.d("BotLoot", "Nessun item — OCR: $totalBlocks blocchi, $totalLines righe")
        } else {
            Log.d("BotLoot", "Trovati ${found.size} item — OCR: $totalBlocks blocchi")
        }

        return found.sortedBy { (fx, fy) ->
            val dx = fx - charX; val dy = fy - charY; dx * dx + dy * dy
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // JOYSTICK
    // ═══════════════════════════════════════════════════════════════════════════
    fun resumeAfterJoystick() {
        BotState.joystickActive = false
        resumeAll()
    }

    private fun resumeAll() {
        if (BotState.attackRunning) {
            handler.removeCallbacks(farmLoop)
            handler.removeCallbacks(farmLoopSafety)
            handler.post(farmLoop)
            handler.removeCallbacks(gameStateScanner); handler.post(gameStateScanner)
        }
        if (BotState.potionRunning) {
            handler.removeCallbacks(potionLoop); handler.post(potionLoop)
        }
        if (BotState.skillsRunning) {
            skillLoops.forEach { handler.removeCallbacks(it) }
            BotState.skillSlots.forEachIndexed { idx, _ ->
                val interval = BotState.skillIntervals.getOrElse(idx) { 5000L }.coerceAtLeast(500L)
                handler.postDelayed(skillLoops[idx], interval)
            }
        }
        if (BotState.lootRunning) {
            handler.removeCallbacks(lootLoop); handler.post(lootLoop)
        }
        if (BotState.walkRunning && !BotState.attackRunning) {
            // Walk standalone (senza attack): usa dispatchWalk
            dispatchWalk()
        }
        if (BotState.pullMode && !BotState.attackRunning) {
            handler.removeCallbacks(gameStateScanner); handler.post(gameStateScanner)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS GESTI
    // ═══════════════════════════════════════════════════════════════════════════
    private fun tapSingle(x: Float, y: Float, durationMs: Long): Boolean {
        return try {
            dispatchGesture(
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(
                        Path().apply { moveTo(x, y) }, 0L, durationMs))
                    .build(), null, null)
        } catch (_: Exception) { false }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CAMMINATA STANDALONE — solo quando Walk è ON senza Attack.
    //
    // FIX freeze v14: il vecchio sistema usava gesture da 400ms con callback
    // immediato. Su molti dispositivi (MIUI, OneUI) questo loop ininterrotto di
    // gesture lunghe satura la coda input del sistema → freeze che richiede
    // riavvio del telefono.
    //
    // Soluzione: gesture più brevi (150ms) + guard flag `walkPending` per
    // impedire doppio dispatch (callback + timer simultanei). Il personaggio
    // si muove identicamente — la frequenza compensa la durata minore.
    // ═══════════════════════════════════════════════════════════════════════════
    @Volatile private var walkPending = false

    private val walkCallback = object : GestureResultCallback() {
        override fun onCompleted(g: GestureDescription?) {
            walkPending = false
            if (BotState.walkRunning) handler.postDelayed({ dispatchWalk() }, 30L)
        }
        override fun onCancelled(g: GestureDescription?) {
            walkPending = false
            if (BotState.walkRunning) handler.postDelayed({ dispatchWalk() }, 100L)
        }
    }

    private fun dispatchWalk() {
        if (!BotState.walkRunning || BotState.joystickActive || walkPending) {
            if (BotState.walkRunning && !walkPending && !BotState.joystickActive)
                handler.postDelayed({ dispatchWalk() }, 200L)
            return
        }
        val pos = BotState.joystickPos ?: return
        val r = if (BotState.joystickRadius > 0f) BotState.joystickRadius * 0.65f
                else resources.displayMetrics.widthPixels * 0.07f
        // Walk standalone: sempre in avanti (0, -1). Non usa mobDir — quella è per continuousWalk.
        val endX = pos.first
        val endY = pos.second - r
        walkPending = true
        try {
            val ok = dispatchGesture(
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(
                        Path().apply { moveTo(pos.first, pos.second); lineTo(endX, endY) },
                        0L, 150L))   // 150ms: abbastanza per muovere il personaggio senza freeze
                    .build(), walkCallback, handler)
            if (!ok) {
                walkPending = false
                if (BotState.walkRunning) handler.postDelayed({ dispatchWalk() }, 100L)
            }
        } catch (_: Exception) {
            walkPending = false
            if (BotState.walkRunning) handler.postDelayed({ dispatchWalk() }, 150L)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // API PUBBLICA
    // ═══════════════════════════════════════════════════════════════════════════
    fun startAttack() {
        if (BotState.attackPos == null) return
        if (BotState.attackRunning) stopAttack()
        BotState.attackRunning    = true
        BotState.mobNearby        = false
        BotState.detectedMobCount = 0
        BotState.mobDirX          = 0f; BotState.mobDirY = -1f
        BotState.lastGameState    = GameState.EMPTY
        AIPlayerEngine.reset()
        GameStateAnalyzer.resetCalibration()
        humanBreakUntil = 0L
        // Prima pausa umana tra 80-145s dall'avvio (non subito)
        val firstBreak = 80_000L + (Math.random() * 65_000).toLong()
        handler.removeCallbacks(humanBreakLoop)
        handler.postDelayed(humanBreakLoop, firstBreak)
        handler.post(gameStateScanner)
        handler.postDelayed(farmLoop, 400L + (Math.random() * 200).toLong())
        BotLogger.d("BotAtk", "AI v1 avviato — prima pausa umana tra ${firstBreak/1000}s")
    }

    fun stopAttack() {
        BotState.attackRunning = false
        BotState.mobNearby     = false
        humanBreakUntil = 0L
        handler.removeCallbacks(humanBreakLoop)
        handler.removeCallbacks(farmLoop)
        handler.removeCallbacks(farmLoopSafety)
        if (!BotState.pullMode) {
            handler.removeCallbacks(gameStateScanner)
            BotState.detectedMobCount = 0
        }
        // Se la camminata standalone era attiva, riavviarla
        if (BotState.walkRunning) {
            walkPending = false
            handler.post { dispatchWalk() }
        }
        BotLogger.d("BotAtk", "farmLoop fermato")
    }

    fun startPotion(intervalMs: Long = BotState.potionIntervalMs) {
        if (BotState.potionSlots.isEmpty()) return
        if (BotState.potionRunning) stopPotion()
        BotState.potionIntervalMs = intervalMs.coerceAtLeast(500L)
        BotState.potionRunning = true
        // Prima pozione dopo il primo intervallo (come il vecchio sistema)
        handler.postDelayed(potionLoop, BotState.potionIntervalMs)
    }

    fun stopPotion() {
        BotState.potionRunning = false; handler.removeCallbacks(potionLoop)
    }

    fun startSkills() {
        if (BotState.skillSlots.isEmpty()) return
        if (BotState.skillsRunning) stopSkills()
        BotState.skillsRunning = true
        // Scaglionato: ogni skill parte in un momento diverso (non tutte a 500ms)
        // per evitare raffiche sincronizzate rilevabili dall'anti-cheat.
        BotState.skillSlots.forEachIndexed { idx, _ ->
            val stagger = 600L + (idx * 300L) + (Math.random() * 400).toLong()
            handler.postDelayed(skillLoops[idx], stagger)
        }
    }

    fun stopSkills() {
        BotState.skillsRunning = false
        skillLoops.forEach { handler.removeCallbacks(it) }
    }

    // FIX v11: startLoot NON ferma più l'attacco.
    // Attacco e loot coesistono — il bot attacca E raccoglie contemporaneamente.
    // Questo era il bug principale: prima startLoot() chiamava stopAttack()
    // causando l'interruzione dell'attacco ogni volta che il loot si attivava.
    fun startLoot() {
        if (BotState.lootRunning) return
        lootTargets = emptyList(); BotState.lootItemsFound = 0; gestureInProgress = false
        scanCount = 0
        BotState.lootRunning = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) handler.postDelayed(lootScanner, 200L)
        handler.postDelayed(lootLoop, 600L)
    }

    fun stopLoot() {
        BotState.lootRunning = false; BotState.lootItemsFound = 0; gestureInProgress = false
        handler.removeCallbacks(lootScanner); handler.removeCallbacks(lootLoop)
        lootTargets = emptyList()
    }

    fun startWalk() {
        if (BotState.joystickPos == null || BotState.walkRunning) return
        BotState.walkRunning = true
        dispatchWalk()
    }

    fun stopWalk() {
        BotState.walkRunning = false
        walkPending = false
    }

    fun startPullMode() {
        BotState.pullMode = true
        // gameStateScanner serve anche senza attack per sapere quanti mob ci sono
        if (!BotState.attackRunning) {
            handler.removeCallbacks(gameStateScanner)
            handler.post(gameStateScanner)
        }
        BotLogger.d("BotAtk", "Pull mode ON — target: ${BotState.pullTargetCount} mob")
    }

    fun stopPullMode() {
        BotState.pullMode         = false
        BotState.detectedMobCount = 0
        if (!BotState.attackRunning) handler.removeCallbacks(gameStateScanner)
        BotLogger.d("BotAtk", "Pull mode OFF")
    }

    fun stopAll() {
        stopAttack(); stopPotion(); stopSkills(); stopLoot(); stopWalk()
        stopPullMode()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // AUTO-DETECT JOYSTICK
    // ═══════════════════════════════════════════════════════════════════════════
    fun autoDetectJoystick(onResult: (center: Pair<Float, Float>, radius: Float) -> Unit,
                           onFailed: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) { onFailed(); return }
        takeScreenshot(Display.DEFAULT_DISPLAY, ContextCompat.getMainExecutor(this),
            object : TakeScreenshotCallback {
                @RequiresApi(Build.VERSION_CODES.R)
                override fun onSuccess(s: ScreenshotResult) {
                    val hw  = Bitmap.wrapHardwareBuffer(s.hardwareBuffer, s.colorSpace)
                    val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    hw?.recycle(); s.hardwareBuffer.close()
                    if (bmp == null) { onFailed(); return }
                    val w = bmp.width; val h = bmp.height
                    val x0 = (w * 0.02f).toInt(); val x1 = (w * 0.30f).toInt()
                    val y0 = (h * 0.68f).toInt(); val y1 = (h * 0.97f).toInt()
                    val cell = 50
                    var minBright = Float.MAX_VALUE
                    var bestCx = (x0 + x1) / 2f
                    var bestCy = (y0 + y1) / 2f
                    var cx = x0
                    while (cx + cell <= x1) {
                        var cy = y0
                        while (cy + cell <= y1) {
                            var sum = 0L; var cnt = 0
                            var sx = cx
                            while (sx < cx + cell) {
                                var sy = cy
                                while (sy < cy + cell) {
                                    val p = bmp.getPixel(sx.coerceAtMost(bmp.width - 1),
                                                         sy.coerceAtMost(bmp.height - 1))
                                    sum += Color.red(p) + Color.green(p) + Color.blue(p)
                                    cnt++; sy += 5
                                }
                                sx += 5
                            }
                            val bright = if (cnt > 0) sum.toFloat() / cnt else Float.MAX_VALUE
                            if (bright < minBright) {
                                minBright = bright
                                bestCx = (cx + cell / 2).toFloat()
                                bestCy = (cy + cell / 2).toFloat()
                            }
                            cy += cell
                        }
                        cx += cell
                    }
                    bmp.recycle()
                    val radius = w * 0.09f
                    handler.post { onResult(bestCx to bestCy, radius) }
                }
                override fun onFailure(e: Int) { handler.post { onFailed() } }
            })
    }

    override fun onServiceConnected() {
        instance = this
        BotLogger.init(this)
        BotLogger.d("Bot", "AccessibilityService connesso — log su file attivo")
    }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() { stopAll() }
    override fun onDestroy() {
        super.onDestroy()
        stopAll()
        textRecognizer.close()
        BotState.joystickActive = false
        if (instance === this) instance = null
    }

    companion object {
        var instance: BotAccessibilityService? = null
            private set
    }
}
