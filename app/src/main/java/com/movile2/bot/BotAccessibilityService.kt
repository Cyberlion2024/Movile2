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
    // FARM LOOP — integra movimento verso i mob + attacco in un unico ciclo.
    //
    // Logica per ciclo (~400ms totali):
    //   1. Legge mob count e direzione dal BotState (aggiornati dal mobScanner)
    //   2. Decide se camminare:
    //      - SÌ se joystick configurato E non siamo in pull-hold (abbastanza mob)
    //      - NO se pull mode ha già raggruppato N mob (si sta fermo e combatte)
    //   3. Se camminare: spinge joystick 110ms in direzione mob (o avanti se no mob)
    //      Poi dopo 140ms tappa il tasto attacco
    //   4. Se non camminare: tappa subito il tasto attacco
    //   5. Pianifica il prossimo ciclo a 380ms dall'inizio
    //
    // Questo rimpiazza sia il vecchio attackLoop (che tappava un punto fisso)
    // sia il vecchio walkLoop (che era completamente separato dall'attacco).
    // Il personaggio ora CERCA i mob, si avvicina e li attacca.
    //
    // PULL MODE:
    //   - Finché detectedMobCount < pullTargetCount: cammina attraendo mob
    //   - Quando detectedMobCount >= pullTargetCount: smette di camminare,
    //     attacca e le skill si attivano (skillLoop le controlla già)
    // ═══════════════════════════════════════════════════════════════════════════
    private val farmLoop = object : Runnable {
        override fun run() {
            if (!BotState.attackRunning) return
            if (BotState.joystickActive) {
                handler.postDelayed(this, 200L); return
            }

            // ── Chiede all'AI cosa fare in questo ciclo ──────────────────
            val action = BotDecisionEngine.decide()
            BotLogger.d("BotAtk", "[${action.stateLabel}] walk=${action.shouldWalk}" +
                " dir=(${
                    "%.2f".format(action.dirX)},${
                    "%.2f".format(action.dirY)})" +
                " mobs=${BotState.detectedMobCount}")

            if (action.shouldWalk && BotState.joystickPos != null) {
                // Sposta il joystick nella direzione decisa dall'AI (110ms)
                pushJoystick(action.dirX, action.dirY, 110L)
                // Dopo che la gesture finisce (110ms) + buffer 30ms → attacca
                handler.postDelayed({
                    if (BotState.attackRunning && !BotState.joystickActive) tapAttack()
                }, 140L)
            } else {
                // Nessun movimento (pull-hold o joystick non configurato) → attacca direttamente
                tapAttack()
            }

            handler.postDelayed(this, 380L)
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

    private fun pushJoystick(dirX: Float, dirY: Float, durationMs: Long) {
        val pos = BotState.joystickPos ?: return
        val r = if (BotState.joystickRadius > 0f) BotState.joystickRadius * 0.65f
                else resources.displayMetrics.widthPixels * 0.07f
        try {
            dispatchGesture(
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(
                        Path().apply {
                            moveTo(pos.first, pos.second)
                            lineTo(pos.first + dirX * r, pos.second + dirY * r)
                        }, 0L, durationMs))
                    .build(), null, null)
        } catch (_: Exception) {}
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
            // Scatta screenshot e aggiorna mobCount + direzione solo se il bot non è in pausa
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
                        // Aggiorna sempre la direzione mob (serve al farmLoop per camminare verso i nemici)
                        if (count > 0) updateMobDirection(b)
                        else { BotState.mobDirX = 0f; BotState.mobDirY = -1f }
                        BotLogger.d("BotMob", "Mob: $count dir=(${
                            "%.2f".format(BotState.mobDirX)},${
                            "%.2f".format(BotState.mobDirY)})")
                        b.recycle()
                    }
                }
                override fun onFailure(e: Int) {
                    BotLogger.w("BotMob", "Screenshot fallito: $e")
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
                        handler.removeCallbacks(farmLoop)
                        handler.postDelayed(farmLoop, ATTACK_RESTART_AFTER_POTION_MS)
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
            handler.removeCallbacks(farmLoop); handler.post(farmLoop)
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
        // Walk standalone: sempre in avanti (0, -1). Non usa mobDir — quella è per farmLoop.
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
        BotState.attackRunning = true
        BotState.mobNearby = false
        BotState.detectedMobCount = 0
        BotState.mobDirX = 0f; BotState.mobDirY = -1f
        BotDecisionEngine.reset()
        handler.post(mobScanner)
        handler.post(farmLoop)
        BotLogger.d("BotAtk", "farmLoop+AI avviati")
    }

    fun stopAttack() {
        BotState.attackRunning = false
        BotState.mobNearby = false
        handler.removeCallbacks(farmLoop)
        if (!BotState.pullMode) {
            handler.removeCallbacks(mobScanner)
            BotState.detectedMobCount = 0
        }
        BotLogger.d("BotAtk", "farmLoop fermato")
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
        walkPending = false
    }

    fun startPullMode() {
        BotState.pullMode = true
        // Il mobScanner serve anche senza attack per sapere quanti mob ci sono
        if (!BotState.attackRunning) {
            handler.removeCallbacks(mobScanner)
            handler.post(mobScanner)
        }
        BotLogger.d("BotAtk", "Pull mode ON — target: ${BotState.pullTargetCount} mob")
    }

    fun stopPullMode() {
        BotState.pullMode = false
        BotState.detectedMobCount = 0
        if (!BotState.attackRunning) handler.removeCallbacks(mobScanner)
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
