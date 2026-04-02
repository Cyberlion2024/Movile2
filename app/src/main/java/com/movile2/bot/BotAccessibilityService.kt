package com.movile2.bot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat

class BotAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var lootTargets: List<Pair<Float, Float>> = emptyList()
    private var gestureInProgress = false

    private val ATTACK_TAP_MS       = 40L
    private val ATTACK_GAP_MS       = 280L
    private val POTION_TAP_MS       = 35L
    private val SKILL_TAP_MS        = 40L
    // Durante ogni pozione teniamo premuto il tasto attacco per questa durata
    // così il dito reale dell'utente non viene mai "rilasciato" (copre il gap)
    private val POTION_ATK_HOLD_MS  = 1500L

    // ═══════════════════════════════════════════════════════════════════════════
    // LOOP ATTACCO — tappa continuamente; nessun check mobNearby.
    // Il bot attacca sempre quando è ON: l'utente sceglie lui quando abilitare.
    // Ciclo: tap 40ms → callback → gap 280ms → tap 40ms → ...
    // ═══════════════════════════════════════════════════════════════════════════
    private val attackCallback = object : GestureResultCallback() {
        override fun onCompleted(g: GestureDescription?) { scheduleNextAttack() }
        override fun onCancelled(g: GestureDescription?)  { scheduleNextAttack() }
    }

    private fun scheduleNextAttack() {
        if (!BotState.attackRunning) return
        handler.postDelayed(attackLoop, ATTACK_GAP_MS)
    }

    private val attackLoop = object : Runnable {
        override fun run() {
            if (!BotState.attackRunning) return
            if (BotState.joystickActive) { scheduleNextAttack(); return }
            val pos = BotState.attackPos ?: run { scheduleNextAttack(); return }
            try {
                val ok = dispatchGesture(
                    GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(
                            Path().apply { moveTo(pos.first, pos.second) }, 0L, ATTACK_TAP_MS))
                        .build(), attackCallback, handler)
                if (!ok) scheduleNextAttack()
            } catch (_: Exception) { scheduleNextAttack() }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SCANNER MOB — ogni 500ms conta i cluster di nomi rossi sullo schermo
    //
    // Algoritmo (da analisi libUE4.so):
    //   UmobNamer.ismob=true + medusman=true → nome ROSSO (mob nemico).
    //   UmobNamer.group=true                  → nome VERDE (membro gruppo) — ignorato.
    //   isLocal=true / isPlayer               → nome BIANCO (proprio char) — ignorato.
    //
    // La detection divide lo schermo in una griglia di celle 40×40px.
    // Ogni cella con almeno 3 pixel rossi vivaci viene marcata come "calda".
    // Un BFS sulle celle calde conta i cluster contigui → numero di mob distinti.
    // Il risultato viene salvato in BotState.detectedMobCount.
    // ═══════════════════════════════════════════════════════════════════════════
    private val mobScanner = object : Runnable {
        override fun run() {
            if (!BotState.attackRunning && !BotState.pullMode) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !BotState.joystickActive) {
                doMobScan()
            }
            handler.postDelayed(this, 500L)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun doMobScan() {
        takeScreenshot(Display.DEFAULT_DISPLAY, ContextCompat.getMainExecutor(this),
            object : TakeScreenshotCallback {
                override fun onSuccess(s: ScreenshotResult) {
                    val hw = Bitmap.wrapHardwareBuffer(s.hardwareBuffer, s.colorSpace)
                    val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    hw?.recycle(); s.hardwareBuffer.close()
                    bmp?.let { b ->
                        val count = countMobClusters(b)
                        BotState.detectedMobCount = count
                        BotState.mobNearby = count > 0
                        // In pull mode aggiorna la direzione verso i mob
                        if (BotState.pullMode && count > 0) updateMobDirection(b)
                        else if (BotState.pullMode) { BotState.mobDirX = 0f; BotState.mobDirY = -1f }
                        b.recycle()
                    }
                }
                override fun onFailure(e: Int) {
                    BotState.mobNearby = false
                    BotState.detectedMobCount = 0
                }
            })
    }

    // ───────────────────────────────────────────────────────────────────────────
    // countMobClusters: conta quanti mob distinti (con nome rosso) sono
    // visibili nella zona centrale dello schermo.
    //
    // Zona di ricerca: 15%..85% x, 10%..65% y (area nomi mob in UE4)
    // Colore nome nemico (UmobNamer.nameColor con medusman=true):
    //   R > 160, G < 115, B < 115, R-G > 60
    // Esclusi colori simili ma non-mob:
    //   bianco (R≈G≈B alto) → proprio personaggio
    //   verde  (G dominante) → membro gruppo
    //
    // Griglia 40×40px: ogni cella "calda" ha ≥3 pixel rossi nel 4×4 campionamento.
    // BFS sulle celle calde conta i componenti connessi → numero mob distinti.
    // Celle isolate (singola cella senza vicini caldi) scartate come rumore.
    // ───────────────────────────────────────────────────────────────────────────
    private fun countMobClusters(bmp: Bitmap): Int {
        val w = bmp.width; val h = bmp.height
        val x0 = (w * 0.15f).toInt(); val x1 = (w * 0.85f).toInt()
        val y0 = (h * 0.10f).toInt(); val y1 = (h * 0.65f).toInt()
        val cellPx = 40   // dimensione cella in pixel
        val sampleStep = 4 // step campionamento dentro la cella

        val cols = (x1 - x0) / cellPx
        val rows = (y1 - y0) / cellPx
        if (cols <= 0 || rows <= 0) return 0

        // Cella "calda" = almeno 3 pixel rossi nel campione 4-pixel-step
        val hot = Array(rows) { BooleanArray(cols) }
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val cx0 = x0 + col * cellPx
                val cy0 = y0 + row * cellPx
                var hits = 0
                var sx = cx0
                while (sx < cx0 + cellPx && sx < x1) {
                    var sy = cy0
                    while (sy < cy0 + cellPx && sy < y1) {
                        val p = bmp.getPixel(sx, sy)
                        val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
                        // Rosso vivace: nome mob nemico (medusman=true in UmobNamer)
                        if (r > 160 && g < 115 && b < 115 && r - g > 60) hits++
                        sy += sampleStep
                    }
                    sx += sampleStep
                }
                hot[row][col] = hits >= 3
            }
        }

        // BFS per contare cluster connessi (4-connectivity)
        val visited = Array(rows) { BooleanArray(cols) }
        var clusters = 0
        val dr = intArrayOf(-1, 1, 0, 0)
        val dc = intArrayOf(0, 0, -1, 1)
        for (startRow in 0 until rows) {
            for (startCol in 0 until cols) {
                if (!hot[startRow][startCol] || visited[startRow][startCol]) continue
                // BFS
                val queue = ArrayDeque<Pair<Int, Int>>()
                queue.add(startRow to startCol)
                visited[startRow][startCol] = true
                var clusterSize = 0
                while (queue.isNotEmpty()) {
                    val (r, c) = queue.removeFirst()
                    clusterSize++
                    for (d in 0 until 4) {
                        val nr = r + dr[d]; val nc = c + dc[d]
                        if (nr in 0 until rows && nc in 0 until cols &&
                            hot[nr][nc] && !visited[nr][nc]) {
                            visited[nr][nc] = true
                            queue.add(nr to nc)
                        }
                    }
                }
                // Scarta cluster di 1 cella sola (rumore, pixel rosso casuale)
                if (clusterSize >= 2) clusters++
            }
        }
        return clusters
    }

    // ───────────────────────────────────────────────────────────────────────────
    // updateMobDirection: calcola il vettore normalizzato dal centro dello
    // schermo (personaggio) verso il centroide di tutti i pixel rossi (mob).
    // Aggiorna BotState.mobDirX / mobDirY.
    // Chiamata solo quando pullMode=true e count > 0.
    // ───────────────────────────────────────────────────────────────────────────
    private fun updateMobDirection(bmp: Bitmap) {
        val w = bmp.width; val h = bmp.height
        val x0 = (w * 0.12f).toInt(); val x1 = (w * 0.88f).toInt()
        val y0 = (h * 0.08f).toInt(); val y1 = (h * 0.68f).toInt()
        val step = 5   // campionamento 1 pixel ogni 5
        var sumX = 0L; var sumY = 0L; var n = 0L
        var sx = x0
        while (sx < x1) {
            var sy = y0
            while (sy < y1) {
                val p = bmp.getPixel(sx, sy)
                val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
                if (r > 160 && g < 115 && b < 115 && r - g > 60) {
                    sumX += sx; sumY += sy; n++
                }
                sy += step
            }
            sx += step
        }
        if (n == 0L) { BotState.mobDirX = 0f; BotState.mobDirY = -1f; return }
        val centX = (sumX / n).toFloat()
        val centY = (sumY / n).toFloat()
        val charX  = w * 0.50f
        val charY  = h * 0.55f
        val dx = centX - charX; val dy = centY - charY
        val len = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
        BotState.mobDirX = dx / len
        BotState.mobDirY = dy / len
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LOOP POZIONE — indipendente dall'attacco, usa multitouch se attacco attivo
    // ═══════════════════════════════════════════════════════════════════════════
    private val potionLoop = object : Runnable {
        override fun run() {
            if (!BotState.potionRunning) return
            val slots = BotState.potionSlots
            var delay = 0L
            for ((px, py) in slots) {
                handler.postDelayed({
                    if (!BotState.potionRunning || gestureInProgress) return@postDelayed
                    if (BotState.joystickActive) return@postDelayed
                    // Quando la pozione scatta, il gesto accessibility cancella
                    // il dito reale dell'utente. Per compensare, teniamo premuto
                    // il tasto attacco per POTION_ATK_HOLD_MS (1500ms) nella stessa
                    // gesture così il gioco vede attacco sempre premuto.
                    // Funziona sia con bot-attack ON che con attacco manuale.
                    val aPos = BotState.attackPos
                    if (aPos != null) {
                        tapMultitouch(aPos.first, aPos.second, POTION_ATK_HOLD_MS,
                                      px, py, POTION_TAP_MS)
                    } else {
                        tapSingle(px, py, POTION_TAP_MS)
                    }
                }, delay)
                delay += 320L
            }
            handler.postDelayed(this, BotState.potionIntervalMs.coerceAtLeast(500L) + delay)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LOOP ABILITÀ — 5 timer indipendenti.
    //
    // In PULL MODE: ogni abilità viene premuta solo se ci sono abbastanza mob
    // raggruppati (detectedMobCount >= pullTargetCount).
    // Se non ci sono abbastanza mob, il timer salta e riprova al prossimo ciclo.
    // In modalità normale: comportamento invariato, si attiva a ogni ciclo.
    // ═══════════════════════════════════════════════════════════════════════════
    private val skillLoops: Array<Runnable> = Array(5) { idx ->
        object : Runnable {
            override fun run() {
                if (!BotState.skillsRunning) return
                val slots = BotState.skillSlots
                if (idx < slots.size && !BotState.joystickActive) {
                    val canFire = if (BotState.pullMode) {
                        BotState.detectedMobCount >= BotState.pullTargetCount
                    } else {
                        true
                    }
                    if (canFire) {
                        val (sx, sy) = slots[idx]
                        tapSingle(sx, sy, SKILL_TAP_MS)
                    }
                }
                val interval = BotState.skillIntervals.getOrElse(idx) { 5000L }.coerceAtLeast(500L)
                handler.postDelayed(this, interval)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LOOP RACCOLTA
    // ═══════════════════════════════════════════════════════════════════════════
    private val lootLoop = object : Runnable {
        override fun run() {
            if (!BotState.lootRunning) return
            if (BotState.joystickActive) { handler.postDelayed(this, 300L); return }
            val items = lootTargets
            BotState.lootItemsFound = items.size
            if (items.isEmpty()) { handler.postDelayed(this, 400L); return }
            tapItemsSequentially(items, 0) {
                if (BotState.lootRunning) handler.postDelayed(this, 250L)
            }
        }
    }

    private fun tapItemsSequentially(items: List<Pair<Float, Float>>, index: Int, onAllDone: () -> Unit) {
        if (!BotState.lootRunning || BotState.joystickActive || index >= items.size) { onAllDone(); return }
        val (x, y) = items[index]
        gestureInProgress = true
        val dispatched = tapSingle(x, y, 55L)
        gestureInProgress = false
        handler.postDelayed({ tapItemsSequentially(items, index + 1, onAllDone) }, if (dispatched) 100L else 130L)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SCANNER LOOT — ogni 400ms
    // ═══════════════════════════════════════════════════════════════════════════
    private val lootScanner = object : Runnable {
        override fun run() {
            if (!BotState.lootRunning) return
            if (!BotState.joystickActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) doLootScan()
            handler.postDelayed(this, 400L)
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
                    bmp?.let { b -> lootTargets = findLootItems(b); b.recycle() }
                }
                override fun onFailure(e: Int) {}
            })
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RILEVAMENTO OGGETTI A TERRA
    // Yang (oro): R>220, G>170, B<60    — moneta yang / drop dorato
    // Testo verde: G>170, R<120, B<100  — nome oggetto comune
    // Testo bianco: R>220, G>220, B>220 — yang testo bianco
    // Cella 12px per separare oggetti vicini
    // ═══════════════════════════════════════════════════════════════════════════
    private fun findLootItems(bmp: Bitmap): List<Pair<Float, Float>> {
        val w = bmp.width; val h = bmp.height
        val x0 = (w * 0.20f).toInt(); val x1 = (w * 0.80f).toInt()
        val y0 = (h * 0.25f).toInt(); val y1 = (h * 0.75f).toInt()
        val cell = 12
        val found = mutableListOf<Pair<Float, Float>>()
        val charX = w * 0.50f; val charY = h * 0.57f
        val maxDist = w * 0.38f; val maxDistSq = maxDist * maxDist

        var cy = y0
        while (cy + cell <= y1) {
            var cx = x0
            while (cx + cell <= x1) {
                val itemX = (cx + cell / 2).toFloat()
                val itemY = (cy + cell / 2).toFloat()
                val ddx = itemX - charX; val ddy = itemY - charY
                if (ddx * ddx + ddy * ddy > maxDistSq) { cx += cell; continue }
                var hits = 0
                for (dy2 in 0 until cell step 2) {
                    for (dx2 in 0 until cell step 2) {
                        val p = bmp.getPixel(cx + dx2, cy + dy2)
                        val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
                        val isWhite = r > 220 && g > 220 && b > 220
                        val isGold  = r > 220 && g > 170 && b < 60 && r > g
                        val isGreen = g > 170 && r < 120 && b < 100
                        if (isWhite || isGold || isGreen) hits++
                    }
                }
                if (hits >= 3) { found.add(itemX to itemY); if (found.size >= 12) break }
                cx += cell
            }
            if (found.size >= 12) break
            cy += cell
        }
        return found.sortedBy { (fx, fy) ->
            val ddx = fx - charX; val ddy = fy - charY; ddx * ddx + ddy * ddy
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
            handler.removeCallbacks(attackLoop); handler.post(attackLoop)
            handler.removeCallbacks(mobScanner); handler.post(mobScanner)
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
        if (BotState.walkRunning) {
            dispatchWalk()
        }
        // Se pull mode attivo ma attacco non attivo: tiene vivo il mob scanner
        if (BotState.pullMode && !BotState.attackRunning) {
            handler.removeCallbacks(mobScanner); handler.post(mobScanner)
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

    private fun tapMultitouch(x1: Float, y1: Float, dur1Ms: Long, x2: Float, y2: Float, dur2Ms: Long): Boolean {
        return try {
            dispatchGesture(
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(x1, y1) }, 0L, dur1Ms))
                    .addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(x2, y2) }, 0L, dur2Ms))
                    .build(), null, null)
        } catch (_: Exception) { false }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CAMMINATA — il bot spinge il joystick in avanti continuamente
    //
    // Invia gesture da centro joystick → punto in avanti (y - raggio).
    // Ogni gesture dura 400ms; al completamento ne parte un'altra subito.
    // Si ferma solo quando walkRunning diventa false.
    // Riprende automaticamente dopo la pausa joystick manuale.
    // ═══════════════════════════════════════════════════════════════════════════
    private val walkCallback = object : GestureResultCallback() {
        override fun onCompleted(g: GestureDescription?) { dispatchWalk() }
        override fun onCancelled(g: GestureDescription?) { if (BotState.walkRunning) dispatchWalk() }
    }

    private fun dispatchWalk() {
        if (!BotState.walkRunning || BotState.joystickActive) {
            if (BotState.walkRunning) handler.postDelayed({ dispatchWalk() }, 300L)
            return
        }
        val pos = BotState.joystickPos ?: return
        // Raggio push: usa joystickRadius se impostato, altrimenti 7% larghezza schermo
        val r = if (BotState.joystickRadius > 0f) BotState.joystickRadius * 0.65f
                else resources.displayMetrics.widthPixels * 0.07f * 0.65f
        // Direzione: verso i mob se pull mode attivo, altrimenti avanti (su)
        val (dirX, dirY) = if (BotState.pullMode && BotState.detectedMobCount > 0) {
            BotState.mobDirX to BotState.mobDirY
        } else {
            0f to -1f   // avanti
        }
        val endX = pos.first  + dirX * r
        val endY = pos.second + dirY * r
        try {
            dispatchGesture(
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(
                        Path().apply { moveTo(pos.first, pos.second); lineTo(endX, endY) },
                        0L, 400L))
                    .build(), walkCallback, handler)
        } catch (_: Exception) {
            if (BotState.walkRunning) handler.postDelayed({ dispatchWalk() }, 150L)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // API PUBBLICA
    // ═══════════════════════════════════════════════════════════════════════════
    fun startAttack() {
        if (BotState.attackPos == null) return
        if (BotState.attackRunning) stopAttack()
        BotState.attackRunning = true
        BotState.mobNearby = false
        handler.post(mobScanner)
        handler.post(attackLoop)
    }

    fun stopAttack() {
        BotState.attackRunning = false
        BotState.mobNearby = false
        handler.removeCallbacks(attackLoop)
        // Ferma il mobScanner solo se pull mode non è attivo
        if (!BotState.pullMode) {
            handler.removeCallbacks(mobScanner)
            BotState.detectedMobCount = 0
        }
    }

    fun startPotion(intervalMs: Long = BotState.potionIntervalMs) {
        if (BotState.potionSlots.isEmpty()) return
        if (BotState.potionRunning) stopPotion()
        BotState.potionIntervalMs = intervalMs.coerceAtLeast(500L)
        BotState.potionRunning = true; handler.post(potionLoop)
    }

    fun stopPotion() {
        BotState.potionRunning = false; handler.removeCallbacks(potionLoop)
    }

    fun startSkills() {
        if (BotState.skillSlots.isEmpty()) return
        if (BotState.skillsRunning) stopSkills()
        BotState.skillsRunning = true
        BotState.skillSlots.forEachIndexed { idx, _ ->
            val interval = BotState.skillIntervals.getOrElse(idx) { 5000L }.coerceAtLeast(500L)
            handler.postDelayed(skillLoops[idx], interval)
        }
    }

    fun stopSkills() {
        BotState.skillsRunning = false
        skillLoops.forEach { handler.removeCallbacks(it) }
    }

    fun startLoot() {
        stopAttack()
        if (BotState.lootRunning) return
        lootTargets = emptyList(); BotState.lootItemsFound = 0; gestureInProgress = false
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
    }

    // Attiva pull mode: avvia il mob scanner se non è già attivo,
    // e mantiene il contatore di cluster aggiornato per le skill.
    fun startPullMode() {
        BotState.pullMode = true
        if (!BotState.attackRunning) {
            // Scanner deve girare anche senza attacco per il conteggio pull
            handler.removeCallbacks(mobScanner)
            handler.post(mobScanner)
        }
    }

    fun stopPullMode() {
        BotState.pullMode = false
        BotState.detectedMobCount = 0
        // Spegni scanner se anche attacco è off
        if (!BotState.attackRunning) {
            handler.removeCallbacks(mobScanner)
        }
    }

    fun stopAll() {
        stopAttack(); stopPotion(); stopSkills(); stopLoot(); stopWalk()
        stopPullMode()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // AUTO-DETECT JOYSTICK
    //
    // Scansiona il quadrante in basso a sinistra dello schermo (x: 2-30%,
    // y: 68-97%) cercando la regione più scura (joystick = overlay semi-trasparente
    // scuro sul background di gioco). Divide l'area in celle 50×50px, calcola
    // la luminosità media di ogni cella, trova la cella minima.
    // Centro della cella = posizione joystick.  Radius = screen_width * 0.09.
    // Chiama onResult(center, radius) nel thread principale.
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
                            // Media luminosità della cella (step=5)
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

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() { stopAll() }
    override fun onDestroy() {
        super.onDestroy()
        stopAll()
        BotState.joystickActive = false
        if (instance === this) instance = null
    }

    companion object {
        var instance: BotAccessibilityService? = null
            private set
    }
}
