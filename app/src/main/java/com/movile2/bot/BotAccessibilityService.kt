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
import kotlin.random.Random

class BotAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    private var lastSkill1Ms = 0L
    private var lastSkill2Ms = 0L
    private var lastSkill3Ms = 0L
    private var lastSkill4Ms = 0L
    private var lastSkill5Ms = 0L
    private var potionUsesInSlot = 0
    private var huntCycles = 0

    // Patrol direction index (0=N, 1=E, 2=S, 3=W)
    private var patrolDir = 0
    private var patrolSteps = 0

    // Camera rotation direction: alternates left/right
    private var cameraLookDir = 1

    // Target detected via screen scan (red pixel centroid of enemy names)
    @Volatile private var detectedTargetX = 0f
    @Volatile private var detectedTargetY = 0f

    // HP ratio from bar scan (0.0 = empty, 1.0 = full)
    @Volatile private var scannedHpRatio = 1.0f
    @Volatile private var hpBarConfigured = false

    private val PATROL_STEPS = intArrayOf(5, 4, 5, 4)
    private val PATROL_DIRS = arrayOf(
        floatArrayOf(0f, -1f),  // N
        floatArrayOf(1f,  0f),  // E
        floatArrayOf(0f,  1f),  // S
        floatArrayOf(-1f, 0f),  // W
    )

    private val CAMERA_EVERY   = 4      // rotate camera every N hunt cycles
    private val CYCLE_MS       = 800L   // total hunt cycle duration
    private val DEFEND_CYCLE_MS = 400L  // faster cycle in defense mode
    private val SCAN_DELAY_MS  = 1200L  // screenshot interval

    // Cicli senza drop HP prima di uscire dalla modalità difesa
    private val STABLE_CYCLES_TO_EXIT = 3
    // Cicli consecutivi di drop HP prima di entrare in modalità difesa
    private val DROP_CYCLES_TO_ENTER  = 2

    private enum class Phase { HUNT, DEFEND, POTION, REFILL }
    private var phase = Phase.HUNT

    // ── Loop principale ───────────────────────────────────────────────────────
    private val loop = object : Runnable {
        override fun run() {
            if (!BotState.isRunning) return
            val cfg = BotConfig.load(this@BotAccessibilityService)

            // Limite massimo uccisioni
            if (cfg.maxKills > 0 && BotState.killCount >= cfg.maxKills) { stopBot(); return }

            // Timer di sessione: auto-stop dopo sessionMinutes minuti
            if (cfg.sessionMinutes > 0 && BotState.sessionStartMs > 0) {
                val elapsed = System.currentTimeMillis() - BotState.sessionStartMs
                if (elapsed >= cfg.sessionMinutes * 60_000L) { stopBot(); return }
            }

            when (phase) {
                Phase.HUNT   -> doHunt(cfg)
                Phase.DEFEND -> doDefend(cfg)
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
        takeScreenshot(Display.DEFAULT_DISPLAY, ContextCompat.getMainExecutor(this),
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val hw = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                    val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    hw?.recycle()
                    screenshot.hardwareBuffer.close()
                    bmp?.let {
                        analyzeFrame(it)
                        it.recycle()
                    }
                }
                override fun onFailure(errorCode: Int) {
                    // Screenshot non disponibile (FLAG_SECURE o schermata di blocco)
                    // Il bot continua in modalità cieca
                    detectedTargetX = 0f
                    detectedTargetY = 0f
                }
            })
    }

    // ── Analisi frame: rileva nomi nemici (rosso) e legge barra HP ───────────
    private fun analyzeFrame(bmp: Bitmap) {
        val cfg = BotConfig.load(this)
        val w = bmp.width
        val h = bmp.height

        // ── 1. Rilevamento nomi mostri nemici ─────────────────────────────────
        // Scansiona solo zona 10%-55% verticale, saltando overlay a sinistra (~250px)
        val yStart = (h * 0.10f).toInt()
        val yEnd   = (h * 0.55f).toInt()
        val xStart = 250

        var sumX = 0L; var sumY = 0L; var count = 0

        for (y in yStart until yEnd step 5) {
            for (x in xStart until w step 5) {
                val pixel = bmp.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                // Rosso vivace tipico dei nomi nemici: R alto, G basso, B basso
                if (r > 180 && g < 110 && b < 90) {
                    sumX += x; sumY += y; count++
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

        // ── 2. Lettura barra HP (top-left) ────────────────────────────────────
        if (cfg.hpBarX > 0f && cfg.hpBarY > 0f && cfg.hpBarFullWidth > 0) {
            hpBarConfigured = true
            val barX = cfg.hpBarX.toInt()
            val barY = cfg.hpBarY.toInt().coerceIn(0, h - 1)
            val barW = cfg.hpBarFullWidth

            // Scansiona pixel orizzontalmente lungo la barra
            // La barra HP è rossa/arancione: R alto, G medio-basso, B basso
            var redPixels = 0
            val scanW = barW.coerceAtMost(w - barX)
            for (x in barX until (barX + scanW)) {
                if (x >= w) break
                val pixel = bmp.getPixel(x, barY)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                // Rosso/arancio della barra vita: R > 140, G < 80, B < 80
                if (r > 140 && g < 80 && b < 80) redPixels++
            }
            scannedHpRatio = if (scanW > 0) redPixels.toFloat() / scanW.toFloat() else 1.0f

            // ── Aggiorna stato under-attack ────────────────────────────────────
            val previousHp = BotState.lastHpRatio
            val drop = previousHp - scannedHpRatio

            if (drop > 0.025f) {
                // HP è calato di almeno 2.5%
                BotState.hpDropCycles++
                BotState.hpStableCycles = 0
            } else {
                BotState.hpStableCycles++
                BotState.hpDropCycles = 0
            }

            BotState.lastHpRatio = scannedHpRatio

            // Entra in difesa se HP cala per DROP_CYCLES_TO_ENTER cicli consecutivi
            if (!BotState.underAttack && BotState.hpDropCycles >= DROP_CYCLES_TO_ENTER) {
                BotState.underAttack = true
            }
            // Esce da difesa dopo STABLE_CYCLES_TO_EXIT cicli senza drop
            if (BotState.underAttack && BotState.hpStableCycles >= STABLE_CYCLES_TO_EXIT) {
                BotState.underAttack = false
                BotState.hpDropCycles = 0
            }
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
        BotState.isRunning   = true
        BotState.sessionStartMs = System.currentTimeMillis()
        BotState.lastHpRatio = 1.0f
        BotState.hpDropCycles = 0
        BotState.hpStableCycles = 0
        BotState.underAttack = false
        huntCycles    = 0
        potionUsesInSlot = 0
        lastSkill1Ms  = 0L
        lastSkill2Ms  = 0L
        lastSkill3Ms  = 0L
        lastSkill4Ms  = 0L
        lastSkill5Ms  = 0L
        phase         = Phase.HUNT
        patrolDir     = 0
        patrolSteps   = 0
        cameraLookDir = 1
        detectedTargetX = 0f
        detectedTargetY = 0f
        scannedHpRatio  = 1.0f
        handler.post(loop)
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

    // ── HUNT: cerca bersaglio, muovi, attacca ────────────────────────────────
    private fun doHunt(cfg: BotConfig) {
        val now = System.currentTimeMillis()

        // Se stiamo perdendo vita → passa in difesa immediatamente
        if (BotState.underAttack) {
            phase = Phase.DEFEND
            next(50L)
            return
        }

        // Se HP basso e pozione configurata → usa pozione
        if (cfg.potionX > 0f && hpBarConfigured &&
            scannedHpRatio < cfg.hpPotionThreshold && scannedHpRatio > 0f) {
            phase = Phase.POTION; next(CYCLE_MS); return
        }

        val useJoystick = cfg.joystickX > 0f && cfg.joystickY > 0f
        val useCamera   = cfg.cameraAreaX > 0f && cfg.cameraAreaY > 0f
        val hasTarget   = detectedTargetX > 0f && detectedTargetY > 0f

        // FIX: ruota la camera SOLO se non c'è già un bersaglio visibile
        val doCamera = useCamera && !hasTarget && (huntCycles % CAMERA_EVERY == CAMERA_EVERY - 1)

        // ── Gesto di movimento ────────────────────────────────────────────────
        if (doCamera) {
            val cx = cfg.cameraAreaX; val cy = cfg.cameraAreaY; val r = cfg.cameraSwipeRange
            swipe(cx - cameraLookDir * r, cy, cx + cameraLookDir * r, cy, 250L)
            cameraLookDir = -cameraLookDir
        } else if (useJoystick) {
            val dir = PATROL_DIRS[patrolDir]
            joystickPush(
                cfg.joystickX, cfg.joystickY,
                cfg.joystickX + dir[0] * cfg.joystickRadius,
                cfg.joystickY + dir[1] * cfg.joystickRadius,
                350L
            )
            patrolSteps++
            if (patrolSteps >= PATROL_STEPS[patrolDir]) {
                patrolSteps = 0; patrolDir = (patrolDir + 1) % PATROL_DIRS.size
            }
        }

        // ── Attacco ───────────────────────────────────────────────────────────
        val base = if (doCamera) 300L else 400L

        if (hasTarget) {
            // Mostro rilevato via pixel rossi: seleziona poi attacca
            handler.postDelayed({ if (BotState.isRunning) tap(detectedTargetX, detectedTargetY) }, base)
            handler.postDelayed({ if (BotState.isRunning) tap(cfg.attackX, cfg.attackY) }, base + 150L)
        } else {
            // Nessun target specifico: premi attacco diretto (auto-target del gioco)
            handler.postDelayed({ if (BotState.isRunning) tap(cfg.attackX, cfg.attackY) }, base)
        }

        // Abilità 1
        if (now - lastSkill1Ms >= cfg.skill1CooldownMs) {
            handler.postDelayed({
                if (BotState.isRunning) {
                    tap(cfg.skill1X, cfg.skill1Y)
                    lastSkill1Ms = System.currentTimeMillis()
                }
            }, base + 300L)
        }

        // Abilità 2
        if (now - lastSkill2Ms >= cfg.skill2CooldownMs) {
            handler.postDelayed({
                if (BotState.isRunning) {
                    tap(cfg.skill2X, cfg.skill2Y)
                    lastSkill2Ms = System.currentTimeMillis()
                }
            }, base + 450L)
        }

        // Abilità 3 (opzionale, solo se configurata)
        if (cfg.skill3X > 0f && cfg.skill3Y > 0f && now - lastSkill3Ms >= cfg.skill3CooldownMs) {
            handler.postDelayed({
                if (BotState.isRunning) { tap(cfg.skill3X, cfg.skill3Y); lastSkill3Ms = System.currentTimeMillis() }
            }, base + 600L)
        }

        // Abilità 4
        if (cfg.skill4X > 0f && cfg.skill4Y > 0f && now - lastSkill4Ms >= cfg.skill4CooldownMs) {
            handler.postDelayed({
                if (BotState.isRunning) { tap(cfg.skill4X, cfg.skill4Y); lastSkill4Ms = System.currentTimeMillis() }
            }, base + 700L)
        }

        // Abilità 5
        if (cfg.skill5X > 0f && cfg.skill5Y > 0f && now - lastSkill5Ms >= cfg.skill5CooldownMs) {
            handler.postDelayed({
                if (BotState.isRunning) { tap(cfg.skill5X, cfg.skill5Y); lastSkill5Ms = System.currentTimeMillis() }
            }, base + 750L)
        }

        huntCycles++
        if (huntCycles % 8 == 0) BotState.killCount++

        // Pozione a timer ogni 30 cicli se non c'è il monitor HP attivo
        if (cfg.potionX > 0f && !hpBarConfigured && huntCycles % 30 == 0) {
            phase = Phase.POTION; next(CYCLE_MS); return
        }

        next(CYCLE_MS + Random.nextLong(0L, 30L))
    }

    // ── DEFEND: siamo sotto attacco, spam attacco finché l'HP si stabilizza ──
    private fun doDefend(cfg: BotConfig) {
        val now = System.currentTimeMillis()

        // Se l'HP si è stabilizzato → torna a cacciare
        if (!BotState.underAttack) {
            phase = Phase.HUNT
            next(CYCLE_MS)
            return
        }

        // Se HP molto basso → usa pozione subito
        if (cfg.potionX > 0f && scannedHpRatio < cfg.hpPotionThreshold && scannedHpRatio > 0f) {
            phase = Phase.POTION; next(200L); return
        }

        // Spam attacco: tap attacco + abilità disponibili senza aspettare bersaglio
        tap(cfg.attackX, cfg.attackY)

        if (now - lastSkill1Ms >= cfg.skill1CooldownMs) {
            handler.postDelayed({
                if (BotState.isRunning) { tap(cfg.skill1X, cfg.skill1Y); lastSkill1Ms = System.currentTimeMillis() }
            }, 150L)
        }
        if (now - lastSkill2Ms >= cfg.skill2CooldownMs) {
            handler.postDelayed({
                if (BotState.isRunning) { tap(cfg.skill2X, cfg.skill2Y); lastSkill2Ms = System.currentTimeMillis() }
            }, 280L)
        }
        if (cfg.skill3X > 0f && now - lastSkill3Ms >= cfg.skill3CooldownMs) {
            handler.postDelayed({
                if (BotState.isRunning) { tap(cfg.skill3X, cfg.skill3Y); lastSkill3Ms = System.currentTimeMillis() }
            }, 400L)
        }
        if (cfg.skill4X > 0f && now - lastSkill4Ms >= cfg.skill4CooldownMs) {
            handler.postDelayed({
                if (BotState.isRunning) { tap(cfg.skill4X, cfg.skill4Y); lastSkill4Ms = System.currentTimeMillis() }
            }, 500L)
        }
        if (cfg.skill5X > 0f && now - lastSkill5Ms >= cfg.skill5CooldownMs) {
            handler.postDelayed({
                if (BotState.isRunning) { tap(cfg.skill5X, cfg.skill5Y); lastSkill5Ms = System.currentTimeMillis() }
            }, 600L)
        }

        next(DEFEND_CYCLE_MS)
    }

    // ── POTION ────────────────────────────────────────────────────────────────
    private fun doPotion(cfg: BotConfig) {
        tap(cfg.potionX, cfg.potionY)
        potionUsesInSlot++
        phase = if (potionUsesInSlot >= cfg.maxPotionsInSlot && cfg.backupPotionX > 0f)
            Phase.REFILL else Phase.HUNT
        // Se siamo sotto attacco torna subito in difesa dopo la pozione
        if (BotState.underAttack) phase = Phase.DEFEND
        next(400L)
    }

    // ── REFILL ────────────────────────────────────────────────────────────────
    private fun doRefill(cfg: BotConfig) {
        swipe(cfg.backupPotionX, cfg.backupPotionY, cfg.potionX, cfg.potionY, 400L)
        potionUsesInSlot = 0
        phase = if (BotState.underAttack) Phase.DEFEND else Phase.HUNT
        next(1000L)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private fun next(ms: Long) = handler.postDelayed(loop, ms)

    private fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build(),
            null, null
        )
    }

    private fun joystickPush(cx: Float, cy: Float, tx: Float, ty: Float, dur: Long) {
        val path = Path().apply { moveTo(cx, cy); lineTo(tx, ty) }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, dur)).build(),
            null, null
        )
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, dur: Long) {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, dur)).build(),
            null, null
        )
    }

    companion object {
        var instance: BotAccessibilityService? = null
            private set
    }
}
