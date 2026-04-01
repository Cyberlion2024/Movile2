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
    private val ATTACK_TAP_MS   = 60L   // durata tap attacco
    private val ATTACK_LOOP_MS  = 120L  // intervallo tra tap (≈8 tap/s)
    private val POTION_TAP_MS   = 40L   // durata tap pozione

    // ═══════════════════════════════════════════════════════════════════════════
    // LOOP ATTACCO — rapid-tap indipendente
    //
    // Ogni 120ms dispatcha un tap da 60ms sul tasto attacco.
    // Nessun willContinue → nessun conflitto con joystick/pozioni/loot.
    // Se il tap viene cancellato (dito utente sullo schermo), il loop
    // continua e il prossimo tap parte tra 120ms — praticamente immediato.
    // ═══════════════════════════════════════════════════════════════════════════
    private val attackLoop = object : Runnable {
        override fun run() {
            if (!BotState.attackRunning) return
            val pos = BotState.attackPos ?: run {
                handler.postDelayed(this, ATTACK_LOOP_MS); return
            }
            fireAttackTap(pos.first, pos.second)
            handler.postDelayed(this, ATTACK_LOOP_MS)
        }
    }

    private fun fireAttackTap(ax: Float, ay: Float) {
        try {
            dispatchGesture(
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(
                        Path().apply { moveTo(ax, ay) }, 0L, ATTACK_TAP_MS))
                    .build(), null, null)
        } catch (_: Exception) {}
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LOOP POZIONE
    //
    // Se l'attacco è attivo: invia attacco + pozione nello stesso GestureDescription
    // come due stroke separati (dito 1 = attacco, dito 2 = pozione).
    // Questo è multitouch vero — i due stroke convivono nello stesso frame,
    // pointer ID distinti, nessuna interferenza.
    //
    // Se l'attacco non è attivo: tap normale.
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
                        val aPos = BotState.attackPos
                        if (aPos != null) {
                            tapMultitouch(aPos.first, aPos.second, ATTACK_TAP_MS,
                                          px, py, POTION_TAP_MS)
                        } else {
                            tapSingle(px, py, POTION_TAP_MS)
                        }
                    } else {
                        tapSingle(px, py, POTION_TAP_MS)
                    }
                }, delay)
                delay += 300L
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
            if (items.isEmpty()) { handler.postDelayed(this, 500L); return }
            tapItemsSequentially(items, 0) {
                if (BotState.lootRunning) handler.postDelayed(this, 300L)
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
            // Multitouch: attacco (dito 1) + loot (dito 2) nello stesso gesto
            dispatched = tapMultitouch(aPos.first, aPos.second, ATTACK_TAP_MS,
                                       x, y, 60L)
        } else {
            dispatched = tapSingle(x, y, 60L)
        }

        val advance = { delay: Long ->
            gestureInProgress = false
            handler.postDelayed({ tapItemsSequentially(items, index + 1, onAllDone) }, delay)
        }
        if (dispatched) advance(120L) else advance(150L)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SCANNER SCREENSHOT — ogni 400ms
    // ═══════════════════════════════════════════════════════════════════════════
    private val scanner = object : Runnable {
        override fun run() {
            if (!BotState.lootRunning) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) doScan()
            handler.postDelayed(this, 400L)
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
    // RILEVAMENTO OGGETTI A TERRA
    //
    // Dallo screenshot: gli oggetti/yang sono nel CENTRO-BASSO dello schermo,
    // lontani dalle UI laterali.
    //
    // Area di ricerca ristretta per evitare falsi positivi da UI:
    //   - X: 12%..76%  (esclude joystick sx e pannello skill dx)
    //   - Y: 25%..80%  (esclude barra top e pulsanti bottom)
    //
    // Colori target:
    //   Yang (giallo-oro saturo): R>210, G>150, B<70, R-B>150
    //   Nome personaggio (verde brillante): G>160, G-R>60, G-B>50, R<120
    //   Nome item (bianco caldo): R>220, G>200, B>170, R≥G≥B, R-B in [25,70]
    //
    // Soglia: 5 pixel su 100 check (3px step su cella 30×30).
    // Più alta della precedente (era 2) per ridurre falsi positivi.
    // ═══════════════════════════════════════════════════════════════════════════
    private fun findLootItems(bmp: Bitmap): List<Pair<Float, Float>> {
        val w = bmp.width; val h = bmp.height

        val x0 = (w * 0.12f).toInt(); val x1 = (w * 0.76f).toInt()
        val y0 = (h * 0.25f).toInt(); val y1 = (h * 0.80f).toInt()
        val cell = 30
        val found = mutableListOf<Pair<Float, Float>>()

        // Posizione stimata del personaggio: centro-basso della zona di gioco
        val charX = w * 0.50f
        val charY = h * 0.58f
        val maxDist = w * 0.35f   // ridotto da 0.45 — solo oggetti vicini
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

                        // Yang: giallo-oro molto saturo
                        val yang = r > 210 && g > 150 && b < 70 && r - b > 150

                        // Nome personaggio: verde brillante
                        val playerName = g > 160 && g - r > 60 && g - b > 50 && r < 120

                        // Nome item: bianco caldo (R≥G≥B, tutti alti)
                        val itemName = r > 220 && g > 200 && b > 170 &&
                                r >= g && g >= b && (r - b) in 25..70

                        if (yang || playerName || itemName) hits++
                    }
                }

                // Soglia alzata a 5 (era 2) per ridurre falsi positivi
                if (hits >= 5) {
                    found.add(itemX to itemY)
                    if (found.size >= 8) return found.sortedByDescending { it.second }
                }
                cx += cell
            }
            cy += cell
        }
        return found.sortedByDescending { it.second }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS GESTI
    // ═══════════════════════════════════════════════════════════════════════════

    // Tap singolo su (x,y) per durationMs ms. Restituisce true se dispatched.
    private fun tapSingle(x: Float, y: Float, durationMs: Long): Boolean {
        return try {
            dispatchGesture(
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(
                        Path().apply { moveTo(x, y) }, 0L, durationMs))
                    .build(), null, null)
        } catch (_: Exception) { false }
    }

    // Multitouch: due dita contemporanee nello stesso GestureDescription.
    // Dito 1: (x1,y1) per dur1Ms; Dito 2: (x2,y2) per dur2Ms.
    // Android assegna pointer ID separati → coesistono senza conflitti.
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
            handler.postDelayed(scanner, 300L)
        handler.postDelayed(lootLoop, 700L)
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
