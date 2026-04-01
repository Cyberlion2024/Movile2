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

    // ── Flag per evitare sovrapposizioni tra pozione e loot ───────────────────
    @Volatile private var gestureInProgress = false

    // ═══════════════════════════════════════════════════════════════════════════
    // LOOP POZIONE
    //
    // Usa tapPotion (35ms). Non sparare se un loot-tap è in corso.
    // ═══════════════════════════════════════════════════════════════════════════
    private val potionLoop = object : Runnable {
        override fun run() {
            if (!BotState.potionRunning) return
            val slots = BotState.potionSlots
            var delay = 0L
            for ((x, y) in slots) {
                handler.postDelayed({
                    if (BotState.potionRunning && !gestureInProgress) tapPotion(x, y)
                }, delay)
                delay += 250L
            }
            handler.postDelayed(this, BotState.potionIntervalMs.coerceAtLeast(500L) + delay)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LOOP RACCOLTA — tap sequenziali con callback
    //
    // Problema del multi-stroke (versione precedente):
    //   Un singolo GestureDescription con 10 stroke da 350ms l'uno dura 3.5s.
    //   Durante quei 3.5s Android blocca OGNI altro dispatchGesture (incluse
    //   le pozioni). Se viene richiesto un secondo gesto, ENTRAMBI falliscono
    //   silenziosamente (dispatchGesture restituisce false).
    //
    // Soluzione: un tap per volta con GestureResultCallback.
    //   onCompleted → il gesto è finito → passiamo al prossimo item.
    //   onCancelled → qualcosa ha interrotto → saltiamo e andiamo avanti.
    //   Così il loop è coordinato con il sistema Android e non blocca le pozioni.
    // ═══════════════════════════════════════════════════════════════════════════
    private val lootLoop = object : Runnable {
        override fun run() {
            if (!BotState.lootRunning) return
            val items = lootTargets
            BotState.lootItemsFound = items.size

            if (items.isEmpty()) {
                handler.postDelayed(this, 500L)
                return
            }

            tapItemsSequentially(items, 0) {
                if (BotState.lootRunning)
                    handler.postDelayed(this, 300L)
            }
        }
    }

    private fun tapItemsSequentially(
        items: List<Pair<Float, Float>>,
        index: Int,
        onAllDone: () -> Unit
    ) {
        if (!BotState.lootRunning || index >= items.size) {
            onAllDone()
            return
        }
        val (x, y) = items[index]
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 60L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        gestureInProgress = true
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                gestureInProgress = false
                handler.post { tapItemsSequentially(items, index + 1, onAllDone) }
            }
            override fun onCancelled(g: GestureDescription?) {
                gestureInProgress = false
                handler.postDelayed({ tapItemsSequentially(items, index + 1, onAllDone) }, 100L)
            }
        }, handler)

        if (!dispatched) {
            gestureInProgress = false
            handler.postDelayed({ tapItemsSequentially(items, index + 1, onAllDone) }, 150L)
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
    // Dallo screenshot analizzato: nel gioco compaiono questi tipi di testo:
    //   - VERDE:       nome del personaggio sopra gli oggetti di sua proprietà
    //   - GIALLO/ORO:  label "Yang" per la valuta
    //   - BIANCO/CREMA: nomi degli item (Corazza dello..., ecc.)
    //
    // Raccoglie tutti e tre i tipi entro il 45% dal centro schermo.
    //
    // Soglie pixel:
    //   Nome personaggio (verde):  G>140, G-R>40, G-B>30, R<150
    //   Yang (giallo-oro):         R>185, G>140, B<110, R>G-30, R>B+70
    //   Item name (bianco/crema):  R>200, G>190, B>170, tutti alti ma R≥G≥B
    //                              (bianco caldo — esclude sfondo chiaro del gioco)
    //
    // Min 2 pixel per cella, celle 30×30 px.
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
                        val r = Color.red(p)
                        val g = Color.green(p)
                        val b = Color.blue(p)

                        // Nome personaggio in verde
                        val playerName = g > 140 && g - r > 40 && g - b > 30 && r < 150

                        // Yang: giallo-oro (anche variante crema/arancio)
                        val yang = r > 185 && g > 140 && b < 110 && r > b + 70

                        // Nome item: bianco caldo (R≥G≥B, tutti alti)
                        // Il "bianco caldo" dei nomi item si distingue dallo sfondo
                        // chiaro del gioco perché il testo è piccolo e compatto
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

    // ── Tap raccolta oggetti — 60ms ───────────────────────────────────────────
    private fun tap(x: Float, y: Float) {
        try {
            dispatchGesture(
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(
                        Path().apply { moveTo(x, y) }, 0L, 60L))
                    .build(), null, null)
        } catch (_: Exception) {}
    }

    // ── Tap pozione — 35ms ────────────────────────────────────────────────────
    // Abbastanza lungo da essere registrato dal gioco (minimo ~20ms).
    // Abbastanza corto da non bloccare il tocco reale tenuto premuto (attacco).
    // NON viene sparato se gestureInProgress è true (loot in corso).
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
    override fun onInterrupt() { stopPotion(); stopLoot() }
    override fun onDestroy() {
        super.onDestroy()
        stopPotion(); stopLoot()
        if (instance === this) instance = null
    }

    companion object {
        var instance: BotAccessibilityService? = null
            private set
    }
}
