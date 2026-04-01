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
import kotlin.math.sqrt

class BotAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    // ── Oggetti trovati nell'ultimo scan ──────────────────────────────────────
    @Volatile private var lootTargets: List<Pair<Float, Float>> = emptyList()

    // ═══════════════════════════════════════════════════════════════════════════
    // LOOP POZIONE
    // Preme tutti gli slot pozione impostati ogni potionIntervalMs.
    // Non richiede azione manuale — parte automaticamente quando gli slot
    // sono configurati.
    // ═══════════════════════════════════════════════════════════════════════════
    private val potionLoop = object : Runnable {
        override fun run() {
            if (!BotState.potionRunning) return
            val slots = BotState.potionSlots
            var delay = 0L
            for ((x, y) in slots) {
                handler.postDelayed({ if (BotState.potionRunning) tap(x, y) }, delay)
                delay += 200L
            }
            handler.postDelayed(this, BotState.potionIntervalMs.coerceAtLeast(500L) + delay)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LOOP RACCOLTA
    // Raccoglie solo gli oggetti VICINI al personaggio (prossimità).
    // Non tocca zone lontane che interferirebbero col joystick/movimento.
    // ═══════════════════════════════════════════════════════════════════════════
    private val lootLoop = object : Runnable {
        override fun run() {
            if (!BotState.lootRunning) return
            val items = lootTargets
            BotState.lootItemsFound = items.size
            var delay = 0L
            for ((ix, iy) in items) {
                handler.postDelayed({
                    if (!BotState.lootRunning) return@postDelayed
                    tap(ix, iy)
                    handler.postDelayed({ if (BotState.lootRunning) tap(ix, iy) }, 250L)
                }, delay)
                delay += 600L
            }
            handler.postDelayed(this, delay + 400L)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SCANNER SCREENSHOT
    // Gira ogni 400ms quando lootRunning è true.
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
    // Cerca oggetti (nome verde) e yang (giallo-oro) SOLO nella zona vicina
    // al personaggio (entro il 30% della larghezza schermo dal centro).
    // Questo evita di toccare il joystick o zone di movimento lontane.
    //
    // Il personaggio in Metin2 è sempre al centro dello schermo (~50% x, ~55% y).
    //
    // Area esclusa dalla scansione:
    //   Top 12%    — barra HP, minimappa
    //   Bottom 22% — skill, joystick
    //   Left 8%    — pannello overlay
    //   Right 15%  — skill colonna destra
    // ═══════════════════════════════════════════════════════════════════════════
    private fun findLootItems(bmp: Bitmap): List<Pair<Float, Float>> {
        val w = bmp.width; val h = bmp.height
        val x0 = (w * 0.08f).toInt();  val x1 = (w * 0.85f).toInt()
        val y0 = (h * 0.12f).toInt();  val y1 = (h * 0.78f).toInt()
        val cell = 40
        val found = mutableListOf<Pair<Float, Float>>()

        // Posizione del personaggio = centro schermo
        val charX = w * 0.50f
        val charY = h * 0.55f
        // Raggio massimo entro cui raccogliere (30% della larghezza)
        val maxDist = w * 0.30f

        var cy = y0
        while (cy + cell <= y1) {
            var cx = x0
            while (cx + cell <= x1) {
                val itemX = (cx + cell / 2).toFloat()
                val itemY = (cy + cell / 2).toFloat()

                // ── Filtro prossimità: salta celle lontane dal personaggio ────
                val dx = itemX - charX
                val dy = itemY - charY
                if (dx * dx + dy * dy > maxDist * maxDist) {
                    cx += cell; continue
                }

                var hits = 0
                for (dy2 in 0 until cell step 3) {
                    for (dx2 in 0 until cell step 3) {
                        val p = bmp.getPixel(cx + dx2, cy + dy2)
                        val r = Color.red(p)
                        val g = Color.green(p)
                        val b = Color.blue(p)

                        // Nome personaggio: testo verde
                        val playerName = g > 155 && g - r > 45 && g - b > 35 && r < 140

                        // Yang: testo giallo-oro
                        val yang = r > 195 && g > 155 && b < 80 && r - b > 120

                        if (playerName || yang) hits++
                    }
                }
                if (hits >= 3) {
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

    // ── Gesto tap ────────────────────────────────────────────────────────────
    private fun tap(x: Float, y: Float) {
        try {
            dispatchGesture(
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(
                        Path().apply { moveTo(x, y) }, 0L, 60L))
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
