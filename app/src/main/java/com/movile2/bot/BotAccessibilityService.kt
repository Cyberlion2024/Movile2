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
    private val SKILL_TAP_MS  = 40L

    // ═══════════════════════════════════════════════════════════════════════════
    // LOOP ATTACCO — tappa solo quando c'è un mostro (nome rosso) vicino
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
            // Attacca solo se è stato rilevato un mostro con nome rosso
            if (!BotState.mobNearby) { handler.postDelayed(this, 300L); return }
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
    // SCANNER MOSTRI — ogni 500ms cerca nomi rossi vicini al personaggio
    // Colore nome mostro nemico: R>160, G<130, B<120
    // ═══════════════════════════════════════════════════════════════════════════
    private val mobScanner = object : Runnable {
        override fun run() {
            if (!BotState.attackRunning) return
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
                    bmp?.let { b -> BotState.mobNearby = findRedMobNearby(b); b.recycle() }
                }
                override fun onFailure(e: Int) { BotState.mobNearby = false }
            })
    }

    private fun findRedMobNearby(bmp: Bitmap): Boolean {
        val w = bmp.width; val h = bmp.height
        // Zona di ricerca: 40% centrale dello schermo (dove appaiono i nomi mostri)
        val x0 = (w * 0.15f).toInt(); val x1 = (w * 0.85f).toInt()
        val y0 = (h * 0.20f).toInt(); val y1 = (h * 0.65f).toInt()
        // Centro personaggio
        val charX = w * 0.50f; val charY = h * 0.57f
        val maxDist = w * 0.35f; val maxDistSq = maxDist * maxDist
        val step = 8
        var redPixels = 0
        var cy = y0
        while (cy < y1) {
            var cx = x0
            while (cx < x1) {
                val dx = cx - charX; val dy = cy - charY
                if (dx * dx + dy * dy <= maxDistSq) {
                    val p = bmp.getPixel(cx, cy)
                    val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
                    // Nome mostro nemico: rosso vivace
                    if (r > 160 && g < 130 && b < 120 && r - g > 60) {
                        redPixels++
                        if (redPixels >= 4) return true
                    }
                }
                cx += step
            }
            cy += step
        }
        return false
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LOOP POZIONE — quando attacco è OFF usa SOLO la pozione, niente attacco
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
                        // Attacco attivo: multitouch pozione + attacco insieme
                        val aPos = BotState.attackPos
                        if (aPos != null) tapMultitouch(aPos.first, aPos.second, ATTACK_TAP_MS, px, py, POTION_TAP_MS)
                        else tapSingle(px, py, POTION_TAP_MS)
                    } else {
                        // Solo pozione: nessun tap di attacco
                        tapSingle(px, py, POTION_TAP_MS)
                    }
                }, delay)
                delay += 320L
            }
            handler.postDelayed(this, BotState.potionIntervalMs.coerceAtLeast(500L) + delay)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LOOP ABILITÀ — tappa tutti gli slot abilità ogni skillIntervalMs
    // ═══════════════════════════════════════════════════════════════════════════
    private val skillLoop = object : Runnable {
        override fun run() {
            if (!BotState.skillsRunning) return
            val slots = BotState.skillSlots
            var delay = 0L
            for ((sx, sy) in slots) {
                handler.postDelayed({
                    if (!BotState.skillsRunning || BotState.joystickActive) return@postDelayed
                    tapSingle(sx, sy, SKILL_TAP_MS)
                }, delay)
                delay += 150L
            }
            handler.postDelayed(this, BotState.skillIntervalMs.coerceAtLeast(500L) + delay)
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
    // Yang puro: R>240, G>185, B<20
    // Item bianco: R>248, G>248, B>248
    // ═══════════════════════════════════════════════════════════════════════════
    private fun findLootItems(bmp: Bitmap): List<Pair<Float, Float>> {
        val w = bmp.width; val h = bmp.height
        val x0 = (w * 0.30f).toInt(); val x1 = (w * 0.70f).toInt()
        val y0 = (h * 0.35f).toInt(); val y1 = (h * 0.70f).toInt()
        val cell = 20
        val found = mutableListOf<Pair<Float, Float>>()
        val charX = w * 0.50f; val charY = h * 0.57f
        val maxDist = w * 0.18f; val maxDistSq = maxDist * maxDist

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
                        if ((r > 240 && g > 185 && b < 20) || (r > 248 && g > 248 && b > 248)) hits++
                    }
                }
                if (hits >= 2) { found.add(itemX to itemY); if (found.size >= 5) break }
                cx += cell
            }
            if (found.size >= 5) break
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
            handler.removeCallbacks(skillLoop); handler.post(skillLoop)
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
        BotState.mobNearby = false
        handler.post(mobScanner)
        handler.post(attackLoop)
    }

    fun stopAttack() {
        BotState.attackRunning = false
        BotState.mobNearby = false
        handler.removeCallbacks(attackLoop)
        handler.removeCallbacks(mobScanner)
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

    fun startSkills(intervalMs: Long = BotState.skillIntervalMs) {
        if (BotState.skillSlots.isEmpty()) return
        if (BotState.skillsRunning) stopSkills()
        BotState.skillIntervalMs = intervalMs.coerceAtLeast(500L)
        BotState.skillsRunning = true; handler.post(skillLoop)
    }

    fun stopSkills() {
        BotState.skillsRunning = false; handler.removeCallbacks(skillLoop)
    }

    fun startLoot() {
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

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() { stopAttack(); stopPotion(); stopSkills(); stopLoot() }
    override fun onDestroy() {
        super.onDestroy()
        stopAttack(); stopPotion(); stopSkills(); stopLoot()
        BotState.joystickActive = false
        if (instance === this) instance = null
    }

    companion object {
        var instance: BotAccessibilityService? = null
            private set
    }
}
