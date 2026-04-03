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

class BotAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var lootTargets: List<Pair<Float, Float>> = emptyList()
    private var gestureInProgress = false

    private val ATTACK_TAP_MS      = 40L
    private val ATTACK_GAP_MS      = 280L
    private val POTION_TAP_MS      = 35L
    private val SKILL_TAP_MS       = 40L
    // Dopo ogni pozione, ripristina immediatamente il loop di attacco
    // per colmare il gap causato dal gesto accessibility che interrompe il tap corrente.
    private val ATTACK_RESTART_AFTER_POTION_MS = 50L

    // ═══════════════════════════════════════════════════════════════════════════
    // LOOP ATTACCO — tappa continuamente; nessun check mobNearby.
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
            if (BotState.joystickActive) {
                Log.d("BotAtk", "Attacco in pausa: joystickActive=true")
                scheduleNextAttack(); return
            }
            val pos = BotState.attackPos ?: run {
                Log.w("BotAtk", "attackPos non impostato!")
                scheduleNextAttack(); return
            }
            try {
                val ok = dispatchGesture(
                    GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(
                            Path().apply { moveTo(pos.first, pos.second) }, 0L, ATTACK_TAP_MS))
                        .build(), attackCallback, handler)
                if (!ok) {
                    Log.w("BotAtk", "dispatchGesture=false (gesture in corso)")
                    scheduleNextAttack()
                }
            } catch (e: Exception) {
                Log.e("BotAtk", "Errore gesto attacco: ${e.message}")
                scheduleNextAttack()
            }
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
                        Log.d("BotMob", "Mob rilevati: $count (pullMode=${BotState.pullMode})")
                        if (BotState.pullMode && count > 0) updateMobDirection(b)
                        else if (BotState.pullMode) { BotState.mobDirX = 0f; BotState.mobDirY = -1f }
                        b.recycle()
                    }
                }
                override fun onFailure(e: Int) {
                    Log.w("BotMob", "Screenshot fallito: $e")
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
    // ───────────────────────────────────────────────────────────────────────────
    private fun countMobClusters(bmp: Bitmap): Int {
        val w = bmp.width; val h = bmp.height
        val x0 = (w * 0.15f).toInt(); val x1 = (w * 0.85f).toInt()
        val y0 = (h * 0.10f).toInt(); val y1 = (h * 0.65f).toInt()
        val cellPx = 40
        val sampleStep = 4

        val cols = (x1 - x0) / cellPx
        val rows = (y1 - y0) / cellPx
        if (cols <= 0 || rows <= 0) return 0

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
                        if (r > 160 && g < 115 && b < 115 && r - g > 60) hits++
                        sy += sampleStep
                    }
                    sx += sampleStep
                }
                hot[row][col] = hits >= 3
            }
        }

        val visited = Array(rows) { BooleanArray(cols) }
        var clusters = 0
        val dr = intArrayOf(-1, 1, 0, 0)
        val dc = intArrayOf(0, 0, -1, 1)
        for (startRow in 0 until rows) {
            for (startCol in 0 until cols) {
                if (!hot[startRow][startCol] || visited[startRow][startCol]) continue
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
                if (clusterSize >= 2) clusters++
            }
        }
        return clusters
    }

    // ───────────────────────────────────────────────────────────────────────────
    // updateMobDirection: calcola il vettore normalizzato dal centro dello
    // schermo verso il centroide di tutti i pixel rossi (mob).
    // ───────────────────────────────────────────────────────────────────────────
    private fun updateMobDirection(bmp: Bitmap) {
        val w = bmp.width; val h = bmp.height
        val x0 = (w * 0.12f).toInt(); val x1 = (w * 0.88f).toInt()
        val y0 = (h * 0.08f).toInt(); val y1 = (h * 0.68f).toInt()
        val step = 5
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
    // LOOP POZIONE — indipendente dall'attacco.
    //
    // FIX: dopo ogni tap pozione, se l'attacco è attivo, lo riavviamo subito
    // con un breve delay (50ms) per compensare la cancellazione del gesto
    // corrente da parte dell'accessibility framework. Questo elimina il "buco"
    // nell'attacco durante l'uso delle pozze.
    // ═══════════════════════════════════════════════════════════════════════════
    private val potionLoop = object : Runnable {
        override fun run() {
            if (!BotState.potionRunning) return
            val slots = BotState.potionSlots
            var delay = 0L
            for ((px, py) in slots) {
                handler.postDelayed({
                    if (!BotState.potionRunning || BotState.joystickActive) return@postDelayed
                    tapSingle(px, py, POTION_TAP_MS)
                    // FIX: dopo la pozione, riavvia subito l'attacco se è attivo
                    // per eliminare il gap causato dall'interruzione del gesto precedente.
                    if (BotState.attackRunning) {
                        handler.removeCallbacks(attackLoop)
                        handler.postDelayed(attackLoop, ATTACK_RESTART_AFTER_POTION_MS)
                    }
                }, delay)
                delay += 350L
            }
            handler.postDelayed(this, BotState.potionIntervalMs.coerceAtLeast(500L) + delay)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LOOP ABILITÀ — 5 timer indipendenti.
    //
    // In PULL MODE: ogni abilità viene premuta solo se ci sono abbastanza mob
    // raggruppati (detectedMobCount >= pullTargetCount).
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
    @Volatile private var scanCount = 0

    private val lootLoop = object : Runnable {
        override fun run() {
            if (!BotState.lootRunning) return
            if (BotState.joystickActive) { handler.postDelayed(this, 400L); return }
            if (scanCount < 2) { handler.postDelayed(this, 300L); return }
            val items = lootTargets
            BotState.lootItemsFound = items.size
            val toTap = items.take(4)
            if (toTap.isEmpty()) { handler.postDelayed(this, 500L); return }
            tapItemsSequentially(toTap, 0) {
                if (BotState.lootRunning) handler.postDelayed(this, 600L)
            }
        }
    }

    private fun tapItemsSequentially(items: List<Pair<Float, Float>>, index: Int, onAllDone: () -> Unit) {
        if (!BotState.lootRunning || BotState.joystickActive || index >= items.size) { onAllDone(); return }
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
                    bmp?.let { b -> lootTargets = findLootItems(b); b.recycle(); scanCount++ }
                }
                override fun onFailure(e: Int) {}
            })
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RILEVAMENTO OGGETTI A TERRA — v12
    //
    // ANALISI SCREENSHOT REALE (18:41 03/04/2026):
    //
    // YANG — testo "Yang" appare in GIALLO ORO CALDO con ombra scura:
    //   Pixel core:  R≈215-240, G≈170-195, B≈35-70
    //   Criteri:     R > 175, G > 120, B < 110, R > G+25, R > B+90
    //   Esclude sabbia (B troppo alto), erba (G > R), UI bianca (B alto)
    //
    // ITEM PERSONAGGIO — il tag "bashy"/"Anyasama" appare in CIANO/TEAL:
    //   Pixel core:  R≈70-130, G≈180-220, B≈190-230
    //   Criteri:     B > 160, G > 155, R < 140, B > R+50
    //   Esclude: verde erba (B basso), rosso mob (R alto), bianco UI (R alto)
    //
    // ITEM BIANCO — nome item "Spada Lunga+0" appare BIANCO:
    //   Criteri:     R > 220, G > 220, B > 220, max-min < 30 (non saturo)
    //   Raccoglie qualsiasi item bianco vicino (potrebbero essere tuoi)
    //
    // Zona: 20-80% x, 40-85% y (più ampia per catturare drop lontani).
    // Cella 18px, step 3 (più denso = più hits per cella).
    // Minimo 4 hits su ~36 campioni per confermare un'etichetta.
    // Distanza max 28% larghezza (copre tutto il cerchio di drop vicini).
    // ═══════════════════════════════════════════════════════════════════════════
    private fun findLootItems(bmp: Bitmap): List<Pair<Float, Float>> {
        val w = bmp.width; val h = bmp.height
        val x0 = (w * 0.20f).toInt(); val x1 = (w * 0.80f).toInt()
        val y0 = (h * 0.40f).toInt(); val y1 = (h * 0.85f).toInt()
        val cell = 18
        val step = 3
        val charX = w * 0.50f; val charY = h * 0.60f
        val maxDist = w * 0.28f; val maxDistSq = maxDist * maxDist
        val found = mutableListOf<Pair<Float, Float>>()

        var totalYangHits = 0; var totalItemHits = 0; var totalWhiteHits = 0

        var cy = y0
        while (cy + cell <= y1) {
            var cx = x0
            while (cx + cell <= x1) {
                val itemX = (cx + cell / 2).toFloat()
                val itemY = (cy + cell / 2).toFloat()
                val ddx = itemX - charX; val ddy = itemY - charY
                if (ddx * ddx + ddy * ddy > maxDistSq) { cx += cell; continue }

                var hitsYang  = 0   // giallo-oro = Yang
                var hitsItem  = 0   // ciano = item tuo personaggio
                var hitsWhite = 0   // bianco = nome item (potrebbe essere tuo)

                var dy2 = 0
                while (dy2 < cell) {
                    var dx2 = 0
                    while (dx2 < cell) {
                        val p = bmp.getPixel(cx + dx2, cy + dy2)
                        val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)

                        // Yang: giallo-oro caldo — R>G, R>>B, G non troppo basso
                        if (r > 175 && g > 120 && b < 110 && r - g > 25 && r - b > 90) {
                            hitsYang++; totalYangHits++
                        }
                        // Item personaggio: ciano/teal — B e G alti, R basso
                        else if (b > 160 && g > 155 && r < 140 && b - r > 50) {
                            hitsItem++; totalItemHits++
                        }
                        // Item bianco: testo nome item — tutti canali alti, non saturo
                        else if (r > 220 && g > 220 && b > 220 && (maxOf(r, g, b) - minOf(r, g, b)) < 30) {
                            hitsWhite++; totalWhiteHits++
                        }
                        dx2 += step
                    }
                    dy2 += step
                }

                if (hitsYang >= 4 || hitsItem >= 4 || hitsWhite >= 4) {
                    val tipo = when {
                        hitsYang >= 4  -> "Yang"
                        hitsItem >= 4  -> "Item-cyan"
                        else           -> "Item-white"
                    }
                    Log.d("BotLoot", "Trovato $tipo a ($itemX,$itemY) yang=$hitsYang cyan=$hitsItem white=$hitsWhite")
                    found.add(itemX to itemY)
                    if (found.size >= 8) break
                }
                cx += cell
            }
            if (found.size >= 8) break
            cy += cell
        }

        if (found.isEmpty()) {
            Log.d("BotLoot", "Nessun item. Hits totali: yang=$totalYangHits cyan=$totalItemHits white=$totalWhiteHits")
        } else {
            Log.d("BotLoot", "Trovati ${found.size} item da raccogliere")
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

    // ═══════════════════════════════════════════════════════════════════════════
    // CAMMINATA — il bot spinge il joystick in avanti continuamente
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
        val r = if (BotState.joystickRadius > 0f) BotState.joystickRadius * 0.65f
                else resources.displayMetrics.widthPixels * 0.07f * 0.65f
        val (dirX, dirY) = if (BotState.pullMode && BotState.detectedMobCount > 0) {
            BotState.mobDirX to BotState.mobDirY
        } else {
            0f to -1f
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
    }

    fun startPullMode() {
        BotState.pullMode = true
        if (!BotState.attackRunning) {
            handler.removeCallbacks(mobScanner)
            handler.post(mobScanner)
        }
    }

    fun stopPullMode() {
        BotState.pullMode = false
        BotState.detectedMobCount = 0
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
