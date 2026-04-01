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

    private val ATTACK_TAP_MS = 40L
    private val ATTACK_GAP_MS = 280L
    private val POTION_TAP_MS = 35L

    // ═══════════════════════════════════════════════════════════════════════════
    // LOOP ATTACCO
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
                            Path().apply { moveTo(pos.first, pos.second) },
                            0L, ATTACK_TAP_MS))
                        .build(), attackCallback, handler)
                if (!ok) scheduleNextAttack()
            } catch (_: Exception) { scheduleNextAttack() }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LOOP POZIONE
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
                    if (BotState.attackRunning) {
                        val aPos = BotState.attackPos
                        if (aPos != null) tapMultitouch(aPos.first, aPos.second, ATTACK_TAP_MS, px, py, POTION_TAP_MS)
                        else tapSingle(px, py, POTION_TAP_MS)
                    } else {
                        tapSingle(px, py, POTION_TAP_MS)
                        val aPos = BotState.attackPos
                        if (aPos != null) {
                            handler.postDelayed({
                                if (BotState.potionRunning && !BotState.attackRunning && !BotState.joystickActive)
                                    tapSingle(aPos.first, aPos.second, 60L)
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
        val aPos = BotState.attackPos
        val dispatched = if (BotState.attackRunning && aPos != null)
            tapMultitouch(aPos.first, aPos.second, ATTACK_TAP_MS, x, y, 55L)
        else tapSingle(x, y, 55L)
        gestureInProgress = false
        handler.postDelayed({ tapItemsSequentially(items, index + 1, onAllDone) }, if (dispatched) 100L else 130L)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SCANNER SCREENSHOT — ogni 400ms
    // ═══════════════════════════════════════════════════════════════════════════
    private val scanner = object : Runnable {
        override fun run() {
            if (!BotState.lootRunning) return
            if (!BotState.joystickActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) doScan()
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
    // RILEVAMENTO OGGETTI A TERRA — v4
    //
    // Zona ristretta al centro dove cadono i drop in Metin2 mobile:
    //   X: 30%..70%   Y: 35%..70%
    //
    // Colori strettissimi per evitare falsi positivi da animazioni/UI:
    //   Yang puro:  R>240, G>185, B<20   (oro saturo, zero azzurro)
    //   Item puro:  R>248, G>248, B>248  (bianco quasi puro — testo item)
    //
    // Cella 20×20, step 3 → ~49 campioni max. Soglia: 2 pixel.
    // Max 5 bersagli, dal più vicino al più lontano dal personaggio.
    // ═══════════════════════════════════════════════════════════════════════════
    private fun findLootItems(bmp: Bitmap): List<Pair<Float, Float>> {
        val w = bmp.width; val h = bmp.height

        val x0 = (w * 0.30f).toInt(); val x1 = (w * 0.70f).toInt()
        val y0 = (h * 0.35f).toInt(); val y1 = (h * 0.70f).toInt()
        val cell = 20
        val found = mutableListOf<Pair<Float, Float>>()

        val charX = w * 0.50f
        val charY = h * 0.57f
        val maxDist = w * 0.18f
        val maxDistSq = maxDist * maxDist

        var cy = y0
        while (cy + cell <= y1) {
            var cx = x0
            while (cx + cell <= x1) {
                val itemX = (cx + cell / 2).toFloat()
                val itemY = (cy + cell / 2).toFloat()
                val ddx = itemX - charX; val ddy = itemY - charY
                if (ddx * ddx + ddy * ddy > maxDistSq) { cx += cell; continue }

                var hits = 0
                for (dy2 in 0 until cell step 3) {
                    for (dx2 in 0 until cell step 3) {
                        val p = bmp.getPixel(cx + dx2, cy + dy2)
                        val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
                        val yang = r > 240 && g > 185 && b < 20
                        val item = r > 248 && g > 248 && b > 248
                        if (yang || item) hits++
                    }
                }

                if (hits >= 2) {
                    found.add(itemX to itemY)
                    if (found.size >= 5) break
                }
                cx += cell
            }
            if (found.size >= 5) break
            cy += cell
        }

        return found.sortedBy { (fx, fy) ->
            val ddx = fx - charX; val ddy = fy - charY
            ddx * ddx + ddy * ddy
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // JOYSTICK — FORWARDING TIMER-BASED
    //
    // Bug della versione precedente: la catena callback continueStroke si rompe
    // se il callback arriva DOPO che lo stroke è già scaduto → continueStroke
    // lancia eccezione → startJoystickGesture crea un nuovo DOWN → il gioco
    // vede due touch distinti → joystickActive rimane true per sempre.
    //
    // Soluzione: approccio timer-based. Un Runnable scatta ogni TICK_MS (80ms)
    // e invia uno stroke da STROKE_MS (120ms). Poiché TICK < STROKE c'è sempre
    // sovrapposizione → il gioco vede un tocco continuo senza gap.
    //
    // Safety: se nessun updateJoystick() arriva per SAFETY_MS (2500ms),
    // forziamo la fine come se l'utente avesse alzato il dito.
    // ═══════════════════════════════════════════════════════════════════════════
    @Volatile private var joystickForwarding = false
    @Volatile private var joystickCurrentX = 0f
    @Volatile private var joystickCurrentY = 0f
    @Volatile private var joystickLastUpdateMs = 0L
    private var activeJoystickStroke: GestureDescription.StrokeDescription? = null

    private val TICK_MS   = 80L
    private val STROKE_MS = 120L
    private val SAFETY_MS = 2500L

    private val joystickTickRunnable = object : Runnable {
        override fun run() {
            if (!joystickForwarding) return

            // Safety: nessun aggiornamento da SAFETY_MS → dito alzato senza ACTION_UP
            if (System.currentTimeMillis() - joystickLastUpdateMs > SAFETY_MS) {
                forceStopJoystick()
                return
            }

            val x = joystickCurrentX; val y = joystickCurrentY
            val path = Path().apply { moveTo(x, y) }
            val prev = activeJoystickStroke

            if (prev == null) {
                // Prima stroke
                val s = GestureDescription.StrokeDescription(path, 0L, STROKE_MS, true)
                activeJoystickStroke = s
                try { dispatchGesture(GestureDescription.Builder().addStroke(s).build(), null, null) }
                catch (_: Exception) { activeJoystickStroke = null }
            } else {
                // Continua la stroke precedente
                try {
                    val next = prev.continueStroke(path, 0L, STROKE_MS, true)
                    activeJoystickStroke = next
                    dispatchGesture(GestureDescription.Builder().addStroke(next).build(), null, null)
                } catch (_: Exception) {
                    // Stroke precedente già scaduta: ne partiamo una nuova
                    activeJoystickStroke = null
                    val s = GestureDescription.StrokeDescription(path, 0L, STROKE_MS, true)
                    activeJoystickStroke = s
                    try { dispatchGesture(GestureDescription.Builder().addStroke(s).build(), null, null) }
                    catch (_: Exception) { activeJoystickStroke = null }
                }
            }

            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun forceStopJoystick() {
        handler.removeCallbacks(joystickTickRunnable)
        val prev = activeJoystickStroke
        joystickForwarding = false
        BotState.joystickActive = false
        activeJoystickStroke = null
        if (prev != null) {
            val path = Path().apply { moveTo(joystickCurrentX, joystickCurrentY) }
            try {
                val fin = prev.continueStroke(path, 0L, 30L, false)
                dispatchGesture(GestureDescription.Builder().addStroke(fin).build(), null, null)
            } catch (_: Exception) {}
        }
        resumeAll()
    }

    fun startJoystickForward(x: Float, y: Float) {
        handler.removeCallbacks(joystickTickRunnable)
        joystickForwarding = true
        BotState.joystickActive = true
        joystickCurrentX = x
        joystickCurrentY = y
        joystickLastUpdateMs = System.currentTimeMillis()
        activeJoystickStroke = null
        handler.post(joystickTickRunnable)
    }

    fun updateJoystick(x: Float, y: Float) {
        joystickCurrentX = x
        joystickCurrentY = y
        joystickLastUpdateMs = System.currentTimeMillis()
    }

    fun stopJoystickForward(x: Float, y: Float) {
        joystickCurrentX = x
        joystickCurrentY = y
        forceStopJoystick()
    }

    private fun resumeAll() {
        if (BotState.attackRunning) {
            handler.removeCallbacks(attackLoop); handler.post(attackLoop)
        }
        if (BotState.potionRunning) {
            handler.removeCallbacks(potionLoop); handler.post(potionLoop)
        }
        if (BotState.lootRunning) {
            handler.removeCallbacks(lootLoop); handler.post(lootLoop)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) handler.postDelayed(scanner, 200L)
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
        handler.removeCallbacks(joystickTickRunnable)
        joystickForwarding = false
        BotState.joystickActive = false
        if (instance === this) instance = null
    }

    companion object {
        var instance: BotAccessibilityService? = null
            private set
    }
}
