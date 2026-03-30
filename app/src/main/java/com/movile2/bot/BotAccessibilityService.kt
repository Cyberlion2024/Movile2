package com.movile2.bot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import kotlin.random.Random

class BotAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    private var lastSkill1Ms = 0L
    private var lastSkill2Ms = 0L
    private var potionUsesInSlot = 0
    private var huntCycles = 0

    // Patrol direction index (0=N, 1=E, 2=S, 3=W)
    private var patrolDir = 0
    private var patrolSteps = 0

    // Camera rotation direction: alternates left/right
    private var cameraLookDir = 1

    // Target detected via screen scan (red pixel centroid)
    @Volatile private var detectedTargetX = 0f
    @Volatile private var detectedTargetY = 0f

    private val PATROL_STEPS = intArrayOf(5, 4, 5, 4)
    private val PATROL_DIRS = arrayOf(
        floatArrayOf(0f, -1f),  // N
        floatArrayOf(1f,  0f),  // E
        floatArrayOf(0f,  1f),  // S
        floatArrayOf(-1f, 0f),  // W
    )

    private val CAMERA_EVERY   = 4    // rotate camera every N hunt cycles
    private val CYCLE_MS       = 800L // total cycle duration — all taps fit within this
    private val SCAN_DELAY_MS  = 1200L // how often to take a screenshot for red-pixel scan

    private enum class Phase { HUNT, POTION, REFILL }
    private var phase = Phase.HUNT

    private val loop = object : Runnable {
        override fun run() {
            if (!BotState.isRunning) return
            val cfg = BotConfig.load(this@BotAccessibilityService)
            if (cfg.maxKills > 0 && BotState.killCount >= cfg.maxKills) { stopBot(); return }
            when (phase) {
                Phase.HUNT   -> doHunt(cfg)
                Phase.POTION -> doPotion(cfg)
                Phase.REFILL -> doRefill(cfg)
            }
        }
    }

    // ── Scansione schermo periodica (Android 11+) ─────────────────────────────
    private val scanner = object : Runnable {
        override fun run() {
            if (!BotState.isRunning) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                doScreenScan()
            }
            handler.postDelayed(this, SCAN_DELAY_MS)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun doScreenScan() {
        val executor = { r: Runnable -> handler.post(r) }
        takeScreenshot(Display.DEFAULT_DISPLAY, executor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                val hw = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                hw?.recycle()
                screenshot.hardwareBuffer.close()
                bmp?.let { analyzeFrame(it); it.recycle() }
            }
            override fun onFailure(errorCode: Int) {
                // Screenshot non disponibile (gioco protetto o schermata di blocco)
                detectedTargetX = 0f
                detectedTargetY = 0f
            }
        })
    }

    // Trova il centroide dei pixel rossi = nome del mostro
    private fun analyzeFrame(bmp: Bitmap) {
        val w = bmp.width
        val h = bmp.height
        // Scansiona solo la zona dove appaiono i nomi dei mostri (10%-55% verticale)
        // Salta la sinistra dove c'è il nostro overlay (~250px)
        val yStart = (h * 0.10f).toInt()
        val yEnd   = (h * 0.55f).toInt()
        val xStart = 250

        var sumX = 0L
        var sumY = 0L
        var count = 0

        // Ogni 5 pixel per performance. Rosso-arancio = nome mostro nemico.
        for (y in yStart until yEnd step 5) {
            for (x in xStart until w step 5) {
                val pixel = bmp.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                // Rosso vivace tipico dei nomi nemici: R alto, G basso, B basso
                if (r > 180 && g < 110 && b < 90) {
                    sumX += x
                    sumY += y
                    count++
                }
            }
        }

        if (count >= 15) {
            detectedTargetX = (sumX / count).toFloat()
            detectedTargetY = (sumY / count).toFloat()
        } else {
            detectedTargetX = 0f
            detectedTargetY = 0f
        }
    }

    override fun onServiceConnected() { instance = this }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() { stopBot() }

    override fun onDestroy() {
        super.onDestroy()
        stopBot()
        if (instance === this) instance = null
    }

    fun startBot() {
        if (BotState.isRunning) return
        BotState.isRunning = true
        huntCycles = 0
        potionUsesInSlot = 0
        lastSkill1Ms = 0L
        lastSkill2Ms = 0L
        phase = Phase.HUNT
        patrolDir = 0
        patrolSteps = 0
        cameraLookDir = 1
        detectedTargetX = 0f
        detectedTargetY = 0f
        handler.post(loop)
        // Avvia scansione schermo se su Android 11+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            handler.postDelayed(scanner, SCAN_DELAY_MS)
        }
    }

    fun stopBot() {
        BotState.isRunning = false
        handler.removeCallbacksAndMessages(null)
        detectedTargetX = 0f
        detectedTargetY = 0f
    }

    // ── HUNT: muovi + cerca bersaglio + attacca ───────────────────────────────
    private fun doHunt(cfg: BotConfig) {
        val now = System.currentTimeMillis()
        val useJoystick = cfg.joystickX > 0f && cfg.joystickY > 0f
        val useCamera   = cfg.cameraAreaX > 0f && cfg.cameraAreaY > 0f
        val doCamera    = useCamera && (huntCycles % CAMERA_EVERY == CAMERA_EVERY - 1)

        // ── Gesto di movimento ────────────────────────────────────────────────
        if (doCamera) {
            val cx = cfg.cameraAreaX; val cy = cfg.cameraAreaY; val r = cfg.cameraSwipeRange
            swipe(cx - cameraLookDir * r, cy, cx + cameraLookDir * r, cy, 250L)
            cameraLookDir = -cameraLookDir
        } else if (useJoystick) {
            val dir = PATROL_DIRS[patrolDir]
            joystickPush(cfg.joystickX, cfg.joystickY,
                cfg.joystickX + dir[0] * cfg.joystickRadius,
                cfg.joystickY + dir[1] * cfg.joystickRadius, 350L)
            patrolSteps++
            if (patrolSteps >= PATROL_STEPS[patrolDir]) {
                patrolSteps = 0; patrolDir = (patrolDir + 1) % PATROL_DIRS.size
            }
        }

        // ── Gesti di attacco (dopo il gesto di movimento) ────────────────────
        // Tutti schedulati DOPO che il gesto di movimento è finito.
        // Ciclo totale 800ms → nessun conflitto di gesti.
        val base = if (doCamera) 300L else 400L

        val tx = detectedTargetX
        val ty = detectedTargetY

        if (tx > 0f && ty > 0f) {
            // Mostro rilevato via pixel rossi: tocca il nome per selezionarlo, poi attacca
            handler.postDelayed({ if (BotState.isRunning) tap(tx, ty) }, base)
            handler.postDelayed({ if (BotState.isRunning) tap(cfg.attackX, cfg.attackY) }, base + 150L)
        } else {
            // Nessun bersaglio rilevato: premi attacco diretto (funziona se il gioco ha auto-target)
            handler.postDelayed({ if (BotState.isRunning) tap(cfg.attackX, cfg.attackY) }, base)
        }

        // Abilità 1
        if (now - lastSkill1Ms >= cfg.skill1CooldownMs) {
            handler.postDelayed({
                if (BotState.isRunning) { tap(cfg.skill1X, cfg.skill1Y); lastSkill1Ms = System.currentTimeMillis() }
            }, base + 300L)
        }

        // Abilità 2
        if (now - lastSkill2Ms >= cfg.skill2CooldownMs) {
            handler.postDelayed({
                if (BotState.isRunning) { tap(cfg.skill2X, cfg.skill2Y); lastSkill2Ms = System.currentTimeMillis() }
            }, base + 450L)
        }

        huntCycles++
        if (huntCycles % 8 == 0) BotState.killCount++

        if (cfg.potionX > 0 && huntCycles % 30 == 0) {
            phase = Phase.POTION; next(CYCLE_MS); return
        }

        next(CYCLE_MS + Random.nextLong(0L, 30L))
    }

    // ── Pozione ───────────────────────────────────────────────────────────────
    private fun doPotion(cfg: BotConfig) {
        tap(cfg.potionX, cfg.potionY)
        potionUsesInSlot++
        phase = if (potionUsesInSlot >= cfg.maxPotionsInSlot && cfg.backupPotionX > 0)
            Phase.REFILL else Phase.HUNT
        next(400L)
    }

    // ── Ricarica ──────────────────────────────────────────────────────────────
    private fun doRefill(cfg: BotConfig) {
        swipe(cfg.backupPotionX, cfg.backupPotionY, cfg.potionX, cfg.potionY, 400L)
        potionUsesInSlot = 0; phase = Phase.HUNT; next(1000L)
    }

    private fun next(ms: Long) = handler.postDelayed(loop, ms)

    private fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build(), null, null)
    }

    private fun joystickPush(cx: Float, cy: Float, tx: Float, ty: Float, dur: Long) {
        val path = Path().apply { moveTo(cx, cy); lineTo(tx, ty) }
        dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, dur)).build(), null, null)
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, dur: Long) {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, dur)).build(), null, null)
    }

    companion object {
        var instance: BotAccessibilityService? = null
            private set
    }
}
