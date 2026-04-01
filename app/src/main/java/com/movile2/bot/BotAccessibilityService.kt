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

    // ── Gestione gesti attacco ────────────────────────────────────────────────
    private var currentAttackStroke: GestureDescription.StrokeDescription? = null
    private val ATTACK_HOLD_MS = 8000L

    // Contatore "auto-cancellazioni": ogni volta che dispatchiamo una continuation
    // Android chiama onCancelled sul gesto precedente. Se selfCancellingCount > 0
    // significa che siamo stati noi stessi a causare la cancellazione (pozione/loot/
    // rinnovo chunk) e NON dobbiamo riavviare l'attacco. Solo se il contatore è 0
    // la cancellazione viene da un tocco esterno (joystick) → riavvio immediato.
    // Tutto gira sullo stesso thread (Handler) quindi nessuna race condition.
    private var selfCancellingCount = 0

    // Flag per evitare che loot e pozione si sovrappongano sul "dito 2"
    private var gestureInProgress = false

    // ═══════════════════════════════════════════════════════════════════════════
    // LOOP ATTACCO
    // ═══════════════════════════════════════════════════════════════════════════
    private val attackLoop = object : Runnable {
        override fun run() {
            if (!BotState.attackRunning) { releaseAttack(); return }
            val pos = BotState.attackPos ?: return
            sendAttackChunk(pos.first, pos.second)
            handler.postDelayed(this, ATTACK_HOLD_MS - 200L)
        }
    }

    private fun sendAttackChunk(ax: Float, ay: Float) {
        val path = Path().apply { moveTo(ax, ay) }
        val prev = currentAttackStroke
        val stroke = try {
            if (prev != null) {
                // Stiamo inviando una continuation — il callback del gesto precedente
                // riceverà onCancelled: dobbiamo ignorarlo (non è il joystick).
                selfCancellingCount++
                prev.continueStroke(path, 0L, ATTACK_HOLD_MS, true)
            } else {
                GestureDescription.StrokeDescription(path, 0L, ATTACK_HOLD_MS, true)
            }
        } catch (_: Exception) {
            if (prev != null) selfCancellingCount-- // rollback se continueStroke lancia
            GestureDescription.StrokeDescription(path, 0L, ATTACK_HOLD_MS, true)
        }
        currentAttackStroke = stroke
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(),
            object : GestureResultCallback() {
                override fun onCancelled(g: GestureDescription?) {
                    if (selfCancellingCount > 0) {
                        // Cancellazione causata da nostra continuation — ignoriamo.
                        selfCancellingCount--
                        return
                    }
                    // Cancellazione esterna (joystick o altro tocco reale).
                    // Ripartiamo subito: se il dito è ancora giù → onCancelled di nuovo;
                    // appena si alza → il gesto va a buon fine.
                    if (!BotState.attackRunning) return
                    currentAttackStroke = null
                    handler.removeCallbacks(attackLoop)
                    handler.post(attackLoop)
                }
            }, handler)
    }

    private fun releaseAttack() {
        val prev = currentAttackStroke ?: return
        currentAttackStroke = null
        val pos = BotState.attackPos ?: return
        val path = Path().apply { moveTo(pos.first, pos.second) }
        try {
            selfCancellingCount++
            val finalStroke = prev.continueStroke(path, 0L, 50L, false)
            dispatchGesture(GestureDescription.Builder().addStroke(finalStroke).build(),
                null, handler)
        } catch (_: Exception) { selfCancellingCount-- }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LOOP POZIONE
    // ═══════════════════════════════════════════════════════════════════════════
    private val potionLoop = object : Runnable {
        override fun run() {
            if (!BotState.potionRunning) return
            val slots = BotState.potionSlots
            var delay = 0L
            for ((x, y) in slots) {
                handler.postDelayed({
                    if (BotState.potionRunning && !gestureInProgress) {
                        if (BotState.attackRunning) tapPotionWithAttack(x, y)
                        else tapPotion(x, y)
                    }
                }, delay)
                delay += 250L
            }
            handler.postDelayed(this, BotState.potionIntervalMs.coerceAtLeast(500L) + delay)
        }
    }

    // Pozione come secondo dito mentre l'attacco è tenuto premuto.
    // selfCancellingCount++ perché questa dispatch causa onCancelled sul gesto attuale.
    private fun tapPotionWithAttack(px: Float, py: Float) {
        val pos = BotState.attackPos
        val prev = currentAttackStroke
        if (pos == null || prev == null) { tapPotion(px, py); return }

        val continuedAttack = try {
            selfCancellingCount++
            prev.continueStroke(Path().apply { moveTo(pos.first, pos.second) },
                0L, ATTACK_HOLD_MS, true)
        } catch (_: Exception) {
            selfCancellingCount--
            tapPotion(px, py)
            return
        }
        currentAttackStroke = continuedAttack

        try {
            val gesture = GestureDescription.Builder()
                .addStroke(continuedAttack)
                .addStroke(GestureDescription.StrokeDescription(
                    Path().apply { moveTo(px, py) }, 0L, 35L))
                .build()
            dispatchGesture(gesture, null, handler)
        } catch (_: Exception) {
            selfCancellingCount--
            tapPotion(px, py)
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
        val next = {
            gestureInProgress = false
            handler.post { tapItemsSequentially(items, index + 1, onAllDone) }
        }
        val nextDelayed = {
            gestureInProgress = false
            handler.postDelayed({ tapItemsSequentially(items, index + 1, onAllDone) }, 100L)
        }

        dispatchLootTap(x, y,
            onCompleted = { next() },
            onFailed    = { nextDelayed() })
    }

    // Tap loot: secondo dito se attacco attivo, tap normale altrimenti.
    //
    // Problema callback: con il gesto combinato (continueStroke attacco 8s + loot 60ms)
    // Android chiamerebbe onCompleted solo dopo 8s (il gesto più lungo).
    // Fix: passiamo null come callback e impostiamo manualmente un delay di 100ms
    // così tapItemsSequentially avanza subito dopo il tap loot, non dopo 8s.
    private fun dispatchLootTap(
        x: Float, y: Float,
        onCompleted: () -> Unit,
        onFailed: () -> Unit
    ) {
        val lootStroke = GestureDescription.StrokeDescription(
            Path().apply { moveTo(x, y) }, 0L, 60L)

        if (BotState.attackRunning) {
            val pos = BotState.attackPos
            val prev = currentAttackStroke
            if (pos != null && prev != null) {
                val continuedAttack = try {
                    selfCancellingCount++
                    prev.continueStroke(Path().apply { moveTo(pos.first, pos.second) },
                        0L, ATTACK_HOLD_MS, true)
                } catch (_: Exception) {
                    selfCancellingCount--
                    null
                }
                if (continuedAttack != null) {
                    currentAttackStroke = continuedAttack
                    val gesture = try {
                        GestureDescription.Builder()
                            .addStroke(continuedAttack)
                            .addStroke(lootStroke)
                            .build()
                    } catch (_: Exception) { null }

                    if (gesture != null) {
                        val ok = dispatchGesture(gesture, null, handler)
                        if (ok) {
                            // Avanziamo dopo 100ms (tap 60ms + margine),
                            // senza aspettare i 8s dell'attacco.
                            handler.postDelayed({ onCompleted() }, 100L)
                            return
                        }
                    }
                    // dispatch fallito: rollback counter
                    selfCancellingCount--
                    currentAttackStroke = prev
                }
            }
        }

        // Fallback: tap normale (attacco non attivo o stroke non disponibile)
        val cb = object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { onCompleted() }
            override fun onCancelled(g: GestureDescription?) { onFailed() }
        }
        if (!dispatchGesture(
                GestureDescription.Builder().addStroke(lootStroke).build(), cb, handler)) {
            onFailed()
        }
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
    // Dallo screenshot: Yang ha label "Yang" con testo giallo-oro.
    // Il nome personaggio sopra gli oggetti è verde.
    // I nomi item sono bianco caldo.
    // ═══════════════════════════════════════════════════════════════════════════
    private fun findLootItems(bmp: Bitmap): List<Pair<Float, Float>> {
        val w = bmp.width; val h = bmp.height
        val x0 = (w * 0.08f).toInt(); val x1 = (w * 0.85f).toInt()
        val y0 = (h * 0.12f).toInt(); val y1 = (h * 0.78f).toInt()
        val cell = 30
        val found = mutableListOf<Pair<Float, Float>>()

        val charX = w * 0.50f
        val charY = h * 0.55f
        val maxDist = w * 0.45f
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
                        val playerName = g > 140 && g - r > 40 && g - b > 30 && r < 150
                        val yang = r > 185 && g > 140 && b < 110 && r > b + 70
                        val itemName = r > 210 && g > 190 && b > 160 &&
                                r >= g && g >= b && r - b > 20 && r - b < 80
                        if (playerName || yang || itemName) hits++
                    }
                }
                if (hits >= 2) {
                    found.add(itemX to itemY)
                    if (found.size >= 10) return found.sortedByDescending { it.second }
                }
                cx += cell
            }
            cy += cell
        }
        return found.sortedByDescending { it.second }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // API PUBBLICA
    // ═══════════════════════════════════════════════════════════════════════════
    fun startAttack() {
        if (BotState.attackPos == null) return
        if (BotState.attackRunning) stopAttack()
        selfCancellingCount = 0
        currentAttackStroke = null
        BotState.attackRunning = true
        handler.post(attackLoop)
    }

    fun stopAttack() {
        BotState.attackRunning = false
        handler.removeCallbacks(attackLoop)
        releaseAttack()
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

    private fun tapPotion(x: Float, y: Float) {
        try {
            dispatchGesture(
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(
                        Path().apply { moveTo(x, y) }, 0L, 35L))
                    .build(), null, null)
        } catch (_: Exception) {}
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
