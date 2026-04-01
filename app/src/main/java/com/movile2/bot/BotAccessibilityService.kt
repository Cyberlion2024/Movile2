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
    @Volatile private var gestureInProgress = false

    // ── Stroke corrente dell'attacco (willContinue) ───────────────────────────
    // Tenuto in memoria per poter essere "continuato" nelle iterazioni successive
    // e per aggiungere la pozione come secondo dito nello stesso frame.
    private var currentAttackStroke: GestureDescription.StrokeDescription? = null

    // Durata di ogni "chunk" tenuto premuto prima di rinnovarlo (ms).
    // Deve essere < MAX_GESTURE_DURATION (60s). 8s è un buon compromesso.
    private val ATTACK_HOLD_MS = 8000L

    // ═══════════════════════════════════════════════════════════════════════════
    // LOOP ATTACCO — tiene premuto il tasto attacco continuamente
    //
    // Meccanismo willContinue=true:
    //   - Ogni StrokeDescription con willContinue=true crea un "dito" che rimane
    //     premuto finché non arriva un continueStroke con willContinue=false.
    //   - Prima iterazione: nuovo stroke da zero.
    //   - Iterazioni successive: continueStroke() riprende il dito già abbassato.
    //   - Stop: invia un ultimo continueStroke con willContinue=false → dito su.
    // ═══════════════════════════════════════════════════════════════════════════
    private val attackLoop = object : Runnable {
        override fun run() {
            if (!BotState.attackRunning) {
                releaseAttack()
                return
            }
            val pos = BotState.attackPos ?: return
            sendAttackChunk(pos.first, pos.second)
            // Rinnoviamo 200ms prima della scadenza per evitare gap
            handler.postDelayed(this, ATTACK_HOLD_MS - 200L)
        }
    }

    private fun sendAttackChunk(ax: Float, ay: Float) {
        val path = Path().apply { moveTo(ax, ay) }
        val prev = currentAttackStroke
        val stroke = try {
            if (prev != null) {
                prev.continueStroke(path, 0L, ATTACK_HOLD_MS, true)
            } else {
                GestureDescription.StrokeDescription(path, 0L, ATTACK_HOLD_MS, true)
            }
        } catch (_: Exception) {
            // continueStroke fallito (gesto interrotto da tocco reale) — ripartiamo da zero
            GestureDescription.StrokeDescription(path, 0L, ATTACK_HOLD_MS, true)
        }
        currentAttackStroke = stroke
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCancelled(g: GestureDescription?) {
                // Il tocco reale (joystick) ha interrotto il gesto.
                // Riparte immediatamente: se il dito è ancora giù verrà
                // cancellato di nuovo (onCancelled → retry), se è già su
                // il gesto parte e rimane attivo.
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
            val finalStroke = prev.continueStroke(path, 0L, 50L, false)
            val gesture = GestureDescription.Builder().addStroke(finalStroke).build()
            dispatchGesture(gesture, null, handler)
        } catch (_: Exception) {}
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LOOP POZIONE
    //
    // Se l'attacco è in corso (willContinue attivo), la pozione viene aggiunta
    // come secondo dito nello stesso GestureDescription (multitouch sintetico).
    // In questo modo i due "dita" hanno pointer ID diversi e non si cancellano.
    //
    // Se l'attacco NON è in corso, tap normale da 35ms.
    // ═══════════════════════════════════════════════════════════════════════════
    private val potionLoop = object : Runnable {
        override fun run() {
            if (!BotState.potionRunning) return
            val slots = BotState.potionSlots
            var delay = 0L
            for ((x, y) in slots) {
                handler.postDelayed({
                    if (BotState.potionRunning && !gestureInProgress) {
                        if (BotState.attackRunning) {
                            tapPotionWithAttack(x, y)
                        } else {
                            tapPotion(x, y)
                        }
                    }
                }, delay)
                delay += 250L
            }
            handler.postDelayed(this, BotState.potionIntervalMs.coerceAtLeast(500L) + delay)
        }
    }

    // Tap pozione come secondo dito mentre l'attacco è tenuto premuto.
    // Rinnova anche il chunk dell'attacco così il dito non si alza.
    private fun tapPotionWithAttack(px: Float, py: Float) {
        val pos = BotState.attackPos
        val prev = currentAttackStroke
        if (pos == null || prev == null) {
            tapPotion(px, py)
            return
        }
        val attackPath = Path().apply { moveTo(pos.first, pos.second) }
        val continuedAttack = try {
            prev.continueStroke(attackPath, 0L, ATTACK_HOLD_MS, true)
        } catch (_: Exception) {
            // Fallback: tap semplice
            tapPotion(px, py)
            return
        }
        currentAttackStroke = continuedAttack

        val potionPath = Path().apply { moveTo(px, py) }
        val potionStroke = GestureDescription.StrokeDescription(potionPath, 0L, 35L)

        try {
            val gesture = GestureDescription.Builder()
                .addStroke(continuedAttack)
                .addStroke(potionStroke)
                .build()
            dispatchGesture(gesture, null, handler)
        } catch (_: Exception) {
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
        val callback = object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                gestureInProgress = false
                handler.post { tapItemsSequentially(items, index + 1, onAllDone) }
            }
            override fun onCancelled(g: GestureDescription?) {
                gestureInProgress = false
                handler.postDelayed({ tapItemsSequentially(items, index + 1, onAllDone) }, 100L)
            }
        }

        val dispatched = dispatchLootTap(x, y, callback)
        if (!dispatched) {
            gestureInProgress = false
            handler.postDelayed({ tapItemsSequentially(items, index + 1, onAllDone) }, 150L)
        }
    }

    // ── Tap loot: secondo dito se attacco è in corso, tap normale altrimenti ──
    //
    // Quando attack è attivo (willContinue), qualsiasi dispatchGesture autonomo
    // cancellerebbe il dito attacco. Invece construiamo un GestureDescription con
    // due stroke: continueStroke(attacco) + loot tap, così il dito 1 rimane giù
    // e il dito 2 tappa l'oggetto. Il timer dell'attackLoop viene resincronizzato
    // dopo ogni chunk inviato.
    private fun dispatchLootTap(x: Float, y: Float, cb: GestureResultCallback): Boolean {
        val lootPath = Path().apply { moveTo(x, y) }
        val lootStroke = GestureDescription.StrokeDescription(lootPath, 0L, 60L)

        if (BotState.attackRunning) {
            val pos = BotState.attackPos
            val prev = currentAttackStroke
            if (pos != null && prev != null) {
                val attackPath = Path().apply { moveTo(pos.first, pos.second) }
                val continuedAttack = try {
                    prev.continueStroke(attackPath, 0L, ATTACK_HOLD_MS, true)
                } catch (_: Exception) { null }

                if (continuedAttack != null) {
                    currentAttackStroke = continuedAttack
                    val gesture = try {
                        GestureDescription.Builder()
                            .addStroke(continuedAttack)
                            .addStroke(lootStroke)
                            .build()
                    } catch (_: Exception) { null }

                    if (gesture != null) {
                        return dispatchGesture(gesture, cb, handler)
                    }
                }
            }
        }

        // Fallback: tap normale (attacco non attivo o stroke non disponibile)
        return dispatchGesture(
            GestureDescription.Builder().addStroke(lootStroke).build(), cb, handler)
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
    // ═══════════════════════════════════════════════════════════════════════════
    private fun findLootItems(bmp: Bitmap): List<Pair<Float, Float>> {
        val w = bmp.width; val h = bmp.height
        val x0 = (w * 0.08f).toInt();  val x1 = (w * 0.85f).toInt()
        val y0 = (h * 0.12f).toInt();  val y1 = (h * 0.78f).toInt()
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
                val dx = itemX - charX
                val dy = itemY - charY
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

    // ── Tap pozione — 35ms ────────────────────────────────────────────────────
    private fun tapPotion(x: Float, y: Float) {
        try {
            dispatchGesture(
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(
                        Path().apply { moveTo(x, y) }, 0L, 35L))
                    .build(), null, null)
        } catch (_: Exception) {}
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────
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
