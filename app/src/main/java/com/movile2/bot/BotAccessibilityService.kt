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

    // ── Timing ────────────────────────────────────────────────────────────────
    // Ogni gesto dura TAP_MS. Tra un gesto e il successivo c'è GAP_MS di buffer.
    // Slot totale per un singolo tap = TAP_MS + GAP_MS = 140ms.
    private val TAP_MS           = 60L
    private val GAP_MS           = 80L
    private val JOYSTICK_MS      = 350L
    private val CAMERA_MS        = 250L
    private val POST_MOVE_GAP_MS = 80L  // attesa extra dopo il gesto di movimento
    private val BETWEEN_CYCLE_MS = 120L // pausa minima prima del prossimo ciclo
    private val SCAN_DELAY_MS    = 1200L
    private val CAMERA_EVERY     = 5    // ruota visuale ogni N cicli (solo senza target)

    // ── Cooldown abilità ──────────────────────────────────────────────────────
    private var lastSkill1Ms = 0L
    private var lastSkill2Ms = 0L
    private var lastSkill3Ms = 0L
    private var lastSkill4Ms = 0L
    private var lastSkill5Ms = 0L

    // ── Pattugliamento ────────────────────────────────────────────────────────
    private var patrolDir   = 0
    private var patrolSteps = 0
    private var cameraDir   = 1

    // ── Stato ciclo ───────────────────────────────────────────────────────────
    private var huntCycles   = 0
    private var potionUses   = 0

    // ── Pixel detection (aggiornato dallo scanner asincrono) ──────────────────
    @Volatile private var targetX          = 0f
    @Volatile private var targetY          = 0f
    @Volatile private var prevTargetFound  = false  // per rilevare scomparsa mostro → kill
    @Volatile private var scannedHpRatio   = 1.0f
    @Volatile private var hpBarConfigured  = false

    private val PATROL_STEPS = intArrayOf(5, 4, 5, 4)
    private val PATROL_DIRS  = arrayOf(
        floatArrayOf(0f, -1f), floatArrayOf(1f, 0f),
        floatArrayOf(0f,  1f), floatArrayOf(-1f, 0f),
    )

    private enum class Phase { HUNT, DEFEND, POTION, REFILL }
    private var phase = Phase.HUNT

    // ── Loop principale ───────────────────────────────────────────────────────
    // Importante: il loop NON si auto-schedula con postDelayed fisso.
    // È ogni fase a schedulare il prossimo run DOPO che le sue azioni sono finite.
    private val loop = object : Runnable {
        override fun run() {
            if (!BotState.isRunning) return
            val cfg = BotConfig.load(this@BotAccessibilityService)

            if (cfg.maxKills > 0 && BotState.killCount >= cfg.maxKills) { stopBot(); return }
            if (cfg.sessionMinutes > 0 && System.currentTimeMillis() - BotState.sessionStartMs >= cfg.sessionMinutes * 60_000L) {
                stopBot(); return
            }

            when (phase) {
                Phase.HUNT   -> doHunt(cfg)
                Phase.DEFEND -> doDefend(cfg)
                Phase.POTION -> doPotion(cfg)
                Phase.REFILL -> doRefill(cfg)
            }
        }
    }

    // ── Scanner schermo (Android 11+) ─────────────────────────────────────────
    private val scanner = object : Runnable {
        override fun run() {
            if (!BotState.isRunning) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) doScreenScan()
            handler.postDelayed(this, SCAN_DELAY_MS)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun doScreenScan() {
        takeScreenshot(Display.DEFAULT_DISPLAY, ContextCompat.getMainExecutor(this),
            object : TakeScreenshotCallback {
                override fun onSuccess(s: ScreenshotResult) {
                    val hw  = Bitmap.wrapHardwareBuffer(s.hardwareBuffer, s.colorSpace)
                    val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    hw?.recycle(); s.hardwareBuffer.close()
                    bmp?.let { analyzeFrame(it); it.recycle() }
                }
                override fun onFailure(e: Int) {
                    targetX = 0f; targetY = 0f
                }
            })
    }

    private fun analyzeFrame(bmp: Bitmap) {
        val cfg = BotConfig.load(this)
        val w = bmp.width; val h = bmp.height

        // ── Rileva nomi mostri (pixel rossi, 10%-55% verticale) ───────────────
        val yStart = (h * 0.10f).toInt(); val yEnd = (h * 0.55f).toInt()
        var sumX = 0L; var sumY = 0L; var count = 0
        for (y in yStart until yEnd step 5) {
            for (x in 250 until w step 5) {
                val p = bmp.getPixel(x, y)
                if (Color.red(p) > 180 && Color.green(p) < 110 && Color.blue(p) < 90) {
                    sumX += x; sumY += y; count++
                }
            }
        }
        if (count >= 15) {
            targetX = (sumX / count).toFloat(); targetY = (sumY / count).toFloat()
        } else {
            targetX = 0f; targetY = 0f
        }

        // ── Legge barra HP (top-left) ──────────────────────────────────────────
        if (cfg.hpBarX > 0f && cfg.hpBarY > 0f && cfg.hpBarFullWidth > 0) {
            hpBarConfigured = true
            val bx = cfg.hpBarX.toInt(); val by = cfg.hpBarY.toInt().coerceIn(0, h - 1)
            val bw = cfg.hpBarFullWidth.coerceAtMost(w - bx)
            var red = 0
            for (x in bx until bx + bw) {
                val p = bmp.getPixel(x, by)
                if (Color.red(p) > 140 && Color.green(p) < 80 && Color.blue(p) < 80) red++
            }
            val newRatio = if (bw > 0) red.toFloat() / bw else 1.0f

            // Rilevamento under attack: HP calato da ultima lettura?
            val drop = BotState.lastHpRatio - newRatio
            if (drop > 0.025f) {
                BotState.hpDropCycles++
                BotState.hpStableCycles = 0
            } else {
                BotState.hpStableCycles++
                BotState.hpDropCycles = 0
            }
            BotState.lastHpRatio = newRatio
            scannedHpRatio = newRatio

            if (!BotState.underAttack && BotState.hpDropCycles >= 2) BotState.underAttack = true
            if (BotState.underAttack && BotState.hpStableCycles >= 3) {
                BotState.underAttack = false; BotState.hpDropCycles = 0
            }
        }
    }

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() { stopBot() }
    override fun onDestroy() { super.onDestroy(); stopBot(); if (instance === this) instance = null }

    fun startBot() {
        if (BotState.isRunning) return
        handler.removeCallbacksAndMessages(null)  // pulizia completa prima di avviare
        BotState.isRunning      = true
        BotState.sessionStartMs = System.currentTimeMillis()
        BotState.lastHpRatio    = 1.0f
        BotState.hpDropCycles   = 0
        BotState.hpStableCycles = 0
        BotState.underAttack    = false
        huntCycles    = 0
        potionUses    = 0
        lastSkill1Ms  = 0L; lastSkill2Ms = 0L; lastSkill3Ms = 0L
        lastSkill4Ms  = 0L; lastSkill5Ms = 0L
        phase         = Phase.HUNT
        patrolDir     = 0; patrolSteps = 0; cameraDir = 1
        targetX       = 0f; targetY = 0f
        prevTargetFound = false
        scannedHpRatio  = 1.0f
        handler.post(loop)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            handler.postDelayed(scanner, SCAN_DELAY_MS)
    }

    fun stopBot() {
        BotState.isRunning = false
        handler.removeCallbacksAndMessages(null)
        targetX = 0f; targetY = 0f
    }

    // ── HUNT ──────────────────────────────────────────────────────────────────
    private fun doHunt(cfg: BotConfig) {
        val now      = System.currentTimeMillis()
        val hasTarget = targetX > 0f && targetY > 0f

        // Kill detection: il target era visibile e ora è sparito → mostro eliminato
        if (prevTargetFound && !hasTarget) BotState.killCount++
        prevTargetFound = hasTarget

        // HP basso → pozione
        val potionSet = cfg.potionX > 0f
        val hpLow     = hpBarConfigured && scannedHpRatio in 0.01f..cfg.hpPotionThreshold
        val timerPotion = potionSet && !hpBarConfigured && huntCycles > 0 && huntCycles % 30 == 0
        if (potionSet && (hpLow || timerPotion)) {
            phase = Phase.POTION; handler.post(loop); return
        }

        // Sotto attacco → difesa
        if (BotState.underAttack) {
            phase = Phase.DEFEND; handler.post(loop); return
        }

        // ── Movimento ─────────────────────────────────────────────────────────
        val useJoystick = cfg.joystickX > 0f && cfg.joystickY > 0f
        val useCamera   = cfg.cameraAreaX > 0f
        val doCamera    = useCamera && !hasTarget && huntCycles > 0 && (huntCycles % CAMERA_EVERY == 0)

        var t = 0L  // offset temporale cumulativo per le prossime azioni

        if (doCamera) {
            val cx = cfg.cameraAreaX; val cy = cfg.cameraAreaY; val r = cfg.cameraSwipeRange
            swipe(cx - cameraDir * r, cy, cx + cameraDir * r, cy, CAMERA_MS)
            cameraDir = -cameraDir
            t = CAMERA_MS + POST_MOVE_GAP_MS
        } else if (useJoystick) {
            val dir = PATROL_DIRS[patrolDir]
            joystickPush(
                cfg.joystickX, cfg.joystickY,
                cfg.joystickX + dir[0] * cfg.joystickRadius,
                cfg.joystickY + dir[1] * cfg.joystickRadius,
                JOYSTICK_MS
            )
            patrolSteps++
            if (patrolSteps >= PATROL_STEPS[patrolDir]) { patrolSteps = 0; patrolDir = (patrolDir + 1) % 4 }
            t = JOYSTICK_MS + POST_MOVE_GAP_MS
        }

        // ── Sequenza attacco/abilità (sequenziale, nessun conflitto) ──────────
        // Blocchiamo il target corrente per evitare che venga azzerato mentre lo usiamo
        val tx = targetX; val ty = targetY

        if (hasTarget) {
            handler.postDelayed({ if (BotState.isRunning) tap(tx, ty) }, t)
            t += TAP_MS + GAP_MS
        }

        // Attacco principale (sempre)
        handler.postDelayed({ if (BotState.isRunning) tap(cfg.attackX, cfg.attackY) }, t)
        t += TAP_MS + GAP_MS

        // Skill 1 – aggiorna cooldown subito per non sparare nel ciclo successivo
        if (cfg.skill1X > 0f && now - lastSkill1Ms >= cfg.skill1CooldownMs) {
            lastSkill1Ms = now
            val ft = t
            handler.postDelayed({ if (BotState.isRunning) tap(cfg.skill1X, cfg.skill1Y) }, ft)
            t += TAP_MS + GAP_MS
        }

        // Skill 2
        if (cfg.skill2X > 0f && now - lastSkill2Ms >= cfg.skill2CooldownMs) {
            lastSkill2Ms = now
            val ft = t
            handler.postDelayed({ if (BotState.isRunning) tap(cfg.skill2X, cfg.skill2Y) }, ft)
            t += TAP_MS + GAP_MS
        }

        // Skill 3
        if (cfg.skill3X > 0f && cfg.skill3Y > 0f && now - lastSkill3Ms >= cfg.skill3CooldownMs) {
            lastSkill3Ms = now
            val ft = t
            handler.postDelayed({ if (BotState.isRunning) tap(cfg.skill3X, cfg.skill3Y) }, ft)
            t += TAP_MS + GAP_MS
        }

        // Skill 4
        if (cfg.skill4X > 0f && cfg.skill4Y > 0f && now - lastSkill4Ms >= cfg.skill4CooldownMs) {
            lastSkill4Ms = now
            val ft = t
            handler.postDelayed({ if (BotState.isRunning) tap(cfg.skill4X, cfg.skill4Y) }, ft)
            t += TAP_MS + GAP_MS
        }

        // Skill 5
        if (cfg.skill5X > 0f && cfg.skill5Y > 0f && now - lastSkill5Ms >= cfg.skill5CooldownMs) {
            lastSkill5Ms = now
            val ft = t
            handler.postDelayed({ if (BotState.isRunning) tap(cfg.skill5X, cfg.skill5Y) }, ft)
            t += TAP_MS + GAP_MS
        }

        huntCycles++

        // Prossimo ciclo parte SOLO dopo che TUTTE le azioni sono finite + buffer
        handler.postDelayed(loop, t + BETWEEN_CYCLE_MS)
    }

    // ── DEFEND: spam attacco finché HP si stabilizza ───────────────────────────
    private fun doDefend(cfg: BotConfig) {
        val now = System.currentTimeMillis()

        if (!BotState.underAttack) {
            phase = Phase.HUNT; handler.postDelayed(loop, 100L); return
        }

        val potionSet = cfg.potionX > 0f
        val hpLow     = hpBarConfigured && scannedHpRatio in 0.01f..cfg.hpPotionThreshold
        if (potionSet && hpLow) {
            phase = Phase.POTION; handler.post(loop); return
        }

        var t = 0L

        // Attacco immediato
        tap(cfg.attackX, cfg.attackY)
        t += TAP_MS + GAP_MS

        if (cfg.skill1X > 0f && now - lastSkill1Ms >= cfg.skill1CooldownMs) {
            lastSkill1Ms = now
            val ft = t
            handler.postDelayed({ if (BotState.isRunning) tap(cfg.skill1X, cfg.skill1Y) }, ft)
            t += TAP_MS + GAP_MS
        }
        if (cfg.skill2X > 0f && now - lastSkill2Ms >= cfg.skill2CooldownMs) {
            lastSkill2Ms = now
            val ft = t
            handler.postDelayed({ if (BotState.isRunning) tap(cfg.skill2X, cfg.skill2Y) }, ft)
            t += TAP_MS + GAP_MS
        }
        if (cfg.skill3X > 0f && cfg.skill3Y > 0f && now - lastSkill3Ms >= cfg.skill3CooldownMs) {
            lastSkill3Ms = now
            val ft = t
            handler.postDelayed({ if (BotState.isRunning) tap(cfg.skill3X, cfg.skill3Y) }, ft)
            t += TAP_MS + GAP_MS
        }
        if (cfg.skill4X > 0f && cfg.skill4Y > 0f && now - lastSkill4Ms >= cfg.skill4CooldownMs) {
            lastSkill4Ms = now
            val ft = t
            handler.postDelayed({ if (BotState.isRunning) tap(cfg.skill4X, cfg.skill4Y) }, ft)
            t += TAP_MS + GAP_MS
        }
        if (cfg.skill5X > 0f && cfg.skill5Y > 0f && now - lastSkill5Ms >= cfg.skill5CooldownMs) {
            lastSkill5Ms = now
            val ft = t
            handler.postDelayed({ if (BotState.isRunning) tap(cfg.skill5X, cfg.skill5Y) }, ft)
            t += TAP_MS + GAP_MS
        }

        handler.postDelayed(loop, t + BETWEEN_CYCLE_MS)
    }

    // ── POTION ────────────────────────────────────────────────────────────────
    private fun doPotion(cfg: BotConfig) {
        tap(cfg.potionX, cfg.potionY)
        potionUses++
        phase = when {
            potionUses >= cfg.maxPotionsInSlot && cfg.backupPotionX > 0f -> Phase.REFILL
            BotState.underAttack -> Phase.DEFEND
            else -> Phase.HUNT
        }
        handler.postDelayed(loop, TAP_MS + 350L)
    }

    // ── REFILL ────────────────────────────────────────────────────────────────
    private fun doRefill(cfg: BotConfig) {
        swipe(cfg.backupPotionX, cfg.backupPotionY, cfg.potionX, cfg.potionY, 400L)
        potionUses = 0
        phase = if (BotState.underAttack) Phase.DEFEND else Phase.HUNT
        handler.postDelayed(loop, 700L)
    }

    // ── Primitive gesti ───────────────────────────────────────────────────────
    private fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_MS)).build(),
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
