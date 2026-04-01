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
    private val ATTACK_TAP_MS  = 40L
    private val ATTACK_GAP_MS  = 280L
    private val POTION_TAP_MS  = 35L

    // ═══════════════════════════════════════════════════════════════════════════
    // LOOP ATTACCO
    // Se joystickActive → salta il dispatch ma ripianifica (ripende quando
    // joystick viene rilasciato tramite resumeAll()).
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
    // Se joystickActive → skip, la prossima iterazione pianificata riprenderà.
    // In modalità attacco manuale: re-tap sull'attacco dopo la pozione.
    // ═══════════════════════════════════════════════════════════════════════════
    private val potionLoop = object : Runnable {
        override fun run() {
            if (!BotState.potionRunning) return
            val slots = BotState.potionSlots
            var delay = 0L
            for ((px, py) in slots) {
                handler.postDelayed({
                    if (!BotState.potionRunning || gestureInProgress) return@postDelayed
                    if (BotState.joystickActive) return@postDelayed  // pausa joystick
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
                        val aPos = BotState.attackPos
                        if (aPos != null) {
                            handler.postDelayed({
                                if (BotState.potionRunning && !BotState.attackRunning
                                    && !BotState.joystickActive) {
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
            if (BotState.joystickActive) { handler.postDelayed(this, 300L); return }
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
        if (!BotState.lootRunning || BotState.joystickActive || index >= items.size) {
            onAllDone(); return
        }
        val (x, y) = items[index]
        gestureInProgress = true
        val aPos = BotState.attackPos
        val dispatched: Boolean
        if (BotState.attackRunning && aPos != null) {
            dispatched = tapMultitouch(aPos.first, aPos.second, ATTACK_TAP_MS, x, y, 55L)
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
    // SCANNER SCREENSHOT — ogni 400ms
    // ═══════════════════════════════════════════════════════════════════════════
    private val scanner = object : Runnable {
        override fun run() {
            if (!BotState.lootRunning) return
            if (!BotState.joystickActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                doScan()
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
    // RILEVAMENTO OGGETTI A TERRA — v3 (conservativo)
    //
    // Zona ristretta al centro dello schermo dove cadono i drop:
    //   X: 22%..68%  (esclude joystick sx, skill dx e HUD laterali)
    //   Y: 32%..72%  (esclude barra HP top e bottoni skill bottom)
    //
    // Solo yang oro (il colore più affidabile e distinto):
    //   Yang brillante : R>205, G>145, B<75, R-B>140
    //   Yang ambra     : R>185, G>115, B<55, R-B>140, R > G*1.45
    //   Nome item bianco caldo: R>220, G>200, B>165, R≥G≥B, R-B in [22,75]
    //
    // Soglia: 4 pixel per cella 32×32 (step 3 → ~110 campioni max)
    // Raggio max: 22% larghezza schermo dal centro personaggio
    // Max 5 bersagli, ordinati dal più vicino al più lontano.
    //
    // La categoria "brightWarm" generica è stata rimossa perché
    // causava falsi positivi su elementi UI (barre, testi, icone).
    // ═══════════════════════════════════════════════════════════════════════════
    private fun findLootItems(bmp: Bitmap): List<Pair<Float, Float>> {
        val w = bmp.width; val h = bmp.height

        val x0 = (w * 0.22f).toInt(); val x1 = (w * 0.68f).toInt()
        val y0 = (h * 0.32f).toInt(); val y1 = (h * 0.72f).toInt()
        val cell = 32
        val found = mutableListOf<Pair<Float, Float>>()

        val charX = w * 0.50f
        val charY = h * 0.58f
        val maxDist = w * 0.22f
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

                        val yangBright = r > 205 && g > 145 && b < 75 && r - b > 140
                        val yangAmber  = r > 185 && g > 115 && b < 55 &&
                                r - b > 140 && r > (g * 1.45f).toInt()
                        val itemName   = r > 220 && g > 200 && b > 165 &&
                                r >= g && g >= b && (r - b) in 22..75

                        if (yangBright || yangAmber || itemName) hits++
                    }
                }

                if (hits >= 4) {
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
    // JOYSTICK — FORWARDING GESTURE CHAIN
    //
    // L'overlay joystick in OverlayService intercetta i touch dell'utente,
    // chiama startJoystickForward / updateJoystick / stopJoystickForward.
    //
    // Il forwarding usa StrokeDescription.continueStroke() per creare
    // una catena di gesture brevi (80ms ciascuna) che il gioco vede come
    // un singolo tocco continuo (joystick tenuto premuto + mosso).
    //
    // I loop attack/potion/loot controllano joystickActive e saltano
    // quando è true. resumeAll() li risveglia immediatamente al rilascio.
    // ═══════════════════════════════════════════════════════════════════════════
    @Volatile private var joystickForwarding = false
    @Volatile private var joystickCurrentX = 0f
    @Volatile private var joystickCurrentY = 0f
    private var activeJoystickStroke: GestureDescription.StrokeDescription? = null

    private val JOYSTICK_TICK_MS = 80L

    private val joystickCallback = object : GestureResultCallback() {
        override fun onCompleted(g: GestureDescription?) { continueJoystickChain() }
        override fun onCancelled(g: GestureDescription?) { continueJoystickChain() }
    }

    private fun continueJoystickChain() {
        if (!joystickForwarding) return
        val prev = activeJoystickStroke ?: return
        val x = joystickCurrentX; val y = joystickCurrentY
        val path = Path().apply { moveTo(x, y) }
        try {
            val next = prev.continueStroke(path, 0L, JOYSTICK_TICK_MS, true)
            activeJoystickStroke = next
            dispatchGesture(
                GestureDescription.Builder().addStroke(next).build(),
                joystickCallback, handler)
        } catch (_: Exception) {
            // Se continueStroke fallisce, riparte con una nuova gesture
            if (joystickForwarding) startJoystickGesture(x, y)
        }
    }

    private fun startJoystickGesture(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, JOYSTICK_TICK_MS, true)
        activeJoystickStroke = stroke
        try {
            dispatchGesture(
                GestureDescription.Builder().addStroke(stroke).build(),
                joystickCallback, handler)
        } catch (_: Exception) {}
    }

    fun startJoystickForward(x: Float, y: Float) {
        joystickForwarding = true
        BotState.joystickActive = true
        joystickCurrentX = x
        joystickCurrentY = y
        startJoystickGesture(x, y)
    }

    fun updateJoystick(x: Float, y: Float) {
        joystickCurrentX = x
        joystickCurrentY = y
    }

    fun stopJoystickForward(x: Float, y: Float) {
        joystickForwarding = false
        BotState.joystickActive = false
        val prev = activeJoystickStroke
        activeJoystickStroke = null
        if (prev != null) {
            val path = Path().apply { moveTo(x, y) }
            try {
                val finalStroke = prev.continueStroke(path, 0L, 30L, false)
                dispatchGesture(
                    GestureDescription.Builder().addStroke(finalStroke).build(),
                    null, null)
            } catch (_: Exception) {}
        }
        // Riprende immediatamente tutti i loop attivi
        resumeAll()
    }

    // Risveglia immediatamente tutti i loop attivi dopo la pausa joystick
    private fun resumeAll() {
        if (BotState.attackRunning) {
            handler.removeCallbacks(attackLoop)
            handler.post(attackLoop)
        }
        if (BotState.potionRunning) {
            handler.removeCallbacks(potionLoop)
            handler.post(potionLoop)
        }
        if (BotState.lootRunning) {
            handler.removeCallbacks(lootLoop)
            handler.post(lootLoop)
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
        joystickForwarding = false
        BotState.joystickActive = false
        if (instance === this) instance = null
    }

    companion object {
        var instance: BotAccessibilityService? = null
            private set
    }
}
