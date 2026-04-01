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

    // ═══════════════════════════════════════════════════════════════════════════
    // LOOP POZIONE
    // Usa tapLight (1ms) invece di 60ms per NON interferire con il tocco
    // reale tenuto premuto dall'utente (es. tasto attacco).
    // ═══════════════════════════════════════════════════════════════════════════
    private val potionLoop = object : Runnable {
        override fun run() {
            if (!BotState.potionRunning) return
            val slots = BotState.potionSlots
            var delay = 0L
            for ((x, y) in slots) {
                handler.postDelayed({ if (BotState.potionRunning) tapLight(x, y) }, delay)
                delay += 200L
            }
            handler.postDelayed(this, BotState.potionIntervalMs.coerceAtLeast(500L) + delay)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LOOP RACCOLTA — gesto multi-stroke
    //
    // Tutti gli item trovati vengono raccolti con UN SOLO dispatchGesture.
    // Ogni item = uno stroke da 60ms con offset temporale di 350ms.
    // Questo è il massimo che possiamo fare: il server richiede un tap
    // individuale per ogni dropId — non esiste raccolta AoE lato server.
    //
    // Vantaggi rispetto a handler.postDelayed multipli:
    //  - Una sola chiamata al sistema Android
    //  - Meno interferenze con i tocchi reali dell'utente
    //  - Timing gestito internamente dal sistema gesture
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

            // Costruisce un unico gesto con N stroke (uno per item)
            val builder = GestureDescription.Builder()
            var time = 0L
            for ((ix, iy) in items.take(10)) {
                val path = Path().apply { moveTo(ix, iy) }
                builder.addStroke(GestureDescription.StrokeDescription(path, time, 60L))
                time += 350L
            }
            try { dispatchGesture(builder.build(), null, null) } catch (_: Exception) {}

            // Riparte dopo che tutti gli stroke sono terminati
            handler.postDelayed(this, time + 500L)
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
    // RILEVAMENTO OGGETTI A TERRA — CON FILTRO DI PROSSIMITÀ
    //
    // Cerca in celle 30×30 px (più fine rispetto alle 40×40 precedenti).
    // Raccoglie SOLO ciò che è entro il 45% della larghezza schermo
    // dal personaggio (centro ~50% x, ~55% y).
    //
    // Soglie colore:
    //   Nome personaggio (verde):  G > 140, G-R > 40, G-B > 30, R < 150
    //   Yang (giallo-oro):         R > 185, G > 140, B < 90, R-B > 110
    //
    // Min pixel validi per cella abbassato da 3 → 2 (più sensibile al testo piccolo).
    //
    // Area esclusa:
    //   Top 12%    — barra HP, minimappa
    //   Bottom 22% — skill, joystick
    //   Left 8%    — pannello overlay
    //   Right 15%  — skill colonna destra
    // ═══════════════════════════════════════════════════════════════════════════
    private fun findLootItems(bmp: Bitmap): List<Pair<Float, Float>> {
        val w = bmp.width; val h = bmp.height
        val x0 = (w * 0.08f).toInt();  val x1 = (w * 0.85f).toInt()
        val y0 = (h * 0.12f).toInt();  val y1 = (h * 0.78f).toInt()
        val cell = 30
        val found = mutableListOf<Pair<Float, Float>>()

        // Personaggio al centro dello schermo
        val charX = w * 0.50f
        val charY = h * 0.55f
        // Raggio di raccolta: 45% della larghezza (era 30%, ora più ampio)
        val maxDist = w * 0.45f
        val maxDistSq = maxDist * maxDist

        var cy = y0
        while (cy + cell <= y1) {
            var cx = x0
            while (cx + cell <= x1) {
                val itemX = (cx + cell / 2).toFloat()
                val itemY = (cy + cell / 2).toFloat()

                // Filtro prossimità
                val dx = itemX - charX
                val dy = itemY - charY
                if (dx * dx + dy * dy > maxDistSq) {
                    cx += cell; continue
                }

                var hits = 0
                for (dy2 in 0 until cell step 3) {
                    for (dx2 in 0 until cell step 3) {
                        val p = bmp.getPixel(cx + dx2, cy + dy2)
                        val r = Color.red(p)
                        val g = Color.green(p)
                        val b = Color.blue(p)

                        // Nome personaggio: testo verde (soglie leggermente allargate)
                        val playerName = g > 140 && g - r > 40 && g - b > 30 && r < 150

                        // Yang: testo giallo-oro (soglie allargate per varianti colore)
                        val yang = r > 185 && g > 140 && b < 90 && r - b > 110

                        if (playerName || yang) hits++
                    }
                }
                // Min 2 pixel (era 3) — più sensibile al testo piccolo
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
        handler.removeCallbacks(scanner)
        handler.removeCallbacks(lootLoop)
        lootTargets = emptyList()
    }

    // ── Tap normale (raccolta oggetti) — 60ms ─────────────────────────────────
    private fun tap(x: Float, y: Float) {
        try {
            dispatchGesture(
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(
                        Path().apply { moveTo(x, y) }, 0L, 60L))
                    .build(), null, null)
        } catch (_: Exception) {}
    }

    // ── Tap leggero (pozione) — 1ms ───────────────────────────────────────────
    // Durata brevissima per NON cancellare il tocco reale tenuto premuto
    // dall'utente (es. tasto attacco). Un gesto accessibility da 60ms blocca
    // la coda input per tutta la sua durata; a 1ms l'interferenza è trascurabile.
    private fun tapLight(x: Float, y: Float) {
        try {
            dispatchGesture(
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(
                        Path().apply { moveTo(x, y) }, 0L, 1L))
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
