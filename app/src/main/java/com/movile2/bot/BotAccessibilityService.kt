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

    // ── Durate gesti ──────────────────────────────────────────────────────────
    private val ATTACK_TAP_MS  = 40L   // tap breve = meno interferenza col joystick
    private val ATTACK_GAP_MS  = 280L  // pausa tra un tap e l'altro (~3 att/s)
    private val POTION_TAP_MS  = 35L   // tap pozione molto breve

    // ═══════════════════════════════════════════════════════════════════════════
    // LOOP ATTACCO — callback-driven, gap garantito
    //
    // Ciclo: [tap 40ms] → onCompleted → [gap 280ms] → [tap 40ms] → …
    // Totale ciclo ≈ 320ms → ~3.1 attacchi/secondo.
    //
    // Il tap dura solo 40ms: Android ha 280ms puliti in cui il joystick
    // dell'utente funziona senza interferenze.
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
            val pos = BotState.attackPos ?: run { scheduleNextAttack(); return }
            try {
                val ok = dispatchGesture(
                    GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(
                            Path().apply { moveTo(pos.first, pos.second) },
                            0L, ATTACK_TAP_MS))
                        .build(), attackCallback, handler)
                if (!ok) scheduleNextAttack()
            } catch (_: Exception) { scheduleNextAttack() }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LOOP POZIONE
    //
    // Se l'attacco BOT è attivo → multitouch (dito 1 = attacco, dito 2 = pozz).
    // Se l'attacco è MANUALE (attackRunning=false ma attackPos impostato):
    //   → tap pozione + re-tap attacco dopo 60ms per ripristinare il colpo.
    // Se nessuna posizione attacco → tap singolo normale.
    // ═══════════════════════════════════════════════════════════════════════════
    private val potionLoop = object : Runnable {
        override fun run() {
            if (!BotState.potionRunning) return
            val slots = BotState.potionSlots
            var delay = 0L
            for ((px, py) in slots) {
                handler.postDelayed({
                    if (!BotState.potionRunning || gestureInProgress) return@postDelayed
                    if (BotState.attackRunning) {
                        // Modalità bot attacco: multitouch
                        val aPos = BotState.attackPos
                        if (aPos != null) {
                            tapMultitouch(aPos.first, aPos.second, ATTACK_TAP_MS,
                                          px, py, POTION_TAP_MS)
                        } else {
                            tapSingle(px, py, POTION_TAP_MS)
                        }
                    } else {
                        // Modalità attacco manuale: prima la pozione...
                        tapSingle(px, py, POTION_TAP_MS)
                        // ...poi, dopo 70ms, re-tap sull'attacco per ripristinarlo.
                        // Il gioco riceve: pozione (35ms) → pausa 70ms → attacco (60ms).
                        val aPos = BotState.attackPos
                        if (aPos != null) {
                            handler.postDelayed({
                                if (BotState.potionRunning && !BotState.attackRunning) {
                                    tapSingle(aPos.first, aPos.second, 60L)
                                }
                            }, POTION_TAP_MS + 70L)
                        }
                    }
                }, delay)
                delay += 320L
            }
            handler.postDelayed(this, BotState.potionIntervalMs.coerceAtLeast(500L) + delay)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LOOP RACCOLTA
    // ═══════════════════════════════════════════════════════════════════════════
    private val lootLoop = object : Runnable {
        override fun run() {
            if (!BotState.lootRunning) return
            val items = lootTargets
            BotState.lootItemsFound = items.size
            if (items.isEmpty()) { handler.postDelayed(this, 400L); return }
            tapItemsSequentially(items, 0) {
                if (BotState.lootRunning) handler.postDelayed(this, 250L)
            }
        }
    }

    private fun tapItemsSequentially(
        items: List<Pair<Float, Float>>,
        index: Int,
        onAllDone: () -> Unit
    ) {
        if (!BotState.lootRunning || index >= items.size) { onAllDone(); return }
        val (x, y) = items[index]

        gestureInProgress = true

        val aPos = BotState.attackPos
        val dispatched: Boolean
        if (BotState.attackRunning && aPos != null) {
            dispatched = tapMultitouch(aPos.first, aPos.second, ATTACK_TAP_MS,
                                       x, y, 55L)
        } else {
            dispatched = tapSingle(x, y, 55L)
        }

        val advance = { delay: Long ->
            gestureInProgress = false
            handler.postDelayed({ tapItemsSequentially(items, index + 1, onAllDone) }, delay)
        }
        if (dispatched) advance(100L) else advance(130L)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SCANNER SCREENSHOT — ogni 350ms
    // ═══════════════════════════════════════════════════════════════════════════
    private val scanner = object : Runnable {
        override fun run() {
            if (!BotState.lootRunning) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) doScan()
            handler.postDelayed(this, 350L)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun doScan() {
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
    // RILEVAMENTO OGGETTI A TERRA — v2
    //
    // Area di ricerca:
    //   X: 10%..78%  (esclude joystick sx e pannello skill dx)
    //   Y: 20%..82%  (area gioco centrale, include parte alta per drop lontani)
    //
    // Colori target (confermati dall'analisi APK):
    //
    //   Yang (giallo-oro): due range per coprire varianti chiaro/scuro
    //     - Brillante: R>200, G>140, B<80, R-B>130
    //     - Scuro/ambra: R>180, G>110, B<60, R-B>130, R>G*1.4
    //
    //   Nome item (bianco caldo): testo bianco con leggera tinta calda
    //     - R>215, G>195, B>160, R≥G, G≥B, R-B in [20,80]
    //
    //   Drop brillante (pixel luminoso generale): somma RGB alta + tinta calda
    //     - r+g+b > 580, R>G, G>B, R-B > 20
    //
    // Soglia: 3 pixel su ~110 check (cella 28×28, step 3) — più sensibile
    // Distanza max personaggio: 38% larghezza schermo
    // Ordinamento: per distanza dal personaggio (più vicini prima)
    // ═══════════════════════════════════════════════════════════════════════════
    private fun findLootItems(bmp: Bitmap): List<Pair<Float, Float>> {
        val w = bmp.width; val h = bmp.height

        val x0 = (w * 0.10f).toInt(); val x1 = (w * 0.78f).toInt()
        val y0 = (h * 0.20f).toInt(); val y1 = (h * 0.82f).toInt()
        val cell = 28
        val found = mutableListOf<Pair<Float, Float>>()

        val charX = w * 0.50f
        val charY = h * 0.58f
        val maxDist = w * 0.38f
        val maxDistSq = maxDist * maxDist

        var cy = y0
        while (cy + cell <= y1) {
            var cx = x0
            while (cx + cell <= x1) {
                val itemX = (cx + cell / 2).toFloat()
                val itemY = (cy + cell / 2).toFloat()
                val dx = itemX - charX; val dy = itemY - charY
                if (dx * dx + dy * dy > maxDistSq) { cx += cell; continue }

                var hits = 0
                for (dy2 in 0 until cell step 3) {
                    for (dx2 in 0 until cell step 3) {
                        val p = bmp.getPixel(cx + dx2, cy + dy2)
                        val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)

                        // Yang brillante (giallo vivo)
                        val yangBright = r > 200 && g > 140 && b < 80 && r - b > 130

                        // Yang ambra/scuro (variante più tenue)
                        val yangAmber = r > 180 && g > 110 && b < 60 &&
                                r - b > 130 && r > (g * 1.4f).toInt()

                        // Nome item: bianco caldo
                        val itemName = r > 215 && g > 195 && b > 160 &&
                                r >= g && g >= b && (r - b) in 20..80

                        // Pixel luminoso generico con tinta calda (drop generici)
                        val brightWarm = (r + g + b) > 580 && r > g && g > b && (r - b) > 20

                        if (yangBright || yangAmber || itemName || brightWarm) hits++
                    }
                }

                // Soglia abbassata a 3 (era 5): più sensibile ma comunque filtrata
                if (hits >= 3) {
                    found.add(itemX to itemY)
                    if (found.size >= 10) break
                }
                cx += cell
            }
            if (found.size >= 10) break
            cy += cell
        }

        // Ordina per distanza dal personaggio: prima i più vicini
        return found.sortedBy { (fx, fy) ->
            val ddx = fx - charX; val ddy = fy - charY
            ddx * ddx + ddy * ddy
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

    private fun tapMultitouch(
        x1: Float, y1: Float, dur1Ms: Long,
        x2: Float, y2: Float, dur2Ms: Long
    ): Boolean {
        return try {
            dispatchGesture(
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(
                        Path().apply { moveTo(x1, y1) }, 0L, dur1Ms))
                    .addStroke(GestureDescription.StrokeDescription(
                        Path().apply { moveTo(x2, y2) }, 0L, dur2Ms))
                    .build(), null, null)
        } catch (_: Exception) { false }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // API PUBBLICA
    // ═══════════════════════════════════════════════════════════════════════════
    fun startAttack() {
        if (BotState.attackPos == null) return
        if (BotState.attackRunning) stopAttack()
        BotState.attackRunning = true
        handler.post(attackLoop)
    }

    fun stopAttack() {
        BotState.attackRunning = false
        handler.removeCallbacks(attackLoop)
    }

    fun startPotion(intervalMs: Long = BotState.potionIntervalMs) {
        if (BotState.potionSlots.isEmpty()) return
        if (BotState.potionRunning) stopPotion()
        BotState.potionIntervalMs = intervalMs.coerceAtLeast(500L)
        BotState.potionRunning = true
        handler.post(potionLoop)
    }

    fun stopPotion() {
        BotState.potionRunning = false
        handler.removeCallbacks(potionLoop)
    }

    fun startLoot() {
        if (BotState.lootRunning) return
        lootTargets = emptyList()
        BotState.lootItemsFound = 0
        gestureInProgress = false
        BotState.lootRunning = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            handler.postDelayed(scanner, 200L)
        handler.postDelayed(lootLoop, 600L)
    }

    fun stopLoot() {
        BotState.lootRunning = false
        BotState.lootItemsFound = 0
        gestureInProgress = false
        handler.removeCallbacks(scanner)
        handler.removeCallbacks(lootLoop)
        lootTargets = emptyList()
    }

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() { stopAttack(); stopPotion(); stopLoot() }
    override fun onDestroy() {
        super.onDestroy()
        stopAttack(); stopPotion(); stopLoot()
        if (instance === this) instance = null
    }

    companion object {
        var instance: BotAccessibilityService? = null
            private set
    }
}
