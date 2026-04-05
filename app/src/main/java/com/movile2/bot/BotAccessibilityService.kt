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
            if (BotState.joystickActive) {
                handler.postDelayed(this, 200L)
                return
            }

            val state  = BotState.lastGameState
            val action = AIPlayerEngine.decide(state)
            val joyPos = BotState.joystickPos
            val atkPos = BotState.attackPos

            BotLogger.d("BotAtk", "[${AIPlayerEngine.currentState}] ${action::class.simpleName}" +
                " hp=${"%.0f".format(state.hpPercent * 100)}% mobs=${state.mobCount}" +
                " melee=${state.nearestMobInMeleeRange}")

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
                AIPlayerEngine.AIAction.Attack -> {
                    if (atkPos != null) tapAttack()
                    handler.postDelayed(self, 200L)
                }

                // USE_SKILL: AI ha rilevato skill pronta e mob presenti
                is AIPlayerEngine.AIAction.UseSkill -> {
                    val slots = BotState.skillSlots
                    if (action.slot < slots.size) {
                        val (sx, sy) = slots[action.slot]
                        tapSingle(sx, sy, SKILL_TAP_MS)
                    }
                    handler.postDelayed(self, 100L)
                }

                // USE_POTION: HP sotto soglia — scatta potionLoop subito
                AIPlayerEngine.AIAction.UsePotion -> {
                    if (BotState.potionRunning) {
                        handler.removeCallbacks(potionLoop)
                        handler.post(potionLoop)
                    }
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

    private fun doJoystickSwipe(pos: Pair<Float, Float>, dirX: Float, dirY: Float,
                                 durationMs: Long, onComplete: () -> Unit) {
        val r = if (BotState.joystickRadius > 0f) BotState.joystickRadius * 0.75f
                else resources.displayMetrics.widthPixels * 0.09f
        
        val endX = pos.first + dirX * r
        val endY = pos.second + dirY * r
        
        try {
            dispatchGesture(
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(
                        Path().apply {
                            moveTo(pos.first, pos.second)
                            lineTo(endX, endY)
                        }, 0L, durationMs))
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
        try {
            dispatchGesture(
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(
                        Path().apply { moveTo(pos.first, pos.second) }, 0L, ATTACK_TAP_MS))
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
            handler.postDelayed(this, 200L)
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
            if (BotState.joystickActive) { handler.postDelayed(this, 500L); return }

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
                        tapSingle(px, py, POTION_TAP_MS)
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

            // Poll ogni 500ms: risponde rapidamente sia a HP bassa che al timer
            handler.postDelayed(this, 500L)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LOOP ABILITÀ — 5 timer vision-driven con fallback a timer.
    //
    // Strategia ibrida:
    //   - Primario: controlla state.skillsReady[idx] (analisi visiva cooldown)
    //     ogni 500ms. Se ready → scatta subito.
    //   - Fallback: se il tempo trascorso dall'ultimo sparo supera l'intervallo
    //     configurato, scatta comunque (copre eventuali falsi negativi vision).
    //
    // Con PULL MODE: scatta solo se mobCount >= pullTargetCount.
    // Joystick attivo: riprova dopo 300ms senza perdere il ciclo.
    // ═══════════════════════════════════════════════════════════════════════════
    private val skillLoops: Array<Runnable> = Array(5) { idx ->
        object : Runnable {
            var lastFiredAt = 0L
            override fun run() {
                if (!BotState.skillsRunning) return
                val slots    = BotState.skillSlots
                val interval = BotState.skillIntervals.getOrElse(idx) { 5000L }.coerceAtLeast(500L)

                if (idx >= slots.size) { handler.postDelayed(this, interval); return }
                if (BotState.joystickActive) { handler.postDelayed(this, 300L); return }

                val state = BotState.lastGameState
                val now   = System.currentTimeMillis()

                // Pronta da visione O timer scaduto (fallback)
                val visionReady  = idx < state.skillsReady.size && state.skillsReady[idx]
                val timerElapsed = (now - lastFiredAt) >= interval

                val canFire = (visionReady || timerElapsed) && when {
                    BotState.pullMode -> state.mobCount >= BotState.pullTargetCount
                    else              -> true  // fire sempre (come il vecchio sistema)
                }

                if (canFire) {
                    val (sx, sy) = slots[idx]
                    tapSingle(sx, sy, SKILL_TAP_MS)
                    lastFiredAt = now
                    // Dopo il fire: aspetta almeno 1s prima del prossimo check
                    handler.postDelayed(this, interval.coerceAtLeast(1000L))
                } else {
                    // Non pronta: controlla di nuovo in 500ms (polling veloce vision)
                    handler.postDelayed(this, 500L)
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
        GameStateAnalyzer.resetCalibration()   // re-calibra la barra HP a ogni avvio
        // gameStateScanner parte subito (200ms). farmLoop parte dopo 400ms così il
        // primo scan è già pronto prima del primo ciclo di decisione dell'AI.
        handler.post(gameStateScanner)
        handler.postDelayed(farmLoop, 400L)
        BotLogger.d("BotAtk", "AI v1: gameStateScanner + farmLoop avviati")
    }

    fun stopAttack() {
        BotState.attackRunning = false
        BotState.mobNearby     = false
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
        // Primo check a 500ms: la vision rileva subito se la skill è pronta.
        // Il loop poi gestisce autonomamente il timing vision+fallback timer.
        BotState.skillSlots.forEachIndexed { idx, _ ->
            handler.postDelayed(skillLoops[idx], 500L)
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
