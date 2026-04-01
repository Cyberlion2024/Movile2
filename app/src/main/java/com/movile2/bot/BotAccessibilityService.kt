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
import kotlin.math.abs

class BotAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    // ── Oggetti trovati nell'ultimo scan ──────────────────────────────────────
    @Volatile private var lootTargets: List<Pair<Float, Float>> = emptyList()

    // ═══════════════════════════════════════════════════════════════════════════
    // LOOP POZIONE
    // Preme continuamente sulla posizione impostata ogni potionIntervalMs.
    // ═══════════════════════════════════════════════════════════════════════════
    private val potionLoop = object : Runnable {
        override fun run() {
            if (!BotState.potionRunning) return
            val x = BotState.potionX; val y = BotState.potionY
            if (x > 0f && y > 0f) tap(x, y)
            handler.postDelayed(this, BotState.potionIntervalMs.coerceAtLeast(500L))
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LOOP RACCOLTA
    // Ogni ciclo: legge lootTargets (aggiornati dallo scanner) e tappa
    // su ciascun oggetto trovato — doppio tap a 250ms per raccogliere.
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
    // Gira ogni 500ms quando lootRunning è true.
    // Aggiorna lootTargets con la lista degli oggetti trovati.
    // ═══════════════════════════════════════════════════════════════════════════
    private val scanner = object : Runnable {
        override fun run() {
            if (!BotState.lootRunning) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) doScan()
            handler.postDelayed(this, 500L)
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
    // Cerca in una griglia di celle 40×40 px nella zona di gioco centrale.
    // Rileva due tipi di pixel:
    //
    //   NOME PERSONAGGIO (verde)
    //     In Mobile2/Metin2, gli oggetti che appartengono al tuo personaggio
    //     mostrano il tuo nome sopra di loro in testo VERDE.
    //     Soglia: G > 155, G-R > 45, G-B > 35
    //
    //   YANG (giallo-oro)
    //     Le monete yang cadono con testo giallo-oro brillante.
    //     Soglia: R > 195, G > 155, B < 80, R-B > 120
    //
    // Una cella con ≥ 3 pixel validi = un oggetto da raccogliere.
    // Ritorna fino a 10 oggetti, ordinati dal basso verso l'alto
    // (gli oggetti più vicini al personaggio vengono raccolti per primi).
    //
    // Area esclusa:
    //   Top 12%  — barra HP, minimappa
    //   Bottom 22% — skill, joystick
    //   Left 8%   — pannello overlay
    //   Right 15%  — skill colonna destra
    // ═══════════════════════════════════════════════════════════════════════════
    private fun findLootItems(bmp: Bitmap): List<Pair<Float, Float>> {
        val w = bmp.width; val h = bmp.height
        val x0 = (w * 0.08f).toInt();  val x1 = (w * 0.85f).toInt()
        val y0 = (h * 0.12f).toInt();  val y1 = (h * 0.78f).toInt()
        val cell = 40
        val found = mutableListOf<Pair<Float, Float>>()

        var cy = y0
        while (cy + cell <= y1) {
            var cx = x0
            while (cx + cell <= x1) {
                var hits = 0
                for (dy in 0 until cell step 3) {
                    for (dx in 0 until cell step 3) {
                        val p  = bmp.getPixel(cx + dx, cy + dy)
                        val r  = Color.red(p)
                        val g  = Color.green(p)
                        val b  = Color.blue(p)

                        // Nome personaggio: testo verde
                        val playerName = g > 155 && g - r > 45 && g - b > 35 && r < 140

                        // Yang: testo giallo-oro
                        val yang = r > 195 && g > 155 && b < 80 && r - b > 120

                        if (playerName || yang) hits++
                    }
                }
                if (hits >= 3) {
                    found.add((cx + cell / 2).toFloat() to (cy + cell / 2).toFloat())
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
    fun startPotion(x: Float, y: Float, intervalMs: Long) {
        if (BotState.potionRunning) stopPotion()
        BotState.potionX = x; BotState.potionY = y
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
